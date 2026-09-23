package ad.simula.ad.sdk.ads

import ad.simula.ad.sdk.core.SimulaScope
import ad.simula.ad.sdk.model.admittedVideoUrl
import ad.simula.ad.sdk.network.BoundedDeadlineHostResolver
import ad.simula.ad.sdk.network.DeadlineHostResolver
import ad.simula.ad.sdk.network.SimulaHttp
import ad.simula.ad.sdk.network.abortConnectionAsync
import ad.simula.ad.sdk.telemetry.Telemetry
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
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
import kotlinx.coroutines.delay
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
private const val MAX_CLEANUP_FILES = 128
private const val MAX_CACHE_ENTRIES = 256
private const val MAX_PENDING_ACQUIRES = 16
private const val MAINTENANCE_FOLLOW_UP_DELAY_MS = 50L
private const val CAPACITY_SCAN_INVALIDATION_MS = 60_000L
private const val MAX_VIDEO_REDIRECTS = 5
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val VIDEO_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

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

/** Process-wide local-only video downloader used by every native fullscreen video surface. */
internal object VideoAssetCache {
    private val gate = Any()
    private var manager: VideoAssetCacheManager? = null

    suspend fun acquire(context: Context, rawUrl: String?): VideoAssetLease? {
        val deadlineMs = saturatingAdd(SystemClock.elapsedRealtime(), VIDEO_DOWNLOAD_TIMEOUT_MS)
        return acquireVideoAssetLeaseWithOwnership(VIDEO_DOWNLOAD_TIMEOUT_MS, Dispatchers.IO) {
            val url = rawUrl?.takeIf { it.length <= VIDEO_URL_MAX_LENGTH }
                ?.let(::admittedVideoUrl)
                ?: return@acquireVideoAssetLeaseWithOwnership null
            val processIdentifier = videoCacheProcessIdentifier(currentVideoCacheProcessName(context))
            val cacheDirectory = videoCacheProcessDirectory(context.applicationContext.cacheDir, processIdentifier)
            val active = synchronized(gate) {
                manager ?: VideoAssetCacheManager(cacheDirectory, processPartId = processIdentifier)
                    .also { manager = it }
            }
            active.acquire(url, deadlineMs)
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
    private val beforePublish: () -> Unit = {},
    private val afterPublish: () -> Unit = {},
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
        var acceptingWaiters: Boolean = true,
    ) {
        lateinit var deferred: Deferred<File?>
    }

    private data class CacheEntry(
        val file: File,
        val size: Long,
        val modifiedMs: Long,
        val complete: Boolean,
    )

    private val gate = Any()
    private val maintenanceMutex = Mutex()
    private val downloads = Semaphore(2)
    private val flights = LinkedHashMap<String, Flight>()
    private val leaseCounts = LinkedHashMap<String, Int>()
    private val activeReservations = LinkedHashMap<String, Long>()
    private val knownFiles = LinkedHashMap<String, CacheEntry>()
    private val evicting = mutableSetOf<String>()
    private val nextPartId = AtomicLong()
    private val nextGeneration = AtomicLong()
    private val publicationGenerations = LinkedHashMap<String, Long>()
    private val canonicalGenerations = LinkedHashMap<String, Long>()
    private val publicationLocks = Array(16) { Any() }
    private val followUpScheduled = AtomicBoolean(false)
    private var knownBytes = 0L
    private var directoryReady = false
    private var scanStream: DirectoryStream<java.nio.file.Path>? = null
    private var scanIterator: Iterator<java.nio.file.Path>? = null
    private var scanComplete = false
    private var scanAvailable = true
    private var scannedEntryCount = 0
    private var lastScanCompletedMs = Long.MIN_VALUE

    suspend fun acquire(rawUrl: String, absoluteDeadlineMs: Long? = null): VideoAssetLease? {
        val startedAtMs = elapsedRealtimeMs()
        val deadlineMs = absoluteDeadlineMs ?: saturatingAdd(startedAtMs, downloadTimeoutMs)
        val url = rawUrl.takeIf { it.length <= VIDEO_URL_MAX_LENGTH }
            ?.let(::admittedVideoUrl)
            ?: return null
        val name = opaqueVideoAssetName(url)
        val flight = synchronized(gate) {
            val pendingWaiters = flights.values.sumOf(Flight::waiters)
            val existing = flights[name]
            if (pendingWaiters >= MAX_PENDING_ACQUIRES) return null
            if (existing != null && !existing.acceptingWaiters) return null
            existing ?: Flight(
                generation = nextGeneration.incrementAndGet(),
                control = DownloadControl(abortConnection),
            ).also { created ->
                flights[name] = created
                publicationGenerations[name] = created.generation
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
                        Telemetry.recordError(
                            signature = "video:asset_download_failed",
                            errorCode = failure::class.java.simpleName,
                        )
                        null
                    }
                }
            }
        }
        synchronized(gate) { flight.waiters++ }
        try {
            val remainingMs = runCatching { remainingTimeoutMs(deadlineMs).toLong() }.getOrNull()
                ?: return null
            val file = withTimeoutOrNull(remainingMs) { flight.deferred.await() } ?: return null
            return synchronized(gate) {
                if (evicting.contains(name)) null else leaseLocked(name, file)
            }
        } finally {
            val closeFlight = synchronized(gate) {
                flight.waiters = (flight.waiters - 1).coerceAtLeast(0)
                if (flight.waiters == 0) {
                    flight.acceptingWaiters = false
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
                cleanupUnclaimedPublication(name, flight.generation)
                synchronized(gate) { flights.remove(name, flight) }
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

    private suspend fun resolveFromCacheOrDownload(
        url: String,
        name: String,
        deadlineMs: Long,
        generation: Long,
        control: DownloadControl,
    ): File? {
        if (!establishCapacitySnapshot()) return null
        val destination = File(directory, name)
        if (isCompleteAsset(destination)) {
            recordKnownFile(destination, complete = true)
            return destination
        }

        ensureWithinDeadline(deadlineMs, control)
        SimulaHttp.validatePublicRedirectTarget(url, millisToNanos(deadlineMs), hostResolver)
        ensureWithinDeadline(deadlineMs, control)

        val acquired = withTimeoutOrNull(remainingTimeoutMs(deadlineMs).toLong()) {
            downloads.acquire()
            true
        } ?: false
        if (!acquired) return null
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
    ): File? {
        val destination = File(directory, name)
        val part = File(directory, "$name.$processPartId.${nextPartId.incrementAndGet()}.part")
        val completedPart = File(directory, "$name.$processPartId.$generation.completed")
        val deadlineExpired = AtomicBoolean(false)
        var deadlineTask: VideoDeadlineCancellation? = null
        var reservation = 0L
        var completed = false
        try {
            if (isCompleteAsset(destination)) {
                recordKnownFile(destination, complete = true)
                return destination
            }
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
                    if (redirectCount >= MAX_VIDEO_REDIRECTS) return null
                    currentUrl = resolveVideoRedirect(currentUrl, location) ?: return null
                    redirectCount++
                    initialTargetValidated = false
                    continue
                }
                if (code !in 200..299) {
                    drainBounded(connection.errorStream, 64L * 1024L)
                    return null
                }
                val declared = connection.contentLengthLong
                if (declared > VIDEO_ASSET_MAX_BYTES) return null
                reservation = if (declared in 1..VIDEO_ASSET_MAX_BYTES) declared else VIDEO_ASSET_MAX_BYTES
                if (!reserveCapacity(part.name, reservation, name)) return null

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
                        if (received <= 0L || (declared >= 0L && received != declared)) return null
                        ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                        output.fd.sync()
                    }
                }
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                if (!moveIntoPlace(part, completedPart)) return null
                beforePublish()
                ensureWithinDeadline(deadlineMs, control, deadlineExpired)
                if (!publishGeneration(completedPart, destination, name, generation, control)) return null
                afterPublish()
                completed = isCompleteAsset(destination)
                if (!completed) return null
                recordKnownFile(destination, complete = true)
                control.connection.compareAndSet(connection, null)
                return destination
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            onDownloadFailure(t)
            Telemetry.recordError(
                signature = "video:asset_download_failed",
                errorCode = t::class.java.simpleName,
            )
            return null
        } finally {
            deadlineTask?.cancel()
            if (reservation > 0L) synchronized(gate) { activeReservations.remove(part.name) }
            if (partExists(part)) deleteFile(part)
            if (partExists(completedPart)) deleteFile(completedPart)
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
                    deleteFile(file)
                    synchronized(gate) {
                        removeKnownFileLocked(name)
                        canonicalGenerations.remove(name)
                        evicting.remove(name)
                    }
                }
            }
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
        if (synchronized(gate) { scanComplete }) return@withLock false
        try {
            if (scanStream == null) {
                fileOperation()
                val opened = Files.newDirectoryStream(directory.toPath())
                scanStream = opened
                scanIterator = opened.iterator()
            }
            val iterator = scanIterator ?: run {
                synchronized(gate) { scanAvailable = false }
                return@withLock false
            }
            var count = 0
            while (count < MAX_CLEANUP_FILES && iterator.hasNext()) {
                val file = iterator.next().toFile()
                scannedEntryCount++
                if (scannedEntryCount > MAX_CACHE_ENTRIES) {
                    synchronized(gate) { scanAvailable = false }
                    closeScanStream()
                    return@withLock false
                }
                inspectMaintenanceFile(file)
                count++
            }
            if (iterator.hasNext()) return@withLock true
            closeScanStream()
            synchronized(gate) {
                scanComplete = true
                scanAvailable = true
                lastScanCompletedMs = elapsedRealtimeMs()
            }
            trimKnownCache()
            false
        } catch (failure: Throwable) {
            closeScanStream()
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
        closeScanStream()
        synchronized(gate) {
            knownFiles.clear()
            knownBytes = 0L
            scanComplete = false
            scanAvailable = true
            scannedEntryCount = 0
        }
    }

    private fun closeScanStream() {
        val stream = scanStream
        try {
            runCatching { stream?.close() }
        } finally {
            scanStream = null
            scanIterator = null
        }
    }

    private fun inspectMaintenanceFile(file: File) {
        val name = file.name
        val assetName = partialAssetName(name) ?: name
        val protected = synchronized(gate) {
            leaseCounts.containsKey(assetName) || flights.containsKey(assetName) ||
                activeReservations.containsKey(name) || evicting.contains(assetName)
        }
        val partial = name.endsWith(".part") || name.endsWith(".completed")
        val modified = fileLastModified(file)
        val size = fileLength(file)
        val complete = !partial && fileIsFile(file) && size in 1..VIDEO_ASSET_MAX_BYTES
        val cutoff = wallClockMs() - VIDEO_CACHE_ORPHAN_MAX_AGE_MS
        val removable = if (partial) {
            modified < cutoff || name.contains(".$processPartId.")
        } else {
            modified < cutoff || !complete
        }
        if (!protected && removable && deleteFile(file)) {
            synchronized(gate) { removeKnownFileLocked(name) }
            return
        }
        if (!protected || !partial) recordKnownFile(file, complete)
    }

    private fun reserveCapacity(partName: String, bytes: Long, protectedName: String): Boolean {
        onCapacityCheck()
        repeat(MAX_CLEANUP_FILES) {
            val candidate = synchronized(gate) {
                val reserved = activeReservations.values.fold(0L, ::saturatingAdd)
                if (saturatingAdd(saturatingAdd(knownBytes, reserved), bytes) <= VIDEO_CACHE_MAX_BYTES) {
                    activeReservations[partName] = bytes
                    return true
                }
                knownFiles.values
                    .asSequence()
                    .filter { entry ->
                        entry.complete && entry.file.name != protectedName &&
                            !leaseCounts.containsKey(entry.file.name) &&
                            !flights.containsKey(entry.file.name) &&
                            !evicting.contains(entry.file.name)
                    }
                    .minByOrNull(CacheEntry::modifiedMs)
                    ?.also { evicting += it.file.name }
            } ?: return false
            val deleted = deleteFile(candidate.file)
            synchronized(gate) {
                if (deleted) removeKnownFileLocked(candidate.file.name)
                evicting.remove(candidate.file.name)
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
                        entry.complete && !leaseCounts.containsKey(entry.file.name) &&
                            !flights.containsKey(entry.file.name) && !evicting.contains(entry.file.name)
                    }
                    .minByOrNull(CacheEntry::modifiedMs)
                    ?.also { evicting += it.file.name }
            } ?: return
            val deleted = deleteFile(candidate.file)
            synchronized(gate) {
                if (deleted) removeKnownFileLocked(candidate.file.name)
                evicting.remove(candidate.file.name)
            }
            if (!deleted) return
        }
        scheduleTrimFollowUp()
    }

    private fun scheduleTrimFollowUp() {
        val stillOverCapacity = synchronized(gate) { knownBytes > VIDEO_CACHE_MAX_BYTES }
        if (stillOverCapacity && followUpScheduled.compareAndSet(false, true)) {
            scope.launch(ioDispatcher) {
                try {
                    delay(MAINTENANCE_FOLLOW_UP_DELAY_MS)
                    trimKnownCache()
                } finally {
                    followUpScheduled.set(false)
                    if (synchronized(gate) { knownBytes > VIDEO_CACHE_MAX_BYTES }) {
                        scheduleTrimFollowUp()
                    }
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

    private fun recordKnownFile(file: File, complete: Boolean) {
        val entry = CacheEntry(file, fileLength(file), fileLastModified(file), complete)
        synchronized(gate) {
            val previous = knownFiles.put(file.name, entry)
            if (previous != null) knownBytes = (knownBytes - previous.size).coerceAtLeast(0L)
            knownBytes = saturatingAdd(knownBytes, entry.size)
        }
    }

    private fun removeKnownFileLocked(name: String) {
        val previous = knownFiles.remove(name) ?: return
        knownBytes = (knownBytes - previous.size).coerceAtLeast(0L)
    }

    private fun moveIntoPlace(part: File, destination: File): Boolean {
        fileOperation()
        return runCatching {
            Files.move(
                part.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            fileOperation()
            runCatching {
                Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    }

    private fun publishGeneration(
        completedPart: File,
        destination: File,
        name: String,
        generation: Long,
        control: DownloadControl,
    ): Boolean {
        val publicationLock = publicationLocks[(name.hashCode() and Int.MAX_VALUE) % publicationLocks.size]
        return synchronized(publicationLock) {
            val ownsPublication = synchronized(gate) {
                publicationGenerations[name] == generation
            }
            if (!ownsPublication || control.cancelled.get()) return@synchronized false
            if (!moveIntoPlace(completedPart, destination)) return@synchronized false
            val retained = synchronized(gate) {
                if (publicationGenerations[name] == generation && !control.cancelled.get()) {
                    canonicalGenerations[name] = generation
                    true
                } else false
            }
            if (!retained) {
                deleteFile(destination)
            }
            retained
        }
    }

    private fun cleanupUnclaimedPublication(name: String, generation: Long) {
        val publicationLock = publicationLocks[(name.hashCode() and Int.MAX_VALUE) % publicationLocks.size]
        synchronized(publicationLock) {
            val shouldDelete = synchronized(gate) {
                canonicalGenerations[name] == generation && !leaseCounts.containsKey(name)
            }
            if (!shouldDelete) return
            deleteFile(File(directory, name))
            synchronized(gate) {
                if (canonicalGenerations[name] == generation && !leaseCounts.containsKey(name)) {
                    canonicalGenerations.remove(name)
                    removeKnownFileLocked(name)
                }
            }
        }
    }

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

    private fun partialAssetName(name: String): String? {
        val suffix = when {
            name.endsWith(".part") -> ".part"
            name.endsWith(".completed") -> ".completed"
            else -> return null
        }
        val withoutFlight = name.removeSuffix(suffix).substringBeforeLast('.', missingDelimiterValue = "")
        return withoutFlight.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
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
