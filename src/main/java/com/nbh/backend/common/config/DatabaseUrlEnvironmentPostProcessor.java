package com.nbh.backend.common.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Railway (and Render/Heroku) expose Postgres as one {@code DATABASE_URL} of the form
 * {@code postgresql://user:pass@host:port/db}, which JDBC cannot take directly. When
 * {@code DATABASE_URL} is set and {@code DB_URL} is not, this derives the {@code DB_URL},
 * {@code DB_USERNAME} and {@code DB_PASSWORD} values that application.yml already reads.
 * Explicit {@code DB_*} variables always win, so local dev and tests are unaffected.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank() || environment.containsProperty("DB_URL")) {
            return;
        }
        URI uri = URI.create(databaseUrl.trim());
        if (uri.getHost() == null || (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme()))) {
            return;
        }
        Map<String, Object> derived = new HashMap<>();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        derived.put("DB_URL", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            derived.put("DB_USERNAME", URLDecoder.decode(user, StandardCharsets.UTF_8));
            if (colon >= 0) {
                derived.put("DB_PASSWORD", URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
            }
        }
        environment.getPropertySources().addLast(new MapPropertySource("derivedDatabaseUrl", derived));
    }
}
