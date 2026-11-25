package app.tavernbridge.launcher.model

/** Lower bound only: process death can follow a successful unlink but precede its acknowledgement. */
internal data class SafetyDeleteReceipt(val total: Int, val confirmed: Int = 0) {
    init { require(total in 2..1000 && confirmed in 0..total) }
    fun acknowledge() = copy(confirmed = confirmed + 1)
    val complete get() = confirmed == total
    val notice get() = if (complete) "${total}개 전부 삭제된 것을 확인했어요."
        else "${total}개 중 최소 ${confirmed}개 삭제를 확인했어요. 나머지는 자동 삭제하지 않아요. 안전 사본·휴지통 목록에서 확인해 주세요."
    fun summary(now: Long, date: String) = OperationResultSummary("safety-delete-batch",
        if (complete) "선택한 보관 항목 삭제 완료" else "일괄 삭제 결과 확인", notice, complete,
        if (complete) "" else "SAFETY_DELETE_INTERRUPTED", date, now, 0)
    fun finish(outcome: OperationResultSummary): OperationResultSummary = when {
        outcome.succeeded && confirmed != total -> summary(outcome.completedAtMillis, outcome.completedAt)
        else -> outcome.copy(operation = "safety-delete-batch", retryAction = RetryAction.NONE,
            detail = if (outcome.succeeded) outcome.detail else "$notice\n${outcome.detail}")
    }
}

/** A Termux success receipt covers one item, never all items in an interrupted selection. */
internal fun OperationResultSummary?.isUnfinishedDeleteStep(operation: String?, startedAt: Long): Boolean =
    this?.operation == "safety-delete-batch" && !succeeded && operation == "safety-delete" &&
        (startedAt <= 0 || startedAt <= completedAtMillis)
