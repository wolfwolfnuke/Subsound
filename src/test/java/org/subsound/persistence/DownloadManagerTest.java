package org.subsound.persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.subsound.persistence.DownloadManager.DownloadManagerEvent;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.subsound.utils.Utils.sha256;

public class DownloadManagerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    public MockMusicServer mockMusicServer;
    @Before
    public void before() {
        mockMusicServer = new MockMusicServer();
    }

    @After
    public void tearDown() {
        mockMusicServer.stop();
        mockMusicServer = null;
    }

    @Test
    public void testDownloadQueueFlow() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);

        var eventList = new ArrayList<DownloadManagerEvent>();
        DownloadManager downloadManager = new DownloadManager(fx.dbService, fx.songCache, () -> null);
        downloadManager.subscribe(eventList::add);
        downloadManager.enqueue(songInfo);

        var allStatuses = java.util.List.of(DownloadStatus.values());
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 15000) {
            var items = fx.dbService.listDownloadQueue(allStatuses);
            if (!items.isEmpty() && items.stream().allMatch(it -> it.status().isDownloaded())) {
                break;
            }
            Thread.sleep(100);
        }

        var items = fx.dbService.listDownloadQueue(allStatuses);
        assertThat(items).hasSize(1);
        assertThat(items).allMatch(it -> it.status() == DownloadStatus.COMPLETED);
        var item = items.getFirst();
        assertThat(item.streamFormat()).isEqualTo("mp3");
        assertThat(item.estimatedBitRate()).isEqualTo(128);
        assertThat(item.checksum()).isPresent();
        assertThat(cacheFile(fx, songInfo.id(), "mp3")).exists();

        downloadManager.stop();
    }

    @Test
    public void testLoadForPlayback_newSong_storesAsCached() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("opus", 96);

        // currentTranscodeInfo returns "opus", we must use this for new songs,
        // not whatever format the SongInfo carries:
        ServerClient client = mockClientReturning("opus", 96);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);

        var result = dm.loadForPlayback(songInfo, (total, count) -> {});
        dm.stop();

        assertThat(result.result()).isEqualTo(SongCache.CacheResult.MISS);

        var stored = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DownloadStatus.CACHED);
        assertThat(stored.streamFormat()).isEqualTo("opus");
        assertThat(stored.estimatedBitRate()).isEqualTo(96);
        assertThat(stored.checksum()).isPresent();
        assertThat(cacheFile(fx, songInfo.id(), "opus")).exists();
    }

    @Test
    public void testLoadForPlayback_currentTranscodeInfoOverridesSongInfo() throws Exception {
        Fixture fx = newFixture();
        // SongInfo carries a stale "mp3" format from the moment it was constructed.
        SongInfo stale = sampleSongInfo("mp3", 128);

        // Live client now reports "opus". The cache key must follow the live policy,
        // not the stale SongInfo
        ServerClient client = mockClientReturning("opus", 96);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm.loadForPlayback(stale, (total, count) -> {});
        dm.stop();

        var stored = fx.dbService.getDownloadQueueItem(stale.id()).orElseThrow();
        assertThat(stored.streamFormat()).isEqualTo("opus");
        assertThat(stored.estimatedBitRate()).isEqualTo(96);
        assertThat(cacheFile(fx, stale.id(), "opus")).exists();
        assertThat(cacheFile(fx, stale.id(), "mp3")).doesNotExist();
    }

    @Test
    public void testLoadForPlayback_existingPending_promotesToCompletedKeepingStoredFormat() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);

        // Enqueue with format=mp3. Use a null client supplier so the queue
        // processor isn't racing us.
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> null);
        dm.stop(); // disable background processor
        fx.dbService.addToDownloadQueue(songInfo);
        // Re-create with a live client whose policy is "opus", the stored row should win:
        ServerClient client = mockClientReturning("opus", 96);
        DownloadManager dm2 = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm2.stop(); // disable background processor

        dm2.loadForPlayback(songInfo, (total, count) -> {});

        var stored = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DownloadStatus.COMPLETED);
        assertThat(stored.streamFormat()).isEqualTo("mp3");
        assertThat(stored.estimatedBitRate()).isEqualTo(128);
        assertThat(cacheFile(fx, songInfo.id(), "mp3")).exists();
        assertThat(cacheFile(fx, songInfo.id(), "opus")).doesNotExist();
    }

    @Test
    public void testLoadForPlayback_alreadyCachedReturnsHitWithoutRedownload() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);

        ServerClient client = mockClientReturning("mp3", 128);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm.stop();

        // First call downloads + persists (CACHED).
        var first = dm.loadForPlayback(songInfo, (total, count) -> {});
        assertThat(first.result()).isEqualTo(SongCache.CacheResult.MISS);

        var fileBefore = cacheFile(fx, songInfo.id(), "mp3");
        long mtimeBefore = fileBefore.lastModified();

        // Re-create with a live client whose policy is "opus", the stored row should win:
        ServerClient opusClient = mockClientReturning("opus", 96);
        DownloadManager dm2 = new DownloadManager(fx.dbService, fx.songCache, () -> opusClient);
        dm2.stop(); // disable background processor

        // Second call must HIT with no new bytes written to disk.
        var second = dm2.loadForPlayback(songInfo, (total, count) -> {});

        assertThat(second.result()).isEqualTo(SongCache.CacheResult.HIT);
        assertThat(cacheFile(fx, songInfo.id(), "mp3").lastModified()).isEqualTo(mtimeBefore);
    }

    @Test
    public void testLoadForPlayback_alreadyCachedReturnsHitWithoutRedownload_newFormat() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);

        ServerClient client = mockClientReturning("mp3", 128);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);

        // First call downloads + persists (CACHED) in mp3:
        var first = dm.loadForPlayback(songInfo, (total, count) -> {});
        assertThat(first.result()).isEqualTo(SongCache.CacheResult.MISS);

        var fileBefore = cacheFile(fx, songInfo.id(), "mp3");
        long mtimeBefore = fileBefore.lastModified();

        // Second call must HIT with no new bytes written to disk.
        var second = dm.loadForPlayback(songInfo, (total, count) -> {});
        dm.stop();

        assertThat(second.result()).isEqualTo(SongCache.CacheResult.HIT);
        assertThat(cacheFile(fx, songInfo.id(), "mp3").lastModified()).isEqualTo(mtimeBefore);
    }

    @Test
    public void testIsAvailableOffline() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);
        ServerClient client = mockClientReturning("mp3", 128);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm.stop(); // we'll drive everything synchronously

        // Not yet downloaded → not available.
        assertThat(dm.isAvailableOffline(songInfo)).isFalse();

        // After download (CACHED) → available.
        dm.loadForPlayback(songInfo, (total, count) -> {});
        // Re-create DM to refresh in-memory map from DB (the previous one was stopped).
        DownloadManager dm2 = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm2.stop();
        assertThat(dm2.isAvailableOffline(songInfo)).isTrue();

        // Delete the file under the DM's feet → no longer available.
        assertThat(cacheFile(fx, songInfo.id(), "mp3").delete()).isTrue();
        assertThat(dm2.isAvailableOffline(songInfo)).isFalse();
    }

    @Test
    public void testLoadForPlayback_pendingRow_refreshesFormatBeforeDownload() throws Exception {
        Fixture fx = newFixture();
        // SongInfo's transcodeInfo says mp3, the row gets seeded as mp3 too.
        SongInfo songInfo = sampleSongInfo("mp3", 128);
        insertDBSong(fx, songInfo);
        fx.dbService.addToDownloadQueue(songInfo);

        // Live client now serves opus. effectiveTranscodeInfo for a PENDING row
        // must refresh the row's stored format BEFORE downloading.
        ServerClient client = mockClientReturning("opus", 96);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm.stop(); // disable background processor

        dm.loadForPlayback(songInfo, (total, count) -> {});

        var stored = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DownloadStatus.COMPLETED);
        assertThat(stored.streamFormat()).isEqualTo("opus");
        assertThat(stored.estimatedBitRate()).isEqualTo(96);
        assertThat(cacheFile(fx, songInfo.id(), "opus")).exists();
        assertThat(cacheFile(fx, songInfo.id(), "mp3")).doesNotExist();
    }

    @Test
    public void testQueueProcessor_pendingRow_refreshesFormatToCurrentPolicy() throws Exception {
        Fixture fx = newFixture();
        // Pre-seed: row stamped mp3 (e.g. from a previous transcode setting).
        SongInfo songInfo = sampleSongInfo("mp3", 128);
        insertDBSong(fx, songInfo);
        fx.dbService.addToDownloadQueue(songInfo);

        // Now policy is opus. The processor's tick should refresh the row before
        // downloading, so the on-disk file ends up with the opus suffix.
        ServerClient client = mockClientReturning("opus", 96);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);

        // Wait up to 15s for the queue processor to pick it up.
        var allStatuses = java.util.List.of(DownloadStatus.values());
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 15000) {
            var rows = fx.dbService.listDownloadQueue(allStatuses);
            if (!rows.isEmpty() && rows.stream().allMatch(it -> it.status().isDownloaded())) {
                break;
            }
            Thread.sleep(100);
        }
        dm.stop();

        var stored = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(DownloadStatus.COMPLETED);
        assertThat(stored.streamFormat()).isEqualTo("opus");
        assertThat(stored.estimatedBitRate()).isEqualTo(96);
        assertThat(cacheFile(fx, songInfo.id(), "opus")).exists();
        assertThat(cacheFile(fx, songInfo.id(), "mp3")).doesNotExist();
    }

    @Test
    public void testLoadForPlayback_completedRowWithMissingFile_demotesAndRedownloads() throws Exception {
        Fixture fx = newFixture();
        SongInfo songInfo = sampleSongInfo("mp3", 128);
        insertDBSong(fx, songInfo);

        ServerClient client = mockClientReturning("mp3", 128);
        DownloadManager dm = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm.stop();

        // First, get into the COMPLETED+file-on-disk state via a normal play.
        dm.loadForPlayback(songInfo, (total, count) -> {});
        var first = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(first.status()).isEqualTo(DownloadStatus.CACHED);
        var checksumBefore = first.checksum().orElseThrow();
        var file = cacheFile(fx, songInfo.id(), "mp3");
        assertThat(file).exists();

        // Simulate cache corruption: file vanishes from disk while DB still says
        // it's downloaded.
        assertThat(file.delete()).isTrue();

        // Re-create DM so its in-memory map sees the CACHED state from DB.
        DownloadManager dm2 = new DownloadManager(fx.dbService, fx.songCache, () -> client);
        dm2.stop();
        var events = new ArrayList<DownloadManagerEvent>();
        dm2.subscribe(events::add);

        var result = dm2.loadForPlayback(songInfo, (total, count) -> {});

        // File restored, row promoted back to a downloaded state, fresh checksum
        // computed (even when content is identical, we re-hash and overwrite).
        assertThat(result.result()).isEqualTo(SongCache.CacheResult.MISS);
        assertThat(file).exists();
        var after = fx.dbService.getDownloadQueueItem(songInfo.id()).orElseThrow();
        assertThat(after.status().isDownloaded()).isTrue();
        assertThat(after.checksum()).isPresent();

        assertThat(after.checksum().get()).isEqualTo(checksumBefore);
        assertThat(events).extracting(DownloadManagerEvent::type)
                .contains(DownloadManagerEvent.Type.DOWNLOAD_PENDING);
    }

    private record Fixture(DatabaseServerService dbService, SongCache songCache, Path dataDir, UUID serverId) {}

    private Fixture newFixture() throws Exception {
        File dataDir = folder.newFolder("data");
        File dbFile = new File(dataDir, "test.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        UUID serverId = UUID.randomUUID();
        DatabaseServerService dbService = new DatabaseServerService(serverId, db);
        SongCache songCache = new SongCache(
                dataDir.toPath(),
                ti -> this.mockMusicServer.openStream(ti)
        );
        return new Fixture(dbService, songCache, dataDir.toPath(), serverId);
    }

    private SongInfo sampleSongInfo(String streamFormat, int estimatedBitRate) {
        var songId = mockMusicServer.getSamples().stream().findAny().orElseThrow().songId();
        return ServerClientSongInfoBuilder.builder()
                .id(songId)
                .title("Song One")
                .mainArtist(new ServerClient.ArtistId("artist-1", "Artist Name"))
                .albumId("album-1")
                .album("Album Name")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .suffix("mp3")
                .genre("rock")
                .moods(java.util.List.of())
                .playCount(0L)
                .transcodeInfo(new TranscodeInfo(
                        songId,
                        Optional.of(320),
                        estimatedBitRate,
                        Duration.ofMinutes(3),
                        streamFormat
                ))
                .downloadUri(URI.create("file:///dev/null"))
                .build();
    }

    /** Insert a DBSong row so refreshFormatIfPending can find the song's metadata. */
    private void insertDBSong(Fixture fx, SongInfo songInfo) {
        // Album row required by FK before songs.
        fx.dbService.insert(new org.subsound.persistence.database.Artist(
                songInfo.artistId(), fx.serverId, songInfo.artistName(), 1,
                Optional.empty(), Optional.empty(), Optional.empty()));
        fx.dbService.insert(new org.subsound.persistence.database.Album(
                songInfo.albumId(), fx.serverId, songInfo.artistId(), songInfo.album(), 1,
                songInfo.year(), songInfo.artistName(), songInfo.duration(),
                Optional.empty(), Optional.empty(), java.time.Instant.now(), Optional.empty()));
        fx.dbService.insert(org.subsound.persistence.database.DBSong.from(songInfo, fx.serverId));
    }

    private ServerClient mockClientReturning(String streamFormat, int estimatedBitRate) {
        ServerClient client = mock(ServerClient.class);
        lenient().when(client.currentTranscodeInfo(any(SongInfo.class))).thenAnswer(inv -> {
            SongInfo s = inv.getArgument(0);
            return new TranscodeInfo(
                    s.id(),
                    s.bitRate(),
                    estimatedBitRate,
                    s.duration(),
                    streamFormat
            );
        });
        lenient().when(client.currentTranscodeInfo(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Duration.class),
                org.mockito.ArgumentMatchers.anyString()
        )).thenAnswer(inv -> new TranscodeInfo(
                inv.getArgument(0),
                inv.getArgument(1),
                estimatedBitRate,
                inv.getArgument(2),
                streamFormat
        ));
        return client;
    }

    private File cacheFile(Fixture fx, String songId, String streamFormat) {
        String hash = sha256(songId);
        return fx.dataDir
                .resolve(fx.serverId.toString())
                .resolve("songs")
                .resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4))
                .resolve(hash.substring(4, 6))
                .resolve(songId + "." + streamFormat)
                .toFile();
    }
}
