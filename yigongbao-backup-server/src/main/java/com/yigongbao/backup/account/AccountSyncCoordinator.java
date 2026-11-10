package com.yigongbao.backup.account;

import com.yigongbao.backup.config.RuntimeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/** 账号同步统一入口，供定时任务和管理员手动操作共用。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountSyncCoordinator {
    private static final int MAX_PAGES_PER_RUN = 10_000;
    private final RemoteAccountClient client;
    private final AccountSyncCheckpointService checkpoints;
    private final RuntimeConfigService configs;
    private final AtomicBoolean running = new AtomicBoolean();

    /** 执行一次同步；manual=true 仅绕过调度周期，不绕过功能和线上总开关。 */
    public Result synchronize(boolean manual) {
        if (!configs.getBoolean("account.sync-enabled", true)) return new Result("DISABLED", 0, "账号同步已关闭");
        if (!configs.getBoolean("online.enabled", false)) return new Result("DISABLED", 0, "线上通信已关闭");
        if (!running.compareAndSet(false, true)) return new Result("RUNNING", 0, "已有账号同步正在执行");
        int total = 0;
        try {
            AccountSyncCheckpointService.RunState run = checkpoints.begin();
            String cursor = run.cursor();
            String highWatermark = run.highWatermark();
            int pageSize = configs.getInt("account.sync-page-size", 200, 1);
            for (int pageNumber = 1; pageNumber <= MAX_PAGES_PER_RUN; pageNumber++) {
                RemoteAccountClient.Page page = client.fetch(cursor, highWatermark, pageSize);
                if (page == null || page.accounts() == null) throw new IllegalStateException("线上账号接口返回为空");
                String next = page.nextCursor();
                if (page.hasMore() && (next == null || next.isBlank())) {
                    throw new IllegalStateException("线上账号接口缺少下一页游标");
                }
                if (next != null && next.equals(cursor) && page.hasMore()) {
                    throw new IllegalStateException("线上账号接口游标未推进");
                }
                if (highWatermark == null) highWatermark = page.highWatermark();
                if (page.highWatermark() != null && !page.highWatermark().equals(highWatermark)) {
                    throw new IllegalStateException("线上账号接口 high-watermark 在分页期间发生变化");
                }
                checkpoints.commitPage(page.accounts(), next, highWatermark);
                total += page.accounts().size();
                if (!page.hasMore()) {
                    checkpoints.complete(total);
                    log.info("线上账号同步完成: trigger={}, count={}", manual ? "MANUAL" : "SCHEDULED", total);
                    return new Result("SUCCEEDED", total, null);
                }
                cursor = next;
            }
            throw new IllegalStateException("单轮账号同步分页数超过安全限制");
        } catch (RuntimeException exception) {
            // 状态持久化失败不能覆盖真正的同步异常，否则运维侧会看到错误的根因。
            try {
                checkpoints.fail(rootMessage(exception));
            } catch (RuntimeException statusException) {
                exception.addSuppressed(statusException);
                log.error("账号同步失败状态持久化异常", statusException);
            }
            log.error("线上账号同步失败: trigger={}, committedCount={}", manual ? "MANUAL" : "SCHEDULED", total, exception);
            return new Result("FAILED", total, rootMessage(exception));
        } finally {
            running.set(false);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record Result(String status, int itemCount, String message) { }
}
