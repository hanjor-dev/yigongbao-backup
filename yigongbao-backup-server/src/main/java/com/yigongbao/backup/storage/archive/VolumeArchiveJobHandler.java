package com.yigongbao.backup.storage.archive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.job.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

/** 可从已持久化 checkpoint 恢复的逐文件封存校验任务。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VolumeArchiveJobHandler implements JobHandler {
    private final VolumeArchiveMapper archiveMapper; private final VolumeArchiveItemMapper itemMapper;
    private final VolumeArchiveVerifier verifier; private final VolumeArchiveService service;
    @Override public void execute(JobExecutionContext context, JobEntity job) {
        VolumeArchiveEntity archive = archiveMapper.selectOne(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                .eq(VolumeArchiveEntity::getJobId, job.getId()).eq(VolumeArchiveEntity::getIsDeleted, 0).last("LIMIT 1"));
        if (archive == null) throw new IllegalStateException("封存任务缺少 attempt");
        if("SUCCEEDED".equals(archive.getPhase()))return;
        List<VolumeArchiveItemEntity> items=itemMapper.selectList(Wrappers.<VolumeArchiveItemEntity>lambdaQuery()
                .eq(VolumeArchiveItemEntity::getArchiveId,archive.getId()).eq(VolumeArchiveItemEntity::getIsDeleted,0).orderByAsc(VolumeArchiveItemEntity::getId));
        long progress=0;
        try {
            context.safePoint("ARCHIVE_START", 0);
            if(archiveMapper.updatePhaseOwned(archive.getId(),"VERIFYING",LocalDateTime.now(),context.owner(),context.fencingToken())!=1)throw new IllegalStateException("封存阶段更新时租约已失效");
            context.taskInfo("ARCHIVE_VERIFY_STARTED", "VERIFYING", "开始校验冷备封存清单", java.util.Map.of("archiveId", archive.getId(), "fileCount", items.size()));
            for (VolumeArchiveItemEntity item:items) {
                if ("VERIFIED".equals(item.getVerificationStatus())) { progress++; continue; }
                context.safePoint("VERIFYING", progress);
                VolumeArchiveVerifier.VerificationResult result=verifier.verify(item);
                context.safePoint("VERIFYING", progress);
                int updated=itemMapper.updateVerificationOwned(item.getId(), item.getVersion(), result.actualSize(),
                        result.actualSha256(), LocalDateTime.now(), result.success()?"VERIFIED":"FAILED",
                        result.code(), result.message(), context.owner(), context.fencingToken());
                if(updated!=1) throw new IllegalStateException("封存校验结果落库时租约已失效");
                if(!result.success()) throw new IllegalStateException(result.code()+"："+result.message());
                if(archiveMapper.updateCheckpointOwned(archive.getId(),item.getId(),context.owner(),context.fencingToken())!=1)throw new IllegalStateException("封存 checkpoint 更新时租约已失效"); context.safePoint("VERIFYING",++progress);
            }
            // 即使是恢复执行时已标记 VERIFIED 的项目，也必须在最终提交窗口重新读取文件。
            for (VolumeArchiveItemEntity item:items) {
                context.safePoint("FINAL_VERIFY", progress);
                VolumeArchiveVerifier.VerificationResult result=verifier.verify(item);
                if(!result.success()) throw new IllegalStateException("FINAL_"+result.code()+"："+result.message());
            }
            verifierCatalogCheck(archive, items);
            context.safePoint("COMMITTING", progress);
            if(archiveMapper.updatePhaseOwned(archive.getId(),"COMMITTING",null,context.owner(),context.fencingToken())!=1)throw new IllegalStateException("封存提交阶段更新时租约已失效");
            try {
                service.complete(archive.getId(),context.owner(),context.fencingToken());
                context.taskInfo("ARCHIVE_SUCCEEDED", "COMMITTED", "冷备封存提交完成", java.util.Map.of("archiveId", archive.getId()));
                log.info("冷备封存成功 jobId={} archiveId={} sourceVolumeId={} files={}", job.getId(), archive.getId(), archive.getSourceVolumeId(), items.size());
            } catch (RuntimeException commitFailure) {
                service.commitFailed(archive.getId(), new VolumeArchiveService.ArchiveFailure("ARCHIVE_COMMIT_FAILED", safeMessage(commitFailure)), context.owner(),context.fencingToken());
                context.taskError("ARCHIVE_COMMIT_FAILED", "COMMITTING", safeMessage(commitFailure), java.util.Map.of("archiveId", archive.getId()));
                log.error("冷备封存提交失败 jobId={} archiveId={} sourceVolumeId={}", job.getId(), archive.getId(), archive.getSourceVolumeId(), commitFailure);
                throw new CommitFailure(commitFailure);
            }
        } catch (JobCancelledException cancelled) {
            service.cancel(archive.getId(), cancelled.getMessage(), context.owner(),context.fencingToken());
            context.taskWarn("ARCHIVE_CANCELLED", "CANCELLED", cancelled.getMessage(), java.util.Map.of("archiveId", archive.getId()));
            throw cancelled;
        } catch (CommitFailure failure) {
            throw failure.original;
        } catch (RuntimeException failure) {
            service.fail(archive.getId(),new VolumeArchiveService.ArchiveFailure("ARCHIVE_VERIFY_FAILED",safeMessage(failure)),context.owner(),context.fencingToken());
            context.taskError("ARCHIVE_VERIFY_FAILED", "VERIFYING", safeMessage(failure), java.util.Map.of("archiveId", archive.getId()));
            log.error("冷备封存校验失败 jobId={} archiveId={} sourceVolumeId={}", job.getId(), archive.getId(), archive.getSourceVolumeId(), failure);
            throw failure;
        }
    }
    private void verifierCatalogCheck(VolumeArchiveEntity archive, List<VolumeArchiveItemEntity> items) {
        // 由 Spring 注入的 verifier 已完成内容校验；清单服务在提交前复核孤立文件和异常副本。
        service.validateFrozenManifest(archive.getId(), items);
    }
    private static String safeMessage(Throwable failure) { return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
    private static final class CommitFailure extends RuntimeException { private final RuntimeException original; private CommitFailure(RuntimeException original){super(original);this.original=original;} }
}
