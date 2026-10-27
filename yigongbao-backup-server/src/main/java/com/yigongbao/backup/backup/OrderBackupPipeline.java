package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.storage.api.ArtifactStore;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.*;

/** 订单级备份：所有资产成功下载后才提交订单包，否则清理暂存目录并标记整单失败。 */
public final class OrderBackupPipeline {
    private final OnlineManifestClient manifests; private final CosObjectClient cos; private final ArtifactStore store; private final ObjectMapper mapper;
    public OrderBackupPipeline(OnlineManifestClient manifests, CosObjectClient cos, ArtifactStore store, ObjectMapper mapper) { this.manifests=manifests; this.cos=cos; this.store=store; this.mapper=mapper; }
    /** 下载并原子提交单个订单；任一资产失败均不保留订单包。 */
    public void backup(String orderId) {
        backup(orderId, manifests.fetch(orderId));
    }

    /** 使用已读取的同一份清单完成下载，避免清单读取与文件下载之间出现版本漂移。 */
    public void backup(String orderId, BackupManifest manifest) {
        if (manifest == null || manifest.assets() == null) throw new IllegalStateException("订单清单不存在");
        String base = "orders/" + orderId; Path staging = store.root().resolve(".staging").resolve(orderId + "-" + System.nanoTime());
        try { Files.createDirectories(staging); Path data = staging.resolve("files"); Files.createDirectories(data); java.util.Set<String> identities = new java.util.HashSet<>();
            for (BackupManifest.Asset asset : manifest.assets()) { if (asset.size() < 0 || asset.assetId() == null || !identities.add(asset.assetId())) throw new IllegalStateException("资产身份重复或大小非法"); Path target=data.resolve(asset.assetId()+"-"+safe(asset.fileName()));
                try (InputStream in=cos.download(asset.sourceKey()); OutputStream out=Files.newOutputStream(target)) { if (in==null) throw new IOException("COS对象不存在"); MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[8192]; long copied=0; int n; while((n=in.read(buffer))!=-1){copied+=n;if(copied>asset.size())throw new IOException("资产实际大小超过清单: "+asset.assetId());digest.update(buffer,0,n);out.write(buffer,0,n);} if(copied!=asset.size())throw new IOException("资产大小与清单不一致: "+asset.assetId()); if(asset.sha256()!=null&&!asset.sha256().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest())))throw new IOException("资产摘要与清单不一致: "+asset.assetId()); }
            }
            Path meta=staging.resolve("manifest.json"); mapper.writeValue(meta.toFile(), manifest); Path zip=staging.resolve(orderId+".zip");
            try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) { zipTree(data,out,data); add(out,meta,"manifest.json"); }
            try (InputStream in=Files.newInputStream(zip)) { store.put(base+"/"+orderId+".zip",in); }
        } catch (Exception ex) { throw new IllegalStateException("订单备份失败，整单回滚: "+orderId,ex); }
        finally { deleteTree(staging); }
    }
    /** 将外部文件名约束在单一文件名范围内，阻断路径穿越。 */
    private static String safe(String name) { String n=Paths.get(name).getFileName().toString(); if (n.isBlank()) throw new IllegalArgumentException("文件名为空"); return n; }
    /** 递归写入订单包目录。 */
    private static void zipTree(Path dir, ZipOutputStream out, Path root) throws IOException { try (var s=Files.list(dir)) { for(Path p:s.toList()) if(Files.isDirectory(p)) zipTree(p,out,root); else add(out,p,root.relativize(p).toString().replace('\\','/')); } }
    /** 写入一个压缩条目。 */
    private static void add(ZipOutputStream out, Path p, String name) throws IOException { out.putNextEntry(new ZipEntry(name)); Files.copy(p,out); out.closeEntry(); }
    /** 清理订单暂存目录，避免中间文件被误认为已备份。 */
    private static void deleteTree(Path p) { if (!Files.exists(p)) return; try (var s=Files.walk(p)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException ignored){}}); } catch(IOException ignored) {} }
}
