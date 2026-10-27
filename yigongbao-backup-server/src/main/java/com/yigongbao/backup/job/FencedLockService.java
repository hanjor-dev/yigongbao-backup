package com.yigongbao.backup.job;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/** 基于 bk_lock 的持久化租约锁，防止多实例和重启后的并发写入。 */
@Service
public class FencedLockService {
    private final FencedLockMapper mapper;

    public FencedLockService(FencedLockMapper mapper) {
        this.mapper = mapper;
    }

    /** 获取锁并返回新的单调 fencing token。 */
    @Transactional
    public long acquire(String key, String owner, Duration lease) {
        validate(key, owner, lease);
        FencedLockEntity row = mapper.selectForUpdate(key);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            FencedLockEntity created = new FencedLockEntity();
            created.setLockKey(key);
            created.setLeaseOwner(owner);
            created.setLeaseUntil(now.plus(lease));
            created.setFencingToken(1L);
            try {
                mapper.insert(created);
                return 1L;
            } catch (DuplicateKeyException race) {
                throw new IllegalStateException("锁已被并发占用", race);
            }
        }
        if (row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) throw new IllegalStateException("锁已被占用");
        long token = Math.addExact(row.getFencingToken() == null ? 0L : row.getFencingToken(), 1L);
        row.setLeaseOwner(owner);
        row.setLeaseUntil(now.plus(lease));
        row.setFencingToken(token);
        mapper.updateById(row);
        return token;
    }

    /** 只有当前 owner 和 token 可以续租。 */
    @Transactional
    public void renew(String key, String owner, long token, Duration lease) {
        validate(key, owner, lease);
        FencedLockEntity row = required(mapper.selectForUpdate(key));
        if (!owner.equals(row.getLeaseOwner()) || token != row.getFencingToken()
                || row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(LocalDateTime.now())) throw new IllegalStateException("锁租约无效");
        row.setLeaseUntil(LocalDateTime.now().plus(lease));
        mapper.updateById(row);
    }

    /** 释放只清空租约，不删除记录，从而保留单调 fencing token。 */
    @Transactional
    public void release(String key, String owner, long token) {
        FencedLockEntity row = required(mapper.selectForUpdate(key));
        if (!owner.equals(row.getLeaseOwner()) || token != row.getFencingToken()) throw new IllegalStateException("锁 fencing token 无效");
        row.setLeaseOwner("");
        row.setLeaseUntil(LocalDateTime.now());
        mapper.updateById(row);
    }

    private static FencedLockEntity required(FencedLockEntity row) { if (row == null) throw new IllegalStateException("锁不存在"); return row; }
    private static void validate(String key, String owner, Duration lease) {
        if (key == null || key.isBlank() || owner == null || owner.isBlank() || lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("锁参数非法");
    }
}
