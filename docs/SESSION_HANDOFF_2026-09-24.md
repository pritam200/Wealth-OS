# Session Handoff — Wealth-OS (Indian Markets AI Platform)

**Repo:** `/Users/pksingh/Documents/indian-markets-ai-platform`
**Branch:** `wealth-os-audit-and-hardening` (remote: `github.com/pritam200/Wealth-OS.git`)
**Stack:** Spring Boot 3.5.3 / Java 25 backend (`backend/`), React 19 + TS + Vite + Tailwind frontend (`frontend/`), PostgreSQL 16 (`marketai_db`).

This document summarizes a long working session so a fresh AI session can continue without re-discovering context.

---

## Standing rules (carry these forward)

- **Never fabricate, estimate, duplicate, silently drop, or overwrite financial records.** Every financial number must be traceable; every recommendation must have a reason; every import must be reconcilable; every decision must use verified data.
- Wealth-OS must **never** initiate payments, click payment links, transfer money, or auto-pay bills — strictly detection/reconciliation.
- Never silently overwrite conflicting information or silently discard uncertain transactions.
- Never expose secret files (e.g. `gmail.env`); respect permission-classifier denials (some `cat`/`find` commands touching env/secret-like paths were blocked in this environment — that's expected, not a bug).
- **Only commit/push when the user explicitly asks.** Never force-push. Screen staged files for secrets before staging.
- Commit messages end with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`; PR descriptions end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Org policy: do not use/recommend Claude Fable 5 / Mythos-class models for this work.
- AI extracts, deterministic rules validate, DB constraints enforce — never let an AI guess become financial truth directly.
- When a genuine but out-of-scope bug is found mid-task, prefer flagging it (background task chip) over silently expanding scope — unless it's small/well-scoped and already investigated, in which case fixing it inline under explicit "continue" authorization is acceptable.

---

## Environment notes

- Backend run command: build with `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -o -q package -DskipTests` from `backend/`, then run the jar directly (`java -jar target/indian-markets-ai-platform-1.0.0.jar`). Find/kill the existing process via `lsof -nP -iTCP:8080 -sTCP:LISTEN` before restarting.
- Frontend dev server runs on port 5173; verify with `npx tsc --noEmit -p tsconfig.app.json` from `frontend/` after any change, and drive the Browser pane for live verification.
- Full backend test suite: `mvn -o -q test`, aggregate counts via `grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[:,]' '{tr+=$2; f+=$4; e+=$6; s+=$8} END {...}'`. Last confirmed green run: **639 tests, 0 failures, 0 errors**.
- A throwaway test account exists for UI/API verification: `rent-feature-verify@example.com` / `TestPass123!` (userId 13) and a second one `identity-verify@example.com` / `TestPass123!` — **never use real user accounts** (`pritamsingh798999@gmail.com`, `test@marketai.com`, `test@test.com`, `rishi.raj4662@gmail.com`, `amanjaiswal3690@gmail.com`) for testing.
- **Important discovery this session:** the user runs the actual dev environment from a *different* local clone of the same GitHub repo (`/Users/pritam/Documents/repos/Wealth-OS` on their own machine), separate from this sandbox's checkout (`/Users/pksingh/Documents/indian-markets-ai-platform`). Changes made in this sandbox are **not visible there until committed and pushed**, and pulled by the user. This already caused one incident (see below) — always commit + push (with explicit user confirmation) before assuming the user can see a change.

---

## Work completed this session, in order

### 1. Expense Tracker + Monthly Investment Planner (Phases 1–7 of prior approved plan)

Full plan file: `/Users/pksingh/.claude/plans/i-want-it-to-fizzy-emerson.md` (now marked superseded/complete at the top).

- **Phase 2 — Unified Scheduled Investments:** extended `RecurringInvestment` (`backend/.../scheduled/entity/RecurringInvestment.java`) with `STOCK_SIP`/`ETF_SIP`/`BROKER_RECURRING` types + `sourceAccountId`; added `RecurringInvestmentHistory` (audit trail of amount/status changes) + `PATCH /api/recurring-investments/{id}`; added `ScheduledInvestmentsService`/`Controller` merging `RecurringInvestment` + `RecurringDeposit` into one read-only `GET /api/scheduled-investments` view.
- **Phase 3 — Investment Plan data model:** new `investmentplan` package — `PlannedInvestment` (month/type/status PLANNED|PARTIAL|COMPLETE|OVER_INVESTED) and `InvestmentReconciliation` entities, deliberately never joined into any net-worth-affecting table. `PlannedInvestmentController`: `GET/POST /api/investment-plan/{month}`, `DELETE`, `GET .../review`.
- **Phase 4 — Automatic reconciliation:** `PlannedInvestmentMatcher` (weighted amount/destination scoring, `CONFIRM_THRESHOLD=0.5`) — `matchTransfer()` runs from `LedgerTransferService.record()` right after saving a transfer; `matchRecurringInvestmentCompletions()` runs from `PlannedInvestmentService.listPlan()`. Verified live end-to-end: creating a matching `LedgerTransfer` flips a `PlannedInvestment` from PLANNED → COMPLETE automatically, with zero net-worth change from the plan itself.
- **Phase 5 — Funding check:** `FundingShortfallCheck implements ReconciliationCheck` — flags when open planned investments' remaining amount exceeds the linked cash account's balance. Auto-registered via `ReconciliationRegistry`.
- **Phase 6 — Frontend:** `MonthlyInvestmentPlanCard.tsx`, `ScheduledInvestmentsCard.tsx`, `RentCard.tsx` added to `Tab10Expenses.tsx`.
- **Phase 7 — Tests:** full suite green after each phase (599 → 611 → 618 → 631 → 635 tests).

### 2. Security/bug-fix pass (self-initiated, flagged then fixed)

Found while live-verifying Phase 6 in the browser:

- **Password leak (security):** `POST /api/ledger/accounts` returned the full `User` entity **including the bcrypt password hash** in the JSON response. Fixed with `@JsonIgnore` on `User.password` (`backend/.../auth/entity/User.java`) as a defense-in-depth backstop for *any* entity carrying a `User` relation.
- **500 error:** `POST /api/ledger/transfers` threw `HttpMessageConversionException` on a Hibernate lazy proxy. Fixed by introducing `CashAccountResponse`/`LedgerTransferResponse` DTOs and rewriting `LedgerController` to map through them instead of returning raw entities.
- Added regression tests: `UserPasswordSerializationTest`, `LedgerControllerTest` (verifies no `user`/password field ever serializes).
- Verified live via curl with the throwaway account: accounts/transfers endpoints now return clean DTOs, no leak, no 500.
- Dismissed the now-resolved background-task chip `task_4c7a100d`.
- **Still open, deliberately not fixed (separate background task `task_6c5d7914`):** `email_review_items.item_index` column is missing a NOT NULL backfill — a botched `ddl-auto=update` migration. Needs a proper backfill migration, more invasive than the above fixes, intentionally left as its own task.

### 3. PAN/DOB financial-identity registration + Settings page

- Discovered the backend already had a **fully-built** PAN/DOB-driven statement-password engine (`FinancialIdentityService`, `/api/identity` GET/PUT/DELETE, `PasswordCandidateResolver`, `PasswordStrategy`) — just never wired into the frontend.
- Added optional PAN + DOB fields to `RegisterPage.tsx` (client-side PAN format validation, calls `PUT /api/identity` right after registration; failure doesn't roll back account creation — shows a non-blocking notice with a "Continue to dashboard" button instead).
- Built new `frontend/src/api/identity.ts` client.
- Built a full **Settings page** (`frontend/src/pages/SettingsPage.tsx`) — new tab (id 18) in `App.tsx`'s nav, reachable via the previously-inert top-bar avatar (now a clickable button, `Topbar.tsx`). Lets a user view status, save/replace, or clear their PAN/DOB later (closing the gap where anyone who skipped it at registration had no way to add it).
- Verified end-to-end live in the browser (registration → identity save → settings page load/edit/clear).

### 4. Git sync incident (discovered, diagnosed, resolved)

- The user hit a Vite import error on their own machine: `Failed to resolve import "./pages/SettingsPage"`.
- Root cause: an **auto-commit mechanism in this sandbox** had already committed and pushed changes to `App.tsx`/`Topbar.tsx`/`RegisterPage.tsx` referencing new files (`SettingsPage.tsx`, `identity.ts`, `investmentPlan.ts`, etc.) — but those new files themselves were **never staged/committed**, so they stayed as untracked local-only files. The user's separate clone pulled the broken half.
- Fixed: staged and committed the 12 missing files (`git commit` → `7283f4c`), then pushed to `origin/wealth-os-audit-and-hardening` after explicit user confirmation (the `git push` command itself was blocked by this sandbox's permission classifier — the user ran it themselves in their own terminal and it succeeded).
- **Lesson for future work in this repo:** always run `git status` after making changes and confirm new files are actually committed — don't assume "edited" means "committed," and don't assume the user's dev machine is this sandbox.

### 5. Household Financial Planner — "Log a Spend" quick-add

User asked for a way to add a spend by category+amount directly from the Household Plan tab (previously read-only: actual amounts only came from classified `Expense` rows).

- Researched the actual/planned computation: `PlannerService.getMonthlyPlan` recomputes each expense's planner category **live, at read time** via `PlanCategoryClassifier` (pure keyword-substring match against `merchant`/`description`, no AI, no stored field) unless `Expense.planCategoryOverride` is set. `POST /api/expenses` cannot set this directly — the only way is the existing `PUT /api/expenses/{id}/plan-category` endpoint.
- Added **`QuickAddSpendCard`** to `Tab17FinancialPlanner.tsx`: pick a planner category (grouped by section, sinking-fund categories excluded), enter amount/description/date/payment method/account → calls `POST /api/expenses` then immediately `PUT /api/expenses/{id}/plan-category` to force it into the chosen box (bypassing keyword guesswork).
- Threaded a shared `refreshToken` state through `PlanHeaderSection`, `CategoryBoxGrid`, and `MonthEndReview` so all sections refresh after a save.
- Verified live: added a ₹1,200 "BigBasket order" under Groceries — hero totals, group summary, and the category box all updated correctly immediately.

### 6. Miscellaneous-category misclassification fix

User reported real transactions landing in "Miscellaneous" incorrectly (wrong category, not wrong amount/duplicate). Root cause: `PlanCategoryClassifier`'s keyword lists (seeded once at account creation, in `PlanCategoryDefaults.java`) only match specific brand names (e.g. Groceries knows "bigbasket, blinkit, zepto…") — anything else, including generic phrases, falls to the Miscellaneous fallback category, and **there was no UI to edit keywords**, even though the backend already supported it (`PUT /api/planner/categories/{id}` with `keywords`).

- Added a **pencil/edit icon** on every category box in `Tab17FinancialPlanner.tsx` opening an inline `KeywordEditor` (comma-separated keyword textarea → `plannerApi.updateCategory(id, {keywords})`).
- Special-cased the fallback ("Miscellaneous") category: keywords field disabled, explanatory copy instead of the (nonsensical) generic message.
- Verified live: edited "Parties / Outings" to add `day outing,local outing`; confirmed persisted correctly via network inspection. (Along the way, a keyboard-navigation mistake in my own browser-testing corrupted that field's value mid-test — caught and fixed immediately using `form_input` to set the value directly rather than keystrokes.)
- **Known limitation communicated to user, not yet built:** editing keywords only affects *future* classification — it does **not** retroactively re-bucket transactions already sitting in Miscellaneous. Those must be moved individually via the existing per-transaction "move to a different section" (⇄) button. Offered to build a bulk "re-classify existing Miscellaneous transactions" action — **user has not yet responded to this offer.**

---

## Files touched this session (non-exhaustive, grouped)

**Backend (new):**
`scheduled/entity/RecurringInvestmentHistory.java`, `scheduled/repository/RecurringInvestmentHistoryRepository.java`, `scheduled/dto/{AmountChange,RecurringInvestmentUpdateRequest,ScheduledInvestmentSummary}.java`, `scheduled/service/ScheduledInvestmentsService.java`, `scheduled/controller/ScheduledInvestmentsController.java`, `investmentplan/**` (entities/repos/dto/service/controller), `reconciliation/check/FundingShortfallCheck.java`, `ledger/dto/{CashAccountResponse,LedgerTransferResponse}.java`.

**Backend (modified):**
`scheduled/entity/RecurringInvestment.java`, `scheduled/service/RecurringInvestmentService.java`, `scheduled/dto/{RecurringInvestmentRequest,RecurringInvestmentResponse}.java`, `scheduled/controller/RecurringInvestmentController.java`, `ledger/service/LedgerTransferService.java`, `ledger/controller/LedgerController.java`, `auth/entity/User.java` (`@JsonIgnore` on password).

**Backend (tests, new):** `RecurringInvestmentServiceTest`, `ScheduledInvestmentsServiceTest`, `PlannedInvestmentServiceTest`, `PlannedInvestmentMatcherTest`, `FundingShortfallCheckTest`, `LedgerControllerTest`, `UserPasswordSerializationTest`, plus updates to `LedgerTransferServiceTest`.

**Frontend (new):**
`api/{identity,investmentPlan,recurringInvestment,rent,scheduledInvestments}.ts`, `components/wealth/{MonthlyInvestmentPlanCard,RentCard,ScheduledInvestmentsCard}.tsx`, `pages/SettingsPage.tsx`.

**Frontend (modified):**
`App.tsx` (Settings tab wiring), `components/layout/Topbar.tsx` (avatar → Settings button), `pages/auth/RegisterPage.tsx` (PAN/DOB fields), `pages/tabs/Tab10Expenses.tsx` (new cards), `pages/tabs/Tab17FinancialPlanner.tsx` (`QuickAddSpendCard`, `KeywordEditor`, `refreshToken` threading).

---

## Open items / pending

1. **Bulk re-classify Miscellaneous transactions** — offered to the user, not yet confirmed or built.
2. **`email_review_items.item_index` NOT NULL backfill** (`task_6c5d7914`) — genuinely unaddressed, needs a proper migration strategy.
3. **Anonymized bank/CC statement samples** — still needed from the user for the separate Priority 3 (CC/bank-statement parser/OCR) work; blocked on them, per the "never fabricate parsing logic without real samples" rule.
4. **Priority 7** (collapsing 5 status surfaces onto one state machine) — explicitly deferred as a large, invasive refactor.
5. Verify the user has pulled commit `7283f4c` (and any later ones) on their own machine (`/Users/pritam/Documents/repos/Wealth-OS`) — confirm no further drift between the two clones.
6. Nothing in this session has been committed beyond `7283f4c` — if further work has since been done in this sandbox, check `git status`/`git log` before assuming what's pushed vs. local-only.

---

## How to resume

1. `cd /Users/pksingh/Documents/indian-markets-ai-platform && git status && git log --oneline -5` — confirm current state matches this document.
2. If continuing UI work: start the frontend dev server (port 5173) and backend jar as described above, verify via the Browser pane before/after any change.
3. Re-run the full backend suite after any backend change: expect ≥639 tests, 0 failures/errors.
4. Respect the standing rules at the top of this document at all times.
