package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cloud synchronization client for Supabase PostgREST ({@code public.user_profiles}, {@code public.watchlist_items},
 * {@code public.watch_history}, {@code public.media_votes}, {@code public.shared_reels}, {@code public.tv_devices},
 * and {@code public.encrypted_vault}).
 *
 * <p><b>Why:</b> Enables Multi-User profiles, cross-app Watchlist, Thumbs Up/Down recommendation voting, Shared
 * Reels/TikToks, Companion Remote auto-discovery, and Zero-Knowledge Encrypted API Key Backups to persist in the cloud
 * and work 24/7 even when the user's PC is powered off.
 *
 * <p><b>What:</b> Performs non-blocking HTTPS REST requests with explicit column projections ({@code PERF-19}),
 * Dual-Path RLS authentication ({@code x-soartv-household-key} + optional Google OAuth user JWT {@code Bearer} token),
 * canonical {@code snake_case} DTO serialization with legacy {@code camelCase} read fallbacks ({@code INC-03}), and
 * local {@link SharedPreferences} offline caching.
 */
public final class SupabaseClient {

    /** HTTP header carrying the household pre-shared secret key for 24/7 non-expiring TV & LAN authorization. */
    public static final String HEADER_HOUSEHOLD_KEY = "x-soartv-household-key";

    /** Secret name in {@link Secrets} and {@link SharedPreferences} for the household authorization key. */
    public static final String SECRET_HOUSEHOLD_KEY = "household_key";

    /** Explicit column projection for {@code public.user_profiles}. */
    public static final String SELECT_USER_PROFILES =
            "id,slug,display_name,avatar_color,trakt_username,is_default,created_at,updated_at";

    /** Explicit column projection for {@code public.watchlist_items}. */
    public static final String SELECT_WATCHLIST_ITEMS =
            "id,profile_id,media_key,imdb_id,kind,title,poster_url,backdrop_url,source_app,deep_link,created_at,updated_at";

    /** Explicit column projection for {@code public.watch_history}. */
    public static final String SELECT_WATCH_HISTORY =
            "id,profile_id,media_key,imdb_id,kind,title,season,episode,progress_percent,is_completed,poster_url,backdrop_url,source_app,watched_at,updated_at";

    /** Explicit column projection for {@code public.media_votes}. */
    public static final String SELECT_MEDIA_VOTES = "media_key,vote";

    /** Explicit column projection for {@code public.shared_reels}. */
    public static final String SELECT_SHARED_REELS =
            "id,sender_name,url,platform,title,thumbnail_url,watched,created_at,updated_at";

    /** Explicit column projection for {@code public.encrypted_vault}. */
    public static final String SELECT_ENCRYPTED_VAULT =
            "vault_id,kdf_algorithm,kdf_salt,kdf_iterations,cipher_iv,ciphertext,key_count,updated_at";

    /** Explicit column projection for {@code public.household_settings}. */
    public static final String SELECT_HOUSEHOLD_SETTINGS =
            "id,settings,field_timestamps,updated_at";

    /** Explicit column projection for {@code public.user_profile_settings}. */
    public static final String SELECT_PROFILE_SETTINGS =
            "profile_id,settings,field_timestamps,updated_at";

    /** Explicit column projection for {@code public.tv_devices}. */
    public static final String SELECT_TV_DEVICES =
            "device_id,device_name,room_name,platform,brand,model,lan_ip,remote_port,mdns_host,realtime_channel,app_version,tv_settings,field_timestamps,is_active,replaced_by,last_seen_at,updated_at";

    /** Explicit column projection for {@code public.gaming_hosts}. */
    public static final String SELECT_GAMING_HOSTS =
            "host_id,hostname,lan_ip,agent_port,mac_addresses,sunshine_uuid,last_seen_at,updated_at";

    private static final String PREFS_NAME = "soartv_supabase_cache";
    private static final String CACHE_ENCRYPTED_VAULT = "encrypted_vault_primary";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int DEFAULT_WATCHLIST_LIMIT = 100;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int DEFAULT_REELS_LIMIT = 24;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "supabase-sync");
        t.setDaemon(true);
        return t;
    });

    private static final String CONFIG_HOUSEHOLD_KEY_FALLBACK = readOptionalConfigField("HOUSEHOLD_KEY");

    /** Represents a shared Reel / TikTok / YouTube Short sent to the TV. */
    public static final class SharedReel {
        public final String id;
        public final String senderName;
        public final String videoUrl;
        public final String platform;
        public final String title;
        public final String thumbnailUrl;
        public final boolean watched;

        /**
         * Creates an immutable {@link SharedReel} DTO.
         *
         * @param id           Database row UUID.
         * @param senderName   Display name of the sender.
         * @param videoUrl     Shared Reel / TikTok / YouTube Short URL.
         * @param platform     Detected platform identifier ({@code tiktok}, {@code instagram}, {@code youtube_shorts}, {@code reel}).
         * @param title        Human-readable video title.
         * @param thumbnailUrl Optional preview thumbnail URL.
         * @param watched      Whether the reel has been marked watched.
         */
        public SharedReel(String id, String senderName, String videoUrl, String platform,
                          String title, String thumbnailUrl, boolean watched) {
            this.id = id == null ? "" : id;
            this.senderName = senderName == null || senderName.isEmpty() ? "Companion" : senderName;
            this.videoUrl = videoUrl == null ? "" : videoUrl;
            this.platform = platform == null || platform.isEmpty() ? detectReelPlatform(this.videoUrl) : platform;
            this.title = title == null || title.isEmpty() ? "Shared Video" : title;
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
            this.watched = watched;
        }
    }

    /** Represents a cloud-synced Watchlist item. */
    public static final class WatchlistEntry {
        public final String id;
        public final String mediaKey;
        public final String mediaType;
        public final String title;
        public final String posterUrl;
        public final String backdropUrl;
        public final String sourceApp;
        public final String deepLink;

        /**
         * Creates an immutable {@link WatchlistEntry} DTO.
         *
         * @param id          Database row UUID.
         * @param mediaKey    Canonical media key (e.g. {@code tt11280740} or {@code tv:1396}).
         * @param mediaType   Normalized media kind ({@code movie} or {@code tv}).
         * @param title       Human-readable title.
         * @param posterUrl   Optional 2:3 poster URL.
         * @param backdropUrl Optional 16:9 backdrop URL.
         * @param sourceApp   Preferred playback app slug.
         * @param deepLink    Optional deep-link URI.
         */
        public WatchlistEntry(String id, String mediaKey, String mediaType, String title,
                              String posterUrl, String backdropUrl, String sourceApp, String deepLink) {
            this.id = id == null ? "" : id;
            this.mediaKey = mediaKey == null ? "" : mediaKey;
            this.mediaType = normalizeKind(mediaType);
            this.title = title == null ? "" : title;
            this.posterUrl = posterUrl;
            this.backdropUrl = backdropUrl;
            this.sourceApp = sourceApp == null || sourceApp.isEmpty() ? "stremio" : sourceApp;
            this.deepLink = deepLink;
        }
    }

    /** Immutable representation of a household TV registered in {@code public.tv_devices}. */
    public static final class TvDeviceRecord {
        public final String deviceId;
        public final String deviceName;
        public final String roomName;
        public final String platform;
        public final String brand;
        public final String model;
        public final String lanIp;
        public final int remotePort;
        public final String mdnsHost;
        public final String realtimeChannel;
        public final String appVersion;
        public final Map<String, Object> tvSettings;
        public final Map<String, Long> fieldTimestamps;
        public final boolean isActive;
        public final String replacedBy;
        public final String lastSeenAt;
        public final String updatedAt;

        public TvDeviceRecord(
                String deviceId,
                String deviceName,
                String roomName,
                String platform,
                String brand,
                String model,
                String lanIp,
                int remotePort,
                String mdnsHost,
                String realtimeChannel,
                String appVersion,
                Map<String, Object> tvSettings,
                Map<String, Long> fieldTimestamps,
                boolean isActive,
                String replacedBy,
                String lastSeenAt,
                String updatedAt) {
            this.deviceId = deviceId == null ? "" : deviceId;
            this.deviceName = deviceName == null || deviceName.isEmpty() ? this.deviceId : deviceName;
            this.roomName = roomName == null || roomName.isEmpty() ? "Living Room" : roomName;
            this.platform = platform == null || platform.isEmpty() ? "android-tv" : platform;
            this.brand = brand == null ? "GENERIC" : brand;
            this.model = model == null ? "" : model;
            this.lanIp = lanIp == null ? "" : lanIp;
            this.remotePort = remotePort;
            this.mdnsHost = mdnsHost == null ? "" : mdnsHost;
            this.realtimeChannel = realtimeChannel == null ? "" : realtimeChannel;
            this.appVersion = appVersion == null ? "2.0" : appVersion;
            this.tvSettings = tvSettings == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(tvSettings));
            this.fieldTimestamps = fieldTimestamps == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(fieldTimestamps));
            this.isActive = isActive;
            this.replacedBy = replacedBy == null ? "" : replacedBy;
            this.lastSeenAt = lastSeenAt == null ? "" : lastSeenAt;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
        }
    }

    /** Immutable representation of a Gaming PC registered in {@code public.gaming_hosts}. */
    public static final class GamingHostRecord {
        public final String hostId;
        public final String hostname;
        public final String lanIp;
        public final int agentPort;
        public final List<String> macAddresses;
        public final String sunshineUuid;
        public final String lastSeenAt;

        public GamingHostRecord(
                String hostId,
                String hostname,
                String lanIp,
                int agentPort,
                List<String> macAddresses,
                String sunshineUuid,
                String lastSeenAt) {
            this.hostId = hostId == null ? "" : hostId;
            this.hostname = hostname == null ? "" : hostname;
            this.lanIp = lanIp == null ? "" : lanIp;
            this.agentPort = agentPort > 0 ? agentPort : 8485;
            this.macAddresses = macAddresses == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(macAddresses));
            this.sunshineUuid = sunshineUuid == null ? "" : sunshineUuid;
            this.lastSeenAt = lastSeenAt == null ? "" : lastSeenAt;
        }
    }

    /** Immutable representation of a timestamped settings document ({@code household_settings} or {@code user_profile_settings}). */
    public static final class SettingsDocument {
        public final Map<String, Object> settings;
        public final Map<String, Long> fieldTimestamps;
        public final String updatedAt;

        public SettingsDocument(Map<String, Object> settings, Map<String, Long> fieldTimestamps, String updatedAt) {
            this.settings = settings == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            this.fieldTimestamps = fieldTimestamps == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(fieldTimestamps));
            this.updatedAt = updatedAt == null ? "" : updatedAt;
        }
    }

    private static volatile SupabaseClient instance;
    private final Context appContext;
    private volatile String householdKeyOverride = "";
    private volatile String userAccessToken = "";
    private final Map<String, Map<String, Integer>> memVotes = new ConcurrentHashMap<>();
    private final Map<String, List<WatchlistEntry>> memWatchlist = new ConcurrentHashMap<>();

    private SupabaseClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Returns the singleton {@link SupabaseClient}.
     *
     * @param context Android context.
     * @return The singleton instance.
     */
    public static SupabaseClient get(Context context) {
        SupabaseClient s = instance;
        if (s == null) {
            synchronized (SupabaseClient.class) {
                s = instance;
                if (s == null) {
                    s = new SupabaseClient(context);
                    instance = s;
                }
            }
        }
        return s;
    }

    private String getBaseUrl() {
        return trimSlash(Prefs.supabaseUrl());
    }

    private String getAnonKey() {
        return Prefs.supabaseAnonKey();
    }

    /**
     * Sets (and persists locally in {@code soartv_supabase_cache}) the household authorization key sent via
     * {@code x-soartv-household-key} for Dual-Path RLS access.
     *
     * @param key 256-bit hex household key (or empty string to clear runtime override).
     */
    public void setHouseholdKey(String key) {
        String clean = key == null ? "" : key.trim();
        this.householdKeyOverride = clean;
        if (appContext != null) {
            cache().edit().putString(SECRET_HOUSEHOLD_KEY, clean).apply();
        }
    }

    /**
     * Resolves the active household authorization key from runtime override, {@link Secrets} provider storage,
     * {@link SharedPreferences} {@code soartv_supabase_cache}, or optional build config fallback.
     *
     * @return Trimmed household key, or {@code ""} if none is configured.
     */
    public String resolveHouseholdKey() {
        String override = this.householdKeyOverride;
        if (override != null && !override.isEmpty()) {
            return override;
        }
        if (appContext != null) {
            String fromSecrets = Secrets.get(appContext, SECRET_HOUSEHOLD_KEY);
            if (fromSecrets != null && !fromSecrets.trim().isEmpty()) {
                return fromSecrets.trim();
            }
            String fromCache = cache().getString(SECRET_HOUSEHOLD_KEY, "");
            if (fromCache != null && !fromCache.trim().isEmpty()) {
                return fromCache.trim();
            }
        }
        return CONFIG_HOUSEHOLD_KEY_FALLBACK;
    }

    /**
     * Sets an authenticated Supabase / Google OAuth user JWT access token. When non-empty, requests send
     * {@code Authorization: Bearer <jwt>} instead of the anonymous project key, satisfying the Google Admin
     * identity branch of {@code public.is_authorized_soartv_request()}.
     *
     * @param jwt Verified Supabase session access token, or {@code null}/{@code ""} to revert to anon Bearer header.
     */
    public void setUserAccessToken(String jwt) {
        this.userAccessToken = jwt == null ? "" : jwt.trim();
    }

    /**
     * Returns the currently configured Supabase / Google OAuth user access token, or {@code ""} if unset.
     *
     * @return Active user JWT or empty string.
     */
    public String getUserAccessToken() {
        return this.userAccessToken;
    }

    /** @return {@code true} if Supabase URL and anon key are configured. */
    public boolean isConfigured() {
        return !getBaseUrl().isEmpty() && !getAnonKey().isEmpty();
    }

    // MARK: - Explicit PostgREST Query Path & Header Builders (PERF-19, SEC-09)

    /**
     * Builds the PostgREST path for fetching user profiles with explicit column projection.
     *
     * @return Relative REST path without {@code select=*}.
     */
    public static String buildProfilesQueryPath() {
        return "/rest/v1/user_profiles?select=" + SELECT_USER_PROFILES + "&order=is_default.desc,created_at.asc";
    }

    /**
     * Builds the PostgREST path for fetching a profile's watchlist items ordered by {@code created_at DESC}.
     *
     * @param profileId Profile UUID.
     * @return Relative REST path using composite index {@code (profile_id, created_at DESC)}.
     */
    public static String buildWatchlistQueryPath(String profileId) {
        return "/rest/v1/watchlist_items?select=" + SELECT_WATCHLIST_ITEMS
                + "&profile_id=eq." + urlEncode(profileId)
                + "&order=created_at.desc&limit=" + DEFAULT_WATCHLIST_LIMIT;
    }

    /**
     * Builds the PostgREST path for fetching a profile's watch history ordered by {@code watched_at DESC}.
     *
     * @param profileId Profile UUID.
     * @return Relative REST path using composite index {@code (profile_id, watched_at DESC)}.
     */
    public static String buildWatchHistoryQueryPath(String profileId) {
        return "/rest/v1/watch_history?select=" + SELECT_WATCH_HISTORY
                + "&profile_id=eq." + urlEncode(profileId)
                + "&order=watched_at.desc&limit=" + DEFAULT_HISTORY_LIMIT;
    }

    /**
     * Builds the PostgREST path for fetching a profile's thumbs up/down votes.
     *
     * @param profileId Profile UUID.
     * @return Relative REST path projecting {@code media_key,vote}.
     */
    public static String buildVotesQueryPath(String profileId) {
        return "/rest/v1/media_votes?select=" + SELECT_MEDIA_VOTES
                + "&profile_id=eq." + urlEncode(profileId);
    }

    /**
     * Builds the PostgREST path for fetching shared Reels / TikToks / Shorts with explicit column projection.
     *
     * @param unwatchedOnly When {@code true}, filters {@code watched=eq.false} to hit {@code (watched, created_at DESC)}.
     * @param limit         Maximum rows to return (clamped to {@code 1..100}).
     * @return Relative REST path.
     */
    public static String buildSharedReelsQueryPath(boolean unwatchedOnly, int limit) {
        int clamped = Math.max(1, Math.min(100, limit));
        String filter = unwatchedOnly ? "&watched=eq.false" : "";
        return "/rest/v1/shared_reels?select=" + SELECT_SHARED_REELS
                + filter + "&order=created_at.desc&limit=" + clamped;
    }

    /**
     * Builds the PostgREST path for fetching an encrypted cloud key backup envelope.
     *
     * @param vaultId Vault identifier (defaults to {@code "primary"} if blank).
     * @return Relative REST path with explicit column projection.
     */
    public static String buildEncryptedVaultQueryPath(String vaultId) {
        String id = (vaultId == null || vaultId.trim().isEmpty())
                ? CloudVaultCrypto.DEFAULT_VAULT_ID : vaultId.trim();
        return "/rest/v1/encrypted_vault?select=" + SELECT_ENCRYPTED_VAULT
                + "&vault_id=eq." + urlEncode(id) + "&limit=1";
    }

    /** Builds the PostgREST path for fetching {@code public.household_settings}. */
    public static String buildHouseholdSettingsQueryPath() {
        return "/rest/v1/household_settings?select=" + SELECT_HOUSEHOLD_SETTINGS + "&id=eq.1&limit=1";
    }

    /** Builds the PostgREST path for fetching a profile's {@code public.user_profile_settings} row. */
    public static String buildProfileSettingsQueryPath(String profileId) {
        return "/rest/v1/user_profile_settings?select=" + SELECT_PROFILE_SETTINGS
                + "&profile_id=eq." + urlEncode(profileId) + "&limit=1";
    }

    /** Builds the PostgREST path for listing {@code public.tv_devices}. */
    public static String buildTvDevicesQueryPath(boolean activeOnly) {
        String filter = activeOnly ? "&is_active=eq.true" : "";
        return "/rest/v1/tv_devices?select=" + SELECT_TV_DEVICES + filter + "&order=last_seen_at.desc";
    }

    /** Builds the PostgREST path for fetching a single TV row from {@code public.tv_devices}. */
    public static String buildTvDeviceQueryPath(String deviceId) {
        return "/rest/v1/tv_devices?select=" + SELECT_TV_DEVICES
                + "&device_id=eq." + urlEncode(deviceId) + "&limit=1";
    }

    /** Builds the PostgREST path for listing {@code public.gaming_hosts}. */
    public static String buildGamingHostsQueryPath() {
        return "/rest/v1/gaming_hosts?select=" + SELECT_GAMING_HOSTS + "&order=last_seen_at.desc";
    }

    /**
     * Constructs Dual-Path PostgREST authentication headers combining the project {@code apikey},
     * either an authenticated Google OAuth user JWT or the project anon key in {@code Authorization},
     * and the optional non-expiring {@code x-soartv-household-key}.
     *
     * @param anonKey         Supabase project publishable/anon key.
     * @param householdKey    Optional 256-bit household secret key.
     * @param userAccessToken Optional Supabase / Google OAuth user session JWT.
     * @param preferHeader    Optional PostgREST {@code Prefer} directive.
     * @return Ordered map of HTTP request headers.
     */
    public static Map<String, String> buildAuthHeaders(String anonKey, String householdKey,
                                                       String userAccessToken, String preferHeader) {
        Map<String, String> headers = new LinkedHashMap<>();
        String cleanAnon = anonKey == null ? "" : anonKey.trim();
        String cleanJwt = userAccessToken == null ? "" : userAccessToken.trim();
        String cleanHousehold = householdKey == null ? "" : householdKey.trim();
        String bearer = !cleanJwt.isEmpty() ? cleanJwt : cleanAnon;

        headers.put("apikey", cleanAnon);
        headers.put("Authorization", "Bearer " + bearer);
        headers.put("Accept", "application/json");
        if (!cleanHousehold.isEmpty()) {
            headers.put(HEADER_HOUSEHOLD_KEY, cleanHousehold);
        }
        if (preferHeader != null && !preferHeader.trim().isEmpty()) {
            headers.put("Prefer", preferHeader.trim());
        }
        return headers;
    }

    // MARK: - Device & Profile Sync

    /**
     * Registers or updates this TV device's LAN IP and remote server port in {@code public.tv_devices}
     * so authorized household phones can locate the TV automatically.
     *
     * @param deviceId   Unique stable identifier for this TV.
     * @param deviceName Friendly name (e.g. {@code "Living Room MiTV"}).
     * @param lanIp      Current site-local IPv4 address.
     * @param port       Companion Remote HTTP/WebSocket port ({@code 1..65535}).
     */
    public void registerTvDeviceAsync(String deviceId, String deviceName, String lanIp, int port) {
        if (!isConfigured() || deviceId == null || deviceId.isEmpty()) return;
        if (port < 0 || port > 65535) {
            safeLogW("Supabase registerTvDevice rejected invalid port: " + port);
            return;
        }
        IO.execute(() -> {
            try {
                TvDeviceProfile profile = TvDeviceProfile.current();
                String roomName = "Living Room";
                try {
                    roomName = SmartHomeManager.get(appContext).getTvRoom();
                } catch (RuntimeException ignored) {
                }
                String mdnsHost = "";
                try {
                    mdnsHost = TvDeviceIdentity.get(appContext).getMdnsHost();
                } catch (RuntimeException ignored) {
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("device_id", deviceId);
                payload.put("device_name", deviceName == null || deviceName.isEmpty() ? deviceId : deviceName);
                payload.put("room_name", roomName);
                payload.put("platform", TvDeviceIdentity.PLATFORM_ANDROID_TV);
                payload.put("brand", profile.getBrand().name());
                payload.put("model", profile.getModel());
                payload.put("lan_ip", lanIp == null ? "" : lanIp);
                payload.put("remote_port", port > 0 ? port : 8484);
                payload.put("mdns_host", mdnsHost);
                payload.put("is_active", true);
                payload.put("last_seen_at", isoNowUtc());
                upsertTvDeviceSync(payload);
            } catch (IOException e) {
                safeLogW("Supabase tv_devices sync failed: " + e.getMessage());
            }
        });
    }

    /** Fetches {@code public.household_settings} row (returns {@code null} if unreachable or empty). */
    public SettingsDocument fetchHouseholdSettingsSync() throws IOException {
        if (!isConfigured()) return null;
        String resp = request("GET", buildHouseholdSettingsQueryPath(), null, null);
        return parseSettingsDocument(resp);
    }

    /** Upserts {@code public.household_settings} row {@code id = 1}. */
    public void upsertHouseholdSettingsSync(Map<String, Object> settings, Map<String, Long> fieldTimestamps) throws IOException {
        if (!isConfigured()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", 1);
        payload.put("settings", settings == null ? Collections.emptyMap() : settings);
        payload.put("field_timestamps", fieldTimestamps == null ? Collections.emptyMap() : fieldTimestamps);
        request("POST", "/rest/v1/household_settings?on_conflict=id",
                CloudVaultCrypto.JsonCodec.toJsonObjectString(payload),
                "resolution=merge-duplicates,return=minimal");
    }

    /** Fetches a user profile's {@code public.user_profile_settings} row. */
    public SettingsDocument fetchProfileSettingsSync(String profileId) throws IOException {
        if (!isConfigured() || !isUuid(profileId)) return null;
        String resp = request("GET", buildProfileSettingsQueryPath(profileId), null, null);
        return parseSettingsDocument(resp);
    }

    /** Upserts a user profile's {@code public.user_profile_settings} row. */
    public void upsertProfileSettingsSync(String profileId, Map<String, Object> settings, Map<String, Long> fieldTimestamps) throws IOException {
        if (!isConfigured() || !isUuid(profileId)) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile_id", profileId);
        payload.put("settings", settings == null ? Collections.emptyMap() : settings);
        payload.put("field_timestamps", fieldTimestamps == null ? Collections.emptyMap() : fieldTimestamps);
        request("POST", "/rest/v1/user_profile_settings?on_conflict=profile_id",
                CloudVaultCrypto.JsonCodec.toJsonObjectString(payload),
                "resolution=merge-duplicates,return=minimal");
    }

    /** Fetches all registered TV devices (active only when {@code activeOnly} is true). */
    public List<TvDeviceRecord> fetchTvDevicesSync(boolean activeOnly) throws IOException {
        if (!isConfigured()) return Collections.emptyList();
        String resp = request("GET", buildTvDevicesQueryPath(activeOnly), null, null);
        if (appContext != null && activeOnly && resp != null && resp.trim().startsWith("[")) {
            cache().edit().putString("tv_devices_active", resp).apply();
        }
        return parseTvDevices(resp);
    }

    /** Returns the cached list of active household TVs for instant offline UI rendering. */
    public List<TvDeviceRecord> getCachedActiveTvDevices() {
        if (appContext == null) return Collections.emptyList();
        return parseTvDevices(cache().getString("tv_devices_active", "[]"));
    }

    /** Fetches a single TV device row from {@code public.tv_devices} by permanent {@code deviceId}. */
    public TvDeviceRecord fetchTvDeviceSync(String deviceId) throws IOException {
        if (!isConfigured() || deviceId == null || deviceId.trim().isEmpty()) return null;
        String resp = request("GET", buildTvDeviceQueryPath(deviceId.trim()), null, null);
        List<TvDeviceRecord> list = parseTvDevices(resp);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Upserts a TV device row into {@code public.tv_devices} keyed by {@code device_id}. */
    public void upsertTvDeviceSync(Map<String, Object> payload) throws IOException {
        if (!isConfigured() || payload == null || payload.isEmpty()) return;
        request("POST", "/rest/v1/tv_devices?on_conflict=device_id",
                CloudVaultCrypto.JsonCodec.toJsonObjectString(payload),
                "resolution=merge-duplicates,return=minimal");
    }

    /** Marks a replaced or retired TV inactive in {@code public.tv_devices}. */
    public void deactivateTvDeviceSync(String oldDeviceId, String replacedByDeviceId) throws IOException {
        if (!isConfigured() || oldDeviceId == null || oldDeviceId.trim().isEmpty()) return;
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("is_active", false);
        patch.put("replaced_by", replacedByDeviceId == null ? "" : replacedByDeviceId.trim());
        request("PATCH", "/rest/v1/tv_devices?device_id=eq." + urlEncode(oldDeviceId.trim()),
                CloudVaultCrypto.JsonCodec.toJsonObjectString(patch),
                "return=minimal");
    }

    /** Fetches paired Gaming PCs from {@code public.gaming_hosts}. */
    public List<GamingHostRecord> fetchGamingHostsSync() throws IOException {
        if (!isConfigured()) return Collections.emptyList();
        String resp = request("GET", buildGamingHostsQueryPath(), null, null);
        if (appContext != null && resp != null && resp.trim().startsWith("[")) {
            cache().edit().putString("gaming_hosts", resp).apply();
        }
        return parseGamingHosts(resp);
    }

    /** Returns cached {@code public.gaming_hosts} entries for fast offline/fallback lookup. */
    public List<GamingHostRecord> getCachedGamingHosts() {
        if (appContext == null) return Collections.emptyList();
        return parseGamingHosts(cache().getString("gaming_hosts", "[]"));
    }

    /** Upserts a Gaming PC record into {@code public.gaming_hosts}. */
    public void upsertGamingHostSync(Map<String, Object> payload) throws IOException {
        if (!isConfigured() || payload == null || payload.isEmpty()) return;
        request("POST", "/rest/v1/gaming_hosts?on_conflict=host_id",
                CloudVaultCrypto.JsonCodec.toJsonObjectString(payload),
                "resolution=merge-duplicates,return=minimal");
    }

    /**
     * Fetches raw JSON array of user profiles from {@code public.user_profiles}, caching them locally.
     *
     * @return Raw UTF-8 JSON array string.
     */
    String fetchProfilesRawJsonSync() {
        SharedPreferences prefs = cache();
        if (isConfigured()) {
            try {
                String resp = request("GET", buildProfilesQueryPath(), null, null);
                if (resp != null && resp.trim().startsWith("[")) {
                    prefs.edit().putString("profiles", resp).apply();
                    return resp;
                }
            } catch (IOException e) {
                safeLogW("Supabase fetchProfiles fallback to cache: " + e.getMessage());
            }
        }
        return prefs.getString("profiles", "[]");
    }

    /**
     * Fetches all user profiles from {@code public.user_profiles}, caching them locally.
     *
     * @return {@link JSONArray} of user profile objects.
     */
    public JSONArray fetchProfilesSync() {
        String raw = fetchProfilesRawJsonSync();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            safeLogW("Supabase fetchProfiles JSON parse failed: " + e.getMessage());
            return new JSONArray();
        }
    }

    /**
     * Upserts a user profile JSON object to {@code public.user_profiles} using canonical {@code snake_case} columns.
     *
     * @param profileJson Android {@link JSONObject} containing profile fields.
     */
    public void upsertProfileAsync(JSONObject profileJson) {
        if (profileJson == null) return;
        upsertProfileJsonStringAsync(profileJson.toString());
    }

    /**
     * Upserts a serialized profile JSON payload to {@code public.user_profiles}.
     *
     * @param profileJsonString Serialized JSON object containing profile fields.
     */
    public void upsertProfileJsonStringAsync(String profileJsonString) {
        if (!isConfigured() || profileJsonString == null || profileJsonString.trim().isEmpty()) return;
        IO.execute(() -> {
            try {
                UserProfileManager.Profile parsed = UserProfileManager.Profile.fromJsonString(profileJsonString);
                if (parsed == null) return;
                request("POST", "/rest/v1/user_profiles?on_conflict=slug",
                        parsed.toSupabasePayloadJson(),
                        "resolution=merge-duplicates,return=minimal");
            } catch (IOException e) {
                safeLogW("Supabase upsertProfile failed: " + e.getMessage());
            }
        });
    }

    /**
     * Deletes a user profile by username/slug from {@code public.user_profiles}.
     *
     * @param username Profile slug to delete.
     */
    public void deleteProfileAsync(String username) {
        if (!isConfigured() || username == null || username.isEmpty()) return;
        IO.execute(() -> {
            try {
                request("DELETE", "/rest/v1/user_profiles?slug=eq." + urlEncode(username), null, "return=minimal");
            } catch (IOException e) {
                safeLogW("Supabase deleteProfile failed: " + e.getMessage());
            }
        });
    }

    // MARK: - Media Votes

    /**
     * Fast, non-blocking, in-memory cached getter for the active user's thumbs up/down votes.
     * Safe to call on the 60fps UI thread.
     *
     * @param userId Active user UUID.
     * @return Unmodifiable map of {@code media_key -> +1/-1}.
     */
    public Map<String, Integer> getCachedVotes(String userId) {
        String cacheKey = "votes:" + (userId == null ? "default" : userId);
        Map<String, Integer> cached = memVotes.get(cacheKey);
        if (cached != null) return cached;
        Map<String, Integer> parsed = Collections.unmodifiableMap(parseVotes(cache().getString(cacheKey, "[]")));
        memVotes.put(cacheKey, parsed);
        return parsed;
    }

    /**
     * Fetches the active user's thumbs up/down votes ({@code media_key -> +1 or -1}).
     *
     * @param userId UUID of the active user profile.
     * @return Map of {@code mediaKey} to vote value ({@code 1} for Thumbs Up, {@code -1} for Thumbs Down).
     */
    public Map<String, Integer> fetchVotesSync(String userId) {
        SharedPreferences prefs = cache();
        String cacheKey = "votes:" + (userId == null ? "default" : userId);
        if (isConfigured() && userId != null && !userId.isEmpty() && isUuid(userId)) {
            try {
                String resp = request("GET", buildVotesQueryPath(userId), null, null);
                prefs.edit().putString(cacheKey, resp).apply();
                Map<String, Integer> parsed = Collections.unmodifiableMap(parseVotes(resp));
                memVotes.put(cacheKey, parsed);
                return parsed;
            } catch (IOException e) {
                safeLogW("Supabase fetchVotes fallback: " + e.getMessage());
            }
        }
        Map<String, Integer> parsed = Collections.unmodifiableMap(parseVotes(prefs.getString(cacheKey, "[]")));
        memVotes.put(cacheKey, parsed);
        return parsed;
    }

    /**
     * Casts a Thumbs Up ({@code +1}) or Thumbs Down ({@code -1}) vote for a media item on behalf of the active user.
     *
     * @param userId    Active user UUID.
     * @param mediaKey  Media identifier (e.g., {@code "movie:1234"} or {@code "tt1234567"}).
     * @param mediaType {@code "movie"} or {@code "tv"}.
     * @param title     Human-readable title.
     * @param vote      {@code 1} (Thumbs Up) or {@code -1} (Thumbs Down).
     * @param onDone    Optional callback invoked on completion.
     */
    public void castVoteAsync(String userId, String mediaKey, String mediaType, String title, int vote, Runnable onDone) {
        if (mediaKey == null || mediaKey.isEmpty()) return;
        int normalized = vote >= 0 ? 1 : -1;
        SharedPreferences prefs = cache();
        String cacheKey = "votes:" + (userId == null ? "default" : userId);
        try {
            List<Map<String, Object>> cached = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(
                    prefs.getString(cacheKey, "[]"));
            List<Map<String, Object>> updated = new ArrayList<>(cached.size() + 1);
            for (Map<String, Object> o : cached) {
                String existingKey = CloudVaultCrypto.JsonCodec.optString(
                        o, "media_key", CloudVaultCrypto.JsonCodec.optString(o, "mediaKey", ""));
                if (!mediaKey.equals(existingKey)) {
                    updated.add(o);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("media_key", mediaKey);
            entry.put("vote", normalized);
            updated.add(entry);
            String jsonStr = CloudVaultCrypto.JsonCodec.toJsonArrayString(updated);
            prefs.edit().putString(cacheKey, jsonStr).apply();
            memVotes.put(cacheKey, Collections.unmodifiableMap(parseVotes(jsonStr)));
        } catch (RuntimeException e) {
            safeLogW("Supabase local vote cache update failed: " + e.getMessage());
        }
        IO.execute(() -> {
            if (isConfigured() && userId != null && isUuid(userId)) {
                try {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("profile_id", userId);
                    payload.put("media_key", mediaKey);
                    payload.put("kind", normalizeKind(mediaType));
                    payload.put("title", title == null ? "" : title);
                    payload.put("vote", normalized);
                    request("POST", "/rest/v1/media_votes?on_conflict=profile_id,media_key",
                            CloudVaultCrypto.JsonCodec.toJsonObjectString(payload),
                            "resolution=merge-duplicates,return=minimal");
                } catch (IOException e) {
                    safeLogW("Supabase castVote failed: " + e.getMessage());
                }
            }
            if (onDone != null) onDone.run();
        });
    }

    // MARK: - Watchlist Sync

    /**
     * Fast, non-blocking, in-memory cached getter for the active user's Watchlist entries.
     * Safe to call on the 60fps UI thread.
     *
     * @param userId Active user UUID.
     * @return Unmodifiable list of {@link WatchlistEntry} items.
     */
    public List<WatchlistEntry> getCachedWatchlist(String userId) {
        String cacheKey = "watchlist:" + (userId == null ? "default" : userId);
        List<WatchlistEntry> cached = memWatchlist.get(cacheKey);
        if (cached != null) return cached;
        List<WatchlistEntry> parsed = Collections.unmodifiableList(parseWatchlist(cache().getString(cacheKey, "[]")));
        memWatchlist.put(cacheKey, parsed);
        return parsed;
    }

    /**
     * Fetches the active user's Watchlist items from {@code public.watchlist_items}.
     *
     * @param userId Active user UUID.
     * @return List of {@link WatchlistEntry} items.
     */
    public List<WatchlistEntry> fetchWatchlistSync(String userId) {
        SharedPreferences prefs = cache();
        String cacheKey = "watchlist:" + (userId == null ? "default" : userId);
        if (isConfigured() && userId != null && isUuid(userId)) {
            try {
                String resp = request("GET", buildWatchlistQueryPath(userId), null, null);
                prefs.edit().putString(cacheKey, resp).apply();
                List<WatchlistEntry> parsed = Collections.unmodifiableList(parseWatchlist(resp));
                memWatchlist.put(cacheKey, parsed);
                return parsed;
            } catch (IOException e) {
                safeLogW("Supabase fetchWatchlist fallback: " + e.getMessage());
            }
        }
        List<WatchlistEntry> parsed = Collections.unmodifiableList(parseWatchlist(prefs.getString(cacheKey, "[]")));
        memWatchlist.put(cacheKey, parsed);
        return parsed;
    }

    /**
     * Builds canonical {@code snake_case} JSON for a {@code public.watchlist_items} row.
     *
     * @param userId      Optional profile UUID.
     * @param mediaKey    Media identifier.
     * @param mediaType   Media kind ({@code "movie"}, {@code "tv"}, or {@code "series"}).
     * @param title       Human-readable title.
     * @param posterUrl   Optional poster URL.
     * @param backdropUrl Optional backdrop URL.
     * @param sourceApp   Playback app identifier.
     * @param deepLink    Optional deep-link URI.
     * @return Canonical {@code snake_case} JSON object string.
     */
    public static String buildWatchlistPayloadJson(String userId, String mediaKey, String mediaType, String title,
                                                   String posterUrl, String backdropUrl, String sourceApp, String deepLink) {
        return CloudVaultCrypto.JsonCodec.toJsonObjectString(
                buildWatchlistPayloadMap(userId, mediaKey, mediaType, title, posterUrl, backdropUrl, sourceApp, deepLink));
    }

    private static Map<String, Object> buildWatchlistPayloadMap(String userId, String mediaKey, String mediaType,
                                                                String title, String posterUrl, String backdropUrl,
                                                                String sourceApp, String deepLink) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (userId != null && isUuid(userId)) {
            payload.put("profile_id", userId);
        }
        String cleanKey = mediaKey == null ? "" : mediaKey.trim();
        payload.put("media_key", cleanKey);
        if (cleanKey.startsWith("tt")) {
            payload.put("imdb_id", cleanKey);
        }
        payload.put("kind", normalizeKind(mediaType));
        payload.put("title", title == null ? "" : title);
        if (posterUrl != null && !posterUrl.isEmpty()) {
            payload.put("poster_url", posterUrl);
        }
        if (backdropUrl != null && !backdropUrl.isEmpty()) {
            payload.put("backdrop_url", backdropUrl);
        }
        payload.put("source_app", (sourceApp == null || sourceApp.isEmpty()) ? "stremio" : sourceApp);
        if (deepLink != null && !deepLink.isEmpty()) {
            payload.put("deep_link", deepLink);
        }
        return payload;
    }

    /**
     * Adds an item to the active user's Watchlist in {@code public.watchlist_items}.
     */
    public void addWatchlistItemAsync(String userId, String mediaKey, String mediaType, String title,
                                      String posterUrl, String backdropUrl, String sourceApp, String deepLink,
                                      Runnable onDone) {
        if (mediaKey == null || title == null) return;
        SharedPreferences prefs = cache();
        String cacheKey = "watchlist:" + (userId == null ? "default" : userId);
        Map<String, Object> canonicalRow = buildWatchlistPayloadMap(
                userId, mediaKey, mediaType, title, posterUrl, backdropUrl, sourceApp, deepLink);
        try {
            List<Map<String, Object>> arr = new ArrayList<>(
                    CloudVaultCrypto.JsonCodec.parseJsonObjectArray(prefs.getString(cacheKey, "[]")));
            arr.removeIf(existing -> mediaKey.equals(
                    CloudVaultCrypto.JsonCodec.optString(existing, "media_key",
                            CloudVaultCrypto.JsonCodec.optString(existing, "mediaKey", ""))));
            arr.add(0, canonicalRow);
            String jsonStr = CloudVaultCrypto.JsonCodec.toJsonArrayString(arr);
            prefs.edit().putString(cacheKey, jsonStr).apply();
            memWatchlist.put(cacheKey, Collections.unmodifiableList(parseWatchlist(jsonStr)));
        } catch (RuntimeException e) {
            safeLogW("Supabase local watchlist cache update failed: " + e.getMessage());
        }
        IO.execute(() -> {
            if (isConfigured() && userId != null && isUuid(userId)) {
                try {
                    request("POST", "/rest/v1/watchlist_items?on_conflict=profile_id,media_key",
                            CloudVaultCrypto.JsonCodec.toJsonObjectString(canonicalRow),
                            "resolution=merge-duplicates,return=minimal");
                } catch (IOException e) {
                    safeLogW("Supabase addWatchlistItem failed: " + e.getMessage());
                }
            }
            if (onDone != null) onDone.run();
        });
    }

    /**
     * Removes an item from the active user's Watchlist in {@code public.watchlist_items}.
     */
    public void removeWatchlistItemAsync(String userId, String mediaKey, Runnable onDone) {
        if (mediaKey == null || mediaKey.isEmpty()) return;
        SharedPreferences prefs = cache();
        String cacheKey = "watchlist:" + (userId == null ? "default" : userId);
        try {
            List<Map<String, Object>> arr = new ArrayList<>(
                    CloudVaultCrypto.JsonCodec.parseJsonObjectArray(prefs.getString(cacheKey, "[]")));
            arr.removeIf(existing -> mediaKey.equals(
                    CloudVaultCrypto.JsonCodec.optString(existing, "media_key",
                            CloudVaultCrypto.JsonCodec.optString(existing, "mediaKey", ""))));
            String jsonStr = CloudVaultCrypto.JsonCodec.toJsonArrayString(arr);
            prefs.edit().putString(cacheKey, jsonStr).apply();
            memWatchlist.put(cacheKey, Collections.unmodifiableList(parseWatchlist(jsonStr)));
        } catch (RuntimeException e) {
            safeLogW("Supabase local watchlist removal failed: " + e.getMessage());
        }
        IO.execute(() -> {
            if (isConfigured() && userId != null && isUuid(userId)) {
                try {
                    String path = "/rest/v1/watchlist_items?profile_id=eq." + urlEncode(userId)
                            + "&media_key=eq." + urlEncode(mediaKey);
                    request("DELETE", path, null, "return=minimal");
                } catch (IOException e) {
                    safeLogW("Supabase removeWatchlistItem failed: " + e.getMessage());
                }
            }
            if (onDone != null) onDone.run();
        });
    }

    // MARK: - Shared Reels Sync (PERF-17)

    /**
     * Fetches shared Reels / TikToks / Shorts from {@code public.shared_reels}.
     *
     * @return List of {@link SharedReel} items, newest first.
     */
    public List<SharedReel> fetchSharedReelsSync() {
        SharedPreferences prefs = cache();
        if (isConfigured()) {
            try {
                String resp = request("GET", buildSharedReelsQueryPath(false, DEFAULT_REELS_LIMIT), null, null);
                prefs.edit().putString("shared_reels", resp).apply();
                return parseReels(resp);
            } catch (IOException e) {
                safeLogW("Supabase fetchSharedReels fallback: " + e.getMessage());
            }
        }
        return parseReels(prefs.getString("shared_reels", "[]"));
    }

    /**
     * Builds canonical {@code snake_case} JSON for inserting into {@code public.shared_reels}.
     *
     * @param senderName Sender display name.
     * @param videoUrl   Shared video URL.
     * @param title      Optional video title.
     * @return Canonical JSON object string with {@code sender_name}, {@code url}, {@code platform}, {@code title}, {@code watched}.
     */
    public static String buildSharedReelPayloadJson(String senderName, String videoUrl, String title) {
        return CloudVaultCrypto.JsonCodec.toJsonObjectString(
                buildSharedReelPayloadMap(senderName, videoUrl, title));
    }

    private static Map<String, Object> buildSharedReelPayloadMap(String senderName, String videoUrl, String title) {
        String cleanUrl = videoUrl == null ? "" : videoUrl.trim();
        String platform = detectReelPlatform(cleanUrl);
        String cleanSender = (senderName == null || senderName.trim().isEmpty()) ? "Companion" : senderName.trim();
        String cleanTitle = (title == null || title.trim().isEmpty())
                ? platform.toUpperCase(Locale.ROOT) + " Reel" : title.trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sender_name", cleanSender);
        payload.put("url", cleanUrl);
        payload.put("platform", platform);
        payload.put("title", cleanTitle);
        payload.put("watched", false);
        return payload;
    }

    /**
     * Shares a new Reel / TikTok / YouTube Short URL to {@code public.shared_reels} (called from the Companion Remote App or TV).
     */
    public void addSharedReelAsync(String senderName, String videoUrl, String title, Runnable onDone) {
        if (videoUrl == null || videoUrl.trim().isEmpty()) return;
        Map<String, Object> reelMap = buildSharedReelPayloadMap(senderName, videoUrl, title);
        String payloadJson = CloudVaultCrypto.JsonCodec.toJsonObjectString(reelMap);
        SharedPreferences prefs = cache();
        try {
            List<Map<String, Object>> next = new ArrayList<>();
            next.add(reelMap);
            next.addAll(CloudVaultCrypto.JsonCodec.parseJsonObjectArray(prefs.getString("shared_reels", "[]")));
            prefs.edit().putString("shared_reels", CloudVaultCrypto.JsonCodec.toJsonArrayString(next)).apply();
        } catch (RuntimeException e) {
            safeLogW("Supabase local shared_reels cache update failed: " + e.getMessage());
        }
        IO.execute(() -> {
            if (isConfigured()) {
                try {
                    request("POST", "/rest/v1/shared_reels", payloadJson, "return=minimal");
                } catch (IOException e) {
                    safeLogW("Supabase addSharedReel failed: " + e.getMessage());
                }
            }
            if (onDone != null) onDone.run();
        });
    }

    // MARK: - Zero-Knowledge Encrypted Cloud Key Backup Vault

    /**
     * Encrypts {@code secretsMap} locally using {@link CloudVaultCrypto} ({@code PBKDF2-HMAC-SHA256} 120,000 iterations
     * + {@code AES-256-GCM}) and upserts the authenticated ciphertext envelope into {@code public.encrypted_vault}.
     * Zero plaintext key names or secret values ever leave the device.
     *
     * @param masterPassphrase Non-empty household master passphrase.
     * @param secretsMap       Map of secret API key names to values.
     * @return The uploaded {@link CloudVaultCrypto.VaultEnvelope}.
     * @throws IOException If Supabase is not configured or the HTTPS upsert fails.
     */
    public CloudVaultCrypto.VaultEnvelope backupVaultSync(char[] masterPassphrase, Map<String, String> secretsMap)
            throws IOException {
        return backupVaultSlotSync(CloudVaultCrypto.DEFAULT_VAULT_ID, masterPassphrase, secretsMap);
    }

    /**
     * Encrypts {@code secretsMap} locally using {@link CloudVaultCrypto} and upserts the authenticated
     * ciphertext envelope into {@code public.encrypted_vault} under {@code vaultId} (e.g., {@code "household"}
     * or {@code "profile:<uuid>"}).
     */
    public CloudVaultCrypto.VaultEnvelope backupVaultSlotSync(
            String vaultId,
            char[] masterPassphrase,
            Map<String, String> secretsMap) throws IOException {
        if (!isConfigured()) {
            throw new IOException("Supabase is not configured");
        }
        String slot = (vaultId == null || vaultId.trim().isEmpty())
                ? CloudVaultCrypto.DEFAULT_VAULT_ID : vaultId.trim();
        CloudVaultCrypto.VaultEnvelope envelope = CloudVaultCrypto.encryptMap(slot, secretsMap, masterPassphrase);
        String body = envelope.toJsonString();
        request("POST", "/rest/v1/encrypted_vault?on_conflict=vault_id", body,
                "resolution=merge-duplicates,return=minimal");
        if (appContext != null) {
            cache().edit().putString(vaultCacheKey(slot), body).apply();
        }
        return envelope;
    }

    /**
     * Asynchronously encrypts and backs up {@code secretsMap} to {@code public.encrypted_vault}.
     *
     * @param masterPassphrase Non-empty household master passphrase (cloned for background execution and scrubbed after use).
     * @param secretsMap       Map of secret API key names to values.
     */
    public void backupVaultAsync(char[] masterPassphrase, Map<String, String> secretsMap) {
        backupVaultAsync(masterPassphrase, secretsMap, null);
    }

    /**
     * Asynchronously encrypts and backs up {@code secretsMap} to {@code public.encrypted_vault}, invoking
     * {@code onDone} once completed.
     *
     * @param masterPassphrase Non-empty household master passphrase (cloned for background execution and scrubbed after use).
     * @param secretsMap       Map of secret API key names to values.
     * @param onDone           Optional completion callback.
     */
    public void backupVaultAsync(char[] masterPassphrase, Map<String, String> secretsMap, Runnable onDone) {
        if (masterPassphrase == null || masterPassphrase.length == 0 || secretsMap == null) {
            if (onDone != null) onDone.run();
            return;
        }
        final char[] passCopy = masterPassphrase.clone();
        final Map<String, String> mapCopy = new LinkedHashMap<>(secretsMap);
        IO.execute(() -> {
            try {
                backupVaultSync(passCopy, mapCopy);
            } catch (IOException | RuntimeException e) {
                safeLogW("Supabase backupVaultAsync failed: " + e.getMessage());
            } finally {
                CloudVaultCrypto.wipePassphrase(passCopy);
                if (onDone != null) onDone.run();
            }
        });
    }

    /**
     * Downloads the primary encrypted envelope from {@code public.encrypted_vault} (falling back to local offline cache
     * if unreachable) and authenticates/decrypts it locally with {@code masterPassphrase}.
     *
     * @param masterPassphrase Household master passphrase.
     * @return Unmodifiable map of restored secret key names to values.
     * @throws IOException              If no vault backup exists or network fetch fails without local cache.
     * @throws GeneralSecurityException If {@code masterPassphrase} is wrong or the ciphertext/auth-tag was tampered with.
     */
    public Map<String, String> restoreVaultSync(char[] masterPassphrase)
            throws IOException, GeneralSecurityException {
        return restoreVaultSlotSync(CloudVaultCrypto.DEFAULT_VAULT_ID, masterPassphrase);
    }

    /**
     * Downloads and decrypts a specific {@code vaultId} slot from {@code public.encrypted_vault}.
     * Returns {@code null} cleanly when the slot does not yet exist on the server (HTTP 200 {@code []}).
     */
    public Map<String, String> restoreVaultSlotOrNullSync(String vaultId, char[] masterPassphrase)
            throws IOException, GeneralSecurityException {
        String slot = (vaultId == null || vaultId.trim().isEmpty())
                ? CloudVaultCrypto.DEFAULT_VAULT_ID : vaultId.trim();
        String rawEnvelope = null;
        IOException fetchError = null;
        if (isConfigured()) {
            try {
                rawEnvelope = request("GET", buildEncryptedVaultQueryPath(slot), null, null);
                if (rawEnvelope != null && !rawEnvelope.trim().isEmpty() && !"[]".equals(rawEnvelope.trim()) && appContext != null) {
                    cache().edit().putString(vaultCacheKey(slot), rawEnvelope).apply();
                }
            } catch (IOException e) {
                fetchError = e;
                safeLogW("Supabase restoreVaultSlot(" + slot + ") fetch failed: " + e.getMessage());
            }
        }
        if ((rawEnvelope == null || rawEnvelope.trim().isEmpty() || "[]".equals(rawEnvelope.trim())) && appContext != null && fetchError != null) {
            rawEnvelope = cache().getString(vaultCacheKey(slot), "");
        }
        CloudVaultCrypto.VaultEnvelope envelope = CloudVaultCrypto.VaultEnvelope.fromJsonString(rawEnvelope);
        if (envelope == null) {
            if (fetchError != null) throw fetchError;
            return null;
        }
        return CloudVaultCrypto.decryptMap(envelope, masterPassphrase);
    }

    /**
     * Downloads and decrypts a specific {@code vaultId} slot, throwing {@link IOException} if absent.
     */
    public Map<String, String> restoreVaultSlotSync(String vaultId, char[] masterPassphrase)
            throws IOException, GeneralSecurityException {
        Map<String, String> map = restoreVaultSlotOrNullSync(vaultId, masterPassphrase);
        if (map == null) {
            throw new IOException("No encrypted vault backup found in Supabase for slot: " + vaultId);
        }
        return map;
    }

    private static String vaultCacheKey(String slot) {
        return CloudVaultCrypto.DEFAULT_VAULT_ID.equals(slot) ? CACHE_ENCRYPTED_VAULT : ("encrypted_vault_" + slot);
    }

    /** Parses a PostgREST JSON response into a {@link SettingsDocument}. */
    public static SettingsDocument parseSettingsDocument(String json) {
        if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) return null;
        try {
            List<Map<String, Object>> rows = json.trim().startsWith("[")
                    ? CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json)
                    : Collections.singletonList(CloudVaultCrypto.JsonCodec.parseJsonObject(json));
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            Map<String, Object> settings = CloudVaultCrypto.JsonCodec.optObjectMap(row, "settings");
            Map<String, Object> rawTs = CloudVaultCrypto.JsonCodec.optObjectMap(row, "field_timestamps");
            Map<String, Long> ts = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawTs.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number) {
                    ts.put(e.getKey(), ((Number) e.getValue()).longValue());
                } else if (e.getKey() != null && e.getValue() != null) {
                    try {
                        ts.put(e.getKey(), Long.parseLong(String.valueOf(e.getValue()).trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return new SettingsDocument(settings, ts, CloudVaultCrypto.JsonCodec.optString(row, "updated_at", ""));
        } catch (RuntimeException e) {
            safeLogW("Supabase parseSettingsDocument failed: " + e.getMessage());
            return null;
        }
    }

    /** Parses a PostgREST JSON array of {@code public.tv_devices} rows. */
    public static List<TvDeviceRecord> parseTvDevices(String json) {
        List<TvDeviceRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) return out;
        try {
            List<Map<String, Object>> rows = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json);
            for (Map<String, Object> row : rows) {
                String deviceId = CloudVaultCrypto.JsonCodec.optString(
                        row, "device_id", CloudVaultCrypto.JsonCodec.optString(row, "device_slug", ""));
                if (deviceId.isEmpty()) continue;
                Map<String, Object> rawTs = CloudVaultCrypto.JsonCodec.optObjectMap(row, "field_timestamps");
                Map<String, Long> ts = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawTs.entrySet()) {
                    if (e.getKey() != null && e.getValue() instanceof Number) {
                        ts.put(e.getKey(), ((Number) e.getValue()).longValue());
                    }
                }
                out.add(new TvDeviceRecord(
                        deviceId,
                        CloudVaultCrypto.JsonCodec.optString(row, "device_name", deviceId),
                        CloudVaultCrypto.JsonCodec.optString(row, "room_name", "Living Room"),
                        CloudVaultCrypto.JsonCodec.optString(row, "platform", "android-tv"),
                        CloudVaultCrypto.JsonCodec.optString(row, "brand", "GENERIC"),
                        CloudVaultCrypto.JsonCodec.optString(row, "model", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "lan_ip", ""),
                        CloudVaultCrypto.JsonCodec.optInt(row, "remote_port", 8484),
                        CloudVaultCrypto.JsonCodec.optString(row, "mdns_host", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "realtime_channel", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "app_version", "2.0"),
                        CloudVaultCrypto.JsonCodec.optObjectMap(row, "tv_settings"),
                        ts,
                        CloudVaultCrypto.JsonCodec.optBoolean(row, "is_active", true),
                        CloudVaultCrypto.JsonCodec.optString(row, "replaced_by", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "last_seen_at", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "updated_at", "")
                ));
            }
        } catch (RuntimeException e) {
            safeLogW("Supabase parseTvDevices failed: " + e.getMessage());
        }
        return Collections.unmodifiableList(out);
    }

    /** Parses a PostgREST JSON array of {@code public.gaming_hosts} rows. */
    public static List<GamingHostRecord> parseGamingHosts(String json) {
        List<GamingHostRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty() || "[]".equals(json.trim())) return out;
        try {
            List<Map<String, Object>> rows = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json);
            for (Map<String, Object> row : rows) {
                String hostId = CloudVaultCrypto.JsonCodec.optString(row, "host_id", "");
                if (hostId.isEmpty()) continue;
                out.add(new GamingHostRecord(
                        hostId,
                        CloudVaultCrypto.JsonCodec.optString(row, "hostname", hostId),
                        CloudVaultCrypto.JsonCodec.optString(row, "lan_ip", ""),
                        CloudVaultCrypto.JsonCodec.optInt(row, "agent_port", 8485),
                        CloudVaultCrypto.JsonCodec.optStringList(row, "mac_addresses"),
                        CloudVaultCrypto.JsonCodec.optString(row, "sunshine_uuid", ""),
                        CloudVaultCrypto.JsonCodec.optString(row, "last_seen_at", "")
                ));
            }
        } catch (RuntimeException e) {
            safeLogW("Supabase parseGamingHosts failed: " + e.getMessage());
        }
        return Collections.unmodifiableList(out);
    }

    static String isoNowUtc() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date());
    }

    // MARK: - Canonical snake_case + Legacy camelCase DTO Parsers (INC-03)

    private static String detectReelPlatform(String url) {
        if (url == null) return "reel";
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("tiktok.com")) return "tiktok";
        if (lower.contains("instagram.com")) return "instagram";
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return "youtube_shorts";
        return "reel";
    }

    private static String normalizeKind(String mediaType) {
        return ("tv".equalsIgnoreCase(mediaType) || "series".equalsIgnoreCase(mediaType)) ? "tv" : "movie";
    }

    /**
     * Parses a JSON array of vote rows ({@code media_key} with fallback to legacy {@code mediaKey}).
     *
     * @param json UTF-8 JSON array string.
     * @return Map of {@code media_key -> +1/-1}.
     */
    public static Map<String, Integer> parseVotes(String json) {
        Map<String, Integer> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json);
            for (Map<String, Object> o : rows) {
                String key = CloudVaultCrypto.JsonCodec.optString(
                        o, "media_key", CloudVaultCrypto.JsonCodec.optString(o, "mediaKey", ""));
                int vote = CloudVaultCrypto.JsonCodec.optInt(o, "vote", 0);
                if (!key.isEmpty() && (vote == 1 || vote == -1)) {
                    out.put(key, vote);
                }
            }
        } catch (RuntimeException e) {
            safeLogW("Supabase parseVotes failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Parses a JSON array of watchlist entries, preferring canonical {@code snake_case} columns while falling back
     * cleanly to legacy {@code camelCase} cache keys.
     *
     * @param json UTF-8 JSON array string.
     * @return List of {@link WatchlistEntry} items.
     */
    public static List<WatchlistEntry> parseWatchlist(String json) {
        List<WatchlistEntry> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json);
            for (Map<String, Object> o : rows) {
                String id = CloudVaultCrypto.JsonCodec.optString(o, "id", "");
                String mediaKey = CloudVaultCrypto.JsonCodec.optString(
                        o, "media_key", CloudVaultCrypto.JsonCodec.optString(o, "mediaKey", ""));
                String kind = CloudVaultCrypto.JsonCodec.optString(
                        o, "kind",
                        CloudVaultCrypto.JsonCodec.optString(
                                o, "media_type",
                                CloudVaultCrypto.JsonCodec.optString(o, "mediaType", "movie")));
                String title = CloudVaultCrypto.JsonCodec.optString(o, "title", "");
                String posterUrl = CloudVaultCrypto.JsonCodec.optNullableString(
                        o, "poster_url", CloudVaultCrypto.JsonCodec.optNullableString(o, "posterUrl", null));
                String backdropUrl = CloudVaultCrypto.JsonCodec.optNullableString(
                        o, "backdrop_url", CloudVaultCrypto.JsonCodec.optNullableString(o, "backdropUrl", null));
                String sourceApp = CloudVaultCrypto.JsonCodec.optString(
                        o, "source_app", CloudVaultCrypto.JsonCodec.optString(o, "sourceApp", "stremio"));
                String deepLink = CloudVaultCrypto.JsonCodec.optNullableString(
                        o, "deep_link", CloudVaultCrypto.JsonCodec.optNullableString(o, "deepLink", null));

                out.add(new WatchlistEntry(id, mediaKey, kind, title, posterUrl, backdropUrl, sourceApp, deepLink));
            }
        } catch (RuntimeException e) {
            safeLogW("Supabase parseWatchlist failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Parses a JSON array of shared reels, preferring canonical {@code snake_case} columns ({@code url},
     * {@code sender_name}, {@code thumbnail_url}, {@code watched}) with read-side fallback to legacy keys
     * ({@code video_url}, {@code videoUrl}, {@code senderName}, {@code sender}, {@code is_watched}, {@code isWatched}).
     *
     * @param json UTF-8 JSON array string.
     * @return Unmodifiable list of {@link SharedReel} items.
     */
    public static List<SharedReel> parseReels(String json) {
        List<SharedReel> out = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json);
            for (Map<String, Object> o : rows) {
                String id = CloudVaultCrypto.JsonCodec.optString(o, "id", "");
                String senderName = CloudVaultCrypto.JsonCodec.optString(
                        o, "sender_name",
                        CloudVaultCrypto.JsonCodec.optString(
                                o, "senderName",
                                CloudVaultCrypto.JsonCodec.optString(o, "sender", "Companion")));
                String url = CloudVaultCrypto.JsonCodec.optString(
                        o, "url",
                        CloudVaultCrypto.JsonCodec.optString(
                                o, "video_url",
                                CloudVaultCrypto.JsonCodec.optString(o, "videoUrl", "")));
                String platform = CloudVaultCrypto.JsonCodec.optString(o, "platform", detectReelPlatform(url));
                String title = CloudVaultCrypto.JsonCodec.optString(o, "title", "Shared Reel");
                String thumbnailUrl = CloudVaultCrypto.JsonCodec.optString(
                        o, "thumbnail_url", CloudVaultCrypto.JsonCodec.optString(o, "thumbnailUrl", ""));
                boolean watched = o.containsKey("watched")
                        ? CloudVaultCrypto.JsonCodec.optBoolean(o, "watched", false)
                        : (o.containsKey("is_watched")
                        ? CloudVaultCrypto.JsonCodec.optBoolean(o, "is_watched", false)
                        : CloudVaultCrypto.JsonCodec.optBoolean(o, "isWatched", false));

                out.add(new SharedReel(id, senderName, url, platform, title, thumbnailUrl, watched));
            }
        } catch (RuntimeException e) {
            safeLogW("Supabase parseReels failed: " + e.getMessage());
        }
        return Collections.unmodifiableList(out);
    }

    private SharedPreferences cache() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String request(String method, String path, String jsonBody, String preferHeader) throws IOException {
        String curUrl = getBaseUrl();
        String curAnonKey = getAnonKey();
        String curHouseholdKey = resolveHouseholdKey();
        String curUserJwt = this.userAccessToken;

        HttpURLConnection conn = (HttpURLConnection) new URL(curUrl + path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(method);

        Map<String, String> headers = buildAuthHeaders(curAnonKey, curHouseholdKey, curUserJwt, preferHeader);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            conn.setRequestProperty(h.getKey(), h.getValue());
        }

        try {
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Supabase HTTP " + code);
            }
            if (code == 204) return "";
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 charset missing", e);
        }
    }

    private static boolean isUuid(String s) {
        return s != null && s.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String readOptionalConfigField(String fieldName) {
        try {
            Field f = Config.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof String ? ((String) v).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void safeLogW(String message) {
        try {
            AppLog.w(message);
        } catch (RuntimeException ignoredInHeadlessJvm) {
            // Stub android.jar throws RuntimeException("Stub!") in desktop JVM unit tests.
        }
    }
}
