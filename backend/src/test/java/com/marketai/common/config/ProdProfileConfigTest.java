package com.marketai.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the real application.yml under the prod profile (no beans, no database) and checks the
 * settings a hosted deployment depends on are actually present. The shared settings used to live
 * in a dev-only document, so prod started without them.
 */
class ProdProfileConfigTest {

    @Configuration
    static class NoBeans {}

    private ConfigurableApplicationContext ctx;

    @AfterEach
    void close() {
        if (ctx != null) ctx.close();
    }

    private Environment prod(Map<String, Object> env) {
        ctx = new SpringApplicationBuilder(NoBeans.class)
            .web(WebApplicationType.NONE)
            .profiles("prod")
            .properties(env)
            .run();
        return ctx.getEnvironment();
    }

    private static Map<String, Object> deployEnv() {
        Map<String, Object> env = new HashMap<>();
        // The main file explicitly: src/test/resources has its own application.yml, which would
        // otherwise shadow the one being deployed.
        env.put("spring.config.location", "file:src/main/resources/application.yml");
        env.put("DB_HOST", "postgres");
        env.put("DB_NAME", "marketai_db");
        env.put("DB_USER", "marketai");
        env.put("DB_PASSWORD", "x");
        env.put("REDIS_PASSWORD", "x");
        env.put("JWT_SECRET", "x");
        env.put("PDF_PASSWORD_ENC_KEY", "x");
        env.put("FRONTEND_URL", "https://family.duckdns.org");
        env.put("GMAIL_REDIRECT_URI", "https://family.duckdns.org/api/gmail/callback");
        return env;
    }

    @Test
    void sharedSettingsReachTheProdProfile() {
        Environment env = prod(deployEnv());

        // Startup validates these sum to 100 — missing entirely meant prod could not start.
        int sum = 0;
        for (String k : new String[]{"technical", "momentum", "valuation", "sentiment"}) {
            sum += env.getRequiredProperty("app.recommendation.weights." + k, Integer.class);
        }
        assertThat(sum).isEqualTo(100);
        assertThat(env.getProperty("gmail.redirect-uri")).isEqualTo("https://family.duckdns.org/api/gmail/callback");
        assertThat(env.getProperty("gmail.frontend-url")).isEqualTo("https://family.duckdns.org");
        assertThat(env.getProperty("app.sync.worker.concurrency")).isNotBlank();
        assertThat(env.getProperty("app.llm.email-provider")).isEqualTo("gemini");
        assertThat(env.getProperty("app.otp.expiry-minutes")).isNotBlank();
    }

    @Test
    void prodIsLockedDown() {
        Environment env = prod(deployEnv());

        assertThat(env.getProperty("app.signup.open-when-empty", Boolean.class)).isFalse();
        assertThat(env.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://postgres:5432/marketai_db");
        assertThat(env.getProperty("app.cors.allowed-origins")).isEqualTo("https://family.duckdns.org");
    }

    @Test
    void prodRefusesToRunOnDevelopmentDefaults() {
        Map<String, Object> env = deployEnv();
        env.remove("DB_PASSWORD");
        Environment e = prod(env);
        // Resolving it fails instead of silently falling back to the local "marketai_pass".
        assertThatThrownBy(() -> e.getProperty("spring.datasource.password"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
