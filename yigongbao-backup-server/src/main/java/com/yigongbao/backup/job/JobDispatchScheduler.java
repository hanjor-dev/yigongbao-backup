package com.yigongbao.backup.job;
import lombok.RequiredArgsConstructor;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
/** 周期领取并执行到期的持久化任务。 */
@Component @RequiredArgsConstructor public class JobDispatchScheduler {private final JobDispatcher dispatcher;@Scheduled(fixedDelayString="${backup.jobs.dispatch-delay-ms:1000}") public void dispatch(){while(dispatcher.dispatchOne()){}}}
