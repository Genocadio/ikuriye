package com.gocavgo.ikuriye.supa

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object SupaMedia {
    suspend fun uploadMedia(
        client: SupabaseClient,
        bucket: String,
        byteArray: ByteArray,
        mimeType: String,
        onProgress: ((Double) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val fileName = "${UUID.randomUUID()}.${mimeType.substringAfter("/", "jpeg")}"
        Log.d("PackageMedia", "Starting upload: bucket=$bucket, fileName=$fileName, size=${byteArray.size}, mimeType=$mimeType")
        try {
            onProgress?.invoke(0.0)

            onProgress?.invoke(5.0)

            val storageApi = client.storage
            storageApi.from(bucket).upload(fileName, byteArray) {
                contentType = ContentType.parse(mimeType)
            }

            onProgress?.invoke(95.0)

            val url = storageApi.from(bucket).createSignedUrl(fileName, expiresIn = 30.days)
            Log.d("PackageMedia", "Upload succeeded, signedUrl: $url")
            onProgress?.invoke(100.0)
            url
        } catch (e: Exception) {
            Log.e("PackageMedia", "Upload failed for $fileName: ${e.message}", e)
            null
        }
    }
}
