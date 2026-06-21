# Auth and Sync Analysis

## Firebase Integration
- `GoogleAuthManager.kt` provides native Google Sign-In capabilities using `CredentialManager`.
- `FirestoreRepository.kt` defines CRUD operations against Firebase Firestore collections.

## Google Sign-In Flow
- Initialized in `MainActivity.kt` with a "Test Google Sign In" button that launches the intent.

## Sync Engine Architecture
- It appears to be an unimplemented boilerplate. `FirestoreRepository` contains functions (`uploadTransaction`, `fetchTransactions`) that map `com.example.expensetracker.model.Transaction` to Firestore.

## Firestore Interaction
- Organizes data under `/users/{uid}/transactions/{transactionId}`.

## Local vs Remote Data Ownership
- The local Room DAO (`TransactionDao`) has a `syncPending` flag.
- The Ktor backend (`TransactionService.kt`) has no knowledge of Firebase or Sync logic.

## Current Implementation Status
- Disconnected. The Ktor backend serves the active data, while the native Android Firebase/Room implementations are present but not wired up to the frontend UI or main transaction loop.
