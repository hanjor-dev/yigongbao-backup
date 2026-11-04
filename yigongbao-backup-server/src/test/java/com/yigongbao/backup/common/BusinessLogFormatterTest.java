package com.yigongbao.backup.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogFormatterTest {
    @Test
    void formatsBusinessValuesForHumanReadableLogs() {
        assertThat(BusinessLogFormatter.bytes(60_345_701L)).isEqualTo("57.55 MB（60345701 字节）");
        assertThat(BusinessLogFormatter.duration(4_730L)).isEqualTo("4.73 秒（4730 毫秒）");
        assertThat(BusinessLogFormatter.shortHash("98652c4ca242c87639dd499e46cd0677")).isEqualTo("98652c4ca242");
        assertThat(BusinessLogFormatter.status("RUNNING")).isEqualTo("RUNNING（执行中）");
    }
}
