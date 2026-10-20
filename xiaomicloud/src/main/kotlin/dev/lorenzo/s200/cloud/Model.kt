package dev.lorenzo.s200.cloud

import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.MiToken
import dev.lorenzo.s200.ScaleMac
import org.json.JSONObject

enum class Region(val code: String) {
    CN("cn"), DE("de"), US("us"), RU("ru"), TW("tw"), SG("sg"), IN("in"), I2("i2");

    val apiBase: String get() = "https://" + (if (this == CN) "" else "$code.") + "api.io.mi.com/app"
}

data class QrLogin(val loginUrl: String, val pollUrl: String, val timeoutSec: Int)

class CloudSession internal constructor(internal val userId: String, internal val ssecurity: String, internal val serviceToken: String)

data class CloudScale(val region: Region, val name: String, val mac: ScaleMac, val bindKey: BindKey, val token: MiToken?)

sealed interface ScaleSearch {
    data class Found(val scales: List<CloudScale>) : ScaleSearch
    data object NoneOnAccount : ScaleSearch
    data class Unreachable(val failed: Map<Region, String>) : ScaleSearch

    companion object {
        fun of(results: Map<Region, Result<List<CloudScale>>>): ScaleSearch {
            val scales = Region.entries.flatMap { results[it]?.getOrNull().orEmpty() }.distinctBy { it.mac }
            val failed = Region.entries.mapNotNull { r -> results[r]?.exceptionOrNull()?.let { r to (it.message ?: it.javaClass.simpleName) } }.toMap()
            return when {
                scales.isNotEmpty() -> Found(scales)
                failed.isNotEmpty() -> Unreachable(failed)
                else -> NoneOnAccount
            }
        }
    }
}

class XiaomiCloudException(message: String) : Exception(message)

internal data class LoginTicket(val userId: String, val ssecurity: String, val location: String)
internal data class Home(val id: String, val owner: String)
internal data class CloudDevice(val did: String, val name: String, val mac: ScaleMac, val token: MiToken?)

internal object CloudJson {
    const val SCALE_MODEL = "xiaomi.scales.ms113"

    private fun obj(text: String) = runCatching { JSONObject(text.removePrefix("&&&START&&&")) }
        .getOrElse { throw XiaomiCloudException("Xiaomi sent an unreadable reply") }

    fun qrLogin(text: String): QrLogin = obj(text).run {
        val url = optString("loginUrl"); val lp = optString("lp")
        if (url.isEmpty() || lp.isEmpty()) throw XiaomiCloudException("Xiaomi refused to start a sign-in: ${optString("desc")}")
        QrLogin(url, lp, optInt("timeout", 300))
    }

    fun loginTicket(text: String): LoginTicket = obj(text).run {
        val ssecurity = optString("ssecurity"); val location = optString("location"); val userId = opt("userId")?.toString().orEmpty()
        if (ssecurity.length <= 4 || location.isEmpty() || userId.isEmpty()) throw XiaomiCloudException("Xiaomi sign-in did not complete: ${optString("desc")}")
        LoginTicket(userId, ssecurity, location)
    }

    fun homes(gethome: String, devCnt: String, userId: String): List<Home> {
        val own = obj(gethome).optJSONObject("result")?.optJSONArray("homelist")
        val shared = obj(devCnt).optJSONObject("result")?.optJSONObject("share")?.optJSONArray("share_family")
        val ownHomes = (0 until (own?.length() ?: 0)).map { Home(own!!.getJSONObject(it).get("id").toString(), userId) }
        val sharedHomes = (0 until (shared?.length() ?: 0)).map { i ->
            shared!!.getJSONObject(i).let { Home(it.get("home_id").toString(), it.get("home_owner").toString()) }
        }
        return (ownHomes + sharedHomes).distinct()
    }

    fun scaleDevices(text: String): List<CloudDevice> {
        val list = obj(text).optJSONObject("result")?.optJSONArray("device_info") ?: return emptyList()
        return (0 until list.length()).map(list::getJSONObject).filter { it.optString("model") == SCALE_MODEL }.mapNotNull { d ->
            val mac = ScaleMac.parse(d.optString("mac")) ?: return@mapNotNull null
            CloudDevice(d.optString("did"), d.optString("name", "Xiaomi scale"), mac, MiToken.parse(d.optString("token")))
        }
    }

    fun beaconKey(text: String): BindKey? = BindKey.parse(obj(text).optJSONObject("result")?.optString("beaconkey").orEmpty())
}
