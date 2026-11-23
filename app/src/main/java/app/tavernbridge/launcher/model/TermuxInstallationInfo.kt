package app.tavernbridge.launcher.model

/** Installer metadata is a hint, not proof of the APK's publisher or signing identity. */
enum class TermuxInstallerSource(val label: String) {
    F_DROID("F-Droid 설치 관리자"),
    GOOGLE_PLAY("Google Play 설치 관리자"),
    UNKNOWN("직접 설치 또는 확인 불가"),
}

enum class TermuxVersionAssessment {
    CALLBACK_TOO_OLD,
    SECURITY_UPDATE_RECOMMENDED,
    MEETS_DOCUMENTED_MINIMUM,
    UNRECOGNIZED,
}

fun classifyTermuxInstaller(installerPackageName: String?): TermuxInstallerSource =
    when (installerPackageName?.trim()) {
        "org.fdroid.fdroid" -> TermuxInstallerSource.F_DROID
        "com.android.vending" -> TermuxInstallerSource.GOOGLE_PLAY
        else -> TermuxInstallerSource.UNKNOWN
    }

/** Only assess recognizable upstream-style versions; forks must not be rejected by this hint. */
fun assessTermuxVersion(versionName: String?): TermuxVersionAssessment {
    val match = Regex("^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?$")
        .matchEntire(versionName?.trim().orEmpty()) ?: return TermuxVersionAssessment.UNRECOGNIZED
    val major = match.groupValues[1].toLongOrNull() ?: return TermuxVersionAssessment.UNRECOGNIZED
    val minor = match.groupValues[2].toLongOrNull() ?: return TermuxVersionAssessment.UNRECOGNIZED
    if (major == 0L && minor < 109L) return TermuxVersionAssessment.CALLBACK_TOO_OLD
    if (major == 0L && minor < 118L) return TermuxVersionAssessment.SECURITY_UPDATE_RECOMMENDED
    return TermuxVersionAssessment.MEETS_DOCUMENTED_MINIMUM
}

data class TermuxInstallationInfo(
    // null means Android did not allow us to determine package presence, not "not installed".
    val installed: Boolean? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val installerPackageName: String? = null,
    val signingCertificateSha256: List<String> = emptyList(),
    val inspectionIssue: String = "",
) {
    val installerSource: TermuxInstallerSource get() = classifyTermuxInstaller(installerPackageName)
    val versionAssessment: TermuxVersionAssessment get() = assessTermuxVersion(versionName)

    /** Display-safe diagnostics: deliberately excludes package IDs, build codes and certificate hashes. */
    fun diagnosticItems(): List<DiagnosticItem> {
        if (installed != true) return listOf(DiagnosticItem(
            id = "termux_installation",
            title = "Termux 설치 정보",
            value = if (installed == false) "설치되지 않음" else "확인할 수 없음",
            detail = inspectionIssue,
            status = if (installed == false) DiagnosticStatus.ERROR else DiagnosticStatus.INFO,
            recommendation = if (installed == false) "Termux를 설치한 뒤 다시 확인해 주세요."
                else "정보 조회 실패만으로 기존 설치가 사라졌다고 판단하지 않습니다. 연결 상태를 함께 확인해 주세요.",
        ))
        val versionNotice = when (versionAssessment) {
            TermuxVersionAssessment.CALLBACK_TOO_OLD ->
                "표시 버전이 공식 명령 결과 수신 기준인 0.109보다 오래되었습니다. 같은 배포 경로의 업데이트를 확인해 주세요."
            TermuxVersionAssessment.SECURITY_UPDATE_RECOMMENDED ->
                "Termux 공식 안내는 보안 수정을 포함한 0.118.0 이상을 권장합니다. 같은 배포 경로의 업데이트를 확인해 주세요."
            TermuxVersionAssessment.MEETS_DOCUMENTED_MINIMUM ->
                "표시 버전은 공식 명령 결과 수신의 최소 버전 조건을 충족합니다. 실제 연결 가능 여부는 연결 검사로 확인합니다."
            TermuxVersionAssessment.UNRECOGNIZED ->
                "버전 표기만으로 호환성을 판단할 수 없습니다. 정상 연결된다면 그대로 사용할 수 있습니다."
        }
        val sourceNotice = when (installerSource) {
            TermuxInstallerSource.GOOGLE_PLAY ->
                "Google Play 배포판은 F-Droid·GitHub 배포판과 기능이 다를 수 있습니다. 정상 연결된다면 그대로 사용하세요."
            TermuxInstallerSource.F_DROID ->
                "Android가 알려 준 설치 관리자 정보입니다. APK의 공식 배포 여부를 인증한 결과는 아닙니다."
            TermuxInstallerSource.UNKNOWN ->
                "APK를 직접 설치하면 출처를 확인하지 못할 수 있습니다. 출처 미확인만으로 실행을 차단하지 않습니다."
        }
        return listOf(
            DiagnosticItem(
                id = "termux_version",
                title = "Termux 버전",
                value = versionName?.takeIf { it.isNotBlank() } ?: "확인 불가",
                detail = versionNotice,
                status = when (versionAssessment) {
                    TermuxVersionAssessment.CALLBACK_TOO_OLD,
                    TermuxVersionAssessment.SECURITY_UPDATE_RECOMMENDED -> DiagnosticStatus.WARNING
                    else -> DiagnosticStatus.INFO
                },
            ),
            DiagnosticItem(
                id = "termux_install_source",
                title = "Termux 배포 경로",
                value = installerSource.label,
                detail = listOf(sourceNotice, inspectionIssue).filter { it.isNotBlank() }.joinToString("\n"),
                status = DiagnosticStatus.INFO,
                recommendation = "다른 배포 경로의 APK나 플러그인을 섞지 마세요. Termux를 삭제하면 내부 데이터도 지워지므로, 연결 문제만으로 삭제·재설치하지 마세요.",
            ),
        )
    }

    /** Include only in an explicit copy action, never in the visible report or ordinary logs. */
    fun copyOnlyDetails(): Map<String, String> = linkedMapOf(
        "Termux 설치 확인" to when (installed) { true -> "설치됨"; false -> "미설치"; null -> "확인 불가" },
        "Termux 버전 코드" to (versionCode?.toString() ?: "확인 불가"),
        "Termux 설치 관리자 패키지" to (installerPackageName?.takeIf { it.isNotBlank() } ?: "확인 불가"),
        "Termux 현재 서명 인증서 SHA-256" to signingCertificateSha256.ifEmpty { listOf("확인 불가") }.joinToString(", "),
        "Termux 서명 검증 범위" to "설치된 인증서의 지문만 수집하며 공식 배포 여부는 인증하지 않음",
    )
}

object TermuxProcessHelp {
    const val OFFICIAL_HELP_URL = "https://github.com/termux/termux-app/issues/2366"

    fun includesAndroid12ProcessLimit(androidSdk: Int): Boolean = androidSdk >= 31

    fun explanation(androidSdk: Int): String = buildString {
        append("메모리 부족이나 기기의 배터리·백그라운드 제한 때문에 Termux 작업이 종료될 수 있습니다.")
        if (includesAndroid12ProcessLimit(androidSdk)) {
            append(" Android 12 이상에서는 하위 프로세스 수와 CPU 사용량 제한도 원인이 될 수 있습니다.")
        }
        append(" 종료 코드만으로 원인을 확정할 수는 없습니다.")
    }
}
