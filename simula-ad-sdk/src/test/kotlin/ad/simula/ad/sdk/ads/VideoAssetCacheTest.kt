package ad.simula.ad.sdk.ads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import ad.simula.ad.sdk.network.DeadlineHostResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VideoAssetCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val seededAssetId = AtomicInteger()

    private val publicDns: (String) -> Array<InetAddress> = {
        arrayOf(InetAddress.getByName("8.8.8.8"))
    }

    private suspend fun VideoAssetCacheManager.acquire(rawUrl: String): VideoAssetLease? =
        (acquireResult(rawUrl) as? VideoAssetCacheResult.Ready)?.lease

    @Test
    fun `manifest strictly validates state name size line and entry bounds`() {
        val asset = opaqueVideoAssetName("https://cdn.example/valid.mp4")
        val file = "$asset.process.1.asset"
        val valid = "SIMULA_VIDEO_CACHE_INDEX_V1\nC\t$asset\t$file\t1\t1\n"
        assertTrue(isValidVideoCacheManifest(valid.toByteArray()))
        assertFalse(isValidVideoCacheManifest(valid.replace("\nC\t", "\nX\t").toByteArray()))
        assertFalse(isValidVideoCacheManifest(valid.replace(file, "../asset").toByteArray()))
        assertFalse(isValidVideoCacheManifest(valid.replace("\t1\t1\n", "\t0\t1\n").toByteArray()))
        assertFalse(isValidVideoCacheManifest((valid + "x".repeat(321) + "\n").toByteArray()))
        val tooMany = buildString {
            append("SIMULA_VIDEO_CACHE_INDEX_V1\n")
            repeat(257) { index ->
                val indexedAsset = opaqueVideoAssetName("https://cdn.example/$index.mp4")
                append("P\t$indexedAsset\t$indexedAsset.process.${index + 1}.asset\t1\t1\n")
            }
        }
        assertFalse(isValidVideoCacheManifest(tooMany.toByteArray()))
    }

    @Test
    fun `cache namespace is stable for the same process across restarts`() {
        val root = temporaryFolder.newFolder("stable-process-root")
        val firstId = videoCacheProcessIdentifier("com.example.host:ads")
        val restartedId = videoCacheProcessIdentifier("com.example.host:ads")

        assertEquals(firstId, restartedId)
        assertEquals(
            videoCacheProcessDirectory(root, firstId).canonicalPath,
            videoCacheProcessDirectory(root, restartedId).canonicalPath,
        )
        assertTrue(firstId.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun `cache namespaces isolate distinct Android process names`() {
        val root = temporaryFolder.newFolder("distinct-process-root")
        val main = videoCacheProcessDirectory(root, videoCacheProcessIdentifier("com.example.host"))
        val ads = videoCacheProcessDirectory(root, videoCacheProcessIdentifier("com.example.host:ads"))

        assertFalse(main.name == ads.name)
        assertFalse(main.canonicalPath == ads.canonicalPath)
    }

    @Test
    fun `cache namespace cannot traverse outside cache root`() {
        val root = temporaryFolder.newFolder("traversal-process-root")
        val directory = videoCacheProcessDirectory(root, "../../outside/../secret")

        assertTrue(directory.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertEquals("simula_video_v2", requireNotNull(directory.parentFile).name)
        assertTrue(directory.name.matches(Regex("process-[0-9a-f]{24}")))
        assertFalse(directory.path.contains(".."))
    }

    @Test
    fun `public initial video request is downloaded with cookies suppressed and no sdk headers`() = runTest {
        val connection = HeaderRecordingConnection(
            url = "https://cdn.example/video.mp4",
            status = 200,
            body = byteArrayOf(1, 2, 3),
            ambientCookie = "host_session=secret",
        )
        val fileOperationUnderLock = AtomicBoolean(false)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("cookie-initial"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { connection },
            onFileOperation = { held -> if (held) fileOperationUnderLock.set(true) },
        )

        val lease = manager.acquire("https://cdn.example/video.mp4")

        assertTrue(lease != null)
        assertEquals("", connection.effectiveCookie)
        assertEquals("", connection.effectiveCookie2)
        assertEquals(setOf("Cookie", "Cookie2"), connection.headersAtConnect.keys)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(fileOperationUnderLock.get())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `installed global cookie handler rejects video before opening connection and is not mutated`() = runTest {
        val previous = CookieHandler.getDefault()
        val installed = CookieManager()
        val opens = AtomicInteger()
        try {
            CookieHandler.setDefault(installed)
            val manager = VideoAssetCacheManager(
                directory = temporaryFolder.newFolder("global-cookie-handler"),
                elapsedRealtimeMs = { 0L },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                scope = this,
                resolveHost = publicDns,
                openConnection = {
                    opens.incrementAndGet()
                    FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L)
                },
            )

            val result = manager.acquireResult("https://cdn.example/video.mp4")

            assertEquals(VideoAssetCacheResult.Failed(VideoAssetCacheError.COOKIE_ISOLATION_UNAVAILABLE), result)
            val failure = videoAssetLoadFailure(VideoAssetCacheError.COOKIE_ISOLATION_UNAVAILABLE)
            assertTrue(failure?.callbackError is SimulaAdError.Network)
            assertEquals("video_asset:cookie_isolation_unavailable", failure?.telemetrySignature)
            assertEquals(0, opens.get())
            assertTrue(CookieHandler.getDefault() === installed)
        } finally {
            CookieHandler.setDefault(previous)
        }
    }

    @Test
    fun `private initial video request is rejected before opening a connection`() = runTest {
        val opens = AtomicInteger()
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("private-initial"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = { arrayOf(InetAddress.getByName("10.0.0.1")) },
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L)
            },
        )

        assertNull(manager.acquire("https://private.example/video.mp4"))
        assertEquals(0, opens.get())
    }

    @Test
    fun `public video redirect to private target is rejected before second connection`() = runTest {
        val initial = HeaderRecordingConnection(
            url = "https://cdn.example/start.mp4",
            status = 302,
            body = ByteArray(0),
            location = "https://private.example/final.mp4",
            ambientCookie = "host_session=secret",
        )
        val opened = mutableListOf<String>()
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("private-redirect"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = { host ->
                arrayOf(InetAddress.getByName(if (host == "cdn.example") "8.8.8.8" else "192.168.1.1"))
            },
            openConnection = { target ->
                opened += target
                initial
            },
        )

        assertNull(manager.acquire("https://cdn.example/start.mp4"))
        assertEquals(listOf("https://cdn.example/start.mp4"), opened)
    }

    @Test
    fun `mixed public and private DNS answers reject video before connecting`() = runTest {
        val opens = AtomicInteger()
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("mixed-dns"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = {
                arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
            },
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L)
            },
        )

        assertNull(manager.acquire("https://mixed.example/video.mp4"))
        assertEquals(0, opens.get())
    }

    @Test
    fun `safe public redirect opens a newly cookie suppressed header free connection`() = runTest {
        val initial = HeaderRecordingConnection(
            url = "https://cdn.example/start.mp4",
            status = 302,
            body = ByteArray(0),
            location = "https://media.example/final.mp4",
            ambientCookie = "host_session=initial-secret",
        )
        val redirected = HeaderRecordingConnection(
            url = "https://media.example/final.mp4",
            status = 200,
            body = byteArrayOf(4, 5, 6),
            ambientCookie = "host_session=redirect-secret",
        )
        val opened = mutableListOf<String>()
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("cookie-redirect"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { target ->
                opened += target
                when (target) {
                    "https://cdn.example/start.mp4" -> initial
                    "https://media.example/final.mp4" -> redirected
                    else -> error("unexpected target")
                }
            },
        )

        val lease = manager.acquire("https://cdn.example/start.mp4")

        assertTrue(lease != null)
        assertEquals(
            listOf("https://cdn.example/start.mp4", "https://media.example/final.mp4"),
            opened,
        )
        listOf(initial, redirected).forEach { connection ->
            assertEquals("", connection.effectiveCookie)
            assertEquals("", connection.effectiveCookie2)
            assertEquals(setOf("Cookie", "Cookie2"), connection.headersAtConnect.keys)
            assertFalse(connection.instanceFollowRedirects)
        }
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `cancelled waiter rethrows without cancelling or removing shared flight`() = runTest {
        val directory = temporaryFolder.newFolder("cancel-single-flight")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val downloadFailure = AtomicReference<Throwable?>(null)
        val managerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + managerDispatcher)
        val manager = VideoAssetCacheManager(
            directory = directory,
            scope = managerScope,
            ioDispatcher = managerDispatcher,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            hostResolver = DeadlineHostResolver { _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                publicDns("cdn.example")
            },
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(
                    body = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    declaredLength = 3L,
                )
            },
            onDownloadFailure = downloadFailure::set,
        )

        val cancelled = async(Dispatchers.Default) { manager.acquire("https://cdn.example/shared.mp4") }
        assertTrue(downloadFailure.get()?.stackTraceToString(), withContext(Dispatchers.IO) {
            started.await(5, TimeUnit.SECONDS)
        })
        val survivor = async(Dispatchers.Default) { manager.acquire("https://cdn.example/shared.mp4") }
        val waiterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (manager.waiterCount("https://cdn.example/shared.mp4") < 2 &&
            System.nanoTime() < waiterDeadline
        ) Thread.yield()
        assertEquals(2, manager.waiterCount("https://cdn.example/shared.mp4"))
        cancelled.cancel()
        release.countDown()

        val lease = survivor.await()
        assertTrue(downloadFailure.get()?.stackTraceToString(), lease != null)
        assertEquals(1, opens.get())
        assertTrue(cancelled.isCancelled)
        lease?.release()
        managerScope.cancel()
        managerDispatcher.close()
    }

    @Test
    fun `last waiter cancellation disconnects and vacates the flight`() = runTest {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val managerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + managerDispatcher)
        val connection = DeadlineConnection(entered, disconnected)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("last-waiter"),
            scope = managerScope,
            ioDispatcher = managerDispatcher,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            resolveHost = publicDns,
            openConnection = { connection },
        )
        val waiter = async(Dispatchers.Default) { manager.acquire("https://cdn.example/cancel.mp4") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })

        waiter.cancelAndJoin()

        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
        assertEquals(1, connection.disconnects.get())
        managerScope.cancel()
        managerDispatcher.close()
    }

    @Test
    fun `acquire returns at hard deadline even when worker is late`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val managerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + managerDispatcher)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("hard-deadline"),
            downloadTimeoutMs = 50L,
            ioDispatcher = managerDispatcher,
            scope = managerScope,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            hostResolver = DeadlineHostResolver { _, _ ->
                entered.countDown()
                while (release.count > 0L) {
                    try {
                        release.await(100L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // Simulates an OS resolver that does not honor Future.cancel(true).
                    }
                }
                publicDns("cdn.example")
            },
            openConnection = { error("deadline must expire before opening") },
        )
        val startedNanos = System.nanoTime()

        val lease = manager.acquire("https://cdn.example/late.mp4")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        release.countDown()
        managerScope.cancel()
        managerDispatcher.close()
        assertTrue(entered.await(1L, TimeUnit.SECONDS))
        assertNull(lease)
        assertTrue("elapsed=$elapsedMs", elapsedMs < 1_000L)
    }

    @Test
    fun `outer timeout releases lease acquired before delivery`() = runTest {
        val directory = temporaryFolder.newFolder("lease-delivery-timeout")
        val url = "https://cdn.example/timeout.mp4"
        val file = sparseAsset(directory, url, size = 3L, modified = 1L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            scope = this,
        )
        val acquired = CompletableDeferred<Unit>()
        val holdDelivery = CompletableDeferred<Unit>()
        val result = async {
            acquireVideoAssetLeaseWithOwnership(
                timeoutMs = 100L,
                ioDispatcher = dispatcher,
                acquire = { manager.acquire(url) },
                afterAcquire = {
                    acquired.complete(Unit)
                    holdDelivery.await()
                },
            )
        }
        runCurrent()
        acquired.await()

        advanceTimeBy(100L)
        runCurrent()

        assertNull(result.await())
        advanceUntilIdle()
        assertFalse(file.exists())
    }

    @Test
    fun `parent cancellation releases lease acquired before delivery`() = runTest {
        val directory = temporaryFolder.newFolder("lease-delivery-cancel")
        val url = "https://cdn.example/cancel-delivery.mp4"
        val file = sparseAsset(directory, url, size = 3L, modified = 1L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            scope = this,
        )
        val acquired = CompletableDeferred<Unit>()
        val holdDelivery = CompletableDeferred<Unit>()
        val result = async {
            acquireVideoAssetLeaseWithOwnership(
                timeoutMs = 1_000L,
                ioDispatcher = dispatcher,
                acquire = { manager.acquire(url) },
                afterAcquire = {
                    acquired.complete(Unit)
                    holdDelivery.await()
                },
            )
        }
        runCurrent()
        acquired.await()

        result.cancelAndJoin()

        advanceUntilIdle()
        assertFalse(file.exists())
    }

    @Test
    fun `successful ownership handoff returns retained lease`() = runTest {
        val directory = temporaryFolder.newFolder("lease-delivery-success")
        val url = "https://cdn.example/success.mp4"
        val file = sparseAsset(directory, url, size = 3L, modified = 1L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            scope = this,
        )
        val lease = acquireVideoAssetLeaseWithOwnership(
            timeoutMs = 100L,
            ioDispatcher = dispatcher,
            acquire = { manager.acquire(url) },
        )

        assertTrue(lease != null)
        assertTrue(file.exists())
        lease?.release()
        advanceUntilIdle()
        assertFalse(file.exists())
        assertFalse(File(directory, VIDEO_CACHE_MANIFEST_NAME).readText().contains(file.name))
    }

    @Test
    fun `load cancellation immediately after acquire releases its owned lease once`() = runTest {
        val releases = AtomicInteger()
        val published = AtomicBoolean(false)
        val result = async {
            acquireVideoLeaseForReady(
                acquire = {
                    VideoAssetCacheResult.Ready(
                        VideoAssetLease(temporaryFolder.newFile("cancelled-ready.mp4")) {
                            releases.incrementAndGet()
                        },
                    )
                },
                afterAcquire = { currentCoroutineContext().job.cancel() },
                isCurrent = { true },
                publishReady = {
                    published.set(true)
                },
            )
        }

        runCurrent()
        result.join()

        assertEquals(1, releases.get())
        assertFalse(published.get())
    }

    @Test
    fun `stale load generation releases acquired ready lease once`() = runTest {
        val releases = AtomicInteger()
        var current = true

        val result = acquireVideoLeaseForReady(
            acquire = {
                VideoAssetCacheResult.Ready(
                    VideoAssetLease(temporaryFolder.newFile("stale-ready.mp4")) {
                        releases.incrementAndGet()
                    }.also { current = false },
                )
            },
            isCurrent = { current },
            publishReady = { error("stale generation must not publish Ready") },
        )

        assertEquals(VideoReadyLeaseResult.Stale, result)
        assertEquals(1, releases.get())
    }

    @Test
    fun `Ready publication failure releases acquired lease once`() = runTest {
        val releases = AtomicInteger()

        val failure = runCatching {
            acquireVideoLeaseForReady(
                acquire = {
                    VideoAssetCacheResult.Ready(
                        VideoAssetLease(temporaryFolder.newFile("rejected-ready.mp4")) {
                            releases.incrementAndGet()
                        },
                    )
                },
                isCurrent = { true },
                publishReady = { error("Ready publication failed") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, releases.get())
    }

    @Test
    fun `successful Ready publication owns lease until Ready releases it`() = runTest {
        val releases = AtomicInteger()
        var readyLease: VideoAssetLease? = null

        val result = acquireVideoLeaseForReady(
            acquire = {
                VideoAssetCacheResult.Ready(
                    VideoAssetLease(temporaryFolder.newFile("published-ready.mp4")) {
                        releases.incrementAndGet()
                    },
                )
            },
            isCurrent = { true },
            publishReady = { ownership ->
                readyLease = ownership.lease
                ownership.transferToReady()
            },
        )

        assertEquals(VideoReadyLeaseResult.Ready, result)
        assertEquals(0, releases.get())
        readyLease?.release()
        readyLease?.release()
        assertEquals(1, releases.get())
    }

    @Test
    fun `cancellation after Ready transfer leaves lease owned by Ready`() = runTest {
        val releases = AtomicInteger()
        var readyLease: VideoAssetLease? = null
        val result = async {
            acquireVideoLeaseForReady(
                acquire = {
                    VideoAssetCacheResult.Ready(
                        VideoAssetLease(temporaryFolder.newFile("cancelled-after-ready.mp4")) {
                            releases.incrementAndGet()
                        },
                    )
                },
                isCurrent = { true },
                publishReady = { ownership ->
                    readyLease = ownership.lease
                    ownership.transferToReady()
                    currentCoroutineContext().job.cancel()
                },
            )
        }

        runCurrent()
        result.join()

        assertEquals(0, releases.get())
        readyLease?.release()
        assertEquals(1, releases.get())
    }

    @Test
    fun `buffer reads reserve capacity once instead of rescanning`() = runTest {
        val capacityChecks = AtomicInteger()
        val bytes = DEFAULT_BUFFER_SIZE * 3L
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("single-capacity-check"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ZeroInputStream(bytes), bytes) },
            onCapacityCheck = capacityChecks::incrementAndGet,
        )

        val lease = manager.acquire("https://cdn.example/buffered.mp4")

        assertTrue(lease != null)
        assertEquals(1, capacityChecks.get())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `manifest replacement uses fsynced same directory fallback`() = runTest {
        val directory = temporaryFolder.newFolder("rename-fallback")
        val renameAttempts = AtomicInteger()
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4L)
            },
            renameFile = { source, destination ->
                renameAttempts.incrementAndGet()
                if (destination.exists()) false else source.renameTo(destination)
            },
        )

        val lease = manager.acquire("https://cdn.example/rename-fallback.mp4")

        assertEquals(listOf<Byte>(1, 2, 3, 4), lease?.file?.readBytes()?.toList())
        assertTrue(renameAttempts.get() >= 3)
        assertTrue(File(directory, VIDEO_CACHE_MANIFEST_NAME).readText().contains("\nC\t"))
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `asset file is not created when manifest publication fails`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-write-failure")
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L) },
            renameFile = { _, _ -> false },
        )

        val result = manager.acquireResult("https://cdn.example/no-index-no-file.mp4")

        assertEquals(VideoAssetCacheResult.Failed(VideoAssetCacheError.CACHE_FULL), result)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".asset") })
    }

    @Test
    fun `positive declared length overrun fails on first oversized read`() = runTest {
        val body = CountingInputStream(byteArrayOf(1, 2, 3))
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("declared-overrun"),
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(body, declaredLength = 1L) },
        )

        assertNull(manager.acquire("https://cdn.example/overrun.mp4"))
        assertEquals(1, body.bulkReads.get())
    }

    @Test
    fun `deadline starts before semaphore wait and prevents late connection`() = runTest {
        val calls = AtomicInteger()
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("deadline"),
            elapsedRealtimeMs = { if (calls.getAndIncrement() == 0) 0L else VIDEO_DOWNLOAD_TIMEOUT_MS + 1L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            openConnection = { error("deadline must expire before opening") },
        )

        assertNull(manager.acquire("https://cdn.example/deadline.mp4"))
    }

    @Test
    fun `capacity eviction never removes active lease`() = runTest {
        val directory = temporaryFolder.newFolder("capacity")
        val oldUrl = "https://cdn.example/old.mp4"
        val activeUrl = "https://cdn.example/active.mp4"
        val oldFile = sparseAsset(directory, oldUrl, 50L * 1024L * 1024L, modified = 1L)
        val activeFile = sparseAsset(directory, activeUrl, 40L * 1024L * 1024L, modified = 2L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 0L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = dispatcher,
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ZeroInputStream(20L * 1024L * 1024L), 20L * 1024L * 1024L) },
        )
        val activeLease = requireNotNull(manager.acquire(activeUrl))

        val downloaded = manager.acquire("https://cdn.example/new.mp4")

        assertTrue(downloaded != null)
        assertTrue(activeFile.exists())
        assertFalse(oldFile.exists())
        activeLease.release()
        downloaded?.release()
        advanceUntilIdle()
    }

    @Test
    fun `same URL auto preload before final delete claim rescues canonical lease`() = runBlocking {
        val directory = temporaryFolder.newFolder("reacquire-before-delete-claim")
        val url = "https://cdn.example/reacquire-before.mp4"
        sparseAsset(directory, url, 3L, modified = 1L)
        val beforeClaim = CountDownLatch(1)
        val allowClaim = CountDownLatch(1)
        val holdFirstClaim = AtomicBoolean(true)
        val opens = AtomicInteger()
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            resolveHost = publicDns,
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3L)
            },
            beforeFinalLeaseDeleteClaim = {
                if (holdFirstClaim.compareAndSet(true, false)) {
                    beforeClaim.countDown()
                    allowClaim.await(5, TimeUnit.SECONDS)
                }
            },
        )
        try {
            val initial = requireNotNull(manager.acquire(url))
            initial.release()
            assertTrue(beforeClaim.await(2, TimeUnit.SECONDS))

            val preload = async(Dispatchers.Default) { manager.acquire(url) }
            val replacement = requireNotNull(preload.await())
            assertEquals(2, manager.activeLeaseCount(url))
            allowClaim.countDown()

            val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (manager.activeLeaseCount(url) != 1 && System.nanoTime() < releaseDeadline) Thread.yield()
            assertTrue(replacement.file.exists())
            assertEquals(1, manager.activeLeaseCount(url))
            assertEquals(0, opens.get())
            replacement.release()
        } finally {
            allowClaim.countDown()
            managerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `same URL auto preload after final delete claim waits for healthy replacement`() = runBlocking {
        val directory = temporaryFolder.newFolder("reacquire-after-delete-claim")
        val url = "https://cdn.example/reacquire-after.mp4"
        sparseAsset(directory, url, 3L, modified = 1L)
        val afterClaim = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val holdFirstDelete = AtomicBoolean(true)
        val opens = AtomicInteger()
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            resolveHost = publicDns,
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(ByteArrayInputStream(byteArrayOf(4, 5, 6)), 3L)
            },
            afterFinalLeaseDeleteClaim = {
                if (holdFirstDelete.compareAndSet(true, false)) {
                    afterClaim.countDown()
                    allowDelete.await(5, TimeUnit.SECONDS)
                }
            },
        )
        try {
            val initial = requireNotNull(manager.acquire(url))
            initial.release()
            assertTrue(afterClaim.await(2, TimeUnit.SECONDS))

            val preload = async(Dispatchers.Default) { manager.acquire(url) }
            val waiterDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (manager.waiterCount(url) == 0 && System.nanoTime() < waiterDeadline) Thread.yield()
            assertEquals(1, manager.waiterCount(url))
            allowDelete.countDown()

            val replacement = requireNotNull(preload.await())
            assertTrue(replacement.file.exists())
            assertEquals(1, manager.activeLeaseCount(url))
            assertEquals(1, opens.get())
            replacement.release()
        } finally {
            allowDelete.countDown()
            managerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `close and same URL auto preload stress keeps exactly one valid lease`() = runBlocking {
        val directory = temporaryFolder.newFolder("reacquire-stress")
        val url = "https://cdn.example/reacquire-stress.mp4"
        sparseAsset(directory, url, 3L, modified = 1L)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ByteArrayInputStream(byteArrayOf(7, 8, 9)), 3L)
            },
        )
        try {
            var lease = requireNotNull(manager.acquire(url))
            repeat(100) {
                val preload = async(Dispatchers.Default) { manager.acquire(url) }
                lease.release()
                lease = requireNotNull(preload.await())
                assertTrue(lease.file.exists())
                val releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (manager.activeLeaseCount(url) != 1 && System.nanoTime() < releaseDeadline) Thread.yield()
                assertEquals(1, manager.activeLeaseCount(url))
            }
            lease.release()
        } finally {
            managerScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `unknown partial is reserved within cap and cannot evict active assets`() = runTest {
        val directory = temporaryFolder.newFolder("partial-cap")
        val firstUrl = "https://cdn.example/first.mp4"
        val secondUrl = "https://cdn.example/second.mp4"
        val firstFile = sparseAsset(directory, firstUrl, 50L * 1024L * 1024L, modified = 1L)
        val secondFile = sparseAsset(directory, secondUrl, 40L * 1024L * 1024L, modified = 2L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 0L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = dispatcher,
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ZeroInputStream(1L), declaredLength = -1L) },
        )
        val first = requireNotNull(manager.acquire(firstUrl))
        val second = requireNotNull(manager.acquire(secondUrl))

        assertNull(manager.acquire("https://cdn.example/unknown.mp4"))
        assertTrue(firstFile.exists())
        assertTrue(secondFile.exists())
        assertTrue(indexedAssetFiles(directory).none { it.name.endsWith(".part") })
        first.release()
        second.release()
        advanceUntilIdle()
    }

    @Test
    fun `manifest over entry cap resets only bounded valid listed paths`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-entry-cap")
        val files = (0..256).map { index ->
            val url = "https://cdn.example/corrupt-$index.mp4"
            sparseAsset(directory, url, 1L, modified = 1L)
        }
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L) },
        )

        val lease = manager.acquire("https://cdn.example/recovered.mp4")

        assertTrue(lease != null)
        assertTrue(files.take(256).none(File::exists))
        assertTrue(files.last().exists())
        assertTrue(File(directory, VIDEO_CACHE_MANIFEST_NAME).length() < 96L * 1024L)
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `invalid primary recovers valid backup before removing it`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-invalid-primary-valid-backup")
        val invalidPrimaryFile = seededAssetFile(directory, "https://cdn.example/invalid-primary.mp4", 1L, 1L)
        val backupUrl = "https://cdn.example/backup.mp4"
        val backupFile = seededAssetFile(directory, backupUrl, 1L, 1L)
        val primary = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        primary.writeText(manifestText(invalidPrimaryFile, "https://cdn.example/invalid-primary.mp4") + "invalid\n")
        val backupText = manifestText(backupFile, backupUrl)
        val backup = File(directory, "$VIDEO_CACHE_MANIFEST_NAME.bak").apply { writeText(backupText) }
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            openConnection = { error("valid backup must avoid download") },
        )

        val lease = manager.acquire(backupUrl)

        assertEquals(backupFile.canonicalPath, lease?.file?.canonicalPath)
        assertEquals(backupText, primary.readText())
        assertTrue(invalidPrimaryFile.exists())
        assertFalse(backup.exists())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `valid primary is preferred over stale backup`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-valid-primary-stale-backup")
        val primaryUrl = "https://cdn.example/primary.mp4"
        val primaryFile = seededAssetFile(directory, primaryUrl, 1L, 1L)
        val staleUrl = "https://cdn.example/stale-backup.mp4"
        val staleFile = seededAssetFile(directory, staleUrl, 1L, 1L)
        val primaryText = manifestText(primaryFile, primaryUrl)
        val primary = File(directory, VIDEO_CACHE_MANIFEST_NAME).apply { writeText(primaryText) }
        val backup = File(directory, "$VIDEO_CACHE_MANIFEST_NAME.bak").apply {
            writeText(manifestText(staleFile, staleUrl))
        }
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            openConnection = { error("valid primary must avoid download") },
        )

        val lease = manager.acquire(primaryUrl)

        assertEquals(primaryFile.canonicalPath, lease?.file?.canonicalPath)
        assertEquals(primaryText, primary.readText())
        assertTrue(staleFile.exists())
        assertFalse(backup.exists())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `invalid primary and backup reset union of at most 256 validated paths`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-both-invalid")
        val primaryFiles = (0 until 128).map { index ->
            val url = "https://cdn.example/invalid-primary-$index.mp4"
            seededAssetFile(directory, url, 1L, 1L) to url
        }
        val backupFiles = (0 until 129).map { index ->
            val url = "https://cdn.example/invalid-backup-$index.mp4"
            seededAssetFile(directory, url, 1L, 1L) to url
        }
        File(directory, VIDEO_CACHE_MANIFEST_NAME).writeText(
            manifestText(primaryFiles) + "invalid\n",
        )
        File(directory, "$VIDEO_CACHE_MANIFEST_NAME.bak").writeText(
            manifestText(backupFiles) + "invalid\n",
        )
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(9)), 1L) },
        )

        val lease = manager.acquire("https://cdn.example/after-bounded-reset.mp4")

        assertTrue(lease != null)
        assertTrue(primaryFiles.none { (file, _) -> file.exists() })
        assertTrue(backupFiles.take(128).none { (file, _) -> file.exists() })
        assertTrue(backupFiles.last().first.exists())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `backup is retained when recovered primary cannot be persisted`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-recovery-persist-failure")
        val invalidFile = seededAssetFile(directory, "https://cdn.example/invalid.mp4", 1L, 1L)
        val backupUrl = "https://cdn.example/retry-backup.mp4"
        val backupFile = seededAssetFile(directory, backupUrl, 1L, 1L)
        File(directory, VIDEO_CACHE_MANIFEST_NAME).writeText(
            manifestText(invalidFile, "https://cdn.example/invalid.mp4") + "invalid\n",
        )
        val backupText = manifestText(backupFile, backupUrl)
        val backup = File(directory, "$VIDEO_CACHE_MANIFEST_NAME.bak").apply { writeText(backupText) }
        val failingManager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            renameFile = { _, _ -> false },
        )

        assertEquals(
            VideoAssetCacheResult.Failed(VideoAssetCacheError.CACHE_FULL),
            failingManager.acquireResult(backupUrl),
        )
        assertEquals(backupText, backup.readText())

        val retryManager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            openConnection = { error("retained backup must be retryable") },
        )
        val lease = retryManager.acquire(backupUrl)

        assertEquals(backupFile.canonicalPath, lease?.file?.canonicalPath)
        assertFalse(backup.exists())
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `oversize manifest reads bounded prefix and resets its valid listed paths`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-byte-cap")
        val listed = sparseAsset(directory, "https://cdn.example/listed.mp4", 1L, modified = 1L)
        File(directory, VIDEO_CACHE_MANIFEST_NAME).appendText("x".repeat(100_000))
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(2)), 1L) },
        )

        val lease = manager.acquire("https://cdn.example/after-oversize.mp4")

        assertTrue(lease != null)
        assertFalse(listed.exists())
        assertTrue(File(directory, VIDEO_CACHE_MANIFEST_NAME).length() < 96L * 1024L)
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `invalid state name size and line fail closed without touching unsafe path`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-strict-validation")
        val unsafe = temporaryFolder.newFile("outside.asset").apply { writeText("host") }
        val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        manifest.writeText(
            "SIMULA_VIDEO_CACHE_INDEX_V1\n" +
                "X\tbad.video\t../${unsafe.name}\t-1\t-1\n" +
                "z".repeat(321) + "\n",
        )
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(3)), 1L) },
        )

        val lease = manager.acquire("https://cdn.example/strict-reset.mp4")

        assertTrue(lease != null)
        assertEquals("host", unsafe.readText())
        assertFalse(manifest.readText().contains("../"))
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `startup prunes missing listed file without directory enumeration`() = runTest {
        val directory = temporaryFolder.newFolder("manifest-missing")
        val missingUrl = "https://cdn.example/missing.mp4"
        val missing = sparseAsset(directory, missingUrl, 1L, modified = 1L)
        assertTrue(missing.delete())
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            resolveHost = publicDns,
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(4)), 1L) },
        )

        val lease = manager.acquire("https://cdn.example/present.mp4")

        assertTrue(lease != null)
        assertFalse(File(directory, VIDEO_CACHE_MANIFEST_NAME).readText().contains(opaqueVideoAssetName(missingUrl)))
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `maintenance follow up is paced and finishes beyond one eviction batch`() = runTest {
        val directory = temporaryFolder.newFolder("paced-maintenance")
        val targetUrl = "https://cdn.example/target.mp4"
        repeat(255) { index ->
            sparseAsset(directory, "https://cdn.example/$index.mp4", 1024L * 1024L, modified = 1_000L + index)
        }
        sparseAsset(directory, targetUrl, 1024L * 1024L, modified = 10_000L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 0L },
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            scope = this,
        )

        val lease = manager.acquire(targetUrl)
        val afterFirstBatch = indexedAssetFiles(directory).sumOf(File::length)
        assertTrue(lease != null)
        assertTrue(afterFirstBatch > VIDEO_CACHE_MAX_BYTES)

        advanceTimeBy(49L)
        assertEquals(afterFirstBatch, indexedAssetFiles(directory).sumOf(File::length))
        advanceTimeBy(1L)
        advanceUntilIdle()
        assertTrue(indexedAssetFiles(directory).sumOf(File::length) <= VIDEO_CACHE_MAX_BYTES)
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `fresh indexed partial survives cleanup and counts against capacity`() = runTest {
        val directory = temporaryFolder.newFolder("foreign-partial")
        val assetName = opaqueVideoAssetName("https://cdn.example/foreign.mp4")
        val foreign = File(directory, "$assetName.other.1.asset")
        RandomAccessFile(foreign, "rw").use { it.setLength(50L * 1024L * 1024L) }
        foreign.setLastModified(1_000L)
        File(directory, VIDEO_CACHE_MANIFEST_NAME).writeText(
            "SIMULA_VIDEO_CACHE_INDEX_V1\nP\t$assetName\t${foreign.name}\t${50L * 1024L * 1024L}\t1000\n",
        )
        val activeUrl = "https://cdn.example/active-partial-cap.mp4"
        val activeFile = sparseAsset(directory, activeUrl, 50L * 1024L * 1024L, modified = 1_000L)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1_000L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            processPartId = "current",
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ZeroInputStream(1L), 1L)
            },
        )

        val active = requireNotNull(manager.acquire(activeUrl))
        assertNull(manager.acquire("https://cdn.example/new.mp4"))
        assertTrue(foreign.exists())
        assertTrue(activeFile.exists())
        active.release()
        advanceUntilIdle()
    }

    @Test
    fun `partial is indexed before asset file creation and publishes atomically`() = runTest {
        val directory = temporaryFolder.newFolder("indexed-before-create")
        val checkedBeforeCreate = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val url = "https://cdn.example/manifest-protocol.mp4"
        val assetName = opaqueVideoAssetName(url)
        val manager = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            processPartId = "process-a",
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(BlockingInputStream(byteArrayOf(1), started, release), 1L)
            },
            onFileOperation = {
                val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
                val partialLine = manifest.takeIf(File::isFile)?.readText()?.contains("P\t$assetName\t") == true
                val physicalExists = manifest.takeIf(File::isFile)?.readLines()?.drop(1)
                    ?.mapNotNull { line -> line.split('\t').getOrNull(2) }
                    ?.any { File(directory, it).exists() } == true
                if (partialLine && !physicalExists) checkedBeforeCreate.set(true)
            },
        )
        val acquisition = async(Dispatchers.Default) { manager.acquire(url) }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        assertTrue(checkedBeforeCreate.get())
        assertFalse(manifestHasComplete(directory, url))

        release.countDown()
        val lease = requireNotNull(acquisition.await())
        assertTrue(manifestHasComplete(directory, url))
        assertTrue(lease.file.exists())
        lease.release()
        managerScope.cancel()
        dispatcher.close()
    }

    @Test
    fun `deadline watchdog disconnects active connection and cancels its task`() = runTest {
        var now = 0L
        val scheduler = ManualDeadlineScheduler()
        val enteredResponse = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = DeadlineConnection(enteredResponse, disconnected)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("watchdog"),
            elapsedRealtimeMs = { now },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            deadlineScheduler = scheduler,
            resolveHost = publicDns,
            openConnection = { connection },
        )

        val acquisition = async(Dispatchers.Default) { manager.acquire("https://cdn.example/deadline.mp4") }
        assertTrue(withContext(Dispatchers.IO) { enteredResponse.await(5, TimeUnit.SECONDS) })
        now = VIDEO_DOWNLOAD_TIMEOUT_MS
        scheduler.fire()

        assertNull(acquisition.await())
        assertEquals(1, connection.disconnects.get())
        assertEquals(1, scheduler.cancelCount.get())
        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `timed out generation cannot publish after replacement starts`() = runBlocking {
        val directory = temporaryFolder.newFolder("publication-generation")
        val firstAtPublish = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAtPublish = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val publicationCalls = AtomicInteger()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val clock = TestCoroutineScheduler()
        val callerDispatcher = StandardTestDispatcher(clock)
        val manager = VideoAssetCacheManager(
            directory = directory,
            downloadTimeoutMs = VIDEO_DOWNLOAD_TIMEOUT_MS,
            elapsedRealtimeMs = { clock.currentTime },
            ioDispatcher = dispatcher,
            scope = managerScope,
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3L)
            },
            beforePublish = {
                if (publicationCalls.incrementAndGet() == 1) {
                    firstAtPublish.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } else {
                    secondAtPublish.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                }
            },
        )
        val url = "https://cdn.example/generation.mp4"
        val first = async(callerDispatcher) { manager.acquire(url) }
        clock.runCurrent()
        assertTrue(firstAtPublish.await(5, TimeUnit.SECONDS))
        // Expire only the first waiter after its worker reaches the publication barrier.
        // The replacement keeps the normal budget, independent of CI scheduling speed.
        clock.advanceTimeBy(VIDEO_DOWNLOAD_TIMEOUT_MS)
        clock.runCurrent()
        assertNull(first.await())

        val replacement = async(Dispatchers.Default) { manager.acquire(url) }
        releaseFirst.countDown()
        assertTrue(secondAtPublish.await(2, TimeUnit.SECONDS))
        assertFalse("stale worker published a complete entry", manifestHasComplete(directory, url))

        releaseSecond.countDown()
        val lease = replacement.await()
        assertTrue(lease != null)
        assertTrue(lease?.file?.exists() == true)
        lease?.release()
        managerScope.cancel()
        dispatcher.close()
    }

    @Test
    fun `timeout after atomic move removes unclaimed canonical publication`() = runBlocking {
        val directory = temporaryFolder.newFolder("post-move-timeout")
        val published = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = directory,
            downloadTimeoutMs = 75L,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3L)
            },
            afterPublish = {
                published.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            },
        )
        val url = "https://cdn.example/post-move.mp4"
        val acquisition = async(Dispatchers.Default) { manager.acquire(url) }
        assertTrue(published.await(2, TimeUnit.SECONDS))

        assertNull(acquisition.await())
        assertTrue(indexedAssetFiles(directory).isEmpty())

        releaseWorker.countDown()
        managerScope.cancel()
        dispatcher.close()
    }

    @Test
    fun `more than sixteen simultaneous distinct callers are admitted atomically`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CountDownLatch(16)
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("bounded-pending"),
            downloadTimeoutMs = 5_000L,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            hostResolver = DeadlineHostResolver { _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                publicDns("cdn.example")
            },
            openConnection = { FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L) },
        )
        val waiters = (0 until 32).map { index ->
            async(Dispatchers.Default) {
                manager.acquireResult("https://cdn.example/$index.mp4").also { result ->
                    if (result == VideoAssetCacheResult.Failed(VideoAssetCacheError.ADMISSION_OVERFLOW)) {
                        rejected.countDown()
                    }
                }
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // Keep admitted downloads blocked until every excess caller has attempted
        // admission. Releasing at 16 pending alone lets late callers reuse freed slots.
        assertTrue("excess callers must be rejected before downloads finish", rejected.await(2, TimeUnit.SECONDS))
        assertEquals(16, manager.pendingAcquireCount())

        release.countDown()
        val results = waiters.map { it.await() }
        assertEquals(16, results.count { it is VideoAssetCacheResult.Ready })
        assertEquals(
            16,
            results.count { it == VideoAssetCacheResult.Failed(VideoAssetCacheError.ADMISSION_OVERFLOW) },
        )
        results.filterIsInstance<VideoAssetCacheResult.Ready>().forEach { it.lease.release() }
        managerScope.cancel()
        dispatcher.close()
    }

    @Test
    fun `more than sixteen simultaneous same key callers are admitted atomically`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CountDownLatch(16)
        val opens = AtomicInteger()
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager = VideoAssetCacheManager(
            directory = temporaryFolder.newFolder("bounded-pending-same-key"),
            downloadTimeoutMs = 5_000L,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = managerScope,
            hostResolver = DeadlineHostResolver { _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                publicDns("cdn.example")
            },
            openConnection = {
                opens.incrementAndGet()
                FakeConnection(ByteArrayInputStream(byteArrayOf(1)), 1L)
            },
        )
        val url = "https://cdn.example/shared-admission.mp4"
        val waiters = (0 until 32).map {
            async(Dispatchers.Default) {
                manager.acquireResult(url).also { result ->
                    if (result == VideoAssetCacheResult.Failed(VideoAssetCacheError.ADMISSION_OVERFLOW)) {
                        rejected.countDown()
                    }
                }
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // Keep admitted downloads blocked until every excess caller has attempted
        // admission. Releasing at 16 pending alone lets late callers reuse freed slots.
        assertTrue("excess callers must be rejected before downloads finish", rejected.await(2, TimeUnit.SECONDS))
        assertEquals(16, manager.pendingAcquireCount())

        release.countDown()
        val results = waiters.map { it.await() }
        assertEquals(16, results.count { it is VideoAssetCacheResult.Ready })
        assertEquals(
            16,
            results.count { it == VideoAssetCacheResult.Failed(VideoAssetCacheError.ADMISSION_OVERFLOW) },
        )
        assertEquals(1, opens.get())
        results.filterIsInstance<VideoAssetCacheResult.Ready>().forEach { it.lease.release() }
        managerScope.cancel()
        dispatcher.close()
    }

    private fun seededAssetFile(directory: File, url: String, size: Long, modified: Long): File {
        val assetName = opaqueVideoAssetName(url)
        return File(directory, "$assetName.seed.${seededAssetId.incrementAndGet()}.asset").also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            file.setLastModified(modified)
        }
    }

    private fun manifestText(file: File, url: String): String = manifestText(listOf(file to url))

    private fun manifestText(files: List<Pair<File, String>>): String = buildString {
        append("SIMULA_VIDEO_CACHE_INDEX_V1\n")
        files.forEach { (file, url) ->
            append("C\t${opaqueVideoAssetName(url)}\t${file.name}\t${file.length()}\t1\n")
        }
    }

    private fun sparseAsset(directory: File, url: String, size: Long, modified: Long): File {
        val file = seededAssetFile(directory, url, size, modified)
        val assetName = opaqueVideoAssetName(url)
        val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        if (!manifest.exists()) manifest.writeText("SIMULA_VIDEO_CACHE_INDEX_V1\n")
        manifest.appendText("C\t$assetName\t${file.name}\t$size\t$modified\n")
        return file
    }

    private fun indexedAssetFiles(directory: File): List<File> {
        val manifest = File(directory, VIDEO_CACHE_MANIFEST_NAME)
        if (!manifest.isFile) return emptyList()
        return manifest.readLines().drop(1).mapNotNull { line ->
            line.split('\t').getOrNull(2)?.let { File(directory, it) }
        }.filter(File::exists)
    }

    private fun manifestHasComplete(directory: File, url: String): Boolean {
        val assetName = opaqueVideoAssetName(url)
        return File(directory, VIDEO_CACHE_MANIFEST_NAME).takeIf(File::isFile)
            ?.readLines().orEmpty().any { it.startsWith("C\t$assetName\t") }
    }

    private class FakeConnection(
        private val body: InputStream,
        private val declaredLength: Long,
        private val status: Int = 200,
    ) : HttpURLConnection(URL("https://cdn.example/video.mp4")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = declaredLength
        override fun getInputStream(): InputStream = body
    }

    private class HeaderRecordingConnection(
        url: String,
        private val status: Int,
        private val body: ByteArray,
        private val location: String? = null,
        private val ambientCookie: String,
    ) : HttpURLConnection(URL(url)) {
        var headersAtConnect: Map<String, List<String>> = emptyMap()
            private set
        var effectiveCookie: String? = null
            private set
        var effectiveCookie2: String? = null
            private set

        override fun connect() {
            headersAtConnect = requestProperties.mapValues { (_, values) -> values.toList() }
            effectiveCookie = getRequestProperty("Cookie") ?: ambientCookie
            effectiveCookie2 = getRequestProperty("Cookie2") ?: ambientCookie
        }

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
    }

    private class ManualDeadlineScheduler : VideoDeadlineScheduler {
        private val scheduled = AtomicReference<(() -> Unit)?>(null)
        val cancelCount = AtomicInteger()

        override fun schedule(delayMs: Long, task: () -> Unit): VideoDeadlineCancellation {
            scheduled.set(task)
            return VideoDeadlineCancellation { cancelCount.incrementAndGet() }
        }

        fun fire() {
            requireNotNull(scheduled.get()).invoke()
        }
    }

    private class DeadlineConnection(
        private val entered: CountDownLatch,
        private val disconnected: CountDownLatch,
    ) : HttpURLConnection(URL("https://cdn.example/deadline.mp4")) {
        val disconnects = AtomicInteger()
        override fun connect() = Unit
        override fun disconnect() {
            disconnects.incrementAndGet()
            disconnected.countDown()
        }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            entered.countDown()
            disconnected.await(5, TimeUnit.SECONDS)
            return 200
        }
        override fun getContentLengthLong(): Long = 1L
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf(1))
    }

    private class BlockingInputStream(
        bytes: ByteArray,
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var admitted = false

        override fun read(): Int {
            awaitRelease()
            return delegate.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            awaitRelease()
            return delegate.read(buffer, offset, length)
        }

        private fun awaitRelease() {
            if (admitted) return
            admitted = true
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
    }

    private class ZeroInputStream(private val total: Long) : InputStream() {
        private var emitted = 0L
        override fun read(): Int = if (emitted++ < total) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted >= total) return -1
            val count = minOf(length.toLong(), total - emitted).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
            emitted += count
            return count
        }
    }

    private class CountingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        val bulkReads = AtomicInteger()
        override fun read(): Int = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            bulkReads.incrementAndGet()
            return delegate.read(buffer, offset, length)
        }
    }
}
