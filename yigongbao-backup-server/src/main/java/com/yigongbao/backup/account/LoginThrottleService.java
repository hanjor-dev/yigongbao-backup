package com.yigongbao.backup.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 跨实例登录失败限流，覆盖不存在账号并避免把明文用户名写入限流表。 */
@Service
public class LoginThrottleService {
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK = Duration.ofMinutes(15);
    private final LoginThrottleMapper mapper;
    private final Clock clock;

    @Autowired
    public LoginThrottleService(LoginThrottleMapper mapper) {
        this(mapper, Clock.systemDefaultZone());
    }

    LoginThrottleService(LoginThrottleMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void assertAllowed(String username, String remoteAddress) {
        LocalDateTime now = now();
        for (Bucket bucket : buckets(username, remoteAddress)) {
            LoginThrottleEntity row = mapper.selectForUpdate(bucket.key());
            if (row != null && row.getBlockedUntil() != null && row.getBlockedUntil().isAfter(now)) {
                throw new LoginThrottledException("登录尝试过于频繁，请稍后再试");
            }
        }
    }

    @Transactional
    public void recordFailure(String username, String remoteAddress) {
        LocalDateTime now = now();
        for (Bucket bucket : buckets(username, remoteAddress)) record(bucket, now);
    }

    @Transactional
    public void clearIdentity(String username) {
        mapper.deleteByBucketKey(identityBucket(username).key());
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void cleanupExpired() { mapper.deleteExpired(); }

    private void record(Bucket bucket, LocalDateTime now) {
        LoginThrottleEntity row = mapper.selectForUpdate(bucket.key());
        if (row == null) {
            row = new LoginThrottleEntity();
            row.setBucketKey(bucket.key());
            row.setScopeType(bucket.scope());
            row.setFailureCount(1);
            row.setWindowStartedAt(now);
            row.setBlockedUntil(bucket.limit() <= 1 ? now.plus(BLOCK) : null);
            try {
                mapper.insert(row);
                return;
            } catch (DuplicateKeyException raced) {
                row = mapper.selectForUpdate(bucket.key());
                if (row == null) throw raced;
            }
        }
        boolean expired = row.getWindowStartedAt() == null
                || row.getWindowStartedAt().plus(WINDOW).isBefore(now);
        int failures = expired ? 1 : (row.getFailureCount() == null ? 0 : row.getFailureCount()) + 1;
        row.setFailureCount(failures);
        if (expired) row.setWindowStartedAt(now);
        row.setBlockedUntil(failures >= bucket.limit() ? now.plus(BLOCK) : null);
        mapper.updateById(row);
    }

    private List<Bucket> buckets(String username, String remoteAddress) {
        return java.util.stream.Stream.of(identityBucket(username), ipBucket(remoteAddress))
                .sorted(java.util.Comparator.comparing(Bucket::key)).toList();
    }

    private static Bucket identityBucket(String username) {
        return new Bucket(hash("IDENTITY\n" + normalize(username)), "IDENTITY", 5);
    }

    private static Bucket ipBucket(String remoteAddress) {
        return new Bucket(hash("IP\n" + normalize(remoteAddress)), "IP", 20);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.substring(0, Math.min(normalized.length(), 256));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256不可用", failure);
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private record Bucket(String key, String scope, int limit) { }

    public static final class LoginThrottledException extends RuntimeException {
        public LoginThrottledException(String message) { super(message); }
    }
}
