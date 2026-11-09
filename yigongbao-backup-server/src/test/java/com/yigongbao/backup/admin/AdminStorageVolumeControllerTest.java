package com.yigongbao.backup.admin;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class AdminStorageVolumeControllerTest {
    @Test
    void parsesFileUriReturnedByStorageVolumeScan() {
        assertThat(AdminStorageVolumeController.parseMountPath("file:///D:/")).isEqualTo(Path.of("D:/"));
    }
}
