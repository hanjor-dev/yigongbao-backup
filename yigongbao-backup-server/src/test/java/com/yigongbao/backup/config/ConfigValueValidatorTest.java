package com.yigongbao.backup.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValueValidatorTest {
    @Test
    void acceptsDayBasedBackupScheduleAndOrderRetryPolicy() {
        assertThatCode(() -> ConfigValueValidator.validate("backup.schedule.interval-days", "1")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.schedule.start-time", "05:00")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.retry.max-retries", "3")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.retry.delays-seconds", "60,300,900")).doesNotThrowAnyException();
    }

    @Test
    void removesDevelopmentBatchLimitFromBusinessConfiguration() {
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.schedule.max-orders-per-run", "5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不允许");
    }

    @Test
    void validatesNaturalDayMaturityConfigurationAndRemovesTechnicalPageSize() {
        assertThatCode(() -> ConfigValueValidator.validate("backup.collection.days-before", "7"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.collection.days-before", "0"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.collection.days-before", "3651"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.candidate.page-size", "100"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不允许");
    }
    @Test
    void validatesKnownStructuredValues() {
        assertThatCode(() -> ConfigValueValidator.validate("storage.threshold.hot.percent", "85")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("backup.schedule.interval-days", "1")).doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("storage.hot-root", "D:/backup")).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidKnownValue() {
        assertThatThrownBy(() -> ConfigValueValidator.validate("storage.threshold.hot.percent", "101"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.schedule.interval-minutes", "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigValueValidator.validate("storage.hot-root", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesOnlineSideNamesForRestoreConflictPolicy() {
        assertThatCode(() -> ConfigValueValidator.validate("restore.conflict-policy", "KEEP_ONLINE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("restore.conflict-policy", "OVERWRITE_ONLINE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConfigValueValidator.validate("restore.conflict-policy", "KEEP_LOCAL"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesFinalCleanupSchedulingKeys() {
        assertThatCode(() -> ConfigValueValidator.validate("cleanup.schedule.enabled", "false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ConfigValueValidator.validate("cleanup.batch-size", "100"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConfigValueValidator.validate("cleanup.batch-size", "0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsZeroRetriesAndEnforcesOperationalBounds() {
        assertThatCode(() -> ConfigValueValidator.validate("restore.retry-count", "0"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConfigValueValidator.validate("job.lease-seconds", "9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysOutsideFinalConfigurationCatalog() {
        assertThatThrownBy(() -> ConfigValueValidator.validate("backup.unknown-option", "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许");
    }
}
