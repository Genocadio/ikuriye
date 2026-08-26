@file:JvmName("ClientScreensKt")
package com.gocavgo.ikuriye.ui

// ── ClientScreens.kt ─────────────────────────────────────────────────────────────────────────────────
// Backward-compatibility re-exports. All implementations have been moved to
// focused sub-packages under ui/client/ and ui/common/.
// ─────────────────────────────────────────────────────────────────────────────────

// Re-export public composables so existing imports from "com.gocavgo.ikuriye.ui"
// continue to resolve without any call-site changes.

// Nothing to declare here — Kotlin does not support top-level re-exports with
// 'typealias' for @Composable functions. Instead, all callers in MainActivity
// already use the correct package (they import the specific class name, and
// since Kotlin resolves imports by name, moving the definition breaks callers).
//
// Therefore we keep thin FORWARDING composables below that delegate to the real
// implementations. This keeps the old import paths working.

import androidx.compose.runtime.Composable
import com.gocavgo.ikuriye.data.ClientPackage
import com.gocavgo.ikuriye.data.ClientUser
import com.gocavgo.ikuriye.data.dto.AuthResult
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.CompletedTrip
import com.gocavgo.ikuriye.viewmodel.DriverProfile
import com.gocavgo.ikuriye.viewmodel.CreatePackageFormState
import com.gocavgo.ikuriye.viewmodel.MediaUploadState
import com.gocavgo.ikuriye.SearchUsersQuery
import com.gocavgo.ikuriye.ui.common.SettingsMenu as SettingsMenuImpl
import com.gocavgo.ikuriye.ui.common.ProfileQuickMenu as ProfileQuickMenuImpl
import com.gocavgo.ikuriye.ui.common.ThemeChoice as ThemeChoiceImpl
import com.gocavgo.ikuriye.ui.common.DriverPageOption as DriverPageOptionImpl
import com.gocavgo.ikuriye.ui.common.MediaCarousel as MediaCarouselImpl
import com.gocavgo.ikuriye.ui.common.FullScreenMediaViewer as FullScreenMediaViewerImpl
import com.gocavgo.ikuriye.ui.common.mediaCacheState as mediaCacheStateImpl
import com.gocavgo.ikuriye.ui.common.MediaCacheState
import com.gocavgo.ikuriye.ui.common.MediaSource
import com.gocavgo.ikuriye.ui.common.formatTime
import com.gocavgo.ikuriye.ui.client.ClientHomeScreen as ClientHomeScreenImpl
import com.gocavgo.ikuriye.ui.client.CreatePackageScreen as CreatePackageScreenImpl
import com.gocavgo.ikuriye.ui.client.TrackPackageScreen as TrackPackageScreenImpl
import com.gocavgo.ikuriye.ui.client.RoleSelectionScreen as RoleSelectionScreenImpl
import com.gocavgo.ikuriye.ui.client.ClientLoginScreen as ClientLoginScreenImpl
import com.gocavgo.ikuriye.ui.client.OtpVerificationScreen as OtpVerificationScreenImpl
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// ── Forwarding composables ────────────────────────────────────────────────────

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
) = RoleSelectionScreenImpl(onTrackByCode, trackError, authResult, isAuthLoading, onSignUp, onSignIn, onClearAuthResult, showOtpScreen, otpEmail, onVerifyOtp, onResendOtp, onDismissOtp, showForgotPassword, forgotPasswordStep, onForgotPassword, onResetPassword, onShowForgotPassword, onHideForgotPassword, signInPrefillEmail, onClearSignInPrefill)

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
) = ClientLoginScreenImpl(onLogin, onBack, authResult, isAuthLoading, onClearAuthResult, onForgotPassword, onResetPassword, showForgotPassword, forgotPasswordStep, onShowForgotPassword, onHideForgotPassword, signInPrefillEmail, onClearSignInPrefill)

@Composable
fun OtpVerificationScreen(
    email: String,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    isAuthLoading: Boolean = false,
    authResult: AuthResult? = null
) = OtpVerificationScreenImpl(email, onVerify, onResend, onBack, isAuthLoading, authResult)

@Composable
fun ClientHomeScreen(
    client: ClientUser,
    packages: List<ClientPackage>,
    themeMode: AppThemeMode,
    isClientProfileMenuOpen: Boolean,
    isClientSettingsOpen: Boolean,
    onCreatePackage: () -> Unit,
    onTrackPackage: (String) -> Unit,
    onLogout: () -> Unit,
    onProfileMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfileClick: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    isRefreshing: Boolean = false,
    isInitialLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onCloseSettings: () -> Unit,
    onDismissMenus: () -> Unit,
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String, String) -> Unit = { _, _ -> },
    onGeneratePickupCode: (String) -> Unit = {},
    hasUnsavedDraft: Boolean = false,
    packagesFetchedOnce: Boolean = false,
    clientDataState: com.gocavgo.ikuriye.viewmodel.DataState = com.gocavgo.ikuriye.viewmodel.DataState.UNKNOWN,
    onNoticesClick: () -> Unit = {},
    noticeCount: Int = 0
) = ClientHomeScreenImpl(client, packages, themeMode, isClientProfileMenuOpen, isClientSettingsOpen, onCreatePackage, onTrackPackage, onLogout, onProfileMenuClick, onSettingsClick, onProfileClick, onThemeModeChange, isRefreshing, isInitialLoading, onRefresh, isLoadingMore, onLoadMore, onCloseSettings, onDismissMenus, onCreateTransfer, onConfirmTransfer, onGeneratePickupCode, hasUnsavedDraft, packagesFetchedOnce, clientDataState, onNoticesClick = onNoticesClick, noticeCount = noticeCount)

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
) = CreatePackageScreenImpl(onBack, onSubmit, formState, onFormFieldChange, onFragileChange, showSenderFields, isSubmitting, userSearchResults, onUserSearch, onClearUserSearch, mediaUploads, onAddMedia, onCancelUpload, onRemoveMedia)

@Composable
fun TrackPackageScreen(
    pkg: ClientPackage,
    onBack: () -> Unit,
    currentUserId: String = "",
    onCreateTransfer: (String) -> Unit = {},
    onConfirmTransfer: (String, String) -> Unit = { _, _ -> },
    onGeneratePickupCode: (String) -> Unit = {},
    onConfirmDelivery: (String, String) -> Unit = { _, _ -> },
    pendingDeliveryCode: String = ""
) = TrackPackageScreenImpl(pkg, onBack, currentUserId, onCreateTransfer, onConfirmTransfer, onGeneratePickupCode, onConfirmDelivery, pendingDeliveryCode)

@Composable
fun ProfileQuickMenu(
    profile: DriverProfile,
    onSettingsClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColorOverride: Color? = null
) = ProfileQuickMenuImpl(profile, onSettingsClick, onLogoutClick, onProfileClick, modifier, accentColorOverride)

@Composable
fun SettingsMenu(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onClose: () -> Unit,
    isPipEnabled: Boolean? = null,
    onPipEnabledChange: ((Boolean) -> Unit)? = null,
    defaultPage: String? = null,
    onDefaultPageChange: ((String) -> Unit)? = null,
    keepScreenAwake: Boolean? = null,
    onKeepScreenAwakeChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) = SettingsMenuImpl(themeMode, onThemeModeChange, onClose, isPipEnabled, onPipEnabledChange, defaultPage, onDefaultPageChange, keepScreenAwake, onKeepScreenAwakeChange, modifier)

@Composable
fun ThemeChoice(
    label: String,
    mode: AppThemeMode,
    current: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) = ThemeChoiceImpl(label, mode, current, onSelect, modifier)

@Composable
fun DriverPageOption(
    label: String,
    value: String,
    current: String?,
    onSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) = DriverPageOptionImpl(label, value, current, onSelect, modifier)

@Composable
fun MediaCarousel(
    mediaUrls: List<String>,
    modifier: Modifier = Modifier,
    onMaximize: (() -> Unit)? = null
) = MediaCarouselImpl(mediaUrls, modifier, onMaximize)

@Composable
fun FullScreenMediaViewer(
    mediaUrls: List<String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showClose: Boolean = true
) = FullScreenMediaViewerImpl(mediaUrls, onClose, modifier, showClose)

@Composable
fun mediaCacheState(url: String): MediaCacheState = mediaCacheStateImpl(url)
