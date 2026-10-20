package dev.lorenzo.s200.bridge

import android.content.Context
import android.content.Intent
import androidx.concurrent.futures.await
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants.API_30
import androidx.core.content.UnusedAppRestrictionsConstants.API_30_BACKPORT
import androidx.core.content.UnusedAppRestrictionsConstants.API_31
import androidx.core.content.UnusedAppRestrictionsConstants.DISABLED
import androidx.core.content.UnusedAppRestrictionsConstants.ERROR
import androidx.core.content.UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE

object UnusedApp {
    suspend fun status(ctx: Context): Int = runCatching { PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).await() }.getOrDefault(ERROR)

    fun manageIntent(ctx: Context): Intent = IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName)

    fun exempt(status: Int) = status == DISABLED || status == FEATURE_NOT_AVAILABLE

    fun canManage(status: Int) = status == API_30_BACKPORT || status == API_30 || status == API_31 || status == DISABLED

    fun label(status: Int) = when (status) {
        DISABLED -> "off"
        FEATURE_NOT_AVAILABLE -> "not on this device"
        API_30_BACKPORT -> "on (permission revocation only)"
        API_30 -> "on (permissions removed if unused)"
        API_31 -> "on (app paused if unused)"
        else -> "unknown"
    }
}
