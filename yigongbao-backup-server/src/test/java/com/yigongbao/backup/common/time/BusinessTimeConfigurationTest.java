package com.yigongbao.backup.common.time;

import org.junit.jupiter.api.Test;
import java.time.ZoneId;
import java.util.TimeZone;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeConfigurationTest {
    @Test void initializesTheProcessToShanghaiBeforeBusinessWrites() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            BusinessTimeConfiguration.initializeProcessTimezone();
            assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        } finally { TimeZone.setDefault(original); }
    }
}
