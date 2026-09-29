package com.marketai.common.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where a ledger record came from, so any figure can be traced back to its source document
 * ("View source"). All null for a record entered by hand before provenance existed; a manual
 * entry now says {@link #MANUAL}.
 */
@Embeddable
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Provenance {

    public static final String MANUAL = "MANUAL";
    public static final String EMAIL_LLM = "EMAIL_LLM";
    public static final String PDF_LLM = "PDF_LLM";
    /** Read from a scanned page or image, transcribed by a vision model first. */
    public static final String OCR_LLM = "OCR_LLM";
    /** Read from a text attachment: CSV, TXT or HTML. */
    public static final String ATTACHMENT_LLM = "ATTACHMENT_LLM";

    /** Gmail message the record was read from. */
    @Column(name = "source_email_id", length = 100)
    private String sourceEmailId;

    /** Event fingerprint of the line it was booked from — the key into the import log, which
     *  holds the attachment, document hash and extraction details. */
    @Column(name = "source_fingerprint", length = 64)
    private String sourceFingerprint;

    /** MANUAL, EMAIL_LLM, PDF_LLM, OCR_LLM or ATTACHMENT_LLM. */
    @Column(name = "extraction_method", length = 30)
    private String extractionMethod;

    /** Model confidence for an extracted record; null for manual entries. */
    @Column(name = "extraction_confidence")
    private Double extractionConfidence;

    /** The issuer's own reference for the event — a broker's trade or order number, a bank's
     *  UTR. Two otherwise identical trades with different trade numbers are two trades. */
    @Column(name = "source_reference", length = 60)
    private String sourceReference;

    /** Shared by the legs of one event — the redemption and purchase sides of a fund switch.
     *  A leg carrying it moved money between holdings, not out to a bank. */
    @Column(name = "link_group", length = 100)
    private String linkGroup;

    public static Provenance manual() {
        return Provenance.builder().extractionMethod(MANUAL).build();
    }
}
