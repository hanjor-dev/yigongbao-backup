package com.yigongbao.backup.config;

import com.yigongbao.backup.job.*;
import com.yigongbao.backup.restore.RestoreJobHandler;
import com.yigongbao.backup.cold.*;
import com.yigongbao.backup.cleanup.*;
import com.yigongbao.backup.order.SupplementUploadJobHandler;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import com.yigongbao.backup.storage.archive.VolumeArchiveJobHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
import java.util.UUID;

/** 持久化任务执行器装配。 */
@Configuration
public class JobRuntimeConfiguration {
 @Bean JobExecutorRegistry jobExecutorRegistry(RestoreJobHandler restore,CleanupDryRunJobHandler cleanupDry,CleanupExecuteJobHandler cleanupExecute,AutomaticCleanupJobHandler automaticCleanup,SupplementUploadJobHandler supplement,ManualBackupJobHandler backup,VolumeArchiveJobHandler archive){return new JobExecutorRegistry(Map.ofEntries(Map.entry("RESTORE",restore),Map.entry("VOLUME_ARCHIVE",archive),Map.entry("CLEANUP_DRY_RUN",cleanupDry),Map.entry("CLEANUP_EXECUTE",cleanupExecute),Map.entry("AUTO_CLEANUP",automaticCleanup),Map.entry("SUPPLEMENT_UPLOAD",supplement),Map.entry("MANUAL_BACKUP",backup),Map.entry("ORDER_BACKUP",backup),Map.entry("DEGRADED_BACKUP",backup)));}
 @Bean JobDispatcher jobDispatcher(PersistentJobCoordinator coordinator,JobExecutorRegistry registry,JobEvidenceService evidence,JobExecutionLogService executionLogs,RuntimeConfigService runtimeConfig){String owner="backup-"+UUID.randomUUID();return new JobDispatcher(coordinator,registry,evidence,executionLogs,owner,runtimeConfig);}
}
