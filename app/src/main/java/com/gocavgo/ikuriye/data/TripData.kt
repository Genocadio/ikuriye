package com.gocavgo.ikuriye.data

// ── Models ──────────────────────────────────────────────────────────────────

data class Package(
    val id: String,
    val label: String,
    val recipient: String,
    val weight: String,
    val notes: String = ""
)

data class TripStop(
    val id: Int,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val pickups: List<Package>,   // packages to pick up AT this stop
    val dropoffs: List<Package>   // packages to drop off AT this stop
)

data class Trip(
    val id: String,
    val routeLabel: String,
    val stops: List<TripStop>
)

// ── Dummy Data ───────────────────────────────────────────────────────────────

object DummyTrip {

    val trip = Trip(
        id = "TRIP-2024-001",
        routeLabel = "Musanze → Kigali Express",
        stops = listOf(
            TripStop(
                id = 0,
                name = "Musanze Depot",
                address = "KN 12 St, Musanze",
                lat = -1.4998,
                lng = 29.6340,
                pickups = listOf(
                    Package("PKG-001", "Medical Supplies", "Dr. Kamanzi", "3.2 kg", "Handle with care"),
                    Package("PKG-002", "Electronics Box", "TechHub Rwanda", "5.0 kg"),
                    Package("PKG-003", "Clothing Bundle", "Fashion Kigali", "2.1 kg")
                ),
                dropoffs = emptyList()
            ),
            TripStop(
                id = 1,
                name = "Nyirangarama",
                address = "Nyirangarama Village Centre",
                lat = -1.6120,
                lng = 29.7780,
                pickups = listOf(
                    Package("PKG-007", "Farm Produce", "Green Market", "8.5 kg", "Perishable — keep cool"),
                    Package("PKG-008", "Handcraft Items", "Ubunifu Arts", "1.8 kg")
                ),
                dropoffs = listOf(
                    Package("PKG-001", "Medical Supplies", "Dr. Kamanzi", "3.2 kg", "Handle with care")
                )
            ),
            TripStop(
                id = 2,
                name = "Base",
                address = "Base Trading Centre, Rulindo",
                lat = -1.7340,
                lng = 29.9120,
                pickups = listOf(
                    Package("PKG-010", "Spare Parts", "AutoFix Garage", "12.0 kg"),
                ),
                dropoffs = listOf(
                    Package("PKG-002", "Electronics Box", "TechHub Rwanda", "5.0 kg"),
                    Package("PKG-008", "Handcraft Items", "Ubunifu Arts", "1.8 kg")
                )
            ),
            TripStop(
                id = 3,
                name = "Kigali Hub",
                address = "KK 17 Ave, Kicukiro, Kigali",
                lat = -1.9441,
                lng = 30.0619,
                pickups = emptyList(),
                dropoffs = listOf(
                    Package("PKG-003", "Clothing Bundle", "Fashion Kigali", "2.1 kg"),
                    Package("PKG-007", "Farm Produce", "Green Market", "8.5 kg", "Perishable — keep cool"),
                    Package("PKG-010", "Spare Parts", "AutoFix Garage", "12.0 kg")
                )
            )
        )
    )
}
