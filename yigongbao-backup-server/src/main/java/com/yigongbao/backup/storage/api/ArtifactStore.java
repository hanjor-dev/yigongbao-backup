package com.yigongbao.backup.storage.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.stream.Stream;

/** 文件对象存储抽象；实现必须保证目标路径不能逃逸根目录。 */
public interface ArtifactStore {
    Path put(String relativePath, InputStream content) throws IOException;
    InputStream open(String relativePath) throws IOException;
    Stream<Path> list(String relativeDirectory) throws IOException;
    void move(String source, String target) throws IOException;
    /** 删除热备对象；调用方必须先完成冷备校验和管理员确认。 */
    void delete(String relativePath) throws IOException;
    Path root();
}
