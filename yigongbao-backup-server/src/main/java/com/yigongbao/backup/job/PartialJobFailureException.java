package com.yigongbao.backup.job;

/** 任务主体已执行完毕但存在不可再自动重试的失败明细。 */
public final class PartialJobFailureException extends Exception {
    public PartialJobFailureException(String message) { super(message); }
}
