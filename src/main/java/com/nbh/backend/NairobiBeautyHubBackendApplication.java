package com.nbh.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider", auditorAwareRef = "staffAuditor")
@EnableScheduling
public class NairobiBeautyHubBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(NairobiBeautyHubBackendApplication.class, args);
    }
}
