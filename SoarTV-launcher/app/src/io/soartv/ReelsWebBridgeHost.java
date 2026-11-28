package io.soartv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-TV residential WebView bridge host executing modular {@link ReelsSourcePlugin} JS bridges
 * strictly inside the isolated {@code :reels} process.
 *
 * <p><b>Why:</b> Enables native Instagram &amp; TikTok group-chat sync, {@code 👀} emoji reactions,
 * and ~1.5s caught-up 10x {@code 👀} bursts with Zero PC Dependency while keeping Android's heavy
 * Chromium WebView isolated from the 60fps {@code :home} launcher process.
 *
 * <p><b>What:</b> Configures persistent {@link CookieManager} sessions, injects per-plugin JS assets
 * ({@code reels/instagram_bridge.js}, {@code reels/tiktok_bridge.js}), and marshals typed async
 * callbacks for listing threads, fetching chronological messages, reacting, and sending bursts.
 */
public final class ReelsWebBridgeHost {
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final long BRIDGE_CALL_TIMEOUT_MS = 18_000L;
    private static final long PAGE_SETTLE_DELAY_MS = 1_400L;

    private static boolean dataDirInitialized;

    /** Raw JSON callback for a single bridge invocation. */
    public interface RawCallback {
        void onResult(boolean ok, Map<String, Object> data, String error);
    }

    /** Callback returning merged raw items &amp; latest chat message IDs across all connected sources. */
    public interface SyncAllCallback {
        void onComplete(
                List<ReelsModels.FeedItem> rawItems,
                Map<String, String> latestMessageIds,
                List<String> authNeededSources);
    }

    /** Callback returning completion status of a multi-source Done Watching execution. */
    public interface DoneExecutionCallback {
        void onComplete(int succeededCount, List<String> warnings);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, WebView> webViews = new LinkedHashMap<>();
    private final Map<String, Boolean> pageLoaded = new ConcurrentHashMap<>();
    private final Map<String, RawCallback> pendingCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger nextCallbackId = new AtomicInteger(1);

    /** Ensures isolated WebView storage directory inside {@code :reels} process on Android 9+. */
    public static synchronized void ensureProcessWebViewDir() {
        if (dataDirInitialized) return;
        dataDirInitialized = true;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix("reels");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Configures standard desktop Chrome WebView settings + persistent third-party cookies. */
    @SuppressLint("SetJavaScriptEnabled")
    public static void configureWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(DESKTOP_UA);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    public ReelsWebBridgeHost(Context context) {
        ensureProcessWebViewDir();
        this.context = context;
    }

    /** Destroys all hosted hidden WebViews cleanly when {@link ReelsPlayerActivity} finishes. */
    public void destroy() {
        mainHandler.post(() -> {
            pendingCallbacks.clear();
            for (WebView wv : webViews.values()) {
                try {
                    wv.stopLoading();
                    wv.destroy();
                } catch (RuntimeException ignored) {
                }
            }
            webViews.clear();
            pageLoaded.clear();
        });
    }

    /** Fetches threads from a specific chat-capable plugin (used during setup in {@link ReelsConnectActivity}). */
    public void listThreads(ReelsSourcePlugin plugin, RawCallback cb) {
        if (plugin == null || !plugin.supportsChatBridge()) {
            cb.onResult(false, null, "unsupported_plugin");
            return;
        }
        invokeBridge(plugin, plugin.inboxUrl(), "listThreads", "", cb);
    }

    /** Syncs messages across every configured chat plugin sequentially (polite, zero spam). */
    public void fetchAllConnectedFeeds(
            Map<String, ReelsModels.SourceWatermark> watermarks,
            SyncAllCallback callback) {
        List<ReelsSourcePlugin> active = new ArrayList<>();
        for (ReelsSourcePlugin p : ReelsSourceRegistry.getDefault().chatPlugins()) {
            ReelsModels.SourceWatermark wm = watermarks != null ? watermarks.get(p.sourceId()) : null;
            if (wm != null && wm.isConfigured()) active.add(p);
        }
        if (active.isEmpty()) {
            callback.onComplete(new ArrayList<ReelsModels.FeedItem>(), new LinkedHashMap<String, String>(),
                    new ArrayList<String>());
            return;
        }
        List<ReelsModels.FeedItem> allItems = new ArrayList<>();
        Map<String, String> latestIds = new LinkedHashMap<>();
        List<String> authNeeded = new ArrayList<>();
        fetchNextSource(active, 0, watermarks, allItems, latestIds, authNeeded, callback);
    }

    private void fetchNextSource(
            List<ReelsSourcePlugin> plugins,
            int index,
            Map<String, ReelsModels.SourceWatermark> watermarks,
            List<ReelsModels.FeedItem> allItems,
            Map<String, String> latestIds,
            List<String> authNeeded,
            SyncAllCallback callback) {
        if (index >= plugins.size()) {
            callback.onComplete(allItems, latestIds, authNeeded);
            return;
        }
        ReelsSourcePlugin plugin = plugins.get(index);
        ReelsModels.SourceWatermark wm = watermarks.get(plugin.sourceId());
        String targetUrl = buildThreadPageUrl(plugin, wm.threadId);
        String args = jsQuote(wm.threadId) + "," + wm.lastWatchedSentAtMs + ",80";
        invokeBridge(plugin, targetUrl, "fetchMessages", args, (ok, data, error) -> {
            if (!ok) {
                if ("not_logged_in".equals(error) || (error != null && error.contains("401"))) {
                    authNeeded.add(plugin.displayName());
                }
            } else if (data != null) {
                List<ReelsModels.FeedItem> parsed = parseBridgeItems(data);
                allItems.addAll(parsed);
                Map<String, String> sourceLatest = ReelsFeedEngine.computeLatestChatMessageIds(parsed);
                latestIds.putAll(sourceLatest);
                List<ReelsModels.SenderAlias> discovered = parseBridgeParticipants(plugin.sourceId(), data);
                ReelsRepository.get(context).discoverSenders(discovered);
                ReelsRepository.get(context).discoverSendersFromItems(parsed);
            }
            fetchNextSource(plugins, index + 1, watermarks, allItems, latestIds, authNeeded, callback);
        });
    }

    /** Executes a {@link ReelsModels.DoneWatchingPlan} across all touched chat sources (👀 reaction + optional 10x 👀). */
    public void executeDoneWatchingPlan(ReelsModels.DoneWatchingPlan plan, DoneExecutionCallback callback) {
        if (plan == null || plan.actions.isEmpty()) {
            callback.onComplete(0, new ArrayList<String>());
            return;
        }
        executeNextAction(plan.actions, 0, 0, new ArrayList<String>(), callback);
    }

    private void executeNextAction(
            List<ReelsModels.DoneWatchingAction> actions,
            int index,
            int okCount,
            List<String> warnings,
            DoneExecutionCallback callback) {
        if (index >= actions.size()) {
            callback.onComplete(okCount, warnings);
            return;
        }
        ReelsModels.DoneWatchingAction act = actions.get(index);
        ReelsSourcePlugin plugin = ReelsSourceRegistry.getDefault().find(act.sourceId);
        if (plugin == null || !plugin.supportsChatBridge() || act.threadId.isEmpty() || act.reactMessageId.isEmpty()) {
            executeNextAction(actions, index + 1, okCount, warnings, callback);
            return;
        }
        String targetUrl = buildThreadPageUrl(plugin, act.threadId);
        String reactArgs = jsQuote(act.threadId) + "," + jsQuote(act.reactMessageId) + "," + jsQuote(ReelsFeedEngine.EYE_EMOJI);
        invokeBridge(plugin, targetUrl, "reactEye", reactArgs, (reactOk, reactData, reactErr) -> {
            int nextOk = reactOk ? (okCount + 1) : okCount;
            if (!reactOk) {
                warnings.add(plugin.displayName() + " react: " + reactErr);
            }
            if (act.sendTenEyes && act.eyeBurstDelaysMs.length > 0) {
                StringBuilder delaysJson = new StringBuilder("[");
                for (int i = 0; i < act.eyeBurstDelaysMs.length; i++) {
                    if (i > 0) delaysJson.append(',');
                    delaysJson.append(act.eyeBurstDelaysMs[i]);
                }
                delaysJson.append(']');
                String burstArgs = jsQuote(act.threadId) + "," + jsQuote(delaysJson.toString())
                        + "," + jsQuote(ReelsFeedEngine.EYE_EMOJI);
                invokeBridge(plugin, targetUrl, "sendEyeBurst", burstArgs, (burstOk, burstData, burstErr) -> {
                    if (!burstOk) {
                        warnings.add(plugin.displayName() + " 10x eyes: " + burstErr);
                    }
                    executeNextAction(actions, index + 1, nextOk, warnings, callback);
                });
            } else {
                executeNextAction(actions, index + 1, nextOk, warnings, callback);
            }
        });
    }

    private String buildThreadPageUrl(ReelsSourcePlugin plugin, String threadId) {
        if (ReelsSourceRegistry.SOURCE_INSTAGRAM.equals(plugin.sourceId())
                && threadId != null && !threadId.isEmpty()) {
            return "https://www.instagram.com/direct/t/" + threadId + "/";
        }
        return plugin.inboxUrl();
    }

    private void invokeBridge(
            ReelsSourcePlugin plugin,
            String url,
            String method,
            String jsArgs,
            RawCallback cb) {
        mainHandler.post(() -> {
            final String cbId = String.valueOf(nextCallbackId.getAndIncrement());
            pendingCallbacks.put(cbId, cb);

            final Runnable timeout = () -> {
                RawCallback pending = pendingCallbacks.remove(cbId);
                if (pending != null) pending.onResult(false, null, "bridge_timeout");
            };
            mainHandler.postDelayed(timeout, BRIDGE_CALL_TIMEOUT_MS);

            WebView wv = getOrCreateWebView(plugin);
            final String bridgeVar = bridgeGlobalFor(plugin.sourceId());
            final String script = loadAssetUtf8(plugin.bridgeAssetPath());
            final Runnable runCall = () -> {
                String callJs = script + "\n"
                        + "try { if (window." + bridgeVar + " && typeof window." + bridgeVar + "." + method + " === 'function') {"
                        + "  window." + bridgeVar + "." + method + "(" + jsQuote(cbId)
                        + (jsArgs.isEmpty() ? "" : ("," + jsArgs)) + ");"
                        + "} else { window.SoarTvBridge.onBridgeResult(" + jsQuote(cbId) + ", '{\"ok\":false,\"error\":\"bridge_missing\"}'); }"
                        + "} catch (e) { window.SoarTvBridge.onBridgeResult(" + jsQuote(cbId) + ", JSON.stringify({ok:false,error:String(e)})); }";
                wv.evaluateJavascript(callJs, null);
            };

            String currentUrl = wv.getUrl();
            boolean loaded = Boolean.TRUE.equals(pageLoaded.get(plugin.sourceId()));
            if (loaded && currentUrl != null && sameOrigin(currentUrl, url)) {
                runCall.run();
                return;
            }
            wv.setWebViewClient(new WebViewClient() {
                private boolean dispatched;

                @Override
                public void onPageFinished(WebView view, String finishedUrl) {
                    pageLoaded.put(plugin.sourceId(), true);
                    if (!dispatched) {
                        dispatched = true;
                        mainHandler.postDelayed(runCall, PAGE_SETTLE_DELAY_MS);
                    }
                }
            });
            wv.loadUrl(url);
        });
    }

    @SuppressLint("JavascriptInterface")
    private WebView getOrCreateWebView(ReelsSourcePlugin plugin) {
        WebView existing = webViews.get(plugin.sourceId());
        if (existing != null) return existing;
        WebView wv = new WebView(context);
        configureWebView(wv);
        wv.addJavascriptInterface(new JsReceiver(), "SoarTvBridge");
        webViews.put(plugin.sourceId(), wv);
        return wv;
    }

    private final class JsReceiver {
        @JavascriptInterface
        public void onBridgeResult(String callbackId, String payloadJson) {
            RawCallback cb = pendingCallbacks.remove(callbackId);
            if (cb == null) return;
            mainHandler.post(() -> {
                try {
                    Map<String, Object> env = CloudVaultCrypto.JsonCodec.parseJsonObject(payloadJson);
                    boolean ok = CloudVaultCrypto.JsonCodec.optBoolean(env, "ok", false);
                    String err = CloudVaultCrypto.JsonCodec.optString(env, "error", "");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (env.get("data") instanceof Map)
                            ? (Map<String, Object>) env.get("data") : null;
                    cb.onResult(ok, data, err);
                } catch (RuntimeException e) {
                    cb.onResult(false, null, "bad_bridge_json");
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ReelsModels.FeedItem> parseBridgeItems(Map<String, Object> data) {
        Object raw = data.get("items");
        if (!(raw instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Map) rows.add((Map<String, Object>) o);
        }
        return ReelsRepository.parseFeedItemsJson(CloudVaultCrypto.JsonCodec.toJsonArrayString(rows));
    }

    @SuppressWarnings("unchecked")
    private static List<ReelsModels.SenderAlias> parseBridgeParticipants(String sourceId, Map<String, Object> data) {
        List<ReelsModels.SenderAlias> out = new ArrayList<>();
        Object raw = data.get("participants");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String sid = CloudVaultCrypto.JsonCodec.optString(m, "sender_id", "");
            if (sid.isEmpty()) continue;
            out.add(new ReelsModels.SenderAlias(
                    sourceId,
                    sid,
                    CloudVaultCrypto.JsonCodec.optString(m, "raw_username", ""),
                    CloudVaultCrypto.JsonCodec.optString(m, "display_name", ""),
                    0));
        }
        return out;
    }

    private static String bridgeGlobalFor(String sourceId) {
        if (ReelsSourceRegistry.SOURCE_INSTAGRAM.equals(sourceId)) return "__SoarTvIgBridge";
        if (ReelsSourceRegistry.SOURCE_TIKTOK.equals(sourceId)) return "__SoarTvTtBridge";
        return "__SoarTvCustomBridge_" + sourceId;
    }

    private static boolean sameOrigin(String a, String b) {
        try {
            URL ua = new URL(a);
            URL ub = new URL(b);
            return ua.getHost().equalsIgnoreCase(ub.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsQuote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private String loadAssetUtf8(String path) {
        if (path == null || path.isEmpty()) return "";
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
