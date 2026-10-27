package com.yigongbao.backup.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermitPollingServiceTest {
    @Test
    void samePermitIsConsumedOnce() {
        int[] calls = {0};
        CleanupClient client = new CleanupClient() {
            public Evaluation evaluate(CleanupRequest r) { return null; }
            public Permit pollPermit(String t) { return new Permit("p", t, List.of("a"), "ACTIVE"); }
            public void delete(Permit p, String k) { calls[0]++; }
        };
        var service = new PermitPollingService(client);
        assertThat(service.pollAndExecute("t").deleted()).isEqualTo(1);
        assertThat(service.pollAndExecute("t").deleted()).isEqualTo(1);
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void retriesOnlyFailedObjectsWithoutRepeatingSuccessfulObject() {
        int[] calls = {0};
        CleanupClient client = new CleanupClient() {
            int poll;
            public Evaluation evaluate(CleanupRequest r) { return null; }
            public Permit pollPermit(String t) { return new Permit("p" + poll++, t, List.of("a", "b"), "ACTIVE"); }
            public void delete(Permit p, String k) {
                calls[0]++;
                if ("b".equals(k) && calls[0] == 2) throw new RuntimeException("temporary");
            }
        };
        var service = new PermitPollingService(client);
        assertThat(service.pollAndExecute("t").failedKeys()).containsExactly("b");
        assertThat(service.pollAndExecute("t").failedKeys()).isEmpty();
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void rejectsExpiredPermit() {
        CleanupClient client = new CleanupClient() {
            public Evaluation evaluate(CleanupRequest r) { return null; }
            public Permit pollPermit(String t) { return new Permit("p", t, List.of("a"), "ACTIVE", Instant.now().minusSeconds(1)); }
            public void delete(Permit p, String k) { }
        };
        assertThatThrownBy(() -> new PermitPollingService(client).pollAndExecute("t"))
                .isInstanceOf(IllegalStateException.class);
    }
}
