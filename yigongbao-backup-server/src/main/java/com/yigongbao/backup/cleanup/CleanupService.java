package com.yigongbao.backup.cleanup;
import java.util.*;
import com.yigongbao.backup.backup.CosObjectClient;
import java.time.Instant;

/** 清理协调器：本地只执行线上签发且对象集合匹配的 permit，禁止页面直接删除。 */
public final class CleanupService {
    private final CleanupClient client;
    private final CosObjectClient cos;
    private final CleanupTaskService tasks;
    public CleanupService(CleanupClient client){this(client, null, null);}
    public CleanupService(CleanupClient client, CosObjectClient cos){this(client, cos, null);}
    public CleanupService(CleanupClient client, CosObjectClient cos, CleanupTaskService tasks){this.client=Objects.requireNonNull(client);this.cos=cos;this.tasks=tasks;}
    public CleanupClient.Evaluation dryRun(int olderThanDays){ if(olderThanDays<0) throw new IllegalArgumentException("天数不能为负"); return client.evaluate(new CleanupClient.CleanupRequest(olderThanDays,true)); }
    public CleanupResult execute(String taskId){ CleanupClient.Permit permit=client.pollPermit(taskId); validate(taskId, permit); DeletePermitEntity persistedPermit=null; Set<String> completed=Set.of(); if(tasks!=null){CleanupTaskEntity task=tasks.findByTaskNo(taskId);if(task!=null){persistedPermit=tasks.savePermit(task.getId(),permit,null,0L);if("CONSUMED".equals(persistedPermit.getStatus()))return new CleanupResult(permit.permitId(),0,List.of());if("EXPIRED".equals(persistedPermit.getStatus())||"REVOKED".equals(persistedPermit.getStatus()))throw new IllegalStateException("清理 permit 已失效");if("ISSUED".equals(persistedPermit.getStatus()))persistedPermit=tasks.claimPermit(persistedPermit.getId());completed=tasks.completedKeys(taskId);}} int ok=0; List<String> failed=new ArrayList<>(); Set<String> seen=new HashSet<>(); for(String key:permit.objectKeys()){if(!seen.add(key)||completed.contains(key)) continue; if(expired(permit)){failed.add(key); continue;} try{delete(permit,key);ok++;if(tasks!=null)tasks.markItem(taskId,key,"DELETED",null,0L);}catch(Exception ex){failed.add(key);if(tasks!=null)tasks.markItem(taskId,key,"FAILED",safe(ex),0L);}} if(tasks!=null&&persistedPermit!=null&&failed.isEmpty())tasks.consumePermit(persistedPermit.getId()); return new CleanupResult(permit.permitId(),ok,failed); }
    static void validate(String taskId, CleanupClient.Permit permit){ if(permit==null||!"ACTIVE".equals(permit.status())||!taskId.equals(permit.taskId())||permit.objectKeys()==null) throw new IllegalStateException("清理授权无效"); if(permit.expiresAt()!=null&&!permit.expiresAt().isAfter(Instant.now())) throw new IllegalStateException("清理授权已过期"); }
    static boolean expired(CleanupClient.Permit permit){ return permit.expiresAt()!=null&&!permit.expiresAt().isAfter(Instant.now()); }
    private void delete(CleanupClient.Permit permit,String key) throws Exception { if(cos!=null){cos.delete(key,permit);} else {client.delete(permit,key);} }
    private static String safe(Exception ex){String message=ex.getMessage();return message==null||message.length()>512?"清理失败":message;}
    public record CleanupResult(String permitId,int deleted,List<String> failedKeys){}
}
