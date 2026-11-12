package com.yigongbao.backup.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从数据库读取线上连接策略，并通过引用名从环境中解析真实密钥。
 * 数据库从不保存或返回 HMAC Secret、Bearer Token 明文。
 */
@Component
@RequiredArgsConstructor
public class OnlineConnectionSettingsProvider {
    private final RuntimeConfigService configs;
    private final Environment environment;

    /** 构建一次远程请求使用的不可变配置快照。 */
    public Settings current() {
        if (!configs.getBoolean("online.enabled", false)) {
            throw new IllegalStateException("线上通信已禁用，请在系统配置中开启 online.enabled");
        }
        String baseUrl = configs.require("online.base-url");
        URI baseUri;
        try {
            baseUri = URI.create(baseUrl);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("线上系统地址必须是有效的 HTTP URL");
        }
        if (baseUri.getHost() == null || !("http".equalsIgnoreCase(baseUri.getScheme())
                || "https".equalsIgnoreCase(baseUri.getScheme()))) {
            throw new IllegalStateException("线上系统地址必须是有效的 HTTP URL");
        }
        String secret = secret("BACKUP_SHARED_HMAC_SECRET", "共享 HMAC 密钥");
        Set<String> externalAllowedHosts = Arrays.stream(environment.getProperty(
                        "BACKUP_EXTERNAL_ALLOWED_HOSTS", "").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        if (externalAllowedHosts.isEmpty()) {
            throw new IllegalStateException("对象存储预签名地址允许主机未配置，请设置环境变量 "
                    + "BACKUP_EXTERNAL_ALLOWED_HOSTS（多个主机使用英文逗号分隔）");
        }
        return new Settings(baseUri,
                Duration.ofSeconds(configs.getLong("online.connect-timeout-seconds", 10L, 1L)),
                Duration.ofSeconds(configs.getLong("online.request-timeout-seconds", 60L, 1L)),
                Duration.ofSeconds(configs.getLong("online.file-transfer-timeout-seconds", 7200L, 60L)),
                secret, externalAllowedHosts);
    }

    private String secret(String reference, String displayName) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException(displayName + "引用未配置");
        }
        String value = environment.getProperty(reference.trim());
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(displayName + "环境变量未配置: " + reference.trim());
        }
        if (value.length() < 32) throw new IllegalStateException(displayName + "长度不能小于32位");
        return value;
    }

    /** 单次调用使用的线上连接快照。 */
    public record Settings(URI baseUri, Duration connectTimeout, Duration requestTimeout,
                           Duration fileTransferTimeout,
                           String sharedSecret, Set<String> externalAllowedHosts) { }
}
