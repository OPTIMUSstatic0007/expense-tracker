# Database Analysis

## Complete Schema (`ledger.db`)
Table: `Transactions`
- `id`: Integer, AutoIncrement, PrimaryKey
- `date`: Varchar(50)
- `entry_type`: Varchar(20) ("Credit" or "Debit")
- `amount`: Decimal(precision=20, scale=4)
- `category`: Varchar(100)
- `expense_type`: Varchar(100)
- `paid_to`: Varchar(100)
- `notes`: Text
- `balance_after`: Decimal(precision=20, scale=4)

## Storage Locations
- Path resolved by `StoragePaths.kt`: `<project_root>/backend/backend/data/ledger.db`.

## `balanceAfter` Behavior
- Computed iteratively during `performRecalculation()` in `TransactionService.kt`.
- Represents the cumulative balance at that specific point in the transaction history.

## Transaction Ordering Source of Truth
- Chronological: The code consistently uses `ORDER BY date ASC, id ASC` to order transactions sequentially (or DESC for reverse chronological display).
- `date` represents a day boundary; `id` resolves ordering for multiple entries on the same day.

## Integer ID Usage Analysis
- Auto-incrementing primary key used for unique identification.
- Crucially acts as the tie-breaker for chronological consistency alongside the date.
