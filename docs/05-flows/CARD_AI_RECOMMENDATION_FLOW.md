# Flow Trace: Card AI Advisor & Recommendation Flow

## "Which card should I use for this purchase?" (`POST /api/cards/recommend`)

```mermaid
sequenceDiagram
    participant UI as Tab11Cards (CardOptimizer component)
    participant Ctrl as CardController
    participant Svc as (card recommend logic)
    participant DB as CreditCard + card_reward_rates

    UI->>Ctrl: POST /api/cards/recommend {category, amount}
    Ctrl->>DB: fetch all CreditCard rows for this user
    Ctrl->>Ctrl: for each card, look up rewardRates[category]
    Ctrl-->>UI: List<RecommendResult> ranked by effective reward for this category/amount
```

Reward rates come from `CreditCard.rewardRates` (an `@ElementCollection` map, category→rate) —
manually maintained per card, not derived from any external catalog or issuer feed. A separate,
more structured mechanism (`CardRewardRule`, effective-dated with `isStale()`) also exists in the
schema for versioned rate history, but the simple `recommend` endpoint reads the live map on
`CreditCard` directly.

## "Should I keep, downgrade, or cancel this card?" (`CardOptimizerService.analyse`)

```mermaid
flowchart TB
    A[User's expenses, last 90 days] --> B[SpendAggregator.aggregate]
    B --> C["Bucket by ExpenseCategory<br/>coverage = categorized / total spend"]
    C --> D{coverage >= 50%?}
    D -->|No| E["Verdict: UNKNOWN<br/>'Not enough categorized spend to judge this card'"]
    D -->|Yes| F["For each card:<br/>projected = Σ(category spend × card's rate for that category)"]
    F --> G["netValue = projected − annualFee"]
    G --> H{fee = 0?}
    H -->|Yes| I[KEEP]
    H -->|No| J{netValue > 0?}
    J -->|Yes| I
    J -->|No| K{bestRate = 0?}
    K -->|Yes| L[CANCEL]
    K -->|No| M{projected = 0?}
    M -->|Yes| N[UNDERUSED]
    M -->|No| O["DOWNGRADE<br/>'ask issuer about a no-fee variant'"]
```

**Key honesty constraints, both deliberate**:
1. Categories are **inferred from merchant names**, never presented as real card-network MCC
   data — `SpendAggregator` marks every category `inferred=true`.
2. `rewardsRealized12m` is **always `null`** — the system does not yet have enough per-card
   reward-transaction history to report a *measured* figure, and the code deliberately refuses to
   substitute a projection for a measurement in that field.

## AI Advisor chat / portfolio review (`aiApi.chat`, `aiApi.portfolioReview`)

```mermaid
sequenceDiagram
    participant UI as AiCopilot component
    participant Ctrl as AiController
    participant Svc as (AI service layer)
    participant Gemini

    UI->>Ctrl: POST /api/ai/chat {prompt, symbol?}
    Ctrl->>Svc: build context (portfolio state, symbol data if provided)
    Svc->>Gemini: send prompt + context
    Gemini-->>Svc: narrative response
    Svc-->>UI: AiResponse
```

This path is distinct from the local-first `EmailIntelAgent`/`LlmProviderRouter` used for Gmail
classification (see `02-backend/GMAIL_INGESTION_PIPELINE.md` §6) — `AiController`'s chat/review
endpoints call Gemini directly for narrative generation, not through the same provider-router
abstraction used for structured email classification. Both ultimately depend on
`GEMINI_API_KEY` being configured if Gemini is the active/only provider.

## Today's Investment Actions — the daily signal-to-action bridge

```mermaid
flowchart LR
    SignalEngine["SignalEngine.analyse(symbol)<br/>for every held symbol"] --> Actions["TodaysActionsService"]
    Actions --> Buckets["Buy / Sell-Reduce / Hold / Watch buckets<br/>by signal + portfolio context"]
    Buckets --> ActionItem["ActionItem rows<br/>(user can execute/snooze/note)"]
    ActionItem --> UI["Tab16TodaysActions"]
```

`ActionItem` records what the user *did* with a recommendation (executed/skipped/snoozed) — it
does **not** place any trade. Marking an action "executed" is a manual acknowledgment; the actual
portfolio change (if any) still has to come through the normal transaction path (manual entry or
Gmail import) described in `TRANSACTION_PROCESSING.md`. This system never trades on the user's
behalf.
