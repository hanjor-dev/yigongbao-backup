package com.yigongbao.backup.account;

/** 账号同步所需的最小仓储接口。 */
public interface BkUserRepository {
    BkUser findBySourceUserId(Long sourceUserId);
    BkUser save(BkUser user);
}
