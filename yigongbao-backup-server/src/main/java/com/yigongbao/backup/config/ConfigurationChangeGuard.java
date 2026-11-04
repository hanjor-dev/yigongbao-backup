package com.yigongbao.backup.config;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 保存配置前执行跨配置依赖和现场资源校验。 */
@Component
@RequiredArgsConstructor
public class ConfigurationChangeGuard {
    private final PersistentConfigService configs;
    private final Environment environment;

    public void validate(String key, String proposedValue) {
        if (key.startsWith("online.")) validateOnline(key, proposedValue);
        if (key.equals("storage.hot-root") || key.equals("cold.import-root")) {
            validateDirectory(Path.of(proposedValue), key);
        }
        if (key.equals("storage.managed-roots")) {
            Arrays.stream(proposedValue.split(",")).map(String::trim).filter(value -> !value.isBlank())
                    .map(Path::of).forEach(path -> validateDirectory(path, key));
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

    private static void validateDirectory(Path path, String key) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || !Files.isReadable(normalized) || !Files.isWritable(normalized)) {
            throw new IllegalArgumentException(key + "对应目录不存在或不可读写: " + normalized);
        }
    }
}
