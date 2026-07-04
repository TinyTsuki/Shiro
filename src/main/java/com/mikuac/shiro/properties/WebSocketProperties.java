package com.mikuac.shiro.properties;

import com.mikuac.shiro.enums.ActionPath;
import com.mikuac.shiro.enums.ActionPathEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 正向 ws 配置
 *
 * @author Zero
 */
@Data
@Component
@ConfigurationProperties(prefix = "shiro.ws")
public class WebSocketProperties {

    /**
     * 访问密钥, 强烈推荐在公网的服务器设置
     */
    private String accessToken = "";

    /**
     * 认证约定
     */
    private String authSchema = "Bearer";

    /**
     * 是否启用约定
     */
    private Boolean enableAuthSchema = true;

    /**
     * 超时回收，10秒
     */
    private Integer timeout = 10;

    /**
     * 文件上传超时，20分钟
     */
    private Integer uploadTimeout = 1200;

    /**
     * 按 OneBot action 配置超时时间，优先级高于内置上传超时
     */
    private Map<String, Integer> actionTimeouts = new HashMap<>();

    /**
     * 最大文本消息缓冲区 512KB
     */
    private Integer maxTextMessageBufferSize = 512000;

    /**
     * 二进制消息的最大长度 512KB
     */
    private Integer maxBinaryMessageBufferSize = 512000;

    /**
     * 获取指定 action 的响应等待超时
     *
     * @param action OneBot action
     * @return 超时时间，单位秒
     */
    public int getTimeout(ActionPath action) {
        if (action == null) {
            return normalizedTimeout(timeout);
        }
        Integer configured = actionTimeouts == null ? null : actionTimeouts.get(action.getPath());
        if (configured != null) {
            return normalizedTimeout(configured);
        }
        if (action == ActionPathEnum.UPLOAD_GROUP_FILE || action == ActionPathEnum.UPLOAD_PRIVATE_FILE) {
            return normalizedTimeout(uploadTimeout);
        }
        return normalizedTimeout(timeout);
    }

    private int normalizedTimeout(Integer value) {
        if (value != null && value > 0) {
            return value;
        }
        return timeout != null && timeout > 0 ? timeout : 10;
    }

    /**
     * 获取访问密钥
     *
     * @return 密钥
     */
    public String getAccessToken() {
        if (Boolean.TRUE.equals(enableAuthSchema) && !Objects.equals(accessToken, "")) {
            return authSchema + " " + accessToken;
        }
        if (Boolean.TRUE.equals(enableAuthSchema) && Objects.equals(accessToken, "")) {
            return "";
        }
        return accessToken;
    }

}
