package com.nbh.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * §3.7: the one real testability obstacle for the expiry-boundary logic in
 * {@code StockReservationService}/{@code CheckoutService}/{@code
 * StockPostingService}/{@code PaymentService} was a direct {@code
 * Instant.now()} call — untestable without either a real wall-clock wait or
 * heavyweight {@code Mockito.mockStatic(Instant.class)}. Injecting this bean
 * instead lets a unit test swap in {@code Clock.fixed(...)} with no
 * production behaviour change ({@code Clock.systemUTC()} behaves identically
 * to a bare {@code Instant.now()}).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
