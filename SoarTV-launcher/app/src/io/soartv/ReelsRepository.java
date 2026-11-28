package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline-first persistence repository for SoarTV's Reels tab across `:home` and `:reels` processes.
 *
 * <p><b>Why:</b> The full-screen player and WebView bridges run inside an isolated {@code :reels}
 * process to keep the launcher's 60fps Canvas immune to WebView memory pressure, while Settings,
 * Home preview, and Companion Remote run in {@code :home}. A multi-process {@link SharedPreferences}
 * backing store paired with background Supabase cloud sync guarantees instant 0ms reads, offline
 * resilience, and seamless cross-process consistency.
 *
 * <p><b>What:</b> Persists per-source {@link ReelsModels.SourceWatermark}s, unlimited family
 * {@link ReelsModels.SenderAlias} custom names, Watch Later {@link ReelsModels.FeedItem}s, and
 * cached unwatched feed snapshots, with pure static JSON codecs testable on a host JVM.
 */
public final class ReelsRepository {
    private static final String PREFS_NAME = "soartv_reels_state";
    private static final String KEY_WATERMARKS_JSON = "watermarks_json";
    private static final String KEY_SENDERS_JSON = "senders_json";
    private static final String KEY_WATCH_LATER_JSON = "watch_later_json";
    private static final String KEY_CACHED_FEED_JSON = "cached_feed_json";
    private static final String KEY_LATEST_CHAT_JSON = "latest_chat_ids_json";

    /** Curated rainbow-safe Liquid Glass pill accents assigned automatically to newly discovered members. */
    private static final int[] MEMBER_PALETTE = new int[] {
        0xFF38BDF8, 0xFFF472B6, 0xFF34D399, 0xFFA78BFA,
        0xFFFBBF24, 0xFFFB7185, 0xFF2DD4BF, 0xFF818CF8
    };

    private static volatile ReelsRepository instance;
    private final Context appContext;

    private ReelsRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Returns the singleton {@link ReelsRepository} for the current process. */
    public static ReelsRepository get(Context context) {
        ReelsRepository r = instance;
        if (r == null) {
            synchronized (ReelsRepository.class) {
                r = instance;
                if (r == null) {
                    r = new ReelsRepository(context);
                    instance = r;
                }
            }
        }
        return r;
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
    }

    // MARK: - Source Watermarks (Connected Threads & Last Watched Checkpoints)

    /** Loads all persisted per-source watermarks keyed by {@code sourceId}. */
    public Map<String, ReelsModels.SourceWatermark> loadWatermarks() {
        return parseWatermarksJson(prefs().getString(KEY_WATERMARKS_JSON, "[]"));
    }

    /** Returns the list of source IDs that currently have a connected group/chat thread configured. */
    public List<String> getConnectedSourceIds() {
        Map<String, ReelsModels.SourceWatermark> all = loadWatermarks();
        List<String> out = new ArrayList<>();
        for (ReelsModels.SourceWatermark wm : all.values()) {
            if (wm != null && wm.isConfigured()) out.add(wm.sourceId);
        }
        return out;
    }

    /** Saves or updates a single source's connection &amp; read watermark locally and syncs to Supabase. */
    public void saveWatermark(ReelsModels.SourceWatermark watermark) {
        if (watermark == null) return;
        Map<String, ReelsModels.SourceWatermark> all = new LinkedHashMap<>(loadWatermarks());
        all.put(watermark.sourceId, watermark);
        prefs().edit().putString(KEY_WATERMARKS_JSON, serializeWatermarksJson(all)).commit();
        SupabaseClient.get(appContext).upsertReelSyncStateAsync(watermark);
    }

    /** Batch-updates multiple watermarks (e.g. after pressing Done Watching across IG + TikTok). */
    public void saveWatermarks(Map<String, ReelsModels.SourceWatermark> updates) {
        if (updates == null || updates.isEmpty()) return;
        Map<String, ReelsModels.SourceWatermark> all = new LinkedHashMap<>(loadWatermarks());
        for (ReelsModels.SourceWatermark wm : updates.values()) {
            if (wm != null) {
                all.put(wm.sourceId, wm);
                SupabaseClient.get(appContext).upsertReelSyncStateAsync(wm);
            }
        }
        prefs().edit().putString(KEY_WATERMARKS_JSON, serializeWatermarksJson(all)).commit();
    }

    /** Clears a source's connected thread configuration (disconnects that chat cleanly). */
    public void disconnectSource(String sourceId) {
        String key = ReelsModels.normalizeSourceId(sourceId);
        Map<String, ReelsModels.SourceWatermark> all = new LinkedHashMap<>(loadWatermarks());
        all.remove(key);
        prefs().edit().putString(KEY_WATERMARKS_JSON, serializeWatermarksJson(all)).commit();
    }

    // MARK: - Unlimited Family / Group Sender Display Names

    /** Loads all custom sender aliases keyed by {@code sourceId + ":" + senderId}. */
    public Map<String, ReelsModels.SenderAlias> loadSenderAliases() {
        return parseSendersJson(prefs().getString(KEY_SENDERS_JSON, "[]"));
    }

    /** Renames (or registers) a family/group account across all future and cached Reels displays. */
    public ReelsModels.SenderAlias saveSenderAlias(
            String sourceId,
            String senderId,
            String rawUsername,
            String customDisplayName) {
        String normSource = ReelsModels.normalizeSourceId(sourceId);
        String cleanSender = ReelsModels.safe(senderId);
        if (cleanSender.isEmpty()) cleanSender = ReelsModels.safe(rawUsername);
        if (cleanSender.isEmpty()) return null;

        Map<String, ReelsModels.SenderAlias> all = new LinkedHashMap<>(loadSenderAliases());
        String key = normSource + ":" + cleanSender;
        ReelsModels.SenderAlias existing = all.get(key);
        int color = existing != null && existing.avatarColor != 0
                ? existing.avatarColor
                : pickColorForSender(key);
        String keptRaw = !ReelsModels.safe(rawUsername).isEmpty()
                ? ReelsModels.safe(rawUsername)
                : (existing != null ? existing.rawUsername : cleanSender);
        String finalName = ReelsModels.safe(customDisplayName);
        if (finalName.isEmpty() && existing != null) finalName = existing.displayName;
        if (finalName.isEmpty()) finalName = keptRaw;

        ReelsModels.SenderAlias next = new ReelsModels.SenderAlias(
                normSource, cleanSender, keptRaw, finalName, color);
        all.put(key, next);
        prefs().edit().putString(KEY_SENDERS_JSON, serializeSendersJson(all)).commit();
        SupabaseClient.get(appContext).upsertReelSenderAsync(next);
        return next;
    }

    /** Auto-registers any newly seen participants from chat threads or fetched items without overwriting custom names. */
    public void discoverSenders(List<ReelsModels.SenderAlias> discovered) {
        if (discovered == null || discovered.isEmpty()) return;
        Map<String, ReelsModels.SenderAlias> all = new LinkedHashMap<>(loadSenderAliases());
        boolean changed = false;
        for (ReelsModels.SenderAlias s : discovered) {
            if (s == null || s.senderId.isEmpty()) continue;
            String key = s.compositeKey();
            ReelsModels.SenderAlias cur = all.get(key);
            if (cur == null) {
                int color = s.avatarColor != 0 ? s.avatarColor : pickColorForSender(key);
                ReelsModels.SenderAlias added = new ReelsModels.SenderAlias(
                        s.sourceId, s.senderId, s.rawUsername,
                        !s.displayName.isEmpty() ? s.displayName : s.rawUsername, color);
                all.put(key, added);
                SupabaseClient.get(appContext).upsertReelSenderAsync(added);
                changed = true;
            } else if (cur.rawUsername.isEmpty() && !s.rawUsername.isEmpty()) {
                ReelsModels.SenderAlias updated = new ReelsModels.SenderAlias(
                        cur.sourceId, cur.senderId, s.rawUsername, cur.displayName, cur.avatarColor);
                all.put(key, updated);
                changed = true;
            }
        }
        if (changed) {
            prefs().edit().putString(KEY_SENDERS_JSON, serializeSendersJson(all)).commit();
        }
    }

    /** Auto-registers any newly seen senders directly from fetched {@link ReelsModels.FeedItem}s. */
    public void discoverSendersFromItems(List<ReelsModels.FeedItem> items) {
        if (items == null || items.isEmpty()) return;
        List<ReelsModels.SenderAlias> candidates = new ArrayList<>();
        for (ReelsModels.FeedItem item : items) {
            if (item == null || item.senderId.isEmpty()) continue;
            candidates.add(new ReelsModels.SenderAlias(
                    item.sourceId, item.senderId, item.senderRawName, item.senderRawName, 0));
        }
        discoverSenders(candidates);
    }

    // MARK: - Watch Later Folder

    /** Loads all items currently stored in the Watch Later folder, ordered oldest-added first. */
    public List<ReelsModels.FeedItem> loadWatchLater() {
        List<ReelsModels.FeedItem> list = parseFeedItemsJson(prefs().getString(KEY_WATCH_LATER_JSON, "[]"));
        List<ReelsModels.FeedItem> sorted = new ArrayList<>(list);
        Collections.sort(sorted, (a, b) -> Long.compare(a.watchLaterAddedAtMs, b.watchLaterAddedAtMs));
        return Collections.unmodifiableList(sorted);
    }

    /** Returns {@code true} if an item with the same {@code id} or {@code shareUrl} is already in Watch Later. */
    public boolean isInWatchLater(ReelsModels.FeedItem item) {
        if (item == null) return false;
        for (ReelsModels.FeedItem wl : loadWatchLater()) {
            if (matchesWatchLaterItem(wl, item)) return true;
        }
        return false;
    }

    /** Toggles {@code item} in the Watch Later folder; returns {@code true} if now saved, {@code false} if removed. */
    public boolean toggleWatchLater(ReelsModels.FeedItem item) {
        if (item == null) return false;
        List<ReelsModels.FeedItem> current = new ArrayList<>(loadWatchLater());
        for (int i = 0; i < current.size(); i++) {
            if (matchesWatchLaterItem(current.get(i), item)) {
                ReelsModels.FeedItem removed = current.remove(i);
                prefs().edit().putString(KEY_WATCH_LATER_JSON, serializeFeedItemsJson(current)).commit();
                SupabaseClient.get(appContext).setSharedReelWatchLaterAsync(removed.shareUrl, false);
                return false;
            }
        }
        long now = System.currentTimeMillis();
        ReelsModels.FeedItem saved = item.withWatchLater(true, now);
        current.add(saved);
        prefs().edit().putString(KEY_WATCH_LATER_JSON, serializeFeedItemsJson(current)).commit();
        String senderLabel = ReelsFeedEngine.resolveSenderDisplayName(saved, loadSenderAliases());
        SupabaseClient.get(appContext).saveWatchLaterReelAsync(saved, senderLabel);
        return true;
    }

    /** Removes all Watch Later items whose {@code id} appears in {@code itemIds} (used by Done Watching inside Watch Later). */
    public int removeFromWatchLaterByIds(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) return 0;
        Set<String> removeSet = new java.util.HashSet<>(itemIds);
        List<ReelsModels.FeedItem> current = new ArrayList<>(loadWatchLater());
        List<ReelsModels.FeedItem> remaining = new ArrayList<>(current.size());
        int removedCount = 0;
        for (ReelsModels.FeedItem wl : current) {
            if (removeSet.contains(wl.id)) {
                removedCount++;
                SupabaseClient.get(appContext).setSharedReelWatchLaterAsync(wl.shareUrl, false);
            } else {
                remaining.add(wl);
            }
        }
        if (removedCount > 0) {
            prefs().edit().putString(KEY_WATCH_LATER_JSON, serializeFeedItemsJson(remaining)).commit();
        }
        return removedCount;
    }

    private static boolean matchesWatchLaterItem(ReelsModels.FeedItem a, ReelsModels.FeedItem b) {
        if (a == null || b == null) return false;
        if (!a.id.isEmpty() && a.id.equals(b.id)) return true;
        return !a.shareUrl.isEmpty() && a.shareUrl.equals(b.shareUrl);
    }

    // MARK: - Cached Unwatched Feed Snapshot

    /** Loads the last cached merged feed snapshot for instant 0ms startup before live refresh completes. */
    public List<ReelsModels.FeedItem> loadCachedFeedItems() {
        return parseFeedItemsJson(prefs().getString(KEY_CACHED_FEED_JSON, "[]"));
    }

    /** Persists the latest merged unwatched feed snapshot &amp; per-source latest chat message IDs. */
    public void saveCachedFeedSnapshot(
            List<ReelsModels.FeedItem> items,
            Map<String, String> latestChatMessageIds) {
        SharedPreferences.Editor ed = prefs().edit();
        ed.putString(KEY_CACHED_FEED_JSON, serializeFeedItemsJson(items));
        if (latestChatMessageIds != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : latestChatMessageIds.entrySet()) {
                m.put(e.getKey(), e.getValue());
            }
            ed.putString(KEY_LATEST_CHAT_JSON, CloudVaultCrypto.JsonCodec.toJsonObjectString(m));
        }
        ed.commit();
    }

    /** Loads the cached map of {@code sourceId -> latest non-emoji chat message ID}. */
    public Map<String, String> loadLatestChatMessageIds() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Map<String, Object> raw = CloudVaultCrypto.JsonCodec.parseJsonObject(
                    prefs().getString(KEY_LATEST_CHAT_JSON, "{}"));
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() != null) {
                    out.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Pulls cloud watermarks &amp; custom sender names from Supabase asynchronously and merges locally. */
    public void syncFromCloudAsync(Runnable onDone) {
        SupabaseClient.get(appContext).fetchReelsCloudStateAsync((cloudWatermarks, cloudSenders, cloudWatchLater) -> {
            if (cloudWatermarks != null && !cloudWatermarks.isEmpty()) {
                Map<String, ReelsModels.SourceWatermark> merged = new LinkedHashMap<>(loadWatermarks());
                for (ReelsModels.SourceWatermark cw : cloudWatermarks.values()) {
                    ReelsModels.SourceWatermark local = merged.get(cw.sourceId);
                    if (local == null || cw.lastWatchedSentAtMs > local.lastWatchedSentAtMs
                            || (!local.isConfigured() && cw.isConfigured())) {
                        merged.put(cw.sourceId, cw);
                    }
                }
                prefs().edit().putString(KEY_WATERMARKS_JSON, serializeWatermarksJson(merged)).commit();
            }
            if (cloudSenders != null && !cloudSenders.isEmpty()) {
                Map<String, ReelsModels.SenderAlias> merged = new LinkedHashMap<>(loadSenderAliases());
                for (ReelsModels.SenderAlias cs : cloudSenders.values()) {
                    if (!cs.displayName.isEmpty() || !merged.containsKey(cs.compositeKey())) {
                        merged.put(cs.compositeKey(), cs);
                    }
                }
                prefs().edit().putString(KEY_SENDERS_JSON, serializeSendersJson(merged)).commit();
            }
            if (cloudWatchLater != null && !cloudWatchLater.isEmpty()) {
                List<ReelsModels.FeedItem> mergedWl = new ArrayList<>(loadWatchLater());
                for (ReelsModels.FeedItem cw : cloudWatchLater) {
                    boolean exists = false;
                    for (ReelsModels.FeedItem ex : mergedWl) {
                        if (matchesWatchLaterItem(ex, cw)) { exists = true; break; }
                    }
                    if (!exists) mergedWl.add(cw);
                }
                prefs().edit().putString(KEY_WATCH_LATER_JSON, serializeFeedItemsJson(mergedWl)).commit();
            }
            if (onDone != null) onDone.run();
        });
    }

    // MARK: - Pure-Java JSON Serialization (Testable on Host JVM)

    /** Serializes a watermark map into a canonical JSON array string. */
    public static String serializeWatermarksJson(Map<String, ReelsModels.SourceWatermark> map) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (map != null) {
            for (ReelsModels.SourceWatermark wm : map.values()) {
                if (wm == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("source", wm.sourceId);
                o.put("thread_id", wm.threadId);
                o.put("thread_title", wm.threadTitle);
                o.put("self_sender_id", wm.selfSenderId);
                o.put("last_watched_message_id", wm.lastWatchedMessageId);
                o.put("last_watched_sent_at_ms", wm.lastWatchedSentAtMs);
                o.put("latest_chat_message_id", wm.latestChatMessageId);
                rows.add(o);
            }
        }
        return CloudVaultCrypto.JsonCodec.toJsonArrayString(rows);
    }

    /** Parses a JSON array string back into a {@code sourceId -> SourceWatermark} map. */
    public static Map<String, ReelsModels.SourceWatermark> parseWatermarksJson(String json) {
        Map<String, ReelsModels.SourceWatermark> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            for (Map<String, Object> o : CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json)) {
                String source = CloudVaultCrypto.JsonCodec.optString(o, "source", "");
                if (source.isEmpty()) continue;
                ReelsModels.SourceWatermark wm = new ReelsModels.SourceWatermark(
                        source,
                        CloudVaultCrypto.JsonCodec.optString(o, "thread_id", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "thread_title", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "self_sender_id", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "last_watched_message_id", ""),
                        optLong(o, "last_watched_sent_at_ms", 0L),
                        CloudVaultCrypto.JsonCodec.optString(o, "latest_chat_message_id", ""));
                out.put(wm.sourceId, wm);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Serializes sender aliases into a canonical JSON array string. */
    public static String serializeSendersJson(Map<String, ReelsModels.SenderAlias> map) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (map != null) {
            for (ReelsModels.SenderAlias s : map.values()) {
                if (s == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("source", s.sourceId);
                o.put("sender_external_id", s.senderId);
                o.put("raw_username", s.rawUsername);
                o.put("display_name", s.displayName);
                o.put("avatar_color", s.avatarColor);
                rows.add(o);
            }
        }
        return CloudVaultCrypto.JsonCodec.toJsonArrayString(rows);
    }

    /** Parses a JSON array string back into a {@code sourceId:senderId -> SenderAlias} map. */
    public static Map<String, ReelsModels.SenderAlias> parseSendersJson(String json) {
        Map<String, ReelsModels.SenderAlias> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            for (Map<String, Object> o : CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json)) {
                String source = CloudVaultCrypto.JsonCodec.optString(o, "source", "");
                String senderId = CloudVaultCrypto.JsonCodec.optString(o, "sender_external_id", "");
                if (source.isEmpty() || senderId.isEmpty()) continue;
                ReelsModels.SenderAlias a = new ReelsModels.SenderAlias(
                        source,
                        senderId,
                        CloudVaultCrypto.JsonCodec.optString(o, "raw_username", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "display_name", ""),
                        CloudVaultCrypto.JsonCodec.optInt(o, "avatar_color", 0));
                out.put(a.compositeKey(), a);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Serializes a list of {@link ReelsModels.FeedItem}s into JSON. */
    public static String serializeFeedItemsJson(List<ReelsModels.FeedItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (items != null) {
            for (ReelsModels.FeedItem it : items) {
                if (it == null) continue;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", it.id);
                o.put("source_id", it.sourceId);
                o.put("item_kind", it.kind.wireValue);
                o.put("thread_id", it.threadId);
                o.put("message_id", it.messageId);
                o.put("sender_id", it.senderId);
                o.put("sender_raw_name", it.senderRawName);
                o.put("sent_at_ms", it.sentAtMs);
                o.put("share_url", it.shareUrl);
                o.put("direct_video_url", it.directVideoUrl);
                o.put("video_width", it.videoWidth);
                o.put("video_height", it.videoHeight);
                o.put("audio_url", it.audioUrl);
                o.put("thumbnail_url", it.thumbnailUrl);
                o.put("caption", it.caption);
                o.put("message_text", it.messageText);
                o.put("watch_later", it.watchLater);
                o.put("watch_later_added_at_ms", it.watchLaterAddedAtMs);
                if (!it.carouselImages.isEmpty()) {
                    o.put("carousel_images", new ArrayList<Object>(it.carouselImages));
                }
                if (!it.variants.isEmpty()) {
                    List<Object> vars = new ArrayList<>();
                    for (ReelsModels.MediaVariant v : it.variants) {
                        Map<String, Object> vo = new LinkedHashMap<>();
                        vo.put("url", v.url);
                        vo.put("width", v.width);
                        vo.put("height", v.height);
                        vars.add(vo);
                    }
                    o.put("variants", vars);
                }
                rows.add(o);
            }
        }
        return CloudVaultCrypto.JsonCodec.toJsonArrayString(rows);
    }

    /** Parses a JSON array string back into immutable {@link ReelsModels.FeedItem}s. */
    @SuppressWarnings("unchecked")
    public static List<ReelsModels.FeedItem> parseFeedItemsJson(String json) {
        List<ReelsModels.FeedItem> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            for (Map<String, Object> o : CloudVaultCrypto.JsonCodec.parseJsonObjectArray(json)) {
                List<String> images = new ArrayList<>();
                Object rawImages = o.get("carousel_images");
                if (rawImages instanceof List) {
                    for (Object img : (List<?>) rawImages) {
                        if (img != null && !String.valueOf(img).trim().isEmpty()) {
                            images.add(String.valueOf(img).trim());
                        }
                    }
                }
                List<ReelsModels.MediaVariant> variants = new ArrayList<>();
                Object rawVars = o.get("variants");
                if (rawVars instanceof List) {
                    for (Object rv : (List<?>) rawVars) {
                        if (rv instanceof Map) {
                            Map<String, Object> vm = (Map<String, Object>) rv;
                            String vu = CloudVaultCrypto.JsonCodec.optString(vm, "url", "");
                            if (!vu.isEmpty()) {
                                variants.add(new ReelsModels.MediaVariant(
                                        vu,
                                        CloudVaultCrypto.JsonCodec.optInt(vm, "width", 0),
                                        CloudVaultCrypto.JsonCodec.optInt(vm, "height", 0)));
                            }
                        }
                    }
                }
                String sourceId = CloudVaultCrypto.JsonCodec.optString(
                        o, "source_id", CloudVaultCrypto.JsonCodec.optString(o, "platform", "other"));
                String shareUrl = CloudVaultCrypto.JsonCodec.optString(
                        o, "share_url", CloudVaultCrypto.JsonCodec.optString(o, "url", ""));
                if ("other".equals(sourceId) && !shareUrl.isEmpty()) {
                    sourceId = ReelsSourceRegistry.getDefault().detectSourceIdForUrl(shareUrl);
                }
                out.add(new ReelsModels.FeedItem(
                        CloudVaultCrypto.JsonCodec.optString(o, "id", ""),
                        sourceId,
                        ReelsModels.ItemKind.fromWire(CloudVaultCrypto.JsonCodec.optString(o, "item_kind", "video")),
                        CloudVaultCrypto.JsonCodec.optString(o, "thread_id",
                                CloudVaultCrypto.JsonCodec.optString(o, "source_thread_id", "")),
                        CloudVaultCrypto.JsonCodec.optString(o, "message_id",
                                CloudVaultCrypto.JsonCodec.optString(o, "source_message_id", "")),
                        CloudVaultCrypto.JsonCodec.optString(o, "sender_id",
                                CloudVaultCrypto.JsonCodec.optString(o, "sender_external_id", "")),
                        CloudVaultCrypto.JsonCodec.optString(o, "sender_raw_name",
                                CloudVaultCrypto.JsonCodec.optString(o, "sender_name", "")),
                        optLong(o, "sent_at_ms", 0L),
                        shareUrl,
                        CloudVaultCrypto.JsonCodec.optString(o, "direct_video_url", ""),
                        CloudVaultCrypto.JsonCodec.optInt(o, "video_width", 0),
                        CloudVaultCrypto.JsonCodec.optInt(o, "video_height", 0),
                        variants,
                        images,
                        CloudVaultCrypto.JsonCodec.optString(o, "audio_url", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "thumbnail_url", ""),
                        CloudVaultCrypto.JsonCodec.optString(o, "caption",
                                CloudVaultCrypto.JsonCodec.optString(o, "title", "")),
                        CloudVaultCrypto.JsonCodec.optString(o, "message_text", ""),
                        CloudVaultCrypto.JsonCodec.optBoolean(o, "watch_later", false),
                        optLong(o, "watch_later_added_at_ms", 0L)));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    static int pickColorForSender(String key) {
        int h = key == null ? 0 : Math.abs(key.hashCode());
        return MEMBER_PALETTE[h % MEMBER_PALETTE.length];
    }

    private static long optLong(Map<String, Object> o, String key, long def) {
        if (o == null || !o.containsKey(key) || o.get(key) == null) return def;
        Object v = o.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }
}
