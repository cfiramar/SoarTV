package io.soartv;

/**
 * Modular plugin contract for any short-form social video platform supported by SoarTV's Reels tab.
 *
 * <p><b>Why:</b> Enforces the Open/Closed Principle so adding future platforms (YouTube Shorts,
 * X/Twitter, Threads, Snapchat, etc.) requires only implementing and registering a new plugin —
 * zero edits to {@link ReelsFeedEngine}, {@link ReelsRepository}, {@link ReelsPlayerActivity},
 * or Supabase SQL schema.
 *
 * <p><b>What:</b> Exposes platform identity, vector icon key, WebView login/inbox entry URLs,
 * JavaScript bridge asset path, feature capabilities (group chats, emoji reactions), and share-URL
 * matching.
 */
public interface ReelsSourcePlugin {

    /** Stable lowercase identifier persisted in Supabase &amp; local preferences (e.g. {@code "instagram"}, {@code "tiktok"}). */
    String sourceId();

    /** Human-readable platform label shown in setup, settings, and accessibility labels. */
    String displayName();

    /** Procedural vector icon name registered in {@link Symbols} (zero emojis). */
    String iconName();

    /** Subtle accent tint for platform badges and setup cards. */
    int accentColor();

    /** Official web login URL opened when the user connects a TV account once manually. */
    String loginUrl();

    /** Official web inbox/messages URL loaded by {@link ReelsWebBridgeHost} to read &amp; react to chats. */
    String inboxUrl();

    /** Asset path under {@code app/assets/} for this platform's injected JS bridge, or {@code ""} if link-only. */
    String bridgeAssetPath();

    /** Whether this plugin provides a live chat sync bridge (Instagram/TikTok) vs. link/Watch-Later only. */
    boolean supportsChatBridge();

    /** Whether this plugin supports reacting with {@code 👀} on the last watched message. */
    boolean supportsEmojiReactions();

    /** Whether this plugin supports multi-member group chats (3+ family/couple members + TV). */
    boolean supportsGroupChats();

    /** Concise setup hint explaining how to create a dedicated TV account &amp; group chat on this platform. */
    String accountCreationHint();

    /** Returns {@code true} if {@code url} belongs to this platform (used for Watch Later &amp; stream resolution). */
    boolean matchesShareUrl(String url);
}
