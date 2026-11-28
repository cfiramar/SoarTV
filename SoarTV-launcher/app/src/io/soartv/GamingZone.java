package io.soartv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates the Smart "Gaming Zone" orchestrator (Wake-on-LAN + Modern Standby S0 Wake +
 * Windows Hello Alphanumeric PIN Unlock + Sunshine/Moonlight/Steam Link + Auto HDMI Switching).
 *
 * <p><b>Why:</b> Lets the user wake their sleeping Windows gaming laptop from the couch, unlock
 * the Windows lock screen with their alphanumeric PIN if needed, browse all installed Steam and
 * standalone PC game titles (with manual hide/unhide support), and automatically route
 * either to the newly active HDMI port ("TV Screen Only") or to Moonlight / Steam Link over Wi-Fi.
 *
 * <p><b>What:</b> Provides Wake-on-LAN packet generation, raw-socket HTTP RPC to the Laptop Gaming
 * Agent on port {@code 8485} (bypassing Android cleartext restrictions on dynamic LAN IPs), game
 * catalog filtering, and an interactive TV picker & PIN modal.
 */
public final class GamingZone {

    public static final int LAPTOP_AGENT_PORT = 8485;
    public static final String HIDDEN_TILE_ID = "__hidden_games__";
    private static final String PREFS_NAME = "gaming_zone_prefs";
    private static final String KEY_HIDDEN_IDS = "hidden_game_ids";
    private static final String KEY_PERM_HIDDEN_IDS = "perm_hidden_game_ids";
    private static final String KEY_LAPTOP_IP = "laptop_ip_override";
    private static final String KEY_CACHED_GAMES = "cached_games_json";
    private static final String KEY_LAST_PLAYED = "last_played_json";
    private static final String KEY_CACHED_AWAKE = "cached_awake";
    private static final String KEY_CACHED_LOCKED = "cached_locked";
    private static final String KEY_CACHED_HDMI = "cached_hdmi";
    private static final String KEY_CACHED_SUNSHINE = "cached_sunshine";
    private static final String KEY_CACHED_BATTERY_PERCENT = "cached_battery_percent";
    private static final String KEY_CACHED_BATTERY_CHARGING = "cached_battery_charging";
    private static final String KEY_CACHED_BATTERY_FULL = "cached_battery_full";
    private static final String KEY_CACHED_SUNSHINE_UUID = "cached_sunshine_uuid";
    private static final String KEY_CACHED_SUNSHINE_HOST = "cached_sunshine_host";
    private static final String KEY_CACHED_PC_DISP_W = "cached_pc_disp_w";
    private static final String KEY_CACHED_PC_DISP_H = "cached_pc_disp_h";
    private static final String KEY_CACHED_PC_DISP_HZ = "cached_pc_disp_hz";
    private static final String KEY_CACHED_STEAM_RUNNING = "cached_steam_running";
    private static final String KEY_CACHED_EPIC_RUNNING = "cached_epic_running";
    private static final String KEY_CACHED_HEROIC_RUNNING = "cached_heroic_running";
    private static final String KEY_CACHED_SUNSHINE_POWER_CTRL = "cached_sunshine_power_ctrl";
    private static final String KEY_CACHED_PC_MACS = "cached_pc_macs";
    private static final String KEY_HDMI_PORT_ROLES = "hdmi_port_roles";
    private static final String KEY_UNPAIRED_PC_IP = "last_unpaired_pc_ip";
    private static volatile String LAST_REACHABLE_UNPAIRED_PC_IP = "";

    private static final String AGENT_SERVICE_ID = "soartv-laptop-gaming-agent";
    private static final String UDP_DISCOVER_OP = "SOARTV_GAMING_DISCOVER_V1";
    private static final String UDP_HERE_OP = "SOARTV_GAMING_HERE_V1";
    private static final int UDP_DISCOVERY_WAIT_MS = 450;
    private static final int SUBNET_SCAN_THREADS = 32;
    private static final int SUBNET_SCAN_CONNECT_TIMEOUT_MS = 320;
    private static final int SUBNET_VERIFY_TIMEOUT_MS = 650;
    private static final int FAST_SAME_SUBNET_PROBE_MS = 650;
    private static final int FAST_CROSS_SUBNET_PROBE_MS = 480;
    private static final Object DISCOVERY_LOCK = new Object();

    public static final String MOONLIGHT_PACKAGE = "com.limelight";
    public static final String MOONLIGHT_SHORTCUT_TRAMPOLINE_CLASS = "com.limelight.ShortcutTrampoline";
    public static final String MOONLIGHT_EXTRA_UUID = "UUID";
    public static final String MOONLIGHT_EXTRA_NAME = "Name";
    public static final String MOONLIGHT_EXTRA_APP_NAME = "AppName";
    private static final long MIN_LAUNCH_STAGE_BEAT_MS = 450L;
    private static final long LAUNCH_HANDOFF_HOLD_MS = 380L;
    private static final java.util.concurrent.atomic.AtomicLong ACTIVE_LAUNCH_TOKEN =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /**
     * Modular classification of what device is plugged into a given TV HDMI input port.
     *
     * <p><b>Why:</b> Prevents media streamers (e.g., Mecool, Apple TV) or permanent consoles (e.g.,
     * PlayStation, Xbox, Switch, Wii) from being mistaken for the gaming laptop's HDMI cable, while
     * surfacing permanent consoles directly on the Gaming Screen for all users.
     */
    public enum HdmiRole {
        AUTO,
        CONSOLE,
        LAPTOP,
        STREAMER,
        FREE
    }

    /**
     * Immutable configuration entry for a numbered TV HDMI port ({@code 1..4}).
     */
    public static final class HdmiPortConfig {
        public final int portNumber;
        public final HdmiRole role;
        public final String deviceName;

        public HdmiPortConfig(int portNumber, HdmiRole role, String deviceName) {
            this.portNumber = portNumber;
            this.role = role != null ? role : HdmiRole.AUTO;
            this.deviceName = deviceName != null ? deviceName.trim() : "";
        }
    }

    public static final int WEBP_CACHE_QUALITY = 86;
    public static final float MAX_ASPECT_STRETCH_TOLERANCE = 0.03f;
    static final int MAX_GAME_ART_BITMAPS = 24;
    public static final String GAME_HERO_DISK_DIR_NAME = "game_hero_hd";
    static final int MAX_GAME_HERO_BACKDROPS = 24;
    static final int MIN_GAME_HERO_WIDTH = 1200;
    static final int MIN_GAME_HERO_HEIGHT = 580;

    /** Pure value object returned by {@link #computeAspectFitOrCropRect(int, int, int, int, float)}. */
    public static final class AspectCropRect {
        public final int left;
        public final int top;
        public final int width;
        public final int height;
        public final boolean cropped;

        public AspectCropRect(int left, int top, int width, int height, boolean cropped) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.cropped = cropped;
        }
    }

    /**
     * Computes the source crop rectangle to map {@code srcW x srcH} into {@code dstW x dstH} while allowing up to
     * {@code maxStretchTolerance} (e.g. {@code 0.03f} = 3%) aspect-ratio stretch before center-cropping any excess.
     */
    public static AspectCropRect computeAspectFitOrCropRect(
            int srcW, int srcH, int dstW, int dstH, float maxStretchTolerance) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return new AspectCropRect(0, 0, Math.max(1, srcW), Math.max(1, srcH), false);
        }
        float tol = Math.max(0f, maxStretchTolerance);
        float srcRatio = (float) srcW / (float) srcH;
        float dstRatio = (float) dstW / (float) dstH;
        float relDiff = Math.abs(srcRatio - dstRatio) / dstRatio;
        if (relDiff <= tol + 1e-6f) {
            return new AspectCropRect(0, 0, srcW, srcH, false);
        }
        if (srcRatio > dstRatio) {
            float effectiveTargetRatio = dstRatio * (1f + tol);
            int cropW = Math.min(srcW, Math.max(1, Math.round(srcH * effectiveTargetRatio)));
            int left = Math.max(0, srcW - cropW);
            int safeW = Math.max(1, Math.min(cropW, srcW - left));
            return new AspectCropRect(left, 0, safeW, srcH, safeW < srcW);
        } else {
            float effectiveTargetRatio = dstRatio / (1f + tol);
            int cropH = Math.min(srcH, Math.max(1, Math.round(srcW / effectiveTargetRatio)));
            int top = 0;
            int safeH = Math.max(1, Math.min(cropH, srcH - top));
            return new AspectCropRect(0, top, srcW, safeH, safeH < srcH);
        }
    }

    @SuppressWarnings("deprecation")
    public static android.graphics.Bitmap.CompressFormat webpLossyFormatForSdk(int sdkInt) {
        if (sdkInt >= 30) {
            return android.graphics.Bitmap.CompressFormat.WEBP_LOSSY;
        }
        return android.graphics.Bitmap.CompressFormat.WEBP;
    }

    public static android.graphics.Bitmap.CompressFormat webpLossyFormat() {
        return webpLossyFormatForSdk(android.os.Build.VERSION.SDK_INT);
    }

    /**
     * Resolves a disk artwork cache file by checking {@code .webp} first and falling back seamlessly
     * to legacy {@code .jpg} when present; returns the {@code .webp} target path if neither exists yet.
     */
    public static java.io.File resolveCachedArtworkFile(java.io.File dir, String baseNameWithoutExt) {
        java.io.File webp = new java.io.File(dir, baseNameWithoutExt + ".webp");
        if (webp.exists() && webp.length() > 0) return webp;
        java.io.File jpg = new java.io.File(dir, baseNameWithoutExt + ".jpg");
        if (jpg.exists() && jpg.length() > 0) return jpg;
        return webp;
    }

    private static java.util.concurrent.ThreadPoolExecutor createBoundedDaemonPool(
            int coreThreads, int maxThreads, int queueCap, final String namePrefix) {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                30L,
                java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(queueCap),
                r -> {
                    Thread t = new Thread(r, namePrefix);
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final java.util.Map<String, android.graphics.BitmapShader> ART_SHADERS = new java.util.HashMap<>();
    private static final java.util.Map<String, android.graphics.Bitmap> ART_MEM =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> eldest) {
                    if (size() <= MAX_GAME_ART_BITMAPS) return false;
                    ART_SHADERS.remove(eldest.getKey());
                    Backdrop.scheduleSafeBitmapRecycle(eldest.getValue());
                    return true;
                }
            };
    private static final java.util.Set<String> ART_LOADING = new java.util.HashSet<>();

    private static final java.util.Set<String> PINNED_GAME_HERO_KEYS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static final java.util.Map<String, Backdrop> GAME_HERO_MEM =
            new java.util.LinkedHashMap<String, Backdrop>(32, 0.75f, true) {
                @Override
                public Backdrop put(String key, Backdrop value) {
                    Backdrop prev = super.put(key, value);
                    while (size() > MAX_GAME_HERO_BACKDROPS) {
                        String victimKey = null;
                        for (java.util.Map.Entry<String, Backdrop> e : entrySet()) {
                            String k = e.getKey();
                            if (k.equals(key)) continue;
                            if (!PINNED_GAME_HERO_KEYS.contains(k) && !Backdrop.isDisplayedBackdrop(e.getValue())) {
                                victimKey = k;
                                break;
                            }
                        }
                        if (victimKey == null) {
                            for (java.util.Map.Entry<String, Backdrop> e : entrySet()) {
                                if (!e.getKey().equals(key)) {
                                    victimKey = e.getKey();
                                    break;
                                }
                            }
                        }
                        if (victimKey == null) break;
                        Backdrop evicted = remove(victimKey);
                        if (evicted != null) Backdrop.scheduleSafeRecycle(evicted);
                    }
                    return prev;
                }

                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Backdrop> eldest) {
                    return false;
                }
            };
    private static final java.util.Set<String> GAME_HERO_LOADING = new java.util.HashSet<>();
    private static final java.util.Map<String, java.util.List<Runnable>> GAME_HERO_CALLBACKS = new java.util.HashMap<>();
    private static final java.util.Set<String> NO_GAME_HERO_IDS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.Map<String, String> DYNAMIC_STEAM_APP_ID_CACHE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static volatile boolean legacyHeroCacheCleaned = false;
    private static final java.util.concurrent.ExecutorService GAME_HERO_IO =
            createBoundedDaemonPool(2, 2, 32, "game-hero-io");
    private static final java.util.concurrent.ExecutorService GAME_ART_IO =
            createBoundedDaemonPool(2, 2, 48, "game-art-io");
    private static final java.util.concurrent.ExecutorService GAMING_RPC_IO =
            createBoundedDaemonPool(2, 3, 32, "gaming-rpc-io");

    /**
     * Releases decoded Gaming Zone hero backdrops and 16:9 card bitmaps from RAM before handing off
     * to Moonlight or another full-screen streaming/video app so MediaCodec has maximum free RAM.
     */
    public static void trimMemoryForStreaming() {
        synchronized (GAME_HERO_MEM) {
            for (Backdrop bd : GAME_HERO_MEM.values()) {
                if (bd != null) {
                    Backdrop.scheduleSafeRecycle(bd);
                }
            }
            GAME_HERO_MEM.clear();
        }
        synchronized (ART_MEM) {
            ART_SHADERS.clear();
            for (android.graphics.Bitmap bmp : ART_MEM.values()) {
                if (bmp != null) {
                    Backdrop.scheduleSafeBitmapRecycle(bmp);
                }
            }
            ART_MEM.clear();
        }
    }

    /**
     * Returns the current number of decoded hero backdrops held in {@code GAME_HERO_MEM}.
     */
    public static int getCachedHeroBackdropCount() {
        synchronized (GAME_HERO_MEM) {
            return GAME_HERO_MEM.size();
        }
    }

    private static final String[] GAMING_APPS = {
            "com.limelight",                // Moonlight Game Streaming (Preferred)
            "com.valvesoftware.steamlink",  // Steam Link (Fallback)
            "com.nvidia.tegrazone3"         // NVIDIA Games / GeForce NOW
    };

    private GamingZone() {}

    /**
     * Immutable value object representing a launchable game or desktop target in the Gaming Zone.
     */
    public static final class GameItem {
        public final String id;
        public final String title;
        public final String category;
        public final String cmd;
        public final boolean hidden;
        public final String imageUrl;
        public final long lastPlayedAt;

        public GameItem(String id, String title, String category, String cmd, boolean hidden) {
            this(id, title, category, cmd, hidden, resolveDefaultArtUrl(id, title, cmd), 0L);
        }

        public GameItem(String id, String title, String category, String cmd, boolean hidden, String imageUrl, long lastPlayedAt) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.category = category != null ? category : "steam";
            this.cmd = cmd != null ? cmd : "";
            this.hidden = hidden;
            this.imageUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : resolveDefaultArtUrl(this.id, this.title, this.cmd);
            this.lastPlayedAt = lastPlayedAt;
        }

        public GameItem withHidden(boolean isHidden) {
            return new GameItem(id, title, category, cmd, isHidden, imageUrl, lastPlayedAt);
        }

        public GameItem withLastPlayed(long timestamp) {
            return new GameItem(id, title, category, cmd, hidden, imageUrl, timestamp);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("category", category);
            o.put("cmd", cmd);
            o.put("hidden", hidden);
            o.put("imageUrl", imageUrl);
            o.put("lastPlayedAt", lastPlayedAt);
            return o;
        }
    }

    /**
     * Extracts the numeric Steam App ID for a game from its ID, launch command, or known title mapping,
     * returning {@code ""} for non-Steam standalone executables or system targets.
     */
    public static String extractSteamAppId(String id, String title, String cmd) {
        String safeId = id != null ? id.trim() : "";
        if (safeId.equals("steam_bigpicture") || safeId.equals("desktop") || safeId.equals(HIDDEN_TILE_ID)) {
            return "";
        }
        if (safeId.startsWith("steam_")) {
            String appId = safeId.substring("steam_".length()).trim();
            if (appId.matches("\\d+")) {
                return appId;
            }
        }
        if (cmd != null && cmd.contains("steam://rungameid/")) {
            int idx = cmd.indexOf("steam://rungameid/") + "steam://rungameid/".length();
            String appId = cmd.substring(idx).replaceAll("[^0-9]", "");
            if (!appId.isEmpty()) {
                return appId;
            }
        }
        String t = title != null ? title.toLowerCase(java.util.Locale.ROOT) : "";
        if (t.contains("hogwarts")) return "990080";
        if (t.contains("worms")) return "217200";
        if (t.contains("boomerang")) return "965680";
        if (t.contains("starve")) return "322330";
        if (t.contains("overcooked! 2") || t.contains("overcooked 2")) return "728880";
        if (t.contains("overcooked")) return "448510";
        if (t.contains("paralives")) return "1118520";
        if (t.contains("fling to the finish")) return "1054430";
        if (t.contains("no time to relax")) return "829660";
        return "";
    }

    private static boolean isLeagueOfLegends(String id, String title, String cmd) {
        String safeId = id != null ? id.trim().toLowerCase(java.util.Locale.ROOT) : "";
        String t = title != null ? title.trim().toLowerCase(java.util.Locale.ROOT) : "";
        String c = cmd != null ? cmd.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return safeId.equals("exe_riot_games")
                || t.contains("league of legends")
                || c.contains("leagueclient.exe");
    }

    /**
     * Resolves an official 16:9 cover art URL (Steam CDN or Riot DataDragon) for a game by ID, title, or launch command.
     */
    public static String resolveDefaultArtUrl(String id, String title, String cmd) {
        String appId = extractSteamAppId(id, title, cmd);
        if (!appId.isEmpty()) {
            return "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/header.jpg";
        }
        if (isLeagueOfLegends(id, title, cmd)) {
            return "https://ddragon.leagueoflegends.com/cdn/img/champion/splash/Ahri_0.jpg";
        }
        return "";
    }

    /**
     * Returns an ordered list of high-resolution (4K / @2x / Full-HD) hero background candidate URLs for a game.
     *
     * <p><b>Why:</b> Steam's standard {@code library_hero.jpg} is {@code 1920x620}, which center-crops to
     * {@code 1102x620} at {@code 16:9} and appears soft on 4K TVs. Prioritizing {@code library_hero_2x.jpg}
     * ({@code 3840x1240}, cropping to {@code 2204x1240} > Full HD) and official 4K standalone artwork ensures
     * every game renders a crisp, high-resolution hero backdrop.
     */
    public static List<String> resolveHeroArtCandidateUrls(String id, String title, String cmd) {
        String safeId = id != null ? id.trim() : "";
        if (safeId.isEmpty() || safeId.equals("steam_bigpicture") || safeId.equals("desktop") || safeId.equals(HIDDEN_TILE_ID)) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<>(4);
        String appId = extractSteamAppId(id, title, cmd);
        if (!appId.isEmpty()) {
            urls.add("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_hero_2x.jpg");
            urls.add("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_hero.jpg");
            return urls;
        }
        if (isLeagueOfLegends(id, title, cmd)) {
            urls.add("https://cmsassets.rgpub.io/sanity/images/dsfx7636/news/ffe8f50201af51a0956875d2aeeb9e662eb0b228-3840x2160.png");
            urls.add("https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/assets/characters/ahri/skins/base/images/ahri_splash_uncentered_0.jpg");
            urls.add("https://ddragon.leagueoflegends.com/cdn/img/champion/splash/Ahri_0.jpg");
            return urls;
        }
        return urls;
    }

    public static List<String> resolveHeroArtCandidateUrls(GameItem item) {
        if (item == null) return Collections.emptyList();
        return resolveHeroArtCandidateUrls(item.id, item.title, item.cmd);
    }

    /**
     * Resolves the primary high-resolution hero background URL ({@code library_hero_2x.jpg} for Steam titles,
     * official 4K key art for known standalone titles, or {@code ""} for system pseudo-tiles).
     */
    public static String resolveHeroArtUrl(String id, String title, String cmd) {
        List<String> candidates = resolveHeroArtCandidateUrls(id, title, cmd);
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    public static String resolveHeroArtUrl(GameItem item) {
        if (item == null) return "";
        return resolveHeroArtUrl(item.id, item.title, item.cmd);
    }

    /**
     * Parses the first Full-HD ({@code 1920x1080}) screenshot URL from Steam Store {@code appdetails} JSON.
     * Implemented in pure Java without {@code org.json} so it executes identically on Android TV and headless JVM tests.
     */
    public static String parseSteamStoreFirstScreenshotUrl(String json, String appId) {
        if (json == null || json.isEmpty() || appId == null || appId.isEmpty()) return "";
        int appIdx = json.indexOf("\"" + appId + "\"");
        if (appIdx < 0) return "";
        int shotsIdx = json.indexOf("\"screenshots\"", appIdx);
        if (shotsIdx < 0) return "";
        java.util.regex.Matcher successMatcher = java.util.regex.Pattern
                .compile("\"success\"\\s*:\\s*(true|false)")
                .matcher(json.substring(appIdx, shotsIdx));
        if (!successMatcher.find() || !"true".equals(successMatcher.group(1))) {
            return "";
        }
        java.util.regex.Matcher fullMatcher = java.util.regex.Pattern
                .compile("\"path_full\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        while (fullMatcher.find(shotsIdx)) {
            String raw = fullMatcher.group(1);
            String unescaped = raw
                    .replace("\\/", "/")
                    .replace("\\\"", "\"")
                    .replace("\\u0026", "&")
                    .trim();
            if (unescaped.startsWith("http")) {
                return unescaped;
            }
            shotsIdx = fullMatcher.end();
        }
        return "";
    }

    /**
     * Parses a numeric Steam App ID from Steam Store {@code storesearch} JSON response.
     * Scans only top-level fields of the first {@code items} entry in pure Java (safe in headless JVM tests).
     */
    public static String parseSteamStoreSearchAppId(String json) {
        if (json == null || json.isEmpty()) return "";
        int itemsIdx = json.indexOf("\"items\"");
        if (itemsIdx < 0) return "";
        int arrStart = json.indexOf('[', itemsIdx);
        if (arrStart < 0) return "";
        int len = json.length();
        int braceDepth = 0;
        for (int i = arrStart + 1; i < len; i++) {
            char c = json.charAt(i);
            if (c == '"') {
                int strEnd = i + 1;
                while (strEnd < len) {
                    char sc = json.charAt(strEnd);
                    if (sc == '\\') {
                        strEnd += 2;
                    } else if (sc == '"') {
                        break;
                    } else {
                        strEnd++;
                    }
                }
                if (braceDepth == 1 && strEnd < len) {
                    String token = json.substring(i + 1, strEnd);
                    if ("id".equals(token)) {
                        int k = strEnd + 1;
                        while (k < len && (json.charAt(k) <= ' ' || json.charAt(k) == ':')) k++;
                        int numStart = k;
                        while (k < len && Character.isDigit(json.charAt(k))) k++;
                        if (k > numStart) {
                            String digits = json.substring(numStart, k);
                            try {
                                if (Long.parseLong(digits) > 0L) return digits;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
                i = strEnd;
                continue;
            }
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0) {
                    // Finished scanning the first item object
                    break;
                }
            } else if (c == ']' && braceDepth == 0) {
                break;
            }
        }
        return "";
    }

    /**
     * Snapshot of the gaming laptop's runtime state returned by the Laptop Gaming Agent (:8485).
     */
    public static final class LaptopStatus {
        public final boolean awake;
        public final boolean locked;
        public final boolean hdmiConnected;
        public final boolean sunshineRunning;
        public final List<GameItem> games;
        public final int batteryPercent;
        public final boolean charging;
        public final boolean batteryFull;
        public final String sunshineHostUuid;
        public final String sunshineHostName;
        public final int pcDisplayWidth;
        public final int pcDisplayHeight;
        public final int pcDisplayRefreshHz;
        public final boolean steamRunning;
        public final boolean epicRunning;
        public final boolean heroicRunning;
        public final boolean sunshinePowerControlReady;

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games
        ) {
            this(awake, locked, hdmiConnected, sunshineRunning, games, -1, false, false, "", "", 0, 0, 0, false, false, false, false);
        }

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games,
                int batteryPercent,
                boolean charging,
                boolean batteryFull
        ) {
            this(awake, locked, hdmiConnected, sunshineRunning, games, batteryPercent, charging, batteryFull, "", "", 0, 0, 0, false, false, false, false);
        }

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games,
                int batteryPercent,
                boolean charging,
                boolean batteryFull,
                String sunshineHostUuid,
                String sunshineHostName
        ) {
            this(awake, locked, hdmiConnected, sunshineRunning, games,
                    batteryPercent, charging, batteryFull, sunshineHostUuid, sunshineHostName, 0, 0, 0, false, false, false, false);
        }

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games,
                int batteryPercent,
                boolean charging,
                boolean batteryFull,
                String sunshineHostUuid,
                String sunshineHostName,
                int pcDisplayWidth,
                int pcDisplayHeight,
                int pcDisplayRefreshHz
        ) {
            this(awake, locked, hdmiConnected, sunshineRunning, games,
                    batteryPercent, charging, batteryFull, sunshineHostUuid, sunshineHostName,
                    pcDisplayWidth, pcDisplayHeight, pcDisplayRefreshHz, false, false, false, false);
        }

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games,
                int batteryPercent,
                boolean charging,
                boolean batteryFull,
                String sunshineHostUuid,
                String sunshineHostName,
                int pcDisplayWidth,
                int pcDisplayHeight,
                int pcDisplayRefreshHz,
                boolean steamRunning,
                boolean epicRunning
        ) {
            this(awake, locked, hdmiConnected, sunshineRunning, games,
                    batteryPercent, charging, batteryFull, sunshineHostUuid, sunshineHostName,
                    pcDisplayWidth, pcDisplayHeight, pcDisplayRefreshHz, steamRunning, epicRunning, false, false);
        }

        public LaptopStatus(
                boolean awake,
                boolean locked,
                boolean hdmiConnected,
                boolean sunshineRunning,
                List<GameItem> games,
                int batteryPercent,
                boolean charging,
                boolean batteryFull,
                String sunshineHostUuid,
                String sunshineHostName,
                int pcDisplayWidth,
                int pcDisplayHeight,
                int pcDisplayRefreshHz,
                boolean steamRunning,
                boolean epicRunning,
                boolean heroicRunning,
                boolean sunshinePowerControlReady
        ) {
            this.awake = awake;
            this.locked = locked;
            this.hdmiConnected = hdmiConnected;
            this.sunshineRunning = sunshineRunning;
            this.games = games != null ? Collections.unmodifiableList(new ArrayList<>(games)) : Collections.emptyList();
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.batteryFull = batteryFull;
            this.sunshineHostUuid = sunshineHostUuid != null ? sunshineHostUuid.trim() : "";
            this.sunshineHostName = sunshineHostName != null ? sunshineHostName.trim() : "";
            this.pcDisplayWidth = Math.max(0, pcDisplayWidth);
            this.pcDisplayHeight = Math.max(0, pcDisplayHeight);
            this.pcDisplayRefreshHz = Math.max(0, pcDisplayRefreshHz);
            this.steamRunning = steamRunning;
            this.epicRunning = epicRunning;
            this.heroicRunning = heroicRunning;
            this.sunshinePowerControlReady = sunshinePowerControlReady;
        }
    }

    /**
     * Resolves the exact Sunshine application name registered in {@code apps.json} for a {@link GameItem}.
     *
     * <p><b>Why:</b> Sunshine registers its built-in desktop target as {@code "Desktop"} while SoarTV's catalog
     * displays {@code "Windows Desktop"} ({@code id="desktop"}). Mapping {@code desktop -> Desktop} and honoring
     * any server-reported {@code sunshineAppName} ensures Moonlight's {@code ShortcutTrampoline} matches the exact
     * Sunshine app entry without manual selection.
     */
    public static String resolveSunshineAppName(GameItem game, String serverAppName) {
        if (serverAppName != null && !serverAppName.trim().isEmpty()) {
            return serverAppName.trim();
        }
        if (game == null) {
            return "Steam Big Picture";
        }
        String safeId = game.id != null ? game.id.trim() : "";
        if ("desktop".equalsIgnoreCase(safeId)) {
            return "Desktop";
        }
        if ("steam_bigpicture".equalsIgnoreCase(safeId)) {
            return "Steam Big Picture";
        }
        String safeTitle = game.title != null ? game.title.trim() : "";
        return !safeTitle.isEmpty() ? safeTitle : "Steam Big Picture";
    }

    /**
     * Pure, immutable specification for launching a Sunshine stream directly via Moonlight's
     * {@code com.limelight.ShortcutTrampoline} activity without opening Moonlight's host/app menus.
     *
     * <p><b>Why:</b> Moonlight's {@code ShortcutTrampoline.validateHostArgs} rejects intents that supply both
     * {@code UUID} and {@code Name} simultaneously. Encapsulating this rule in a pure value object makes host-extra
     * mutual exclusion 100% unit-testable on headless Desktop JVMs.
     */
    public static final class MoonlightLaunchSpec {
        public final String packageName;
        public final String className;
        public final String uuidExtra;
        public final String nameExtra;
        public final String appNameExtra;
        public final boolean canDirectLaunch;

        public MoonlightLaunchSpec(
                String packageName,
                String className,
                String uuidExtra,
                String nameExtra,
                String appNameExtra,
                boolean canDirectLaunch
        ) {
            this.packageName = packageName;
            this.className = className;
            this.uuidExtra = uuidExtra;
            this.nameExtra = nameExtra;
            this.appNameExtra = appNameExtra;
            this.canDirectLaunch = canDirectLaunch;
        }

        /**
         * Resolves a {@link MoonlightLaunchSpec} prioritizing {@code hostUuid} over {@code hostName} (never both).
         */
        public static MoonlightLaunchSpec resolve(
                String hostUuid,
                String hostName,
                GameItem game,
                String serverAppName
        ) {
            String cleanUuid = hostUuid != null ? hostUuid.trim() : "";
            String cleanName = hostName != null ? hostName.trim() : "";
            String appName = resolveSunshineAppName(game, serverAppName);
            if (!cleanUuid.isEmpty()) {
                return new MoonlightLaunchSpec(
                        MOONLIGHT_PACKAGE,
                        MOONLIGHT_SHORTCUT_TRAMPOLINE_CLASS,
                        cleanUuid,
                        null,
                        appName,
                        true
                );
            }
            if (!cleanName.isEmpty()) {
                return new MoonlightLaunchSpec(
                        MOONLIGHT_PACKAGE,
                        MOONLIGHT_SHORTCUT_TRAMPOLINE_CLASS,
                        null,
                        cleanName,
                        appName,
                        true
                );
            }
            return new MoonlightLaunchSpec(
                    MOONLIGHT_PACKAGE,
                    MOONLIGHT_SHORTCUT_TRAMPOLINE_CLASS,
                    null,
                    null,
                    appName,
                    false
            );
        }
    }

    /**
     * Converts a validated {@link MoonlightLaunchSpec} into an explicit Android {@link Intent} targeting
     * {@code com.limelight.ShortcutTrampoline}.
     */
    public static Intent buildMoonlightDirectLaunchIntent(MoonlightLaunchSpec spec) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(spec.packageName, spec.className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (spec.uuidExtra != null && !spec.uuidExtra.isEmpty()) {
            intent.putExtra(MOONLIGHT_EXTRA_UUID, spec.uuidExtra);
        } else if (spec.nameExtra != null && !spec.nameExtra.isEmpty()) {
            intent.putExtra(MOONLIGHT_EXTRA_NAME, spec.nameExtra);
        }
        intent.putExtra(MOONLIGHT_EXTRA_APP_NAME, spec.appNameExtra);
        return intent;
    }

    /**
     * Pure, immutable value object recording an active game streaming or HDMI gaming session.
     * Pure Java without Android framework dependencies so it is 100% testable on headless JVMs.
     */
    public static final class ActiveStreamingSession {
        public final String gameId;
        public final String gameTitle;
        public final String hostIp;
        public final int port;
        public final long launchTimeMs;

        public ActiveStreamingSession(String gameId, String gameTitle, String hostIp, int port, long launchTimeMs) {
            this.gameId = gameId != null ? gameId.trim() : "";
            this.gameTitle = gameTitle != null ? gameTitle.trim() : "";
            this.hostIp = hostIp != null ? hostIp.trim() : "";
            this.port = port > 0 ? port : LAPTOP_AGENT_PORT;
            this.launchTimeMs = launchTimeMs;
        }

        public ActiveStreamingSession(String gameId, String gameTitle, String hostIp) {
            this(gameId, gameTitle, hostIp, LAPTOP_AGENT_PORT, System.currentTimeMillis());
        }

        public long getElapsedMs(long currentNowMs) {
            return Math.max(0L, currentNowMs - launchTimeMs);
        }
    }

    public static final long EXIT_DEBOUNCE_THRESHOLD_MS = 3000L;
    static final long PRELAUNCH_WINDOW_WAIT_MAX_MS = 45_000L;
    static final long PRELAUNCH_WINDOW_POLL_MS = 350L;
    private static final String ACTIVE_SESSION_FILE_NAME = "active_stream_session.txt";
    private static final Object ACTIVE_SESSION_FILE_LOCK = new Object();
    private static volatile java.io.File activeSessionLastDir = null;
    private static volatile java.io.File testActiveSessionDir = null;
    private static final java.util.concurrent.atomic.AtomicReference<ActiveStreamingSession> ACTIVE_STREAMING_SESSION =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private static final java.util.concurrent.atomic.AtomicReference<ActiveStreamingSession> INFLIGHT_PRELAUNCH_SESSION =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    static void setTestActiveSessionDir(java.io.File dir) {
        testActiveSessionDir = dir;
        activeSessionLastDir = null;
    }

    private static java.io.File resolveActiveSessionFile(Context context) {
        if (testActiveSessionDir != null) {
            return new java.io.File(testActiveSessionDir, ACTIVE_SESSION_FILE_NAME);
        }
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                java.io.File fDir = appCtx.getFilesDir();
                if (fDir != null) {
                    activeSessionLastDir = fDir;
                    return new java.io.File(fDir, ACTIVE_SESSION_FILE_NAME);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (activeSessionLastDir != null) {
            return new java.io.File(activeSessionLastDir, ACTIVE_SESSION_FILE_NAME);
        }
        return null;
    }

    static String serializeActiveStreamingSession(ActiveStreamingSession session) {
        if (session == null) return "";
        java.util.Base64.Encoder enc = java.util.Base64.getEncoder();
        String gid = enc.encodeToString(session.gameId.getBytes(StandardCharsets.UTF_8));
        String gtitle = enc.encodeToString(session.gameTitle.getBytes(StandardCharsets.UTF_8));
        String hip = enc.encodeToString(session.hostIp.getBytes(StandardCharsets.UTF_8));
        return gid + "|" + gtitle + "|" + hip + "|" + session.port + "|" + session.launchTimeMs;
    }

    static ActiveStreamingSession parseActiveStreamingSession(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String[] parts = raw.trim().split("\\|", -1);
            if (parts.length < 5) return null;
            java.util.Base64.Decoder dec = java.util.Base64.getDecoder();
            String gid = new String(dec.decode(parts[0]), StandardCharsets.UTF_8);
            String gtitle = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);
            String hip = new String(dec.decode(parts[2]), StandardCharsets.UTF_8);
            int port = Integer.parseInt(parts[3].trim());
            long launchMs = Long.parseLong(parts[4].trim());
            return new ActiveStreamingSession(gid, gtitle, hip, port, launchMs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setActiveStreamingSession(ActiveStreamingSession session) {
        setActiveStreamingSession(null, session);
    }

    public static void setActiveStreamingSession(Context context, ActiveStreamingSession session) {
        ACTIVE_STREAMING_SESSION.set(session);
        java.io.File f = resolveActiveSessionFile(context);
        if (f == null) return;
        synchronized (ACTIVE_SESSION_FILE_LOCK) {
            try {
                if (session == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    java.io.File parent = f.getParentFile();
                    if (parent != null && !parent.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    String json = serializeActiveStreamingSession(session);
                    java.io.File tmp = new java.io.File(parent, ACTIVE_SESSION_FILE_NAME + ".tmp");
                    java.nio.file.Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
                    java.nio.file.Files.move(
                            tmp.toPath(),
                            f.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static ActiveStreamingSession getActiveStreamingSession() {
        return getActiveStreamingSession(null);
    }

    public static ActiveStreamingSession getActiveStreamingSession(Context context) {
        java.io.File f = resolveActiveSessionFile(context);
        if (f != null) {
            synchronized (ACTIVE_SESSION_FILE_LOCK) {
                if (!f.isFile()) {
                    ACTIVE_STREAMING_SESSION.set(null);
                    return null;
                }
                try {
                    String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    ActiveStreamingSession parsed = parseActiveStreamingSession(raw);
                    ACTIVE_STREAMING_SESSION.set(parsed);
                    return parsed;
                } catch (Throwable ignored) {
                }
            }
        }
        return ACTIVE_STREAMING_SESSION.get();
    }

    public static ActiveStreamingSession clearActiveStreamingSession() {
        return clearActiveStreamingSession(null);
    }

    public static ActiveStreamingSession clearActiveStreamingSession(Context context) {
        java.io.File f = resolveActiveSessionFile(context);
        if (f != null) {
            synchronized (ACTIVE_SESSION_FILE_LOCK) {
                ActiveStreamingSession prev = null;
                if (f.isFile()) {
                    try {
                        String raw = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        prev = parseActiveStreamingSession(raw);
                    } catch (Throwable ignored) {
                    }
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                ActiveStreamingSession memPrev = ACTIVE_STREAMING_SESSION.getAndSet(null);
                return prev != null ? prev : memPrev;
            }
        }
        return ACTIVE_STREAMING_SESSION.getAndSet(null);
    }

    /**
     * Pure evaluation of whether an active session should trigger remote exit on launcher resume.
     */
    public static boolean shouldTriggerRemoteExit(ActiveStreamingSession session, long nowMs, long minElapsedThresholdMs) {
        if (session == null) return false;
        return (nowMs - session.launchTimeMs) >= minElapsedThresholdMs;
    }

    /**
     * Pure JSON payload builder for termination RPC without Android runtime stubs.
     */
    public static String buildTerminationPayload(String gameId, String reason) {
        String safeId = gameId != null ? gameId.replace("\"", "\\\"") : "";
        String safeReason = reason != null ? reason.replace("\"", "\\\"") : "remote_home_exit";
        return "{\"gameId\":\"" + safeId + "\",\"reason\":\"" + safeReason + "\"}";
    }

    /**
     * Invoked from HomeActivity.onResume(). Checks active session, evaluates debounce, and dispatches exit RPC.
     */
    public static void handleLauncherResume(final Context context) {
        final ActiveStreamingSession session = getActiveStreamingSession(context);
        if (session == null) return;

        long now = System.currentTimeMillis();
        if (shouldTriggerRemoteExit(session, now, EXIT_DEBOUNCE_THRESHOLD_MS)) {
            final ActiveStreamingSession toTerminate = clearActiveStreamingSession(context);
            if (toTerminate != null) {
                dispatchRemoteTerminationAsync(context, toTerminate, "remote_home_exit");
            }
        }
    }

    /**
     * Dispatches POST /api/gaming/terminate on background GAMING_RPC_IO thread pool.
     */
    public static void dispatchRemoteTerminationAsync(
            final Context context,
            final ActiveStreamingSession session,
            final String reason
    ) {
        if (session == null) return;
        final Context appCtx = context != null ? context.getApplicationContext() : null;
        GAMING_RPC_IO.execute(() -> terminateSessionOverHttpSync(appCtx, session, reason));
    }

    private static boolean terminateSessionOverHttpSync(
            Context context,
            ActiveStreamingSession session,
            String reason) {
        if (session == null) return false;
        String payload = buildTerminationPayload(session.gameId, reason);
        String host = session.hostIp != null && !session.hostIp.isEmpty()
                ? session.hostIp
                : (context != null ? getLaptopIp(context) : "127.0.0.1");
        int port = session.port > 0 ? session.port : LAPTOP_AGENT_PORT;
        try {
            HttpResult res = rawHttpRequestWithStatus(
                    context, host, port, "POST", "/api/gaming/terminate", payload, 3500);
            AppLog.i("GamingZone: Dispatched remote termination for " + session.gameId
                    + " (" + reason + ") -> HTTP " + res.statusCode);
            return res.isSuccess();
        } catch (Exception firstErr) {
            if (context != null && port == LAPTOP_AGENT_PORT) {
                String discovered = discoverLaptopIpOnLanSync(context);
                if (!discovered.isEmpty() && !discovered.equals(host)) {
                    try {
                        HttpResult retryRes = rawHttpRequestWithStatus(
                                context, discovered, port, "POST", "/api/gaming/terminate", payload, 3500);
                        AppLog.i("GamingZone: Dispatched remote termination after IP re-discovery ("
                                + discovered + ") -> HTTP " + retryRes.statusCode);
                        return retryRes.isSuccess();
                    } catch (Exception ignored) {
                    }
                }
            }
            AppLog.w("GamingZone: Remote termination dispatch failed (non-fatal): " + firstErr.getMessage());
            return false;
        }
    }

    /**
     * Synchronously terminates the active PC game (bypassing the 3000ms HomeActivity resume debounce)
     * and clears {@code ACTIVE_STREAMING_SESSION}. Used by unit tests and immediate exit handlers.
     */
    public static boolean terminateActivePCGameSync(Context context, String fallbackGameId, String reason) {
        ActiveStreamingSession active = clearActiveStreamingSession(context);
        ActiveStreamingSession inflight = INFLIGHT_PRELAUNCH_SESSION.getAndSet(null);
        ActiveStreamingSession target = active != null ? active : inflight;
        if (target == null) {
            String gid = fallbackGameId != null ? fallbackGameId : "";
            String host = context != null ? getLaptopIp(context) : "127.0.0.1";
            target = new ActiveStreamingSession(gid, "", host, LAPTOP_AGENT_PORT, System.currentTimeMillis());
        }
        return terminateSessionOverHttpSync(context, target, reason);
    }

    /**
     * Immediately dispatches PC game termination + privacy restore on {@code GAMING_RPC_IO} without debounce.
     * Invoked when the user presses TV Remote {@code Back}/{@code Home} inside {@link GameStreamActivity}.
     */
    public static void terminateActivePCGameNow(final Context context, final String fallbackGameId, final String reason) {
        final Context appCtx = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        final ActiveStreamingSession active = clearActiveStreamingSession(appCtx);
        final ActiveStreamingSession inflight = INFLIGHT_PRELAUNCH_SESSION.getAndSet(null);
        final ActiveStreamingSession target = active != null
                ? active
                : (inflight != null
                        ? inflight
                        : new ActiveStreamingSession(
                                fallbackGameId != null ? fallbackGameId : "",
                                "",
                                appCtx != null ? getLaptopIp(appCtx) : "127.0.0.1",
                                LAPTOP_AGENT_PORT,
                                System.currentTimeMillis()));
        GAMING_RPC_IO.execute(() -> terminateSessionOverHttpSync(appCtx, target, reason));
    }

    /**
     * Submits a 4-digit Sunshine pairing PIN to the authenticated Laptop Gaming Agent (`POST /api/gaming/sunshine-pair`)
     * so {@link SunshineStreamClient} pairs SoarTV's RSA-2048 certificate with zero manual PC UI prompts.
     */
    public static boolean submitSunshinePairingPinSync(
            Context context,
            String host,
            String pin,
            String deviceName) {
        return submitSunshinePairingPinSync(context, host, LAPTOP_AGENT_PORT, pin, deviceName);
    }

    static boolean submitSunshinePairingPinSync(
            Context context,
            String host,
            int agentPort,
            String pin,
            String deviceName) {
        if (pin == null || pin.trim().isEmpty()) return false;
        int port = agentPort > 0 ? agentPort : LAPTOP_AGENT_PORT;
        String cleanName = deviceName != null && !deviceName.trim().isEmpty() ? deviceName.trim() : "SoarTV";
        try {
            String cleanPin = pin.trim().replace("\\", "\\\\").replace("\"", "\\\"");
            String escapedName = cleanName.replace("\\", "\\\\").replace("\"", "\\\"");
            String bodyJson = "{\"pin\":\"" + cleanPin + "\",\"name\":\"" + escapedName
                    + "\",\"deviceName\":\"" + escapedName + "\"}";
            String targetHost = (host != null && !host.trim().isEmpty()) ? host.trim() : getLaptopIp(context);
            try {
                HttpResult res = rawHttpRequestWithStatus(
                        context, targetHost, port, "POST", "/api/gaming/sunshine-pair", bodyJson, 9000);
                return res.isSuccess();
            } catch (IOException firstIo) {
                if (context != null) {
                    String discovered = discoverLaptopIpOnLanSync(context);
                    if (!discovered.isEmpty() && !discovered.equals(targetHost)) {
                        HttpResult retry = rawHttpRequestWithStatus(
                                context, discovered, port, "POST", "/api/gaming/sunshine-pair", bodyJson, 9000);
                        return retry.isSuccess();
                    }
                }
                throw firstIo;
            }
        } catch (Exception e) {
            AppLog.w("GamingZone submitSunshinePairingPinSync failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Asks the authenticated Laptop Gaming Agent ({@code POST /api/gaming/sunshine/start}) to start
     * Sunshine on demand and wait until its GameStream ports ({@code 47989} &amp; {@code 47984}) are ready.
     */
    public static boolean ensureSunshineRunningOnPcSync(Context context, String host) {
        return ensureSunshineRunningOnPcSync(context, host, LAPTOP_AGENT_PORT);
    }

    static boolean ensureSunshineRunningOnPcSync(Context context, String host, int agentPort) {
        int port = agentPort > 0 ? agentPort : LAPTOP_AGENT_PORT;
        String targetHost = (host != null && !host.trim().isEmpty())
                ? host.trim()
                : (context != null ? getLaptopIp(context) : "127.0.0.1");
        try {
            HttpResult res = rawHttpRequestWithStatus(
                    context, targetHost, port, "POST", "/api/gaming/sunshine/start", "{}", 2500, 30000);
            if (res.isSuccess()) {
                return true;
            }
        } catch (IOException firstIo) {
            if (context != null && port == LAPTOP_AGENT_PORT) {
                String discovered = discoverLaptopIpOnLanSync(context);
                if (!discovered.isEmpty() && !discovered.equals(targetHost)) {
                    try {
                        HttpResult retry = rawHttpRequestWithStatus(
                                context, discovered, port, "POST", "/api/gaming/sunshine/start", "{}", 2500, 30000);
                        return retry.isSuccess();
                    } catch (IOException ignored) {
                    }
                }
            }
            AppLog.w("GamingZone ensureSunshineRunningOnPcSync failed: " + firstIo.getMessage());
        } catch (Exception e) {
            AppLog.w("GamingZone ensureSunshineRunningOnPcSync error: " + e.getMessage());
        }
        return false;
    }

    /**
     * Immutable UI presentation step for the animated Liquid Glass Game Launch Modal (zero emojis).
     */
    public static final class LaunchModalStep {
        public final int stageIndex;
        public final int progressPercent;
        public final String iconName;
        public final String headline;
        public final String subtitle;

        public LaunchModalStep(
                int stageIndex,
                int progressPercent,
                String iconName,
                String headline,
                String subtitle
        ) {
            this.stageIndex = stageIndex;
            this.progressPercent = progressPercent;
            this.iconName = iconName;
            this.headline = headline;
            this.subtitle = subtitle;
        }
    }

    /**
     * Ordered lifecycle stages shown in the animated Game Launch Modal while waking the PC, unlocking Windows,
     * pre-warming Steam silently, and connecting directly to Moonlight or HDMI.
     */
    public enum LaunchStage {
        WAKING_PC(0, 18, "power"),
        UNLOCKING_WINDOWS(1, 38, "key"),
        PREPARING_STEAM(2, 64, "controller"),
        CONNECTING_STREAM(3, 92, "check");

        public final int stageIndex;
        public final int progressPercent;
        public final String iconName;

        LaunchStage(int stageIndex, int progressPercent, String iconName) {
            this.stageIndex = stageIndex;
            this.progressPercent = progressPercent;
            this.iconName = iconName;
        }

        private static String trSafe(String english) {
            try {
                return Ui.tr(english);
            } catch (Throwable ignored) {
                return english;
            }
        }

        /**
         * Builds localized, human-readable explanatory copy for this launch stage without emojis.
         */
        public LaunchModalStep buildStep(GameItem game, String hdmiLabel) {
            switch (this) {
                case WAKING_PC:
                    return new LaunchModalStep(
                            stageIndex,
                            progressPercent,
                            iconName,
                            trSafe("Waking up Gaming PC…"),
                            trSafe("Checking wireless & HDMI connection")
                    );
                case UNLOCKING_WINDOWS:
                    return new LaunchModalStep(
                            stageIndex,
                            progressPercent,
                            iconName,
                            trSafe("Unlocking Windows…"),
                            trSafe("Signing in to your PC session")
                    );
                case PREPARING_STEAM: {
                    String cmdLower = (game != null && game.cmd != null)
                            ? game.cmd.toLowerCase(java.util.Locale.ROOT)
                            : "";
                    boolean usesHeroic = game != null
                            && ("heroic".equalsIgnoreCase(game.category)
                            || (game.id != null && game.id.startsWith("heroic_"))
                            || cmdLower.startsWith("heroic://"));
                    boolean usesEpic = !usesHeroic && game != null
                            && ("epic".equalsIgnoreCase(game.category)
                            || "legendary".equalsIgnoreCase(game.category)
                            || (game.id != null && (game.id.startsWith("epic_") || game.id.startsWith("legendary_")))
                            || cmdLower.startsWith("com.epicgames.launcher://")
                            || cmdLower.startsWith("legendary://"));
                    boolean usesSteam = !usesHeroic && !usesEpic && (game == null
                            || "steam".equalsIgnoreCase(game.category)
                            || "steam_bigpicture".equalsIgnoreCase(game.id)
                            || (game.id != null && game.id.startsWith("steam_"))
                            || cmdLower.contains("steam://"));
                    String head = usesHeroic
                            ? trSafe("Starting Heroic Games & Preparing Stream…")
                            : (usesEpic
                                    ? trSafe("Starting Epic Games & Preparing Stream…")
                                    : (usesSteam
                                            ? trSafe("Starting Steam & Preparing Stream…")
                                            : trSafe("Preparing Stream…")));
                    String sub = (usesHeroic || usesEpic || usesSteam)
                            ? trSafe("Suppressing PC pop-ups & routing audio to TV")
                            : trSafe("Routing audio & controllers to TV");
                    return new LaunchModalStep(stageIndex, progressPercent, iconName, head, sub);
                }
                case CONNECTING_STREAM:
                default: {
                    if (hdmiLabel != null && !hdmiLabel.trim().isEmpty()) {
                        return new LaunchModalStep(
                                stageIndex,
                                progressPercent,
                                iconName,
                                trSafe("Switching TV to ") + hdmiLabel.trim() + "…",
                                trSafe("HDMI cable active — routing video & audio")
                        );
                    }
                    String target = (game != null && game.title != null && !game.title.trim().isEmpty())
                            ? game.title.trim()
                            : "Steam Big Picture";
                    return new LaunchModalStep(
                            stageIndex,
                            progressPercent,
                            iconName,
                            trSafe("Launching ") + target + "…",
                            trSafe("Stream ready — press any controller button once in game")
                    );
                }
            }
        }
    }

    /**
     * Cancels any in-flight asynchronous game launch handoff so pressing Back/Cancel on the Game Launch Modal
     * cleanly aborts opening Moonlight or switching HDMI.
     */
    public static void cancelActiveGameLaunch() {
        ACTIVE_LAUNCH_TOKEN.incrementAndGet();
        ActiveStreamingSession active = clearActiveStreamingSession();
        ActiveStreamingSession inflight = INFLIGHT_PRELAUNCH_SESSION.getAndSet(null);
        final ActiveStreamingSession toClean = active != null ? active : inflight;
        if (toClean != null) {
            GAMING_RPC_IO.execute(() -> terminateSessionOverHttpSync(null, toClean, "user_cancelled_launch_modal"));
        }
    }

    /**
     * Returns the default catalog of Steam and standalone PC game titles discovered on the user's laptop.
     *
     * @return Unmodifiable list of default {@link GameItem} entries.
     */
    public static List<GameItem> defaultGameCatalog() {
        return Collections.unmodifiableList(Arrays.asList(
                new GameItem("steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", false),
                new GameItem("desktop", "Windows Desktop", "system", "explorer.exe", false),
                new GameItem("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", false),
                new GameItem("steam_322330", "Don't Starve Together", "steam", "steam://rungameid/322330", false),
                new GameItem("steam_728880", "Overcooked! 2", "steam", "steam://rungameid/728880", false),
                new GameItem("steam_1118520", "Paralives", "steam", "steam://rungameid/1118520", false),
                new GameItem("exe_hogwartslegacy", "Hogwarts Legacy", "standalone", "C:\\Games\\HogwartsLegacy\\HogwartsLegacy.exe", false),
                new GameItem("exe_riot_games", "League of Legends", "standalone", "C:\\Games\\Riot Games\\League of Legends\\LeagueClient.exe", false),
                new GameItem("exe_worms_armageddon", "Worms Armageddon", "standalone", "C:\\Games\\Worms Armageddon\\WA.exe", false)
        ));
    }

    /**
     * Filters and annotates a list of games with the user's hidden game IDs.
     *
     * @param games      Full catalog of games.
     * @param hiddenIds  Set of game IDs hidden by the user.
     * @param showHidden When {@code true}, returns all games with {@code hidden=true} marked; when {@code false}, excludes hidden games.
     * @return Filtered list of {@link GameItem} objects.
     */
    public static List<GameItem> filterGames(List<GameItem> games, Set<String> hiddenIds, boolean showHidden) {
        if (games == null) return Collections.emptyList();
        Set<String> hidden = hiddenIds != null ? hiddenIds : Collections.emptySet();
        List<GameItem> result = new ArrayList<>(games.size());
        for (GameItem item : games) {
            boolean isHidden = hiddenIds != null ? hidden.contains(item.id) : item.hidden;
            if (!showHidden && isHidden) {
                continue;
            }
            result.add(item.withHidden(isHidden));
        }
        return result;
    }

    /**
     * Returns only games that are temporarily hidden (present in {@code hiddenIds} and NOT in {@code permHiddenIds}),
     * excluding system entries ({@code steam_bigpicture}, {@code desktop}).
     */
    public static List<GameItem> filterTemporarilyHiddenGames(
            List<GameItem> games,
            Set<String> hiddenIds,
            Set<String> permHiddenIds
    ) {
        if (games == null) return Collections.emptyList();
        Set<String> hidden = hiddenIds != null ? hiddenIds : Collections.emptySet();
        Set<String> perm = permHiddenIds != null ? permHiddenIds : Collections.emptySet();
        List<GameItem> result = new ArrayList<>();
        for (GameItem g : games) {
            if ("system".equals(g.category) || HIDDEN_TILE_ID.equals(g.id)) continue;
            if (perm.contains(g.id)) continue;
            if (g.hidden || hidden.contains(g.id)) {
                result.add(g.withHidden(true));
            }
        }
        return result;
    }

    /**
     * Selects the preferred game streaming client package installed on the TV (Moonlight first, Steam Link second).
     *
     * @param installedPackages Collection of installed package names on the TV.
     * @return Preferred package name, or {@code null} if none of the supported clients are installed.
     */
    public static String selectPreferredClientPackage(Collection<String> installedPackages) {
        if (installedPackages == null) return null;
        for (String candidate : GAMING_APPS) {
            if (installedPackages.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Classifies an HDMI input label (from HDMI-CEC or Android TV input settings) into an {@link HdmiRole}.
     *
     * <p><b>Why:</b> Automatically detects PlayStation/Sony/Xbox/Nintendo Switch/Wii consoles and
     * Apple TV/Mecool/Chromecast streamers on any user's TV without hardcoding port numbers.
     */
    public static HdmiRole classifyLabelAsRole(String label) {
        if (label == null || label.trim().isEmpty()) return HdmiRole.AUTO;
        String lower = label.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("^hdmi\\s*\\d+$")) return HdmiRole.AUTO;
        String[] consoleTokens = {
                "playstation", "ps5", "ps4", "ps3", "sony", "xbox",
                "nintendo", "switch", "wii", "steam deck", "console"
        };
        for (String tok : consoleTokens) {
            if (lower.contains(tok)) return HdmiRole.CONSOLE;
        }
        String[] streamerTokens = {
                "mecool", "apple tv", "chromecast", "fire tv", "firetv",
                "roku", "shield", "mi box", "tv box", "tvbox", "streamer",
                "cable", "set-top", "decoder", "tuner", "airplay"
        };
        for (String tok : streamerTokens) {
            if (lower.contains(tok)) return HdmiRole.STREAMER;
        }
        String[] laptopTokens = {
                "laptop", "pc", "computer", "desktop", "omen", "legion", "rog", "alienware"
        };
        for (String tok : laptopTokens) {
            if (lower.contains(tok)) return HdmiRole.LAPTOP;
        }
        return HdmiRole.AUTO;
    }

    /**
     * Parses a comma-separated HDMI port role specification (e.g., {@code "1:streamer:Mecool,2:streamer:Apple TV,3:laptop:Laptop"}).
     */
    public static java.util.Map<Integer, HdmiPortConfig> parseHdmiPortRolesSpec(String spec) {
        java.util.Map<Integer, HdmiPortConfig> out = new java.util.HashMap<>();
        if (spec == null || spec.trim().isEmpty()) return out;
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(":", 3);
            if (parts.length < 2) continue;
            try {
                int port = Integer.parseInt(parts[0].trim());
                if (port < 1 || port > 8) continue;
                String roleStr = parts[1].trim().toUpperCase(java.util.Locale.ROOT);
                HdmiRole role;
                try {
                    role = HdmiRole.valueOf(roleStr);
                } catch (IllegalArgumentException e) {
                    role = HdmiRole.AUTO;
                }
                String devName = parts.length >= 3 ? parts[2].trim() : "";
                out.put(port, new HdmiPortConfig(port, role, devName));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Serializes a map of {@link HdmiPortConfig} entries back into {@code SharedPreferences} string format.
     */
    public static String serializeHdmiPortRolesSpec(java.util.Map<Integer, HdmiPortConfig> configs) {
        if (configs == null || configs.isEmpty()) return "";
        List<Integer> ports = new ArrayList<>(configs.keySet());
        Collections.sort(ports);
        StringBuilder sb = new StringBuilder();
        for (Integer p : ports) {
            HdmiPortConfig cfg = configs.get(p);
            if (cfg == null || cfg.role == HdmiRole.AUTO) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(cfg.portNumber).append(":").append(cfg.role.name().toLowerCase(java.util.Locale.ROOT));
            if (!cfg.deviceName.isEmpty()) {
                sb.append(":").append(cfg.deviceName.replace(",", " "));
            }
        }
        return sb.toString();
    }

    /**
     * Loads the active per-TV HDMI port configurations (from user {@code SharedPreferences}, falling back to {@link Config#HDMI_PORT_ROLES}).
     */
    public static java.util.Map<Integer, HdmiPortConfig> getHdmiPortConfigs(Context context) {
        String raw = "";
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (sp.contains(KEY_HDMI_PORT_ROLES)) {
                raw = sp.getString(KEY_HDMI_PORT_ROLES, "");
            } else if (Config.HDMI_PORT_ROLES != null && !Config.HDMI_PORT_ROLES.trim().isEmpty()) {
                raw = Config.HDMI_PORT_ROLES.trim();
            }
        } else if (Config.HDMI_PORT_ROLES != null) {
            raw = Config.HDMI_PORT_ROLES.trim();
        }
        return parseHdmiPortRolesSpec(raw);
    }

    /**
     * Saves an HDMI port's role and friendly device name in {@code SharedPreferences} for this TV.
     */
    public static void setHdmiPortRole(Context context, int portNumber, HdmiRole role, String deviceName) {
        if (context == null || portNumber < 1 || portNumber > 8) return;
        java.util.Map<Integer, HdmiPortConfig> current = new java.util.HashMap<>(getHdmiPortConfigs(context));
        if (role == null || role == HdmiRole.AUTO) {
            current.remove(portNumber);
        } else {
            current.put(portNumber, new HdmiPortConfig(portNumber, role, deviceName));
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HDMI_PORT_ROLES, serializeHdmiPortRolesSpec(current))
                .apply();
    }

    /**
     * Returns the user-assigned device label for an HDMI port (e.g., {@code "Mecool"}, {@code "Apple TV"}, {@code "PlayStation 5"}), or {@code ""} if unset/free/laptop.
     */
    public static String getHdmiPortCustomLabel(Context context, int portNumber) {
        HdmiPortConfig cfg = getHdmiPortConfigs(context).get(portNumber);
        if (cfg == null || cfg.deviceName.isEmpty()) return "";
        if (cfg.role == HdmiRole.FREE) return "";
        return cfg.deviceName;
    }

    private static int parsePortNumber(HomeApps.Item in) {
        if (in == null || in.number == null) return 0;
        try {
            return Integer.parseInt(in.number.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HdmiRole effectiveRoleForInput(HomeApps.Item in, java.util.Map<Integer, HdmiPortConfig> portConfigs) {
        int port = parsePortNumber(in);
        if (portConfigs != null && portConfigs.containsKey(port)) {
            HdmiRole configured = portConfigs.get(port).role;
            if (configured != null && configured != HdmiRole.AUTO) {
                return configured;
            }
        }
        return classifyLabelAsRole(in != null ? in.label : "");
    }

    /**
     * Resolves the TV HDMI input to switch to ONLY when the Laptop itself confirms an HDMI cable is connected.
     *
     * <p><b>Why:</b> The user normally plays wirelessly via Moonlight and keeps media streamers (e.g.
     * Mecool on HDMI 1, Apple TV on HDMI 2) permanently connected to the TV. If {@code laptopReportsHdmiConnected}
     * is {@code false}, this method MUST return {@code null} so the TV connects wirelessly via Moonlight.
     * If {@code laptopReportsHdmiConnected} is {@code true} (laptop was connected via HDMI cable), it picks
     * the Laptop / Free HDMI port (never a Streamer or Console port).
     */
    public static HomeApps.Item findLaptopHdmiInput(
            List<HomeApps.Item> inputs,
            java.util.Map<Integer, HdmiPortConfig> portConfigs,
            boolean laptopReportsHdmiConnected
    ) {
        if (!laptopReportsHdmiConnected || inputs == null) {
            return null;
        }
        HomeApps.Item configuredLaptopPort = null;
        HomeApps.Item connectedNonStreamer = null;
        for (HomeApps.Item in : inputs) {
            if (in == null || in.kind != HomeApps.INPUT) continue;
            HdmiRole role = effectiveRoleForInput(in, portConfigs);
            if (role == HdmiRole.STREAMER || role == HdmiRole.CONSOLE) {
                continue;
            }
            if (role == HdmiRole.LAPTOP || role == HdmiRole.FREE) {
                if (in.connected) {
                    return in;
                }
                if (configuredLaptopPort == null) {
                    configuredLaptopPort = in;
                }
            } else if (in.connected && connectedNonStreamer == null) {
                connectedNonStreamer = in;
            }
        }
        return connectedNonStreamer != null ? connectedNonStreamer : configuredLaptopPort;
    }

    /**
     * Detects all permanent Gaming Consoles (PlayStation, Sony, Xbox, Nintendo Switch, Wii, Steam Deck)
     * connected or assigned to the TV's HDMI ports.
     */
    public static List<HomeApps.Item> findConsoleHdmiInputs(
            List<HomeApps.Item> inputs,
            java.util.Map<Integer, HdmiPortConfig> portConfigs
    ) {
        List<HomeApps.Item> consoles = new ArrayList<>();
        if (inputs == null) return consoles;
        for (HomeApps.Item in : inputs) {
            if (in == null || in.kind != HomeApps.INPUT) continue;
            HdmiRole role = effectiveRoleForInput(in, portConfigs);
            if (role == HdmiRole.CONSOLE) {
                consoles.add(in);
            }
        }
        return consoles;
    }

    /**
     * Returns all permanent Gaming Consoles currently known on this TV (via HDMI-CEC or HDMI Ports Setup).
     */
    public static List<HomeApps.Item> getConnectedConsoles(Context context) {
        if (context == null) return Collections.emptyList();
        return findConsoleHdmiInputs(HomeApps.allInputs(context), getHdmiPortConfigs(context));
    }

    /**
     * Opens an interactive dialog allowing any user to inspect or assign what is connected to each HDMI port
     * (Auto-Detect via HDMI-CEC, PlayStation/Xbox/Switch/Wii Console, Gaming Laptop, Media Streamer, or Free).
     */
    public static void showHdmiPortsSetupDialog(final Activity activity, final Runnable onUpdated) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final List<HomeApps.Item> allInputs = HomeApps.allInputs(activity);
        final List<HomeApps.Item> hdmiInputs = new ArrayList<>();
        for (HomeApps.Item in : allInputs) {
            if (in != null && in.kind == HomeApps.INPUT) {
                hdmiInputs.add(in);
            }
        }
        if (hdmiInputs.isEmpty()) {
            Toast.makeText(activity, "No HDMI ports reported by TV hardware.", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.Map<Integer, HdmiPortConfig> configs = getHdmiPortConfigs(activity);
        String[] rows = new String[hdmiInputs.size()];
        for (int i = 0; i < hdmiInputs.size(); i++) {
            HomeApps.Item in = hdmiInputs.get(i);
            int port = parsePortNumber(in);
            HdmiRole role = effectiveRoleForInput(in, configs);
            HdmiPortConfig cfg = configs.get(port);
            String roleDesc;
            switch (role) {
                case CONSOLE:
                    roleDesc = "Gaming Console" + (cfg != null && !cfg.deviceName.isEmpty() ? " (" + cfg.deviceName + ")" : "");
                    break;
                case LAPTOP:
                    roleDesc = "Laptop / PC HDMI";
                    break;
                case STREAMER:
                    roleDesc = "Media Streamer" + (cfg != null && !cfg.deviceName.isEmpty() ? " (" + cfg.deviceName + ")" : "");
                    break;
                case FREE:
                    roleDesc = "Free (Available for Laptop HDMI)";
                    break;
                default:
                    roleDesc = "Auto-Detect (HDMI-CEC: " + in.label + ")";
                    break;
            }
            String sig = in.connected ? "Active Signal" : "Standby / Unplugged";
            rows[i] = "HDMI " + port + " · " + roleDesc + " [" + sig + "]";
        }

        HomeView hv = HomeView.findFrom(activity, null);
        if (hv != null) {
            hv.openLiquidGlassChoiceModal(
                    "HDMI Ports Setup",
                    "Assign Consoles, Media Streamers, or Gaming PC to each HDMI port",
                    rows,
                    null,
                    "Done",
                    which -> {
                        if (which >= 0 && which < hdmiInputs.size()) {
                            int port = parsePortNumber(hdmiInputs.get(which));
                            showSingleHdmiPortRolePicker(activity, port, onUpdated);
                        }
                    },
                    null
            );
        }
    }

    private static void showSingleHdmiPortRolePicker(
            final Activity activity,
            final int portNumber,
            final Runnable onUpdated
    ) {
        final String[] labels = {
                "Auto-Detect via HDMI-CEC (Default)",
                "Gaming Laptop / PC (Use when HDMI cable plugged in)",
                "PlayStation 5 / PlayStation 4 (Console)",
                "Xbox Series X|S / Xbox One (Console)",
                "Nintendo Switch / Wii (Console)",
                "Media Streamer / TV Box (Apple TV, Mecool, Chromecast, Shield)",
                "Free / Unassigned Port"
        };
        final HdmiRole[] roles = {
                HdmiRole.AUTO,
                HdmiRole.LAPTOP,
                HdmiRole.CONSOLE,
                HdmiRole.CONSOLE,
                HdmiRole.CONSOLE,
                HdmiRole.STREAMER,
                HdmiRole.FREE
        };
        final String[] names = {
                "",
                "Laptop",
                "PlayStation",
                "Xbox",
                "Nintendo Console",
                "Media Streamer",
                ""
        };
        HomeView hv = HomeView.findFrom(activity, null);
        if (hv != null) {
            hv.openLiquidGlassChoiceModal(
                    "Assign HDMI " + portNumber,
                    "Choose the device connected to HDMI " + portNumber,
                    labels,
                    null,
                    "Back",
                    idx -> {
                        if (idx >= 0 && idx < roles.length) {
                            setHdmiPortRole(activity, portNumber, roles[idx], names[idx]);
                            if (onUpdated != null) onUpdated.run();
                            showHdmiPortsSetupDialog(activity, onUpdated);
                        }
                    },
                    () -> showHdmiPortsSetupDialog(activity, onUpdated)
            );
        }
    }

    /**
     * Builds a 102-byte IEEE 802.3 Wake-on-LAN magic packet for the given MAC address string.
     *
     * @param macAddress MAC address in {@code XX:XX:XX:XX:XX:XX} or {@code XX-XX-XX-XX-XX-XX} format.
     * @return 102-byte magic packet, or {@code null} if the MAC address is invalid.
     */
    public static byte[] buildMagicPacket(String macAddress) {
        if (macAddress == null) return null;
        String clean = macAddress.trim().replace("-", ":").replace(".", ":");
        String[] hex = clean.split(":");
        if (hex.length != 6) return null;
        byte[] macBytes = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                int val = Integer.parseInt(hex[i], 16);
                if (val < 0 || val > 255) return null;
                macBytes[i] = (byte) val;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int i = 6; i < packet.length; i += 6) {
            System.arraycopy(macBytes, 0, packet, i, 6);
        }
        return packet;
    }

    /**
     * Sends Wake-on-LAN magic packets asynchronously on the local network to both `255.255.255.255`
     * and every active Wi-Fi/Ethernet subnet broadcast address (`x.x.x.255`) so routers that renumbered
     * subnets still deliver the magic frame reliably.
     *
     * @param macAddress Target PC MAC address (falls back to {@link Config#GAMING_PC_MAC}).
     */
    public static void sendWakeOnLanAsync(String macAddress) {
        sendWakeOnLanAsync(null, macAddress);
    }

    /**
     * Sends Wake-on-LAN magic packets for the configured MAC plus all auto-learned PC adapter MACs
     * (`KEY_CACHED_PC_MACS`) across limited and directed LAN broadcast addresses.
     */
    public static void sendWakeOnLanAsync(final Context context, final String macAddress) {
        final Set<String> candidateMacs = new java.util.LinkedHashSet<>();
        if (macAddress != null && !macAddress.trim().isEmpty()) {
            candidateMacs.add(macAddress.trim());
        }
        if (Config.GAMING_PC_MAC != null && !Config.GAMING_PC_MAC.trim().isEmpty()) {
            candidateMacs.add(Config.GAMING_PC_MAC.trim());
        }
        candidateMacs.addAll(getCachedPcMacs(context));

        final List<byte[]> packets = new ArrayList<>();
        for (String m : candidateMacs) {
            byte[] magic = buildMagicPacket(m);
            if (magic != null) {
                packets.add(magic);
            }
        }
        if (packets.isEmpty()) return;

        GAMING_RPC_IO.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                List<InetAddress> broadcastTargets = collectLanBroadcastAddresses();
                for (byte[] magic : packets) {
                    for (InetAddress bcast : broadcastTargets) {
                        try {
                            socket.send(new DatagramPacket(magic, magic.length, bcast, 9));
                            socket.send(new DatagramPacket(magic, magic.length, bcast, 7));
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                AppLog.w("GamingZone WoL failed: " + e.getMessage());
            }
        });
    }

    /**
     * Returns all normalized PC network adapter MAC addresses auto-learned from authenticated `/api/gaming/status`.
     */
    public static List<String> getCachedPcMacs(Context context) {
        if (context == null) return Collections.emptyList();
        try {
            String raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CACHED_PC_MACS, "");
            if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (String item : raw.split(",")) {
                String clean = item.trim().toUpperCase(java.util.Locale.ROOT).replace("-", ":");
                if (buildMagicPacket(clean) != null && !out.contains(clean)) {
                    out.add(clean);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static void saveCachedPcMacs(Context context, Collection<String> macs) {
        if (context == null || macs == null || macs.isEmpty()) return;
        try {
            List<String> merged = new ArrayList<>(getCachedPcMacs(context));
            boolean changed = false;
            for (String m : macs) {
                if (m == null) continue;
                String clean = m.trim().toUpperCase(java.util.Locale.ROOT).replace("-", ":");
                if (buildMagicPacket(clean) != null && !merged.contains(clean)) {
                    merged.add(clean);
                    changed = true;
                }
            }
            if (changed) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CACHED_PC_MACS, String.join(",", merged))
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<InetAddress> collectLanBroadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            InetAddress globalBcast = InetAddress.getByName("255.255.255.255");
            out.add(globalBcast);
            seen.add("255.255.255.255");
        } catch (IOException ignored) {
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface nif = ifaces.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (ia == null) continue;
                    InetAddress addr = ia.getAddress();
                    InetAddress bcast = ia.getBroadcast();
                    if (addr instanceof java.net.Inet4Address && bcast != null) {
                        String hostAddr = bcast.getHostAddress();
                        if (hostAddr != null && seen.add(hostAddr)) {
                            out.add(bcast);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<String> collectActiveTvIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface nif = ifaces.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase(java.util.Locale.ROOT) : "";
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")) continue;
                java.util.Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && !ips.contains(ip)) {
                            ips.add(ip);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ips;
    }

    private static String ipv4Slash24Prefix(String ip) {
        if (ip == null) return "";
        String clean = ip.trim();
        String[] parts = clean.split("\\.");
        if (parts.length != 4) return "";
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return "";
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return "";
            }
        }
        return parts[0] + "." + parts[1] + "." + parts[2] + ".";
    }

    private static boolean isOnActiveTvSubnet(String targetIp) {
        String targetPrefix = ipv4Slash24Prefix(targetIp);
        if (targetPrefix.isEmpty()) return false;
        List<String> tvIps = collectActiveTvIpv4Addresses();
        if (tvIps.isEmpty()) return true;
        for (String tvIp : tvIps) {
            if (targetPrefix.equals(ipv4Slash24Prefix(tvIp))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences gamingPrefs(Context context) {
        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
    }

    private static String getCachedSunshineUuid(Context context) {
        if (context == null) return "";
        try {
            String u = gamingPrefs(context)
                    .getString(KEY_CACHED_SUNSHINE_UUID, "");
            return u != null ? u.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Verifies whether {@code host:port} is the user's genuine Gaming PC Laptop Agent (`:8485`).
     *
     * <p><b>Why:</b> When Wi-Fi drops, a router reboots, or the PC restarts and receives a new DHCP IP,
     * SoarTV must never blindly trust an unverified LAN responder or overwrite `KEY_LAPTOP_IP` with an imposter.
     * When a paired `gaming_pc_token` exists on the TV, the candidate MUST prove knowledge of that token
     * (`authenticated == true` on `GET /api/gaming/status` with `X-SoarTV-Token`).
     */
    public enum EndpointProbeStatus {
        REACHABLE_AUTHENTICATED,
        REACHABLE_UNPAIRED,
        UNREACHABLE
    }

    /**
     * Structured outcome from probing a candidate `:8485` Gaming PC endpoint so discovery distinguishes
     * a genuine SoarTV Laptop Gaming Agent awaiting 6-digit pairing from unreachable or imposter hosts.
     */
    public static final class ProbeResult {
        public final EndpointProbeStatus status;
        public final String host;
        public final int port;
        public final String sunshineHostUuid;
        public final String sunshineHostName;

        public ProbeResult(
                EndpointProbeStatus status,
                String host,
                int port,
                String sunshineHostUuid,
                String sunshineHostName) {
            this.status = status != null ? status : EndpointProbeStatus.UNREACHABLE;
            this.host = host != null ? host : "";
            this.port = port;
            this.sunshineHostUuid = sunshineHostUuid != null ? sunshineHostUuid : "";
            this.sunshineHostName = sunshineHostName != null ? sunshineHostName : "";
        }

        public boolean isReachable() {
            return status != EndpointProbeStatus.UNREACHABLE;
        }

        public boolean isAuthenticated() {
            return status == EndpointProbeStatus.REACHABLE_AUTHENTICATED;
        }

        public boolean isUnpaired() {
            return status == EndpointProbeStatus.REACHABLE_UNPAIRED;
        }
    }

    public static void recordReachableUnpairedPcIp(Context context, String ip) {
        String clean = ip != null ? ip.trim() : "";
        LAST_REACHABLE_UNPAIRED_PC_IP = clean;
        if (context != null) {
            try {
                SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit().putString(KEY_UNPAIRED_PC_IP, clean).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String getReachableUnpairedPcIp(Context context) {
        String mem = LAST_REACHABLE_UNPAIRED_PC_IP;
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        if (context != null) {
            try {
                String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_UNPAIRED_PC_IP, "");
                return saved != null ? saved.trim() : "";
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    public static ProbeResult probeCandidateGamingPcEndpoint(
            Context context,
            String host,
            int port,
            int timeoutMs) {
        return probeCandidateGamingPcEndpoint(
                context, host, port, timeoutMs, Math.max(SUBNET_VERIFY_TIMEOUT_MS, timeoutMs));
    }

    public static ProbeResult probeCandidateGamingPcEndpoint(
            Context context,
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs) {
        if (!SunshineStreamClient.isAllowedCleartextLanHost(host)) {
            return new ProbeResult(EndpointProbeStatus.UNREACHABLE, host, port, "", "");
        }
        String cleanHost = host.trim();
        int targetPort = port > 0 ? port : LAPTOP_AGENT_PORT;
        try {
            HttpResult res = rawHttpRequestWithStatus(
                    context,
                    cleanHost,
                    targetPort,
                    "GET",
                    "/api/gaming/status",
                    null,
                    Math.max(80, connectTimeoutMs),
                    Math.max(200, readTimeoutMs));
            if (!res.isSuccess() || res.body.isEmpty()) {
                return new ProbeResult(EndpointProbeStatus.UNREACHABLE, cleanHost, targetPort, "", "");
            }
            Object parsed = RemoteAuthManager.MiniJson.parse(res.body);
            if (!(parsed instanceof java.util.Map)) {
                return new ProbeResult(EndpointProbeStatus.UNREACHABLE, cleanHost, targetPort, "", "");
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) parsed;

            String serviceId = map.get("agentService") != null ? String.valueOf(map.get("agentService")).trim() : "";
            boolean hasAuthedField = map.containsKey("authenticated");
            boolean isAuthed = Boolean.TRUE.equals(map.get("authenticated"));
            String sunshineUuid = map.get("sunshineHostUuid") != null
                    ? String.valueOf(map.get("sunshineHostUuid")).trim()
                    : "";
            String sunshineHost = map.get("sunshineHostName") != null
                    ? String.valueOf(map.get("sunshineHostName")).trim()
                    : "";

            boolean looksLikeAgent = AGENT_SERVICE_ID.equals(serviceId)
                    || (map.containsKey("sunshineRunning") && map.containsKey("games"));
            if (!looksLikeAgent) {
                return new ProbeResult(EndpointProbeStatus.UNREACHABLE, cleanHost, targetPort, "", "");
            }

            String pairedToken = getLaptopAuthToken(context);
            if (!pairedToken.isEmpty()) {
                if (hasAuthedField) {
                    if (!isAuthed) {
                        recordReachableUnpairedPcIp(context, cleanHost);
                        if (context != null && (!sunshineUuid.isEmpty() || !sunshineHost.isEmpty())) {
                            saveSunshineHostIdentity(context, sunshineUuid, sunshineHost);
                        }
                        return new ProbeResult(
                                EndpointProbeStatus.REACHABLE_UNPAIRED,
                                cleanHost,
                                targetPort,
                                sunshineUuid,
                                sunshineHost);
                    }
                } else {
                    String expectedUuid = getCachedSunshineUuid(context);
                    if (expectedUuid.isEmpty() || !expectedUuid.equalsIgnoreCase(sunshineUuid)) {
                        return new ProbeResult(
                                EndpointProbeStatus.UNREACHABLE,
                                cleanHost,
                                targetPort,
                                sunshineUuid,
                                sunshineHost);
                    }
                }
            }

            if (context != null) {
                saveSunshineHostIdentity(context, sunshineUuid, sunshineHost);
                Object macObj = map.get("macAddresses");
                if (macObj instanceof List) {
                    List<String> macs = new ArrayList<>();
                    for (Object item : (List<?>) macObj) {
                        if (item != null) macs.add(String.valueOf(item));
                    }
                    saveCachedPcMacs(context, macs);
                }
            }
            recordReachableUnpairedPcIp(context, "");
            return new ProbeResult(
                    EndpointProbeStatus.REACHABLE_AUTHENTICATED,
                    cleanHost,
                    targetPort,
                    sunshineUuid,
                    sunshineHost);
        } catch (Exception ignored) {
            return new ProbeResult(EndpointProbeStatus.UNREACHABLE, cleanHost, targetPort, "", "");
        }
    }

    public static boolean verifyCandidateGamingPcEndpoint(
            Context context,
            String host,
            int port,
            int timeoutMs) {
        return probeCandidateGamingPcEndpoint(context, host, port, timeoutMs).isAuthenticated();
    }

    public static boolean verifyCandidateGamingPcEndpoint(
            Context context,
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs) {
        return probeCandidateGamingPcEndpoint(
                context, host, port, connectTimeoutMs, readTimeoutMs).isAuthenticated();
    }

    /**
     * Broadcasts `SOARTV_GAMING_DISCOVER_V1` over UDP to {@code targets}, collects `SOARTV_GAMING_HERE_V1`
     * replies within {@code waitMs}, and verifies each candidate over TCP (`probeCandidateGamingPcEndpoint`).
     */
    public static String discoverLaptopViaUdpTargetsSync(
            Context context,
            List<InetSocketAddress> targets,
            int verifyTcpPort,
            int waitMs) {
        if (targets == null || targets.isEmpty()) return "";
        byte[] probeBytes = ("{\"op\":\"" + UDP_DISCOVER_OP + "\"}").getBytes(StandardCharsets.UTF_8);
        Set<String> checkedEndpoints = new HashSet<>();
        String firstUnpairedIp = "";

        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true);
            for (InetSocketAddress target : targets) {
                if (target == null) continue;
                try {
                    InetAddress addr = target.getAddress() != null
                            ? target.getAddress()
                            : InetAddress.getByName(target.getHostString());
                    udp.send(new DatagramPacket(probeBytes, probeBytes.length, addr, target.getPort()));
                } catch (IOException ignored) {
                }
            }

            long deadline = System.currentTimeMillis() + Math.max(80, waitMs);
            byte[] recvBuf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) Math.max(25L, deadline - System.currentTimeMillis());
                udp.setSoTimeout(remaining);
                DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    udp.receive(pkt);
                } catch (IOException timeoutOrClosed) {
                    break;
                }
                if (pkt.getAddress() == null) continue;
                String responderIp = pkt.getAddress().getHostAddress();
                if (!SunshineStreamClient.isAllowedCleartextLanHost(responderIp)) continue;

                String payload = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                Object parsed = RemoteAuthManager.MiniJson.parse(payload);
                if (!(parsed instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) parsed;
                String op = map.get("op") != null ? String.valueOf(map.get("op")) : "";
                String svc = map.get("agentService") != null ? String.valueOf(map.get("agentService")) : "";
                if (!UDP_HERE_OP.equals(op) || !AGENT_SERVICE_ID.equals(svc)) {
                    continue;
                }

                int reportedPort = LAPTOP_AGENT_PORT;
                Object portObj = map.get("port");
                if (portObj instanceof Number) {
                    reportedPort = ((Number) portObj).intValue();
                } else if (portObj != null) {
                    try {
                        reportedPort = Integer.parseInt(String.valueOf(portObj).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                int candidatePort = reportedPort > 0
                        ? reportedPort
                        : (verifyTcpPort > 0 ? verifyTcpPort : LAPTOP_AGENT_PORT);
                String endpointKey = responderIp + ":" + candidatePort;
                if (!checkedEndpoints.add(endpointKey)) continue;

                ProbeResult probe = probeCandidateGamingPcEndpoint(
                        context, responderIp, candidatePort, Math.max(SUBNET_VERIFY_TIMEOUT_MS, waitMs));
                if (probe.isAuthenticated()) {
                    if (context != null) {
                        setLaptopIp(context, responderIp);
                    }
                    return responderIp;
                }
                if (probe.isUnpaired() && firstUnpairedIp.isEmpty()) {
                    firstUnpairedIp = responderIp;
                }
            }
        } catch (Exception ignored) {
        }
        if (!firstUnpairedIp.isEmpty()) {
            recordReachableUnpairedPcIp(context, firstUnpairedIp);
        }
        return "";
    }

    /**
     * Scans {@code candidateIps} in parallel across bounded daemon threads, checking `:8485` (`X-SoarTV-Token`
     * verification) and Sunshine `:47989/serverinfo` (matching `expectedSunshineUuid` when cached, or adopting
     * a valid `:47989/serverinfo` `<uniqueid>` when no UUID is cached yet).
     */
    public static String scanCandidatesForGamingPcSync(
            final Context context,
            List<String> candidateIps,
            final int agentPort,
            final int sunshineHttpPort,
            final String expectedSunshineUuid,
            final int connectTimeoutMs) {
        if (candidateIps == null || candidateIps.isEmpty()) return "";
        final java.util.concurrent.atomic.AtomicReference<String> winner =
                new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<String> sunshineFallbackWinner =
                new java.util.concurrent.atomic.AtomicReference<>("");
        final java.util.concurrent.atomic.AtomicReference<String> unpairedWinner =
                new java.util.concurrent.atomic.AtomicReference<>("");
        final String cleanExpectedUuid = expectedSunshineUuid != null ? expectedSunshineUuid.trim() : "";
        final int timeout = Math.max(80, connectTimeoutMs);
        int poolSize = Math.max(1, Math.min(SUBNET_SCAN_THREADS, candidateIps.size()));

        java.util.concurrent.ExecutorService scanPool = java.util.concurrent.Executors.newFixedThreadPool(
                poolSize,
                r -> {
                    Thread t = new Thread(r, "gaming-lan-scan");
                    t.setDaemon(true);
                    return t;
                });
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(candidateIps.size());

        for (final String rawIp : candidateIps) {
            scanPool.execute(() -> {
                try {
                    if (!winner.get().isEmpty() || rawIp == null) return;
                    String ip = rawIp.trim();
                    if (!SunshineStreamClient.isAllowedCleartextLanHost(ip)) return;

                    if (agentPort > 0) {
                        ProbeResult probe = probeCandidateGamingPcEndpoint(
                                context, ip, agentPort, timeout, Math.max(SUBNET_VERIFY_TIMEOUT_MS, timeout * 2));
                        if (probe.isAuthenticated()) {
                            winner.compareAndSet("", ip);
                            return;
                        }
                        if (probe.isUnpaired()) {
                            unpairedWinner.compareAndSet("", ip);
                        }
                    }

                    if (winner.get().isEmpty() && sunshineHttpPort > 0) {
                        try {
                            String xml = SunshineStreamClient.fetchServerInfoUnauthenticatedXml(
                                    ip, sunshineHttpPort, timeout, Math.max(SUBNET_VERIFY_TIMEOUT_MS, timeout * 2));
                            String statusCode = SunshineStreamClient.extractXmlStatusCode(xml);
                            String remoteUuid = SunshineStreamClient.extractXmlTag(xml, "uniqueid");
                            if ((statusCode.isEmpty() || "200".equals(statusCode)) && !remoteUuid.isEmpty()) {
                                if (!cleanExpectedUuid.isEmpty()) {
                                    if (cleanExpectedUuid.equalsIgnoreCase(remoteUuid)) {
                                        winner.compareAndSet("", ip);
                                    }
                                } else {
                                    if (context != null) {
                                        saveSunshineHostIdentity(
                                                context,
                                                remoteUuid,
                                                SunshineStreamClient.extractXmlTag(xml, "hostname"));
                                    }
                                    if (agentPort <= 0) {
                                        winner.compareAndSet("", ip);
                                    } else {
                                        sunshineFallbackWinner.compareAndSet("", ip);
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            long waitDeadline = System.currentTimeMillis() + 6000L;
            while (latch.getCount() > 0 && winner.get().isEmpty() && System.currentTimeMillis() < waitDeadline) {
                if (latch.await(35L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            scanPool.shutdownNow();
        }

        String found = winner.get();
        if (found.isEmpty() && !sunshineFallbackWinner.get().isEmpty()) {
            found = sunshineFallbackWinner.get();
        }
        if (found.isEmpty() && !unpairedWinner.get().isEmpty()) {
            recordReachableUnpairedPcIp(context, unpairedWinner.get());
        }
        if (!found.isEmpty() && context != null) {
            setLaptopIp(context, found);
        }
        return found;
    }

    private static boolean isTcpPortReachable(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Discovers the user's Gaming PC across Wi-Fi drops, router DHCP lease changes, subnet renumbering,
     * and PC restarts using a two-layer zero-config LAN protocol:
     * 1. Fast UDP broadcast (`SOARTV_GAMING_DISCOVER_V1` -> `~15ms` on LAN) verified via `X-SoarTV-Token`.
     * 2. Parallel active-subnet TCP scan (`:8485` Agent + `:47989` Sunshine `uniqueid`, `~180ms`).
     */
    public static String discoverLaptopIpOnLanSync(Context context) {
        synchronized (DISCOVERY_LOCK) {
            List<InetSocketAddress> udpTargets = new ArrayList<>();
            for (InetAddress bcast : collectLanBroadcastAddresses()) {
                udpTargets.add(new InetSocketAddress(bcast, LAPTOP_AGENT_PORT));
            }
            String viaUdp = discoverLaptopViaUdpTargetsSync(
                    context, udpTargets, LAPTOP_AGENT_PORT, UDP_DISCOVERY_WAIT_MS);
            if (!viaUdp.isEmpty()) {
                AppLog.i("GamingZone: Discovered Gaming PC via UDP broadcast at " + viaUdp);
                return viaUdp;
            }

            List<String> tvIps = collectActiveTvIpv4Addresses();
            Set<String> tvIpSet = new HashSet<>(tvIps);
            java.util.LinkedHashSet<String> prefixes = new java.util.LinkedHashSet<>();
            for (String tvIp : tvIps) {
                String p = ipv4Slash24Prefix(tvIp);
                if (!p.isEmpty()) prefixes.add(p);
            }
            String configuredIp = getLaptopIp(context);
            String configuredPrefix = ipv4Slash24Prefix(configuredIp);
            if (!configuredPrefix.isEmpty()) {
                prefixes.add(configuredPrefix);
            }

            int preferredLastOctet = -1;
            String[] cfgParts = configuredIp.split("\\.");
            if (cfgParts.length == 4) {
                try {
                    preferredLastOctet = Integer.parseInt(cfgParts[3]);
                } catch (NumberFormatException ignored) {
                }
            }

            List<String> candidates = new ArrayList<>(prefixes.size() * 253);
            Set<String> seenCandidates = new HashSet<>();
            for (String prefix : prefixes) {
                if (preferredLastOctet >= 2 && preferredLastOctet <= 254) {
                    String prio = prefix + preferredLastOctet;
                    if (!tvIpSet.contains(prio) && seenCandidates.add(prio)) {
                        candidates.add(prio);
                    }
                }
                for (int host = 2; host <= 254; host++) {
                    String cand = prefix + host;
                    if (!tvIpSet.contains(cand) && seenCandidates.add(cand)) {
                        candidates.add(cand);
                    }
                }
            }

            String expectedUuid = getCachedSunshineUuid(context);
            String viaScan = scanCandidatesForGamingPcSync(
                    context,
                    candidates,
                    LAPTOP_AGENT_PORT,
                    SunshineStreamClient.SUNSHINE_HTTP_PORT,
                    expectedUuid,
                    SUBNET_SCAN_CONNECT_TIMEOUT_MS);
            if (!viaScan.isEmpty()) {
                AppLog.i("GamingZone: Discovered Gaming PC via active subnet scan at " + viaScan);
                return viaScan;
            }
            return "";
        }
    }

    /**
     * Probes the currently saved `getLaptopIp(context)` first (failing fast in `240ms` if the TV's Wi-Fi
     * moved to a new `/24` subnet), and automatically runs `discoverLaptopIpOnLanSync` if unreachable.
     */
    public static String resolveReachableLaptopIpSync(Context context, int fastTimeoutMs) {
        String currentIp = getLaptopIp(context);
        int probeTimeout = isOnActiveTvSubnet(currentIp)
                ? Math.max(200, fastTimeoutMs)
                : Math.min(Math.max(150, fastTimeoutMs), FAST_CROSS_SUBNET_PROBE_MS);

        ProbeResult direct = probeCandidateGamingPcEndpoint(
                context, currentIp, LAPTOP_AGENT_PORT, probeTimeout, Math.max(SUBNET_VERIFY_TIMEOUT_MS, probeTimeout * 2));
        if (direct.isAuthenticated()) {
            return currentIp;
        }
        String expectedUuid = getCachedSunshineUuid(context);
        try {
            String xml = SunshineStreamClient.fetchServerInfoUnauthenticatedXml(
                    currentIp,
                    SunshineStreamClient.SUNSHINE_HTTP_PORT,
                    probeTimeout,
                    Math.max(SUBNET_VERIFY_TIMEOUT_MS, probeTimeout * 2));
            String statusCode = SunshineStreamClient.extractXmlStatusCode(xml);
            String remoteUuid = SunshineStreamClient.extractXmlTag(xml, "uniqueid");
            if ((statusCode.isEmpty() || "200".equals(statusCode)) && !remoteUuid.isEmpty()) {
                if (!expectedUuid.isEmpty()) {
                    if (expectedUuid.equalsIgnoreCase(remoteUuid)) {
                        return currentIp;
                    }
                } else {
                    if (context != null) {
                        saveSunshineHostIdentity(
                                context,
                                remoteUuid,
                                SunshineStreamClient.extractXmlTag(xml, "hostname"));
                    }
                    return currentIp;
                }
            }
        } catch (IOException ignored) {
        }

        String discovered = discoverLaptopIpOnLanSync(context);
        if (!discovered.isEmpty()) {
            reportGamingHostToCloudAsync(context, discovered);
            return discovered;
        }
        // Cloud Rendezvous fallback (survives cross-subnet moves & UDP-blocking mesh routers)
        String cloudIp = resolveCloudGamingHostIpSync(context, currentIp);
        if (!cloudIp.isEmpty()) {
            setLaptopIp(context, cloudIp);
            return cloudIp;
        }
        return currentIp;
    }

    /**
     * Queries {@code public.gaming_hosts} in Supabase for a paired Gaming PC's latest LAN IP & MACs
     * when local UDP/subnet discovery did not find it (e.g. mesh AP isolation or cross-subnet change).
     */
    static String resolveCloudGamingHostIpSync(Context context, String skipIp) {
        if (context == null) return "";
        try {
            SupabaseClient sb = SupabaseClient.get(context);
            List<SupabaseClient.GamingHostRecord> hosts = sb.isConfigured()
                    ? sb.fetchGamingHostsSync()
                    : sb.getCachedGamingHosts();
            if (hosts == null || hosts.isEmpty()) return "";
            adoptCloudGamingHosts(context, hosts);
            for (SupabaseClient.GamingHostRecord h : hosts) {
                String ip = h.lanIp == null ? "" : h.lanIp.trim();
                if (ip.isEmpty() || ip.equals(skipIp)) continue;
                if (!SunshineStreamClient.isAllowedCleartextLanHost(ip)) continue;
                if (verifyCandidateGamingPcEndpoint(context, ip, h.agentPort, 900)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Adopts Gaming PC MAC addresses, Sunshine UUID, and last-seen LAN IP from Supabase {@code gaming_hosts}
     * so a new or second TV can immediately wake and pair with the PC without manual IP entry.
     */
    public static void adoptCloudGamingHosts(Context context, List<SupabaseClient.GamingHostRecord> hosts) {
        if (context == null || hosts == null || hosts.isEmpty()) return;
        SupabaseClient.GamingHostRecord primary = hosts.get(0);
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        if (primary.lanIp != null && !primary.lanIp.trim().isEmpty()
                && SunshineStreamClient.isAllowedCleartextLanHost(primary.lanIp.trim())) {
            String cur = sp.getString(KEY_LAPTOP_IP, "").trim();
            if (cur.isEmpty()) {
                ed.putString(KEY_LAPTOP_IP, primary.lanIp.trim());
            }
        }
        if (primary.sunshineUuid != null && !primary.sunshineUuid.trim().isEmpty()) {
            ed.putString(KEY_CACHED_SUNSHINE_UUID, primary.sunshineUuid.trim());
        }
        if (primary.hostname != null && !primary.hostname.trim().isEmpty()) {
            ed.putString(KEY_CACHED_SUNSHINE_HOST, primary.hostname.trim());
        }
        if (primary.macAddresses != null && !primary.macAddresses.isEmpty()) {
            Set<String> mergedMacs = new java.util.LinkedHashSet<>();
            String existing = sp.getString(KEY_CACHED_PC_MACS, "");
            if (existing != null && !existing.isEmpty()) {
                for (String m : existing.split(",")) {
                    String clean = m.trim();
                    if (!clean.isEmpty()) mergedMacs.add(clean);
                }
            }
            for (String m : primary.macAddresses) {
                if (m != null && !m.trim().isEmpty()) mergedMacs.add(m.trim().toUpperCase(java.util.Locale.US));
            }
            ed.putString(KEY_CACHED_PC_MACS, String.join(",", mergedMacs));
        }
        ed.apply();
    }

    /** Reports a verified Gaming PC's fresh LAN IP, MACs, and Sunshine UUID to {@code public.gaming_hosts}. */
    static void reportGamingHostToCloudAsync(Context context, String lanIp) {
        if (context == null || lanIp == null || lanIp.trim().isEmpty()) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final String cleanIp = lanIp.trim();
        GAMING_RPC_IO.execute(() -> {
            try {
                SupabaseClient sb = SupabaseClient.get(appCtx);
                if (!sb.isConfigured()) return;
                SharedPreferences sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String uuid = sp.getString(KEY_CACHED_SUNSHINE_UUID, "").trim();
                String hostname = sp.getString(KEY_CACHED_SUNSHINE_HOST, "").trim();
                String hostId = !uuid.isEmpty() ? uuid : (!hostname.isEmpty() ? hostname.toLowerCase(java.util.Locale.ROOT) : "primary-pc");
                List<String> macs = new ArrayList<>();
                String rawMacs = sp.getString(KEY_CACHED_PC_MACS, "");
                if (rawMacs != null && !rawMacs.isEmpty()) {
                    for (String m : rawMacs.split(",")) {
                        if (!m.trim().isEmpty()) macs.add(m.trim());
                    }
                }
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("host_id", hostId);
                payload.put("hostname", hostname.isEmpty() ? hostId : hostname);
                payload.put("lan_ip", cleanIp);
                payload.put("agent_port", LAPTOP_AGENT_PORT);
                payload.put("mac_addresses", macs);
                payload.put("sunshine_uuid", uuid);
                payload.put("last_seen_at", SupabaseClient.isoNowUtc());
                sb.upsertGamingHostSync(payload);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Handles an inbound `POST /api/gaming/pc-announce` push beacon from the Gaming PC when it boots
     * or changes DHCP IP: asynchronously verifies {@code candidateIp} via `X-SoarTV-Token` / Sunshine UUID
     * before adopting it in `SharedPreferences`.
     */
    public static void verifyAndAdoptCandidatePcIpAsync(final Context context, final String candidateIp) {
        if (context == null || !SunshineStreamClient.isAllowedCleartextLanHost(candidateIp)) {
            return;
        }
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final String cleanIp = candidateIp.trim();
        GAMING_RPC_IO.execute(() -> {
            if (verifyCandidateGamingPcEndpoint(appCtx, cleanIp, LAPTOP_AGENT_PORT, 1500)) {
                setLaptopIp(appCtx, cleanIp);
                LaptopStatus st = fetchLaptopStatusSync(appCtx);
                saveLaptopStatusToCache(appCtx, st);
                AppLog.i("GamingZone: Adopted verified PC push-beacon IP " + cleanIp);
            }
        });
    }

    /**
     * Resolves the paired Laptop Gaming Agent auth token (`Secrets` first, falling back to `Prefs.gamingPcToken()`).
     * Never logs or exposes the token.
     */
    @SuppressWarnings("deprecation")
    public static String getLaptopAuthToken(Context context) {
        try {
            if (context != null) {
                Secrets.refreshIfDirChanged(context);
                String secretTok = Secrets.get(context, "gaming_pc_token");
                if (secretTok != null && !secretTok.trim().isEmpty()) {
                    return secretTok.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String prefTok = Prefs.gamingPcToken();
            if (prefTok != null && !prefTok.trim().isEmpty()) {
                return prefTok.trim();
            }
            if (context != null) {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                String direct = appCtx.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS)
                        .getString("gamingPcToken", "");
                if (direct != null && !direct.trim().isEmpty()) {
                    return direct.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * Requests a 6-digit pairing code to be displayed on the Gaming PC via `POST /api/gaming/pair/request`.
     */
    public static boolean requestLaptopPairingSync(Context context) {
        try {
            String host = resolveReachableLaptopIpSync(context, FAST_SAME_SUBNET_PROBE_MS);
            String resp = rawHttpRequest(
                    context,
                    host,
                    LAPTOP_AGENT_PORT,
                    "POST",
                    "/api/gaming/pair/request",
                    "{}",
                    4000
            );
            if (resp != null && !resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                return json.optBoolean("ok", !json.has("error"));
            }
        } catch (Exception e) {
            AppLog.w("GamingZone pair request failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Verifies a 6-digit pairing code against `POST /api/gaming/pair/verify` and persists the issued `token`
     * in `Prefs.setGamingPcToken(token)`.
     */
    public static boolean verifyLaptopPairingSync(Context context, String code) {
        if (code == null || code.trim().isEmpty()) return false;
        try {
            JSONObject req = new JSONObject();
            req.put("code", code.trim());
            String host = resolveReachableLaptopIpSync(context, FAST_SAME_SUBNET_PROBE_MS);
            String resp = rawHttpRequest(
                    context,
                    host,
                    LAPTOP_AGENT_PORT,
                    "POST",
                    "/api/gaming/pair/verify",
                    req.toString(),
                    5000
            );
            if (resp != null && !resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                String token = json.optString("token", json.optString("authToken", "")).trim();
                if (!token.isEmpty()) {
                    if (context != null) {
                        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                        appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .edit()
                                .putString("gamingPcToken", token)
                                .commit();
                    }
                    Prefs.setGamingPcToken(token);
                    return true;
                }
            }
        } catch (Exception e) {
            AppLog.w("GamingZone pair verify failed: " + e.getMessage());
        }
        return false;
    }

    /** Returns the configured Laptop LAN IP address. */
    public static String getLaptopIp(Context context) {
        if (context != null) {
            SharedPreferences sp = gamingPrefs(context);
            String override = sp.getString(KEY_LAPTOP_IP, "");
            if (override != null && !override.trim().isEmpty()) {
                return override.trim();
            }
        }
        return Config.GAMING_PC_IP != null && !Config.GAMING_PC_IP.trim().isEmpty()
                ? Config.GAMING_PC_IP.trim()
                : "";
    }

    /** Updates the Laptop LAN IP override in SharedPreferences. */
    public static void setLaptopIp(Context context, String ip) {
        if (context == null || ip == null) return;
        gamingPrefs(context)
                .edit()
                .putString(KEY_LAPTOP_IP, ip.trim())
                .commit();
    }

    /** Returns the set of user-hidden game IDs stored on the TV (excluding permanently hidden games). */
    public static Set<String> getHiddenGameIds(Context context) {
        if (context == null) return Collections.emptySet();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = sp.getStringSet(KEY_HIDDEN_IDS, null);
        Set<String> out = stored != null ? new HashSet<>(stored) : new HashSet<>();
        out.removeAll(getPermanentlyHiddenGameIds(context));
        return out;
    }

    /** Alias returning temporarily hidden game IDs for {@link CloudSyncManager}. */
    public static Set<String> getTemporarilyHiddenGameIds(Context context) {
        return getHiddenGameIds(context);
    }

    /** Replaces local hidden & permanently hidden game IDs from active profile Cloud Sync. */
    public static void importHiddenGameIdsFromCloud(Context context, Set<String> tempHidden, Set<String> permHidden) {
        if (context == null) return;
        Set<String> perm = permHidden == null ? new HashSet<>() : new HashSet<>(permHidden);
        Set<String> temp = tempHidden == null ? new HashSet<>() : new HashSet<>(tempHidden);
        temp.removeAll(perm);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PERM_HIDDEN_IDS, perm)
                .putStringSet(KEY_HIDDEN_IDS, temp)
                .apply();
    }

    /** Returns the set of permanently hidden game IDs (cannot be undone, never shown under the Hidden tile). */
    public static Set<String> getPermanentlyHiddenGameIds(Context context) {
        if (context == null) return Collections.emptySet();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = sp.getStringSet(KEY_PERM_HIDDEN_IDS, null);
        return stored != null ? new HashSet<>(stored) : new HashSet<>();
    }

    /** Permanently hides a game ID (cannot be undone; removes it from temporary hidden list as well). */
    public static void permanentlyHideGame(Context context, String gameId) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) return;
        String cleanId = gameId.trim();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> perm = new HashSet<>(getPermanentlyHiddenGameIds(context));
        perm.add(cleanId);
        Set<String> temp = new HashSet<>(getHiddenGameIds(context));
        temp.remove(cleanId);
        sp.edit()
                .putStringSet(KEY_PERM_HIDDEN_IDS, perm)
                .putStringSet(KEY_HIDDEN_IDS, temp)
                .apply();
        try {
            CloudSyncManager.get(context).recordLocalFieldChange(CloudSyncManager.Scope.PROFILE, "perm_hidden_games");
        } catch (RuntimeException ignored) {
        }
    }

    /** Returns the list of temporarily hidden games (never includes permanently hidden games). */
    public static List<GameItem> getTemporarilyHiddenGames(Context context) {
        Set<String> tempHidden = getHiddenGameIds(context);
        if (tempHidden.isEmpty()) return Collections.emptyList();
        LaptopStatus cached = getCachedLaptopStatus(context);
        List<GameItem> out = new ArrayList<>();
        for (GameItem g : cached.games) {
            if (tempHidden.contains(g.id)) {
                out.add(g);
            }
        }
        return out;
    }

    /** Hides or re-shows a game ID locally and asynchronously notifies the Laptop Gaming Agent. */
    public static void setGameHidden(Context context, String gameId, boolean hidden) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) return;
        String cleanId = gameId.trim();
        Set<String> current = new HashSet<>(getHiddenGameIds(context));
        if (hidden) {
            current.add(cleanId);
        } else {
            current.remove(cleanId);
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit().putStringSet(KEY_HIDDEN_IDS, current);
        String rawGames = sp.getString(KEY_CACHED_GAMES, null);
        if (rawGames != null && !rawGames.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(rawGames);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject g = arr.optJSONObject(i);
                    if (g != null && cleanId.equals(g.optString("id", ""))) {
                        g.put("hidden", hidden);
                    }
                }
                editor.putString(KEY_CACHED_GAMES, arr.toString());
            } catch (JSONException ignored) {
            }
        }
        editor.apply();
        try {
            CloudSyncManager.get(context).recordLocalFieldChange(CloudSyncManager.Scope.PROFILE, "hidden_games");
        } catch (RuntimeException ignored) {
        }

        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        GAMING_RPC_IO.execute(() -> {
            try {
                String ip = resolveReachableLaptopIpSync(appCtx, FAST_SAME_SUBNET_PROBE_MS);
                JSONObject body = new JSONObject();
                body.put("gameId", cleanId);
                body.put("hidden", hidden);
                try {
                    rawHttpRequest(appCtx, ip, LAPTOP_AGENT_PORT, "POST", "/api/gaming/hide", body.toString(), 2000);
                } catch (IOException firstIo) {
                    String refreshed = discoverLaptopIpOnLanSync(appCtx);
                    if (!refreshed.isEmpty() && !refreshed.equals(ip)) {
                        rawHttpRequest(appCtx, refreshed, LAPTOP_AGENT_PORT, "POST", "/api/gaming/hide", body.toString(), 2000);
                    } else {
                        throw firstIo;
                    }
                }
            } catch (Exception e) {
                AppLog.w("GamingZone hide sync failed: " + e.getMessage());
            }
        });
    }

    /**
     * Records a game as played right now in local SharedPreferences so it appears first in "Continue Playing".
     */
    public static void recordGamePlayed(Context context, String gameId) {
        if (context == null || gameId == null || gameId.trim().isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            String raw = sp.getString(KEY_LAST_PLAYED, "{}");
            JSONObject obj = new JSONObject(raw != null && !raw.isEmpty() ? raw : "{}");
            obj.put(gameId.trim(), System.currentTimeMillis());
            sp.edit().putString(KEY_LAST_PLAYED, obj.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /**
     * Reads the map of {@code gameId -> lastPlayedAt} timestamps persisted locally on the TV.
     */
    public static java.util.Map<String, Long> getLastPlayedTimestamps(Context context) {
        java.util.Map<String, Long> map = new java.util.HashMap<>();
        if (context == null) return map;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            String raw = sp.getString(KEY_LAST_PLAYED, "{}");
            if (raw != null && !raw.isEmpty()) {
                JSONObject obj = new JSONObject(raw);
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    long ts = obj.optLong(k, 0L);
                    if (ts > 0L) map.put(k, ts);
                }
            }
        } catch (JSONException ignored) {
        }
        return map;
    }

    /**
     * Returns the locally cached {@link LaptopStatus} immediately (0 ms network wait) for instant Gaming Screen rendering.
     */
    public static LaptopStatus getCachedLaptopStatus(Context context) {
        Set<String> localHidden = getHiddenGameIds(context);
        Set<String> permHidden = getPermanentlyHiddenGameIds(context);
        java.util.Map<String, Long> played = getLastPlayedTimestamps(context);
        boolean awake = false, locked = false, hdmi = false, sunshine = false;
        boolean steamRunning = false, epicRunning = false, heroicRunning = false, sunshinePowerCtrl = false;
        int batteryPercent = -1;
        boolean charging = false, batteryFull = false;
        String sunshineUuid = "";
        String sunshineHost = "";
        int pcDispW = 0, pcDispH = 0, pcDispHz = 0;
        List<GameItem> games = new ArrayList<>();
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            awake = sp.getBoolean(KEY_CACHED_AWAKE, false);
            locked = sp.getBoolean(KEY_CACHED_LOCKED, false);
            hdmi = sp.getBoolean(KEY_CACHED_HDMI, false);
            sunshine = sp.getBoolean(KEY_CACHED_SUNSHINE, false);
            steamRunning = sp.getBoolean(KEY_CACHED_STEAM_RUNNING, false);
            epicRunning = sp.getBoolean(KEY_CACHED_EPIC_RUNNING, false);
            heroicRunning = sp.getBoolean(KEY_CACHED_HEROIC_RUNNING, false);
            sunshinePowerCtrl = sp.getBoolean(KEY_CACHED_SUNSHINE_POWER_CTRL, false);
            batteryPercent = sp.getInt(KEY_CACHED_BATTERY_PERCENT, -1);
            charging = sp.getBoolean(KEY_CACHED_BATTERY_CHARGING, false);
            batteryFull = sp.getBoolean(KEY_CACHED_BATTERY_FULL, false);
            sunshineUuid = sp.getString(KEY_CACHED_SUNSHINE_UUID, "");
            sunshineHost = sp.getString(KEY_CACHED_SUNSHINE_HOST, "");
            pcDispW = sp.getInt(KEY_CACHED_PC_DISP_W, 0);
            pcDispH = sp.getInt(KEY_CACHED_PC_DISP_H, 0);
            pcDispHz = sp.getInt(KEY_CACHED_PC_DISP_HZ, 0);
            String rawGames = sp.getString(KEY_CACHED_GAMES, null);
            if (rawGames != null && !rawGames.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(rawGames);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject g = arr.optJSONObject(i);
                        if (g == null) continue;
                        String gid = g.optString("id", "");
                        if (permHidden.contains(gid)) continue;
                        boolean isHidden = localHidden.contains(gid);
                        long ts = played.containsKey(gid) ? played.get(gid) : g.optLong("lastPlayedAt", 0L);
                        games.add(new GameItem(
                                gid,
                                g.optString("title", gid),
                                g.optString("category", "steam"),
                                g.optString("cmd", ""),
                                isHidden,
                                g.optString("imageUrl", ""),
                                ts
                        ));
                    }
                } catch (JSONException ignored) {
                }
            }
        }
        if (games.isEmpty()) {
            for (GameItem item : filterGames(defaultGameCatalog(), localHidden, true)) {
                if (permHidden.contains(item.id)) continue;
                long ts = played.containsKey(item.id) ? played.get(item.id) : 0L;
                games.add(item.withLastPlayed(ts));
            }
        }
        List<String> order = getGameOrder(context);
        if (!order.isEmpty()) {
            games.sort((a, b) -> {
                int ia = order.indexOf(a.id);
                int ib = order.indexOf(b.id);
                if (ia < 0) ia = Integer.MAX_VALUE;
                if (ib < 0) ib = Integer.MAX_VALUE;
                return Integer.compare(ia, ib);
            });
        }
        return new LaptopStatus(
                awake, locked, hdmi, sunshine, games, batteryPercent, charging, batteryFull,
                sunshineUuid, sunshineHost, pcDispW, pcDispH, pcDispHz,
                steamRunning, epicRunning, heroicRunning, sunshinePowerCtrl);
    }

    /**
     * Returns the user's custom game library order from SharedPreferences.
     */
    public static List<String> getGameOrder(Context context) {
        if (context == null) return Collections.emptyList();
        String raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("game_order", "");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Saves the user's custom game library order in SharedPreferences.
     */
    public static void setGameOrder(Context context, List<String> order) {
        if (context == null || order == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("game_order", String.join(",", order))
                .apply();
    }

    /**
     * Saves the discovered {@link LaptopStatus} and game list locally in SharedPreferences so future opens are instant.
     */
    public static void saveLaptopStatusToCache(Context context, LaptopStatus status) {
        if (context == null || status == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (GameItem item : status.games) {
                arr.put(item.toJson());
            }
            SharedPreferences.Editor ed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CACHED_AWAKE, status.awake)
                    .putBoolean(KEY_CACHED_LOCKED, status.locked)
                    .putBoolean(KEY_CACHED_HDMI, status.hdmiConnected)
                    .putBoolean(KEY_CACHED_SUNSHINE, status.sunshineRunning)
                    .putBoolean(KEY_CACHED_STEAM_RUNNING, status.steamRunning)
                    .putBoolean(KEY_CACHED_EPIC_RUNNING, status.epicRunning)
                    .putBoolean(KEY_CACHED_HEROIC_RUNNING, status.heroicRunning)
                    .putBoolean(KEY_CACHED_SUNSHINE_POWER_CTRL, status.sunshinePowerControlReady)
                    .putInt(KEY_CACHED_BATTERY_PERCENT, status.batteryPercent)
                    .putBoolean(KEY_CACHED_BATTERY_CHARGING, status.charging)
                    .putBoolean(KEY_CACHED_BATTERY_FULL, status.batteryFull)
                    .putString(KEY_CACHED_GAMES, arr.toString());
            if (!status.sunshineHostUuid.isEmpty()) {
                ed.putString(KEY_CACHED_SUNSHINE_UUID, status.sunshineHostUuid);
            }
            if (!status.sunshineHostName.isEmpty()) {
                ed.putString(KEY_CACHED_SUNSHINE_HOST, status.sunshineHostName);
            }
            if (status.pcDisplayWidth > 0 && status.pcDisplayHeight > 0) {
                ed.putInt(KEY_CACHED_PC_DISP_W, status.pcDisplayWidth);
                ed.putInt(KEY_CACHED_PC_DISP_H, status.pcDisplayHeight);
            }
            if (status.pcDisplayRefreshHz > 0) {
                ed.putInt(KEY_CACHED_PC_DISP_HZ, status.pcDisplayRefreshHz);
            }
            ed.apply();
        } catch (JSONException ignored) {
        }
    }

    /**
     * Returns the "Continue Playing" list ordered by most recently played first.
     * Only includes games that have actually been played recently (lastPlayedAt > 0).
     */
    public static List<GameItem> getContinuePlayingGames(Context context) {
        LaptopStatus cached = getCachedLaptopStatus(context);
        List<GameItem> visible = filterGames(cached.games, getHiddenGameIds(context), false);
        List<GameItem> played = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - 30L * 24L * 3600_000L;
        for (GameItem g : visible) {
            if (g.lastPlayedAt > 0L && g.lastPlayedAt >= cutoff && !"system".equals(g.category)) {
                played.add(g);
            }
        }
        played.sort((a, b) -> Long.compare(b.lastPlayedAt, a.lastPlayedAt));
        return played;
    }

    /**
     * Pure static filter determining whether an {@link android.view.InputDevice} represents a physical game controller
     * (excluding virtual bridges such as "Virtual - Remote", "Virtual - Search", TV IR/Bluetooth remotes, and CEC).
     */
    public static boolean isPhysicalGameController(android.view.InputDevice dev) {
        if (dev == null) return false;
        int sources = dev.getSources();
        boolean isGamepad = (sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
        boolean hasButtons = false;
        if (isGamepad) {
            boolean[] keys = dev.hasKeys(
                    android.view.KeyEvent.KEYCODE_BUTTON_A,
                    android.view.KeyEvent.KEYCODE_BUTTON_B,
                    android.view.KeyEvent.KEYCODE_BUTTON_X,
                    android.view.KeyEvent.KEYCODE_BUTTON_Y,
                    android.view.KeyEvent.KEYCODE_BUTTON_L1,
                    android.view.KeyEvent.KEYCODE_BUTTON_R1,
                    android.view.KeyEvent.KEYCODE_BUTTON_MODE);
            int hits = 0;
            if (keys != null) {
                for (boolean k : keys) {
                    if (k) hits++;
                }
            }
            hasButtons = hits >= 2;
        }
        return isPhysicalGameController(dev.getName(), dev.isVirtual(), isGamepad, hasButtons, false);
    }

    public static boolean isPhysicalGameController(
            String deviceName,
            boolean isVirtualDevice,
            boolean hasGamepadOrJoystickSource,
            boolean hasStandardGamepadButtons
    ) {
        return isPhysicalGameController(deviceName, isVirtualDevice, hasGamepadOrJoystickSource, hasStandardGamepadButtons, false);
    }

    /**
     * Extended filter that also accounts for USB XInput dongles (e.g. GameSir Nova Lite 3537:1040)
     * whose companion hid-generic interface may be labeled as "Keyboard" and report hasStandardGamepadButtons = false.
     */
    public static boolean isPhysicalGameController(
            String deviceName,
            boolean isVirtualDevice,
            boolean hasGamepadOrJoystickSource,
            boolean hasStandardGamepadButtons,
            boolean isMatchedXInputUsb
    ) {
        if (isVirtualDevice) {
            return false;
        }
        String name = deviceName == null ? "" : deviceName.trim();
        if (name.isEmpty()) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("virtual")
                || lower.contains("remote")
                || lower.contains("search")
                || lower.contains("cec")
                || lower.contains("xiaomi")
                || lower.contains("tv ")
                || lower.contains("mtk")
                || lower.contains("gpio")
                || lower.contains("ir ")
                || lower.contains("hdmi")
                || lower.contains("uinput")
                || lower.contains("atv")
                || lower.contains("voice")
                || lower.contains("assistant")
                || lower.startsWith("rc ")
                || lower.endsWith(" rc")
                || lower.contains(" rc ")
                || lower.contains("consumer control")
                || lower.contains("system control")
                || lower.contains("motion sensors")
                || lower.contains("motion sensor")
                || lower.contains("accelerometer")
                || lower.contains("gyroscope")
                || lower.contains("mouse")
                || lower.contains("touchscreen")
                || lower.contains("headset")
                || lower.contains("headphone")
                || isCompanionAudio(lower)
                || isCompanionMic(lower)
                || lower.contains("stylus")
                || isCompanionPen(lower)
                || lower.contains("digitizer")) {
            return false;
        }

        // Reject standalone touchpad companion nodes (e.g. "Xbox Touchpad" or USB DS4 Touchpad without gamepad buttons),
        // while accepting merged Bluetooth DualShock/DualSense gamepads ("Wireless Controller Touchpad") that carry
        // both standard gamepad buttons and the kernel hid-sony/hid-playstation BatteryState.
        if (lower.contains("touchpad")) {
            boolean mergedGamepadWithTouchpad = hasGamepadOrJoystickSource
                    && hasStandardGamepadButtons
                    && (lower.contains("controller") || lower.contains("gamepad")
                        || lower.contains("dualsense") || lower.contains("dualshock"));
            if (!mergedGamepadWithTouchpad) {
                return false;
            }
        }

        boolean isXboxIdentified = lower.contains("xbox") || lower.contains("xinput");
        if (isMatchedXInputUsb || isXboxIdentified) {
            return true;
        }

        if (!hasGamepadOrJoystickSource || !hasStandardGamepadButtons) {
            return false;
        }
        if (lower.contains("keyboard")) {
            return false;
        }
        return true;
    }

    private static boolean isCompanionAudio(String lower) {
        return lower.endsWith(" audio")
                || lower.contains(" audio ")
                || lower.contains("audio device")
                || lower.contains("audio adapter")
                || lower.contains("-audio")
                || lower.contains("(audio)")
                || lower.contains("[audio]");
    }

    private static boolean isCompanionMic(String lower) {
        return lower.contains("microphone")
                || lower.endsWith(" mic")
                || lower.contains(" mic ")
                || lower.contains(" mic-")
                || lower.contains("-mic")
                || lower.contains("(mic)")
                || lower.contains("[mic]");
    }

    private static boolean isCompanionPen(String lower) {
        return lower.endsWith(" pen")
                || lower.contains(" pen ")
                || lower.contains(" pen-")
                || lower.contains("-pen")
                || lower.contains("(pen)")
                || lower.contains("[pen]");
    }

    /**
     * Detects all physical gamepads / controllers currently connected to the TV via Bluetooth or USB.
     */
    public static List<String> getConnectedControllers(Context context) {
        List<ControllerManager.ControllerInfo> list = ControllerManager.getConnectedPhysicalControllers(context);
        List<String> controllers = new ArrayList<>(list.size());
        for (ControllerManager.ControllerInfo ci : list) {
            controllers.add(ci.getDisplayName());
        }
        return controllers;
    }

    /**
     * Formats a clean count-only controller status label without virtual/device-name clutter.
     */
    public static String formatControllerCountSummary(int count) {
        int safeCount = Math.max(0, count);
        if (safeCount == 1) {
            return "1 Controller Connected";
        }
        return safeCount + " Controllers Connected";
    }

    /**
     * Formats a concise PC online / connection transport status label (wireless vs. cable) without excess text.
     */
    public static String formatPcConnectionStatus(boolean pcOnline, boolean cableConnected) {
        return formatPcConnectionStatus(pcOnline, cableConnected, -1, false, false);
    }

    /**
     * Formats PC connection status with battery level (0..100%) whether on battery or plugged into AC power.
     * Desktop PCs without a system battery (batteryPercent &lt; 0) omit percentage.
     * Zero Emojis Mandate: Procedural vector charging bolt is drawn in HomeView, never as a string emoji.
     */
    public static String formatPcConnectionStatus(
            boolean pcOnline,
            boolean cableConnected,
            int batteryPercent,
            boolean isCharging,
            boolean isFull
    ) {
        if (!pcOnline) {
            return "PC Offline";
        }
        String base = cableConnected ? "PC Online · Cable" : "PC Online · Wireless";
        if (batteryPercent < 0) {
            return base;
        }
        return base + " · " + batteryPercent + "%";
    }

    /**
     * Extended PC status formatter reflecting on-demand Sunshine standby when {@code SunshineService}
     * is configured for manual start and currently stopped until a wireless game launches.
     */
    public static String formatPcConnectionStatus(
            boolean pcOnline,
            boolean cableConnected,
            int batteryPercent,
            boolean isCharging,
            boolean isFull,
            boolean sunshineRunning,
            boolean sunshinePowerControlReady
    ) {
        if (!pcOnline) {
            return "PC Offline";
        }
        if (!cableConnected && !sunshineRunning && sunshinePowerControlReady) {
            String base = "PC Online · Wireless (Standby)";
            return batteryPercent < 0 ? base : (base + " · " + batteryPercent + "%");
        }
        return formatPcConnectionStatus(pcOnline, cableConnected, batteryPercent, isCharging, isFull);
    }

    /**
     * Returns true if a connected-to-power / charging bolt indicator icon should be rendered next to the PC battery level.
     */
    public static boolean isPcBatteryChargingIndicatorNeeded(
            boolean pcOnline,
            int batteryPercent,
            boolean isCharging,
            boolean isFull
    ) {
        if (!pcOnline || !isCharging || batteryPercent < 0) return false;
        return true;
    }

    /**
     * Returns a concise count-only summary of connected game controllers.
     */
    public static String getControllerStatusSummary(Context context) {
        return formatControllerCountSummary(getConnectedControllers(context).size());
    }

    /**
     * Asynchronously refreshes the Laptop status in the background and invokes {@code onUpdate} on the main thread.
     */
    public static void refreshLaptopStatusAsync(final Context context, final Runnable onUpdate) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        GAMING_RPC_IO.execute(() -> {
            LaptopStatus st = fetchLaptopStatusSync(appCtx);
            saveLaptopStatusToCache(appCtx, st);
            if (onUpdate != null) main.post(onUpdate);
        });
    }

    private static android.graphics.Bitmap fitOrCropBitmap(android.graphics.Bitmap src, int dstW, int dstH) {
        if (src == null) return null;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        AspectCropRect rect = computeAspectFitOrCropRect(sw, sh, dstW, dstH, MAX_ASPECT_STRETCH_TOLERANCE);
        android.graphics.Bitmap working = src;
        if (rect.cropped && (rect.left != 0 || rect.top != 0 || rect.width != sw || rect.height != sh)) {
            android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                    src, rect.left, rect.top, rect.width, rect.height);
            if (cropped != src && !src.isRecycled()) src.recycle();
            working = cropped;
        }
        if (working.getWidth() == dstW && working.getHeight() == dstH) {
            return working;
        }
        android.graphics.Bitmap scaled = Ui.downscale(working, dstW, dstH);
        if (scaled != working && !working.isRecycled()) {
            working.recycle();
        }
        return scaled;
    }

    private static boolean writeWebpAtomically(android.graphics.Bitmap bmp, java.io.File targetFile) {
        if (bmp == null || targetFile == null) return false;
        java.io.File dir = targetFile.getParentFile();
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        java.io.File tmp = new java.io.File(targetFile.getPath() + ".tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            if (!bmp.compress(webpLossyFormat(), WEBP_CACHE_QUALITY, fos)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            fos.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            AppLog.w("GamingZone WebP cache write failed: " + e.getMessage());
            return false;
        }
        if (!tmp.renameTo(targetFile)) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.delete();
            if (!tmp.renameTo(targetFile)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a 16:9 bitmap for a {@link GameItem}, loading from memory/disk cache (`.webp` first, `.jpg` fallback)
     * or downloading from Steam CDN in the background (falling back immediately to a styled 16:9 game card).
     */
    public static android.graphics.Bitmap getGameBitmap(final Context context, final GameItem item, final Runnable onLoaded) {
        if (item == null) return null;
        final String key = item.id;
        synchronized (ART_MEM) {
            android.graphics.Bitmap mem = ART_MEM.get(key);
            if (mem != null && !mem.isRecycled()) return mem;
        }
        final java.io.File cacheDir = new java.io.File(context.getCacheDir(), "game_art");
        final String safeBase = key.replaceAll("[^a-zA-Z0-9_-]", "_");
        final java.io.File cachedFile = resolveCachedArtworkFile(cacheDir, safeBase);
        if (cachedFile.exists() && cachedFile.length() > 0) {
            android.graphics.Bitmap diskBmp = android.graphics.BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
            if (diskBmp != null) {
                synchronized (ART_MEM) {
                    android.graphics.Bitmap prev = ART_MEM.put(key, diskBmp);
                    if (prev != null && prev != diskBmp) Backdrop.scheduleSafeBitmapRecycle(prev);
                    ART_SHADERS.put(key, new android.graphics.BitmapShader(diskBmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
                }
                return diskBmp;
            }
        }
        // Build stylized fallback card immediately so there is never an empty card on screen
        android.graphics.Bitmap fallback = buildStylizedGameCard(context, item);
        synchronized (ART_MEM) {
            android.graphics.Bitmap prev = ART_MEM.put(key, fallback);
            if (prev != null && prev != fallback) Backdrop.scheduleSafeBitmapRecycle(prev);
            ART_SHADERS.put(key, new android.graphics.BitmapShader(fallback, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        }
        if (item.imageUrl != null && item.imageUrl.startsWith("http")) {
            boolean shouldFetch;
            synchronized (ART_LOADING) {
                shouldFetch = ART_LOADING.add(key);
            }
            if (shouldFetch) {
                final java.io.File targetWebp = new java.io.File(cacheDir, safeBase + ".webp");
                final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                GAME_ART_IO.execute(() -> {
                    try {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(item.imageUrl).openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(6000);
                        try (InputStream in = conn.getInputStream()) {
                            android.graphics.Bitmap netBmp = android.graphics.BitmapFactory.decodeStream(in);
                            if (netBmp != null) {
                                android.graphics.Bitmap scaled = fitOrCropBitmap(netBmp, 640, 360);
                                writeWebpAtomically(scaled, targetWebp);
                                synchronized (ART_MEM) {
                                    android.graphics.Bitmap prev = ART_MEM.put(key, scaled);
                                    if (prev != null && prev != scaled) Backdrop.scheduleSafeBitmapRecycle(prev);
                                    ART_SHADERS.put(key, new android.graphics.BitmapShader(scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
                                }
                                if (onLoaded != null) main.post(onLoaded);
                            }
                        } finally {
                            conn.disconnect();
                        }
                    } catch (Exception e) {
                        AppLog.w("game cover art fetch failed for " + key + ": " + e.getMessage());
                    } finally {
                        synchronized (ART_LOADING) {
                            ART_LOADING.remove(key);
                        }
                    }
                });
            }
        }
        return fallback;
    }

    public static android.graphics.BitmapShader getGameShader(String gameId) {
        synchronized (ART_MEM) {
            return ART_SHADERS.get(gameId);
        }
    }

    static java.io.File getGameHeroDiskDir(Context context) {
        java.io.File base = context.getFilesDir() != null ? context.getFilesDir() : context.getCacheDir();
        return new java.io.File(base, GAME_HERO_DISK_DIR_NAME);
    }

    private static void cleanLegacyLowResHeroCacheOnce(Context context) {
        if (legacyHeroCacheCleaned || context == null) return;
        legacyHeroCacheCleaned = true;
        try {
            java.io.File oldDir = new java.io.File(context.getCacheDir(), "game_hero");
            if (oldDir.isDirectory()) {
                java.io.File[] oldFiles = oldDir.listFiles();
                if (oldFiles != null) {
                    for (java.io.File f : oldFiles) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                oldDir.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Pins lead game hero backdrop keys (e.g., Continue Playing #0 and Games Library #0) in {@code GAME_HERO_MEM}
     * so scrolling through the game library or switching tabs never evicts the lead game backgrounds.
     */
    static void setPinnedGameHeroes(java.util.Collection<GameItem> leadGames) {
        PINNED_GAME_HERO_KEYS.clear();
        if (leadGames == null) return;
        int pinned = 0;
        for (GameItem g : leadGames) {
            if (g == null || g.id == null || g.id.isEmpty()
                    || HIDDEN_TILE_ID.equals(g.id) || "system".equals(g.category)) {
                continue;
            }
            if (PINNED_GAME_HERO_KEYS.add("hero:" + g.id) && ++pinned >= 4) {
                break;
            }
        }
    }

    static Backdrop peekGameBackdrop(GameItem item) {
        if (item == null || HIDDEN_TILE_ID.equals(item.id) || "system".equals(item.category)) return null;
        final String key = "hero:" + item.id;
        synchronized (GAME_HERO_MEM) {
            Backdrop b = GAME_HERO_MEM.get(key);
            if (b != null && b.isRecycled()) {
                GAME_HERO_MEM.remove(key);
                return null;
            }
            return b;
        }
    }

    static int getGameHeroCacheSize() {
        synchronized (GAME_HERO_MEM) {
            return GAME_HERO_MEM.size();
        }
    }

    static boolean isGameBackdropCached(GameItem item) {
        return peekGameBackdrop(item) != null;
    }

    /**
     * Returns the first already-decoded {@link Backdrop} in memory among {@code preferredGames}
     * (or the most recently cached game backdrop if none of {@code preferredGames} is ready yet),
     * or {@code null} when no game backdrop has been decoded yet.
     */
    static Backdrop firstReadyGameBackdrop(List<GameItem> preferredGames) {
        synchronized (GAME_HERO_MEM) {
            if (preferredGames != null) {
                for (GameItem g : preferredGames) {
                    if (g == null || HIDDEN_TILE_ID.equals(g.id) || "system".equals(g.category)) continue;
                    String k = "hero:" + g.id;
                    Backdrop b = GAME_HERO_MEM.get(k);
                    if (b != null && b.isRecycled()) {
                        GAME_HERO_MEM.remove(k);
                        continue;
                    }
                    if (b != null) return b;
                }
            }
            Backdrop last = null;
            java.util.Iterator<java.util.Map.Entry<String, Backdrop>> it = GAME_HERO_MEM.entrySet().iterator();
            while (it.hasNext()) {
                Backdrop b = it.next().getValue();
                if (b != null && b.isRecycled()) {
                    it.remove();
                    continue;
                }
                if (b != null) last = b;
            }
            return last;
        }
    }

    /**
     * Proactively preloads and caches high-resolution hero backgrounds for the given ordered list of games
     * into persistent disk storage ({@code filesDir/game_hero_hd}) and the in-RAM LRU {@code GAME_HERO_MEM} cache.
     */
    static void prefetchGameBackdropsAsync(final Context context, final List<GameItem> games, final Runnable onLoaded) {
        if (context == null || games == null || games.isEmpty()) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Set<String> seen = new HashSet<>();
        for (GameItem g : games) {
            if (g == null || g.id.isEmpty() || HIDDEN_TILE_ID.equals(g.id) || "system".equals(g.category)) continue;
            if (!seen.add(g.id)) continue;
            if (peekGameBackdrop(g) != null) continue;
            ensureGameBackdropLoadedAsync(appCtx, g, onLoaded);
        }
    }

    static void preloadGameBackdropAsync(final Context context, final GameItem item, final Runnable onLoaded) {
        if (context == null || item == null || HIDDEN_TILE_ID.equals(item.id) || "system".equals(item.category)) return;
        if (peekGameBackdrop(item) != null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ensureGameBackdropLoadedAsync(appCtx, item, onLoaded);
    }

    /**
     * Resolves a Home-style high-resolution {@link Backdrop} for a {@link GameItem}.
     * Returns the decoded {@link Backdrop} immediately when cached in RAM, or {@code null} while loading
     * asynchronously so {@code HomeView} keeps displaying the previous loaded game background seamlessly.
     */
    static Backdrop getGameBackdrop(final Context context, final GameItem item, final Runnable onLoaded) {
        if (context == null || item == null || HIDDEN_TILE_ID.equals(item.id) || "system".equals(item.category)) {
            return null;
        }
        Backdrop mem = peekGameBackdrop(item);
        if (mem != null) return mem;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ensureGameBackdropLoadedAsync(appCtx, item, onLoaded);
        return null;
    }

    private static void ensureGameBackdropLoadedAsync(
            final Context context, final GameItem item, final Runnable onLoaded) {
        if (context == null || item == null || NO_GAME_HERO_IDS.contains(item.id)) return;
        final String heroKey = "hero:" + item.id;
        boolean shouldFetch;
        synchronized (GAME_HERO_LOADING) {
            if (onLoaded != null) {
                java.util.List<Runnable> list = GAME_HERO_CALLBACKS.get(heroKey);
                if (list == null) {
                    list = new java.util.ArrayList<>(2);
                    GAME_HERO_CALLBACKS.put(heroKey, list);
                }
                if (!list.contains(onLoaded)) {
                    list.add(onLoaded);
                }
            }
            shouldFetch = GAME_HERO_LOADING.add(heroKey);
        }
        if (!shouldFetch) {
            // Double-check cache in case worker finished right before we registered
            if (peekGameBackdrop(item) != null && onLoaded != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onLoaded);
            }
            return;
        }

        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        GAME_HERO_IO.execute(() -> {
            boolean loadedOk = false;
            try {
                cleanLegacyLowResHeroCacheOnce(context);
                java.io.File heroDir = getGameHeroDiskDir(context);
                String safeName = item.id.replaceAll("[^a-zA-Z0-9_-]", "_");
                java.io.File cachedHeroFile = resolveCachedArtworkFile(heroDir, safeName + "_hd");

                if (cachedHeroFile.exists() && cachedHeroFile.length() > 0) {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(cachedHeroFile.getAbsolutePath(), bounds);
                    if (bounds.outWidth >= MIN_GAME_HERO_WIDTH && bounds.outHeight >= MIN_GAME_HERO_HEIGHT) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
                        opts.inSampleSize = 1;
                        while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W
                                && bounds.outHeight / (opts.inSampleSize * 2) >= MIN_GAME_HERO_HEIGHT) {
                            opts.inSampleSize *= 2;
                        }
                        android.graphics.Bitmap diskBmp = android.graphics.BitmapFactory.decodeFile(
                                cachedHeroFile.getAbsolutePath(), opts);
                        if (diskBmp != null) {
                            Backdrop bd = Backdrop.make(diskBmp, true);
                            synchronized (GAME_HERO_MEM) {
                                Backdrop prev = GAME_HERO_MEM.put(heroKey, bd);
                                if (prev != null && prev != bd) Backdrop.scheduleSafeRecycle(prev);
                            }
                            loadedOk = true;
                            return;
                        }
                    }
                }

                android.graphics.Bitmap downloaded = downloadHighResGameHeroSync(context, item);
                if (downloaded != null) {
                    android.graphics.Bitmap normalized = normalizeHeroBitmapForDisk(downloaded);
                    java.io.File targetWebp = new java.io.File(heroDir, safeName + "_hd.webp");
                    writeWebpAtomically(normalized, targetWebp);
                    Backdrop bd = Backdrop.make(normalized, true);
                    synchronized (GAME_HERO_MEM) {
                        Backdrop prev = GAME_HERO_MEM.put(heroKey, bd);
                        if (prev != null && prev != bd) Backdrop.scheduleSafeRecycle(prev);
                    }
                    loadedOk = true;
                    return;
                }

                NO_GAME_HERO_IDS.add(item.id);
            } catch (Exception e) {
                AppLog.w("game hero load failed for " + item.id + ": " + e.getMessage());
            } finally {
                java.util.List<Runnable> callbacks;
                synchronized (GAME_HERO_LOADING) {
                    GAME_HERO_LOADING.remove(heroKey);
                    callbacks = GAME_HERO_CALLBACKS.remove(heroKey);
                }
                if (loadedOk && callbacks != null) {
                    for (Runnable cb : callbacks) {
                        if (cb != null) main.post(cb);
                    }
                }
            }
        });
    }

    private static android.graphics.Bitmap normalizeHeroBitmapForDisk(android.graphics.Bitmap src) {
        if (src == null) return null;
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        AspectCropRect rect = computeAspectFitOrCropRect(sw, sh, 16, 9, MAX_ASPECT_STRETCH_TOLERANCE);
        int maxW = Math.max(1280, Backdrop.W);
        int targetW = Math.min(maxW, Math.max(1280, (rect.width / 16) * 16));
        int targetH = Math.max(720, (targetW / 16) * 9);
        return fitOrCropBitmap(src, targetW, targetH);
    }

    private static android.graphics.Bitmap downloadHighResGameHeroSync(Context context, GameItem item) {
        String appId = extractSteamAppId(item.id, item.title, item.cmd);
        if (appId.isEmpty() && !isLeagueOfLegends(item.id, item.title, item.cmd)) {
            appId = resolveSteamAppIdViaStoreSearchSync(context, item.title);
        }

        List<String> candidates = new ArrayList<>(5);
        if (!appId.isEmpty()) {
            // 1. Steam @2x Ultra-HD Hero (3840x1240 -> crops to 2204x1240 > Full HD)
            candidates.add("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_hero_2x.jpg");
            android.graphics.Bitmap twoX = tryDownloadHeroBitmapSync(candidates.get(0), 1600, 900);
            if (twoX != null) return twoX;

            // 2. When @2x is unavailable (404), check Steam Store Full-HD (1920x1080) screenshot before 620px banner
            String fullHdShot = fetchSteamStoreFirstScreenshotUrlSync(appId);
            if (!fullHdShot.isEmpty()) {
                android.graphics.Bitmap shotBmp = tryDownloadHeroBitmapSync(fullHdShot, 1600, 900);
                if (shotBmp != null) return shotBmp;
            }

            // 3. Fallback to standard Steam library_hero.jpg (1920x620)
            String stdHero = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_hero.jpg";
            android.graphics.Bitmap stdBmp = tryDownloadHeroBitmapSync(stdHero, MIN_GAME_HERO_WIDTH, MIN_GAME_HERO_HEIGHT);
            if (stdBmp != null) return stdBmp;
        } else {
            candidates.addAll(resolveHeroArtCandidateUrls(item));
            if (item.imageUrl != null && item.imageUrl.startsWith("http") && !candidates.contains(item.imageUrl)) {
                candidates.add(item.imageUrl);
            }
            for (String url : candidates) {
                android.graphics.Bitmap bmp = tryDownloadHeroBitmapSync(url, MIN_GAME_HERO_WIDTH, MIN_GAME_HERO_HEIGHT);
                if (bmp != null) return bmp;
            }
        }
        return null;
    }

    private static android.graphics.Bitmap tryDownloadHeroBitmapSync(String url, int minW, int minH) {
        if (url == null || !url.startsWith("http")) return null;
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(9000);
            if (conn.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream(262_144)) {
                byte[] buf = new byte[16_384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 16 * 1024 * 1024) return null;
                }
                data = bos.toByteArray();
            }
            if (data.length < 4096) return null;

            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth < minW || bounds.outHeight < minH || bounds.outWidth < bounds.outHeight) {
                return null;
            }

            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W
                    && bounds.outHeight / (opts.inSampleSize * 2) >= Math.max(minH, Backdrop.H)) {
                opts.inSampleSize *= 2;
            }
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String fetchSteamStoreFirstScreenshotUrlSync(String appId) {
        if (appId == null || appId.isEmpty()) return "";
        String url = "https://store.steampowered.com/api/appdetails?appids=" + appId + "&filters=screenshots";
        String json = downloadTextSync(url, 4500);
        return parseSteamStoreFirstScreenshotUrl(json, appId);
    }

    private static String resolveSteamAppIdViaStoreSearchSync(Context context, String title) {
        if (title == null || title.trim().isEmpty()) return "";
        String norm = title.trim().toLowerCase(java.util.Locale.ROOT);
        String mem = DYNAMIC_STEAM_APP_ID_CACHE.get(norm);
        if (mem != null) return mem;

        SharedPreferences prefs = null;
        String prefKey = "steam_search_id_" + norm.replaceAll("[^a-z0-9]+", "_");
        try {
            if (context != null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (prefs.contains(prefKey)) {
                    String saved = prefs.getString(prefKey, "");
                    DYNAMIC_STEAM_APP_ID_CACHE.put(norm, saved);
                    return saved;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String encoded = java.net.URLEncoder.encode(title.trim(), "UTF-8");
            String url = "https://store.steampowered.com/api/storesearch/?term=" + encoded + "&l=english&cc=US";
            String json = downloadTextSync(url, 4500);
            String found = parseSteamStoreSearchAppId(json);
            DYNAMIC_STEAM_APP_ID_CACHE.put(norm, found);
            if (prefs != null) {
                prefs.edit().putString(prefKey, found).apply();
            }
            return found;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String downloadTextSync(String url, int timeoutMs) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) return "";
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream(8192)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 512 * 1024) break;
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static android.graphics.Bitmap buildStylizedGameCard(Context c, GameItem item) {
        int w = 640, h = 360;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(b);
        int c0 = 0xFF1B2838, c1 = 0xFF0F1722;
        String t = item.title.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("steam")) { c0 = 0xFF1B2838; c1 = 0xFF2A475E; }
        else if (t.contains("desktop")) { c0 = 0xFF0078D4; c1 = 0xFF002B49; }
        else if (t.contains("league")) { c0 = 0xFF0A323C; c1 = 0xFFC89B3C; }
        else if (t.contains("hogwarts")) { c0 = 0xFF2C1B47; c1 = 0xFF0F0A1C; }
        else if (t.contains("overcooked")) { c0 = 0xFFD84315; c1 = 0xFF4E1402; }
        else if (t.contains("boomerang")) { c0 = 0xFF00897B; c1 = 0xFF00332C; }
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, w, h, c0, c1, android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        Symbols.draw(c, cv, "gamepad", w / 2f, h * 0.42f, 84f, 0xD9FFFFFF);
        android.graphics.Paint tp = Ui.text(Ui.semibold, 34f, 0xFFFFFFFF);
        tp.setTextAlign(android.graphics.Paint.Align.CENTER);
        cv.drawText(item.title, w / 2f, h * 0.78f, tp);
        return b;
    }

    /**
     * Queries the Laptop Gaming Agent (:8485) synchronously for its current status and game list,
     * automatically discovering and adopting the PC's new LAN IP if Wi-Fi/DHCP reassigned it.
     */
    public static LaptopStatus fetchLaptopStatusSync(Context context) {
        String ip = resolveReachableLaptopIpSync(context, FAST_SAME_SUBNET_PROBE_MS);
        Set<String> localHidden = getHiddenGameIds(context);
        Set<String> permHidden = getPermanentlyHiddenGameIds(context);
        java.util.Map<String, Long> played = getLastPlayedTimestamps(context);
        try {
            String resp = rawHttpRequest(context, ip, LAPTOP_AGENT_PORT, "GET", "/api/gaming/status", "", 1800);
            if (resp != null && !resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                boolean awake = json.optBoolean("awake", true);
                boolean locked = json.optBoolean("locked", false);
                boolean hdmi = json.optBoolean("hdmiConnected", false);
                boolean sunshine = json.optBoolean("sunshineRunning", false);
                boolean steamRunning = json.optBoolean("steamRunning", false);
                boolean epicRunning = json.optBoolean("epicRunning", false);
                boolean heroicRunning = json.optBoolean("heroicRunning", false);
                boolean sunshinePowerCtrl = json.optBoolean("sunshinePowerControlReady", false);
                int batteryPercent = json.isNull("batteryPercent") ? -1 : json.optInt("batteryPercent", -1);
                boolean charging = json.optBoolean("charging", false);
                boolean batteryFull = json.optBoolean("full", json.optBoolean("batteryFull", false));
                String sunshineUuid = json.optString("sunshineHostUuid", "");
                String sunshineHost = json.optString("sunshineHostName", "");
                int pcDispW = json.optInt("primaryDisplayWidth", 0);
                int pcDispH = json.optInt("primaryDisplayHeight", 0);
                int pcDispHz = json.optInt("primaryDisplayRefreshHz", 0);
                JSONArray macArr = json.optJSONArray("macAddresses");
                if (macArr != null && macArr.length() > 0) {
                    List<String> macs = new ArrayList<>(macArr.length());
                    for (int m = 0; m < macArr.length(); m++) {
                        String ms = macArr.optString(m, "");
                        if (!ms.isEmpty()) macs.add(ms);
                    }
                    saveCachedPcMacs(context, macs);
                }
                JSONArray arr = json.optJSONArray("games");
                List<GameItem> games = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject g = arr.optJSONObject(i);
                        if (g == null) continue;
                        String gid = g.optString("id", "");
                        if (permHidden.contains(gid)) continue;
                        boolean isHidden = localHidden.contains(gid);
                        long ts = played.containsKey(gid) ? played.get(gid) : 0L;
                        games.add(new GameItem(
                                gid,
                                g.optString("title", gid),
                                g.optString("category", "steam"),
                                g.optString("cmd", ""),
                                isHidden,
                                g.optString("imageUrl", ""),
                                ts
                        ));
                    }
                }
                if (games.isEmpty()) {
                    for (GameItem item : filterGames(defaultGameCatalog(), localHidden, true)) {
                        long ts = played.containsKey(item.id) ? played.get(item.id) : 0L;
                        games.add(item.withLastPlayed(ts));
                    }
                }
                LaptopStatus prevCached = getCachedLaptopStatus(context);
                LaptopStatus status = new LaptopStatus(
                        awake,
                        locked,
                        hdmi,
                        sunshine,
                        games,
                        batteryPercent,
                        charging,
                        batteryFull,
                        !sunshineUuid.isEmpty() ? sunshineUuid : prevCached.sunshineHostUuid,
                        !sunshineHost.isEmpty() ? sunshineHost : prevCached.sunshineHostName,
                        pcDispW > 0 ? pcDispW : prevCached.pcDisplayWidth,
                        pcDispH > 0 ? pcDispH : prevCached.pcDisplayHeight,
                        pcDispHz > 0 ? pcDispHz : prevCached.pcDisplayRefreshHz,
                        steamRunning,
                        epicRunning,
                        heroicRunning,
                        sunshinePowerCtrl
                );
                saveLaptopStatusToCache(context, status);
                return status;
            }
        } catch (Exception ignored) {
        }
        return getCachedLaptopStatus(context);
    }

    /**
     * Builds the JSON status representation of the Gaming Zone for {@link RemoteControlServer}.
     */
    public static JSONObject toStatusJson(Context context) throws JSONException {
        return toStatusJson(context, false);
    }

    /**
     * Builds the JSON status representation of the Gaming Zone, optionally forcing a synchronous PC network probe.
     */
    public static JSONObject toStatusJson(Context context, boolean forceNetworkRefresh) throws JSONException {
        LaptopStatus st;
        if (forceNetworkRefresh) {
            st = fetchLaptopStatusSync(context);
        } else {
            st = getCachedLaptopStatus(context);
            refreshLaptopStatusAsync(context, null);
        }
        JSONObject out = new JSONObject();
        out.put("laptopIp", getLaptopIp(context));
        out.put("awake", st.awake);
        out.put("locked", st.locked);
        out.put("hdmiConnected", st.hdmiConnected);
        out.put("sunshineRunning", st.sunshineRunning);
        out.put("steamRunning", st.steamRunning);
        out.put("epicRunning", st.epicRunning);
        out.put("heroicRunning", st.heroicRunning);
        out.put("sunshinePowerControlReady", st.sunshinePowerControlReady);
        out.put("batteryPercent", st.batteryPercent);
        out.put("batteryCharging", st.charging);
        out.put("batteryFull", st.batteryFull);
        List<ControllerManager.ControllerInfo> allKnown = ControllerManager.getAllKnownControllers(context);
        List<String> connectedNames = new ArrayList<>();
        JSONArray ctrlDetails = new JSONArray();
        JSONArray allKnownArr = new JSONArray();
        for (ControllerManager.ControllerInfo ci : allKnown) {
            if (ci == null) continue;
            JSONObject kj = new JSONObject();
            kj.put("descriptor", ci.descriptor);
            kj.put("name", ci.getDisplayName());
            kj.put("deviceName", ci.deviceName);
            kj.put("customName", ci.customName);
            kj.put("color", ControllerManager.formatColorHex(ci.color));
            kj.put("colorName", ci.tagged ? ControllerManager.findColorName(ci.color) : "Neutral");
            kj.put("tagged", ci.tagged);
            kj.put("connected", ci.connected);
            kj.put("batteryPercent", ci.batteryPercent);
            kj.put("charging", ci.charging);
            kj.put("powerStatus", ci.getPowerStatusDisplay());
            allKnownArr.put(kj);
            if (ci.connected) {
                connectedNames.add(ci.getDisplayName());
                ctrlDetails.put(kj);
            }
        }
        out.put("controllerSummary", formatControllerCountSummary(connectedNames.size()));
        out.put("connectedControllers", new JSONArray(connectedNames));
        out.put("controllers", ctrlDetails);
        out.put("allKnownControllers", allKnownArr);

        JSONArray continueArr = new JSONArray();
        for (GameItem item : getContinuePlayingGames(context)) {
            continueArr.put(item.toJson());
        }
        out.put("continuePlaying", continueArr);

        JSONArray arr = new JSONArray();
        for (GameItem item : st.games) {
            arr.put(item.toJson());
        }
        out.put("games", arr);
        return out;
    }

    /**
     * Sends an unlock request with the user's Windows Hello Alphanumeric PIN to the laptop agent.
     */
    public static boolean unlockLaptopSync(Context context, String pin) {
        if (pin == null || pin.trim().isEmpty()) return false;
        try {
            JSONObject req = new JSONObject();
            req.put("pin", pin.trim());
            String host = resolveReachableLaptopIpSync(context, FAST_SAME_SUBNET_PROBE_MS);
            String resp = rawHttpRequest(context, host, LAPTOP_AGENT_PORT, "POST", "/api/gaming/unlock", req.toString(), 6500);
            if (resp != null && !resp.isEmpty()) {
                JSONObject json = new JSONObject(resp);
                return json.optBoolean("unlocked", !json.optBoolean("locked", false));
            }
        } catch (Exception e) {
            AppLog.w("GamingZone unlock request failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Triggers a remote game launch on the laptop:
     * 1. Sends Wake-on-LAN + Modern Standby S0 wake.
     * 2. Calls Laptop Gaming Agent (:8485) with connectionMode="auto" so the laptop checks if an HDMI
     *    cable was connected to it recently/now, unlocks Windows if PIN provided, and starts the game.
     * 3. If the laptop confirms an HDMI cable is connected, switches the TV to the Laptop HDMI port
     *    (ignoring Media Streamer and Console ports). Otherwise connects wirelessly via Moonlight!
     */
    private static void saveSunshineHostIdentity(Context context, String uuid, String hostName) {
        if (context == null) return;
        String cleanUuid = uuid != null ? uuid.trim() : "";
        String cleanHost = hostName != null ? hostName.trim() : "";
        if (cleanUuid.isEmpty() && cleanHost.isEmpty()) return;
        SharedPreferences.Editor ed = gamingPrefs(context).edit();
        if (!cleanUuid.isEmpty()) ed.putString(KEY_CACHED_SUNSHINE_UUID, cleanUuid);
        if (!cleanHost.isEmpty()) ed.putString(KEY_CACHED_SUNSHINE_HOST, cleanHost);
        ed.commit();
    }

    public static void launchGameOnTvAndLaptop(
            final Activity activity,
            final View fromView,
            final GameItem game,
            final String optionalPin
    ) {
        final long launchToken = ACTIVE_LAUNCH_TOKEN.incrementAndGet();
        final Context appCtx = activity.getApplicationContext();
        sendWakeOnLanAsync(appCtx, Config.GAMING_PC_MAC);
        if (game != null) {
            recordGamePlayed(appCtx, game.id);
        }
        final String targetTitle = (game != null && !game.title.trim().isEmpty()) ? game.title.trim() : "Steam Big Picture";
        final HomeView hv = HomeView.findFrom(activity, fromView);
        final String targetId = (game != null && !game.id.trim().isEmpty()) ? game.id.trim() : "steam_bigpicture";
        final boolean prelaunchOnPc = !"desktop".equalsIgnoreCase(targetId);

        final LaunchModalStep wakeStep = LaunchStage.WAKING_PC.buildStep(game, null);
        if (hv != null) {
            hv.startGameLaunchModal(
                    targetTitle,
                    wakeStep.headline,
                    wakeStep.subtitle,
                    wakeStep.iconName,
                    wakeStep.stageIndex,
                    wakeStep.progressPercent
            );
        } else {
            Toast.makeText(activity, "Starting " + targetTitle + "…", Toast.LENGTH_SHORT).show();
        }

        GAMING_RPC_IO.execute(() -> {
            if (ACTIVE_LAUNCH_TOKEN.get() != launchToken) return;
            boolean laptopReportsHdmi = false;
            boolean laptopLockedNeedsPin = false;
            boolean laptopAuthRejected = false;
            boolean agentReached = false;
            String respSunshineUuid = "";
            String respSunshineHost = "";
            String respSunshineApp = "";
            String resolvedIp = resolveReachableLaptopIpSync(appCtx, 650);

            final boolean hasPin = optionalPin != null && !optionalPin.trim().isEmpty();
            if (hasPin && hv != null) {
                final LaunchModalStep unlockStep = LaunchStage.UNLOCKING_WINDOWS.buildStep(game, null);
                activity.runOnUiThread(() -> {
                    if (ACTIVE_LAUNCH_TOKEN.get() == launchToken) {
                        hv.updateGameLaunchModal(
                                unlockStep.headline,
                                unlockStep.subtitle,
                                unlockStep.iconName,
                                unlockStep.stageIndex,
                                unlockStep.progressPercent
                        );
                    }
                });
                try {
                    Thread.sleep(280L);
                } catch (InterruptedException ignored) {
                }
            }

            if (ACTIVE_LAUNCH_TOKEN.get() != launchToken) return;
            final long prepStartedMs = System.currentTimeMillis();
            final LaptopStatus preLaunchCached = getCachedLaptopStatus(appCtx);
            final boolean sunshineStartingCold = preLaunchCached != null
                    && !preLaunchCached.hdmiConnected
                    && !preLaunchCached.sunshineRunning
                    && preLaunchCached.sunshinePowerControlReady;
            if (hv != null) {
                final LaunchModalStep prepStep = LaunchStage.PREPARING_STEAM.buildStep(game, null);
                final String prepHeadline = sunshineStartingCold
                        ? "Starting Sunshine & Preparing Stream…"
                        : prepStep.headline;
                final String prepSubtitle = sunshineStartingCold
                        ? "Waking Sunshine streaming service on your Gaming PC"
                        : prepStep.subtitle;
                activity.runOnUiThread(() -> {
                    if (ACTIVE_LAUNCH_TOKEN.get() == launchToken) {
                        hv.updateGameLaunchModal(
                                prepHeadline,
                                prepSubtitle,
                                prepStep.iconName,
                                prepStep.stageIndex,
                                prepStep.progressPercent
                        );
                    }
                });
            }

            for (int attempt = 0; attempt < 2 && !agentReached && !laptopAuthRejected; attempt++) {
                if (ACTIVE_LAUNCH_TOKEN.get() != launchToken) return;
                if (attempt == 1) {
                    try {
                        Thread.sleep(1200L);
                    } catch (InterruptedException ignored) {
                    }
                    if (ACTIVE_LAUNCH_TOKEN.get() != launchToken) return;
                    String wokenIp = discoverLaptopIpOnLanSync(appCtx);
                    if (wokenIp.isEmpty()) {
                        break;
                    }
                    resolvedIp = wokenIp;
                }
                try {
                    JSONObject req = new JSONObject();
                    req.put("gameId", targetId);
                    req.put("connectionMode", "auto");
                    req.put("delegateLaunchToMoonlight", false);
                    req.put("prelaunchBeforeStream", prelaunchOnPc);
                    req.put("userApproved", true);
                    if (hasPin) {
                        req.put("pin", optionalPin.trim());
                    }
                    INFLIGHT_PRELAUNCH_SESSION.set(new ActiveStreamingSession(
                            targetId, targetTitle, resolvedIp, LAPTOP_AGENT_PORT, System.currentTimeMillis()));
                    HttpResult launchRes = rawHttpRequestWithStatus(
                            appCtx,
                            resolvedIp,
                            LAPTOP_AGENT_PORT,
                            "POST",
                            "/api/gaming/launch",
                            req.toString(),
                            3500,
                            35000
                    );
                    if (launchRes.statusCode == 401 || launchRes.statusCode == 403) {
                        laptopAuthRejected = true;
                        INFLIGHT_PRELAUNCH_SESSION.set(null);
                    } else if (launchRes.isSuccess() && !launchRes.body.isEmpty()) {
                        agentReached = true;
                        JSONObject json = new JSONObject(launchRes.body);
                        boolean lockedNow = json.optBoolean("locked", false);
                        if (lockedNow && !hasPin) {
                            laptopLockedNeedsPin = true;
                            INFLIGHT_PRELAUNCH_SESSION.set(null);
                        }
                        if ("hdmi".equalsIgnoreCase(json.optString("activeMode", ""))
                                || json.optBoolean("hdmiConnected", false)) {
                            laptopReportsHdmi = true;
                        }
                        respSunshineUuid = json.optString("sunshineHostUuid", "");
                        respSunshineHost = json.optString("sunshineHostName", "");
                        respSunshineApp = json.optString("sunshineAppName", "");
                        saveSunshineHostIdentity(appCtx, respSunshineUuid, respSunshineHost);
                    } else {
                        INFLIGHT_PRELAUNCH_SESSION.set(null);
                    }
                } catch (Exception e) {
                    INFLIGHT_PRELAUNCH_SESSION.set(null);
                    AppLog.w("GamingZone launch RPC attempt " + (attempt + 1) + " failed: " + e.getMessage());
                }
            }
            final String laptopIp = resolvedIp;

            // Wait for the target game window to materialize behind the PC's black privacy curtain
            // BEFORE starting the video stream so the user never sees desktop icons or open windows!
            if (agentReached && !laptopLockedNeedsPin && !laptopAuthRejected && !laptopReportsHdmi && prelaunchOnPc) {
                long waitStart = System.currentTimeMillis();
                while (ACTIVE_LAUNCH_TOKEN.get() == launchToken
                        && (System.currentTimeMillis() - waitStart) < PRELAUNCH_WINDOW_WAIT_MAX_MS) {
                    long waited = System.currentTimeMillis() - waitStart;
                    final int dynamicPct = Math.min(94, 70 + (int) ((waited * 24L) / PRELAUNCH_WINDOW_WAIT_MAX_MS));
                    if (hv != null) {
                        activity.runOnUiThread(() -> {
                            if (ACTIVE_LAUNCH_TOKEN.get() == launchToken) {
                                hv.updateGameLaunchModal(
                                        "Opening " + targetTitle + "…",
                                        "PC audio muted & screen shielded until the game window is ready",
                                        "gamepad",
                                        2,
                                        dynamicPct);
                            }
                        });
                    }
                    try {
                        HttpResult stRes = rawHttpRequestWithStatus(
                                appCtx, laptopIp, LAPTOP_AGENT_PORT, "GET", "/api/gaming/launch-status", null, 2000);
                        if (stRes.isSuccess() && !stRes.body.isEmpty()) {
                            JSONObject stJson = new JSONObject(stRes.body);
                            if (stJson.optBoolean("gameWindowReady", false)) {
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(PRELAUNCH_WINDOW_POLL_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            if (!laptopLockedNeedsPin && ACTIVE_LAUNCH_TOKEN.get() == launchToken) {
                long elapsedPrep = System.currentTimeMillis() - prepStartedMs;
                if (elapsedPrep < MIN_LAUNCH_STAGE_BEAT_MS) {
                    try {
                        Thread.sleep(MIN_LAUNCH_STAGE_BEAT_MS - elapsedPrep);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            if (ACTIVE_LAUNCH_TOKEN.get() != launchToken) return;

            final LaptopStatus cached = getCachedLaptopStatus(appCtx);
            boolean sunshineFallback = false;
            if (!agentReached && !laptopAuthRejected && !laptopLockedNeedsPin) {
                try {
                    java.io.File idDir = new java.io.File(appCtx.getFilesDir(), "sunshine_mtls");
                    if (checkSunshineDirectFallbackSync(
                            appCtx,
                            idDir,
                            laptopIp,
                            SunshineStreamClient.SUNSHINE_HTTP_PORT,
                            SunshineStreamClient.SUNSHINE_HTTPS_PORT)) {
                        sunshineFallback = true;
                        INFLIGHT_PRELAUNCH_SESSION.set(null);
                        if (respSunshineApp.trim().isEmpty()) {
                            respSunshineApp = "desktop".equalsIgnoreCase(targetId)
                                    ? "Desktop"
                                    : ("steam_bigpicture".equalsIgnoreCase(targetId) ? "Steam Big Picture" : targetTitle);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            final boolean useSunshineFallback = sunshineFallback;
            final String effectiveUuid = !respSunshineUuid.trim().isEmpty()
                    ? respSunshineUuid.trim()
                    : cached.sunshineHostUuid;
            final String effectiveHost = !respSunshineHost.trim().isEmpty()
                    ? respSunshineHost.trim()
                    : cached.sunshineHostName;
            final String effectiveApp = respSunshineApp;
            final boolean routeHdmi = laptopReportsHdmi;
            final boolean promptPin = laptopLockedNeedsPin;
            final boolean authRejected = laptopAuthRejected;
            final boolean reached = agentReached;

            activity.runOnUiThread(() -> {
                if (ACTIVE_LAUNCH_TOKEN.get() != launchToken || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (authRejected) {
                    if (hv != null) hv.closeGameLaunchModal();
                    Toast.makeText(
                            activity,
                            "Gaming PC requires 6-digit pairing before launching games.",
                            Toast.LENGTH_LONG).show();
                    promptLaptopPairingDialog(activity, fromView, null);
                    return;
                }
                if (promptPin) {
                    if (hv != null) hv.closeGameLaunchModal();
                    showPinDialog(activity, fromView, cached, game);
                    return;
                }
                if (!reached && !useSunshineFallback) {
                    if (hv != null) hv.closeGameLaunchModal();
                    Toast.makeText(
                            activity,
                            "Waking Gaming PC (" + laptopIp + ") — wait a few seconds and press OK again.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (routeHdmi) {
                    List<HomeApps.Item> refreshedInputs = HomeApps.allInputs(activity);
                    final HomeApps.Item targetHdmi = findLaptopHdmiInput(
                            refreshedInputs,
                            getHdmiPortConfigs(activity),
                            true
                    );
                    if (targetHdmi != null) {
                        if (hv != null) {
                            LaunchModalStep hdmiStep = LaunchStage.CONNECTING_STREAM.buildStep(game, targetHdmi.label);
                            hv.updateGameLaunchModal(
                                    hdmiStep.headline,
                                    hdmiStep.subtitle,
                                    hdmiStep.iconName,
                                    hdmiStep.stageIndex,
                                    hdmiStep.progressPercent
                            );
                        }
                        View scheduler = fromView != null ? fromView : activity.getWindow().getDecorView();
                        scheduler.postDelayed(() -> {
                            if (ACTIVE_LAUNCH_TOKEN.get() != launchToken || activity.isFinishing() || activity.isDestroyed()) {
                                return;
                            }
                            if (hv != null) hv.closeGameLaunchModal();
                            INFLIGHT_PRELAUNCH_SESSION.set(null);
                            setActiveStreamingSession(appCtx, new ActiveStreamingSession(
                                    targetId,
                                    targetTitle,
                                    laptopIp,
                                    LAPTOP_AGENT_PORT,
                                    System.currentTimeMillis()
                            ));
                            View anchor = fromView != null ? fromView : activity.getWindow().getDecorView();
                            HomeApps.open(activity, targetHdmi, anchor, anchor.getWidth() / 2, anchor.getHeight() / 2, 1, 1);
                        }, LAUNCH_HANDOFF_HOLD_MS);
                        return;
                    }
                }

                if (hv != null) {
                    LaunchModalStep streamStep = LaunchStage.CONNECTING_STREAM.buildStep(game, null);
                    hv.updateGameLaunchModal(
                            streamStep.headline,
                            streamStep.subtitle,
                            streamStep.iconName,
                            streamStep.stageIndex,
                            streamStep.progressPercent
                    );
                }

                final String sunshineTargetApp = (effectiveApp != null && !effectiveApp.trim().isEmpty())
                        ? effectiveApp.trim()
                        : "Desktop";
                View scheduler = fromView != null ? fromView : activity.getWindow().getDecorView();
                scheduler.postDelayed(() -> {
                    if (ACTIVE_LAUNCH_TOKEN.get() != launchToken || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    INFLIGHT_PRELAUNCH_SESSION.set(null);
                    if (hv != null) hv.closeGameLaunchModal();
                    Recs.setBackgroundActivityPaused(true);
                    ScreensaverCatalog.setBackgroundActivityPaused(true);
                    try {
                        Recs.get(appCtx).trim();
                    } catch (Throwable ignored) {
                    }
                    trimMemoryForStreaming();

                    setActiveStreamingSession(appCtx, new ActiveStreamingSession(
                            targetId,
                            targetTitle,
                            laptopIp,
                            LAPTOP_AGENT_PORT,
                            System.currentTimeMillis()
                    ));
                    Intent streamIntent = GameStreamActivity.buildIntent(
                            activity,
                            laptopIp,
                            targetId,
                            targetTitle,
                            sunshineTargetApp);
                    activity.startActivity(streamIntent);
                    activity.overridePendingTransition(0, 0);
                }, 180L);
            });
        });
    }

    static boolean checkSunshineDirectFallbackSync(
            Context context,
            java.io.File identityDir,
            String host,
            int sunshineHttpPort,
            int sunshineHttpsPort) {
        if (host == null || host.trim().isEmpty() || !SunshineStreamClient.isAllowedCleartextLanHost(host)) {
            return false;
        }
        String cleanHost = host.trim();
        try {
            String sunXml = SunshineStreamClient.fetchServerInfoUnauthenticatedXml(
                    cleanHost,
                    sunshineHttpPort > 0 ? sunshineHttpPort : SunshineStreamClient.SUNSHINE_HTTP_PORT,
                    800,
                    1500);
            String sunUuid = SunshineStreamClient.extractXmlTag(sunXml, "uniqueid");
            String sunHost = SunshineStreamClient.extractXmlTag(sunXml, "hostname");
            if (sunUuid.isEmpty()) {
                return false;
            }
            if (context != null) {
                saveSunshineHostIdentity(context, sunUuid, sunHost);
            }
            if (identityDir == null && context != null) {
                identityDir = new java.io.File(context.getFilesDir(), "sunshine_mtls");
            }
            if (identityDir == null) {
                return false;
            }
            SunshineStreamClient.ClientIdentity identity = SunshineStreamClient.loadOrCreateIdentity(identityDir);
            javax.net.ssl.SSLSocketFactory sslFactory =
                    SunshineStreamClient.getOrCreateMtlsSocketFactory(identity);
            return SunshineStreamClient.fetchPairedServerInfoXml(
                    cleanHost,
                    sunshineHttpsPort > 0 ? sunshineHttpsPort : SunshineStreamClient.SUNSHINE_HTTPS_PORT,
                    identity,
                    sslFactory) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Activates the Gaming Zone from the TV Launcher: sends Wake-on-LAN + S0 wake signal, probes
     * the laptop's status, and opens the interactive Liquid Glass Gaming Zone Picker on the TV.
     */
    public static String launch(final Activity activity, final View fromView) {
        final Context appCtx = activity.getApplicationContext();
        sendWakeOnLanAsync(appCtx, Config.GAMING_PC_MAC);
        GAMING_RPC_IO.execute(() -> {
            String host = resolveReachableLaptopIpSync(appCtx, FAST_SAME_SUBNET_PROBE_MS);
            try {
                rawHttpRequest(appCtx, host, LAPTOP_AGENT_PORT, "POST", "/api/gaming/wake", "{}", 1200);
            } catch (Exception ignored) {
            }
            final LaptopStatus status = fetchLaptopStatusSync(appCtx);
            activity.runOnUiThread(() -> showGamingPickerDialog(activity, fromView, status, false));
        });

        return "Opening Gaming Zone · Checking Laptop (HDMI / Wireless)…";
    }

    private static void showGamingPickerDialog(
            final Activity activity,
            final View fromView,
            final LaptopStatus status,
            final boolean showHidden
    ) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final HomeView hv = HomeView.findFrom(activity, fromView);
        if (hv == null) return;

        final Set<String> hiddenIds = getHiddenGameIds(activity);
        final List<GameItem> displayedGames = filterGames(status.games, hiddenIds, showHidden);
        final HomeApps.Item laptopHdmi = findLaptopHdmiInput(
                HomeApps.allInputs(activity),
                getHdmiPortConfigs(activity),
                status.hdmiConnected
        );
        final boolean isHdmi = status.hdmiConnected && (laptopHdmi != null);

        String connBadge = isHdmi
                ? "HDMI Cable (" + laptopHdmi.label + ")"
                : (status.awake ? "Wireless · PC Game Stream Ready" : "Sleeping · Wake Sent");
        if (status.locked) {
            connBadge += " · Windows Locked";
        }

        final List<String> rows = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (status.locked) {
            rows.add("Unlock Windows Laptop (Enter Alphanumeric PIN)");
            actions.add(() -> showPinDialog(activity, fromView, status, null));
        }

        for (final GameItem game : displayedGames) {
            String prefix = "system".equals(game.category)
                    ? "[PC] "
                    : ("steam".equals(game.category)
                            ? "[Steam] "
                            : ("heroic".equals(game.category)
                                    ? "[Heroic] "
                                    : (("epic".equals(game.category) || "legendary".equals(game.category))
                                            ? "[Epic] "
                                            : "[Game] ")));
            String suffix = game.hidden ? " [Hidden - Click to Unhide]" : "";
            rows.add(prefix + game.title + suffix);
            actions.add(() -> {
                if (game.hidden) {
                    setGameHidden(activity, game.id, false);
                    Toast.makeText(activity, "Restored " + game.title + " to Gaming Zone", Toast.LENGTH_SHORT).show();
                    GAMING_RPC_IO.execute(() -> {
                        final LaptopStatus updated = fetchLaptopStatusSync(activity.getApplicationContext());
                        activity.runOnUiThread(() -> showGamingPickerDialog(activity, fromView, updated, showHidden));
                    });
                } else if (status.locked) {
                    showPinDialog(activity, fromView, status, game);
                } else {
                    launchGameOnTvAndLaptop(activity, fromView, game, null);
                }
            });
        }

        rows.add("Pair Gaming PC (6-Digit Code)…");
        actions.add(() -> promptLaptopPairingDialog(activity, fromView, null));

        rows.add("Gaming PC IP Address (" + getLaptopIp(activity) + ")…");
        actions.add(() -> promptLaptopIpDialog(activity, fromView, null));

        rows.add("HDMI Ports Setup (Consoles, Streamers & Laptop)…");
        actions.add(() -> showHdmiPortsSetupDialog(activity, null));

        rows.add(showHidden ? "Hide Shown Hidden Games" : "Show / Restore Hidden Games");
        actions.add(() -> showGamingPickerDialog(activity, fromView, status, !showHidden));

        rows.add("Hide a Game Manually…");
        actions.add(() -> showHideGameSelectorDialog(activity, fromView, status));

        hv.openLiquidGlassChoiceModal(
                "Gaming Zone",
                connBadge,
                rows.toArray(new String[0]),
                null,
                "Close",
                which -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                },
                null
        );
    }

    /** Opens the Liquid Glass modal to configure the Gaming PC LAN IP override. */
    public static void promptLaptopIpDialog(
            final Activity activity,
            final View fromView,
            final Runnable onSaved
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final HomeView hv = HomeView.findFrom(activity, fromView);
        if (hv == null) return;
        String currentIp = getLaptopIp(activity);
        hv.openLiquidGlassInputModal(
                "Gaming PC IP Address",
                "Enter your Windows Gaming PC LAN IPv4 address (port " + LAPTOP_AGENT_PORT + "):",
                "e.g. 192.168.1.100",
                currentIp,
                false,
                "Save IP",
                entered -> {
                    String clean = entered != null ? entered.trim() : "";
                    if (!clean.isEmpty()) {
                        setLaptopIp(activity, clean);
                        Toast.makeText(activity, "Gaming PC IP saved: " + clean, Toast.LENGTH_SHORT).show();
                        refreshLaptopStatusAsync(activity, onSaved);
                    }
                },
                null
        );
    }

    /** Requests a 6-digit pairing code on the Gaming PC and opens the Liquid Glass verification modal. */
    public static void promptLaptopPairingDialog(
            final Activity activity,
            final View fromView,
            final Runnable onPaired
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final HomeView hv = HomeView.findFrom(activity, fromView);
        if (hv == null) return;
        final Context appCtx = activity.getApplicationContext();
        Toast.makeText(activity, "Requesting 6-digit pairing code on Gaming PC…", Toast.LENGTH_SHORT).show();
        GAMING_RPC_IO.execute(() -> {
            final boolean requested = requestLaptopPairingSync(appCtx);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String sub = requested
                        ? "Enter the 6-digit code shown on your Gaming PC screen:"
                        : "Enter the 6-digit pairing code from your Gaming PC (check PC agent is running):";
                hv.openLiquidGlassInputModal(
                        "Pair Gaming PC",
                        sub,
                        "6-digit code",
                        "",
                        false,
                        "Verify & Pair",
                        code -> {
                            final String cleanCode = code != null ? code.trim() : "";
                            if (cleanCode.isEmpty()) return;
                            GAMING_RPC_IO.execute(() -> {
                                final boolean ok = verifyLaptopPairingSync(appCtx, cleanCode);
                                activity.runOnUiThread(() -> {
                                    Toast.makeText(
                                            activity,
                                            ok ? "Gaming PC paired securely!" : "Pairing failed — check code and try again.",
                                            Toast.LENGTH_SHORT
                                    ).show();
                                    if (ok) {
                                        refreshLaptopStatusAsync(appCtx, onPaired);
                                    }
                                });
                            });
                        },
                        null
                );
            });
        });
    }

    public static void showPinDialog(
            final Activity activity,
            final View fromView,
            final LaptopStatus status,
            final GameItem gameToLaunchAfter
    ) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        final HomeView hv = HomeView.findFrom(activity, fromView);
        if (hv == null) return;

        String title = gameToLaunchAfter != null
                ? "Unlock Windows & Launch " + gameToLaunchAfter.title
                : "Unlock Windows Laptop";

        hv.openLiquidGlassInputModal(
                title,
                "Enter your Windows Hello PIN (digits or letters):",
                "Windows Hello PIN",
                "",
                true,
                "Unlock & Run",
                enteredPin -> {
                    final String pin = enteredPin != null ? enteredPin.trim() : "";
                    if (pin.isEmpty()) return;
                    if (gameToLaunchAfter != null) {
                        launchGameOnTvAndLaptop(activity, fromView, gameToLaunchAfter, pin);
                    } else {
                        final Context appCtx = activity.getApplicationContext();
                        GAMING_RPC_IO.execute(() -> {
                            final boolean ok = unlockLaptopSync(appCtx, pin);
                            final LaptopStatus updated = fetchLaptopStatusSync(appCtx);
                            activity.runOnUiThread(() -> {
                                Toast.makeText(
                                        activity,
                                        ok ? "Windows Unlocked!" : "PIN sent to Windows Lock Screen",
                                        Toast.LENGTH_SHORT
                                ).show();
                                if (status != null) {
                                    showGamingPickerDialog(activity, fromView, updated, false);
                                }
                            });
                        });
                    }
                },
                null
        );
    }

    private static void showHideGameSelectorDialog(
            final Activity activity,
            final View fromView,
            final LaptopStatus status
    ) {
        final HomeView hv = HomeView.findFrom(activity, fromView);
        if (hv == null) return;
        final Set<String> hiddenIds = getHiddenGameIds(activity);
        final List<GameItem> visible = filterGames(status.games, hiddenIds, false);
        if (visible.isEmpty()) return;

        String[] labels = new String[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            labels[i] = "Hide: " + visible.get(i).title;
        }
        hv.openLiquidGlassChoiceModal(
                "Select a Game to Hide",
                "Temporarily hide a game from the Gaming Zone row",
                labels,
                null,
                "Back",
                which -> {
                    if (which >= 0 && which < visible.size()) {
                        GameItem chosen = visible.get(which);
                        setGameHidden(activity, chosen.id, true);
                        Toast.makeText(
                                activity,
                                "Hidden " + chosen.title + " (use 'Show / Restore Hidden Games' to re-show)",
                                Toast.LENGTH_SHORT
                        ).show();
                        showGamingPickerDialog(activity, fromView, status, false);
                    }
                },
                () -> showGamingPickerDialog(activity, fromView, status, false)
        );
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "").trim();
    }

    /**
     * Minimal raw-Socket HTTP client for LAN communication with the Laptop Gaming Agent (:8485).
     * Attaches `X-SoarTV-Token` automatically whenever a paired token is available in `Secrets` or `Prefs`.
     */
    /**
     * Structured HTTP status + body wrapper for Laptop Gaming Agent (`:8485`) requests so `401 Unauthorized`
     * or `{"ok":false}` responses are never mistaken for successful game launches.
     */
    public static final class HttpResult {
        public final int statusCode;
        public final String body;

        public HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body != null ? body : "";
        }

        public boolean isSuccess() {
            if (statusCode < 200 || statusCode >= 300) return false;
            if (body.contains("\"ok\":false") || body.contains("\"ok\": false")) return false;
            return true;
        }
    }

    public static HttpResult rawHttpRequestWithStatus(
            Context context,
            String host,
            int port,
            String method,
            String path,
            String jsonBody,
            int timeoutMs
    ) throws IOException {
        return rawHttpRequestWithStatus(context, host, port, method, path, jsonBody, timeoutMs, timeoutMs);
    }

    public static HttpResult rawHttpRequestWithStatus(
            Context context,
            String host,
            int port,
            String method,
            String path,
            String jsonBody,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws IOException {
        byte[] bodyBytes = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
        String tok = sanitizeHeaderValue(getLaptopAuthToken(context));
        String authHeader = tok.isEmpty() ? "" : ("X-SoarTV-Token: " + tok + "\r\n");
        String tvPortHeader = "X-SoarTV-Tv-Port: " + Config.REMOTE_SERVER_PORT + "\r\n";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String reqHeader = method + " " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + authHeader
                    + tvPortHeader
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            byte[] headerBytes = reqHeader.getBytes(StandardCharsets.ISO_8859_1);
            byte[] wirePacket = new byte[headerBytes.length + bodyBytes.length];
            System.arraycopy(headerBytes, 0, wirePacket, 0, headerBytes.length);
            if (bodyBytes.length > 0) {
                System.arraycopy(bodyBytes, 0, wirePacket, headerBytes.length, bodyBytes.length);
            }
            out.write(wirePacket);
            out.flush();

            ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int headerEnd = -1;
            int expectedBodyLen = -1;
            int n;
            while ((n = in.read(chunk)) > 0) {
                respBuf.write(chunk, 0, n);
                if (headerEnd < 0) {
                    byte[] cur = respBuf.toByteArray();
                    for (int i = 0; i <= cur.length - 4; i++) {
                        if (cur[i] == '\r' && cur[i + 1] == '\n' && cur[i + 2] == '\r' && cur[i + 3] == '\n') {
                            headerEnd = i;
                            String hdrs = new String(cur, 0, headerEnd, StandardCharsets.ISO_8859_1);
                            for (String line : hdrs.split("\r\n")) {
                                int cIdx = line.indexOf(':');
                                if (cIdx > 0 && "content-length".equalsIgnoreCase(line.substring(0, cIdx).trim())) {
                                    try {
                                        expectedBodyLen = Integer.parseInt(line.substring(cIdx + 1).trim());
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
                if (headerEnd >= 0 && expectedBodyLen >= 0 && (respBuf.size() - (headerEnd + 4)) >= expectedBodyLen) {
                    break;
                }
            }
            String full = new String(respBuf.toByteArray(), StandardCharsets.UTF_8);
            int statusCode = 200;
            int firstLineEnd = full.indexOf("\r\n");
            if (firstLineEnd > 0) {
                String statusLine = full.substring(0, firstLineEnd).trim();
                String[] parts = statusLine.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        statusCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            int split = full.indexOf("\r\n\r\n");
            String body = split >= 0 ? full.substring(split + 4).trim() : full.trim();
            return new HttpResult(statusCode, body);
        }
    }

    private static String rawHttpRequest(
            Context context,
            String host,
            int port,
            String method,
            String path,
            String jsonBody,
            int timeoutMs
    ) throws IOException {
        HttpResult res = rawHttpRequestWithStatus(context, host, port, method, path, jsonBody, timeoutMs);
        if (res.statusCode >= 400) {
            throw new IOException("HTTP " + res.statusCode + " from " + path);
        }
        return res.body;
    }
}
