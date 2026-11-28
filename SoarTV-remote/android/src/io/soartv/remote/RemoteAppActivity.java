package io.soartv.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardened Native Android Companion Remote Activity for SoarTV.
 *
 * <p><b>Why:</b> Eliminates weak {@code file://} WebView sandboxing ({@code SEC-11}), disables USB
 * backup token extraction ({@code SEC-12}), handles {@code soartvremote://auth} and {@code soartvremote://pair}
 * deep links, provides fast native Java Wi-Fi {@code /24} TV auto-discovery (bypassing Chromium's
 * 6-socket per-origin limit), grants camera access for in-app QR pairing, and provides
 * hardware-backed {@link BiometricPrompt} + {@code AndroidKeyStore} fingerprint wireless PC unlock.
 */
public final class RemoteAppActivity extends Activity {

    private static final String LOCAL_HOST = "remote.soartv.local";
    private static final String START_URL = "http://" + LOCAL_HOST + "/index.html?native=1";
    private static final String BIOMETRIC_PREFS = "soartv_remote_biometric";
    private static final String PREF_ENC_PIN = "enc_pin";
    private static final String PREF_ENC_IV = "enc_iv";
    private static final String KEYSTORE_ALIAS = "soartv_pc_unlock_pin_key";
    private static final int REQ_CAMERA_PERMISSION = 1001;
    private static final int REQ_NATIVE_QR_CAMERA = 1002;
    private static final int DEFAULT_TV_PORT = 8484;

    private FrameLayout rootContainer;
    private WebView webView;
    private volatile boolean pageLoaded = false;
    private volatile boolean trustedOriginActive = true;
    private volatile String pendingGoogleAccessToken = null;
    private volatile String pendingQrPairUri = null;
    private volatile String lastDiscoveredTvUrl = "";
    private PermissionRequest pendingWebPermissionRequest = null;
    private boolean pendingNativeQrOpen = false;
    private CancellationSignal activeBiometricSignal;

    // Native Camera2 QR Scanner state (works natively without HTTPS WebRTC restrictions)
    private FrameLayout nativeQrOverlay;
    private TextureView qrTextureView;
    private CameraDevice qrCameraDevice;
    private CameraCaptureSession qrCaptureSession;
    private ImageReader qrImageReader;
    private HandlerThread qrCameraThread;
    private Handler qrCameraHandler;
    private Size qrPreviewSize;
    private volatile boolean qrScannerActive = false;
    private volatile boolean qrLockDispatched = false;
    private volatile long lastQrFrameAnalyzedMs = 0L;
    private int qrConsecutiveHits = 0;
    private final byte[] qrLumaBuf = new byte[160 * 120];
    private boolean currentDarkTheme = true;
    private int cachedSafeTopDp = 0;
    private int cachedSafeBottomDp = 0;

    private static final int DARK_STATUS_BAR_COLOR = Color.parseColor("#0E1024");
    private static final int DARK_NAV_BAR_COLOR = Color.parseColor("#0A1522");
    private static final int LIGHT_STATUS_BAR_COLOR = Color.parseColor("#F7F9FF");
    private static final int LIGHT_NAV_BAR_COLOR = Color.parseColor("#EEF3FC");

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(DARK_STATUS_BAR_COLOR);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // SEC-11: Strictly disable all file:// and content:// access inside WebView
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Allow http://remote.soartv.local to fetch http://<TV_LAN_IP>:8484
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.addJavascriptInterface(new SoarTvNativeBridge(), "SoarTvNative");
        webView.setWebViewClient(new HardenedRemoteWebViewClient());
        webView.setWebChromeClient(new QrCameraWebChromeClient());

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(DARK_STATUS_BAR_COLOR);
        rootContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(rootContainer);
        installImeInsetsResizeListener();
        applySystemBarAppearance(true);
        handleIncomingIntent(getIntent());
        triggerBackgroundTvDiscovery("");
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        triggerBackgroundTvDiscovery(lastDiscoveredTvUrl);
    }

    @Override
    protected void onPause() {
        closeNativeQrScanner(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getData();
        if (uri == null || !"soartvremote".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
        if ("pair".equals(host)) {
            String rawUri = uri.toString();
            if (pageLoaded && webView != null) {
                deliverQrPairUriToJs(rawUri);
            } else {
                pendingQrPairUri = rawUri;
            }
            return;
        }
        String token = extractAccessToken(uri);
        if (token.isEmpty()) {
            return;
        }
        if (pageLoaded && webView != null) {
            deliverGoogleTokenToJs(token);
        } else {
            pendingGoogleAccessToken = token;
        }
    }

    private void deliverGoogleTokenToJs(final String token) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window.onSoarTvGoogleAuthCallback && window.onSoarTvGoogleAuthCallback("
                    + jsonQuote(token) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverQrPairUriToJs(final String rawUri) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window.onSoarTvQrScanned && window.onSoarTvQrScanned("
                    + jsonQuote(rawUri) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverDiscoveredTvToJs(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return;
        lastDiscoveredTvUrl = baseUrl;
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive || !pageLoaded) return;
            String js = "window.onSoarTvDiscovered && window.onSoarTvDiscovered("
                    + jsonQuote(baseUrl) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void evalCallbackOnJs(final String fnName, final String argsLiteral) {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive) return;
            String js = "window." + fnName + " && window." + fnName + "(" + argsLiteral + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void triggerBackgroundTvDiscovery(final String hintUrl) {
        Thread t = new Thread(() -> {
            String found = discoverTvOnLan(hintUrl, DEFAULT_TV_PORT);
            if (found != null && !found.isEmpty()) {
                deliverDiscoveredTvToJs(found);
            }
        }, "soartv-lan-discovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Phase 1 (Instant ~15-240ms): Broadcasts {@code SOARTV_TV_DISCOVER_V1} on UDP port {@code 8484}
     * to discover ALL active household Android TVs on the LAN regardless of DHCP IP changes.
     */
    static List<String> discoverTvsViaUdp(int port, int listenTimeoutMs) {
        int targetPort = port > 0 ? port : DEFAULT_TV_PORT;
        List<String> replies = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        byte[] probe = "{\"op\":\"SOARTV_TV_DISCOVER_V1\"}".getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(120);

            Set<InetAddress> broadcastTargets = new LinkedHashSet<>();
            try {
                broadcastTargets.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets != null && nets.hasMoreElements()) {
                    NetworkInterface nif = nets.nextElement();
                    if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        if (ia != null && ia.getBroadcast() != null) {
                            broadcastTargets.add(ia.getBroadcast());
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            for (InetAddress target : broadcastTargets) {
                try {
                    sock.send(new DatagramPacket(probe, probe.length, target, targetPort));
                } catch (IOException ignored) {
                }
            }

            long deadline = System.currentTimeMillis() + Math.max(120, listenTimeoutMs);
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String text = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                    if (text.startsWith("{") && text.contains("SOARTV_TV_HERE_V1")) {
                        String senderIp = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : "";
                        String dedupeKey = extractJsonStringField(text, "deviceId");
                        if (dedupeKey.isEmpty()) dedupeKey = senderIp;
                        if (!dedupeKey.isEmpty() && seenKeys.add(dedupeKey)) {
                            replies.add(text);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    if (!replies.isEmpty()) break;
                }
            }
        } catch (Exception ignored) {
        }
        return replies;
    }

    private static String extractJsonStringField(String json, String fieldName) {
        if (json == null || fieldName == null) return "";
        String needle = "\"" + fieldName + "\":\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return "";
        int start = idx + needle.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end).trim() : "";
    }

    private static int extractJsonIntField(String json, String fieldName, int fallback) {
        if (json == null || fieldName == null) return fallback;
        String needle = "\"" + fieldName + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return fallback;
        int start = idx + needle.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end <= start) return fallback;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Discovers a SoarTV on LAN: Phase 1 runs instant UDP broadcast (~15ms), Phase 2 falls back to
     * parallel native Java TCP {@code /24} subnet scan ({@code 180ms} connect timeout).
     */
    static String discoverTvOnLan(String hintUrl, int port) {
        int targetPort = port > 0 ? port : DEFAULT_TV_PORT;
        List<String> udpReplies = discoverTvsViaUdp(targetPort, 240);
        for (String reply : udpReplies) {
            String lanIp = extractJsonStringField(reply, "lanIp");
            int replyPort = extractJsonIntField(reply, "port", targetPort);
            if (!lanIp.isEmpty() && !lanIp.startsWith("127.")) {
                return "http://" + lanIp + ":" + (replyPort > 0 ? replyPort : targetPort);
            }
        }
        Set<String> prefixes = new LinkedHashSet<>();

        // 1. Inspect the phone's actual active Wi-Fi / LAN IPv4 interfaces first
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets != null && nets.hasMoreElements()) {
                NetworkInterface nif = nets.nextElement();
                if (nif == null || !nif.isUp() || nif.isLoopback()) continue;
                String nifName = nif.getName() != null ? nif.getName().toLowerCase(Locale.US) : "";
                if (nifName.startsWith("tun") || nifName.startsWith("ppp") || nifName.startsWith("rmnet") || nifName.startsWith("dummy")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        String prefix = extractIpv4Prefix(ip);
                        if (!prefix.isEmpty()) {
                            prefixes.add(prefix);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. If a hint URL was provided, check if that exact host is still alive first
        if (hintUrl != null && !hintUrl.trim().isEmpty()) {
            String hintPrefix = extractIpv4Prefix(hintUrl);
            if (!hintPrefix.isEmpty()) {
                prefixes.add(hintPrefix);
            }
        }

        // 3. Common home router subnets as fallback
        for (String fallback : new String[]{"192.168.1", "192.168.0", "10.0.0"}) {
            if (prefixes.size() < 3) {
                prefixes.add(fallback);
            }
        }

        List<String> candidateIps = new ArrayList<>(260);
        for (String prefix : prefixes) {
            for (int host = 2; host <= 254; host++) {
                candidateIps.add(prefix + "." + host);
            }
        }
        if (candidateIps.isEmpty()) return "";

        final AtomicReference<String> winner = new AtomicReference<>("");
        ExecutorService pool = Executors.newFixedThreadPool(48, r -> {
            Thread t = new Thread(r, "soartv-probe");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Callable<Void>> tasks = new ArrayList<>(candidateIps.size());
            for (final String ip : candidateIps) {
                tasks.add(() -> {
                    if (!winner.get().isEmpty()) return null;
                    if (probeSingleTvHost(ip, targetPort)) {
                        winner.compareAndSet("", "http://" + ip + ":" + targetPort);
                    }
                    return null;
                });
            }
            pool.invokeAll(tasks, 1800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return winner.get();
    }

    private static boolean probeSingleTvHost(String ip, int port) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ip, port), 180);
        } catch (IOException unreachable) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":" + port + "/api/auth/config");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(350);
            conn.setReadTimeout(350);
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n = in.read(buf);
                if (n <= 0) return false;
                bos.write(buf, 0, n);
                String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                return json.contains("\"paired\":") && json.contains("\"tvName\":");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractIpv4Prefix(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().replaceFirst("^https?://", "");
        int slash = cleaned.indexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(0, slash);
        int colon = cleaned.indexOf(':');
        if (colon >= 0) cleaned = cleaned.substring(0, colon);
        String[] parts = cleaned.split("\\.");
        if (parts.length == 4) {
            try {
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet < 0 || octet > 255) return "";
                }
                return parts[0] + "." + parts[1] + "." + parts[2];
            } catch (NumberFormatException ignored) {
            }
        }
        return "";
    }

    private static String extractAccessToken(Uri uri) {
        String frag = uri.getEncodedFragment();
        String fromFrag = findParamValue(frag, "access_token");
        if (!fromFrag.isEmpty()) return fromFrag;
        String query = uri.getEncodedQuery();
        return findParamValue(query, "access_token");
    }

    private static String findParamValue(String encodedPairs, String key) {
        if (encodedPairs == null || encodedPairs.isEmpty()) return "";
        for (String pair : encodedPairs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(pair.substring(0, eq));
            if (key.equals(k)) {
                return urlDecode(pair.substring(eq + 1));
            }
        }
        return "";
    }

    private static String urlDecode(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return "";
        }
    }

    static String jsonQuote(String value) {
        if (value == null) return "\"\"";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case '>': sb.append("\\u003e"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void openExternalBrowser(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return;
        }
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browserIntent);
        } catch (Exception ignored) {
        }
    }

    private String resolvePhoneNickname() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                String globalName = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
                if (isUsefulPhoneName(globalName)) {
                    return globalName.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String btName = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
            if (isUsefulPhoneName(btName)) {
                return btName.trim();
            }
        } catch (Throwable ignored) {
        }
        String model = Build.MODEL != null ? Build.MODEL.trim() : "";
        String mfr = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        if (!model.isEmpty()) {
            if (!mfr.isEmpty() && !model.toLowerCase(Locale.US).startsWith(mfr.toLowerCase(Locale.US))) {
                String capMfr = Character.toUpperCase(mfr.charAt(0)) + mfr.substring(1);
                return (capMfr + " " + model).trim();
            }
            return model;
        }
        return "";
    }

    private static boolean isUsefulPhoneName(String raw) {
        if (raw == null) return false;
        String clean = raw.trim();
        if (clean.length() < 2) return false;
        String lower = clean.toLowerCase(Locale.US);
        return !"android".equals(lower)
                && !"android phone".equals(lower)
                && !"localhost".equals(lower)
                && !"unknown".equals(lower);
    }

    /**
     * WebChromeClient granting camera access to {@code http://remote.soartv.local} after verifying
     * Android runtime {@code CAMERA} permission so in-app QR pairing works seamlessly.
     */
    private final class QrCameraWebChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (request == null) return;
            Uri origin = request.getOrigin();
            String host = (origin != null && origin.getHost() != null) ? origin.getHost().toLowerCase(Locale.US) : "";
            if (!LOCAL_HOST.equals(host)) {
                request.deny();
                return;
            }
            boolean wantsVideo = false;
            String[] resources = request.getResources();
            if (resources != null) {
                for (String res : resources) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        wantsVideo = true;
                        break;
                    }
                }
            }
            if (!wantsVideo) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingWebPermissionRequest = request;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            PermissionRequest req = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (req != null) {
                if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    req.deny();
                }
            }
        } else if (requestCode == REQ_NATIVE_QR_CAMERA) {
            boolean shouldOpen = pendingNativeQrOpen;
            pendingNativeQrOpen = false;
            if (shouldOpen) {
                if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showNativeQrScannerOverlay();
                } else {
                    evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Camera permission is needed to scan TV QR codes"));
                }
            }
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void requestOrOpenNativeQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showNativeQrScannerOverlay();
        } else {
            pendingNativeQrOpen = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_NATIVE_QR_CAMERA);
        }
    }

    /**
     * Opens a full-bleed Liquid Glass live Camera2 QR viewfinder directly inside the app, bypassing
     * Chromium WebView's HTTPS-only {@code navigator.mediaDevices} restriction on {@code http://remote.soartv.local}.
     */
    private void showNativeQrScannerOverlay() {
        if (rootContainer == null) return;
        closeNativeQrScanner(false);

        qrScannerActive = true;
        qrLockDispatched = false;
        qrConsecutiveHits = 0;
        lastQrFrameAnalyzedMs = 0L;
        applySystemBarColors(true);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#080B18"));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        TextureView tv = new TextureView(this);
        overlay.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        QrReticleOverlayView reticle = new QrReticleOverlayView(this);
        overlay.addView(reticle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Top Liquid Glass floating header pill
        LinearLayout topPill = new LinearLayout(this);
        topPill.setOrientation(LinearLayout.VERTICAL);
        topPill.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable topBg = new GradientDrawable();
        topBg.setColor(0xB8141A30);
        topBg.setCornerRadius(dp(22));
        topBg.setStroke(Math.round(dp(1)), 0x38FFFFFF);
        topPill.setBackground(topBg);
        int padH = Math.round(dp(20));
        int padV = Math.round(dp(12));
        topPill.setPadding(padH, padV, padH, padV);

        TextView title = new TextView(this);
        title.setText("Scan TV QR Code");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        topPill.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Point your camera at the QR code on your TV");
        sub.setTextColor(0xC0FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Math.round(dp(3)), 0, 0);
        topPill.addView(sub);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        topLp.topMargin = Math.round(dp(56));
        topLp.leftMargin = Math.round(dp(20));
        topLp.rightMargin = Math.round(dp(20));
        overlay.addView(topPill, topLp);

        // Bottom Liquid Glass Cancel pill button
        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        cancelBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cancelBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xCC1B223C);
        btnBg.setCornerRadius(dp(999));
        btnBg.setStroke(Math.round(dp(1)), 0x48FFFFFF);
        cancelBtn.setBackground(btnBg);
        cancelBtn.setPadding(Math.round(dp(34)), Math.round(dp(13)), Math.round(dp(34)), Math.round(dp(13)));
        cancelBtn.setOnClickListener(v -> closeNativeQrScanner(true));

        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        btnLp.bottomMargin = Math.round(dp(54));
        overlay.addView(cancelBtn, btnLp);

        nativeQrOverlay = overlay;
        qrTextureView = tv;
        rootContainer.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera2ForQr(surface, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                configureQrPreviewTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        if (tv.isAvailable()) {
            openCamera2ForQr(tv.getSurfaceTexture(), tv.getWidth(), tv.getHeight());
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera2ForQr(final SurfaceTexture surfaceTexture, final int viewWidth, final int viewHeight) {
        if (!qrScannerActive) return;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                closeNativeQrScanner(false);
                evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Camera unavailable on this device"));
                return;
            }
            String chosenId = null;
            Size bestPreview = new Size(1280, 720);
            Size bestAnalysis = new Size(640, 480);
            for (String camId : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(camId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosenId = camId;
                    bestPreview = pickCameraPreviewSize(chars);
                    bestAnalysis = pickCameraAnalysisSize(chars);
                    break;
                }
                if (chosenId == null) {
                    chosenId = camId;
                    bestPreview = pickCameraPreviewSize(chars);
                    bestAnalysis = pickCameraAnalysisSize(chars);
                }
            }
            if (chosenId == null) {
                closeNativeQrScanner(false);
                evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("No camera found on this device"));
                return;
            }
            qrPreviewSize = bestPreview;
            configureQrPreviewTransform(viewWidth, viewHeight);

            qrCameraThread = new HandlerThread("soartv-qr-camera");
            qrCameraThread.start();
            qrCameraHandler = new Handler(qrCameraThread.getLooper());

            qrImageReader = ImageReader.newInstance(
                    bestAnalysis.getWidth(), bestAnalysis.getHeight(), ImageFormat.YUV_420_888, 2);
            qrImageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null || !qrScannerActive || qrLockDispatched) return;
                    long now = System.currentTimeMillis();
                    if (now - lastQrFrameAnalyzedMs < 150L) return;
                    lastQrFrameAnalyzedMs = now;
                    analyzeCameraYPlaneForQr(img);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) {
                        try {
                            img.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, qrCameraHandler);

            manager.openCamera(chosenId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (!qrScannerActive) {
                        camera.close();
                        return;
                    }
                    qrCameraDevice = camera;
                    startQrCaptureSession(camera, surfaceTexture);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (qrCameraDevice == camera) qrCameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (qrCameraDevice == camera) qrCameraDevice = null;
                    runOnUiThread(() -> {
                        closeNativeQrScanner(false);
                        evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Could not start camera (" + error + ")"));
                    });
                }
            }, qrCameraHandler);
        } catch (Exception e) {
            closeNativeQrScanner(false);
            evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Could not open camera"));
        }
    }

    private void startQrCaptureSession(CameraDevice camera, SurfaceTexture surfaceTexture) {
        try {
            if (qrPreviewSize != null) {
                surfaceTexture.setDefaultBufferSize(qrPreviewSize.getWidth(), qrPreviewSize.getHeight());
            }
            Surface previewSurface = new Surface(surfaceTexture);
            Surface readerSurface = qrImageReader.getSurface();
            final CaptureRequest.Builder reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            reqBuilder.addTarget(previewSurface);
            reqBuilder.addTarget(readerSurface);
            reqBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            reqBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(
                    Arrays.asList(previewSurface, readerSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!qrScannerActive || qrCameraDevice == null) {
                                session.close();
                                return;
                            }
                            qrCaptureSession = session;
                            try {
                                session.setRepeatingRequest(reqBuilder.build(), null, qrCameraHandler);
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> {
                                closeNativeQrScanner(false);
                                evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Camera preview unavailable"));
                            });
                        }
                    },
                    qrCameraHandler
            );
        } catch (Exception e) {
            runOnUiThread(() -> {
                closeNativeQrScanner(false);
                evalCallbackOnJs("onSoarTvNativeQrClosed", jsonQuote("Camera setup failed"));
            });
        }
    }

    private static Size pickCameraPreviewSize(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1280, 720);
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            int score = Math.abs(s.getWidth() - 1280) + Math.abs(s.getHeight() - 720);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** Selects the smallest hardware-supported YUV_420_888 analysis size (>= 320x240) so Camera2 HAL never rejects the session. */
    private static Size pickCameraAnalysisSize(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(640, 480);
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size best = sizes[0];
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() < 240 || s.getHeight() < 180) continue;
            int score = Math.abs(s.getWidth() - 640) + Math.abs(s.getHeight() - 480);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void configureQrPreviewTransform(int viewWidth, int viewHeight) {
        if (qrTextureView == null || qrPreviewSize == null || viewWidth <= 0 || viewHeight <= 0) return;
        // Portrait phone displays camera sensor buffer rotated 90 degrees -> effective aspect is height / width
        float bufferW = qrPreviewSize.getHeight();
        float bufferH = qrPreviewSize.getWidth();
        float viewRatio = (float) viewWidth / (float) viewHeight;
        float bufferRatio = bufferW / bufferH;
        float scaleX = 1f;
        float scaleY = 1f;
        if (bufferRatio > viewRatio) {
            scaleX = bufferRatio / viewRatio;
        } else {
            scaleY = viewRatio / bufferRatio;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        qrTextureView.setTransform(matrix);
    }

    private void analyzeCameraYPlaneForQr(Image img) {
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length == 0) return;
        ByteBuffer yBuf = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int srcW = img.getWidth();
        int srcH = img.getHeight();
        if (srcW < 64 || srcH < 64) return;

        final int dstW = 160;
        final int dstH = 120;
        for (int y = 0; y < dstH; y++) {
            int sy = (y * srcH) / dstH;
            int rowOff = sy * rowStride;
            int dstRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                int sx = (x * srcW) / dstW;
                int idx = rowOff + sx * pixelStride;
                qrLumaBuf[dstRow + x] = idx < yBuf.limit() ? yBuf.get(idx) : (byte) 240;
            }
        }

        if (hasQrFinderPatternInLuma(qrLumaBuf, dstW, dstH)) {
            qrConsecutiveHits++;
            if (qrConsecutiveHits >= 2 && !qrLockDispatched) {
                qrLockDispatched = true;
                runOnUiThread(() -> {
                    vibrateGentleConfirm();
                    closeNativeQrScanner(false);
                    evalCallbackOnJs("onSoarTvNativeQrDetected", "");
                });
            }
        } else {
            qrConsecutiveHits = 0;
        }
    }

    private void vibrateGentleConfirm() {
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                //noinspection deprecation
                vib.vibrate(28);
            }
        } catch (Exception ignored) {
        }
    }

    private void closeNativeQrScanner(boolean notifyCancelled) {
        qrScannerActive = false;
        if (qrCaptureSession != null) {
            try {
                qrCaptureSession.close();
            } catch (Exception ignored) {
            }
            qrCaptureSession = null;
        }
        if (qrCameraDevice != null) {
            try {
                qrCameraDevice.close();
            } catch (Exception ignored) {
            }
            qrCameraDevice = null;
        }
        if (qrImageReader != null) {
            try {
                qrImageReader.close();
            } catch (Exception ignored) {
            }
            qrImageReader = null;
        }
        if (qrCameraThread != null) {
            try {
                qrCameraThread.quitSafely();
            } catch (Exception ignored) {
            }
            qrCameraThread = null;
            qrCameraHandler = null;
        }
        if (nativeQrOverlay != null && rootContainer != null) {
            rootContainer.removeView(nativeQrOverlay);
        }
        nativeQrOverlay = null;
        qrTextureView = null;
        applySystemBarColors(currentDarkTheme);
        if (notifyCancelled) {
            evalCallbackOnJs("onSoarTvNativeQrClosed", "''");
        }
    }

    /**
     * Detects ISO/IEC 18004 {@code 1:1:3:1:1} QR Finder Patterns in an 8-bit grayscale luminance buffer
     * with horizontal + vertical cross-verification (zero third-party barcode dependencies).
     * Tests a center-reticle contrast midpoint first (ideal for glowing TV QR codes in dim rooms)
     * and falls back to the full-frame mean threshold.
     */
    static boolean hasQrFinderPatternInLuma(byte[] luma, int width, int height) {
        if (luma == null || width < 32 || height < 32 || luma.length < width * height) {
            return false;
        }
        int cx0 = width / 5;
        int cx1 = width - cx0;
        int cy0 = height / 5;
        int cy1 = height - cy0;
        int centerMin = 255;
        int centerMax = 0;
        for (int y = cy0; y < cy1; y += 2) {
            int row = y * width;
            for (int x = cx0; x < cx1; x += 2) {
                int v = luma[row + x] & 0xFF;
                if (v < centerMin) centerMin = v;
                if (v > centerMax) centerMax = v;
            }
        }
        int centerMid = (centerMax - centerMin >= 40) ? ((centerMin + centerMax) / 2) : -1;
        if (centerMid >= 0 && scanLumaForQrFinderAtThreshold(luma, width, height, centerMid)) {
            return true;
        }

        long sum = 0;
        int stepSample = Math.max(1, (width * height) / 1024);
        int countSample = 0;
        for (int i = 0; i < width * height; i += stepSample) {
            sum += (luma[i] & 0xFF);
            countSample++;
        }
        int globalMean = countSample > 0 ? (int) (sum / countSample) : 128;
        if (Math.abs(globalMean - centerMid) >= 12) {
            return scanLumaForQrFinderAtThreshold(luma, width, height, globalMean);
        }
        return false;
    }

    private static boolean scanLumaForQrFinderAtThreshold(byte[] luma, int width, int height, int threshold) {
        int[] foundX = new int[4];
        int[] foundY = new int[4];
        int foundCount = 0;
        int rowStep = Math.max(1, height / 64);

        for (int y = 2; y < height - 2; y += rowStep) {
            int[] counts = new int[5];
            int state = 0;
            for (int x = 0; x < width; x++) {
                boolean isDark = (luma[y * width + x] & 0xFF) < threshold;
                if (isDark) {
                    if (state == 1 || state == 3) state++;
                    counts[state]++;
                } else {
                    if (state == 0) {
                        if (counts[0] > 0) {
                            state = 1;
                            counts[1] = 1;
                        }
                    } else if (state == 2) {
                        state = 3;
                        counts[3] = 1;
                    } else if (state == 4) {
                        if (matches11311(counts)) {
                            int total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
                            int cx = Math.max(0, Math.min(width - 1, x - counts[4] - counts[3] - counts[2] / 2));
                            if (verifyVerticalFinder(luma, width, height, threshold, cx, y, (total + 1) / 2)) {
                                boolean dup = false;
                                double minDist = Math.max(10.0, total * 0.6);
                                for (int k = 0; k < foundCount; k++) {
                                    if (Math.hypot(foundX[k] - cx, foundY[k] - y) < minDist) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (!dup) {
                                    foundX[foundCount] = cx;
                                    foundY[foundCount] = y;
                                    foundCount++;
                                    if (foundCount >= 2) return true;
                                }
                            }
                        }
                        counts[0] = counts[2];
                        counts[1] = counts[3];
                        counts[2] = counts[4];
                        counts[3] = 1;
                        counts[4] = 0;
                        state = 3;
                    } else {
                        counts[state]++;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matches11311(int[] c) {
        int total = c[0] + c[1] + c[2] + c[3] + c[4];
        if (total < 7) return false;
        double mod = total / 7.0;
        double maxVar = mod * 0.65;
        return Math.abs(mod - c[0]) <= maxVar
                && Math.abs(mod - c[1]) <= maxVar
                && Math.abs(3.0 * mod - c[2]) <= 3.0 * maxVar
                && Math.abs(mod - c[3]) <= maxVar
                && Math.abs(mod - c[4]) <= maxVar;
    }

    private static boolean verifyVerticalFinder(
            byte[] luma, int width, int height, int threshold, int cx, int startY, int maxMod) {
        int[] c = new int[5];
        int y = startY;
        while (y >= 0 && (luma[y * width + cx] & 0xFF) < threshold) {
            c[2]++;
            y--;
        }
        if (y < 0) return false;
        while (y >= 0 && (luma[y * width + cx] & 0xFF) >= threshold && c[1] <= maxMod) {
            c[1]++;
            y--;
        }
        if (y < 0 || c[1] > maxMod) return false;
        while (y >= 0 && (luma[y * width + cx] & 0xFF) < threshold && c[0] <= maxMod) {
            c[0]++;
            y--;
        }
        y = startY + 1;
        while (y < height && (luma[y * width + cx] & 0xFF) < threshold) {
            c[2]++;
            y++;
        }
        if (y >= height) return false;
        while (y < height && (luma[y * width + cx] & 0xFF) >= threshold && c[3] <= maxMod) {
            c[3]++;
            y++;
        }
        if (y >= height || c[3] > maxMod) return false;
        while (y < height && (luma[y * width + cx] & 0xFF) < threshold && c[4] <= maxMod) {
            c[4]++;
            y++;
        }
        return matches11311(c);
    }

    /** Lightweight Liquid Glass QR scanner reticle overlay (zero allocations in {@code onDraw}). */
    private final class QrReticleOverlayView extends View {
        private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF holeRect = new RectF();

        QrReticleOverlayView(Context ctx) {
            super(ctx);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            scrimPaint.setColor(0x9E080B18);
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1.2f));
            borderPaint.setColor(0x52FFFFFF);
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(dp(3.6f));
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);
            cornerPaint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float box = Math.min(w * 0.68f, dp(256));
            float left = (w - box) / 2f;
            float top = (h - box) / 2f;
            float r = dp(26);
            holeRect.set(left, top, left + box, top + box);

            canvas.drawRect(0, 0, w, h, scrimPaint);
            canvas.drawRoundRect(holeRect, r, r, clearPaint);
            canvas.drawRoundRect(holeRect, r, r, borderPaint);

            float arm = dp(26);
            // Four crisp white corner accents matching Liquid Glass framing
            canvas.drawLine(left + r * 0.45f, top, left + r * 0.45f + arm, top, cornerPaint);
            canvas.drawLine(left, top + r * 0.45f, left, top + r * 0.45f + arm, cornerPaint);

            canvas.drawLine(left + box - r * 0.45f - arm, top, left + box - r * 0.45f, top, cornerPaint);
            canvas.drawLine(left + box, top + r * 0.45f, left + box, top + r * 0.45f + arm, cornerPaint);

            canvas.drawLine(left + r * 0.45f, top + box, left + r * 0.45f + arm, top + box, cornerPaint);
            canvas.drawLine(left, top + box - r * 0.45f - arm, left, top + box - r * 0.45f, cornerPaint);

            canvas.drawLine(left + box - r * 0.45f - arm, top + box, left + box - r * 0.45f, top + box, cornerPaint);
            canvas.drawLine(left + box, top + box - r * 0.45f - arm, left + box, top + box - r * 0.45f, cornerPaint);
        }
    }

    /**
     * WebViewClient enforcing strict asset whitelisting on {@code http://remote.soartv.local} and
     * preventing top-level navigation to untrusted origins.
     */
    private final class HardenedRemoteWebViewClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return null;
            }
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
            if (!"http".equals(scheme) || !LOCAL_HOST.equals(host)) {
                // Allow LAN fetch() requests (e.g. http://<TV_IP>:8484/api/...) to go to the network
                return null;
            }

            String rawPath = uri.getPath() != null ? uri.getPath() : "/";
            String assetName;
            String mimeType;
            switch (rawPath) {
                case "":
                case "/":
                case "/index.html":
                    assetName = "index.html";
                    mimeType = "text/html";
                    break;
                case "/app.bundle.js":
                    assetName = "app.bundle.js";
                    mimeType = "application/javascript";
                    break;
                case "/manifest.json":
                    assetName = "manifest.json";
                    mimeType = "application/manifest+json";
                    break;
                default:
                    return buildNotFoundResponse();
            }

            try {
                InputStream stream = getAssets().open(assetName);
                WebResourceResponse resp = new WebResourceResponse(mimeType, "UTF-8", stream);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("X-Content-Type-Options", "nosniff");
                    resp.setResponseHeaders(headers);
                }
                return resp;
            } catch (IOException e) {
                return buildNotFoundResponse();
            }
        }

        private WebResourceResponse buildNotFoundResponse() {
            InputStream empty = new ByteArrayInputStream("Not Found".getBytes(StandardCharsets.UTF_8));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        404,
                        "Not Found",
                        Collections.singletonMap("Cache-Control", "no-store"),
                        empty
                );
            }
            return new WebResourceResponse("text/plain", "UTF-8", empty);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return request != null && handleNavigationUri(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return url != null && handleNavigationUri(Uri.parse(url));
        }

        private boolean handleNavigationUri(Uri uri) {
            if (uri == null) return true;
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.US) : "";
            if ("http".equals(scheme) && LOCAL_HOST.equals(host)) {
                trustedOriginActive = true;
                return false;
            }
            if ("soartvremote".equals(scheme)) {
                if ("pair".equals(host)) {
                    deliverQrPairUriToJs(uri.toString());
                    return true;
                }
                String token = extractAccessToken(uri);
                if (!token.isEmpty()) {
                    deliverGoogleTokenToJs(token);
                }
                return true;
            }
            // Open Google OAuth or APK download links in the system external browser
            openExternalBrowser(uri);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Uri parsed = url != null ? Uri.parse(url) : null;
            trustedOriginActive = parsed != null
                    && "http".equalsIgnoreCase(parsed.getScheme())
                    && LOCAL_HOST.equalsIgnoreCase(parsed.getHost());
            pageLoaded = trustedOriginActive;
            if (!trustedOriginActive) return;

            deliverNativeSafeAreaInsetsToJs();

            String discovered = lastDiscoveredTvUrl;
            if (discovered != null && !discovered.isEmpty()) {
                deliverDiscoveredTvToJs(discovered);
            } else {
                triggerBackgroundTvDiscovery("");
            }

            String pendingQr = pendingQrPairUri;
            if (pendingQr != null && !pendingQr.isEmpty()) {
                pendingQrPairUri = null;
                deliverQrPairUriToJs(pendingQr);
            }

            String pending = pendingGoogleAccessToken;
            if (pending != null && !pending.isEmpty()) {
                pendingGoogleAccessToken = null;
                deliverGoogleTokenToJs(pending);
            }
        }
    }

    /**
     * Hardware-backed biometric & native Wi-Fi discovery JavascriptInterface exposed strictly to {@code http://remote.soartv.local}.
     */
    public final class SoarTvNativeBridge {

        @JavascriptInterface
        public String discoverTv(String hintUrl) {
            if (!trustedOriginActive) return "";
            String found = discoverTvOnLan(hintUrl, DEFAULT_TV_PORT);
            if (found != null && !found.isEmpty()) {
                lastDiscoveredTvUrl = found;
                return found;
            }
            return lastDiscoveredTvUrl != null ? lastDiscoveredTvUrl : "";
        }

        @JavascriptInterface
        public String discoverAllTvsOnLan() {
            if (!trustedOriginActive) return "[]";
            List<String> replies = discoverTvsViaUdp(DEFAULT_TV_PORT, 320);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < replies.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(replies.get(i));
            }
            sb.append(']');
            return sb.toString();
        }

        @JavascriptInterface
        public void startNativeTvDiscovery(String hintUrl) {
            if (!trustedOriginActive) return;
            triggerBackgroundTvDiscovery(hintUrl);
        }

        @JavascriptInterface
        public void setSystemBarTheme(final boolean dark) {
            if (!trustedOriginActive) return;
            runOnUiThread(() -> applySystemBarAppearance(dark));
        }

        @JavascriptInterface
        public void openNativeQrScanner() {
            if (!trustedOriginActive) return;
            runOnUiThread(RemoteAppActivity.this::requestOrOpenNativeQrScanner);
        }

        @JavascriptInterface
        public void closeNativeQrScanner() {
            if (!trustedOriginActive) return;
            runOnUiThread(() -> RemoteAppActivity.this.closeNativeQrScanner(false));
        }

        @JavascriptInterface
        public void openExternalUrl(final String url) {
            if (!trustedOriginActive || url == null) return;
            final String trimmed = url.trim();
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return;
            runOnUiThread(() -> openExternalBrowser(Uri.parse(trimmed)));
        }

        /**
         * Returns the user-configured phone / Bluetooth device name (Settings.Global.DEVICE_NAME ->
         * bluetooth_name -> Build.MODEL) with zero extra runtime permissions so paired phones appear
         * under friendly nicknames like "Alex's S25 Ultra" on the TV.
         */
        @JavascriptInterface
        public String getDeviceName() {
            if (!trustedOriginActive) return "";
            return resolvePhoneNickname();
        }

        @JavascriptInterface
        public boolean isBiometricAvailable() {
            if (!trustedOriginActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return false;
            }
            PackageManager pm = getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        }

        @JavascriptInterface
        public boolean hasSavedPcUnlockPin() {
            if (!trustedOriginActive) return false;
            SharedPreferences sp = getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE);
            String ct = sp.getString(PREF_ENC_PIN, "");
            String iv = sp.getString(PREF_ENC_IV, "");
            return ct != null && !ct.isEmpty() && iv != null && !iv.isEmpty();
        }

        @JavascriptInterface
        public void clearSavedPcUnlockPin() {
            if (!trustedOriginActive) return;
            clearBiometricVaultInternal();
        }

        @JavascriptInterface
        public void enrollPcUnlockPin(final String pin) {
            if (!trustedOriginActive) return;
            if (pin == null) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'Enter a valid Windows PIN'");
                return;
            }
            final String trimmed = pin.trim();
            if (trimmed.isEmpty() || trimmed.length() > 64 || !isPrintableAscii(trimmed)) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'PIN must be 1-64 printable characters'");
                return;
            }
            if (!isBiometricAvailable()) {
                evalCallbackOnJs("onBiometricEnrollResult", "false, 'Fingerprint hardware unavailable'");
                return;
            }
            runOnUiThread(() -> Api28Biometrics.enrollPin(RemoteAppActivity.this, trimmed));
        }

        @JavascriptInterface
        public void authenticatePcUnlock() {
            if (!trustedOriginActive) return;
            if (!isBiometricAvailable() || !hasSavedPcUnlockPin()) {
                evalCallbackOnJs("onBiometricPcPinError", "'No saved fingerprint PC PIN'");
                return;
            }
            runOnUiThread(() -> Api28Biometrics.authenticatePin(RemoteAppActivity.this));
        }
    }

    private void applySystemBarAppearance(boolean dark) {
        currentDarkTheme = dark;
        if (nativeQrOverlay != null) {
            return;
        }
        applySystemBarColors(dark);
    }

    private void deliverNativeSafeAreaInsetsToJs() {
        runOnUiThread(() -> {
            if (webView == null || !trustedOriginActive || !pageLoaded) return;
            String js = "(function(){var s=document.documentElement.style;"
                    + "s.setProperty('--native-safe-top','" + cachedSafeTopDp + "px');"
                    + "s.setProperty('--native-safe-bottom','" + cachedSafeBottomDp + "px');"
                    + "})();";
            webView.evaluateJavascript(js, null);
        });
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarColors(boolean dark) {
        Window window = getWindow();
        if (window == null) return;
        // Ensure DecorView is installed before touching WindowInsetsController (prevents Android 11-16 NPE)
        View decor = window.getDecorView();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        // True seamless edge-to-edge: let the WebView's Twilight Aurora / Pearl Glass flow behind
        // both the top status bar (including camera cutout) and bottom navigation/gesture bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        int fallbackBg = dark ? DARK_STATUS_BAR_COLOR : LIGHT_STATUS_BAR_COLOR;
        if (webView != null) {
            webView.setBackgroundColor(fallbackBg);
        }
        if (rootContainer != null) {
            rootContainer.setBackgroundColor(fallbackBg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    int lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ctrl.setSystemBarsAppearance(dark ? 0 : lightMask, lightMask);
                } else if (decor != null) {
                    decor.post(() -> {
                        try {
                            WindowInsetsController lateCtrl = window.getInsetsController();
                            if (lateCtrl != null) {
                                int lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                                lateCtrl.setSystemBarsAppearance(dark ? 0 : lightMask, lightMask);
                            }
                        } catch (RuntimeException ignored) {
                        }
                    });
                }
            } catch (RuntimeException ignored) {
            }
        } else if (decor != null) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /**
     * Bridges true edge-to-edge status/nav bar safe-area insets into CSS ({@code --native-safe-top},
     * {@code --native-safe-bottom}) while raising the WebView cleanly above the software keyboard on
     * Android 11–16 without any solid-color bar seam or double padding.
     */
    private void installImeInsetsResizeListener() {
        if (rootContainer == null) return;
        final float density = Math.max(1f, getResources().getDisplayMetrics().density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sys = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                int topDp = Math.max(0, Math.round(sys.top / density));
                int bottomDp = imeBottom > 0 ? 0 : Math.max(0, Math.round(sys.bottom / density));
                if (topDp != cachedSafeTopDp || bottomDp != cachedSafeBottomDp) {
                    cachedSafeTopDp = topDp;
                    cachedSafeBottomDp = bottomDp;
                    deliverNativeSafeAreaInsetsToJs();
                }

                int kbPad = (imeBottom > 0 && nativeQrOverlay == null) ? imeBottom : 0;
                if (v.getPaddingTop() != 0 || v.getPaddingBottom() != kbPad) {
                    v.setPadding(0, 0, 0, kbPad);
                }
                return WindowInsets.CONSUMED;
            });
            rootContainer.requestApplyInsets();
        } else {
            rootContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                @SuppressWarnings("deprecation")
                int topPx = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation")
                int botPx = insets.getSystemWindowInsetBottom();
                cachedSafeTopDp = Math.max(0, Math.round(topPx / density));
                cachedSafeBottomDp = Math.max(0, Math.round(botPx / density));
                deliverNativeSafeAreaInsetsToJs();
                return insets;
            });
        }
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    void clearBiometricVaultInternal() {
        getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_ENC_PIN)
                .remove(PREF_ENC_IV)
                .apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Isolated API 28+ helper for {@link BiometricPrompt} + {@code AndroidKeyStore} AES-256-GCM
     * per-use biometric authentication so {@code d8 --min-api 24} verifies cleanly on older phones.
     */
    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28Biometrics {

        private Api28Biometrics() {}

        static void enrollPin(final RemoteAppActivity activity, final String rawPin) {
            try {
                KeyGenerator keyGen = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(true)
                        .build();
                keyGen.init(spec);
                SecretKey secretKey = keyGen.generateKey();

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);

                cancelPreviousSignal(activity);
                CancellationSignal signal = new CancellationSignal();
                activity.activeBiometricSignal = signal;

                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("Confirm Fingerprint to Save PC Unlock")
                        .setDescription("Encrypts your Windows unlock PIN in Android hardware KeyStore")
                        .setNegativeButton("Cancel", activity.getMainExecutor(), (dialog, which) -> {
                            signal.cancel();
                            activity.evalCallbackOnJs("onBiometricEnrollResult", "false, 'Cancelled'");
                        })
                        .build();

                prompt.authenticate(
                        new BiometricPrompt.CryptoObject(cipher),
                        signal,
                        activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                byte[] plain = rawPin.getBytes(StandardCharsets.UTF_8);
                                try {
                                    Cipher unlockedCipher = result.getCryptoObject().getCipher();
                                    byte[] ciphertext = unlockedCipher.doFinal(plain);
                                    byte[] iv = unlockedCipher.getIV();
                                    activity.getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE)
                                            .edit()
                                            .putString(PREF_ENC_PIN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                                            .putString(PREF_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                                            .apply();
                                    activity.evalCallbackOnJs("onBiometricEnrollResult", "true, ''");
                                } catch (Exception e) {
                                    activity.evalCallbackOnJs("onBiometricEnrollResult",
                                            "false, " + jsonQuote("Encryption failed"));
                                } finally {
                                    Arrays.fill(plain, (byte) 0);
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                String msg = errString != null ? errString.toString() : "Authentication cancelled";
                                activity.evalCallbackOnJs("onBiometricEnrollResult", "false, " + jsonQuote(msg));
                            }
                        }
                );
            } catch (Exception e) {
                activity.evalCallbackOnJs("onBiometricEnrollResult",
                        "false, " + jsonQuote("Could not initialize fingerprint key"));
            }
        }

        static void authenticatePin(final RemoteAppActivity activity) {
            try {
                SharedPreferences sp = activity.getSharedPreferences(BIOMETRIC_PREFS, Context.MODE_PRIVATE);
                final byte[] ciphertext = Base64.decode(sp.getString(PREF_ENC_PIN, ""), Base64.NO_WRAP);
                final byte[] iv = Base64.decode(sp.getString(PREF_ENC_IV, ""), Base64.NO_WRAP);

                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                SecretKey secretKey = (SecretKey) ks.getKey(KEYSTORE_ALIAS, null);
                if (secretKey == null) {
                    activity.clearBiometricVaultInternal();
                    activity.evalCallbackOnJs("onBiometricPcPinError", "'Saved fingerprint key missing; re-save PIN'");
                    return;
                }

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));

                cancelPreviousSignal(activity);
                CancellationSignal signal = new CancellationSignal();
                activity.activeBiometricSignal = signal;

                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("Touch Fingerprint Sensor to Unlock PC")
                        .setDescription("Unlocks your Windows Gaming PC wirelessly over Wi-Fi")
                        .setNegativeButton("Use PIN", activity.getMainExecutor(), (dialog, which) -> {
                            signal.cancel();
                            activity.evalCallbackOnJs("onBiometricPcPinError", "'Cancelled'");
                        })
                        .build();

                prompt.authenticate(
                        new BiometricPrompt.CryptoObject(cipher),
                        signal,
                        activity.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                byte[] plain = null;
                                try {
                                    Cipher unlockedCipher = result.getCryptoObject().getCipher();
                                    plain = unlockedCipher.doFinal(ciphertext);
                                    String pin = new String(plain, StandardCharsets.UTF_8);
                                    activity.evalCallbackOnJs("onBiometricPcPinReady", jsonQuote(pin));
                                } catch (Exception e) {
                                    activity.evalCallbackOnJs("onBiometricPcPinError", "'Failed to decrypt PIN'");
                                } finally {
                                    if (plain != null) {
                                        Arrays.fill(plain, (byte) 0);
                                    }
                                }
                            }

                            @Override
                            public void onAuthenticationError(int errorCode, CharSequence errString) {
                                String msg = errString != null ? errString.toString() : "Fingerprint cancelled";
                                activity.evalCallbackOnJs("onBiometricPcPinError", jsonQuote(msg));
                            }
                        }
                );
            } catch (KeyPermanentlyInvalidatedException invalidated) {
                activity.clearBiometricVaultInternal();
                activity.evalCallbackOnJs("onBiometricPcPinError",
                        "'Fingerprints changed on phone; please save PC PIN again'");
            } catch (Exception e) {
                activity.evalCallbackOnJs("onBiometricPcPinError", "'Biometric unlock unavailable'");
            }
        }

        private static void cancelPreviousSignal(RemoteAppActivity activity) {
            if (activity.activeBiometricSignal != null) {
                try {
                    activity.activeBiometricSignal.cancel();
                } catch (Exception ignored) {
                }
                activity.activeBiometricSignal = null;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && nativeQrOverlay != null) {
            closeNativeQrScanner(true);
            return true;
        }
        if (webView != null && trustedOriginActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                webView.evaluateJavascript("if (window.sendKey) window.sendKey('vol_up');", null);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                webView.evaluateJavascript("if (window.sendKey) window.sendKey('vol_down');", null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        closeNativeQrScanner(false);
        if (activeBiometricSignal != null) {
            try {
                activeBiometricSignal.cancel();
            } catch (Exception ignored) {
            }
            activeBiometricSignal = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
