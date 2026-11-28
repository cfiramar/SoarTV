package io.soartv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Modular visibility & navigation controller for optional SoarTV Top-Bar tabs
 * ({@code Gaming}, {@code Reels}, and {@code Smart Home}).
 *
 * <p><b>Why:</b> Users who do not own smart-home devices or do not stream PC games to their TV
 * should get a clean, clutter-free launcher without unused tabs, while allowing instant late
 * activation via {@code config.properties} ({@code enable_gaming=true}, {@code enable_smarthome=true}),
 * TV Settings ({@code Settings -> Home & Appearance}), or an AI setup assistant.
 *
 * <p><b>What:</b> Resolves tri-state tab modes ({@code "auto"}, {@code "true"}, {@code "false"}) across
 * runtime preferences, build configuration, and configured device/key presence, builds the 7-slot
 * Top-Bar visibility mask, and provides deterministic D-Pad stepping across visible tabs.
 */
public final class FeatureTabs {
    private FeatureTabs() {}

    public static final String MODE_AUTO = "auto";
    public static final String MODE_TRUE = "true";
    public static final String MODE_FALSE = "false";

    private static final String PREF_GAMING_MODE = "featureGamingMode";
    private static final String PREF_SMARTHOME_MODE = "featureSmartHomeMode";

    /** Normalizes any raw config/preference string into {@code "true"}, {@code "false"}, or {@code "auto"}. */
    public static String normalizeMode(String raw) {
        if (raw == null) return MODE_AUTO;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(clean) || "on".equals(clean) || "yes".equals(clean) || "1".equals(clean) || "shown".equals(clean)) {
            return MODE_TRUE;
        }
        if ("false".equals(clean) || "off".equals(clean) || "no".equals(clean) || "0".equals(clean) || "hidden".equals(clean)) {
            return MODE_FALSE;
        }
        return MODE_AUTO;
    }

    /**
     * Resolves whether an optional tab is enabled given:
     * <ol>
     *   <li>{@code runtimePrefMode}: User choice saved on the TV ({@code "true"} / {@code "false"} / {@code "auto"})</li>
     *   <li>{@code buildConfigMode}: Build-time {@code config.properties} value ({@code "true"} / {@code "false"} / {@code "auto"})</li>
     *   <li>{@code hasConfiguredHardwareOrKeys}: Auto-detected presence of paired PC or Smart-Home keys/devices</li>
     * </ol>
     */
    public static boolean resolveFeatureEnabled(
            String runtimePrefMode,
            String buildConfigMode,
            boolean hasConfiguredHardwareOrKeys) {
        String rt = normalizeMode(runtimePrefMode);
        if (MODE_TRUE.equals(rt)) return true;
        if (MODE_FALSE.equals(rt)) return false;

        String cfg = normalizeMode(buildConfigMode);
        if (MODE_TRUE.equals(cfg)) return true;
        if (MODE_FALSE.equals(cfg)) return false;

        return hasConfiguredHardwareOrKeys;
    }

    /** Returns the user's runtime override mode for the Gaming tab ({@code "auto"}, {@code "true"}, or {@code "false"}). */
    public static String getGamingModePref() {
        return getGamingModePref(null);
    }

    /** Returns the runtime override mode for the Gaming tab, checking both {@link Prefs} and {@link Secrets}. */
    public static String getGamingModePref(Context c) {
        try {
            String p = Prefs.rawFeatureMode(PREF_GAMING_MODE);
            if (!MODE_AUTO.equals(p)) return p;
        } catch (RuntimeException ignored) {
        }
        if (c != null) {
            try {
                String pushed = Secrets.getSecret(c, "enable_gaming");
                if (nonEmpty(pushed)) return normalizeMode(pushed);
            } catch (RuntimeException ignored) {
            }
        }
        return MODE_AUTO;
    }

    /** Updates the user's runtime override mode for the Gaming tab on the TV. */
    public static void setGamingModePref(String mode) {
        Prefs.setRawFeatureMode(PREF_GAMING_MODE, normalizeMode(mode));
        if (Prefs.context() != null) {
            try {
                CloudSyncManager.get(Prefs.context()).recordLocalFieldChange(
                        CloudSyncManager.Scope.TV_DEVICE, "enable_gaming");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Returns the user's runtime override mode for the Smart Home tab ({@code "auto"}, {@code "true"}, or {@code "false"}). */
    public static String getSmartHomeModePref() {
        return getSmartHomeModePref(null);
    }

    /** Returns the runtime override mode for the Smart Home tab, checking both {@link Prefs} and {@link Secrets}. */
    public static String getSmartHomeModePref(Context c) {
        try {
            String p = Prefs.rawFeatureMode(PREF_SMARTHOME_MODE);
            if (!MODE_AUTO.equals(p)) return p;
        } catch (RuntimeException ignored) {
        }
        if (c != null) {
            try {
                String pushed = Secrets.getSecret(c, "enable_smarthome");
                if (nonEmpty(pushed)) return normalizeMode(pushed);
            } catch (RuntimeException ignored) {
            }
        }
        return MODE_AUTO;
    }

    /** Updates the user's runtime override mode for the Smart Home tab on the TV. */
    public static void setSmartHomeModePref(String mode) {
        Prefs.setRawFeatureMode(PREF_SMARTHOME_MODE, normalizeMode(mode));
        if (Prefs.context() != null) {
            try {
                CloudSyncManager.get(Prefs.context()).recordLocalFieldChange(
                        CloudSyncManager.Scope.TV_DEVICE, "enable_smarthome");
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Returns {@code true} when any PC gaming host, MAC, or pairing token is configured. */
    public static boolean hasGamingConfiguration(Context c) {
        if (nonEmpty(Config.GAMING_PC_IP) || nonEmpty(Config.GAMING_PC_MAC)) return true;
        try {
            if (nonEmpty(Prefs.gamingPcToken())) return true;
        } catch (RuntimeException ignored) {
        }
        if (c != null) {
            try {
                SharedPreferences gp = c.getSharedPreferences("gaming_zone_prefs", Context.MODE_PRIVATE);
                if (nonEmpty(gp.getString("laptop_ip_override", ""))
                        || nonEmpty(gp.getString("cached_sunshine_uuid", ""))
                        || nonEmpty(gp.getString("cached_games_json", ""))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** Returns {@code true} when any Smart-Home light bulb, AC, or boiler credential/IP is configured. */
    public static boolean hasSmartHomeConfiguration(Context c) {
        if (nonEmpty(Config.WIZ_LIGHT_IPS)
                || nonEmpty(Config.SENSIBO_DEVICE_ID)
                || nonEmpty(Config.TUYA_ACCESS_ID)
                || nonEmpty(Config.TUYA_DEVICE_ID)
                || nonEmpty(Config.SWITCHER_DEVICE_IP)
                || nonEmpty(Config.SWITCHER_DEVICE_ID)) {
            return true;
        }
        if (c != null) {
            try {
                if (nonEmpty(Secrets.getSecret(c, Secrets.SENSIBO_API_KEY))
                        || nonEmpty(Secrets.getSecret(c, Secrets.TUYA_ACCESS_SECRET))) {
                    return true;
                }
                SharedPreferences sp = c.getSharedPreferences("smart_home_prefs", Context.MODE_PRIVATE);
                if (nonEmpty(sp.getString("wiz_known_bulbs", ""))
                        || nonEmpty(sp.getString("custom_wiz_ips", ""))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** Returns {@code true} if the Gaming tab should currently appear in the Top Bar and Companion Remote. */
    public static boolean isGamingEnabled(Context c) {
        return resolveFeatureEnabled(getGamingModePref(c), Config.ENABLE_GAMING, hasGamingConfiguration(c));
    }

    /** Returns {@code true} if the Smart Home tab should currently appear in the Top Bar and Companion Remote. */
    public static boolean isSmartHomeEnabled(Context c) {
        return resolveFeatureEnabled(getSmartHomeModePref(c), Config.ENABLE_SMARTHOME, hasSmartHomeConfiguration(c));
    }

    /** Returns {@code true} if the Shared Reels tab is enabled in this build. */
    public static boolean isReelsEnabled() {
        return MODE_TRUE.equals(normalizeMode(Config.ENABLE_REELS));
    }

    /** Builds the 7-slot Top-Bar screen visibility mask for the given {@link Context}. */
    public static boolean[] currentMask(Context c) {
        return buildEnabledScreenMask(isGamingEnabled(c), isReelsEnabled(), isSmartHomeEnabled(c));
    }

    /** Pure helper building the 7-element boolean visibility mask across {@code SCREEN_SEARCH..SCREEN_SETTINGS}. */
    public static boolean[] buildEnabledScreenMask(boolean gaming, boolean reels, boolean smartHome) {
        return new boolean[] {
            true,       // 0: Search
            true,       // 1: Home
            true,       // 2: Apps
            gaming,     // 3: Gaming (optional)
            reels,      // 4: Reels (WIP / private)
            smartHome,  // 5: Smart Home (optional)
            true        // 6: Settings
        };
    }

    /** Steps left ({@code delta < 0}) or right ({@code delta > 0}) to the next enabled Top-Bar screen. */
    public static int stepEnabledScreen(int currentScreen, int delta, boolean[] mask) {
        if (mask == null || mask.length == 0 || delta == 0) return currentScreen;
        int step = delta > 0 ? 1 : -1;
        int idx = currentScreen + step;
        while (idx >= 0 && idx < mask.length) {
            if (mask[idx]) return idx;
            idx += step;
        }
        return sanitizeActiveScreen(currentScreen, mask);
    }

    /** Returns {@code requestedScreen} if enabled in {@code mask}, otherwise {@code SCREEN_HOME} (1). */
    public static int sanitizeActiveScreen(int requestedScreen, boolean[] mask) {
        if (mask == null || mask.length == 0) return HomeView.SCREEN_HOME;
        int clamped = Math.max(0, Math.min(mask.length - 1, requestedScreen));
        if (mask[clamped]) return clamped;
        return HomeView.SCREEN_HOME;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
