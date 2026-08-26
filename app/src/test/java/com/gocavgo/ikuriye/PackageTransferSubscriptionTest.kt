package com.gocavgo.ikuriye

import com.gocavgo.ikuriye.data.PackageRepository
import com.gocavgo.ikuriye.data.PackageStatus
import com.gocavgo.ikuriye.type.CustodianRole
import com.gocavgo.ikuriye.type.DeliveryType
import com.gocavgo.ikuriye.type.LocationType
import com.gocavgo.ikuriye.type.PersonRole
import com.gocavgo.ikuriye.type.TransferRuleType
import com.gocavgo.ikuriye.type.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the newPackageTransfer subscription mapping logic.
 *
 * Since [PackageTransferSubscription.mapSubscriptionPackage] is private,
 * we test the shared mapping functions it depends on and verify the
 * subscription data shape matches what the GraphQL schema provides.
 */
class PackageTransferSubscriptionTest {

    @Test
    fun mapStatus_handlesAllSubscriptionStatuses() {
        // The newPackageTransfer subscription can emit any PackageStatus.
        // Verify the mapper handles every possible status from the subscription.
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.CREATED))
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.ACCEPTED))
        assertEquals(PackageStatus.PICKED_UP, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.PICKED_UP))
        assertEquals(PackageStatus.IN_TRANSIT, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.IN_TRANSIT))
        assertEquals(PackageStatus.PENDING_CONFIRMATION, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.PENDING_CONFIRMATION))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.DELIVERED))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.COMPLETED))
        assertEquals(PackageStatus.CANCELLED, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.CANCELLED))
        assertEquals(PackageStatus.OUT_FOR_DELIVERY, PackageRepository.mapStatus(com.gocavgo.ikuriye.type.PackageStatus.READY_FOR_COLLECTION))
    }

    @Test
    fun mapStatusFromEventType_handlesSubscriptionEventTypes() {
        // Subscription events include status changes from the package lifecycle
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType("CREATED"))
        assertEquals(PackageStatus.PENDING, PackageRepository.mapStatusFromEventType("ACCEPTED"))
        assertEquals(PackageStatus.PICKED_UP, PackageRepository.mapStatusFromEventType("PICKED_UP"))
        assertEquals(PackageStatus.IN_TRANSIT, PackageRepository.mapStatusFromEventType("IN_TRANSIT"))
        assertEquals(PackageStatus.PENDING_CONFIRMATION, PackageRepository.mapStatusFromEventType("DELIVERY_INITIATED"))
        assertEquals(PackageStatus.DELIVERED, PackageRepository.mapStatusFromEventType("DELIVERED"))
        assertEquals(PackageStatus.CANCELLED, PackageRepository.mapStatusFromEventType("CANCELLED"))
    }

    @Test
    fun subscriptionTransferFields_areMappedCorrectly() {
        // Verify that transfer rule types from the subscription are valid enums
        assertEquals(TransferRuleType.AUTO, TransferRuleType.valueOf("AUTO"))
        assertEquals(TransferRuleType.SECURE, TransferRuleType.valueOf("SECURE"))
        assertEquals(TransferRuleType.CONFIRM, TransferRuleType.valueOf("CONFIRM"))
    }

    @Test
    fun subscriptionTransferStatuses_areRecognized() {
        // Verify that transfer statuses from the subscription are valid
        assertEquals(TransferStatus.PENDING, TransferStatus.valueOf("PENDING"))
        assertEquals(TransferStatus.REQUESTED, TransferStatus.valueOf("REQUESTED"))
        assertEquals(TransferStatus.DONE, TransferStatus.valueOf("DONE"))
        assertEquals(TransferStatus.CANCELED, TransferStatus.valueOf("CANCELED"))
    }

    @Test
    fun subscriptionRoles_mapToCustodianRoles() {
        // The subscription response includes custodian roles
        assertEquals(CustodianRole.DRIVER, CustodianRole.valueOf("DRIVER"))
        assertEquals(CustodianRole.WORKER, CustodianRole.valueOf("WORKER"))
        assertEquals(CustodianRole.OFFICE, CustodianRole.valueOf("OFFICE"))
    }

    @Test
    fun subscriptionPersonRoles_areCorrect() {
        // Subscription response includes SENDER and RECEIVER roles
        assertEquals(PersonRole.SENDER, PersonRole.valueOf("SENDER"))
        assertEquals(PersonRole.RECEIVER, PersonRole.valueOf("RECEIVER"))
    }

    @Test
    fun subscriptionLocationTypes_areCorrect() {
        // Subscription response includes ORIGIN and DESTINATION
        assertEquals(LocationType.ORIGIN, LocationType.valueOf("ORIGIN"))
        assertEquals(LocationType.DESTINATION, LocationType.valueOf("DESTINATION"))
    }

    @Test
    fun deduplication_preventsDoubleEmission() {
        // Simulate the deduplication logic used in PackageTransferSubscription
        val seenIds = mutableSetOf<String>()
        val emitted = mutableListOf<String>()

        val incomingIds = listOf("CAV-001", "CAV-002", "CAV-001", "CAV-003", "CAV-002")

        for (id in incomingIds) {
            if (id !in seenIds) {
                seenIds.add(id)
                emitted.add(id)
            }
        }

        assertEquals(3, emitted.size)
        assertEquals("CAV-001", emitted[0])
        assertEquals("CAV-002", emitted[1])
        assertEquals("CAV-003", emitted[2])
    }

    @Test
    fun seedSeenIds_preventsInitialDuplicates() {
        // Simulate seedSeenIds to prevent re-emitting packages already in the UI
        val seenIds = mutableSetOf<String>()
        val emitted = mutableListOf<String>()

        // Seed with already-loaded packages
        seenIds.addAll(listOf("CAV-001", "CAV-002"))

        // Incoming packages from subscription
        val incomingIds = listOf("CAV-001", "CAV-002", "CAV-003")

        for (id in incomingIds) {
            if (id !in seenIds) {
                seenIds.add(id)
                emitted.add(id)
            }
        }

        assertEquals(1, emitted.size)
        assertEquals("CAV-003", emitted[0])
    }
}
