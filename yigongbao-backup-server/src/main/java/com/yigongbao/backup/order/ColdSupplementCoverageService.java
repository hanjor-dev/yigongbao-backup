package com.yigongbao.backup.order;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** 冷备批次成功写入后，整批标记订单补传文件的冷备覆盖关系。 */
@Service
@RequiredArgsConstructor
public class ColdSupplementCoverageService {
    private final OrderSnapshotMapper snapshots;
    private final BkSupplementFileMapper supplements;

    @Transactional
    public int markCovered(long batchId, Collection<String> orderCodes, long actor) {
        if (batchId < 1 || orderCodes == null || orderCodes.isEmpty()) return 0;
        Set<String> codes = orderCodes.stream().filter(code -> code != null && code.matches("[A-Za-z0-9_-]{1,64}"))
                .collect(Collectors.toSet());
        if (codes.isEmpty()) return 0;
        var snapshotIds = snapshots.selectList(new QueryWrapper<OrderSnapshotEntity>().in("order_code", codes))
                .stream().map(OrderSnapshotEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (snapshotIds.isEmpty()) return 0;
        return supplements.update(null, new UpdateWrapper<BkSupplementFileEntity>()
                .in("snapshot_id", snapshotIds)
                .set("cold_coverage_status", "COVERED")
                .set("covered_batch_id", batchId)
                .set("update_by", actor));
    }
}
