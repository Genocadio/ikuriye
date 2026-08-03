package com.gocavgo.ikuriye.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen width classification for adaptive layouts.
 */
enum class ScreenWidthClass {
    COMPACT,   // < 600dp (phones portrait)
    MEDIUM,    // 600-840dp (phones landscape, small tablets)
    EXPANDED   // > 840dp (tablets landscape)
}

/**
 * Detect the current screen width class.
 */
@Composable
fun currentScreenWidthClass(): ScreenWidthClass {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp.dp
    return when {
        widthDp < 600.dp -> ScreenWidthClass.COMPACT
        widthDp <= 840.dp -> ScreenWidthClass.MEDIUM
        else -> ScreenWidthClass.EXPANDED
    }
}

/**
 * Whether the current device is in landscape orientation.
 */
@Composable
fun isLandscape(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}

/**
 * Whether the screen is wide enough for a side navigation rail (> compact).
 */
@Composable
fun isWideScreen(): Boolean {
    return currentScreenWidthClass() != ScreenWidthClass.COMPACT
}

/**
 * Returns the optimal max-width for content panels on wide screens.
 */
@Composable
fun contentMaxWidth(): Dp {
    val config = LocalConfiguration.current
    return when {
        config.screenWidthDp >= 1200 -> 900.dp
        config.screenWidthDp >= 840 -> 720.dp
        config.screenWidthDp >= 600 -> config.screenWidthDp.dp * 0.85f
        else -> Dp.Unspecified
    }
}

/**
 * Returns the number of columns for a grid layout based on available width.
 */
@Composable
fun adaptiveGridColumns(itemMinWidth: Dp = 340.dp): Int {
    val config = LocalConfiguration.current
    val availableWidth = config.screenWidthDp.dp - 32.dp
    return maxOf(1, (availableWidth / itemMinWidth).toInt())
}

/**
 * Horizontal padding that adapts to screen size.
 */
@Composable
fun adaptiveHorizontalPadding(): Dp {
    return when (currentScreenWidthClass()) {
        ScreenWidthClass.COMPACT -> 16.dp
        ScreenWidthClass.MEDIUM -> 24.dp
        ScreenWidthClass.EXPANDED -> 32.dp
    }
}
