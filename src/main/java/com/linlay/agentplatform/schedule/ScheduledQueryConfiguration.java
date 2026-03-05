package com.linlay.agentplatform.schedule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ScheduledQueryConfiguration {

    @Bean(name = "scheduledQueryTaskScheduler")
    public TaskScheduler scheduledQueryTaskScheduler(ScheduleCatalogProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getPoolSize()));
        scheduler.setThreadNamePrefix("scheduled-query-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
