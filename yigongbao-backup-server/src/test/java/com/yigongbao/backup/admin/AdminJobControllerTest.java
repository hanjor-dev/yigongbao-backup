package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.backup.BackupJobCreationService;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import com.yigongbao.backup.job.PersistentJobService;
import com.yigongbao.backup.storage.archive.VolumeArchiveService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminJobControllerTest {
    @Test
    void manualStrictTaskFreezesCurrentAutomaticDegradePolicy() throws Exception {
        BackupJobCreationService creation = mock(BackupJobCreationService.class);
        when(creation.autoDegradeOnRelationBroken()).thenReturn(true);
        com.yigongbao.backup.job.JobEntity created = new com.yigongbao.backup.job.JobEntity();
        created.setId(1L);
        when(creation.createForOrders(anyString(), anyString(), anyLong(), anyString(), eq(1))).thenReturn(created);
        AdminJobController controller = new AdminJobController(mock(PersistentJobService.class),
                mock(PersistentJobCoordinator.class), new ObjectMapper(), creation,
                mock(VolumeArchiveService.class));

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("ADMIN")).thenAnswer(invocation -> null);
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(9L);
            controller.create(new AdminJobController.CreateCommand(1, 0,
                    List.of(new AdminJobController.OrderCommand(7L, null)), List.of()));
        }

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(creation).createForOrders(eq("MANUAL_BACKUP"), anyString(), eq(9L), payload.capture(), eq(1));
        assertThat(new ObjectMapper().readValue(payload.getValue(),
                com.yigongbao.backup.backup.ManualBackupJobHandler.Payload.class)
                .resolvedAutoDegradeOnRelationBroken()).isTrue();
    }

    @Test
    void filesEndpointReturnsRequestedServerPageAndOrderScope() {
        PersistentJobService jobs = mock(PersistentJobService.class);
        PersistentJobService.JobFilePage page =
                new PersistentJobService.JobFilePage(List.of(), 245L, 2L, 50L, 5L);
        when(jobs.listFiles(99L, 75L, 2L, 50L)).thenReturn(page);
        AdminJobController controller = new AdminJobController(jobs, mock(PersistentJobCoordinator.class),
                new ObjectMapper(), mock(BackupJobCreationService.class), mock(VolumeArchiveService.class));

        try (MockedStatic<StpUtil> auth = mockStatic(StpUtil.class)) {
            auth.when(() -> StpUtil.checkRole("ADMIN")).thenAnswer(invocation -> null);

            assertThat(controller.files(99L, 2L, 50L, 75L).data()).isSameAs(page);

            auth.verify(() -> StpUtil.checkRole("ADMIN"));
        }
        verify(jobs).listFiles(99L, 75L, 2L, 50L);
        verify(jobs, never()).listFiles(99L);
    }
}
