package com.yigongbao.backup.storage;

import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import com.yigongbao.backup.storage.reservation.InMemorySpaceReservationService;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StorageContractTest {
    @Test void pathAndAtomicMoveContract() throws Exception {
        Path root = Files.createTempDirectory("store"); var store = new LocalDirectoryArtifactStore(root);
        store.put("orders/a.txt", new java.io.ByteArrayInputStream("ok".getBytes()));
        assertThat(Files.readString(root.resolve("orders/a.txt"))).isEqualTo("ok");
        assertThatThrownBy(() -> store.open("../escape")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSymbolicLinkEscape() throws Exception {
        Path root = Files.createTempDirectory("store-root");
        Path outside = Files.createTempDirectory("store-outside");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException denied) {
            return; // Windows developer mode/CI may disallow symlink creation.
        }
        var store = new LocalDirectoryArtifactStore(root);
        assertThatThrownBy(() -> store.put("linked/escape.txt", new java.io.ByteArrayInputStream("no".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentReservationsCannotOversell() throws Exception {
        var service = new InMemorySpaceReservationService(); service.register("nas-a", 100);
        ExecutorService pool = Executors.newFixedThreadPool(8); var tasks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> (Callable<Boolean>) () -> { try { service.reserve("nas-a", 20); return true; } catch (IllegalStateException e) { return false; } }).toList();
        long success = pool.invokeAll(tasks).stream().filter(f -> { try { return f.get(); } catch (Exception e) { return false; } }).count();
        pool.shutdown(); assertThat(success).isEqualTo(5);
        assertThatThrownBy(() -> service.reserve("unknown", 1)).isInstanceOf(IllegalStateException.class);
    }
}
