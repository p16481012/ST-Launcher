package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
                    LanAddressCopyRow(endpoint.url) { onCopy(endpoint.url) }
                    V2NavigationRow(stringResource(R.string.lan_show_qr), { qrEndpoint = endpoint })
                }
                V2OutlineAction(stringResource(R.string.lan_refresh_addresses), onRefresh, Modifier.fillMaxWidth(), footer = true)
            }
            V2NavigationRow(stringResource(R.string.lan_security_settings), onConfigure)
        }
    }
    qrEndpoint?.takeIf { externalEnabled && endpoints.any { current -> current.url == it.url } }?.let { endpoint ->
        LanQrDialog(endpoint, onDismiss = { qrEndpoint = null })
    }
}

@Composable
internal fun LanAddressCopyRow(url: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.lan_copy_address)) }
    }
}

@Composable
private fun LanQrDialog(endpoint: LanEndpoint, onDismiss: () -> Unit) {
    V2BottomSheet(onClose = onDismiss) {
        Text(stringResource(R.string.lan_show_qr), style = MaterialTheme.typography.titleLarge)
        Text(endpoint.url)
        LanQrImage(endpoint)
        Button(onClick = LocalV2SheetDismiss.current ?: onDismiss, modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) { Text(stringResource(R.string.lan_close)) }
    }
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
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.lan_qr_description),
                modifier = Modifier.sizeIn(maxWidth = 224.dp).fillMaxWidth().aspectRatio(1f),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
        }
    } else Text(stringResource(if (rendered == null) R.string.lan_qr_loading else R.string.lan_qr_failed))
    Text(stringResource(R.string.lan_qr_notice), style = MaterialTheme.typography.bodySmall)
}
