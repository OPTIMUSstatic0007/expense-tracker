# Frontend-Backend Complete Interaction Flows

This document details the exact flow pipeline for every major interaction in the application.

## 1. Initial Page Load & Rendering Pipeline
**Trigger:** User opens app -> WebView loads `http://localhost:8080/`.
1. **Frontend:** `app.js` executes `initTheme()`.
2. **Frontend:** `setupEventListeners()` binds UI actions.
3. **Frontend:** Calls `loadTransactions(false)`.
4. **API Call:** `fetch('/transactions?page=1&limit=30')`.
5. **Backend Route:** Ktor `get("/")` within `TransactionRoutes`.
6. **Service Layer:** `TransactionService.getPagedTransactions` + `getLedgerOverview`.
7. **Database Layer:** SQLite `Transactions.selectAll()` and aggregated sum queries.
8. **Response:** JSON containing transactions array and total balances.
9. **Frontend Update:** Renders transactions into `#history-body`, updates global overview cards.
10. **Background Activity:** `fetchDbStats()` and `updateBackupStatus()` may run based on UI drawer/panel states.

## 2. Transaction Creation Flow
**Trigger:** User clicks "+ Add" button, fills form, clicks "Save".
1. **UI Element:** `#save-btn` click triggers validation.
2. **Frontend JS:** Reads form input values, constructs transaction object.
3. **API Call:** `fetch('/transactions', { method: 'POST', body: JSON })`.
4. **Backend Route:** Ktor `post("/")` within `TransactionRoutes`.
5. **Service Layer:** `TransactionService.addTransaction(transaction)`.
6. **Database Layer:** SQLite `Transactions.insert`.
7. **Background Sync:** `BackupService.onTransactionEvent("ADD", ...)` updates backup metadata asynchronously.
8. **Response:** 201 Created with inserted transaction details.
9. **Frontend Update:** Clears form, re-fetches first page `loadTransactions(false)`.

## 3. Analytics Rendering Pipeline
**Trigger:** User clicks "Analytics" drawer item.
1. **UI Element:** Drawer "Analytics Center" click.
2. **Frontend JS:** `openAnalyticsPanel()` -> `loadAnalyticsData()`.
3. **API Call:** `fetch('/transactions/all')`.
4. **Backend Route:** Ktor `get("/all")` within `TransactionRoutes`.
5. **Service Layer:** `TransactionService.getAllTransactions()`.
6. **Database Layer:** SQLite query for all records.
7. **Response:** JSON array of ALL transactions.
8. **Frontend JS:** `processAnalyticsData(data)` aggregates data by category and month.
9. **Frontend Update:** `renderCharts()` uses Chart.js to paint `categoryBreakdownChart` and `incomeVsExpenseChart`.

## 4. DB Center Status Pipeline
**Trigger:** User opens the "Database Center" panel.
1. **UI Element:** `#drawer-db-center-link` click.
2. **Frontend JS:** `openDbCenter()` -> `fetchDbStats()`.
3. **API Call:** `fetch('/db/stats')`.
4. **Backend Route:** Ktor `get("/stats")` inside `route("/db")`.
5. **Service Layer:** `TransactionService.getTotalCount()` & `BackupService.getBackupStatus()`.
6. **Database/File:** Checks `.db` file size on disk (`StoragePaths.databaseFile`).
7. **Response:** JSON map with metrics.
8. **Frontend Update:** Populates DB Center elements (`#dbcenter-total-txns`, `#dbcenter-db-size`, etc.).

## 5. Export Action Pipeline
**Trigger:** User clicks Export (Excel/CSV).
1. **UI Element:** Export button click.
2. **Frontend JS:** Reads filters, builds URL.
3. **Browser Navigation:** Uses `window.location.href = /export/excel?...` (triggers file download, natively intercepted by WebView `DownloadListener`).
4. **Backend Route:** Ktor `get("/export/excel")`.
5. **Service Layer:** `ExportService.generateExcel(filteredData)`.
6. **Response:** File stream with `Content-Disposition: attachment`.
7. **Android System:** `DownloadManager` intercepts and saves file.

## 6. Update/Delete Transaction Pipeline
**Trigger:** User clicks "Save" on an edit form or clicks the "Delete" icon.
1. **UI Element:** `#save-btn` (with `editingId` set) or delete icon click.
2. **Frontend JS:** Constructs JSON payload (for edit) or confirms deletion.
3. **API Call:** `fetch('/transactions/{id}', { method: 'PUT'/'DELETE' })`.
4. **Backend Route:** Ktor `put("/{id}")` or `delete("/{id}")` within `TransactionRoutes`.
5. **Service Layer:** `TransactionService.updateTransaction()` or `deleteTransaction()`.
6. **Database Layer:** SQLite `Transactions.update` or `Transactions.deleteWhere`.
7. **Background Sync:** `BackupService.onTransactionEvent("UPDATE"/"DELETE", ...)` updates backup metadata asynchronously.
8. **Response:** 200 OK.
9. **Frontend Update:** Displays toast, re-fetches first page `loadTransactions(false)`, updates backup status.

## 7. Backup and Restore Pipeline
**Trigger:** User manually clicks "Create Restore Point" or uploads a `.db` file.
1. **UI Element:** `#create-restore-btn` click or `#restore-upload-btn` file selection.
2. **Frontend JS:**
   - Backup: `fetch('/backup/snapshot', { method: 'POST' })`
   - Restore: Creates `FormData` with the file, `fetch('/restore/database', { method: 'POST', body: formData })`.
3. **Backend Route:** Ktor `post("/snapshot")` or `post("/restore/database")`.
4. **Service Layer:** `BackupService.triggerImmediateBackup()` or `BackupService.restoreDatabase()`.
5. **Database/File:** Writes snapshot file to disk, or overwrites `ledger.db` with uploaded file.
6. **Response:** 200 OK.
7. **Frontend Update:** Displays toast, updates backup status, fetches DB stats.

## 8. Authentication Flow (Android Native)
**Trigger:** User opens app or clicks "Test Google Sign In".
1. **UI Element:** Native Jetpack Compose Button `onClick`.
2. **Android App:** Calls `googleAuthManager.getSignInIntent()`.
3. **Android System:** Launches Google Sign-In UI activity (`googleSignInLauncher.launch`).
4. **User Action:** Selects Google account and grants permissions.
5. **Android App Callback:** Receiver catches success/error, calls `googleAuthManager.handleSignInResult()`.
6. **State Update:** Obtains user email/ID, displays a native Toast message. *(Note: Currently, auth state does not gate WebView frontend access.)*

## 9. Search and Filtering Pipeline
**Trigger:** User inputs text in search bar or applies filters in UI (though currently used primarily for Exports).
1. **UI Element:** User applies filters/search.
2. **Frontend JS:** Reads filter/search string state.
3. **API Call:** Filters are sent as query params to backend routes (e.g. `/export/excel?search=xyz&month=05`).
4. **Backend Route:** Ktor route `get("/export/...")` extracts query params `month`, `year`, `category`, `search`.
5. **Service Layer/Controller Logic:** `getAllTransactions().filter { ... }` applies string matching (lowercase search checking category, paidTo, notes) and exact match for date/category.
6. **Database Layer:** (SQLite currently fetched all and filtered in-memory in this route.)
7. **Response:** Filtered dataset processed (e.g., into Excel bytes).
