package com.gocavgo.ikuriye.ui.client

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.hasOpenTransfer
import com.gocavgo.ikuriye.ui.common.FullScreenMediaViewer
import com.gocavgo.ikuriye.ui.common.MediaCarousel
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.common.SmartTimeText
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.PhoneValidation

@Composable
fun TrackPackageScreen(
    pkg: ClientPackage,
    onBack: () -> Unit,
    currentUserId: String = "",
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String, String) -> Unit = { _, _ -> },
    onRejectTransfer: (String, String) -> Unit = { _, _ -> },
    onGeneratePickupCode: (String) -> Unit = {},
    onConfirmDelivery: (String, String) -> Unit = { _, _ -> },
    pendingDeliveryCode: String = ""
) {
    val colors = LocalDriversColors.current
    val maxW = contentMaxWidth()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .then(if (maxW != Dp.Unspecified) Modifier.widthIn(max = maxW) else Modifier)
                .padding(16.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(colors.surfaceAlt)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Track Package", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pkg.id, color = colors.textSecondary, fontSize = 12.sp)
                    // ── Custodian icon: show small button beside code when package has custodians ──
                    if (pkg.custodians.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        var showCustodians by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showCustodians = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Filled.People, null,
                                    tint = colors.blue,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (showCustodians) {
                                AlertDialog(
                                    onDismissRequest = { showCustodians = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = colors.surface,
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.People, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Custodians", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        }
                                    },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            pkg.custodians.forEach { c ->
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = colors.surfaceAlt,
                                                    border = BorderStroke(1.dp, colors.divider)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.Person, null,
                                                            tint = colors.blue,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Column {
                                                            Text(c.name.ifBlank { c.userId }, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                            Text(c.role, color = colors.textSecondary, fontSize = 11.sp)
                                                            if (c.phone.isNotBlank()) {
                                                                Text(c.phone, color = colors.blue, fontSize = 11.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { showCustodians = false }) {
                                            Text("Close", color = colors.blue)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            pkg.status == PackageStatus.DELIVERED -> DeliveredPackageSummary(pkg, currentUserId)
            pkg.status == PackageStatus.CANCELLED -> CancelledPackageSummary(pkg)
            else                                  -> ActivePackageTracking(pkg, currentUserId, onCreateTransfer, onConfirmTransfer, onRejectTransfer, onGeneratePickupCode, onConfirmDelivery, pendingDeliveryCode)
        }
        }
    }
}

// ── Delivered summary ─────────────────────────────────────────────────────────

@Composable
private fun DeliveredPackageSummary(pkg: ClientPackage, currentUserId: String = "") {
    val colors = LocalDriversColors.current

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.blue.copy(alpha = 0.08f)) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.blue, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Delivered", color = colors.blue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Package has been received", color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }

        item {
            SectionLabel("Route")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("From", color = colors.textSecondary, fontSize = 10.sp)
                        Text(pkg.fromAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.textSecondary, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("To", color = colors.textSecondary, fontSize = 10.sp)
                        Text(pkg.toAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
            }
        }

        item {
            SectionLabel("People")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(14.dp)) {
                    val senderLabel = if (pkg.senderId.isNotEmpty() && pkg.senderId == currentUserId) "Sender (You)" else "Sender"
                    InfoRow(senderLabel, pkg.senderName)
                    if (pkg.senderPhone.isNotBlank()) { Spacer(Modifier.height(4.dp)); InfoRow("Sender phone", PhoneValidation.toDisplayFormat(pkg.senderPhone)) }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow("Recipient", pkg.recipientName)
                    if (pkg.recipientPhone.isNotBlank()) { Spacer(Modifier.height(4.dp)); InfoRow("Recipient phone", PhoneValidation.toDisplayFormat(pkg.recipientPhone)) }
                }
            }
        }

        if (pkg.statusHistory.isNotEmpty()) {
            item {
                SectionLabel("Transfer Log")
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                    Column(Modifier.padding(14.dp)) {
                        pkg.statusHistory.forEachIndexed { i, log ->
                            Row(verticalAlignment = Alignment.Top) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == 0) colors.blue else colors.divider))
                                    if (i < pkg.statusHistory.size - 1) Box(Modifier.width(1.dp).height(24.dp).background(colors.divider))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(log.message, color = colors.textPrimary, fontSize = 13.sp)
                                    SmartTimeText(log.timestamp, color = colors.textSecondary, fontSize = 11.sp)
                                }
                            }
                            if (i < pkg.statusHistory.size - 1) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        CustodiansSection(pkg = pkg, colors = colors)

        if (BuildConfig.DEBUG) Log.d("TrackPackage", "DeliveredPackageSummary mediaUrls=${pkg.mediaUrls}")
        if (pkg.mediaUrls.isNotEmpty()) {
            item { SectionLabel("Media"); MediaCarouselWithFullscreen(mediaUrls = pkg.mediaUrls) }
        }

        item {
            SectionLabel("Details")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(14.dp)) {
                    InfoTimeRow("Sent", pkg.createdAt)
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    InfoTimeRow("Received", pkg.receivedAt)
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow("Description", pkg.description)
                    if (pkg.weight.isNotBlank()) { HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp)); InfoRow("Weight", pkg.weight) }
                }
            }
        }

        if (pkg.driverName.isNotEmpty()) {
            item { SectionLabel("Driver"); DriverInfoCard(pkg.driverName, pkg.driverPhone, pkg.driverCompany, pkg.vehicleType) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Cancelled summary ─────────────────────────────────────────────────────────

@Composable
private fun CancelledPackageSummary(pkg: ClientPackage) {
    val colors = LocalDriversColors.current

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.red.copy(alpha = 0.08f)) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(colors.red.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Cancel, null, tint = colors.red, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Cancelled", color = colors.red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("This package was cancelled", color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            SectionLabel("Route")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(colors.green))
                        Spacer(Modifier.width(10.dp))
                        Column { Text("From", color = colors.textSecondary, fontSize = 10.sp); Text(pkg.fromAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    Box(modifier = Modifier.padding(start = 4.dp).width(2.dp).height(20.dp).background(colors.divider))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(colors.red))
                        Spacer(Modifier.width(10.dp))
                        Column { Text("To", color = colors.textSecondary, fontSize = 10.sp); Text(pkg.toAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        item {
            SectionLabel("Details")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(14.dp)) {
                    InfoTimeRow("Sent", pkg.createdAt)
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    val cancelledAt = pkg.statusHistory.lastOrNull { it.status == PackageStatus.CANCELLED }?.timestamp ?: "N/A"
                    InfoTimeRow("Cancelled", cancelledAt)
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow("Description", pkg.description)
                }
            }
        }
        val cancelReason = pkg.statusHistory.lastOrNull { it.status == PackageStatus.CANCELLED }?.message
        if (!cancelReason.isNullOrBlank()) {
            item {
                SectionLabel("Reason")
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = colors.red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(cancelReason, color = colors.textPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Active tracking ───────────────────────────────────────────────────────────

@Composable
private fun ActivePackageTracking(
    pkg: ClientPackage,
    currentUserId: String = "",
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String, String) -> Unit = { _, _ -> },
    onRejectTransfer: (String, String) -> Unit = { _, _ -> },
    onGeneratePickupCode: (String) -> Unit = {},
    onConfirmDelivery: (String, String) -> Unit = { _, _ -> },
    pendingDeliveryCode: String = ""
) {
    val colors = LocalDriversColors.current
    
    val hasActiveTransfer = pkg.hasOpenTransfer
    val hasNoCustodians = pkg.custodians.isEmpty()
    val isTransferRequested = pkg.transfers.any { it.status == "REQUESTED" }
    val isDelivered = pkg.status == PackageStatus.DELIVERED
    val isCancelled = pkg.status == PackageStatus.CANCELLED
    val canCreateTransfer = !isDelivered && !isCancelled && !hasActiveTransfer && hasNoCustodians
    val canConfirmTransfer = !isDelivered && !isCancelled && isTransferRequested
    val canGeneratePickupCode = pkg.custodians.isNotEmpty() && pkg.packageUuid.isNotBlank()
        && !isDelivered && !isCancelled && pkg.status != PackageStatus.PENDING
    val isAwaitingConfirmation = pkg.status == PackageStatus.PENDING_CONFIRMATION
    val canConfirmDelivery = isAwaitingConfirmation && pkg.packageUuid.isNotBlank() && pendingDeliveryCode.isNotBlank()

    val statusColor = when (pkg.status) {
        PackageStatus.PENDING -> colors.amber
        PackageStatus.PICKED_UP, PackageStatus.IN_TRANSIT -> colors.blue
        PackageStatus.ARRIVED_AT_OFFICE -> colors.blue
        PackageStatus.PENDING_CONFIRMATION -> colors.amber
        PackageStatus.OUT_FOR_DELIVERY -> colors.green
        PackageStatus.DELIVERED -> colors.blue
        PackageStatus.CANCELLED -> colors.red
    }
    val statusLabel = when (pkg.status) {
        PackageStatus.PENDING          -> "Awaiting pickup"
        PackageStatus.PICKED_UP        -> "Picked up"
        PackageStatus.IN_TRANSIT       -> "In transit"
        PackageStatus.ARRIVED_AT_OFFICE -> "At destination office"
        PackageStatus.PENDING_CONFIRMATION -> "Awaiting delivery confirmation"
        PackageStatus.OUT_FOR_DELIVERY -> "Arriving soon"
        PackageStatus.DELIVERED        -> "Delivered"
        PackageStatus.CANCELLED        -> "Cancelled"
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = statusColor.copy(alpha = 0.08f)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(
                            when (pkg.status) {
                                PackageStatus.PENDING -> Icons.Filled.Schedule
                                PackageStatus.PICKED_UP -> Icons.Filled.Inventory
                                PackageStatus.IN_TRANSIT -> Icons.Filled.LocalShipping
                                PackageStatus.ARRIVED_AT_OFFICE -> Icons.Filled.Store
                                PackageStatus.PENDING_CONFIRMATION -> Icons.Filled.Verified
                                PackageStatus.OUT_FOR_DELIVERY -> Icons.Filled.DirectionsCar
                                PackageStatus.DELIVERED -> Icons.Filled.CheckCircle
                                PackageStatus.CANCELLED -> Icons.Filled.Cancel
                            },
                            null, tint = statusColor, modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(statusLabel, color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        val lastLog = pkg.statusHistory.lastOrNull()
                        if (lastLog != null && lastLog.message.isNotBlank()) Text(lastLog.message, color = colors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            SectionLabel("Route")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("From", color = colors.textSecondary, fontSize = 10.sp)
                        Text(pkg.fromAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = colors.textSecondary, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text("To", color = colors.textSecondary, fontSize = 10.sp)
                        Text(pkg.toAddress, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
            }
        }

        item {
            SectionLabel("People")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(14.dp)) {
                    val senderLabel = if (pkg.senderId.isNotEmpty() && pkg.senderId == currentUserId) "Sender (You)" else "Sender"
                    InfoRow(senderLabel, pkg.senderName)
                    if (pkg.senderPhone.isNotBlank()) { Spacer(Modifier.height(4.dp)); InfoRow("Sender phone", PhoneValidation.toDisplayFormat(pkg.senderPhone)) }
                    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                    InfoRow("Recipient", pkg.recipientName)
                    if (pkg.recipientPhone.isNotBlank()) { Spacer(Modifier.height(4.dp)); InfoRow("Recipient phone", PhoneValidation.toDisplayFormat(pkg.recipientPhone)) }
                }
            }
        }

        if (pkg.statusHistory.isNotEmpty()) {
            item {
                SectionLabel("Transfer Log")
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                    Column(Modifier.padding(14.dp)) {
                        pkg.statusHistory.asReversed().forEachIndexed { i, log ->
                            Row(verticalAlignment = Alignment.Top) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == 0) colors.blue else colors.divider))
                                    if (i < pkg.statusHistory.size - 1) Box(Modifier.width(1.dp).height(24.dp).background(colors.divider))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(log.message, color = colors.textPrimary, fontSize = 13.sp)
                                    SmartTimeText(log.timestamp, color = colors.textSecondary, fontSize = 11.sp)
                                }
                            }
                            if (i < pkg.statusHistory.size - 1) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        CustodiansSection(pkg = pkg, colors = colors)

        if (BuildConfig.DEBUG) Log.d("TrackPackage", "ActivePackageTracking mediaUrls=${pkg.mediaUrls}")
        if (pkg.mediaUrls.isNotEmpty()) {
            item { SectionLabel("Media"); MediaCarouselWithFullscreen(mediaUrls = pkg.mediaUrls) }
        }

        // ── Transfer action buttons (same logic as PackageCard in ClientHomeScreen) ──
        if (canCreateTransfer || canConfirmTransfer || canGeneratePickupCode || canConfirmDelivery) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.divider)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (canCreateTransfer) {
                                OutlinedButton(
                                    onClick = { onCreateTransfer(pkg.id) },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Filled.CompareArrows, null, modifier = Modifier.size(16.dp), tint = colors.blue)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Create Transfer", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.blue)
                                }
                            }
                            if (canConfirmTransfer) {
                                Button(
                                    onClick = { pkg.transferId?.let { onConfirmTransfer(pkg.id, it) } },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.green)
                                ) {
                                    Icon(Icons.Filled.Verified, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Confirm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { pkg.transferId?.let { onRejectTransfer(pkg.id, it) } },
                                    modifier = Modifier.height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.Red)
                                }
                            }
                            if (canConfirmDelivery) {
                                Button(
                                    onClick = { onConfirmDelivery(pkg.packageUuid, pendingDeliveryCode) },
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.green)
                                ) {
                                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Confirm Delivery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        // ── Pickup code button: full text button on detail screen ──
                        if (canGeneratePickupCode) {
                            OutlinedButton(
                                onClick = { onGeneratePickupCode(pkg.packageUuid) },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.4f))
                            ) {
                                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(16.dp), tint = colors.blue)
                                Spacer(Modifier.width(6.dp))
                                Text("Generate Pickup Code", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.blue)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Custodians section (LazyListScope extension for use inside LazyColumn) ──

private fun androidx.compose.foundation.lazy.LazyListScope.CustodiansSection(
    pkg: ClientPackage,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    if (pkg.custodians.isEmpty()) return
    item {
        SectionLabel("Custodians")
        Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
            Column(Modifier.padding(14.dp)) {
                pkg.custodians.forEachIndexed { i, c ->
                    var showCustodianInfo by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Person, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { c.userId }, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(c.role, color = colors.textSecondary, fontSize = 11.sp)
                        }
                        if (c.phone.isNotBlank()) {
                            IconButton(
                                onClick = { showCustodianInfo = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.Info, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (i < pkg.custodians.size - 1) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 8.dp))

                    if (showCustodianInfo) {
                        AlertDialog(
                            onDismissRequest = { showCustodianInfo = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = colors.surface,
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Person, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(c.name.ifBlank { c.userId }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.surfaceAlt,
                                        border = BorderStroke(1.dp, colors.divider)
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.Badge, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Role: ", color = colors.textSecondary, fontSize = 13.sp)
                                                Text(c.role, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            if (c.phone.isNotBlank()) {
                                                Spacer(Modifier.height(8.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Filled.Phone, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Phone: ", color = colors.textSecondary, fontSize = 13.sp)
                                                    Text(com.gocavgo.ikuriye.util.PhoneValidation.toDisplayFormat(c.phone), color = colors.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showCustodianInfo = false }) {
                                    Text("Close", color = colors.blue)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Media carousel with maximize ──────────────────────────────────────────────

@Composable
private fun MediaCarouselWithFullscreen(mediaUrls: List<String>) {
    var showMaximized by remember { mutableStateOf(false) }
    MediaCarousel(mediaUrls = mediaUrls, onMaximize = { showMaximized = true })
    if (showMaximized) {
        Dialog(onDismissRequest = { showMaximized = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FullScreenMediaViewer(mediaUrls = mediaUrls,
                onClose = { showMaximized = false }, showClose = false)
        }
    }
}

// ── Shared row helpers ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalDriversColors.current
    Text(text, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalDriversColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoTimeRow(label: String, iso: String) {
    val colors = LocalDriversColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        SmartTimeText(
            iso = iso.ifBlank { "N/A" },
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DriverInfoCard(driverName: String, driverPhone: String, driverCompany: String = "", vehicleType: String = "") {
    val colors = LocalDriversColors.current
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(
                    when (vehicleType) { "bike" -> Icons.Filled.TwoWheeler; "car" -> Icons.Filled.DirectionsCar; else -> Icons.Filled.Person },
                    null, tint = colors.blue, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(driverName, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (driverCompany.isNotEmpty()) Text(driverCompany, color = colors.blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (driverPhone.isNotEmpty()) Text(PhoneValidation.toDisplayFormat(driverPhone), color = colors.textSecondary, fontSize = 12.sp)
            }
            if (vehicleType.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.blue.copy(alpha = 0.08f)) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (vehicleType == "bike") Icons.Filled.TwoWheeler else Icons.Filled.DirectionsCar, null, tint = colors.blue, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(vehicleType.replaceFirstChar { it.uppercase() }, color = colors.blue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
