package com.marketai.advisor.dto;

/**
 * The fixed, read-only set of existing-data queries the advisor chat may ground an answer in.
 * The LLM only ever picks ONE of these names — it never sees or invents the underlying numbers,
 * which are always fetched fresh from the real services below.
 */
public enum AdvisorTool {
    NET_WORTH,
    RECENT_EXPENSES,
    UPCOMING_REMINDERS,
    PORTFOLIO_HOLDINGS,
    /** Why net worth moved since the start of the month: snapshots against recorded flows. */
    NET_WORTH_CHANGE,
    /** This month's investment plan: planned, transferred, invested, still pending. */
    INVESTMENT_PLAN,
    /** Records imported from email this month, optionally for one provider. */
    IMPORTED_TRANSACTIONS,
    /** What moved a fund's value between its two latest NAVs. */
    VALUE_CHANGE,
    /** Records the data audit finds duplicated. */
    DUPLICATES,
    /** Records that imply something missing, open reconciliation issues, failed emails. */
    MISSING_TRANSACTIONS,
    /** The source document behind every trade that makes up the holdings. */
    HOLDING_SOURCES,
    UNKNOWN
}
