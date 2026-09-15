# Wealth-OS Documentation

Personal wealth-management platform for Indian markets: stocks, mutual funds, FD/RD, EPF, bank
cash, credit cards, loans, income/expenses — ingested from Gmail, scored by a local AI/signal
layer, and surfaced as advisory actions.

**Stack (verified from `pom.xml` / `package.json`):** Java 25, Spring Boot 3.5.3, Hibernate/JPA,
PostgreSQL 16, Redis 7, Maven — React 19 + TypeScript + Vite 8, Tailwind 3, Zustand, Recharts.

This suite is grounded exclusively in the current codebase. Every claim in every document was
extracted from an actual controller, entity, config file, or component — not inferred. Where the
code has a real gap (no CI/CD, an incomplete `.env.example`, a Java-version mismatch between
`pom.xml` and the Dockerfile), the docs say so explicitly rather than papering over it.

## Status tags used throughout

- `[IMPLEMENTED]` — working code exists and was verified in this repo.
- `[PARTIALLY IMPLEMENTED]` — exists but incomplete, inconsistent, or has a known gap.
- `[PLANNED / NOT IMPLEMENTED]` — referenced in intent (a rule, a route, a comment) but no working code exists.

## Fast-path navigation by role

| I am a... | Start here |
|---|---|
| **Product Manager / Stakeholder** | [`00-pmo-product/PRODUCT_OVERVIEW.md`](00-pmo-product/PRODUCT_OVERVIEW.md) → [`FEATURE_MATRIX.md`](00-pmo-product/FEATURE_MATRIX.md) → [`ROADMAP_AND_STATUS.md`](00-pmo-product/ROADMAP_AND_STATUS.md) |
| **Architect / Tech Lead** | [`01-architecture/ARCHITECTURE_OVERVIEW.md`](01-architecture/ARCHITECTURE_OVERVIEW.md) → [`DATABASE_ARCHITECTURE.md`](01-architecture/DATABASE_ARCHITECTURE.md) → [`ARCHITECTURE_DECISIONS.md`](01-architecture/ARCHITECTURE_DECISIONS.md) |
| **Backend Engineer** | [`02-backend/BACKEND_ONBOARDING.md`](02-backend/BACKEND_ONBOARDING.md) → [`BACKEND_STRUCTURE.md`](02-backend/BACKEND_STRUCTURE.md) → [`API_SPECIFICATION.md`](02-backend/API_SPECIFICATION.md) |
| **Frontend Engineer** | [`03-frontend/FRONTEND_ONBOARDING.md`](03-frontend/FRONTEND_ONBOARDING.md) → [`FRONTEND_STRUCTURE.md`](03-frontend/FRONTEND_STRUCTURE.md) → [`UI_COMPONENTS_GUIDE.md`](03-frontend/UI_COMPONENTS_GUIDE.md) |
| **DevOps / SRE** | [`04-devops/DEVOPS_ONBOARDING.md`](04-devops/DEVOPS_ONBOARDING.md) → [`ENVIRONMENT_VARIABLES.md`](04-devops/ENVIRONMENT_VARIABLES.md) → [`DEPLOYMENT_GUIDE.md`](04-devops/DEPLOYMENT_GUIDE.md) |
| **Anyone tracing a bug end-to-end** | [`05-flows/`](05-flows/) |
| **Anyone planning the next build** | [`research/README.md`](research/README.md) → [`PRODUCT_GAPS.md`](research/PRODUCT_GAPS.md) → [`TECHNICAL_GAPS.md`](research/TECHNICAL_GAPS.md) |
| **Anyone building the next phase** | [`architecture/README.md`](architecture/README.md) → [`SYSTEM_ARCHITECTURE.md`](architecture/SYSTEM_ARCHITECTURE.md) → [`FINANCIAL_LEDGER.md`](architecture/FINANCIAL_LEDGER.md) |

## Directory map

```
docs/
├── 00-pmo-product/       Product vision, feature matrix, business rules, roadmap
├── 01-architecture/      System design, data flow, DB schema, ADRs
├── 02-backend/           Java/Spring Boot onboarding, layering, financial engine, Gmail pipeline, API reference, jobs
├── 03-frontend/          React onboarding, structure, state/data-fetching, routing/auth, component guide
├── 04-devops/            Infra, environment variables, CI/CD (gap), deployment, monitoring
├── 05-flows/             Cross-cutting traces: Gmail→ledger, transaction processing, card AI
├── research/             Competitive benchmark, product & technical gap matrices (pre-design)
└── architecture/         TARGET architecture: system, data, ledger, diagrams (design phase)
```

## The rule everything in this codebase follows

> **Do not fabricate, estimate, duplicate, silently drop, or overwrite financial records.**

This shows up concretely as: a fingerprint-gated import pipeline (§`05-flows/GMAIL_TO_PORTFOLIO_FLOW.md`),
a ledger-replay holdings model where nothing is ever hand-set (§`02-backend/FINANCIAL_ENGINE.md`),
a confidence ceiling on every AI-derived signal that caps at how much data was actually available
(§`02-backend/FINANCIAL_ENGINE.md`), and a human review queue for anything the system isn't sure
about rather than a silent best-guess import (§`05-flows/GMAIL_TO_PORTFOLIO_FLOW.md`).
