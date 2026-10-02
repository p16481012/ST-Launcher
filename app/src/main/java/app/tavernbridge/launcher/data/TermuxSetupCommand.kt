package app.tavernbridge.launcher.data

/** A user-pasted command. Never dispatch this automatically through RUN_COMMAND. */
internal fun createTermuxSetupCommand(termuxDirectory: String? = null): String {
    val directory = termuxDirectory?.let { "'${it.replace("'", "'\\''")}'" } ?: "\"${'$'}HOME/.termux\""
    return """
    (
    set -eu;
    st_setup_dir=$directory;
    mkdir -p "${'$'}st_setup_dir";
    st_setup_props="${'$'}st_setup_dir/termux.properties";
    if [ -L "${'$'}st_setup_props" ] || { [ -e "${'$'}st_setup_props" ] && [ ! -f "${'$'}st_setup_props" ]; }; then
    printf '%s\n' '설정 파일이 일반 파일이 아닙니다. 원본을 변경하지 않았습니다.' >&2; exit 1;
    fi;
    st_setup_tmp=${'$'}(mktemp "${'$'}st_setup_props.launcher.XXXXXX");
    trap 'rm -f -- "${'$'}st_setup_tmp"' EXIT;
    if [ -f "${'$'}st_setup_props" ]; then
    awk '!/^[[:space:]]*allow-external-apps([[:space:]]*[:=]|[[:space:]]|${'$'})/ { print } END { print "allow-external-apps=true" }' "${'$'}st_setup_props" > "${'$'}st_setup_tmp";
    else printf '%s\n' 'allow-external-apps=true' > "${'$'}st_setup_tmp";
    fi;
    chmod 600 "${'$'}st_setup_tmp";
    mv -- "${'$'}st_setup_tmp" "${'$'}st_setup_props";
    trap - EXIT;
    termux-reload-settings;
    printf '%s\n' 'Termux 외부 명령 설정 완료. 런처로 돌아가 연결을 다시 확인해 주세요.';
    )
""".trimIndent().lineSequence().joinToString(" ") { it.trim() }
}
