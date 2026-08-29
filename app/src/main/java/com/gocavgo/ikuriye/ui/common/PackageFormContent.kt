package com.gocavgo.ikuriye.ui.common

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.StatusUpdate
import com.gocavgo.ikuriye.ui.PhoneInput
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.CreatePackageFormState
import com.gocavgo.ikuriye.viewmodel.MediaUploadState

// ── Step definitions ──────────────────────────────────────────────────────────

data class StepInfo(val icon: ImageVector)

val STEPS = listOf(
    StepInfo(Icons.Filled.Place),        // 0 — Location + People
    StepInfo(Icons.Filled.Description),   // 1 — Package Details
    StepInfo(Icons.Filled.AttachFile),    // 2 — Media
    StepInfo(Icons.Filled.CheckCircle)    // 3 — Summary & Send
)

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun PackageFormContent(
    formState: CreatePackageFormState,
    onFormFieldChange: (String, String) -> Unit,
    onFragileChange: (Boolean) -> Unit,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    stepIsValid: Boolean,
    isLastStep: Boolean,
    showSenderFields: Boolean = false,
    isSubmitting: Boolean = false,
    userSearchResults: List<SearchUsersQuery.SearchUser> = emptyList(),
    onUserSearch: (String) -> Unit = {},
    onClearUserSearch: () -> Unit = {},
    mediaUploads: List<MediaUploadState> = emptyList(),
    onAddMedia: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onCancelUpload: (String) -> Unit = {},
    onRemoveMedia: (String) -> Unit = {},
    onSubmit: (ClientPackage) -> Unit,
    selectedTransferRuleType: String? = null,
    onTransferRuleTypeChange: (String) -> Unit = {},
    driverSearchQuery: String = "",
    onDriverSearchQueryChange: (String) -> Unit = {},
    transferMatchUserId: String? = null,
    transferMatchUserName: String? = null,
    onSelectDriver: (SearchUsersQuery.SearchUser) -> Unit = {},
    onClearDriver: () -> Unit = {},
    showErrors: Boolean = false
) {
    val context = LocalContext.current
    val colors  = LocalDriversColors.current

    var showFromDropdown       by remember { mutableStateOf(false) }
    var showToDropdown         by remember { mutableStateOf(false) }
    var showCategoryDropdown   by remember { mutableStateOf(false) }
    var showFromRecipientDropdown by remember { mutableStateOf(false) } // search results inside location step
    var contactPickerTarget by remember { mutableStateOf("") } // "sender" or "recipient"
    val currentContactPickerTarget by rememberUpdatedState(contactPickerTarget)

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
            onAddMedia("camera_${java.util.UUID.randomUUID()}.jpg", stream.toByteArray(), "image/jpeg")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) photoLauncher.launch()
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val size = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                cursor.moveToFirst()
                if (idx >= 0) cursor.getLong(idx) else -1L
            } ?: -1L
            if (size > 50L * 1024 * 1024) {
                Toast.makeText(context, "File too large (max 50 MB)", Toast.LENGTH_LONG).show()
                return@rememberLauncherForActivityResult
            }
            val bytes    = context.contentResolver.openInputStream(uri)?.readBytes()
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) onAddMedia(uri.toString(), bytes, mimeType)
        }
    }

    // ── Contacts picker ────────────────────────────────────────────────────
    val contactsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val contactUri = data.data ?: return@rememberLauncherForActivityResult
            // Query contact name (grant-URI-permission allows this)
            var name = ""
            var phone = ""
            context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                    val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    // Query phone number — needs READ_CONTACTS on some devices (Samsung)
                    // Wrap in try-catch so it gracefully falls back to name-only if permission is denied
                    try {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { phones ->
                            if (phones.moveToFirst()) {
                                phone = phones.getString(phones.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                            }
                        }
                    } catch (_: SecurityException) {
                        // Permission not granted — name only is fine
                    }
                }
            }
            // Populate fields
            val target = currentContactPickerTarget
            when (target) {
                "sender" -> {
                    onFormFieldChange("senderName", name)
                    onFormFieldChange("senderPhone", phone)
                }
                "recipient" -> {
                    onFormFieldChange("recipientName", name)
                    onFormFieldChange("recipientPhone", phone)
                }
            }
        }
    }

    // ── Contacts permission launcher ──────────────────────────────────────
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            contactsLauncher.launch(Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
            })
        } else {
            Toast.makeText(context, "Permission needed to read contacts", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Camera capture (photo step) ────────────────────────────────────────
    val onLaunchCamera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            photoLauncher.launch()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val allCategories = remember {
        listOf("Electronics", "Clothing", "Food & Perishables", "Documents", "Medical Supplies",
            "Sports Equipment", "Auto Parts", "Books & Stationery", "Fragile Items", "Other")
    }
    val filteredCategories = remember(formState.category) { allCategories.filter { it.contains(formState.category, ignoreCase = true) } }

    val rwandaLocations = remember {
        listOf(
            "Kicukiro, Kigali", "Nyarugenge, Kigali", "Remera, Kigali", "Kimironko, Kigali",
            "Nyabugogo, Kigali", "Gikondo, Kigali", "Kanombe, Kigali", "Niboye, Kigali",
            "Gatenga, Kigali", "Gahanga, Kigali", "Kabeza, Kigali", "Nyamirambo, Kigali",
            "Kimisagara, Kigali", "Muhima, Kigali", "Nyakabanda, Kigali", "Kiyovu, Kigali",
            "Rugando, Kigali", "Kacyiru, Kigali", "Gisozi, Kigali", "Kibagabaga, Kigali",
            "Kimihurura, Kigali", "Nyarutarama, Kigali", "Kagarama, Kigali", "Biryogo, Kigali",
            "Busanza, Kigali", "Giporoso, Kigali", "Kicukiro Center, Kigali",
            "Musanze Town", "Ruhengeri, Musanze", "Kinigi, Musanze",
            "Byumba, Gicumbi", "Rulindo Town", "Burera", "Gakenke",
            "Cyumba, Gicumbi", "Miyove, Gicumbi", "Nemba, Gicumbi",
            "Huye Town", "Butare, Huye", "Nyanza Town", "Nyamagabe",
            "Gisagara", "Muhanga Town", "Ruhango", "Kamonyi",
            "Rubavu Town", "Gisenyi, Rubavu", "Rusizi Town", "Kamembe, Rusizi",
            "Karongi Town", "Kibuye, Karongi", "Nyamasheke", "Rutsiro",
            "Ngororero", "Nyabihu", "Rwamagana Town", "Nyagatare Town",
            "Bugesera", "Ngoma Town", "Kayonza Town", "Gatsibo", "Kirehe",
            "Akagera National Park", "Volcanoes National Park", "Nyungwe National Park",
            "Lake Kivu", "Lake Muhazi", "Lake Burera", "Lake Ruhondo"
        ).distinct()
    }
    val filteredFrom = remember(formState.fromAddress) { rwandaLocations.filter { it.contains(formState.fromAddress, ignoreCase = true) } }
    val filteredTo   = remember(formState.toAddress)   { rwandaLocations.filter { it.contains(formState.toAddress, ignoreCase = true) } }

    // ── Validation per step (progressive — only validates what's been revealed) ──
    val locationRevealStage = when {
        formState.fromAddress.isBlank() -> 0
        formState.recipientName.isBlank() -> 1
        formState.toAddress.isBlank() -> 2
        else -> 3
    }
    val detailsRevealStage = when {
        formState.category.isBlank() -> 0
        formState.description.isBlank() -> 1
        else -> 2
    }
    val onLaunchContactPicker: (String) -> Unit = { target ->
        contactPickerTarget = target
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            contactsLauncher.launch(Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
            })
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // ── Scroll state ──
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when any dropdown opens, so suggestions are fully visible
    LaunchedEffect(showFromDropdown, showToDropdown, showCategoryDropdown) {
        if (showFromDropdown || showToDropdown || showCategoryDropdown) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── Animated step content ──────────────────────────────────────────
        Box(modifier = Modifier.wrapContentHeight()) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 4 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 4 })
                    } else {
                        (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 4 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 4 })
                    }
                },
                label = "createStep"
            ) { step ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── Per-step header (title + hint) ──────────────────────
                    val stepHeader = when (step) {
                        0 -> "Pickup & delivery" to "Where is the package going?"
                        1 -> "Package details" to "What are you sending?"
                        2 -> "Photos" to "Attach a few photos (optional)"
                        else -> null
                    }
                    if (stepHeader != null) {
                        StepHeader(title = stepHeader.first, subtitle = stepHeader.second, colors = colors)
                    }

                    when (step) {
                        0 -> LocationStep(
                            formState = formState,
                            onFormFieldChange = onFormFieldChange,
                            showSenderFields = showSenderFields,
                            userSearchResults = userSearchResults,
                            onUserSearch = onUserSearch,
                            onClearUserSearch = onClearUserSearch,
                            showRecipientSearchResults = showFromRecipientDropdown,
                            onToggleRecipientSearch = { showFromRecipientDropdown = it },
                            showFromDropdown = showFromDropdown,
                            showToDropdown = showToDropdown,
                            filteredFrom = filteredFrom,
                            filteredTo = filteredTo,
                            onShowFromDropdown = { showFromDropdown = it; if (it) showToDropdown = false },
                            onShowToDropdown = { showToDropdown = it; if (it) showFromDropdown = false },
                            locationRevealStage = locationRevealStage,
                            onPickContact = onLaunchContactPicker,
                            showErrors = showErrors,
                            colors = colors
                        )
                        1 -> DetailsStep(
                            formState = formState,
                            onFormFieldChange = onFormFieldChange,
                            onFragileChange = onFragileChange,
                            showCategoryDropdown = showCategoryDropdown,
                            filteredCategories = filteredCategories,
                            onShowCategoryDropdown = { showCategoryDropdown = it },
                            detailsRevealStage = detailsRevealStage,
                            showErrors = showErrors,
                            colors = colors
                        )
                        2 -> MediaStep(
                            mediaUploads = mediaUploads,
                            onAddMedia = onAddMedia,
                            onCancelUpload = onCancelUpload,
                            onRemoveMedia = onRemoveMedia,
                            onGalleryClick = { galleryLauncher.launch("*/*") },
                            onCameraClick = onLaunchCamera,
                            colors = colors
                        )
                        3 -> SummaryStep(
                            formState = formState,
                            mediaUploads = mediaUploads,
                            showSenderFields = showSenderFields,
                            selectedTransferRuleType = selectedTransferRuleType,
                            onTransferRuleTypeChange = onTransferRuleTypeChange,
                            driverSearchQuery = driverSearchQuery,
                            onDriverSearchQueryChange = onDriverSearchQueryChange,
                            showDriverSearchResults = driverSearchQuery.startsWith("@") && userSearchResults.isNotEmpty(),
                            userSearchResults = userSearchResults,
                            onSelectDriver = onSelectDriver,
                            onClearDriver = onClearDriver,
                            transferMatchUserId = transferMatchUserId,
                            transferMatchUserName = transferMatchUserName,
                            colors = colors
                        )
                    }

                    // ── Bottom spacer for keyboard clearance ──────────────
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Icons-Only Step Indicator (clickable to go back) ──────────────────────────

@Composable
fun IconsStepIndicator(
    currentStep: Int,
    maxReachedStep: Int = currentStep,
    totalSteps: Int,
    onStepClick: (Int) -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        STEPS.forEachIndexed { idx, step ->
            val isCompleted = idx < maxReachedStep
            val isActive = idx == currentStep

            // Animate the active circle size
            val circleSize by animateDpAsState(
                targetValue = if (isActive) 34.dp else 28.dp,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "circleSize_$idx"
            )
            // Animate a glowing ring alpha around the active step
            val ringAlpha by animateFloatAsState(
                targetValue = if (isActive) 0.25f else 0f,
                animationSpec = tween(300),
                label = "ringAlpha_$idx"
            )
            val ringColor = colors.blue

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(42.dp) // fixed outer box keeps spacing stable
            ) {
                // Glow ring drawn behind
                if (ringAlpha > 0f) {
                    Canvas(modifier = Modifier.size(42.dp)) {
                        drawCircle(
                            color = ringColor.copy(alpha = ringAlpha),
                            radius = size.minDimension / 2f
                        )
                    }
                }

                Surface(
                    onClick = { if (idx <= maxReachedStep) onStepClick(idx) },
                    shape = CircleShape,
                    color = when {
                        isCompleted -> colors.green
                        isActive -> colors.blue
                        else -> colors.divider
                    },
                    modifier = Modifier.size(circleSize),
                    shadowElevation = if (isActive) 6.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isCompleted) {
                            Icon(Icons.Filled.Check, null, tint = Color.White,
                                modifier = Modifier.size(if (isActive) 17.dp else 14.dp))
                        } else {
                            Icon(step.icon, null,
                                tint = if (isActive) Color.White else colors.textSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(if (isActive) 17.dp else 14.dp))
                        }
                    }
                }
            }

            // Animated connector line — fills with green as steps are completed
            if (idx < totalSteps - 1) {
                val fillFraction by animateFloatAsState(
                    targetValue = if (idx < maxReachedStep) 1f else 0f,
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                    label = "connector_$idx"
                )
                val trackColor = colors.divider
                val fillColor = colors.green
                Canvas(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                ) {
                    // Track
                    drawLine(
                        color = trackColor,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round
                    )
                    // Fill
                    if (fillFraction > 0f) {
                        drawLine(
                            color = fillColor,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                            end = androidx.compose.ui.geometry.Offset(size.width * fillFraction, size.height / 2),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}

// ── Step 0: Location → People → Destination (progressive) ─────────────────────

@Composable
private fun LocationStep(
    formState: CreatePackageFormState,
    onFormFieldChange: (String, String) -> Unit,
    showSenderFields: Boolean,
    userSearchResults: List<SearchUsersQuery.SearchUser>,
    onUserSearch: (String) -> Unit,
    onClearUserSearch: () -> Unit,
    showRecipientSearchResults: Boolean,
    onToggleRecipientSearch: (Boolean) -> Unit,
    showFromDropdown: Boolean,
    showToDropdown: Boolean,
    filteredFrom: List<String>,
    filteredTo: List<String>,
    onShowFromDropdown: (Boolean) -> Unit,
    onShowToDropdown: (Boolean) -> Unit,
    locationRevealStage: Int,
    onPickContact: (String) -> Unit,
    showErrors: Boolean,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    val hasSender = showSenderFields && formState.senderName.isNotBlank()
    // ── Editing state for compact/expanded fields ──
    var editingFrom by remember { mutableStateOf(formState.fromAddress.isBlank()) }
    var editingTo   by remember { mutableStateOf(formState.toAddress.isBlank()) }
    // Split recipient editing: name and phone can collapse independently
    var editingRecipientName by remember { mutableStateOf(formState.recipientName.isBlank()) }
    var editingRecipientPhone by remember { mutableStateOf(formState.recipientPhone.isBlank()) }
    val showCompactCard = !editingRecipientName && !editingRecipientPhone &&
        formState.recipientName.isNotBlank() && formState.recipientPhone.isNotBlank()
    // Split sender editing: name and phone can collapse independently (driver mode)
    var editingSenderName by remember { mutableStateOf(formState.senderName.isBlank()) }
    var editingSenderPhone by remember { mutableStateOf(formState.senderPhone.isBlank()) }
    val showCompactSender = !editingSenderName && !editingSenderPhone &&
        formState.senderName.isNotBlank() && formState.senderPhone.isNotBlank()

    // ── Sender fields (driver mode only, shown first) ──
    if (showSenderFields) {
        FormLabel("Sender")
        if (showCompactSender) {
            CompactRecipientCard(
                name = formState.senderName,
                phone = formState.senderPhone,
                onEdit = { editingSenderName = true; editingSenderPhone = true },
                colors = colors
            )
        } else {
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(12.dp)) {
                    // ── Sender name ────────────────────────────────────────
                    if (!editingSenderName && formState.senderName.isNotBlank()) {
                        CompactFieldChip(
                            label = formState.senderName,
                            icon = Icons.Filled.Person,
                            onEdit = { editingSenderName = true },
                            colors = colors
                        )
                    } else {
                        FormTextField(
                            value = formState.senderName,
                            onValueChange = { v ->
                                onFormFieldChange("senderName", v)
                                if (v.startsWith("@")) { onUserSearch(v.removePrefix("@")) }
                                else { onClearUserSearch() }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = "Sender name *",
                            leadingIcon = Icons.Filled.Person,
                            trailingIcon = {
                                IconButton(onClick = { onPickContact("sender") }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.Contacts, "Pick from contacts", tint = colors.blue, modifier = Modifier.size(20.dp))
                                }
                            },
                            singleLine = true,
                            colors = colors
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // ── Sender phone ───────────────────────────────────────
                    if (!editingSenderPhone && formState.senderPhone.isNotBlank()) {
                        CompactFieldChip(
                            label = formState.senderPhone,
                            icon = Icons.Filled.Phone,
                            onEdit = { editingSenderPhone = true },
                            colors = colors
                        )
                    } else {
                        Box(modifier = Modifier.onFocusChanged { focusState ->
                            // When phone gains focus & name is filled → collapse name
                            if (focusState.isFocused && formState.senderName.isNotBlank()) {
                                editingSenderName = false
                            }
                        }) {
                            PhoneInput(value = formState.senderPhone,
                                onValueChange = { onFormFieldChange("senderPhone", it) },
                                modifier = Modifier.fillMaxWidth(), label = "Sender phone")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // When pickup location gains focus & sender phone is filled → collapse sender phone
    LaunchedEffect(showFromDropdown) {
        if (showFromDropdown && formState.senderPhone.isNotBlank()) {
            editingSenderPhone = false
        }
    }

    // ── Stage 1: Pickup location ──
    FormLabel("Pickup location")
    CompactLocationField(
        value = formState.fromAddress,
        isEditing = editingFrom,
        icon = { Icon(Icons.Filled.MyLocation, null, tint = colors.green) },
        placeholder = if (hasSender) "From where?" else "Pickup location *",
        showDropdown = showFromDropdown,
        filteredOptions = filteredFrom,
        onValueChange = { v ->
            onFormFieldChange("fromAddress", v)
            if (v.isNotBlank()) onShowFromDropdown(true)
        },
        onFocusChange = { if (it) { onShowFromDropdown(true); onShowToDropdown(false) } },
        onSelectOption = { loc -> onFormFieldChange("fromAddress", loc); onShowFromDropdown(false); editingFrom = false },
        onExpand = { editingFrom = true },
        isError = showErrors && formState.fromAddress.isBlank(),
        errorText = if (showErrors && formState.fromAddress.isBlank()) "Pickup location is required" else null,
        colors = colors
    )

    // ── Stage 2: Recipient (appears after pickup is selected) ──
    AnimatedVisibility(visible = locationRevealStage >= 1, enter = fadeIn() + expandVertically()) {
        Column {
            Spacer(Modifier.height(8.dp))
            FormLabel("Who's receiving?")
            if (showCompactCard) {
                CompactRecipientCard(
                    name = formState.recipientName,
                    phone = formState.recipientPhone,
                    onEdit = { editingRecipientName = true; editingRecipientPhone = true },
                    colors = colors
                )
            } else {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface,
                    border = BorderStroke(1.dp, colors.divider)) {
                    Column(Modifier.padding(12.dp)) {
                        // ── Name field ────────────────────────────────────────
                        if (!editingRecipientName && formState.recipientName.isNotBlank()) {
                            // Compact name chip
                            CompactFieldChip(
                                label = formState.recipientName,
                                icon = Icons.Filled.Person,
                                onEdit = { editingRecipientName = true },
                                colors = colors
                            )
                        } else {
                            FormTextField(
                                value = formState.recipientName,
                                onValueChange = { v ->
                                    onFormFieldChange("recipientName", v)
                                    if (v.startsWith("@")) { onUserSearch(v.removePrefix("@"));
                                        onToggleRecipientSearch(true) }
                                    else { onToggleRecipientSearch(false); onClearUserSearch() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = "Recipient name *",
                                leadingIcon = Icons.Filled.Person,
                                trailingIcon = {
                                    IconButton(onClick = { onPickContact("recipient") }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Filled.Contacts, "Pick from contacts", tint = colors.blue, modifier = Modifier.size(20.dp))
                                    }
                                },
                                singleLine = true,
                                isError = showErrors && formState.recipientName.isBlank(),
                                errorText = if (showErrors && formState.recipientName.isBlank()) "Recipient name is required" else null,
                                colors = colors
                            )
                            if (showRecipientSearchResults && userSearchResults.isNotEmpty()) {
                                FormSearchDropdown(userSearchResults) { user ->
                                    onFormFieldChange("recipientName", "${user.firstName} ${user.lastName}")
                                    onFormFieldChange("recipientPhone", user.phone ?: "")
                                    onToggleRecipientSearch(false); onClearUserSearch()
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // ── Phone field ───────────────────────────────────────
                        if (!editingRecipientPhone && formState.recipientPhone.isNotBlank()) {
                            CompactFieldChip(
                                label = formState.recipientPhone,
                                icon = Icons.Filled.Phone,
                                onEdit = { editingRecipientPhone = true },
                                colors = colors
                            )
                        } else {
                            Box(modifier = Modifier.onFocusChanged { focusState ->
                                // When phone field gains focus & name is filled → collapse name
                                if (focusState.isFocused && formState.recipientName.isNotBlank()) {
                                    editingRecipientName = false
                                }
                            }) {
                                PhoneInput(value = formState.recipientPhone,
                                    onValueChange = { onFormFieldChange("recipientPhone", it) },
                                    modifier = Modifier.fillMaxWidth(), label = "Recipient phone")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Stage 3: Delivery location (appears after recipient name is filled) ──
    AnimatedVisibility(visible = locationRevealStage >= 2, enter = fadeIn() + expandVertically()) {
        Column {
            Spacer(Modifier.height(8.dp))
            FormLabel("Delivery location")
            CompactLocationField(
                value = formState.toAddress,
                isEditing = editingTo,
                icon = { Icon(Icons.Filled.LocationOn, null, tint = colors.red) },
                placeholder = "Deliver to *",
                showDropdown = showToDropdown,
                filteredOptions = filteredTo,
                onValueChange = { v ->
                    onFormFieldChange("toAddress", v)
                    if (v.isNotBlank()) onShowToDropdown(true)
                },
                onFocusChange = { if (it) { onShowToDropdown(true); onShowFromDropdown(false) } },
        onSelectOption = { loc -> onFormFieldChange("toAddress", loc); onShowToDropdown(false); editingTo = false },
        onExpand = { editingTo = true },
        isError = showErrors && formState.toAddress.isBlank(),
        errorText = if (showErrors && formState.toAddress.isBlank()) "Delivery location is required" else null,
        colors = colors
    )
            // When delivery location gains focus & phone is filled → collapse phone
            LaunchedEffect(showToDropdown) {
                if (showToDropdown && formState.recipientPhone.isNotBlank()) {
                    editingRecipientPhone = false
                }
            }
        }
    }
}

// ── Step 1: Package Details (progressive) ─────────────────────────────────────

@Composable
private fun DetailsStep(
    formState: CreatePackageFormState,
    onFormFieldChange: (String, String) -> Unit,
    onFragileChange: (Boolean) -> Unit,
    showCategoryDropdown: Boolean,
    filteredCategories: List<String>,
    onShowCategoryDropdown: (Boolean) -> Unit,
    detailsRevealStage: Int,
    showErrors: Boolean,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    var editingCategory by remember { mutableStateOf(formState.category.isBlank()) }

    // ── Stage 1: Category ──
    FormLabel("Category")
    CompactLocationField(
        value = formState.category,
        isEditing = editingCategory,
        icon = { Icon(Icons.Filled.Category, null, tint = colors.blue) },
        placeholder = "Select category *",
        showDropdown = showCategoryDropdown,
        filteredOptions = filteredCategories,
        onValueChange = { v ->
            onFormFieldChange("category", v)
            if (v.isNotBlank()) onShowCategoryDropdown(true)
        },
        onFocusChange = { onShowCategoryDropdown(it) },
        onSelectOption = { cat -> onFormFieldChange("category", cat); onShowCategoryDropdown(false); editingCategory = false },
        onExpand = { editingCategory = true },
        isError = showErrors && formState.category.isBlank(),
        errorText = if (showErrors && formState.category.isBlank()) "Choose a category" else null,
        colors = colors
    )

    // ── Stage 2: Description + Weight + Fragile (appears after category) ──
    AnimatedVisibility(visible = detailsRevealStage >= 1, enter = fadeIn() + expandVertically()) {
        Column {
            Spacer(Modifier.height(8.dp))
            FormLabel("Package details")
            Surface(shape = RoundedCornerShape(14.dp), color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(12.dp)) {
                    FormTextField(
                        value = formState.description,
                        onValueChange = { onFormFieldChange("description", it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Description *",
                        leadingIcon = Icons.Filled.Description,
                        minLines = 2,
                        maxLines = 3,
                        isError = showErrors && formState.description.isBlank(),
                        errorText = if (showErrors && formState.description.isBlank()) "Add a short description" else null,
                        colors = colors
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormTextField(
                            value = formState.weight,
                            onValueChange = { onFormFieldChange("weight", it) },
                            modifier = Modifier.weight(1f),
                            label = "Weight (kg) *",
                            leadingIcon = Icons.Filled.MonitorWeight,
                            singleLine = true,
                            isError = showErrors && formState.weight.isBlank(),
                            errorText = if (showErrors && formState.weight.isBlank()) "Weight is required" else null,
                            colors = colors
                        )
                        Spacer(Modifier.width(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = formState.isFragile,
                                onCheckedChange = { onFragileChange(it) },
                                colors = CheckboxDefaults.colors(checkedColor = colors.red)
                            )
                            Text("Fragile", color = colors.textSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Step 2: Media Attachments ────────────────────────────────────────────────

@Composable
private fun MediaStep(
    mediaUploads: List<MediaUploadState>,
    onAddMedia: (String, ByteArray, String) -> Unit,
    onCancelUpload: (String) -> Unit,
    onRemoveMedia: (String) -> Unit,
    onGalleryClick: () -> Unit,
    onCameraClick: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    FormLabel("Media Attachments")
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onGalleryClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.textPrimary)
                ) {
                    Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery", fontSize = 12.sp)
                }
                Button(
                    onClick = onCameraClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.textPrimary)
                ) {
                    Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Camera", fontSize = 12.sp)
                }
            }

            if (mediaUploads.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mediaUploads) { media ->
                        Box(
                            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceAlt).border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                        ) {
                            AsyncImage(model = media.byteArray ?: media.uri, contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (media.isUploading) {
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.BottomCenter) {
                                    LinearProgressIndicator(
                                        progress = { (media.progress / 100.0).toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = colors.blue, trackColor = Color.White.copy(alpha = 0.3f)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { if (media.isUploading) onCancelUpload(media.id) else onRemoveMedia(media.id) },
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(if (media.isUploading) Icons.Filled.Close else Icons.Filled.Delete,
                                    null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Image, null, tint = colors.textSecondary.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("No media attached", color = colors.textSecondary.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Step 3: Summary & Transfer Options ────────────────────────────────────────

@Composable
private fun SummaryStep(
    formState: CreatePackageFormState,
    mediaUploads: List<MediaUploadState>,
    showSenderFields: Boolean,
    selectedTransferRuleType: String?,
    onTransferRuleTypeChange: (String) -> Unit,
    driverSearchQuery: String,
    onDriverSearchQueryChange: (String) -> Unit,
    showDriverSearchResults: Boolean,
    userSearchResults: List<SearchUsersQuery.SearchUser>,
    onSelectDriver: (SearchUsersQuery.SearchUser) -> Unit,
    onClearDriver: () -> Unit,
    transferMatchUserId: String?,
    transferMatchUserName: String?,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Summary header ────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Review your package", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.textPrimary)
                }
                Spacer(Modifier.height(12.dp))

                // Sender
                SummaryRow(Icons.Filled.Person, if (showSenderFields) "Sender" else "From",
                    if (showSenderFields) formState.senderName.ifBlank { "Not specified" } else formState.fromAddress.ifBlank { "Not specified" },
                    colors)
                if (showSenderFields && formState.senderName.isNotBlank()) {
                    SummaryRow(Icons.Filled.Phone, "Sender phone", formState.senderPhone.ifBlank { "Not provided" }, colors)
                    SummaryRow(Icons.Filled.MyLocation, "Pickup location", formState.fromAddress.ifBlank { "Not specified" }, colors)
                }

                // Recipient
                SummaryRow(Icons.Filled.Person, "Recipient", formState.recipientName.ifBlank { "Not specified" }, colors)
                if (formState.recipientName.isNotBlank()) {
                    SummaryRow(Icons.Filled.Phone, "Recipient phone", formState.recipientPhone.ifBlank { "Not provided" }, colors)
                }

                // Delivery
                SummaryRow(Icons.Filled.LocationOn, "Deliver to", formState.toAddress.ifBlank { "Not specified" }, colors)

                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.divider, thickness = 0.5.dp)

                // Package details
                SummaryRow(Icons.Filled.Category, "Category", formState.category.ifBlank { "Not specified" }, colors)
                SummaryRow(Icons.Filled.Description, "Description", formState.description.ifBlank { "Not specified" }, colors)
                SummaryRow(Icons.Filled.MonitorWeight, "Weight", if (formState.weight.isNotBlank()) "${formState.weight} kg" else "Not specified", colors)
                if (formState.isFragile) {
                    SummaryRow(Icons.Filled.Warning, "Fragile", "Yes \u2014 handle with care", colors, accent = colors.red)
                }
            }
        }

        // ── Media thumbnails (if any) ─────────────────────────────────────
        if (mediaUploads.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Attachments", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        mediaUploads.forEach { media ->
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, colors.divider),
                                color = colors.surfaceAlt
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = media.byteArray ?: media.uri,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    if (media.isUploading) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.blue
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Transfer mode selection (client mode only) ────────────────────
        if (!showSenderFields) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SwapHoriz, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delivery Options", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("How should drivers receive this package?",
                        color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TransferModeChip("Auto", "Auto-assign", selectedTransferRuleType == "AUTO", { onTransferRuleTypeChange("AUTO") }, colors)
                        TransferModeChip("Secure", "Code needed", selectedTransferRuleType == "SECURE", { onTransferRuleTypeChange("SECURE") }, colors)
                        TransferModeChip("Confirm", "Accept needed", selectedTransferRuleType == "CONFIRM", { onTransferRuleTypeChange("CONFIRM") }, colors)
                    }

                    if (selectedTransferRuleType != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                        Spacer(Modifier.height(8.dp))
                        FormTextField(
                            value = driverSearchQuery,
                            onValueChange = onDriverSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = "Assign to driver @username",
                            leadingIcon = Icons.Filled.Person,
                            singleLine = true,
                            colors = colors
                        )
                        if (showDriverSearchResults && userSearchResults.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                                shape = RoundedCornerShape(10.dp), color = colors.surface,
                                border = BorderStroke(1.dp, colors.divider), shadowElevation = 4.dp
                            ) {
                                LazyColumn {
                                    items(userSearchResults) { user ->
                                        Text(
                                            "${user.firstName} ${user.lastName} (@${user.username})",
                                            modifier = Modifier.fillMaxWidth().clickable { onSelectDriver(user) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            color = colors.textPrimary, fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                        if (transferMatchUserId != null) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.green.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Person, null, tint = colors.green, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(transferMatchUserName ?: "Driver",
                                            color = colors.green, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text("Will need to accept this package",
                                            color = colors.textSecondary, fontSize = 10.sp)
                                    }
                                    IconButton(onClick = onClearDriver, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    label: String,
    value: String,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors,
    accent: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon, null,
            tint = (accent ?: colors.textSecondary).copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = colors.textSecondary.copy(alpha = 0.7f),
                fontSize = 11.sp,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                value,
                color = accent ?: colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Compact field helpers ─────────────────────────────────────────────────────

/**
 * A field that shows a compact chip when a value is selected and [isEditing] is false.
 * Tap the chip to expand it to a full [OutlinedTextField].
 */
@Composable
private fun CompactLocationField(
    value: String,
    isEditing: Boolean,
    icon: @Composable () -> Unit,
    placeholder: String,
    showDropdown: Boolean,
    filteredOptions: List<String>,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSelectOption: (String) -> Unit,
    onExpand: () -> Unit,
    isError: Boolean = false,
    errorText: String? = null,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Box {
        if (isEditing || value.isBlank()) {
            // ── Expanded: full styled field ──
            FormTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
                label = placeholder,
                customLeadingIcon = icon,
                singleLine = true,
                isError = isError,
                errorText = errorText,
                colors = colors
            )
            if (showDropdown && filteredOptions.isNotEmpty()) {
                FormLocationDropdown(filteredOptions) { loc ->
                    onSelectOption(loc)
                }
            }
        } else {
            // ── Compact: clickable chip showing the selected value ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, if (isError && value.isBlank()) colors.red else colors.divider),
                onClick = onExpand
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    icon()
                    Spacer(Modifier.width(12.dp))
                    Text(value, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * A compact chip showing a single field value (name or phone), with an edit button.
 */
@Composable
private fun CompactFieldChip(
    label: String,
    icon: ImageVector,
    onEdit: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = colors.blue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * A compact profile card showing recipient name and phone, with an edit button.
 */
@Composable
private fun CompactRecipientCard(
    name: String,
    phone: String,
    onEdit: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile circle
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = CircleShape, color = colors.green.copy(alpha = 0.15f)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(name.first().uppercase(), color = colors.green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(phone, color = colors.textSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Filled.Edit, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Package builder helper ────────────────────────────────────────────────────

fun buildPackage(
    formState: CreatePackageFormState,
    mediaUploads: List<MediaUploadState>,
    transferRuleType: String?,
    transferMatchUserId: String?,
    transferMatchUserName: String?
): ClientPackage = ClientPackage(
    senderName     = formState.senderName.ifBlank { "Unknown Sender" },
    senderPhone    = formState.senderPhone.ifBlank { "" },
    fromAddress    = formState.fromAddress,
    recipientName  = formState.recipientName,
    recipientPhone = formState.recipientPhone,
    toAddress      = formState.toAddress,
    description    = formState.description,
    weight         = formState.weight,
    category       = formState.category,
    fragile        = formState.isFragile,
    mediaUrls      = mediaUploads.mapNotNull { it.url },
    status         = PackageStatus.PENDING,
    createdAt      = "Just now",
    statusHistory  = listOf(StatusUpdate(PackageStatus.PENDING, "Just now", formState.fromAddress, "Order placed")),
    transferRuleType     = transferRuleType,
    transferMatchUserId  = transferMatchUserId,
    transferMatchUserName = transferMatchUserName
)

// ── Shared form helpers ───────────────────────────────────────────────────────

/**
 * A consistent, themed text field for the create-package form. Outlines are
 * tuned to the app palette, required labels keep an asterisk, and inline
 * validation messages render below the field in the error color.
 */
@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    iconTint: Color? = null,
    customLeadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label, fontSize = 14.sp) },
        leadingIcon = customLeadingIcon
            ?: leadingIcon?.let { icon -> { Icon(icon, null, tint = iconTint ?: colors.blue, modifier = Modifier.size(20.dp)) } },
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = errorText?.let { msg -> { Text(msg, fontWeight = FontWeight.Medium) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = colors.divider,
            focusedBorderColor = colors.blue,
            cursorColor = colors.blue,
            unfocusedLabelColor = colors.textSecondary,
            focusedLabelColor = colors.blue,
            focusedLeadingIconColor = colors.blue,
            unfocusedLeadingIconColor = colors.textSecondary,
            errorBorderColor = colors.red,
            errorLabelColor = colors.red,
            errorCursorColor = colors.red,
            errorSupportingTextColor = colors.red,
            errorLeadingIconColor = colors.red
        )
    )
}

/**
 * A compact section title with a blue→green accent tick, used at the top of
 * each wizard step.
 */
@Composable
private fun StepHeader(
    title: String,
    subtitle: String,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.blue, colors.green)
                    )
                )
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    val colors = LocalDriversColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.blue, colors.green)
                    )
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.uppercase(),
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun FormLocationDropdown(options: List<String>, onSelect: (String) -> Unit) {
    val colors = LocalDriversColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp).heightIn(max = 200.dp),
        shape = RoundedCornerShape(12.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.divider), shadowElevation = 4.dp
    ) {
        LazyColumn {
            items(options) { loc ->
                Text(loc, modifier = Modifier.fillMaxWidth().clickable { onSelect(loc) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = colors.textPrimary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun FormSearchDropdown(results: List<SearchUsersQuery.SearchUser>, onSelect: (SearchUsersQuery.SearchUser) -> Unit) {
    val colors = LocalDriversColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        shape = RoundedCornerShape(12.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.divider), shadowElevation = 4.dp
    ) {
        LazyColumn {
            items(results) { user ->
                Text(
                    "${user.firstName} ${user.lastName} (@${user.username})",
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(user) }.padding(12.dp),
                    color = colors.textPrimary
                )
            }
        }
    }
}

@Composable
private fun TransferModeChip(
    label: String, description: String, selected: Boolean, onClick: () -> Unit,
    colors: com.gocavgo.ikuriye.ui.theme.DriversColors
) {
    val bgColor = if (selected) colors.blue.copy(alpha = 0.15f) else colors.surfaceAlt
    val borderColor = if (selected) colors.blue else colors.divider
    val textColor = if (selected) colors.blue else colors.textSecondary
    Surface(
        shape = RoundedCornerShape(8.dp), color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = textColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            Text(description, color = colors.textSecondary.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
        }
    }
}
