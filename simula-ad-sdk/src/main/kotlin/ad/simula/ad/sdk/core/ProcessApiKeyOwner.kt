package ad.simula.ad.sdk.core

import ad.simula.ad.sdk.privacy.ProcessPrivacyOwner
import ad.simula.ad.sdk.privacy.PrivacyOwnerToken
import ad.simula.ad.sdk.privacy.SimulaPrivacyConfig
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class ApiKeyOwnership {
    Owner,
    Compatible,
    Incompatible;

    val isCompatible: Boolean get() = this != Incompatible
}

/** First non-blank API key owns process-wide telemetry and durable billing infrastructure. */
internal class FirstApiKeyOwner {
    private val owner = AtomicReference<String?>(null)

    fun claim(apiKey: String): ApiKeyOwnership {
        while (true) {
            val current = owner.get()
            if (current != null) {
                return if (current == apiKey) ApiKeyOwnership.Compatible else ApiKeyOwnership.Incompatible
            }
            if (owner.compareAndSet(null, apiKey)) return ApiKeyOwnership.Owner
        }
    }
}

/** Defers a process-mutating ownership claim until a committed Compose side effect invokes it. */
internal class PostCommitApiKeyClaim(
    private val claim: () -> ApiKeyOwnership,
) {
    private val lock = Any()
    private var committed: ApiKeyOwnership? = null

    fun result(): ApiKeyOwnership? = synchronized(lock) { committed }

    fun commit(): ApiKeyOwnership = synchronized(lock) {
        committed ?: claim().also { committed = it }
    }
}

/** Atomically commits key ownership before allowing the compatible entry to seed privacy. */
internal class ApiKeyPrivacyEntryOwner(
    private val apiKeyOwner: FirstApiKeyOwner,
    private val seedPrivacy: (PrivacyOwnerToken, SimulaPrivacyConfig, Boolean) -> Unit,
    private val releasePrivacy: (PrivacyOwnerToken) -> Unit = {},
) {
    private val lock = Any()

    fun claimAndSeedPrivacy(
        apiKey: String,
        privacyOwnerToken: PrivacyOwnerToken,
        privacy: SimulaPrivacyConfig,
        explicitPrivacy: Boolean,
    ): ApiKeyOwnership = synchronized(lock) {
        apiKeyOwner.claim(apiKey).also { ownership ->
            if (ownership.isCompatible) {
                seedPrivacy(privacyOwnerToken, privacy, explicitPrivacy)
            } else {
                releasePrivacy(privacyOwnerToken)
            }
        }
    }
}

internal sealed class ImperativeInitializationAttempt<out T> {
    internal data class Winner<T>(val value: T) : ImperativeInitializationAttempt<T>()
    internal object Duplicate : ImperativeInitializationAttempt<Nothing>()
    internal object Incompatible : ImperativeInitializationAttempt<Nothing>()
}

/** Only the synchronized imperative winner may claim process configuration or publish state. */
internal class ImperativeInitializationGate {
    private val lock = Any()

    @Volatile
    private var initialized = false

    val isInitialized: Boolean get() = initialized

    fun <T> initialize(
        claimAndSeed: () -> ApiKeyOwnership,
        onWinner: () -> T,
    ): ImperativeInitializationAttempt<T> = synchronized(lock) {
        if (initialized) return@synchronized ImperativeInitializationAttempt.Duplicate
        if (!claimAndSeed().isCompatible) {
            return@synchronized ImperativeInitializationAttempt.Incompatible
        }
        val value = onWinner()
        initialized = true
        ImperativeInitializationAttempt.Winner(value)
    }
}

/** Production process owner plus one bounded warning for rejected mixed-key entry points. */
internal object ProcessApiKeyOwner {
    private val owner = FirstApiKeyOwner()
    private val entryOwner = ApiKeyPrivacyEntryOwner(
        apiKeyOwner = owner,
        seedPrivacy = { token, config, explicit -> ProcessPrivacyOwner.seed(token, config, explicit) },
        releasePrivacy = ProcessPrivacyOwner::release,
    )
    private val mismatchWarned = AtomicBoolean(false)

    fun claimAndSeedPrivacy(
        apiKey: String,
        privacyOwnerToken: PrivacyOwnerToken,
        privacy: SimulaPrivacyConfig,
        explicitPrivacy: Boolean,
    ): ApiKeyOwnership = entryOwner.claimAndSeedPrivacy(
        apiKey,
        privacyOwnerToken,
        privacy,
        explicitPrivacy,
    )

    fun warnIncompatibleEntry() {
        if (!mismatchWarned.compareAndSet(false, true)) return
        runCatching {
            Log.w(
                "SimulaAdSDK",
                "Ignoring an SDK entry point configured with a different API key; " +
                    "the first process API key remains active.",
            )
        }
    }
}
