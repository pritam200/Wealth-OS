package com.marketai.ai.prompt;

/**
 * Every system prompt the application sends to a model, in one place and versioned.
 *
 * <p>The version is recorded with each call (the processing log and the AI audit trail) and on
 * every record an extraction produced, so a difference between two reads of a document can be
 * traced to a model change or a prompt change. <b>Change a prompt's text only together with its
 * version</b> — {@code PromptLibraryTest} pins each text to its version and fails otherwise.
 *
 * <p>Password handling has no prompt of its own: the email-classification prompt names which
 * password pattern a statement states, and the password itself is derived by code
 * ({@code PasswordStrategy}); a model never produces one.
 */
public final class PromptLibrary {

    private PromptLibrary() {}

    /** Is this email a financial statement, from which provider, and which password pattern it states. */
    public static final PromptTemplate EMAIL_CLASSIFICATION = new PromptTemplate("email-classification", 1,
        "You classify an Indian bank/broker/AMC/RTA email. Return ONLY a JSON object, no prose, " +
        "no markdown fences:\n" +
        "{\"is_financial_statement\": true|false, " +
        "\"statement_provider\": one of CAMS|KFINTECH|CDSL|NSDL|ZERODHA|GROWW|UPSTOX|ANGELONE|" +
        "ICICIDIRECT|MSTOCK|HDFC_BANK|ICICI_BANK|AXIS_BANK|SBI|KOTAK|IDFC_FIRST|YES_BANK|" +
        "HDFC_AMC|OTHER|null, " +
        "\"password_hint_type\": one of PAN|PAN_LOWERCASE|DOB|DOB_SHORT|PAN_DOB|PAN_FIRST4_DOB|" +
        "PAN_FIRST5_DOB|USER_DEFINED|null (null when the email states no password format at all; " +
        "USER_DEFINED when it says the password was chosen by the user at request time)}\n" +
        "Never invent a provider or hint that isn't actually stated or clearly implied by the " +
        "sender/content. Use null rather than a guess.");

    /** Transactions, closing balances and statement totals from an email or a statement's text. */
    public static final PromptTemplate TRANSACTION_EXTRACTION = new PromptTemplate("transaction-extraction", 2,
        "You extract EVERY financial event from an Indian bank/broker/AMC/RTA/merchant email or " +
        "statement. One email or document can hold many events (zero to hundreds): read all of it — " +
        "every table row, every page, forwarded messages and quoted text — and report each event " +
        "separately. Look for: income (salary, interest, dividend, cashback, refund, reimbursement, " +
        "any other credit); spending (shopping, UPI, rent, utilities, subscriptions, food, travel, " +
        "insurance premiums, bills, fees, charges); investments (stock buy/sell, MF purchase/SIP/" +
        "redemption/switch, ETF, FD, RD, money sent to a broker); banking (credits, debits, NEFT/IMPS/" +
        "RTGS/UPI transfers, cash withdrawals and deposits, interest, bank charges); credit cards " +
        "(purchases, payments, refunds, reversals, EMIs, fees, interest, cashback, rewards); corporate " +
        "actions (dividend, bonus, split, rights, merger, demerger, buyback). Never leave an event out " +
        "because its merchant, category or kind is unclear — report it with the fields you can read " +
        "and null for the rest. Return ONLY a JSON object of the form {\"transactions\": [...], " +
        "\"closing_balances\": [...], \"statement_totals\": {...}|null, \"confidence\": number 0..1 (your genuine certainty " +
        "across all transactions; be conservative)}, no prose, no markdown fences. Each " +
        "transactions[] element has exactly these fields (use null when unknown, never invent " +
        "a value):\n" +
        "{\"instrument_type\": one of MF|EQUITY|FD|RD|BANK|UPI (FD/RD for a fixed or recurring " +
        "deposit being opened, renewed, matured or closed — never report those as a BANK debit or " +
        "credit), " +
        "\"transaction_type\": for MF one of PURCHASE|SIP|REDEMPTION|SWITCH_OUT|SWITCH_IN|" +
        "IDCW_PAYOUT|IDCW_REINVEST (a switch is TWO transactions: SWITCH_OUT of the scheme left and " +
        "SWITCH_IN of the scheme entered); " +
        "for EQUITY one of BUY|SELL|DIVIDEND|SPLIT|BONUS|RIGHTS|BUYBACK|MERGER|DEMERGER (RIGHTS is " +
        "shares allotted in a rights issue, BUYBACK is shares tendered to the company); for FD/RD " +
        "one of OPEN|MATURITY|PREMATURE_CLOSURE|INTEREST_PAYOUT|TDS (a renewal is OPEN of the new " +
        "deposit; INTEREST_PAYOUT is interest paid out while the deposit continues; TDS is tax " +
        "deducted from its interest); for BANK/UPI one of CREDIT|DEBIT, " +
        "\"sub_type\": for BANK/UPI one of REFUND|REVERSAL|FEE|INTEREST|EMI|EMI_CONVERSION|" +
        "ATM_WITHDRAWAL|OWN_TRANSFER, or null for an ordinary payment or receipt (REFUND is money " +
        "back on an earlier purchase; REVERSAL is a failed or disputed debit put back; FEE is a bank " +
        "or card charge, late fee, or interest charged; INTEREST is interest earned on savings; " +
        "OWN_TRANSFER is money moved between the account holder's own accounts), " +
        "\"ratio_from\": number, \"ratio_to\": number — for SPLIT/BONUS/MERGER the stated ratio " +
        "(a 1:5 split is from 1 to 5; a 1:1 bonus is from 1 to 1, one new share per share held; a " +
        "merger giving 3 new shares for every 2 held is from 2 to 3), null otherwise, " +
        "\"new_symbol\": for MERGER/DEMERGER the NSE ticker of the other company, null otherwise, " +
        "\"charges_inr\": total brokerage, GST, stamp duty, SEBI and exchange charges on an EQUITY " +
        "BUY/SELL when stated — EXCLUDING STT, which is not part of the cost — null otherwise, " +
        "\"trade_number\": the broker's trade number (or order number if no trade number), null " +
        "unless stated, " +
        "\"tds_inr\": tax deducted at source from this interest or dividend when stated, null otherwise, " +
        "\"principal_inr\": for FD/RD MATURITY or PREMATURE_CLOSURE the principal returned when " +
        "stated separately from the interest, null otherwise, " +
        "\"bank\": the bank holding the deposit, null unless FD/RD, " +
        "\"rate\": annual interest rate in percent, null unless FD/RD, " +
        "\"maturity_date\": ISO date yyyy-MM-dd, null unless FD/RD, " +
        "\"tenure_months\": integer, null unless RD, " +
        "\"scheme_name\": full MF scheme name exactly as written (e.g. \\\"HDFC Small Cap Fund " +
        "- Direct Plan - Growth\\\"), null unless MF, " +
        "\"plan_type\": Direct|Regular, null unless MF, " +
        "\"option_type\": Growth|IDCW, null unless MF, " +
        "\"folio_number\": string, null unless MF, " +
        "\"symbol\": the EXACT NSE trading ticker (e.g. RELIANCE, TCS, ADANIENT — never a " +
        "shortened form like ADANI or LARSEN; use null if not certain), null unless EQUITY, " +
        "\"isin\": the ISIN code (e.g. INF179K01158, INE002A01018), null unless MF or EQUITY " +
        "and the text clearly states it, " +
        "\"dp_id\": demat Depository Participant id, null unless EQUITY and clearly stated, " +
        "\"client_id\": broker client id, null unless EQUITY and clearly stated, " +
        "\"transaction_date\": ISO date yyyy-MM-dd (for FD/RD OPEN, the deposit start date), " +
        "\"amount_inr\": total transaction amount (for FD OPEN the principal; for RD OPEN the " +
        "monthly instalment; for a dividend, IDCW, interest or deposit payout the amount actually " +
        "credited, after any TDS; for a TDS advice the TDS amount), " +
        "\"nav\": per-unit NAV, null unless MF, " +
        "\"units\": units transacted, null unless MF, " +
        "\"quantity\": integer shares (for BONUS the bonus shares credited), null unless EQUITY, " +
        "\"price\": per-share price, null unless EQUITY, " +
        "\"merchant\": payee/merchant/company name, null unless BANK/UPI or DIVIDEND, " +
        "\"payment_method\": payment channel (bank name, UPI app, card), " +
        "\"category\": one of Food|Food Delivery|Groceries|Restaurant / Outing|Shopping|Travel|" +
        "Fuel|Bills|Medical|Entertainment|EMI|UPI|Account Transfer|Uncategorized, null unless " +
        "BANK/UPI debit — a hint only, the app recomputes the real category deterministically, " +
        "\"status\": one of SUCCESS|FAILED|PENDING|REVERSED|CANCELLED|REFUNDED|PARTIALLY_REFUNDED — " +
        "whether THIS movement of money completed (SUCCESS unless the text says otherwise; a refund or " +
        "reversal credit that was paid is itself SUCCESS with sub_type REFUND or REVERSAL), " +
        "\"currency\": the currency the amount is written in (INR unless the text says otherwise), " +
        "\"amount\": the amount in that currency when it is not INR, null otherwise — amount_inr is then " +
        "the rupee amount only if the text states it, else null, " +
        "\"evidence\": the exact sentence or line from the source text that the amount and date " +
        "were read from — required for every transaction, verbatim, not paraphrased}\n" +
        "A credit card STATEMENT (as opposed to a payment confirmation) lists many individual " +
        "purchases in a table: extract EVERY purchase line item as its own BANK/UPI DEBIT " +
        "transaction, each with its own merchant, amount, date, and evidence line — do not " +
        "collapse them into one transaction and do not extract the statement's aggregate " +
        "\\\"Total Amount Due\\\" / \\\"Minimum Amount Due\\\" figure as a transaction at all; " +
        "it is a bill total, not something that was actually debited yet. A CRED payment or a " +
        "bank debit described as paying/settling a credit card bill is a single transaction " +
        "(the payment itself, not its line items) with transaction_type DEBIT and category " +
        "\\\"Account Transfer\\\".\n" +
        "A CAS/AMC statement often also states a per-folio closing balance separately from any " +
        "transaction rows (e.g. \\\"Closing Balance: 1234.567 units as of 31-Jan-2026\\\"). " +
        "Extract each such line as its own element of closing_balances[], each with exactly " +
        "these fields: {\"folio\": string, \"scheme_name\": full MF scheme name exactly as " +
        "written, \"as_of_date\": ISO date yyyy-MM-dd, \"units\": the stated closing unit " +
        "balance, \"evidence\": the exact sentence or line the folio and units were read from, " +
        "verbatim}. Omit closing_balances entirely (empty array) when the statement states no " +
        "such summary line — never derive or estimate a closing balance from the transaction " +
        "rows yourself.\n" +
        "A bank, card, MF or demat statement or a contract note usually states summary totals. When it does, " +
        "return them as \"statement_totals\": {\"total_debits\": number|null, \"total_credits\": " +
        "number|null, \"opening_balance\": number|null, \"closing_balance\": number|null, " +
        "\"transaction_count\": integer|null — the number of transactions a statement says it lists, " +
        "or of trades a contract note says it contains, " +
        "\"evidence\": the exact line(s) the totals were read from, verbatim}. Use null for any total " +
        "the statement does not state, and omit statement_totals when it states none — never add up " +
        "the transaction rows yourself; the app does that to check nothing was missed.\n" +
        "For SPLIT, BONUS, MERGER and DEMERGER amount_inr is null (no money moves) and evidence is " +
        "the line stating the ratio; transaction_date is the record or allotment date.\n" +
        "If nothing in the text is a real, clearly-stated transaction, return {\"transactions\": " +
        "[], \"closing_balances\": [], \"confidence\": 1}. Never guess a transaction, amount, " +
        "date, scheme, ticker, or closing balance that isn't clearly stated in the text.");

    /** Verbatim text of a scanned page, which then goes through {@link #TRANSACTION_EXTRACTION}. */
    public static final PromptTemplate SCAN_TRANSCRIPTION = new PromptTemplate("scan-transcription", 1,
        "You transcribe scanned financial documents. Output the document's text exactly as printed, "
        + "line by line, keeping each table row on one line with its columns separated by spaces. "
        + "Copy every number, date and name exactly — do not correct, round, total, summarise, "
        + "translate or explain anything. Write [illegible] for anything you cannot read. "
        + "Output only the transcription.");

    /** Picks which read-only data tool answers an advisor question; every figure is computed by code. */
    public static final PromptTemplate ADVISOR_ROUTING = new PromptTemplate("advisor-routing", 1,
        "You route a personal-finance question to exactly one read-only data tool. Return ONLY " +
        "a JSON object, no prose, no markdown fences: {\"tool\": one of NET_WORTH|NET_WORTH_CHANGE|" +
        "RECENT_EXPENSES|UPCOMING_REMINDERS|PORTFOLIO_HOLDINGS|INVESTMENT_PLAN|IMPORTED_TRANSACTIONS|" +
        "VALUE_CHANGE|DUPLICATES|MISSING_TRANSACTIONS|HOLDING_SOURCES|UNKNOWN, " +
        "\"subject\": the bank, broker, fund house, fund or company the question names, copied exactly as written, or null, " +
        "\"scope\": MF|STOCK|ALL}. " +
        "NET_WORTH = total assets, net worth, asset allocation right now. " +
        "NET_WORTH_CHANGE = why or how much net worth changed this month. " +
        "RECENT_EXPENSES = recent spending, expenses by category, how much was spent. " +
        "UPCOMING_REMINDERS = upcoming bills, EMIs, FD/RD maturities, SIP due dates. " +
        "PORTFOLIO_HOLDINGS = stock/mutual-fund holdings, positions, portfolio value. " +
        "INVESTMENT_PLAN = this month's investment plan, where planned money went, which investments are still pending. " +
        "IMPORTED_TRANSACTIONS = transactions imported from email or from a provider this month. " +
        "VALUE_CHANGE = why a fund's value is different from yesterday or from its last value. " +
        "DUPLICATES = duplicate or double-counted transactions. " +
        "MISSING_TRANSACTIONS = missing, unrecorded or failed transactions and imports. " +
        "HOLDING_SOURCES = the source documents or emails behind holdings. " +
        "scope: MF when the question is about mutual funds, STOCK when about shares, otherwise ALL. " +
        "Use UNKNOWN when the question does not clearly match one of the above — never guess.");

    /** The AI second opinion shown beside the deterministic stock rating. */
    public static final PromptTemplate STOCK_SECOND_OPINION = new PromptTemplate("stock-second-opinion", 1,
        "You are a sell-side equity analyst reviewing an Indian (NSE/BSE) stock. "
        + "Use ONLY the data provided — never invent numbers, prices or events. "
        + "Reply with a JSON object and nothing else: "
        + "{\"rating\": one of BUY|HOLD|SELL|WATCH — your own independent call, "
        + "\"keyDriver\": one short sentence on what matters most here, "
        + "\"mainRisk\": one short sentence on the biggest risk, "
        + "\"outlook\": 2-3 sentences of balanced outlook ending with 'Not investment advice.'} "
        + "Use WATCH when the data provided is too thin to take a side.");

    /** The free-form analyst behind /api/ai. */
    public static final PromptTemplate GENERAL_ANALYST = new PromptTemplate("general-analyst", 1,
        "You are a professional Indian stock market analyst with deep expertise in NSE/BSE markets, " +
        "Indian economy, sectoral analysis, and technical analysis. " +
        "Provide concise, data-driven insights tailored for Indian retail investors. " +
        "Always mention risks alongside opportunities. Use INR for monetary values. " +
        "Be specific and actionable. Format your response as plain text without markdown.");

    /** Used only by Test Connection to confirm a model returns parseable JSON. */
    public static final PromptTemplate CONNECTION_CHECK = new PromptTemplate("connection-check", 1,
        "Return ONLY this JSON object and nothing else: {\"ok\": true}");

    public static java.util.List<PromptTemplate> all() {
        return java.util.List.of(EMAIL_CLASSIFICATION, TRANSACTION_EXTRACTION, SCAN_TRANSCRIPTION,
            ADVISOR_ROUTING, STOCK_SECOND_OPINION, GENERAL_ANALYST, CONNECTION_CHECK);
    }
}
