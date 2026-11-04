package com.yigongbao.backup.cold;

import com.yigongbao.backup.storage.api.ArtifactStore;
import java.io.IOException; import java.io.InputStream; import java.nio.file.*; import java.util.*; import java.util.zip.*;

/** 冷备写入基础：管理员明确选择目标盘，按批次生成一个可校验压缩包；系统不自动操作未选择的盘。 */
public final class ColdBatchService {
    private final ArtifactStore hot;
    public ColdBatchService(ArtifactStore hot) { this.hot=hot; }
    public Path writeBatch(String batchId, Path selectedColdRoot, List<String> orderPackages) throws IOException {
        if (selectedColdRoot == null || orderPackages == null || orderPackages.isEmpty()) throw new IllegalArgumentException("必须选择目标盘和订单包");
        Path root=selectedColdRoot.toAbsolutePath().normalize(); Files.createDirectories(root); Path tmp=root.resolve("."+batchId+".part"); Path target=root.resolve(batchId+".zip");
        List<String> manifestEntries = new ArrayList<>();
        try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(tmp))) {
            for(String relative:orderPackages) {
                Path source=hot.root().resolve(relative).normalize();
                if(!source.startsWith(hot.root())||!Files.isRegularFile(source)) throw new IOException("热备订单包不存在");
                String order = orderOf(relative);
                if(order == null) throw new IOException("订单包路径必须位于 orders/{orderId} 下");
                String entryName = entryName(relative, order, source.getFileName().toString());
                out.putNextEntry(new ZipEntry(entryName)); Files.copy(source,out); out.closeEntry();
                manifestEntries.add(entryName);
            }
            String manifest = "{\"format\":\"COLD_ARCHIVE_V1\",\"batchId\":\"" + escape(batchId)
                    + "\",\"entries\":[" + manifestEntries.stream().map(e -> "\"" + escape(e) + "\"").reduce((a,b)->a+","+b).orElse("") + "]}";
            out.putNextEntry(new ZipEntry("batch-manifest.json")); out.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new ZipEntry("_COMPLETE")); out.write(batchId.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
        try { return Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE); } catch(AtomicMoveNotSupportedException e) { return Files.move(tmp,target); }
    }

    /** 在写出冷备包前按热备订单包大小估算目标盘空间，供持久化任务预留使用。 */
    public long estimateBatch(List<String> orderPackages) throws IOException {
        if (orderPackages == null || orderPackages.isEmpty()) {
            throw new IllegalArgumentException("订单包不能为空");
        }
        long total = 0L;
        for (String relative : orderPackages) {
            Path source = hot.root().resolve(relative).normalize();
            if (!source.startsWith(hot.root()) || !Files.isRegularFile(source)) {
                throw new IOException("热备订单包不存在");
            }
            long size = Files.size(source);
            if (size < 0 || Long.MAX_VALUE - total < size) {
                throw new IOException("冷备空间估算溢出");
            }
            total += size;
        }
        return Math.max(1L, total);
    }

    private static String orderOf(String relative) {
        String n = relative.replace('\\','/');
        String[] p = n.split("/");
        if (p.length >= 3 && "orders".equals(p[0]) && p[1].matches("[A-Za-z0-9_-]{1,64}")
                && !p[1].matches("\\d{8}")) return orderIdFromFile(p[p.length - 1]);
        if (p.length >= 3 && "orders".equals(p[0]) && p[1].matches("\\d{8}")) return orderIdFromFile(p[p.length - 1]);
        return null;
    }

    private static String orderIdFromFile(String fileName) {
        if (fileName == null || !fileName.endsWith(".zip")) return null;
        String stem = fileName.substring(0, fileName.length() - 4);
        int separator = stem.lastIndexOf('-');
        String id = separator < 0 ? stem : stem.substring(separator + 1);
        return id.matches("[A-Za-z0-9_-]{1,64}") ? id : null;
    }

    private static String entryName(String relative, String order, String fileName) {
        String n = relative.replace('\\', '/');
        String[] parts = n.split("/");
        if (parts.length >= 3 && parts[1].matches("\\d{8}")) {
            return "orders/" + order + "/original/" + parts[1] + "/" + fileName;
        }
        return "orders/" + order + "/original/" + fileName;
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
