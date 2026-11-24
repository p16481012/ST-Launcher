package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.EphemeralBasicAuthCredentials
import app.tavernbridge.launcher.model.LanSecurityChange
import app.tavernbridge.launcher.model.LanSecuritySnapshot
import app.tavernbridge.launcher.model.LanWhitelist

/** Credentials deliberately disappear on rotation/process death and never enter SavedState. */
@Composable
fun LANSecuritySettingsDialog(
    current: LanSecuritySnapshot,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (LanSecurityChange) -> Unit,
    requestedListen: Boolean = current.listen,
    requestedBasicAuth: Boolean = current.basicAuthEnabled,
) {
    var listen by remember(current, requestedListen) { mutableStateOf(requestedListen) }
    var whitelist by remember(current) { mutableStateOf(current.whitelist.joinToString("\n")) }
    var basicAuth by remember(current, requestedBasicAuth) { mutableStateOf(if (current.perUserBasicAuth) current.basicAuthEnabled else requestedBasicAuth) }
    var username by remember(current) { mutableStateOf("") }
    var password by remember(current) { mutableStateOf("") }
    var acknowledged by remember(current) { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val parsedNetworks = remember(whitelist) { LanWhitelist.parse(whitelist) }
    val replacingCredentials = username.isNotEmpty() || password.isNotEmpty()
    val needsCredentials = basicAuth && !current.perUserBasicAuth && (!current.basicAuthEnabled || replacingCredentials)
    val credentialsValid = !needsCredentials || EphemeralBasicAuthCredentials.valid(username, password)
    val canSubmit = !busy && !submitted && acknowledged && (!listen || parsedNetworks != null) && credentialsValid
    val dismiss = { username = ""; password = ""; onDismiss() }

    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.lan_security_settings), style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lan_allow_external), modifier = Modifier.weight(1f))
                        Switch(checked = listen, onCheckedChange = { listen = it; acknowledged = false }, enabled = !busy && !submitted)
                    }
                    Text(stringResource(R.string.lan_security_notice), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.lan_restart_notice), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = whitelist,
                        onValueChange = { if (it.length <= 8192) { whitelist = it; acknowledged = false } },
                        enabled = !busy && !submitted,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.lan_allowlist)) },
                        supportingText = { Text(stringResource(R.string.lan_allowlist_help)) },
                        isError = parsedNetworks == null,
                        minLines = 3, maxLines = 6,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                    if (parsedNetworks == null) Text(
                        stringResource(R.string.lan_legacy_allowlist), color = MaterialTheme.colorScheme.error,
                    ) else Text(
                        stringResource(R.string.lan_allowlist_preview, parsedNetworks.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.lan_basic_auth), modifier = Modifier.weight(1f))
                        Switch(
                            checked = basicAuth,
                            onCheckedChange = { basicAuth = it; username = ""; password = ""; acknowledged = false },
                            enabled = !busy && !submitted && !current.perUserBasicAuth,
                        )
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
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { if (it.length <= 1024) { password = it; acknowledged = false } },
                            enabled = !busy && !submitted,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.lan_password)) },
                            supportingText = { Text(stringResource(R.string.lan_password_help)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        )
                        if (replacingCredentials && !credentialsValid) Text(
                            stringResource(R.string.lan_credentials_invalid), color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(stringResource(R.string.lan_credential_storage_notice), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = { acknowledged = it },
                            enabled = !busy && !submitted,
                        )
                        Text(stringResource(R.string.lan_explicit_consent), modifier = Modifier.weight(1f))
                    }
                }
                Button(
                    onClick = {
                        if (canSubmit) {
                            val credentials = if (needsCredentials) EphemeralBasicAuthCredentials.create(username, password) else null
                            if (!needsCredentials || credentials != null) {
                                val change = LanSecurityChange(listen, parsedNetworks, basicAuth, credentials)
                                username = ""
                                password = ""
                                submitted = true
                                try { onConfirm(change) } catch (failure: Throwable) {
                                    change.close()
                                    throw failure
                                }
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.lan_apply)) }
                TextButton(onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.lan_cancel))
                }
            }
        }
    }
}
