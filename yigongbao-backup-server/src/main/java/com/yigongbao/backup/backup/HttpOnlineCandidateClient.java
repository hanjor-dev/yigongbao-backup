package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 线上候选事件 HTTP 客户端，严格使用线上 `/candidates/search` 契约。 */
@RequiredArgsConstructor
public final class HttpOnlineCandidateClient implements OnlineCandidateClient {
    private final RemoteHttpSupport http;
    private final ObjectMapper mapper;

    @Override
    public Page search(String cursor, int pageSize, String highWatermark, OffsetDateTime orderCreatedBefore) {
        if (pageSize < 1 || pageSize > 200) throw new IllegalArgumentException("候选分页大小非法");
        if (orderCreatedBefore == null) throw new IllegalArgumentException("订单创建时间上界不能为空");
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "cursor", cursor == null ? "" : cursor,
                    "pageSize", pageSize,
                    "highWatermark", highWatermark == null ? "" : highWatermark,
                    "orderCreatedBefore", orderCreatedBefore.toString()));
            JsonNode data = http.data("POST", "/internal/backup/v1/candidates/search", body);
            List<Candidate> items = new ArrayList<>();
            for (JsonNode item : data.path("items")) {
                items.add(new Candidate(item.path("eventId").asLong(), item.path("orderId").asLong(),
                        item.has("generation") ? item.path("generation").asInt() : null,
                        parseTime(text(item, "eligibleAt")), parseTime(text(item, "orderCreatedAt")),
                        text(item, "eventStatus")));
            }
            return new Page(items, text(data, "nextCursor"), data.path("hasMore").asBoolean(false),
                    text(data, "highWatermark"));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("线上候选查询失败", ex);
        }
    }

    @Override
    public ReconcileResult reconcile(Long orderIdFrom, Long orderIdTo) {
        try {
            JsonNode data = http.data("POST", "/internal/backup/v1/candidates/reconcile",
                    mapper.writeValueAsString(Map.of("orderIdFrom", orderIdFrom == null ? 0 : orderIdFrom,
                            "orderIdTo", orderIdTo == null ? Long.MAX_VALUE : orderIdTo)));
            return new ReconcileResult(data.path("scanned").asInt(), data.path("repaired").asInt(),
                    data.path("cancelled").asInt(), data.path("deleted").asInt());
        } catch (Exception ex) {
            throw new IllegalStateException("线上候选对账失败", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static OffsetDateTime parseTime(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value);
    }
}
