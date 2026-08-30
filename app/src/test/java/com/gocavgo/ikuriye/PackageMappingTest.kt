package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.PackageRepository
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.type.CustodianRole
import com.gocavgo.ikuriye.type.DeliveryType
import com.gocavgo.ikuriye.type.LocationType
import com.gocavgo.ikuriye.type.PersonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageMappingTest {

    @Test
    fun mapStatus_mapsAllGqlStatusesToDomain() {
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.CREATED))
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.ACCEPTED))
        assertEquals(PackageStatus.PICKED_UP, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.PICKED_UP))
        assertEquals(PackageStatus.PICKED_UP, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.ASSIGNED_DRIVER))
        assertEquals(PackageStatus.IN_TRANSIT, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.IN_TRANSIT))
        assertEquals(PackageStatus.IN_TRANSIT, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.ORIGIN_OFFICE))
        assertEquals(PackageStatus.ARRIVED_AT_OFFICE, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.DESTINATION_OFFICE))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.DELIVERED))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.COMPLETED))
        assertEquals(PackageStatus.CANCELLED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.CANCELLED))
        assertEquals(PackageStatus.OUT_FOR_DELIVERY, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.READY_FOR_COLLECTION))
    }

    @Test
    fun mapStatusFromEventType_mapsKnownEvents() {
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType("CREATED"))
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType("ACCEPTED"))
        assertEquals(PackageStatus.PICKED_UP, PackageRepository.mapStatusFromEventType("PICKED_UP"))
        assertEquals(PackageStatus.IN_TRANSIT, PackageRepository.mapStatusFromEventType("IN_TRANSIT"))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatusFromEventType("DELIVERED"))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatusFromEventType("COMPLETED"))
        assertEquals(PackageStatus.CANCELLED, PackageRepository.mapStatusFromEventType("cancelled"))
        assertEquals(PackageStatus.OUT_FOR_DELIVERY, PackageRepository.mapStatusFromEventType("READY_FOR_COLLECTION"))
    }

    @Test
    fun mapStatusFromEventType_mapsDestinationOfficeToArrivedAtOffice() {
        assertEquals(PackageStatus.ARRIVED_AT_OFFICE, PackageRepository.mapStatusFromEventType("DESTINATION_OFFICE"))
    }

    @Test
    fun mapStatusFromEventType_unknownEventFallsBackToPending() {
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType("UNKNOWN_EVENT"))
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType(""))
    }

    @Test
    fun mapPackageById_mapsAllFields() {
        val pkg = PackageByIdQuery.Package(
            id = "uuid-1",
            trackingCode = "CAV-000001",
            deliveryType = DeliveryType.OPEN,
            status = com.gocavgo.ikuriye.type.PackageStatus.DELIVERED,
            creatorId = "user-1",
            custodians = listOf(
                PackageByIdQuery.Custodian("c1", "user-2", "Jean", "+2507", CustodianRole.DRIVER, "2026-01-01T00:00:00Z")
            ),
            people = listOf(
                PackageByIdQuery.Person("p1", PersonRole.SENDER, "user-1", "Alice", "+2501"),
                PackageByIdQuery.Person("p2", PersonRole.RECEIVER, "user-2", "Bob", "+2502")
            ),
            locations = listOf(
                PackageByIdQuery.Location("l1", LocationType.ORIGIN, "Kigali", 0.0, 0.0),
                PackageByIdQuery.Location("l2", LocationType.DESTINATION, "Musanze", 1.0, 1.0)
            ),
            details = PackageByIdQuery.Details(
                category = "Electronics",
                description = "Laptop",
                fragile = true,
                weight = 2.5,
                media = listOf(PackageByIdQuery.Medium("m1", "https://x/y.jpg", "image/jpeg"))
            ),
            events = listOf(
                PackageByIdQuery.Event("e1", "CREATED", "user-1", "Package created", "2026-01-01T00:00:00Z"),
                PackageByIdQuery.Event("e2", "DELIVERED", "user-2", "Delivered", "2026-01-02T00:00:00Z")
            ),
            custody = emptyList(),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-02T00:00:00Z"
        )

        val mapped = PackageRepository.mapPackageById(pkg)

        assertEquals("CAV-000001", mapped.id)
        assertEquals("CAV-000001", mapped.trackingCode)
        assertEquals("uuid-1", mapped.packageUuid)
        assertEquals(PackageStatus.DELIVERED, mapped.status)
        assertEquals("Alice", mapped.senderName)
        assertEquals("+2501", mapped.senderPhone)
        assertEquals("user-1", mapped.senderId)
        assertEquals("Bob", mapped.recipientName)
        assertEquals("+2502", mapped.recipientPhone)
        assertEquals("Kigali", mapped.fromAddress)
        assertEquals("Musanze", mapped.toAddress)
        assertEquals("Laptop", mapped.description)
        assertEquals("Electronics", mapped.category)
        assertTrue(mapped.fragile)
        assertEquals("2.5 kg", mapped.weight)
        assertEquals(listOf("https://x/y.jpg"), mapped.mediaUrls)
        assertEquals(1, mapped.photoCount)
        assertEquals(2, mapped.statusHistory.size)
        assertEquals("Package created", mapped.statusHistory[0].message)
        assertEquals("Delivered", mapped.statusHistory[1].message)
        assertEquals(1, mapped.custodians.size)
        assertEquals("Jean", mapped.custodians[0].name)
        assertEquals(CustodianRole.DRIVER.name, mapped.custodians[0].role)
        assertEquals("2026-01-02T00:00:00Z", mapped.receivedAt)
        assertEquals("DELIVERED", mapped.backendStatus)
    }

    @Test
    fun mapPackageById_nullableDetailsAndPeople_defaultToEmpty() {
        val pkg = PackageByIdQuery.Package(
            id = "uuid-2",
            trackingCode = "CAV-000002",
            deliveryType = DeliveryType.OPEN,
            status = com.gocavgo.ikuriye.type.PackageStatus.CREATED,
            creatorId = "user-1",
            custodians = emptyList(),
            people = emptyList(),
            locations = emptyList(),
            details = null,
            events = emptyList(),
            custody = emptyList(),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )

        val mapped = PackageRepository.mapPackageById(pkg)

        assertEquals(PackageStatus.PENDING, mapped.status)
        assertEquals("", mapped.senderName)
        assertEquals("", mapped.recipientName)
        assertEquals("", mapped.fromAddress)
        assertEquals("", mapped.toAddress)
        assertEquals("", mapped.description)
        assertEquals("", mapped.weight)
        assertEquals(0, mapped.photoCount)
        assertTrue(mapped.mediaUrls.isEmpty())
        assertEquals("", mapped.receivedAt)
    }
}
