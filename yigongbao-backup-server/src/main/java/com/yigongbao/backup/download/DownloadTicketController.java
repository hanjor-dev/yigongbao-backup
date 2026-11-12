package com.yigongbao.backup.download;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.audit.AuditLogService;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.order.*;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 下载票据签发需要登录，票据消费不依赖会话并直接流式返回文件。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DownloadTicketController {
    private final DownloadTicketService tickets;
    private final OrderSnapshotMapper snapshots;
    private final OrderPackageMapper packages;
    private final SupplementService supplements;
    private final StorageVolumeReadLeaseService readLeases;
    private final VolumeArtifactStore store;
    private final AuditLogService audit;

    @PostMapping("/download-tickets/order-packages/{snapshotId}")
    public ApiResponse<TicketResponse> issueOrder(@PathVariable long snapshotId, HttpServletRequest request) {
        StpUtil.checkLogin();
        OrderSnapshotEntity snapshot = snapshots.selectById(snapshotId);
        if (snapshot == null) throw new IllegalArgumentException("订单快照不存在");
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshotId).eq(OrderPackageEntity::getStatus, "AVAILABLE").last("limit 1"));
        if (pkg == null) throw new IllegalArgumentException("订单不存在可下载压缩包");
        String name = (snapshot.getOrderCode() == null ? snapshot.getSourceOrderId() : snapshot.getOrderCode()) + ".zip";
        return issued(request, "ORDER_PACKAGE", pkg.getId(), name);
    }

    @PostMapping("/download-tickets/supplements/{snapshotId}/{fileId}")
    public ApiResponse<TicketResponse> issueSupplement(@PathVariable long snapshotId, @PathVariable String fileId,
                                                       HttpServletRequest request) throws IOException {
        StpUtil.checkLogin();
        BkSupplementFileEntity file = supplements.requireForSnapshot(snapshotId, fileId);
        return issued(request, "SUPPLEMENT_FILE", file.getId(), file.getOriginalFilename());
    }

    private ApiResponse<TicketResponse> issued(HttpServletRequest request, String type, long artifactId, String name) {
        long actor = StpUtil.getLoginIdAsLong();
        String role = StpUtil.hasRole("ADMIN") ? "ADMIN" : "USER";
        DownloadTicketService.IssuedTicket ticket = tickets.issue(actor, role, type, artifactId, name);
        audit.appendActor(request, actor, role,
                "DOWNLOAD_TICKET_ISSUE", type, String.valueOf(artifactId), null, "OK", null);
        return ApiResponse.ok(new TicketResponse("/api/v1/downloads/" + ticket.token(), ticket.expiresAt()), requestId());
    }

    @GetMapping("/downloads/{token}")
    public ResponseEntity<?> download(@PathVariable String token, HttpServletRequest request) throws IOException {
        DownloadTicketEntity ticket = tickets.consume(token);
        StorageVolumeReadLeaseService.LeasedArtifact lease = null;
        FileChannel channel = null;
        try {
            lease = readLeases.acquire(ticket.getArtifactType(), ticket.getArtifactId(), "DOWNLOAD",
                    "ticket:" + ticket.getId(), readLeases.defaultTtl());
            var replica = lease.replica();
            Path path = store.resolve(replica.storageVolumeId(), replica.storageKey());
            if (!Files.isRegularFile(path)) throw new NoSuchFileException(path.toString());
            String expected = expectedSha256(ticket);
            channel = FileChannel.open(path, StandardOpenOption.READ);
            long size = channel.size();
            try {
                verifySha256(channel, expected);
                lease.heartbeat();
                channel.position(0L);
            } catch (RuntimeException | IOException failure) {
                readLeases.markDamaged(lease, "DOWNLOAD_CHECKSUM_FAILED", failure.getMessage(), ticket.getActorUserId());
                channel.close();
                channel = null;
                throw failure;
            }
            audit.appendActor(request, ticket.getActorUserId(), ticket.getActorRoleCode(), "DOWNLOAD_TICKET_CONSUME",
                    ticket.getArtifactType(), String.valueOf(ticket.getArtifactId()), null, "OPENED", null);
            var input = readLeases.wrap(Channels.newInputStream(channel), lease);
            channel = null;
            lease = null;
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(ticket.getDownloadName(), StandardCharsets.UTF_8).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new InputStreamResource(input));
        } catch (StorageVolumeUnavailableException failure) {
            audit.appendActor(request, ticket.getActorUserId(), ticket.getActorRoleCode(), "DOWNLOAD_TICKET_CONSUME",
                    ticket.getArtifactType(), String.valueOf(ticket.getArtifactId()), null, "FAILED", failure.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                    "STORAGE_VOLUME_OFFLINE", failure.getMessage(), requestId()));
        } catch (NoSuchFileException | FileNotFoundException failure) {
            audit.appendActor(request, ticket.getActorUserId(), ticket.getActorRoleCode(), "DOWNLOAD_TICKET_CONSUME",
                    ticket.getArtifactType(), String.valueOf(ticket.getArtifactId()), null, "FAILED", "文件不存在");
            return ResponseEntity.notFound().build();
        } catch (RuntimeException | IOException failure) {
            audit.appendActor(request, ticket.getActorUserId(), ticket.getActorRoleCode(), "DOWNLOAD_TICKET_CONSUME",
                    ticket.getArtifactType(), String.valueOf(ticket.getArtifactId()), null, "FAILED", failure.getMessage());
            throw failure;
        } finally {
            if (channel != null) channel.close();
            if (lease != null) lease.close();
        }
    }

    private String expectedSha256(DownloadTicketEntity ticket) throws NoSuchFileException {
        if ("ORDER_PACKAGE".equals(ticket.getArtifactType())) {
            OrderPackageEntity pkg = packages.selectById(ticket.getArtifactId());
            if (pkg == null) throw new NoSuchFileException(String.valueOf(ticket.getArtifactId()));
            return pkg.getPackageSha256();
        }
        if ("SUPPLEMENT_FILE".equals(ticket.getArtifactType())) {
            BkSupplementFileEntity file = supplements.findById(ticket.getArtifactId());
            if (file == null) throw new NoSuchFileException(String.valueOf(ticket.getArtifactId()));
            return file.getSha256();
        }
        throw new IllegalArgumentException("不支持的下载对象类型");
    }

    private static void verifySha256(FileChannel channel, String expected) throws IOException {
        if (expected == null || expected.isBlank()) throw new IllegalStateException("下载对象缺少 SHA-256，拒绝下载");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) >= 0) { buffer.flip(); digest.update(buffer); buffer.clear(); }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected))
                throw new IllegalStateException("下载对象 SHA-256 校验失败，拒绝下载");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String requestId() { return UUID.randomUUID().toString(); }
    public record TicketResponse(String url, java.time.LocalDateTime expiresAt) { }
}
