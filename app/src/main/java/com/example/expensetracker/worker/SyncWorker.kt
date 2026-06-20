package com.example.expensetracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.expensetracker.cloud.SyncManager
import com.example.expensetracker.cloud.SyncLogger
import com.google.firebase.auth.FirebaseAuth

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            SyncLogger.warning("Background sync skipped: no authenticated user found")
            return Result.success() // Do not retry if the user is simply logged out
        }

        val syncManager = SyncManager.getInstance(applicationContext)
        syncManager.initialize(user)

        val success = syncManager.performBackgroundSync()

        return if (success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
