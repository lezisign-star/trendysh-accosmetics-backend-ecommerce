package com.nbh.backend.tumaboda;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Where TumaBoda booking calls run. The order-event listeners' own javadoc says any listener doing real
 * network I/O must leave the request thread; a staff member marking an order READY should not wait on
 * TumaBoda. Tests set {@code app.tumaboda.sync-booking=true} so a booking happens before the call returns
 * and can be asserted on without polling.
 */
@Configuration
public class TumaBodaExecutorConfig {

    @Bean(name = "tumabodaTaskExecutor")
    public Executor tumabodaTaskExecutor(@Value("${app.tumaboda.sync-booking:false}") boolean sync) {
        if (sync) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("tumaboda-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
