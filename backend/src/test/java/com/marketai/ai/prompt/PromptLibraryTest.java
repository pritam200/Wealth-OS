package com.marketai.ai.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every prompt's text to its version. The version is stored with each extraction, so a
 * text change without a version bump would make two different prompts look identical in the
 * record. If this fails after an intended change: bump the template's version in PromptLibrary,
 * then update its tag and hash here.
 */
class PromptLibraryTest {

    private static final Map<String, String> PINNED = Map.of(
        "email-classification-v1", "61fb7926c6246b79",
        "transaction-extraction-v2", "ad9fa80e4e092414",
        "scan-transcription-v1", "fbbe1913031b0286",
        "advisor-routing-v1", "8601dcb7de5c58af",
        "stock-second-opinion-v1", "f792aa892c715237",
        "general-analyst-v1", "03fa0e26b017a00a",
        "connection-check-v1", "eb9be5e2fc3fa64d");

    @Test
    @DisplayName("a prompt's text never changes without its version")
    void textIsPinnedToVersion() {
        Map<String, String> actual = PromptLibrary.all().stream()
            .collect(Collectors.toMap(PromptTemplate::tag, t -> sha(t.system())));
        assertThat(actual).isEqualTo(PINNED);
    }

    @Test
    void idsAreUnique() {
        assertThat(PromptLibrary.all().stream().map(PromptTemplate::id).distinct().count())
            .isEqualTo(PromptLibrary.all().size());
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
