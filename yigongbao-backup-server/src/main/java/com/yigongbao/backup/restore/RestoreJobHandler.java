package com.yigongbao.backup.restore;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yigongbao.backup.admin.AdminRestoreController;
import com.yigongbao.backup.job.JobEntity;
import com.yigongbao.backup.job.JobExecutionContext;
import com.yigongbao.backup.job.JobHandler;
import com.yigongbao.backup.order.OrderSnapshotEntity;
import com.yigongbao.backup.order.OrderSnapshotMapper;
import com.yigongbao.backup.storage.api.ArtifactStore;
import com.yigongbao.backup.storage.api.SpacePlanner.SpaceReservation;
import com.yigongbao.backup.storage.api.SpaceReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 后台执行线上恢复，每个订单边界都是可取消、可续租的安全点。 */
@Component
@RequiredArgsConstructor
public class RestoreJobHandler implements JobHandler {
    private final OrderSnapshotMapper snapshots;
    private final ArtifactStore store;
    private final ObjectProvider<OnlineRestoreClient> clients;
    private final ObjectMapper mapper;
    private final SpaceReservationService reservations;

    @Override
    public void execute(JobExecutionContext context, JobEntity job) throws Exception {
        AdminRestoreController.RestorePayload payload =
                mapper.readValue(job.getConfigSnapshotJson(), AdminRestoreController.RestorePayload.class);
        List<OrderSnapshotEntity> targets;
        if (payload.orderSnapshotIds() != null && !payload.orderSnapshotIds().isEmpty()) {
            targets = snapshots.selectBatchIds(payload.orderSnapshotIds());
        } else {
            targets = snapshots.selectList(Wrappers.<OrderSnapshotEntity>lambdaQuery()
                    .ge(payload.createdFrom() != null, OrderSnapshotEntity::getOrderCreatedAt, payload.createdFrom())
                    .le(payload.createdTo() != null, OrderSnapshotEntity::getOrderCreatedAt, payload.createdTo())
                    .orderByAsc(OrderSnapshotEntity::getOrderCreatedAt));
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("没有匹配的订单快照");
        OnlineRestoreClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("线上恢复客户端未配置");
        LocalOrderRestoreService service = new LocalOrderRestoreService(client);
        long done = 0;
        for (OrderSnapshotEntity snapshot : targets) {
            context.safePoint("RESTORE_ORDER_" + snapshot.getId(), done);
            if (isDeleted(snapshot.getSourceStatus())) {
                done++;
                continue;
            }
            Path path = packagePath(snapshot);
            SpaceReservation reservation = reservations.reserve("hot", Files.size(path), job.getId());
            context.orderStarted(snapshot.getSourceOrderId(), snapshot.getId());
            context.fileStarted(snapshot.getSourceOrderId(), snapshot.getOrderCode(), "RESTORE_UPLOAD", Files.size(path));
            try {
                service.restore(path, snapshot.getSourceOrderId().toString());
                context.fileSucceeded(snapshot.getSourceOrderId(), Files.size(path));
                context.orderSucceeded();
            } finally {
                reservations.release(reservation);
            }
            done++;
            context.safePoint("RESTORE_ORDER_COMPLETED", done);
        }
    }

    private Path packagePath(OrderSnapshotEntity snapshot) {
        if (snapshot == null || snapshot.getSourceOrderId() == null || snapshot.getOrderCode() == null
                || !snapshot.getOrderCode().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("恢复订单快照不完整");
        }
        Path path = store.root().resolve("orders/" + snapshot.getOrderCode() + "/"
                + snapshot.getOrderCode() + ".zip").normalize();
        if (!path.startsWith(store.root()) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("订单热备包不存在，请先从冷备导入");
        }
        return path;
    }

    private static boolean isDeleted(String status) {
        return status != null && ("DELETED".equalsIgnoreCase(status) || "DELETE".equalsIgnoreCase(status));
    }
}
