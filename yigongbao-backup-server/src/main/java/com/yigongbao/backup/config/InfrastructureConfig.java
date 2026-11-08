package com.yigongbao.backup.config;

import com.yigongbao.backup.backup.CosObjectClient;
import com.yigongbao.backup.cleanup.CleanupClient;
import com.yigongbao.backup.cleanup.CleanupService;
import com.yigongbao.backup.cleanup.CleanupTaskService;
import com.yigongbao.backup.cleanup.PermitPollingService;
import com.yigongbao.backup.cold.ColdBatchArtifactMapper;
import com.yigongbao.backup.cold.ColdBatchService;
import com.yigongbao.backup.cold.ColdImportService;
import com.yigongbao.backup.cold.HotCleanupService;
import com.yigongbao.backup.order.BkSupplementFileMapper;
import com.yigongbao.backup.order.SupplementService;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import com.yigongbao.backup.storage.probe.DefaultStorageProbe;
import com.yigongbao.backup.storage.validation.ManagedStoragePathPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 本地存储、探测器和线上清理客户端的基础设施装配。 */
@Configuration
public class InfrastructureConfig {
    @Bean
    @ConditionalOnMissingBean
    StorageProbe storageProbe() {
        return new DefaultStorageProbe();
    }

    /** 热备/NAS根目录以数据库为唯一来源；配置缺失时拒绝启动相关基础设施。 */
    @Bean
    @ConditionalOnMissingBean
    ArtifactStore artifactStore(RuntimeConfigService configs) {
        Path root = ensureDirectory(Path.of(configs.require("storage.hot-root")), "热备存储根目录");
        return new LocalDirectoryArtifactStore(root);
    }

    /** 受控路径白名单从数据库读取，避免YAML与管理页面配置不一致。 */
    @Bean
    @ConditionalOnMissingBean
    ManagedStoragePathPolicy managedStoragePathPolicy(RuntimeConfigService configs) {
        List<Path> roots = Arrays.stream(configs.require("storage.managed-roots").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(Path::of)
                .map(path -> ensureDirectory(path, "受控存储根目录")).toList();
        if (roots.isEmpty()) throw new IllegalStateException("受控存储根目录不能为空");
        return new ManagedStoragePathPolicy(roots);
    }

    @Bean
    @ConditionalOnMissingBean
    ColdBatchService coldBatchService(ArtifactStore store) {
        return new ColdBatchService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    HotCleanupService hotCleanupService(ArtifactStore store, ColdBatchArtifactMapper artifacts) {
        return new HotCleanupService(store, artifacts);
    }

    @Bean
    @ConditionalOnMissingBean
    SupplementService supplementService(VolumeArtifactStore store, BkSupplementFileMapper mapper,
                                         RuntimeConfigService runtimeConfig) {
        return new SupplementService(store, mapper, runtimeConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    ColdImportService coldImportService(ArtifactStore store, RuntimeConfigService configs) {
        Path importRoot = ensureDirectory(Path.of(configs.require("cold.import-root")), "冷备导入区目录");
        return new ColdImportService(importRoot, store, configs);
    }

    @Bean
    PermitPollingService permitPollingService(CleanupClient client, CosObjectClient cos,
                                               CleanupTaskService tasks) {
        return new PermitPollingService(client, cos, tasks);
    }

    @Bean
    CleanupService cleanupService(CleanupClient client, CosObjectClient cos,
                                  CleanupTaskService tasks, RuntimeConfigService configs) {
        return new CleanupService(client, cos, tasks, configs);
    }

    private static Path ensureDirectory(Path path, String name) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            if (!Files.isDirectory(normalized) || !Files.isReadable(normalized) || !Files.isWritable(normalized)) {
                throw new IllegalStateException(name + "不可读写: " + normalized);
            }
            return normalized;
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建或访问" + name + ": " + path, exception);
        }
    }
}
