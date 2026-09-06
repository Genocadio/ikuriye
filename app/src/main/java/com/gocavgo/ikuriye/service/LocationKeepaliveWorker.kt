package com.gocavgo.ikuriye.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.gocavgo.ikuriye.data.AuthRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that checks if LocationService is alive and restarts it.
 *
 * This is the last line of defense against OEM battery killers (Xiaomi, Huawei,
 * Samsung, OnePlus) that aggressively kill foreground services. WorkManager uses
 * AlarmManager under the hood, which is much harder for OEMs to kill.
 *
 * Runs every 15 minutes (WorkManager minimum interval).
 * Only active when a user is logged in.
 */
class LocationKeepaliveWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    companion object {
        private const val TAG = "LocationKeepalive"
        private const val WORK_NAME = "location_keepalive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationKeepaliveWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Keepalive scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Keepalive cancelled")
        }
    }

    override fun doWork(): Result {
        val cachedUser = AuthRepository.getCachedUser()
        if (cachedUser == null) {
            Log.d(TAG, "No logged-in user — skipping keepalive check")
            return Result.success()
        }

        Log.d(TAG, "Checking LocationService status")

        // Start the service — if it's already running, this is a no-op.
        // If it was killed, this restarts it.
        val serviceIntent = Intent(applicationContext, LocationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Log.d(TAG, "LocationService keepalive OK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart LocationService: ${e.message}")
            return Result.retry()
        }

        return Result.success()
    }
}
