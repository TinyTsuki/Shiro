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

    private static final int DEFAULT_UPLOAD_TIMEOUT = 3600;

    /**
     * 文件上传响应等待上限，单位秒，默认一小时
     */
    private Integer uploadTimeout = DEFAULT_UPLOAD_TIMEOUT;

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
        Integer configured = action == null || actionTimeouts == null ? null : actionTimeouts.get(action.getPath());
        if (configured != null && configured > 0) return configured;
        if (isFileUpload(action)) {
            return uploadTimeout != null && uploadTimeout > 0 ? uploadTimeout : DEFAULT_UPLOAD_TIMEOUT;
        }
        return timeout != null && timeout > 0 ? timeout : 10;
    }

    public static boolean isFileUpload(ActionPath action) {
        return action != null && (ActionPathEnum.UPLOAD_GROUP_FILE.getPath().equals(action.getPath())
                || ActionPathEnum.UPLOAD_PRIVATE_FILE.getPath().equals(action.getPath()));
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
