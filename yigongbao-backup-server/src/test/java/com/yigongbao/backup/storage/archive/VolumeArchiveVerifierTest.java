package com.yigongbao.backup.storage.archive;

import com.yigongbao.backup.storage.volume.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class VolumeArchiveVerifierTest {
    @Test void rejectsFileWhoseSizeChangedAfterManifestFreeze() throws Exception {
        Path root=Files.createTempDirectory("archive-verify"); Files.createDirectories(root.resolve("orders")); Files.writeString(root.resolve("orders/a.bin"),"changed");
        StorageVolumeEntity volume=new StorageVolumeEntity();volume.setId(1L);volume.setRootPath(root.toString());
        StorageVolumeMapper mapper=mock(StorageVolumeMapper.class);when(mapper.selectById(1L)).thenReturn(volume);
        VolumeArchiveItemEntity item=new VolumeArchiveItemEntity();item.setStorageVolumeId(1L);item.setStorageKey("orders/a.bin");item.setArtifactType("SUPPLEMENT_FILE");item.setExpectedSize(1L);
        VolumeArchiveVerifier.VerificationResult result=new VolumeArchiveVerifier(mapper).verify(item);
        assertThat(result.success()).isFalse();assertThat(result.code()).isEqualTo("SIZE_MISMATCH");
    }

    @Test void acceptsOrderPackageWhoseManifestDeclaresThePersistedOnlineJcsDigest() throws Exception {
        Path root=Files.createTempDirectory("archive-manifest");
        Path file=writeOrderPackage(root,"{\"manifestSha256\":\"online-jcs-digest\",\"assets\":[]}");
        VolumeArchiveVerifier.VerificationResult result=verifier(root).verify(orderPackage(file,"online-jcs-digest"));
        assertThat(result.success()).isTrue();
    }

    @Test void rejectsOrderPackageWhoseManifestDeclaresAnotherDigest() throws Exception {
        Path root=Files.createTempDirectory("archive-manifest-mismatch");
        Path file=writeOrderPackage(root,"{\"manifestSha256\":\"other-digest\",\"assets\":[]}");
        VolumeArchiveVerifier.VerificationResult result=verifier(root).verify(orderPackage(file,"online-jcs-digest"));
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("MANIFEST_SHA256_MISMATCH");
    }

    @Test void rejectsOrderPackageWhoseManifestDigestIsMissingOrMalformed() throws Exception {
        Path missingRoot=Files.createTempDirectory("archive-manifest-missing");
        Path missing=writeOrderPackage(missingRoot,"{\"assets\":[]}");
        assertThat(verifier(missingRoot).verify(orderPackage(missing,"online-jcs-digest")).code())
                .isEqualTo("MANIFEST_SHA256_MISSING");

        Path malformedRoot=Files.createTempDirectory("archive-manifest-malformed");
        Path malformed=writeOrderPackage(malformedRoot,"{not-json");
        assertThat(verifier(malformedRoot).verify(orderPackage(malformed,"online-jcs-digest")).code())
                .isEqualTo("MANIFEST_INVALID");
    }

    @Test void rejectsWindowsJunctionThatEscapesTheManagedRoot() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path root=Files.createTempDirectory("archive-junction-root");
        Path outside=Files.createTempDirectory("archive-junction-outside");
        Files.writeString(outside.resolve("a.bin"),"outside");
        Path junction=root.resolve("orders");
        Process process=new ProcessBuilder("cmd","/c","mklink","/J",junction.toString(),outside.toString())
                .redirectErrorStream(true).start();
        assumeTrue(process.waitFor()==0,"当前环境无法创建 Windows junction");
        try {
            VolumeArchiveItemEntity item=new VolumeArchiveItemEntity();item.setStorageVolumeId(1L);
            item.setStorageKey("orders/a.bin");item.setArtifactType("SUPPLEMENT_FILE");
            VolumeArchiveVerifier.VerificationResult result=verifier(root).verify(item);
            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("LINK_NOT_ALLOWED");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    private static VolumeArchiveVerifier verifier(Path root) {
        StorageVolumeEntity volume=new StorageVolumeEntity(); volume.setId(1L); volume.setRootPath(root.toString());
        StorageVolumeMapper mapper=mock(StorageVolumeMapper.class); when(mapper.selectById(1L)).thenReturn(volume);
        return new VolumeArchiveVerifier(mapper);
    }

    private static VolumeArchiveItemEntity orderPackage(Path file,String manifestDigest) throws Exception {
        VolumeArchiveItemEntity item=new VolumeArchiveItemEntity(); item.setStorageVolumeId(1L);
        item.setStorageKey("orders/package.zip"); item.setArtifactType("ORDER_PACKAGE");
        item.setExpectedSize(Files.size(file)); item.setExpectedSha256(sha256(file));
        item.setExpectedManifestSha256(manifestDigest); return item;
    }

    private static Path writeOrderPackage(Path root,String manifest) throws Exception {
        Path directory=root.resolve("orders"); Files.createDirectories(directory); Path file=directory.resolve("package.zip");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))){
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return file;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(file)){byte[] buffer=new byte[8192];for(int read;(read=input.read(buffer))>=0;)if(read>0)digest.update(buffer,0,read);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
