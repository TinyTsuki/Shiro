package com.mikuac.shiro.adapter;

import com.mikuac.shiro.common.utils.ConnectionUtils;
import com.mikuac.shiro.properties.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.WebSocketClient;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedWebSocketClientTest {
    @Test
    void lateCompletionCannotOverwriteNewConnectionOwnership() {
        class DeferredCallback extends CompletableFuture<WebSocketSession> {
            java.util.function.BiConsumer<? super WebSocketSession, ? super Throwable> callback;

            @Override
            public CompletableFuture<WebSocketSession> whenComplete(java.util.function.BiConsumer<? super WebSocketSession, ? super Throwable> callback) {
                this.callback = callback;
                return this;
            }
        }
        var transport = mock(WebSocketClient.class);
        var oldAttempt = new DeferredCallback();
        var newAttempt = new CompletableFuture<WebSocketSession>();
        when(transport.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class))).thenReturn(oldAttempt, newAttempt);
        var old = MultiAccountRoutingTest.session("old", 71001);
        when(old.isOpen()).thenReturn(false);
        var current = MultiAccountRoutingTest.session("new", 71001);
        try (var connection = new ManagedWebSocketClient(transport, mock(WebSocketHandler.class),
                new WebSocketClientProperties.Endpoint(71001, "ws://127.0.0.1:12001", "fixture", 5))) {
            connection.connectIfNeeded();
            oldAttempt.complete(old);
            connection.connectIfNeeded();
            newAttempt.complete(current);
            oldAttempt.callback.accept(old, null);
            connection.connectIfNeeded();
            verify(transport, times(2)).execute(any(), any(WebSocketHttpHeaders.class), any(URI.class));
        }
    }

    @Test
    void legacySettingsAndExplicitCredentialsResolveIndependently() {
        var settings = new WebSocketClientProperties();
        settings.setUrl("ws://127.0.0.1:12001");
        var global = new WebSocketProperties();
        global.setAccessToken("shared-fixture");
        assertEquals("Bearer shared-fixture", settings.resolveEndpoints(global).getFirst().authorization());
        var first = account(71001, "ws://127.0.0.1:12001", "first-fixture");
        var second = account(71002, "wss://fixture.invalid/socket", "second-fixture");
        settings.setAccounts(List.of(first, second));
        var resolved = settings.resolveEndpoints(global);
        assertEquals(List.of(71001L, 71002L), resolved.stream().map(WebSocketClientProperties.Endpoint::selfId).toList());
        assertEquals(List.of("Bearer first-fixture", "Bearer second-fixture"), resolved.stream().map(WebSocketClientProperties.Endpoint::authorization).toList());
        assertFalse(resolved.toString().contains("fixture"));
        assertThrows(UnsupportedOperationException.class, () -> resolved.clear());
        second.setAccessToken(null);
        assertThrows(IllegalArgumentException.class, () -> settings.resolveEndpoints(global));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fixture.invalid", "ws://user:secret@fixture.invalid", "ws://fixture.invalid/#secret", "ws://fixture.invalid:0", "invalid"})
    void invalidEndpointsAreRejectedWithoutExposingValues(String url) {
        var settings = new WebSocketClientProperties();
        settings.setAccounts(List.of(account(71001, url, "secret-fixture")));
        var error = assertThrows(IllegalArgumentException.class, () -> settings.resolveEndpoints(new WebSocketProperties()));
        assertFalse(error.toString().contains("secret"));
        assertNull(error.getCause());
    }

    @Test
    void duplicateIdentityAndEndpointAreRejected() {
        var settings = new WebSocketClientProperties();
        var first = account(71001, "ws://127.0.0.1:12001", "first");
        var second = account(71001, "ws://127.0.0.1:12002", "second");
        settings.setAccounts(List.of(first, second));
        assertThrows(IllegalArgumentException.class, () -> settings.resolveEndpoints(new WebSocketProperties()));
        second.setSelfId(71002);
        second.setUrl(first.getUrl());
        assertThrows(IllegalArgumentException.class, () -> settings.resolveEndpoints(new WebSocketProperties()));
    }

    @Test
    void pendingHandshakeDoesNotDuplicateAndShutdownClosesLateSuccess() throws Exception {
        var transport = mock(WebSocketClient.class);
        var delegate = mock(WebSocketHandler.class);
        var pending = new CompletableFuture<WebSocketSession>();
        when(transport.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class))).thenReturn(pending);
        var connection = new ManagedWebSocketClient(transport, delegate,
                new WebSocketClientProperties.Endpoint(71001, "ws://127.0.0.1:12001", "Bearer first-fixture", 5));
        connection.connectIfNeeded();
        connection.connectIfNeeded();
        var handler = ArgumentCaptor.forClass(WebSocketHandler.class);
        var headers = ArgumentCaptor.forClass(WebSocketHttpHeaders.class);
        verify(transport).execute(handler.capture(), headers.capture(), eq(URI.create("ws://127.0.0.1:12001")));
        assertEquals("Bearer first-fixture", headers.getValue().getFirst("Authorization"));
        connection.close();
        var late = MultiAccountRoutingTest.session("late", 0);
        handler.getValue().afterConnectionEstablished(late);
        pending.complete(late);
        verify(late, atLeastOnce()).close();
        verifyNoInteractions(delegate);
        connection.connectIfNeeded();
        verifyNoMoreInteractions(transport);
    }

    @Test
    void rejectedIdentityRetiresOnlyThatConnectionsRetry() {
        var transport = mock(WebSocketClient.class);
        var session = MultiAccountRoutingTest.session("wrong-account", 71002);
        session.getAttributes().put(ConnectionUtils.IDENTITY_REJECTED, true);
        when(transport.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        try (var connection = new ManagedWebSocketClient(transport, mock(WebSocketHandler.class),
                new WebSocketClientProperties.Endpoint(71001, "ws://127.0.0.1:12001", "fixture", 5))) {
            connection.connectIfNeeded();
            connection.connectIfNeeded();
            connection.connectIfNeeded();
            verify(transport, times(1)).execute(any(), any(WebSocketHttpHeaders.class), any(URI.class));
        }
    }

    @Test
    void failedAccountDoesNotPreventOtherConnectionAndCredentialsStayScoped() throws Exception {
        var transport = mock(WebSocketClient.class);
        var pending = new CompletableFuture<WebSocketSession>();
        when(transport.execute(any(), any(WebSocketHttpHeaders.class), eq(URI.create("ws://127.0.0.1:12001"))))
                .thenThrow(new IllegalStateException("fixture transport"));
        when(transport.execute(any(), any(WebSocketHttpHeaders.class), eq(URI.create("ws://127.0.0.1:12002"))))
                .thenReturn(pending);
        var delegate = mock(WebSocketHandler.class);
        try (var first = new ManagedWebSocketClient(transport, delegate, new WebSocketClientProperties.Endpoint(71001, "ws://127.0.0.1:12001", "Bearer first", 5));
             var second = new ManagedWebSocketClient(transport, delegate, new WebSocketClientProperties.Endpoint(71002, "ws://127.0.0.1:12002", "Bearer second", 5))) {
            first.connectIfNeeded();
            second.connectIfNeeded();
            first.connectIfNeeded();
            var handler = ArgumentCaptor.forClass(WebSocketHandler.class);
            var headers = ArgumentCaptor.forClass(WebSocketHttpHeaders.class);
            verify(transport).execute(handler.capture(), headers.capture(), eq(URI.create("ws://127.0.0.1:12002")));
            assertEquals("Bearer second", headers.getValue().getFirst("Authorization"));
            var session = MultiAccountRoutingTest.session("second", 0);
            handler.getValue().afterConnectionEstablished(session);
            assertEquals(71002L, session.getAttributes().get(ConnectionUtils.EXPECTED_SELF_ID));
            pending.complete(session);
            second.connectIfNeeded();
            verify(delegate).afterConnectionEstablished(session);
        }
    }

    static WebSocketClientProperties.ClientAccount account(long id, String url, String token) {
        var account = new WebSocketClientProperties.ClientAccount();
        account.setSelfId(id);
        account.setUrl(url);
        account.setAccessToken(token);
        return account;
    }
}
