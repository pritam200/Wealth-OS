# Competitive Research Report

**Research window:** September 2026. All probes and fetches dated 2026-09-15 unless stated.
**Method:** 11 parallel research agents, each instructed to label every claim as
**[A] documented** (with URL), **[B] inference** from observable product behaviour, or
**[C] speculation**. That labelling is preserved below. Where two sources disagreed, the
disagreement is recorded rather than resolved.

> **Read this document before `PRODUCT_GAPS.md` or `TECHNICAL_GAPS.md`.** Those two are
> derived from this one. Nothing here is a design decision — §9 records the
> COPY / IMPROVE / DIFFERENTIATE / REJECT verdicts, and those feed the design docs.

---

## 0. Executive summary — the five findings that change the build

1. **Nobody in Indian retail ships net-worth attribution.** No competitor answers "your net
   worth rose ₹X this month: ₹A you saved, ₹B the market gave you, ₹C you spent." The
   blocker is not the maths (Modified Dietz is trivial) — it is **transfer-pair detection**,
   which Wealth OS already solved with `LedgerTransfer`. This is the single largest
   unoccupied space found. **[B]**
2. **smallcase has no overlap detection, and the absence is structural.** Verified three
   ways (§4). Overlap warnings on the platform come only from third-party managers'
   marketing blogs. Surfacing overlap would suppress multi-basket subscriptions, which is
   smallcase's revenue model. **[A] for the absence, [C] for the motive.**
3. **Our stock scoring model is wrong in a way the market has already solved.** Value
   Research uses Quality 25 / Growth 20 / **Valuation 35** / Momentum 20 with an entirely
   separate ratio set for banks. We use technical 30 / momentum 20 / valuation 20 /
   **sentiment 30** — no quality factor, no growth factor, and a 30% weight on the softest
   available signal. VR does not use sentiment as a rating input at all. **[A]**
4. **The card problem is stateful, not a rate lookup.** Four independent counters on four
   different clocks (category caps, milestone progress, fee-waiver progress, transaction-count
   milestones), each with its *own* eligible-spend definition. Kotak alone publishes **five
   different exclusion lists for five different purposes**. A static rate table cannot express
   "this card earns 0% but is ₹40k short of a ₹2L fee waiver, so use it." **[A]**
5. **Forward estimates are not obtainable free in India.** No free source for consensus EPS
   exists. Forward P/E and PEG must be dropped from v1 and rebuilt on trailing/TTM metrics,
   labelled honestly. **[B]**

---

## 1. Aggregation & net-worth platforms

### 1.1 The attribution gap

Standard practice decomposes a period's net-worth change into **net saving + revaluation**.
The performance-measurement primitives are well established:

- **Modified Dietz:** `R = (B − A − F) / (A + Σ Wᵢ·Fᵢ)` where A = beginning value,
  B = ending value, F = net external flows, Wᵢ = weight of flow i by time remaining.
- **TWR vs MWRR:** TWR strips cash-flow timing (GIPS-compliant, the only honest basis for
  benchmark comparison); MWRR/XIRR includes the user's timing (answers "what did *my money*
  earn"). **[A]**
- **Brinson-Fachler:** allocation / selection / interaction attribution against a benchmark.

Morningstar's *Mind the Gap* quantifies the difference between the two for the decade to
Dec 2024: investor return **7.0%** vs total return **8.2%** p.a. — a ~1.2pp gap that is pure
cash-flow timing. **[A]** https://www.morningstar.com/business/insights/research/mind-the-gap

**The finding:** every Indian platform shows either XIRR *or* a balance, never the bridge
between two net-worth points. INDmoney shows XIRR against a Nifty 50 benchmark return —
which is an apples-to-oranges comparison unless the benchmark's return is recomputed under
the *user's own cash-flow schedule*. **Benchmark XIRR on your cashflows is rare-to-absent in
Indian retail and is the correct build. [B]**

### 1.2 Zerodha Console — and a correction to a premise we held

**Zerodha does not score or rate stocks.** No Zerodha-owned surface assigns a rating, grade,
or buy/sell score. Its analytics are descriptive plus *risk warnings*. **[A]**

| Surface | What it does |
|---|---|
| Console Analytics | Portfolio XIRR (vs Nifty 50 / Midcap 150), dividend & interest, **Deep dive powered by Tijori** (governance / accounting / financial-health red flags, green-yellow-red), contributors to returns, portfolio timeline **[A]** |
| Console Portfolio Analytics | Asset class, sector, market-cap tier (large 1–100 / mid 101–250 / small 251+), **MF look-through importing top 40 stocks per fund** **[A]** |
| Kite Nudge | Pre-trade risk warnings only: T2T, unsolicited tips, corporate actions, IRP/insolvency, illiquid (<₹100cr mcap), ASM/GSM/ESM, promoter pledge, price far from LTP, penny stock, ICA. **Newest (17 Jul 2026): alert when a single stock or sector exceeds 50% of portfolio.** **[A]** |
| Kite Fundamentals widget | Tijori-powered since Jun 2024 (replaced Tickertape). PE, sector PE, 52w range, revenue mix, events, financials, peers, shareholding. **No score.** **[A]** |

**A correction worth recording:** Tickertape is **not** Zerodha-owned. Legal entity is
Anchorage Technologies Pvt Ltd, branded "Tickertape from smallcase"; smallcase is CASE
Platforms Pvt Ltd; **Rainmatter (Zerodha's fund) is an investor in smallcase, not its owner.**
**[A]** https://www.tickertape.in/meta/disclosures — Any design assumption that "Zerodha rates
stocks" is wrong; that scoring is Tickertape's, with its own terms and a Pro paywall on the
numeric values.

**The most useful design lesson from Zerodha is an inconsistency in their own product:**
Console's *portfolio* XIRR covers current holdings only and "does not take into account
historical buy and sell trades," while the *equity-holdings* XIRR page covers all trades since
FY2017 with corporate-action adjustment. Two surfaces of one product give two answers. Zerodha
itself points users to MProfit for trade-inclusive portfolio XIRR. **[A]**
https://support.zerodha.com/category/console/portfolio/console-holdings/articles/portfolio-xirr

They also **suppress** the figure rather than mislead: "If the majority of the investments are
less than a year, the XIRR would be greater than the actual percentage" → shows "–". **[A]**
This refuse-to-answer behaviour is worth copying.

### 1.3 Return-metric selection (Zerodha Varsity / Kuvera, consistent)

| Horizon | Lumpsum | SIP / multiple flows |
|---|---|---|
| < 1 year | Absolute | Absolute |
| > 1 year | CAGR (≡ XIRR for a single flow) | XIRR |

**[A]** https://zerodha.com/varsity/chapter/measuring-mutual-fund-returns/ ·
https://kuvera.in/blog/decoding-mutual-fund-returns-calculating-cagr-xirr/

Value Research computes fund returns on **adjusted NAV — "NAV adjusted for dividends and
splits"** **[A]** https://www.valueresearchonline.com/methodologies/ — this is the IDCW answer:
dividends are reinvested into the return series, not dropped.

**Engineering pitfalls to design for [B]:** switches must be modelled as paired outflow/inflow
on the same date (naive systems double-count); IDCW payouts are outflows in the XIRR vector;
partial redemptions are outflows with the residual valued at terminal date; SIP+STP chains
produce sign-alternating flows where XIRR can have **multiple roots**. Most Indian platforms
likely use bracketed Newton/bisection and silently fail on multi-root cases. **[C]**

### 1.4 Account Aggregator — previously recommended, now retracted

An earlier recommendation in this project put Account Aggregator at the top of the roadmap.
**That was wrong and is retracted.** FIU registration requires being an RBI/SEBI/IRDAI/PFRDA-
regulated entity, first-year cost ₹5–25 lakh, no hobbyist tier — **and credit cards are
excluded from AA entirely.** Jupiter is an FIU via Finvu and states "AA data can be viewed as
the source of truth for bank balance, FD/RD and transactions data" **[A]** — that path is
open to Jupiter and closed to us.

**The un-gated rails we do have:** CAS (CDSL/NSDL/CAMS/KFintech) mailback, Gmail, and the
AMFI/AMC public disclosure files.

> **A second, newer gate — and it closed recently. [A]**
> In **September 2025, AMFI directed MF Central (the CAMS/KFin platform) to stop sharing
> investor data with third-party apps**, which had been pulling it via OTP-consent.
> https://www.business-standard.com/markets/mutual-fund/amfi-asks-mf-central-to-stop-sharing-investor-data-with-third-party-apps-125091801057_1.html
>
> **Consequence: for the user's *own* MF holdings, the remaining paths are investor-supplied
> CAS files or Account Aggregator — and AA gates on regulatory status (§1.4).** This makes
> **CAS-first the only viable architecture for us**, and it is the architecture Wealth OS
> already has. Note the vendor arguing this loudest (casparser.in) has commercial bias, but the
> AMFI restriction itself is independently reported.
>
> **This is distinct from the *fund holdings* question in §3** — scheme portfolios (what a fund
> owns) remain free and mandated; it is *investor* portfolios (what the user owns) that got
> harder.

---

## 2. Investment research & scoring platforms

### 2.1 Value Research — the published methodology that indicts our model

**Stock Rating — exactly four dimensions, published weights [A]**
https://www.valueresearchonline.com/methodologies/

| Dimension | Weight | Inputs (non-financial companies) |
|---|---|---|
| **Valuation** | **35%** | Earnings yield, P/E, P/B, FCF yield, PEG, dividend yield |
| **Quality** | **25%** | ROE, ROCE, operating margins, debtor-to-sales, D/E, contingent liabilities, working-capital-ex-cash to sales |
| **Growth** | **20%** | Revenue, operating profit, PAT, cash flow from operations, **Piotroski F-score** |
| **Momentum** | **20%** | Price momentum |

IPOs use 32 / 23 / 45 with momentum dropped (no price history). Banks/NBFCs substitute an
**entirely different ratio set**: ROA, net interest margins, provision coverage, capital
adequacy, and a **sustainable-growth-rate gap** (SGR = ROE × (1 − payout), compared against
actual advances growth), with **P/E replaced by PE-to-SGR**.

**5-star percentile curve:** top 10% / next 22.5% / middle 35% / next 22.5% / bottom 10%.

**Published exclusions — a "refuse to rate" path [A]:** untraded in the last month, less than
3 years of history, negative net worth, accumulated losses, bottom 1% by market cap.

**Screener.in exposes the composite scores directly** as `zscr` (Altman Z), `fscr` (Piotroski
F), `cscr` (C-score) — these are queryable, not derived. **[A]**

**Structural insight:** VR separates the **free quantitative star rating** from **Stock
Advisor** (valueresearchstocks.com, launched 2017), a paid human-analyst product doing
qualitative thesis work — management track record, business strength, growth drivers. **They
do not pretend the quant score is a thesis.** **[A]**

**Fund rating is a different engine:** Return Score **minus** Risk Score, equity weighted
5-year 60% / 3-year 40%. The Fund Risk Grade is deliberately downside-only — "focus on
downside risk… periods where the fund underperforms the risk-free return" **[A]**
https://www.valueresearchonline.com/fund-rating-methodology/

### 2.2 Tickertape Scorecard

Verified live on https://www.tickertape.in/stocks/reliance-industries-RELI **[A]**. Six
dimensions, labelled with per-dimension words (not Good/Avg/Bad):

| Dimension | Scored | Inputs |
|---|---|---|
| Performance | 0–10 | 1Y return + 5Y CAGR + 1Y forecast |
| Valuation | 0–10 | PE, PB, P/CFO |
| Growth | 0–10 | Top-line/bottom-line growth with positive earnings |
| Profitability | 0–10 | ROE, margins |
| Entry point | — | Fundamentals leg (price vs intrinsic value) + technicals leg (overbought/oversold) |
| Red flags | — | Promoter pledge, ASM, GSM, probability of default, unsolicited messages |

Numeric score and peer rank are **gated behind Tickertape Pro**. Internal API
`analyze.api.tickertape.in/stocks/scorecard/RELI` returns `"locked":true` with all values
null. **[A]**

**Note [B]:** Tickertape's Red Flags inputs (ASM/GSM/pledge/unsolicited SMS) are the *same*
risk primitives Zerodha's Nudge uses. The difference is Tickertape rolls them into a
stock-level score; Zerodha fires them only as a pre-trade warning. **Both treat them as
disqualifiers, not as score contributions — a separate "red flag" channel, not a factor.**

### 2.3 Morningstar — fair value + Uncertainty Rating **[A]**

Methodology PDF: https://www.morningstar.com/content/dam/marketing/shared/research/methodology/705988Morningstar_Equity_Research_Methodology.pdf

Three-stage DCF: Stage I explicit 5–10y full-financial-statement forecast; **Stage II fade of
ROIC toward WACC, with fade length governed by the Economic Moat rating** (wide fades slowest);
Stage III perpetuity.

Uncertainty Rating seeds *quantitatively* from trailing-12-month standard deviation of daily
returns, then takes an analyst overlay (operating/financial leverage, cyclicality,
concentration, pricing power, contingent events). **The star bands are a function of
uncertainty** — this is the mechanism we lack entirely:

| Uncertainty | 5-star discount to FV | 1-star premium |
|---|---|---|
| Low | 20% | 25% |
| Medium | 30% | 35% |
| High | 40% | 55% |
| Very High | 50% | 75% |
| Extreme | 75% | 300% |

Ratings recompute at every market close. **There is no predefined star distribution**, so the
aggregate star count is itself a market-valuation gauge — unlike VR's fixed percentile curve.
That is a real design fork: **fixed percentiles always produce 5-star stocks even in a bubble;
absolute bands don't.**

### 2.4 Valuation methods — what holds up under scrutiny

| Method | Verdict |
|---|---|
| **Historical multiple percentile / z-score** | Widely published (Koyfin, Bloomberg EQRV, Screener.in). **But:** assumes a stationary multiple. Re-rating and intangible-investment accounting break stationarity. A stock at the 5th percentile of its own 10y P/E is a value trap exactly when E has structurally shifted — **the percentile is silent on why it de-rated. [B]** |
| **Reverse DCF / price-implied expectations** | Rappaport & Mauboussin, *Expectations Investing* (2021). Take price as given, solve for the market-implied growth/margin pair or forecast period. Free tools at expectationsinvesting.com. **[A]** The honest framing: we don't know fair value, we know what the price assumes. |
| **EV/EBITDA vs sector median** | Damodaran: sector medians are the shortcut; the defensible version is cross-sectional regression on fundamentals. His published market regression: `EV/EBITDA = 8.554 + 1.016·g(rev) − 0.150·TaxRate − 0.0664·(Debt/Capital) − 0.0188·ReinvestmentRate, R² = 38.0%` **[A]**. Circularity caveat: if the sector is mispriced, so is the answer. |
| **Fed model / earnings yield vs bond yield** | **Rejected as predictive.** Asness, "Fight the Fed Model," *JPM* 2003: E/P is *real*, Y is *nominal* — the Modigliani-Cohn money illusion. Empirics: E/P alone forecasts 10y real returns at adj. R² 30.2%/34.9%/29.6% across windows; **E/P − Y gets 11.9%/9.7%/1.4%**, and Y's bivariate coefficient is ~0 (t = 0.06, −0.36, 0.44). **[A]** https://www.aqr.com/-/media/AQR/Documents/Journal-Articles/JPM-Fight-the-Fed-Model.pdf |
| **India BEER ratio** | The local version (10y G-Sec ÷ Nifty earnings yield) is published by Canara Robeco, DSIJ, SBI Securities. Inherits the same flaw. **[A]** |
| **Nifty P/E bands** | NSE publishes official daily P/E, P/B, dividend yield. The common "<20 cheap / 20–23 fair / >25 expensive" bands are **folk-practice, not NSE-endorsed**, and are corrupted by the 2020–21 COVID earnings collapse that pushed reported Nifty P/E above 40. **[B]** |
| **Residual income / EVA** | Ohlson (1995): `V = B₀ + Σ(ROEₜ − r)·Bₜ₋₁/(1+r)ᵗ`. Ties valuation to the quality factor directly — a stock only creates value when ROE > cost of equity. **[A]** |

**Valuation timing is published as difficult.** Asness, Ilmanen & Maloney, "Market Timing:
Sin a Little," *JOIM* 2017: valuation levels contain return information but are far too noisy
to time profitably, and naive backtests are contaminated by look-ahead in the normalisation
(you didn't know the 10-year mean in real time). **[A]** — **This is a direct warning against
our own design: any percentile band we compute must use only data available at that date.**

---

## 3. Mutual fund overlap — feasibility resolved

This was the make-or-break question. **Answer: feasible, but it is a first-class subsystem,
not a feature.**

### 3.1 The data is mandated and free

SEBI requires MFs to disclose **full portfolios of all schemes monthly**, on the AMC website
**and** AMFI's, **within 10 days of month-end**, in "user-friendly and downloadable spreadsheet
format." Debt schemes: **fortnightly, within 5 days.** **[A]**
https://www.sebi.gov.in/sebi_data/faqfiles/sep-2024/1727242783639.pdf

AMFI hosts at https://www.amfiindia.com/online-center/portfolio-disclosure — **but that page
is a JS-driven dropdown; plain HTTP returns no per-AMC links.** A guessed path
(`/research-information/other-data/monthly-portfolio-disclosures`) **404s** — blog posts
quoting it are wrong. **[A]**

### 3.2 The cost

**No central machine-readable source exists.** Each AMC posts its own XLS/XLSX, often a single
mega-workbook covering all schemes, with inconsistent headers, header-row offsets, and notes
rows. HDFC's portfolio page **403s to bots**; expect WAFs. **[A]**

> **Plain estimate: 3–6 focused weeks for one developer to reach the top ~200 equity schemes,
> then ~1–3 days/month ongoing as AMCs silently change layouts.** The hard part is not access —
> it's (a) heterogeneous XLSX layouts across 40+ AMCs, (b) mapping scheme names to AMFI codes
> and stock names to ISINs, (c) WAFs and JS-gated download pages. **Not impractical; not a
> weekend.** **[B]**

This resolves the standing code comment at `backend/src/main/java/.../PortfolioContextService.java:227`
("fund constituent data is not available, so true stock/fund overlap is not computed"). The
comment is accurate *today* and stays accurate until this subsystem is built.

**Observed lag in the wild [A]:** Advisorkhoj on 15 Sep 2026 was still showing "Portfolio as on
30-06-2026" — a **~2.5-month lag**. Even established players don't refresh aggressively.
**Label the as-of date prominently.**

**Cheap partial alternative [A]:** Zerodha Console's MF look-through imports **the top 40
stocks per fund**. Top-N truncation is a legitimate v1 — it gets most of the overlap mass and
requires far less parsing — provided the truncation is disclosed.

### 3.3 The formula

**Kuvera documents the formula explicitly [A]** — overlap is "the sum of the lower weightings
of every common stock held between two funds," with a worked example (Fund A holds HDFC Bank at
5%, Fund B at 8% → contributes 5%).
https://kuvera.in/blog/which-mutual-fund-overlap-calculator-provides-the-most-accurate-comparison-for-my-portfolio/

```
Overlap(A,B) = Σ  min(w_A,i , w_B,i)     for i ∈ (holdings_A ∩ holdings_B)
```

This is the standard **histogram-intersection** measure: bounded [0, 100%], symmetric. Kuvera
explicitly contrasts it with the naive **stock-count** method (common ÷ total unique), which
they call less accurate because it treats a 1% position the same as a 10% one. No Indian
regulator or index provider publishes an authoritative spec, so Kuvera's is the best available
citation.

**Denominator decisions that will generate support tickets [B]:** fund weights are % of NAV, so
cash, debt and derivatives sit in the denominator — **two funds each holding 8% cash
mechanically cap overlap at 92%.** Recommendation: report raw (% of NAV) as the headline since
it reflects real rupee duplication, and renormalise to equity-only in a secondary view.
Derivative/arbitrage notionals badly distort weights in hybrid and arbitrage schemes — exclude
or flag those categories.

### 3.3a Fund → direct-stock look-through ("effective exposure")

This is the Morningstar X-Ray model **[A]** (https://www.morningstar.com/help-center/portfolio/xray):

```
EffectiveExposure(stock S) = DirectValue(S) + Σ_f ( Value(fund f) × w_f,S )
Effective%                 = EffectiveExposure(S) / TotalPortfolioValue
```

**Five caveats, all [B], each a real failure mode:**
1. **Stale portfolios** — you are applying a month-old weight to today's fund value. A fund that
   has exited the stock still shows exposure. **Refuse to compute for portfolios older than
   ~60 days** and always show the as-of date.
2. **Weights drift with price** — published % of NAV is a month-end snapshot. More accurate:
   re-price the disclosed *quantity* at today's price and renormalise.
3. **Cash drag** — fund weights sum to <100%; effective exposure understates unless handled.
4. **Derivatives/arbitrage** — notional longs inflate exposure; hedged positions aren't real
   exposure.
5. **Double-counting via FoFs and index funds** held inside the portfolio.

**Critical for us:** Advisorkhoj publishes **no methodology** and some Indian tools report
*count of common stocks* rather than weighted overlap. **Our number will not match theirs.
State which definition we use, on the surface.**

### 3.4 Who ships it

| Tool | What it shows |
|---|---|
| **Advisorkhoj** | 2 funds at a time; common-stock **count**, uncommon counts, drill-down, download. Data as-of 30-06-2026. No methodology. **[A]** |
| **Tickertape "Mutual Fund Compare"** | 2–5 funds; overlap %, common-stock list, weight differences. **Mutual-fund-only — does not ingest smallcases.** **[A]** |
| Dezerv, 1 Finance, Thefundoo, OverlapIQ | Standalone overlap calculators **[A]** |
| Kuvera, INDmoney | Editorial content about overlap, not a productised in-app tool **[B]** |

**Value Research's published study [A]** — "Flexi-cap vs large and mid-cap funds: The overlap
problem," 21 Apr 2026: across 31 same-AMC pairs — 1 below 20%; **14 at 20–40%; 12 at 40–60%;
4 at 60–80%.**
https://www.valueresearchonline.com/stories/228353/flexi-cap-vs-large-and-mid-cap-funds-the-overlap-problem/
(Note: that's L&M vs flexi-cap, not large-cap vs flexi-cap — don't misquote it.)

### 3.5 The gap nobody covers

- smallcase ↔ smallcase overlap: **nobody**
- smallcase ↔ mutual fund overlap: **nobody**
- **Direct stocks ↔ mutual fund overlap: nobody** — and this is the case that matters most for
  a user who holds both, which is exactly Wealth OS's user.

---

## 4. smallcase & WealthDesk — the overlap absence, verified

**smallcase has NO overlap-detection, duplicate-holding, or cross-basket concentration
warning.** Verified three ways **[A]**:

1. smallcase's own educational page on portfolio overlap
   (https://www.smallcase.com/learn/mutual-funds-portfolio-overlap/) is **purely mutual-fund
   framed**, offers no calculator, never discusses overlap *between smallcases*, and
   outbound-links to Tickertape's MF screener rather than any native tool.
2. Every overlap-risk warning that exists is a **third-party publisher blog, not a smallcase
   product surface** — Wright Research ("How Many Smallcases Should You Have in Your
   Portfolio?"), Teji Mandi ("Does Multiple Smallcases = More Profit?"). Both warn that popular
   stocks recur across themes creating hidden cumulative concentration. **These are smallcase
   *managers* marketing on the platform, not smallcase warning its users.**
3. No help-centre article, disclosure, or product-release post surfaces such a feature.

**[C] on the motive:** the gap looks structural, not accidental. Surfacing overlap would
directly suppress multi-basket subscriptions (the revenue model), and smallcase's disclosure
posture — "The Company does not provide any research recommendations or advice on its own"
**[A]** https://www.smallcase.com/meta/disclosures/ — makes a platform-generated concentration
warning legally awkward, since it would be smallcase editorialising on third-party RIA
portfolios.

**What smallcase does well and we should copy [A]:**

- **Per-basket factsheet PDF** at `assets.smallcase.com/factsheets/<SCID>.pdf`, fixed template:
  rationale prose, creator + SEBI reg number, and a **Methodology section with four named
  sub-heads — Defining the universe / Research / Constituent Screening / Weighting /
  Rebalance** — plus a ratios table, inception date, market-cap category, and
  **Review Frequency / Last Reviewed / Next Review On**.
- **The 2023 transparency release:** visible rebalance timeline (full history with dates and
  constituent changes), **downloadable CSV of historical constituents between rebalance
  dates**, XIRR, cost-adjusted returns, **removal of backtested data from charts**, and
  rebalance frequency + past/next dates visible **without login**. **[A]**
  https://www.smallcase.com/blog/providing-greater-clarity-and-control/
- **Rebalances are opt-in, not automatic** — notification, review, "apply the update in 2
  clicks"; skipping means "composition & returns may vary from the original." Users may
  exclude individual stocks. **[A]**
- Index math is transparent: `qty = (100 × weight) / price`; equal weighting by default where
  none prescribed; rebalance index values use **next-day OHLC average (T1), not T0 close**, to
  reduce manager-vs-investor return divergence; returns exclude transaction costs. **[A]**
  https://www.smallcase.com/meta/return-calculation/

**The template is mandatory; rigour is not [B].** The verified Prudent Cap factsheet's
Weighting line reads "All the stocks selected will have equal weight" and Rebalance reads
"rebalanced on an as-needed basis" with "Next Review On: To Be Decided." Rationale prose is
often marketing ("our AI algorithm analyzes 500 handpicked stocks") rather than falsifiable
factor definitions.

**WealthDesk** is functionally the same model — SEBI-registered curators, opt-in rebalance
approval, ₹1,000 minimum, distributed through Share.Market (PhonePe). Two differences **[A]**:
fee collection (smallcase debits the broking account; WealthDesk collects upfront via
UPI/netbanking) and positioning (WealthDesk is broker-agnostic infrastructure; smallcase also
manufactures in-house via **Windmill Capital**, its wholly-owned RA/PM subsidiary — a conflict
WealthDesk doesn't have). **[C]:** WealthDesk appears to have no overlap tooling either;
its 2026 operational status could not be verified (root domain 403s to automated fetch).

**The raw material for overlap is public** — per-basket constituents are exposed in the
downloadable rebalance-history CSVs and factsheets. **Overlap is computable without either
platform's cooperation. [B]**

---

## 5. Credit-card optimisation

### 5.1 Landscape

| Product | What it does | Terms source | Spend data | Output |
|---|---|---|---|---|
| **FinArt** (finart.app) | **Closest real competitor.** Exclusions/cap checker (category → which cards pay, caps, which pay zero, ~40+ cards); spend wizard ranking by **rupee value after caps and fees**; "is this fee worth it" checker **[A]** | **Published methodology**: issuer pages + **MITC documents**, per-card "verified on" date, user corrections next release. Explicitly **category-based, not raw MCC**, "because issuers rarely publish the exact MCC map" **[A]** | App does SMS/notification parsing (1M+ installs) with a private mode keeping SMS off their servers **[A]** — but the web card tools ask you to type spends; **the two halves appear not joined [B]** | Ranked list + ₹ net value |
| **ccreward.app** | Bank+card+MCC+₹ → ₹ reward; "Best Card" over your saved cards; MCC lookup; transfer-partner calculator; "report incorrect reward" form **[A]** | Frontend is OSS but **rules are NOT in the repo** — pulled from a private `/v4/static/` API behind a key; scoring is server-side at `/v4/calculateRewards` **[A]** | None — manual ₹ per query | Ranked ₹ per card |
| **SaveSage** (savesage.club) | Manual cards; rewards calculator; points balance tracker; "Travel on Points" award-value finder; AI chat; paid expert calls **[A]** | Not disclosed; publishes devaluation blog posts → in-house editorial **[B]** | **Optional Gmail read-access**; users publicly object to full-mailbox scope **[A]** | Recommendations; **no auditable ₹ model exposed [B]** |
| **TechnoFino** | Forum + YouTube. **A dedicated "Devaluation Update" subforum — the only structured change-tracking in the market [A].** Member-built MCC Lookup Tool (scan a UPI QR → per-card earn/exclusion/surcharge) | Crowdsourced from members' T&C emails | None | Discussion |
| **CardInsider / CardExpert** | Editorial, compare tools, lounge finder; CardExpert has **no calculators** **[A]** | Editorial **[B]** | None | Narrative verdict |
| **Paisabazaar / BankBazaar** | Credit score, eligibility, compare, EMI calcs **[A]** | Issuer partner content **[B]** | None (bureau + KYC, not spend) | **Ranked by approval probability × commission, not reward math [B].** BankBazaar sells a co-branded RBL card — direct conflict **[A]** |
| **MaxRewards** (US) | Unified balances, **auto-activates** Amex/Chase/BoA/Citi offers and quarterly categories, fee-vs-value tracker, browser extension **[A]** | Not disclosed **[C]** | **Stores bank usernames + passwords**, encrypted with two keys in two databases **[A]**. Split-key reversible encryption ⇒ plaintext replay, i.e. screen-scraping **[B]** | Dashboard + best-card prompt |
| **CardPointers** (US) | 5,000+ cards, offer auto-add, 5/24 tracker, location-based prompt. **"You don't have to connect your banks, just add the cards you have"**; offer sync runs in the user's own browser; location stays on-device **[A]** | Not disclosed **[C]** | None required | Best-card-for-category |

**Could not verify as existing Indian products:** "Card Optimizer" (only a GitHub hobby repo and
US tools) and "SwipeSage" (nothing — likely a misremembering of SaveSage). "RewardSmart" exists
but is **US-only**. **[A] null results.**

**The whitespace [B]:** nobody closes the loop *observed spend → cap-aware ranked card →
automatic re-ranking after a devaluation*. **FinArt owns both halves and hasn't joined them.**

### 5.2 UPI + RuPay — a hard correctness gate we do not implement

**Network gate:** "Currently, only RuPay Credit Cards can be linked on UPI" **[A]**
https://www.sbicard.com/sbi-card-en/assets/docs/html/personal/sbi-credit-card-on-upi/pdf/sbi-upi-faq.pdf
Visa/Mastercard/Amex cannot. **Recommending a Visa card for a UPI rail is a correctness bug,
not a ranking imperfection.**

**Route gate — a second gate, before MCC.** Same FAQ, verbatim: *"Peer to Peer (P2P), Card to
Card (C2C) and Peer to Peer to Merchant (P2P2M) transactions are not allowed on UPI on Credit
Card. Only Payment to Merchant (P2M) will be allowed."* Restricted: P2P, P2PM, C2C, digital
account opening, lending platforms, cash withdrawal (merchant and ATM), eRUPI, IPO, foreign
inward remittances, **mutual funds**. Limits ₹5L/txn, ₹10L/day (₹1L at small offline
merchants). **[A]**

**Acquirer-side blocked MCCs [A]** (https://razorpay.com/docs/payments/payment-methods/upi/cc-on-upi/):
6010/6012/6013 (financial institutions), 6011 (ATM), 6051 (crypto/forex), 6211 (securities),
4829 (wire transfer), 7322 (debt collection), 7400-series (lending), 7800–7802/7995/9406
(gambling/lottery).

**Rewards parity and its load-bearing loophole.** NPCI circular **RuPay-OC011-FY-24-25**,
effective **1 Sep 2024**: rewards "should not be lower (directly & indirectly) for RuPay Credit
Cards on UPI transactions" — **except where the issuer earns no interchange** **[A]**
https://livefromalounge.com/npci-mandates-rewards-parity/ . RuPay-CC-on-UPI carries ~2% MDR only
above ₹2,000, and **small merchants (turnover < ₹20 lakh) are fully exempt regardless of ticket
size**.

> **Consequence:** whether a ₹5,000 UPI payment earns anything can depend on the *merchant's
> turnover band* — a fact the user cannot observe and our engine cannot reliably know.
> **Model this as a confidence band, not a number. [B]**
> (The ₹20 lakh threshold itself is from payment-gateway blogs, not an NPCI document —
> directionally confirmed, exact figure **unverified**.)

### 5.3 Exclusions are per-issuer and non-standard

The canonical published list is Kotak's **[A]**
(https://www.kotak.bank.in/content/dam/Kotak/gsfcfiles/credit-cards/list-of-mccs-with-respect-to-revised-fees-and-rewards_june_01_2025.pdf):
Utility 4900/4814/4899/4812 · Insurance 5960/6300/6381/6399 · Wallet 6540 · Rent 6513 ·
Government 9222/9223/9311/9399/9402/9405 · Fuel 5172/5541/5542/5983 ·
Education 8211/8220/8241/8244/8249/8299 · Gaming 5816.

**Critically, Kotak maintains five different lists for five different purposes** — fee levy;
reward exclusion; Air-Miles exclusion; fee-waiver/milestone exclusion; "White Pass" exclusion
(effective 1 Apr 2026) — with **per-card-variant carve-outs** (fuel exclusion doesn't apply to
IndianOil Kotak; milestones don't apply to Kotak UPI RuPay), and the disclaimer that the list
**"is not exhaustive"** and is modifiable at the bank's discretion. **[A]**

Gold/jewellery appears **not** in Kotak's list but **in Axis Atlas's milestone exclusions**.
**Exclusion sets are per-issuer, not standard. [A]**

Concrete per-card example — HDFC UPI RuPay CashPoints **[A]**: 3% grocery/supermarket/dining/
PayZapp, 2% utilities, 1% else — **three independent 500-CashPoint monthly caps**, minimum ₹100
per transaction, 1 CashPoint = ₹0.25, and rent/wallet/EMI/fuel/insurance/government excluded.

### 5.4 Why this is a stateful problem — four counters, four clocks

| Card | Structure |
|---|---|
| HDFC Infinia / Diners Black | Monthly accelerated cap 15,000 / 10,000 RP; **separate** 3,000 RP/mo cap on SmartBuy vouchers (new, Jul 2026); utility (4900) 2,000 RP/mo; telecom (4812/4814/4899) 2,000 RP/mo |
| Axis Atlas | Anniversary-year tiers: Silver → Gold at **₹7.5L**, → Platinum at **₹15L**; milestones 2,500/5,000/10,000 EDGE Miles; **gold, rent, wallet, govt, insurance, fuel, utilities, telecom don't count toward the tier** |
| SBI Cashback | 5% online / 1% offline; **from 1 Apr 2026 caps cut to ₹2,000 online + ₹2,000 offline**; gaming/toll/government dropped; ₹999 fee waived at ₹2L annual spend |
| Amex India | 1,000 bonus MR for **4 transactions of ≥₹1,500 each in a calendar month**; Platinum Travel milestones at ₹1.9L and ₹4L |
| ICICI / SBI fees | ICICI 1% on wallet loads ≥₹5,000; SBI 1% on wallet loads >₹1,000 and 1% on education via aggregators (from 1 Nov 2025); **ICICI excludes rent/govt/education from fee-waiver spend specifically** |

All **[A]**.

The four state dimensions, on four different clocks:

1. **Category cap counters** — monthly *or* per statement cycle (**SBI uses statement cycle,
   HDFC uses calendar month**). Marginal rate drops to base the instant a counter fills.
2. **Milestone progress** — anniversary-year, with its *own* eligible-spend definition.
3. **Fee-waiver progress** — a *third* eligible-spend definition.
4. **Transaction-count milestones** (Amex 4×₹1,500) — make *splitting* a purchase rational,
   which no rate model can express.

> **The correct answer to "which card for this ₹X?" is `argmax` of *marginal* value =
> (capped category rate) + (Δ probability-weighted milestone value) + (Δ fee-waiver value)
> − (surcharge).** In December, a card whose caps are exhausted but which is ₹40k short of a
> ₹2L fee waiver is the right answer **at a 0% reward rate**.
> **Build the counters first; the rate table is the easy part.**

### 5.5 Point valuation

The same point is worth wildly different amounts by route: HDFC Infinia ≈ **₹0.25 cashback /
₹0.50 catalogue / ₹1.00 SmartBuy travel**; Axis **EDGE Reward Points ₹0.20** vs **EDGE Miles
~₹1+** on airline transfer — **two currencies on one issuer with a ~5× gap.** (Aggregated from
secondary sources — **treat as ranges, not gospel.**)

**Honest "net annual value" [B]:** store `value_per_point` as a **vector keyed by
redemption_route**, each with `{rate, liquidity, min_redemption, expiry, transfer_ratio,
requires_award_availability}`. Default the headline to the **user's declared redemption
behaviour**, not the theoretical max — **best-case transfer value as the headline is the single
biggest source of inflated "you'll earn ₹X" claims.** Show
`conservative (cashback) / realistic (declared) / ceiling (best transfer)`. Deduct fees, apply
expiry, and **never count a milestone the user isn't on pace to hit.**

---

## 6. Document & email ingestion

### 6.1 What Indian fintechs actually do

**Jupiter is the only one that publishes its actual Gmail query, and it's the most useful
finding in this section [A]:**

```
subject:"Consolidated Account Statement - CAMS Mailback Request"
  or subject:"Consolidated Account Statement - KFINTECH Mailback Request",
newer_than:7d
```

**Jupiter's portfolio analyser is a CAS-mailback harvester, not a general inbox scraper.** It
narrows to two sender/subject patterns and gets a *structured statement*, rather than parsing a
thousand bespoke email formats. Jupiter states "read only" access, adherence to the Google API
Services User Data Policy including Limited Use, and "We don't allow humans to read the data,
unless" (consent/security/legal). **[A]** https://jupiter.money/privacy-policy-detail-2/

Others **[A]**: CRED — "We restrict our email reading to those related to financial services…
we do not access any personal emails"; INDmoney — "read-only, secure, automated and limited to
financial data" via OAuth, no ad use; CheQ — holds **CASA (Cloud Application Security
Assessment) certification** to access email accounts, and **outsources SMS retrieval to third
parties**.

**Channel reliability ranking [A]/[B]:** Account Aggregator > CAS mailback > email/SMS scraping.
Kuvera triggers a CAMS CAS to the user's own email rather than asking for a password **[B]**.
**SMS is a dead end for new entrants** — Google restricts SMS permissions to default handlers
with a narrow exemption list; requires a Permissions Declaration Form **[A]**
https://support.google.com/googleplay/android-developer/answer/10208820

**Password-protected PDFs.** No fintech documents this publicly, but casparser.in states the
rule: **"For all CAS types except KFintech, the password is the investor's PAN number"**
(KFintech = user-defined, 8+ chars) **[A]**. Bank statements are vendor-specific — HDFC uses
Customer ID (not DOB), ICICI uses DOB/PAN-fragment combos, SBI netbanking uses account number
while YONO uses DOB **[A]**. **Implication: a per-issuer password-candidate generator, not a
single global rule** — and note this must never become a deterministic password *generator* for
user-facing secrets (see the standing security constraint).

### 6.2 The review-queue finding

> **No evidence was found that any of these apps exposes a user-facing uncertain-extraction
> review queue or per-transaction source traceability.** Jupiter advertises "Jupiter
> auto-categorizes your transactions" with no confirmation step — consistent with silent
> auto-import. **[B — inference from absence, not proven.]**
>
> **This is a differentiation opportunity, not a pattern to copy.** Wealth OS already has
> `EmailReviewItem` + `ReviewStatus` + the 0.85 confidence gate.

### 6.3 Pipeline architecture

**Stage 0 — cursor.** Gmail `watch` + Pub/Sub with `historyId`; re-call `watch` at least every
7 days. **`historyId` is valid only for a limited window — if too much time passes,
`history.list` returns 404 and you must full-resync.** **[A]**
https://developers.google.com/workspace/gmail/api/guides/push
*(Wealth OS already implements this, including the 404 → mandatory-full-sync path.)*

**Stage 1 — classify before extracting.** Standard practice **[A]**. Use a **cheap cascade**,
not an ML classifier first: (1) sender domain + DKIM-verified domain; (2) subject regex;
(3) attachment filename/MIME + PDF producer metadata; (4) first-page text fingerprint;
(5) small-model classifier **only as fallback**. Output `(doc_type, issuer, confidence)`.
**Jupiter's published query is exactly this, collapsed to steps 1–2.**

**Stage 2 — table extraction.** 2026 consensus: **pdfplumber** is the safest default (actively
maintained, widest layout coverage); **Camelot** wins on bordered "lattice" tables; **Tabula**
is the zero-config option. **All three fail on scanned PDFs.** **[A]** Indian bank statements
are overwhelmingly text-layer with ruled tables, so **Camelot lattice + pdfplumber fallback
covers most of the corpus at zero marginal cost [B]**.

Managed services, directional **[A]**: AWS Textract **+Tables ~$15/1k pages**; Google Document
AI $1.50–30/1k; Azure prebuilt ~$10/1k. Reported accuracy Textract ~84.8% on complex tables,
Azure ~87% on line items. **None are tuned for Indian layouts; at ~85% you still need a review
queue, so paying $15/1k to skip a free pdfplumber pass is poor economics.**

**Stage 4 — LLM extraction.** Use **schema-constrained decoding** (OpenAI reported 100% schema
adherence with `strict: true` **[A]**). **But schema conformance is not correctness.**

> **The single most important finding for our confidence design: token logprobs are a poor
> confidence signal for extraction.** On DocILE (55-field invoice benchmark where frontier LLMs
> fail on 26% of fields), logprob-mean achieves only **0.705 ROC AUC**, because errors often
> come from causes the model cannot observe — unreadable source, OCR noise — and *"a frontier
> LLM confidently transcribing OCR noise produces high log-probabilities for a wrong answer."*
> **[A]** https://arxiv.org/abs/2606.24420
>
> **Use multi-signal confidence instead:** cross-field arithmetic validation (do debits +
> credits reconcile to the closing balance?), agreement between the deterministic parser and
> the LLM, self-consistency across passes, and grounding checks.

**Grounding is mandatory** for traceability: require the model to emit a **verbatim source
span** (page/bbox where available) for every extracted value, then **mechanically verify that
span exists in the source text**. *"LLMs cannot cite code they haven't seen, regardless of
plausibility"* **[A]**. **This one mechanism gives hallucination defence and user-facing "which
email produced this" for free** — and it is precisely what "EVERY FINANCIAL NUMBER MUST BE
TRACEABLE" requires.

**Don't build a generic extractor for CAS.** Use **`casparser`** (MIT, Python) — parses CAMS +
KFintech detailed/summary and NSDL/CDSL demat eCAS, returns Pydantic models. **[A]**
https://github.com/codereverser/casparser — **Reserve the LLM pipeline for the long tail.**

### 6.4 Identity — two distinct identities, not one

**1. Ingest identity (idempotency):** deterministic, content-derived. Stripe's model — compare
incoming parameters to the original and error if they differ; cache results including errors
**[A]**. TigerBeetle: the transfer `id`'s *"primary purpose… is to serve as an 'idempotency
key'"* **[A]**. For us: `SHA-256(gmail_message_id ‖ attachment_hash ‖ row_index)`. **This
dedupes re-processing, nothing more.**

**2. Economic identity (the real problem):** the same trade arriving via contract-note PDF
*and* broker email. **Content hashing cannot solve this — the bytes differ.** The established
answer is a **composite natural key with tolerance windows**:
`(account, instrument, direction, quantity, amount ± ε, date ± N days)`.

> **Where a strong external reference exists — UTR for UPI, ISO 20022 `EndToEndId`
> (*"passed on, unchanged, throughout the entire end-to-end chain"*), `TxId`, SWIFT `UETR` —
> prefer it and skip fuzzy matching entirely. Indian rails give you UTR/RRN in many emails;
> harvest it aggressively, it's the highest-value field on the page.** **[A]**

**Reconciliation vocabulary to adopt [A]:** **matched / unmatched / partially-matched** (one
deposit covering several ledger entries) **/ exceptions** ("breaks"), with exceptions
categorised by type — timing difference, amount variance, unidentified transaction.
Representative confidence banding: **≥95 auto-match; 85–94 auto-match but flag; 70–84 route to
human; <70 treat as genuinely different.** Also borrow **matching vs reconciliation** as
distinct controls: matching is pre-commit and blocking; reconciliation is post-commit and
comparative.

**Plaid's pending→posted model is the canonical precedent for "the same event, restated."** A
posted transaction carries `pending_transaction_id`; the transition is the pending ID in
`removed` plus the posted one in `added` — and *"in some rare cases, Plaid will fail to match a
posted transaction to its pending counterpart."* **[A]** Two lessons: **model supersession
explicitly as a link**, and **accept that matching sometimes fails — design the failure path.**

**Event sourcing — the honest tradeoff [A].** Double-entry requires every posting to net to
zero, *"which turns tampering into an arithmetic alarm"*; corrections are appended as
compensating postings linked to the original. **But** Azure's Architecture Center is blunt about
the costs: the event store is poor for querying (you need maintained projections), and
projection code becomes effectively immutable — *"even a simple bug fix can cause a butterfly
effect as it runs against every transaction since the beginning of time."*

> **The pragmatic guidance, which we should follow: event sourcing needn't be all-or-nothing.
> Apply it to the parts that benefit (the payment ledger) and use CRUD where the complexity
> isn't justified.**

---

## 7. Portfolio analytics: rebalancing, risk, benchmarks

### 7.1 Rebalancing

Vanguard's *Best practices for portfolio rebalancing* (1926–2009 US data): risk-adjusted
returns are "not meaningfully different" across monthly/quarterly/annual, but event count and
cost rise sharply → **annual or semiannual monitoring with 5% thresholds. [A]**
Vanguard's *The Rebalancing Edge* (Dec 2024): **threshold beats calendar** on a risk-adjusted
basis — 15–22bp/yr accumulation, 22–25bp decumulation vs monthly — recommending a
**200/175bp trigger/destination** rule. **[A]**

**Kuvera is the one Indian platform with an explicit position [A]:** allow "5% deviation to
model allocation," and "Deviation based rebalancing is far superior to time-based
rebalancing." Its UI has a Target column with inline rebalance tips.

**[B]:** INDmoney shows drift (market-cap/sector/equity-vs-liquid look-through) but **no trigger
engine**; Zerodha Console has none; Value Research/Morningstar are fund analytics, not
allocation tools.

> **Gap: nobody in India ships tax-aware rebalancing (STCG/LTCG, ₹1.25L exemption, exit
> loads), and cash-flow rebalancing — steering new SIPs to underweights — dominates sell-side
> rebalancing for Indian retail. [B]**

(Bogleheads' 5/25 rule — rebalance when an asset class drifts 5 absolute pp *or* 25% relative,
whichever first — is well-established but the source URL 403'd; **cite carefully**.)

### 7.2 Risk metrics — and an honest assessment of which are noise

**Value Research publishes the widest set [A]** (all trailing 3yr monthly): mean, SD, variance,
R², beta, alpha, Sharpe, Treynor, Sortino, information ratio, covariance, upside/downside
capture, downside risk, max drawdown, max gain.
https://www.valueresearchonline.com/statistical-variables-methodology/
Morningstar India shows alpha, beta, R², SD, Sharpe, upside/downside capture **[A]**.
**Kuvera and INDmoney show essentially none at portfolio level [B].**

**Assessment for retail [B] — this drives what we should actually display:**

| Tier | Metrics | Why |
|---|---|---|
| **Meaningful** | **Max drawdown**, downside capture, rolling-return consistency | Max drawdown is the only metric that maps to a *felt* experience: "you'd have been down 38%" |
| **Conditionally useful** | SD (only as drawdown's input), Sharpe (**only within a category, never across asset classes**) | |
| **Mostly noise** | alpha / beta / R² on a 3yr monthly window; Treynor; covariance; information ratio | **36 observations → standard errors so wide the point estimate is uninformative.** And for a diversified equity fund R² ≈ 0.95, making **beta ≈ 1 tautologically** |
| **Theoretically better, practically unstable** | Sortino | Better than Sharpe in principle, but 36 monthly observations leave too few downside points to estimate stably |

### 7.3 Benchmarks — three incompatible "peer groups"

SEBI circular **SEBI/HO/IMD/IMD-II DF3/P/CIR/2021/652 (27 Oct 2021)** created the two-tier
structure: tier-1 "reflective of the category of the scheme," tier-2 "demonstrative of the
investment style/strategy of the Fund Manager within the category" (optional). Tier-1-only:
hybrid, solution-oriented, thematic/sectoral, index/ETF, single-underlying FoF, "other."
**All benchmarks must be Total Return Indices.** Effective 1 Dec 2021 (debt) / 1 Jan 2022. **[A]**

TRI was separately mandated by **SEBI/HO/IMD/DF3/CIR/P/2018/04 (4 Jan 2018)**, effective
1 Feb 2018, replacing price-return indices — **[B]** which structurally raised the benchmark bar
and cut reported alpha across Indian equity funds.

**AMFI publishes the authoritative tier-1 mapping table [A]** — circular 35P/MEM-COR/72/2021-22
(2 Dec 2021) for equity/thematic/sectoral; 35P/MEM-COR/131/2021-22 (31 Mar 2022) for open-ended
debt mapped to the **Potential Risk Class matrix**; plus incremental additions.
https://www.amfiindia.com/circulars — **This is the table to ingest, not scraped factsheets.**

Value Research assigns its own peer groups via **Fund Classification — "classifies funds
strictly based on their portfolio make-up"** **[A]**, i.e. holdings-based, not the AMC's stated
mandate. Morningstar does the same via Categories.

> **Consequence for our build [B]: three different "peer group" answers exist for any fund —
> SEBI category, AMFI tier-1 benchmark, and the holdings-derived VR/Morningstar category — and
> they disagree, most visibly for flexicap, multicap and BAF funds.**
> **Recommendation: store all three. Default to AMFI tier-1 for regulatory-comparable display,
> holdings-derived for peer ranking.**

---

## 8. Indian data sources — field report (probed live 2026-09-15 from a datacenter IP)

### 8.1 The NSE trap

| Probe | Result |
|---|---|
| `www.nseindia.com/` (browser UA) | **HTTP 403 — Akamai "Access Denied"** |
| `www.nseindia.com/api/quote-equity?symbol=RELIANCE` with cookie bootstrap | **403** |
| `nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_...csv.zip` | **200, 204 KB** |
| `nsearchives.../content/equities/EQUITY_L.csv` | **200, 182 KB** (full equity master) |
| `nsearchives.../content/indices/ind_nifty500list.csv` | **200, 32 KB** |

All **[A]**.

> **Critical operational fact: `www.nseindia.com` (the JSON APIs — quotes, announcements,
> shareholding, bulk/block deals, option chain) is Akamai-fenced and blocks cloud/datacenter
> IPs outright. Cookie-priming does not help. `nsearchives.nseindia.com` (bhavcopies, masters,
> index lists, corp-action archives) is open.**
> **Plan for: archives from the server, JSON APIs only from a residential IP.**
> (Whether the APIs work from an *Indian residential* IP is **unverified** — the 403 may be
> geo+datacenter combined.)

**Library status [A]:** `nsepy` **dead** (0.8, 2020-03-07 — do not use). **`jugaad-data`
0.35.5 (2026-08-25) — actively maintained, best choice**, with built-in caching to avoid
blocks. `nselib` 2.5.1 maintained. `nsepython` semi-active, ships a "server edition"
specifically because of IP blocking.

### 8.2 BSE — quietly the friendlier exchange

`api.bseindia.com` returns **200 JSON** for LODR Reg-30 announcements with PDF attachment names,
and `ListofScripData` returns a **1.8 MB full active scrip master**. **Needs only a browser UA +
`Referer: https://www.bseindia.com/`. No cookie dance, no datacenter-IP block.** **[A]**
Some endpoint names have drifted (302s) and must be re-derived from DevTools.

### 8.3 Screener.in — the best free fundamentals source, and it's a scrape

**No official API [A]** — their own KB: "Though we don't provide APIs on Screener, you can use
the 'Export' option…" CSV export is **Premium-only**.

**But:** unauthenticated fetch of `/company/RELIANCE/` returns **HTTP 200, 228 KB**, containing
`#profit-loss #balance-sheet #cash-flow #ratios #quarters #shareholding #quarterly-shp #peers
#documents`, with annual columns **Mar 2015 → Mar 2026 (12 years)** and ~12 quarters.
Consolidated at `/company/RELIANCE/consolidated/`. **[A]**

**robots.txt disallows only `/user/*`, `/*?q=`, `/*?sort=`, `/*?limit=`, `/*?page=`,
`/company/source/quarter/*` — `/company/<SYM>/` is NOT disallowed.** **[A]**
https://www.screener.in/robots.txt

> **Legal position [B]: robots permits it; ToS is the binding layer and `/terms/` 404'd.
> Treat redistribution as grey. A personal scoring engine at polite rates (1 req/2–3s) is
> plausibly fine; republishing is not. Find the live ToS before any redistribution.**

### 8.4 Mutual fund data — and a live bug in our own code

> **`www.amfiindia.com/spages/NAVAll.txt` now 302-redirects to
> `https://portal.amfiindia.com/spages/NAVAll.txt` (200, 1.5 MB, 18,061 lines), and the header
> is now:**
> `Scheme Code;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;NAV;Date`
> **— the new `Plan`/`Option` columns break old 6-field positional parsers.** **[A]**

This confirms the root cause of the AMFI outage already fixed in this codebase (bind columns by
header name, follow redirects, raise the WebClient codec buffer). **Action: point the client at
`portal.amfiindia.com` directly to avoid depending on redirect-following.**

**mfapi.in [A]** — free, no key: `/mf` (5.7 MB, all schemes), `/mf/search?q=`, `/mf/{code}`,
`/mf/{code}/latest` — full NAV history + ISIN + category. `mftool` on PyPI (3.3, 2026-05-03)
is maintained.

**MF Central / CAMS / KFintech [A]/[B]:** `mfcentral.com` is a consent/OTP-gated investor
portal, no API. CAS statements are PDF and password-protected.

### 8.5 Broker APIs — prices only, none give fundamentals

| Broker | Cost | Historical OHLC |
|---|---|---|
| **DhanHQ v2** | **API free with account** | **Daily = back to inception**; intraday 1/5/15/25/60-min for 5 years, 90 days/request **[A]** |
| **Zerodha Kite Connect** | Personal tier free (orders only, **no data**); **₹500/mo per app** for WS + historical candles **[A]** | minute→60minute + day, "back several years" |
| Upstox | Free with account | 1-min & 30-min **~6 months**; daily **1 year** — **weak for backtests [A]** |

**Best combo: Dhan (free, inception-to-date daily) + Kite (₹500/mo) as backup.** This confirms
the earlier correction — Dhan, not Account Aggregator, is the highest-value data integration.

### 8.6 Analyst estimates — confirmed not free

**No free source exists for Indian consensus estimates.** Trendlyne/Tickertape/MarketsMojo
license from vendors and gate it; Yahoo Finance India estimates are sparse/stale for mid and
small caps. Real access = Refinitiv I/B/E/S, Bloomberg, CapIQ, or Ace/Accord — ₹lakhs to
$20–30k/user/yr. **[B]/[C]**

> **Design consequence: drop forward P/E and PEG from v1.** Build on trailing/TTM metrics plus
> historical growth (3/5/10y CAGR of EPS/revenue from Screener). **A "PEG" using *historical*
> growth is defensible if labelled honestly.**

### 8.7 Corporate actions

Free and structured **[A]/[B]**: BSE corporate-action API (works from datacenter IPs) and NSE
corporate-action archive CSVs on `nsearchives`. **Kite/Dhan historical candles are already
split/bonus-adjusted** — verify per broker before trusting it.

---

### 2.5 Simply Wall St — the most transparent scoring model found

Unlike Morningstar (which blocked every fetch — see §11) Simply Wall St documents its model
completely. **Snowflake: five axes — Valuation, Future Growth, Past Performance, Financial
Health, Dividend — with exactly 6 binary checks per axis, pass = 1, fail = 0, so each axis
scores 0–6.** Explicitly not a buy/sell signal. **[A]**
https://support.simplywall.st/hc/en-us/articles/360001740916

| Axis | The six checks |
|---|---|
| **Valuation** | price below fair value; price ≥20% below fair value; multiple vs peer average; multiple vs industry average; multiple vs "Fair Ratio"; price ≥20% below analyst target **with consensus dispersion under 15%** |
| **Financial Health** (non-financials) | ST assets > ST liabilities; ST assets > LT liabilities; D/E < 40%; D/E flat-or-falling; operating cash flow > 20% of total debt; EBIT ≥ 5× interest |
| **Financial Health** (financials — a *separate* six) | assets < 20× equity; bad-loan provision > actual write-offs; deposits > 50% of liabilities; net loans < 110% of assets; loans < 125% of deposits; net charge-off ratio < 3% |
| **Future Growth** | earnings growth > savings rate + inflation; > country-market weighted average; > 20%; revenue growth > market; revenue growth > 20%; forecast ROE in 3 years > 20% |
| **Past Performance** | unusual items < 20% of EBT or accruals ratio < 20%; net margin up YoY; earnings above 5-years-ago level; current growth > 5-year average; growth > industry; ROE ≥ 20% |
| **Dividend** | yield above bottom 10% of market (**gating — failing it disqualifies the whole axis**); above bottom 25%; 10-year stability; 10-year growth (**auto-fail if <10 years of history**); yield in market top 25%; earnings payout 0–90%; cash-flow payout 0–90% |

Valuation model: **2-stage DCF on levered free cash flow to equity, 10-year forecast, Gordon
Growth terminal value using the 5-year average long-term government bond rate as the perpetual
growth rate.** Variants: DDM for dividend payers, Excess Returns for financials, AFFO 2-stage
for REITs. Multiple selection: P/E if profitable and P/E<150, else P/S for growth/early-stage,
P/B for financials. Data from **S&P Global Market Intelligence**. **[A]**

**Three things worth taking [A]/[B]:**
- **Binary checks, not continuous scores.** Six pass/fail tests per axis are auditable and
  explainable in a way a weighted z-score composite never is. "Failed 4 of 6 financial-health
  checks, here they are" is a *reason*, which is what our constraint demands.
- **A separate check-set for financial institutions**, exactly as Value Research does. Two
  independent sources converging on this makes it non-optional for us.
- **Gating checks that disqualify an entire axis** (the dividend yield floor) and
  **auto-fails on insufficient history** — a structured version of "refuse to answer."

**Their disclosed cost-of-equity construction is a genuine gap [A — confirmed absent]:** the
discount rate / beta derivation is not documented in the valuation article. And the platform
deliberately shows **up to three different fair values** — SWS's model, the user's own, and the
most popular *Community* value — as differing perspectives rather than one truth. **[A]**

### 2.6 Factor construction — measured evidence, not opinion

This section rests on an **original computation** run against Ken French's data library
(`F-F_Research_Data_5_Factors_2x3` + `F-F_Momentum_Factor`), monthly, **Jul 1963 – Jul 2026,
n = 757**. Reproducible. **[A]**

**Measured factor correlation matrix:**

|  | Mkt-RF | SMB | HML | RMW | CMA | UMD |
|---|---|---|---|---|---|---|
| **Mkt-RF** | 1.00 | 0.27 | −0.21 | −0.19 | −0.36 | −0.16 |
| **SMB** | | 1.00 | 0.02 | −0.33 | −0.08 | −0.08 |
| **HML** | | | 1.00 | 0.09 | **0.68** | −0.19 |
| **RMW** | | | | 1.00 | 0.02 | 0.05 |
| **CMA** | | | | | 1.00 | −0.02 |
| **UMD** | | | | | | 1.00 |

Annualised return / vol (Sharpe): Mkt-RF 7.19% / 15.45% (0.47) · SMB 2.25 / 10.47 (0.21) ·
HML 3.59 / 10.28 (0.35) · RMW 3.08 / 7.91 (0.39) · CMA 2.96 / 7.18 (0.41) · UMD 7.25 / 14.56 (0.50).

**The single most important number is HML–CMA = 0.68** — value and conservative-investment are
near-proxies, which is the mechanical driver of Fama-French's own §7 finding, titled *"HML: a
redundant factor"*: *"in the five-factor model, HML is redundant for describing average
returns, at least in U.S. data for 1963–2013"* (intercepts −0.04%, t = −0.47). **[A]**
https://tevgeniou.github.io/EquityRiskFactors/bibliography/FiveFactor.pdf

> **The near-orthogonal trio is RMW / CMA / UMD** — pairwise 0.02, 0.05, −0.02. For a
> fundamentals + technicals build, quality-ish (RMW), investment discipline (CMA) and momentum
> (UMD) are the cleanest near-independent axes. **Adding a classic B/M value factor buys very
> little beyond CMA. [B]**

**Measured diversification payoff — equal-weight blends, same sample [A]:**

| Blend | Sharpe |
|---|---|
| HML alone | 0.35 |
| UMD alone | 0.50 |
| **HML + UMD** (corr −0.19) | **0.67** |
| **HML + CMA** (corr **+0.68**) | **0.41 — zero gain** |
| RMW + CMA + UMD | 0.73 |
| All four | 0.76 |

**This is the whole orthogonality argument in one table: adding a *correlated* factor bought
nothing; adding a *negatively correlated* one nearly doubled Sharpe.** It is the direct
quantitative case against our current model, whose momentum-family indicators are pairwise
ρ>0.9.

**Correlation instability — the finding that settles the weighting method [A, computed]:**

| pair | 1963–1999 | 2000–2026 |
|---|---|---|
| HML–CMA | +0.75 | +0.62 |
| HML–UMD | −0.21 | −0.19 |
| **HML–RMW** | **−0.33** | **+0.33** |
| **RMW–CMA** | **−0.35** | **+0.26** |
| SMB–RMW | −0.21 | −0.44 |

**HML–RMW and RMW–CMA flip sign entirely between subperiods.** Only HML–CMA and HML–UMD are
stable.

> **This is a strong argument against PCA / Gram-Schmidt orthogonalisation or
> covariance-optimised factor weights in a personal system: the covariance matrix you estimate
> is not the one you will live with. It argues for fixed, equal-weight bucket blending. [B]**

AQR reached the same conclusion for the same reason — QMJ footnote 3, verbatim: *"we can
cho[o]se to orthogonalize each factor… in a potential nightmare of choices and dimensionality,
or to construct our factors more simply allowing some correlation among them. We choose the
latter."* **[A]** MSCI, S&P and AQR all converge on **rank → z-score → average within bucket →
equal-weight buckets**, not optimisation. **[A]**

**Value–momentum [A]:** Asness, Moskowitz & Pedersen, "Value and Momentum Everywhere," *JF*
2013 — *"the correlation between value and momentum returns is strongly negative, averaging
about –0.60"* across individual-stock markets. AQR's "Our Model Goes to Six" argues FF's HML is
effectively *"an 80/20 portfolio of timely value and momentum"* because it uses lagged prices —
rebuild with current prices (HML-DEV) and value is significant again. **[A]**

**Signal blending vs portfolio blending [B]:** the mainstream practitioner recommendation is
*signal blending* (one composite score per stock) over *portfolio blending* (separate sleeves),
because mixing sleeves lets a cheap-but-junk stock offset an expensive-but-quality one, diluting
net exposure. (Ghayur–Heaney–Platt, *FAJ* 2018, 403'd — **magnitude unverified**.)

### 2.7 Quality — the exact published definitions

**MSCI Quality Index — exactly three descriptors [A]**
https://www.msci.com/eqb/methodology/meth_docs/MSCI_Quality_Indexes_Meth_June2017.pdf

1. **ROE** = trailing-12-month EPS ÷ latest book value per share
2. **Debt-to-Equity** = latest fiscal-year total debt ÷ book value
3. **Earnings Variability** = standard deviation of YoY EPS growth over the last **5 fiscal years**

Process: **winsorise at 5th/95th percentile** within the parent index → z-score → **negate** the
D/E and Earnings-Variability z-scores → average the three. **If ROE is missing the security is
excluded entirely**; missing D/E or Earnings Variability just drops that term.
**This is the cheapest defensible quality factor in existence — three ratios.**

**AQR QMJ — the deep version [A]** (Asness, Frazzini & Pedersen, *RAST* 2019, Appendix A1).
Quality = z(Profitability) + z(Growth) + z(Safety) + z(Payout). Standardisation is
**rank-then-z-score**, not raw-value z-scoring — which handles fat tails.

- **Profitability** = z(GPOA, ROE, ROA, CFOA, GMAR, ACC), where GPOA = (REVT−COGS)/AT;
  ACC = −(DP − ΔWC)/AT
- **Growth** = the same six measures over **5 years**, each as *change in numerator ÷ lagged
  denominator*
- **Safety** = z(BAB, IVOL, LEV, O-Score, Z-Score, EVOL); EVOL = SD of quarterly ROE over the
  past **60 quarters**, min 12 non-missing
- **Payout** = z(EISS, DISS, NPOP)

Conditional double sort (size, then quality), 2×3, value-weighted, **monthly** rebalance, long
top 30% / short bottom 30%. **Positive in 23 of 24 countries.**

**QMJ internal correlations (Table V, US 1956–2012) [A]:** QMJ–Profitability 0.82,
QMJ–Safety 0.88, **QMJ–Growth 0.24**, QMJ–Payout 0.69; Profitability–Safety 0.64,
**Safety–Growth 0.15**, **Growth–Payout −0.34**.
**[B] Even inside "quality," the sub-components are far from independent — Growth is the odd one
out and the only near-orthogonal leg.**

**Novy-Marx gross profitability [A]** — *JFE* 2013. **GP/A = (REVT − COGS) / AT.** His verbatim
argument: *"Gross profits is the cleanest accounting measure of true economic profitability. The
farther down the income statement one goes, the more polluted profitability measures become."*
A firm spending on advertising, R&D or organisational capital — all *optimal* value-creating
actions — shows **lower** earnings and ROE than a less profitable competitor. Gross profit sits
above those lines.

Spearman rank correlation of GP/A with B/M = **−18%**: profitability is mechanically a *growth*
tilt and value a *low-profitability* tilt — **which is exactly why they combine well.** The
50/50 profitability+value strategy has t = 5.87 and **annual Sharpe 0.85, 2.5× the market's
0.34**, and is *"orthogonal to momentum."*

**Piotroski F-Score — 9 binary signals [A].** *Profitability:* ROA > 0; CFO > 0; ΔROA > 0;
**CFO > ROA** (accrual quality). *Leverage/liquidity:* Δ LT-debt/avg-assets < 0; Δ current ratio
> 0; no new equity issued. *Operating efficiency:* Δ gross margin > 0; Δ asset turnover > 0.

**Red-flag screens [A].** **Altman Z** = 1.2·WC/TA + 1.4·RE/TA + 3.3·EBIT/TA +
0.6·MktEquity/TotalLiab + 1.0·Sales/TA. **Beneish M** = −4.84 + 0.920·DSRI + 0.528·GMI +
0.404·AQI + 0.892·SGI + 0.115·DEPI − 0.172·SGAI + 4.679·TATA − 0.327·LVGI; **flag if M > −1.78**.
Both are directly available on Screener.in as `zscr` / `fscr` / `cscr`.

> **A trap worth naming [B]: accruals appear independently in Piotroski (CFO > ROA), Beneish
> (TATA), and QMJ (ACC). In a composite you are triple-counting accruals unless you consciously
> assign it one slot.** This is the same class of error as our current momentum over-weighting.

**[C]** Cash conversion (CFO/EBITDA, CFO/NI) has no factor-grade academic validation found —
**treat it as a red-flag screen, not a scored factor.**

### 2.8 Regime overlays — what survives scrutiny

| Overlay | Evidence |
|---|---|
| **Faber 10-month SMA** | **[A]** S&P 1901–2012: arithmetic returns near-identical (11.26% vs 11.22%) but **compound 9.32% → 10.18%** — pure volatility-drag reduction. Max DD 1929–32 **83.7% → 42.2%**; 2000s **44.7% → 16.5%**. Mechanism: below the SMA, returns average **60% lower and volatility 30% higher**. Checked month-end only. **The cheapest defensible overlay.** |
| **Momentum crash protection** | **[A]** Daniel & Moskowitz, *JFE* 2016. A **"panic state"** = negative trailing 2-year market return **×** high market variance. In panics WML beta falls by **0.518 (t = −28.4)** — momentum becomes a written call on the market, *only then*. Sharpe: static WML **0.682** → constant-vol 1.041 → **dynamic out-of-sample 1.194**. Barroso & Santa-Clara (*JFE* 2015) is the simpler cousin: scale by σ_target/σ̂ using 126-day realised variance, 12% annualised target. |
| **General volatility scaling** | **Contested.** Moreira & Muir (*JF* 2017) claim ~25% Sharpe gain; **Cederburg, O'Doherty, Wang & Yan (*JFE* 2020) demolish it out-of-sample** — managed market Sharpe **0.42 vs 0.46** unmanaged; combination loses on certainty-equivalent in **72 of 103** strategies. **Only MOM, ROE and BAB survive.** **[A]** |
| **Valuation-spread factor timing** | **[A]** Asness, "Siren Song," *JPM* 2016 — valuation-spread timing is *"quite weak historically (and whatever power they have is too highly correlated to the simple value factor itself)"*. Even in 2020, with the value spread at the **100th percentile, 66% wider than the GFC peak**, Asness refused the timing call. |
| **Hindenburg Omen** | **[A]** ~**20% hit rate / 80% false positives**; no peer-reviewed validation found. **Skip it.** |

> **The conclusion that matters for our design [A]/[B]: regime-modulating a *stock-level*
> signal is well-supported ONLY for momentum. Cederburg et al. is direct evidence against
> assuming it generalises. A 200DMA/10-month market filter is defensible; scaling every factor
> by realised vol is not.**

---

## 9. Feature disposition — COPY / IMPROVE / DIFFERENTIATE / REJECT

Per the spec, every competitor feature studied is classified with reasoning.

### COPY THE CONCEPT

| Feature | Source | Reasoning |
|---|---|---|
| **Refuse-to-answer on thin data** | Zerodha (suppresses XIRR < 1yr), Value Research (5 published rating exclusions) | Both market leaders **decline to produce a number** rather than produce a misleading one. This is the single most aligned-with-our-constraints behaviour found. We already do it in `SignalEngine` (`limitedBy("NOT_SIZED")`) — generalise it. |
| **Cheap classification cascade before extraction** | Jupiter's published Gmail query | Sender domain → subject regex → attachment metadata → fingerprint → model last. Near-zero cost, near-perfect precision on the head of the distribution. |
| **`casparser` for CAS** | codereverser/casparser (MIT) | Do not build a generic extractor for a format someone already parses correctly. Reserve the LLM for the long tail. |
| **Reconciliation vocabulary** | ReconArt / industry standard | matched / unmatched / partially-matched / exception, with exceptions typed (timing, amount variance, unidentified). Adopt the words verbatim — they're load-bearing in our 10-status ingestion model. |
| **Supersession as an explicit link** | Plaid `pending_transaction_id` | Model "the same event, restated" as a link, not an overwrite. Directly serves "never overwrite financial records." |
| **Rebalance timeline + historical constituents CSV** | smallcase 2023 transparency release | Full history with dates, downloadable, visible without login. Also: **removal of backtested data from charts** — copy that too. |
| **Methodology block with fixed sub-heads** | smallcase factsheet | *Defining the universe / Research / Screening / Weighting / Rebalance*. A fixed template forces disclosure even when rigour varies. |
| **AMFI tier-1 benchmark mapping** | AMFI circulars | Ingest the authoritative table rather than scraping factsheets. |
| **Multi-signal extraction confidence** | DocILE research | Arithmetic validation + parser/LLM agreement + self-consistency. **Explicitly NOT logprobs.** |

### IMPROVE

| Feature | Source | Our improvement |
|---|---|---|
| **Factor scoring** | Value Research 4-factor (Q25/G20/V35/M20) | Adopt their **dimensions and financials-sector ratio switch**, but decompose further to the 9 dimensions in the spec and **publish per-factor sub-scores**, which VR paywalls and Tickertape gates behind Pro. **Critically: fix the multicollinearity problem** — RSI/CCI/Stochastic/ROC/MACD-line are pairwise ρ>0.9, so naive per-indicator averaging over-weights momentum ~4:1. |
| **Uncertainty-scaled conviction** | Morningstar Uncertainty Rating | Their insight — the margin of safety required *scales with uncertainty* — is right and absent everywhere in India. Improve by driving it off **data quality we can actually measure** (bars available, parser confidence, earnings-history length) rather than analyst judgement we don't have. |
| **MF overlap** | Advisorkhoj (count-based), Tickertape (MF-only, 2–5 funds) | Use **weighted Σ min(wᵢ) not counts**, state the methodology (Advisorkhoj publishes none), and extend to the pairs nobody covers (§below). |
| **Card recommendation** | FinArt (best in market) | FinArt has cap-aware rupee ranking **and** SMS parsing but hasn't joined them. **Join them**: observed spend → live counters → marginal-value ranking. |
| **Portfolio concentration alert** | Zerodha's new 50% single-stock/sector nudge | Right idea, blunt threshold. Improve with **look-through** (direct stock + MF holdings + smallcase constituents combined), which is exactly what concentration means for our user. |
| **Point valuation** | All card tools | Vector keyed by redemption route; headline defaults to the user's **declared** behaviour, with conservative/realistic/ceiling shown. Nobody does this honestly. |
| **Benchmark comparison** | INDmoney (XIRR vs Nifty return) | Compute **benchmark XIRR under the user's own cash-flow schedule**. Their comparison is apples-to-oranges; ours would be correct. |

### DIFFERENTIATE (unoccupied space)

| Feature | Evidence it's unoccupied |
|---|---|
| **Net-worth attribution waterfall** | §1.1 — nobody ships it. Blocker is transfer-pair detection, which `LedgerTransfer` already solves. **Highest-value differentiator found.** |
| **Direct stocks ↔ MF overlap** | §3.5 — Tickertape is MF-only; smallcase has nothing; nobody joins direct equity to fund look-through. Our user holds both. |
| **smallcase ↔ smallcase and smallcase ↔ MF overlap** | §4 — verified absent from smallcase, and structurally unlikely to appear. Constituents are public via rebalance CSVs. |
| **User-facing extraction review queue with source traceability** | §6.2 — no evidence any Indian app offers one. We already have `EmailReviewItem`. |
| **Grounded source spans on every extracted number** | §6.3 — mandatory for "EVERY FINANCIAL NUMBER MUST BE TRACEABLE"; not seen in any competitor. |
| **Marginal-value card engine with live counters** | §5.4 — the whitespace FinArt left. |
| **Tax-aware rebalancing** | §7.1 — "nobody in India ships tax-aware rebalancing." |
| **Versioned, provenance-stamped, publicly-dated card-terms changelog** | §5.1 — TechnoFino's forum is the **only** structured change-tracking in the market. Cheap differentiation and a trust moat. |

### REJECT

| Feature | Source | Reasoning |
|---|---|---|
| **Stored bank credentials** | MaxRewards | Split-key reversible encryption is plaintext replay. In India this is worse than the US: conflicts with RBI's customer-liability framing, sits outside sanctioned AA rails, violates issuer netbanking T&Cs. **Use AA consent artefacts, or nothing.** |
| **Full-mailbox Gmail scope** | SaveSage | Users object publicly. Restrict to specific senders; **Jupiter's narrow query is the model.** |
| **Commission-ranked "recommendations"** | Paisabazaar / BankBazaar | Approval-probability × commission is a different objective function from reward maximisation. BankBazaar even sells a co-branded card. If we ever monetise via affiliate, **separate the optimisation surface from the acquisition surface and disclose the ranking basis.** |
| **Fed model / BEER as a predictive signal** | Yardeni; Canara Robeco et al. | Empirically rejected (§2.4): E/P − Y forecasts at adj. R² 1.4–11.9% vs 29.6–34.9% for E/P alone. Fine as a *descriptive* chart, never as a score input. |
| **Forward P/E and PEG in v1** | Tickertape, Trendlyne | Data isn't obtainable free (§8.6). Shipping it would mean fabricating or scraping stale estimates. |
| **Best-case point valuation as the headline** | Most card tools | "The single biggest source of inflated 'you'll earn ₹X' claims." |
| **Alpha/beta/R²/Treynor/information ratio as headline risk metrics** | Value Research, Morningstar | 36-observation windows make them uninformative; R²≈0.95 makes beta≈1 tautologically (§7.2). Compute if cheap, **don't lead with them.** |
| **Naive historical-multiple percentile as a standalone valuation verdict** | Koyfin, Screener, Simply Wall St | Assumes stationarity; silent on *why* a stock de-rated; and the timing literature (Asness et al.) shows look-ahead contamination in exactly this normalisation. **Usable only as one input, computed point-in-time.** |
| **Sentiment at 30% weight** | Our own current `AnalystService` | VR — the most methodologically transparent Indian rater — does not use sentiment as a rating input at all. |
| **Account Aggregator as a near-term roadmap item** | Earlier recommendation in this project | **Retracted.** Regulated-entity gate, ₹5–25 lakh first-year, no hobbyist tier, and credit cards excluded entirely (§1.4). |
| **SMS parsing as a new channel** | axio, CheQ | Google restricts SMS permissions to default handlers with a narrow exemption; a Permissions Declaration Form is required. Dead end for a new entrant. |

---

## 10. India tax rules — implementation-critical, current as of 15 Sep 2026

Primary reference to implement against: **AMFI Tax Regime for Mutual Funds** —
https://www.amfiindia.com/investor/knowledge-center-info?zoneName=TaxRegimeForMutualFunds

### Equity / equity-oriented MF (transfers on or after 23 Jul 2024)

| Rule | Value |
|---|---|
| Long-term threshold | **> 12 months** **[A]** |
| **STCG** | **20%** (s.111A) — **up from 15%** **[A]** |
| **LTCG** | **12.5%** (s.112A), **no indexation**, plus surcharge + 4% cess **[A]** |
| **Exemption** | **₹1,25,000 — not ₹1 lakh** **[A]** |

> **CRITICAL: the ₹1.25L exemption is a per-financial-year aggregate across *all* s.112A gains**
> (listed equity shares + equity-oriented MF + business trust units) **for the assessee — not
> per transaction, not per scheme, not per folio.** **A per-redemption tax calculation is
> wrong.** **[A]**

Budget 2026 (1 Feb 2026) left LTCG at 12.5% and the ₹1.25L limit unchanged for FY 2026-27. **[A]**

### Debt / "specified mutual funds"

- Indexation is **gone**. **[A]**
- Units acquired **on/after 1 Apr 2023** → s.50AA deems gains **short-term regardless of
  holding period** → **slab rates**. **[A]**
- Units acquired **before 1 Apr 2023** → **> 24 months → 12.5% without indexation** (s.112,
  post-23 Jul 2024); ≤ 24 months → slab. **[A]**

> **FLAG — the definition changed and this is the most error-prone rule in the set.**
> "Specified mutual fund" was originally *≤35% in domestic equity*; it is now framed as a fund
> investing **more than 65% of total proceeds in debt and money market instruments**,
> applicable from **FY 2025-26**. This re-classifies some hybrid/FoF products.
> **Verify against the Finance (No.2) Act 2024 text before coding the classifier.**

### Exit load

**Not standardized [A].** Each scheme sets its own, disclosed in the SID/KIM. The dominant
equity convention is **1% if redeemed within 365 days, nil thereafter**, with many funds
exempting ~10% of units per year **[B — strong, widely reported]**.
**Store exit load as per-scheme data. It is not a constant.**

### Grandfathering, surcharge and cess — the parts that make a bare rate wrong

- **Grandfathering still applies [A]:** cost = **higher of (actual cost, FMV on 31 Jan 2018)**,
  capped at the sale price.
- **Surcharge on s.112A LTCG is capped at 15%**, and **4% health & education cess applies on
  top. [A]** **Do not quote a bare 12.5% / 20% as the final number for high-income users.**

### STRUCTURAL CHANGE — statutory renumbering effective 1 April 2026

> **The Income-tax Act, 2025 replaces the 1961 Act effective 1 April 2026, with capital-gains
> provisions renumbered: Section 111A → Section 196, Section 112A → Section 198. [A]**
> https://taxguru.in/income-tax/capital-gains-income-tax-act-2025-tax-period-2026-27.html
>
> **Rates carry over unchanged**, but any hard-coded section reference or user-facing copy
> citing "112A"/"111A" is now stale. **Store the section reference as data, not a literal.**

### Exit load — mechanics beyond the rate

**[A]:** SEBI caps exit load at **2%**, and the load is credited **back into the scheme**, not
to the AMC. **[B]:** loads vary per SID (some use 90/365 days, tiered %, or free-redemption
limits of 10–12% of units/year), so **store exit-load rules per scheme and apply FIFO at the
unit-lot level — each SIP instalment has its own allotment date.**

### Tax-loss harvesting

Widely practiced **[A]**. **Zerodha Console** has a dedicated TLH report; **Kuvera** ships a Tax
Harvesting feature. **[B]:** these mostly *surface candidates* rather than automate execution.

> **Note: Kuvera's own tax-harvesting page — and several live Zerodha pages — still cite the
> stale ₹1 lakh figure. Do not copy competitor tax numbers; go to AMFI or the Act.** **[A]**

**The Indian practice is distinctive and inverts the US default [A]/[B]:** the dominant play is
**tax-*gain* harvesting** — realising LTCG *up to* ₹1.25 lakh tax-free and immediately rebuying
to step up cost basis — not US-style loss harvesting. **India has no wash-sale rule**, which is
what makes same-day sell-and-rebuy viable. Kuvera quantifies the benefit at up to **₹15,625/yr**
(= ₹1.25L × 12.5%) and advises same-day round-trip to minimise NAV impact.
**[B] — verify the no-wash-sale position independently before shipping; it is the load-bearing
assumption of the whole feature.**

### Recommended "should I book profit?" computation [B]

**Never show gross gain.** Per unit-lot:

```
1. FIFO-split units into lots; per lot get allotment date, cost
   (grandfathered to max(cost, FMV_2018-01-31) if pre-01-Feb-2018)
2. Classify each lot LT (>12m) or ST
3. exit_load  = units × NAV × load_rate(days_held, scheme SID)
4. gross_gain = units × NAV − cost − exit_load      ← load reduces proceeds, so reduces gain
5. ST tax = ST_gain × 20%
6. LT tax = max(0, LT_gain − remaining_FY_exemption) × 12.5%
       where remaining_FY_exemption = 125000 − FY-to-date LTCG already realised   ← running counter
7. add surcharge (capped 15%) + 4% cess
8. net_proceeds = units × NAV − exit_load − total_tax
```

Surface three numbers — **gross proceeds, total drag (load + tax), net in hand** — plus, where
it applies: *"wait N days and this lot turns long-term / exits the load window, saving ₹X."*
**That deferral prompt is the highest-value output of the whole feature.**

---

## 11. Open questions and unverified claims

Recorded so they are not silently promoted to fact later.

| Claim | Status |
|---|---|
| "Regulation 70(3), SEBI (Mutual Funds) Regulations, **2026**" | **Unverified** — could not confirm a 2026 recodification; the long-standing regulations are the 1996 MF Regulations. **Do not cite the 2026 number.** |
| SBI Cashback cap values | **Sources conflict**: ₹5,000/cycle (BankBazaar/Paisabazaar, likely stale) vs ₹2,000 online from Feb 2026 (Bigtricks) vs ₹2,000+₹2,000 from 1 Apr 2026 (CardInsider/SaveSage). **Verify against SBI's MITC before encoding. This single disagreement is the whole argument for versioned, provenance-stamped card data.** |
| Small-merchant MDR exemption at ₹20 lakh turnover | Directionally confirmed; **exact threshold unverified** (payment-gateway blogs, not an NPCI document) |
| HDFC Infinia retention at ₹18L spend / ₹50L relationship from FY2026-27 | **One secondary source only** |
| Amex Platinum Travel ₹1.9L milestone halved to 7,500 MR | **Secondary sources only** |
| NPCI's own CC-on-UPI FAQ | Page returned empty to the fetcher; the restricted-category list is relayed via SBI Card's FAQ quoting NPCI — reliable but second-hand |
| `mfdata.in` free holdings API | **Unverified** — blocked by a network filter. Verify before depending on it. |
| GitHub `fund-disclosures` scrapers | **Unvetted** — low-signal accounts. Audit before use. |
| Screener.in Terms of Service | **`/terms/` 404'd.** Find the live ToS before any redistribution. |
| EODHD / FMP India fundamentals depth | **Unverified** — demo tokens dead, pricing 403. Get trial keys and test `RELIANCE.NSE`. |
| Whether `www.nseindia.com/api/` works from Indian residential IPs | **Unverified** — the 403 may be geo+datacenter combined |
| Value Research page at `/learn/equity-funds/flexi-cap-funds-large-cap-overlap-2026/` | Exists but figures could not be extracted — **unverified** |
| Walnut/axio 2019 delisting | **Unconfirmed** — widely repeated, no primary source |
| Rainmatter's exact stake in smallcase | **Not publicly disclosed** |
| WealthDesk 2026 operational status | **Unverified** — root domain 403s |
| Morningstar India Category classification methodology doc | **Not retrieved** — search budget exhausted |
| Bogleheads 5/25 rule citable URL | **403** — the rule itself is well-established, the URL is not captured |

---

## 12. Research provenance

| Agent | Scope | Status |
|---|---|---|
| a5f005977338e44ce | Net-worth attribution methodology | Complete |
| af31d257ff97bbbe5 | Value Research India methodology | Complete |
| a5619e3151ee9c0f1 | smallcase & WealthDesk | Complete |
| aac8f2fbf8a4993df | Zerodha Console, Nudge, Tickertape ownership | Complete |
| a7ff851cb4535b6d9 | Portfolio analytics (XIRR, rebalancing, risk, benchmarks) | Complete |
| a76e3355ece6192a1 | MF overlap feasibility + India capital-gains rules | Complete |
| a6c3070df2acfafef | Indian card-optimisation platforms, UPI/RuPay, caps | Complete |
| ad18e9256a4c96743 | Financial document intelligence architecture | Complete |
| a6dfb07bd57bf029a | Indian market data sources (live probes) | Complete |
| abe2d1dc5553cae37 | Valuation bands literature | Complete |
| a029c7f57b64af1ae | Indian fintech email ingestion | Complete |
| ae57f05e60e55af33 | Factor models & quality metrics | **Outstanding** |
| ad1ba75ec5e05403d | Regime & factor timing | **Outstanding** |
| aea31249c52b22614 | Morningstar / Simply Wall St primary docs | **Outstanding** |

Several agents exhausted a 200-call web-search budget; per-section verification gaps are listed
in §11 rather than papered over.
