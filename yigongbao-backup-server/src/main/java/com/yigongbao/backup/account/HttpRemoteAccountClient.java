package com.yigongbao.backup.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.yigongbao.backup.remote.RemoteHttpSupport;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 线上账号增量同步 HTTP 客户端；游标编码为 updateTime|id，避免重复读取。 */
@RequiredArgsConstructor
public final class HttpRemoteAccountClient implements RemoteAccountClient {
    private final RemoteHttpSupport http;

    @Override
    public Page fetch(String cursor, int size) {
        return fetch(cursor, null, size);
    }

    @Override
    public Page fetch(String cursor, String highWatermark, int size) {
        if (size < 1 || size > 500) throw new IllegalArgumentException("账号分页大小非法");
        Cursor state = Cursor.parse(cursor);
        String path = "/internal/backup/v1/accounts?updateTime="
                + java.net.URLEncoder.encode(state.updatedAfter(), java.nio.charset.StandardCharsets.UTF_8)
                + "&id=" + state.afterId() + "&pageSize=" + size;
        if (highWatermark != null && !highWatermark.isBlank()) {
            Cursor upper = Cursor.parse(highWatermark);
            path += "&highWatermarkTime=" + java.net.URLEncoder.encode(upper.updatedAfter(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&highWatermarkId=" + upper.afterId();
        }
        JsonNode data = http.data("GET", path, "");
        List<RemoteAccount> accounts = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            int status = item.has("status") ? item.path("status").asInt()
                    : (item.path("enabled").asBoolean() ? 1 : 0);
            accounts.add(new RemoteAccount(item.path("id").asLong(), item.path("username").asText(),
                    item.path("passwordHash").asText(), status,
                    text(item, "realName"), text(item, "phone"), text(item, "email"),
                    parseTime(text(item, "updatedAt", "updateTime")), item.path("deleted").asBoolean(false)));
        }
        JsonNode next = data.path("nextCursor");
        String nextUpdated = text(data, "nextUpdatedAfter", "updateTime");
        String nextId = text(data, "nextAfterId", "id");
        if (next.isObject()) {
            nextUpdated = text(next, "updateTime", "updatedAfter");
            nextId = text(next, "id", "afterId");
        }
        boolean more = data.path("hasMore").asBoolean(false);
        if (more && (nextUpdated == null || nextUpdated.isBlank() || nextId == null || nextId.isBlank())) {
            throw new IllegalStateException("线上账号接口声明存在下一页，但未返回完整游标");
        }
        String nextCursor = nextUpdated == null || nextUpdated.isBlank() || nextId == null || nextId.isBlank()
                ? null : nextUpdated + "|" + nextId;
        JsonNode high = data.path("highWatermark");
        String highTime = high.isObject() ? text(high, "updateTime", "updatedAfter") : null;
        String highId = high.isObject() ? text(high, "id", "afterId") : null;
        String responseHigh = highTime == null || highId == null ? highWatermark : highTime + "|" + highId;
        return new Page(accounts, nextCursor, more, responseHigh);
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) return value.asText();
        }
        return null;
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (RuntimeException ignored) { return LocalDateTime.parse(value); }
    }

    private record Cursor(String updatedAfter, long afterId) {
        static Cursor parse(String value) {
            if (value == null || value.isBlank()) return new Cursor("1970-01-01T00:00:00Z", 0L);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("账号同步游标非法");
            return new Cursor(parts[0], Long.parseLong(parts[1]));
        }
    }
}
