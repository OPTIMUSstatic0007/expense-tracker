# Migration Blueprint

This document analyzes the current system architecture to prepare for the future migration to an offline-first Room Database architecture, removing the local Ktor dependency for the Android client.

## Current Production System Overview
Currently, the "Hybrid App" runs a local server (Ktor) inside the app (implied by the current repo structure and port 8080 reliance), rendering the frontend inside a WebView. The frontend communicates with the backend exclusively via REST APIs.

## Candidate Bridge Contracts (Future Android JavascriptInterface)
To migrate away from local REST APIs to direct native integration without modifying the web frontend drastically, the Android app will eventually need to inject a JavascriptInterface (e.g., `AndroidBridge`) into the WebView.

### Mapping API endpoints to Bridge Methods
| Current API Call | Future Bridge Method | Description |
|------------------|----------------------|-------------|
| `GET /transactions` | `window.Android.getPagedTransactions(page, limit)` | Returns JSON string of transactions & overview data. |
| `POST /transactions` | `window.Android.addTransaction(jsonString)` | Inserts a transaction into Room DB. |
| `PUT /transactions/{id}`| `window.Android.updateTransaction(id, jsonString)` | Updates Room DB record. |
| `DELETE /transactions/{id}`| `window.Android.deleteTransaction(id)` | Soft deletes record in Room DB. |
| `GET /transactions/all`| `window.Android.getAllTransactions()` | Returns full JSON for analytics calculation. |
| `GET /db/stats` | `window.Android.getDbStats()` | Returns DB size, record count, and backup info. |
| `POST /backup/snapshot`| `window.Android.createRestorePoint()` | Triggers manual backup or cloud sync. |

## Structural Changes Required for Future Phase
1. **Frontend Refactor:** The `fetch()` calls in `app.js` will need to be wrapped in adapter logic to check for the Android JavascriptInterface. If present, use the bridge; otherwise, fallback to REST API (if keeping web client compatibility).
2. **Android DB Layer:** Fully implement Room DAO methods (note the KSP `suspend` limitation for Unit return types mentioned in memory) and link them to the JavascriptInterface.
3. **Firestore Sync:** Switch cloud sync from Ktor's `BackupService` to Android's native `FirestoreRepository`.

## Strict Phase 0 Rule Enforcement
During this phase, **NO APIs have been migrated, NO architecture has been changed, and NO code has been modified.** This repository of documentation accurately reflects the canonical state of the system as-is.
