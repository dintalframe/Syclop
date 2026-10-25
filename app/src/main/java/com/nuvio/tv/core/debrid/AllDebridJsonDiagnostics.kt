package com.nuvio.tv.core.debrid

import com.squareup.moshi.JsonDataException

/** Diagnostic vocabulary is closed: never returns a server value, raw JSON or exception message. */
internal object AllDebridJsonDiagnostics {
    private val allowed = setOf("status", "data", "error", "code", "magnets", "id", "filename", "name",
        "size", "hash", "ready", "statusCode", "downloaded", "files", "n", "s", "l", "e", "link",
        "filesize", "delayed", "streams", "quality", "user", "username", "isPremium", "isTrial")
    private val pathSuffix = Regex("(?: at path | at )(\\$(?:\\.[A-Za-z][A-Za-z0-9_]{0,31}|\\[[0-9]{1,9}]){0,16})\\z")
    private val fieldToken = Regex("\\.([A-Za-z][A-Za-z0-9_]{0,31})")
    private val declaredField = Regex("\\A(?:Required|Non-null) value '([A-Za-z][A-Za-z0-9_]{0,31})'(?: \\(JSON name '([A-Za-z][A-Za-z0-9_]{0,31})'\\))? (?:missing|was null) at (?:path )?\\$")

    private const val TOKENS = "BEGIN_ARRAY|END_ARRAY|BEGIN_OBJECT|END_OBJECT|NAME|STRING|NUMBER|BOOLEAN|NULL|END_DOCUMENT"
    private val tokenMismatch = Regex("\\AExpected ($TOKENS) but was ($TOKENS) at path (\\$(?:\\.[A-Za-z][A-Za-z0-9_]{0,31}|\\[[0-9]{1,9}]){0,16})\\z")

    fun fields(failure: Throwable): String {
        if (failure !is JsonDataException) return ""
        // Moshi normally appends its path. Read a bounded tail, then reconstruct only vocabulary
        // from our DTO schema. Even a forged message can select only these fixed field names.
        val tail = failure.message?.takeLast(1_024)
            ?: return " jsonPath=unknown jsonField=unknown"
        val path = pathSuffix.find(tail)?.groupValues?.get(1)
        val tokens = path?.let { fieldToken.findAll(it).map { match -> match.groupValues[1] }.toList() }
        val safePath = if (path != null && tokens != null && tokens.all(allowed::contains)) {
            if (tokens.isEmpty()) "$" else tokens.joinToString(".", prefix = "${'$'}.")
        } else "unknown"
        val field = declaredField.find(tail)?.let { match ->
            match.groupValues[2].ifEmpty { match.groupValues[1] }.takeIf(allowed::contains)
        } ?: "unknown"
        // Match only a complete, bounded Moshi token-mismatch sentence. Server values and
        // numeric/string conversion errors cannot select arbitrary diagnostic text.
        val mismatch = failure.message?.takeIf { it.length <= 1_024 }?.let(tokenMismatch::matchEntire)
        val tokenFields = mismatch?.let { " jsonExpectedToken=${it.groupValues[1]} jsonActualToken=${it.groupValues[2]}" }.orEmpty()
        return " jsonPath=$safePath jsonField=$field$tokenFields"
    }
}
