package com.marketai.document.entity;

/** Where a document came from. Determines which password rules and extractors apply. */
public enum DocumentSource {
    /** Body or attachment of a synced Gmail message. */
    GMAIL,
    /** A Consolidated Account Statement the user requested be mailed to themselves.
     *  Structured and high-trust — the channel Jupiter narrows to exclusively. */
    CAS_MAILBACK,
    /** Uploaded directly by the user. */
    MANUAL_UPLOAD
}
