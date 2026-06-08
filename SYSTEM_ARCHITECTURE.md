# System Architecture

## Overview
The current stable production system uses a hybrid architecture:
- **Android App:** Functions primarily as a wrapper (WebView) serving the web frontend, along with native Google Sign-In capabilities.
- **Frontend:** A static HTML/CSS/Vanilla JS web application serving as the UI.
- **Backend:** A Ktor-based Kotlin application exposing REST APIs for data access and backup/restore.
- **Database Layer:** SQLite for the backend. Currently, an offline-first Room database structure exists in the Android codebase but appears to be not fully integrated with the WebView flow in production yet.

## Components

### 1. Android App
- **Activity:** `MainActivity` uses Jetpack Compose.
- **Authentication:** `GoogleAuthManager` for Google Sign-In.
- **WebView Interface:** Embeds the frontend UI via `ExpenseTrackerWebView`. File download triggers rely on `DownloadManager`.
- **Offline DB (Future integration):** Room DB `ExpenseDatabase`, `TransactionDao`, `LocalRepository`.
- **Cloud Sync:** `FirestoreRepository` for backing up transactions to Firebase.

### 2. Frontend (Static Web App)
- **Files:** `index.html`, `style.css`, `app.js`.
- **State Management:** Handled directly in `app.js` using global variables (`transactions`, `editingId`, `currentPage`).
- **Interaction:** Vanilla JS event listeners. Directly invokes backend APIs.
- **Theme:** Stores light/dark mode preference in `localStorage`.
- **Charts:** Uses `Chart.js` for analytics rendering.

### 3. Backend (Ktor)
- **Application Logic:** `Application.kt` initializes the server, database, backup engine, and CORS.
- **Routes:** `TransactionRoutes.kt` defines endpoints for CRUD operations, exports, backup, and database stats.
- **Services:** `TransactionService`, `BackupService`, `ExportService`.
- **Database:** Uses Jetbrains Exposed for SQLite interactions (`DatabaseFactory.kt`).

## Interaction Model
The primary interaction flow bypasses the native Android Room database for core ledger operations, directly connecting the WebView frontend to the Ktor backend APIs:
Android App (WebView) -> Ktor Backend APIs -> SQLite Database

## Key Subsystems
1. **Dashboard System:** Dynamically renders transaction history, summaries, and charts.
2. **Analytics System:** Generates aggregations per category and income vs. expense metrics using Chart.js.
3. **Backup Engine:** Automatically and manually captures database snapshots (`BackupService.kt`).
4. **Restore Points:** Supports uploading a database file to replace the current database state.
5. **Aggregation & Filtering:** Handled by Ktor routes filtering transactions for exports or views.
