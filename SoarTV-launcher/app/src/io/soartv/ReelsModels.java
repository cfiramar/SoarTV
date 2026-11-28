package io.soartv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable domain models for SoarTV's modular Reels &amp; Social Video Feed.
 *
 * <p><b>Why:</b> Isolates all feed, sender, watermark, Watch Later, aspect-ratio stage, and
 * Done-Watching plan data into pure-Java value objects with zero Android framework dependencies
 * so the core engine stays strictly typed, extensible to unlimited platforms/family members,
 * and 100% unit-testable on a headless host JVM.
 *
 * <p><b>What:</b> Defines {@link ItemKind}, {@link MediaVariant}, {@link FeedItem},
 * {@link ChatThreadInfo}, {@link SenderAlias}, {@link SourceWatermark},
 * {@link DoneWatchingAction}, {@link DoneWatchingPlan}, and {@link StageRect}.
 */
public final class ReelsModels {
    private ReelsModels() {}

    /** Distinguishes playable videos, multi-image photo carousels, and standalone chat text messages. */
    public enum ItemKind {
        VIDEO("video"),
        PHOTO_CAROUSEL("photo_carousel"),
        TEXT_MESSAGE("text");

        public final String wireValue;

        ItemKind(String wireValue) {
            this.wireValue = wireValue;
        }

        /** Parses a persisted or bridge wire string into an {@link ItemKind}, defaulting to {@link #VIDEO}. */
        public static ItemKind fromWire(String raw) {
            if (raw == null) return VIDEO;
            String norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("text".equals(norm) || "text_message".equals(norm)) return TEXT_MESSAGE;
            if ("photo_carousel".equals(norm) || "carousel".equals(norm) || "photos".equals(norm)) {
                return PHOTO_CAROUSEL;
            }
            return VIDEO;
        }
    }

    /** Represents a candidate playable media stream variant with optional dimensions for horizontal preference. */
    public static final class MediaVariant {
        public final String url;
        public final int width;
        public final int height;

        public MediaVariant(String url, int width, int height) {
            this.url = url == null ? "" : url.trim();
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        /** Returns {@code true} when dimensions explicitly indicate a landscape/horizontal (e.g. 16:9) stream. */
        public boolean isHorizontal() {
            return width > 0 && height > 0 && width > height;
        }

        /** Returns pixel area ({@code width * height}) for choosing the sharpest variant within an orientation. */
        public long pixelArea() {
            return (long) width * (long) height;
        }
    }

    /** Represents one chronological item (video, photo carousel, or non-emoji chat text message) in the feed. */
    public static final class FeedItem {
        public final String id;
        public final String sourceId;
        public final ItemKind kind;
        public final String threadId;
        public final String messageId;
        public final String senderId;
        public final String senderRawName;
        public final long sentAtMs;
        public final String shareUrl;
        public final String directVideoUrl;
        public final int videoWidth;
        public final int videoHeight;
        public final List<MediaVariant> variants;
        public final List<String> carouselImages;
        public final String audioUrl;
        public final String thumbnailUrl;
        public final String caption;
        public final String messageText;
        public final boolean watchLater;
        public final long watchLaterAddedAtMs;

        public FeedItem(
                String id,
                String sourceId,
                ItemKind kind,
                String threadId,
                String messageId,
                String senderId,
                String senderRawName,
                long sentAtMs,
                String shareUrl,
                String directVideoUrl,
                int videoWidth,
                int videoHeight,
                List<MediaVariant> variants,
                List<String> carouselImages,
                String audioUrl,
                String thumbnailUrl,
                String caption,
                String messageText,
                boolean watchLater,
                long watchLaterAddedAtMs) {
            String normSource = normalizeSourceId(sourceId);
            String cleanMsgId = safe(messageId);
            String cleanId = safe(id);
            if (cleanId.isEmpty()) {
                cleanId = !cleanMsgId.isEmpty() ? (normSource + ":" + cleanMsgId) : (normSource + ":" + sentAtMs);
            }
            this.id = cleanId;
            this.sourceId = normSource;
            this.kind = kind == null ? ItemKind.VIDEO : kind;
            this.threadId = safe(threadId);
            this.messageId = cleanMsgId;
            this.senderId = safe(senderId);
            this.senderRawName = safe(senderRawName);
            this.sentAtMs = sentAtMs;
            this.shareUrl = safe(shareUrl);
            this.directVideoUrl = safe(directVideoUrl);
            this.videoWidth = Math.max(0, videoWidth);
            this.videoHeight = Math.max(0, videoHeight);
            this.variants = variants == null
                    ? Collections.<MediaVariant>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(variants));
            this.carouselImages = carouselImages == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(carouselImages));
            this.audioUrl = safe(audioUrl);
            this.thumbnailUrl = safe(thumbnailUrl);
            this.caption = safe(caption);
            this.messageText = safe(messageText);
            this.watchLater = watchLater;
            this.watchLaterAddedAtMs = watchLaterAddedAtMs;
        }

        /** Returns a copy with updated {@code messageText}. */
        public FeedItem withMessageText(String newMessageText) {
            return new FeedItem(id, sourceId, kind, threadId, messageId, senderId, senderRawName, sentAtMs,
                    shareUrl, directVideoUrl, videoWidth, videoHeight, variants, carouselImages, audioUrl,
                    thumbnailUrl, caption, newMessageText, watchLater, watchLaterAddedAtMs);
        }

        /** Returns a copy with updated Watch Later status and timestamp. */
        public FeedItem withWatchLater(boolean saved, long addedAtMs) {
            return new FeedItem(id, sourceId, kind, threadId, messageId, senderId, senderRawName, sentAtMs,
                    shareUrl, directVideoUrl, videoWidth, videoHeight, variants, carouselImages, audioUrl,
                    thumbnailUrl, caption, messageText, saved, addedAtMs);
        }

        /** Returns a copy with resolved direct media stream URL and dimensions. */
        public FeedItem withResolvedMedia(String resolvedUrl, int w, int h, List<String> images, String audio) {
            List<String> nextImages = (images != null && !images.isEmpty()) ? images : carouselImages;
            ItemKind nextKind = (!nextImages.isEmpty() && (resolvedUrl == null || resolvedUrl.isEmpty()))
                    ? ItemKind.PHOTO_CAROUSEL : kind;
            return new FeedItem(id, sourceId, nextKind, threadId, messageId, senderId, senderRawName, sentAtMs,
                    shareUrl, safe(resolvedUrl), w > 0 ? w : videoWidth, h > 0 ? h : videoHeight, variants,
                    nextImages, !safe(audio).isEmpty() ? safe(audio) : audioUrl, thumbnailUrl, caption,
                    messageText, watchLater, watchLaterAddedAtMs);
        }

        /** Returns {@code true} if known dimensions or any variant is horizontal (landscape, e.g. 16:9). */
        public boolean hasHorizontalVideo() {
            if (videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight) return true;
            for (MediaVariant v : variants) {
                if (v != null && v.isHorizontal()) return true;
            }
            return false;
        }
    }

    /** Represents a customizable display name for a family/group participant on a given platform. */
    public static final class SenderAlias {
        public final String sourceId;
        public final String senderId;
        public final String rawUsername;
        public final String displayName;
        public final int avatarColor;

        public SenderAlias(String sourceId, String senderId, String rawUsername, String displayName, int avatarColor) {
            this.sourceId = normalizeSourceId(sourceId);
            this.senderId = safe(senderId);
            this.rawUsername = safe(rawUsername);
            this.displayName = safe(displayName);
            this.avatarColor = avatarColor;
        }

        /** Canonical composite map key ({@code sourceId + ":" + senderId}). */
        public String compositeKey() {
            return sourceId + ":" + senderId;
        }

        /** Best non-empty label to render on screen. */
        public String effectiveName() {
            if (!displayName.isEmpty()) return displayName;
            if (!rawUsername.isEmpty()) return rawUsername;
            return senderId.isEmpty() ? "Member" : senderId;
        }
    }

    /** Describes a selectable chat thread (group chat or 1-on-1 conversation) discovered from a source bridge. */
    public static final class ChatThreadInfo {
        public final String sourceId;
        public final String threadId;
        public final String title;
        public final boolean isGroup;
        public final List<SenderAlias> participants;

        public ChatThreadInfo(
                String sourceId,
                String threadId,
                String title,
                boolean isGroup,
                List<SenderAlias> participants) {
            this.sourceId = normalizeSourceId(sourceId);
            this.threadId = safe(threadId);
            this.title = safe(title);
            this.isGroup = isGroup;
            this.participants = participants == null
                    ? Collections.<SenderAlias>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(participants));
        }
    }

    /** Persisted per-source connection &amp; read-progress watermark. */
    public static final class SourceWatermark {
        public final String sourceId;
        public final String threadId;
        public final String threadTitle;
        public final String selfSenderId;
        public final String lastWatchedMessageId;
        public final long lastWatchedSentAtMs;
        public final String latestChatMessageId;

        public SourceWatermark(
                String sourceId,
                String threadId,
                String threadTitle,
                String selfSenderId,
                String lastWatchedMessageId,
                long lastWatchedSentAtMs,
                String latestChatMessageId) {
            this.sourceId = normalizeSourceId(sourceId);
            this.threadId = safe(threadId);
            this.threadTitle = safe(threadTitle);
            this.selfSenderId = safe(selfSenderId);
            this.lastWatchedMessageId = safe(lastWatchedMessageId);
            this.lastWatchedSentAtMs = Math.max(0L, lastWatchedSentAtMs);
            this.latestChatMessageId = safe(latestChatMessageId);
        }

        /** Returns {@code true} if a target chat thread has been selected for this source. */
        public boolean isConfigured() {
            return !threadId.isEmpty();
        }
    }

    /** Per-source reaction &amp; caught-up burst instruction emitted by {@link ReelsFeedEngine#computeDoneWatchingPlan}. */
    public static final class DoneWatchingAction {
        public final String sourceId;
        public final String threadId;
        public final String reactMessageId;
        public final long reactSentAtMs;
        public final boolean sendTenEyes;
        public final int[] eyeBurstDelaysMs;

        public DoneWatchingAction(
                String sourceId,
                String threadId,
                String reactMessageId,
                long reactSentAtMs,
                boolean sendTenEyes,
                int[] eyeBurstDelaysMs) {
            this.sourceId = normalizeSourceId(sourceId);
            this.threadId = safe(threadId);
            this.reactMessageId = safe(reactMessageId);
            this.reactSentAtMs = reactSentAtMs;
            this.sendTenEyes = sendTenEyes;
            this.eyeBurstDelaysMs = eyeBurstDelaysMs == null ? new int[0] : eyeBurstDelaysMs.clone();
        }
    }

    /** Complete result of clicking Done Watching on the main chronological Reels feed. */
    public static final class DoneWatchingPlan {
        public final List<DoneWatchingAction> actions;
        public final List<String> watchedItemIds;
        public final Map<String, SourceWatermark> updatedWatermarks;

        public DoneWatchingPlan(
                List<DoneWatchingAction> actions,
                List<String> watchedItemIds,
                Map<String, SourceWatermark> updatedWatermarks) {
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.watchedItemIds = Collections.unmodifiableList(new ArrayList<>(watchedItemIds));
            this.updatedWatermarks = Collections.unmodifiableMap(new LinkedHashMap<>(updatedWatermarks));
        }

        /** Looks up the planned action for a given {@code sourceId}, or {@code null} if that source wasn't watched. */
        public DoneWatchingAction actionForSource(String sourceId) {
            String key = normalizeSourceId(sourceId);
            for (DoneWatchingAction a : actions) {
                if (a.sourceId.equals(key)) return a;
            }
            return null;
        }
    }

    /** Viewport rectangle computed for adaptive 16:9 horizontal vs 9:16 vertical playback. */
    public static final class StageRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final boolean isHorizontal;

        public StageRect(int left, int top, int right, int bottom, boolean isHorizontal) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.isHorizontal = isHorizontal;
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }
    }

    static String normalizeSourceId(String raw) {
        if (raw == null) return "other";
        String clean = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return clean.isEmpty() ? "other" : clean;
    }

    static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
