# API Wiring Map

This document maps the connections between frontend actions and backend API endpoints.

## Base URLs
- **API_BASE_URL:** `/transactions`
- **EXPORT_BASE_URL:** `/export`
- **BACKUP_BASE_URL:** `/backup`

## Transaction APIs
| Frontend Trigger | HTTP Method | Endpoint | Backend Service | Description |
|------------------|-------------|----------|-----------------|-------------|
| `loadTransactions(false)` | `GET` | `/transactions?page=1&limit=30` | `TransactionService.getPagedTransactions` | Fetches the first page of transactions. |
| `loadTransactions(true)` | `GET` | `/transactions?page={N}&limit=30` | `TransactionService.getPagedTransactions` | Fetches subsequent pages (pagination). |
| `saveBtn.click` (New) | `POST` | `/transactions` | `TransactionService.addTransaction` | Creates a new transaction. |
| `saveBtn.click` (Edit)| `PUT` | `/transactions/{id}` | `TransactionService.updateTransaction` | Updates an existing transaction. |
| `deleteTransaction` | `DELETE`| `/transactions/{id}` | `TransactionService.deleteTransaction` | Deletes a transaction. |

## Analytics API
| Frontend Trigger | HTTP Method | Endpoint | Backend Service | Description |
|------------------|-------------|----------|-----------------|-------------|
| `loadAnalyticsData` | `GET` | `/transactions/all` | `TransactionService.getAllTransactions` | Fetches all transactions to compute analytics. |

## DB Center & Backup APIs
| Frontend Trigger | HTTP Method | Endpoint | Backend Service | Description |
|------------------|-------------|----------|-----------------|-------------|
| `fetchDbStats` | `GET` | `/db/stats` | `TransactionService`, `BackupService` | Retrieves DB size, transaction count, backup status. |
| `updateBackupStatus` | `GET` | `/backup/status` | `BackupService.getBackupStatus` | Checks the status of the sync/backup. |
| `handleRestore` | `POST` | `/restore/database` | `BackupService.restoreDatabase` | Uploads a `.db` file to restore the state. |
| `createRestorePoint` | `POST` | `/backup/snapshot` | `BackupService.triggerImmediateBackup` | Manually creates a backup snapshot. |

## Export APIs
| Frontend Trigger | HTTP Method | Endpoint | Backend Service | Description |
|------------------|-------------|----------|-----------------|-------------|
| `triggerExport('excel')` | `GET` | `/export/excel` | `ExportService.generateExcel` | Downloads an XLSX representation of transactions. |
| `triggerExport('csv')` | `GET` | `/export/csv` | `ExportService.generateCsv` | Downloads a CSV representation of transactions. |

## Localhost / Dynamic Dependencies
Currently, the frontend completely relies on the host (wherever the backend serves it) for dynamic data. Because the app runs on `http://localhost:8080` in the WebView, all relative API calls (e.g. `fetch('/transactions')`) implicitly resolve to the Ktor server backend.
