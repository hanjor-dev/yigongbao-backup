package com.yigongbao.backup.account;

import java.util.List;

/** 线上账号分页读取适配器；生产环境由 HMAC HTTP 实现注入。 */
public interface RemoteAccountClient {
    Page fetch(String cursor, int size);
    record Page(List<RemoteAccount> accounts, String nextCursor, boolean hasMore) {}
}
