package app.tavernbridge.launcher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import app.tavernbridge.launcher.model.DirectoryAppOption
import app.tavernbridge.launcher.model.rankDirectoryApps
import app.tavernbridge.launcher.termux.TermuxContract

internal class DirectoryAppLauncher(private val context: Context) {
    private fun rootIntent() = Intent(Intent.ACTION_VIEW).apply {
        // Termux does not implement findDocumentPath(): open its real root, not a
        // document deep link that may silently fall back to Downloads.
        setDataAndType(
            DocumentsContract.buildRootUri("${TermuxContract.PACKAGE}.documents", TermuxContract.HOME_PATH),
            DocumentsContract.Root.MIME_TYPE_ITEM,
        )
        // We do not own this provider and must not fabricate URI grants for it.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun options(): List<DirectoryAppOption> {
        val pm = context.packageManager
        val candidates = pm.queryIntentActivities(rootIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                if (!activity.permission.isNullOrBlank() &&
                    context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
                ) return@mapNotNull null
                DirectoryAppOption(
                    packageName = activity.packageName,
                    activityName = activity.name,
                    label = resolved.loadLabel(pm).toString().ifBlank { activity.packageName },
                    hasDocumentAccess = pm.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, activity.packageName) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
        return rankDirectoryApps(candidates)
    }

    fun open(optionId: String, allowUnverified: Boolean): Boolean = runCatching {
        // Re-query: an app may have been removed/disabled or lost permission since
        // the picker opened. Never accept an arbitrary component from the caller.
        val option = options().firstOrNull { it.id == optionId } ?: return false
        if (!option.hasDocumentAccess && !allowUnverified) return false
        context.startActivity(rootIntent().setClassName(option.packageName, option.activityName))
        true
    }.getOrDefault(false)
}
