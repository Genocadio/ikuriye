package com.gocavgo.ikuriye.ui.driver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gocavgo.ikuriye.network.BackendStorage
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.LocationSearchResult
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Driver create-trip modal (minimal fleetman-style flow):
 *  1. Search origin + destination locations
 *  2. Search routes (auto-runs once both are filled) and pick one
 *  3. Choose whether it's a return (reversed) trip
 *  4. Pick a departure time
 *  5. Create the trip
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTripPanel(
    onDismiss: () -> Unit,
    originSearch: String,
    destinationSearch: String,
    originResults: List<LocationSearchResult>,
    destinationResults: List<LocationSearchResult>,
    isSearchingLocations: Boolean,
    routeResults: List<BackendStorage.DriverRoute>,
    isSearchingRoutes: Boolean,
    selectedRoute: BackendStorage.DriverRoute?,
    isReversed: Boolean,
    departureTimeSeconds: Long?,
    isCreating: Boolean,
    error: String?,
    onOriginSearch: (String) -> Unit,
    onDestinationSearch: (String) -> Unit,
    onOriginSelect: (LocationSearchResult) -> Unit,
    onDestinationSelect: (LocationSearchResult) -> Unit,
    onOriginClear: () -> Unit,
    onDestinationClear: () -> Unit,
    onSearchRoutes: () -> Unit,
    onRouteSelect: (BackendStorage.DriverRoute) -> Unit,
    onReversedChange: (Boolean) -> Unit,
    onDepartureTimeChange: (Long) -> Unit,
    onCreateTrip: () -> Unit
) {
    val colors = LocalDriversColors.current
    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { transitionState.targetState = true }

    // Auto-run the route search once both origin and destination are filled.
    LaunchedEffect(originSearch, destinationSearch) {
        if (originSearch.isNotBlank() && destinationSearch.isNotBlank()) {
            kotlinx.coroutines.delay(400)
            onSearchRoutes()
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val departureMillis = departureTimeSeconds?.times(1000) ?: System.currentTimeMillis()
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    Dialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isCreating,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = scaleIn(initialScale = 0.9f) + fadeIn(),
                exit = scaleOut(targetScale = 0.9f) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .widthIn(max = 520.dp)
                        .statusBarsPadding()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {} // block click-through to scrim
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = colors.background,
                    tonalElevation = 4.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 640.dp)
                            .navigationBarsPadding()
                    ) {
                        // ── Header ─────────────────────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = colors.surfaceAlt, modifier = Modifier.size(32.dp)) {
                                IconButton(onClick = { if (!isCreating) onDismiss() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Create Trip", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    "Search origin & destination, pick a route",
                                    color = colors.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // ── 1. Origin & destination ───────────────────
                            item {
                                SectionLabel("1. Where are you coming from?", colors)
                                TripLocationField(
                                    value = originSearch,
                                    label = "Origin",
                                    placeholder = "Search origin… e.g. Kigali",
                                    results = originResults,
                                    isSearching = isSearchingLocations,
                                    onValueChange = onOriginSearch,
                                    onSelect = onOriginSelect,
                                    onClear = onOriginClear,
                                    colors = colors
                                )
                            }
                            item {
                                SectionLabel("2. Where are you heading?", colors)
                                TripLocationField(
                                    value = destinationSearch,
                                    label = "Destination",
                                    placeholder = "Search destination… e.g. Musanze",
                                    results = destinationResults,
                                    isSearching = isSearchingLocations,
                                    onValueChange = onDestinationSearch,
                                    onSelect = onDestinationSelect,
                                    onClear = onDestinationClear,
                                    colors = colors
                                )
                            }

                            // ── 2. Route selection ────────────────────────
                            item {
                                SectionLabel("3. Select a route", colors)
                                when {
                                    originSearch.isBlank() || destinationSearch.isBlank() -> Text(
                                        "Fill in both origin and destination to search routes.",
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    isSearchingRoutes -> Row(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Searching routes…", color = colors.textSecondary, fontSize = 12.sp)
                                    }
                                    routeResults.isEmpty() -> Column(
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    ) {
                                        Text(
                                            "No routes found from \"$originSearch\" to \"$destinationSearch\".",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            "Try a different origin or destination.",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // Pinned selection shown first (if any), then the full list so
                                        // the driver can switch routes without re-searching.
                                        if (selectedRoute != null) {
                                            SelectedRouteCard(route = selectedRoute, colors = colors)
                                        }
                                        Text(
                                            if (selectedRoute != null) "Or choose another route:" else "Choose a route:",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        routeResults.forEach { route ->
                                            RouteOptionCard(
                                                route = route,
                                                selected = selectedRoute?.id == route.id,
                                                onClick = { onRouteSelect(route) },
                                                colors = colors
                                            )
                                        }
                                    }
                                }
                            }

                            // ── 3. Reversed (return trip) ─────────────────
                            item {
                                SectionLabel("4. Return trip?", colors)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surface,
                                    border = BorderStroke(1.dp, colors.divider)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Refresh, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Reverse the route", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text(
                                                if (selectedRoute != null)
                                                    "${selectedRoute.destinationName ?: "Destination"} → ${selectedRoute.originName ?: "Origin"}"
                                                else "Travel the route in the opposite direction",
                                                color = colors.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Switch(
                                            checked = isReversed,
                                            onCheckedChange = onReversedChange,
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = Color.White,
                                                checkedTrackColor = colors.green,
                                                uncheckedThumbColor = Color.White,
                                                uncheckedTrackColor = colors.surfaceAlt,
                                                uncheckedBorderColor = colors.divider
                                            )
                                        )
                                    }
                                }
                            }

                            // ── 4. Departure time ─────────────────────────
                            item {
                                SectionLabel("5. Departure time", colors)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TimeChip(
                                        icon = Icons.Filled.Event,
                                        text = dateFormat.format(Date(departureMillis)),
                                        onClick = { if (!isCreating) showDatePicker = true },
                                        colors = colors,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TimeChip(
                                        icon = Icons.Filled.Schedule,
                                        text = timeFormat.format(Date(departureMillis)),
                                        onClick = { if (!isCreating) showTimePicker = true },
                                        colors = colors,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // ── Error + CTA ───────────────────────────────
                            if (error != null) {
                                item {
                                    Text(error, color = colors.red, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            item {
                                Spacer(Modifier.height(2.dp))
                                Button(
                                    onClick = onCreateTrip,
                                    enabled = !isCreating && selectedRoute != null && departureTimeSeconds != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.green,
                                        contentColor = Color.White,
                                        disabledContainerColor = colors.surfaceAlt,
                                        disabledContentColor = colors.textSecondary
                                    )
                                ) {
                                    if (isCreating) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Creating trip…", fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("Create Trip", fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Date picker ─────────────────────────────────────────────────────────
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = departureMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= System.currentTimeMillis() - 24 * 60 * 60 * 1000
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { pickedMillis ->
                        // Picked date is UTC-midnight; apply it as the local date keeping the existing time.
                        val localDate = LocalDate.ofInstant(Instant.ofEpochMilli(pickedMillis), ZoneOffset.UTC)
                        val cal = Calendar.getInstance().apply { timeInMillis = departureMillis }
                        cal.set(Calendar.YEAR, localDate.year)
                        cal.set(Calendar.MONTH, localDate.monthValue - 1)
                        cal.set(Calendar.DAY_OF_MONTH, localDate.dayOfMonth)
                        onDepartureTimeChange(cal.timeInMillis / 1000)
                    }
                    showDatePicker = false
                }) { Text("OK", color = colors.blue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = colors.textSecondary) }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }

    // ── Time picker ─────────────────────────────────────────────────────────
    if (showTimePicker) {
        val cal = Calendar.getInstance().apply { timeInMillis = departureMillis }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Departure time", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = departureMillis }
                    c.set(Calendar.HOUR_OF_DAY, timeState.hour)
                    c.set(Calendar.MINUTE, timeState.minute)
                    c.set(Calendar.SECOND, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    onDepartureTimeChange(c.timeInMillis / 1000)
                    showTimePicker = false
                }) { Text("OK", color = colors.blue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel", color = colors.textSecondary) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String, colors: com.gocavgo.ikuriye.ui.theme.DriversColors) {
    Text(
        text,
        color = colors.textPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun TripLocationField(
    value: String,
    label: String,
    placeholder: String,
    results: List<LocationSearchResult>,
    isSearching: Boolean,
    onValueChange: (String) -> Unit,
    onSelect: (LocationSearchResult) -> Unit,
    onClear: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    var isFocused by remember { mutableStateOf(false) }
    val showDropdown = isFocused && value.isNotBlank() && (results.isNotEmpty() || isSearching)
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (value.isNotBlank()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear",
                        modifier = Modifier.size(18.dp).clickable { onClear() },
                        tint = colors.textSecondary
                    )
                }
            }
        )
        if (showDropdown) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 60.dp)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (isSearching && results.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching…", color = colors.textSecondary, fontSize = 13.sp)
                        }
                    }
                    results.forEach { loc ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surface,
                            onClick = { onSelect(loc) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Place, null, tint = colors.green, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(loc.displayName(), color = colors.textPrimary, fontSize = 14.sp)
                                    val sub = loc.subtitle()
                                    if (sub.isNotBlank()) {
                                        Text(sub, color = colors.textSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (!isSearching && results.isEmpty() && value.isNotBlank()) {
                        Text(
                            "No matching locations — you can still search routes with this text.",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedRouteCard(
    route: BackendStorage.DriverRoute,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.green.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, colors.green.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Timeline, null, tint = colors.green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(route.displayLabel(), color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(routeDetail(route), color = colors.textSecondary, fontSize = 11.sp)
            }
            Text(
                "✓ Selected",
                color = colors.green,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RouteOptionCard(
    route: BackendStorage.DriverRoute,
    selected: Boolean,
    onClick: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.blue.copy(alpha = 0.1f) else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.blue else colors.divider),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Timeline, null, tint = colors.blue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(route.displayLabel(), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(routeDetail(route), color = colors.textSecondary, fontSize = 11.sp)
            }
            Icon(Icons.Filled.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun TimeChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        onClick = onClick,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = colors.blue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun routeDetail(route: BackendStorage.DriverRoute): String {
    val parts = mutableListOf<String>()
    route.distanceMeters?.let { d ->
        if (d >= 1000) parts.add(String.format(Locale.getDefault(), "%.1f km", d / 1000.0))
        else parts.add("$d m")
    }
    route.estimatedDurationSeconds?.let { sec ->
        val h = sec / 3600
        val m = (sec % 3600) / 60
        parts.add(if (h > 0) "${h}h ${m}m" else "${m}m")
    }
    route.routePrice?.let { p -> parts.add("RWF ${p.toInt()}") }
    if (route.cityRoute) parts.add("City route")
    return parts.joinToString(" • ").ifBlank { "Route #${route.id}" }
}