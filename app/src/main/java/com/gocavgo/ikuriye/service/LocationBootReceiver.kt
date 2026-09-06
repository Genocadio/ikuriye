package com.gocavgo.ikuriye.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gocavgo.ikuriye.data.AuthRepository

/**
 * Restarts the LocationService after device reboot or app update.
 *
 * Registered in AndroidManifest for:
 * - BOOT_COMPLETED: device reboot
 * - MY_PACKAGE_REPLACED: app update via Play Store
 *
 * Only starts the service if a user is logged in (checks SharedPreferences cache).
 */
class LocationBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Received broadcast: $action")

        // Only restart if user is logged in
        val cachedUser = AuthRepository.getCachedUser()
        if (cachedUser == null) {
            Log.d(TAG, "No logged-in user — skipping LocationService restart")
            return
        }

        startLocationService(context)
    }

    private fun startLocationService(context: Context) {
        val serviceIntent = Intent(context, LocationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "LocationService restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart LocationService: ${e.message}")
        }
    }
}
