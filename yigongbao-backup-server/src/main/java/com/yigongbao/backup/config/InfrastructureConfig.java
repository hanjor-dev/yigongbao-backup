package com.yigongbao.backup.config;

import com.yigongbao.backup.cold.HotCleanupService;

	import com.yigongbao.backup.backup.CosObjectClient; import com.yigongbao.backup.cleanup.CleanupClient; import com.yigongbao.backup.cleanup.CleanupService; import com.yigongbao.backup.cleanup.PermitPollingService; import com.yigongbao.backup.cleanup.CleanupTaskService; import com.yigongbao.backup.cold.ColdBatchService; import com.yigongbao.backup.cold.ColdImportService; import com.yigongbao.backup.order.BkSupplementFileMapper; import com.yigongbao.backup.order.SupplementService; import com.yigongbao.backup.storage.api.StorageProbe; import com.yigongbao.backup.storage.api.ArtifactStore; import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore; import com.yigongbao.backup.storage.probe.DefaultStorageProbe; import com.yigongbao.backup.storage.validation.ManagedStoragePathPolicy; import org.springframework.beans.factory.ObjectProvider; import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration; import org.springframework.beans.factory.annotation.Value; import java.nio.file.Files; import java.nio.file.Path; import java.util.Arrays; import java.util.Set; import java.util.stream.Collectors;

/** 本地存储、探测器和线上清理客户端的默认基础设施装配。 */
@Configuration
public class InfrastructureConfig {
 @Bean @ConditionalOnMissingBean StorageProbe storageProbe(){return new DefaultStorageProbe();}
 @Bean @ConditionalOnMissingBean ArtifactStore artifactStore(@Value("${backup.storage.root:./data}") String root){return new LocalDirectoryArtifactStore(Path.of(root));}
 @Bean @ConditionalOnMissingBean ManagedStoragePathPolicy managedStoragePathPolicy(@Value("${backup.storage.managed-roots:./data,./cold-disks}") String roots){java.util.List<Path> paths=Arrays.stream(roots.split(",")).map(String::trim).filter(s->!s.isBlank()).map(Path::of).peek(path->{try{Files.createDirectories(path);}catch(java.io.IOException e){throw new IllegalStateException("无法创建受控存储根目录: "+path,e);}}).toList();return new ManagedStoragePathPolicy(paths);}
 /** 装配冷备写出服务；目标盘路径必须由管理员在请求中显式选择。 */
 @Bean @ConditionalOnMissingBean ColdBatchService coldBatchService(ArtifactStore store){return new ColdBatchService(store);}
 @Bean @ConditionalOnMissingBean HotCleanupService hotCleanupService(ArtifactStore store,com.yigongbao.backup.cold.ColdBatchArtifactMapper artifacts){return new HotCleanupService(store,artifacts);}
 /** 装配补传文件校验服务；扩展名和大小均由部署配置决定。 */
 @Bean @ConditionalOnMissingBean SupplementService supplementService(ArtifactStore store,BkSupplementFileMapper mapper,@Value("${backup.supplement.extensions:zip,step,stl,dcm}") String extensions,@Value("${backup.supplement.max-bytes:1073741824}") long maxBytes){Set<String> allowed=Arrays.stream(extensions.split(",")).map(String::trim).map(String::toLowerCase).filter(s->!s.isBlank()).collect(Collectors.toUnmodifiableSet());return new SupplementService(store,allowed,maxBytes,mapper);}
 @Bean @ConditionalOnMissingBean ColdImportService coldImportService(ArtifactStore store,@Value("${backup.cold.import-root:./cold-import}") String importRoot,@Value("${backup.cold.max-import-bytes:4398046511104}") long maxImportBytes){return new ColdImportService(Path.of(importRoot),store,maxImportBytes);}
 @Bean @ConditionalOnMissingBean(CleanupClient.class) @ConditionalOnProperty(prefix="backup.online", name="enabled", havingValue="false", matchIfMissing=true) CleanupClient cleanupClient(){return new CleanupClient(){public Evaluation evaluate(CleanupRequest r){throw new IllegalStateException("线上清理客户端未配置");} public Permit pollPermit(String t){throw new IllegalStateException("线上清理客户端未配置");} public void delete(Permit p,String k){throw new IllegalStateException("线上清理客户端未配置");}};}
	 @Bean PermitPollingService permitPollingService(CleanupClient client,ObjectProvider<CosObjectClient> cos,CleanupTaskService tasks){return new PermitPollingService(client,cos.getIfAvailable(),tasks);}
 @Bean CleanupService cleanupService(CleanupClient client,ObjectProvider<CosObjectClient> cos,CleanupTaskService tasks){return new CleanupService(client,cos.getIfAvailable(),tasks);}
}
