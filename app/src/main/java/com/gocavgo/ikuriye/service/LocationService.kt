package com.gocavgo.ikuriye.service

import android.Manifest
// Using custom app drawable instead of android.R.drawable.ic_menu_mylocation
// which is an internal resource that may not exist on all device ROMs
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

        /** Send this intent to the service to update the notification with trip info. */
        const val ACTION_UPDATE_TRIP    = "com.gocavgo.drivers.UPDATE_TRIP"
        const val EXTRA_STOP_NAME       = "extra_stop_name"
        const val EXTRA_PICKUP_COUNT    = "extra_pickup_count"
        const val EXTRA_DROPOFF_COUNT   = "extra_dropoff_count"

        private const val UPDATE_INTERVAL_MS   = 5_000L
        private const val FASTEST_INTERVAL_MS  = 3_000L
        private const val MIN_DISPLACEMENT_M   = 5f

        /** Call from Activity/BootReceiver to request battery-optimisation exemption. */
        @SuppressLint("SuspiciousIndentation")
        fun requestBatteryOptimisationExemption(context: Context) {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
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

    // ── GPS watchdog ──────────────────────────────────────────────────────────
    // onLocationAvailability is unreliable on many devices — FLP fires false then
    // true on the same poll cycle, making any callback-based guard useless.
    // Solution: ignore the availability callback for logging. Instead track
    // whether real fixes are arriving. A Handler post checks every 15 s;
    // if no fix arrived in that window we log once. Next real fix logs recovery.
    @Volatile private var lastFixTimestamp = 0L
    @Volatile private var fixGapLogged     = false
    private val FIX_GAP_WARN_MS            = 15_000L

    // Trip data for the notification — written via ACTION_UPDATE_TRIP intent
    @Volatile private var notifStopName = ""
    @Volatile private var notifPickups  = 0
    @Volatile private var notifDropoffs = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Start foreground BEFORE doing anything else (required on API 26+)
        startForegroundWithType()

        // Dedicated background thread for location callbacks —
        // avoids main-thread throttling when screen is off on OEM ROMs.
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
            val hasFine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                Log.e(TAG, "Location permissions not granted — cannot start FGS with TYPE_LOCATION")
                stopSelf()
                return
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            @SuppressLint("DefaultLocale")
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocation = location

                // Watchdog: stamp arrival time; log recovery if gap was previously flagged
                val now = System.currentTimeMillis()
                if (fixGapLogged) {
                    fixGapLogged = false
                    Log.i(TAG, "📡 GPS fix resumed after ${(now - lastFixTimestamp) / 1000}s gap")
                }
                lastFixTimestamp = now
                val lat      = location.latitude
                val lng      = location.longitude
                val accuracy = location.accuracy
                val speed    = location.speed * 3.6f // m/s → km/h

                Log.d(TAG, "📍 LAT=$lat | LNG=$lng | ACC=${accuracy}m | SPD=${String.format(java.util.Locale.US, "%.1f", speed)} km/h")

                sendBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_LAT,      lat)
                    putExtra(EXTRA_LNG,      lng)
                    putExtra(EXTRA_ACCURACY, accuracy)
                    putExtra(EXTRA_SPEED,    speed)
                })

                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(buildNotifText())
                )
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Intentionally ignored for logging — FLP fires false+true on the
                // same poll cycle on many devices making this callback unreliable.
                // GPS health is tracked by the watchdog instead (see startWatchdog).
            }
        }
    }

    /** Polls every [FIX_GAP_WARN_MS] ms; logs once if no fix has arrived. */
    private fun startWatchdog() {
        watchdogHandler = Handler(locationThread.looper)
        val check = object : Runnable {
            override fun run() {
                val gap = System.currentTimeMillis() - lastFixTimestamp
                if (lastFixTimestamp > 0 && gap > FIX_GAP_WARN_MS && !fixGapLogged) {
                    fixGapLogged = true
                    Log.i(TAG, "📡 No GPS fix for ${gap / 1000}s — still tracking")
                }
                watchdogHandler.postDelayed(this, FIX_GAP_WARN_MS)
            }
        }
        watchdogHandler.postDelayed(check, FIX_GAP_WARN_MS)
    }

    private fun requestLocationUpdates(looper: Looper) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, looper)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Location permission not granted: ${e.message}")
        }
    }

    /** Human-readable notification body — shown on lockscreen and shade. */
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
            .setContentTitle("CaVgo Driver — Active Trip")
            .setContentText(contentText)
            // BigTextStyle makes it readable on lockscreen without truncation
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
                description = "Continuous location tracking for active delivery trip"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_TRIP) {
            notifStopName  = intent.getStringExtra(EXTRA_STOP_NAME)    ?: notifStopName
            notifPickups   = intent.getIntExtra(EXTRA_PICKUP_COUNT,  notifPickups)
            notifDropoffs  = intent.getIntExtra(EXTRA_DROPOFF_COUNT, notifDropoffs)
            // Refresh notification immediately so lockscreen/shade shows new data
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