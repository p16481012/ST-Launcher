package app.tavernbridge.launcher.model

import java.util.Locale

internal fun storageDiagnosticItem(id: String, title: String, rawBytes: String): DiagnosticItem {
    val bytes = rawBytes.toLongOrNull()?.takeIf { it >= 0 } ?: return DiagnosticItem(id, title, "미확인",
        "여유 공간을 측정하지 못했어요. 공간이 부족하다는 뜻은 아니에요.", DiagnosticStatus.WARNING,
        "저장 공간을 확인한 뒤 다시 진단해 주세요.")
    val status = when {
        bytes >= 2L * 1024 * 1024 * 1024 -> DiagnosticStatus.OK
        bytes >= 512L * 1024 * 1024 -> DiagnosticStatus.WARNING
        else -> DiagnosticStatus.ERROR
    }
    return DiagnosticItem(id, title, formatBytes(bytes), "사용 가능한 여유 공간", status)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
    else -> "$bytes B"
}
