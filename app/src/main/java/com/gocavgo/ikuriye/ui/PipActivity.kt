package com.gocavgo.ikuriye.ui

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.service.LocationService
import com.gocavgo.ikuriye.ui.theme.IkuriyeTheme
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors

/**
 * Lightweight activity designed to be launched in PiP mode.
 * Receives the current stop data via Intent extras and live location via broadcast.
 */
class PipActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CURRENT_STOP     = "pip_current_stop"
        const val EXTRA_NEXT_STOP        = "pip_next_stop"
        const val EXTRA_PICKUP_SUMMARY   = "pip_pickup_summary"
        const val EXTRA_DROPOFF_SUMMARY  = "pip_dropoff_summary"
        const val EXTRA_NEXT_PICKUP      = "pip_next_pickup"
        const val EXTRA_NEXT_DROPOFF     = "pip_next_dropoff"
    }

    private var locationReceiver: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )

        val currentStop   = intent.getStringExtra(EXTRA_CURRENT_STOP)   ?: "—"
        val nextStop      = intent.getStringExtra(EXTRA_NEXT_STOP)       ?: "Final stop"
        val pickupSummary = intent.getStringExtra(EXTRA_PICKUP_SUMMARY)  ?: "None"
        val dropoffSummary= intent.getStringExtra(EXTRA_DROPOFF_SUMMARY) ?: "None"
        val nextPickup    = intent.getStringExtra(EXTRA_NEXT_PICKUP)     ?: "None"
        val nextDropoff   = intent.getStringExtra(EXTRA_NEXT_DROPOFF)    ?: "None"

        setContent {
            IkuriyeTheme {
                var lat by remember { mutableDoubleStateOf(0.0) }
                var lng by remember { mutableDoubleStateOf(0.0) }
                var speed by remember { mutableFloatStateOf(0f) }

                // Register broadcast receiver for live location updates in PiP
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            lat   = intent?.getDoubleExtra(LocationService.EXTRA_LAT, 0.0) ?: 0.0
                            lng   = intent?.getDoubleExtra(LocationService.EXTRA_LNG, 0.0) ?: 0.0
                            speed = intent?.getFloatExtra(LocationService.EXTRA_SPEED, 0f) ?: 0f
                        }
                    }
                    locationReceiver = receiver
                    val filter = IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(receiver, filter)
                    }
                    onDispose {
                        try { unregisterReceiver(receiver) } catch (_: Exception) {}
                    }
                }

                PipContent(
                    currentStop = currentStop,
                    nextStop = nextStop,
                    pickupSummary = pickupSummary,
                    dropoffSummary = dropoffSummary,
                    nextPickup = nextPickup,
                    nextDropoff = nextDropoff,
                    lat = lat,
                    lng = lng,
                    speed = speed
                )
            }
        }

        // Auto-enter PiP immediately when activity starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPipModeCompat()
            } catch (e: Exception) {
                // Some OEM/ROM builds throw here (IllegalStateException) — never crash
                // the activity over PiP, just fall back to normal rendering.
                Log.e("PipActivity", "Failed to enter PiP mode", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipModeCompat() {
        val rect = Rect()
        window.decorView.getGlobalVisibleRect(rect)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 2))
            .setSourceRectHint(rect)
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto PiP on home button press
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipModeCompat()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
    }
}

// ── PiP UI ────────────────────────────────────────────────────────────────────

@Composable
fun PipContent(
    currentStop: String,
    nextStop: String,
    pickupSummary: String,
    dropoffSummary: String,
    nextPickup: String,
    nextDropoff: String,
    lat: Double,
    lng: Double,
    speed: Float
) {
    val colors = LocalDriversColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Upcoming stop name - centered at top
        Text(
            text = "Upcoming Stop",
            color = colors.textSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = nextStop,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Package counts - pickups and dropoffs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Pickups
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Pickups",
                    color = colors.green,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = nextPickup,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "packages",
                    color = colors.textSecondary,
                    fontSize = 7.sp
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Dropoffs
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Drop-offs",
                    color = colors.red,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = nextDropoff,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "packages",
                    color = colors.textSecondary,
                    fontSize = 7.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Speed indicator at bottom
        Text(
            text = "%.0f km/h".format(speed),
            color = colors.blue,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PipBadge(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val colors = LocalDriversColors.current

    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(4.dp)
    ) {
        Text(label, color = color, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        Text(value, color = colors.textPrimary, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 10.sp)
    }
}
