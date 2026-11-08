package io.github.brickystudios.funkblick.core

/**
 * The IEEE registry from assets/oui.txt (built by tools/build_oui.py): "PREFIX<TAB>Company" with 6, 7 or 9 hex
 * digits for MA-L (/24), MA-M (/28) and MA-S (/36). Longer prefixes win, they carve up blocks the IEEE owns.
 */
class OuiDb(lines: Sequence<String>) {
    private val byLength = HashMap<Int, HashMap<String, String>>()

    init {
        for (line in lines) {
            val tab = line.indexOf('\t')
            if (tab !in 6..9) continue
            byLength.getOrPut(tab) { HashMap() }[line.substring(0, tab)] = line.substring(tab + 1)
        }
    }

    val size get() = byLength.values.sumOf { it.size }

    fun lookup(address: String): String? {
        val hex = hexOf(address) ?: return null
        for (n in intArrayOf(9, 7, 6)) byLength[n]?.get(hex.substring(0, n))?.let { return it }
        return null
    }

    companion object {
        val EMPTY = OuiDb(emptySequence())
    }
}

/** "aa:bb:cc:dd:ee:ff" or "AABBCCDDEEFF" -> "AABBCCDDEEFF", null if it isn't a MAC address. */
fun hexOf(address: String): String? {
    val hex = address.filter { it != ':' && it != '-' }.uppercase()
    return hex.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
}

/** Locally administered bit: phones pick such addresses at random to avoid being tracked. */
fun isLocallyAdministered(address: String): Boolean {
    val hex = hexOf(address) ?: return false
    return hex.substring(0, 2).toInt(16) and 0x02 != 0
}

/** Bluetooth SIG company identifiers seen most often around here (manufacturer-specific data). */
val BLE_COMPANIES = mapOf(
    0x004C to "Apple",
    0x0075 to "Samsung",
    0x0006 to "Microsoft",
    0x00E0 to "Google",
    0x0087 to "Garmin",
    0x0157 to "Huami (Amazfit)",
    0x038F to "Xiaomi",
    0x027D to "Huawei",
    0x0059 to "Nordic Semiconductor",
    0x02E5 to "Espressif",
    0x000F to "Broadcom",
    0x0131 to "Cypress",
    0x0499 to "Ruuvi",
    0x0171 to "Amazon",
    0x009E to "Bose",
    0x012D to "Sony",
    0x04EC to "Motorola Solutions",
    0x0D53 to "Luxottica (Ray-Ban Meta)",
    0x03C2 to "Snap (Spectacles)",
    0x060C to "Vuzix",
)
