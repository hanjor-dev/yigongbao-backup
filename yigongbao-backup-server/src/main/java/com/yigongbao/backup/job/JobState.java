package com.yigongbao.backup.job;

/** 本地长任务生命周期状态。 */
public enum JobState {
    CREATED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED
}
