package com.yigongbao.backup.common;

/** 本地 HTTP API 统一响应信封。 */
public record ApiResponse<T>(String code, String message, String requestId, T data) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>("OK", "操作成功", requestId, data);
    }
}
