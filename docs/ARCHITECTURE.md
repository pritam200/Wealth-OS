# Wealth-OS — Architecture

Personal wealth-management platform for Indian markets: tracks stocks, mutual funds, FD/RD, EPF,
bank cash, income, expenses, credit cards and loans; ingests transactions from Gmail; and produces
advisory recommendations.

## The rule everything else follows

> **Do not fabricate, estimate, duplicate, silently drop, or overwrite financial records.**

Every architectural decision below exists to enforce some part of that. When adding code, the
question is not "does this work" but "can this produce a number nobody can trace".

---

## 1. Stack

| Layer | Technology |
|---|---|
| Backend | Java 8, Spring Boot 2.7.18, Hibernate/JPA, Maven |
| Database | PostgreSQL 16 (`ddl-auto: update` in dev, `validate` in prod) |
| Cache | Redis 7 (optional; `cache.type: simple` in dev) |
| Frontend | React 19, TypeScript, Vite, Tailwind 3.4, recharts, zustand |
| AI | Local Ollama (default), Gemini (optional fallback) |

**Java 8 constraint** — no `List.of()`, no `var`, no `Optional.orElseThrow()` without args. This
has caused real compile breaks; check before using a modern API.

---

## 2. Module map

```
backend/src/main/java/com/marketai/
├── auth/           JWT auth, refresh tokens, roles
├── portfolio/      Holding, Transaction, PortfolioService, XirrCalculator
├── recommendation/ PortfolioContextService  ← THE net-worth calculator
├── analyst/        AnalystService — deterministic factor model per stock
├── actions/        TodaysActionsService (computed) + ActionItem (persisted disposition)
├── ledger/         CashAccount, LedgerTransfer  ← net-worth invariant
├── tracking/       FixedDeposit, RecurringDeposit, EpfAccount, Loan, OtherAsset
├── redemption/     MfRedemption + Reinvestment (chunked redeployment)
├── income/         Income + IncomeSource enum
├── expense/        Expense + ExpenseCategory enum
├── card/           CreditCard, CardCatalog, CardService (optimizer)
├── gmail/          OAuth, sync, parsers, PDF import, fingerprint dedup
├── ai/
│   ├── llm/        LlmProvider abstraction, OllamaProvider, LlmJsonParser
│   ├── intel/      EmailIntelAgent — semantic classification
│   ├── review/     EmailReviewItem — human review queue
│   └── audit/      AiAuditTrail
├── reconciliation/ Cross-domain integrity checks
├── market/         Stock, PriceHistory, MarketIndex, MarketDataService
└── networth/       NetWorthSnapshot history
```

---

## 3. Load-bearing invariants

These are the five things that must not be broken. Each was a real bug before it was a rule.

### 3.1 One net-worth calculation

`PortfolioContextService.build(userId)` is the **only** place net worth, total assets and asset
allocation are computed. Exposed as `GET /api/wealth/summary`.

```
totalAssets = stocks + mutualFunds + FD + RD + EPF + otherAssets + cash
netWorth    = totalAssets − loansOutstanding
```

*Why:* the same figure was previously recomputed in six places (three of them in one file), which
is why different tabs showed different net worth. Any new screen must call this, never re-derive.

### 3.2 The transaction ledger is the source of truth for holdings

`Holding.quantity` and `Holding.averageCost` are **never** assigned directly. Every mutation goes
through `PortfolioService.recomputeFromLedger(holding)`, which replays all `Transaction` rows.

Manual corrections are modelled as **synthetic offsetting transactions** (an offsetting SELL at the
old figures, then a corrective BUY at the new ones) rather than field overwrites — so the ledger
always explains the current position. A nightly `HoldingReconciliationScheduler` replays every
user's ledger and logs any drift.

### 3.3 A transfer never changes net worth

Three distinct money events:

| Event | Effect on net worth |
|---|---|
| Expense | decreases |
| Income | increases |
| **Transfer** | **unchanged** — only allocation moves |

`LedgerTransfer` debits the source and credits the destination by the same amount, guarded by an
`applied` flag so a replay cannot move money twice; deletion reverses exactly.

*Why cash exists at all:* before `CashAccount`, assets had no cash component, so moving ₹50,000
bank→MF raised the MF value with nothing decreasing — net worth grew by the full transfer. The
invariant was not merely unenforced, it was unrepresentable.

### 3.4 Recommendations never mutate holdings

`ActionItemService` is constructed with the action repository **alone** — it has no holding,
portfolio or transaction collaborator, so advice cannot write to the ledger even by accident
(there is a test asserting this). Marking an action `EXECUTED` records only that the user placed
the trade; the portfolio moves when the resulting `Transaction` is booked.

### 3.5 Imports are idempotent at the content level

Two independent gates:

1. `ProcessedEmail(user_id, gmail_message_id)` — per-message, and `FAILED` rows are **retried** on
   the next sync (a transient error used to drop an email permanently).
2. `ImportedTransactionFingerprint(user_id, fingerprint)` — a **DB-level unique constraint** on a
   SHA-256 of the transaction's intrinsic content (type, date, amounts, symbol/folio/bank/merchant,
   quantity). Excludes message id, sender and description, so a forwarded copy hashes identically.

Checked at the single chokepoint `ParsedEmailImporter.importParsedEmail`, so no import path can
double-book by forgetting its own check. Full Resync clears fingerprints deliberately.

---

## 4. AI layer

The AI layer is **strictly additive**. `app.llm.provider=none` disables every model call and the
deterministic parsers and all arithmetic keep working unchanged.

```
Email ─► deterministic parsers ─► (only if empty) EmailIntelAgent ─► confidence gate
                                                                        │
                            ┌───────────────────────────────────────────┤
                            ▼                    ▼                      ▼
                       IMPORT (≥0.85)     REVIEW_REQUIRED        NOT_A_TRANSACTION
                            │                    │
                    ParsedEmailImporter    EmailReviewItem ──► human ACCEPT/EDIT/REJECT
                    (fingerprint gate)                              │
                                                                    └──► same importer
```

### Deterministic safety boundary

The model supplies **semantics only**. Backend code does all arithmetic, all validation and all DB
writes. Concretely:

- `LlmJsonParser` refuses to coerce. `"₹5,000 approx"` → **null, not 5000**. A fractional share
  count → null, not rounded. Confidence of `95` (percent) → null, not 95.0. Prose or truncated
  JSON → parse failure, never salvaged.
- Every AI-proposed ticker is resolved against the real stock master; ambiguous → the trade is
  **dropped**, because a plausible-but-wrong ticker silently creates a second holding for a stock
  already owned.
- Missing fields yield `null`, never a default — a missing amount can never become `0`.

### Confidence routing

Threshold `app.llm.min-confidence` (default `0.85`). **Unknown confidence is treated exactly like
low confidence** — absence of evidence is never read as permission. Classifications that must not
be auto-booked (`INTERNAL_TRANSFER`, `SELF_TRANSFER`, `MF_SWITCH`, `FD_MATURITY`,
`RD_INSTALLMENT`) go to review even at 0.99.

### Providers

`LlmProvider` → `OllamaProvider` (default) | `GeminiLlmProvider`, selected by
`LlmProviderRouter`. Ollama uses `/api/chat` with `format: json` (constrains decoding — far more
effective than prompt wording on a small model) and `temperature: 0` (classification must be
reproducible run to run).

### Audit

`ai_audit_trail` stores prompt, output, confidence, status, latency and tokens per call.
Prompts **are** persisted (that's the audit value) with secret redaction on write — statement
passwords, API keys, `Bearer` tokens, `ya29.` Google tokens and JWTs. Nothing sensitive is logged.

**Known performance constraint:** ~8.2s per email on `qwen2.5:7b`. The agent is therefore a
fallback for emails no parser could read, not a per-email step. Before a real 300-email sync,
review-queue population should move off the synchronous request or the sync will appear hung.

---

## 5. Reconciliation

`ReconciliationService.checkAll(userId)` aggregates, via `GET /api/reconciliation/report`:

- **Portfolio** — mismatched tickers, duplicate symbols, unverifiable symbols
- **FD/RD** — matured-but-idle > 30 days, orphaned renewal links
- **Net worth** — server-computed value vs. last persisted snapshot, tolerance
  `max(₹1000, 1%)`

Surfaced as banners on the Dashboard. The principle: **flag it, never silently display a number
you cannot stand behind.**

---

## 6. FD/RD lifecycle

```
ACTIVE ──► MATURED ──► CLOSED
                  └──► MATURED_RENEWED  (renewedToId / renewedFromId cross-links)
```

Renewal detection (`detectAndLinkRenewal` / `detectAndLinkRdRenewal`): same bank + maturity date
within a day window + amount within tolerance. Runs on **every** creation path (manual add and
Gmail import) — it was previously wired only into the import path, so a manually-entered renewal
double-counted.

`getSummary()` excludes `CLOSED` and `MATURED_RENEWED` from asset totals. **Any new FD/RD query
must apply the same filter or it will double-count.**

---

## 7. Frontend

Tabs are a `useState` index in `App.tsx`, not routes (only `/login`, `/register`, `/stock/:symbol`
are real routes). `TabContent` dispatches on the index.

### Design tokens

All theming lives in `tailwind.config.js` + `src/index.css`. Components use semantic tokens
(`surface-card`, `surface-border`, `text-ink`, `bull`/`bear`/`neutral`/`brand`), so a full theme
flip is a two-file change.

Two conventions worth knowing:

- **`gray` is a text-emphasis scale, not a lightness scale.** Lower number = more prominent. This
  is how all ~660 usages already read, and inverting the values flips the app between light and
  dark without touching a component. All text tiers clear WCAG AA (≥4.5:1).
- **`text-ink` is primary text; `text-white` means literally white** and is correct only on a
  saturated fill (a blue button, the active nav pill).

### Privacy

All monetary values render through `<Amount>` / `useMaskedText()` backed by `privacyStore`. New
number rendering must go through it or the privacy toggle silently stops working.

---

## 8. Scheduled jobs

| Job | Cadence | Purpose |
|---|---|---|
| `GmailSyncScheduler` | periodic | pull new email |
| `HoldingReconciliationScheduler` | nightly | replay ledgers, log drift |
| `DepositMaturityScheduler` | nightly | flip `ACTIVE` → `MATURED` past maturity |
| `MfNavHistoryScheduler` | periodic | refresh NAV history |

---

## 9. Known gaps

- **AI latency in the sync path** (§4) — the most important one to address next.
- `DB_PASSWORD` still has a committed dev default (`marketai_pass`). Local-only DB, but it is a
  committed credential. `JWT_SECRET` was fixed (§SETUP).
- 12 entities use a bare `Long userId` with an index but **no FK** to `users` — no DB-enforced
  orphan protection. `Holding`/`Portfolio`/`ActionItem`/`CashAccount` use proper `@JoinColumn` and
  are the pattern to follow.
- AMFI NAV fetch fails on a PKIX/SSL cert-trust error against `amfiindia.com`.
- No `RewardTransaction` ledger — card points are a single mutable integer, so "expiring points"
  and redemption history can't be built yet.
