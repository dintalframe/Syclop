package com.nuvio.tv.data.mal

import android.util.Log

internal enum class MalAuthStage {
    SCAN_CREATED, CALLBACK_RECEIVED, TOKEN_EXCHANGE, ACCOUNT_READ, PERSIST, CONNECTED,
    USER_CANCELLED, SCREEN_CLOSED, ACCOUNT_CHANGED, EXPIRED, STORAGE_READ
}

/** Only a fixed stage, exception class and numeric HTTP status; never an exception message/URL/token. */
internal object MalAuthDiagnostics {
    fun event(stage: MalAuthStage, failure: Throwable? = null) {
        val text = message(stage, failure)
        if (failure == null) Log.i("SyclopMalAuth", text) else Log.w("SyclopMalAuth", text)
    }
    fun message(stage: MalAuthStage, failure: Throwable? = null): String = buildString {
        append("stage=").append(stage.name.lowercase())
        if (failure != null) {
            val type = failure.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) } ?: "Exception"
            append(" class=").append(type)
            (failure as? MalApiException)?.statusCode?.takeIf { it in 100..599 }?.let { append(" status=").append(it) }
        }
    }
}
