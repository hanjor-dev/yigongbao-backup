package com.yigongbao.backup.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;


/** 保存配置前执行跨配置依赖和现场资源校验。 */
@Component
@RequiredArgsConstructor
public class ConfigurationChangeGuard {
    private final PersistentConfigService configs;
    private final Environment environment;

    public void validate(String key, String proposedValue) {
        if (key.startsWith("online.")) validateOnline(key, proposedValue);
        if (key.equals("storage.read-lease.ttl-seconds") || key.equals("storage.read-lease.heartbeat-seconds")) {
            int ttl = projectedInt("storage.read-lease.ttl-seconds", key, proposedValue, 120);
            int heartbeat = projectedInt("storage.read-lease.heartbeat-seconds", key, proposedValue, 20);
            if ((long) heartbeat * 2 >= ttl) {
                throw new IllegalArgumentException("读取租约心跳必须小于租约时长的一半");
            }
        }
        if (key.equals("supplement.max-file-size-bytes")) {
            long businessLimit = Long.parseLong(proposedValue);
            DataSize hardLimit = DataSize.parse(environment.getProperty("BACKUP_MAX_UPLOAD_SIZE", "2GB"));
            if (businessLimit > hardLimit.toBytes()) {
                throw new IllegalArgumentException("补传业务上限不能超过HTTP上传硬上限");
            }
        }
    }

    private void validateOnline(String changedKey, String proposedValue) {
        if (!Boolean.parseBoolean(projected("online.enabled", changedKey, proposedValue))) return;
        String baseUrl = projected("online.base-url", changedKey, proposedValue);
        if (baseUrl.isBlank()) throw new IllegalArgumentException("开启线上通信前必须配置线上系统地址");
        requireSecret("BACKUP_SHARED_HMAC_SECRET", "共享HMAC密钥");
    }

    private int projectedInt(String key, String changedKey, String proposedValue, int fallback) {
        String value = projected(key, changedKey, proposedValue);
        return value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private String projected(String key, String changedKey, String proposedValue) {
        if (key.equals(changedKey)) return proposedValue == null ? "" : proposedValue.trim();
        ConfigService.Entry entry = configs.get(key);
        return entry == null || entry.value() == null ? "" : entry.value().trim();
    }

    private void requireSecret(String reference, String name) {
        String value = reference.isBlank() ? null : environment.getProperty(reference);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "环境变量未配置: " + reference);
        }
        if (value.length() < 32) throw new IllegalArgumentException(name + "长度不能小于32位");
    }

}
