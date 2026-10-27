package com.yigongbao.backup.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 基于 bk_config 的配置服务；更新采用版本行追加和配置版本CAS。 */
@Service
@RequiredArgsConstructor
public class PersistentConfigService {
    private static final String GLOBAL_SCOPE = "GLOBAL";
    private final BkConfigMapper mapper;

    public ConfigService.Entry get(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("配置键为空");
        BkConfigEntity entity = latest(key);
        return entity == null ? null : view(entity);
    }

    public List<ConfigService.Entry> list() {
        Map<String, BkConfigEntity> latest = mapper.selectList(Wrappers.<BkConfigEntity>lambdaQuery()
                        .eq(BkConfigEntity::getScope, GLOBAL_SCOPE)
                        .orderByDesc(BkConfigEntity::getConfigVersion))
                .stream().collect(Collectors.toMap(BkConfigEntity::getConfigKey, Function.identity(),
                        (left, right) -> left.getConfigVersion() >= right.getConfigVersion() ? left : right));
        return latest.values().stream().sorted(Comparator.comparing(BkConfigEntity::getConfigKey))
                .map(PersistentConfigService::view).toList();
    }

    public synchronized ConfigService.Entry put(String key, String value, int expectedVersion, long actor) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("配置键非法");
        ConfigValueValidator.validate(key, value);
        BkConfigEntity old = latest(key);
        int current = old == null ? 0 : old.getConfigVersion();
        if (current != expectedVersion) throw new IllegalStateException("配置版本冲突");
        BkConfigEntity next = new BkConfigEntity();
        next.setConfigKey(key);
        next.setScope(GLOBAL_SCOPE);
        next.setValueType("STRING");
        next.setConfigValue(value);
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
                        .orderByDesc(BkConfigEntity::getConfigVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private static ConfigService.Entry view(BkConfigEntity entity) {
        return new ConfigService.Entry(entity.getConfigKey(), entity.getConfigValue(), entity.getConfigVersion(),
                entity.getChangedBy() == null ? 0L : entity.getChangedBy(),
                entity.getChangedAt() == null ? entity.getUpdateTime().toInstant(java.time.ZoneOffset.UTC) :
                        entity.getChangedAt().toInstant(java.time.ZoneOffset.UTC));
    }
}
