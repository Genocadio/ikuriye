package com.gocavgo.ikuriye.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

class MediaCache private constructor(appContext: Context) {

    companion object {
        private const val CACHE_DIR = "media_cache"
        private const val INDEX_FILE = "index.json"
        private const val EXPIRY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RETRIES = 2

        @Volatile
        private var instance: MediaCache? = null

        fun getInstance(context: Context): MediaCache {
            return instance ?: synchronized(this) {
                instance ?: MediaCache(context.applicationContext).also { instance = it }
            }
        }
    }

    data class CacheEntry(
        val url: String,
        val localPath: String,
        val cachedAt: Long
    )

    private val cacheDir = File(appContext.cacheDir, CACHE_DIR)
    private val indexFile = File(cacheDir, INDEX_FILE)
    private val index = mutableMapOf<String, CacheEntry>()

    // Tracks URLs currently being downloaded to prevent concurrent duplicate downloads
    // of the same URL (which would corrupt the file on disk).
    private val downloading = mutableSetOf<String>()

    init {
        cacheDir.mkdirs()
        loadIndex()
        evictExpired()
    }

    fun getCachedFile(url: String): File? {
        synchronized(this) {
            val entry = index[url]
            if (entry == null) {
                Log.d("MediaCache", "MISS  $url")
                return null
            }
            val file = File(entry.localPath)
            if (!file.exists()) {
                index.remove(url)
                saveIndex()
                Log.d("MediaCache", "STALE $url — file deleted from disk, removing index")
                return null
            }
            Log.d("MediaCache", "HIT   $url → ${file.name}")
            return file
        }
    }

    suspend fun cacheMedia(url: String): File = withContext(Dispatchers.IO) {
        // Fast check: already cached? (synchronized for thread safety)
        synchronized(this@MediaCache) {
            val existing = index[url]
            if (existing != null) {
                val f = File(existing.localPath)
                if (f.exists()) {
                    Log.d("MediaCache", "REUSE $url → ${f.name}")
                    return@withContext f
                } else index.remove(url)
            }
        }

        // Try to claim this URL for downloading. `add()` returns `true` if the
        // URL wasn't already in the set (we're the first), `false` if another
        // coroutine already claimed it (we should wait).
        // We save the result here (outside any synchronized) because `add()`
        // is atomic — it tells us definitively who is first.
        val isFirst = synchronized(this@MediaCache) { downloading.add(url) }
        
        if (!isFirst) {
            // Another coroutine is already downloading this URL.
            // Poll the index outside the lock — can't call delay() inside synchronized.
            var waited = 0
            while (waited < 20) {
                kotlinx.coroutines.delay(500)
                val entry = synchronized(this@MediaCache) { index[url] }
                if (entry != null) {
                    val f = File(entry.localPath)
                    if (f.exists()) return@withContext f
                }
                waited++
            }
            throw Exception("Download already in progress for $url (timed out waiting)")
        }

        try {
            val ext = getExtension(url)
            val hash = md5(url)
            val file = File(cacheDir, "$hash$ext")

            val shortUrl = if (url.length > 80) url.take(60) + "…" else url
            Log.d("MediaCache", "DL    $shortUrl → ${file.name}")

            var lastError: Exception? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.getInputStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val size = file.length()
                    Log.d("MediaCache", "DONE  $shortUrl → ${file.name} (${size} bytes, attempt $attempt)")
                    synchronized(this@MediaCache) {
                        index[url] = CacheEntry(url, file.absolutePath, System.currentTimeMillis())
                        saveIndex()
                    }
                    return@withContext file
                } catch (e: Exception) {
                    lastError = e
                    Log.d("MediaCache", "FAIL  $shortUrl (attempt $attempt): ${e.message}")
                    if (attempt < MAX_RETRIES) kotlinx.coroutines.delay(1000L)
                }
            }
            Log.d("MediaCache", "GIVE_UP $shortUrl")
            throw lastError ?: Exception("Failed to cache $url")
        } finally {
            synchronized(this@MediaCache) {
                downloading.remove(url)
            }
        }
    }

    /**
     * Cache media bytes that were JUST uploaded, without re-downloading.
     * Writes the byte array to a local file and indexes it by the URL,
     * so [getCachedFile] / [cacheMedia] will find it immediately on next view.
     */
    suspend fun cacheBytes(url: String, byteArray: ByteArray) {
        val ext = getExtension(url)
        val hash = md5(url)
        val file = File(cacheDir, "$hash$ext")
        withContext(Dispatchers.IO) {
            file.outputStream().use { it.write(byteArray) }
        }
        synchronized(this) {
            index[url] = CacheEntry(url, file.absolutePath, System.currentTimeMillis())
            saveIndex()
        }
        Log.d("MediaCache", "CACHE_BYTES $url → ${file.name} (${byteArray.size} bytes)")
    }

    fun enqueuePreload(urls: List<String>, scope: CoroutineScope) {
        // Only enqueue URLs that aren't cached AND aren't already being downloaded
        val toDownload = synchronized(this) {
            urls.filter { url ->
                val cached = getCachedFile(url)
                cached == null && !downloading.contains(url)
            }
        }
        Log.d("MediaCache", "PRELOAD ${toDownload.size}/${urls.size} uncached URLs")
        toDownload.forEach { url ->
            scope.launch {
                try { cacheMedia(url) } catch (_: Exception) {}
            }
        }
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            val json = indexFile.readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val url = obj.getString("url")
                val localPath = obj.getString("localPath")
                val cachedAt = obj.getLong("cachedAt")
                index[url] = CacheEntry(url, localPath, cachedAt)
            }
        } catch (_: Exception) {}
    }

    private fun saveIndex() {
        try {
            val arr = JSONArray()
            index.values.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("url", entry.url)
                    put("localPath", entry.localPath)
                    put("cachedAt", entry.cachedAt)
                })
            }
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(arr.toString(2))
        } catch (_: Exception) {}
    }

    fun evictExpired() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val toRemove = mutableListOf<String>()
            index.forEach { (url, entry) ->
                if (now - entry.cachedAt > EXPIRY_MS) {
                    File(entry.localPath).delete()
                    toRemove.add(url)
                    Log.d("MediaCache", "EVICT ${entry.localPath} (age ${(now - entry.cachedAt) / 1000 / 60}m)")
                }
            }
            toRemove.forEach { index.remove(it) }
            if (toRemove.isNotEmpty()) {
                saveIndex()
                Log.d("MediaCache", "EVICT done: ${toRemove.size} entries removed")
            }
        }
    }

    private fun getExtension(url: String): String = when {
        url.endsWith(".mp4", true) -> ".mp4"
        url.endsWith(".mov", true) -> ".mov"
        url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) -> ".jpg"
        url.endsWith(".png", true) -> ".png"
        url.endsWith(".gif", true) -> ".gif"
        url.endsWith(".webp", true) -> ".webp"
        else -> ".bin"
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
