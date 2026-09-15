# Business Logic Rules

Every formula below is quoted from the actual implementation, not paraphrased business intent.
File references point to `backend/src/main/java/com/marketai/`.

## Net Worth

**`PortfolioContextService.build()`**

```
stocksValue, mfValue   = sum of Holding.currentValue, split by symbol suffix ".MF"
equityInvested/Current = sum across ALL holdings (stocks + MF)
fd    = firstNonNull(totalFdCurrentValue, totalFdPrincipal)
rd    = totalRdCurrentValue
epf   = totalEpf
other = totalOtherAssets
loans = totalLoanOutstanding
cash  = sum of CashAccount.balance

totalAssets = stocksValue + mfValue + fd + rd + epf + other + cash
netWorth    = totalAssets − loans

equityPercent = (stocksValue + mfValue) / totalAssets × 100
debtPercent   = (fd + rd + epf) / totalAssets × 100
otherPercent  = other / totalAssets × 100        (cash is NOT its own bucket — it's inside totalAssets only)

equityPnl        = equityCurrent − equityInvested
equityPnlPercent = equityPnl / equityInvested × 100   (null if equityInvested ≤ 0)
```

**Edge case:** `pct(part, whole)` returns `null` (never `0`) when the denominator is ≤ 0 — an
undefined percentage is never displayed as "0%".

**Concentration flags** (thresholds are hardcoded constants):
| Flag | Trigger | Severity |
|---|---|---|
| `SINGLE_STOCK` | one stock > 10% of total assets **or** > 25% of the equity book | HIGH |
| `SECTOR` | one sector > 30% of *known-sector* equity | HIGH |
| `ASSET_ALLOCATION` | equity > 85% of total assets | MODERATE |
| `LEVERAGE` | loans > 50% of total assets | MODERATE |

Sector concentration is only trusted if ≥80% of equity value has a known sector — below that, a
data-gap note is appended instead of silently treating unknown-sector value as zero-risk.

## Holdings (ledger replay)

**`PortfolioService.recomputeFromLedger(Holding h)`** — the *only* place `quantity`/`averageCost`
is ever set. Replays `Transaction` rows ordered by date ascending:

```
BUY:  totalCost += price×qty ;  netQty += qty
SELL: sellQty = min(t.qty, netQty)
      if netQty > 0: avgCostAtSale = totalCost / netQty   (scale 4)
                      totalCost -= avgCostAtSale × sellQty
      netQty -= sellQty   (floored at 0)

if netQty ≤ 0 → holding deleted, method returns null (fully sold)
else          → quantity = netQty, averageCost = totalCost / netQty  (scale 2)
```

**This is weighted-average-cost replay, not FIFO/LIFO lot tracking.** Each sale reduces the
pooled cost basis proportionally, not by matching a specific buy lot. `[Known limitation —
FIFO/LIFO cost basis is `[PLANNED / NOT IMPLEMENTED]`.]`

## XIRR

**`XirrCalculator`** — Newton-Raphson on `Σ cashflow_i / (1+r)^(days_i/365) = 0`.
- Requires ≥2 cash flows with at least one positive and one negative sign, else `null`.
- `MAX_ITERATIONS=100`, `PRECISION=1e-7`, `INITIAL_GUESS=0.1` (10%), rate floored at `-0.999999`.
- Cash flows for a holding: every BUY (negated) and SELL (positive) transaction, plus a synthetic
  "sold today at current value" flow. Falls back to the stored `Holding.xirr` field only if the
  ledger is empty or Newton's method fails to converge.

## FD/RD Renewal Detection

**`TrackingService`** — shared tolerances: `RENEWAL_DATE_WINDOW_DAYS = 10`,
`RENEWAL_AMOUNT_TOLERANCE = 0.05` (5%, chosen to cover standard TDS deduction plus rounding
without false-matching an unrelated similarly-sized new FD).

```
Candidate FD: same user, same bank (case-insensitive), status in {ACTIVE, MATURED}
Gate 1: |candidate.maturityDate − newFd.startDate| ≤ 10 days
Gate 2: |newFd.principal − candidate.projectedMaturityValue| / projectedMaturityValue ≤ 0.05
Pick:   among all candidates clearing both gates, the smallest date difference
```

On a match: old deposit → `status=MATURED_RENEWED`, `renewedToId` set (excluded from net worth
from then on); new deposit → `renewedFromId` set. **No link is ever invented if nothing clears
both thresholds** — the FD is simply left unlinked, never guessed.

RD renewal compares the candidate's **projected corpus** against the new RD's **total committed
value** (`monthlyAmount × tenureMonths`), not its first installment — "since an RD's principal is
the whole schedule, not one payment."

**FD maturity/current value** — compound interest `P × (1 + r/n)^(n×t)`, `n` = 12/4/1 for
monthly/quarterly/annually (quarterly is the default when unspecified). Falls back to simple
1-year interest if either date is null.

## MF Redemption Tax

**`RedemptionService`** — `STCG_RATE=0.15`, `LTCG_RATE=0.125`, `LTCG_EXEMPTION=₹1,25,000`.

```
redeemedAmount    = nav × unitsRedeemed
investedPortion   = holding.averageCost × unitsRedeemed
gain              = redeemedAmount − investedPortion
isLongTerm        = daysHeld ≥ 365  →  LTCG, else STCG

if gain > 0:
  LTCG tax = max(gain − 125000, 0) × 0.125
  STCG tax = gain × 0.15
```

**Known limitation:** the ₹1.25L LTCG exemption is applied *per redemption*, not tracked
cumulatively across the financial year. `[PARTIALLY IMPLEMENTED — cumulative-year exemption
tracking is [PLANNED / NOT IMPLEMENTED]].`

**Deployment plan** for redeemed-but-not-reinvested cash: a rule-based 20% (invest now) / 30%
(after a correction, trigger = Nifty ATR-derived pullback level) / 50% (SIP over 6 months) split
— explicitly documented as **not** live AI market monitoring.

## Signal Engine Scoring

**`SignalEngine`** scores by *family*, not by individual indicator — deliberately, because
correlated oscillators (RSI/CCI/Stochastic/ROC/MACD-line, pairwise correlation >0.9) would
otherwise silently over-weight momentum ~4:1 under naive per-indicator averaging.

```
Weights: trend=0.25, momentum=0.25, structure=0.25, volume=0.15, volatility=0.10  (sum=1.00)

composite = round(Σ(score_i × weight_i) / Σ(weight_i available))     clamped [-100,100]
ceiling   = round(weightAvailable / totalWeight × 100)
confidence = min(|composite|, ceiling)      ← can never exceed how much data was actually available

BUY if composite ≥ 20, SELL if ≤ −20, else HOLD
```

**Guardrails** (can downgrade BUY → HOLD):
- `LOW_VOLUME_TRAP`: volume ratio < 0.7× the 20-bar average.
- `COUNTER_TREND`: BUY signal while market structure reads DOWNTREND.
- `STALE_DATA`: newest bar older than 5 days (flagged, not suppressed).
- `INSUFFICIENT_BARS`: fewer bars than the minimum → whole signal becomes `INSUFFICIENT_DATA`
  before any scoring runs.

**ATR execution**: stop = 2×ATR(14), target = 4×ATR(14) — fixed 2:1 reward:risk by construction.
Entry zone = last close ± 0.25×ATR. **Position sizing is never computed** —
`limitedBy="NOT_SIZED"`, documented as "needs an equity figure and a tracked cash balance; sizing
without one would be invented."

## Card Net-Value Optimizer

**`CardOptimizerService`** — `MIN_TRUSTWORTHY_COVERAGE = 0.50`.

```
projected = Σ over categories( category.projectedAnnualSpend × card.rateForCategory / 100 )
netValue  = projected − annualFee
breakEvenSpend = annualFee × 100 / bestRate   (null if fee=0 or bestRate=0)
```

Verdict precedence:
1. Spend coverage < 50% → `UNKNOWN` ("not enough categorized spend to judge this card")
2. `fee == 0` → `KEEP`
3. `netValue > 0` → `KEEP`
4. `bestRate == 0` → `CANCEL`
5. `projected == 0` (fee but no rewarded spend) → `UNDERUSED`
6. else → `DOWNGRADE` ("ask issuer about a no-fee variant rather than outright cancelling")

Spend categories are **inferred from merchant names, not real card-network MCCs** — every
category is flagged `inferred=true`. Annualization (`365/90 × observed`) is explicitly **not
seasonally adjusted**.

## Internal Transfers (Net-Worth Neutrality)

**`LedgerTransferService`** — enforced structurally, not by a runtime check: the only balance
mutations are equal-and-opposite.

```
source.balance      -= amount     (if a tracked source account)
destination.balance += amount     (if a tracked destination account)
```

For a transfer into a non-cash asset (e.g., buying a mutual fund), only the cash side is touched
here — the asset side is created independently by that asset's own import path. This is what
keeps a bank→MF transfer from inflating net worth by the transferred amount.
