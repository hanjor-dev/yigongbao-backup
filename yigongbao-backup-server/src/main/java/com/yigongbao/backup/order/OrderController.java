package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.LegacyArtifactStoreVolumeAdapter;
import com.yigongbao.backup.storage.volume.StorageVolumeUnavailableException;
import com.yigongbao.backup.storage.location.ArtifactReplicaResolver;
import com.yigongbao.backup.storage.lease.StorageVolumeReadLeaseService;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 普通用户订单查询、资产查看和订单包下载接口。 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderSnapshotMapper snapshots;
    private final AssetMapper assets;
    private final OrderPackageMapper packages;
    private final VolumeArtifactStore store;
    private final ArtifactReplicaResolver replicas;
    private final OrderStorageLocationService locations;
    private final StorageVolumeReadLeaseService readLeases;

    /** 显式声明唯一注入构造器，避免多构造器导致 Spring 回退查找无参构造器。 */
    @Autowired
    public OrderController(OrderSnapshotMapper snapshots, AssetMapper assets,
                           OrderPackageMapper packages, VolumeArtifactStore store, ArtifactReplicaResolver replicas,
                           OrderStorageLocationService locations, StorageVolumeReadLeaseService readLeases) {
        this.snapshots = snapshots;
        this.assets = assets;
        this.packages = packages;
        this.store = store;
        this.replicas = replicas;
        this.locations = locations;
        this.readLeases = readLeases;
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    /** 兼容既有列表查询测试；实际下载链路通过多卷构造器注入。 */
    public OrderController(OrderSnapshotMapper snapshots, AssetMapper assets,
                           OrderPackageMapper packages, ArtifactStore store) {
        this(snapshots, assets, packages, new LegacyArtifactStoreVolumeAdapter(store), null, null, null);
    }

    /**
     * 返回订单文件页可用的固定筛选项。
     * <p>选项由线上订单业务契约固化，不依赖线上服务或本地已有快照，离线场景下仍可完整展示。</p>
     *
     * @return 状态、业务类型、实体交付及三地存储状态选项
     */
    @GetMapping("/filter-options")
    public ApiResponse<Map<String, List<Map<String, Object>>>> filterOptions() {
        StpUtil.checkLogin();
        Map<String, List<Map<String, Object>>> options = new LinkedHashMap<>();
        options.put("orderStatuses", List.of(
                option(1010, "草稿"), option(1020, "数据待审核"), option(1030, "数据审核通过"),
                option(1040, "数据审核不通过"), option(2010, "待设计"), option(2020, "设计中"),
                option(2030, "设计完成"), option(3010, "待打印"), option(3020, "打印中"),
                option(3030, "打印完成"), option(3040, "打印失败"), option(4010, "后处理中"),
                option(5010, "质检中"), option(5020, "质检合格"), option(5030, "质检不合格"),
                option(5040, "返工"), option(5050, "包装中"), option(6010, "待入库"),
                option(6020, "已入库"), option(6030, "已出库"), option(8010, "已完成"), option(9010, "已取消")));
        options.put("businessTypes", List.of(
                option("11.1", "业务"), option("11.2", "测试"),
                option("11.3", "试用"), option("11.4", "代理")));
        options.put("physicalDeliveries", List.of(
                option(0, "不需要实体交付"), option(1, "需要实体交付"), option(2, "异地打印")));
        options.put("storageStates", List.of(
                option("PRESENT", "存在"), option("ABSENT", "缺失"), option("PARTIAL", "部分存在"),
                option("CLEARED", "已清理"), option("RESTORING", "恢复中"),
                option("ERROR", "异常"), option("UNKNOWN", "未知")));
        options.put("localReplicaStates", List.of(option("PRESENT", "存在"), option("PARTIAL", "部分异常"),
                option("ERROR", "异常"), option("ABSENT", "缺失")));
        return ApiResponse.ok(options, rid());
    }

    /** 构建筛选项值与展示名称，使用可预测的字段契约供前端统一渲染。 */
    private static Map<String, Object> option(Object value, String label) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    /** 分页查询订单快照，支持统一关键词、订单业务字段和三地存储状态筛选。 */
    @GetMapping
    public ApiResponse<Page<OrderSnapshotEntity>> list(
            @RequestParam(name = "current", defaultValue = "1") long current,
            @RequestParam(name = "size", defaultValue = "20") long size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "createdFrom", required = false) String createdFrom,
            @RequestParam(name = "createdTo", required = false) String createdTo,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "businessType", required = false) String businessType,
            @RequestParam(name = "needsPhysicalDelivery", required = false) Integer needsPhysicalDelivery,
            @RequestParam(name = "cloudRollup", required = false) String cloudRollup,
            @RequestParam(name = "hotRollup", required = false) String hotRollup,
            @RequestParam(name = "coldRollup", required = false) String coldRollup) {
        StpUtil.checkLogin();
        if (current < 1 || size < 1) {
            throw new IllegalArgumentException("分页参数必须大于零");
        }
        if (keyword != null && keyword.trim().length() > 128) {
            throw new IllegalArgumentException("关键词筛选条件过长");
        }
        LambdaQueryWrapper<OrderSnapshotEntity> query = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String normalizedKeyword = keyword.trim();
            query.and(value -> value.like(OrderSnapshotEntity::getOrderCode, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getPublicOrderCode, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getOrgName, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getHospitalName, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getPatientName, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getOperatorName, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getDoctorName, normalizedKeyword)
                    .or().like(OrderSnapshotEntity::getRebuildProjectNames, normalizedKeyword));
        }
        if (businessType != null && !businessType.isBlank()) {
            requireBusinessType(businessType);
            query.eq(OrderSnapshotEntity::getBusinessType, businessType.trim());
        }
        if (needsPhysicalDelivery != null && (needsPhysicalDelivery < 0 || needsPhysicalDelivery > 2)) {
            throw new IllegalArgumentException("实体交付类型非法");
        }
        query.eq(status != null, OrderSnapshotEntity::getStatus, status)
                .eq(needsPhysicalDelivery != null, OrderSnapshotEntity::getNeedsPhysicalDelivery, needsPhysicalDelivery);
        LocalDateTime from = parseDateBoundary(createdFrom, "createdFrom", false);
        LocalDateTime to = parseDateBoundary(createdTo, "createdTo", true);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("创建时间范围无效");
        }
        if (from != null) {
            query.ge(OrderSnapshotEntity::getOrderCreatedAt, from);
        }
        if (to != null) {
            query.lt(OrderSnapshotEntity::getOrderCreatedAt, to);
        }
        if (cloudRollup != null && !cloudRollup.isBlank()) {
            requireStatus(cloudRollup, "云端存储状态", ROLLUP_STATES);
            query.eq(OrderSnapshotEntity::getCloudRollup, cloudRollup);
        }
        if (hotRollup != null && !hotRollup.isBlank()) {
            requireStatus(hotRollup, "热备存储状态", LOCAL_REPLICA_STATES);
            applyCurrentTierFilter(query, "HOT", hotRollup);
        }
        if (coldRollup != null && !coldRollup.isBlank()) {
            requireStatus(coldRollup, "冷备存储状态", LOCAL_REPLICA_STATES);
            applyCurrentTierFilter(query, "COLD", coldRollup);
        }
        query.orderByDesc(OrderSnapshotEntity::getOrderCreatedAt)
                .orderByDesc(OrderSnapshotEntity::getId);
        Page<OrderSnapshotEntity> page = snapshots.selectPage(new Page<>(current, Math.min(size, 100)), query);
        enrichAssetScale(page.getRecords());
        enrichStorageLocations(page.getRecords());
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
        enrichStorageLocations(List.of(snapshot));
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
        if (locations != null) {
            OrderStorageLocationService.LocationView location = locations.locate(packageForSnapshot(id));
            for (AssetEntity asset : result) {
                asset.setStorageVolumeId(location.storageVolumeId()); asset.setStorageVolumeName(location.storageVolumeName());
                asset.setStorageTier(location.storageTier()); asset.setStorageMountStatus(location.mountStatus());
                asset.setStorageAccessible(location.accessible());
                asset.setStorageUnavailableReason(location.unavailableReason());
                asset.setStorageDiagnosticReason(location.diagnosticReason());
                asset.setStorageArchiveNote(location.archiveNote()); asset.setStorageOfflineLabelNote(location.offlineLabelNote());
                asset.setStoragePhysicalLocation(location.physicalLocation()); asset.setStoragePeriodLabel(location.periodLabel());
                asset.setStorageKey(location.storageKey() == null || asset.getPackageEntryName() == null ? null
                        : location.storageKey() + "!/" + asset.getPackageEntryName());
            }
        }
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
        if (orderPackage == null) return ResponseEntity.notFound().build();
        if (replicas == null && (orderPackage.getStorageKey() == null || orderPackage.getStorageVolumeId() == null)) return ResponseEntity.notFound().build();
        String code = snapshot.getOrderCode() == null ? String.valueOf(snapshot.getSourceOrderId()) : snapshot.getOrderCode();
        String key = orderPackage.getStorageKey();
        StorageVolumeReadLeaseService.LeasedArtifact lease = null;
        try {
            Long volumeId = orderPackage.getStorageVolumeId();
            if (readLeases != null) {
                lease = readLeases.acquire("ORDER_PACKAGE", orderPackage.getId(), "DOWNLOAD",
                        "user:" + StpUtil.getLoginIdAsLong() + ":" + UUID.randomUUID(), readLeases.defaultTtl());
                ArtifactReplicaResolver.ReadableArtifactReplica replica = lease.replica();
                volumeId = replica.storageVolumeId(); key = replica.storageKey();
            } else if (replicas != null) {
                ArtifactReplicaResolver.ReadableArtifactReplica replica = replicas.resolveReadable("ORDER_PACKAGE", orderPackage.getId());
                volumeId = replica.storageVolumeId();
                key = replica.storageKey();
            }
            if (volumeId == null || key == null) return ResponseEntity.notFound().build();
            Path packagePath = store.resolve(volumeId, key);
            if (!Files.isRegularFile(packagePath)) return ResponseEntity.notFound().build();
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(packagePath,
                    java.nio.file.StandardOpenOption.READ);
            long size = channel.size();
            try {
                verifySha256(channel, orderPackage.getPackageSha256());
                if (lease != null) lease.heartbeat();
                channel.position(0L);
            } catch (RuntimeException | IOException exception) {
                if (lease != null) readLeases.markDamaged(lease,"DOWNLOAD_CHECKSUM_FAILED",exception.getMessage(),StpUtil.getLoginIdAsLong());
                channel.close();
                throw exception;
            }
            java.io.InputStream input = java.nio.channels.Channels.newInputStream(channel);
            StorageVolumeReadLeaseService.LeasedArtifact heldLease = lease;
            lease = null;
            InputStreamResource body = new InputStreamResource(heldLease==null?input:readLeases.wrap(input,heldLease));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(downloadFileName(code), StandardCharsets.UTF_8).build().toString())
                    .body(body);
        } catch (StorageVolumeUnavailableException exception) {
            return ResponseEntity.status(409).body(ApiResponse.ok(java.util.Map.of("code", "STORAGE_VOLUME_OFFLINE", "message", exception.getMessage()), rid()));
        } catch (NoSuchFileException | FileNotFoundException exception) {
            return ResponseEntity.notFound().build();
        } finally {
            if (lease != null) lease.close();
        }
    }

    private static LocalDateTime parseDateBoundary(String value, String field, boolean exclusiveEnd) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate date = LocalDate.parse(value);
                return (exclusiveEnd ? date.plusDays(1) : date).atStartOfDay();
            }
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(field + " 时间格式非法", exception);
            }
        }
    }

    /** 在同一个已打开文件句柄上校验并随后输出，避免校验和下载之间文件被替换。 */
    private static void verifySha256(java.nio.channels.FileChannel channel, String expected) throws IOException {
        if (expected == null || expected.isBlank()) throw new IllegalStateException("订单包缺少 SHA-256，拒绝下载");
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            String actual = java.util.HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) throw new IllegalStateException("订单包 SHA-256 校验失败，拒绝下载");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境不支持 SHA-256", impossible);
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

    private OrderPackageEntity packageForSnapshot(long snapshotId) {
        return packages.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderPackageEntity>lambdaQuery()
                .eq(OrderPackageEntity::getSnapshotId, snapshotId).eq(OrderPackageEntity::getStatus, "AVAILABLE")
                .orderByDesc(OrderPackageEntity::getId).last("limit 1"));
    }

    /** 列表与详情使用同一份副本解析结果，离线卷仍可查询。 */
    private void enrichStorageLocations(List<OrderSnapshotEntity> rows) {
        if (locations == null || rows == null || rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(OrderSnapshotEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        Map<Long, OrderPackageEntity> bySnapshot = new HashMap<>();
        packages.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrderPackageEntity>lambdaQuery()
                        .in(OrderPackageEntity::getSnapshotId, ids).eq(OrderPackageEntity::getStatus, "AVAILABLE")
                        .orderByDesc(OrderPackageEntity::getId))
                .forEach(pkg -> bySnapshot.putIfAbsent(pkg.getSnapshotId(), pkg));
        Map<Long, OrderStorageLocationService.LocationView> projected = locations.locateAll(bySnapshot.values());
        for (OrderSnapshotEntity row : rows) {
            OrderPackageEntity pkg = bySnapshot.get(row.getId());
            OrderStorageLocationService.LocationView location = pkg == null ? locations.locate(null) : projected.get(pkg.getId());
            row.setStorageVolumeId(location.storageVolumeId()); row.setStorageVolumeName(location.storageVolumeName());
            row.setStorageTier(location.storageTier()); row.setStorageKey(location.storageKey());
            row.setStorageMountStatus(location.mountStatus()); row.setStorageAccessible(location.accessible());
            row.setStorageUnavailableReason(location.unavailableReason());
            row.setStorageDiagnosticReason(location.diagnosticReason());
            row.setStorageAdminStatus(location.adminStatus()); row.setStorageOperationStatus(location.operationStatus());
            row.setStorageArchiveNote(location.archiveNote()); row.setStorageOfflineLabelNote(location.offlineLabelNote());
            row.setStoragePhysicalLocation(location.physicalLocation());
            row.setStorageOrderCreatedFrom(location.orderCreatedFrom()); row.setStorageOrderCreatedTo(location.orderCreatedTo());
            row.setStoragePeriodLabel(location.periodLabel());
            row.setHotRollup(location.hotRollup()); row.setColdRollup(location.coldRollup());
        }
    }

    /** HOT/COLD 当前状态由有效副本所属卷派生，禁止读取备份发生时写入的快照 rollup。 */
    private static void applyCurrentTierFilter(LambdaQueryWrapper<OrderSnapshotEntity> query, String tier, String status) {
        status = status.trim().toUpperCase(java.util.Locale.ROOT);
        String healthy = tierCopyExists(tier, "ac.state IN ('VERIFIED','PRESENT')");
        String abnormal = tierCopyExists(tier, "(ac.state IS NULL OR ac.state NOT IN ('VERIFIED','PRESENT'))");
        if ("PRESENT".equals(status)) query.apply(healthy + " AND NOT " + abnormal);
        else if ("PARTIAL".equals(status)) query.apply(healthy + " AND " + abnormal);
        else if ("ERROR".equals(status)) query.apply("NOT " + healthy + " AND " + abnormal);
        else if ("ABSENT".equals(status)) query.apply("NOT " + healthy + " AND NOT " + abnormal);
        else query.apply("1 = 0");
    }

    private static String tierCopyExists(String tier, String statePredicate) {
        return "EXISTS (SELECT 1 FROM bk_order_package op "
                + "JOIN bk_artifact_copy ac ON ac.artifact_type = 'ORDER_PACKAGE' AND ac.artifact_id = op.id "
                + "AND ac.lifecycle = 'ACTIVE' AND " + statePredicate + " AND ac.is_deleted = 0 "
                + "JOIN bk_storage_volume sv ON sv.id = ac.storage_volume_id AND sv.is_deleted = 0 "
                + "WHERE op.snapshot_id = bk_order_snapshot.id AND op.status = 'AVAILABLE' AND op.is_deleted = 0 "
                + "AND sv.tier = '" + tier + "')";
    }

    /** 将订单号限制为安全的下载文件名，避免响应头注入和非法路径字符。 */
    private static String downloadFileName(String code) {
        String safe = code == null ? "order" : code.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isBlank()) safe = "order";
        return safe.substring(0, Math.min(120, safe.length())) + ".zip";
    }

    private static final java.util.Set<String> ROLLUP_STATES = java.util.Set.of(
            "PRESENT", "CLEARED", "RESTORING", "AVAILABLE", "PARTIAL", "ABSENT", "ERROR", "UNKNOWN");
    private static final java.util.Set<String> LOCAL_REPLICA_STATES = java.util.Set.of("PRESENT", "PARTIAL", "ERROR", "ABSENT");

    private static void requireStatus(String value, String field, java.util.Set<String> allowed) {
        if (!allowed.contains(value.trim().toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + "非法");
        }
    }

    /** 业务字典编码允许医工宝现有的层级点号，例如 11.1。 */
    private static void requireBusinessType(String value) {
        if (!value.trim().matches("[A-Za-z0-9_.-]{1,32}")) {
            throw new IllegalArgumentException("业务类型非法");
        }
    }

}
