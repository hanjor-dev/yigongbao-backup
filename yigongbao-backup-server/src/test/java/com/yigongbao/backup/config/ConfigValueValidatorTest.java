package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValueValidatorTest {
    @Test
    void validatesKnownStructuredValues() {
        assertThatCode(() -> ConfigValueValidator.validate("storage.threshold.hot.percent", "85")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.schedule.interval-minutes", "30")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("storage.nas.root", "D:/backup")).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidKnownValue() {
        assertThatThrownBy(() -> ConfigValueValidator.validate("storage.threshold.hot.percent", "101"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.schedule.interval-minutes", "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValueValidator.validate("storage.nas.root", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
