package com.example.expensetracker.migration

import android.util.Log
import com.example.expensetracker.local.TransactionDao
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Executes the three-gate validation pipeline after all records are inserted into Room.
 *
 * ALL methods are called inside database.runInTransaction{} — they are synchronous and
 * call DAO methods directly (non-suspending). Do NOT route through LocalRepository.
 *
 * Gate 1 — Record count parity:      Room count == expected source count
 * Gate 2 — Final balance parity:     Room-computed balance == legacy canonical balance (40642.25)
 * Gate 3 — Ordering integrity:       sequenceIds ordered by createdAt ASC == [1..N] contiguous
 */
internal object ImportValidator {

    private const val TAG = "ImportValidator"

    // Canonical final balance from legacy production database.
    // Hard-coded as ground truth — import is considered corrupt if this does not match.
    private val CANONICAL_BALANCE = BigDecimal("40642.25")

    // Tolerance for floating-point comparison (2 decimal places)
    private val COMPARISON_SCALE = 2

    /**
     * Runs all three gates sequentially.
     *
     * @param dao               The DAO — called synchronously inside runInTransaction.
     * @param insertedEntities  The list of entities that were inserted, in legacy ORDER BY date ASC, id ASC order.
     * @param expectedCount     The number of records read from the legacy DB.
     * @return                  null if all gates pass, or an [ImportResult.ValidationFailed] describing the first failure.
     */
    fun runAll(
        dao: TransactionDao,
        insertedEntities: List<InsertedEntitySummary>,
        expectedCount: Int
    ): ImportResult.ValidationFailed? {

        return validateGate1(dao, expectedCount)
            ?: validateGate2(insertedEntities)
            ?: validateGate3(dao, expectedCount)
    }

    // ─── Gate 1: Record Count ────────────────────────────────────────────────

    private fun validateGate1(dao: TransactionDao, expectedCount: Int): ImportResult.ValidationFailed? {
        val roomCount = dao.getActiveTransactionCountSync()
        return if (roomCount != expectedCount) {
            val detail = "Expected $expectedCount, found $roomCount in Room"
            Log.e(TAG, "Gate 1 FAILED — $detail")
            ImportResult.ValidationFailed(gate = "Gate 1 (Record Count)", detail = detail)
        } else {
            Log.i(TAG, "Gate 1 PASSED — $roomCount records confirmed in Room")
            null
        }
    }

    // ─── Gate 2: Final Balance Parity ────────────────────────────────────────

    /**
     * Replicates the exact balance computation used by AndroidBridge.getTransactions():
     *   sorted entities (createdAt DESC) → reversed → oldest-first → running sum
     *
     * @param insertedEntities  Summaries of inserted entities in legacy ASC order (oldest first).
     *                          This is the same order the bridge uses after .reversed().
     */
    private fun validateGate2(insertedEntities: List<InsertedEntitySummary>): ImportResult.ValidationFailed? {
        var balance = BigDecimal.ZERO
        for (entity in insertedEntities) {
            val amount = BigDecimal(entity.amount.toString())
            balance = if (entity.type == "Credit") {
                balance.add(amount)
            } else {
                balance.subtract(amount)
            }
        }

        val rounded = balance.setScale(COMPARISON_SCALE, RoundingMode.HALF_UP)
        val canonical = CANONICAL_BALANCE.setScale(COMPARISON_SCALE, RoundingMode.HALF_UP)

        return if (rounded.compareTo(canonical) != 0) {
            val detail = "Computed ₹$rounded, expected ₹$canonical"
            Log.e(TAG, "Gate 2 FAILED — $detail")
            ImportResult.ValidationFailed(gate = "Gate 2 (Final Balance)", detail = detail)
        } else {
            Log.i(TAG, "Gate 2 PASSED — Final balance ₹$rounded matches canonical ₹$canonical")
            null
        }
    }

    // ─── Gate 3: Ordering Integrity ──────────────────────────────────────────

    /**
     * Queries Room for sequenceIds ordered by createdAt ASC and asserts the result
     * is the contiguous sequence [1, 2, ..., expectedCount].
     *
     * This proves that the createdAt encoding correctly encodes legacy date ASC, id ASC order.
     */
    private fun validateGate3(dao: TransactionDao, expectedCount: Int): ImportResult.ValidationFailed? {
        val sequenceIds = dao.getSequenceIdsByCreatedAtAsc()

        if (sequenceIds.size != expectedCount) {
            val detail = "Expected $expectedCount sequenceIds, got ${sequenceIds.size}"
            Log.e(TAG, "Gate 3 FAILED — $detail")
            return ImportResult.ValidationFailed(gate = "Gate 3 (Ordering Integrity)", detail = detail)
        }

        for (i in sequenceIds.indices) {
            val expected = (i + 1).toLong()
            if (sequenceIds[i] != expected) {
                val detail = "Position $i: expected sequenceId=$expected, got ${sequenceIds[i]}"
                Log.e(TAG, "Gate 3 FAILED — $detail")
                return ImportResult.ValidationFailed(gate = "Gate 3 (Ordering Integrity)", detail = detail)
            }
        }

        Log.i(TAG, "Gate 3 PASSED — sequenceIds [1..$expectedCount] are contiguous in createdAt ASC order")
        return null
    }

    // ─── Logging Helper ──────────────────────────────────────────────────────

    fun logReport(result: ImportResult, expectedCount: Int) {
        Log.i(TAG, "════════════════════════════════════════")
        Log.i(TAG, "Import Validation Report")
        Log.i(TAG, "════════════════════════════════════════")
        when (result) {
            is ImportResult.Success -> {
                Log.i(TAG, "Records imported:    ${result.importedCount} / $expectedCount  ✓")
                Log.i(TAG, "Final balance:       ₹${result.finalBalance}  ✓")
                Log.i(TAG, "Ordering integrity:  [1..$expectedCount] contiguous  ✓")
                Log.i(TAG, "Status:              SUCCESS")
            }
            is ImportResult.ValidationFailed -> {
                Log.e(TAG, "FAILED gate:         ${result.gate}")
                Log.e(TAG, "Detail:              ${result.detail}")
                Log.e(TAG, "Status:              ROLLED BACK — Room DB unchanged")
            }
            else -> Log.i(TAG, "Status: ${result::class.simpleName}")
        }
        Log.i(TAG, "════════════════════════════════════════")
    }
}

/**
 * Lightweight summary of an inserted entity used during in-memory balance validation.
 * Avoids re-querying Room for Gate 2.
 */
data class InsertedEntitySummary(
    val id: String,
    val type: String,   // "Credit" or "Debit"
    val amount: Double  // positive value
)
