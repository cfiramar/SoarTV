package io.soartv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central registry of {@link ReelsSourcePlugin} implementations powering SoarTV's modular Reels tab.
 *
 * <p><b>Why:</b> Decouples core feed merging, UI rendering, Settings, and Watch Later from any
 * hardcoded list of social networks. Built-in plugins cover Instagram, TikTok, YouTube Shorts,
 * and X/Twitter, and additional platforms can be registered dynamically without touching core logic.
 *
 * <p><b>What:</b> Maintains an insertion-ordered map of {@code sourceId -> ReelsSourcePlugin},
 * resolves share URLs to owning plugins, and exposes chat-capable vs. all registered sources.
 */
public final class ReelsSourceRegistry {
    public static final String SOURCE_INSTAGRAM = "instagram";
    public static final String SOURCE_TIKTOK = "tiktok";
    public static final String SOURCE_YOUTUBE_SHORTS = "youtube_shorts";
    public static final String SOURCE_X = "x";

    private static final ReelsSourceRegistry DEFAULT = createDefault();

    private final Map<String, ReelsSourcePlugin> plugins = new LinkedHashMap<>();

    /** Returns the shared default registry pre-loaded with built-in plugins. */
    public static ReelsSourceRegistry getDefault() {
        return DEFAULT;
    }

    /** Creates a fresh registry pre-loaded with all built-in plugins (useful for isolated unit tests). */
    public static ReelsSourceRegistry createDefault() {
        ReelsSourceRegistry reg = new ReelsSourceRegistry();
        reg.register(new InstagramSourcePlugin());
        reg.register(new TikTokSourcePlugin());
        reg.register(new YouTubeShortsSourcePlugin());
        reg.register(new XSourcePlugin());
        return reg;
    }

    /** Registers or replaces a {@link ReelsSourcePlugin} by its normalized {@link ReelsSourcePlugin#sourceId()}. */
    public synchronized void register(ReelsSourcePlugin plugin) {
        if (plugin == null) return;
        String id = ReelsModels.normalizeSourceId(plugin.sourceId());
        plugins.put(id, plugin);
    }

    /** Returns the plugin for {@code sourceId}, or {@code null} if unknown. */
    public synchronized ReelsSourcePlugin find(String sourceId) {
        return plugins.get(ReelsModels.normalizeSourceId(sourceId));
    }

    /** Returns an unmodifiable snapshot of all registered plugins in registration order. */
    public synchronized List<ReelsSourcePlugin> allPlugins() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.values()));
    }

    /** Returns only plugins that expose a live chat sync bridge (e.g. Instagram, TikTok). */
    public synchronized List<ReelsSourcePlugin> chatPlugins() {
        List<ReelsSourcePlugin> out = new ArrayList<>();
        for (ReelsSourcePlugin p : plugins.values()) {
            if (p.supportsChatBridge()) out.add(p);
        }
        return Collections.unmodifiableList(out);
    }

    /** Detects the owning {@code sourceId} for any shared URL, falling back to {@code "other"}. */
    public synchronized String detectSourceIdForUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "other";
        for (ReelsSourcePlugin p : plugins.values()) {
            if (p.matchesShareUrl(url)) return p.sourceId();
        }
        return "other";
    }

    /** Returns the procedural {@link Symbols} icon name for {@code sourceId}, defaulting to {@code "tiktok"}. */
    public String iconNameFor(String sourceId) {
        ReelsSourcePlugin p = find(sourceId);
        return p != null ? p.iconName() : "tiktok";
    }

    /** Returns the display label for {@code sourceId}. */
    public String displayNameFor(String sourceId) {
        ReelsSourcePlugin p = find(sourceId);
        if (p != null) return p.displayName();
        String norm = ReelsModels.normalizeSourceId(sourceId);
        return norm.isEmpty() ? "Reels" : (Character.toUpperCase(norm.charAt(0)) + norm.substring(1));
    }

    /** Returns the accent color for {@code sourceId}. */
    public int accentColorFor(String sourceId) {
        ReelsSourcePlugin p = find(sourceId);
        return p != null ? p.accentColor() : 0xFF2563EB;
    }

    // MARK: - Built-In Source Plugins

    /** Native Instagram Reels group/1-on-1 chat plugin (via on-TV residential WebView). */
    public static final class InstagramSourcePlugin implements ReelsSourcePlugin {
        @Override public String sourceId() { return SOURCE_INSTAGRAM; }
        @Override public String displayName() { return "Instagram"; }
        @Override public String iconName() { return "instagram"; }
        @Override public int accentColor() { return 0xFFE1306C; }
        @Override public String loginUrl() { return "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F"; }
        @Override public String inboxUrl() { return "https://www.instagram.com/direct/inbox/"; }
        @Override public String bridgeAssetPath() { return "reels/instagram_bridge.js"; }
        @Override public boolean supportsChatBridge() { return true; }
        @Override public boolean supportsEmojiReactions() { return true; }
        @Override public boolean supportsGroupChats() { return true; }
        @Override public String accountCreationHint() {
            return "Create a free dedicated Instagram account for your TV (e.g. @livingroom.tv), add it to your couple or family group chat, then sign in once here.";
        }
        @Override public boolean matchesShareUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("instagram.com/") || lower.contains("instagr.am/");
        }
    }

    /** Native TikTok group/1-on-1 chat plugin (via on-TV residential WebView). */
    public static final class TikTokSourcePlugin implements ReelsSourcePlugin {
        @Override public String sourceId() { return SOURCE_TIKTOK; }
        @Override public String displayName() { return "TikTok"; }
        @Override public String iconName() { return "tiktok"; }
        @Override public int accentColor() { return 0xFF25F4EE; }
        @Override public String loginUrl() { return "https://www.tiktok.com/login"; }
        @Override public String inboxUrl() { return "https://www.tiktok.com/messages"; }
        @Override public String bridgeAssetPath() { return "reels/tiktok_bridge.js"; }
        @Override public boolean supportsChatBridge() { return true; }
        @Override public boolean supportsEmojiReactions() { return true; }
        @Override public boolean supportsGroupChats() { return true; }
        @Override public String accountCreationHint() {
            return "Create a free dedicated TikTok account for your TV, add it to your couple or family group chat, then scan the QR code here to sign in without typing a password.";
        }
        @Override public boolean matchesShareUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("tiktok.com/") || lower.contains("vm.tiktok.com") || lower.contains("vt.tiktok.com");
        }
    }

    /** Modular YouTube Shorts plugin (URL/Watch Later + future feed expansion). */
    public static final class YouTubeShortsSourcePlugin implements ReelsSourcePlugin {
        @Override public String sourceId() { return SOURCE_YOUTUBE_SHORTS; }
        @Override public String displayName() { return "YouTube Shorts"; }
        @Override public String iconName() { return "youtube"; }
        @Override public int accentColor() { return 0xFFFF0033; }
        @Override public String loginUrl() { return "https://www.youtube.com/shorts"; }
        @Override public String inboxUrl() { return "https://www.youtube.com/shorts"; }
        @Override public String bridgeAssetPath() { return ""; }
        @Override public boolean supportsChatBridge() { return false; }
        @Override public boolean supportsEmojiReactions() { return false; }
        @Override public boolean supportsGroupChats() { return false; }
        @Override public String accountCreationHint() {
            return "YouTube Shorts links shared in chats or via Companion Remote play natively in SoarTV.";
        }
        @Override public boolean matchesShareUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("youtube.com/shorts") || lower.contains("youtu.be/") || lower.contains("youtube.com/watch");
        }
    }

    /** Modular X / Twitter video plugin (URL/Watch Later + future feed expansion). */
    public static final class XSourcePlugin implements ReelsSourcePlugin {
        @Override public String sourceId() { return SOURCE_X; }
        @Override public String displayName() { return "X"; }
        @Override public String iconName() { return "x_social"; }
        @Override public int accentColor() { return 0xFF1D9BF0; }
        @Override public String loginUrl() { return "https://x.com/i/flow/login"; }
        @Override public String inboxUrl() { return "https://x.com/messages"; }
        @Override public String bridgeAssetPath() { return ""; }
        @Override public boolean supportsChatBridge() { return false; }
        @Override public boolean supportsEmojiReactions() { return false; }
        @Override public boolean supportsGroupChats() { return true; }
        @Override public String accountCreationHint() {
            return "X / Twitter video links shared in chats or via Companion Remote are ready for modular expansion.";
        }
        @Override public boolean matchesShareUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase(Locale.ROOT);
            return lower.contains("x.com/") || lower.contains("twitter.com/");
        }
    }
}
