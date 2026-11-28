package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Multi-User profiles on the TV Launcher, synchronized with Supabase ({@code public.user_profiles}).
 *
 * <p><b>Why:</b> The user requires full Multi-User support (Add, Edit, Remove, and Switch profiles) where each profile
 * maintains its own Watch History, Thumbs Up/Down recommendation votes, Trakt username, and Favorite Apps order.
 *
 * <p><b>What:</b> Stores the active user and profile roster locally in {@link SharedPreferences} using canonical
 * {@code snake_case} JSON (with legacy {@code camelCase} read fallbacks) for instant zero-latency startup and syncs
 * changes asynchronously to Supabase via a bounded background executor and {@link SupabaseClient}.
 */
public final class UserProfileManager {

    /** Immutable representation of a SoarTV user profile. */
    public static final class Profile {
        public final String id;
        public final String username;
        public final String displayName;
        public final String avatarColor;
        public final String traktUsername;
        public final boolean isDefault;

        /**
         * Creates an immutable {@link Profile}.
         *
         * @param id            UUID primary key (generated if blank).
         * @param username      Normalized lowercase profile slug.
         * @param displayName   Human-readable profile name.
         * @param avatarColor   Hex swatch color (defaults to {@code "#38BDF8"}).
         * @param traktUsername Optional linked Trakt username.
         * @param isDefault     Whether this profile is the household default.
         */
        public Profile(String id, String username, String displayName, String avatarColor,
                       String traktUsername, boolean isDefault) {
            this.id = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
            this.username = (username == null || username.isEmpty()) ? "user" : username.toLowerCase(Locale.ROOT).trim();
            this.displayName = (displayName == null || displayName.isEmpty()) ? this.username : displayName.trim();
            this.avatarColor = (avatarColor == null || avatarColor.isEmpty()) ? "#38BDF8" : avatarColor;
            this.traktUsername = traktUsername == null ? "" : traktUsername.trim();
            this.isDefault = isDefault;
        }

        /**
         * Builds the canonical {@code snake_case} field map matching {@code public.user_profiles} columns.
         *
         * @param includeUsernameAlias Whether to also include {@code "username"} alongside {@code "slug"} for local/remote compatibility.
         * @return Ordered field map.
         */
        Map<String, Object> toCanonicalMap(boolean includeUsernameAlias) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("slug", username);
            if (includeUsernameAlias) {
                m.put("username", username);
            }
            m.put("display_name", displayName);
            m.put("avatar_color", avatarColor);
            m.put("trakt_username", traktUsername);
            m.put("is_default", isDefault);
            return m;
        }

        /**
         * Serializes this profile into a strict canonical {@code snake_case} JSON object matching
         * {@code public.user_profiles} columns ({@code id, slug, display_name, avatar_color, trakt_username, is_default}).
         *
         * @return UTF-8 JSON object string for PostgREST upsert.
         */
        public String toSupabasePayloadJson() {
            return CloudVaultCrypto.JsonCodec.toJsonObjectString(toCanonicalMap(false));
        }

        /**
         * Serializes this profile into canonical {@code snake_case} JSON (including {@code username} slug alias).
         *
         * @return UTF-8 JSON object string.
         */
        public String toJsonString() {
            return CloudVaultCrypto.JsonCodec.toJsonObjectString(toCanonicalMap(true));
        }

        /**
         * Serializes this profile into an Android {@link JSONObject} for local IPC/HTTP callers.
         *
         * @return Android {@link JSONObject} in canonical {@code snake_case}.
         */
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("slug", username);
                o.put("username", username);
                o.put("display_name", displayName);
                o.put("avatar_color", avatarColor);
                o.put("trakt_username", traktUsername);
                o.put("is_default", isDefault);
            } catch (JSONException e) {
                SupabaseClient.safeLogW("Profile.toJson failed: " + e.getMessage());
            }
            return o;
        }

        /**
         * Deserializes a {@link Profile} from a parsed JSON map, preferring canonical {@code snake_case}
         * keys while falling back to legacy {@code camelCase} keys ({@code displayName}, {@code avatarColor},
         * {@code traktUsername}, {@code isDefault}).
         *
         * @param map Parsed JSON key/value map.
         * @return Reconstructed {@link Profile}, or {@code null} if {@code map} is null or empty.
         */
        static Profile fromJsonMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) return null;
            String slug = CloudVaultCrypto.JsonCodec.optString(
                    map, "slug", CloudVaultCrypto.JsonCodec.optString(map, "username", "user"));
            String display = CloudVaultCrypto.JsonCodec.optString(
                    map, "display_name", CloudVaultCrypto.JsonCodec.optString(map, "displayName", slug));
            String color = CloudVaultCrypto.JsonCodec.optString(
                    map, "avatar_color", CloudVaultCrypto.JsonCodec.optString(map, "avatarColor", "#38BDF8"));
            String trakt = CloudVaultCrypto.JsonCodec.optString(
                    map, "trakt_username", CloudVaultCrypto.JsonCodec.optString(map, "traktUsername", ""));
            boolean def = map.containsKey("is_default")
                    ? CloudVaultCrypto.JsonCodec.optBoolean(map, "is_default", false)
                    : CloudVaultCrypto.JsonCodec.optBoolean(map, "isDefault", false);
            return new Profile(
                    CloudVaultCrypto.JsonCodec.optString(map, "id", ""),
                    slug,
                    display,
                    color,
                    trakt,
                    def
            );
        }

        /**
         * Deserializes a {@link Profile} from a UTF-8 JSON object string, supporting both canonical
         * {@code snake_case} and legacy {@code camelCase} field names.
         *
         * @param json UTF-8 JSON object string.
         * @return Parsed {@link Profile}, or {@code null} if blank/invalid.
         */
        public static Profile fromJsonString(String json) {
            if (json == null || json.trim().isEmpty()) return null;
            try {
                return fromJsonMap(CloudVaultCrypto.JsonCodec.parseJsonObject(json));
            } catch (RuntimeException e) {
                SupabaseClient.safeLogW("Profile.fromJsonString failed: " + e.getMessage());
                return null;
            }
        }

        /**
         * Deserializes a {@link Profile} from an Android {@link JSONObject}, supporting both canonical
         * {@code snake_case} and legacy {@code camelCase} field names.
         *
         * @param o Android {@link JSONObject}.
         * @return Parsed {@link Profile}, or {@code null} if {@code o} is null.
         */
        public static Profile fromJson(JSONObject o) {
            if (o == null) return null;
            String slug = o.optString("slug", o.optString("username", "user"));
            String display = o.optString("display_name", o.optString("displayName", slug));
            String color = o.optString("avatar_color", o.optString("avatarColor", "#38BDF8"));
            String trakt = o.optString("trakt_username", o.optString("traktUsername", ""));
            boolean def = o.has("is_default") ? o.optBoolean("is_default", false) : o.optBoolean("isDefault", false);
            return new Profile(
                    o.optString("id", ""),
                    slug,
                    display,
                    color,
                    trakt,
                    def
            );
        }
    }

    private static final String PREFS_NAME = "soartv_users";
    private static final String KEY_ACTIVE_USERNAME = "active_username";
    private static final String KEY_PROFILES_JSON = "profiles_json";
    private static final String DEFAULT_PROFILE_UUID = "824cf01b-96f3-4a6b-a554-621938e5a280";
    private static final String[] PALETTE = {
            "#38BDF8", "#A855F7", "#EC4899", "#10B981", "#F59E0B", "#6366F1"
    };

    private static final ExecutorService PROFILE_SYNC_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "user-profile-sync");
        t.setDaemon(true);
        return t;
    });

    private static volatile UserProfileManager instance;
    private final Context appContext;
    private final List<Profile> profiles = new ArrayList<>();
    private volatile Profile activeProfile;

    private UserProfileManager(Context context) {
        this.appContext = context.getApplicationContext();
        loadLocal();
    }

    /**
     * Returns the singleton {@link UserProfileManager}.
     *
     * @param context Android context.
     * @return Singleton instance.
     */
    public static UserProfileManager get(Context context) {
        UserProfileManager m = instance;
        if (m == null) {
            synchronized (UserProfileManager.class) {
                m = instance;
                if (m == null) {
                    m = new UserProfileManager(context);
                    instance = m;
                }
            }
        }
        return m;
    }

    private synchronized void loadLocal() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_PROFILES_JSON, "");
        profiles.clear();
        if (raw != null && !raw.isEmpty()) {
            try {
                List<Map<String, Object>> arr = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(raw);
                for (Map<String, Object> item : arr) {
                    Profile p = Profile.fromJsonMap(item);
                    if (p != null) profiles.add(p);
                }
            } catch (RuntimeException e) {
                SupabaseClient.safeLogW("UserProfileManager loadLocal parse failed: " + e.getMessage());
            }
        }
        if (profiles.isEmpty()) {
            String defaultTrakt = Config.TRAKT_USERNAME == null ? "" : Config.TRAKT_USERNAME.trim();
            String defaultUsername = defaultTrakt.isEmpty() ? "main" : defaultTrakt.toLowerCase(Locale.ROOT);
            String defaultDisplay = defaultUsername.substring(0, 1).toUpperCase(Locale.ROOT) + defaultUsername.substring(1);
            profiles.add(new Profile(
                    DEFAULT_PROFILE_UUID,
                    defaultUsername,
                    defaultDisplay,
                    "#38BDF8",
                    defaultTrakt,
                    true
            ));
            saveLocal();
        }
        String activeUser = prefs.getString(KEY_ACTIVE_USERNAME, "");
        Profile found = null;
        for (Profile p : profiles) {
            if (p.username.equalsIgnoreCase(activeUser)) {
                found = p;
                break;
            }
        }
        activeProfile = found != null ? found : profiles.get(0);
    }

    private synchronized void saveLocal() {
        List<Map<String, Object>> arr = new ArrayList<>(profiles.size());
        for (Profile p : profiles) {
            arr.add(p.toCanonicalMap(true));
        }
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PROFILES_JSON, CloudVaultCrypto.JsonCodec.toJsonArrayString(arr))
                .putString(KEY_ACTIVE_USERNAME, activeProfile != null ? activeProfile.username : "")
                .apply();
    }

    /**
     * Synchronizes the local profile list with Supabase {@code public.user_profiles} synchronously on the caller's thread.
     */
    public void syncWithCloudSync() {
        try {
            String remoteRaw = SupabaseClient.get(appContext).fetchProfilesRawJsonSync();
            List<Map<String, Object>> remoteItems = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(remoteRaw);
            if (!remoteItems.isEmpty()) {
                synchronized (UserProfileManager.this) {
                    List<Profile> merged = new ArrayList<>(remoteItems.size());
                    for (Map<String, Object> item : remoteItems) {
                        Profile p = Profile.fromJsonMap(item);
                        if (p != null) merged.add(p);
                    }
                    if (!merged.isEmpty()) {
                        String currentUsername = activeProfile != null ? activeProfile.username : "";
                        profiles.clear();
                        profiles.addAll(merged);
                        Profile match = null;
                        for (Profile p : profiles) {
                            if (p.username.equalsIgnoreCase(currentUsername)) {
                                match = p;
                                break;
                            }
                        }
                        activeProfile = match != null ? match : profiles.get(0);
                        saveLocal();
                    }
                }
            }
        } catch (RuntimeException e) {
            SupabaseClient.safeLogW("UserProfileManager syncWithCloudSync failed: " + e.getMessage());
        }
    }

    /**
     * Synchronizes the local profile list with Supabase {@code public.user_profiles} on a managed background executor.
     *
     * @param onUpdated Optional callback run after sync completes.
     */
    public void syncWithCloudAsync(Runnable onUpdated) {
        PROFILE_SYNC_IO.execute(() -> {
            syncWithCloudSync();
            if (onUpdated != null) onUpdated.run();
        });
    }

    /** @return Immutable list of all configured {@link Profile}s. */
    public synchronized List<Profile> getProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    /** @return The currently active {@link Profile}. */
    public synchronized Profile getActiveProfile() {
        return activeProfile;
    }

    /**
     * Switches the active user profile by username.
     *
     * @param username Target profile's username.
     * @return The newly active {@link Profile}.
     */
    public synchronized Profile switchProfile(String username) {
        if (username == null) return activeProfile;
        for (Profile p : profiles) {
            if (p.username.equalsIgnoreCase(username.trim())) {
                activeProfile = p;
                saveLocal();
                Prefs.version++;
                try {
                    CloudSyncManager.get(appContext).syncNowAsync("profile_switch", null);
                } catch (RuntimeException ignored) {
                }
                return activeProfile;
            }
        }
        return activeProfile;
    }

    /**
     * Cycles to the next user profile in the list (useful for quick single-click remote switching).
     *
     * @return The newly active {@link Profile}.
     */
    public synchronized Profile cycleNextProfile() {
        if (profiles.size() <= 1) return activeProfile;
        int idx = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).username.equalsIgnoreCase(activeProfile.username)) {
                idx = i;
                break;
            }
        }
        activeProfile = profiles.get((idx + 1) % profiles.size());
        saveLocal();
        Prefs.version++;
        try {
            CloudSyncManager.get(appContext).syncNowAsync("profile_switch", null);
        } catch (RuntimeException ignored) {
        }
        return activeProfile;
    }

    /**
     * Adds or updates a user profile and syncs it to Supabase.
     *
     * @param displayName   Human-readable display name.
     * @param traktUsername Optional Trakt username for this profile.
     * @return The created or updated {@link Profile}.
     */
    public synchronized Profile addOrUpdateProfile(String displayName, String traktUsername) {
        if (displayName == null || displayName.trim().isEmpty()) return activeProfile;
        String cleanDisplay = displayName.trim();
        String slug = cleanDisplay.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (slug.isEmpty()) slug = "user" + (profiles.size() + 1);

        int existingIndex = -1;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).username.equalsIgnoreCase(slug)) {
                existingIndex = i;
                break;
            }
        }
        String color = PALETTE[profiles.size() % PALETTE.length];
        Profile p;
        if (existingIndex >= 0) {
            Profile old = profiles.get(existingIndex);
            p = new Profile(old.id, old.username, cleanDisplay, old.avatarColor,
                    traktUsername == null || traktUsername.isEmpty() ? old.traktUsername : traktUsername,
                    old.isDefault);
            profiles.set(existingIndex, p);
        } else {
            p = new Profile(UUID.randomUUID().toString(), slug, cleanDisplay, color, traktUsername, false);
            profiles.add(p);
        }
        activeProfile = p;
        saveLocal();
        Prefs.version++;
        SupabaseClient.get(appContext).upsertProfileJsonStringAsync(p.toSupabasePayloadJson());
        return p;
    }

    /**
     * Removes a user profile by username (preventing removal of the last remaining profile).
     *
     * @param username Username to remove.
     * @return {@code true} if removed.
     */
    public synchronized boolean removeProfile(String username) {
        if (profiles.size() <= 1 || username == null) return false;
        int idx = -1;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).username.equalsIgnoreCase(username.trim())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;
        Profile removed = profiles.remove(idx);
        if (activeProfile != null && activeProfile.username.equalsIgnoreCase(removed.username)) {
            activeProfile = profiles.get(0);
        }
        saveLocal();
        Prefs.version++;
        SupabaseClient.get(appContext).deleteProfileAsync(removed.username);
        return true;
    }
}
