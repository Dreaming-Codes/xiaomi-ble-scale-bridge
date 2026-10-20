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

object WeighInNotifier {
    private const val CHANNEL = "weigh-ins"

    fun post(ctx: Context, r: Reading, prev: Reading?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weigh-ins", NotificationManager.IMPORTANCE_DEFAULT))
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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
