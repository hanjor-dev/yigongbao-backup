package com.yigongbao.backup.admin;

import cn.dev33.satoken.stp.StpUtil;
import com.yigongbao.backup.cold.ColdImportService;
import com.yigongbao.backup.cold.ColdImportJobHandler;
import com.yigongbao.backup.common.ApiResponse;
import com.yigongbao.backup.job.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 管理员冷备导入区扫描和按订单选择性回迁接口。 */
@RestController
@RequestMapping("/api/v1/admin/cold-imports")
@RequiredArgsConstructor
public class AdminColdImportController {
    private final ColdImportService imports;
    private final PersistentJobCoordinator jobs;
    private final ObjectMapper mapper;

    @GetMapping("/scan")
    public ApiResponse<List<String>> scan() throws IOException {
        admin();
        throw new IllegalStateException("旧冷备导入区流程已停用，请重新插入原冷备盘并在存储卷管理中确认挂载");
    }

    /** 契约规定的幂等扫描命令；GET 版本保留给管理页面刷新使用。 */
    @PostMapping("/scan")
    public ApiResponse<List<String>> scanCommand() throws IOException {
        return scan();
    }

    /** batchId 在第一期对应导入区压缩文件名；orderIds 为空时恢复该包内全部订单。 */
    @PostMapping("/{batchId}/restore")
    public ApiResponse<RestoreTask> restore(@PathVariable("batchId") String batchId,
                                              @RequestBody(required = false) RestoreCommand command)
            throws Exception {
        admin();
        throw new IllegalStateException("旧冷备导入区流程已停用，请重新插入原冷备盘并在存储卷管理中确认挂载");
    }

    private static void admin() {
        StpUtil.checkRole("ADMIN");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    public record RestoreCommand(Set<String> orderIds) {
    }

    public record RestoreTask(Long jobId, String jobNo, String status) {
    }
}
