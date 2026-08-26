package com.gocavgo.ikuriye.ui.driver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.ui.TripContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import com.gocavgo.ikuriye.ui.common.CachedAvatarImage
import com.gocavgo.ikuriye.ui.common.ProfileQuickMenu
import com.gocavgo.ikuriye.ui.common.SettingsMenu
import com.gocavgo.ikuriye.ui.common.adaptiveHorizontalPadding
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.CompletedTrip
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import com.gocavgo.ikuriye.viewmodel.DriverVehicle
import com.gocavgo.ikuriye.viewmodel.TripViewModel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
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
    onPipEnabledChange: (Boolean) -> Unit = {},
    onDefaultPageChange: (String) -> Unit = {},
    onKeepScreenAwakeChange: (Boolean) -> Unit = {},
    onNoticesClick: () -> Unit = {},
    noticeCount: Int = 0
) {
    val state  by viewModel.state.collectAsState()
    val colors = LocalDriversColors.current
    val wide   = isWideScreen()
    val maxW   = contentMaxWidth()
    var hasAppeared by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    // Sync pager → driverHomeTab (for bottom bar)
    LaunchedEffect(pagerState.currentPage) {
        if (driverHomeTab != pagerState.currentPage) {
            onTabChange(pagerState.currentPage)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Sync driverHomeTab → pager (for bottom bar clicks)
    LaunchedEffect(driverHomeTab) {
        if (pagerState.currentPage != driverHomeTab) {
            pagerState.animateScrollToPage(driverHomeTab)
        }
    }
    LaunchedEffect(Unit) { hasAppeared = true }

    // ── Main content composable (shared between compact and wide layouts) ─
    val mainContent: @Composable () -> Unit = {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                0 -> {
                    if (state.hasActiveTrip) {
                        TripContent(
                            viewModel       = viewModel,
                            vehiclePlate    = vehicle.plateNumber,
                            onVehicleClick  = onVehicleClick,
                            onProfileClick  = onProfileClick,
                            completedTrips  = completedTrips
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 48.dp, bottom = 90.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = if (completedTrips.isEmpty()) 0.dp else 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Filled.LocalShipping, null, tint = colors.textSecondary, modifier = Modifier.size(56.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("No active trip", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("Your next assigned route will appear here", color = colors.textSecondary, fontSize = 13.sp)
                                }
                            }
                            if (completedTrips.isNotEmpty()) {
                                item { CompletedTripsHistorySection(trips = completedTrips) }
                            }
                        }
                    }
                }
                else -> DriverPackagesTab(viewModel = viewModel)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {

        if (wide) {
            // ── WIDE SCREEN LAYOUT (landscape/tablet): Side rail + content ──
            Row(modifier = Modifier.fillMaxSize()) {
                // Side navigation rail
                Surface(
                    modifier = Modifier.widthIn(min = 72.dp, max = 88.dp).fillMaxHeight(),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.divider)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top: profile + tabs
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Profile avatar
                            Box(
                                modifier = Modifier.size(42.dp).clip(CircleShape)
                                    .background(if (isDriverProfileMenuOpen) colors.blue.copy(alpha = 0.12f) else colors.surfaceAlt)
                                    .clickable { onProfileMenuClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                val avatarUrl = profile.avatarUrl
                                if (!avatarUrl.isNullOrBlank()) {
                                    CachedAvatarImage(remoteUrl = avatarUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text(profile.name.first().uppercase(),
                                        color = if (isDriverProfileMenuOpen) colors.blue else colors.textSecondary,
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                            Spacer(Modifier.height(24.dp))

                            // Tab buttons (vertical)
                            listOf(0 to Icons.Filled.LocalShipping, 1 to Icons.Filled.Inventory2).forEach { (idx, icon) ->
                                val selected = driverHomeTab == idx
                                Surface(
                                    modifier = Modifier.size(48.dp).padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) colors.blue else colors.surfaceAlt,
                                    onClick = { onTabChange(idx) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(icon, null, tint = if (selected) Color.White else colors.textSecondary, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }

                        // Bottom: Create package FAB (vertical)
                        FloatingActionButton(
                            onClick = onCreatePackage,
                            modifier = Modifier.size(48.dp),
                            containerColor = colors.green,
                            contentColor = Color.White,
                            shape = RoundedCornerShape(14.dp)
                        ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(22.dp)) }
                    }
                }

                // Main content area (centered with max-width constraint)
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    val contentMod = if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW).fillMaxHeight() else Modifier.fillMaxSize()
                    Box(modifier = contentMod) {
                        mainContent()
                    }
                }
            }
        } else {
            // ── COMPACT: existing vertical layout with bottom bar ──
            mainContent()

            // Bottom bar
            AnimatedVisibility(
                visible  = hasAppeared,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter    = slideInVertically(tween(400, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(400))
            ) {
                Row(
                    modifier = Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        shadowElevation = 6.dp,
                        border = BorderStroke(1.dp, colors.divider)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Profile avatar
                            Box(
                                modifier = Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (isDriverProfileMenuOpen) colors.blue.copy(alpha = 0.12f) else colors.surfaceAlt)
                                    .clickable { onProfileMenuClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                val avatarUrl = profile.avatarUrl
                                if (!avatarUrl.isNullOrBlank()) {
                                    CachedAvatarImage(remoteUrl = avatarUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text(
                                        profile.name.first().uppercase(),
                                        color = if (isDriverProfileMenuOpen) colors.blue else colors.textSecondary,
                                        fontWeight = FontWeight.Bold, fontSize = 15.sp
                                    )
                                }
                            }

                            listOf(0 to (Icons.Filled.LocalShipping to "Trips"), 1 to (Icons.Filled.Inventory2 to "Packages")).forEach { (idx, pair) ->
                                val (icon, label) = pair
                                Box(
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                                        .background(animateColorAsState(if (driverHomeTab == idx) colors.blue else Color.Transparent, tween(300), label = "driverTab${idx}Bg").value)
                                        .clickable { onTabChange(idx) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(icon, null, modifier = Modifier.size(15.dp),
                                            tint = animateColorAsState(if (driverHomeTab == idx) Color.White else colors.textSecondary, tween(300), label = "driverTab${idx}Icon").value)
                                        Spacer(Modifier.width(5.dp))
                                        Text(label,
                                            color = animateColorAsState(if (driverHomeTab == idx) Color.White else colors.textSecondary, tween(300), label = "driverTab${idx}Text").value,
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    FloatingActionButton(
                        onClick = onCreatePackage,
                        containerColor = colors.green,
                        contentColor   = MaterialTheme.colorScheme.onPrimary,
                        shape          = CircleShape,
                        elevation      = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp)
                    ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(26.dp)) }
                }
            }
        }

        // ── Bell notification button (floating, visible on all pages) ──────
        SmallFloatingActionButton(
            onClick = onNoticesClick,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 2.dp, end = 16.dp),
            containerColor = colors.surface,
            contentColor = colors.textPrimary,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
        ) {
            BadgedBox(badge = {
                if (noticeCount > 0) {
                    Badge(containerColor = colors.red, contentColor = Color.White) {
                        Text("$noticeCount", fontSize = 9.sp)
                    }
                }
            }) {
                Icon(Icons.Filled.Notifications, null, modifier = Modifier.size(20.dp))
            }
        }

        // ── Shared: overlays, menus, dialogs (same for both layouts) ──────────
        
        // Overlay scrim
        if (isDriverProfileMenuOpen || isDriverSettingsOpen) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismissMenus)
            )
        }

        // Profile dropdown
        AnimatedVisibility(
            visible  = isDriverProfileMenuOpen,
            modifier = Modifier.align(if (wide) Alignment.TopStart else Alignment.BottomStart)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = if (wide) 80.dp else 16.dp, bottom = if (wide) 0.dp else 80.dp, top = if (wide) 80.dp else 0.dp),
            enter    = expandVertically(tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
            exit     = shrinkVertically(tween(200)) + fadeOut(tween(200))
        ) {
            ProfileQuickMenu(
                profile        = profile,
                onSettingsClick = { onDismissMenus(); onSettingsClick() },
                onLogoutClick   = onLogout,
                onProfileClick  = { onDismissMenus(); onProfileClick() }
            )
        }

        // Settings dropdown
        AnimatedVisibility(
            visible  = isDriverSettingsOpen,
            modifier = Modifier.align(if (wide) Alignment.TopStart else Alignment.BottomStart)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = if (wide) 80.dp else 16.dp, bottom = if (wide) 0.dp else 80.dp, top = if (wide) 80.dp else 0.dp),
            enter    = expandVertically(tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
            exit     = shrinkVertically(tween(200)) + fadeOut(tween(200))
        ) {
            SettingsMenu(
                themeMode             = themeMode,
                onThemeModeChange     = onThemeModeChange,
                onClose               = onCloseSettings,
                isPipEnabled          = isPipEnabled,
                onPipEnabledChange    = onPipEnabledChange,
                defaultPage           = defaultPage,
                onDefaultPageChange   = onDefaultPageChange,
                keepScreenAwake       = keepScreenAwake,
                onKeepScreenAwakeChange = onKeepScreenAwakeChange
            )
        }

        // ── Floating new-package toast (shown at top, swipeable to dismiss) ─
        AnimatedVisibility(
            visible = state.showNewPackageToast,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            enter = slideInVertically(tween(300)) { -it / 2 } + fadeIn(tween(300)),
            exit  = slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(200))
        ) {
            val newPkg = state.newPackageFromSubscription
            if (newPkg != null) {
                // ── Swipe-to-dismiss: drag offset + fade on swipe ────────────
                var swipeOffset by remember { mutableFloatStateOf(0f) }
                val dismissThreshold = -160f // px — must swipe up past this to dismiss

                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, swipeOffset.roundToInt()) }
                        .alpha(1f + (swipeOffset / 300f).coerceIn(0f, 1f))
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                swipeOffset = (swipeOffset + delta).coerceAtMost(0f) // only upward
                            },
                            onDragStopped = {
                                if (swipeOffset < dismissThreshold) {
                                    viewModel.dismissNewPackageToast()
                                }
                                swipeOffset = 0f // snap back if not dismissed
                            }
                        )
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = 380.dp)
                            .clickable { viewModel.onNewPackageToastClicked() },
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.5.dp, colors.blue.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.green.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.LocalShipping, null, tint = colors.green, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("New package available!", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text( newPkg.id, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("${newPkg.fromAddress} → ${newPkg.toAddress}", color = colors.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.ChevronRight, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Auto-dismiss after 8 seconds
                LaunchedEffect(newPkg.id) {
                    kotlinx.coroutines.delay(8_000)
                    viewModel.dismissNewPackageToast()
                }
            }
        }

        // ── Package dialogs ───────────────────────────────────────────────────
        val selectedPkg = viewModel.getSelectedDriverPackage()

        if (state.isDeliverDialogOpen && selectedPkg != null) {
            DeliverConfirmationDialog(
                packageId    = selectedPkg.id,
                expectedCode = "",
                codeInput    = state.deliverCodeInput,
                codeError    = state.deliverCodeError,
                onCodeChange = { viewModel.updateDeliverCode(it) },
                onConfirm    = { viewModel.confirmDeliver() },
                onDismiss    = { viewModel.closeDeliverDialog() },
                isConfirming = state.isConfirmingDelivery
            )
        }

        if (state.isTransferDialogOpen && selectedPkg != null) {
            TransferToOfficeDialog(
                packageId   = selectedPkg.id,
                currentStop = state.trip.stops.getOrNull(state.currentStopIndex)?.name ?: "Current location",
                onConfirm   = { viewModel.confirmTransfer() },
                onDismiss   = { viewModel.closeTransferDialog() },
                isCreating  = state.isCreatingTransfer
            )
        }

        if (state.isPackageDetailSheetOpen && selectedPkg != null) {
            PackageDetailSheet(pkg = selectedPkg, onDismiss = { viewModel.closePackageDetail() })
        }

        // ── Batch Transfer Dialog (selection mode) ────────────────────────
        if (state.showBatchTransferDialog) {
            BatchTransferDialog(
                packageCount = state.selectedPackageIds.size,
                selectedRuleType = state.selectedTransferRuleType,
                onRuleTypeChange = { viewModel.setTransferRuleType(it) },
                onConfirm = { viewModel.createBatchTransfer() },
                onDismiss = { viewModel.closeBatchTransferDialog() },
                isCreating = state.isCreatingTransfer
            )
        }
    }
}
