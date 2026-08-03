package com.gocavgo.ikuriye.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Stores the logged-in user's profile picture to a dedicated local file so it
 * never needs to be re-downloaded unless the user logs out, logs in again, or
 * explicitly updates their avatar in the profile section.
 *
 * Only one user is logged in at a time on a device, so we use a fixed filename.
 */
object AvatarCache {

    private const val TAG = "AvatarCache"
    private const val AVATAR_FILE = "profile_image"

    private fun getFile(context: Context): File {
        val dir = File(context.cacheDir, "avatars")
        dir.mkdirs()
        return File(dir, AVATAR_FILE)
    }

    /**
     * Download the avatar from [imageUrl] and save it to the local cache file.
     * Safe to call from any coroutine — runs on [Dispatchers.IO].
     * Silently ignores failures (the app will fall back to the remote URL).
     */
    suspend fun cache(context: Context, imageUrl: String?) {
        if (imageUrl.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val file = getFile(context)
                file.delete() // Remove any stale cached version first
                val url = URL(imageUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.getInputStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Avatar cached (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache avatar: ${e.message}")
            }
        }
    }

    /**
     * Returns a [File] pointing to the cached avatar if it exists on disk,
     * or null if no avatar has been cached yet.
     */
    fun getLocalFile(context: Context): File? {
        val file = getFile(context)
        return if (file.exists()) file else null
    }

    /**
     * Returns a [Uri] for the cached avatar file, or null if none is cached.
     */
    fun getLocalUri(context: Context): Uri? {
        return getLocalFile(context)?.let { Uri.fromFile(it) }
    }

    /** Delete the cached avatar file — call on logout or avatar change. */
    fun clear(context: Context) {
        getFile(context).delete()
        Log.d(TAG, "Avatar cache cleared")
    }
}
