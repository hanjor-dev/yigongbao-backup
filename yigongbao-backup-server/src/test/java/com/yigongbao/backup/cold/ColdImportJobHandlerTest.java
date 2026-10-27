package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ColdImportJobHandlerTest {
    @Test
    void reservesHotSpaceWithJobIdAndReleasesAfterImport() throws Exception {
        ColdImportService imports = mock(ColdImportService.class);
        ObjectMapper mapper = new ObjectMapper();
        SpaceReservationService reservations = mock(SpaceReservationService.class);
        SpaceReservation reservation = new SpaceReservation("hot", 80L, "r1");
        when(imports.estimateRestoreBytes("batch.zip", Set.of("O1"))).thenReturn(80L);
        when(imports.restore("batch.zip", Set.of("O1"))).thenReturn(1);
        when(reservations.reserve(eq("hot"), eq(80L), eq(91L))).thenReturn(reservation);
        JobEntity job = new JobEntity();
        job.setId(91L);
        job.setConfigSnapshotJson(mapper.writeValueAsString(new ColdImportJobHandler.Payload("batch.zip", Set.of("O1"))));

        new ColdImportJobHandler(imports, mapper, reservations).execute(mock(JobExecutionContext.class), job);

        verify(reservations).reserve("hot", 80L, 91L);
        verify(reservations).release(reservation);
    }

    @Test
    void releasesHotSpaceWhenImportFails() throws Exception {
        ColdImportService imports = mock(ColdImportService.class);
        ObjectMapper mapper = new ObjectMapper();
        SpaceReservationService reservations = mock(SpaceReservationService.class);
        SpaceReservation reservation = new SpaceReservation("hot", 1L, "r1");
        when(imports.estimateRestoreBytes(anyString(), anySet())).thenReturn(1L);
        when(reservations.reserve(eq("hot"), eq(1L), eq(92L))).thenReturn(reservation);
        when(imports.restore(anyString(), anySet())).thenThrow(new java.io.IOException("disk"));
        JobEntity job = new JobEntity();
        job.setId(92L);
        job.setConfigSnapshotJson("{\"batchId\":\"batch.zip\",\"orderIds\":[]}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ColdImportJobHandler(imports, mapper, reservations).execute(mock(JobExecutionContext.class), job))
                .isInstanceOf(Exception.class);
        verify(reservations).release(reservation);
    }
}
