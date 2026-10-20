package dev.lorenzo.s200.bridge

import android.content.Context
import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.MiToken
import dev.lorenzo.s200.ScaleMac

data class Reading(val epochSec: Long, val kg: Double, val profile: Int) {
    fun encode() = "$epochSec|$kg|$profile"

    companion object {
        fun decode(s: String): Reading? = s.split("|").takeIf { it.size == 3 }
            ?.let { (a, b, c) -> runCatching { Reading(a.toLong(), b.toDouble(), c.toInt()) }.getOrNull() }
    }
}

const val PRESENCE_APPEARED = "BLE appeared"

data class PresenceEntry(val atMs: Long, val what: String) {
    fun encode() = "$atMs|${what.replace(Regex("[|;]"), " ")}"

    companion object {
        fun decode(s: String): PresenceEntry? = s.split("|", limit = 2).takeIf { it.size == 2 }
            ?.let { (a, b) -> a.toLongOrNull()?.let { PresenceEntry(it, b) } }
    }
}

class Config(ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var keyHex: String get() = p.getString("key", "")!!; set(v) = p.edit().putString("key", v).apply()
    var macText: String get() = p.getString("mac", "")!!; set(v) = p.edit().putString("mac", v).apply()
    var profileSlot: Int get() = p.getInt("profile", -1); set(v) = p.edit().putInt("profile", v).apply()
    var associationId: Int get() = p.getInt("assoc", -1); set(v) = p.edit().putInt("assoc", v).apply()
    var lastTs: Long get() = p.getLong("lastTs", 0); set(v) = p.edit().putLong("lastTs", v).apply()
    var lastStatus: String get() = p.getString("status", "-")!!; set(v) = p.edit().putString("status", v).apply()
    var tokenHex: String get() = p.getString("token", "")!!; set(v) = p.edit().putString("token", v).apply()
    var ackStatus: String get() = p.getString("ackStatus", "-")!!; set(v) = p.edit().putString("ackStatus", v).apply()

    val key: BindKey? get() = BindKey.parse(keyHex)
    val mac: ScaleMac? get() = ScaleMac.parse(macText)
    val token: MiToken? get() = MiToken.parse(tokenHex)
    val profile: Int? get() = profileSlot.takeIf { it >= 0 }
    val configured get() = key != null && mac != null

    var lastAppearedMs: Long get() = p.getLong("appearedAt", 0); set(v) = p.edit().putLong("appearedAt", v).apply()
    val presenceLog: List<PresenceEntry> get() = p.getString("presenceLog", "")!!.split(";").mapNotNull(PresenceEntry::decode)
    fun recordPresence(what: String, atMs: Long = System.currentTimeMillis()) {
        if (what == PRESENCE_APPEARED) lastAppearedMs = atMs
        p.edit().putString("presenceLog", (listOf(PresenceEntry(atMs, what)) + presenceLog).take(40).joinToString(";") { it.encode() }).apply()
    }

    val history: List<Reading> get() = p.getString("hist", "")!!.split(";").mapNotNull(Reading::decode)
    fun addReading(r: Reading) = p.edit().putString("hist", (listOf(r) + history).sortedByDescending { it.epochSec }.take(20).joinToString(";") { it.encode() }).apply()
}
