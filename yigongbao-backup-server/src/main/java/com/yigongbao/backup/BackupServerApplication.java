package com.yigongbao.backup;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 本地备份服务启动类，负责加载 Spring Boot、MyBatis-Plus 和 Sa-Token 基础配置。 */
@SpringBootApplication
@EnableScheduling
public class BackupServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackupServerApplication.class, args);
    }
}
