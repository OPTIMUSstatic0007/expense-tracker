# Analytics and Stats Analysis

## Current Analytics Capabilities
Currently, there is no true frontend "analytics" dashboard (e.g., Chart.js visualizations, category breakdown pies) built into the UI. The "Analytics" functionality exists purely as raw financial data aggregations and backend calculations to fuel the basic ledger dashboard overview.

## The Financial Overview Engine (`getLedgerOverview`)
### API Wiring
- Executed during the `GET /` (Paged Transactions) API call in `TransactionRoutes.kt`.
- The aggregated payload is returned inside the `PagedTransactionsResponse` object:
  ```json
  {
      "globalBalance": 1000.00,
      "totalCredit": 1500.00,
      "totalDebit": 500.00,
      "transactions": [...]
  }
  ```

### How It Works (`TransactionService.kt`)
1. **Latest Balance (`globalBalance`)**: Instead of summing all rows, it queries the *newest* row (using `ORDER BY date DESC, id DESC LIMIT 1`) and extracts the `balanceAfter` column. This guarantees O(1) performance for the global balance since `performRecalculation()` ensures this column is perfectly accurate.
2. **Total Credit**: Selects the sum of `amount` where `entryType eq "Credit"`.
3. **Total Debit**: Selects the sum of `amount` where `entryType eq "Debit"`.

### Frontend Wiring (`app.js`)
- Handled by `updateDashboard(..., pagedData)`.
- Replaces the DOM elements `#balance`, `#totalCredit`, and `#totalDebit` directly with the formatted values from the JSON response.

## Database Stats Engine (`/db/stats`)
### API Wiring
- Exposed via `GET /db/stats` in `TransactionRoutes.kt`.
- Primarily intended as a read-only stats panel for monitoring the backend health (often used in Mobile views or debug panels).

### How It Works
- **Total Transactions**: Queries `TransactionService.getTotalCount()`.
- **Database Size**: Checks the physical file length of `StoragePaths.databaseFile` in bytes.
- **Backup Status**: Injects `backupService.getBackupStatus()`, pulling data from `backup_meta.json` to show pending syncs and last backup times.

## Future Analytics Implementation Considerations
- The backend currently lacks grouped aggregation APIs (e.g., "Expenses grouped by Category per Month").
- Any future frontend visual analytics (like pie charts) will require either:
  1. Adding a new Ktor route (e.g., `/analytics/categories`) to perform `GROUP BY` operations in Exposed.
  2. The frontend pulling all transactions into memory and using Javascript `.reduce()` to bucket the data for rendering.
