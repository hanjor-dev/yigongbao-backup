package com.yigongbao.backup.admin;

import com.yigongbao.backup.config.PersistentConfigService;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewControllerTest {
    @Test void controllerDelegatesWithoutProbeConfigOrVolumeDependencies() {
        var fieldTypes=Arrays.stream(AdminOverviewController.class.getDeclaredFields()).map(java.lang.reflect.Field::getType).toList();
        assertThat(fieldTypes).contains(AdminOverviewService.class).doesNotContain(StorageProbe.class,PersistentConfigService.class,StorageVolumeMapper.class);
    }
}
