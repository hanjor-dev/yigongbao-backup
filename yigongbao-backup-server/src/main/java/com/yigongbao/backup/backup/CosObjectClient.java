package com.yigongbao.backup.backup;

import java.io.IOException;
import java.io.InputStream;
import com.yigongbao.backup.cleanup.CleanupClient;

/** COS 对象下载抽象，生产实现负责 SDK、超时和重试策略。 */
public interface CosObjectClient {
    InputStream download(String sourceKey) throws IOException;

    /**
     * 使用线上签发的 permit 删除精确对象。旧下载实现无需立即实现该能力，避免把
     * permit 当成线上 HTTP 删除命令发送。
     */
    default void delete(String sourceKey, CleanupClient.Permit permit) throws IOException {
        throw new UnsupportedOperationException("当前 COS 客户端未配置授权删除能力");
    }

    /** 删除必须显式携带冻结时的精确对象版本，生产实现不得退化为按键删除。 */
    default void delete(String sourceKey, String versionIdentity, CleanupClient.Permit permit) throws IOException {
        throw new UnsupportedOperationException("当前 COS 客户端不支持精确版本删除");
    }
}
