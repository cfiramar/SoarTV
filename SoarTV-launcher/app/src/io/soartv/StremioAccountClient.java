package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Native TV client for Stremio Account & Cloud Library synchronization (api.strem.io).
 *
 * Why: Stremio calls its user Watch List "Library" and stores items in the Stremio
 * cloud datastore collection "libraryItem". Connecting directly to the user's Stremio
 * account (via authKey or email/password login) allows SoarTV to sync the user's full
 * Stremio Library (both Watch List items and episode watch progress) directly on the TV
 * with zero PC dependency.
 */
public final class StremioAccountClient {
    private static final String TAG = "SoarTV";
    private static final String API_BASE = "https://api.strem.io/api";
    private static final String LINK_API_BASE = "https://link.stremio.com/api/v2";
    private static final String PREF_NAME = "stremio_account";
    private static final int TIMEOUT_MS = 10_000;
    private static final long QR_POLL_INTERVAL_MS = 3_500L;
    private static final long QR_EXPIRY_MS = 300_000L;

    private static final Object QR_LOCK = new Object();
    private static volatile boolean qrSigningIn = false;
    private static volatile int qrSessionGeneration = 0;
    private static volatile String qrCode = "";
    private static volatile String qrLink = "";
    private static volatile String qrMessage = "";

    /** Result returned by Stremio's official TV link-code creation endpoint ({@code /api/v2/create}). */
    public static final class LinkCodeResult {
        public final String code;
        public final String link;

        public LinkCodeResult(String code, String link) {
            this.code = code != null ? code.trim() : "";
            this.link = link != null ? link.trim() : "";
        }

        public boolean isValid() {
            return !code.isEmpty() && !link.isEmpty();
        }
    }

    /**
     * Value object representing an item in the user's Stremio Library ("libraryItem").
     */
    public static final class LibraryItem {
        public final String imdbId;
        public final String name;
        public final String kind; // "tv" or "movie"
        public final String poster;
        public final long ctimeMs;
        public final long mtimeMs;
        public final long lastWatchedMs;
        public final int timesWatched;
        public final long timeOffsetMs;
        public final long durationMs;
        public final String videoId;
        public final int season;
        public final int episode;
        public final boolean watched;
        public final boolean removed;
        public final boolean temp;

        public LibraryItem(
                String imdbId,
                String name,
                String kind,
                String poster,
                long ctimeMs,
                long mtimeMs,
                long lastWatchedMs,
                int timesWatched,
                long timeOffsetMs,
                long durationMs,
                String videoId,
                int season,
                int episode,
                boolean watched,
                boolean removed,
                boolean temp) {
            this.imdbId = imdbId != null ? imdbId : "";
            this.name = name != null ? name : "";
            this.kind = "movie".equals(kind) ? "movie" : "tv";
            String cleanPoster = WatchNext.sanitizePosterUrl(poster, this.imdbId, true);
            this.poster = cleanPoster != null && !cleanPoster.isEmpty() ? cleanPoster
                    : (this.imdbId.startsWith("tt") ? "https://images.metahub.space/poster/medium/" + this.imdbId + "/img" : "");
            this.ctimeMs = ctimeMs;
            this.mtimeMs = mtimeMs;
            this.lastWatchedMs = lastWatchedMs;
            this.timesWatched = timesWatched;
            this.timeOffsetMs = timeOffsetMs;
            this.durationMs = durationMs;
            this.videoId = videoId != null ? videoId : "";
            this.season = season;
            this.episode = episode;
            this.watched = watched;
            this.removed = removed;
            this.temp = temp;
        }

        /**
         * Returns true if this item was explicitly added to the user's Stremio Library (Watch List).
         * In Stremio's datastore, items explicitly saved to Library have removed=false and temp=false.
         */
        public boolean isLibraryWatchlistItem() {
            return !removed && !temp && imdbId.startsWith("tt");
        }

        /**
         * Returns true ONLY if this item has real active playback progress for Continue Watching.
         * Excludes unwatched Stremio Library additions (where Stremio sets default video_id S1:E1 with 0 progress)
         * and excludes already-finished movies.
         */
        public boolean hasContinueWatchingState() {
            if (!imdbId.startsWith("tt") || removed) return false;
            if ("movie".equals(kind)) {
                if (watched) return false;
                if (durationMs > 0L && timeOffsetMs >= (long) (durationMs * 0.90f)) return false;
                return timeOffsetMs > 60_000L;
            }
            // For TV series: require real mid-episode playback (>60s) OR actual watched episode history (timesWatched > 0 or progressed past S1E1)
            return timeOffsetMs > 60_000L
                    || (lastWatchedMs > 0L && (timesWatched > 0 || season > 1 || episode > 1));
        }
    }

    private StremioAccountClient() {}

    private static SharedPreferences prefs(Context c) {
        if (c == null) return null;
        try {
            Context app = c.getApplicationContext();
            return (app != null ? app : c).getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Returns true if a Stremio account authKey is configured in hardware-backed {@link Secrets}
     * (or migrated from legacy preferences). Plaintext passwords are never stored in APK config.
     */
    public static boolean isConfigured(Context c) {
        String key = getAuthKey(c);
        return key != null && !key.isEmpty();
    }

    private static String activeProfileId(Context c) {
        if (c == null) return "";
        try {
            UserProfileManager.Profile p = UserProfileManager.get(c).getActiveProfile();
            return p != null ? p.id : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isDefaultProfileActive(Context c) {
        if (c == null) return true;
        try {
            UserProfileManager.Profile p = UserProfileManager.get(c).getActiveProfile();
            return p == null || p.isDefault;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /**
     * Returns the active profile's Stremio authKey from hardware-backed {@link Secrets}.
     * Falls back to {@link Secrets#HOUSEHOLD_STREMIO_AUTH_KEY} ONLY when the active profile
     * explicitly enabled {@link Prefs#useHouseholdStremio()} (opt-in sharing).
     */
    public static String getAuthKey(Context c) {
        String pid = activeProfileId(c);
        if (!pid.isEmpty()) {
            String profKey = Secrets.getProfileSecret(c, pid, "stremio_auth_key");
            if (!profKey.isEmpty()) {
                return profKey.trim();
            }
            if (Prefs.useHouseholdStremio()) {
                String hhKey = Secrets.getSecret(c, Secrets.HOUSEHOLD_STREMIO_AUTH_KEY);
                if (!hhKey.isEmpty()) return hhKey.trim();
            }
            if (!isDefaultProfileActive(c)) {
                return "";
            }
        }
        String secretKey = Secrets.getSecret(c, Secrets.STREMIO_AUTH_KEY);
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            if (!pid.isEmpty() && isDefaultProfileActive(c)) {
                Secrets.putProfileSecret(c, pid, "stremio_auth_key", secretKey.trim());
            }
            SharedPreferences sp = prefs(c);
            if (sp != null && (sp.contains("auth_key") || sp.contains("password"))) {
                sp.edit().remove("auth_key").remove("password").apply();
            }
            return secretKey.trim();
        }
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            String legacy = sp.getString("auth_key", "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                String clean = legacy.trim();
                if (Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, clean)) {
                    if (!pid.isEmpty() && isDefaultProfileActive(c)) {
                        Secrets.putProfileSecret(c, pid, "stremio_auth_key", clean);
                    }
                    sp.edit().remove("auth_key").remove("password").apply();
                }
                return clean;
            }
        }
        if (Config.STREMIO_AUTH_KEY != null && !Config.STREMIO_AUTH_KEY.trim().isEmpty()) {
            String cfgKey = Config.STREMIO_AUTH_KEY.trim();
            Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, cfgKey);
            return cfgKey;
        }
        return "";
    }

    private static volatile List<LibraryItem> memCachedLibrary = null;

    /**
     * Saves a Stremio authKey encrypted per active profile in {@link Secrets} (mirroring into
     * {@link Secrets#STREMIO_AUTH_KEY} and {@link Secrets#HOUSEHOLD_STREMIO_AUTH_KEY} when saving on the default profile).
     */
    public static void saveAuthKey(Context c, String authKey, String email) {
        String cleanKey = authKey != null ? authKey.trim() : "";
        String cleanEmail = email != null ? email.trim() : "";
        String pid = activeProfileId(c);
        if (!pid.isEmpty()) {
            Secrets.putProfileSecret(c, pid, "stremio_auth_key", cleanKey);
            Secrets.putProfileSecret(c, pid, "stremio_email", cleanKey.isEmpty() ? "" : cleanEmail);
            try {
                CloudSyncManager.get(c).recordLocalSecretChange(CloudSyncManager.Scope.PROFILE, "stremio_auth_key");
            } catch (RuntimeException ignored) {
            }
        }
        if (pid.isEmpty() || isDefaultProfileActive(c)) {
            if (cleanKey.isEmpty()) {
                Secrets.deleteSecret(c, Secrets.STREMIO_AUTH_KEY);
            } else {
                Secrets.putSecret(c, Secrets.STREMIO_AUTH_KEY, cleanKey);
                Secrets.putSecret(c, Secrets.HOUSEHOLD_STREMIO_AUTH_KEY, cleanKey);
                if (c != null) {
                    try {
                        CloudSyncManager.get(c).recordLocalSecretChange(
                                CloudSyncManager.Scope.HOUSEHOLD, Secrets.HOUSEHOLD_STREMIO_AUTH_KEY);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            SharedPreferences.Editor ed = sp.edit().remove("auth_key").remove("password");
            if (!cleanEmail.isEmpty() && !cleanKey.isEmpty()) {
                ed.putString("email", cleanEmail);
            } else if (cleanKey.isEmpty()) {
                ed.putString("email", "");
                ed.putString("cached_library_json", "");
                memCachedLibrary = Collections.emptyList();
            }
            ed.apply();
        } else if (cleanKey.isEmpty()) {
            memCachedLibrary = Collections.emptyList();
        }
        Prefs.version++;
    }

    /**
     * Returns the saved Stremio account email for the active profile for display in Settings.
     */
    public static String getAccountEmail(Context c) {
        String pid = activeProfileId(c);
        if (!pid.isEmpty()) {
            String profEmail = Secrets.getProfileSecret(c, pid, "stremio_email");
            if (!profEmail.isEmpty()) return profEmail;
            if (!isDefaultProfileActive(c)) {
                return Prefs.useHouseholdStremio() && !Secrets.getSecret(c, Secrets.HOUSEHOLD_STREMIO_AUTH_KEY).isEmpty()
                        ? "Household Shared" : "";
            }
        }
        SharedPreferences sp = prefs(c);
        if (sp != null) {
            String saved = sp.getString("email", "");
            if (saved != null && !saved.isEmpty()) return saved;
        }
        return Config.STREMIO_EMAIL != null ? Config.STREMIO_EMAIL : "";
    }

    /**
     * Logs in to Stremio API using email and password, storing ONLY the resulting authKey in {@link Secrets}
     * (never persisting the password).
     */
    public static String loginWithEmail(Context c, String email, String password) {
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            return "";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("type", "Login");
            body.put("email", email.trim());
            body.put("password", password);
            body.put("facebook", false);
            String resp = postJson(API_BASE + "/login", body.toString());
            if (resp == null || resp.isEmpty()) return "";
            JSONObject root = new JSONObject(resp);
            JSONObject result = root.optJSONObject("result");
            if (result != null) {
                String authKey = result.optString("authKey", "").trim();
                JSONObject user = result.optJSONObject("user");
                String resolvedEmail = user != null ? user.optString("email", email.trim()) : email.trim();
                if (!authKey.isEmpty()) {
                    saveAuthKey(c, authKey, resolvedEmail);
                    return authKey;
                }
            }
        } catch (Exception e) {
            try {
                Log.w(TAG, "Stremio login failed: " + e.getMessage());
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    /** Current 4-character Stremio TV link code (e.g. {@code "FWG4"}), or {@code ""} when idle. */
    public static String qrCode() {
        return qrCode;
    }

    /** Current Stremio TV link URL (e.g. {@code "https://link.stremio.com/FWG4"}), or {@code ""} when idle. */
    public static String qrLink() {
        return qrLink;
    }

    /** Last error/status message from Stremio QR sign-in, or {@code ""} when none. */
    public static String qrMessage() {
        return qrMessage;
    }

    /** Cancels any active Stremio QR polling loop immediately. */
    public static void cancelQrSignIn() {
        synchronized (QR_LOCK) {
            qrSessionGeneration++;
            qrSigningIn = false;
        }
    }

    /**
     * Pure parser for {@code GET https://link.stremio.com/api/v2/create?type=Create}.
     * Uses pure-Java parsing (no Android {@code org.json} dependency) so it runs identically on
     * Android TV and headless Desktop JVM tests. Returns {@code null} if malformed or unsuccessful.
     */
    public static LinkCodeResult parseLinkCodeResponse(String json) {
        String resultBlock = extractResultObjectBlock(json);
        if (resultBlock.isEmpty()) return null;
        String code = extractJsonStringValue(resultBlock, "code");
        if (code.isEmpty()) return null;
        String link = extractJsonStringValue(resultBlock, "link");
        if (link.isEmpty()) {
            link = "https://link.stremio.com/" + code;
        }
        LinkCodeResult out = new LinkCodeResult(code, link);
        return out.isValid() ? out : null;
    }

    /**
     * Pure parser for {@code GET https://link.stremio.com/api/v2/read?type=Read&code=...}.
     * Returns the approved {@code authKey}, or {@code ""} while still pending ({@code error.code == 101}).
     */
    public static String parseLinkReadAuthKey(String json) {
        String resultBlock = extractResultObjectBlock(json);
        if (resultBlock.isEmpty()) return "";
        return extractJsonStringValue(resultBlock, "authKey");
    }

    private static String extractResultObjectBlock(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        int keyIdx = json.indexOf("\"result\"");
        if (keyIdx < 0) return "";
        int colon = json.indexOf(':', keyIdx + 8);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '{') return "";
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int j = i; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
            } else {
                if (ch == '"') {
                    inStr = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return json.substring(i, j + 1);
                    }
                }
            }
        }
        return "";
    }

    private static String extractJsonStringValue(String obj, String fieldName) {
        if (obj == null || fieldName == null || fieldName.isEmpty()) return "";
        String needle = "\"" + fieldName + "\"";
        int idx = obj.indexOf(needle);
        while (idx >= 0) {
            int pos = idx + needle.length();
            while (pos < obj.length() && Character.isWhitespace(obj.charAt(pos))) pos++;
            if (pos < obj.length() && obj.charAt(pos) == ':') {
                pos++;
                while (pos < obj.length() && Character.isWhitespace(obj.charAt(pos))) pos++;
                if (pos < obj.length() && obj.charAt(pos) == '"') {
                    StringBuilder sb = new StringBuilder();
                    pos++;
                    boolean esc = false;
                    while (pos < obj.length()) {
                        char c = obj.charAt(pos++);
                        if (esc) {
                            esc = false;
                            if (c == 'n') sb.append('\n');
                            else if (c == 'r') sb.append('\r');
                            else if (c == 't') sb.append('\t');
                            else sb.append(c);
                        } else if (c == '\\') {
                            esc = true;
                        } else if (c == '"') {
                            return sb.toString().trim();
                        } else {
                            sb.append(c);
                        }
                    }
                    return "";
                }
            }
            idx = obj.indexOf(needle, idx + needle.length());
        }
        return "";
    }

    /** Resolves the Stremio account email for a valid {@code authKey} via {@code /api/getUser} (best effort). */
    public static String fetchUserEmailByAuthKey(String authKey) {
        if (authKey == null || authKey.trim().isEmpty()) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("type", "GetUser");
            body.put("authKey", authKey.trim());
            String resp = postJson(API_BASE + "/getUser", body.toString());
            if (resp == null || resp.isEmpty()) return "";
            JSONObject root = new JSONObject(resp);
            JSONObject res = root.optJSONObject("result");
            if (res != null) {
                String email = res.optString("email", "").trim();
                if (!email.isEmpty()) return email;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Starts Stremio's official QR / browser account linking flow ({@code link.stremio.com}) in a background
     * daemon thread and invokes {@code changed} on code generation, completion, or expiry/error.
     */
    public static void signInWithQr(Context c, Runnable changed) {
        final int gen;
        synchronized (QR_LOCK) {
            qrSessionGeneration++;
            gen = qrSessionGeneration;
            qrSigningIn = true;
            qrCode = "";
            qrLink = "";
            qrMessage = "";
        }
        final Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                String createJson = getJson(LINK_API_BASE + "/create?type=Create");
                LinkCodeResult created = parseLinkCodeResponse(createJson);
                if (created == null || !created.isValid()) {
                    qrMessage = "Could not reach link.stremio.com — try Email & Password.";
                    return;
                }
                if (qrSessionGeneration != gen) return;
                qrCode = created.code;
                qrLink = created.link;
                if (changed != null) changed.run();

                long deadline = System.currentTimeMillis() + QR_EXPIRY_MS;
                String encodedCode = java.net.URLEncoder.encode(created.code, "UTF-8");
                while (qrSessionGeneration == gen && System.currentTimeMillis() < deadline) {
                    Thread.sleep(QR_POLL_INTERVAL_MS);
                    if (qrSessionGeneration != gen) return;
                    String readJson = getJson(LINK_API_BASE + "/read?type=Read&code=" + encodedCode);
                    String authKey = parseLinkReadAuthKey(readJson);
                    if (!authKey.isEmpty()) {
                        String email = fetchUserEmailByAuthKey(authKey);
                        saveAuthKey(app, authKey, email);
                        return;
                    }
                }
                if (qrSessionGeneration == gen) {
                    qrMessage = "Stremio link code expired. Open again for a fresh code.";
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (qrSessionGeneration == gen) {
                    qrMessage = "Stremio QR login failed: " + e.getMessage();
                }
            } finally {
                synchronized (QR_LOCK) {
                    if (qrSessionGeneration == gen) {
                        qrSigningIn = false;
                    }
                }
                if (qrSessionGeneration == gen && changed != null) {
                    changed.run();
                }
            }
        }, "stremio-qr-auth");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Fetches the user's Stremio Library items from api.strem.io/api/datastoreGet,
     * falling back to cached library JSON if offline.
     */
    public static List<LibraryItem> fetchLibraryItems(Context c) {
        String authKey = getAuthKey(c);
        if (authKey.isEmpty()) {
            return loadCachedLibraryItems(c);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("authKey", authKey);
            body.put("collection", "libraryItem");
            body.put("all", true);
            String resp = postJson(API_BASE + "/datastoreGet", body.toString());
            if (resp != null && !resp.isEmpty()) {
                List<LibraryItem> parsed = parseLibraryDatastoreResponse(resp);
                if (!parsed.isEmpty()) {
                    memCachedLibrary = Collections.unmodifiableList(parsed);
                    SharedPreferences sp = prefs(c);
                    if (sp != null) {
                        sp.edit()
                                .putString("cached_library_json", resp)
                                .putLong("cached_at", System.currentTimeMillis())
                                .apply();
                    }
                }
                if (!parsed.isEmpty()) return parsed;
            }
        } catch (Exception e) {
            try {
                Log.w(TAG, "Stremio datastoreGet failed: " + e.getMessage());
            } catch (RuntimeException ignored) {
            }
        }
        return loadCachedLibraryItems(c);
    }

    /**
     * Loads the most recently cached Stremio Library items from memory / SharedPreferences (0ms, zero network).
     */
    public static List<LibraryItem> loadCachedLibraryItems(Context c) {
        List<LibraryItem> mem = memCachedLibrary;
        if (mem != null) return mem;
        SharedPreferences sp = prefs(c);
        if (sp == null) return Collections.emptyList();
        String cached = sp.getString("cached_library_json", "");
        if (cached == null || cached.isEmpty()) {
            memCachedLibrary = Collections.emptyList();
            return memCachedLibrary;
        }
        List<LibraryItem> parsed = Collections.unmodifiableList(parseLibraryDatastoreResponse(cached));
        memCachedLibrary = parsed;
        return parsed;
    }

    /**
     * Pure parser for Stremio's datastoreGet response JSON.
     * Sorts items by most recently modified/watched descending.
     */
    public static List<LibraryItem> parseLibraryDatastoreResponse(String json) {
        List<LibraryItem> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr;
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                arr = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                arr = root.optJSONArray("result");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("_id", "").trim();
                if (!id.startsWith("tt")) continue;
                boolean removed = item.optBoolean("removed", false);
                boolean temp = item.optBoolean("temp", false);
                if (removed) continue;
                String type = item.optString("type", "series");
                if (!"movie".equals(type) && !"series".equals(type) && !"tv".equals(type)) continue;
                String kind = "movie".equals(type) ? "movie" : "tv";
                String name = item.optString("name", "").trim();
                String rawPoster = item.optString("poster", "").trim();
                String poster = WatchNext.sanitizePosterUrl(rawPoster, id, true);
                if (poster == null || poster.isEmpty()) {
                    poster = "https://images.metahub.space/poster/medium/" + id + "/img";
                }
                long ctimeMs = parseIsoTimestamp(item.optString("_ctime", ""));
                long mtimeMs = parseIsoTimestamp(item.optString("_mtime", ""));

                JSONObject state = item.optJSONObject("state");
                long lastWatchedMs = 0L;
                int timesWatched = 0;
                long timeOffsetMs = 0L;
                long durationMs = 0L;
                String videoId = "";
                if (state != null) {
                    lastWatchedMs = parseIsoTimestamp(state.optString("lastWatched", ""));
                    timesWatched = state.optInt("timesWatched", 0);
                    timeOffsetMs = state.optLong("timeOffset", 0L);
                    durationMs = state.optLong("duration", 0L);
                    videoId = state.optString("video_id", "").trim();
                }
                int[] se = parseVideoIdSeasonEpisode(videoId);
                boolean watched = "movie".equals(kind)
                        ? (timesWatched > 0 && (timeOffsetMs == 0L || (durationMs > 0L && timeOffsetMs >= durationMs * 88L / 100L)))
                        : false;

                out.add(new LibraryItem(
                        id, name, kind, poster,
                        ctimeMs, mtimeMs, lastWatchedMs,
                        timesWatched, timeOffsetMs, durationMs,
                        videoId, se[0], se[1], watched, removed, temp
                ));
            }
            out.sort((a, b) -> {
                long ta = Math.max(a.lastWatchedMs, Math.max(a.mtimeMs, a.ctimeMs));
                long tb = Math.max(b.lastWatchedMs, Math.max(b.mtimeMs, b.ctimeMs));
                return Long.compare(tb, ta);
            });
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Parses Stremio video_id strings like "tt0903747:2:5" into [season, episode].
     */
    public static int[] parseVideoIdSeasonEpisode(String videoId) {
        if (videoId == null || videoId.isEmpty()) return new int[]{0, 0};
        String[] parts = videoId.split(":");
        if (parts.length >= 3) {
            try {
                int s = Integer.parseInt(parts[parts.length - 2].trim());
                int e = Integer.parseInt(parts[parts.length - 1].trim());
                if (s > 0 && e > 0) return new int[]{s, e};
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{0, 0};
    }

    private static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        try {
            String clean = iso.trim();
            if (clean.endsWith("Z")) clean = clean.substring(0, clean.length() - 1);
            int dot = clean.indexOf('.');
            if (dot > 0) clean = clean.substring(0, dot);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(clean).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String getJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SoarTV/1.5");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String postJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
