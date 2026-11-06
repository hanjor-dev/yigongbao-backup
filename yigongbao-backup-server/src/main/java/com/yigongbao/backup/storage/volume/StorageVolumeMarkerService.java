package com.yigongbao.backup.storage.volume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** 受控存储根目录的卷身份标记读写服务。 */
public class StorageVolumeMarkerService {
    private static final String MARKER_FILENAME = ".yigongbao-volume.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建受控根目录与不可变卷标记；已有不匹配标记会拒绝覆盖。 */
    public StorageVolume initialize(Path root, UUID volumeId) throws IOException {
        Files.createDirectories(root);
        VerificationResult existing = verify(root, volumeId);
        if (existing.exists() && !existing.valid()) {
            throw new IllegalStateException("受控目录已存在其他存储卷标记");
        }
        Path marker = root.resolve(MARKER_FILENAME);
        if (!existing.exists()) {
            String content = objectMapper.writeValueAsString(new MarkerPayload(volumeId.toString(), 1, Instant.now().toString()));
            Files.writeString(marker, content);
        }
        return new StorageVolume(volumeId, marker);
    }

    /** 校验当前挂载点的卷标记，盘符变化时只要标记匹配即可识别为同一卷。 */
    public VerificationResult verify(Path root, UUID expectedVolumeId) {
        Path marker = root.resolve(MARKER_FILENAME);
        if (!Files.isRegularFile(marker)) {
            return new VerificationResult(false, false, "卷标记不存在");
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(marker));
            boolean matched = expectedVolumeId.toString().equals(node.path("volumeId").asText());
            return new VerificationResult(true, matched, matched ? null : "卷标记与登记卷不匹配");
        } catch (IOException exception) {
            return new VerificationResult(true, false, "卷标记无法读取：" + exception.getMessage());
        }
    }

    /** 成功初始化后的卷标记信息。 */
    public record StorageVolume(UUID volumeId, Path markerPath) {
    }

    /** 标记校验结果。 */
    public record VerificationResult(boolean exists, boolean valid, String reason) {
    }

    private record MarkerPayload(String volumeId, int formatVersion, String createdAt) {
    }
}
