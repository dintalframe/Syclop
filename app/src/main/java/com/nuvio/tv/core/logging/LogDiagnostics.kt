package com.nuvio.tv.core.logging

import java.net.URI

fun String?.rawForLog(): String =
    if (this == null) "(null)" else "[redacted]"

fun String?.urlForLog(): String {
    if (this == null) return "(null)"
    // Add-on credentials can be embedded in any path segment, not just the query.
    return runCatching {
        val uri = URI(this)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
            "[redacted URL]"
        } else {
            URI(scheme, null, uri.host, uri.port, null, null, null).toString()
        }
    }.getOrDefault("[redacted URL]")
}

fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String {
    val value = this ?: return "(null)"
    if (value.isBlank()) return value
    // API errors can contain tokens too; retain size instead of response contents.
    return "[redacted body; ${value.length} chars]".take(maxLength.coerceAtLeast(0))
}

fun Throwable.diagnosticSummary(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
        parts.add(name)
        current = current.cause
    }
    return parts.joinToString(" <- ")
}
