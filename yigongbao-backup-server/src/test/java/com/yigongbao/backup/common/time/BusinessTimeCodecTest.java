package com.yigongbao.backup.common.time;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeCodecTest {
    @Test void convertsDatabaseLocalTimeAsShanghaiRatherThanUtc() {
        LocalDateTime local = LocalDateTime.of(2026, 11, 4, 0, 0);
        Instant instant = BusinessTimeCodec.toInstant(local);
        assertThat(instant).isEqualTo(Instant.parse("2026-11-03T16:00:00Z"));
        assertThat(BusinessTimeCodec.toLocalDateTime(instant)).isEqualTo(local);
        assertThat(BusinessTimeCodec.toUtcOffset(local).toString()).isEqualTo("2026-11-03T16:00Z");
    }
}
