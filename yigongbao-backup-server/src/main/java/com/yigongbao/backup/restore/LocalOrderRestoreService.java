package com.yigongbao.backup.restore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 线上恢复协调器：订单内全部文件上传成功后才 commit，任何失败都 abort，订单整体失败。 */
public final class LocalOrderRestoreService {
    private static final Logger log = LoggerFactory.getLogger(LocalOrderRestoreService.class);
    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final OnlineRestoreClient client;
    private final long maxUncompressedBytes;
    private final int maxEntries;
    private final ObjectMapper mapper;
    private final String conflictPolicy;

    public LocalOrderRestoreService(OnlineRestoreClient client) {
        this(client, DEFAULT_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_ENTRIES, new ObjectMapper(), "REJECT");
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, String conflictPolicy) {
        this(client, DEFAULT_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_ENTRIES, new ObjectMapper(), conflictPolicy);
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, long maxUncompressedBytes, int maxEntries) {
        this(client, maxUncompressedBytes, maxEntries, new ObjectMapper(), "REJECT");
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, long maxUncompressedBytes, int maxEntries,
                                    ObjectMapper mapper) {
        this(client, maxUncompressedBytes, maxEntries, mapper, "REJECT");
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, long maxUncompressedBytes, int maxEntries,
                                    ObjectMapper mapper, String conflictPolicy) {
        if (client == null || maxUncompressedBytes <= 0 || maxEntries <= 0 || mapper == null) {
            throw new IllegalArgumentException("恢复服务参数非法");
        }
        this.client = client;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.maxEntries = maxEntries;
        this.mapper = mapper;
        this.conflictPolicy = switch (conflictPolicy == null ? "REJECT" : conflictPolicy) {
            case "REJECT", "CONFIRM_OVERWRITE", "FORCE_OVERWRITE" -> conflictPolicy == null ? "REJECT" : conflictPolicy;
            default -> throw new IllegalArgumentException("恢复冲突策略非法");
        };
    }

    /**
     * 按 manifest 期望资产逐文件上传。任何一项失败都不提交线上会话，避免出现整单部分恢复。
     */
    public RestoreOutcome restore(Path orderZip, String orderId) throws IOException {
        return restore(orderZip, orderId, RestoreAssetListener.noOp());
    }

    /**
     * 在请求线上恢复会话前读取 ZIP 内的文件范围。
     *
     * <p>仅做本地只读检查，用于订单开始日志；不会调用线上接口、上传文件或改写数据。</p>
     */
    public RestorePackageInventory inspect(Path orderZip, String orderId) throws IOException {
        if (orderZip == null || !Files.isRegularFile(orderZip)) {
            throw new FileNotFoundException(String.valueOf(orderZip));
        }
        OnlineRestoreClient.RestoreRequest request = readPrepareRequest(orderZip, orderId);
        try (ZipFile zip = new ZipFile(orderZip.toFile())) {
            Set<String> entries = new HashSet<>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entry.isDirectory() && !"manifest.json".equals(entry.getName())) entries.add(entry.getName());
            }
            if (request == null) {
                List<RestorePackageAsset> assets = entries.stream().sorted()
                        .map(entry -> new RestorePackageAsset(entry, Path.of(entry).getFileName().toString(),
                                "UNKNOWN", List.of(), Math.max(0L, zip.getEntry(entry).getSize()), true))
                        .toList();
                return new RestorePackageInventory(null, assets);
            }
            JsonNode root = readManifestRoot(zip);
            List<RestorePackageAsset> assets = new ArrayList<>();
            for (JsonNode asset : root.path("assets")) {
                String identity = text(asset, "assetIdentity", "assetId", "id");
                if (identity == null || identity.isBlank()) continue;
                String fileName = text(asset, "originalFilename", "fileName");
                List<String> relationKinds = new ArrayList<>();
                for (JsonNode relation : asset.path("relations")) {
                    String kind = text(relation, "relationKind", "type");
                    if (kind != null && !kind.isBlank()) relationKinds.add(kind);
                }
                boolean exists = entries.stream().map(path -> Path.of(path).getFileName().toString())
                        .anyMatch(name -> name.equals(identity) || name.startsWith(identity + "-"));
                assets.add(new RestorePackageAsset(identity, fileName, text(asset, "assetType", "type"),
                        List.copyOf(relationKinds), Math.max(0L, asset.path("size").asLong(0L)), exists));
            }
            return new RestorePackageInventory(request.manifestRevision(), List.copyOf(assets));
        }
    }

    /**
     * 流式解压订单 ZIP 并逐资产上传，同时向调用方发布可持久化的文件级执行证据。
     *
     * @param orderZip 订单热备压缩包
     * @param orderId 线上订单标识
     * @param listener 单个 ZIP 条目的上传生命周期监听器
     */
    public RestoreOutcome restore(Path orderZip, String orderId, RestoreAssetListener listener) throws IOException {
        listener = listener == null ? RestoreAssetListener.noOp() : listener;
        if (orderZip == null || !Files.isRegularFile(orderZip)) {
            throw new FileNotFoundException(String.valueOf(orderZip));
        }
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("订单标识非法");
        }

        OnlineRestoreClient.RestoreRequest prepareRequest = readPrepareRequest(orderZip, orderId);
        // 空 Manifest 没有任何对象可上传，若继续 prepare/commit 会创建无法提交的空线上会话。
        if (prepareRequest != null && prepareRequest.assets().isEmpty()) {
            throw new IllegalStateException("订单备份包不含可恢复文件");
        }
        Map<String, Long> expectedSizes = new java.util.HashMap<>();
        if (prepareRequest != null) {
            for (OnlineRestoreClient.RestoreAsset asset : prepareRequest.assets()) {
                expectedSizes.put(asset.assetIdentity(), Math.max(0L, asset.size()));
            }
        }
        OnlineRestoreClient.Session session = prepareRequest == null
                ? client.prepare(orderId) : client.prepare(orderId, prepareRequest);
        if ("SKIPPED_DELETED".equalsIgnoreCase(session.status())) {
            // 线上订单已不存在时禁止上传和提交，符合“恢复默认跳过已删除订单”的约定。
            return RestoreOutcome.SKIPPED_DELETED;
        }
        // 重试前 prepare 发现线上已成功提交时，禁止再次覆盖恢复对象或重复回写关联。
        if ("COMMITTED".equalsIgnoreCase(session.status())) {
            log.info("线上恢复订单已提交，跳过重复上传｜订单ID={}｜会话ID={}", orderId, session.id());
            return RestoreOutcome.ALREADY_COMMITTED;
        }
        if (!"PREPARED".equalsIgnoreCase(session.status())) {
            throw new IllegalStateException("线上恢复会话状态不可执行: " + session.status());
        }
        boolean committed = false;
        boolean commitOutcomeUnknown = false;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(orderZip))) {
            ZipEntry entry;
            Set<String> uploaded = new HashSet<>();
            Set<String> expected = session.expectedAssets() == null
                    ? Set.of() : new HashSet<>(session.expectedAssets());
            long totalBytes = 0;
            int entryCount = 0;
            while ((entry = in.getNextEntry()) != null) {
                if (++entryCount > maxEntries) {
                    throw new IOException("恢复压缩包条目数量超过限制");
                }
                String entryName = safeEntryName(entry);
                if (entry.isDirectory() || "manifest.json".equals(entryName)) {
                    continue;
                }
                String packageName = Path.of(entryName).getFileName().toString();
                String assetName = resolveAssetIdentity(packageName, expected);
                if (!uploaded.add(assetName)) {
                    throw new IOException("恢复压缩包包含重复资产: " + assetName);
                }
                String objectKey = session.uploadObjectKeys().getOrDefault(assetName, "");
                long entrySize = expectedSizes.getOrDefault(assetName, Math.max(0L, entry.getSize()));
                listener.started(assetName, entryName, objectKey, entrySize);
                CountingInputStream bounded = new CountingInputStream(in, maxUncompressedBytes - totalBytes);
                try {
                    client.upload(session, assetName, bounded);
                } catch (IOException | RuntimeException uploadFailure) {
                    listener.failed(assetName, entryName, objectKey, bounded.count(), uploadFailure);
                    throw uploadFailure;
                }
                log.info("线上恢复文件上传完成｜订单ID={}｜会话ID={}｜资产ID={}｜字节数={}", orderId, session.id(), assetName, bounded.count());
                listener.succeeded(assetName, entryName, objectKey, bounded.count());
                totalBytes += bounded.count();
                if (totalBytes > maxUncompressedBytes) {
                    throw new IOException("恢复压缩包解压总量超过限制");
                }
            }
            if (!expected.equals(uploaded)) {
                throw new IOException("上传资产不完整");
            }
            client.commit(session);
            committed = true;
            log.info("线上恢复订单提交完成｜订单ID={}｜会话ID={}｜文件数={}", orderId, session.id(), uploaded.size());
        } catch (CommitOutcomeUnknownException unknown) {
            // commit 结果未知时禁止 abort；线上可能已经提交，必须等待查询/对账后再决定。
            commitOutcomeUnknown = true;
            throw unknown;
        } catch (Exception exception) {
            throw new IllegalStateException("线上恢复整单失败: " + orderId, exception);
        } finally {
            if (!committed && !commitOutcomeUnknown) {
                try {
                    client.abort(session);
                } catch (RuntimeException abortFailure) {
                    // 原始恢复失败更重要；abort 失败交由任务重试/告警处理。
                    log.error("线上恢复终止失败｜订单ID={}｜会话ID={}｜原因={}", orderId, session.id(), abortFailure.getMessage(), abortFailure);
                }
            }
        }
        return RestoreOutcome.COMMITTED;
    }

    /** 恢复订单 ZIP 内单个资产的执行证据回调；默认无操作，兼容非任务调用方。 */
    public interface RestoreAssetListener {
        void started(String assetIdentity, String zipEntryName, String objectKey, long totalBytes);
        void succeeded(String assetIdentity, String zipEntryName, String objectKey, long uploadedBytes);
        default void failed(String assetIdentity, String zipEntryName, String objectKey, long uploadedBytes, Throwable failure) { }
        static RestoreAssetListener noOp() {
            return new RestoreAssetListener() {
                @Override public void started(String assetIdentity, String zipEntryName, String objectKey, long totalBytes) { }
                @Override public void succeeded(String assetIdentity, String zipEntryName, String objectKey, long uploadedBytes) { }
            };
        }
    }

    /**
     * 从订单包中的 manifest.json 固化 prepare 所需摘要。没有 manifest 的旧包仍走旧接口，
     * 这样历史测试包和早期本地备份不会被无故拒绝；新包必须包含完整摘要。
     */
    private OnlineRestoreClient.RestoreRequest readPrepareRequest(Path orderZip, String orderId) throws IOException {
        try (ZipFile zip = new ZipFile(orderZip.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("manifest.json");
            if (manifestEntry == null) return null;
            byte[] bytes;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                bytes = in.readAllBytes();
            }
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) throw new IOException("manifest.json 格式非法");
            String manifestSha = text(root, "manifestSha256", "manifestJcsSha256");
            if (manifestSha == null || manifestSha.isBlank()) manifestSha = sha256(bytes);
            String revision = text(root, "manifestRevision", "revision");
            if (revision == null || revision.isBlank()) revision = manifestSha;
            int schemaVersion = root.path("manifestSchemaVersion").asInt(1);
            JsonNode assets = root.path("assets");
            List<String> identities = new ArrayList<>();
            List<JsonNode> relations = new ArrayList<>();
            List<OnlineRestoreClient.RestoreAsset> restoreAssets = new ArrayList<>();
            if (assets.isArray()) {
                for (JsonNode asset : assets) {
                    String identity = text(asset, "assetIdentity", "assetId", "id");
                    if (identity != null && !identity.isBlank()) identities.add(identity);
                    relations.add(asset.path("relations"));
                    if (identity != null && !identity.isBlank()) {
                        // 新版备份 manifest 将来源定位字段放在资产根节点；兼容旧版 sourceObject 嵌套结构。
                        JsonNode source = asset.path("sourceObject");
                        String platform = text(asset, "platform");
                        if (platform == null || platform.isBlank()) platform = text(source, "platform");
                        String sourceObjectKey = text(asset, "objectKey", "sourceKey");
                        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
                            sourceObjectKey = text(source, "objectKey", "sourceKey");
                        }
                        restoreAssets.add(new OnlineRestoreClient.RestoreAsset(identity,
                                text(asset, "originalFilename", "fileName"), asset.path("size").asLong(-1),
                                text(asset, "sha256"), platform, sourceObjectKey,
                                restoreRelations(asset.path("relations"), schemaVersion)));
                    }
                }
            }
            identities.sort(Comparator.naturalOrder());
            String assetSetDigest = sha256(mapper.writeValueAsBytes(identities));
            String relationSetHash = text(root, "relationSetHash");
            if (relationSetHash == null || relationSetHash.isBlank()) {
                relationSetHash = sha256(mapper.writeValueAsBytes(relations));
            }
            String completeness = text(root, "backupCompleteness");
            if (completeness == null || completeness.isBlank()) completeness = "COMPLETE";
            String relationshipStateHash = schemaVersion >= 2 ? text(root, "relationshipStateHash") : null;
            List<OnlineRestoreClient.RestoreRelationGap> relationGaps = new ArrayList<>();
            if (schemaVersion >= 2 && root.path("relationGaps").isArray()) {
                for (JsonNode gap : root.path("relationGaps")) {
                    relationGaps.add(new OnlineRestoreClient.RestoreRelationGap(
                            text(gap, "relationKind"), text(gap, "sourceRecordId"), text(gap, "relationOwnerKey"),
                            text(gap, "relationValue"), text(gap, "originalFileId"), text(gap, "reasonCode"),
                            text(gap, "reason"), text(gap, "relationHash")));
                }
            }
            if (schemaVersion >= 2 && (relationshipStateHash == null || relationshipStateHash.isBlank())) {
                throw new IOException("v2 manifest 缺少 relationshipStateHash");
            }
            String requestRelationDigest = schemaVersion >= 2 ? relationshipStateHash : relationSetHash;
            String requestDigest = sha256((orderId + "\n" + manifestSha + "\n"
                    + assetSetDigest + "\n" + requestRelationDigest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new OnlineRestoreClient.RestoreRequest(revision, manifestSha, conflictPolicy,
                    requestDigest, assetSetDigest, relationSetHash, schemaVersion, completeness,
                    relationshipStateHash, relationGaps, restoreAssets);
        } catch (IOException ex) {
            throw new IOException("读取恢复包 manifest.json 失败", ex);
        }
    }

    /** 线上会话的业务终态；调用方必须区分“已删除而跳过”和恢复成功。 */
    public enum RestoreOutcome { COMMITTED, ALREADY_COMMITTED, SKIPPED_DELETED }

    /**
     * v2 关系摘要按固定字段投影生成。线上全局 NON_NULL 序列化会省略空 relationValue，
     * 恢复时补回显式 null，兼容已生成的 ZIP 及尚未升级的线上恢复端。
     */
    private List<Map<String, Object>> restoreRelations(JsonNode relations, int schemaVersion) {
        List<Map<String, Object>> values = mapper.convertValue(relations,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        if (schemaVersion < 2) return values;
        List<Map<String, Object>> normalized = new ArrayList<>(values.size());
        for (Map<String, Object> value : values) {
            Map<String, Object> relation = new java.util.LinkedHashMap<>(value);
            relation.putIfAbsent("relationValue", null);
            normalized.add(relation);
        }
        return List.copyOf(normalized);
    }

    /** 从 ZIP 读取并校验 manifest 根节点，供只读清单展示复用。 */
    private JsonNode readManifestRoot(ZipFile zip) throws IOException {
        ZipEntry manifest = zip.getEntry("manifest.json");
        if (manifest == null) throw new IOException("恢复包缺少 manifest.json");
        try (InputStream input = zip.getInputStream(manifest)) {
            JsonNode root = mapper.readTree(input.readAllBytes());
            if (root == null || !root.isObject()) throw new IOException("manifest.json 格式非法");
            return root;
        }
    }

    /** ZIP 内订单文件的只读清单。 */
    public record RestorePackageInventory(String manifestRevision, List<RestorePackageAsset> assets) { }

    /** 一个待恢复文件及其与订单业务的关系摘要。 */
    public record RestorePackageAsset(String assetIdentity, String fileName, String assetType,
                                      List<String> relationKinds, long size, boolean zipEntryPresent) { }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && !value.isNull() && value.isValueNode()) return value.asText();
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String safeEntryName(ZipEntry entry) throws IOException {
        String raw = entry.getName();
        if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0) {
            throw new IOException("压缩包条目名称非法");
        }
        String normalized = raw.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (normalized.startsWith("/") || path.isAbsolute() || path.startsWith("..")
                || normalized.contains("../") || normalized.contains("..\\")) {
            throw new IOException("包内路径非法");
        }
        return path.toString().replace('\\', '/');
    }

    /** 将本地包中的“资产ID-原始文件名”映射回线上清单的资产身份。 */
    private static String resolveAssetIdentity(String packageName, Set<String> expected) throws IOException {
        if (expected.contains(packageName)) return packageName;
        String match = null;
        for (String identity : expected) {
            if (packageName.startsWith(identity + "-")) {
                if (match != null) throw new IOException("恢复包资产身份匹配不唯一: " + packageName);
                match = identity;
            }
        }
        if (match == null) throw new IOException("恢复包资产不在线上清单中: " + packageName);
        return match;
    }

    /** 限制单个 Zip 条目的解压读取量，阻止 ZIP bomb 消耗无限内存/磁盘。 */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long count;

        private CountingInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = Math.max(0, limit);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++count > limit) {
                throw new IOException("压缩包解压总量超过限制");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0 && (count += read) > limit) {
                throw new IOException("压缩包解压总量超过限制");
            }
            return read;
        }

        private long count() {
            return count;
        }
    }
}
