package com.nuvio.tv.ui.screens.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.catalog.SimklPublicCatalog
import com.nuvio.tv.domain.model.Meta

/** A metadata website link only; no provider/media URL is accepted at this boundary. */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
fun SimklAttributionButton(meta: Meta) {
    val url = meta.links.firstOrNull { it.name == "Simkl" && it.category == "metadata" &&
        SimklPublicCatalog.isItemPageUrl(it.url) }?.url ?: return
    val context = LocalContext.current
    val unavailable = stringResource(R.string.syclop_simkl_browser_unavailable)
    Button(onClick = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = SimklPublicCatalog.LOGO_URL, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.syclop_simkl_view_item))
        }
    }
}
