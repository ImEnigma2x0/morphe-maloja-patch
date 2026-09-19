package app.enigma.extension.music.maloja;

import static app.morphe.extension.shared.settings.Setting.parent;
import static java.lang.Boolean.FALSE;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Settings of the Maloja patch. Persisted in the same preferences as the official Morphe
 * settings, so the Morphe settings screen syncs them with the preferences declared in
 * {@code morphe_addon_prefs.xml}.
 */
public class MalojaSettings {
    public static final BooleanSetting ENABLED = new BooleanSetting("morphe_maloja_enabled", FALSE, true);
    public static final StringSetting SERVER_URL = new StringSetting("morphe_maloja_server_url", "", false, parent(ENABLED));
    public static final StringSetting API_KEY = new StringSetting("morphe_maloja_api_key", "", false, parent(ENABLED));
    public static final IntegerSetting MIN_SONG_DURATION = new IntegerSetting("morphe_maloja_min_song_duration", 30, false, parent(ENABLED));
    public static final IntegerSetting DELAY_PERCENT = new IntegerSetting("morphe_maloja_delay_percent", 50, false, parent(ENABLED));
    public static final IntegerSetting DELAY_SECONDS = new IntegerSetting("morphe_maloja_delay_seconds", 180, false, parent(ENABLED));

    /**
     * Metadata cleanup settings of the official Scrobbling patch, read by key so the same
     * cleanup applies to every provider. Defaults match the official patch when it is absent.
     */
    static boolean metadataCleanup() {
        return Setting.preferences.getBoolean("morphe_music_scrobbling_metadata_cleanup", true);
    }

    static String customRegex() {
        return Setting.preferences.getString("morphe_music_scrobbling_custom_regex", "");
    }

    static boolean parseTitle() {
        return Setting.preferences.getBoolean("morphe_music_scrobbling_parse_title", false);
    }

    static boolean isConfigured() {
        return !SERVER_URL.get().isBlank() && !API_KEY.get().isBlank();
    }

    private MalojaSettings() {
    }
}
