# Backend Structure

Package root: `com.marketai`. 30 `@RestController` classes found under `**/controller/`.

## Standard layered pattern (representative: `portfolio` domain)

```
Controller  src/main/java/com/marketai/portfolio/controller/PortfolioController.java
Service     src/main/java/com/marketai/portfolio/service/PortfolioService.java   (@Service, @RequiredArgsConstructor, @Slf4j — concrete class, no interface)
Repository  src/main/java/com/marketai/portfolio/repository/{PortfolioRepository,HoldingRepository,TransactionRepository}.java
Entity      src/main/java/com/marketai/portfolio/entity/{Portfolio,Holding,Transaction}.java
DTO         src/main/java/com/marketai/portfolio/dto/{AddHoldingRequest,PortfolioSummaryDto,TransactionDto,IntegrityReportDto,MergeSummaryDto}.java
Extras      scheduler/HoldingReconciliationScheduler.java, util/XirrCalculator.java
```

**Note**: controllers here are not strictly service-only — `PortfolioController` also injects
`HoldingRepository`/`TransactionRepository` directly for a few read-only endpoints, and reaches
into `income.repository.IncomeRepository` (a different domain's repository) as a collaborator
passed into `portfolioService.sellHolding(...)`. Entities use Lombok directly with JPA
annotations — no separate mapper/converter layer (DTOs are hand-built in controller/service code).

## Domain package map (30 controllers + supporting-only domains)

| Domain | Has controller? | Has entity/repository? | Notes |
|---|---|---|---|
| `auth` | ✓ | ✓ | Extra `security/` package (`JwtAuthFilter`, `JwtService`) |
| `ai` | ✓ (`/api/ai`) | ✓ (`AiHistory`) | Extra `audit/`, `client/`, `intel/`, `llm/` packages; nested `ai.review` sub-domain has its own full stack + a *second* REST surface (`/api/review`) |
| `amfi` | ✓ | — | Read-only: AMFI NAV lookup, cached in-memory |
| `analyst` | ✓ | — | Routes through `RecommendationEngine`, no owned entity |
| `actions` | ✓ (2 controllers) | ✓ (`ActionItem`) | `ActionItemController` + `TodaysActionsController` |
| `card` | ✓ | ✓ | Extra `optimizer/` package (`CardOptimizerService`, `SpendAggregator`) |
| `expense` | ✓ | ✓ | |
| `forecast` | ✓ | — | Derived/computed, no owned persistence |
| `gmail` | ✓ (2 controllers) | ✓ | Extra `ai/`, `parser/`, `scheduler/`, `security/` packages; `GmailController` + `GmailPushController` |
| `goal` | ✓ (`/api/goals`) | ✓ (`FinancialGoal`) | |
| `income` | ✓ | ✓ | |
| `ledger` | ✓ | ✓ | Request DTOs (`CashAccountRequest`, `TransferRequest`) are nested static classes inside the controller rather than in `dto/` — inconsistent with the rest of the app |
| `market` | ✓ | ✓ | Extra `client/` package (Yahoo Finance client) |
| `mf` | — | ✓ (`MfNavHistory`) | No controller at all — pure internal/support domain consumed by other services |
| `networth` | ✓ | ✓ (`NetWorthSnapshot`) | |
| `news` | ✓ | ✓ | Extra `client/` package |
| `planning` | (folder absent — reminders/goals/tax live in separate top-level domains, not a `planning` package) | | |
| `portfolio` | ✓ | ✓ | The representative example above |
| `recommendation` | ✓ (2 controllers) | — | `RecommendationController` + `WealthController` (`/api/wealth`) |
| `reconciliation` | ✓ | — | Cross-domain check, distinct from `gmail`'s own `/api/gmail/reconciliation-report` |
| `redemption` | ✓ | ✓ | |
| `reminder` | ✓ | — | `[PARTIALLY IMPLEMENTED]` — read-only, only `GET /api/reminders` exists |
| `scheduled` | ✓ (`/api/recurring-investments`) | ✓ (`RecurringInvestment`) | |
| `signal` | ✓ | — | `SignalEngine` computes on the fly, no owned entity |
| `sync` | ✓ | ✓ (`SyncJob`) | `SyncJobDto` is a nested class inside the controller, not in `dto/` |
| `tax` | ✓ | — | |
| `technical` | ✓ | — | |
| `tracking` | ✓ | ✓ | FD/RD/Loan/EPF/OtherAsset — all sub-resources under one controller |

## Global exception handling

`GlobalExceptionHandler` (`common/exception/`) — `@RestControllerAdvice`:

| Exception | Status | Message behavior |
|---|---|---|
| `ResourceNotFoundException` | 404 | |
| `DuplicateResourceException` | 409 | |
| `BadCredentialsException` | 401 | Hardcoded `"Invalid email or password"` — never leaks `ex.getMessage()` |
| `AccessDeniedException` | 403 | Generic `"Access denied"` |
| `MethodArgumentNotValidException` | 400 | Includes a `validationErrors` field→message map |
| `ExternalApiException` | 503 | ERROR-logged |
| `org.apache.catalina.connector.ClientAbortException` | (no body) | DEBUG-logged only (client disconnected) |
| Generic `Exception` | 500 | `"An unexpected error occurred"`, ERROR-logged |

`[PARTIALLY IMPLEMENTED]`: the generic handler also contains a redundant string-based
`ex.getClass().getName().contains("ClientAbort")` check duplicating the dedicated
`ClientAbortException` handler above it — a minor fragility worth cleaning up.

## Security configuration

See `01-architecture/ARCHITECTURE_OVERVIEW.md` §"Security model" for the full permitAll list and
filter chain. Summary: stateless JWT, `BCryptPasswordEncoder(12)`, `JwtAuthFilter` before
`UsernamePasswordAuthenticationFilter`, no method-level `@PreAuthorize` usage anywhere in the
codebase (`@EnableMethodSecurity` is declared but unused).

## Naming/registration conventions worth knowing

- Parsers (`gmail/parser/*Parser.java`) are plain `@Component` beans — Spring auto-discovers and
  injects the full `List<EmailParser>`; ordering is controlled by `@Order` (see
  `GMAIL_INGESTION_PIPELINE.md`).
- Schedulers live in a `scheduler/` sub-package per domain (`portfolio/scheduler/`,
  `tracking/scheduler/`, `gmail/scheduler/`, `mf/scheduler/`), not centralized.
- No mapper library (MapStruct is a dependency but its actual usage was not confirmed as
  widespread in the controllers inspected — DTO construction appears largely hand-written).
