package com.yigongbao.backup.config;

import com.yigongbao.backup.job.*;
import com.yigongbao.backup.restore.RestoreJobHandler;
import com.yigongbao.backup.cold.*;
import com.yigongbao.backup.cleanup.*;
import com.yigongbao.backup.order.SupplementUploadJobHandler;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
import java.util.UUID;

/** 持久化任务执行器装配。 */
@Configuration
public class JobRuntimeConfiguration {
 @Bean JobExecutorRegistry jobExecutorRegistry(RestoreJobHandler restore,ColdWriteJobHandler coldWrite,ColdImportJobHandler coldImport,HotCleanupJobHandler hotCleanup,CleanupDryRunJobHandler cleanupDry,CleanupExecuteJobHandler cleanupExecute,AutomaticCleanupJobHandler automaticCleanup,SupplementUploadJobHandler supplement,ManualBackupJobHandler backup){return new JobExecutorRegistry(Map.of("RESTORE",restore,"COLD_WRITE",coldWrite,"COLD_IMPORT",coldImport,"HOT_CLEANUP",hotCleanup,"CLEANUP_DRY_RUN",cleanupDry,"CLEANUP_EXECUTE",cleanupExecute,"AUTO_CLEANUP",automaticCleanup,"SUPPLEMENT_UPLOAD",supplement,"MANUAL_BACKUP",backup,"ORDER_BACKUP",backup));}
 @Bean JobDispatcher jobDispatcher(PersistentJobCoordinator coordinator,JobExecutorRegistry registry,JobEvidenceService evidence,RuntimeConfigService runtimeConfig){String owner="backup-"+UUID.randomUUID();return new JobDispatcher(coordinator,registry,evidence,owner,runtimeConfig);}
}
