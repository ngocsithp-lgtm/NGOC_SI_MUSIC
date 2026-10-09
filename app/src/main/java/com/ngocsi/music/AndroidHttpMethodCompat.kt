package com.ngocsi.music

import java.util.Locale

/**
 * HttpURLConnection does not expose PATCH in its supported request-method list.
 * Google APIs support POST plus X-HTTP-Method-Override for this case.
 */
internal data class HttpMethodPlan(
    val requestMethod: String,
    val overrideHeader: String? = null
)

internal object AndroidHttpMethodCompat {
    fun plan(method: String): HttpMethodPlan {
        val normalized = method.trim().uppercase(Locale.ROOT)
        return if (normalized == "PATCH") {
            HttpMethodPlan(requestMethod = "POST", overrideHeader = "PATCH")
        } else {
            HttpMethodPlan(requestMethod = normalized)
        }
    }
}
