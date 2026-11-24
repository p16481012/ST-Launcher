package app.tavernbridge.launcher.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.EphemeralBasicAuthCredentials
import app.tavernbridge.launcher.model.LanSecurityChange
import app.tavernbridge.launcher.model.LanSecuritySnapshot
import app.tavernbridge.launcher.model.LanWhitelist
import app.tavernbridge.launcher.model.OperationResultSummary

/** Credentials deliberately disappear on rotation/process death and never enter SavedState. */
@Composable
internal fun LanSecuritySettingsPage(
    current: LanSecuritySnapshot,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (LanSecurityChange) -> Boolean,
    requestedListen: Boolean = current.listen,
    requestedBasicAuth: Boolean = current.basicAuthEnabled,
    running: Boolean = false,
    result: OperationResultSummary? = null,
) {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        val previouslySecure = window == null || window.attributes.flags and flag != 0
        if (!previouslySecure) window?.addFlags(flag)
        onDispose { if (!previouslySecure) window?.clearFlags(flag) }
    }
    var listen by remember(current, requestedListen) { mutableStateOf(requestedListen) }
    var whitelist by remember(current) { mutableStateOf(current.whitelist.joinToString("\n")) }
    var basicAuth by remember(current, requestedBasicAuth) { mutableStateOf(if (current.perUserBasicAuth) current.basicAuthEnabled else requestedBasicAuth) }
    var username by remember(current) { mutableStateOf("") }
    var password by remember(current) { mutableStateOf("") }
    var acknowledged by remember(current) { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    var networkFocused by remember { mutableStateOf(false) }
    var networkError by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(false) }
    var priorResult by remember { mutableStateOf<OperationResultSummary?>(null) }
    LaunchedEffect(result, submitted, busy) {
        if (submitted && !busy && result != null && result != priorResult && result.operation == "save-lan-security") {
            if (result.succeeded) onDismiss() else { submitted = false; rejected = true }
        }
    }
    val parsedNetworks = remember(whitelist) { LanWhitelist.parse(whitelist) }
    val replacingCredentials = username.isNotEmpty() || password.isNotEmpty()
    val needsCredentials = basicAuth && !current.perUserBasicAuth && (!current.basicAuthEnabled || replacingCredentials)
    val credentialsValid = !needsCredentials || EphemeralBasicAuthCredentials.valid(username, password)
    val canSubmit = !busy && !submitted && acknowledged && (!listen || parsedNetworks != null) && credentialsValid
    val dismiss = { username = ""; password = ""; onDismiss() }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().toggleable(listen, enabled = !busy && !submitted, role = Role.Switch) { listen = it; acknowledged = false }
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lan_allow_external), modifier = Modifier.weight(1f))
                        SettingsSwitch(listen, !busy && !submitted)
                    }
                    Text(stringResource(R.string.lan_security_notice), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.lan_restart_notice), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = whitelist,
                        onValueChange = { if (it.length <= 8192) { whitelist = it; acknowledged = false; networkError = false } },
                        enabled = !busy && !submitted,
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (networkFocused && !it.isFocused) networkError = parsedNetworks == null
                            networkFocused = it.isFocused
                        },
                        label = { Text(stringResource(R.string.lan_allowlist)) },
                        supportingText = { Text(stringResource(R.string.lan_allowlist_help)) },
                        isError = networkError && parsedNetworks == null,
                        colors = settingsInputColors(), shape = RoundedCornerShape(12.dp),
                        minLines = 3, maxLines = 6,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                    if (parsedNetworks == null && networkError) Text(
                        stringResource(R.string.lan_legacy_allowlist), color = MaterialTheme.colorScheme.error,
                    ) else if (parsedNetworks != null) Text(
                        stringResource(R.string.lan_allowlist_preview, parsedNetworks.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth().toggleable(basicAuth, enabled = !busy && !submitted && !current.perUserBasicAuth, role = Role.Switch) {
                        basicAuth = it; username = ""; password = ""; acknowledged = false; passwordError = false
                    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lan_basic_auth), modifier = Modifier.weight(1f))
                        SettingsSwitch(basicAuth, !busy && !submitted && !current.perUserBasicAuth)
                    }
                    if (current.perUserBasicAuth) {
                        Text(stringResource(R.string.lan_per_user_auth))
                    } else if (basicAuth) {
                        if (current.basicAuthEnabled) Text(stringResource(R.string.lan_keep_credentials))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { if (it.length <= 128) { username = it; acknowledged = false } },
                            enabled = !busy && !submitted,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.lan_username)) },
                            colors = settingsInputColors(), shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { if (it.length <= 1024) { password = it; acknowledged = false; passwordError = false } },
                            enabled = !busy && !submitted,
                            modifier = Modifier.fillMaxWidth().onFocusChanged {
                                if (passwordFocused && !it.isFocused) passwordError = !credentialsValid
                                passwordFocused = it.isFocused
                            },
                            isError = passwordError && !credentialsValid,
                            colors = settingsInputColors(), shape = RoundedCornerShape(12.dp),
                            label = { Text(stringResource(R.string.lan_password)) },
                            supportingText = { Text(stringResource(R.string.lan_password_help)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        )
                        if (passwordError && !credentialsValid) Text(
                            stringResource(R.string.lan_credentials_invalid), color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(stringResource(R.string.lan_credential_storage_notice), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().toggleable(acknowledged, enabled = !busy && !submitted, role = Role.Checkbox) { acknowledged = it }
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChoiceMark(acknowledged, enabled = !busy && !submitted)
                        Text(stringResource(R.string.lan_explicit_consent), modifier = Modifier.weight(1f))
                    }
                }
                val hint = when {
                    busy -> "진행 중인 작업이 끝난 뒤 적용해 주세요."
                    rejected -> "지금은 적용할 수 없어요. 연결 확인이나 다른 작업이 끝나면 다시 적용해 주세요."
                    listen && networkError && parsedNetworks == null -> "허용 목록의 주소를 확인해 주세요."
                    passwordError && !credentialsValid -> "사용할 아이디와 암호를 입력해 주세요."
                    !acknowledged -> "적용하려면 위의 접속 범위 확인란을 눌러 주세요."
                    else -> null
                }
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { summary = true },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (running) "적용하고 재시작" else "적용") }
                TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.lan_cancel))
                }
            }
    if (summary) AlertDialog(onDismissRequest = { summary = false },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("바뀌는 내용 확인") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("같은 Wi-Fi에서 접속: " + if (listen) "허용" else "차단")
            if (listen) Text("허용 범위: " + parsedNetworks.orEmpty().joinToString(", "))
            Text("로그인 요구: " + if (basicAuth) "켬" else "끔")
            if (running) Text("서버를 잠시 끄고 다시 켜요.") else Text("다음 서버 시작부터 적용해요.")
        } },
        dismissButton = { TextButton(onClick = { summary = false }) { Text("돌아가기") } },
        confirmButton = { TextButton(enabled = canSubmit, onClick = {
                        summary = false
                        if (canSubmit) {
                            val credentials = if (needsCredentials) EphemeralBasicAuthCredentials.create(username, password) else null
                            if (!needsCredentials || credentials != null) {
                                val change = LanSecurityChange(listen, parsedNetworks, basicAuth, credentials)
                                try {
                                    priorResult = result
                                    submitted = onConfirm(change)
                                    rejected = !submitted
                                    if (submitted) { username = ""; password = "" } else change.close()
                                } catch (failure: Throwable) {
                                    change.close()
                                    throw failure
                                }
                            }
                        }
                    }) { Text(stringResource(R.string.lan_apply)) } })
}
