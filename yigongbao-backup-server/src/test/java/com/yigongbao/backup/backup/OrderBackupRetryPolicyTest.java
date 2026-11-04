package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBackupRetryPolicyTest {
    @Test
    void retriesFailedOrderUsingConfiguredDelaysUntilItSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> waited = new ArrayList<>();

        String result = new OrderBackupRetryPolicy(3, "60,300,900").execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("temporary");
            return "ok";
        }, waited::add);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(waited).containsExactly(60L, 300L);
    }

    @Test
    void exposesFinalFailureAfterAllRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> new OrderBackupRetryPolicy(2, "1,2").execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("still-down");
        }, ignored -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessage("still-down");
        assertThat(attempts).hasValue(3);
    }
}
