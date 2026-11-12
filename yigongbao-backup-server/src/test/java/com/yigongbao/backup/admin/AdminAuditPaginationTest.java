package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.audit.AuditLogEntity;
import com.yigongbao.backup.audit.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAuditPaginationTest {
    @Test
    void returnsStableCursorAndFetchesOnlyOneLookaheadRow() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditLogEntity first = row(9L, "2026-11-12T10:00:00");
        AuditLogEntity second = row(8L, "2026-11-12T09:00:00");
        AuditLogEntity lookahead = row(7L, "2026-11-12T08:00:00");
        when(mapper.findPage(null, null, 3)).thenReturn(List.of(first, second, lookahead));
        AdminOverviewController controller = new AdminOverviewController(mock(AdminOverviewService.class), mapper);

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            AdminOverviewController.AuditPage page = controller.audit(2, null, null).data();
            assertThat(page.records()).containsExactly(first, second);
            assertThat(page.hasMore()).isTrue();
            assertThat(page.nextOccurredAt()).isEqualTo(second.getOccurredAt());
            assertThat(page.nextId()).isEqualTo(8L);
        }
        verify(mapper).findPage(null, null, 3);
    }

    private static AuditLogEntity row(long id, String time) {
        AuditLogEntity row = new AuditLogEntity();
        row.setId(id); row.setOccurredAt(LocalDateTime.parse(time));
        return row;
    }
}
