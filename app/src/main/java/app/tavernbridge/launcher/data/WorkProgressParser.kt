package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.security.redactSensitiveText
import java.util.Base64

object WorkProgressParser {
    private val ansiControlSequence = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val ansiOperatingSystemCommand = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    /** The same sanitized text reaches the short preview, expanded view and clipboard. */
    private fun safeOperationLog(text: String): String {
        val clean = text.replace(ansiOperatingSystemCommand, "")
            .replace(ansiControlSequence, "")
            .filter { char ->
                (char == '\n' || char == '\t' || !char.isISOControl()) &&
                    Character.getType(char) != Character.FORMAT.toInt()
            }
        // Redact before the length limit so cutting off a secret's field name
        // cannot expose its value at the start of the retained tail.
        return redactSensitiveText(clean, maxLength = null).takeLast(40_000).trim()
    }

    fun parse(output: String): WorkProgress? {
        val sections = output.split("\n__ST_LAUNCHER_LOG__\n", limit = 2)
        val metadata = sections.first().split("\n__ST_LAUNCHER_SERVER_LOG__\n", limit = 2)
        val values = metadata.first().lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1).trimEnd('\r')
        }.toMap()
        if (values["operation"].isNullOrBlank() && values["phase"].isNullOrBlank()) return null
        fun nonNegative(key: String): Long = values[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        fun timestamp(key: String): Long = nonNegative(key).takeIf { it <= Long.MAX_VALUE / 1_000L }
            ?.times(1_000L) ?: 0L
        val mode = values["progress_mode"].takeIf { it in setOf("bytes", "files", "indeterminate", "complete") }
            ?: "indeterminate"
        val operation = values["operation"] ?: "idle"
        val phaseStartedAt = timestamp("phase_started_at")
        val explicitStart = timestamp("operation_started_at").takeIf {
            it > 0L && (phaseStartedAt == 0L || it <= phaseStartedAt)
        } ?: 0L
        val monitorStart = timestamp("monitor_started_at")
        val monitorMatches = operation != "idle" && values["monitor_operation"] == operation &&
            (explicitStart == 0L || monitorStart == 0L || explicitStart == monitorStart)
        val operationStartedAt = explicitStart.takeIf { it > 0L } ?: monitorStart.takeIf {
            monitorMatches && it > 0L && phaseStartedAt >= it &&
                timestamp("monitor_heartbeat_at") >= phaseStartedAt
        } ?: 0L
        val monitorHeartbeat = if (monitorMatches) timestamp("monitor_heartbeat_at") else 0L
        val observedActivity = if (monitorMatches && phaseStartedAt > 0L) {
            timestamp("observed_activity_at").takeIf { it >= phaseStartedAt } ?: 0L
        } else 0L
        val item = runCatching {
            String(Base64.getDecoder().decode(values["current_item_b64"].orEmpty()), Charsets.UTF_8)
        }.getOrDefault("").filterNot { it.isISOControl() }.take(1_024)
        // Server output is relevant only after a new server session has been
        // opened by this exact command, never during its earlier backup/checks.
        val currentServerLog = metadata.getOrNull(1).orEmpty().takeIf {
            operation in setOf("start", "restart", "update", "switch-branch") &&
                values["server_operation"] == operation && operationStartedAt > 0L &&
                timestamp("server_operation_started_at") == operationStartedAt
        }.orEmpty()
        val waitingDetail = if ((values["status"] ?: "running") == "running" && monitorMatches &&
            operationStartedAt > 0L && monitorStart == operationStartedAt &&
            monitorHeartbeat >= phaseStartedAt && nonNegative("monitor_wait_seconds") >= 10L
        ) runCatching {
            String(Base64.getDecoder().decode(values["monitor_wait_detail_b64"].orEmpty()), Charsets.UTF_8)
                .filterNot { it.isISOControl() }.take(512)
        }.getOrDefault("") else ""
        val operationLog = listOf(
            sections.getOrElse(1) { "" },
            waitingDetail.takeIf { it.isNotBlank() }?.let { "[대기 확인] ${values["phase"].orEmpty()} · $it" }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString("\n")
        val log = if (currentServerLog.isBlank()) operationLog else
            "===== SillyTavern 서버 출력 =====\n$currentServerLog\n===== 작업 처리 내역 =====\n$operationLog"
        return WorkProgress(
            percent = values["percent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0,
            phase = values["phase"].orEmpty(),
            detail = values["detail"].orEmpty(),
            status = values["status"] ?: "running",
            operation = operation,
            errorCode = values["error_code"].orEmpty(),
            finishedAtMillis = timestamp("finished_at"),
            logText = safeOperationLog(log),
            progressMode = mode,
            completedBytes = nonNegative("completed_bytes"),
            totalBytes = nonNegative("total_bytes"),
            completedFiles = nonNegative("completed_files"),
            totalFiles = nonNegative("total_files"),
            currentItem = item,
            heartbeatAtMillis = maxOf(timestamp("heartbeat_at"), monitorHeartbeat),
            activityAtMillis = maxOf(timestamp("activity_at"), observedActivity),
            phaseStartedAtMillis = phaseStartedAt,
            operationStartedAtMillis = operationStartedAt,
            backupRequestId = values["backup_request_id"].orEmpty(),
            operationId = values["operation_id"].orEmpty(),
            operationRequestId = values["operation_request_id"].orEmpty(),
            cancellationRequested = values["cancellation_requested"] == "1",
            cancellationMode = values["cancellation_mode"].takeIf { it in setOf("immediate", "deferred", "none") } ?: "none",
        )
    }
}
