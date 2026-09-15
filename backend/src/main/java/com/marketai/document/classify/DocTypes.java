package com.marketai.document.classify;

/** Canonical document types. String constants rather than an enum so an unrecognised type from
 *  a model fallback can still be recorded rather than forced into a bucket it does not fit. */
public final class DocTypes {
    public static final String CONTRACT_NOTE   = "CONTRACT_NOTE";
    public static final String TRADE_CONFIRM   = "TRADE_CONFIRM";
    public static final String MF_TRANSACTION  = "MF_TRANSACTION";
    public static final String CAS_STATEMENT   = "CAS_STATEMENT";
    public static final String BANK_STATEMENT  = "BANK_STATEMENT";
    public static final String FD_RD_ADVICE    = "FD_RD_ADVICE";
    public static final String CARD_STATEMENT  = "CARD_STATEMENT";
    public static final String DIVIDEND_ADVICE = "DIVIDEND_ADVICE";
    public static final String UNKNOWN         = "UNKNOWN";

    private DocTypes() {}
}
