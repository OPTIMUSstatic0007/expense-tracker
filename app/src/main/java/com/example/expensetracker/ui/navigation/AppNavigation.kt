package com.example.expensetracker.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Screens in the app.
 */
enum class Screen {
    Login,
    Dashboard,
    Settings
}

/**
 * Simple navigation state holder — no Jetpack Navigation library needed.
 * The current screen is driven by auth state + user actions.
 */
class AppNavigationState {
    val currentScreen: MutableState<Screen> = mutableStateOf(Screen.Dashboard)

    fun navigateTo(screen: Screen) {
        currentScreen.value = screen
    }
}
