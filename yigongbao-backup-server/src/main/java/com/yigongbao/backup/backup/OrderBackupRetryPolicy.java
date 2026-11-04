package com.yigongbao.backup.backup;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.LongConsumer;

/** 单订单失败重试策略；重试次数不影响同一任务中的其他订单。 */
public final class OrderBackupRetryPolicy {
    private final int maxRetries;
    private final List<Long> delays;

    public OrderBackupRetryPolicy(int maxRetries, String delaysSeconds) {
        if (maxRetries < 0 || maxRetries > 10) throw new IllegalArgumentException("订单重试次数非法");
        this.maxRetries = maxRetries;
        this.delays = Arrays.stream(delaysSeconds == null ? new String[0] : delaysSeconds.split(","))
                .filter(value -> !value.isBlank()).map(String::trim).map(Long::valueOf).toList();
        if (maxRetries > 0 && delays.size() < maxRetries) throw new IllegalArgumentException("订单重试延迟数量不足");
    }

    public <T> T execute(Callable<T> operation, LongConsumer waitSeconds) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return operation.call();
            } catch (Exception failure) {
                if (attempt >= maxRetries) throw failure;
                waitSeconds.accept(delays.get(attempt));
                attempt++;
            }
        }
    }

    public int maxRetries() { return maxRetries; }

    /** 第 retryNumber 次重试（从 1 开始）前应等待的秒数。 */
    public long delaySeconds(int retryNumber) {
        if (retryNumber < 1 || retryNumber > maxRetries) throw new IllegalArgumentException("重试序号非法");
        return delays.get(retryNumber - 1);
    }
}
