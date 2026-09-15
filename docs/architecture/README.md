# Architecture (Target)

**This directory is the architecture we are designing toward.**
[`../01-architecture/`](../01-architecture/) documents the system **as it exists today** and
remains the source of truth for current behaviour. Where the two disagree, `01-architecture`
describes reality and this directory describes intent.

Produced in the DESIGN phase, after
[RESEARCH → AUDIT → BENCHMARK](../research/). **No code has been written against these
documents yet.**

| Document | Scope |
|---|---|
| **[SYSTEM_ARCHITECTURE.md](SYSTEM_ARCHITECTURE.md)** | Layer map, what changes and why, data-acquisition topology, build sequence |
| **[DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md)** | The three data tiers, document/identity/reference entities, MF holdings, attribution, tax lots, migration posture |
| **[FINANCIAL_LEDGER.md](FINANCIAL_LEDGER.md)** | The single-writer invariant, replay semantics, transfer neutrality, the seven properties to preserve |
| **[diagrams/](diagrams/)** | Standalone Mermaid sources (all render-verified) |

## Reading order

1. `SYSTEM_ARCHITECTURE.md` §1–2 — the organising principle and the layer map
2. `FINANCIAL_LEDGER.md` — what already works and must not break
3. `SYSTEM_ARCHITECTURE.md` §3 — the changes, each traced to research evidence
4. `DATA_ARCHITECTURE.md` — the schema that supports them

## Conventions

Sections marked **TARGET** are proposals. Everything else describes verified current behaviour,
cited as `file:line`. Evidence for design choices is labelled **[A]** documented /
**[B]** inference / **[C]** speculation in [`../research/`](../research/) and referenced rather
than restated here.

## Status

| Phase | State |
|---|---|
| Research | Complete — 14 agents, 4 deliverables |
| Audit | Complete — verified against code |
| Benchmark | Complete — disposition recorded in `../research/COMPETITIVE_RESEARCH.md` §9 |
| **Design** | **Complete — this directory** |
| Implement | **Phases 0–3 complete. Phases 4–7 core engines built and tested.** 416 tests green, zero deprecation warnings, zero DDL failures. Remaining: per-AMC MF ingestion (3–6wk), effective-dated card rules, tax-aware rebalancing, routing cutover (needs real mail), sector scoring (needs a fundamentals feed) |

The build sequence is in [`SYSTEM_ARCHITECTURE.md` §6](SYSTEM_ARCHITECTURE.md#6-build-sequence).
Phase 0 is cheap and protective; Phase 4 explicitly keeps the existing scorer serving until a
replacement passes a comparison harness.
