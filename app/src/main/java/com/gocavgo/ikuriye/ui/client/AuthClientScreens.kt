package com.gocavgo.ikuriye.ui.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.ui.ForgotPasswordDialog
import com.gocavgo.ikuriye.ui.PhoneInput
import com.gocavgo.ikuriye.ui.common.AuthSplitLayout
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleSelectionScreen(
    onTrackByCode: (String) -> Unit = {},
    trackError: String = "",
    authResult: AuthResult? = null,
    isAuthLoading: Boolean = false,
    onSignUp: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    onSignIn: (String, String) -> Unit = { _, _ -> },
    onClearAuthResult: () -> Unit = {},
    showOtpScreen: Boolean = false,
    otpEmail: String = "",
    onVerifyOtp: (String) -> Unit = {},
    onResendOtp: () -> Unit = {},
    onDismissOtp: () -> Unit = {},
    showForgotPassword: Boolean = false,
    forgotPasswordStep: Int = 0,
    onForgotPassword: (String) -> Unit = {},
    onResetPassword: (String, String) -> Unit = { _, _ -> },
    onShowForgotPassword: () -> Unit = {},
    onHideForgotPassword: () -> Unit = {},
    signInPrefillEmail: String = "",
    onClearSignInPrefill: () -> Unit = {}
) {
    val colors = LocalDriversColors.current
    var trackingCode by remember { mutableStateOf("") }
    var isSignUp     by remember { mutableStateOf(false) }
    var showAuth     by remember { mutableStateOf(false) }
    var email        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var fullName     by remember { mutableStateOf("") }
    var phone        by remember { mutableStateOf("") }

    // React to signInPrefillEmail — switch to sign-in tab with email pre-filled
    LaunchedEffect(signInPrefillEmail) {
        if (signInPrefillEmail.isNotBlank()) {
            showAuth = true
            isSignUp = false
            email = signInPrefillEmail
            password = ""
            fullName = ""
            phone = ""
            onClearSignInPrefill()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        if (showOtpScreen) {
            OtpVerificationScreen(
                email = otpEmail, onVerify = onVerifyOtp, onResend = onResendOtp,
                onBack = onDismissOtp, isAuthLoading = isAuthLoading, authResult = authResult
            )
        } else {
            AuthSplitLayout(
                accent = colors.blue,
                logo = Icons.Filled.LocalShipping,
                title = "ikuriye",
                subtitle = "Deliver anything, anywhere"
            ) {
                AnimatedContent(
                    targetState = showAuth,
                    transitionSpec = {
                        if (targetState)
                            (fadeIn(tween(300)) + slideInVertically(tween(350)) { it / 4 }) togetherWith (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 })
                        else
                            (fadeIn(tween(300)) + slideInVertically(tween(350)) { -it / 4 }) togetherWith (fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 6 })
                    },
                    label = "mainContent"
                ) { authOpen ->
                    if (!authOpen) {
                        Column(Modifier.fillMaxWidth()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = colors.surface,
                                    shadowElevation = 4.dp,
                                    border = BorderStroke(1.dp, colors.divider)
                                ) {
                                    Column(Modifier.padding(20.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Search, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text("Track a package", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        OutlinedTextField(
                                            value = trackingCode, onValueChange = { trackingCode = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("Enter tracking number", color = colors.textSecondary, fontSize = 14.sp) },
                                            leadingIcon = { Icon(Icons.Filled.Tag, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp)) },
                                            trailingIcon = {
                                                if (trackingCode.isNotEmpty()) IconButton(onClick = { trackingCode = "" }, modifier = Modifier.size(20.dp)) {
                                                    Icon(Icons.Filled.Close, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            singleLine = true, shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.divider, focusedBorderColor = colors.blue, cursorColor = colors.blue)
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Button(
                                            onClick = { if (trackingCode.isNotBlank()) onTrackByCode(trackingCode.trim()) },
                                            modifier = Modifier.fillMaxWidth().height(50.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                                        ) {
                                            Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Track", fontWeight = FontWeight.Bold)
                                        }
                                        if (trackError.isNotBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(trackError, color = colors.red, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { showAuth = true; isSignUp = false },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.5.dp, colors.blue),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.blue)
                                    ) { Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                    Button(
                                        onClick = { showAuth = true; isSignUp = true },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.blue)
                                    ) { Text("Sign Up", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                                Surface(
                                    onClick = { showAuth = false }, modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp), color = colors.surfaceAlt, border = BorderStroke(1.dp, colors.divider)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Track a package", color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                        Icon(Icons.Filled.ChevronRight, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                AuthPanel(
                                    isSignUp = isSignUp, onTabChange = { isSignUp = it },
                                    email = email, onEmailChange = { email = it },
                                    password = password, onPasswordChange = { password = it },
                                    fullName = fullName, onFullNameChange = { fullName = it },
                                    phone = phone, onPhoneChange = { phone = it },
                                    isAuthLoading = isAuthLoading, authResult = authResult,
                                    onSignUp = { if (email.isNotBlank() && password.isNotBlank() && fullName.isNotBlank() && phone.isNotBlank()) onSignUp(email.trim(), password, fullName.trim(), phone.trim()) },
                                    onSignIn = { if (email.isNotBlank() && password.isNotBlank()) onSignIn(email.trim(), password) },
                                    onClearAuthResult = onClearAuthResult,
                                    onShowForgotPassword = onShowForgotPassword,
                                    showForgotPassword = showForgotPassword,
                                    forgotPasswordStep = forgotPasswordStep,
                                    onForgotPassword = onForgotPassword,
                                    onResetPassword = onResetPassword,
                                    onHideForgotPassword = onHideForgotPassword
                                )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClientLoginScreen(
    onLogin: (String, String) -> Unit,
    onBack: () -> Unit,
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
    var email    by remember { mutableStateOf("") }
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
        accent = colors.green,
        logo = Icons.Filled.Inventory2,
        title = "CaVgo Client",
        subtitle = "Sign in to send & track packages",
        onBack = onBack
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider)
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Email or Phone") },
                    leadingIcon = { Icon(Icons.Filled.Person, null) }, singleLine = true,
                    enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLogin(email.trim(), password) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.green),
                    enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank()
                ) {
                    if (isAuthLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Log in", fontWeight = FontWeight.Bold)
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
                TextButton(onClick = onShowForgotPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Forgot Password?", color = colors.green, fontSize = 13.sp)
                }
            }
        }
    }

    if (showForgotPassword) {
        ForgotPasswordDialog(
            onDismiss = onHideForgotPassword, onSendCode = onForgotPassword,
            onResetPassword = onResetPassword, step = forgotPasswordStep, isLoading = isAuthLoading
        )
    }
}

@Composable
fun OtpVerificationScreen(
    email: String,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    isAuthLoading: Boolean = false,
    authResult: AuthResult? = null
) {
    val colors = LocalDriversColors.current
    var code by remember { mutableStateOf("") }

    AuthSplitLayout(
        accent = colors.blue,
        logo = Icons.Filled.MarkEmailRead,
        title = "Verify Your Email",
        subtitle = "Enter the verification code sent to"
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(email, color = colors.blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = code, onValueChange = { if (it.length <= 6) code = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Verification Code") },
                        leadingIcon = { Icon(Icons.Filled.Lock, null) }, singleLine = true,
                        enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp), placeholder = { Text("000000") }
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onVerify(code.trim()) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                        enabled = !isAuthLoading && code.length == 6
                    ) {
                        if (isAuthLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Verify", fontWeight = FontWeight.Bold)
                    }
                    if (authResult is AuthResult.Error) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(authResult.message, color = colors.red, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onResend, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = !isAuthLoading) {
                        Text("Resend Code", color = colors.blue, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onBack) { Text("Use a different email", color = colors.textSecondary, fontSize = 12.sp) }
        }
    }
}

// ── Auth panel (sign-in / sign-up form) ──────────────────────────────────────

@Composable
private fun AuthPanel(
    isSignUp: Boolean,
    onTabChange: (Boolean) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    fullName: String,
    onFullNameChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    isAuthLoading: Boolean,
    authResult: AuthResult?,
    onSignUp: () -> Unit,
    onSignIn: () -> Unit,
    onClearAuthResult: () -> Unit = {},
    onShowForgotPassword: () -> Unit = {},
    showForgotPassword: Boolean = false,
    forgotPasswordStep: Int = 0,
    onForgotPassword: (String) -> Unit = {},
    onResetPassword: (String, String) -> Unit = { _, _ -> },
    onHideForgotPassword: () -> Unit = {}
) {
    val colors = LocalDriversColors.current

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surface, shadowElevation = 6.dp, border = BorderStroke(1.dp, colors.divider)) {
        Column(Modifier.padding(16.dp)) {
            // Tab row
            Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceAlt) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(false to "Sign In", true to "Sign Up").forEach { (isUp, label) ->
                        Surface(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onTabChange(isUp) },
                            color = if (isSignUp == isUp) colors.blue else androidx.compose.ui.graphics.Color.Transparent
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (isSignUp == isUp) MaterialTheme.colorScheme.onPrimary else colors.textSecondary, fontSize = 14.sp, fontWeight = if (isSignUp == isUp) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = isSignUp,
                transitionSpec = { (slideInVertically(tween(250)) { it } + fadeIn(tween(250))) togetherWith (slideOutVertically(tween(200)) { -it } + fadeOut(tween(200))) },
                label = "authFields"
            ) { signUp ->
                Column {
                    if (signUp) {
                        OutlinedTextField(value = fullName, onValueChange = onFullNameChange, modifier = Modifier.fillMaxWidth(), label = { Text("Full Name") }, leadingIcon = { Icon(Icons.Filled.Person, null) }, singleLine = true, enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp))
                        Spacer(Modifier.height(10.dp))
                    }
                    OutlinedTextField(value = email, onValueChange = onEmailChange, modifier = Modifier.fillMaxWidth(), label = { Text(if (signUp) "Email" else "Email or Phone") }, leadingIcon = { Icon(Icons.Filled.Email, null) }, singleLine = true, enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = password, onValueChange = onPasswordChange, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !isAuthLoading, shape = RoundedCornerShape(12.dp))
                    if (signUp) {
                        Spacer(Modifier.height(10.dp))
                        PhoneInput(value = phone, onValueChange = onPhoneChange, modifier = Modifier.fillMaxWidth(), label = "Phone", enabled = !isAuthLoading)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = if (isSignUp) onSignUp else onSignIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue),
                enabled = !isAuthLoading && email.isNotBlank() && password.isNotBlank() && (!isSignUp || (fullName.isNotBlank() && phone.isNotBlank()))
            ) {
                if (isAuthLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (isSignUp) "Create Account" else "Sign In", fontWeight = FontWeight.Bold)
            }
            if (authResult is AuthResult.Error) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(authResult.message, color = colors.red, fontSize = 12.sp)
                }
            }
            if (!isSignUp) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onShowForgotPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Forgot Password?", color = colors.blue, fontSize = 12.sp)
                }
            }
        }
    }
    if (showForgotPassword) {
        ForgotPasswordDialog(onDismiss = onHideForgotPassword, onSendCode = onForgotPassword, onResetPassword = onResetPassword, step = forgotPasswordStep, isLoading = isAuthLoading)
    }
}
