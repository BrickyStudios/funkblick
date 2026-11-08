package io.github.brickystudios.funkblick.core

// What a manufacturer or a network name says about the equipment. A match is a hint about the hardware,
// never about who runs it: a Cradlepoint router sits in buses, ambulances, delivery vans and police cars alike.

class Signature(val category: Category, val pattern: Regex, val why: () -> String)

private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

/** Matched against the shortened IEEE company name. */
val VENDOR_SIGNATURES = listOf(
    Signature(Category.PRIORITY, re("^(axon enterprise|taser|motorola solutions|watchguard video|digital ally|coban|kustom signals|zepcam|edesix|reveal media|sepura|getac)")) {
        tr("Hersteller von Bodycams, Funk- oder Fahrzeugtechnik für Polizei und Rettungsdienst. Sagt nichts darüber, wer das Gerät betreibt",
            "Maker of body cams, radios or vehicle gear for police and EMS. Says nothing about who operates this one")
    },
    Signature(Category.FLEET, re("^(cradlepoint|sierra wireless|inseego|novatel|teltonika|peplink|pismo labs|digi international|robustel|icomera|nomad digital)")) {
        tr("Hersteller baut Mobilfunkrouter für Fahrzeuge (Busse, Bahnen, Einsatz- und Lieferwagen)",
            "Maker of cellular routers for vehicles (buses, trains, service and delivery vans)")
    },
    Signature(Category.CAR, re("^(peiker|panasonic automotive|harman|alps ?alpine|magneti marelli|continental automotive|bosch car multimedia|visteon|aptiv|denso|preh|lg innotek)")) {
        tr("Hersteller von Auto-Infotainment und Telematik", "Maker of car infotainment and telematics")
    },
    Signature(Category.CAMERA, re("^(hangzhou hikvision|zhejiang dahua|hangzhou ezviz|axis communications|wyze|blink|arlo$|ring$|zhejiang uniview|vivotek|mobotix|reolink|hanwha vision|bosch security)")) {
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
    Signature(Category.PRIORITY, re("^axon|axon body|watchguard|bodycam|body cam")) {
        tr("Name typisch für eine Bodycam", "Name typical of a body cam")
    },
    Signature(Category.FLEET, re("^(rut[x0-9]|teltonika|cradlepoint|ibr\\d)")) {
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
    Signature(Category.CAMERA, re("^(ipc|ipcam|hik-|ezviz_|tapo_cam|yi_|v380|lookcam|hdlivecam|ipc365|wificam|cam_|reolink|wyze|ring setup|70mai|viofo|blackvue)|camera|_cam\\b")) {
        tr("Name typisch für eine Kamera mit eigenem WLAN", "Name typical of a camera running its own Wi-Fi")
    },
)

fun matchVendor(vendor: String?): Signature? = vendor?.let { v -> VENDOR_SIGNATURES.firstOrNull { it.pattern.containsMatchIn(v) } }

fun matchName(name: String?): Signature? = name?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> NAME_SIGNATURES.firstOrNull { it.pattern.containsMatchIn(n) } }
