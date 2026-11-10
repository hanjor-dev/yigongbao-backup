package com.yigongbao.backup.storage.volume;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 发现 Windows 服务账号可见的挂载点。
 *
 * <p>发现过程严格只读：不会创建受控目录、卷标记或数据库记录；这些动作只能由
 * 管理员后续纳管操作执行。</p>
 */
@Service
public class StorageVolumeDiscoveryService {
    private static final String MANAGED_DIRECTORY = "yigongbao-backup";

    /** 扫描当前服务账号可见的文件系统根目录。 */
    public List<DiscoveredStorageVolume> scan() {
        List<Path> roots = new ArrayList<>();
        for (File root : File.listRoots()) {
            roots.add(root.toPath());
        }
        return discoverMountedPaths(roots);
    }

    /** 为测试和已映射网络盘复用的只读发现入口。 */
    public List<DiscoveredStorageVolume> discoverMountedPaths(Collection<Path> mountPaths) {
        List<DiscoveredStorageVolume> candidates = new ArrayList<>();
        for (Path mountPath : mountPaths) {
            Path normalizedMount = mountPath.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedMount)) {
                continue;
            }
            try {
                FileStore fileStore = Files.getFileStore(normalizedMount);
                long totalBytes = fileStore.getTotalSpace();
                long usableBytes = fileStore.getUsableSpace();
                candidates.add(new DiscoveredStorageVolume(normalizedMount,
                        normalizedMount.resolve(MANAGED_DIRECTORY), classify(fileStore),
                        fileStore.name(), fileStore.type(), volumeSerial(fileStore), totalBytes, usableBytes,
                        Math.max(0L, totalBytes - usableBytes)));
            } catch (IOException ignored) {
                // 无法读取的挂载点不能被纳管，交由后续健康探测和管理员界面说明。
            }
        }
        return candidates;
    }

    /** WindowsFileStore 提供稳定 volume serial；非 Windows 文件系统明确返回空，不伪造物理身份。 */
    private String volumeSerial(FileStore store) {
        try { Object value=store.getAttribute("volume:vsn"); return value==null?null:String.valueOf(value); }
        catch (RuntimeException | IOException ignored) { return null; }
    }

    /** 根据 FileStore 名称/类型识别已映射网络盘；其它可见目录默认本地候选。 */
    private StorageAccessType classify(FileStore fileStore) {
        String signature = (fileStore.name() + " " + fileStore.type()).toLowerCase();
        if (signature.contains("cifs") || signature.contains("smb") || signature.contains("nfs")) {
            return StorageAccessType.MAPPED_NETWORK;
        }
        return StorageAccessType.LOCAL;
    }
}
