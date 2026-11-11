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
