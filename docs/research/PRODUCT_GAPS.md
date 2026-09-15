# Product Gap Matrix

**"Wealth OS Today"** is verified against the code as of this audit — file:line cited, not
assumed. **"Competitors"** is sourced from [`COMPETITIVE_RESEARCH.md`](COMPETITIVE_RESEARCH.md).

## Priority definitions (from the spec)

| | Scope |
|---|---|
| **P0** | Financial truth · Gmail ingestion · dedup · reconciliation · portfolio + net-worth correctness |
| **P1** | Investment + MF + card intelligence · portfolio risk · Action Center |
| **P2** | Advanced AI · tax · scenario · goals |
| **P3** | Nice-to-have |

A gap is **P0 only if getting it wrong shows the user a wrong number.** Several attractive
features below are deliberately P1/P2 despite being differentiators, because they are additive
rather than corrective.

---

## P0 — Financial truth

| Area | Wealth OS Today | Competitors | Gap | Priority |
|---|---|---|---|---|
| **Ledger as single writer** | ✅ **Intact.** `Holding.quantity`/`averageCost` written **only** at `PortfolioService.java:258-259` inside `recomputeFromLedger`. Verified: no other writer exists | Zerodha Console contradicts itself between two XIRR surfaces | **None — this is our strongest asset.** Protect it with a test that fails if a second writer appears | **P0 (guard)** |
| **Net-worth attribution** | ⚠️ `NetWorthSnapshot` **does** store a dated series (`snapshotDate`, `totalAssets`, `netWorth`) via `NetWorthService.record()`. What's absent is **decomposition** — no contribution/withdrawal/revaluation fields | **Nobody ships it.** Modified Dietz / TWR / MWRR are standard but unused in Indian retail | "Your net worth rose ₹X: ₹A saved, ₹B market, ₹C spent." **The hard part — transfer-pair detection — is already solved by `LedgerTransferService`** | **P0** |
| **Return-metric correctness** | ⚠️ `XirrCalculator` exists; one metric regardless of horizon; no benchmark-XIRR | Varsity/Kuvera publish a horizon→metric decision table; Zerodha **suppresses** XIRR under 1yr | Wrong metric for the horizon is a wrong number. Also: **switches must be paired outflow/inflow or they double-count**; XIRR can have multiple roots on SIP+STP chains | **P0** |
| **Refuse-to-answer** | ⚠️ Partial — `SignalEngine.java:275` sets `limitedBy("NOT_SIZED")`, but no general mechanism | VR publishes **5 rating exclusions**; Zerodha shows "–" rather than a misleading XIRR | No first-class `DataSufficiency` type. Every computed number should be able to decline | **P0** |
| **Reconciliation coverage** | ⚠️ `ReconciliationService.checkAll` covers **3 of ~17** listed checks | Industry standard: matched / unmatched / partially-matched / typed exceptions | 14 checks missing; no exception typing; no partial-match concept | **P0** |
| **Economic identity (cross-source dedup)** | ⚠️ `TransactionFingerprinter` hashes financial content, correctly excluding message id — **survives forwards** | Plaid models restatement via `pending_transaction_id` and **admits matching sometimes fails** | Same trade via contract-note PDF **and** broker email has different bytes → content hash can't match. Need composite key + tolerance, and **UTR/RRN harvesting** to make it exact | **P0** |
| **Source traceability** | ❌ No grounded source spans. Numbers can be traced to an *email*, not to a *span within it* | Nobody does this either | "EVERY FINANCIAL NUMBER MUST BE TRACEABLE" is not fully satisfied by email-level provenance | **P0** |
| **Extraction confidence** | ⚠️ Flat 0.85 gate on model-reported confidence | Research: **logprobs give only 0.705 ROC AUC** on DocILE | We gate on the signal shown to be weak. Need arithmetic validation + parser/LLM agreement | **P0** |
| **Ingestion status model** | ⚠️ `ProcessedEmail` + `PendingPdf` + `EmailReviewItem` cover the states informally | — | Spec requires 10 explicit statuses (DISCOVERED…REQUIRES_REVIEW) as one observable model | **P0** |
| **Review queue** | ✅ `EmailReviewItem` + `ReviewStatus` + decision endpoint | **No evidence any Indian app offers one** | **None — we're ahead.** Surface it as a feature, not plumbing | **P0 (done)** |
| **AMFI feed** | ✅ Fixed — binds columns by header name | — | **Action:** point at `portal.amfiindia.com` directly rather than relying on the 302 from `www.` | **P0 (small)** |

## P1 — Intelligence

| Area | Wealth OS Today | Competitors | Gap | Priority |
|---|---|---|---|---|
| **Stock factor model** | ❌ **Wrong.** `AnalystService.java:142` = tech 30 + mom 20 + val 20 + **sentiment 30**. No quality factor, no growth factor | VR: Quality 25 / Growth 20 / **Valuation 35** / Momentum 20, **sentiment not used at all** | Missing the two factors that most determine whether a business is worth owning; 30% on the softest signal | **P1 (top)** |
| **Factor multicollinearity** | ❌ Momentum-family indicators pairwise **ρ>0.9** → momentum effectively **~4:1 over-weighted** | Measured: HML–CMA 0.68 drives FF's own redundancy finding; RMW/CMA/UMD are the near-orthogonal trio | Naive per-indicator averaging double-counts. **And accruals recur in Piotroski, Beneish and QMJ — triple-counted unless consciously slotted once** | **P1** |
| **Sector-aware scoring** | ❌ One uniform formula for every stock | **VR and Simply Wall St independently** use a separate ratio set for banks/financials | Applying a generic formula to a bank is simply wrong — ROA/NIM/provision coverage/CAR/SGR gap instead | **P1** |
| **Rating eligibility** | ❌ Scores everything | VR excludes: untraded last month, <3y history, negative net worth, accumulated losses, bottom 1% mcap | No "refuse to rate" path | **P1** |
| **Red flags** | ❌ Not modelled | Tickertape scores them as a **separate channel**; Zerodha Nudge fires them pre-trade | ASM/GSM/pledge/default-probability should **veto**, never contribute a weighted score | **P1** |
| **Explainable thesis** | ⚠️ `Factor` list with notes exists | VR separates free quant rating from paid **Stock Advisor** — they don't pretend the score is a thesis | Need the same separation: decomposed factors = screen; thesis = a different artifact | **P1** |
| **Position sizing** | ❌ `SignalEngine.java:275` deliberately `suggestedAmount(null)`, `limitedBy("NOT_SIZED")` | — | Honest placeholder, but the spec wants sizing first-class | **P1** |
| **Uncertainty-scaled conviction** | ❌ Not modelled | Morningstar: 5-star discount **20/30/40/50/75%** by uncertainty tier | Margin of safety should scale with uncertainty. **Drive it off data quality we can measure**, not analyst judgement we lack | **P1** |
| **MF overlap** | ❌ Not implemented. `PortfolioContextService.java:227` correctly documents why; `mf/entity/` contains only `MfNavHistory.java` | Advisorkhoj (counts, no methodology), Tickertape (MF-only) | **Feasible: 3–6 weeks + 1–3 days/month maintenance.** The comment stays accurate until built | **P1** |
| **Direct stocks ↔ MF overlap** | ❌ | **Nobody** | Unoccupied, and it is exactly our user's situation | **P1** |
| **Card: cap counters** | ❌ `CardRewardRule.monthlyCapAmount` exists but **no `UtilizationCounter`** | FinArt ranks by ₹ after caps | **Four counters on four clocks.** Without them the ranking is wrong whenever a cap is near | **P1** |
| **Card: MCC** | ❌ **Zero MCC files.** `SpendAggregator` infers category from merchant name, honestly marked `inferred=true` | FinArt chose category-level deliberately; ccreward uses MCC | Honest today. MCC would improve precision but **FinArt's reasoning — issuers rarely publish the map — applies to us too** | **P1** |
| **Card: UPI/RuPay gate** | ❌ Not modelled | Nobody models it well either | **Only RuPay works on UPI; P2P/C2C/P2PM/MF/IPO blocked.** Recommending a Visa card for a UPI rail is a correctness bug | **P1** |
| **Card: exclusions** | ❌ Zero exclusion files | Kotak publishes **five lists for five purposes** | One exclusion list per card is **the most common modelling error**. Need `EligibleSpendSet` keyed by purpose | **P1** |
| **Card: point valuation** | ⚠️ Single rate | Most tools use best-case transfer value as headline | Axis runs two currencies with a **~5× gap**. Need a vector by redemption route | **P1** |
| **Portfolio concentration** | ⚠️ `AssetAllocationRing` displays, doesn't alert | Zerodha's new 50% single-stock/sector nudge | Needs **look-through** — direct + MF + smallcase combined | **P1** |
| **Risk metrics** | ❌ None at portfolio level | VR publishes 15; Kuvera/INDmoney publish none | **Lead with max drawdown + downside capture.** Alpha/beta/R² on 36 observations are uninformative | **P1** |
| **Rebalancing triggers** | ❌ Display only | Kuvera: 5% band, "deviation beats time-based" | Vanguard supports a **200/175bp trigger/destination** rule | **P1** |
| **Benchmarks** | ❌ No benchmark model | SEBI two-tier TRI; AMFI publishes the tier-1 mapping table | **Three peer-group definitions exist and disagree** for flexicap/multicap/BAF. Store all three | **P1** |
| **Action Center** | ✅ `ActionItem` + status + endpoints | — | Present; needs the new signal contract wired in | **P1 (extend)** |
| **Data health** | ⚠️ 3 of ~17 checks | — | Same gap as reconciliation, user-facing | **P1** |

## P2 — Tax, scenario, goals

| Area | Wealth OS Today | Competitors | Gap | Priority |
|---|---|---|---|---|
| **Capital-gains engine** | ⚠️ `tax/service/TaxService.java` exists and is **already on the correct rates** — 12.5% LTCG above ₹1,25,000, 20% STCG, applied to the **FY aggregate**. But it estimates a *range* from `Income` rows grouped by source string, with no lot-level LT/ST split. Honestly labelled "an estimate range, not tax advice" | Zerodha TLH report; Kuvera Tax Harvesting (**Kuvera's page still shows the stale ₹1 lakh**) | Needs **lot-level** LT/ST classification, grandfathering, exit load, surcharge/cess. The rate constants and per-FY aggregation are already right — **do not "fix" them** | **P2 (correctness-critical)** |
| **Statutory renumbering** | ❌ | — | **Income-tax Act 2025 replaces the 1961 Act from 1 Apr 2026: 111A→196, 112A→198.** Rates unchanged; **store section refs as data, not literals** | **P2** |
| **Grandfathering / surcharge / cess** | ❌ | — | Cost = max(actual, FMV 31 Jan 2018), capped at sale price. Surcharge capped 15% + 4% cess — **a bare 12.5% is wrong for high earners** | **P2** |
| **Specified-MF classifier** | ❌ | — | **The most error-prone rule in the set** — definition changed from "≤35% equity" to ">65% in debt/MMI" from FY2025-26. **Verify against the Finance (No.2) Act 2024 before coding** | **P2** |
| **Exit load** | ❌ | — | **Not standardized** — per-SID, SEBI-capped at 2%, credited back to the scheme. Store per scheme; apply **FIFO at unit-lot level** | **P2** |
| **Tax-gain harvesting** | ❌ | Kuvera: ₹15,625/yr | **The Indian play inverts the US default** — realise LTCG up to ₹1.25L tax-free and rebuy. No wash-sale rule makes it viable (**verify independently**) | **P2** |
| **Deferral prompt** | ❌ | **Nobody ships it** | "Wait N days and this lot turns long-term / exits the load window, saving ₹X" — **the highest-value output of the whole tax feature** | **P2** |
| **Tax-aware rebalancing** | ❌ | **Nobody in India** | Unoccupied | **P2** |
| **FD/RD lifecycle** | ✅ Implemented + tested | — | Present | — |
| **MF profit-booking engine** | ❌ | — | Depends on the tax engine landing first | **P2** |

## P3 — Nice-to-have

| Area | Wealth OS Today | Competitors | Gap | Priority |
|---|---|---|---|---|
| **smallcase overlap** | ❌ | **Verified absent from smallcase, structurally unlikely to appear** | Constituents are public via rebalance CSVs. Real differentiator, but only if the user holds smallcases | **P3** |
| **Card-terms changelog** | ❌ | TechnoFino's forum is the **only** structured change-tracking in the market | Public, dated, provenance-stamped changelog = cheap trust moat | **P3** |
| **Investor-return gap** | ❌ | Morningstar *Mind the Gap* | "Your timing cost you X%" — behavioural insight | **P3** |
| **Forward P/E, PEG** | ❌ | Tickertape, Trendlyne (licensed) | **REJECTED for v1** — not obtainable free. Shipping it would mean fabricating or scraping stale estimates | **Rejected** |
| **Account Aggregator** | ❌ | Jupiter (FIU via Finvu) | **REJECTED — retracted from the roadmap.** Regulated-entity gate, ₹5–25 lakh, cards excluded entirely | **Rejected** |
| **SMS parsing** | ❌ | axio, CheQ (outsourced) | **REJECTED** — Google restricts to default handlers; dead end for a new entrant | **Rejected** |

---

## What changed in our own recommendations

Recorded so the reversals are explicit rather than silent.

| Earlier position | Now | Why |
|---|---|---|
| Account Aggregator is the highest-value roadmap item | **Retracted** | FIU requires a regulated entity, ₹5–25 lakh first-year, no hobbyist tier — **and credit cards are excluded from AA entirely** |
| — | **Dhan is the highest-value data integration** | Free API with account, **daily history back to inception**. Kite charges ₹500/mo for the same data |
| MF overlap may be infeasible | **Feasible — 3–6 weeks** | SEBI mandates free monthly disclosure. Cost is ETL + scheme-master, not access |
| MF Central/CAMS could supply user holdings | **Closed** | **AMFI directed MF Central to stop sharing investor data with third-party apps (Sept 2025).** CAS-first is now the only viable path — which is what we already built |
| Zerodha rates stocks | **Wrong** | Zerodha rates nothing. That's **Tickertape**, owned by smallcase; Rainmatter is an *investor* in smallcase, not its owner |
