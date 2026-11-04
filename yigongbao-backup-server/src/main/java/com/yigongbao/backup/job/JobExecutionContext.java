package com.yigongbao.backup.job;

import java.util.List;

/** 任务处理器使用的、受租约及 fencing token 保护的执行上下文。 */
public final class JobExecutionContext {
    private final PersistentJobCoordinator coordinator; private final JobEvidenceService evidence;
    private final long id; private final String owner; private final long token; private final long lease;
    private Long currentOrder; private Long currentSnapshot; private String currentFile; private String currentFileStep;
    public JobExecutionContext(PersistentJobCoordinator c,long id,String owner,long token,long lease){this(c,null,id,owner,token,lease);}
    public JobExecutionContext(PersistentJobCoordinator c,JobEvidenceService evidence,long id,String owner,long token,long lease){this.coordinator=c;this.evidence=evidence;this.id=id;this.owner=owner;this.token=token;this.lease=lease;}
    public void heartbeat(){coordinator.heartbeat(id,owner,token,lease);}
    public void safePoint(String step,long progress){coordinator.safePoint(id,owner,token,step,progress);coordinator.heartbeat(id,owner,token,lease);}
    public void result(String json){coordinator.storeResult(id,owner,token,json);}
    public void initializeOrders(List<Long> orderIds){if(evidence!=null)evidence.initializeOrders(id,orderIds);}
    public void orderStarted(Long source,Long snapshot){currentOrder=source;currentSnapshot=snapshot;if(evidence!=null)evidence.order(id,source,snapshot,"RUNNING",null);}
    public void orderRetrying(Long source,long seconds){currentOrder=source;if(evidence!=null)evidence.order(id,source,currentSnapshot,"RETRYING",new IllegalStateException("将在"+seconds+"秒后重试"));}
    public void orderQueuedForRetry(Long source,Throwable failure){currentOrder=source;if(evidence!=null)evidence.order(id,source,currentSnapshot,"RETRY_PENDING",failure);currentOrder=null;currentSnapshot=null;}
    public void failedOrderRetryPhaseStarted(int failedCount){coordinator.activeStatus(id,owner,token,"RETRYING_FAILED_ORDERS","RETRY_FAILED_ORDERS",failedCount);}
    public void orderMetadata(Long source,Long snapshot,String code,String createdAt,String status,String businessJson){currentSnapshot=snapshot;if(evidence!=null)evidence.orderMetadata(id,source,snapshot,code,createdAt,status,businessJson);}
    public void orderSucceeded(){if(evidence!=null&&currentOrder!=null)evidence.order(id,currentOrder,currentSnapshot,"SUCCEEDED",null);currentOrder=null;currentSnapshot=null;}
    public void orderCompleted(String status){if(evidence!=null&&currentOrder!=null)evidence.order(id,currentOrder,currentSnapshot,status,null);currentOrder=null;currentSnapshot=null;}
    public void orderFailed(Throwable failure){orderFailed("FAILED",failure);}
    public void orderFailed(String status,Throwable failure){if(evidence!=null&&currentOrder!=null)evidence.order(id,currentOrder,currentSnapshot,status,failure);currentOrder=null;currentSnapshot=null;}
    public void orderSimulationMetadata(int expectedFiles,long expectedBytes,String path){if(evidence!=null&&currentOrder!=null)evidence.orderSimulationMetadata(id,currentOrder,expectedFiles,expectedBytes,path);}
    public void fileStarted(Long source,String identity,String step,long total){currentFile=identity;currentFileStep=step;if(evidence!=null)evidence.file(id,source,identity,step,"RUNNING",0,total,null);}
    public void fileProgress(Long source,String identity,String step,long done,long total){if(evidence!=null&&identity!=null)evidence.file(id,source,identity,step,"RUNNING",done,total,null);}
    public void fileSucceeded(Long source,long bytes){if(currentFile!=null)fileSucceeded(source,currentFile,currentFileStep,bytes);}
    public void fileSucceeded(Long source,String identity,String step,long bytes){if(evidence!=null&&identity!=null)evidence.file(id,source,identity,step,"SUCCEEDED",bytes,bytes,null);if(identity!=null&&identity.equals(currentFile)){currentFile=null;currentFileStep=null;}}
    public void fileFailed(Long source,Throwable failure){if(currentFile!=null)fileFailed(source,currentFile,currentFileStep,failure);}
    public void fileFailed(Long source,String identity,String step,Throwable failure){if(evidence!=null&&identity!=null)evidence.file(id,source,identity,step,"FAILED",0,0,failure);if(identity!=null&&identity.equals(currentFile)){currentFile=null;currentFileStep=null;}}
    public void failure(Throwable failure){fileFailed(currentOrder,failure);orderFailed(failure);}
    public long jobId(){return id;} public long fencingToken(){return token;}
}
