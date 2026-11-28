package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Permanent per-TV device identity, live IP-change monitor, LAN UDP discovery responder, and mDNS
 * ({@code _soartv._tcp}) advertiser for IP-resilient Multi-TV operation.
 *
 * <p><b>Why:</b> Using a TV's model string (e.g. {@code soartv-mitv}) collides when a household owns
 * two TVs of the same model, while reading Wi-Fi MAC addresses is blocked/randomized on Android 11–14.
 * Anchoring identity to a permanent UUID ({@code ANDROID_ID}-derived and persisted) and actively watching
 * {@link ConnectivityManager.NetworkCallback} guarantees that router reboots, DHCP changes, or Wi-Fi
 * switches immediately refresh Supabase {@code tv_devices} and LAN discovery without breaking pairings.
 *
 * <p><b>What:</b> Generates and persists {@code deviceId}, {@code deviceName}, and {@code mdnsHost};
 * notifies listeners immediately on site-local IPv4 changes; responds to {@code SOARTV_TV_DISCOVER_V1}
 * UDP probes on port {@code 8484}; and registers an Android NSD mDNS service ({@code _soartv._tcp.}).
 */
public final class TvDeviceIdentity {

    public static final String UDP_DISCOVER_REQUEST_OP = "SOARTV_TV_DISCOVER_V1";
    public static final String UDP_DISCOVER_REPLY_OP = "SOARTV_TV_HERE_V1";
    public static final String MDNS_SERVICE_TYPE = "_soartv._tcp.";
    public static final String PLATFORM_ANDROID_TV = "android-tv";

    private static final String PREFS_NAME = "soartv_tv_identity";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_LAST_LAN_IP = "last_lan_ip";

    /** Listener notified whenever this TV's active site-local IPv4 changes (DHCP/router/Wi-Fi switch). */
    public interface IpChangeListener {
        void onLanIpChanged(String newIp, String oldIp);
    }

    private static volatile TvDeviceIdentity instance;
    private final Context appContext;
    private volatile String cachedDeviceId;
    private volatile String cachedDeviceName;
    private volatile String lastKnownLanIp = "";
    private volatile boolean started = false;
    private volatile IpChangeListener ipChangeListener;

    private ConnectivityManager.NetworkCallback networkCallback;
    private DatagramSocket udpSocket;
    private Thread udpThread;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;

    private TvDeviceIdentity(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    /** Returns the singleton {@link TvDeviceIdentity}. */
    public static TvDeviceIdentity get(Context context) {
        TvDeviceIdentity cur = instance;
        if (cur == null) {
            synchronized (TvDeviceIdentity.class) {
                cur = instance;
                if (cur == null) {
                    cur = new TvDeviceIdentity(context);
                    instance = cur;
                }
            }
        }
        return cur;
    }

    /**
     * Deterministically derives a permanent UUID string from an Android {@code ANDROID_ID} seed
     * (pure static helper safe for headless JVM tests).
     *
     * @param androidId Hardware/app-scoped {@code Settings.Secure.ANDROID_ID}, or blank.
     * @return RFC 4122 UUID string.
     */
    public static String deriveStableDeviceId(String androidId) {
        if (androidId != null && !androidId.trim().isEmpty()
                && !"9774d56d682e549c".equalsIgnoreCase(androidId.trim())) {
            return UUID.nameUUIDFromBytes(
                    ("soartv-tv-v2:" + androidId.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)
            ).toString();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Sanitizes a TV room or friendly name into a valid RFC-6762 mDNS hostname label
     * (e.g., {@code "Living Room TV"} -> {@code "soartv-living-room-tv.local"}).
     */
    public static String buildMdnsHostname(String friendlyName, String deviceId) {
        String base = friendlyName == null ? "" : friendlyName.trim().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder();
        boolean prevDash = false;
        for (int i = 0; i < base.length() && slug.length() < 32; i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                slug.append(c);
                prevDash = false;
            } else if (!prevDash && slug.length() > 0) {
                slug.append('-');
                prevDash = true;
            }
        }
        while (slug.length() > 0 && slug.charAt(slug.length() - 1) == '-') {
            slug.deleteCharAt(slug.length() - 1);
        }
        if (slug.length() == 0) {
            String suffix = (deviceId != null && deviceId.length() >= 6)
                    ? deviceId.substring(0, 6).toLowerCase(Locale.ROOT)
                    : "tv";
            slug.append(suffix);
        }
        String label = slug.toString();
        if (!label.startsWith("soartv-")) {
            label = "soartv-" + label;
        }
        return label + ".local";
    }

    /** Returns the permanent unique UUID identifying this physical TV across IP/Wi-Fi/app changes. */
    public synchronized String getDeviceId() {
        if (cachedDeviceId != null && !cachedDeviceId.isEmpty()) {
            return cachedDeviceId;
        }
        SharedPreferences sp = prefs();
        if (sp != null) {
            String saved = sp.getString(KEY_DEVICE_ID, "").trim();
            if (!saved.isEmpty()) {
                cachedDeviceId = saved;
                return saved;
            }
        }
        String androidId = "";
        if (appContext != null) {
            try {
                androidId = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            } catch (RuntimeException ignored) {
            }
        }
        String created = deriveStableDeviceId(androidId);
        cachedDeviceId = created;
        if (sp != null) {
            sp.edit().putString(KEY_DEVICE_ID, created).apply();
        }
        return created;
    }

    /**
     * Explicitly adopts a target {@code deviceId} (e.g., provisioned by Setup Studio) and persists it.
     */
    public synchronized void setDeviceId(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) return;
        String clean = deviceId.trim();
        cachedDeviceId = clean;
        SharedPreferences sp = prefs();
        if (sp != null) {
            sp.edit().putString(KEY_DEVICE_ID, clean).apply();
        }
    }

    /** Returns the human-readable TV name (e.g., {@code "Living Room TV"} or device model). */
    public synchronized String getDeviceName() {
        if (cachedDeviceName != null && !cachedDeviceName.isEmpty()) {
            return cachedDeviceName;
        }
        SharedPreferences sp = prefs();
        if (sp != null) {
            String saved = sp.getString(KEY_DEVICE_NAME, "").trim();
            if (!saved.isEmpty()) {
                cachedDeviceName = saved;
                return saved;
            }
        }
        String systemName = "";
        if (appContext != null) {
            try {
                systemName = Settings.Global.getString(appContext.getContentResolver(), Settings.Global.DEVICE_NAME);
            } catch (RuntimeException ignored) {
            }
        }
        if (systemName != null && !systemName.trim().isEmpty()) {
            cachedDeviceName = systemName.trim();
            return cachedDeviceName;
        }
        try {
            String model = TvDeviceProfile.current().getModel();
            if (model != null && !model.trim().isEmpty()) {
                return model.trim();
            }
        } catch (RuntimeException ignored) {
        }
        return "SoarTV";
    }

    /** Persists a custom friendly name for this TV and refreshes mDNS if active. */
    public synchronized void setDeviceName(String name) {
        String clean = name == null ? "" : name.trim();
        cachedDeviceName = clean;
        SharedPreferences sp = prefs();
        if (sp != null) {
            sp.edit().putString(KEY_DEVICE_NAME, clean).apply();
        }
    }

    /** Returns the stable mDNS hostname for this TV (e.g., {@code "soartv-living-room.local"}). */
    public String getMdnsHost() {
        return buildMdnsHostname(getDeviceName(), getDeviceId());
    }

    /** Returns the last observed site-local IPv4 address for this TV. */
    public String getLastKnownLanIp() {
        if (lastKnownLanIp != null && !lastKnownLanIp.isEmpty()) {
            return lastKnownLanIp;
        }
        SharedPreferences sp = prefs();
        return sp != null ? sp.getString(KEY_LAST_LAN_IP, "") : "";
    }

    public void setIpChangeListener(IpChangeListener listener) {
        this.ipChangeListener = listener;
    }

    /**
     * Builds the compact JSON payload returned to {@code SOARTV_TV_DISCOVER_V1} UDP probes.
     */
    public static String buildDiscoveryReplyJson(
            String deviceId,
            String deviceName,
            String roomName,
            String brand,
            String model,
            String lanIp,
            int port,
            String mdnsHost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", UDP_DISCOVER_REPLY_OP);
        m.put("deviceId", deviceId == null ? "" : deviceId);
        m.put("deviceName", deviceName == null ? "SoarTV" : deviceName);
        m.put("roomName", roomName == null ? "Living Room" : roomName);
        m.put("brand", brand == null ? "GENERIC" : brand);
        m.put("model", model == null ? "" : model);
        m.put("platform", PLATFORM_ANDROID_TV);
        m.put("lanIp", lanIp == null ? "" : lanIp);
        m.put("port", port);
        m.put("mdnsHost", mdnsHost == null ? "" : mdnsHost);
        return CloudVaultCrypto.JsonCodec.toJsonObjectString(m);
    }

    /**
     * Validates an incoming UDP discovery probe string and returns a JSON reply payload if matched,
     * or {@code null} for unrelated datagrams.
     */
    public static String handleDiscoveryProbeText(
            String requestText,
            String deviceId,
            String deviceName,
            String roomName,
            String brand,
            String model,
            String lanIp,
            int port,
            String mdnsHost) {
        if (requestText == null || !requestText.contains(UDP_DISCOVER_REQUEST_OP)) {
            return null;
        }
        try {
            Map<String, Object> parsed = CloudVaultCrypto.JsonCodec.parseJsonObject(requestText.trim());
            String op = CloudVaultCrypto.JsonCodec.optString(parsed, "op", "");
            if (!UDP_DISCOVER_REQUEST_OP.equals(op)) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return buildDiscoveryReplyJson(deviceId, deviceName, roomName, brand, model, lanIp, port, mdnsHost);
    }

    /**
     * Starts live IP-change monitoring, LAN UDP discovery responder on {@code port}, and mDNS
     * {@code _soartv._tcp.} registration. Idempotent and non-blocking.
     */
    public synchronized void startServices(int remotePort) {
        if (started || appContext == null) return;
        started = true;
        int port = remotePort > 0 ? remotePort : Config.REMOTE_SERVER_PORT;

        InetAddress initial = RemoteControlServer.lanAddress(appContext);
        if (initial != null && initial.getHostAddress() != null) {
            recordLanIpLocked(initial.getHostAddress(), false);
        }

        registerNetworkCallback();
        startUdpDiscoveryResponder(port);
        registerMdnsService(port);
    }

    /** Stops network watchers, UDP responder, and mDNS advertisement cleanly. */
    public synchronized void stopServices() {
        started = false;
        if (networkCallback != null && appContext != null) {
            try {
                ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkCallback = null;
        }
        if (udpSocket != null) {
            try {
                udpSocket.close();
            } catch (RuntimeException ignored) {
            }
            udpSocket = null;
        }
        udpThread = null;
        if (nsdManager != null && nsdListener != null) {
            try {
                nsdManager.unregisterService(nsdListener);
            } catch (RuntimeException ignored) {
            }
            nsdListener = null;
        }
    }

    private void registerNetworkCallback() {
        if (appContext == null) return;
        try {
            ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    inspectActiveLanIp(cm.getLinkProperties(network));
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    inspectActiveLanIp(linkProperties);
                }
            };
            cm.registerNetworkCallback(req, networkCallback);
        } catch (RuntimeException e) {
            SupabaseClient.safeLogW("TvDeviceIdentity NetworkCallback registration failed: " + e.getMessage());
        }
    }

    private void inspectActiveLanIp(LinkProperties link) {
        String detected = "";
        if (link != null) {
            for (LinkAddress a : link.getLinkAddresses()) {
                InetAddress addr = a.getAddress();
                if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                    detected = addr.getHostAddress();
                    break;
                }
            }
        }
        if (detected == null || detected.isEmpty()) {
            InetAddress fallback = RemoteControlServer.lanAddress(appContext);
            if (fallback != null && fallback.getHostAddress() != null) {
                detected = fallback.getHostAddress();
            }
        }
        if (detected != null && !detected.isEmpty()) {
            recordLanIpLocked(detected, true);
        }
    }

    private void recordLanIpLocked(String newIp, boolean notifyIfChanged) {
        String clean = newIp == null ? "" : newIp.trim();
        if (clean.isEmpty()) return;
        String prev;
        boolean changed;
        synchronized (this) {
            prev = lastKnownLanIp;
            changed = !clean.equals(prev);
            lastKnownLanIp = clean;
            SharedPreferences sp = prefs();
            if (sp != null && changed) {
                sp.edit().putString(KEY_LAST_LAN_IP, clean).apply();
            }
        }
        if (notifyIfChanged && changed) {
            IpChangeListener listener = ipChangeListener;
            if (listener != null) {
                try {
                    listener.onLanIpChanged(clean, prev);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void startUdpDiscoveryResponder(int port) {
        udpThread = new Thread(() -> {
            try {
                DatagramSocket sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.bind(new InetSocketAddress(port));
                udpSocket = sock;

                byte[] buf = new byte[1024];
                while (started && !sock.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(pkt);
                    } catch (IOException e) {
                        if (!started || sock.isClosed()) break;
                        continue;
                    }
                    if (pkt.getLength() <= 0 || pkt.getAddress() == null) continue;
                    String req = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8);
                    InetAddress lan = RemoteControlServer.lanAddress(appContext);
                    String lanIp = lan != null ? lan.getHostAddress() : getLastKnownLanIp();
                    String room = "Living Room";
                    try {
                        room = SmartHomeManager.get(appContext).getTvRoom();
                    } catch (RuntimeException ignored) {
                    }
                    TvDeviceProfile profile = TvDeviceProfile.current();
                    String reply = handleDiscoveryProbeText(
                            req,
                            getDeviceId(),
                            getDeviceName(),
                            room,
                            profile.getBrand().name(),
                            profile.getModel(),
                            lanIp,
                            port,
                            getMdnsHost()
                    );
                    if (reply != null) {
                        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                        DatagramPacket resp = new DatagramPacket(out, out.length, pkt.getAddress(), pkt.getPort());
                        try {
                            sock.send(resp);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                SupabaseClient.safeLogW("TvDeviceIdentity UDP discovery bind warning: " + e.getMessage());
            }
        }, "soartv-tv-udp-discovery");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private void registerMdnsService(int port) {
        if (appContext == null) return;
        try {
            nsdManager = appContext.getSystemService(NsdManager.class);
            if (nsdManager == null) return;
            String serviceName = getMdnsHost().replace(".local", "");
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(serviceName);
            info.setServiceType(MDNS_SERVICE_TYPE);
            info.setPort(port);
            info.setAttribute("deviceId", getDeviceId());
            info.setAttribute("name", getDeviceName());
            info.setAttribute("platform", PLATFORM_ANDROID_TV);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {}
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            };
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            SupabaseClient.safeLogW("TvDeviceIdentity mDNS registration skipped: " + e.getMessage());
        }
    }

    private SharedPreferences prefs() {
        if (appContext == null) return null;
        try {
            return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
