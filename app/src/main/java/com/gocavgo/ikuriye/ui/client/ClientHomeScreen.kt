package com.gocavgo.ikuriye.ui.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ClientUser
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.ui.common.CachedAvatarImage
import com.gocavgo.ikuriye.ui.common.ProfileQuickMenu
import com.gocavgo.ikuriye.ui.common.SettingsMenu
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.common.formatTime
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.PhoneValidation
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.WindowInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientHomeScreen(
    client: ClientUser,
    packages: List<ClientPackage>,
    themeMode: AppThemeMode,
    isClientProfileMenuOpen: Boolean,
    isClientSettingsOpen: Boolean,
    onCreatePackage: () -> Unit,
    onTrackPackage: (String) -> Unit,
    onLogout: () -> Unit,
    onProfileMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    isRefreshing: Boolean = false,
    isInitialLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onCloseSettings: () -> Unit,
    onDismissMenus: () -> Unit,
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String, String) -> Unit = { _, _ -> },
    onGeneratePickupCode: (String) -> Unit = {},
    hasUnsavedDraft: Boolean = false,
    packagesFetchedOnce: Boolean = false,
    clientDataState: com.gocavgo.ikuriye.viewmodel.DataState = com.gocavgo.ikuriye.viewmodel.DataState.UNKNOWN,
    onNoticesClick: () -> Unit = {},
    noticeCount: Int = 0
) {
    val colors = LocalDriversColors.current
    var searchQuery by remember { mutableStateOf("") }
    var hasAppeared by remember { mutableStateOf(false) }
    val wide = isWideScreen()
    val maxW = contentMaxWidth()
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    var selectedTab by remember { mutableIntStateOf(0) }
    // Sync pager state → selectedTab
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) {
            selectedTab = pagerState.currentPage
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Sync selectedTab → pager state (for bottom bar clicks)
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    LaunchedEffect(Unit) { hasAppeared = true }

    val activePackages = packages.filter {
        (it.status == PackageStatus.PENDING ||
        it.status == PackageStatus.PICKED_UP ||
        it.status == PackageStatus.IN_TRANSIT ||
        it.status == PackageStatus.OUT_FOR_DELIVERY) &&
        (searchQuery.isBlank() || it.id.contains(searchQuery, ignoreCase = true))
    }
    // ── Auto-open create package panel ONLY when server/cache definitively confirms zero packages ──
    // Uses clientDataState to avoid opening on network failure or during loading.
    // - NO_DATA: server responded with empty list → safe to show create modal
    // - UNKNOWN/LOADING: don't open yet (backend unreachable, or still fetching)
    // - HAS_DATA: packages exist → never open modal
    var preventedAutoOpen by remember { mutableStateOf(false) }
    LaunchedEffect(clientDataState) {
        if (clientDataState == com.gocavgo.ikuriye.viewmodel.DataState.NO_DATA && !preventedAutoOpen) {
            preventedAutoOpen = true
            onCreatePackage()
        }
    }

    val completedPackages = packages.filter {
        (it.status == PackageStatus.DELIVERED || it.status == PackageStatus.CANCELLED) &&
        (searchQuery.isBlank() || it.id.contains(searchQuery, ignoreCase = true))
    }

    var isSearchExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )

    val density = LocalDensity.current
    val statusBarHeightDp = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val gradientHeightDp = (statusBarHeightDp + statusBarHeightDp / 2f).coerceAtLeast(1.dp)
    val listTopPadding = gradientHeightDp + 12.dp
    val solidStopFraction = (statusBarHeightDp / gradientHeightDp).coerceIn(0f, 1f)

    // Block horizontal swipe while pull-to-refresh is active
    var isVerticalScrolling by remember { mutableStateOf(false) }
    val noHorizontalDuringRefresh = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If vertical scroll is detected, block horizontal scroll
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    isVerticalScrolling = true
                }
                return Offset.Zero
            }
            override fun onPostScroll(available: Offset, consumed: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y == 0f && consumed.y == 0f) {
                    isVerticalScrolling = false
                }
                return Offset.Zero
            }
        }
    }

    // ── Main content composable (shared between compact and wide layouts) ─────
    val mainContent: @Composable () -> Unit = {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().background(colors.background)
        ) {
            Box(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {

                // ── Package list (scrolls behind status bar, drawn first) ─────
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().nestedScroll(noHorizontalDuringRefresh)
                ) { tab ->
                    // Each page gets its own scroll state to prevent cross-tab leaking
                    val pageListState = remember { LazyListState() }
                    val list = if (tab == 0) activePackages else completedPackages
                    if (isInitialLoading && list.isEmpty()) {
                        LazyColumn(
                            state = pageListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = listTopPadding, bottom = 90.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) { items(5) { PackageSkeleton(shimmerAlpha) } }
                    } else if (list.isEmpty()) {
                        LazyColumn(
                            state = pageListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = listTopPadding, bottom = 90.dp)
                        ) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().fillParentMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            if (searchQuery.isNotBlank()) Icons.Filled.SearchOff
                                            else if (tab == 0) Icons.Filled.LocalShipping else Icons.Filled.CheckCircle,
                                            null, tint = colors.textSecondary, modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            if (searchQuery.isNotBlank()) "No packages matching \"$searchQuery\""
                                            else if (tab == 0) "No active packages" else "No completed packages",
                                            color = colors.textSecondary, fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = pageListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = listTopPadding, bottom = 90.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(items = list, key = { it.id }) { pkg ->
                                PackageCard(
                                    pkg = pkg,
                                    onClick = { onTrackPackage(pkg.id) },
                                    clientId = client.id,
                                    clientPhone = client.phone,
                                    onCreateTransfer = { onCreateTransfer(pkg.id) },
                                    onConfirmTransfer = { transferId -> onConfirmTransfer(pkg.id, transferId) },
                                    onGeneratePickupCode = onGeneratePickupCode,
                                    modifier = Modifier.animateItem(tween(300))
                                )
                            }
                            item {
                                if (!isRefreshing && !isLoadingMore && list.isNotEmpty()) {
                                    LaunchedEffect(Unit) { onLoadMore() }
                                }
                            }
                        }
                    }
                }

                // ── Status bar overlay: solid behind status bar, fading below ─
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

                // ── Search bar (when expanded, below the gradient) ──────────
                // end = 66.dp reserves ~40dp for the bell button + 8dp gap + 16dp end padding
                AnimatedVisibility(
                    visible = isSearchExpanded,
                    modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, top = gradientHeightDp + 6.dp, end = 66.dp),
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit  = fadeOut(tween(150)) + shrinkVertically(tween(150))
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.divider)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search by tracking number...", color = colors.textSecondary, fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                IconButton(onClick = { searchQuery = ""; isSearchExpanded = false }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = colors.blue,
                                cursorColor = colors.blue
                            )
                        )
                    }
                }

                // ── Floating search FAB (collapsed state, left side) ────────
                AnimatedVisibility(
                    visible = !isSearchExpanded,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 2.dp),
                    enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                    exit  = scaleOut(tween(150)) + fadeOut(tween(150))
                ) {
                    SmallFloatingActionButton(
                        onClick = { isSearchExpanded = true },
                        containerColor = colors.surface,
                        contentColor = colors.textPrimary,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                    ) {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Bottom bar (compact only — wide uses side rail instead)
            if (!wide) {
                AnimatedVisibility(
                    visible = hasAppeared,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(400, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(400))
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
                                Box(
                                    modifier = Modifier.size(38.dp).clip(CircleShape)
                                        .background(if (isClientProfileMenuOpen) colors.blue.copy(alpha = 0.12f) else colors.surfaceAlt)
                                        .clickable { onProfileMenuClick() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val avatarUrl = client.avatarUrl
                                    if (!avatarUrl.isNullOrBlank()) {
                                        CachedAvatarImage(remoteUrl = avatarUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Text(
                                            client.name.ifBlank { "U" }.first().uppercase(),
                                            color = if (isClientProfileMenuOpen) colors.blue else colors.textSecondary,
                                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                                        )
                                    }
                                }
                                listOf(0 to ("Active" to Icons.Filled.LocalShipping), 1 to ("Completed" to Icons.Filled.CheckCircle)).forEach { (idx, pair) ->
                                    val (label, icon) = pair
                                    Box(
                                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                                            .background(animateColorAsState(if (selectedTab == idx) colors.blue else Color.Transparent, tween(300), label = "clientTab${idx}Bg").value)
                                            .clickable { selectedTab = idx },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, null, modifier = Modifier.size(15.dp),
                                                tint = animateColorAsState(if (selectedTab == idx) Color.White else colors.textSecondary, tween(300), label = "clientTab${idx}Icon").value)
                                            Spacer(Modifier.width(5.dp))
                                            Text(label, color = animateColorAsState(if (selectedTab == idx) Color.White else colors.textSecondary, tween(300), label = "clientTab${idx}Text").value,
                                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (hasUnsavedDraft) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = colors.red,
                                    contentColor = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }) {
                                FloatingActionButton(
                                    onClick = onCreatePackage,
                                    containerColor = colors.green,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    shape = CircleShape,
                                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp)
                                ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(26.dp)) }
                            }
                        } else {
                            FloatingActionButton(
                                onClick = onCreatePackage,
                                containerColor = colors.green,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp)
                            ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(26.dp)) }
                        }
                    }
                }
            }

            if (isClientProfileMenuOpen || isClientSettingsOpen) {
                Box(
                    modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismissMenus)
                )
            }

            AnimatedVisibility(
                visible = isClientProfileMenuOpen,
                modifier = Modifier.align(if (wide) Alignment.TopStart else Alignment.BottomStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = if (wide) 80.dp else 16.dp, bottom = if (wide) 0.dp else 80.dp, top = if (wide) 80.dp else 0.dp),
                enter = expandVertically(tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
                exit  = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                ProfileQuickMenu(
                    profile = DriverProfile(name = client.name, email = client.email, phone = client.phone, username = client.username, avatarUrl = client.avatarUrl),
                    onSettingsClick = onSettingsClick,
                    onLogoutClick = onLogout,
                    onProfileClick = onProfileClick,
                    accentColorOverride = colors.green
                )
            }

            AnimatedVisibility(
                visible = isClientSettingsOpen,
                modifier = Modifier.align(if (wide) Alignment.TopStart else Alignment.BottomStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = if (wide) 80.dp else 16.dp, bottom = if (wide) 0.dp else 80.dp, top = if (wide) 80.dp else 0.dp),
                enter = expandVertically(tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
                exit  = shrinkVertically(tween(200)) + fadeOut(tween(200))
            ) {
                SettingsMenu(themeMode = themeMode, onThemeModeChange = onThemeModeChange, onClose = onCloseSettings)
            }
        }
    }

    // ── Layout switch: wide (landscape/tablet) vs compact (phone) ──────────
    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {

        if (wide) {
            // ── WIDE SCREEN LAYOUT: Side rail + content ─────────────────────
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
                                    .background(if (isClientProfileMenuOpen) colors.blue.copy(alpha = 0.12f) else colors.surfaceAlt)
                                    .clickable { onProfileMenuClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                val avatarUrl = client.avatarUrl
                                if (!avatarUrl.isNullOrBlank()) {
                                    CachedAvatarImage(remoteUrl = avatarUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text(client.name.ifBlank { "U" }.first().uppercase(),
                                        color = if (isClientProfileMenuOpen) colors.blue else colors.textSecondary,
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                            Spacer(Modifier.height(24.dp))

                            // Tab buttons (vertical)
                            listOf(0 to (Icons.Filled.LocalShipping to "Active"), 1 to (Icons.Filled.CheckCircle to "Done")).forEach { (idx, pair) ->
                                val (icon, label) = pair
                                val selected = selectedTab == idx
                                Surface(
                                    modifier = Modifier.width(56.dp).padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (selected) colors.blue else colors.surfaceAlt,
                                    onClick = { selectedTab = idx }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(icon, null, tint = if (selected) Color.White else colors.textSecondary,
                                            modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.height(2.dp))
                                        Text(label,
                                            color = if (selected) Color.White else colors.textSecondary,
                                            fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        // Bottom: Create package FAB
                        if (hasUnsavedDraft) {
                            BadgedBox(badge = {
                                Badge(
                                    containerColor = colors.red,
                                    contentColor = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }) {
                                FloatingActionButton(
                                    onClick = onCreatePackage,
                                    modifier = Modifier.size(48.dp),
                                    containerColor = colors.green,
                                    contentColor = Color.White,
                                    shape = RoundedCornerShape(14.dp)
                                ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(22.dp)) }
                            }
                        } else {
                            FloatingActionButton(
                                onClick = onCreatePackage,
                                modifier = Modifier.size(48.dp),
                                containerColor = colors.green,
                                contentColor = Color.White,
                                shape = RoundedCornerShape(14.dp)
                            ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(22.dp)) }
                        }
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
            // ── COMPACT: existing vertical layout ───────────────────────────
            mainContent()
        }

        // ── Bell notification button (always visible at top-right, above mainContent) ─
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
    }
}

// ── Original skeleton loader ──────────────────────────────────────────────────

@Composable
private fun PackageSkeleton(alpha: Float) {
    val colors = LocalDriversColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = alpha),
        border = BorderStroke(1.dp, colors.divider.copy(alpha = alpha))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp, 10.dp).background(colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(16.dp).background(colors.textSecondary.copy(alpha = 0.2f), CircleShape))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Box(Modifier.size(40.dp, 8.dp).background(colors.textSecondary.copy(alpha = 0.1f), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(70.dp, 12.dp).background(colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Box(Modifier.size(40.dp, 8.dp).background(colors.textSecondary.copy(alpha = 0.1f), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(70.dp, 12.dp).background(colors.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(4.dp)))
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).background(colors.textSecondary.copy(alpha = 0.1f), RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun PackageCard(
    pkg: ClientPackage,
    onClick: () -> Unit,
    clientId: String = "",
    clientPhone: String = "",
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String) -> Unit = {},
    onGeneratePickupCode: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current
    val isDelivered = pkg.status == PackageStatus.DELIVERED
    val isCancelled = pkg.status == PackageStatus.CANCELLED
    val isMyPackage = pkg.senderId == clientId
    val isForMe = pkg.recipientId == clientId
    val senderDisplayPhone = PhoneValidation.toDisplayFormat(pkg.senderPhone.ifBlank { if (isMyPackage) clientPhone else "" })
    val recipientDisplayPhone = PhoneValidation.toDisplayFormat(pkg.recipientPhone.ifBlank { if (isForMe) clientPhone else "" })

    // Check server transfers and custodians for button visibility
    val hasActiveTransfer = pkg.transfers.any { t -> t.status == "PENDING" || t.status == "REQUESTED" }
    val hasNoCustodians = pkg.custodians.isEmpty()
    val isTransferRequested = pkg.transfers.any { it.status == "REQUESTED" }
    val canCreateTransfer = !isDelivered && !isCancelled && !hasActiveTransfer && hasNoCustodians
    val canConfirmTransfer = !isDelivered && !isCancelled && isTransferRequested
    val canGeneratePickupCode = pkg.custodians.isNotEmpty() && pkg.packageUuid.isNotBlank()
        && !isDelivered && !isCancelled && pkg.status != PackageStatus.PENDING
    val isStale = !isDelivered && !isCancelled && !hasActiveTransfer && hasNoCustodians && pkg.status == PackageStatus.PENDING

    val statusColor = when (pkg.status) {
        PackageStatus.PENDING -> if (isStale) colors.amber.copy(alpha = 0.5f) else colors.amber
        PackageStatus.PICKED_UP, PackageStatus.IN_TRANSIT -> colors.blue
        PackageStatus.PENDING_CONFIRMATION -> colors.amber
        PackageStatus.OUT_FOR_DELIVERY -> colors.green
        PackageStatus.DELIVERED -> colors.blue
        PackageStatus.CANCELLED -> colors.red
    }
    val statusIcon = when (pkg.status) {
        PackageStatus.PENDING -> if (isStale) Icons.Filled.HourglassEmpty else Icons.Filled.Schedule
        PackageStatus.PICKED_UP -> Icons.Filled.Inventory
        PackageStatus.IN_TRANSIT -> Icons.Filled.LocalShipping
        PackageStatus.PENDING_CONFIRMATION -> Icons.Filled.Verified
        PackageStatus.OUT_FOR_DELIVERY -> Icons.Filled.Moped
        PackageStatus.DELIVERED -> Icons.Filled.CheckCircle
        PackageStatus.CANCELLED -> Icons.Filled.Cancel
    }

    Surface(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pkg.id, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (canGeneratePickupCode) {
                    IconButton(
                        onClick = { onGeneratePickupCode(pkg.packageUuid) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Generate pickup code",
                            tint = colors.blue,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (isDelivered && pkg.receivedAt.isNotBlank()) formatTime(pkg.receivedAt) else formatTime(pkg.createdAt),
                    color = colors.textSecondary, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp)
                )
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sender", color = colors.textSecondary, fontSize = 10.sp)
                    Text(if (pkg.senderId == clientId) "You" else pkg.senderName, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (senderDisplayPhone.isNotBlank()) Text(senderDisplayPhone, color = colors.textSecondary, fontSize = 10.sp)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Recipient", color = colors.textSecondary, fontSize = 10.sp)
                    Text(if (pkg.recipientId == clientId) "You" else pkg.recipientName, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (recipientDisplayPhone.isNotBlank()) Text(recipientDisplayPhone, color = colors.textSecondary, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pkg.fromAddress, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowForward, null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Text(pkg.toAddress, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }

            // Transfer action buttons
            if (canCreateTransfer || canConfirmTransfer) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canCreateTransfer) {
                        OutlinedButton(
                            onClick = { onCreateTransfer(pkg.id) },
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Filled.CompareArrows, null, modifier = Modifier.size(14.dp), tint = colors.blue)
                            Spacer(Modifier.width(5.dp))
                            Text("Create Transfer", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.blue)
                        }
                    }
                    if (canConfirmTransfer) {
                        Button(
                            onClick = { pkg.transferId?.let { onConfirmTransfer(it) } },
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.green)
                        ) {
                            Icon(Icons.Filled.Verified, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Confirm Transfer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
