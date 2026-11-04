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

/** 线上恢复协调器：订单内全部文件上传成功后才 commit，任何失败都 abort，订单整体失败。 */
public final class LocalOrderRestoreService {
    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final OnlineRestoreClient client;
    private final long maxUncompressedBytes;
    private final int maxEntries;
    private final ObjectMapper mapper;
    private final String conflictPolicy;

    public LocalOrderRestoreService(OnlineRestoreClient client) {
        this(client, DEFAULT_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_ENTRIES, new ObjectMapper(), "KEEP_ONLINE");
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, String conflictPolicy) {
        this(client, DEFAULT_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_ENTRIES, new ObjectMapper(), conflictPolicy);
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, long maxUncompressedBytes, int maxEntries) {
        this(client, maxUncompressedBytes, maxEntries, new ObjectMapper(), "KEEP_ONLINE");
    }

    public LocalOrderRestoreService(OnlineRestoreClient client, long maxUncompressedBytes, int maxEntries,
                                    ObjectMapper mapper) {
        this(client, maxUncompressedBytes, maxEntries, mapper, "KEEP_ONLINE");
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
        this.conflictPolicy = switch (conflictPolicy == null ? "KEEP_ONLINE" : conflictPolicy) {
            case "KEEP_ONLINE" -> "REJECT";
            case "OVERWRITE_ONLINE" -> "OVERWRITE";
            case "KEEP_NEWEST" -> "KEEP_NEWEST";
            default -> throw new IllegalArgumentException("恢复冲突策略非法");
        };
    }

    /**
     * 按 manifest 期望资产逐文件上传。任何一项失败都不提交线上会话，避免出现整单部分恢复。
     */
    public void restore(Path orderZip, String orderId) throws IOException {
        if (orderZip == null || !Files.isRegularFile(orderZip)) {
            throw new FileNotFoundException(String.valueOf(orderZip));
        }
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("订单标识非法");
        }

        OnlineRestoreClient.RestoreRequest prepareRequest = readPrepareRequest(orderZip, orderId);
        OnlineRestoreClient.Session session = prepareRequest == null
                ? client.prepare(orderId) : client.prepare(orderId, prepareRequest);
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
                CountingInputStream bounded = new CountingInputStream(in, maxUncompressedBytes - totalBytes);
                client.upload(session, assetName, bounded);
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
                }
            }
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
            JsonNode assets = root.path("assets");
            List<String> identities = new ArrayList<>();
            List<JsonNode> relations = new ArrayList<>();
            if (assets.isArray()) {
                for (JsonNode asset : assets) {
                    String identity = text(asset, "assetIdentity", "assetId", "id");
                    if (identity != null && !identity.isBlank()) identities.add(identity);
                    relations.add(asset.path("relations"));
                }
            }
            identities.sort(Comparator.naturalOrder());
            String assetSetDigest = sha256(mapper.writeValueAsBytes(identities));
            String relationSetHash = text(root, "relationSetHash");
            if (relationSetHash == null || relationSetHash.isBlank()) {
                relationSetHash = sha256(mapper.writeValueAsBytes(relations));
            }
            String requestDigest = sha256((orderId + "\n" + manifestSha + "\n"
                    + assetSetDigest + "\n" + relationSetHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new OnlineRestoreClient.RestoreRequest(revision, manifestSha, conflictPolicy,
                    requestDigest, assetSetDigest, relationSetHash);
        } catch (IOException ex) {
            throw new IOException("读取恢复包 manifest.json 失败", ex);
        }
    }

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
