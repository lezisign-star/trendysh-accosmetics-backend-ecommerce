package com.nbh.backend.common.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Where emails are actually sent. Sending is a network call to the mail provider, so it runs off the
 * request thread: a slow or failing provider then neither slows nor fails the request that asked for the
 * email, and — for the order-lookup and password-reset codes — a request for an address that has orders
 * (which sends an email) takes no longer than one for an address that has none, so response time does
 * not reveal who has bought. Tests set {@code app.email.sync-send=true} so a send has happened by the
 * time the request returns and can be asserted on directly.
 */
@Configuration
public class EmailExecutorConfig {

    @Bean(name = "emailTaskExecutor")
    public Executor emailTaskExecutor(@Value("${app.email.sync-send:false}") boolean sync) {
        if (sync) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("email-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        // A deploy should not throw away emails that are already queued.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
