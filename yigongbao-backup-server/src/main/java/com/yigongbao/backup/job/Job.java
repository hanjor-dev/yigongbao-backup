package com.yigongbao.backup.job;

import lombok.Data;

import java.time.Instant;

/** 任务状态机的运行时对象。 */
@Data
public class Job {
    private final long id;
    private String jobType;
    private JobState state = JobState.CREATED;
    private long fencingToken;
    private String leaseOwner;
    private Instant leaseUntil;
    private int orderTotal;
    private int orderCompleted;
    private int fileTotal;
    private int fileCompleted;
    private String failureCode;
    private String failureEvidence;
    private String resultJson;
}
