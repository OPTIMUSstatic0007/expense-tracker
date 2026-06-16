package com.example.expensetracker.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported theme modes.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

/**
 * Single source of truth for the app's theme.
 *
 * Persists the user's choice in SharedPreferences so it survives
 * app restart, process death, sign-out, and sign-in.
 *
 * Exposes a [StateFlow] that Compose and the WebView bridge both observe
 * to stay in sync.
 */
class ThemeManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "expense_tracker_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadTheme())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * Persist and emit a new theme mode.
     */
    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    /**
     * Toggle between LIGHT and DARK.
     * If currently SYSTEM, resolves to the opposite of [isSystemDark].
     */
    fun toggleTheme(isSystemDark: Boolean = false) {
        val current = _themeMode.value
        val nextMode = when (current) {
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.SYSTEM -> if (isSystemDark) ThemeMode.LIGHT else ThemeMode.DARK
        }
        setTheme(nextMode)
    }

    /**
     * Resolve whether the UI should render in dark mode right now.
     *
     * @param isSystemDark The current system-level dark mode flag.
     */
    fun isDark(isSystemDark: Boolean): Boolean {
        return when (_themeMode.value) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemDark
        }
    }

    private fun loadTheme(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null)
        return try {
            if (stored != null) ThemeMode.valueOf(stored) else ThemeMode.SYSTEM
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }
}
