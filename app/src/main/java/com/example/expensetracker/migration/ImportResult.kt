package com.example.expensetracker.migration

/**
 * Sealed class representing every possible outcome of the legacy import operation.
 *
 * Used by LegacyImportEngine to communicate results back to the caller (AndroidBridge).
 * Each subclass maps to a distinct JSON status string returned to the JS layer.
 */
sealed class ImportResult {

    /** Import completed successfully. All validation gates passed. */
    data class Success(
        val importedCount: Int,
        val finalBalance: Double
    ) : ImportResult()

    /**
     * Import was skipped — SharedPreferences flag indicates it already ran.
     * No DB write was attempted.
     */
    object AlreadyImported : ImportResult()

    /**
     * Import was aborted — Room DB already contains active transactions.
     * Import is only valid on an empty database to prevent data corruption.
     */
    data class RoomNotEmpty(val existingCount: Int) : ImportResult()

    /**
     * The legacy asset file could not be found or opened.
     * The .db file may not have been bundled in assets/.
     */
    data class LegacyDbNotFound(val reason: String) : ImportResult()

    /**
     * The legacy database was opened but contained an unexpected number of records.
     * Import is aborted to prevent partial or corrupt imports.
     */
    data class UnexpectedRecordCount(val actual: Int, val expected: Int) : ImportResult()

    /**
     * A validation gate failed after all records were inserted.
     * The Room transaction was rolled back — DB is left untouched.
     */
    data class ValidationFailed(val gate: String, val detail: String) : ImportResult()

    /**
     * An unexpected exception occurred during the import pipeline.
     * The Room transaction was rolled back automatically.
     */
    data class UnexpectedException(val message: String) : ImportResult()
}
