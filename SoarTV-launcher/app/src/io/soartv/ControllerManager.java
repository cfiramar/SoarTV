package io.soartv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encapsulates gaming controller color tagging, naming, persistence, palette management,
 * contrast calculation, and connection/battery state reporting for SoarTV.
 *
 * <p>Why: Gamers connect multiple gamepads (Bluetooth, USB, 2.4GHz dongles) to their Android TV.
 * Color tagging and custom naming allow physical gamepads to be distinguished at a glance on the TV screen
 * and companion mobile app, while real-time battery and charging indicators alert the player before a
 * controller dies mid-game.</p>
 */
public final class ControllerManager {

    private ControllerManager() {}

    private static final String PREFS_NAME = "controller_tags";
    private static final String KEY_PREFIX_NAME = "ctrl_name_";
    private static final String KEY_PREFIX_COLOR = "ctrl_color_";
    private static final String KEY_PREFIX_TAGGED = "ctrl_tagged_";
    private static final String KEY_PREFIX_HWNAME = "ctrl_hwname_";
    private static final String KEY_PREFIX_LAST_XHCI = "ctrl_last_xhci_";

    /** Sensible neutral slate-grey appearance for untagged controllers until user explicitly tags them. */
    public static final int COLOR_NEUTRAL_FALLBACK = 0xFF64748B; // #64748B

    /** Emerald-green status pill colors shared between Connected Controllers and Online PC on the Gaming screen. */
    public static final int CONNECTED_PILL_BG_LIGHT = 0x2416A34A;
    public static final int CONNECTED_PILL_BG_DARK = 0x3822C55E;
    public static final int CONNECTED_PILL_BORDER_LIGHT = 0x5816A34A;
    public static final int CONNECTED_PILL_BORDER_DARK = 0x724ADE80;
    public static final int CONNECTED_PILL_TEXT_LIGHT = 0xFF15803D;
    public static final int CONNECTED_PILL_TEXT_DARK = 0xFFDCFCE7;

    /** Preset sample colors available in color picker / presets. */
    public static final int COLOR_LIGHT_BLUE = 0xFFC2DDFB; // #c2ddfb
    public static final int COLOR_PINK       = 0xFFF9C1D4; // #f9c1d4
    public static final int COLOR_GREEN      = 0xFFCEF0BE; // #cef0be
    public static final int COLOR_YELLOW     = 0xFFFEE776; // #fee776

    /**
     * Immutable visual swatch option ordered along the rainbow spectrum (never exposing hex strings to the user).
     */
    public static final class ColorOption {
        public final String name;
        public final int argb;

        public ColorOption(String name, int argb) {
            this.name = name != null ? name : "";
            this.argb = argb;
        }
    }

    /**
     * Rainbow-ordered visual color swatches for controller setup and editing (Red -> Orange -> Yellow ->
     * Green -> Sky/Light Blue -> Blue -> Indigo -> Purple -> Pink -> White -> Gray -> Black).
     */
    public static final List<ColorOption> RAINBOW_COLORS = Collections.unmodifiableList(Arrays.asList(
            new ColorOption("Red",           0xFFEF4444),
            new ColorOption("Orange",        0xFFF97316),
            new ColorOption("Gold",          0xFFEAB308),
            new ColorOption("Soft Yellow",   COLOR_YELLOW),
            new ColorOption("Lime Green",    0xFF84CC16),
            new ColorOption("Soft Green",    COLOR_GREEN),
            new ColorOption("Emerald Green", 0xFF10B981),
            new ColorOption("Sky Blue",      0xFF38BDF8),
            new ColorOption("Light Blue",    COLOR_LIGHT_BLUE),
            new ColorOption("Blue",          0xFF3B82F6),
            new ColorOption("Indigo",        0xFF6366F1),
            new ColorOption("Purple",        0xFFA855F7),
            new ColorOption("Pink",          COLOR_PINK),
            new ColorOption("Hot Pink",      0xFFEC4899),
            new ColorOption("White",         0xFFFFFFFF),
            new ColorOption("Gray",          0xFF9CA3AF),
            new ColorOption("Black",         0xFF1F2937)
    ));

    /**
     * Returns the human-readable color name for an ARGB swatch without ever exposing hex codes.
     */
    public static String findColorName(int argb) {
        int opaque = 0xFF000000 | argb;
        for (ColorOption opt : RAINBOW_COLORS) {
            if ((0xFF000000 | opt.argb) == opaque) {
                return opt.name;
            }
        }
        return "Custom Color";
    }

    /** Closed palette mapping standard color names to their ARGB int values. */
    public static final Map<String, Integer> PALETTE;
    static {
        Map<String, Integer> p = new LinkedHashMap<>();
        p.put("Red",        0xFFEF4444); // #EF4444
        p.put("Orange",     0xFFF97316); // #F97316
        p.put("Yellow",     0xFFEAB308); // #EAB308
        p.put("Warm-Green", 0xFF84CC16); // #84CC16
        p.put("Cold-Green", 0xFF10B981); // #10B981
        p.put("Blue",       0xFF3B82F6); // #3B82F6
        p.put("Light-Blue", 0xFF38BDF8); // #38BDF8
        p.put("White",      0xFFFFFFFF); // #FFFFFF
        p.put("Gray",       0xFF9CA3AF); // #9CA3AF
        p.put("Black",      0xFF1F2937); // #1F2937
        PALETTE = Collections.unmodifiableMap(p);
    }

    /** User presets mapping favorite preset names to their ARGB int values. */
    public static final Map<String, Integer> PRESETS;
    static {
        Map<String, Integer> pr = new LinkedHashMap<>();
        pr.put("Light Blue", COLOR_LIGHT_BLUE);
        pr.put("Pink",       COLOR_PINK);
        pr.put("Green",      COLOR_GREEN);
        pr.put("Yellow",     COLOR_YELLOW);
        PRESETS = Collections.unmodifiableMap(pr);
    }

    /** Mock controllers list used for offline automated testing without TV ADB intervention. */
    private static volatile List<ControllerInfo> mockConnectedControllers = null;

    /**
     * Sets a mock list of connected controllers for non-disruptive offline automated testing.
     * Passing {@code null} restores live {@link InputDevice} queries.
     */
    public static void setMockConnectedControllers(List<ControllerInfo> mockList) {
        mockConnectedControllers = (mockList == null)
                ? null
                : Collections.unmodifiableList(new ArrayList<>(mockList));
    }

    /**
     * Immutable value object representing a physical controller's identity, tag, and runtime state.
     */
    public static final class ControllerInfo {
        public final int deviceId;
        public final String descriptor;
        public final String deviceName;
        public final String customName;
        public final int color;
        public final boolean tagged;
        public final int batteryPercent; // 0..100, or -1 if unknown
        public final boolean charging;
        public final boolean connected;

        public ControllerInfo(
                int deviceId,
                String descriptor,
                String deviceName,
                String customName,
                int color,
                boolean tagged,
                int batteryPercent,
                boolean charging
        ) {
            this(deviceId, descriptor, deviceName, customName, color, tagged, batteryPercent, charging, true);
        }

        public ControllerInfo(
                int deviceId,
                String descriptor,
                String deviceName,
                String customName,
                int color,
                boolean tagged,
                int batteryPercent,
                boolean charging,
                boolean connected
        ) {
            this.deviceId = deviceId;
            this.descriptor = descriptor != null ? descriptor : "";
            this.deviceName = deviceName != null ? deviceName : "Gamepad";
            this.customName = customName != null ? customName.trim() : "";
            this.color = color;
            this.tagged = tagged;
            this.batteryPercent = Math.max(-1, Math.min(100, batteryPercent));
            this.charging = charging;
            this.connected = connected;
        }

        public ControllerInfo(
                String descriptor,
                String deviceName,
                String customName,
                int color,
                boolean tagged,
                int batteryPercent,
                boolean charging
        ) {
            this(-1, descriptor, deviceName, customName, color, tagged, batteryPercent, charging, true);
        }

        public String getDisplayName() {
            return !customName.isEmpty() ? customName : deviceName;
        }

        public String getBatteryDisplay() {
            return batteryPercent >= 0 ? (batteryPercent + "%") : "";
        }

        /**
         * Returns a human-readable battery percentage and/or connected-to-power summary
         * (e.g. {@code "85%"}, {@code "100% · Connected to Power"}, {@code "Connected to Power"}, or {@code ""}).
         */
        public String getPowerStatusDisplay() {
            if (batteryPercent >= 0 && charging) {
                return batteryPercent + "% · Connected to Power";
            }
            if (batteryPercent >= 0) {
                return batteryPercent + "%";
            }
            if (charging) {
                return "Connected to Power";
            }
            return "";
        }

        public ControllerInfo withBattery(int percent, boolean isCharging) {
            return new ControllerInfo(deviceId, descriptor, deviceName, customName, color, tagged, percent, isCharging, connected);
        }

        public ControllerInfo withTag(String newCustomName, int newColor) {
            return new ControllerInfo(deviceId, descriptor, deviceName, newCustomName, newColor, true, batteryPercent, charging, connected);
        }

        public ControllerInfo withConnected(boolean isConnected) {
            return new ControllerInfo(deviceId, descriptor, deviceName, customName, color, tagged, batteryPercent, charging, isConnected);
        }
    }

    /**
     * Returns true when the kernel {@code BatteryState.getStatus()} value indicates the controller is
     * plugged into external power ({@code BATTERY_STATUS_CHARGING == 2} or {@code BATTERY_STATUS_FULL == 5}).
     */
    public static boolean isBatteryChargingOrOnPower(int batteryStatus) {
        return batteryStatus == 2 || batteryStatus == 5;
    }

    /**
     * Formats the text label rendered inside a connected controller's green pill on the Gaming Screen,
     * preserving both tagged custom names and untagged hardware names alongside battery / power state.
     */
    public static String formatControllerPillLabel(ControllerInfo ci) {
        if (ci == null) return "Gamepad";
        String name = ci.getDisplayName();
        if (ci.batteryPercent >= 0) {
            return name + " · " + ci.batteryPercent + "%";
        }
        if (ci.charging) {
            return name + " · Connected to Power";
        }
        return name;
    }

    /**
     * Returns true when {@code candidateMac} has already been claimed by a <em>different</em> connected controller,
     * while ensuring a controller never skips its own {@code "bt:<mac>"} Bluetooth device during battery fallback.
     */
    public static boolean shouldSkipClaimedBluetoothAddress(
            String candidateMac,
            String ownDescriptor,
            Set<String> claimedBtAddresses
    ) {
        if (candidateMac == null || candidateMac.trim().isEmpty() || claimedBtAddresses == null) {
            return false;
        }
        String macNorm = candidateMac.trim().toLowerCase(Locale.ROOT);
        if (!claimedBtAddresses.contains(macNorm)) {
            return false;
        }
        if (ownDescriptor != null && ownDescriptor.trim().toLowerCase(Locale.ROOT).equals("bt:" + macNorm)) {
            return false;
        }
        return true;
    }

    /**
     * Returns true when the controller has not yet been configured by the user and should launch
     * the streamlined First-Time Setup flow (Pick Color -> Name Controller) rather than the Edit menu.
     */
    public static boolean isFirstTimeSetup(ControllerInfo ci) {
        return ci != null && !ci.tagged;
    }

    /**
     * Returns the ordered action labels shown when managing an already-configured controller,
     * separating Rename and Change Color instead of forcing the user through first-time setup again.
     */
    public static List<String> getControllerEditActions(ControllerInfo ci) {
        List<String> actions = new ArrayList<>();
        actions.add("Rename Controller");
        actions.add("Change Color");
        actions.add("Reset Name & Color");
        if (ci != null && ci.descriptor != null && ci.descriptor.toLowerCase(Locale.ROOT).startsWith("bt:")) {
            actions.add("Unpair Bluetooth Controller");
        }
        return Collections.unmodifiableList(actions);
    }

    /**
     * Safely parses a hex-code color string (e.g. {@code "#c2ddfb"}, {@code "c2ddfb"}, {@code "#FFF"}, {@code "#FF000000"})
     * into an ARGB int. If parsing fails, returns {@code defaultColor}.
     */
    public static int parseColorHex(String hex, int defaultColor) {
        if (hex == null) return defaultColor;
        String clean = hex.trim();
        if (clean.isEmpty()) return defaultColor;
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.startsWith("0x") || clean.startsWith("0X")) clean = clean.substring(2);
        if (clean.length() == 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                char ch = clean.charAt(i);
                sb.append(ch).append(ch);
            }
            clean = sb.toString();
        }
        try {
            if (clean.length() == 6) {
                long rgb = Long.parseLong(clean, 16);
                return (int) (0xFF000000L | rgb);
            } else if (clean.length() == 8) {
                return (int) Long.parseLong(clean, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        return defaultColor;
    }

    /**
     * Formats an ARGB color int into a clean lowercase hex string (e.g. {@code "#c2ddfb"}).
     */
    public static String formatColorHex(int color) {
        return String.format(Locale.ROOT, "#%06x", (0xFFFFFF & color));
    }

    /**
     * Calculates WCAG perceptual luminance and selects semi-transparent white or black for high contrast.
     *
     * <p>Formula: {@code 0.299 * R + 0.587 * G + 0.114 * B}.
     * If luminance > 130 (light background such as Light Blue, Yellow, Pink, Green, or White), returns
     * semi-transparent black ({@code 0xCC000000}). Otherwise returns semi-transparent white ({@code 0xD9FFFFFF}).</p>
     */
    public static int calculateContrastInk(int backgroundColor) {
        int r = (backgroundColor >> 16) & 0xFF;
        int g = (backgroundColor >> 8) & 0xFF;
        int b = backgroundColor & 0xFF;
        double lum = 0.299 * r + 0.587 * g + 0.114 * b;
        return lum > 130.0 ? 0xCC000000 : 0xD9FFFFFF;
    }

    private static final Object BOLT_LOCK = new Object();
    private static Paint sBoltPaint;
    private static Path sBoltPath;

    /**
     * Renders a crisp procedural lightning bolt centered at {@code (cx, cy)} scaled to {@code size}.
     */
    public static void drawLightningBolt(Canvas c, float cx, float cy, float size, int inkColor) {
        if (c == null) return;
        try {
            synchronized (BOLT_LOCK) {
                if (sBoltPath == null) {
                    sBoltPath = new Path();
                    sBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                sBoltPath.reset();
                float h = size;
                float w = size * 0.65f;
                sBoltPath.moveTo(cx + w * 0.15f, cy - h);
                sBoltPath.lineTo(cx - w * 0.85f, cy + h * 0.05f);
                sBoltPath.lineTo(cx - w * 0.10f, cy + h * 0.05f);
                sBoltPath.lineTo(cx - w * 0.30f, cy + h);
                sBoltPath.lineTo(cx + w * 0.85f, cy - h * 0.05f);
                sBoltPath.lineTo(cx + w * 0.10f, cy - h * 0.05f);
                sBoltPath.close();

                sBoltPaint.setStyle(Paint.Style.FILL);
                sBoltPaint.setColor(inkColor);
                c.drawPath(sBoltPath, sBoltPaint);
            }
        } catch (RuntimeException ignored) {
            // Safe fallback in headless host JVM unit tests where android.jar stubs throw RuntimeException("Stub!")
        }
    }

    /**
     * Persists the user's chosen custom name and color for the specified controller descriptor.
     */
    public static void saveTag(Context context, String descriptor, String customName, int color) {
        saveTag(context, descriptor, customName, color, null);
    }

    /**
     * Persists the user's chosen custom name, color, and optional clean hardware name for the specified controller descriptor.
     */
    public static void saveTag(Context context, String descriptor, String customName, int color, String hardwareDeviceName) {
        if (context == null || descriptor == null || descriptor.trim().isEmpty()) return;
        String key = descriptor.trim();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit()
                .putString(KEY_PREFIX_NAME + key, customName != null ? customName.trim() : "")
                .putInt(KEY_PREFIX_COLOR + key, color)
                .putBoolean(KEY_PREFIX_TAGGED + key, true)
                .remove("ctrl_cleared_" + key);
        if (hardwareDeviceName != null && !hardwareDeviceName.trim().isEmpty()) {
            ed.putString(KEY_PREFIX_HWNAME + key, hardwareDeviceName.trim());
        }
        ed.apply();
        if (key.startsWith("bt:")) {
            try {
                CloudSyncManager.get(context).recordLocalFieldChange(
                        CloudSyncManager.Scope.HOUSEHOLD, "bt_controller_tags");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Exports all tagged Bluetooth gamepad names and colors ({@code bt:<mac>}) for Household Cloud Sync.
     * Per-TV physical USB port tags ({@code usbport:*}) are intentionally excluded.
     */
    public static Map<String, Object> exportBluetoothTagsForCloud(Context context) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (context == null) return out;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        if (all == null) return out;
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k != null && k.startsWith(KEY_PREFIX_TAGGED + "bt:") && Boolean.TRUE.equals(e.getValue())) {
                String desc = k.substring(KEY_PREFIX_TAGGED.length());
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("name", sp.getString(KEY_PREFIX_NAME + desc, ""));
                tag.put("color", sp.getInt(KEY_PREFIX_COLOR + desc, COLOR_NEUTRAL_FALLBACK));
                String hw = sp.getString(KEY_PREFIX_HWNAME + desc, "");
                if (!hw.isEmpty()) tag.put("hw_name", hw);
                out.put(desc, tag);
            }
        }
        return out;
    }

    /** Imports household-synced Bluetooth gamepad names and colors ({@code bt:<mac>}) locally. */
    public static void importBluetoothTagsFromCloud(Context context, Map<String, Object> tags) {
        if (context == null || tags == null || tags.isEmpty()) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : tags.entrySet()) {
            String desc = e.getKey();
            if (desc == null || !desc.startsWith("bt:")) continue;
            Map<String, Object> tag = CloudVaultCrypto.JsonCodec.optObjectMap(tags, desc);
            if (tag.isEmpty()) continue;
            String name = CloudVaultCrypto.JsonCodec.optString(tag, "name", "");
            int color = CloudVaultCrypto.JsonCodec.optInt(tag, "color", COLOR_NEUTRAL_FALLBACK);
            String hw = CloudVaultCrypto.JsonCodec.optString(tag, "hw_name", "");
            ed.putString(KEY_PREFIX_NAME + desc, name);
            ed.putInt(KEY_PREFIX_COLOR + desc, color);
            ed.putBoolean(KEY_PREFIX_TAGGED + desc, true);
            if (!hw.isEmpty()) ed.putString(KEY_PREFIX_HWNAME + desc, hw);
        }
        ed.apply();
    }

    /**
     * Returns true if the controller with the given descriptor has been explicitly tagged by the user.
     */
    public static boolean isTagged(Context context, String descriptor) {
        if (context == null || descriptor == null || descriptor.trim().isEmpty()) return false;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_PREFIX_TAGGED + descriptor.trim(), false);
    }

    /**
     * Returns the user's custom name for the specified controller descriptor, or empty string if untagged.
     */
    public static String getCustomName(Context context, String descriptor) {
        if (context == null || descriptor == null || descriptor.trim().isEmpty()) return "";
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_PREFIX_NAME + descriptor.trim(), "");
    }

    /**
     * Returns the user's assigned color for the specified controller descriptor, or {@link #COLOR_NEUTRAL_FALLBACK}.
     */
    public static int getAssignedColor(Context context, String descriptor) {
        if (context == null || descriptor == null || descriptor.trim().isEmpty()) return COLOR_NEUTRAL_FALLBACK;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PREFIX_TAGGED + descriptor.trim(), false)) {
            return COLOR_NEUTRAL_FALLBACK;
        }
        return sp.getInt(KEY_PREFIX_COLOR + descriptor.trim(), COLOR_NEUTRAL_FALLBACK);
    }

    /**
     * Clears any saved tag (name and color) for the specified controller descriptor.
     */
    public static void clearTag(Context context, String descriptor) {
        if (context == null || descriptor == null || descriptor.trim().isEmpty()) return;
        String key = descriptor.trim();
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .remove(KEY_PREFIX_NAME + key)
                .remove(KEY_PREFIX_COLOR + key)
                .remove(KEY_PREFIX_TAGGED + key)
                .putBoolean("ctrl_cleared_" + key, true)
                .apply();
    }

    /**
     * Resolves a stable hardware descriptor string for an InputDevice, falling back to permanent
     * vendorId and productId attributes when the raw device descriptor is null or blank.
     */
    public static String resolveStableDescriptor(String rawDescriptor, String deviceName, int vendorId, int productId) {
        return resolveStableDescriptor(rawDescriptor, null, null, deviceName, vendorId, productId);
    }

    /**
     * Resolves a stable hardware descriptor string for an InputDevice, falling back to permanent
     * uniqueId/MAC or vendorId and productId attributes when the raw device descriptor is null or blank.
     */
    public static String resolveStableDescriptor(String rawDescriptor, String uniqueId, String deviceName, int vendorId, int productId) {
        return resolveStableDescriptor(rawDescriptor, uniqueId, null, deviceName, vendorId, productId);
    }

    /**
     * Returns true if {@code candidate} is a standard 6-octet Bluetooth MAC address (with or without {@code "bt:"} prefix).
     */
    public static boolean isBluetoothMacAddress(String candidate) {
        if (candidate == null) return false;
        String s = candidate.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("bt:")) {
            s = s.substring(3).trim();
        }
        return s.matches("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$");
    }

    /**
     * Normalizes a Bluetooth MAC address to SoarTV's canonical {@code "bt:<lowercase-mac>"} descriptor
     * so tags match identically whether a controller is newly bonded, sleeping, or awake in EventHub.
     */
    public static String canonicalBluetoothDescriptor(String macAddress) {
        if (macAddress == null) return "";
        String s = macAddress.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("bt:")) {
            s = s.substring(3).trim();
        }
        if (s.isEmpty()) return "";
        return "bt:" + s;
    }

    /**
     * Returns true when a USB/HID vendor ID belongs to a known physical game controller manufacturer
     * (excluding TV remote vendors such as Xiaomi {@code 0x2717}).
     */
    public static boolean isKnownGamepadVendor(int vendorId) {
        switch (vendorId) {
            case 0x054c: // Sony (DualShock 4 / DualSense / GameSir Home+B mode)
            case 0x045e: // Microsoft (Xbox)
            case 0x057e: // Nintendo (Switch Pro / Joy-Con)
            case 0x3537: // GameSir / Zikway (Nova Lite)
            case 0x2dc8: // 8BitDo
            case 0x1532: // Razer
            case 0x1038: // SteelSeries
            case 0x046d: // Logitech
            case 0x18d1: // Google (Stadia)
            case 0x28de: // Valve (Steam Controller)
            case 0x0171: // Amazon (Luna)
            case 0x20d6: // PowerA
            case 0x24c6: // PowerA / BDA
            case 0x0f0d: // Hori
            case 0x2563: // ShanWan
                return true;
            default:
                return false;
        }
    }

    /**
     * Determines whether a bonded Bluetooth device represents a physical game controller
     * (such as DualShock 4 / GameSir {@code "Wireless Controller"}) while excluding TV remotes,
     * headphones, speakers, phones, and computers.
     */
    public static boolean isBluetoothGamepadCandidate(String name, int majorClass, int deviceClass) {
        if (name == null || name.trim().isEmpty()) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("xiaomi")
                || lower.contains("remote")
                || lower.startsWith("rc ")
                || lower.endsWith(" rc")
                || lower.contains(" rc ")
                || lower.contains("tv ")
                || lower.contains("voice")
                || lower.contains("assistant")
                || lower.contains("headset")
                || lower.contains("headphone")
                || lower.contains("airpods")
                || lower.contains("buds")
                || lower.contains("speaker")
                || lower.contains("soundbar")
                || lower.contains("keyboard")
                || lower.contains("mouse")) {
            return false;
        }
        // Reject Audio/Video (0x0400), Phone (0x0200), Computer (0x0100), Networking (0x0300), Imaging (0x0600), Wearable (0x0700), Health (0x0900)
        if (majorClass == 0x0400 || majorClass == 0x0200 || majorClass == 0x0100
                || majorClass == 0x0300 || majorClass == 0x0600 || majorClass == 0x0700 || majorClass == 0x0900) {
            return false;
        }
        // Standard Bluetooth HID Gamepad (0x0508) or Joystick (0x0504)
        if (deviceClass == 0x0508 || deviceClass == 0x0504) {
            return true;
        }
        return BluetoothControllerPairer.isLikelyGamepadOrRemoteName(name);
    }

    /**
     * Resolves a stable hardware descriptor string for an InputDevice, prioritizing physical USB port
     * topology (e.g. {@code "usbport:13623:4160:xhci-2"}) for USB dongles and canonical Bluetooth MAC
     * ({@code "bt:aa:bb:cc:11:22:33"}) for Bluetooth gamepads so tags survive power-cycles and sleep/wake.
     */
    public static String resolveStableDescriptor(String rawDescriptor, String uniqueId, String usbPortId, String deviceName, int vendorId, int productId) {
        if (usbPortId != null && !usbPortId.trim().isEmpty() && (vendorId != 0 || productId != 0)) {
            return "usbport:" + vendorId + ":" + productId + ":" + usbPortId.trim();
        }
        if (isBluetoothMacAddress(uniqueId)) {
            return canonicalBluetoothDescriptor(uniqueId);
        }
        if (isBluetoothMacAddress(rawDescriptor)) {
            return canonicalBluetoothDescriptor(rawDescriptor);
        }
        if (rawDescriptor != null && !rawDescriptor.trim().isEmpty()) {
            return rawDescriptor.trim();
        }
        if (uniqueId != null && !uniqueId.trim().isEmpty()) {
            return uniqueId.trim();
        }
        String safeName = deviceName != null && !deviceName.trim().isEmpty() ? deviceName.trim() : "Gamepad";
        if (vendorId != 0 || productId != 0) {
            return safeName + "_" + vendorId + "_" + productId;
        }
        return safeName;
    }

    /**
     * Strips companion interface suffixes such as trailing " Keyboard", " Consumer Control",
     * " (Keyboard)", or " - Keyboard" so that hybrid USB/HID dongles (like "2.4G XBOX 360 For Windows Keyboard")
     * display cleanly as "2.4G XBOX 360 For Windows".
     */
    public static String cleanDeviceName(String name) {
        if (name == null) return "Gamepad";
        String clean = name.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String lower = clean.toLowerCase(Locale.ROOT);
            if (lower.endsWith(" - keyboard")) {
                clean = clean.substring(0, clean.length() - " - keyboard".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (keyboard)")) {
                clean = clean.substring(0, clean.length() - " (keyboard)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [keyboard]")) {
                clean = clean.substring(0, clean.length() - " [keyboard]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" keyboard")) {
                clean = clean.substring(0, clean.length() - " keyboard".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - consumer control")) {
                clean = clean.substring(0, clean.length() - " - consumer control".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (consumer control)")) {
                clean = clean.substring(0, clean.length() - " (consumer control)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [consumer control]")) {
                clean = clean.substring(0, clean.length() - " [consumer control]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" consumer control")) {
                clean = clean.substring(0, clean.length() - " consumer control".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - mouse")) {
                clean = clean.substring(0, clean.length() - " - mouse".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (mouse)")) {
                clean = clean.substring(0, clean.length() - " (mouse)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [mouse]")) {
                clean = clean.substring(0, clean.length() - " [mouse]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" mouse")) {
                clean = clean.substring(0, clean.length() - " mouse".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - touchpad")) {
                clean = clean.substring(0, clean.length() - " - touchpad".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (touchpad)")) {
                clean = clean.substring(0, clean.length() - " (touchpad)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [touchpad]")) {
                clean = clean.substring(0, clean.length() - " [touchpad]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" touchpad")) {
                clean = clean.substring(0, clean.length() - " touchpad".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - stylus")) {
                clean = clean.substring(0, clean.length() - " - stylus".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (stylus)")) {
                clean = clean.substring(0, clean.length() - " (stylus)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [stylus]")) {
                clean = clean.substring(0, clean.length() - " [stylus]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" stylus")) {
                clean = clean.substring(0, clean.length() - " stylus".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - system control")) {
                clean = clean.substring(0, clean.length() - " - system control".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (system control)")) {
                clean = clean.substring(0, clean.length() - " (system control)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [system control]")) {
                clean = clean.substring(0, clean.length() - " [system control]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" system control")) {
                clean = clean.substring(0, clean.length() - " system control".length()).trim();
                changed = true;
            } else if (lower.endsWith(" - motion sensors")) {
                clean = clean.substring(0, clean.length() - " - motion sensors".length()).trim();
                changed = true;
            } else if (lower.endsWith(" (motion sensors)")) {
                clean = clean.substring(0, clean.length() - " (motion sensors)".length()).trim();
                changed = true;
            } else if (lower.endsWith(" [motion sensors]")) {
                clean = clean.substring(0, clean.length() - " [motion sensors]".length()).trim();
                changed = true;
            } else if (lower.endsWith(" motion sensors")) {
                clean = clean.substring(0, clean.length() - " motion sensors".length()).trim();
                changed = true;
            } else if (lower.endsWith(" motion sensor")) {
                clean = clean.substring(0, clean.length() - " motion sensor".length()).trim();
                changed = true;
            }
            if (clean.endsWith(" -") || clean.endsWith(" /") || clean.endsWith(" _") || clean.endsWith(" :")) {
                clean = clean.substring(0, clean.length() - 2).trim();
                changed = true;
            } else if (clean.endsWith("-") || clean.endsWith("/") || clean.endsWith("_") || clean.endsWith(":")) {
                clean = clean.substring(0, clean.length() - 1).trim();
                changed = true;
            }
        }
        return clean.isEmpty() ? "Gamepad" : clean;
    }

    /**
     * Identifies whether a USB interface conforms to the Xbox 360 XInput or Xbox One GIP specification.
     *
     * <p>Xbox 360 XInput: interfaceClass == 255 (Vendor Specific), interfaceSubclass == 93, interfaceProtocol == 1 (wired) or 129 (wireless).
     * Xbox One GIP: interfaceClass == 255, interfaceSubclass == 71, interfaceProtocol == 208.</p>
     */
    public static boolean isXInputInterface(int cls, int sub, int proto) {
        return (cls == 255 && sub == 93 && (proto == 1 || proto == 129))
                || (cls == 255 && sub == 71 && proto == 208);
    }

    /**
     * Determines whether the given USB device has at least one interface conforming to Xbox XInput or GIP.
     * Does NOT match idle dongles such as GameSir / Zikway idle state (VID 0x3537, PID 0x2106, interfaceClass == 3 HID).
     */
    public static boolean isXInputUsbDevice(UsbDevice dev) {
        if (dev == null) return false;
        try {
            int count = dev.getInterfaceCount();
            for (int i = 0; i < count; i++) {
                UsbInterface iface = dev.getInterface(i);
                if (iface == null) continue;
                if (isXInputInterface(iface.getInterfaceClass(), iface.getInterfaceSubclass(), iface.getInterfaceProtocol())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Immutable snapshot of an Android input node's physical hardware topology (`Location`, `UniqueId`,
     * Bluetooth MAC, and all merged EventHub SHA-1 `Descriptor` strings).
     */
    public static final class DeviceHardwareLocation {
        public final int deviceId;
        public final String name;
        public final String location;
        public final String uniqueId;
        public final String bluetoothAddress;
        public final String descriptor;
        public final Set<String> allDescriptors;

        public DeviceHardwareLocation(int deviceId, String name, String location, String uniqueId, String bluetoothAddress) {
            this(deviceId, name, location, uniqueId, bluetoothAddress, "", null);
        }

        public DeviceHardwareLocation(
                int deviceId,
                String name,
                String location,
                String uniqueId,
                String bluetoothAddress,
                String descriptor
        ) {
            this(deviceId, name, location, uniqueId, bluetoothAddress, descriptor, null);
        }

        public DeviceHardwareLocation(
                int deviceId,
                String name,
                String location,
                String uniqueId,
                String bluetoothAddress,
                String descriptor,
                Set<String> mergedDescriptors
        ) {
            this.deviceId = deviceId;
            this.name = name != null ? name.trim() : "";
            this.location = location != null ? location.trim() : "";
            this.uniqueId = uniqueId != null ? uniqueId.trim() : "";
            this.bluetoothAddress = bluetoothAddress != null ? bluetoothAddress.trim() : "";
            this.descriptor = descriptor != null ? descriptor.trim() : "";
            Set<String> set = new LinkedHashSet<>();
            if (!this.descriptor.isEmpty()) {
                set.add(this.descriptor.toLowerCase(Locale.ROOT));
            }
            if (mergedDescriptors != null) {
                for (String d : mergedDescriptors) {
                    if (d != null && !d.trim().isEmpty()) {
                        set.add(d.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            this.allDescriptors = Collections.unmodifiableSet(set);
        }

        /**
         * Verifies that a cached ID lookup belongs to the same underlying hardware descriptor (preventing
         * false matches if an OEM dump omits `Input Reader State` and EventHub IDs overlap InputDevice IDs).
         */
        public boolean matchesDescriptor(String candidateDescriptor) {
            if (allDescriptors.isEmpty() || candidateDescriptor == null || candidateDescriptor.trim().isEmpty()) {
                return true;
            }
            return allDescriptors.contains(candidateDescriptor.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static final Map<Integer, DeviceHardwareLocation> sHardwareLocations = new ConcurrentHashMap<>();
    private static final Map<String, DeviceHardwareLocation> sHardwareLocationsByDescriptor = new ConcurrentHashMap<>();
    private static final ExecutorService ADB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soartv-ctrl-adb");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean sReflectionInitialized = false;
    private static volatile java.lang.reflect.Method sInputDeviceGetBtAddress;
    private static volatile java.lang.reflect.Method sInputDeviceGetBatteryState;
    private static volatile java.lang.reflect.Method sInputManagerGetBtAddress;
    private static volatile java.lang.reflect.Method sBtDeviceIsConnected;
    private static volatile java.lang.reflect.Method sBtDeviceGetBatteryLevel;
    private static volatile Class<?> sBatteryStateCachedClass;
    private static volatile java.lang.reflect.Method sBatteryStateIsPresent;
    private static volatile java.lang.reflect.Method sBatteryStateGetCapacity;
    private static volatile java.lang.reflect.Method sBatteryStateGetStatus;

    private static void ensureReflectionInitialized() {
        if (sReflectionInitialized) return;
        synchronized (ControllerManager.class) {
            if (sReflectionInitialized) return;
            sInputDeviceGetBtAddress = findAccessibleMethod(InputDevice.class, "getBluetoothAddress");
            sInputDeviceGetBatteryState = findAccessibleMethod(InputDevice.class, "getBatteryState");
            sInputManagerGetBtAddress = findAccessibleMethod(android.hardware.input.InputManager.class, "getInputDeviceBluetoothAddress", int.class);
            sBtDeviceIsConnected = findAccessibleMethod(BluetoothDevice.class, "isConnected");
            sBtDeviceGetBatteryLevel = findAccessibleMethod(BluetoothDevice.class, "getBatteryLevel");
            sReflectionInitialized = true;
        }
    }

    private static void ensureBatteryStateReflection(Class<?> bsCls) {
        if (bsCls == null || bsCls == sBatteryStateCachedClass) return;
        synchronized (ControllerManager.class) {
            if (bsCls == sBatteryStateCachedClass) return;
            sBatteryStateIsPresent = findAccessibleMethod(bsCls, "isPresent");
            sBatteryStateGetCapacity = findAccessibleMethod(bsCls, "getCapacity");
            sBatteryStateGetStatus = findAccessibleMethod(bsCls, "getStatus");
            sBatteryStateCachedClass = bsCls;
        }
    }

    private static java.lang.reflect.Method findAccessibleMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try {
            java.lang.reflect.Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }
    private static volatile long sLastAdbRefreshUptime = 0L;
    private static final Object REFRESH_LOCK = new Object();
    private static boolean sRefreshInFlight = false;
    private static boolean sRefreshPendingAgain = false;
    private static final List<Runnable> sPendingRefreshCallbacks = new ArrayList<>();
    private static volatile Runnable sAsyncRefreshListener = null;

    /**
     * Registers a UI listener notified whenever an asynchronous hardware-location probe resolves new port data.
     */
    public static void setAsyncRefreshListener(Runnable listener) {
        sAsyncRefreshListener = listener;
    }

    /**
     * Injects mock hardware location mappings for unit tests.
     */
    public static void setMockHardwareLocationsForTesting(Map<Integer, DeviceHardwareLocation> mockLocs) {
        sHardwareLocations.clear();
        sHardwareLocationsByDescriptor.clear();
        if (mockLocs != null) {
            sHardwareLocations.putAll(mockLocs);
            for (DeviceHardwareLocation loc : mockLocs.values()) {
                indexHardwareLocationDescriptors(loc);
            }
        }
    }

    private static void indexHardwareLocationDescriptors(DeviceHardwareLocation loc) {
        if (loc == null) return;
        for (String d : loc.allDescriptors) {
            if (d != null && !d.isEmpty()) {
                sHardwareLocationsByDescriptor.put(d, loc);
            }
        }
    }

    /**
     * Resolves cached hardware topology for an {@link InputDevice}, cross-checking SHA-1 descriptors so
     * numeric EventHub/InputReader ID overlaps on 4-port USB hubs can never misroute a controller's port.
     */
    static DeviceHardwareLocation lookupHardwareLocation(int inputDeviceId, String rawDescriptor) {
        DeviceHardwareLocation byId = sHardwareLocations.get(inputDeviceId);
        if (byId != null && byId.matchesDescriptor(rawDescriptor)) {
            return byId;
        }
        if (rawDescriptor != null && !rawDescriptor.trim().isEmpty()) {
            return sHardwareLocationsByDescriptor.get(rawDescriptor.trim().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    /**
     * Extracts physical USB bus/port identifier from a sysfs/EventHub location string
     * (e.g. "usb-1c7a0000.xhci-2/input1" -> "xhci-2", "usb-xhci-hcd.0.auto-1.2/input0" -> "xhci-1.2").
     *
     * <p><b>Important:</b> Rejects Android {@code /dev/bus/usb/001/030} paths and {@code usb_<id>} strings
     * because Linux USB device numbers increment on every controller power-cycle and are NOT physical ports.</p>
     */
    public static String extractUsbPortIdentifier(String location) {
        if (location == null || location.trim().isEmpty()) return "";
        String loc = location.trim();
        String lower = loc.toLowerCase(Locale.ROOT);

        // Reject ephemeral Linux USB device counters (/dev/bus/usb/001/030 -> 033) and Android device IDs (usb_1036)
        if (lower.startsWith("/dev/bus/usb/") || lower.startsWith("usb_")) {
            return "";
        }

        // Strip EventHub/sysfs input node suffix (e.g. "/input0", "/input1")
        int inputIdx = lower.indexOf("/input");
        if (inputIdx >= 0) {
            loc = loc.substring(0, inputIdx).trim();
            lower = loc.toLowerCase(Locale.ROOT);
        } else if (loc.indexOf('/') >= 0 && !loc.startsWith("/")) {
            loc = loc.substring(0, loc.indexOf('/')).trim();
            lower = loc.toLowerCase(Locale.ROOT);
        }

        // Check for xhci controller suffix (e.g. "usb-1c7a0000.xhci-2" -> "xhci-2")
        int dashIdx = loc.lastIndexOf('-');
        if (dashIdx >= 0 && dashIdx + 1 < loc.length()) {
            String suffix = loc.substring(dashIdx + 1);
            if (Character.isDigit(suffix.charAt(0))) {
                return "xhci-" + suffix;
            }
        }
        int xhciIdx = lower.lastIndexOf("xhci-");
        if (xhciIdx >= 0) {
            return loc.substring(xhciIdx);
        }
        return loc;
    }

    /**
     * Scans {@code SharedPreferences} entries for an orphaned ephemeral USB tag key
     * ({@code usbport:<vendorId>:<productId>:usb-*}) created before the power-cycle persistence fix,
     * returning its descriptor key so its custom name and color can be migrated to the stable {@code xhci-*} port.
     */
    public static String findOrphanedEphemeralUsbTagKey(Map<String, ?> allPrefs, int vendorId, int productId) {
        if (allPrefs == null || (vendorId == 0 && productId == 0)) return null;
        String prefix = KEY_PREFIX_TAGGED + "usbport:" + vendorId + ":" + productId + ":usb-";
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String k = entry.getKey();
            if (k != null && k.startsWith(prefix) && Boolean.TRUE.equals(entry.getValue())) {
                String desc = k.substring(KEY_PREFIX_TAGGED.length());
                if (!Boolean.TRUE.equals(allPrefs.get("ctrl_cleared_" + desc))) {
                    return desc;
                }
            }
        }
        return null;
    }

    /**
     * Extracts a human-readable port label such as "USB 1", "USB 2", "USB 1.2", or "USB 1-2" from a USB location.
     */
    public static String extractUsbPortLabel(String location) {
        String id = extractUsbPortIdentifier(location);
        if (id.isEmpty()) return "";
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("xhci-")) {
            id = id.substring("xhci-".length());
        } else if (lower.startsWith("usb-")) {
            id = id.substring("usb-".length());
        }
        return id.isEmpty() ? "" : ("USB " + id);
    }

    /**
     * Parses output of `dumpsys input`, correlating `Event Hub State: -> Devices:` (hardware Location,
     * UniqueId, Descriptor, and bluetoothAddress) with `Input Reader State:` (`Device <inputDeviceId>:` ->
     * `EventHub Devices: [ ... ]`) so keys match Android's {@link InputDevice#getId()} without ID collisions
     * when multi-interface USB hub dongles create 3 EventHub nodes per controller.
     *
     * <p>Falls back cleanly to EventHub IDs if the dump snippet omits `Input Reader State:`.</p>
     */
    public static Map<Integer, DeviceHardwareLocation> parseInputDumpLocations(String dump) {
        return parseInputDumpLocationsInternal(dump, null);
    }

    private static Map<Integer, DeviceHardwareLocation> parseInputDumpLocationsInternal(
            String dump,
            Map<Integer, DeviceHardwareLocation> outAllEventHubNodes
    ) {
        Map<Integer, DeviceHardwareLocation> eventHubById = new LinkedHashMap<>();
        if (dump == null || dump.isEmpty()) return eventHubById;

        int curId = -1;
        String curName = "";
        String curLoc = "";
        String curUnique = "";
        String curBt = "";
        String curDesc = "";
        boolean inDevices = false;
        boolean inInputReader = false;
        int curReaderId = -1;
        String curReaderName = "";
        Map<Integer, DeviceHardwareLocation> inputReaderById = new LinkedHashMap<>();

        String[] lines = dump.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("Devices:") && !inInputReader) {
                inDevices = true;
                continue;
            }
            if (inDevices) {
                int leadingSpaces = 0;
                while (leadingSpaces < line.length() && (line.charAt(leadingSpaces) == ' ' || line.charAt(leadingSpaces) == '\t')) {
                    leadingSpaces += (line.charAt(leadingSpaces) == '\t') ? 4 : 1;
                }
                if (!trimmed.isEmpty() && leadingSpaces <= 2) {
                    if (curId >= 0) {
                        eventHubById.put(curId, new DeviceHardwareLocation(curId, curName, curLoc, curUnique, curBt, curDesc));
                        curId = -1;
                    }
                    inDevices = false;
                } else {
                    int colonIdx = trimmed.indexOf(':');
                    if (colonIdx > 0) {
                        String potentialId = trimmed.substring(0, colonIdx).trim();
                        boolean isSignedInt = !potentialId.isEmpty();
                        for (int i = 0; i < potentialId.length(); i++) {
                            char ch = potentialId.charAt(i);
                            if (i == 0 && ch == '-' && potentialId.length() > 1) {
                                continue;
                            }
                            if (!Character.isDigit(ch)) {
                                isSignedInt = false;
                                break;
                            }
                        }
                        if (isSignedInt) {
                            if (curId >= 0) {
                                eventHubById.put(curId, new DeviceHardwareLocation(curId, curName, curLoc, curUnique, curBt, curDesc));
                            }
                            try {
                                int parsedEhId = Integer.parseInt(potentialId);
                                curId = parsedEhId >= 0 ? parsedEhId : -1;
                            } catch (NumberFormatException e) {
                                curId = -1;
                            }
                            curName = trimmed.substring(colonIdx + 1).trim();
                            curLoc = "";
                            curUnique = "";
                            curBt = "";
                            curDesc = "";
                            continue;
                        }
                    }

                    if (curId >= 0) {
                        if (trimmed.startsWith("Descriptor:")) {
                            curDesc = trimmed.substring("Descriptor:".length()).trim();
                        } else if (trimmed.startsWith("Location:")) {
                            curLoc = trimmed.substring("Location:".length()).trim();
                        } else if (trimmed.startsWith("UniqueId:")) {
                            curUnique = trimmed.substring("UniqueId:".length()).trim();
                        } else if (trimmed.startsWith("Identifier:")) {
                            int btIdx = trimmed.indexOf("bluetoothAddress=");
                            if (btIdx >= 0) {
                                String afterBt = trimmed.substring(btIdx + "bluetoothAddress=".length()).trim();
                                int endIdx = afterBt.indexOf(',');
                                if (endIdx >= 0) afterBt = afterBt.substring(0, endIdx).trim();
                                if (!afterBt.equalsIgnoreCase("<not set>") && !afterBt.isEmpty()) {
                                    curBt = afterBt;
                                }
                            }
                        }
                    }
                    continue;
                }
            }

            if ((trimmed.startsWith("Input Reader State:") || trimmed.startsWith("Input Reader State ("))
                    && trimmed.endsWith(":")) {
                inInputReader = true;
                curReaderId = -1;
                curReaderName = "";
                continue;
            }
            if (inInputReader) {
                if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.isEmpty()) {
                    inInputReader = false;
                    curReaderId = -1;
                    continue;
                }
                if (trimmed.startsWith("Device ")) {
                    int colonIdx = trimmed.indexOf(':');
                    if (colonIdx > "Device ".length()) {
                        String idPart = trimmed.substring("Device ".length(), colonIdx).trim();
                        try {
                            int parsedId = Integer.parseInt(idPart);
                            if (parsedId >= 0) {
                                curReaderId = parsedId;
                                curReaderName = trimmed.substring(colonIdx + 1).trim();
                            } else {
                                curReaderId = -1;
                            }
                        } catch (NumberFormatException e) {
                            curReaderId = -1;
                        }
                        continue;
                    }
                }
                if (curReaderId >= 0 && trimmed.startsWith("EventHub Devices:")) {
                    String after = trimmed.substring("EventHub Devices:".length()).trim();
                    int openB = after.indexOf('[');
                    int closeB = after.indexOf(']');
                    if (openB >= 0 && closeB > openB) {
                        String inside = after.substring(openB + 1, closeB).trim();
                        if (!inside.isEmpty()) {
                            String[] parts = inside.split("\\s+");
                            DeviceHardwareLocation bestEh = null;
                            Set<String> mergedDescs = new LinkedHashSet<>();
                            for (String p : parts) {
                                try {
                                    int ehId = Integer.parseInt(p.trim());
                                    DeviceHardwareLocation candidate = eventHubById.get(ehId);
                                    if (candidate != null) {
                                        if (!candidate.descriptor.isEmpty()) {
                                            mergedDescs.add(candidate.descriptor);
                                        }
                                        if (bestEh == null) {
                                            bestEh = candidate;
                                        } else if (bestEh.location.isEmpty() && !candidate.location.isEmpty()) {
                                            bestEh = candidate;
                                        }
                                    }
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            if (bestEh != null) {
                                String resolvedName = !curReaderName.isEmpty() ? curReaderName : bestEh.name;
                                inputReaderById.put(curReaderId, new DeviceHardwareLocation(
                                        curReaderId,
                                        resolvedName,
                                        bestEh.location,
                                        bestEh.uniqueId,
                                        bestEh.bluetoothAddress,
                                        bestEh.descriptor,
                                        mergedDescs
                                ));
                            }
                        }
                    }
                    curReaderId = -1;
                }
            }
        }
        if (curId >= 0) {
            eventHubById.put(curId, new DeviceHardwareLocation(curId, curName, curLoc, curUnique, curBt, curDesc));
        }
        if (outAllEventHubNodes != null) {
            outAllEventHubNodes.putAll(eventHubById);
        }
        return !inputReaderById.isEmpty() ? inputReaderById : eventHubById;
    }

    /**
     * Refreshes physical hardware locations asynchronously via zero-PC on-TV ADB loopback (`127.0.0.1:5555`).
     * Guarantees trailing-edge execution so rapid power-cycle reconnect events never drop {@code onComplete}.
     */
    public static void refreshHardwareLocationsAsync(Context context, Runnable onComplete) {
        if (context == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        final Context appCtx = context.getApplicationContext();
        synchronized (REFRESH_LOCK) {
            if (onComplete != null) {
                sPendingRefreshCallbacks.add(onComplete);
            }
            if (sRefreshInFlight) {
                sRefreshPendingAgain = true;
                return;
            }
            sRefreshInFlight = true;
            sRefreshPendingAgain = false;
        }

        ADB_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long elapsed = SystemClock.uptimeMillis() - sLastAdbRefreshUptime;
                    if (elapsed >= 0L && elapsed < 250L) {
                        try {
                            Thread.sleep(250L - elapsed);
                        } catch (InterruptedException ignored) {}
                    }
                    sLastAdbRefreshUptime = SystemClock.uptimeMillis();
                    Adb adb = Adb.connectSignedOnly(appCtx);
                    if (adb != null) {
                        try {
                            String dump = adb.shell("dumpsys input");
                            Map<Integer, DeviceHardwareLocation> allEh = new LinkedHashMap<>();
                            Map<Integer, DeviceHardwareLocation> parsed = parseInputDumpLocationsInternal(dump, allEh);
                            if (parsed != null && !parsed.isEmpty()) {
                                sHardwareLocations.clear();
                                sHardwareLocationsByDescriptor.clear();
                                for (DeviceHardwareLocation ehLoc : allEh.values()) {
                                    indexHardwareLocationDescriptors(ehLoc);
                                }
                                sHardwareLocations.putAll(parsed);
                                for (DeviceHardwareLocation loc : parsed.values()) {
                                    indexHardwareLocationDescriptors(loc);
                                }
                            }
                        } finally {
                            adb.close();
                        }
                    }
                } catch (Throwable ignored) {
                }

                final List<Runnable> toNotify;
                boolean runAgain;
                synchronized (REFRESH_LOCK) {
                    runAgain = sRefreshPendingAgain;
                    sRefreshPendingAgain = false;
                    if (runAgain) {
                        toNotify = Collections.emptyList();
                    } else {
                        toNotify = new ArrayList<>(sPendingRefreshCallbacks);
                        sPendingRefreshCallbacks.clear();
                        sRefreshInFlight = false;
                    }
                }
                if (runAgain) {
                    ADB_EXECUTOR.execute(this);
                    return;
                }
                Handler main = new Handler(Looper.getMainLooper());
                main.post(() -> {
                    for (Runnable cb : toNotify) {
                        try {
                            cb.run();
                        } catch (Throwable ignored) {}
                    }
                    Runnable listener = sAsyncRefreshListener;
                    if (listener != null) {
                        try {
                            listener.run();
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });
    }

    /**
     * Cleans up hardware location cache when an InputDevice is disconnected.
     */
    public static void onInputDeviceRemoved(int deviceId) {
        sHardwareLocations.remove(deviceId);
    }

    private static volatile Integer sMockBluetoothBattery = null;
    private static volatile Map<String, Integer> sMockBluetoothBatteries = null;

    /**
     * Sets a mock Bluetooth battery level (0..100) for headless testing, or null to restore live queries.
     */
    public static void setMockBluetoothBatteryForTesting(Integer mockBattery) {
        sMockBluetoothBattery = mockBattery;
    }

    /**
     * Sets a map of mock Bluetooth battery levels keyed by MAC address or device name for testing
     * multi-controller scenarios without collisions.
     */
    public static void setMockBluetoothBatteriesForTesting(Map<String, Integer> mockBatteries) {
        sMockBluetoothBatteries = mockBatteries != null ? new LinkedHashMap<>(mockBatteries) : null;
    }

    /**
     * Attempts to resolve the hardware Bluetooth MAC address of an InputDevice using Android reflection.
     * Checks InputDevice.getBluetoothAddress() (Android 14+ / AOSP) and InputManager.getInputDeviceBluetoothAddress(int) (Android 12+).
     */
    public static String resolveInputDeviceBluetoothAddress(Context context, InputDevice dev) {
        if (dev == null) return null;
        ensureReflectionInitialized();
        if (sInputDeviceGetBtAddress != null) {
            try {
                Object addr = sInputDeviceGetBtAddress.invoke(dev);
                if (addr instanceof String && !((String) addr).trim().isEmpty()) {
                    return ((String) addr).trim();
                }
            } catch (Throwable ignored) {}
        }

        if (context != null && sInputManagerGetBtAddress != null) {
            try {
                android.hardware.input.InputManager im =
                        (android.hardware.input.InputManager) context.getSystemService(Context.INPUT_SERVICE);
                if (im != null) {
                    Object addr = sInputManagerGetBtAddress.invoke(im, dev.getId());
                    if (addr instanceof String && !((String) addr).trim().isEmpty()) {
                        return ((String) addr).trim();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Reads battery percent (0..100) from a connected Bluetooth device via reflection fallback
     * when InputDevice.getBatteryState() is unsupported or returns unknown (-1).
     *
     * <p>Protected against missing BLUETOOTH permissions or TV vendor stubs via try/catch Throwable.</p>
     */
    public static int queryBluetoothBatteryFallback(Context context, String descriptor, String deviceName) {
        return queryBluetoothBatteryFallback(context, null, descriptor, deviceName, new HashSet<>());
    }

    /**
     * Reads battery percent (0..100) from a connected Bluetooth device via reflection fallback
     * when InputDevice.getBatteryState() is unsupported or returns unknown (-1).
     *
     * <p>Avoids collisions between multiple gamepads sharing the same name by tracking claimed
     * Bluetooth device MAC addresses and prioritizing exact hardware MAC matching.</p>
     */
    public static int queryBluetoothBatteryFallback(
            Context context,
            InputDevice dev,
            String descriptor,
            String deviceName,
            Set<String> claimedBtAddresses
    ) {
        String devMac = resolveInputDeviceBluetoothAddress(context, dev);
        String descLower = descriptor != null ? descriptor.toLowerCase(Locale.ROOT) : "";
        String targetNameLower = deviceName != null ? deviceName.toLowerCase(Locale.ROOT) : "";

        if (sMockBluetoothBatteries != null) {
            String devMacKey = devMac != null ? devMac.toLowerCase(Locale.ROOT) : null;
            String devMacNoColons = devMacKey != null ? devMacKey.replace(":", "") : null;
            String descKey = descriptor != null ? descriptor.toLowerCase(Locale.ROOT) : null;
            String nameKey = deviceName != null ? deviceName.toLowerCase(Locale.ROOT) : null;

            for (Map.Entry<String, Integer> entry : sMockBluetoothBatteries.entrySet()) {
                if (entry.getKey() == null) continue;
                String k = entry.getKey().toLowerCase(Locale.ROOT);
                String kNoColons = k.replace(":", "");
                boolean match = false;
                if (devMacKey != null && (k.equals(devMacKey) || kNoColons.equals(devMacNoColons))) {
                    match = true;
                } else if (descKey != null && (k.equals(descKey) || descKey.contains(k) || descKey.contains(kNoColons))) {
                    match = true;
                } else if (nameKey != null && k.equals(nameKey)) {
                    match = true;
                }
                if (match) {
                    if (!shouldSkipClaimedBluetoothAddress(k, descriptor, claimedBtAddresses)) {
                        if (claimedBtAddresses != null) claimedBtAddresses.add(k);
                        return entry.getValue();
                    }
                }
            }
        }
        if (sMockBluetoothBattery != null) {
            return sMockBluetoothBattery;
        }
        if (context == null) return -1;
        try {
            BluetoothAdapter adapter = null;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    if (bm != null) adapter = bm.getAdapter();
                } catch (Throwable ignored) {}
            }
            if (adapter == null) {
                try {
                    adapter = BluetoothAdapter.getDefaultAdapter();
                } catch (Throwable ignored) {}
            }
            if (adapter == null || !adapter.isEnabled()) {
                return -1;
            }

            Set<BluetoothDevice> bonded = null;
            try {
                bonded = adapter.getBondedDevices();
            } catch (Throwable ignored) {}
            if (bonded == null || bonded.isEmpty()) {
                return -1;
            }

            BluetoothDevice bestMatch = null;
            List<BluetoothDevice> connectedUnclaimed = new ArrayList<>();

            for (BluetoothDevice btDev : bonded) {
                if (btDev == null) continue;
                String mac = null;
                try {
                    mac = btDev.getAddress();
                } catch (Throwable ignored) {}
                String macNorm = mac != null ? mac.toLowerCase(Locale.ROOT) : "";

                if (!macNorm.isEmpty() && shouldSkipClaimedBluetoothAddress(macNorm, descriptor, claimedBtAddresses)) {
                    continue; // Already claimed by another connected gamepad
                }

                // Exclude audio/video (headphones, soundbars), phones, and wearable devices from gamepad matching
                try {
                    android.bluetooth.BluetoothClass bc = btDev.getBluetoothClass();
                    if (bc != null) {
                        int major = bc.getMajorDeviceClass();
                        if (major == 0x0400 || major == 0x0200 || major == 0x0700) {
                            continue;
                        }
                    }
                } catch (Throwable ignored) {}

                ensureReflectionInitialized();
                boolean isConnected = false;
                if (sBtDeviceIsConnected != null) {
                    try {
                        isConnected = Boolean.TRUE.equals(sBtDeviceIsConnected.invoke(btDev));
                    } catch (Throwable ignored) {}
                }
                if (!isConnected) continue;

                connectedUnclaimed.add(btDev);

                // Check 1: Direct MAC match against resolved hardware address
                if (devMac != null && !devMac.isEmpty()) {
                    String devMacNorm = devMac.toLowerCase(Locale.ROOT);
                    if (devMacNorm.equals(macNorm) || devMacNorm.replace(":", "").equals(macNorm.replace(":", ""))) {
                        bestMatch = btDev;
                        break;
                    }
                }

                // Check 2: MAC match against descriptor string
                if (mac != null && !mac.isEmpty()) {
                    String macNoColons = macNorm.replace(":", "");
                    if (descLower.contains(macNorm) || descLower.contains(macNoColons)) {
                        bestMatch = btDev;
                        break;
                    }
                }
            }

            // Fallback: match by deviceName among connected, unclaimed Bluetooth devices
            if (bestMatch == null && !targetNameLower.isEmpty() && !connectedUnclaimed.isEmpty()) {
                // First pass: exact match
                for (BluetoothDevice btDev : connectedUnclaimed) {
                    String btName = null;
                    try {
                        btName = btDev.getName();
                    } catch (Throwable ignored) {}
                    if (btName != null && targetNameLower.equalsIgnoreCase(btName.trim())) {
                        bestMatch = btDev;
                        break;
                    }
                }
                // Second pass: relaxed substring match
                if (bestMatch == null) {
                    for (BluetoothDevice btDev : connectedUnclaimed) {
                        String btName = null;
                        try {
                            btName = btDev.getName();
                        } catch (Throwable ignored) {}
                        if (btName != null && !btName.trim().isEmpty()) {
                            String btNameLower = btName.trim().toLowerCase(Locale.ROOT);
                            if (targetNameLower.contains(btNameLower) || btNameLower.contains(targetNameLower)) {
                                bestMatch = btDev;
                                break;
                            }
                        }
                    }
                }
            }

            if (bestMatch != null) {
                String matchMac = null;
                try {
                    matchMac = bestMatch.getAddress();
                } catch (Throwable ignored) {}
                if (matchMac != null && claimedBtAddresses != null) {
                    claimedBtAddresses.add(matchMac.toLowerCase(Locale.ROOT));
                }

                if (sBtDeviceGetBatteryLevel != null) {
                    try {
                        Object val = sBtDeviceGetBatteryLevel.invoke(bestMatch);
                        if (val instanceof Number) {
                            int lvl = ((Number) val).intValue();
                            if (lvl >= 0 && lvl <= 100) {
                                return lvl;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Queries connected physical game controllers, filtering out virtual bridges and remote controls.
     * Populates each controller's descriptor, device name, custom tag (if configured), and live battery state.
     *
     * <p>Supports both Bluetooth controllers and 2.4G USB dongles (e.g. GameSir Nova Lite 3537:1040)
     * as well as wired XInput controllers. Deduplicates by hardware descriptor so distinct USB ports
     * retain their individual tags.</p>
     */
    public static List<ControllerInfo> getConnectedPhysicalControllers(Context context) {
        if (mockConnectedControllers != null) {
            return mockConnectedControllers;
        }
        List<ControllerInfo> list = new ArrayList<>();
        if (context == null) return list;

        try {
            // 1. Discover attached XInput UsbDevices
            List<UsbDevice> xinputUsbList = new ArrayList<>();
            try {
                UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if (usbManager != null) {
                    Map<String, UsbDevice> devMap = usbManager.getDeviceList();
                    if (devMap != null) {
                        for (UsbDevice ud : devMap.values()) {
                            if (isXInputUsbDevice(ud)) {
                                xinputUsbList.add(ud);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            List<UsbDevice> unmatchedUsb = new ArrayList<>(xinputUsbList);
            Set<String> seenDescriptors = new HashSet<>();
            Set<String> claimedBtAddresses = new HashSet<>();

            int[] deviceIds = InputDevice.getDeviceIds();
            if (deviceIds != null) {
                for (int id : deviceIds) {
                    InputDevice dev = InputDevice.getDevice(id);
                    if (dev == null) continue;
                    if (dev.isVirtual()) continue;

                    String rawDevName = dev.getName() != null ? dev.getName().trim() : "";
                    String lowerName = rawDevName.toLowerCase(Locale.ROOT);
                    if (lowerName.contains("virtual")
                            || lowerName.contains("cec")
                            || lowerName.contains("xiaomi")) {
                        continue;
                    }

                    boolean isMatchedXInputUsb = false;
                    if (dev.getVendorId() != 0 && dev.getProductId() != 0) {
                        for (UsbDevice ud : xinputUsbList) {
                            if (ud.getVendorId() == dev.getVendorId() && ud.getProductId() == dev.getProductId()) {
                                isMatchedXInputUsb = true;
                                break;
                            }
                        }
                    }

                    int sources = dev.getSources();
                    boolean isGamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                            || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                    boolean hasButtons = false;
                    if (isGamepad) {
                        boolean[] keys = dev.hasKeys(
                                android.view.KeyEvent.KEYCODE_BUTTON_A,
                                android.view.KeyEvent.KEYCODE_BUTTON_B,
                                android.view.KeyEvent.KEYCODE_BUTTON_X,
                                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                                android.view.KeyEvent.KEYCODE_BUTTON_L1,
                                android.view.KeyEvent.KEYCODE_BUTTON_R1,
                                android.view.KeyEvent.KEYCODE_BUTTON_MODE);
                        int keyHits = 0;
                        if (keys != null) {
                            for (boolean k : keys) {
                                if (k) keyHits++;
                            }
                        }
                        hasButtons = keyHits >= 2 || isKnownGamepadVendor(dev.getVendorId());
                    }

                    if (!GamingZone.isPhysicalGameController(rawDevName, dev.isVirtual(), isGamepad, hasButtons, isMatchedXInputUsb)) {
                        continue;
                    }

                    SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    DeviceHardwareLocation hwLoc = lookupHardwareLocation(id, dev.getDescriptor());
                    if (hwLoc == null) {
                        hwLoc = resolveSingleSysfsHardwareLocation(dev.getVendorId(), dev.getProductId());
                    }
                    if (hwLoc == null) {
                        // Trigger background location refresh whenever any physical USB/BT controller lacks cached topology
                        refreshHardwareLocationsAsync(context, null);
                    }
                    String usbPortId = null;
                    String portLabel = null;
                    if (hwLoc != null && hwLoc.location.toLowerCase(Locale.ROOT).contains("usb")) {
                        usbPortId = extractUsbPortIdentifier(hwLoc.location);
                        portLabel = extractUsbPortLabel(hwLoc.location);
                    }

                    String cleanName = cleanDeviceName(rawDevName);
                    String btAddress = resolveInputDeviceBluetoothAddress(context, dev);
                    if ((btAddress == null || btAddress.isEmpty()) && hwLoc != null) {
                        if (!hwLoc.uniqueId.isEmpty() && hwLoc.uniqueId.contains(":")) {
                            btAddress = hwLoc.uniqueId;
                        } else if (!hwLoc.bluetoothAddress.isEmpty() && hwLoc.bluetoothAddress.contains(":")) {
                            btAddress = hwLoc.bluetoothAddress;
                        }
                    }
                    String descriptor = resolveStableDescriptor(dev.getDescriptor(), btAddress, usbPortId, cleanName, dev.getVendorId(), dev.getProductId());

                    // Live battery reading (API 31+) via cached reflection handles
                    int batteryPercent = -1;
                    boolean charging = false;
                    if (Build.VERSION.SDK_INT >= 31) {
                        ensureReflectionInitialized();
                        if (sInputDeviceGetBatteryState != null) {
                            try {
                                Object bs = sInputDeviceGetBatteryState.invoke(dev);
                                if (bs != null) {
                                    ensureBatteryStateReflection(bs.getClass());
                                    boolean isPresent = sBatteryStateIsPresent != null
                                            && Boolean.TRUE.equals(sBatteryStateIsPresent.invoke(bs));
                                    if (isPresent) {
                                        if (sBatteryStateGetCapacity != null) {
                                            Object capObj = sBatteryStateGetCapacity.invoke(bs);
                                            if (capObj instanceof Number) {
                                                float cap = ((Number) capObj).floatValue();
                                                if (!Float.isNaN(cap) && cap >= 0f) {
                                                    if (cap <= 1.0f) {
                                                        batteryPercent = Math.round(cap * 100f);
                                                    } else {
                                                        batteryPercent = Math.round(cap);
                                                    }
                                                    batteryPercent = Math.max(0, Math.min(100, batteryPercent));
                                                }
                                            }
                                        }
                                        if (sBatteryStateGetStatus != null) {
                                            Object statObj = sBatteryStateGetStatus.invoke(bs);
                                            if (statObj instanceof Number) {
                                                charging = isBatteryChargingOrOnPower(((Number) statObj).intValue());
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    // Fallback to Bluetooth battery level BEFORE marking descriptor's own MAC as claimed
                    if (batteryPercent < 0) {
                        int btBatt = queryBluetoothBatteryFallback(context, dev, descriptor, cleanName, claimedBtAddresses);
                        if (btBatt >= 0) {
                            batteryPercent = btBatt;
                        }
                    }

                    if (!seenDescriptors.add(descriptor)) {
                        // If a second sub-node (e.g. Touchpad/Gamepad split) shares this descriptor and carries battery/power, merge it!
                        if (batteryPercent >= 0 || charging) {
                            for (int idx = 0; idx < list.size(); idx++) {
                                ControllerInfo existing = list.get(idx);
                                if (descriptor.equals(existing.descriptor)) {
                                    int mergedPct = existing.batteryPercent >= 0 ? existing.batteryPercent : batteryPercent;
                                    boolean mergedChg = existing.charging || charging;
                                    list.set(idx, existing.withBattery(mergedPct, mergedChg));
                                    break;
                                }
                            }
                        }
                        continue;
                    }
                    if (descriptor.startsWith("bt:")) {
                        claimedBtAddresses.add(descriptor.substring(3).toLowerCase(Locale.ROOT));
                    }

                    // An InputDevice representing an attached XInput UsbDevice has been accepted:
                    if (dev.getVendorId() != 0 && dev.getProductId() != 0) {
                        for (int i = 0; i < unmatchedUsb.size(); i++) {
                            UsbDevice ud = unmatchedUsb.get(i);
                            if (ud.getVendorId() == dev.getVendorId() && ud.getProductId() == dev.getProductId()) {
                                unmatchedUsb.remove(i);
                                break;
                            }
                        }
                    }

                    boolean tagged = migrateAndCheckTagged(context, sp, descriptor, dev.getDescriptor(), btAddress,
                            dev.getVendorId(), dev.getProductId(), cleanName);
                    if (!tagged && portLabel != null && !portLabel.isEmpty() && !cleanName.contains(portLabel)) {
                        cleanName = cleanName + " (" + portLabel + ")";
                    }
                    String customName = getCustomName(context, descriptor);
                    int color = getAssignedColor(context, descriptor);

                    list.add(new ControllerInfo(
                            id,
                            descriptor,
                            cleanName,
                            customName,
                            color,
                            tagged,
                            batteryPercent,
                            charging,
                            true
                    ));
                }
            }

            // Fallback synthesis for any attached XInput UsbDevice that had no corresponding InputDevice
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            for (UsbDevice ud : unmatchedUsb) {
                String rawName = null;
                try {
                    rawName = ud.getProductName();
                } catch (Throwable ignored) {}
                if (rawName == null || rawName.trim().isEmpty()) {
                    try {
                        rawName = ud.getManufacturerName();
                    } catch (Throwable ignored) {}
                }
                if (rawName == null || rawName.trim().isEmpty()) {
                    rawName = "XInput Gamepad";
                }
                String cleanName = cleanDeviceName(rawName);
                String usbPortPath = ud.getDeviceName() != null ? ud.getDeviceName() : ("usb_" + ud.getDeviceId());
                String usbPortId = extractUsbPortIdentifier(usbPortPath);
                String portLabel = extractUsbPortLabel(usbPortPath);
                String descriptor = resolveStableDescriptor(null, null, usbPortId, cleanName, ud.getVendorId(), ud.getProductId());

                if (!seenDescriptors.add(descriptor)) {
                    continue;
                }

                boolean tagged = migrateAndCheckTagged(context, sp, descriptor, null, null,
                        ud.getVendorId(), ud.getProductId(), cleanName);
                if (!tagged && portLabel != null && !portLabel.isEmpty() && !cleanName.contains(portLabel)) {
                    cleanName = cleanName + " (" + portLabel + ")";
                }
                String customName = getCustomName(context, descriptor);
                int color = getAssignedColor(context, descriptor);

                list.add(new ControllerInfo(
                        -1,
                        descriptor,
                        cleanName,
                        customName,
                        color,
                        tagged,
                        -1,
                        false,
                        true
                ));
            }

            // Fallback synthesis for active Bluetooth gamepads connected at ACL/HID level whose InputDevice is still initializing
            appendBondedBluetoothControllers(context, sp, list, seenDescriptors, claimedBtAddresses, true);
        } catch (RuntimeException ignored) {
        }
        return list;
    }

    /**
     * Returns ALL known physical game controllers:
     * <ol>
     *   <li>Currently connected USB / 2.4G / Bluetooth controllers ({@code connected = true}).</li>
     *   <li>Paired (bonded) Bluetooth gamepads in {@link BluetoothAdapter#getBondedDevices()} even when powered off / sleeping ({@code connected = false}).</li>
     *   <li>Previously configured USB controllers saved in {@code SharedPreferences} while unplugged/off ({@code connected = false}).</li>
     * </ol>
     */
    public static List<ControllerInfo> getAllKnownControllers(Context context) {
        List<ControllerInfo> active = getConnectedPhysicalControllers(context);
        List<ControllerInfo> all = new ArrayList<>(active);
        if (context == null || mockConnectedControllers != null) {
            return all;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> seenDescriptors = new HashSet<>();
            Set<String> claimedBt = new HashSet<>();
            for (ControllerInfo ci : active) {
                seenDescriptors.add(ci.descriptor);
                if (ci.descriptor.startsWith("bt:")) {
                    claimedBt.add(ci.descriptor.substring(3));
                }
            }

            // Include paired (bonded) Bluetooth gamepads even when sleeping / disconnected
            appendBondedBluetoothControllers(context, sp, all, seenDescriptors, claimedBt, false);

            // Include saved tagged USB / wired controllers even when powered off
            Map<String, ?> prefsMap = sp.getAll();
            if (prefsMap != null) {
                for (Map.Entry<String, ?> entry : prefsMap.entrySet()) {
                    String k = entry.getKey();
                    if (k == null || !k.startsWith(KEY_PREFIX_TAGGED) || !Boolean.TRUE.equals(entry.getValue())) {
                        continue;
                    }
                    String desc = k.substring(KEY_PREFIX_TAGGED.length());
                    if (desc.isEmpty() || Boolean.TRUE.equals(prefsMap.get("ctrl_cleared_" + desc))) {
                        continue;
                    }
                    // Skip legacy ephemeral ":usb-" keys
                    if (desc.startsWith("usbport:") && desc.contains(":usb-")) {
                        continue;
                    }
                    if (!seenDescriptors.add(desc)) {
                        continue;
                    }
                    String hwName = sp.getString(KEY_PREFIX_HWNAME + desc, "");
                    if (hwName == null || hwName.trim().isEmpty()) {
                        hwName = desc.startsWith("bt:") ? "Wireless Controller" : "USB Controller";
                    }
                    String customName = getCustomName(context, desc);
                    int color = getAssignedColor(context, desc);
                    all.add(new ControllerInfo(
                            -1,
                            desc,
                            hwName,
                            customName,
                            color,
                            true,
                            -1,
                            false,
                            false
                    ));
                }
            }
        } catch (Throwable ignored) {
        }
        return all;
    }

    private static void appendBondedBluetoothControllers(
            Context context,
            SharedPreferences sp,
            List<ControllerInfo> outList,
            Set<String> seenDescriptors,
            Set<String> claimedBtAddresses,
            boolean requireConnected
    ) {
        try {
            BluetoothAdapter adapter = null;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                    if (bm != null) adapter = bm.getAdapter();
                } catch (Throwable ignored) {}
            }
            if (adapter == null) {
                try {
                    adapter = BluetoothAdapter.getDefaultAdapter();
                } catch (Throwable ignored) {}
            }
            if (adapter == null || !adapter.isEnabled()) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return;

            for (BluetoothDevice btDev : bonded) {
                if (btDev == null) continue;
                String mac = null;
                String name = null;
                try {
                    mac = btDev.getAddress();
                    name = btDev.getName();
                } catch (Throwable ignored) {}
                if (mac == null || mac.trim().isEmpty()) continue;
                String macLower = mac.trim().toLowerCase(Locale.ROOT);
                if (claimedBtAddresses != null && claimedBtAddresses.contains(macLower)) {
                    continue;
                }

                int major = 0x1F00;
                int devCls = 0x1F00;
                try {
                    android.bluetooth.BluetoothClass bc = btDev.getBluetoothClass();
                    if (bc != null) {
                        major = bc.getMajorDeviceClass();
                        devCls = bc.getDeviceClass();
                    }
                } catch (Throwable ignored) {}

                String desc = canonicalBluetoothDescriptor(macLower);
                boolean alreadyTagged = isTagged(context, desc) || isTagged(context, mac.trim());
                if (!alreadyTagged && !isBluetoothGamepadCandidate(name, major, devCls)) {
                    continue;
                }

                ensureReflectionInitialized();
                boolean btConnected = false;
                if (sBtDeviceIsConnected != null) {
                    try {
                        btConnected = Boolean.TRUE.equals(sBtDeviceIsConnected.invoke(btDev));
                    } catch (Throwable ignored) {}
                }

                if (requireConnected && !btConnected) {
                    continue;
                }
                if (!seenDescriptors.add(desc)) {
                    continue;
                }
                if (claimedBtAddresses != null) {
                    claimedBtAddresses.add(macLower);
                }

                String cleanName = cleanDeviceName(name != null && !name.trim().isEmpty() ? name : "Wireless Controller");
                boolean tagged = migrateAndCheckTagged(context, sp, desc, null, mac, 0, 0, cleanName);
                String customName = getCustomName(context, desc);
                int color = getAssignedColor(context, desc);

                int batt = -1;
                if (btConnected && sBtDeviceGetBatteryLevel != null) {
                    try {
                        Object v = sBtDeviceGetBatteryLevel.invoke(btDev);
                        if (v instanceof Number) {
                            int lvl = ((Number) v).intValue();
                            if (lvl >= 0 && lvl <= 100) batt = lvl;
                        }
                    } catch (Throwable ignored) {}
                }

                outList.add(new ControllerInfo(
                        -1,
                        desc,
                        cleanName,
                        customName,
                        color,
                        tagged,
                        batt,
                        false,
                        btConnected
                ));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reads {@code /sys/class/input/input*} synchronously in {@code <0.3ms} when exactly one physical input node
     * matches {@code (vendorId, productId)}, resolving its exact USB {@code phys} port or Bluetooth {@code uniq} MAC
     * immediately without waiting for {@code dumpsys input}. Returns {@code null} when zero or multiple identical
     * nodes are attached simultaneously (deferring to {@code sHardwareLocations} keyed by exact {@code InputDevice} ID).
     */
    private static volatile boolean sSysfsInputUnreadable = false;

    private static DeviceHardwareLocation resolveSingleSysfsHardwareLocation(int vendorId, int productId) {
        if (sSysfsInputUnreadable || (vendorId == 0 && productId == 0)) return null;
        try {
            java.io.File sysInput = new java.io.File("/sys/class/input");
            java.io.File[] entries = sysInput.listFiles();
            if (entries == null) {
                sSysfsInputUnreadable = true;
                return null;
            }
            String wantVendor = String.format(Locale.ROOT, "%04x", vendorId & 0xFFFF);
            String wantProduct = String.format(Locale.ROOT, "%04x", productId & 0xFFFF);
            DeviceHardwareLocation match = null;
            String matchPort = "";
            String matchBt = "";
            for (java.io.File entry : entries) {
                String name = entry.getName();
                if (!name.startsWith("input")) continue;
                String v = readTinySysfsLine(new java.io.File(entry, "id/vendor"));
                String p = readTinySysfsLine(new java.io.File(entry, "id/product"));
                if (!wantVendor.equalsIgnoreCase(v) || !wantProduct.equalsIgnoreCase(p)) continue;
                String nodeName = readTinySysfsLine(new java.io.File(entry, "name"));
                String phys = readTinySysfsLine(new java.io.File(entry, "phys"));
                String uniq = readTinySysfsLine(new java.io.File(entry, "uniq"));
                String btMac = isBluetoothMacAddress(uniq) ? uniq : (isBluetoothMacAddress(phys) ? phys : "");
                String usbPort = extractUsbPortIdentifier(phys);
                if (match != null) {
                    boolean sameSameBt = !matchBt.isEmpty() && matchBt.equalsIgnoreCase(btMac);
                    boolean sameUsbPort = !matchPort.isEmpty() && matchPort.equalsIgnoreCase(usbPort);
                    if (!sameSameBt && !sameUsbPort) {
                        // Two distinct physical controllers connected simultaneously: require exact InputDevice ID mapping
                        return null;
                    }
                    continue;
                }
                match = new DeviceHardwareLocation(-1, nodeName, phys, uniq, btMac);
                matchPort = usbPort;
                matchBt = btMac;
            }
            return match;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readTinySysfsLine(java.io.File f) {
        if (f == null || !f.isFile()) return "";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[128];
            int n = fis.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Decides whether a legacy controller tag ({@code legacyKey}) may be migrated onto a newly resolved canonical
     * hardware descriptor ({@code targetDescriptor}).
     *
     * <p><b>Why:</b> Identical controller models (e.g. a Blue and a Pink GameSir / DualShock controller) share the
     * exact same non-unique EventHub SHA-1 hash {@code InputDevice.getDescriptor()}. Once ANY canonical {@code bt:}
     * or permanent {@code usbport:} controller is already tagged in {@code controller_prefs}, a leftover raw EventHub
     * hash MUST NEVER be copied onto a second controller, preventing Pink from inheriting Blue's color/name.
     */
    public static boolean canMigrateLegacyControllerTag(
            Map<String, ?> allPrefs,
            String targetDescriptor,
            String legacyKey,
            int vendorId,
            int productId
    ) {
        if (allPrefs == null || targetDescriptor == null || legacyKey == null) return false;
        String target = targetDescriptor.trim();
        String legacy = legacyKey.trim();
        if (target.isEmpty() || legacy.isEmpty() || target.equals(legacy)) return false;
        if (!Boolean.TRUE.equals(allPrefs.get(KEY_PREFIX_TAGGED + legacy))) return false;

        // Exact Bluetooth MAC migration ("AA:BB:CC:DD:EE:FF" -> "bt:aa:bb:cc:dd:ee:ff") only matches its own MAC
        if (isBluetoothMacAddress(legacy)) {
            return canonicalBluetoothDescriptor(legacy).equalsIgnoreCase(target);
        }

        // Non-MAC legacy EventHub SHA-1 hash: block if ANY canonical bt: or permanent usbport: tag already exists!
        for (Map.Entry<String, ?> e : allPrefs.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith(KEY_PREFIX_TAGGED) || !Boolean.TRUE.equals(e.getValue())) continue;
            String existingDesc = k.substring(KEY_PREFIX_TAGGED.length());
            if (existingDesc.equals(target) || existingDesc.equals(legacy)) continue;
            if (existingDesc.startsWith("bt:")
                    || (existingDesc.startsWith("usbport:") && !existingDesc.contains(":usb-"))) {
                return false;
            }
        }
        return true;
    }

    private static void removeLegacyTagKeys(SharedPreferences sp, String legacyKey) {
        if (sp == null || legacyKey == null || legacyKey.trim().isEmpty()) return;
        String k = legacyKey.trim();
        sp.edit()
                .remove(KEY_PREFIX_NAME + k)
                .remove(KEY_PREFIX_COLOR + k)
                .remove(KEY_PREFIX_TAGGED + k)
                .remove(KEY_PREFIX_HWNAME + k)
                .apply();
    }

    private static boolean migrateAndCheckTagged(
            Context context,
            SharedPreferences sp,
            String descriptor,
            String rawDescriptor,
            String btAddress,
            int vendorId,
            int productId,
            String cleanHardwareName
    ) {
        if (isTagged(context, descriptor)) {
            if (cleanHardwareName != null && !cleanHardwareName.isEmpty()
                    && sp.getString(KEY_PREFIX_HWNAME + descriptor, "").isEmpty()) {
                sp.edit().putString(KEY_PREFIX_HWNAME + descriptor, cleanHardwareName).apply();
            }
            return true;
        }
        if (sp.getBoolean("ctrl_cleared_" + descriptor, false)) {
            return false;
        }
        // 1. One-shot migration from raw EventHub SHA-1 descriptor ONLY when zero canonical controllers exist yet
        if (rawDescriptor != null && !rawDescriptor.trim().isEmpty()
                && canMigrateLegacyControllerTag(sp.getAll(), descriptor, rawDescriptor, vendorId, productId)) {
            saveTag(context, descriptor, getCustomName(context, rawDescriptor), getAssignedColor(context, rawDescriptor), cleanHardwareName);
            removeLegacyTagKeys(sp, rawDescriptor);
            return true;
        }
        // 2. One-shot migration from legacy non-prefixed Bluetooth MAC matching this exact controller's MAC
        if (btAddress != null && !btAddress.trim().isEmpty()) {
            String upperMac = btAddress.trim().toUpperCase(Locale.ROOT);
            String lowerMac = btAddress.trim().toLowerCase(Locale.ROOT);
            if (!upperMac.equals(descriptor) && isTagged(context, upperMac)) {
                saveTag(context, descriptor, getCustomName(context, upperMac), getAssignedColor(context, upperMac), cleanHardwareName);
                removeLegacyTagKeys(sp, upperMac);
                return true;
            }
            if (!lowerMac.equals(descriptor) && isTagged(context, lowerMac)) {
                saveTag(context, descriptor, getCustomName(context, lowerMac), getAssignedColor(context, lowerMac), cleanHardwareName);
                removeLegacyTagKeys(sp, lowerMac);
                return true;
            }
        }
        // 3. Recover orphaned ephemeral USB device-number tag ("usbport:<vid>:<pid>:usb-1-30")
        if (vendorId != 0 || productId != 0) {
            String orphan = findOrphanedEphemeralUsbTagKey(sp.getAll(), vendorId, productId);
            if (orphan != null && !orphan.equals(descriptor)) {
                String prevName = getCustomName(context, orphan);
                int prevColor = getAssignedColor(context, orphan);
                saveTag(context, descriptor, prevName, prevColor, cleanHardwareName);
                sp.edit()
                        .remove(KEY_PREFIX_NAME + orphan)
                        .remove(KEY_PREFIX_COLOR + orphan)
                        .remove(KEY_PREFIX_TAGGED + orphan)
                        .apply();
                return true;
            }
        }
        return false;
    }
}
