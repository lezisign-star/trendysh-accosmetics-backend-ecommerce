package com.nbh.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Truncates the timestamp handed to every {@code @CreatedDate}/{@code @LastModifiedDate} field to
 * microseconds — found live (2026-09-29): Postgres's {@code timestamptz} columns only keep
 * microsecond precision and the JDBC driver ROUNDS (not truncates) to the nearest one on write, so
 * the nanosecond-precision {@code Instant.now()} the default provider hands auditing could differ
 * from what a later fresh SELECT of that same row reads back by a few hundred nanoseconds. That
 * showed up as {@code ProductService#setPublished}'s freshly-reloaded response reporting a
 * different {@code updatedAt} string than the row was created with, even though the column itself
 * never changed. Truncating up front leaves nothing for Postgres to round, so an in-memory read
 * right after a write and a fresh SELECT of the same row always agree, byte for byte. Uses the
 * app's shared {@link Clock} bean rather than a bare {@code Instant.now()} for the same testability
 * reason {@link ClockConfig} exists.
 */
@Configuration
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }
}
