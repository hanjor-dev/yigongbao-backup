package com.yigongbao.backup.backup;

import com.yigongbao.backup.remote.RemoteCallException;

/** 订单关系损坏自动补偿的稳定错误码识别与有界模式切换规则。 */
public final class AutomaticDegradedBackupPolicy {
    public static final String RELATION_BROKEN = "RELATION_BROKEN";
    public static final String RELATION_GAPS_RESOLVED = "RELATION_GAPS_RESOLVED";
    public static final String RELATION_STATE_UNSTABLE = "RELATION_STATE_UNSTABLE";

    public boolean shouldAutoDegrade(boolean enabled, Throwable failure) {
        return enabled && hasCode(failure, RELATION_BROKEN);
    }

    public boolean isRelationGapsResolved(Throwable failure) {
        return hasCode(failure, RELATION_GAPS_RESOLVED);
    }

    public Phase next(Phase phase, Signal signal) {
        return switch (phase) {
            case DEGRADED -> switch (signal) {
                case INCOMPLETE_MANIFEST -> Phase.INCOMPLETE;
                case RELATION_GAPS_RESOLVED -> Phase.STRICT_CONFIRMATION;
                default -> Phase.FAILED;
            };
            case STRICT_CONFIRMATION -> switch (signal) {
                case COMPLETE_MANIFEST -> Phase.COMPLETE;
                case RELATION_BROKEN -> Phase.FINAL_DEGRADED;
                default -> Phase.FAILED;
            };
            case FINAL_DEGRADED -> switch (signal) {
                case INCOMPLETE_MANIFEST -> Phase.INCOMPLETE;
                case RELATION_GAPS_RESOLVED -> Phase.RELATION_STATE_UNSTABLE;
                default -> Phase.FAILED;
            };
            default -> phase;
        };
    }

    private static boolean hasCode(Throwable failure, String expected) {
        return failure instanceof RemoteCallException remote && expected.equals(remote.code());
    }

    public enum Phase {
        DEGRADED, STRICT_CONFIRMATION, FINAL_DEGRADED,
        COMPLETE, INCOMPLETE, RELATION_STATE_UNSTABLE, FAILED
    }

    public enum Signal {
        COMPLETE_MANIFEST, INCOMPLETE_MANIFEST,
        RELATION_BROKEN, RELATION_GAPS_RESOLVED, OTHER_FAILURE
    }
}
