package com.example.expensetracker.bridge

import android.webkit.JavascriptInterface
import android.util.Log
import com.household.ledger.database.TransactionService
import com.household.ledger.models.Transaction
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal

class AndroidBridge(private val transactionService: TransactionService) {

    @JavascriptInterface
    fun getTransactions(page: Int, limit: Int): String {
        Log.d("AndroidBridge", "getTransactions called page=$page limit=$limit")
        return runBlocking {
            try {
                Log.d("AndroidBridge", "DB Source path: com.household.ledger.database.DatabaseFactory")
                val transactions = transactionService.getPagedTransactions(page, limit)
                val totalCount = transactionService.getTotalCount()

                val jsonArray = JSONArray()
                for (t in transactions) {
                    val obj = JSONObject()
                    obj.put("id", t.id)
                    obj.put("amount", t.amount.toDouble())
                    obj.put("entryType", t.entryType)
                    obj.put("category", t.category)
                    obj.put("date", t.date)
                    obj.put("expenseType", t.expenseType)
                    obj.put("paidTo", t.paidTo)
                    obj.put("notes", t.notes)
                    obj.put("balanceAfter", t.balanceAfter?.toDouble() ?: 0.0)
                    jsonArray.put(obj)
                }

                val result = JSONObject()
                result.put("transactions", jsonArray)
                result.put("hasMore", (page * limit) < totalCount)

                Log.d("AndroidBridge", "getTransactions success: returned ${transactions.size} records")
                result.toString()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "getTransactions error", e)
                val result = JSONObject()
                result.put("transactions", JSONArray())
                result.put("hasMore", false)
                result.toString()
            }
        }
    }

    @JavascriptInterface
    fun addTransaction(transactionJson: String): String {
        Log.d("AndroidBridge", "addTransaction called: $transactionJson")
        return runBlocking {
            try {
                val obj = JSONObject(transactionJson)
                val newTransaction = Transaction(
                    id = null,
                    date = obj.optString("date", ""),
                    entryType = obj.optString("entryType", "Debit"),
                    amount = BigDecimal(obj.optDouble("amount", 0.0).toString()),
                    category = obj.optString("category", ""),
                    expenseType = obj.optString("expenseType", ""),
                    paidTo = obj.optString("paidTo", ""),
                    notes = obj.optString("notes", "")
                )

                val added = transactionService.addTransaction(newTransaction)
                Log.d("AndroidBridge", "addTransaction status: success, new id: ${added?.id}")

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
                val updatedTransaction = Transaction(
                    id = id.toInt(),
                    date = obj.optString("date", ""),
                    entryType = obj.optString("entryType", "Debit"),
                    amount = BigDecimal(obj.optDouble("amount", 0.0).toString()),
                    category = obj.optString("category", ""),
                    expenseType = obj.optString("expenseType", ""),
                    paidTo = obj.optString("paidTo", ""),
                    notes = obj.optString("notes", "")
                )

                val success = transactionService.updateTransaction(id.toInt(), updatedTransaction)
                Log.d("AndroidBridge", "updateTransaction status: $success")

                val response = JSONObject()
                response.put("status", if (success) "ok" else "error")
                if (!success) response.put("error", "Failed to update record")
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
                val success = transactionService.deleteTransaction(id.toInt())
                Log.d("AndroidBridge", "deleteTransaction status: $success")

                val response = JSONObject()
                response.put("status", if (success) "ok" else "error")
                if (!success) response.put("error", "Failed to delete record")
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
