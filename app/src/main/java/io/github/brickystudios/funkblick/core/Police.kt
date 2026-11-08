package io.github.brickystudios.funkblick.core

// "Probably police" from evidence, never from a hunch (plan C). Research 08.11.2026: NRW police wear Axon Body 2 body
// cams (9,000 ordered); Axon gear advertises Bluetooth with the fixed prefix 00:25:DF. NRW patrol cars are Ford S-Max,
// Mercedes Vito and, from late 2025, Ford Tourneo Custom; older ones BMW 318d Touring. Police radio (TETRA) is out of reach.
// Checked again 08.11.2026: 400 Tourneo Custom ordered, deliveries with the Vito from December 2025 (land.nrw).

enum class PoliceLevel { POSSIBLE, PROBABLE }

data class Police(val level: PoliceLevel, val why: String)

/** Rough box around North Rhine-Westphalia: the fleet rule only holds there. */
fun inNrw(lat: Double?, lon: Double?) = lat != null && lon != null && lat in 50.32..52.53 && lon in 5.86..9.46

private val NRW_FLEET_NAME = Regex("ford|mercedes|mbux|mb hotspot|tourneo|s-max|vito|sprinter", RegexOption.IGNORE_CASE)

/** [others] are the other devices of the session; "at the same time" means within a minute of each other. */
fun policeFor(d: Device, others: Collection<Device>, lat: Double?, lon: Double?): Police? {
    val c = d.cls
    if (c.mute != null) return null
    if (c.police != null) return Police(PoliceLevel.PROBABLE, tr("Von dir als Polizei markiert", "You marked this as police"))
    if (c.category == Category.PRIORITY && c.vendor?.startsWith("Axon", ignoreCase = true) == true)
        return Police(PoliceLevel.PROBABLE, tr("Axon-Gerät: Die Polizei NRW trägt Axon-Bodycams", "Axon device: NRW police wear Axon body cams"))
    if (c.category == Category.PRIORITY) return Police(PoliceLevel.POSSIBLE, tr("Hersteller von Behörden- und Einsatztechnik", "Maker of public safety gear"))
    if (!d.isFollowingVehicle()) return null
    val gearNow = others.any { it !== d && it.cls.category == Category.PRIORITY && kotlin.math.abs(it.lastSeen - d.lastSeen) <= 60_000 }
    if (gearNow) return Police(PoliceLevel.PROBABLE, tr("Fährt mit, und zur selben Zeit ist Behördentechnik in Reichweite", "Comes along while public safety gear is in range"))
    if (c.category == Category.FLEET) return Police(PoliceLevel.POSSIBLE, tr("Mobilfunkrouter fährt mit: Einsatz- oder Nutzfahrzeug", "A cellular router comes along: service or emergency vehicle"))
    if (c.category == Category.CAR && inNrw(lat, lon) && d.name?.let { NRW_FLEET_NAME.containsMatchIn(it) } == true)
        return Police(PoliceLevel.POSSIBLE, tr("Fährt mit, Marke passt zur NRW-Streifenwagen-Flotte (Ford, Mercedes)", "Comes along, brand matches the NRW patrol fleet (Ford, Mercedes)"))
    return null
}
