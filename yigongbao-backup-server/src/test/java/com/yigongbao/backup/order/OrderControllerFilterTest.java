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
        OrderController controller = new OrderController(snapshots, mock(AssetMapper.class), mock(ArtifactStore.class));

        controller.list(1, 20, null, null, null, "AVAILABLE", "FORMAL", "PRESENT", "ABSENT", "PARTIAL");

        verify(snapshots).selectPage(any(Page.class), argThat(query -> query != null));
    }

    @Test
    void rejectsUnknownStorageRollupFilter() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(mock(SaRequest.class), mock(SaResponse.class), storage());
        StpUtil.login(1L);
        OrderController controller = new OrderController(mock(OrderSnapshotMapper.class),
                mock(AssetMapper.class), mock(ArtifactStore.class));

        assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null,
                null, "NOT_A_STATE", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("云端存储状态");
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
