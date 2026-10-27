package com.yigongbao.backup.audit;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {
    @Test
    void appendsHashChainedEntriesAndAdvancesHead() {
        AuditLogMapper logs = mock(AuditLogMapper.class);
        AuditChainHeadMapper heads = mock(AuditChainHeadMapper.class);
        AuditChainHeadEntity head = new AuditChainHeadEntity();
        head.setId(7L); head.setChainId(AuditLogService.DEFAULT_CHAIN); head.setLastSequenceNo(0L);
        when(heads.findForUpdate(AuditLogService.DEFAULT_CHAIN)).thenReturn(null, head);
        when(heads.updateById(any(AuditChainHeadEntity.class))).thenReturn(1);
        when(logs.insert(any(AuditLogEntity.class))).thenReturn(1);

        AuditLogEntity result = new AuditLogService(logs, heads).append(
                new AuditLogService.Event("LOGIN_FAILURE", "USER", "alice", "AUTH_FAILED"));

        assertThat(result.getSequenceNo()).isEqualTo(1L);
        assertThat(result.getInputDigest()).matches("[0-9a-f]{64}");
        assertThat(result.getEntryHash()).matches("[0-9a-f]{64}");
        assertThat(result.getPreviousEntryHash()).isNull();
        assertThat(head.getLastSequenceNo()).isEqualTo(1L);
        assertThat(head.getLastEntryHash()).isEqualTo(result.getEntryHash());
        verify(logs).insert(result);
        verify(heads).updateById(head);
    }
}
