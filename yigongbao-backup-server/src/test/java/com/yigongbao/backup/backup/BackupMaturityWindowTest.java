package com.yigongbao.backup.backup;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 自动备份成熟窗口的自然日边界测试。 */
class BackupMaturityWindowTest {

    @Test
    void includesTheWholeNaturalDayThatIsConfiguredDaysBeforeExecution() {
        assertThat(BackupMaturityWindow.orderCreatedBefore(LocalDate.of(2026, 11, 4), 7))
                .isEqualTo(LocalDate.of(2026, 10, 29).atStartOfDay());
    }

    @Test
    void rejectsNegativeDaysBefore() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> BackupMaturityWindow.orderCreatedBefore(LocalDate.of(2026, 11, 4), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
