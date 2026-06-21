# System Architecture

## Full Application Architecture
The application is a monolith consisting of a Kotlin/Ktor backend serving a static vanilla HTML/JS/CSS frontend, with an Android app providing a native wrapper.
The Android app utilizes a WebView to display the frontend locally or via the Ktor server.

## Frontend
- Resides in `frontend/` and `backend/src/main/resources/static/`.
- Built with vanilla JavaScript, HTML, and CSS without a heavy framework.
- Communicates with the backend REST API via `fetch` requests (e.g., `app.js`).

## Backend
- Built with Kotlin and Ktor.
- Main entry point is `Application.kt` which sets up routes, CORS, and initialization.
- REST API defined in `TransactionRoutes.kt` using structured JSON for requests and responses.

## Database
- SQLite database (`ledger.db`) managed via JetBrains Exposed ORM.
- Storage path is determined by `StoragePaths.kt` inside the backend component.

## Backup Engine
- `BackupService.kt` manages automatic (on startup/mutation) and manual SQLite file backups.
- Metadata and history stored in JSON files (`backup_meta.json`, `backup_history.json`).

## Restore Engine
- `BackupService.kt` handles DB file uploads, creates emergency snapshots, validates SQLite structure, and performs an atomic file swap before calling `DatabaseFactory.resetConnection()`.

## Startup Sequence
1. `Application.kt` module execution begins.
2. `DatabaseFactory.init()` connects to SQLite, enables WAL mode, and validates the schema.
3. `BackupService` triggers an automatic startup backup (`createAutoBackup()`).
4. Ktor routes are registered and the HTTP server begins listening.

## Component Interaction Diagram
(Client/WebView) <--> [HTTP REST API] <--> (Ktor Backend) <--> [TransactionService / BackupService] <--> (SQLite / File System)

## Request/Response Lifecycle
1. User interacts with frontend.
2. JS sends an HTTP request to the Ktor backend.
3. Ktor backend receives the request in `TransactionRoutes.kt`.
4. `TransactionService` performs logic and queries/mutates SQLite via Exposed.
5. If a mutation occurs, `performRecalculation()` updates balances and `BackupService` fires a metadata update.
6. The Ktor backend returns a JSON payload to the frontend.
