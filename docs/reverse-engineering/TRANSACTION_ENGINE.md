# Transaction Engine

## Add Transaction Flow
1. POST `/` hits `TransactionRoutes.kt`.
2. Request payload mapped to `Transaction` model.
3. `TransactionService.addTransaction()` inserts a new row with `balanceAfter` set to zero.
4. `performRecalculation()` is invoked to rebuild balances from the start of the ledger.
5. `BackupService.onTransactionEvent("ADD", ...)` triggers asynchronously.

## Edit Transaction Flow
1. PUT `/{id}` hits `TransactionRoutes.kt`.
2. `TransactionService.updateTransaction()` updates fields.
3. If successful, `performRecalculation()` rebuilds balances.
4. `BackupService.onTransactionEvent("UPDATE", ...)` triggers asynchronously.

## Delete Transaction Flow
1. DELETE `/{id}` hits `TransactionRoutes.kt`.
2. `TransactionService.deleteTransaction()` deletes the row.
3. If successful, `performRecalculation()` rebuilds balances.
4. `BackupService.onTransactionEvent("DELETE", ...)` triggers asynchronously.

## `performRecalculation()` Deep Analysis
- Source: `TransactionService.kt`.
- Behavior: Fetches all records `ORDER BY date ASC, id ASC`. Iterates sequentially.
- If `entryType == "Credit"`, adds `amount` to `currentBalance`.
- If `entryType == "Debit"`, subtracts `amount` from `currentBalance`.
- Updates the `balanceAfter` column for each row.

## Financial Overview Calculations
- Found in `getLedgerOverview()`.
- Calculates total credits and debits using SQL `sum()`.
- `latestBalance` is fetched from the *newest* record using `ORDER BY date DESC, id DESC LIMIT 1`.

## Running Balance Calculations
- Controlled entirely by the `performRecalculation()` iterative step rather than mathematical calculation on the fly, ensuring DB state reflects actual running balance.
