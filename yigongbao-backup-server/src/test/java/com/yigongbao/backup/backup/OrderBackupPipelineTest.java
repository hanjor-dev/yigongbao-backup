package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import org.junit.jupiter.api.Test;
import java.io.*; import java.nio.file.*; import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrderBackupPipelineTest {
    @Test void allAssetsAreCommittedAsOnePackage() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O1","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper()); pipeline.backup("O1");
        assertThat(root.resolve("orders/O1/O1.zip")).exists();
    }
    @Test void anyAssetFailureFailsWholeOrderAndLeavesNoPackage() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O2","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1"),new BackupManifest.Asset("a2","b.txt","DRAWING",2,"bad")));
        var pipeline=new OrderBackupPipeline(id->m,key->{if(key.equals("bad")) throw new IOException("down"); return new ByteArrayInputStream("ok".getBytes());},store,new ObjectMapper());
        assertThatThrownBy(()->pipeline.backup("O2")).isInstanceOf(IllegalStateException.class).hasMessageContaining("整单回滚");
        assertThat(root.resolve("orders/O2/O2.zip")).doesNotExist();
    }
    @Test void manifestSizeMismatchFailsWholeOrder() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O3","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",3,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper());
        assertThatThrownBy(()->pipeline.backup("O3")).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("orders/O3/O3.zip")).doesNotExist();
    }
}
