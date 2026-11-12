package com.yigongbao.backup.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusUtil;
import ch.qos.logback.core.util.FileSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionLoggingConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void logbackUsesTheJarWorkingDirectoryAndItsOwnBoundedRotationPolicy() throws Exception {
        String classpath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path"));
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java");

        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                classpath,
                LogbackProbe.class.getName())
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(exited).as("Logback probe exits in time; output=%s", output).isTrue();
        assertThat(process.exitValue()).as("Logback probe output=%s", output).isZero();
        assertThat(output)
                .contains("MAX_FILE_SIZE=104857600")
                .contains("MAX_HISTORY=30")
                .contains("TOTAL_SIZE_CAP=2147483648")
                .contains("CLEAN_HISTORY_ON_START=true")
                .contains("ARCHIVE_PATTERN=./log/yigongbao-backup-server.%d{yyyy-MM-dd}.%i.log.gz")
                .contains("CONSOLE_APPENDER=true");

        Path logFile = tempDir.resolve("log/yigongbao-backup-server.log");
        assertThat(logFile).exists();
        assertThat(Files.readString(logFile, StandardCharsets.UTF_8))
                .contains("logback-relative-path-test");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public static class LogbackProbe {

        public static void main(String[] args) throws Exception {
            URL configuration = LogbackProbe.class.getClassLoader().getResource("logback-spring.xml");
            if (configuration == null) {
                throw new IllegalStateException("logback-spring.xml not found");
            }

            LoggerContext context = new LoggerContext();
            context.setMDCAdapter(new LogbackMDCAdapter());
            try {
                JoranConfigurator configurator = new JoranConfigurator();
                configurator.setContext(context);
                configurator.doConfigure(configuration);
                context.start();

                if (new StatusUtil(context).getHighestLevel(0) >= Status.ERROR) {
                    throw new IllegalStateException("Logback configuration contains errors");
                }

                Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
                @SuppressWarnings("unchecked")
                RollingFileAppender<ILoggingEvent> appender =
                        (RollingFileAppender<ILoggingEvent>) root.getAppender("ROLLING_FILE");
                @SuppressWarnings("unchecked")
                SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy =
                        (SizeAndTimeBasedRollingPolicy<ILoggingEvent>) appender.getRollingPolicy();

                FileSize maxFileSize = (FileSize) ReflectionTestUtils.getField(policy, "maxFileSize");
                FileSize totalSizeCap = (FileSize) ReflectionTestUtils.getField(policy, "totalSizeCap");
                System.out.println("MAX_FILE_SIZE=" + maxFileSize.getSize());
                System.out.println("MAX_HISTORY=" + policy.getMaxHistory());
                System.out.println("TOTAL_SIZE_CAP=" + totalSizeCap.getSize());
                System.out.println("CLEAN_HISTORY_ON_START=" + policy.isCleanHistoryOnStart());
                System.out.println("ARCHIVE_PATTERN=" + policy.getFileNamePattern());
                System.out.println("CONSOLE_APPENDER=" + (root.getAppender("CONSOLE") != null));

                appender.doAppend(new LoggingEvent(
                        LogbackProbe.class.getName(),
                        root,
                        Level.INFO,
                        "logback-relative-path-test",
                        null,
                        null));
            } finally {
                context.stop();
            }
        }
    }
}
