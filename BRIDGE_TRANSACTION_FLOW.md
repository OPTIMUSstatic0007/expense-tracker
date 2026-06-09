# Phase 2A: Offline Bridge Layer for Transactions (REFINED)

## Canonical DB Path
The transaction database used by the entire system (backend APIs, ledger aggregation engine, backup engine) is an Exposed-managed SQLite database.
By default, the backend resolves this to `backend/backend/data/ledger.db` on the desktop filesystem.
In the Android runtime sandbox, `StoragePaths.kt` allows intercepting this path to securely mount the canonical database natively within Android's internal application storage (`context.filesDir/ledger_data/ledger.db`). This prevents filesystem crash exceptions on app launch.

## JS Bridge Calls
The frontend asset (`app.js`) interacts with the native Android layer via `window.AndroidBridge` using the following JavascriptInterface methods:
1. `getTransactions(page, limit)`: Synchronously fetches paginated transactions and returns a JSON string containing the data.
2. `addTransaction(transactionJson)`: Creates a new ledger entry synchronously by passing the form payload as a JSON string.
3. `updateTransaction(id, transactionJson)`: Updates an existing ledger entry by its numeric ID using the modified payload as a JSON string.
4. `deleteTransaction(id)`: Flags the corresponding transaction numeric ID as deleted (soft delete).

All bridge methods act synchronously on the JS thread, receiving string responses indicating the outcome (e.g., `{"status": "ok"}`).

## Kotlin Bridge Handlers
The implementation inside `AndroidBridge.kt` takes an injected `TransactionService` instance from the `:backend` module.
Each method uses `runBlocking` over `Dispatchers.IO` to execute suspend functions on the JavaBridge thread and returns a String containing the JSON output, which Webkit directly marshals back to JavaScript.

## Serialization Schema
The frontend expects transaction objects modeled perfectly after the Ktor API definitions (`id`, `date`, `entryType`, `amount`, `category`, `expenseType`, `paidTo`, `notes`, `balanceAfter`). The `AndroidBridge` parses the native `Transaction` data class from the `backend` module and converts it into precisely the JSON expected by `app.js`.

## Edit / Delete Event Flow
By restoring the `Int` based numeric primary key system instead of `UUID` strings (which broke unquoted inline JS handlers), `editTransaction(id)` and `deleteTransaction(id)` correctly match the target entities and successfully re-render the frontend ledger dynamically.

## Removed Temporary Storage Layers
The `LocalRepository`, `TransactionEntity`, `TransactionDao`, and `ExpenseDatabase` Room structures introduced during the initial bridge migration phase were completely eliminated to ensure a single source of truth (`backend/backend/data/ledger.db`).

## Current Remaining Localhost Dependencies
Although the transaction operations (CRUD) bypass the `127.0.0.1:8080` backend, `app.js` continues to issue network `fetch()` requests for:
- Database Metrics: `/db/stats`
- Analytics/Charts: `/transactions/all`
- Sync/Backup APIs: `/backup/status`, `/backup/snapshot`, `/restore/database`, etc.
- Export APIs: `/export/csv`, `/export/json`, `/export/excel`


## Logging Strategy
To prevent Android runtime crashes caused by JVM-only logging frameworks (`logback-classic`, `slf4j` reflection), the backend components utilize a lightweight platform-aware abstraction (`AppLogger.kt`). On Android, logs fallback safely to `System.out.println` (captured by Logcat), bypassing `org.slf4j.LoggerFactory` entirely.
