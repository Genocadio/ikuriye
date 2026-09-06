package com.gocavgo.ikuriye.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.gocavgo.ikuriye.MainActivity
import androidx.core.net.toUri

class LocationService : Service() {

    companion object {
        const val TAG = "CavgoLocation"
        const val CHANNEL_ID = "cavgo_location_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_LOCATION_UPDATE = "com.gocavgo.drivers.LOCATION_UPDATE"
        const val EXTRA_LAT      = "extra_lat"
        const val EXTRA_LNG      = "extra_lng"
        const val EXTRA_ACCURACY = "extra_accuracy"
        const val EXTRA_SPEED    = "extra_speed"

        const val ACTION_UPDATE_TRIP    = "com.gocavgo.drivers.UPDATE_TRIP"
        const val EXTRA_STOP_NAME       = "extra_stop_name"
        const val EXTRA_PICKUP_COUNT    = "extra_pickup_count"
        const val EXTRA_DROPOFF_COUNT   = "extra_dropoff_count"

        private const val UPDATE_INTERVAL_MS   = 5_000L
        private const val FASTEST_INTERVAL_MS  = 3_000L
        private const val MIN_DISPLACEMENT_M   = 5f

        fun requestBatteryOptimisationExemption(context: Context) {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationThread: HandlerThread
    private lateinit var watchdogHandler: Handler
    private var lastLocation: Location? = null

    // ── Fallback chain: HIGH_ACCURACY → BALANCED_POWER if stale ────────────
    @Volatile private var currentPriority = Priority.PRIORITY_HIGH_ACCURACY
    @Volatile private var fallbackTriggered = false

    // ── GPS watchdog ───────────────────────────────────────────────────────
    // After 30s without fix → re-request with same priority (some OEMs silently
    // stop the callback). After 60s → fall back to BALANCED_POWER_ACCURACY.
    @Volatile private var lastFixTimestamp = 0L
    @Volatile private var fixGapLogged = false
    @Volatile private var reRequestLogged = false
    @Volatile private var reRequestCount = 0
    private val FIX_GAP_WARN_MS = 30_000L
    private val STALENESS_REREQUEST_MS = 30_000L
    private val STALENESS_FALLBACK_MS = 60_000L
    private val MAX_REREQUEST_COUNT = 3

    // Trip data for notification
    @Volatile private var notifStopName = ""
    @Volatile private var notifPickups = 0
    @Volatile private var notifDropoffs = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithType()

        // Initialize MQTT publisher
        MqttLocationPublisher.init(this)

        // Dedicated background thread — avoids main-thread throttling on OEM ROMs
        locationThread = HandlerThread("CavgoLocationThread").also { it.start() }
        val looper = locationThread.looper

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        requestLocationUpdates(looper)
        startWatchdog()

        Log.i(TAG, "LocationService started")
    }

    private fun startForegroundWithType() {
        val notification = buildNotification("Acquiring location…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                Log.e(TAG, "Location permissions not granted — cannot start FGS with TYPE_LOCATION")
                stopSelf()
                return
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Location callback with MQTT publish + staleness detection ──────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            @SuppressLint("DefaultLocale")
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocation = location

                val now = System.currentTimeMillis()

                // Watchdog: stamp fix arrival; log recovery if gap was previously flagged
                if (fixGapLogged) {
                    fixGapLogged = false
                    Log.i(TAG, "📡 GPS fix resumed after ${(now - lastFixTimestamp) / 1000}s gap")
                }
                if (reRequestLogged) {
                    reRequestLogged = false
                    reRequestCount = 0
                }
                lastFixTimestamp = now

                // Reset fallback if we're getting good fixes
                if (fallbackTriggered && location.accuracy < 50f) {
                    fallbackTriggered = false
                    Log.i(TAG, "📡 GPS quality recovered — staying on HIGH_ACCURACY")
                }

                val lat = location.latitude
                val lng = location.longitude
                val accuracy = location.accuracy
                val speed = location.speed * 3.6f // m/s → km/h

                Log.d(TAG, "📍 LAT=$lat | LNG=$lng | ACC=${accuracy}m | SPD=${String.format(java.util.Locale.US, "%.1f", speed)} km/h | Priority=$currentPriority")

                // Publish GPS to MQTT broker
                MqttLocationPublisher.publishLocation(
                    lat = lat, lng = lng,
                    speed = location.speed, // m/s for MQTT
                    bearing = if (location.hasBearing()) location.bearing else null,
                    accuracy = accuracy
                )

                // Broadcast to UI
                sendBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LNG, lng)
                    putExtra(EXTRA_ACCURACY, accuracy)
                    putExtra(EXTRA_SPEED, speed)
                })

                // Update notification
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(buildNotifText()))
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Intentionally ignored — FLP fires false+true on same cycle.
                // Watchdog handles staleness detection instead.
            }
        }
    }

    // ── Watchdog: staleness detection + re-request + fallback ──────────────

    private fun startWatchdog() {
        watchdogHandler = Handler(locationThread.looper)
        val check = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val gap = now - lastFixTimestamp

                if (lastFixTimestamp > 0) {
                    // Warn after 30s without fix
                    if (gap > FIX_GAP_WARN_MS && !fixGapLogged) {
                        fixGapLogged = true
                        Log.i(TAG, "📡 No GPS fix for ${gap / 1000}s — still tracking")
                    }

                    // Re-request after 30s (some OEMs silently stop callback)
                    if (gap > STALENESS_REREQUEST_MS && reRequestCount < MAX_REREQUEST_COUNT) {
                        reRequestCount++
                        if (!reRequestLogged) {
                            reRequestLogged = true
                            Log.i(TAG, "📡 Re-requesting location updates (attempt $reRequestCount) after ${gap / 1000}s gap")
                        }
                        reRequestLocationUpdates()
                    }

                    // Fallback to BALANCED_POWER after 60s (covers tunnels/buildings)
                    if (gap > STALENESS_FALLBACK_MS && !fallbackTriggered) {
                        fallbackTriggered = true
                        currentPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
                        Log.i(TAG, "📡 Falling back to BALANCED_POWER_ACCURACY after ${gap / 1000}s without fix")
                        reRequestLocationUpdates()
                    }
                }

                watchdogHandler.postDelayed(this, FIX_GAP_WARN_MS)
            }
        }
        watchdogHandler.postDelayed(check, FIX_GAP_WARN_MS)
    }

    // ── Location request with fallback support ─────────────────────────────

    private fun requestLocationUpdates(looper: Looper) {
        val request = buildLocationRequest()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, looper)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Location permission not granted: ${e.message}")
        }
    }

    private fun reRequestLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val request = buildLocationRequest()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, locationThread.looper)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Re-request failed: ${e.message}")
        }
    }

    private fun buildLocationRequest(): LocationRequest {
        return LocationRequest.Builder(currentPriority, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun buildNotifText(): String {
        return if (notifStopName.isNotEmpty()) {
            "→ $notifStopName  |  ↑${notifPickups} pick-up  ↓${notifDropoffs} drop-off"
        } else {
            "Acquiring location…"
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CaVgo — Location Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(com.gocavgo.ikuriye.R.drawable.ic_location_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CaVgo Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous location tracking"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_TRIP) {
            notifStopName = intent.getStringExtra(EXTRA_STOP_NAME) ?: notifStopName
            notifPickups = intent.getIntExtra(EXTRA_PICKUP_COUNT, notifPickups)
            notifDropoffs = intent.getIntExtra(EXTRA_DROPOFF_COUNT, notifDropoffs)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(buildNotifText()))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (::watchdogHandler.isInitialized) watchdogHandler.removeCallbacksAndMessages(null)
        if (::locationThread.isInitialized) locationThread.quitSafely()
        Log.i(TAG, "LocationService stopped")
    }
}
