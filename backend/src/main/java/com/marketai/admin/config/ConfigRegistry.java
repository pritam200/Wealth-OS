package com.marketai.admin.config;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The settings the console knows about. A name not listed here can be neither shown nor rotated. */
@Component
public class ConfigRegistry {

    private final List<ConfigItem> items = List.of(
        new ConfigItem("APP_ENVIRONMENT", "Application", false, "DEV, STAGE or PRODUCTION"),
        new ConfigItem("SPRING_PROFILES_ACTIVE", "Application", false, "Active Spring profiles"),
        new ConfigItem("FRONTEND_URL", "Application", false, "Public address of the web app"),
        new ConfigItem("DB_HOST", "Application", false, "Database host"),
        new ConfigItem("DB_PORT", "Application", false, "Database port"),
        new ConfigItem("LLM_PROVIDER", "Application", false, "Which model reads documents"),
        new ConfigItem("GMAIL_CLIENT_ID", "Integrations", false, "Google OAuth client id"),
        new ConfigItem("MAIL_HOST", "Integrations", false, "SMTP server for sign-up codes"),
        new ConfigItem("MAIL_USERNAME", "Integrations", false, "SMTP user"),
        new ConfigItem("MAIL_FROM", "Integrations", false, "Sender shown on emails"),
        new ConfigItem("JWT_SECRET", "Secrets", true, "Signs login tokens"),
        new ConfigItem("DB_PASSWORD", "Secrets", true, "Database password"),
        new ConfigItem("GEMINI_API_KEY", "Secrets", true, "Gemini API key"),
        new ConfigItem("GMAIL_CLIENT_SECRET", "Secrets", true, "Google OAuth client secret"),
        new ConfigItem("MAIL_PASSWORD", "Secrets", true, "SMTP password / app password"),
        new ConfigItem("PDF_PASSWORD_ENC_KEY", "Secrets", true, "Encrypts saved statement passwords"),
        new ConfigItem("REDIS_PASSWORD", "Secrets", true, "Redis password"),
        new ConfigItem("DUCKDNS_TOKEN", "Secrets", true, "Dynamic DNS token")
    );

    /** Feature flags the console may switch. Code reads them through {@code FeatureFlags}. */
    private final List<String> flags = List.of("maintenance_banner", "new_signups", "email_ingestion", "ai_advisor");

    public List<ConfigItem> items() { return items; }
    public List<String> flags() { return flags; }
    public Optional<ConfigItem> find(String name) { return items.stream().filter(i -> i.name().equals(name)).findFirst(); }
}
