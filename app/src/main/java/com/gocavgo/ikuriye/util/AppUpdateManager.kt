package com.gocavgo.ikuriye.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Silent background in-app updater for Ikuriye using GitHub Releases metadata (`update.json`).
 *
 * Flow:
 * 1. App silently checks `https://github.com/Genocadio/ikuriye/releases/latest/download/update.json`.
 * 2. Compares `update.versionCode > installedVersionCode`.
 * 3. Downloads the APK silently in the background off the main thread.
 * 4. Verifies the SHA-256 checksum of the downloaded file.
 * 5. ONLY AFTER the download & SHA-256 verification complete successfully is [updateReadyState]
 *    populated with [UpdateReady]. The UI then displays an install banner/prompt to the user.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val UPDATE_JSON_URL = "https://github.com/Genocadio/ikuriye/releases/latest/download/update.json"

    /**
     * Transient network failures (e.g. TLS `Connection reset`) are expected on
     * flaky connections. We retry a couple of times before giving up for this round.
     */
    private const val MAX_CHECK_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 2_000L

    /**
     * Only one silent update flow at a time. On app startup both the ViewModel
     * init and the post-auth restore can fire [checkForUpdatesAndDownload]
     * concurrently — serialize them so they never double-download.
     */
    private val checkMutex = Mutex()

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val apkFileName: String,
        val sha256: String,
        val downloadUrl: String
    )

    data class UpdateReadyState(
        val updateInfo: UpdateInfo,
        val apkFile: File
    )

    private val _updateReadyState = MutableStateFlow<UpdateReadyState?>(null)
    val updateReadyState: StateFlow<UpdateReadyState?> = _updateReadyState.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Checks for updates and silently downloads/verifies the APK in the background.
     * Safe to call on app startup or periodic background sync.
     */
    suspend fun checkForUpdatesAndDownload(context: Context) = checkMutex.withLock {
        withContext(Dispatchers.IO) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installedVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

            val request = Request.Builder()
                .url(UPDATE_JSON_URL)
                .get()
                .build()

            val response = executeWithRetry(request, "update metadata") ?: return@withContext
            val body = response.body?.string()
            if (body == null) {
                response.close()
                return@withContext
            }
            if (!response.isSuccessful) {
                Log.d(TAG, "checkForUpdates: HTTP ${response.code} — no release update.json found")
                response.close()
                return@withContext
            }

            val json = JSONObject(body)
            val versionName = json.optString("versionName", "")
            val versionCode = json.optLong("versionCode", 0L)
            val apkName = json.optString("apk", "")
            val sha256 = json.optString("sha256", "")

            if (versionCode <= installedVersionCode || apkName.isBlank() || sha256.isBlank()) {
                Log.d(TAG, "App is up to date (installed=$installedVersionCode, latest=$versionCode)")
                return@withContext
            }

            val downloadUrl = "https://github.com/Genocadio/ikuriye/releases/latest/download/$apkName"
            val updateInfo = UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                apkFileName = apkName,
                sha256 = sha256,
                downloadUrl = downloadUrl
            )

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetApk = File(updatesDir, "ikuriye-v${versionCode}.apk")

            // Check if already downloaded and verified
            if (targetApk.exists() && verifySha256(targetApk, sha256)) {
                Log.i(TAG, "Update APK already downloaded & verified: ${targetApk.name}")
                _updateReadyState.value = UpdateReadyState(updateInfo, targetApk)
                return@withContext
            }

            // Silent background download
            Log.i(TAG, "Downloading update v$versionName (code $versionCode) in background...")
            val tempApk = File(updatesDir, "temp_update_${versionCode}.apk")
            if (tempApk.exists()) tempApk.delete()

            val apkRequest = Request.Builder().url(downloadUrl).get().build()
            val apkResponse = httpClient.newCall(apkRequest).execute()
            val responseBody = apkResponse.body ?: return@withContext

            if (!apkResponse.isSuccessful) {
                Log.w(TAG, "Failed to download APK: HTTP ${apkResponse.code}")
                return@withContext
            }

            responseBody.byteStream().use { input ->
                FileOutputStream(tempApk).use { output ->
                    input.copyTo(output, bufferSize = 32768)
                }
            }

            // Verify checksum
            if (!verifySha256(tempApk, sha256)) {
                Log.e(TAG, "APK checksum mismatch! Expected $sha256 — discarding download")
                tempApk.delete()
                return@withContext
            }

            // Move to verified target file
            if (targetApk.exists()) targetApk.delete()
            tempApk.renameTo(targetApk)

            Log.i(TAG, "Update v$versionName downloaded and verified successfully! Ready to install.")
            _updateReadyState.value = UpdateReadyState(updateInfo, targetApk)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient network failures are expected — log once at warn, no stack trace.
            Log.w(TAG, "checkForUpdatesAndDownload skipped this round: ${e.message}")
        }
        }
    }

    private suspend fun executeWithRetry(request: Request, tag: String): Response? {
        var lastError: String? = null
        repeat(MAX_CHECK_ATTEMPTS) { attempt ->
            try {
                val response = httpClient.newCall(request).execute()
                if (attempt > 0) Log.d(TAG, "$tag OK on attempt ${attempt + 1}/$MAX_CHECK_ATTEMPTS")
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.d(TAG, "$tag attempt ${attempt + 1}/$MAX_CHECK_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_CHECK_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "$tag failed after $MAX_CHECK_ATTEMPTS attempts: $lastError")
        return null
    }

    /**
     * Dismisses the update banner/prompt in UI for current session.
     */
    fun dismissUpdatePrompt() {
        _updateReadyState.value = null
    }

    /**
     * Launches the Android PackageInstaller for the downloaded and verified APK.
     */
    fun installUpdate(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch PackageInstaller: ${e.message}", e)
        }
    }

    private fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (!file.exists()) return false
        val actual = computeSha256(file)
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    private fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
