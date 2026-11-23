package app.tavernbridge.launcher.data

/** No paths or file contents from a probe are included in failure messages. */
internal object SharedStorageProbe {
    fun requireReadable(output: String) {
        val values = output.lineSequence().filter { '=' in it }
            .groupBy({ it.substringBefore('=') }, { it.substringAfter('=') })
        if (values["shared_read_ready"] == listOf("1") && values["shared_read_reason"] == listOf("ok")) return
        val reason = values["shared_read_reason"]?.singleOrNull()
        val message = when (reason) {
            "storage_unavailable" -> "Termux의 다운로드 폴더 연결을 확인할 수 없습니다. Termux에서 termux-setup-storage를 실행하고 저장소 권한을 허용해 주세요."
            "read_denied", "missing" -> "런처가 만든 파일을 Termux에서 읽지 못했습니다. Android 설정에서 Termux의 파일 접근 권한을 확인해 주세요."
            "content_mismatch" -> "런처와 Termux가 같은 다운로드 폴더를 읽는지 확인할 수 없습니다. 두 앱이 같은 사용자·프로필에 설치되어 있는지 확인해 주세요."
            else -> "Termux의 실제 파일 읽기 검사 응답이 올바르지 않습니다. 저장소 연결을 다시 확인해 주세요."
        }
        throw IllegalStateException("[SHARED_STORAGE_READ_FAILED]\n$message")
    }
}
