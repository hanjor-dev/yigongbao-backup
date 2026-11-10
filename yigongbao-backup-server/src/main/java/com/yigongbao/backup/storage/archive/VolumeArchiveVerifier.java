package com.yigongbao.backup.storage.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 对冻结清单逐项执行不信任文件系统的完整校验。 */
@Service
@RequiredArgsConstructor
public class VolumeArchiveVerifier {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StorageVolumeMapper volumeMapper;

    public VerificationResult verify(VolumeArchiveItemEntity item) {
        try {
            StorageVolumeEntity volume = volumeMapper.selectById(item.getStorageVolumeId());
            if (volume == null) return VerificationResult.failed("VOLUME_NOT_FOUND", "存储卷不存在");
            Path root = Path.of(volume.getRootPath()).toAbsolutePath().normalize();
            Path file = root.resolve(ManagedVolumeArtifactCatalog.normalizeStorageKey(item.getStorageKey())).normalize();
            if (!file.startsWith(root)) return VerificationResult.failed("PATH_ESCAPE", "文件路径越出受控根目录");
            Path cursor = root;
            if (isLinkOrReparsePoint(cursor))
                return VerificationResult.failed("LINK_NOT_ALLOWED", "受控根目录是链接或重解析点");
            for (Path part : root.relativize(file)) {
                cursor = cursor.resolve(part);
                if (isLinkOrReparsePoint(cursor))
                    return VerificationResult.failed("LINK_NOT_ALLOWED", "受管路径包含链接或重解析点");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return VerificationResult.failed("FILE_MISSING", "受管文件不存在");
            // 摘要与 ZIP 内容必须基于同一个已打开文件句柄，避免路径在两次打开之间被替换。
            try (FileChannel channel=FileChannel.open(file, StandardOpenOption.READ)) {
                long size=channel.size();
                if (item.getExpectedSize()!=null && item.getExpectedSize()!=size)
                    return VerificationResult.failed("SIZE_MISMATCH", "文件大小不一致");
                String sha=digest(Channels.newInputStream(channel));
                if (item.getExpectedSha256()!=null && !item.getExpectedSha256().equalsIgnoreCase(sha))
                    return VerificationResult.failed("SHA256_MISMATCH", "文件摘要不一致");
                if ("ORDER_PACKAGE".equals(item.getArtifactType())) {
                    channel.position(0);
                    byte[] manifestBytes=null;
                    try (ZipInputStream zip=new ZipInputStream(Channels.newInputStream(channel))) {
                        for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                            validateZipEntry(entry.getName());
                            if("manifest.json".equals(entry.getName())&&!entry.isDirectory()) manifestBytes=readBounded(zip,MAX_MANIFEST_BYTES);
                            else drain(zip);
                            zip.closeEntry();
                        }
                    }
                    if(manifestBytes==null) return VerificationResult.failed("MANIFEST_MISSING", "订单包缺少 manifest.json");
                    JsonNode manifest;
                    try { manifest=JSON.readTree(manifestBytes); }
                    catch (Exception invalid) { return VerificationResult.failed("MANIFEST_INVALID", "manifest.json 不是合法 JSON"); }
                    String declared=text(manifest,"manifestSha256","manifestJcsSha256");
                    if(item.getExpectedManifestSha256()!=null&&!item.getExpectedManifestSha256().isBlank()) {
                        if(declared==null||declared.isBlank())
                            return VerificationResult.failed("MANIFEST_SHA256_MISSING", "manifest 缺少线上 JCS 摘要声明");
                        if(!item.getExpectedManifestSha256().equalsIgnoreCase(declared))
                            return VerificationResult.failed("MANIFEST_SHA256_MISMATCH", "manifest 声明摘要与台账不一致");
                    }
                }
                return new VerificationResult(true, null, null, size, sha);
            }
        } catch (Exception failure) {
            return VerificationResult.failed("VERIFY_IO_ERROR", failure.getMessage());
        }
    }

    private static void validateZipEntry(String name) {
        String value = name.replace('\\', '/');
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || value.startsWith("/") || value.matches("^[A-Za-z]:.*") || path.startsWith(".."))
            throw new IllegalStateException("ZIP 包含非法路径：" + name);
    }
    private static boolean isLinkOrReparsePoint(Path path) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        // Windows junction 等 reparse point 在 NOFOLLOW_LINKS 下通常表现为 isOther，
        // 不能只依赖 Files.isSymbolicLink，否则可能跟随路径读到受控卷之外。
        return attributes.isSymbolicLink() || attributes.isOther();
    }
    private static String digest(InputStream input) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[64*1024];for(int n;(n=input.read(b))>=0;)if(n>0)d.update(b,0,n);return HexFormat.of().formatHex(d.digest()); }
    private static void drain(InputStream input) throws Exception { byte[] b=new byte[64*1024];while(input.read(b)>=0){} }
    private static byte[] readBounded(InputStream input,int limit) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];
        for(int read;(read=input.read(buffer))>=0;){
            if(read==0)continue;
            if(output.size()+read>limit)throw new IllegalStateException("manifest.json 超过大小限制");
            output.write(buffer,0,read);
        }
        return output.toByteArray();
    }
    private static String text(JsonNode node,String... names){
        for(String name:names){JsonNode value=node==null?null:node.get(name);if(value!=null&&!value.isNull()&&value.isTextual())return value.asText();}
        return null;
    }

    public record VerificationResult(boolean success, String code, String message, Long actualSize, String actualSha256) {
        static VerificationResult failed(String code,String message){return new VerificationResult(false,code,message,null,null);}
    }
}
