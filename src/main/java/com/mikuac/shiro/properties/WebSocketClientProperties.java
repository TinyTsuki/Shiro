package com.mikuac.shiro.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 正向 ws 配置
 *
 * @author Zero
 */
@Data
@Component
@ConfigurationProperties(prefix = "shiro.ws.client")
public class WebSocketClientProperties {

    /**
     * 是否启用正向 Websocket 连接
     */
    private Boolean enable = false;

    /**
     * ws地址
     */
    private String url = "";

    /**
     * 断线重连时间（秒）
     */
    private Integer reconnectInterval = 5;

    private List<ClientAccount> accounts = new ArrayList<>();

    @Getter
    @Setter
    public static class ClientAccount {
        private long selfId;
        private String url;
        private String accessToken;
        private Integer reconnectInterval;
    }

    public record Endpoint(long selfId, String url, String authorization, int reconnectInterval) {
        @Override
        public String toString() {
            return "Endpoint[selfId=" + selfId + "]";
        }
    }

    /**
     * A nonempty account list replaces the legacy single connection.
     */
    public List<Endpoint> resolveEndpoints(WebSocketProperties defaults) {
        if (accounts == null || accounts.isEmpty()) {
            return List.of(endpoint(0, url, defaults.getAccessToken(), reconnectInterval));
        }
        var ids = new HashSet<Long>();
        var urls = new HashSet<String>();
        var endpoints = new ArrayList<Endpoint>();
        for (ClientAccount account : accounts) {
            if (account == null || account.selfId <= 0 || !ids.add(account.selfId)
                    || account.accessToken == null)
                throw new IllegalArgumentException("Invalid client account identity or credential");
            String token = account.accessToken;
            if (Boolean.TRUE.equals(defaults.getEnableAuthSchema()) && !token.isEmpty())
                token = defaults.getAuthSchema() + " " + token;
            Endpoint endpoint = endpoint(account.selfId, account.url, token,
                    account.reconnectInterval == null ? reconnectInterval : account.reconnectInterval);
            if (!urls.add(endpoint.url())) throw new IllegalArgumentException("Duplicate client endpoint");
            endpoints.add(endpoint);
        }
        return List.copyOf(endpoints);
    }

    private static Endpoint endpoint(long id, String url, String token, Integer interval) {
        try {
            URI uri = URI.create(url);
            if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || interval == null || interval < 1 || token == null || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IllegalArgumentException();
            }
            return new Endpoint(id, uri.normalize().toString(), token, interval);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid WebSocket client endpoint configuration");
        }
    }

}
