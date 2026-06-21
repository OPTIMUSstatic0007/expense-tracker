# Backup Retention Engine

## `BackupService` Architecture
- Manages complete SQLite `.db` file copies located in `StoragePaths.backupsDir`.
- Contains logic for both automated and manual backups.

## Restore Workflow
- A `.db` file is uploaded via POST `/restore/database` in `TransactionRoutes.kt`.
- `restoreDatabase` is called in `BackupService.kt`.
- Creates an "emergency" snapshot of the current state.
- Validates the uploaded file via a raw JDBC check (`SELECT count(*) FROM sqlite_master`).
- Closes/resets the current Exposed connection via `DatabaseFactory.resetConnection()`.
- Performs an atomic copy of the uploaded file to `ledger.db`.
- Recalculates balances and updates backup metadata.

## Retention Workflow
- Triggered internally via `enforceRetentionPolicy()`.
- Uses `MAX_AUTO_BACKUPS` and `MAX_MANUAL_RESTORE_POINTS` limits.
- Sorts the `backup_history.json` entries by timestamp descending.
- Prunes older backups physically from disk and removes them from metadata.

## Metadata Files
- `backup_meta.json`: Tracks aggregate counts, status flags (`isDirty`), and last operation times.
- `backup_history.json`: Tracks individual backup file details (name, type, tx count, balance).

## Restore Points
- Auto backups generated on startup.
- Manual snapshots triggered via API.
- Emergency snapshots generated immediately before an overwrite/restore.

## Failure Recovery Behavior
- The `emergency` backup serves as a rollback safety net if the user restores a corrupt or incorrect database. The validity check ensures non-SQLite files don't brick the app.
