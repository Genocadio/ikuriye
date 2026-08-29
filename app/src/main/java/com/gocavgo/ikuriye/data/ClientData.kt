package com.gocavgo.ikuriye.data

import java.util.UUID

// ── Client Models ────────────────────────────────────────────────────────────

data class ClientUser(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
    val username: String? = null,
    val avatarUrl: String? = null
)

enum class PackageStatus {
    PENDING,
    PICKED_UP,
    IN_TRANSIT,
    PENDING_CONFIRMATION,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED
}

data class ClientPackage(
    val id: String = UUID.randomUUID().toString().take(8).uppercase(),
    val trackingCode: String = "",
    val senderId: String = "",
    val senderName: String,
    val senderPhone: String,
    val fromAddress: String,
    val recipientId: String = "",
    val recipientName: String,
    val recipientPhone: String,
    val toAddress: String,
    val description: String,
    val weight: String = "",
    val category: String = "",
    val fragile: Boolean = false,
    val photoCount: Int = 0,
    val mediaUrls: List<String> = emptyList(),
    val status: PackageStatus = PackageStatus.PENDING,
    val driverName: String = "",
    val driverPhone: String = "",
    val driverCompany: String = "",
    val vehicleType: String = "",
    val deliveryCode: String = "",
    val createdAt: String = "Just now",
    val receivedAt: String = "",
    val statusHistory: List<StatusUpdate> = emptyList(),
    val custodians: List<CustodianInfo> = emptyList(),
    val transferRuleType: String? = null,
    val transferMatchUserId: String? = null,
    val transferMatchUserName: String? = null,
    // Transfer data
    val transferId: String? = null,
    val transferStatus: String? = null,
    // Server-returned transfers list — includes all open transfers
    val transfers: List<ServerTransferInfo> = emptyList(),
    // Internal server UUID — used when calling mutations that need the backend ID
    val packageUuid: String = ""
)

/**
 * Returns true if the package is in an active (in-progress) delivery state.
 * Terminal states (DELIVERED, CANCELLED) return false.
 */
fun ClientPackage.isActive(): Boolean =
    status != PackageStatus.DELIVERED && status != PackageStatus.CANCELLED

/**
 * True while the package has an in-progress (open) transfer — either a
 * REQUESTED transfer awaiting confirmation or a PENDING transfer already in
 * motion. While open, no further transfer may be created.
 */
val OPEN_TRANSFER_STATUSES = setOf("PENDING", "REQUESTED")
val ClientPackage.hasOpenTransfer: Boolean
    get() = transferStatus in OPEN_TRANSFER_STATUSES ||
        transfers.any { it.status in OPEN_TRANSFER_STATUSES }

/**
 * Returns true if any unread notice in [notices] is about this package.
 * Matches by resourceId (package UUID) or by trackingCode in the payload.
 */
fun ClientPackage.hasUnreadNotices(notices: List<Notice>): Boolean =
    notices.any { n ->
        n.viewerReadAt == null &&
        n.resourceType.equals("PACKAGE", ignoreCase = true) &&
        (
            n.resourceId == packageUuid ||
            n.resourceId == id ||
            n.payload?.let { payload ->
                try {
                    org.json.JSONObject(payload).optString("trackingCode") == id
                } catch (_: Exception) { false }
            } == true
        )
    }

/**
 * Sorts a package list so packages with unread notices appear first,
 * preserving the original order within each group.
 */
fun List<ClientPackage>.sortedByUnreadNotices(notices: List<Notice>): List<ClientPackage> {
    val (withNotices, withoutNotices) = partition { it.hasUnreadNotices(notices) }
    return withNotices + withoutNotices
}


data class CustodianInfo(
    val id: String,
    val userId: String,
    val name: String = "",
    val phone: String = "",
    val role: String,
    val assignedAt: String
)

data class ServerTransferInfo(
    val id: String,
    val ruleType: String,
    val status: String
)

data class StatusUpdate(
    val status: PackageStatus,
    val timestamp: String,
    val location: String = "",
    val message: String = ""
)

// ── Dummy Data ───────────────────────────────────────────────────────────────

object DummyClientData {

    val client = ClientUser(
        id = "CLI-001",
        name = "Alice Uwimana",
        email = "alice@gocavgo.com",
        phone = "+250 785 234 567",
        password = "client123"
    )

    val driver = ClientUser(
        id = "DRV-001",
        name = "Jean Bosco",
        email = "driver@gocavgo.com",
        phone = "+250 788 123 456",
        password = "driver123"
    )

    val samplePackages = listOf(
        ClientPackage(
            id = "PKG-1001",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "KN 5 St, Kigali",
            recipientName = "Dr. Kamanzi",
            recipientPhone = "+250 783 111 222",
            toAddress = "Musanze Hospital, Musanze",
            description = "Medical equipment and supplies for the clinic",
            weight = "3.2 kg",
            photoCount = 2,
            status = PackageStatus.DELIVERED,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            createdAt = "2 hours ago",
            receivedAt = "5 min ago",
            statusHistory = emptyList()
        ),
        ClientPackage(
            id = "PKG-1002",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "KK 17 Ave, Kicukiro",
            recipientName = "TechHub Rwanda",
            recipientPhone = "+250 784 333 444",
            toAddress = "Nyabugogo Business Park",
            description = "Laptop and accessories",
            weight = "2.5 kg",
            photoCount = 0,
            status = PackageStatus.IN_TRANSIT,
            driverName = "Patrick Nshimiyimana",
            driverPhone = "+250 789 222 333",
            vehicleType = "bike",
            createdAt = "45 minutes ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "30min ago", "KK 17 Ave, Kicukiro", "Picked up from sender"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "15min ago", "KN 3 Rd", "Departed Kicukiro heading north"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "10min ago", "KN 5 St", "Passed Kigali city centre"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "5min ago", "RN7 Highway", "On the highway — arriving soon")
            )
        ),
        ClientPackage(
            id = "PKG-1003",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "Remera Plaza, Kigali",
            recipientName = "Fashion Kigali",
            recipientPhone = "+250 786 555 666",
            toAddress = "Huye Main Terminal",
            description = "Clothing boutique samples for wholesale",
            weight = "5.0 kg",
            photoCount = 3,
            status = PackageStatus.PICKED_UP,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            createdAt = "20 minutes ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "5min ago", "Remera Plaza, Kigali", "Picked up from sender")
            )
        ),
        ClientPackage(
            id = "PKG-1004",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "Gikondo, Kigali",
            recipientName = "Green Market Co.",
            recipientPhone = "+250 787 777 888",
            toAddress = "Rubavu Market, Rubavu",
            description = "Fresh farm produce — vegetables and fruits",
            weight = "8.0 kg",
            photoCount = 1,
            status = PackageStatus.PENDING,
            driverName = "",
            driverPhone = "",
            createdAt = "Just now",
            statusHistory = emptyList()
        ),
        ClientPackage(
            id = "PKG-1005",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "Kimironko, Kigali",
            recipientName = "AutoFix Garage",
            recipientPhone = "+250 789 999 000",
            toAddress = "Base Trading Centre, Rulindo",
            description = "Car engine parts — heavy items",
            weight = "12.0 kg",
            photoCount = 0,
            status = PackageStatus.OUT_FOR_DELIVERY,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            createdAt = "1 hour ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "50min ago", "Kimironko, Kigali", "Picked up from sender"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "35min ago", "RN7 Highway", "Departed Kigali heading north"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "25min ago", "Rulindo Junction", "Passed Rulindo junction"),
                StatusUpdate(PackageStatus.OUT_FOR_DELIVERY, "15min ago", "Rulindo Area", "Arriving soon at destination")
            )
        ),
        ClientPackage(
            id = "PKG-1006",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "Nyabugogo Bus Park",
            recipientName = "Sports Hub",
            recipientPhone = "+250 782 444 555",
            toAddress = "Rubavu Station",
            description = "Sports equipment for training camp",
            weight = "6.5 kg",
            photoCount = 0,
            status = PackageStatus.CANCELLED,
            driverName = "",
            driverPhone = "",
            createdAt = "3 hours ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PENDING, "3h ago", "Nyabugogo Bus Park", "Order placed"),
                StatusUpdate(PackageStatus.CANCELLED, "2h 50min ago", "Nyabugogo Bus Park", "Cancelled by sender")
            )
        )
    )

    val locations = listOf(
        "KN 5 St, Kigali",
        "KK 17 Ave, Kicukiro",
        "Remera Plaza, Kigali",
        "Gikondo, Kigali",
        "Kimironko, Kigali",
        "Nyabugogo Bus Park",
        "Musanze Hospital, Musanze",
        "Huye Main Terminal, Huye",
        "Rubavu Market, Rubavu",
        "Base Trading Centre, Rulindo"
    )
}

// ── Driver Package Dummy Data ──────────────────────────────────────────────

object DummyDriverPackages {

    val currentPackages = listOf(
        ClientPackage(
            id = "PKG-2001",
            senderName = "Paul Nkurunziza",
            senderPhone = "+250 781 111 333",
            fromAddress = "KN 5 St, Kigali",
            recipientName = "Dr. Kamanzi",
            recipientPhone = "+250 783 111 222",
            toAddress = "Musanze Hospital, Musanze",
            description = "Medical equipment and supplies for the clinic",
            weight = "3.2 kg",
            photoCount = 2,
            status = PackageStatus.IN_TRANSIT,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            deliveryCode = "MED-4421",
            createdAt = "2 hours ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "1h 45min ago", "KN 5 St, Kigali", "Picked up from sender"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "1h ago", "RN7 Highway", "In transit to Musanze")
            )
        ),
        ClientPackage(
            id = "PKG-2002",
            senderName = "Grace Mukamana",
            senderPhone = "+250 782 222 444",
            fromAddress = "Kimironko, Kigali",
            recipientName = "AutoFix Garage",
            recipientPhone = "+250 789 999 000",
            toAddress = "Base Trading Centre, Rulindo",
            description = "Car engine parts — heavy items",
            weight = "12.0 kg",
            photoCount = 0,
            status = PackageStatus.OUT_FOR_DELIVERY,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            deliveryCode = "ENG-8833",
            createdAt = "1 hour ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "50min ago", "Kimironko, Kigali", "Picked up from sender"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "35min ago", "RN7 Highway", "Departed Kigali heading north"),
                StatusUpdate(PackageStatus.OUT_FOR_DELIVERY, "15min ago", "Rulindo Area", "Arriving at destination")
            )
        ),
        ClientPackage(
            id = "PKG-2003",
            senderName = "Alice Uwimana",
            senderPhone = "+250 785 234 567",
            fromAddress = "Remera Plaza, Kigali",
            recipientName = "Fashion Kigali",
            recipientPhone = "+250 786 555 666",
            toAddress = "Huye Main Terminal, Huye",
            description = "Clothing boutique samples for wholesale",
            weight = "5.0 kg",
            photoCount = 3,
            status = PackageStatus.DELIVERED,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            deliveryCode = "FSH-7712",
            createdAt = "3 hours ago",
            receivedAt = "1h ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "2h 40min ago", "Remera Plaza, Kigali", "Picked up from sender"),
                StatusUpdate(PackageStatus.IN_TRANSIT, "2h ago", "RN7 Highway", "In transit to Huye"),
                StatusUpdate(PackageStatus.DELIVERED, "1h ago", "Huye Main Terminal", "Delivered to Fashion Kigali")
            )
        ),
        ClientPackage(
            id = "PKG-2004",
            senderName = "Emmanuel Habimana",
            senderPhone = "+250 784 555 777",
            fromAddress = "Gikondo, Kigali",
            recipientName = "Green Market Co.",
            recipientPhone = "+250 787 777 888",
            toAddress = "Rubavu Market, Rubavu",
            description = "Fresh farm produce — vegetables and fruits",
            weight = "8.0 kg",
            photoCount = 1,
            status = PackageStatus.PICKED_UP,
            driverName = "Jean Bosco",
            driverPhone = "+250 788 123 456",
            driverCompany = "QuickCargo Ltd",
            vehicleType = "car",
            deliveryCode = "FRM-3399",
            createdAt = "15 minutes ago",
            statusHistory = listOf(
                StatusUpdate(PackageStatus.PICKED_UP, "5min ago", "Gikondo, Kigali", "Picked up from sender")
            )
        )
    )

    val availableOffers = listOf(
        ClientPackage(
            id = "PKG-3001",
            senderName = "Samuel Iradukunda",
            senderPhone = "+250 786 333 111",
            fromAddress = "Nyabugogo Bus Park",
            recipientName = "Sports Hub",
            recipientPhone = "+250 782 444 555",
            toAddress = "Rubavu Station, Rubavu",
            description = "Sports equipment for training camp",
            weight = "6.5 kg",
            photoCount = 0,
            status = PackageStatus.PENDING,
            createdAt = "10 minutes ago",
            statusHistory = emptyList()
        ),
        ClientPackage(
            id = "PKG-3002",
            senderName = "Diane Uwimana",
            senderPhone = "+250 783 888 222",
            fromAddress = "KN 5 St, Kigali",
            recipientName = "TechHub Rwanda",
            recipientPhone = "+250 784 333 444",
            toAddress = "Nyabugogo Business Park, Kigali",
            description = "Laptop and accessories",
            weight = "2.5 kg",
            photoCount = 1,
            status = PackageStatus.PENDING,
            createdAt = "25 minutes ago",
            statusHistory = emptyList()
        ),
        ClientPackage(
            id = "PKG-3003",
            senderName = "Jean Ndayisaba",
            senderPhone = "+250 787 999 444",
            fromAddress = "KK 17 Ave, Kicukiro",
            recipientName = "Kigali Art Gallery",
            recipientPhone = "+250 781 222 555",
            toAddress = "Musanze Cultural Centre, Musanze",
            description = "Framed paintings for exhibition",
            weight = "4.0 kg",
            photoCount = 4,
            status = PackageStatus.PENDING,
            createdAt = "5 minutes ago",
            statusHistory = emptyList()
        )
    )
}
