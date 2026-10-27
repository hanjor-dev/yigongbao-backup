package com.yigongbao.backup.common;

import jakarta.servlet.http.HttpServletRequest; import lombok.extern.slf4j.Slf4j; import org.springframework.http.HttpStatus; import org.springframework.web.bind.annotation.ExceptionHandler; import org.springframework.web.bind.annotation.RestControllerAdvice; import java.util.UUID;

/** 将业务异常转换为稳定 JSON 错误信封并避免泄露堆栈。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException e,HttpServletRequest r){String requestId=UUID.randomUUID().toString();log.warn("请求参数校验失败 requestId={} method={} uri={} message={}",requestId,r.getMethod(),r.getRequestURI(),e.getMessage());return response(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",e.getMessage(),requestId);}
 @ExceptionHandler(IllegalStateException.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> conflict(IllegalStateException e,HttpServletRequest r){String requestId=UUID.randomUUID().toString();log.warn("请求状态冲突 requestId={} method={} uri={} message={}",requestId,r.getMethod(),r.getRequestURI(),e.getMessage());return response(HttpStatus.CONFLICT,"STATE_CONFLICT",e.getMessage(),requestId);}
 @ExceptionHandler(Exception.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> error(Exception e,HttpServletRequest r){String requestId=UUID.randomUUID().toString();log.error("请求处理失败 requestId={} method={} uri={}",requestId,r.getMethod(),r.getRequestURI(),e);return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","服务处理失败",requestId);}
 private org.springframework.http.ResponseEntity<ApiResponse<Void>> response(HttpStatus status,String code,String msg,String requestId){return org.springframework.http.ResponseEntity.status(status).body(new ApiResponse<>(code,msg,requestId,null));}
}
