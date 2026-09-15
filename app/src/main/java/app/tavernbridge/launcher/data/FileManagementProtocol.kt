package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.ExistingInstallation
import app.tavernbridge.launcher.model.TavernTextFile
import app.tavernbridge.launcher.model.validTavernEntryName
import java.util.Base64

internal const val TEXT_EDIT_LIMIT = 65_536

internal fun encodeFileArgument(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

internal fun fileChildPath(parent: String, name: String): String {
    require(validTavernEntryName(name)) { "파일 이름에는 경로 구분자나 제어 문자를 사용할 수 없습니다." }
    require(!parent.startsWith('/') && parent.split('/').none { it == ".." || it == "." }) {
        "SillyTavern 안의 폴더만 선택할 수 있습니다."
    }
    return if (parent.isEmpty()) name else "$parent/$name"
}

/** Only documented local providers have paths that Termux can access directly. */
internal fun installationPathForTree(authority: String?, documentId: String): String {
    val path = when (authority) {
        "com.android.externalstorage.documents" -> {
            require(documentId.startsWith("primary:")) { "내부 저장소의 설치 폴더를 선택해 주세요." }
            "/storage/emulated/0/" + documentId.removePrefix("primary:")
        }
        "com.termux.documents" -> {
            require(documentId.startsWith("/data/data/com.termux/files/home/")) {
                "Termux 홈 안의 설치 폴더를 선택해 주세요."
            }
            documentId
        }
        else -> throw IllegalArgumentException("내부 저장소 또는 Termux의 설치 폴더를 선택해 주세요. 클라우드 폴더는 지원하지 않습니다.")
    }
    require(path.none { it.isISOControl() || it == '\\' } && path.split('/').none { it == ".." || it == "." }) {
        "안전하지 않은 폴더 경로입니다."
    }
    return path.trimEnd('/')
}

internal object FileManagementProtocol {
    private fun fields(output: String) = output.lineSequence()
        .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    fun installation(output: String): ExistingInstallation {
        val values = fields(output)
        return ExistingInstallation(
            sourcePath = decode(values.getValue("source_path_b64")),
            version = decode(values.getValue("source_version_b64")),
            sizeBytes = values.getValue("source_bytes").toLong().also { require(it >= 0) },
            destinationPath = decode(values.getValue("destination_path_b64")),
            sameInstallation = values.getValue("same_installation") == "1",
        )
    }

    fun textFile(path: String, output: String): TavernTextFile {
        val values = fields(output)
        val bytes = Base64.getDecoder().decode(values.getValue("content_b64"))
        require(bytes.size <= TEXT_EDIT_LIMIT) { "앱 안에서는 64 KiB 이하의 텍스트만 수정할 수 있습니다." }
        val revision = values.getValue("sha256")
        require(revision.matches(Regex("[0-9a-f]{64}"))) { "파일 검증 정보가 올바르지 않습니다." }
        return TavernTextFile(path, String(bytes, Charsets.UTF_8), revision)
    }
}
