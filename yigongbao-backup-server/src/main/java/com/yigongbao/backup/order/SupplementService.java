package com.yigongbao.backup.order;

import com.yigongbao.backup.storage.api.ArtifactStore;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import com.yigongbao.backup.config.RuntimeConfigService;

/** 订单补传只允许配置的工程文件类型和大小，第一期不提供删除。 */
public final class SupplementService {
    private final ArtifactStore store; private final Set<String> extensions; private final long maxBytes; private final BkSupplementFileMapper mapper; private final RuntimeConfigService runtimeConfig;
    public SupplementService(ArtifactStore store, Set<String> extensions, long maxBytes) { this(store, extensions, maxBytes, null); }
    public SupplementService(ArtifactStore store, Set<String> extensions, long maxBytes, BkSupplementFileMapper mapper) { this(store, extensions, maxBytes, mapper, null); }
    public SupplementService(ArtifactStore store, BkSupplementFileMapper mapper, RuntimeConfigService runtimeConfig) { this(store, Set.of("zip", "step", "stl", "dcm"), 1073741824L, mapper, runtimeConfig); }
    public SupplementService(ArtifactStore store, Set<String> extensions, long maxBytes, BkSupplementFileMapper mapper, RuntimeConfigService runtimeConfig) { this.store=store; this.extensions=extensions; this.maxBytes=maxBytes; this.mapper=mapper; this.runtimeConfig=runtimeConfig; }
    public String upload(String orderId, String fileName, InputStream content, long size) throws IOException {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("订单号非法");
        if (size < 0 || size > maxBytes()) throw new IllegalArgumentException("补传文件超过大小上限");
        String safe = safeFileName(fileName); int dot=safe.lastIndexOf('.'); String ext=dot<0?"":safe.substring(dot+1).toLowerCase();
        if (!allowedExtensions().contains(ext)) throw new IllegalArgumentException("补传文件格式不允许");
        String id=UUID.randomUUID().toString();
        String key="supplements/"+orderId+"/"+id+"-"+safe;
        store.put(key, bounded(content));
        return id;
    }

    /** 上传并在 bk_supplement_file 中登记元数据；数据库失败时尽力回滚已写入文件。 */
    public String uploadForSnapshot(long snapshotId, String orderId, String fileName, InputStream content,
                                   long size, long uploadedBy, String contentType, String remark) throws IOException {
        if (mapper == null) throw new IllegalStateException("补传元数据持久化未配置");
        if (snapshotId < 1) throw new IllegalArgumentException("订单快照非法");
        String safe = safeFileName(fileName);
        int dot = safe.lastIndexOf('.');
        String ext = dot < 0 ? "" : safe.substring(dot + 1).toLowerCase();
        if (!allowedExtensions().contains(ext)) throw new IllegalArgumentException("补传文件格式不允许");
        if (size < 0 || size > maxBytes()) throw new IllegalArgumentException("补传文件超过大小上限");
        String id = UUID.randomUUID().toString();
        String key = "supplements/" + safeOrder(orderId) + "/" + id + "-" + safe;
        String digest;
        long actualSize;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(bounded(content), sha)) {
                store.put(key, input);
            }
            Path path = store.root().resolve(key).normalize();
            if (!path.startsWith(store.root()) || !Files.isRegularFile(path)) throw new IOException("补传文件写入后不存在");
            actualSize = Files.size(path);
            digest = java.util.HexFormat.of().formatHex(sha.digest());
            BkSupplementFileEntity entity = new BkSupplementFileEntity();
            entity.setSnapshotId(snapshotId); entity.setStorageKey(key); entity.setOriginalFilename(safe);
            entity.setInternalFilename(id + "-" + safe); entity.setSize(actualSize); entity.setSha256(digest);
            entity.setContentType(contentType); entity.setExtension(ext); entity.setUploadedBy(uploadedBy);
            entity.setUploadedAt(LocalDateTime.now()); entity.setRemark(remark); entity.setHotStatus("AVAILABLE");
            entity.setColdCoverageStatus("PENDING"); entity.setCreateBy(uploadedBy); entity.setUpdateBy(uploadedBy);
            mapper.insert(entity);
            return id;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        } catch (RuntimeException | IOException e) {
            try { store.delete(key); } catch (IOException ignored) { }
            throw e;
        }
    }

    /** 按上传 ID 查找补传文件；不接受用户提交的路径。 */
    public InputStream open(String orderId, String fileId) throws IOException {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")
                || fileId == null || !fileId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("补传文件标识非法");
        }
        String prefix = fileId + "-";
        try (var paths = store.list("supplements/" + orderId)) {
            Path match = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst().orElse(null);
            if (match == null) {
                throw new java.nio.file.NoSuchFileException(fileId);
            }
            return store.open("supplements/" + orderId + "/" + match.getFileName());
        } catch (java.nio.file.NoSuchFileException exception) {
            throw exception;
        }
    }

    /** 列出指定订单的补传文件，文件 ID 从受控存储键中解析，不接受客户端路径。 */
    public List<SupplementInfo> list(String orderId) throws IOException {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("订单号非法");
        }
        try (var paths = store.list("supplements/" + orderId)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> {
                        String name = path.getFileName().toString();
                        String fileId = fileId(name);
                        if (fileId.isBlank() || name.length() <= 37) {
                            return null;
                        }
                        try { return new SupplementInfo(fileId, name.substring(37), Files.size(path)); }
                        catch (Exception ignored) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(SupplementInfo::fileId))
                    .toList();
        } catch (java.nio.file.NoSuchFileException exception) {
            return List.of();
        }
    }

    public List<SupplementInfo> listForSnapshot(long snapshotId, String orderId) throws IOException {
        if (mapper == null) return list(orderId);
        return mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<BkSupplementFileEntity>lambdaQuery()
                        .eq(BkSupplementFileEntity::getSnapshotId, snapshotId)
                        .orderByDesc(BkSupplementFileEntity::getUploadedAt))
                .stream().map(entity -> new SupplementInfo(fileId(entity.getInternalFilename()), entity.getOriginalFilename(), entity.getSize())).toList();
    }

    public InputStream openForSnapshot(long snapshotId, String fileId) throws IOException {
        if (mapper == null) throw new IllegalStateException("补传元数据持久化未配置");
        BkSupplementFileEntity entity = mapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<BkSupplementFileEntity>lambdaQuery()
                        .eq(BkSupplementFileEntity::getSnapshotId, snapshotId)
                        .eq(BkSupplementFileEntity::getIsDeleted, 0))
                .stream().filter(item -> fileId.equals(fileId(item.getInternalFilename()))).findFirst().orElse(null);
        if (entity == null) throw new java.nio.file.NoSuchFileException(fileId);
        return store.open(entity.getStorageKey());
    }

    private Set<String> allowedExtensions() {
        return runtimeConfig == null ? extensions : runtimeConfig.getCsvSet("supplement.allowed-extensions", String.join(",", extensions));
    }

    private long maxBytes() {
        return runtimeConfig == null ? maxBytes : runtimeConfig.getLong("supplement.max-file-size-bytes", maxBytes, 1L);
    }

    private InputStream bounded(InputStream source) {
        long limit = maxBytes();
        return new FilterInputStream(source) {
            private long count;

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value >= 0 && ++count > limit) {
                    throw new IOException("补传文件实际大小超过上限");
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0 && (count += read) > limit) {
                    throw new IOException("补传文件实际大小超过上限");
                }
                return read;
            }
        };
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("补传文件名为空或非法");
        }
        String safe = java.nio.file.Paths.get(fileName).getFileName().toString();
        if (safe.isBlank() || safe.length() > 255) {
            throw new IllegalArgumentException("补传文件名过长");
        }
        return safe;
    }

    private static String safeOrder(String orderId) {
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("订单号非法");
        return orderId;
    }

    private static String fileId(String internalFilename) {
        if (internalFilename == null) return "";
        if (internalFilename.length() < 37 || internalFilename.charAt(36) != '-') return "";
        try { return UUID.fromString(internalFilename.substring(0, 36)).toString(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    public record SupplementInfo(String fileId, String fileName, long size) {
    }
}
