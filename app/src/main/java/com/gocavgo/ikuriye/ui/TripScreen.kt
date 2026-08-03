package com.gocavgo.ikuriye.ui

import android.annotation.SuppressLint
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.PhoneValidation
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.CompletedTrip
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import com.gocavgo.ikuriye.viewmodel.DriverVehicle
import com.gocavgo.ikuriye.viewmodel.DriverLocation
import com.gocavgo.ikuriye.viewmodel.TripUiState
import com.gocavgo.ikuriye.viewmodel.TripViewModel
import com.gocavgo.ikuriye.data.Package
import com.gocavgo.ikuriye.ui.common.ProfileQuickMenu
import com.gocavgo.ikuriye.ui.common.SettingsMenu
import com.gocavgo.ikuriye.ui.common.adaptiveHorizontalPadding
import com.gocavgo.ikuriye.ui.common.isLandscape
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.driver.DriverHomeScreen as DriverHomeScreenImpl
import com.gocavgo.ikuriye.ui.driver.CompletedTripsHistorySection

// ══════════════════════════════════════════════════════════════════════════════
// MAIN TRIP SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun TripScreen(
    viewModel: TripViewModel,
    profile: DriverProfile = DriverProfile(),
    vehicle: DriverVehicle = DriverVehicle(),
    vehiclePlate: String = "",
    isProfileMenuOpen: Boolean = false,
    isVehicleMenuOpen: Boolean = false,
    isSettingsOpen: Boolean = false,
    isCompletedTripsOpen: Boolean = false,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    isPipEnabled: Boolean = false,
    completedTrips: List<CompletedTrip> = emptyList(),
    onProfileClick: () -> Unit = {},
    onVehicleClick: () -> Unit = {},
    onEditProfileClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onDismissMenus: () -> Unit = {},
    onCompletedTripsClick: () -> Unit = {},
    onThemeModeChange: (AppThemeMode) -> Unit = {},
    onPipEnabledChange: (Boolean) -> Unit = {},
    onDefaultPageChange: (String) -> Unit = {},
    onKeepScreenAwakeChange: (Boolean) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalDriversColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (state.tripCompleted) {
            TripCompletedScreen()
            return@Box
        }

        TripContent(viewModel, vehiclePlate, onVehicleClick, onProfileClick)

        if (isVehicleMenuOpen || isProfileMenuOpen || isSettingsOpen) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismissMenus
                    )
            )
        }

        if (isVehicleMenuOpen) {
            VehicleDetailsMenu(
                vehicle = vehicle,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 58.dp)
            )
        }

        if (isProfileMenuOpen) {
            ProfileQuickMenu(
                profile = profile,
                onProfileClick = onEditProfileClick,
                onSettingsClick = onSettingsClick,
                onLogoutClick = onLogoutClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 58.dp)
            )
        }

        if (isSettingsOpen) {
            SettingsMenu(
                themeMode = themeMode,
                isPipEnabled = isPipEnabled,
                onThemeModeChange = onThemeModeChange,
                onPipEnabledChange = onPipEnabledChange,
                onDefaultPageChange = onDefaultPageChange,
                onKeepScreenAwakeChange = onKeepScreenAwakeChange,
                onClose = onCloseSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 16.dp, top = 58.dp)
            )
        }
    }
}

// ── Reusable trip content (no menus, no overlays) ────────────────────────────

@Composable
fun TripContent(
    viewModel: TripViewModel,
    vehiclePlate: String,
    onVehicleClick: () -> Unit,
    onProfileClick: () -> Unit,
    completedTrips: List<CompletedTrip> = emptyList()
) {
    val state by viewModel.state.collectAsState()
    val colors = LocalDriversColors.current
    val wide = isWideScreen()
    val landscape = isLandscape()
    val maxW = contentMaxWidth()
    val isCompleted = state.tripCompleted

    val density = LocalDensity.current
    val statusBarHeightDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val gradientHeightDp = (statusBarHeightDp + statusBarHeightDp / 2f).coerceAtLeast(1.dp)
    val solidStopFraction = (statusBarHeightDp / gradientHeightDp).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ── Scrollable content ──
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Top spacer so content starts behind status bar
            Spacer(Modifier.height(gradientHeightDp + 8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW).align(Alignment.CenterHorizontally) else Modifier)
            ) {
                // ── Completed banner ──
                if (isCompleted) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.green.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, colors.green.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Trip Completed", color = colors.green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("All packages delivered successfully", color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Top bar (compact on landscape) ──
                TripTopBar(state, vehiclePlate, onProfileClick, onVehicleClick, landscape = landscape)

                Spacer(Modifier.height(12.dp))

                // ── MAIN CONTENT ──
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1.6f)) {
                            UpcomingStopDashboard(viewModel, wide = true)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RouteProgressStrip(state)
                            Spacer(Modifier.height(12.dp))
                            TripActionButton(state, viewModel)
                        }
                    }
                } else {
                    UpcomingStopDashboard(viewModel, wide = false)
                    Spacer(Modifier.height(12.dp))
                    RouteProgressStrip(state)
                    Spacer(Modifier.height(16.dp))
                    TripActionButton(state, viewModel)
                }

                // ── Completed trips history (scrollable above the dock) ──
                if (completedTrips.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        CompletedTripsHistorySection(trips = completedTrips)
                    }
                }

                // Extra bottom spacing so content scrolls above the floating dock
                Spacer(Modifier.height(120.dp))
            }
        }

        // ── Status bar gradient overlay ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gradientHeightDp)
                .background(
                    Brush.verticalGradient(
                        0.0f to colors.background,
                        solidStopFraction to colors.background,
                        1.0f to colors.background.copy(alpha = 0f)
                    )
                )
        )
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────
@Composable
fun TripTopBar(
    state: TripUiState,
    vehiclePlate: String,
    onProfileClick: () -> Unit,
    onVehicleClick: () -> Unit,
    landscape: Boolean = false
) {
    val colors = LocalDriversColors.current

    // No Surface wrapper — background flows continuously through the gradient
    if (landscape) {
        // ── Compact horizontal layout for landscape ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.blue),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.LocalShipping, null, tint = Color.White,
                    modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(state.trip.routeLabel, color = colors.textPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            // Vehicle plate pill
            Surface(
                onClick = onVehicleClick,
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.DirectionsCar, null, tint = colors.blue, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        vehiclePlate.ifBlank { "No vehicle" },
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        // ── Vertical layout for portrait phones / tablets (no background bar) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            // Centered vehicle plate pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    onClick = onVehicleClick,
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.divider)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.DirectionsCar, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            vehiclePlate.ifBlank { "No vehicle" },
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(colors.blue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.LocalShipping, null, tint = Color.White,
                        modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("CaVgo Driver", color = colors.textSecondary, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text(state.trip.routeLabel, color = colors.textPrimary,
                        fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun VehicleDetailsMenu(
    vehicle: DriverVehicle,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current

    Surface(
        modifier = modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            ),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.divider)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.blue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.DirectionsCar, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(vehicle.plateNumber, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Assigned vehicle", color = colors.textSecondary, fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 12.dp))
            MenuInfoRow("Model", vehicle.model, Icons.Filled.DirectionsCar)
            Spacer(Modifier.height(10.dp))
            MenuInfoRow("Seat size", "${vehicle.seats} seats", Icons.Filled.EventSeat)
        }
    }
}
@Composable
private fun MenuInfoRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val colors = LocalDriversColors.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Column {
            Text(label, color = colors.textSecondary, fontSize = 10.sp)
            Text(value, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Location Bar ──────────────────────────────────────────────────────────────
@SuppressLint("DefaultLocale")
@Composable
fun LocationBar(loc: DriverLocation) {
    val colors = LocalDriversColors.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "alpha"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp), color = colors.surfaceAlt
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.green.copy(alpha = pulse)))
            Spacer(Modifier.width(8.dp))
            if (loc.lat == 0.0 && loc.lng == 0.0) {
                Text("Acquiring GPS…", color = colors.textSecondary, fontSize = 11.sp)
            } else {
                Text("%.5f, %.5f".format(loc.lat, loc.lng),
                    color = colors.green, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("±${loc.accuracy.toInt()}m  ${String.format(java.util.Locale.US, "%.0f", loc.speedKmh)} km/h",
                    color = colors.textSecondary, fontSize = 10.sp)
            }
        }
    }
}

// ── UPCOMING STOP DASHBOARD ───────────────────────────────────────────────────
@Composable
fun UpcomingStopDashboard(viewModel: TripViewModel, wide: Boolean = false) {
    val colors = LocalDriversColors.current
    val stop = viewModel.nextStop ?: viewModel.currentStop
    val isNext = viewModel.nextStop != null
    val accent = if (isNext) colors.amber else colors.blue
    val padH = adaptiveHorizontalPadding()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = padH),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, accent.copy(0.35f))
    ) {
        Column(modifier = Modifier.padding(if (wide) 20.dp else 16.dp)) {

            // ── Stop label + name ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(if (wide) 36.dp else 32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isNext) Icons.AutoMirrored.Filled.ArrowForward else Icons.Filled.Navigation,
                        null,
                        tint = accent,
                        modifier = Modifier.size(if (wide) 18.dp else 16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (isNext) "UPCOMING STOP" else "CURRENT STOP",
                        color = accent,
                        fontSize = if (wide) 11.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        stop.name,
                        color = colors.textPrimary,
                        fontSize = if (wide) 22.sp else 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(stop.address, color = colors.textSecondary,
                fontSize = if (wide) 13.sp else 12.sp,
                modifier = Modifier.padding(start = if (wide) 46.dp else 42.dp))

            Spacer(Modifier.height(if (wide) 20.dp else 16.dp))
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Spacer(Modifier.height(if (wide) 18.dp else 14.dp))

            // ── Pickup / Dropoff side by side ─────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp)
            ) {
                // Pickups column
                PackageColumn(
                    modifier = Modifier.weight(1f),
                    title = "PICK UP",
                    count = stop.pickups.size,
                    color = colors.green,
                    packages = stop.pickups,
                    icon = Icons.Filled.AddCircle,
                    wide = wide
                )

                // Vertical divider
                Box(
                    Modifier.width(1.dp).heightIn(min = 60.dp).background(colors.divider)
                        .align(Alignment.CenterVertically)
                )

                // Dropoffs column
                PackageColumn(
                    modifier = Modifier.weight(1f),
                    title = "DROP OFF",
                    count = stop.dropoffs.size,
                    color = colors.red,
                    packages = stop.dropoffs,
                    icon = Icons.Filled.RemoveCircle,
                    wide = wide
                )
            }
        }
    }
}

@Composable
fun PackageColumn(
    modifier: Modifier,
    title: String,
    count: Int,
    color: Color,
    packages: List<Package>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    wide: Boolean = false
) {
    val colors = LocalDriversColors.current

    Column(modifier = modifier) {
        // Header with count badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(if (wide) 16.dp else 14.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, color = color, fontSize = if (wide) 11.sp else 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            // Count badge
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f))
                    .padding(horizontal = if (wide) 9.dp else 7.dp, vertical = if (wide) 3.dp else 2.dp)
            ) {
                Text(
                    count.toString(),
                    color = color,
                    fontSize = if (wide) 12.sp else 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(if (wide) 10.dp else 8.dp))

        if (packages.isEmpty()) {
            Text("None", color = colors.textSecondary, fontSize = if (wide) 12.sp else 11.sp)
        } else {
            packages.forEach { pkg ->
                PackagePill(pkg, color, wide = wide)
                Spacer(Modifier.height(if (wide) 6.dp else 5.dp))
            }
        }
    }
}

@Composable
fun PackagePill(pkg: Package, color: Color, wide: Boolean = false) {
    val colors = LocalDriversColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (wide) 10.dp else 8.dp))
            .background(color.copy(alpha = 0.07f))
            .padding(horizontal = if (wide) 12.dp else 8.dp, vertical = if (wide) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pkg.label,
                color = colors.textPrimary,
                fontSize = if (wide) 12.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pkg.recipient,
                color = colors.textSecondary,
                fontSize = if (wide) 11.sp else 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(pkg.weight, color = color,
            fontSize = if (wide) 11.sp else 10.sp,
            fontWeight = FontWeight.Bold)
    }
}

// ── Route Progress Strip ──────────────────────────────────────────────────────
@Composable
fun RouteProgressStrip(state: TripUiState) {
    val colors = LocalDriversColors.current
    val stops = state.trip.stops
    val cur   = state.currentStopIndex
    val wide = isWideScreen()
    val padH = adaptiveHorizontalPadding()

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = padH),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(Modifier.padding(if (wide) 16.dp else 14.dp)) {
            Text("ROUTE", color = colors.textSecondary,
                fontSize = if (wide) 11.sp else 10.sp,
                letterSpacing = 1.sp)
            Spacer(Modifier.height(if (wide) 12.dp else 10.dp))

            if (wide) {
                // ── Wide layout: larger dots with more spacing ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    stops.forEachIndexed { idx, stop ->
                        val isDone    = idx < cur
                        val isCurrent = idx == cur
                        val dotColor  = when {
                            isDone    -> colors.green
                            isCurrent -> colors.blue
                            else      -> colors.divider
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isCurrent) 32.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = if (isCurrent) 0.2f else 0.1f))
                                    .border(if (isCurrent) 2.dp else 1.dp, dotColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDone) {
                                    Icon(Icons.Filled.Check, null, tint = colors.green,
                                        modifier = Modifier.size(14.dp))
                                } else {
                                    Text("${idx + 1}", color = dotColor, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stop.name,
                                color = if (isCurrent) colors.textPrimary else colors.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (idx < stops.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .weight(0.4f)
                                    .height(2.dp)
                                    .background(if (idx < cur) colors.green else colors.divider)
                            )
                        }
                    }
                }
            } else {
                // ── Compact layout for phones — evenly distributed stops ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    stops.forEachIndexed { idx, stop ->
                        val isDone    = idx < cur
                        val isCurrent = idx == cur
                        val dotColor  = when {
                            isDone    -> colors.green
                            isCurrent -> colors.blue
                            else      -> colors.divider
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isCurrent) 28.dp else 22.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = if (isCurrent) 0.2f else 0.1f))
                                    .border(if (isCurrent) 2.dp else 1.dp, dotColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDone) {
                                    Icon(Icons.Filled.Check, null, tint = colors.green,
                                        modifier = Modifier.size(12.dp))
                                } else {
                                    Text("${idx + 1}", color = dotColor, fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                stop.name,
                                color = if (isCurrent) colors.textPrimary else colors.textSecondary,
                                fontSize = 8.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (idx < stops.lastIndex) {
                            // Connector line — fills remaining space between stops
                            Box(
                                modifier = Modifier
                                    .weight(0.15f)
                                    .height(2.dp)
                                    .background(if (idx < cur) colors.green else colors.divider)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Action Button ─────────────────────────────────────────────────────────────
@Composable
fun TripActionButton(state: TripUiState, viewModel: TripViewModel) {
    val colors = LocalDriversColors.current
    val isLast = state.currentStopIndex == state.trip.stops.lastIndex

    if (state.tripCompleted) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = colors.green.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, colors.green.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trip Completed", color = colors.green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (!state.arrivedAtStop) {
            Button(
                onClick = { viewModel.arriveAtCurrentStop() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
            ) {
                Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Arrived at ${viewModel.currentStop.name}", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { viewModel.departCurrentStop() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLast) colors.green else colors.amber)
            ) {
                Icon(
                    if (isLast) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.ArrowForward,
                    null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isLast) "Complete Trip" else "Depart → ${viewModel.nextStop?.name}",
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Trip Completed ────────────────────────────────────────────────────────────
@Composable
fun TripCompletedScreen() {
    val colors = LocalDriversColors.current

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("Trip Completed!", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("All packages delivered.", color = colors.textSecondary, fontSize = 14.sp)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// PIP VIEW — compact version of the upcoming stop dashboard
// Stop name centred top, pickups left, dropoffs right
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun PipTripView(viewModel: TripViewModel) {
    val colors = LocalDriversColors.current
    val stop   = viewModel.nextStop ?: viewModel.currentStop
    val isNext = viewModel.nextStop != null
    val color  = if (isNext) colors.amber else colors.blue

    // Guard: if stop name is blank, the trip data hasn't loaded yet
    val hasTripData = stop.name.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            if (!hasTripData) {
                // ── Fallback: no trip data available ──────────────────────
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.LocalShipping, null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "No active trip",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // ── Stop name centred at top ──────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (isNext) "UPCOMING" else "CURRENT",
                        color = color,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        stop.name,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(stop.address, color = colors.textSecondary, fontSize = 8.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Spacer(Modifier.height(6.dp))

                // ── Pickups & dropoffs side by side ───────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PipPackageList(
                        modifier = Modifier.weight(1f),
                        title = "PICK UP",
                        packages = stop.pickups,
                        color = colors.green
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
                    PipPackageList(
                        modifier = Modifier.weight(1f),
                        title = "DROP OFF",
                        packages = stop.dropoffs,
                        color = colors.red
                    )
                }
            }
        }
    }
}

@Composable
fun PipPackageList(
    modifier: Modifier,
    title: String,
    packages: List<Package>,
    color: Color
) {
    val colors = LocalDriversColors.current

    Column(modifier = modifier) {
        // Header row with count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = color, fontSize = 7.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape).background(color.copy(0.15f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(packages.size.toString(), color = color,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (packages.isEmpty()) {
            Text("None", color = colors.textSecondary, fontSize = 8.sp)
        } else {
            packages.forEach { pkg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color.copy(0.07f))
                        .padding(horizontal = 5.dp, vertical = 3.dp)
                ) {
                    Text(
                        pkg.label,
                        color = colors.textPrimary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(pkg.weight, color = color, fontSize = 7.sp)
                }
                    Spacer(Modifier.height(2.dp))
            }
        }
    }
}

// ── Driver Home Screen forward ───────────────────────────────────────────────────
// Implementation in ui/driver/DriverHomeScreen.kt; forward keeps old import path working.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverHomeScreen(
    viewModel: TripViewModel,
    profile: DriverProfile,
    vehicle: DriverVehicle,
    driverHomeTab: Int,
    isDriverProfileMenuOpen: Boolean,
    isDriverSettingsOpen: Boolean,
    themeMode: AppThemeMode,
    isPipEnabled: Boolean = false,
    completedTrips: List<CompletedTrip> = emptyList(),
    isVehicleMenuOpen: Boolean = false,
    isProfileMenuOpen: Boolean = false,
    isSettingsOpen: Boolean = false,
    defaultPage: String = "trips",
    keepScreenAwake: Boolean = false,
    onCreatePackage: () -> Unit,
    onTabChange: (Int) -> Unit,
    onProfileMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onCloseSettings: () -> Unit,
    onLogout: () -> Unit,
    onDismissMenus: () -> Unit,
    onProfileClick: () -> Unit,
    onVehicleClick: () -> Unit = {},
    onEditProfileClick: () -> Unit = {},
    onCompletedTripsClick: () -> Unit = {},
    onPipEnabledChange: (Boolean) -> Unit = {},
    onDefaultPageChange: (String) -> Unit = {},
    onKeepScreenAwakeChange: (Boolean) -> Unit = {},
    onNoticesClick: () -> Unit = {},
    noticeCount: Int = 0
) = DriverHomeScreenImpl(
    viewModel = viewModel, profile = profile, vehicle = vehicle,
    driverHomeTab = driverHomeTab, isDriverProfileMenuOpen = isDriverProfileMenuOpen,
    isDriverSettingsOpen = isDriverSettingsOpen, themeMode = themeMode,
    isPipEnabled = isPipEnabled, completedTrips = completedTrips,
    isVehicleMenuOpen = isVehicleMenuOpen, isProfileMenuOpen = isProfileMenuOpen,
    isSettingsOpen = isSettingsOpen, defaultPage = defaultPage,
    keepScreenAwake = keepScreenAwake, onCreatePackage = onCreatePackage,
    onTabChange = onTabChange, onProfileMenuClick = onProfileMenuClick,
    onSettingsClick = onSettingsClick, onThemeModeChange = onThemeModeChange,
    onCloseSettings = onCloseSettings, onLogout = onLogout,
    onDismissMenus = onDismissMenus, onProfileClick = onProfileClick,
    onVehicleClick = onVehicleClick, onEditProfileClick = onEditProfileClick,
    onPipEnabledChange = onPipEnabledChange, onDefaultPageChange = onDefaultPageChange,
    onKeepScreenAwakeChange = onKeepScreenAwakeChange,
    onNoticesClick = onNoticesClick, noticeCount = noticeCount
)

// ── End of TripScreen.kt ───────────────────────────────────────────────────────────
// DriverHomeScreen impl → ui/driver/DriverHomeScreen.kt
// Driver packages + dialogs → ui/driver/DriverPackagesTab.kt + DeliveryDialogs.kt
// Package detail sheet → ui/driver/PackageDetailSheet.kt
// Completed trips history → ui/driver/CompletedTripsHistory.kt

