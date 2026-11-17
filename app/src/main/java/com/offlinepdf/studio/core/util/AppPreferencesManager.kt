package com.offlinepdf.studio.core.util

import android.content.Context
import android.content.SharedPreferences

class AppPreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "DARK") ?: "DARK"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var defaultCompression: String
        get() = prefs.getString(KEY_COMPRESSION, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString(KEY_COMPRESSION, value).apply()

    var defaultPageSize: String
        get() = prefs.getString(KEY_PAGE_SIZE, "A4") ?: "A4"
        set(value) = prefs.edit().putString(KEY_PAGE_SIZE, value).apply()

    var directDownloadEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIRECT_DOWNLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_DIRECT_DOWNLOAD, value).apply()

    var autoSanitizeOnSave: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SANITIZE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SANITIZE, value).apply()

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_COMPRESSION = "default_compression"
        private const val KEY_PAGE_SIZE = "default_page_size"
        private const val KEY_DIRECT_DOWNLOAD = "direct_download_enabled"
        private const val KEY_AUTO_SANITIZE = "auto_sanitize_on_save"
    }
}
