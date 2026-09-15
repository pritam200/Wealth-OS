package com.marketai.scoring.redflag;

/**
 * A disqualifying condition, kept in its own channel rather than scored.
 *
 * <p>Tickertape and Zerodha's Nudge both treat these as disqualifiers rather than as weighted
 * inputs, and that is the right structure. Folding a promoter pledge into a composite lets a
 * cheap valuation average it away — the stock scores well <em>because</em> it is cheap, and it
 * is cheap <em>because</em> of the pledge. A veto cannot be averaged.
 */
public record RedFlag(Type type, String detail, Severity severity) {

    public enum Type {
        /** Exchange Additional Surveillance Measure. */
        ASM,
        /** Graded Surveillance Measure. */
        GSM,
        /** Enhanced Surveillance Measure — micro and small caps. */
        ESM,
        /** Trade-to-trade settlement: no intraday, delivery only. */
        TRADE_TO_TRADE,
        /** Promoters have pledged shares. */
        PROMOTER_PLEDGE,
        /** Insolvency proceedings. */
        INSOLVENCY,
        /** Auditor has qualified the accounts or resigned. */
        AUDIT_QUALIFICATION,
        /** Altman Z or an equivalent puts the company in distress territory. */
        DISTRESS_SCORE,
        /** Circulated as an unsolicited tip. */
        UNSOLICITED_TIP;

        /**
         * Whether this alone should block a recommendation. The surveillance and insolvency
         * flags are exchange-declared facts about tradeability; a pledge is a risk the user may
         * knowingly accept.
         */
        public boolean isBlocking() {
            return this == ASM || this == GSM || this == ESM
                || this == INSOLVENCY || this == TRADE_TO_TRADE
                || this == UNSOLICITED_TIP;
        }
    }

    public enum Severity { INFO, WARNING, BLOCKING }

    public boolean blocks() {
        return severity == Severity.BLOCKING || type.isBlocking();
    }
}
