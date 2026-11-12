package com.yigongbao.backup.admin;

import com.yigongbao.backup.common.time.BusinessClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OverviewAlertDismissalServiceTest {
    private final BusinessClock clock=new BusinessClock(Clock.fixed(
            Instant.parse("2026-11-12T08:30:00Z"),BusinessClock.ZONE));

    @Test void keepsDismissalsForCurrentAlertsAndDeletesRecoveredOnes() {
        OverviewAlertDismissalMapper mapper=mock(OverviewAlertDismissalMapper.class);
        when(mapper.selectAll()).thenReturn(List.of(
                new OverviewAlertDismissalMapper.DismissalRow("current"),
                new OverviewAlertDismissalMapper.DismissalRow("recovered")));
        OverviewAlertDismissalService service=new OverviewAlertDismissalService(mapper,clock);

        var dismissed=service.reconcile(List.of(alert("current"),alert("new")));

        assertThat(dismissed).containsExactly("current");
        verify(mapper).deleteByAlertKey("recovered");
        verify(mapper,never()).deleteByAlertKey("current");
    }

    @Test void dismissPersistsAlertSnapshotAndOperatorIdempotently() {
        OverviewAlertDismissalMapper mapper=mock(OverviewAlertDismissalMapper.class);
        OverviewAlertDismissalService service=new OverviewAlertDismissalService(mapper,clock);
        AdminOverviewView.Alert alert=new AdminOverviewView.Alert(
                "code:ORDER:1","WARNING","ARTIFACT","code","无效历史告警","说明",
                LocalDateTime.of(2026,11,12,16,20),"ORDER","1","orders",Map.of());

        service.dismiss(alert,7L);

        verify(mapper).insertIgnore("code:ORDER:1","code","WARNING","无效历史告警",7L,
                LocalDateTime.of(2026,11,12,16,30));
    }

    @Test void unavailableFactsDoNotEraseDismissalsAsIfTheIssueRecovered() {
        OverviewAlertDismissalMapper mapper=mock(OverviewAlertDismissalMapper.class);
        when(mapper.selectAll()).thenReturn(List.of(new OverviewAlertDismissalMapper.DismissalRow("hidden")));
        OverviewAlertDismissalService service=new OverviewAlertDismissalService(mapper,clock);

        assertThat(service.reconcile(List.of(),false)).containsExactly("hidden");

        verify(mapper,never()).deleteByAlertKey(anyString());
    }

    private static AdminOverviewView.Alert alert(String key) {
        return new AdminOverviewView.Alert(key,"WARNING","TEST","CODE","标题","说明",
                LocalDateTime.of(2026,11,12,16,0),"TEST","1","orders",Map.of());
    }
}
