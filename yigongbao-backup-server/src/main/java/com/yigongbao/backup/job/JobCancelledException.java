package com.yigongbao.backup.job;
/** 任务已在业务安全点取消，不应重试。 */
public final class JobCancelledException extends RuntimeException {public JobCancelledException(String message){super(message);}}
