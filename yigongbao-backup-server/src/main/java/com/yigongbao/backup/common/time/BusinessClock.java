package com.yigongbao.backup.common.time;

import org.springframework.stereotype.Component;
import java.time.*;

/** 可替换的上海业务时钟，仅用于需要确定性判断的纯策略。 */
@Component
public final class BusinessClock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock;
    public BusinessClock() { this(Clock.system(ZONE)); }
    public BusinessClock(Clock clock) { this.clock = clock.withZone(ZONE); }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public LocalDate today() { return LocalDate.now(clock); }
    public Clock clock() { return clock; }
}
