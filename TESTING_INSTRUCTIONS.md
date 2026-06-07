# Compose UI & Room Database Integration Testing Instructions

To verify that the offline-first Room integration is successful with the new Compose architecture, please execute the following verification steps:

## 1. Adding a Transaction
- Open the app and ensure the UI loads correctly.
- Add a transaction. (Currently primarily handled via the WebView bridge or backend in this app context, but native integration testing can be done via unit/instrumentation tests validating `TransactionViewModel.insertTransaction`).
- Verify via the backend or logcat that the transaction has been stored locally inside Room without requiring a network connection.

## 2. Live UI Refresh
- To test the Compose UI reactively updating, monitor the `transactions` state inside `MainActivity.kt`.
- Use the **Android Studio Layout Inspector** to observe the Compose state tree (`transactions` val inside `Scaffold`) or add temporary `Log.d("Test", "Transactions size: ${transactions.size}")` alongside the `collectAsState()` block.
- Insert a record into Room (via App Inspector or another module) and verify the state value natively updates without manual polling.

## 3. Transaction Persistence After App Restart
- Add transactions to populate the database.
- Completely kill the application process (Swipe away from recents).
- Relaunch the application.
- Observe through Android Studio App Inspector or UI state that the identical number of transactions correctly loads on cold start from the local Room database.

## 4. Soft Delete Verification
- Trigger a soft delete for a known transaction ID using `TransactionViewModel.softDeleteTransaction("known-id")`.
- Execute a query on the Room database via App Inspector: `SELECT * FROM transactions WHERE id = 'known-id'`.
- Verify the `deleted` column evaluates to `1` (true).
- Ensure the `syncPending` column evaluates to `1` (true).
- Verify the transaction is immediately removed from the `transactions` flow collected via Compose UI.
