package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.api.ArtifactStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ContentDisposition;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** 普通用户订单查询、资产查看和订单包下载接口。 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderSnapshotMapper snapshots;
    private final AssetMapper assets;
    private final OrderPackageMapper packages;
    private final ArtifactStore store;

    /** 显式声明唯一注入构造器，避免多构造器导致 Spring 回退查找无参构造器。 */
    public OrderController(OrderSnapshotMapper snapshots, AssetMapper assets,
                           OrderPackageMapper packages, ArtifactStore store) {
        this.snapshots = snapshots;
        this.assets = assets;
        this.packages = packages;
        this.store = store;
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 分页查询全部订单快照，并支持订单号、创建时间和快照状态筛选。 */
    @GetMapping
    public ApiResponse<Page<OrderSnapshotEntity>> list(
            @RequestParam(name = "current", defaultValue = "1") long current,
            @RequestParam(name = "size", defaultValue = "20") long size,
            @RequestParam(name = "orderCode", required = false) String orderCode,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo", required = false) String createdTo,
            @RequestParam(name = "snapshotStatus", required = false) String snapshotStatus,
            @RequestParam(name = "sourceStatus", required = false) String sourceStatus,
            @RequestParam(name = "cloudRollup", required = false) String cloudRollup,
            @RequestParam(name = "hotRollup", required = false) String hotRollup,
            @RequestParam(name = "coldRollup", required = false) String coldRollup,
            @RequestParam(name = "orgName", required = false) String orgName,
            @RequestParam(name = "hospitalName", required = false) String hospitalName,
            @RequestParam(name = "patientName", required = false) String patientName,
            @RequestParam(name = "doctorName", required = false) String doctorName,
            @RequestParam(name = "businessType", required = false) String businessType,
            @RequestParam(name = "phase", required = false) Integer phase,
            @RequestParam(name = "status", required = false) Integer status) {
        StpUtil.checkLogin();
        if (current < 1 || size < 1) {
            throw new IllegalArgumentException("分页参数必须大于零");
        }
        if (orderCode != null && orderCode.length() > 128) {
            throw new IllegalArgumentException("订单号筛选条件过长");
        }
        LambdaQueryWrapper<OrderSnapshotEntity> query = new LambdaQueryWrapper<>();
        if (orderCode != null && !orderCode.isBlank()) {
            String keyword = orderCode.trim();
            query.and(value -> value.like(OrderSnapshotEntity::getOrderCode, keyword)
                    .or().like(OrderSnapshotEntity::getPublicOrderCode, keyword));
        }
        applyLike(query, orgName, "机构名称", OrderSnapshotEntity::getOrgName);
        applyLike(query, hospitalName, "医院名称", OrderSnapshotEntity::getHospitalName);
        applyLike(query, patientName, "患者姓名", OrderSnapshotEntity::getPatientName);
        applyLike(query, doctorName, "医生姓名", OrderSnapshotEntity::getDoctorName);
        if (businessType != null && !businessType.isBlank()) {
            requireBusinessType(businessType);
            query.eq(OrderSnapshotEntity::getBusinessType, businessType.trim());
        }
        query.eq(phase != null, OrderSnapshotEntity::getPhase, phase)
                .eq(status != null, OrderSnapshotEntity::getStatus, status);
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
        Page<OrderSnapshotEntity> page = snapshots.selectPage(new Page<>(current, Math.min(size, 100)), query);
        enrichAssetScale(page.getRecords());
        return ApiResponse.ok(page, rid());
    }

    /** 查询单个订单快照；不存在时按契约返回 404。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderSnapshotEntity>> detail(@PathVariable("id") long id) {
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
    public ResponseEntity<ApiResponse<List<AssetEntity>>> assets(@PathVariable("id") long id) {
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
    public ResponseEntity<?> download(@PathVariable("id") long id) throws IOException {
        StpUtil.checkLogin();
        OrderSnapshotEntity snapshot = snapshots.selectById(id);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        OrderPackageEntity orderPackage = packages.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderPackageEntity>lambdaQuery()
                        .eq(OrderPackageEntity::getSnapshotId, id)
                        .eq(OrderPackageEntity::getStatus, "AVAILABLE").last("limit 1"));
        if (orderPackage == null || orderPackage.getStorageKey() == null) return ResponseEntity.notFound().build();
        String code = snapshot.getOrderCode() == null ? String.valueOf(snapshot.getSourceOrderId()) : snapshot.getOrderCode();
        String key = orderPackage.getStorageKey();
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
                            ContentDisposition.attachment().filename(downloadFileName(code), StandardCharsets.UTF_8).build().toString())
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

    /** 订单列表展示文件规模；资产数量和源文件总大小来自当前快照资产表。 */
    private void enrichAssetScale(List<OrderSnapshotEntity> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> snapshotIds = rows.stream().map(OrderSnapshotEntity::getId)
                .filter(java.util.Objects::nonNull).toList();
        if (snapshotIds.isEmpty()) return;
        Map<Long, Long> counts = new HashMap<>();
        Map<Long, Long> bytes = new HashMap<>();
        assets.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AssetEntity>lambdaQuery()
                        .in(AssetEntity::getSnapshotId, snapshotIds))
                .forEach(asset -> {
                    counts.merge(asset.getSnapshotId(), 1L, Long::sum);
                    bytes.merge(asset.getSnapshotId(), asset.getSourceSize() == null ? 0L : asset.getSourceSize(), Long::sum);
                });
        rows.forEach(row -> {
            row.setAssetCount(counts.getOrDefault(row.getId(), 0L));
            row.setTotalBytes(bytes.getOrDefault(row.getId(), 0L));
        });
    }

    /** 将订单号限制为安全的下载文件名，避免响应头注入和非法路径字符。 */
    private static String downloadFileName(String code) {
        String safe = code == null ? "order" : code.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isBlank()) safe = "order";
        return safe.substring(0, Math.min(120, safe.length())) + ".zip";
    }

    private static final java.util.Set<String> SNAPSHOT_STATES = java.util.Set.of(
            "AVAILABLE", "SIMULATED", "RECEIPT_SUSPENDED", "CORRUPT");
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

    /** 业务字典编码允许医工宝现有的层级点号，例如 11.1。 */
    private static void requireBusinessType(String value) {
        if (!value.trim().matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalArgumentException("业务类型非法");
        }
    }

    /** 为结构化文本字段追加安全模糊查询，避免从 JSON 中运行时提取。 */
    private static void applyLike(LambdaQueryWrapper<OrderSnapshotEntity> query, String value, String field,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<OrderSnapshotEntity, ?> column) {
        if (value == null || value.isBlank()) return;
        if (value.trim().length() > 128) throw new IllegalArgumentException(field + "筛选条件过长");
        query.like(column, value.trim());
    }
}
