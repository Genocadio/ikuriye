package com.gocavgo.ikuriye.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.data.Notice

/**
 * A compact, anchored notification dropdown — styled like the profile quick
 * menu that appears when tapping the user avatar. Renders a dim scrim behind
 * a small floating card pinned below the bell icon (top-end corner).
 *
 * There is no "mark all read" action: each notification offers a single
 * [Notice] tap that marks the notice as read and navigates to the related
 * package details.
 */
@Composable
fun NoticesPanel(
    notices: List<Notice>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onNoticeClick: (Notice) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val accentColor = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize()) {
        // Dim scrim behind the dropdown — tap to dismiss
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.4f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss
                    )
            )
        }

        // Floating card, pinned below the bell at the top-end
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 62.dp, end = 16.dp),
            enter = expandVertically(tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {} // block click-through to the scrim
                    ),
                shape = RoundedCornerShape(18.dp),
                color = colors.surface,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ── Notice list ────────────────────────────────────────
                    if (notices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 150.dp)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.NotificationsNone,
                                    contentDescription = null,
                                    tint = colors.outlineVariant,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "No notifications yet",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Updates about your packages will appear here",
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                        ) {
                            items(notices, key = { it.id }) { notice ->
                                NoticeItem(
                                    notice = notice,
                                    onClick = { onNoticeClick(notice) },
                                    colors = colors,
                                    accentColor = accentColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeItem(
    notice: Notice,
    onClick: () -> Unit,
    colors: androidx.compose.material3.ColorScheme,
    accentColor: androidx.compose.ui.graphics.Color
) {
    val isUnread = notice.viewerReadAt == null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isUnread) accentColor.copy(alpha = 0.06f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Event icon in a tinted circle
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isUnread) accentColor.copy(alpha = 0.14f) else colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    noticeIcon(notice.eventType),
                    contentDescription = null,
                    tint = if (isUnread) accentColor else colors.outlineVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title + time (top right) + unread dot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice.title,
                        fontSize = 12.sp,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isUnread) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatTime(notice.createdAt),
                        fontSize = 10.sp,
                        color = colors.outlineVariant
                    )
                }
                Text(
                    notice.message,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f), thickness = 1.dp)
}

private fun noticeIcon(eventType: String): ImageVector = when {
    eventType.contains("DELIVERED", ignoreCase = true) -> Icons.Outlined.CheckCircle
    eventType.contains("CANCELLED", ignoreCase = true) -> Icons.Outlined.Cancel
    eventType.contains("TRANSFER", ignoreCase = true) -> Icons.Outlined.SwapHoriz
    eventType.contains("CREATED", ignoreCase = true) -> Icons.Outlined.AddCircle
    eventType.contains("PICKED_UP", ignoreCase = true) -> Icons.Outlined.Inventory2
    eventType.contains("IN_TRANSIT", ignoreCase = true) -> Icons.Outlined.LocalShipping
    else -> Icons.Outlined.Notifications
}
