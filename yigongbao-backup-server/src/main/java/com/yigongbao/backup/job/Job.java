package com.yigongbao.backup.job;

import lombok.Data;

import java.time.Instant;

/** 任务状态机的运行时对象。 */
@Data
public class Job {
    private final long id;
    private String jobType;
    private String jobNo;
    private Long requestedBy;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private JobState state = JobState.CREATED;
    private long fencingToken;
    private String leaseOwner;
    private Instant leaseUntil;
    private int orderTotal;
    private int orderCompleted;
    private int orderFailed;
    /** 线上已删除等无需恢复的订单数量；属于已处理但非成功、非失败。 */
    private int orderSkipped;
    private int fileTotal;
    private int fileCompleted;
    private int fileFailed;
    private long bytesTotal;
    private long bytesCompleted;
    private String failureCode;
    private String failureEvidence;
    private String resultJson;
    private String executionMode = "REAL";
    /** 线上恢复任务提交时固化的冲突处理策略。 */
    private String conflictPolicy;
    private Long targetStorageVolumeId;
    private String storageVolumeName;
    private String storageTier;
    private String currentStorageVolumeName;
    private String currentStorageTier;
    private String currentMountStatus;
    private String currentOperationStatus;
    private Boolean currentStorageResolved;
    private String currentStorageUnavailableReason;
    private int manifestSucceeded;
    private int manifestFailed;
    private int expectedFileTotal;
    /** 清单中的源文件总容量，表示压缩前读取量。 */
    private long expectedBytes;
    /** 已生成订单 ZIP 压缩包的实际累计容量。 */
    private long packageBytes;
    private boolean cancelRequested;
}
