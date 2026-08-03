package com.gocavgo.ikuriye.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PackageCache {

    private const val TAG = "PackageCache"
    private const val CACHE_FILE = "my_packages_page0.json"
    private const val EXPIRY_MS = 60 * 60 * 1000L

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "pkg_cache")
        cacheDir?.mkdirs()
        evictExpired()
    }

    fun getCached(): PagedResult? {
        val dir = cacheDir ?: return null
        val file = File(dir, CACHE_FILE)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val cachedAt = json.optLong("cachedAt", 0L)
            if (System.currentTimeMillis() - cachedAt > EXPIRY_MS) {
                Log.d(TAG, "Cache expired")
                file.delete()
                return null
            }
            val itemsArr = json.getJSONArray("items")
            val items = mutableListOf<ClientPackage>()
            for (i in 0 until itemsArr.length()) {
                items.add(packageFromJson(itemsArr.getJSONObject(i)))
            }
            Log.d(TAG, "Cache hit: ${items.size} packages")
            PagedResult(
                items = items,
                totalCount = json.optInt("totalCount", items.size),
                totalPages = json.optInt("totalPages", 1),
                currentPage = json.optInt("currentPage", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache", e)
            file.delete()
            null
        }
    }

    fun save(result: PagedResult) {
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
            File(dir, CACHE_FILE).writeText(json.toString())
            Log.d(TAG, "Cache saved: ${result.items.size} packages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache", e)
        }
    }

    fun clear() {
        cacheDir?.let {
            File(it, CACHE_FILE).delete()
        }
    }

    private fun evictExpired() {
        cacheDir?.listFiles()?.forEach { file ->
            if (file.name == CACHE_FILE) {
                try {
                    val json = JSONObject(file.readText())
                    val cachedAt = json.optLong("cachedAt", 0L)
                    if (System.currentTimeMillis() - cachedAt > EXPIRY_MS) {
                        file.delete()
                        Log.d(TAG, "Evicted expired cache")
                    }
                } catch (_: Exception) {
                    file.delete()
                }
            }
        }
    }

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
        val mediaArr = JSONArray()
        pkg.mediaUrls.forEach { url -> mediaArr.put(url) }
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
