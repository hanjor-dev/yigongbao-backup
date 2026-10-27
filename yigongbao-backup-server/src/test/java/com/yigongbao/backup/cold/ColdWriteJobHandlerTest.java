package com.yigongbao.backup.cold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.order.ColdSupplementCoverageService;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;

class ColdWriteJobHandlerTest {
 @TempDir Path temp;
 @Test void writesArchiveAndCompletesBatchInBackground() throws Exception {
  ColdBatchMapper batches=mock(ColdBatchMapper.class);ColdBatchWorkflowService workflow=mock(ColdBatchWorkflowService.class);ColdBatchService writer=mock(ColdBatchService.class);ColdSupplementCoverageService coverage=mock(ColdSupplementCoverageService.class);JobExecutionContext context=mock(JobExecutionContext.class);
  ColdBatchEntity batch=new ColdBatchEntity();batch.setId(3L);batch.setBatchNo("CB-3");batch.setStatus("CREATED");when(batches.selectById(3L)).thenReturn(batch);Path archive=Files.writeString(temp.resolve("result.zip"),"archive");when(writer.estimateBatch(eq(List.of("orders/A/A.zip")))).thenReturn(Files.size(archive));when(writer.writeBatch(eq("CB-3"),eq(temp.toRealPath()),eq(List.of("orders/A/A.zip")))).thenReturn(archive);
  SpaceReservationService reservations=mock(SpaceReservationService.class); SpaceReservation reservation=new SpaceReservation("cold",100L,"token"); when(reservations.reserve(eq("cold"),anyLong(),eq(77L))).thenReturn(reservation);
  ColdWriteJobHandler handler=new ColdWriteJobHandler(batches,workflow,writer,coverage,new ObjectMapper(),reservations);JobEntity job=new JobEntity();job.setId(77L);job.setRequestedBy(9L);job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(new ColdWriteJobHandler.Payload(3L,temp.toString(),List.of("orders/A/A.zip"),"digest")));
  handler.execute(context,job);
  verify(workflow).start(3L);verify(workflow).complete(3L,1,1,Files.size(archive),"digest");verify(context,atLeastOnce()).safePoint(anyString(),anyLong());
  verify(reservations).reserve("cold",Files.size(archive),77L); verify(reservations).release(reservation);
 }

 @Test void releasesColdReservationWhenArchiveWriteFails() throws Exception {
  ColdBatchMapper batches=mock(ColdBatchMapper.class); ColdBatchWorkflowService workflow=mock(ColdBatchWorkflowService.class); ColdBatchService writer=mock(ColdBatchService.class); ColdSupplementCoverageService coverage=mock(ColdSupplementCoverageService.class); JobExecutionContext context=mock(JobExecutionContext.class); SpaceReservationService reservations=mock(SpaceReservationService.class); SpaceReservation reservation=new SpaceReservation("cold",100L,"token"); when(reservations.reserve(eq("cold"),anyLong(),eq(78L))).thenReturn(reservation);
  ColdBatchEntity batch=new ColdBatchEntity(); batch.setId(3L); batch.setBatchNo("CB-3"); batch.setStatus("CREATED"); when(batches.selectById(3L)).thenReturn(batch); when(writer.estimateBatch(anyList())).thenReturn(100L); when(writer.writeBatch(anyString(),any(),anyList())).thenThrow(new java.io.IOException("disk"));
  ColdWriteJobHandler handler=new ColdWriteJobHandler(batches,workflow,writer,coverage,new ObjectMapper(),reservations); JobEntity job=new JobEntity(); job.setId(78L); job.setConfigSnapshotJson(new ObjectMapper().writeValueAsString(new ColdWriteJobHandler.Payload(3L,temp.toString(),List.of("orders/A/A.zip"),"digest")));
  org.assertj.core.api.Assertions.assertThatThrownBy(()->handler.execute(context,job)).isInstanceOf(Exception.class);
  verify(reservations).release(reservation);
 }
}
