package com.gocavgo.ikuriye.ui.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.ui.common.PackageFormContent
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.CreatePackageFormState
import com.gocavgo.ikuriye.viewmodel.MediaUploadState

/**
 * Full-screen package creation screen.
 *
 * This is a thin wrapper around [PackageFormContent] with a top toolbar.
 * For the floating overlay variant used in the main app flow, see [FloatingCreatePanel].
 */
@Composable
fun CreatePackageScreen(
    onBack: () -> Unit,
    onSubmit: (ClientPackage) -> Unit,
    formState: CreatePackageFormState = CreatePackageFormState(),
    onFormFieldChange: (String, String) -> Unit = { _, _ -> },
    onFragileChange: (Boolean) -> Unit = {},
    showSenderFields: Boolean = false,
    isSubmitting: Boolean = false,
    userSearchResults: List<SearchUsersQuery.SearchUser> = emptyList(),
    onUserSearch: (String) -> Unit = {},
    onClearUserSearch: () -> Unit = {},
    mediaUploads: List<MediaUploadState> = emptyList(),
    onAddMedia: (String, ByteArray, String) -> Unit = { _, _, _ -> },
    onCancelUpload: (String) -> Unit = {},
    onRemoveMedia: (String) -> Unit = {}
) {
    val colors = LocalDriversColors.current
    val wide = isWideScreen()
    val maxW = contentMaxWidth()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wide && maxW != Dp.Unspecified) Modifier.widthIn(max = maxW) else Modifier)
                .padding(16.dp)
        ) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(colors.surfaceAlt)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Text("Send a Package", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))

            // Form body — Full-screen variant manages its own step state
            var step by remember { mutableIntStateOf(0) }
            val stepValid = when (step) {
                0 -> formState.fromAddress.isNotBlank() && formState.recipientName.isNotBlank() && formState.toAddress.isNotBlank()
                1 -> formState.category.isNotBlank() && formState.description.isNotBlank() && formState.weight.isNotBlank()
                2 -> true
                3 -> true
                else -> false
            }
            PackageFormContent(
                formState = formState,
                onFormFieldChange = onFormFieldChange,
                onFragileChange = onFragileChange,
                currentStep = step,
                onStepChange = { step = it },
                stepIsValid = stepValid,
                isLastStep = step == 3,
                showSenderFields = showSenderFields,
                isSubmitting = isSubmitting,
                userSearchResults = userSearchResults,
                onUserSearch = onUserSearch,
                onClearUserSearch = onClearUserSearch,
                mediaUploads = mediaUploads,
                onAddMedia = onAddMedia,
                onCancelUpload = onCancelUpload,
                onRemoveMedia = onRemoveMedia,
                onSubmit = onSubmit
            )
        }
    }
}
