package com.example.expensetracker.bridge

import android.webkit.JavascriptInterface
import android.util.Log
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.local.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

class AndroidBridge(private val repository: LocalRepository) {

    private fun parseDateToLong(dateStr: String): Long {
        try {
            val parts = dateStr.split("/")
            if (parts.size == 3) {
                val cal = Calendar.getInstance()
                cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt(), 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        } catch (e: Exception) {
            Log.e("AndroidBridge", "Error parsing date: $dateStr", e)
        }
        return System.currentTimeMillis()
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(timestamp)
    }

    @JavascriptInterface
    fun getTransactions(page: Int, limit: Int): String {
        Log.d("AndroidBridge", "getTransactions called page=$page limit=$limit")
        return runBlocking {
            val entities = repository.getAllTransactions().first().filter { !it.deleted }
            val sortedEntities = entities.sortedByDescending { it.createdAt }

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

                val entity = TransactionEntity(
                    id = id,
                    amount = obj.optDouble("amount", 0.0),
                    type = obj.optString("entryType", "Debit"),
                    category = obj.optString("category", "General"),
                    note = noteData.toString(),
                    createdAt = parseDateToLong(obj.optString("date", "")),
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                    syncPending = true
                )

                repository.updateTransaction(entity)

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
                response.put("status", "ok")
                response.toString()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "deleteTransaction error", e)
                val response = JSONObject()
                response.put("status", "error")
                response.put("error", e.message)
                response.toString()
            }
        }
    }
}
