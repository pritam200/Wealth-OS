# LLM research engine (2026-09-30)

The LLM is a research and reasoning layer on top of the deterministic analysis. It never supplies a number, and nothing it writes changes the quantitative analysis.

## 1. Flow

```
price history → technicals → forecast → signal validation → fundamentals → company/sector/market/macro
   → filings & news → portfolio                                  (ResearchContextBuilder — code only)
   → cache lookup                                                 (ResearchCache)
   → web research (Gemini routes only, Google Search grounding)
   → analyst pass (JSON) → devil's-advocate pass (JSON)           (ResearchOrchestrator)
   → validation, citation check, basis derivation, number guard   (ResearchOutputValidator, NumberGuard)
   → final-view gate                                              (FinalViewPolicy)
   → stored ResearchResult → UI
```

There is one engine. These screens get research from `ResearchOrchestrator`, or read what it stored through `ResearchCache`:
- Stock page, Market Forecast, Price Projections and AI Advisor use `ResearchOrchestrator`.
- The Analyst panel, Recommendations and Today's Actions read `ResearchCache`.
- The AI Advisor's free-text stock answer (`AiService.analyseStock`) is built on the same verified context, and its figures pass the same number guard.

The old separate "AI second opinion" call in `AnalystService` (the `stock-second-opinion` prompt) has been removed.

## 2. Verified context

Every item is a `Fact` with an id (F1…), category, value, unit, as-of date, source and basis (DATA / CALCULATION / MODEL / PORTFOLIO). Anything that could not be obtained is still listed, marked **UNAVAILABLE** with the reason. It is never omitted.

| Group | Contents |
|---|---|
| Market | Last validated close, live quote (price type, previous close, change, time), market status |
| Technical | RSI, SMA20/50/100/200, EMA20/50/200, MACD, ATR (₹ and %), ADX/±DI, Bollinger, volatility, volume profile, 52-week range, S/R levels with method, trend votes |
| Forecast | 1D/5D/20D/60D 50%/90% model ranges; their historical coverage, calibration and measured band frequencies |
| Signal | Rule reading, shown signal, walk-forward BUY/SELL hit rates vs base rate, whether the current call is validated |
| Fundamental | Revenue, EBITDA, margins, OCF, FCF, debt, cash, EPS, P/E, P/B, ROE, D/E, growth and latest quarter. ROCE is listed as unavailable (the source lacks capital employed) |
| Company / sector | Sector, industry; sector index with 5/20-session change; stock-minus-sector 20-session return |
| Market / macro | Nifty 50 (trend label, volatility), Bank Nifty, India VIX, USD/INR, Brent, US 10Y, S&P 500, Nasdaq, Nikkei, Hang Seng. FII/DII, CPI and RBI rate are listed as unavailable (no verified source) |
| Portfolio | Shares, average cost, value, unrealised P&L, share of stock book, holding period, largest position, sector exposure, other holdings in the sector, funds held, planned investment (unavailable) |
| Data quality | Series status, bar count, expected session, warnings in the last year |
| News | Keyword sentiment score (a calculation, not a rating input) |

Mutual funds are covered too:
- AMFI category and fund house, plan and option, latest NAV.
- 1/3/5-year returns, volatility and drawdown from stored NAV history.
- A Nifty proxy benchmark, labelled as a proxy.
- The holding, holding period, tax treatment, share of the MF book, and same-category funds held.
- Expense ratio, AUM, manager, holdings, sector allocation, overlap, exit load and stated benchmark are listed as unavailable (they need the factsheet).
- A fund never gets a directional quantitative rating.

**Evidence** items have ids (E1…). Each carries its kind, source tier, source, title, publication date, URL, retrieval time and relevance. They come from:
- NSE announcements (last 120 days), corporate actions, board meetings including upcoming ones, and results filings. All are tier PRIMARY.
- Google News RSS and stored news, tiered by publisher: RELIABLE, NEWS or UNVERIFIED. Social media is UNVERIFIED.
- On Gemini routes, grounded web findings (kind WEB), each tied to the page that supports it.

Every source's outcome is recorded (OK / UNAVAILABLE / NOT_SUPPORTED). The model is told that an unavailable source means "not checked", not "nothing found".

## 3. Model passes and validation

- **Analyst** (`research-analyst-v2`) returns JSON with:
  - executive summary, the fundamental, technical, market, sector, news, valuation, portfolio and forecast assessments, and bull/base/bear cases;
  - seven cross-checks (answered SUPPORTS / CONTRADICTS / MIXED / UNKNOWN), contradicting evidence, risks, catalysts and missing information;
  - evidence quality, a conclusion, and actionability, which is one of BUY / SELL / HOLD / NO_ACTIONABLE_SIGNAL / INSUFFICIENT_DATA / CONFLICTING_EVIDENCE / RESEARCH_REQUIRED.
  Every statement is `{text, evidence:[ids]}`. Invalid JSON gets one retry; if the retry also fails, research is reported unavailable.
- **Devil's advocate** (`research-devils-advocate-v2`), a second pass stored separately, covers:
  - contradictory evidence, overlooked risks, data-quality problems and catalysts;
  - why the technical signal, the fundamental thesis or the forecast range could fail;
  - thesis risk (LOW / MEDIUM / HIGH) and a verdict.
- **Validation:**
  - Required sections must be present, and enums must come from the allowed sets.
  - Citations to ids that are not in the context are dropped and recorded.
  - The prompt asks for the 1–5 ids that most directly support each statement. A statement citing more than 6 keeps its first 6 and the cap is recorded. (qwen2.5:14b otherwise "cited" whole ranges like F36–F121.)
  - Each statement's **basis is derived from what it cites** (FILING, DATA, CALCULATION, MODEL, PORTFOLIO, NEWS). A statement that cites nothing valid is labelled `LLM_INTERPRETATION`.
- **Number guard:**
  - Every figure in model text must match, within rounding, a figure in the facts or evidence. Crore, lakh and billion units are handled.
  - Anything else is replaced with "[figure not in verified data]" and listed in the output checks. That covers targets, probabilities and computed differences.
  - Exempt: years, dates, indicator periods, textbook thresholds (RSI 30/70, ADX 25), the 50/90/95% range levels, and small counts.

## 4. Final view (a fixed gate, not a weighted score)

| Rule | Condition | Result |
|---|---|---|
| DATA_GATE | Price data stale or insufficient | INSUFFICIENT_DATA |
| QUANT_ONLY | No research | Validated BUY/SELL, else NO_ACTIONABLE_SIGNAL ("AI research unavailable — quantitative view only") |
| RESEARCH_FLAG | Research says CONFLICTING_EVIDENCE / RESEARCH_REQUIRED / INSUFFICIENT_DATA | That |
| CONFIRMED | Validated call and research agree | The call; CONFLICTING_EVIDENCE if the review rates thesis risk HIGH |
| OPPOSED | Validated call and research disagree | CONFLICTING_EVIDENCE |
| NOT_CONFIRMED | Validated call, research neutral | NO_ACTIONABLE_SIGNAL |
| NO_EDGE | No validated call | NO_ACTIONABLE_SIGNAL, whatever the research leans |

Research can stop a call but never start one. In `RecommendationEngine`, a validated-call action (ACCUMULATE / AVOID / BOOK_PROFIT) becomes **REVIEW** when the current session's research final view is CONFLICTING_EVIDENCE. Today's Actions inherits this.

## 5. Caching and failure

- **Cache key:** `research_records` is unique on (subject, symbol, user scope, market date, snapshot hash, prompt versions, provider, model).
- **Snapshot hash:** SHA-256 over fact values and dates, evidence (source, title, publication time) and the quant assessment. Retrieval times and wall-clock market status are left out. So a new filing or headline changes the hash and research re-runs, while re-fetching the same news does not.
- **Modes:**
  - `mode=cached` never calls a model.
  - The default reuses exact-key research, else runs it.
  - `refresh=true` always runs it.
- **Frontend:** loads cached first, runs once automatically if nothing is current, and offers "Refresh research".
- **Failure:**
  - The deterministic view stands, with status UNAVAILABLE or DISABLED and the reason.
  - The most recent research is shown as `previous`, labelled "Last research: <time>, on market data to <date>". It does not feed the final view.
  - Yesterday's research is never shown as current.
- **Configuration:**
  - Uses the existing LLM Configuration task *Financial analysis & market research* (provider, model and fallback). There is no new config.
  - Web research runs only when that task is routed to a Gemini model with tool support and the privacy mode allows cloud calls.
  - A provider change only changes the cache key. No financial record reads research.

## 6. UI

- **Stock page:**
  - AI Research panel, showing the final view, quantitative assessment and LLM conclusion side by side.
  - Market, sector and news context; bull/base/bear cases; risks, catalysts and contradicting evidence.
  - A **"Why this analysis could be wrong"** section.
  - Assessments, cross-checks and missing information.
  - Every statement shows basis chips and F#/E# citation chips; hover shows the source, and a link opens the filing or article.
- **Analysis details → LLM Research:**
  - Status, provider and model, time, market date, prompt versions, snapshot hash, cache and stale state, run time, web searches, and the final-view rule.
  - Sources researched with their status, and output checks (removed figures, dropped citations).
  - An evidence table (id, fact, source, date, tier; cited ones highlighted) and every verified fact sent to the model.
- **Market Forecast:**
  - An all-horizons table (current, 1D/5D/20D/60D 50%/90% ranges, historical coverage, calibration).
  - Market research for indices, covering global markets, crude, USD/INR, yields, RBI, inflation, FII/DII, VIX, events and geopolitics. On Ollama this comes from the verified context and headlines only.
- **Other screens:**
  - The Analyst panel's "AI Second Opinion" is replaced by the research view.
  - AI Advisor holdings (stocks and funds) and Price Projections get the research panel.

## 7. Known limits

- **Web research needs a Gemini route.** On Ollama it is recorded as NOT_SUPPORTED, and research uses NSE filings, Google News RSS and stored news only.
- **The number guard is conservative.** It also removes legitimate derived figures, such as "5% above SMA50", if they are not in the context. The model is told to cite instead.
- **Evidence is not re-verified.** Headlines and grounded web findings are summaries. The link to the source is kept, but the claim itself is not checked beyond its publisher tier.
- **Local-model timing.** On qwen2.5:14b a full two-pass run took about 284 s for a stock (RELIANCE) and 259 s for Nifty 50. The default 120 s Ollama timeout was too short for the index context (both attempts timed out). Raise the Ollama timeout in LLM Configuration to about 300 s for research on a local 14B model.
- **Over-citing on local models.** Even with the v2 prompt, qwen2.5:14b cites long id ranges. They are capped at 6 per statement and the cap is recorded in the output checks, but the kept ids are the model's first six, not necessarily the most relevant.

## 8. Acceptance run (scratch environment, 2026-09-30)

| # | Check | Result |
|---|---|---|
| 1 | Backend suite | 948 tests, 0 failures, 0 errors |
| 2 | Frontend | `tsc -b` clean; `vite build` succeeds |
| 3 | Gemini | **Not run live**: no Gemini key in the scratch environment. Covered by `GeminiGroundingTest` against a local stand-in server (google_search tool sent, JSON mode off, grounding parsed) and the orchestrator web-evidence test |
| 4 | Ollama | Live: RELIANCE (two runs) and Nifty 50 researched end to end on qwen2.5:14b, status OK |
| 5 | LLM failure | Live: Nifty 50 run timed out, and the UI showed "AI research unavailable: Ollama call timed out", quantitative view unchanged. Unit-tested with stale last research |
| 6 | Stale market data | `staleData`, `FinalViewPolicyTest.staleDataGatesEverything` |
| 7 | Missing fundamentals | Live (Yahoo unreachable from the sandbox JVM, so fundamentals were marked UNAVAILABLE); `promptContents` test |
| 8 | Conflicting evidence | `conflictingEvidence`, policy table, `RecommendationEngineTest.conflictingResearchHoldsBackTheCall` |
| 9 | News refresh | Live: a Google News item dropped out, the hash changed and research re-ran; `newsChangesHash` |
| 10 | Traceable conclusions | Basis derived per statement; F#/E# chips; evidence and facts tables in Analysis details |
| 11 | LLM cannot invent numbers | `NumberGuardTest`, `happyPath` (₹180 target removed), AI Advisor answer guarded (`AiServiceResearchTest`) |
| 12 | Forecasts unchanged by LLM | `happyPath` asserts the quant and facts are the inputs, untouched; the research path never writes forecast data |
| 13 | One orchestrator | Stock page, Market Forecast, Price Projections, AI Advisor use `ResearchOrchestrator`; analyst/recommendations/Today's Actions read its cache (`AiServiceResearchTest`) |
| 14 | Full research trail | Analysis details → LLM Research (verified in the browser) |

External sources in the sandbox: the JVM cannot reach NSE or Yahoo because the corporate TLS-inspection CA is not in the Java truststore. They are therefore recorded as UNAVAILABLE. Google News RSS worked.
