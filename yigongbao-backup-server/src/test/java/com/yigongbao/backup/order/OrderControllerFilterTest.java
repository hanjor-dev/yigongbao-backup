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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.Map;

class OrderControllerFilterTest {
    @AfterEach
    void logout() {
        SaTokenContextForThreadLocalStorage.clearBox();
    }

    @Test
    void acceptsStorageRollupFiltersAndAppliesThemToQuery() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderSnapshotMapper snapshots = mock(OrderSnapshotMapper.class);
        when(snapshots.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderController controller = new OrderController(snapshots, mock(AssetMapper.class),
                mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        controller.list(1, 20, null, null, null, "AVAILABLE", "FORMAL", "PRESENT", "ABSENT", "PARTIAL",
                null, null, null, null, null, null, null);

        verify(snapshots).selectPage(any(Page.class), argThat(query -> query != null));
    }

    @Test
    void rejectsUnknownStorageRollupFilter() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderController controller = new OrderController(mock(OrderSnapshotMapper.class),
                mock(AssetMapper.class), mock(OrderPackageMapper.class), mock(ArtifactStore.class));

        assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null,
                null, "NOT_A_STATE", null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("云端存储状态");
    }

    @Test
    void appliesStructuredBusinessFiltersWithoutReadingJson() {
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

        controller.list(1, 20, "O-1", null, null, null, null, null, null, null,
                "机构", "医院", "患者", "医生", "11.1", 20, 2030);

        verify(snapshots).selectPage(any(Page.class), argThat(query -> {
            String sql = query.getSqlSegment();
            return sql.contains("org_name") && sql.contains("hospital_name")
                    && sql.contains("patient_name") && sql.contains("doctor_name")
                    && sql.contains("business_type") && sql.contains("phase") && sql.contains("status");
        }));
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
