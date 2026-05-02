package org.subsound.persistence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.subsound.integration.ServerClient.StreamResponse;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.persistence.SongCache.CacheResult;
import org.subsound.persistence.SongCache.CacheSong;
import org.subsound.persistence.SongCache.LoadSongResult;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public class SongCacheTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Minimal CacheSong factory for tests. */
    private static CacheSong cacheSong(String songId, String streamFormat) {
        var transcodeInfo = new TranscodeInfo(
                songId,
                Optional.of(320),
                128,
                Duration.ofSeconds(60),
                streamFormat
        );
        return new CacheSong(
                "server-1",
                songId,
                transcodeInfo,
                "mp3",
                1000L,
                (total, count) -> {}
        );
    }

    @Test
    public void testConcurrentGetSong_dedupesToSingleDownload() throws Exception {
        var dataDir = folder.newFolder("data").toPath();
        var openCount = new AtomicInteger(0);
        var releaseFirst = new CountDownLatch(1);
        var firstInsideOpener = new CountDownLatch(1);

        Function<TranscodeInfo, StreamResponse> opener = ti -> {
            int n = openCount.incrementAndGet();
            if (n == 1) {
                // First call: signal we're inside, then block until released.
                firstInsideOpener.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("test latch timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // Same bytes for any caller that does open the stream.
            byte[] bytes = "hello-song-bytes".getBytes();
            return new StreamResponse(ti.songId(), new ByteArrayInputStream(bytes), bytes.length, "audio/mpeg");
        };

        var cache = new SongCache(dataDir, opener, 4);
        var song = cacheSong("song-A", "mp3");

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CompletableFuture<LoadSongResult> first = CompletableFuture.supplyAsync(
                    () -> cache.getSong(song), pool);

            // Wait for thread A to be inside the opener (file doesn't exist yet,
            // A holds the songId lock, the actual download is paused).
            assertThat(firstInsideOpener.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<LoadSongResult> second = CompletableFuture.supplyAsync(
                    () -> cache.getSong(song), pool);

            // Give the second thread a moment to reach the lock. It should be
            // blocked on the songId lock, NOT inside the opener.
            Thread.sleep(200);
            assertThat(openCount.get()).isEqualTo(1);

            // Let the first download complete.
            releaseFirst.countDown();

            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.result()).isEqualTo(CacheResult.MISS);
            assertThat(secondResult.result()).isEqualTo(CacheResult.HIT);
            // Critically: only one network fetch happened.
            assertThat(openCount.get()).isEqualTo(1);
        }
    }

    @Test
    public void testMaxConcurrentDownloads_throttlesDistinctSongs() throws Exception {
        var dataDir = folder.newFolder("data").toPath();
        int permits = 2;
        int total = 5;

        var inFlight = new AtomicInteger(0);
        var maxObservedInFlight = new AtomicInteger(0);
        var perSongLatches = new ConcurrentHashMap<String, CountDownLatch>();

        Function<TranscodeInfo, StreamResponse> opener = ti -> {
            int now = inFlight.incrementAndGet();
            maxObservedInFlight.accumulateAndGet(now, Math::max);
            try {
                // Hold the slot until the test releases this song.
                var latch = perSongLatches.computeIfAbsent(ti.songId(), k -> new CountDownLatch(1));
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("test latch timed out for " + ti.songId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            byte[] bytes = ("song-" + ti.songId()).getBytes();
            return new StreamResponse(ti.songId(), new ByteArrayInputStream(bytes), bytes.length, "audio/mpeg");
        };

        var cache = new SongCache(dataDir, opener, permits);

        try (ExecutorService pool = Executors.newFixedThreadPool(total)) {
            CompletableFuture<?>[] futures = new CompletableFuture[total];
            for (int i = 0; i < total; i++) {
                var song = cacheSong("song-" + i, "mp3");
                futures[i] = CompletableFuture.supplyAsync(() -> cache.getSong(song), pool);
            }

            // Let things settle into the steady state where exactly `permits`
            // are in-flight and the rest are queued on the semaphore.
            Thread.sleep(300);
            assertThat(inFlight.get()).isLessThanOrEqualTo(permits);
            assertThat(maxObservedInFlight.get()).isEqualTo(permits);

            // Release all songs so the test can complete.
            for (int i = 0; i < total; i++) {
                perSongLatches.computeIfAbsent("song-" + i, k -> new CountDownLatch(1)).countDown();
            }
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            // Final assertion: at no point did more than `permits` distinct
            // downloads run concurrently.
            assertThat(maxObservedInFlight.get()).isEqualTo(permits);
        }
    }

    @Test
    public void testDownloadLocks_areRemovedAfterUse() throws Exception {
        var dataDir = folder.newFolder("data").toPath();

        Function<TranscodeInfo, StreamResponse> opener = ti -> {
            byte[] bytes = ("data-" + ti.songId()).getBytes();
            return new StreamResponse(ti.songId(), new ByteArrayInputStream(bytes), bytes.length, "audio/mpeg");
        };

        var cache = new SongCache(dataDir, opener, 4);
        assertThat(cache.activeDownloadLockCount()).isZero();

        // Sequential downloads of distinct songs, each acquire/release
        // should leave the lock map empty.
        for (int i = 0; i < 100; i++) {
            cache.getSong(cacheSong("song-" + i, "mp3"));
        }
        assertThat(cache.activeDownloadLockCount()).isZero();

        // Repeated HITs on the same song never grow the map either.
        for (int i = 0; i < 10; i++) {
            cache.getSong(cacheSong("song-0", "mp3"));
        }
        assertThat(cache.activeDownloadLockCount()).isZero();
    }

    @Test
    public void testDownloadLocks_concurrentWaitersShareLockAndCleanUp() throws Exception {
        var dataDir = folder.newFolder("data").toPath();
        var releaseFirst = new CountDownLatch(1);
        var firstInsideOpener = new CountDownLatch(1);
        var openCount = new AtomicInteger(0);

        Function<TranscodeInfo, StreamResponse> opener = ti -> {
            int n = openCount.incrementAndGet();
            if (n == 1) {
                firstInsideOpener.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new RuntimeException("test latch timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = "shared".getBytes();
            return new StreamResponse(ti.songId(), new ByteArrayInputStream(bytes), bytes.length, "audio/mpeg");
        };

        var cache = new SongCache(dataDir, opener, 4);
        var song = cacheSong("song-shared", "mp3");

        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            var f1 = CompletableFuture.supplyAsync(() -> cache.getSong(song), pool);
            assertThat(firstInsideOpener.await(5, TimeUnit.SECONDS)).isTrue();
            // Two waiters arrive while the first is mid-download. With the
            // refcount approach the entry should be in the map with refs=3.
            var f2 = CompletableFuture.supplyAsync(() -> cache.getSong(song), pool);
            var f3 = CompletableFuture.supplyAsync(() -> cache.getSong(song), pool);

            // Give waiters time to enter compute() and reach lock().
            Thread.sleep(200);
            assertThat(cache.activeDownloadLockCount()).isEqualTo(1);

            releaseFirst.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            f3.get(5, TimeUnit.SECONDS);

            // After all callers release, the entry must be gone.
            assertThat(cache.activeDownloadLockCount()).isZero();
        }
    }
}
