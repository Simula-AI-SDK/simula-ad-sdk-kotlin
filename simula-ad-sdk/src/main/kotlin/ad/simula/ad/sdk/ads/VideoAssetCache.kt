package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.admittedVideoUrl
import ad.simula.ad.sdk.network.BoundedDeadlineHostResolver
import ad.simula.ad.sdk.network.DeadlineHostResolver
import ad.simula.ad.sdk.network.SimulaHttp
import ad.simula.ad.sdk.network.abortConnectionAsync
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.CookieHandler
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val VIDEO_ASSET_MAX_BYTES = 50L * 1024L * 1024L
internal const val VIDEO_CACHE_MAX_BYTES = 100L * 1024L * 1024L
internal const val VIDEO_DOWNLOAD_TIMEOUT_MS = 30_000L
internal const val VIDEO_URL_MAX_LENGTH = 8_192
internal const val VIDEO_CACHE_ORPHAN_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val VIDEO_CACHE_DIRECTORY = "simula_video_v2"
internal const val VIDEO_CACHE_MANIFEST_NAME = "cache-index-v1"
private const val MAX_CLEANUP_FILES = 128
private const val MAX_CACHE_ENTRIES = 256
private const val MAX_PENDING_ACQUIRES = 16
private const val MAX_MANIFEST_BYTES = 96 * 1024
private const val MAX_MANIFEST_LINE_BYTES = 320
private const val MANIFEST_HEADER = "SIMULA_VIDEO_CACHE_INDEX_V1"
private const val MANIFEST_TEMP_SUFFIX = ".tmp"
private const val MANIFEST_COPY_SUFFIX = ".copy"
private const val MANIFEST_BACKUP_SUFFIX = ".bak"
private const val MAINTENANCE_FOLLOW_UP_DELAY_MS = 50L
private const val CAPACITY_SCAN_INVALIDATION_MS = 60_000L
private const val MAX_VIDEO_REDIRECTS = 5
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val VIDEO_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

internal enum class VideoAssetCacheError(val telemetryCode: String) {
    INVALID_URL("invalid_url"),
    UNSAFE_TARGET("unsafe_target"),
    UNAVAILABLE("transfer_failed"),
    TOO_LARGE("asset_too_large"),
    CACHE_FULL("cache_full"),
    ADMISSION_OVERFLOW("cache_admission"),
    TIMED_OUT("cache_timeout"),
    STALE("stale"),
}

internal sealed interface VideoAssetCacheResult {
    data class Ready(val lease: VideoAssetLease) : VideoAssetCacheResult
    data class Failed(val error: VideoAssetCacheError) : VideoAssetCacheResult
}

internal data class VideoAssetLoadFailure(
    val callbackError: SimulaAdError,
    val telemetryCode: String,
) {
    val telemetrySignature: String = "video_asset:$telemetryCode"
}

internal fun videoAssetLoadFailure(error: VideoAssetCacheError): VideoAssetLoadFailure? = when (error) {
    VideoAssetCacheError.TIMED_OUT -> VideoAssetLoadFailure(
        SimulaAdError.Network(SocketTimeoutException("Video cache deadline exceeded")),
        error.telemetryCode,
    )
    VideoAssetCacheError.UNAVAILABLE -> VideoAssetLoadFailure(
        SimulaAdError.Network(java.io.IOException("Video asset transfer failed")),
        error.telemetryCode,
    )
    VideoAssetCacheError.INVALID_URL,
    VideoAssetCacheError.UNSAFE_TARGET,
    VideoAssetCacheError.TOO_LARGE,
    VideoAssetCacheError.CACHE_FULL,
    VideoAssetCacheError.ADMISSION_OVERFLOW,
    -> VideoAssetLoadFailure(SimulaAdError.NoFill, error.telemetryCode)
    VideoAssetCacheError.STALE -> VideoAssetLoadFailure(
        SimulaAdError.NoFill,
        VideoAssetCacheError.ADMISSION_OVERFLOW.telemetryCode,
    )
}

private enum class VideoCacheEntryState(val wireValue: String) {
    PARTIAL("P"),
    COMPLETE("C"),
}

private data class VideoCacheManifestEntry(
    val assetName: String,
    val fileName: String,
    val size: Long,
    val modifiedMs: Long,
    val state: VideoCacheEntryState,
)

private data class VideoCacheManifestRead(
    val entries: LinkedHashMap<String, VideoCacheManifestEntry>,
    val validPaths: List<File>,
    val valid: Boolean,
)

private sealed interface VideoAssetFileResult {
    data class Ready(val file: File) : VideoAssetFileResult
    data class Failed(val error: VideoAssetCacheError) : VideoAssetFileResult
}

internal fun interface VideoDeadlineCancellation {
    fun cancel()
}

internal fun interface VideoDeadlineScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): VideoDeadlineCancellation
}

private object SharedVideoDeadlineScheduler : VideoDeadlineScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "simula-video-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMs: Long, task: () -> Unit): VideoDeadlineCancellation {
        val future = executor.schedule({ runCatching(task) }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return VideoDeadlineCancellation { future.cancel(false) }
    }
}

/** Exclusive lifetime claim for a fully downloaded local video asset. */
internal class VideoAssetLease internal constructor(
    val file: File,
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) runCatching(releaseAction)
    }
}

internal suspend fun acquireVideoAssetLeaseWithOwnership(
    timeoutMs: Long,
    ioDispatcher: CoroutineDispatcher,
    afterAcquire: suspend () -> Unit = {},
    acquire: suspend () -> VideoAssetLease?,
): VideoAssetLease? {
    var acquired: VideoAssetLease? = null
    var delivered = false
    try {
        val lease = withTimeoutOrNull(timeoutMs) {
            withContext(ioDispatcher) {
                val result = acquire()
                acquired = result
                if (result != null) afterAcquire()
                result
            }
        }
        if (lease != null) delivered = true
        return lease
    } finally {
        val abandoned = acquired.takeIf { !delivered }
        if (abandoned != null) {
            withContext(NonCancellable + ioDispatcher) { abandoned.release() }
        }
    }
}

internal suspend fun acquireVideoAssetResultWithOwnership(
    timeoutMs: Long,
    ioDispatcher: CoroutineDispatcher,
    acquire: suspend () -> VideoAssetCacheResult,
): VideoAssetCacheResult {
    var acquired: VideoAssetLease? = null
    var delivered = false
    try {
        val result = withTimeoutOrNull(timeoutMs) {
            withContext(ioDispatcher) {
                acquire().also { outcome ->
                    if (outcome is VideoAssetCacheResult.Ready) acquired = outcome.lease
                }
            }
        } ?: VideoAssetCacheResult.Failed(VideoAssetCacheError.TIMED_OUT)
        if (result is VideoAssetCacheResult.Ready) delivered = true
        return result
    } finally {
        val abandoned = acquired.takeIf { !delivered }
        if (abandoned != null) {
            withContext(NonCancellable + ioDispatcher) { abandoned.release() }
        }
    }
}

internal sealed interface VideoReadyLeaseResult {
    data object Ready : VideoReadyLeaseResult
    data object Stale : VideoReadyLeaseResult
    data class Failed(val error: VideoAssetCacheError) : VideoReadyLeaseResult
}

internal class VideoReadyLeaseOwnership internal constructor(
    val lease: VideoAssetLease,
) {
    private val owned = AtomicBoolean(true)

    fun transferToReady() {
        owned.set(false)
    }

    internal val transferred: Boolean
        get() = !owned.get()

    internal fun releaseIfOwned() {
        if (owned.compareAndSet(true, false)) lease.release()
    }
}

internal suspend fun acquireVideoLeaseForReady(
    acquire: suspend () -> VideoAssetCacheResult,
    afterAcquire: suspend (VideoAssetLease) -> Unit = {},
    isCurrent: () -> Boolean,
    publishReady: suspend (VideoReadyLeaseOwnership) -> Unit,
): VideoReadyLeaseResult {
    val acquiredLease = when (val result = acquire()) {
        is VideoAssetCacheResult.Ready -> result.lease
        is VideoAssetCacheResult.Failed -> return VideoReadyLeaseResult.Failed(result.error)
    }
    val ownership = VideoReadyLeaseOwnership(acquiredLease)
    try {
        afterAcquire(acquiredLease)
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return VideoReadyLeaseResult.Stale
        return try {
            publishReady(ownership)
            if (ownership.transferred) VideoReadyLeaseResult.Ready else VideoReadyLeaseResult.Stale
        } catch (failure: Throwable) {
            if (ownership.transferred) VideoReadyLeaseResult.Ready else throw failure
        }
    } finally {
        ownership.releaseIfOwned()
    }
}

/** Process-wide local-only video downloader used by every native fullscreen video surface. */
internal object VideoAssetCache {
    private val gate = Any()
    private var manager: VideoAssetCacheManager? = null

    suspend fun acquire(context: Context, rawUrl: String?): VideoAssetCacheResult {
        val deadlineMs = saturatingAdd(SystemClock.elapsedRealtime(), VIDEO_DOWNLOAD_TIMEOUT_MS)
        return acquireVideoAssetResultWithOwnership(VIDEO_DOWNLOAD_TIMEOUT_MS, Dispatchers.IO) {
            val url = rawUrl?.takeIf { it.length <= VIDEO_URL_MAX_LENGTH }
                ?.let(::admittedVideoUrl)
                ?: return@acquireVideoAssetResultWithOwnership VideoAssetCacheResult.Failed(
                    VideoAssetCacheError.INVALID_URL,
                )
            val processIdentifier = videoCacheProcessIdentifier(currentVideoCacheProcessName(context))
            val cacheDirectory = videoCacheProcessDirectory(context.applicationContext.cacheDir, processIdentifier)
            val active = synchronized(gate) {
                manager ?: VideoAssetCacheManager(cacheDirectory, processPartId = processIdentifier)
                    .also { manager = it }
            }
            active.acquireResult(url, deadlineMs)
        }
    }
}

internal class VideoAssetCacheManager(
    private val directory: File,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = SimulaScope,
    private val processPartId: String = "process",
    private val downloadTimeoutMs: Long = VIDEO_DOWNLOAD_TIMEOUT_MS,
    private val deadlineScheduler: VideoDeadlineScheduler = SharedVideoDeadlineScheduler,
    resolveHost: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val hostResolver: DeadlineHostResolver = BoundedDeadlineHostResolver(
        lookup = resolveHost,
        clockNanos = { millisToNanos(elapsedRealtimeMs()) },
    ),
    private val openConnection: (String) -> HttpURLConnection = { target ->
        URL(target).openConnection() as? HttpURLConnection
            ?: throw java.io.IOException("Expected an HttpURLConnection")
    },
    private val onFileOperation: (stateLockHeld: Boolean) -> Unit = {},
    private val onCapacityCheck: () -> Unit = {},
    private val onDownloadFailure: (Throwable) -> Unit = {},
    private val abortConnection: (HttpURLConnection?) -> Unit = ::abortConnectionAsync,
    private val cookieHandler: () -> CookieHandler? = CookieHandler::getDefault,
    private val renameFile: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    },
    private val beforePublish: () -> Unit = {},
    private val afterPublish: () -> Unit = {},
    private val beforeFinalLeaseDeleteClaim: () -> Unit = {},
    private val afterFinalLeaseDeleteClaim: () -> Unit = {},
) {
    private class DownloadControl(
        private val abortConnection: (HttpURLConnection?) -> Unit,
    ) {
        val cancelled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>(null)

        fun cancel() {
            cancelled.set(true)
            abortConnection(connection.getAndSet(null))
        }
    }

    private class Flight(
        val generation: Long,
        val control: DownloadControl,
        var waiters: Int = 0,
    ) {
        lateinit var deferred: Deferred<VideoAssetFileResult>
    }

    private data class CacheEntry(
        val assetName: String,
        val file: File,
        val size: Long,
        val modifiedMs: Long,
        val complete: Boolean,
    )

    private val gate = Any()
    private val manifestLock = Any()
    private val maintenanceMutex = Mutex()
    private val downloads = Semaphore(2)
    private val flights = LinkedHashMap<String, Flight>()
    private val leaseCounts = LinkedHashMap<String, Int>()
    private val knownFiles = LinkedHashMap<String, CacheEntry>()
    private val manifestEntries = LinkedHashMap<String, VideoCacheManifestEntry>()
    private val activeEntries = mutableSetOf<String>()
    private val evicting = mutableSetOf<String>()
    private val nextPartId = AtomicLong()
    private val nextGeneration = AtomicLong()
    private val publicationGenerations = LinkedHashMap<String, Long>()
    private val publicationLocks = Array(16) { Any() }
    private val followUpScheduled = AtomicBoolean(false)
    private var knownBytes = 0L
    private var directoryReady = false
    private var manifestReady = false
    private var scanFiles: Array<CacheEntry>? = null
    private var scanIndex = 0
    private var scanComplete = false
    private var scanAvailable = true
    private var scannedEntryCount = 0
    private var lastScanCompletedMs = Long.MIN_VALUE

    suspend fun acquireResult(rawUrl: String, absoluteDeadlineMs: Long? = null): VideoAssetCacheResult {
        val startedAtMs = elapsedRealtimeMs()
        val deadlineMs = absoluteDeadlineMs ?: saturatingAdd(startedAtMs, downloadTimeoutMs)
        val url = rawUrl.takeIf { it.length <= VIDEO_URL_MAX_LENGTH }
            ?.let(::admittedVideoUrl)
            ?: return VideoAssetCacheResult.Failed(VideoAssetCacheError.INVALID_URL)
        val name = opaqueVideoAssetName(url)
        val flight = synchronized(gate) {
            val pendingWaiters = flights.values.sumOf(Flight::waiters)
            if (pendingWaiters >= MAX_PENDING_ACQUIRES) {
                return VideoAssetCacheResult.Failed(VideoAssetCacheError.ADMISSION_OVERFLOW)
            }
            flights[name]?.also { existing ->
                existing.waiters++
            } ?: Flight(
                generation = nextGeneration.incrementAndGet(),
                control = DownloadControl(abortConnection),
                waiters = 1,
            ).let { created ->
                flights[name] = created
                publicationGenerations[name] = created.generation
                try {
                    created.deferred = scope.async(ioDispatcher) {
                        try {
                            resolveFromCacheOrDownload(
                                url,
                                name,
                                deadlineMs,
                                created.generation,
                                created.control,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            onDownloadFailure(failure)
                            VideoAssetFileResult.Failed(normalizeVideoAssetError(failure))
                        }
                    }
                    created
                } catch (failure: Throwable) {
                    flights.remove(name, created)
                    if (publicationGenerations[name] == created.generation) {
                        publicationGenerations.remove(name)
                    }
                    onDownloadFailure(failure)
                    null
                }
            }
        } ?: return VideoAssetCacheResult.Failed(VideoAssetCacheError.UNAVAILABLE)
        try {
            val remainingMs = runCatching { remainingTimeoutMs(deadlineMs).toLong() }.getOrNull()
                ?: return VideoAssetCacheResult.Failed(VideoAssetCacheError.TIMED_OUT)
            val result = withTimeoutOrNull(remainingMs) { flight.deferred.await() }
                ?: return VideoAssetCacheResult.Failed(VideoAssetCacheError.TIMED_OUT)
            return when (result) {
                is VideoAssetFileResult.Ready -> leaseCanonicalFile(name, result.file)
                    ?.let(VideoAssetCacheResult::Ready)
                    ?: VideoAssetCacheResult.Failed(VideoAssetCacheError.STALE)
                is VideoAssetFileResult.Failed -> VideoAssetCacheResult.Failed(result.error)
            }
        } finally {
            val closeFlight = synchronized(gate) {
                flight.waiters = (flight.waiters - 1).coerceAtLeast(0)
                if (flight.waiters == 0) {
                    flights.remove(name, flight)
                    if (publicationGenerations[name] == flight.generation) {
                        publicationGenerations.remove(name)
                    }
                    true
                } else false
            }
            if (closeFlight) {
                if (!flight.deferred.isCompleted) {
                    flight.control.cancel()
                    flight.deferred.cancel()
                }
                cleanupUnclaimedPublication(name)
            }
        }
    }

    internal fun waiterCount(rawUrl: String): Int {
        if (rawUrl.length > VIDEO_URL_MAX_LENGTH) return 0
        val name = opaqueVideoAssetName(rawUrl)
        return synchronized(gate) { flights[name]?.waiters ?: 0 }
    }

    internal fun pendingAcquireCount(): Int = synchronized(gate) {
        flights.values.sumOf(Flight::waiters)
    }

    internal fun activeLeaseCount(rawUrl: String): Int {
        if (rawUrl.length > VIDEO_URL_MAX_LENGTH) return 0
        return synchronized(gate) { leaseCounts[opaqueVideoAssetName(rawUrl)] ?: 0 }
    }

    private suspend fun resolveFromCacheOrDownload(
        url: String,
        name: String,
        deadlineMs: Long,
        generation: Long,
        control: DownloadControl,
    ): VideoAssetFileResult {
        if (!establishCapacitySnapshot()) return VideoAssetFileResult.Failed(VideoAssetCacheError.CACHE_FULL)
        completeAssetFile(name)?.let { return VideoAssetFileResult.Ready(it) }

        ensureWithinDeadline(deadlineMs, control)
        SimulaHttp.validatePublicRedirectTarget(url, millisToNanos(deadlineMs), hostResolver)
        ensureWithinDeadline(deadlineMs, control)

        val acquired = withTimeoutOrNull(remainingTimeoutMs(deadlineMs).toLong()) {
            downloads.acquire()
            true
        } ?: false
        if (!acquired) return VideoAssetFileResult.Failed(VideoAssetCacheError.TIMED_OUT)
        try {
            return downloadOnIo(url, name, deadlineMs, generation, control)
        } finally {
            downloads.release()
        }
    }

    private fun downloadOnIo(
        url: String,
        name: String,
        deadlineMs: Long,
        generation: Long,
        control: DownloadControl,
    ): VideoAssetFileResult {
        val part = File(directory, "$name.$processPartId.${nextPartId.incrementAndGet()}.asset")
        val deadlineExpired = AtomicBoolean(false)
        var deadlineTask: VideoDeadlineCancellation? = null
        var completed = false
        try {
            completeAssetFile(name)?.let { return VideoAssetFileResult.Ready(it) }
            deadlineTask = deadlineScheduler.schedule(remainingTimeoutMs(deadlineMs).toLong()) {
                deadlineExpired.set(true)
                control.cancel()
            }
            var currentUrl = url
            var redirectCount = 0
            var initialTargetValidated = true
            while (true) {
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                if (!initialTargetValidated) {
                    SimulaHttp.validatePublicRedirectTarget(
                        currentUrl,
                        millisToNanos(deadlineMs),
                        hostResolver,
                    )
                    ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                }
                SimulaHttp.validateRedirectCookieIsolation(cookieHandler())
                val connection = openConnection(currentUrl)
                control.connection.set(connection)
                configureConnection(connection, remainingTimeoutMs(deadlineMs))
                connection.connect()
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                connection.readTimeout = remainingTimeoutMs(deadlineMs)
                val code = connection.responseCode
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                if (code in VIDEO_REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                    drainBounded(connection.errorStream ?: connection.inputStream, 64L * 1024L)
                    control.connection.compareAndSet(connection, null)
                    if (redirectCount >= MAX_VIDEO_REDIRECTS) {
                        return VideoAssetFileResult.Failed(VideoAssetCacheError.UNAVAILABLE)
                    }
                    currentUrl = resolveVideoRedirect(currentUrl, location)
                        ?: return VideoAssetFileResult.Failed(VideoAssetCacheError.UNSAFE_TARGET)
                    redirectCount++
                    initialTargetValidated = false
                    continue
                }
                if (code !in 200..299) {
                    drainBounded(connection.errorStream, 64L * 1024L)
                    return VideoAssetFileResult.Failed(VideoAssetCacheError.UNAVAILABLE)
                }
                val declared = connection.contentLengthLong
                if (declared > VIDEO_ASSET_MAX_BYTES) {
                    return VideoAssetFileResult.Failed(VideoAssetCacheError.TOO_LARGE)
                }
                val reservation = if (declared in 1..VIDEO_ASSET_MAX_BYTES) declared else VIDEO_ASSET_MAX_BYTES
                if (!reserveCapacity(part, reservation, name)) {
                    return VideoAssetFileResult.Failed(VideoAssetCacheError.CACHE_FULL)
                }

                var received = 0L
                connection.inputStream.use { input ->
                    fileOperation()
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                            connection.readTimeout = remainingTimeoutMs(deadlineMs)
                            val count = input.read(buffer)
                            ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                            if (count < 0) break
                            if (declared > 0L && received + count > declared) {
                                throw VideoDeclaredLengthExceededException()
                            }
                            received += count
                            if (received > VIDEO_ASSET_MAX_BYTES) throw VideoAssetTooLargeException()
                            output.write(buffer, 0, count)
                        }
                        if (received <= 0L || (declared >= 0L && received != declared)) {
                            return VideoAssetFileResult.Failed(VideoAssetCacheError.UNAVAILABLE)
                        }
                        ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                        output.fd.sync()
                    }
                }
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                beforePublish()
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                if (!publishGeneration(part, name, generation, received, control)) {
                    return VideoAssetFileResult.Failed(VideoAssetCacheError.STALE)
                }
                afterPublish()
                completed = isCompleteAsset(part)
                if (!completed) return VideoAssetFileResult.Failed(VideoAssetCacheError.UNAVAILABLE)
                control.connection.compareAndSet(connection, null)
                return VideoAssetFileResult.Ready(part)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            onDownloadFailure(t)
            return VideoAssetFileResult.Failed(normalizeVideoAssetError(t))
        } finally {
            deadlineTask?.cancel()
            if (!completed) deleteTrackedFile(part.name)
            val incompleteConnection = control.connection.getAndSet(null)
            if (!completed) abortConnection(incompleteConnection)
        }
    }

    private fun configureConnection(connection: HttpURLConnection, timeoutMs: Int) {
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cookie", "")
        connection.setRequestProperty("Cookie2", "")
    }

    private fun resolveVideoRedirect(currentUrl: String, location: String?): String? {
        val candidate = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = SimulaHttp.resolvePublicRedirect(currentUrl, candidate)
        return resolved?.takeIf { it.length <= VIDEO_URL_MAX_LENGTH }?.let(::admittedVideoUrl)
    }

    private fun leaseLocked(name: String, file: File): VideoAssetLease {
        leaseCounts[name] = (leaseCounts[name] ?: 0) + 1
        return VideoAssetLease(file) {
            scope.launch(ioDispatcher) {
                runCatching { beforeFinalLeaseDeleteClaim() }
                synchronized(publicationLock(name)) {
                    val shouldDelete = synchronized(gate) {
                        val remaining = (leaseCounts[name] ?: 1) - 1
                        if (remaining > 0) {
                            leaseCounts[name] = remaining
                            false
                        } else {
                            leaseCounts.remove(name)
                            if (!flights.containsKey(name) && evicting.add(name)) true else false
                        }
                    }
                    if (shouldDelete) {
                        runCatching { afterFinalLeaseDeleteClaim() }
                        deleteTrackedFile(file.name)
                        synchronized(gate) {
                            evicting.remove(name)
                        }
                    }
                }
            }
        }
    }

    private fun completeAssetFile(name: String): File? = synchronized(publicationLock(name)) {
        if (synchronized(gate) { evicting.contains(name) }) return@synchronized null
        val entry = synchronized(gate) {
            knownFiles.values.firstOrNull { it.assetName == name && it.complete }
        } ?: return@synchronized null
        if (!isCompleteAsset(entry.file) || fileLength(entry.file) != entry.size) {
            deleteTrackedFile(entry.file.name)
            return@synchronized null
        }
        entry.file
    }

    private fun leaseCanonicalFile(name: String, file: File): VideoAssetLease? =
        synchronized(publicationLock(name)) {
            val tracked = synchronized(gate) {
                knownFiles[file.name]?.takeIf { it.assetName == name && it.complete }
            } ?: return@synchronized null
            if (!isCompleteAsset(file) || fileLength(file) != tracked.size) return@synchronized null
            synchronized(gate) {
                if (evicting.contains(name)) null else leaseLocked(name, file)
            }
        }

    private suspend fun establishCapacitySnapshot(): Boolean {
        invalidateStaleScan()
        while (true) {
            val hasMore = scanMaintenanceBatch()
            val snapshot = synchronized(gate) { scanComplete && scanAvailable }
            if (snapshot) return true
            if (!hasMore) return false
        }
    }

    private suspend fun scanMaintenanceBatch(): Boolean = maintenanceMutex.withLock {
        if (!ensureDirectory()) {
            synchronized(gate) { scanAvailable = false }
            return@withLock false
        }
        if (!initializeManifest()) {
            synchronized(gate) { scanAvailable = false }
            return@withLock false
        }
        if (synchronized(gate) { scanComplete }) return@withLock false
        try {
            if (scanFiles == null) {
                scanFiles = synchronized(gate) { knownFiles.values.toTypedArray() }
                scanIndex = 0
            }
            val files = scanFiles ?: run {
                synchronized(gate) { scanAvailable = false }
                return@withLock false
            }
            var count = 0
            while (count < MAX_CLEANUP_FILES && scanIndex < files.size) {
                val entry = files[scanIndex++]
                scannedEntryCount++
                if (scannedEntryCount > MAX_CACHE_ENTRIES) {
                    synchronized(gate) { scanAvailable = false }
                    closeScanFiles()
                    return@withLock false
                }
                inspectMaintenanceFile(entry)
                count++
            }
            if (scanIndex < files.size) return@withLock true
            closeScanFiles()
            synchronized(gate) {
                scanComplete = true
                scanAvailable = true
                lastScanCompletedMs = elapsedRealtimeMs()
            }
            trimKnownCache()
            false
        } catch (failure: Throwable) {
            closeScanFiles()
            synchronized(gate) { scanAvailable = false }
            onDownloadFailure(failure)
            false
        }
    }

    private fun invalidateStaleScan() {
        val invalidate = synchronized(gate) {
            scanComplete && elapsedRealtimeMs() - lastScanCompletedMs >= CAPACITY_SCAN_INVALIDATION_MS
        }
        if (!invalidate) return
        closeScanFiles()
        synchronized(gate) {
            scanComplete = false
            scanAvailable = true
            scannedEntryCount = 0
        }
    }

    private fun closeScanFiles() {
        scanFiles = null
        scanIndex = 0
    }

    private fun inspectMaintenanceFile(entry: CacheEntry) {
        val file = entry.file
        val name = file.name
        val assetName = entry.assetName
        val protected = synchronized(gate) {
            leaseCounts.containsKey(assetName) ||
                (entry.complete && flights.containsKey(assetName)) ||
                activeEntries.contains(name) || evicting.contains(assetName)
        }
        val exists = fileIsFile(file)
        val modified = fileLastModified(file)
        val size = fileLength(file)
        val valid = if (entry.complete) {
            exists && size == entry.size && size in 1..VIDEO_ASSET_MAX_BYTES
        } else {
            exists && size in 0..entry.size && entry.size in 0..VIDEO_ASSET_MAX_BYTES
        }
        val cutoff = wallClockMs() - VIDEO_CACHE_ORPHAN_MAX_AGE_MS
        if (!exists && !protected) {
            reconcileMissingEntry(name)
            return
        }
        val removable = !valid || modified < cutoff
        if (!protected && removable) {
            val deleted = synchronized(publicationLock(assetName)) {
                val claimed = synchronized(gate) {
                    !leaseCounts.containsKey(assetName) && !flights.containsKey(assetName) &&
                        !activeEntries.contains(name) && evicting.add(assetName)
                }
                if (!claimed) false else try {
                    deleteTrackedFile(name)
                } finally {
                    synchronized(gate) { evicting.remove(assetName) }
                }
            }
            if (deleted) return
        }
    }

    private fun reserveCapacity(part: File, bytes: Long, protectedName: String): Boolean {
        onCapacityCheck()
        repeat(MAX_CLEANUP_FILES) {
            val admitted = synchronized(manifestLock) {
                val canAdmit = synchronized(gate) {
                    knownFiles.size < MAX_CACHE_ENTRIES &&
                        saturatingAdd(knownBytes, bytes) <= VIDEO_CACHE_MAX_BYTES
                }
                if (!canAdmit) false else registerPartialLocked(part, bytes, protectedName)
            }
            if (admitted) return true
            val candidate = synchronized(gate) {
                knownFiles.values
                    .asSequence()
                    .filter { entry ->
                        entry.complete && entry.assetName != protectedName &&
                            !leaseCounts.containsKey(entry.assetName) &&
                            !flights.containsKey(entry.assetName) &&
                            !evicting.contains(entry.assetName)
                    }
                    .minByOrNull(CacheEntry::modifiedMs)
                    ?.also { evicting += it.assetName }
            } ?: return false
            val deleted = synchronized(publicationLock(candidate.assetName)) {
                deleteTrackedFile(candidate.file.name)
            }
            synchronized(gate) {
                evicting.remove(candidate.assetName)
            }
            if (!deleted) return false
        }
        scheduleTrimFollowUp()
        return false
    }

    private fun trimKnownCache() {
        repeat(MAX_CLEANUP_FILES) {
            val candidate = synchronized(gate) {
                if (knownBytes <= VIDEO_CACHE_MAX_BYTES) return
                knownFiles.values
                    .asSequence()
                    .filter { entry ->
                        entry.complete && !leaseCounts.containsKey(entry.assetName) &&
                            !flights.containsKey(entry.assetName) && !evicting.contains(entry.assetName)
                    }
                    .minByOrNull(CacheEntry::modifiedMs)
                    ?.also { evicting += it.assetName }
            } ?: return
            val deleted = synchronized(publicationLock(candidate.assetName)) {
                deleteTrackedFile(candidate.file.name)
            }
            synchronized(gate) {
                evicting.remove(candidate.assetName)
            }
            if (!deleted) return
        }
        scheduleTrimFollowUp()
    }

    private fun scheduleTrimFollowUp() {
        val canTrim = synchronized(gate) {
            knownBytes > VIDEO_CACHE_MAX_BYTES && knownFiles.values.any { entry ->
                entry.complete && !leaseCounts.containsKey(entry.assetName) &&
                    !flights.containsKey(entry.assetName) && !evicting.contains(entry.assetName)
            }
        }
        if (canTrim && followUpScheduled.compareAndSet(false, true)) {
            scope.launch(ioDispatcher) {
                try {
                    delay(MAINTENANCE_FOLLOW_UP_DELAY_MS)
                    trimKnownCache()
                } finally {
                    followUpScheduled.set(false)
                    scheduleTrimFollowUp()
                }
            }
        }
    }

    private fun ensureDirectory(): Boolean {
        if (directoryReady) return true
        val ready = fileIsDirectory(directory) || runCatching {
            fileOperation()
            directory.mkdirs()
        }.getOrDefault(false)
        directoryReady = ready
        return ready
    }

    private fun initializeManifest(): Boolean = synchronized(manifestLock) {
        if (manifestReady) return@synchronized true
        val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        val backup = File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_BACKUP_SUFFIX)
        val primaryRead = readVideoCacheManifest(manifest, directory)
        val backupRead = if (primaryRead?.valid == true) null else readVideoCacheManifest(backup, directory)
        val selected = when {
            primaryRead?.valid == true -> primaryRead
            backupRead?.valid == true -> backupRead
            primaryRead == null && backupRead == null ->
                VideoCacheManifestRead(LinkedHashMap(), emptyList(), valid = true)
            else -> null
        }
        if (selected == null) {
            val resetPaths = LinkedHashSet<File>(MAX_CACHE_ENTRIES)
            listOfNotNull(primaryRead, backupRead).forEach { invalid ->
                for (path in invalid.validPaths) {
                    if (resetPaths.size >= MAX_CACHE_ENTRIES) break
                    resetPaths += path
                }
            }
            var deletedAll = true
            resetPaths.forEach { path ->
                if (!deleteFile(path)) deletedAll = false
            }
            if (!deletedAll) return@synchronized false
            deleteFile(manifest)
            deleteFile(backup)
            deleteFile(File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_TEMP_SUFFIX))
            deleteFile(File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_COPY_SUFFIX))
            if (!persistManifestLocked(LinkedHashMap())) return@synchronized false
        } else {
            if (selected === backupRead &&
                !persistManifestLocked(LinkedHashMap(selected.entries), preserveBackup = true)
            ) return@synchronized false
            manifestEntries.clear()
            manifestEntries.putAll(selected.entries)
            synchronized(gate) {
                knownFiles.clear()
                knownBytes = 0L
                selected.entries.values.forEach { record ->
                    putKnownFileLocked(record.toCacheEntry())
                }
            }
        }
        deleteFile(File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_TEMP_SUFFIX))
        deleteFile(File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_COPY_SUFFIX))
        deleteFile(backup)
        manifestReady = true
        true
    }

    private fun registerPartialLocked(file: File, bytes: Long, assetName: String): Boolean {
        if (manifestEntries.containsKey(file.name) || manifestEntries.size >= MAX_CACHE_ENTRIES) return false
        val record = VideoCacheManifestEntry(
            assetName = assetName,
            fileName = file.name,
            size = bytes,
            modifiedMs = wallClockMs().coerceAtLeast(0L),
            state = VideoCacheEntryState.PARTIAL,
        )
        val candidate = LinkedHashMap(manifestEntries).apply { put(file.name, record) }
        if (!persistManifestLocked(candidate)) return false
        manifestEntries[file.name] = record
        synchronized(gate) {
            putKnownFileLocked(record.toCacheEntry())
            activeEntries += file.name
        }
        return true
    }

    private fun publishManifestEntry(file: File, assetName: String, size: Long): Boolean =
        synchronized(manifestLock) {
            val current = manifestEntries[file.name]
                ?.takeIf { it.assetName == assetName && it.state == VideoCacheEntryState.PARTIAL }
                ?: return@synchronized false
            val complete = current.copy(
                size = size,
                modifiedMs = wallClockMs().coerceAtLeast(0L),
                state = VideoCacheEntryState.COMPLETE,
            )
            val candidate = LinkedHashMap(manifestEntries).apply { put(file.name, complete) }
            if (!persistManifestLocked(candidate)) return@synchronized false
            manifestEntries[file.name] = complete
            synchronized(gate) {
                activeEntries.remove(file.name)
                putKnownFileLocked(complete.toCacheEntry())
            }
            true
        }

    private fun reconcileMissingEntry(fileName: String): Boolean = synchronized(manifestLock) {
        if (!manifestEntries.containsKey(fileName)) return@synchronized true
        val candidate = LinkedHashMap(manifestEntries).apply { remove(fileName) }
        if (!persistManifestLocked(candidate)) return@synchronized false
        manifestEntries.remove(fileName)
        synchronized(gate) {
            activeEntries.remove(fileName)
            removeKnownFileLocked(fileName)
        }
        true
    }

    private fun deleteTrackedFile(fileName: String): Boolean {
        val record = synchronized(manifestLock) { manifestEntries[fileName] }
            ?: return deleteFile(File(directory, fileName))
        if (!deleteFile(File(directory, record.fileName))) return false
        return reconcileMissingEntry(fileName)
    }

    private fun VideoCacheManifestEntry.toCacheEntry(): CacheEntry = CacheEntry(
        assetName = assetName,
        file = File(directory, fileName),
        size = size,
        modifiedMs = modifiedMs,
        complete = state == VideoCacheEntryState.COMPLETE,
    )

    private fun putKnownFileLocked(entry: CacheEntry) {
        val previous = knownFiles.put(entry.file.name, entry)
        if (previous != null) knownBytes = (knownBytes - previous.size).coerceAtLeast(0L)
        knownBytes = saturatingAdd(knownBytes, entry.size)
    }

    private fun persistManifestLocked(
        entries: LinkedHashMap<String, VideoCacheManifestEntry>,
        preserveBackup: Boolean = false,
    ): Boolean {
        val bytes = serializeVideoCacheManifest(entries.values) ?: return false
        val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        val temporary = File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_TEMP_SUFFIX)
        val copy = File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_COPY_SUFFIX)
        val backup = File(directory, VIDEO_CACHE_MANIFEST_NAME + MANIFEST_BACKUP_SUFFIX)
        return try {
            writeSyncedFile(temporary, bytes)
            if (runCatching { renameFile(temporary, manifest) }.getOrDefault(false)) return true
            writeSyncedFile(copy, bytes)
            if (preserveBackup) {
                if (partExists(manifest) && !deleteFile(manifest)) return false
                return runCatching { renameFile(copy, manifest) }.getOrDefault(false)
            }
            deleteFile(backup)
            val hadManifest = partExists(manifest)
            if (hadManifest && !runCatching { renameFile(manifest, backup) }.getOrDefault(false)) return false
            if (runCatching { renameFile(copy, manifest) }.getOrDefault(false)) {
                deleteFile(temporary)
                deleteFile(backup)
                true
            } else {
                if (hadManifest) runCatching { renameFile(backup, manifest) }
                false
            }
        } catch (failure: Throwable) {
            onDownloadFailure(failure)
            false
        } finally {
            deleteFile(temporary)
            deleteFile(copy)
        }
    }

    private fun writeSyncedFile(file: File, bytes: ByteArray) {
        fileOperation()
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun removeKnownFileLocked(name: String) {
        val previous = knownFiles.remove(name) ?: return
        knownBytes = (knownBytes - previous.size).coerceAtLeast(0L)
    }

    private fun publishGeneration(
        part: File,
        name: String,
        generation: Long,
        size: Long,
        control: DownloadControl,
    ): Boolean {
        return synchronized(publicationLock(name)) {
            val ownsPublication = synchronized(gate) {
                publicationGenerations[name] == generation
            }
            if (!ownsPublication || control.cancelled.get()) return@synchronized false
            if (!publishManifestEntry(part, name, size)) return@synchronized false
            val retained = synchronized(gate) {
                if (publicationGenerations[name] == generation && !control.cancelled.get()) {
                    true
                } else false
            }
            if (!retained) {
                deleteTrackedFile(part.name)
            }
            retained
        }
    }

    private fun cleanupUnclaimedPublication(name: String) {
        synchronized(publicationLock(name)) {
            val shouldDelete = synchronized(gate) {
                !leaseCounts.containsKey(name) && !flights.containsKey(name)
            }
            if (!shouldDelete) return
            val files = synchronized(gate) {
                knownFiles.values.filter { it.assetName == name }.map { it.file.name }
            }
            files.forEach(::deleteTrackedFile)
        }
    }

    private fun publicationLock(name: String): Any =
        publicationLocks[(name.hashCode() and Int.MAX_VALUE) % publicationLocks.size]

    private fun remainingTimeoutMs(deadlineMs: Long): Int {
        val remaining = deadlineMs - elapsedRealtimeMs()
        if (remaining <= 0L) throw SocketTimeoutException("Video download deadline exceeded")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ensureWithinDeadline(
        deadlineMs: Long,
        control: DownloadControl,
        expired: AtomicBoolean? = null,
    ) {
        if (control.cancelled.get() || expired?.get() == true) {
            throw SocketTimeoutException("Video download deadline exceeded")
        }
        remainingTimeoutMs(deadlineMs)
    }

    private fun isCompleteAsset(file: File): Boolean = fileIsFile(file) &&
        fileLength(file) in 1..VIDEO_ASSET_MAX_BYTES

    private fun fileOperation() = onFileOperation(Thread.holdsLock(gate))

    private fun fileIsDirectory(file: File): Boolean {
        fileOperation()
        return runCatching { file.isDirectory }.getOrDefault(false)
    }

    private fun fileIsFile(file: File): Boolean {
        fileOperation()
        return runCatching { file.isFile }.getOrDefault(false)
    }

    private fun fileLength(file: File): Long {
        fileOperation()
        return runCatching { file.length().coerceAtLeast(0L) }.getOrDefault(0L)
    }

    private fun fileLastModified(file: File): Long {
        fileOperation()
        return runCatching { file.lastModified() }.getOrDefault(0L)
    }

    private fun partExists(file: File): Boolean {
        fileOperation()
        return runCatching { file.exists() }.getOrDefault(false)
    }

    private fun deleteFile(file: File): Boolean {
        fileOperation()
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }
}

private val VIDEO_ASSET_NAME_PATTERN = Regex("^[0-9a-f]{64}\\.video$")
private val VIDEO_CACHE_FILE_NAME_PATTERN =
    Regex("^[0-9a-f]{64}\\.video\\.[a-z0-9_-]{1,64}\\.[1-9][0-9]{0,18}\\.asset$")

private fun readVideoCacheManifest(file: File, directory: File): VideoCacheManifestRead? {
    if (!runCatching { file.isFile }.getOrDefault(false)) return null
    val buffer = ByteArray(MAX_MANIFEST_BYTES + 1)
    var count = 0
    var oversize = false
    return try {
        FileInputStream(file).use { input ->
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_MANIFEST_BYTES) oversize = true
            if (!oversize && input.read() >= 0) oversize = true
        }
        parseVideoCacheManifest(buffer.copyOf(minOf(count, MAX_MANIFEST_BYTES)), directory, oversize)
    } catch (_: Throwable) {
        VideoCacheManifestRead(LinkedHashMap(), emptyList(), valid = false)
    }
}

private fun parseVideoCacheManifest(
    bytes: ByteArray,
    directory: File,
    forcedInvalid: Boolean,
): VideoCacheManifestRead {
    val entries = LinkedHashMap<String, VideoCacheManifestEntry>()
    val validPaths = ArrayList<File>(MAX_CACHE_ENTRIES)
    var valid = !forcedInvalid && bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()
    if (bytes.any { byte ->
            val value = byte.toInt() and 0xff
            value != '\n'.code && value != '\t'.code && value !in 0x20..0x7e
        }
    ) valid = false
    val text = String(bytes, Charsets.US_ASCII)
    val lines = text.removeSuffix("\n").split('\n')
    if (lines.firstOrNull() != MANIFEST_HEADER) valid = false
    if (lines.size - 1 > MAX_CACHE_ENTRIES) valid = false
    lines.drop(1).forEach { line ->
        if (line.toByteArray(Charsets.US_ASCII).size > MAX_MANIFEST_LINE_BYTES) {
            valid = false
            return@forEach
        }
        val fields = line.split('\t')
        if (fields.size != 5) {
            valid = false
            return@forEach
        }
        val state = when (fields[0]) {
            VideoCacheEntryState.PARTIAL.wireValue -> VideoCacheEntryState.PARTIAL
            VideoCacheEntryState.COMPLETE.wireValue -> VideoCacheEntryState.COMPLETE
            else -> null
        }
        val assetName = fields[1]
        val fileName = fields[2]
        val size = fields[3].toLongOrNull()
        val modifiedMs = fields[4].toLongOrNull()
        val safeName = VIDEO_ASSET_NAME_PATTERN.matches(assetName) &&
            VIDEO_CACHE_FILE_NAME_PATTERN.matches(fileName) && fileName.startsWith("$assetName.")
        if (safeName && validPaths.size < MAX_CACHE_ENTRIES) validPaths += File(directory, fileName)
        if (state == null || size == null || modifiedMs == null) {
            valid = false
            return@forEach
        }
        val safeSize = when (state) {
            VideoCacheEntryState.PARTIAL -> size in 0..VIDEO_ASSET_MAX_BYTES
            VideoCacheEntryState.COMPLETE -> size in 1..VIDEO_ASSET_MAX_BYTES
        }
        if (!safeName || !safeSize || modifiedMs < 0L ||
            entries.containsKey(fileName) || entries.values.any { it.assetName == assetName &&
                it.state == VideoCacheEntryState.COMPLETE && state == VideoCacheEntryState.COMPLETE }
        ) {
            valid = false
            return@forEach
        }
        if (entries.size >= MAX_CACHE_ENTRIES) {
            valid = false
            return@forEach
        }
        entries[fileName] = VideoCacheManifestEntry(assetName, fileName, size, modifiedMs, state)
    }
    return VideoCacheManifestRead(entries, validPaths, valid)
}

internal fun isValidVideoCacheManifest(bytes: ByteArray): Boolean =
    parseVideoCacheManifest(bytes, File("."), forcedInvalid = false).valid

private fun serializeVideoCacheManifest(entries: Collection<VideoCacheManifestEntry>): ByteArray? {
    if (entries.size > MAX_CACHE_ENTRIES) return null
    val builder = StringBuilder(MANIFEST_HEADER).append('\n')
    val fileNames = mutableSetOf<String>()
    val completeAssets = mutableSetOf<String>()
    for (entry in entries) {
        val safeName = VIDEO_ASSET_NAME_PATTERN.matches(entry.assetName) &&
            VIDEO_CACHE_FILE_NAME_PATTERN.matches(entry.fileName) &&
            entry.fileName.startsWith("${entry.assetName}.")
        val safeSize = when (entry.state) {
            VideoCacheEntryState.PARTIAL -> entry.size in 0..VIDEO_ASSET_MAX_BYTES
            VideoCacheEntryState.COMPLETE -> entry.size in 1..VIDEO_ASSET_MAX_BYTES
        }
        if (!safeName || !safeSize || entry.modifiedMs < 0L || !fileNames.add(entry.fileName) ||
            (entry.state == VideoCacheEntryState.COMPLETE && !completeAssets.add(entry.assetName))
        ) return null
        val line = "${entry.state.wireValue}\t${entry.assetName}\t${entry.fileName}\t${entry.size}\t${entry.modifiedMs}"
        if (line.length > MAX_MANIFEST_LINE_BYTES) return null
        builder.append(line).append('\n')
        if (builder.length > MAX_MANIFEST_BYTES) return null
    }
    return builder.toString().toByteArray(Charsets.US_ASCII).takeIf { it.size <= MAX_MANIFEST_BYTES }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private fun millisToNanos(value: Long): Long =
    if (value > Long.MAX_VALUE / NANOS_PER_MILLISECOND) Long.MAX_VALUE
    else value.coerceAtLeast(0L) * NANOS_PER_MILLISECOND

internal fun opaqueVideoAssetName(url: String): String {
    require(url.length <= VIDEO_URL_MAX_LENGTH) { "Video URL exceeds length limit" }
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".video"
}

internal fun videoCacheProcessIdentifier(processName: String): String {
    val normalized = processName.trim().takeIf { it.isNotEmpty() } ?: "unknown"
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal fun videoCacheProcessDirectory(cacheDir: File, processIdentifier: String): File {
    val bounded = processIdentifier.takeIf { it.matches(Regex("[0-9a-f]{24}")) }
        ?: videoCacheProcessIdentifier(processIdentifier)
    return File(cacheDir, "$VIDEO_CACHE_DIRECTORY/process-$bounded")
}

private fun currentVideoCacheProcessName(context: Context): String {
    val applicationContext = context.applicationContext
    val processName = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            legacyVideoCacheProcessName(applicationContext)
        }
    }.getOrNull()
    return processName?.takeIf { it.isNotBlank() }
        ?: applicationContext.applicationInfo.processName?.takeIf { it.isNotBlank() }
        ?: applicationContext.packageName
}

private fun legacyVideoCacheProcessName(context: Context): String? {
    val procName = runCatching {
        FileInputStream("/proc/self/cmdline").use { input ->
            val buffer = ByteArray(256)
            val count = input.read(buffer).coerceAtLeast(0)
            val end = buffer.indexOf(0.toByte()).takeIf { it in 0 until count } ?: count
            String(buffer, 0, end, Charsets.UTF_8).trim()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    if (procName != null) return procName
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return activityManager?.runningAppProcesses
        ?.firstOrNull { it.pid == Process.myPid() }
        ?.processName
}

private fun drainBounded(stream: InputStream?, limit: Long) {
    stream ?: return
    runCatching {
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = limit
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                remaining -= count
            }
        }
    }
}

private class VideoAssetTooLargeException : java.io.IOException("Video asset exceeds size limit")
private class VideoDeclaredLengthExceededException : java.io.IOException("Video asset exceeds declared length")

private fun normalizeVideoAssetError(failure: Throwable): VideoAssetCacheError = when (failure) {
    is SocketTimeoutException -> VideoAssetCacheError.TIMED_OUT
    is SimulaHttp.RedirectTargetRejectedException,
    is SimulaHttp.RedirectCookieIsolationException,
    -> VideoAssetCacheError.UNSAFE_TARGET
    is VideoAssetTooLargeException,
    is VideoDeclaredLengthExceededException,
    -> VideoAssetCacheError.TOO_LARGE
    else -> VideoAssetCacheError.UNAVAILABLE
}
