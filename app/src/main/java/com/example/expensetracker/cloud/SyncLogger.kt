package com.example.expensetracker.cloud

import android.util.Log

object SyncLogger {
    private const val TAG = "SyncEngine"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warning(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
    }
}
