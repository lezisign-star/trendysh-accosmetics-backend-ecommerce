package com.nbh.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Lets a listener opt in to running on its own thread with {@code @Async} — used for slow work like sending email. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
