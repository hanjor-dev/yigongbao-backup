package com.yigongbao.backup.storage.volume;

/** 文件副本所在卷未挂载或不可访问时的可区分业务异常。 */
public class StorageVolumeUnavailableException extends IllegalStateException {
    public StorageVolumeUnavailableException(String message) {
        super(message);
    }
}
