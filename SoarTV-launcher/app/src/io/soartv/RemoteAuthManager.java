package io.soartv;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Authentication, Pairing, Role-Based Access Control (RBAC), and Google Admin OAuth Recovery
 * manager for the SoarTV Companion Remote ({@link RemoteControlServer}).
 *
 * <p><b>Why:</b> Prevents unauthorized LAN/web callers from controlling the TV, unlocking the Gaming PC,
 * or changing update settings ({@code SEC-05}), while keeping pairing effortless via QR code, 6-digit code,
 * 1-click TV remote approval, 30-day automatic Guest expiration, and anti-lockout "Sign in with Google"
 * owner recovery that works 100% offline over LAN once paired.
 *
 * <p><b>What:</b> Persists only {@code SHA-256(bearerToken)} device records inside hardware-encrypted
 * {@link Secrets#REMOTE_PAIRED_DEVICES}, enforces per-IP rate limits on pairing requests and verification,
 * validates role permissions ({@code GUEST}, {@code HOUSEHOLD}, {@code ADMIN}), and verifies Supabase
 * Google OAuth tokens strictly against the owner's {@code admin_emails} whitelist.
 */
public final class RemoteAuthManager {

    /** Access roles for paired Companion Remote devices. */
    public enum Role {
        /** Temporary visitor role: expires automatically 30 days after pairing. */
        GUEST,
        /** Permanent family/household role: TV, media, smart-home & gaming launch/wake (no PC unlock or admin kick). */
        HOUSEHOLD,
        /** Permanent owner/admin role: full control including Wireless PC Unlock, OTA updates, and device management. */
        ADMIN;

        public static Role fromString(String raw, Role fallback) {
            if (raw == null) return fallback;
            switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "ADMIN":
                    return ADMIN;
                case "HOUSEHOLD":
                    return HOUSEHOLD;
                case "GUEST":
                    return GUEST;
                default:
                    return fallback;
            }
        }
    }

    /** Authentication outcome for an incoming HTTP request's Bearer token. */
    public enum AuthStatus {
        AUTHENTICATED,
        MISSING_TOKEN,
        INVALID_TOKEN,
        GUEST_EXPIRED
    }

    /** Pending pairing lifecycle state. */
    public enum PendingStatus {
        PENDING,
        APPROVED,
        DISMISSED,
        EXPIRED
    }

    public static final long GUEST_TTL_MS = 30L * 86_400_000L;
    public static final long PENDING_PAIR_TTL_MS = 5L * 60_000L;
    public static final long APK_HANDOFF_TTL_MS = 15L * 60_000L;
    /** ISO-18004 standard 4-module quiet zone so iPhone &amp; Android cameras lock onto TV QR codes reliably. */
    public static final int PAIRING_QR_QUIET_ZONE = 4;
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int MAX_PAIR_REQUESTS_PER_MIN = 5;
    private static final int MAX_VERIFY_FAILURES_PER_MIN = 8;

    /** Immutable snapshot of a paired Companion Remote device (stores only SHA-256 hash of bearer token). */
    public static final class PairedDevice {
        public final String deviceId;
        public final String deviceName;
        public final String tokenSha256;
        public final Role role;
        public final String googleEmail;
        public final long createdAtMs;
        public final long expiresAtMs;
        public final long lastSeenMs;

        public PairedDevice(
                String deviceId,
                String deviceName,
                String tokenSha256,
                Role role,
                String googleEmail,
                long createdAtMs,
                long expiresAtMs,
                long lastSeenMs) {
            this.deviceId = deviceId != null ? deviceId : "";
            this.deviceName = sanitizeDeviceName(deviceName);
            this.tokenSha256 = tokenSha256 != null ? tokenSha256 : "";
            this.role = role != null ? role : Role.GUEST;
            this.googleEmail = googleEmail != null ? googleEmail : "";
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
            this.lastSeenMs = lastSeenMs;
        }

        public boolean isExpired(long nowMs) {
            return role == Role.GUEST && expiresAtMs > 0L && nowMs >= expiresAtMs;
        }

        PairedDevice withLastSeen(long seenMs) {
            return new PairedDevice(deviceId, deviceName, tokenSha256, role, googleEmail, createdAtMs, expiresAtMs, seenMs);
        }

        PairedDevice withRole(Role nextRole, long nowMs) {
            long nextExpires = nextRole == Role.GUEST ? (nowMs + GUEST_TTL_MS) : 0L;
            return new PairedDevice(deviceId, deviceName, tokenSha256, nextRole, googleEmail, createdAtMs, nextExpires, lastSeenMs);
        }

        PairedDevice withDeviceName(String nextName) {
            return new PairedDevice(deviceId, nextName, tokenSha256, role, googleEmail, createdAtMs, expiresAtMs, lastSeenMs);
        }

        Map<String, Object> toStorageMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", deviceId);
            m.put("deviceName", deviceName);
            m.put("tokenSha256", tokenSha256);
            m.put("role", role.name());
            m.put("googleEmail", googleEmail);
            m.put("createdAtMs", createdAtMs);
            m.put("expiresAtMs", expiresAtMs);
            m.put("lastSeenMs", lastSeenMs);
            return m;
        }

        public Map<String, Object> toPublicMap(String currentTokenSha256, long nowMs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", deviceId);
            m.put("deviceName", deviceName);
            m.put("role", role.name());
            m.put("googleEmail", googleEmail);
            m.put("createdAtMs", createdAtMs);
            m.put("expiresAtMs", expiresAtMs);
            m.put("lastSeenMs", lastSeenMs);
            m.put("expired", isExpired(nowMs));
            if (role == Role.GUEST && expiresAtMs > nowMs) {
                long daysLeft = Math.max(1L, (expiresAtMs - nowMs + 86_399_999L) / 86_400_000L);
                m.put("daysRemaining", daysLeft);
            } else {
                m.put("daysRemaining", 0L);
            }
            m.put("isCurrentDevice", currentTokenSha256 != null && !currentTokenSha256.isEmpty()
                    && constantTimeEquals(tokenSha256, currentTokenSha256));
            return m;
        }
    }

    /** Active 5-minute pairing challenge supporting QR scan, 6-digit code entry, and 1-click TV remote approval. */
    public static final class PendingPairRequest {
        public final String requestId;
        public final String deviceName;
        public final String clientId;
        public final String clientIp;
        public final String sixDigitCode;
        public final String qrSecret;
        public final String pairUrl;
        public final Role presetRole;
        public final boolean bootstrapAdmin;
        public final long createdAtMs;
        public final long expiresAtMs;
        private PendingStatus status = PendingStatus.PENDING;
        private String transientIssuedToken = "";
        private PairedDevice approvedDevice;

        PendingPairRequest(
                String requestId,
                String deviceName,
                String clientId,
                String clientIp,
                String sixDigitCode,
                String qrSecret,
                String pairUrl,
                Role presetRole,
                boolean bootstrapAdmin,
                long createdAtMs,
                long expiresAtMs) {
            this.requestId = requestId;
            this.deviceName = sanitizeDeviceName(deviceName);
            this.clientId = clientId != null ? clientId : "";
            this.clientIp = clientIp != null ? clientIp : "";
            this.sixDigitCode = sixDigitCode;
            this.qrSecret = qrSecret;
            this.pairUrl = pairUrl;
            this.presetRole = presetRole;
            this.bootstrapAdmin = bootstrapAdmin;
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
        }

        public synchronized PendingStatus getStatus(long nowMs) {
            if (status == PendingStatus.PENDING && nowMs >= expiresAtMs) {
                status = PendingStatus.EXPIRED;
            }
            return status;
        }
    }

    /** Result of verifying a Bearer token on an incoming API request. */
    public static final class AuthResult {
        public final AuthStatus status;
        public final PairedDevice device;

        AuthResult(AuthStatus status, PairedDevice device) {
            this.status = status;
            this.device = device;
        }

        public boolean isAuthenticated() {
            return status == AuthStatus.AUTHENTICATED && device != null;
        }
    }

    /** Result of pairing verification, TV poll, or Google Admin OAuth exchange. */
    public static final class IssueResult {
        public final int httpStatus;
        public final String jsonBody;
        public final PairedDevice device;

        IssueResult(int httpStatus, String jsonBody, PairedDevice device) {
            this.httpStatus = httpStatus;
            this.jsonBody = jsonBody;
            this.device = device;
        }
    }

    /** Pluggable listener notified when a pending pairing prompt opens, updates, or closes on the TV. */
    public interface PromptListener {
        void onPairingPromptChanged();
    }

    private static volatile RemoteAuthManager instance;

    private final Context appContext;
    private final LongSupplier clock;
    private final SecureRandom rng = new SecureRandom();
    private static final class ApkHandoff {
        final String token;
        final Role role;
        final long timestampMs;

        ApkHandoff(String token, Role role, long timestampMs) {
            this.token = token;
            this.role = role;
            this.timestampMs = timestampMs;
        }
    }

    private final Map<String, PairedDevice> devicesById = new LinkedHashMap<>();
    private final Map<String, List<Long>> requestHitsByIp = new LinkedHashMap<>();
    private final Map<String, List<Long>> failedVerifyHitsByIp = new LinkedHashMap<>();
    private final Map<String, ApkHandoff> apkHandoffByIp = new LinkedHashMap<>();

    /**
     * Records a short-lived (15-minute) same-phone LAN IP handoff so when a user pairs in mobile Chrome
     * and immediately downloads & opens {@code SoarTV-Remote.apk} on that same phone IP, the native APK
     * inherits its paired token automatically without requiring a second pairing step.
     */
    public synchronized void recordSameIpApkHandoff(String clientIp, String rawToken, Role role) {
        if (clientIp == null || rawToken == null) return;
        String ip = clientIp.trim();
        String tok = rawToken.trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip) || "admin".equalsIgnoreCase(ip) || tok.isEmpty()) {
            return;
        }
        apkHandoffByIp.put(ip, new ApkHandoff(tok, role != null ? role : Role.GUEST, clock.getAsLong()));
    }
    private PendingPairRequest activePending;
    private volatile PromptListener promptListener;
    private volatile String supabaseUrlOverrideForTest;
    private volatile String supabaseAnonKeyOverrideForTest;
    private volatile String adminEmailsOverrideForTest;

    RemoteAuthManager(Context context, LongSupplier clock) {
        Context app = null;
        if (context != null) {
            try {
                app = context.getApplicationContext();
            } catch (RuntimeException ignored) {
            }
            if (app == null) app = context;
        }
        this.appContext = app;
        this.clock = clock != null ? clock : System::currentTimeMillis;
        loadFromSecrets();
    }

    /** Returns the singleton {@link RemoteAuthManager}. */
    public static RemoteAuthManager get(Context context) {
        RemoteAuthManager m = instance;
        if (m == null) {
            synchronized (RemoteAuthManager.class) {
                m = instance;
                if (m == null) {
                    m = new RemoteAuthManager(context, System::currentTimeMillis);
                    instance = m;
                }
            }
        }
        return m;
    }

    public void setPromptListener(PromptListener listener) {
        this.promptListener = listener;
    }

    void setTestAuthOverrides(String supabaseUrl, String anonKey, String adminEmails) {
        this.supabaseUrlOverrideForTest = supabaseUrl;
        this.supabaseAnonKeyOverrideForTest = anonKey;
        this.adminEmailsOverrideForTest = adminEmails;
    }

    private void notifyPromptChanged() {
        PromptListener l = promptListener;
        if (l != null) {
            try {
                l.onPairingPromptChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Renders a crisp QR code {@link Bitmap} for {@code pairUrl} using the built-in {@link Qr} encoder. */
    public static Bitmap encodePairingQrBitmap(String pairUrl, int sizePx) {
        if (pairUrl == null || pairUrl.isEmpty()) return null;
        return Qr.encode(pairUrl, Math.max(160, sizePx), PAIRING_QR_QUIET_ZONE, 0xFF111827, 0xFFFFFFFF);
    }

    /** Renders a crisp inline SVG QR code for {@code pairUrl} so Admins can show a scannable invite QR on their own phone. */
    public static String encodePairingQrSvg(String pairUrl) {
        if (pairUrl == null || pairUrl.isEmpty()) return "";
        return Qr.encodeSvg(pairUrl, PAIRING_QR_QUIET_ZONE);
    }

    public static String sanitizeDeviceName(String raw) {
        if (raw == null) return "Companion Remote";
        StringBuilder sb = new StringBuilder();
        String trimmed = raw.trim();
        for (int i = 0; i < trimmed.length() && sb.length() < 48; i++) {
            char ch = trimmed.charAt(i);
            if (ch >= 0x20 && ch <= 0x7E && ch != '<' && ch != '>') {
                sb.append(ch);
            }
        }
        String res = sb.toString().trim();
        return res.isEmpty() ? "Companion Remote" : res;
    }

    /** Extracts Bearer token from an HTTP {@code Authorization} header value. */
    public static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) return "";
        String trimmed = authorizationHeader.trim();
        if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return "";
    }

    /** Authenticates a Bearer token and updates {@code lastSeenMs} throttled to once per minute. */
    public synchronized AuthResult authenticate(String rawBearerToken) {
        if (rawBearerToken == null || rawBearerToken.trim().isEmpty()) {
            return new AuthResult(AuthStatus.MISSING_TOKEN, null);
        }
        String hash = sha256Hex(rawBearerToken.trim());
        long now = clock.getAsLong();
        for (Map.Entry<String, PairedDevice> entry : devicesById.entrySet()) {
            PairedDevice dev = entry.getValue();
            if (constantTimeEquals(dev.tokenSha256, hash)) {
                if (dev.isExpired(now)) {
                    return new AuthResult(AuthStatus.GUEST_EXPIRED, dev);
                }
                if (now - dev.lastSeenMs >= 60_000L) {
                    PairedDevice updated = dev.withLastSeen(now);
                    entry.setValue(updated);
                    saveToSecrets();
                    return new AuthResult(AuthStatus.AUTHENTICATED, updated);
                }
                return new AuthResult(AuthStatus.AUTHENTICATED, dev);
            }
        }
        return new AuthResult(AuthStatus.INVALID_TOKEN, null);
    }

    /**
     * Evaluates Role-Based Access Control (RBAC) for a protected endpoint and action.
     *
     * @param role Authenticated caller role.
     * @param path Request path (e.g. {@code "/api/gaming"}, {@code "/api/update"}).
     * @param method HTTP method ({@code "GET"} or {@code "POST"}).
     * @param action Optional JSON {@code action} or {@code deviceId} discriminator.
     * @param hasUnlockPin True if a non-empty PC unlock PIN was supplied.
     * @return True if permitted, false if {@code 403 Forbidden}.
     */
    public static boolean isAuthorized(Role role, String path, String method, String action, boolean hasUnlockPin) {
        if (role == null || path == null) return false;
        if (role == Role.ADMIN) return true;

        String cleanAction = action != null ? action.trim().toLowerCase(Locale.ROOT) : "";
        if (path.startsWith("/api/admin/")) return false;
        if ("/api/update".equals(path)) return false;
        if ("/api/launcher_mode".equals(path)) return false;

        if ("/api/gaming".equals(path)) {
            if ("GET".equalsIgnoreCase(method)) return true;
            if ("unlock".equals(cleanAction) || hasUnlockPin) {
                return false; // Only ADMIN may unlock the Windows PC
            }
            return role == Role.HOUSEHOLD; // HOUSEHOLD can wake/launch/refresh/hide; GUEST cannot
        }

        if ("/api/user".equals(path)) {
            if (role == Role.GUEST) return false;
            return !"remove".equals(cleanAction); // Only ADMIN removes household profiles
        }

        if ("/api/smarthome".equals(path)) {
            if (role == Role.HOUSEHOLD) return true;
            // GUEST can toggle room lights / light dimming, not boiler timers or whole-house AC routines
            return cleanAction.isEmpty()
                    || cleanAction.startsWith("wiz_")
                    || "light_params".equals(cleanAction);
        }

        if ("/api/key".equals(path) && role == Role.GUEST) {
            return !"settings".equals(cleanAction) && !"users".equals(cleanAction) && !"trakt".equals(cleanAction);
        }

        if ("/api/modal".equals(path)) {
            return !cleanAction.startsWith("pair_"); // Only ADMIN (handled above) may approve pairing prompts remotely
        }

        return "/api/status".equals(path)
                || "/api/live".equals(path)
                || "/api/wake".equals(path)
                || "/api/key".equals(path)
                || "/api/scroll".equals(path)
                || "/api/text".equals(path)
                || "/api/voice".equals(path)
                || "/api/ai".equals(path)
                || "/api/cast".equals(path)
                || "/api/app".equals(path);
    }

    /** Returns true if at least one non-expired {@code ADMIN} device is currently paired. */
    public synchronized boolean hasAnyAdminDevice() {
        for (PairedDevice d : devicesById.values()) {
            if (d.role == Role.ADMIN) return true;
        }
        return false;
    }

    /** Returns the currently active pending pairing request if unexpired, or {@code null}. */
    public synchronized PendingPairRequest getActivePendingRequest() {
        long now = clock.getAsLong();
        if (activePending != null && activePending.getStatus(now) == PendingStatus.PENDING) {
            return activePending;
        }
        return null;
    }

    /**
     * Initiates a new 5-minute `"New Remote Detected"` pairing challenge (rate-limited to 5/min per IP).
     */
    public IssueResult createPairRequest(String deviceName, String clientId, String clientIp, String baseLanUrl) {
        PendingPairRequest created;
        synchronized (this) {
            long now = clock.getAsLong();
            if (!allowRateLimit(requestHitsByIp, clientIp, MAX_PAIR_REQUESTS_PER_MIN, now)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "rate_limited");
                err.put("message", "Too many pairing requests. Please wait a minute.");
                return new IssueResult(429, MiniJson.stringify(err), null);
            }
            created = buildPendingRequest(deviceName, clientId, clientIp, baseLanUrl, null, now);
            activePending = created;
        }
        notifyPromptChanged();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("requestId", created.requestId);
        out.put("expiresAtMs", created.expiresAtMs);
        out.put("bootstrapAdmin", !hasAnyAdminDevice());
        return new IssueResult(200, MiniJson.stringify(out), null);
    }

    /**
     * Creates an Admin-initiated 5-minute invite (with a pre-selected role) returning both the 6-digit code and QR URL.
     */
    public PendingPairRequest createInvite(Role role, String baseLanUrl) {
        PendingPairRequest invite;
        synchronized (this) {
            long now = clock.getAsLong();
            Role targetRole = role != null ? role : Role.GUEST;
            invite = buildPendingRequest("Invited " + targetRole.name(), "", "admin", baseLanUrl, targetRole, now);
            activePending = invite;
        }
        notifyPromptChanged();
        return invite;
    }

    private PendingPairRequest buildPendingRequest(
            String deviceName,
            String clientId,
            String clientIp,
            String baseLanUrl,
            Role presetRole,
            long nowMs) {
        String reqId = randomUrlSafeToken(16);
        String code = String.format(Locale.ROOT, "%06d", 100000 + rng.nextInt(900000));
        String qrSecret = randomUrlSafeToken(16); // 128-bit URL-safe secret (22 chars)
        String cleanBase = (baseLanUrl != null && !baseLanUrl.trim().isEmpty())
                ? baseLanUrl.trim().replaceAll("/+$", "")
                : "http://127.0.0.1:8484";
        String pairUrl = cleanBase + "/?pair=" + qrSecret;
        boolean isBootstrap = !hasAnyAdminDevice() && (presetRole == null || presetRole == Role.ADMIN);
        return new PendingPairRequest(
                reqId,
                deviceName,
                clientId,
                clientIp,
                code,
                qrSecret,
                pairUrl,
                presetRole,
                isBootstrap,
                nowMs,
                nowMs + PENDING_PAIR_TTL_MS
        );
    }

    /**
     * Approves the active pending pairing request directly from the TV screen (1-click TV remote approval).
     */
    public PairedDevice approvePendingOnTv(Role chosenRole) {
        PairedDevice paired;
        synchronized (this) {
            long now = clock.getAsLong();
            if (activePending == null || activePending.getStatus(now) != PendingStatus.PENDING) {
                return null;
            }
            Role granted = chosenRole != null ? chosenRole : Role.GUEST;
            String rawToken = randomUrlSafeToken(32); // 256-bit bearer token
            paired = registerDeviceLocked(activePending.deviceName, rawToken, granted, "", now);
            synchronized (activePending) {
                activePending.status = PendingStatus.APPROVED;
                activePending.transientIssuedToken = rawToken;
                activePending.approvedDevice = paired;
            }
        }
        notifyPromptChanged();
        return paired;
    }

    /** Dismisses/rejects the active pending pairing request from the TV screen. */
    public void dismissPendingOnTv() {
        synchronized (this) {
            if (activePending != null) {
                synchronized (activePending) {
                    activePending.status = PendingStatus.DISMISSED;
                    activePending.transientIssuedToken = "";
                }
            }
        }
        notifyPromptChanged();
    }

    /**
     * Polls a pending pairing request ({@code GET /api/pair/poll?requestId=...}) so a phone waiting on the TV
     * screen receives its issued Bearer token automatically as soon as the TV remote clicks Approve.
     */
    public synchronized IssueResult pollPairRequest(String requestId) {
        return pollPairRequest(requestId, "");
    }

    public synchronized IssueResult pollPairRequest(String requestId, String clientIp) {
        long now = clock.getAsLong();
        Map<String, Object> out = new LinkedHashMap<>();
        if (requestId == null || requestId.trim().isEmpty() || activePending == null
                || !constantTimeEquals(activePending.requestId, requestId.trim())) {
            out.put("status", "expired");
            return new IssueResult(200, MiniJson.stringify(out), null);
        }
        PendingStatus st = activePending.getStatus(now);
        if (st == PendingStatus.PENDING) {
            out.put("status", "pending");
            out.put("expiresAtMs", activePending.expiresAtMs);
            return new IssueResult(200, MiniJson.stringify(out), null);
        }
        if (st == PendingStatus.APPROVED && !activePending.transientIssuedToken.isEmpty() && activePending.approvedDevice != null) {
            String token = activePending.transientIssuedToken;
            PairedDevice dev = activePending.approvedDevice;
            String handoffIp = (clientIp != null && !clientIp.trim().isEmpty()) ? clientIp.trim() : activePending.clientIp;
            recordSameIpApkHandoff(handoffIp, token, dev.role);
            // One-time token delivery: wipe transient plaintext token immediately after poll
            activePending.transientIssuedToken = "";
            activePending = null;
            out.put("status", "approved");
            out.put("ok", true);
            out.put("token", token);
            out.put("deviceId", dev.deviceId);
            out.put("role", dev.role.name());
            out.put("expiresAtMs", dev.expiresAtMs);
            return new IssueResult(200, MiniJson.stringify(out), dev);
        }
        out.put("status", st == PendingStatus.DISMISSED ? "dismissed" : "expired");
        return new IssueResult(200, MiniJson.stringify(out), null);
    }

    /**
     * Verifies either a 6-digit code, a 128-bit QR secret, or a live camera QR finder-pattern lock ({@code POST /api/pair/verify}).
     */
    public IssueResult verifyPairing(String code, String qrSecret, String deviceName, String clientIp) {
        return verifyPairing(code, qrSecret, "", "", false, deviceName, clientIp);
    }

    public IssueResult verifyPairing(
            String code,
            String qrSecret,
            String requestId,
            String clientId,
            boolean qrCameraDetected,
            String deviceName,
            String clientIp) {
        PairedDevice dev;
        String rawToken;
        synchronized (this) {
            long now = clock.getAsLong();
            if (!checkFailureBudget(failedVerifyHitsByIp, clientIp, MAX_VERIFY_FAILURES_PER_MIN, now)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "rate_limited");
                err.put("message", "Too many failed pairing attempts. Try again in 1 minute.");
                return new IssueResult(429, MiniJson.stringify(err), null);
            }
            if (activePending == null || activePending.getStatus(now) != PendingStatus.PENDING) {
                recordFailure(failedVerifyHitsByIp, clientIp, now);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "no_pending_pair");
                err.put("message", "No active pairing code on TV. Request pairing again.");
                return new IssueResult(400, MiniJson.stringify(err), null);
            }
            String cleanCode = code != null ? code.trim() : "";
            String cleanQr = qrSecret != null ? qrSecret.trim() : "";
            String cleanReqId = requestId != null ? requestId.trim() : "";
            String cleanClientId = clientId != null ? clientId.trim() : "";
            boolean matchedCode = cleanCode.length() == 6 && constantTimeEquals(activePending.sixDigitCode, cleanCode);
            boolean matchedQr = cleanQr.length() >= 16 && constantTimeEquals(activePending.qrSecret, cleanQr);
            boolean matchedCameraQr = qrCameraDetected
                    && !cleanReqId.isEmpty()
                    && constantTimeEquals(activePending.requestId, cleanReqId)
                    && (activePending.clientId.isEmpty() || constantTimeEquals(activePending.clientId, cleanClientId));
            if (!matchedCode && !matchedQr && !matchedCameraQr) {
                recordFailure(failedVerifyHitsByIp, clientIp, now);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "invalid_code");
                err.put("message", "Invalid pairing code or QR link.");
                return new IssueResult(401, MiniJson.stringify(err), null);
            }
            Role grantedRole;
            if (activePending.presetRole != null) {
                grantedRole = activePending.presetRole;
            } else if (!hasAnyAdminDevice()) {
                grantedRole = Role.ADMIN;
            } else {
                grantedRole = Role.GUEST;
            }
            String finalName = (deviceName != null && !deviceName.trim().isEmpty())
                    ? deviceName
                    : activePending.deviceName;
            rawToken = randomUrlSafeToken(32);
            dev = registerDeviceLocked(finalName, rawToken, grantedRole, "", now);
            recordSameIpApkHandoff(clientIp, rawToken, dev.role);
            activePending.status = PendingStatus.APPROVED;
            activePending.transientIssuedToken = "";
            activePending = null;
        }
        notifyPromptChanged();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", rawToken);
        out.put("deviceId", dev.deviceId);
        out.put("role", dev.role.name());
        out.put("expiresAtMs", dev.expiresAtMs);
        return new IssueResult(200, MiniJson.stringify(out), dev);
    }

    /**
     * Verifies a Supabase Google OAuth {@code access_token} directly against {@code <supabaseUrl>/auth/v1/user}
     * and checks the verified Google email against the owner's {@code admin_emails} whitelist.
     */
    public IssueResult authenticateWithGoogleToken(String accessToken, String deviceName) {
        return authenticateWithGoogleToken(accessToken, deviceName, "");
    }

    public IssueResult authenticateWithGoogleToken(String accessToken, String deviceName, String clientIp) {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "missing_access_token");
            return new IssueResult(400, MiniJson.stringify(err), null);
        }
        String supabaseUrl = resolveSupabaseUrl();
        String anonKey = resolveSupabaseAnonKey();
        if (supabaseUrl.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "supabase_not_configured");
            return new IssueResult(503, MiniJson.stringify(err), null);
        }
        Set<String> whitelist = resolveAdminEmailWhitelist();
        if (whitelist.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "email_not_whitelisted");
            err.put("message", "No admin_emails configured on TV. Pair via TV screen first or set admin_emails.");
            return new IssueResult(403, MiniJson.stringify(err), null);
        }

        String userEndpoint = supabaseUrl.replaceAll("/+$", "") + "/auth/v1/user";
        // Allow https:// in production, plus http://127.0.0.1 or http://localhost strictly for local loopback unit tests
        String lowerUrl = userEndpoint.toLowerCase(Locale.ROOT);
        if (!lowerUrl.startsWith("https://")
                && !lowerUrl.startsWith("http://127.0.0.1:")
                && !lowerUrl.startsWith("http://localhost:")) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "insecure_supabase_url");
            return new IssueResult(400, MiniJson.stringify(err), null);
        }

        String userJson;
        int httpCode;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(userEndpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            if (!anonKey.isEmpty()) {
                conn.setRequestProperty("apikey", anonKey);
            }
            conn.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
            conn.setRequestProperty("Accept", "application/json");
            try {
                httpCode = conn.getResponseCode();
                InputStream stream = (httpCode >= 200 && httpCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                userJson = stream != null ? readBoundedUtf8(stream, 32768) : "";
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "google_verify_unreachable");
            return new IssueResult(502, MiniJson.stringify(err), null);
        }

        if (httpCode != 200 || userJson.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "invalid_google_token");
            return new IssueResult(401, MiniJson.stringify(err), null);
        }

        IssueResult res = verifyParsedGoogleUserJson(userJson, whitelist, deviceName);
        if (res.httpStatus == 200 && res.device != null && clientIp != null && !clientIp.trim().isEmpty()) {
            Object parsed = MiniJson.parse(res.jsonBody);
            if (parsed instanceof Map) {
                Object tok = ((Map<?, ?>) parsed).get("token");
                if (tok != null) {
                    recordSameIpApkHandoff(clientIp, String.valueOf(tok), res.device.role);
                }
            }
        }
        return res;
    }

    IssueResult verifyParsedGoogleUserJson(String userJson, Set<String> whitelist, String deviceName) {
        Object parsedObj = MiniJson.parse(userJson);
        if (!(parsedObj instanceof Map)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "invalid_google_response");
            return new IssueResult(401, MiniJson.stringify(err), null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) parsedObj;
        String email = asString(userMap.get("email")).trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || !email.contains("@")) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "missing_google_email");
            return new IssueResult(401, MiniJson.stringify(err), null);
        }

        boolean googleProvider = hasGoogleProvider(userMap);
        boolean emailVerified = isGoogleEmailVerified(userMap);
        if (!googleProvider || !emailVerified) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "unverified_google_identity");
            return new IssueResult(401, MiniJson.stringify(err), null);
        }

        if (whitelist == null || !whitelist.contains(email)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "email_not_whitelisted");
            return new IssueResult(403, MiniJson.stringify(err), null);
        }

        String rawToken;
        PairedDevice adminDev;
        List<Map<String, Object>> devicesList;
        synchronized (this) {
            long now = clock.getAsLong();
            rawToken = randomUrlSafeToken(32);
            String cleanName = sanitizeDeviceName(deviceName != null && !deviceName.trim().isEmpty() ? deviceName : "Admin Phone");
            // Replace previous token for same Google email + deviceName to avoid duplicate entries on re-login
            String existingId = null;
            for (PairedDevice d : devicesById.values()) {
                if (email.equalsIgnoreCase(d.googleEmail) && cleanName.equalsIgnoreCase(d.deviceName)) {
                    existingId = d.deviceId;
                    break;
                }
            }
            if (existingId != null) {
                devicesById.remove(existingId);
            }
            adminDev = registerDeviceLocked(cleanName, rawToken, Role.ADMIN, email, now);
            devicesList = buildPublicDevicesListLocked(adminDev.tokenSha256, now);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("role", Role.ADMIN.name());
        out.put("token", rawToken);
        out.put("deviceId", adminDev.deviceId);
        out.put("email", email);
        out.put("devices", devicesList);
        return new IssueResult(200, MiniJson.stringify(out), adminDev);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasGoogleProvider(Map<String, Object> userMap) {
        Object appMetaObj = userMap.get("app_metadata");
        if (appMetaObj instanceof Map) {
            Map<String, Object> appMeta = (Map<String, Object>) appMetaObj;
            if ("google".equalsIgnoreCase(asString(appMeta.get("provider")))) {
                return true;
            }
            Object providers = appMeta.get("providers");
            if (providers instanceof List) {
                for (Object p : (List<Object>) providers) {
                    if ("google".equalsIgnoreCase(asString(p))) return true;
                }
            }
        }
        Object identitiesObj = userMap.get("identities");
        if (identitiesObj instanceof List) {
            for (Object item : (List<Object>) identitiesObj) {
                if (item instanceof Map) {
                    Map<String, Object> idMap = (Map<String, Object>) item;
                    if ("google".equalsIgnoreCase(asString(idMap.get("provider")))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean isGoogleEmailVerified(Map<String, Object> userMap) {
        if (!asString(userMap.get("email_confirmed_at")).trim().isEmpty()) {
            return true;
        }
        Object userMetaObj = userMap.get("user_metadata");
        if (userMetaObj instanceof Map) {
            Map<String, Object> userMeta = (Map<String, Object>) userMetaObj;
            Object ev = userMeta.get("email_verified");
            if (Boolean.TRUE.equals(ev) || "true".equalsIgnoreCase(asString(ev))) {
                return true;
            }
        }
        return false;
    }

    /** Returns an unmodifiable snapshot of all paired devices. */
    public synchronized List<PairedDevice> listDevices() {
        return Collections.unmodifiableList(new ArrayList<>(devicesById.values()));
    }

    /** Builds JSON-serializable device maps for {@code GET /api/admin/devices}. */
    public synchronized List<Map<String, Object>> listPublicDeviceMaps(String currentTokenSha256) {
        return buildPublicDevicesListLocked(currentTokenSha256, clock.getAsLong());
    }

    private List<Map<String, Object>> buildPublicDevicesListLocked(String currentTokenSha256, long nowMs) {
        List<Map<String, Object>> list = new ArrayList<>(devicesById.size());
        for (PairedDevice d : devicesById.values()) {
            list.add(d.toPublicMap(currentTokenSha256, nowMs));
        }
        return list;
    }

    /** Revokes (kicks) a paired device by {@code deviceId}. */
    public synchronized boolean revokeDevice(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) return false;
        PairedDevice removed = devicesById.remove(deviceId.trim());
        if (removed != null) {
            saveToSecrets();
            return true;
        }
        return false;
    }

    /** Updates the role of an existing paired device ({@code GUEST <-> HOUSEHOLD <-> ADMIN}). */
    public synchronized boolean setDeviceRole(String deviceId, Role newRole) {
        if (deviceId == null || newRole == null) return false;
        String cleanId = deviceId.trim();
        PairedDevice existing = devicesById.get(cleanId);
        if (existing == null) return false;
        devicesById.put(cleanId, existing.withRole(newRole, clock.getAsLong()));
        saveToSecrets();
        return true;
    }

    /** Updates the custom nickname of an existing paired phone remote (persisted in encrypted Secrets). */
    public synchronized boolean renameDevice(String deviceId, String newDeviceName) {
        if (deviceId == null || newDeviceName == null) return false;
        String cleanId = deviceId.trim();
        String trimmedName = newDeviceName.trim();
        if (cleanId.isEmpty() || trimmedName.isEmpty()) return false;
        PairedDevice existing = devicesById.get(cleanId);
        if (existing == null) return false;
        devicesById.put(cleanId, existing.withDeviceName(trimmedName));
        saveToSecrets();
        return true;
    }

    /** Builds the unauthenticated {@code GET /api/auth/config} JSON payload. */
    public synchronized String buildAuthConfigJson(String authorizationHeader, String tvName) {
        return buildAuthConfigJson(authorizationHeader, tvName, "");
    }

    public synchronized String buildAuthConfigJson(String authorizationHeader, String tvName, String clientIp) {
        String bearer = extractBearerToken(authorizationHeader);
        AuthResult auth = authenticate(bearer);
        if (auth.isAuthenticated() && !bearer.isEmpty() && clientIp != null && !clientIp.trim().isEmpty()) {
            recordSameIpApkHandoff(clientIp, bearer, auth.device.role);
        }
        String supabaseUrl = resolveSupabaseUrl();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paired", auth.isAuthenticated());
        out.put("role", auth.isAuthenticated() ? auth.device.role.name() : "");
        out.put("guestExpired", auth.status == AuthStatus.GUEST_EXPIRED);
        out.put("expiresAtMs", auth.device != null ? auth.device.expiresAtMs : 0L);
        out.put("googleAuthEnabled", !supabaseUrl.isEmpty());
        out.put("supabaseUrl", supabaseUrl);
        out.put("tvName", tvName != null && !tvName.isEmpty() ? tvName : "SoarTV");
        out.put("bootstrapAdmin", !hasAnyAdminDevice());
        if (appContext != null) {
            try {
                TvDeviceIdentity id = TvDeviceIdentity.get(appContext);
                out.put("tvDeviceId", id.getDeviceId());
                out.put("mdnsHost", id.getMdnsHost());
                out.put("tvRoom", SmartHomeManager.get(appContext).getTvRoom());
                out.put("tvBrand", TvDeviceProfile.current().getBrand().name());
                out.put("tvPlatform", TvDeviceIdentity.PLATFORM_ANDROID_TV);
            } catch (RuntimeException ignored) {
            }
        }
        // Authenticated ADMIN and HOUSEHOLD remotes receive household TV-directory access for multi-TV
        // switching, IP-change self-healing, and LG/Samsung Realtime remote control.
        // GUEST (30-day) remotes NEVER receive household_key and stay strictly LAN-scoped.
        if (auth.isAuthenticated() && auth.device != null
                && (auth.device.role == Role.ADMIN || auth.device.role == Role.HOUSEHOLD)) {
            String anonKey = resolveSupabaseAnonKey();
            String hhKey = appContext != null
                    ? SupabaseClient.get(appContext).resolveHouseholdKey()
                    : Secrets.getSecret(null, SupabaseClient.SECRET_HOUSEHOLD_KEY);
            if (!supabaseUrl.isEmpty() && !anonKey.isEmpty() && !hhKey.isEmpty()) {
                out.put("supabaseAnonKey", anonKey);
                out.put("householdKey", hhKey);
            }
        }
        if (!auth.isAuthenticated() && clientIp != null && !clientIp.trim().isEmpty()) {
            ApkHandoff handoff = apkHandoffByIp.get(clientIp.trim());
            if (handoff != null && (clock.getAsLong() - handoff.timestampMs) <= APK_HANDOFF_TTL_MS) {
                AuthResult handoffAuth = authenticate(handoff.token);
                if (handoffAuth.isAuthenticated() && handoffAuth.device != null) {
                    out.put("handoffToken", handoff.token);
                    out.put("handoffRole", handoffAuth.device.role.name());
                }
            }
        }
        return MiniJson.stringify(out);
    }

    public String resolveSupabaseUrl() {
        if (supabaseUrlOverrideForTest != null) return supabaseUrlOverrideForTest.trim();
        try {
            if (Prefs.context() == null && appContext != null) {
                Prefs.init(appContext);
            }
            if (Prefs.context() != null) {
                String u = Prefs.supabaseUrl();
                if (u != null && !u.trim().isEmpty()) return u.trim();
            }
        } catch (RuntimeException ignored) {
        }
        return Config.SUPABASE_URL != null ? Config.SUPABASE_URL.trim() : "";
    }

    public String resolveSupabaseAnonKey() {
        if (supabaseAnonKeyOverrideForTest != null) return supabaseAnonKeyOverrideForTest.trim();
        try {
            if (Prefs.context() == null && appContext != null) {
                Prefs.init(appContext);
            }
            if (Prefs.context() != null) {
                String k = Prefs.supabaseAnonKey();
                if (k != null && !k.trim().isEmpty()) return k.trim();
            }
        } catch (RuntimeException ignored) {
        }
        return Config.SUPABASE_ANON_KEY != null ? Config.SUPABASE_ANON_KEY.trim() : "";
    }

    public Set<String> resolveAdminEmailWhitelist() {
        Set<String> emails = new HashSet<>();
        String rawSecret = adminEmailsOverrideForTest != null
                ? adminEmailsOverrideForTest
                : Secrets.getSecret(appContext, Secrets.ADMIN_EMAILS);
        addEmails(emails, rawSecret);
        if (adminEmailsOverrideForTest == null) {
            addEmails(emails, Config.ADMIN_EMAILS);
        }
        return emails;
    }

    private static void addEmails(Set<String> target, String csv) {
        if (csv == null || csv.trim().isEmpty()) return;
        for (String part : csv.split("[,;\\s]+")) {
            String clean = part.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty() && clean.contains("@")) {
                target.add(clean);
            }
        }
    }

    private PairedDevice registerDeviceLocked(
            String deviceName,
            String rawToken,
            Role role,
            String googleEmail,
            long nowMs) {
        String devId = "rem_" + randomUrlSafeToken(9);
        String tokenHash = sha256Hex(rawToken);
        long expiresAt = role == Role.GUEST ? (nowMs + GUEST_TTL_MS) : 0L;
        PairedDevice dev = new PairedDevice(
                devId,
                sanitizeDeviceName(deviceName),
                tokenHash,
                role,
                googleEmail,
                nowMs,
                expiresAt,
                nowMs
        );
        devicesById.put(devId, dev);
        saveToSecrets();
        return dev;
    }

    /** Reloads paired Companion Remote device records after a Household Cloud Vault sync. */
    public synchronized void reloadFromSecrets() {
        loadFromSecrets();
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadFromSecrets() {
        devicesById.clear();
        String json = Secrets.getSecret(appContext, Secrets.REMOTE_PAIRED_DEVICES);
        if (json == null || json.trim().isEmpty()) return;
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof List)) return;
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String id = asString(m.get("deviceId")).trim();
            String hash = asString(m.get("tokenSha256")).trim();
            if (id.isEmpty() || hash.length() != 64) continue;
            PairedDevice dev = new PairedDevice(
                    id,
                    asString(m.get("deviceName")),
                    hash,
                    Role.fromString(asString(m.get("role")), Role.GUEST),
                    asString(m.get("googleEmail")),
                    asLong(m.get("createdAtMs"), 0L),
                    asLong(m.get("expiresAtMs"), 0L),
                    asLong(m.get("lastSeenMs"), 0L)
            );
            devicesById.put(id, dev);
        }
    }

    private synchronized void saveToSecrets() {
        if (devicesById.isEmpty()) {
            Secrets.deleteSecret(appContext, Secrets.REMOTE_PAIRED_DEVICES);
            notifyHouseholdRemoteVaultChanged();
            return;
        }
        List<Map<String, Object>> arr = new ArrayList<>(devicesById.size());
        for (PairedDevice d : devicesById.values()) {
            arr.add(d.toStorageMap());
        }
        Secrets.putSecret(appContext, Secrets.REMOTE_PAIRED_DEVICES, MiniJson.stringify(arr));
        notifyHouseholdRemoteVaultChanged();
    }

    private void notifyHouseholdRemoteVaultChanged() {
        if (appContext == null) return;
        try {
            CloudSyncManager.get(appContext).recordLocalSecretChange(
                    CloudSyncManager.Scope.HOUSEHOLD, Secrets.REMOTE_PAIRED_DEVICES);
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean allowRateLimit(Map<String, List<Long>> map, String ip, int maxPerWindow, long nowMs) {
        String key = ip != null && !ip.isEmpty() ? ip : "unknown";
        List<Long> hits = pruneHits(map, key, nowMs);
        if (hits.size() >= maxPerWindow) {
            return false;
        }
        hits.add(nowMs);
        return true;
    }

    private static boolean checkFailureBudget(Map<String, List<Long>> map, String ip, int maxFailures, long nowMs) {
        String key = ip != null && !ip.isEmpty() ? ip : "unknown";
        return pruneHits(map, key, nowMs).size() < maxFailures;
    }

    private static void recordFailure(Map<String, List<Long>> map, String ip, long nowMs) {
        String key = ip != null && !ip.isEmpty() ? ip : "unknown";
        pruneHits(map, key, nowMs).add(nowMs);
    }

    private static List<Long> pruneHits(Map<String, List<Long>> map, String key, long nowMs) {
        List<Long> hits = map.get(key);
        if (hits == null) {
            hits = new ArrayList<>();
            map.put(key, hits);
        }
        Iterator<Long> it = hits.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next() >= RATE_WINDOW_MS) {
                it.remove();
            }
        }
        return hits;
    }

    private String randomUrlSafeToken(int numBytes) {
        byte[] bytes = new byte[numBytes];
        rng.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String readBoundedUtf8(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            int allowed = Math.min(n, maxBytes - total);
            if (allowed > 0) bos.write(buf, 0, allowed);
            total += n;
            if (total >= maxBytes) break;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static long asLong(Object v, long fallback) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /**
     * Pure-Java RFC-8259 JSON parser and serializer so {@link RemoteAuthManager} and {@link RemoteControlServer}
     * operate deterministically on both Android TV and headless Desktop JVM unit tests (without hitting
     * {@code android.jar}'s {@code org.json} stubs).
     */
    static final class MiniJson {
        private final String s;
        private int pos;

        private MiniJson(String s) {
            this.s = s != null ? s : "";
        }

        static Object parse(String json) {
            if (json == null || json.trim().isEmpty()) return null;
            MiniJson p = new MiniJson(json);
            try {
                Object val = p.readValue();
                p.skipWs();
                return val;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String json) {
            Object v = parse(json);
            return (v instanceof Map) ? (Map<String, Object>) v : new LinkedHashMap<>();
        }

        static String stringify(Object value) {
            StringBuilder sb = new StringBuilder();
            writeJson(sb, value);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeJson(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
                sb.append(v);
            } else if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (Double.isFinite(d)) sb.append(v);
                else sb.append("null");
            } else if (v instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeJson(sb, e.getValue());
                }
                sb.append('}');
            } else if (v instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object item : (Iterable<Object>) v) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJson(sb, item);
                }
                sb.append(']');
            } else {
                writeString(sb, String.valueOf(v));
            }
        }

        static void writeString(StringBuilder sb, String str) {
            sb.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected EOF");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw new IllegalArgumentException("Expected key");
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("Expected ':'");
                pos++;
                Object val = readValue();
                map.put(key, val);
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("Unclosed object");
                char sep = s.charAt(pos++);
                if (sep == '}') break;
                if (sep != ',') throw new IllegalArgumentException("Expected ',' or '}'");
            }
            return map;
        }

        private List<Object> readArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("Unclosed array");
                char sep = s.charAt(pos++);
                if (sep == ']') break;
                if (sep != ',') throw new IllegalArgumentException("Expected ',' or ']'");
            }
            return list;
        }

        private String readString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("Incomplete escape");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            sb.append(esc);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        private Number readNumber() {
            int start = pos;
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) throw new IllegalArgumentException("Expected " + literal);
            pos += literal.length();
        }
    }
}
