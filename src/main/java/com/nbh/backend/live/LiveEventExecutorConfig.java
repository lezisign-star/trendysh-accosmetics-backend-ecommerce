package com.nbh.backend.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Where live updates are written to open connections. Writing to a browser can block for as long as its
 * connection is slow, so it happens on these threads, never on the request that committed the order or
 * payment. A full queue drops the update instead of slowing anything down: an update is only a hint to look
 * again and every screen also refreshes on its own. Tests set {@code app.live.sync=true} so an update has
 * been written by the time the request that caused it returns.
 */
@Configuration
public class LiveEventExecutorConfig {

    @Bean(name = "liveEventExecutor")
    public Executor liveEventExecutor(@Value("${app.live.sync:false}") boolean sync) {
        if (sync) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("live-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
