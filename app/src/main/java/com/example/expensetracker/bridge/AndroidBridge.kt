package com.example.expensetracker.bridge

import android.webkit.JavascriptInterface
import com.example.expensetracker.ui.theme.ThemeManager
import com.example.expensetracker.ui.theme.ThemeMode
import com.example.expensetracker.backup.BackupManager
import android.util.Log
import com.example.expensetracker.repository.LocalRepository
import com.example.expensetracker.local.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.expensetracker.backup.BackupType
import com.example.expensetracker.backup.BackupResult
import com.example.expensetracker.backup.RestoreManager
import com.example.expensetracker.backup.BackupLifecycleManager
import java.io.File
import android.content.Context
import java.util.Date

class AndroidBridge(
    private val repository: LocalRepository,
    private val backupManager: BackupManager,
    private val restoreManager: RestoreManager,
    private val lifecycleManager: BackupLifecycleManager,
    private val context: Context,
    private val themeManager: ThemeManager,
    private val syncManager: com.example.expensetracker.cloud.SyncManager? = null,
    private val onOpenSettings: (() -> Unit)? = null
) {

    // ═══ SYNC BRIDGE ═══




    // ═══ SYNC BRIDGE ═══

    @JavascriptInterface
    fun getSyncState(): String {
        Log.d("AndroidBridge", "getSyncState called")
        val response = JSONObject()
        val sm = syncManager
        if (sm == null) {
            response.put("status", "Error")
            response.put("connection", "UNKNOWN")
            response.put("pendingQueue", 0)
            response.put("isSyncing", false)
            response.put("lastSync", 0)
            response.put("localRecords", 0)
            response.put("cloudRecords", 0)
            response.put("hasError", true)
            response.put("errorMessage", "SyncManager is not initialized")
            return response.toString()
        }

        val syncState = sm.syncState.value
        val connectivity = sm.connectivityState.value
        val pendingCount = sm.pendingQueueCount.value
        val lastSync = sm.lastSyncTime.value
        val localRecords = sm.localRecordsCount.value
        val cloudRecords = sm.cloudRecordsCount.value

        var status = "Unknown"
        var isSyncing = false
        var hasError = false
        var errorMessage: String? = null

        when (syncState) {
            is com.example.expensetracker.cloud.SyncState.Idle -> {
                status = "Synced"
            }
            is com.example.expensetracker.cloud.SyncState.Syncing -> {
                status = "Syncing..."
                isSyncing = true
            }
            is com.example.expensetracker.cloud.SyncState.Error -> {
                status = "Error"
                hasError = true
                errorMessage = syncState.message
            }
            is com.example.expensetracker.cloud.SyncState.Disabled -> {
                status = "Sign In Required"
            }
        }

        val connectionStatus = when (connectivity) {
            is com.example.expensetracker.cloud.ConnectivityState.Online -> "Online"
            is com.example.expensetracker.cloud.ConnectivityState.Offline -> "Offline"
            else -> "Unknown"
        }

        if (connectivity is com.example.expensetracker.cloud.ConnectivityState.Offline) {
            status = "Offline"
        } else if (pendingCount > 0 && !isSyncing) {
            status = "Pending Sync"
        }

        response.put("status", status)
        response.put("connection", connectionStatus)
        response.put("pendingQueue", pendingCount)
        response.put("isSyncing", isSyncing)
        response.put("lastSync", lastSync)
        response.put("localRecords", localRecords)
        response.put("cloudRecords", cloudRecords)
        response.put("hasError", hasError)
        response.put("errorMessage", errorMessage ?: JSONObject.NULL)

        return response.toString()
    }

    @JavascriptInterface
    fun triggerSync() {
        Log.d("AndroidBridge", "triggerSync called")
        syncManager?.let { sm ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                sm.performBackgroundSync()
            }
        }
    }


    /**
     * Called from WebView JavaScript when the user taps the
     * Settings / Account item in the navigation drawer.
     */
    @JavascriptInterface
    fun openSettings() {
        Log.d("AndroidBridge", "[SETTINGS] openSettings called from WebView")
        onOpenSettings?.invoke()
    }

    // ═══ THEME BRIDGE ═══

    /**
     * Called from WebView JS to toggle the theme (LIGHT↔DARK).
     */
    @JavascriptInterface
    fun toggleTheme() {
        Log.d("AndroidBridge", "[THEME] toggleTheme called from WebView")
        val isSystemDark = (context.resources.configuration.uiMode
                and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        themeManager.toggleTheme(isSystemDark)
    }

    /**
     * Returns the currently resolved theme as "dark" or "light".
     * Called from WebView JS on page load to sync initial state.
     */
    @JavascriptInterface
    fun getTheme(): String {
        val isSystemDark = (context.resources.configuration.uiMode
                and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val resolved = if (themeManager.isDark(isSystemDark)) "dark" else "light"
        Log.d("AndroidBridge", "[THEME] getTheme → $resolved (mode=${themeManager.themeMode.value})")
        return resolved
    }

    /**
     * Sets the theme mode from WebView JS.
     * Accepts "light", "dark", or "system".
     */
    @JavascriptInterface
    fun setTheme(mode: String) {
        Log.d("AndroidBridge", "[THEME] setTheme called with: $mode")
        val themeMode = when (mode.lowercase()) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            "system" -> ThemeMode.SYSTEM
            else -> {
                Log.w("AndroidBridge", "[THEME] Unknown theme mode: $mode, ignoring")
                return
            }
        }
        themeManager.setTheme(themeMode)
    }

    @JavascriptInterface
    fun backupDatabase(): String {
        Log.d("AndroidBridge", "[BACKUP] backupDatabase called")
        Log.d("AndroidBridge", "[BACKUP] backup started, type=MANUAL")
        val result = backupManager.createBackup(BackupType.MANUAL)
        val response = JSONObject()
        if (result is BackupResult.Success) {
            Log.d("AndroidBridge", "[BACKUP] backup completed successfully")
            Log.d("AndroidBridge", "[BACKUP] backupPath=${result.backupPath}")
            Log.d("AndroidBridge", "[BACKUP] backupFile=${result.backupFileName}")
            lifecycleManager.onBackupCreated(result, BackupType.MANUAL)
            response.put("status", "success")
            response.put("message", "Restore point created")
            response.put("backupPath", result.backupPath)
            response.put("backupFile", result.backupFileName)
            response.put("timestamp", result.timestamp)
        } else if (result is BackupResult.Failure) {
            Log.e("AndroidBridge", "[BACKUP] backup failed: ${result.errorMessage}")
            response.put("status", "error")
            response.put("message", result.errorMessage)
        }
        Log.d("AndroidBridge", "[BACKUP] returning JSON: ${response.toString()}")
        return response.toString()
    }

    @JavascriptInterface
    fun getDbStats(): String {
        Log.d("AndroidBridge", "getDbStats called")
        return runBlocking {
            val response = JSONObject()
            try {
                val allTxns = repository.getAllTransactions().first()
                val totalTxns = allTxns.size
                val dbFile = context.getDatabasePath("expense_database")
                val dbSize = if (dbFile.exists()) dbFile.length() else 0L

                val backupsDir = backupManager.getBackupDirectory()
                var lastBackupTime = "Never"
                var latestTime = 0L
                for (type in listOf("auto", "manual", "emergency")) {
                    val typeDir = File(backupsDir, type)
                    if (typeDir.exists()) {
                        val files = typeDir.listFiles()?.filter { it.name.endsWith(".db") }
                        val maxFile = files?.maxByOrNull { it.lastModified() }
                        if (maxFile != null && maxFile.lastModified() > latestTime) {
                            latestTime = maxFile.lastModified()
                            lastBackupTime = formatDate(latestTime)
                        }
                    }
                }

                val pendingSync = allTxns.count { it.syncPending }

                response.put("totalTransactions", totalTxns)
                response.put("databaseSizeBytes", dbSize)
                response.put("lastBackupTime", lastBackupTime)

                if (pendingSync == 0) {
                    response.put("syncStatus", "Synced")
                } else {
                    response.put("syncStatus", "Pending Sync")
                    response.put("pendingMutations", pendingSync)
                }

            } catch (e: Exception) {
                Log.e("AndroidBridge", "getDbStats error", e)
                response.put("totalTransactions", 0)
                response.put("databaseSizeBytes", 0)
                response.put("lastBackupTime", "Unavailable")
                response.put("syncStatus", "Error")
            }
            response.toString()
        }
    }

    @JavascriptInterface
    fun getBackupStatus(): String {
        val response = JSONObject()
        try {
            val backupsDir = backupManager.getBackupDirectory()
            var totalBackups = 0
            var autoCount = 0
            var manualCount = 0
            var latestTime = 0L
            var latestSize = 0L

            if (backupsDir.exists() && backupsDir.isDirectory) {
                val autoDir = File(backupsDir, "auto")
                val manualDir = File(backupsDir, "manual")
                val emergencyDir = File(backupsDir, "emergency")

                val allFiles = mutableListOf<File>()

                if (autoDir.exists()) {
                    val files = autoDir.listFiles()?.filter { it.name.endsWith(".db") } ?: emptyList()
                    autoCount = files.size
                    allFiles.addAll(files)
                }
                if (manualDir.exists()) {
                    val files = manualDir.listFiles()?.filter { it.name.endsWith(".db") } ?: emptyList()
                    manualCount = files.size
                    allFiles.addAll(files)
                }
                if (emergencyDir.exists()) {
                    val files = emergencyDir.listFiles()?.filter { it.name.endsWith(".db") } ?: emptyList()
                    allFiles.addAll(files)
                }

                totalBackups = allFiles.size

                val latestFile = allFiles.maxByOrNull { it.lastModified() }
                if (latestFile != null) {
                    latestTime = latestFile.lastModified()
                    latestSize = latestFile.length()
                }
            }

            response.put("backupCount", totalBackups)
            response.put("latestBackupTime", if (latestTime > 0) formatDate(latestTime) else "Never")

            val sizeMb = if (latestSize > 0) String.format(Locale.US, "%.2f MB", latestSize / (1024.0 * 1024.0)) else "0 MB"
            response.put("latestBackupSize", sizeMb)
            response.put("autoBackupCount", autoCount)
            response.put("manualBackupCount", manualCount)

        } catch (e: Exception) {
            Log.e("AndroidBridge", "getBackupStatus error", e)
            response.put("backupCount", 0)
            response.put("latestBackupTime", "Error")
            response.put("latestBackupSize", "0 MB")
            response.put("autoBackupCount", 0)
            response.put("manualBackupCount", 0)
        }
        return response.toString()
    }

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

    /**
     * Shared helper: converts a TransactionEntity into the JSON shape
     * that the WebView JS expects (entryType, date, parsed note fields, etc.).
     * Callers can add extra fields (e.g. balanceAfter) on the returned object.
     */
    private fun serializeTransaction(entity: TransactionEntity): JSONObject {
        val obj = JSONObject()
        obj.put("id", entity.id)
        obj.put("amount", entity.amount)
        obj.put("entryType", entity.type)
        obj.put("category", entity.category)
        obj.put("date", formatDate(entity.createdAt))

        var expenseType = ""
        var paidTo = ""
        var notes = ""
        try {
            val noteObj = JSONObject(entity.note)
            expenseType = noteObj.optString("expenseType", "")
            paidTo = noteObj.optString("paidTo", "")
            notes = noteObj.optString("notes", "")
        } catch (e: Exception) {
            notes = entity.note
        }

        obj.put("expenseType", expenseType)
        obj.put("paidTo", paidTo)
        obj.put("notes", notes)
        return obj
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

            val balances = mutableMapOf<String, Double>()
            // The DAO returns newest first. We need to iterate from oldest to newest to compute running balance.
            val oldestFirstEntities = sortedEntities.reversed()
            for (entity in oldestFirstEntities) {
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
                val obj = serializeTransaction(entity)
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

    /**
     * Returns ALL non-deleted transactions as a flat JSON array for analytics.
     * No pagination, no running balance — analytics processes everything client-side.
     */
    @JavascriptInterface
    fun getAllTransactionsForAnalytics(): String {
        Log.d("AndroidBridge", "getAllTransactionsForAnalytics called")
        return runBlocking {
            try {
                val entities = repository.getAllTransactions().first().filter { !it.deleted }
                val jsonArray = JSONArray()
                for (entity in entities) {
                    jsonArray.put(serializeTransaction(entity))
                }
                jsonArray.toString()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Error fetching analytics data", e)
                "[]"
            }
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

                try {
                    if (lifecycleManager.shouldAllowBackup(BackupType.AUTO)) {
                        val backupResult = backupManager.createBackup(BackupType.AUTO)
                        if (backupResult is BackupResult.Success) {
                            lifecycleManager.onBackupCreated(backupResult, BackupType.AUTO)
                        }
                    }
                } catch (be: Exception) {
                    Log.e("AndroidBridge", "Auto backup failed during addTransaction", be)
                }

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

                    try {
                        if (lifecycleManager.shouldAllowBackup(BackupType.AUTO)) {
                            val backupResult = backupManager.createBackup(BackupType.AUTO)
                            if (backupResult is BackupResult.Success) {
                                lifecycleManager.onBackupCreated(backupResult, BackupType.AUTO)
                            }
                        }
                    } catch (be: Exception) {
                        Log.e("AndroidBridge", "Auto backup failed during updateTransaction", be)
                    }
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

                try {
                    if (lifecycleManager.shouldAllowBackup(BackupType.AUTO)) {
                        val backupResult = backupManager.createBackup(BackupType.AUTO)
                        if (backupResult is BackupResult.Success) {
                            lifecycleManager.onBackupCreated(backupResult, BackupType.AUTO)
                        }
                    }
                } catch (be: Exception) {
                    Log.e("AndroidBridge", "Auto backup failed during deleteTransaction", be)
                }

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

    @JavascriptInterface
    fun getAvailableBackups(): String {
        Log.d("AndroidBridge", "[RESTORE] getAvailableBackups called")
        return try {
            val backups = restoreManager.getAvailableBackups()
            Log.d("AndroidBridge", "[RESTORE] found ${backups.size} available backups")
            val jsonArray = JSONArray()
            for (backup in backups) {
                Log.d("AndroidBridge", "[RESTORE] backup: ${backup.fileName} type=${backup.backupType} size=${backup.sizeBytes}")
                val obj = JSONObject()
                obj.put("fileName", backup.fileName)
                obj.put("backupType", backup.backupType)
                obj.put("timestamp", backup.timestamp)
                obj.put("sizeBytes", backup.sizeBytes)
                jsonArray.put(obj)
            }
            val result = jsonArray.toString()
            Log.d("AndroidBridge", "[RESTORE] returning JSON array with ${backups.size} entries")
            result
        } catch (e: Exception) {
            Log.e("AndroidBridge", "[RESTORE] getAvailableBackups error", e)
            "[]"
        }
    }

    @JavascriptInterface
    fun restoreDatabase(fileName: String): String {
        Log.d("AndroidBridge", "[RESTORE] restoreDatabase called")
        Log.d("AndroidBridge", "[RESTORE] fileName=$fileName")
        val response = JSONObject()
        return try {
            Log.d("AndroidBridge", "[RESTORE] calling restoreManager.restoreDatabase()")
            val success = restoreManager.restoreDatabase(fileName)
            if (success) {
                Log.d("AndroidBridge", "[RESTORE] restore completed successfully for: $fileName")
                response.put("status", "success")
                response.put("message", "Database successfully restored")
            } else {
                Log.e("AndroidBridge", "[RESTORE] restore failed for: $fileName")
                response.put("status", "error")
                response.put("message", "Restore failed during validation or file replacement.")
            }
            Log.d("AndroidBridge", "[RESTORE] returning JSON: ${response.toString()}")
            response.toString()
        } catch (e: Exception) {
            Log.e("AndroidBridge", "[RESTORE] restoreDatabase exception", e)
            response.put("status", "error")
            response.put("message", e.message ?: "Unknown error")
            response.toString()
        }
    }

    // ═══ LIFECYCLE MANAGER BRIDGE ═══

    /**
     * Runs retention enforcement + cleanup.
     * Returns a structured CleanupReport as JSON.
     * Change 5: shows toast + refreshes metrics after cleanup.
     */
    @JavascriptInterface
    fun runCleanup(): String {
        Log.d("AndroidBridge", "[LIFECYCLE] runCleanup called")
        return try {
            val report = lifecycleManager.enforceRetentionPolicy()
            Log.d("AndroidBridge", "[LIFECYCLE] cleanup complete: ${report.filesRemoved} files removed")
            report.toJson().toString()
        } catch (e: Exception) {
            Log.e("AndroidBridge", "[LIFECYCLE] runCleanup error", e)
            val error = JSONObject()
            error.put("filesRemoved", 0)
            error.put("storageRecovered", "0 B")
            error.put("storageRecoveredBytes", 0)
            error.put("backupsRemaining", 0)
            error.put("errors", org.json.JSONArray().put(e.message ?: "Unknown error"))
            error.toString()
        }
    }

    /**
     * Returns comprehensive backup lifecycle statistics as JSON.
     */
    @JavascriptInterface
    fun getLifecycleStats(): String {
        Log.d("AndroidBridge", "[LIFECYCLE] getLifecycleStats called")
        return try {
            val stats = lifecycleManager.getLifecycleStats()
            stats.toJson().toString()
        } catch (e: Exception) {
            Log.e("AndroidBridge", "[LIFECYCLE] getLifecycleStats error", e)
            JSONObject().put("totalBackups", 0).put("healthLabel", "Error").put("healthStatus", "error").toString()
        }
    }

    /**
     * Runs integrity verification on all backups.
     * Per Change 4: corrupted backups are marked, not deleted.
     */
    @JavascriptInterface
    fun verifyBackupIntegrity(): String {
        Log.d("AndroidBridge", "[LIFECYCLE] verifyBackupIntegrity called")
        return try {
            val report = lifecycleManager.verifyAllBackups()
            Log.d("AndroidBridge", "[LIFECYCLE] integrity check: ${report.healthyCount} healthy, ${report.corruptedCount} corrupted")
            report.toJson().toString()
        } catch (e: Exception) {
            Log.e("AndroidBridge", "[LIFECYCLE] verifyBackupIntegrity error", e)
            JSONObject().put("totalChecked", 0).put("error", e.message).toString()
        }
    }
}
