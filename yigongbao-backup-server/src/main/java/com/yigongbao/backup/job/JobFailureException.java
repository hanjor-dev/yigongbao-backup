package com.yigongbao.backup.job;

/** 携带稳定业务错误码的本地任务异常，避免持久化 Java 类型名作为失败码。 */
public final class JobFailureException extends IllegalStateException {
    private final String code;

    public JobFailureException(String code, String message) {
        this(code, message, null);
    }

    public JobFailureException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("任务失败码不能为空");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
