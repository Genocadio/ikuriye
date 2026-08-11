package com.gocavgo.ikuriye.supa

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

object SupaMedia {

    private const val TAG = "SupaMedia"
    private const val MAX_UPLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_000L

    /**
     * Uploads media to Supabase Storage and returns a signed URL (30-day expiry).
     * Retries transient network failures up to [MAX_UPLOAD_ATTEMPTS] times.
     * Returns null on persistent failure — callers should surface "Upload failed".
     */
    suspend fun uploadMedia(
        client: SupabaseClient,
        bucket: String,
        byteArray: ByteArray,
        mimeType: String,
        onProgress: ((Double) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.${mimeType.substringAfter("/", "jpeg")}"
        Log.d(TAG, "Starting upload: bucket=$bucket, fileName=$fileName, size=${byteArray.size}, mimeType=$mimeType")
        onProgress?.invoke(0.0)
        onProgress?.invoke(5.0)

        val storageApi = client.storage
        var lastError: Exception? = null
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            try {
                storageApi.from(bucket).upload(fileName, byteArray) {
                    contentType = ContentType.parse(mimeType)
                }
                lastError = null
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // coroutine cancelled — never swallow, never retry
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS failed for $fileName: ${e.message}")
                if (attempt < MAX_UPLOAD_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
        }

        if (lastError != null) {
            Log.e(TAG, "Upload failed for $fileName after $MAX_UPLOAD_ATTEMPTS attempts: ${lastError.message}", lastError)
            return@withContext null
        }

        try {
            onProgress?.invoke(95.0)
            val url = storageApi.from(bucket).createSignedUrl(fileName, expiresIn = 30.days)
            Log.d(TAG, "Upload succeeded, signedUrl: $url")
            onProgress?.invoke(100.0)
            url
        } catch (e: Exception) {
            Log.e(TAG, "createSignedUrl failed for $fileName: ${e.message}", e)
            null
        }
    }
}
