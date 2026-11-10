package com.yigongbao.backup.account;

import java.util.List;

/** 线上账号分页读取适配器；生产环境由 HMAC HTTP 实现注入。 */
public interface RemoteAccountClient {
    Page fetch(String cursor, int size);
    default Page fetch(String cursor, String highWatermark, int size) { return fetch(cursor, size); }
    record Page(List<RemoteAccount> accounts, String nextCursor, boolean hasMore, String highWatermark) {
        public Page(List<RemoteAccount> accounts, String nextCursor, boolean hasMore) {
            this(accounts, nextCursor, hasMore, null);
        }
    }
}
