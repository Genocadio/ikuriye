package com.gocavgo.ikuriye.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.CreatePackageFormState
import com.gocavgo.ikuriye.viewmodel.MediaUploadState
import kotlinx.coroutines.delay

/**
 * A floating dialog overlay that shows the package creation form as a centered card.
 *
 * The card has horizontal padding (not full-width), its height matches the content,
 * and the button area is compact at the bottom.
 *
 * - [visible] controls whether the panel is shown.
 * - [dismissable] when `false`, the close button is hidden and back/outside dismiss is blocked
 *   (the user must submit a package before closing). Defaults to `true`.
 * - [onDismiss] closes the panel WITHOUT clearing form state (data persists until explicitly sent).
 * - [onDiscardDraft] closes the panel AND clears the form state. Only used when [hasUnsavedData] is true.
 * - [hasUnsavedData] when true, shows a confirmation dialog before dismissing if the form has data.
 * - All other parameters are forwarded to [PackageFormContent].
 */
@Composable
fun FloatingCreatePanel(
    visible: Boolean,
    dismissable: Boolean = true,
    onDismiss: () -> Unit,
    onDiscardDraft: (() -> Unit)? = null,
    hasUnsavedData: Boolean = false,
    formState: CreatePackageFormState,
    onFormFieldChange: (String, String) -> Unit,
    onFragileChange: (Boolean) -> Unit,
    showSenderFields: Boolean = false,
    isSubmitting: Boolean = false,
    userSearchResults: List<SearchUsersQuery.SearchUser> = emptyList(),
    onUserSearch: (String) -> Unit = {},
    onClearUserSearch: () -> Unit = {},
    mediaUploads: List<MediaUploadState> = emptyList(),
    onAddMedia: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onCancelUpload: (String) -> Unit = {},
    onRemoveMedia: (String) -> Unit = {},
    onSubmit: (ClientPackage) -> Unit
) {
    if (!visible) return

    var currentStep by remember { mutableIntStateOf(0) }
    var maxReachedStep by remember { mutableIntStateOf(0) }
    // Steps the user already tried to advance past while it was invalid — marks
    // the specific empty required fields with inline "required" error messages.
    var attemptedSteps by remember { mutableStateOf(setOf<Int>()) }
    val showErrors = currentStep in attemptedSteps
    var showDiscardDialog by remember { mutableStateOf(false) }
    // ── Transfer options (managed here so buildPackage sees them on submit) ──
    var selectedTransferRuleType by remember { mutableStateOf<String?>("AUTO") }
    var transferMatchUserId by remember { mutableStateOf<String?>(null) }
    var transferMatchUserName by remember { mutableStateOf<String?>(null) }
    var driverSearchQuery by remember { mutableStateOf("") }

    val stepIsValid = when (currentStep) {
        0 -> formState.fromAddress.isNotBlank() && formState.recipientName.isNotBlank() && formState.toAddress.isNotBlank()
        1 -> formState.category.isNotBlank() && formState.description.isNotBlank() && formState.weight.isNotBlank()
        2 -> true
        3 -> true
        else -> false
    }
    val isLastStep = currentStep == 3

    // ── Next-icon pulse: fires 2 s after step becomes valid ──────────────────
    var nextIconPulse by remember { mutableStateOf(false) }
    LaunchedEffect(stepIsValid, currentStep) {
        nextIconPulse = false
        if (stepIsValid) {
            delay(2000L)
            nextIconPulse = true
        }
    }
    val pulseScale by animateFloatAsState(
        targetValue = if (nextIconPulse) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        finishedListener = { nextIconPulse = false },
        label = "nextIconPulse"
    )

    // Only allow outside-click / back-press to dismiss when form is completely empty
    val isFormEmpty = formState.fromAddress.isBlank() &&
            formState.toAddress.isBlank() &&
            formState.recipientName.isBlank() &&
            formState.recipientPhone.isBlank() &&
            formState.category.isBlank() &&
            formState.description.isBlank() &&
            formState.weight.isBlank() &&
            formState.senderName.isBlank() &&
            formState.senderPhone.isBlank()
    val canDismissOutside = dismissable && isFormEmpty

    fun handleDismissRequest() {
        if (dismissable && hasUnsavedData) {
            showDiscardDialog = true
        } else if (dismissable) {
            onDismiss()
        }
    }

    val dismissHandler: () -> Unit = { handleDismissRequest() }

    // ── Transition state: starts false → immediately animates to true on mount ─
    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { transitionState.targetState = true }

    Dialog(
        onDismissRequest = dismissHandler,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = canDismissOutside,
            dismissOnClickOutside = canDismissOutside
        )
    ) {
        val colors = LocalDriversColors.current

        // ── Full-screen scrim, centered content ───────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .imePadding()
                .let { mod ->
                    if (canDismissOutside) {
                        mod.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { handleDismissRequest() }
                        )
                    } else mod
                },
            contentAlignment = Alignment.Center
        ) {
            // ── Animated card: scale 0.85→1.0 + fade 0→1 on enter, reverse on exit ─
            AnimatedVisibility(
                visibleState = transitionState,
                enter = scaleIn(
                    initialScale = 0.85f,
                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 300f)
                ) + fadeIn(animationSpec = tween(220)),
                exit = scaleOut(
                    targetScale = 0.85f,
                    animationSpec = tween(180)
                ) + fadeOut(animationSpec = tween(150))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .widthIn(max = 520.dp)
                        .wrapContentHeight()
                        .statusBarsPadding()
                        .padding(bottom = 8.dp)
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
                            .heightIn(max = 520.dp)
                            .navigationBarsPadding()
                    ) {
                        // ── Gradient accent bar (top of card, clipped by Surface's corners) ──
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(colors.blue, colors.green)
                                    )
                                )
                        )

                        // ── Header: [X] [Step Dots] [Next] ─────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Close (left) — wrapped in a surfaceAlt circle
                            if (dismissable) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.surfaceAlt,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    IconButton(
                                        onClick = { handleDismissRequest() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close, null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.width(28.dp))
                            }

                            // Step indicator (center, takes remaining space)
                            Box(modifier = Modifier.weight(1f)) {
                                IconsStepIndicator(
                                    currentStep = currentStep,
                                    maxReachedStep = maxReachedStep,
                                    totalSteps = STEPS.size,
                                    onStepClick = { idx -> if (idx <= maxReachedStep) { currentStep = idx } },
                                    colors = colors
                                )
                            }

                            // Next / Send (right) — solid filled pill button with pulse.
                            // Always visible: when the step is invalid, tapping it
                            // surfaces the inline "required" errors instead of advancing.
                            val haptic = LocalHapticFeedback.current
                            val btnColor = when {
                                isSubmitting -> colors.surfaceAlt
                                isLastStep -> if (stepIsValid) colors.green else colors.surfaceAlt
                                else -> if (stepIsValid) colors.blue else colors.surfaceAlt
                            }
                            val btnContentColor = if (stepIsValid && !isSubmitting) Color.White else colors.textSecondary
                            Surface(
                                onClick = {
                                    if (!isSubmitting) {
                                        if (stepIsValid) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (isLastStep) {
                                                onSubmit(buildPackage(
                                                    formState,
                                                    mediaUploads,
                                                    if (showSenderFields) null else selectedTransferRuleType,
                                                    if (showSenderFields) null else transferMatchUserId,
                                                    if (showSenderFields) null else transferMatchUserName
                                                ))
                                            } else {
                                                currentStep++
                                                if (currentStep > maxReachedStep) maxReachedStep = currentStep
                                            }
                                        } else {
                                            attemptedSteps = attemptedSteps + currentStep
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                color = btnColor,
                                shadowElevation = if (nextIconPulse && stepIsValid) 8.dp else 3.dp,
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = pulseScale
                                        scaleY = pulseScale
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isLastStep) {
                                        if (isSubmitting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White
                                            )
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send, null,
                                                tint = btnContentColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                "Send",
                                                color = btnContentColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Text(
                                            "Next",
                                            color = btnContentColor,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward, null,
                                            tint = btnContentColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

                        // ── Form content (bank-card height, scrollable) ────
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            PackageFormContent(
                                formState = formState,
                                onFormFieldChange = onFormFieldChange,
                                onFragileChange = onFragileChange,
                                currentStep = currentStep,
                                onStepChange = { step ->
                                    currentStep = step
                                    if (step > maxReachedStep) maxReachedStep = step
                                },
                                stepIsValid = stepIsValid,
                                isLastStep = isLastStep,
                                showSenderFields = showSenderFields,
                                isSubmitting = isSubmitting,
                                userSearchResults = userSearchResults,
                                onUserSearch = onUserSearch,
                                onClearUserSearch = onClearUserSearch,
                                mediaUploads = mediaUploads,
                                onAddMedia = onAddMedia,
                                onCancelUpload = onCancelUpload,
                                onRemoveMedia = onRemoveMedia,
                                onSubmit = onSubmit,
                                selectedTransferRuleType = selectedTransferRuleType,
                                onTransferRuleTypeChange = { selectedTransferRuleType = it },
                                driverSearchQuery = driverSearchQuery,
                                onDriverSearchQueryChange = { v ->
                                    driverSearchQuery = v
                                    if (v.startsWith("@")) { onUserSearch(v.removePrefix("@")) }
                                    else { onClearUserSearch() }
                                },
                                transferMatchUserId = transferMatchUserId,
                                transferMatchUserName = transferMatchUserName,
                                onSelectDriver = { user ->
                                    transferMatchUserId = user.id
                                    transferMatchUserName = "${user.firstName} ${user.lastName}"
                                    driverSearchQuery = "${user.firstName} ${user.lastName}"
                                    onClearUserSearch()
                                },
                                onClearDriver = {
                                    transferMatchUserId = null
                                    transferMatchUserName = null
                                    driverSearchQuery = ""
                                },
                                showErrors = showErrors
                            )
                        }

                        // ── Compact bottom spacer ─────────────────────────
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }

    // ── Confirm discard dialog (shown when trying to close with unsaved data) ─
    if (showDiscardDialog) {
        val discardColors = LocalDriversColors.current
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = discardColors.surface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Unsaved changes", fontWeight = FontWeight.Bold, color = discardColors.textPrimary)
            },
            text = {
                Text(
                    "You have unsaved package data. Save as draft to keep it, or discard to start fresh later.",
                    color = discardColors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = discardColors.green)
                ) {
                    Text("Save as Draft", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("Keep Editing", color = discardColors.blue, fontWeight = FontWeight.Bold)
                    }
                    if (onDiscardDraft != null) {
                        TextButton(onClick = {
                            showDiscardDialog = false
                            onDiscardDraft()
                        }) {
                            Text("Discard", color = discardColors.red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        )
    }
}
