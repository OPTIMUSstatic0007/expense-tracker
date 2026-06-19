package com.example.expensetracker.cloud

import com.google.firebase.auth.FirebaseUser
import com.example.expensetracker.firebase.FirestoreConstants
import com.example.expensetracker.local.TransactionEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncManager(
    private val cloudFirestoreRepository: CloudFirestoreRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val deviceId: String
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disabled("Awaiting authentication"))
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    val connectivityState: StateFlow<ConnectivityState> = connectivityMonitor.connectivityState

    private var initializedUid: String? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val uploadMutex = Mutex()
    private val pendingUploads = mutableListOf<PendingUpload>()
    private var connectivityJob: Job? = null

    fun initialize(user: FirebaseUser) {
        if (initializedUid == user.uid) {
            SyncLogger.info("SyncManager already initialized for uid=${user.uid}")
            return
        }

        SyncLogger.info("SyncManager initialization requested for uid=${user.uid}")
        val repositoryInitialized = cloudFirestoreRepository.initialize(user)

        _syncState.value = if (repositoryInitialized) {
            SyncState.Idle
        } else {
            SyncState.Disabled("Cloud Firestore unavailable")
        }

        initializedUid = user.uid
        connectivityMonitor.start()
        observeConnectivity()
        requestUploadDrain()
        SyncLogger.info("SyncManager initialized for uid=${user.uid}")
    }

    fun onStart() {
        SyncLogger.info("SyncManager onStart")
    }

    fun onStop() {
        connectivityMonitor.stop()
        SyncLogger.info("SyncManager onStop")
    }

    fun onUserAuthenticated(user: FirebaseUser) {
        initialize(user)
    }

    fun onUserSignedOut() {
        initializedUid = null
        cloudFirestoreRepository.clear()
        connectivityMonitor.stop()
        _syncState.value = SyncState.Disabled("Awaiting authentication")
        managerScope.launch {
            queueMutex.withLock {
                pendingUploads.clear()
            }
        }
        SyncLogger.info("SyncManager reset after sign out")
    }

    suspend fun onTransactionChanged(
        transaction: TransactionEntity,
        operationType: PendingOperation.OperationType
    ) {
        val pendingOperation = PendingOperation(
            id = UUID.randomUUID().toString(),
            ownerUid = initializedUid.orEmpty(),
            deviceId = deviceId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            version = maxOf(1L, transaction.updatedAt),
            syncStatus = SyncStatus.PENDING.name,
            transactionId = transaction.id,
            operationType = operationType
        )

        queueMutex.withLock {
            pendingUploads.add(PendingUpload(pendingOperation, transaction))
        }

        SyncLogger.info(
            "Pending created: operation=${operationType.name} transactionId=${transaction.id}"
        )
        requestUploadDrain()
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) {
            return
        }

        connectivityJob = managerScope.launch {
            connectivityMonitor.connectivityState.collectLatest { state ->
                if (state is ConnectivityState.Online) {
                    requestUploadDrain()
                }
            }
        }
    }

    private fun requestUploadDrain() {
        managerScope.launch {
            drainPendingUploads()
        }
    }

    private suspend fun drainPendingUploads() {
        uploadMutex.withLock {
            while (true) {
                if (connectivityMonitor.connectivityState.value is ConnectivityState.Offline) {
                    SyncLogger.info("Skipped because offline")
                    return
                }

                val nextUpload = queueMutex.withLock {
                    pendingUploads.firstOrNull {
                        it.operation.syncStatus == SyncStatus.PENDING.name ||
                                it.operation.syncStatus == SyncStatus.FAILED.name
                    }
                } ?: run {
                    _syncState.value = SyncState.Idle
                    return
                }

                if (nextUpload.operation.ownerUid.isBlank()) {
                    SyncLogger.warning(
                        "Authentication missing: pending upload has no ownerUid for transactionId=${nextUpload.operation.transactionId}"
                    )
                    _syncState.value = SyncState.Disabled("Awaiting authentication")
                    return
                }

                _syncState.value = SyncState.Syncing
                val completed = uploadWithRetry(nextUpload)
                if (!completed) {
                    return
                }

                queueMutex.withLock {
                    pendingUploads.removeAll { it.operation.id == nextUpload.operation.id }
                }
                SyncLogger.info(
                    "Upload succeeded: operation=${nextUpload.operation.operationType.name} transactionId=${nextUpload.operation.transactionId}"
                )
            }
        }
    }

    private suspend fun uploadWithRetry(upload: PendingUpload): Boolean {
        var currentOperation = upload.operation

        while (currentOperation.retryCount < FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS) {
            if (connectivityMonitor.connectivityState.value is ConnectivityState.Offline) {
                queueMutex.withLock {
                    replaceOperation(currentOperation.copy(syncStatus = SyncStatus.PENDING.name))
                }
                SyncLogger.info("Skipped because offline")
                return false
            }

            try {
                SyncLogger.info(
                    "Upload started: operation=${currentOperation.operationType.name} transactionId=${currentOperation.transactionId}"
                )
                val cloudTransaction = CloudTransactionMapper.fromEntity(
                    entity = upload.transaction,
                    ownerUid = currentOperation.ownerUid,
                    deviceId = currentOperation.deviceId
                )

                when (currentOperation.operationType) {
                    PendingOperation.OperationType.CREATE -> {
                        cloudFirestoreRepository.createTransaction(cloudTransaction)
                    }

                    PendingOperation.OperationType.UPDATE -> {
                        cloudFirestoreRepository.updateTransaction(cloudTransaction)
                    }

                    PendingOperation.OperationType.DELETE -> {
                        cloudFirestoreRepository.softDeleteTransaction(
                            cloudTransaction.copy(deleted = true)
                        )
                    }
                }
                return true
            } catch (exception: Exception) {
                val nextRetryCount = currentOperation.retryCount + 1
                val nextStatus = if (nextRetryCount >= FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS) {
                    SyncStatus.FAILED.name
                } else {
                    SyncStatus.PENDING.name
                }
                currentOperation = currentOperation.copy(
                    updatedAt = System.currentTimeMillis(),
                    attemptedAt = System.currentTimeMillis(),
                    retryCount = nextRetryCount,
                    syncStatus = nextStatus
                )
                queueMutex.withLock {
                    replaceOperation(currentOperation)
                }

                SyncLogger.error(
                    "Upload failed: operation=${currentOperation.operationType.name} transactionId=${currentOperation.transactionId} retryCount=${currentOperation.retryCount}",
                    exception
                )

                if (currentOperation.retryCount >= FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS) {
                    _syncState.value = SyncState.Error("Upload failed after max retries")
                    return false
                }

                SyncLogger.info(
                    "Retry: operation=${currentOperation.operationType.name} transactionId=${currentOperation.transactionId} retryCount=${currentOperation.retryCount}"
                )
                delay(FirestoreConstants.SYNC_RETRY_INITIAL_DELAY_MS * currentOperation.retryCount)
            }
        }

        return false
    }

    private fun replaceOperation(operation: PendingOperation) {
        val index = pendingUploads.indexOfFirst { it.operation.id == operation.id }
        if (index >= 0) {
            val currentUpload = pendingUploads[index]
            pendingUploads[index] = currentUpload.copy(operation = operation)
        }
    }

    private data class PendingUpload(
        val operation: PendingOperation,
        val transaction: TransactionEntity
    )
}
