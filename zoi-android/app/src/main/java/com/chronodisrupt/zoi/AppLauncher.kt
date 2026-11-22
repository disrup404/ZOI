package com.chronodisrupt.zoi

import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

internal data class LaunchableApp(
    val label: String,
    val packageName: String
)

internal sealed class AppLaunchLookup {
    data class Found(val app: LaunchableApp) : AppLaunchLookup()
    data class Ambiguous(val apps: List<LaunchableApp>) : AppLaunchLookup()
    data object NotFound : AppLaunchLookup()
}

/**
 * Resolves only apps that expose a normal launcher activity.
 * Android package visibility is limited by the matching LAUNCHER query
 * declared in the manifest; Jane does not request QUERY_ALL_PACKAGES.
 */
internal fun findLaunchableApp(
    packageManager: PackageManager,
    requestedName: String
): AppLaunchLookup {
    val target = normalizeAppName(requestedName)
        .removeSuffix(" app")
        .trim()
    if (target.isBlank()) return AppLaunchLookup.NotFound

    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }

    val apps = resolved
        .mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (activity.packageName == "com.chronodisrupt.zoi") return@mapNotNull null
            val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            LaunchableApp(label, activity.packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

    val exact = apps.filter { normalizeAppName(it.label) == target }
    if (exact.size == 1) return AppLaunchLookup.Found(exact.first())
    if (exact.size > 1) return AppLaunchLookup.Ambiguous(exact)

    // Support natural variants such as "Google Maps" when the launcher
    // label is simply "Maps", while refusing to guess between close matches.
    val partial = apps.filter { app ->
        val label = normalizeAppName(app.label)
        label.startsWith(target) ||
            (label.length >= 4 && target.endsWith(" $label"))
    }
    return when (partial.size) {
        0 -> AppLaunchLookup.NotFound
        1 -> AppLaunchLookup.Found(partial.first())
        else -> AppLaunchLookup.Ambiguous(partial.take(5))
    }
}

private fun normalizeAppName(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
