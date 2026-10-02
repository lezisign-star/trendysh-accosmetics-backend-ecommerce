package com.nbh.backend.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "integration_setting")
public class IntegrationSetting {

    @Id
    @Column(name = "setting_key", length = 60)
    private String key;

    @Column(name = "value_encrypted", nullable = false, columnDefinition = "TEXT")
    private String valueEncrypted;

    @Column(name = "updated_by_user_id")
    private UUID updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
