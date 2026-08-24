package com.gocavgo.ikuriye.network

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.nexx.NexxAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads files through the CavGo backend. Returns only what clients need:
 * [UploadResult.mediaId], [UploadResult.url], [UploadResult.mimeType].
 *
 * Clients never see storage paths, buckets, or backend storage details.
 */
object BackendStorage {

    private const val TAG = "BackendStorage"
    private const val MAX_UPLOAD_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1_000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Upload a file. Returns [UploadResult] with mediaId + url + mimeType, or null on failure.
     */
    suspend fun uploadFile(
        byteArray: ByteArray,
        mimeType: String,
        purpose: String = "package-media",
        onProgress: ((Double) -> Unit)? = null
    ): UploadResult? = withContext(Dispatchers.IO) {
        val token = NexxAuth.getAccessToken()
        if (token == null) {
            Log.e(TAG, "No access token available for upload")
            return@withContext null
        }

        val extension = mimeType.substringAfter("/", "jpeg")
        val fileName = "${UUID.randomUUID()}.$extension"

        onProgress?.invoke(0.0)
        onProgress?.invoke(5.0)

        var lastError: Exception? = null
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        fileName,
                        byteArray.toRequestBody(mimeType.toMediaType())
                    )
                    .addFormDataPart("purpose", purpose)
                    .build()

                val request = Request.Builder()
                    .url("${BuildConfig.GRAPHQL_URL.replace("/graphql", "")}/api/files/upload")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                onProgress?.invoke(50.0)

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS failed: HTTP ${response.code}")
                    if (attempt < MAX_UPLOAD_ATTEMPTS) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    }
                    continue
                }

                val json = JSONObject(responseBody)
                if (json.has("error")) {
                    Log.e(TAG, "Upload error: ${json.getString("error")}")
                    lastError = Exception(json.getString("error"))
                    continue
                }

                onProgress?.invoke(95.0)

                val result = UploadResult(
                    mediaId = json.getString("mediaId"),
                    url = json.getString("url"),
                    mimeType = json.getString("mimeType")
                )

                Log.d(TAG, "Upload succeeded: mediaId=${result.mediaId}")
                onProgress?.invoke(100.0)
                return@withContext result

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Upload attempt $attempt/$MAX_UPLOAD_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_UPLOAD_ATTEMPTS) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                }
            }
        }

        Log.e(TAG, "Upload failed after $MAX_UPLOAD_ATTEMPTS attempts: ${lastError?.message}")
        null
    }

    data class UploadResult(
        val mediaId: String,
        val url: String,
        val mimeType: String
    )
}
