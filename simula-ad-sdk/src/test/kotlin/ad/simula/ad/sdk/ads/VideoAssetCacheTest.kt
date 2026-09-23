package ad.simula.ad.sdk.ads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
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

    private val publicDns: (String) -> Array<InetAddress> = {
        arrayOf(InetAddress.getByName("8.8.8.8"))
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
    }

    @Test
    fun `load cancellation immediately after acquire releases its owned lease once`() = runTest {
        val releases = AtomicInteger()
        val published = AtomicBoolean(false)
        val result = async {
            acquireVideoLeaseForReady(
                acquire = {
                    VideoAssetLease(temporaryFolder.newFile("cancelled-ready.mp4")) {
                        releases.incrementAndGet()
                    }
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
                VideoAssetLease(temporaryFolder.newFile("stale-ready.mp4")) {
                    releases.incrementAndGet()
                }.also { current = false }
            },
            isCurrent = { current },
            publishReady = { error("stale generation must not publish Ready") },
        )

        assertEquals(VideoReadyLeaseResult.STALE, result)
        assertEquals(1, releases.get())
    }

    @Test
    fun `Ready publication failure releases acquired lease once`() = runTest {
        val releases = AtomicInteger()

        val failure = runCatching {
            acquireVideoLeaseForReady(
                acquire = {
                    VideoAssetLease(temporaryFolder.newFile("rejected-ready.mp4")) {
                        releases.incrementAndGet()
                    }
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
                VideoAssetLease(temporaryFolder.newFile("published-ready.mp4")) {
                    releases.incrementAndGet()
                }
            },
            isCurrent = { true },
            publishReady = { ownership ->
                readyLease = ownership.lease
                ownership.transferToReady()
            },
        )

        assertEquals(VideoReadyLeaseResult.READY, result)
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
                    VideoAssetLease(temporaryFolder.newFile("cancelled-after-ready.mp4")) {
                        releases.incrementAndGet()
                    }
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
        sparseAsset(directory, oldUrl, 50L * 1024L * 1024L, modified = 1L)
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
        assertFalse(File(directory, opaqueVideoAssetName(oldUrl)).exists())
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
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
        first.release()
        second.release()
        advanceUntilIdle()
    }

    @Test
    fun `entry cap fails closed before cache admission`() = runTest {
        val directory = temporaryFolder.newFolder("cleanup-batches")
        repeat(300) { index ->
            File(directory, "orphan-$index.part").apply {
                writeText("partial")
                setLastModified(1L)
            }
        }
        val url = "https://cdn.example/retained.mp4"
        sparseAsset(directory, url, 1L, modified = 2_000_000_000L)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 2_000_000_000L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = dispatcher,
            scope = this,
        )

        val lease = manager.acquire(url)

        assertNull(lease)
        advanceUntilIdle()
        assertTrue(directory.listFiles().orEmpty().size <= 301)
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
        val afterFirstBatch = directory.listFiles().orEmpty().sumOf(File::length)
        assertTrue(lease != null)
        assertTrue(afterFirstBatch > VIDEO_CACHE_MAX_BYTES)

        advanceTimeBy(49L)
        assertEquals(afterFirstBatch, directory.listFiles().orEmpty().sumOf(File::length))
        advanceTimeBy(1L)
        advanceUntilIdle()
        assertTrue(directory.listFiles().orEmpty().sumOf(File::length) <= VIDEO_CACHE_MAX_BYTES)
        lease?.release()
        advanceUntilIdle()
    }

    @Test
    fun `fresh foreign partial survives cleanup and counts against capacity`() = runTest {
        val directory = temporaryFolder.newFolder("foreign-partial")
        val foreign = File(directory, "${opaqueVideoAssetName("https://cdn.example/foreign.mp4")}.other.1.part")
        RandomAccessFile(foreign, "rw").use { it.setLength(60L * 1024L * 1024L) }
        foreign.setLastModified(1_000L)
        val manager = VideoAssetCacheManager(
            directory = directory,
            wallClockMs = { 1_000L },
            elapsedRealtimeMs = { 0L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = this,
            processPartId = "current",
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(ZeroInputStream(50L * 1024L * 1024L), 50L * 1024L * 1024L)
            },
        )

        assertNull(manager.acquire("https://cdn.example/new.mp4"))
        assertTrue(foreign.exists())
    }

    @Test
    fun `different process flights use distinct partial files`() = runTest {
        val directory = temporaryFolder.newFolder("unique-parts")
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val secondDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        fun manager(
            process: String,
            started: CountDownLatch,
            dispatcher: CoroutineDispatcher,
        ) = VideoAssetCacheManager(
            directory = directory,
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            ioDispatcher = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            processPartId = process,
            resolveHost = publicDns,
            openConnection = {
                FakeConnection(BlockingInputStream(byteArrayOf(1), started, release), 1L)
            },
        )
        val firstManager = manager("process-a", firstStarted, firstDispatcher)
        val secondManager = manager("process-b", secondStarted, secondDispatcher)

        val first = async(Dispatchers.Default) { firstManager.acquire("https://cdn.example/shared.mp4") }
        val second = async(Dispatchers.Default) { secondManager.acquire("https://cdn.example/shared.mp4") }
        assertTrue(withContext(Dispatchers.IO) { firstStarted.await(5, TimeUnit.SECONDS) })
        assertTrue(withContext(Dispatchers.IO) { secondStarted.await(5, TimeUnit.SECONDS) })

        val partials = directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertEquals(2, partials.size)
        assertTrue(partials.any { it.name.contains(".process-a.") })
        assertTrue(partials.any { it.name.contains(".process-b.") })

        release.countDown()
        first.await()?.release()
        second.await()?.release()
        firstDispatcher.close()
        secondDispatcher.close()
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
        val destination = File(directory, opaqueVideoAssetName(url))
        val first = async(Dispatchers.Default) { manager.acquire(url) }
        assertTrue(firstAtPublish.await(2, TimeUnit.SECONDS))
        assertNull(first.await())

        val replacement = async(Dispatchers.Default) { manager.acquire(url) }
        releaseFirst.countDown()
        assertTrue(secondAtPublish.await(2, TimeUnit.SECONDS))
        assertFalse("stale worker published the canonical file", destination.exists())

        releaseSecond.countDown()
        val lease = replacement.await()
        assertTrue(lease != null)
        assertTrue(destination.exists())
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
        val destination = File(directory, opaqueVideoAssetName(url))
        val acquisition = async(Dispatchers.Default) { manager.acquire(url) }
        assertTrue(published.await(2, TimeUnit.SECONDS))

        assertNull(acquisition.await())
        assertFalse(destination.exists())

        releaseWorker.countDown()
        managerScope.cancel()
        dispatcher.close()
    }

    @Test
    fun `pending acquire admission is bounded`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
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
        )
        val waiters = (0 until 16).map { index ->
            async(Dispatchers.Default) { manager.acquire("https://cdn.example/$index.mp4") }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (manager.pendingAcquireCount() < 16 && System.nanoTime() < admissionDeadline) Thread.yield()
        assertEquals(16, manager.pendingAcquireCount())

        assertNull(manager.acquire("https://cdn.example/rejected.mp4"))

        waiters.forEach { it.cancel() }
        release.countDown()
        managerScope.cancel()
        dispatcher.close()
    }

    private fun sparseAsset(directory: File, url: String, size: Long, modified: Long): File =
        File(directory, opaqueVideoAssetName(url)).also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            file.setLastModified(modified)
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
