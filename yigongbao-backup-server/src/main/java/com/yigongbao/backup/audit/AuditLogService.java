package com.yigongbao.backup.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;

/** 持久化哈希链审计服务；敏感原文不进入审计载荷，只保存摘要。 */
@Service
@RequiredArgsConstructor
public class AuditLogService {
    public static final String DEFAULT_CHAIN = "LOCAL-BACKUP";

    private final AuditLogMapper logMapper;
    private final AuditChainHeadMapper headMapper;
    private final Clock clock = Clock.systemUTC();

    @Transactional
    public AuditLogEntity append(Event event) {
        if (event == null || blank(event.action()) || blank(event.objectType()) || blank(event.objectId())
                || blank(event.resultCode())) {
            throw new IllegalArgumentException("审计事件字段不完整");
        }
        String chainId = blank(event.chainId()) ? DEFAULT_CHAIN : event.chainId();
        AuditChainHeadEntity head = headMapper.findForUpdate(chainId);
        if (head == null) {
            head = new AuditChainHeadEntity();
            head.setChainId(chainId);
            head.setLastSequenceNo(0L);
            head.setLastEntryHash(null);
            headMapper.insert(head);
            // 插入后重新读取，确保后续更新使用数据库生成的版本与主键。
            head = headMapper.findForUpdate(chainId);
            if (head == null) throw new IllegalStateException("审计链头创建失败");
        }
        long sequence = head.getLastSequenceNo() == null ? 1L : head.getLastSequenceNo() + 1L;
        String inputDigest = blank(event.inputDigest()) ? sha256(event.action() + "\n" + event.objectId()) : event.inputDigest();
        LocalDateTime occurredAt = event.occurredAt() == null ? LocalDateTime.now(clock) : event.occurredAt();
        String entryHash = sha256(String.join("\n", chainId, Long.toString(sequence),
                value(event.actorUserId()), value(event.actorRoleCode()), value(event.clientAddress()),
                event.action(), event.objectType(), event.objectId(), value(event.jobId()), inputDigest,
                event.resultCode(), value(event.failureReason()), value(head.getLastEntryHash()), occurredAt.toString()));

        AuditLogEntity log = new AuditLogEntity();
        log.setChainId(chainId); log.setSequenceNo(sequence); log.setActorUserId(event.actorUserId());
        log.setActorRoleCode(event.actorRoleCode()); log.setClientAddress(event.clientAddress());
        log.setAction(event.action()); log.setObjectType(event.objectType()); log.setObjectId(event.objectId());
        log.setJobId(event.jobId()); log.setInputDigest(inputDigest); log.setResultCode(event.resultCode());
        log.setFailureReason(event.failureReason()); log.setPreviousEntryHash(head.getLastEntryHash());
        log.setEntryHash(entryHash); log.setOccurredAt(occurredAt);
        logMapper.insert(log);

        head.setLastSequenceNo(sequence); head.setLastEntryHash(entryHash);
        if (headMapper.updateById(head) != 1) throw new IllegalStateException("审计链头更新失败");
        return log;
    }

    public record Event(String chainId, Long actorUserId, String actorRoleCode, String clientAddress,
                        String action, String objectType, String objectId, Long jobId, String inputDigest,
                        String resultCode, String failureReason, LocalDateTime occurredAt) {
        public Event(String action, String objectType, String objectId, String resultCode) {
            this(DEFAULT_CHAIN, null, null, null, action, objectType, objectId, null, null, resultCode, null, null);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }
}
