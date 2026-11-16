package com.mikuac.shiro.adapter;

import com.mikuac.shiro.common.utils.ConnectionUtils;
import com.mikuac.shiro.properties.WebSocketClientProperties.Endpoint;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;

/**
 * One account's connection attempt and shutdown ownership.
 */
@Slf4j
public final class ManagedWebSocketClient implements AutoCloseable {
    private final WebSocketClient client;
    private final WebSocketHandler handler;
    private final Endpoint endpoint;
    private CompletableFuture<WebSocketSession> pending;
    private WebSocketSession session;
    private boolean closed;

    public ManagedWebSocketClient(WebSocketClient client, WebSocketHandler delegate, Endpoint endpoint) {
        this.client = client;
        this.endpoint = endpoint;
        this.handler = new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession connected) throws Exception {
                synchronized (ManagedWebSocketClient.this) {
                    if (closed) {
                        connected.close();
                        return;
                    }
                    if (endpoint.selfId() > 0)
                        connected.getAttributes().put(ConnectionUtils.EXPECTED_SELF_ID, endpoint.selfId());
                    session = connected;
                    connected.getAttributes().put("authorization", endpoint.authorization());
                    super.afterConnectionEstablished(connected);
                }
            }
        };
    }

    public synchronized void connectIfNeeded() {
        if (session != null && Boolean.TRUE.equals(session.getAttributes().get(ConnectionUtils.IDENTITY_REJECTED))) {
            close();
            return;
        }
        if (closed || pending != null && !pending.isDone() || session != null && session.isOpen()) return;
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (!endpoint.authorization().isEmpty()) headers.set("Authorization", endpoint.authorization());
        try {
            var attempt = client.execute(handler, headers, URI.create(endpoint.url()));
            pending = attempt;
            attempt.whenComplete((connected, failure) -> {
                synchronized (ManagedWebSocketClient.this) {
                    if (pending != attempt) {
                        if (connected != null && connected != session) closeSession(connected);
                        return;
                    }
                    if (failure != null)
                        log.debug("Client connection attempt failed account={} type={}", endpoint.selfId(), failure.getClass().getSimpleName());
                    if (connected == null) return;
                    if (closed) closeSession(connected);
                    else session = connected;
                }
            });
        } catch (RuntimeException failure) {
            // One endpoint failure must not cancel its scheduled reconnect task.
            log.debug("Client connection attempt failed account={} type={}", endpoint.selfId(), failure.getClass().getSimpleName());
            pending = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (session != null) closeSession(session);
        // Keep the callback: a late successful handshake must be closed too.
    }

    private static void closeSession(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // Shutdown does not retry requests.
        }
    }
}
