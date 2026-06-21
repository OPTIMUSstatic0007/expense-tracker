# Android Bridge Analysis

## Android ↔ WebView Communication
- The Android app (`MainActivity.kt`) uses an embedded `WebView`.
- It loads `APP_URL` which is currently hardcoded to `http://10.0.2.2:8080` (a local network IP aimed at the emulator's host Ktor server).
- Currently, NO `window.AndroidBridge` interface is configured or injected natively via `addJavascriptInterface`.

## JS Interfaces
- None exist in `MainActivity.kt`.

## Data Contracts
- The frontend relies on HTTP REST API calls via `fetch` to interact with the backend, instead of a native bridge.

## JSON Payload Formats
- Defined by the Kotlin Ktor REST APIs mapped to standard models (e.g., `Transaction.kt`).

## Bridge Lifecycle
- N/A natively.

## Offline Architecture Interaction
- The Room DB (`ExpenseDatabase.kt` / `LocalRepository.kt`) and the Ktor backend are completely disconnected. The Android application is merely acting as a thin browser wrapper around the Ktor-served URL, providing no native offline-first functionality despite the presence of Room DAOs.
