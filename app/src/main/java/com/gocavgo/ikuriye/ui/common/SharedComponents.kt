package com.gocavgo.ikuriye.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.data.AvatarCache
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import kotlinx.coroutines.delay

/**
 * Formats an ISO-8601 timestamp to a human-readable relative string.
 * e.g. "3h ago", "2d ago", "Jan 05"
 */
fun formatTime(iso: String): String {
    if (iso.isBlank() || iso == "Just now") return iso
    return try {
        val instant = java.time.Instant.parse(iso)
        val now     = java.time.Instant.now()
        val dur     = java.time.Duration.between(instant, now)
        when {
            dur.isNegative       -> "Just now"
            dur.toMinutes() < 1  -> "Just now"
            dur.toMinutes() < 60 -> "${dur.toMinutes()}m ago"
            dur.toHours()   < 24 -> "${dur.toHours()}h ago"
            dur.toDays()    < 7  -> "${dur.toDays()}d ago"
            else -> {
                val local = instant.atZone(java.time.ZoneId.systemDefault())
                java.time.format.DateTimeFormatter.ofPattern("MMM dd").format(local)
            }
        }
    } catch (_: Exception) { iso }
}

/**
 * Displays the user's profile picture from the local cache if available,
 * falling back to the remote [remoteUrl]. The avatar is cached on login and
 * profile update, and cleared on logout — so it never re-downloads unless
 * the user explicitly changes their picture or logs in again.
 */
@Composable
fun CachedAvatarImage(
    remoteUrl: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val localUri = remember(remoteUrl) { AvatarCache.getLocalUri(context) }
    val model = localUri?.toString() ?: remoteUrl
    if (!model.isNullOrBlank()) {
        AsyncImage(model = model, contentDescription = null, modifier = modifier, contentScale = contentScale)
    }
}

/**
 * Shows a generated pickup code with a 2-minute countdown timer.
 * Auto-dismisses when the timer expires.
 */
@Composable
fun PickupCodeDialog(
    pickupCode: String,
    expiryMs: Long,
    onDismiss: () -> Unit
) {
    val colors = LocalDriversColors.current

    // Countdown timer — updates every second
    var remainingSecs by remember { mutableIntStateOf(0) }
    LaunchedEffect(expiryMs) {
        while (true) {
            val now = System.currentTimeMillis()
            val remaining = ((expiryMs - now) / 1000).toInt()
            if (remaining <= 0) {
                onDismiss()
                break
            }
            remainingSecs = remaining
            delay(1000L)
        }
    }

    val progress = (remainingSecs.toFloat() / 120f).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Lock, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Pickup Code", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Show this code to the driver when they arrive for delivery.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))

                // Large pickup code display
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.blue.copy(alpha = 0.06f),
                    border = BorderStroke(1.5.dp, colors.blue.copy(alpha = 0.3f))
                ) {
                    Text(
                        pickupCode,
                        modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = colors.blue,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Countdown progress bar
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (remainingSecs > 30) colors.blue else colors.red,
                    trackColor = colors.divider
                )

                Spacer(Modifier.height(8.dp))

                // Time remaining display
                val minutes = remainingSecs / 60
                val seconds = remainingSecs % 60
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (remainingSecs > 30) colors.surfaceAlt else colors.red.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Timer,
                            null,
                            tint = if (remainingSecs > 30) colors.textSecondary else colors.red,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${minutes}:${seconds.toString().padStart(2, '0')}",
                            color = if (remainingSecs > 30) colors.textSecondary else colors.red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "remaining",
                            color = if (remainingSecs > 30) colors.textSecondary else colors.red,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "The code expires after 2 minutes. You can generate a new one after it expires.",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                modifier = Modifier.height(42.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Auto-shown popup for the sender/receiver when the driver initiates delivery.
 * Displays the one-time delivery code and lets the user confirm delivery with it
 * (or dismiss to confirm manually later).
 */
@Composable
fun DeliveryConfirmationDialog(
    deliveryCode: String,
    trackingCode: String = "",
    recipientName: String = "",
    isConfirming: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalDriversColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.green.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Verified, null, tint = colors.green, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Delivery Confirmation", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (trackingCode.isNotBlank()) {
                    Text(trackingCode, color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "The driver has arrived with your package. Share this code with them or confirm delivery here.",
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.green.copy(alpha = 0.06f),
                    border = BorderStroke(1.5.dp, colors.green.copy(alpha = 0.3f))
                ) {
                    Text(
                        deliveryCode,
                        modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = colors.green,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (recipientName.isNotBlank()) {
                    Text(
                        "Confirm that ${recipientName} has received the package.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.green),
                modifier = Modifier.height(40.dp),
                enabled = !isConfirming
            ) {
                if (isConfirming) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Confirm Delivery", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isConfirming) {
                Text("Not Now", color = colors.textSecondary)
            }
        }
    )
}

/**
 * Responsive auth/scaffold layout that works in portrait and landscape.
 *
 * On compact (portrait phone) widths the branding is stacked above the
 * [content] in a single centered column. On wide (landscape / tablet) widths
 * it switches to a two-panel split: branding on the left, scrollable
 * [content] on the right — so forms stay usable with the keyboard open.
 */
@Composable
fun AuthSplitLayout(
    accent: Color,
    logo: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalDriversColors.current
    val wide = isWideScreen()
    val maxW = contentMaxWidth()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val maxH = maxHeight

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // ── Branding panel (left) ────────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(accent.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(adaptiveHorizontalPadding())
                    ) {
                        Box(
                            modifier = Modifier.size(88.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(logo, null, tint = accent, modifier = Modifier.size(44.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(title, color = colors.textPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(subtitle, color = colors.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
                // ── Form panel (right) ───────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(adaptiveHorizontalPadding()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = maxH)
                            .widthIn(max = 460.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Spacer(Modifier.height(24.dp))
                        content()
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = adaptiveHorizontalPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxH)
                        .then(if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW) else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(logo, null, tint = accent, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(title, color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    content()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceAlt)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPrimary)
            }
        }
    }
}
