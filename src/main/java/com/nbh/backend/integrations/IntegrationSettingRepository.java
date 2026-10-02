package com.nbh.backend.integrations;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationSettingRepository extends JpaRepository<IntegrationSetting, String> {
}
