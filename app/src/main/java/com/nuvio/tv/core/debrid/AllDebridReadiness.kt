package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.dto.AllDebridMagnetDto

/** Availability is a service response, never a provider cache badge or a status-name guess. */
internal object AllDebridReadiness {
    enum class Classification { READY, PROCESSING, SERVICE_ERROR, MISSING_STATUS }
    data class Decision(
        val uploadedReady: Boolean?,
        val statusCount: Int,
        val matchingId: Boolean,
        val matchedStatusCode: Int?,
        val classification: Classification
    ) {
        /** Closed vocabulary and bounded numbers only; no IDs, status text or server values. */
        fun diagnosticFields(): String = "uploadedReady=${uploadedReady?.toString() ?: "missing"} " +
            "statusCount=$statusCount matchingId=$matchingId " +
            "matchedStatusCode=${matchedStatusCode?.toString() ?: "unknown"} classification=${classification.name}"
    }

    fun classify(uploadedReady: Boolean?, uploadedId: Long, statuses: List<AllDebridMagnetDto>): Decision {
        if (uploadedReady == true) return Decision(true, 0, false, null, Classification.READY)
        val matches = statuses.filter { it.id == uploadedId }
        val item = matches.singleOrNull()
        val code = item?.statusCode
        val classification = when {
            item == null -> Classification.MISSING_STATUS
            item.error != null -> Classification.SERVICE_ERROR
            code == null || code < 0 -> Classification.MISSING_STATUS
            code in 0..3 -> Classification.PROCESSING
            code == 4 -> Classification.READY
            else -> Classification.SERVICE_ERROR
        }
        return Decision(uploadedReady, statuses.size.coerceAtMost(1_000), matches.isNotEmpty(),
            code?.takeIf { it in 0..999 }, classification)
    }
}
