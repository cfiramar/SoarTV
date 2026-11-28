package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Settings changed on the TV itself (SoarTV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** TCL's Settings button: NOTIFICATION, which only Google TV's home app (off in our mode) answers. */
    static final int KEY_TCL_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    /** A TCL Settings button's value that opens our quick settings panel over what's playing. */
    static final String QUICK_PANEL = HomeMode.PKG + "#panel";
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Xiaomi's PatchWall button: BUTTON_5, which the TV hands to the same receiver to open PatchWall (its log: "192"). */
    static final int KEY_XIAOMI_PATCHWALL = KeyEvent.KEYCODE_BUTTON_5;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /** The SonyLIV button of the BRAVIA VU31's remote (India, Google TV 14), a key code of its own (#395). */
    static final int KEY_SONY_LIV = 3004;
    /** The gear on Sony's Google TV remotes (BRAVIA 8 II): NOTIFICATION, which only launcherx's dashboard answers. */
    static final int KEY_SONY_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;

    /**
     * A Sony app button by scan code. The BRAVIA VU31's remote (Google TV 14, #395) sends other codes for the same
     * buttons: BUTTON_4 with 758 for Netflix, BUTTON_2 with 652 for Prime Video, BUTTON_15 with 653 for YouTube.
     */
    static int sonyKey(int scanCode) {
        switch (scanCode) {
            case 758: return KEY_SONY_NETFLIX;
            case 652: return KEY_SONY_PRIME;
            case 653: return KEY_SONY_YOUTUBE;
            default: return SONY_KEY + scanCode;
        }
    }
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    /**
     * Xiaomi's MiTV-AFKR0 remote (RemoteKeys.XIAOMI_AFKR0): "Mi TV+" is BUTTON_5 (PatchWall), Netflix is BUTTON_6,
     * Prime Video is BUTTON_7, and YouTube is BUTTON_16.
     */
    static final int KEY_XIAOMI_NETFLIX = KeyEvent.KEYCODE_BUTTON_6, KEY_XIAOMI_PRIME = KeyEvent.KEYCODE_BUTTON_7,
            KEY_XIAOMI_YOUTUBE = KeyEvent.KEYCODE_BUTTON_16;
    /**
     * Hisense's buttons (Q6N, MediaTek, Android TV 12 tested; read from the key filter): global keys for Google TV's
     * home app (launcherx), or Hisense's 607 for Disney+. The gear sends NOTIFICATION; ALL_APPS (284) and PROFILE_SWITCH
     * (288) are API 28/29 names, so numbers here.
     */
    static final int KEY_HISENSE_PRIME = 295, KEY_HISENSE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_HISENSE_DISNEY = 607,
            KEY_HISENSE_APPS = 284, KEY_HISENSE_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION, KEY_HISENSE_PROFILE = 288;
    /**
     * Philips' Google TV remote (RemoteKeys.PHILIPS_GTV, TP Vision 65OLED910): TP Vision keycodes
     * (GLOBAL_FUNCTION 811, LOCAL_OTT1 816, NETFLIX 475, YOUTUBE 781, AMAZON_PRIME_VIDEO 788).
     */
    static final int KEY_PHILIPS_SETTINGS = 811, KEY_PHILIPS_APPS = 816, KEY_PHILIPS_GUIDE = KeyEvent.KEYCODE_GUIDE,
            KEY_PHILIPS_NETFLIX = 475, KEY_PHILIPS_YOUTUBE = 781, KEY_PHILIPS_PRIME = 788;
    /** Google TV Streamer's Vendor_18d1_Product_9451.kl; MACRO_1 (313) was added in API 34. */
    static final int KEY_GOOGLE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_GOOGLE_NETFLIX = KeyEvent.KEYCODE_BUTTON_4,
            KEY_GOOGLE_CUSTOM = 313;
    /**
     * Walmart onn 4K Pro remote (Vendor_0957_Product_0031.kl, Google TV 14): YouTube (BUTTON_3) and Free TV (297)
     * target launcherx; Netflix, Disney+, and Paramount+ use BUTTON_4, BUTTON_9, and BUTTON_10.
     */
    static final int KEY_ONN_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_ONN_NETFLIX = KeyEvent.KEYCODE_BUTTON_4,
            KEY_ONN_DISNEY = KeyEvent.KEYCODE_BUTTON_9, KEY_ONN_PARAMOUNT = KeyEvent.KEYCODE_BUTTON_10,
            KEY_ONN_FREE_TV = 297;
    /** A button's value that opens this home screen's All Apps. */
    static final String ALL_APPS = HomeMode.PKG + "#all";
    /** A button's value that opens SoarTV's dedicated Settings tab directly. */
    static final String SETTINGS_TAB = HomeMode.PKG + "#settings";
    /** Xiaomi remote Settings / Gear button (global key intercepted by mitv.service) and Google TV Notification gear key. */
    static final int KEY_XIAOMI_SETTINGS = KeyEvent.KEYCODE_SETTINGS;
    static final int KEY_XIAOMI_NOTIFICATION = KeyEvent.KEYCODE_NOTIFICATION;
    /**
     * The JVC G24's (Skyworth) Inputs button, one key here whichever way it comes (BUTTON_16 with scan code 383 or 79,
     * or BUTTON_14 with 240: SkyworthRemote.shortcut), and its gear, NOTIFICATION; TV_SHORTCUTS and QUICK_SETTINGS
     * are the values that open the TV's input list and our Quick Settings panel.
     */
    static final int KEY_JVC_SHORTCUT = 200_383, KEY_JVC_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    static final String TV_SHORTCUTS = HomeMode.PKG + "#tvshortcuts";
    static final String QUICK_SETTINGS = HomeMode.PKG + "#quicksettings";
    static final int[] KEYS = RemoteKeys.CHROMECAST_SABRINA ? new int[] {SabrinaRemote.KEY_YOUTUBE}
            : RemoteKeys.GOOGLE_STREAMER
            ? new int[] {KEY_GOOGLE_YOUTUBE, KEY_GOOGLE_NETFLIX, KEY_GOOGLE_CUSTOM}
            : RemoteKeys.ONN ? new int[] {KEY_ONN_YOUTUBE, KEY_ONN_NETFLIX, KEY_ONN_DISNEY, KEY_ONN_PARAMOUNT,
                    KEY_ONN_FREE_TV}
            : RemoteKeys.JVC_SKYWORTH ? new int[] {KEY_JVC_SHORTCUT, KEY_JVC_SETTINGS}
            : RemoteKeys.PHILIPS_GTV ? new int[] {KEY_PHILIPS_SETTINGS, KEY_PHILIPS_APPS, KEY_PHILIPS_GUIDE,
                    KEY_PHILIPS_NETFLIX, KEY_PHILIPS_YOUTUBE, KEY_PHILIPS_PRIME}
            : RemoteKeys.HISENSE ? new int[] {KEY_HISENSE_PRIME, KEY_HISENSE_YOUTUBE, KEY_HISENSE_DISNEY,
                    KEY_HISENSE_APPS, KEY_HISENSE_SETTINGS, KEY_HISENSE_PROFILE}
            : RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY, KEY_SONY_LIV, KEY_SONY_SETTINGS}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI_PATCHWALL_ONLY ? new int[] {KEY_XIAOMI_PATCHWALL, KEY_XIAOMI_SETTINGS}
            : RemoteKeys.XIAOMI_AFKR0 ? new int[] {KEY_XIAOMI_PATCHWALL, KEY_XIAOMI_NETFLIX, KEY_XIAOMI_PRIME,
                    KEY_XIAOMI_YOUTUBE, KEY_XIAOMI_SETTINGS}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_YOUTUBE, KEY_XIAOMI_PATCHWALL, KEY_XIAOMI_SETTINGS,
                    KEY_XIAOMI_NOTIFICATION, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK,
                    KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION, KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL, KEY_TCL_SETTINGS};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        migrateWatchlistSimilarityOrder();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /**
     * Resolves a runtime API key using strict priority:
     * 1. Non-empty runtime {@code SharedPreferences} override,
     * 2. Hardware-encrypted {@link Secrets} vault entry,
     * 3. Compile-time {@link Config} fallback.
     */
    static String resolveKeyWithVaultFallback(
            String runtimeOverride,
            Context ctx,
            String secretName,
            String configFallback) {
        if (runtimeOverride != null && !runtimeOverride.trim().isEmpty()) {
            return runtimeOverride.trim();
        }
        String fromVault = Secrets.getSecret(ctx, secretName);
        if (fromVault != null && !fromVault.trim().isEmpty()) {
            return fromVault.trim();
        }
        return configFallback == null ? "" : configFallback.trim();
    }

    /**
     * Synchronizes {@code SharedPreferences} runtime key overrides with a decrypted Cloud Vault map
     * so stale overrides never shadow freshly restored vault keys.
     */
    static void syncRestoredVaultOverrides(Map<String, String> restored) {
        if (app == null || restored == null) return;
        SharedPreferences.Editor ed = sp().edit();
        String[][] mappings = new String[][] {
                {"tmdbKey", Secrets.TMDB_KEY},
                {"omdbKey", Secrets.OMDB_KEY},
                {"mdblistKey", Secrets.MDBLIST_KEY},
                {"geminiKey", Secrets.GEMINI_API_KEY},
                {"githubToken", Secrets.GITHUB_TOKEN}
        };
        for (String[] pair : mappings) {
            if (restored.containsKey(pair[1])) {
                String v = restored.get(pair[1]);
                String clean = v == null ? "" : v.trim();
                if (clean.isEmpty()) {
                    ed.remove(pair[0]);
                } else {
                    ed.putString(pair[0], clean);
                }
            }
        }
        ed.apply();
        reload();
    }

    /** The TV's own 12/24-hour setting (Settings → System → Date & time). */
    static boolean clock24() {
        return app != null && android.text.format.DateFormat.is24HourFormat(app);
    }

    /** "auto" (the TV's own 12/24-hour setting), "24" or "12". */
    static String clockFormat() {
        String v = profileSp().getString("clock", "auto");
        return v.equals("24") || v.equals("12") ? v : "auto";
    }

    static void setClockFormat(String v) { putProfile("clock", v, "clock_format"); }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences inMemoryPrefs;

    private static SharedPreferences sp() {
        if (app != null) {
            return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        }
        if (inMemoryPrefs == null) {
            inMemoryPrefs = new InMemoryPreferences();
        }
        return inMemoryPrefs;
    }

    /**
     * Per-user profile {@link SharedPreferences} store matching Tier 2 ({@code user_profile_settings}).
     * Falls back to {@link #sp()} in headless JVM tests where {@code app == null}, and lazily seeds the
     * default profile from legacy {@code "settings"} on first upgrade so zero existing user state is lost.
     */
    private static SharedPreferences profileSp() {
        if (app == null) {
            return sp();
        }
        String profSlug = "default";
        boolean isDefaultProfile = true;
        try {
            UserProfileManager.Profile active = UserProfileManager.get(app).getActiveProfile();
            if (active != null && active.username != null && !active.username.trim().isEmpty()) {
                profSlug = active.username.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
                isDefaultProfile = active.isDefault;
            }
        } catch (RuntimeException ignored) {
        }
        SharedPreferences psp = app.getSharedPreferences("profile_" + profSlug, Context.MODE_PRIVATE);
        if (isDefaultProfile && !psp.getBoolean("_seeded_v2", false)) {
            seedDefaultProfileFromLegacy(psp, sp());
        }
        return psp;
    }

    private static synchronized void seedDefaultProfileFromLegacy(SharedPreferences target, SharedPreferences legacy) {
        if (target.getBoolean("_seeded_v2", false)) return;
        SharedPreferences.Editor ed = target.edit().putBoolean("_seeded_v2", true);
        Map<String, ?> all = legacy.getAll();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String k = e.getKey();
                Object v = e.getValue();
                if (k == null || v == null) continue;
                if (isProfileScopedKey(k)) {
                    putAny(ed, k, v);
                }
            }
        }
        ed.apply();
    }

    private static boolean isProfileScopedKey(String k) {
        return "theme".equals(k) || "autoThemeMode".equals(k) || "autoDarkHour".equals(k) || "autoLightHour".equals(k)
                || "language".equals(k) || "clock".equals(k) || "temperature".equals(k) || "hideAnimation".equals(k)
                || "servicesOff".equals(k) || "servicesOn".equals(k)
                || "genreOrder".equals(k) || "genreOrder_tv".equals(k) || "genreOrder_movie".equals(k)
                || "hiddenGenres_tv".equals(k) || "hiddenGenres_movie".equals(k)
                || "watchlistOrder_tv".equals(k) || "watchlistOrder_movie".equals(k)
                || "watchedItems".equals(k) || k.startsWith("watchedSeasons:") || k.startsWith("autoUnwatchedSeason:")
                || "watchOff".equals(k) || "watchHidden".equals(k)
                || k.startsWith("watchHiddenAt:") || k.startsWith("watchHiddenState:")
                || "shelf".equals(k) || "appOrder".equals(k) || "hiddenApps".equals(k) || "permHiddenApps".equals(k)
                || k.startsWith("saver");
    }

    @SuppressWarnings("unchecked")
    private static void putAny(SharedPreferences.Editor ed, String k, Object v) {
        if (v instanceof String) ed.putString(k, (String) v);
        else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) ed.putInt(k, (Integer) v);
        else if (v instanceof Long) ed.putLong(k, (Long) v);
        else if (v instanceof Float) ed.putFloat(k, (Float) v);
        else if (v instanceof Set) ed.putStringSet(k, new HashSet<>((Set<String>) v));
    }

    private static void touchCloud(CloudSyncManager.Scope scope, String fieldKey) {
        if (app == null) return;
        try {
            CloudSyncManager.get(app).recordLocalFieldChange(scope, fieldKey);
        } catch (RuntimeException ignored) {
        }
    }

    private static void touchCloudSecret(CloudSyncManager.Scope scope, String secretKey) {
        if (app == null) return;
        try {
            CloudSyncManager.get(app).recordLocalSecretChange(scope, secretKey);
        } catch (RuntimeException ignored) {
        }
    }

    private static void putProfile(String key, String value, String cloudField) {
        profileSp().edit().putString(key, value).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.PROFILE, cloudField);
    }

    private static void putProfile(String key, int value, String cloudField) {
        profileSp().edit().putInt(key, value).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.PROFILE, cloudField);
    }

    private static void putProfile(String key, boolean value, String cloudField) {
        profileSp().edit().putBoolean(key, value).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.PROFILE, cloudField);
    }

    private static void putProfile(String key, long value, String cloudField) {
        profileSp().edit().putLong(key, value).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.PROFILE, cloudField);
    }

    private static SharedPreferences screensaverSp() {
        return isTvOverrideScreensaverEnabled() ? sp() : profileSp();
    }

    private static CloudSyncManager.Scope screensaverScope() {
        return isTvOverrideScreensaverEnabled() ? CloudSyncManager.Scope.TV_DEVICE : CloudSyncManager.Scope.PROFILE;
    }

    private static void putSaver(String key, String value) {
        screensaverSp().edit().putString(key, value).apply();
        version++;
        touchCloud(screensaverScope(), "screensaver");
    }

    private static void putSaver(String key, boolean value) {
        screensaverSp().edit().putBoolean(key, value).apply();
        version++;
        touchCloud(screensaverScope(), "screensaver");
    }

    private static void putSaver(String key, long value) {
        screensaverSp().edit().putLong(key, value).apply();
        version++;
        touchCloud(screensaverScope(), "screensaver");
    }

    private static final class InMemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = values.get(key);
            return v instanceof Set ? (Set<String>) v : defValues;
        }
        @Override public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).intValue() : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).longValue() : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).floatValue() : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new InMemoryEditor(this); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private static final class InMemoryEditor implements Editor {
            private final InMemoryPreferences target;
            private final Map<String, Object> changes = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean clearAll = false;

            InMemoryEditor(InMemoryPreferences target) { this.target = target; }
            @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { changes.put(key, values); return this; }
            @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { clearAll = true; return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                if (clearAll) target.values.clear();
                for (String r : removed) target.values.remove(r);
                target.values.putAll(changes);
            }
        }
    }

    /**
     * Reads the file again if another process changed it. On Android 11 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        if (app == null) {
            version++;
            return;
        }
        String before = language();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before)) Ui.applyLanguage();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, int value) {
        sp().edit().putInt(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    private static void put(String key, long value) {
        sp().edit().putLong(key, value).apply();
        version++;
    }

    private static void putCommitted(String key, String value) {
        sp().edit().putString(key, value).commit();
        version++;
    }

    private static void putCommitted(String key, boolean value) {
        sp().edit().putBoolean(key, value).commit();
        version++;
    }

    /**
     * Without bumping `version`: a new version makes the home screen start over (and fetch the weather and prices
     * again). Continue Watching's lists don't need that: the home screen reads them on every return (reload).
     */
    private static void putQuietly(String key, Set<String> value) {
        sp().edit().putStringSet(key, new HashSet<>(value)).apply();
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : profileSp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { putProfile("language", v, "language"); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = profileSp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { putProfile("temperature", v, "temperature"); }

    /** Picks per tab on the home screen: 3 (as it always was) up to Recs.MAX_CARDS. */
    static int cards() {
        return Math.max(3, Math.min(Recs.MAX_CARDS, sp().getInt("cards", 3)));
    }

    static void setCards(int n) { put("cards", n); }

    /** Cartoons and anime out of the picks (Home Settings > Picks): hide_animation in config.properties until changed here. */
    static boolean hideAnimation() {
        return profileSp().getBoolean("hideAnimation", Config.HIDE_ANIMATION);
    }

    static void setHideAnimation(boolean hide) { putProfile("hideAnimation", hide, "hide_animation"); }

    /** Explicit opt-in toggle allowing a secondary profile to reuse the household Stremio connection. */
    static boolean useHouseholdStremio() {
        return profileSp().getBoolean("useHouseholdStremio", false);
    }

    static void setUseHouseholdStremio(boolean enabled) {
        putProfile("useHouseholdStremio", enabled, "use_household_stremio");
    }

    // MARK: - Picks from

    private static String offList;
    private static int offListAt = -1;

    /** The services (Recs' keys: "netflix", "prime"...) whose titles are left out of the picks, as ",a,b,". */
    private static String offServices() {
        if (offList == null || offListAt != version) {
            String saved = sp().getString("servicesOff", null);
            if (saved == null) {   // not chosen on the TV yet: what hide_services says
                StringBuilder first = new StringBuilder();
                for (String key : Config.HIDE_SERVICES.split(",")) {
                    key = key.trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty()) first.append(first.length() > 0 ? "," : "").append(key);
                }
                saved = first.toString();
            }
            offList = saved.isEmpty() ? "," : "," + saved + ",";
            offListAt = version;
        }
        return offList;
    }

    /** A service found by its app's name is keyed by its package, and is off until switched on. */
    private static boolean byName(String key) {
        return key.indexOf('.') >= 0;
    }

    private static String onServices() {
        String saved = sp().getString("servicesOn", "");
        return saved.isEmpty() ? "," : "," + saved + ",";
    }

    static boolean serviceOff(String key) {
        if (key == null) return false;
        return byName(key) ? !onServices().contains("," + key + ",") : offServices().contains("," + key + ",");
    }

    static void setServiceOff(String key, boolean off) {
        if (byName(key)) {
            String on = onServices().replace("," + key + ",", ",");
            if (!off) on += key + ",";
            put("servicesOn", on.length() > 1 ? on.substring(1, on.length() - 1) : "");
            return;
        }
        String list = offServices();
        list = off ? (serviceOff(key) ? list : list + key + ",") : list.replace("," + key + ",", ",");
        put("servicesOff", list.length() > 1 ? list.substring(1, list.length() - 1) : "");
    }

    /** "auto" (dark from sunset to sunrise or custom hours), "light" or "dark". */
    static String theme() {
        String v = profileSp().getString("theme", Config.THEME.trim());
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { putProfile("theme", v, "theme"); }

    /** When theme() is "auto": "sun" (by sunrise/sunset) or "hours" (custom start hours). */
    static String autoThemeMode() {
        String v = profileSp().getString("autoThemeMode", "sun");
        return "hours".equals(v) ? "hours" : "sun";
    }

    static void setAutoThemeMode(String v) { putProfile("autoThemeMode", "hours".equals(v) ? "hours" : "sun", "auto_theme_mode"); }

    /** Hour of day (0..23) when Dark Mode starts in "hours" auto-theme mode (default 19 = 19:00). */
    static int autoDarkHour() {
        int h = profileSp().getInt("autoDarkHour", 19);
        return Math.max(0, Math.min(23, h));
    }

    static void setAutoDarkHour(int h) { putProfile("autoDarkHour", Math.max(0, Math.min(23, h)), "auto_dark_hour"); }

    /** Hour of day (0..23) when Light Mode starts in "hours" auto-theme mode (default 7 = 07:00). */
    static int autoLightHour() {
        int h = profileSp().getInt("autoLightHour", 7);
        return Math.max(0, Math.min(23, h));
    }

    static void setAutoLightHour(int h) { putProfile("autoLightHour", Math.max(0, Math.min(23, h)), "auto_light_hour"); }

    /** Tuner mode for Channels & Inputs -> Channels -> Tuner Mode ("antenna", "cable", "satellite"). */
    static String tunerMode() {
        String v = sp().getString("tunerMode", "antenna");
        return ("cable".equals(v) || "satellite".equals(v)) ? v : "antenna";
    }

    static void setTunerMode(String v) { put("tunerMode", v); touchCloud(CloudSyncManager.Scope.TV_DEVICE, "ui_prefs"); }

    /** Update channels automatically toggle under Channels & Inputs -> Channels. */
    static boolean autoUpdateChannels() { return sp().getBoolean("autoUpdateChannels", true); }
    static void setAutoUpdateChannels(boolean on) { put("autoUpdateChannels", on); touchCloud(CloudSyncManager.Scope.TV_DEVICE, "ui_prefs"); }

    /** Wi-Fi/Ethernet, Bluetooth and USB icons at the top-left of the home screen. */
    static boolean statusIcons() { return sp().getBoolean("statusicons", false); }
    static void setStatusIcons(boolean on) { put("statusicons", on); touchCloud(CloudSyncManager.Scope.TV_DEVICE, "ui_prefs"); }

    // MARK: - City

    static String city() { return sp().getString("city", Place.city()).trim(); }
    static String latitude() { return sp().getString("latitude", Place.latitude()); }
    static String longitude() { return sp().getString("longitude", Place.longitude()); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.HOUSEHOLD, "default_location");
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); touchCloud(CloudSyncManager.Scope.TV_DEVICE, "ui_prefs"); }

    // MARK: - Per-TV Override Switches (Favorite Apps Shelf, Hidden Apps, Screensaver)

    /** When true, this TV uses its own custom Favorite Apps Shelf instead of the active User Profile's synced shelf. */
    static boolean isTvOverrideShelfEnabled() { return sp().getBoolean("tvOverrideShelf", false); }
    static void setTvOverrideShelfEnabled(boolean enabled) {
        if (enabled && tvOverrideShelf() == null) {
            List<String> cur = profileShelf();
            if (cur != null) setTvOverrideShelf(cur);
        }
        put("tvOverrideShelf", enabled);
        touchCloud(CloudSyncManager.Scope.TV_DEVICE, "override_shelf");
    }

    /** When true, this TV uses its own custom Hidden Apps lists instead of the active User Profile's synced lists. */
    static boolean isTvOverrideHiddenAppsEnabled() { return sp().getBoolean("tvOverrideHiddenApps", false); }
    static void setTvOverrideHiddenAppsEnabled(boolean enabled) {
        if (enabled && !sp().contains("tvOverrideHiddenAppsSeeded")) {
            sp().edit()
                    .putStringSet("tv_hiddenApps", new HashSet<>(profileHiddenApps()))
                    .putStringSet("tv_permHiddenApps", new HashSet<>(profilePermHiddenApps()))
                    .putBoolean("tvOverrideHiddenAppsSeeded", true)
                    .apply();
        }
        put("tvOverrideHiddenApps", enabled);
        touchCloud(CloudSyncManager.Scope.TV_DEVICE, "override_hidden_apps");
    }

    /** When true, this TV uses its own custom Screensaver settings instead of the active User Profile's synced settings. */
    static boolean isTvOverrideScreensaverEnabled() { return sp().getBoolean("tvOverrideScreensaver", false); }
    static void setTvOverrideScreensaverEnabled(boolean enabled) {
        if (enabled && !sp().contains("tvOverrideScreensaverSeeded")) {
            importScreensaverMapInto(sp(), exportProfileScreensaverMap());
            sp().edit().putBoolean("tvOverrideScreensaverSeeded", true).apply();
        }
        put("tvOverrideScreensaver", enabled);
        touchCloud(CloudSyncManager.Scope.TV_DEVICE, "override_screensaver");
    }

    /** OK on the screensaver opens the title it shows, in its app; any other key only closes it. Off: any key closes it. */
    static boolean saverWatch() { return screensaverSp().getBoolean("saverWatch", false); }
    static void setSaverWatch(boolean on) { putSaver("saverWatch", on); }

    /** Rotating Photography Screensaver 6 core categories. */
    static final String[] SAVER_CATEGORIES = {"nature", "urban", "macro", "space", "paintings", "art"};
    static final String SAVER_CATEGORY_MIXED = "mixed";
    static final String DEFAULT_SAVER_CATEGORIES = "nature,urban,macro,space,paintings,art";
    private static final Set<String> CORE_SAVER_CATEGORIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(SAVER_CATEGORIES))
    );

    /** Returns the active set of screensaver gallery categories (minimum 1 guaranteed). */
    static Set<String> saverCategories() {
        SharedPreferences ssp = screensaverSp();
        String raw = ssp.getString("saverCategories", null);
        if (raw == null || raw.trim().isEmpty()) {
            String legacy = ssp.getString("saverCategory", null);
            if (legacy != null && !legacy.trim().isEmpty() && !"mixed".equalsIgnoreCase(legacy)) {
                String lower = legacy.trim().toLowerCase(Locale.ROOT);
                if (CORE_SAVER_CATEGORIES.contains(lower)) {
                    return new LinkedHashSet<>(Collections.singletonList(lower));
                }
            }
            return new LinkedHashSet<>(CORE_SAVER_CATEGORIES);
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && CORE_SAVER_CATEGORIES.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.addAll(CORE_SAVER_CATEGORIES);
        }
        return result;
    }

    /** Context-accepting overload for caller convenience. */
    static Set<String> saverCategories(Context context) {
        return saverCategories();
    }

    /** Stores the active set of screensaver gallery categories as a comma-separated string. */
    static void setSaverCategories(Set<String> categories) {
        Set<String> valid = new LinkedHashSet<>();
        if (categories != null) {
            for (String c : categories) {
                if (c != null) {
                    String trimmed = c.trim().toLowerCase(Locale.ROOT);
                    if (CORE_SAVER_CATEGORIES.contains(trimmed)) {
                        valid.add(trimmed);
                    }
                }
            }
        }
        if (valid.isEmpty()) {
            valid.addAll(CORE_SAVER_CATEGORIES);
        }
        StringBuilder sb = new StringBuilder();
        for (String cat : valid) {
            if (sb.length() > 0) sb.append(',');
            sb.append(cat);
        }
        putSaver("saverCategories", sb.toString());
        putSaver("saverCategory", valid.iterator().next());
    }

    /**
     * Toggles the specified screensaver category.
     * CRITICAL RULE: If the user attempts to uncheck the ONLY active category, the toggle is rejected
     * (at least one category must always remain enabled).
     * @return true if state was toggled, false if rejected because only 1 active category remained.
     */
    static boolean toggleSaverCategory(String category) {
        if (category == null || category.trim().isEmpty()) return false;
        String cat = category.trim().toLowerCase(Locale.ROOT);
        if (!CORE_SAVER_CATEGORIES.contains(cat)) return false;
        Set<String> current = saverCategories();
        if (current.contains(cat)) {
            if (current.size() <= 1) {
                return false; // Reject unchecking only remaining category
            }
            current.remove(cat);
        } else {
            current.add(cat);
        }
        setSaverCategories(current);
        return true;
    }

    /** Legacy single category accessor for 100% backward compatibility. */
    static String saverCategory() {
        Set<String> cats = saverCategories();
        return cats.isEmpty() ? "nature" : cats.iterator().next();
    }

    /** Legacy single category mutator for 100% backward compatibility. */
    static void setSaverCategory(String v) {
        if (v == null || v.trim().isEmpty() || "mixed".equalsIgnoreCase(v)) {
            setSaverCategories(CORE_SAVER_CATEGORIES);
        } else {
            String lower = v.trim().toLowerCase(Locale.ROOT);
            if (CORE_SAVER_CATEGORIES.contains(lower)) {
                setSaverCategories(new LinkedHashSet<>(Collections.singletonList(lower)));
            }
        }
    }

    /**
     * Screensaver curated clock designs:
     * "liquid_glass", "frosted_pill", "stacked", "split_flap",
     * "modern_serif", "digital_mono", "minimal", "outline".
     */
    static final String[] SAVER_CLOCK_STYLES = {
            "liquid_glass", "frosted_pill", "stacked", "split_flap",
            "modern_serif", "digital_mono", "minimal", "outline"
    };
    static String saverClockStyle() {
        String v = screensaverSp().getString("saverClockStyle", "liquid_glass");
        if ("editorial".equals(v)) return "stacked";
        if ("classic".equals(v)) return "frosted_pill";
        for (String s : SAVER_CLOCK_STYLES) if (s.equals(v)) return s;
        return "liquid_glass";
    }
    static void setSaverClockStyle(String v) { putSaver("saverClockStyle", v); }

    /** Screensaver World Clock secondary timezone and city label. */
    static String saverWorldClockTz() { return screensaverSp().getString("saverWorldClockTz", "America/New_York"); }
    static String saverWorldClockLabel() { return screensaverSp().getString("saverWorldClockLabel", "NYC"); }
    static void setSaverWorldClock(String tz, String label) {
        screensaverSp().edit().putString("saverWorldClockTz", tz).putString("saverWorldClockLabel", label).apply();
        version++;
        touchCloud(screensaverScope(), "screensaver");
    }

    /** Screensaver overlay toggles (artist, location, local time, world clock, weather, calendar, shortcuts). */
    static boolean saverShowArtist() { return screensaverSp().getBoolean("saverShowArtist", true); }
    static void setSaverShowArtist(boolean on) { putSaver("saverShowArtist", on); }
    static boolean saverShowLocation() { return screensaverSp().getBoolean("saverShowLocation", true); }
    static void setSaverShowLocation(boolean on) { putSaver("saverShowLocation", on); }
    static boolean saverShowArtworkTitle() { return screensaverSp().getBoolean("saverShowArtworkTitle", true); }
    static void setSaverShowArtworkTitle(boolean on) { putSaver("saverShowArtworkTitle", on); }
    static boolean saverShowLocalTime() { return screensaverSp().getBoolean("saverShowLocalTime", true); }
    static void setSaverShowLocalTime(boolean on) { putSaver("saverShowLocalTime", on); }
    static boolean saverShowWorldClock() { return screensaverSp().getBoolean("saverShowWorldClock", true); }
    static void setSaverShowWorldClock(boolean on) { putSaver("saverShowWorldClock", on); }
    static boolean saverShowWeather() { return screensaverSp().getBoolean("saverShowWeather", true); }
    static void setSaverShowWeather(boolean on) { putSaver("saverShowWeather", on); }
    static boolean saverShowCalendar() { return screensaverSp().getBoolean("saverShowCalendar", true); }
    static void setSaverShowCalendar(boolean on) { putSaver("saverShowCalendar", on); }
    static boolean saverShowShortcuts() { return screensaverSp().getBoolean("saverShowShortcuts", true); }
    static void setSaverShowShortcuts(boolean on) { putSaver("saverShowShortcuts", on); }

    /** Ken Burns image animation toggle: zooming and panning. Default is true (enabled). */
    static boolean saverAnimation() { return screensaverSp().getBoolean("saverAnimation", true); }
    static void setSaverAnimation(boolean on) { putSaver("saverAnimation", on); }

    /**
     * Curated cyclic slide duration intervals in milliseconds for the 4K Screensaver:
     * 0 ("Don't change image") through 2h, wrapping cyclically back to 0.
     */
    static final long[] SAVER_INTERVALS_MS = {
            0L,
            10_000L, 15_000L, 20_000L, 30_000L, 45_000L, 60_000L,
            90_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 2_700_000L,
            3_600_000L, 5_400_000L, 7_200_000L
    };
    static final String[] SAVER_INTERVAL_LABELS = {
            "Don't change image",
            "10s", "15s", "20s", "30s", "45s", "60s",
            "1:30 min", "2 min", "5 min", "10 min", "15 min", "30 min", "45 min",
            "1h", "1:30h", "2h"
    };

    static long saverIntervalMs() {
        long v = screensaverSp().getLong("saverIntervalMs", 30_000L);
        return Math.max(0L, v);
    }

    static void setSaverIntervalMs(long ms) { putSaver("saverIntervalMs", Math.max(0L, ms)); }

    static Map<String, Object> exportProfileScreensaverMap() { return exportScreensaverMapFrom(profileSp()); }
    static void importProfileScreensaverMap(Map<String, Object> map) { importScreensaverMapInto(profileSp(), map); }
    static Map<String, Object> exportTvOverrideScreensaverMap() { return exportScreensaverMapFrom(sp()); }
    static void importTvOverrideScreensaverMap(Map<String, Object> map) { importScreensaverMapInto(sp(), map); }

    private static Map<String, Object> exportScreensaverMapFrom(SharedPreferences target) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        String rawCats = target.getString("saverCategories", DEFAULT_SAVER_CATEGORIES);
        List<String> cats = new ArrayList<>();
        if (rawCats != null) {
            for (String tok : rawCats.split(",")) {
                String c = tok.trim().toLowerCase(Locale.ROOT);
                if (!c.isEmpty() && CORE_SAVER_CATEGORIES.contains(c) && !cats.contains(c)) cats.add(c);
            }
        }
        if (cats.isEmpty()) cats.addAll(CORE_SAVER_CATEGORIES);
        m.put("categories", cats);
        m.put("clock_style", target.getString("saverClockStyle", "liquid_glass"));
        m.put("world_clock_tz", target.getString("saverWorldClockTz", "America/New_York"));
        m.put("world_clock_label", target.getString("saverWorldClockLabel", "NYC"));
        m.put("interval_ms", Math.max(0L, target.getLong("saverIntervalMs", 30_000L)));
        m.put("animation", target.getBoolean("saverAnimation", true));
        m.put("watch", target.getBoolean("saverWatch", false));
        m.put("show_artist", target.getBoolean("saverShowArtist", true));
        m.put("show_location", target.getBoolean("saverShowLocation", true));
        m.put("show_artwork_title", target.getBoolean("saverShowArtworkTitle", true));
        m.put("show_local_time", target.getBoolean("saverShowLocalTime", true));
        m.put("show_world_clock", target.getBoolean("saverShowWorldClock", true));
        m.put("show_weather", target.getBoolean("saverShowWeather", true));
        m.put("show_calendar", target.getBoolean("saverShowCalendar", true));
        m.put("show_shortcuts", target.getBoolean("saverShowShortcuts", true));
        return m;
    }

    private static void importScreensaverMapInto(SharedPreferences target, Map<String, Object> m) {
        if (target == null || m == null || m.isEmpty()) return;
        SharedPreferences.Editor ed = target.edit();
        List<String> cats = CloudVaultCrypto.JsonCodec.optStringList(m, "categories");
        if (!cats.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            String first = null;
            for (String c : cats) {
                String clean = c.trim().toLowerCase(Locale.ROOT);
                if (CORE_SAVER_CATEGORIES.contains(clean)) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(clean);
                    if (first == null) first = clean;
                }
            }
            if (first != null) {
                ed.putString("saverCategories", sb.toString());
                ed.putString("saverCategory", first);
            }
        }
        if (m.containsKey("clock_style")) ed.putString("saverClockStyle", CloudVaultCrypto.JsonCodec.optString(m, "clock_style", "liquid_glass"));
        if (m.containsKey("world_clock_tz")) ed.putString("saverWorldClockTz", CloudVaultCrypto.JsonCodec.optString(m, "world_clock_tz", "America/New_York"));
        if (m.containsKey("world_clock_label")) ed.putString("saverWorldClockLabel", CloudVaultCrypto.JsonCodec.optString(m, "world_clock_label", "NYC"));
        if (m.containsKey("interval_ms")) ed.putLong("saverIntervalMs", Math.max(0L, CloudVaultCrypto.JsonCodec.optLong(m, "interval_ms", 30_000L)));
        if (m.containsKey("animation")) ed.putBoolean("saverAnimation", CloudVaultCrypto.JsonCodec.optBoolean(m, "animation", true));
        if (m.containsKey("watch")) ed.putBoolean("saverWatch", CloudVaultCrypto.JsonCodec.optBoolean(m, "watch", false));
        if (m.containsKey("show_artist")) ed.putBoolean("saverShowArtist", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_artist", true));
        if (m.containsKey("show_location")) ed.putBoolean("saverShowLocation", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_location", true));
        if (m.containsKey("show_artwork_title")) ed.putBoolean("saverShowArtworkTitle", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_artwork_title", true));
        if (m.containsKey("show_local_time")) ed.putBoolean("saverShowLocalTime", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_local_time", true));
        if (m.containsKey("show_world_clock")) ed.putBoolean("saverShowWorldClock", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_world_clock", true));
        if (m.containsKey("show_weather")) ed.putBoolean("saverShowWeather", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_weather", true));
        if (m.containsKey("show_calendar")) ed.putBoolean("saverShowCalendar", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_calendar", true));
        if (m.containsKey("show_shortcuts")) ed.putBoolean("saverShowShortcuts", CloudVaultCrypto.JsonCodec.optBoolean(m, "show_shortcuts", true));
        ed.apply();
        version++;
    }

    /**
     * Returns the index in {@link #SAVER_INTERVALS_MS} matching {@code ms}, or the nearest preset index
     * so legacy or custom values snap cleanly onto the cyclic Up/Down duration ladder.
     */
    static int saverIntervalIndex(long ms) {
        if (ms <= 0L) return 0;
        int bestIdx = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < SAVER_INTERVALS_MS.length; i++) {
            long diff = Math.abs(SAVER_INTERVALS_MS[i] - ms);
            if (diff == 0L) return i;
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Advances {@code currentMs} up to the next slide duration in {@link #SAVER_INTERVALS_MS},
     * wrapping cleanly from {@code 2h} ({@code 7_200_000L}) back to {@code 0L} ({@code "Don't change image"}).
     */
    static long nextSaverIntervalUp(long currentMs) {
        long cur = Math.max(0L, currentMs);
        for (int i = 0; i < SAVER_INTERVALS_MS.length; i++) {
            if (SAVER_INTERVALS_MS[i] == cur) {
                return SAVER_INTERVALS_MS[(i + 1) % SAVER_INTERVALS_MS.length];
            }
            if (SAVER_INTERVALS_MS[i] > cur) {
                return SAVER_INTERVALS_MS[i];
            }
        }
        return SAVER_INTERVALS_MS[0];
    }

    /**
     * Steps {@code currentMs} down to the previous slide duration in {@link #SAVER_INTERVALS_MS},
     * wrapping cleanly from {@code 0L} ({@code "Don't change image"}) back to {@code 2h} ({@code 7_200_000L}).
     */
    static long nextSaverIntervalDown(long currentMs) {
        long cur = Math.max(0L, currentMs);
        if (cur <= 0L) {
            return SAVER_INTERVALS_MS[SAVER_INTERVALS_MS.length - 1];
        }
        for (int i = SAVER_INTERVALS_MS.length - 1; i >= 0; i--) {
            if (SAVER_INTERVALS_MS[i] == cur) {
                int prev = (i - 1 + SAVER_INTERVALS_MS.length) % SAVER_INTERVALS_MS.length;
                return SAVER_INTERVALS_MS[prev];
            }
            if (SAVER_INTERVALS_MS[i] < cur) {
                return SAVER_INTERVALS_MS[i];
            }
        }
        return SAVER_INTERVALS_MS[SAVER_INTERVALS_MS.length - 1];
    }

    /**
     * Formats any screensaver slide interval in milliseconds into a clean human-readable label.
     */
    static String formatSaverIntervalLabel(long ms) {
        if (ms <= 0L) return SAVER_INTERVAL_LABELS[0];
        for (int i = 0; i < SAVER_INTERVALS_MS.length; i++) {
            if (SAVER_INTERVALS_MS[i] == ms) return SAVER_INTERVAL_LABELS[i];
        }
        long totalSec = Math.max(1L, ms / 1000L);
        if (totalSec <= 60L) return totalSec + "s";
        long mins = totalSec / 60L;
        long remSec = totalSec % 60L;
        if (mins < 60L) {
            return remSec == 0L ? (mins + " min") : String.format(Locale.US, "%d:%02d min", mins, remSec);
        }
        long hours = mins / 60L;
        long remMin = mins % 60L;
        return remMin == 0L ? (hours + "h") : String.format(Locale.US, "%d:%02dh", hours, remMin);
    }

    static String saverIntervalLabel() {
        return formatSaverIntervalLabel(saverIntervalMs());
    }

    /** Curated 61 global metropolitan hubs covering every populated UTC offset from UTC-10 to UTC+13. */
    static final String[][] SAVER_WORLD_HUBS = {
            {"HNL", "Pacific/Honolulu", "Honolulu"},
            {"ANC", "America/Anchorage", "Anchorage"},
            {"YVR", "America/Vancouver", "Vancouver"},
            {"LAX", "America/Los_Angeles", "Los Angeles"},
            {"PHX", "America/Phoenix", "Phoenix"},
            {"DEN", "America/Denver", "Denver"},
            {"MEX", "America/Mexico_City", "Mexico City"},
            {"CHI", "America/Chicago", "Chicago"},
            {"BOG", "America/Bogota", "Bogota"},
            {"LIM", "America/Lima", "Lima"},
            {"YYZ", "America/Toronto", "Toronto"},
            {"NYC", "America/New_York", "New York"},
            {"CCS", "America/Caracas", "Caracas"},
            {"SCL", "America/Santiago", "Santiago"},
            {"YHZ", "America/Halifax", "Halifax"},
            {"YYT", "America/St_Johns", "St. John's"},
            {"BUE", "America/Argentina/Buenos_Aires", "Buenos Aires"},
            {"SAO", "America/Sao_Paulo", "São Paulo"},
            {"REK", "Atlantic/Reykjavik", "Reykjavik"},
            {"UTC", "UTC", "Universal Time (UTC)"},
            {"LIS", "Europe/Lisbon", "Lisbon"},
            {"DUB", "Europe/Dublin", "Dublin"},
            {"LON", "Europe/London", "London"},
            {"MAD", "Europe/Madrid", "Madrid"},
            {"PAR", "Europe/Paris", "Paris"},
            {"AMS", "Europe/Amsterdam", "Amsterdam"},
            {"ZRH", "Europe/Zurich", "Zurich"},
            {"BER", "Europe/Berlin", "Berlin"},
            {"ROM", "Europe/Rome", "Rome"},
            {"STO", "Europe/Stockholm", "Stockholm"},
            {"VIE", "Europe/Vienna", "Vienna"},
            {"PRG", "Europe/Prague", "Prague"},
            {"WAW", "Europe/Warsaw", "Warsaw"},
            {"ATH", "Europe/Athens", "Athens"},
            {"BUH", "Europe/Bucharest", "Bucharest"},
            {"CAI", "Africa/Cairo", "Cairo"},
            {"JNB", "Africa/Johannesburg", "Johannesburg"},
            {"TLV", "Asia/Jerusalem", "Jerusalem / Tel Aviv"},
            {"IST", "Europe/Istanbul", "Istanbul"},
            {"MOW", "Europe/Moscow", "Moscow"},
            {"RUH", "Asia/Riyadh", "Riyadh"},
            {"NBO", "Africa/Nairobi", "Nairobi"},
            {"THR", "Asia/Tehran", "Tehran"},
            {"DXB", "Asia/Dubai", "Dubai"},
            {"KBL", "Asia/Kabul", "Kabul"},
            {"KHI", "Asia/Karachi", "Karachi"},
            {"BOM", "Asia/Kolkata", "Mumbai"},
            {"KTM", "Asia/Kathmandu", "Kathmandu"},
            {"DAC", "Asia/Dhaka", "Dhaka"},
            {"BKK", "Asia/Bangkok", "Bangkok"},
            {"JKT", "Asia/Jakarta", "Jakarta"},
            {"SIN", "Asia/Singapore", "Singapore"},
            {"HKG", "Asia/Hong_Kong", "Hong Kong"},
            {"TPE", "Asia/Taipei", "Taipei"},
            {"MNL", "Asia/Manila", "Manila"},
            {"PER", "Australia/Perth", "Perth"},
            {"SEL", "Asia/Seoul", "Seoul"},
            {"TYO", "Asia/Tokyo", "Tokyo"},
            {"ADL", "Australia/Adelaide", "Adelaide"},
            {"SYD", "Australia/Sydney", "Sydney"},
            {"AKL", "Pacific/Auckland", "Auckland"}
    };

    /** Default genre order for TV Shows and Movies rows (38 popular genres). */
    static final String[] DEFAULT_GENRES = {
            "Comedy", "Rom-Coms", "Romance", "Thriller", "Action", "Horror", "Drama", "Sci-Fi",
            "Crime", "Adventure", "Animation", "Anime", "Superhero", "Mystery", "Documentary",
            "Family", "Fantasy", "History", "War & Politics", "Biography", "K-Drama", "Music",
            "Sports", "Reality", "Western", "Stand-Up & Talk",
            "Action & Adventure", "Psychological Thriller", "Dark Comedy", "Crime & Mystery",
            "Medical & Legal", "Teen & Coming-of-Age", "Supernatural", "Post-Apocalyptic",
            "Martial Arts", "Spy & Espionage", "True Crime", "Miniseries"
    };

    static List<String> genreOrder() {
        return genreOrder("tv");
    }

    static List<String> genreOrder(String mediaType) {
        String key = "movie".equals(mediaType) ? "genreOrder_movie" : "genreOrder_tv";
        String v = app != null ? profileSp().getString(key, profileSp().getString("genreOrder", null)) : null;
        List<String> out = new ArrayList<>();
        if (v != null && !v.isEmpty()) {
            for (String g : v.split(",")) {
                String t = g.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        for (String d : DEFAULT_GENRES) {
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    /** Returns the visible (unhidden) genres in user-saved order for the Home screen. */
    static List<String> visibleGenreOrder(String mediaType) {
        java.util.Set<String> hidden = hiddenGenres(mediaType);
        List<String> all = genreOrder(mediaType);
        List<String> out = new ArrayList<>(all.size());
        for (String g : all) {
            if (!hidden.contains(g)) out.add(g);
        }
        return out;
    }

    /** Returns the genres sorted for Settings: visible genres first (in saved order), followed by hidden genres last. */
    static List<String> settingsGenreOrder(String mediaType) {
        java.util.Set<String> hidden = hiddenGenres(mediaType);
        List<String> all = genreOrder(mediaType);
        List<String> visibleList = new ArrayList<>();
        List<String> hiddenList = new ArrayList<>();
        for (String g : all) {
            if (hidden.contains(g)) hiddenList.add(g);
            else visibleList.add(g);
        }
        visibleList.addAll(hiddenList);
        return visibleList;
    }

    static java.util.Set<String> hiddenGenres(String mediaType) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (app == null) return out;
        String key = "movie".equals(mediaType) ? "hiddenGenres_movie" : "hiddenGenres_tv";
        String raw = profileSp().getString(key, "");
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(",")) {
                String g = part.trim();
                if (!g.isEmpty()) out.add(g);
            }
        }
        return out;
    }

    static boolean isGenreHidden(String mediaType, String genre) {
        if (genre == null || genre.isEmpty()) return false;
        return hiddenGenres(mediaType).contains(genre.trim());
    }

    static void setGenreHidden(String mediaType, String genre, boolean hidden) {
        if (app == null || genre == null || genre.trim().isEmpty()) return;
        String key = "movie".equals(mediaType) ? "hiddenGenres_movie" : "hiddenGenres_tv";
        java.util.Set<String> set = new java.util.LinkedHashSet<>(hiddenGenres(mediaType));
        if (hidden) set.add(genre.trim());
        else set.remove(genre.trim());
        replaceHiddenGenres(mediaType, set);
    }

    static void replaceHiddenGenres(String mediaType, Set<String> hiddenSet) {
        if (app == null) return;
        String key = "movie".equals(mediaType) ? "hiddenGenres_movie" : "hiddenGenres_tv";
        String cloudKey = "movie".equals(mediaType) ? "hidden_genres_movie" : "hidden_genres_tv";
        StringBuilder sb = new StringBuilder();
        if (hiddenSet != null) {
            for (String g : hiddenSet) {
                if (g == null || g.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(g.trim());
            }
        }
        putProfile(key, sb.toString(), cloudKey);
    }

    static void setGenreOrder(List<String> genres) {
        setGenreOrder("tv", genres);
    }

    static void setGenreOrder(String mediaType, List<String> genres) {
        String key = "movie".equals(mediaType) ? "genreOrder_movie" : "genreOrder_tv";
        String cloudKey = "movie".equals(mediaType) ? "genre_order_movie" : "genre_order_tv";
        StringBuilder sb = new StringBuilder();
        for (String g : genres) {
            if (g == null || g.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(g.trim());
        }
        putProfile(key, sb.toString(), cloudKey);
    }

    /** Custom order for Smart Home / Drawer items. */
    static List<String> drawerOrder() {
        String v = sp().getString("drawerOrder", null);
        if (v == null || v.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setDrawerOrder(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String p : ids) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("drawerOrder", sb.toString());
        touchCloud(CloudSyncManager.Scope.HOUSEHOLD, "smart_home_order");
    }

    /** Preferred Music app for the Headbar Music tab: "ytmusic", "spotify", or "applemusic". */
    static String musicSource() {
        String v = sp().getString("musicSource", "ytmusic");
        return ("spotify".equals(v) || "applemusic".equals(v)) ? v : "ytmusic";
    }
    static void setMusicSource(String v) { put("musicSource", v); }

    // MARK: - Top of the home screen

    static final String TOP_PICKS = "picks", TOP_CONTINUE = "continue", TOP_NONE = "none";
    static final String[] TOP_CONTENT = {TOP_PICKS, TOP_CONTINUE, TOP_NONE};

    /** What the cards under the title show: the recommendations (TMDB), what you were watching in your apps, or nothing. */
    static String topContent() {
        String v = sp().getString("topContent", TOP_PICKS);
        return v.equals(TOP_CONTINUE) || v.equals(TOP_NONE) ? v : TOP_PICKS;
    }

    static void setTopContent(String v) { put("topContent", v); }

    /** Apps left out of Continue Watching (Settings), by package. */
    static Set<String> watchOff() { return new HashSet<>(profileSp().getStringSet("watchOff", new HashSet<>())); }
    static void setWatchOff(Set<String> packages) {
        profileSp().edit().putStringSet("watchOff", new HashSet<>(packages)).apply();
        touchCloud(CloudSyncManager.Scope.PROFILE, "watch_off");
    }

    /** Single items hidden from Continue Watching (holding OK on a card), as WatchNext.hideKey. */
    static Set<String> watchHidden() { return new HashSet<>(profileSp().getStringSet("watchHidden", new HashSet<>())); }
    static void setWatchHidden(Set<String> keys) {
        profileSp().edit().putStringSet("watchHidden", new HashSet<>(keys)).apply();
        touchCloud(CloudSyncManager.Scope.PROFILE, "watch_hidden");
    }

    /** Timestamp (System.currentTimeMillis) when a Continue Watching item was hidden; watching it after this unhides it. */
    static long watchHiddenAt(String hideKey) {
        if (hideKey == null) return 0L;
        return profileSp().getLong("watchHiddenAt:" + hideKey, 0L);
    }

    /** Saved playback state signature (episode + minute position) when the item was hidden. */
    static String watchHiddenState(String hideKey) {
        if (hideKey == null) return "";
        return profileSp().getString("watchHiddenState:" + hideKey, "");
    }

    static void hideWatchItem(String hideKey, long hiddenAtMs) {
        hideWatchItem(hideKey, hiddenAtMs, "");
    }

    static void hideWatchItem(String hideKey, long hiddenAtMs, String stateSig) {
        if (hideKey == null) return;
        Set<String> hidden = watchHidden();
        hidden.add(hideKey);
        profileSp().edit()
                .putStringSet("watchHidden", hidden)
                .putLong("watchHiddenAt:" + hideKey, hiddenAtMs)
                .putString("watchHiddenState:" + hideKey, stateSig == null ? "" : stateSig)
                .apply();
        touchCloud(CloudSyncManager.Scope.PROFILE, "watch_hidden");
    }

    static void unhideWatchItem(String hideKey) {
        if (hideKey == null) return;
        Set<String> hidden = watchHidden();
        if (hidden.remove(hideKey)) {
            profileSp().edit()
                    .putStringSet("watchHidden", hidden)
                    .remove("watchHiddenAt:" + hideKey)
                    .remove("watchHiddenState:" + hideKey)
                    .apply();
            touchCloud(CloudSyncManager.Scope.PROFILE, "watch_hidden");
        }
    }

    /** Exports per-profile hidden Continue Watching entries (with hiddenAt timestamps & state signatures). */
    static Map<String, Object> exportWatchHiddenMap() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : watchHidden()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("hidden_at", watchHiddenAt(key));
            entry.put("state_sig", watchHiddenState(key));
            out.put(key, entry);
        }
        return out;
    }

    /** Replaces per-profile hidden Continue Watching entries from cloud sync. */
    static void importWatchHiddenMap(Map<String, Object> map) {
        Set<String> next = new HashSet<>();
        SharedPreferences.Editor ed = profileSp().edit();
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
                String k = e.getKey().trim();
                next.add(k);
                Map<String, Object> sub = CloudVaultCrypto.JsonCodec.optObjectMap(map, k);
                long at = CloudVaultCrypto.JsonCodec.optLong(sub, "hidden_at", 0L);
                String sig = CloudVaultCrypto.JsonCodec.optString(sub, "state_sig", "");
                ed.putLong("watchHiddenAt:" + k, at);
                ed.putString("watchHiddenState:" + k, sig);
            }
        }
        ed.putStringSet("watchHidden", next).apply();
    }

    /** Recent Apps: "off" (the default: Android manages memory on its own), "drawer" (a shortcut in the bottom drawer and the
     *  remote's app-switch key) or "home" (those, and pressing Home twice). The top button is its own choice (Top Buttons). */
    static String recents() {
        String v = sp().getString("recents", "off");
        return v.equals("drawer") || v.equals("home") ? v : "off";
    }

    static void setRecents(String v) { put("recents", v); }

    /**
     * After the TV starts from off, never from standby (Home Settings > At power-on, #396 and #346): "tv" (the TV's
     * choice), "home" (ours, where a TV app takes the screen at boot) or "live" (Live TV, where the TV starts on ours).
     */
    static final String[] POWER_ON = {"tv", "home", "live"};

    static String powerOn() {
        String v = sp().getString("powerOn", "tv");
        return v.equals("home") || v.equals("live") ? v : "tv";
    }

    static void setPowerOn(String v) { put("powerOn", v); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }
    /** The weather card's forecast: "hours" (the next 6 hours) or "days" (the next 5 days). */
    static String forecast() { return "days".equals(sp().getString("forecast", "hours")) ? "days" : "hours"; }
    static void setForecast(String v) { put("forecast", v); }

    static boolean nowPlaying() { return sp().getBoolean("nowPlaying", false); }
    static void setNowPlaying(boolean on) {   // committed: the main process reads it right away to allow the listener
        sp().edit().putBoolean("nowPlaying", on).commit();
        version++;
    }

    /**
     * Also list the TV's own system apps (Settings, Files, the manufacturer's apps) in All Apps and the shelf editor.
     * Off keeps the list as it always was: the apps people installed, plus the system ones a TV needs there.
     */
    static boolean androidApps() { return sp().getBoolean("androidapps", false); }
    static void setAndroidApps(boolean on) { put("androidapps", on); }

    // MARK: - TMDB key

    /**
     * The TMDB key given on the TV, or "": then config.properties' one (Recs.key). Never logged.
     */
    static String tmdbKey() { return app == null ? "" : sp().getString("tmdbKey", "").trim(); }

    static void setTmdbKey(String key) {   // committed: ScreensaverAlone reads it from the main process
        sp().edit().putString("tmdbKey", key).commit();
        if (app != null && key != null && !key.trim().isEmpty()) {
            Secrets.putSecret(app, Secrets.TMDB_KEY, key.trim());
            touchCloudSecret(CloudSyncManager.Scope.HOUSEHOLD, Secrets.TMDB_KEY);
        }
        version++;
    }

    // MARK: - Shelf & Hidden Apps (Per-Profile by Default, With Optional Per-TV Override)

    private static List<String> readCsvList(SharedPreferences target, String key) {
        String v = target.getString(key, null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static String joinCsvList(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        if (packages != null) {
            for (String p : packages) if (p != null && !p.isEmpty()) sb.append(sb.length() == 0 ? "" : ",").append(p);
        }
        return sb.toString();
    }

    /** Profile-level favorite shelf packages (used unless this TV overrides the shelf). */
    static List<String> profileShelf() { return readCsvList(profileSp(), "shelf"); }
    static void setProfileShelf(List<String> packages) {
        profileSp().edit().putString("shelf", joinCsvList(packages)).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.PROFILE, "shelf_apps");
    }

    /** TV-override favorite shelf packages (active when {@link #isTvOverrideShelfEnabled()} is true). */
    static List<String> tvOverrideShelf() { return readCsvList(sp(), "tv_shelf"); }
    static void setTvOverrideShelf(List<String> packages) {
        sp().edit().putString("tv_shelf", joinCsvList(packages)).apply();
        version++;
        touchCloud(CloudSyncManager.Scope.TV_DEVICE, "shelf_apps");
    }

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        return isTvOverrideShelfEnabled() ? tvOverrideShelf() : profileShelf();
    }

    static void setShelf(List<String> packages) {
        if (isTvOverrideShelfEnabled()) setTvOverrideShelf(packages);
        else setProfileShelf(packages);
    }

    /** Custom order for apps on the Apps screen; null until reordered by the user. */
    static List<String> appOrder() {
        SharedPreferences target = isTvOverrideShelfEnabled() ? sp() : profileSp();
        String key = isTvOverrideShelfEnabled() ? "tv_appOrder" : "appOrder";
        String v = target.getString(key, null);
        if (v == null || v.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setAppOrder(List<String> packages) {
        SharedPreferences target = isTvOverrideShelfEnabled() ? sp() : profileSp();
        String key = isTvOverrideShelfEnabled() ? "tv_appOrder" : "appOrder";
        target.edit().putString(key, joinCsvList(packages)).apply();
        touchCloud(isTvOverrideShelfEnabled() ? CloudSyncManager.Scope.TV_DEVICE : CloudSyncManager.Scope.PROFILE, "app_order");
    }

    static Set<String> profileHiddenApps() {
        Set<String> hidden = new HashSet<>(profileSp().getStringSet("hiddenApps", new HashSet<>()));
        hidden.removeAll(profilePermHiddenApps());
        return hidden;
    }

    static Set<String> profilePermHiddenApps() {
        return new HashSet<>(profileSp().getStringSet("permHiddenApps", new HashSet<>()));
    }

    static void replaceProfileHiddenApps(Set<String> hidden) {
        profileSp().edit().putStringSet("hiddenApps", hidden == null ? new HashSet<>() : new HashSet<>(hidden)).apply();
    }

    static void replaceProfilePermHiddenApps(Set<String> permHidden) {
        profileSp().edit().putStringSet("permHiddenApps", permHidden == null ? new HashSet<>() : new HashSet<>(permHidden)).apply();
    }

    static Set<String> tvOverrideHiddenApps() {
        Set<String> hidden = new HashSet<>(sp().getStringSet("tv_hiddenApps", new HashSet<>()));
        hidden.removeAll(tvOverridePermHiddenApps());
        return hidden;
    }

    static Set<String> tvOverridePermHiddenApps() {
        return new HashSet<>(sp().getStringSet("tv_permHiddenApps", new HashSet<>()));
    }

    static void replaceTvOverrideHiddenApps(Set<String> hidden) {
        sp().edit().putStringSet("tv_hiddenApps", hidden == null ? new HashSet<>() : new HashSet<>(hidden)).apply();
    }

    static void replaceTvOverridePermHiddenApps(Set<String> permHidden) {
        sp().edit().putStringSet("tv_permHiddenApps", permHidden == null ? new HashSet<>() : new HashSet<>(permHidden)).apply();
    }

    /** Temporarily hidden apps on the Apps screen (excluding permanently hidden apps). */
    static Set<String> hiddenApps() {
        return isTvOverrideHiddenAppsEnabled() ? tvOverrideHiddenApps() : profileHiddenApps();
    }

    static void setAppHidden(String pkg, boolean hidden) {
        if (pkg == null || pkg.isEmpty()) return;
        if (isTvOverrideHiddenAppsEnabled()) {
            Set<String> set = new HashSet<>(sp().getStringSet("tv_hiddenApps", new HashSet<>()));
            if (hidden) set.add(pkg);
            else set.remove(pkg);
            sp().edit().putStringSet("tv_hiddenApps", set).apply();
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "hidden_apps");
        } else {
            Set<String> set = new HashSet<>(profileSp().getStringSet("hiddenApps", new HashSet<>()));
            if (hidden) set.add(pkg);
            else set.remove(pkg);
            profileSp().edit().putStringSet("hiddenApps", set).apply();
            touchCloud(CloudSyncManager.Scope.PROFILE, "hidden_apps");
        }
    }

    /** Permanently hidden apps (never shown in the Apps grid or under the Hidden tile). */
    static Set<String> permHiddenApps() {
        return isTvOverrideHiddenAppsEnabled() ? tvOverridePermHiddenApps() : profilePermHiddenApps();
    }

    static void permanentlyHideApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (isTvOverrideHiddenAppsEnabled()) {
            Set<String> perm = tvOverridePermHiddenApps();
            perm.add(pkg);
            Set<String> temp = new HashSet<>(sp().getStringSet("tv_hiddenApps", new HashSet<>()));
            temp.remove(pkg);
            sp().edit().putStringSet("tv_permHiddenApps", perm).putStringSet("tv_hiddenApps", temp).apply();
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "perm_hidden_apps");
        } else {
            Set<String> perm = profilePermHiddenApps();
            perm.add(pkg);
            Set<String> temp = new HashSet<>(profileSp().getStringSet("hiddenApps", new HashSet<>()));
            temp.remove(pkg);
            profileSp().edit().putStringSet("permHiddenApps", perm).putStringSet("hiddenApps", temp).apply();
            touchCloud(CloudSyncManager.Scope.PROFILE, "perm_hidden_apps");
        }
    }

    // MARK: - Sleep timer

    /** When the sleep timer ends (System.currentTimeMillis); SleepTimer's alarm says whether it's still on. */
    static long sleepAt() { return sp().getLong("sleepAt", 0); }

    /** When it was set, in uptimeMillis (dumpsys power's clock): a TV turned off since ended it (SleepTimer.check). */
    static long sleepFrom() { return sp().getLong("sleepFrom", 0); }

    // No version++ for the sleep settings: HomeActivity would rebuild the home screen and fetch the weather again for a
    // timer; HomeView reads what it needs on resume and every 15 s (updateSleep).
    static void setSleepAt(long at, long from) {
        sp().edit().putLong("sleepAt", at).putLong("sleepFrom", from).apply();
    }

    /** The sleep timer's minutes left on screen: over any app (SleepOverlay), or where this TV can't, on the home screen. */
    static boolean sleepShown() { return sp().getBoolean("sleepShown", true); }
    static void setSleepShown(boolean on) { sp().edit().putBoolean("sleepShown", on).apply(); }

    /** Where it shows over other apps: SleepOverlay's places, the top center first. */
    static int sleepPlace() { return sp().getInt("sleepPlace", 0); }

    static void setSleepPlace(int place) { sp().edit().putInt("sleepPlace", place).apply(); }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order (HomeApps.TOP_ALL). null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took them all off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        if (RemoteKeys.CHROMECAST_SABRINA) {
            return keyCode == SabrinaRemote.KEY_YOUTUBE ? "com.google.android.youtube.tv" : "";
        }
        if (RemoteKeys.GOOGLE_STREAMER) {
            return keyCode == KEY_GOOGLE_YOUTUBE ? "com.google.android.youtube.tv" : "";
        }
        if (RemoteKeys.ONN) {
            return keyCode == KEY_ONN_YOUTUBE ? "com.google.android.youtube.tv" : "";
        }
        if (RemoteKeys.JVC_SKYWORTH) {
            if (keyCode == KEY_JVC_SHORTCUT) return TV_SHORTCUTS;
            return keyCode == KEY_JVC_SETTINGS ? QUICK_SETTINGS : "";
        }
        if (RemoteKeys.SONY) {
            if (!sonyGoogleTv()) return "";   // "Sony default": Sony's own receiver opens it
            switch (keyCode) {
                case KEY_SONY_NETFLIX: return "com.netflix.ninja";
                case KEY_SONY_DISNEY: return "com.disney.disneyplus";
                case KEY_SONY_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_SONY_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_SONY_CRUNCHYROLL: return "com.crunchyroll.crunchyroid";
                case KEY_SONY_LIV: return "com.sonyliv";
                case KEY_SONY_SETTINGS: return SETTINGS_TAB;
                default: return "";
            }
        }
        if (RemoteKeys.XIAOMI_AFKR0) {
            switch (keyCode) {
                case KEY_XIAOMI_NETFLIX: return "com.netflix.ninja";
                case KEY_XIAOMI_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_XIAOMI_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_XIAOMI_SETTINGS: return SETTINGS_TAB;
                default: return "";
            }
        }
        if (RemoteKeys.PHILIPS_GTV) {
            switch (keyCode) {
                case KEY_PHILIPS_APPS: return ALL_APPS;
                case KEY_PHILIPS_SETTINGS: return SETTINGS_TAB;
                default: return "";
            }
        }
        if (RemoteKeys.HISENSE) {
            switch (keyCode) {
                case KEY_HISENSE_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_HISENSE_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_HISENSE_DISNEY: return "com.disney.disneyplus";
                case KEY_HISENSE_APPS: return ALL_APPS;
                case KEY_HISENSE_SETTINGS: return SETTINGS_TAB;
                default: return "";
            }
        }
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_TCL_SETTINGS: return SETTINGS_TAB;   // Open SoarTV's Settings tab directly
            case KEY_XIAOMI_SETTINGS: return SETTINGS_TAB;
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static Boolean sonyGoogleTv;

    /**
     * A Sony with Google TV's home app (launcherx), which takes the app buttons from Sony's receiver and which SoarTV
     * mode turns off: there "Sony default" does nothing (BRAVIA VU31, #395), so the buttons open their apps unless
     * the person picks otherwise. Sony's Android TV sets (VH22) keep Sony's default.
     */
    private static boolean sonyGoogleTv() {
        if (sonyGoogleTv == null) {
            boolean has;
            try {
                app.getPackageManager().getApplicationInfo("com.google.android.apps.tv.launcherx",
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                has = true;
            } catch (PackageManager.NameNotFoundException e) {
                has = false;
            }
            sonyGoogleTv = has;
        }
        return sonyGoogleTv;
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;
    /** Every button saved as Nothing (the key service off): a button added since stays Nothing too, not its default. */
    private static boolean keysAllOff;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                keysAllOff = false;
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                    boolean saved = false, on = false;
                    for (String v : keyApps) if (v != null) { saved = true; on |= !v.isEmpty(); }
                    keysAllOff = saved && !on;
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return keysAllOff ? "" : defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }

    /** GitHub repository ("owner/repo") used for optional OTA self-updates (configured via config.properties or Settings). */
    static String githubRepo() {
        String fallback = "";
        String def = Config.GITHUB_REPO == null || Config.GITHUB_REPO.trim().isEmpty()
                ? fallback : Config.GITHUB_REPO.trim();
        return sp().getString("githubRepo", def).trim();
    }

    static void setGithubRepo(String repo) {
        put("githubRepo", repo == null ? "" : repo.trim());
    }

    /** Optional fine-grained GitHub Personal Access Token (Contents: Read-only) for private repo self-updates. */
    static String githubToken() {
        String given = app != null ? sp().getString("githubToken", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.GITHUB_TOKEN, Config.GITHUB_TOKEN);
    }

    static void setGithubToken(String token) {
        String clean = token == null ? "" : token.trim();
        if (app != null) {
            put("githubToken", clean);
            Secrets.putSecret(app, Secrets.GITHUB_TOKEN, clean);
        }
    }

    // MARK: - Watched Items & Watch List Custom Order

    private static java.util.Set<String> cachedWatchedSet;
    private static int cachedWatchedVer = -1;

    /** Returns the set of media keys ("tv:1396", "movie:603", "tt0903747") explicitly marked as watched in SoarTV. */
    static synchronized java.util.Set<String> watchedItems() {
        if (cachedWatchedSet != null && cachedWatchedVer == version) {
            return cachedWatchedSet;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        if (app != null) {
            String raw = profileSp().getString("watchedItems", "");
            if (raw != null && !raw.isEmpty()) {
                for (String part : raw.split(",")) {
                    String k = part.trim();
                    if (!k.isEmpty()) out.add(k);
                }
            }
        }
        cachedWatchedSet = java.util.Collections.unmodifiableSet(out);
        cachedWatchedVer = version;
        return cachedWatchedSet;
    }

    static boolean isWatchedItem(String key) {
        if (key == null || key.isEmpty()) return false;
        return watchedItems().contains(key);
    }

    static synchronized void replaceWatchedItems(Set<String> keys) {
        if (app == null) return;
        StringBuilder sb = new StringBuilder();
        if (keys != null) {
            for (String k : keys) {
                if (k == null || k.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(k.trim());
            }
        }
        profileSp().edit().putString("watchedItems", sb.toString()).apply();
        version++;
        cachedWatchedSet = null;
    }

    static synchronized void setWatchedItem(String key, boolean watched) {
        if (key == null || key.trim().isEmpty() || app == null) return;
        String trimmed = key.trim();
        java.util.Set<String> next = new java.util.LinkedHashSet<>(watchedItems());
        if (watched) next.add(trimmed);
        else next.remove(trimmed);
        StringBuilder sb = new StringBuilder();
        for (String k : next) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        SharedPreferences.Editor ed = profileSp().edit().putString("watchedItems", sb.toString());
        if (watched) {
            ed.remove("autoUnwatchedSeason:" + trimmed);
        } else {
            ed.remove("watchedSeasons:" + trimmed);
        }
        ed.apply();
        version++;
        cachedWatchedSet = null;
        touchCloud(CloudSyncManager.Scope.PROFILE, "watched_items");
    }

    /** Returns the recorded released-season count when a TV series was marked as watched, or 0 if unknown. */
    static int getWatchedSeriesSeasons(String key) {
        if (key == null || key.trim().isEmpty() || app == null) return 0;
        return profileSp().getInt("watchedSeasons:" + key.trim(), 0);
    }

    /** Records the released-season count for a watched TV series so future seasons can auto-unwatch it. */
    static void setWatchedSeriesSeasons(String key, int seasons) {
        if (key == null || key.trim().isEmpty() || app == null || seasons <= 0) return;
        profileSp().edit().putInt("watchedSeasons:" + key.trim(), seasons).apply();
    }

    /** Returns the released-season number at which this series was auto-unwatched, or 0 if none. */
    static int getAutoUnwatchedSeason(String key) {
        if (key == null || key.trim().isEmpty() || app == null) return 0;
        return profileSp().getInt("autoUnwatchedSeason:" + key.trim(), 0);
    }

    /** Remembers that a series was auto-unwatched for {@code season} so external syncs do not re-gray it prematurely. */
    static void setAutoUnwatchedSeason(String key, int season) {
        if (key == null || key.trim().isEmpty() || app == null || season <= 0) return;
        profileSp().edit().putInt("autoUnwatchedSeason:" + key.trim(), season).apply();
    }

    /** Custom user-reordered media keys for the Watch List row ("tv" or "movie"). */
    static List<String> watchlistOrder(String subKind) {
        List<String> out = new ArrayList<>();
        if (app == null) return out;
        String prefKey = "movie".equalsIgnoreCase(subKind) ? "watchlistOrder_movie" : "watchlistOrder_tv";
        String raw = profileSp().getString(prefKey, "");
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(",")) {
                String k = part.trim();
                if (!k.isEmpty() && !out.contains(k)) out.add(k);
            }
        }
        return out;
    }

    static void setWatchlistOrder(String subKind, List<String> order) {
        if (app == null || order == null) return;
        String prefKey = "movie".equalsIgnoreCase(subKind) ? "watchlistOrder_movie" : "watchlistOrder_tv";
        String cloudKey = "movie".equalsIgnoreCase(subKind) ? "watchlist_order_movie" : "watchlist_order_tv";
        StringBuilder sb = new StringBuilder();
        for (String k : order) {
            if (k == null || k.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(k.trim());
        }
        putProfile(prefKey, sb.toString(), cloudKey);
    }

    /** Clears custom manual order for Watch List so default similarity sorting takes effect. */
    static void clearWatchlistOrder(String subKind) {
        if (app == null) return;
        String prefKey = "movie".equalsIgnoreCase(subKind) ? "watchlistOrder_movie" : "watchlistOrder_tv";
        String cloudKey = "movie".equalsIgnoreCase(subKind) ? "watchlist_order_movie" : "watchlist_order_tv";
        profileSp().edit().remove(prefKey).apply();
        touchCloud(CloudSyncManager.Scope.PROFILE, cloudKey);
    }

    /**
     * One-time migration for Milestone 6: clears stale pre-feature watchlistOrder_* keys
     * so default content similarity sorting takes effect immediately without requiring manual reset.
     */
    static void migrateWatchlistSimilarityOrder() {
        if (app == null) return;
        android.content.SharedPreferences sp = sp();
        if (!sp.getBoolean("watchlistSimMigrated_v1", false)) {
            sp.edit()
                .remove("watchlistOrder_tv")
                .remove("watchlistOrder_movie")
                .putBoolean("watchlistSimMigrated_v1", true)
                .apply();
        }
    }

    /** Raw tri-state feature tab visibility mode ("auto", "true", or "false") stored on the TV. */
    static String rawFeatureMode(String prefKey) {
        if (app == null || prefKey == null || prefKey.isEmpty()) return FeatureTabs.MODE_AUTO;
        return FeatureTabs.normalizeMode(sp().getString(prefKey, FeatureTabs.MODE_AUTO));
    }

    /** Persists a tri-state feature tab visibility mode ("auto", "true", or "false") on the TV. */
    static void setRawFeatureMode(String prefKey, String mode) {
        if (app == null || prefKey == null || prefKey.isEmpty()) return;
        putCommitted(prefKey, FeatureTabs.normalizeMode(mode));
    }

    /** Whether the user has completed or dismissed the guided Initial Setup wizard at least once. */
    static boolean initialSetupDone() {
        return app != null && sp().getBoolean("initialSetupDone", false);
    }

    static void setInitialSetupDone(boolean done) {
        if (app == null) return;
        put("initialSetupDone", done);
    }

    /** Timestamp (epoch ms) when Trakt & Stremio accounts were last synced, or 0 if never synced. */
    static long lastAccountSyncAt() {
        return app != null ? sp().getLong("lastAccountSyncAt", 0L) : 0L;
    }

    static void setLastAccountSyncAt(long epochMs) {
        if (app == null) return;
        sp().edit().putLong("lastAccountSyncAt", epochMs).apply();
        version++;
    }

    /** Runtime override, hardware-encrypted Secrets vault, or Config fallback for OMDb API key. */
    static String omdbKey() {
        String given = app != null ? sp().getString("omdbKey", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.OMDB_KEY, Config.OMDB_KEY);
    }

    static void setOmdbKey(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            put("omdbKey", clean);
            Secrets.putSecret(app, Secrets.OMDB_KEY, clean);
            touchCloudSecret(CloudSyncManager.Scope.HOUSEHOLD, Secrets.OMDB_KEY);
        }
    }

    /** Runtime override, hardware-encrypted Secrets vault, or Config fallback for MDBList API key. */
    static String mdblistKey() {
        String given = app != null ? sp().getString("mdblistKey", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.MDBLIST_KEY, Config.MDBLIST_KEY);
    }

    static void setMdblistKey(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            put("mdblistKey", clean);
            Secrets.putSecret(app, Secrets.MDBLIST_KEY, clean);
            touchCloudSecret(CloudSyncManager.Scope.HOUSEHOLD, Secrets.MDBLIST_KEY);
        }
    }

    /** Runtime override, hardware-encrypted Secrets vault, or Config fallback for Google Gemini API key. */
    static String geminiKey() {
        String given = app != null ? sp().getString("geminiKey", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.GEMINI_API_KEY, Config.GEMINI_API_KEY);
    }

    static void setGeminiKey(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            put("geminiKey", clean);
            Secrets.putSecret(app, Secrets.GEMINI_API_KEY, clean);
            touchCloudSecret(CloudSyncManager.Scope.HOUSEHOLD, Secrets.GEMINI_API_KEY);
        }
    }

    /** Runtime override, hardware-encrypted Secrets bootstrap, or Config fallback for Supabase URL. */
    static String supabaseUrl() {
        String given = app != null ? sp().getString("supabaseUrl", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.SUPABASE_URL, Config.SUPABASE_URL);
    }

    static void setSupabaseUrl(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            put("supabaseUrl", clean);
            Secrets.putSecret(app, Secrets.SUPABASE_URL, clean);
        }
    }

    /** Runtime override, hardware-encrypted Secrets bootstrap, or Config fallback for Supabase Anon Key. */
    static String supabaseAnonKey() {
        String given = app != null ? sp().getString("supabaseAnonKey", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.SUPABASE_ANON_KEY, Config.SUPABASE_ANON_KEY);
    }

    static void setSupabaseAnonKey(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            put("supabaseAnonKey", clean);
            Secrets.putSecret(app, Secrets.SUPABASE_ANON_KEY, clean);
        }
    }

    /** Paired Gaming PC authentication token (stored locally & in hardware-encrypted Secrets vault). Never logged. */
    static String gamingPcToken() {
        String given = app != null ? sp().getString("gamingPcToken", "").trim() : "";
        return resolveKeyWithVaultFallback(given, app, Secrets.GAMING_PC_TOKEN, "");
    }

    static void setGamingPcToken(String v) {
        String clean = v == null ? "" : v.trim();
        if (app != null) {
            sp().edit().putString("gamingPcToken", clean).commit();
        }
        Secrets.putSecret(app, Secrets.GAMING_PC_TOKEN, clean);
        touchCloudSecret(CloudSyncManager.Scope.HOUSEHOLD, Secrets.GAMING_PC_TOKEN);
    }

    // MARK: - Native Gaming Stream Settings (Per-TV, synced in tv_devices.tv_settings)

    public static String gamingResolution() {
        if (app == null) return "1080p";
        String v = sp().getString("gamingResolution", "1080p");
        return (v == null || v.trim().isEmpty()) ? "1080p" : v.trim();
    }

    public static void setGamingResolution(String v) {
        if (app != null) {
            putCommitted("gamingResolution", v == null || v.trim().isEmpty() ? "1080p" : v.trim());
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static int gamingFps() {
        return app != null ? sp().getInt("gamingFps", 60) : 60;
    }

    public static void setGamingFps(int fps) {
        int clamped = (fps == 30 || fps == 60 || fps == 90 || fps == 120) ? fps : 60;
        if (app != null) {
            sp().edit().putInt("gamingFps", clamped).commit();
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static int gamingBitrateMbps() {
        return app != null ? sp().getInt("gamingBitrateMbps", 30) : 30;
    }

    public static void setGamingBitrateMbps(int mbps) {
        int clamped = Math.max(5, Math.min(150, mbps));
        if (app != null) {
            sp().edit().putInt("gamingBitrateMbps", clamped).commit();
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static String gamingFramePacing() {
        if (app == null) return "balanced";
        String v = sp().getString("gamingFramePacing", "balanced");
        return (v == null || v.trim().isEmpty()) ? "balanced" : v.trim();
    }

    public static void setGamingFramePacing(String v) {
        if (app != null) {
            putCommitted("gamingFramePacing", v == null || v.trim().isEmpty() ? "balanced" : v.trim());
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static String gamingCodec() {
        if (app == null) return "auto";
        String v = sp().getString("gamingCodec", "auto");
        return (v == null || v.trim().isEmpty()) ? "auto" : v.trim();
    }

    public static void setGamingCodec(String v) {
        if (app != null) {
            putCommitted("gamingCodec", v == null || v.trim().isEmpty() ? "auto" : v.trim());
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingHdr() {
        return app != null && sp().getBoolean("gamingHdr", false);
    }

    public static void setGamingHdr(boolean enabled) {
        if (app != null) {
            putCommitted("gamingHdr", enabled);
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static String gamingAudioConfig() {
        if (app == null) return "stereo";
        String v = sp().getString("gamingAudioConfig", "stereo");
        return (v == null || v.trim().isEmpty()) ? "stereo" : v.trim();
    }

    public static void setGamingAudioConfig(String v) {
        if (app != null) {
            putCommitted("gamingAudioConfig", v == null || v.trim().isEmpty() ? "stereo" : v.trim());
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingPlayAudioOnPc() {
        return app != null && sp().getBoolean("gamingPlayAudioOnPc", false);
    }

    public static void setGamingPlayAudioOnPc(boolean enabled) {
        if (app != null) {
            putCommitted("gamingPlayAudioOnPc", enabled);
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingRumble() {
        return app == null || sp().getBoolean("gamingRumble", true);
    }

    public static void setGamingRumble(boolean enabled) {
        if (app != null) {
            putCommitted("gamingRumble", enabled);
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingMouseEmulation() {
        return app == null || sp().getBoolean("gamingMouseEmulation", true);
    }

    public static void setGamingMouseEmulation(boolean enabled) {
        if (app != null) {
            putCommitted("gamingMouseEmulation", enabled);
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingPerfOverlay() {
        return app != null && sp().getBoolean("gamingPerfOverlay", false);
    }

    public static void setGamingPerfOverlay(boolean enabled) {
        if (app != null) {
            putCommitted("gamingPerfOverlay", enabled);
            touchCloud(CloudSyncManager.Scope.TV_DEVICE, "gaming_stream");
        }
    }

    public static boolean gamingOptimizeSettings() {
        return app == null || sp().getBoolean("gamingOptimizeSettings", true);
    }

    public static void setGamingOptimizeSettings(boolean enabled) {
        if (app != null) putCommitted("gamingOptimizeSettings", enabled);
    }

    // MARK: - File-Backed JSON Caches (PERF-20: keeps multi-MB catalogs out of SharedPreferences XML)

    static String sanitizeJsonCacheName(String name) {
        if (name == null || name.trim().isEmpty()) return "cache";
        String clean = name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.isEmpty() ? "cache" : clean;
    }

    /** Pure directory-scoped reader for unit tests and runtime file-backed JSON caches. */
    static String readJsonCacheFromDir(File cacheDir, String name) {
        if (cacheDir == null) return null;
        File f = new File(cacheDir, sanitizeJsonCacheName(name) + ".json");
        if (!f.exists() || f.length() == 0) return null;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(f.length(), 1_048_576L))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.w("readJsonCacheFromDir failed for " + name, e);
            return null;
        }
    }

    /** Pure directory-scoped atomic writer (.tmp + fsync + replace) for file-backed JSON caches. */
    static synchronized boolean writeJsonCacheToDir(File cacheDir, String name, String json) {
        if (cacheDir == null) return false;
        String safe = sanitizeJsonCacheName(name);
        File target = new File(cacheDir, safe + ".json");
        if (json == null || json.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return true;
        }
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File tmp = new File(cacheDir, safe + "." + Thread.currentThread().getId() + ".json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            AppLog.w("writeJsonCacheToDir failed for " + safe, e);
            return false;
        }
        if (!tmp.renameTo(target)) {
            // On Windows / certain filesystems, delete existing target before rename
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
        }
        return true;
    }

    static String readJsonCacheFile(String name, String legacyPrefKey) {
        return readJsonCacheFile(app, "settings", name, legacyPrefKey);
    }

    static String readJsonCacheFile(Context context, String legacyPrefsFile, String name, String legacyPrefKey) {
        Context ctx = context != null ? context : app;
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(), "json_cache");
        String cached = readJsonCacheFromDir(dir, name);
        if (cached != null) return cached;
        if (legacyPrefKey != null && !legacyPrefKey.isEmpty() && legacyPrefsFile != null) {
            SharedPreferences legacySp = ctx.getSharedPreferences(legacyPrefsFile, Context.MODE_PRIVATE);
            String legacyJson = legacySp.getString(legacyPrefKey, null);
            if (legacyJson != null && !legacyJson.isEmpty()) {
                if (writeJsonCacheToDir(dir, name, legacyJson)) {
                    legacySp.edit().remove(legacyPrefKey).apply();
                }
                return legacyJson;
            }
        }
        return null;
    }

    static boolean writeJsonCacheFile(String name, String legacyPrefKey, String json) {
        return writeJsonCacheFile(app, "settings", name, legacyPrefKey, json);
    }

    static boolean writeJsonCacheFile(Context context, String legacyPrefsFile, String name, String legacyPrefKey, String json) {
        Context ctx = context != null ? context : app;
        if (ctx == null) return false;
        File dir = new File(ctx.getFilesDir(), "json_cache");
        boolean ok = writeJsonCacheToDir(dir, name, json);
        if (ok && legacyPrefKey != null && !legacyPrefKey.isEmpty() && legacyPrefsFile != null) {
            SharedPreferences legacySp = ctx.getSharedPreferences(legacyPrefsFile, Context.MODE_PRIVATE);
            if (legacySp.contains(legacyPrefKey)) {
                legacySp.edit().remove(legacyPrefKey).apply();
            }
        }
        return ok;
    }
}
