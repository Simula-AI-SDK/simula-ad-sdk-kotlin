package ad.simula.ad.sdk.network

/** Stable artifacts contain no developer configuration or request markers. */
internal object ArtifactRequestOptions {
    fun freeze() = Unit
    fun headers(): Map<String, String> = emptyMap()
}
