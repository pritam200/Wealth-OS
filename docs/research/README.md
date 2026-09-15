# Research

Competitive and technical research underpinning the Wealth OS product upgrade.
Produced during the RESEARCH → AUDIT → BENCHMARK phases, **before** any design or code.

Read in order:

| # | Document | What it answers |
|---|---|---|
| 1 | **[COMPETITIVE_RESEARCH.md](COMPETITIVE_RESEARCH.md)** | What competitors actually do, how, and with what data. Includes the COPY / IMPROVE / DIFFERENTIATE / REJECT disposition for every feature studied (§9), India tax rules (§10), and every unverified claim (§11) |
| 2 | **[COMPETITOR_FEATURE_MATRIX.md](COMPETITOR_FEATURE_MATRIX.md)** | Product · Feature · How it works · Data required · Wealth OS equivalent · Our improvement |
| 3 | **[PRODUCT_GAPS.md](PRODUCT_GAPS.md)** | Area · Wealth OS Today · Competitors · Gap · Priority (P0–P3) |
| 4 | **[TECHNICAL_GAPS.md](TECHNICAL_GAPS.md)** | System · Current · Problem · Industry Pattern · Proposed Architecture |

## How to read the labels

Every research claim carries its evidence grade, preserved from the source agent:

- **[A]** documented, with a URL or a live HTTP probe
- **[B]** inference from observable product behaviour
- **[C]** speculation

Repository claims cite `file:line` and were verified against the code, not assumed.
Implementation status uses the same tags as the rest of `/docs`:
`[IMPLEMENTED]` · `[PARTIALLY IMPLEMENTED]` · `[PLANNED / NOT IMPLEMENTED]` · `[NOT BUILT]`.

## Standing constraints these documents serve

> Do not fabricate, estimate, duplicate, silently drop, or overwrite financial records.
>
> EVERY FINANCIAL NUMBER MUST BE TRACEABLE. EVERY RECOMMENDATION MUST HAVE A REASON.
> EVERY IMPORT MUST BE RECONCILABLE. EVERY DECISION MUST USE VERIFIED DATA.

Two research findings map directly onto these:

- **Grounded verbatim source spans** (COMPETITIVE_RESEARCH §6.3) are the mechanism that makes
  "every financial number traceable" true at the field level rather than the email level.
- **Binary pass/fail checks** (§2.5, Simply Wall St) are what makes "every recommendation has a
  reason" explainable — a weighted z-score composite cannot be explained; "failed 4 of these 6
  named checks" can.

## Status

Research phase **complete** — 14 agents, all reported. Design phase (`docs/architecture/`) not
yet started. **No code has been written against these findings.**
