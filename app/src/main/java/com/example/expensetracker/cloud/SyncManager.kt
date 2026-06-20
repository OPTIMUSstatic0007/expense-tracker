package com.example.expensetracker.cloud

import com.google.firebase.auth.FirebaseUser
import com.example.expensetracker.firebase.FirestoreConstants
import com.example.expensetracker.local.TransactionEntity
import com.example.expensetracker.repository.LocalRepository
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
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
    private val pendingSyncRepository: PendingSyncRepository,
    private val deviceId: String
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Disabled("Awaiting authentication"))
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    val connectivityState: StateFlow<ConnectivityState> = connectivityMonitor.connectivityState

    private var initializedUid: String? = null
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private val downloadMutex = Mutex()
    private val realtimeMutex = Mutex()
    private val pendingRealtimeChanges = mutableListOf<PendingRealtimeChange>()
    private var connectivityJob: Job? = null
    private var localRepository: LocalRepository? = null
    private var realtimeListenerRegistration: ListenerRegistration? = null
    private var realtimeListenerUid: String? = null

    fun initialize(user: FirebaseUser) {
        if (initializedUid == user.uid) {
            SyncLogger.info("SyncManager already initialized for uid=${user.uid}")
            attachRealtimeListener()
            return
        }

        if (initializedUid != null) {
            detachRealtimeListener()
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
        attachRealtimeListener()
        requestQueueRestore()
        requestUploadDrain()
        requestDownload()
        SyncLogger.info("SyncManager initialized for uid=${user.uid}")
    }

    fun onStart() {
        SyncLogger.info("SyncManager onStart")
    }

    fun onStop() {
        connectivityMonitor.stop()
        SyncLogger.info("SyncManager onStop")
    }

    fun shutdown() {
        detachRealtimeListener()
        connectivityJob?.cancel()
        connectivityJob = null
        connectivityMonitor.stop()
        managerJob.cancel()
        SyncLogger.info("SyncManager shutdown")
    }

    fun onUserAuthenticated(user: FirebaseUser) {
        initialize(user)
    }

    fun onUserSignedOut() {
        detachRealtimeListener()
        initializedUid = null
        cloudFirestoreRepository.clear()
        connectivityMonitor.stop()
        _syncState.value = SyncState.Disabled("Awaiting authentication")
        managerScope.launch {
            realtimeMutex.withLock {
                pendingRealtimeChanges.clear()
            }
        }
        localRepository = null
        SyncLogger.info("SyncManager reset after sign out")
    }

    fun attachLocalRepository(repository: LocalRepository) {
        localRepository = repository
        SyncLogger.info("SyncManager local repository attached")
        attachRealtimeListener()
        requestQueueRestore()
        requestUploadDrain()
        requestRealtimeDrain()
        requestDownload()
    }

    suspend fun onTransactionChanged(
        transaction: TransactionEntity,
        operationType: PendingSyncOperation.OperationType
    ) {
        val uid = initializedUid
        if (uid.isNullOrBlank()) {
            SyncLogger.warning("Queue insert skipped: authenticated user missing")
            _syncState.value = SyncState.Disabled("Awaiting authentication")
            return
        }

        val now = System.currentTimeMillis()
        val pendingOperation = PendingSyncOperation(
            id = UUID.randomUUID().toString(),
            ownerUid = uid,
            deviceId = deviceId,
            createdAt = now,
            updatedAt = now,
            version = maxOf(1L, transaction.updatedAt),
            transactionId = transaction.id,
            operationType = operationType,
            status = PendingSyncOperation.Status.PENDING
        )

        pendingSyncRepository.insert(pendingOperation)
        requestUploadDrain()
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) {
            return
        }

        connectivityJob = managerScope.launch {
            connectivityMonitor.connectivityState.collectLatest { state ->
                if (state is ConnectivityState.Online) {
                    logQueueResume()
                    requestUploadDrain()
                    requestDownload()
                }
            }
        }
    }

    private fun requestQueueRestore() {
        managerScope.launch {
            val uid = initializedUid ?: return@launch
            val pendingCount = pendingSyncRepository.countPending(uid)
            if (pendingCount > 0) {
                SyncLogger.info("Queue restored: pending=$pendingCount")
            }
        }
    }

    private fun logQueueResume() {
        managerScope.launch {
            val uid = initializedUid ?: return@launch
            val pendingCount = pendingSyncRepository.countPending(uid)
            if (pendingCount > 0) {
                SyncLogger.info("Queue resumed: pending=$pendingCount")
            }
        }
    }

    private fun requestUploadDrain() {
        managerScope.launch {
            drainPendingUploads()
        }
    }

    fun downloadFromCloud() {
        requestDownload()
    }

    private fun requestDownload() {
        managerScope.launch {
            downloadMissingTransactions()
        }
    }

    private fun requestRealtimeDrain() {
        managerScope.launch {
            drainPendingRealtimeChanges()
        }
    }

    private fun attachRealtimeListener() {
        val uid = initializedUid
        if (uid.isNullOrBlank()) {
            SyncLogger.warning("Realtime listener skipped: authenticated user missing")
            return
        }

        if (!cloudFirestoreRepository.isInitialized) {
            SyncLogger.warning("Realtime listener skipped: Cloud Firestore unavailable")
            return
        }

        if (realtimeListenerRegistration != null && realtimeListenerUid == uid) {
            return
        }

        detachRealtimeListener()

        try {
            realtimeListenerRegistration = cloudFirestoreRepository.listenToTransactionChanges(
                onDocumentChange = { changeType, transaction ->
                    managerScope.launch {
                        applyRealtimeChange(changeType, transaction)
                    }
                },
                onError = { exception ->
                    _syncState.value = SyncState.Error("Realtime listener failed")
                    SyncLogger.error("Realtime listener failed", exception)
                }
            )
            realtimeListenerUid = uid
            SyncLogger.info("Realtime listener attached")
        } catch (exception: Exception) {
            realtimeListenerRegistration = null
            realtimeListenerUid = null
            _syncState.value = SyncState.Error("Realtime listener failed")
            SyncLogger.error("Realtime listener failed", exception)
        }
    }

    private fun detachRealtimeListener() {
        val registration = realtimeListenerRegistration ?: return
        registration.remove()
        realtimeListenerRegistration = null
        realtimeListenerUid = null
        SyncLogger.info("Realtime detached")
    }

    private suspend fun applyRealtimeChange(
        changeType: DocumentChange.Type,
        transaction: CloudTransaction
    ) {
        realtimeMutex.withLock {
            val uid = initializedUid
            if (uid.isNullOrBlank()) {
                SyncLogger.warning("Realtime skipped: authenticated user missing")
                return
            }

            if (transaction.ownerUid != uid) {
                SyncLogger.warning("Realtime skipped owner mismatch for transactionId=${transaction.id}")
                return
            }

            val repository = localRepository
            if (repository == null) {
                pendingRealtimeChanges.add(PendingRealtimeChange(changeType, transaction))
                SyncLogger.info("Realtime deferred: local repository unavailable")
                return
            }

            when (changeType) {
                DocumentChange.Type.ADDED -> applyRealtimeAdded(repository, transaction)
                DocumentChange.Type.MODIFIED -> applyRealtimeModified(repository, transaction)
                DocumentChange.Type.REMOVED -> applyRealtimeRemoved(repository, transaction)
            }
        }
    }

    private suspend fun drainPendingRealtimeChanges() {
        realtimeMutex.withLock {
            val repository = localRepository ?: return
            if (pendingRealtimeChanges.isEmpty()) {
                return
            }

            val changes = pendingRealtimeChanges.toList()
            pendingRealtimeChanges.clear()
            changes.forEach { change ->
                when (change.changeType) {
                    DocumentChange.Type.ADDED -> applyRealtimeAdded(repository, change.transaction)
                    DocumentChange.Type.MODIFIED -> applyRealtimeModified(repository, change.transaction)
                    DocumentChange.Type.REMOVED -> applyRealtimeRemoved(repository, change.transaction)
                }
            }
        }
    }

    private suspend fun applyRealtimeAdded(
        repository: LocalRepository,
        transaction: CloudTransaction
    ) {
        val existing = repository.getTransactionById(transaction.id)
        if (existing != null) {
            SyncLogger.info("Realtime ADDED ignored existing transactionId=${transaction.id}")
            return
        }

        val inserted = repository.insertTransactionFromCloud(
            CloudTransactionMapper.toEntity(transaction)
        )
        if (inserted) {
            SyncLogger.info("Realtime ADDED transactionId=${transaction.id}")
        }
    }

    private suspend fun applyRealtimeModified(
        repository: LocalRepository,
        transaction: CloudTransaction
    ) {
        val existing = repository.getTransactionById(transaction.id)
        if (existing == null) {
            SyncLogger.info("Realtime MODIFIED ignored missing transactionId=${transaction.id}")
            return
        }

        if (isSelfEcho(transaction, existing)) {
            SyncLogger.info("Realtime MODIFIED ignored self echo transactionId=${transaction.id}")
            return
        }

        repository.updateTransactionFromCloud(CloudTransactionMapper.toEntity(transaction))
        SyncLogger.info("Realtime MODIFIED transactionId=${transaction.id}")
    }

    private suspend fun applyRealtimeRemoved(
        repository: LocalRepository,
        transaction: CloudTransaction
    ) {
        val existing = repository.getTransactionById(transaction.id)
        if (existing == null) {
            SyncLogger.info("Realtime REMOVED ignored missing transactionId=${transaction.id}")
            return
        }

        repository.softDeleteTransactionFromCloud(
            id = transaction.id,
            updatedAt = maxOf(transaction.updatedAt, existing.updatedAt)
        )
        SyncLogger.info("Realtime REMOVED transactionId=${transaction.id}")
    }

    private fun isSelfEcho(
        transaction: CloudTransaction,
        existing: TransactionEntity
    ): Boolean {
        return transaction.deviceId == deviceId &&
                existing.amount == transaction.amount &&
                existing.type == transaction.type &&
                existing.category == transaction.category &&
                existing.note == transaction.note &&
                existing.createdAt == transaction.createdAt &&
                existing.updatedAt == transaction.updatedAt &&
                existing.deleted == transaction.deleted
    }

    private suspend fun downloadMissingTransactions() {
        downloadMutex.withLock {
            val uid = initializedUid
            if (uid.isNullOrBlank()) {
                SyncLogger.warning("Download skipped: authenticated user missing")
                _syncState.value = SyncState.Disabled("Awaiting authentication")
                return
            }

            if (!cloudFirestoreRepository.isInitialized) {
                SyncLogger.warning("Download skipped: Cloud Firestore unavailable")
                _syncState.value = SyncState.Disabled("Cloud Firestore unavailable")
                return
            }

            if (connectivityMonitor.connectivityState.value is ConnectivityState.Offline) {
                SyncLogger.info("Download skipped because offline")
                return
            }

            val repository = localRepository
            if (repository == null) {
                SyncLogger.info("Download deferred: local repository unavailable")
                return
            }

            try {
                _syncState.value = SyncState.Syncing
                val localCount = repository.getTransactionCount()
                val cloudTransactions = cloudFirestoreRepository.downloadTransactions()
                val entities = cloudTransactions.map(CloudTransactionMapper::toEntity)
                val insertedCount = repository.insertMissingTransactionsFromCloud(entities)
                SyncLogger.info(
                    "Download complete: localCount=$localCount cloudCount=${cloudTransactions.size} insertedCount=$insertedCount"
                )
                _syncState.value = SyncState.Idle
            } catch (exception: Exception) {
                _syncState.value = SyncState.Error("Download failed")
                SyncLogger.error("Download failed", exception)
            }
        }
    }

    private suspend fun drainPendingUploads() {
        uploadMutex.withLock {
            while (true) {
                if (connectivityMonitor.connectivityState.value is ConnectivityState.Offline) {
                    SyncLogger.info("Skipped because offline")
                    return
                }

                val uid = initializedUid
                if (uid.isNullOrBlank()) {
                    SyncLogger.warning("Upload skipped: authenticated user missing")
                    _syncState.value = SyncState.Disabled("Awaiting authentication")
                    return
                }

                val repository = localRepository
                if (repository == null) {
                    SyncLogger.info("Queue upload deferred: local repository unavailable")
                    return
                }

                val nextOperation = pendingSyncRepository.getNextPending(uid) ?: run {
                    _syncState.value = SyncState.Idle
                    SyncLogger.info("Queue empty")
                    return
                }

                if (nextOperation.ownerUid.isBlank()) {
                    SyncLogger.warning(
                        "Authentication missing: pending upload has no ownerUid for transactionId=${nextOperation.transactionId}"
                    )
                    _syncState.value = SyncState.Disabled("Awaiting authentication")
                    return
                }

                val transaction = repository.getTransactionById(nextOperation.transactionId)
                if (transaction == null) {
                    val failedOperation = pendingSyncRepository.markFailed(nextOperation)
                    logUploadFailure(
                        operation = failedOperation,
                        exception = IllegalStateException("Local transaction missing")
                    )
                    if (failedOperation.status == PendingSyncOperation.Status.FAILED) {
                        _syncState.value = SyncState.Error("Upload failed after max retries")
                    }
                    return
                }

                _syncState.value = SyncState.Syncing
                val completed = uploadWithRetry(nextOperation, transaction)
                if (!completed) {
                    return
                }

                val completedOperation = pendingSyncRepository.markCompleted(nextOperation)
                pendingSyncRepository.delete(completedOperation)
                SyncLogger.info(
                    "Upload succeeded: operation=${nextOperation.operationType.name} transactionId=${nextOperation.transactionId}"
                )
            }
        }
    }

    private suspend fun uploadWithRetry(
        operation: PendingSyncOperation,
        transaction: TransactionEntity
    ): Boolean {
        var currentOperation = operation

        while (currentOperation.retryCount < FirestoreConstants.SYNC_RETRY_MAX_ATTEMPTS) {
            if (connectivityMonitor.connectivityState.value is ConnectivityState.Offline) {
                SyncLogger.info("Skipped because offline")
                return false
            }

            try {
                currentOperation = pendingSyncRepository.markUploading(currentOperation)
                SyncLogger.info(
                    "Uploading operation: operation=${currentOperation.operationType.name} transactionId=${currentOperation.transactionId}"
                )
                val cloudTransaction = CloudTransactionMapper.fromEntity(
                    entity = transaction,
                    ownerUid = currentOperation.ownerUid,
                    deviceId = currentOperation.deviceId
                )

                when (currentOperation.operationType) {
                    PendingSyncOperation.OperationType.CREATE -> {
                        cloudFirestoreRepository.createTransaction(cloudTransaction)
                    }

                    PendingSyncOperation.OperationType.UPDATE -> {
                        cloudFirestoreRepository.updateTransaction(cloudTransaction)
                    }

                    PendingSyncOperation.OperationType.DELETE -> {
                        cloudFirestoreRepository.softDeleteTransaction(
                            cloudTransaction.copy(deleted = true)
                        )
                    }
                }
                return true
            } catch (exception: Exception) {
                currentOperation = pendingSyncRepository.markFailed(currentOperation)
                logUploadFailure(currentOperation, exception)

                if (currentOperation.status == PendingSyncOperation.Status.FAILED) {
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

    private fun logUploadFailure(
        operation: PendingSyncOperation,
        exception: Exception
    ) {
        if (operation.status == PendingSyncOperation.Status.FAILED) {
            SyncLogger.error(
                "Failed permanently: operation=${operation.operationType.name} transactionId=${operation.transactionId} retryCount=${operation.retryCount}",
                exception
            )
        } else {
            SyncLogger.error(
                "Upload failed: operation=${operation.operationType.name} transactionId=${operation.transactionId} retryCount=${operation.retryCount}",
                exception
            )
        }
    }

    private data class PendingRealtimeChange(
        val changeType: DocumentChange.Type,
        val transaction: CloudTransaction
    )
}
