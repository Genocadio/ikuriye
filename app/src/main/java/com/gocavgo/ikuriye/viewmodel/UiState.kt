package com.gocavgo.ikuriye.viewmodel

import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ClientUser
import com.gocavgo.ikuriye.data.DummyTrip
import com.gocavgo.ikuriye.data.Trip
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.data.dto.AuthUserDto

// ── Domain models ─────────────────────────────────────────────────────────────

data class CreatePackageFormState(
    val senderName: String = "",
    val senderPhone: String = "",
    val fromAddress: String = "",
    val toAddress: String = "",
    val recipientName: String = "",
    val recipientPhone: String = "",
    val description: String = "",
    val weight: String = "",
    val category: String = "",
    val isFragile: Boolean = false
) {
    fun updateField(field: String, value: String): CreatePackageFormState {
        return when (field) {
            "senderName" -> copy(senderName = value)
            "senderPhone" -> copy(senderPhone = value)
            "fromAddress" -> copy(fromAddress = value)
            "toAddress" -> copy(toAddress = value)
            "recipientName" -> copy(recipientName = value)
            "recipientPhone" -> copy(recipientPhone = value)
            "description" -> copy(description = value)
            "weight" -> copy(weight = value)
            "category" -> copy(category = value)
            else -> this
        }
    }
}

data class DriverProfile(
    val name: String = "Jean Bosco",
    val phone: String = "+250 788 123 456",
    val email: String = "driver@gocavgo.com",
    val username: String? = null,
    val avatarUrl: String? = null
)

data class DriverVehicle(
    val plateNumber: String = "RAC 482K",
    val model: String = "Toyota Hiace",
    val seats: Int = 14
)

data class CompletedTrip(
    val origin: String,
    val destination: String,
    val plateNumber: String
)

data class DriverLocation(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 0f,
    val speedKmh: Float = 0f
)

data class MediaUploadState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: String? = null,
    val byteArray: ByteArray? = null,
    val progress: Double = 0.0,
    val mediaId: String? = null,
    val url: String? = null,
    val mimeType: String = "image/jpeg",
    val isUploading: Boolean = false,
    val error: String? = null,
    val job: kotlinx.coroutines.Job? = null
)

// ── Enums ──────────────────────────────────────────────────────────────────────

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

enum class AppRole { NONE, DRIVER, CLIENT }

/**
 * Tracks whether we have definitive knowledge of the user's package data.
 * Used to decide when to auto-open the create-package modal.
 *
 * - UNKNOWN: no data loaded yet (first frame after login/launch)
 * - LOADING: fetching from server, no definitive answer yet
 * - HAS_DATA: server/cache confirmed packages exist
 * - NO_DATA: server/cache confirmed zero packages (safe to open create modal)
 */
enum class DataState { UNKNOWN, LOADING, HAS_DATA, NO_DATA }

// ── UI State ───────────────────────────────────────────────────────────────────

data class TripUiState(
    val isLoggedIn: Boolean = false,
    val isProfileMenuOpen: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val isCompletedTripsOpen: Boolean = false,
    val isVehicleMenuOpen: Boolean = false,
    val driverProfile: DriverProfile = DriverProfile(),
    val vehicle: DriverVehicle = DriverVehicle(),
    val completedTrips: List<CompletedTrip> = emptyList(),
    val driverCompletedTrips: List<CompletedTrip> = listOf(
        CompletedTrip("Musanze Depot", "Kigali Hub", "RAC 482K"),
        CompletedTrip("Nyabugogo", "Huye Terminal", "RAE 119P"),
        CompletedTrip("Rubavu Station", "Kigali Hub", "RAD 774B")
    ),
    val driverHomeTab: Int = 0,
    val defaultPage: String = "trips",
    val keepScreenAwake: Boolean = false,
    val isDriverCreatingPackage: Boolean = false,
    val isDriverProfileMenuOpen: Boolean = false,
    val isDriverSettingsOpen: Boolean = false,
    // Trip state
    val trip: Trip = DummyTrip.trip,
    val currentStopIndex: Int = 0,
    val arrivedAtStop: Boolean = false,
    val tripCompleted: Boolean = false,
    val hasActiveTrip: Boolean = true,
    val driverLocation: DriverLocation = DriverLocation(),
    // Settings
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val isPipEnabled: Boolean = false,
    val password: String = "",
    // Client state
    val appRole: AppRole = AppRole.NONE,
    val isClientLoggedIn: Boolean = false,
    val clientProfile: ClientUser = ClientUser(id = "", name = "", email = "", phone = "", password = ""),
    val clientPackages: List<ClientPackage> = emptyList(),
    val clientSelectedPackageId: String? = null,
    val isCreatingPackage: Boolean = false,
    val isSubmittingPackage: Boolean = false,
    val isTrackingPackage: Boolean = false,
    val isProfileOpen: Boolean = false,
    val isClientProfileMenuOpen: Boolean = false,
    val isClientSettingsOpen: Boolean = false,
    val isRefreshingPackages: Boolean = false,
    val isLoadingMorePackages: Boolean = false,
    val isClientInitialLoading: Boolean = false,
    val clientPackagesFetchedOnce: Boolean = false,
    /**
     * Definitive knowledge of whether packages exist.
     * UNKNOWN = no data yet; LOADING = fetching; HAS_DATA = packages exist; NO_DATA = confirmed empty.
     * Auto-open create modal only when NO_DATA is definitive.
     */
    val clientDataState: DataState = DataState.UNKNOWN,
    val isDriverInitialLoading: Boolean = false,
    // Client pagination
    val clientCurrentPage: Int = 0,
    val clientHasMore: Boolean = true,
    val clientTotalPages: Int = 0,
    val clientTotalCount: Int = 0,
    // Driver current packages pagination
    val driverCurrentPage: Int = 0,
    val driverCurrentHasMore: Boolean = true,
    val driverCurrentTotalPages: Int = 0,
    val driverCurrentTotalCount: Int = 0,
    // Driver available offers pagination
    val driverOffersPage: Int = 0,
    val driverOffersHasMore: Boolean = true,
    val driverOffersTotalPages: Int = 0,
    val driverOffersTotalCount: Int = 0,
    // Public tracking (pre-login)
    val publicTrackingPackage: ClientPackage? = null,
    val publicTrackingError: String = "",
    // Driver packages
    val driverCurrentPackages: List<ClientPackage> = emptyList(),
    val driverAvailableOffers: List<ClientPackage> = emptyList(),
    val driverPackageSubTab: Int = 0,
    val driverPackageSearchQuery: String = "",
    // Driver package dialogs
    val isDeliverDialogOpen: Boolean = false,
    val isTransferDialogOpen: Boolean = false,
    val selectedDriverPackageId: String? = null,
    val isPackageDetailSheetOpen: Boolean = false,
    val deliverCodeInput: String = "",
    val deliverCodeError: String = "",
    // Auth
    val authResult: AuthResult? = null,
    val isAuthLoading: Boolean = false,
    val authUser: AuthUserDto? = null,
    val isAuthInitialized: Boolean = false,
    val signInPrefillEmail: String = "",
    // Profile update
    val isUpdatingProfile: Boolean = false,
    val isUploadingProfileImage: Boolean = false,
    val profileUpdateError: String = "",
    val selectedProfileImage: ByteArray? = null,
    val profileImageMimeType: String = "image/jpeg",
    // OTP
    val showOtpScreen: Boolean = false,
    val otpEmail: String = "",
    // Forgot password (0=email, 1=code+pass, 2=success)
    val showForgotPassword: Boolean = false,
    val forgotPasswordEmail: String = "",
    val forgotPasswordStep: Int = 0,
    // User search
    val userSearchResults: List<SearchUsersQuery.SearchUser> = emptyList(),
    val isSearchingUsers: Boolean = false,
    val createPackageForm: CreatePackageFormState = CreatePackageFormState(),
    val mediaUploads: List<MediaUploadState> = emptyList(),
    // Transfer state
    val isCreatingTransfer: Boolean = false,
    val showTransferCreationDialog: Boolean = false,
    val transferCreationPackageId: String? = null,
    val selectedTransferRuleType: String? = null,
    val transferMatchUserId: String? = null,
    val transferMatchUserName: String? = null,
    val showConfirmTransferDialog: Boolean = false,
    val confirmTransferPackageId: String? = null,
    val confirmTransferId: String? = null,
    val isConfirmingTransfer: Boolean = false,
    // Reject transfer
    val showRejectTransferDialog: Boolean = false,
    val rejectTransferPackageId: String? = null,
    val rejectTransferId: String? = null,
    val isRejectingTransfer: Boolean = false,
    // SECURE transfer code reveal after creation
    val showTransferCodeRevealDialog: Boolean = false,
    val transferCodeRevealValue: String = "",
    // Driver accept via transfer
    val showAcceptTransferCodeDialog: Boolean = false,
    val acceptTransferCodeInput: String = "",
    val acceptTransferCodeError: String = "",
    val acceptTransferPackageId: String? = null,
    val acceptTransferId: String? = null,
    val acceptTransferRuleType: String? = null,
    val isAcceptingTransfer: Boolean = false,
    // Driver request transfer (CONFIRM type)
    val showRequestTransferDialog: Boolean = false,
    val requestTransferPackageId: String? = null,
    val requestTransferId: String? = null,
    val isRequestingTransfer: Boolean = false,
    // Selection mode for batch transfer
    val isSelectionMode: Boolean = false,
    val selectedPackageIds: Set<String> = emptySet(),
    val showBatchTransferDialog: Boolean = false,
    // Pickup code
    val pickupCode: String? = null,
    val pickupCodePackageUuid: String? = null,
    val pickupCodeExpiryMs: Long = 0L,
    val isGeneratingPickupCode: Boolean = false,
    // Delivery confirmation (PENDING_CONFIRMATION flow)
    val isInitiatingDelivery: Boolean = false,
    val showDeliveryConfirmationDialog: Boolean = false,
    val deliveryConfirmationPackageUuid: String? = null,
    val deliveryConfirmationCode: String = "",
    val deliveryConfirmationTrackingCode: String = "",
    val deliveryConfirmationRecipientName: String = "",
    val isConfirmingDelivery: Boolean = false,
    // New package subscription (driver side, real-time)
    val newPackageFromSubscription: ClientPackage? = null,
    val showNewPackageToast: Boolean = false,
    // Notices
    val showNoticesPanel: Boolean = false,
    val noticeCount: Int = 0,
    val notices: List<com.gocavgo.ikuriye.data.Notice> = emptyList()
)
