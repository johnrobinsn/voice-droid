package com.voicedroid.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.voicedroid.accessibility.AxOps

/** Resolves an installed app by display label and launches or closes it. */
object Launcher {

    private data class App(val label: String, val pkg: String)

    fun launch(ctx: Context, query: String): Result {
        val pick = resolve(ctx, query) ?: return Result(false, "no app found matching '$query'")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pick.pkg)
            ?: return Result(false, "no launch intent for ${pick.pkg}")
        // NEW_TASK makes it work from a non-Activity context; RESET_TASK_IF_NEEDED
        // brings an existing instance to the foreground instead of starting a new one.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.startActivity(intent)
        return Result(true, label = pick.label, pkg = pick.pkg)
    }

    /**
     * Close an app: background it (via Home if it's foreground) then ask the system
     * to kill its background processes. On Samsung/aggressive OEMs the OS may have
     * already evicted the app; in that case this is a no-op but still returns ok.
     */
    fun close(ctx: Context, query: String): Result {
        val pick = resolve(ctx, query) ?: return Result(false, "no app found matching '$query'")
        if (pick.pkg == ctx.packageName) {
            return Result(false, "refusing to close Voice Droid itself")
        }
        if (AxOps.activePackage() == pick.pkg) {
            // Push to background first so killBackgroundProcesses can actually take effect.
            AxOps.home()
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pick.pkg)
        return Result(true, label = pick.label, pkg = pick.pkg)
    }

    private fun resolve(ctx: Context, query: String): App? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = listLaunchable(ctx)
        val exact = apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
        val starts = apps.firstOrNull { it.label.lowercase().startsWith(q) }
        val contains = apps.firstOrNull { it.label.lowercase().contains(q) }
        return exact ?: starts ?: contains
    }

    private fun listLaunchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        return resolves.map { ri ->
            App(
                label = ri.loadLabel(pm)?.toString().orEmpty(),
                pkg = ri.activityInfo.packageName,
            )
        }
    }

    data class Result(
        val ok: Boolean,
        val error: String? = null,
        val label: String? = null,
        val pkg: String? = null,
    )
}
