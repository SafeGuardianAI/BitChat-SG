package com.bitchat.android.ui.lite

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent settings for the Lite/Full mode toggle. Encrypted at rest so
 * that an elder's care-flow choices aren't recoverable from device backups.
 */
class LiteModePreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Force-disable Lite mode even on a LITE-tier device. Default false. */
    var forceFullMode: Boolean
        get() = prefs.getBoolean(KEY_FORCE_FULL_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_FULL_MODE, value).apply()

    /** Force Lite mode even on a STANDARD/FULL device (caregiver setup). Default false. */
    var forceLiteMode: Boolean
        get() = prefs.getBoolean(KEY_FORCE_LITE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_LITE_MODE, value).apply()

    companion object {
        private const val PREFS_NAME = "safeguardian_lite_prefs"
        private const val KEY_FORCE_FULL_MODE = "force_full_mode"
        private const val KEY_FORCE_LITE_MODE = "force_lite_mode"
    }
}
