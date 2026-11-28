package io.soartv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java domain engine for SoarTV's multi-source Reels &amp; Social Video Feed.
 *
 * <p><b>Why:</b> Encapsulates every business rule from the Reels specification in deterministic,
 * framework-free methods so the full behavior (oldest-first multi-source merging, non-emoji text
 * filtering, inclusive Done Watching dual/multi-app {@code 👀} reaction &amp; 10x burst planning,
 * Watch Later removal, horizontal 16:9 stream preference, and adaptive stage geometry) can be
 * rigorously verified via unit tests and shared consistently across Android TV and future platforms.
 *
 * <p><b>What:</b> Provides static pure functions over {@link ReelsModels} value objects.
 */
public final class ReelsFeedEngine {
    private ReelsFeedEngine() {}

    /** Official eye emoji used for chat reactions and caught-up celebration markers in Instagram &amp; TikTok. */
    public static final String EYE_EMOJI = "\uD83D\uDC40";

    /** Number of consecutive eye-emoji messages sent when the user catches up to the latest chat item. */
    public static final int CAUGHT_UP_EYE_MESSAGE_COUNT = 10;

    /** Target total elapsed window (ms) across the 10 randomized eye-emoji messages (~1.5s per user spec). */
    public static final int CAUGHT_UP_BURST_TOTAL_MS = 1500;

    /** Minimum inter-message randomized delay (ms) between consecutive eye-emoji messages. */
    public static final int MIN_BURST_STEP_DELAY_MS = 95;

    /** Maximum inter-message randomized delay (ms) between consecutive eye-emoji messages. */
    public static final int MAX_BURST_STEP_DELAY_MS = 240;

    /** Default vertical aspect ratio (9:16) when a short video's dimensions have not been reported yet. */
    public static final float DEFAULT_VERTICAL_ASPECT = 9f / 16f;

    /** Threshold above which a video is treated as landscape/horizontal (e.g. 16:9 TikToks). */
    public static final float HORIZONTAL_ASPECT_THRESHOLD = 1.05f;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    /**
     * Returns {@code true} if {@code raw} contains only whitespace, URLs, punctuation, and/or Unicode
     * emojis/pictographs (such as {@code "😂😂😂"}, {@code "👀"}, {@code "❤️🔥"}, skin-tone/ZWJ sequences)
     * with zero meaningful human text characters.
     *
     * <p>Preserves all scripts including Hebrew, English, Arabic, Cyrillic, CJK, and digits.
     */
    public static boolean isEmojiOnlyMessage(String raw) {
        return extractDisplayableText(raw).isEmpty();
    }

    /**
     * Strips bare URLs and checks whether meaningful non-emoji text remains.
     *
     * @param raw Raw chat message text or video caption note.
     * @return Cleaned human-readable text if meaningful text exists, or {@code ""} if empty/emoji-only.
     */
    public static String extractDisplayableText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String withoutUrls = URL_PATTERN.matcher(raw).replaceAll(" ").trim();
        if (withoutUrls.isEmpty()) return "";

        boolean hasMeaningfulChar = false;
        int len = withoutUrls.length();
        for (int i = 0; i < len; ) {
            int cp = withoutUrls.codePointAt(i);
            i += Character.charCount(cp);
            if (isEmojiOrIgnorableCodePoint(cp)) {
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                hasMeaningfulChar = true;
                break;
            }
        }
        if (!hasMeaningfulChar) return "";
        return MULTI_SPACE.matcher(withoutUrls).replaceAll(" ").trim();
    }

    private static boolean isEmojiOrIgnorableCodePoint(int cp) {
        if (Character.isWhitespace(cp)) return true;
        // Zero-Width Joiner, Variation Selectors, Keycap Combining Mark, Directional Marks
        if (cp == 0x200D || cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3
                || cp == 0x200B || cp == 0x200C || cp == 0x200E || cp == 0x200F) {
            return true;
        }
        // Skin tone modifiers (Fitzpatrick) & Regional Indicator Symbols (flags)
        if ((cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0x1F1E6 && cp <= 0x1F1FF)) return true;
        // Supplementary Plane Emoji & Pictographs (Emoticons, Transport, Supplemental Symbols, Extended-A)
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;
        // Miscellaneous Symbols, Dingbats, Miscellaneous Technical (watches/clocks/hearts/stars)
        if ((cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)) {
            return true;
        }
        return false;
    }

    /**
     * Merges items from any number of connected sources into a single chronological feed ordered
     * strictly <b>oldest first</b> ({@code sentAtMs ASC}), filtering out:
     * <ul>
     *   <li>Emoji-only text messages (including the TV's own {@code 👀} caught-up messages)</li>
     *   <li>Items already watched at or before each source's remembered {@link ReelsModels.SourceWatermark}</li>
     *   <li>Duplicate {@code (sourceId, messageId)} entries</li>
     * </ul>
     *
     * @param rawItems   Combined raw items fetched from all active sources.
     * @param watermarks Map of {@code sourceId -> SourceWatermark} storing last watched checkpoints.
     * @return Unmodifiable oldest-first list ready for instant full-screen playback.
     */
    public static List<ReelsModels.FeedItem> mergeOldestFirst(
            List<ReelsModels.FeedItem> rawItems,
            Map<String, ReelsModels.SourceWatermark> watermarks) {
        if (rawItems == null || rawItems.isEmpty()) return Collections.emptyList();
        Map<String, ReelsModels.SourceWatermark> wmMap = watermarks == null
                ? Collections.<String, ReelsModels.SourceWatermark>emptyMap()
                : watermarks;

        Set<String> seenKeys = new HashSet<>();
        List<ReelsModels.FeedItem> filtered = new ArrayList<>();
        for (ReelsModels.FeedItem item : rawItems) {
            if (item == null) continue;
            String dedupKey = item.messageId.isEmpty()
                    ? item.id
                    : (item.sourceId + ":" + item.messageId);
            if (!seenKeys.add(dedupKey)) continue;

            // Sanitize message text: drop emoji-only text slides; keep videos while clearing emoji-only notes.
            String cleanText = extractDisplayableText(item.messageText);
            if (item.kind == ReelsModels.ItemKind.TEXT_MESSAGE && cleanText.isEmpty()) {
                continue;
            }
            ReelsModels.FeedItem normalized = cleanText.equals(item.messageText)
                    ? item : item.withMessageText(cleanText);

            // Check per-source watermark so next session always resumes at the oldest unwatched item.
            ReelsModels.SourceWatermark wm = wmMap.get(normalized.sourceId);
            if (isAlreadyWatched(normalized, wm)) {
                continue;
            }
            filtered.add(normalized);
        }

        Collections.sort(filtered, OLDEST_FIRST_COMPARATOR);
        return Collections.unmodifiableList(filtered);
    }

    /** Returns {@code true} if {@code item} is at or before {@code watermark}. */
    public static boolean isAlreadyWatched(ReelsModels.FeedItem item, ReelsModels.SourceWatermark wm) {
        if (item == null || wm == null) return false;
        if (!wm.lastWatchedMessageId.isEmpty() && wm.lastWatchedMessageId.equals(item.messageId)) {
            return true;
        }
        if (wm.lastWatchedSentAtMs <= 0L) return false;
        if (item.sentAtMs < wm.lastWatchedSentAtMs) {
            return true;
        }
        if (item.sentAtMs == wm.lastWatchedSentAtMs
                && !wm.lastWatchedMessageId.isEmpty()
                && !item.messageId.isEmpty()
                && compareMessageIds(item.messageId, wm.lastWatchedMessageId) <= 0) {
            return true;
        }
        return false;
    }

    private static final Comparator<ReelsModels.FeedItem> OLDEST_FIRST_COMPARATOR =
            new Comparator<ReelsModels.FeedItem>() {
                @Override
                public int compare(ReelsModels.FeedItem a, ReelsModels.FeedItem b) {
                    int t = Long.compare(a.sentAtMs, b.sentAtMs);
                    if (t != 0) return t;
                    int s = a.sourceId.compareTo(b.sourceId);
                    if (s != 0) return s;
                    return compareMessageIds(a.messageId, b.messageId);
                }
            };

    /** Numeric-aware comparator for monotonic platform message IDs with lexicographic fallback. */
    static int compareMessageIds(String a, String b) {
        String sa = a == null ? "" : a;
        String sb = b == null ? "" : b;
        if (sa.equals(sb)) return 0;
        if (isAllDigits(sa) && isAllDigits(sb)) {
            if (sa.length() != sb.length()) return Integer.compare(sa.length(), sb.length());
        }
        return sa.compareTo(sb);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * Computes the exact multi-source Done Watching execution plan for a main-feed session.
     *
     * <p><b>Inclusive boundary:</b> Pressing Done Watching while on index {@code furthestWatchedIndex}
     * marks items {@code 0 .. furthestWatchedIndex} (inclusive) as watched, schedules a {@code 👀}
     * emoji reaction on the last watched message in <b>each</b> source that had items watched, and
     * schedules 10 consecutive {@code 👀} messages (~1.5s randomized burst) independently for any
     * source whose last watched item is also the very latest item in that source's chat.
     *
     * @param sessionQueue                Active chronological queue shown during the session.
     * @param furthestWatchedIndex        Inclusive 0-based index of the furthest item watched (e.g. index 2 = Video #3).
     * @param latestChatMessageIdBySource Map of {@code sourceId -> latest non-emoji message ID in that chat}.
     * @param existingWatermarks          Current stored watermarks per source (for preserving thread metadata).
     * @param rngSeed                     Seed for deterministic/randomized eye-burst jitter.
     * @return Immutable {@link ReelsModels.DoneWatchingPlan}.
     */
    public static ReelsModels.DoneWatchingPlan computeDoneWatchingPlan(
            List<ReelsModels.FeedItem> sessionQueue,
            int furthestWatchedIndex,
            Map<String, String> latestChatMessageIdBySource,
            Map<String, ReelsModels.SourceWatermark> existingWatermarks,
            long rngSeed) {
        if (sessionQueue == null || sessionQueue.isEmpty() || furthestWatchedIndex < 0) {
            return new ReelsModels.DoneWatchingPlan(
                    Collections.<ReelsModels.DoneWatchingAction>emptyList(),
                    Collections.<String>emptyList(),
                    Collections.<String, ReelsModels.SourceWatermark>emptyMap());
        }
        int lastIdx = Math.min(furthestWatchedIndex, sessionQueue.size() - 1);
        List<String> watchedIds = new ArrayList<>(lastIdx + 1);
        Map<String, ReelsModels.FeedItem> lastItemBySource = new LinkedHashMap<>();

        for (int i = 0; i <= lastIdx; i++) {
            ReelsModels.FeedItem item = sessionQueue.get(i);
            if (item == null) continue;
            watchedIds.add(item.id);
            lastItemBySource.put(item.sourceId, item);
        }

        Map<String, String> latestMap = latestChatMessageIdBySource == null
                ? Collections.<String, String>emptyMap()
                : latestChatMessageIdBySource;
        Map<String, ReelsModels.SourceWatermark> prevMap = existingWatermarks == null
                ? Collections.<String, ReelsModels.SourceWatermark>emptyMap()
                : existingWatermarks;

        List<ReelsModels.DoneWatchingAction> actions = new ArrayList<>();
        Map<String, ReelsModels.SourceWatermark> updatedWatermarks = new LinkedHashMap<>();
        int sourceOrdinal = 0;

        for (Map.Entry<String, ReelsModels.FeedItem> entry : lastItemBySource.entrySet()) {
            String sourceId = entry.getKey();
            ReelsModels.FeedItem lastItem = entry.getValue();
            ReelsModels.SourceWatermark prev = prevMap.get(sourceId);

            String threadId = !lastItem.threadId.isEmpty()
                    ? lastItem.threadId
                    : (prev != null ? prev.threadId : "");
            String threadTitle = prev != null ? prev.threadTitle : "";
            String selfSenderId = prev != null ? prev.selfSenderId : "";

            // Determine whether this source's last watched item is also the very last item in its chat.
            String latestInChat = latestMap.get(sourceId);
            boolean isLatestInChat = false;
            if (latestInChat != null && !latestInChat.isEmpty() && !lastItem.messageId.isEmpty()) {
                isLatestInChat = latestInChat.equals(lastItem.messageId);
            } else if ((latestInChat == null || latestInChat.isEmpty()) && !lastItem.messageId.isEmpty()) {
                // Fallback when caller didn't supply explicit latestChatMessageId: check if no newer item of this source remains in queue.
                boolean hasNewerInQueue = false;
                for (int j = lastIdx + 1; j < sessionQueue.size(); j++) {
                    ReelsModels.FeedItem rem = sessionQueue.get(j);
                    if (rem != null && sourceId.equals(rem.sourceId)) {
                        hasNewerInQueue = true;
                        break;
                    }
                }
                isLatestInChat = !hasNewerInQueue;
            }

            int[] delays = isLatestInChat
                    ? computeEyeBurstDelaysMs(CAUGHT_UP_EYE_MESSAGE_COUNT, rngSeed + (sourceOrdinal * 31L))
                    : new int[0];

            actions.add(new ReelsModels.DoneWatchingAction(
                    sourceId,
                    threadId,
                    lastItem.messageId,
                    lastItem.sentAtMs,
                    isLatestInChat,
                    delays));

            updatedWatermarks.put(sourceId, new ReelsModels.SourceWatermark(
                    sourceId,
                    threadId,
                    threadTitle,
                    selfSenderId,
                    lastItem.messageId,
                    lastItem.sentAtMs,
                    latestInChat != null && !latestInChat.isEmpty() ? latestInChat : lastItem.messageId));
            sourceOrdinal++;
        }

        return new ReelsModels.DoneWatchingPlan(actions, watchedIds, updatedWatermarks);
    }

    /**
     * Returns the inclusive list of Watch Later item IDs to remove when clicking Done Watching
     * inside the Watch Later folder ({@code 0 .. furthestWatchedIndex}).
     */
    public static List<String> computeWatchLaterRemovalIds(
            List<ReelsModels.FeedItem> watchLaterQueue,
            int furthestWatchedIndex) {
        if (watchLaterQueue == null || watchLaterQueue.isEmpty() || furthestWatchedIndex < 0) {
            return Collections.emptyList();
        }
        int lastIdx = Math.min(furthestWatchedIndex, watchLaterQueue.size() - 1);
        List<String> out = new ArrayList<>(lastIdx + 1);
        for (int i = 0; i <= lastIdx; i++) {
            ReelsModels.FeedItem item = watchLaterQueue.get(i);
            if (item != null && !item.id.isEmpty()) {
                out.add(item.id);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Generates randomized inter-message delays for sending {@code count} consecutive {@code 👀} messages
     * over ~1.5 seconds total ({@link #CAUGHT_UP_BURST_TOTAL_MS}), keeping natural timing jitter without
     * feeling sluggish. Entry {@code [0]} is always {@code 0ms} (first message sends immediately).
     */
    public static int[] computeEyeBurstDelaysMs(int count, long seed) {
        if (count <= 0) return new int[0];
        int[] delays = new int[count];
        delays[0] = 0;
        if (count == 1) return delays;

        Random rng = new Random(seed);
        int intervals = count - 1;
        int[] raw = new int[intervals];
        int rawSum = 0;
        for (int i = 0; i < intervals; i++) {
            raw[i] = MIN_BURST_STEP_DELAY_MS + rng.nextInt(MAX_BURST_STEP_DELAY_MS - MIN_BURST_STEP_DELAY_MS + 1);
            rawSum += raw[i];
        }
        double scale = rawSum > 0 ? ((double) CAUGHT_UP_BURST_TOTAL_MS / rawSum) : 1.0;
        for (int i = 0; i < intervals; i++) {
            int scaled = (int) Math.round(raw[i] * scale);
            delays[i + 1] = Math.max(MIN_BURST_STEP_DELAY_MS, Math.min(MAX_BURST_STEP_DELAY_MS, scaled));
        }
        return delays;
    }

    /**
     * Selects the best playable media stream URL from candidate variants, <b>explicitly preferring
     * horizontal (landscape 16:9) streams whenever available</b>, and picking highest resolution
     * within the preferred orientation.
     *
     * @param variants    Candidate video variants with width/height metadata.
     * @param fallbackUrl Direct fallback URL when {@code variants} is empty.
     * @return Selected {@link ReelsModels.MediaVariant}, or a fallback wrapper.
     */
    public static ReelsModels.MediaVariant selectPreferredVideoVariant(
            List<ReelsModels.MediaVariant> variants,
            String fallbackUrl) {
        ReelsModels.MediaVariant bestHorizontal = null;
        ReelsModels.MediaVariant bestVerticalOrUnknown = null;
        if (variants != null) {
            for (ReelsModels.MediaVariant v : variants) {
                if (v == null || v.url.isEmpty()) continue;
                if (v.isHorizontal()) {
                    if (bestHorizontal == null || v.pixelArea() > bestHorizontal.pixelArea()) {
                        bestHorizontal = v;
                    }
                } else {
                    if (bestVerticalOrUnknown == null || v.pixelArea() > bestVerticalOrUnknown.pixelArea()) {
                        bestVerticalOrUnknown = v;
                    }
                }
            }
        }
        if (bestHorizontal != null) return bestHorizontal;
        if (bestVerticalOrUnknown != null) return bestVerticalOrUnknown;
        return new ReelsModels.MediaVariant(fallbackUrl == null ? "" : fallbackUrl, 0, 0);
    }

    /**
     * Computes the adaptive TV viewport rectangle for a video or photo slide:
     * <ul>
     *   <li><b>Horizontal ({@code videoW / videoH > 1.05})</b>: Expands across the widescreen TV
     *       canvas (full-bleed 16:9 fit, {@code isHorizontal = true}).</li>
     *   <li><b>Vertical / Square ({@code videoH >= videoW} or unknown)</b>: Centers full-height
     *       vertical stage ({@code isHorizontal = false}) leaving Twilight Aurora flanks for the
     *       corner sender badge, text message bubble, and action rail.</li>
     * </ul>
     */
    public static ReelsModels.StageRect computeVideoStageRect(int viewW, int viewH, int videoW, int videoH) {
        int sw = Math.max(1, viewW);
        int sh = Math.max(1, viewH);
        float aspect = (videoW > 0 && videoH > 0)
                ? ((float) videoW / (float) videoH)
                : DEFAULT_VERTICAL_ASPECT;
        boolean horizontal = aspect >= HORIZONTAL_ASPECT_THRESHOLD;
        float screenAspect = (float) sw / (float) sh;

        int dstW;
        int dstH;
        if (aspect >= screenAspect) {
            dstW = sw;
            dstH = Math.max(1, Math.round(sw / aspect));
        } else {
            dstH = sh;
            dstW = Math.max(1, Math.round(sh * aspect));
        }
        int left = (sw - dstW) / 2;
        int top = (sh - dstH) / 2;
        return new ReelsModels.StageRect(left, top, left + dstW, top + dstH, horizontal);
    }

    /**
     * Resolves the human-readable sender name to show in the corner badge, honoring custom
     * user-defined account names across unlimited family/group members.
     */
    public static String resolveSenderDisplayName(
            ReelsModels.FeedItem item,
            Map<String, ReelsModels.SenderAlias> aliases) {
        if (item == null) return "Member";
        if (aliases != null) {
            if (!item.senderId.isEmpty()) {
                ReelsModels.SenderAlias exact = aliases.get(item.sourceId + ":" + item.senderId);
                if (exact != null && !exact.displayName.isEmpty()) return exact.displayName;
            }
            if (!item.senderRawName.isEmpty()) {
                String rawLower = item.senderRawName.toLowerCase(Locale.ROOT);
                for (ReelsModels.SenderAlias a : aliases.values()) {
                    if (a != null && a.sourceId.equals(item.sourceId)
                            && !a.rawUsername.isEmpty()
                            && a.rawUsername.toLowerCase(Locale.ROOT).equals(rawLower)
                            && !a.displayName.isEmpty()) {
                        return a.displayName;
                    }
                }
            }
        }
        if (!item.senderRawName.isEmpty()) return item.senderRawName;
        if (!item.senderId.isEmpty()) return item.senderId;
        return "Member";
    }

    /** Computes the map of latest non-emoji message IDs per source from a raw chat snapshot. */
    public static Map<String, String> computeLatestChatMessageIds(List<ReelsModels.FeedItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) return Collections.emptyMap();
        Map<String, ReelsModels.FeedItem> latestBySource = new HashMap<>();
        for (ReelsModels.FeedItem item : rawItems) {
            if (item == null || item.messageId.isEmpty()) continue;
            if (item.kind == ReelsModels.ItemKind.TEXT_MESSAGE && isEmojiOnlyMessage(item.messageText)) {
                continue;
            }
            ReelsModels.FeedItem cur = latestBySource.get(item.sourceId);
            if (cur == null || OLDEST_FIRST_COMPARATOR.compare(item, cur) > 0) {
                latestBySource.put(item.sourceId, item);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ReelsModels.FeedItem> e : latestBySource.entrySet()) {
            out.put(e.getKey(), e.getValue().messageId);
        }
        return Collections.unmodifiableMap(out);
    }

    /** Formats a calm compact relative timestamp ({@code "Just now"}, {@code "12m ago"}, {@code "3h ago"}, {@code "2d ago"}). */
    public static String formatRelativeTime(long sentAtMs, long nowMs) {
        if (sentAtMs <= 0L || nowMs <= 0L) return "";
        long diffSec = Math.max(0L, (nowMs - sentAtMs) / 1000L);
        if (diffSec < 60L) return "Just now";
        long mins = diffSec / 60L;
        if (mins < 60L) return mins + "m ago";
        long hours = mins / 60L;
        if (hours < 24L) return hours + "h ago";
        long days = hours / 24L;
        if (days < 30L) return days + "d ago";
        return (days / 30L) + "mo ago";
    }
}
