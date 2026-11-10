package com.yigongbao.backup.storage.archive;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class VolumeArchivePreviewServiceTest {
    @Test void formatsOrderCreationPeriodForPhysicalLabel() {
        assertThat(VolumeArchivePreviewService.periodLabel(
                LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 3, 15, 20, 0)))
                .isEqualTo("20260101-20260315");
    }
}
