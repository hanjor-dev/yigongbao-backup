package com.yigongbao.backup.account;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 仅用于开发自测；生产实现替换为 bk_user MyBatis-Plus Mapper。 */
@Repository
public class InMemoryBkUserRepository implements BkUserRepository {
    private final Map<Long, BkUser> users = new ConcurrentHashMap<>();
    @Override public BkUser findBySourceUserId(Long sourceUserId) { return users.get(sourceUserId); }
    @Override public BkUser save(BkUser user) { users.put(user.getSourceUserId(), user); return user; }
}
