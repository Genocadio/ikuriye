package com.gocavgo.ikuriye.ui.driver

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.hasOpenTransfer
import com.gocavgo.ikuriye.data.hasUnreadNotices
import com.gocavgo.ikuriye.data.sortedByUnreadNotices
import com.gocavgo.ikuriye.ui.common.FullScreenMediaViewer
import com.gocavgo.ikuriye.ui.common.MediaCarousel
import com.gocavgo.ikuriye.ui.common.adaptiveHorizontalPadding
import com.gocavgo.ikuriye.ui.common.formatTime
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.TripViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverPackagesTab(viewModel: TripViewModel) {
    val state by viewModel.state.collectAsState()
    val colors = LocalDriversColors.current
    LaunchedEffect(Unit) { viewModel.loadDriverPackages() }

    val subTab              = state.driverPackageSubTab
    val searchQuery         = state.driverPackageSearchQuery
    val isRefreshing        = state.isRefreshingPackages
    val isDriverInitLoading = state.isDriverInitialLoading
    val isSelection         = state.isSelectionMode
    val selectedIds         = state.selectedPackageIds

    val filteredCurrent = state.driverCurrentPackages.filter {
        searchQuery.isBlank() || it.id.contains(searchQuery, true) ||
        it.description.contains(searchQuery, true) ||
        it.toAddress.contains(searchQuery, true) || it.fromAddress.contains(searchQuery, true)
    }.sortedByUnreadNotices(state.notices)
    val filteredOffers = state.driverAvailableOffers.filter {
        searchQuery.isBlank() || it.id.contains(searchQuery, true) ||
        it.description.contains(searchQuery, true) ||
        it.toAddress.contains(searchQuery, true) || it.fromAddress.contains(searchQuery, true)
    }
    val driverUserId = state.authUser?.id
    // Only show packages that have a real transfer offer — packages without a transferId
    // are not offers and should not appear in this list.
    val visibleOffers = filteredOffers.filter { pkg ->
        val alreadyCustodian = driverUserId != null && pkg.custodians.any { it.userId == driverUserId }
        pkg.transferId != null && !alreadyCustodian && pkg.transferStatus != "REQUESTED"
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWide = screenWidthDp >= 600
    val gridCols = when {
        screenWidthDp >= 1000 -> GridCells.Adaptive(320.dp)
        screenWidthDp >= 600 -> GridCells.Adaptive(300.dp)
        else -> GridCells.Fixed(1)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )

    var isSearchExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    // Sync pager → subTab
    LaunchedEffect(pagerState.currentPage) {
        if (subTab != pagerState.currentPage) {
            viewModel.setDriverPackageSubTab(pagerState.currentPage)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Sync subTab → pager (for tab button clicks)
    LaunchedEffect(subTab) {
        if (pagerState.currentPage != subTab) {
            pagerState.animateScrollToPage(subTab)
        }
    }

    // Exit selection mode if switching away from current tab
    LaunchedEffect(subTab) {
        if (subTab != 0 && isSelection) viewModel.exitSelectionMode()
    }

    // Block horizontal swipe while pull-to-refresh is active
    var isVerticalScrolling by remember { mutableStateOf(false) }
    val noHorizontalDuringRefresh = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
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

    PullToRefreshBox(
        isRefreshing = isRefreshing && !isSelection,
        onRefresh = { viewModel.refreshDriverPackages() },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {

            Column(modifier = Modifier.fillMaxSize()) {

                // ── Selection mode compact bar (shown above search when selecting) ─
                if (isSelection) {
                    SelectionChipRow(
                        selectedCount = selectedIds.size,
                        onCancel = { viewModel.exitSelectionMode() }
                    )
                }

                // ── Row: search bar (expanded) or sub-tabs + search icon (collapsed) ──
                // animateContentSize smooths the 42dp→56dp height change when the
                // bar expands, instead of snapping the row (and the list) down.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(tween(220)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSearchExpanded) {
                        // Expanded search bar — constrained by weight so it leaves room for the bell.
                        // No vertical padding: the field's top edge stays aligned with the icon row.
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateDriverPackageSearch(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search package code...", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.updateDriverPackageSearch("")
                                    isSearchExpanded = false
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = colors.divider,
                                focusedBorderColor = colors.blue,
                                cursorColor = colors.blue,
                                unfocusedLeadingIconColor = colors.textSecondary,
                                focusedLeadingIconColor = colors.blue
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                        )
                        // Reserve space for the floating bell so the search bar doesn't extend behind it
                        Spacer(Modifier.width(8.dp))
                        Spacer(Modifier.width(42.dp))
                    } else {
                        // Search icon button (left side)
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceAlt,
                            border = BorderStroke(1.dp, colors.divider),
                            onClick = { isSearchExpanded = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Active", 1 to "New").forEach { (index, label) ->
                                val selected = subTab == index
                                val count    = if (index == 0) state.driverCurrentPackages.size else visibleOffers.size
                                val disabled  = isSelection && index != 0
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) colors.blue else colors.surfaceAlt,
                                    border = if (selected) null else BorderStroke(1.dp, colors.divider),
                                    onClick = { if (!disabled) viewModel.setDriverPackageSubTab(index) }
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                        Text(label, color = if (selected) Color.White else if (disabled) colors.textSecondary.copy(alpha = 0.5f) else colors.textSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier.widthIn(min = 20.dp).heightIn(min = 20.dp).clip(CircleShape)
                                                .background(if (selected) Color.White.copy(alpha = 0.25f) else if (disabled) colors.divider.copy(alpha = 0.3f) else colors.divider)
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("$count", color = if (selected) Color.White else if (disabled) colors.textSecondary.copy(alpha = 0.3f) else colors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                        // Right-side spacer to reserve room for the floating bell
                        Spacer(Modifier.width(8.dp))
                        Spacer(Modifier.width(42.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Tab content ─────────────────────────────────────────────────
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().nestedScroll(noHorizontalDuringRefresh)
                ) { tab ->
                        val listState = remember { LazyListState() }
                        
                        // Search collapse handled by search bar toggle
                        
                        when (tab) {                            0 -> {
                            if (isDriverInitLoading && filteredCurrent.isEmpty() && !isSelection) {
                                ShimmerList(shimmerAlpha)
                            } else if (filteredCurrent.isEmpty()) {
                                EmptyPackagesState("No current packages", "Packages assigned to you will appear here")
                            } else if (isWide) {
                                LazyVerticalGrid(
                                    columns = gridCols,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = adaptiveHorizontalPadding(), end = adaptiveHorizontalPadding(), top = 4.dp, bottom = if (isSelection) 160.dp else 90.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredCurrent, key = { it.id }) { pkg ->
                                        val isReq = pkg.transferStatus == "REQUESTED"
                                        if (isSelection) {
                                            SelectableCurrentPackageCard(pkg = pkg, isSelected = pkg.id in selectedIds, canSelect = pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED && !isReq, onToggle = { viewModel.togglePackageSelection(pkg.id) }, onDetail = { viewModel.openPackageDetail(pkg.id) })
                                        } else {
                                            DriverCurrentPackageCard(pkg = pkg, onDeliver = { viewModel.openDeliverDialog(pkg.id) }, onTransfer = { viewModel.openTransferDialog(pkg.id) }, onDetail = { viewModel.openPackageDetail(pkg.id) }, onLongPress = { if (pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED && !pkg.hasOpenTransfer) { viewModel.startSelectionMode(pkg.id) } }, onPickup = { viewModel.pickupPackage(pkg.id) }, onArriveAtOffice = { viewModel.arriveAtOffice(pkg.id) }, notices = state.notices)
                                        }
                                    }
                                    if (state.driverCurrentHasMore && !state.isLoadingMorePackages && !isRefreshing) {
                                        item { LaunchedEffect(Unit) { viewModel.loadMoreDriverCurrent() } }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (isSelection) 160.dp else 90.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredCurrent, key = { it.id }) { pkg ->
                                        val isReq = pkg.transferStatus == "REQUESTED"
                                        if (isSelection) {
                                            SelectableCurrentPackageCard(pkg = pkg, isSelected = pkg.id in selectedIds, canSelect = pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED && !isReq, onToggle = { viewModel.togglePackageSelection(pkg.id) }, onDetail = { viewModel.openPackageDetail(pkg.id) })
                                        } else {
                                            DriverCurrentPackageCard(pkg = pkg, onDeliver = { viewModel.openDeliverDialog(pkg.id) }, onTransfer = { viewModel.openTransferDialog(pkg.id) }, onDetail = { viewModel.openPackageDetail(pkg.id) }, onLongPress = { if (pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED && !pkg.hasOpenTransfer) { viewModel.startSelectionMode(pkg.id) } }, onPickup = { viewModel.pickupPackage(pkg.id) }, onArriveAtOffice = { viewModel.arriveAtOffice(pkg.id) }, notices = state.notices)
                                        }
                                    }
                                    if (state.driverCurrentHasMore && !state.isLoadingMorePackages && !isRefreshing) {
                                        item { LaunchedEffect(Unit) { viewModel.loadMoreDriverCurrent() } }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (isDriverInitLoading && filteredOffers.isEmpty()) {
                                ShimmerList(shimmerAlpha)
                            } else if (visibleOffers.isEmpty()) {
                                EmptyPackagesState("No available offers", "New delivery offers will appear here")
                            } else if (isWide) {
                                LazyVerticalGrid(
                                    columns = gridCols,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = adaptiveHorizontalPadding(), end = adaptiveHorizontalPadding(), top = 4.dp, bottom = 90.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(visibleOffers, key = { it.id }) { pkg ->
                                        val acceptInfo: Pair<(() -> Unit), String>? = pkg.transferId?.let { transferId ->
                                            when (pkg.transferRuleType) {
                                                "CONFIRM" -> ({ viewModel.openRequestTransferDialog(pkg.id, transferId) } to "Request Offer")
                                                "SECURE" -> ({ viewModel.openAcceptTransferCodeDialog(pkg.id, transferId, "SECURE") } to "Accept Offer")
                                                "AUTO" -> ({ viewModel.acceptAUTOTransfer(pkg.id, transferId) } to "Accept Offer")
                                                else -> null
                                            }
                                        }
                                        DriverOfferCard(pkg = pkg, onAccept = acceptInfo?.first, acceptButtonLabel = acceptInfo?.second ?: "Accept Offer", onDetail = { viewModel.openPackageDetail(pkg.id) }, isNew = pkg.id == state.newPackageFromSubscription?.id)
                                    }
                                    if (state.driverOffersHasMore && !state.isLoadingMorePackages && !isRefreshing) {
                                        item { LaunchedEffect(Unit) { viewModel.loadMoreDriverOffers() } }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 90.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(visibleOffers, key = { it.id }) { pkg ->
                                        val acceptInfo: Pair<(() -> Unit), String>? = pkg.transferId?.let { transferId ->
                                            when (pkg.transferRuleType) {
                                                "CONFIRM" -> ({ viewModel.openRequestTransferDialog(pkg.id, transferId) } to "Request Offer")
                                                "SECURE" -> ({ viewModel.openAcceptTransferCodeDialog(pkg.id, transferId, "SECURE") } to "Accept Offer")
                                                "AUTO" -> ({ viewModel.acceptAUTOTransfer(pkg.id, transferId) } to "Accept Offer")
                                                else -> null
                                            }
                                        }
                                        DriverOfferCard(pkg = pkg, onAccept = acceptInfo?.first, acceptButtonLabel = acceptInfo?.second ?: "Accept Offer", onDetail = { viewModel.openPackageDetail(pkg.id) }, isNew = pkg.id == state.newPackageFromSubscription?.id)
                                    }
                                    if (state.driverOffersHasMore && !state.isLoadingMorePackages && !isRefreshing) {
                                        item { LaunchedEffect(Unit) { viewModel.loadMoreDriverOffers() } }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Floating Create Transfer button (selection mode) ─────────────────
            val allDriverPackages = state.driverCurrentPackages + state.driverAvailableOffers
            val eligibleSelectedCount = selectedIds.count { id ->
                allDriverPackages.find { it.id == id }?.hasOpenTransfer != true
            }
            AnimatedVisibility(
                visible = isSelection && selectedIds.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 100.dp),
                enter = slideInVertically(tween(300)) { it / 2 } + scaleIn(tween(300)),
                exit = slideOutVertically(tween(200)) { it / 2 } + scaleOut(tween(200))
            ) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openBatchTransferDialog() },
                    containerColor = colors.blue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                    icon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null, modifier = Modifier.size(20.dp)) },
                    text = { Text("Create Transfer ($eligibleSelectedCount)", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                )
            }
        }
    }
}

// ── Selection Mode Compact Chip Row ──────────────────────────────────────────

@Composable
private fun SelectionChipRow(selectedCount: Int, onCancel: () -> Unit) {
    val colors = LocalDriversColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = colors.blue.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.3f))
        ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = colors.blue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("$selectedCount selected", color = colors.blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onCancel,
            modifier = Modifier.heightIn(min = 30.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Cancel", color = colors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ── Selectable Current Package Card ──────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableCurrentPackageCard(
    pkg: ClientPackage,
    isSelected: Boolean,
    canSelect: Boolean,
    onToggle: () -> Unit,
    onDetail: () -> Unit
) {
    val colors = LocalDriversColors.current
    var showMedia by remember { mutableStateOf(false) }

    val statusColor = when (pkg.status) {
        PackageStatus.PICKED_UP -> colors.amber
        PackageStatus.IN_TRANSIT -> colors.blue
        PackageStatus.ARRIVED_AT_OFFICE -> colors.blue
        PackageStatus.OUT_FOR_DELIVERY, PackageStatus.DELIVERED -> colors.green
        else -> colors.textSecondary
    }

    val borderColor = if (isSelected) colors.blue else colors.divider
    val bgColor = if (isSelected) colors.blue.copy(alpha = 0.04f) else colors.surface

    val cardAlpha = if (canSelect) 1f else 0.5f
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = if (isSelected) BorderStroke(2.dp, colors.blue.copy(alpha = 0.6f)) else BorderStroke(1.dp, borderColor),
        shadowElevation = if (isSelected) 4.dp else 2.dp,
        modifier = Modifier.combinedClickable(
            onClick = { if (canSelect) onToggle() },
            onLongClick = { if (canSelect) onToggle() }
        ).alpha(cardAlpha)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox for selection
                if (canSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(checkedColor = colors.blue, uncheckedColor = colors.textSecondary),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocalShipping, null, tint = statusColor, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pkg.id, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    TruncatedDescription(pkg.description)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                    Text(pkg.status.name.replace("_", " "), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            AddressRow(Icons.Filled.MyLocation, colors.green, pkg.fromAddress)
            Spacer(Modifier.height(4.dp))
            AddressRow(Icons.Filled.LocationOn, colors.red, pkg.toAddress)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pkg.weight.isNotBlank()) { Icon(Icons.Filled.Scale, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(pkg.weight, color = colors.textSecondary, fontSize = 11.sp) }
                if (pkg.photoCount > 0) { Spacer(Modifier.width(12.dp)); Row(modifier = Modifier.clickable { showMedia = true }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Photo, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("${pkg.photoCount}", color = colors.textSecondary, fontSize = 11.sp) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetail, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Details", color = colors.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            if (!canSelect) {
                Spacer(Modifier.height(6.dp))
                val msg = when {
                    pkg.status == PackageStatus.DELIVERED || pkg.status == PackageStatus.CANCELLED -> "Already completed"
                    pkg.transferStatus == "REQUESTED" -> "Transfer request pending — waiting for confirmation"
                    else -> "Not available for transfer"
                }
                Text(msg, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Normal)
            }
        }
    }
    if (showMedia) { MediaCarouselDialog(pkg.mediaUrls) { showMedia = false } }
}

// ── Package Cards ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriverCurrentPackageCard(
    pkg: ClientPackage,
    onDeliver: () -> Unit,
    onTransfer: () -> Unit,
    onDetail: () -> Unit,
    onLongPress: () -> Unit = {},
    onPickup: () -> Unit = {},
    onArriveAtOffice: () -> Unit = {},
    notices: List<com.gocavgo.ikuriye.data.Notice> = emptyList()
) {
    val colors = LocalDriversColors.current
    val isDelivered = pkg.status == PackageStatus.DELIVERED
    val isTransferRequested = pkg.transferStatus == "REQUESTED"
    val isTransferOpen = pkg.hasOpenTransfer
    val isUnread = pkg.hasUnreadNotices(notices)
    var showMedia by remember { mutableStateOf(false) }

    val statusColor = when (pkg.status) {
        PackageStatus.PICKED_UP -> colors.amber
        PackageStatus.IN_TRANSIT -> colors.blue
        PackageStatus.ARRIVED_AT_OFFICE -> colors.blue
        PackageStatus.PENDING_CONFIRMATION -> colors.amber
        PackageStatus.OUT_FOR_DELIVERY, PackageStatus.DELIVERED -> colors.green
        else -> colors.textSecondary
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        shadowElevation = 2.dp,
        modifier = Modifier.combinedClickable(
            onClick = { onDetail() },
            onLongClick = onLongPress
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(if (isDelivered) Icons.Filled.CheckCircle else Icons.Filled.LocalShipping, null, tint = statusColor, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isUnread) {
                            val dotTransition = rememberInfiniteTransition(label = "unread")
                            val dotAlpha by dotTransition.animateFloat(
                                initialValue = 0.35f, targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                label = "dotAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(colors.red.copy(alpha = dotAlpha))
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(pkg.id, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (isTransferOpen) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = colors.blue.copy(alpha = 0.12f)) {
                                Text("Pending Transfer", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = colors.blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    TruncatedDescription(pkg.description)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                    Text(pkg.status.name.replace("_", " "), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            AddressRow(Icons.Filled.MyLocation, colors.green, pkg.fromAddress)
            Spacer(Modifier.height(4.dp))
            AddressRow(Icons.Filled.LocationOn, colors.red, pkg.toAddress)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pkg.weight.isNotBlank()) { Icon(Icons.Filled.Scale, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(pkg.weight, color = colors.textSecondary, fontSize = 11.sp) }
                if (pkg.photoCount > 0) { Spacer(Modifier.width(12.dp)); Row(modifier = Modifier.clickable { showMedia = true }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Photo, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("${pkg.photoCount}", color = colors.textSecondary, fontSize = 11.sp) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetail, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Details", color = colors.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            if (!isDelivered && !isTransferOpen) {
                Spacer(Modifier.height(8.dp))
                val isPendingConfirmation = pkg.status == PackageStatus.PENDING_CONFIRMATION
                val isFixedRoute = pkg.deliveryType == "FIXED_ROUTE"
                val needsPickup = isFixedRoute && pkg.backendStatus in listOf("ASSIGNED_DRIVER", "ORIGIN_OFFICE")
                val needsArrival = isFixedRoute && pkg.backendStatus == "IN_TRANSIT"
                val isAtDestinationOffice = isFixedRoute && pkg.backendStatus == "DESTINATION_OFFICE"
                val canDeliver = !needsPickup && !needsArrival && !isAtDestinationOffice
                
                when {
                    // FIXED_ROUTE: Driver needs to pick up first (ASSIGNED_DRIVER or ORIGIN_OFFICE)
                    needsPickup -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onPickup,
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                            ) {
                                Icon(Icons.Filled.LocalShipping, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pick Up Package", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    // FIXED_ROUTE: Driver in transit, needs to mark arrival at destination office
                    needsArrival -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onArriveAtOffice,
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.green)
                            ) {
                                Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Arrive at Office", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    // FIXED_ROUTE: Arrived at destination — waiting for worker to mark ready
                    isAtDestinationOffice -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.blue.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.2f))
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.HourglassTop, null, tint = colors.blue, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Awaiting office to make package ready for collection", color = colors.blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    // Standard deliver / confirm flow
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onDeliver,
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (isPendingConfirmation) colors.blue else colors.green)
                            ) {
                                Icon(
                                    if (isPendingConfirmation) Icons.Filled.Lock else Icons.Filled.Check,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                );
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isPendingConfirmation) "Enter Confirmation Code" else "Deliver",
                                    fontSize = if (isPendingConfirmation) 11.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            if (!isPendingConfirmation) {
                                OutlinedButton(onClick = onTransfer, modifier = Modifier.weight(1f).heightIn(min = 38.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, colors.divider)) {
                                    Icon(Icons.Filled.MoveToInbox, null, modifier = Modifier.size(16.dp), tint = colors.amber); Spacer(Modifier.width(6.dp)); Text("Transfer to Office", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                                }
                            }
                        }
                    }
                }
            } else if (isTransferOpen) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.blue.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.HourglassTop, null, tint = colors.blue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isTransferRequested) "Awaiting owner confirmation — actions disabled while pending"
                            else "Transfer in progress — awaiting office pickup",
                            color = colors.blue, fontSize = 11.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
    if (showMedia) { MediaCarouselDialog(pkg.mediaUrls) { showMedia = false } }
}

@Composable
fun DriverOfferCard(
    pkg: ClientPackage,
    onAccept: (() -> Unit)?,
    acceptButtonLabel: String = "Accept Offer",
    onDetail: () -> Unit,
    isNew: Boolean = false
) {
    val colors = LocalDriversColors.current
    var showMedia by remember { mutableStateOf(false) }

    // ── Growing-border animation for new packages ────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "growingBorder")
    val borderProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Restart),
        label = "borderProgress"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isNew) (1.dp + 2.dp * borderProgress) else 1.dp,
        label = "borderWidth"
    )
    val borderColor = if (isNew) colors.blue.copy(alpha = 0.4f + borderProgress * 0.4f) else colors.divider

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = if (isNew) BorderStroke(borderWidth, borderColor) else BorderStroke(1.dp, colors.divider),
        shadowElevation = if (isNew) 4.dp else 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.NewReleases, null, tint = colors.amber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(pkg.id, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp); TruncatedDescription(pkg.description) }
                // ── Transfer-type indicator icons ─────────────────────
                if (pkg.transferRuleType == "SECURE") {
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Lock, null, tint = colors.blue, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                } else if (pkg.transferRuleType == "CONFIRM") {
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape).background(colors.amber.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.HourglassTop, null, tint = colors.amber, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (isNew) {
                    Surface(shape = RoundedCornerShape(8.dp), color = colors.amber.copy(alpha = 0.12f)) { Text("NEW", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(10.dp))
            AddressRow(Icons.Filled.MyLocation, colors.green, pkg.fromAddress)
            Spacer(Modifier.height(4.dp))
            AddressRow(Icons.Filled.LocationOn, colors.red, pkg.toAddress)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pkg.weight.isNotBlank()) { Icon(Icons.Filled.Scale, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(pkg.weight, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                if (pkg.category.isNotBlank()) { if (pkg.weight.isNotBlank()) Spacer(Modifier.width(16.dp)); Icon(Icons.Filled.Category, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(pkg.category, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                if (pkg.fragile) { Spacer(Modifier.width(16.dp)); Icon(Icons.Filled.Warning, null, tint = colors.amber, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Fragile", color = colors.amber, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pkg.photoCount > 0) { Row(modifier = Modifier.clickable { showMedia = true }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Photo, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("${pkg.photoCount}", color = colors.textSecondary, fontSize = 11.sp) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetail, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("View Details", color = colors.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            if (onAccept != null) {
                val icon = when {
                    acceptButtonLabel.contains("Transfer") -> Icons.AutoMirrored.Filled.CompareArrows
                    acceptButtonLabel.contains("Code") -> Icons.Filled.Lock
                    else -> Icons.Filled.AddTask
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.blue)) {
                    Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(acceptButtonLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (showMedia) { MediaCarouselDialog(pkg.mediaUrls) { showMedia = false } }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun TruncatedDescription(description: String) {
    val colors = LocalDriversColors.current
    var showFull by remember { mutableStateOf(false) }
    Text(description, color = colors.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { showFull = true })
    if (showFull) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text("Description", fontWeight = FontWeight.Bold) },
            text = { Text(description, color = colors.textPrimary) },
            confirmButton = { TextButton(onClick = { showFull = false }) { Text("Close", color = colors.blue, fontWeight = FontWeight.Bold) } }
        )
    }
}

@Composable
private fun AddressRow(icon: ImageVector, iconTint: androidx.compose.ui.graphics.Color, address: String) {
    val colors = LocalDriversColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(address, color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaCarouselDialog(mediaUrls: List<String>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FullScreenMediaViewer(mediaUrls = mediaUrls, onClose = onDismiss, showClose = true)
    }
}

@Composable
private fun ShimmerList(alpha: Float) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { items(5) { PackageSkeleton(alpha) } }
}

@Composable
private fun PackageSkeleton(alpha: Float) {
    val colors = LocalDriversColors.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surface.copy(alpha = alpha), border = BorderStroke(1.dp, colors.divider.copy(alpha = alpha))) {
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
fun EmptyPackagesState(title: String, subtitle: String) {
    val colors = LocalDriversColors.current
    // LazyColumn so PullToRefreshBox can detect the pull gesture even when list is empty
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth().fillParentMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inventory2, null, tint = colors.textSecondary, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}
