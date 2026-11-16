package com.mikuac.shiro.core;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.socket.WebSocketSession;

/**
 * Created on 2021/7/7.
 *
 * @author Zero
 * @version $Id: $Id
 */
@Component
public class BotContainer {

    /**
     * Bot容器
     */
    public final Map<Long, Bot> robots = new ConcurrentHashMap<>();

    public boolean ownsSession(long selfId, WebSocketSession session) {
        Bot bot = robots.get(selfId);
        return bot != null && bot.getSession() == session;
    }

    public Bot removeSession(long selfId, WebSocketSession session) {
        AtomicReference<Bot> removed = new AtomicReference<>();
        robots.computeIfPresent(selfId, (id, bot) -> {
            if (bot.getSession() != session) return bot;
            removed.set(bot);
            return null;
        });
        return removed.get();
    }

}
