package com.marketai.document.identity;

import com.marketai.document.identity.ExternalReference.ReferenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceHarvesterTest {

    private final ReferenceHarvester harvester = new ReferenceHarvester();

    @Test
    @DisplayName("a labelled UTR is harvested and normalised")
    void utrIsHarvested() {
        String body = """
            Dear Customer,
            Rs. 50,000.00 has been transferred from your account.
            UTR No: HDFCN52026091200123456
            Beneficiary: SELF
            """;

        assertThat(harvester.harvest(body))
            .extracting(ExternalReference::type, ExternalReference::value)
            .contains(org.assertj.core.groups.Tuple.tuple(
                ReferenceType.UTR, "HDFCN52026091200123456"));
    }

    @Test
    void separatorsAndCaseAreNormalised() {
        assertThat(harvester.harvest("UTR Number: hdfc-n5-2026-0912").getFirst().value())
            .isEqualTo("HDFCN520260912");
    }

    @Test
    @DisplayName("a bare digit run is NOT harvested — a false reference is worse than none")
    void unlabelledNumbersAreIgnored() {
        // Every one of these would be captured by a pattern-only harvester, and tier-1 matching
        // treats a reference as proof — so a false positive merges two different transactions
        // and loses a financial record.
        String body = """
            Your account 912345678901 was debited by Rs 123456.
            Registered mobile 9876543210. Customer ID 445566778899.
            Available balance: 250000.00
            """;

        assertThat(harvester.harvest(body)).isEmpty();
    }

    @Test
    @DisplayName("the more specific label wins over the generic one")
    void labelPriorityIsRespected() {
        String body = "UPI Ref No: 402512345678\nRef No: XYZ987654";

        var refs = harvester.harvest(body);

        assertThat(refs).extracting(ExternalReference::type)
            .contains(ReferenceType.UPI_TXN_ID);
        // strongest() must prefer the rail reference over the issuer-scoped one.
        assertThat(harvester.strongest(body)).get()
            .extracting(ExternalReference::type).isEqualTo(ReferenceType.UPI_TXN_ID);
    }

    @Test
    @DisplayName("both a rail reference and an issuer reference are kept")
    void multipleReferencesAreAllRetained() {
        // A later document may quote either one, so discarding the weaker reference would lose
        // a match we could otherwise have made exactly.
        String body = "UTR: SBIN226091200999\nOrder ID: ORD-55512";

        assertThat(harvester.harvest(body))
            .extracting(ExternalReference::type)
            .contains(ReferenceType.UTR, ReferenceType.ISSUER_REF);
    }

    @ParameterizedTest
    @DisplayName("common Indian label spellings are all recognised")
    @ValueSource(strings = {
        "UTR: ABCD12345678",
        "UTR No. ABCD12345678",
        "UTR Number - ABCD12345678",
        "Unique Transaction Reference: ABCD12345678",
        "UTR No :  ABCD12345678"
    })
    void labelVariantsAreRecognised(String line) {
        assertThat(harvester.harvest(line))
            .extracting(ExternalReference::value).contains("ABCD12345678");
    }

    @Test
    @DisplayName("cheque and issuer references are not treated as globally unique")
    void weakReferencesAreMarkedAsSuch() {
        // A cheque number repeats across accounts and two brokers can both mint order 12345.
        // Matching on these alone would merge unrelated transactions.
        assertThat(ReferenceType.CHEQUE.isGloballyUnique()).isFalse();
        assertThat(ReferenceType.ISSUER_REF.isGloballyUnique()).isFalse();

        assertThat(ReferenceType.UTR.isGloballyUnique()).isTrue();
        assertThat(ReferenceType.RRN.isGloballyUnique()).isTrue();
        assertThat(ReferenceType.UPI_TXN_ID.isGloballyUnique()).isTrue();
        assertThat(ReferenceType.IMPS_REF.isGloballyUnique()).isTrue();
    }

    @Test
    void emptyAndNullInputYieldNothing() {
        assertThat(harvester.harvest(null)).isEmpty();
        assertThat(harvester.harvest("")).isEmpty();
        assertThat(harvester.harvest("   ")).isEmpty();
        assertThat(harvester.strongest(null)).isEmpty();
    }

    @Test
    void shortValuesAreRejected() {
        // Six characters is the floor; anything shorter is too collision-prone to trust as an
        // identity key.
        assertThat(harvester.harvest("UTR: AB12")).isEmpty();
    }

    @Test
    @DisplayName("the same reference quoted twice is de-duplicated")
    void duplicatesAreCollapsed() {
        String body = "UTR: ABCD12345678\nPlease quote UTR No. ABCD12345678 in correspondence.";

        assertThat(harvester.harvest(body))
            .filteredOn(r -> r.type() == ReferenceType.UTR)
            .hasSize(1);
    }

    @Test
    void theOriginatingLabelIsRetainedForTraceability() {
        var ref = harvester.harvest("Retrieval Reference Number: 402512345678").getFirst();

        assertThat(ref.type()).isEqualTo(ReferenceType.RRN);
        assertThat(ref.label()).containsIgnoringCase("Retrieval Reference");
    }
}
