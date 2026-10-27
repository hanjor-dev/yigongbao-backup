package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.api.ArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 普通用户订单查询、资产查看和订单包下载接口。 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderSnapshotMapper snapshots;
    private final AssetMapper assets;
    private final ArtifactStore store;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 分页查询全部订单快照，并支持订单号、创建时间和快照状态筛选。 */
    @GetMapping
    public ApiResponse<Page<OrderSnapshotEntity>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String orderCode,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String snapshotStatus,
            @RequestParam(required = false) String sourceStatus,
            @RequestParam(required = false) String cloudRollup,
            @RequestParam(required = false) String hotRollup,
            @RequestParam(required = false) String coldRollup) {
        StpUtil.checkLogin();
        if (current < 1 || size < 1) {
            throw new IllegalArgumentException("分页参数必须大于零");
        }
        if (orderCode != null && orderCode.length() > 128) {
            throw new IllegalArgumentException("订单号筛选条件过长");
        }
        LambdaQueryWrapper<OrderSnapshotEntity> query = new LambdaQueryWrapper<>();
        if (orderCode != null && !orderCode.isBlank()) {
            query.like(OrderSnapshotEntity::getOrderCode, orderCode.trim());
        }
        LocalDateTime from = parseTime(createdFrom, "createdFrom");
        LocalDateTime to = parseTime(createdTo, "createdTo");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("创建时间范围无效");
        }
        if (from != null) {
            query.ge(OrderSnapshotEntity::getOrderCreatedAt, from);
        }
        if (to != null) {
            query.le(OrderSnapshotEntity::getOrderCreatedAt, to);
        }
        if (snapshotStatus != null && !snapshotStatus.isBlank()) {
            requireStatus(snapshotStatus, "快照状态", SNAPSHOT_STATES);
            query.eq(OrderSnapshotEntity::getSnapshotStatus, snapshotStatus);
        }
        if (sourceStatus != null && !sourceStatus.isBlank()) {
            requireCode(sourceStatus, "订单业务状态");
            query.eq(OrderSnapshotEntity::getSourceStatus, sourceStatus.trim());
        }
        if (cloudRollup != null && !cloudRollup.isBlank()) {
            requireStatus(cloudRollup, "云端存储状态", ROLLUP_STATES);
            query.eq(OrderSnapshotEntity::getCloudRollup, cloudRollup);
        }
        if (hotRollup != null && !hotRollup.isBlank()) {
            requireStatus(hotRollup, "热备存储状态", ROLLUP_STATES);
            query.eq(OrderSnapshotEntity::getHotRollup, hotRollup);
        }
        if (coldRollup != null && !coldRollup.isBlank()) {
            requireStatus(coldRollup, "冷备存储状态", ROLLUP_STATES);
            query.eq(OrderSnapshotEntity::getColdRollup, coldRollup);
        }
        query.orderByDesc(OrderSnapshotEntity::getOrderCreatedAt)
                .orderByDesc(OrderSnapshotEntity::getId);
        return ApiResponse.ok(snapshots.selectPage(new Page<>(current, Math.min(size, 100)), query), rid());
    }

    /** 查询单个订单快照；不存在时按契约返回 404。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderSnapshotEntity>> detail(@PathVariable long id) {
        StpUtil.checkLogin();
        if (id < 1) {
            return ResponseEntity.notFound().build();
        }
        OrderSnapshotEntity snapshot = snapshots.selectById(id);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(snapshot, rid()));
    }

    /** 查询订单关联资产及其三态存储状态。 */
    @GetMapping("/{id}/assets")
    public ResponseEntity<ApiResponse<List<AssetEntity>>> assets(@PathVariable long id) {
        StpUtil.checkLogin();
        if (id < 1 || snapshots.selectById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        List<AssetEntity> result = assets.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AssetEntity>lambdaQuery()
                        .eq(AssetEntity::getSnapshotId, id)
                        .orderByAsc(AssetEntity::getId));
        return ResponseEntity.ok(ApiResponse.ok(result, rid()));
    }

    /** 下载订单级压缩包，文件不存在时返回 404。 */
    @GetMapping("/{id}/package/download")
    public ResponseEntity<?> download(@PathVariable long id) throws IOException {
        StpUtil.checkLogin();
        OrderSnapshotEntity snapshot = snapshots.selectById(id);
        if (snapshot == null || snapshot.getOrderCode() == null
                || !snapshot.getOrderCode().matches("[A-Za-z0-9_-]{1,64}")) {
            return ResponseEntity.notFound().build();
        }
        String code = snapshot.getOrderCode();
        String key = "orders/" + code + "/" + code + ".zip";
        Path packagePath = store.root().resolve(key).normalize();
        if (!packagePath.startsWith(store.root()) || !Files.isRegularFile(packagePath)) {
            return ResponseEntity.notFound().build();
        }
        try {
            long size = Files.size(packagePath);
            InputStreamResource body = new InputStreamResource(store.open(key));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + code + ".zip\"")
                    .body(body);
        } catch (NoSuchFileException | FileNotFoundException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    private static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(field + " 时间格式非法", exception);
            }
        }
    }

    private static final java.util.Set<String> SNAPSHOT_STATES = java.util.Set.of(
            "AVAILABLE", "RECEIPT_SUSPENDED", "CORRUPT");
    private static final java.util.Set<String> ROLLUP_STATES = java.util.Set.of(
            "PRESENT", "CLEARED", "RESTORING", "AVAILABLE", "PARTIAL", "ABSENT", "ERROR", "UNKNOWN");

    private static void requireStatus(String value, String field, java.util.Set<String> allowed) {
        if (!allowed.contains(value.trim().toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + "非法");
        }
    }

    /** 校验线上业务状态编码，允许随线上字典扩展但拒绝注入和超长输入。 */
    private static void requireCode(String value, String field) {
        if (!value.trim().matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException(field + "非法");
        }
    }
}
