package com.bitchat.android

import android.app.Application
import android.util.Log
import com.bitchat.android.ai.AIManager
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main application class for bitchat Android
 * Enhanced with SafeGuardian AI capabilities
 */
class BitchatApplication : Application() {

    // Application-level coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // AI Manager (lazy initialization)
    val aiManager: AIManager by lazy {
        AIManager(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try { TorManager.init(this) } catch (_: Exception) { }

        // Initialize Nexa SDK early (required for ASR/LLM)
        try {
            com.nexa.sdk.NexaSdk.getInstance().init(applicationContext)
        } catch (e: Exception) {
            Log.e("BitchatApp", "Nexa SDK init failed (non-fatal)", e)
        }

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

        // TorManager already initialized above

        // ============================================
        // SafeGuardian AI Initialization
        // ============================================
        initializeAI()
    }

    /**
     * Initialize AI system in background
     */
    private fun initializeAI() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // Initialize Nexa SDK
                aiManager.initialize()

                // Cleanup incomplete downloads
                aiManager.modelManager.cleanupIncompleteDownloads()

                Log.d("BitchatApp", "AI system initialized successfully")
            } catch (e: Exception) {
                Log.e("BitchatApp", "AI initialization failed (non-fatal)", e)
                // Non-fatal: app can continue without AI
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        // Cleanup AI resources
        try {
            aiManager.shutdown()
        } catch (_: Exception) { }
    }
}
