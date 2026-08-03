package com.gocavgo.ikuriye.data

import android.content.Context
import android.content.SharedPreferences
import com.gocavgo.ikuriye.viewmodel.AppThemeMode

object SettingsRepository {

    private const val PREFS_NAME = "ikuriye_supa"         // same file as auth
    private const val KEY_DEFAULT_PAGE    = "settings_default_page"
    private const val KEY_KEEP_SCREEN     = "settings_keep_screen_awake"
    private const val KEY_THEME_MODE      = "settings_theme_mode"
    private const val KEY_PIP_ENABLED     = "settings_pip_enabled"
    private const val KEY_RESUME_SCREEN   = "resume_screen_key"
    private const val KEY_RESUME_PKG_ID   = "resume_package_id"

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

    // ── Clear (called on logout) ──────────────────────────────────────────────

    fun clear() {
        prefs?.edit()
            ?.remove(KEY_DEFAULT_PAGE)
            ?.remove(KEY_KEEP_SCREEN)
            ?.remove(KEY_THEME_MODE)
            ?.remove(KEY_PIP_ENABLED)
            ?.remove(KEY_RESUME_SCREEN)
            ?.remove(KEY_RESUME_PKG_ID)
            ?.remove("resume_tracking_code")
            ?.apply()
    }
}
