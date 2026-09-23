# Duplicate expense/income cleanup — review before running

Generated 2026-09-23, all rows belong to `user_id = 1`. These are the live duplicate rows
caused by the cross-day dedup bug fixed in `ParsedEmailImporter.isDuplicateExpense`/
`isDuplicateIncome` (a parser that couldn't extract a transaction date fell back to
`LocalDate.now()`, so re-syncing the same email on a later calendar day produced a second row
that the old same-day-only dedup check never saw). The fix stops this from happening again;
it does not touch any row that already exists. Nothing below has been deleted — this is a
proposal for you to review.

**Proposed rule: for each group (same `user_id` + `source_email_id`), keep the row with the
earliest `expense_date`/`income_date` (ties broken by lowest `id`), delete the rest.** Every
group's rows have the identical amount and the identical merchant/description within noise —
verified below — so "keep one, drop the duplicates" loses no information; the correct amount
is still recorded once.

## Expenses — 33 groups, 59 extra rows, ₹80,727.13 overstated

| source_email_id | amount | merchant | keep id | delete ids |
|---|---|---|---|---|
| 19fd7a05f5d44516 | 870.00 | 7308080808 | 18 | 19 |
| 19fdbc6809f0b613 | 16.00 | CRED | 26 | 39, 46 |
| 19fdbc995c191b36 | 2.00 | CRED | 25 | 38, 45 |
| 19febb1a593fbe7f | 24.00 | CRED | 27 | 37, 44 |
| 1a003aa1c4ff5112 | 70.00 | Google Pay | 31 | 36, 43 |
| 1a008f295b2703e6 | 30.00 | Paytm | 30 | 35, 42 |
| 1a00936ef1dceae1 | 100.00 | CRED | 29 | 34, 41 |
| 1a00ad587bc73e59 | 76.00 | Paytm | 28 | 33, 40 |
| 1a02dc93e2dd783e | 45.00 | Google Pay | 53 | 103 |
| 1a02e97115abe20b | 100.00 | Google Pay | 52 | 102 |
| 1a02ea0b80ce83b9 | 30.00 | Paytm | 51 | 101 |
| 1a03298bd63593c1 | 20000.00 | VPA miraeasset | 57 | 100 |
| 1a0329c3d9158c9f | 40000.00 | 7308080808 | 56 | 99 |
| 1a03753581905579 | 761.21 | Paytm | 77 | 98 |
| 1a03c1574ae3fbc1 | 25.00 | Paytm | 76 | 97, 123 |
| 1a0416feb44430d9 | 120.00 | Paytm | 75 | 96, 122 |
| 1a042a9eb8528339 | 462.00 | Flipkart | 74 | 95, 121 |
| 1a043b1ac65273a9 | 33.00 | CRED | 73 | 94, 120 |
| 1a046f33def53d8b | 90.00 | Google Pay | 71 | 93, 119 |
| 1a04b588f202162e | 66.00 | CRED | 70 | 92, 118 |
| 1a04b7a4e19a2e85 | 1766.00 | AJIO | 69 | 91, 117 |
| 1a04d5d9371602a3 | 349.00 | Airtel | 68 | 90, 116 |
| 1a04d618ff81d006 | 1994.61 | CRED | 67 | 89, 115 |
| 1a050a6b85a0bab2 | 333.00 | CRED | 66 | 88, 114 |
| 1a05118da2a07462 | 139.00 | VPA cp | 65 | 87, 113 |
| 1a052091f140d7ac | 304.00 | CRED | 64 | 86, 112 |
| 1a05225c10e0a8ae | 248.00 | Airtel | 63 | 85, 111 |
| 1a0522cc68b18223 | 200.00 | Airtel | 62 | 84, 110 |
| 1a052317e61312d8 | 345.85 | Paytm | 61 | 83, 109 |
| 1a055925d2073d1e | 422.00 | Meesho | 60 | 82, 108 |
| 1a056093cee71399 | 2200.00 | Paytm | 59 | 81, 107 |
| 1a0568a965bca0e6 | 20.00 | CRED | 58 | 80, 106 |
| 1a05d0fc742b9bc3 | 25.00 | CRED | 78 | 79, 105 |

## Incomes — 2 groups, 3 extra rows, ₹66.00 overstated

| source_email_id | amount | keep id | delete ids | note |
|---|---|---|---|---|
| 19fd0773cddc95db | 15.00 | 8 | 9, 11 | descriptions vary slightly across the 3 rows ("crediting into your bank Dividend" / "TITAN COMPANY Dividend") — same source email + same amount, confirmed the same dividend credit re-parsed 3 times |
| 19fd77a1a7cf87be | 36.00 | 7 | 10 | |

## What running this would do

A `DELETE FROM expenses WHERE id IN (...)` / `DELETE FROM incomes WHERE id IN (...)` for
exactly the "delete ids" columns above — 59 expense rows, 3 income rows, 62 rows total. Every
kept row is untouched. Net worth and expense/income totals for `user_id=1` would each drop by
the "overstated" amount shown above (they are currently inflated by exactly that much).

**This SQL has NOT been run.** Tell me to proceed (optionally after spot-checking a few rows
yourself) and I'll execute the deletes, or tell me to hold and I'll leave it as-is. Once these
rows are gone, the `UNIQUE(user_id, source_email_id)` constraint can be added to `expenses`
and `incomes`, matching what's already on `card_statements`/`card_payments`.
