package com.gocavgo.ikuriye

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gocavgo.ikuriye.data.AuthRepository
import com.gocavgo.ikuriye.data.PackageCache
import com.gocavgo.ikuriye.data.SettingsRepository
import com.gocavgo.ikuriye.service.LocationService
import com.gocavgo.ikuriye.ui.ClientHomeScreen
import com.gocavgo.ikuriye.ui.ClientLoginScreen
import com.gocavgo.ikuriye.ui.DriverHomeScreen
import com.gocavgo.ikuriye.ui.common.NoticesPanel
import com.gocavgo.ikuriye.ui.common.PickupCodeDialog
import com.gocavgo.ikuriye.ui.common.DeliveryConfirmationDialog
import com.gocavgo.ikuriye.ui.common.FloatingCreatePanel
import com.gocavgo.ikuriye.ui.LoginScreen
import com.gocavgo.ikuriye.ui.OtpVerificationScreen
import com.gocavgo.ikuriye.ui.PipTripView
import com.gocavgo.ikuriye.ui.ProfileScreen
import com.gocavgo.ikuriye.ui.RoleSelectionScreen
import com.gocavgo.ikuriye.ui.TrackPackageScreen
import com.gocavgo.ikuriye.ui.driver.AcceptTransferCodeDialog
import com.gocavgo.ikuriye.ui.driver.ConfirmTransferDialog
import com.gocavgo.ikuriye.ui.driver.RequestTransferDialog
import com.gocavgo.ikuriye.ui.driver.TransferCreationDialog
import com.gocavgo.ikuriye.ui.theme.IkuriyeTheme
import com.gocavgo.ikuriye.ui.theme.LocalDriversColors
import com.gocavgo.ikuriye.viewmodel.AppRole
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import com.gocavgo.ikuriye.viewmodel.TripViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CavgoMain"
    }

    private var locationReceiver: BroadcastReceiver? = null
    private var tripViewModel: TripViewModel? = null
    private var pendingLocationStart = false

    // This drives the UI swap — false = full screen, true = PiP compact
    private val isInPip = mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted   = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Log.i(TAG, "Location permission granted")
            pendingLocationStart = false
            // Only drivers use continuous background tracking; clients just get
            // foreground location access (no service, no background permission).
            if (tripViewModel?.state?.value?.appRole == AppRole.DRIVER) {
                startLocationService()
                requestBackgroundLocationIfNeeded()
                LocationService.requestBatteryOptimisationExemption(this)
            }
        } else {
            Log.w(TAG, "Location permission denied")
            pendingLocationStart = false
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Background location permission result: $granted")
    }

    private var pendingPipEnable = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this) && pendingPipEnable) {
            tripViewModel?.setPipEnabled(true)
        }
        pendingPipEnable = false
    }

    /**
     * Registers (or unregisters) PiP auto-enter via [PictureInPictureParams.setAutoEnterEnabled].
     * Available on API 31+. For older devices the [onUserLeaveHint] fallback is used.
     * Call this every time the driver/PiP state changes.
     */
    private fun updatePipParams() {
        val s = tripViewModel?.state?.value ?: return
        val shouldPip = s.appRole == AppRole.DRIVER && s.isLoggedIn && s.isAuthInitialized && s.isPipEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(shouldPip)
                    .setSourceRectHint(sourceRectHint())
                    .build()
                setPictureInPictureParams(params)
                Log.d(TAG, "updatePipParams: autoEnterEnabled=$shouldPip")
            } catch (e: Exception) {
                Log.e(TAG, "updatePipParams failed", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        AuthRepository.init(this)
        PackageCache.init(this)
        SettingsRepository.init(this)

        setContent {
            val vm: TripViewModel = viewModel()
            tripViewModel = vm

            val inPip by isInPip
            val state by vm.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val useDarkTheme = when (state.themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            // Keep screen awake — driver-only setting; never affects client
            // Keep screen awake — driver-only setting; never affects client
            SideEffect {
                val isDriver = state.appRole == AppRole.DRIVER
                if (isDriver && state.keepScreenAwake) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Register PiP auto-enter params whenever the relevant state changes.
            // On API 31+ the system handles PiP entry automatically — no race condition.
            LaunchedEffect(state.isPipEnabled, state.appRole, state.isLoggedIn, state.isAuthInitialized) {
                updatePipParams()
            }

            // Request location access on launch for both roles.
            // Only drivers use continuous background tracking (LocationService);
            // clients get foreground location access but never background
            // location, the tracking service, or battery-optimisation exemptions.
            LaunchedEffect(state.appRole) {
                if (state.appRole == AppRole.NONE) return@LaunchedEffect
                val driver = state.appRole == AppRole.DRIVER
                if (hasLocationPermissions()) {
                    if (driver) {
                        startLocationService()
                        requestBackgroundLocationIfNeeded()
                        LocationService.requestBatteryOptimisationExemption(this@MainActivity)
                    }
                } else {
                    pendingLocationStart = driver
                    requestPermissionsIfNeeded()
                }
            }

            // Stop the location service whenever we leave driver mode
            // (logout, role switch, or client session) — guarantees clients
            // are never background-tracked even after a driver session.
            LaunchedEffect(state.appRole, state.isLoggedIn) {
                if (state.appRole != AppRole.DRIVER || !state.isLoggedIn) {
                    stopLocationService()
                }
            }

            // Restore auth session on app start
            LaunchedEffect(Unit) {
                vm.restoreAuthSession()
            }

            // Handle auth result navigation
            LaunchedEffect(state.authResult) {
                when (state.authResult) {
                    is com.gocavgo.ikuriye.data.dto.AuthResult.Success -> {
                        vm.clearAuthResult()
                    }
                    else -> {}
                }
            }

            // Observe toast events for auth errors
            LaunchedEffect(Unit) {
                vm.toastEvent.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            IkuriyeTheme(darkTheme = useDarkTheme) {

                // ── Transfer Creation Dialog ──────────────────────────────────
                if (state.showTransferCreationDialog && state.transferCreationPackageId != null) {
                    TransferCreationDialog(
                        onDismiss = vm::closeTransferCreationDialog,
                        selectedRuleType = state.selectedTransferRuleType,
                        onRuleTypeChange = vm::setTransferRuleType,
                        onConfirm = vm::createTransfer,
                        isCreating = state.isCreatingTransfer,
                        userSearchResults = state.userSearchResults,
                        onUserSearch = { query -> vm.searchUsers(query, com.gocavgo.ikuriye.type.Role.DRIVER) },
                        onClearUserSearch = vm::clearUserSearch,
                        transferMatchUserId = state.transferMatchUserId,
                        transferMatchUserName = state.transferMatchUserName,
                        onMatchUserChange = vm::setTransferMatchUser,
                        onClearMatchUser = vm::clearTransferMatchUser
                    )
                }

                // ── Confirm Transfer Dialog ──────────────────────────────────
                if (state.showConfirmTransferDialog && state.confirmTransferId != null) {
                    ConfirmTransferDialog(
                        onDismiss = vm::closeConfirmTransferDialog,
                        onConfirm = vm::confirmTransferRequest,
                        isConfirming = state.isConfirmingTransfer
                    )
                }

                // ── Accept Transfer Code Dialog ──────────────────────────────
                if (state.showAcceptTransferCodeDialog) {
                    AcceptTransferCodeDialog(
                        codeInput = state.acceptTransferCodeInput,
                        codeError = state.acceptTransferCodeError,
                        onCodeChange = vm::updateAcceptTransferCode,
                        onConfirm = vm::acceptOfferViaTransfer,
                        onDismiss = vm::closeAcceptTransferCodeDialog,
                        isAccepting = state.isAcceptingTransfer,
                        requiresCode = state.acceptTransferRuleType == "SECURE"
                    )
                }

                // ── Pickup Code Dialog ───────────────────────────────────────
                val currentPickupCode = state.pickupCode
                if (currentPickupCode != null) {
                    PickupCodeDialog(
                        pickupCode = currentPickupCode,
                        expiryMs = state.pickupCodeExpiryMs,
                        onDismiss = vm::dismissPickupCode
                    )
                }

                // ── Delivery Confirmation Dialog (PENDING_CONFIRMATION) ──────
                val confirmPkgUuid = state.deliveryConfirmationPackageUuid
                if (state.showDeliveryConfirmationDialog && !state.deliveryConfirmationCode.isBlank() && confirmPkgUuid != null) {
                    DeliveryConfirmationDialog(
                        deliveryCode = state.deliveryConfirmationCode,
                        trackingCode = state.deliveryConfirmationTrackingCode,
                        recipientName = state.deliveryConfirmationRecipientName,
                        isConfirming = state.isConfirmingDelivery,
                        onConfirm = { vm.confirmDeliveryFromCode(confirmPkgUuid, state.deliveryConfirmationCode) },
                        onDismiss = vm::dismissDeliveryConfirmationDialog
                    )
                }

                // ── Request Transfer Dialog (CONFIRM type) ────────────────────
                if (state.showRequestTransferDialog) {
                    RequestTransferDialog(
                        onDismiss = vm::closeRequestTransferDialog,
                        onConfirm = vm::requestTransferForPackage,
                        isRequesting = state.isRequestingTransfer
                    )
                }

                // ── Push trip data into the service notification ──────────────
                if (state.appRole == AppRole.DRIVER && state.isLoggedIn && state.hasActiveTrip) {
                    val stop = vm.nextStop ?: vm.currentStop
                    LaunchedEffect(stop.name, stop.pickups.size, stop.dropoffs.size) {
                        val intent = Intent(this@MainActivity, LocationService::class.java).apply {
                            action = LocationService.ACTION_UPDATE_TRIP
                            putExtra(LocationService.EXTRA_STOP_NAME,     stop.name)
                            putExtra(LocationService.EXTRA_PICKUP_COUNT,  stop.pickups.size)
                            putExtra(LocationService.EXTRA_DROPOFF_COUNT, stop.dropoffs.size)
                        }
                        startService(intent)
                    }
                }
                // ─────────────────────────────────────────────────────────────

                when {
                    !state.isAuthInitialized -> {
                        val c = LocalDriversColors.current
                        Box(
                            Modifier.fillMaxSize().background(c.background),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = c.green, strokeWidth = 3.dp)
                        }
                    }
                    inPip && state.isPipEnabled -> PipTripView(viewModel = vm)
                    state.appRole == AppRole.NONE -> {
                        val publicPkg = state.publicTrackingPackage
                        if (publicPkg != null) {
                            BackHandler { vm.closePublicTracking() }
                            TrackPackageScreen(
                                pkg = publicPkg,
                                onBack = vm::closePublicTracking
                            )
                        } else {
                            DoubleBackToExitHandler()
                            RoleSelectionScreen(
                                onTrackByCode = vm::publicTrackPackage,
                                trackError = state.publicTrackingError,
                                authResult = state.authResult,
                                isAuthLoading = state.isAuthLoading,
                                onSignUp = vm::signUp,
                                onSignIn = vm::signIn,
                                onClearAuthResult = vm::clearAuthResult,
                                showOtpScreen = state.showOtpScreen,
                                otpEmail = state.otpEmail,
                                onVerifyOtp = vm::verifyOtp,
                                onResendOtp = vm::resendOtp,
                                onDismissOtp = vm::dismissOtpScreen,
                                showForgotPassword = state.showForgotPassword,
                                forgotPasswordStep = state.forgotPasswordStep,
                                onForgotPassword = vm::sendPasswordResetCode,
                                onResetPassword = vm::completePasswordReset,
                                onShowForgotPassword = vm::showForgotPassword,
                                onHideForgotPassword = vm::hideForgotPassword,
                                signInPrefillEmail = state.signInPrefillEmail,
                                onClearSignInPrefill = vm::clearSignInPrefill
                            )
                        }
                    }
                    state.appRole == AppRole.DRIVER -> {
                        if (state.showOtpScreen) {
                            BackHandler { vm.dismissOtpScreen() }
                            OtpVerificationScreen(
                                email = state.otpEmail,
                                onVerify = vm::verifyOtp,
                                onResend = vm::resendOtp,
                                onBack = vm::dismissOtpScreen,
                                isAuthLoading = state.isAuthLoading,
                                authResult = state.authResult
                            )
                        } else if (!state.isLoggedIn) {
                            BackHandler { vm.goBackToRoleSelect() }
                            LoginScreen(
                                onLogin = vm::signIn,
                                authResult = state.authResult,
                                isAuthLoading = state.isAuthLoading,
                                onClearAuthResult = vm::clearAuthResult,
                                onForgotPassword = vm::sendPasswordResetCode,
                                onResetPassword = vm::completePasswordReset,
                                showForgotPassword = state.showForgotPassword,
                                forgotPasswordStep = state.forgotPasswordStep,
                                onShowForgotPassword = vm::showForgotPassword,
                                onHideForgotPassword = vm::hideForgotPassword,
                                signInPrefillEmail = state.signInPrefillEmail,
                                onClearSignInPrefill = vm::clearSignInPrefill
                            )
                        } else if (state.isProfileOpen) {
                            BackHandler { vm.closeProfile() }
                            ProfileScreen(profile = state.driverProfile, viewModel = vm)
                        } else {
                            DoubleBackToExitHandler()
                            DriverHomeScreen(
                                viewModel = vm,
                                profile = state.driverProfile,
                                vehicle = state.vehicle,
                                driverHomeTab = state.driverHomeTab,
                                isDriverProfileMenuOpen = state.isDriverProfileMenuOpen,
                                isDriverSettingsOpen = state.isDriverSettingsOpen,
                                themeMode = state.themeMode,
                                isPipEnabled = state.isPipEnabled,
                                completedTrips = state.driverCompletedTrips,
                                isVehicleMenuOpen = state.isVehicleMenuOpen,
                                isProfileMenuOpen = state.isProfileMenuOpen,
                                isSettingsOpen = state.isSettingsOpen,
                                defaultPage = state.defaultPage,
                                keepScreenAwake = state.keepScreenAwake,
                                onCreatePackage = vm::openDriverCreatePackage,
                                onTabChange = vm::setDriverHomeTab,
                                onProfileMenuClick = vm::toggleDriverProfileMenu,
                                onSettingsClick = vm::openDriverSettings,
                                onThemeModeChange = vm::setThemeMode,
                                onCloseSettings = vm::closeDriverSettings,
                                onLogout = vm::logout,
                                onDismissMenus = vm::dismissDriverMenus,
                                onProfileClick = vm::openProfile,
                                onVehicleClick = vm::toggleVehicleMenu,
                                onEditProfileClick = vm::openProfile,
                                onPipEnabledChange = ::handlePipToggle,
                                onDefaultPageChange = vm::setDefaultPage,
                                onKeepScreenAwakeChange = vm::setKeepScreenAwake,
                                onNoticesClick = vm::toggleNotices,
                                noticeCount = state.noticeCount
                            )
                        }
                    }
                    state.appRole == AppRole.CLIENT -> {
                        // Back press handling for client sub-screens.
                        // Note: isCreatingPackage is handled by the FloatingCreatePanel Dialog itself.
                        if (state.isTrackingPackage) {
                            BackHandler { vm.closeTrackPackage() }
                        } else if (state.isProfileOpen) {
                            BackHandler { vm.closeProfile() }
                        } else if (!state.isClientLoggedIn) {
                            BackHandler { vm.goBackToRoleSelect() }
                        }

                        // Key for animated transitions between client screens
                        // ("create" is now handled by [FloatingCreatePanel] overlay)
                        val clientScreenKey = when {
                            state.showOtpScreen -> "otp"
                            state.isTrackingPackage -> "track"
                            state.isProfileOpen -> "profile"
                            !state.isClientLoggedIn -> "login"
                            else -> "home"
                        }
                        // Stable background behind animated transitions prevents white flash
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            AnimatedContent(
                                targetState = clientScreenKey,
                                transitionSpec = {
                                    when {
                                        targetState == "home" && initialState == "login" ->
                                            (fadeIn(tween(350)) + slideInHorizontally(tween(350)) { it / 3 }) togetherWith
                                                    (fadeOut(tween(200)))
                                        targetState == "login" && initialState == "home" ->
                                            (fadeIn(tween(250))) togetherWith
                                                    (fadeOut(tween(250)) + slideOutHorizontally(tween(250)) { -it / 3 })
                                        else ->
                                            (fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 5 }) togetherWith
                                                    (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 5 })
                                    }
                                },
                                label = "clientScreen"
                            ) { screen ->
                            when (screen) {
                                "otp" -> OtpVerificationScreen(
                                    email = state.otpEmail,
                                    onVerify = vm::verifyOtp,
                                    onResend = vm::resendOtp,
                                    onBack = vm::dismissOtpScreen,
                                    isAuthLoading = state.isAuthLoading,
                                    authResult = state.authResult
                                )
                                "track" -> {
                                    val selectedPkg = vm.getSelectedPackage()
                                    if (selectedPkg != null) {
                                        TrackPackageScreen(
                                            pkg = selectedPkg,
                                            onBack = vm::closeTrackPackage,
                                            currentUserId = state.clientProfile.id,
                                            onCreateTransfer = vm::openTransferCreationDialog,
                                            onConfirmTransfer = vm::openConfirmTransferDialog,
                                            onGeneratePickupCode = vm::generatePickupCode,
                                            onConfirmDelivery = vm::confirmDeliveryFromCode,
                                            pendingDeliveryCode = if (state.deliveryConfirmationPackageUuid == selectedPkg.packageUuid) state.deliveryConfirmationCode else ""
                                        )
                                    }
                                }
                                "profile" -> ProfileScreen(
                                    profile = DriverProfile(
                                        name = state.clientProfile.name,
                                        email = state.clientProfile.email,
                                        phone = state.clientProfile.phone,
                                        username = state.clientProfile.username,
                                        avatarUrl = state.clientProfile.avatarUrl
                                    ),
                                    viewModel = vm
                                )
                                "login" -> ClientLoginScreen(
                                    onLogin = vm::signIn,
                                    onBack = vm::goBackToRoleSelect,
                                    authResult = state.authResult,
                                    isAuthLoading = state.isAuthLoading,
                                    onClearAuthResult = vm::clearAuthResult,
                                    onForgotPassword = vm::sendPasswordResetCode,
                                    onResetPassword = vm::completePasswordReset,
                                    showForgotPassword = state.showForgotPassword,
                                    forgotPasswordStep = state.forgotPasswordStep,
                                    onShowForgotPassword = vm::showForgotPassword,
                                    onHideForgotPassword = vm::hideForgotPassword,
                                    signInPrefillEmail = state.signInPrefillEmail,
                                    onClearSignInPrefill = vm::clearSignInPrefill
                                )
                                else -> {
                                    val hasUnsavedDraftForExit = !state.isCreatingPackage &&
                                        (state.createPackageForm.fromAddress.isNotBlank() ||
                                         state.createPackageForm.description.isNotBlank() ||
                                         state.createPackageForm.category.isNotBlank() ||
                                         state.createPackageForm.weight.isNotBlank() ||
                                         state.createPackageForm.recipientName.isNotBlank() ||
                                         state.createPackageForm.toAddress.isNotBlank() ||
                                         state.createPackageForm.recipientPhone.isNotBlank() ||
                                         state.mediaUploads.isNotEmpty())
                                    DoubleBackToExitHandler(
                                        hasUnsavedDraft = hasUnsavedDraftForExit,
                                        onDiscardDraft = vm::resetCreatePackageForm
                                    )
                                    val hasUnsavedDraft = !state.isCreatingPackage &&
                                        (state.createPackageForm.fromAddress.isNotBlank() ||
                                         state.createPackageForm.description.isNotBlank() ||
                                         state.createPackageForm.category.isNotBlank() ||
                                         state.createPackageForm.weight.isNotBlank() ||
                                         state.createPackageForm.recipientName.isNotBlank() ||
                                         state.createPackageForm.toAddress.isNotBlank() ||
                                         state.createPackageForm.recipientPhone.isNotBlank() ||
                                         state.mediaUploads.isNotEmpty())
                                    ClientHomeScreen(
                                        client = state.clientProfile,
                                        packages = state.clientPackages,
                                        themeMode = state.themeMode,
                                        isClientProfileMenuOpen = state.isClientProfileMenuOpen,
                                        isClientSettingsOpen = state.isClientSettingsOpen,
                                        onCreatePackage = vm::openCreatePackage,
                                        onTrackPackage = vm::trackPackage,
                                        onLogout = vm::clientLogout,
                                        onProfileMenuClick = vm::toggleClientProfileMenu,
                                        onSettingsClick = vm::openClientSettings,
                                        onProfileClick = vm::openProfile,
                                        isRefreshing = state.isRefreshingPackages,
                                        isInitialLoading = state.isClientInitialLoading,
                                        onRefresh = vm::refreshClientPackages,
                                        isLoadingMore = state.isLoadingMorePackages,
                                        onLoadMore = vm::loadMoreClientPackages,
                                        onThemeModeChange = vm::setThemeMode,
                                        onCloseSettings = vm::closeClientSettings,
                                        onDismissMenus = vm::dismissClientMenus,
                                        onCreateTransfer = vm::openTransferCreationDialog,
                                        onConfirmTransfer = vm::openConfirmTransferDialog,
                                        onGeneratePickupCode = vm::generatePickupCode,
                                        hasUnsavedDraft = hasUnsavedDraft,
                                        packagesFetchedOnce = state.clientPackagesFetchedOnce,
                                        clientDataState = state.clientDataState,
                                        onNoticesClick = vm::toggleNotices,
                                        noticeCount = state.noticeCount
                                    )
                                }
                            }
                        }
                        } // closes AnimatedContent

                        // ── FloatingCreatePanel overlay (client side) ────────
                        // Always dismissable — user can close via swipe-down or close button.
                        // If the form has data, a confirm dialog shows before dismissing.
                        if (state.isCreatingPackage) {
                            val hasUnsavedPackageData = state.createPackageForm.fromAddress.isNotBlank() ||
                                state.createPackageForm.description.isNotBlank() ||
                                state.createPackageForm.category.isNotBlank() ||
                                state.createPackageForm.weight.isNotBlank() ||
                                state.createPackageForm.recipientName.isNotBlank() ||
                                state.createPackageForm.toAddress.isNotBlank() ||
                                state.createPackageForm.recipientPhone.isNotBlank() ||
                                state.mediaUploads.isNotEmpty()
                            FloatingCreatePanel(
                                visible = true,
                                dismissable = true,
                                onDiscardDraft = vm::resetCreatePackageForm,
                                hasUnsavedData = hasUnsavedPackageData,
                                onDismiss = vm::closeCreatePackage,
                                formState = state.createPackageForm,
                                onFormFieldChange = vm::updateCreatePackageFormField,
                                onFragileChange = vm::updateCreatePackageFragile,
                                isSubmitting = state.isSubmittingPackage,
                                userSearchResults = state.userSearchResults,
                                onUserSearch = vm::searchUsers,
                                onClearUserSearch = vm::clearUserSearch,
                                mediaUploads = state.mediaUploads,
                                onAddMedia = vm::addMediaForUpload,
                                onCancelUpload = vm::cancelMediaUpload,
                                onRemoveMedia = vm::removeMedia,
                                onSubmit = { pkg -> vm.createPackage(pkg) }
                            )
                        }
                    } // closes Box wrapper
                } // closes AnimatedContent

                // ── Notices Panel overlay (works for both driver & client) ──
                BackHandler(enabled = state.showNoticesPanel) { vm.dismissNotices() }
                NoticesPanel(
                    notices = state.notices,
                    visible = state.showNoticesPanel,
                    onDismiss = vm::dismissNotices,
                    onNoticeClick = vm::openNoticeFromNotification
                )

                // ── FloatingCreatePanel overlay (driver side) ────────────────
                if (state.appRole == AppRole.DRIVER && state.isDriverCreatingPackage) {
                    val hasUnsavedPackageData = state.createPackageForm.fromAddress.isNotBlank() ||
                        state.createPackageForm.description.isNotBlank() ||
                        state.createPackageForm.category.isNotBlank() ||
                        state.createPackageForm.weight.isNotBlank() ||
                        state.createPackageForm.recipientName.isNotBlank() ||
                        state.createPackageForm.toAddress.isNotBlank() ||
                        state.createPackageForm.recipientPhone.isNotBlank() ||
                        state.createPackageForm.senderName.isNotBlank() ||
                        state.createPackageForm.senderPhone.isNotBlank() ||
                        state.mediaUploads.isNotEmpty()
                    FloatingCreatePanel(
                        visible = true,
                        onDiscardDraft = vm::resetCreatePackageForm,
                        hasUnsavedData = hasUnsavedPackageData,
                        onDismiss = vm::closeDriverCreatePackage,
                        formState = state.createPackageForm,
                        onFormFieldChange = vm::updateCreatePackageFormField,
                        onFragileChange = vm::updateCreatePackageFragile,
                        showSenderFields = true,
                        isSubmitting = state.isSubmittingPackage,
                        userSearchResults = state.userSearchResults,
                        onUserSearch = vm::searchUsers,
                        onClearUserSearch = vm::clearUserSearch,
                        mediaUploads = state.mediaUploads,
                        onAddMedia = vm::addMediaForUpload,
                        onCancelUpload = vm::cancelMediaUpload,
                        onRemoveMedia = vm::removeMedia,
                        onSubmit = { pkg -> vm.createPackage(pkg) }
                    )
                }
            }
        }
    }

    // Android calls this whenever PiP state changes (enter or exit)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip.value = isInPictureInPictureMode
        Log.d(TAG, "PiP mode changed: $isInPictureInPictureMode")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = tripViewModel?.state?.value
        val pipActuallyEnabled = s?.isPipEnabled == true
        val isDriverReady = s?.appRole == AppRole.DRIVER && s.isLoggedIn && s.isAuthInitialized
        Log.d(TAG, "onUserLeaveHint: pipEnabled=$pipActuallyEnabled driverReady=$isDriverReady role=${s?.appRole} sdk=${Build.VERSION.SDK_INT}")

        // On API 31+ the system handles auto-enter via setPictureInPictureParams.
        // This fallback is for API 26-30 where auto-enter is not available.
        if (pipActuallyEnabled && isDriverReady && Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                // Always call setPictureInPictureParams BEFORE enterPictureInPictureMode
                // to prevent "Current activity does not support picture-in-picture" crash
                // that occurs when the LaunchedEffect-driven updatePipParams() hasn't
                // completed before the user leaves the app.
                setPictureInPictureParams(params)
                val success = enterPictureInPictureMode(params)
                Log.d(TAG, "onUserLeaveHint: enterPictureInPictureMode returned $success")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enter PiP mode", e)
            }
        }
    }

    /** Current window bounds — used as the PiP source-rect hint (API 31+). */
    private fun sourceRectHint(): Rect {
        val rect = Rect()
        if (window.decorView.isAttachedToWindow) {
            window.decorView.getGlobalVisibleRect(rect)
        }
        return rect
    }

    private fun handlePipToggle(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            pendingPipEnable = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            tripViewModel?.setPipEnabled(enabled)
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)

        if (permissionsNeeded.isNotEmpty()) {
            locationPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            if (pendingLocationStart) {
                pendingLocationStart = false
                startLocationService()
                requestBackgroundLocationIfNeeded()
                LocationService.requestBatteryOptimisationExemption(this)
            }
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "Location service start requested")
        } catch (e: Exception) {
            // Guard against ForegroundServiceStartNotAllowedException (API 31+ when
            // started from background) and SecurityException — never crash the app.
            Log.e(TAG, "Failed to start location service: ${e.message}", e)
        }
    }

    private fun stopLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
        Log.i(TAG, "Location service stop requested")
    }

    override fun onStart() {
        super.onStart()
        registerLocationReceiver()
    }

    override fun onStop() {
        super.onStop()
        locationReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        locationReceiver = null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLocationReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val lat   = intent?.getDoubleExtra(LocationService.EXTRA_LAT, 0.0) ?: return
                val lng   = intent.getDoubleExtra(LocationService.EXTRA_LNG, 0.0)
                val acc   = intent.getFloatExtra(LocationService.EXTRA_ACCURACY, 0f)
                val speed = intent.getFloatExtra(LocationService.EXTRA_SPEED, 0f)
                tripViewModel?.updateLocation(lat, lng, acc, speed)
            }
        }
        locationReceiver = receiver
        val filter = IntentFilter(LocationService.ACTION_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Using RECEIVER_NOT_EXPORTED to prevent other apps from sending
            // fake location broadcasts that could inject spoofed GPS coordinates.
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}

// ── Helper: double-back-to-exit with toast — 30-second window ────────────────
// If [hasUnsavedDraft] is true, shows a confirmation dialog instead of the toast.

@Composable
private fun DoubleBackToExitHandler(
    hasUnsavedDraft: Boolean = false,
    onDiscardDraft: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val colors = com.gocavgo.ikuriye.ui.theme.LocalDriversColors.current
    var showExitDialog by remember { mutableStateOf(false) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val toast = remember {
        Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT)
    }

    // ── Exit confirmation dialog for unsaved drafts ────────────────────
    if (showExitDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = colors.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            title = {
                androidx.compose.material3.Text(
                    "Unsaved draft",
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = colors.textPrimary
                )
            },
            text = {
                androidx.compose.material3.Text(
                    "You have a package draft that hasn't been sent. Discard it and exit?",
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        showExitDialog = false
                        onDiscardDraft?.invoke()
                        activity?.finish()
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.red)
                ) {
                    androidx.compose.material3.Text("Discard & Exit", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                    androidx.compose.material3.Text("Keep Editing", color = colors.blue, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        )
    }

    BackHandler {
        if (hasUnsavedDraft) {
            showExitDialog = true
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 30000L) {
                toast.cancel()
                activity?.finish()
            } else {
                lastBackPressTime = now
                toast.show()
            }
        }
    }
}
