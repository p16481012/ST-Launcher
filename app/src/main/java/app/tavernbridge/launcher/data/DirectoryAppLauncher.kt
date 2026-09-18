package app.tavernbridge.launcher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
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

    fun openRecommended(): Boolean = runCatching {
        val pm = context.packageManager
        val intent = rootIntent()
        val handler = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { resolved ->
                val activity = resolved.activityInfo ?: return@firstOrNull false
                if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return@firstOrNull false
                if (!activity.permission.isNullOrBlank() &&
                    context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
                ) return@firstOrNull false
                pm.checkPermission(Manifest.permission.MANAGE_DOCUMENTS, activity.packageName) ==
                    PackageManager.PERMISSION_GRANTED
            } ?: return false
        context.startActivity(intent.setClassName(handler.activityInfo.packageName, handler.activityInfo.name))
        true
    }.getOrDefault(false)
}
