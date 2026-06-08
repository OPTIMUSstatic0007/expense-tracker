# Phase 2A: Offline Bridge Layer for Transactions

## JS Bridge Calls
The frontend asset (`app.js`) interacts with the native Android layer via `window.AndroidBridge` using the following JavascriptInterface methods:
1. `getTransactions(page, limit)`: Synchronously fetches paginated transactions and returns a JSON string containing the data.
2. `addTransaction(transactionJson)`: Creates a new ledger entry synchronously by passing the form payload as a JSON string.
3. `updateTransaction(id, transactionJson)`: Updates an existing ledger entry by its ID using the modified payload as a JSON string.
4. `deleteTransaction(id)`: Flags the corresponding transaction ID as deleted (soft delete).

All bridge methods act synchronously on the JS thread, receiving string responses indicating the outcome (e.g., `{"status": "ok"}`).

## Kotlin Bridge Handlers
The implementation inside `AndroidBridge.kt` takes an injected `LocalRepository` instance to interact with the Android `ExpenseDatabase`.
Each method uses `runBlocking` over `Dispatchers.IO` to execute suspend functions on the JavaBridge thread and returns a String containing the JSON output, which Webkit directly marshals back to JavaScript.

## Serialization Format
The existing local Android `TransactionEntity` schema only possesses native columns for `note`, `type`, `amount`, and `category`.
The Ktor web frontend previously expected extra fields like `expenseType` and `paidTo`.
To avoid rewriting native entities during this iteration and fulfill the strict "preserve UI components" requirement, we dynamically encode/decode UI-specific fields within the entity's `note` field as a JSON map:
```json
{
  "notes": "Original user text",
  "expenseType": "Credit Card",
  "paidTo": "Grocery Store"
}
```
Dates are strictly parsed from DD/MM/YYYY into Epoch UTC milliseconds via `SimpleDateFormat`.

## Threading Model
- WebView Thread -> `window.AndroidBridge.getTransactions()` (JS)
- JavaBridge Thread -> `AndroidBridge.kt` (Kotlin) -> `runBlocking`
- IO Thread -> `LocalRepository.kt` -> `ExpenseDatabase.kt` (Room DAO operations)

## Current Remaining Localhost Dependencies
Although the transaction operations (CRUD) bypass the `127.0.0.1:8080` backend, `app.js` continues to issue network `fetch()` requests for:
- Database Metrics: `/db/stats`
- Analytics/Charts: `/transactions/all`
- Sync/Backup APIs: `/backup/status`, `/backup/snapshot`, `/restore/database`, etc.
- Export APIs: `/export/csv`, `/export/json`, `/export/excel`

These areas remain untouched in Phase 2A as requested.
