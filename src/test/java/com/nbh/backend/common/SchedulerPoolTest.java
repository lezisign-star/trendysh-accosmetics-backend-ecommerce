package com.nbh.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot runs every {@code @Scheduled} job on ONE thread unless told otherwise. With the expiry
 * sweeps and the TumaBoda jobs sharing it, a poll stuck on a slow network call would hold the sweeps back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchedulerPoolTest {

    @Autowired private ThreadPoolTaskScheduler scheduler;

    @Test
    void scheduledJobsDoNotAllQueueBehindASingleThread() {
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(4);
    }
}
