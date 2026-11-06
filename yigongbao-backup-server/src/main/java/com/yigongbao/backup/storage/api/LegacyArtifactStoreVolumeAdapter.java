package com.yigongbao.backup.storage.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 仅用于历史测试和迁移兼容的单根目录适配器；生产写入必须使用真实卷路由。 */
public final class LegacyArtifactStoreVolumeAdapter implements VolumeArtifactStore {
    private final ArtifactStore delegate;
    public LegacyArtifactStoreVolumeAdapter(ArtifactStore delegate) { this.delegate = delegate; }
    /** 返回旧制品仓库，仅供历史单根目录测试兼容；生产代码不得调用。 */
    public ArtifactStore delegate() { return delegate; }
    public Path put(long volumeId, String key, InputStream content) throws IOException { return delegate.put(key, content); }
    public InputStream open(long volumeId, String key) throws IOException { return delegate.open(key); }
    public Stream<Path> list(long volumeId, String key) throws IOException { return delegate.list(key); }
    public void move(long volumeId, String source, String target) throws IOException { delegate.move(source, target); }
    public void delete(long volumeId, String key) throws IOException { delegate.delete(key); }
    public Path resolve(long volumeId, String key) { return delegate.root().resolve(key).normalize(); }
}
