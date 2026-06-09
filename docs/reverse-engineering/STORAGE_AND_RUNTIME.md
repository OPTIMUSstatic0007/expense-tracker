# Storage and Runtime

## `StoragePaths` Analysis
- Resolves the root directory programmatically by looking for `settings.gradle.kts` and `backend/src/main/kotlin`.
- Falls back gracefully to the current working directory.

## Database Locations
- Target file: `<project_root>/backend/backend/data/ledger.db`.

## Backup Locations
- Target directory: `<project_root>/backend/backend/data/backups/`.
- JSON metadata: `backup_meta.json` and `backup_history.json`.

## Asset Locations
- Source assets reside in `backend/src/main/resources/static/` (and mirror versions in `frontend/`).
- Served at the `/` Ktor static route.

## Android Internal Storage Usage
- Room database is configured as `expense_database`.
- Downloads via the WebView are routed to the external `Environment.DIRECTORY_DOWNLOADS`.

## Runtime Resource Loading
- Ktor `defaultResource("index.html", "static")` serves the Single Page Application.
- The Android WebView dynamically fetches resources from the `http://10.0.2.2:8080` backend.
