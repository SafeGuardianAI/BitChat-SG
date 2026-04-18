package com.bitchat.android

import android.app.Application
import android.system.Os
import android.util.Log
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.TorManager
import com.nexa.sdk.NexaSdk

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Tor first so any early network goes over Tor
        try { TorManager.init(this) } catch (_: Exception) { }

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Set Nexa SDK environment variables before SDK init so they are available
        // when the native plugin loader runs. NEXA_TOKEN authenticates NPU model downloads.
        // NEXA_PLUGIN_PATH tells the SDK where to find the JNI .so files.
        try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            if (BuildConfig.NEXA_TOKEN.isNotBlank()) {
                Os.setenv("NEXA_TOKEN", BuildConfig.NEXA_TOKEN, true)
            }
            Os.setenv("NEXA_PLUGIN_PATH", nativeLibDir, true)
            // Ensure the dynamic linker can find QNN HTP delegate libraries
            val existingLd = System.getenv("LD_LIBRARY_PATH") ?: ""
            Os.setenv(
                "LD_LIBRARY_PATH",
                if (existingLd.isBlank()) nativeLibDir else "$nativeLibDir:$existingLd",
                true
            )
            Log.d("BitchatApplication", "Nexa env vars set (token=${BuildConfig.NEXA_TOKEN.isNotBlank()}, pluginPath=$nativeLibDir)")
        } catch (e: Exception) {
            Log.w("BitchatApplication", "Failed to set Nexa env vars", e)
        }

        // Initialize Nexa SDK runtime (required before any LlmWrapper usage)
        try {
            NexaSdk.getInstance().init(this, object : NexaSdk.InitCallback {
                override fun onSuccess() {
                    Log.d("BitchatApplication", "Nexa SDK initialized")
                }
                override fun onFailure(reason: String) {
                    Log.e("BitchatApplication", "Nexa SDK init failed: $reason")
                }
            })
        } catch (e: Exception) {
            Log.e("BitchatApplication", "Nexa SDK init threw", e)
        }
    }
}
