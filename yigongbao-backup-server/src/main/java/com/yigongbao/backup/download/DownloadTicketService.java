package com.yigongbao.backup.download;

import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/** 签发和原子消费下载票据，避免把登录令牌放进 URL。 */
@Service
@RequiredArgsConstructor
public class DownloadTicketService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DownloadTicketMapper mapper;
    private final RuntimeConfigService configs;

    @Transactional
    public IssuedTicket issue(long actorUserId, String actorRoleCode, String artifactType, long artifactId, String downloadName) {
        if (actorUserId < 1 || artifactId < 1 || artifactType == null || artifactType.isBlank())
            throw new IllegalArgumentException("下载票据参数无效");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long ttl = configs.getLong("download.ticket.ttl-seconds", 120, 30);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(Math.min(ttl, 600));
        DownloadTicketEntity entity = new DownloadTicketEntity();
        entity.setTokenHash(hash(token));
        entity.setActorUserId(actorUserId);
        entity.setActorRoleCode(actorRoleCode == null || actorRoleCode.isBlank() ? "USER" : actorRoleCode);
        entity.setArtifactType(artifactType);
        entity.setArtifactId(artifactId);
        entity.setDownloadName(safeName(downloadName));
        entity.setStatus("ISSUED");
        entity.setExpiresAt(expiresAt);
        mapper.insert(entity);
        return new IssuedTicket(token, expiresAt);
    }

    @Transactional
    public DownloadTicketEntity consume(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        DownloadTicketEntity ticket = mapper.findByTokenHash(hash(token));
        LocalDateTime now = LocalDateTime.now();
        if (ticket == null || ticket.getId() == null || !"ISSUED".equals(ticket.getStatus())
                || ticket.getExpiresAt() == null || !ticket.getExpiresAt().isAfter(now)
                || mapper.consumeOnce(ticket.getId(), now) != 1) throw invalid();
        ticket.setStatus("CONSUMED");
        ticket.setConsumedAt(now);
        return ticket;
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void purgeExpired() {
        mapper.purgeBefore(LocalDateTime.now().minusHours(24));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("下载地址无效、已过期或已使用");
    }

    private static String safeName(String value) {
        String name = value == null ? "download.bin" : value.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) name = "download.bin";
        return name.substring(0, Math.min(name.length(), 255));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public record IssuedTicket(String token, LocalDateTime expiresAt) { }
}
