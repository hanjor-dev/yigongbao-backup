package com.yigongbao.backup.admin;
import cn.dev33.satoken.stp.StpUtil; import com.yigongbao.backup.common.ApiResponse; import com.yigongbao.backup.storage.api.StorageProbe; import com.yigongbao.backup.storage.validation.ManagedStoragePathPolicy; import lombok.RequiredArgsConstructor; import org.springframework.web.bind.annotation.*; import java.util.UUID;
/** 管理员存储池容量探测接口。 */
@RestController @RequestMapping("/api/v1/admin/storage") @RequiredArgsConstructor
public class AdminStorageController { private final StorageProbe probe; private final ManagedStoragePathPolicy paths; private static String rid(){return UUID.randomUUID().toString();} @PostMapping("/probe") public ApiResponse<StorageProbe.StorageSnapshot> probe(@RequestBody ProbeCommand c){StpUtil.checkRole("ADMIN"); return ApiResponse.ok(probe.probe(paths.requireManaged(c==null?null:c.path(),"存储目录")),rid());} public record ProbeCommand(String path){} }
