package com.yigongbao.backup.config;

import java.time.Instant; import java.util.Map; import java.util.List; import java.util.concurrent.ConcurrentHashMap; import org.springframework.stereotype.Service;

/** 配置版本服务：修改必须携带当前版本，防止管理员并发覆盖；敏感值只存引用。 */
/** 版本化配置服务，所有更新必须使用 CAS 版本。 */
@Service public final class ConfigService {
 private final Map<String,Entry> values=new ConcurrentHashMap<>();
 public Entry get(String key){return values.get(key);}
 public List<Entry> list(){return values.values().stream().sorted(java.util.Comparator.comparing(Entry::key)).toList();}
 public synchronized Entry put(String key,String value,int expectedVersion,long actor){ if(key==null||key.isBlank())throw new IllegalArgumentException("配置键为空"); Entry old=values.get(key); int current=old==null?0:old.version(); if(current!=expectedVersion)throw new IllegalStateException("配置版本冲突"); Entry next=new Entry(key,value,current+1,actor,Instant.now()); values.put(key,next); return next; }
 public record Entry(String key,String value,int version,long changedBy,Instant changedAt){}
}
