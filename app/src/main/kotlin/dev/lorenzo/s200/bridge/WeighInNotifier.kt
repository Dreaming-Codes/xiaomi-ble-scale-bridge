package dev.lorenzo.s200.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

fun formatKg(kg: Double, locale: Locale = Locale.getDefault()): String =
    DecimalFormat("0.0#", DecimalFormatSymbols(locale)).format(kg) + " kg"

fun formatDelta(kg: Double, prevKg: Double, locale: Locale = Locale.getDefault()): String {
    val d = (kg - prevKg).times(100).roundToLong() / 100.0
    val sign = when { d > 0 -> "+"; d < 0 -> "−"; else -> "±" }
    return sign + formatKg(abs(d), locale)
}

fun presenceTestTitle(kg: Double, locale: Locale = Locale.getDefault()) = "Presence test: " + formatKg(kg, locale)

fun presenceTestDetail(via: String, appearedMs: Long?, receivedMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val f = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun t(ms: Long) = f.format(Instant.ofEpochMilli(ms).atZone(zone))
    return "via $via, appeared ${appearedMs?.let(::t) ?: "never"}, received ${t(receivedMs)}"
}

object WeighInNotifier {
    private const val CHANNEL = "weigh-ins"

    fun postPresenceTest(ctx: Context, r: Reading, detail: String) {
        val nm = channel(ctx) ?: return
        nm.notify(r.epochSec.toInt(), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_scale)
            .setContentTitle(presenceTestTitle(r.kg))
            .setContentText("($detail)")
            .setStyle(Notification.BigTextStyle().bigText("($detail)"))
            .setContentIntent(open(ctx)).setAutoCancel(true)
            .build())
    }

    private fun channel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weigh-ins", NotificationManager.IMPORTANCE_DEFAULT))
        return nm.takeIf { it.areNotificationsEnabled() }
    }

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun post(ctx: Context, r: Reading, prev: Reading?) {
        val nm = channel(ctx) ?: return
        val open = open(ctx)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochSecond(r.epochSec).atZone(ZoneId.systemDefault()))
        val text = listOfNotNull(time, prev?.let { formatDelta(r.kg, it.kg) }).joinToString(" · ")
        nm.notify(r.epochSec.toInt(), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_scale)
            .setContentTitle(formatKg(r.kg))
            .setContentText(text)
            .setWhen(r.epochSec * 1000).setShowWhen(true)
            .setContentIntent(open).setAutoCancel(true)
            .build())
    }
}
