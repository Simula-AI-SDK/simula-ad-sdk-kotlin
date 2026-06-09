package ad.simula.ad.sdk.telemetry

import ad.simula.ad.sdk.network.SimulaApiClient

/** Outcome of one telemetry batch POST, mirroring the reward-verification drop/retry policy. */
internal enum class TelemetryAck {
    /** 2xx — events accepted; drop them from the buffer. */
    ACCEPTED,

    /** Permanent client error (4xx except 408/429) — retrying won't help; drop them. */
    DROP,

    /** Transient (5xx / 408 / 429 / connectivity) — keep and retry with backoff. */
    RETRY,
}

/** Sends one encoded batch. Abstracted so the manager can be tested without the network. */
internal fun interface TelemetrySender {
    suspend fun send(body: String): TelemetryAck
}

/**
 * Production sender: posts to `POST /v1/telemetry/events` via [SimulaApiClient], reusing
 * its auth + consent headers. The request is **not** self-instrumented (recursion guard).
 */
internal class ApiTelemetrySender(private val apiKey: String) : TelemetrySender {
    override suspend fun send(body: String): TelemetryAck {
        val code = SimulaApiClient.postTelemetry(apiKey, body)
        return when {
            code in 200..299 -> TelemetryAck.ACCEPTED
            code in 400..499 && code != 408 && code != 429 -> TelemetryAck.DROP
            else -> TelemetryAck.RETRY // -1 (connectivity), 5xx, 408, 429
        }
    }
}
