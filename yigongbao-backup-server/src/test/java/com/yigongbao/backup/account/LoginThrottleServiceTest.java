package com.yigongbao.backup.account;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LoginThrottleServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void blocksUnknownUserAttemptsByClientAddressBeforeAuthenticationWork() {
        LoginThrottleMapper mapper = mock(LoginThrottleMapper.class);
        LoginThrottleEntity blocked = new LoginThrottleEntity();
        blocked.setBlockedUntil(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC).plusMinutes(5));
        when(mapper.selectForUpdate(anyString())).thenReturn(blocked);

        assertThatThrownBy(() -> new LoginThrottleService(mapper, CLOCK)
                .assertAllowed("missing-user", "203.0.113.8"))
                .isInstanceOf(LoginThrottleService.LoginThrottledException.class);
    }

    @Test
    void recordsIndependentIpAndIdentityBucketsForEveryFailure() {
        LoginThrottleMapper mapper = mock(LoginThrottleMapper.class);
        when(mapper.selectForUpdate(anyString())).thenReturn(null);

        new LoginThrottleService(mapper, CLOCK).recordFailure("missing-user", "203.0.113.8");

        verify(mapper, times(2)).insert(any(LoginThrottleEntity.class));
    }
}
