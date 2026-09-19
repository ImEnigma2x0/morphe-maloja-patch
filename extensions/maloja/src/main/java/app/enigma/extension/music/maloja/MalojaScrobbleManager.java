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
    }

    public void onSetMetadata(MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) return;

        final String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        final String album = MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        final int duration = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);

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
        boolean updated = false;
        if (duration > currentDurationSeconds) {
            currentDurationSeconds = duration;
            updated = true;
        }
        if (album != null && !album.isBlank() && (currentAlbum == null || currentAlbum.isBlank())) {
            currentAlbum = album;
            updated = true;
        }

        if (updated && songStarted && isPlayerPlaying && MalojaSettings.ENABLED.get()
                && !scrobbled && scrobbleRunnable == null && scrobbleTimerStartedAt == 0L) {
            // The timer may have been skipped because the duration was unknown at song start.
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
