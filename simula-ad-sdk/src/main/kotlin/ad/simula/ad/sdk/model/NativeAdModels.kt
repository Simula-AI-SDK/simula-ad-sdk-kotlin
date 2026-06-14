package ad.simula.ad.sdk.model

/**
 * Provider-level targeting context for native ads. Set once on [ad.simula.ad.sdk.provider.SimulaProvider]
 * (or via [ad.simula.ad.sdk.ads.SimulaAds.updateContext]) and attached automatically to every
 * `POST /load/native` — a [ad.simula.ad.sdk.nativead.NativeAdSlot] never passes context itself.
 *
 * Maps 1:1 to the backend `NativeContext` wire object (camelCase keys). Updates replace the context
 * in full (not a merge), mirroring the PRD's `updateContext` contract; ads already preloaded under
 * the previous context are unaffected.
 *
 * Mirrors the Swift SDK's `SimulaAdContext`.
 */
data class SimulaAdContext(
    /** Current search / query term in the feed. */
    val searchTerm: String? = null,
    /** Content tags (the backend keeps at most 10). */
    val tags: List<String>? = null,
    /** Feed category. */
    val category: String? = null,
    /** Title of the surrounding feed item. */
    val title: String? = null,
    /** Description of the surrounding feed item. */
    val description: String? = null,
    /** Opaque user-profile signal. */
    val userProfile: String? = null,
    /** User email, if available. */
    val userEmail: String? = null,
    /** Arbitrary string key-values (the backend keeps at most 10 entries). */
    val customContext: Map<String, String>? = null,
    /** Whether the surrounding content is NSFW. Defaults to false. */
    val nsfw: Boolean = false,
)

/**
 * Payload handed to [ad.simula.ad.sdk.nativead.NativeAdSlot]'s `onImpression` callback when the
 * OMID-shaped viewability threshold is met (≥50% visible for ≥1 continuous second). Mirrors the
 * PRD's `AdData` and the Swift SDK's `NativeAdData`.
 */
data class NativeAdData(
    /** Serve UUID from the backend (`impression_id`); the handle used for impression/click reporting. */
    val impressionId: String,
    /** Always `"character_ad"` on a fill. */
    val adFormat: String,
    /** Echo of the slot's `adUnitId` prop (null when none was set). */
    val adUnitId: String?,
)
