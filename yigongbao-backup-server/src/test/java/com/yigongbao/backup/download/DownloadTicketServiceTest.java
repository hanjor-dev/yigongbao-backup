package com.yigongbao.backup.download;

import com.yigongbao.backup.config.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DownloadTicketServiceTest {
    @Test
    void storesOnlyHashAndConsumesExactlyOnce() {
        DownloadTicketMapper mapper = mock(DownloadTicketMapper.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        when(configs.getLong("download.ticket.ttl-seconds", 120, 30)).thenReturn(120L);
        when(mapper.insert(any(DownloadTicketEntity.class))).thenAnswer(invocation -> {
            DownloadTicketEntity value = invocation.getArgument(0);
            value.setId(7L);
            return 1;
        });
        DownloadTicketService service = new DownloadTicketService(mapper, configs);

        DownloadTicketService.IssuedTicket issued = service.issue(3L, "ADMIN", "ORDER_PACKAGE", 9L, "A.zip");
        ArgumentCaptor<DownloadTicketEntity> inserted = ArgumentCaptor.forClass(DownloadTicketEntity.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(64, inserted.getValue().getTokenHash().length());
        assertNotEquals(issued.token(), inserted.getValue().getTokenHash());
        assertEquals("ADMIN", inserted.getValue().getActorRoleCode());

        DownloadTicketEntity stored = inserted.getValue();
        when(mapper.findByTokenHash(stored.getTokenHash())).thenReturn(stored);
        when(mapper.consumeOnce(eq(7L), any(LocalDateTime.class))).thenReturn(1, 0);
        assertEquals(9L, service.consume(issued.token()).getArtifactId());
        assertThrows(IllegalArgumentException.class, () -> service.consume(issued.token()));
    }

    @Test
    void rejectsExpiredTicketBeforeClaim() {
        DownloadTicketMapper mapper = mock(DownloadTicketMapper.class);
        RuntimeConfigService configs = mock(RuntimeConfigService.class);
        DownloadTicketService service = new DownloadTicketService(mapper, configs);
        DownloadTicketEntity stored = new DownloadTicketEntity();
        stored.setId(1L); stored.setStatus("ISSUED"); stored.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(mapper.findByTokenHash(any())).thenReturn(stored);
        assertThrows(IllegalArgumentException.class, () -> service.consume("A".repeat(43)));
        verify(mapper, never()).consumeOnce(anyLong(), any());
    }
}
