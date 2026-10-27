package com.yigongbao.backup.job;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.HexFormat;
/** 从任务类型、操作人和规范化参数生成稳定幂等键。 */
public final class JobIdempotency {private JobIdempotency(){}public static String key(String type,long actor,String payload){try{return type.toLowerCase()+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((type+"\n"+actor+"\n"+(payload==null?"{}":payload)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("SHA-256不可用",e);}}}
