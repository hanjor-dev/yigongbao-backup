package com.yigongbao.backup.storage.volume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 受控存储根目录的卷身份标记读写服务。 */
@Service
public class StorageVolumeMarkerService {
    private static final String MARKER_FILENAME = ".yigongbao-volume.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建受控根目录与不可变卷标记；已有不匹配标记会拒绝覆盖。 */
    public StorageVolume initialize(Path root, UUID volumeId) throws IOException {
        Files.createDirectories(root);
        MarkerIdentity existing = inspect(root);
        if (existing.exists() && (!existing.valid() || !volumeId.equals(existing.volumeId()))) {
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
        MarkerIdentity identity = inspect(root);
        if (!identity.exists()) return new VerificationResult(false, false, identity.reason());
        boolean matched = identity.valid() && expectedVolumeId.equals(identity.volumeId());
        return new VerificationResult(true, matched, matched ? null
                : identity.valid() ? "卷标记与登记卷不匹配" : identity.reason());
    }

    /** 从卷台账读取期望的介质身份；旧卷继续使用稳定派生标识。 */
    public UUID expectedVolumeId(StorageVolumeEntity volume) {
        try {
            return UUID.fromString(objectMapper.readTree(volume.getMarkerJson()).path("volumeId").asText());
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(("yigongbao-storage-volume:" + volume.getId())
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 读取目录中已有的卷身份；损坏标记必须显式返回无效，禁止静默覆盖。 */
    public MarkerIdentity inspect(Path root) {
        Path marker = root.resolve(MARKER_FILENAME);
        if (!Files.isRegularFile(marker)) return new MarkerIdentity(false, false, null, "卷标记不存在");
        try {
            JsonNode node = objectMapper.readTree(Files.readString(marker));
            UUID volumeId = UUID.fromString(node.path("volumeId").asText());
            int formatVersion = node.path("formatVersion").asInt(0);
            if (formatVersion < 1) return new MarkerIdentity(true, false, null, "卷标记格式版本非法");
            return new MarkerIdentity(true, true, volumeId, null);
        } catch (Exception exception) {
            return new MarkerIdentity(true, false, null, "卷标记无法读取：" + exception.getMessage());
        }
    }

    /** 成功初始化后的卷标记信息。 */
    public record StorageVolume(UUID volumeId, Path markerPath) {
    }

    /** 标记校验结果。 */
    public record VerificationResult(boolean exists, boolean valid, String reason) {
    }

    public record MarkerIdentity(boolean exists, boolean valid, UUID volumeId, String reason) {
    }

    private record MarkerPayload(String volumeId, int formatVersion, String createdAt) {
    }
}
