package com.gocavgo.ikuriye.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Format speed adaptively:
 * - Walking / parking speed (< 5 km/h) -> m/s (e.g., "1.2 m/s")
 * - Driving speed (>= 5 km/h) -> km/h (e.g., "42 km/h")
 */
fun formatLiveSpeed(speedKmh: Float): String {
    return if (speedKmh < 5.0f) {
        val ms = speedKmh / 3.6f
        "%.1f m/s".format(Locale.US, ms)
    } else {
        "%.0f km/h".format(Locale.US, speedKmh)
    }
}

/**
 * Vehicle plate pill with Live Activity movement indicator:
 * - Shows speed badge when moving (`speedKmh >= 0.8 km/h`).
 * - Hides speed badge 1 second after stopping (`speedKmh < 0.8 km/h` for 1000ms).
 * - Displays speed adaptively in m/s (slow movement) or km/h (driving).
 */
@Composable
fun VehiclePlatePill(
    plateNumber: String,
    speedKmh: Float = 0f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    val colors = LocalDriversColors.current
    var isMoving by remember { mutableStateOf(false) }

    LaunchedEffect(speedKmh) {
        if (speedKmh >= 0.8f) {
            isMoving = true
        } else {
            // Wait 1 second before hiding speed badge when stopped
            delay(1000L)
            if (speedKmh < 0.8f) {
                isMoving = false
            }
        }
    }

    val pulse by rememberInfiniteTransition(label = "speedPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(if (isLandscape) 14.dp else 18.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, if (isMoving) colors.green.copy(alpha = 0.5f) else colors.divider)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isLandscape) 10.dp else 14.dp,
                vertical = if (isLandscape) 4.dp else 6.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = if (isMoving) colors.green else colors.blue,
                modifier = Modifier.size(if (isLandscape) 14.dp else 18.dp)
            )
            Spacer(Modifier.width(if (isLandscape) 4.dp else 8.dp))
            Text(
                plateNumber.ifBlank { "No vehicle" },
                color = colors.textPrimary,
                fontSize = if (isLandscape) 11.sp else 13.sp,
                fontWeight = FontWeight.Bold
            )

            AnimatedVisibility(
                visible = isMoving,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(tween(200)),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(tween(200))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.green.copy(alpha = pulse))
                    )
                    Spacer(Modifier.width(5.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.green.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, colors.green.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Speed,
                                contentDescription = null,
                                tint = colors.green,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                formatLiveSpeed(speedKmh),
                                color = colors.green,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
