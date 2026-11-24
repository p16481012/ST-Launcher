package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.LanEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LanConnectionCard(
    externalEnabled: Boolean,
    endpoints: List<LanEndpoint>,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var qrEndpoint by remember { mutableStateOf<LanEndpoint?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lan_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (externalEnabled) R.string.lan_enabled_notice else R.string.lan_disabled_notice))
            if (externalEnabled) {
                Text(stringResource(R.string.lan_network_notice), style = MaterialTheme.typography.bodyMedium)
                if (endpoints.isEmpty()) Text(stringResource(R.string.lan_no_address))
                endpoints.take(16).forEach { endpoint ->
                    Text(endpoint.url, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { onCopy(endpoint.url) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.lan_copy_address)) }
                    TextButton(
                        onClick = { qrEndpoint = endpoint },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.lan_show_qr)) }
                }
                TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.lan_refresh_addresses))
                }
            }
            OutlinedButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.lan_security_settings))
            }
        }
    }
    qrEndpoint?.takeIf { externalEnabled && endpoints.any { current -> current.url == it.url } }?.let { endpoint ->
        LanQrDialog(endpoint, onDismiss = { qrEndpoint = null })
    }
}

@Composable
private fun LanQrDialog(endpoint: LanEndpoint, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.lan_show_qr)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(endpoint.url)
            LanQrImage(endpoint)
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lan_close)) } })
}

@Composable
internal fun LanQrImage(endpoint: LanEndpoint) {
    val rendered by produceState<Result<Bitmap>?>(null, endpoint.url) {
        value = null
        value = try {
            Result.success(withContext(Dispatchers.Default) { LanQrBitmap.create(endpoint) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
                val bitmap = rendered?.getOrNull()
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(), contentDescription = stringResource(R.string.lan_qr_description),
                        modifier = Modifier.fillMaxWidth().sizeIn(maxWidth = 320.dp, maxHeight = 320.dp),
                    )
                } else Text(stringResource(if (rendered == null) R.string.lan_qr_loading else R.string.lan_qr_failed))
                Text(stringResource(R.string.lan_qr_notice))
}
