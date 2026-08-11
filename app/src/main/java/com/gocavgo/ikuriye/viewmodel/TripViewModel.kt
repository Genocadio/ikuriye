package com.gocavgo.ikuriye.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.data.AuthRepository
import com.gocavgo.ikuriye.data.SettingsRepository
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ClientUser
import com.gocavgo.ikuriye.data.PackageCache
import com.gocavgo.ikuriye.data.PackageRepository
import com.gocavgo.ikuriye.data.Notice
import com.gocavgo.ikuriye.data.NoticeRepository
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.data.StatusUpdate
import com.gocavgo.ikuriye.type.CreatePackageInput
import com.gocavgo.ikuriye.type.DeliveryType
import com.gocavgo.ikuriye.type.DetailInput
import com.gocavgo.ikuriye.type.LocationInput
import com.gocavgo.ikuriye.type.LocationType
import com.gocavgo.ikuriye.type.PersonInput
import com.gocavgo.ikuriye.type.PersonRole
import com.apollographql.apollo.api.Optional
import com.gocavgo.ikuriye.data.TripStop
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.data.dto.AuthUserDto
import com.gocavgo.ikuriye.data.dto.SignInInput
import com.gocavgo.ikuriye.data.dto.SignUpInput
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.supa.SupaAuth
import com.gocavgo.ikuriye.supa.SupaClient
import com.gocavgo.ikuriye.supa.SupaMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TripViewModel : ViewModel() {

    private val _state = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    // ── New package subscription (driver-side real-time) ────────────────────
    private var subscriptionJob: kotlinx.coroutines.Job? = null

    // Notice IDs whose delivery-confirmation popup was already shown once
    private val autoShownDeliveryNotices = mutableSetOf<String>()

    init {
        // ── Listen for JWT session expiry events — force logout ──
        viewModelScope.launch {
            AuthRepository.sessionExpired.collect {
                if (BuildConfig.DEBUG) Log.d("TripViewModel", "Session expired — forcing logout")
                stopPackageSubscription()
                logout()
            }
        }
    }

    val stops get() = _state.value.trip.stops

    val currentStop: TripStop
        get() = stops.getOrNull(_state.value.currentStopIndex) ?: TripStop(id = 0, name = "", address = "", lat = 0.0, lng = 0.0, pickups = emptyList(), dropoffs = emptyList())

    val nextStop: TripStop?
        get() {
            val next = _state.value.currentStopIndex + 1
            return if (next < stops.size) stops[next] else null
        }

    fun restoreAuthSession() {
        // Load persisted settings immediately so the correct tab/theme is ready
        // even before the auth check completes
        _state.update {
            it.copy(
                defaultPage      = SettingsRepository.getDefaultPage(),
                keepScreenAwake  = SettingsRepository.getKeepScreenAwake(),
                themeMode        = SettingsRepository.getThemeMode(),
                isPipEnabled     = SettingsRepository.getPipEnabled()
            )
        }
        // Apply the persisted default page to the tab state
        applyDefaultPage(SettingsRepository.getDefaultPage())

        // Use cached user immediately — no loading spinner.
        // We DO NOT call AuthRepository.isLoggedIn() here because that checks
        // the Supabase client session, which hasn't been restored yet
        // (we intentionally removed the runBlocking from SupaAuth.init() to
        // avoid ANR). Instead, we check the SharedPreferences-based cached user.
        val cached = AuthRepository.getCachedUser()
        if (cached != null) {
            // Show UI immediately with cached user data.
            // CRITICAL: Do NOT call applyUser(cached) here because that triggers
            // fetchClientPackages() which makes a GraphQL request with null token
            // (Supabase session hasn't been restored yet). Instead, only set the
            // UI-relevant state fields, and defer network calls to the background block.
            _state.update {
                it.copy(
                    authUser = cached,
                    appRole = when (cached.role.name) {
                        "DRIVER" -> AppRole.DRIVER
                        else -> AppRole.CLIENT
                    },
                    isLoggedIn = cached.role.name == "DRIVER",
                    isClientLoggedIn = cached.role.name != "DRIVER"
                )
            }
            // Apply driver/client profile from cached data
            val fullName = listOfNotNull(cached.firstName, cached.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { if (cached.role.name == "DRIVER") "Driver" else "Customer" }
            when (cached.role.name) {
                "DRIVER" -> {
                    _state.update {
                        it.copy(
                            driverProfile = DriverProfile(
                                name = fullName,
                                phone = cached.phone ?: it.driverProfile.phone,
                                email = cached.email,
                                username = cached.username,
                                avatarUrl = cached.avatarUrl
                            )
                        )
                    }
                    restoreNavigationState()
                }
                else -> {
                    _state.update {
                        it.copy(
                            clientProfile = ClientUser(
                                id = cached.id,
                                name = fullName,
                                email = cached.email,
                                phone = cached.phone ?: it.clientProfile.phone,
                                password = "",
                                username = cached.username,
                                avatarUrl = cached.avatarUrl
                            )
                        )
                    }
                    restoreNavigationState()
                }
            }
            _state.update { it.copy(isAuthInitialized = true) }

            // Background: verify the SDK auto-loaded the session from storage,
            // then sync with backend. Network calls (fetchClientPackages,
            // preloadDriverPackages) are deferred until the token is confirmed.
            viewModelScope.launch {
                // Step 1: check if the SDK auto-loaded the session from storage.
                // (autoSaveToStorage + autoLoadFromStorage + SharedPreferencesSessionManager
                // handle persistence automatically during init().)
                if (SupaAuth.getAccessToken() == null) {
                    Log.w("TripViewModel", "restoreSession: no token after auto-load, trying refresh")
                    val refreshed = SupaAuth.refreshSession()
                    if (!refreshed) {
                        Log.e("TripViewModel", "restoreSession: refresh also failed — session is invalid, logging out silently")
                        logout()
                        return@launch
                    }
                    Log.d("TripViewModel", "restoreSession: refresh succeeded — token now available")
                } else {
                    Log.d("TripViewModel", "restoreSession: session loaded from storage, token available")
                }

                // Step 2: start proactive session observation
                // This auto-saves on SDK-initiated token refreshes and
                // periodically checks token health as a safety net.
                SupaAuth.observeSession(this@TripViewModel.viewModelScope)

                // Step 2b: start notice feed (Realtime subscription + initial fetch)
                NoticeRepository.start(this@TripViewModel.viewModelScope)
                collectNotices()

                // Step 3: fetch initial data now that we have a valid session
                when (cached.role.name) {
                    "DRIVER" -> {
                        preloadDriverPackages()
                        startPackageSubscription()
                    }
                    else -> {
                        fetchClientPackages()
                    }
                }

                // Step 4: sync with backend — if offline, keep cached user, just notify
                val syncResult = AuthRepository.restoreSession()
                when (syncResult) {
                    is AuthResult.Success -> {
                        applyUser(syncResult.user)
                    }
                    is AuthResult.VerificationRequired -> {
                        // Session restored but user not fully confirmed
                    }
                    is AuthResult.EmailAlreadyExists -> {
                        // Not applicable during session restore — ignore
                    }
                    is AuthResult.Error -> {
                        Log.e("TripViewModel", "restoreSession: sync failed — ${syncResult.message}")
                        // Offline or backend unavailable — DO NOT sign out or reset state.
                        // The cached user is already applied above. Just notify so the
                        // user knows data may be stale.
                        if (!AuthRepository.isNetworkAvailable()) {
                            _toastEvent.emit("No internet connection — showing cached data")
                        } else {
                            _toastEvent.emit("Could not sync with server: ${syncResult.message}")
                        }
                    }
                    is AuthResult.Loading -> {}
                }
            }
        } else {
            _state.update { it.copy(isAuthInitialized = true) }
        }
    }

    private fun applyUser(user: AuthUserDto) {
        _state.update { it.copy(authUser = user) }
        val fullName = listOfNotNull(user.firstName, user.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "" }
        
        when (user.role.name) {
            "DRIVER" -> {
                _state.update {
                    it.copy(
                        appRole = AppRole.DRIVER,
                        isLoggedIn = true,
                        driverProfile = DriverProfile(
                            name = fullName.ifBlank { "Driver" },
                            phone = user.phone ?: it.driverProfile.phone,
                            email = user.email,
                            username = user.username,
                            avatarUrl = user.avatarUrl
                        )
                    )
                }
                restoreNavigationState()
                // Pre-load cached packages in background so they're ready
                // the moment the user opens the Packages tab
                preloadDriverPackages()
                // Start real-time subscription for new package transfers
                startPackageSubscription()
            }
            else -> {
                _state.update {
                    it.copy(
                        appRole = AppRole.CLIENT,
                        isClientLoggedIn = true,
                        clientProfile = ClientUser(
                            id = user.id,
                            name = fullName.ifBlank { "Customer" },
                            email = user.email,
                            phone = user.phone ?: it.clientProfile.phone,
                            password = "",
                            username = user.username,
                            avatarUrl = user.avatarUrl
                        )
                    )
                }
                restoreNavigationState()
                // NOTE: fetchClientPackages() is NOT called here during restoreAuthSession()
                // because it's handled separately after session restoration.
                // It IS called during fresh login (handleAuthResult -> applyUser -> fetchClientPackages)
                // where the session is already available.
            }
        }
    }

    fun signUp(email: String, password: String, fullName: String, phone: String?) {
        viewModelScope.launch {
            _state.update { it.copy(isAuthLoading = true, authResult = null) }
            val result = AuthRepository.signUp(SignUpInput(email, password, fullName, phone))
            _state.update { it.copy(isAuthLoading = false, authResult = result) }
            handleAuthResult(result)
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isAuthLoading = true, authResult = null) }
            val result = AuthRepository.signIn(SignInInput(email, password))
            _state.update { it.copy(isAuthLoading = false, authResult = result) }
            handleAuthResult(result)
            // Auto-show forgot password dialog after first invalid credentials attempt
            if (result is AuthResult.Error) {
                val msg = result.message
                if (msg.contains("Invalid", ignoreCase = true) &&
                    !msg.contains("Email not confirmed", ignoreCase = true)) {
                    _state.update { it.copy(showForgotPassword = true) }
                }
            }
        }
    }

    fun verifyOtp(code: String) {
        val email = _state.value.otpEmail
        if (email.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isAuthLoading = true, authResult = null) }
            val result = AuthRepository.verifyEmailOtpAndSync(email, code.trim())
            _state.update { it.copy(isAuthLoading = false, authResult = result, showOtpScreen = false, otpEmail = "") }
            handleAuthResult(result)
        }
    }

    fun resendOtp() {
        val email = _state.value.otpEmail
        if (email.isBlank()) return
        viewModelScope.launch {
            try {
                SupaAuth.resendEmailOtp(email)
                _toastEvent.emit("Verification code resent to $email")
            } catch (e: Exception) {
                Log.e("TripViewModel", "resendOtp failed: ${e.message}", e)
                Log.e("TripViewModel", "resendOtp failed: ${e.message}", e)
                _toastEvent.emit(e.message ?: "Failed to resend code")
            }
        }
    }

    fun dismissOtpScreen() {
        _state.update { it.copy(showOtpScreen = false, otpEmail = "") }
    }

    fun sendPasswordResetCode(email: String) {
        viewModelScope.launch {
            val error = AuthRepository.sendPasswordResetCode(email)
            if (error != null) {
                Log.e("TripViewModel", "sendPasswordResetCode failed for $email: $error")
                _toastEvent.emit(error)
            } else {
                _state.update { it.copy(forgotPasswordEmail = email, forgotPasswordStep = 1) }
                _toastEvent.emit("Reset code sent to $email")
            }
        }
    }

    fun completePasswordReset(code: String, newPassword: String) {
        val email = _state.value.forgotPasswordEmail
        if (email.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isAuthLoading = true) }
            val error = AuthRepository.completePasswordReset(email, code.trim(), newPassword)
            _state.update { it.copy(isAuthLoading = false) }
            if (error != null) {
                Log.e("TripViewModel", "completePasswordReset failed: $error")
                _toastEvent.emit(error)
            } else {
                _state.update { it.copy(forgotPasswordStep = 2) }
                _toastEvent.emit("Password reset successful")
            }
        }
    }

    fun showForgotPassword() {
        _state.update { it.copy(showForgotPassword = true, forgotPasswordEmail = "", forgotPasswordStep = 0) }
    }

    fun hideForgotPassword() {
        _state.update { it.copy(showForgotPassword = false, forgotPasswordEmail = "", forgotPasswordStep = 0) }
    }

    fun clearAuthResult() {
        _state.update { it.copy(authResult = null) }
    }

    fun clearSignInPrefill() {
        _state.update { it.copy(signInPrefillEmail = "") }
    }

    private fun handleAuthResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                val user = result.user
                _state.update { it.copy(authUser = user) }
                when (user.role.name) {
                    "DRIVER" -> {
                        _state.update {
                            it.copy(
                                appRole = AppRole.DRIVER,
                                isLoggedIn = true,
                                driverProfile = DriverProfile(
                                    name = listOfNotNull(user.firstName, user.lastName)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" ")
                                        .ifBlank { "Driver" },
                                    phone = user.phone ?: it.driverProfile.phone,
                                    email = user.email
                                )
                            )
                        }
                    }
                    else -> {
                        _state.update {
                            it.copy(
                                appRole = AppRole.CLIENT,
                                isClientLoggedIn = true,
                                clientProfile = ClientUser(
                                    id = user.id,
                                    name = listOfNotNull(user.firstName, user.lastName)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" ")
                                        .ifBlank { "Customer" },
                                    email = user.email,
                                    phone = user.phone ?: it.clientProfile.phone,
                                    password = ""
                                )
                            )
                        }
                        fetchClientPackages()
                    }
                }
                // Start notice feed on fresh login
                NoticeRepository.start(this@TripViewModel.viewModelScope)
                collectNotices()
            }
            is AuthResult.Loading -> { /* no-op */ }
            is AuthResult.VerificationRequired -> {
                Log.d("TripViewModel", "Email verification required for: ${result.email}")
                _state.update {
                    it.copy(showOtpScreen = true, otpEmail = result.email)
                }
            }
            is AuthResult.EmailAlreadyExists -> {
                Log.d("TripViewModel", "Email already exists, pre-filling login: ${result.email}")
                _state.update {
                    it.copy(signInPrefillEmail = result.email, authResult = null)
                }
            }
            is AuthResult.Error -> {
                Log.e("TripViewModel", "Auth error: ${result.message}")
                viewModelScope.launch {
                    _toastEvent.emit(result.message)
                }
            }
        }
    }

    fun logout() {
        NoticeRepository.stop()
        stopPackageSubscription()
        viewModelScope.launch {
            AuthRepository.signOut()
        }
        SettingsRepository.clear()
        _state.update {
            it.copy(
                isLoggedIn = false,
                isProfileOpen = false,
                isProfileMenuOpen = false,
                isSettingsOpen = false,
                isCompletedTripsOpen = false,
                isVehicleMenuOpen = false,
                appRole = AppRole.NONE,
                authResult = null,
                authUser = null,
                // Reset settings to defaults after logout
                defaultPage     = "trips",
                keepScreenAwake = false,
                themeMode       = AppThemeMode.SYSTEM,
                isPipEnabled    = false,
                driverHomeTab   = 0,
                driverPackageSubTab = 0
            )
        }
    }

    fun openProfile() {
        _state.update {
            it.copy(
                isProfileOpen = true,
                isProfileMenuOpen = false,
                isSettingsOpen = false,
                isCompletedTripsOpen = false,
                isVehicleMenuOpen = false,
                isClientProfileMenuOpen = false,
                isClientSettingsOpen = false
            )
        }
        saveNavigationState()
    }

    fun closeProfile() {
        _state.update { it.copy(isProfileOpen = false) }
        saveNavigationState()
    }

    fun toggleProfileMenu() {
        _state.update {
            it.copy(
                isProfileMenuOpen = !it.isProfileMenuOpen,
                isSettingsOpen = false,
                isVehicleMenuOpen = false
            )
        }
    }

    fun closeFloatingMenus() {
        _state.update {
            it.copy(
                isProfileMenuOpen = false,
                isVehicleMenuOpen = false,
                isSettingsOpen = false,
                isCompletedTripsOpen = false
            )
        }
    }

    fun openSettings() {
        _state.update { it.copy(isSettingsOpen = true, isProfileMenuOpen = false, isVehicleMenuOpen = false, isCompletedTripsOpen = false) }
    }

    fun closeSettings() {
        _state.update { it.copy(isSettingsOpen = false, isCompletedTripsOpen = false) }
    }

    fun toggleCompletedTrips() {
        _state.update { it.copy(isCompletedTripsOpen = !it.isCompletedTripsOpen) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        SettingsRepository.saveThemeMode(mode)
    }

    fun setPipEnabled(enabled: Boolean) {
        _state.update { it.copy(isPipEnabled = enabled) }
        SettingsRepository.savePipEnabled(enabled)
    }

    fun toggleVehicleMenu() {
        _state.update {
            it.copy(
                isVehicleMenuOpen = !it.isVehicleMenuOpen,
                isProfileMenuOpen = false,
                isSettingsOpen = false,
                isCompletedTripsOpen = false
            )
        }
    }

    fun closeVehicleMenu() {
        _state.update { it.copy(isVehicleMenuOpen = false) }
    }

    fun updateProfile(name: String, phone: String) {
        _state.update {
            it.copy(
                driverProfile = it.driverProfile.copy(
                    name = name.ifBlank { it.driverProfile.name },
                    phone = phone.ifBlank { it.driverProfile.phone }
                )
            )
        }
    }

    fun changePassword(password: String) {
        if (password.length >= 4) {
            _state.update { it.copy(password = password) }
        }
    }

    fun updatePassword(newPassword: String) {
        viewModelScope.launch {
            _state.update { it.copy(isUpdatingProfile = true) }
            val success = com.gocavgo.ikuriye.supa.SupaAuth.updatePassword(newPassword)
            _state.update { it.copy(isUpdatingProfile = false) }
            if (success) {
                _toastEvent.emit("Password updated successfully")
            } else {
                Log.e("TripViewModel", "updatePassword failed")
                _toastEvent.emit("Failed to update password")
            }
        }
    }

    fun onProfileImageSelected(byteArray: ByteArray, mimeType: String) {
        _state.update { it.copy(selectedProfileImage = byteArray, profileImageMimeType = mimeType) }
        
        viewModelScope.launch {
            _state.update { it.copy(isUploadingProfileImage = true, profileUpdateError = "") }

            val uploadedUrl = com.gocavgo.ikuriye.supa.SupaMedia.uploadMedia(
                client = com.gocavgo.ikuriye.supa.SupaAuth.client,
                bucket = BuildConfig.PROFILE_BUCKET,
                byteArray = byteArray,
                mimeType = mimeType
            )

            if (uploadedUrl != null) {
                // Cache the uploaded profile picture locally so it doesn't
                // need to re-download when viewing the profile.
                val ctx = AuthRepository.getAppContext()
                if (ctx != null) {
                    try {
                        com.gocavgo.ikuriye.cache.MediaCache.getInstance(ctx).cacheBytes(uploadedUrl, byteArray)
                    } catch (e: Exception) {
                        Log.e("TripViewModel", "Failed to cache profile image: ${e.message}")
                    }
                }
                val result = AuthRepository.updateProfile(avatarUrl = uploadedUrl)
                when (result) {
                    is AuthResult.Success -> {
                        applyUser(result.user)
                        _state.update { it.copy(isUploadingProfileImage = false, selectedProfileImage = null) }
                        _toastEvent.emit("Profile picture updated")
                    }
                    is AuthResult.Error -> {
                        _state.update { it.copy(isUploadingProfileImage = false, selectedProfileImage = null) }
                        _toastEvent.emit("Failed to save picture: ${result.message}")
                    }
                    else -> _state.update { it.copy(isUploadingProfileImage = false, selectedProfileImage = null) }
                }
            } else {
                _state.update { it.copy(isUploadingProfileImage = false, selectedProfileImage = null) }
                _toastEvent.emit("Failed to upload profile picture")
            }
        }
    }

    fun updateUserProfile(fullName: String, phone: String, username: String) {
        viewModelScope.launch {
            _state.update { it.copy(isUpdatingProfile = true, profileUpdateError = "") }

            val result = AuthRepository.updateProfile(
                fullName = fullName.ifBlank { null },
                phone = phone.ifBlank { null },
                username = username.ifBlank { null }
            )

            when (result) {
                is AuthResult.Success -> {
                    applyUser(result.user)
                    _state.update { it.copy(isUpdatingProfile = false) }
                    _toastEvent.emit("Profile updated successfully")
                }
                is AuthResult.Error -> {
                    _state.update { it.copy(isUpdatingProfile = false, profileUpdateError = result.message) }
                    _toastEvent.emit("Update failed: ${result.message}")
                }
                else -> {
                    _state.update { it.copy(isUpdatingProfile = false) }
                }
            }
        }
    }

    fun updateLocation(lat: Double, lng: Double, accuracy: Float, speedKmh: Float) {
        _state.update { it.copy(driverLocation = DriverLocation(lat, lng, accuracy, speedKmh)) }
    }

    fun arriveAtCurrentStop() {
        _state.update { it.copy(arrivedAtStop = true) }
    }

    fun advanceToNextStop() {
        val next = _state.value.currentStopIndex + 1
        if (next >= stops.size) {
            _state.update { it.copy(tripCompleted = true) }
        } else {
            _state.update { it.copy(currentStopIndex = next, arrivedAtStop = false) }
        }
    }

    fun departCurrentStop() {
        advanceToNextStop()
    }

    // ── Driver Home Methods ───────────────────────────────────────────────────

    fun setDriverHomeTab(tab: Int) {
        _state.update { it.copy(driverHomeTab = tab) }
    }
    
    fun setDefaultPage(page: String) {
        _state.update { it.copy(defaultPage = page) }
        applyDefaultPage(page)
        SettingsRepository.saveDefaultPage(page)
    }

    private fun applyDefaultPage(page: String) {
        when (page) {
            "trips"              -> _state.update { it.copy(driverHomeTab = 0) }
            "packages/current"   -> _state.update { it.copy(driverHomeTab = 1, driverPackageSubTab = 0) }
            "packages/available" -> _state.update { it.copy(driverHomeTab = 1, driverPackageSubTab = 1) }
        }
    }

    fun setKeepScreenAwake(keep: Boolean) {
        _state.update { it.copy(keepScreenAwake = keep) }
        SettingsRepository.saveKeepScreenAwake(keep)
    }

    fun toggleKeepScreenAwake() {
        val next = !_state.value.keepScreenAwake
        _state.update { it.copy(keepScreenAwake = next) }
        SettingsRepository.saveKeepScreenAwake(next)
    }

    fun openDriverCreatePackage() {
        _state.update { it.copy(isDriverCreatingPackage = true) }
        saveNavigationState()
    }

    fun closeDriverCreatePackage() {
        _state.update { it.copy(isDriverCreatingPackage = false) }
        saveNavigationState()
    }

    fun toggleDriverProfileMenu() {
        _state.update { it.copy(isDriverProfileMenuOpen = !_state.value.isDriverProfileMenuOpen) }
    }

    fun openDriverSettings() {
        _state.update { it.copy(isDriverSettingsOpen = true, isDriverProfileMenuOpen = false) }
    }

    fun closeDriverSettings() {
        _state.update { it.copy(isDriverSettingsOpen = false) }
    }

    fun dismissDriverMenus() {
        _state.update { it.copy(isDriverProfileMenuOpen = false, isDriverSettingsOpen = false) }
    }

    // ── Driver Package Methods ──────────────────────────────────────────────

    fun setDriverPackageSubTab(tab: Int) {
        _state.update { it.copy(driverPackageSubTab = tab) }
    }

    fun updateDriverPackageSearch(query: String) {
        _state.update { it.copy(driverPackageSearchQuery = query) }
    }

    fun acceptOffer(offerId: String) {
        _state.update { s ->
            val offer = s.driverAvailableOffers.find { it.id == offerId } ?: return@update s
            val accepted = offer.copy(
                status = PackageStatus.PICKED_UP,
                driverName = s.driverProfile.name,
                driverPhone = s.driverProfile.phone,
                driverCompany = "QuickCargo Ltd",
                vehicleType = "car",
                deliveryCode = offer.id.replace("PKG", "DLV"),
                statusHistory = listOf(StatusUpdate(PackageStatus.PICKED_UP, "Just now", offer.fromAddress, "Offer accepted — heading to pickup"))
            )
            s.copy(
                    driverCurrentPackages = listOf(accepted) + s.driverCurrentPackages,
                driverAvailableOffers = s.driverAvailableOffers.filter { it.id != offerId }
            )
        }
    }

    fun openDeliverDialog(packageId: String) {
        val pkg = _state.value.driverCurrentPackages.find { it.id == packageId } ?: return
        if (pkg.status == PackageStatus.PENDING_CONFIRMATION) {
            // Delivery already initiated — just open the code entry dialog
            _state.update { it.copy(isDeliverDialogOpen = true, selectedDriverPackageId = packageId, deliverCodeInput = "", deliverCodeError = "") }
            return
        }
        if (pkg.packageUuid.isBlank()) {
            viewModelScope.launch { _toastEvent.emit("Missing package UUID — cannot initiate delivery") }
            return
        }
        // Driver initiates delivery — no code required. The sender/receiver
        // receives the PACKAGE_DELIVERY_INITIATED notice with the delivery code
        // and confirms in-app (or shares the code with the driver).
        viewModelScope.launch {
            _state.update { it.copy(isInitiatingDelivery = true) }
            val result = PackageRepository.initiateDelivery(pkg.packageUuid)
            if (result != null) {
                _state.update { s ->
                    s.copy(
                        isInitiatingDelivery = false,
                        isDeliverDialogOpen = true,
                        selectedDriverPackageId = packageId,
                        deliverCodeInput = "",
                        deliverCodeError = "",
                        driverCurrentPackages = s.driverCurrentPackages.map {
                            if (it.id == packageId) it.copy(
                                status = PackageStatus.PENDING_CONFIRMATION,
                                statusHistory = it.statusHistory + StatusUpdate(
                                    PackageStatus.PENDING_CONFIRMATION, "Just now", it.toAddress,
                                    "Delivery initiated — awaiting confirmation"
                                )
                            ) else it
                        }
                    )
                }
                _toastEvent.emit("Delivery initiated. Ask the recipient to confirm in their app, or enter the code they share with you.")
            } else {
                _state.update { it.copy(isInitiatingDelivery = false) }
                _toastEvent.emit("Failed to initiate delivery")
            }
        }
    }

    fun closeDeliverDialog() {
        _state.update { it.copy(isDeliverDialogOpen = false, selectedDriverPackageId = null, deliverCodeInput = "", deliverCodeError = "") }
    }

    fun updateDeliverCode(code: String) {
        _state.update { it.copy(deliverCodeInput = code, deliverCodeError = "") }
    }

    /**
     * Driver confirms delivery by entering the code the recipient shared.
     * Calls [PackageRepository.confirmDelivery] against the backend.
     */
    fun confirmDeliver(): Boolean {
        val s = _state.value
        val pkg = s.driverCurrentPackages.find { it.id == s.selectedDriverPackageId } ?: return false
        val code = s.deliverCodeInput.trim()
        if (code.isEmpty()) {
            _state.update { it.copy(deliverCodeError = "Enter the code shared by the recipient, or ask them to confirm in their app") }
            return false
        }
        if (pkg.packageUuid.isBlank()) {
            _state.update { it.copy(deliverCodeError = "Missing package UUID") }
            return false
        }
        viewModelScope.launch {
            _state.update { it.copy(isConfirmingDelivery = true, deliverCodeError = "") }
            val updated = PackageRepository.confirmDelivery(pkg.packageUuid, code)
            if (updated != null) {
                _state.update { s2 ->
                    s2.copy(
                        isConfirmingDelivery = false,
                        isDeliverDialogOpen = false,
                        selectedDriverPackageId = null,
                        deliverCodeInput = "",
                        deliverCodeError = "",
                        driverCurrentPackages = s2.driverCurrentPackages.map {
                            if (it.id == pkg.id) it.copy(
                                status = PackageStatus.DELIVERED,
                                receivedAt = "Just now",
                                statusHistory = it.statusHistory + StatusUpdate(
                                    PackageStatus.DELIVERED, "Just now", it.toAddress, "Delivered successfully"
                                )
                            ) else it
                        }
                    )
                }
                _toastEvent.emit("Package delivered")
            } else {
                _state.update { it.copy(isConfirmingDelivery = false, deliverCodeError = "Invalid or expired confirmation code") }
            }
        }
        return true
    }

    fun openTransferDialog(packageId: String) {
        _state.update { it.copy(isTransferDialogOpen = true, selectedDriverPackageId = packageId) }
    }

    fun closeTransferDialog() {
        _state.update { it.copy(isTransferDialogOpen = false, selectedDriverPackageId = null) }
    }

    fun confirmTransfer() {
        val s = _state.value
        val pkg = s.driverCurrentPackages.find { it.id == s.selectedDriverPackageId } ?: return
        if (pkg.packageUuid.isBlank()) {
            viewModelScope.launch { _toastEvent.emit("Missing package UUID — cannot create transfer") }
            return
        }

        // Initiate an AUTO transfer to the office — only workers can accept.
        viewModelScope.launch {
            _state.update { it.copy(isCreatingTransfer = true) }
            val result = PackageRepository.createTransfer(
                packageIds = listOf(pkg.packageUuid),
                ruleType = "AUTO"
            )
            if (result != null) {
                _state.update { s2 ->
                    s2.copy(
                        isCreatingTransfer = false,
                        isTransferDialogOpen = false,
                        selectedDriverPackageId = null,
                        driverCurrentPackages = s2.driverCurrentPackages.map {
                            if (it.id == pkg.id) it.copy(
                                transferId = result.id,
                                transferStatus = result.status,
                                statusHistory = it.statusHistory + StatusUpdate(
                                    PackageStatus.IN_TRANSIT, "Just now", it.toAddress,
                                    "Transfer initiated — awaiting office pickup"
                                )
                            ) else it
                        }
                    )
                }
                _toastEvent.emit("Transfer initiated — the office can now pick it up")
            } else {
                _state.update { it.copy(isCreatingTransfer = false) }
                _toastEvent.emit("Failed to create transfer")
            }
        }
    }

    fun openPackageDetail(packageId: String) {
        _state.update { it.copy(isPackageDetailSheetOpen = true, selectedDriverPackageId = packageId) }
    }

    fun closePackageDetail() {
        _state.update { it.copy(isPackageDetailSheetOpen = false, selectedDriverPackageId = null) }
    }

    fun getSelectedDriverPackage(): ClientPackage? {
        val s = _state.value
        val id = s.selectedDriverPackageId ?: return null
        return s.driverCurrentPackages.find { it.id == id }
            ?: s.driverAvailableOffers.find { it.id == id }
    }

    // ── Client Methods ────────────────────────────────────────────────────────

    // ── Navigation State Persistence (survives process death) ────────────────

    private fun saveNavigationState() {
        val s = _state.value
        when (s.appRole) {
            AppRole.CLIENT -> {
                val key = when {
                    s.showOtpScreen -> "otp"
                    s.isCreatingPackage -> "create"
                    s.isTrackingPackage -> "track"
                    s.isProfileOpen -> "profile"
                    !s.isClientLoggedIn -> "login"
                    else -> null // home screen — no restore needed
                }
                SettingsRepository.saveResumeState(key, s.clientSelectedPackageId)
            }
            AppRole.DRIVER -> {
                val key = when {
                    s.showOtpScreen -> "driver_otp"
                    s.isDriverCreatingPackage -> "driver_create"
                    s.isProfileOpen -> "driver_profile"
                    !s.isLoggedIn -> "driver_login"
                    else -> null
                }
                SettingsRepository.saveResumeState(key, null)
            }
            else -> SettingsRepository.clearResumeState()
        }
    }

    private fun restoreNavigationState() {
        val screenKey = SettingsRepository.getResumeScreenKey() ?: return
        when (screenKey) {
            "create" -> openCreatePackage()
            "track" -> {
                val pkgId = SettingsRepository.getResumePackageId()
                if (pkgId != null) trackPackage(pkgId)
            }
            "profile" -> openProfile()
            "driver_create" -> openDriverCreatePackage()
        }
        // Clear restore state after use so it doesn't re-apply on subsequent init
        SettingsRepository.clearResumeState()
    }

    fun goBackToRoleSelect() {
        NoticeRepository.stop()
        viewModelScope.launch {
            AuthRepository.signOut()
        }
        SettingsRepository.clear()
        _state.update {
            it.copy(
                appRole = AppRole.NONE,
                isLoggedIn = false,
                isClientLoggedIn = false,
                isProfileOpen = false,
                isProfileMenuOpen = false,
                isSettingsOpen = false,
                isCompletedTripsOpen = false,
                isVehicleMenuOpen = false,
                isCreatingPackage = false,
                isTrackingPackage = false,
                publicTrackingPackage = null,
                publicTrackingError = "",
                authResult = null,
                authUser = null
            )
        }
    }

    fun clientLogout() {
        NoticeRepository.stop()
        viewModelScope.launch {
            AuthRepository.signOut()
        }
        SettingsRepository.clear()
        _state.update {
            it.copy(
                isClientLoggedIn = false,
                isCreatingPackage = false,
                isTrackingPackage = false,
                clientSelectedPackageId = null,
                isClientProfileMenuOpen = false,
                isClientSettingsOpen = false,
                appRole = AppRole.NONE,
                authResult = null,
                authUser = null
            )
        }
    }

    fun toggleClientProfileMenu() {
        _state.update {
            it.copy(
                isClientProfileMenuOpen = !it.isClientProfileMenuOpen,
                isClientSettingsOpen = false
            )
        }
    }

    fun openClientSettings() {
        _state.update { it.copy(isClientSettingsOpen = true, isClientProfileMenuOpen = false) }
    }

    fun closeClientSettings() {
        _state.update { it.copy(isClientSettingsOpen = false) }
    }

    fun dismissClientMenus() {
        _state.update { it.copy(isClientProfileMenuOpen = false, isClientSettingsOpen = false) }
    }

    fun openCreatePackage() {
        _state.update { it.copy(isCreatingPackage = true, isTrackingPackage = false) }
        saveNavigationState()
    }

    fun closeCreatePackage() {
        _state.update { it.copy(isCreatingPackage = false) }
        saveNavigationState()
    }

    fun trackPackage(packageId: String) {
        _state.update {
            it.copy(
                isTrackingPackage = true,
                isCreatingPackage = false,
                clientSelectedPackageId = packageId
            )
        }
        saveNavigationState()
    }

    fun closeTrackPackage() {
        _state.update {
            it.copy(
                isTrackingPackage = false,
                clientSelectedPackageId = null
            )
        }
        saveNavigationState()
    }

    fun createPackage(pkg: ClientPackage) {
        Log.d("PackageMedia", "=== CREATE PACKAGE ===")
        Log.d("PackageMedia", "mediaUrls from ClientPackage: ${pkg.mediaUrls}")
        Log.d("PackageMedia", "mediaUrls count: ${pkg.mediaUrls.size}")

        viewModelScope.launch {
            _state.update { it.copy(isSubmittingPackage = true) }
            val s = _state.value
            val authUser = s.authUser

            val sender = if (s.appRole == AppRole.DRIVER) {
                PersonInput(
                    role = PersonRole.SENDER,
                    userId = Optional.absent(),
                    name = if (pkg.senderName.isNotBlank()) Optional.present(pkg.senderName) else Optional.absent(),
                    phone = if (pkg.senderPhone.isNotBlank()) Optional.present(pkg.senderPhone) else Optional.absent()
                )
            } else {
                val senderName = listOfNotNull(authUser?.firstName, authUser?.lastName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { null }
                PersonInput(
                    role = PersonRole.SENDER,
                    userId = if (authUser?.id != null) Optional.present(authUser.id) else Optional.absent(),
                    name = if (senderName != null) Optional.present(senderName) else Optional.absent(),
                    phone = if (authUser?.phone?.isNotBlank() == true) Optional.present(authUser.phone) else Optional.absent()
                )
            }

            val receiver = PersonInput(
                role = PersonRole.RECEIVER,
                name = if (pkg.recipientName.isNotBlank()) Optional.present(pkg.recipientName) else Optional.absent(),
                phone = if (pkg.recipientPhone.isNotBlank()) Optional.present(pkg.recipientPhone) else Optional.absent()
            )

            val origin = LocationInput(
                type = LocationType.ORIGIN,
                latitude = 0.0,
                longitude = 0.0,
                placeName = if (pkg.fromAddress.isNotBlank()) Optional.present(pkg.fromAddress) else Optional.absent()
            )

            val destination = LocationInput(
                type = LocationType.DESTINATION,
                latitude = 0.0,
                longitude = 0.0,
                placeName = if (pkg.toAddress.isNotBlank()) Optional.present(pkg.toAddress) else Optional.absent()
            )

            val weightStr = pkg.weight.trim()
            val weightValue = weightStr.toDoubleOrNull()
                ?: Regex("[\\d.]+").find(weightStr)?.value?.toDoubleOrNull()

            val mediaInputs = if (pkg.mediaUrls.isNotEmpty()) {
                pkg.mediaUrls.map { url ->
                    com.gocavgo.ikuriye.type.MediaInput(
                        url = url,
                        mediaType = if (url.endsWith(".mp4", true)) com.gocavgo.ikuriye.type.MediaType.VIDEO else com.gocavgo.ikuriye.type.MediaType.PICTURE
                    )
                }
            } else emptyList()
            Log.d("PackageMedia", "MediaInput objects being sent: ${mediaInputs.map { "${it.url} (${it.mediaType})" }}")

            val details = DetailInput(
                category = if (pkg.category.isNotBlank()) Optional.present(pkg.category) else Optional.absent(),
                description = if (pkg.description.isNotBlank()) Optional.present(pkg.description) else Optional.absent(),
                fragile = Optional.present(pkg.fragile),
                weight = if (weightValue != null) Optional.present(weightValue) else Optional.absent(),
                media = if (mediaInputs.isNotEmpty()) Optional.present(mediaInputs) else Optional.absent()
            )

            val transferRuleType = pkg.transferRuleType?.let { type ->
                try {
                    com.gocavgo.ikuriye.type.TransferRuleType.valueOf(type)
                } catch (_: IllegalArgumentException) { null }
            }

            val input = CreatePackageInput(
                deliveryType = DeliveryType.OPEN,
                sender = Optional.present(sender),
                receiver = receiver,
                origin = origin,
                destination = destination,
                details = Optional.present(details),
                transferRuleType = if (transferRuleType != null) Optional.present(transferRuleType) else Optional.Absent,
                transferMatchUserId = if (pkg.transferMatchUserId?.isNotBlank() == true) Optional.present(pkg.transferMatchUserId) else Optional.Absent
            )

            val result = PackageRepository.createPackage(input)
            Log.d("PackageMedia", "createPackage result: ${result != null}")
            Log.d("PackageMedia", "result mediaUrls: ${result?.mediaUrls}")
            if (result != null) {
                _state.update {
                    it.copy(
                        clientPackages = listOf(result) + it.clientPackages,
                        isCreatingPackage = false,
                        isSubmittingPackage = false,
                        createPackageForm = CreatePackageFormState(),
                        mediaUploads = emptyList()
                    )
                }
            } else {
                _state.update { it.copy(isSubmittingPackage = false) }
                _toastEvent.emit("Failed to create package. Please try again.")
            }
        }
    }

    fun getSelectedPackage(): ClientPackage? {
        val id = _state.value.clientSelectedPackageId ?: return null
        return _state.value.clientPackages.find { it.id == id }
    }

    fun findPackageByCode(code: String): ClientPackage? {
        return _state.value.clientPackages.find {
            it.id.equals(code.trim(), ignoreCase = true)
        }
    }

    fun fetchClientPackages() {
        viewModelScope.launch {
            _state.update { it.copy(clientCurrentPage = 0) }
            val cached = withContext(Dispatchers.IO) { PackageCache.getCached() }
            val hasNoCache = cached == null
            if (cached != null) {
                _state.update { it.copy(
                    clientPackages = cached.items,
                    clientCurrentPage = 0,
                    clientHasMore = cached.currentPage + 1 < cached.totalPages,
                    clientTotalPages = cached.totalPages,
                    clientTotalCount = cached.totalCount,
                    clientPackagesFetchedOnce = true
                ) }
            }
            if (hasNoCache) {
                _state.update { it.copy(isClientInitialLoading = true) }
            }
            try {
                val result = PackageRepository.fetchMyPackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                PackageCache.save(result)
                _state.update { it.copy(
                    clientPackages = result.items,
                    clientCurrentPage = 0,
                    clientHasMore = result.currentPage + 1 < result.totalPages,
                    clientTotalPages = result.totalPages,
                    clientTotalCount = result.totalCount,
                    clientPackagesFetchedOnce = true
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "fetchClientPackages: ${e.message}", e)
            } finally {
                _state.update { it.copy(isClientInitialLoading = false) }
            }
        }
    }

    fun refreshClientPackages() {
        _state.update { it.copy(isRefreshingPackages = true) }
        viewModelScope.launch {
            try {
                val result = PackageRepository.fetchMyPackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                withContext(Dispatchers.IO) { PackageCache.save(result) }
                _state.update { it.copy(
                    clientPackages = result.items,
                    clientCurrentPage = 0,
                    clientHasMore = result.currentPage + 1 < result.totalPages,
                    clientTotalPages = result.totalPages,
                    clientTotalCount = result.totalCount
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "refreshClientPackages: ${e.message}", e)
            } finally {
                _state.update { it.copy(isRefreshingPackages = false) }
            }
        }
    }

    fun loadMoreClientPackages() {
        val s = _state.value
        if (s.isLoadingMorePackages || !s.clientHasMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMorePackages = true) }
            try {
                val nextPage = s.clientCurrentPage + 1
                val result = PackageRepository.fetchMyPackages(page = nextPage, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                _state.update { it.copy(
                    clientPackages = s.clientPackages + result.items,
                    clientCurrentPage = nextPage,
                    clientHasMore = result.currentPage + 1 < result.totalPages,
                    clientTotalPages = result.totalPages,
                    clientTotalCount = result.totalCount
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "loadMoreClientPackages: ${e.message}", e)
            } finally {
                _state.update { it.copy(isLoadingMorePackages = false) }
            }
        }
    }

    // ── New Package Subscription (driver-side real-time) ───────────────────

    /**
     * Start listening for real-time new-package-transfer events.
     * Called after driver login and on app resume.
     */
    fun startPackageSubscription() {
        stopPackageSubscription() // avoid duplicate subscriptions
        subscriptionJob = viewModelScope.launch {
            // Collect known IDs from current offers to avoid duplicates on first poll
            val initialIds = _state.value.driverAvailableOffers.map { it.id }.toSet()
            PackageRepository.pollNewPackages(initialIds).collect { pkg ->
                // Guard: skip if this package has already been accepted and moved
                // to current packages (prevents race with acceptAUTOTransfer/etc).
                val alreadyAccepted = _state.value.driverCurrentPackages.any { it.id == pkg.id }
                if (alreadyAccepted) return@collect

                val currentState = _state.value
                val isOnOffersTab = currentState.driverHomeTab == 1 && currentState.driverPackageSubTab == 1
                if (isOnOffersTab) {
                    // Directly add to top of offers list with growing border
                    val pkgId = pkg.id
                    _state.update {
                        it.copy(
                            driverAvailableOffers = listOf(pkg) + it.driverAvailableOffers,
                            newPackageFromSubscription = pkg
                        )
                    }
                    // Auto-clear the "new" flag after 8 seconds so the border animation stops.
                    // Only clear if the flag still points to THIS package, not a newer one
                    // that arrived in the meantime.
                    kotlinx.coroutines.delay(8_000)
                    _state.update { s ->
                        if (s.newPackageFromSubscription?.id == pkgId) {
                            s.copy(newPackageFromSubscription = null)
                        } else s
                    }
                } else {
                    // Show floating toast — user can tap to switch to offers tab
                    _state.update {
                        it.copy(
                            newPackageFromSubscription = pkg,
                            showNewPackageToast = true
                        )
                    }
                }
            }
        }
    }

    fun stopPackageSubscription() {
        subscriptionJob?.cancel()
        subscriptionJob = null
    }

    /**
     * Called when the user taps the floating new-package toast.
     * Navigates to the Available Offers tab and dismisses the toast.
     */
    fun onNewPackageToastClicked() {
        val pkg = _state.value.newPackageFromSubscription
        _state.update {
            it.copy(
                showNewPackageToast = false,
                newPackageFromSubscription = null,
                driverHomeTab = 1,
                driverPackageSubTab = 1,
                driverAvailableOffers = if (pkg != null) listOf(pkg) + it.driverAvailableOffers else it.driverAvailableOffers
            )
        }
    }

    fun dismissNewPackageToast() {
        _state.update { it.copy(showNewPackageToast = false, newPackageFromSubscription = null) }
    }

    fun clearNewPackageFlag() {
        _state.update { it.copy(newPackageFromSubscription = null) }
    }

    /**
     * Directly accept an AUTO transfer — no code, no confirmation dialog.
     * Calls the server and moves the package from offers to active packages.
     */
    fun acceptAUTOTransfer(packageId: String, transferId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isAcceptingTransfer = true) }
            val success = PackageRepository.acceptPackageByTransfer(transferId)
            if (success) {
                _state.update { s2 ->
                    val offer = s2.driverAvailableOffers.find { it.id == packageId } ?: return@update s2
                    val accepted = offer.copy(
                        status = PackageStatus.PICKED_UP,
                        driverName = s2.driverProfile.name,
                        driverPhone = s2.driverProfile.phone,
                        driverCompany = "QuickCargo Ltd",
                        vehicleType = "car",
                        deliveryCode = packageId.replace("PKG", "DLV"),
                        statusHistory = listOf(StatusUpdate(PackageStatus.PICKED_UP, "Just now", offer.fromAddress, "Offer accepted"))
                    )
                    s2.copy(
                        driverCurrentPackages = listOf(accepted) + s2.driverCurrentPackages,
                        driverAvailableOffers = s2.driverAvailableOffers.filter { it.id != packageId },
                        isAcceptingTransfer = false
                    )
                }
                _toastEvent.emit("Offer accepted")
            } else {
                _state.update { it.copy(isAcceptingTransfer = false) }
                _toastEvent.emit("Failed to accept offer")
            }
        }
    }

    private fun preloadDriverPackages() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { PackageCache.getCached() }
            if (cached != null) {
                _state.update { it.copy(
                    driverCurrentPackages = cached.items,
                    driverCurrentPage = 0,
                    driverCurrentHasMore = cached.currentPage + 1 < cached.totalPages,
                    driverCurrentTotalPages = cached.totalPages,
                    driverCurrentTotalCount = cached.totalCount
                ) }
            }
        }
    }

    fun loadDriverPackages() {
        viewModelScope.launch {
            _state.update { it.copy(
                driverCurrentPage = 0,
                driverOffersPage = 0
            ) }
            val cached = withContext(Dispatchers.IO) { PackageCache.getCached() }
            val hasNoCache = cached == null
            if (cached != null) {
                _state.update { it.copy(
                    driverCurrentPackages = cached.items,
                    driverCurrentPage = 0,
                    driverCurrentHasMore = cached.currentPage + 1 < cached.totalPages,
                    driverCurrentTotalPages = cached.totalPages,
                    driverCurrentTotalCount = cached.totalCount
                ) }
            }
            if (hasNoCache) {
                _state.update { it.copy(isDriverInitialLoading = true) }
            }
            try {
                val current = PackageRepository.fetchMyPackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                withContext(Dispatchers.IO) { PackageCache.save(current) }
                val offers = PackageRepository.fetchAvailablePackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                _state.update { it.copy(
                    driverCurrentPackages = current.items,
                    driverCurrentPage = 0,
                    driverCurrentHasMore = current.currentPage + 1 < current.totalPages,
                    driverCurrentTotalPages = current.totalPages,
                    driverCurrentTotalCount = current.totalCount,
                    driverAvailableOffers = offers.items,
                    driverOffersPage = 0,
                    driverOffersHasMore = offers.currentPage + 1 < offers.totalPages,
                    driverOffersTotalPages = offers.totalPages,
                    driverOffersTotalCount = offers.totalCount
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "loadDriverPackages: ${e.message}", e)
            } finally {
                _state.update { it.copy(isDriverInitialLoading = false) }
            }
        }
    }

    fun refreshDriverPackages() {
        _state.update { it.copy(isRefreshingPackages = true) }
        viewModelScope.launch {
            try {
                val current = PackageRepository.fetchMyPackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                withContext(Dispatchers.IO) { PackageCache.save(current) }
                val offers = PackageRepository.fetchAvailablePackages(page = 0, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                _state.update { it.copy(
                    driverCurrentPackages = current.items,
                    driverCurrentPage = 0,
                    driverCurrentHasMore = current.currentPage + 1 < current.totalPages,
                    driverCurrentTotalPages = current.totalPages,
                    driverCurrentTotalCount = current.totalCount,
                    driverAvailableOffers = offers.items,
                    driverOffersPage = 0,
                    driverOffersHasMore = offers.currentPage + 1 < offers.totalPages,
                    driverOffersTotalPages = offers.totalPages,
                    driverOffersTotalCount = offers.totalCount
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "refreshDriverPackages: ${e.message}", e)
            } finally {
                _state.update { it.copy(isRefreshingPackages = false) }
            }
        }
    }

    fun loadMoreDriverCurrent() {
        val s = _state.value
        if (s.isLoadingMorePackages || !s.driverCurrentHasMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMorePackages = true) }
            try {
                val nextPage = s.driverCurrentPage + 1
                val result = PackageRepository.fetchMyPackages(page = nextPage, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                _state.update { it.copy(
                    driverCurrentPackages = s.driverCurrentPackages + result.items,
                    driverCurrentPage = nextPage,
                    driverCurrentHasMore = result.currentPage + 1 < result.totalPages,
                    driverCurrentTotalPages = result.totalPages,
                    driverCurrentTotalCount = result.totalCount,
                    isLoadingMorePackages = false
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "loadMoreDriverCurrent: ${e.message}", e)
                _state.update { it.copy(isLoadingMorePackages = false) }
            }
        }
    }

    fun loadMoreDriverOffers() {
        val s = _state.value
        if (s.isLoadingMorePackages || !s.driverOffersHasMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMorePackages = true) }
            try {
                val nextPage = s.driverOffersPage + 1
                val result = PackageRepository.fetchAvailablePackages(page = nextPage, order = com.gocavgo.ikuriye.type.SortOrder.DESC)
                _state.update { it.copy(
                    driverAvailableOffers = s.driverAvailableOffers + result.items,
                    driverOffersPage = nextPage,
                    driverOffersHasMore = result.currentPage + 1 < result.totalPages,
                    driverOffersTotalPages = result.totalPages,
                    driverOffersTotalCount = result.totalCount,
                    isLoadingMorePackages = false
                ) }
            } catch (e: Exception) {
                Log.e("TripViewModel", "loadMoreDriverOffers: ${e.message}", e)
                _state.update { it.copy(isLoadingMorePackages = false) }
            }
        }
    }

    // ── Transfer Methods ──────────────────────────────────────────────────────

    fun openTransferCreationDialog(packageId: String) {
        _state.update { it.copy(
            showTransferCreationDialog = true,
            transferCreationPackageId = packageId,
            transferMatchUserId = null,
            transferMatchUserName = null
        ) }
    }

    // ── Selection Mode & Batch Transfer Methods ──────────────────────────────

    fun startSelectionMode(packageId: String) {
        _state.update { it.copy(
            isSelectionMode = true,
            selectedPackageIds = setOf(packageId)
        ) }
    }

    fun togglePackageSelection(packageId: String) {
        _state.update { s ->
            val current = s.selectedPackageIds
            val updated = if (packageId in current) current - packageId else current + packageId
            s.copy(selectedPackageIds = updated)
        }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(
            isSelectionMode = false,
            selectedPackageIds = emptySet()
        ) }
    }

    fun openBatchTransferDialog() {
        _state.update { it.copy(showBatchTransferDialog = true) }
    }

    fun closeBatchTransferDialog() {
        _state.update { it.copy(showBatchTransferDialog = false) }
    }

    fun createBatchTransfer() {
        val s = _state.value
        val trackingCodes = s.selectedPackageIds.toList()
        val ruleType = s.selectedTransferRuleType ?: return
        if (trackingCodes.isEmpty()) return

        // Map tracking codes to internal UUIDs for the API
        val packageUuids = (s.driverCurrentPackages + s.driverAvailableOffers)
            .filter { it.id in trackingCodes }
            .map { it.packageUuid }
            .filter { it.isNotBlank() }

        if (packageUuids.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit("No packages found with valid IDs") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isCreatingTransfer = true) }
            val result = PackageRepository.createTransfer(
                packageIds = packageUuids,
                ruleType = ruleType
            )
            if (result != null) {
                _state.update { s2 ->
                    s2.copy(
                        driverCurrentPackages = s2.driverCurrentPackages.map { pkg ->
                            if (pkg.id in trackingCodes) pkg.copy(
                                transferId = result.id,
                                transferStatus = result.status
                            ) else pkg
                        },
                        isCreatingTransfer = false,
                        showBatchTransferDialog = false,
                        selectedTransferRuleType = null,
                        isSelectionMode = false,
                        selectedPackageIds = emptySet()
                    )
                }
                _toastEvent.emit("Transfer created with ${packageUuids.size} package(s)")
            } else {
                _state.update { it.copy(isCreatingTransfer = false) }
                _toastEvent.emit("Failed to create transfer")
            }
        }
    }

    fun closeTransferCreationDialog() {
        _state.update { it.copy(
            showTransferCreationDialog = false,
            transferCreationPackageId = null,
            selectedTransferRuleType = null,
            transferMatchUserId = null,
            transferMatchUserName = null
        ) }
    }

    fun setTransferRuleType(ruleType: String) {
        _state.update { it.copy(selectedTransferRuleType = ruleType) }
    }

    fun setTransferMatchUser(userId: String?, userName: String?) {
        _state.update { it.copy(
            transferMatchUserId = userId,
            transferMatchUserName = userName
        ) }
    }

    fun clearTransferMatchUser() {
        _state.update { it.copy(
            transferMatchUserId = null,
            transferMatchUserName = null
        ) }
    }

    fun createTransfer() {
        val s = _state.value
        val trackingCode = s.transferCreationPackageId ?: return
        val ruleType = s.selectedTransferRuleType ?: return

        // Find the package by tracking code to get its internal UUID
        val pkg = s.clientPackages.find { it.id == trackingCode }
        val packageUuid = pkg?.packageUuid
        if (packageUuid.isNullOrBlank()) {
            viewModelScope.launch { _toastEvent.emit("Package has no valid server ID") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isCreatingTransfer = true) }
            val result = PackageRepository.createTransfer(
                packageIds = listOf(packageUuid),
                ruleType = ruleType,
                matchUserId = s.transferMatchUserId
            )
            if (result != null) {
                // Update the package with transfer info
                _state.update { s2 ->
                    s2.copy(
                        clientPackages = s2.clientPackages.map { pkg ->
                            if (pkg.id == trackingCode) pkg.copy(
                                transferId = result.id,
                                transferStatus = result.status
                            ) else pkg
                        },
                        isCreatingTransfer = false,
                        showTransferCreationDialog = false,
                        transferCreationPackageId = null,
                        selectedTransferRuleType = null,
                        transferMatchUserId = null,
                        transferMatchUserName = null
                    )
                }
                _toastEvent.emit("Transfer created successfully")
            } else {
                _state.update { it.copy(isCreatingTransfer = false) }
                _toastEvent.emit("Failed to create transfer")
            }
        }
    }

    fun openConfirmTransferDialog(packageId: String, transferId: String) {
        _state.update { it.copy(
            showConfirmTransferDialog = true,
            confirmTransferPackageId = packageId,
            confirmTransferId = transferId
        ) }
    }

    fun closeConfirmTransferDialog() {
        _state.update { it.copy(
            showConfirmTransferDialog = false,
            confirmTransferPackageId = null,
            confirmTransferId = null
        ) }
    }

    fun confirmTransferRequest() {
        val transferId = _state.value.confirmTransferId ?: return
        val packageId = _state.value.confirmTransferPackageId ?: return

        viewModelScope.launch {
            _state.update { it.copy(isConfirmingTransfer = true) }
            val result = PackageRepository.confirmTransfer(transferId)
            if (result != null) {
                _state.update { s2 ->
                    s2.copy(
                        clientPackages = s2.clientPackages.map { pkg ->
                            if (pkg.id == packageId) pkg.copy(
                                transferStatus = result.status
                            ) else pkg
                        },
                        isConfirmingTransfer = false,
                        showConfirmTransferDialog = false,
                        confirmTransferPackageId = null,
                        confirmTransferId = null
                    )
                }
                _toastEvent.emit("Transfer confirmed")
            } else {
                _state.update { it.copy(isConfirmingTransfer = false) }
                _toastEvent.emit("Failed to confirm transfer")
            }
        }
    }

    fun openRequestTransferDialog(packageId: String, transferId: String) {
        _state.update { it.copy(
            showRequestTransferDialog = true,
            requestTransferPackageId = packageId,
            requestTransferId = transferId
        ) }
    }

    fun closeRequestTransferDialog() {
        _state.update { it.copy(
            showRequestTransferDialog = false,
            requestTransferPackageId = null,
            requestTransferId = null
        ) }
    }

    fun requestTransferForPackage() {
        val transferId = _state.value.requestTransferId ?: return
        val packageId = _state.value.requestTransferPackageId ?: return

        viewModelScope.launch {
            _state.update { it.copy(isRequestingTransfer = true) }
            val result = PackageRepository.requestTransfer(transferId)
            if (result != null) {
                // Update package status in available offers to show it's pending
                _state.update { s2 ->
                    s2.copy(
                        driverAvailableOffers = s2.driverAvailableOffers.map { pkg ->
                            if (pkg.id == packageId) pkg.copy(
                                transferId = result.id,
                                transferStatus = result.status
                            ) else pkg
                        },
                        showRequestTransferDialog = false,
                        requestTransferPackageId = null,
                        requestTransferId = null,
                        isRequestingTransfer = false
                    )
                }
                _toastEvent.emit("Transfer request sent — awaiting owner confirmation")
            } else {
                _state.update { it.copy(isRequestingTransfer = false) }
                _toastEvent.emit("Failed to request transfer")
            }
        }
    }

    fun openAcceptTransferCodeDialog(packageId: String, transferId: String, ruleType: String) {
        _state.update { it.copy(
            showAcceptTransferCodeDialog = true,
            acceptTransferPackageId = packageId,
            acceptTransferId = transferId,
            acceptTransferRuleType = ruleType,
            acceptTransferCodeInput = "",
            acceptTransferCodeError = ""
        ) }
    }

    fun closeAcceptTransferCodeDialog() {
        _state.update { it.copy(
            showAcceptTransferCodeDialog = false,
            acceptTransferPackageId = null,
            acceptTransferId = null,
            acceptTransferRuleType = null,
            acceptTransferCodeInput = "",
            acceptTransferCodeError = ""
        ) }
    }

    fun updateAcceptTransferCode(code: String) {
        _state.update { it.copy(acceptTransferCodeInput = code, acceptTransferCodeError = "") }
    }

    fun acceptOfferViaTransfer() {
        val s = _state.value
        val transferId = s.acceptTransferId ?: return
        val packageId = s.acceptTransferPackageId ?: return
        val ruleType = s.acceptTransferRuleType ?: return

        viewModelScope.launch {
            _state.update { it.copy(isAcceptingTransfer = true) }

            val code = if (ruleType == "SECURE") {
                val input = s.acceptTransferCodeInput.trim()
                if (input.isBlank()) {
                    _state.update { it.copy(acceptTransferCodeError = "Transfer code is required for secure transfers", isAcceptingTransfer = false) }
                    return@launch
                }
                input
            } else null

            val success = PackageRepository.acceptPackageByTransfer(transferId, code)
            if (success) {
                // Move package from offers to current packages
                _state.update { s2 ->
                    val offer = s2.driverAvailableOffers.find { it.id == packageId } ?: return@update s2
                    val accepted = offer.copy(
                        status = PackageStatus.PICKED_UP,
                        driverName = s2.driverProfile.name,
                        driverPhone = s2.driverProfile.phone,
                        driverCompany = "QuickCargo Ltd",
                        vehicleType = "car",
                        deliveryCode = packageId.replace("PKG", "DLV"),
                        statusHistory = listOf(StatusUpdate(PackageStatus.PICKED_UP, "Just now", offer.fromAddress, "Offer accepted via transfer"))
                    )
                    s2.copy(
                        driverCurrentPackages = listOf(accepted) + s2.driverCurrentPackages,
                        driverAvailableOffers = s2.driverAvailableOffers.filter { it.id != packageId },
                        showAcceptTransferCodeDialog = false,
                        acceptTransferPackageId = null,
                        acceptTransferId = null,
                        acceptTransferRuleType = null,
                        acceptTransferCodeInput = "",
                        acceptTransferCodeError = "",
                        isAcceptingTransfer = false
                    )
                }
                _toastEvent.emit("Package accepted via transfer")
            } else {
                _state.update { it.copy(
                    acceptTransferCodeError = if (ruleType == "SECURE") "Invalid transfer code" else "Failed to accept transfer",
                    isAcceptingTransfer = false
                ) }
            }
        }
    }

    fun publicTrackPackage(code: String) {
        viewModelScope.launch {
            _state.update { it.copy(publicTrackingPackage = null, publicTrackingError = "") }
            val pkg = PackageRepository.trackByCode(code)
            _state.update {
                it.copy(
                    publicTrackingPackage = pkg,
                    publicTrackingError = if (pkg == null) "No package found with code \"$code\"" else ""
                )
            }
        }
    }

    fun closePublicTracking() {
        _state.update { it.copy(publicTrackingPackage = null, publicTrackingError = "") }
    }

    fun searchUsers(query: String, role: com.gocavgo.ikuriye.type.Role? = null) {
        if (query.isBlank()) {
            _state.update { it.copy(userSearchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSearchingUsers = true) }
            try {
                val response = ApolloClientProvider.client.query(
                    SearchUsersQuery(
                        query = query,
                        role = role?.let { Optional.present(it) } ?: Optional.absent()
                    )
                ).execute()
                val results = response.data?.searchUsers?.filterNotNull() ?: emptyList()
                _state.update { it.copy(userSearchResults = results, isSearchingUsers = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isSearchingUsers = false) }
            }
        }
    }

    fun clearUserSearch() {
        _state.update { it.copy(userSearchResults = emptyList()) }
    }

    // ── Pickup Code Methods ────────────────────────────────────────────────

    fun generatePickupCode(packageUuid: String) {
        viewModelScope.launch {
            _state.update { it.copy(isGeneratingPickupCode = true) }
            val result = PackageRepository.generatePickupCode(packageUuid)
            if (result != null && result.pickupCode.isNotBlank()) {
                val expiry = System.currentTimeMillis() + 120_000L // 2 minutes
                _state.update {
                    it.copy(
                        pickupCode = result.pickupCode,
                        pickupCodePackageUuid = packageUuid,
                        pickupCodeExpiryMs = expiry,
                        isGeneratingPickupCode = false
                    )
                }
            } else {
                _state.update { it.copy(isGeneratingPickupCode = false) }
                _toastEvent.emit("Failed to generate pickup code")
            }
        }
    }

    fun dismissPickupCode() {
        _state.update { it.copy(pickupCode = null, pickupCodePackageUuid = null, pickupCodeExpiryMs = 0L) }
    }

    // ── Delivery Confirmation Methods (PENDING_CONFIRMATION flow) ────────────

    /**
     * Sender/receiver confirms delivery using the one-time code received in the
     * PACKAGE_DELIVERY_INITIATED notice. Drives the auto-popup and the manual
     * "Confirm Delivery" button on the tracking screen.
     */
    fun confirmDeliveryFromCode(packageUuid: String, code: String) {
        viewModelScope.launch {
            _state.update { it.copy(isConfirmingDelivery = true) }
            val updated = PackageRepository.confirmDelivery(packageUuid, code.trim())
            _state.update { it.copy(isConfirmingDelivery = false) }
            if (updated != null) {
                _state.update { s ->
                    s.copy(
                        clientPackages = s.clientPackages.map {
                            if (it.packageUuid == packageUuid) it.copy(
                                status = PackageStatus.DELIVERED,
                                receivedAt = "Just now",
                                statusHistory = it.statusHistory + StatusUpdate(
                                    PackageStatus.DELIVERED, "Just now", it.toAddress, "Delivery confirmed"
                                )
                            ) else it
                        },
                        showDeliveryConfirmationDialog = false,
                        deliveryConfirmationPackageUuid = null,
                        deliveryConfirmationCode = "",
                        deliveryConfirmationTrackingCode = ""
                    )
                }
                _toastEvent.emit("Delivery confirmed")
                // Delivery succeeded — mark the related notice(s) as read so the
                // notification is "seen" and stops acting as a pending reminder.
                _state.value.notices
                    .filter { it.eventType == "PACKAGE_DELIVERY_INITIATED" && it.resourceId == packageUuid }
                    .forEach { notice ->
                        autoShownDeliveryNotices.add(notice.viewerNoticeId)
                        NoticeRepository.markRead(notice)
                    }
            } else {
                _toastEvent.emit("Could not confirm delivery. Check the code and try again.")
            }
        }
    }

    /**
     * Dismisses the auto delivery-confirmation popup. The code stays stored in
     * state so the user can still confirm manually from the tracking screen.
     */
    fun dismissDeliveryConfirmationDialog() {
        // Keep the code in state so the user can still confirm later from the
        // tracking screen or by tapping the notification again.
        _state.value.notices
            .filter { it.eventType == "PACKAGE_DELIVERY_INITIATED" && it.resourceId == _state.value.deliveryConfirmationPackageUuid }
            .forEach { autoShownDeliveryNotices.add(it.viewerNoticeId) }
        _state.update { it.copy(showDeliveryConfirmationDialog = false) }
        viewModelScope.launch {
            _toastEvent.emit("Delivery not confirmed yet — tap the notification to confirm it later")
        }
    }

    // ── Create Package Form Methods ─────────────────────────────────────────

    fun resetCreatePackageForm() {
        _state.update { it.copy(
            createPackageForm = CreatePackageFormState(),
            mediaUploads = emptyList(),
            isCreatingPackage = false
        ) }
    }

    fun updateCreatePackageFormField(field: String, value: String) {
        _state.update { it.copy(createPackageForm = it.createPackageForm.updateField(field, value)) }
    }

    fun updateCreatePackageFragile(fragile: Boolean) {
        _state.update { it.copy(createPackageForm = it.createPackageForm.copy(isFragile = fragile)) }
    }

    fun addMediaForUpload(uri: String, byteArray: ByteArray, mimeType: String) {
        val newState = MediaUploadState(
            uri = uri,
            byteArray = byteArray,
            mimeType = mimeType,
            isUploading = true
        )
        
        _state.update { it.copy(mediaUploads = it.mediaUploads + newState) }
        
        val job = viewModelScope.launch {
            val url = SupaMedia.uploadMedia(
                client = SupaClient.instance,
                bucket = BuildConfig.MEDIA_BUCKET,
                byteArray = byteArray,
                mimeType = mimeType,
                onProgress = { progress ->
                    updateMediaProgress(newState.id, progress)
                }
            )
            
            Log.d("PackageMedia", "Upload completed for ${newState.id}: url=$url")
            // Cache the uploaded bytes locally by their URL so we don't need
            // to re-download them when viewing the package.
            if (url != null) {
                val ctx = AuthRepository.getAppContext()
                if (ctx != null) {
                    try {
                        com.gocavgo.ikuriye.cache.MediaCache.getInstance(ctx).cacheBytes(url, byteArray)
                    } catch (e: Exception) {
                        Log.e("TripViewModel", "Failed to cache uploaded media: ${e.message}")
                    }
                }
            }
            _state.update { s ->
                s.copy(
                    mediaUploads = s.mediaUploads.map { m ->
                        if (m.id == newState.id) m.copy(
                            url = url,
                            isUploading = false,
                            progress = if (url != null) 100.0 else m.progress,
                            error = if (url == null) "Upload failed" else null
                        ) else m
                    }
                )
            }
        }
        
        _state.update { s ->
            s.copy(
                mediaUploads = s.mediaUploads.map { m ->
                    if (m.id == newState.id) m.copy(job = job) else m
                }
            )
        }
    }

    private fun updateMediaProgress(id: String, progress: Double) {
        _state.update { s ->
            s.copy(
                mediaUploads = s.mediaUploads.map { m ->
                    if (m.id == id) m.copy(progress = progress) else m
                }
            )
        }
    }

    fun cancelMediaUpload(id: String) {
        val media = _state.value.mediaUploads.find { it.id == id }
        media?.job?.cancel()
        _state.update { it.copy(mediaUploads = it.mediaUploads.filter { m -> m.id != id }) }
    }

    fun removeMedia(id: String) {
        _state.update { it.copy(mediaUploads = it.mediaUploads.filter { m -> m.id != id }) }
    }

    // ── Notices Methods ──────────────────────────────────────────────────

    fun toggleNotices() {
        _state.update { it.copy(showNoticesPanel = !it.showNoticesPanel) }
    }

    fun dismissNotices() {
        _state.update { it.copy(showNoticesPanel = false) }
    }

    /**
     * Opens a notification: marks it as read (optimistic, local) and, when the
     * notice is about a package, fetches that package fresh from the backend
     * (GraphQL [PackageByIdQuery]) and navigates to its details — the driver
     * package detail sheet, or the client tracking screen.
     */
    fun openNoticeFromNotification(notice: Notice) {
        dismissNotices()

        // A delivery-initiated notice carries the one-time code. Tapping it should
        // re-open the delivery-confirmation popup (with the code) instead of just
        // navigating, so the user can confirm or dismiss from there.
        if (notice.eventType == "PACKAGE_DELIVERY_INITIATED" && !notice.payload.isNullOrBlank()) {
            val json = try {
                JSONObject(notice.payload)
            } catch (e: Exception) {
                null
            }
            val code = json?.optString("deliveryCode")?.takeIf { it.isNotBlank() }
            if (code != null && json != null) {
                val packageUuid = notice.resourceId
                val trackingCode = json.optString("trackingCode")
                // Never re-open the popup if the package was already delivered.
                val pkg = findClientPackageForDelivery(packageUuid, trackingCode)
                if (pkg?.status != PackageStatus.DELIVERED) {
                    _state.update {
                        it.copy(
                            showDeliveryConfirmationDialog = true,
                            deliveryConfirmationPackageUuid = packageUuid,
                            deliveryConfirmationCode = code,
                            deliveryConfirmationTrackingCode = trackingCode,
                            deliveryConfirmationRecipientName = pkg?.recipientName ?: ""
                        )
                    }
                    return
                }
            }
        }

        // Mark the notice as read (optimistic local update + Supabase notice_viewer write)
        viewModelScope.launch {
            NoticeRepository.markRead(notice)
        }

        // Only package notices can navigate to a package detail page
        if (!notice.resourceType.equals("PACKAGE", ignoreCase = true) || notice.resourceId.isBlank()) return

        viewModelScope.launch {
            val pkg = PackageRepository.fetchPackageById(notice.resourceId)
            if (pkg == null) {
                _toastEvent.emit("Could not load the package for this notification")
                return@launch
            }
            when (_state.value.appRole) {
                AppRole.DRIVER -> {
                    _state.update { s ->
                        val exists = s.driverCurrentPackages.any { it.packageUuid == pkg.packageUuid || it.id == pkg.id }
                        s.copy(
                            driverCurrentPackages = if (exists) s.driverCurrentPackages else listOf(pkg) + s.driverCurrentPackages
                        )
                    }
                    openPackageDetail(pkg.id)
                }
                AppRole.CLIENT -> {
                    _state.update { s ->
                        val exists = s.clientPackages.any { it.packageUuid == pkg.packageUuid || it.id == pkg.id }
                        s.copy(
                            clientPackages = if (exists) s.clientPackages else listOf(pkg) + s.clientPackages
                        )
                    }
                    trackPackage(pkg.id)
                }
                else -> {}
            }
        }
    }

    /**
     * Start collecting flows from [NoticeRepository] into [TripUiState].
     * Safe to call multiple times — subsequent calls are no-ops because
     * collecting a StateFlow is idempotent.
     */
    private fun collectNotices() {
        viewModelScope.launch {
            NoticeRepository.notices.collect { notices ->
                _state.update { it.copy(notices = notices) }
                maybeShowDeliveryConfirmation(notices)
            }
        }
        viewModelScope.launch {
            NoticeRepository.unreadCount.collect { count ->
                _state.update { it.copy(noticeCount = count) }
            }
        }
    }

    /**
     * Auto-shows the delivery-confirmation popup for the sender/receiver when a
     * PACKAGE_DELIVERY_INITIATED notice arrives carrying payload.deliveryCode.
     * Shown at most once per notice; afterwards the user can confirm manually.
     */
    private fun maybeShowDeliveryConfirmation(notices: List<Notice>) {
        if (_state.value.appRole != AppRole.CLIENT) return
        if (_state.value.showDeliveryConfirmationDialog) return

        val notice = notices
            .filter { it.eventType == "PACKAGE_DELIVERY_INITIATED" }
            .maxByOrNull { it.createdAt } ?: return
        if (notice.viewerNoticeId in autoShownDeliveryNotices) return

        val payload = notice.payload ?: return
        val code = try {
            JSONObject(payload).optString("deliveryCode").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } ?: return

        val packageUuid = notice.resourceId
        val trackingCode = try {
            JSONObject(payload).optString("trackingCode")
        } catch (e: Exception) {
            ""
        }
        val pkg = findClientPackageForDelivery(packageUuid, trackingCode)
        // Never auto-pop if the package has already been delivered (e.g. the user
        // confirmed earlier from the tracking screen and the notice is still unread).
        if (pkg?.status == PackageStatus.DELIVERED) return

        autoShownDeliveryNotices.add(notice.viewerNoticeId)
        _state.update {
            it.copy(
                showDeliveryConfirmationDialog = true,
                deliveryConfirmationPackageUuid = packageUuid,
                deliveryConfirmationCode = code,
                deliveryConfirmationTrackingCode = trackingCode,
                deliveryConfirmationRecipientName = pkg?.recipientName ?: ""
            )
        }
    }

    /**
     * Finds the locally-cached client package matching a delivery notice, matched
     * by package UUID or tracking code (whichever the notice payload carries).
     */
    private fun findClientPackageForDelivery(packageUuid: String, trackingCode: String): ClientPackage? =
        _state.value.clientPackages.find {
            it.packageUuid == packageUuid || (trackingCode.isNotBlank() && it.trackingCode == trackingCode)
        }
}
