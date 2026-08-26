package com.gocavgo.ikuriye.data

import android.content.Context
import android.content.SharedPreferences
import com.gocavgo.ikuriye.viewmodel.AppThemeMode
import com.gocavgo.ikuriye.viewmodel.CreatePackageFormState

object SettingsRepository {

    private const val PREFS_NAME = "ikuriye_supa"         // same file as auth
    private const val KEY_DEFAULT_PAGE    = "settings_default_page"
    private const val KEY_KEEP_SCREEN     = "settings_keep_screen_awake"
    private const val KEY_THEME_MODE      = "settings_theme_mode"
    private const val KEY_PIP_ENABLED     = "settings_pip_enabled"
    private const val KEY_RESUME_SCREEN   = "resume_screen_key"
    private const val KEY_RESUME_PKG_ID   = "resume_package_id"
    private const val KEY_AUTO_SHOWN_DELIVERY = "auto_shown_delivery_notices"
    // Create package form draft persistence
    private const val KEY_FORM_FROM_ADDRESS    = "draft_from_address"
    private const val KEY_FORM_TO_ADDRESS      = "draft_to_address"
    private const val KEY_FORM_RECIPIENT_NAME  = "draft_recipient_name"
    private const val KEY_FORM_RECIPIENT_PHONE = "draft_recipient_phone"
    private const val KEY_FORM_DESCRIPTION     = "draft_description"
    private const val KEY_FORM_WEIGHT          = "draft_weight"
    private const val KEY_FORM_CATEGORY        = "draft_category"
    private const val KEY_FORM_IS_FRAGILE      = "draft_is_fragile"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    fun getDefaultPage(): String =
        prefs?.getString(KEY_DEFAULT_PAGE, "trips") ?: "trips"

    fun getKeepScreenAwake(): Boolean =
        prefs?.getBoolean(KEY_KEEP_SCREEN, false) ?: false

    fun getThemeMode(): AppThemeMode {
        val raw = prefs?.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            ?: return AppThemeMode.SYSTEM
        return try { AppThemeMode.valueOf(raw) } catch (_: Exception) { AppThemeMode.SYSTEM }
    }

    fun getPipEnabled(): Boolean =
        prefs?.getBoolean(KEY_PIP_ENABLED, false) ?: false

    // ── Auto-shown delivery-notice popup ids ─────────────────────────────────
    // Persisted so a delivery-confirmation popup that was already shown once
    // (confirmed OR dismissed) does not reappear after the app is killed and
    // reopened — the in-memory set alone would be lost on process death.
    private const val MAX_AUTO_SHOWN_DELIVERY = 50

    // Returns a defensive copy: the SharedPreferences StringSet instance must
    // never be modified by callers.
    fun getAutoShownDeliveryNotices(): Set<String> =
        prefs?.getStringSet(KEY_AUTO_SHOWN_DELIVERY, emptySet())?.toSet() ?: emptySet()

    fun addAutoShownDeliveryNotice(noticeId: String) {
        val p = prefs ?: return
        val current = p.getStringSet(KEY_AUTO_SHOWN_DELIVERY, emptySet()) ?: emptySet()
        // Cap the set so it can't grow unboundedly across a user's lifetime.
        val updated = (current + noticeId).toList().takeLast(MAX_AUTO_SHOWN_DELIVERY).toSet()
        p.edit().putStringSet(KEY_AUTO_SHOWN_DELIVERY, updated).apply()
    }

    // ── Resume State ──────────────────────────────────────────────────────────

    fun getResumeScreenKey(): String? =
        prefs?.getString(KEY_RESUME_SCREEN, null)

    fun getResumePackageId(): String? =
        prefs?.getString(KEY_RESUME_PKG_ID, null)

    // ── Writes ────────────────────────────────────────────────────────────────

    fun saveDefaultPage(page: String) {
        prefs?.edit()?.putString(KEY_DEFAULT_PAGE, page)?.apply()
    }

    fun saveKeepScreenAwake(keep: Boolean) {
        prefs?.edit()?.putBoolean(KEY_KEEP_SCREEN, keep)?.apply()
    }

    fun saveThemeMode(mode: AppThemeMode) {
        prefs?.edit()?.putString(KEY_THEME_MODE, mode.name)?.apply()
    }

    fun savePipEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PIP_ENABLED, enabled)?.apply()
    }

    // ── Save current screen state for resume after process death ──

    fun saveResumeState(screenKey: String?, packageId: String?) {
        val edit = prefs?.edit() ?: return
        if (screenKey != null) edit.putString(KEY_RESUME_SCREEN, screenKey)
        else edit.remove(KEY_RESUME_SCREEN)
        if (packageId != null) edit.putString(KEY_RESUME_PKG_ID, packageId)
        else edit.remove(KEY_RESUME_PKG_ID)
        edit.apply()
    }

    fun clearResumeState() {
        prefs?.edit()
            ?.remove(KEY_RESUME_SCREEN)
            ?.remove(KEY_RESUME_PKG_ID)
            ?.apply()
    }

    // ── Create Package Form Draft ────────────────────────────────────────────

    fun saveCreatePackageDraft(
        fromAddress: String, toAddress: String, recipientName: String,
        recipientPhone: String, description: String, weight: String,
        category: String, isFragile: Boolean
    ) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_FORM_FROM_ADDRESS, fromAddress)
            .putString(KEY_FORM_TO_ADDRESS, toAddress)
            .putString(KEY_FORM_RECIPIENT_NAME, recipientName)
            .putString(KEY_FORM_RECIPIENT_PHONE, recipientPhone)
            .putString(KEY_FORM_DESCRIPTION, description)
            .putString(KEY_FORM_WEIGHT, weight)
            .putString(KEY_FORM_CATEGORY, category)
            .putBoolean(KEY_FORM_IS_FRAGILE, isFragile)
            .apply()
    }

    fun getCreatePackageDraft(): CreatePackageFormState {
        val p = prefs ?: return CreatePackageFormState()
        return CreatePackageFormState(
            fromAddress = p.getString(KEY_FORM_FROM_ADDRESS, "") ?: "",
            toAddress = p.getString(KEY_FORM_TO_ADDRESS, "") ?: "",
            recipientName = p.getString(KEY_FORM_RECIPIENT_NAME, "") ?: "",
            recipientPhone = p.getString(KEY_FORM_RECIPIENT_PHONE, "") ?: "",
            description = p.getString(KEY_FORM_DESCRIPTION, "") ?: "",
            weight = p.getString(KEY_FORM_WEIGHT, "") ?: "",
            category = p.getString(KEY_FORM_CATEGORY, "") ?: "",
            isFragile = p.getBoolean(KEY_FORM_IS_FRAGILE, false)
        )
    }

    fun hasCreatePackageDraft(): Boolean {
        val p = prefs ?: return false
        return p.getString(KEY_FORM_FROM_ADDRESS, "")?.isNotBlank() == true
            || p.getString(KEY_FORM_TO_ADDRESS, "")?.isNotBlank() == true
            || p.getString(KEY_FORM_RECIPIENT_NAME, "")?.isNotBlank() == true
            || p.getString(KEY_FORM_DESCRIPTION, "")?.isNotBlank() == true
    }

    fun clearCreatePackageDraft() {
        val p = prefs ?: return
        p.edit()
            ?.remove(KEY_FORM_FROM_ADDRESS)
            ?.remove(KEY_FORM_TO_ADDRESS)
            ?.remove(KEY_FORM_RECIPIENT_NAME)
            ?.remove(KEY_FORM_RECIPIENT_PHONE)
            ?.remove(KEY_FORM_DESCRIPTION)
            ?.remove(KEY_FORM_WEIGHT)
            ?.remove(KEY_FORM_CATEGORY)
            ?.remove(KEY_FORM_IS_FRAGILE)
            ?.apply()
    }

    // ── Clear (called on logout) ──────────────────────────────────────────────

    fun clear() {
        prefs?.edit()
            ?.remove(KEY_DEFAULT_PAGE)
            ?.remove(KEY_KEEP_SCREEN)
            ?.remove(KEY_THEME_MODE)
            ?.remove(KEY_PIP_ENABLED)
            ?.remove(KEY_RESUME_SCREEN)
            ?.remove(KEY_RESUME_PKG_ID)
            ?.remove(KEY_AUTO_SHOWN_DELIVERY)
            ?.remove("resume_tracking_code")
            ?.apply()
    }
}
