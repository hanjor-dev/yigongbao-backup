package com.yigongbao.backup.remote;

/** 线上接口调用失败；消息不包含认证密钥或认证请求头。 */
public final class RemoteCallException extends RuntimeException {
    private final int status;

    public RemoteCallException(String message) {
        this(message, 0, null);
    }

    public RemoteCallException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public RemoteCallException(String message, int status) {
        this(message, status, null);
    }

    public RemoteCallException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
