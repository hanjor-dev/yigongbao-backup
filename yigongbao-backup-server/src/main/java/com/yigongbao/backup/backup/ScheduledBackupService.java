package com.yigongbao.backup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.job.JobIdempotency;
import com.yigongbao.backup.job.JobOrderEntity;
import com.yigongbao.backup.job.JobOrderMapper;
import com.yigongbao.backup.job.PersistentJobCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.yigongbao.backup.config.RuntimeConfigService;

import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 在配置的日期周期和开始时间汇总全部增量订单，并创建一个持久化备份任务。 */
@Service
@Slf4j
public class ScheduledBackupService {
    private static final int INTERNAL_CANDIDATE_PAGE_SIZE = 200;
    private final OnlineCandidateClient candidates;
    private final ObjectMapper mapper;
    private final OrderSnapshotMapper snapshots;
    private final PersistentJobCoordinator coordinator;
    private final RuntimeConfigService runtimeConfig;
    private final BackupScheduleCheckpointService checkpoint;
    private final JobOrderMapper jobOrders;
    private final BackupExecutionModeProvider executionModes;
    private final BackupJobCreationService jobCreation;
    private volatile long lastReconcileAt;
    private volatile LocalDateTime previousSchedulePollAt;

    public ScheduledBackupService(OnlineCandidateClient candidates, ObjectMapper mapper,
                                  OrderSnapshotMapper snapshots, PersistentJobCoordinator coordinator,
                                  RuntimeConfigService runtimeConfig, BackupScheduleCheckpointService checkpoint,
                                  JobOrderMapper jobOrders,
                                  BackupExecutionModeProvider executionModes, BackupJobCreationService jobCreation) {
        this.candidates = candidates; this.mapper = mapper; this.snapshots = snapshots;
        this.coordinator = coordinator; this.runtimeConfig = runtimeConfig; this.checkpoint = checkpoint;
        this.jobOrders = jobOrders;
        this.executionModes = executionModes;
        this.jobCreation = jobCreation;
    }

    /** 定时入口；线上适配器未启用时由配置条件保证不会实例化。 */
    @Scheduled(fixedDelay = 60000)
    public void runScheduled() {
        run(false);
    }

    /**
     * 管理员主动执行一次候选扫描，用于联调、故障补偿或不等待下一个计划日的场景。
     * 仍遵守自动备份总开关、候选资格、已备份排除及任务幂等约束。
     */
    public void runNowByAdministrator() {
        run(true);
    }

    private synchronized void run(boolean forced) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime previousPollAt = previousSchedulePollAt;
        previousSchedulePollAt = now;
        if (!runtimeConfig.getBoolean("online.enabled", false)) return;
        if (!runtimeConfig.getBoolean("backup.schedule.enabled", true)) return;
        long intervalDays = runtimeConfig.getLong("backup.schedule.interval-days", 1L, 1L);
        String startTime = runtimeConfig.get("backup.schedule.start-time", "05:00");
        if (!DailyBackupSchedulePolicy.isDue(now, startTime, intervalDays, checkpoint.lastSuccessAt(), previousPollAt, forced)) return;
        // 每轮从成熟且尚无有效回执的集合起点扫描。不能沿用永久事件游标，
        // 否则此前未成熟或较晚才转为正式状态的历史订单会被永久越过。
        String cursor = "";
        String highWatermark = "";
        int daysBefore = runtimeConfig.getInt("backup.collection.days-before", 7, 0);
        LocalDateTime orderCreatedBefore = BackupMaturityWindow.orderCreatedBefore(now.toLocalDate(), daysBefore);
        int maximumOrders = runtimeConfig.getInt("backup.schedule.max-orders-per-run", 100, 1);
        if (maximumOrders > 1000) {
            throw new IllegalStateException("数据库配置超出允许上限: backup.schedule.max-orders-per-run");
        }
        java.util.LinkedHashSet<Long> orderIds = new java.util.LinkedHashSet<>();
        checkpoint.begin();
        log.info("{}备份候选扫描开始｜执行模式={}｜计划日期={}｜备份间隔={}天｜计划开始时间={}｜备份{}天前的订单｜订单创建时间上界={}（排他）｜单任务订单上限={}",
                forced ? "管理员立即" : "定时", executionModes.mode(), now.toLocalDate(), intervalDays, startTime, daysBefore, orderCreatedBefore,
                maximumOrders);
        try {
          while (true) {
            int remaining = maximumOrders - orderIds.size();
            if (remaining <= 0) break;
            int pageSize = Math.min(INTERNAL_CANDIDATE_PAGE_SIZE, remaining);
            OnlineCandidateClient.Page page = candidates.search(cursor, pageSize, highWatermark,
                    orderCreatedBefore.atOffset(ZoneOffset.UTC));
            int beforePage = orderIds.size();
            if (highWatermark.isBlank() && page.highWatermark() != null) highWatermark = page.highWatermark();
            page.items().stream()
                    .filter(candidate -> candidate.orderId() != null)
                    .filter(candidate -> !isTerminal(candidate.eventStatus()))
                    .filter(candidate -> !alreadyBackedUp(candidate.orderId()))
                    .map(OnlineCandidateClient.Candidate::orderId)
                    .limit(remaining)
                    .forEach(orderIds::add);
            log.info("定时备份候选分页完成｜请求数量={}｜线上返回数量={}｜本页接纳数量={}｜累计待备份数量={}｜是否还有下一页={}",
                    pageSize, page.items().size(), orderIds.size() - beforePage, orderIds.size(), page.hasMore());
            cursor = page.nextCursor();
            if (!page.hasMore() || orderIds.size() >= maximumOrders) break;
            if (cursor == null || cursor.isBlank()) throw new IllegalStateException("线上候选分页缺少下一页游标");
          }
          // 上一批经过任务内重试仍失败的订单，等待下一个计划时间自动补偿。
          int remaining = Math.max(0, maximumOrders - orderIds.size());
          if (remaining > 0) {
            jobOrders.selectList(Wrappers.<JobOrderEntity>lambdaQuery()
                            .eq(JobOrderEntity::getStatus, "FAILED")
                            .orderByAsc(JobOrderEntity::getId))
                    .stream().map(JobOrderEntity::getSourceOrderId)
                    .filter(java.util.Objects::nonNull).distinct()
                    .filter(orderId -> !alreadyBackedUp(orderId))
                    .limit(remaining).forEach(orderIds::add);
          }
          if (!orderIds.isEmpty()) {
            String payload;
            try { payload = mapper.writeValueAsString(new ManualBackupJobHandler.Payload(List.copyOf(orderIds), orderIds.size(),
                    now.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), daysBefore,
                    orderCreatedBefore.atOffset(ZoneOffset.UTC).toString(), executionModes.mode().name(),
                    executionModes.simulationRoot() == null ? null : executionModes.simulationRoot().toString())); }
            catch (Exception exception) { throw new IllegalStateException("定时备份任务参数序列化失败", exception); }
            String dailyPayload = now.toLocalDate() + ":" + payload;
            com.yigongbao.backup.job.JobEntity created = jobCreation.createForOrders(
                    "ORDER_BACKUP", JobIdempotency.key("ORDER_BACKUP", 0L, dailyPayload), 0L, payload, orderIds.size());
            log.info("定时备份任务已创建｜任务ID={}｜任务编号={}｜订单总数={}｜计划日期={}｜首个订单ID={}｜最后订单ID={}",
                    created.getId(), created.getJobNo(), orderIds.size(), now.toLocalDate(),
                    orderIds.iterator().next(), last(orderIds));
          }
          checkpoint.complete(orderIds.size(), "");
          if (orderIds.isEmpty()) {
              log.info("定时备份候选扫描完成，无待备份订单｜计划日期={}｜订单创建时间上界={}（排他）",
                      now.toLocalDate(), orderCreatedBefore);
          }
        } catch (RuntimeException error) {
          checkpoint.fail(error);
          log.error("自动备份候选扫描失败，将在下一个计划时间重新扫描成熟未备份订单", error);
          return;
        }
    }

    /** 每日执行一次候选漏单对账；对账失败不影响下一轮增量备份。 */
    @Scheduled(fixedDelay = 60000)
    public void reconcileCandidates() {
        if (!runtimeConfig.getBoolean("online.enabled", false)) return;
        if (!runtimeConfig.getBoolean("backup.schedule.enabled", true)
                || !runtimeConfig.getBoolean("backup.schedule.reconcile-enabled", true)) return;
        long intervalMillis = runtimeConfig.getLong("backup.schedule.reconcile-interval-hours", 24L, 1L) * 3_600_000L;
        long now = System.currentTimeMillis();
        if (lastReconcileAt > 0 && now - lastReconcileAt < intervalMillis) return;
        lastReconcileAt = now;
        try {
            OnlineCandidateClient.ReconcileResult result = candidates.reconcile(null, null);
            log.info("线上候选对账完成: scanned={}, repaired={}, cancelled={}, deleted={}",
                    result.scanned(), result.repaired(), result.cancelled(), result.deleted());
        } catch (RuntimeException error) {
            log.error("线上候选对账失败，将在下一周期重试", error);
        }
    }

    private boolean alreadyBackedUp(Long orderId) {
        return snapshots.selectCount(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                .eq(OrderSnapshotEntity::getSourceOrderId, orderId)
                .in(OrderSnapshotEntity::getSnapshotStatus, "AVAILABLE", "SUCCESS", "BACKED_UP")) > 0;
    }

    private static boolean isTerminal(String status) { return status != null && ("CANCELLED".equalsIgnoreCase(status) || "DELETED".equalsIgnoreCase(status) || "DELETE".equalsIgnoreCase(status)); }

    private static Long last(java.util.LinkedHashSet<Long> values) {
        Long result = null;
        for (Long value : values) result = value;
        return result;
    }
}
