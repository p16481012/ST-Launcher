package app.tavernbridge.launcher.model

enum class ServerSetting(val key: String, val title: String, val group: String, val hint: String, val help: String) {
    THUMB("thumbnails.enabled", "썸네일 사용", "그림·속도", "끄면 원본 그림을 보여 줘요.",
        "썸네일은 목록용으로 작게 줄인 그림이에요. 끄면 프로필 사진이 원본 화질로 보이지만, 캐릭터가 많으면 목록이 느려지고 데이터를 더 써요."),
    LAZY("performance.lazyLoadCharacters", "캐릭터 지연 불러오기", "그림·속도", "캐릭터가 많을 때 첫 화면이 빨라져요.",
        "캐릭터 정보를 필요할 때만 읽어요. 일부 확장과 맞지 않을 수 있어요."),
    COPIES("backups.chat.maxTotalBackups", "채팅 자동 사본", "저장 공간", "대화할 때마다 실리태번이 남기는 사본이에요.",
        "사용자마다 최근 것부터 이만큼 남겨요. 캐릭터별 사본 수에는 별도의 제한이 있어요."),
    EXTENSIONS("extensions.autoUpdate", "확장 자동 업데이트", "확장·플러그인", "실리태번을 켤 때 확장도 최신으로 맞춰요.",
        "설치한 확장의 업데이트를 실리태번이 시작할 때 확인해요."),
    PLUGINS("enableServerPlugins", "서버 플러그인", "확장·플러그인", "서버에서 추가 프로그램을 실행해요.",
        "plugins 폴더의 프로그램이 서버와 같은 권한으로 실행돼요. 믿을 수 있는 것만 넣어 주세요."),
    CORS("enableCorsProxy", "CORS 프록시", "네트워크", "브라우저가 못 부르는 주소를 서버가 대신 불러요.",
        "일부 API 연결 안내에서 켜라고 할 때만 켜 주세요. 외부 접속이 켜져 있으면 허용한 기기가 이 서버를 거쳐 다른 주소를 부를 수 있어요.");

    fun accepts(value: String) = if (this == COPIES) value in setOf("-1", "50", "100", "200") else value in setOf("true","false")
}

data class ChatCopyUsage(val count: Long, val bytes: Long, val users: List<Triple<String,Long,Long>>)

data class ServerConfig(val revision: String, val version: String, val values: Map<ServerSetting, String>) {
    companion object {
        fun parse(text: String): ServerConfig {
            val lines = text.lineSequence().toList()
            val revision = lines.single { it.startsWith("config_revision=") }.substringAfter('=')
            require(revision.matches(Regex("[a-f0-9]{64}")))
            val version = lines.single { it.startsWith("st_version=") }.substringAfter('=')
            require(version.matches(Regex("(?:[0-9][a-zA-Z0-9.+-]{0,63})?")))
            val entries = lines.filter { it.startsWith("key\t") }.map { it.split('\t') }
            require(entries.size == ServerSetting.entries.size && entries.all { it.size == 6 })
            val values = buildMap {
                for (key in ServerSetting.entries) {
                    val row = entries.single { it[1] == key.key }
                    require(row[2] in setOf("0","1") && row[5] in setOf("0","1"))
                    if (row[2] == "1") {
                        require(listOf(row[3],row[4]).all { if (key == ServerSetting.COPIES) it.toLongOrNull() != null else key.accepts(it) })
                        put(key,row[3])
                    }
                }
            }
            return ServerConfig(revision, version, values)
        }
    }
}
