package com.yigongbao.backup.job;
/** 一种持久化任务类型的后台执行器。 */
public interface JobHandler {void execute(JobExecutionContext context,JobEntity job) throws Exception;}
