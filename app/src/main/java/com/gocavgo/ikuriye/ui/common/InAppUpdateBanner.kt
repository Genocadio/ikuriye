package com.gocavgo.ikuriye.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.AppUpdateManager

/**
 * Modern floating banner that appears ONLY AFTER an update has been silently
 * downloaded and SHA-256 verified in the background.
 */
@Composable
fun InAppUpdateBanner(
    readyUpdate: AppUpdateManager.UpdateReadyState?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current

    AnimatedVisibility(
        visible = readyUpdate != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        if (readyUpdate != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.blue.copy(alpha = 0.3f)),
                shadowElevation = 8.dp,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(colors.blue.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = "Update ready",
                            tint = colors.blue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ikuriye v${readyUpdate.updateInfo.versionName} Ready",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Downloaded & verified — ready to install",
                            color = colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.blue,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Install", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp).padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
