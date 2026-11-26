package app.tavernbridge.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import app.tavernbridge.launcher.data.DataLibraryAccess
import app.tavernbridge.launcher.data.LibraryItem

@Composable
internal fun LibraryArtwork(library: DataLibraryAccess, user: String, item: LibraryItem,
    modifier: Modifier = Modifier, detail: Boolean = false) {
    key(library, user, item.path, item.artRevision, detail) {
        val state = produceState<Pair<Boolean, Bitmap?>>(false to null) {
            value = true to library.artwork.load(user, item, detail)
        }.value
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val image = state.second
            if (image != null) Image(image.asImageBitmap(), contentDescription = null,
                modifier = Modifier.matchParentSize().testTag("library-artwork-ready"),
                contentScale = if (detail) ContentScale.Fit else ContentScale.Crop)
            else if (state.first && detail) Text("미리보기 없음", style = MaterialTheme.typography.labelMedium)
            else Icon(Icons.Outlined.Image, if (state.first) "미리보기 없음" else null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
