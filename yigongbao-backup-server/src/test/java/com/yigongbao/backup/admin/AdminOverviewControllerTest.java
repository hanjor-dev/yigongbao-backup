package com.yigongbao.backup.admin;

import com.yigongbao.backup.config.PersistentConfigService;
import com.yigongbao.backup.storage.api.StorageProbe;
import com.yigongbao.backup.storage.volume.StorageVolumeMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class AdminOverviewControllerTest {
    @Test void controllerDelegatesWithoutProbeConfigOrVolumeDependencies() {
        var fieldTypes=Arrays.stream(AdminOverviewController.class.getDeclaredFields()).map(java.lang.reflect.Field::getType).toList();
        assertThat(fieldTypes).contains(AdminOverviewService.class).doesNotContain(StorageProbe.class,PersistentConfigService.class,StorageVolumeMapper.class);
    }

    @Test void exposesAdminAlertDismissalAsAnExplicitMutationEndpoint() throws Exception {
        var method=AdminOverviewController.class.getDeclaredMethod(
                "dismissOverviewAlert",String.class,HttpServletRequest.class);
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/overview/alerts/{alertKey}/dismiss");
    }
}
