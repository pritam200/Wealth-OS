# EXPENSE TRACKER + MONTHLY INVESTMENT PLANNER UPGRADE

Act as a **Senior Product Manager + FinTech UX Engineer + Backend Engineer**.

Upgrade the existing **Expense / Cash Flow section**.

Do not remove existing functionality. Add the following capabilities and integrate them with the existing financial ledger and Gmail sync.

---

# 1. MANUAL EXPENSE ENTRY — REQUIRED

The Expense Tracker currently does not give me a proper option to manually enter an expense.

Add a prominent:

### + Add Expense

button.

Allow manual entry of:

* Amount
* Date
* Merchant / paid to
* Category
* Subcategory
* Payment method
* Bank / credit card / cash account
* Notes
* Recurring or one-time
* Attachment/receipt optionally

Example:

```text
Rent
₹31,000
23 Sep 2026
Paid to: Landlord
Payment account: HDFC Bank
Category: Housing
```

After saving:

* Add it to the expense ledger.
* Include it in monthly expense totals.
* Include it in budget calculations.
* Include it in cash-flow analysis.
* Make it available for reconciliation with Gmail/bank data.

---

# 2. MANUAL RENT PAYMENT

Rent should have a dedicated convenient flow.

Allow:

### + Add Rent Payment

Fields:

* Month
* Amount
* Paid date
* Paid to
* Bank/account
* Payment method
* Transaction/reference ID
* Notes

If rent is recurring, allow the user to create a recurring rent schedule.

Example:

```text
Monthly Rent
₹31,000
Due: 1st of every month
Account: HDFC Bank
```

Then show:

```text
September Rent
₹31,000
✓ Paid

October Rent
₹31,000
○ Upcoming
```

If Gmail/bank sync later detects the actual payment, automatically match it rather than creating a duplicate expense.

---

# 3. ADD "MONTHLY INVESTMENT PLAN"

Inside the same Cash Flow / Expense area, add a separate section:

# Monthly Investment Plan

This is NOT the investment portfolio.

It is a **monthly planning and tracking layer**.

The purpose is:

> "At the beginning of the month, I decide how much money I intend to invest, from which bank account, into what investment, and then the system tracks whether I actually completed it."

---

# 4. SCHEDULED INVESTMENTS

Create another section:

# Scheduled Investments

Track recurring investments such as:

* RD
* SIP
* Mutual Fund SIP
* Stock SIP
* ETF SIP
* recurring broker investment
* other scheduled investments

Each schedule should contain:

```text
Investment
Amount
Frequency
Due date
Source bank/account
Destination
Status
```

Example:

```text
HDFC RD
₹10,000
Monthly
5th
HDFC Bank
Scheduled

SBI MF SIP
₹5,000
Monthly
10th
HDFC Bank
Scheduled
```

---

# 5. MONTHLY LUMP-SUM INVESTMENT PLAN

Add:

# This Month's Investments

At the beginning of each month, I should be able to manually define how much I WANT to invest.

Example:

```text
September 2026

HDFC Bank → ₹35,000
ICICI Bank → ₹20,000

Total planned investment
₹55,000
```

Allow multiple investment entries.

Example:

| Source Account | Planned Amount | Investment Type | Destination |
| -------------- | -------------: | --------------- | ----------- |
| HDFC Bank      |        ₹20,000 | Mutual Fund     | SBI MF      |
| HDFC Bank      |        ₹15,000 | Stocks          | m.Stock     |
| ICICI Bank     |        ₹10,000 | Mutual Fund     | ICICI MF    |
| ICICI Bank     |        ₹10,000 | Stocks          | Upstox      |

Total:

**₹55,000**

---

# 6. PLANNED VS ACTUAL

This is the most important part.

The system must compare:

```text
PLANNED
vs
ACTUAL
```

Example:

```text
September Investment Plan

Planned:       ₹55,000
Invested:      ₹45,000
Remaining:     ₹10,000

Progress:      81.8%
```

Break it down:

```text
HDFC Bank
Planned: ₹35,000
Actual:  ₹35,000
Status: ✓ Complete

ICICI Bank
Planned: ₹20,000
Actual:  ₹10,000
Remaining: ₹10,000
Status: ⚠ Partial
```

---

# 7. AUTOMATIC RECONCILIATION WITH BANK DATA

The monthly investment plan must connect to the actual financial ledger.

Do NOT require the user to manually mark every investment as complete.

If Gmail/bank sync detects:

```text
HDFC Bank
₹15,000
Transfer to m.Stock
```

and the monthly plan contains:

```text
HDFC → m.Stock
₹15,000
```

automatically match them.

Status becomes:

`✓ Completed`

---

# 8. MATCH INVESTMENTS ACROSS DIFFERENT DESTINATIONS

The system should recognize investment flows such as:

```text
Bank
 ↓
m.Stock
 ↓
Stock purchase
```

or:

```text
Bank
 ↓
Upstox
 ↓
Stock purchase
```

or:

```text
Bank
 ↓
SBI Mutual Fund
 ↓
MF units purchased
```

or:

```text
Bank
 ↓
RD
```

Do not only look for the word "investment."

Use:

* transaction reference
* merchant/provider
* amount
* date
* account
* broker
* AMC
* folio
* UTR
* transaction type

---

# 9. DO NOT DOUBLE-COUNT TRANSFERS

Important:

If:

```text
HDFC Bank
₹20,000 debit
        ↓
m.Stock
₹20,000 credit
        ↓
Stock purchase
```

the bank-to-broker transfer is NOT itself an expense.

Similarly:

```text
Bank → Mutual Fund
```

is an investment transfer, not a normal household expense.

Classify it correctly.

The system should distinguish:

```text
Expense
Investment
Transfer
Income
Refund
```

---

# 10. LUMP-SUM INVESTMENT VS SIP

The monthly planner should support both:

### Scheduled

Example:

```text
SBI MF SIP
₹5,000
10th every month
```

### Manual Monthly Lump Sum

Example:

```text
September
HDFC → ₹35,000
ICICI → ₹20,000
```

The user can decide the destination later if required.

The planner should still track whether the planned amount was actually invested.

---

# 11. PARTIAL COMPLETION

Support:

```text
Planned: ₹35,000
Actual: ₹20,000
Remaining: ₹15,000
```

Status:

`PARTIALLY COMPLETED`

If another ₹15,000 investment is detected later:

```text
Actual: ₹35,000
Remaining: ₹0
```

Status becomes:

`COMPLETED`

---

# 12. OVER-INVESTMENT

If:

```text
Planned: ₹20,000
Actual: ₹25,000
```

show:

```text
Planned: ₹20,000
Actual: ₹25,000
Over-invested: ₹5,000
```

Do not mark the extra amount as a duplicate.

---

# 13. MONTH-END INVESTMENT REVIEW

At month-end provide:

# Investment Plan Review

Example:

```text
September 2026

Planned Investments       ₹55,000
Completed                 ₹45,000
Pending                   ₹10,000
Over-invested              ₹0
Completion Rate             81.8%
```

Then show:

### Completed

✓ HDFC → m.Stock ₹15,000
✓ HDFC → SBI MF ₹20,000
✓ ICICI → ICICI MF ₹10,000

### Pending

⚠ ICICI → Planned Investment ₹10,000

This should be based on actual ledger data.

---

# 14. ALIGN WITH BANK BALANCES

The planner should also show whether the planned investment is realistically funded.

Example:

```text
HDFC Bank

Current available balance: ₹52,000
Planned investments:       ₹35,000
Planned expenses:           ₹8,000

Projected remaining:        ₹9,000
```

If planned investments exceed available/expected funds:

```text
⚠ Funding shortfall

Planned: ₹35,000
Available after planned expenses: ₹25,000
Shortfall: ₹10,000
```

Do not automatically move money or execute anything.

This is a planning/monitoring feature unless an explicit execution integration exists.

---

# 15. INVESTMENT PLAN SHOULD NOT ALTER ACTUAL WEALTH

Important distinction:

### Plan

What I intend to invest.

### Actual

What I actually invested.

The plan must NOT:

* create fake holdings
* increase net worth
* create fake transactions
* increase portfolio value
* show planned investments as completed

Only verified financial transactions affect actual wealth.

---

# 16. MONTHLY PLANNER UI

Arrange the section approximately as:

```text
MONTHLY CASH FLOW
────────────────────────────

Income              ₹2,00,000
Expenses              ₹65,000
Planned Investments   ₹55,000
Remaining Planned     ₹80,000


MONTHLY INVESTMENT PLAN
────────────────────────────

Planned       ₹55,000
Invested      ₹45,000
Remaining     ₹10,000
Progress         81.8%


HDFC BANK
₹35,000 / ₹35,000
██████████ 100%
✓ Completed


ICICI BANK
₹10,000 / ₹20,000
█████░░░░░ 50%
⚠ ₹10,000 remaining


SCHEDULED INVESTMENTS
────────────────────────────

HDFC RD       ₹10,000   ✓
SBI MF SIP     ₹5,000   ✓
ICICI SIP      ₹5,000   Upcoming


RECENT INVESTMENT ACTIVITY
────────────────────────────

23 Sep  m.Stock     ₹15,000 ✓
20 Sep  SBI MF       ₹5,000 ✓
15 Sep  ICICI MF    ₹10,000 ✓
```

Keep the sections collapsible if the existing UI supports expandable cards.

---

# 17. MONTHLY RESET

At the beginning of every month:

Create a new monthly planning period.

Example:

```text
September 2026
October 2026
November 2026
```

Do NOT delete previous plans.

Historical months must remain available for analysis.

---

# 18. RECURRING SCHEDULE MANAGEMENT

Allow:

* Add schedule
* Edit schedule
* Pause schedule
* Resume schedule
* Delete schedule
* Change amount
* Change bank
* Change date
* Change destination

Maintain historical records of changes.

Example:

```text
SBI SIP

Jan–Jun: ₹5,000
Jul–Sep: ₹7,500
Oct onward: ₹10,000
```

Do not rewrite historical investments when the schedule changes.

---

# 19. DUPLICATE PROTECTION

Manual entry + Gmail sync + scheduled investment detection must all use the same financial ledger.

Example:

User manually enters:

```text
Rent ₹31,000
```

Then Gmail later detects the same rent payment.

The system should identify the matching transaction and link the sources instead of creating:

```text
₹31,000 manual
+
₹31,000 Gmail
=
₹62,000
```

Same principle applies to investments.

---

# 20. DATA MODEL

Separate:

### Planned Investment

```text
planId
month
sourceAccount
plannedAmount
investmentType
destination
scheduled/oneTime
dueDate
status
```

### Actual Transaction

Existing financial ledger.

### Reconciliation

```text
planId
transactionId
matchedAmount
matchConfidence
matchedAt
```

Never mix planned records with actual financial transactions.

---

# 21. ACCEPTANCE CRITERIA

The feature is complete only when I can:

### Expense

* Manually add any expense.
* Manually add rent.
* Track recurring rent.
* Assign payment account.
* See it in monthly expenses.
* Prevent Gmail duplicate creation.

### Scheduled Investments

* Create RD.
* Create SIP.
* Track recurring investments.
* See due/upcoming/completed status.

### Monthly Investment Plan

At the beginning of the month I can enter:

```text
HDFC Bank → ₹35,000
ICICI Bank → ₹20,000
```

Then throughout the month the system automatically tracks actual investments from:

* bank transactions
* broker transactions
* mutual-fund transactions
* RD/SIP transactions
* investment emails

and shows:

```text
Planned
Actual
Remaining
Completion %
Status
```

### Month End

I can clearly see:

```text
How much I planned to invest
How much I actually invested
How much remains
Which bank account funded it
Where the money went
Which SIP/RD completed
Which lump-sum investment is pending
Whether I over-invested
```

Most importantly:

> **Planned investments must never be treated as actual investments until verified financial transactions exist.**