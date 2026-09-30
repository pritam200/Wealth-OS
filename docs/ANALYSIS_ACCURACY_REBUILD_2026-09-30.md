# Stock analysis & market forecast — accuracy rebuild (2026-09-30)

Status: implemented on branch `wealth-os-audit-and-hardening`, not yet committed.
Backend suite: 891 tests, 0 failures. Frontend: `tsc --noEmit -p tsconfig.app.json` clean.

## 1. What was wrong

| Area | Defect | Effect |
|---|---|---|
| ATR | Averaged the **oldest** 14 true ranges | Volatility, stops and forecast widths reflected data from years ago |
| Forecast | Bull/base/bear odds came from a fixed trend→probability table | The probabilities were made up |
| Forecast | Band width was ATR·√h, treating ATR (₹) like a % volatility | Ranges had the wrong width |
| Support/resistance | Min/max of recent bars; often equal to the current price | Levels were meaningless |
| History | Stored once and never topped up; zero-price and partial bars kept | Indicators were computed on stale data or bad bars |
| Fundamentals | Yahoo's D/E percentage stored as a ratio (100×) | Rebalancing risk flags were wrong |
| Index quotes | NIFTY50 etc. not mapped to Yahoo symbols | No quotes for indices |
| Sentiment | Substring matching (e.g. "fall" matched "rainfall"), no dedup or dating | Noisy scores |
| Rating | Weighted blend of unvalidated factor scores; `|composite|` shown as "% confidence" | The number looked like a probability but wasn't one |
| MF ratings | BUY/HOLD with fabricated confidence | Recommendations had no measured basis |
| Frontend | ×1.05 / ×1.12 "targets", browser-side volatility, `|| 0` and RSI default of 50 | Numbers were shown just because the UI expected one |

## 2. Canonical pipeline

`MarketDataService.getDailySeries(symbol, minBars)` is the only entry point for daily prices. It:
- canonicalises the symbol (NIFTY50 → ^NSEI, …);
- tops up history (first fetch 10y; later 5d–1y depending on the gap);
- upserts with provider, exchange and timestamps;
- re-fetches if more than 20% of rows rescale (a split).

`PriceSeriesValidator` then:
- rejects invalid OHLC bars;
- deduplicates;
- drops the unfinished current-session candle (before 15:45 IST);
- flags missing dates, abnormal moves (>±20%) and unadjusted splits;
- sets the series status: `OK`, `DATA_QUALITY_WARNING`, `STALE_DATA` (>3 sessions behind) or `INSUFFICIENT_DATA`.

A split is flagged only when the close **and** the open jump by a standard ratio **and** the two bars' ranges don't overlap. A close-to-close ratio alone falsely flagged real crashes, such as ADANIENT in Jan 2023 and on 4 Jun 2024.

Every consumer uses the same series and the same `Indicators` functions: technicals, forecast, signal engine, analyst, recommendation, Today's Actions, redemption plan and the AI prompt. The frontend renders these values and does not recompute any.

## 3. Methods

**Indicators** (`technical/service/Indicators.java`):
- Wilder RSI, ATR and ADX.
- SMA-seeded EMA.
- MACD 12/26/9.
- Bollinger 20 with 2 population σ.
- Sample-σ log-return volatility.

Each indicator returns `null` below its data requirement. Values are checked against an independent Python implementation on 450 real RELIANCE bars (`IndicatorReferenceTest`, tolerance 1e-9).

**Support/resistance:**
- Swing-pivot clusters with ≥2 touches, tolerance max(0.5·ATR, 0.5% of price), over the last 250 sessions.
- 52-week high/low, SMA50/200 and unfilled gaps.
- Returns the nearest and next level each side, with source and reason, or `NO_RELIABLE_LEVEL`.

**Trend:**
- Six votes (price vs SMA50 and SMA200, SMA50 vs SMA200, SMA50 slope, EMA20 vs EMA50, swing structure).
- ADX, RSI, MACD and volume are shown as confirmation only.
- The evidence is returned with the label. The label is descriptive only (see §5).

**Forecast range:**
- Zero-drift log-normal: ln(P[t+h]/P[t]) ~ N(0, (σ√h)²), where σ is the sample σ of the last 120 daily log returns.
- Horizons are 1/5/20/60 sessions.
- Bands: Bear 5–25th percentile, Base 25–75th, Bull 75–95th.
- Scenario probabilities are the **measured** share of this instrument's past outcomes in each band, from a walk-forward backtest that uses only data up to each past session. They are shown with Wilson 95% intervals, and only when there are ≥30 independent windows (N/h). Otherwise the UI shows "Probability unavailable".
- No range is shown for stale or insufficient data, or when a split falls inside the volatility window.

**Signal / rating:**
- The existing rule is replayed walk-forward on the instrument's own history (20-session horizon).
- BUY or SELL is shown only if the lower 95% bound of that call's hit rate beats the base rate over ≥30 independent windows. Otherwise the result is `NO_ACTIONABLE_SIGNAL`, which also covers the rule's neutral reading.
- `confidenceScore` is now the historical hit rate of a validated call, or `null`.

**News:**
- Whole-word lexicon over the last 14 days, deduplicated by normalised title, filtered for relevance, needing ≥3 articles.
- Reported separately and never a rating input.

**LLM:** the prompts receive only the canonical figures (missing values are marked "unavailable") and are told never to predict a price. The AI view is advisory and cannot change the rating.

## 4. Backtest: range model on real data

Data:
- 27 instruments, 66,774 daily Yahoo bars, Sep 2016 – 29 Sep 2026.
- Indices: ^NSEI, ^NSEBANK, ^BSESN, ^NSEMDCP50.
- Large caps: RELIANCE, TCS, HDFCBANK, ICICIBANK, INFY, ITC, LT, SBIN, AXISBANK, BHARTIARTL, HINDUNILVR, MARUTI, SUNPHARMA, BAJFINANCE, ONGC, COALINDIA.
- Mid-cap and volatile names: PERSISTENT, VOLTAS, TATAPOWER, ADANIENT, YESBANK, IDEA, SUZLON.

Endpoint: `GET /api/forecast/backtest?symbols=…&horizons=1D,5D,20D,60D`.

Pooled results:

| Horizon | Obs (independent) | 50% range held | 90% range held | Region freq (nominal 5/20/50/20/5) | MAE, last close | MAE, drift | MAE, SMA20 reversion | Brier: model vs trailing freq |
|---|---|---|---|---|---|---|---|---|
| 1D | 63,480 (63,480) | 59.0% | 90.6% | 4.4/15.2/59.0/16.4/5.0 | 1.35% | 1.37% | 3.93% | 0.250 vs 0.250 |
| 5D | 63,372 (12,655) | 55.3% | 89.6% | 4.8/15.6/55.3/18.7/5.7 | 3.19% | 3.25% | 4.95% | 0.250 vs 0.251 |
| 20D | 62,967 (3,131) | 52.0% | 89.1% | 4.5/15.1/52.0/22.1/6.4 | 6.70% | 6.98% | 7.81% | 0.250 vs 0.259 |
| 60D | 61,887 (1,025) | 49.7% | 87.8% | 4.7/13.1/49.7/25.1/7.5 | 12.13% | 13.35% | 12.79% | 0.250 vs 0.280 |

Coverage by regime and period (50% / 90% range held):

| Regime / period | 1D | 20D | 60D |
|---|---|---|---|
| Bull (trailing 1y > +15%) | 0.59 / 0.91 | 0.52 / 0.90 | 0.51 / 0.88 |
| Bear (trailing 1y < −15%) | 0.60 / 0.90 | 0.54 / 0.90 | 0.51 / 0.88 |
| Sideways | 0.59 / 0.91 | 0.51 / 0.88 | 0.48 / 0.87 |
| High volatility (σ > 35%/yr) | 0.65 / 0.92 | 0.59 / 0.92 | 0.53 / 0.89 |
| COVID crash (20 Feb – 31 Mar 2020) | 0.32 / 0.62 | 0.25 / 0.52 | 0.33 / 0.73 |
| Recovery (Apr 2020 – Mar 2021) | 0.63 / 0.93 | 0.56 / 0.91 | 0.51 / 0.87 |
| 2022 drawdown | 0.53 / 0.88 | 0.51 / 0.90 | 0.50 / 0.93 |
| Sep 2024 – Mar 2025 correction | 0.57 / 0.90 | 0.49 / 0.92 | 0.50 / 0.92 |

Reading the results:
- The 90% range holds 88–91% of the time at every horizon, so the range model is calibrated.
- Daily ranges are slightly too wide in the middle (fat tails around a peaked centre).
- At 60 days there is an upside skew: 25% of outcomes landed in the Bull band against 20% nominal. This is why the probabilities shown come from measured frequencies rather than the nominal 20/50/20.
- The model fails in sudden volatility regime shifts: during the COVID crash only 52–73% of outcomes stayed inside the 90% range. The UI states this risk.
- For direction, the last close is the best point estimate (lowest MAE). Trailing drift and SMA20 reversion do worse.
- The model's P(up) = 0.5 scores as well as or better than a trailing up-frequency on Brier score.

## 5. Backtest: signals and trend (honest negative result)

- Across all 27 instruments, **no** current signal-rule call is validated. BUY hit rates are roughly the base up-rate. For example, ^NSEI BUY was right 64.9% of the time against a 62.1% base rate, with the lower 95% bound at 47.6%.
- SELL calls are usually worse than simply calling "down": ^NSEI SELL was right 33.8% of the time against a 37.9% base down-rate.
- An earlier Python check found the same for trend labels: outcome distributions of about 25/55/20 regardless of the label.

So the app now shows **No actionable signal** everywhere instead of a fabricated BUY/SELL/HOLD. The trend label is kept as a description only. Any future rule has to pass the same validation before it can produce a call.

## 6. Where users see it

- **Market Forecast:**
  - 1D/5D/20D/60D horizons.
  - Status banner and model-estimated 50%/90% ranges with their historical coverage.
  - Scenario cards with measured frequency and 95% CI, or "Probability unavailable".
  - Calibration curve, directional baseline, MAE, methodology and data issues.
- **Stock page:** price freshness line (delayed intraday, last traded, or stored close when the live quote fails), ATR in ₹ and %, volatility, "no reliable level", and an expandable **Analysis details** audit panel. The panel covers data provenance, validator issues, the indicator table with formulas and bar requirements, trend votes, S/R method, volatility and backtest, and signal-rule validation.
- **Price Projections (Tab 4):** the backend 20D/60D ranges replace the ×1.05/×1.12 "targets".
- **Rating chips everywhere** (Analyst panel, AI Advisor, holding badge, Today's Actions): the new ratings and actions (No actionable signal, Stale data, Avoid, Not rated). A percentage appears only as "historical hit rate" for a validated call.
- **MF tab:** portfolio figure relabelled as absolute return, benchmarks relabelled as 1-year, and a note that the two periods differ.
- **Risk Matrix:** the score is labelled as an allocation heuristic, with its rule.

## 7. Known limits and follow-ups

- The production Yahoo client must be reachable. In the scratch environment, Java failed TLS (PKIX) to Yahoo, so the 10-year history was loaded from curl downloads of the same chart API.
- The fundamentals facts panel is empty until quotes refresh (`FUNDAMENTALS_VERSION = 2` forces a re-fetch of old D/E values).
- The range model uses unconditional 120-day σ. A regime-aware estimate (e.g. EWMA or GARCH) would narrow the crash-period miss, but it must be backtested with the same harness before replacing the current one.
- No intraday timeframes: the signal engine is daily only.
