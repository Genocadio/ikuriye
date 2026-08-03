package com.gocavgo.ikuriye.ui.common

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.DriverProfile

// ── Profile Quick Menu ────────────────────────────────────────────────────────

@Composable
fun ProfileQuickMenu(
    profile: DriverProfile,
    onSettingsClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColorOverride: androidx.compose.ui.graphics.Color? = null
) {
    val colors = LocalDriversColors.current
    val accent = accentColorOverride ?: colors.blue

    Surface(
        modifier = modifier
            .width(230.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.divider)
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onProfileClick() }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    val avatarUrl = profile.avatarUrl
                    if (!avatarUrl.isNullOrBlank()) {
                        CachedAvatarImage(remoteUrl = avatarUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Filled.Person, null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(profile.name, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(profile.email, color = colors.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = colors.divider)
            ProfileMenuButton("Settings", Icons.Filled.Settings, onSettingsClick)
            ProfileMenuButton("Logout", Icons.AutoMirrored.Filled.Logout, onLogoutClick, danger = true)
        }
    }
}

@Composable
private fun ProfileMenuButton(label: String, icon: ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    val colors = LocalDriversColors.current
    val tint = if (danger) colors.red else colors.textPrimary
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = tint, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Settings Menu (driver + client combined, sections shown conditionally) ────

@Composable
fun SettingsMenu(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onClose: () -> Unit,
    isPipEnabled: Boolean? = null,
    onPipEnabledChange: ((Boolean) -> Unit)? = null,
    defaultPage: String? = null,
    onDefaultPageChange: ((String) -> Unit)? = null,
    keepScreenAwake: Boolean? = null,
    onKeepScreenAwakeChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current

    Surface(
        modifier = modifier
            .width(250.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.divider)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Theme", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeChoice("System", AppThemeMode.SYSTEM, themeMode, onThemeModeChange, Modifier.weight(1f))
                ThemeChoice("Light",  AppThemeMode.LIGHT,  themeMode, onThemeModeChange, Modifier.weight(1f))
                ThemeChoice("Dark",   AppThemeMode.DARK,   themeMode, onThemeModeChange, Modifier.weight(1f))
            }

            if (defaultPage != null || keepScreenAwake != null) {
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 14.dp))
                Text("Driver Settings", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            if (defaultPage != null) {
                Spacer(Modifier.height(4.dp))
                Text("Default Landing Page", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DriverPageOption("Trips",     "trips",              defaultPage, onDefaultPageChange, Modifier.weight(1f))
                    DriverPageOption("Current",   "packages/current",   defaultPage, onDefaultPageChange, Modifier.weight(1f))
                    DriverPageOption("Available", "packages/available", defaultPage, onDefaultPageChange, Modifier.weight(1f))
                }
            }

            if (keepScreenAwake != null && onKeepScreenAwakeChange != null) {
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ScreenLockLandscape, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Keep Screen Awake", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            if (keepScreenAwake) "Screen stays on in foreground or floating" else "Screen may turn off normally",
                            color = colors.textSecondary, fontSize = 11.sp
                        )
                    }
                    Switch(checked = keepScreenAwake, onCheckedChange = onKeepScreenAwakeChange, colors = SwitchDefaults.colors(checkedTrackColor = colors.blue))
                }
            }

            if (isPipEnabled != null && onPipEnabledChange != null) {
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 14.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PictureInPicture, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Picture-in-Picture", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        val context = LocalContext.current
                        val overlayGranted = Settings.canDrawOverlays(context)
                        Text(
                            when {
                                isPipEnabled && overlayGranted  -> "Enabled when leaving the app"
                                isPipEnabled && !overlayGranted -> "Needs overlay permission"
                                else -> "Disabled"
                            },
                            color = if (isPipEnabled && !overlayGranted) colors.red else colors.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(checked = isPipEnabled, onCheckedChange = onPipEnabledChange, colors = SwitchDefaults.colors(checkedTrackColor = colors.blue))
                }
            }
        }
    }
}

// ── Theme pill ────────────────────────────────────────────────────────────────

@Composable
fun ThemeChoice(
    label: String,
    mode: AppThemeMode,
    current: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current
    val selected = mode == current
    Surface(
        onClick = { onSelect(mode) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.green else colors.surfaceAlt,
        border = if (!selected) BorderStroke(1.dp, colors.divider) else null
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}

// ── Driver landing-page option pill ──────────────────────────────────────────

@Composable
fun DriverPageOption(
    label: String,
    value: String,
    current: String?,
    onSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalDriversColors.current
    val selected = current == value
    Surface(
        onClick = { onSelect?.invoke(value) },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.blue else colors.surfaceAlt,
        border = if (!selected) BorderStroke(1.dp, colors.divider) else null,
        enabled = onSelect != null
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}
