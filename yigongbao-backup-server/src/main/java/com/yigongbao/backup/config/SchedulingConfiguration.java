package com.yigongbao.backup.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 将任务执行、租约维护、卷巡检等调度工作隔离，避免一个长任务阻塞全部后台维护。 */
@Configuration
@Slf4j
public class SchedulingConfiguration {

    @Bean(name = "taskScheduler")
    public TaskScheduler taskSchedulerBean() {
        return taskScheduler();
    }

    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("backup-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(failure -> {
            ScheduledTaskFailureDetails details = ScheduledTaskFailureDetails.from(failure);
            log.error("后台调度任务执行失败｜调度任务={}｜调度类型={}｜执行方法={}｜异常类型={}｜异常原因={}",
                    details.taskName(),
                    details.taskType(),
                    details.executionMethod(),
                    failure.getClass().getSimpleName(),
                    failure.getMessage(),
                    failure);
        });
        return scheduler;
    }
}
