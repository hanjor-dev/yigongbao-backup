package com.yigongbao.backup.storage.local;

import com.yigongbao.backup.storage.api.ArtifactStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Objects;
import java.util.stream.Stream;

/** 本机目录、NAS 挂载目录和可拔盘目录共用的安全实现。 */
public final class LocalDirectoryArtifactStore implements ArtifactStore {
    private final Path root;
    public LocalDirectoryArtifactStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public Path put(String relativePath, InputStream content) throws IOException {
        Path target = safe(relativePath); Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".part");
        try {
            try (InputStream in = content) { Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING); }
            try { return Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { return Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    public InputStream open(String relativePath) throws IOException { return Files.newInputStream(safe(relativePath)); }
    /** 解析卷内路径但不打开文件，供下载响应和受控临时处理使用。 */
    public Path resolve(String relativePath) { return safe(relativePath); }
    public Stream<Path> list(String relativeDirectory) throws IOException { return Files.list(safe(relativeDirectory)); }
    public void move(String source, String target) throws IOException {
        Path from = safe(source), to = safe(target); Files.createDirectories(to.getParent());
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(from, to); }
    }
    /** 在根目录边界内删除单个热备文件。 */
    public void delete(String relativePath) throws IOException {
        Path target = safe(relativePath);
        if (Files.exists(target) && !Files.isRegularFile(target)) throw new IOException("热备对象不是普通文件");
        Files.deleteIfExists(target);
    }
    private Path safe(String value) {
        Objects.requireNonNull(value, "relativePath");
        Path resolved = root.resolve(value).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("路径超出存储根目录");
        return resolved;
    }
}
