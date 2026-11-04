package com.yigongbao.backup.job;

/** 任务主体已执行完毕但存在不可再自动重试的失败明细。 */
public final class PartialJobFailureException extends Exception {
    private final boolean allItemsFailed;

    public PartialJobFailureException(String message) { this(message, false); }

    public PartialJobFailureException(String message, boolean allItemsFailed) {
        super(message);
        this.allItemsFailed = allItemsFailed;
    }

    public boolean allItemsFailed() { return allItemsFailed; }
}
