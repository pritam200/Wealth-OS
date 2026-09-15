# Product Overview

## Vision

A single, trustworthy source of truth for a user's entire financial life in the Indian market
context — equities, mutual funds, fixed-income (FD/RD/EPF), cash, credit cards, and loans — built
from transactions the system actually observes (via Gmail) rather than manual data entry, with an
advisory layer that tells the user what to do next and why.

## Problem solved

Indian retail investors' financial data is fragmented across broker apps, bank apps, AMC portals,
and credit card statements, none of which talk to each other. Consolidation today typically means
manual spreadsheets or trusting a black-box aggregator. This product instead:

1. Ingests the email trail every bank/broker/AMC already sends (`[IMPLEMENTED]` — 18 institution-specific
   parsers plus a generic bank-alert catch-all, see `02-backend/GMAIL_INGESTION_PIPELINE.md`).
2. Derives holdings and net worth from a replayable transaction ledger rather than accepting a
   hand-entered number (`[IMPLEMENTED]` — `PortfolioService.recomputeFromLedger`).
3. Refuses to guess: every ambiguous or low-confidence import goes to a human review queue instead
   of being silently booked (`[IMPLEMENTED]` — `EmailReviewItem`/`ReviewStatus`).
4. Surfaces a daily, risk-adjusted action list (buy/sell/hold with rationale) instead of a wall of
   raw numbers (`[IMPLEMENTED]` — Today's Investment Actions, `TodaysActionsService`).

## Core features (at a glance — see FEATURE_MATRIX.md for the authoritative status of each)

| Feature | One-line description |
|---|---|
| Portfolio & Net Worth | Stocks, MF, FD/RD, EPF, cash, loans consolidated into one number via `PortfolioContextService` |
| Gmail Ingestion | OAuth-connected inbox scan → parse → dedup → ledger commit, with push (real-time) and scheduled (30-min safety net) triggers |
| AI Email Classification | Local LLM (Ollama, `qwen2.5:7b` default) fallback for emails no deterministic parser recognizes, gated at 85% confidence |
| Human Review Queue | Anything below the confidence bar, or of a type that must never be auto-booked (e.g. internal transfers), is queued for Accept/Edit/Reject |
| Signal Engine | Multi-factor (trend/momentum/structure/volume/volatility) BUY/SELL/HOLD scoring with ATR-based stop/target, confidence capped by data availability |
| Card Optimizer | Net-value verdict (Keep/Downgrade/Cancel/Underused) per credit card against the user's own 90-day spend |
| Today's Investment Actions | Daily buy/sell/hold/watch queue with execute/snooze/note actions |
| FD/RD Lifecycle | Automatic renewal detection (old FD → new FD linking) so a renewed deposit is never double-counted |
| MF Redemption & Tax | STCG/LTCG tax computation per redemption, with a staged reinvestment ("deployment plan") tracker |
| Financial Planning | Goals, reminders, tax summary, recurring investments (SIP/PPF/NPS) |
| Privacy Masking | One global toggle hides every rupee figure app-wide (`useMaskedText`/`Amount`) |

## Who this is for

A single authenticated user managing their own finances (multi-tenant by `User` FK throughout, but
no team/shared-account concept exists in the schema). The product is not a robo-advisor — it never
places trades; every recommendation requires the user to act on it themselves (or, at most,
mark it executed after the fact).
