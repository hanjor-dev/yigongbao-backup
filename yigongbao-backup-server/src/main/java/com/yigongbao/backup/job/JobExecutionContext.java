package com.yigongbao.backup.job;

import java.util.List;
import java.util.Map;

/** 任务处理器使用的、受租约及 fencing token 保护的执行上下文。 */
public final class JobExecutionContext {
    private final PersistentJobCoordinator coordinator; private final JobEvidenceService evidence; private final JobExecutionLogService logs;
    private final long id; private final String owner; private final long token; private final long lease;
    private Long currentOrder; private Long currentSnapshot; private String currentFile; private String currentFileStep;
    public JobExecutionContext(PersistentJobCoordinator c,long id,String owner,long token,long lease){this(c,null,null,id,owner,token,lease);}
    public JobExecutionContext(PersistentJobCoordinator c,JobEvidenceService evidence,long id,String owner,long token,long lease){this(c,evidence,null,id,owner,token,lease);}
    public JobExecutionContext(PersistentJobCoordinator c,JobEvidenceService evidence,JobExecutionLogService logs,long id,String owner,long token,long lease){this.coordinator=c;this.evidence=evidence;this.logs=logs;this.id=id;this.owner=owner;this.token=token;this.lease=lease;}
    public void heartbeat(){coordinator.heartbeat(id,owner,token,lease);}
    public void safePoint(String step,long progress){coordinator.safePoint(id,owner,token,step,progress);coordinator.heartbeat(id,owner,token,lease);}
    public void result(String json){coordinator.storeResult(id,owner,token,json);}
    public void initializeOrders(List<Long> orderIds){if(evidence!=null)fenced(()->evidence.initializeOrders(id,orderIds));}
    public List<JobOrderEntity> orderStates(){return evidence==null?List.of():evidence.orders(id);}
    public void failOrders(List<Long> orderIds,Throwable failure){if(evidence!=null)fenced(()->evidence.failOrders(id,orderIds,failure));}
    public void orderStarted(Long source,Long snapshot){currentOrder=source;currentSnapshot=snapshot;if(evidence!=null)fenced(()->evidence.order(id,source,snapshot,"RUNNING",null));}
    public void orderRetrying(Long source,long seconds){currentOrder=source;if(evidence!=null)fenced(()->evidence.order(id,source,currentSnapshot,"RETRYING",new IllegalStateException("将在"+seconds+"秒后重试")));}
    public void orderQueuedForRetry(Long source,Throwable failure){currentOrder=source;if(evidence!=null)fenced(()->evidence.order(id,source,currentSnapshot,"RETRY_PENDING",failure));currentOrder=null;currentSnapshot=null;}
    public void orderQueuedForAutomaticDegraded(Long source,Throwable failure){currentOrder=source;if(evidence!=null)fenced(()->evidence.order(id,source,currentSnapshot,"DEGRADE_PENDING",failure));currentOrder=null;currentSnapshot=null;}
    public void orderDegrading(Long source){currentOrder=source;if(evidence!=null)fenced(()->evidence.order(id,source,currentSnapshot,"DEGRADING",null));}
    public void orderRetryConsumed(Long source){if(evidence!=null)fenced(()->evidence.consumeRetry(id,source));}
    public void orderDegradePhase(Long source,String phase){if(evidence!=null)fenced(()->evidence.updateDegradePhase(id,source,phase));}
    public void failedOrderRetryPhaseStarted(int failedCount){coordinator.activeStatus(id,owner,token,"RETRYING_FAILED_ORDERS","RETRY_FAILED_ORDERS",failedCount);}
    public void orderMetadata(Long source,Long snapshot,String code,String createdAt,String status,String businessJson){currentSnapshot=snapshot;if(evidence!=null)fenced(()->evidence.orderMetadata(id,source,snapshot,code,createdAt,status,businessJson));}
    public void orderSucceeded(){if(evidence!=null&&currentOrder!=null)fenced(()->evidence.order(id,currentOrder,currentSnapshot,"SUCCEEDED",null));currentOrder=null;currentSnapshot=null;}
    public void orderCompleted(String status){if(evidence!=null&&currentOrder!=null)fenced(()->evidence.order(id,currentOrder,currentSnapshot,status,null));currentOrder=null;currentSnapshot=null;}
    public void orderFailed(Throwable failure){orderFailed("FAILED",failure);}
    public void orderFailed(String status,Throwable failure){if(evidence!=null&&currentOrder!=null)fenced(()->evidence.order(id,currentOrder,currentSnapshot,status,failure));currentOrder=null;currentSnapshot=null;}
    public void orderSimulationMetadata(int expectedFiles,long expectedBytes,String path){if(evidence!=null&&currentOrder!=null)fenced(()->evidence.orderSimulationMetadata(id,currentOrder,expectedFiles,expectedBytes,path));}
    public void orderAtVolume(Long source,long volumeId,String storageKey){if(evidence!=null&&source!=null)fenced(()->evidence.orderAtVolume(id,source,volumeId,storageKey));}
    public void fileStarted(Long source,String identity,String step,long total){currentFile=identity;currentFileStep=step;if(evidence!=null)fenced(()->evidence.file(id,source,identity,step,"RUNNING",0,total,null));}
    /** 记录恢复 ZIP 内单个文件的开始状态及线上签发的目标对象键。 */
    public void fileStarted(Long source,String identity,String step,long total,String objectKey){currentFile=identity;currentFileStep=step;if(evidence!=null)fenced(()->evidence.file(id,source,identity,step,"RUNNING",0,total,objectKey,null));}
    /** 持久化附属文件步骤，不改变订单 ZIP 聚合步骤的当前状态。 */
    public void fileStepStarted(Long source,String identity,String step,long total,String objectKey){if(evidence!=null)fenced(()->evidence.file(id,source,identity,step,"RUNNING",0,total,objectKey,null));}
    public void fileProgress(Long source,String identity,String step,long done,long total){if(evidence!=null&&identity!=null)fenced(()->evidence.file(id,source,identity,step,"RUNNING",done,total,null));}
    public void fileSucceeded(Long source,long bytes){if(currentFile!=null)fileSucceeded(source,currentFile,currentFileStep,bytes);}
    public void fileSucceeded(Long source,String identity,String step,long bytes){if(evidence!=null&&identity!=null)fenced(()->evidence.file(id,source,identity,step,"SUCCEEDED",bytes,bytes,null));if(identity!=null&&identity.equals(currentFile)){currentFile=null;currentFileStep=null;}}
    public void fileCompleted(Long source,String status){if(currentFile!=null){String identity=currentFile;String step=currentFileStep;if(evidence!=null)fenced(()->evidence.file(id,source,identity,step,status,0,0,null));currentFile=null;currentFileStep=null;}}
    /** 完成附属文件步骤，不影响订单 ZIP 聚合步骤。 */
    public void fileStepSucceeded(Long source,String identity,String step,long bytes){if(evidence!=null&&identity!=null)fenced(()->evidence.file(id,source,identity,step,"SUCCEEDED",bytes,bytes,null));}
    public void fileFailed(Long source,Throwable failure){if(currentFile!=null)fileFailed(source,currentFile,currentFileStep,failure);}
    public void fileFailed(Long source,String identity,String step,Throwable failure){if(evidence!=null&&identity!=null)fenced(()->evidence.file(id,source,identity,step,"FAILED",0,0,failure));if(identity!=null&&identity.equals(currentFile)){currentFile=null;currentFileStep=null;}}
    /** 失败附属文件步骤，不影响订单 ZIP 聚合步骤。 */
    public void fileStepFailed(Long source,String identity,String step,long uploadedBytes,Throwable failure){if(evidence!=null&&identity!=null)fenced(()->evidence.file(id,source,identity,step,"FAILED",uploadedBytes,0,failure));}
    public void fileAtVolume(Long source,String identity,String step,String status,long done,long total,String objectKey,long volumeId,String storageKey,Throwable failure){if(evidence!=null&&identity!=null)fenced(()->evidence.fileAtVolume(id,source,identity,step,status,done,total,objectKey,volumeId,storageKey,failure));}
    public void failure(Throwable failure){fileFailed(currentOrder,failure);orderFailed(failure);}
    /** 持久化任务范围内的普通业务事件。 */
    public void taskInfo(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.info(id,null,eventCode,stage,message,details));}
    /** 持久化任务范围内的告警事件。 */
    public void taskWarn(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.warn(id,null,eventCode,stage,message,details));}
    /** 持久化任务范围内的错误事件。 */
    public void taskError(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.error(id,null,eventCode,stage,message,details));}
    /** 任务状态已经提交为成功、失败、重试或取消后，以本次 fencing token 追加终态事件。 */
    public void completedTaskInfo(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)completed(()->logs.info(id,null,eventCode,stage,message,details));}
    public void completedTaskWarn(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)completed(()->logs.warn(id,null,eventCode,stage,message,details));}
    public void completedTaskError(String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)completed(()->logs.error(id,null,eventCode,stage,message,details));}
    /** 持久化指定订单的普通业务事件。 */
    public void orderInfo(long sourceOrderId,String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.info(id,sourceOrderId,eventCode,stage,message,details));}
    /** 持久化指定订单的告警事件。 */
    public void orderWarn(long sourceOrderId,String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.warn(id,sourceOrderId,eventCode,stage,message,details));}
    /** 持久化指定订单的错误事件。 */
    public void orderError(long sourceOrderId,String eventCode,String stage,String message,Map<String,?> details){if(logs!=null)fenced(()->logs.error(id,sourceOrderId,eventCode,stage,message,details));}
    private void fenced(Runnable write){coordinator.withinActiveLease(id,owner,token,write);}
    private void completed(Runnable write){coordinator.withinCompletedAttempt(id,token,write);}
    public long jobId(){return id;} public String owner(){return owner;} public long fencingToken(){return token;}
}
