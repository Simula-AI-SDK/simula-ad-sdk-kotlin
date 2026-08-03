package ad.simula.ad.sdk.model

import ad.simula.ad.sdk.telemetry.Telemetry
import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap

internal const val MAX_EXTRA_PARAMETER_ENTRIES = 10
internal const val MAX_EXTRA_PARAMETER_KEY_LENGTH = 64
internal const val MAX_EXTRA_PARAMETER_VALUE_LENGTH = 256

private const val EXTRA_PARAMETERS_LOG_TAG = "SimulaAdMetadata"
private const val EXTRA_PARAMETERS_WARNING =
    "Some metadata entries were ignored because they are invalid or exceed SDK limits."

private fun recordExtraParametersWarning() {
    Log.w(EXTRA_PARAMETERS_LOG_TAG, EXTRA_PARAMETERS_WARNING)
    Telemetry.recordOperation(
        name = "extra_parameters_invalid",
        durationMs = 0L,
        success = false,
        failureClass = "invalid_or_over_limit",
    )
}

/** Validates publisher metadata and returns an immutable wire snapshot, or null when none survives. */
internal fun normalizeExtraParameters(
    parameters: Map<String, String>,
    warn: () -> Unit = ::recordExtraParametersWarning,
): Map<String, String>? {
    val entries = runCatching { parameters.entries.map { it.key to it.value } }.getOrElse {
        warn()
        return null
    }
    val valid = entries
        .asSequence()
        .filter { (key, value) ->
            key.isNotEmpty() &&
                key.codePointCount(0, key.length) <= MAX_EXTRA_PARAMETER_KEY_LENGTH &&
                value.codePointCount(0, value.length) <= MAX_EXTRA_PARAMETER_VALUE_LENGTH &&
                !key.startsWith('$') &&
                '.' !in key
        }
        .sortedBy { it.first }
        .toList()
    if (valid.size != entries.size || valid.size > MAX_EXTRA_PARAMETER_ENTRIES) warn()

    if (valid.isEmpty()) return null
    val snapshot = LinkedHashMap<String, String>(minOf(valid.size, MAX_EXTRA_PARAMETER_ENTRIES))
    valid.take(MAX_EXTRA_PARAMETER_ENTRIES).forEach { (key, value) -> snapshot[key] = value }
    return Collections.unmodifiableMap(snapshot)
}

/** Lock-guarded publisher metadata used by imperative full-screen ad instances. */
internal class ExtraParametersStore(
    private val warn: () -> Unit = ::recordExtraParametersWarning,
) {
    private val lock = Any()
    private var parameters: Map<String, String> = emptyMap()

    fun set(key: String, value: String) {
        val entry = normalizeExtraParameters(mapOf(key to value), warn) ?: return
        synchronized(lock) {
            if (key !in parameters && parameters.size >= MAX_EXTRA_PARAMETER_ENTRIES) {
                warn()
                return
            }
            parameters = Collections.unmodifiableMap(LinkedHashMap(parameters + entry))
        }
    }

    fun replace(replacement: Map<String, String>) {
        val normalized = normalizeExtraParameters(replacement, warn).orEmpty()
        synchronized(lock) { parameters = normalized }
    }

    fun snapshot(): Map<String, String>? = synchronized(lock) {
        parameters.takeIf { it.isNotEmpty() }
    }
}
