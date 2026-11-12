package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.Job;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.HttpServletRequest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;

/** 普通用户订单补传接口；每个上传任务在创建时锁定实际写入热备卷。 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/supplements")
@RequiredArgsConstructor
public class SupplementController {
    private final SupplementService supplements;
    private final OrderSnapshotMapper snapshots;
    private final VolumeArtifactStore store;
    private final ActiveVolumeSelectionService activeVolumes;
    private final PersistentJobCoordinator jobs;
    private final PersistentJobService jobService;
    private final ObjectMapper mapper;
    private final StorageVolumeReadLeaseService readLeases;
    private final SupplementStagingService stagingFiles;
    private final AuditLogService audit;

    @GetMapping
    public ApiResponse<List<SupplementService.SupplementInfo>> list(@PathVariable("orderId") String orderId) throws IOException {
        StpUtil.checkLogin();
        SnapshotRef ref = snapshot(orderId);
        return ApiResponse.ok(supplements.listForSnapshot(ref.id(), ref.code()), requestId());
    }

    /** 将上传流暂存于目标卷，再创建带目标卷 ID 的后台上传任务。 */
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<UploadTask> upload(@PathVariable("orderId") String orderId,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        StpUtil.checkLogin();
        if (file.isEmpty()) throw new IllegalArgumentException("补传文件为空");
        String safeFileName = supplements.validateUpload(file.getOriginalFilename(), file.getSize());
        SnapshotRef ref = snapshot(orderId);
        long actor = StpUtil.getLoginIdAsLong();
        StorageVolumeEntity volume = activeVolumes.selectActiveForNewBackup(file.getSize());
        Path staged = stagingFiles.create();
        try {
            file.transferTo(staged);
            SupplementUploadJobHandler.Payload payload = new SupplementUploadJobHandler.Payload(ref.id(), ref.code(),
                    safeFileName, file.getSize(), file.getContentType(), null, staged.toString(), volume.getId());
            String config = mapper.writeValueAsString(payload);
            String stable = ref.id() + "\n" + file.getOriginalFilename() + "\n" + file.getSize() + "\n" + sha256(staged);
            JobEntity job = jobs.create("SUPPLEMENT_UPLOAD", JobIdempotency.key("SUPPLEMENT_UPLOAD", actor, stable),
                    actor, config, volume.getId());
            if (!Objects.equals(job.getConfigSnapshotJson(), config)) stagingFiles.delete(staged);
            return ApiResponse.ok(new UploadTask(job.getId(), job.getJobNo(), job.getStatus(), safeFileName, file.getSize()), requestId());
        } catch (Exception exception) {
            stagingFiles.delete(staged);
            if (exception instanceof IOException io) throw io;
            throw new IllegalStateException("补传任务创建失败", exception);
        }
    }

    @GetMapping("/tasks/{jobId}")
    public ApiResponse<Job> task(@PathVariable("orderId") String orderId, @PathVariable("jobId") long jobId) throws Exception {
        StpUtil.checkLogin();
        SnapshotRef ref = snapshot(orderId);
        Job job = jobService.get(jobId);
        if (!"SUPPLEMENT_UPLOAD".equals(job.getJobType()) || !Objects.equals(job.getRequestedBy(), StpUtil.getLoginIdAsLong())) {
            throw new IllegalArgumentException("补传任务不存在");
        }
        SupplementUploadJobHandler.Payload payload = mapper.readValue(jobService.configJson(jobId), SupplementUploadJobHandler.Payload.class);
        if (payload.snapshotId() != ref.id()) throw new IllegalArgumentException("补传任务与订单不匹配");
        return ApiResponse.ok(job, requestId());
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<?> download(@PathVariable("orderId") String orderId, @PathVariable("fileId") String fileId,
                                      HttpServletRequest request) throws IOException {
        StpUtil.checkLogin();
        StorageVolumeReadLeaseService.LeasedArtifact lease = null;
        try {
            SnapshotRef ref = snapshot(orderId);
            BkSupplementFileEntity file = supplements.requireForSnapshot(ref.id(), fileId);
            lease = readLeases.acquire("SUPPLEMENT_FILE", file.getId(), "DOWNLOAD",
                    "user:" + StpUtil.getLoginIdAsLong() + ":" + UUID.randomUUID(), readLeases.defaultTtl());
            var replica = lease.replica();
            Path path = store.resolve(replica.storageVolumeId(), replica.storageKey());
            if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
            var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ);
            try {
                verifySha256(channel, file.getSha256());
                lease.heartbeat();
                channel.position(0L);
            } catch (RuntimeException | IOException exception) {
                readLeases.markDamaged(lease,"DOWNLOAD_CHECKSUM_FAILED",exception.getMessage(),StpUtil.getLoginIdAsLong());
                channel.close();
                throw exception;
            }
            StorageVolumeReadLeaseService.LeasedArtifact heldLease = lease;
            lease = null;
            var input = readLeases.wrap(java.nio.channels.Channels.newInputStream(channel),heldLease);
            ResponseEntity<?> response = ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileId + "\"")
                    .contentLength(channel.size()).body(new InputStreamResource(input));
            audit.appendActor(request, StpUtil.getLoginIdAsLong(), "USER", "SUPPLEMENT_DOWNLOAD",
                    "SUPPLEMENT_FILE", String.valueOf(file.getId()), null, "OPENED", null);
            return response;
        } catch (StorageVolumeUnavailableException exception) {
            audit.appendActor(request, StpUtil.getLoginIdAsLong(), "USER", "SUPPLEMENT_DOWNLOAD",
                    "SUPPLEMENT_FILE", fileId, null, "FAILED", exception.getMessage());
            return ResponseEntity.status(409).body(ApiResponse.error(
                    "STORAGE_VOLUME_OFFLINE", exception.getMessage(), requestId()));
        } catch (NoSuchFileException | FileNotFoundException exception) {
            audit.appendActor(request, StpUtil.getLoginIdAsLong(), "USER", "SUPPLEMENT_DOWNLOAD",
                    "SUPPLEMENT_FILE", fileId, null, "FAILED", "文件不存在");
            return ResponseEntity.notFound().build();
        } finally {
            if (lease != null) lease.close();
        }
    }

    private static void verifySha256(java.nio.channels.FileChannel channel, String expected) throws IOException {
        if (expected == null || expected.isBlank()) throw new IllegalStateException("补传文件缺少 SHA-256，拒绝下载");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) >= 0) { buffer.flip(); digest.update(buffer); buffer.clear(); }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected))
                throw new IllegalStateException("补传文件 SHA-256 校验失败，拒绝下载");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境不支持 SHA-256", impossible);
        }
    }

    private SnapshotRef snapshot(String id) {
        if (id == null || !id.matches("[0-9]{1,19}")) throw new IllegalArgumentException("订单标识非法");
        OrderSnapshotEntity snapshot = snapshots.selectById(Long.parseLong(id));
        if (snapshot == null || snapshot.getOrderCode() == null) throw new IllegalArgumentException("订单不存在");
        return new SnapshotRef(snapshot.getId(), snapshot.getOrderCode());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requestId() { return UUID.randomUUID().toString(); }
    private record SnapshotRef(long id, String code) { }
    public record UploadTask(Long jobId, String jobNo, String status, String fileName, long size) { }
}
