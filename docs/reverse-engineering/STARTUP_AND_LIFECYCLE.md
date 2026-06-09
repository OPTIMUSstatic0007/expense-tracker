# Startup and Lifecycle

## Application Startup Sequence
1. Standard Ktor execution via `fun Application.module()`.
2. Cross-Origin Resource Sharing (CORS) configuration.
3. Database and Backup initialization.
4. Route binding.

## Service Initialization Order
1. `DatabaseFactory.init()`
2. `BackupService()` constructor call.
3. `backupService.createAutoBackup()`
4. `routing { ... }` binding `TransactionRoutes`.

## Database Initialization Order
1. Connect via JDBC url `jdbc:sqlite:{absolute_path}`.
2. Raw JDBC query executes `PRAGMA journal_mode=WAL;`.
3. Exposed `transaction` validates/creates `Transactions` table schema.

## Backup Initialization Order
- Checks if `isDirty` flag is set in metadata.
- If so, triggers an automated snapshot on startup.

## Dependency Graph
- Ktor application relies fully on `DatabaseFactory` and `BackupService` resolving without errors.
- Frontend relies on Ktor REST routes.
- Android app relies on the Ktor backend being reachable via IP.
