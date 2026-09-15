package com.marketai.common.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualityTest {

    @Test
    @DisplayName("wire() emits exactly the strings the API already returns")
    void wireFormatIsUnchanged() {
        // These three literals were the previous representation, produced and compared as bare
        // strings across six services. Anything that changes them is an API break for clients
        // and a silent mismatch against values already stored.
        assertThat(DataQuality.FULL.wire()).isEqualTo("FULL");
        assertThat(DataQuality.PARTIAL.wire()).isEqualTo("PARTIAL");
        assertThat(DataQuality.INSUFFICIENT.wire()).isEqualTo("INSUFFICIENT");
    }

    @Test
    void roundTripsThroughTheWireFormat() {
        for (DataQuality q : DataQuality.values()) {
            assertThat(DataQuality.of(q.wire())).isEqualTo(q);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "Full", "  PARTIAL  ", "insufficient"})
    @DisplayName("parsing tolerates case and surrounding whitespace")
    void parsingIsLenientAboutFormatting(String raw) {
        assertThat(DataQuality.of(raw)).isNotNull();
        assertThat(DataQuality.of(raw).wire()).isEqualTo(raw.trim().toUpperCase());
    }

    @Test
    @DisplayName("null and unrecognised values degrade to INSUFFICIENT, never to FULL")
    void unknownValuesAreTreatedAsUnusable() {
        // The direction of this default is the whole point. An unrecognised marker means we do
        // not know what is behind the number; reading that as FULL would let a typo or a schema
        // drift silently re-enable the guessing these guards exist to prevent.
        assertThat(DataQuality.of(null)).isEqualTo(DataQuality.INSUFFICIENT);
        assertThat(DataQuality.of("")).isEqualTo(DataQuality.INSUFFICIENT);
        assertThat(DataQuality.of("COMPLETE")).isEqualTo(DataQuality.INSUFFICIENT);
        assertThat(DataQuality.of("FULLY")).isEqualTo(DataQuality.INSUFFICIENT);
    }

    @Test
    void usabilityAndCaveatsAreDistinctQuestions() {
        assertThat(DataQuality.FULL.isUsable()).isTrue();
        assertThat(DataQuality.FULL.needsCaveat()).isFalse();

        // PARTIAL is shown to the user — but never without saying what is missing.
        assertThat(DataQuality.PARTIAL.isUsable()).isTrue();
        assertThat(DataQuality.PARTIAL.needsCaveat()).isTrue();

        assertThat(DataQuality.INSUFFICIENT.isUsable()).isFalse();
        assertThat(DataQuality.INSUFFICIENT.needsCaveat()).isFalse();
    }
}
