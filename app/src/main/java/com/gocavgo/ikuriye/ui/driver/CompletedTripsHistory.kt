package com.gocavgo.ikuriye.ui.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.CompletedTrip

@Composable
fun CompletedTripsHistorySection(trips: List<CompletedTrip>, modifier: Modifier = Modifier) {
    val colors = LocalDriversColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Completed Trips", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("${trips.size}", color = colors.textSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        trips.forEachIndexed { index, trip ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.green.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${trip.origin} → ${trip.destination}",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DirectionsCar, null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(trip.plateNumber, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = colors.green.copy(alpha = 0.1f)) {
                        Text("Done", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = colors.green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (index < trips.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}
