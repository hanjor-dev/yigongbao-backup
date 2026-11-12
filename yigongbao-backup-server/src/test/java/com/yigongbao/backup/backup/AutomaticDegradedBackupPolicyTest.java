package com.yigongbao.backup.backup;

import com.yigongbao.backup.remote.RemoteCallException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutomaticDegradedBackupPolicyTest {
    private final AutomaticDegradedBackupPolicy policy = new AutomaticDegradedBackupPolicy();

    @Test
    void queuesOnlyExactRelationBrokenCodeWhenTaskSnapshotEnablesPolicy() {
        assertThat(policy.shouldAutoDegrade(true, remote("RELATION_BROKEN", "unrelated text"))).isTrue();
        assertThat(policy.shouldAutoDegrade(false, remote("RELATION_BROKEN", "订单文件关系不完整"))).isFalse();
        assertThat(policy.shouldAutoDegrade(true, remote("STATE_CONFLICT", "RELATION_BROKEN"))).isFalse();
        assertThat(policy.shouldAutoDegrade(true, new IllegalStateException("RELATION_BROKEN"))).isFalse();
    }

    @Test
    void recognizesResolvedGapsByStableCodeAndNotMessage() {
        assertThat(policy.isRelationGapsResolved(remote("RELATION_GAPS_RESOLVED", "任意文案"))).isTrue();
        assertThat(policy.isRelationGapsResolved(remote("STATE_CONFLICT", "关系已恢复完整"))).isFalse();
    }

    @Test
    void oscillatingRelationshipStateTerminatesAfterOneStrictConfirmationAndFinalDegradedRead() {
        var phase = AutomaticDegradedBackupPolicy.Phase.DEGRADED;
        phase = policy.next(phase, AutomaticDegradedBackupPolicy.Signal.RELATION_GAPS_RESOLVED);
        assertThat(phase).isEqualTo(AutomaticDegradedBackupPolicy.Phase.STRICT_CONFIRMATION);
        phase = policy.next(phase, AutomaticDegradedBackupPolicy.Signal.RELATION_BROKEN);
        assertThat(phase).isEqualTo(AutomaticDegradedBackupPolicy.Phase.FINAL_DEGRADED);
        phase = policy.next(phase, AutomaticDegradedBackupPolicy.Signal.RELATION_GAPS_RESOLVED);
        assertThat(phase).isEqualTo(AutomaticDegradedBackupPolicy.Phase.RELATION_STATE_UNSTABLE);
        assertThat(AutomaticDegradedBackupPolicy.RELATION_STATE_UNSTABLE)
                .isEqualTo("RELATION_STATE_UNSTABLE");
    }

    @Test
    void successfulDegradedOrStrictManifestProducesItsMatchingTerminalCompleteness() {
        assertThat(policy.next(AutomaticDegradedBackupPolicy.Phase.DEGRADED,
                AutomaticDegradedBackupPolicy.Signal.INCOMPLETE_MANIFEST))
                .isEqualTo(AutomaticDegradedBackupPolicy.Phase.INCOMPLETE);
        assertThat(policy.next(AutomaticDegradedBackupPolicy.Phase.STRICT_CONFIRMATION,
                AutomaticDegradedBackupPolicy.Signal.COMPLETE_MANIFEST))
                .isEqualTo(AutomaticDegradedBackupPolicy.Phase.COMPLETE);
    }

    private static RemoteCallException remote(String code, String message) {
        return new RemoteCallException(message, 409, false, code, "request-1", message, null);
    }
}
