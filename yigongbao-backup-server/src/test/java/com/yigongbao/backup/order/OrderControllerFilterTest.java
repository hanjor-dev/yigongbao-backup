package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaRequest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yigongbao.backup.storage.api.ArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

class OrderControllerFilterTest {
    @AfterEach
    void logout() {
        SaTokenContextForThreadLocalStorage.clearBox();
    }

    @Test
    void localStorageFiltersUseCurrentArtifactVolumeInsteadOfSnapshotRollups() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "orderStorageFilterTest"),
                OrderSnapshotEntity.class);
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        when(snapshots.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderController controller = new OrderController(snapshots, mock(AssetMapper.class),
                mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        controller.list(1, 20, null, null, null, null, null, null, "PRESENT", "PRESENT", "ABSENT");

        verify(snapshots).selectPage(any(Page.class), argThat(query -> {
            String sql = query.getSqlSegment();
            return sql.contains("bk_artifact_copy") && sql.contains("bk_storage_volume")
                    && sql.contains("HOT") && sql.contains("COLD")
                    && !sql.contains("hot_rollup") && !sql.contains("cold_rollup");
        }));
    }

    @Test
    void rejectsUnknownStorageRollupFilter() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderController controller = new OrderController(mock(OrderSnapshotMapper.class),
                mock(AssetMapper.class), mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null,
                null, null, "NOT_A_STATE", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("云端存储状态");
    }

    @Test
    void appliesUnifiedKeywordAndStructuredBusinessFiltersWithoutReadingJson() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "orderSnapshotTest"),
                OrderSnapshotEntity.class);
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        when(snapshots.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderController controller = new OrderController(snapshots, mock(AssetMapper.class),
                mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        controller.list(1, 20, "检索词", "2026-11-01", "2026-11-30", 2030,
                "11.1", 1, null, null, null);

        verify(snapshots).selectPage(any(Page.class), argThat(query -> {
            String sql = query.getSqlSegment();
            return sql.contains("order_code") && sql.contains("public_order_code")
                    && sql.contains("org_name") && sql.contains("hospital_name")
                    && sql.contains("patient_name") && sql.contains("operator_name")
                    && sql.contains("doctor_name") && sql.contains("rebuild_project_names")
                    && sql.contains("business_type") && sql.contains("needs_physical_delivery")
                    && sql.contains("status");
        }));
    }

    @Test
    void currentReplicaFilterAcceptsDiagnosticPartialState() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "orderDiagnosticStorageFilterTest"),
                OrderSnapshotEntity.class);
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        when(snapshots.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderController controller = new OrderController(snapshots,
                mock(AssetMapper.class), mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        controller.list(1, 20, null, null, null, null,
                null, null, null, "PARTIAL", null);

        verify(snapshots).selectPage(any(Page.class), argThat(query -> {
            String sql = query.getSqlSegment();
            return sql.contains("state IN ('VERIFIED','PRESENT')")
                    && sql.contains("state NOT IN ('VERIFIED','PRESENT')");
        }));
    }

    @Test
    void exposesCompleteFixedFilterOptionsWithoutReadingOrderSnapshots() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        OrderController controller = new OrderController(snapshots, mock(AssetMapper.class),
                mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        Map<String, List<Map<String, Object>>> options = controller.filterOptions().data();

        assertThat(options.get("orderStatuses"))
                .extracting(option -> option.get("value"))
                .contains(1010, 2010, 8010, 9010);
        assertThat(options.get("businessTypes"))
                .extracting(option -> option.get("value"))
                .containsExactly("11.1", "11.2", "11.3", "11.4");
        assertThat(options.get("physicalDeliveries"))
                .extracting(option -> option.get("value"))
                .containsExactly(0, 1, 2);
        assertThat(options.get("storageStates"))
                .extracting(option -> option.get("value"))
                .contains("PRESENT", "ABSENT", "CLEARED", "ERROR");
        assertThat(options.get("localReplicaStates"))
                .extracting(option -> option.get("value"))
                .containsExactly("PRESENT", "PARTIAL", "ERROR", "ABSENT");
        verifyNoInteractions(snapshots);
    }

    private static SaStorage storage() {
        Map<String, Object> values = new HashMap<>();
        return new SaStorage() {
            public Object getSource() { return values; }
            public Object get(String key) { return values.get(key); }
            public SaStorage set(String key, Object value) { values.put(key, value); return this; }
            public SaStorage delete(String key) { values.remove(key); return this; }
        };
    }
}
