package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.termux.TermuxContract

/** A user-pasted command. Never dispatch this automatically through RUN_COMMAND. */
internal fun createTermuxSetupCommand(
    termuxDirectory: String = "${TermuxContract.HOME_PATH}/.termux",
    bashPath: String = TermuxContract.BASH_PATH,
    secondaryTermuxDirectory: String? = if (termuxDirectory == "${TermuxContract.HOME_PATH}/.termux")
        "${TermuxContract.HOME_PATH}/.config/termux" else null,
): String {
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    // Match Termux's primary-then-secondary precedence without creating a new primary
    // that would shadow an existing secondary configuration.
    val selectSecondary = secondaryTermuxDirectory?.let { secondary -> """
    st_setup_secondary=${quote(secondary)};
    if [ ! -e "${'$'}st_setup_dir/termux.properties" ] && [ ! -L "${'$'}st_setup_dir/termux.properties" ] && { [ -e "${'$'}st_setup_secondary/termux.properties" ] || [ -L "${'$'}st_setup_secondary/termux.properties" ]; }; then
    st_setup_dir="${'$'}st_setup_secondary";
    fi;
    """ } ?: ""
    val script = """
    set -eu;
    st_setup_dir=${quote(termuxDirectory)};
    $selectSecondary
    mkdir -p "${'$'}st_setup_dir";
    st_setup_props="${'$'}st_setup_dir/termux.properties";
    if [ -L "${'$'}st_setup_props" ] || { [ -e "${'$'}st_setup_props" ] && [ ! -f "${'$'}st_setup_props" ]; }; then
    printf '%s\n' '설정 파일이 일반 파일이 아닙니다. 원본을 변경하지 않았습니다.' >&2; exit 1;
    fi;
    if [ ! -f "${'$'}st_setup_props" ] || ! awk '{ previous=last; last=${'$'}0 } END { exit !(previous == "" && last == "allow-external-apps=true") }' "${'$'}st_setup_props"; then
    st_setup_tmp=${'$'}(mktemp "${'$'}st_setup_props.launcher.XXXXXX");
    trap 'rm -f -- "${'$'}st_setup_tmp"' EXIT;
    if [ -f "${'$'}st_setup_props" ]; then
    cat -- "${'$'}st_setup_props" > "${'$'}st_setup_tmp";
    printf '\n\n%s\n' 'allow-external-apps=true' >> "${'$'}st_setup_tmp";
    else printf '%s\n' 'allow-external-apps=true' > "${'$'}st_setup_tmp";
    fi;
    chmod 600 "${'$'}st_setup_tmp";
    mv -- "${'$'}st_setup_tmp" "${'$'}st_setup_props";
    trap - EXIT;
    fi;
    termux-reload-settings;
    printf '%s\n' 'Termux 외부 명령 설정 완료. 런처로 돌아가 연결을 다시 확인해 주세요.';
""".trimIndent().lineSequence().joinToString(" ") { it.trim() }
    // Properties supports continued logical lines. Preserve existing bytes and separate
    // the final, winning key from any trailing backslash instead of filtering raw lines.
    // An explicit interpreter also works when the user's interactive shell is not POSIX.
    return "${quote(bashPath)} --noprofile --norc -c ${quote(script)}"
}
