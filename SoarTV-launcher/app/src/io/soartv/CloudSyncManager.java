package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 3-Tier Cloud Settings & Automatic Zero-Knowledge Vault Sync Coordinator for SoarTV v2.
 *
 * <p><b>Why:</b> Keeps every household TV (Android TV, Google TV, LG webOS, Samsung Tizen) synchronized
 * across family user profiles and devices while staying 100% offline-first and zero-PC-dependent:
 * <ul>
 *   <li><b>Tier 1 (Household):</b> Shared API keys, Smart-Home credentials &amp; WiZ MAC→Room map,
 *       paired Companion Phones, paired Gaming PCs, and Bluetooth controller tags.</li>
 *   <li><b>Tier 2 (User Profile):</b> Theme, Language/Units, per-profile Trakt &amp; Stremio logins,
 *       Watch List/History/Votes, Genre Orders, Hidden Items/Games, and default canonical Favorite Apps,
 *       Hidden Apps &amp; Screensaver settings.</li>
 *   <li><b>Tier 3 (Per-TV Device):</b> Room location, optional tabs (Gaming/Smart-Home/Reels),
 *       Gaming stream quality, optional per-TV overrides for Favorite Apps/Hidden Apps/Screensaver,
 *       live LAN IP, and brand-locked hardware isolation.</li>
 * </ul>
 *
 * <p><b>What:</b> Implements the Mid-Way Sync cadence (2.5s debounced auto-push on edits, instant pull on
 * boot/wake/profile-switch, lightweight 15-minute active-only periodic check paused during sleep/gaming),
 * deterministic field-level Last-Write-Wins merging, automatic Household-Key AES-256-GCM multi-slot vault
 * encryption/decryption, and guided Replace-Existing-TV portable setting migration.
 */
public final class CloudSyncManager {

    /** Synchronization tier matching the 3 Supabase v2 scopes. */
    public enum Scope {
        HOUSEHOLD,
        PROFILE,
        TV_DEVICE
    }

    /** Outcome of a deterministic field-level Last-Write-Wins merge between local and remote documents. */
    public static final class MergeResult {
        public final Map<String, Object> mergedValues;
        public final Map<String, Long> mergedTimestamps;
        public final boolean localChanged;
        public final boolean remoteNeedsPush;

        public MergeResult(
                Map<String, Object> mergedValues,
                Map<String, Long> mergedTimestamps,
                boolean localChanged,
                boolean remoteNeedsPush) {
            this.mergedValues = Collections.unmodifiableMap(new LinkedHashMap<>(mergedValues));
            this.mergedTimestamps = Collections.unmodifiableMap(new LinkedHashMap<>(mergedTimestamps));
            this.localChanged = localChanged;
            this.remoteNeedsPush = remoteNeedsPush;
        }
    }

    /** Result summary surfaced in Settings UI. */
    public static final class SyncSummary {
        public final long lastSyncAtMs;
        public final String statusText;
        public final boolean syncing;
        public final boolean configured;

        public SyncSummary(long lastSyncAtMs, String statusText, boolean syncing, boolean configured) {
            this.lastSyncAtMs = lastSyncAtMs;
            this.statusText = statusText == null ? "" : statusText;
            this.syncing = syncing;
            this.configured = configured;
        }
    }

    /** Debounce window before pushing local edits to Supabase (2.5 seconds). */
    public static final long PUSH_DEBOUNCE_MS = 2_500L;
    /** Lightweight periodic sync interval while SoarTV is actively displayed (15 minutes). */
    public static final long PERIODIC_SYNC_INTERVAL_MS = 15L * 60_000L;
    /** Minimum spacing between automatic resume pulls to avoid rapid activity-switch storms. */
    public static final long MIN_RESUME_SYNC_GAP_MS = 30_000L;

    /** Portable per-TV setting keys safely copied when replacing an old TV (even across brands). */
    public static final Set<String> PORTABLE_TV_SETTING_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "room_name",
            "enable_gaming",
            "enable_smarthome",
            "enable_reels",
            "gaming_stream",
            "override_shelf",
            "shelf_apps",
            "app_order",
            "override_hidden_apps",
            "hidden_apps",
            "perm_hidden_apps",
            "override_screensaver",
            "screensaver",
            "ui_prefs"
    )));

    private static final String SYNC_META_PREFS = "soartv_cloud_sync_meta";
    private static final String KEY_HH_TIMESTAMPS = "ts_household";
    private static final String KEY_TV_TIMESTAMPS = "ts_tv_device";
    private static final String KEY_PROFILE_TS_PREFIX = "ts_profile_";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_STATUS = "last_sync_status";
    private static final String KEY_CLOUD_SHELF_CACHE = "cloud_canonical_shelf_";
    private static final String KEY_CLOUD_HIDDEN_CACHE = "cloud_canonical_hidden_";
    private static final String KEY_CLOUD_PERM_HIDDEN_CACHE = "cloud_canonical_perm_hidden_";
    private static final String KEY_DIRTY_HH_SECRETS = "dirty_hh_secrets";
    private static final String KEY_DIRTY_PROF_SECRETS_PREFIX = "dirty_prof_secrets_";

    private static volatile CloudSyncManager instance;

    private final Context appContext;
    private final ExecutorService syncIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soartv-cloud-sync");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "soartv-cloud-sched");
        t.setDaemon(true);
        return t;
    });

    private final Object stateLock = new Object();
    private final EnumSet<Scope> dirtyScopes = EnumSet.noneOf(Scope.class);
    private ScheduledFuture<?> debouncedPushTask;
    private ScheduledFuture<?> periodicSyncTask;

    private volatile boolean homeActive = false;
    private volatile boolean backgroundPaused = false;
    private volatile boolean syncing = false;
    private volatile boolean applyingRemote = false;
    private volatile long lastSyncAtMs = 0L;
    private volatile String lastStatus = "Not synced yet";
    private volatile Runnable uiRefreshCallback;

    private CloudSyncManager(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        SharedPreferences meta = metaPrefs();
        if (meta != null) {
            this.lastSyncAtMs = meta.getLong(KEY_LAST_SYNC_MS, 0L);
            this.lastStatus = meta.getString(KEY_LAST_STATUS, "Not synced yet");
        }
    }

    /** Returns the singleton {@link CloudSyncManager}. */
    public static CloudSyncManager get(Context context) {
        CloudSyncManager cur = instance;
        if (cur == null) {
            synchronized (CloudSyncManager.class) {
                cur = instance;
                if (cur == null) {
                    cur = new CloudSyncManager(context);
                    instance = cur;
                }
            }
        }
        return cur;
    }

    /** Registers a UI callback (typically {@link HomeView}) invoked on the main thread after remote changes apply. */
    public void setUiRefreshCallback(Runnable callback) {
        this.uiRefreshCallback = callback;
    }

    /** Returns current cloud sync status summary for Settings display. */
    public SyncSummary getSummary() {
        boolean configured = isCloudSyncConfigured();
        return new SyncSummary(lastSyncAtMs, lastStatus, syncing, configured);
    }

    /** Returns {@code true} when Supabase URL, Anon Key, and Household Key are all available. */
    public boolean isCloudSyncConfigured() {
        if (appContext == null) return false;
        SupabaseClient sb = SupabaseClient.get(appContext);
        if (!sb.isConfigured()) return false;
        String hk = sb.resolveHouseholdKey();
        return hk != null && hk.trim().length() >= 16;
    }

    // MARK: - Pure Testable Merge & Scheduling Algorithms

    /**
     * Performs deterministic field-level Last-Write-Wins (LWW) merge between a local and remote settings map.
     *
     * <p><b>Why:</b> If Living Room TV edits Genre Order while Bedroom TV edits Theme simultaneously,
     * merging per field using epoch timestamps guarantees neither edit overwrites the other.
     */
    public static MergeResult mergeFieldMaps(
            Map<String, Object> localValues,
            Map<String, Long> localTimestamps,
            Map<String, Object> remoteValues,
            Map<String, Long> remoteTimestamps) {
        Map<String, Object> lv = localValues == null ? Collections.emptyMap() : localValues;
        Map<String, Long> lt = localTimestamps == null ? Collections.emptyMap() : localTimestamps;
        Map<String, Object> rv = remoteValues == null ? Collections.emptyMap() : remoteValues;
        Map<String, Long> rt = remoteTimestamps == null ? Collections.emptyMap() : remoteTimestamps;

        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(lv.keySet());
        allKeys.addAll(rv.keySet());

        Map<String, Object> mergedVals = new LinkedHashMap<>();
        Map<String, Long> mergedTs = new LinkedHashMap<>();
        boolean localChanged = false;
        boolean remoteNeedsPush = false;

        for (String key : allKeys) {
            boolean hasLocal = lv.containsKey(key);
            boolean hasRemote = rv.containsKey(key);
            long localTime = lt.containsKey(key) && lt.get(key) != null ? lt.get(key) : 0L;
            long remoteTime = rt.containsKey(key) && rt.get(key) != null ? rt.get(key) : 0L;

            if (hasLocal && !hasRemote) {
                mergedVals.put(key, lv.get(key));
                mergedTs.put(key, localTime > 0L ? localTime : 1L);
                remoteNeedsPush = true;
            } else if (!hasLocal && hasRemote) {
                mergedVals.put(key, rv.get(key));
                mergedTs.put(key, remoteTime > 0L ? remoteTime : 1L);
                localChanged = true;
            } else {
                Object lObj = lv.get(key);
                Object rObj = rv.get(key);
                if (remoteTime > localTime) {
                    mergedVals.put(key, rObj);
                    mergedTs.put(key, remoteTime);
                    if (!deepJsonEquals(lObj, rObj)) {
                        localChanged = true;
                    }
                } else if (localTime > remoteTime) {
                    mergedVals.put(key, lObj);
                    mergedTs.put(key, localTime);
                    if (!deepJsonEquals(lObj, rObj)) {
                        remoteNeedsPush = true;
                    }
                } else {
                    // Equal timestamps: prefer remote if non-zero timestamp and values differ (convergent tie-break)
                    if (remoteTime > 0L && !deepJsonEquals(lObj, rObj)) {
                        mergedVals.put(key, rObj);
                        mergedTs.put(key, remoteTime);
                        localChanged = true;
                    } else {
                        mergedVals.put(key, lObj);
                        mergedTs.put(key, localTime);
                    }
                }
            }
        }
        return new MergeResult(mergedVals, mergedTs, localChanged, remoteNeedsPush);
    }

    /**
     * Merges decrypted local and remote vault secrets cleanly: remote supplies any missing or updated keys,
     * while keys explicitly modified locally since the last sync retain their local value and trigger upload.
     */
    public static Map<String, String> mergeSecretMaps(
            Map<String, String> localSecrets,
            Map<String, String> remoteSecrets,
            Set<String> locallyDirtyKeys) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (remoteSecrets != null) {
            for (Map.Entry<String, String> e : remoteSecrets.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().trim().isEmpty()) {
                    merged.put(e.getKey(), e.getValue().trim());
                }
            }
        }
        if (localSecrets != null) {
            for (Map.Entry<String, String> e : localSecrets.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null || v.trim().isEmpty()) continue;
                boolean dirty = locallyDirtyKeys != null && locallyDirtyKeys.contains(k);
                if (dirty || !merged.containsKey(k)) {
                    merged.put(k, v.trim());
                }
            }
        }
        return merged;
    }

    /**
     * Filters a source TV's {@code tv_settings} map down to ONLY cross-brand portable settings for
     * the "Replace Existing TV" onboarding flow (stripping hardware/brand-locked keys automatically).
     */
    public static Map<String, Object> extractPortableTvSettings(Map<String, Object> sourceTvSettings) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sourceTvSettings == null || sourceTvSettings.isEmpty()) return out;
        for (Map.Entry<String, Object> e : sourceTvSettings.entrySet()) {
            if (PORTABLE_TV_SETTING_KEYS.contains(e.getKey()) && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Enforces the Mid-Way Sync invariant: periodic polling runs ONLY when SoarTV is actively displayed,
     * not paused for gaming/sleep/external apps, and at least {@code intervalMs} has elapsed since last sync.
     */
    public static boolean shouldRunPeriodicSync(
            boolean homeActive,
            boolean backgroundPaused,
            long lastSyncAtMs,
            long nowMs,
            long intervalMs) {
        if (!homeActive || backgroundPaused) return false;
        long effectiveInterval = Math.max(60_000L, intervalMs);
        return lastSyncAtMs <= 0L || (nowMs - lastSyncAtMs) >= effectiveInterval;
    }

    private static boolean deepJsonEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        String ja = CloudVaultCrypto.JsonCodec.toJsonObjectString(Collections.singletonMap("v", a));
        String jb = CloudVaultCrypto.JsonCodec.toJsonObjectString(Collections.singletonMap("v", b));
        return ja.equals(jb);
    }

    // MARK: - Local Mutation Hooks & Mid-Way Sync Lifecycle

    /**
     * Records a local setting mutation timestamp for field-level LWW merging and schedules a 2.5s debounced push.
     * Ignored automatically while applying remote changes so remote pulls never echo back.
     */
    public void recordLocalFieldChange(Scope scope, String fieldKey) {
        if (applyingRemote || appContext == null || scope == null || fieldKey == null || fieldKey.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String profileId = activeProfileId();
        Map<String, Long> ts = loadTimestamps(scope, profileId);
        ts.put(fieldKey.trim(), now);
        saveTimestamps(scope, profileId, ts);
        scheduleDebouncedPush(scope);
    }

    /** Marks a secret key as modified locally so the next sync pushes it into the encrypted cloud vault. */
    public void recordLocalSecretChange(Scope scope, String secretKey) {
        if (applyingRemote || appContext == null || scope == null || secretKey == null || secretKey.trim().isEmpty()) {
            return;
        }
        SharedPreferences meta = metaPrefs();
        if (meta != null) {
            String prefKey = scope == Scope.HOUSEHOLD
                    ? KEY_DIRTY_HH_SECRETS
                    : (KEY_DIRTY_PROF_SECRETS_PREFIX + activeProfileId());
            Set<String> dirty = new HashSet<>(meta.getStringSet(prefKey, Collections.emptySet()));
            dirty.add(secretKey.trim());
            meta.edit().putStringSet(prefKey, dirty).apply();
        }
        scheduleDebouncedPush(scope);
    }

    /** Schedules a debounced background push for {@code scope} (coalescing rapid clicks within 2.5 seconds). */
    public void scheduleDebouncedPush(Scope scope) {
        if (applyingRemote || appContext == null || scope == null) return;
        synchronized (stateLock) {
            dirtyScopes.add(scope);
            if (debouncedPushTask != null) {
                debouncedPushTask.cancel(false);
            }
            debouncedPushTask = scheduler.schedule(() -> {
                EnumSet<Scope> toPush;
                synchronized (stateLock) {
                    if (dirtyScopes.isEmpty()) return;
                    toPush = EnumSet.copyOf(dirtyScopes);
                    dirtyScopes.clear();
                    debouncedPushTask = null;
                }
                syncIo.execute(() -> pushDirtyScopesSync(toPush));
            }, PUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Called when {@link HomeActivity} resumes: starts the 15-minute active poll and pulls if stale. */
    public void onHomeResumed() {
        homeActive = true;
        backgroundPaused = false;
        long now = System.currentTimeMillis();
        if (now - lastSyncAtMs >= MIN_RESUME_SYNC_GAP_MS) {
            syncNowAsync("resume", null);
        }
        synchronized (stateLock) {
            if (periodicSyncTask == null || periodicSyncTask.isCancelled()) {
                periodicSyncTask = scheduler.scheduleWithFixedDelay(() -> {
                    if (shouldRunPeriodicSync(homeActive, backgroundPaused, lastSyncAtMs,
                            System.currentTimeMillis(), PERIODIC_SYNC_INTERVAL_MS)) {
                        syncNowAsync("periodic", null);
                    }
                }, PERIODIC_SYNC_INTERVAL_MS, PERIODIC_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Called when {@link HomeActivity} pauses or enters sleep: stops periodic polling immediately. */
    public void onHomePaused() {
        homeActive = false;
        synchronized (stateLock) {
            if (periodicSyncTask != null) {
                periodicSyncTask.cancel(false);
                periodicSyncTask = null;
            }
        }
    }

    /** Pauses periodic sync during Moonlight game streaming or foreground third-party media apps. */
    public void setBackgroundPaused(boolean paused) {
        this.backgroundPaused = paused;
    }

    /** Triggered immediately when {@link TvDeviceIdentity} detects a Wi-Fi / DHCP / Ethernet IP change. */
    public void reportLanIpChangedAsync(String newIp) {
        if (appContext == null || newIp == null || newIp.trim().isEmpty()) return;
        syncIo.execute(() -> {
            if (!isCloudSyncConfigured()) return;
            try {
                TvDeviceIdentity id = TvDeviceIdentity.get(appContext);
                TvDeviceProfile profile = TvDeviceProfile.current();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("device_id", id.getDeviceId());
                payload.put("device_name", id.getDeviceName());
                payload.put("room_name", SmartHomeManager.get(appContext).getTvRoom());
                payload.put("platform", TvDeviceIdentity.PLATFORM_ANDROID_TV);
                payload.put("brand", profile.getBrand().name());
                payload.put("model", profile.getModel());
                payload.put("lan_ip", newIp.trim());
                payload.put("remote_port", Config.REMOTE_SERVER_PORT);
                payload.put("mdns_host", id.getMdnsHost());
                payload.put("is_active", true);
                payload.put("last_seen_at", SupabaseClient.isoNowUtc());
                SupabaseClient.get(appContext).upsertTvDeviceSync(payload);
            } catch (IOException e) {
                SupabaseClient.safeLogW("CloudSyncManager IP update failed: " + e.getMessage());
            }
        });
    }

    /** Runs a full 3-tier pull + field-level merge + conditional push asynchronously. */
    public void syncNowAsync(String reason, Runnable onDone) {
        if (appContext == null) {
            if (onDone != null) onDone.run();
            return;
        }
        syncIo.execute(() -> {
            boolean changed = false;
            try {
                changed = syncAllSync(reason);
            } catch (Exception e) {
                SupabaseClient.safeLogW("CloudSyncManager syncNow(" + reason + ") failed: " + e.getMessage());
                updateSyncMeta("Offline cache (" + e.getClass().getSimpleName() + ")", false);
            } finally {
                syncing = false;
            }
            if (changed) {
                notifyUiRefresh();
            }
            if (onDone != null) {
                postToMain(onDone);
            }
        });
    }

    // MARK: - Full 3-Tier Sync Implementation

    private boolean syncAllSync(String reason) {
        applyBootstrapMetadataIfPresent();
        if (!isCloudSyncConfigured()) {
            updateSyncMeta("Local mode (Supabase not linked)", false);
            return false;
        }
        syncing = true;
        boolean anyLocalChange = false;
        SupabaseClient sb = SupabaseClient.get(appContext);
        char[] vaultPass = CloudVaultCrypto.deriveHouseholdVaultPassphrase(sb.resolveHouseholdKey());
        try {
            // 0. Handle one-time Setup Studio "Replace Existing TV" portable settings import
            anyLocalChange |= handleBootstrapTvReplacementIfPending(sb);

            // 1. Tier 1 — Household encrypted secrets + structured household settings + gaming hosts
            anyLocalChange |= syncHouseholdVault(sb, vaultPass);
            anyLocalChange |= syncHouseholdSettings(sb);
            syncGamingHostsFromCloud(sb);

            // 2. Tier 2 — User profiles roster + active profile encrypted vault + profile settings
            UserProfileManager.get(appContext).syncWithCloudSync();
            String profileId = activeProfileId();
            if (profileId != null && !profileId.isEmpty()) {
                anyLocalChange |= syncProfileVault(sb, vaultPass, profileId);
                anyLocalChange |= syncProfileSettings(sb, profileId);
                sb.fetchWatchlistSync(profileId);
                sb.fetchVotesSync(profileId);
            }

            // 3. Tier 3 — This TV Device (live IP + per-TV settings + active TVs directory cache)
            anyLocalChange |= syncThisTvDevice(sb);
            try {
                sb.fetchTvDevicesSync(true);
            } catch (IOException ignored) {
            }

            updateSyncMeta("Synced (" + reason + ")", true);
        } finally {
            CloudVaultCrypto.wipePassphrase(vaultPass);
            syncing = false;
        }
        return anyLocalChange;
    }

    private void pushDirtyScopesSync(EnumSet<Scope> scopes) {
        if (!isCloudSyncConfigured() || scopes == null || scopes.isEmpty()) return;
        SupabaseClient sb = SupabaseClient.get(appContext);
        char[] vaultPass = CloudVaultCrypto.deriveHouseholdVaultPassphrase(sb.resolveHouseholdKey());
        try {
            if (scopes.contains(Scope.HOUSEHOLD)) {
                syncHouseholdVault(sb, vaultPass);
                syncHouseholdSettings(sb);
            }
            if (scopes.contains(Scope.PROFILE)) {
                String profileId = activeProfileId();
                if (profileId != null && !profileId.isEmpty()) {
                    syncProfileVault(sb, vaultPass, profileId);
                    syncProfileSettings(sb, profileId);
                }
            }
            if (scopes.contains(Scope.TV_DEVICE)) {
                syncThisTvDevice(sb);
            }
            updateSyncMeta("Synced (auto-push)", true);
        } catch (Exception e) {
            SupabaseClient.safeLogW("CloudSyncManager pushDirtyScopes failed: " + e.getMessage());
        } finally {
            CloudVaultCrypto.wipePassphrase(vaultPass);
        }
    }

    private void applyBootstrapMetadataIfPresent() {
        if (appContext == null) return;
        String bootId = Secrets.getSecret(appContext, Secrets.TV_BOOTSTRAP_DEVICE_ID);
        if (!bootId.isEmpty()) {
            TvDeviceIdentity.get(appContext).setDeviceId(bootId);
            Secrets.deleteSecret(appContext, Secrets.TV_BOOTSTRAP_DEVICE_ID);
        }
        String bootName = Secrets.getSecret(appContext, Secrets.TV_BOOTSTRAP_NAME);
        if (!bootName.isEmpty()) {
            TvDeviceIdentity.get(appContext).setDeviceName(bootName);
            Secrets.deleteSecret(appContext, Secrets.TV_BOOTSTRAP_NAME);
        }
        String bootRoom = Secrets.getSecret(appContext, Secrets.TV_BOOTSTRAP_ROOM);
        if (!bootRoom.isEmpty()) {
            SmartHomeManager.get(appContext).setTvRoomQuietly(bootRoom);
            Secrets.deleteSecret(appContext, Secrets.TV_BOOTSTRAP_ROOM);
        }
    }

    private boolean handleBootstrapTvReplacementIfPending(SupabaseClient sb) {
        String replaceOldId = Secrets.getSecret(appContext, Secrets.TV_BOOTSTRAP_REPLACE_ID);
        if (replaceOldId == null || replaceOldId.trim().isEmpty()) return false;
        String oldId = replaceOldId.trim();
        try {
            SupabaseClient.TvDeviceRecord oldTv = sb.fetchTvDeviceSync(oldId);
            if (oldTv != null) {
                Map<String, Object> portable = extractPortableTvSettings(oldTv.tvSettings);
                if (!portable.containsKey("room_name") && oldTv.roomName != null && !oldTv.roomName.isEmpty()) {
                    portable.put("room_name", oldTv.roomName);
                }
                applyingRemote = true;
                try {
                    applyTvSettingsMap(portable);
                    long now = System.currentTimeMillis();
                    Map<String, Long> tvTs = loadTimestamps(Scope.TV_DEVICE, "");
                    for (String k : portable.keySet()) {
                        tvTs.put(k, now);
                    }
                    saveTimestamps(Scope.TV_DEVICE, "", tvTs);
                } finally {
                    applyingRemote = false;
                }
                if (!oldId.equals(TvDeviceIdentity.get(appContext).getDeviceId())) {
                    sb.deactivateTvDeviceSync(oldId, TvDeviceIdentity.get(appContext).getDeviceId());
                }
            }
            Secrets.deleteSecret(appContext, Secrets.TV_BOOTSTRAP_REPLACE_ID);
            return true;
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager replace-TV bootstrap deferred: " + e.getMessage());
            return false;
        }
    }

    private boolean syncHouseholdVault(SupabaseClient sb, char[] vaultPass) {
        Map<String, String> local = Secrets.exportHouseholdVaultSecrets(appContext);
        // Include any runtime Prefs overrides if Secrets did not yet have them
        seedIfAbsent(local, Secrets.TMDB_KEY, Prefs.tmdbKey());
        seedIfAbsent(local, Secrets.OMDB_KEY, Prefs.omdbKey());
        seedIfAbsent(local, Secrets.MDBLIST_KEY, Prefs.mdblistKey());
        seedIfAbsent(local, Secrets.GEMINI_API_KEY, Prefs.geminiKey());
        seedIfAbsent(local, Secrets.GITHUB_TOKEN, Prefs.githubToken());
        seedIfAbsent(local, Secrets.GAMING_PC_TOKEN, Prefs.gamingPcToken());

        Map<String, String> remote = null;
        try {
            remote = sb.restoreVaultSlotOrNullSync(CloudVaultCrypto.HOUSEHOLD_VAULT_ID, vaultPass);
        } catch (IOException | GeneralSecurityException e) {
            SupabaseClient.safeLogW("CloudSyncManager household vault pull skipped: " + e.getMessage());
            return false;
        }

        SharedPreferences meta = metaPrefs();
        Set<String> dirty = meta != null
                ? new HashSet<>(meta.getStringSet(KEY_DIRTY_HH_SECRETS, Collections.emptySet()))
                : Collections.emptySet();
        Map<String, String> merged = mergeSecretMaps(local, remote, dirty);

        boolean localChanged = !merged.equals(local);
        boolean remoteNeedsPush = remote == null || !merged.equals(remote);

        if (localChanged) {
            applyingRemote = true;
            try {
                Secrets.importHouseholdVaultSecrets(appContext, merged);
                Prefs.syncRestoredVaultOverrides(merged);
                if (merged.containsKey(Secrets.REMOTE_PAIRED_DEVICES)) {
                    RemoteAuthManager.get(appContext).reloadFromSecrets();
                }
            } finally {
                applyingRemote = false;
            }
        }
        if (remoteNeedsPush && !merged.isEmpty()) {
            try {
                sb.backupVaultSlotSync(CloudVaultCrypto.HOUSEHOLD_VAULT_ID, vaultPass, merged);
                if (meta != null && !dirty.isEmpty()) {
                    meta.edit().remove(KEY_DIRTY_HH_SECRETS).apply();
                }
            } catch (IOException e) {
                SupabaseClient.safeLogW("CloudSyncManager household vault push failed: " + e.getMessage());
            }
        } else if (meta != null && !dirty.isEmpty()) {
            meta.edit().remove(KEY_DIRTY_HH_SECRETS).apply();
        }
        return localChanged;
    }

    private boolean syncProfileVault(SupabaseClient sb, char[] vaultPass, String profileId) {
        Map<String, String> local = Secrets.exportProfileVaultSecrets(appContext, profileId);
        String slot = CloudVaultCrypto.profileVaultId(profileId);
        Map<String, String> remote = null;
        try {
            remote = sb.restoreVaultSlotOrNullSync(slot, vaultPass);
        } catch (IOException | GeneralSecurityException e) {
            SupabaseClient.safeLogW("CloudSyncManager profile vault pull skipped: " + e.getMessage());
            return false;
        }

        SharedPreferences meta = metaPrefs();
        String dirtyKey = KEY_DIRTY_PROF_SECRETS_PREFIX + profileId;
        Set<String> dirty = meta != null
                ? new HashSet<>(meta.getStringSet(dirtyKey, Collections.emptySet()))
                : Collections.emptySet();
        Map<String, String> merged = mergeSecretMaps(local, remote, dirty);

        boolean localChanged = !merged.equals(local);
        boolean remoteNeedsPush = (remote == null && !merged.isEmpty()) || (remote != null && !merged.equals(remote));

        if (localChanged) {
            applyingRemote = true;
            try {
                Secrets.importProfileVaultSecrets(appContext, profileId, merged);
            } finally {
                applyingRemote = false;
            }
        }
        if (remoteNeedsPush && !merged.isEmpty()) {
            try {
                sb.backupVaultSlotSync(slot, vaultPass, merged);
                if (meta != null && !dirty.isEmpty()) {
                    meta.edit().remove(dirtyKey).apply();
                }
            } catch (IOException e) {
                SupabaseClient.safeLogW("CloudSyncManager profile vault push failed: " + e.getMessage());
            }
        } else if (meta != null && !dirty.isEmpty()) {
            meta.edit().remove(dirtyKey).apply();
        }
        return localChanged;
    }

    private boolean syncHouseholdSettings(SupabaseClient sb) {
        Map<String, Object> localVals = collectLocalHouseholdSettings();
        Map<String, Long> localTs = loadTimestamps(Scope.HOUSEHOLD, "");
        SupabaseClient.SettingsDocument remoteDoc;
        try {
            remoteDoc = sb.fetchHouseholdSettingsSync();
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager household settings fetch failed: " + e.getMessage());
            return false;
        }
        Map<String, Object> remoteVals = remoteDoc != null ? remoteDoc.settings : Collections.emptyMap();
        Map<String, Long> remoteTs = remoteDoc != null ? remoteDoc.fieldTimestamps : Collections.emptyMap();

        MergeResult merged = mergeFieldMaps(localVals, localTs, remoteVals, remoteTs);
        if (merged.localChanged) {
            applyingRemote = true;
            try {
                applyHouseholdSettingsMap(merged.mergedValues);
            } finally {
                applyingRemote = false;
            }
        }
        saveTimestamps(Scope.HOUSEHOLD, "", merged.mergedTimestamps);
        if (merged.remoteNeedsPush || remoteDoc == null) {
            try {
                sb.upsertHouseholdSettingsSync(merged.mergedValues, merged.mergedTimestamps);
            } catch (IOException e) {
                SupabaseClient.safeLogW("CloudSyncManager household settings push failed: " + e.getMessage());
            }
        }
        return merged.localChanged;
    }

    private boolean syncProfileSettings(SupabaseClient sb, String profileId) {
        Map<String, Object> localVals = collectLocalProfileSettings(profileId);
        Map<String, Long> localTs = loadTimestamps(Scope.PROFILE, profileId);
        SupabaseClient.SettingsDocument remoteDoc;
        try {
            remoteDoc = sb.fetchProfileSettingsSync(profileId);
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager profile settings fetch failed: " + e.getMessage());
            return false;
        }
        Map<String, Object> remoteVals = remoteDoc != null ? remoteDoc.settings : Collections.emptyMap();
        Map<String, Long> remoteTs = remoteDoc != null ? remoteDoc.fieldTimestamps : Collections.emptyMap();

        MergeResult merged = mergeFieldMaps(localVals, localTs, remoteVals, remoteTs);
        if (merged.localChanged) {
            applyingRemote = true;
            try {
                applyProfileSettingsMap(profileId, merged.mergedValues);
            } finally {
                applyingRemote = false;
            }
        }
        saveTimestamps(Scope.PROFILE, profileId, merged.mergedTimestamps);
        if (merged.remoteNeedsPush || remoteDoc == null) {
            try {
                sb.upsertProfileSettingsSync(profileId, merged.mergedValues, merged.mergedTimestamps);
            } catch (IOException e) {
                SupabaseClient.safeLogW("CloudSyncManager profile settings push failed: " + e.getMessage());
            }
        }
        return merged.localChanged;
    }

    private boolean syncThisTvDevice(SupabaseClient sb) {
        TvDeviceIdentity id = TvDeviceIdentity.get(appContext);
        String deviceId = id.getDeviceId();
        Map<String, Object> localVals = collectLocalTvSettings();
        Map<String, Long> localTs = loadTimestamps(Scope.TV_DEVICE, "");

        SupabaseClient.TvDeviceRecord remoteTv = null;
        try {
            remoteTv = sb.fetchTvDeviceSync(deviceId);
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager tv_devices fetch failed: " + e.getMessage());
            return false;
        }

        Map<String, Object> remoteVals = remoteTv != null ? remoteTv.tvSettings : Collections.emptyMap();
        Map<String, Long> remoteTs = remoteTv != null ? remoteTv.fieldTimestamps : Collections.emptyMap();

        MergeResult merged = mergeFieldMaps(localVals, localTs, remoteVals, remoteTs);
        if (merged.localChanged) {
            applyingRemote = true;
            try {
                applyTvSettingsMap(merged.mergedValues);
            } finally {
                applyingRemote = false;
            }
        }
        saveTimestamps(Scope.TV_DEVICE, "", merged.mergedTimestamps);

        InetAddress lan = RemoteControlServer.lanAddress(appContext);
        String lanIp = lan != null && lan.getHostAddress() != null ? lan.getHostAddress() : id.getLastKnownLanIp();
        TvDeviceProfile profile = TvDeviceProfile.current();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", deviceId);
        payload.put("device_name", id.getDeviceName());
        payload.put("room_name", SmartHomeManager.get(appContext).getTvRoom());
        payload.put("platform", TvDeviceIdentity.PLATFORM_ANDROID_TV);
        payload.put("brand", profile.getBrand().name());
        payload.put("model", profile.getModel());
        payload.put("lan_ip", lanIp == null ? "" : lanIp);
        payload.put("remote_port", Config.REMOTE_SERVER_PORT);
        payload.put("mdns_host", id.getMdnsHost());
        payload.put("app_version", "2.0");
        payload.put("tv_settings", merged.mergedValues);
        payload.put("field_timestamps", merged.mergedTimestamps);
        payload.put("is_active", true);
        payload.put("last_seen_at", SupabaseClient.isoNowUtc());
        try {
            sb.upsertTvDeviceSync(payload);
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager tv_devices upsert failed: " + e.getMessage());
        }
        return merged.localChanged;
    }

    private void syncGamingHostsFromCloud(SupabaseClient sb) {
        try {
            List<SupabaseClient.GamingHostRecord> hosts = sb.fetchGamingHostsSync();
            if (!hosts.isEmpty()) {
                GamingZone.adoptCloudGamingHosts(appContext, hosts);
            }
        } catch (IOException e) {
            SupabaseClient.safeLogW("CloudSyncManager gaming_hosts sync skipped: " + e.getMessage());
        }
    }

    // MARK: - Local Tier Collectors & Appliers

    private Map<String, Object> collectLocalHouseholdSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        String repo = Prefs.githubRepo();
        if (!repo.isEmpty()) m.put("github_repo", repo);
        String city = Prefs.city();
        if (!city.isEmpty()) {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("city", city);
            loc.put("latitude", Prefs.latitude());
            loc.put("longitude", Prefs.longitude());
            m.put("default_location", loc);
        }
        List<String> drawerOrder = Prefs.drawerOrder();
        if (drawerOrder != null && !drawerOrder.isEmpty()) {
            m.put("smart_home_order", drawerOrder);
        }
        try {
            SmartHomeManager shm = SmartHomeManager.get(appContext);
            Map<String, Object> wizMeta = shm.exportWizBulbRegistryForCloud();
            if (!wizMeta.isEmpty()) m.put("wiz_bulbs", wizMeta);
            List<String> customRooms = shm.exportCustomRoomsForCloud();
            if (!customRooms.isEmpty()) m.put("custom_rooms", customRooms);
            String switcherDevId = shm.getSwitcherDeviceId();
            if (!switcherDevId.isEmpty()) m.put("switcher_device_id", switcherDevId);
        } catch (RuntimeException ignored) {
        }
        try {
            Map<String, Object> btTags = ControllerManager.exportBluetoothTagsForCloud(appContext);
            if (!btTags.isEmpty()) m.put("bt_controller_tags", btTags);
        } catch (RuntimeException ignored) {
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private void applyHouseholdSettingsMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        String repo = CloudVaultCrypto.JsonCodec.optString(values, "github_repo", "");
        if (!repo.isEmpty()) Prefs.setGithubRepo(repo);

        Map<String, Object> loc = CloudVaultCrypto.JsonCodec.optObjectMap(values, "default_location");
        if (!loc.isEmpty()) {
            String city = CloudVaultCrypto.JsonCodec.optString(loc, "city", "");
            String lat = CloudVaultCrypto.JsonCodec.optString(loc, "latitude", "");
            String lon = CloudVaultCrypto.JsonCodec.optString(loc, "longitude", "");
            if (!city.isEmpty()) Prefs.setCity(city, lat, lon);
        }
        List<String> order = CloudVaultCrypto.JsonCodec.optStringList(values, "smart_home_order");
        if (!order.isEmpty()) Prefs.setDrawerOrder(order);

        try {
            SmartHomeManager shm = SmartHomeManager.get(appContext);
            Map<String, Object> wiz = CloudVaultCrypto.JsonCodec.optObjectMap(values, "wiz_bulbs");
            if (!wiz.isEmpty()) shm.importWizBulbRegistryFromCloud(wiz);
            List<String> customRooms = CloudVaultCrypto.JsonCodec.optStringList(values, "custom_rooms");
            if (!customRooms.isEmpty()) shm.importCustomRoomsFromCloud(customRooms);
            String switcherDevId = CloudVaultCrypto.JsonCodec.optString(values, "switcher_device_id", "");
            if (!switcherDevId.isEmpty()) shm.setSwitcherDeviceId(switcherDevId);
        } catch (RuntimeException ignored) {
        }
        try {
            Map<String, Object> btTags = CloudVaultCrypto.JsonCodec.optObjectMap(values, "bt_controller_tags");
            if (!btTags.isEmpty()) ControllerManager.importBluetoothTagsFromCloud(appContext, btTags);
        } catch (RuntimeException ignored) {
        }
    }

    private Map<String, Object> collectLocalProfileSettings(String profileId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("use_household_stremio", Prefs.useHouseholdStremio());
        m.put("theme", Prefs.theme());
        m.put("auto_theme_mode", Prefs.autoThemeMode());
        m.put("auto_dark_hour", Prefs.autoDarkHour());
        m.put("auto_light_hour", Prefs.autoLightHour());
        m.put("language", Prefs.language());
        m.put("clock_format", Prefs.clockFormat());
        m.put("temperature", Prefs.temperature());
        m.put("hide_animation", Prefs.hideAnimation());
        m.put("genre_order_tv", Prefs.genreOrder("tv"));
        m.put("genre_order_movie", Prefs.genreOrder("movie"));
        m.put("hidden_genres_tv", new ArrayList<>(Prefs.hiddenGenres("tv")));
        m.put("hidden_genres_movie", new ArrayList<>(Prefs.hiddenGenres("movie")));
        m.put("watchlist_order_tv", Prefs.watchlistOrder("tv"));
        m.put("watchlist_order_movie", Prefs.watchlistOrder("movie"));
        m.put("watched_items", new ArrayList<>(Prefs.watchedItems()));
        m.put("watch_hidden", Prefs.exportWatchHiddenMap());
        m.put("watch_off", new ArrayList<>(Prefs.watchOff()));

        // Hidden games (per-user profile)
        m.put("hidden_games", new ArrayList<>(GamingZone.getTemporarilyHiddenGameIds(appContext)));
        m.put("perm_hidden_games", new ArrayList<>(GamingZone.getPermanentlyHiddenGameIds(appContext)));

        // Default canonical Favorite Apps, Hidden Apps, and Screensaver (Profile-level defaults)
        Set<String> installed = installedAndroidPackages();
        List<String> profileShelfLocal = Prefs.profileShelf();
        if (profileShelfLocal != null) {
            List<String> existingCloud = loadCachedCanonicalList(KEY_CLOUD_SHELF_CACHE + profileId);
            m.put("shelf_apps", CanonicalAppCatalog.mergeCanonicalListNonDestructive(existingCloud, profileShelfLocal, installed));
        }
        Set<String> profHidden = Prefs.profileHiddenApps();
        Set<String> profPermHidden = Prefs.profilePermHiddenApps();
        List<String> existingHidden = loadCachedCanonicalList(KEY_CLOUD_HIDDEN_CACHE + profileId);
        List<String> existingPermHidden = loadCachedCanonicalList(KEY_CLOUD_PERM_HIDDEN_CACHE + profileId);
        m.put("hidden_apps", CanonicalAppCatalog.mergeCanonicalSetNonDestructive(new LinkedHashSet<>(existingHidden), profHidden, installed));
        m.put("perm_hidden_apps", CanonicalAppCatalog.mergeCanonicalSetNonDestructive(new LinkedHashSet<>(existingPermHidden), profPermHidden, installed));
        m.put("screensaver", Prefs.exportProfileScreensaverMap());
        return m;
    }

    private void applyProfileSettingsMap(String profileId, Map<String, Object> v) {
        if (v == null || v.isEmpty()) return;
        if (v.containsKey("use_household_stremio")) {
            Prefs.setUseHouseholdStremio(CloudVaultCrypto.JsonCodec.optBoolean(v, "use_household_stremio", false));
        }
        if (v.containsKey("theme")) Prefs.setTheme(CloudVaultCrypto.JsonCodec.optString(v, "theme", "auto"));
        if (v.containsKey("auto_theme_mode")) Prefs.setAutoThemeMode(CloudVaultCrypto.JsonCodec.optString(v, "auto_theme_mode", "sun"));
        if (v.containsKey("auto_dark_hour")) Prefs.setAutoDarkHour(CloudVaultCrypto.JsonCodec.optInt(v, "auto_dark_hour", 19));
        if (v.containsKey("auto_light_hour")) Prefs.setAutoLightHour(CloudVaultCrypto.JsonCodec.optInt(v, "auto_light_hour", 7));
        if (v.containsKey("language")) Prefs.setLanguage(CloudVaultCrypto.JsonCodec.optString(v, "language", "auto"));
        if (v.containsKey("clock_format")) Prefs.setClockFormat(CloudVaultCrypto.JsonCodec.optString(v, "clock_format", "auto"));
        if (v.containsKey("temperature")) Prefs.setTemperature(CloudVaultCrypto.JsonCodec.optString(v, "temperature", "auto"));
        if (v.containsKey("hide_animation")) Prefs.setHideAnimation(CloudVaultCrypto.JsonCodec.optBoolean(v, "hide_animation", false));

        List<String> gTv = CloudVaultCrypto.JsonCodec.optStringList(v, "genre_order_tv");
        if (!gTv.isEmpty()) Prefs.setGenreOrder("tv", gTv);
        List<String> gMov = CloudVaultCrypto.JsonCodec.optStringList(v, "genre_order_movie");
        if (!gMov.isEmpty()) Prefs.setGenreOrder("movie", gMov);
        if (v.containsKey("hidden_genres_tv")) {
            Prefs.replaceHiddenGenres("tv", new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "hidden_genres_tv")));
        }
        if (v.containsKey("hidden_genres_movie")) {
            Prefs.replaceHiddenGenres("movie", new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "hidden_genres_movie")));
        }
        if (v.containsKey("watchlist_order_tv")) {
            Prefs.setWatchlistOrder("tv", CloudVaultCrypto.JsonCodec.optStringList(v, "watchlist_order_tv"));
        }
        if (v.containsKey("watchlist_order_movie")) {
            Prefs.setWatchlistOrder("movie", CloudVaultCrypto.JsonCodec.optStringList(v, "watchlist_order_movie"));
        }
        if (v.containsKey("watched_items")) {
            Prefs.replaceWatchedItems(new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "watched_items")));
        }
        if (v.containsKey("watch_hidden")) {
            Prefs.importWatchHiddenMap(CloudVaultCrypto.JsonCodec.optObjectMap(v, "watch_hidden"));
        }
        if (v.containsKey("watch_off")) {
            Prefs.setWatchOff(new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "watch_off")));
        }
        if (v.containsKey("hidden_games") || v.containsKey("perm_hidden_games")) {
            GamingZone.importHiddenGameIdsFromCloud(
                    appContext,
                    new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "hidden_games")),
                    new LinkedHashSet<>(CloudVaultCrypto.JsonCodec.optStringList(v, "perm_hidden_games")));
        }

        Set<String> installed = installedAndroidPackages();
        if (v.containsKey("shelf_apps")) {
            List<String> canonical = CloudVaultCrypto.JsonCodec.optStringList(v, "shelf_apps");
            saveCachedCanonicalList(KEY_CLOUD_SHELF_CACHE + profileId, canonical);
            if (!canonical.isEmpty()) {
                List<String> localPkgs = CanonicalAppCatalog.resolveCanonicalListToLocal(canonical, installed);
                if (!localPkgs.isEmpty()) Prefs.setProfileShelf(localPkgs);
            }
        }
        if (v.containsKey("hidden_apps")) {
            List<String> canonical = CloudVaultCrypto.JsonCodec.optStringList(v, "hidden_apps");
            saveCachedCanonicalList(KEY_CLOUD_HIDDEN_CACHE + profileId, canonical);
            Prefs.replaceProfileHiddenApps(new LinkedHashSet<>(CanonicalAppCatalog.resolveCanonicalListToLocal(canonical, installed)));
        }
        if (v.containsKey("perm_hidden_apps")) {
            List<String> canonical = CloudVaultCrypto.JsonCodec.optStringList(v, "perm_hidden_apps");
            saveCachedCanonicalList(KEY_CLOUD_PERM_HIDDEN_CACHE + profileId, canonical);
            Prefs.replaceProfilePermHiddenApps(new LinkedHashSet<>(CanonicalAppCatalog.resolveCanonicalListToLocal(canonical, installed)));
        }
        if (v.containsKey("screensaver")) {
            Prefs.importProfileScreensaverMap(CloudVaultCrypto.JsonCodec.optObjectMap(v, "screensaver"));
        }
    }

    private Map<String, Object> collectLocalTvSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("room_name", SmartHomeManager.get(appContext).getTvRoom());
        m.put("enable_gaming", FeatureTabs.getGamingModePref(appContext));
        m.put("enable_smarthome", FeatureTabs.getSmartHomeModePref(appContext));
        m.put("enable_reels", FeatureTabs.isReelsEnabled() ? "true" : "false");

        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("resolution", Prefs.gamingResolution());
        stream.put("fps", Prefs.gamingFps());
        stream.put("bitrate_mbps", Prefs.gamingBitrateMbps());
        stream.put("frame_pacing", Prefs.gamingFramePacing());
        stream.put("codec", Prefs.gamingCodec());
        stream.put("hdr", Prefs.gamingHdr());
        stream.put("audio_config", Prefs.gamingAudioConfig());
        stream.put("play_audio_on_pc", Prefs.gamingPlayAudioOnPc());
        stream.put("rumble", Prefs.gamingRumble());
        stream.put("mouse_emulation", Prefs.gamingMouseEmulation());
        stream.put("perf_overlay", Prefs.gamingPerfOverlay());
        m.put("gaming_stream", stream);

        boolean ovShelf = Prefs.isTvOverrideShelfEnabled();
        boolean ovHidden = Prefs.isTvOverrideHiddenAppsEnabled();
        boolean ovSaver = Prefs.isTvOverrideScreensaverEnabled();
        m.put("override_shelf", ovShelf);
        m.put("override_hidden_apps", ovHidden);
        m.put("override_screensaver", ovSaver);

        Set<String> installed = installedAndroidPackages();
        if (ovShelf) {
            List<String> tvShelf = Prefs.tvOverrideShelf();
            if (tvShelf != null) {
                m.put("shelf_apps", CanonicalAppCatalog.mergeCanonicalListNonDestructive(null, tvShelf, installed));
            }
        }
        if (ovHidden) {
            m.put("hidden_apps", CanonicalAppCatalog.mergeCanonicalSetNonDestructive(null, Prefs.tvOverrideHiddenApps(), installed));
            m.put("perm_hidden_apps", CanonicalAppCatalog.mergeCanonicalSetNonDestructive(null, Prefs.tvOverridePermHiddenApps(), installed));
        }
        if (ovSaver) {
            m.put("screensaver", Prefs.exportTvOverrideScreensaverMap());
        }

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("status_icons", Prefs.statusIcons());
        ui.put("sounds", Prefs.sounds());
        ui.put("sleep_shown", Prefs.sleepShown());
        ui.put("sleep_place", Prefs.sleepPlace());
        ui.put("power_on", Prefs.powerOn());
        ui.put("recents", Prefs.recents());
        ui.put("tuner_mode", Prefs.tunerMode());
        ui.put("auto_update_channels", Prefs.autoUpdateChannels());
        m.put("ui_prefs", ui);
        return m;
    }

    private void applyTvSettingsMap(Map<String, Object> v) {
        if (v == null || v.isEmpty()) return;
        String room = CloudVaultCrypto.JsonCodec.optString(v, "room_name", "");
        if (!room.isEmpty()) SmartHomeManager.get(appContext).setTvRoomQuietly(room);
        if (v.containsKey("enable_gaming")) {
            FeatureTabs.setGamingModePref(CloudVaultCrypto.JsonCodec.optString(v, "enable_gaming", FeatureTabs.MODE_AUTO));
        }
        if (v.containsKey("enable_smarthome")) {
            FeatureTabs.setSmartHomeModePref(CloudVaultCrypto.JsonCodec.optString(v, "enable_smarthome", FeatureTabs.MODE_AUTO));
        }

        Map<String, Object> stream = CloudVaultCrypto.JsonCodec.optObjectMap(v, "gaming_stream");
        if (!stream.isEmpty()) {
            if (stream.containsKey("resolution")) Prefs.setGamingResolution(CloudVaultCrypto.JsonCodec.optString(stream, "resolution", "1080p"));
            if (stream.containsKey("fps")) Prefs.setGamingFps(CloudVaultCrypto.JsonCodec.optInt(stream, "fps", 60));
            if (stream.containsKey("bitrate_mbps")) Prefs.setGamingBitrateMbps(CloudVaultCrypto.JsonCodec.optInt(stream, "bitrate_mbps", 30));
            if (stream.containsKey("frame_pacing")) Prefs.setGamingFramePacing(CloudVaultCrypto.JsonCodec.optString(stream, "frame_pacing", "balanced"));
            if (stream.containsKey("codec")) Prefs.setGamingCodec(CloudVaultCrypto.JsonCodec.optString(stream, "codec", "auto"));
            if (stream.containsKey("hdr")) Prefs.setGamingHdr(CloudVaultCrypto.JsonCodec.optBoolean(stream, "hdr", false));
            if (stream.containsKey("audio_config")) Prefs.setGamingAudioConfig(CloudVaultCrypto.JsonCodec.optString(stream, "audio_config", "stereo"));
            if (stream.containsKey("play_audio_on_pc")) Prefs.setGamingPlayAudioOnPc(CloudVaultCrypto.JsonCodec.optBoolean(stream, "play_audio_on_pc", false));
            if (stream.containsKey("rumble")) Prefs.setGamingRumble(CloudVaultCrypto.JsonCodec.optBoolean(stream, "rumble", true));
            if (stream.containsKey("mouse_emulation")) Prefs.setGamingMouseEmulation(CloudVaultCrypto.JsonCodec.optBoolean(stream, "mouse_emulation", true));
            if (stream.containsKey("perf_overlay")) Prefs.setGamingPerfOverlay(CloudVaultCrypto.JsonCodec.optBoolean(stream, "perf_overlay", false));
        }

        if (v.containsKey("override_shelf")) {
            Prefs.setTvOverrideShelfEnabled(CloudVaultCrypto.JsonCodec.optBoolean(v, "override_shelf", false));
        }
        if (v.containsKey("override_hidden_apps")) {
            Prefs.setTvOverrideHiddenAppsEnabled(CloudVaultCrypto.JsonCodec.optBoolean(v, "override_hidden_apps", false));
        }
        if (v.containsKey("override_screensaver")) {
            Prefs.setTvOverrideScreensaverEnabled(CloudVaultCrypto.JsonCodec.optBoolean(v, "override_screensaver", false));
        }

        Set<String> installed = installedAndroidPackages();
        if (Prefs.isTvOverrideShelfEnabled() && v.containsKey("shelf_apps")) {
            List<String> localPkgs = CanonicalAppCatalog.resolveCanonicalListToLocal(
                    CloudVaultCrypto.JsonCodec.optStringList(v, "shelf_apps"), installed);
            if (!localPkgs.isEmpty()) Prefs.setTvOverrideShelf(localPkgs);
        }
        if (Prefs.isTvOverrideHiddenAppsEnabled()) {
            if (v.containsKey("hidden_apps")) {
                Prefs.replaceTvOverrideHiddenApps(new LinkedHashSet<>(CanonicalAppCatalog.resolveCanonicalListToLocal(
                        CloudVaultCrypto.JsonCodec.optStringList(v, "hidden_apps"), installed)));
            }
            if (v.containsKey("perm_hidden_apps")) {
                Prefs.replaceTvOverridePermHiddenApps(new LinkedHashSet<>(CanonicalAppCatalog.resolveCanonicalListToLocal(
                        CloudVaultCrypto.JsonCodec.optStringList(v, "perm_hidden_apps"), installed)));
            }
        }
        if (Prefs.isTvOverrideScreensaverEnabled() && v.containsKey("screensaver")) {
            Prefs.importTvOverrideScreensaverMap(CloudVaultCrypto.JsonCodec.optObjectMap(v, "screensaver"));
        }

        Map<String, Object> ui = CloudVaultCrypto.JsonCodec.optObjectMap(v, "ui_prefs");
        if (!ui.isEmpty()) {
            if (ui.containsKey("status_icons")) Prefs.setStatusIcons(CloudVaultCrypto.JsonCodec.optBoolean(ui, "status_icons", false));
            if (ui.containsKey("sounds")) Prefs.setSounds(CloudVaultCrypto.JsonCodec.optBoolean(ui, "sounds", true));
            if (ui.containsKey("sleep_shown")) Prefs.setSleepShown(CloudVaultCrypto.JsonCodec.optBoolean(ui, "sleep_shown", true));
            if (ui.containsKey("sleep_place")) Prefs.setSleepPlace(CloudVaultCrypto.JsonCodec.optInt(ui, "sleep_place", 0));
            if (ui.containsKey("power_on")) Prefs.setPowerOn(CloudVaultCrypto.JsonCodec.optString(ui, "power_on", "tv"));
            if (ui.containsKey("recents")) Prefs.setRecents(CloudVaultCrypto.JsonCodec.optString(ui, "recents", "off"));
            if (ui.containsKey("tuner_mode")) Prefs.setTunerMode(CloudVaultCrypto.JsonCodec.optString(ui, "tuner_mode", "antenna"));
            if (ui.containsKey("auto_update_channels")) Prefs.setAutoUpdateChannels(CloudVaultCrypto.JsonCodec.optBoolean(ui, "auto_update_channels", true));
        }
    }

    // MARK: - Helpers

    private Set<String> installedAndroidPackages() {
        Set<String> out = new HashSet<>();
        if (appContext == null) return out;
        try {
            android.content.pm.PackageManager pm = appContext.getPackageManager();
            if (pm != null) {
                for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (ai != null && ai.packageName != null && !ai.packageName.isEmpty()) {
                        out.add(ai.packageName);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private String activeProfileId() {
        if (appContext == null) return "";
        try {
            UserProfileManager.Profile p = UserProfileManager.get(appContext).getActiveProfile();
            return p != null ? p.id : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void seedIfAbsent(Map<String, String> target, String key, String value) {
        if (value != null && !value.trim().isEmpty() && !target.containsKey(key)) {
            target.put(key, value.trim());
        }
    }

    private Map<String, Long> loadTimestamps(Scope scope, String profileId) {
        Map<String, Long> out = new LinkedHashMap<>();
        SharedPreferences meta = metaPrefs();
        if (meta == null) return out;
        String key = timestampPrefKey(scope, profileId);
        String raw = meta.getString(key, "{}");
        try {
            Map<String, Object> parsed = CloudVaultCrypto.JsonCodec.parseJsonObject(raw);
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number) {
                    out.put(e.getKey(), ((Number) e.getValue()).longValue());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private void saveTimestamps(Scope scope, String profileId, Map<String, Long> ts) {
        SharedPreferences meta = metaPrefs();
        if (meta == null) return;
        Map<String, Object> box = new LinkedHashMap<>();
        if (ts != null) box.putAll(ts);
        meta.edit().putString(timestampPrefKey(scope, profileId),
                CloudVaultCrypto.JsonCodec.toJsonObjectString(box)).apply();
    }

    private static String timestampPrefKey(Scope scope, String profileId) {
        switch (scope) {
            case HOUSEHOLD: return KEY_HH_TIMESTAMPS;
            case PROFILE: return KEY_PROFILE_TS_PREFIX + (profileId == null ? "default" : profileId);
            case TV_DEVICE:
            default: return KEY_TV_TIMESTAMPS;
        }
    }

    private List<String> loadCachedCanonicalList(String key) {
        SharedPreferences meta = metaPrefs();
        if (meta == null) return Collections.emptyList();
        String raw = meta.getString(key, "");
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void saveCachedCanonicalList(String key, List<String> list) {
        SharedPreferences meta = metaPrefs();
        if (meta == null) return;
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (String s : list) {
                if (s == null || s.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(s.trim());
            }
        }
        meta.edit().putString(key, sb.toString()).apply();
    }

    private void updateSyncMeta(String status, boolean stampTime) {
        long now = stampTime ? System.currentTimeMillis() : lastSyncAtMs;
        lastStatus = status;
        lastSyncAtMs = now;
        SharedPreferences meta = metaPrefs();
        if (meta != null) {
            SharedPreferences.Editor ed = meta.edit().putString(KEY_LAST_STATUS, status);
            if (stampTime) ed.putLong(KEY_LAST_SYNC_MS, now);
            ed.apply();
        }
    }

    private SharedPreferences metaPrefs() {
        if (appContext == null) return null;
        try {
            return appContext.getSharedPreferences(SYNC_META_PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void notifyUiRefresh() {
        Runnable cb = uiRefreshCallback;
        if (cb != null) {
            postToMain(cb);
        }
    }

    private static void postToMain(Runnable r) {
        if (r == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(r);
        } catch (RuntimeException headlessJvm) {
            r.run();
        }
    }
}
