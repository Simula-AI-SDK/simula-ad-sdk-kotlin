package ad.simula.ad.sdk.network

/** Lock protects configuration against concurrent initialization and playable requests. */
internal class ArtifactOptionSelection {
    private var selected: Boolean? = null

    @Synchronized
    fun configure(hidden: Boolean): Boolean {
        val existing = selected
        if (existing != null) return existing == hidden
        selected = hidden
        return true
    }

    @Synchronized
    fun freeze(): Boolean {
        val value = selected ?: false
        selected = value
        return value
    }
    fun headers(): Map<String, String> = if (freeze()) {
        mapOf("X-Simula-Dev-Hide-Companion" to "true")
    } else {
        emptyMap()
    }
}

internal object ArtifactRequestOptions {
    val selection = ArtifactOptionSelection()

    fun freeze() { selection.freeze() }

    fun headers(): Map<String, String> = selection.headers()
}
