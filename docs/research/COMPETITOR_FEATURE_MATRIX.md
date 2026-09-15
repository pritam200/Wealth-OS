# Competitor Feature Matrix

Derived from [`COMPETITIVE_RESEARCH.md`](COMPETITIVE_RESEARCH.md). Every row's "How it works"
is sourced there; this document is the compressed, actionable view.

**Wealth OS equivalent** column uses the documentation tags:
`[IMPLEMENTED]` · `[PARTIALLY IMPLEMENTED]` · `[PLANNED / NOT IMPLEMENTED]` · `[NOT BUILT]`
— and cites the actual file where one exists, verified against the code, not assumed.

---

## 1. Aggregation, net worth & performance

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **Zerodha Console** | Portfolio XIRR | XIRR on **current holdings only**; explicitly excludes historical buy/sell. Separate equity-holdings XIRR page covers all trades since FY2017 with corporate-action adjustment — **the two surfaces disagree** | Trade ledger with dates + current MV | `[IMPLEMENTED]` `portfolio/util/XirrCalculator.java` | **One XIRR, one definition, stated on the surface.** Zerodha's own product contradicts itself; we have a single ledger so we have no excuse |
| **Zerodha Console** | Suppress XIRR under 1 year | Shows "–" with "XIRR would be greater than the actual percentage" | Holding age distribution | `[PARTIALLY IMPLEMENTED]` — `SignalEngine` has `limitedBy("NOT_SIZED")` but no return-metric gating | **Generalise refuse-to-answer into a first-class `DataSufficiency` result type** across every computed number |
| Varsity / Kuvera | Metric selection by horizon | <1yr → absolute; >1yr lumpsum → CAGR; >1yr SIP → XIRR | Flow schedule | `[NOT BUILT]` — single metric regardless of horizon | **Auto-select and label which metric is shown and why** |
| **INDmoney** | XIRR vs Nifty 50 | Portfolio XIRR displayed beside index return | Benchmark price series | `[NOT BUILT]` | **Compute benchmark XIRR under the user's own cash-flow schedule.** Their comparison is apples-to-oranges; ours would be correct |
| **Morningstar** | Investor-return gap | Dollar-weighted vs time-weighted; published gap 7.0% vs 8.2% p.a. | Both return series | `[NOT BUILT]` | Show the gap as a *behavioural* insight: "your timing cost you X%" |
| **Value Research** | Adjusted-NAV returns | "NAV adjusted for dividends and splits" | Corporate-action history | `[PARTIALLY IMPLEMENTED]` — `MfNavHistory` stores raw NAV | Reinvest IDCW into the return series rather than dropping it |
| **Jupiter** | AA as source of truth | FIU via Finvu; bank/FD/RD/transactions | RBI FIU licence | `[NOT BUILT — BLOCKED]` | **Not available to us** (§1.4). CAS + Gmail instead |
| *nobody* | **Net-worth attribution waterfall** | — | Transfer-pair detection + Modified Dietz | `[PARTIALLY IMPLEMENTED]` — `ledger/service/LedgerTransferService.java` solves the hard half | **The largest unoccupied space found.** We already have the blocker solved |

## 2. Stock scoring & research

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **Value Research** | Stock Rating | Quality 25 / Growth 20 / **Valuation 35** / Momentum 20; percentile 5-star curve (10/22.5/35/22.5/10) | 10y statements, prices | `[PARTIALLY IMPLEMENTED]` `analyst/AnalystService.java:142-143` — tech 30 / mom 20 / val 20 / **sentiment 30** | **Add Quality + Growth (we have neither); drop sentiment from the rating.** VR doesn't use sentiment as a rating input at all |
| **Value Research** | Separate bank/NBFC ratio set | ROA, NIM, provision coverage, CAR, **SGR gap** (ROE×(1−payout) vs advances growth); **PE→PE-to-SGR** | Bank-specific line items | `[NOT BUILT]` — one uniform formula for every stock | **Sector-aware scoring.** Applying a generic formula to a bank is simply wrong, and two independent sources (VR, Simply Wall St) converge on this |
| **Value Research** | Published rating exclusions | Untraded last month; <3y history; negative net worth; accumulated losses; bottom 1% mcap → **refuse to rate** | Listing + statement history | `[NOT BUILT]` | Adopt verbatim as a `RatingEligibility` gate |
| **Tickertape** | Scorecard (6 dims) | Performance / Valuation / Growth / Profitability scored 0–10; Entry point; **Red flags as a separate channel** | Fundamentals + prices | `[PARTIALLY IMPLEMENTED]` — composite only, no per-factor surfacing | **Publish per-factor sub-scores free.** Tickertape paywalls the numbers behind Pro; VR paywalls Stock Advisor |
| **Tickertape / Zerodha Nudge** | Red flags not scored | ASM, GSM, pledge, default probability, unsolicited SMS → **disqualifier, not a factor** | Exchange surveillance lists | `[NOT BUILT]` | Copy the *architecture*: a red-flag channel that can veto, never a weighted contribution |
| **Simply Wall St** | Snowflake: 6 **binary** checks × 5 axes | Pass/fail, 0–6 per axis; separate 6-check set for financial institutions; gating checks disqualify an axis | Statements + estimates | `[NOT BUILT]` | **Binary checks are auditable in a way a z-score composite never is.** "Failed 4 of 6 health checks, here they are" *is* a reason |
| **Morningstar** | Uncertainty-scaled star bands | 5-star discount 20/30/40/50/75% by uncertainty tier; seeded from TTM daily return SD | Price vol + FVE | `[NOT BUILT]` | **Drive uncertainty off data quality we can measure** (bars available, parser confidence, history length) rather than analyst judgement we don't have |
| **Morningstar** | No fixed star distribution | Absolute bands, so aggregate star count is itself a valuation gauge | — | — | **Design fork to decide:** VR's fixed percentiles always produce 5-star stocks even in a bubble; Morningstar's absolute bands don't |
| Screener.in | `zscr`/`fscr`/`cscr` | Altman Z, Piotroski F, C-score exposed as queryable fields | Screener scrape | `[NOT BUILT]` | Free, already computed — ingest rather than re-derive |
| Trendlyne / Tickertape | Forward P/E, PEG | Licensed consensus estimates | Paid vendor feed | `[NOT BUILT — REJECTED for v1]` | **Not obtainable free (§8.6).** Use trailing/TTM + historical growth, labelled honestly |

## 3. Mutual funds & overlap

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **Advisorkhoj** | MF overlap | 2 funds; **common-stock counts**, no weights, no published methodology | AMC monthly portfolios | `[PLANNED / NOT IMPLEMENTED]` — `PortfolioContextService.java:227` documents the data blocker | **Weighted Σ min(wᵢ), methodology stated on the surface.** Their number and ours will differ; say why |
| **Tickertape** | MF Compare | 2–5 funds; overlap %, common list, weight deltas. **MF-only** | Fund holdings | `[NOT BUILT]` | Extend to the pairs nobody covers (below) |
| **Zerodha Console** | MF look-through | Imports **top 40 stocks per fund** into portfolio analytics | Fund holdings | `[NOT BUILT]` | **Top-N truncation is a legitimate v1** — gets most of the overlap mass for far less parsing, *provided it's disclosed* |
| **Morningstar** | X-Ray effective exposure | `Direct(S) + Σ_f (Value_f × w_f,S)` | Fund holdings + direct positions | `[NOT BUILT]` | Ours is the only portfolio that natively holds both direct equity and funds in one ledger |
| *nobody* | **Direct stocks ↔ MF overlap** | — | Both sides | `[NOT BUILT]` | **Unoccupied, and it's exactly our user's situation** |
| *nobody* | **smallcase ↔ smallcase / ↔ MF overlap** | — | Rebalance-history CSVs (public) | `[NOT BUILT]` | Verified absent from smallcase and structurally unlikely to appear |
| **Kuvera** | Tax harvesting | Monitors portfolio, recommends same-day sell/rebuy; quantifies ₹15,625/yr | Lot-level cost basis | `[NOT BUILT]` | **Their page still cites the stale ₹1 lakh figure.** Correct rate + running FY exemption counter |
| **Zerodha Console** | TLH report | Realised + unrealised STCG/LTCG | Lot ledger | `[NOT BUILT]` | Add the **deferral prompt** — "wait N days, save ₹X" — which neither ships |

## 4. Credit cards

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **FinArt** | Cap-aware rupee ranking | Category → cards that pay, caps, cards that pay zero; ranks by ₹ **after caps and fees**; published methodology with per-card "verified on" date | MITC-sourced rules, **category not MCC** | `[PARTIALLY IMPLEMENTED]` `card/optimizer/CardOptimizerService.java` — rate × spend, `CardRewardRule.monthlyCapAmount` exists but no counter | **They have SMS parsing AND cap-aware ranking and haven't joined them. Join them.** |
| **ccreward.app** | Best-card by MCC | Bank+card+MCC+₹ → ₹; server-side scoring; user error-report form | Private curated rule DB | `[PARTIALLY IMPLEMENTED]` — `SpendAggregator` infers category from **merchant name**, `inferred=true` | Their `/bestCardQuestions` endpoint implies **dynamic follow-ups** ("was this via SmartBuy?") — our match predicate needs attributes the transaction won't contain |
| **TechnoFino** | Devaluation subforum | Crowdsourced, dated change tracking — **the only structured change log in the market** | Community | `[NOT BUILT]` | **A public, dated changelog is cheap differentiation and a trust moat** |
| **MaxRewards** (US) | Auto-activate offers | Logs in with stored credentials | Bank username + password | `[NOT BUILT — REJECTED]` | Split-key reversible encryption = plaintext replay. Outside AA rails, violates issuer T&Cs |
| **CardPointers** (US) | No bank connection | "Just add the cards you have"; offer sync in the user's own browser; location on-device | Card list only | — | **The privacy posture to copy** — but put it in the contract, not a blog interview |
| **SaveSage** | Gmail parsing | Optional **full-mailbox** read scope | Gmail OAuth | `[IMPLEMENTED]` but **narrow** | Users publicly object to their scope. **Jupiter's targeted query is the model** |
| **Paisabazaar / BankBazaar** | "Recommendations" | Ranked by **approval probability × commission** | Bureau + KYC | `[NOT BUILT — REJECTED]` | Different objective function from reward maximisation |
| *nobody* | **Marginal-value engine with live counters** | — | 4 counters on 4 clocks | `[NOT BUILT]` | `argmax` of (capped rate) + Δ(milestone) + Δ(fee-waiver) − surcharge. **In December, a 0%-reward card can be the right answer** |
| *nobody* | **UPI/RuPay route eligibility gate** | — | Network + route + blocked MCCs | `[NOT BUILT]` | Only RuPay works on UPI; P2P/C2C/P2PM/MF/IPO blocked. **Recommending a Visa card for a UPI rail is a correctness bug** |
| All card tools | Point valuation | Usually best-case transfer value as headline | Redemption routes | `[NOT BUILT]` | Vector by route; headline = user's **declared** behaviour; show conservative / realistic / ceiling |

## 5. Document & email ingestion

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **Jupiter** | CAS-mailback harvester | Published literal query: two CAS subjects + `newer_than:7d`. **Not a general inbox scraper** | Narrow Gmail scope | `[IMPLEMENTED]` — broader: 18 parsers + `ExcludedSender` | **Adopt their narrowing as the first cascade stage**, keeping our long-tail coverage behind it |
| **Kuvera** | CAS trigger, no password | Triggers a CAMS CAS to the user's own email | User consent | `[PARTIALLY IMPLEMENTED]` — `SavedPdfPassword`, `PendingPdf` | CAS password = PAN for all types **except KFintech** (user-defined). Per-issuer candidate generator |
| **CheQ** | CASA certification | Cloud Application Security Assessment to access email | Compliance | `[NOT BUILT]` | Relevant only at multi-user scale |
| **Plaid** | pending → posted | `pending_transaction_id` links restatement; **"in rare cases Plaid will fail to match"** | Both versions | `[NOT BUILT]` | **Model supersession as a link, and design the failure path** |
| **Stripe / TigerBeetle** | Idempotency key | Compare params to original, error on mismatch; cache results **including errors** | Content hash | `[IMPLEMENTED]` — `TransactionFingerprinter` (SHA-256, excludes message id) | Ours already survives forwards. Add `attachment_hash ‖ row_index` for multi-row documents |
| Industry | Reconciliation vocabulary | matched / unmatched / partially-matched / **exceptions** typed by cause | — | `[PARTIALLY IMPLEMENTED]` — `ReconciliationService.checkAll` covers 3 of ~17 checks | Adopt the vocabulary verbatim into the 10-status ingestion model |
| Research | Confidence signal | **logprobs are poor** — 0.705 ROC AUC on DocILE | Multi-signal | `[PARTIALLY IMPLEMENTED]` — flat 0.85 gate on model confidence | **Replace with arithmetic validation + parser/LLM agreement + self-consistency** |
| Research | Grounding spans | Verbatim source span per value, mechanically verified against source text | Span offsets | `[NOT BUILT]` | **This single mechanism delivers "EVERY FINANCIAL NUMBER MUST BE TRACEABLE"** and hallucination defence together |
| *nobody* | **User-facing review queue** | — | Confidence + UI | `[IMPLEMENTED]` `ai/review/entity/EmailReviewItem.java` | **We already have what no Indian competitor appears to offer.** Surface it as a feature, not plumbing |

## 6. Portfolio construction & risk

| Product | Feature | How it works | Data required | Wealth OS equivalent | Our improvement |
|---|---|---|---|---|---|
| **Kuvera** | Drift rebalancing | 5% deviation band; "Deviation based rebalancing is far superior to time-based" | Target allocation | `[NOT BUILT]` | Vanguard's newer work supports a **200/175bp trigger/destination** rule |
| **INDmoney** | Drift display | Market-cap/sector/equity-vs-liquid look-through, **no trigger engine** | Allocation | `[PARTIALLY IMPLEMENTED]` — `AssetAllocationRing.tsx` displays, doesn't trigger | Add the trigger |
| *nobody (India)* | **Tax-aware rebalancing** | — | Lot basis + tax rules | `[NOT BUILT]` | Unoccupied. Also: **cash-flow rebalancing — steering new SIPs to underweights — dominates sell-side for Indian retail** |
| **Zerodha Nudge** | Concentration alert | Single stock or sector >50% (Jul 2026) | Holdings | `[NOT BUILT]` | **Add look-through** — direct + MF + smallcase combined. That's what concentration actually means |
| **Value Research** | 15 risk statistics | Trailing 3yr monthly: SD, beta, alpha, R², Sharpe, Sortino, Treynor, capture ratios, max drawdown | 36 monthly returns | `[NOT BUILT]` | **Lead with max drawdown and downside capture.** Alpha/beta/R² on 36 observations are uninformative; R²≈0.95 makes beta≈1 tautologically |
| **smallcase** | Rebalance timeline | Full history + **downloadable constituents CSV**; **backtested data removed from charts**; visible without login | Constituent history | `[NOT BUILT]` | Copy all three, including the backtest removal |
| **smallcase** | Factsheet methodology block | Fixed sub-heads: universe / research / screening / weighting / rebalance | — | `[NOT BUILT]` | **A fixed template forces disclosure even when rigour varies** |
| AMFI / SEBI | Two-tier TRI benchmarks | Tier-1 by category (AMFI-prescribed table), tier-2 by style; all TRI | AMFI circulars | `[NOT BUILT]` | **Store all three peer-group definitions** (SEBI category, AMFI tier-1, holdings-derived) — they disagree for flexicap/multicap/BAF |

## 7. Factor construction (academic, not product)

| Source | Finding | Wealth OS equivalent | Our improvement |
|---|---|---|---|
| Computed from Ken French, n=757 | **HML–CMA = 0.68**; RMW/CMA/UMD pairwise 0.02/0.05/−0.02 | `AnalystService` momentum-family indicators pairwise **ρ>0.9** | Pick near-orthogonal axes; **momentum is currently ~4:1 over-weighted by accident** |
| Computed, subperiod split | **HML–RMW and RMW–CMA flip sign** between 1963–99 and 2000–26 | — | **Do not use PCA or covariance-optimised weights.** Fixed equal-weight buckets |
| AQR QMJ footnote 3 | "…a potential nightmare of choices and dimensionality… We choose the latter" | — | rank → z-score → average within bucket → equal-weight buckets |
| MSCI Quality | **Exactly 3 descriptors**: ROE, D/E, 5y earnings variability; winsorise 5/95, negate, average; **exclude if ROE missing** | `[NOT BUILT]` | **The cheapest defensible quality factor in existence** |
| Novy-Marx | **GP/A = (REVT−COGS)/AT**; "cleanest accounting measure of true economic profitability"; Spearman vs B/M = **−18%** | `[NOT BUILT]` | Value+profitability 50/50: Sharpe **0.85 vs market 0.34**, orthogonal to momentum |
| Piotroski / Beneish / QMJ | **Accruals appear in all three independently** | — | **Assign accruals exactly one slot** — same class of error as our momentum over-weighting |
| Faber | 10-month SMA: compound **9.32% → 10.18%**, max DD **83.7% → 42.2%** | `[NOT BUILT]` | Cheapest defensible regime overlay |
| Daniel & Moskowitz | Panic state (neg 2y return × high variance): WML beta falls **0.518 (t=−28.4)**; Sharpe 0.682 → **1.194 OOS** | `[NOT BUILT]` | **Regime-modulating a stock-level signal is supported ONLY for momentum** |
| Cederburg et al. 2020 | Vol-managed loses on certainty-equivalent in **72 of 103** strategies | — | Direct evidence against generalising vol scaling |
| Asness "Fight the Fed Model" | E/P alone adj. R² 29.6% vs **1.4%** for E/P − Y | `[NOT BUILT]` | **Never use BEER/Fed model as a score input** |
