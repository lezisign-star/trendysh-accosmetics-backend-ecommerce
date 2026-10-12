package com.nbh.backend.variantjobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Hands waiting jobs to workers (VARIANTS_PLAN.md 3.3). Every few seconds it claims as many jobs as there are free
 * workers ({@code app.variant-jobs.workers}, default 3): queued ones, paused ones whose wait is over, and running ones
 * whose worker stopped saying it is alive for {@code stale-heartbeat-minutes} (the server restarted mid-job). The
 * database is the queue, so nothing is lost on a restart and two servers never take the same job.
 */
@Slf4j
@Component
public class VariantJobPoller {

    private final VariantJobWriter writer;
    private final VariantJobWorker worker;
    private final Semaphore free;
    private final ThreadPoolTaskExecutor executor;
    private final Duration stale;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public VariantJobPoller(VariantJobWriter writer, VariantJobWorker worker,
                            @Value("${app.variant-jobs.workers:3}") int workers,
                            @Value("${app.variant-jobs.stale-heartbeat-minutes:5}") long staleMinutes) {
        this.writer = writer;
        this.worker = worker;
        int n = Math.max(1, workers);
        this.free = new Semaphore(n);
        this.stale = Duration.ofMinutes(staleMinutes);
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("variant-job-");
        executor.setCorePoolSize(n);
        executor.setMaxPoolSize(n);
        executor.setQueueCapacity(n);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
    }

    @Scheduled(fixedDelayString = "${app.variant-jobs.poll-ms:3000}",
            initialDelayString = "${app.variant-jobs.initial-delay-ms:10000}")
    public void poll() {
        pollOnce();
    }

    /** Claims and starts as many jobs as there are free workers; how many it started. */
    public int pollOnce() {
        int started = 0;
        while (free.tryAcquire()) {
            Optional<UUID> claimed;
            try {
                claimed = writer.claim(workerId, Instant.now().minus(stale));
            } catch (RuntimeException e) {
                free.release();
                log.warn("Couldn't claim a variant job: {}", e.getMessage());
                break;
            }
            if (claimed.isEmpty()) {
                free.release();
                break;
            }
            UUID jobId = claimed.get();
            executor.execute(() -> {
                try {
                    worker.run(jobId);
                } finally {
                    free.release();
                }
            });
            started++;
        }
        return started;
    }

    /** Workers busy right now (for tests and the status screen). */
    public int busy() {
        return executor.getActiveCount();
    }
}
