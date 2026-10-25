package com.nuvio.tv.core.providers

import com.nuvio.tv.core.express.ExpressHttpClient
import com.nuvio.tv.core.express.ExpressException
import com.nuvio.tv.core.express.validateExpressJsonBounds
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ProviderManifestClient @Inject constructor(private val http: ExpressHttpClient, moshi: Moshi) {
    private val adapter = moshi.adapter(AddonManifestDto::class.java)
    suspend fun fetch(url: String): AddonManifestDto = withContext(Dispatchers.IO) {
        val document = http.get(url, 2 * 1024 * 1024)
        validateExpressJsonBounds(document.text)
        val manifest = adapter.fromJson(document.text) ?: throw ExpressException("Provider manifest is invalid.")
        require(manifest.id.isNotBlank() && manifest.id.length <= 256 && manifest.name.isNotBlank() && manifest.name.length <= 256 &&
            manifest.version.isNotBlank() && manifest.version.length <= 128 && manifest.resources.size <= 100 && manifest.catalogs.size <= 100 &&
            manifest.types.size <= 100 && (manifest.resources.isNotEmpty() || manifest.catalogs.isNotEmpty())) { "Provider manifest is invalid." }
        manifest
    }
}
