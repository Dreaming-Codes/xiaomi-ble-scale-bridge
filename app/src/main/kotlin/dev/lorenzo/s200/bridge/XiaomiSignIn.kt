package dev.lorenzo.s200.bridge

import dev.lorenzo.s200.cloud.CloudScale
import dev.lorenzo.s200.cloud.CloudSession
import dev.lorenzo.s200.cloud.Region
import dev.lorenzo.s200.cloud.ScaleSearch
import dev.lorenzo.s200.cloud.XiaomiCloud
import dev.lorenzo.s200.cloud.XiaomiCloudException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface XiaomiLogin {
    data object Idle : XiaomiLogin
    data class AwaitingBrowser(val loginUrl: String) : XiaomiLogin
    data object Fetching : XiaomiLogin
    data class Choose(val scales: List<CloudScale>) : XiaomiLogin
    data class Picked(val scale: CloudScale) : XiaomiLogin
    data class Failed(val reason: String) : XiaomiLogin
}

internal fun ScaleSearch.toLogin(): XiaomiLogin = when (this) {
    is ScaleSearch.Found -> scales.singleOrNull()?.let(XiaomiLogin::Picked) ?: XiaomiLogin.Choose(scales)
    ScaleSearch.NoneOnAccount -> XiaomiLogin.Failed("No supported Xiaomi scale on this account in any region. Add it in Xiaomi Home, or enter the values by hand.")
    is ScaleSearch.Unreachable -> XiaomiLogin.Failed(
        "No scale found, and these regions didn't answer: " + failed.entries.joinToString { (r, why) -> "${r.code} ($why)" } + ". Try again, or enter the values by hand.",
    )
}

internal suspend fun xiaomiSignIn(emit: (XiaomiLogin) -> Unit): XiaomiLogin = withContext(Dispatchers.IO) {
    try {
        val qr = XiaomiCloud.requestQrLogin()
        withContext(Dispatchers.Main) { emit(XiaomiLogin.AwaitingBrowser(qr.loginUrl)) }
        val deadline = System.currentTimeMillis() + qr.timeoutSec * 1000L
        var session: CloudSession? = null
        while (session == null) {
            ensureActive()
            if (System.currentTimeMillis() > deadline) throw XiaomiCloudException("The sign-in link expired. Try again.")
            session = XiaomiCloud.awaitLogin(qr)
        }
        withContext(Dispatchers.Main) { emit(XiaomiLogin.Fetching) }
        coroutineScope {
            Region.entries.map { r -> async { r to runCatching { XiaomiCloud.scales(session, r) } } }.awaitAll().toMap()
        }.let(ScaleSearch::of).toLogin()
    } catch (e: XiaomiCloudException) {
        XiaomiLogin.Failed(e.message.orEmpty())
    } catch (e: java.io.IOException) {
        XiaomiLogin.Failed("Can't reach Xiaomi: ${e.message}")
    }
}
