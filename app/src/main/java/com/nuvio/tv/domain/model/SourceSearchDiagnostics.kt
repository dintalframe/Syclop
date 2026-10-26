package com.nuvio.tv.domain.model

/** Fixed reason codes only: never retains provider URLs, account tokens or response payloads. */
enum class SourceSearchReason(val label: String) {
    MISSING_MAPPING("missing anime mapping"),
    MISSING_COORDINATES("missing episode coordinates"),
    TIMEOUT("timed out"),
    RATE_LIMITED("rate limited"),
    PARSER_ERROR("parser failures"),
    TITLE_MISMATCH("unrelated titles"),
    SEASON_PACK_HIDDEN("season packs hidden"),
    INVALID_HASH("invalid source hashes"),
    DUPLICATE("duplicates removed"),
    REQUEST_FAILED("request failures"),
    UNSUPPORTED("unsupported definitions"),
    REQUEST_BUDGET("request limit reached")
}

data class SourceSearchDiagnostics(
    val providers: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val sourceCount: Int = 0,
    val complete: Boolean = false,
    val counts: Map<SourceSearchReason, Int> = emptyMap()
) {
    fun summary(): String = buildString {
        append(completed).append('/').append(providers).append(" providers completed")
        counts.entries.filter { it.value > 0 }.forEach { (reason, count) ->
            append(" · ").append(count).append(' ').append(reason.label)
        }
    }

    companion object {
        fun combine(reports: List<SourceSearchDiagnostics>): SourceSearchDiagnostics = SourceSearchDiagnostics(
            providers = reports.sumOf { it.providers }, completed = reports.sumOf { it.completed },
            failed = reports.sumOf { it.failed }, sourceCount = reports.sumOf { it.sourceCount },
            complete = reports.all { it.complete },
            counts = SourceSearchReason.entries.mapNotNull { reason ->
                reports.sumOf { it.counts[reason] ?: 0 }.takeIf { it > 0 }?.let { reason to it }
            }.toMap()
        )
    }
}
