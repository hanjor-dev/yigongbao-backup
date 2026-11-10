package com.yigongbao.backup.remote;

/** 线上接口调用失败；消息不包含认证密钥或认证请求头。 */
public final class RemoteCallException extends RuntimeException {
    private final int status;
    private final boolean retryable;
    private final String code;
    private final String requestId;
    private final String reason;

    public RemoteCallException(String message) {
        this(message, 0, true, null);
    }

    public RemoteCallException(String message, Throwable cause) {
        this(message, 0, true, cause);
    }

    public RemoteCallException(String message, int status) {
        this(message, status, true, null);
    }

    public RemoteCallException(String message, int status, Throwable cause) {
        this(message, status, true, cause);
    }

    public RemoteCallException(String message, int status, boolean retryable) {
        this(message, status, retryable, null);
    }

    public RemoteCallException(String message, int status, boolean retryable, Throwable cause) {
        this(message, status, retryable, null, null, null, cause);
    }

    public RemoteCallException(String message, int status, boolean retryable,
                               String code, String requestId, String reason, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
        this.code = code;
        this.requestId = requestId;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public boolean retryable() { return retryable; }

    public String code() { return code; }

    public String requestId() { return requestId; }

    public String reason() { return reason; }
}
