package com.yigongbao.backup.storage.volume;

/** 存储卷生命周期状态不满足操作前提时抛出的业务异常。 */
public class StorageVolumeOperationException extends IllegalStateException {
    public StorageVolumeOperationException(String message) {
        super(message);
    }
}
