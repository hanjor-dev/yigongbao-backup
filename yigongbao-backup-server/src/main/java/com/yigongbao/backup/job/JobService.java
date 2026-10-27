package com.yigongbao.backup.job;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/** 任务状态机基础：条件式领取、租约和 fencing token 防止旧执行器写入。 */
@Service
public class JobService {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Clock clock;

    public JobService() { this(Clock.systemUTC()); }
    public JobService(Clock clock) { this.clock = clock; }

    public Job create(int orderTotal, int fileTotal) {
        Job job = new Job(ids.incrementAndGet());
        job.setOrderTotal(orderTotal);
        job.setFileTotal(fileTotal);
        jobs.put(job.getId(), job);
        return job;
    }

    public synchronized Job claim(long id, String owner, Duration lease) {
        Job job = required(id);
        if (job.getState() != JobState.CREATED
                && !(job.getState() == JobState.RUNNING
                && job.getLeaseUntil() != null && !job.getLeaseUntil().isAfter(clock.instant()))) {
            throw new IllegalStateException("任务不可领取");
        }
        job.setState(JobState.RUNNING);
        job.setLeaseOwner(owner);
        job.setLeaseUntil(clock.instant().plus(lease));
        job.setFencingToken(job.getFencingToken() + 1);
        return job;
    }

    public synchronized void heartbeat(long id, String owner, long fencingToken, Duration lease) {
        Job job = required(id);
        checkLease(job, owner, fencingToken);
        job.setLeaseUntil(clock.instant().plus(lease));
    }

    public synchronized void transition(long id, String owner, long fencingToken, JobState next) {
        Job job = required(id);
        checkLease(job, owner, fencingToken);
        if (!allowed(job.getState(), next)) throw new IllegalStateException("非法任务状态迁移");
        job.setState(next);
    }

    public synchronized void fail(long id, String owner, long fencingToken, String code, String evidence) {
        Job job = required(id);
        checkLease(job, owner, fencingToken);
        job.setFailureCode(code);
        job.setFailureEvidence(evidence);
        job.setState(JobState.FAILED);
    }

    public Job recoverExpired(long id, String owner, Duration lease) {
        return claim(id, owner, lease);
    }

    public Job get(long id) { return required(id); }

    /** 返回任务中心列表快照，避免管理页面依赖固定任务 ID。 */
    public List<Job> list() {
        return jobs.values().stream().sorted(java.util.Comparator.comparingLong(Job::getId).reversed()).toList();
    }

    private void checkLease(Job job, String owner, long token) {
        if (!owner.equals(job.getLeaseOwner()) || token != job.getFencingToken()
                || job.getLeaseUntil() == null || !job.getLeaseUntil().isAfter(clock.instant())) {
            throw new IllegalStateException("租约或 fencing token 无效");
        }
    }

    private boolean allowed(JobState from, JobState to) {
        return switch (from) {
            case CREATED -> EnumSet.of(JobState.RUNNING, JobState.CANCELLED).contains(to);
            case RUNNING -> EnumSet.of(JobState.PAUSED, JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED).contains(to);
            case PAUSED -> EnumSet.of(JobState.RUNNING, JobState.CANCELLED).contains(to);
            default -> false;
        };
    }

    private Job required(long id) {
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("任务不存在");
        return job;
    }
}
