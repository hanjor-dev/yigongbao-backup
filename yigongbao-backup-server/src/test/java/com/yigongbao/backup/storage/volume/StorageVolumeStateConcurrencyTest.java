package com.yigongbao.backup.storage.volume;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定卷生命周期服务对封存编排公开的并发安全接口。 */
class StorageVolumeStateConcurrencyTest {
    @Test
    void exposesExplicitSwitchMountAndFailoverOperations() throws Exception {
        Method switchActive = StorageVolumeLifecycleService.class.getMethod(
                "switchActive", long.class, long.class, long.class);
        Method confirmMounted = StorageVolumeLifecycleService.class.getMethod(
                "confirmMounted", long.class, Path.class, long.class);
        Method failover = StorageVolumeLifecycleService.class.getMethod(
                "failover", long.class, long.class, long.class);

        assertThat(switchActive.getReturnType()).isEqualTo(StorageVolumeEntity.class);
        assertThat(confirmMounted.getReturnType()).isEqualTo(StorageVolumeEntity.class);
        assertThat(failover.getReturnType()).isEqualTo(StorageVolumeEntity.class);
    }
}
