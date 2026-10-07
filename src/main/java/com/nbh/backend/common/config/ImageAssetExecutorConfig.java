package com.nbh.backend.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Where a deleted photo is removed from Cloudinary. That is a network call to a third party, so it runs off the
 * request thread: a slow Cloudinary never slows (or fails) the staff member's "Remove photo". Tests set
 * {@code app.image-assets.sync-delete=true} so the deletion has happened by the time the request returns.
 */
@Configuration
public class ImageAssetExecutorConfig {

    @Bean(name = "imageAssetExecutor")
    public Executor imageAssetExecutor(@Value("${app.image-assets.sync-delete:false}") boolean sync) {
        if (sync) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("image-assets-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        // A deploy should not forget photos that are already queued for deletion.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
