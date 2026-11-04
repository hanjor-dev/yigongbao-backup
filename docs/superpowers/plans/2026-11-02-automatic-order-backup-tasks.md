# Automatic Order Backup Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual candidate selection with one scheduled incremental backup task per due day and make the backup page the task list/detail workspace.

**Architecture:** A daily local-time schedule gate collects one stable online candidate window using persisted cursor/high-watermark, then creates one durable job containing every eligible order. Orders execute independently with bounded in-job retry; only successful receipts publish local snapshots. The existing durable job tables remain the source of task and detail data, while the frontend removes the generic task-center route.

**Tech Stack:** Java 21, Spring Boot 3.2, MyBatis-Plus, MySQL 8, Vue 3, Element Plus, Vite.

**Spec:** Confirmed conversation requirements on 2026-11-02.

## Global Constraints

- A due day creates at most one automatic task at configured local time.
- A missed start while the server is down is skipped and is not caught up after restart.
- Formal execution has no order-count cap; development may use `BACKUP_DEV_CANDIDATE_LIMIT=5`.
- One file failure fails its order, but other orders continue.
- Failed orders retry inside the same task and remain eligible for later compensation if exhausted.
- No candidate-selection page and no separate task-center menu.

## Review Focus

- Restart after the configured time must not create a missed task.
- Multiple scheduler ticks in one minute/day must create only one task.
- More candidates than one API page must enter the same task.
- Partial order failure must expose counts/details and keep failed orders retryable.
- Empty candidate windows must advance scan state without creating an empty job.

---

### Task 1: Daily schedule and incremental collection

**Files:**
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/ScheduledBackupService.java`
- Modify: `yigongbao-backup-server/src/main/java/com/yigongbao/backup/backup/BackupScheduleCheckpointService.java`
- Modify: configuration validators and SQL defaults/migrations
- Test: scheduler/checkpoint tests

**Interfaces:**
- Consumes: `OnlineCandidateClient.search(cursor, pageSize, highWatermark)`
- Produces: exactly one `MANUAL_BACKUP` durable job for a due date.

- [ ] Add failing tests for start time, missed-day skip, duplicate tick, multi-page collection and empty window.
- [ ] Implement `backup.schedule.start-time` and remove `backup.schedule.max-orders-per-run`.
- [ ] Add optional dev-only `BACKUP_DEV_CANDIDATE_LIMIT` environment limit.
- [ ] Persist daily schedule state and stable cursor only after the collection window is complete.
- [ ] Run backend tests.

### Task 2: Per-order retry and task summaries

**Files:**
- Modify: `ManualBackupJobHandler.java`
- Modify: job execution/context services and admin job DTO/controller as needed
- Test: backup handler and task-detail tests

**Interfaces:**
- Consumes: one task payload containing all source order IDs.
- Produces: total/success/failure/file/byte progress and order-level attempts/errors.

- [ ] Add failing tests for retry success, exhausted retry, partial failure and other-order continuation.
- [ ] Implement configurable attempts and delays without losing durable task progress.
- [ ] Make failed orders remain non-`AVAILABLE` and therefore eligible for compensation.
- [ ] Expose task list/detail fields needed by the frontend.
- [ ] Run backend tests.

### Task 3: Backup task workspace frontend

**Files:**
- Modify: `yigongbao-backup-web/src/views/BackupView.vue`
- Modify: `yigongbao-backup-web/src/composables/useBackupConsole.js`
- Modify: routes/layout/application shell
- Delete: separate generic task-center view/route when unused
- Test: frontend backup-flow/navigation tests

**Interfaces:**
- Consumes: admin job list/detail APIs filtered to backup tasks.
- Produces: backup task table, task details and retry-failed-orders action.

- [ ] Add failing tests that candidate APIs and task-center navigation are absent.
- [ ] Implement backup task table with core counts, capacity, progress, source and timestamps.
- [ ] Reuse/relocate task detail components inside the backup page.
- [ ] Implement failed-order retry from task detail.
- [ ] Run frontend tests and production build.

### Task 4: Contract, SQL and end-to-end verification

**Files:**
- Modify: local OpenAPI, initialization SQL, merged initialization SQL, configuration docs and Windows smoke guide.

**Interfaces:**
- Produces: consistent fresh-install and existing-install configuration behavior.

- [ ] Remove obsolete database configuration and add start-time/retry configuration defaults.
- [ ] Add idempotent migration for existing databases.
- [ ] Update API/configuration documentation and mock smoke steps.
- [ ] Run both Java module suites, frontend tests/build, mock HTTP smoke and diff checks.
