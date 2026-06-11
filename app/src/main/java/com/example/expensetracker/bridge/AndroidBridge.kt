package com.example.expensetracker.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import android.util.Log
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.local.TransactionEntity
import com.example.expensetracker.migration.ImportResult
import com.example.expensetracker.migration.LegacyImportEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

class AndroidBridge(private val repository: LocalRepository, private val context: Context) {


    private fun parseDateToLong(dateStr: String, preserveTimeFrom: Long? = null): Long {
        try {
            val now = Calendar.getInstance()
            if (preserveTimeFrom != null) {
                now.timeInMillis = preserveTimeFrom
            }

            if (dateStr.contains("-")) {
                val parts = dateStr.split("-")
                if (parts.size == 3) {
                    val cal = Calendar.getInstance()
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
                    cal.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))
                    // Ensure sequential ordering by adding a slight offset for identical timestamps
                    if (preserveTimeFrom == null) {
                        Thread.sleep(1) // Ensure distinct ms
                        val msPassed = System.currentTimeMillis() - now.timeInMillis
                        return cal.timeInMillis + msPassed
                    }
                    return cal.timeInMillis
                }
            } else {
                val parts = dateStr.split("/")
                if (parts.size == 3) {
                    val cal = Calendar.getInstance()
                    cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt(), now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
                    cal.set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))
                    if (preserveTimeFrom == null) {
                        Thread.sleep(1) // Ensure distinct ms
                        val msPassed = System.currentTimeMillis() - now.timeInMillis
                        return cal.timeInMillis + msPassed
                    }
                    return cal.timeInMillis
                }
            }
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error parsing date: $dateStr", e)
        }
        return System.currentTimeMillis()
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(timestamp)
    }

    @JavascriptInterface
    fun getTransactions(page: Int, limit: Int): String {
        Log.d("AndroidBridge", "getTransactions called page=$page limit=$limit")
        return runBlocking {
            val entities = repository.getAllTransactions().first().filter { !it.deleted }
            val sortedEntities = entities

            val startIndex = (page - 1) * limit
            val endIndex = minOf(startIndex + limit, sortedEntities.size)

            val pagedEntities = if (startIndex < sortedEntities.size) {
                sortedEntities.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            var globalBalance = 0.0
            var totalCredit = 0.0
            var totalDebit = 0.0

            val reversedEntities = sortedEntities.reversed()
            val balances = mutableMapOf<String, Double>()
            for (entity in reversedEntities) {
                if (entity.type == "Credit") {
                    globalBalance += entity.amount
                    totalCredit += entity.amount
                } else {
                    globalBalance -= entity.amount
                    totalDebit += entity.amount
                }
                balances[entity.id] = globalBalance
            }

            val jsonArray = JSONArray()
            for (entity in pagedEntities) {
                val obj = JSONObject()
                obj.put("id", entity.id)
                obj.put("amount", entity.amount)
                obj.put("entryType", entity.type)
                obj.put("category", entity.category)
                obj.put("date", formatDate(entity.createdAt))

                // Parse note for extra fields
                var expenseType = ""
                var paidTo = ""
                var notes = ""
                try {
                    val noteObj = JSONObject(entity.note)
                    expenseType = noteObj.optString("expenseType", "")
                    paidTo = noteObj.optString("paidTo", "")
                    notes = noteObj.optString("notes", "")
                } catch (e: Exception) {
                    // Fallback to raw note
                    notes = entity.note
                }

                obj.put("expenseType", expenseType)
                obj.put("paidTo", paidTo)
                obj.put("notes", notes)

                obj.put("balanceAfter", balances[entity.id] ?: 0.0)

                jsonArray.put(obj)
            }

            val result = JSONObject()
            result.put("transactions", jsonArray)
            result.put("hasMore", endIndex < sortedEntities.size)
            result.put("globalBalance", globalBalance)
            result.put("totalCredit", totalCredit)
            result.put("totalDebit", totalDebit)

            result.toString()
        }
    }

    @JavascriptInterface
    fun addTransaction(transactionJson: String): String {
        Log.d("AndroidBridge", "addTransaction called: $transactionJson")
        return runBlocking {
            try {
                val obj = JSONObject(transactionJson)

                val noteData = JSONObject()
                noteData.put("notes", obj.optString("notes", ""))
                noteData.put("expenseType", obj.optString("expenseType", ""))
                noteData.put("paidTo", obj.optString("paidTo", ""))

                val entity = TransactionEntity(
                    id = UUID.randomUUID().toString(),
                    amount = obj.optDouble("amount", 0.0),
                    type = obj.optString("entryType", "Debit"),
                    category = obj.optString("category", "General"),
                    note = noteData.toString(),
                    createdAt = parseDateToLong(obj.optString("date", "")),
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                    syncPending = true
                )

                repository.insertTransaction(entity)

                val response = JSONObject()
                response.put("status", "ok")
                response.toString()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "addTransaction error", e)
                val response = JSONObject()
                response.put("status", "error")
                response.put("error", e.message)
                response.toString()
            }
        }
    }

    @JavascriptInterface
    fun updateTransaction(id: String, transactionJson: String): String {
        Log.d("AndroidBridge", "updateTransaction called: $id $transactionJson")
        return runBlocking {
            try {
                val obj = JSONObject(transactionJson)

                val noteData = JSONObject()
                noteData.put("notes", obj.optString("notes", ""))
                noteData.put("expenseType", obj.optString("expenseType", ""))
                noteData.put("paidTo", obj.optString("paidTo", ""))

                val existingEntity = repository.getAllTransactions().first().find { it.id == id }

                if (existingEntity != null) {
                    val entity = TransactionEntity(
                        id = id,
                        amount = obj.optDouble("amount", 0.0),
                        type = obj.optString("entryType", "Debit"),
                        category = obj.optString("category", "General"),
                        note = noteData.toString(),
                        createdAt = parseDateToLong(obj.optString("date", ""), existingEntity.createdAt),
                        updatedAt = System.currentTimeMillis(),
                        deleted = existingEntity.deleted,
                        syncPending = true,
                        sequenceId = existingEntity.sequenceId
                    )
                    repository.updateTransaction(entity)
                } else {
                    throw Exception("Transaction not found")
                }

                val response = JSONObject()
                response.put("status", "ok")
                response.toString()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "updateTransaction error", e)
                val response = JSONObject()
                response.put("status", "error")
                response.put("error", e.message)
                response.toString()
            }
        }
    }

    @JavascriptInterface
    fun deleteTransaction(id: String): String {
        Log.d("AndroidBridge", "deleteTransaction called: $id")
        return runBlocking {
            try {
                repository.softDeleteTransaction(id)
                val response = JSONObject()
                response.put(\"status\", \"ok\")
                response.toString()
            } catch (e: Exception) {
                Log.e(\"AndroidBridge\", \"deleteTransaction error\", e)
                val response = JSONObject()
                response.put(\"status\", \"error\")
                response.put(\"error\", e.message)
                response.toString()
            }
        }
    }

    /**
     * Triggers the full legacy database import pipeline.
     *
     * Called by JS: window.AndroidBridge.importLegacyDatabase()
     * This method blocks on the JavaBridge thread (runBlocking is intentional here —
     * consistent with the pattern used by getTransactions/addTransaction/etc.).
     *
     * Returns a JSON string:
     *   { "status": "success", "importedCount": 64, "finalBalance": 40642.25 }
     *   { "status": "alreadyImported" }
     *   { "status": "roomNotEmpty", "existingCount": N }
     *   { "status": "legacyDbNotFound", "reason": "..." }
     *   { "status": "unexpectedCount", "actual": N, "expected": 64 }
     *   { "status": "validationFailed", "gate": "...", "detail": "..." }
     *   { "status": "error", "error": "..." }
     */
    @JavascriptInterface
    fun importLegacyDatabase(): String {
        Log.d("AndroidBridge", "importLegacyDatabase() called from JS")
        val engine = LegacyImportEngine(context)
        val result = engine.execute()

        val response = JSONObject()
        when (result) {
            is ImportResult.Success -> {
                response.put("status", "success")
                response.put("importedCount", result.importedCount)
                response.put("finalBalance", result.finalBalance)
            }
            is ImportResult.AlreadyImported -> {
                response.put("status", "alreadyImported")
            }
            is ImportResult.RoomNotEmpty -> {
                response.put("status", "roomNotEmpty")
                response.put("existingCount", result.existingCount)
            }
            is ImportResult.LegacyDbNotFound -> {
                response.put("status", "legacyDbNotFound")
                response.put("reason", result.reason)
            }
            is ImportResult.UnexpectedRecordCount -> {
                response.put("status", "unexpectedCount")
                response.put("actual", result.actual)
                response.put("expected", result.expected)
            }
            is ImportResult.ValidationFailed -> {
                response.put("status", "validationFailed")
                response.put("gate", result.gate)
                response.put("detail", result.detail)
            }
            is ImportResult.UnexpectedException -> {
                response.put("status", "error")
                response.put("error", result.message)
            }
        }
        return response.toString()
    }

    /**
     * Returns the current import status without triggering an import.
     *
     * Called by JS on page load to decide whether to show/hide the import button.
     *
     * Returns JSON:
     *   { "imported": true,  "timestamp": "1718000000000" }
     *   { "imported": false }
     */
    @JavascriptInterface
    fun checkImportStatus(): String {
        val engine = LegacyImportEngine(context)
        val response = JSONObject()
        response.put("imported", engine.isAlreadyImported())
        val ts = engine.getImportTimestamp()
        if (ts != null) response.put("timestamp", ts)
        return response.toString()
    }
}
