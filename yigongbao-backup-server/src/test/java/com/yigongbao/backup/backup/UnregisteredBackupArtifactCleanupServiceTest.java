package com.yigongbao.backup.backup;

import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobMapper;
import com.yigongbao.backup.storage.api.VolumeArtifactStore;
import com.yigongbao.backup.storage.volume.StorageVolumeEntity;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UnregisteredBackupArtifactCleanupServiceTest {
    @TempDir Path root;

    @Test void deletesUnregisteredPackageLeftByATerminalBackupJob() throws Exception {
        Path orphan = root.resolve("orders/20261113/O-118-118-job-91-rev.zip");
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "orphan");
        StorageVolumeMapper volumes = volumeMapper();
        UnregisteredBackupArtifactMapper references = mock(UnregisteredBackupArtifactMapper.class);
        when(references.findProtectedStorageKeys(7L)).thenReturn(List.of());
        JobMapper jobs = mock(JobMapper.class);
        JobEntity failed = new JobEntity(); failed.setId(91L); failed.setStatus("FAILED");
        when(jobs.selectById(91L)).thenReturn(failed);
        VolumeArtifactStore store = storeResolvingFrom(root);

        int cleaned = new UnregisteredBackupArtifactCleanupService(volumes, references, jobs, store).cleanupBatch();

        assertThat(cleaned).isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    @Test void preservesRegisteredPackagesAndPackagesOwnedByRetryableJobs() throws Exception {
        Path registered = root.resolve("orders/20261113/registered.zip");
        Path retryable = root.resolve("orders/20261113/O-119-119-job-92-rev.zip");
        Files.createDirectories(registered.getParent());
        Files.writeString(registered, "registered");
        Files.writeString(retryable, "retryable");
        StorageVolumeMapper volumes = volumeMapper();
        UnregisteredBackupArtifactMapper references = mock(UnregisteredBackupArtifactMapper.class);
        when(references.findProtectedStorageKeys(7L)).thenReturn(List.of("orders/20261113/registered.zip"));
        JobMapper jobs = mock(JobMapper.class);
        JobEntity created = new JobEntity(); created.setId(92L); created.setStatus("CREATED");
        when(jobs.selectById(92L)).thenReturn(created);

        int cleaned = new UnregisteredBackupArtifactCleanupService(
                volumes, references, jobs, storeResolvingFrom(root)).cleanupBatch();

        assertThat(cleaned).isZero();
        assertThat(registered).exists();
        assertThat(retryable).exists();
    }

    private StorageVolumeMapper volumeMapper() {
        StorageVolumeMapper mapper = mock(StorageVolumeMapper.class);
        StorageVolumeEntity volume = new StorageVolumeEntity();
        volume.setId(7L); volume.setTier("HOT"); volume.setMountStatus("ONLINE");
        volume.setOperationStatus("REPAIR_REQUIRED"); volume.setIsDeleted(0);
        when(mapper.selectList(any())).thenReturn(List.of(volume));
        return mapper;
    }

    private static VolumeArtifactStore storeResolvingFrom(Path root) throws Exception {
        VolumeArtifactStore store = mock(VolumeArtifactStore.class);
        when(store.resolve(eq(7L), anyString())).thenAnswer(invocation ->
                root.resolve(invocation.<String>getArgument(1)).normalize());
        return store;
    }
}
