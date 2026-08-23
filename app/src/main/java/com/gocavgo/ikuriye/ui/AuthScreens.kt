package com.gocavgo.ikuriye.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.ui.common.AuthSplitLayout
import com.gocavgo.ikuriye.ui.common.CachedAvatarImage
import com.gocavgo.ikuriye.ui.common.contentMaxWidth
import com.gocavgo.ikuriye.ui.common.isWideScreen
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.util.PhoneValidation
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import com.gocavgo.ikuriye.viewmodel.DriverVehicle
import com.gocavgo.ikuriye.viewmodel.TripViewModel

@Composable
fun LoginScreen(
    onLogin: (String, String) -> Unit,
    authResult: AuthResult? = null,
    isAuthLoading: Boolean = false,
    onClearAuthResult: () -> Unit = {},
    onForgotPassword: (String) -> Unit = {},
    onResetPassword: (String, String) -> Unit = { _, _ -> },
    showForgotPassword: Boolean = false,
    forgotPasswordStep: Int = 0,
    onShowForgotPassword: () -> Unit = {},
    onHideForgotPassword: () -> Unit = {},
    signInPrefillEmail: String = "",
    onClearSignInPrefill: () -> Unit = {}
) {
    val colors = LocalDriversColors.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onClearAuthResult() }
    LaunchedEffect(signInPrefillEmail) {
        if (signInPrefillEmail.isNotBlank()) {
            email = signInPrefillEmail
            password = ""
            onClearSignInPrefill()
        }
    }

    AuthSplitLayout(
        accent = colors.blue,
        logo = Icons.Filled.LocalShipping,
        title = "CaVgo Driver",
        subtitle = "Sign in to view your active trip"
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email or Phone") },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    singleLine = true,
                    enabled = !isAuthLoading,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !isAuthLoading,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLogin(email.trim(), password) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                    enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank()
                ) {
                    if (isAuthLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Log in", fontWeight = FontWeight.Bold)
                    }
                }

                if (authResult is AuthResult.Error) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(authResult.message, color = colors.red, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onShowForgotPassword,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Forgot Password?", color = colors.blue, fontSize = 13.sp)
                }
            }
        }
    }

    if (showForgotPassword) {
        ForgotPasswordDialog(
            onDismiss = onHideForgotPassword,
            onSendCode = onForgotPassword,
            onResetPassword = onResetPassword,
            step = forgotPasswordStep,
            isLoading = isAuthLoading
        )
    }
}

@Composable
fun ForgotPasswordDialog(
    onDismiss: () -> Unit,
    onSendCode: (String) -> Unit,
    onResetPassword: (code: String, newPassword: String) -> Unit = { _, _ -> },
    step: Int = 0,
    isLoading: Boolean = false
) {
    val colors = LocalDriversColors.current
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                when (step) {
                    0 -> "Reset Password"
                    1 -> "Enter Reset Code"
                    else -> "Password Reset"
                },
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                when (step) {
                    0 -> {
                        Text(
                            "Self-service password reset is not available yet. Ask your administrator to set a temporary password for your account — you will be asked to create a new one on your next login.",
                            color = colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                    1 -> {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { if (it.length <= 6) code = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirmation code") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            singleLine = true,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("000000") }
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        null
                                    )
                                }
                            },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirm new password") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            isError = confirmPassword.isNotEmpty() && confirmPassword != newPassword,
                            supportingText = if (confirmPassword.isNotEmpty() && confirmPassword != newPassword) {{ Text("Passwords do not match", color = colors.red, fontSize = 11.sp) }} else null
                        )
                    }
                    else -> {
                        Text(
                            "Your password has been reset successfully.",
                            color = colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(onClick = onDismiss) {
                    Text("OK", color = colors.blue, fontWeight = FontWeight.Bold)
                }
                1 -> Button(
                    onClick = { onResetPassword(code.trim(), newPassword) },
                    enabled = code.length == 6 && newPassword.length >= 6 && newPassword == confirmPassword && !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Reset Password", fontWeight = FontWeight.Bold)
                    }
                }
                else -> TextButton(onClick = onDismiss) {
                    Text("Done", color = colors.blue, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (step < 2) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        }
    )
}

@Composable
fun ProfileScreen(
    profile: DriverProfile,
    viewModel: TripViewModel
) {
    val colors = LocalDriversColors.current
    val state by viewModel.state.collectAsState()
    val authUser = state.authUser
    val context = LocalContext.current
    
    var name by remember(authUser) { mutableStateOf(authUser?.let { "${it.firstName ?: ""} ${it.lastName ?: ""}".trim() } ?: profile.name) }
    var phone by remember(authUser) { mutableStateOf(PhoneValidation.toDisplayFormat(authUser?.phone ?: profile.phone)) }
    var username by remember(authUser) { mutableStateOf(authUser?.username ?: "") }
    var currentPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var isEditing by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isUpdatingProfile) {
        if (pendingSave && !state.isUpdatingProfile) {
            pendingSave = false
            if (state.profileUpdateError.isEmpty()) {
                isEditing = false
            }
        }
    }
    
    // Track initial values to detect changes
    val initialName = authUser?.let { "${it.firstName ?: ""} ${it.lastName ?: ""}".trim() } ?: profile.name
    val initialPhone = PhoneValidation.toDisplayFormat(authUser?.phone ?: profile.phone)
    val initialUsername = authUser?.username ?: ""
    
    // Detect unsaved changes
    val hasUnsavedProfileChanges = isEditing && (
        name != initialName ||
        phone != initialPhone ||
        username != initialUsername
    )
    val hasUnsavedPassword = password.length >= 6
    val hasAnyUnsavedChanges = hasUnsavedProfileChanges || hasUnsavedPassword

    val profileImageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bytes = inputStream?.readBytes()
            val mimeType = context.contentResolver.getType(it) ?: "image/jpeg"
            if (bytes != null) {
                viewModel.onProfileImageSelected(bytes, mimeType)
            }
        }
    }

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (hasAnyUnsavedChanges) {
                        showDiscardDialog = true
                    } else {
                        viewModel.closeProfile()
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceAlt)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text("Profile Settings", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        // Profile Picture Section
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .clickable { if (!state.isUploadingProfileImage) profileImageLauncher.launch("image/*") }
                    .border(1.dp, colors.divider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (state.isUploadingProfileImage) {
                    // Show uploading spinner overlay
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp,
                            color = colors.blue
                        )
                    }
                } else if (state.selectedProfileImage != null) {
                    AsyncImage(
                        model = state.selectedProfileImage,
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (authUser?.avatarUrl != null) {
                    CachedAvatarImage(
                        remoteUrl = authUser.avatarUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Filled.Person, null, modifier = Modifier.size(48.dp), tint = colors.textSecondary)
                }
                
                if (!state.isUploadingProfileImage) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.AddAPhoto, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Account Details", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(authUser?.email ?: profile.email, color = colors.textSecondary, fontSize = 12.sp)
                
                Spacer(Modifier.height(18.dp))
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Full Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = isEditing && !state.isUpdatingProfile
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = isEditing && !state.isUpdatingProfile,
                    prefix = { Text("@") }
                )
                Spacer(Modifier.height(12.dp))
                PhoneInput(
                    value = phone,
                    onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Phone Number",
                    enabled = isEditing && !state.isUpdatingProfile
                )
                
                if (state.profileUpdateError.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.profileUpdateError, color = colors.red, fontSize = 12.sp)
                }

                Spacer(Modifier.height(24.dp))
                if (isEditing) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                name = initialName
                                phone = initialPhone
                                username = initialUsername
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !state.isUpdatingProfile
                        ) {
                            Text("Cancel", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                viewModel.updateUserProfile(name, phone, username)
                                pendingSave = true
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                            enabled = !state.isUpdatingProfile && (
                                name != initialName ||
                                phone != initialPhone ||
                                username != initialUsername
                            )
                        ) {
                            if (state.isUpdatingProfile) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Save Changes", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { isEditing = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                    ) {
                        Icon(Icons.Filled.Create, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Edit Profile", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Separate section for password change
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Security", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.updatePassword(currentPassword, password)
                        currentPassword = ""
                        password = ""
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.textPrimary),
                    enabled = currentPassword.isNotBlank() && password.length >= 6 && !state.isUpdatingProfile
                ) {
                    if (state.isUpdatingProfile) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.textSecondary)
                    } else {
                        Text("Update Password", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        }
    }

    // ── Discard changes confirmation dialog ─────────────────────────────────
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Discard changes?", fontWeight = FontWeight.Bold, color = colors.textPrimary)
            },
            text = {
                if (hasUnsavedProfileChanges && hasUnsavedPassword) {
                    Text("You have unsaved profile changes and a pending password update. Leaving now will discard them.",
                        color = colors.textSecondary, fontSize = 13.sp)
                } else if (hasUnsavedProfileChanges) {
                    Text("You have unsaved profile changes. Leaving now will discard them.",
                        color = colors.textSecondary, fontSize = 13.sp)
                } else {
                    Text("You have an unsaved password update. Leaving now will discard it.",
                        color = colors.textSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.closeProfile()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.red)
                ) {
                    Text("Discard", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing", color = colors.blue, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun EmptyTripHome(profile: DriverProfile, onProfileClick: () -> Unit) {
    val colors = LocalDriversColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        HomeHeader(profile = profile, onProfileClick = onProfileClick)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.LocalShipping, null, tint = colors.textSecondary, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text("No active trip", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your next assigned route will appear here.", color = colors.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun VehicleScreen(
    vehicle: DriverVehicle,
    viewModel: TripViewModel
) {
    val colors = LocalDriversColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.closeVehicleMenu() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text("Assigned Vehicle", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.blue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.DirectionsCar, null, tint = colors.blue)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(vehicle.plateNumber, color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Vehicle currently assigned to this driver", color = colors.textSecondary, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(18.dp))
                VehicleInfoRow("Model", vehicle.model, Icons.Filled.DirectionsCar)
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 12.dp))
                VehicleInfoRow("Seat size", "${vehicle.seats} seats", Icons.Filled.EventSeat)
            }
        }
    }
}

@Composable
private fun VehicleInfoRow(
    label: String,
    value: String,
    icon: ImageVector
) {
    val colors = LocalDriversColors.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = colors.textSecondary, fontSize = 11.sp)
            Text(value, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeHeader(profile: DriverProfile, onProfileClick: () -> Unit) {
    val colors = LocalDriversColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Welcome back", color = colors.textSecondary, fontSize = 11.sp)
            Text(profile.name, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onProfileClick,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(colors.surfaceAlt)
        ) {
            Icon(Icons.Filled.Person, null, tint = colors.blue)
        }
    }
}
