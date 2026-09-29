package com.marketai.ai.llm;

/** The providers behind {@link LlmProvider}. {@link #cloud()} decides what Local AI mode forbids. */
public enum LlmProviderId {
    GEMINI("gemini", "Gemini", true),
    OLLAMA("ollama", "Local / Ollama", false);

    private final String key;
    private final String label;
    private final boolean cloud;

    LlmProviderId(String key, String label, boolean cloud) {
        this.key = key;
        this.label = label;
        this.cloud = cloud;
    }

    /** Lower-case id as recorded on completions and in the audit trail ("gemini", "ollama"). */
    public String key() { return key; }

    public String label() { return label; }

    public boolean cloud() { return cloud; }

    public LlmProviderId other() { return this == GEMINI ? OLLAMA : GEMINI; }

    /** Accepts "gemini"/"GEMINI"; null for blank, "none" or anything unknown. */
    public static LlmProviderId parse(String s) {
        if (s == null) return null;
        for (LlmProviderId p : values()) if (p.key.equalsIgnoreCase(s.trim()) || p.name().equalsIgnoreCase(s.trim())) return p;
        return null;
    }
}
