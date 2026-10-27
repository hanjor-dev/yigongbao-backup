package com.yigongbao.backup.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CleanupJobHandlerTest {
 @Test void executesPermitConsumptionAsBackgroundJob() throws Exception {
  PermitPollingService poller=mock(PermitPollingService.class);when(poller.pollAndExecute("cleanup-1")).thenReturn(new CleanupService.CleanupResult("p1",1,java.util.List.of()));JobExecutionContext context=mock(JobExecutionContext.class);SpaceReservationService reservations=mock(SpaceReservationService.class);SpaceReservation reservation=new SpaceReservation("hot",1,"r");when(reservations.reserve("hot",1,1)).thenReturn(reservation);CleanupExecuteJobHandler handler=new CleanupExecuteJobHandler(poller,new ObjectMapper(),reservations);JobEntity job=new JobEntity();job.setId(1L);job.setConfigSnapshotJson("{\"taskId\":\"cleanup-1\"}");
  handler.execute(context,job);
  verify(context).safePoint("POLL_PERMIT",0);verify(poller).pollAndExecute("cleanup-1");
  verify(reservations).release(reservation);
 }
}
