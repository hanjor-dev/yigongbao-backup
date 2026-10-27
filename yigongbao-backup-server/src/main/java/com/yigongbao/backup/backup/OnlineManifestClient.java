package com.yigongbao.backup.backup;

/** 线上清单接口抽象。 */
public interface OnlineManifestClient { BackupManifest fetch(String orderId); }
