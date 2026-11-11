package com.yigongbao.backup.job;

/** 任务已经创建，但执行前置条件不满足；该类失败必须直接进入终态且保留稳定业务码。 */
public class JobPreconditionException extends IllegalStateException {
    private final String code;

    public JobPreconditionException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "JOB_PRECONDITION_FAILED" : code;
    }

    public String code() {
        return code;
    }
}
