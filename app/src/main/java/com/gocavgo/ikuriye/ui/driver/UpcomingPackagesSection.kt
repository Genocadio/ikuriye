package com.gocavgo.ikuriye.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.Package
import com.gocavgo.ikuriye.data.TripStop
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors

private const val MAX_ROWS = 6

/**
 * Small card on the driver home that replaces the old completed-trips card.
 * It summarises — with only minimal info per row — the packages to drop off
 * and pick up at the next ("coming") stop of the active trip. Tapping any row
 * opens the full package-details popup.
 */
@Composable
fun UpcomingPackagesSection(
    stop: TripStop,
    allPackages: List<ClientPackage>,
    onPackageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current
    val dropoffs = stop.dropoffs
    val pickups = stop.pickups
    if (dropoffs.isEmpty() && pickups.isEmpty()) return

    val combined = dropoffs.map { it to false } + pickups.map { it to true }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider),
            shadowElevation = 1.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val visible = combined.take(MAX_ROWS)
                visible.forEachIndexed { index, (pkg, isPickup) ->
                    UpcomingPackageRow(pkg = pkg, isPickup = isPickup, allPackages = allPackages, onPackageClick = onPackageClick)
                    if (index < visible.lastIndex) {
                        HorizontalDivider(color = colors.divider, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
                if (combined.size > MAX_ROWS) {
                    Text(
                        "+${combined.size - MAX_ROWS} more",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingPackageRow(
    pkg: Package,
    isPickup: Boolean,
    allPackages: List<ClientPackage>,
    onPackageClick: (String) -> Unit
) {
    val colors = LocalDriversColors.current
    val detail = allPackages.find { it.id == pkg.id || it.packageUuid == pkg.id }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPackageClick(pkg.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isPickup) colors.blue.copy(alpha = 0.12f) else colors.amber.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPickup) Icons.Filled.Download else Icons.Filled.Upload,
                null,
                tint = if (isPickup) colors.blue else colors.amber,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pkg.label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isPickup) Icons.Filled.Person else Icons.Filled.PersonPin, null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isPickup) "Pick from ${detail?.senderName?.takeIf { it.isNotBlank() } ?: pkg.recipient}"
                    else "Hand to ${detail?.driverName?.takeIf { it.isNotBlank() } ?: pkg.recipient}",
                    color = colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (pkg.weight.isNotBlank() && pkg.weight != "Standard") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Straighten, null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(pkg.weight, color = colors.textSecondary, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = (if (isPickup) colors.blue else colors.amber).copy(alpha = 0.1f)) {
            Text(
                if (isPickup) "Pick up" else "Drop off",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                color = if (isPickup) colors.blue else colors.amber,
                fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}