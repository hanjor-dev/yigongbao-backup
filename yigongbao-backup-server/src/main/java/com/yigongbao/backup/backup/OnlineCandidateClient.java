package com.yigongbao.backup.backup;

import java.time.OffsetDateTime;
import java.util.List;

/** 线上正式订单候选增量接口；游标由线上事件表高水位决定。 */
public interface OnlineCandidateClient {
    Page search(String cursor, int pageSize);

    /** 对账指定订单 ID 区间，修复线上事件漏记录；实现方可按配置禁用。 */
    default ReconcileResult reconcile(Long orderIdFrom, Long orderIdTo) { return new ReconcileResult(0, 0, 0, 0); }

    record Page(List<Candidate> items, String nextCursor, boolean hasMore, String highWatermark) {
        public Page {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    record Candidate(Long eventId, Long orderId, Integer generation,
                     OffsetDateTime eligibleAt, OffsetDateTime orderCreatedAt,
                     String eventStatus) {
    }

    record ReconcileResult(int scanned, int repaired, int cancelled, int deleted) {}
}
