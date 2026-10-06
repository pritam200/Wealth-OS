package com.marketai.admin.config;

/** Where a rotated secret goes: your secret manager, never this application's database or logs. */
public interface SecretStore {
    boolean available();
    /** Stores {@code value} under {@code name}. Implementations must not log or retain the value. */
    void put(String name, String value);
}
