package com.yigongbao.backup.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CleanupTaskServiceTest {
    @Test
    void createsTaskAndItemsAndRepeatsByIdempotencyKey() {
        CleanupTaskMapper tasks = mock(CleanupTaskMapper.class);
        CleanupItemMapper items = mock(CleanupItemMapper.class);
        DeletePermitMapper permits = mock(DeletePermitMapper.class);
        when(tasks.findByIdempotencyKey("key-1")).thenReturn(null);
        doAnswer(invocation -> {
            CleanupTaskEntity task = invocation.getArgument(0);
            task.setId(7L);
            task.setVersion(0);
            return 1;
        }).when(tasks).insert(any(CleanupTaskEntity.class));
        CleanupTaskService service = new CleanupTaskService(tasks, items, permits);
        CleanupTaskService.CreateInput input = new CleanupTaskService.CreateInput(
                "remote-task", "key-1", 180, "ACTUAL", "snap", "objects", 12,
                List.of(new CleanupTaskService.ItemInput("COS", "bucket", "a", "v1", 3L, "UNSHARED", 12)));

        CleanupTaskEntity created = service.create(input, 9L);

        assertThat(created.getId()).isEqualTo(7L);
        verify(tasks).insert(any(CleanupTaskEntity.class));
        verify(items).insert(any(CleanupItemEntity.class));
        when(tasks.findByIdempotencyKey("key-1")).thenReturn(created);
        assertThat(service.create(input, 9L)).isSameAs(created);
        verify(tasks, times(1)).insert(any(CleanupTaskEntity.class));
    }

    @Test
    void pauseAndResumeUseConditionalStatusUpdates() {
        CleanupTaskMapper tasks = mock(CleanupTaskMapper.class);
        CleanupTaskEntity task = task(7L, "CREATED");
        when(tasks.selectById(7L)).thenReturn(task);
        when(tasks.update(any(), any())).thenReturn(1);
        CleanupTaskService service = new CleanupTaskService(tasks, mock(CleanupItemMapper.class), mock(DeletePermitMapper.class));

        service.pause(7L, 9L);
        service.resume(7L, 9L);

        verify(tasks, times(2)).update(isNull(), any());
    }

    @Test
    void savesAndClaimsPermitWithExpiryAndAttempt() {
        CleanupTaskMapper tasks = mock(CleanupTaskMapper.class);
        CleanupItemMapper items = mock(CleanupItemMapper.class);
        DeletePermitMapper permits = mock(DeletePermitMapper.class);
        CleanupTaskEntity task = task(7L, "CREATED");
        task.setTaskNo("remote-task");
        when(tasks.selectById(7L)).thenReturn(task);
        when(permits.findByPermitId("permit-1")).thenReturn(null);
        doAnswer(invocation -> { DeletePermitEntity permit = invocation.getArgument(0); permit.setId(11L); permit.setVersion(0); return 1; })
                .when(permits).insert(any(DeletePermitEntity.class));
        when(permits.selectById(11L)).thenReturn(permit(11L, "ISSUED"));
        when(permits.update(any(), any())).thenReturn(1);
        CleanupTaskService service = new CleanupTaskService(tasks, items, permits);

        DeletePermitEntity saved = service.savePermit(7L,
                new CleanupClient.Permit("permit-1", "remote-task", List.of("a"), "ACTIVE", Instant.now().plusSeconds(60)),
                new CleanupTaskService.PermitEvidence(3, "snap", "objects", "fence", "receipt", 2, "package", "nonce"), 9L);

        assertThat(saved.getId()).isEqualTo(11L);
        assertThat(saved.getAttemptNo()).isEqualTo(3);
        service.claimPermit(11L);
        verify(permits).insert(any(DeletePermitEntity.class));
        verify(permits).update(isNull(), any());
    }

    @Test
    void rejectsDifferentRequestUnderSameIdempotencyKey() {
        CleanupTaskMapper tasks = mock(CleanupTaskMapper.class);
        CleanupTaskEntity existing = task(1L, "CREATED");
        existing.setPolicyVersion(180);
        existing.setMode("ACTUAL");
        existing.setObjectSetHash("old");
        when(tasks.findByIdempotencyKey("key")).thenReturn(existing);
        CleanupTaskService service = new CleanupTaskService(tasks, mock(CleanupItemMapper.class), mock(DeletePermitMapper.class));
        CleanupTaskService.CreateInput input = new CleanupTaskService.CreateInput(
                "t", "key", 90, "ACTUAL", null, "new", 0, List.of());
        assertThatThrownBy(() -> service.create(input, 1L)).isInstanceOf(IllegalStateException.class);
    }

    private static CleanupTaskEntity task(long id, String status) {
        CleanupTaskEntity task = new CleanupTaskEntity();
        task.setId(id); task.setVersion(0); task.setStatus(status); task.setTaskNo("t-" + id);
        return task;
    }

    private static DeletePermitEntity permit(long id, String status) {
        DeletePermitEntity permit = new DeletePermitEntity();
        permit.setId(id); permit.setVersion(0); permit.setStatus(status);
        return permit;
    }
}
