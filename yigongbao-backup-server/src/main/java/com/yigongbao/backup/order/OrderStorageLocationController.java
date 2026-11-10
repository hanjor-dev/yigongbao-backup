package com.yigongbao.backup.order;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yigongbao.backup.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 为订单页提供脱敏的包所在卷与可访问性，物理路径不下发给普通用户。 */
@RestController
public class OrderStorageLocationController {
    private final OrderPackageMapper packages; private final OrderStorageLocationService locations;
    public OrderStorageLocationController(OrderPackageMapper packages, OrderStorageLocationService locations) { this.packages = packages; this.locations = locations; }
    @GetMapping("/api/v1/orders/{id}/package-location")
    public ApiResponse<OrderStorageLocationService.LocationView> location(@PathVariable long id) {
        StpUtil.checkLogin();
        OrderPackageEntity pkg = packages.selectOne(Wrappers.<OrderPackageEntity>lambdaQuery().eq(OrderPackageEntity::getSnapshotId, id).eq(OrderPackageEntity::getStatus, "AVAILABLE").last("LIMIT 1"));
        return ApiResponse.ok(locations.locate(pkg), UUID.randomUUID().toString());
    }
}
