package com.gocavgo.ikuriye.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        // Bounded cache: prevents unbounded index growth on media-heavy packages.
        private const val MAX_ENTRIES = 200
        // Cap parallel downloads so a 20-photo package doesn't thrash the network.
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        // Debounce for async LRU-touch persistence: waits this long after the last
        // touch before writing the index, coalescing scroll bursts into one write.
        private const val PERSIST_DEBOUNCE_MS = 1_000L

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

    // Global cap on simultaneous downloads (separate from per-URL dedup).
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    // App-lifetime scope for background index persistence (LRU touches).
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pending debounced persist job — reused across rapid touches.
    private var pendingPersist: Job? = null

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
            // True LRU: touch last-access time so capacity eviction and expiry
            // evict the least-recently-USED entries, not the oldest-inserted.
            touch(url, entry)
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
                    touch(url, existing)
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
                    // Some CDNs / WAFs reject requests without a User-Agent.
                    connection.setRequestProperty("User-Agent", "Ikuriye/1.0 (Android)")
                    connection.getInputStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val size = file.length()
                    Log.d("MediaCache", "DONE  $shortUrl → ${file.name} (${size} bytes, attempt $attempt)")
                    synchronized(this@MediaCache) {
                        index[url] = CacheEntry(url, file.absolutePath, System.currentTimeMillis())
                        trimToCapacity()
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
            trimToCapacity()
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
                try {
                    // Global concurrency cap — cacheMedia itself also dedupes per-URL.
                    downloadSemaphore.withPermit {
                        cacheMedia(url)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * True-LRU touch: refreshes an entry's [CacheEntry.cachedAt] to now so it
     * counts as recently used. Caller must hold the lock (called from within
     * synchronized blocks). Persisted asynchronously on [Dispatchers.IO] via a
     * debounced [pendingPersist] job, so the LRU order survives process death
     * without doing disk I/O on the main thread (where [getCachedFile] runs).
     */
    private fun touch(url: String, entry: CacheEntry) {
        val now = System.currentTimeMillis()
        if (now - entry.cachedAt < 1_000L) return // touched within the last second — skip
        index[url] = entry.copy(cachedAt = now)
        schedulePersist()
    }

    /**
     * Schedules a debounced index write on [Dispatchers.IO]. Rapid touches within
     * [PERSIST_DEBOUNCE_MS] coalesce into a single write. Must be called while
     * holding the lock (touch is called inside synchronized blocks); the write
     * itself re-acquires the lock to serialize with other index writes.
     *
     * The job nulls out [pendingPersist] inside the lock so every read/write of
     * it happens under the same monitor — closing a tiny race where a touch
     * landing right after the job released the lock (but before it was marked
     * completed) would otherwise be skipped and never persisted.
     */
    private fun schedulePersist() {
        if (pendingPersist?.isActive == true) return // a debounce is already running
        pendingPersist = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            synchronized(this@MediaCache) {
                pendingPersist = null
                saveIndex()
            }
        }
    }

    /**
     * Keeps the cache bounded: evicts the least-recently-used entries (by
     * [CacheEntry.cachedAt], which is touched on every access) once
     * [MAX_ENTRIES] is exceeded, deleting their files from disk. Caller must
     * hold the lock (called from within synchronized blocks).
     */
    private fun trimToCapacity() {
        if (index.size <= MAX_ENTRIES) return
        val overflow = index.size - MAX_ENTRIES
        val lru = index.values.sortedBy { it.cachedAt }.take(overflow)
        lru.forEach { entry ->
            File(entry.localPath).delete()
            index.remove(entry.url)
            Log.d("MediaCache", "TRIM evict ${entry.url.take(60)}")
        }
        Log.d("MediaCache", "TRIM done: ${lru.size} entries evicted (cache now ${index.size})")
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
