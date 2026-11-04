package com.yigongbao.backup.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 运行时配置读取服务。
 * <p>备份业务参数统一从 bk_config 读取。基础设施必填项使用 {@link #require(String)}，
 * 缺失时明确失败，禁止静默回退到 YAML 或代码中的第二份业务值。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuntimeConfigService {
    private final PersistentConfigService configs;

    /** 读取字符串配置；defaultValue仅保留二进制兼容，生产不再回退代码默认值。 */
    public String get(String key, String defaultValue) {
        try {
            ConfigService.Entry entry = configs.get(key);
            if (entry == null || entry.value() == null) {
                throw new IllegalStateException("缺少数据库配置: " + key);
            }
            return entry.value().trim();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("读取数据库配置失败: " + key, exception);
        }
    }

    /** 读取整数配置并校验最小值。 */
    public int getInt(String key, int defaultValue, int min) {
        String value = get(key, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min) throw new IllegalStateException("数据库整数配置低于允许下限: " + key);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("数据库整数配置非法: " + key, exception);
        }
    }

    /** 读取长整数配置并校验最小值。 */
    public long getLong(String key, long defaultValue, long min) {
        String value = get(key, Long.toString(defaultValue));
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min) throw new IllegalStateException("数据库长整数配置低于允许下限: " + key);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("数据库长整数配置非法: " + key, exception);
        }
    }

    /** 读取布尔配置。只有 true/false 才会生效。 */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, Boolean.toString(defaultValue)).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalStateException("数据库布尔配置非法: " + key);
        };
    }

    /** 读取逗号分隔的集合配置。 */
    public Set<String> getCsvSet(String key, String defaultValue) {
        String value = get(key, defaultValue);
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(result::add);
        return Set.copyOf(result);
    }

    /** 读取数据库中的必填配置；缺失或读取失败时给出包含配置键的明确异常。 */
    public String require(String key) {
        try {
            ConfigService.Entry entry = configs.get(key);
            if (entry == null || entry.value() == null || entry.value().isBlank()) {
                throw new IllegalStateException("缺少必填数据库配置: " + key);
            }
            return entry.value().trim();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("读取必填数据库配置失败: " + key, exception);
        }
    }
}
