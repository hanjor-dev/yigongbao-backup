package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

/** 基于 bk_config 的配置服务；更新采用版本行追加和配置版本CAS。 */
@Service
public class PersistentConfigService {
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private final BkConfigMapper mapper;
    private final ObjectProvider<ConfigurationChangeGuard> guardProvider;

    public PersistentConfigService(BkConfigMapper mapper) { this(mapper, null); }

    @Autowired
    public PersistentConfigService(BkConfigMapper mapper,
                                   ObjectProvider<ConfigurationChangeGuard> guardProvider) {
        this.mapper = mapper;
        this.guardProvider = guardProvider;
    }

    public ConfigService.Entry get(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("配置键为空");
        BkConfigEntity entity = latest(key);
        return entity == null ? null : view(entity);
    }

    public List<ConfigService.Entry> list() {
        Map<String, BkConfigEntity> latest = mapper.selectList(Wrappers.<BkConfigEntity>lambdaQuery()
                        .eq(BkConfigEntity::getScope, GLOBAL_SCOPE)
                        .eq(BkConfigEntity::getIsDeleted, 0)
                        .orderByDesc(BkConfigEntity::getConfigVersion))
                .stream().collect(Collectors.toMap(BkConfigEntity::getConfigKey, Function.identity(),
                        (left, right) -> left.getConfigVersion() >= right.getConfigVersion() ? left : right));
        return latest.values().stream().sorted(Comparator.comparing(BkConfigEntity::getConfigKey))
                .map(PersistentConfigService::view).toList();
    }

    /** 返回当前有效配置涉及的分类，保持数据库配置的展示顺序稳定。 */
    public List<Category> categories() {
        List<ConfigService.Entry> entries = list();
        return entries.stream()
                .collect(Collectors.toMap(ConfigService.Entry::categoryCode,
                        entry -> new Category(entry.categoryCode(), entry.categoryName(), 0),
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream().map(category -> new Category(category.code(), category.name(),
                        (int) entries.stream().filter(item -> category.code().equals(item.categoryCode())).count()))
                .sorted(Comparator.comparingInt(category -> categoryOrder(category.code())))
                .toList();
    }

    @Transactional
    public ConfigService.Entry put(String key, String value, int expectedVersion, long actor) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("配置键非法");
        ConfigValueValidator.validate(key, value);
        String mutationLock = mapper.lockGlobalMutation();
        ConfigurationChangeGuard guard = guardProvider == null ? null : guardProvider.getIfAvailable();
        if (guardProvider != null && mutationLock == null) throw new IllegalStateException("配置全局写锁未初始化");
        if (guard != null) guard.validate(key, value);
        BkConfigEntity old = latest(key);
        int current = old == null ? 0 : old.getConfigVersion();
        if (current != expectedVersion) throw new IllegalStateException("配置版本冲突");
        BkConfigEntity next = new BkConfigEntity();
        next.setConfigKey(key);
        next.setConfigName(old == null ? key : old.getConfigName());
        next.setCategoryCode(old == null ? categoryCode(key) : old.getCategoryCode());
        next.setCategoryName(old == null ? categoryName(next.getCategoryCode()) : old.getCategoryName());
        next.setScope(GLOBAL_SCOPE);
        next.setValueType(old == null ? inferValueType(key) : old.getValueType());
        next.setConfigValue(value);
        next.setOptionsJson(old == null ? null : old.getOptionsJson());
        next.setDescription(old == null ? null : old.getDescription());
        next.setSecretRef(old == null ? null : old.getSecretRef());
        next.setConfigVersion(current + 1);
        next.setChangedBy(actor);
        next.setChangedAt(LocalDateTime.now());
        next.setCreateBy(actor);
        next.setUpdateBy(actor);
        mapper.insert(next);
        return view(next);
    }

    private BkConfigEntity latest(String key) {
        return mapper.selectList(Wrappers.<BkConfigEntity>lambdaQuery()
                        .eq(BkConfigEntity::getConfigKey, key)
                        .eq(BkConfigEntity::getScope, GLOBAL_SCOPE)
                        .eq(BkConfigEntity::getIsDeleted, 0)
                        .orderByDesc(BkConfigEntity::getConfigVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 新增配置时保留可读的类型信息，便于管理页和审计工具识别。 */
    private static String inferValueType(String key) {
        if (key.endsWith(".percent") || key.endsWith("-minutes") || key.endsWith("-hours")
                || key.endsWith("-seconds") || key.endsWith("-count") || key.endsWith("-size")) return "INTEGER";
        if (key.endsWith(".bytes") || key.endsWith("-bytes")) return "LONG";
        if (key.endsWith("-enabled") || key.endsWith("-required") || key.endsWith("-protection")
                || key.endsWith("-deleted") || key.startsWith("backup.schedule.")) return "BOOLEAN";
        return "STRING";
    }

    private static ConfigService.Entry view(BkConfigEntity entity) {
        return new ConfigService.Entry(entity.getConfigKey(),
                entity.getConfigName() == null ? entity.getConfigKey() : entity.getConfigName(),
                entity.getCategoryCode() == null ? categoryCode(entity.getConfigKey()) : entity.getCategoryCode(),
                entity.getCategoryName() == null ? categoryName(entity.getCategoryCode() == null ? categoryCode(entity.getConfigKey()) : entity.getCategoryCode()) : entity.getCategoryName(),
                entity.getValueType(), entity.getConfigValue(), entity.getOptionsJson(), entity.getDescription(),
                entity.getConfigVersion(), entity.getChangedBy() == null ? 0L : entity.getChangedBy(),
                com.yigongbao.backup.common.time.BusinessTimeCodec.toInstant(
                        entity.getChangedAt() == null ? entity.getUpdateTime() : entity.getChangedAt()));
    }

    private static String categoryCode(String key) {
        if (key == null) return "JOB_EXECUTION";
        if (key.startsWith("backup.")) return "BACKUP_COLLECTION";
        if (key.startsWith("storage.")) return "STORAGE_MANAGEMENT";
        if (key.startsWith("cold.")) return "COLD_ARCHIVE";
        if (key.startsWith("restore.")) return "ONLINE_RESTORE";
        if (key.startsWith("cleanup.")) return "CLOUD_CLEANUP";
        if (key.startsWith("supplement.")) return "SUPPLEMENT_FILE";
        if (key.startsWith("account.")) return "ACCOUNT_SYNC";
        if (key.startsWith("online.")) return "ONLINE_INTEGRATION";
        return "JOB_EXECUTION";
    }

    private static String categoryName(String code) {
        return switch (code == null ? "JOB_EXECUTION" : code) {
            case "BACKUP_COLLECTION" -> "备份采集策略";
            case "STORAGE_MANAGEMENT" -> "存储与容量";
            case "COLD_ARCHIVE" -> "冷备归档与导入";
            case "ONLINE_RESTORE" -> "线上恢复策略";
            case "CLOUD_CLEANUP" -> "云端清理策略";
            case "SUPPLEMENT_FILE" -> "补传文件规则";
            case "ACCOUNT_SYNC" -> "账号同步策略";
            case "ONLINE_INTEGRATION" -> "线上系统连接";
            default -> "任务执行策略";
        };
    }

    private static int categoryOrder(String code) {
        return switch (code == null ? "" : code) {
            case "BACKUP_COLLECTION" -> 10; case "STORAGE_MANAGEMENT" -> 20;
            case "COLD_ARCHIVE" -> 30; case "ONLINE_RESTORE" -> 40;
            case "CLOUD_CLEANUP" -> 50; case "SUPPLEMENT_FILE" -> 60;
            case "ACCOUNT_SYNC" -> 70; case "ONLINE_INTEGRATION" -> 80;
            default -> 90;
        };
    }

    public record Category(String code, String name, int configCount) { }
}
