package com.yigongbao.backup.storage.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 将浏览器提交的路径限制在服务端配置的受控存储根目录内。 */
public final class ManagedStoragePathPolicy {
    private final List<Path> managedRoots;

    public ManagedStoragePathPolicy(List<Path> managedRoots) {
        if (managedRoots == null || managedRoots.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个受控存储根目录");
        }
        this.managedRoots = managedRoots.stream().map(ManagedStoragePathPolicy::existingRealPath).distinct().toList();
    }

    public Path requireManaged(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        Path candidate = existingRealPath(Path.of(value));
        if (candidate.getParent() == null) {
            throw new IllegalArgumentException(fieldName + "不得为文件系统根目录");
        }
        boolean contained = managedRoots.stream().anyMatch(candidate::startsWith);
        if (!contained) {
            throw new IllegalArgumentException(fieldName + "不在服务端受控存储范围内");
        }
        return candidate;
    }

    private static Path existingRealPath(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("存储目录不存在或不是目录: " + normalized);
            }
            return normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法验证存储目录", exception);
        }
    }
}
