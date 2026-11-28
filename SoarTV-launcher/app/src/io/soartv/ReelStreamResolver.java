package io.soartv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modular media stream resolver turning shared Instagram, TikTok, YouTube Shorts, or future
 * platform URLs into directly playable MP4 streams, photo carousels, or clean embed fallbacks.
 *
 * <p><b>Why:</b> Runs 100% natively on the Android TV over the user's residential home Wi-Fi IP
 * (Zero PC Dependency) using a pluggable chain of {@link PlatformStreamExtractor} instances so
 * adding new platforms never alters playback code. Explicitly prefers <b>horizontal (16:9)</b>
 * video variants whenever a source offers both horizontal and vertical streams.
 *
 * <p><b>What:</b> Resolves direct payload variants -&gt; TikWM HD/photo carousel -&gt; residential-IP
 * Instagram GraphQL/embed extraction -&gt; clean 9:16/16:9 WebView embed fallback.
 */
public final class ReelStreamResolver {
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Pattern IG_SHORTCODE = Pattern.compile(
            "instagram\\.com/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TT_VIDEO_ID = Pattern.compile(
            "tiktok\\.com/(?:@[^/]+/)?video/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_SHORTS_ID = Pattern.compile(
            "(?:youtube\\.com/shorts/|youtu\\.be/|v=)([A-Za-z0-9_-]{11})", Pattern.CASE_INSENSITIVE);

    /** Immutable resolved playback descriptor returned to {@link ReelsPlayerActivity}. */
    public static final class ResolvedMedia {
        public final String videoUrl;
        public final int width;
        public final int height;
        public final List<String> carouselImages;
        public final String audioUrl;
        public final String embedFallbackUrl;
        public final String thumbnailUrl;

        public ResolvedMedia(
                String videoUrl,
                int width,
                int height,
                List<String> carouselImages,
                String audioUrl,
                String embedFallbackUrl,
                String thumbnailUrl) {
            this.videoUrl = videoUrl == null ? "" : videoUrl.trim();
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.carouselImages = carouselImages == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(carouselImages));
            this.audioUrl = audioUrl == null ? "" : audioUrl.trim();
            this.embedFallbackUrl = embedFallbackUrl == null ? "" : embedFallbackUrl.trim();
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl.trim();
        }

        public boolean hasDirectVideo() {
            return !videoUrl.isEmpty();
        }

        public boolean hasCarousel() {
            return !carouselImages.isEmpty();
        }

        public boolean hasEmbedFallback() {
            return !embedFallbackUrl.isEmpty();
        }
    }

    /** Pluggable extractor contract for resolving a specific platform's share URL into direct media. */
    public interface PlatformStreamExtractor {
        boolean supports(ReelsModels.FeedItem item);
        ResolvedMedia extract(ReelsModels.FeedItem item);
    }

    private final List<PlatformStreamExtractor> extractors = new ArrayList<>();

    private static final ReelStreamResolver DEFAULT = createDefault();

    public static ReelStreamResolver getDefault() {
        return DEFAULT;
    }

    public static ReelStreamResolver createDefault() {
        ReelStreamResolver r = new ReelStreamResolver();
        r.registerExtractor(new DirectPayloadExtractor());
        r.registerExtractor(new TikTokStreamExtractor());
        r.registerExtractor(new InstagramStreamExtractor());
        return r;
    }

    /** Registers an additional {@link PlatformStreamExtractor} (Open/Closed extensibility). */
    public synchronized void registerExtractor(PlatformStreamExtractor extractor) {
        if (extractor != null) extractors.add(extractor);
    }

    /**
     * Resolves {@code item} synchronously through registered extractors, falling back to an official
     * embed player URL so playback never hits a dead end.
     */
    public ResolvedMedia resolveSync(ReelsModels.FeedItem item) {
        if (item == null) return new ResolvedMedia("", 0, 0, null, "", "", "");
        List<PlatformStreamExtractor> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(extractors);
        }
        for (PlatformStreamExtractor ex : snapshot) {
            if (!ex.supports(item)) continue;
            try {
                ResolvedMedia res = ex.extract(item);
                if (res != null && (res.hasDirectVideo() || res.hasCarousel())) {
                    return res;
                }
            } catch (RuntimeException ignored) {
            }
        }
        String embed = buildEmbedFallbackUrl(item.sourceId, item.shareUrl);
        return new ResolvedMedia("", item.videoWidth, item.videoHeight, item.carouselImages,
                item.audioUrl, embed, item.thumbnailUrl);
    }

    /** Builds a clean embed player URL for fallback WebView playback when raw MP4 extraction is blocked. */
    public static String buildEmbedFallbackUrl(String sourceId, String shareUrl) {
        if (shareUrl == null || shareUrl.trim().isEmpty()) return "";
        String url = shareUrl.trim();
        Matcher ig = IG_SHORTCODE.matcher(url);
        if (ig.find()) {
            return "https://www.instagram.com/reel/" + ig.group(1) + "/embed/";
        }
        Matcher tt = TT_VIDEO_ID.matcher(url);
        if (tt.find()) {
            return "https://www.tiktok.com/embed/v2/" + tt.group(1);
        }
        Matcher yt = YT_SHORTS_ID.matcher(url);
        if (yt.find()) {
            return "https://www.youtube.com/embed/" + yt.group(1) + "?autoplay=1&playsinline=1&rel=0";
        }
        return url;
    }

    // MARK: - Built-in Extractors

    /** Uses direct MP4 streams already supplied by the authenticated chat payload, preferring horizontal 16:9. */
    static final class DirectPayloadExtractor implements PlatformStreamExtractor {
        @Override
        public boolean supports(ReelsModels.FeedItem item) {
            return !item.variants.isEmpty() || !item.directVideoUrl.isEmpty() || !item.carouselImages.isEmpty();
        }

        @Override
        public ResolvedMedia extract(ReelsModels.FeedItem item) {
            if (!item.carouselImages.isEmpty() && item.variants.isEmpty() && item.directVideoUrl.isEmpty()) {
                return new ResolvedMedia("", item.videoWidth, item.videoHeight, item.carouselImages,
                        item.audioUrl, buildEmbedFallbackUrl(item.sourceId, item.shareUrl), item.thumbnailUrl);
            }
            ReelsModels.MediaVariant best = ReelsFeedEngine.selectPreferredVideoVariant(
                    item.variants, item.directVideoUrl);
            if (best.url.isEmpty()) return null;
            int w = best.width > 0 ? best.width : item.videoWidth;
            int h = best.height > 0 ? best.height : item.videoHeight;
            return new ResolvedMedia(best.url, w, h, item.carouselImages, item.audioUrl,
                    buildEmbedFallbackUrl(item.sourceId, item.shareUrl), item.thumbnailUrl);
        }
    }

    /** Resolves TikTok videos (preferring HD / horizontal 16:9 when available) and photo carousels via TikWM. */
    static final class TikTokStreamExtractor implements PlatformStreamExtractor {
        @Override
        public boolean supports(ReelsModels.FeedItem item) {
            return ReelsSourceRegistry.SOURCE_TIKTOK.equals(item.sourceId)
                    || (item.shareUrl.toLowerCase(Locale.ROOT).contains("tiktok.com"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public ResolvedMedia extract(ReelsModels.FeedItem item) {
            if (item.shareUrl.isEmpty()) return null;
            try {
                String api = "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(item.shareUrl, "UTF-8");
                String resp = httpGet(api, null);
                Map<String, Object> root = CloudVaultCrypto.JsonCodec.parseJsonObject(resp);
                Object dataObj = root.get("data");
                if (!(dataObj instanceof Map)) return null;
                Map<String, Object> data = (Map<String, Object>) dataObj;

                List<String> images = new ArrayList<>();
                Object rawImages = data.get("images");
                if (rawImages instanceof List) {
                    for (Object o : (List<?>) rawImages) {
                        if (o != null && !String.valueOf(o).trim().isEmpty()) {
                            images.add(String.valueOf(o).trim());
                        }
                    }
                }
                String music = CloudVaultCrypto.JsonCodec.optString(data, "music", "");
                String cover = CloudVaultCrypto.JsonCodec.optString(data, "cover", item.thumbnailUrl);
                int w = CloudVaultCrypto.JsonCodec.optInt(data, "width", 0);
                int h = CloudVaultCrypto.JsonCodec.optInt(data, "height", 0);

                List<ReelsModels.MediaVariant> candidates = new ArrayList<>();
                String hdPlay = CloudVaultCrypto.JsonCodec.optString(data, "hdplay", "");
                String play = CloudVaultCrypto.JsonCodec.optString(data, "play", "");
                String wmPlay = CloudVaultCrypto.JsonCodec.optString(data, "wmplay", "");
                if (!hdPlay.isEmpty()) candidates.add(new ReelsModels.MediaVariant(hdPlay, w > 0 ? w : 1080, h));
                if (!play.isEmpty()) candidates.add(new ReelsModels.MediaVariant(play, w, h));
                if (!wmPlay.isEmpty()) candidates.add(new ReelsModels.MediaVariant(wmPlay, w, h));

                ReelsModels.MediaVariant chosen = ReelsFeedEngine.selectPreferredVideoVariant(candidates, "");
                if (!images.isEmpty() && chosen.url.isEmpty()) {
                    return new ResolvedMedia("", w, h, images, music,
                            buildEmbedFallbackUrl(item.sourceId, item.shareUrl), cover);
                }
                if (chosen.url.isEmpty()) return null;
                return new ResolvedMedia(chosen.url, w, h, images, music,
                        buildEmbedFallbackUrl(item.sourceId, item.shareUrl), cover);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    /** Residential-IP Instagram Reel resolver for Watch Later or expired direct CDN links. */
    static final class InstagramStreamExtractor implements PlatformStreamExtractor {
        private static final String IG_GRAPHQL_DOC_ID = "8845758582119845";

        @Override
        public boolean supports(ReelsModels.FeedItem item) {
            return ReelsSourceRegistry.SOURCE_INSTAGRAM.equals(item.sourceId)
                    || item.shareUrl.toLowerCase(Locale.ROOT).contains("instagram.com");
        }

        @Override
        @SuppressWarnings("unchecked")
        public ResolvedMedia extract(ReelsModels.FeedItem item) {
            if (item.shareUrl.isEmpty()) return null;
            Matcher m = IG_SHORTCODE.matcher(item.shareUrl);
            if (!m.find()) return null;
            String shortcode = m.group(1);
            try {
                String form = "variables=" + URLEncoder.encode("{\"shortcode\":\"" + shortcode + "\"}", "UTF-8")
                        + "&doc_id=" + IG_GRAPHQL_DOC_ID;
                String resp = httpPostForm("https://www.instagram.com/graphql/query", form);
                Map<String, Object> root = CloudVaultCrypto.JsonCodec.parseJsonObject(resp);
                Object dataObj = root.get("data");
                if (dataObj instanceof Map) {
                    Object mediaObj = ((Map<String, Object>) dataObj).get("xdt_shortcode_media");
                    if (mediaObj instanceof Map) {
                        Map<String, Object> media = (Map<String, Object>) mediaObj;
                        String videoUrl = CloudVaultCrypto.JsonCodec.optString(media, "video_url", "");
                        String thumb = CloudVaultCrypto.JsonCodec.optString(media, "display_url", item.thumbnailUrl);
                        int w = 0;
                        int h = 0;
                        Object dimObj = media.get("dimensions");
                        if (dimObj instanceof Map) {
                            Map<String, Object> dm = (Map<String, Object>) dimObj;
                            w = CloudVaultCrypto.JsonCodec.optInt(dm, "width", 0);
                            h = CloudVaultCrypto.JsonCodec.optInt(dm, "height", 0);
                        }
                        if (!videoUrl.isEmpty()) {
                            return new ResolvedMedia(videoUrl, w, h, null, "",
                                    buildEmbedFallbackUrl(item.sourceId, item.shareUrl), thumb);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
            return null;
        }
    }

    private static String httpGet(String urlStr, String cookieHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieHeader);
        }
        try {
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readUtf8(in);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPostForm(String urlStr, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("X-IG-App-ID", "936619743392459");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(formBody.getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readUtf8(in);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        try {
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        } finally {
            in.close();
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
