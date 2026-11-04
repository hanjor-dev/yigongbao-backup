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
    private int fileTotal;
    private int fileCompleted;
    private int fileFailed;
    private long bytesTotal;
    private long bytesCompleted;
    private String failureCode;
    private String failureEvidence;
    private String resultJson;
    private String executionMode = "REAL";
    private int manifestSucceeded;
    private int manifestFailed;
    private int expectedFileTotal;
    /** 清单中的源文件总容量，表示压缩前读取量。 */
    private long expectedBytes;
    /** 已生成订单 ZIP 压缩包的实际累计容量。 */
    private long packageBytes;
    private boolean cancelRequested;
}
