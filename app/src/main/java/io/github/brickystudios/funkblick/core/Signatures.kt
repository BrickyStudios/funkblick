package io.github.brickystudios.funkblick.core

// What a manufacturer or a network name says about the equipment. A match is a hint about the hardware,
// never about who runs it: a Cradlepoint router sits in buses, ambulances, delivery vans and police cars alike.

class Signature(val category: Category, val pattern: Regex, val why: () -> String)

private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

/** Matched against the shortened IEEE company name. */
val VENDOR_SIGNATURES = listOf(
    Signature(Category.PRIORITY, re("^(axon enterprise|taser|motorola solutions|watchguard video|digital ally|coban|kustom signals|zepcam|edesix|reveal media|sepura|getac|airbus ds|airbus defence|vievu|l-3 communications mobile-vision|icop digital|international police|patrol products|fusus)")) {
        tr("Hersteller von Bodycams, Funk- oder Fahrzeugtechnik für Polizei und Rettungsdienst. Sagt nichts darüber, wer das Gerät betreibt",
            "Maker of body cams, radios or vehicle gear for police and EMS. Says nothing about who operates this one")
    },
    // speed and red light cameras, speed guns: mobile ones stand at the roadside with a modem (makers per RF Sentinel's list)
    Signature(Category.PRIORITY, re("^(vitronic|jenoptik|robot visual|gatsometer|redflex|sensys traffic|sensys gatso|applied concepts)")) {
        tr("Hersteller von Blitzern und Geschwindigkeitsmessgeräten", "Maker of speed cameras and speed measuring gear")
    },
    Signature(Category.FLEET, re("^(cradlepoint|sierra wireless|inseego|novatel|teltonika|peplink|pismo labs|digi international|robustel|icomera|nomad digital)")) {
        tr("Hersteller baut Mobilfunkrouter für Fahrzeuge (Busse, Bahnen, Einsatz- und Lieferwagen)",
            "Maker of cellular routers for vehicles (buses, trains, service and delivery vans)")
    },
    Signature(Category.CAR, re("^(peiker|panasonic automotive|harman|alps ?alpine|magneti marelli|continental automotive|bosch car multimedia|visteon|aptiv|denso|preh|lg innotek)")) {
        tr("Hersteller von Auto-Infotainment und Telematik", "Maker of car infotainment and telematics")
    },
    Signature(Category.CAMERA, re("^(hangzhou hikvision|zhejiang dahua|hangzhou ezviz|axis communications|wyze|blink|arlo$|ring$|zhejiang uniview|vivotek|mobotix|reolink|hanwha vision|bosch security|i-pro)")) {
        tr("Hersteller von Überwachungs- und WLAN-Kameras", "Maker of security and Wi-Fi cameras")
    },
    Signature(Category.INFRA, re("^(cisco systems|cisco meraki|hewlett packard enterprise|aruba|ruckus|juniper|extreme networks|fortinet|ubiquiti|routerboard|mikrotik|lancom|cambium|ericsson)")) {
        tr("Hersteller von Firmen-Netzwerktechnik (Access Points, Controller)", "Maker of business network gear (access points, controllers)")
    },
    Signature(Category.HOME, re("^(avm|sagemcom|arcadyan|askey|sercomm|vantiva|technicolor|commscope|arris|zyxel|tp-link|netgear|d-link|cisco-linksys|the linksys|asustek|devolo|deutsche telekom|sky uk|adb broadband|tenda|sonos|amazon|tuya|espressif)")) {
        tr("Hersteller von Heimroutern und Smart-Home-Geräten", "Maker of home routers and smart home devices")
    },
    Signature(Category.PERSONAL, re("^(apple|samsung|huawei|xiaomi|beijing xiaomi|guangdong oppo|vivo mobile|oneplus|motorola mobility|google|garmin|fitbit|sony|bose|nothing tech)")) {
        tr("Hersteller von Handys, Uhren und Kopfhörern (bei WLAN meist ein Handy-Hotspot)",
            "Maker of phones, watches and headphones (on Wi-Fi usually a phone hotspot)")
    },
)

/** Matched against the SSID or BLE name. */
val NAME_SIGNATURES = listOf(
    Signature(Category.PRIORITY, re("^axon|^ab[234]-|axon body|watchguard|bodycam|body cam")) {
        tr("Name typisch für eine Bodycam oder Axon-Fahrzeugvideo", "Name typical of a body cam or Axon in-car video")
    },
    Signature(Category.FLEET, re("^(rut[x0-9]|teltonika|cradlepoint|ibr\\d|r1900-|r2100-)")) {
        tr("Name typisch für einen Mobilfunkrouter (Fahrzeug, Baustelle, Messanhänger)", "Name typical of a cellular router (vehicle, building site, trailer)")
    },
    // 4G-MIFI-xxxx and the like are 30-euro pocket routers: on the first drives they rang as "vehicle router" for nothing
    Signature(Category.PERSONAL, re("mifi|^4g[-_ ]|^lte[-_ ]|^5g[-_ ]router")) {
        tr("Name typisch für einen mobilen WLAN-Router zum Mitnehmen (MiFi), meist privat", "Name typical of a pocket Wi-Fi router (MiFi), mostly private")
    },
    Signature(Category.CAR, re("^(bmw \\d{4,6}|direct-bmw|direct-mb|my (bmw|vw|seat|cupra|skoda|ford|opel|peugeot|renault|citroen|mini)|audi_mmi|audi\\d|hyundai|kia[ _]|toyota|share_renault|carplay_|car-wifi|smartphone_connect|skoda|tesla|volvo|porsche|mercedes|mbux|mb hotspot|vw[ _]wlan|polestar|jeep|fiat|ford\\b|tourneo|s-max|sprinter)")) {
        tr("Name typisch für das WLAN eines Autos (Infotainment, CarPlay, Hotspot)", "Name typical of a car's Wi-Fi (infotainment, CarPlay, hotspot)")
    },
    Signature(Category.HOME, re("^direct-.*(hp|laserjet|officejet|deskjet|envy|smart tank|epson|canon|brother|bravia|samsung)")) {
        tr("Drucker oder Fernseher mit WLAN Direct", "Printer or TV with Wi-Fi Direct")
    },
    Signature(Category.HOME, re("^\\[(lg|tv|av)\\]|webos tv")) {
        tr("Name typisch für einen Fernseher oder eine Soundbar", "Name typical of a TV or soundbar")
    },
    Signature(Category.PUBLIC_WIFI, re("^(wifionice|wifi@db|telekom|telekom_fon|eduroam|cdwifi|flixbus.*)$|freifunk|bayernwlan|vodafone (hotspot|homespot)|railnet")) {
        tr("Bekanntes öffentliches WLAN (Bahn, Hotspot, Freifunk, Hochschule)", "Known public Wi-Fi (train, hotspot, Freifunk, university)")
    },
    Signature(Category.CAMERA, re("^(ipc|ipcam|hik-|ezviz_|tapo_cam|yi_|v380|lookcam|hdlivecam|ipc365|wificam|cam_|reolink|wyze|ring setup|70mai|viofo|blackvue)|camera|_cam\\b|^mv\\d{8}$|^dgk-[a-z0-9]{6}-[a-z0-9]{5}$|^ai_[a-z0-9]{6,12}$")) {
        tr("Name typisch für eine Kamera mit eigenem WLAN", "Name typical of a camera running its own Wi-Fi")
    },
)

fun matchVendor(vendor: String?): Signature? = vendor?.let { v -> VENDOR_SIGNATURES.firstOrNull { it.pattern.containsMatchIn(v) } }

fun matchName(name: String?): Signature? = name?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> NAME_SIGNATURES.firstOrNull { it.pattern.containsMatchIn(n) } }

// What Axon gear, camera glasses and drones put into their Bluetooth adverts, also behind a random address.
// After RF Sentinel (github.com/CIS-C0/RFSentinel, GPL-3.0) and the all-cameras-are-beacons reference (Apache-2.0).

/** IEEE lists this block as private; field captures put Axon devices in it. */
const val AXON_PRIVATE_BLOCK = "D81F65"

/** An "AXON-" hotspot, the "AB3-" network a body cam opens while pairing, Axon Fleet in-car Wi-Fi ("Axon12-5g"). */
val AXON_NAME = Regex("^(axon|ab[234]-)", RegexOption.IGNORE_CASE)

private const val CID_TASER = 0x034D
private val AXON_UUIDS = setOf(0xFC81, 0xFE6B, 0xFE6C) // Axon Enterprise, TASER International (2)
private const val UUID_REMOTE_ID = 0xFFFA // ASTM F3411 drone Remote ID
private val GLASSES_COMPANIES = setOf(0x0D53, 0x03C2, 0x060C) // Luxottica (Ray-Ban Meta), Snap (Spectacles), Vuzix
private const val CID_META_TECH = 0x058E
private val IDO_NAME = Regex("^ID[A-Z]?\\d{2,4}[A-Z]?$")

class PayloadHit(val category: Category, val axon: Boolean, val why: String)

fun payloadSignature(s: Sighting): PayloadHit? {
    val payloads = s.manufacturerData.values + s.serviceData.values
    val shorts = s.serviceUuids + s.serviceData.keys
    if (payloads.any { it.hasAscii("BWCDEVICE") }) return PayloadHit(Category.PRIORITY, true,
        tr("Sendet Axons Kennung „BWCDEVICE“: eine Axon-Bodycam, auch hinter einer Zufallsadresse", "Sends Axon's \"BWCDEVICE\" tag: an Axon body cam, also behind a random address"))
    if (shorts.any { it in AXON_UUIDS } || (CID_TASER in s.manufacturerData && !idoWatch(s))) return PayloadHit(Category.PRIORITY, true,
        tr("Axon-/TASER-Kennung im Funk: Bodycam, Taser, Holster-Sensor oder Fahrzeug", "Axon / TASER id in the advert: body cam, taser, holster sensor or vehicle"))
    s.serviceData[UUID_REMOTE_ID]?.takeIf { it.size >= 27 && it[0] == 0x0D.toByte() }?.let {
        return PayloadHit(Category.CAMERA, false, tr("Drohne, sendet Remote ID (Pflicht ab 250 g, fast immer mit Kamera)", "Drone sending Remote ID (required from 250 g, nearly always with a camera)"))
    }
    if (s.manufacturerData.keys.any { it in GLASSES_COMPANIES } || s.manufacturerData[CID_META_TECH]?.hasAscii("META_RB_GLASS") == true ||
        s.name?.contains(Regex("ray-?ban", RegexOption.IGNORE_CASE)) == true)
        return PayloadHit(Category.CAMERA, false, tr("Kamera-Brille (Ray-Ban Meta, Spectacles, Vuzix): kann unauffällig filmen", "Camera glasses (Ray-Ban Meta, Spectacles, Vuzix): can film unnoticed"))
    return null
}

/** Cheap IDO smartwatches squat on TASER's company id: they repeat their own address in it and use service 0x0AF0. */
private fun idoWatch(s: Sighting): Boolean {
    val p = s.manufacturerData[CID_TASER] ?: return false
    val own = hexOf(s.address)?.chunked(2)?.map { it.toInt(16).toByte() } ?: return false
    return p.size >= 6 && (0 until 6).all { p[it] == own[it] } && (0x0AF0 in s.serviceUuids || s.name?.let(IDO_NAME::matches) == true)
}

private fun ByteArray.hasAscii(t: String) = String(this, Charsets.ISO_8859_1).contains(t)
