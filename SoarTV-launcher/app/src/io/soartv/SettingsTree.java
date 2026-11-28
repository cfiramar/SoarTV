package io.soartv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Encapsulates the redesigned Two-Panel Settings hierarchy for SoarTV and native Android TV Settings.
 *
 * <p><b>Why:</b> Replaces the previous flat 12-category wall with a Native Google TV-inspired
 * 3-section / 10-category architecture (plus a context-aware top Initial Setup highlight while
 * setup is incomplete), consolidates cluttered flat lists (Gaming PC, Game Controllers, Sleep Timer),
 * exposes missing native Android TV settings with live hardware telemetry (zero hardcoded personal
 * SSIDs or device models), and provides pure-Java full-tree search for Unified Search.
 */
public final class SettingsTree {

    /** Total number of core onboarding steps tracked by Initial Setup &amp; Cloud. */
    private static final int INITIAL_SETUP_TOTAL_STEPS = 6;

    private SettingsTree() {}

    /** Visual &amp; interaction affordance rendered for each Settings row. */
    public enum RowType {
        GROUP,
        TOGGLE,
        CHECK,
        ACTION,
        EXTERNAL,
        INFO,
        SECTION_HEADER
    }

    /** Optional semantic dot/badge tone rendered beside compact status values. */
    public enum StatusTone {
        NONE,
        POSITIVE,
        WARNING
    }

    /**
     * Immutable node in the recursive Settings tree.
     */
    public static final class Node {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String value;
        public final String icon;
        public final boolean checked;
        public final boolean grayedOut;
        public final String hint;
        public final int swatchColor;
        public final RowType rowType;
        public final StatusTone statusTone;
        public final List<Node> children;

        public Node(
                String id,
                String title,
                String subtitle,
                String value,
                String icon,
                boolean checked,
                List<Node> children) {
            this(id, title, subtitle, value, icon, checked, false, "", 0,
                    inferDefaultRowType(checked, children), StatusTone.NONE, children);
        }

        public Node(
                String id,
                String title,
                String subtitle,
                String value,
                String icon,
                boolean checked,
                boolean grayedOut,
                String hint,
                List<Node> children) {
            this(id, title, subtitle, value, icon, checked, grayedOut, hint, 0,
                    inferDefaultRowType(checked, children), StatusTone.NONE, children);
        }

        public Node(
                String id,
                String title,
                String subtitle,
                String value,
                String icon,
                boolean checked,
                boolean grayedOut,
                String hint,
                int swatchColor,
                List<Node> children) {
            this(id, title, subtitle, value, icon, checked, grayedOut, hint, swatchColor,
                    inferDefaultRowType(checked, children), StatusTone.NONE, children);
        }

        public Node(
                String id,
                String title,
                String subtitle,
                String value,
                String icon,
                boolean checked,
                boolean grayedOut,
                String hint,
                int swatchColor,
                RowType rowType,
                StatusTone statusTone,
                List<Node> children) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.value = value == null ? "" : value;
            this.icon = icon == null ? "settings" : icon;
            this.checked = checked;
            this.grayedOut = grayedOut;
            this.hint = hint == null ? "" : hint;
            this.swatchColor = swatchColor;
            this.rowType = rowType == null ? inferDefaultRowType(checked, children) : rowType;
            this.statusTone = statusTone == null ? StatusTone.NONE : statusTone;
            this.children = children == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(children));
        }

        private static RowType inferDefaultRowType(boolean checked, List<Node> children) {
            if (children != null && !children.isEmpty()) return RowType.GROUP;
            return checked ? RowType.CHECK : RowType.ACTION;
        }

        public boolean hasChildren() {
            return !children.isEmpty();
        }

        public boolean isSectionHeader() {
            return rowType == RowType.SECTION_HEADER;
        }

        public Node findById(String targetId) {
            if (targetId == null) return null;
            if (targetId.equals(this.id)) return this;
            for (Node child : children) {
                Node found = child.findById(targetId);
                if (found != null) return found;
            }
            return null;
        }
    }

    /** Creates a non-focusable uppercase section header divider node for the Settings rail. */
    public static Node sectionHeader(String id, String title) {
        return new Node(id, title, "", "", "", false, false, "", 0,
                RowType.SECTION_HEADER, StatusTone.NONE, null);
    }

    private static Node toggleNode(String id, String title, String subtitle, String value, String icon, boolean on) {
        return new Node(id, title, subtitle, value, icon, on, false, "", 0,
                RowType.TOGGLE, on ? StatusTone.POSITIVE : StatusTone.NONE, null);
    }

    private static Node externalNode(String id, String title, String subtitle, String value, String icon) {
        return new Node(id, title, subtitle, value, icon, false, false, "", 0,
                RowType.EXTERNAL, StatusTone.NONE, null);
    }

    private static Node infoNode(String id, String title, String subtitle, String value, String icon) {
        return new Node(id, title, subtitle, value, icon, false, false, "", 0,
                RowType.INFO, StatusTone.NONE, null);
    }

    private static Node checkNode(String id, String title, String subtitle, String value, String icon, boolean selected) {
        return checkNode(id, title, subtitle, value, icon, selected, StatusTone.NONE);
    }

    private static Node checkNode(
            String id, String title, String subtitle, String value, String icon, boolean selected, StatusTone tone) {
        return new Node(id, title, subtitle, value, icon, selected, false, "", 0,
                RowType.CHECK, tone != null ? tone : StatusTone.NONE, null);
    }

    private static StatusTone mapStreamTone(int streamTone) {
        if (streamTone == ModalOverlayRenderer.StreamQualityModalState.TONE_RECOMMENDED) {
            return StatusTone.POSITIVE;
        }
        if (streamTone == ModalOverlayRenderer.StreamQualityModalState.TONE_CAUTION) {
            return StatusTone.WARNING;
        }
        return StatusTone.NONE;
    }

    private static Node actionNode(String id, String title, String subtitle, String value, String icon,
            boolean checked, StatusTone tone) {
        return new Node(id, title, subtitle, value, icon, checked, false, "", 0,
                RowType.ACTION, tone, null);
    }

    private static Node groupNode(String id, String title, String subtitle, String value, String icon,
            boolean checked, String hint, StatusTone tone, List<Node> children) {
        return new Node(id, title, subtitle, value, icon, checked, false, hint, 0,
                RowType.GROUP, tone, children);
    }

    /** Search match produced by {@link #searchNodes(Node, String)} for Unified Search deep-jumping. */
    public static final class SearchMatch {
        public final Node node;
        public final List<String> pathIds;
        public final String breadcrumb;
        final int score;

        SearchMatch(Node node, List<String> pathIds, String breadcrumb, int score) {
            this.node = node;
            this.pathIds = Collections.unmodifiableList(new ArrayList<>(pathIds));
            this.breadcrumb = breadcrumb;
            this.score = score;
        }
    }

    public static final class Snap extends StateSnapshot {}

    public static Node buildScreensaverTree(StateSnapshot s) {
        return buildRoot(s);
    }

    /**
     * Pure snapshot of current settings &amp; live TV hardware state so the tree can be built
     * and unit-tested deterministically without Android dependencies.
     */
    public static class StateSnapshot {
        public String theme = "auto";
        public String autoThemeMode = "sun";
        public int darkHour = 19;
        public int lightHour = 7;
        public String city = "New York";
        public String timezone = "America/New_York (UTC-05:00)";
        public String sunriseText = "06:30";
        public String sunsetText = "17:45";
        public String activeProfileId = "main";
        public String activeProfileName = "Main";
        public List<String[]> profiles = new ArrayList<>(); // {id, displayName}
        public boolean traktConnected = false;
        public String traktUser = "";
        public boolean stremioConnected = false;
        public String stremioEmail = "";
        public String tunerMode = "antenna";
        public boolean autoUpdateChannels = true;
        public boolean statusIcons = false;
        public boolean sounds = true;
        public boolean cartoonsInGeneral = true;
        public String saverCategory = "mixed";
        public Set<String> saverCategories = null;
        public String saverClockStyle = "liquid_glass";
        public boolean saverWatch = false;
        public boolean saverAnimation = true;
        public long saverIntervalMs = 30_000L;
        public String saverIntervalLabel = "30s";
        public boolean saverShowArtworkTitle = true;
        public boolean saverShowArtist = true;
        public boolean saverShowLocation = true;
        public boolean saverShowLocalTime = true;
        public boolean saverShowWorldClock = true;
        public boolean saverShowWeather = true;
        public boolean saverShowCalendar = true;
        public String saverWorldClockTz = "America/New_York";
        public String saverWorldClockLabel = "NYC";
        public int sleepMinutesLeft = 0;
        public boolean sleepShown = true;
        public int sleepPlace = 0;
        public boolean h24 = true;
        public String tvRoom = "Living Room";
        public List<String> existingRooms = new ArrayList<>(Arrays.asList("Living Room", "Bedroom"));
        public List<String> tvGenres = new ArrayList<>(Arrays.asList(Prefs.DEFAULT_GENRES));
        public List<String> movieGenres = new ArrayList<>(Arrays.asList(Prefs.DEFAULT_GENRES));
        public Set<String> hiddenTvGenres = new HashSet<>();
        public Set<String> hiddenMovieGenres = new HashSet<>();
        public boolean hasTmdbKey = true;
        public boolean hasOmdbKey = false;
        public boolean hasMdblistKey = false;
        public boolean hasGeminiKey = false;
        public boolean hasSupabase = false;
        public boolean hasSensiboKey = false;
        public boolean hasTuyaKeys = false;
        public int discoveredWizBulbs = 0;
        public boolean initialSetupDone = false;
        public long lastAccountSyncAt = 0L;
        public List<ControllerManager.ControllerInfo> knownControllers = new ArrayList<>();
        public List<RemoteAuthManager.PairedDevice> pairedRemotes = new ArrayList<>();
        public long referenceNowMs = 0L;
        public String gamingResolution = "1080p";
        public int gamingFps = 60;
        public int gamingBitrateMbps = 30;
        public String gamingFramePacing = "balanced";
        public String gamingCodec = "auto";
        public boolean gamingHdr = false;
        public String gamingAudioConfig = "stereo";
        public boolean gamingPlayAudioOnPc = false;
        public boolean gamingRumble = true;
        public boolean gamingMouseEmulation = true;
        public boolean gamingPerfOverlay = false;
        public boolean gamingOptimizeSettings = true;
        public String gamingPcIp = "";

        // Dynamic live hardware & system telemetry (replaces all previously hardcoded strings)
        public boolean networkConnected = true;
        public String networkType = "Wi-Fi";
        public String wifiSsid = "";
        public int wifiLinkSpeedMbps = 0;
        public String tvIpAddress = "";
        public String remoteLanUrl = "";
        public String displaySummary = "";
        public int tvDisplayWidth = 0;
        public int tvDisplayHeight = 0;
        public int tvRefreshRateHz = 0;
        public int pcDisplayWidth = 0;
        public int pcDisplayHeight = 0;
        public int pcDisplayRefreshHz = 0;
        public List<String[]> externalInputs = new ArrayList<>(); // {id, label, connectedFlag ("1"|"0")}
        public String deviceName = "Android TV";
        public String deviceModel = "Android TV";
        public String androidVersion = "14";
        public String securityPatch = "";
        public String appVersion = "1.4";
        public String storageSummary = "";
        public boolean developerOptionsEnabled = false;
        public boolean gamingTabEnabled = true;
        public boolean smartHomeTabEnabled = true;
        public String gamingTabMode = FeatureTabs.MODE_AUTO;
        public String smartHomeTabMode = FeatureTabs.MODE_AUTO;

        // Previously unexposed SoarTV preferences
        public String soartvLanguage = "auto";
        public List<String[]> availableLanguages = new ArrayList<>(); // {code, displayName}
        public String temperatureUnit = "auto";
        public String clockFormatPref = "auto";
        public String powerOnMode = "";
        public String recentsMode = "off";
        public boolean allowDoubleHomeRecents = true;

        // Cloud Sync v2 & Per-TV Override state
        public boolean cloudSyncConfigured = false;
        public String cloudSyncStatus = "Not synced yet";
        public long cloudLastSyncAt = 0L;
        public List<SupabaseClient.TvDeviceRecord> householdTvDevices = new ArrayList<>();
        public boolean useHouseholdStremio = false;
        public boolean tvOverrideShelf = false;
        public boolean tvOverrideHiddenApps = false;
        public boolean tvOverrideScreensaver = false;
    }

    /**
     * Captures the live state from Android preferences, hardware managers, and system APIs.
     */
    public static StateSnapshot captureState(Context c, HomeData data) {
        StateSnapshot s = new StateSnapshot();
        s.theme = Prefs.theme();
        s.autoThemeMode = Prefs.autoThemeMode();
        s.darkHour = Prefs.autoDarkHour();
        s.lightHour = Prefs.autoLightHour();
        String city = Prefs.city();
        s.city = (city == null || city.isEmpty()) ? "Tel Aviv" : city;

        HomeData.Weather wx = data != null ? data.weather : null;
        s.timezone = formatTimezone(wx);
        s.sunriseText = formatSunTime(wx != null ? wx.sunrise : 0L, "06:15");
        s.sunsetText = formatSunTime(wx != null ? wx.sunset : 0L, "18:00");

        if (c != null) {
            UserProfileManager upm = UserProfileManager.get(c);
            UserProfileManager.Profile active = upm.getActiveProfile();
            s.activeProfileId = active.username;
            s.activeProfileName = active.displayName;
            for (UserProfileManager.Profile p : upm.getProfiles()) {
                s.profiles.add(new String[] {p.username, p.displayName});
            }
            s.traktConnected = Trakt.hasOAuth(c);
            s.traktUser = Trakt.user(c) == null ? "" : Trakt.user(c);
            s.stremioConnected = StremioAccountClient.isConfigured(c);
            s.stremioEmail = StremioAccountClient.getAccountEmail(c);
            s.sleepMinutesLeft = SleepTimer.minutesLeft(c);
            SmartHomeManager shm = SmartHomeManager.get(c);
            s.tvRoom = shm.getTvRoom();
            s.existingRooms = shm.getExistingSmartHomeRooms();
            int wizCount = 0;
            for (SmartHomeManager.Device d : shm.getDevices()) {
                if (d.getProtocol() == SmartHomeManager.Protocol.WIZ_LIGHT && !"wiz_house_lights".equals(d.getId())) {
                    wizCount++;
                }
            }
            s.discoveredWizBulbs = wizCount;
            s.hasSensiboKey = !Secrets.get(c, "sensibo_api_key").isEmpty() || !Config.SENSIBO_API_KEY.trim().isEmpty();
            s.hasTuyaKeys = (!Secrets.get(c, "tuya_access_id").isEmpty() || !Config.TUYA_ACCESS_ID.trim().isEmpty())
                    && (!Secrets.get(c, "tuya_access_secret").isEmpty() || !Config.TUYA_ACCESS_SECRET.trim().isEmpty());
            s.knownControllers = ControllerManager.getAllKnownControllers(c);
            s.pairedRemotes = RemoteAuthManager.get(c).listDevices();
            CloudSyncManager.SyncSummary cloudSum = CloudSyncManager.get(c).getSummary();
            s.cloudSyncConfigured = cloudSum.configured;
            s.cloudSyncStatus = cloudSum.statusText;
            s.cloudLastSyncAt = cloudSum.lastSyncAtMs;
            s.householdTvDevices = SupabaseClient.get(c).getCachedActiveTvDevices();
            s.gamingPcIp = safeString(GamingZone.getLaptopIp(c));
            GamingZone.LaptopStatus cachedPc = GamingZone.getCachedLaptopStatus(c);
            if (cachedPc != null) {
                s.pcDisplayWidth = cachedPc.pcDisplayWidth;
                s.pcDisplayHeight = cachedPc.pcDisplayHeight;
                s.pcDisplayRefreshHz = cachedPc.pcDisplayRefreshHz;
            }
            captureLiveSystemTelemetry(c, s);
        } else {
            s.pairedRemotes = Collections.emptyList();
        }

        s.tunerMode = Prefs.tunerMode();
        s.autoUpdateChannels = Prefs.autoUpdateChannels();
        s.statusIcons = Prefs.statusIcons();
        s.sounds = Prefs.sounds();
        s.cartoonsInGeneral = !Prefs.hideAnimation();
        s.saverCategory = Prefs.saverCategory();
        s.saverCategories = Prefs.saverCategories();
        s.saverClockStyle = Prefs.saverClockStyle();
        s.saverWatch = Prefs.saverWatch();
        s.saverAnimation = Prefs.saverAnimation();
        s.saverIntervalMs = Prefs.saverIntervalMs();
        s.saverIntervalLabel = Prefs.saverIntervalLabel();
        s.saverShowArtworkTitle = Prefs.saverShowArtworkTitle();
        s.saverShowArtist = Prefs.saverShowArtist();
        s.saverShowLocation = Prefs.saverShowLocation();
        s.saverShowLocalTime = Prefs.saverShowLocalTime();
        s.saverShowWorldClock = Prefs.saverShowWorldClock();
        s.saverShowWeather = Prefs.saverShowWeather();
        s.saverShowCalendar = Prefs.saverShowCalendar();
        s.saverWorldClockTz = Prefs.saverWorldClockTz();
        s.saverWorldClockLabel = Prefs.saverWorldClockLabel();
        s.sleepShown = Prefs.sleepShown();
        s.sleepPlace = Prefs.sleepPlace();
        s.h24 = Ui.h24();
        s.tvGenres = Prefs.settingsGenreOrder("tv");
        s.movieGenres = Prefs.settingsGenreOrder("movie");
        s.hiddenTvGenres = Prefs.hiddenGenres("tv");
        s.hiddenMovieGenres = Prefs.hiddenGenres("movie");
        s.hasTmdbKey = !Recs.key().isEmpty();
        s.hasOmdbKey = !Prefs.omdbKey().isEmpty();
        s.hasMdblistKey = !Prefs.mdblistKey().isEmpty();
        s.hasGeminiKey = !Prefs.geminiKey().isEmpty();
        s.hasSupabase = !Prefs.supabaseUrl().isEmpty() && !Prefs.supabaseAnonKey().isEmpty();
        s.initialSetupDone = Prefs.initialSetupDone();
        s.lastAccountSyncAt = Prefs.lastAccountSyncAt();
        s.gamingResolution = Prefs.gamingResolution();
        s.gamingFps = Prefs.gamingFps();
        s.gamingBitrateMbps = Prefs.gamingBitrateMbps();
        s.gamingFramePacing = Prefs.gamingFramePacing();
        s.gamingCodec = Prefs.gamingCodec();
        s.gamingHdr = Prefs.gamingHdr();
        s.gamingAudioConfig = Prefs.gamingAudioConfig();
        s.gamingPlayAudioOnPc = Prefs.gamingPlayAudioOnPc();
        s.gamingRumble = Prefs.gamingRumble();
        s.gamingMouseEmulation = Prefs.gamingMouseEmulation();
        s.gamingPerfOverlay = Prefs.gamingPerfOverlay();
        s.gamingOptimizeSettings = Prefs.gamingOptimizeSettings();
        s.soartvLanguage = Prefs.language();
        s.temperatureUnit = Prefs.temperature();
        s.clockFormatPref = Prefs.clockFormat();
        s.powerOnMode = Prefs.powerOn();
        s.recentsMode = Prefs.recents();
        s.allowDoubleHomeRecents = !RemoteKeys.XIAOMI;
        s.useHouseholdStremio = Prefs.useHouseholdStremio();
        s.tvOverrideShelf = Prefs.isTvOverrideShelfEnabled();
        s.tvOverrideHiddenApps = Prefs.isTvOverrideHiddenAppsEnabled();
        s.tvOverrideScreensaver = Prefs.isTvOverrideScreensaverEnabled();
        try {
            String[] langs = Ui.languages();
            if (langs != null) {
                for (String code : langs) {
                    s.availableLanguages.add(new String[] {code, Ui.languageName(code)});
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    @SuppressWarnings("deprecation")
    private static void captureLiveSystemTelemetry(Context c, StateSnapshot s) {
        // 1. Device identity & OS build
        try {
            String devName = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (devName != null && !devName.trim().isEmpty()) s.deviceName = devName.trim();
        } catch (Throwable ignored) {}
        try {
            if (Build.MODEL != null && !Build.MODEL.trim().isEmpty()) s.deviceModel = Build.MODEL.trim();
            if (Build.VERSION.RELEASE != null && !Build.VERSION.RELEASE.trim().isEmpty()) s.androidVersion = Build.VERSION.RELEASE.trim();
            if (Build.VERSION.SECURITY_PATCH != null) s.securityPatch = Build.VERSION.SECURITY_PATCH.trim();
        } catch (Throwable ignored) {}
        try {
            String vn = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            if (vn != null && !vn.trim().isEmpty()) s.appVersion = vn.trim();
        } catch (Throwable ignored) {}
        try {
            s.developerOptionsEnabled = Settings.Global.getInt(
                    c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Throwable ignored) {}

        // 2. Network & Companion Remote URL
        try {
            String lanUrl = RemoteControlServer.get(c).getLanUrl();
            s.remoteLanUrl = safeString(lanUrl);
            if (lanUrl != null && lanUrl.startsWith("http://")) {
                String stripped = lanUrl.substring("http://".length());
                int colon = stripped.indexOf(':');
                int slash = stripped.indexOf('/');
                int end = colon >= 0 ? colon : (slash >= 0 ? slash : stripped.length());
                String host = stripped.substring(0, end).trim();
                if (!host.isEmpty() && !"127.0.0.1".equals(host)) s.tvIpAddress = host;
            }
        } catch (Throwable ignored) {}

        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
                if (caps == null) {
                    s.networkConnected = false;
                    s.networkType = "Offline";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    s.networkConnected = true;
                    s.networkType = "Ethernet";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    s.networkConnected = true;
                    s.networkType = "Wi-Fi";
                    try {
                        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                        String rawSsid = info != null ? info.getSSID() : null;
                        if (rawSsid != null) {
                            String clean = rawSsid.replace("\"", "").trim();
                            if (!clean.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(clean)) {
                                s.wifiSsid = clean;
                            }
                        }
                        if (info != null && info.getLinkSpeed() > 0) {
                            s.wifiLinkSpeedMbps = info.getLinkSpeed();
                        }
                    } catch (Throwable ignored) {}
                } else {
                    s.networkConnected = true;
                    s.networkType = "Connected";
                }
            }
        } catch (Throwable ignored) {}

        // 3. Real current Display Mode (resolution @ Hz)
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm != null ? wm.getDefaultDisplay() : null;
            Display.Mode mode = display != null ? display.getMode() : null;
            if (mode != null && mode.getPhysicalWidth() > 0 && mode.getPhysicalHeight() > 0) {
                int w = mode.getPhysicalWidth();
                int h = mode.getPhysicalHeight();
                int hz = Math.round(mode.getRefreshRate());
                s.tvDisplayWidth = w;
                s.tvDisplayHeight = h;
                s.tvRefreshRateHz = hz;
                String tag = (w >= 3840) ? "4K" : (w >= 1920 ? "1080p" : (w + "x" + h));
                s.displaySummary = tag + " · " + hz + "Hz";
            }
        } catch (Throwable ignored) {}

        // 4. Live External TV Inputs via TvInputManager / HomeApps
        try {
            List<HomeApps.Item> inputs = HomeApps.allInputs(c);
            if (inputs != null) {
                for (HomeApps.Item it : inputs) {
                    if (it == null || it.kind != HomeApps.INPUT || it.label == null || it.label.trim().isEmpty()) {
                        continue;
                    }
                    String key = (it.inputId != null && !it.inputId.isEmpty())
                            ? it.inputId
                            : ((it.key != null && !it.key.isEmpty()) ? it.key : it.label);
                    s.externalInputs.add(new String[] {key, it.label.trim(), it.connected ? "1" : "0"});
                }
            }
        } catch (Throwable ignored) {}

        // 5. Live Internal Storage free / total GB via StatFs
        try {
            s.gamingTabMode = FeatureTabs.getGamingModePref();
            s.smartHomeTabMode = FeatureTabs.getSmartHomeModePref();
            s.gamingTabEnabled = FeatureTabs.isGamingEnabled(c);
            s.smartHomeTabEnabled = FeatureTabs.isSmartHomeEnabled(c);
        } catch (Throwable ignored) {}
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long availBytes = stat.getAvailableBytes();
            long totalBytes = stat.getTotalBytes();
            if (totalBytes > 0L) {
                double availGb = availBytes / (1024.0 * 1024.0 * 1024.0);
                double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
                s.storageSummary = String.format(Locale.US, "%.1f GB free of %.1f GB", availGb, totalGb);
            }
        } catch (Throwable ignored) {}
    }

    private static String safeString(String v) {
        return v == null ? "" : v.trim();
    }

    public static String formatSyncTimestamp(long epochMs) {
        if (epochMs <= 0L) return "Never synced";
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return "Last synced: " + fmt.format(new Date(epochMs));
    }

    private static String formatTimezone(HomeData.Weather wx) {
        TimeZone tz = TimeZone.getDefault();
        String id = (wx != null && wx.timezoneId != null && !wx.timezoneId.isEmpty())
                ? wx.timezoneId
                : tz.getID();
        int offsetSec = (wx != null && wx.utcOffsetSeconds != Integer.MIN_VALUE)
                ? wx.utcOffsetSeconds
                : (tz.getOffset(System.currentTimeMillis()) / 1000);
        int hours = offsetSec / 3600;
        int mins = Math.abs((offsetSec % 3600) / 60);
        return String.format(Locale.US, "%s (UTC%+03d:%02d)", id, hours, mins);
    }

    private static String formatSunTime(long epochMs, String fallback) {
        if (epochMs <= 0L) return fallback;
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date(epochMs));
    }

    public static String formatHour(int hour) {
        int h = Math.max(0, Math.min(23, hour));
        return String.format(Locale.US, "%02d:00", h);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Builds the complete redesigned Two-Panel Settings tree from a state snapshot.
     *
     * <p>Follows Native Google TV ordering across 3 clear sections, promoting Initial Setup to the
     * top only while setup is incomplete and moving it to the bottom of the Settings rail once
     * completed.
     */
    public static Node buildRoot(StateSnapshot s) {
        StateSnapshot snap = (s != null) ? s : new StateSnapshot();
        List<Node> rootList = new ArrayList<>();
        boolean initialSetupCompleted = isInitialSetupCompleted(snap);

        // Promote Initial Setup to the top only while incomplete; once completed, move it to the bottom
        if (!initialSetupCompleted) {
            rootList.add(sectionHeader("sec_setup", "GET STARTED"));
            rootList.add(buildInitialSetupNode(snap));
        }

        // Section 1: TV & Connectivity (Native Google TV muscle memory)
        rootList.add(sectionHeader("sec_tv", "TV & CONNECTIVITY"));
        rootList.add(buildNetworkInternetNode(snap));
        rootList.add(buildDisplaySoundNode(snap));
        rootList.add(buildChannelsInputsNode(snap));
        rootList.add(buildRemotesAccessoriesNode(snap));

        // Section 2: SoarTV Experience
        rootList.add(sectionHeader("sec_soartv", "SOARTV"));
        rootList.add(buildHomeAppearanceNode(snap));
        if (snap.gamingTabEnabled) {
            rootList.add(buildGamingNode(snap));
        }
        if (snap.smartHomeTabEnabled) {
            rootList.add(buildSmartHomeNode(snap));
        }

        // Section 3: Accounts & System
        rootList.add(sectionHeader("sec_system", "ACCOUNTS & SYSTEM"));
        rootList.add(buildUsersNode(snap));
        rootList.add(buildAppsPrivacyNode(snap));
        rootList.add(buildSystemNode(snap));
        if (initialSetupCompleted) {
            rootList.add(buildInitialSetupNode(snap));
        }

        return new Node("root", "Settings", "", "", "settings", false, false,
                "", 0, RowType.GROUP, StatusTone.NONE, rootList);
    }

    /**
     * Searches the Settings tree for nodes matching {@code rawQuery}, returning deduplicated
     * ranked matches with full navigation {@code pathIds} for Unified Search deep-jumping.
     */
    public static List<SearchMatch> searchNodes(Node root, String rawQuery) {
        if (root == null || rawQuery == null) return Collections.emptyList();
        String q = rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return Collections.emptyList();
        String[] tokens = q.split("\\s+");

        List<SearchMatch> rawMatches = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        collectSearchMatches(root, new ArrayList<>(), "", q, tokens, seenIds, rawMatches);
        Collections.sort(rawMatches, (a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return Integer.compare(a.pathIds.size(), b.pathIds.size());
        });
        return rawMatches;
    }

    private static void collectSearchMatches(
            Node node,
            List<String> ancestorIds,
            String breadcrumb,
            String fullQuery,
            String[] tokens,
            Set<String> seenIds,
            List<SearchMatch> out) {
        if (node == null) return;
        for (Node child : node.children) {
            if (child == null || child.isSectionHeader()) continue;
            List<String> nextPath = new ArrayList<>(ancestorIds);
            nextPath.add(child.id);
            String nextCrumb = breadcrumb.isEmpty() ? child.title : (breadcrumb + " / " + child.title);

            int score = scoreMatch(child, breadcrumb, fullQuery, tokens);
            if (score > 0 && seenIds.add(child.id)) {
                out.add(new SearchMatch(child, nextPath, breadcrumb.isEmpty() ? "Settings" : breadcrumb, score));
            }
            if (child.hasChildren()) {
                collectSearchMatches(child, nextPath, nextCrumb, fullQuery, tokens, seenIds, out);
            }
        }
    }

    private static int scoreMatch(Node node, String breadcrumb, String fullQuery, String[] tokens) {
        String title = node.title.toLowerCase(Locale.ROOT);
        String sub = node.subtitle.toLowerCase(Locale.ROOT);
        String val = node.value.toLowerCase(Locale.ROOT);
        String crumb = breadcrumb.toLowerCase(Locale.ROOT);
        String combined = title + " " + sub + " " + val + " " + crumb;

        for (String tok : tokens) {
            if (!tok.isEmpty() && !combined.contains(tok)) {
                return 0;
            }
        }
        int score = 10;
        if (title.equals(fullQuery)) score += 100;
        else if (title.startsWith(fullQuery)) score += 70;
        else if (title.contains(fullQuery)) score += 50;
        else if (sub.contains(fullQuery)) score += 25;
        if (!node.hasChildren()) score += 8; // Prefer actionable leaf settings when tied
        return score;
    }

    // MARK: - Section 1: TV & Connectivity

    private static Node buildNetworkInternetNode(StateSnapshot s) {
        String netBadge;
        if (!s.networkConnected) {
            netBadge = "Offline";
        } else if ("Ethernet".equalsIgnoreCase(s.networkType)) {
            netBadge = "Ethernet";
        } else if (s.wifiSsid != null && !s.wifiSsid.isEmpty()) {
            netBadge = s.wifiSsid;
        } else {
            netBadge = "Wi-Fi";
        }
        String netIcon = "Ethernet".equalsIgnoreCase(s.networkType) ? "ethernet" : "wifi";
        StatusTone netTone = s.networkConnected ? StatusTone.POSITIVE : StatusTone.WARNING;

        String wifiSub = s.networkConnected
                ? ("Connected via " + netBadge + " · Manage Wi-Fi networks & Ethernet")
                : "Not connected · Open Wi-Fi & network settings";

        String ipDisplay = (s.tvIpAddress != null && !s.tvIpAddress.isEmpty()) ? s.tvIpAddress : "Unavailable";
        String detailsSub = (s.remoteLanUrl != null && !s.remoteLanUrl.isEmpty())
                ? ("IPv4: " + ipDisplay + " · Companion Remote: " + s.remoteLanUrl)
                : ("IPv4: " + ipDisplay + " · Connection: " + s.networkType);

        List<Node> children = Arrays.asList(
                externalNode("net_wifi",
                        "Wi-Fi & Network Connections",
                        wifiSub,
                        netBadge,
                        netIcon),
                infoNode("net_details",
                        "Network & Companion Remote Address",
                        detailsSub,
                        ipDisplay,
                        "info"),
                externalNode("net_advanced",
                        "IP & Proxy Settings",
                        "Configure DHCP, Static IP, DNS, or HTTP proxy in Android TV Settings",
                        "System",
                        "sliders"));

        return groupNode("network_internet",
                "Network & Internet",
                "Wi-Fi, Ethernet, IP address & Companion Remote URL",
                netBadge,
                netIcon,
                s.networkConnected,
                "",
                netTone,
                children);
    }

    private static Node buildDisplaySoundNode(StateSnapshot s) {
        String dispSummary = (s.displaySummary != null && !s.displaySummary.isEmpty())
                ? s.displaySummary
                : "Auto";

        List<Node> children = Arrays.asList(
                externalNode("ds_display_open",
                        "Display & Resolution",
                        "Active display mode, HDR / Dolby Vision, Match Frame Rate & Screen Zoom",
                        dispSummary,
                        "tv"),
                externalNode("ds_picture_open",
                        "Picture Settings",
                        "Picture mode, backlight, brightness, contrast, color & sharpness",
                        "System",
                        "sunny"),
                externalNode("ds_sound_open",
                        "Sound & Audio Output",
                        "TV Speakers, HDMI eARC, Optical, Dolby Atmos & digital passthrough",
                        "System",
                        "speaker"),
                toggleNode("ds_nav_sounds",
                        "Navigation Click Sounds",
                        "Soft audio feedback when moving focus in SoarTV",
                        s.sounds ? "On" : "Off",
                        "bell",
                        s.sounds));

        return groupNode("display_sound",
                "Display & Sound",
                "Resolution, HDR, Picture Mode, Audio Output & Navigation Sounds",
                dispSummary,
                "tv",
                false,
                "",
                StatusTone.NONE,
                children);
    }

    private static Node buildChannelsInputsNode(StateSnapshot s) {
        // 1. Live External Inputs from TvInputManager (falls back cleanly when running headless)
        List<Node> externalInputsChildren = new ArrayList<>();
        int connectedInputs = 0;
        if (s.externalInputs != null && !s.externalInputs.isEmpty()) {
            for (String[] inp : s.externalInputs) {
                if (inp == null || inp.length < 2) continue;
                String inputId = inp[0];
                String label = inp[1];
                boolean connected = inp.length >= 3 && "1".equals(inp[2]);
                if (connected) connectedInputs++;
                externalInputsChildren.add(actionNode(
                        "ci_input_id:" + inputId,
                        label,
                        connected ? "Signal connected · Press OK to switch input" : "Press OK to switch to this input",
                        connected ? "Connected" : "",
                        "hdmi",
                        connected,
                        connected ? StatusTone.POSITIVE : StatusTone.NONE));
            }
        } else {
            externalInputsChildren.add(actionNode("ci_input:hdmi1", "HDMI 1", "Switch to HDMI 1 input", "", "hdmi", false, StatusTone.NONE));
            externalInputsChildren.add(actionNode("ci_input:hdmi2", "HDMI 2", "Switch to HDMI 2 input", "", "hdmi", false, StatusTone.NONE));
            externalInputsChildren.add(actionNode("ci_input:av", "AV / Composite", "Switch to Composite AV input", "", "tv", false, StatusTone.NONE));
        }

        Node externalInputsNode = groupNode(
                "ci_external_inputs",
                "External Inputs",
                "Switch directly to connected HDMI and AV ports on this TV",
                connectedInputs > 0 ? (connectedInputs + " Active") : "",
                "hdmi",
                connectedInputs > 0,
                "Press OK to switch directly to an external input",
                connectedInputs > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                externalInputsChildren);

        Node hdmiRolesNode = actionNode(
                "shg_hdmi_ports",
                "HDMI Device Roles",
                "Assign permanent consoles (PlayStation, Xbox, Switch), streamers & Laptop HDMI",
                "Configure",
                "sliders",
                false,
                StatusTone.NONE);

        // 2. Live TV & Broadcast Channels (4-level recursion: Channels -> Tuner Mode -> Antenna/Cable/Satellite)
        List<Node> tunerChildren = Arrays.asList(
                checkNode("ci_tuner:antenna", "Antenna", "Over-the-air terrestrial digital/analog broadcast",
                        "antenna".equals(s.tunerMode) ? "Selected" : "", "live_tv", "antenna".equals(s.tunerMode)),
                checkNode("ci_tuner:cable", "Cable", "Digital & analog cable TV tuner",
                        "cable".equals(s.tunerMode) ? "Selected" : "", "live_tv", "cable".equals(s.tunerMode)),
                checkNode("ci_tuner:satellite", "Satellite", "DVB-S / DVB-S2 satellite tuner",
                        "satellite".equals(s.tunerMode) ? "Selected" : "", "live_tv", "satellite".equals(s.tunerMode)));

        List<Node> channelsChildren = Arrays.asList(
                groupNode("ci_tuner_mode", "Tuner Mode", "Select Antenna, Cable, or Satellite",
                        capitalize(s.tunerMode), "sliders", false, "Press OK to select broadcast tuner mode",
                        StatusTone.NONE, tunerChildren),
                toggleNode("ci_auto_update", "Update Channels Automatically",
                        "Keep broadcast channel list updated in background",
                        s.autoUpdateChannels ? "On" : "Off", "sync", s.autoUpdateChannels),
                actionNode("ci_channel_scan", "Channel Scan",
                        "Automatically scan for all available broadcast channels", "", "search", false, StatusTone.NONE),
                actionNode("ci_manual_digital", "Manual Digital Scan",
                        "Scan a specific digital DVB frequency or channel", "", "sliders", false, StatusTone.NONE),
                actionNode("ci_manual_analog", "Manual Analog Scan",
                        "Scan and fine-tune analog channels", "", "sliders", false, StatusTone.NONE));

        Node channelsNode = groupNode(
                "ci_channels",
                "Live TV & Channels",
                "Tuner mode, automatic updates, digital & analog channel scan",
                capitalize(s.tunerMode),
                "live_tv",
                false,
                "",
                StatusTone.NONE,
                channelsChildren);

        Node hdmiCecNode = externalNode(
                "ci_hdmi_cec",
                "HDMI-CEC Control",
                "Auto power sync, one-touch play & volume control across HDMI devices",
                "System",
                "settings");

        List<Node> castChildren = Arrays.asList(
                externalNode("ci_cast_open",
                        "Google Cast Built-in",
                        "Manage Chromecast streaming permissions & remote notifications",
                        "System",
                        "cast"),
                externalNode("ci_airplay_open",
                        "Apple AirPlay & HomeKit",
                        "Stream from iPhone, iPad, or Mac and configure HomeKit room",
                        "System",
                        "tv"));

        Node castAirplayNode = groupNode(
                "ci_cast_airplay",
                "Cast & AirPlay",
                "Google Cast built-in and Apple AirPlay & HomeKit",
                "",
                "cast",
                false,
                "",
                StatusTone.NONE,
                castChildren);

        return groupNode(
                "channels_inputs",
                "Channels & Inputs",
                "External HDMI inputs, device roles, Live TV tuner, HDMI-CEC & Cast",
                connectedInputs > 0 ? (connectedInputs + " Active") : "",
                "hdmi",
                false,
                "",
                connectedInputs > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                Arrays.asList(externalInputsNode, hdmiRolesNode, channelsNode, hdmiCecNode, castAirplayNode));
    }

    private static Node buildRemotesAccessoriesNode(StateSnapshot s) {
        int connectedCtrls = countConnectedControllers(s);
        int activeRemotes = countActivePairedRemotes(s);
        int totalConnected = connectedCtrls + activeRemotes;
        String railBadge = totalConnected > 0 ? (totalConnected + " Active") : "";

        Node btPairInApp = actionNode(
                "gaming_bt_pair",
                "Pair Bluetooth Controller or Remote",
                "Fast in-app Bluetooth scanner with hands-free pairing confirmation",
                "Scan",
                "bluetooth",
                false,
                StatusTone.NONE);

        Node btSysAccessories = externalNode(
                "bt_sys_accessories",
                "Bluetooth Audio & Accessories",
                "Pair or manage Bluetooth headphones, speakers, soundbars & keyboards",
                "System",
                "speaker");

        Node controllersNode = buildControllersListNode(s);
        Node quickInviteAdminNode = actionNode(
                "remote_invite:ADMIN",
                "Invite Admin Phone (QR & App)",
                "Show QR code to pair another Admin phone & install the Companion Remote app",
                "QR Code",
                "shield",
                false,
                StatusTone.NONE);
        Node pairedRemotesNode = buildPairedRemotesNode(s);

        Node remoteButtonsNode = actionNode(
                "rem_buttons",
                "Customize TV Remote Buttons",
                "Remap Netflix, YouTube, Prime Video, or vendor buttons in SoarTV",
                "Configure",
                "remote",
                false,
                StatusTone.NONE);

        return groupNode(
                "remotes_accessories",
                "Remotes & Accessories",
                "Bluetooth audio & gamepads, Companion Phone Remotes & TV remote buttons",
                railBadge,
                "remote",
                totalConnected > 0,
                "",
                totalConnected > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                Arrays.asList(quickInviteAdminNode, pairedRemotesNode, btPairInApp, btSysAccessories, controllersNode, remoteButtonsNode));
    }

    // MARK: - Section 2: SoarTV Experience

    private static Node buildHomeAppearanceNode(StateSnapshot s) {
        Node themeNode = buildThemeNode(s);
        Node screensaverNode = buildScreensaverNode(s);
        Node genresNode = buildHomeGenresNode(s);
        Node interfaceNode = buildInterfaceUnitsNode(s);

        String themeBadge = "dark".equals(s.theme) ? "Dark" : ("light".equals(s.theme) ? "Light" : "Auto");
        return groupNode(
                "home_appearance",
                "Home & Appearance",
                "Theme, 4K Screensaver, Content & Genres, Clock & Temperature Units",
                themeBadge,
                "palette",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(themeNode, screensaverNode, genresNode, interfaceNode));
    }

    private static Node buildInterfaceUnitsNode(StateSnapshot s) {
        String clkVal = "24".equals(s.clockFormatPref) ? "24-Hour"
                : ("12".equals(s.clockFormatPref) ? "12-Hour" : "Auto");
        List<Node> clkChoices = Arrays.asList(
                checkNode("ui_clock_fmt:auto", "Automatic (Follow System)", "Use Android TV's locale clock format",
                        "auto".equals(s.clockFormatPref) ? "Selected" : "", "clock", "auto".equals(s.clockFormatPref)),
                checkNode("ui_clock_fmt:24", "24-Hour (23:45)", "Always display 24-hour time in SoarTV & Screensaver",
                        "24".equals(s.clockFormatPref) ? "Selected" : "", "clock", "24".equals(s.clockFormatPref)),
                checkNode("ui_clock_fmt:12", "12-Hour (11:45 PM)", "Always display 12-hour time with AM/PM",
                        "12".equals(s.clockFormatPref) ? "Selected" : "", "clock", "12".equals(s.clockFormatPref)));

        String tempVal = "C".equalsIgnoreCase(s.temperatureUnit) ? "Celsius (°C)"
                : ("F".equalsIgnoreCase(s.temperatureUnit) ? "Fahrenheit (°F)" : "Auto");
        List<Node> tempChoices = Arrays.asList(
                checkNode("ui_temp_unit:auto", "Automatic (By Region)", "Pick °C or °F from your TV's region",
                        "auto".equalsIgnoreCase(s.temperatureUnit) ? "Selected" : "", "thermometer",
                        "auto".equalsIgnoreCase(s.temperatureUnit)),
                checkNode("ui_temp_unit:C", "Celsius (°C)", "Display weather and climate in Celsius",
                        "C".equalsIgnoreCase(s.temperatureUnit) ? "Selected" : "", "thermometer",
                        "C".equalsIgnoreCase(s.temperatureUnit)),
                checkNode("ui_temp_unit:F", "Fahrenheit (°F)", "Display weather and climate in Fahrenheit",
                        "F".equalsIgnoreCase(s.temperatureUnit) ? "Selected" : "", "thermometer",
                        "F".equalsIgnoreCase(s.temperatureUnit)));

        String recVal = "home".equals(s.recentsMode) ? "Drawer & 2× Home"
                : ("drawer".equals(s.recentsMode) ? "In Drawer" : "Off");
        List<Node> recChoices = new ArrayList<>();
        recChoices.add(checkNode("ui_recents:off", "Off", "Disable the Recent Apps switcher",
                "off".equals(s.recentsMode) ? "Selected" : "", "recents", "off".equals(s.recentsMode)));
        recChoices.add(checkNode("ui_recents:drawer", "In Drawer & App-Switch Key",
                "Open Recent Apps via the app-switch remote button",
                "drawer".equals(s.recentsMode) ? "Selected" : "", "recents", "drawer".equals(s.recentsMode)));
        if (s.allowDoubleHomeRecents) {
            recChoices.add(checkNode("ui_recents:home", "Drawer & Double-Press Home",
                    "Also open Recent Apps by pressing Home twice within 700ms",
                    "home".equals(s.recentsMode) ? "Selected" : "", "recents", "home".equals(s.recentsMode)));
        }

        String gamingTabVal = FeatureTabs.MODE_TRUE.equals(s.gamingTabMode) ? "Shown"
                : (FeatureTabs.MODE_FALSE.equals(s.gamingTabMode) ? "Hidden"
                : (s.gamingTabEnabled ? "Auto (On)" : "Auto (Off)"));
        List<Node> gamingTabChoices = Arrays.asList(
                checkNode("ui_tab_gaming:auto", "Automatic (When PC Configured)",
                        "Show the Gaming tab automatically once a Gaming PC is paired or configured",
                        FeatureTabs.MODE_AUTO.equals(s.gamingTabMode) ? "Selected" : "", "gamepad",
                        FeatureTabs.MODE_AUTO.equals(s.gamingTabMode)),
                checkNode("ui_tab_gaming:true", "Always Show Gaming Tab",
                        "Keep the Gaming tab visible in the Top Bar and Companion Remote",
                        FeatureTabs.MODE_TRUE.equals(s.gamingTabMode) ? "Selected" : "", "gamepad",
                        FeatureTabs.MODE_TRUE.equals(s.gamingTabMode)),
                checkNode("ui_tab_gaming:false", "Hide Gaming Tab",
                        "Remove the Gaming tab from the Top Bar, Settings, and Companion Remote",
                        FeatureTabs.MODE_FALSE.equals(s.gamingTabMode) ? "Selected" : "", "gamepad",
                        FeatureTabs.MODE_FALSE.equals(s.gamingTabMode)));

        String shTabVal = FeatureTabs.MODE_TRUE.equals(s.smartHomeTabMode) ? "Shown"
                : (FeatureTabs.MODE_FALSE.equals(s.smartHomeTabMode) ? "Hidden"
                : (s.smartHomeTabEnabled ? "Auto (On)" : "Auto (Off)"));
        List<Node> shTabChoices = Arrays.asList(
                checkNode("ui_tab_smarthome:auto", "Automatic (When Devices Configured)",
                        "Show the Smart Home tab automatically once bulbs, AC, or boiler keys exist",
                        FeatureTabs.MODE_AUTO.equals(s.smartHomeTabMode) ? "Selected" : "", "lightbulb",
                        FeatureTabs.MODE_AUTO.equals(s.smartHomeTabMode)),
                checkNode("ui_tab_smarthome:true", "Always Show Smart Home Tab",
                        "Keep the Smart Home tab visible in the Top Bar and Companion Remote",
                        FeatureTabs.MODE_TRUE.equals(s.smartHomeTabMode) ? "Selected" : "", "lightbulb",
                        FeatureTabs.MODE_TRUE.equals(s.smartHomeTabMode)),
                checkNode("ui_tab_smarthome:false", "Hide Smart Home Tab",
                        "Remove the Smart Home tab from the Top Bar, Settings, and Companion Remote",
                        FeatureTabs.MODE_FALSE.equals(s.smartHomeTabMode) ? "Selected" : "", "lightbulb",
                        FeatureTabs.MODE_FALSE.equals(s.smartHomeTabMode)));

        return groupNode(
                "home_interface",
                "Interface & Units",
                "Optional tabs (Gaming & Smart Home), status icons, clock format, °C/°F & Recent Apps",
                "",
                "sliders",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        groupNode("ui_tab_gaming",
                                "Gaming Tab Visibility",
                                "Show or hide the PC Gaming tab in the Top Bar & Companion Remote",
                                gamingTabVal,
                                "gamepad",
                                s.gamingTabEnabled,
                                "Press OK to choose Automatic, Shown, or Hidden",
                                StatusTone.NONE,
                                gamingTabChoices),
                        groupNode("ui_tab_smarthome",
                                "Smart Home Tab Visibility",
                                "Show or hide the Smart Home tab in the Top Bar & Companion Remote",
                                shTabVal,
                                "lightbulb",
                                s.smartHomeTabEnabled,
                                "Press OK to choose Automatic, Shown, or Hidden",
                                StatusTone.NONE,
                                shTabChoices),
                        toggleNode("net_status_icons",
                                "Top-Bar Status Icons",
                                "Show Wi-Fi, Bluetooth & USB indicators beside the clock",
                                s.statusIcons ? "On" : "Off",
                                "wifi",
                                s.statusIcons),
                        toggleNode("tv_override_shelf",
                                "Custom Favorite Apps Shelf for This TV",
                                "Use a separate Favorite Apps list on this TV instead of syncing with your user profile",
                                s.tvOverrideShelf ? "This TV Only" : "Synced with Profile",
                                "apps",
                                s.tvOverrideShelf),
                        toggleNode("tv_override_hidden_apps",
                                "Custom Hidden Apps for This TV",
                                "Hide or unhide apps specifically on this TV instead of syncing with your user profile",
                                s.tvOverrideHiddenApps ? "This TV Only" : "Synced with Profile",
                                "eye_off",
                                s.tvOverrideHiddenApps),
                        groupNode("ui_clock_fmt",
                                "Clock Format",
                                "Choose Automatic, 24-Hour, or 12-Hour clock across SoarTV",
                                clkVal,
                                "clock",
                                false,
                                "Press OK to choose your preferred clock format",
                                StatusTone.NONE,
                                clkChoices),
                        groupNode("ui_temp_unit",
                                "Temperature Unit",
                                "Choose Celsius (°C) or Fahrenheit (°F) for weather & screensaver",
                                tempVal,
                                "thermometer",
                                false,
                                "Press OK to choose temperature units",
                                StatusTone.NONE,
                                tempChoices),
                        groupNode("ui_recents",
                                "Recent Apps Switcher",
                                "Quickly switch between or close running TV apps",
                                recVal,
                                "recents",
                                !"off".equals(s.recentsMode),
                                "Press OK to configure Recent Apps trigger",
                                StatusTone.NONE,
                                recChoices)));
    }

    private static Node buildGamingNode(StateSnapshot s) {
        int connectedCount = countConnectedControllers(s);
        List<ControllerManager.ControllerInfo> known = s.knownControllers != null
                ? s.knownControllers
                : Collections.emptyList();

        String ctrlSummaryValue;
        if (connectedCount > 0) {
            ctrlSummaryValue = connectedCount + " Connected";
        } else if (!known.isEmpty()) {
            ctrlSummaryValue = known.size() + " Paired";
        } else {
            ctrlSummaryValue = "";
        }

        List<Node> rainbowChildren = new ArrayList<>();
        for (ControllerManager.ColorOption opt : ControllerManager.RAINBOW_COLORS) {
            rainbowChildren.add(new Node(
                    "gaming_color_swatch:" + opt.name,
                    opt.name,
                    "Assign " + opt.name + " to a controller",
                    "",
                    "palette",
                    false,
                    false,
                    "",
                    opt.argb,
                    RowType.ACTION,
                    StatusTone.NONE,
                    null));
        }
        rainbowChildren.add(actionNode(
                "gaming_color_custom",
                "Custom Color…",
                "Choose a custom shade for your controller",
                "Custom",
                "palette",
                false,
                StatusTone.NONE));

        String resVal = formatStreamResolutionLabel(s.gamingResolution);
        String fpsVal = s.gamingFps + " FPS";
        String bitrateVal = s.gamingBitrateMbps + " Mbps";
        String pacingVal = formatFramePacingLabel(s.gamingFramePacing);
        String codecVal = formatCodecLabel(s.gamingCodec);
        String audioVal = formatAudioConfigLabel(s.gamingAudioConfig);

        ModalOverlayRenderer.StreamQualityModalState sqPreview =
                new ModalOverlayRenderer.StreamQualityModalState(
                        s.gamingResolution,
                        s.gamingFps,
                        s.gamingBitrateMbps,
                        s.tvDisplayWidth,
                        s.tvDisplayHeight,
                        s.tvRefreshRateHz,
                        s.pcDisplayWidth,
                        s.pcDisplayHeight,
                        s.pcDisplayRefreshHz,
                        s.networkType,
                        s.wifiLinkSpeedMbps);
        StatusTone sqStatusTone = mapStreamTone(sqPreview.getOverallTone());
        StatusTone resRowTone = mapStreamTone(sqPreview.getResolutionTone());
        StatusTone fpsRowTone = mapStreamTone(sqPreview.getFpsTone());
        StatusTone bitrateRowTone = mapStreamTone(sqPreview.getBitrateTone());
        boolean pacingRec = "balanced".equalsIgnoreCase(s.gamingFramePacing);
        boolean codecRec = "auto".equalsIgnoreCase(s.gamingCodec);
        StatusTone pacingRowTone = pacingRec ? StatusTone.POSITIVE : StatusTone.NONE;
        StatusTone codecRowTone = codecRec ? StatusTone.POSITIVE : StatusTone.NONE;

        int recommendedBitrateMbps = sqPreview.getRecommendedBitrateForCurrentCombo();
        List<Node> bitrateChildren = new ArrayList<>();
        int[] bitrates = ModalOverlayRenderer.StreamQualityModalState.BITRATE_STEPS;
        for (int i = 0; i < bitrates.length; i++) {
            int mbps = bitrates[i];
            String hint = buildBitrateOptionSubtitle(mbps, recommendedBitrateMbps, s);
            StatusTone optTone = mapStreamTone(sqPreview.getBitrateToneForIndex(i));
            bitrateChildren.add(checkNode(
                    "gaming_set_bitrate:" + mbps,
                    mbps + " Mbps",
                    hint,
                    s.gamingBitrateMbps == mbps ? "Selected" : "",
                    "sliders",
                    s.gamingBitrateMbps == mbps,
                    optTone));
        }

        String resGroupSub = sqPreview.getResolutionHint() + " \u00B7 " + buildResolutionGroupSubtitle(s);
        String fpsGroupSub = sqPreview.getFpsHint()
                + (s.tvRefreshRateHz > 0 ? "" : (" \u00B7 " + buildFpsGroupSubtitle(s)));
        String bitrateGroupSub = sqPreview.getBitrateHint()
                + ("Ethernet".equalsIgnoreCase(s.networkType)
                        ? " \u00B7 Ethernet"
                        : ("Wi-Fi".equalsIgnoreCase(s.networkType) && s.wifiLinkSpeedMbps > 0
                                ? (" \u00B7 Wi-Fi " + s.wifiLinkSpeedMbps + " Mbps")
                                : ""));
        String pacingGroupSub = "low_latency".equalsIgnoreCase(s.gamingFramePacing)
                ? "Immediate render \u00B7 Lowest input lag, may tear without VSYNC"
                : ("smoothness".equalsIgnoreCase(s.gamingFramePacing)
                        ? "1-frame buffer \u00B7 Extra smooth on jittery Wi-Fi"
                        : "Recommended \u00B7 VSYNC locked for tear-free motion");
        String codecGroupSub = "hevc".equalsIgnoreCase(s.gamingCodec)
                ? "HEVC \u00B7 Higher efficiency per Mbps when supported by TV & PC"
                : ("h264".equalsIgnoreCase(s.gamingCodec)
                        ? "H.264 \u00B7 Broad fast hardware decode compatibility"
                        : ("av1".equalsIgnoreCase(s.gamingCodec)
                                ? "AV1 \u00B7 Next-gen compression when supported by TV & PC"
                                : "Recommended \u00B7 Auto-negotiates fastest TV & PC hardware codec"));

        Node streamQualityNode = groupNode(
                "gaming_stream_quality",
                "Stream Quality & Video",
                "Resolution, frame rate, bitrate, frame pacing, hardware codec & HDR",
                resVal + " \u00B7 " + fpsVal + " \u00B7 " + s.gamingBitrateMbps + "M",
                "tv",
                sqStatusTone == StatusTone.POSITIVE,
                sqPreview.getOverallBannerText(),
                sqStatusTone,
                Arrays.asList(
                        groupNode(
                                "gaming_stream_res",
                                "Stream Resolution",
                                resGroupSub,
                                resVal,
                                "tv",
                                resRowTone == StatusTone.POSITIVE,
                                "Green = matches detected display \u00B7 Amber = exceeds TV resolution",
                                resRowTone,
                                Arrays.asList(
                                        checkNode("gaming_set_res:720p", "720p HD (1280×720)",
                                                buildResolutionOptionSubtitle("720p", 1280, 720, s),
                                                "720p".equalsIgnoreCase(s.gamingResolution) ? "Selected" : "",
                                                "tv", "720p".equalsIgnoreCase(s.gamingResolution),
                                                mapStreamTone(sqPreview.getResolutionToneForIndex(0))),
                                        checkNode("gaming_set_res:1080p", "1080p Full HD (1920×1080)",
                                                buildResolutionOptionSubtitle("1080p", 1920, 1080, s),
                                                "1080p".equalsIgnoreCase(s.gamingResolution) ? "Selected" : "",
                                                "tv", "1080p".equalsIgnoreCase(s.gamingResolution),
                                                mapStreamTone(sqPreview.getResolutionToneForIndex(1))),
                                        checkNode("gaming_set_res:1440p", "1440p QHD (2560×1440)",
                                                buildResolutionOptionSubtitle("1440p", 2560, 1440, s),
                                                "1440p".equalsIgnoreCase(s.gamingResolution) ? "Selected" : "",
                                                "tv", "1440p".equalsIgnoreCase(s.gamingResolution),
                                                mapStreamTone(sqPreview.getResolutionToneForIndex(2))),
                                        checkNode("gaming_set_res:4k", "4K UHD (3840×2160)",
                                                buildResolutionOptionSubtitle("4k", 3840, 2160, s),
                                                "4k".equalsIgnoreCase(s.gamingResolution) ? "Selected" : "",
                                                "tv", "4k".equalsIgnoreCase(s.gamingResolution),
                                                mapStreamTone(sqPreview.getResolutionToneForIndex(3))))),
                        groupNode(
                                "gaming_stream_fps",
                                "Stream Frame Rate",
                                fpsGroupSub,
                                fpsVal,
                                "bolt",
                                fpsRowTone == StatusTone.POSITIVE,
                                "Green = matches TV refresh rate \u00B7 Amber = exceeds panel Hz",
                                fpsRowTone,
                                Arrays.asList(
                                        checkNode("gaming_set_fps:30", "30 FPS",
                                                buildFpsOptionSubtitle(30, s),
                                                s.gamingFps == 30 ? "Selected" : "", "bolt", s.gamingFps == 30,
                                                mapStreamTone(sqPreview.getFpsToneForIndex(0))),
                                        checkNode("gaming_set_fps:60", "60 FPS",
                                                buildFpsOptionSubtitle(60, s),
                                                s.gamingFps == 60 ? "Selected" : "", "bolt", s.gamingFps == 60,
                                                mapStreamTone(sqPreview.getFpsToneForIndex(1))),
                                        checkNode("gaming_set_fps:90", "90 FPS",
                                                buildFpsOptionSubtitle(90, s),
                                                s.gamingFps == 90 ? "Selected" : "", "bolt", s.gamingFps == 90,
                                                mapStreamTone(sqPreview.getFpsToneForIndex(2))),
                                        checkNode("gaming_set_fps:120", "120 FPS",
                                                buildFpsOptionSubtitle(120, s),
                                                s.gamingFps == 120 ? "Selected" : "", "bolt", s.gamingFps == 120,
                                                mapStreamTone(sqPreview.getFpsToneForIndex(3))))),
                        groupNode(
                                "gaming_stream_bitrate",
                                "Video Bitrate",
                                bitrateGroupSub,
                                bitrateVal,
                                "sliders",
                                bitrateRowTone == StatusTone.POSITIVE,
                                "Green = ideal for current resolution & FPS \u00B7 Amber = too low or above Wi-Fi headroom",
                                bitrateRowTone,
                                bitrateChildren),
                        groupNode(
                                "gaming_stream_pacing",
                                "Frame Pacing",
                                pacingGroupSub,
                                pacingVal,
                                "clock",
                                pacingRec,
                                "Green = Balanced (VSYNC Locked) recommended for smooth tear-free motion",
                                pacingRowTone,
                                Arrays.asList(
                                        checkNode("gaming_set_pacing:low_latency", "Lowest Latency",
                                                "Render decoded frames immediately as they arrive",
                                                "low_latency".equalsIgnoreCase(s.gamingFramePacing) ? "Selected" : "",
                                                "bolt", "low_latency".equalsIgnoreCase(s.gamingFramePacing),
                                                StatusTone.NONE),
                                        checkNode("gaming_set_pacing:balanced", "Balanced (VSYNC Locked)",
                                                "Recommended \u00B7 Aligns frames to TV VSYNC while dropping stale queue buildup",
                                                "balanced".equalsIgnoreCase(s.gamingFramePacing) ? "Selected" : "",
                                                "clock", "balanced".equalsIgnoreCase(s.gamingFramePacing),
                                                StatusTone.POSITIVE),
                                        checkNode("gaming_set_pacing:smoothness", "Prefer Smoothness",
                                                "Schedules 1 VSYNC ahead to absorb network jitter",
                                                "smoothness".equalsIgnoreCase(s.gamingFramePacing) ? "Selected" : "",
                                                "sparkles", "smoothness".equalsIgnoreCase(s.gamingFramePacing),
                                                StatusTone.NONE))),
                        groupNode(
                                "gaming_stream_codec",
                                "Video Codec",
                                codecGroupSub,
                                codecVal,
                                "settings",
                                codecRec,
                                "Green = Automatic recommended to negotiate the fastest hardware decoder",
                                codecRowTone,
                                Arrays.asList(
                                        checkNode("gaming_set_codec:auto", "Automatic",
                                                "Recommended \u00B7 Negotiates the best hardware codec supported by both TV & PC",
                                                "auto".equalsIgnoreCase(s.gamingCodec) ? "Selected" : "",
                                                "sparkles", "auto".equalsIgnoreCase(s.gamingCodec),
                                                StatusTone.POSITIVE),
                                        checkNode("gaming_set_codec:hevc", "HEVC (H.265)",
                                                "Higher visual efficiency per megabit when supported by TV & PC",
                                                "hevc".equalsIgnoreCase(s.gamingCodec) ? "Selected" : "",
                                                "tv", "hevc".equalsIgnoreCase(s.gamingCodec),
                                                StatusTone.NONE),
                                        checkNode("gaming_set_codec:h264", "H.264 (AVC)",
                                                "Broad hardware compatibility across encoders and decoders",
                                                "h264".equalsIgnoreCase(s.gamingCodec) ? "Selected" : "",
                                                "tv", "h264".equalsIgnoreCase(s.gamingCodec),
                                                StatusTone.NONE),
                                        checkNode("gaming_set_codec:av1", "AV1",
                                                "Next-gen compression when supported by both GPU & TV",
                                                "av1".equalsIgnoreCase(s.gamingCodec) ? "Selected" : "",
                                                "tv", "av1".equalsIgnoreCase(s.gamingCodec),
                                                StatusTone.NONE))),
                        toggleNode(
                                "gaming_stream_hdr",
                                "High Dynamic Range (HDR10)",
                                "Stream 10-bit Rec.2020 HDR when supported by PC display & TV",
                                s.gamingHdr ? "On" : "Off",
                                "sparkles",
                                s.gamingHdr),
                        toggleNode(
                                "gaming_stream_optimize",
                                "Optimize Game Settings for Streaming",
                                "Automatically match in-game display settings to stream target",
                                s.gamingOptimizeSettings ? "On" : "Off",
                                "sliders",
                                s.gamingOptimizeSettings)));

        Node streamAudioControlsNode = groupNode(
                "gaming_stream_audio_controls",
                "Stream Audio & Controls",
                "Surround sound channels, PC speaker mute policy, rumble & mouse mode",
                audioVal,
                "speaker",
                false,
                "Configure surround audio, PC speaker muting, vibration & gamepad mouse mode",
                StatusTone.NONE,
                Arrays.asList(
                        groupNode(
                                "gaming_stream_audio_cfg",
                                "Audio Channels",
                                "Select stereo or home-theater surround output",
                                audioVal,
                                "speaker",
                                false,
                                "",
                                StatusTone.NONE,
                                Arrays.asList(
                                        checkNode("gaming_set_audio:stereo", "Stereo (2.0)",
                                                "Standard 2-channel low-latency PCM audio",
                                                "stereo".equalsIgnoreCase(s.gamingAudioConfig) ? "Selected" : "",
                                                "speaker", "stereo".equalsIgnoreCase(s.gamingAudioConfig)),
                                        checkNode("gaming_set_audio:5.1", "5.1 Surround",
                                                "6-channel home theater surround sound",
                                                "5.1".equalsIgnoreCase(s.gamingAudioConfig) ? "Selected" : "",
                                                "speaker", "5.1".equalsIgnoreCase(s.gamingAudioConfig)),
                                        checkNode("gaming_set_audio:7.1", "7.1 Surround",
                                                "8-channel surround sound",
                                                "7.1".equalsIgnoreCase(s.gamingAudioConfig) ? "Selected" : "",
                                                "speaker", "7.1".equalsIgnoreCase(s.gamingAudioConfig)))),
                        toggleNode(
                                "gaming_stream_pc_audio",
                                "Play Audio on Gaming PC Too",
                                "Keep off so your Gaming PC stays muted during TV sessions",
                                s.gamingPlayAudioOnPc ? "On" : "Off",
                                "speaker",
                                s.gamingPlayAudioOnPc),
                        toggleNode(
                                "gaming_stream_rumble",
                                "Controller Rumble & Vibration",
                                "Forward in-game force feedback to connected controllers",
                                s.gamingRumble ? "On" : "Off",
                                "gamepad",
                                s.gamingRumble),
                        toggleNode(
                                "gaming_stream_mouse_emu",
                                "Virtual Mouse Mode (Hold Start)",
                                "Hold Start for 1 second during a game to move cursor with Left Stick",
                                s.gamingMouseEmulation ? "On" : "Off",
                                "smart_button",
                                s.gamingMouseEmulation),
                        toggleNode(
                                "gaming_stream_perf_overlay",
                                "Stream Performance Overlay",
                                "Display live resolution, FPS, codec & RTT in top-left corner",
                                s.gamingPerfOverlay ? "On" : "Off",
                                "info",
                                s.gamingPerfOverlay)));

        // Consolidated Gaming PC group (collapses 4 flat rows into 1 clean inner group)
        String pcIpBadge = (s.gamingPcIp != null && !s.gamingPcIp.isEmpty()) ? s.gamingPcIp : "Configure";
        Node gamingPcGroup = groupNode(
                "gaming_pc_group",
                "Gaming PC",
                "Sync Steam/Sunshine library, Wake-on-LAN, 6-digit pairing & IP address",
                pcIpBadge,
                "pc",
                false,
                "Manage your Gaming PC connection and game catalog",
                StatusTone.NONE,
                Arrays.asList(
                        actionNode("shg_pc_sync", "Sync Gaming PC Library Now",
                                "Refresh Steam & Sunshine game library cache", "Sync", "sync", false, StatusTone.NONE),
                        actionNode("shg_pc_wake", "Wake Gaming PC (Wake-on-LAN)",
                                "Send magic packet to wake gaming laptop/PC", "Wake", "power", false, StatusTone.NONE),
                        actionNode("shg_pc_pair", "Pair Gaming PC (6-Digit Code)",
                                "Authenticate TV with Laptop Gaming Agent using a 6-digit pairing code", "Pair", "key", false, StatusTone.NONE),
                        actionNode("shg_pc_ip", "Gaming PC IP Address",
                                "Set or update your Gaming PC LAN IP address", pcIpBadge, "wifi", false, StatusTone.NONE)));

        // Consolidated Game Controllers group (collapses 4 flat rows into 1 clean inner group)
        Node controllersGroup = groupNode(
                "gaming_controllers_group",
                "Game Controllers",
                "Pair Bluetooth controllers, rename & color-tag gamepads, multi-controller wizard",
                ctrlSummaryValue,
                "gamepad",
                connectedCount > 0,
                "Pair wireless gamepads or customize controller names and colors",
                connectedCount > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                Arrays.asList(
                        actionNode("gaming_bt_pair", "Pair Bluetooth Controller",
                                "Scan and pair wireless controllers right inside SoarTV", "Scan", "bluetooth", false, StatusTone.NONE),
                        buildControllersListNode(s),
                        actionNode("gaming_ctrl_wizard", "Guided Multi-Controller Setup",
                                "Connect and set up multiple controllers one by one", "Start", "sparkles", false, StatusTone.NONE),
                        groupNode("gaming_colors_rainbow", "Controller Color Swatches",
                                "Visual colors sorted in rainbow order",
                                ControllerManager.RAINBOW_COLORS.size() + " Colors", "palette", false,
                                "Rainbow-ordered visual swatches · Press OK to assign a color",
                                StatusTone.NONE, rainbowChildren)));

        Node hdmiPortsShortcut = actionNode(
                "shg_hdmi_ports",
                "HDMI Gaming Ports",
                "Assign PlayStation, Xbox, Nintendo Switch, streamers & Laptop HDMI ports",
                "Configure",
                "hdmi",
                false,
                StatusTone.NONE);

        return groupNode(
                "gaming_settings",
                "Gaming",
                "Stream quality, audio, Gaming PC, controllers & HDMI consoles",
                ctrlSummaryValue,
                "gamepad",
                connectedCount > 0,
                "",
                connectedCount > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                Arrays.asList(
                        streamQualityNode,
                        streamAudioControlsNode,
                        gamingPcGroup,
                        controllersGroup,
                        hdmiPortsShortcut));
    }

    private static Node buildSmartHomeNode(StateSnapshot s) {
        Node tvRoomNode = buildTvRoomNode(s, "TV Room Location");
        Node wizScanNode = actionNode(
                "shg_wiz_scan",
                "Scan Wi-Fi for WiZ Light Bulbs",
                "Discover WiZ V2 smart bulbs on the active Wi-Fi subnet automatically",
                s.discoveredWizBulbs > 0 ? (s.discoveredWizBulbs + " Found") : "Scan",
                "lightbulb",
                s.discoveredWizBulbs > 0,
                s.discoveredWizBulbs > 0 ? StatusTone.POSITIVE : StatusTone.NONE);

        boolean keysReady = s.hasSensiboKey || s.hasTuyaKeys;
        Node integrationsNode = groupNode(
                "sh_integrations",
                "Climate & Cloud Integrations",
                "Sensibo AC API key, Tuya Smart-Home cloud keys & Google Home bridge",
                keysReady ? "Configured" : "Optional",
                "ac",
                keysReady,
                "Press OK to configure or update your private Smart-Home keys",
                keysReady ? StatusTone.POSITIVE : StatusTone.NONE,
                buildSmartHomeKeysChildren(s));

        return groupNode(
                "smarthome_settings",
                "Smart-Home",
                "TV room (" + s.tvRoom + "), WiZ light discovery, Sensibo, Tuya & Google Home",
                s.tvRoom,
                "lightbulb",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(tvRoomNode, wizScanNode, integrationsNode));
    }

    // MARK: - Section 3: Accounts & System

    private static Node buildUsersNode(StateSnapshot s) {
        List<Node> profileChildren = new ArrayList<>();
        if (s.profiles != null) {
            for (String[] prof : s.profiles) {
                boolean active = prof[0].equals(s.activeProfileId);
                profileChildren.add(checkNode(
                        "profile_select:" + prof[0],
                        prof[1],
                        "",
                        active ? "Active" : "",
                        "user",
                        active));
            }
        }
        profileChildren.add(actionNode(
                "profile_cycle",
                "Switch to Next Household Profile",
                "Cycle active user profile for Watch List & Recommendations",
                "",
                "user",
                false,
                StatusTone.NONE));

        Node profileNode = groupNode(
                "users_profile",
                "Household Profile",
                "Active SoarTV user profile for Watch List & Recommendations",
                s.activeProfileName,
                "user",
                false,
                "Press OK to switch the active household profile",
                StatusTone.NONE,
                profileChildren);

        Node traktNode = buildTraktAccountNode(s, "Trakt Account (OAuth)");
        Node stremioNode = buildStremioAccountNode(s, "Stremio Account (Library Sync)");
        Node stremioSharedNode = toggleNode(
                "stremio_use_household",
                "Use Household Shared Stremio",
                "Opt in to share the household Stremio account when this profile has no personal Stremio login",
                s.useHouseholdStremio ? "Shared" : "Personal",
                "play",
                s.useHouseholdStremio);

        Node cloudSyncNode = buildCloudSyncActionNode(s, "cloud_sync_now", "Cloud Settings & Encrypted Vault Sync");
        Node cloudTvsNode = buildHouseholdTvsNode(s, "cloud_tv_devices", "Household TVs in Cloud");

        String lastSyncLabel = formatSyncTimestamp(s.lastAccountSyncAt);
        String syncSub = (s.traktConnected || s.stremioConnected)
                ? (lastSyncLabel + " · Sync Watch List, Library, Ratings & Continue Watching")
                : "Connect Trakt or Stremio above to sync your library";
        String syncVal;
        if (s.lastAccountSyncAt > 0L) {
            syncVal = lastSyncLabel;
        } else if (s.traktConnected && s.stremioConnected) {
            syncVal = "Trakt + Stremio";
        } else if (s.traktConnected) {
            syncVal = "Trakt";
        } else if (s.stremioConnected) {
            syncVal = "Stremio";
        } else {
            syncVal = "Not Connected";
        }
        Node syncAllNode = actionNode(
                "users_sync_all",
                "Sync Accounts (Trakt & Stremio)",
                syncSub,
                syncVal,
                "sync",
                s.traktConnected || s.stremioConnected,
                (s.traktConnected || s.stremioConnected) ? StatusTone.POSITIVE : StatusTone.NONE);

        Node googleAccounts = externalNode(
                "users_google",
                "Google TV Accounts & Sign-In",
                "Manage system Google accounts on this TV",
                "System",
                "user");

        return groupNode(
                "users",
                "Accounts & Profiles",
                "Household profiles, Cloud Sync, Trakt & Stremio accounts & Google TV sign-in",
                s.activeProfileName,
                "user",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(profileNode, cloudSyncNode, cloudTvsNode, traktNode, stremioNode, stremioSharedNode, syncAllNode, googleAccounts));
    }

    private static Node buildCloudSyncActionNode(StateSnapshot s, String id, String title) {
        String lastLabel = formatSyncTimestamp(s.cloudLastSyncAt);
        String val = !s.cloudSyncConfigured
                ? "Not Linked"
                : (s.cloudLastSyncAt > 0L ? lastLabel : "Ready");
        String statusPrefix = (s.cloudSyncStatus != null && !s.cloudSyncStatus.isEmpty())
                ? (s.cloudSyncStatus + " · ")
                : "";
        String sub = s.cloudSyncConfigured
                ? (statusPrefix + "Automatic zero-knowledge AES-256-GCM vault & 3-tier cloud sync")
                : "Link this TV with Setup Studio (Supabase URL, Anon Key & Household Key)";
        return actionNode(
                id,
                title,
                sub,
                val,
                "cloud",
                s.cloudSyncConfigured,
                s.cloudSyncConfigured ? StatusTone.POSITIVE : StatusTone.NONE);
    }

    private static Node buildHouseholdTvsNode(StateSnapshot s, String id, String title) {
        List<SupabaseClient.TvDeviceRecord> tvs = s.householdTvDevices != null
                ? s.householdTvDevices
                : Collections.emptyList();
        List<Node> children = new ArrayList<>();
        if (tvs.isEmpty()) {
            children.add(infoNode(
                    id + "_empty",
                    "No Household TVs Synced Yet",
                    "Run Cloud Sync to register this TV and discover other TVs in your household",
                    "",
                    "tv"));
        } else {
            for (int i = 0; i < tvs.size(); i++) {
                SupabaseClient.TvDeviceRecord tv = tvs.get(i);
                if (tv == null) continue;
                String label = tv.deviceName.isEmpty() ? tv.deviceId : tv.deviceName;
                StringBuilder meta = new StringBuilder();
                if (!tv.roomName.isEmpty()) meta.append(tv.roomName);
                if (!tv.platform.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(tv.platform);
                }
                if (!tv.brand.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(tv.brand);
                }
                String ipLabel = tv.lanIp.isEmpty() ? "Cloud" : tv.lanIp;
                children.add(infoNode(
                        id + "_item_" + i,
                        label,
                        meta.length() == 0 ? tv.deviceId : meta.toString(),
                        ipLabel,
                        "tv"));
            }
        }
        String countVal = tvs.isEmpty()
                ? (s.cloudSyncConfigured ? "0 TVs" : "Offline")
                : (tvs.size() == 1 ? "1 TV" : (tvs.size() + " TVs"));
        return groupNode(
                id,
                title,
                "Active TVs linked to your household cloud directory (auto-updates on IP changes)",
                countVal,
                "tv",
                !tvs.isEmpty(),
                "Press OK to view TVs registered in your household",
                !tvs.isEmpty() ? StatusTone.POSITIVE : StatusTone.NONE,
                children);
    }

    private static Node buildAppsPrivacyNode(StateSnapshot s) {
        List<Node> specialChildren = Arrays.asList(
                externalNode("apps_spec_overlay", "Display Over Other Apps",
                        "Required for sleep timer overlay & on-screen alerts", "System", "apps"),
                externalNode("apps_spec_usage", "Usage Access",
                        "Required for Recent Apps switcher", "System", "apps"),
                externalNode("apps_spec_system", "All System App Settings",
                        "Manage all installed Android TV packages", "System", "settings"));

        List<Node> usageAdsChildren = Arrays.asList(
                externalNode("priv_usage", "Usage & Diagnostics",
                        "Control whether Android TV sends diagnostic data", "System", "info"),
                externalNode("priv_ads", "Ads & Advertising ID",
                        "Reset or opt out of personalized system ads", "System", "shield"));

        return groupNode(
                "apps_privacy",
                "Apps & Privacy",
                "Installed apps, permissions, special access, location access, voice & ads",
                "",
                "apps",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        externalNode("apps_see_all",
                                "See All Installed Apps",
                                "Manage, force-stop, clear cache, or uninstall TV apps",
                                "System",
                                "apps"),
                        externalNode("apps_permissions",
                                "App Permissions",
                                "Control microphone, camera, storage & location permissions by app",
                                "System",
                                "shield"),
                        groupNode("apps_special",
                                "Special App Access",
                                "Display over other apps, Usage access & system app settings",
                                "",
                                "sliders",
                                false,
                                "",
                                StatusTone.NONE,
                                specialChildren),
                        externalNode("apps_security",
                                "Security & Restrictions",
                                "Unknown sources & app installation verification",
                                "System",
                                "shield"),
                        externalNode("priv_sys_location",
                                "System Location Access",
                                "Choose which apps can access your TV's location and to what extent",
                                "System",
                                "location"),
                        externalNode("priv_assistant_open",
                                "Voice & AI Assistant",
                                s.hasGeminiKey
                                        ? "Gemini AI Search active · Open Android TV Voice & Assistant settings"
                                        : "Configure Android TV Voice Search & Google Assistant settings",
                                s.hasGeminiKey ? "Gemini Ready" : "System",
                                "sparkles"),
                        groupNode("priv_usage_ads",
                                "Usage, Diagnostics & Ads",
                                "Diagnostic telemetry & advertising ID controls",
                                "",
                                "shield",
                                false,
                                "",
                                StatusTone.NONE,
                                usageAdsChildren)));
    }

    private static Node buildSystemNode(StateSnapshot s) {
        // 1. About & Device Info (100% live device telemetry)
        String modelLabel = (s.deviceModel != null && !s.deviceModel.isEmpty()) ? s.deviceModel : "Android TV";
        String androidLabel = "Android " + ((s.androidVersion != null && !s.androidVersion.isEmpty()) ? s.androidVersion : "TV");
        String patchSub = (s.securityPatch != null && !s.securityPatch.isEmpty())
                ? ("Security patch: " + s.securityPatch)
                : "Android TV operating system version";
        String soartvVer = "SoarTV " + ((s.appVersion != null && !s.appVersion.isEmpty()) ? s.appVersion : "1.4");

        List<Node> aboutChildren = Arrays.asList(
                externalNode("sys_about_device",
                        "System Update & Device Info",
                        "Device name: " + s.deviceName + " · Open Android TV About & System Update",
                        modelLabel,
                        "info"),
                infoNode("sys_about_android",
                        "Android TV OS Version",
                        patchSub,
                        androidLabel,
                        "tv"),
                infoNode("sys_about_soartv",
                        "SoarTV Launcher Version",
                        "Zero-PC-dependency 4K Liquid Glass launcher",
                        soartvVer,
                        "sparkles"));

        Node aboutNode = groupNode(
                "sys_about",
                "About & Device Info",
                modelLabel + " · " + androidLabel + " · " + soartvVer,
                androidLabel,
                "info",
                false,
                "",
                StatusTone.NONE,
                aboutChildren);

        // 2. SoarTV Launcher & Updates
        Node launcherUpdatesNode = groupNode(
                "sys_launcher_updates",
                "SoarTV Launcher & Updates",
                "GitHub OTA updates, default home screen mode & diagnostics",
                soartvVer,
                "sparkles",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        actionNode("lu_ota", "Check for SoarTV OTA Update",
                                "Check GitHub releases for the latest SoarTV APK", "Check", "sparkles", false, StatusTone.NONE),
                        actionNode("lu_homemode", "Default Home Screen Mode",
                                "Switch safely between SoarTV and the TV's original home screen", "", "home", false, StatusTone.NONE),
                        actionNode("lu_classic", "SoarTV Diagnostics & Key Sheet",
                                "Open SoarTV metrics, watchdog notices & legacy configuration", "", "settings", false, StatusTone.NONE)));

        // 3. System Notifications
        List<NowPlaying.SystemNotice> notices = NowPlaying.getSystemNotices();
        List<Node> notifChildren = new ArrayList<>();
        notifChildren.add(infoNode("notif_polls_muted", "Mute User-Polls & Feedback Surveys",
                "System notifications are active in SoarTV; user-polls & survey prompts are automatically muted",
                "Muted", "bell"));
        if (notices.isEmpty()) {
            notifChildren.add(infoNode("notif_empty", "No Active System Notifications",
                    "Android TV system notifications will appear here when posted", "", "check"));
        } else {
            for (NowPlaying.SystemNotice n : notices) {
                notifChildren.add(actionNode("notif_dismiss:" + n.key, n.title,
                        n.text, "Dismiss", "bell", false, StatusTone.NONE));
            }
            notifChildren.add(actionNode("notif_clear_all", "Clear All System Notifications",
                    "Dismiss all captured system notifications", "", "trash", false, StatusTone.NONE));
        }
        Node notifNode = groupNode("sys_notifications", "System Notifications",
                "Android TV system notifications captured in SoarTV (user-polls muted)",
                notices.isEmpty() ? "Active" : (notices.size() + " new"), "bell", true,
                "Press OK on a notification to dismiss it",
                notices.isEmpty() ? StatusTone.NONE : StatusTone.WARNING,
                notifChildren);

        // 4. Power & Sleep Timer (Single clean interactive Up/Down Sleep Timer modal + overlay controls!)
        String sleepVal = s.sleepMinutesLeft > 0
                ? (SleepTimer.formatDurationLabel(s.sleepMinutesLeft) + " left")
                : "Off";
        List<Node> sleepPlaceChildren = new ArrayList<>();
        String[] placeLabels = {"Top center", "Top right", "Bottom right", "Bottom center", "Bottom left", "Top left"};
        for (int idx = 0; idx < placeLabels.length; idx++) {
            boolean sel = (s.sleepPlace % placeLabels.length) == idx;
            sleepPlaceChildren.add(checkNode(
                    "sys_sleep_place:" + idx,
                    placeLabels[idx],
                    "Corner where the remaining sleep timer pill appears over apps",
                    sel ? "Selected" : "",
                    "location",
                    sel));
        }
        String currentPlaceLabel = placeLabels[Math.max(0, s.sleepPlace) % placeLabels.length];

        String powerOnVal = "home".equals(s.powerOnMode) ? "SoarTV Home"
                : ("live".equals(s.powerOnMode) ? "Live TV" : "TV's Choice");
        List<Node> powerOnChildren = Arrays.asList(
                checkNode("sys_power_on:", "TV's Choice (Default)", "Let the TV decide what opens on boot",
                        s.powerOnMode == null || s.powerOnMode.isEmpty() ? "Selected" : "", "tv",
                        s.powerOnMode == null || s.powerOnMode.isEmpty()),
                checkNode("sys_power_on:home", "SoarTV Home Screen", "Always open SoarTV Home on full boot",
                        "home".equals(s.powerOnMode) ? "Selected" : "", "home", "home".equals(s.powerOnMode)),
                checkNode("sys_power_on:live", "Live TV", "Jump directly into Live TV on full boot",
                        "live".equals(s.powerOnMode) ? "Selected" : "", "live_tv", "live".equals(s.powerOnMode)));

        List<Node> powerChildren = Arrays.asList(
                actionNode("sys_sleep_modal",
                        "Sleep Timer",
                        "Open Up/Down timer picker to toggle On/Off, pick duration, or add extra minutes",
                        sleepVal,
                        "bedtime",
                        s.sleepMinutesLeft > 0,
                        s.sleepMinutesLeft > 0 ? StatusTone.POSITIVE : StatusTone.NONE),
                toggleNode("sys_sleep_show",
                        "Show Time Left On Screen",
                        "Display a subtle floating countdown pill while the Sleep Timer is active",
                        s.sleepShown ? "On" : "Off",
                        "clock",
                        s.sleepShown),
                groupNode("sys_sleep_place",
                        "Timer Badge Position",
                        "Choose where the Sleep Timer countdown pill sits on screen",
                        currentPlaceLabel,
                        "location",
                        false,
                        "Press OK to select the on-screen corner for the Sleep Timer badge",
                        StatusTone.NONE,
                        sleepPlaceChildren),
                groupNode("sys_power_on",
                        "At Power-On",
                        "Choose what opens automatically when the TV boots up",
                        powerOnVal,
                        "power",
                        false,
                        "",
                        StatusTone.NONE,
                        powerOnChildren),
                externalNode("sys_power_open",
                        "System Power & Energy Saver",
                        "Idle standby, power-on behavior & eco mode in Android TV Settings",
                        "System",
                        "power"),
                actionNode("sys_restart",
                        "Restart",
                        "Restart this TV now",
                        "",
                        "restart",
                        false,
                        StatusTone.NONE));

        Node powerNode = groupNode(
                "sys_power",
                "Power & Sleep Timer",
                "Interactive Sleep Timer, power-on behavior, energy saver & restart",
                sleepVal,
                "power",
                s.sleepMinutesLeft > 0,
                "",
                s.sleepMinutesLeft > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                powerChildren);

        // 5. Date & Time
        String tzShortBadge = (s.timezone != null && s.timezone.contains("(") && s.timezone.contains(")"))
                ? s.timezone.substring(s.timezone.indexOf('(') + 1, s.timezone.indexOf(')'))
                : (s.h24 ? "24-Hour" : "12-Hour");
        Node dateTimeNode = groupNode(
                "sys_datetime",
                "Date & Time",
                (s.timezone != null && !s.timezone.isEmpty())
                        ? (s.timezone + " · Automatic date, time & format")
                        : "Automatic date & time, timezone & 24-hour system format",
                tzShortBadge,
                "clock",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        infoNode("sys_dt_tz", "Active Time-Zone", "Current TV timezone", s.timezone, "clock"),
                        externalNode("sys_dt_open", "System Date & Time Settings",
                                "Automatic network time, timezone & system 24-hour format",
                                s.h24 ? "24-Hour" : "12-Hour", "clock")));

        // 6. Language & Keyboard (includes SoarTV UI Language + System Language + Keyboard)
        List<Node> soartvLangChildren = new ArrayList<>();
        boolean isAutoLang = s.soartvLanguage == null || s.soartvLanguage.isEmpty() || "auto".equalsIgnoreCase(s.soartvLanguage);
        soartvLangChildren.add(checkNode(
                "sys_soartv_lang:auto",
                "Automatic (Follow TV Language)",
                "Match Android TV's system language automatically",
                isAutoLang ? "Selected" : "",
                "globe",
                isAutoLang));
        String activeLangDisplay = "Automatic";
        if (s.availableLanguages != null) {
            for (String[] pair : s.availableLanguages) {
                if (pair == null || pair.length < 2) continue;
                boolean sel = !isAutoLang && pair[0].equalsIgnoreCase(s.soartvLanguage);
                if (sel) activeLangDisplay = pair[1];
                soartvLangChildren.add(checkNode(
                        "sys_soartv_lang:" + pair[0],
                        pair[1],
                        "",
                        sel ? "Selected" : "",
                        "globe",
                        sel));
            }
        }
        Node langKbNode = groupNode(
                "sys_lang_kb",
                "Language & Keyboard",
                "SoarTV UI language, Android TV system language & on-screen keyboard",
                activeLangDisplay,
                "globe",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        groupNode("sys_soartv_lang", "SoarTV Display Language",
                                "Override the language used inside SoarTV",
                                activeLangDisplay, "globe", false,
                                "Press OK to choose SoarTV's interface language",
                                StatusTone.NONE, soartvLangChildren),
                        externalNode("sys_language", "Android TV System Language",
                                "Change system-wide TV language & locale", "System", "globe"),
                        externalNode("sys_keyboard", "Keyboard & Input",
                                "Virtual keyboard & Gboard settings", "System", "keyboard")));

        // 7. Storage (with live StatFs free/total GB — concise badge on rail, full breakdown in subtitle)
        String storageShortBadge = (s.storageSummary != null && !s.storageSummary.isEmpty())
                ? s.storageSummary.split(" of ")[0]
                : "System";
        Node storageNode = externalNode(
                "sys_storage",
                "Storage",
                (s.storageSummary != null && !s.storageSummary.isEmpty())
                        ? (s.storageSummary + " · Manage internal & USB storage")
                        : "Internal shared storage & removable USB drives",
                storageShortBadge,
                "storage");

        // 8. Accessibility
        Node a11yNode = externalNode(
                "sys_a11y",
                "Accessibility",
                "Captions, TalkBack screen reader, text scaling & high contrast",
                "System",
                "accessibility");

        List<Node> sysChildren = new ArrayList<>();
        sysChildren.add(aboutNode);
        sysChildren.add(launcherUpdatesNode);
        sysChildren.add(notifNode);
        sysChildren.add(powerNode);
        sysChildren.add(dateTimeNode);
        sysChildren.add(langKbNode);
        sysChildren.add(storageNode);
        sysChildren.add(a11yNode);
        if (s.developerOptionsEnabled) {
            sysChildren.add(externalNode(
                    "sys_dev_options",
                    "Developer Options",
                    "USB & Wireless debugging, animation scales & GPU tools",
                    "Enabled",
                    "code"));
        }
        sysChildren.add(externalNode(
                "atv_open_full",
                "Open Native Android TV Settings",
                "Launch the full system Android TV Settings screen directly",
                "System",
                "tv_settings"));

        String sysBadge = (s.storageSummary != null && !s.storageSummary.isEmpty())
                ? s.storageSummary.split(" of ")[0]
                : androidLabel;

        return groupNode(
                "system_settings",
                "System",
                "About, Updates, Power & Sleep Timer, Language, Storage",
                sysBadge,
                "settings",
                false,
                "",
                StatusTone.NONE,
                sysChildren);
    }

    // MARK: - Shared Reusable Sub-Trees (Initial Setup Guide, Accounts, Controllers, Theme, Genres, Screensaver)

    /**
     * Counts how many of the core onboarding steps in Initial Setup &amp; Cloud are currently completed.
     */
    static int countCompletedInitialSetupSteps(StateSnapshot s) {
        if (s == null) return 0;
        int completedSteps = 0;
        if (countActivePairedRemotes(s) > 0) completedSteps++;
        if (s.traktConnected) completedSteps++;
        if (s.stremioConnected) completedSteps++;
        if (s.tvRoom != null && !s.tvRoom.isEmpty()) completedSteps++;
        if (s.hasSensiboKey || s.hasTuyaKeys || s.discoveredWizBulbs > 0) completedSteps++;
        if (s.hasTmdbKey) completedSteps++;
        return completedSteps;
    }

    /**
     * Returns {@code true} when Initial Setup &amp; Cloud is completed (either explicitly marked done
     * or all core onboarding steps are configured), which moves the row to the bottom of Settings.
     */
    public static boolean isInitialSetupCompleted(StateSnapshot s) {
        if (s == null) return false;
        return s.initialSetupDone || countCompletedInitialSetupSteps(s) >= INITIAL_SETUP_TOTAL_STEPS;
    }

    private static Node buildInitialSetupNode(StateSnapshot s) {
        int activeRemotes = countActivePairedRemotes(s);
        int completedSteps = countCompletedInitialSetupSteps(s);

        boolean allDone = isInitialSetupCompleted(s);
        String overallStatus = allDone ? "Completed" : (completedSteps + " / " + INITIAL_SETUP_TOTAL_STEPS + " Steps");
        StatusTone tone = allDone ? StatusTone.POSITIVE : StatusTone.WARNING;

        Node wizardStartNode = actionNode(
                "setup_wizard_start",
                "Guided Step-by-Step Initial Setup",
                "Pair your phone remote first, then breeze through Trakt, Stremio, TV Room & Keys",
                overallStatus,
                "sparkles",
                allDone,
                tone);

        String remoteStepValue;
        String remoteStepSub;
        if (activeRemotes > 0) {
            String firstPhoneName = "";
            long nowMs = s.referenceNowMs > 0L ? s.referenceNowMs : System.currentTimeMillis();
            if (s.pairedRemotes != null) {
                for (RemoteAuthManager.PairedDevice dev : s.pairedRemotes) {
                    if (dev != null && !dev.isExpired(nowMs) && dev.deviceName != null && !dev.deviceName.isEmpty()) {
                        firstPhoneName = dev.deviceName;
                        break;
                    }
                }
            }
            remoteStepValue = (activeRemotes == 1 && !firstPhoneName.isEmpty())
                    ? firstPhoneName
                    : (activeRemotes + " Paired");
            remoteStepSub = "Phone remote paired · Press OK to pair another phone or manage nicknames";
        } else {
            remoteStepValue = "Not Paired";
            remoteStepSub = "Pair your phone as TV Owner (Admin) via QR to type & sign in faster";
        }
        Node pairRemoteNode = actionNode(
                "setup_pair_remote",
                "1. Companion Phone Remote (App & QR)",
                remoteStepSub,
                remoteStepValue,
                "cast",
                activeRemotes > 0,
                activeRemotes > 0 ? StatusTone.POSITIVE : StatusTone.WARNING);

        Node traktNode = buildTraktAccountNode(s, "2. Trakt Account (OAuth)");
        Node stremioNode = buildStremioAccountNode(s, "3. Stremio Account (Library Sync)");
        Node tvRoomNode = buildTvRoomNode(s, "4. TV Room Assignment");

        boolean shReady = s.hasSensiboKey || s.hasTuyaKeys || s.discoveredWizBulbs > 0;
        List<Node> shChildren = new ArrayList<>();
        shChildren.add(actionNode("shg_wiz_scan",
                "Scan Wi-Fi for WiZ Smart Light Bulbs",
                "Automatically discover WiZ V2 bulbs on the active Wi-Fi subnet",
                s.discoveredWizBulbs > 0 ? (s.discoveredWizBulbs + " Found") : "Scan",
                "lightbulb",
                s.discoveredWizBulbs > 0,
                s.discoveredWizBulbs > 0 ? StatusTone.POSITIVE : StatusTone.NONE));
        shChildren.addAll(buildSmartHomeKeysChildren(s));

        Node shSetupNode = groupNode(
                "setup_smarthome",
                "5. Smart-Home Devices Discovery & Keys",
                "WiZ LAN bulbs, Sensibo AC, Tuya AC, Water Boiler & Google Home",
                shReady ? "Completed" : "Optional",
                "lightbulb",
                shReady,
                "Press OK to scan devices or update your private Smart-Home API keys",
                shReady ? StatusTone.POSITIVE : StatusTone.NONE,
                shChildren);

        boolean keysReady = s.hasTmdbKey && (s.hasOmdbKey || s.hasMdblistKey) && s.hasGeminiKey;
        List<Node> keyChildren = Arrays.asList(
                actionNode("setup_key_tmdb",
                        "TMDB API Key (Movie & TV Metadata)",
                        "Free key from themoviedb.org/settings/api",
                        s.hasTmdbKey ? "Completed" : "Required",
                        "key",
                        s.hasTmdbKey,
                        s.hasTmdbKey ? StatusTone.POSITIVE : StatusTone.WARNING),
                actionNode("setup_key_omdb",
                        "OMDb API Key (Rotten Tomatoes & Metacritic)",
                        "Free key (1,000/day) from omdbapi.com/apikey.aspx",
                        s.hasOmdbKey ? "Completed" : "Not Configured",
                        "key",
                        s.hasOmdbKey,
                        s.hasOmdbKey ? StatusTone.POSITIVE : StatusTone.NONE),
                actionNode("setup_key_mdblist",
                        "MDBList API Key (Rotten Tomatoes & Metacritic)",
                        "Free key (1,000/day) from mdblist.com/preferences (direct account sign-in)",
                        s.hasMdblistKey ? "Completed" : "Not Configured",
                        "key",
                        s.hasMdblistKey,
                        s.hasMdblistKey ? StatusTone.POSITIVE : StatusTone.NONE),
                actionNode("setup_key_gemini",
                        "Gemini AI API Key (AI Search)",
                        "Free key from aistudio.google.com/app/apikey",
                        s.hasGeminiKey ? "Completed" : "Not Configured",
                        "sparkles",
                        s.hasGeminiKey,
                        s.hasGeminiKey ? StatusTone.POSITIVE : StatusTone.NONE),
                actionNode("setup_key_supabase",
                        "Supabase Cloud Database (URL & Anon Key)",
                        "Private free-tier Supabase project for multi-device profiles, settings & auto-vault",
                        s.hasSupabase ? "Completed" : "Not Configured",
                        "cloud",
                        s.hasSupabase,
                        s.hasSupabase ? StatusTone.POSITIVE : StatusTone.NONE),
                buildCloudSyncActionNode(s, "setup_cloud_sync_now", "Sync Cloud Settings & Encrypted Vault Now"),
                buildHouseholdTvsNode(s, "setup_cloud_tv_devices", "Household TVs Linked to Cloud"));

        Node keysNode = groupNode(
                "setup_keys",
                "6. Private API Keys & Cloud Vault",
                "TMDB, OMDb / MDBList (RT & MC ratings), Gemini AI & Supabase Vault",
                keysReady ? "Completed" : (s.hasTmdbKey ? "Partially Completed" : "Required"),
                "key",
                keysReady,
                "Press OK on any service to enter or update your private free-plan key",
                keysReady ? StatusTone.POSITIVE : StatusTone.WARNING,
                keyChildren);

        Node finishNode = actionNode(
                "setup_mark_done",
                "Mark Initial Setup as Complete",
                "Move Initial Setup to the bottom of Settings once your accounts and keys are ready",
                allDone ? "Completed" : "Finish",
                "check",
                allDone,
                allDone ? StatusTone.POSITIVE : StatusTone.NONE);

        return groupNode(
                "initial_setup",
                "Initial Setup & Cloud",
                "Phone Remote pairing, Trakt & Stremio OAuth, TV Room, Smart-Home & Private Keys",
                overallStatus,
                "sparkles",
                allDone,
                "Steps show live status · Press OK to configure or change any step",
                tone,
                Arrays.asList(wizardStartNode, pairRemoteNode, traktNode, stremioNode, tvRoomNode, shSetupNode, keysNode, finishNode));
    }

    private static List<Node> buildSmartHomeKeysChildren(StateSnapshot s) {
        return Arrays.asList(
                actionNode("setup_key_sensibo",
                        "Sensibo AC API Key",
                        "Private API key from home.sensibo.com/me/api",
                        s.hasSensiboKey ? "Configured" : "Not Configured",
                        "ac",
                        s.hasSensiboKey,
                        s.hasSensiboKey ? StatusTone.POSITIVE : StatusTone.NONE),
                actionNode("setup_key_tuya",
                        "Tuya Smart-Home Cloud Keys",
                        "Access ID & Secret from iot.tuya.com (Central Europe Data Center)",
                        s.hasTuyaKeys ? "Configured" : "Not Configured",
                        "ac",
                        s.hasTuyaKeys,
                        s.hasTuyaKeys ? StatusTone.POSITIVE : StatusTone.NONE),
                actionNode("setup_google_home",
                        "Google Home Voice Assistant Bridge",
                        "Test or pair devices linked to your Google Home account",
                        "Ready",
                        "sparkles",
                        true,
                        StatusTone.NONE));
    }

    private static Node buildTraktAccountNode(StateSnapshot s, String label) {
        String traktVal = s.traktConnected
                ? (s.traktUser.isEmpty() ? "Connected" : ("Connected (@" + s.traktUser + ")"))
                : "Not Configured";
        return actionNode(
                "trakt_auth",
                label,
                s.traktConnected
                        ? "Connected to Trakt · Press OK if you wish to disconnect"
                        : "Link your private Trakt account via QR code & activation code",
                traktVal,
                "sync",
                s.traktConnected,
                s.traktConnected ? StatusTone.POSITIVE : StatusTone.NONE);
    }

    private static Node buildStremioAccountNode(StateSnapshot s, String label) {
        String stremioVal = s.stremioConnected
                ? (s.stremioEmail.isEmpty() ? "Connected" : ("Connected (" + s.stremioEmail + ")"))
                : "Not Configured";
        return actionNode(
                "stremio_auth",
                label,
                s.stremioConnected
                        ? "Connected to Stremio · Press OK if you wish to disconnect"
                        : "Sign in to sync your Stremio Library & Continue Watching progress",
                stremioVal,
                "play",
                s.stremioConnected,
                s.stremioConnected ? StatusTone.POSITIVE : StatusTone.NONE);
    }

    private static int countConnectedControllers(StateSnapshot s) {
        int count = 0;
        if (s.knownControllers != null) {
            for (ControllerManager.ControllerInfo ci : s.knownControllers) {
                if (ci != null && ci.connected) count++;
            }
        }
        return count;
    }

    private static int countActivePairedRemotes(StateSnapshot s) {
        long nowMs = s.referenceNowMs > 0L ? s.referenceNowMs : System.currentTimeMillis();
        int count = 0;
        if (s.pairedRemotes != null) {
            for (RemoteAuthManager.PairedDevice dev : s.pairedRemotes) {
                if (dev != null && !dev.isExpired(nowMs)) count++;
            }
        }
        return count;
    }

    private static Node buildControllersListNode(StateSnapshot s) {
        List<ControllerManager.ControllerInfo> known = s.knownControllers != null
                ? s.knownControllers
                : Collections.emptyList();
        int connectedCount = countConnectedControllers(s);

        List<Node> controllerItems = new ArrayList<>();
        if (known.isEmpty()) {
            controllerItems.add(actionNode(
                    "gaming_bt_pair",
                    "No Controllers Paired Yet — Pair Bluetooth Controller",
                    "Put your controller in pairing mode or plug in a USB dongle",
                    "Pair",
                    "bluetooth",
                    false,
                    StatusTone.NONE));
        } else {
            for (ControllerManager.ControllerInfo ci : known) {
                if (ci == null) continue;
                String statusVal;
                String sub;
                int swatch = ci.tagged ? ci.color : 0;
                String power = ci.connected ? ci.getPowerStatusDisplay() : "";
                if (!ci.tagged) {
                    statusVal = "Set Up";
                    sub = (ci.connected ? "Connected" : "Paired")
                            + (!power.isEmpty() ? (" · " + power) : "")
                            + " · Press OK for First-Time Color & Name Setup";
                } else {
                    String colorLabel = ControllerManager.findColorName(ci.color);
                    if (ci.connected) {
                        if (ci.batteryPercent >= 0 && ci.charging) {
                            statusVal = "Connected · " + ci.batteryPercent + "% · On Power";
                        } else if (ci.batteryPercent >= 0) {
                            statusVal = "Connected · " + ci.batteryPercent + "%";
                        } else if (ci.charging) {
                            statusVal = "Connected · On Power";
                        } else {
                            statusVal = "Connected";
                        }
                        sub = "Connected" + (!power.isEmpty() ? (" · " + power) : "")
                                + " · " + colorLabel + " · Press OK to rename or change color";
                    } else {
                        statusVal = "Paired";
                        sub = "Paired · " + colorLabel + " · Press OK to rename or change color";
                    }
                }
                controllerItems.add(new Node(
                        "gaming_ctrl_item:" + ci.descriptor,
                        ci.getDisplayName(),
                        sub,
                        statusVal,
                        "gamepad",
                        ci.connected,
                        false,
                        "",
                        swatch,
                        RowType.ACTION,
                        ci.connected ? StatusTone.POSITIVE : StatusTone.NONE,
                        null));
            }
        }

        String ctrlSummaryValue;
        if (connectedCount > 0) {
            ctrlSummaryValue = connectedCount + " Connected";
        } else if (!known.isEmpty()) {
            ctrlSummaryValue = known.size() + " Paired";
        } else {
            ctrlSummaryValue = "";
        }

        return groupNode(
                "gaming_controllers",
                "Controllers & Paired Gamepads",
                "Set up new controllers or rename / recolor paired controllers",
                ctrlSummaryValue,
                "gamepad",
                connectedCount > 0,
                "New controllers open First-Time Setup · Configured controllers open Edit Options",
                connectedCount > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                controllerItems);
    }

    private static Node buildPairedRemotesNode(StateSnapshot s) {
        List<Node> remoteChildren = new ArrayList<>();
        remoteChildren.add(actionNode(
                "remote_invite:ADMIN",
                "Invite Admin Remote (QR & App)",
                "Show QR code to pair another Admin phone & download the app (full Admin + PC Unlock)",
                "Invite",
                "shield",
                false,
                StatusTone.NONE));
        remoteChildren.add(actionNode(
                "remote_invite:HOUSEHOLD",
                "Invite Household Remote (QR & App)",
                "Show QR code to pair a permanent family phone & download the app",
                "Invite",
                "users",
                false,
                StatusTone.NONE));
        remoteChildren.add(actionNode(
                "remote_invite:GUEST",
                "Invite Guest Remote (30 Days)",
                "Show QR code for a temporary 30-day visitor remote & quick browser link",
                "Invite",
                "plus",
                false,
                StatusTone.NONE));

        long nowMs = s.referenceNowMs > 0L ? s.referenceNowMs : System.currentTimeMillis();
        int activeRemoteCount = 0;
        if (s.pairedRemotes != null) {
            for (RemoteAuthManager.PairedDevice dev : s.pairedRemotes) {
                if (dev == null) continue;
                boolean expired = dev.isExpired(nowMs);
                if (!expired) activeRemoteCount++;
                String statusVal;
                if (expired) {
                    statusVal = "Expired";
                } else if (dev.role == RemoteAuthManager.Role.ADMIN) {
                    statusVal = "Admin";
                } else if (dev.role == RemoteAuthManager.Role.HOUSEHOLD) {
                    statusVal = "Household";
                } else {
                    long daysLeft = Math.max(1L, (dev.expiresAtMs - nowMs + 86_399_999L) / 86_400_000L);
                    statusVal = "Guest · " + daysLeft + "d left";
                }
                String emailPrefix = (dev.googleEmail != null && !dev.googleEmail.isEmpty())
                        ? (dev.googleEmail + " · ")
                        : "";
                String sub = emailPrefix + "Press OK to rename, change role, or kick remote";
                String title = (dev.deviceName != null && !dev.deviceName.isEmpty()) ? dev.deviceName : "Companion Remote";
                remoteChildren.add(new Node(
                        "remote_manage:" + dev.deviceId,
                        title,
                        sub,
                        statusVal,
                        "cast",
                        !expired,
                        expired,
                        "",
                        0,
                        RowType.ACTION,
                        expired ? StatusTone.NONE : StatusTone.POSITIVE,
                        null));
            }
        }
        return groupNode(
                "paired_remotes",
                "Companion Phone Remotes",
                "Invite Guest, Household, or Admin phones and manage paired remotes",
                activeRemoteCount + " Paired",
                "cast",
                activeRemoteCount > 0,
                "Press OK to invite a phone remote or change a paired device's role",
                activeRemoteCount > 0 ? StatusTone.POSITIVE : StatusTone.NONE,
                remoteChildren);
    }

    private static Node buildThemeNode(StateSnapshot s) {
        boolean isDark = "dark".equals(s.theme);
        boolean isLight = "light".equals(s.theme);
        boolean isAuto = "auto".equals(s.theme);
        boolean isAutoSun = isAuto && "sun".equals(s.autoThemeMode);
        boolean isAutoHours = isAuto && "hours".equals(s.autoThemeMode);

        Node darkNode = checkNode(
                "theme_dark",
                "Dark",
                "Always use Dark Liquid Glass appearance",
                isDark ? "Active" : "",
                "bedtime",
                isDark);

        Node lightNode = checkNode(
                "theme_light",
                "Light",
                "Always use Light Liquid Glass appearance",
                isLight ? "Active" : "",
                "sun",
                isLight);

        // Distinct from System App Location Access & Screensaver Location Caption
        List<Node> sunChildren = Arrays.asList(
                actionNode("theme_sun_location",
                        "Weather & Sunrise City",
                        "City used to calculate local weather and automatic sunrise/sunset switching",
                        s.city,
                        "location",
                        isAutoSun,
                        StatusTone.NONE),
                infoNode("theme_sun_timezone",
                        "Calculated Time-Zone",
                        "Automatically determined from your weather city",
                        s.timezone,
                        "clock"),
                infoNode("theme_sun_sunrise",
                        "Sunrise Time",
                        "Light Mode starts automatically at sunrise",
                        s.sunriseText,
                        "sun"),
                infoNode("theme_sun_sunset",
                        "Sunset Time",
                        "Dark Mode starts automatically at sunset",
                        s.sunsetText,
                        "bedtime"));

        Node bySunNode = groupNode(
                "theme_auto_sun",
                "By Sunrise / Sunset",
                "Switch theme automatically at local sunrise (" + s.sunriseText + ") and sunset (" + s.sunsetText + ")",
                isAutoSun ? "Active" : s.city,
                "sun",
                isAutoSun,
                "",
                isAutoSun ? StatusTone.POSITIVE : StatusTone.NONE,
                sunChildren);

        List<Node> darkHourOptions = new ArrayList<>();
        for (int h : new int[] {17, 18, 19, 20, 21, 22, 23, 0}) {
            boolean sel = s.darkHour == h;
            darkHourOptions.add(checkNode(
                    "theme_set_dark_hour:" + h,
                    formatHour(h),
                    "",
                    sel ? "Selected" : "",
                    "bedtime",
                    sel));
        }

        List<Node> lightHourOptions = new ArrayList<>();
        for (int h : new int[] {5, 6, 7, 8, 9, 10, 11}) {
            boolean sel = s.lightHour == h;
            lightHourOptions.add(checkNode(
                    "theme_set_light_hour:" + h,
                    formatHour(h),
                    "",
                    sel ? "Selected" : "",
                    "sun",
                    sel));
        }

        List<Node> hoursChildren = Arrays.asList(
                groupNode("theme_hours_dark",
                        "Dark Mode Starts At",
                        "Choose the hour when Dark Mode turns on",
                        formatHour(s.darkHour),
                        "bedtime",
                        isAutoHours,
                        "Press OK to select the hour when Dark Mode begins",
                        StatusTone.NONE,
                        darkHourOptions),
                groupNode("theme_hours_light",
                        "Light Mode Starts At",
                        "Choose the hour when Light Mode turns on",
                        formatHour(s.lightHour),
                        "sun",
                        isAutoHours,
                        "Press OK to select the hour when Light Mode begins",
                        StatusTone.NONE,
                        lightHourOptions));

        Node byHoursNode = groupNode(
                "theme_auto_hours",
                "By Hours",
                "Switch theme at custom hours (" + formatHour(s.darkHour) + " – " + formatHour(s.lightHour) + ")",
                isAutoHours ? "Active" : (formatHour(s.darkHour) + " / " + formatHour(s.lightHour)),
                "sliders",
                isAutoHours,
                "",
                isAutoHours ? StatusTone.POSITIVE : StatusTone.NONE,
                hoursChildren);

        Node autoNode = groupNode(
                "theme_auto",
                "Auto",
                "Automatically transition between Light and Dark mode",
                isAuto ? ("hours".equals(s.autoThemeMode) ? "By Hours" : "By Sunrise / Sunset") : "",
                "sparkles",
                isAuto,
                "",
                isAuto ? StatusTone.POSITIVE : StatusTone.NONE,
                Arrays.asList(bySunNode, byHoursNode));

        String currentLabel = isDark ? "Dark" : (isLight ? "Light" : "Auto");
        return groupNode(
                "theme",
                "Theme",
                "Dark, Light, or Auto (by Sunrise/Sunset or by Hours)",
                currentLabel,
                "palette",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(darkNode, lightNode, autoNode));
    }

    private static Node buildHomeGenresNode(StateSnapshot s) {
        List<Node> tvGenreNodes = new ArrayList<>();
        Set<String> hiddenTv = s.hiddenTvGenres != null ? s.hiddenTvGenres : Collections.emptySet();
        if (s.tvGenres != null) {
            List<Node> hiddenNodes = new ArrayList<>();
            int visIdx = 1;
            for (int i = 0; i < s.tvGenres.size(); i++) {
                String g = s.tvGenres.get(i);
                boolean hidden = hiddenTv.contains(g);
                String label = hidden ? g : (visIdx++ + ". " + g);
                Node n = new Node(
                        "genre_tv_item:" + g,
                        label,
                        "",
                        hidden ? "Hidden" : "Visible",
                        hidden ? "eye_off" : "reorder",
                        !hidden,
                        hidden,
                        "",
                        0,
                        RowType.TOGGLE,
                        StatusTone.NONE,
                        null);
                if (hidden) hiddenNodes.add(n);
                else tvGenreNodes.add(n);
            }
            tvGenreNodes.addAll(hiddenNodes);
        }

        List<Node> movieGenreNodes = new ArrayList<>();
        Set<String> hiddenMovie = s.hiddenMovieGenres != null ? s.hiddenMovieGenres : Collections.emptySet();
        if (s.movieGenres != null) {
            List<Node> hiddenNodes = new ArrayList<>();
            int visIdx = 1;
            for (int i = 0; i < s.movieGenres.size(); i++) {
                String g = s.movieGenres.get(i);
                boolean hidden = hiddenMovie.contains(g);
                String label = hidden ? g : (visIdx++ + ". " + g);
                Node n = new Node(
                        "genre_movie_item:" + g,
                        label,
                        "",
                        hidden ? "Hidden" : "Visible",
                        hidden ? "eye_off" : "reorder",
                        !hidden,
                        hidden,
                        "",
                        0,
                        RowType.TOGGLE,
                        StatusTone.NONE,
                        null);
                if (hidden) hiddenNodes.add(n);
                else movieGenreNodes.add(n);
            }
            movieGenreNodes.addAll(hiddenNodes);
        }

        return groupNode(
                "home_genres",
                "Content & Genres",
                "TV Shows & Movies genres (Hide / Show & Reorder) and Cartoons filter",
                s.tvGenres != null ? (s.tvGenres.size() + " Genres") : "",
                "reorder",
                false,
                "Press OK to open genre list or toggle filter",
                StatusTone.NONE,
                Arrays.asList(
                        groupNode("hg_tv_genres", "TV Shows Genres",
                                "Visible genres appear first; hidden genres are greyed out at the bottom",
                                "", "reorder", false,
                                "Press OK to Hide / Show Genre · Hold OK to Reorder",
                                StatusTone.NONE, tvGenreNodes),
                        groupNode("hg_movie_genres", "Movies Genres",
                                "Visible genres appear first; hidden genres are greyed out at the bottom",
                                "", "reorder", false,
                                "Press OK to Hide / Show Genre · Hold OK to Reorder",
                                StatusTone.NONE, movieGenreNodes),
                        toggleNode("hg_cartoons", "Cartoons in General Genre Rows",
                                "Show Western animated shows in Comedy/Action rows (Anime is always isolated)",
                                s.cartoonsInGeneral ? "On" : "Off", "tv", s.cartoonsInGeneral),
                        actionNode("hg_reset_genres", "Reset Genres to Default",
                                "Restore default genre visibility and order for TV Shows & Movies",
                                "", "close", false, StatusTone.NONE)));
    }

    /**
     * Returns the dedicated procedural symbol icon name for each 4K photography gallery category.
     */
    public static String iconForGalleryCategory(String category) {
        if (category == null) return "gallery_mixed";
        switch (category.toLowerCase(Locale.ROOT)) {
            case "nature": return "gallery_nature";
            case "macro": return "gallery_macro";
            case "urban": return "gallery_urban";
            case "space": return "sparkles";
            case "paintings": return "palette";
            case "art": return "star";
            case "wildlife": return "gallery_wildlife";
            default: return "gallery_mixed";
        }
    }

    /**
     * Returns the dedicated procedural symbol icon name for each screensaver clock style.
     */
    public static String iconForClockStyle(String style) {
        if (style == null) return "clock_liquid";
        switch (style.toLowerCase(Locale.ROOT)) {
            case "frosted_pill": return "clock_pill";
            case "stacked": return "clock_stacked";
            case "split_flap": return "clock_split";
            case "modern_serif": return "clock_serif";
            case "digital_mono": return "clock_mono";
            case "minimal": return "clock_minimal";
            case "outline": return "clock_outline";
            case "editorial": return "clock_editorial";
            case "classic": return "clock_classic";
            default: return "clock_liquid";
        }
    }

    public static String labelForClockStyle(String style) {
        if (style == null) return "Liquid Glass";
        switch (style.toLowerCase(Locale.ROOT)) {
            case "frosted_pill": return "Frosted Pill";
            case "stacked": return "Stacked Duo";
            case "split_flap": return "Split Cards";
            case "modern_serif": return "Editorial Serif";
            case "digital_mono": return "Tabular Mono";
            case "minimal": return "Ultra Thin";
            case "outline": return "Fine Outline";
            default: return "Liquid Glass";
        }
    }

    private static Node buildScreensaverNode(StateSnapshot s) {
        Set<String> activeCats = (s.saverCategories != null && !s.saverCategories.isEmpty())
                ? s.saverCategories
                : (s.saverCategory != null && !"mixed".equalsIgnoreCase(s.saverCategory)
                        ? Collections.singleton(s.saverCategory.toLowerCase(Locale.ROOT))
                        : Prefs.saverCategories());

        List<Node> catNodes = new ArrayList<>();
        for (String cat : Prefs.SAVER_CATEGORIES) {
            boolean sel = activeCats.contains(cat.toLowerCase(Locale.ROOT));
            catNodes.add(checkNode("ss_cat:" + cat, capitalize(cat),
                    ScreensaverCatalog.categoryDescription(cat),
                    sel ? "Active" : "", iconForGalleryCategory(cat), sel));
        }

        String activeClockStyle = (s.saverClockStyle != null && !s.saverClockStyle.isEmpty())
                ? s.saverClockStyle
                : "liquid_glass";
        if ("editorial".equals(activeClockStyle)) activeClockStyle = "stacked";
        else if ("classic".equals(activeClockStyle)) activeClockStyle = "frosted_pill";

        List<Node> clockNodes = new ArrayList<>();
        for (String st : Prefs.SAVER_CLOCK_STYLES) {
            boolean sel = st.equals(activeClockStyle);
            String desc;
            if ("liquid_glass".equals(st)) desc = "Frosted crystal glass numerals refracting the 4K wallpaper";
            else if ("frosted_pill".equals(st)) desc = "Translucent smoked-glass stadium badge framing date and time";
            else if ("stacked".equals(st)) desc = "Sculptural two-row vertical hours over minutes layout";
            else if ("split_flap".equals(st)) desc = "Horological twin smoked numeral cards with split-flap hairline";
            else if ("modern_serif".equals(st)) desc = "High-contrast magazine serif typography with refined proportions";
            else if ("digital_mono".equals(st)) desc = "Precision instrument tabular monospace numerals";
            else if ("minimal".equals(st)) desc = "Feather-light hairline numerals for pure photo immersion";
            else if ("outline".equals(st)) desc = "Crisp hollow architectural stroke numerals";
            else desc = "Frosted crystal glass numerals";
            clockNodes.add(checkNode("ss_clock:" + st, labelForClockStyle(st), desc,
                    sel ? "Selected" : "", iconForClockStyle(st), sel));
        }

        String intervalVal = (s.saverIntervalLabel != null && !s.saverIntervalLabel.isEmpty())
                ? s.saverIntervalLabel
                : Prefs.formatSaverIntervalLabel(s.saverIntervalMs);

        // Exact 6 granular overlay toggles (distinctly named so Location Caption != System Location Access)
        List<Node> infoNodes = Arrays.asList(
                toggleNode("ss_toggle_clock", "Main Clock", "Show local time in upper right",
                        s.saverShowLocalTime ? "Enabled" : "Disabled", "clock", s.saverShowLocalTime),
                toggleNode("ss_toggle_weather", "Weather", "Show current temperature and weather condition",
                        s.saverShowWeather ? "Enabled" : "Disabled", "sunny", s.saverShowWeather),
                toggleNode("ss_toggle_artist", "Artist Name", "Show photographer or artist credit in small caption",
                        s.saverShowArtist ? "Enabled" : "Disabled", "user", s.saverShowArtist),
                toggleNode("ss_toggle_location", "Location / Artwork Caption",
                        "Show or hide the photo's location or artwork name on the screensaver",
                        s.saverShowLocation ? "Enabled" : "Disabled", "location", s.saverShowLocation),
                toggleNode("ss_toggle_world_clock", "Second Clock", "Show world timezone clock alongside date",
                        s.saverShowWorldClock ? "Enabled" : "Disabled", "globe", s.saverShowWorldClock),
                toggleNode("ss_toggle_calendar", "Date & Calendar", "Show current day and date",
                        s.saverShowCalendar ? "Enabled" : "Disabled", "timer", s.saverShowCalendar));

        long tzRefNow = s.referenceNowMs > 0L ? s.referenceNowMs : System.currentTimeMillis();
        List<Node> tzNodes = new ArrayList<>();
        for (String[] hub : Prefs.SAVER_WORLD_HUBS) {
            String code = hub[0];
            String tzId = hub[1];
            String city = hub[2];
            String cleanCity = city.endsWith(" (" + code + ")")
                    ? city.substring(0, city.length() - (" (" + code + ")").length()).trim()
                    : city;
            boolean sel = code.equals(s.saverWorldClockLabel)
                    || ((s.saverWorldClockLabel == null || s.saverWorldClockLabel.isEmpty()) && tzId.equals(s.saverWorldClockTz));
            String subtitle = ScreensaverCatalog.formatTimezoneSubtitle(tzId, tzRefNow, s.h24);
            String liveHourAndOffset = ScreensaverCatalog.formatTimezoneSelectionValue(tzId, tzRefNow, s.h24);
            tzNodes.add(checkNode("ss_tz:" + tzId + ":" + code, cleanCity,
                    subtitle, liveHourAndOffset, "globe", sel));
        }

        String catSummary;
        if (activeCats.size() == Prefs.SAVER_CATEGORIES.length) {
            catSummary = "All Categories";
        } else if (activeCats.size() == 1) {
            catSummary = capitalize(activeCats.iterator().next());
        } else {
            catSummary = activeCats.size() + " Active";
        }
        String catParentIcon = (activeCats.size() == 1)
                ? iconForGalleryCategory(activeCats.iterator().next())
                : (s.saverCategory != null && !"mixed".equalsIgnoreCase(s.saverCategory)
                        ? iconForGalleryCategory(s.saverCategory)
                        : "gallery_nature");

        return groupNode(
                "screensaver",
                "Screensaver & Galleries",
                "Start 4K UHD screensaver, preview photography galleries & clock styles",
                catSummary + " (4K)",
                "sparkles",
                false,
                "",
                StatusTone.NONE,
                Arrays.asList(
                        actionNode("ss_preview", "Start Screensaver",
                                "Launch the rotating 4K UHD photography screensaver now", "4K UHD", "tv", false, StatusTone.NONE),
                        toggleNode("tv_override_screensaver", "Custom Screensaver for This TV",
                                "Keep separate screensaver galleries & clock settings only on this TV",
                                s.tvOverrideScreensaver ? "This TV Only" : "Synced with Profile", "tv", s.tvOverrideScreensaver),
                        groupNode("ss_category", "Photography Category",
                                "Choose 4K UHD (3840×2160) photography galleries (live preview on right)",
                                catSummary, catParentIcon, false,
                                "Press OK to toggle 4K UHD photography galleries", StatusTone.NONE, catNodes),
                        groupNode("ss_clock", "Clock Style",
                                "Choose screensaver clock style (live preview on right)",
                                labelForClockStyle(activeClockStyle), iconForClockStyle(activeClockStyle), false,
                                "Press OK to select screensaver clock style", StatusTone.NONE, clockNodes),
                        toggleNode("ss_animation", "Image Animations",
                                "Toggle Ken Burns pan & zoom motion",
                                s.saverAnimation ? "Enabled" : "Disabled", "sparkles", s.saverAnimation),
                        actionNode("ss_interval", "Slide Duration",
                                "Open Up/Down duration stepper (Don't change image through 2h)",
                                intervalVal, "timer", false, StatusTone.NONE),
                        groupNode("ss_info_toggles", "On-Screen Info & Text",
                                "Customize visible info overlays and text",
                                "Configure", "info", false,
                                "Press OK to toggle overlay text elements", StatusTone.NONE, infoNodes),
                        groupNode("ss_tz", "Second Clock Timezone",
                                "Choose world city for secondary clock",
                                s.saverWorldClockLabel + " · " + ScreensaverCatalog.formatUtcOffset(s.saverWorldClockTz, tzRefNow),
                                "globe", false,
                                "Press OK to select world city timezone", StatusTone.NONE, tzNodes),
                        externalNode("ss_sys_dream", "System Screensaver & Idle Timer",
                                "Configure Android TV idle timeout & system Daydream screensaver",
                                "System", "clock")));
    }

    private static Node buildTvRoomNode(StateSnapshot s, String titleLabel) {
        List<Node> roomChildren = new ArrayList<>();
        List<String> rooms = (s.existingRooms != null && !s.existingRooms.isEmpty())
                ? s.existingRooms
                : Arrays.asList("Living Room", "Bedroom");
        for (String room : rooms) {
            boolean sel = room.equalsIgnoreCase(s.tvRoom);
            roomChildren.add(checkNode(
                    "tv_room_set:" + room,
                    room,
                    "",
                    sel ? "Selected" : "",
                    "location",
                    sel));
        }
        roomChildren.add(actionNode(
                "tv_room_add",
                "+ Add New Room…",
                "Create a new room for this TV and sync it to your Smart-Home apps",
                "Add",
                "plus",
                false,
                StatusTone.NONE));
        return groupNode(
                "shg_tv_room",
                titleLabel,
                "Room used when clicking the Lights tile on the Smart-Home screen",
                s.tvRoom,
                "location",
                true,
                "Only rooms from your connected Smart-Home apps are listed · Press OK to select or add a room",
                StatusTone.NONE,
                roomChildren);
    }

    private static String formatStreamResolutionLabel(String res) {
        if ("720p".equalsIgnoreCase(res)) return "720p";
        if ("1440p".equalsIgnoreCase(res)) return "1440p";
        if ("4k".equalsIgnoreCase(res)) return "4K";
        return "1080p";
    }

    private static String formatFramePacingLabel(String pacing) {
        if ("low_latency".equalsIgnoreCase(pacing)) return "Lowest Latency";
        if ("smoothness".equalsIgnoreCase(pacing)) return "Smoothness";
        return "Balanced";
    }

    private static String formatCodecLabel(String codec) {
        if ("hevc".equalsIgnoreCase(codec)) return "HEVC";
        if ("h264".equalsIgnoreCase(codec)) return "H.264";
        if ("av1".equalsIgnoreCase(codec)) return "AV1";
        return "Auto";
    }

    private static String formatAudioConfigLabel(String audio) {
        if ("5.1".equalsIgnoreCase(audio)) return "5.1 Surround";
        if ("7.1".equalsIgnoreCase(audio)) return "7.1 Surround";
        return "Stereo";
    }

    /**
     * Computes a dynamic target bitrate (Mbps) matched to the selected resolution and frame rate.
     */
    static int computeRecommendedBitrateMbps(String resolution, int fps) {
        int base;
        if ("720p".equalsIgnoreCase(resolution)) {
            base = 15;
        } else if ("1440p".equalsIgnoreCase(resolution)) {
            base = 40;
        } else if ("4k".equalsIgnoreCase(resolution)) {
            base = 80;
        } else {
            base = 30;
        }
        int scaled = base;
        if (fps <= 30) {
            scaled = Math.max(10, base / 2);
        } else if (fps >= 120) {
            scaled = Math.min(120, Math.round(base * 1.5f));
        } else if (fps >= 90) {
            scaled = Math.min(120, Math.round(base * 1.25f));
        }
        int[] steps = new int[] {10, 15, 20, 30, 40, 60, 80, 100, 120};
        int best = steps[0];
        int bestDist = Math.abs(scaled - best);
        for (int step : steps) {
            int d = Math.abs(scaled - step);
            if (d < bestDist) {
                bestDist = d;
                best = step;
            }
        }
        return best;
    }

    private static String buildResolutionGroupSubtitle(StateSnapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.tvDisplayWidth > 0 && s.tvDisplayHeight > 0) {
            String tvTag = s.tvDisplayWidth >= 3840 ? "4K"
                    : (s.tvDisplayWidth >= 2560 ? "1440p"
                    : (s.tvDisplayWidth >= 1920 ? "1080p" : (s.tvDisplayWidth + "×" + s.tvDisplayHeight)));
            sb.append("TV: ").append(tvTag);
            if (s.tvRefreshRateHz > 0) sb.append(" ").append(s.tvRefreshRateHz).append("Hz");
        }
        if (s.pcDisplayWidth > 0 && s.pcDisplayHeight > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("PC: ").append(s.pcDisplayWidth).append("×").append(s.pcDisplayHeight);
            if (s.pcDisplayRefreshHz > 0) sb.append(" ").append(s.pcDisplayRefreshHz).append("Hz");
        }
        return sb.length() > 0 ? sb.toString() : "Choose target streaming resolution";
    }

    private static String buildResolutionOptionSubtitle(String key, int targetW, int targetH, StateSnapshot s) {
        boolean hasPc = s.pcDisplayWidth > 0 && s.pcDisplayHeight > 0;
        boolean hasTv = s.tvDisplayWidth > 0 && s.tvDisplayHeight > 0;
        if (hasPc && s.pcDisplayWidth == targetW && s.pcDisplayHeight == targetH) {
            if (!hasTv || (targetW <= s.tvDisplayWidth && targetH <= s.tvDisplayHeight)) {
                return "Recommended · Matches PC primary display (" + s.pcDisplayWidth + "×" + s.pcDisplayHeight + ")";
            }
            return "Matches PC primary display (" + s.pcDisplayWidth + "×" + s.pcDisplayHeight + ")";
        }
        if (hasTv && targetW > s.tvDisplayWidth && targetH > s.tvDisplayHeight) {
            return "Exceeds current TV display (" + s.tvDisplayWidth + "×" + s.tvDisplayHeight + ")";
        }
        if (hasPc && (targetW > s.pcDisplayWidth || targetH > s.pcDisplayHeight)) {
            return "Higher than PC primary display (" + s.pcDisplayWidth + "×" + s.pcDisplayHeight + ")";
        }
        if ("720p".equalsIgnoreCase(key)) return "Lowest bandwidth & decode load";
        if ("1080p".equalsIgnoreCase(key)) return "Balanced sharpness and bandwidth";
        if ("1440p".equalsIgnoreCase(key)) return "Higher detail; requires more bandwidth and encode headroom";
        return "Ultra HD detail; requires high network bandwidth and 4K encode/decode";
    }

    private static String buildFpsGroupSubtitle(StateSnapshot s) {
        if (s.tvRefreshRateHz > 0 && s.pcDisplayRefreshHz > 0) {
            return "Detected TV: " + s.tvRefreshRateHz + "Hz · PC: " + s.pcDisplayRefreshHz + "Hz";
        }
        if (s.tvRefreshRateHz > 0) {
            return "Detected TV display refresh rate: " + s.tvRefreshRateHz + "Hz";
        }
        return "Match your TV panel refresh rate for tear-free motion";
    }

    private static String buildFpsOptionSubtitle(int fps, StateSnapshot s) {
        int tvHz = s.tvRefreshRateHz;
        int pcHz = s.pcDisplayRefreshHz;
        if (tvHz > 0) {
            if (Math.abs(fps - tvHz) <= 2) {
                if (pcHz > 0 && Math.abs(fps - pcHz) <= 2) {
                    return "Recommended · Matches your TV (" + tvHz + "Hz) & PC (" + pcHz + "Hz)";
                }
                return "Recommended · Matches your TV's " + tvHz + "Hz refresh rate";
            }
            if (fps > tvHz + 2) {
                return "Exceeds your TV's current " + tvHz + "Hz refresh rate";
            }
            if (fps == 30) {
                return "Lower bandwidth / power-saving mode (half of 60Hz)";
            }
            return "Smooth frame rate within your TV's " + tvHz + "Hz ceiling";
        }
        if (fps == 30) return "Lower bandwidth / power-saving mode";
        if (fps == 60) return "Standard smooth gaming frame rate";
        if (fps == 90) return "High frame rate mode (for 90Hz+ displays)";
        return "High frame rate mode (for 120Hz+ displays)";
    }

    private static String buildBitrateGroupSubtitle(StateSnapshot s) {
        if ("Ethernet".equalsIgnoreCase(s.networkType)) {
            return "Connected via Ethernet · Higher bitrate improves clarity";
        }
        if ("Wi-Fi".equalsIgnoreCase(s.networkType) && s.wifiLinkSpeedMbps > 0) {
            return "Wi-Fi link speed: " + s.wifiLinkSpeedMbps + " Mbps · Stay well below link capacity";
        }
        return "Higher bitrate increases clarity; lower bitrate stabilizes congested networks";
    }

    private static String buildBitrateOptionSubtitle(int mbps, int recommendedMbps, StateSnapshot s) {
        String resLabel = formatStreamResolutionLabel(s.gamingResolution);
        if ("Wi-Fi".equalsIgnoreCase(s.networkType) && s.wifiLinkSpeedMbps > 0) {
            int safeCeiling = Math.max(15, (s.wifiLinkSpeedMbps * 45) / 100);
            if (mbps > safeCeiling) {
                return "May exceed Wi-Fi headroom (link: " + s.wifiLinkSpeedMbps + " Mbps)";
            }
        }
        if (mbps == recommendedMbps) {
            return "Recommended for " + resLabel + " · " + s.gamingFps + " FPS";
        }
        return "Target video stream bitrate";
    }
}
