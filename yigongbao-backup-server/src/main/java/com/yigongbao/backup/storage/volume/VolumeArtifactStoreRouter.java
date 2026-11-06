package com.yigongbao.backup.storage.volume;

import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 根据存储卷台账即时创建安全的局部文件访问器。
 *
 * <p>不缓存启动时的热备根目录，因此 A/B/C 卷切换不会改变历史文件的读取位置。</p>
 */
@Service
@RequiredArgsConstructor
public class VolumeArtifactStoreRouter implements VolumeArtifactStore {
    private final StorageVolumeMapper volumeMapper;

    @Override
    public Path put(long storageVolumeId, String storageKey, InputStream content) throws IOException {
        return writableStore(storageVolumeId).put(storageKey, content);
    }

    @Override
    public InputStream open(long storageVolumeId, String storageKey) throws IOException {
        return readableStore(storageVolumeId).open(storageKey);
    }

    @Override
    public Stream<Path> list(long storageVolumeId, String storageKey) throws IOException {
        return readableStore(storageVolumeId).list(storageKey);
    }

    @Override
    public void move(long storageVolumeId, String sourceStorageKey, String targetStorageKey) throws IOException {
        writableStore(storageVolumeId).move(sourceStorageKey, targetStorageKey);
    }

    @Override
    public void delete(long storageVolumeId, String storageKey) throws IOException {
        writableStore(storageVolumeId).delete(storageKey);
    }

    @Override
    public Path resolve(long storageVolumeId, String storageKey) throws IOException {
        LocalDirectoryArtifactStore store = readableStore(storageVolumeId);
        Path path = store.resolve(storageKey);
        return path;
    }

    private LocalDirectoryArtifactStore readableStore(long storageVolumeId) {
        StorageVolumeEntity volume = required(storageVolumeId);
        if (!StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())) {
            throw new StorageVolumeUnavailableException("STORAGE_VOLUME_OFFLINE：存储卷未挂载或不可访问");
        }
        return new LocalDirectoryArtifactStore(Path.of(volume.getRootPath()));
    }

    private LocalDirectoryArtifactStore writableStore(long storageVolumeId) {
        StorageVolumeEntity volume = required(storageVolumeId);
        if (!StorageVolumeStatus.Mount.ONLINE.name().equals(volume.getMountStatus())) {
            throw new StorageVolumeUnavailableException("STORAGE_VOLUME_OFFLINE：存储卷未挂载或不可访问");
        }
        if (!StorageVolumeStatus.Write.ACTIVE.name().equals(volume.getWriteStatus())
                && !StorageVolumeStatus.Write.STANDBY.name().equals(volume.getWriteStatus())) {
            throw new StorageVolumeOperationException("存储卷为只读或已停用，禁止写入");
        }
        return new LocalDirectoryArtifactStore(Path.of(volume.getRootPath()));
    }

    private StorageVolumeEntity required(long storageVolumeId) {
        StorageVolumeEntity volume = volumeMapper.selectById(storageVolumeId);
        if (volume == null || Integer.valueOf(1).equals(volume.getIsDeleted())) {
            throw new StorageVolumeUnavailableException("STORAGE_VOLUME_NOT_FOUND：存储卷不存在");
        }
        return volume;
    }
}
