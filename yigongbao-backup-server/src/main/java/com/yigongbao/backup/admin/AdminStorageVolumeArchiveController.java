package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.storage.archive.*;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
import com.yigongbao.backup.storage.volume.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 整盘冷备封存、离线和故障切换管理接口。 */
@RestController
@RequestMapping("/api/v1/admin/storage-volumes")
@RequiredArgsConstructor
public class AdminStorageVolumeArchiveController {
    private final VolumeArchivePreviewService previews; private final VolumeArchiveService archives;
    private final VolumeArchiveMapper archiveMapper; private final VolumeArchiveItemMapper itemMapper;
    private final StorageVolumeReadLeaseService leases; private final StorageVolumeLifecycleService lifecycle;
    private final StorageVolumeMapper volumeMapper;

    @GetMapping("/{id}/archive-preview")
    public ApiResponse<VolumeArchivePreviewService.ArchivePreview> preview(@PathVariable long id,
            @RequestParam(required=false) Long successorVolumeId) { admin(); return ApiResponse.ok(previews.preview(id,successorVolumeId),requestId()); }

    @PostMapping("/{id}/archive")
    public ApiResponse<JobEntity> start(@PathVariable long id,@RequestBody ArchiveCommand command) {
        admin(); return ApiResponse.ok(archives.start(new VolumeArchiveService.StartArchiveCommand(id,
                command.successorVolumeId(),command.archiveNote(),command.offlineLabelNote(),command.physicalLocation(),command.idempotencyKey()),
                StpUtil.getLoginIdAsLong()),requestId());
    }

    @GetMapping("/{id}/archives")
    public ApiResponse<List<VolumeArchiveEntity>> history(@PathVariable long id) { admin(); return ApiResponse.ok(archiveMapper.selectList(
            Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getSourceVolumeId,id)
                    .eq(VolumeArchiveEntity::getIsDeleted,0).orderByDesc(VolumeArchiveEntity::getId)),requestId()); }

    /** 冷备管理页的独立封存尝试列表；不混入订单备份任务。 */
    @GetMapping("/archives")
    public ApiResponse<List<ArchiveListItem>> listArchives() {
        admin();
        List<VolumeArchiveEntity> attempts=archiveMapper.selectList(Wrappers.<VolumeArchiveEntity>lambdaQuery()
                .eq(VolumeArchiveEntity::getIsDeleted,0).orderByDesc(VolumeArchiveEntity::getId));
        List<Long> archiveIds=attempts.stream().map(VolumeArchiveEntity::getId).filter(Objects::nonNull).toList();
        Map<Long,Long> verifiedCounts=archiveIds.isEmpty()?Map.of():itemMapper.selectList(
                        Wrappers.<VolumeArchiveItemEntity>lambdaQuery()
                                .in(VolumeArchiveItemEntity::getArchiveId,archiveIds)
                                .eq(VolumeArchiveItemEntity::getVerificationStatus,"VERIFIED")
                                .eq(VolumeArchiveItemEntity::getIsDeleted,0)).stream()
                .collect(Collectors.groupingBy(VolumeArchiveItemEntity::getArchiveId,Collectors.counting()));
        List<Long> volumeIds=attempts.stream().flatMap(value->java.util.stream.Stream.of(
                        value.getSourceVolumeId(),value.getSuccessorVolumeId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long,StorageVolumeEntity> volumes=volumeIds.isEmpty()?Map.of():volumeMapper.selectBatchIds(volumeIds).stream()
                .collect(Collectors.toMap(StorageVolumeEntity::getId,Function.identity()));
        List<ArchiveListItem> result=attempts.stream()
                .sorted(java.util.Comparator.comparing(VolumeArchiveEntity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(value->new ArchiveListItem(value,VolumeSummary.from(volumes.get(value.getSourceVolumeId())),
                        VolumeSummary.from(volumes.get(value.getSuccessorVolumeId())),
                        verifiedCounts.getOrDefault(value.getId(),0L))).toList();
        return ApiResponse.ok(result,requestId());
    }

    @GetMapping("/archives/{archiveId}")
    public ApiResponse<ArchiveDetail> detail(@PathVariable long archiveId) { admin(); VolumeArchiveEntity archive=archives.required(archiveId);
        List<VolumeArchiveItemEntity> items=itemMapper.selectList(Wrappers.<VolumeArchiveItemEntity>lambdaQuery()
                .eq(VolumeArchiveItemEntity::getArchiveId,archiveId).eq(VolumeArchiveItemEntity::getIsDeleted,0).orderByAsc(VolumeArchiveItemEntity::getId));
        return ApiResponse.ok(new ArchiveDetail(archive,items),requestId()); }

    @PostMapping("/archives/{archiveId}/abandon")
    public ApiResponse<VolumeArchiveEntity> abandon(@PathVariable long archiveId,@RequestBody(required=false) AbandonCommand command) {
        admin(); return ApiResponse.ok(archives.abandon(archiveId,StpUtil.getLoginIdAsLong(),command==null?null:command.reason()),requestId());
    }

    @PostMapping("/{id}/prepare-offline")
    public ApiResponse<StorageVolumeEntity> offline(@PathVariable long id) { admin(); return ApiResponse.ok(leases.beginOffline(id,StpUtil.getLoginIdAsLong()),requestId()); }

    @PostMapping("/{id}/failover")
    public ApiResponse<StorageVolumeEntity> failover(@PathVariable long id,@RequestBody FailoverCommand command) { admin();
        return ApiResponse.ok(lifecycle.failover(id,command.standbyVolumeId(),StpUtil.getLoginIdAsLong()),requestId()); }

    private static void admin(){StpUtil.checkRole("ADMIN");} private static String requestId(){return UUID.randomUUID().toString();}
    public record ArchiveCommand(Long successorVolumeId,String archiveNote,String offlineLabelNote,String physicalLocation,String idempotencyKey){}
    public record FailoverCommand(long standbyVolumeId){}
    public record AbandonCommand(String reason){}
    public record ArchiveDetail(VolumeArchiveEntity archive,List<VolumeArchiveItemEntity> items){}
    public record ArchiveListItem(VolumeArchiveEntity archive,VolumeSummary sourceVolume,VolumeSummary successorVolume,
                                  long verifiedFileCount){}
    public record VolumeSummary(Long id,String volumeCode,String displayName,String tier,String mountStatus,
                                String writeStatus,String operationStatus){
        static VolumeSummary from(StorageVolumeEntity value){return value==null?null:new VolumeSummary(value.getId(),
                value.getVolumeCode(),value.getDisplayName(),value.getTier(),value.getMountStatus(),
                value.getWriteStatus(),value.getOperationStatus());}
    }
}
