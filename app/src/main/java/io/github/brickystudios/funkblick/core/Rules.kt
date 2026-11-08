package io.github.brickystudios.funkblick.core

enum class RuleKind { WATCH, MUTE }

enum class RuleField { ADDRESS, PREFIX, NAME_EXACT, NAME_CONTAINS, VENDOR }

/** A user's own watch or mute rule. Stored on the phone only (scan/Store.kt). */
data class Rule(
    val kind: RuleKind,
    val field: RuleField,
    val value: String,
    val note: String = "",
    val id: Long = System.currentTimeMillis(),
)

fun Rule.isValid(): Boolean = when (field) {
    RuleField.ADDRESS -> hexOf(value) != null
    RuleField.PREFIX -> value.filter { it.isLetterOrDigit() }.let { p -> p.length in 2..12 && p.all { it.uppercaseChar() in "0123456789ABCDEF" } }
    else -> value.isNotBlank()
}

fun Rule.matches(s: Sighting, vendor: String?): Boolean {
    if (!isValid()) return false
    val v = value.trim()
    return when (field) {
        RuleField.ADDRESS -> hexOf(s.address) == hexOf(v)
        RuleField.PREFIX -> hexOf(s.address)?.startsWith(v.filter { it.isLetterOrDigit() }.uppercase()) == true
        RuleField.NAME_EXACT -> s.name?.trim().equals(v, ignoreCase = true)
        RuleField.NAME_CONTAINS -> s.name?.contains(v, ignoreCase = true) == true
        RuleField.VENDOR -> vendor?.contains(v, ignoreCase = true) == true
    }
}

fun RuleField.label(): String = when (this) {
    RuleField.ADDRESS -> tr("Adresse", "Address")
    RuleField.PREFIX -> tr("Adress-Präfix (Hersteller)", "Address prefix (maker)")
    RuleField.NAME_EXACT -> tr("Name genau", "Exact name")
    RuleField.NAME_CONTAINS -> tr("Name enthält", "Name contains")
    RuleField.VENDOR -> tr("Hersteller enthält", "Maker contains")
}
