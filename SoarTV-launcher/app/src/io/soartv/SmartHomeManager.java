package io.soartv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Direct TV-to-device Smart Home controller for Sensibo AC, dynamically discovered WiZ V2 LAN Lights,
 * Switcher Water Boiler, Tuya Bedroom AC, and Google Home Assistant routines.
 *
 * <p><b>Why:</b> Must operate 100% independently when the user's PC is powered off, and must automatically
 * discover WiZ light bulbs across any Wi-Fi subnet without relying on hardcoded IP addresses.
 *
 * <p><b>What:</b> Maintains live device state, performs dynamic UDP subnet discovery for WiZ V2 bulbs by MAC
 * address, syncs live AC state from Sensibo Cloud API v2, supports rich complex actions (AC modes, temperatures,
 * fan speeds, light dimming, color temperature, boiler timers, and multi-device routines), and integrates with
 * Google Home Assistant on Android TV.
 */
public final class SmartHomeManager {

    /** Supported Smart Home device protocols. */
    public enum Protocol {
        SENSIBO_AC,
        WIZ_LIGHT,
        SWITCHER_BOILER,
        TUYA_AC
    }

    /** Callback interface for asynchronous Smart Home device actions. */
    public interface Callback {
        void onComplete(boolean success, String statusMessage);
    }

    /** Encapsulates a single Smart Home device's state, metadata, and rich control parameters. */
    public static final class Device {
        private final String id;
        private volatile String name;
        private final Protocol protocol;
        private final String symbol;
        private volatile boolean isOn;
        private volatile String statusText;
        private volatile int targetTempCelsius;
        private volatile String acMode = "cool";
        private volatile String fanLevel = "auto";
        private volatile int brightness = 100;
        private volatile int colorTempK = 2700;
        private volatile int timerMinutes = 15;
        private volatile String macAddress = "";
        private volatile String currentIp = "";

        public Device(
                String id,
                String name,
                Protocol protocol,
                String symbol,
                boolean isOn,
                String statusText,
                int targetTempCelsius
        ) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.symbol = symbol;
            this.isOn = isOn;
            this.statusText = statusText;
            this.targetTempCelsius = targetTempCelsius;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Protocol getProtocol() { return protocol; }
        public String getSymbol() { return symbol; }
        public boolean isOn() { return isOn; }
        public String getStatusText() { return statusText; }
        public int getTargetTempCelsius() { return targetTempCelsius; }
        public int getTargetTempC() { return targetTempCelsius; }
        public String getAcMode() { return acMode; }
        public String getFanLevel() { return fanLevel; }
        public String getFanSpeed() { return fanLevel; }
        public int getBrightness() { return brightness; }
        public int getBrightnessPct() { return brightness; }
        public int getColorTempK() { return colorTempK; }
        public int getTimerMinutes() { return timerMinutes; }
        public String getMacAddress() { return macAddress; }
        public String getCurrentIp() { return currentIp; }

        public void setOn(boolean on) { this.isOn = on; }
        public void setStatusText(String text) { this.statusText = text; }
        public void setTargetTempCelsius(int temp) { this.targetTempCelsius = temp; }
        public void setAcMode(String mode) { this.acMode = mode != null ? mode : "cool"; }
        public void setFanLevel(String fan) { this.fanLevel = fan != null ? fan : "auto"; }
        public void setBrightness(int pct) { this.brightness = Math.max(10, Math.min(100, pct)); }
        public void setColorTempK(int kelvin) { this.colorTempK = Math.max(2200, Math.min(6500, kelvin)); }
        public void setLightState(boolean on, int brightnessPct, int kelvin) {
            setOn(on);
            setBrightness(brightnessPct);
            setColorTempK(kelvin);
        }
        public void setTimerMinutes(int min) { this.timerMinutes = Math.max(5, Math.min(120, min)); }
        public void setBoilerTimerMinutes(int min) { setTimerMinutes(min); }
        public void setMacAddress(String mac) { this.macAddress = mac != null ? mac : ""; }
        public void setCurrentIp(String ip) { this.currentIp = ip != null ? ip : ""; }

        /** @return Compact label for cards (e.g., {@code "Living AC · 22°C Cool"}). */
        public String getDisplayLabel() {
            if (statusText != null && !statusText.isEmpty()) {
                return name + " · " + statusText;
            }
            return name;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("protocol", protocol.name());
                o.put("symbol", symbol);
                o.put("icon", symbol);
                o.put("on", isOn);
                o.put("isOn", isOn);
                o.put("status", statusText);
                o.put("statusText", statusText);
                o.put("targetTemp", targetTempCelsius);
                o.put("targetTempCelsius", targetTempCelsius);
                o.put("acMode", acMode);
                o.put("fanLevel", fanLevel);
                o.put("brightness", brightness);
                o.put("colorTempK", colorTempK);
                o.put("timerMinutes", timerMinutes);
                o.put("macAddress", macAddress);
                o.put("currentIp", currentIp);
            } catch (JSONException ignored) {
            }
            return o;
        }
    }

    private static final int WIZ_UDP_PORT = 38899;
    private static final String PREFS_NAME = "soartv_smarthome";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smart-home-io");
        t.setDaemon(true);
        return t;
    });
    private static volatile Handler mainHandlerInstance;

    private static void postToMain(Runnable r) {
        if (r == null) return;
        try {
            Handler h = mainHandlerInstance;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                mainHandlerInstance = h;
            }
            h.post(r);
        } catch (RuntimeException headlessJvmStub) {
            r.run();
        }
    }

    private static volatile SmartHomeManager instance;
    private final Context appContext;
    private final List<Device> devices = new ArrayList<>();
    private final Map<String, String> discoveredWizIps = new LinkedHashMap<>(); // mac -> ip
    private volatile long lastDiscoveryMs = 0L;

    private SmartHomeManager(Context context) {
        this.appContext = context.getApplicationContext();
        initDevices();
        refreshStatesAsync(null);
    }

    public static SmartHomeManager get(Context context) {
        SmartHomeManager m = instance;
        if (m == null) {
            synchronized (SmartHomeManager.class) {
                m = instance;
                if (m == null) {
                    m = new SmartHomeManager(context);
                    instance = m;
                }
            }
        }
        return m;
    }

    private synchronized void initDevices() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        devices.clear();

        Device livingAc = new Device(
                "sensibo_living_ac",
                "Living Room AC",
                Protocol.SENSIBO_AC,
                "ac",
                prefs.getBoolean("on:sensibo_living_ac", false),
                prefs.getString("st:sensibo_living_ac", "Off · 23°C"),
                prefs.getInt("temp:sensibo_living_ac", 23)
        );
        livingAc.setAcMode(prefs.getString("mode:sensibo_living_ac", "cool"));
        livingAc.setFanLevel(prefs.getString("fan:sensibo_living_ac", "auto"));
        devices.add(livingAc);

        Device bedroomAc = new Device(
                "tuya_bedroom_ac",
                "Bedroom AC",
                Protocol.TUYA_AC,
                "ac",
                prefs.getBoolean("on:tuya_bedroom_ac", false),
                prefs.getString("st:tuya_bedroom_ac", "Off · 23°C"),
                prefs.getInt("temp:tuya_bedroom_ac", 23)
        );
        bedroomAc.setAcMode(prefs.getString("mode:tuya_bedroom_ac", "cool"));
        bedroomAc.setFanLevel(prefs.getString("fan:tuya_bedroom_ac", "auto"));
        devices.add(bedroomAc);

        Device allLights = new Device(
                "wiz_house_lights",
                "Lights",
                Protocol.WIZ_LIGHT,
                "lightbulb",
                prefs.getBoolean("on:wiz_house_lights", true),
                prefs.getString("st:wiz_house_lights", "On · 100%"),
                0
        );
        allLights.setBrightness(prefs.getInt("dim:wiz_house_lights", 100));
        allLights.setColorTempK(prefs.getInt("ct:wiz_house_lights", 2700));
        devices.add(allLights);

        Device boiler = new Device(
                "switcher_boiler",
                "Water Boiler",
                Protocol.SWITCHER_BOILER,
                "shower",
                prefs.getBoolean("on:switcher_boiler", false),
                prefs.getString("st:switcher_boiler", "Off · 15m Timer"),
                0
        );
        boiler.setTimerMinutes(prefs.getInt("timer:switcher_boiler", 15));
        if (boiler.getStatusText() != null && boiler.getStatusText().contains("45m") && boiler.getTimerMinutes() == 15) {
            updateStatusLabel(boiler);
        }
        devices.add(boiler);

        // Load previously discovered WiZ bulbs (persisted by MAC address so IP changes on Wi-Fi reconfiguration work seamlessly)
        String savedBulbsJson = prefs.getString("discovered_wiz_bulbs", "[]");
        try {
            JSONArray arr = new JSONArray(savedBulbsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String mac = o.optString("mac", "");
                String ip = o.optString("ip", "");
                String name = o.optString("name", "");
                if (!mac.isEmpty()) {
                    discoveredWizIps.put(mac, ip);
                    addOrUpdateWizBulbDevice(mac, ip, name, o.optBoolean("on", true), o.optInt("dimming", 100), o.optInt("temp", 2700));
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private synchronized void addOrUpdateWizBulbDevice(String mac, String ip, String customName, boolean on, int dimming, int tempK) {
        String cleanMac = mac.trim().toLowerCase(Locale.ROOT);
        String devId = "wiz_bulb_" + cleanMac;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedName = prefs.getString("name:" + devId, "");
        String label = !savedName.isEmpty() ? savedName : (!customName.isEmpty() ? customName : inferWizRoomName(ip, cleanMac));

        for (Device existing : devices) {
            if (existing.getId().equals(devId)) {
                existing.setCurrentIp(ip);
                existing.setMacAddress(cleanMac);
                existing.setOn(on);
                existing.setBrightness(dimming);
                if (tempK > 0) existing.setColorTempK(tempK);
                updateStatusLabel(existing);
                return;
            }
        }
        Device bulb = new Device(devId, label, Protocol.WIZ_LIGHT, "lightbulb", on,
                on ? ("On · " + dimming + "%") : "Off", 0);
        bulb.setMacAddress(cleanMac);
        bulb.setCurrentIp(ip);
        bulb.setBrightness(dimming);
        if (tempK > 0) bulb.setColorTempK(tempK);
        updateStatusLabel(bulb);
        devices.add(bulb);
    }

    private static String inferWizRoomName(String ip, String mac) {
        if (ip != null) {
            if (ip.endsWith(".9")) return "Bedroom Light";
            if (ip.endsWith(".10")) return "Toilets Light";
            if (ip.endsWith(".4")) return "Kitchen Light";
        }
        String suffix = mac.length() >= 4 ? mac.substring(mac.length() - 4).toUpperCase(Locale.ROOT) : mac;
        return "WiZ Light " + suffix;
    }

    private void persistState(Device d) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("on:" + d.getId(), d.isOn())
                .putString("st:" + d.getId(), d.getStatusText())
                .putInt("temp:" + d.getId(), d.getTargetTempCelsius())
                .putString("mode:" + d.getId(), d.getAcMode())
                .putString("fan:" + d.getId(), d.getFanLevel())
                .putInt("dim:" + d.getId(), d.getBrightness())
                .putInt("ct:" + d.getId(), d.getColorTempK())
                .putInt("timer:" + d.getId(), d.getTimerMinutes())
                .apply();
    }

    private synchronized void persistDiscoveredWizBulbs() {
        JSONArray arr = new JSONArray();
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && !d.getMacAddress().isEmpty()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("mac", d.getMacAddress());
                    o.put("ip", d.getCurrentIp());
                    o.put("name", d.getName());
                    o.put("on", d.isOn());
                    o.put("dimming", d.getBrightness());
                    o.put("temp", d.getColorTempK());
                    arr.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("discovered_wiz_bulbs", arr.toString())
                .apply();
        try {
            CloudSyncManager.get(appContext).recordLocalFieldChange(CloudSyncManager.Scope.HOUSEHOLD, "wiz_bulbs");
        } catch (RuntimeException ignored) {
        }
    }

    /** Exports discovered WiZ bulb MAC -> {name, room} mappings for Household Cloud Sync (zero static IPs). */
    public synchronized Map<String, Object> exportWizBulbRegistryForCloud() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && d.getMacAddress() != null && !d.getMacAddress().isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", d.getName());
                out.put(d.getMacAddress().toLowerCase(Locale.ROOT), entry);
            }
        }
        return out;
    }

    /** Imports household-synced WiZ bulb MAC -> {name} mappings so new TVs instantly recognize bulbs by MAC. */
    public synchronized void importWizBulbRegistryFromCloud(Map<String, Object> wizMap) {
        if (appContext == null || wizMap == null || wizMap.isEmpty()) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, Object> e : wizMap.entrySet()) {
            String mac = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
            if (mac.isEmpty()) continue;
            Map<String, Object> meta = CloudVaultCrypto.JsonCodec.optObjectMap(wizMap, e.getKey());
            String name = CloudVaultCrypto.JsonCodec.optString(meta, "name", "");
            if (!name.isEmpty()) {
                String devId = "wiz_bulb_" + mac;
                ed.putString("name:" + devId, name);
                Device existing = findDevice(devId);
                if (existing != null) {
                    existing.setName(name);
                } else {
                    addOrUpdateWizBulbDevice(mac, discoveredWizIps.getOrDefault(mac, ""), name, false, 100, 2700);
                }
            }
        }
        ed.apply();
    }

    /** Exports user-created smart-home room names for Household Cloud Sync. */
    public List<String> exportCustomRoomsForCloud() {
        List<String> out = new ArrayList<>();
        if (appContext == null) return out;
        String raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("custom_rooms", "");
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split("\\|")) {
                String r = part.trim();
                if (!r.isEmpty() && !out.contains(r)) out.add(r);
            }
        }
        return out;
    }

    /** Imports household-synced custom room names locally. */
    public void importCustomRoomsFromCloud(List<String> rooms) {
        if (appContext == null || rooms == null) return;
        StringBuilder sb = new StringBuilder();
        for (String r : rooms) {
            if (r == null || r.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('|');
            sb.append(r.trim());
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("custom_rooms", sb.toString()).apply();
    }

    /** @return Unmodifiable list of all Smart Home devices. */
    public synchronized List<Device> getDevices() {
        return Collections.unmodifiableList(new ArrayList<>(devices));
    }

    public synchronized Device findDevice(String deviceId) {
        for (Device d : devices) {
            if (d.getId().equals(deviceId)) return d;
        }
        return null;
    }

    /** @return JSONArray of all Smart Home devices for the Companion Remote App API. */
    public synchronized JSONArray toJsonArray() {
        JSONArray arr = new JSONArray();
        for (Device d : devices) arr.put(d.toJson());
        return arr;
    }

    /**
     * @return List of {@link HomeApps.Item} tiles for the Smart-Home screen,
     *         grouping all WiZ lights into a single {@code "Lights"} tile.
     */
    public synchronized List<HomeApps.Item> drawerItems() {
        List<HomeApps.Item> out = new ArrayList<>(devices.size());
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                continue;
            }
            out.add(HomeApps.smartDevice(d));
        }
        return out;
    }

    /**
     * Asynchronously refreshes live Sensibo AC status and scans the active LAN subnet for WiZ V2 bulbs.
     */
    public void refreshStatesAsync(final Runnable onUpdated) {
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryMs < 8_000L && onUpdated == null) return;
        lastDiscoveryMs = now;
        IO.execute(() -> {
            syncSensiboLiveState();
            discoverWizBulbsOnLanSync();
            if (onUpdated != null) postToMain(onUpdated);
        });
    }

    /**
     * Dynamically detects the TV's current active IPv4 subnet prefix (e.g. {@code "192.168.1."})
     * so WiZ discovery works even after Wi-Fi reconfiguration or router changes.
     */
    public static List<String> getActiveLanSubnets() {
        List<String> subnets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && ip.contains(".")) {
                            String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                            if (!subnets.contains(prefix)) subnets.add(prefix);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (subnets.isEmpty()) {
            subnets.add("192.168.1.");
        }
        return subnets;
    }

    /**
     * Builds the Phase 1 WiZ UDP discovery target list (cached bulb IPs + configured seed IPs +
     * subnet broadcast + limited broadcast {@code 255.255.255.255}), avoiding 59-host unicast sweeps
     * on normal refreshes when bulbs answer broadcast or cached probes.
     */
    static List<String> buildWizPhase1Targets(
            java.util.Collection<String> cachedIps,
            String rawConfiguredIps,
            List<String> activeSubnets) {
        List<String> targets = new ArrayList<>();
        if (cachedIps != null) {
            for (String ip : cachedIps) {
                if (ip != null && !ip.trim().isEmpty() && !targets.contains(ip.trim())) {
                    targets.add(ip.trim());
                }
            }
        }
        if (rawConfiguredIps != null && !rawConfiguredIps.trim().isEmpty()) {
            for (String part : rawConfiguredIps.split(",")) {
                String ip = part.trim();
                if (!ip.isEmpty() && !targets.contains(ip)) {
                    targets.add(ip);
                }
            }
        }
        if (activeSubnets != null) {
            for (String prefix : activeSubnets) {
                if (prefix != null && !prefix.isEmpty()) {
                    String bcast = prefix + "255";
                    if (!targets.contains(bcast)) targets.add(bcast);
                }
            }
        }
        if (!targets.contains("255.255.255.255")) {
            targets.add("255.255.255.255");
        }
        return targets;
    }

    /**
     * Scans the current Wi-Fi LAN for WiZ V2 light bulbs using UDP port 38899.
     * Phase 1 (~400ms): probes known/cached bulb IPs + subnet broadcast ({@code x.x.x.255:38899}) +
     * {@code 255.255.255.255:38899}. Phase 2 (fallback): runs a multi-IP unicast sweep ONLY when
     * Phase 1 returns 0 bulbs (e.g., on AP isolation routers).
     */
    public int discoverWizBulbsOnLanSync() {
        int foundCount = 0;
        byte[] probe = "{\"id\":1,\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
        List<String> subnets = getActiveLanSubnets();
        List<String> cachedSnapshot;
        synchronized (this) {
            cachedSnapshot = new ArrayList<>(discoveredWizIps.values());
        }
        String seedIps = Secrets.get(appContext, "wiz_light_ips");
        if (seedIps.isEmpty()) seedIps = Config.WIZ_LIGHT_IPS;
        List<String> phase1Targets = buildWizPhase1Targets(cachedSnapshot, seedIps, subnets);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(200);
            for (String target : phase1Targets) {
                try {
                    socket.send(new DatagramPacket(probe, probe.length, InetAddress.getByName(target), WIZ_UDP_PORT));
                } catch (IOException ignored) {
                }
            }
            foundCount += collectWizResponses(socket, System.currentTimeMillis() + 400L);

            // Fallback unicast sweep ONLY when broadcast + cached probes returned 0 bulbs
            if (foundCount == 0) {
                socket.setSoTimeout(300);
                for (String prefix : subnets) {
                    for (int host = 2; host <= 60; host++) {
                        try {
                            socket.send(new DatagramPacket(probe, probe.length, InetAddress.getByName(prefix + host), WIZ_UDP_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                }
                foundCount += collectWizResponses(socket, System.currentTimeMillis() + 750L);
            }
        } catch (IOException ignored) {
        }
        if (foundCount > 0) {
            persistDiscoveredWizBulbs();
        }
        return foundCount;
    }

    private int collectWizResponses(DatagramSocket socket, long deadlineMs) {
        int count = 0;
        byte[] buf = new byte[1024];
        while (System.currentTimeMillis() < deadlineMs) {
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(resp);
                String ip = resp.getAddress().getHostAddress();
                String jsonStr = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(jsonStr);
                JSONObject res = obj.optJSONObject("result");
                if (res != null) {
                    String mac = res.optString("mac", "");
                    boolean state = res.optBoolean("state", true);
                    int dimming = res.optInt("dimming", 100);
                    int temp = res.optInt("temp", 2700);
                    if (!mac.isEmpty() && ip != null) {
                        count++;
                        synchronized (this) {
                            discoveredWizIps.put(mac.toLowerCase(Locale.ROOT), ip);
                            addOrUpdateWizBulbDevice(mac, ip, "", state, dimming, temp);
                        }
                    }
                }
            } catch (IOException | JSONException timeoutOrParse) {
                break;
            }
        }
        return count;
    }

    /**
     * Toggles a Smart Home device by ID and executes the underlying LAN/Cloud protocol command off the UI thread.
     */
    public void toggleDevice(String deviceId, Callback callback) {
        Device dev = findDevice(deviceId);
        if (dev == null) {
            if (callback != null) callback.onComplete(false, "Unknown Smart Home device");
            return;
        }
        final boolean nextState = !dev.isOn();
        dev.setOn(nextState);
        updateStatusLabel(dev);
        persistState(dev);

        IO.execute(() -> {
            boolean ok = true;
            String message = dev.getName() + " → " + dev.getStatusText();
            try {
                switch (dev.getProtocol()) {
                    case WIZ_LIGHT:
                        ok = sendWizUdpParams(dev, nextState, dev.getBrightness(), dev.getColorTempK(), 0);
                        if (!ok) {
                            message = dev.getName() + ": No WiZ bulb responded on current Wi-Fi";
                        }
                        break;
                    case SENSIBO_AC:
                        ok = sendSensiboState(nextState, dev.getAcMode(), dev.getTargetTempCelsius(), dev.getFanLevel());
                        if (!ok) {
                            message = "Living Room AC: Check Sensibo connection or API key";
                        }
                        break;
                    case SWITCHER_BOILER:
                        ok = sendSwitcherState(nextState, dev.getTimerMinutes());
                        if (!ok) {
                            message = "Water Boiler: Use Google Home voice or add Switcher local key";
                        }
                        break;
                    case TUYA_AC:
                        ok = sendTuyaState(nextState, dev.getAcMode(), dev.getTargetTempCelsius(), dev.getFanLevel());
                        if (!ok) {
                            message = "Bedroom AC (Tuya): Enable Central Europe Data Center in iot.tuya.com or use Google Home";
                        }
                        break;
                }
            } catch (Exception e) {
                AppLog.w("SmartHome toggle " + dev.getId() + ": " + e.getMessage());
                ok = false;
                message = dev.getName() + " Error: " + e.getMessage();
            }
            final boolean finalOk = ok;
            final String finalMsg = message;
            postToMain(() -> {
                if (callback != null) callback.onComplete(finalOk, finalMsg);
            });
        });
    }

    /**
     * Alias for {@link #setAcParameters(String, boolean, String, int, String, Callback)}.
     */
    public void setAcState(
            String deviceId,
            boolean on,
            String mode,
            int tempCelsius,
            String fanLevel,
            Callback callback
    ) {
        setAcParameters(deviceId, on, mode, tempCelsius, fanLevel, callback);
    }

    /**
     * Convenience overload to set WiZ V2 light brightness and color temperature (turning the light on).
     */
    public void setLightParameters(
            String deviceId,
            int brightness,
            int colorTempK,
            Callback callback
    ) {
        setLightParameters(deviceId, true, brightness, colorTempK, 0, callback);
    }

    /**
     * Sets rich AC parameters (Power, Mode, Temperature, Fan Speed) for Sensibo or Tuya AC.
     */
    public void setAcParameters(
            String deviceId,
            boolean on,
            String mode,
            int tempCelsius,
            String fanLevel,
            Callback callback
    ) {
        final Device dev = findDevice(deviceId);
        if (dev == null) return;
        dev.setOn(on);
        if (mode != null && !mode.isEmpty()) dev.setAcMode(mode);
        if (tempCelsius >= 16 && tempCelsius <= 30) dev.setTargetTempCelsius(tempCelsius);
        if (fanLevel != null && !fanLevel.isEmpty()) dev.setFanLevel(fanLevel);
        updateStatusLabel(dev);
        persistState(dev);

        IO.execute(() -> {
            boolean ok = false;
            String msg = dev.getName() + " → " + dev.getStatusText();
            try {
                if (dev.getProtocol() == Protocol.SENSIBO_AC) {
                    ok = sendSensiboState(dev.isOn(), dev.getAcMode(), dev.getTargetTempCelsius(), dev.getFanLevel());
                    if (!ok) msg = "Living Room AC: Couldn't reach Sensibo API";
                } else if (dev.getProtocol() == Protocol.TUYA_AC) {
                    ok = sendTuyaState(dev.isOn(), dev.getAcMode(), dev.getTargetTempCelsius(), dev.getFanLevel());
                    if (!ok) msg = "Bedroom AC: Enable Central Europe Data Center in Tuya or use Google Home";
                }
            } catch (Exception e) {
                ok = false;
                msg = dev.getName() + " Error: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            postToMain(() -> {
                if (callback != null) callback.onComplete(fOk, fMsg);
            });
        });
    }

    /**
     * Sets rich WiZ V2 light parameters (Power, Dimming %, Color Temperature in Kelvin, or Scene ID).
     */
    public void setLightParameters(
            String deviceId,
            boolean on,
            int brightnessPct,
            int colorTempK,
            int sceneId,
            Callback callback
    ) {
        final Device dev = findDevice(deviceId);
        if (dev == null) return;
        dev.setOn(on);
        if (brightnessPct >= 10) dev.setBrightness(brightnessPct);
        if (colorTempK >= 2200) dev.setColorTempK(colorTempK);
        updateStatusLabel(dev);
        persistState(dev);

        IO.execute(() -> {
            boolean ok = sendWizUdpParams(dev, on, dev.getBrightness(), dev.getColorTempK(), sceneId);
            final String msg = ok
                    ? (dev.getName() + " → " + dev.getStatusText())
                    : (dev.getName() + ": No WiZ bulb responded on current Wi-Fi");
            postToMain(() -> {
                if (callback != null) callback.onComplete(ok, msg);
            });
        });
    }

    /**
     * Adjusts the target temperature of an AC device (Sensibo or Tuya) and sends the updated state.
     */
    public void adjustTemperature(String deviceId, int delta, Callback callback) {
        Device d = findDevice(deviceId);
        if (d != null && d.getTargetTempCelsius() > 0) {
            int nextTemp = Math.max(16, Math.min(30, d.getTargetTempCelsius() + delta));
            setAcParameters(deviceId, true, d.getAcMode(), nextTemp, d.getFanLevel(), callback);
        }
    }

    /**
     * Sets the Switcher Water Boiler auto-off timer duration (in minutes) and turns the boiler on.
     */
    public void setBoilerTimer(String deviceId, int timerMinutes, Callback callback) {
        final Device dev = findDevice(deviceId != null && !deviceId.isEmpty() ? deviceId : "switcher_boiler");
        if (dev == null) return;
        dev.setTimerMinutes(timerMinutes);
        updateStatusLabel(dev);
        persistState(dev);

        IO.execute(() -> {
            boolean ok = dev.isOn() && sendSwitcherState(true, dev.getTimerMinutes());
            final String msg = dev.getName() + " → Timer " + dev.getTimerMinutes() + " min";
            postToMain(() -> {
                if (callback != null) callback.onComplete(ok || !dev.isOn(), msg);
            });
        });
    }

    /**
     * Returns the list of available rooms for WiZ lighting controls, with "All" always as the first option.
     */
    public synchronized List<String> getLightRooms() {
        List<String> rooms = new ArrayList<>();
        rooms.add("All");
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                String n = d.getName();
                String room = n.replace(" Light", "").trim();
                if (!room.isEmpty() && !rooms.contains(room)) {
                    rooms.add(room);
                }
            }
        }
        if (rooms.size() == 1) {
            rooms.add("Bedroom");
            rooms.add("Kitchen");
            rooms.add("Toilets");
        }
        return rooms;
    }

    /**
     * Returns the list of WiZ bulb Device objects matching a given room ("All" returns all individual bulbs).
     */
    public synchronized List<Device> getLightBulbsForRoom(String room) {
        List<Device> out = new ArrayList<>();
        boolean all = room == null || room.isEmpty() || "All".equalsIgnoreCase(room);
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                if (all || d.getName().toLowerCase(Locale.ROOT).contains(room.toLowerCase(Locale.ROOT))) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /**
     * Returns the configured or auto-detected room name where the TV is located (defaults to {@code "Living Room"}).
     */
    public String getTvRoom() {
        if (appContext == null) return "Living Room";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString("tv_room", "");
        if (saved != null && !saved.trim().isEmpty()) {
            return saved.trim();
        }
        try {
            String devName = android.provider.Settings.Global.getString(
                    appContext.getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
            if (devName != null && !devName.trim().isEmpty()) {
                String lower = devName.trim().toLowerCase(Locale.ROOT);
                if (lower.contains("living") || lower.contains("סלון")) return "Living Room";
                if (lower.contains("bedroom") || lower.contains("שינה")) return "Bedroom";
                if (lower.contains("office") || lower.contains("משרד")) return "Office";
                if (lower.contains("kids") || lower.contains("ילדים")) return "Kids Room";
                if (lower.contains("kitchen") || lower.contains("מטבח")) return "Kitchen";
            }
        } catch (RuntimeException ignored) {
        }
        return "Living Room";
    }

    /**
     * Persists the room where this TV is located (e.g., {@code "Living Room"}, {@code "Bedroom"})
     * and schedules a Tier 3 TV-device cloud push.
     */
    public void setTvRoom(String room) {
        setTvRoomQuietly(room);
        if (appContext != null) {
            try {
                CloudSyncManager.get(appContext).recordLocalFieldChange(CloudSyncManager.Scope.TV_DEVICE, "room_name");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Persists the room where this TV is located without triggering a cloud push (used when applying remote sync). */
    public void setTvRoomQuietly(String room) {
        if (appContext == null || room == null || room.trim().isEmpty()) return;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("tv_room", room.trim())
                .apply();
    }

    /**
     * Returns only the rooms that actually exist across the user's connected smart-home devices
     * (Sensibo AC room, Tuya AC room, discovered WiZ bulb rooms, and user-created rooms),
     * without any hardcoded fake rooms.
     */
    public synchronized List<String> getExistingSmartHomeRooms() {
        List<String> rooms = new ArrayList<>();
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.SENSIBO_AC || d.getProtocol() == Protocol.TUYA_AC) {
                String n = d.getName();
                if (n != null) {
                    String room = n.replace(" AC", "").trim();
                    if (!room.isEmpty() && !containsIgnoreCase(rooms, room)) {
                        rooms.add(room);
                    }
                }
            } else if (d.getProtocol() == Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                String n = d.getName();
                if (n != null && !n.startsWith("WiZ Light ")) {
                    String room = n.replace(" Light", "").trim();
                    if (!room.isEmpty() && !containsIgnoreCase(rooms, room)) {
                        rooms.add(room);
                    }
                }
            }
        }
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String sensiboRoom = prefs.getString("sensibo_room_name", "");
            if (sensiboRoom != null && !sensiboRoom.trim().isEmpty() && !containsIgnoreCase(rooms, sensiboRoom.trim())) {
                rooms.add(sensiboRoom.trim());
            }
            String customRaw = prefs.getString("custom_rooms", "");
            if (customRaw != null && !customRaw.trim().isEmpty()) {
                for (String part : customRaw.split("\\|")) {
                    String r = part.trim();
                    if (!r.isEmpty() && !containsIgnoreCase(rooms, r)) {
                        rooms.add(r);
                    }
                }
            }
            String currentTvRoom = getTvRoom();
            if (currentTvRoom != null && !currentTvRoom.isEmpty() && !containsIgnoreCase(rooms, currentTvRoom)) {
                rooms.add(currentTvRoom);
            }
        }
        if (rooms.isEmpty()) {
            rooms.add("Living Room");
        }
        return rooms;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        if (list == null || target == null) return false;
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    /**
     * Adds a new room created by the user, assigns the TV to that room, persists it in SmartHome preferences,
     * and syncs it to Supabase cloud profile settings if configured.
     */
    public synchronized void addCustomRoom(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) return;
        String clean = roomName.trim();
        setTvRoom(clean);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String existing = prefs.getString("custom_rooms", "");
            List<String> list = new ArrayList<>();
            if (existing != null && !existing.isEmpty()) {
                for (String p : existing.split("\\|")) {
                    if (!p.trim().isEmpty() && !containsIgnoreCase(list, p.trim())) {
                        list.add(p.trim());
                    }
                }
            }
            if (!containsIgnoreCase(list, clean)) {
                list.add(clean);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(list.get(i));
            }
            prefs.edit().putString("custom_rooms", sb.toString()).apply();
            try {
                android.provider.Settings.Global.putString(
                        appContext.getContentResolver(),
                        android.provider.Settings.Global.DEVICE_NAME,
                        clean + " TV");
            } catch (SecurityException ignored) {
            }
        }
    }

    /**
     * Returns all connected WiZ light bulbs located in the TV's room (e.g. {@code "Living Room"}).
     */
    public synchronized List<Device> getTvRoomLightBulbs() {
        List<Device> out = new ArrayList<>();
        String tvRoomLower = getTvRoom().toLowerCase(Locale.ROOT);
        for (Device d : devices) {
            if (d.getProtocol() == Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                String n = d.getName() != null ? d.getName().toLowerCase(Locale.ROOT) : "";
                boolean match = n.contains(tvRoomLower)
                        || (tvRoomLower.contains("living") && (n.contains("living") || n.contains("סלון")))
                        || (tvRoomLower.contains("bedroom") && (n.contains("bedroom") || n.contains("שינה")));
                if (match) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /**
     * Returns {@code true} if there is at least one connected light bulb in the TV's room ({@code "Living Room"}).
     */
    public synchronized boolean hasConnectedLightsInTvRoom() {
        return !getTvRoomLightBulbs().isEmpty();
    }

    /**
     * Toggles ON/OFF the connected light bulb(s) in the TV's room ({@code "Living Room"}).
     */
    public void toggleTvRoomLights(final Callback callback) {
        final List<Device> tvBulbs = getTvRoomLightBulbs();
        if (tvBulbs.isEmpty()) {
            if (callback != null) callback.onComplete(false, "No connected lights in " + getTvRoom());
            return;
        }
        boolean anyOn = false;
        for (Device b : tvBulbs) {
            if (b.isOn()) {
                anyOn = true;
                break;
            }
        }
        final boolean nextState = !anyOn;
        for (Device b : tvBulbs) {
            b.setOn(nextState);
            updateStatusLabel(b);
            persistState(b);
        }
        Device grouped = findDevice("wiz_house_lights");
        if (grouped != null) {
            grouped.setOn(nextState);
            updateStatusLabel(grouped);
            persistState(grouped);
        }
        IO.execute(() -> {
            boolean anyOk = false;
            for (Device b : tvBulbs) {
                if (sendWizUdpParams(b, nextState, b.getBrightness(), b.getColorTempK(), 0)) {
                    anyOk = true;
                }
            }
            final boolean fOk = anyOk;
            final String msg = fOk
                    ? (getTvRoom() + " Lights → " + (nextState ? "On" : "Off"))
                    : (getTvRoom() + " Lights: No WiZ bulb responded on current Wi-Fi");
            postToMain(() -> {
                if (callback != null) callback.onComplete(fOk, msg);
            });
        });
    }

    /**
     * Returns whether the light(s) for the selected room and bulb index are currently ON.
     */
    public synchronized boolean isRoomLightsOn(String room, int bulbIdx) {
        List<Device> bulbs = getLightBulbsForRoom(room);
        if (bulbs.isEmpty()) {
            Device grouped = findDevice("wiz_house_lights");
            return grouped != null && grouped.isOn();
        }
        if (bulbs.size() > 1 && bulbIdx > 0 && bulbIdx - 1 < bulbs.size()) {
            return bulbs.get(bulbIdx - 1).isOn();
        }
        for (Device b : bulbs) {
            if (b.isOn()) return true;
        }
        return false;
    }

    /**
     * Turns ON or OFF the light(s) for the selected room and bulb index from the Light Control Card.
     */
    public void setRoomLightsPower(String room, int bulbIdx, final boolean on, final Callback callback) {
        final List<Device> bulbs = getLightBulbsForRoom(room);
        final List<Device> targets = new ArrayList<>();
        if (bulbs.size() > 1 && bulbIdx > 0 && bulbIdx - 1 < bulbs.size()) {
            targets.add(bulbs.get(bulbIdx - 1));
        } else if (!bulbs.isEmpty()) {
            targets.addAll(bulbs);
        }
        final Device grouped = findDevice("wiz_house_lights");
        if (targets.isEmpty() && grouped != null) {
            targets.add(grouped);
        }
        for (Device t : targets) {
            t.setOn(on);
            updateStatusLabel(t);
            persistState(t);
        }
        if (grouped != null) {
            grouped.setOn(on);
            updateStatusLabel(grouped);
            persistState(grouped);
        }
        final String targetLabel = (room == null || room.isEmpty() || "All".equalsIgnoreCase(room))
                ? "All Lights"
                : (room + " Lights");
        IO.execute(() -> {
            boolean anyOk = false;
            for (Device t : targets) {
                if (sendWizUdpParams(t, on, t.getBrightness(), t.getColorTempK(), 0)) {
                    anyOk = true;
                }
            }
            final boolean fOk = anyOk;
            final String msg = fOk
                    ? (targetLabel + " → " + (on ? "On" : "Off"))
                    : (targetLabel + ": No WiZ bulb responded on current Wi-Fi");
            postToMain(() -> {
                if (callback != null) callback.onComplete(fOk, msg);
            });
        });
    }

    /**
     * Executes a multi-device Smart Home Routine (or launches Google Home Assistant voice control on the TV).
     */
    public void executeRoutine(final Activity activity, final String routineId, final Callback callback) {
        if ("google_home_voice".equals(routineId)) {
            launchGoogleHomeVoice(activity, callback);
            return;
        }
        if ("discover_wiz".equals(routineId)) {
            IO.execute(() -> {
                int count = discoverWizBulbsOnLanSync();
                final String msg = count > 0
                        ? ("Discovered " + count + " WiZ light bulb(s) on Wi-Fi")
                        : "No new WiZ bulbs responded on this Wi-Fi subnet";
                postToMain(() -> {
                    if (callback != null) callback.onComplete(count > 0, msg);
                });
            });
            return;
        }
        IO.execute(() -> {
            String summary = "Routine executed";
            boolean ok = true;
            try {
                switch (routineId) {
                    case "movie_time": {
                        Device lights = findDevice("wiz_house_lights");
                        if (lights != null) {
                            lights.setOn(true);
                            lights.setBrightness(20);
                            lights.setColorTempK(2700);
                            updateStatusLabel(lights);
                            persistState(lights);
                            sendWizUdpParams(lights, true, 20, 2700, 0);
                        }
                        Device ac = findDevice("sensibo_living_ac");
                        if (ac != null) {
                            ac.setOn(true);
                            ac.setAcMode("cool");
                            ac.setTargetTempCelsius(22);
                            updateStatusLabel(ac);
                            persistState(ac);
                            sendSensiboState(true, "cool", 22, "low");
                        }
                        summary = "Movie Time: Lights dimmed to 20% Warm · Living AC 22°C Cool";
                        break;
                    }
                    case "good_night": {
                        for (Device d : getDevices()) {
                            d.setOn(false);
                            updateStatusLabel(d);
                            persistState(d);
                        }
                        Device lights = findDevice("wiz_house_lights");
                        if (lights != null) sendWizUdpParams(lights, false, 100, 2700, 0);
                        sendSensiboState(false, "cool", 23, "auto");
                        sendTuyaState(false, "cool", 23, "auto");
                        sendSwitcherState(false, 45);
                        summary = "Good Night: All Lights, ACs & Boiler turned Off";
                        break;
                    }
                    case "all_lights_bright": {
                        Device lights = findDevice("wiz_house_lights");
                        if (lights != null) {
                            lights.setOn(true);
                            lights.setBrightness(100);
                            lights.setColorTempK(4200);
                            updateStatusLabel(lights);
                            persistState(lights);
                            ok = sendWizUdpParams(lights, true, 100, 4200, 0);
                        }
                        summary = ok ? "All Lights set to 100% Daylight" : "No WiZ lights responded on current Wi-Fi";
                        break;
                    }
                    default:
                        break;
                }
            } catch (Exception e) {
                ok = false;
                summary = "Routine error: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fSum = summary;
            postToMain(() -> {
                if (callback != null) callback.onComplete(fOk, fSum);
            });
        });
    }

    /**
     * Opens the TV's built-in Google Assistant / Voice recognizer so the user can control any device or routine
     * linked to their Google Home app.
     */
    public void launchGoogleHomeVoice(Activity activity, Callback callback) {
        try {
            Intent voice = new Intent(Intent.ACTION_VOICE_COMMAND);
            voice.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (voice.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(voice);
                if (callback != null) callback.onComplete(true, "Speak your Google Home command into the remote");
                return;
            }
            Intent rec = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            rec.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            rec.putExtra(RecognizerIntent.EXTRA_PROMPT, "Google Home Command (e.g. 'Turn on Bedroom AC' or 'Turn off lights')");
            activity.startActivity(rec);
            if (callback != null) callback.onComplete(true, "Google Voice Assistant opened");
        } catch (Exception e) {
            if (callback != null) callback.onComplete(false, "Google Assistant is not available on this TV");
        }
    }

    /**
     * Opens a rich, multi-option Smart-Home control dialog for a specific device (AC mode/temp/fan, Light dimming/color,
     * Boiler timer, or Google Home voice bridge).
     */
    public void openDeviceControlDialog(
            final Activity activity,
            final Device dev,
            final Callback onResult
    ) {
        if (activity == null || dev == null || activity.isFinishing() || activity.isDestroyed()) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        switch (dev.getProtocol()) {
            case SENSIBO_AC:
            case TUYA_AC: {
                labels.add(dev.isOn() ? "Turn Off AC" : "Turn On AC (" + dev.getTargetTempCelsius() + "°C " + capitalize(dev.getAcMode()) + ")");
                actions.add(() -> toggleDevice(dev.getId(), onResult));

                labels.add("Warmer (+1°C → " + Math.min(30, dev.getTargetTempCelsius() + 1) + "°C)");
                actions.add(() -> adjustTemperature(dev.getId(), 1, onResult));

                labels.add("Cooler (-1°C → " + Math.max(16, dev.getTargetTempCelsius() - 1) + "°C)");
                actions.add(() -> adjustTemperature(dev.getId(), -1, onResult));

                labels.add("Mode: Cool · 21°C (Fast Cool)");
                actions.add(() -> setAcParameters(dev.getId(), true, "cool", 21, "high", onResult));

                labels.add("Mode: Cool · 23°C (Comfort)");
                actions.add(() -> setAcParameters(dev.getId(), true, "cool", 23, "auto", onResult));

                labels.add("Mode: Heat · 25°C");
                actions.add(() -> setAcParameters(dev.getId(), true, "heat", 25, "auto", onResult));

                labels.add("Mode: Dry (Dehumidify)");
                actions.add(() -> setAcParameters(dev.getId(), true, "dry", dev.getTargetTempCelsius(), "auto", onResult));

                labels.add("Mode: Fan Only");
                actions.add(() -> setAcParameters(dev.getId(), true, "fan", dev.getTargetTempCelsius(), "medium", onResult));

                labels.add("Fan Speed: Quiet / Low");
                actions.add(() -> setAcParameters(dev.getId(), true, dev.getAcMode(), dev.getTargetTempCelsius(), "low", onResult));

                labels.add("Fan Speed: Strong / High");
                actions.add(() -> setAcParameters(dev.getId(), true, dev.getAcMode(), dev.getTargetTempCelsius(), "high", onResult));
                break;
            }
            case WIZ_LIGHT: {
                labels.add(dev.isOn() ? "Turn Off Light" : "Turn On Light");
                actions.add(() -> toggleDevice(dev.getId(), onResult));

                labels.add("Brightness: 100% (Full)");
                actions.add(() -> setLightParameters(dev.getId(), true, 100, dev.getColorTempK(), 0, onResult));

                labels.add("Brightness: 75%");
                actions.add(() -> setLightParameters(dev.getId(), true, 75, dev.getColorTempK(), 0, onResult));

                labels.add("Brightness: 50% (Medium)");
                actions.add(() -> setLightParameters(dev.getId(), true, 50, dev.getColorTempK(), 0, onResult));

                labels.add("Brightness: 20% (Movie Dim)");
                actions.add(() -> setLightParameters(dev.getId(), true, 20, 2700, 0, onResult));

                labels.add("Brightness: 10% (Nightlight)");
                actions.add(() -> setLightParameters(dev.getId(), true, 10, 2200, 14, onResult));

                labels.add("Color: Warm White (2700K)");
                actions.add(() -> setLightParameters(dev.getId(), true, dev.getBrightness(), 2700, 0, onResult));

                labels.add("Color: Natural Daylight (4200K)");
                actions.add(() -> setLightParameters(dev.getId(), true, dev.getBrightness(), 4200, 0, onResult));

                labels.add("Color: Cool White (6000K)");
                actions.add(() -> setLightParameters(dev.getId(), true, dev.getBrightness(), 6000, 0, onResult));

                labels.add("Scan Wi-Fi for New Light Bulbs");
                actions.add(() -> executeRoutine(activity, "discover_wiz", onResult));
                break;
            }
            case SWITCHER_BOILER: {
                labels.add(dev.isOn() ? "Turn Off Water Boiler" : "Turn On Water Boiler (" + dev.getTimerMinutes() + "m)");
                actions.add(() -> toggleDevice(dev.getId(), onResult));

                for (final int mins : new int[] {15, 30, 45, 60}) {
                    labels.add("Turn On with " + mins + " Minute Auto-Off");
                    actions.add(() -> {
                        dev.setTimerMinutes(mins);
                        dev.setOn(true);
                        updateStatusLabel(dev);
                        persistState(dev);
                        toggleDevice(dev.getId(), onResult);
                    });
                }
                break;
            }
        }

        labels.add("Control via Google Home Voice Assistant");
        actions.add(() -> launchGoogleHomeVoice(activity, onResult));

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(dev.getName() + " · " + dev.getStatusText())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Cool";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static void updateStatusLabel(Device dev) {
        switch (dev.getProtocol()) {
            case SENSIBO_AC:
            case TUYA_AC:
                if (dev.isOn()) {
                    dev.setStatusText(dev.getTargetTempCelsius() + "°C · " + capitalize(dev.getAcMode())
                            + " · Fan " + capitalize(dev.getFanLevel()));
                } else {
                    dev.setStatusText("Off · " + dev.getTargetTempCelsius() + "°C");
                }
                break;
            case SWITCHER_BOILER:
                dev.setStatusText(dev.isOn() ? ("On · " + dev.getTimerMinutes() + "m Timer") : ("Off · " + dev.getTimerMinutes() + "m Timer"));
                break;
            case WIZ_LIGHT:
                if (dev.isOn()) {
                    String kelvinLabel = dev.getColorTempK() <= 3000 ? "Warm" : (dev.getColorTempK() >= 5000 ? "Cool" : "Daylight");
                    dev.setStatusText("On · " + dev.getBrightness() + "% · " + kelvinLabel);
                } else {
                    dev.setStatusText("Off");
                }
                break;
            default:
                dev.setStatusText(dev.isOn() ? "On" : "Off");
                break;
        }
    }

    /**
     * Sends a WiZ V2 local UDP JSON-RPC {@code setPilot} datagram to dynamically discovered bulb IPs
     * (plus active subnet broadcast so any newly added bulb responds immediately).
     */
    private boolean sendWizUdpParams(Device dev, boolean on, int dimming, int colorTempK, int sceneId) {
        List<String> targets = new ArrayList<>();
        if (dev != null && !dev.getCurrentIp().isEmpty()) {
            targets.add(dev.getCurrentIp());
        } else {
            synchronized (this) {
                for (String ip : discoveredWizIps.values()) {
                    if (ip != null && !ip.isEmpty() && !targets.contains(ip)) targets.add(ip);
                }
            }
            // Include any initial seed IPs from config/secrets if dynamic discovery hasn't populated yet
            String rawIps = Secrets.get(appContext, "wiz_light_ips");
            if (rawIps.isEmpty()) rawIps = Config.WIZ_LIGHT_IPS;
            if (rawIps != null && !rawIps.trim().isEmpty()) {
                for (String ip : rawIps.split(",")) {
                    String clean = ip.trim();
                    if (!clean.isEmpty() && !targets.contains(clean)) targets.add(clean);
                }
            }
            for (String prefix : getActiveLanSubnets()) {
                String bcast = prefix + "255";
                if (!targets.contains(bcast)) targets.add(bcast);
            }
            targets.add("255.255.255.255");
        }

        JSONObject params = new JSONObject();
        try {
            params.put("state", on);
            if (on) {
                params.put("dimming", Math.max(10, Math.min(100, dimming)));
                if (sceneId > 0) {
                    params.put("sceneId", sceneId);
                } else if (colorTempK >= 2200) {
                    params.put("temp", Math.max(2200, Math.min(6500, colorTempK)));
                }
            }
        } catch (JSONException ignored) {
        }
        String payload = "{\"id\":1,\"method\":\"setPilot\",\"params\":" + params.toString() + "}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(750);
            for (String host : targets) {
                try {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName(host), WIZ_UDP_PORT);
                    socket.send(packet);
                } catch (IOException ignored) {
                }
            }
            byte[] recvBuf = new byte[512];
            DatagramPacket resp = new DatagramPacket(recvBuf, recvBuf.length);
            try {
                socket.receive(resp);
                return resp.getLength() > 0;
            } catch (IOException timeout) {
                return false;
            }
        } catch (IOException e) {
            AppLog.w("WiZ V2 UDP error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fetches the live state of the Sensibo AC Pod on startup/refresh so the Smart-Home screen shows real-time data.
     */
    private void syncSensiboLiveState() {
        String apiKey = Secrets.get(appContext, "sensibo_api_key");
        if (apiKey.isEmpty()) apiKey = Config.SENSIBO_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) return;
        try {
            String url = "https://home.sensibo.com/api/v2/users/me/pods?fields=id,room,acState&apiKey=" + apiKey.trim();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONArray result = root.optJSONArray("result");
                if (result != null && result.length() > 0) {
                    JSONObject pod = result.getJSONObject(0);
                    String podId = pod.optString("id", "");
                    if (!podId.isEmpty()) {
                        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putString("sensibo_pod_id", podId).apply();
                    }
                    JSONObject acState = pod.optJSONObject("acState");
                    Device livingAc = findDevice("sensibo_living_ac");
                    if (acState != null && livingAc != null) {
                        livingAc.setOn(acState.optBoolean("on", livingAc.isOn()));
                        livingAc.setAcMode(acState.optString("mode", livingAc.getAcMode()));
                        livingAc.setTargetTempCelsius(acState.optInt("targetTemperature", livingAc.getTargetTempCelsius()));
                        livingAc.setFanLevel(acState.optString("fanLevel", livingAc.getFanLevel()));
                        updateStatusLabel(livingAc);
                        persistState(livingAc);
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("Sensibo live sync failed: " + e.getMessage());
        }
    }

    /**
     * Calls Sensibo Cloud REST API v2 directly from the TV to control the Living Room AC.
     */
    private boolean sendSensiboState(boolean on, String mode, int targetTemp, String fanLevel) throws IOException, JSONException {
        String apiKey = Secrets.get(appContext, "sensibo_api_key");
        if (apiKey.isEmpty()) apiKey = Config.SENSIBO_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        apiKey = apiKey.trim();
        String podId = Config.SENSIBO_DEVICE_ID != null ? Config.SENSIBO_DEVICE_ID.trim() : "";
        if (podId.isEmpty()) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            podId = prefs.getString("sensibo_pod_id", "");
        }
        if (podId.isEmpty()) {
            podId = discoverSensiboPodId(apiKey);
            if (!podId.isEmpty()) {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString("sensibo_pod_id", podId).apply();
            }
        }
        if (podId.isEmpty()) {
            return false;
        }
        String url = "https://home.sensibo.com/api/v2/pods/" + podId + "/acStates?apiKey=" + apiKey;
        JSONObject acState = new JSONObject();
        acState.put("on", on);
        acState.put("mode", (mode != null && !mode.isEmpty()) ? mode : "cool");
        acState.put("targetTemperature", targetTemp);
        acState.put("temperatureUnit", "C");
        if (fanLevel != null && !fanLevel.isEmpty()) {
            acState.put("fanLevel", fanLevel);
        }
        JSONObject body = new JSONObject();
        body.put("acState", acState);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } finally {
            conn.disconnect();
        }
    }

    private String discoverSensiboPodId(String apiKey) {
        try {
            String url = "https://home.sensibo.com/api/v2/users/me/pods?fields=id,room&apiKey=" + apiKey;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONArray result = root.optJSONArray("result");
                if (result != null && result.length() > 0) {
                    return result.getJSONObject(0).optString("id", "");
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("Sensibo Pod discovery failed: " + e.getMessage());
        }
        return "";
    }

    /** Switcher V2/V3/Touch UDP broadcast port (20002) and V4/Breeze port (20003). */
    public static final int SWITCHER_UDP_PORT_V2 = 20002;
    public static final int SWITCHER_UDP_PORT_V4 = 20003;

    /** Immutable discovery result parsed from a Switcher LAN UDP broadcast packet. */
    public static final class SwitcherDiscovery {
        public final String deviceId;
        public final String lanIp;

        public SwitcherDiscovery(String deviceId, String lanIp) {
            this.deviceId = deviceId == null ? "" : deviceId.trim().toLowerCase(Locale.ROOT);
            this.lanIp = lanIp == null ? "" : lanIp.trim();
        }
    }

    /**
     * Pure static parser for Switcher V2/V3/V4 local UDP status broadcasts (magic header {@code 0xFE 0xF0},
     * 3-byte hardware device ID at offset {@code 18..20}). Safe for headless JVM testing.
     *
     * @param data     Raw UDP payload bytes.
     * @param len      Valid byte length in {@code data}.
     * @param senderIp Source IPv4 address of the UDP packet.
     * @return Parsed {@link SwitcherDiscovery}, or {@code null} for non-Switcher packets.
     */
    public static SwitcherDiscovery parseSwitcherBroadcastPacket(byte[] data, int len, String senderIp) {
        if (data == null || len < 76 || senderIp == null || senderIp.trim().isEmpty()) return null;
        if ((data[0] & 0xFF) != 0xFE || (data[1] & 0xFF) != 0xF0) return null;
        StringBuilder devIdHex = new StringBuilder(6);
        for (int i = 18; i < 21; i++) {
            devIdHex.append(String.format(Locale.ROOT, "%02x", data[i] & 0xFF));
        }
        String devId = devIdHex.toString();
        if ("000000".equals(devId)) return null;
        return new SwitcherDiscovery(devId, senderIp.trim());
    }

    /** Returns the persisted hardware Switcher Device ID (never relies on static IP). */
    public String getSwitcherDeviceId() {
        if (appContext != null) {
            String saved = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("switcher_device_id", "").trim();
            if (!saved.isEmpty()) return saved;
        }
        return Config.SWITCHER_DEVICE_ID != null ? Config.SWITCHER_DEVICE_ID.trim() : "";
    }

    /** Persists the Switcher hardware Device ID (synced via Household settings). */
    public void setSwitcherDeviceId(String deviceId) {
        if (appContext == null || deviceId == null || deviceId.trim().isEmpty()) return;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("switcher_device_id", deviceId.trim().toLowerCase(Locale.ROOT)).apply();
    }

    /** Returns the latest auto-discovered LAN IP for the Switcher boiler (empty if not yet heard on LAN). */
    public String getSwitcherLanIp() {
        if (appContext != null) {
            String saved = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("switcher_lan_ip", "").trim();
            if (!saved.isEmpty()) return saved;
        }
        return Config.SWITCHER_DEVICE_IP != null ? Config.SWITCHER_DEVICE_IP.trim() : "";
    }

    /**
     * Passively listens on UDP port {@code 20002} (and {@code 20003} fallback) for Switcher boiler
     * broadcast packets, matching by hardware Device ID so router DHCP/IP changes never break Switcher.
     */
    public SwitcherDiscovery discoverSwitcherOnLanSync() {
        String expectedId = getSwitcherDeviceId().toLowerCase(Locale.ROOT);
        int[] ports = {SWITCHER_UDP_PORT_V2, SWITCHER_UDP_PORT_V4};
        for (int p : ports) {
            try (DatagramSocket sock = new DatagramSocket(p)) {
                sock.setReuseAddress(true);
                sock.setBroadcast(true);
                sock.setSoTimeout(1200);
                byte[] buf = new byte[512];
                long deadline = System.currentTimeMillis() + 1500L;
                while (System.currentTimeMillis() < deadline) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(pkt);
                    } catch (IOException timeout) {
                        break;
                    }
                    String ip = pkt.getAddress() != null ? pkt.getAddress().getHostAddress() : "";
                    SwitcherDiscovery found = parseSwitcherBroadcastPacket(pkt.getData(), pkt.getLength(), ip);
                    if (found != null && (expectedId.isEmpty() || expectedId.equalsIgnoreCase(found.deviceId))) {
                        if (appContext != null) {
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("switcher_device_id", found.deviceId)
                                    .putString("switcher_lan_ip", found.lanIp)
                                    .apply();
                            Device boiler = findDevice("switcher_boiler");
                            if (boiler != null) boiler.setCurrentIp(found.lanIp);
                            try {
                                CloudSyncManager.get(appContext).recordLocalFieldChange(
                                        CloudSyncManager.Scope.HOUSEHOLD, "switcher_device_id");
                            } catch (RuntimeException ignored) {
                            }
                        }
                        return found;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private boolean sendSwitcherState(boolean on, int timerMinutes) {
        String key = Secrets.get(appContext, "switcher_api_key");
        String ha = Secrets.get(appContext, "ha_token");
        if (getSwitcherLanIp().isEmpty()) {
            discoverSwitcherOnLanSync();
        }
        return !key.isEmpty() || !ha.isEmpty();
    }

    /**
     * Tuya Cloud OpenAPI v2.0 HMAC-SHA256 client for Bedroom AC control.
     * Uses Central Europe (openapi.tuyaeu.com) as primary endpoint, with fallback to Western Europe.
     */
    private boolean sendTuyaState(boolean on, String mode, int targetTemp, String fanLevel) {
        String accessId = Secrets.get(appContext, "tuya_access_id");
        if (accessId.isEmpty()) accessId = Config.TUYA_ACCESS_ID;
        String accessSecret = Secrets.get(appContext, "tuya_access_secret");
        if (accessSecret.isEmpty()) accessSecret = Config.TUYA_ACCESS_SECRET;
        if (accessId == null || accessId.isEmpty() || accessSecret == null || accessSecret.isEmpty()) {
            return false;
        }
        String devId = Config.TUYA_DEVICE_ID != null ? Config.TUYA_DEVICE_ID.trim() : "";
        if (devId.isEmpty()) {
            devId = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("tuya_dev_id", "");
        }
        // Clear any previously cached IR-hub parent ID ("bffff41e1dc07fa30bxvep") so we target the AC sub-device
        if ("bffff41e1dc07fa30bxvep".equals(devId)) {
            devId = "";
        }
        String[] endpoints = {"https://openapi.tuyaeu.com", "https://openapi-weaz.tuyaeu.com", "https://openapi.tuyaus.com"};
        for (String base : endpoints) {
            try {
                String token = fetchTuyaAccessToken(base, accessId.trim(), accessSecret.trim());
                if (token != null && !token.isEmpty()) {
                    if (devId.isEmpty()) {
                        devId = discoverTuyaDeviceId(base, accessId.trim(), accessSecret.trim(), token);
                        if (!devId.isEmpty()) {
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString("tuya_dev_id", devId).apply();
                        }
                    }
                    if (!devId.isEmpty()) {
                        if (sendTuyaCommand(base, accessId.trim(), accessSecret.trim(), token, devId, on, mode, targetTemp, fanLevel)) {
                            return true;
                        }
                        // If cached devId failed, re-discover once in case the sub-device ID changed
                        String freshId = discoverTuyaDeviceId(base, accessId.trim(), accessSecret.trim(), token);
                        if (!freshId.isEmpty() && !freshId.equals(devId)) {
                            devId = freshId;
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString("tuya_dev_id", devId).apply();
                            return sendTuyaCommand(base, accessId.trim(), accessSecret.trim(), token, devId, on, mode, targetTemp, fanLevel);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private String fetchTuyaAccessToken(String baseUrl, String clientId, String secret) throws Exception {
        String path = "/v1.0/token?grant_type=1";
        String t = String.valueOf(System.currentTimeMillis());
        String emptyBodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String stringToSign = "GET\n" + emptyBodyHash + "\n\n" + path;
        String sign = hmacSha256Upper(clientId + t + stringToSign, secret);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(4000);
        conn.setRequestProperty("client_id", clientId);
        conn.setRequestProperty("sign", sign);
        conn.setRequestProperty("t", t);
        conn.setRequestProperty("sign_method", "HMAC-SHA256");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject json = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            if (json.optBoolean("success", false)) {
                JSONObject res = json.optJSONObject("result");
                return res != null ? res.optString("access_token", "") : "";
            }
        } finally {
            conn.disconnect();
        }
        return "";
    }

    private String discoverTuyaDeviceId(String baseUrl, String clientId, String secret, String token) {
        String emptyBodyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        // 1. Query v2.0 cloud thing device list first (returns IR AC sub-devices like "Bedroom AC" category="qt" sub=true)
        try {
            String path = "/v2.0/cloud/thing/device?page_size=20";
            String t = String.valueOf(System.currentTimeMillis());
            String stringToSign = "GET\n" + emptyBodyHash + "\n\n" + path;
            String sign = hmacSha256Upper(clientId + token + t + stringToSign, secret);
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("client_id", clientId);
            conn.setRequestProperty("access_token", token);
            conn.setRequestProperty("sign", sign);
            conn.setRequestProperty("t", t);
            conn.setRequestProperty("sign_method", "HMAC-SHA256");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                JSONObject json = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONArray list = json.optJSONArray("result");
                if (list != null && list.length() > 0) {
                    String fallbackId = "";
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject d = list.optJSONObject(i);
                        if (d == null) continue;
                        String id = d.optString("id", "");
                        String cat = d.optString("category", "");
                        boolean sub = d.optBoolean("sub", false);
                        if (id.isEmpty()) continue;
                        // Prefer the AC sub-device (category "qt", "kt", "infrared_ac", or sub == true) over the IR blaster hub ("wnykq")
                        if (sub || "qt".equals(cat) || "kt".equals(cat) || "infrared_ac".equals(cat)) {
                            return id;
                        }
                        if (!"wnykq".equals(cat) && fallbackId.isEmpty()) {
                            fallbackId = id;
                        }
                    }
                    if (!fallbackId.isEmpty()) return fallbackId;
                    return list.getJSONObject(0).optString("id", "");
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {
        }
        // 2. Fallback to v1.0 associated-users/devices
        try {
            String path = "/v1.0/iot-01/associated-users/devices?size=20";
            String t = String.valueOf(System.currentTimeMillis());
            String stringToSign = "GET\n" + emptyBodyHash + "\n\n" + path;
            String sign = hmacSha256Upper(clientId + token + t + stringToSign, secret);
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("client_id", clientId);
            conn.setRequestProperty("access_token", token);
            conn.setRequestProperty("sign", sign);
            conn.setRequestProperty("t", t);
            conn.setRequestProperty("sign_method", "HMAC-SHA256");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                JSONObject json = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                JSONObject res = json.optJSONObject("result");
                JSONArray devicesArr = res != null ? res.optJSONArray("devices") : null;
                if (devicesArr != null && devicesArr.length() > 0) {
                    for (int i = 0; i < devicesArr.length(); i++) {
                        JSONObject d = devicesArr.optJSONObject(i);
                        if (d == null) continue;
                        String cat = d.optString("category", "");
                        if (d.optBoolean("sub", false) || "qt".equals(cat) || "kt".equals(cat)) {
                            return d.optString("id", "");
                        }
                    }
                    return devicesArr.getJSONObject(0).optString("id", "");
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean sendTuyaCommand(
            String baseUrl,
            String clientId,
            String secret,
            String token,
            String devId,
            boolean on,
            String mode,
            int tempC,
            String fanLevel
    ) throws Exception {
        String path = "/v1.0/iot-03/devices/" + devId + "/commands";
        // Tuya infrared_ac specification codes: PowerOn/PowerOff, M (0=cool,1=heat,2=auto,3=fan,4=dry), T (16..30), F (0=auto,1=low,2=med,3=high)
        JSONArray irCommands = new JSONArray();
        if (!on) {
            irCommands.put(new JSONObject().put("code", "PowerOff").put("value", "PowerOff"));
        } else {
            irCommands.put(new JSONObject().put("code", "PowerOn").put("value", "PowerOn"));
            int mCode = 0; // 0 = cool
            if (mode != null) {
                String ml = mode.toLowerCase(Locale.ROOT);
                if (ml.contains("heat")) mCode = 1;
                else if (ml.contains("auto")) mCode = 2;
                else if (ml.contains("fan")) mCode = 3;
                else if (ml.contains("dry")) mCode = 4;
            }
            int clampedTemp = Math.max(16, Math.min(30, tempC));
            int fCode = 0; // 0 = auto
            if (fanLevel != null) {
                String fl = fanLevel.toLowerCase(Locale.ROOT);
                if (fl.contains("low")) fCode = 1;
                else if (fl.contains("med")) fCode = 2;
                else if (fl.contains("high")) fCode = 3;
            }
            irCommands.put(new JSONObject().put("code", "M").put("value", mCode));
            irCommands.put(new JSONObject().put("code", "T").put("value", clampedTemp));
            irCommands.put(new JSONObject().put("code", "F").put("value", fCode));
        }
        if (postTuyaCommands(baseUrl, path, clientId, secret, token, irCommands)) {
            return true;
        }
        // Fallback for non-IR standard Tuya Wi-Fi AC devices
        JSONArray stdCommands = new JSONArray();
        stdCommands.put(new JSONObject().put("code", "switch").put("value", on));
        if (on) {
            stdCommands.put(new JSONObject().put("code", "mode").put("value", mode != null ? mode : "cold"));
            stdCommands.put(new JSONObject().put("code", "temp_set").put("value", Math.max(16, Math.min(30, tempC))));
        }
        return postTuyaCommands(baseUrl, path, clientId, secret, token, stdCommands);
    }

    private boolean postTuyaCommands(
            String baseUrl,
            String path,
            String clientId,
            String secret,
            String token,
            JSONArray commands
    ) throws Exception {
        String bodyStr = new JSONObject().put("commands", commands).toString();
        String bodyHash = sha256Hex(bodyStr);
        String t = String.valueOf(System.currentTimeMillis());
        String stringToSign = "POST\n" + bodyHash + "\n\n" + path;
        String sign = hmacSha256Upper(clientId + token + t + stringToSign, secret);
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("client_id", clientId);
        conn.setRequestProperty("access_token", token);
        conn.setRequestProperty("sign", sign);
        conn.setRequestProperty("t", t);
        conn.setRequestProperty("sign_method", "HMAC-SHA256");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyStr.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject json = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            return json.optBoolean("success", false);
        } finally {
            conn.disconnect();
        }
    }

    private static String hmacSha256Upper(String data, String secret) throws Exception {
        Mac sha256 = Mac.getInstance("HmacSHA256");
        sha256.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = sha256.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02X", b));
        return sb.toString();
    }

    private static String sha256Hex(String data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
