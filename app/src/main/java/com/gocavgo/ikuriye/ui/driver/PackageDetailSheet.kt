package com.gocavgo.ikuriye.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.ui.common.FullScreenMediaViewer
import com.gocavgo.ikuriye.ui.common.MediaCarousel
import com.gocavgo.ikuriye.ui.common.SmartTimeText
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.PhoneValidation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageDetailSheet(pkg: ClientPackage, onDismiss: () -> Unit) {
    val colors     = LocalDriversColors.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
        shape            = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.width(40.dp).height(4.dp).padding(top = 8.dp, bottom = 4.dp),
                shape = RoundedCornerShape(2.dp), color = colors.divider
            ) {}
        }
    ) {
        val isWide = isWideScreen()
    val padH = if (isWide) 24.dp else 20.dp

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = padH)
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .verticalScroll(scrollState)
    ) {
        Spacer(Modifier.height(16.dp))

        // ── Header: ID + status ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pkg.id, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val statusColor = when (pkg.status) {
                PackageStatus.PICKED_UP          -> colors.amber
                PackageStatus.IN_TRANSIT         -> colors.blue
                PackageStatus.ARRIVED_AT_OFFICE  -> colors.blue
                PackageStatus.OUT_FOR_DELIVERY,
                PackageStatus.DELIVERED          -> colors.green
                PackageStatus.CANCELLED          -> colors.red
                else                             -> colors.textSecondary
            }
            Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.12f)) {
                Text(pkg.status.name.replace("_", " "),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = statusColor, fontSize = if (isWide) 12.sp else 11.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(pkg.description, color = colors.textSecondary,
            fontSize = if (isWide) 14.sp else 13.sp)

        if (pkg.createdAt.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            DetailTimeRow("Created", pkg.createdAt, Icons.Filled.Schedule,
                colors.textSecondary)
        }

        HorizontalDivider(color = colors.divider,
            modifier = Modifier.padding(vertical = if (isWide) 12.dp else 14.dp))

        // ── Sender / Recipient side-by-side on wide screens ──
        if (isWide) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SENDER", color = colors.blue, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    DetailRow("Name", pkg.senderName, Icons.Filled.Person, colors.blue)
                    DetailRow("Phone", PhoneValidation.toDisplayFormat(pkg.senderPhone),
                        Icons.Filled.Phone, colors.blue)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("RECIPIENT", color = colors.green, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    DetailRow("Name", pkg.recipientName, Icons.Filled.Person, colors.green)
                    DetailRow("Phone", PhoneValidation.toDisplayFormat(pkg.recipientPhone),
                        Icons.Filled.Phone, colors.green)
                }
            }
        } else {
            DetailRow("Sender", pkg.senderName, Icons.Filled.Person, colors.blue)
            DetailRow("Sender phone", PhoneValidation.toDisplayFormat(pkg.senderPhone),
                Icons.Filled.Phone, colors.blue)
            Spacer(Modifier.height(8.dp))
            DetailRow("Recipient", pkg.recipientName, Icons.Filled.Person, colors.green)
            DetailRow("Recipient phone", PhoneValidation.toDisplayFormat(pkg.recipientPhone),
                Icons.Filled.Phone, colors.green)
        }

        Spacer(Modifier.height(if (isWide) 8.dp else 10.dp))

        // ── Pickup / Destination (always full width) ──
        DetailRow("Pickup", pkg.fromAddress, Icons.Filled.MyLocation, colors.green)
        DetailRow("Destination", pkg.toAddress, Icons.Filled.LocationOn, colors.red)

        // ── Media carousel ──
        if (pkg.mediaUrls.isNotEmpty()) {
            Spacer(Modifier.height(if (isWide) 12.dp else 14.dp))
            Text("Media", color = colors.textSecondary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
            var showMaximized by remember { mutableStateOf(false) }
            MediaCarousel(mediaUrls = pkg.mediaUrls,
                onMaximize = { showMaximized = true })
            if (showMaximized) {
                Dialog(onDismissRequest = { showMaximized = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    FullScreenMediaViewer(mediaUrls = pkg.mediaUrls,
                        onClose = { showMaximized = false }, showClose = false)
                }
            }
        }

        // ── Weight & count ──
        if (pkg.weight.isNotBlank() || pkg.photoCount > 0) {
            Spacer(Modifier.height(if (isWide) 8.dp else 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (pkg.weight.isNotBlank())
                    DetailRow("Weight", pkg.weight, Icons.Filled.Scale, colors.textSecondary)
                if (pkg.photoCount > 0)
                    DetailRow("Photos", "${pkg.photoCount}", Icons.Filled.Photo,
                        colors.textSecondary)
            }
        }

        // ── Delivery code ──
        if (pkg.deliveryCode.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            DetailRow("Delivery code", pkg.deliveryCode, Icons.Filled.Key, colors.amber)
        }

        // ── Status history ──
        if (pkg.statusHistory.isNotEmpty()) {
            Spacer(Modifier.height(if (isWide) 12.dp else 14.dp))
            Text("Status History", color = colors.textPrimary,
                fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            pkg.statusHistory.reversed().forEach { update ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(colors.blue.copy(alpha = 0.3f)).offset(y = 4.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(update.message, color = colors.textPrimary, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmartTimeText(update.timestamp,
                                color = colors.textSecondary, fontSize = 10.sp)
                            if (update.location.isNotBlank()) {
                                Text(" • ${update.location}",
                                    color = colors.textSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
    }
}

@Composable
private fun DetailRow(label: String, value: String, icon: ImageVector, iconTint: Color) {
    val colors = LocalDriversColors.current
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = colors.textSecondary, fontSize = 10.sp)
            Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DetailTimeRow(label: String, iso: String, icon: ImageVector, iconTint: Color) {
    val colors = LocalDriversColors.current
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = colors.textSecondary, fontSize = 10.sp)
            SmartTimeText(iso.ifBlank { "N/A" },
                color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
