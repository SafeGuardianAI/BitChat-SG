package com.bitchat.android.ai

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * WorkManager worker that flushes pending victim records to cloud backends
 * when network connectivity is restored.
 *
 * Enqueued with [NETWORK_CONNECTED] constraint so Android only runs it when
 * the device comes online — true store-and-push offline-first behaviour.
 */
class VictimSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "VictimSyncWorker"
        private const val WORK_NAME = "victim_sync"

        /**
         * Enqueue a one-time sync. Safe to call on every AI response — WorkManager
         * deduplicates via [ExistingWorkPolicy.KEEP] so only one job runs at a time.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<VictimSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Victim sync started")
        val localStore = VictimLocalStore(applicationContext)

        var totalFlushed = 0

        // Firebase flush
        if (localStore.hasPending(BackendType.FIREBASE)) {
            val firestoreStore = FirestoreVictimStore(applicationContext)
            totalFlushed += firestoreStore.flushLocalQueue(localStore)
        }

        // MongoDB flush — use existing HTTP client
        if (localStore.hasPending(BackendType.MONGODB)) {
            val rescueService = RescueAPIService.getInstance(applicationContext)
            totalFlushed += flushMongoDB(localStore, rescueService)
        }

        Log.d(TAG, "Victim sync complete: $totalFlushed records flushed")
        return Result.success()
    }

    private suspend fun flushMongoDB(
        localStore: VictimLocalStore,
        rescueService: RescueAPIService
    ): Int {
        var flushed = 0

        for (entry in localStore.getPendingCreates(BackendType.MONGODB)) {
            val localId = entry.optString("local_id")
            val data = entry.optString("data")
            val victim = rescueService.parseVictimFromResponse(data) ?: continue
            val cloudId = rescueService.postVictim(victim)
            if (cloudId != null) {
                localStore.markSynced(localId, cloudId)
                localStore.removeSynced(localId)
                flushed++
            }
        }

        for (entry in localStore.getPendingUpdates(BackendType.MONGODB)) {
            val localId = entry.optString("local_id")
            val cloudId = entry.optString("cloud_id")
            val data = entry.optString("data")
            val victim = rescueService.parseVictimFromResponse(data) ?: continue
            if (rescueService.updateVictim(cloudId, victim)) {
                localStore.removeSynced(localId)
                flushed++
            }
        }

        return flushed
    }
}
