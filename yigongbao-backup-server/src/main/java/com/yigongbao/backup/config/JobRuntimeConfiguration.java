package com.yigongbao.backup.config;

import com.yigongbao.backup.job.*;
import com.yigongbao.backup.restore.RestoreJobHandler;
import com.yigongbao.backup.cold.*;
import com.yigongbao.backup.cleanup.*;
import com.yigongbao.backup.order.SupplementUploadJobHandler;
import com.yigongbao.backup.backup.ManualBackupJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 持久化任务执行器装配。 */
@Configuration
public class JobRuntimeConfiguration {
 @Bean JobExecutorRegistry jobExecutorRegistry(RestoreJobHandler restore,ColdWriteJobHandler coldWrite,ColdImportJobHandler coldImport,HotCleanupJobHandler hotCleanup,CleanupDryRunJobHandler cleanupDry,CleanupExecuteJobHandler cleanupExecute,SupplementUploadJobHandler supplement,ManualBackupJobHandler backup){return new JobExecutorRegistry(Map.of("RESTORE",restore,"COLD_WRITE",coldWrite,"COLD_IMPORT",coldImport,"HOT_CLEANUP",hotCleanup,"CLEANUP_DRY_RUN",cleanupDry,"CLEANUP_EXECUTE",cleanupExecute,"SUPPLEMENT_UPLOAD",supplement,"MANUAL_BACKUP",backup));}
 @Bean JobDispatcher jobDispatcher(PersistentJobCoordinator coordinator,JobExecutorRegistry registry,JobEvidenceService evidence,@Value("${backup.jobs.owner:}") String configuredOwner,@Value("${backup.jobs.lease-seconds:60}") long lease,@Value("${backup.jobs.max-retries:3}") int retries){String owner=configuredOwner==null||configuredOwner.isBlank()?"backup-"+UUID.randomUUID():configuredOwner;return new JobDispatcher(coordinator,registry,evidence,owner,Duration.ofSeconds(lease),retries);}
}
