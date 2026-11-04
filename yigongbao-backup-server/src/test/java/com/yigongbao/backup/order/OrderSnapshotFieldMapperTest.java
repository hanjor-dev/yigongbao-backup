package com.yigongbao.backup.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSnapshotFieldMapperTest {
    @Test
    void mapsAllQueryableFieldsAndKeepsNestedValuesAsJson() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 9_007_199_254_740_993L);
        raw.put("isClassicCase", 1);
        raw.put("createTime", "2026-11-03T05:00:00");
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("orderCode", "O-100");
        display.put("publicOrderCode", "P-100");
        display.put("hospitalId", 88L);
        display.put("hospitalName", "第一医院");
        display.put("patientName", "张三");
        display.put("estimatedCost", new BigDecimal("1234.56"));
        display.put("phase", 20);
        display.put("phaseName", "设计");
        display.put("status", 2030);
        display.put("statusName", "设计中");
        display.put("statusColor", Map.of("textColor", "#111111"));
        display.put("regionalAudit", Map.of("status", 1, "auditorName", "审核员"));
        display.put("designAudit", null);
        display.put("rebuildProjectList", List.of(Map.of("projectName", "颅骨重建")));

        OrderSnapshotEntity target = new OrderSnapshotEntity();
        new OrderSnapshotFieldMapper(new ObjectMapper().findAndRegisterModules()).apply(target, raw, display);

        assertThat(target.getSourceOrderId()).isEqualTo(9_007_199_254_740_993L);
        assertThat(target.getOrderCode()).isEqualTo("O-100");
        assertThat(target.getPublicOrderCode()).isEqualTo("P-100");
        assertThat(target.getHospitalId()).isEqualTo(88L);
        assertThat(target.getHospitalName()).isEqualTo("第一医院");
        assertThat(target.getPatientName()).isEqualTo("张三");
        assertThat(target.getEstimatedCost()).isEqualByComparingTo("1234.56");
        assertThat(target.getPhase()).isEqualTo(20);
        assertThat(target.getStatus()).isEqualTo(2030);
        assertThat(target.getClassicCase()).isTrue();
        assertThat(target.getStatusColorJson()).contains("textColor");
        assertThat(target.getRegionalAuditJson()).contains("审核员");
        assertThat(target.getDesignAuditJson()).isNull();
        assertThat(target.getRebuildProjectListJson()).contains("颅骨重建");
    }

    @Test
    void acceptsLegacyClassicCaseAlias() {
        OrderSnapshotEntity target = new OrderSnapshotEntity();

        new OrderSnapshotFieldMapper(new ObjectMapper().findAndRegisterModules())
                .apply(target, Map.of("classicCase", 1), Map.of());

        assertThat(target.getClassicCase()).isTrue();
    }
}
