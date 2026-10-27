package com.yigongbao.backup.common;

import jakarta.servlet.http.HttpServletRequest; import org.springframework.http.HttpStatus; import org.springframework.web.bind.annotation.ExceptionHandler; import org.springframework.web.bind.annotation.RestControllerAdvice; import java.util.UUID;

/** 将业务异常转换为稳定 JSON 错误信封并避免泄露堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException e,HttpServletRequest r){return response(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",e.getMessage());}
 @ExceptionHandler(IllegalStateException.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> conflict(IllegalStateException e,HttpServletRequest r){return response(HttpStatus.CONFLICT,"STATE_CONFLICT",e.getMessage());}
 @ExceptionHandler(Exception.class) public org.springframework.http.ResponseEntity<ApiResponse<Void>> error(Exception e,HttpServletRequest r){return response(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","服务处理失败");}
 private org.springframework.http.ResponseEntity<ApiResponse<Void>> response(HttpStatus status,String code,String msg){return org.springframework.http.ResponseEntity.status(status).body(new ApiResponse<>(code,msg, UUID.randomUUID().toString(),null));}
}
