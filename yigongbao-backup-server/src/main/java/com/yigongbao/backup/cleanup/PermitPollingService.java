package com.yigongbao.backup.cleanup;
import com.yigongbao.backup.backup.CosObjectClient;
import java.util.*; import java.util.concurrent.ConcurrentHashMap;

/** 本地调度器的 permit 消费记录；同一 permit 只允许消费一次。 */
public final class PermitPollingService {
 private final CleanupClient client; private final Map<String,CleanupService.CleanupResult> results=new ConcurrentHashMap<>();
 private final Map<String,Set<String>> completedByTask=new ConcurrentHashMap<>();
 private final CosObjectClient cos; private final CleanupTaskService tasks;
 public PermitPollingService(CleanupClient client){this(client,null,null);}
 public PermitPollingService(CleanupClient client,CosObjectClient cos){this(client,cos,null);}
 public PermitPollingService(CleanupClient client,CosObjectClient cos,CleanupTaskService tasks){this.client=client;this.cos=cos;this.tasks=tasks;}
 public synchronized CleanupService.CleanupResult pollAndExecute(String taskId){CleanupClient.Permit p=client.pollPermit(taskId);CleanupService.validate(taskId,p);if(results.containsKey(p.permitId()))return results.get(p.permitId());DeletePermitEntity persistedPermit=null;CleanupTaskEntity task=tasks==null?null:tasks.findByTaskNo(taskId);if(task!=null){persistedPermit=tasks.savePermit(task.getId(),p,null,0L);if("CONSUMED".equals(persistedPermit.getStatus()))return new CleanupService.CleanupResult(p.permitId(),0,List.of());if("EXPIRED".equals(persistedPermit.getStatus())||"REVOKED".equals(persistedPermit.getStatus()))throw new IllegalStateException("清理 permit 已失效");if("ISSUED".equals(persistedPermit.getStatus()))persistedPermit=tasks.claimPermit(persistedPermit.getId());}Set<String> completed=completedByTask.computeIfAbsent(taskId,k->new HashSet<>());if(tasks!=null)completed.addAll(tasks.completedKeys(taskId));int ok=0;List<String> failed=new ArrayList<>();for(String k:new LinkedHashSet<>(p.objectKeys())){if(completed.contains(k))continue;if(CleanupService.expired(p)){failed.add(k);continue;}try{if(cos!=null)cos.delete(k,p);else client.delete(p,k);completed.add(k);ok++;if(tasks!=null)tasks.markItem(taskId,k,"DELETED",null,0L);}catch(Exception e){failed.add(k);if(tasks!=null)tasks.markItem(taskId,k,"FAILED",e.getMessage(),0L);}}CleanupService.CleanupResult r=new CleanupService.CleanupResult(p.permitId(),ok,failed);if(failed.isEmpty()){results.put(p.permitId(),r);if(tasks!=null&&persistedPermit!=null)tasks.consumePermit(persistedPermit.getId());}return r;}
}
