package ad.simula.ad.sdk.network

internal data class BeaconPersistenceKey(
    val apiKey: String,
    val impressionId: String,
    val action: String,
)

internal fun PendingBeacon.persistenceKey(): BeaconPersistenceKey =
    BeaconPersistenceKey(apiKey, impressionId, action)

/** Kept as one tested schema decision so table identity cannot drift from queue identity again. */
internal const val BEACON_PRIMARY_KEY_SQL = "api_key, impression_id, action"

/** An ignored insert is successful only when the row can be proven to exist already. */
internal fun migrationRowPersisted(insertResult: Long, rowExists: () -> Boolean): Boolean =
    insertResult != -1L || rowExists()

internal fun normalizeMigratedBeacon(
    beacon: PendingBeacon,
    now: Long,
    fallbackApiKey: String,
): PendingBeacon = beacon.copy(
    createdAt = beacon.createdAt.takeIf { it > 0 }
        ?: beacon.lastAttemptTimestamp.takeIf { it > 0 }
        ?: now,
    apiKey = beacon.apiKey.ifBlank { fallbackApiKey },
)

internal fun normalizeMigratedVerification(
    verification: PendingVerification,
    now: Long,
): PendingVerification = verification.copy(
    createdAt = verification.createdAt.takeIf { it > 0 }
        ?: verification.lastAttemptTimestamp.takeIf { it > 0 }
        ?: now,
)
