package com.yigongbao.backup.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在 Spring Boot 加载业务配置前读取工作目录中的 .env 文件。
 *
 * <p>显式系统环境变量优先于 .env，.env 优先于 application.yml 中的默认值；
 * .env 不存在时不影响正常启动。</p>
 */
public final class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String ENV_FILE_PROPERTY = "BACKUP_ENV_FILE";
    private static final String PROPERTY_SOURCE_NAME = "backupDotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = resolveEnvFile();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return;
        }
        try {
            Map<String, Object> values = parse(Files.readAllLines(envFile, StandardCharsets.UTF_8));
            values.entrySet().removeIf(entry -> System.getenv(entry.getKey()) != null
                    || System.getProperty(entry.getKey()) != null);
            if (!values.isEmpty()) {
                environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 .env 配置文件: " + envFile, exception);
        }
    }

    private Path resolveEnvFile() {
        String configured = System.getenv(ENV_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty(ENV_FILE_PROPERTY);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path workingDirectoryFile = Path.of(".env").toAbsolutePath().normalize();
        if (Files.isRegularFile(workingDirectoryFile)) {
            return workingDirectoryFile;
        }
        try {
            URI location = DotenvEnvironmentPostProcessor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path codeLocation = Path.of(location);
            Path applicationDirectory = Files.isDirectory(codeLocation)
                    ? codeLocation.getParent().getParent()
                    : codeLocation.getParent();
            return applicationDirectory.resolve(".env").toAbsolutePath().normalize();
        } catch (URISyntaxException | NullPointerException exception) {
            return workingDirectoryFile;
        }
    }

    static Map<String, Object> parse(Iterable<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String sourceLine : lines) {
            if (sourceLine == null) {
                continue;
            }
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            String value = line.substring(separator + 1).trim();
            values.put(key, unquote(value));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
