package dev.lorenzo.s200.cloud

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom

object XiaomiCloud {
    private const val ACCOUNT = "https://account.xiaomi.com"
    private val random = SecureRandom()
    private val agent = buildString {
        repeat(18) { append('a' + random.nextInt(26)) }
        append('-')
        repeat(13) { append('A' + random.nextInt(5)) }
        append(" APP/com.xiaomi.mihome APPV/10.5.201")
    }

    private class Reply(val code: Int, val body: String, val cookies: Map<String, String>, val location: String?)

    private fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 20_000): Reply {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", agent)
            headers.forEach(c::setRequestProperty)
            if (method == "POST") { c.doOutput = true; c.outputStream.close() }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val cookies = c.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
                .map { it.substringBefore(';') }.filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=') }
            return Reply(code, body, cookies, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun query(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    fun requestQrLogin(): QrLogin {
        val params = mapOf(
            "_qrsize" to "480", "qs" to "%3Fsid%3Dxiaomiio%26_json%3Dtrue", "callback" to "https://sts.api.io.mi.com/sts",
            "_hasLogo" to "false", "sid" to "xiaomiio", "serviceParam" to "", "_locale" to "en_GB", "_dc" to System.currentTimeMillis().toString(),
        )
        val r = request("$ACCOUNT/longPolling/loginUrl?${query(params)}")
        if (r.code != 200) throw XiaomiCloudException("Xiaomi sign-in unavailable (HTTP ${r.code})")
        return CloudJson.qrLogin(r.body)
    }

    fun awaitLogin(qr: QrLogin): CloudSession? {
        val r = try { request(qr.pollUrl, readTimeoutMs = 25_000) } catch (_: SocketTimeoutException) { return null }
        if (r.code != 200) return null
        val ticket = CloudJson.loginTicket(r.body)
        return CloudSession(ticket.userId, ticket.ssecurity, serviceToken(ticket.location))
    }

    private fun serviceToken(location: String): String {
        var url = location
        repeat(5) {
            val r = request(url, headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))
            r.cookies["serviceToken"]?.takeIf { it.isNotEmpty() }?.let { return it }
            url = r.location?.let { URI(url).resolve(it).toString() } ?: throw XiaomiCloudException("Xiaomi sent no service token (HTTP ${r.code})")
        }
        throw XiaomiCloudException("Xiaomi sent no service token")
    }

    private fun call(session: CloudSession, region: Region, path: String, data: String): String {
        val url = region.apiBase + path
        val nonce = MiCloudCrypto.nonce(ByteArray(8).also(random::nextBytes), System.currentTimeMillis())
        val signed = MiCloudCrypto.signedNonce(session.ssecurity, nonce)
        val fields = MiCloudCrypto.encryptParams(url, signed, nonce, session.ssecurity, mapOf("data" to data))
        val cookie = mapOf(
            "userId" to session.userId, "yetAnotherServiceToken" to session.serviceToken, "serviceToken" to session.serviceToken,
            "locale" to "en_GB", "timezone" to "GMT+02:00", "is_daylight" to "1", "dst_offset" to "3600000", "channel" to "MI_APP_STORE",
        ).entries.joinToString("; ") { (k, v) -> "$k=$v" }
        val r = request("$url?${query(fields)}", "POST", mapOf(
            "Accept-Encoding" to "identity", "Content-Type" to "application/x-www-form-urlencoded", "Cookie" to cookie,
            "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2", "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
        ))
        if (r.code != 200) throw XiaomiCloudException("HTTP ${r.code}")
        return MiCloudCrypto.decrypt(signed, r.body)
    }

    fun scales(session: CloudSession, region: Region): List<CloudScale> {
        val homes = CloudJson.homes(
            call(session, region, "/v2/homeroom/gethome", """{"fg": true, "fetch_share": true, "fetch_share_dev": true, "limit": 300, "app_ver": 7}"""),
            call(session, region, "/v2/user/get_device_cnt", """{ "fetch_own": true, "fetch_share": true}"""),
            session.userId,
        )
        val devices = homes.flatMap { h ->
            CloudJson.scaleDevices(call(session, region, "/v2/home/home_device_list",
                """{"home_owner": ${h.owner},"home_id": ${h.id},  "limit": 200,  "get_split_device": true, "support_smart_home": true}"""))
        }.distinctBy { it.did }
        return devices.mapNotNull { d ->
            val key = CloudJson.beaconKey(call(session, region, "/v2/device/blt_get_beaconkey", """{"did":"${d.did}","pdid":1}""")) ?: return@mapNotNull null
            CloudScale(region, d.name, d.mac, key, d.token)
        }
    }
}
