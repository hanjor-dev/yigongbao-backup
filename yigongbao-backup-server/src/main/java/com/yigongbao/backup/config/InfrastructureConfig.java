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
import com.yigongbao.backup.order.OrderPackageMapper;
import com.yigongbao.backup.storage.location.ArtifactCopyMapper;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.order.BkSupplementFileMapper;
import com.yigongbao.backup.order.SupplementService;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import com.yigongbao.backup.storage.volume.ActiveVolumeArtifactStore;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
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

    /**
     * 兼容仍依赖 ArtifactStore 的组件；每次调用解析当前 ACTIVE 卷，禁止启动时绑定配置路径。
     */
    @Bean
    @ConditionalOnMissingBean
    ArtifactStore artifactStore(ActiveVolumeSelectionService selection, VolumeArtifactStore volumes) {
        return new ActiveVolumeArtifactStore(selection, volumes);
    }

    @Bean
    @ConditionalOnMissingBean
    ColdBatchService coldBatchService(ArtifactStore store) {
        return new ColdBatchService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    HotCleanupService hotCleanupService(VolumeArtifactStore store, ColdBatchArtifactMapper artifacts,
                                        OrderPackageMapper packages, ArtifactCopyMapper copies, StorageVolumeMapper volumeMapper) {
        return new HotCleanupService(store, artifacts, packages, copies, volumeMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    SupplementService supplementService(VolumeArtifactStore store, BkSupplementFileMapper mapper,
                                         RuntimeConfigService runtimeConfig, StorageVolumeMapper volumes,
                                         ArtifactCopyMapper copies, ArtifactReplicaResolver replicas) {
        return new SupplementService(store, mapper, runtimeConfig, volumes, copies, replicas);
    }

    @Bean
    @ConditionalOnMissingBean
    ColdImportService coldImportService(com.yigongbao.backup.storage.volume.StorageVolumeMapper volumes,
                                        ActiveVolumeSelectionService activeVolumes, VolumeArtifactStore volumeStore,
                                        ArtifactCopyMapper copies, OrderPackageMapper packages, RuntimeConfigService configs) {
        return new ColdImportService(volumes, activeVolumes, volumeStore, copies, packages, 4L * 1024 * 1024 * 1024 * 1024, configs);
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
