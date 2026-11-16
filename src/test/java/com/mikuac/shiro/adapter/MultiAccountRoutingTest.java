package com.mikuac.shiro.adapter;

import com.mikuac.shiro.common.utils.*;
import com.mikuac.shiro.constant.Connection;
import com.mikuac.shiro.core.*;
import com.mikuac.shiro.enums.*;
import com.mikuac.shiro.handler.*;
import com.mikuac.shiro.properties.*;
import com.mikuac.shiro.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiAccountRoutingTest {
    @ParameterizedTest
    @ValueSource(strings = {"text", "segments", "forward"})
    void eventSendRejectsAnotherBotsEventBeforeAnyAction(String type) {
        var actions = mock(ActionHandler.class);
        var bot = new Bot(71001, session("first", 71001), actions, null, null, null);
        var event = new com.mikuac.shiro.dto.event.message.AnyMessageEvent();
        event.setSelfId(71002L);
        event.setMessageType("group");
        event.setGroupId(72001L);
        assertThrows(IllegalArgumentException.class, () -> {
            switch (type) {
                case "text" -> bot.sendMsg(event, "fixture", false);
                case "segments" -> bot.sendMsg(event, java.util.List.of(), false);
                default -> bot.sendForwardMsg(event, java.util.List.of());
            }
        });
        verifyNoInteractions(actions);
        event.setSelfId(71001L);
        bot.sendMsg(event, "fixture", false);
        verify(actions).action(eq(bot.getSession()), eq(ActionPathEnum.SEND_GROUP_MSG), any());
    }

    static WebSocketSession session(String id, long selfId) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        if (selfId > 0) attributes.put(Connection.X_SELF_ID, selfId);
        attributes.put(Connection.ADAPTER_KEY, AdapterEnum.CLIENT);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    static Bot bot(long id, WebSocketSession session) {
        return new Bot(id, session, null, null, null, null);
    }

    static class Fixture {
        final BotContainer bots = new BotContainer();
        final CoreEvent events = mock(CoreEvent.class);
        final EventHandler handler = mock(EventHandler.class);
        final ActionHandler actions = mock(ActionHandler.class);
        final ShiroAsyncTask async = mock(ShiroAsyncTask.class);
        final BotFactory factory = mock(BotFactory.class);
        final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        final WebSocketClientHandler client;

        Fixture() {
            when(events.session(any())).thenReturn(true);
            when(factory.createBot(anyLong(), any())).thenAnswer(call -> bot(call.getArgument(0), call.getArgument(1)));
            doAnswer(call -> {
                call.<Runnable>getArgument(0).run();
                return null;
            }).when(executor).execute(any(Runnable.class));
            client = new WebSocketClientHandler(handler, factory, actions, async, bots, events, new WebSocketProperties(), executor);
        }
    }

    @Test
    void oneClientDisconnectCannotRemoveOtherAccountOrReplacement() throws Exception {
        var f = new Fixture();
        WebSocketSession first = session("first", 71001), second = session("second", 71002), replacement = session("replacement", 71001);
        f.client.afterConnectionEstablished(first);
        f.client.afterConnectionEstablished(second);
        f.client.afterConnectionClosed(first, CloseStatus.NORMAL);
        assertFalse(f.bots.robots.containsKey(71001L));
        assertTrue(f.bots.ownsSession(71002, second));
        f.client.afterConnectionEstablished(replacement);
        f.client.afterConnectionClosed(first, CloseStatus.NORMAL);
        assertTrue(f.bots.ownsSession(71001, replacement));
        verify(f.events, times(1)).offline(71001L);
        verify(f.events, never()).offline(71002L);
    }

    @Test
    void duplicateLiveIdentityCannotReplaceOwner() throws Exception {
        var f = new Fixture();
        WebSocketSession owner = session("owner", 71001), duplicate = session("duplicate", 71001);
        f.client.afterConnectionEstablished(owner);
        f.client.afterConnectionEstablished(duplicate);
        assertTrue(f.bots.ownsSession(71001, owner));
        verify(duplicate).close(CloseStatus.POLICY_VIOLATION);
        f.client.afterConnectionClosed(duplicate, CloseStatus.NORMAL);
        assertTrue(f.bots.ownsSession(71001, owner));
        verify(f.events, never()).offline(anyLong());
    }

    @Test
    void configuredIdentityIsVerifiedBeforeFirstDispatchAndOnLaterMessages() throws Exception {
        var f = new Fixture();
        var wrong = session("wrong", 0);
        wrong.getAttributes().put(ConnectionUtils.EXPECTED_SELF_ID, 71001L);
        f.client.handleMessage(wrong, new TextMessage("{\"self_id\":71002,\"post_type\":\"message\"}"));
        assertTrue(f.bots.robots.isEmpty());
        verify(wrong).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(f.async);
        var right = session("right", 0);
        right.getAttributes().put(ConnectionUtils.EXPECTED_SELF_ID, 71001L);
        f.client.handleMessage(right, new TextMessage("{\"self_id\":71001,\"post_type\":\"message\"}"));
        verify(f.async).execHandlerMsg(eq(f.handler), eq(71001L), eq(right), any());
        f.client.handleMessage(right, new TextMessage("{\"self_id\":71002,\"echo\":7}"));
        verify(right).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(f.actions);
    }

    @Test
    void queuedEventFromReplacedSessionIsDropped() {
        var bots = new BotContainer();
        WebSocketSession old = session("old", 71001), current = session("current", 71001);
        bots.robots.put(71001L, bot(71001, current));
        var events = mock(EventHandler.class);
        var task = new ShiroAsyncTask(bots);
        var payload = new JsonObjectWrapper();
        task.execHandlerMsg(events, 71001, old, payload);
        verifyNoInteractions(events);
        task.execHandlerMsg(events, 71001, current, payload);
        verify(events).handler(bots.robots.get(71001L), payload);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    void reverseDisconnectCannotRemoveReplacementEvenWhenCleanupWasAlreadyScheduled(int delay) {
        var f = new Fixture();
        var tasks = mock(ScheduledTask.class);
        var scheduled = mock(ScheduledThreadPoolExecutor.class);
        when(tasks.executor()).thenReturn(scheduled);
        var cleanup = new AtomicReference<Runnable>();
        when(scheduled.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> {
                    cleanup.set(call.getArgument(0));
                    return null;
                });
        var config = new ShiroProperties();
        config.setWaitBotConnect(delay);
        var server = new WebSocketServerHandler(f.handler, f.factory, f.actions, f.async, f.bots,
                f.events, new WebSocketProperties(), tasks, config, f.executor);
        WebSocketSession old = session("old", 71001), current = session("current", 71001), other = session("other", 71002);
        f.bots.robots.put(71001L, bot(71001, old));
        f.bots.robots.put(71002L, bot(71002, other));
        if (delay > 0) server.afterConnectionClosed(old, CloseStatus.NORMAL);
        f.bots.robots.put(71001L, bot(71001, current));
        if (cleanup.get() != null) cleanup.get().run();
        server.afterConnectionClosed(old, CloseStatus.NORMAL);
        assertTrue(f.bots.ownsSession(71001, current));
        assertTrue(f.bots.ownsSession(71002, other));
        verify(f.events, never()).offline(anyLong());
    }

    @Test
    void actionReceiptFromWrongAccountCannotConsumeTheRightCallback() throws Exception {
        var actions = new ActionHandler(new WebSocketProperties(), new RateLimiterProperties(), null);
        WebSocketSession first = session("first", 71001), second = session("second", 71002);
        doAnswer(call -> {
            var sent = JsonObjectWrapper.parseObject(call.<TextMessage>getArgument(0).getPayload());
            var wrong = new JsonObjectWrapper();
            wrong.put("echo", sent.get("echo"));
            wrong.put("account", 71002L);
            actions.onReceiveActionResp(second, wrong);
            var right = new JsonObjectWrapper();
            right.put("echo", sent.get("echo"));
            right.put("account", 71001L);
            actions.onReceiveActionResp(first, right);
            return null;
        }).when(first).sendMessage(any());
        var result = actions.rawAction(first, ActionPathEnum.GET_LOGIN_INFO, Map.of());
        assertEquals(71001L, result.get("account"));
        verify(second, never()).sendMessage(any());
    }
}
