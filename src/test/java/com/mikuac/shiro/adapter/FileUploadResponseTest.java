package com.mikuac.shiro.adapter;

import com.mikuac.shiro.common.utils.JsonObjectWrapper;
import com.mikuac.shiro.enums.ActionPath;
import com.mikuac.shiro.enums.ActionPathEnum;
import com.mikuac.shiro.handler.ActionHandler;
import com.mikuac.shiro.properties.RateLimiterProperties;
import com.mikuac.shiro.properties.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.TextMessage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileUploadResponseTest {
    @ParameterizedTest
    @ValueSource(strings = {"upload_group_file", "upload_private_file"})
    void uploadPolicyUsesProtocolNameAndValidOverrides(String path) {
        var config = new WebSocketProperties();
        ActionPath raw = () -> path;
        assertEquals(3600, config.getTimeout(raw));
        config.setTimeout(2);
        config.setUploadTimeout(45);
        assertEquals(45, config.getTimeout(raw));
        config.setActionTimeouts(Map.of(path, 7));
        assertEquals(7, config.getTimeout(raw));
        config.setActionTimeouts(Map.of(path, -1));
        assertEquals(45, config.getTimeout(raw));
        config.setUploadTimeout(null);
        assertEquals(3600, config.getTimeout(raw));
        assertEquals(2, config.getTimeout(ActionPathEnum.GET_LOGIN_INFO));
    }

    @Test
    void uploadWaitsBeyondOrdinaryTimeoutAndOnlyItsSessionCanEndTheWait() throws Exception {
        var config = new WebSocketProperties();
        config.setTimeout(1);
        config.setUploadTimeout(30);
        var actions = new ActionHandler(config, new RateLimiterProperties(), null);
        var first = MultiAccountRoutingTest.session("upload-A", 71001);
        var second = MultiAccountRoutingTest.session("upload-B", 71002);
        var sent = new CompletableFuture<JsonObjectWrapper>();
        var otherSent = new CompletableFuture<Boolean>();
        doAnswer(call -> sent.complete(JsonObjectWrapper.parseObject(call.<TextMessage>getArgument(0).getPayload())))
                .when(first).sendMessage(any());
        doAnswer(call -> otherSent.complete(true)).when(second).sendMessage(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var upload = pool.submit(() -> actions.rawAction(first, () -> "upload_group_file", Map.of()));
            var parallel = pool.submit(() -> actions.action(second, ActionPathEnum.UPLOAD_PRIVATE_FILE, Map.of()));
            var receipt = sent.get(5, TimeUnit.SECONDS);
            otherSent.get(5, TimeUnit.SECONDS);
            actions.onSessionClosed(second);
            assertEquals("unknown", parallel.get(2, TimeUnit.SECONDS).get("status"));
            assertThrows(TimeoutException.class, () -> upload.get(1200, TimeUnit.MILLISECONDS));
            receipt.put("status", "ok");
            actions.onReceiveActionResp(first, receipt);
            assertEquals("ok", upload.get(2, TimeUnit.SECONDS).get("status"));
            verify(first, times(1)).sendMessage(any());
        }
    }

    @Test
    void missingUploadReceiptIsUnknownAndAnExplicitRefusalStaysFailed() throws Exception {
        var config = new WebSocketProperties();
        config.setUploadTimeout(1);
        var actions = new ActionHandler(config, new RateLimiterProperties(), null);
        var session = MultiAccountRoutingTest.session("timeout", 71001);
        assertEquals("unknown", actions.action(session, ActionPathEnum.UPLOAD_PRIVATE_FILE, Map.of()).get("status"));
        doAnswer(call -> {
            var reply = JsonObjectWrapper.parseObject(call.<TextMessage>getArgument(0).getPayload());
            reply.put("status", "failed");
            actions.onReceiveActionResp(session, reply);
            return null;
        }).when(session).sendMessage(any());
        assertEquals("failed", actions.action(session, ActionPathEnum.UPLOAD_PRIVATE_FILE, Map.of()).get("status"));
        verify(session, times(2)).sendMessage(any());
    }
}
