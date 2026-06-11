package com.example.expensetracker.migration

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.expensetracker.local.ExpenseDatabase
import com.example.expensetracker.local.TransactionEntity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID

/**
 * Legacy SQLite → Room import engine.
 *
 * Reads the bundled legacy_ledger.db from app assets, transforms each record
 * to a Room TransactionEntity, and writes all 64 records atomically into Room.
 *
 * ─── Key design decisions ───────────────────────────────────────────────────
 *
 * 1. Date parsing:
 *    Uses its own isolated midnight-based parser. Does NOT call
 *    AndroidBridge.parseDateToLong() which sets time-of-execution, not midnight.
 *    Formula: createdAt = midnight_utc_of(date) + legacyId (ms)
 *    This encodes legacy "ORDER BY date ASC, id ASC" into Room's createdAt field.
 *
 * 2. UUID generation:
 *    Deterministic — UUID.nameUUIDFromBytes("ExpenseTracker-legacy-import:$id")
 *    Same legacy id always produces the same UUID. Safe from v4 collision.
 *    Enables idempotent re-import detection.
 *
 * 3. Transaction isolation:
 *    Uses database.runInTransaction{} (synchronous). All DAO calls are
 *    non-suspending and execute on the calling thread. LocalRepository is
 *    NOT used — bypassing it prevents thread-switch deadlocks inside the
 *    synchronous write lock.
 *
 * 4. Import guard:
 *    SharedPreferences flag prevents re-import. Room non-empty check prevents
 *    import over existing data.
 *
 * ─── Invariants not modified ────────────────────────────────────────────────
 * - DAO ordering (createdAt DESC, updatedAt DESC) — unchanged
 * - AndroidBridge CRUD logic — unchanged
 * - sequenceId schema — unchanged
 * - Backup/retention systems — not touched
 */
class LegacyImportEngine(private val context: Context) {

    companion object {
        private const val TAG = "LegacyImportEngine"

        // Asset filename — must match what was placed in app/src/main/assets/
        private const val ASSET_NAME = "legacy_ledger.db"

        // SharedPreferences keys
        private const val PREFS_NAME  = "legacy_import_prefs"
        private const val PREF_DONE   = "legacy_import_completed"
        private const val PREF_TIME   = "legacy_import_timestamp"
        private const val PREF_COUNT  = "legacy_import_record_count"

        // Namespace for deterministic UUID generation
        private const val UUID_NAMESPACE = "ExpenseTracker-legacy-import"

        // Expected record count — hard-coded for Phase 2B.3 (known dataset)
        private const val EXPECTED_RECORD_COUNT = 64

        // Legacy SQLite table and column names
        private const val TABLE = "Transactions"
        private const val COL_ID            = "id"
        private const val COL_DATE          = "date"
        private const val COL_ENTRY_TYPE    = "entry_type"
        private const val COL_AMOUNT        = "amount"
        private const val COL_CATEGORY      = "category"
        private const val COL_EXPENSE_TYPE  = "expense_type"
        private const val COL_PAID_TO       = "paid_to"
        private const val COL_NOTES         = "notes"
        private const val COL_BALANCE_AFTER = "balance_after"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Public Entry Point ──────────────────────────────────────────────────

    /**
     * Executes the full import pipeline.
     *
     * Called from AndroidBridge on the JavaBridge thread via runBlocking.
     * This method is synchronous — it blocks until complete.
     *
     * @return [ImportResult] describing the outcome.
     */
    fun execute(): ImportResult {
        Log.i(TAG, "Import requested.")

        // Guard 1 — Already imported?
        if (prefs.getBoolean(PREF_DONE, false)) {
            val ts = prefs.getString(PREF_TIME, "unknown")
            val count = prefs.getInt(PREF_COUNT, 0)
            Log.i(TAG, "Import already completed on $ts ($count records). Skipping.")
            return ImportResult.AlreadyImported
        }

        // Guard 2 — Room DB is not empty?
        val database = ExpenseDatabase.getInstance(context)
        val dao = database.transactionDao()

        val existingCount = dao.getActiveTransactionCountSync()
        if (existingCount > 0) {
            Log.w(TAG, "Room DB is not empty ($existingCount records). Import aborted.")
            return ImportResult.RoomNotEmpty(existingCount)
        }

        // Step 1 — Copy legacy asset to a temp file and open it
        val legacyDb = openLegacyAsset() ?: return ImportResult.LegacyDbNotFound(
            "Asset '$ASSET_NAME' not found or could not be opened"
        )

        return try {
            // Step 2 — Read all rows from legacy DB ordered exactly as production used
            val legacyRows = readLegacyRows(legacyDb)
            legacyDb.close()
            Log.i(TAG, "Read ${legacyRows.size} rows from legacy DB")

            // Step 3 — Validate source count before doing any Room writes
            if (legacyRows.size != EXPECTED_RECORD_COUNT) {
                return ImportResult.UnexpectedRecordCount(
                    actual = legacyRows.size,
                    expected = EXPECTED_RECORD_COUNT
                )
            }

            // Step 4 — Transform rows to Room entities (oldest-first, ASC order)
            val entities = legacyRows.map { row -> transformRow(row) }
            // Build lightweight summaries for in-memory Gate 2 validation
            val summaries = entities.map { e ->
                InsertedEntitySummary(id = e.id, type = e.type, amount = e.amount)
            }

            // Step 5 — Atomic Room transaction: insert all + validate inside same lock
            var validationFailure: ImportResult.ValidationFailed? = null

            database.runInTransaction {
                // Insert all entities directly via DAO — NOT via LocalRepository
                // (LocalRepository uses withContext(Dispatchers.IO) which deadlocks here)
                for (entity in entities) {
                    dao.insertTransaction(entity)
                }
                Log.i(TAG, "Inserted ${entities.size} entities into Room")

                // Run three-gate validation inside the same transaction
                validationFailure = ImportValidator.runAll(
                    dao = dao,
                    insertedEntities = summaries,
                    expectedCount = EXPECTED_RECORD_COUNT
                )

                // Throw to trigger automatic SQLite rollback if any gate failed
                if (validationFailure != null) {
                    throw ImportValidationException("Validation failed: ${validationFailure!!.gate}")
                }
            }

            // If we reach here, transaction was committed successfully
            val finalBalance = summaries.fold(0.0) { acc, s ->
                if (s.type == "Credit") acc + s.amount else acc - s.amount
            }

            val result = ImportResult.Success(
                importedCount = EXPECTED_RECORD_COUNT,
                finalBalance = finalBalance
            )

            // Mark import as complete in SharedPreferences
            prefs.edit()
                .putBoolean(PREF_DONE, true)
                .putString(PREF_TIME, System.currentTimeMillis().toString())
                .putInt(PREF_COUNT, EXPECTED_RECORD_COUNT)
                .apply()

            ImportValidator.logReport(result, EXPECTED_RECORD_COUNT)
            Log.i(TAG, "Import SUCCESSFUL — $EXPECTED_RECORD_COUNT records, balance ₹$finalBalance")
            result

        } catch (e: ImportValidationException) {
            // Validation threw inside runInTransaction — Room rolled back automatically
            val failure = ImportResult.ValidationFailed(
                gate = "Validation pipeline",
                detail = e.message ?: "Unknown validation error"
            )
            ImportValidator.logReport(failure, EXPECTED_RECORD_COUNT)
            failure

        } catch (e: Exception) {
            // Any other exception — Room transaction rolled back automatically
            Log.e(TAG, "Unexpected exception during import", e)
            ImportResult.UnexpectedException(e.message ?: "Unknown error")
        }
    }

    // ─── Legacy DB Access ────────────────────────────────────────────────────

    /**
     * Copies the bundled asset to a temp file and opens it as a read-only SQLiteDatabase.
     * Returns null if the asset doesn't exist or can't be opened.
     */
    private fun openLegacyAsset(): SQLiteDatabase? {
        return try {
            // Copy asset → temp file (SQLiteDatabase.openDatabase requires a real file path)
            val tempFile = File(context.cacheDir, "legacy_import_tmp.db")
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Legacy asset copied to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open legacy asset '$ASSET_NAME': ${e.message}")
            null
        }
    }

    /**
     * Reads all rows from the legacy Transactions table.
     *
     * ORDER BY date ASC, id ASC — canonical production ordering (oldest first).
     * This is the ordering used by the legacy performRecalculation() for balance computation,
     * and is the ordering we encode into Room's createdAt field.
     */
    private fun readLegacyRows(db: SQLiteDatabase): List<LegacyRow> {
        val rows = mutableListOf<LegacyRow>()
        val cursor = db.rawQuery(
            "SELECT $COL_ID, $COL_DATE, $COL_ENTRY_TYPE, $COL_AMOUNT, " +
            "$COL_CATEGORY, $COL_EXPENSE_TYPE, $COL_PAID_TO, $COL_NOTES, $COL_BALANCE_AFTER " +
            "FROM $TABLE ORDER BY $COL_DATE ASC, $COL_ID ASC",
            null
        )

        cursor.use {
            while (it.moveToNext()) {
                rows.add(
                    LegacyRow(
                        id           = it.getInt(it.getColumnIndexOrThrow(COL_ID)),
                        date         = it.getString(it.getColumnIndexOrThrow(COL_DATE)),
                        entryType    = it.getString(it.getColumnIndexOrThrow(COL_ENTRY_TYPE)),
                        amount       = it.getDouble(it.getColumnIndexOrThrow(COL_AMOUNT)),
                        category     = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY)),
                        expenseType  = it.getString(it.getColumnIndexOrThrow(COL_EXPENSE_TYPE)),
                        paidTo       = it.getString(it.getColumnIndexOrThrow(COL_PAID_TO)),
                        notes        = it.getString(it.getColumnIndexOrThrow(COL_NOTES)),
                        balanceAfter = it.getDouble(it.getColumnIndexOrThrow(COL_BALANCE_AFTER))
                    )
                )
            }
        }

        return rows
    }

    // ─── Row Transformation ──────────────────────────────────────────────────

    /**
     * Transforms one legacy row into a Room TransactionEntity.
     *
     * Field mapping:
     *  legacy.id           → sequenceId  (direct copy; non-zero bypasses LocalRepository auto-assign)
     *  legacy.id           → uuid        (deterministic; see §4 of blueprint)
     *  legacy.date         → createdAt   (midnight UTC of date + legacyId ms; see §5)
     *  legacy.date         → updatedAt   (same value as createdAt; see §6)
     *  legacy.entry_type   → type
     *  legacy.amount       → amount
     *  legacy.category     → category
     *  legacy.expense_type → note (JSON field "expenseType")
     *  legacy.paid_to      → note (JSON field "paidTo")
     *  legacy.notes        → note (JSON field "notes")
     *  legacy.balance_after → (NOT stored; used only for validation)
     *  —                   → deleted = false
     *  —                   → syncPending = false (imported data is canonical)
     */
    private fun transformRow(row: LegacyRow): TransactionEntity {
        val uuid      = generateDeterministicUuid(row.id)
        val createdAt = parseDateToMidnightMillis(row.date, row.id)
        val noteJson  = buildNoteJson(row.notes, row.expenseType, row.paidTo)

        return TransactionEntity(
            id          = uuid,
            amount      = row.amount,
            type        = row.entryType,   // "Credit" or "Debit" — matches Room convention
            category    = row.category,
            note        = noteJson,
            createdAt   = createdAt,
            updatedAt   = createdAt,       // updatedAt = createdAt for imports (§6)
            deleted     = false,
            syncPending = false,           // canonical — no sync needed
            sequenceId  = row.id.toLong() // non-zero → LocalRepository preserves it
        )
    }

    // ─── Date Parsing ────────────────────────────────────────────────────────

    /**
     * Parses a legacy date string (yyyy-MM-dd) to epoch milliseconds at LOCAL MIDNIGHT
     * plus a small per-record offset (legacyId ms) to preserve intra-day ordering.
     *
     * Formula: createdAt = midnight_local(date) + legacyId
     *
     * Why midnight?
     *   All 64 records originate from the same legacy source with no time component.
     *   Using midnight as a fixed anchor gives a deterministic, reproducible epoch
     *   that doesn't shift between import attempts (unlike AndroidBridge.parseDateToLong()
     *   which uses the current wall-clock time).
     *
     * Why +legacyId?
     *   Multiple transactions may share the same date. The legacyId offset (1–64 ms)
     *   creates distinct createdAt values for same-date rows, encoding the secondary
     *   sort key (id ASC) into the timestamp. The 64ms maximum is negligible and
     *   does not shift the calendar day.
     *
     * This function does NOT call AndroidBridge.parseDateToLong() — see blueprint §5.
     */
    private fun parseDateToMidnightMillis(dateStr: String, legacyId: Int): Long {
        return try {
            val parts = dateStr.split("-")
            require(parts.size == 3) { "Unexpected date format: $dateStr" }
            val year  = parts[0].toInt()
            val month = parts[1].toInt() - 1  // Calendar months are 0-indexed
            val day   = parts[2].toInt()

            val cal = Calendar.getInstance()
            cal.set(year, month, day, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)

            cal.timeInMillis + legacyId  // +id to disambiguate same-date records
        } catch (e: Exception) {
            Log.e(TAG, "Date parse failed for '$dateStr' (legacyId=$legacyId): ${e.message}")
            throw e  // Fail-fast: abort import on malformed date
        }
    }

    // ─── UUID Generation ─────────────────────────────────────────────────────

    /**
     * Generates a deterministic UUID from the legacy record id.
     *
     * Uses UUID.nameUUIDFromBytes() (UUID v3 — MD5-based name UUID).
     * The same legacyId always produces the same UUID across all import attempts,
     * enabling idempotent re-import detection.
     *
     * The output is a v3 UUID, which is distinct from the v4 UUIDs generated by
     * UUID.randomUUID() in AndroidBridge.addTransaction(). No collision is possible.
     */
    private fun generateDeterministicUuid(legacyId: Int): String {
        return UUID.nameUUIDFromBytes("$UUID_NAMESPACE:$legacyId".toByteArray())
            .toString()
    }

    // ─── Note JSON Encoding ──────────────────────────────────────────────────

    /**
     * Builds the Room `note` JSON string in the exact format expected by AndroidBridge.getTransactions().
     *
     * AndroidBridge reads:
     *   noteObj.optString("expenseType", "")
     *   noteObj.optString("paidTo", "")
     *   noteObj.optString("notes", "")
     *
     * JSONObject.put() handles escaping of special characters automatically.
     */
    private fun buildNoteJson(notes: String, expenseType: String, paidTo: String): String {
        return JSONObject().apply {
            put("notes", notes)
            put("expenseType", expenseType)
            put("paidTo", paidTo)
        }.toString()
    }

    // ─── SharedPreferences Helpers ───────────────────────────────────────────

    /**
     * Returns true if the import was already completed in a previous run.
     * Used by AndroidBridge to check import status without running the full engine.
     */
    fun isAlreadyImported(): Boolean = prefs.getBoolean(PREF_DONE, false)

    /**
     * Returns the timestamp of the completed import, or null if never run.
     */
    fun getImportTimestamp(): String? = prefs.getString(PREF_TIME, null)
}

// ─── Internal Data Structures ────────────────────────────────────────────────

/** Raw data from one legacy DB row. */
private data class LegacyRow(
    val id:           Int,
    val date:         String,
    val entryType:    String,
    val amount:       Double,
    val category:     String,
    val expenseType:  String,
    val paidTo:       String,
    val notes:        String,
    val balanceAfter: Double  // read for per-record logging; not written to Room
)

/** Thrown inside runInTransaction to trigger automatic SQLite rollback. */
private class ImportValidationException(message: String) : Exception(message)
