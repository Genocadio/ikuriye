package com.gocavgo.ikuriye.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local-first package cache with separate stores for driver and client data.
 *
 * Architecture:
 * - **Driver current packages**: cached separately, smart-merged on refresh
 * - **Client packages**: cached separately, smart-merged on refresh
 * - **Completed packages**: cached without media URLs (lazy-load on demand)
 * - **Driver offers**: NOT cached (always fresh from server)
 *
 * Smart merge: on refresh, only packages that changed (status, transfer, etc.)
 * are updated; removed packages are dropped; new packages are added.
 * This prevents the "flash-empty" problem and keeps the UI stable.
 */
object PackageCache {

    private const val TAG = "PackageCache"
    private const val DRIVER_CACHE_FILE = "driver_packages.json"
    private const val CLIENT_CACHE_FILE = "client_packages.json"
    private const val EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days (was 1 hour)
    private const val TOUCH_THROTTLE_MS = 5 * 60 * 1000L

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "pkg_cache")
        cacheDir?.mkdirs()
        evictExpired()
    }

    // ── Driver Packages ──────────────────────────────────────────────────────

    fun getDriverCached(): PagedResult? = getCached(DRIVER_CACHE_FILE)

    fun saveDriver(result: PagedResult) = save(DRIVER_CACHE_FILE, result)

    fun mergeDriverUpdate(updated: ClientPackage) {
        mergeUpdate(DRIVER_CACHE_FILE, updated)
    }

    fun removeDriverPackage(packageId: String) {
        removePackage(DRIVER_CACHE_FILE, packageId)
    }

    // ── Client Packages ──────────────────────────────────────────────────────

    fun getClientCached(): PagedResult? = getCached(CLIENT_CACHE_FILE)

    fun saveClient(result: PagedResult) = save(CLIENT_CACHE_FILE, result)

    fun mergeClientUpdate(updated: ClientPackage) {
        mergeUpdate(CLIENT_CACHE_FILE, updated)
    }

    fun removeClientPackage(packageId: String) {
        removePackage(CLIENT_CACHE_FILE, packageId)
    }

    // ── Generic Cache Operations ─────────────────────────────────────────────

    private fun getCached(fileName: String): PagedResult? {
        val dir = cacheDir ?: return null
        val file = File(dir, fileName)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val cachedAt = json.optLong("cachedAt", 0L)
            if (System.currentTimeMillis() - cachedAt > EXPIRY_MS) {
                Log.d(TAG, "Cache expired: $fileName")
                file.delete()
                return null
            }
            val itemsArr = json.getJSONArray("items")
            val items = mutableListOf<ClientPackage>()
            for (i in 0 until itemsArr.length()) {
                items.add(packageFromJson(itemsArr.getJSONObject(i)))
            }
            touch(file, json, cachedAt)
            Log.d(TAG, "Cache hit: ${items.size} packages from $fileName")
            PagedResult(
                items = items,
                totalCount = json.optInt("totalCount", items.size),
                totalPages = json.optInt("totalPages", 1),
                currentPage = json.optInt("currentPage", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache: $fileName", e)
            file.delete()
            null
        }
    }

    private fun save(fileName: String, result: PagedResult) {
        val dir = cacheDir ?: return
        try {
            val itemsArr = JSONArray()
            result.items.forEach { pkg ->
                itemsArr.put(packageToJson(pkg))
            }
            val json = JSONObject().apply {
                put("cachedAt", System.currentTimeMillis())
                put("totalCount", result.totalCount)
                put("totalPages", result.totalPages)
                put("currentPage", result.currentPage)
                put("items", itemsArr)
            }
            File(dir, fileName).writeText(json.toString())
            Log.d(TAG, "Cache saved: ${result.items.size} packages to $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache: $fileName", e)
        }
    }

    /**
     * Smart merge: update a single package in the cache.
     * If the package exists, update it in-place. If not, add it.
     * This preserves the existing list order and avoids full re-fetch.
     */
    private fun mergeUpdate(fileName: String, updated: ClientPackage) {
        val dir = cacheDir ?: return
        val file = File(dir, fileName)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val itemsArr = json.getJSONArray("items")
            var found = false
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                if (item.optString("id") == updated.id) {
                    itemsArr.put(i, packageToJson(updated))
                    found = true
                    break
                }
            }
            if (!found) {
                // Package not in cache yet — prepend it
                itemsArr.put(0, packageToJson(updated))
            }
            json.put("items", itemsArr)
            json.put("cachedAt", System.currentTimeMillis()) // extend expiry
            file.writeText(json.toString())
            Log.d(TAG, "Cache merged: ${updated.id} in $fileName (found=$found)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge cache: $fileName", e)
        }
    }

    /**
     * Remove a package from the cache by ID.
     */
    private fun removePackage(fileName: String, packageId: String) {
        val dir = cacheDir ?: return
        val file = File(dir, fileName)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val itemsArr = json.getJSONArray("items")
            val newArr = JSONArray()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                if (item.optString("id") != packageId) {
                    newArr.put(item)
                }
            }
            json.put("items", newArr)
            json.put("totalCount", newArr.length())
            json.put("cachedAt", System.currentTimeMillis())
            file.writeText(json.toString())
            Log.d(TAG, "Cache removed: $packageId from $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove from cache: $fileName", e)
        }
    }

    fun clear() {
        cacheDir?.listFiles()?.forEach { it.delete() }
    }

    private fun touch(file: File, json: JSONObject, cachedAt: Long) {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TOUCH_THROTTLE_MS) return
        try {
            json.put("cachedAt", now)
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to touch cache", e)
        }
    }

    private fun evictExpired() {
        cacheDir?.listFiles()?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val cachedAt = json.optLong("cachedAt", 0L)
                if (System.currentTimeMillis() - cachedAt > EXPIRY_MS) {
                    file.delete()
                    Log.d(TAG, "Evicted expired cache: ${file.name}")
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
    }

    // ── JSON Serialization (media URLs excluded for completed packages) ──────

    private fun packageToJson(pkg: ClientPackage): JSONObject {
        val historyArr = JSONArray()
        pkg.statusHistory.forEach { h ->
            historyArr.put(JSONObject().apply {
                put("status", h.status.name)
                put("timestamp", h.timestamp)
                put("location", h.location)
                put("message", h.message)
            })
        }
        val custArr = JSONArray()
        pkg.custodians.forEach { c ->
            custArr.put(JSONObject().apply {
                put("id", c.id)
                put("userId", c.userId)
                put("role", c.role)
                put("assignedAt", c.assignedAt)
                if (c.name.isNotBlank()) put("name", c.name)
                if (c.phone.isNotBlank()) put("phone", c.phone)
            })
        }
        // Only cache media URLs for non-completed packages (save storage)
        val mediaArr = JSONArray()
        if (pkg.status != PackageStatus.DELIVERED && pkg.status != PackageStatus.CANCELLED) {
            pkg.mediaUrls.forEach { url -> mediaArr.put(url) }
        }
        return JSONObject().apply {
            put("id", pkg.id)
            put("trackingCode", pkg.trackingCode)
            put("senderId", pkg.senderId)
            put("senderName", pkg.senderName)
            put("senderPhone", pkg.senderPhone)
            put("fromAddress", pkg.fromAddress)
            put("recipientId", pkg.recipientId)
            put("recipientName", pkg.recipientName)
            put("recipientPhone", pkg.recipientPhone)
            put("toAddress", pkg.toAddress)
            put("description", pkg.description)
            put("weight", pkg.weight)
            put("category", pkg.category)
            put("fragile", pkg.fragile)
            put("photoCount", pkg.photoCount)
            put("mediaUrls", mediaArr)
            put("status", pkg.status.name)
            put("driverName", pkg.driverName)
            put("driverPhone", pkg.driverPhone)
            put("driverCompany", pkg.driverCompany)
            put("vehicleType", pkg.vehicleType)
            put("deliveryCode", pkg.deliveryCode)
            put("createdAt", pkg.createdAt)
            put("receivedAt", pkg.receivedAt)
            put("statusHistory", historyArr)
            put("custodians", custArr)
            if (pkg.transferId != null) put("transferId", pkg.transferId)
            if (pkg.transferStatus != null) put("transferStatus", pkg.transferStatus)
            if (pkg.transferRuleType != null) put("transferRuleType", pkg.transferRuleType)
            if (pkg.packageUuid.isNotBlank()) put("packageUuid", pkg.packageUuid)
            if (pkg.transfers.isNotEmpty()) {
                val transfersArr = JSONArray()
                pkg.transfers.forEach { t ->
                    transfersArr.put(JSONObject().apply {
                        put("id", t.id)
                        put("ruleType", t.ruleType)
                        put("status", t.status)
                    })
                }
                put("transfers", transfersArr)
            }
        }
    }

    private fun packageFromJson(json: JSONObject): ClientPackage {
        val mediaArr = json.optJSONArray("mediaUrls")
        val mediaUrls = if (mediaArr != null) {
            (0 until mediaArr.length()).map { mediaArr.getString(it) }
        } else emptyList()

        val historyArr = json.optJSONArray("statusHistory")
        val statusHistory = if (historyArr != null) {
            (0 until historyArr.length()).map { i ->
                val h = historyArr.getJSONObject(i)
                StatusUpdate(
                    status = try { PackageStatus.valueOf(h.getString("status")) } catch (_: Exception) { PackageStatus.PENDING },
                    timestamp = h.optString("timestamp", ""),
                    location = h.optString("location", ""),
                    message = h.optString("message", "")
                )
            }
        } else emptyList()

        val custArr = json.optJSONArray("custodians")
        val custodians = if (custArr != null) {
            (0 until custArr.length()).map { i ->
                val c = custArr.getJSONObject(i)
                CustodianInfo(
                    id = c.optString("id", ""),
                    userId = c.optString("userId", ""),
                    name = c.optString("name", ""),
                    phone = c.optString("phone", ""),
                    role = c.optString("role", ""),
                    assignedAt = c.optString("assignedAt", "")
                )
            }
        } else emptyList()

        return ClientPackage(
            id = json.optString("id", ""),
            trackingCode = json.optString("trackingCode", ""),
            senderId = json.optString("senderId", ""),
            senderName = json.optString("senderName", ""),
            senderPhone = json.optString("senderPhone", ""),
            fromAddress = json.optString("fromAddress", ""),
            recipientId = json.optString("recipientId", ""),
            recipientName = json.optString("recipientName", ""),
            recipientPhone = json.optString("recipientPhone", ""),
            toAddress = json.optString("toAddress", ""),
            description = json.optString("description", ""),
            weight = json.optString("weight", ""),
            category = json.optString("category", ""),
            fragile = json.optBoolean("fragile", false),
            photoCount = json.optInt("photoCount", 0),
            mediaUrls = mediaUrls,
            status = try { PackageStatus.valueOf(json.optString("status", "PENDING")) } catch (_: Exception) { PackageStatus.PENDING },
            driverName = json.optString("driverName", ""),
            driverPhone = json.optString("driverPhone", ""),
            driverCompany = json.optString("driverCompany", ""),
            vehicleType = json.optString("vehicleType", ""),
            deliveryCode = json.optString("deliveryCode", ""),
            createdAt = json.optString("createdAt", "Just now"),
            receivedAt = json.optString("receivedAt", ""),
            statusHistory = statusHistory,
            custodians = custodians,
            transferId = json.optString("transferId", "").ifBlank { null },
            transferStatus = json.optString("transferStatus", "").ifBlank { null },
            transferRuleType = json.optString("transferRuleType", "").ifBlank { null },
            packageUuid = json.optString("packageUuid", ""),
            transfers = run {
                val arr = json.optJSONArray("transfers")
                if (arr != null) {
                    (0 until arr.length()).mapNotNull { i ->
                        val t = arr.optJSONObject(i) ?: return@mapNotNull null
                        ServerTransferInfo(
                            id = t.optString("id", ""),
                            ruleType = t.optString("ruleType", ""),
                            status = t.optString("status", "")
                        )
                    }
                } else emptyList()
            }
        )
    }
}
