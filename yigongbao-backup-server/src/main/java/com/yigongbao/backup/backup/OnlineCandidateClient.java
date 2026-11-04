package com.yigongbao.backup.backup;

import java.time.OffsetDateTime;
import java.util.List;

/** 线上正式订单成熟候选接口；单轮分页由事件表高水位保证稳定。 */
public interface OnlineCandidateClient {
    /** 只返回创建时间早于排他上界的成熟正式订单；后续页必须沿用首页高水位。 */
    Page search(String cursor, int pageSize, String highWatermark, OffsetDateTime orderCreatedBefore);

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
