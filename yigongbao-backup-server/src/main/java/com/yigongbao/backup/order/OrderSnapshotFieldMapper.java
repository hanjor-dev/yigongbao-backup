package com.yigongbao.backup.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将线上原始订单和列表展示快照映射到本地可检索字段。 */
public final class OrderSnapshotFieldMapper {
    private final ObjectMapper mapper;

    public OrderSnapshotFieldMapper(ObjectMapper mapper) {
        this.mapper = mapper.copy().findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 原地更新业务字段，绝不覆盖本地主键、审计字段和乐观锁版本。 */
    public void apply(OrderSnapshotEntity target, Map<String, Object> raw, Map<String, Object> display) {
        Map<String, Object> rawValues = raw == null ? Map.of() : raw;
        Map<String, Object> displayValues = display == null ? Map.of() : display;
        target.setSourceOrderId(longValue(rawValues.get("id"), displayValues.get("id")));
        target.setOrderCreatedAt(timeValue(rawValues.get("createTime"), displayValues.get("createTime")));
        target.setClassicCase(booleanValue(rawValues.get("isClassicCase"))
                || booleanValue(rawValues.get("classicCase")));
        target.setSourceVersion(integerValue(displayValues.get("version"), rawValues.get("version")));

        LinkedHashMap<String, Object> scalars = new LinkedHashMap<>(displayValues);
        for (String reserved : new String[]{"id", "createTime", "version", "isClassicCase",
                "statusColor", "regionalAudit", "designAudit", "rebuildProjectList"}) {
            scalars.remove(reserved);
        }
        try {
            mapper.readerForUpdating(target).readValue(mapper.writeValueAsBytes(scalars));
        } catch (Exception exception) {
            throw new IllegalArgumentException("订单展示快照字段类型非法", exception);
        }
        target.setSourceStatus(target.getStatus() == null ? text(rawValues.get("status"))
                : String.valueOf(target.getStatus()));
        target.setStatusColorJson(jsonOrNull(displayValues.get("statusColor")));
        target.setRegionalAuditJson(jsonOrNull(displayValues.get("regionalAudit")));
        target.setDesignAuditJson(jsonOrNull(displayValues.get("designAudit")));
        target.setRebuildProjectListJson(jsonOrNull(displayValues.get("rebuildProjectList")));
    }

    private String jsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("订单嵌套快照无法序列化", exception);
        }
    }

    private static Long longValue(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                if (value != null) {
                    return Long.valueOf(String.valueOf(value));
                }
            } catch (NumberFormatException ignored) {
                // 尝试下一个兼容值。
            }
        }
        return null;
    }

    private static Integer integerValue(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                if (value != null) {
                    return Integer.valueOf(String.valueOf(value));
                }
            } catch (NumberFormatException ignored) {
                // 尝试下一个兼容值。
            }
        }
        return null;
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() == 1)
                || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static LocalDateTime timeValue(Object... values) {
        for (Object value : values) {
            if (value instanceof LocalDateTime time) {
                return time;
            }
            if (value == null) {
                continue;
            }
            try {
                return OffsetDateTime.parse(String.valueOf(value)).toLocalDateTime();
            }
            catch (RuntimeException ignored) {
                try {
                    return LocalDateTime.parse(String.valueOf(value));
                } catch (RuntimeException ignoredAgain) {
                    // 尝试下一个兼容值。
                }
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
