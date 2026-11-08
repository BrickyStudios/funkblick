package io.github.brickystudios.funkblick.core

/**
 * Turns one sighting into a category with the evidence behind it. Classification is kept apart from alerting:
 * Session decides what is worth a sound, this only says what the hardware probably is and why.
 */
class Classifier(private val oui: OuiDb) {

    fun classify(s: Sighting, rules: List<Rule>): Classification {
        val ev = mutableListOf<Evidence>()
        val local = isLocallyAdministered(s.address)
        val axonBlock = !local && hexOf(s.address)?.startsWith(AXON_PRIVATE_BLOCK) == true
        var vendor = if (local) null else if (axonBlock) "Axon Enterprise" else oui.lookup(s.address)
        if (vendor != null) ev += Evidence(
            Source.REGISTRY,
            if (axonBlock) tr("Adressblock D8:1F:65 ist bei der IEEE privat; Messungen im Feld ordnen ihn Axon zu", "Address block D8:1F:65 is private at the IEEE; field captures put Axon in it")
            else if (s.radio == Radio.WIFI) tr("IEEE-Register: Adressblock gehört $vendor", "IEEE registry: address block belongs to $vendor")
            else tr("IEEE-Register: $vendor (Bluetooth-Adressen sind oft zufällig, Treffer kann Zufall sein)",
                "IEEE registry: $vendor (Bluetooth addresses are often random, this may be chance)"),
            if (s.radio == Radio.WIFI) Confidence.HIGH else Confidence.LOW,
        )
        // Wi-Fi: the local bit is reliable. BLE: without a registry hit the address is almost always random.
        val random = local || (s.radio == Radio.BLE && vendor == null)

        s.manufacturerData.keys.firstNotNullOfOrNull { id -> BLE_COMPANIES[id]?.let { id to it } }?.let { (id, company) ->
            ev += Evidence(Source.PAYLOAD, tr("Bluetooth-Firmenkennung 0x%04X: $company", "Bluetooth company id 0x%04X: $company").format(id), Confidence.HIGH)
            if (vendor == null || random) vendor = company
        }

        val candidates = mutableListOf<Category>()
        val tracker = parseTracker(s)
        if (tracker != null) {
            val state = when (tracker.separated) {
                true -> tr("vom Besitzer getrennt", "away from its owner")
                false -> tr("beim Besitzer", "near its owner")
                null -> tr("Status unbekannt", "state unknown")
            }
            ev += Evidence(Source.PAYLOAD, tr("Sendet wie ${tracker.kind.label} ($state)", "Broadcasts like ${tracker.kind.label} ($state)"), Confidence.HIGH)
            candidates += if (tracker.kind.isTag) Category.TRACKER else Category.PERSONAL
        }
        payloadSignature(s)?.let { h ->
            ev += Evidence(Source.PAYLOAD, h.why, Confidence.HIGH)
            candidates += h.category
            if (h.axon && (vendor == null || random)) vendor = "Axon Enterprise"
        }
        val byName = matchName(s.name)
        byName?.let { sig ->
            ev += Evidence(Source.NAME, sig.why(), Confidence.MEDIUM)
            candidates += sig.category
        }
        // a tag's maker (Apple, Samsung) says "phone" and would only confuse
        if (tracker == null || !tracker.kind.isTag) matchVendor(vendor)?.let { sig ->
            ev += Evidence(Source.SIGNATURE, sig.why(), if (s.radio == Radio.WIFI && !random) Confidence.MEDIUM else Confidence.LOW)
            candidates += sig.category
        }
        if (s.security == Security.OPEN) ev += Evidence(
            Source.NAME, tr("Offenes WLAN ohne Verschlüsselung: andere im Netz können mitlesen, VPN nutzen",
                "Open Wi-Fi without encryption: others on it can read along, use a VPN"), Confidence.HIGH,
        )
        if (s.security == Security.WEP) ev += Evidence(
            Source.NAME, tr("WEP gilt seit 2004 als geknackt", "WEP has been broken since 2004"), Confidence.HIGH,
        )

        val watch = rules.firstOrNull { it.kind == RuleKind.WATCH && it.matches(s, vendor) }
        val mute = rules.firstOrNull { it.kind == RuleKind.MUTE && it.matches(s, vendor) }
        val police = rules.firstOrNull { it.kind == RuleKind.POLICE && it.matches(s, vendor) }
        watch?.let { ev += Evidence(Source.RULE, tr("Deine Regel: beobachten (${it.field.label()} „${it.value}“)", "Your rule: watch (${it.field.label()} \"${it.value}\")"), Confidence.HIGH) }
        police?.let { ev += Evidence(Source.RULE, tr("Von dir als Polizei markiert (${it.field.label()} „${it.value}“)", "You marked this as police (${it.field.label()} \"${it.value}\")"), Confidence.HIGH) }
        mute?.let { ev += Evidence(Source.RULE, tr("Deine Regel: stumm (${it.field.label()} „${it.value}“)", "Your rule: mute (${it.field.label()} \"${it.value}\")"), Confidence.HIGH) }

        // a train's or bus's passenger Wi-Fi (WIFIonICE on an Icomera router) is public Wi-Fi, not a vehicle to report
        if (Category.PUBLIC_WIFI in candidates) candidates.remove(Category.FLEET)
        // car makers' module suppliers (LG Innotek, Alps Alpine, Panasonic) also build TVs and controllers: a name that says what it is wins
        if (byName != null && byName.category != Category.CAR) candidates.remove(Category.CAR)
        // otherwise the most specific finding wins; enum order is TRACKER, PRIORITY, CAMERA, FLEET, PUBLIC_WIFI, ...
        val category = candidates.minByOrNull { it.ordinal } ?: Category.UNKNOWN
        return Classification(vendor, random, category, tracker, ev, watch, mute, police)
    }
}
