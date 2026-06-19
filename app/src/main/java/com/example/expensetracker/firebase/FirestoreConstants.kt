package com.example.expensetracker.firebase

object FirestoreConstants {
    const val COLLECTION_USERS = "users"
    const val COLLECTION_TRANSACTIONS = "transactions"
    const val COLLECTION_METADATA = "metadata"

    const val DOC_PROFILE = "profile"
    const val DOC_SETTINGS = "settings"
    const val DOC_SYNC = "sync"

    const val FIELD_ID = "id"
    const val FIELD_AMOUNT = "amount"
    const val FIELD_TYPE = "type"
    const val FIELD_CATEGORY = "category"
    const val FIELD_NOTE = "note"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_UPDATED_AT = "updatedAt"
    const val FIELD_DELETED = "deleted"
    const val FIELD_DEVICE_ID = "deviceId"
    const val FIELD_SYNC_STATUS = "syncStatus"
    const val FIELD_LAST_SYNCED_AT = "lastSyncedAt"
    const val FIELD_OWNER_UID = "ownerUid"
    const val FIELD_VERSION = "version"

    const val SYNC_RETRY_MAX_ATTEMPTS = 3
    const val SYNC_RETRY_INITIAL_DELAY_MS = 1000L
    const val SYNC_RETRY_BACKOFF_MULTIPLIER = 2.0
    const val SYNC_BATCH_SIZE = 50
    const val SYNC_TIMEOUT_MS = 30_000L
    const val SYNC_DEBOUNCE_MS = 2000L

    const val CONNECTIVITY_CHECK_INTERVAL_MS = 5000L
}
