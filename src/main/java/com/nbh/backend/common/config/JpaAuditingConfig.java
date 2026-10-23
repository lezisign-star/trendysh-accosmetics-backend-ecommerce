package com.nbh.backend.common.config;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
 *
 * <p>Also says who is saving, for the fields marked {@code @LastModifiedBy} (a variant's, which the price history reads): the
 * signed-in staff member, or "system" when nobody is signed in (a background job), so a save is never credited to whoever
 * happened to edit the row last.
 */
@Configuration
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    @Bean
    public AuditorAware<String> staffAuditor() {
        return () -> {
            Authentication who = SecurityContextHolder.getContext().getAuthentication();
            if (who != null && who.getPrincipal() instanceof StaffUser staff) {
                String name = staff.getFullName();
                return Optional.of(name.length() <= 200 ? name : name.substring(0, 200));
            }
            return Optional.of("system");
        };
    }
}
