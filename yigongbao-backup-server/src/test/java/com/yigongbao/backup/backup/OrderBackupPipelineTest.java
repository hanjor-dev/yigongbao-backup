package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.storage.local.LocalDirectoryArtifactStore;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.io.*; import java.nio.file.*; import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrderBackupPipelineTest {
    @Test void repeatedBackupOfSameManifestProducesTheSamePackageDigest() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O-IDEMPOTENT","2026-01-01T00:00:00Z",
                List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper());

        var first=pipeline.backupWithResult("O-IDEMPOTENT",m);
        Thread.sleep(2100L);
        var second=pipeline.backupWithResult("O-IDEMPOTENT",m);

        assertThat(second.packageSha256()).isEqualTo(first.packageSha256());
        assertThat(second.packageSize()).isEqualTo(first.packageSize());
    }

    @Test void keepsDiagnosticWordsButRedactsActualCredentialValues() {
        String safe = OrderBackupPipeline.safeError(new IllegalStateException(
                "租约或 fencing token 无效; signature=very-secret"));

        assertThat(safe).contains("fencing token 无效", "signature=[REDACTED]").doesNotContain("very-secret");
    }
    @Test void logsOrderFilePackageAndCommitStagesWithoutExposingSourceUrl() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        String sensitiveUrl="https://cos.example/private?a-secret-signature";
        var m=new BackupManifest("O-LOG","2026-01-01T00:00:00Z",
                List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,sensitiveUrl)));
        Logger logger=(Logger) LoggerFactory.getLogger(OrderBackupPipeline.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper())
                    .backupWithResult("O-LOG",m);
        } finally { logger.detachAppender(appender); }

        String logs=appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs).contains("订单备份流水线开始", "文件下载开始", "订单包压缩完成", "订单备份制品提交完成",
                "文件容量=2 字节", "执行耗时=");
        assertThat(logs).doesNotContain(sensitiveUrl).doesNotContain("a-secret-signature");
    }
    @Test void reportsTheExactAssetBeingDownloaded() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O0","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        List<String> events=new ArrayList<>();
        var listener=new OrderBackupPipeline.AssetProgressListener() {
            public void started(BackupManifest.Asset asset){events.add("start:"+asset.assetId());}
            public void succeeded(BackupManifest.Asset asset){events.add("success:"+asset.assetId());}
            public void failed(BackupManifest.Asset asset,Throwable failure){events.add("fail:"+asset.assetId());}
        };

        new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper())
                .backupWithResult("O0",m,listener);

        assertThat(events).containsExactly("start:a1","success:a1");
    }
    @Test void allAssetsAreCommittedAsOnePackage() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O1","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper()); pipeline.backup("O1");
        assertThat(root.resolve("orders/" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/O1-O1.zip")).exists();
    }
    @Test void anyAssetFailureFailsWholeOrderAndLeavesNoPackage() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O2","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1"),new BackupManifest.Asset("a2","b.txt","DRAWING",2,"bad")));
        var pipeline=new OrderBackupPipeline(id->m,key->{if(key.equals("bad")) throw new IOException("down"); return new ByteArrayInputStream("ok".getBytes());},store,new ObjectMapper());
        assertThatThrownBy(()->pipeline.backup("O2")).isInstanceOf(IllegalStateException.class).hasMessageContaining("整单回滚");
        assertThat(root.resolve("orders/" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/O2-O2.zip")).doesNotExist();
    }
    @Test void manifestSizeMismatchFailsWholeOrder() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O3","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",3,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper());
        assertThatThrownBy(()->pipeline.backup("O3")).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("orders/" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/O3-O3.zip")).doesNotExist();
    }
    @Test void lostLeaseBeforePublishLeavesNoCommittedPackage() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("O4","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        var pipeline=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper());
        assertThatThrownBy(() -> pipeline.backupWithResult("O4",m,OrderBackupPipeline.AssetProgressListener.NOOP,
                () -> { throw new IllegalStateException("租约或 fencing token 无效"); }))
                .hasMessageContaining("整单回滚");
        assertThat(root.resolve("orders/" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/O4-O4.zip")).doesNotExist();
    }

    @Test void storesPackageUnderBackupDateAndSafeOrderNumber() throws Exception {
        Path root=Files.createTempDirectory("backup"); var store=new LocalDirectoryArtifactStore(root);
        var m=new BackupManifest("42","2026-01-01T00:00:00Z",List.of(new BackupManifest.Asset("a1","a.txt","DESIGN",2,"k1")));
        var result=new OrderBackupPipeline(id->m,key->new ByteArrayInputStream("ok".getBytes()),store,new ObjectMapper())
                .backupWithResult("42",m,"20261103","ORD/2026-001",OrderBackupPipeline.AssetProgressListener.NOOP,() -> {});
        assertThat(result.storageKey()).isEqualTo("orders/20261103/ORD_2026-001-42.zip");
        assertThat(root.resolve(result.storageKey())).exists();
    }
}
