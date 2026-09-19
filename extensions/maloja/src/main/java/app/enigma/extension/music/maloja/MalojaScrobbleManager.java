package app.enigma.extension.music.maloja;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Tracks the current song from the media session and scrobbles it to Maloja once enough of it
 * has played. Mirrors the timer rules of the official Morphe scrobbling patch.
 * <p>
 * All methods must be called on the main thread.
 */
public class MalojaScrobbleManager {
    private static MalojaScrobbleManager instance;

    public static MalojaScrobbleManager getInstance() {
        Utils.verifyOnMainThread();
        if (instance == null) {
            instance = new MalojaScrobbleManager();
        }
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Metadata of the current track, kept so it can be read again as the song of an album. */
    private MediaMetadata currentMetadata;

    private String currentTitle;
    private String currentArtist;
    private String currentAlbum;
    private int currentDurationSeconds;

    private long songStartedAtSeconds;
    private boolean songStarted;
    private boolean isPlayerPlaying;

    private long scrobbleRemainingMillis;
    private long scrobbleTimerStartedAt;
    private boolean scrobbled;
    private Runnable scrobbleRunnable;

    private MalojaScrobbleManager() {
        AlbumSongBridge.addChangeListener(this::reloadCurrentTrack);
    }

    /**
     * The song of an album, and the video id of the app itself, can both land after the metadata
     * of a track, so the track is read again whenever either of them arrives.
     */
    private void reloadCurrentTrack() {
        Utils.runOnMainThread(() -> {
            MediaMetadata metadata = currentMetadata;
            if (metadata != null) {
                onSetMetadata(metadata);
            }
        });
    }

    public void onSetMetadata(MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) return;

        currentMetadata = metadata;

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        final String album = MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        int duration = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);

        // With the official "Play album songs" patch the metadata still describes the music video,
        // which names another version of the song and is minutes longer. Only the artist is the same.
        AlbumSongBridge.Song song = AlbumSongBridge.currentSong();
        final boolean songDurationKnown = song != null && song.durationSeconds > 0;
        if (song != null) {
            if (song.title != null && !song.title.isBlank()) {
                rawTitle = song.title;
            }
            if (songDurationKnown) {
                duration = song.durationSeconds;
            }
        }

        final String[] resolved = MetadataCleaner.resolveTitleAndArtist(rawTitle, rawArtist);
        final String title = resolved[0];
        final String artist = resolved[1];
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return;
        }

        if (!title.equals(currentTitle) || !artist.equals(currentArtist)) {
            Logger.printDebug(() -> "New song detected: " + title + " - " + artist);
            stopTimer();
            songStarted = false;
            scrobbled = false;

            currentTitle = title;
            currentArtist = artist;
            currentAlbum = album;
            currentDurationSeconds = duration;

            if (isPlayerPlaying) {
                onSongStart();
            }
            return;
        }

        // Same song, but the duration or album may only arrive with a later metadata update.
        // The album song is authoritative, since it is shorter than the video it replaces.
        final boolean durationChanged = songDurationKnown
                ? duration != currentDurationSeconds
                : duration > currentDurationSeconds;
        if (durationChanged) {
            final int newDuration = duration;
            Logger.printDebug(() -> "Updated duration for " + title + ": " + newDuration + "s");
            currentDurationSeconds = duration;
        }
        if (album != null && !album.isBlank() && (currentAlbum == null || currentAlbum.isBlank())) {
            currentAlbum = album;
        }

        if (durationChanged && songStarted && !scrobbled && MalojaSettings.ENABLED.get()) {
            // Recompute the timer from the song start with the corrected duration, whether the
            // timer was skipped for an unknown duration or is still running with the old one.
            startTimer();
        }
    }

    public void onSetPlaybackState(PlaybackState state) {
        Utils.verifyOnMainThread();
        if (state == null) return;
        isPlayerPlaying = state.getState() == PlaybackState.STATE_PLAYING;

        if (currentTitle == null || currentArtist == null) return;

        if (isPlayerPlaying) {
            if (!songStarted) {
                onSongStart();
            } else {
                onSongResume();
            }
        } else if (songStarted) {
            pauseTimer();
        }
    }

    private void onSongStart() {
        songStartedAtSeconds = System.currentTimeMillis() / 1000;
        songStarted = true;

        if (MalojaSettings.ENABLED.get()) {
            startTimer();
        }
    }

    private void onSongResume() {
        if (MalojaSettings.ENABLED.get() && !scrobbled && scrobbleRemainingMillis > 0) {
            cancelRunnable();
            scrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleScrobble(scrobbleRemainingMillis);
        }
    }

    private void startTimer() {
        cancelRunnable();

        final int minSongDuration = MalojaSettings.MIN_SONG_DURATION.get();
        if (currentDurationSeconds <= minSongDuration) {
            Logger.printDebug(() -> "Duration " + currentDurationSeconds
                    + "s <= minimum " + minSongDuration + "s, skipping scrobble");
            return;
        }

        final float delayPercent = MalojaSettings.DELAY_PERCENT.get() / 100.0f;
        final int delaySeconds = MalojaSettings.DELAY_SECONDS.get();

        final long thresholdMs = (long) (currentDurationSeconds * 1000L * delayPercent);
        final long totalDelayMs = Math.min(thresholdMs, delaySeconds * 1000L);
        final long elapsedMs = Math.max(0, System.currentTimeMillis() - songStartedAtSeconds * 1000L);

        scrobbleRemainingMillis = totalDelayMs - elapsedMs;

        if (scrobbleRemainingMillis <= 0) {
            scrobble();
            return;
        }

        if (isPlayerPlaying) {
            scrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleScrobble(scrobbleRemainingMillis);
        } else {
            scrobbleTimerStartedAt = 0L;
        }
    }

    private void pauseTimer() {
        cancelRunnable();
        if (scrobbleTimerStartedAt != 0L) {
            final long elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt;
            scrobbleRemainingMillis = Math.max(0, scrobbleRemainingMillis - elapsed);
            scrobbleTimerStartedAt = 0L;
        }
    }

    private void stopTimer() {
        cancelRunnable();
        scrobbleRemainingMillis = 0L;
        scrobbleTimerStartedAt = 0L;
    }

    private void scheduleScrobble(long delayMs) {
        scrobbleRunnable = () -> {
            scrobbleRunnable = null;
            scrobble();
        };
        handler.postDelayed(scrobbleRunnable, delayMs);
    }

    private void cancelRunnable() {
        if (scrobbleRunnable != null) {
            handler.removeCallbacks(scrobbleRunnable);
            scrobbleRunnable = null;
        }
    }

    private void scrobble() {
        if (scrobbled) return;
        scrobbled = true;

        final String serverUrl = MalojaSettings.SERVER_URL.get();
        final String apiKey = MalojaSettings.API_KEY.get();
        final String artist = currentArtist;
        final String track = currentTitle;
        final String album = currentAlbum;
        final int duration = currentDurationSeconds;
        final long listenedAt = songStartedAtSeconds;

        executor.submit(() -> {
            try {
                if (Maloja.scrobble(serverUrl, apiKey, artist, track, album, duration, listenedAt)) {
                    Logger.printDebug(() -> "Scrobbled to Maloja: '" + track + "' by " + artist);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Maloja scrobble failure", ex);
            }
        });
    }
}
