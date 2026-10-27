package com.yigongbao.backup.storage;

import com.yigongbao.backup.storage.validation.ManagedStoragePathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedStoragePathPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsOnlyPathsInsideConfiguredManagedRoots() throws Exception {
        Path managed = Files.createDirectories(temporary.resolve("managed"));
        Path selected = Files.createDirectories(managed.resolve("cold-01"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        ManagedStoragePathPolicy policy = new ManagedStoragePathPolicy(List.of(managed));

        assertThat(policy.requireManaged(selected.toString(), "冷备目录")).isEqualTo(selected.toRealPath());
        assertThatThrownBy(() -> policy.requireManaged(outside.toString(), "冷备目录"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控");
    }

    @Test
    void rejectsTraversalAndSymbolicLinkEscape() throws Exception {
        Path managed = Files.createDirectories(temporary.resolve("managed"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        ManagedStoragePathPolicy policy = new ManagedStoragePathPolicy(List.of(managed));

        assertThatThrownBy(() -> policy.requireManaged(managed.resolve("../outside").toString(), "存储目录"))
                .isInstanceOf(IllegalArgumentException.class);

        Path link = managed.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            return; // 当前文件系统不支持创建测试符号链接。
        }
        assertThatThrownBy(() -> policy.requireManaged(link.toString(), "存储目录"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控");
    }

    @Test
    void rejectsFilesystemRootEvenWhenMisconfiguredAsManaged() {
        Path filesystemRoot = temporary.toAbsolutePath().getRoot();
        ManagedStoragePathPolicy policy = new ManagedStoragePathPolicy(List.of(filesystemRoot));

        assertThatThrownBy(() -> policy.requireManaged(filesystemRoot.toString(), "存储目录"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("根目录");
    }
}
