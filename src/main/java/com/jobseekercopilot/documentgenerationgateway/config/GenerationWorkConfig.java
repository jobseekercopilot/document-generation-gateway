package com.jobseekercopilot.documentgenerationgateway.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class GenerationWorkConfig {

    @Bean(name = "generationWorkExecutor")
    ThreadPoolTaskExecutor generationWorkExecutor(
            @Value("${document-generation.executor.core-pool-size:2}")
            int corePoolSize,
            @Value("${document-generation.executor.max-pool-size:4}")
            int maxPoolSize,
            @Value("${document-generation.executor.queue-capacity:32}")
            int queueCapacity) {
        if (corePoolSize < 1
                || maxPoolSize < corePoolSize
                || queueCapacity < 0) {
            throw new IllegalStateException(
                    "Document generation executor bounds are invalid.");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("document-generation-");
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "generationRetryScheduler")
    ThreadPoolTaskScheduler generationRetryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("document-generation-retry-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
