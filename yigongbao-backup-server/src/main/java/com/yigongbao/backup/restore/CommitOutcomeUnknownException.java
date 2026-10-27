package com.yigongbao.backup.restore;

/** commit 请求和查询均无法确定结果；调用方不得自动 abort，必须保留会话供对账。 */
public final class CommitOutcomeUnknownException extends RuntimeException {
    private final String sessionId;

    public CommitOutcomeUnknownException(String sessionId, Throwable cause) {
        super("线上恢复提交结果未知: " + sessionId, cause);
        this.sessionId = sessionId;
    }

    public String sessionId() { return sessionId; }
}
