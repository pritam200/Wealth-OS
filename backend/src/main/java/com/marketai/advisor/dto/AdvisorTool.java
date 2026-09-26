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
    UNKNOWN
}
