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

enum class UnusedApp(val label: String, val exempt: Boolean, val canManage: Boolean) {
    Off("off", exempt = true, canManage = true),
    Unavailable("not on this device", exempt = true, canManage = false),
    RevokesPermissionsBackport("on (permission revocation only)", exempt = false, canManage = true),
    RevokesPermissions("on (permissions removed if unused)", exempt = false, canManage = true),
    Hibernates("on (app paused if unused)", exempt = false, canManage = true),
    Unknown("unknown", exempt = false, canManage = false);

    companion object {
        fun of(status: Int) = when (status) {
            DISABLED -> Off
            FEATURE_NOT_AVAILABLE -> Unavailable
            API_30_BACKPORT -> RevokesPermissionsBackport
            API_30 -> RevokesPermissions
            API_31 -> Hibernates
            else -> Unknown
        }

        suspend fun query(ctx: Context): UnusedApp =
            of(runCatching { PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).await() }.getOrDefault(ERROR))

        fun manageIntent(ctx: Context): Intent = IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName)
    }
}
