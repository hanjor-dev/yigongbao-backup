package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.Job;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.volume.ActiveVolumeSelectionService;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
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

/** 普通用户订单补传接口；每个上传任务在创建时锁定实际写入热备卷。 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/supplements")
@RequiredArgsConstructor
public class SupplementController {
    private static final String STAGING_DIRECTORY = ".staging/supplements";
    private final SupplementService supplements;
    private final OrderSnapshotMapper snapshots;
    private final VolumeArtifactStore store;
    private final ActiveVolumeSelectionService activeVolumes;
    private final PersistentJobCoordinator jobs;
    private final PersistentJobService jobService;
    private final ObjectMapper mapper;

    @GetMapping
    public ApiResponse<List<SupplementService.SupplementInfo>> list(@PathVariable("orderId") String orderId) throws IOException {
        StpUtil.checkLogin();
        SnapshotRef ref = snapshot(orderId);
        return ApiResponse.ok(supplements.listForSnapshot(ref.id(), ref.code()), requestId());
    }

    /** 将上传流暂存于目标卷，再创建带目标卷 ID 的后台上传任务。 */
    @PostMapping
    public ApiResponse<UploadTask> upload(@PathVariable("orderId") String orderId,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        StpUtil.checkLogin();
        if (file.isEmpty()) throw new IllegalArgumentException("补传文件为空");
        SnapshotRef ref = snapshot(orderId);
        long actor = StpUtil.getLoginIdAsLong();
        StorageVolumeEntity volume = activeVolumes.requireActiveHotVolume();
        Path staging = store.resolve(volume.getId(), STAGING_DIRECTORY);
        Files.createDirectories(staging);
        Path staged = Files.createTempFile(staging, "upload-", ".part");
        try {
            file.transferTo(staged);
            SupplementUploadJobHandler.Payload payload = new SupplementUploadJobHandler.Payload(ref.id(), ref.code(),
                    file.getOriginalFilename(), file.getSize(), file.getContentType(), null, staged.toString(), volume.getId());
            String config = mapper.writeValueAsString(payload);
            String stable = ref.id() + "\n" + file.getOriginalFilename() + "\n" + file.getSize() + "\n" + sha256(staged);
            JobEntity job = jobs.create("SUPPLEMENT_UPLOAD", JobIdempotency.key("SUPPLEMENT_UPLOAD", actor, stable),
                    actor, config, volume.getId());
            if (!Objects.equals(job.getConfigSnapshotJson(), config)) Files.deleteIfExists(staged);
            return ApiResponse.ok(new UploadTask(job.getId(), job.getJobNo(), job.getStatus(), file.getOriginalFilename(), file.getSize()), requestId());
        } catch (Exception exception) {
            Files.deleteIfExists(staged);
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
    public ResponseEntity<?> download(@PathVariable("orderId") String orderId, @PathVariable("fileId") String fileId) throws IOException {
        StpUtil.checkLogin();
        try {
            SnapshotRef ref = snapshot(orderId);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileId + "\"")
                    .body(new InputStreamResource(supplements.openForSnapshot(ref.id(), fileId)));
        } catch (NoSuchFileException | FileNotFoundException exception) {
            return ResponseEntity.notFound().build();
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
