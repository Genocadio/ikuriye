package com.gocavgo.ikuriye.data

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import com.gocavgo.ikuriye.AcceptPackageByTransferMutation
import com.gocavgo.ikuriye.AvailablePackagesQuery
import com.gocavgo.ikuriye.ConfirmDeliveryMutation
import com.gocavgo.ikuriye.ConfirmTransferMutation
import com.gocavgo.ikuriye.RejectTransferMutation
import com.gocavgo.ikuriye.CreatePackageMutation
import com.gocavgo.ikuriye.CreateTransferMutation
import com.gocavgo.ikuriye.InitiateDeliveryMutation
import com.gocavgo.ikuriye.MyPackagesQuery
import com.gocavgo.ikuriye.MyTransfersQuery
import com.gocavgo.ikuriye.PackageByIdQuery
import com.gocavgo.ikuriye.PackageByTrackingCodeQuery
import com.gocavgo.ikuriye.RegenerateDeliveryCodeMutation
import com.gocavgo.ikuriye.RequestTransferMutation
import com.gocavgo.ikuriye.TransfersForMeQuery
import com.gocavgo.ikuriye.UpdatePackageStatusMutation
import com.gocavgo.ikuriye.type.UpdatePackageStatusInput
import com.gocavgo.ikuriye.network.ApolloClientProvider
import com.gocavgo.ikuriye.type.AcceptTransferInput
import com.gocavgo.ikuriye.type.ConfirmDeliveryInput
import com.gocavgo.ikuriye.type.CreatePackageInput
import com.gocavgo.ikuriye.type.CreateTransferInput
import com.gocavgo.ikuriye.type.InitiateDeliveryInput
import com.gocavgo.ikuriye.type.PackageStatus as GqlPackageStatus
import com.gocavgo.ikuriye.type.RegenerateDeliveryCodeInput
import com.gocavgo.ikuriye.type.SortOrder
import com.gocavgo.ikuriye.type.TransferRuleType
import com.apollographql.apollo.api.Optional
import kotlinx.coroutines.isActive

data class PagedResult(
    val items: List<ClientPackage>,
    val totalCount: Int,
    val totalPages: Int,
    val currentPage: Int
)

/**
 * Result of a packages fetch. A [FetchPackagesResult.Error] is any
 * transport/GraphQL-level failure — callers MUST NOT treat it as an
 * authoritative empty list (e.g. by saving it over a good cache). A
 * legitimate "user has no packages" response is still [FetchPackagesResult.Success]
 * with an empty page.
 */
sealed interface FetchPackagesResult {
    data class Success(val page: PagedResult) : FetchPackagesResult
    data class Error(val message: String?) : FetchPackagesResult
}

/**
 * Generic result wrapper for single-item fetches and mutations.
 * [NotFound] means the server returned null (legitimate empty response).
 * [Failure] means a transport/GraphQL error — callers MUST NOT treat it
 * as "item doesn't exist" (e.g. by deleting a cache entry).
 */
sealed interface SingleResult<out T> {
    data class Success<T>(val data: T) : SingleResult<T>
    data class NotFound(val message: String? = null) : SingleResult<Nothing>
    data class Failure(val message: String?) : SingleResult<Nothing>
}

object PackageRepository {

    private const val TAG = "PackageRepository"

    suspend fun fetchMyPackages(
        page: Int = 0,
        size: Int = 20,
        order: SortOrder = SortOrder.DESC
    ): FetchPackagesResult {
        return try {
            val response = ApolloClientProvider.client
                .query(MyPackagesQuery(
                    page = Optional.presentIfNotNull(page),
                    size = Optional.presentIfNotNull(size),
                    order = Optional.presentIfNotNull(order)
                ))
                .execute()
            val errors = response.errors
            val data = response.data
            if (BuildConfig.DEBUG) Log.d(TAG, "fetchMyPackages: hasErrors=${errors != null && errors.isNotEmpty()}, hasData=${data != null}")
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "fetchMyPackages: GraphQL errors — $errorMsgs")
                FetchPackagesResult.Error(errorMsgs)
            } else if (data != null) {
                val page = data.myPackages
                FetchPackagesResult.Success(PagedResult(
                    items = page.items.map { pkg -> mapMyPackage(pkg) },
                    totalCount = page.totalCount,
                    totalPages = page.totalPages,
                    currentPage = page.currentPage
                ))
            } else {
                Log.e(TAG, "fetchMyPackages: no data")
                FetchPackagesResult.Error("GraphQL response contained no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyPackages: exception — ${e.message}", e)
            FetchPackagesResult.Error(e.message)
        }
    }

    suspend fun fetchAvailablePackages(
        page: Int = 0,
        size: Int = 20,
        order: SortOrder = SortOrder.DESC
    ): FetchPackagesResult {
        return try {
            val response = ApolloClientProvider.client
                .query(AvailablePackagesQuery(
                    page = Optional.presentIfNotNull(page),
                    size = Optional.presentIfNotNull(size),
                    order = Optional.presentIfNotNull(order)
                ))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "fetchAvailablePackages: GraphQL errors — $errorMsgs")
                FetchPackagesResult.Error(errorMsgs)
            } else if (data != null) {
                val page = data.availablePackages
                FetchPackagesResult.Success(PagedResult(
                    items = page.items.map { pkg -> mapAvailablePackage(pkg) },
                    totalCount = page.totalCount,
                    totalPages = page.totalPages,
                    currentPage = page.currentPage
                ))
            } else {
                Log.e(TAG, "fetchAvailablePackages: no data")
                FetchPackagesResult.Error("GraphQL response contained no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAvailablePackages: exception — ${e.message}", e)
            FetchPackagesResult.Error(e.message)
        }
    }

    suspend fun trackByCode(code: String): SingleResult<ClientPackage> {
        return try {
            val response = ApolloClientProvider.client
                .query(PackageByTrackingCodeQuery(code))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "trackByCode: GraphQL errors — $errorMsgs")
                SingleResult.Failure(errorMsgs)
            } else if (data != null) {
                val pkg = data.packageByTrackingCode?.let { mapTrackedPackage(it) }
                if (pkg != null) SingleResult.Success(pkg) else SingleResult.NotFound("Package not found")
            } else {
                Log.e(TAG, "trackByCode: no data")
                SingleResult.Failure("GraphQL response contained no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "trackByCode: exception — ${e.message}", e)
            SingleResult.Failure(e.message)
        }
    }

    /**
     * Fetch a single package by its internal UUID from the backend.
     * Used e.g. when a notification points to a package that may not
     * be present in the locally-cached lists.
     */
    suspend fun fetchPackageById(id: String): SingleResult<ClientPackage> {
        return try {
            val response = ApolloClientProvider.client
                .query(PackageByIdQuery(id))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "fetchPackageById: GraphQL errors — $errorMsgs")
                SingleResult.Failure(errorMsgs)
            } else if (data != null) {
                val pkg = data.`package`?.let { mapPackageById(it) }
                if (pkg != null) SingleResult.Success(pkg) else SingleResult.NotFound("Package not found")
            } else {
                Log.e(TAG, "fetchPackageById: no data")
                SingleResult.Failure("GraphQL response contained no data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPackageById: exception — ${e.message}", e)
            SingleResult.Failure(e.message)
        }
    }

    suspend fun createPackage(input: CreatePackageInput): ClientPackage? {
        return try {
            val response = ApolloClientProvider.client
                .mutation(CreatePackageMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "createPackage: hasErrors=${errors != null && errors.isNotEmpty()}, hasData=${data != null}")
                if (data != null) {
                    val pkg = data.createPackage.deliveryPackage
                    Log.d(TAG, "createPackage: media=${pkg.details?.media?.map { "${it?.url} (${it?.mimeType})" }}")
                }
            }
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "createPackage: GraphQL errors — $errorMsgs")
                null
            } else if (data != null) {
                mapCreatedPackage(data.createPackage)
            } else {
                Log.e(TAG, "createPackage: no data")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPackage: exception — ${e.message}", e)
            null
        }
    }

    // ── Shared mapping helper ─────────────────────────────────────────────────

    /**
     * Common builder shared by all GraphQL response → [ClientPackage] mappers.
     * Callers extract the raw fields and pass them here, keeping each mapper
     * focused on GraphQL-specific null-safety / field extraction.
     */
    private fun buildClientPackage(
        trackingCode: String,
        packageUuid: String,
        deliveryType: String = "FIXED_ROUTE",
        senderId: String = "",
        senderName: String = "",
        senderPhone: String = "",
        fromAddress: String = "",
        recipientId: String = "",
        recipientName: String = "",
        recipientPhone: String = "",
        toAddress: String = "",
        description: String = "",
        weight: String = "",
        category: String = "",
        fragile: Boolean = false,
        mediaUrls: List<String> = emptyList(),
        photoCount: Int = 0,
        status: PackageStatus,
        createdAt: String = "",
        updatedAt: String = "",
        statusHistory: List<StatusUpdate> = emptyList(),
        custodians: List<CustodianInfo> = emptyList(),
        transfers: List<ServerTransferInfo> = emptyList(),
        transferId: String? = null,
        transferStatus: String? = null,
        transferRuleType: String? = null,
        backendStatus: String = ""
    ): ClientPackage {
        val firstActive = transfers.firstOrNull { it.status in OPEN_TRANSFER_STATUSES }
        return ClientPackage(
            id = trackingCode,
            trackingCode = trackingCode,
            packageUuid = packageUuid,
            deliveryType = deliveryType,
            senderId = senderId,
            senderName = senderName,
            senderPhone = senderPhone,
            fromAddress = fromAddress,
            recipientId = recipientId,
            recipientName = recipientName,
            recipientPhone = recipientPhone,
            toAddress = toAddress,
            description = description,
            weight = weight,
            category = category,
            fragile = fragile,
            mediaUrls = mediaUrls,
            photoCount = photoCount,
            status = status,
            driverName = "",
            driverPhone = "",
            createdAt = createdAt,
            receivedAt = if (status == PackageStatus.DELIVERED) updatedAt else "",
            statusHistory = statusHistory,
            custodians = custodians,
            transfers = transfers,
            transferId = transferId ?: firstActive?.id,
            transferStatus = transferStatus ?: firstActive?.status,
            transferRuleType = transferRuleType ?: firstActive?.ruleType,
            backendStatus = backendStatus
        )
    }

    // ── Per-query mappers (each extracts fields then calls buildClientPackage) ──

    private fun mapCreatedPackage(result: CreatePackageMutation.CreatePackage): ClientPackage {
        val pkg = result.deliveryPackage
        val sender = pkg.people?.find { it?.role?.name == "SENDER" }
        val receiver = pkg.people?.find { it?.role?.name == "RECEIVER" }
        val origin = pkg.locations?.find { it?.type?.name == "ORIGIN" }
        val destination = pkg.locations?.find { it?.type?.name == "DESTINATION" }
        val history = (pkg.events ?: emptyList()).mapNotNull { event ->
            event?.let {
                StatusUpdate(
                    status = mapStatusFromEventType(it.eventType),
                    timestamp = it.createdAt,
                    location = "",
                    message = it.description ?: ""
                )
            }
        }
        val transfer = result.transfer
        val serverTransfers = if (transfer != null) {
            listOf(ServerTransferInfo(transfer.id, transfer.ruleType.name, transfer.status.name))
        } else emptyList()

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            deliveryType = pkg.deliveryType.name,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it?.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = (pkg.custodians ?: emptyList()).mapNotNull { c ->
                c?.let { CustodianInfo(it.id, it.userId, it.name ?: "", it.phone ?: "", it.role.name, it.assignedAt) }
            },
            transfers = serverTransfers,
            transferId = transfer?.id,
            transferStatus = transfer?.status?.name,
            transferRuleType = transfer?.ruleType?.name,
            backendStatus = pkg.status.name
        )
    }

    private fun mapMyPackage(pkg: MyPackagesQuery.Item): ClientPackage {
        val sender = pkg.people?.find { it?.role?.name == "SENDER" }
        val receiver = pkg.people?.find { it?.role?.name == "RECEIVER" }
        val origin = pkg.locations.find { it?.type?.name == "ORIGIN" }
        val destination = pkg.locations.find { it?.type?.name == "DESTINATION" }
        val history = (pkg.events ?: emptyList()).mapNotNull { event ->
            event?.let {
                StatusUpdate(
                    status = mapStatusFromEventType(it.eventType),
                    timestamp = it.createdAt,
                    location = "",
                    message = it.description ?: ""
                )
            }
        }
        val serverTransfers = pkg.transfers.map { t ->
            ServerTransferInfo(id = t.id, ruleType = t.ruleType.name, status = t.status.name)
        }

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            deliveryType = pkg.deliveryType.name,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it?.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = (pkg.custodians ?: emptyList()).mapNotNull { c ->
                c?.let { CustodianInfo(it.id, it.userId, it.name ?: "", it.phone ?: "", it.role.name, it.assignedAt) }
            },
            transfers = serverTransfers,
            backendStatus = pkg.status.name
        )
    }

    private fun mapAvailablePackage(pkg: AvailablePackagesQuery.Item): ClientPackage {
        val sender = pkg.people?.find { it?.role?.name == "SENDER" }
        val receiver = pkg.people?.find { it?.role?.name == "RECEIVER" }
        val origin = pkg.locations?.find { it?.type?.name == "ORIGIN" }
        val destination = pkg.locations?.find { it?.type?.name == "DESTINATION" }
        val history = (pkg.events ?: emptyList()).mapNotNull { event ->
            event?.let {
                StatusUpdate(
                    status = mapStatusFromEventType(it.eventType),
                    timestamp = it.createdAt,
                    location = "",
                    message = it.description ?: ""
                )
            }
        }
        val serverTransfers = pkg.transfers.map { t ->
            ServerTransferInfo(id = t.id, ruleType = t.ruleType.name, status = t.status.name)
        }

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it?.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = (pkg.custodians ?: emptyList()).mapNotNull { c ->
                c?.let { CustodianInfo(it.id, it.userId, it.name ?: "", it.phone ?: "", it.role.name, it.assignedAt) }
            },
            transfers = serverTransfers,
            backendStatus = pkg.status.name
        )
    }

    internal fun mapPackageById(pkg: PackageByIdQuery.Package): ClientPackage {
        val sender = pkg.people.find { it.role.name == "SENDER" }
        val receiver = pkg.people.find { it.role.name == "RECEIVER" }
        val origin = pkg.locations.find { it.type.name == "ORIGIN" }
        val destination = pkg.locations.find { it.type.name == "DESTINATION" }
        val history = pkg.events.mapNotNull { event ->
            StatusUpdate(
                status = mapStatusFromEventType(event.eventType),
                timestamp = event.createdAt,
                location = "",
                message = event.description ?: ""
            )
        }

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = pkg.custodians.map { c ->
                CustodianInfo(c.id, c.userId, c.name ?: "", c.phone ?: "", c.role.name, c.assignedAt)
            },
            backendStatus = pkg.status.name
        )
    }

    private fun mapTrackedPackage(pkg: PackageByTrackingCodeQuery.PackageByTrackingCode): ClientPackage {
        val sender = pkg.people?.find { it?.role?.name == "SENDER" }
        val receiver = pkg.people?.find { it?.role?.name == "RECEIVER" }
        val origin = pkg.locations?.find { it?.type?.name == "ORIGIN" }
        val destination = pkg.locations?.find { it?.type?.name == "DESTINATION" }
        val history = (pkg.events ?: emptyList()).mapNotNull { event ->
            event?.let {
                StatusUpdate(
                    status = mapStatusFromEventType(it.eventType),
                    timestamp = it.createdAt,
                    location = "",
                    message = it.description ?: ""
                )
            }
        }
        val serverTransfers = (pkg.transfers ?: emptyList()).map { t ->
            ServerTransferInfo(id = t.id, ruleType = t.ruleType.name, status = t.status.name)
        }

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it?.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = (pkg.custodians ?: emptyList()).mapNotNull { c ->
                c?.let { CustodianInfo(it.id, it.userId, it.name ?: "", it.phone ?: "", it.role.name, it.assignedAt) }
            },
            transfers = serverTransfers,
            backendStatus = pkg.status.name
        )
    }

    private fun mapConfirmedPackage(pkg: ConfirmDeliveryMutation.ConfirmDelivery): ClientPackage {
        val sender = pkg.people.find { it.role.name == "SENDER" }
        val receiver = pkg.people.find { it.role.name == "RECEIVER" }
        val origin = pkg.locations.find { it.type.name == "ORIGIN" }
        val destination = pkg.locations.find { it.type.name == "DESTINATION" }
        val history = pkg.events.mapNotNull { event ->
            StatusUpdate(
                status = mapStatusFromEventType(event.eventType),
                timestamp = event.createdAt,
                location = "",
                message = event.description ?: ""
            )
        }

        return buildClientPackage(
            trackingCode = pkg.trackingCode,
            packageUuid = pkg.id,
            senderId = sender?.userId ?: "",
            senderName = sender?.name ?: "",
            senderPhone = sender?.phone ?: "",
            fromAddress = origin?.placeName ?: "",
            recipientId = receiver?.userId ?: "",
            recipientName = receiver?.name ?: "",
            recipientPhone = receiver?.phone ?: "",
            toAddress = destination?.placeName ?: "",
            description = pkg.details?.description ?: "",
            weight = pkg.details?.weight?.let { "$it kg" } ?: "",
            category = pkg.details?.category ?: "",
            fragile = pkg.details?.fragile ?: false,
            mediaUrls = pkg.details?.media?.mapNotNull { it.url } ?: emptyList(),
            photoCount = pkg.details?.media?.size ?: 0,
            status = mapStatus(pkg.status),
            createdAt = pkg.createdAt,
            updatedAt = pkg.updatedAt ?: "",
            statusHistory = history,
            custodians = pkg.custodians.map { c ->
                CustodianInfo(c.id, c.userId, c.name ?: "", c.phone ?: "", c.role.name, c.assignedAt)
            },
            backendStatus = pkg.status.name
        )
    }



    // ── Pickup Code Operations ───────────────────────────────────────────────

    data class PickupCodeResult(
        val packageId: String,
        val pickupCode: String
    )

    suspend fun generatePickupCode(packageId: String): PickupCodeResult? {
        // TODO: generatePickupCode mutation is not yet implemented on the server.
        // Stubbed to return null so the app doesn't crash.
        Log.w(TAG, "generatePickupCode: mutation not available on server")
        return null
    }

    // ── Delivery Initiation / Confirmation ──────────────────────────────────

    /**
     * Result of [initiateDelivery] / [regenerateDeliveryCode].
     * Carries the one-time 6-digit code that only the sender/receiver may see.
     */
    data class DeliveryCodeResult(
        val packageUuid: String,
        val deliveryCode: String,
        val packageStatus: PackageStatus
    )

    /**
     * Initiates delivery for a package: transitions it to PENDING_CONFIRMATION
     * and generates a one-time delivery code published to the sender/receiver.
     * Callable by the current custodian (driver) only — no code required.
     */
    suspend fun initiateDelivery(packageUuid: String): DeliveryCodeResult? {
        return try {
            val input = InitiateDeliveryInput(packageId = packageUuid)
            val response = ApolloClientProvider.client
                .mutation(InitiateDeliveryMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "initiateDelivery: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                DeliveryCodeResult(
                    packageUuid = data.initiateDelivery.deliveryPackage.id,
                    deliveryCode = data.initiateDelivery.deliveryCode,
                    packageStatus = mapStatus(data.initiateDelivery.deliveryPackage.status)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "initiateDelivery: exception — ${e.message}", e)
            null
        }
    }

    /**
     * Confirms delivery of a PENDING_CONFIRMATION package using the delivery
     * code received from the sender/receiver. Sets the package to DELIVERED.
     */
    suspend fun confirmDelivery(packageUuid: String, deliveryCode: String): ClientPackage? {
        return try {
            val input = ConfirmDeliveryInput(packageId = packageUuid, deliveryCode = deliveryCode)
            val response = ApolloClientProvider.client
                .mutation(ConfirmDeliveryMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "confirmDelivery: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                mapConfirmedPackage(data.confirmDelivery)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmDelivery: exception — ${e.message}", e)
            null
        }
    }

    /**
     * Updates the status of a package (e.g. ASSIGNED_DRIVER → IN_TRANSIT, IN_TRANSIT → DESTINATION_OFFICE).
     * Used by drivers for FIXED_ROUTE flows to transition between states.
     */
    suspend fun updatePackageStatus(packageUuid: String, newStatus: String, notes: String = ""): ClientPackage? {
        return try {
            val statusEnum = try {
                GqlPackageStatus.valueOf(newStatus)
            } catch (_: IllegalArgumentException) {
                Log.e(TAG, "updatePackageStatus: unknown status '$newStatus'")
                return null
            }
            val actorId = AuthRepository.getCachedUser()?.id ?: ""
            val input = UpdatePackageStatusInput(
                packageId = packageUuid,
                actorId = actorId,
                status = statusEnum,
                notes = Optional.presentIfNotNull(notes.ifBlank { null })
            )
            val response = ApolloClientProvider.client
                .mutation(UpdatePackageStatusMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "updatePackageStatus: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                val pkg = data.updatePackageStatus
                val sender = pkg.people?.find { it?.role?.name == "SENDER" }
                val receiver = pkg.people?.find { it?.role?.name == "RECEIVER" }
                val origin = pkg.locations?.find { it?.type?.name == "ORIGIN" }
                val destination = pkg.locations?.find { it?.type?.name == "DESTINATION" }
                val history = (pkg.events ?: emptyList()).mapNotNull { event ->
                    event?.let {
                        StatusUpdate(
                            status = mapStatusFromEventType(it.eventType),
                            timestamp = it.createdAt,
                            location = "",
                            message = it.description ?: ""
                        )
                    }
                }
                buildClientPackage(
                    trackingCode = pkg.trackingCode,
                    packageUuid = pkg.id,
                    deliveryType = pkg.deliveryType.name,
                    senderId = sender?.userId ?: "",
                    senderName = sender?.name ?: "",
                    senderPhone = sender?.phone ?: "",
                    fromAddress = origin?.placeName ?: "",
                    recipientId = receiver?.userId ?: "",
                    recipientName = receiver?.name ?: "",
                    recipientPhone = receiver?.phone ?: "",
                    toAddress = destination?.placeName ?: "",
                    description = pkg.details?.description ?: "",
                    weight = pkg.details?.weight?.let { "$it kg" } ?: "",
                    category = pkg.details?.category ?: "",
                    fragile = pkg.details?.fragile ?: false,
                    mediaUrls = pkg.details?.media?.mapNotNull { it?.url } ?: emptyList(),
                    photoCount = pkg.details?.media?.size ?: 0,
                    status = mapStatus(pkg.status),
                    createdAt = pkg.createdAt,
                    updatedAt = pkg.updatedAt ?: "",
                    statusHistory = history,
                    custodians = (pkg.custodians ?: emptyList()).mapNotNull { c ->
                        c?.let { CustodianInfo(it.id, it.userId, it.name ?: "", it.phone ?: "", it.role.name, it.assignedAt) }
                    },
                    backendStatus = pkg.status.name
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "updatePackageStatus: exception — ${e.message}", e)
            null
        }
    }

    /**
     * Regenerates the delivery code while a package is PENDING_CONFIRMATION.
     * Invalidates the previous code and re-publishes the new one.
     */
    suspend fun regenerateDeliveryCode(packageUuid: String): DeliveryCodeResult? {
        return try {
            val input = RegenerateDeliveryCodeInput(packageId = packageUuid)
            val response = ApolloClientProvider.client
                .mutation(RegenerateDeliveryCodeMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "regenerateDeliveryCode: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                DeliveryCodeResult(
                    packageUuid = data.regenerateDeliveryCode.deliveryPackage.id,
                    deliveryCode = data.regenerateDeliveryCode.deliveryCode,
                    packageStatus = mapStatus(data.regenerateDeliveryCode.deliveryPackage.status)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "regenerateDeliveryCode: exception — ${e.message}", e)
            null
        }
    }

    // ── Transfer Operations ──────────────────────────────────────────────────

    data class TransferInfo(
        val id: String,
        val ruleType: String,
        val status: String,
        val transferCode: String? = null,
        val packageIds: List<String> = emptyList()
    )

    suspend fun createTransfer(
        packageIds: List<String>,
        ruleType: String,
        matchUserId: String? = null,
        acceptorType: String = "WORKER"
    ): TransferInfo? {
        return try {
            val ruleTypeEnum = try {
                TransferRuleType.valueOf(ruleType)
            } catch (_: IllegalArgumentException) { null } ?: return null

            val acceptorTypeEnum = try {
                com.gocavgo.ikuriye.type.TransferAcceptorType.valueOf(acceptorType)
            } catch (_: IllegalArgumentException) { null } ?: return null

            val input = CreateTransferInput(
                packageIds = packageIds,
                ruleType = ruleTypeEnum,
                acceptorType = Optional.present(acceptorTypeEnum),
                matchUserId = if (matchUserId?.isNotBlank() == true) Optional.present(matchUserId) else Optional.absent()
            )
            val response = ApolloClientProvider.client
                .mutation(CreateTransferMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "createTransfer: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                val t = data.createTransfer
                TransferInfo(
                    id = t.id,
                    ruleType = t.ruleType.name,
                    status = t.status.name,
                    transferCode = t.transferCode,
                    packageIds = t.packages.mapNotNull { it?.packageId }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "createTransfer: exception — ${e.message}", e)
            null
        }
    }

    suspend fun confirmTransfer(transferId: String): TransferInfo? {
        return try {
            val response = ApolloClientProvider.client
                .mutation(ConfirmTransferMutation(transferId))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "confirmTransfer: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                val t = data.confirmTransfer
                TransferInfo(
                    id = t.id,
                    ruleType = t.ruleType.name,
                    status = t.status.name,
                    packageIds = t.packages.mapNotNull { it?.packageId }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "confirmTransfer: exception — ${e.message}", e)
            null
        }
    }

    suspend fun rejectTransfer(transferId: String): TransferInfo? {
        return try {
            val response = ApolloClientProvider.client
                .mutation(RejectTransferMutation(transferId))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "rejectTransfer: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                null
            } else if (data != null) {
                val t = data.rejectTransfer
                TransferInfo(
                    id = t.id,
                    ruleType = t.ruleType.name,
                    status = t.status.name,
                    packageIds = t.packages.mapNotNull { it?.packageId }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "rejectTransfer: exception — ${e.message}", e)
            null
        }
    }

    suspend fun requestTransfer(transferId: String): TransferInfo? {
        return try {
            val input = AcceptTransferInput(
                transferId = transferId,
                transferCode = Optional.Absent
            )
            val response = ApolloClientProvider.client
                .mutation(RequestTransferMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                val errorMsgs = errors.joinToString("; ") { it.message ?: "unknown" }
                Log.e(TAG, "requestTransfer: GraphQL errors — $errorMsgs")
                null
            } else if (data != null) {
                val t = data.acceptTransfer.transfer
                TransferInfo(
                    id = t.id,
                    ruleType = t.ruleType.name,
                    status = t.status.name,
                    packageIds = t.packages.mapNotNull { it?.packageId }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestTransfer: exception — ${e.message}", e)
            null
        }
    }

    suspend fun acceptPackageByTransfer(transferId: String, transferCode: String? = null): Boolean {
        return try {
            val input = AcceptTransferInput(
                transferId = transferId,
                transferCode = if (transferCode?.isNotBlank() == true) Optional.present(transferCode) else Optional.Absent
            )
            val response = ApolloClientProvider.client
                .mutation(AcceptPackageByTransferMutation(input))
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "acceptPackageByTransfer: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                false
            } else {
                data != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptPackageByTransfer: exception — ${e.message}", e)
            false
        }
    }

    suspend fun fetchMyTransfers(): List<TransferInfo> {
        return try {
            val response = ApolloClientProvider.client
                .query(MyTransfersQuery())
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "fetchMyTransfers: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                emptyList()
            } else if (data != null) {
                data.myTransfers.mapNotNull { t ->
                    t?.let {
                        TransferInfo(
                            id = it.id,
                            ruleType = it.ruleType.name,
                            status = it.status.name,
                            packageIds = it.packages.mapNotNull { p -> p?.packageId }
                        )
                    }
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyTransfers: exception — ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches transfers targeted at the current user (matched by matchUserId).
     * Returns [MatchedTransfer] list — each with transfer metadata + the matched
     * package details. Used by the driver's "Transfers" tab to show packages
     * that workers have assigned to them.
     */
    data class MatchedTransfer(
        val transferId: String,
        val ruleType: String,
        val acceptorType: String,
        val status: String,
        val creatorId: String,
        val packages: List<ClientPackage>
    )

    suspend fun fetchTransfersForMe(): List<MatchedTransfer> {
        return try {
            val response = ApolloClientProvider.client
                .query(TransfersForMeQuery())
                .execute()
            val errors = response.errors
            val data = response.data
            if (errors != null && errors.isNotEmpty()) {
                Log.e(TAG, "fetchTransfersForMe: GraphQL errors — ${errors.joinToString("; ") { it.message ?: "unknown" }}")
                emptyList()
            } else if (data != null) {
                val results = mutableListOf<MatchedTransfer>()
                for (t in data.transfersForMe) {
                    val pkgIds = t.packages.mapNotNull { it?.packageId }
                    if (pkgIds.isEmpty()) continue

                    // Fetch each package by UUID to get full details
                    val packages = mutableListOf<ClientPackage>()
                    for (pkgId in pkgIds) {
                        when (val result = fetchPackageById(pkgId)) {
                            is SingleResult.Success -> packages.add(result.data)
                            else -> Log.w(TAG, "fetchTransfersForMe: package $pkgId not found")
                        }
                    }
                    if (packages.isNotEmpty()) {
                        results.add(MatchedTransfer(
                            transferId = t.id,
                            ruleType = t.ruleType.name,
                            acceptorType = t.acceptorType.name,
                            status = t.status.name,
                            creatorId = t.creatorId,
                            packages = packages
                        ))
                    }
                }
                results
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTransfersForMe: exception — ${e.message}", e)
            emptyList()
        }
    }

    // ── Polling-based new package detection (driver-side, real-time approx) ──

    /**
     * Periodically polls for available packages and emits only packages that
     * are new (not in the already-seen set). Uses a 30-second polling interval.
     *
     * @param seenIds set of package IDs already known — new IDs are emitted
     * @return a flow that emits new [ClientPackage]s as they appear
     */
    fun pollNewPackages(seenIds: Set<String> = emptySet()): kotlinx.coroutines.flow.Flow<ClientPackage> = kotlinx.coroutines.flow.flow {
        var knownIds = seenIds.toMutableSet()
        val baseIntervalMs = 30_000L
        val maxIntervalMs = 300_000L // 5 minutes cap
        var currentIntervalMs = baseIntervalMs
        var consecutiveFailures = 0
        // Small initial delay to let other startup work finish
        kotlinx.coroutines.delay(3_000)
        // Check isActive so the loop stops promptly when the collecting coroutine
        // is cancelled (e.g. driver logs out, ViewModel clears, app goes to background).
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                when (val result = fetchAvailablePackages(page = 0, size = 20, order = SortOrder.DESC)) {
                    is FetchPackagesResult.Success -> {
                        consecutiveFailures = 0
                        currentIntervalMs = baseIntervalMs
                        val newItems = result.page.items.filter { it.id !in knownIds }
                        if (newItems.isNotEmpty()) {
                            knownIds.addAll(newItems.map { it.id })
                            for (pkg in newItems) {
                                emit(pkg)
                            }
                        }
                    }
                    is FetchPackagesResult.Error -> {
                        consecutiveFailures++
                        currentIntervalMs = (baseIntervalMs * (1L shl (consecutiveFailures - 1).coerceAtMost(4)))
                            .coerceAtMost(maxIntervalMs)
                        Log.w(TAG, "pollNewPackages: fetch failed (attempt $consecutiveFailures, next in ${currentIntervalMs / 1000}s) — ${result.message}")
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                currentIntervalMs = (baseIntervalMs * (1L shl (consecutiveFailures - 1).coerceAtMost(4)))
                    .coerceAtMost(maxIntervalMs)
                Log.e(TAG, "pollNewPackages: exception (attempt $consecutiveFailures, next in ${currentIntervalMs / 1000}s) — ${e.message}", e)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            kotlinx.coroutines.delay(currentIntervalMs)
        }
    }

    internal fun mapStatus(gql: GqlPackageStatus): PackageStatus {
        return when (gql) {
            GqlPackageStatus.CREATED -> PackageStatus.PENDING
            GqlPackageStatus.ACCEPTED -> PackageStatus.PENDING
            GqlPackageStatus.PICKED_UP -> PackageStatus.PICKED_UP
            GqlPackageStatus.IN_TRANSIT -> PackageStatus.IN_TRANSIT
            GqlPackageStatus.PENDING_CONFIRMATION -> PackageStatus.PENDING_CONFIRMATION
            GqlPackageStatus.DELIVERED -> PackageStatus.DELIVERED
            GqlPackageStatus.COMPLETED -> PackageStatus.DELIVERED
            GqlPackageStatus.CANCELLED -> PackageStatus.CANCELLED
            GqlPackageStatus.ORIGIN_OFFICE -> PackageStatus.IN_TRANSIT
            GqlPackageStatus.ASSIGNED_DRIVER -> PackageStatus.PICKED_UP
            GqlPackageStatus.DESTINATION_OFFICE -> PackageStatus.ARRIVED_AT_OFFICE
            GqlPackageStatus.READY_FOR_COLLECTION -> PackageStatus.OUT_FOR_DELIVERY
            else -> PackageStatus.PENDING
        }
    }

    internal fun mapStatusFromEventType(eventType: String): PackageStatus {
        return when (eventType.uppercase()) {
            "CREATED", "ACCEPTED" -> PackageStatus.PENDING
            "PICKED_UP" -> PackageStatus.PICKED_UP
            "IN_TRANSIT" -> PackageStatus.IN_TRANSIT
            "DELIVERY_INITIATED" -> PackageStatus.PENDING_CONFIRMATION
            "DELIVERED", "COMPLETED" -> PackageStatus.DELIVERED
            "CANCELLED" -> PackageStatus.CANCELLED
            "ORIGIN_OFFICE", "ASSIGNED_DRIVER" -> PackageStatus.IN_TRANSIT
            "DESTINATION_OFFICE" -> PackageStatus.ARRIVED_AT_OFFICE
            "READY_FOR_COLLECTION" -> PackageStatus.OUT_FOR_DELIVERY
            else -> PackageStatus.PENDING
        }
    }
}
