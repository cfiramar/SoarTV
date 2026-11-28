package io.soartv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive TDD unit test suite for SoarTV's modular Reels &amp; Social Video Feed engine:
 * <ul>
 *   <li>Pluggable {@link ReelsSourceRegistry} extensibility (Instagram, TikTok, YouTube Shorts, X, and custom future apps)</li>
 *   <li>Oldest-first multi-source merging with 3+ family members &amp; per-source watch watermark resumption</li>
 *   <li>Unicode emoji-only message filtering (English, Hebrew RTL, ZWJ/skin-tone emojis, URLs, pure {@code 👀})</li>
 *   <li>Inclusive Done Watching boundary (Video #3 watched -&gt; resumes at #4, reacts {@code 👀} on #3)</li>
 *   <li>Multi-app Done Watching reactions + independent per-app 10x {@code 👀} ~1.5s randomized caught-up burst</li>
 *   <li>Watch Later folder storage, isolation, and Done Watching cleanup</li>
 *   <li>Horizontal (16:9) stream variant preference &amp; adaptive 16:9 vs 9:16 TV stage math</li>
 *   <li>Pure-Java JSON repository serialization round-trips</li>
 * </ul>
 */
public final class ReelsFeatureTests {
    private static int passed;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("ReelsFeatureTests FAILED: " + msg);
        passed++;
    }

    private static ReelsModels.FeedItem videoItem(
            String source, String threadId, String msgId, String senderId, String senderRaw,
            long sentAtMs, String shareUrl, String msgText) {
        return new ReelsModels.FeedItem(
                "", source, ReelsModels.ItemKind.VIDEO, threadId, msgId, senderId, senderRaw,
                sentAtMs, shareUrl, "", 0, 0, null, null, "", "", "", msgText, false, 0L);
    }

    private static ReelsModels.FeedItem textItem(
            String source, String threadId, String msgId, String senderId, String senderRaw,
            long sentAtMs, String text) {
        return new ReelsModels.FeedItem(
                "", source, ReelsModels.ItemKind.TEXT_MESSAGE, threadId, msgId, senderId, senderRaw,
                sentAtMs, "", "", 0, 0, null, null, "", "", "", text, false, 0L);
    }

    public static void main(String[] args) {
        testSourceRegistryAndExtensibility();
        testEmojiOnlyFiltering();
        testOldestFirstMultiSourceAndFamilyMerge();
        testDoneWatchingInclusiveAndDualAppReactions();
        testDoneWatchingSingleSourceAndMidChat();
        testEyeBurstTimingOverOnePointFiveSeconds();
        testWatchLaterFolderDoneWatchingRemoval();
        testHorizontalVariantPreferenceAndStageGeometry();
        testSenderDisplayNameResolution();
        testRepositoryJsonRoundTrips();

        System.out.println("ReelsFeatureTests: " + passed + " assertions passed");
    }

    private static void testSourceRegistryAndExtensibility() {
        ReelsSourceRegistry reg = ReelsSourceRegistry.createDefault();
        check(reg.find("instagram") != null && reg.find("tiktok") != null, "Built-in IG & TikTok plugins present");
        check(reg.find("youtube_shorts") != null && reg.find("x") != null, "Built-in YouTube Shorts & X plugins present");
        check(reg.chatPlugins().size() == 2, "Default chat-bridge plugins = Instagram & TikTok");
        check("instagram".equals(reg.detectSourceIdForUrl("https://www.instagram.com/reel/ABC123/")), "Detect IG Reel URL");
        check("tiktok".equals(reg.detectSourceIdForUrl("https://vm.tiktok.com/ZM8xyz123/")), "Detect TikTok short URL");
        check("youtube_shorts".equals(reg.detectSourceIdForUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ")), "Detect YouTube Shorts URL");
        check("x".equals(reg.detectSourceIdForUrl("https://x.com/user/status/123456")), "Detect X URL");

        // Register a brand-new hypothetical future platform dynamically (Open/Closed principle)
        reg.register(new ReelsSourcePlugin() {
            @Override public String sourceId() { return "threads"; }
            @Override public String displayName() { return "Threads"; }
            @Override public String iconName() { return "message"; }
            @Override public int accentColor() { return 0xFFFFFFFF; }
            @Override public String loginUrl() { return "https://www.threads.net/login"; }
            @Override public String inboxUrl() { return "https://www.threads.net/direct"; }
            @Override public String bridgeAssetPath() { return "reels/threads_bridge.js"; }
            @Override public boolean supportsChatBridge() { return true; }
            @Override public boolean supportsEmojiReactions() { return true; }
            @Override public boolean supportsGroupChats() { return true; }
            @Override public String accountCreationHint() { return "Create a TV Threads account"; }
            @Override public boolean matchesShareUrl(String url) { return url != null && url.contains("threads.net/"); }
        });
        check(reg.chatPlugins().size() == 3, "Registering new future app increases chatPlugins without core edits");
        check("threads".equals(reg.detectSourceIdForUrl("https://www.threads.net/@user/post/999")), "Custom plugin URL detection");
    }

    private static void testEmojiOnlyFiltering() {
        check(ReelsFeedEngine.isEmojiOnlyMessage(""), "Empty string ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("   \n\t "), "Whitespace ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("\uD83D\uDC40"), "Single eye emoji ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("\uD83D\uDE02\uD83D\uDE02\uD83D\uDE02"), "Repeated laughing emojis ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("\u2764\uFE0F\uD83D\uDD25\uD83D\uDC4D\uD83C\uDFFD"), "Heart + VS16 + fire + skin-tone ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"), "ZWJ family emoji ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("https://vm.tiktok.com/abc123/ \uD83D\uDE02"), "URL + emoji-only ignored");
        check(ReelsFeedEngine.isEmojiOnlyMessage("!!! ??? ..."), "Punctuation-only ignored");

        check(!ReelsFeedEngine.isEmojiOnlyMessage("Wait for the ending!"), "English text kept");
        check("Wait for the ending! \uD83D\uDE02".equals(
                ReelsFeedEngine.extractDisplayableText("https://vm.tiktok.com/abc Wait for the ending! \uD83D\uDE02")),
                "URL stripped while mixed English text+emoji kept");
        String hebrew = "\u05D7\u05D9\u05D9\u05D1\u05D9\u05DD \u05DC\u05E8\u05D0\u05D5\u05EA \u05E2\u05D3 \u05D4\u05E1\u05D5\u05E3 \uD83D\uDE02";
        check(!ReelsFeedEngine.isEmojiOnlyMessage(hebrew), "Hebrew text with emoji kept");
        check(hebrew.equals(ReelsFeedEngine.extractDisplayableText(hebrew)), "Hebrew text preserved verbatim");
        check(!ReelsFeedEngine.isEmojiOnlyMessage("10/10"), "Numeric message kept");
    }

    private static void testOldestFirstMultiSourceAndFamilyMerge() {
        // 4 family members across 3 sources (IG, TikTok, and future 'threads'), unsorted input + emoji-only noise + watched items
        List<ReelsModels.FeedItem> raw = Arrays.asList(
                videoItem("tiktok", "tt_grp", "200", "u_mom", "Mom", 4000L, "https://www.tiktok.com/@a/video/200", "\uD83D\uDE02"),
                videoItem("instagram", "ig_grp", "101", "u_dad", "Dad", 1000L, "https://www.instagram.com/reel/old/", ""),
                textItem("instagram", "ig_grp", "102", "u_sis", "Sister", 2500L, "\uD83D\uDC40"),
                textItem("tiktok", "tt_grp", "199", "u_partner", "Partner", 2800L, "Wait until you see the next one!"),
                videoItem("instagram", "ig_grp", "103", "u_partner", "Partner", 3000L, "https://www.instagram.com/reel/new1/", "So funny"),
                videoItem("threads", "th_grp", "301", "u_bro", "Brother", 3500L, "https://www.threads.net/post/301", ""),
                // Duplicate of 103 should be deduplicated
                videoItem("instagram", "ig_grp", "103", "u_partner", "Partner", 3000L, "https://www.instagram.com/reel/new1/", "So funny")
        );

        Map<String, ReelsModels.SourceWatermark> wms = new HashMap<>();
        wms.put("instagram", new ReelsModels.SourceWatermark("instagram", "ig_grp", "Family", "tv", "101", 1000L, "103"));

        List<ReelsModels.FeedItem> merged = ReelsFeedEngine.mergeOldestFirst(raw, wms);
        check(merged.size() == 4, "Watched IG 101, emoji-only IG 102, and duplicate IG 103 excluded -> 4 items remain (got " + merged.size() + ")");
        check("199".equals(merged.get(0).messageId) && merged.get(0).kind == ReelsModels.ItemKind.TEXT_MESSAGE,
                "Oldest item is TikTok text message @2800");
        check("103".equals(merged.get(1).messageId) && "So funny".equals(merged.get(1).messageText),
                "Second item is IG video @3000 with text note preserved");
        check("301".equals(merged.get(2).messageId) && "threads".equals(merged.get(2).sourceId),
                "Third item is modular 3rd-source video @3500");
        check("200".equals(merged.get(3).messageId) && merged.get(3).messageText.isEmpty(),
                "Fourth item is TikTok video @4000 with emoji-only caption stripped to empty");
    }

    private static void testDoneWatchingInclusiveAndDualAppReactions() {
        // Queue: 0=IG(10), 1=TT(20), 2=IG(30), 3=TT(40), 4=TT(50)
        // Latest in IG chat = 30 (IG is caught up at index 2!), Latest in TT chat = 50 (TT still has 50 unwatched!)
        List<ReelsModels.FeedItem> queue = Arrays.asList(
                videoItem("instagram", "ig_t", "10", "u1", "P1", 1000L, "https://instagram.com/reel/10", ""),
                videoItem("tiktok", "tt_t", "20", "u2", "P2", 2000L, "https://tiktok.com/@x/video/20", ""),
                videoItem("instagram", "ig_t", "30", "u1", "P1", 3000L, "https://instagram.com/reel/30", ""),
                videoItem("tiktok", "tt_t", "40", "u2", "P2", 4000L, "https://tiktok.com/@x/video/40", ""),
                videoItem("tiktok", "tt_t", "50", "u1", "P1", 5000L, "https://tiktok.com/@x/video/50", "")
        );
        Map<String, String> latestBySource = new HashMap<>();
        latestBySource.put("instagram", "30");
        latestBySource.put("tiktok", "50");

        // User stops on index 3 (Video #4 = TT 40) and presses Done Watching.
        // Inclusive: index 3 itself counts as watched!
        ReelsModels.DoneWatchingPlan plan = ReelsFeedEngine.computeDoneWatchingPlan(
                queue, 3, latestBySource, Collections.<String, ReelsModels.SourceWatermark>emptyMap(), 42L);

        check(plan.watchedItemIds.size() == 4, "Inclusive Done Watching marks indices 0..3 (4 items)");
        check(plan.actions.size() == 2, "Both Instagram and TikTok receive reaction actions");

        ReelsModels.DoneWatchingAction igAct = plan.actionForSource("instagram");
        ReelsModels.DoneWatchingAction ttAct = plan.actionForSource("tiktok");
        check(igAct != null && "30".equals(igAct.reactMessageId), "IG reacts 👀 on last watched IG item (30)");
        check(igAct.sendTenEyes && igAct.eyeBurstDelaysMs.length == 10, "IG is at end of chat -> sends 10 consecutive 👀");
        check(ttAct != null && "40".equals(ttAct.reactMessageId), "TikTok reacts 👀 on last watched TT item (40)");
        check(!ttAct.sendTenEyes && ttAct.eyeBurstDelaysMs.length == 0, "TikTok still has item 50 -> does NOT send 10x 👀");

        // Next time user opens Reels with updated watermarks -> starts cleanly at TT(50)!
        List<ReelsModels.FeedItem> nextSession = ReelsFeedEngine.mergeOldestFirst(queue, plan.updatedWatermarks);
        check(nextSession.size() == 1 && "50".equals(nextSession.get(0).messageId),
                "Next Reels launch resumes at oldest unwatched item (TT 50)");
    }

    private static void testDoneWatchingSingleSourceAndMidChat() {
        List<ReelsModels.FeedItem> queue = Arrays.asList(
                videoItem("tiktok", "tt_t", "1", "u1", "P1", 1000L, "https://tiktok.com/@x/video/1", ""),
                videoItem("tiktok", "tt_t", "2", "u2", "P2", 2000L, "https://tiktok.com/@x/video/2", ""),
                videoItem("instagram", "ig_t", "9", "u1", "P1", 3000L, "https://instagram.com/reel/9", "")
        );
        Map<String, String> latest = new HashMap<>();
        latest.put("tiktok", "2");
        latest.put("instagram", "9");

        // Stop on index 1 (TT 2) — IG was never reached this session
        ReelsModels.DoneWatchingPlan plan = ReelsFeedEngine.computeDoneWatchingPlan(
                queue, 1, latest, Collections.<String, ReelsModels.SourceWatermark>emptyMap(), 7L);
        check(plan.actions.size() == 1, "Only TikTok touched when IG wasn't watched yet");
        check(plan.actionForSource("instagram") == null, "Untouched Instagram gets no reaction");
        check(plan.actionForSource("tiktok").sendTenEyes, "TikTok reached end of TikTok chat (2) -> 10x 👀");
    }

    private static void testEyeBurstTimingOverOnePointFiveSeconds() {
        int[] delays = ReelsFeedEngine.computeEyeBurstDelaysMs(ReelsFeedEngine.CAUGHT_UP_EYE_MESSAGE_COUNT, 12345L);
        check(delays.length == 10, "Exactly 10 eye-emoji slots");
        check(delays[0] == 0, "First eye emoji sends immediately (0ms delay)");
        int totalMs = 0;
        boolean hasVariation = false;
        for (int i = 1; i < delays.length; i++) {
            check(delays[i] >= ReelsFeedEngine.MIN_BURST_STEP_DELAY_MS
                    && delays[i] <= ReelsFeedEngine.MAX_BURST_STEP_DELAY_MS,
                    "Randomized per-message delay within safe bounds (" + delays[i] + "ms)");
            totalMs += delays[i];
            if (i > 1 && delays[i] != delays[i - 1]) hasVariation = true;
        }
        check(totalMs >= 1200 && totalMs <= 1800, "Total 10-message burst spans ~1.5s (actual=" + totalMs + "ms)");
        check(hasVariation, "Delays are genuinely randomized rather than flat constant spacing");
    }

    private static void testWatchLaterFolderDoneWatchingRemoval() {
        List<ReelsModels.FeedItem> wl = Arrays.asList(
                videoItem("tiktok", "tt_t", "10", "u1", "P1", 1000L, "https://tiktok.com/@x/video/10", "").withWatchLater(true, 100L),
                videoItem("instagram", "ig_t", "20", "u2", "P2", 2000L, "https://instagram.com/reel/20", "").withWatchLater(true, 200L),
                videoItem("tiktok", "tt_t", "30", "u1", "P1", 3000L, "https://tiktok.com/@x/video/30", "").withWatchLater(true, 300L)
        );
        List<String> toRemove = ReelsFeedEngine.computeWatchLaterRemovalIds(wl, 1);
        check(toRemove.size() == 2, "Done Watching at index 1 in Watch Later removes items 0 and 1");
        check(toRemove.contains("tiktok:10") && toRemove.contains("instagram:20") && !toRemove.contains("tiktok:30"),
                "Unwatched Watch Later item 30 remains untouched");
    }

    private static void testHorizontalVariantPreferenceAndStageGeometry() {
        List<ReelsModels.MediaVariant> mixed = Arrays.asList(
                new ReelsModels.MediaVariant("https://cdn/vertical_1080.mp4", 1080, 1920),
                new ReelsModels.MediaVariant("https://cdn/horizontal_720.mp4", 1280, 720),
                new ReelsModels.MediaVariant("https://cdn/horizontal_1080.mp4", 1920, 1080)
        );
        ReelsModels.MediaVariant chosen = ReelsFeedEngine.selectPreferredVideoVariant(mixed, "https://cdn/fallback.mp4");
        check("https://cdn/horizontal_1080.mp4".equals(chosen.url) && chosen.isHorizontal(),
                "Prefers highest-res 16:9 horizontal stream over 9:16 vertical when both exist");

        List<ReelsModels.MediaVariant> verticalOnly = Arrays.asList(
                new ReelsModels.MediaVariant("https://cdn/v720.mp4", 720, 1280),
                new ReelsModels.MediaVariant("https://cdn/v1080.mp4", 1080, 1920)
        );
        ReelsModels.MediaVariant vChosen = ReelsFeedEngine.selectPreferredVideoVariant(verticalOnly, "");
        check("https://cdn/v1080.mp4".equals(vChosen.url) && !vChosen.isHorizontal(),
                "Selects sharpest vertical stream when no horizontal variant exists");

        // Adaptive TV stage on 4K 3840x2160 screen
        ReelsModels.StageRect hStage = ReelsFeedEngine.computeVideoStageRect(3840, 2160, 1920, 1080);
        check(hStage.isHorizontal && hStage.width() == 3840 && hStage.height() == 2160 && hStage.left == 0 && hStage.top == 0,
                "16:9 horizontal TikTok fills 4K TV edge-to-edge");

        ReelsModels.StageRect vStage = ReelsFeedEngine.computeVideoStageRect(3840, 2160, 1080, 1920);
        check(!vStage.isHorizontal && vStage.height() == 2160 && vStage.width() == 1215 && vStage.left > 0,
                "9:16 vertical Reel centers cleanly full-height with Twilight Aurora flanks");
    }

    private static void testSenderDisplayNameResolution() {
        Map<String, ReelsModels.SenderAlias> aliases = new LinkedHashMap<>();
        aliases.put("instagram:u1", new ReelsModels.SenderAlias("instagram", "u1", "handle_a", "Partner", 0xFFF472B6));
        aliases.put("tiktok:u2", new ReelsModels.SenderAlias("tiktok", "u2", "handle_b", "Sister", 0xFF34D399));

        ReelsModels.FeedItem ig = videoItem("instagram", "t", "1", "u1", "handle_a", 100L, "https://instagram.com/reel/1", "");
        ReelsModels.FeedItem tt = videoItem("tiktok", "t", "2", "u2", "handle_b", 200L, "https://tiktok.com/@b/video/2", "");
        ReelsModels.FeedItem unknown = videoItem("tiktok", "t", "3", "u3", "GuestUser", 300L, "https://tiktok.com/@g/video/3", "");

        check("Partner".equals(ReelsFeedEngine.resolveSenderDisplayName(ig, aliases)), "Resolves custom IG name");
        check("Sister".equals(ReelsFeedEngine.resolveSenderDisplayName(tt, aliases)), "Resolves custom TikTok name");
        check("GuestUser".equals(ReelsFeedEngine.resolveSenderDisplayName(unknown, aliases)), "Falls back to raw username");
    }

    private static void testRepositoryJsonRoundTrips() {
        Map<String, ReelsModels.SourceWatermark> wms = new LinkedHashMap<>();
        wms.put("instagram", new ReelsModels.SourceWatermark("instagram", "ig_1", "Couple IG", "tv_ig", "m99", 55555L, "m100"));
        wms.put("tiktok", new ReelsModels.SourceWatermark("tiktok", "tt_2", "Family TT", "tv_tt", "t77", 66666L, "t77"));
        Map<String, ReelsModels.SourceWatermark> parsedWms = ReelsRepository.parseWatermarksJson(
                ReelsRepository.serializeWatermarksJson(wms));
        check(parsedWms.size() == 2 && "m99".equals(parsedWms.get("instagram").lastWatchedMessageId)
                && parsedWms.get("tiktok").lastWatchedSentAtMs == 66666L, "Watermark JSON round-trip");

        Map<String, ReelsModels.SenderAlias> senders = new LinkedHashMap<>();
        senders.put("instagram:u1", new ReelsModels.SenderAlias("instagram", "u1", "raw1", "Alex", 0xFF38BDF8));
        Map<String, ReelsModels.SenderAlias> parsedSenders = ReelsRepository.parseSendersJson(
                ReelsRepository.serializeSendersJson(senders));
        check(parsedSenders.size() == 1 && "Alex".equals(parsedSenders.get("instagram:u1").displayName),
                "Sender alias JSON round-trip");

        List<ReelsModels.MediaVariant> vars = Collections.singletonList(
                new ReelsModels.MediaVariant("https://cdn/hd.mp4", 1920, 1080));
        ReelsModels.FeedItem item = new ReelsModels.FeedItem(
                "tiktok:555", "tiktok", ReelsModels.ItemKind.VIDEO, "tt_2", "555", "u1", "raw1", 90000L,
                "https://www.tiktok.com/@raw1/video/555", "https://cdn/hd.mp4", 1920, 1080, vars,
                Collections.<String>emptyList(), "", "https://cdn/thumb.jpg", "Great clip", "Watch this!", true, 91000L);
        List<ReelsModels.FeedItem> parsedItems = ReelsRepository.parseFeedItemsJson(
                ReelsRepository.serializeFeedItemsJson(Collections.singletonList(item)));
        check(parsedItems.size() == 1 && "Watch this!".equals(parsedItems.get(0).messageText)
                && parsedItems.get(0).watchLater && parsedItems.get(0).hasHorizontalVideo(),
                "FeedItem JSON round-trip preserves Watch Later, messageText, and horizontal variant");
    }
}
