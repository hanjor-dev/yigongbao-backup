package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
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
    private final OrderSnapshotMapper snapshotMapper;
    private final StorageVolumeAbnormalStateRepairService abnormalStateRepairs;

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
    public ApiResponse<Page<ArchiveListItem>> listArchives(
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size) {
        admin();
        long safeCurrent=Math.max(1,current);long safeSize=Math.min(100,Math.max(1,size));
        Page<VolumeArchiveEntity> attemptPage=archiveMapper.selectPage(new Page<>(safeCurrent,safeSize),
                Wrappers.<VolumeArchiveEntity>lambdaQuery().eq(VolumeArchiveEntity::getIsDeleted,0)
                        .orderByDesc(VolumeArchiveEntity::getId));
        List<VolumeArchiveEntity> attempts=attemptPage.getRecords();
        List<Long> archiveIds=attempts.stream().map(VolumeArchiveEntity::getId).filter(Objects::nonNull).toList();
        Map<Long,Long> verifiedCounts=archiveIds.isEmpty()?Map.of():itemMapper.countVerifiedByArchiveIds(archiveIds)
                .stream().collect(Collectors.toMap(VolumeArchiveItemMapper.ArchiveVerifiedCount::archiveId,
                        value->value.verifiedCount()==null?0L:value.verifiedCount()));
        List<Long> volumeIds=attempts.stream().flatMap(value->java.util.stream.Stream.of(
                        value.getSourceVolumeId(),value.getSuccessorVolumeId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long,StorageVolumeEntity> volumes=volumeIds.isEmpty()?Map.of():volumeMapper.selectBatchIds(volumeIds).stream()
                .collect(Collectors.toMap(StorageVolumeEntity::getId,Function.identity()));
        List<ArchiveListItem> records=attempts.stream()
                .map(value->new ArchiveListItem(value,VolumeSummary.from(volumes.get(value.getSourceVolumeId())),
                        VolumeSummary.from(volumes.get(value.getSuccessorVolumeId())),
                        verifiedCounts.getOrDefault(value.getId(),0L))).toList();
        Page<ArchiveListItem> result=new Page<>(attemptPage.getCurrent(),attemptPage.getSize(),attemptPage.getTotal());
        result.setRecords(records);
        return ApiResponse.ok(result,requestId());
    }

    @GetMapping("/archives/{archiveId}")
    public ApiResponse<ArchiveDetail> detail(@PathVariable long archiveId,
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size) {
        admin();
        VolumeArchiveEntity archive=archives.required(archiveId);
        long safeCurrent=Math.max(1,current); long safeSize=Math.min(100,Math.max(1,size));
        Page<VolumeArchiveItemEntity> items=itemMapper.selectPage(new Page<>(safeCurrent,safeSize),
                Wrappers.<VolumeArchiveItemEntity>lambdaQuery()
                        .eq(VolumeArchiveItemEntity::getArchiveId,archiveId)
                        .eq(VolumeArchiveItemEntity::getIsDeleted,0)
                        .orderByAsc(VolumeArchiveItemEntity::getId));
        fillOrderCodes(items.getRecords());
        return ApiResponse.ok(new ArchiveDetail(archive,ArchiveItemPage.from(items)),requestId());
    }

    private void fillOrderCodes(List<VolumeArchiveItemEntity> items) {
        List<Long> snapshotIds=items.stream().map(VolumeArchiveItemEntity::getSnapshotId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long,String> bySnapshotId=snapshotIds.isEmpty()?Map.of():snapshotMapper.selectBatchIds(snapshotIds).stream()
                .filter(value->value.getId()!=null&&value.getOrderCode()!=null)
                .collect(Collectors.toMap(OrderSnapshotEntity::getId,OrderSnapshotEntity::getOrderCode,(left,right)->left));
        items.forEach(item->item.setOrderCode(bySnapshotId.get(item.getSnapshotId())));
        List<Long> unresolvedSourceIds=items.stream().filter(item->item.getOrderCode()==null)
                .map(VolumeArchiveItemEntity::getSourceOrderId).filter(Objects::nonNull).distinct().toList();
        if(unresolvedSourceIds.isEmpty()) return;
        Map<Long,String> bySourceId=snapshotMapper.selectList(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                        .in(OrderSnapshotEntity::getSourceOrderId,unresolvedSourceIds)
                        .eq(OrderSnapshotEntity::getIsDeleted,0).orderByDesc(OrderSnapshotEntity::getId)).stream()
                .filter(value->value.getSourceOrderId()!=null&&value.getOrderCode()!=null)
                .collect(Collectors.toMap(OrderSnapshotEntity::getSourceOrderId,OrderSnapshotEntity::getOrderCode,
                        (newest,older)->newest));
        items.stream().filter(item->item.getOrderCode()==null)
                .forEach(item->item.setOrderCode(bySourceId.get(item.getSourceOrderId())));
    }

    @PostMapping("/archives/{archiveId}/abandon")
    public ApiResponse<VolumeArchiveEntity> abandon(@PathVariable long archiveId,@RequestBody(required=false) AbandonCommand command) {
        admin(); return ApiResponse.ok(archives.abandon(archiveId,StpUtil.getLoginIdAsLong(),command==null?null:command.reason()),requestId());
    }

    /** 仅解除已证明文件完整的封存流程异常，不允许绕过文件校验失败。 */
    @PostMapping("/{id}/repair-abnormal-state")
    public ApiResponse<StorageVolumeEntity> repairAbnormalState(@PathVariable long id,
            @RequestBody RepairAbnormalStateCommand command) {
        admin();
        return ApiResponse.ok(abnormalStateRepairs.repair(id, StpUtil.getLoginIdAsLong(),
                command == null ? null : command.reason()), requestId());
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
    public record RepairAbnormalStateCommand(String reason){}
    public record ArchiveDetail(VolumeArchiveEntity archive,ArchiveItemPage items){}
    public record ArchiveItemPage(List<VolumeArchiveItemEntity> records,long total,long current,long size,long pages){
        static ArchiveItemPage from(Page<VolumeArchiveItemEntity> page){return new ArchiveItemPage(
                page.getRecords(),page.getTotal(),page.getCurrent(),page.getSize(),page.getPages());}
    }
    public record ArchiveListItem(VolumeArchiveEntity archive,VolumeSummary sourceVolume,VolumeSummary successorVolume,
                                  long verifiedFileCount){}
    public record VolumeSummary(Long id,String volumeCode,String displayName,String tier,String mountStatus,
                                String writeStatus,String operationStatus){
        static VolumeSummary from(StorageVolumeEntity value){return value==null?null:new VolumeSummary(value.getId(),
                value.getVolumeCode(),value.getDisplayName(),value.getTier(),value.getMountStatus(),
                value.getWriteStatus(),value.getOperationStatus());}
    }
}
