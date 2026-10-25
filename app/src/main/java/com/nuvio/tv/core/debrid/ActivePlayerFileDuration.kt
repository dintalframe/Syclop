package com.nuvio.tv.core.debrid

/** One local native-player capture. No URL or provider size claim is retained. */
data class ActivePlayerFileDuration(
    val profileId: Int, val selectionRevision: Long, val credentialRevision: Long,
    val nativeVideoId: String, val mediaType: String, val season: Int?, val episode: Int?,
    val mediaOriginKey: String, val durationMillis: Long, val sourceKey: String? = null
) {
    internal fun matches(profile: Int, selection: Long, credentials: Long, video: String?, type: String?,
        season: Int?, episode: Int?, url: String?): Boolean =
        durationMillis > 0L && credentialRevision >= 0L && profileId == profile &&
            selectionRevision == selection && credentialRevision == credentials &&
            nativeVideoId == video && mediaType == type && this.season == season && this.episode == episode &&
            mediaOriginKey.matches(Regex("[a-f0-9]{64}")) && mediaOriginKey == originKey(url)

    override fun toString() = "ActivePlayerFileDuration([redacted])"

    companion object {
        internal fun originKey(url: String?): String? = url?.takeIf { it.isNotBlank() && it.length <= 16_384 }?.let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
