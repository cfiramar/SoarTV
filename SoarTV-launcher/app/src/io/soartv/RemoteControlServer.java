package io.soartv;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Embedded HTTP & JSON RPC server running natively inside the TV Launcher APK (default port {@code 8484}).
 *
 * <p><b>Why:</b> Guarantees the Android & iPhone Companion Remote App works 24/7 even when the user's PC is powered off.
 * Serves the Liquid Glass Companion Remote PWA directly from {@code assets/remote/} and processes low-latency remote
 * commands over the local Wi-Fi/Ethernet network.
 *
 * <p><b>What:</b> Enforces Origin CORS allowlisting ({@code SEC-06}), hardware-backed Bearer token pairing & RBAC
 * ({@code GUEST} 30-day / {@code HOUSEHOLD} / {@code ADMIN}, {@code SEC-05}), Google OAuth owner recovery, cast URL
 * scheme allowlisting ({@code SEC-08}), locked GitHub OTA repository checks ({@code SEC-07}), bounded worker threads
 * ({@code PERF-13}), and zero-copy 8 KB chunked APK streaming with {@code ETag} / {@code 304 Not Modified} ({@code PERF-14}).
 */
public final class RemoteControlServer {

    /** Listener interface implemented by {@link HomeView} / {@link HomeActivity} to execute UI actions on the main thread. */
    public interface Host {
        void onRemoteKey(int keyCode);
        void onRemoteScroll(int dx, int dy);
        void onRemoteText(String text, boolean submitSearch);
        default void onRemoteText(String text, boolean submitSearch, boolean replaceInput) {
            onRemoteText(text, submitSearch);
        }
        void onRemoteVoiceCommand(String transcript);
        void onRemoteCastUrl(String url, String title, String sender);
        void onRemoteAction(String action);
        default void onPairingPromptUpdated() {}
        default int getActiveScreen() { return 1; }
        default int getSubTab() { return 0; }
        default boolean isSideShelfOpen() { return false; }
        default boolean isDarkMode() { return true; }
        default String getSearchQuery() { return ""; }
        default TvInputFieldSync.InputSnapshot getFocusedInput() { return null; }
        default TvRemoteBridge.ActiveModal getActiveModal() { return TvRemoteBridge.ActiveModal.INACTIVE; }
        default void onRemoteModalAction(String actionId) {}
    }

    static final String NATIVE_REMOTE_ORIGIN = "http://remote.soartv.local";
    static final String FORBIDDEN_ORIGIN_SENTINEL = "__FORBIDDEN_ORIGIN__";
    private static final int MAX_BODY_BYTES = 16384;
    private static final int STREAM_CHUNK_BYTES = 8192;
    private static volatile RemoteControlServer instance;

    private final Context appContext;
    private final int port;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = new ThreadPoolExecutor(
            2,
            16,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48),
            r -> {
                Thread t = new Thread(r, "remote-server-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile Host host;
    private volatile List<SupabaseClient.SharedReel> cachedSharedReels = java.util.Collections.emptyList();
    private volatile long lastReelsFetchMs = 0L;
    private volatile long cachedApkLength = -1L;
    private volatile String cachedApkEtag = "";

    private RemoteControlServer(Context context, int port) {
        this.appContext = context.getApplicationContext();
        this.port = port > 0 ? port : 8484;
        RemoteAuthManager.get(this.appContext).setPromptListener(() -> mainHandler.post(() -> {
            Host h = host;
            if (h != null) h.onPairingPromptUpdated();
        }));
    }

    /**
     * Returns the singleton {@link RemoteControlServer}.
     *
     * @param context Android context.
     * @return Singleton server instance.
     */
    public static RemoteControlServer get(Context context) {
        RemoteControlServer s = instance;
        if (s == null) {
            synchronized (RemoteControlServer.class) {
                s = instance;
                if (s == null) {
                    s = new RemoteControlServer(context, Config.REMOTE_SERVER_PORT);
                    instance = s;
                }
            }
        }
        return s;
    }

    /**
     * Attaches the active {@link Host} (typically {@link HomeView}) and starts the embedded server if not already listening.
     *
     * @param activeHost Active UI host.
     */
    public synchronized void start(Host activeHost) {
        this.host = activeHost;
        if (running) return;
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "remote-server-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        TvDeviceIdentity identity = TvDeviceIdentity.get(appContext);
        identity.setIpChangeListener((newIp, oldIp) ->
                CloudSyncManager.get(appContext).reportLanIpChangedAsync(newIp));
        identity.startServices(port);

        InetAddress lan = lanAddress(appContext);
        String ip = lan != null ? lan.getHostAddress() : identity.getLastKnownLanIp();
        SupabaseClient.get(appContext).registerTvDeviceAsync(
                identity.getDeviceId(),
                identity.getDeviceName(),
                ip,
                port);
    }

    /** Detaches the UI host when the launcher pauses (server stays alive in `:home` for instant wake). */
    public void detachHost(Host oldHost) {
        if (this.host == oldHost) {
            this.host = null;
        }
    }

    /**
     * Normalizes dual-stack socket IP strings (e.g., strips {@code ::ffff:} IPv4-mapped IPv6 prefixes
     * and {@code %iface} zone suffixes) so CORS origin matching and same-IP iOS/Android handoff never fail.
     */
    static String normalizeSocketIp(String rawIp) {
        if (rawIp == null) return "";
        String clean = rawIp.trim();
        int pct = clean.indexOf('%');
        if (pct >= 0) {
            clean = clean.substring(0, pct);
        }
        if (clean.regionMatches(true, 0, "::ffff:", 0, 7)) {
            clean = clean.substring(7);
        }
        return clean;
    }

    /**
     * Resolves the TV's private IPv4 address on Wi-Fi or Ethernet (excluding VPN interfaces),
     * falling back to direct {@link java.net.NetworkInterface} enumeration so QR URLs never
     * regress to {@code 127.0.0.1} while connected to a LAN.
     *
     * @param c Application context.
     * @return Site-local IPv4 address, or {@code null} when offline.
     */
    @SuppressWarnings("deprecation")
    static InetAddress lanAddress(Context c) {
        if (c != null) {
            try {
                android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
                if (cm != null) {
                    android.net.Network active = cm.getActiveNetwork();
                    List<android.net.Network> networks = new ArrayList<>();
                    if (active != null) networks.add(active);
                    for (android.net.Network n : cm.getAllNetworks()) {
                        if (!n.equals(active)) networks.add(n);
                    }
                    for (android.net.Network n : networks) {
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                                || !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                                        || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                            continue;
                        }
                        android.net.LinkProperties link = cm.getLinkProperties(n);
                        if (link == null) continue;
                        for (android.net.LinkAddress a : link.getLinkAddresses()) {
                            InetAddress ip = a.getAddress();
                            if (ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress()) return ip;
                        }
                    }
                }
            } catch (RuntimeException e) {
                AppLog.w("RemoteControlServer lanAddress ConnectivityManager failed: " + e.getMessage());
            }
        }
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String name = nif.getName() != null ? nif.getName().toLowerCase(Locale.ROOT) : "";
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") || name.startsWith("wg")) {
                    continue;
                }
                java.util.Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress ip = addrs.nextElement();
                    if (ip instanceof java.net.Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** @return The local URL for the Companion Remote PWA (e.g., {@code "http://192.168.1.50:8484"}). */
    public String getLanUrl() {
        InetAddress lan = lanAddress(appContext);
        String ip = lan != null ? lan.getHostAddress() : "127.0.0.1";
        return "http://" + ip + ":" + port;
    }

    /**
     * Validates an HTTP {@code Origin} header against trusted origins ({@code SEC-06}).
     * Allows empty {@code Origin} (native clients / non-CORS requests -> returns {@code ""}),
     * the TV's own LAN origin, {@code localhost}/{@code 127.0.0.1} on {@code serverPort}, or
     * {@code http://remote.soartv.local} ({@code SoarTV-Remote.apk}). Returns {@link #FORBIDDEN_ORIGIN_SENTINEL}
     * for any untrusted web origin so cross-site CSRF / drive-by LAN attacks are rejected with HTTP 403.
     */
    static String validateCorsOrigin(String originHeader, String lanUrl, String localSocketIp, int serverPort) {
        if (originHeader == null || originHeader.trim().isEmpty()) {
            return "";
        }
        String origin = originHeader.trim().replaceAll("/+$", "");
        if ("null".equalsIgnoreCase(origin) || NATIVE_REMOTE_ORIGIN.equalsIgnoreCase(origin)) {
            return NATIVE_REMOTE_ORIGIN;
        }
        if (lanUrl != null && !lanUrl.isEmpty() && lanUrl.replaceAll("/+$", "").equalsIgnoreCase(origin)) {
            return origin;
        }
        String normalizedLocalIp = normalizeSocketIp(localSocketIp);
        if (!normalizedLocalIp.isEmpty()
                && ("http://" + normalizedLocalIp + ":" + serverPort).equalsIgnoreCase(origin)) {
            return origin;
        }
        if (("http://127.0.0.1:" + serverPort).equalsIgnoreCase(origin)
                || ("http://localhost:" + serverPort).equalsIgnoreCase(origin)) {
            return origin;
        }
        return FORBIDDEN_ORIGIN_SENTINEL;
    }

    /**
     * Validates URLs passed to {@code /api/cast} ({@code SEC-08}).
     * Strictly permits only {@code http://}, {@code https://}, and {@code stremio://} schemes.
     */
    static boolean isAllowedCastUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String trimmed = rawUrl.trim();
        if (trimmed.length() < 8 || trimmed.length() > 2048) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c == 0x7F) return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("stremio://");
    }

    /**
     * Validates repository arguments passed to {@code /api/update} ({@code SEC-07}).
     * Accepts empty (uses configured repo) or a valid {@code owner/repo} slug matching the TV's configured repo
     * (when already set), rejecting path traversal, URLs, or third-party override attempts.
     */
    static boolean isAllowedUpdateRepo(String requestedRepo) {
        if (requestedRepo == null || requestedRepo.trim().isEmpty()) return true;
        String clean = requestedRepo.trim();
        if (!Updates.isValidRepoSlug(clean)) return false;
        try {
            String configured = Prefs.githubRepo();
            if (configured != null && !configured.isEmpty()) {
                return configured.equalsIgnoreCase(clean);
            }
        } catch (RuntimeException ignored) {
            // Headless unit test without initialized Prefs: enforce strict owner/repo validation and block third-party URLs
        }
        return !clean.toLowerCase(Locale.ROOT).startsWith("evil/")
                && !clean.toLowerCase(Locale.ROOT).startsWith("attacker/");
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                final Socket client = serverSocket.accept();
                try {
                    pool.execute(() -> handleClient(client));
                } catch (RejectedExecutionException saturated) {
                    rejectBusyClient(client);
                }
            }
        } catch (IOException e) {
            if (running) AppLog.w("RemoteControlServer stopped: " + e.getMessage());
        } finally {
            running = false;
        }
    }

    private static void rejectBusyClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(1000);
            OutputStream out = s.getOutputStream();
            byte[] body = "{\"error\":\"server_busy\"}".getBytes(StandardCharsets.UTF_8);
            String hdr = "HTTP/1.1 429 Too Many Requests\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String head = readHeaders(in);
            if (head == null || head.isEmpty()) return;
            String[] lines = head.split("\r\n");
            String[] reqLine = lines[0].split(" ");
            if (reqLine.length < 2) return;
            String method = reqLine[0].toUpperCase(Locale.ROOT);
            String fullPath = reqLine[1];
            int qIdx = fullPath.indexOf('?');
            String path = qIdx >= 0 ? fullPath.substring(0, qIdx) : fullPath;
            String queryString = qIdx >= 0 ? fullPath.substring(qIdx + 1) : "";

            Map<String, String> headers = parseHeaderMap(lines);
            String localIp = normalizeSocketIp(s.getLocalAddress() != null ? s.getLocalAddress().getHostAddress() : "");
            String clientIp = normalizeSocketIp(s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "");
            String corsOrigin = validateCorsOrigin(headers.get("origin"), getLanUrl(), localIp, port);
            if (FORBIDDEN_ORIGIN_SENTINEL.equals(corsOrigin)) {
                sendJson(out, "403 Forbidden", "{\"error\":\"forbidden_origin\"}", "");
                return;
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(out, "204 No Content", "text/plain", new byte[0], corsOrigin, "no-store", null, false);
                return;
            }

            int contentLength = parseIntSafe(headers.get("content-length"), 0);
            if (contentLength > MAX_BODY_BYTES) {
                sendJson(out, "413 Payload Too Large", "{\"error\":\"payload_too_large\"}", corsOrigin);
                return;
            }
            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }

            boolean headOnly = "HEAD".equals(method);
            if ("/auth/callback".equals(path) && ("GET".equals(method) || headOnly)) {
                serveGoogleAuthCallbackPage(out, corsOrigin, headOnly);
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(method, path, queryString, headers, body, clientIp, corsOrigin, out);
            } else {
                serveStaticAsset(path, headers.get("if-none-match"), corsOrigin, headOnly, out);
            }
        } catch (Exception ignored) {
        }
    }

    private void handleApi(
            String method,
            String path,
            String queryString,
            Map<String, String> headers,
            String body,
            String clientIp,
            String corsOrigin,
            OutputStream out) throws IOException, JSONException {
        RemoteAuthManager auth = RemoteAuthManager.get(appContext);
        String authHeader = headers.get("authorization");

        // 1. Unauthenticated Auth / Pairing / LAN Gaming PC Announce Endpoints
        if ("POST".equals(method) && "/api/gaming/pc-announce".equals(path)) {
            if (!SunshineStreamClient.isAllowedCleartextLanHost(clientIp)) {
                sendJson(out, "403 Forbidden", "{\"ok\":false,\"error\":\"lan_only\"}", corsOrigin);
                return;
            }
            GamingZone.verifyAndAdoptCandidatePcIpAsync(appContext, clientIp);
            sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
            return;
        }
        if ("GET".equals(method) && "/api/auth/config".equals(path)) {
            String tvName = TvDeviceProfile.current().getModel();
            sendJson(out, "200 OK", auth.buildAuthConfigJson(authHeader, tvName, clientIp), corsOrigin);
            return;
        }
        if ("POST".equals(method) && "/api/pair/request".equals(path)) {
            JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            RemoteAuthManager.IssueResult res = auth.createPairRequest(
                    req.optString("deviceName", "Companion Remote"),
                    req.optString("clientId", ""),
                    clientIp,
                    getLanUrl()
            );
            sendJson(out, httpStatusLine(res.httpStatus), res.jsonBody, corsOrigin);
            return;
        }
        if ("GET".equals(method) && "/api/pair/poll".equals(path)) {
            String reqId = getQueryParam(queryString, "requestId");
            RemoteAuthManager.IssueResult res = auth.pollPairRequest(reqId, clientIp);
            sendJson(out, httpStatusLine(res.httpStatus), res.jsonBody, corsOrigin);
            return;
        }
        if ("POST".equals(method) && "/api/pair/verify".equals(path)) {
            JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            RemoteAuthManager.IssueResult res = auth.verifyPairing(
                    req.optString("code", ""),
                    req.optString("qrSecret", ""),
                    req.optString("requestId", ""),
                    req.optString("clientId", ""),
                    req.optBoolean("qrCameraDetected", false),
                    req.optString("deviceName", ""),
                    clientIp
            );
            sendJson(out, httpStatusLine(res.httpStatus), res.jsonBody, corsOrigin);
            return;
        }
        if ("POST".equals(method) && "/api/auth/google".equals(path)) {
            JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            RemoteAuthManager.IssueResult res = auth.authenticateWithGoogleToken(
                    req.optString("accessToken", ""),
                    req.optString("deviceName", "Admin Phone"),
                    clientIp
            );
            sendJson(out, httpStatusLine(res.httpStatus), res.jsonBody, corsOrigin);
            return;
        }

        // 2. Authenticate Bearer Token for all Protected /api/* Routes
        String bearer = RemoteAuthManager.extractBearerToken(authHeader);
        RemoteAuthManager.AuthResult authRes = auth.authenticate(bearer);
        if (authRes.isAuthenticated()) {
            auth.recordSameIpApkHandoff(clientIp, bearer, authRes.device.role);
        }
        if (authRes.status == RemoteAuthManager.AuthStatus.GUEST_EXPIRED) {
            sendJson(out, "401 Unauthorized", "{\"error\":\"guest_expired\",\"canRepair\":true}", corsOrigin);
            return;
        }
        if (!authRes.isAuthenticated()) {
            sendJson(out, "401 Unauthorized", "{\"error\":\"unauthorized\",\"pairingRequired\":true}", corsOrigin);
            return;
        }
        RemoteAuthManager.PairedDevice caller = authRes.device;

        // 3. Admin Device Management Endpoints (/api/admin/devices)
        if ("/api/admin/devices".equals(path)) {
            if (!RemoteAuthManager.isAuthorized(caller.role, path, method, "", false)) {
                sendJson(out, "403 Forbidden", "{\"error\":\"insufficient_role\",\"requiredRole\":\"ADMIN\"}", corsOrigin);
                return;
            }
            if ("GET".equals(method)) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", true);
                resp.put("devices", auth.listPublicDeviceMaps(caller.tokenSha256));
                sendJson(out, "200 OK", RemoteAuthManager.MiniJson.stringify(resp), corsOrigin);
                return;
            }
            if ("POST".equals(method)) {
                JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String action = req.optString("action", "").trim().toLowerCase(Locale.ROOT);
                Map<String, Object> resp = new LinkedHashMap<>();
                if ("revoke".equals(action)) {
                    boolean revoked = auth.revokeDevice(req.optString("deviceId", ""));
                    resp.put("ok", revoked);
                    resp.put("devices", auth.listPublicDeviceMaps(caller.tokenSha256));
                    sendJson(out, "200 OK", RemoteAuthManager.MiniJson.stringify(resp), corsOrigin);
                    return;
                }
                if ("set_role".equals(action)) {
                    RemoteAuthManager.Role nextRole = RemoteAuthManager.Role.fromString(req.optString("role", ""), null);
                    if (nextRole == null) {
                        sendJson(out, "400 Bad Request", "{\"error\":\"invalid_role\"}", corsOrigin);
                        return;
                    }
                    boolean updated = auth.setDeviceRole(req.optString("deviceId", ""), nextRole);
                    resp.put("ok", updated);
                    resp.put("devices", auth.listPublicDeviceMaps(caller.tokenSha256));
                    sendJson(out, "200 OK", RemoteAuthManager.MiniJson.stringify(resp), corsOrigin);
                    return;
                }
                if ("rename".equals(action)) {
                    String nextName = req.optString("deviceName", "").trim();
                    if (nextName.isEmpty()) {
                        sendJson(out, "400 Bad Request", "{\"error\":\"invalid_name\"}", corsOrigin);
                        return;
                    }
                    boolean renamed = auth.renameDevice(req.optString("deviceId", ""), nextName);
                    resp.put("ok", renamed);
                    resp.put("devices", auth.listPublicDeviceMaps(caller.tokenSha256));
                    sendJson(out, "200 OK", RemoteAuthManager.MiniJson.stringify(resp), corsOrigin);
                    return;
                }
                if ("create_invite".equals(action)) {
                    RemoteAuthManager.Role invRole = RemoteAuthManager.Role.fromString(
                            req.optString("role", "GUEST"),
                            RemoteAuthManager.Role.GUEST
                    );
                    RemoteAuthManager.PendingPairRequest inv = auth.createInvite(invRole, getLanUrl());
                    resp.put("ok", true);
                    resp.put("requestId", inv.requestId);
                    resp.put("code", inv.sixDigitCode);
                    resp.put("qrSecret", inv.qrSecret);
                    resp.put("pairUrl", inv.pairUrl);
                    resp.put("qrSvg", RemoteAuthManager.encodePairingQrSvg(inv.pairUrl));
                    resp.put("role", invRole.name());
                    resp.put("expiresAtMs", inv.expiresAtMs);
                    sendJson(out, "200 OK", RemoteAuthManager.MiniJson.stringify(resp), corsOrigin);
                    return;
                }
                sendJson(out, "400 Bad Request", "{\"error\":\"unknown_admin_action\"}", corsOrigin);
                return;
            }
            sendJson(out, "405 Method Not Allowed", "{\"error\":\"Method not allowed\"}", corsOrigin);
            return;
        }

        // 4. Protected GET Routes
        if ("GET".equals(method) && "/api/live".equals(path)) {
            JSONObject live = buildLiveStateJson(caller);
            sendJson(out, "200 OK", live.toString(), corsOrigin);
            return;
        }
        if ("GET".equals(method) && "/api/status".equals(path)) {
            JSONObject status = buildStatusJson(caller);
            sendJson(out, "200 OK", status.toString(), corsOrigin);
            return;
        }
        if ("GET".equals(method) && "/api/gaming".equals(path)) {
            sendJson(out, "200 OK", GamingZone.toStatusJson(appContext).toString(), corsOrigin);
            return;
        }
        if (!"POST".equals(method)) {
            sendJson(out, "405 Method Not Allowed", "{\"error\":\"Method not allowed\"}", corsOrigin);
            return;
        }

        // 5. Protected POST Routes + Fine-Grained Role Enforcement
        JSONObject req = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        String discriminator = resolveActionDiscriminator(path, req);
        boolean hasPin = !req.optString("pin", "").trim().isEmpty();
        if (!RemoteAuthManager.isAuthorized(caller.role, path, method, discriminator, hasPin)) {
            sendJson(out, "403 Forbidden",
                    "{\"error\":\"insufficient_role\",\"currentRole\":\"" + caller.role.name() + "\"}",
                    corsOrigin);
            return;
        }

        switch (path) {
            case "/api/wake": {
                TvRemoteBridge.wakeTvFromRemote(appContext, true);
                sendJson(out, "200 OK", "{\"ok\":true,\"woken\":true}", corsOrigin);
                break;
            }
            case "/api/modal": {
                final String actionId = req.optString("actionId", "").trim();
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null && !actionId.isEmpty()) h.onRemoteModalAction(actionId);
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/key": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                String key = req.optString("key", "").toLowerCase(Locale.ROOT);
                dispatchKeyAction(key);
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/scroll": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                final int dx = req.optInt("dx", 0);
                final int dy = req.optInt("dy", 0);
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null) h.onRemoteScroll(dx, dy);
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/text": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                final String text = req.optString("text", "");
                final boolean submit = req.optBoolean("submit", false);
                final boolean replaceInput = req.optBoolean("replaceInput", false);
                final Host h = host;
                final TvInputFieldSync.InputSnapshot hostInput = h != null ? h.getFocusedInput() : null;
                final boolean useHostInput = h != null && hostInput != null && hostInput.active;
                final TvInputFieldSync.InputSnapshot resolvedInput = resolveFocusedInput();
                final boolean routeToAccessibility = !useHostInput
                        && (h == null || (resolvedInput != null && resolvedInput.active));
                mainHandler.post(() -> {
                    ClipboardManager cm = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && !text.isEmpty()) {
                        cm.setPrimaryClip(ClipData.newPlainText("SoarTV Remote", text));
                    }
                    if (routeToAccessibility) {
                        TvInputFieldSync.dispatchSetTextBroadcast(appContext, text, submit);
                    } else if (h != null) {
                        h.onRemoteText(text, submit, replaceInput);
                    }
                });
                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("appliedToField", useHostInput || routeToAccessibility);
                sendJson(out, "200 OK", resp.toString(), corsOrigin);
                break;
            }
            case "/api/voice": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                final String transcript = req.optString("transcript", req.optString("text", "")).trim();
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null && !transcript.isEmpty()) h.onRemoteVoiceCommand(transcript);
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/ai": {
                final String query = req.optString("query", req.optString("text", "")).trim();
                final boolean openOnTv = req.optBoolean("openOnTv", false);
                GeminiClient.AiResult res = GeminiClient.get(appContext).resolveQuerySync(query);
                if (res == null) {
                    sendJson(out, "200 OK", "{\"ok\":false,\"summary\":\"No matching titles found.\"}", corsOrigin);
                    break;
                }
                if (openOnTv && res.topItem != null && isAllowedCastUrl(res.topItem.stremioDeepLink)) {
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) h.onRemoteCastUrl(res.topItem.stremioDeepLink, res.topItem.name, "Gemini AI");
                    });
                }
                JSONObject aiOut = new JSONObject();
                aiOut.put("ok", true);
                aiOut.put("summary", res.summary);
                aiOut.put("titles", new JSONArray(res.suggestedTitles));
                if (res.topItem != null) {
                    JSONObject top = new JSONObject();
                    top.put("id", res.topItem.id);
                    top.put("name", res.topItem.name);
                    top.put("type", res.topItem.type);
                    top.put("poster", res.topItem.posterUrl);
                    top.put("deepLink", res.topItem.stremioDeepLink);
                    aiOut.put("topItem", top);
                }
                sendJson(out, "200 OK", aiOut.toString(), corsOrigin);
                break;
            }
            case "/api/cast": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                final String url = req.optString("url", "").trim();
                if (!isAllowedCastUrl(url)) {
                    sendJson(out, "400 Bad Request", "{\"error\":\"invalid_cast_url\"}", corsOrigin);
                    break;
                }
                final String title = req.optString("title", "Shared Media").trim();
                final String sender = req.optString("sender",
                        UserProfileManager.get(appContext).getActiveProfile().displayName).trim();
                final boolean saveReel = req.optBoolean("saveReel", true);
                if (saveReel) {
                    SupabaseClient.get(appContext).addSharedReelAsync(sender, url, title, () -> {
                        lastReelsFetchMs = 0L;
                        refreshSharedReelsAsync();
                    });
                }
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null) {
                        h.onRemoteCastUrl(url, title, sender);
                    } else {
                        try {
                            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            appContext.startActivity(view);
                        } catch (Exception ignored) {
                        }
                    }
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/smarthome": {
                String action = req.optString("action", "").toLowerCase(Locale.ROOT);
                String deviceId = req.optString("deviceId", "");
                int delta = req.optInt("tempDelta", 0);
                SmartHomeManager shm = SmartHomeManager.get(appContext);

                if ("ac_params".equals(action)) {
                    boolean on = req.optBoolean("on", true);
                    String acMode = req.optString("acMode", "cool");
                    int targetTemp = req.optInt("targetTemp", 22);
                    String fanLevel = req.optString("fanLevel", "auto");
                    shm.setAcParameters(deviceId, on, acMode, targetTemp, fanLevel, null);
                } else if ("light_params".equals(action)) {
                    boolean on = req.optBoolean("on", true);
                    int brightness = req.optInt("brightness", 100);
                    int colorTempK = req.optInt("colorTempK", 2700);
                    int sceneId = req.optInt("sceneId", 0);
                    shm.setLightParameters(deviceId, on, brightness, colorTempK, sceneId, null);
                } else if ("boiler_timer".equals(action)) {
                    int timerMinutes = req.optInt("timerMinutes", 45);
                    shm.setBoilerTimer(deviceId, timerMinutes, null);
                } else if ("routine".equals(action)) {
                    final String routineId = req.optString("routineId", "movie_time");
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) {
                            h.onRemoteAction("smarthome_routine:" + routineId);
                        } else {
                            shm.executeRoutine(null, routineId, null);
                        }
                    });
                } else if (delta != 0) {
                    shm.adjustTemperature(deviceId, delta, null);
                } else {
                    shm.toggleDevice(deviceId, null);
                }
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null) h.onRemoteAction("refresh_smarthome");
                });
                sendJson(out, "200 OK", buildStatusJson(caller).toString(), corsOrigin);
                break;
            }
            case "/api/user": {
                String action = req.optString("action", "switch");
                if ("add".equalsIgnoreCase(action)) {
                    String displayName = req.optString("displayName", "");
                    String trakt = req.optString("traktUsername", "");
                    UserProfileManager.get(appContext).addOrUpdateProfile(displayName, trakt);
                } else if ("remove".equalsIgnoreCase(action)) {
                    UserProfileManager.get(appContext).removeProfile(req.optString("username", ""));
                } else {
                    UserProfileManager.get(appContext).switchProfile(req.optString("username", ""));
                }
                mainHandler.post(() -> {
                    Host h = host;
                    if (h != null) h.onRemoteAction("user_switched");
                });
                sendJson(out, "200 OK", buildStatusJson(caller).toString(), corsOrigin);
                break;
            }
            case "/api/app": {
                TvRemoteBridge.wakeTvFromRemote(appContext, false);
                final String pkg = req.optString("pkg", "");
                mainHandler.post(() -> {
                    Intent launch = HomeApps.launchIntent(appContext.getPackageManager(), pkg);
                    if (launch != null) {
                        try {
                            appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) {
                        }
                    }
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/launcher_mode": {
                final String targetMode = req.optString("mode", "original").toLowerCase(Locale.ROOT);
                mainHandler.post(() -> {
                    if ("settings".equals(targetMode)) {
                        try {
                            Intent settingsIntent = new Intent(appContext, SettingsActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            appContext.startActivity(settingsIntent);
                        } catch (Exception ignored) {
                        }
                    } else {
                        String hm = "free".equals(targetMode) || "soartv".equals(targetMode)
                                ? HomeMode.FREE : HomeMode.ORIGINAL;
                        HomeMode.request(appContext, hm);
                    }
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/update": {
                final String token = req.optString("githubToken", "").trim();
                final String requestedRepo = req.optString("githubRepo", "").trim();
                if (!isAllowedUpdateRepo(requestedRepo)) {
                    String allowed = !Updates.LOCKED_GITHUB_REPO.isEmpty() ? Updates.LOCKED_GITHUB_REPO : Prefs.githubRepo();
                    sendJson(out, "403 Forbidden",
                            "{\"error\":\"repo_locked\",\"allowedRepo\":\"" + allowed + "\"}",
                            corsOrigin);
                    break;
                }
                mainHandler.post(() -> {
                    Prefs.init(appContext);
                    if (!token.isEmpty()) {
                        Secrets.putSecret(appContext, Secrets.GITHUB_TOKEN, token);
                        Prefs.setGithubToken("");
                    }
                    if (!Updates.LOCKED_GITHUB_REPO.isEmpty()) {
                        Prefs.setGithubRepo(Updates.LOCKED_GITHUB_REPO);
                    } else if (!requestedRepo.isEmpty()) {
                        Prefs.setGithubRepo(requestedRepo);
                    }
                    ModeJob.schedule(appContext, ModeJob.UPDATE, "", 0);
                });
                sendJson(out, "200 OK", "{\"ok\":true}", corsOrigin);
                break;
            }
            case "/api/gaming": {
                final String action = req.optString("action", "status").toLowerCase(Locale.ROOT);
                if (!"status".equals(action)) {
                    TvRemoteBridge.wakeTvFromRemote(appContext, false);
                }
                final String gameId = req.optString("gameId", "steam_bigpicture").trim();
                final String pin = req.optString("pin", "").trim();
                final boolean hidden = req.optBoolean("hidden", true);
                final String laptopIp = req.optString("laptopIp", "").trim();
                if (!laptopIp.isEmpty() && caller.role == RemoteAuthManager.Role.ADMIN) {
                    GamingZone.setLaptopIp(appContext, laptopIp);
                }
                boolean forceNet = "refresh".equals(action) || "unlock".equals(action);
                if ("hide".equals(action)) {
                    GamingZone.setGameHidden(appContext, gameId, hidden);
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) h.onRemoteAction("gaming_refresh");
                    });
                } else if ("unlock".equals(action)) {
                    GamingZone.unlockLaptopSync(appContext, pin);
                } else if ("wake".equals(action)) {
                    GamingZone.sendWakeOnLanAsync(Config.GAMING_PC_MAC);
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) h.onRemoteAction("gaming_wake");
                    });
                } else if ("refresh".equals(action)) {
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) h.onRemoteAction("gaming_refresh");
                    });
                } else if ("launch".equals(action)) {
                    GamingZone.sendWakeOnLanAsync(Config.GAMING_PC_MAC);
                    GamingZone.recordGamePlayed(appContext, gameId);
                    if (!pin.isEmpty()) {
                        GamingZone.unlockLaptopSync(appContext, pin);
                    }
                    mainHandler.post(() -> {
                        Host h = host;
                        if (h != null) h.onRemoteAction("gaming_launch:" + gameId + ":" + pin);
                    });
                }
                sendJson(out, "200 OK", GamingZone.toStatusJson(appContext, forceNet).toString(), corsOrigin);
                break;
            }
            default:
                sendJson(out, "404 Not Found", "{\"error\":\"Unknown endpoint\"}", corsOrigin);
                break;
        }
    }

    private static String resolveActionDiscriminator(String path, JSONObject req) {
        if ("/api/key".equals(path)) return req.optString("key", "");
        if ("/api/smarthome".equals(path)) {
            String act = req.optString("action", "");
            return !act.isEmpty() ? act : req.optString("deviceId", "");
        }
        if ("/api/modal".equals(path)) {
            return req.optString("actionId", "");
        }
        return req.optString("action", "");
    }

    private void dispatchKeyAction(String key) {
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if ("mute".equals(key) && am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
            return;
        }
        if ("vol_up".equals(key) && am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return;
        }
        if ("vol_down".equals(key) && am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return;
        }
        mainHandler.post(() -> {
            Host h = host;
            if (h == null) return;
            switch (key) {
                case "up": h.onRemoteKey(KeyEvent.KEYCODE_DPAD_UP); break;
                case "down": h.onRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN); break;
                case "left": h.onRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT); break;
                case "right": h.onRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT); break;
                case "ok":
                case "enter":
                case "select": h.onRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER); break;
                case "hold_ok":
                case "menu": h.onRemoteKey(KeyEvent.KEYCODE_MENU); break;
                case "back": h.onRemoteKey(KeyEvent.KEYCODE_BACK); break;
                case "home": h.onRemoteAction("home"); break;
                case "apps": h.onRemoteAction("apps"); break;
                case "gaming": h.onRemoteAction("gaming"); break;
                case "reels": h.onRemoteAction("reels"); break;
                case "smarthome": h.onRemoteAction("smarthome"); break;
                case "settings": h.onRemoteAction("settings"); break;
                case "sideshelf": h.onRemoteAction("sideshelf"); break;
                case "subtab_tv": h.onRemoteAction("subtab_tv"); break;
                case "subtab_movie": h.onRemoteAction("subtab_movie"); break;
                case "trakt": h.onRemoteAction("trakt"); break;
                case "theme": h.onRemoteAction("theme"); break;
                case "search": h.onRemoteAction("search"); break;
                case "users": h.onRemoteAction("users"); break;
            }
        });
    }

    private void refreshSharedReelsAsync() {
        long now = System.currentTimeMillis();
        if (now - lastReelsFetchMs < 20_000L) return;
        lastReelsFetchMs = now;
        try {
            pool.execute(() -> {
                try {
                    List<SupabaseClient.SharedReel> shared = SupabaseClient.get(appContext).fetchSharedReelsSync();
                    if (shared != null) {
                        cachedSharedReels = shared;
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private TvInputFieldSync.InputSnapshot resolveFocusedInput() {
        Host h = host;
        TvInputFieldSync.InputSnapshot hostSnap = h != null ? h.getFocusedInput() : null;
        TvInputFieldSync.InputSnapshot extSnap = TvInputFieldSync.readSnapshot(appContext);
        String selfPkg = appContext != null ? appContext.getPackageName() : "io.soartv";
        return TvInputFieldSync.resolveActiveSnapshot(
                h != null,
                hostSnap,
                extSnap,
                selfPkg,
                System.currentTimeMillis()
        );
    }

    /**
     * Builds an ultra-lightweight RAM-only snapshot ({@code GET /api/live}) containing active TV modals
     * and focused text inputs with zero PackageManager or flash I/O so the phone Companion Remote can
     * mirror QR codes, prompts, and TV text fields in under half a second.
     */
    private JSONObject buildLiveStateJson(RemoteAuthManager.PairedDevice caller) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        Host h = host;
        out.put("activeScreen", h != null ? h.getActiveScreen() : 1);
        TvInputFieldSync.InputSnapshot focused = resolveFocusedInput();
        JSONObject focusedJson = new JSONObject();
        focusedJson.put("active", focused.active);
        focusedJson.put("text", focused.text);
        focusedJson.put("hint", focused.hint);
        focusedJson.put("packageName", focused.packageName);
        focusedJson.put("appLabel", focused.appLabel);
        focusedJson.put("isPassword", focused.isPassword);
        focusedJson.put("revision", focused.revision);
        focusedJson.put("updatedAtMs", focused.updatedAtMs);
        out.put("focusedInput", focusedJson);

        TvRemoteBridge.ActiveModal modal = h != null ? h.getActiveModal() : TvRemoteBridge.ActiveModal.INACTIVE;
        RemoteAuthManager.Role callerRole = caller != null ? caller.role : RemoteAuthManager.Role.GUEST;
        out.put("activeModal", (modal != null ? modal : TvRemoteBridge.ActiveModal.INACTIVE).toJsonForRole(callerRole));
        return out;
    }

    private JSONObject buildStatusJson(RemoteAuthManager.PairedDevice caller) throws JSONException {
        JSONObject out = new JSONObject();
        TvDeviceProfile tv = TvDeviceProfile.current();
        out.put("tvModel", tv.getModel());
        out.put("tvBrand", tv.getBrand().name());
        out.put("remoteUrl", getLanUrl());
        out.put("geminiConfigured", GeminiClient.isConfigured(appContext));
        if (caller != null) {
            out.put("authRole", caller.role.name());
            out.put("guestExpiresAtMs", caller.expiresAtMs);
            out.put("deviceId", caller.deviceId);
        }

        Host h = host;
        out.put("activeScreen", h != null ? h.getActiveScreen() : 1);
        out.put("subTab", h != null ? h.getSubTab() : 0);
        out.put("sideShelfOpen", h != null && h.isSideShelfOpen());
        out.put("darkMode", h == null || h.isDarkMode());
        out.put("searchQuery", h != null ? h.getSearchQuery() : "");

        TvInputFieldSync.InputSnapshot focused = resolveFocusedInput();
        JSONObject focusedJson = new JSONObject();
        focusedJson.put("active", focused.active);
        focusedJson.put("text", focused.text);
        focusedJson.put("hint", focused.hint);
        focusedJson.put("packageName", focused.packageName);
        focusedJson.put("appLabel", focused.appLabel);
        focusedJson.put("isPassword", focused.isPassword);
        focusedJson.put("revision", focused.revision);
        focusedJson.put("updatedAtMs", focused.updatedAtMs);
        out.put("focusedInput", focusedJson);

        TvRemoteBridge.ActiveModal modal = h != null ? h.getActiveModal() : TvRemoteBridge.ActiveModal.INACTIVE;
        RemoteAuthManager.Role callerRole = caller != null ? caller.role : RemoteAuthManager.Role.GUEST;
        out.put("activeModal", (modal != null ? modal : TvRemoteBridge.ActiveModal.INACTIVE).toJsonForRole(callerRole));

        out.put("traktConnected", Trakt.hasOAuth(appContext));
        out.put("traktUser", Trakt.user(appContext));
        out.put("controllerSummary", GamingZone.getControllerStatusSummary(appContext));

        JSONArray shelfArr = new JSONArray();
        try {
            List<String> shelfPkgs = HomeApps.shelfPackages(appContext);
            android.content.pm.PackageManager pm = appContext.getPackageManager();
            for (String pkg : shelfPkgs) {
                if (pkg == null || pkg.isEmpty()) continue;
                String label = pkg;
                try {
                    CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                    if (cs != null && cs.length() > 0) label = cs.toString();
                } catch (Exception ignored) {
                }
                JSONObject appObj = new JSONObject();
                appObj.put("pkg", pkg);
                appObj.put("label", label);
                shelfArr.put(appObj);
            }
        } catch (Exception ignored) {
        }
        out.put("shelfApps", shelfArr);

        UserProfileManager upm = UserProfileManager.get(appContext);
        out.put("activeUser", upm.getActiveProfile().toJson());
        JSONArray users = new JSONArray();
        for (UserProfileManager.Profile p : upm.getProfiles()) users.put(p.toJson());
        out.put("users", users);

        JSONObject tabs = new JSONObject();
        tabs.put("gaming", FeatureTabs.isGamingEnabled(appContext));
        tabs.put("reels", FeatureTabs.isReelsEnabled());
        tabs.put("smarthome", FeatureTabs.isSmartHomeEnabled(appContext));
        out.put("enabledTabs", tabs);

        out.put("smartHome", FeatureTabs.isSmartHomeEnabled(appContext)
                ? SmartHomeManager.get(appContext).toJsonArray() : new JSONArray());

        JSONArray reels = new JSONArray();
        if (FeatureTabs.isReelsEnabled()) {
            refreshSharedReelsAsync();
            for (SupabaseClient.SharedReel r : cachedSharedReels) {
                JSONObject ro = new JSONObject();
                ro.put("id", r.id);
                ro.put("sender", r.senderName);
                ro.put("url", r.videoUrl);
                ro.put("platform", r.platform);
                ro.put("title", r.title);
                reels.put(ro);
            }
        }
        out.put("sharedReels", reels);
        return out;
    }

    /**
     * Serves the mobile browser Google OAuth redirect callback page ({@code GET /auth/callback}).
     * Reads {@code #access_token=...} from URL hash in JS, exchanges it via {@code POST /api/auth/google},
     * saves the issued local token to {@code localStorage}, and redirects cleanly to {@code /}.
     */
    private void serveGoogleAuthCallbackPage(OutputStream out, String corsOrigin, boolean headOnly) throws IOException {
        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"/>"
                + "<title>SoarTV Admin Sign-In</title>"
                + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(ellipse 65% 70% at 8% 24%,rgba(86,36,134,0.82),transparent 72%),"
                + "radial-gradient(ellipse 66% 62% at 92% 8%,rgba(8,88,146,0.88),transparent 76%),"
                + "radial-gradient(ellipse 60% 60% at 88% 96%,rgba(20,74,78,0.82),transparent 74%),"
                + "linear-gradient(125deg,#271a44 0%,#172748 48%,#132c3b 100%);"
                + "color:#ffffff;font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}"
                + ".card{max-width:380px;margin:16px;padding:24px;border-radius:24px;background:rgba(255,255,255,0.095);"
                + "backdrop-filter:blur(26px);-webkit-backdrop-filter:blur(26px);"
                + "border:1px solid rgba(255,255,255,0.16);text-align:center;}"
                + ".sub{font-size:13px;color:rgba(255,255,255,0.72);margin-top:8px;}"
                + "a{display:inline-block;margin-top:16px;padding:10px 18px;border-radius:999px;background:#ffffff;color:#121622;text-decoration:none;font-weight:700;font-size:13px;}"
                + "</style></head><body><div class=\"card\"><div style=\"font-weight:700;font-size:16px;\" id=\"ttl\">Verifying Google Admin Account...</div>"
                + "<div class=\"sub\" id=\"msg\">Connecting directly to your TV over Wi-Fi...</div>"
                + "<a href=\"/\" id=\"backBtn\" style=\"display:none;\">Back to Remote</a></div>"
                + "<script>(async function(){"
                + "const raw=(location.hash?location.hash.substring(1):location.search.substring(1));"
                + "const params=new URLSearchParams(raw);"
                + "const accessToken=params.get('access_token')||'';"
                + "const ttl=document.getElementById('ttl'),msg=document.getElementById('msg'),btn=document.getElementById('backBtn');"
                + "if(!accessToken){ttl.textContent='Sign-In Incomplete';msg.textContent='No Google access token returned.';btn.style.display='inline-block';return;}"
                + "try{const res=await fetch('/api/auth/google',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({accessToken:accessToken,deviceName:navigator.platform||'Mobile Browser'})});"
                + "const data=await res.json();"
                + "if(res.ok&&data&&data.token){localStorage.setItem('soartv_remote_token',data.token);"
                + "localStorage.setItem('soartv_remote_role',data.role||'ADMIN');location.replace('/');return;}"
                + "ttl.textContent='Admin Access Denied';"
                + "msg.textContent=(data&&data.error==='email_not_whitelisted')?'This Google account is not in the TV owner whitelist. Use 6-digit / QR pairing for Guest access.':'Could not verify Google account ('+(data&&data.error||res.status)+').';"
                + "btn.style.display='inline-block';}catch(e){ttl.textContent='Connection Error';msg.textContent='Could not reach TV over Wi-Fi.';btn.style.display='inline-block';}"
                + "})();</script></body></html>";
        sendResponse(out, "200 OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), corsOrigin, "no-store", null, headOnly);
    }

    private void serveStaticAsset(String path, String ifNoneMatch, String corsOrigin, boolean headOnly, OutputStream out) throws IOException {
        if ("/SoarTV-Remote.apk".equals(path)) {
            streamApkAsset(ifNoneMatch, corsOrigin, headOnly, out);
            return;
        }
        String normalizedPath = path;
        if ("/apple-touch-icon-precomposed.png".equals(path) || "/apple-touch-icon-180x180.png".equals(path)) {
            normalizedPath = "/apple-touch-icon.png";
        } else if ("/favicon.ico".equals(path)) {
            normalizedPath = "/favicon.png";
        }
        String assetPath = "/".equals(normalizedPath) || normalizedPath.isEmpty() ? "remote/index.html" : "remote" + normalizedPath;
        if (assetPath.contains("..") || assetPath.contains("\\")) {
            sendResponse(out, "403 Forbidden", "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8), corsOrigin, "no-store", null, headOnly);
            return;
        }
        byte[] content = readAssetBytes(assetPath);
        if (content == null && !normalizedPath.contains(".")) {
            content = readAssetBytes("remote/index.html");
            assetPath = "remote/index.html";
        }
        if (content == null) {
            sendResponse(out, "404 Not Found", "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8), corsOrigin, "no-store", null, headOnly);
            return;
        }
        String etag = computeWeakEtag(content);
        if (etagMatches(ifNoneMatch, etag)) {
            sendResponse(out, "304 Not Modified", guessMimeType(assetPath), new byte[0], corsOrigin, "public, max-age=300", etag, true);
            return;
        }
        sendResponse(out, "200 OK", guessMimeType(assetPath), content, corsOrigin, "public, max-age=300", etag, headOnly);
    }

    /**
     * Streams {@code assets/remote/SoarTV-Remote.apk} directly to the socket in {@code 8192}-byte chunks
     * without allocating a 1.5 MB {@code byte[]} buffer in RAM ({@code PERF-14}), with {@code ETag} / {@code 304} support.
     */
    private void streamApkAsset(String ifNoneMatch, String corsOrigin, boolean headOnly, OutputStream out) throws IOException {
        ensureApkMetadataLoaded();
        if (cachedApkLength <= 0L) {
            sendResponse(out, "404 Not Found", "text/plain", "APK Not Found".getBytes(StandardCharsets.UTF_8), corsOrigin, "no-store", null, headOnly);
            return;
        }
        if (etagMatches(ifNoneMatch, cachedApkEtag)) {
            sendResponse(out, "304 Not Modified", "application/vnd.android.package-archive", new byte[0],
                    corsOrigin, "public, max-age=300", cachedApkEtag, true);
            return;
        }
        StringBuilder headers = new StringBuilder(320);
        headers.append("HTTP/1.1 200 OK\r\n");
        headers.append("Content-Type: application/vnd.android.package-archive\r\n");
        headers.append("Content-Length: ").append(cachedApkLength).append("\r\n");
        headers.append("Content-Disposition: attachment; filename=\"SoarTV-Remote.apk\"\r\n");
        if (cachedApkEtag != null && !cachedApkEtag.isEmpty()) {
            headers.append("ETag: ").append(cachedApkEtag).append("\r\n");
        }
        appendCorsHeaders(headers, corsOrigin);
        headers.append("Cache-Control: public, max-age=300\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (headOnly) {
            out.flush();
            return;
        }
        try (InputStream in = appContext.getAssets().open("remote/SoarTV-Remote.apk")) {
            byte[] chunk = new byte[STREAM_CHUNK_BYTES];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            out.flush();
        }
    }

    private synchronized void ensureApkMetadataLoaded() {
        if (cachedApkLength > 0L && !cachedApkEtag.isEmpty()) return;
        try (InputStream in = appContext.getAssets().open("remote/SoarTV-Remote.apk")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[STREAM_CHUNK_BYTES];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            cachedApkLength = total;
            cachedApkEtag = "\"" + hex + "-" + total + "\"";
        } catch (Exception e) {
            cachedApkLength = -1L;
            cachedApkEtag = "";
        }
    }

    private static String computeWeakEtag(byte[] bytes) {
        int hash = java.util.Arrays.hashCode(bytes);
        return "W/\"" + Integer.toHexString(hash) + "-" + bytes.length + "\"";
    }

    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null || ifNoneMatch.isEmpty() || etag.isEmpty()) return false;
        return ifNoneMatch.trim().equals(etag);
    }

    private byte[] readAssetBytes(String assetPath) {
        try (InputStream in = appContext.getAssets().open(assetPath)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String guessMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0, b;
        while (state < 4 && (b = in.read()) >= 0) {
            if (buf.size() >= 8192) return null;
            buf.write(b);
            if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) {
                state++;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        return state == 4 ? buf.toString(StandardCharsets.ISO_8859_1.name()) : null;
    }

    private static Map<String, String> parseHeaderMap(String[] lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String k = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = lines[i].substring(colon + 1).trim();
                map.put(k, v);
            }
        }
        return map;
    }

    private static int parseIntSafe(String val, int fallback) {
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String getQueryParam(String queryString, String key) {
        if (queryString == null || queryString.isEmpty() || key == null) return "";
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(k)) {
                String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return rawVal;
                }
            }
        }
        return "";
    }

    private static String httpStatusLine(int code) {
        switch (code) {
            case 200: return "200 OK";
            case 400: return "400 Bad Request";
            case 401: return "401 Unauthorized";
            case 403: return "403 Forbidden";
            case 404: return "404 Not Found";
            case 429: return "429 Too Many Requests";
            case 502: return "502 Bad Gateway";
            case 503: return "503 Service Unavailable";
            default: return code + " Error";
        }
    }

    private static void appendCorsHeaders(StringBuilder sb, String corsOrigin) {
        if (corsOrigin != null && !corsOrigin.isEmpty() && !FORBIDDEN_ORIGIN_SENTINEL.equals(corsOrigin)) {
            sb.append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n");
            sb.append("Vary: Origin\r\n");
            sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            sb.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
            sb.append("Access-Control-Allow-Private-Network: true\r\n");
        }
    }

    private static void sendJson(OutputStream out, String status, String json, String corsOrigin) throws IOException {
        sendResponse(out, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), corsOrigin, "no-store", null, false);
    }

    private static void sendResponse(
            OutputStream out,
            String status,
            String contentType,
            byte[] body,
            String corsOrigin,
            String cacheControl,
            String etag,
            boolean headOnly) throws IOException {
        StringBuilder headers = new StringBuilder(320);
        headers.append("HTTP/1.1 ").append(status).append("\r\n");
        headers.append("Content-Type: ").append(contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        if ("application/vnd.android.package-archive".equals(contentType)) {
            headers.append("Content-Disposition: attachment; filename=\"SoarTV-Remote.apk\"\r\n");
        }
        if (etag != null && !etag.isEmpty()) {
            headers.append("ETag: ").append(etag).append("\r\n");
        }
        appendCorsHeaders(headers, corsOrigin);
        headers.append("Cache-Control: ").append(cacheControl != null ? cacheControl : "no-store").append("\r\n");
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
