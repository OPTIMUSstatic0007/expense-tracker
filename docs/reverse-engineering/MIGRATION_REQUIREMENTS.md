# Migration Requirements

## Evidence-Based Migration Requirements
Currently, the Android app functions purely as a browser wrapping an external REST API. For a true native offline experience (implied by the memory and the presence of Room DAOs), the frontend must communicate locally via a JavascriptInterface (`AndroidBridge`) to the Room database.

## Legacy -> Room Mapping Plan
- The Ktor `Transactions` table and the Room `TransactionEntity` table share similar domains.
- Room's `TransactionEntity` must map `amount`, `type` ("Credit"/"Debit"), `category`, `note`, `createdAt`, `updatedAt`, `deleted`, and `syncPending`.

## Mandatory Preservation Requirements
- `balanceAfter` calculation logic must be perfectly replicated in the native layer (since it is not stored in the Room DB schema).
- Ordering MUST strictly follow `createdAt DESC` (matching `date ASC, id ASC` in legacy).
- The string format for Types ("Credit", "Debit") must remain exact for UI compatibility.

## Backup Compatibility Requirements
- Existing `.db` backup files must either be converted into Room `.sqlite` formats or the restore mechanism needs to parse raw SQLite and insert it into Room.

## Verification Requirements
- Totals and Balances computed via `getLedgerOverview()` must mathematically match the native sum aggregations.

## Rollback Strategy
- The legacy Ktor backup system (JSON history + .db snapshots) needs to be carefully evaluated or mapped to Android native internal storage (`Context.filesDir`).
