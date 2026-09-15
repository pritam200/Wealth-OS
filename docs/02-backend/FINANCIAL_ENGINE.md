# Financial Engine — Backend Deep Dive

For the business-level rules and formulas in plain terms, see
`00-pmo-product/BUSINESS_LOGIC_RULES.md`. This document is the engineering view: which classes
own which calculation, their exact signatures, and the invariants a change must not break.

## Net worth — `recommendation/service/PortfolioContextService.java`

**Entry point**: `build(Long userId) → PortfolioContext`, consumed by
`recommendation/controller/WealthController` → `GET /api/wealth/summary`. This is the *only*
sanctioned place to compute net worth — do not recompute it elsewhere.

**Data sources it pulls from**: `HoldingRepository` (stocks/MF), `TrackingService.getSummary()`
(FD/RD/EPF/other/loans), `CashAccountRepository.sumBalanceByUser()` (cash).

**Failure handling**: if `TrackingService.getSummary()` throws, FD/RD/EPF/other/loans all default
to `BigDecimal.ZERO` and a data-gap note is appended to the result rather than the call failing —
net worth still computes from whatever data is available, with the gap surfaced, not hidden.

**Constants** (hardcoded in this class — change here if concentration thresholds need tuning):
`SINGLE_STOCK` 10%/25%, `SECTOR` 30%, `ASSET_ALLOCATION` 85%, `LEVERAGE` 50%,
`SECTOR_COVERAGE_TRUSTWORTHY` 80%.

## Holdings — `portfolio/service/PortfolioService.java`

**`recomputeFromLedger(Holding h)`** is the single write path for `quantity`/`averageCost`. Any
new code path that creates or modifies a `Transaction` must call this afterward — do not set
`Holding` fields directly.

**`reconcileAllUsers()`** — called nightly by `portfolio/scheduler/HoldingReconciliationScheduler`
(`fixedDelay=86_400_000`, 60s after startup). Iterates every user, replays every holding's ledger,
logs a warning if the pre-replay stored values had drifted.

**`computeRealXirr(Holding h)`** builds the cash-flow list for `util/XirrCalculator` from the same
transaction ledger, plus a synthetic "sold today at current value" flow. Falls back to the stored
`Holding.xirr` field only if the ledger is empty or Newton-Raphson fails to converge — never
silently returns 0 or null when a fallback value exists.

## XIRR — `portfolio/util/XirrCalculator.java`

Pure static utility, no Spring dependency. `computeXirrPercent(List<CashFlow>) → Double` (nullable).
Newton-Raphson, `MAX_ITERATIONS=100`, `PRECISION=1e-7`, floors the rate at `-0.999999`. Returns
`null` (never a guessed value) when: fewer than 2 flows, all flows the same sign, or the iteration
doesn't converge.

## FD/RD lifecycle — `tracking/service/TrackingService.java`

**`detectAndLinkRenewal(userId, newFdId)`** / **`detectAndLinkRdRenewal(userId, newRdId)`** — call
these after *every* FD/RD creation path (manual entry and Gmail import both go through
`addFd`/`addRd`, which call these internally — do not bypass by inserting an entity directly).

Tolerances are class constants: `RENEWAL_DATE_WINDOW_DAYS=10`, `RENEWAL_AMOUNT_TOLERANCE=0.05`.
If you need to tune false-positive/false-negative renewal-matching behavior, these are the two
knobs — see `BUSINESS_LOGIC_RULES.md` for why these specific values were chosen.

**`markMaturedDeposits()`** — called nightly by `tracking/scheduler/DepositMaturityScheduler`
(`fixedDelay=86_400_000`). Flips ACTIVE → MATURED once past `maturityDate`. Note this runs
independently of and does not call renewal detection — a matured-but-unrenewed deposit simply
sits at status MATURED until a new deposit triggers a renewal match against it.

## MF redemption — `redemption/service/RedemptionService.java`

**`recordRedemption(userId, holding, unitsRedeemed, nav)`** — computes STCG/LTCG tax at the
moment of redemption (see formula in `BUSINESS_LOGIC_RULES.md`) and persists an `MfRedemption`
row with `status=ACTIVE`, `reinvestedAmount=0`.

**`recordReinvestment(userId, redemptionId, amount, date, targetFund, note)`** — appends a
`Reinvestment` chunk row, flips `status=COMPLETED` once `reinvestedAmount >= redeemedAmount`.

**`totalAwaitingRedeployment(userId)`** — sums `cashRemaining` across `ACTIVE` redemptions.
**Do not add this to `CashAccount` totals** — it's an earmark on cash already counted there (the
sale proceeds land in a bank account, which `CashAccount.balance` already reflects), not
additional cash. Adding both would double-count.

**`getDeploymentPlan(userId, redemptionId)`** — rule-based only (20/30/50 split with an
ATR-derived pullback trigger for the "after a correction" tranche). Not live market monitoring;
do not present this as an AI-driven recommendation in the UI.

## Signal engine — `signal/service/SignalEngine.java`

`analyse(String symbol) → SignalPayload`. Family weights and thresholds are class constants
(`W_TREND=0.25`, `W_MOMENTUM=0.25`, `W_STRUCTURE=0.25`, `W_VOLUME=0.15`, `W_VOLATILITY=0.10`,
`BUY_THRESHOLD=20`, `SELL_THRESHOLD=-20`).

**If adding a new factor**: add it as its own family with its own weight (rebalance the others so
they still sum to 1.00), not as an additional vote inside an existing family — the whole point of
the family-weighted design is that correlated indicators must never silently dominate the score.
See `01-architecture/ARCHITECTURE_DECISIONS.md` ADR-8.

**`MarketStructureAnalyzer`** provides the `structure` family score (uptrend/downtrend/ranging via
swing high/low detection) — a separate class, not inlined in `SignalEngine`.

**Confidence ceiling** is computed from `weightAvailable / totalWeight`, where `totalWeight` is
the sum of all defined family weights (currently 1.00, since `microstructure` has weight 0 and is
never counted toward the ceiling denominator — verify this doesn't change if `microstructure` is
ever given a nonzero weight when a broker feed becomes available).

**ATR execution** (`buildExecution`): `ATR_STOP_MULTIPLE=2.0`, `ATR_TARGET_MULTIPLE=4.0` — fixed
2:1 reward:risk. Position sizing is deliberately unimplemented (`limitedBy="NOT_SIZED"`) — do not
add a sizing heuristic without first wiring in a real, tracked cash-balance and equity input; the
class's own documentation states inventing one would violate the no-fabrication rule.

## Card optimizer — `card/optimizer/{CardOptimizerService,SpendAggregator}.java`

**`SpendAggregator.aggregate(userId)`** — 90-day window, buckets by `ExpenseCategory`, computes a
`coverage` ratio (categorized / total spend). Every category is flagged `inferred=true` — this is
merchant-name inference, never present it as MCC data.

**`CardOptimizerService.analyse(userId)`** — `MIN_TRUSTWORTHY_COVERAGE=0.50`. Verdict precedence
is a fixed if/else chain (see `BUSINESS_LOGIC_RULES.md`); do not reorder without re-verifying the
"un-trustworthy coverage always wins" rule stays first.

`rewardsRealized12m` is hardcoded `null` throughout — it is not backed by real reward-transaction
history yet. Do not populate it with a projection; that would misrepresent an estimate as a
measurement.

## Internal transfers — `ledger/service/LedgerTransferService.java`

**`applyCashEffect(transfer)`** — the only method that mutates `CashAccount.balance` for a
transfer. Guarded by `LedgerTransfer.applied` (boolean) so a re-save or replay can never move
money twice. **`delete(user, id)`** reverses by the exact equal-and-opposite operation (not by
recomputation) so balances return to precisely where they were.

If you add a new `destinationType`, ensure the corresponding asset-creation path (wherever that
asset type is actually created) does *not* also credit cash — `LedgerTransferService` already
did that on the way in.
