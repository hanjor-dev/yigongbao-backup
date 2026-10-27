package com.yigongbao.backup.job;
import java.util.Map;
/** 任务类型与后台执行器的不可变映射。 */
public final class JobExecutorRegistry {private final Map<String,JobHandler> handlers;public JobExecutorRegistry(Map<String,JobHandler> handlers){this.handlers=Map.copyOf(handlers);}public JobHandler required(String type){JobHandler h=handlers.get(type);if(h==null)throw new IllegalStateException("未注册的任务类型: "+type);return h;}}
