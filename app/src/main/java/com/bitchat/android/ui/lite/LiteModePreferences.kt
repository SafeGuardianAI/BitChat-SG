package com.bitchat.android.ui.lite

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent + observable Lite/Full mode toggles.
 *
 * Attempts encrypted-at-rest storage; falls back to plain SharedPreferences
 * if the Keystore is unavailable (first-boot key generation, corrupted keystore,
 * some emulators). Lite-mode preference is not sensitive enough to crash over.
 */
class LiteModePreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { e ->
        Log.w("LiteModePrefs", "EncryptedSharedPreferences unavailable, using plain: ${e.message}")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _forceFullMode = MutableStateFlow(prefs.getBoolean(KEY_FORCE_FULL_MODE, false))
    val forceFullMode: StateFlow<Boolean> = _forceFullMode.asStateFlow()

    private val _forceLiteMode = MutableStateFlow(prefs.getBoolean(KEY_FORCE_LITE_MODE, false))
    val forceLiteMode: StateFlow<Boolean> = _forceLiteMode.asStateFlow()

    fun setForceFullMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_FULL_MODE, value).apply()
        _forceFullMode.value = value
    }

    fun setForceLiteMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_LITE_MODE, value).apply()
        _forceLiteMode.value = value
    }

    /** Switch into Lite mode: enable lite, clear any override that pinned full. */
    fun enableLiteMode() {
        setForceLiteMode(true)
        setForceFullMode(false)
    }

    companion object {
        private const val PREFS_NAME = "safeguardian_lite_prefs"
        private const val KEY_FORCE_FULL_MODE = "force_full_mode"
        private const val KEY_FORCE_LITE_MODE = "force_lite_mode"

        @Volatile private var INSTANCE: LiteModePreferences? = null

        fun get(context: Context): LiteModePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiteModePreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
