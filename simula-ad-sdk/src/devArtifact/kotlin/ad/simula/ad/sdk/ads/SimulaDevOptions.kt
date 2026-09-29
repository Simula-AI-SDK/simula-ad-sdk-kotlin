package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.network.ArtifactRequestOptions

/** Development artifacts only. Configure before initializing any SDK surface. */
object SimulaDevOptions {
    /** First configuration wins. Returns false if changing it would require a process restart. */
    @JvmStatic
    fun configure(hidePlayableCompanion: Boolean = false): Boolean =
        ArtifactRequestOptions.selection.configure(hidePlayableCompanion)
}
