package io.soartv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.usb.UsbManager;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the focused card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours or days
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        RemoteControlServer.Host {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    /** Closed Favorite Apps side-shelf edge indicator dimensions (taller and narrower pill tab, arrow-only). */
    static final float SIDE_SHELF_HINT_W = 28f;
    static final float SIDE_SHELF_HINT_H = 236f;
    static final float SIDE_SHELF_HINT_RADIUS = 16f;
    static final boolean SIDE_SHELF_HINT_SHOW_APPS_ICON = false;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF343741, WHITE = 0xFFF5F5F7;
    static final int LIGHT_HERO_BG = 0xFFFFFFFF;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 2200;
    static final long FADE_MS = 450;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, RECENT = 9;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "soartv-home-loader");
            t.setDaemon(true);
            return t;
        }
    });
    // Light design (the L_ constants): title, tab bar, sub-tab/genre bar, three recommendation cards and the shelf.
    // The tab bar is a 56 px capsule; sub-tabs + genres sit at 196..242; the cards sit on the 5-column grid at 262.
    private static final float L_TITLE_BASE = 112, L_TABBAR_TOP = 132, L_TABBAR_H = 56, L_SUBBAR_TOP = 196, L_SUBBAR_H = 46, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float WATCH_GAP = 40;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 96;
    private static final float L_STATUS = 25, L_STATUS_GAP = 22;
    private static final String[] STATUS_SYMBOLS = {"wifi", "ethernet", "bluetooth", "usb"};
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    static final int L_INK = 0xFF343741, L_DIM = 0xFF565A67, L_FAINT = 0xFF676C7B;
    private static final int TAB_COUNT = 4;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {"continue", Recs.WATCHLIST, Recs.EXPLORE, Recs.TRENDING};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_WATCH = 6, Z_SUBTABS = 7;

    // 7-Option Top-Bar Screens: Search (AI), Home, Apps, Gaming, Reels, Smart-Home, Settings
    static final int SCREEN_SEARCH = 0;
    static final int SCREEN_HOME = 1;
    static final int SCREEN_APPS = 2;
    static final int SCREEN_GAMING = 3;
    static final int SCREEN_REELS = 4;
    static final int SCREEN_SMARTHOME = 5;
    static final int SCREEN_SETTINGS = 6;
    static final int TOP_BAR_COUNT = 7;
    static final String[] TOP_BAR_ICONS = {"search", "home", "apps", "gamepad", "tiktok", "lightbulb", "settings"};

    static final int ROW_CONTINUE = 0;
    static final int ROW_MEDIA_SELECTOR = 1;
    static final int ROW_PICKS = 2;
    static final int ROW_GENRE = 3;
    static final int ROW_GAMES = 4;
    static final int ROW_ACTIONS = 5;
    static final int ROW_SMARTHOME = 6;

    static final class ActionCard {
        final String id;
        final String title;
        final String subtitle;
        final String symbol;
        final int colorTop;
        final int colorBottom;
        final HomeApps.Item appItem;

        ActionCard(String id, String title, String subtitle, String symbol, int colorTop, int colorBottom, HomeApps.Item appItem) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.symbol = symbol;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
            this.appItem = appItem;
        }
    }

    static final class BrowserRow {
        final int kind;
        final String title;
        final String genreKey;
        final String pickKind;
        final List<WatchNext.Item> watchItems;
        final List<Recs.Pick> pickItems;
        final List<GamingZone.GameItem> gameItems;
        final List<ActionCard> actionItems;
        final List<HomeApps.Item> smartItems;
        final boolean watchLoaded;
        final int screen;
        float scrollX;

        BrowserRow(int kind, String title, String genreKey,
                   List<WatchNext.Item> watchItems,
                   List<Recs.Pick> pickItems,
                   List<GamingZone.GameItem> gameItems,
                   List<ActionCard> actionItems,
                   List<HomeApps.Item> smartItems) {
            this(kind, title, genreKey, null, watchItems, pickItems, gameItems, actionItems, smartItems, true, -1);
        }

        BrowserRow(int kind, String title, String genreKey,
                   List<WatchNext.Item> watchItems,
                   List<Recs.Pick> pickItems,
                   List<GamingZone.GameItem> gameItems,
                   List<ActionCard> actionItems,
                   List<HomeApps.Item> smartItems,
                   boolean watchLoaded) {
            this(kind, title, genreKey, null, watchItems, pickItems, gameItems, actionItems, smartItems, watchLoaded, -1);
        }

        BrowserRow(int kind, String title, String genreKey, String pickKind,
                   List<WatchNext.Item> watchItems,
                   List<Recs.Pick> pickItems,
                   List<GamingZone.GameItem> gameItems,
                   List<ActionCard> actionItems,
                   List<HomeApps.Item> smartItems) {
            this(kind, title, genreKey, pickKind, watchItems, pickItems, gameItems, actionItems, smartItems, true, -1);
        }

        BrowserRow(int kind, String title, String genreKey, String pickKind,
                   List<WatchNext.Item> watchItems,
                   List<Recs.Pick> pickItems,
                   List<GamingZone.GameItem> gameItems,
                   List<ActionCard> actionItems,
                   List<HomeApps.Item> smartItems,
                   boolean watchLoaded) {
            this(kind, title, genreKey, pickKind, watchItems, pickItems, gameItems, actionItems, smartItems, watchLoaded, -1);
        }

        BrowserRow(int kind, String title, String genreKey, String pickKind,
                   List<WatchNext.Item> watchItems,
                   List<Recs.Pick> pickItems,
                   List<GamingZone.GameItem> gameItems,
                   List<ActionCard> actionItems,
                   List<HomeApps.Item> smartItems,
                   boolean watchLoaded,
                   int screen) {
            this.kind = kind;
            this.title = title;
            this.genreKey = genreKey;
            this.pickKind = pickKind;
            this.watchItems = watchItems != null ? watchItems : java.util.Collections.emptyList();
            this.pickItems = pickItems != null ? pickItems : java.util.Collections.emptyList();
            this.gameItems = gameItems != null ? gameItems : java.util.Collections.emptyList();
            this.actionItems = actionItems != null ? actionItems : java.util.Collections.emptyList();
            this.smartItems = smartItems != null ? smartItems : java.util.Collections.emptyList();
            this.watchLoaded = watchLoaded;
            this.screen = screen >= 0 ? screen : (kind == ROW_CONTINUE || kind == ROW_GENRE ? SCREEN_HOME : -1);
        }

        boolean isSkeleton(int idx) {
            return kind == ROW_CONTINUE && !watchLoaded;
        }

        int count() {
            switch (kind) {
                case ROW_MEDIA_SELECTOR: return 2;
                case ROW_CONTINUE:
                    if (!watchLoaded) return 4;
                    return Math.max(1, watchItems.size() + pickItems.size());
                case ROW_PICKS:
                case ROW_GENRE: return pickItems.size();
                case ROW_GAMES: return gameItems.size();
                case ROW_ACTIONS: return actionItems.size();
                case ROW_SMARTHOME: return smartItems.size();
                default: return 0;
            }
        }

        float height(float k) {
            return height(k, screen);
        }

        float height(float k, int currentScreen) {
            if (kind == ROW_MEDIA_SELECTOR) return 84 * k;
            if (kind == ROW_SMARTHOME) return WIDE_BROWSER_ROW_HEIGHT * k;
            if (currentScreen == SCREEN_HOME && (kind == ROW_CONTINUE || kind == ROW_PICKS || kind == ROW_GENRE)) {
                return MEDIA_ROW_HEIGHT * k;
            }
            if (currentScreen < 0 && (kind == ROW_CONTINUE || kind == ROW_GENRE || kind == ROW_PICKS)) {
                return MEDIA_ROW_HEIGHT * k;
            }
            return WIDE_BROWSER_ROW_HEIGHT * k;
        }
    }

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Coalesces rapid key-repeat bursts in 28ms while swapping already-cached RAM backdrops in 0ms. */
    private static final long BG_FOLLOW_MS = 28L;
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final android.hardware.input.InputManager inputManager;
    private final android.hardware.input.InputManager.InputDeviceListener inputDeviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    ControllerManager.refreshHardwareLocationsAsync(getContext(), HomeView.this::onControllerConnectionChanged);
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    ControllerManager.onInputDeviceRemoved(deviceId);
                    onControllerConnectionChanged();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    ControllerManager.refreshHardwareLocationsAsync(getContext(), HomeView.this::onControllerConnectionChanged);
                }
            };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String act = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(act)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(act)
                    || BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)
                    || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)
                    || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(act)
                    || "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED".equals(act)) {
                ControllerManager.refreshHardwareLocationsAsync(getContext(), HomeView.this::onControllerConnectionChanged);
                onControllerConnectionChanged();
            }
        }
    };
    private boolean usbReceiverRegistered;

    private void onControllerConnectionChanged() {
        if (activeScreen == SCREEN_GAMING || activeScreen == SCREEN_SETTINGS) {
            if (activeScreen == SCREEN_SETTINGS) {
                rebuildSettingsTree();
            }
            heroDirty = true;
            heroLayer.invalidate();
            wake();
        }
    }

    private final ConnectivityManager connectivity;
    private final BluetoothAdapter bluetooth;
    private boolean networkRegistered, devicesRegistered, mediaRegistered;
    private int network;   // 0 = not connected, 1 = Wi-Fi, 2 = Ethernet
    private boolean bluetoothConnected, usbConnected;
    /** The status layer spans the screen while the icons are on (they sit at the left); off, it keeps its old width. */
    private boolean statusWide;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { if (recentsOpen()) recents.openFocused(); else activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return a11yHolds(id); }
        @Override public void hold(int id) { if (recentsOpen()) recents.closeFocused(); else if (sheetOpen) a11yHold(); else if (canReorder()) startReorder(); else showMenu(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, menuLayer, recentLayer;
    private RecentApps recents;
    /** Default focus starts on the first item of the Home screen (Z_CARDS), not in the Top-Bar. */
    private int zone = Z_CARDS;
    private int activeScreen = SCREEN_HOME;
    private int homeRow = 0;
    private int homeCol = 0;
    private final Spring homeScrollY = new Spring(0.36f, 0.88f, 0);
    private final List<BrowserRow> browserRows = new ArrayList<>();
    private boolean sideShelfOpen = false;
    private final Spring sideShelfSpring = new Spring(0.38f, 0.88f, 0);
    private GamingZone.LaptopStatus gamingStatus;
    private List<SupabaseClient.SharedReel> sharedReelsList = new ArrayList<>();
    private List<HomeApps.Item> allAppsList = new ArrayList<>();
    private String searchQuery = "";
    private List<Recs.Pick> searchMediaResults = new ArrayList<>();
    private List<ActionCard> searchYoutubeResults = new ArrayList<>();
    private List<ActionCard> searchAppAndSettingResults = new ArrayList<>();
    private GamingZone.GameItem menuGameItem;
    private HomeApps.Item menuSmartItem;
    private HomeApps.Item menuShelfApp;
    private HomeApps.Item menuGridApp;
    private boolean reorderGames;
    private boolean reorderAppsGrid;
    private boolean reorderWatchlist;
    private Bitmap sideShelfBlur;
    private boolean showHiddenGamesInline = false;
    private boolean showHiddenAppsInline = false;

    // Interactive Smart-Home Device Control Card (opened on Hold-OK for Smart-Home devices)
    private boolean smartCardOpen = false;
    private boolean menuRenderSmartCard = false;
    private SmartHomeManager.Device smartCardDevice;
    private final SmartHomeScreenRenderer.ControlCardSession smartCardSession = new SmartHomeScreenRenderer.ControlCardSession();
    private final String[] smartCardLabels = new String[8];
    private final String[] smartCardValues = new String[8];
    private int smartCardRowCount = 0;

    // Grayscale + dimmed filter for watched items on Watch List and inline hidden games/apps
    private static final android.graphics.ColorMatrixColorFilter GRAYSCALE_DIM_FILTER = createGrayscaleDimFilter();

    private static android.graphics.ColorMatrixColorFilter createGrayscaleDimFilter() {
        try {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0.12f);
            android.graphics.ColorMatrix scale = new android.graphics.ColorMatrix(new float[] {
                0.55f, 0, 0, 0, 0,
                0, 0.55f, 0, 0, 0,
                0, 0, 0.55f, 0, 0,
                0, 0, 0, 0.72f, 0
            });
            cm.postConcat(scale);
            return new android.graphics.ColorMatrixColorFilter(cm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a crisp 220 ms transition.
    private static final long NIGHT_MS = 220;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // Native Two-Panel (38% Left Rail / 62% Right Pane, 42%/58% Preview, or 100% Leaf) Settings Screen state
    private SettingsTree.Node settingsRoot;
    private final List<String> settingsPathIds = new ArrayList<>();
    private final java.util.ArrayDeque<Integer> settingsStepDepthStack = new java.util.ArrayDeque<>();
    private final int[] settingsDepthIndices = new int[16];
    private boolean settingsFocusRight = false;
    private int settingsRightIndex = 0;
    private boolean reorderSettingsGenre = false;
    private String settingsSearchQuery = "";
    private boolean settingsSearchBarFocused = false;
    private boolean settingsSearchClearFocused = false;
    private EditText settingsSearchEdit;
    private boolean settingsSearchSyncingEdit = false;
    private String cachedSettingsFilterParentId = null;
    private String cachedSettingsFilterQuery = null;
    private List<SettingsTree.Node> cachedSettingsFilterSource = null;
    private List<SettingsTree.Node> cachedSettingsFilteredList = null;
    private android.app.AlertDialog traktAuthDialog;
    private android.app.AlertDialog stremioAuthDialog;

    // Interactive Liquid Glass Up/Down Sleep Timer Modal state
    private boolean sleepModalOpen = false;
    private ModalOverlayRenderer.SleepTimerModalState sleepModalState = null;
    private long sleepModalOpenedAt = 0L;

    // Interactive Liquid Glass Cyclic Up/Down Screensaver Slide Duration Modal state
    private boolean slideDurationModalOpen = false;
    private boolean slideDurationModalDirty = false;
    private ModalOverlayRenderer.SlideDurationModalState slideDurationModalState = null;
    private long slideDurationModalOpenedAt = 0L;

    private boolean streamQualityModalOpen = false;
    private boolean streamQualityModalDirty = false;
    private ModalOverlayRenderer.StreamQualityModalState streamQualityModalState = null;
    private long streamQualityModalOpenedAt = 0L;
    private long lastGamepadAxisMoveAt = 0L;
    private int lastGamepadAxisDx = 0;
    private int lastGamepadAxisDy = 0;

    // Cancellable Sync Waiting Screen modal state
    private boolean syncModalOpen = false;
    private String syncModalTitle = "";
    private String syncModalStep = "";
    private int syncModalProgress = 0;
    private final RectF syncCancelRect = new RectF();

    // Animated Liquid Glass Game Launch Modal state
    private boolean gameLaunchModalOpen = false;
    private String gameLaunchTitle = "";
    private String gameLaunchHeadline = "";
    private String gameLaunchSubtitle = "";
    private String gameLaunchIcon = "power";
    private int gameLaunchStageIndex = 0;
    private float gameLaunchTargetPct = 0f;
    private float gameLaunchDisplayPct = 0f;
    private final RectF gameLaunchCancelRect = new RectF();

    // --- Track D Pure State Helpers & Liquid Glass Modal Infrastructure (INC-04, INC-05, INC-06, PERF-07, PERF-08) ---

    interface ChoiceSelectedListener {
        void onChoiceSelected(int index);
    }

    interface TextConfirmedListener {
        void onTextConfirmed(String text);
    }

    static HomeView findFrom(Activity activity, View fromView) {
        if (fromView instanceof HomeView) return (HomeView) fromView;
        if (fromView != null) {
            android.view.ViewParent p = fromView.getParent();
            while (p != null) {
                if (p instanceof HomeView) return (HomeView) p;
                p = p.getParent();
            }
        }
        if (activity != null && activity.getWindow() != null) {
            View decor = activity.getWindow().getDecorView();
            return findHomeViewRecursive(decor);
        }
        return null;
    }

    private static HomeView findHomeViewRecursive(View v) {
        if (v instanceof HomeView) return (HomeView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                HomeView found = findHomeViewRecursive(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Backward-compatible delegator to {@link HomeInputRouter.RowScrollMemory}. */
    static final class RowScrollMemory extends HomeInputRouter.RowScrollMemory {}

    static String buildRowMemoryKey(int screen, int subTab, int rowKind, String pickKind, String genreKey, String title) {
        return HomeInputRouter.buildRowMemoryKey(screen, subTab, rowKind, pickKind, genreKey, title);
    }

    private String rowMemoryKey(BrowserRow r) {
        if (r == null) return "";
        return buildRowMemoryKey(activeScreen, subTab, r.kind, r.pickKind, r.genreKey, r.title);
    }

    /** Backward-compatible delegator to {@link HomeInputRouter.BackNavigationStack}. */
    static final class BackNavigationStack extends HomeInputRouter.BackNavigationStack {}

    /** Backward-compatible delegator to {@link ModalOverlayRenderer.LiquidGlassInputState}. */
    static final class LiquidGlassInputState extends ModalOverlayRenderer.LiquidGlassInputState {
        LiquidGlassInputState(String initialText, boolean secretMode) {
            super(initialText, secretMode);
        }
    }

    // Stage 2 (BP-05) Dedicated Screen & Modal Collaborators
    private final HomeInputRouter inputRouter = new HomeInputRouter();
    private final ModalOverlayRenderer modalRenderer = new ModalOverlayRenderer();
    private final SettingsScreenRenderer settingsRenderer = new SettingsScreenRenderer();
    private final GamingScreenRenderer gamingRenderer = new GamingScreenRenderer();
    private final SmartHomeScreenRenderer smartHomeRenderer = new SmartHomeScreenRenderer();
    private final HomeInputRouter.RowScrollMemory rowScrollMemory = inputRouter.rowScrollMemory();
    private final HomeInputRouter.BackNavigationStack backStack = inputRouter.backStack();
    private final AtomicInteger watchlistSortGen = new AtomicInteger();

    private final GamingScreenRenderer.CardDrawCallbacks gameCardCallbacks = new GamingScreenRenderer.CardDrawCallbacks() {
        @Override
        public LinearGradient cachedGradient(int colorTop, int colorBottom, RectF targetCell) {
            return cachedActionCardGradient(colorTop, colorBottom, targetCell);
        }

        @Override
        public void coverImage(Canvas c, Bitmap bmp, BitmapShader shader, RectF targetCell, float radius, float alpha) {
            HomeView.this.coverImage(c, bmp, shader, targetCell, radius, alpha);
        }

        @Override
        public void onGameArtworkDecoded() {
            handler.post(() -> {
                if (activeScreen == SCREEN_GAMING) {
                    handler.removeCallbacks(bgFollow);
                    handler.postDelayed(bgFollow, 16);
                }
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });
        }
    };

    private final Runnable onSettingsPreviewDecoded = new Runnable() {
        @Override
        public void run() {
            handler.post(() -> {
                if (activeScreen == SCREEN_SETTINGS) {
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                }
            });
        }
    };

    // On-TV "New Remote Detected" Liquid Glass Pairing Platter state (cached QR Bitmap per requestId)
    private RemoteAuthManager.PendingPairRequest activePendingPairing;
    private String pairingQrRequestId = "";
    private Bitmap pairingQrBitmap;
    private int pairingModalFocus = 0;
    private boolean pairingModalOkDown = false;
    private boolean pairingModalBackDown = false;
    private long pairingModalOpenedAt = 0L;

    // Liquid Glass Modal state (choice list & text/PIN input rendered on toastLayer)
    private EditText lgHiddenEdit;
    private boolean lgSyncingEdit = false;
    private boolean lgModalOpen = false;
    private boolean lgModalIsInput = false;
    private String lgModalTitle = "";
    private String lgModalSubtitle = "";
    private String lgModalHint = "";
    private String lgModalConfirmLabel = "";
    private String lgModalCancelLabel = "";
    private String[] lgChoiceLabels = new String[0];
    private int[] lgChoiceSwatches;
    private int lgChoiceFocus = 0;
    private int lgChoiceFirstRow = 0;
    private ChoiceSelectedListener lgChoiceCallback;
    private TextConfirmedListener lgInputCallback;
    private Runnable lgCancelCallback;
    private LiquidGlassInputState lgInputState = new LiquidGlassInputState("", false);
    // Input modal focus slots: 0 = input box (opens keyboard), 1 = Show/Hide PIN (if secretMode), next = Confirm, last = Cancel
    private int lgInputFocusSlot = 0;
    private final Matrix cardGradMatrix = new Matrix();
    private final LinkedHashMap<Long, LinearGradient> actionCardGradients = new LinkedHashMap<Long, LinearGradient>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LinearGradient> eldest) {
            return size() > 32;
        }
    };

    // At the top: 7 top-bar screens (topIndex = 1 "Home" by default), sub-tabs ("TV Shows" = 0 default, "Movies" = 1)
    private int tab, topIndex = SCREEN_HOME, cardIndex;
    /** Sub-tab: 0 = TV Shows ("tv", default), 1 = Movies ("movie"). */
    private int subTab = 0;
    /** Selected genre index: 0 = All Genres, 1..N = activeGenres.get(genreIndex - 1). */
    private int genreIndex = 0;
    /** Focused pill in Z_SUBTABS: 0 = Series, 1 = Movies, 2 = All Genres, 3.. = activeGenres. */
    private int subIndex = 0;
    private List<String> activeGenres = new ArrayList<>();
    private final List<RectF> subRects = new ArrayList<>();
    private boolean reorderGenre;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The 7 top-bar items (plus extra room for legacy buttons).
    private final Spring[] topFocus = new Spring[Math.max(TOP_BAR_COUNT, TAB_COUNT + HomeApps.MAX_TOP)];
    private final RectF[] topRects = new RectF[Math.max(TOP_BAR_COUNT, TAB_COUNT + HomeApps.MAX_TOP)];
    final TopBarPillState topPill = new TopBarPillState();
    private final RectF topPillRect = new RectF();
    private final Spring[] cardFocus = new Spring[Recs.MAX_CARDS];
    /** How open (wide) each card is: 1 for the one at cardIndex, 0 for the others; the widths always add up to the same. */
    private final Spring[] cardOpen = new Spring[Recs.MAX_CARDS];
    /** Picks per tab (Prefs.cards()), read when the cards update, not on every frame. */
    private int cardSlots = 3;
    /** The row scrolls in whole cards: the focused one stays among the first three. */
    private final Spring cardScroll = new Spring(0.32f, 0.78f, 0);
    // Settings > Top of the screen: the picks (above), what you were watching in your apps (a row of 16:9 cards that
    // scrolls, Z_WATCH) or nothing. Its texts are measured once per list and its pictures decoded once (WatchArt), so
    // drawing the row costs no more than the picks.
    private String topMode = Prefs.TOP_PICKS;
    private List<WatchNext.Item> allWatching = new ArrayList<>();
    private List<WatchNext.Item> watching = new ArrayList<>();
    private final Spring[] watchFocus = new Spring[WatchNext.MAX];
    private final Spring watchScroll = new Spring(0.32f, 1f, 0);
    private int watchIndex;
    private boolean watchAllowed = true;
    private boolean watchLoaded;   // read at least once: before that, no "nothing half-watched"
    private String[] watchTitles, watchMetas;
    private float watchTextsK;
    private int watchTextsGen = -1;
    private LinearGradient watchSheen, watchRim;   // focus sheen and rim, made once per card size (not per frame)
    private float watchShaderW;
    private WatchArt watchArt;
    // Holding OK on a card: a small menu (hide it, open the app, cancel) on its own layer, drawn only while it shows.
    private static final float MENU_W = 720, MENU_PAD = 44, MENU_INSET = 16, MENU_ROW = 76, MENU_STEP = 80, MENU_R = 36;
    private final Spring menu = new Spring(0.26f, 0.86f, 0), menuPick = new Spring(0.22f, 0.9f, 0);
    private final String[] menuOptions = new String[8];
    private final String[] menuIcons = new String[8];
    private int menuOptionCount = 3;
    private final RectF menuPanel = new RectF();
    private final Set<String> hiddenNow = new HashSet<>();   // hidden this session (a reload already under way may not know)
    private boolean menuOpen;
    private int menuIndex;
    private WatchNext.Item menuItem;
    private Recs.Pick menuPickCard;
    private String menuPickRowKind;
    private String menuTitle = "", menuSub = "";
    private float menuOptionsTop;
    private Paint menuTitlePaint, menuSubPaint, menuOptionPaint;
    private final RectF[] cardRects = new RectF[Recs.MAX_CARDS];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lSubTab, lSubTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;
    // Continue Watching: the background takes the focused card's colour (worked out once per picture, WatchArt), easing
    // from one to the next. Drawn as a plain fill under a fixed shade, so a colour change creates nothing per frame.
    // Until the first picture's colour is in (a cold start reads the list and the pictures), the tint is a cool grey,
    // so the background and the glass already look as they will and only ease to the card's colour.
    private static final int TINT_WAIT = 0xFF8C93A0;
    private Shader tintShade;
    private int tintFrom = TINT_WAIT, tintTo = TINT_WAIT;
    private boolean tinted;   // a card's colour is in (else TINT_WAIT)
    private final Spring tintMix = new Spring(0.7f, 1f, 1);
    // The glass (shelf, drawer) reads that same colour: two flat images blended while it eases (Android 13+, where the
    // glass reads images), so nothing is made per frame. On older TVs the glass is a flat fill of glassLight().
    private int[] tintGlassPx;
    private boolean glassEasing;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("stremio", new int[] {0xFF7B4CA0, 0xFF3E1F5E, 1});
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving shelf apps or top buttons: hold OK, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderTop, reorderAnim, okHeld, holdArmed;
    private int orderRevision, pendingOrderSaves;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring[] topSlide = new Spring[HomeApps.MAX_TOP];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private int drawerHome;            // where the Home Assistant tiles start (the list's size without them)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    private float touchX, touchY;
    private final RectF hitBox = new RectF();   // the mouse's hit tests (menu rows, Continue Watching cards)

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF(), tmpCardBox = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    // Pre-allocated theme vignette fallback scrims & unified non-hero canvas (Zero allocations in render loop)
    private final Paint leftVigPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bottomVigPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint topVigPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint unifiedBgBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint unifiedBgGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private LinearGradient leftVigGrad, bottomVigGrad, topVigGrad, unifiedBgBaseGrad;
    private android.graphics.RadialGradient unifiedBgGlowGrad;
    private Bitmap unifiedBgBitmap;
    private int[] unifiedBgPixels;
    private int lastUnifiedNightStep = -1;
    private int lastVigThemeColor = -1;
    private int lastVigW = -1, lastVigH = -1;
    private boolean useShaderVignette = (android.os.Build.VERSION.SDK_INT >= 33);
    private boolean sceneForceFallbackApplied = false;
    private int consecutiveSlowFrames = 0;
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;

    // Top Details Header & Auto-Scroll Overview Marquee (Milestone 4)
    static final class HeaderState {
        String mediaKey = "";
        int subTab = 0;

        // Row 1: Title
        String title = "";
        String displayTitle = "";

        // Row 2: Metadata (Duration · Dates · Ratings)
        String durationText = null;  // e.g. "45min" or "1:45"
        String datesText = null;     // e.g. "2009-2016", "Aug 2022", "YYYY-Now"
        boolean hasRt = false;
        String rtScoreText = null;   // e.g. "94%"
        boolean hasMc = false;
        String mcScoreText = null;   // e.g. "7.8"
        boolean hasImdb = false;
        String imdbScoreText = null; // e.g. "IMDb 8.5"
        boolean hasRow2 = false;

        // Row 3: Genres
        String genresText = "";      // e.g. "Drama | Crime | Mystery"

        // Separate gray Continue Watching status line (e.g. "Continue Watching S1E10")
        String continueStatusText = "";

        // Row 4: Multi-Line Summary / Overview (never truncated, vertical auto-scroll)
        String overviewText = "";
        StaticLayout overviewLayout = null;
        int overviewLayoutWidth = 0;
        float overviewTextSize = 0f;

        void clear() {
            mediaKey = "";
            subTab = 0;
            title = "";
            displayTitle = "";
            durationText = null;
            datesText = null;
            hasRt = false;
            rtScoreText = null;
            hasMc = false;
            mcScoreText = null;
            hasImdb = false;
            imdbScoreText = null;
            hasRow2 = false;
            genresText = "";
            continueStatusText = "";
            overviewText = "";
            overviewLayout = null;
            overviewLayoutWidth = 0;
            overviewTextSize = 0f;
        }

        void copyFrom(HeaderState o) {
            if (o == null) {
                clear();
                return;
            }
            this.mediaKey = o.mediaKey;
            this.subTab = o.subTab;
            this.title = o.title;
            this.displayTitle = o.displayTitle;
            this.durationText = o.durationText;
            this.datesText = o.datesText;
            this.hasRt = o.hasRt;
            this.rtScoreText = o.rtScoreText;
            this.hasMc = o.hasMc;
            this.mcScoreText = o.mcScoreText;
            this.hasImdb = o.hasImdb;
            this.imdbScoreText = o.imdbScoreText;
            this.hasRow2 = o.hasRow2;
            this.genresText = o.genresText;
            this.continueStatusText = o.continueStatusText;
            this.overviewText = o.overviewText;
            this.overviewLayout = o.overviewLayout;
            this.overviewLayoutWidth = o.overviewLayoutWidth;
            this.overviewTextSize = o.overviewTextSize;
        }
    }

    /**
     * Encapsulates the snappy, springy sliding pill indicator animation for the Top Bar.
     */
    /**
     * Pure, deterministic state machine for hero backdrop crossfades and mid-fade `A -> B -> A` reversals.
     * Guarantees `bgFrom != bgTo` on every frame so Android HWUI never draws the same `HeroSlot` `RenderNode`
     * twice in one parent `sky` DisplayList.
     */
    public static final class BackdropTransitionState {
        public Backdrop bgFrom;
        public Backdrop bgTo;
        public long bgFadeAt;

        public boolean transitionTo(Backdrop b, long upMs, float durationMs) {
            if (b == bgTo && (b == null || !b.isRecycled())) {
                return false;
            }
            if (bgTo != null && bgTo.isRecycled()) {
                bgTo = null;
            }
            if (bgFrom != null && bgFrom.isRecycled()) {
                bgFrom = null;
            }
            if (bgFadeAt != 0L && b != null && b == bgFrom && bgTo != null && bgTo != b) {
                float prevT = Math.max(0f, Math.min(1f, (upMs - bgFadeAt) / Math.max(1f, durationMs)));
                float prevE = prevT * prevT * (3f - 2f * prevT);
                if (prevE <= 0.06f) {
                    bgFrom = null;
                    bgTo = b;
                    bgFadeAt = 0L;
                } else {
                    bgFrom = bgTo;
                    bgTo = b;
                    bgFadeAt = upMs - Math.round((1f - prevT) * durationMs);
                }
            } else {
                float curT = bgFadeAt == 0L ? 1f : Math.max(0f, Math.min(1f, (upMs - bgFadeAt) / Math.max(1f, durationMs)));
                float curE = curT * curT * (3f - 2f * curT);
                if (bgFadeAt == 0L || curE >= 0.5f || bgFrom == null) {
                    bgFrom = bgTo;
                }
                bgTo = b;
                bgFadeAt = upMs;
            }
            if (bgFrom == bgTo) {
                bgFrom = null;
                if (bgTo != null) {
                    bgFadeAt = 0L;
                }
            }
            return true;
        }
    }

    public static final class TopBarPillState {
        public final Spring left = new Spring(0.115f, 0.92f, 0f);
        public final Spring right = new Spring(0.115f, 0.92f, 0f);
        public final Spring alpha = new Spring(0.10f, 0.95f, 0f);

        public void retarget(float leftBound, float rightBound, boolean focused, boolean snap) {
            if (focused && rightBound > leftBound) {
                boolean wasHidden = !isVisible();
                left.target = leftBound;
                right.target = rightBound;
                alpha.target = 1f;
                if (snap) {
                    left.snap(leftBound);
                    right.snap(rightBound);
                    alpha.snap(1f);
                } else if (wasHidden) {
                    left.snap(leftBound);
                    right.snap(rightBound);
                }
            } else {
                alpha.target = 0f;
                if (snap) {
                    alpha.snap(0f);
                }
            }
        }

        public void retarget(RectF targetRect, boolean focused, boolean snap) {
            if (targetRect != null && !targetRect.isEmpty()) {
                retarget(targetRect.left, targetRect.right, focused, snap);
            } else {
                retarget(0f, 0f, false, snap);
            }
        }

        public boolean step(float dt) {
            return left.step(dt) | right.step(dt) | alpha.step(dt);
        }

        public boolean settled() {
            return left.settled() && right.settled() && alpha.settled();
        }

        public void snapToTarget() {
            left.snap(left.target);
            right.snap(right.target);
            alpha.snap(alpha.target);
        }

        public boolean isVisible() {
            return alpha.value > 0.01f && (right.value > left.value);
        }

        public float computePillCenterX() {
            return (left.value + right.value) / 2f;
        }

        public float computePillWidth() {
            float a = Math.max(0f, Math.min(1f, alpha.value));
            float s = 1f + 0.05f * a;
            return (right.value - left.value) * s;
        }

        public RectF computePillRect(float baseTop, float baseBottom, RectF out) {
            if (out == null) out = new RectF();
            float a = Math.max(0f, Math.min(1f, alpha.value));
            float s = 1f + 0.05f * a;
            float cx = (left.value + right.value) / 2f;
            float w = (right.value - left.value) * s;
            float cy = (baseTop + baseBottom) / 2f;
            float h = (baseBottom - baseTop) * s;
            out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            return out;
        }
    }

    public static final int PROGRESS_TRACK_COLOR = 0x4DFFFFFF;
    public static final int PROGRESS_FILL_COLOR = 0xF2FFFFFF;

    static final float HOME_ROWS_START_Y = 556f;
    public static final float MEDIA_ROW_HEIGHT = 406f;
    public static final float MEDIA_CARDS_TOP_OFFSET = 76f;
    public static final float MEDIA_CARD_W = 196f;
    public static final float MEDIA_CARD_H = 294f;
    public static final float MEDIA_CARD_GAP = 20f;

    public static final float WIDE_BROWSER_ROW_HEIGHT = 348f;
    public static final float WIDE_BROWSER_CARD_W = 352f;
    public static final float WIDE_BROWSER_CARD_H = 198f;
    public static final float WIDE_BROWSER_CARD_GAP = 28f;

    static final float OVERVIEW_FONT_SIZE = 26f;
    static final float OVERVIEW_WRAP_WIDTH = 960f;
    static final float OVERVIEW_BOX_TOP = 280f;
    static final float CONTINUE_STATUS_BASELINE = 290f;
    static final float OVERVIEW_BOX_TOP_WITH_CONTINUE = 314f;
    public static final float CONTINUE_EPISODE_BASELINE_OFFSET = 26f;
    public static final float CONTINUE_SCRIM_HEIGHT_DP = 136f;
    static final int[] CONTINUE_SCRIM_COLORS = new int[] { 0x00000000, 0xB8000000, 0xF2000000 };
    static final float[] CONTINUE_SCRIM_STOPS = new float[] { 0.0f, 0.52f, 1.0f };

    /**
     * Evaluates the piecewise-linear alpha ({@code 0..1}) of the Continue Watching bottom dark scrim at normalized
     * vertical fraction {@code frac} ({@code 0.0} = top of scrim, {@code 1.0} = bottom of card).
     */
    public static float continueScrimAlphaAtFraction(float frac) {
        float f = Math.max(0f, Math.min(1f, frac));
        for (int i = 1; i < CONTINUE_SCRIM_STOPS.length; i++) {
            float s0 = CONTINUE_SCRIM_STOPS[i - 1];
            float s1 = CONTINUE_SCRIM_STOPS[i];
            if (f <= s1 || i == CONTINUE_SCRIM_STOPS.length - 1) {
                float a0 = ((CONTINUE_SCRIM_COLORS[i - 1] >>> 24) & 0xFF) / 255f;
                float a1 = ((CONTINUE_SCRIM_COLORS[i] >>> 24) & 0xFF) / 255f;
                float t = (s1 > s0) ? (f - s0) / (s1 - s0) : 1f;
                return a0 + (a1 - a0) * Math.max(0f, Math.min(1f, t));
            }
        }
        return 0f;
    }

    public static float continueEpisodeBaseline(float cellBottom, float k, float s) {
        return cellBottom - CONTINUE_EPISODE_BASELINE_OFFSET * k * s;
    }
    static final float OVERVIEW_BOX_BOTTOM = 516f;
    static final float NON_HOME_DIM_MIX = 0.50f;
    static final int OVERVIEW_DARK_COLOR = 0xFFFFFFFF;
    static final int CONTINUE_STATUS_DARK_COLOR = 0xFF98989D;
    static final int UNIFIED_BG_DARK_TOP = 0xFF121C3D;
    static final int UNIFIED_BG_DARK_MID = 0xFF0C1329;
    static final int UNIFIED_BG_DARK_BOTTOM = 0xFF070A16;
    static final int UNIFIED_BG_DARK_GLOW = 0x780EA5E9;
    static final int UNIFIED_BG_DARK_VIOLET = 0xFF6D28D9;
    static final int UNIFIED_BG_DARK_CYAN = 0xFF0284C7;
    static final int UNIFIED_BG_DARK_TEAL = 0xFF0D9488;
    static final int UNIFIED_BG_DARK_MAGENTA = 0xFF9D174D;
    static final int UNIFIED_BG_LIGHT_TOP = 0xFFFFFFFF;
    static final int UNIFIED_BG_LIGHT_MID = 0xFFFCFDFF;
    static final int UNIFIED_BG_LIGHT_BOTTOM = 0xFFF5F8FF;
    static final int UNIFIED_BG_LIGHT_GLOW = 0x24FFFFFF;
    static final int UNIFIED_BG_LIGHT_VIOLET = 0xFFC4B5FD;
    static final int UNIFIED_BG_LIGHT_CYAN = 0xFF7DD3FC;
    static final int UNIFIED_BG_LIGHT_TEAL = 0xFF99F6E4;
    static final int UNIFIED_BG_MESH_W = 320;
    static final int UNIFIED_BG_MESH_H = 180;
    static final int SETTINGS_LEFT_PANEL_DARK = 0x1AFFFFFF;
    static final int SETTINGS_LEFT_PANEL_LIGHT = 0x42FFFFFF;
    static final int SETTINGS_RIGHT_PANEL_DARK = 0x12FFFFFF;
    static final int SETTINGS_RIGHT_PANEL_LIGHT = 0x30FFFFFF;

    /**
     * Evaluates the rich, multi-layered Cosmic Aurora / Nebula mesh color at normalized coordinates
     * {@code (u, v)} in {@code [0..1] x [0..1]} for non-hero screens ({@code Search}, {@code Apps},
     * {@code Reels}, {@code Smart-Home}, {@code Settings}).
     *
     * <p><b>Why:</b> Replaces the previous flat charcoal backdrop with an atmospheric aurora ribbon and
     * jewel-toned sapphire/amethyst/teal light pools while keeping luminance comfortably controlled so
     * white typography and Liquid Glass panels stay crisp.
     */
    static int sampleUnifiedAuroraColor(float u, float v, float nightMix, float dither) {
        float n = Math.max(0f, Math.min(1f, nightMix));
        // Base vertical cosmic gradient (Dark) & airy pearl-sky gradient (Light)
        int darkBase = v < 0.52f
                ? mix(UNIFIED_BG_DARK_TOP, UNIFIED_BG_DARK_MID, v / 0.52f)
                : mix(UNIFIED_BG_DARK_MID, UNIFIED_BG_DARK_BOTTOM, (v - 0.52f) / 0.48f);
        int lightBase = v < 0.52f
                ? mix(UNIFIED_BG_LIGHT_TOP, UNIFIED_BG_LIGHT_MID, v / 0.52f)
                : mix(UNIFIED_BG_LIGHT_MID, UNIFIED_BG_LIGHT_BOTTOM, (v - 0.52f) / 0.48f);

        float darkBaseScale = 1.0f - 0.16f * n;
        float darkBaseR = ((darkBase >> 16) & 0xFF) * darkBaseScale;
        float darkBaseG = ((darkBase >> 8) & 0xFF) * darkBaseScale;
        float darkBaseB = (darkBase & 0xFF) * darkBaseScale;
        float baseR = (((lightBase >> 16) & 0xFF) * (1f - n) + darkBaseR * n);
        float baseG = (((lightBase >> 8) & 0xFF) * (1f - n) + darkBaseG * n);
        float baseB = ((lightBase & 0xFF) * (1f - n) + darkBaseB * n);

        // 1. Flowing S-curve diagonal Aurora Ribbon sweeping from lower-left violet to upper-right cyan
        double waveCenter = 0.58 - 0.36 * u + 0.085 * Math.sin(u * 4.9 + 0.55) + 0.038 * Math.cos(u * 9.4 - 1.1);
        double dyRibbon = (v - waveCenter) / 0.23;
        float ribbon = (float) Math.exp(-dyRibbon * dyRibbon);
        int darkRibbonCol = mix(UNIFIED_BG_DARK_VIOLET, UNIFIED_BG_DARK_CYAN, Math.max(0f, Math.min(1f, u * 1.08f - 0.04f)));
        int lightRibbonCol = mix(UNIFIED_BG_LIGHT_VIOLET, UNIFIED_BG_LIGHT_CYAN, Math.max(0f, Math.min(1f, u * 1.08f - 0.04f)));
        float ribWeight = ribbon * (0.08f + 0.34f * n);
        baseR = baseR * (1f - ribWeight) + (((lightRibbonCol >> 16) & 0xFF) * (1f - n) + ((darkRibbonCol >> 16) & 0xFF) * n) * ribWeight;
        baseG = baseG * (1f - ribWeight) + (((lightRibbonCol >> 8) & 0xFF) * (1f - n) + ((darkRibbonCol >> 8) & 0xFF) * n) * ribWeight;
        baseB = baseB * (1f - ribWeight) + ((lightRibbonCol & 0xFF) * (1f - n) + (darkRibbonCol & 0xFF) * n) * ribWeight;

        // 2. Orb A: Upper-Left crisp white reading sanctuary in Light Mode; Royal Amethyst in Dark Mode
        float dxA = (u - 0.15f) * 1.35f, dyA = (v - 0.22f);
        float orbA = (float) Math.exp(-(dxA * dxA + dyA * dyA) / 0.20f);
        int darkOrbA = mix(UNIFIED_BG_DARK_VIOLET, UNIFIED_BG_DARK_MAGENTA, 0.28f);
        float wA = orbA * (0.04f + 0.42f * n);
        baseR = baseR * (1f - wA) + (((UNIFIED_BG_LIGHT_VIOLET >> 16) & 0xFF) * (1f - n) + ((darkOrbA >> 16) & 0xFF) * n) * wA;
        baseG = baseG * (1f - wA) + (((UNIFIED_BG_LIGHT_VIOLET >> 8) & 0xFF) * (1f - n) + ((darkOrbA >> 8) & 0xFF) * n) * wA;
        baseB = baseB * (1f - wA) + ((UNIFIED_BG_LIGHT_VIOLET & 0xFF) * (1f - n) + (darkOrbA & 0xFF) * n) * wA;

        // 3. Orb B: Upper-Right airy silk sky whisper in Light Mode; Electric Sapphire-Cyan in Dark Mode
        float dxB = (u - 0.84f) * 1.35f, dyB = (v - 0.16f);
        float orbB = (float) Math.exp(-(dxB * dxB + dyB * dyB) / 0.24f);
        float wB = orbB * (0.20f + 0.26f * n);
        baseR = baseR * (1f - wB) + (((UNIFIED_BG_LIGHT_CYAN >> 16) & 0xFF) * (1f - n) + ((UNIFIED_BG_DARK_CYAN >> 16) & 0xFF) * n) * wB;
        baseG = baseG * (1f - wB) + (((UNIFIED_BG_LIGHT_CYAN >> 8) & 0xFF) * (1f - n) + ((UNIFIED_BG_DARK_CYAN >> 8) & 0xFF) * n) * wB;
        baseB = baseB * (1f - wB) + ((UNIFIED_BG_LIGHT_CYAN & 0xFF) * (1f - n) + (UNIFIED_BG_DARK_CYAN & 0xFF) * n) * wB;

        // 4. Orb C: Lower-Right delicate horizon tint in Light Mode; Emerald-Teal in Dark Mode
        float dxC = (u - 0.78f) * 1.30f, dyC = (v - 0.86f);
        float orbC = (float) Math.exp(-(dxC * dxC + dyC * dyC) / 0.22f);
        float wC = orbC * (0.10f + 0.26f * n);
        baseR = baseR * (1f - wC) + (((UNIFIED_BG_LIGHT_TEAL >> 16) & 0xFF) * (1f - n) + ((UNIFIED_BG_DARK_TEAL >> 16) & 0xFF) * n) * wC;
        baseG = baseG * (1f - wC) + (((UNIFIED_BG_LIGHT_TEAL >> 8) & 0xFF) * (1f - n) + ((UNIFIED_BG_DARK_TEAL >> 8) & 0xFF) * n) * wC;
        baseB = baseB * (1f - wC) + ((UNIFIED_BG_LIGHT_TEAL & 0xFF) * (1f - n) + (UNIFIED_BG_DARK_TEAL & 0xFF) * n) * wC;

        // Overall ~15% dim on the default Dark Mode aurora background (Light Mode n=0 stays 1.0f untouched)
        float darkOverallScale = 1.0f - 0.15f * n;
        baseR *= darkOverallScale;
        baseG *= darkOverallScale;
        baseB *= darkOverallScale;

        int rOut = Math.max(0, Math.min(255, Math.round(baseR + dither)));
        int gOut = Math.max(0, Math.min(255, Math.round(baseG + dither)));
        int bOut = Math.max(0, Math.min(255, Math.round(baseB + dither)));
        return 0xFF000000 | (rOut << 16) | (gOut << 8) | bOut;
    }

    /**
     * Generates a {@code width x height} ARGB pixel buffer for the dithered Unified Cosmic Aurora Mesh.
     * Invoked only when screen dimensions or theme mode changes (zero per-frame allocations).
     */
    static int[] generateUnifiedAuroraPixels(int width, int height, float nightMix, int[] reuse) {
        int w = Math.max(2, width);
        int h = Math.max(2, height);
        int[] out = (reuse != null && reuse.length == w * h) ? reuse : new int[w * h];
        for (int y = 0; y < h; y++) {
            float v = (y + 0.5f) / h;
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w;
                // Deterministic hash dither (+-0.65 LSB) eliminates 8-bit gradient banding on 4K TVs
                int hash = (x * 73856093) ^ (y * 19349663);
                hash = (hash ^ (hash >>> 13)) * 1274126177;
                float dither = (((hash & 0xFF) / 255f) - 0.5f) * 1.3f;
                out[rowOff + x] = sampleUnifiedAuroraColor(u, v, nightMix, dither);
            }
        }
        return out;
    }

    private static final String SEP_DOT = " · ";
    private static final String RATING_SEP = "   ";
    private float sepDotWidth;
    private float ratingSepWidth;

    /**
     * Returns {@code true} for screens that render dynamic item-driven hero/pattern backdrops
     * ({@link #SCREEN_HOME} and {@link #SCREEN_GAMING}), and {@code false} for all screens that share
     * the unified ambient background.
     */
    static boolean usesHeroBackdropMode(int screenIdx) {
        return screenIdx == SCREEN_HOME || screenIdx == SCREEN_GAMING;
    }

    static boolean shouldUpdateBackdropFromScreenRows(int screenIdx) {
        return usesHeroBackdropMode(screenIdx);
    }

    static float effectiveOverviewBoxTop(HeaderState s) {
        return OVERVIEW_BOX_TOP;
    }

    static String resolveContinueWatchingOverview(boolean isSeries, String episodeOverview, String seriesOverview) {
        if (isSeries && episodeOverview != null && !episodeOverview.trim().isEmpty()) {
            return Recs.formatOverviewText(episodeOverview);
        }
        return Recs.formatOverviewText(seriesOverview);
    }

    static String formatHeaderRow2Layout(HeaderState s) {
        if (s == null || !s.hasRow2) return "";
        StringBuilder sb = new StringBuilder();
        boolean needDot = false;
        if (s.durationText != null && !s.durationText.isEmpty()) {
            sb.append(s.durationText);
            needDot = true;
        }
        if (s.datesText != null && !s.datesText.isEmpty()) {
            if (needDot) sb.append(SEP_DOT);
            sb.append(s.datesText);
            needDot = true;
        }
        List<String> ratings = new ArrayList<>(3);
        if (s.hasImdb && s.imdbScoreText != null && !s.imdbScoreText.isEmpty()) {
            ratings.add(s.imdbScoreText);
        }
        if (s.hasMc && s.mcScoreText != null && !s.mcScoreText.isEmpty()) {
            ratings.add("[mc] " + s.mcScoreText);
        }
        if (s.hasRt && s.rtScoreText != null && !s.rtScoreText.isEmpty()) {
            ratings.add("[rt] " + s.rtScoreText);
        }
        if (!ratings.isEmpty()) {
            if (needDot) sb.append(SEP_DOT);
            sb.append(String.join(" ", ratings));
        }
        return sb.toString();
    }
    private TextPaint headerTitlePaint;
    private Paint headerMetaPaint;
    private Paint headerGenrePaint;
    private Paint headerContinuePaint;
    private TextPaint overviewPaint;
    private TextPaint continueEpisodePaint;
    private final LinearGradient continueScrimUnitGrad = new LinearGradient(
            0f, 0f, 0f, 1f, CONTINUE_SCRIM_COLORS, CONTINUE_SCRIM_STOPS, Shader.TileMode.CLAMP);
    private final Matrix continueScrimMatrix = new Matrix();
    private final HeaderState currentHeader = new HeaderState();
    private final HeaderState previousHeader = new HeaderState();
    private final HeaderState tmpHeader = new HeaderState();
    private long headerFadeAt = 0L;
    private static final float HEADER_FADE_MS = 150f;

    private float overviewScrollY = 0f;
    private float overviewMaxScroll = 0f;
    private long overviewStartAt = 0L;
    private boolean overviewActive = false;
    private long lastOverviewCycle = 0L;
    private LinearGradient overviewFadeTop, overviewFadeBottom, overviewFadeBoth;
    private LinearGradient overviewFadeTopCompact, overviewFadeBottomCompact, overviewFadeBothCompact;
    private final Matrix overviewShaderMatrix = new Matrix();
    private int lastOverviewShaderColor;

    static final long OVERVIEW_PAUSE_START_MS = 7500L;    // 7.5s calm initial reading pause before scrolling starts
    static final float OVERVIEW_SCROLL_SPEED = 5.5f;      // 5.5 virtual px/sec gentle vertical reading scroll
    static final long OVERVIEW_PAUSE_END_MS = 7500L;      // 7.5s pause at bottom before returning to head
    static final long OVERVIEW_RESET_DURATION_MS = 1500L; // 1.5s smooth ease back to top
    private static final float OVERVIEW_FADE_H = 28f;    // 28 virtual px vertical top/bottom edge fade

    private final Runnable overviewWake = this::wake;

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case RECENT: if (recents != null) recents.draw(c, w, h); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            setNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 2
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
        }

        @Override public void onLost(Network network) {
            setNetwork(0);
        }
    };

    /** Wi-Fi or Ethernet, for the icon at the top; none when the TV has no network. */
    private void setNetwork(int type) {
        if (type == network) return;
        network = type;
        status.invalidate();
    }

    private final BroadcastReceiver deviceCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /** The drive broadcasts, in a filter of their own (see startStatus). */
    private final BroadcastReceiver mediaCallback = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateDevices();
        }
    };

    /**
     * Bluetooth (when something is connected) and a USB drive, for the icons at the top. Only the audio profiles count:
     * a Bluetooth remote is always connected, and its HID connection would keep the icon on for good. The queries go to
     * LOADER: a Bluetooth remote that reconnects on the first key press after a while would otherwise pay for the
     * profile states, the storage volumes and the binder calls on that very press.
     */
    private void updateDevices() {
        LOADER.execute(() -> {
            boolean bt = false;
            try {
                if (bluetooth != null && bluetooth.isEnabled()) {
                    bt = bluetooth.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                            || bluetooth.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
                }
            } catch (RuntimeException ignored) {
            }
            boolean drive = false;
            try {
                StorageManager sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        // Only a mounted one: #192's unmounted drive doesn't show.
                        if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            drive = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            boolean btOn = bt, driveOn = drive;
            handler.post(() -> setDevices(btOn, driveOn));
        });
    }

    private void setDevices(boolean bt, boolean drive) {
        if (bt == bluetoothConnected && drive == usbConnected) return;
        bluetoothConnected = bt;
        usbConnected = drive;
        status.invalidate();
    }

    /** Reads the icons' state and listens for changes only while the setting is on (nothing runs with it off). */
    private void startStatus() {
        if (!Prefs.statusIcons()) return;
        updateDevices();
        // The registration hands the current network over at once, so the first read is this callback. With no
        // network at all it hands nothing, and the icon would keep the one from before the pause (Wi-Fi turned off).
        if (connectivity != null && !networkRegistered) {
            try {
                if (connectivity.getActiveNetwork() == null) setNetwork(0);
                connectivity.registerDefaultNetworkCallback(networkCallback, handler);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!devicesRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            // Headphones connect their link first and the audio a moment later; with a Bluetooth remote already
            // connected the adapter's own state doesn't change then, so the profiles' broadcasts say it.
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(deviceCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(deviceCallback, f);
                devicesRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        // A mounted drive's broadcast carries a file:// URI: in the same filter as the Bluetooth actions it would make
        // those never match (they have no data), so it gets a filter of its own.
        if (!mediaRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_MEDIA_MOUNTED);
            f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(Intent.ACTION_MEDIA_EJECT);
            f.addAction(Intent.ACTION_MEDIA_REMOVED);
            f.addDataScheme("file");
            try {
                if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(mediaCallback, f, Context.RECEIVER_NOT_EXPORTED);
                else getContext().registerReceiver(mediaCallback, f);
                mediaRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopStatus() {
        if (connectivity != null && networkRegistered) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkRegistered = false;
        }
        if (devicesRegistered || mediaRegistered) {
            // Registered once per filter, so unregistered once per filter.
            if (devicesRegistered) {
                try {
                    getContext().unregisterReceiver(deviceCallback);
                } catch (RuntimeException ignored) {
                }
                devicesRegistered = false;
            }
            if (mediaRegistered) {
                try {
                    getContext().unregisterReceiver(mediaCallback);
                } catch (RuntimeException ignored) {
                }
                mediaRegistered = false;
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            playing.start();   // the listener allowed after the screen showed (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            // Update picks only outside SCREEN_HOME to freeze active tiles during browsing
            if (activeScreen != SCREEN_HOME) {
                updatePicks();
            }
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, this::onPlaying);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        android.hardware.input.InputManager im = null;
        try {
            im = (android.hardware.input.InputManager) a.getSystemService(Context.INPUT_SERVICE);
        } catch (Throwable ignored) {}
        inputManager = im;
        connectivity = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth = bm == null ? null : bm.getAdapter();
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        } catch (RuntimeException e) {   // some projectors (Rockchip rk3566 HY300) ship without the launcherapps service
            AppLog.w("no app-change callback: " + e.getMessage());
        }
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        recentLayer = new Layer(a, RECENT);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        // Keep full-screen layers in lightweight RenderNode DisplayLists (LAYER_TYPE_NONE) while caching only the
        // compact 1920x140 status bar (~1.0 MB) in LAYER_TYPE_HARDWARE so top-bar LIQUID glass pills never re-shade during scrolling.
        for (View v : new View[] {heroLayer, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(LAYER_TYPE_NONE, null);
        status.setLayerType(LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer, recentLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        initLiquidGlassHiddenEdit(a);
        setContentDescription("SoarTV");
        recentLayer.setVisibility(INVISIBLE);
        drawerLayer.setVisibility(INVISIBLE);
        menuLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.20f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < topSlide.length; i++) topSlide[i] = new Spring(0.32f, 0.86f, 0);
        for (int i = 0; i < Recs.MAX_CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardOpen[i] = new Spring(0.32f, 0.78f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < watchFocus.length; i++) watchFocus[i] = new Spring(0.32f, 0.78f, 0);
        topMode = Prefs.topContent();
        watchArt = new WatchArt(a, () -> {
            heroDirty = true;
            updateTint();
            heroLayer.invalidate();
            if (activeScreen == SCREEN_HOME && homeRow == 0) {
                handler.removeCallbacks(bgFollow);
                handler.postDelayed(bgFollow, 40);
            }
            wake();
        });
        setLightButtons(HomeApps.topButtons(null));
        recs.setLight(true);
        recs.setBackdropCallback((key, b) -> {
            if (!running || b == null || b.isRecycled()) return;
            if (activeScreen == SCREEN_HOME && b != bgTo) {
                String expectedKey = focusedHomeBackdropKey();
                if (key == null || expectedKey == null || key.equals(expectedKey)) {
                    showBackdrop(b);
                    wake();
                }
            }
        });
        recs.setPosterCallback(() -> {
            if (running && activeScreen == SCREEN_HOME) {
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }
        });
        recs.setSyncProgressListener((step, pct, done) -> updateSyncModal(step, pct, done));
        CloudSyncManager.get(a).setUiRefreshCallback(() -> handler.post(() -> {
            Prefs.reload();
            recs.clearRowCache();
            recs.refreshIfStale();
            reload();
            rebuildSettingsTree();
            layoutLight();
            statusDirty = true;
            heroDirty = true;
            invalidateAll();
        }));
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        gamingStatus = GamingZone.getCachedLaptopStatus(a);
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        if (!Prefs.initialSetupDone() && !Trakt.hasOAuth(a) && !StremioAccountClient.isConfigured(a)) {
            activeScreen = SCREEN_SETTINGS;
            topIndex = SCREEN_SETTINGS;
            settingsPathIds.clear();
            settingsPathIds.add("initial_setup");
        }
        recs.setActiveSubKind(subTab == 0 ? "tv" : "movie");
        rebuildScreenRows();
        if (!usesHeroBackdropMode(activeScreen)) {
            neutralGlass();
        }
        refreshGamingScreenData(false);
        refreshReelsData();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        boolean h24 = Ui.h24();
        if (h24 != clock24) {   // 12/24 hours changed (the TV's or the Clock row's): the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        Recs.setBackgroundActivityPaused(false);
        ScreensaverCatalog.setBackgroundActivityPaused(false);
        CloudSyncManager.get(getContext().getApplicationContext()).setBackgroundPaused(false);
        if (ScreensaverCatalog.consumePausedPrefetchNeedsResume()) {
            ScreensaverCatalog.prefetchAsync(getContext());
        }
        RemoteControlServer.get(getContext().getApplicationContext()).start(this);
        refreshPairingPromptState();
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        if (inputManager != null) {
            try {
                inputManager.registerInputDeviceListener(inputDeviceListener, handler);
            } catch (Throwable ignored) {}
        }
        if (!usbReceiverRegistered) {
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
                f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                f.addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
                if (Build.VERSION.SDK_INT >= 33) {
                    getContext().registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
                } else {
                    getContext().registerReceiver(usbReceiver, f);
                }
                usbReceiverRegistered = true;
            } catch (Throwable ignored) {}
        }
        ControllerManager.setAsyncRefreshListener(() -> handler.post(this::onControllerConnectionChanged));
        ControllerManager.refreshHardwareLocationsAsync(getContext(), this::onControllerConnectionChanged);
        if (activeBtPairer != null && activeBtPairDialog != null
                && activeBtPairDialog.isShowing() && !activeBtPairer.isBondingInProgress()) {
            activeBtPairer.restartDiscovery();
        }
        startStatus();
        handler.postDelayed(tick, 15_000);
        playing.start();
        checkNotices();
        hiddenNow.retainAll(Prefs.watchHidden());   // Home Settings may have shown the hidden ones again
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (gamingOnResume) {
            gamingOnResume = false;
            selectScreen(SCREEN_GAMING);
            zone = Z_CARDS;
            retarget(false);
        } else if (reelsOnResume) {
            reelsOnResume = false;
            selectScreen(SCREEN_REELS);
            zone = Z_TOP;
            topIndex = SCREEN_REELS;
            retarget(false);
        } else if (settingsOnResume) {
            settingsOnResume = false;
            showSettingsScreen();
        } else if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        } else if (activeScreen == SCREEN_SETTINGS) {
            // Refresh live values (Wi-Fi, timezone, sleep timer, controllers) while preserving exact inner-Settings navigation
            rebuildSettingsTree();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        flushPendingTopBarScreenSwitch();
        handler.removeCallbacks(bgFollow);
        handler.removeCallbacks(overviewWake);
        cancelReorder(true);
        if (recents != null) recents.closeNow();
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        if (gameLaunchModalOpen) {
            gameLaunchModalOpen = false;
            toastLayer.invalidate();
        }
        closeMenu(true);
        hideSettingsSearchSoftInput();
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(bars);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        Recs.setBackgroundActivityPaused(true);
        ScreensaverCatalog.setBackgroundActivityPaused(true);
        CloudSyncManager.get(getContext().getApplicationContext()).setBackgroundPaused(true);
        recs.flushPendingSaveAsync();
        RemoteControlServer.get(getContext().getApplicationContext()).detachHost(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        if (inputManager != null) {
            try {
                inputManager.unregisterInputDeviceListener(inputDeviceListener);
            } catch (Throwable ignored) {}
        }
        if (usbReceiverRegistered) {
            try {
                getContext().unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {}
            usbReceiverRegistered = false;
        }
        if (activeBtPairer != null && !activeBtPairer.isBondingInProgress()) {
            activeBtPairer.pauseDiscoveryOnly();
        }
        ControllerManager.setAsyncRefreshListener(null);
        stopStatus();
    }

    void destroy() {
        closeLiquidGlassModal(false);
        Backdrop.setDisplayedBackdrops(null, null);
        shadows.clear();
        clearPairingQrBitmap();
        settingsRenderer.recycleBitmaps();
        if (activeBtPairer != null) {
            activeBtPairer.stop();
            activeBtPairer = null;
            activeBtPairDialog = null;
        }
        pause();
        try {
            if (launcherApps != null) launcherApps.unregisterCallback(packages);
        } catch (RuntimeException ignored) {
        }
        click.release();
        watchArt.close();   // its thread is shared: nothing is left running for this view
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (usbReceiverRegistered) {
            try {
                getContext().unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {}
            usbReceiverRegistered = false;
        }
    }

    // MARK: - Headbar Actions & Companion Remote Host

    private void openReelsFeed() {
        LOADER.execute(() -> {
            List<SupabaseClient.SharedReel> reels = SupabaseClient.get(getContext()).fetchSharedReelsSync();
            handler.post(() -> {
                if (reels != null && !reels.isEmpty()) {
                    SupabaseClient.SharedReel latest = reels.get(0);
                    if (latest.videoUrl != null && !latest.videoUrl.isEmpty()) {
                        onRemoteCastUrl(latest.videoUrl, latest.title, latest.senderName);
                        return;
                    }
                }
                onRemoteCastUrl("https://www.youtube.com/shorts", "Reels & Shorts", "YouTube");
            });
        });
    }

    private void openMusicHub() {
        String[] musicPkgs = {
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android",
            "com.apple.android.music",
            "com.google.android.youtube.tv",
            "org.smarttube.stable"
        };
        PackageManager pm = activity.getPackageManager();
        for (String pkg : musicPkgs) {
            Intent launch = HomeApps.launchIntent(pm, pkg);
            if (launch != null) {
                try {
                    if ("com.google.android.youtube.tv".equals(pkg) || "org.smarttube.stable".equals(pkg)) {
                        Intent musicView = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com"))
                                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(musicView);
                    } else {
                        activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                    return;
                } catch (RuntimeException ignored) {
                }
            }
        }
        showToast(Ui.tr("No music app installed (YouTube Music, Spotify, Apple Music)."));
    }

    private void openAiSearch(String query) {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            showToast(Ui.tr("Gemini AI searching: ") + q);
            GeminiClient.get(getContext()).resolveQueryAsync(q, res -> handler.post(() -> {
                if (res != null && res.resolvedMedia != null) {
                    String msg = (res.summary != null && !res.summary.isEmpty())
                            ? res.summary
                            : (Ui.tr("Opening ") + res.resolvedMedia.name + (res.resolvedMedia.releaseInfo.isEmpty() ? "" : " (" + res.resolvedMedia.releaseInfo + ")"));
                    showToast(msg);
                    onRemoteCastUrl(res.resolvedMedia.stremioDeepLink, res.resolvedMedia.name, "Gemini AI");
                } else if (res != null && res.summary != null && !res.summary.isEmpty()) {
                    showToast(res.summary);
                } else {
                    showToast(Ui.tr("AI Search: ") + q);
                }
            }));
            return;
        }
        if (GeminiClient.get(getContext()).isConfigured()) {
            openAiSearch("Suggest one great popular sci-fi or thriller movie to watch tonight");
            return;
        }
        try {
            Intent searchIntent = new Intent(Intent.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (searchIntent.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(searchIntent);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        showToast(Ui.tr("Use Companion Remote (port ") + Config.REMOTE_SERVER_PORT + Ui.tr(") for Voice & AI Search"));
    }

    private void toggleMute() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                        android.media.AudioManager.FLAG_SHOW_UI);
                boolean muted = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
                showToast(muted ? Ui.tr("TV Audio Muted") : Ui.tr("TV Audio Unmuted"));
            }
        } catch (RuntimeException e) {
            AppLog.w("toggleMute failed: " + e.getMessage());
        }
    }

    private void toggleThemeMode() {
        boolean nextDark = !nightTarget;
        Prefs.setTheme(nextDark ? "dark" : "light");
        checkNight(true);
        if (activeScreen == SCREEN_SETTINGS) {
            rebuildSettingsTree();
        }
        showToast(nextDark ? Ui.tr("Dark Mode Enabled") : Ui.tr("Light Mode Enabled"));
    }

    private void setThemePreference(String theme, String autoMode) {
        Prefs.setTheme(theme);
        if (autoMode != null) {
            Prefs.setAutoThemeMode(autoMode);
        }
        checkNight(true);
        if (activeScreen == SCREEN_SETTINGS) {
            rebuildSettingsTree();
        }
        heroDirty = statusDirty = tilesDirty = true;
        invalidateAll();
        wake();
    }

    @Override
    public void onPairingPromptUpdated() {
        handler.post(this::refreshPairingPromptState);
    }

    @Override
    public void onRemoteKey(int keyCode) {
        Recs.notifyUserInteraction();
        wake();
        if (lgModalOpen) {
            handleLiquidGlassModalRemoteKey(keyCode);
            return;
        }
        if (streamQualityModalOpen && streamQualityModalState != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (streamQualityModalState.moveFocus(0, -1)) {
                        persistStreamQualityModalValues();
                        click.move();
                        toastLayer.invalidate();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (streamQualityModalState.moveFocus(0, 1)) {
                        persistStreamQualityModalValues();
                        click.move();
                        toastLayer.invalidate();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (streamQualityModalState.moveFocus(-1, 0)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (streamQualityModalState.moveFocus(1, 0)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    closeStreamQualityModal();
                    break;
                default:
                    break;
            }
            return;
        }
        if (slideDurationModalOpen && slideDurationModalState != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    slideDurationModalState.stepUp();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    slideDurationModalState.stepDown();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    closeSlideDurationModal();
                    break;
                default:
                    break;
            }
            return;
        }
        if (sleepModalOpen && sleepModalState != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    sleepModalState.moveFocus(0, -1);
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    sleepModalState.moveFocus(0, 1);
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    sleepModalState.moveFocus(-1, 0);
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    sleepModalState.moveFocus(1, 0);
                    click.move();
                    toastLayer.invalidate();
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                    int slot = sleepModalState.getFocusSlot();
                    if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_TOGGLE) {
                        sleepModalState.toggleEnabled();
                        click.move();
                        toastLayer.invalidate();
                    } else if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_ADD_FIVE) {
                        sleepModalState.addFiveMinutes();
                        click.move();
                        toastLayer.invalidate();
                    } else if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_CANCEL) {
                        closeSleepTimerModal();
                    } else {
                        saveSleepTimerModal();
                    }
                    break;
                }
                case KeyEvent.KEYCODE_BACK:
                    closeSleepTimerModal();
                    break;
                default:
                    break;
            }
            return;
        }
        if (activePendingPairing != null) {
            handlePairingModalRemoteKey(keyCode);
            return;
        }
        if (gameLaunchModalOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BACK) {
                cancelGameLaunchModalByUser();
            }
            return;
        }
        if (syncModalOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BACK) {
                syncModalOpen = false;
                toastLayer.invalidate();
                click.move();
                showToast(Ui.tr("Continuing sync in background…"));
            }
            return;
        }
        if (menuOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveMenu(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveMenu(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (smartCardOpen) adjustSmartCardValue(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (smartCardOpen) adjustSmartCardValue(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (smartCardOpen) {
                        if (smartCardDevice != null
                                && smartCardDevice.getProtocol() == SmartHomeManager.Protocol.WIZ_LIGHT
                                && menuIndex == 1) {
                            adjustSmartCardValue(1);
                        } else {
                            closeMenu(false);
                            click.open();
                        }
                    } else {
                        pickMenu();
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    closeMenu(false);
                    click.move();
                    break;
                default:
                    break;
            }
            return;
        }
        if (reordering) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shiftReorder(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shiftReorder(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    shiftReorder(reorderAppsGrid ? -COLUMNS : -1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    shiftReorder(reorderAppsGrid ? COLUMNS : 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    dropReorder();
                    break;
                case KeyEvent.KEYCODE_BACK:
                    cancelReorder(false);
                    click.move();
                    break;
                default:
                    break;
            }
            return;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activate();
                break;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (activeScreen == SCREEN_SETTINGS && zone == Z_CARDS && isCurrentSettingsColumnSearchable()
                        && settingsSearchQuery != null && !settingsSearchQuery.isEmpty()) {
                    settingsSearchBarFocused = true;
                    updateSettingsSearchQuery(
                            settingsSearchQuery.substring(0, settingsSearchQuery.length() - 1), false);
                    click.move();
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                hold();
                break;
            case KeyEvent.KEYCODE_BACK:
                back();
                break;
            default:
                break;
        }
    }

    @Override
    public void onRemoteScroll(int dx, int dy) {
        int keyCode = HomeInputRouter.resolveRemoteScrollKeyCode(dx, dy);
        if (keyCode != 0) {
            onRemoteKey(keyCode);
        }
    }

    private final Object hostFocusedInputLock = new Object();
    private long hostFocusedInputRev = 1L;
    private String lastHostFocusedInputSig = "";

    @Override public int getActiveScreen() { return activeScreen; }
    @Override public int getSubTab() { return subTab; }
    @Override public boolean isSideShelfOpen() { return sideShelfOpen; }
    @Override public boolean isDarkMode() { return nightTarget; }
    @Override public String getSearchQuery() { return searchQuery != null ? searchQuery : ""; }

    @Override
    public TvInputFieldSync.InputSnapshot getFocusedInput() {
        if (lgModalOpen && lgModalIsInput) {
            LiquidGlassInputState inputState = lgInputState;
            boolean secret = inputState != null && inputState.isSecretMode();
            String raw = inputState != null ? inputState.getText() : "";
            String title = lgModalTitle != null ? lgModalTitle : "";
            String safeText = TvInputFieldSync.extractSafeText(raw, title, false, secret);
            long rev;
            synchronized (hostFocusedInputLock) {
                String sig = "modal|" + secret + "|" + title + "|" + safeText;
                if (!sig.equals(lastHostFocusedInputSig)) {
                    lastHostFocusedInputSig = sig;
                    hostFocusedInputRev++;
                }
                rev = hostFocusedInputRev;
            }
            return new TvInputFieldSync.InputSnapshot(
                    true,
                    safeText,
                    title,
                    "io.soartv",
                    "SoarTV",
                    secret,
                    rev,
                    System.currentTimeMillis()
            );
        }
        if (activeScreen == SCREEN_SETTINGS && isCurrentSettingsColumnSearchable()) {
            SettingsTree.Node leftParent = currentSettingsLeftParent();
            String q = settingsSearchQuery != null ? settingsSearchQuery : "";
            String hint = SettingsScreenRenderer.searchPlaceholderForColumn(leftParent);
            String parentId = leftParent != null ? leftParent.id : "";
            long rev;
            synchronized (hostFocusedInputLock) {
                String sig = "settings_search|" + parentId + "|" + q;
                if (!sig.equals(lastHostFocusedInputSig)) {
                    lastHostFocusedInputSig = sig;
                    hostFocusedInputRev++;
                }
                rev = hostFocusedInputRev;
            }
            return new TvInputFieldSync.InputSnapshot(
                    true,
                    q,
                    hint,
                    "io.soartv",
                    "SoarTV Settings",
                    false,
                    rev,
                    System.currentTimeMillis()
            );
        }
        if (activeScreen == SCREEN_SEARCH) {
            String q = searchQuery != null ? searchQuery : "";
            long rev;
            synchronized (hostFocusedInputLock) {
                String sig = "search|" + q;
                if (!sig.equals(lastHostFocusedInputSig)) {
                    lastHostFocusedInputSig = sig;
                    hostFocusedInputRev++;
                }
                rev = hostFocusedInputRev;
            }
            return new TvInputFieldSync.InputSnapshot(
                    true,
                    q,
                    "Search shows, movies, apps",
                    "io.soartv",
                    "SoarTV Search",
                    false,
                    rev,
                    System.currentTimeMillis()
            );
        }
        return null;
    }

    @Override
    public TvRemoteBridge.ActiveModal getActiveModal() {
        // 1. Trakt OAuth QR dialog -> one-tap open browser & auto-copy activation code on phone
        if (traktAuthDialog != null && traktAuthDialog.isShowing()) {
            String code = Trakt.code();
            if (code != null && !code.trim().isEmpty()) {
                String activateUrl = Trakt.normalizeActivationUrl(Trakt.url());
                return new TvRemoteBridge.ActiveModal(
                        true,
                        "trakt_qr:" + code.trim(),
                        TvRemoteBridge.KIND_QR_LINK,
                        "Connect Trakt Account",
                        "Open Trakt on your phone and enter the code below — no camera needed.",
                        activateUrl,
                        code.trim(),
                        "Open Trakt & Copy Code",
                        java.util.Collections.singletonList(
                                new TvRemoteBridge.ModalAction("dismiss", "Cancel on TV", "secondary")),
                        false
                );
            }
        }
        // 2. Stremio QR Link dialog -> one-tap open link.stremio.com/XXXX directly on phone
        if (stremioAuthDialog != null && stremioAuthDialog.isShowing()) {
            String code = StremioAccountClient.qrCode();
            if (code != null && !code.trim().isEmpty()) {
                String link = StremioAccountClient.qrLink();
                String targetUrl = (link != null && !link.trim().isEmpty())
                        ? link.trim()
                        : ("https://link.stremio.com/" + code.trim());
                return new TvRemoteBridge.ActiveModal(
                        true,
                        "stremio_qr:" + code.trim(),
                        TvRemoteBridge.KIND_QR_LINK,
                        "Connect Stremio Account",
                        "Approve in your phone browser to sync your Stremio Library — no camera needed.",
                        targetUrl,
                        code.trim(),
                        "Approve on Stremio",
                        java.util.Arrays.asList(
                                new TvRemoteBridge.ModalAction("stremio_manual", "Use Email & Password", "secondary"),
                                new TvRemoteBridge.ModalAction("dismiss", "Cancel on TV", "secondary")),
                        false
                );
            }
        }
        // 3. New Remote Pairing approval prompt on TV (ADMIN-only remote approval)
        if (activePendingPairing != null) {
            String codeFmt = HomeInputRouter.formatSixDigitCode(activePendingPairing.sixDigitCode);
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "pair_prompt:" + activePendingPairing.requestId,
                    TvRemoteBridge.KIND_ACTIONS,
                    "New Remote Detected",
                    activePendingPairing.deviceName + " wants to connect to this TV.",
                    "",
                    codeFmt,
                    "",
                    java.util.Arrays.asList(
                            new TvRemoteBridge.ModalAction("pair_guest", "Allow Guest (30d)", "primary"),
                            new TvRemoteBridge.ModalAction("pair_household", "Allow Household", "secondary"),
                            new TvRemoteBridge.ModalAction("pair_admin", "Make Admin", "blue"),
                            new TvRemoteBridge.ModalAction("dismiss", "Dismiss", "danger")),
                    true
            );
        }
        // 4. Liquid Glass Choice / Confirmation modal on TV (e.g. Restart, Confirmations, Controller Actions)
        if (lgModalOpen && !lgModalIsInput && lgChoiceLabels != null && lgChoiceLabels.length > 0) {
            List<TvRemoteBridge.ModalAction> actions = new ArrayList<>();
            for (int i = 0; i < lgChoiceLabels.length; i++) {
                String lbl = lgChoiceLabels[i];
                if (lbl == null || lbl.trim().isEmpty()) continue;
                actions.add(new TvRemoteBridge.ModalAction("lg_choice:" + i, lbl.trim(), i == 0 ? "primary" : "secondary"));
            }
            actions.add(new TvRemoteBridge.ModalAction("dismiss", "Cancel", "secondary"));
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "lg_choice:" + lgModalTitle + ":" + lgChoiceLabels.length,
                    TvRemoteBridge.KIND_ACTIONS,
                    lgModalTitle != null && !lgModalTitle.isEmpty() ? lgModalTitle : "TV Prompt",
                    lgModalSubtitle != null ? lgModalSubtitle : "",
                    "",
                    "",
                    "",
                    actions,
                    false
            );
        }
        // 5b. Screensaver Slide Duration interactive modal
        if (slideDurationModalOpen && slideDurationModalState != null) {
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "slide_duration:" + slideDurationModalState.getIntervalMs(),
                    TvRemoteBridge.KIND_ACTIONS,
                    "Slide Duration",
                    slideDurationModalState.getLabel() + " \u00B7 " + slideDurationModalState.getCaption(),
                    "",
                    "",
                    "",
                    java.util.Arrays.asList(
                            new TvRemoteBridge.ModalAction("slide_dur_up", "Next (" + slideDurationModalState.getNextLabel() + ")", "secondary"),
                            new TvRemoteBridge.ModalAction("slide_dur_down", "Previous (" + slideDurationModalState.getPrevLabel() + ")", "secondary"),
                            new TvRemoteBridge.ModalAction("dismiss", "Done", "primary")),
                    false
            );
        }
        // 5. Sleep Timer interactive modal
        if (sleepModalOpen && sleepModalState != null) {
            int mins = sleepModalState.getMinutes();
            boolean on = sleepModalState.isEnabled();
            String sub = on
                    ? ("Sleeps in " + SleepTimer.formatDurationLabel(mins))
                    : "Sleep timer currently off";
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "sleep_timer:" + mins + ":" + on,
                    TvRemoteBridge.KIND_ACTIONS,
                    "Sleep Timer",
                    sub,
                    "",
                    "",
                    "",
                    java.util.Arrays.asList(
                            new TvRemoteBridge.ModalAction("sleep_save", "Save Timer", "primary"),
                            new TvRemoteBridge.ModalAction("sleep_add5", "+5 Minutes", "secondary"),
                            new TvRemoteBridge.ModalAction("sleep_toggle", on ? "Turn Off" : "Turn On", "secondary"),
                            new TvRemoteBridge.ModalAction("dismiss", "Cancel", "secondary")),
                    false
            );
        }
        // 6. Hold-OK Context Menu on TV
        if (menuOpen && !smartCardOpen && menuOptionCount > 0) {
            List<TvRemoteBridge.ModalAction> actions = new ArrayList<>();
            for (int i = 0; i < menuOptionCount && i < menuOptions.length; i++) {
                String lbl = menuOptions[i];
                if (lbl == null || lbl.trim().isEmpty()) continue;
                actions.add(new TvRemoteBridge.ModalAction("menu_pick:" + i, lbl.trim(), i == 0 ? "primary" : "secondary"));
            }
            actions.add(new TvRemoteBridge.ModalAction("dismiss", "Cancel", "secondary"));
            String ttl = (menuTitle != null && !menuTitle.trim().isEmpty()) ? menuTitle.trim() : "Quick Actions";
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "context_menu:" + ttl + ":" + menuOptionCount,
                    TvRemoteBridge.KIND_ACTIONS,
                    ttl,
                    menuSub != null ? menuSub : "",
                    "",
                    "",
                    "",
                    actions,
                    false
            );
        }
        // 7. Game Launch progress modal
        if (gameLaunchModalOpen) {
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "game_launch:" + gameLaunchTitle,
                    TvRemoteBridge.KIND_ACTIONS,
                    gameLaunchTitle != null && !gameLaunchTitle.isEmpty() ? ("Launching " + gameLaunchTitle) : "Launching Game",
                    gameLaunchSubtitle != null ? gameLaunchSubtitle : "Preparing PC game stream...",
                    "",
                    "",
                    "",
                    java.util.Collections.singletonList(
                            new TvRemoteBridge.ModalAction("dismiss", "Cancel Launch", "danger")),
                    false
            );
        }
        // 8. Sync Waiting modal
        if (syncModalOpen) {
            return new TvRemoteBridge.ActiveModal(
                    true,
                    "sync_modal:" + syncModalTitle,
                    TvRemoteBridge.KIND_ACTIONS,
                    syncModalTitle != null && !syncModalTitle.isEmpty() ? syncModalTitle : "Syncing",
                    syncModalStep != null ? syncModalStep : "",
                    "",
                    "",
                    "",
                    java.util.Collections.singletonList(
                            new TvRemoteBridge.ModalAction("dismiss", "Continue in Background", "secondary")),
                    false
            );
        }
        return TvRemoteBridge.ActiveModal.INACTIVE;
    }

    @Override
    public void onRemoteModalAction(String actionId) {
        if (actionId == null || actionId.trim().isEmpty()) return;
        Recs.notifyUserInteraction();
        wake();
        String id = actionId.trim();
        if ("dismiss".equals(id)) {
            if (traktAuthDialog != null && traktAuthDialog.isShowing()) {
                Trakt.cancelSignIn();
                try { traktAuthDialog.dismiss(); } catch (RuntimeException ignored) {}
                traktAuthDialog = null;
                return;
            }
            if (stremioAuthDialog != null && stremioAuthDialog.isShowing()) {
                StremioAccountClient.cancelQrSignIn();
                try { stremioAuthDialog.dismiss(); } catch (RuntimeException ignored) {}
                stremioAuthDialog = null;
                return;
            }
            if (activePendingPairing != null) {
                executePairingModalAction(3);
                return;
            }
            if (lgModalOpen) {
                closeLiquidGlassModal(true);
                return;
            }
            if (slideDurationModalOpen) {
                closeSlideDurationModal();
                return;
            }
            if (sleepModalOpen) {
                closeSleepTimerModal();
                return;
            }
            if (menuOpen) {
                closeMenu(false);
                click.move();
                return;
            }
            if (gameLaunchModalOpen) {
                cancelGameLaunchModalByUser();
                return;
            }
            if (syncModalOpen) {
                syncModalOpen = false;
                toastLayer.invalidate();
                click.move();
                return;
            }
            return;
        }
        if ("stremio_manual".equals(id)) {
            StremioAccountClient.cancelQrSignIn();
            if (stremioAuthDialog != null) {
                try { stremioAuthDialog.dismiss(); } catch (RuntimeException ignored) {}
                stremioAuthDialog = null;
            }
            openStremioEmailPasswordDialog();
            return;
        }
        if ("pair_guest".equals(id) && activePendingPairing != null) {
            executePairingModalAction(0);
            return;
        }
        if ("pair_household".equals(id) && activePendingPairing != null) {
            executePairingModalAction(1);
            return;
        }
        if ("pair_admin".equals(id) && activePendingPairing != null) {
            executePairingModalAction(2);
            return;
        }
        if (id.startsWith("lg_choice:") && lgModalOpen && !lgModalIsInput && lgChoiceLabels != null) {
            try {
                int idx = Integer.parseInt(id.substring("lg_choice:".length()));
                if (idx >= 0 && idx < lgChoiceLabels.length) {
                    lgChoiceFocus = idx;
                    ChoiceSelectedListener cb = lgChoiceCallback;
                    closeLiquidGlassModal(false);
                    click.open();
                    if (cb != null) cb.onChoiceSelected(idx);
                }
            } catch (NumberFormatException ignored) {}
            return;
        }
        if (id.startsWith("menu_pick:") && menuOpen && !smartCardOpen) {
            try {
                int idx = Integer.parseInt(id.substring("menu_pick:".length()));
                if (idx >= 0 && idx < menuOptionCount) {
                    menuIndex = idx;
                    pickMenu();
                }
            } catch (NumberFormatException ignored) {}
            return;
        }
        if (slideDurationModalOpen && slideDurationModalState != null) {
            if ("slide_dur_up".equals(id)) {
                slideDurationModalState.stepUp();
                persistSlideDurationModalValue();
                click.move();
                toastLayer.invalidate();
            } else if ("slide_dur_down".equals(id)) {
                slideDurationModalState.stepDown();
                persistSlideDurationModalValue();
                click.move();
                toastLayer.invalidate();
            }
            return;
        }
        if (sleepModalOpen && sleepModalState != null) {
            if ("sleep_save".equals(id)) {
                saveSleepTimerModal();
            } else if ("sleep_add5".equals(id)) {
                sleepModalState.addFiveMinutes();
                click.move();
                toastLayer.invalidate();
            } else if ("sleep_toggle".equals(id)) {
                sleepModalState.toggleEnabled();
                click.move();
                toastLayer.invalidate();
            }
        }
    }

    @Override
    public void onRemoteText(String text, boolean submitSearch) {
        onRemoteText(text, submitSearch, false);
    }

    @Override
    public void onRemoteText(String text, boolean submitSearch, boolean replaceInput) {
        String safe = text != null ? text : "";
        if (lgModalOpen && lgModalIsInput) {
            if (!replaceInput && safe.isEmpty()) return;
            lgInputState.setText(safe);
            syncHiddenEditFromInputState();
            toastLayer.invalidate();
            wake();
            if (submitSearch) {
                confirmLiquidGlassInputModal();
            }
            return;
        }
        if (activeScreen == SCREEN_SETTINGS && isCurrentSettingsColumnSearchable()) {
            updateSettingsSearchQuery(safe, submitSearch);
            return;
        }
        if (activeScreen == SCREEN_SEARCH && replaceInput) {
            if (submitSearch) {
                zone = Z_CARDS;
                performUnifiedSearch(safe.trim());
            } else {
                searchQuery = safe;
                rebuildScreenRows();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }
            return;
        }
        if (safe.isEmpty()) return;
        if (submitSearch) {
            selectScreen(SCREEN_SEARCH);
            zone = Z_CARDS;
            performUnifiedSearch(safe);
        } else {
            showToast(Ui.tr("Copied to TV: ") + safe);
        }
    }

    @Override
    public void onRemoteVoiceCommand(String transcript) {
        if (transcript == null || transcript.isEmpty()) return;
        String lower = transcript.toLowerCase(java.util.Locale.ROOT).trim();
        if (lower.contains("mute")) {
            toggleMute();
        } else if (lower.contains("movie time")) {
            SmartHomeManager.get(getContext()).executeRoutine(activity, "movie_time", (ok, msg) -> {
                showToast(msg);
                homeChanged();
            });
        } else if (lower.contains("good night") || lower.contains("goodnight")) {
            SmartHomeManager.get(getContext()).executeRoutine(activity, "good_night", (ok, msg) -> {
                showToast(msg);
                homeChanged();
            });
        } else if (lower.contains("gaming") || lower.contains("steam") || lower.contains("pc")) {
            selectScreen(SCREEN_GAMING);
            zone = Z_CARDS;
            retarget(false);
            showToast(GamingZone.launch(activity, this));
        } else if (lower.contains("dark mode") || lower.contains("light mode") || lower.contains("theme")) {
            toggleThemeMode();
        } else if (lower.contains("boiler") || lower.contains("water")) {
            SmartHomeManager.get(getContext()).toggleDevice("switcher_boiler",
                    (ok, msg) -> showToast(msg));
        } else if (lower.contains("light") || lower.contains("lamp")) {
            SmartHomeManager.get(getContext()).toggleDevice("wiz_house_lights",
                    (ok, msg) -> showToast(msg));
        } else if (lower.contains("living room ac") || lower.contains("sensibo")) {
            SmartHomeManager.get(getContext()).toggleDevice("sensibo_living_ac",
                    (ok, msg) -> showToast(msg));
        } else if (lower.contains("bedroom ac") || lower.contains("tuya")) {
            SmartHomeManager.get(getContext()).toggleDevice("tuya_bedroom_ac",
                    (ok, msg) -> showToast(msg));
        } else {
            selectScreen(SCREEN_SEARCH);
            zone = Z_CARDS;
            performUnifiedSearch(transcript);
        }
    }

    @Override
    public void onRemoteCastUrl(String url, String title, String sender) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(view);
            String label = (title != null && !title.isEmpty()) ? title : url;
            if (sender != null && !sender.isEmpty()) {
                showToast(Ui.tr("Casting from ") + sender + ": " + label);
            } else {
                showToast(Ui.tr("Casting: ") + label);
            }
        } catch (RuntimeException e) {
            showToast(Ui.tr("Couldn't open cast URL: ") + url);
        }
    }

    @Override
    public void onRemoteAction(String action) {
        if (action == null) return;
        switch (action) {
            case "home":
                reset(true);
                break;
            case "apps":
                selectScreen(SCREEN_APPS);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "gaming":
                selectScreen(SCREEN_GAMING);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "reels":
                selectScreen(SCREEN_REELS);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "smarthome":
                selectScreen(SCREEN_SMARTHOME);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "settings":
                selectScreen(SCREEN_SETTINGS);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "sideshelf":
                if (sideShelfOpen) {
                    closeSideShelf();
                } else {
                    if (activeScreen != SCREEN_HOME) {
                        selectScreen(SCREEN_HOME);
                    }
                    openSideShelf();
                }
                break;
            case "subtab_tv":
                if (activeScreen != SCREEN_HOME) {
                    selectScreen(SCREEN_HOME);
                }
                selectSubTab(0);
                showToast(Ui.tr("TV Shows"));
                break;
            case "subtab_movie":
                if (activeScreen != SCREEN_HOME) {
                    selectScreen(SCREEN_HOME);
                }
                selectSubTab(1);
                showToast(Ui.tr("Movies"));
                break;
            case "trakt":
                openTraktAuthDialog();
                break;
            case "theme":
                toggleThemeMode();
                break;
            case "search":
                selectScreen(SCREEN_SEARCH);
                zone = Z_CARDS;
                retarget(false);
                break;
            case "users": {
                UserProfileManager.Profile nextProfile = UserProfileManager.get(getContext()).cycleNextProfile();
                recs.refreshIfStale();
                reload();
                updateCards();
                showToast(Ui.tr("Profile: ") + nextProfile.displayName);
                break;
            }
            case "user_switched": {
                recs.refreshIfStale();
                reload();
                updateCards();
                UserProfileManager.Profile active = UserProfileManager.get(getContext()).getActiveProfile();
                showToast(Ui.tr("Profile: ") + active.displayName);
                break;
            }
            case "refresh_smarthome":
                homeChanged();
                if (activeScreen == SCREEN_SMARTHOME) {
                    rebuildScreenRows();
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                }
                break;
            case "gaming_refresh":
                refreshGamingScreenData(true);
                break;
            case "gaming_wake":
                showToast(Ui.tr("Waking Gaming PC via Wake-on-LAN…"));
                refreshGamingScreenData(false);
                break;
            default:
                if (action.startsWith("smarthome_routine:")) {
                    String routineId = action.substring("smarthome_routine:".length()).trim();
                    SmartHomeManager.get(getContext()).executeRoutine(activity, routineId, (ok, msg) -> {
                        showToast(msg);
                        homeChanged();
                        rebuildScreenRows();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    });
                } else if (action.startsWith("gaming_launch:")) {
                    String[] parts = action.split(":", 3);
                    String gameId = parts.length > 1 ? parts[1] : "steam_bigpicture";
                    String pin = parts.length > 2 ? parts[2] : "";
                    GamingZone.GameItem chosen = null;
                    if (gamingStatus != null && gamingStatus.games != null) {
                        for (GamingZone.GameItem g : gamingStatus.games) {
                            if (g.id.equals(gameId)) {
                                chosen = g;
                                break;
                            }
                        }
                    }
                    if (chosen == null) {
                        for (GamingZone.GameItem g : GamingZone.defaultGameCatalog()) {
                            if (g.id.equals(gameId)) {
                                chosen = g;
                                break;
                            }
                        }
                    }
                    if (chosen == null) {
                        chosen = new GamingZone.GameItem(gameId, gameId, "steam", "", false);
                    }
                    GamingZone.recordGamePlayed(getContext(), chosen.id);
                    GamingZone.launchGameOnTvAndLaptop(activity, this, chosen, pin);
                }
                break;
        }
    }

    /**
     * Starting state (Home pressed while the home screen is showing):
     * Home screen active (SCREEN_HOME = 1), first item on Home screen focused (not in top-bar),
     * side-shelf, drawer and sheet closed.
     */
    void reset(boolean animated) {
        wake();
        closeLiquidGlassModal(false);
        if (slideDurationModalOpen) {
            closeSlideDurationModal();
        }
        clearSettingsSearchState(true);
        if (gameLaunchModalOpen) {
            GamingZone.cancelActiveGameLaunch();
            closeGameLaunchModal();
        }
        cancelReorder(!animated);
        closeMenu(!animated);
        if (recents != null) recents.close();   // Home closes Recent Apps too (twice opens it: HomeActivity)
        showHiddenGamesInline = false;
        showHiddenAppsInline = false;
        rowScrollMemory.clear();
        backStack.clear();
        activeScreen = SCREEN_HOME;
        topIndex = SCREEN_HOME;
        zone = Z_CARDS;
        homeRow = 0;
        homeCol = 0;
        shelfIndex = 0;
        cardIndex = 0;
        sideShelfOpen = false;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sideShelfSpring.target = 0;
            homeScrollY.target = 0;
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sideShelfSpring.snap(0);
            clearSideBlur();
            homeScrollY.snap(0);
            sheet.snap(0);
            drawer.snap(0);
        }
        rebuildAllAppsList();
        rebuildScreenRows();
        retarget(!animated);
        motionDirty = true;
    }

    private void clearSideBlur() {
        sideShelfBlur = null;
        if (Build.VERSION.SDK_INT >= 31) {
            sky.setRenderEffect(null);
            heroLayer.setRenderEffect(null);
            status.setRenderEffect(null);
        }
    }

    private boolean isRtl() {
        String lang = Ui.language;
        return "he".equals(lang) || "iw".equals(lang) || "ar".equals(lang) || "fa".equals(lang)
                || getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    private void openSideShelf() {
        if (shelf.isEmpty() || activeScreen != SCREEN_HOME) return;
        Recs.notifyUserInteraction();
        sideShelfOpen = true;
        sideShelfSpring.target = 1f;
        zone = Z_SHELF;
        shelfIndex = Math.max(0, Math.min(shelfIndex, shelf.size() - 1));
        // Reuse the already-blurred 192x108 frost bitmap (or unified ambient mesh) in 0ms instead of stalling
        // RenderThread + CPU on a synchronous Window PixelCopy!
        Bitmap instantFrost = null;
        if (bgTo != null && !bgTo.isRecycled()) {
            instantFrost = (bgTo.frostBitmap != null && !bgTo.frostBitmap.isRecycled())
                    ? bgTo.frostBitmap
                    : (night > 0.5f ? bgTo.nightImage : bgTo.lightImage);
        }
        if (instantFrost == null || instantFrost.isRecycled()) {
            instantFrost = unifiedBgBitmap;
        }
        sideShelfBlur = instantFrost;
        retarget(false);
        platter.invalidate();
        tiles.invalidate();
        heroDirty = true;
        wake();
    }

    private void closeSideShelf() {
        if (!sideShelfOpen) return;
        sideShelfOpen = false;
        sideShelfSpring.target = 0f;
        zone = Z_CARDS;
        retarget(false);
        platter.invalidate();
        tiles.invalidate();
        heroDirty = true;
        wake();
    }

    private void syncDisplayedBackdrops() {
        Backdrop.setDisplayedBackdrops(bgFrom, bgTo);
    }

    private static final long TOP_BAR_SWITCH_DEBOUNCE_MS = 150L;
    private int pendingTopBarScreen = -1;
    private boolean preserveTopBarSpringsOnRetarget = false;
    private final Runnable commitTopBarScreenRunnable = () -> {
        int target = pendingTopBarScreen;
        pendingTopBarScreen = -1;
        if (target >= 0 && target != activeScreen) {
            selectScreenFromTopBar(target);
        }
    };

    private void flushPendingTopBarScreenSwitch() {
        handler.removeCallbacks(commitTopBarScreenRunnable);
        int target = pendingTopBarScreen;
        pendingTopBarScreen = -1;
        if (target >= 0 && target != activeScreen) {
            selectScreenFromTopBar(target);
        }
    }

    private void selectScreenFromTopBar(int screenIdx) {
        preserveTopBarSpringsOnRetarget = (zone == Z_TOP);
        try {
            selectScreenInternal(screenIdx, true);
            if (!usesHeroBackdropMode(activeScreen)) {
                retarget(false);
            }
        } finally {
            preserveTopBarSpringsOnRetarget = false;
        }
    }

    private void selectScreen(int screenIdx) {
        handler.removeCallbacks(commitTopBarScreenRunnable);
        pendingTopBarScreen = -1;
        selectScreenInternal(screenIdx, true);
    }

    private void selectScreenInternal(int screenIdx, boolean recordHistory) {
        int clamped = FeatureTabs.sanitizeActiveScreen(
                Math.max(0, Math.min(TOP_BAR_COUNT - 1, screenIdx)),
                FeatureTabs.currentMask(getContext()));
        int prevScreen = activeScreen;
        if (recordHistory && clamped != prevScreen) {
            backStack.pushScreenState(
                    new HomeInputRouter.ScreenCursorState(prevScreen, subTab, homeRow, homeCol, gridIndex),
                    clamped);
        }
        if (activeScreen == SCREEN_SETTINGS && clamped != SCREEN_SETTINGS) {
            clearSettingsSearchState(true);
            settingsPathIds.clear();
            settingsStepDepthStack.clear();
            java.util.Arrays.fill(settingsDepthIndices, 0);
            settingsFocusRight = false;
            settingsRightIndex = 0;
        }
        if (activeScreen == SCREEN_GAMING && clamped != SCREEN_GAMING && showHiddenGamesInline) {
            showHiddenGamesInline = false;
        }
        if (activeScreen == SCREEN_APPS && clamped != SCREEN_APPS && showHiddenAppsInline) {
            showHiddenAppsInline = false;
            rebuildAllAppsList();
        }
        if (sideShelfOpen) {
            sideShelfOpen = false;
            sideShelfSpring.target = 0f;
        }
        activeScreen = clamped;
        topIndex = clamped;
        homeRow = 0;
        homeCol = 0;
        gridIndex = 0;
        scroll.snap(0);
        homeScrollY.snap(0);
        if (activeScreen == SCREEN_GAMING) {
            refreshGamingScreenData(false);
        } else if (activeScreen == SCREEN_REELS) {
            refreshReelsData();
        } else if (activeScreen == SCREEN_SMARTHOME) {
            homeChanged();
        } else if (activeScreen == SCREEN_APPS) {
            rebuildAllAppsList();
        }
        rebuildScreenRows();
        if (!usesHeroBackdropMode(activeScreen)) {
            handler.removeCallbacks(bgFollow);
            bgFadeAt = 0;
            bgFrom = null;
            bgTo = null;
            syncDisplayedBackdrops();
            neutralGlass();
        } else {
            if (activeScreen == SCREEN_GAMING) {
                prefetchVisibleGamingBackdrops();
                if (prevScreen != SCREEN_GAMING) {
                    GamingZone.GameItem fg = focusedGameOnGaming();
                    Backdrop ready = GamingZone.peekGameBackdrop(fg);
                    bgFadeAt = 0;
                    bgFrom = null;
                    bgTo = ready;
                    syncDisplayedBackdrops();
                    if (ready != null && glassOf(ready) != null) {
                        scene.setFrost(frostOf(ready));
                        scene.setGlassImage(glassOf(ready));
                    } else {
                        neutralGlass();
                    }
                }
            } else if (activeScreen == SCREEN_HOME && prevScreen != SCREEN_HOME) {
                Backdrop homeReady = peekFocusedHomeBackdrop();
                bgFadeAt = 0;
                bgFrom = null;
                bgTo = homeReady;
                syncDisplayedBackdrops();
                if (homeReady != null && glassOf(homeReady) != null) {
                    scene.setFrost(frostOf(homeReady));
                    scene.setGlassImage(glassOf(homeReady));
                } else {
                    neutralGlass();
                }
            }
            retarget(true);
        }
        heroDirty = statusDirty = tilesDirty = true;
        sky.invalidate();
        platter.invalidate();
        tiles.invalidate();
        status.invalidate();
        heroLayer.invalidate();
        wake();
    }

    private List<GamingZone.GameItem> collectPrioritizedGamingItems() {
        List<GamingZone.GameItem> ordered = new ArrayList<>();
        GamingZone.GameItem fg = focusedGameOnGaming();
        if (fg != null) ordered.add(fg);
        try {
            ordered.addAll(GamingZone.getContinuePlayingGames(getContext()));
        } catch (Throwable ignored) {
        }
        if (gamingStatus != null && gamingStatus.games != null && !gamingStatus.games.isEmpty()) {
            ordered.addAll(gamingStatus.games);
        } else {
            ordered.addAll(GamingZone.defaultGameCatalog());
        }
        return ordered;
    }

    private void prefetchVisibleGamingBackdrops() {
        List<GamingZone.GameItem> prioritized = collectPrioritizedGamingItems();
        List<GamingZone.GameItem> pinnedLeads = new ArrayList<>(3);
        try {
            List<GamingZone.GameItem> cont = GamingZone.getContinuePlayingGames(getContext());
            if (cont != null && !cont.isEmpty()) pinnedLeads.add(cont.get(0));
        } catch (Throwable ignored) {
        }
        List<GamingZone.GameItem> lib = (gamingStatus != null && gamingStatus.games != null && !gamingStatus.games.isEmpty())
                ? gamingStatus.games : GamingZone.defaultGameCatalog();
        if (lib != null && !lib.isEmpty()) pinnedLeads.add(lib.get(0));
        GamingZone.setPinnedGameHeroes(pinnedLeads);
        GamingZone.prefetchGameBackdropsAsync(getContext(), prioritized, gameBackdropLoaded);
    }

    private void refreshGamingScreenData(boolean showFeedback) {
        if (gamingStatus == null) {
            gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
        }
        prefetchVisibleGamingBackdrops();
        if (showFeedback) {
            showToast(Ui.tr("Syncing game library with Laptop Gaming Agent…"));
        }
        GamingZone.refreshLaptopStatusAsync(getContext(), () -> {
            gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
            prefetchVisibleGamingBackdrops();
            if (activeScreen == SCREEN_GAMING) {
                rebuildScreenRows();
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }
            if (showFeedback) {
                showToast(gamingStatus.awake ? Ui.tr("Game library synced with PC") : Ui.tr("Showing locally cached game library (PC in standby)"));
            }
        });
    }

    private void refreshReelsData() {
        LOADER.execute(() -> {
            List<SupabaseClient.SharedReel> reels = SupabaseClient.get(getContext()).fetchSharedReelsSync();
            handler.post(() -> {
                if (reels != null) {
                    sharedReelsList = reels;
                    if (activeScreen == SCREEN_REELS) {
                        rebuildScreenRows();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    }
                }
            });
        });
    }

    private void promptAiSearchDialog() {
        try {
            android.widget.EditText field = new android.widget.EditText(activity);
            field.setSingleLine(true);
            field.setText(searchQuery);
            field.setSelection(field.getText().length());
            field.setHint(Ui.tr("Search shows, movies, YouTube, apps, or settings…"));
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
                    activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Search SoarTV"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> performUnifiedSearch(field.getText().toString()))
                    .setNeutralButton(Ui.tr("Voice"), (d, which) -> startVoiceSearch())
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                performUnifiedSearch(field.getText().toString());
                return true;
            });
            dialog.show();
        } catch (RuntimeException e) {
            startVoiceSearch();
        }
    }

    private void startVoiceSearch() {
        try {
            Intent speech = new Intent("android.speech.action.RECOGNIZE_SPEECH");
            speech.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
            speech.putExtra("android.speech.extra.PROMPT", Ui.tr("Speak a show, movie, YouTube video, app, or setting…"));
            if (speech.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(speech);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            Intent voiceAssist = new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (voiceAssist.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(voiceAssist);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        promptAiSearchDialog();
    }

    private void performUnifiedSearch(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        searchQuery = q;
        searchMediaResults.clear();
        searchYoutubeResults.clear();
        searchAppAndSettingResults.clear();
        if (q.isEmpty()) {
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            return;
        }
        showToast(Ui.tr("Searching: ") + q);
        String qLower = q.toLowerCase(Locale.ROOT);

        // 1. Immediate local matches from installed apps & settings
        if (allAppsList.isEmpty()) rebuildAllAppsList();
        for (HomeApps.Item app : allAppsList) {
            if (app == null || app.label == null) continue;
            if (app.label.toLowerCase(Locale.ROOT).contains(qLower)
                    || (app.pkg != null && app.pkg.toLowerCase(Locale.ROOT).contains(qLower))) {
                searchAppAndSettingResults.add(new ActionCard(
                        "app_open:" + app.pkg, app.label,
                        Ui.tr("Installed App"), "apps",
                        0xFF2563EB, 0xFF1E3A8A, app));
            }
        }
        // Dynamic full-tree Settings Search (jumps straight to matching Settings paths)
        SettingsTree.Node searchTree = settingsRoot != null
                ? settingsRoot
                : SettingsTree.buildRoot(SettingsTree.captureState(getContext(), data));
        List<SettingsTree.SearchMatch> settingMatches = SettingsTree.searchNodes(searchTree, q);
        int maxSettingHits = Math.min(6, settingMatches.size());
        for (int i = 0; i < maxSettingHits; i++) {
            SettingsTree.SearchMatch sm = settingMatches.get(i);
            StringBuilder encodedPath = new StringBuilder();
            for (int pi = 0; pi < sm.pathIds.size(); pi++) {
                if (pi > 0) encodedPath.append(">");
                encodedPath.append(sm.pathIds.get(pi));
            }
            String subLine = sm.breadcrumb + (sm.node.value.isEmpty() ? "" : (" \u00B7 " + Ui.tr(sm.node.value)));
            searchAppAndSettingResults.add(new ActionCard(
                    "setting_jump:" + encodedPath,
                    Ui.tr(sm.node.title),
                    subLine,
                    sm.node.icon,
                    0xFF475569, 0xFF1E293B, null));
        }
        String[][] quickSettingShortcuts = {
            {"settings_theme", "Toggle Dark / Light Mode", "Switch appearance theme immediately", "dark light theme mode appearance"},
            {"settings_profile", "Switch Household Profile", "Cycle active user profile immediately", "profile user account switch"}
        };
        for (String[] sc : quickSettingShortcuts) {
            if (sc[1].toLowerCase(Locale.ROOT).contains(qLower)
                    || sc[2].toLowerCase(Locale.ROOT).contains(qLower)
                    || sc[3].contains(qLower)) {
                searchAppAndSettingResults.add(new ActionCard(
                        sc[0], Ui.tr(sc[1]), Ui.tr(sc[2]), "settings",
                        0xFF334155, 0xFF0F172A, null));
            }
        }

        // 2. YouTube search options
        searchYoutubeResults.add(new ActionCard(
                "youtube_query:" + q,
                Ui.tr("YouTube: ") + q,
                Ui.tr("Search videos in YouTube app"), "tv",
                0xFFDC2626, 0xFF7F1D1D, null));
        searchYoutubeResults.add(new ActionCard(
                "youtube_query:" + q + " official trailer",
                q + " · " + Ui.tr("Official Trailer"),
                Ui.tr("Watch trailer on YouTube"), "tv",
                0xFFB91C1C, 0xFF450A0A, null));
        searchYoutubeResults.add(new ActionCard(
                "youtube_query:" + q + " full episodes clips",
                q + " · " + Ui.tr("Clips & Episodes"),
                Ui.tr("Watch clips on YouTube"), "tv",
                0xFF991B1B, 0xFF450A0A, null));

        // 3. Local TMDB/Recs matches first
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (String sub : new String[] {"tv", "movie"}) {
            for (Recs.Pick p : recs.now(Recs.EXPLORE, sub, "All")) {
                if (p.title != null && p.title.toLowerCase(Locale.ROOT).contains(qLower) && seenKeys.add(Recs.key(p))) {
                    searchMediaResults.add(p);
                }
            }
        }
        rebuildScreenRows();
        retarget(false);
        heroDirty = true;
        heroLayer.invalidate();
        wake();

        // 4. Async Cinemeta (Stremio) + Gemini AI fallback for natural-language/complex queries
        LOADER.execute(() -> {
            List<Recs.Pick> foundMedia = new ArrayList<>(searchMediaResults);
            try {
                for (String type : new String[] {"series", "movie"}) {
                    List<CinemetaClient.MediaItem> cmList = CinemetaClient.get(getContext()).searchSync(q, type);
                    for (int i = 0; i < Math.min(6, cmList.size()); i++) {
                        CinemetaClient.MediaItem item = cmList.get(i);
                        Recs.Pick p = new Recs.Pick();
                        p.id = Math.abs(item.imdbId.hashCode());
                        p.kind = "series".equals(type) ? "tv" : "movie";
                        p.title = item.name;
                        p.link = item.stremioDeepLink;
                        p.pkg = "com.stremio.one";
                        p.app = "stremio";
                        p.meta = (item.releaseInfo != null && !item.releaseInfo.isEmpty()) ? item.releaseInfo : "";
                        p.detailed = true;
                        if (seenKeys.add(p.title.toLowerCase(Locale.ROOT) + ":" + p.kind)) {
                            foundMedia.add(p);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            handler.post(() -> {
                if (!q.equals(searchQuery)) return;
                searchMediaResults = foundMedia;
                rebuildScreenRows();
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });

            // Also ask Gemini AI if configured (helps when simple keyword search is not enough)
            if (GeminiClient.get(getContext()).isConfigured()) {
                GeminiClient.get(getContext()).resolveQueryAsync(q, res -> handler.post(() -> {
                    if (!q.equals(searchQuery) || res == null) return;
                    if (res.resolvedMedia != null) {
                        CinemetaClient.MediaItem item = res.resolvedMedia;
                        Recs.Pick p = new Recs.Pick();
                        p.id = Math.abs(item.imdbId.hashCode());
                        p.kind = "series".equals(item.type) ? "tv" : "movie";
                        p.title = item.name;
                        p.link = item.stremioDeepLink;
                        p.pkg = "com.stremio.one";
                        p.app = "stremio";
                        p.meta = (res.summary != null && !res.summary.isEmpty()) ? res.summary : Ui.tr("Gemini AI Pick");
                        p.detailed = true;
                        searchMediaResults.add(0, p);
                        rebuildScreenRows();
                        retarget(false);
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    }
                }));
            }
        });
    }

    private static String[] topBarLabels() {
        return new String[] {
            Ui.tr("Search"),
            Ui.tr("Home"),
            Ui.tr("Apps"),
            Ui.tr("Gaming"),
            Ui.tr("Reels"),
            Ui.tr("Smart-Home"),
            Ui.tr("Settings")
        };
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        if (reorderTop) cancelReorder(true);
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= TAB_COUNT + lightButtons.size()) {
            topIndex = (TAB_COUNT - 1) + lightButtons.size();
            if (topIndex < TAB_COUNT && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TAB_COUNT) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {
                Ui.tr("Continue Watching"),
                Ui.tr("Watch List"),
                Ui.tr("Recommended"),
                Ui.tr("Trending")
            };
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, height);
        statusWide = true;
        measure(status, width, Math.round(140 * kk));
        measure(platter, width, height);
        measure(tiles, width, height);
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(menuLayer, width, height);
        measure(toastLayer, width, height);   // full screen: renders both top toasts and centered Sync Waiting Screen modal
        measure(recentLayer, width, height);
        if (lgHiddenEdit != null) {
            measure(lgHiddenEdit, 1, 1);
        }
        if (settingsSearchEdit != null) {
            measure(settingsSearchEdit, 1, 1);
        }
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, 0, 0);
        place(platter, 0, 0);
        place(tiles, 0, 0);
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        menuLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, 0, 0);
        recentLayer.layout(0, 0, width, height);
        if (lgHiddenEdit != null) {
            lgHiddenEdit.layout(0, 0, 1, 1);
        }
        if (settingsSearchEdit != null) {
            settingsSearchEdit.layout(0, 0, 1, 1);
        }
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        updateVignetteShaders();
        watchArt.setScale(k);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        // No photo yet (or none at all, in Continue Watching): the glass reads the plain background from the first frame.
        if (bgTo == null && bgFadeAt == 0) neutralGlass();
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        lTitle = Ui.text(Ui.displaySemibold, 68 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 28 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 28 * k, L_INK);
        lSubTab = Ui.text(Ui.medium, 22 * k, L_DIM);
        lSubTabOn = Ui.text(Ui.semibold, 22 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = tintShade = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        menuTitlePaint = Ui.text(Ui.semibold, 34 * k, L_INK);
        menuSubPaint = Ui.text(Ui.medium, 25 * k, L_DIM);
        menuOptionPaint = Ui.text(Ui.medium, 30 * k, L_INK);
        if (menuOpen) layoutMenu();
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);

        headerTitlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 68 * k, L_INK));
        headerTitlePaint.setLetterSpacing(0.004f);

        headerMetaPaint = Ui.text(Ui.semibold, 23 * k, 0xEBFFFFFF);
        headerMetaPaint.setLetterSpacing(0.010f);

        headerGenrePaint = Ui.text(Ui.medium, 21 * k, 0xC0FFFFFF);
        headerGenrePaint.setLetterSpacing(0.012f);

        headerContinuePaint = Ui.text(Ui.semibold, 21 * k, CONTINUE_STATUS_DARK_COLOR);
        headerContinuePaint.setLetterSpacing(0.012f);

        // Never setShadowLayer on overviewPaint: when overviewPaint.setShader(activeGrad) is active during scroll,
        // Skia renders the blurred shadow layer using the shader's white color, creating an outer white glow!
        overviewPaint = new TextPaint(Ui.text(Ui.regular, OVERVIEW_FONT_SIZE * k, OVERVIEW_DARK_COLOR));
        overviewPaint.clearShadowLayer();

        continueEpisodePaint = new TextPaint(Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF));
        continueEpisodePaint.clearShadowLayer();

        sepDotWidth = headerMetaPaint.measureText(SEP_DOT);
        ratingSepWidth = headerMetaPaint.measureText(RATING_SEP);

        Symbols.warm(getContext(), "rt", 20 * k);
        Symbols.warm(getContext(), "mc", 20 * k);

        updateOverviewShaders();
        ensureOverviewLayout(currentHeader);
        ensureOverviewLayout(previousHeader);
        resetOverview(currentHeader.overviewText);

        onPlaying();   // the line fits the new size; a title that came before the first layout bounces and is said now
    }

    private void updateOverviewShaders() {
        if (w <= 0 || h <= 0 || overviewPaint == null) return;
        float boxH = (OVERVIEW_BOX_BOTTOM - OVERVIEW_BOX_TOP) * k;
        float fadeH = OVERVIEW_FADE_H * k;
        if (boxH <= fadeH * 2f) return;

        int color = overviewPaint.getColor();
        int transparent = color & 0x00FFFFFF;
        lastOverviewShaderColor = color;

        overviewFadeBottom = new LinearGradient(
                0, 0, 0, boxH,
                new int[] { color, color, transparent },
                new float[] { 0f, 1f - (fadeH / boxH), 1f },
                Shader.TileMode.CLAMP);

        overviewFadeTop = new LinearGradient(
                0, 0, 0, boxH,
                new int[] { transparent, color, color },
                new float[] { 0f, fadeH / boxH, 1f },
                Shader.TileMode.CLAMP);

        overviewFadeBoth = new LinearGradient(
                0, 0, 0, boxH,
                new int[] { transparent, color, color, transparent },
                new float[] { 0f, fadeH / boxH, 1f - (fadeH / boxH), 1f },
                Shader.TileMode.CLAMP);

        float compactH = (OVERVIEW_BOX_BOTTOM - OVERVIEW_BOX_TOP_WITH_CONTINUE) * k;
        if (compactH > fadeH * 2f) {
            overviewFadeBottomCompact = new LinearGradient(
                    0, 0, 0, compactH,
                    new int[] { color, color, transparent },
                    new float[] { 0f, 1f - (fadeH / compactH), 1f },
                    Shader.TileMode.CLAMP);

            overviewFadeTopCompact = new LinearGradient(
                    0, 0, 0, compactH,
                    new int[] { transparent, color, color },
                    new float[] { 0f, fadeH / compactH, 1f },
                    Shader.TileMode.CLAMP);

            overviewFadeBothCompact = new LinearGradient(
                    0, 0, 0, compactH,
                    new int[] { transparent, color, color, transparent },
                    new float[] { 0f, fadeH / compactH, 1f - (fadeH / compactH), 1f },
                    Shader.TileMode.CLAMP);
        }
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    /**
     * Returns {@code true} only when a continuous active animation frame took between 26ms and 200ms,
     * ignoring 1000ms idle {@code postFrameCallbackDelayed} wakeups so resting screens never false-trip fallback.
     */
    static boolean isRealSlowAnimationFrame(long deltaNanos) {
        return deltaNanos > 26_000_000L && deltaNanos < 200_000_000L;
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long rawDeltaNanos = lastNanos == 0 ? 0L : (frameTimeNanos - lastNanos);
        boolean measuredFrame = lastNanos != 0 && rawDeltaNanos > 0L && rawDeltaNanos < 200_000_000L;
        float dt = !measuredFrame ? 1 / 60f : Math.min(0.05f, rawDeltaNanos / 1e9f);
        lastNanos = frameTimeNanos;

        // Dynamic Frame-Drop Protection:
        // Only switch multi-tap glass refraction to 1-pass fallback if 6 consecutive active animation frames exceed 26ms.
        // Keep 1-pass AgslHero active because 1 shader pass (2.07 MPix) uses 4x less GPU fill-rate than 5 gradient rects.
        if (!sceneForceFallbackApplied && measuredFrame) {
            if (isRealSlowAnimationFrame(rawDeltaNanos)) {
                consecutiveSlowFrames++;
                if (consecutiveSlowFrames >= 6) {
                    sceneForceFallbackApplied = true;
                    scene.setForceFallback(true);
                    AppLog.w("Performance: 6 consecutive active frames > 26ms (" + Math.round(rawDeltaNanos / 1e6f) + "ms) - enabling 1-pass glass fallback");
                    platter.invalidate();
                }
            } else {
                consecutiveSlowFrames = 0;
            }
        }
        long up = SystemClock.uptimeMillis();

        settleWhileSliding();
        boolean tilesMoving = step(tileFocus, dt);
        boolean topReorderFrame = reorderTop && reorderAnim;
        boolean heroReorderFrame = false;
        if (reorderAnim) {
            boolean moving = stepReorder(dt, up);
            if (reorderAppsGrid || reorderGenre || reorderDrawer || reorderGames) heroReorderFrame = moving;
            else if (!topReorderFrame) tilesMoving |= moving;
        }
        boolean statusMoving = step(statusFocus, dt);
        boolean sideMoving = sideShelfSpring.step(dt);
        boolean scrollYMoving = homeScrollY.step(dt);
        boolean heroMoving = heroFocus.step(dt) | scrollYMoving | heroReorderFrame;
        boolean pillMoving = topPill.step(dt);
        boolean topMoving = step(topFocus, dt) | pillMoving | topReorderFrame, cardsMoving = step(cardFocus, dt) | step(cardOpen, dt) | cardScroll.step(dt)
                | step(watchFocus, dt) | watchScroll.step(dt);
        if (sideMoving) {
            platter.invalidate();
            tilesDirty = true;
            sky.invalidate();
            heroDirty = true;
            if (!sideShelfOpen && sideShelfSpring.value <= 0.01f) {
                clearSideBlur();
            }
        }
        boolean tintMoving = tintMix.step(dt);
        if (tintMoving) {
            sky.invalidate();
            platter.invalidate();   // the glass follows the background's colour
            heroDirty = true;       // the cards' text colours too
            statusDirty = true;     // and the corner buttons' glass, on the status layer
            if (glassEasing) scene.setGlassMix(tintMix.value);
        }
        if (topMoving) status.invalidate();
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        heroMoving |= topReorderFrame | cardsMoving | (activeScreen == SCREEN_APPS && (gridMoving || gridDirty));
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        if (gameLaunchModalOpen) {
            float deltaPct = gameLaunchTargetPct - gameLaunchDisplayPct;
            if (Math.abs(deltaPct) > 0.05f) {
                gameLaunchDisplayPct += deltaPct * Math.min(1f, dt * 8.5f);
            } else {
                gameLaunchDisplayPct = gameLaunchTargetPct;
            }
            toastLayer.invalidate();
        }
        boolean heroFading = tickHeroFade(up);
        boolean headerFading = tickHeaderFade(up);
        boolean overviewMoving = stepOverview(up);
        heroMoving |= headerFading | overviewMoving;
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean menuMoving = menu.step(dt) | menuPick.step(dt);
        if (menuMoving || nightMoving && menuOpen) menuLayer.invalidate();
        else if (!menuOpen && menuLayer.getVisibility() == VISIBLE) menuLayer.setVisibility(INVISIBLE);
        boolean recentMoving = recents != null && recents.step(dt);
        if (recents != null) {
            if (recents.visible() != (recentLayer.getVisibility() == VISIBLE)) recentLayer.setVisibility(recents.visible() ? VISIBLE : INVISIBLE);
            if (recentMoving) recentLayer.invalidate();   // idle, the cards are drawn once
            setUnderRecents(recents.covering());
        }

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (clockMode()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = topMoving || tilesMoving || sideMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || menuMoving || sheetMoving || toastFading || heroFading || bgFading || nightMoving || tintMoving || sheetOpen
                || sheet.value > 0.001f || recentMoving || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty()
                || gameLaunchModalOpen;
        idle = !busy;
        if (busy) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
        }
    }

    private final int[] underState = new int[9];
    private boolean underHidden;

    /** Recent Apps covers the screen: the layers below aren't drawn (on slower TVs they cost more than the cards). */
    private void setUnderRecents(boolean covered) {
        if (covered == underHidden) return;
        underHidden = covered;
        View[] under = {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuLayer, toastLayer};
        for (int i = 0; i < under.length; i++) {
            if (covered) {
                underState[i] = under[i].getVisibility();
                under[i].setVisibility(INVISIBLE);
            } else {
                under[i].setVisibility(underState[i]);
            }
        }
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /**
     * While the drawer slides, the focus in the drawer, the hero and the capsule jumps instead of animating: the shelf's
     * focus alone is cheap over the sliding sky (10 ms a frame on a Realtek TCL), but a redraw of the drawer's cards or
     * of the hero on top of it took 35 to 45 ms of GPU there (most frames dropped when a key came during the slide).
     */
    private void settleWhileSliding() {
        if (drawer.settled()) return;
        if (settle(drawerFocus)) drawerDirty = true;
        if (settle(cardFocus) | settle(topFocus) | (!topPill.settled())) {
            topPill.snapToTarget();
            heroDirty = statusDirty = true;
        }
        if (settle(statusFocus)) statusDirty = true;
        if (!heroFocus.settled()) {
            heroFocus.snap(heroFocus.target);
            heroDirty = true;
        }
    }

    private static boolean settle(Spring[] springs) {
        boolean moved = false;
        for (Spring s : springs) {
            if (s.settled()) continue;
            s.snap(s.target);
            moved = true;
        }
        return moved;
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    private final BackdropTransitionState bgTransition = new BackdropTransitionState();

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b != null && b.isRecycled()) return;
        long up = SystemClock.uptimeMillis();
        bgTransition.bgFrom = bgFrom;
        bgTransition.bgTo = bgTo;
        bgTransition.bgFadeAt = bgFadeAt;
        if (!bgTransition.transitionTo(b, up, FADE_MS)) return;
        bgFrom = bgTransition.bgFrom;
        bgTo = bgTransition.bgTo;
        bgFadeAt = bgTransition.bgFadeAt;
        syncDisplayedBackdrops();
        sky.invalidate();
        platter.invalidate();
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || bgFadeAt == 0L || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
            scene.setGlassMix(fadeEase(bgFadeAt, up));
        }
    }

    /** Completes any in-flight backdrop crossfade immediately so non-Home screens never freeze at partial dim. */
    private void snapBackdropFade() {
        if (bgFadeAt != 0 && bgTo != null) {
            bgFadeAt = 0;
            bgFrom = null;
            syncDisplayedBackdrops();
            if (glassOf(bgTo) != null) {
                scene.setFrost(frostOf(bgTo));
                scene.setGlassImage(glassOf(bgTo));
            }
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /**
     * No photo: the glass reads the background's own colour (not Scene's default aurora, which isn't on screen): the
     * gray, or in Continue Watching the focused card's tint.
     */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            glassEasing = false;
            return;
        }
        plainImage(neutralFrostPx, Backdrop.FW, Backdrop.FH, tintTo);
        plainImage(neutralGlassPx, Backdrop.GW, Backdrop.GH, tintTo);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
        glassEasing = false;
    }

    private int cachedNeutralAuroraNightBucket = -1;
    private int[] cachedNeutralFrostAuroraPx;
    private int[] cachedNeutralGlassAuroraPx;

    /** The non-photo background (Unified Cosmic Aurora Mesh or Continue Watching tint) for Liquid Glass refraction. */
    private void plainImage(int[] px, int width, int height, int card) {
        if (!continueMode()) {
            int nightBucket = Math.round(Ui.clamp(night) * 255f);
            if (width == Backdrop.FW && height == Backdrop.FH) {
                if (cachedNeutralFrostAuroraPx == null || cachedNeutralAuroraNightBucket != nightBucket) {
                    cachedNeutralFrostAuroraPx = computeAuroraBuffer(width, height, night);
                }
                System.arraycopy(cachedNeutralFrostAuroraPx, 0, px, 0, px.length);
                return;
            }
            if (width == Backdrop.GW && height == Backdrop.GH) {
                if (cachedNeutralGlassAuroraPx == null || cachedNeutralAuroraNightBucket != nightBucket) {
                    cachedNeutralGlassAuroraPx = computeAuroraBuffer(width, height, night);
                    if (cachedNeutralFrostAuroraPx != null) {
                        cachedNeutralAuroraNightBucket = nightBucket;
                    }
                } else {
                    cachedNeutralAuroraNightBucket = nightBucket;
                }
                System.arraycopy(cachedNeutralGlassAuroraPx, 0, px, 0, px.length);
                return;
            }
            for (int j = 0; j < height; j++) {
                float v = (j + 0.5f) / height;
                int rowOff = j * width;
                for (int i = 0; i < width; i++) {
                    float u = (i + 0.5f) / width;
                    px[rowOff + i] = sampleUnifiedAuroraColor(u, v, night, 0f);
                }
            }
            return;
        }
        for (int j = 0; j < height; j++) {
            java.util.Arrays.fill(px, j * width, (j + 1) * width, plainAt((j + 0.5f) / height, card));
        }
    }

    private static int[] computeAuroraBuffer(int width, int height, float nightVal) {
        int[] buf = new int[width * height];
        for (int j = 0; j < height; j++) {
            float v = (j + 0.5f) / height;
            int rowOff = j * width;
            for (int i = 0; i < width; i++) {
                float u = (i + 0.5f) / width;
                buf[rowOff + i] = sampleUnifiedAuroraColor(u, v, nightVal, 0f);
            }
        }
        return buf;
    }

    /** The plain background's colour at height y (0 = top, 1 = bottom), as drawLightSky draws it. */
    private int plainAt(float y, int card) {
        if (!continueMode()) return blend(0xFFFFFFFF, 0xFF141416, night);
        return blend(tintColor(card), 0xFF000000, 0.2f * y);   // under drawTint's shade
    }

    /** The card's colour as a pale tint by day, a deep one at night (drawTint, before its shade). */
    private int tintColor(int card) {
        return blend(blend(0xFFFFFFFF, card, 0.20f), blend(0xFF141416, card, 0.3f), night);
    }

    /** The day glass's colour: white, or in Continue Watching white with a little of the background's tint. */
    private int glassLight() {
        if (!continueMode()) return 0xFFFFFFFF;
        return blend(0xFFFFFFFF, blend(tintFrom, tintTo, Ui.clamp(tintMix.value)), 0.18f);
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from Prefs.theme() and sun/hours schedule); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget && nightAt == 0 && Math.abs(night - (want ? 1f : 0f)) < 0.001f) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
            nightChanged();
            wake();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        updateVignetteShaders();
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        if (lSubTab != null) lSubTab.setColor(tone(L_DIM, 0xFF8E8E93));
        if (lSubTabOn != null) lSubTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
        if (headerTitlePaint != null) headerTitlePaint.setColor(tone(L_INK, WHITE));
        if (headerMetaPaint != null) headerMetaPaint.setColor(tone(L_INK, 0xEBFFFFFF));
        if (headerGenrePaint != null) headerGenrePaint.setColor(tone(L_DIM, 0xC0FFFFFF));
        if (headerContinuePaint != null) headerContinuePaint.setColor(tone(L_DIM, CONTINUE_STATUS_DARK_COLOR));
        int overviewColor = tone(L_INK, OVERVIEW_DARK_COLOR);
        if (overviewPaint != null) {
            overviewPaint.setColor(overviewColor);
            if (lastOverviewShaderColor != overviewColor) {
                updateOverviewShaders();
            }
        }
        if (continueMode()) applyWatchContrast();
    }

    private int watchTextBg, watchQuoteBg, watchTitleInk, watchMetaInk, watchQuoteInk;
    private int watchTitleColor, watchMetaColor, watchQuoteColor;
    private boolean watchColorsReady;

    /** Contrast changes only with the background or the day/night text colours. */
    private void applyWatchContrast() {
        int card = blend(tintFrom, tintTo, Ui.clamp(tintMix.value));
        int behind = plainAt((L_CARDS_TOP + WatchArt.H + 70) * k / Math.max(1, h), card);
        int quoteBg = plainAt((L_TABBAR_TOP + L_TABBAR_H / 2) * k / Math.max(1, h), card);
        int title = lCardTitle.getColor(), meta = lCardMeta.getColor(), quote = lQuote.getColor();
        if (!watchColorsReady || behind != watchTextBg || quoteBg != watchQuoteBg
                || title != watchTitleInk || meta != watchMetaInk || quote != watchQuoteInk) {
            watchTextBg = behind;
            watchQuoteBg = quoteBg;
            watchTitleInk = title;
            watchMetaInk = meta;
            watchQuoteInk = quote;
            watchTitleColor = readable(title, behind);
            watchMetaColor = readable(meta, behind);
            watchQuoteColor = readable(quote, quoteBg);
            watchColorsReady = true;
        }
        lCardTitle.setColor(watchTitleColor);
        lCardMeta.setColor(watchMetaColor);
        lQuote.setColor(watchQuoteColor);
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            syncDisplayedBackdrops();
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    /**
     * Recreates the multi-stop vignette LinearGradient shaders.
     * ZERO allocations in render loop: invoked ONLY on size change or theme night changes.
     */
    private void updateVignetteShaders() {
        if (w <= 0 || h <= 0) return;
        int themeColor = tone(LIGHT_HERO_BG, 0xFF0B0B0D);
        int nightStep = Math.round(night * 64f);
        if (themeColor == lastVigThemeColor && w == lastVigW && h == lastVigH && nightStep == lastUnifiedNightStep) return;
        lastVigThemeColor = themeColor;
        lastVigW = w;
        lastVigH = h;
        int clearTheme = themeColor & 0x00FFFFFF;

        // 0. Unified Cosmic Aurora Mesh Canvas for all non-hero screens (Search, Apps, Reels, Smart-Home, Settings)
        if (unifiedBgBitmap == null || nightStep != lastUnifiedNightStep) {
            lastUnifiedNightStep = nightStep;
            unifiedBgPixels = generateUnifiedAuroraPixels(UNIFIED_BG_MESH_W, UNIFIED_BG_MESH_H, night, unifiedBgPixels);
            if (unifiedBgBitmap == null) {
                unifiedBgBitmap = Bitmap.createBitmap(UNIFIED_BG_MESH_W, UNIFIED_BG_MESH_H, Bitmap.Config.ARGB_8888);
            }
            unifiedBgBitmap.setPixels(unifiedBgPixels, 0, UNIFIED_BG_MESH_W, 0, 0, UNIFIED_BG_MESH_W, UNIFIED_BG_MESH_H);
        }
        int uniTop = tone(UNIFIED_BG_LIGHT_TOP, UNIFIED_BG_DARK_TOP);
        int uniMid = tone(UNIFIED_BG_LIGHT_MID, UNIFIED_BG_DARK_MID);
        int uniBot = tone(UNIFIED_BG_LIGHT_BOTTOM, UNIFIED_BG_DARK_BOTTOM);
        int uniGlow = tone(UNIFIED_BG_LIGHT_GLOW, UNIFIED_BG_DARK_GLOW);
        unifiedBgBaseGrad = new LinearGradient(0, 0, 0, h,
                new int[] { uniTop, uniMid, uniBot },
                new float[] { 0.0f, 0.52f, 1.0f }, Shader.TileMode.CLAMP);

        unifiedBgGlowGrad = new android.graphics.RadialGradient(
                w * 0.80f, h * 0.16f, Math.max(w, h) * 0.72f,
                new int[] { uniGlow, uniGlow & 0x00FFFFFF },
                new float[] { 0.0f, 1.0f }, Shader.TileMode.CLAMP);
        unifiedBgGlowPaint.setShader(unifiedBgGlowGrad);

        float maxLeftAlpha = (night > 0.5f) ? 0.88f : 0.86f;
        float maxBotAlpha = (night > 0.5f) ? 0.93f : 0.90f;

        // 1. Left Feather Scrim: Rich tint at left edge easing smoothly across 86% width (matching Shaders.HERO_VIGNETTE)
        leftVigGrad = new LinearGradient(0, 0, w * 0.86f, 0,
                new int[] {
                        Ui.alpha(themeColor, maxLeftAlpha),
                        Ui.alpha(themeColor, maxLeftAlpha * 0.75f),
                        Ui.alpha(themeColor, maxLeftAlpha * 0.39f),
                        Ui.alpha(themeColor, maxLeftAlpha * 0.10f),
                        clearTheme
                },
                new float[] { 0.0f, 0.25f, 0.52f, 0.78f, 1.0f }, Shader.TileMode.CLAMP);
        leftVigPaint.setShader(leftVigGrad);

        // 2. Bottom Feather Scrim: Clear at 26% height, easing smoothly to tint at bottom edge
        bottomVigGrad = new LinearGradient(0, h * 0.26f, 0, h,
                new int[] {
                        clearTheme,
                        Ui.alpha(themeColor, maxBotAlpha * 0.34f),
                        Ui.alpha(themeColor, maxBotAlpha * 0.73f),
                        Ui.alpha(themeColor, maxBotAlpha)
                },
                new float[] { 0.0f, 0.36f, 0.70f, 1.0f }, Shader.TileMode.CLAMP);
        bottomVigPaint.setShader(bottomVigGrad);

        // 3. Top Feather Scrim: Clearer 46% tint behind Top-Bar easing out by 25% height
        topVigGrad = new LinearGradient(0, 0, 0, h * 0.25f,
                new int[] { Ui.alpha(themeColor, 0.46f), Ui.alpha(themeColor, 0.18f), clearTheme },
                new float[] { 0.0f, 0.55f, 1.0f }, Shader.TileMode.CLAMP);
        topVigPaint.setShader(topVigGrad);
    }

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private static final int SIDE_SHELF_VISIBLE = 6;
    private static final float SIDE_SHELF_FOCUS_GROW = 0.10f;
    private static final float SIDE_SHELF_INNER_MARGIN = 18f;

    private void layoutShelf() {
        int n = Math.min(shelf.size(), SIDE_SHELF_VISIBLE);
        float gap = 20 * k;
        float tw = 216 * k, th = tw * 9f / 16f;
        float innerMargin = SIDE_SHELF_INNER_MARGIN * k;
        float padX = (tw * SIDE_SHELF_FOCUS_GROW / 2f) + innerMargin;
        float padY = (th * SIDE_SHELF_FOCUS_GROW / 2f) + innerMargin;
        float scrollGutter = (shelf.size() > SIDE_SHELF_VISIBLE) ? (16 * k) : 0f;
        float totalH = n * th + Math.max(0, n - 1) * gap;
        float top = Math.max(76 * k, (h - totalH) / 2f - padY);
        float left = isRtl() ? (w - 56 * k - tw) : (56 * k);
        if (isRtl()) {
            platterRect.set(left - padX - scrollGutter, top, left + tw + padX, top + totalH + 2 * padY);
        } else {
            platterRect.set(left - padX, top, left + tw + padX + scrollGutter, top + totalH + 2 * padY);
        }
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, Math.max(0, shelf.size() - n)));
        float y = top + padY - shelfFirst * (th + gap);
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(left, y, left + tw, y + th);
            snap(tileRects[i]);
            y += th + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the vertical side-shelf, one app at a time, so the focused app is shown. */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), SIDE_SHELF_VISIBLE);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, Math.max(0, shelf.size() - n))));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dy = (shelfFirst - first) * (tileRects[0].height() + 20 * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(0, dy);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dy;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;
        wake();
    }

    /** Side-Shelf layer: draws the edge hint tab when closed, or the vertical glass sidebar when open. */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty() || activeScreen != SCREEN_HOME) return;
        boolean rtl = isRtl();
        float open = Ui.clamp(sideShelfSpring.value);
        if (open < 0.99f) {
            float hintAlpha = 1f - open;
            float hw = SIDE_SHELF_HINT_W * k, hh = SIDE_SHELF_HINT_H * k, hy = (h - hh) / 2f + 30 * k;
            if (rtl) {
                tmp.set(w - hw, hy, w + 18 * k, hy + hh);
            } else {
                tmp.set(-18 * k, hy, hw, hy + hh);
            }
            float hr = SIDE_SHELF_HINT_RADIUS * k;
            shadow.setColor(Ui.alpha(tone(0x28000000, 0x66000000), hintAlpha));
            shadows.draw(c, tmp, hr, SHADOW_BLUR * k, shadow);
            scene.liquid(c, tmp, hr, tone(glassLight(), SMOKE), tone(0.48f, 0.22f), tone(0.03f, 0.08f),
                    24 * k, 18 * k, tone(0.65f, 0.9f), hintAlpha);
        }
        if (open <= 0.01f) return;
        if (sideShelfBlur != null && !sideShelfBlur.isRecycled()) {
            imagePaint.setColorFilter(veil(0.56f, 0xFF05060A));
            drawImage(c, sideShelfBlur, open);
            imagePaint.setColorFilter(null);
        } else {
            c.drawColor(Ui.alpha(0xFF05060A, 0.78f * open));
        }
        float slideX = (rtl ? 1f : -1f) * (1f - open) * 320 * k;
        c.save();
        c.translate(slideX, 0);
        // Concentric squircle radius matching the hovered app tile radius + uniform inner margin
        float radius = (TILE_RADIUS * (1f + SIDE_SHELF_FOCUS_GROW) + SIDE_SHELF_INNER_MARGIN) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(Ui.alpha(tone(0x26000000, 0x66000000), open));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        scene.liquid(c, platterRect, radius, tone(glassLight(), SMOKE), tone(0.55f, 0.25f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), open);
        c.restore();
    }

    // What's playing, cut to fit the tab row and the clock's row: once per title, never while drawing.
    private String playingTab, playingClock;
    private float playingTabWidth, playingTabCap, playingClockCap;
    private final RectF barRect = new RectF();
    // The bars bounce for a few seconds when the title changes, then stand still: bouncing all along redrew the top
    // layer 10 times a second (~8% of a core on a Skyworth's Cortex-A55), the still screen nothing.
    private static final long BAR_FRAME_MS = 100, BARS_BOUNCE_MS = 3000;
    private long barsUntil;
    private String shownTitle;   // kept while the screen is hidden: showing it again with the same title is no change
    private final Runnable bars = new Runnable() {
        @Override public void run() {
            if (!running || playingTab == null) return;
            heroDirty = true;
            wake();
            if (SystemClock.uptimeMillis() < barsUntil) handler.postDelayed(this, BAR_FRAME_MS);
        }
    };

    private void bounceBars() {
        handler.removeCallbacks(bars);
        if (playingTab == null) return;
        barsUntil = SystemClock.uptimeMillis() + BARS_BOUNCE_MS;
        handler.post(bars);
    }

    /** What's playing changed (or stopped). */
    private void onPlaying() {
        fitPlaying();
        heroDirty = true;
        wake();
        // Only a new title bounces the bars and is said to TalkBack: not a subtitle some players rewrite every second (the
        // time), and not the same title again when the screen shows.
        String title = playing.title, now = playing.text;
        if (title != null && now != null && running && playingTab != null && !title.equals(shownTitle)) {
            shownTitle = title;
            bounceBars();
            if (readerOn()) announceForAccessibility(Ui.trf("Now playing: {what}", "what", now.replace(" · ", ", ")));
        }
    }

    /**
     * A screen reader is on (TalkBack, or another service that speaks): only then is a new title announced. Other
     * accessibility services (a button mapper, a sleep timer) would get the announcement too, for nothing.
     */
    private boolean readerOn() {
        AccessibilityManager am = getContext().getSystemService(AccessibilityManager.class);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    private void fitPlaying() {
        String now = playing.text;
        if (now == null || lQuote == null) {
            playingTab = playingClock = null;
            return;
        }
        fitPaint.set(lQuote);
        playingTab = TextUtils.ellipsize(now, fitPaint, 640 * k, TextUtils.TruncateAt.END).toString();
        playingTabWidth = lQuote.measureText(playingTab);
        playingTabCap = Ui.cap(lQuote);
        playingClockCap = Ui.cap(lClockQuote);
        fitPaint.set(lClockQuote);
        playingClock = TextUtils.ellipsize(now, fitPaint, (CONTENT_R - MARGIN - 60) * k, TextUtils.TruncateAt.END).toString();
    }

    private void drawTiles(Canvas c) {
        if (shelf.isEmpty() || activeScreen != SCREEN_HOME) return;
        boolean rtl = isRtl();
        float open = Ui.clamp(sideShelfSpring.value);
        if (open < 0.99f) {
            float hintAlpha = 1f - open;
            float hw = SIDE_SHELF_HINT_W * k, hh = SIDE_SHELF_HINT_H * k, hy = (h - hh) / 2f + 30 * k;
            float cx = rtl ? (w - hw / 2f) : (hw / 2f);
            int hintColor = Ui.alpha(tone(L_INK, 0xEBFFFFFF), 0.9f * hintAlpha);
            Symbols.draw(getContext(), c, rtl ? "chevron_right" : "chevron_left", cx, hy + hh * 0.5f, 16 * k, hintColor);
        }
        if (open <= 0.01f) return;
        float slideX = (rtl ? 1f : -1f) * (1f - open) * 320 * k;
        c.save();
        c.translate(slideX, 0);
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (!reorderTop && liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        float innerTop = platterRect.top + 16 * k;
        float innerBottom = platterRect.bottom - 16 * k;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) {
                c.save();
                c.clipRect(platterRect.left, innerTop, platterRect.right, innerBottom);
            }
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float dy = i < tileSlide.length ? tileSlide[i].value : 0;
                if (Math.abs(dy) < 0.5f && (i < shelfFirst || i >= shelfFirst + SIDE_SHELF_VISIBLE)) continue;
                if (tileRects[i].bottom + dy <= innerTop || tileRects[i].top + dy >= innerBottom) continue;
                float angle = !reorderTop && i == liftIndex ? wiggle * moveLift.value : 0;
                if (dy == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(0, dy);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
            if (pass == 0) {
                c.restore();
            }
        }
        if (shelf.size() > SIDE_SHELF_VISIBLE) {
            float trackTop = platterRect.top + 32 * k;
            float trackBottom = platterRect.bottom - 32 * k;
            float trackH = Math.max(40 * k, trackBottom - trackTop);
            float trackW = 5.5f * k;
            float trackX = rtl ? (platterRect.left + 10 * k) : (platterRect.right - 10 * k - trackW);
            fill.setShader(null);
            fill.setColor(Ui.alpha(tone(0x2E343741, 0x28FFFFFF), open));
            tmp.set(trackX, trackTop, trackX + trackW, trackBottom);
            c.drawRoundRect(tmp, trackW / 2f, trackW / 2f, fill);

            float thumbRatio = Math.min(1f, SIDE_SHELF_VISIBLE / (float) shelf.size());
            float thumbH = Math.max(28 * k, trackH * thumbRatio);
            int maxFirst = Math.max(1, shelf.size() - SIDE_SHELF_VISIBLE);
            float scrollFrac = Ui.clamp(shelfFirst / (float) maxFirst);
            float thumbTop = trackTop + (trackH - thumbH) * scrollFrac;
            fill.setColor(Ui.alpha(tone(0xC8343741, 0xEBFFFFFF), open));
            tmp.set(trackX, thumbTop, trackX + trackW, thumbTop + thumbH);
            c.drawRoundRect(tmp, trackW / 2f, trackW / 2f, fill);
        }

        // Minimalistic, subtle right-arrow on the shelf's outer side (indicating right-arrow closes shelf)
        float chevronX = rtl ? (platterRect.left - 22 * k) : (platterRect.right + 22 * k);
        Symbols.draw(getContext(), c, rtl ? "chevron_left" : "chevron_right",
                chevronX, platterRect.centerY(), 20 * k, Ui.alpha(tone(L_INK, 0xFFFFFFFF), 0.55f * open));

        c.restore();
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 10% from the center, concentric with the side-shelf squircle platter.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = !reorderTop && i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + SIDE_SHELF_FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /** The image cropped at the center to the cell's shape (not stretched), at `alpha`. */
    private void coverImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float cellRatio = cell.width() / cell.height();
        float imgRatio = iw / ih;
        float sw, sh, sl, st, s;
        if (imgRatio >= cellRatio) {
            // Image is wider than cell (e.g. 16:9 backdrop in 2:3 card): crop sides
            sh = ih;
            sw = cellRatio * ih;
            sl = (iw - sw) / 2f;
            st = 0f;
            s = cell.height() / ih;
        } else {
            // Image is taller than cell: anchor at top (st = 0) so top-placed titles are never cropped
            sw = iw;
            sh = iw / cellRatio;
            sl = 0f;
            st = 0f;
            s = cell.width() / iw;
        }
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(s, s);
        c.translate(-sl, -st);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(sl, st, sl + sw, st + sh, r / s, r / s, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + SIDE_SHELF_FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + SIDE_SHELF_FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + SIDE_SHELF_FOCUS_GROW), bh = tileRects[0].height() * (1 + SIDE_SHELF_FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + SIDE_SHELF_FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        list.addAll(SmartHomeManager.get(c).drawerItems());
        List<String> savedOrder = Prefs.drawerOrder();
        if (savedOrder != null && !savedOrder.isEmpty()) {
            List<HomeApps.Item> ordered = new ArrayList<>(list.size());
            for (String key : savedOrder) {
                for (HomeApps.Item it : list) {
                    if (key.equals(it.key) && !ordered.contains(it)) ordered.add(it);
                }
            }
            for (HomeApps.Item it : list) {
                if (!ordered.contains(it)) ordered.add(it);
            }
            return ordered;
        }
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        int home = inputs;
        while (home < list.size() && list.get(home).kind != HomeApps.SMART_DEVICE) home++;
        drawerItems = list;
        drawerInputs = inputs;
        drawerHome = home;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = Math.max(1, (drawerInputs > 0 ? 1 : 0) + (drawerHome > drawerInputs ? 1 : 0) + (n > drawerHome ? 1 : 0));
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if ((i == drawerInputs || i == drawerHome) && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k;
        weatherCard.set(left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
        homeChanged();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** A Smart-Home device state changed: refresh cached drawer tiles. */
    private void homeChanged() {
        List<HomeApps.Item> tiles = new ArrayList<>(SmartHomeManager.get(getContext()).drawerItems());
        boolean same = tiles.size() == drawerItems.size() - drawerHome;
        for (int i = 0; same && i < tiles.size(); i++) same = tiles.get(i).key.equals(drawerItems.get(drawerHome + i).key);
        if (!same) {
            List<HomeApps.Item> list = new ArrayList<>(drawerItems.subList(0, drawerHome));
            list.addAll(tiles);
            setDrawerItems(list);
        }
        for (int i = drawerHome; i < drawerItems.size(); i++) {
            HomeApps.Item it = drawerItems.get(i);
            if (it.smartDevice != null) it.label = it.smartDevice.getDisplayLabel();
        }
        drawerDirty = true;
        wake();
    }

    private void pressHome(HomeApps.Item it) {
        if (it.kind == HomeApps.SMART_DEVICE && it.smartDevice != null) {
            SmartHomeManager mgr = SmartHomeManager.get(getContext());
            if (it.smartDevice.getProtocol() == SmartHomeManager.Protocol.WIZ_LIGHT) {
                if (mgr.hasConnectedLightsInTvRoom()) {
                    mgr.toggleTvRoomLights((ok, msg) -> {
                        showToast(msg);
                        homeChanged();
                        rebuildScreenRows();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    });
                    homeChanged();
                } else {
                    openSmartDeviceControlCard(it.smartDevice);
                }
                return;
            }
            mgr.toggleDevice(it.smartDevice.getId(), (ok, msg) -> {
                showToast(msg);
                homeChanged();
            });
            homeChanged();
        }
    }

    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, glassLight(), lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerHome) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        if (drawerHome < drawerItems.size()) c.drawText(Ui.tr("Smart Home"), drawerRects[drawerHome].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
    }

    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected
                || it.kind == HomeApps.SMART_DEVICE && it.smartDevice != null && !it.smartDevice.isOn();
        int ink = blend(tone(off ? L_FAINT : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k;
        if (textDays) {
            float hw = (r.right - 30 * k - hx) / 5;
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(dayText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                float maxW = hourTemp.measureText(dayMaxText[i]), minW = dayLow.measureText(dayMinText[i]), gap = 6 * k;
                float left = hc - (maxW + gap + minW) / 2;
                c.drawText(dayMaxText[i], left + maxW / 2, r.top + 196 * k, hourTemp);
                c.drawText(dayMinText[i], left + maxW + gap + minW / 2, r.top + 196 * k, dayLow);
            }
        } else {
            float hw = (r.right - 30 * k - hx) / 6;
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
            }
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    private String drawerCardsKey() {
        return data.weatherAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = false;
        if (!preserveTopBarSpringsOnRetarget) {
            topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
            if (!sheetOpen && zone == Z_TOP && topIndex >= 0 && topIndex < TOP_BAR_COUNT && !topRects[topIndex].isEmpty()) {
                topPill.retarget(topRects[topIndex], true, snap);
            } else {
                topPill.retarget(null, false, snap);
            }
        }

        if (activeScreen == SCREEN_APPS) {
            retargetAppsGrid(snap);
            heroChanged = true;
        } else if (!browserRows.isEmpty()) {
            homeRow = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
            BrowserRow row = browserRows.get(homeRow);
            int maxCol = Math.max(0, row.count() - 1);
            homeCol = Math.max(0, Math.min(homeCol, maxCol));
            if (row.kind == ROW_MEDIA_SELECTOR) {
                row.scrollX = 0;
            } else {
                boolean isHomeMediaRow = (activeScreen == SCREEN_HOME
                        && (row.kind == ROW_CONTINUE || row.kind == ROW_PICKS || row.kind == ROW_GENRE));
                float cardStep = (isHomeMediaRow ? (MEDIA_CARD_W + MEDIA_CARD_GAP) : (WIDE_BROWSER_CARD_W + WIDE_BROWSER_CARD_GAP)) * k;
                int leadCols = isHomeMediaRow ? 3 : 2;
                float targetScrollX = Math.max(0f, (homeCol - leadCols) * cardStep);
                if (row.scrollX != targetScrollX) {
                    row.scrollX = targetScrollX;
                    heroChanged = true;
                }
                rowScrollMemory.save(rowMemoryKey(row), homeCol, row.scrollX);
            }
            float yCursor = 0f;
            for (int r = 0; r < homeRow; r++) {
                yCursor += browserRows.get(r).height(k, activeScreen);
            }
            float targetY;
            if (activeScreen == SCREEN_HOME) {
                targetY = yCursor;
            } else {
                float maxScrollY = Math.max(0f, totalRowsHeight() - (h - 220 * k));
                targetY = homeRow == 0 ? 0f : Math.max(0f, Math.min(maxScrollY, yCursor - 110 * k));
            }
            if (homeScrollY.target != targetY || snap) {
                homeScrollY.target = targetY;
                if (snap) homeScrollY.snap(targetY);
                heroChanged = true;
            }
            if (activeScreen == SCREEN_HOME) {
                String activeSubKind = subTab == 0 ? "tv" : "movie";
                recs.setActiveSubKind(activeSubKind);
                // Advance lazy-loading in user's top-to-bottom row order across active & upcoming rows
                int prefetchMaxRow = Math.min(browserRows.size() - 1, homeRow + 2);
                for (int rIdx = Math.max(0, homeRow); rIdx <= prefetchMaxRow; rIdx++) {
                    BrowserRow br = browserRows.get(rIdx);
                    int cursor = (rIdx == homeRow) ? homeCol : 0;
                    if ((br.kind == ROW_PICKS || br.kind == ROW_GENRE) && cursor + 10 >= br.pickItems.size()) {
                        recs.prefetchMoreForRow(activeSubKind, br.pickKind, br.genreKey);
                    }
                }
                // Optimal RAM management: keep the first 12 preloaded items of every row in RAM in user row order,
                // plus pre-decode posters ahead of focus on the active & adjacent rows.
                Set<String> activeBackdrops = new HashSet<>();
                for (int rIdx = Math.max(0, homeRow - 2); rIdx <= Math.min(browserRows.size() - 1, homeRow + 3); rIdx++) {
                    BrowserRow br = browserRows.get(rIdx);
                    if (br.pickItems != null && !br.pickItems.isEmpty()) {
                        int preloadMax = Math.min(br.pickItems.size() - 1, 8);
                        for (int cIdx = 0; cIdx <= preloadMax; cIdx++) {
                            Recs.Pick p = br.pickItems.get(cIdx);
                            if (p != null) {
                                if (p.backdrop != null && !p.backdrop.isEmpty()) activeBackdrops.add(p.backdrop);
                                String pk = Recs.posterCacheKey(p);
                                if (pk != null) activeBackdrops.add(pk);
                            }
                        }
                    }
                }
                int rMin = Math.max(0, homeRow - 2);
                int rMax = Math.min(browserRows.size() - 1, homeRow + 3);
                for (int rIdx = rMin; rIdx <= rMax; rIdx++) {
                    BrowserRow br = browserRows.get(rIdx);
                    if (br.kind == ROW_CONTINUE && br.watchItems != null && !br.watchItems.isEmpty()) {
                        int wCenter = (rIdx == homeRow) ? homeCol : 0;
                        int wMin = Math.max(0, wCenter - 1);
                        int wMax = Math.min(br.watchItems.size() - 1, wCenter + 3);
                        for (int wIdx = wMin; wIdx <= wMax; wIdx++) {
                            if (rIdx != homeRow || wIdx != homeCol) {
                                recs.preloadWatchBackdropAsync(br.watchItems.get(wIdx));
                            }
                        }
                    }
                    if (br.pickItems != null && !br.pickItems.isEmpty()) {
                        int cCenter = (rIdx == homeRow) ? homeCol : 0;
                        int cMin = Math.max(0, cCenter - 4);
                        int cMax = Math.min(br.pickItems.size() - 1, cCenter + 14);
                        for (int cIdx = cMin; cIdx <= cMax; cIdx++) {
                            Recs.Pick p = br.pickItems.get(cIdx);
                            if (p != null) {
                                if (p.backdrop != null && !p.backdrop.isEmpty()) activeBackdrops.add(p.backdrop);
                                String pk = Recs.posterCacheKey(p);
                                if (pk != null) activeBackdrops.add(pk);
                                if (rIdx >= Math.max(0, homeRow - 1) && rIdx <= homeRow + 3
                                        && cIdx >= cCenter - 2 && cIdx <= cCenter + 12) {
                                    recs.poster(p);
                                }
                                // Pre-decode neighboring hero backdrops so D-pad navigation swaps in 0ms
                                boolean isAdjacentInRow = (rIdx == homeRow && cIdx != homeCol
                                        && cIdx >= homeCol - 1 && cIdx <= homeCol + 3);
                                boolean isAdjacentRowLead = ((rIdx == homeRow + 1 || rIdx == homeRow - 1) && cIdx == 0);
                                if (isAdjacentInRow || isAdjacentRowLead) {
                                    recs.preloadBackdropAsync(p);
                                }
                            }
                        }
                    }
                }
                recs.trimBitmapsToActiveWindow(activeBackdrops);
            }
        }

        if (activeScreen == SCREEN_HOME) {
            updateHeaderFocus(snap);
            recs.setActiveFocusKey(focusedHomeBackdropKey());
            handler.removeCallbacks(bgFollow);
            boolean readyInRam = peekFocusedHomeBackdrop() != null;
            if (snap || readyInRam) {
                bgFollow.run();
            } else {
                handler.postDelayed(bgFollow, BG_FOLLOW_MS);
            }
        } else if (activeScreen == SCREEN_GAMING) {
            handler.removeCallbacks(bgFollow);
            GamingZone.GameItem fg = focusedGameOnGaming();
            boolean readyInRam = fg != null && GamingZone.peekGameBackdrop(fg) != null;
            if (snap || readyInRam) {
                bgFollow.run();
            } else {
                handler.postDelayed(bgFollow, BG_FOLLOW_MS);
            }
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged || zone == Z_CARDS;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    private float totalRowsHeight() {
        float sum = 0f;
        for (BrowserRow r : browserRows) sum += r.height(k, activeScreen);
        return sum;
    }

    private void retargetAppsGrid(boolean snap) {
        int n = allAppsList.size();
        if (gridFocus.length != n) {
            gridFocus = new Spring[n];
            for (int i = 0; i < n; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
            gridLabels = null;
        }
        gridIndex = Math.max(0, Math.min(gridIndex, Math.max(0, n - 1)));
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = (zone == Z_CARDS && i == gridIndex) ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (n + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet & Apps screen

    private void rebuildAllAppsList() {
        List<HomeApps.Item> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HomeApps.Item it : shelf) {
            if (it != null && it.pkg != null && seen.add(it.pkg)) raw.add(it);
        }
        for (HomeApps.Item it : others) {
            if (it != null && it.pkg != null && seen.add(it.pkg)) raw.add(it);
        }
        Set<String> permHidden = new HashSet<>(Prefs.permHiddenApps());
        Set<String> tempHidden = new HashSet<>(Prefs.hiddenApps());
        List<HomeApps.Item> visible = new ArrayList<>();
        List<HomeApps.Item> hiddenInstalled = new ArrayList<>();
        for (HomeApps.Item it : raw) {
            if (permHidden.contains(it.pkg)) continue;
            if (tempHidden.contains(it.pkg)) {
                it.isHiddenItem = true;
                hiddenInstalled.add(it);
                continue;
            }
            it.isHiddenItem = false;
            visible.add(it);
        }
        List<String> customOrder = Prefs.appOrder();
        if (customOrder != null && !customOrder.isEmpty()) {
            java.util.Map<String, Integer> rank = new java.util.HashMap<>();
            for (int i = 0; i < customOrder.size(); i++) rank.put(customOrder.get(i), i);
            visible.sort((a, b) -> {
                int ra = rank.containsKey(a.pkg) ? rank.get(a.pkg) : Integer.MAX_VALUE;
                int rb = rank.containsKey(b.pkg) ? rank.get(b.pkg) : Integer.MAX_VALUE;
                return Integer.compare(ra, rb);
            });
        }
        if (!hiddenInstalled.isEmpty()) {
            visible.add(HomeApps.hiddenAppsTile(getContext(), hiddenInstalled.size()));
            if (showHiddenAppsInline) {
                visible.addAll(hiddenInstalled);
            }
        } else {
            showHiddenAppsInline = false;
        }
        allAppsList = visible;
        if (gridFocus.length < allAppsList.size()) {
            Spring[] nextFocus = new Spring[allAppsList.size()];
            for (int i = 0; i < nextFocus.length; i++) {
                nextFocus[i] = i < gridFocus.length ? gridFocus[i] : new Spring(0.3f, 0.82f, 0);
            }
            gridFocus = nextFocus;
        }
        gridIndex = Math.min(gridIndex, Math.max(0, allAppsList.size() - 1));
        gridLabels = null;
        if (activeScreen == SCREEN_APPS) {
            retargetAppsGrid(true);
            heroDirty = true;
        }
    }

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        rebuildAllAppsList();
        gridFocus = new Spring[Math.max(list.size(), allAppsList.size())];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, allAppsList.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens the Apps screen. */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        selectScreen(SCREEN_APPS);
        zone = Z_CARDS;
        retarget(false);
    }

    private boolean allAppsOnResume;

    /** Returns focus cleanly to the Reels Top-Bar tab after exiting {@link ReelsPlayerActivity}. */
    void showReelsScreen() {
        if (!running) {
            reelsOnResume = true;
            return;
        }
        selectScreen(SCREEN_REELS);
        zone = Z_TOP;
        topIndex = SCREEN_REELS;
        retarget(false);
    }

    private boolean reelsOnResume;

    /** Launches the isolated full-screen adaptive vertical/horizontal Reels player immediately. */
    private void launchReelsPlayer(boolean openWatchLater) {
        try {
            Intent intent = new Intent(activity, ReelsPlayerActivity.class)
                    .putExtra(ReelsPlayerActivity.EXTRA_OPEN_WATCH_LATER, openWatchLater);
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            showToast(Ui.tr("Couldn't open Reels player."));
        }
    }

    /** Launches the dedicated TV Reels account &amp; family group chat setup activity. */
    private void launchReelsConnectSetup() {
        try {
            activity.startActivity(new Intent(activity, ReelsConnectActivity.class));
        } catch (RuntimeException e) {
            showToast(Ui.tr("Couldn't open Reels account setup."));
        }
    }

    /** Opens the Gaming screen directly when returning from an active native game stream. */
    void showGamingScreen() {
        if (!running) {
            gamingOnResume = true;
            return;
        }
        selectScreen(SCREEN_GAMING);
        zone = Z_CARDS;
        retarget(false);
    }

    private boolean gamingOnResume;

    /**
     * Opens SoarTV's Settings tab directly (e.g. from the physical TV remote's Settings gear button).
     * Closes any open overlays and preserves inner Settings navigation if already inside Settings.
     */
    void showSettingsScreen() {
        if (!running) {
            settingsOnResume = true;
            return;
        }
        closeLiquidGlassModal(false);
        closeMenu(true);
        if (sideShelfOpen) closeSideShelf();
        if (sheetOpen) closeSheet();
        if (recents != null && recents.isOpen()) recents.closeNow();
        if (activeScreen != SCREEN_SETTINGS) {
            selectScreen(SCREEN_SETTINGS);
        }
        zone = Z_CARDS;
        rebuildSettingsTree();
        retarget(false);
        heroDirty = true;
        heroLayer.invalidate();
        wake();
    }

    private boolean settingsOnResume;

    /**
     * Captures the exact screen, cursor, Back history, and nested Settings navigation state so returning
     * from Native Android TV Settings or an Activity recreation restores the user's exact location.
     */
    HomeInputRouter.NavigationSnapshot captureNavigationSnapshot() {
        return new HomeInputRouter.NavigationSnapshot(
                activeScreen,
                topIndex,
                zone,
                subTab,
                homeRow,
                homeCol,
                gridIndex,
                settingsPathIds,
                settingsDepthIndices,
                settingsFocusRight,
                settingsRightIndex,
                backStack.snapshotStates());
    }

    /**
     * Restores a previously captured {@link HomeInputRouter.NavigationSnapshot} cleanly.
     */
    void restoreNavigationSnapshot(HomeInputRouter.NavigationSnapshot snap) {
        if (snap == null) return;
        activeScreen = Math.max(0, Math.min(TOP_BAR_COUNT - 1, snap.activeScreen));
        topIndex = Math.max(0, Math.min(TOP_BAR_COUNT - 1, snap.topIndex));
        zone = snap.zone;
        subTab = Math.max(0, Math.min(1, snap.subTab));
        recs.setActiveSubKind(subTab == 0 ? "tv" : "movie");
        homeRow = Math.max(0, snap.homeRow);
        homeCol = Math.max(0, snap.homeCol);
        gridIndex = Math.max(0, snap.gridIndex);
        settingsPathIds.clear();
        settingsPathIds.addAll(snap.settingsPathIds);
        settingsStepDepthStack.clear();
        for (int i = 0; i < snap.settingsPathIds.size(); i++) {
            settingsStepDepthStack.push(1);
        }
        java.util.Arrays.fill(settingsDepthIndices, 0);
        System.arraycopy(snap.settingsDepthIndices, 0, settingsDepthIndices, 0,
                Math.min(snap.settingsDepthIndices.length, settingsDepthIndices.length));
        settingsFocusRight = snap.settingsFocusRight;
        settingsRightIndex = Math.max(0, snap.settingsRightIndex);
        backStack.restoreStates(snap.backHistory);
        if (activeScreen == SCREEN_SETTINGS) {
            rebuildSettingsTree();
        } else if (activeScreen == SCREEN_APPS) {
            rebuildAllAppsList();
        } else {
            rebuildScreenRows();
        }
        retarget(true);
        heroDirty = true;
        statusDirty = true;
        motionDirty = true;
    }

    private void openSheet() {
        selectScreen(SCREEN_APPS);
        zone = Z_CARDS;
        retarget(false);
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        List<HomeApps.Item> list = activeScreen == SCREEN_APPS ? allAppsList : others;
        if (gridFocus.length < list.size()) {
            gridFocus = new Spring[list.size()];
            for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        }
        for (int i = 0; i < list.size(); i++) {
            gridFocus[i].target = (sheetOpen || (activeScreen == SCREEN_APPS && zone == Z_CARDS)) && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (list.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) {
            if (it.isHiddenItem) imageFill.setColorFilter(GRAYSCALE_DIM_FILTER);
            roundImage(c, it.tile, it.tileShader, cell, r);
            if (it.isHiddenItem) imageFill.setColorFilter(null);
        }
        if (it.isHiddenItem) {
            veil.setColor(0x66000000);
            c.drawRoundRect(cell, r, r, veil);
        }
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        if (text == null || text.isEmpty()) return;
        toasts.clear();
        if (toastPaint != null) {
            toast = layout(text);
            toastAt = SystemClock.uptimeMillis();
            toastLayer.invalidate();
        } else {
            toasts.add(text);
        }
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < FADE_MS || age > TOAST_MS - FADE_MS;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private float titleRight;   // where the top title ends (0: none), so toasts don't cover it

    private void startSyncModal(String title, String initialStep, int initialPct) {
        syncModalOpen = true;
        syncModalTitle = title == null ? Ui.tr("Syncing…") : title;
        syncModalStep = initialStep == null ? "" : initialStep;
        syncModalProgress = Math.max(5, Math.min(100, initialPct));
        toastLayer.invalidate();
        wake();
    }

    private void updateSyncModal(String step, int pct, boolean done) {
        if (!syncModalOpen) return;
        if (step != null && !step.isEmpty()) syncModalStep = step;
        if (pct > 0) syncModalProgress = Math.max(syncModalProgress, Math.min(100, pct));
        if (done) {
            syncModalProgress = 100;
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
            handler.postDelayed(() -> {
                if (syncModalOpen && syncModalProgress >= 100) {
                    syncModalOpen = false;
                    toastLayer.invalidate();
                    wake();
                }
            }, 420);
        }
        toastLayer.invalidate();
        wake();
    }

    private void drawSyncModal(Canvas c) {
        modalRenderer.drawSyncModal(
                getContext(), c, w, h, k, screen,
                syncModalTitle, syncModalStep, syncModalProgress,
                dark() > 0.5f, L_INK, L_DIM, WHITE, DEEP_BLUR,
                fill, border, shadowDeep, lCardTitle, lSubTab, lSubTabOn,
                fitPaint, shadows, tmp, syncCancelRect);
    }

    void startGameLaunchModal(
            String gameTitle,
            String headline,
            String subtitle,
            String iconName,
            int stageIndex,
            int initialPct
    ) {
        gameLaunchModalOpen = true;
        gameLaunchTitle = gameTitle != null ? gameTitle : "";
        gameLaunchHeadline = headline != null ? headline : "";
        gameLaunchSubtitle = subtitle != null ? subtitle : "";
        gameLaunchIcon = (iconName != null && !iconName.isEmpty()) ? iconName : "power";
        gameLaunchStageIndex = Math.max(0, Math.min(3, stageIndex));
        float clamped = Math.max(6f, Math.min(100f, initialPct));
        gameLaunchTargetPct = clamped;
        gameLaunchDisplayPct = Math.min(clamped, 10f);
        toastLayer.invalidate();
        wake();
    }

    void updateGameLaunchModal(
            String headline,
            String subtitle,
            String iconName,
            int stageIndex,
            int targetPct
    ) {
        if (!gameLaunchModalOpen) return;
        if (headline != null && !headline.isEmpty()) gameLaunchHeadline = headline;
        if (subtitle != null) gameLaunchSubtitle = subtitle;
        if (iconName != null && !iconName.isEmpty()) gameLaunchIcon = iconName;
        gameLaunchStageIndex = Math.max(gameLaunchStageIndex, Math.max(0, Math.min(3, stageIndex)));
        gameLaunchTargetPct = Math.max(gameLaunchTargetPct, Math.min(100f, targetPct));
        toastLayer.invalidate();
        wake();
    }

    void closeGameLaunchModal() {
        if (!gameLaunchModalOpen) return;
        gameLaunchModalOpen = false;
        toastLayer.invalidate();
        wake();
    }

    private void cancelGameLaunchModalByUser() {
        GamingZone.cancelActiveGameLaunch();
        closeGameLaunchModal();
        okDown = okHeld = backDown = false;
        click.move();
        showToast(Ui.tr("Game launch cancelled"));
    }

    private void drawGameLaunchModal(Canvas c) {
        float spinnerDeg = ((SystemClock.uptimeMillis() % 1200L) / 1200f) * 360f;
        modalRenderer.drawGameLaunchModal(
                getContext(), c, w, h, k, screen,
                gameLaunchTitle, gameLaunchHeadline, gameLaunchSubtitle,
                gameLaunchIcon, gameLaunchStageIndex, gameLaunchDisplayPct, spinnerDeg,
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint,
                tmp, gameLaunchCancelRect);
    }

    private void clearPairingQrBitmap() {
        if (pairingQrBitmap != null) {
            Backdrop.scheduleSafeBitmapRecycle(pairingQrBitmap);
            pairingQrBitmap = null;
        }
        pairingQrRequestId = "";
    }

    private void refreshPairingPromptState() {
        Context ctx = getContext() != null ? getContext().getApplicationContext() : null;
        if (ctx == null) return;
        RemoteAuthManager.PendingPairRequest req = RemoteAuthManager.get(ctx).getActivePendingRequest();
        if (req == null) {
            boolean hadModal = activePendingPairing != null;
            activePendingPairing = null;
            clearPairingQrBitmap();
            pairingModalOkDown = false;
            pairingModalBackDown = false;
            if (hadModal) {
                toastLayer.invalidate();
                wake();
            }
            return;
        }
        if (!req.requestId.equals(pairingQrRequestId) || pairingQrBitmap == null) {
            clearPairingQrBitmap();
            int qrSizePx = Math.max(220, Math.round(280f * Math.max(0.75f, k)));
            pairingQrBitmap = RemoteAuthManager.encodePairingQrBitmap(req.pairUrl, qrSizePx);
            pairingQrRequestId = req.requestId;
            pairingModalFocus = HomeInputRouter.initialPairingModalFocus(req.presetRole, req.bootstrapAdmin);
            pairingModalOpenedAt = SystemClock.uptimeMillis();
            pairingModalOkDown = false;
            pairingModalBackDown = false;
            okDown = okHeld = backDown = false;
        }
        activePendingPairing = req;
        toastLayer.invalidate();
        wake();
    }

    private void drawPairingModal(Canvas c) {
        if (activePendingPairing == null) return;
        modalRenderer.drawPairingModal(
                getContext(), c, w, h, k,
                activePendingPairing, pairingQrBitmap, pairingModalFocus,
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, bitmapPaint, lCardTitle, lSubTab, lSubTabOn, fitPaint);
    }

    private void executePairingModalAction(int actionIndex) {
        Context ctx = getContext() != null ? getContext().getApplicationContext() : null;
        if (ctx == null) return;
        RemoteAuthManager auth = RemoteAuthManager.get(ctx);
        switch (actionIndex) {
            case 0: {
                RemoteAuthManager.PairedDevice d = auth.approvePendingOnTv(RemoteAuthManager.Role.GUEST);
                if (d != null) showToast(Ui.tr("Approved Guest remote (30 days): ") + d.deviceName);
                break;
            }
            case 1: {
                RemoteAuthManager.PairedDevice d = auth.approvePendingOnTv(RemoteAuthManager.Role.HOUSEHOLD);
                if (d != null) showToast(Ui.tr("Approved Household remote: ") + d.deviceName);
                break;
            }
            case 2: {
                RemoteAuthManager.PairedDevice d = auth.approvePendingOnTv(RemoteAuthManager.Role.ADMIN);
                if (d != null) showToast(Ui.tr("Approved Admin remote: ") + d.deviceName);
                break;
            }
            default:
                auth.dismissPendingOnTv();
                showToast(Ui.tr("Pairing request dismissed"));
                break;
        }
        click.open();
        refreshPairingPromptState();
        if (activeScreen == SCREEN_SETTINGS) {
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
        }
    }

    private boolean handlePairingModalKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        if (SystemClock.uptimeMillis() - pairingModalOpenedAt < 140L && up) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, -1, 0);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 1, 0);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 0, -1);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 0, 1);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    pairingModalOkDown = true;
                } else if (up && pairingModalOkDown && !e.isCanceled()) {
                    pairingModalOkDown = false;
                    executePairingModalAction(pairingModalFocus);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && e.getRepeatCount() == 0) {
                    pairingModalBackDown = true;
                } else if (up && pairingModalBackDown && !e.isCanceled()) {
                    pairingModalBackDown = false;
                    executePairingModalAction(3);
                }
                return true;
            default:
                return true;
        }
    }

    private void handlePairingModalRemoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, -1, 0);
                click.move();
                toastLayer.invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 1, 0);
                click.move();
                toastLayer.invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 0, -1);
                click.move();
                toastLayer.invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 0, 1);
                click.move();
                toastLayer.invalidate();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                executePairingModalAction(pairingModalFocus);
                break;
            case KeyEvent.KEYCODE_BACK:
                executePairingModalAction(3);
                break;
            default:
                break;
        }
    }

    private void handlePairingModalTap(float x, float y) {
        int hit = modalRenderer.hitTestPairingModal(x, y);
        if (hit == -2) {
            executePairingModalAction(3);
        } else if (hit >= 0 && hit < ModalOverlayRenderer.PAIRING_ACTION_COUNT) {
            pairingModalFocus = hit;
            executePairingModalAction(hit);
        }
    }

    private long lgModalOpenedAt = 0L;
    private boolean lgModalOkDown = false;
    private boolean lgModalBackDown = false;

    private void initLiquidGlassHiddenEdit(Activity a) {
        try {
            lgHiddenEdit = new EditText(a);
            lgHiddenEdit.setAlpha(0f);
            lgHiddenEdit.setBackground(null);
            lgHiddenEdit.setSingleLine(true);
            lgHiddenEdit.setFocusable(true);
            lgHiddenEdit.setFocusableInTouchMode(true);
            lgHiddenEdit.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            lgHiddenEdit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            lgHiddenEdit.setVisibility(GONE);
            lgHiddenEdit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (lgSyncingEdit || !lgModalOpen || !lgModalIsInput) return;
                    lgInputState.setText(s != null ? s.toString() : "");
                    toastLayer.invalidate();
                    wake();
                }
            });
            lgHiddenEdit.setOnEditorActionListener((v, actionId, event) -> {
                if (!lgModalOpen || !lgModalIsInput) return false;
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_NEXT) {
                    hideLiquidGlassSoftInput();
                    lgInputFocusSlot = lgInputConfirmSlot();
                    toastLayer.invalidate();
                    wake();
                    return true;
                }
                return false;
            });
            addView(lgHiddenEdit);
        } catch (Throwable ignored) {
            lgHiddenEdit = null;
        }
        try {
            settingsSearchEdit = new EditText(a);
            settingsSearchEdit.setAlpha(0f);
            settingsSearchEdit.setBackground(null);
            settingsSearchEdit.setSingleLine(true);
            settingsSearchEdit.setFocusable(true);
            settingsSearchEdit.setFocusableInTouchMode(true);
            settingsSearchEdit.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            settingsSearchEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            settingsSearchEdit.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            settingsSearchEdit.setVisibility(GONE);
            settingsSearchEdit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (settingsSearchSyncingEdit || activeScreen != SCREEN_SETTINGS || !isCurrentSettingsColumnSearchable()) {
                        return;
                    }
                    String next = s != null ? s.toString() : "";
                    if (!next.equals(settingsSearchQuery)) {
                        settingsSearchQuery = next;
                        settingsSearchClearFocused = false;
                        invalidateSettingsSearchCache();
                        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                        settingsDepthIndices[depth] = 0;
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    }
                }
            });
            settingsSearchEdit.setOnEditorActionListener((v, actionId, event) -> {
                if (activeScreen != SCREEN_SETTINGS || !isCurrentSettingsColumnSearchable()) return false;
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_NEXT) {
                    hideSettingsSearchSoftInput();
                    List<SettingsTree.Node> matches = currentSettingsDisplayLeftList(currentSettingsLeftParent());
                    if (!matches.isEmpty()) {
                        settingsSearchBarFocused = false;
                        settingsSearchClearFocused = false;
                        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                        settingsDepthIndices[depth] = SettingsScreenRenderer.firstSelectableIndex(matches);
                    }
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    return true;
                }
                return false;
            });
            addView(settingsSearchEdit);
        } catch (Throwable ignored) {
            settingsSearchEdit = null;
        }
    }

    private void syncSettingsSearchEditFromQuery() {
        if (settingsSearchEdit == null) return;
        settingsSearchSyncingEdit = true;
        try {
            String val = settingsSearchQuery != null ? settingsSearchQuery : "";
            settingsSearchEdit.setText(val);
            settingsSearchEdit.setSelection(val.length());
        } catch (Throwable ignored) {
        } finally {
            settingsSearchSyncingEdit = false;
        }
    }

    private void showSettingsSearchSoftInput() {
        if (settingsSearchEdit == null) return;
        try {
            settingsSearchEdit.setVisibility(VISIBLE);
            syncSettingsSearchEditFromQuery();
            settingsSearchEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(settingsSearchEdit, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideSettingsSearchSoftInput() {
        if (settingsSearchEdit == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(settingsSearchEdit.getWindowToken(), 0);
            }
            settingsSearchEdit.clearFocus();
            settingsSearchEdit.setVisibility(GONE);
            requestFocus();
        } catch (Throwable ignored) {
        }
    }

    private void invalidateSettingsSearchCache() {
        cachedSettingsFilterParentId = null;
        cachedSettingsFilterQuery = null;
        cachedSettingsFilterSource = null;
        cachedSettingsFilteredList = null;
    }

    private void clearSettingsSearchState(boolean hideIme) {
        settingsSearchQuery = "";
        settingsSearchBarFocused = false;
        settingsSearchClearFocused = false;
        invalidateSettingsSearchCache();
        syncSettingsSearchEditFromQuery();
        if (hideIme) {
            hideSettingsSearchSoftInput();
        }
    }

    private boolean isSettingsColumnSearchable(SettingsTree.Node leftParent) {
        return SettingsScreenRenderer.isLastColumnFullWidth(settingsPathIds, leftParent)
                && SettingsScreenRenderer.isSearchableColumn(leftParent);
    }

    private boolean isCurrentSettingsColumnSearchable() {
        if (settingsRoot == null) return false;
        return isSettingsColumnSearchable(currentSettingsLeftParent());
    }

    private List<SettingsTree.Node> currentSettingsDisplayLeftList(SettingsTree.Node leftParent) {
        if (leftParent == null || leftParent.children == null || leftParent.children.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        if (!isSettingsColumnSearchable(leftParent) || settingsSearchQuery == null || settingsSearchQuery.trim().isEmpty()) {
            return leftParent.children;
        }
        String parentId = leftParent.id;
        if (cachedSettingsFilteredList != null
                && parentId.equals(cachedSettingsFilterParentId)
                && settingsSearchQuery.equals(cachedSettingsFilterQuery)
                && cachedSettingsFilterSource == leftParent.children) {
            return cachedSettingsFilteredList;
        }
        List<SettingsTree.Node> filtered = SettingsScreenRenderer.filterNodesForSearch(
                leftParent, leftParent.children, settingsSearchQuery);
        cachedSettingsFilterParentId = parentId;
        cachedSettingsFilterQuery = settingsSearchQuery;
        cachedSettingsFilterSource = leftParent.children;
        cachedSettingsFilteredList = filtered;
        return filtered;
    }

    private void updateSettingsSearchQuery(String nextRaw, boolean jumpToFirstResult) {
        String safe = nextRaw != null ? nextRaw : "";
        settingsSearchQuery = safe;
        settingsSearchClearFocused = false;
        invalidateSettingsSearchCache();
        syncSettingsSearchEditFromQuery();
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        List<SettingsTree.Node> matches = currentSettingsDisplayLeftList(currentSettingsLeftParent());
        settingsDepthIndices[depth] = matches.isEmpty() ? 0 : SettingsScreenRenderer.firstSelectableIndex(matches);
        if (jumpToFirstResult && !matches.isEmpty()) {
            hideSettingsSearchSoftInput();
            settingsSearchBarFocused = false;
            zone = Z_CARDS;
        }
        heroDirty = true;
        heroLayer.invalidate();
        wake();
    }

    private void syncHiddenEditFromInputState() {
        if (lgHiddenEdit == null) return;
        lgSyncingEdit = true;
        try {
            String val = lgInputState.getText();
            lgHiddenEdit.setText(val);
            lgHiddenEdit.setSelection(val.length());
        } catch (Throwable ignored) {
        } finally {
            lgSyncingEdit = false;
        }
    }

    private void showLiquidGlassSoftInput() {
        if (lgHiddenEdit == null) return;
        try {
            lgHiddenEdit.setVisibility(VISIBLE);
            lgHiddenEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(lgHiddenEdit, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideLiquidGlassSoftInput() {
        if (lgHiddenEdit == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(lgHiddenEdit.getWindowToken(), 0);
            }
            lgHiddenEdit.clearFocus();
            lgHiddenEdit.setVisibility(GONE);
            requestFocus();
        } catch (Throwable ignored) {
        }
    }

    private int lgInputConfirmSlot() {
        return lgInputState.isSecretMode() ? 2 : 1;
    }

    private int lgInputCancelSlot() {
        return lgInputState.isSecretMode() ? 3 : 2;
    }

    void openLiquidGlassChoiceModal(
            String title,
            String subtitle,
            String[] labels,
            int[] swatchColorsOrNull,
            String cancelLabel,
            ChoiceSelectedListener onSelect,
            Runnable onCancel
    ) {
        openLiquidGlassChoiceModal(title, subtitle, labels, swatchColorsOrNull, 0, cancelLabel, onSelect, onCancel);
    }

    void openLiquidGlassChoiceModal(
            String title,
            String subtitle,
            String[] labels,
            int[] swatchColorsOrNull,
            int initialIndex,
            String cancelLabel,
            ChoiceSelectedListener onSelect,
            Runnable onCancel
    ) {
        hideLiquidGlassSoftInput();
        lgModalOpen = true;
        lgModalIsInput = false;
        lgModalTitle = title != null ? title : "";
        lgModalSubtitle = subtitle != null ? subtitle : "";
        lgChoiceLabels = labels != null ? labels : new String[0];
        lgChoiceSwatches = swatchColorsOrNull;
        int maxIdx = Math.max(0, lgChoiceLabels.length);
        lgChoiceFocus = lgChoiceLabels.length > 0
                ? Math.max(0, Math.min(lgChoiceLabels.length - 1, initialIndex))
                : maxIdx;
        lgChoiceFirstRow = Math.max(0, lgChoiceFocus - 5);
        lgModalCancelLabel = (cancelLabel != null && !cancelLabel.isEmpty()) ? cancelLabel : Ui.tr("Cancel");
        lgChoiceCallback = onSelect;
        lgInputCallback = null;
        lgCancelCallback = onCancel;
        lgModalOpenedAt = SystemClock.uptimeMillis();
        lgModalOkDown = false;
        lgModalBackDown = false;
        okDown = okHeld = backDown = false;
        toastLayer.invalidate();
        wake();
    }

    void openLiquidGlassInputModal(
            String title,
            String subtitle,
            String hint,
            String initialText,
            boolean pinMaskMode,
            String confirmLabel,
            TextConfirmedListener onConfirm,
            Runnable onCancel
    ) {
        lgModalOpen = true;
        lgModalIsInput = true;
        lgModalTitle = title != null ? title : "";
        lgModalSubtitle = subtitle != null ? subtitle : "";
        lgModalHint = hint != null ? hint : "";
        lgInputState = new LiquidGlassInputState(initialText, pinMaskMode);
        lgModalConfirmLabel = (confirmLabel != null && !confirmLabel.isEmpty()) ? confirmLabel : Ui.tr("Confirm");
        lgModalCancelLabel = Ui.tr("Cancel");
        lgChoiceCallback = null;
        lgInputCallback = onConfirm;
        lgCancelCallback = onCancel;
        lgInputFocusSlot = (initialText != null && !initialText.isEmpty()) ? lgInputConfirmSlot() : 0;
        lgModalOpenedAt = SystemClock.uptimeMillis();
        lgModalOkDown = false;
        lgModalBackDown = false;
        okDown = okHeld = backDown = false;
        if (lgHiddenEdit != null) {
            lgHiddenEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            lgHiddenEdit.setVisibility(VISIBLE);
            syncHiddenEditFromInputState();
        }
        toastLayer.invalidate();
        wake();
    }

    void closeLiquidGlassModal(boolean invokeCancel) {
        if (!lgModalOpen) return;
        Runnable cancelCb = lgCancelCallback;
        lgModalOpen = false;
        lgModalIsInput = false;
        lgChoiceCallback = null;
        lgInputCallback = null;
        lgCancelCallback = null;
        lgModalOkDown = false;
        lgModalBackDown = false;
        hideLiquidGlassSoftInput();
        toastLayer.invalidate();
        wake();
        if (invokeCancel) {
            click.move();
            if (cancelCb != null) cancelCb.run();
        }
    }

    private void confirmLiquidGlassInputModal() {
        if (!lgModalOpen || !lgModalIsInput) return;
        TextConfirmedListener confirmCb = lgInputCallback;
        String val = lgInputState.getText();
        closeLiquidGlassModal(false);
        click.open();
        if (confirmCb != null) {
            confirmCb.onTextConfirmed(val);
        }
    }

    private void activateLiquidGlassModalFocused() {
        if (!lgModalOpen) return;
        if (!lgModalIsInput) {
            if (lgChoiceFocus >= 0 && lgChoiceFocus < lgChoiceLabels.length) {
                ChoiceSelectedListener cb = lgChoiceCallback;
                int picked = lgChoiceFocus;
                closeLiquidGlassModal(false);
                click.open();
                if (cb != null) cb.onChoiceSelected(picked);
            } else {
                closeLiquidGlassModal(true);
            }
            return;
        }
        if (lgInputFocusSlot == 0) {
            click.open();
            showLiquidGlassSoftInput();
        } else if (lgInputState.isSecretMode() && lgInputFocusSlot == 1) {
            lgInputState.toggleReveal();
            click.move();
            toastLayer.invalidate();
            wake();
        } else if (lgInputFocusSlot == lgInputConfirmSlot()) {
            confirmLiquidGlassInputModal();
        } else {
            closeLiquidGlassModal(true);
        }
    }

    private void moveLiquidGlassModalFocus(int dx, int dy) {
        if (!lgModalOpen) return;
        if (!lgModalIsInput) {
            if (dy == 0) return;
            int total = lgChoiceLabels.length + 1;
            int next = Math.max(0, Math.min(total - 1, lgChoiceFocus + dy));
            if (next != lgChoiceFocus) {
                lgChoiceFocus = next;
                int maxVis = Math.min(6, Math.max(1, lgChoiceLabels.length));
                if (lgChoiceFocus < lgChoiceLabels.length) {
                    if (lgChoiceFocus < lgChoiceFirstRow) lgChoiceFirstRow = lgChoiceFocus;
                    else if (lgChoiceFocus >= lgChoiceFirstRow + maxVis) lgChoiceFirstRow = lgChoiceFocus - maxVis + 1;
                }
                click.move();
                toastLayer.invalidate();
                wake();
            }
            return;
        }
        int delta = dy != 0 ? dy : dx;
        if (delta == 0) return;
        int maxSlot = lgInputCancelSlot();
        int nextSlot = Math.max(0, Math.min(maxSlot, lgInputFocusSlot + (delta > 0 ? 1 : -1)));
        if (nextSlot != lgInputFocusSlot) {
            if (nextSlot != 0) hideLiquidGlassSoftInput();
            lgInputFocusSlot = nextSlot;
            click.move();
            toastLayer.invalidate();
            wake();
        }
    }

    private void handleLiquidGlassModalRemoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: moveLiquidGlassModalFocus(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveLiquidGlassModalFocus(0, 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: moveLiquidGlassModalFocus(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveLiquidGlassModalFocus(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activateLiquidGlassModalFocused();
                break;
            case KeyEvent.KEYCODE_DEL:
                if (lgModalIsInput) {
                    lgInputState.backspace();
                    syncHiddenEditFromInputState();
                    toastLayer.invalidate();
                    wake();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                closeLiquidGlassModal(true);
                break;
            default:
                break;
        }
    }

    private boolean handleLiquidGlassModalKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && SystemClock.uptimeMillis() - lgModalOpenedAt < 180L) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveLiquidGlassModalFocus(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveLiquidGlassModalFocus(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveLiquidGlassModalFocus(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveLiquidGlassModalFocus(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) lgModalOkDown = true;
                } else {
                    if (lgModalOkDown && !e.isCanceled()) activateLiquidGlassModalFocused();
                    lgModalOkDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (e.getRepeatCount() == 0) lgModalBackDown = true;
                } else {
                    if (lgModalBackDown && !e.isCanceled()) closeLiquidGlassModal(true);
                    lgModalBackDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (down && lgModalIsInput) {
                    lgInputState.backspace();
                    syncHiddenEditFromInputState();
                    toastLayer.invalidate();
                    wake();
                }
                return true;
            default:
                if (down && lgModalIsInput) {
                    int uch = e.getUnicodeChar();
                    if (uch >= 32 && uch != 127) {
                        lgInputState.appendChar((char) uch);
                        syncHiddenEditFromInputState();
                        toastLayer.invalidate();
                        wake();
                        return true;
                    }
                }
                return true;
        }
    }

    private void handleLiquidGlassModalTap(float x, float y) {
        if (!lgModalOpen) return;
        int hit = modalRenderer.hitTestLiquidGlassModal(
                x, y, lgModalIsInput, lgChoiceLabels.length, lgChoiceFirstRow, lgInputState.isSecretMode());
        if (hit == ModalOverlayRenderer.LG_HIT_OUTSIDE || hit == ModalOverlayRenderer.LG_HIT_CANCEL) {
            closeLiquidGlassModal(true);
        } else if (hit == ModalOverlayRenderer.LG_HIT_INPUT_BOX) {
            lgInputFocusSlot = 0;
            showLiquidGlassSoftInput();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.LG_HIT_REVEAL) {
            lgInputFocusSlot = 1;
            lgInputState.toggleReveal();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.LG_HIT_CONFIRM) {
            confirmLiquidGlassInputModal();
        } else if (hit >= 0 && !lgModalIsInput) {
            lgChoiceFocus = hit;
            activateLiquidGlassModalFocused();
        }
    }

    private void drawLiquidGlassModal(Canvas c) {
        modalRenderer.drawLiquidGlassModal(
                getContext(), c, w, h, k,
                lgModalIsInput, lgModalTitle, lgModalSubtitle, lgModalHint,
                lgModalConfirmLabel, lgModalCancelLabel,
                lgChoiceLabels, lgChoiceSwatches, lgChoiceFocus, lgChoiceFirstRow,
                lgInputState, lgInputFocusSlot, lgInputConfirmSlot(), lgInputCancelSlot(),
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint);
    }

    private void openSleepTimerModal() {
        int currentMins = SleepTimer.minutesLeft(getContext().getApplicationContext());
        sleepModalState = new ModalOverlayRenderer.SleepTimerModalState(currentMins);
        sleepModalOpen = true;
        sleepModalOpenedAt = SystemClock.uptimeMillis();
        okDown = okHeld = backDown = false;
        toastLayer.invalidate();
        wake();
        click.open();
    }

    private void closeSleepTimerModal() {
        if (!sleepModalOpen) return;
        sleepModalOpen = false;
        sleepModalState = null;
        okDown = okHeld = backDown = false;
        toastLayer.invalidate();
        wake();
        click.move();
    }

    private void saveSleepTimerModal() {
        if (!sleepModalOpen || sleepModalState == null) return;
        int mins = sleepModalState.getMinutes();
        Context c = getContext().getApplicationContext();
        SleepTimer.set(c, Math.max(0, mins));
        updateSleep();
        sleepModalOpen = false;
        sleepModalState = null;
        okDown = okHeld = backDown = false;
        rebuildSettingsTree();
        heroDirty = true;
        heroLayer.invalidate();
        toastLayer.invalidate();
        wake();
        click.open();
        if (mins > 0) {
            String atClock = SleepTimer.formatSleepAtClock(mins, System.currentTimeMillis(), Ui.h24());
            showToast(Ui.tr("Sleep Timer: ") + SleepTimer.formatDurationLabel(mins)
                    + (atClock.isEmpty() ? "" : (" (" + Ui.tr("sleeps at ") + atClock + ")")));
        } else {
            showToast(Ui.tr("Sleep Timer: Off"));
        }
    }

    private boolean handleSleepTimerModalKey(KeyEvent e) {
        if (!sleepModalOpen || sleepModalState == null) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && SystemClock.uptimeMillis() - sleepModalOpenedAt < 180L) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    sleepModalState.moveFocus(0, -1);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    sleepModalState.moveFocus(0, 1);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    sleepModalState.moveFocus(-1, 0);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    sleepModalState.moveFocus(1, 0);
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && !e.isCanceled()) {
                    int slot = sleepModalState.getFocusSlot();
                    if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_TOGGLE) {
                        sleepModalState.toggleEnabled();
                        click.move();
                        toastLayer.invalidate();
                    } else if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_ADD_FIVE) {
                        sleepModalState.addFiveMinutes();
                        click.move();
                        toastLayer.invalidate();
                    } else if (slot == ModalOverlayRenderer.SleepTimerModalState.SLOT_CANCEL) {
                        closeSleepTimerModal();
                    } else {
                        saveSleepTimerModal();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down && !e.isCanceled()) {
                    closeSleepTimerModal();
                }
                return true;
            default:
                return true;
        }
    }

    private void handleSleepTimerModalTap(float x, float y) {
        if (!sleepModalOpen || sleepModalState == null) return;
        int hit = modalRenderer.hitTestSleepTimerModal(x, y);
        if (hit == ModalOverlayRenderer.SLEEP_HIT_OUTSIDE || hit == ModalOverlayRenderer.SLEEP_HIT_CANCEL) {
            closeSleepTimerModal();
        } else if (hit == ModalOverlayRenderer.SLEEP_HIT_TOGGLE) {
            sleepModalState.setFocusSlot(ModalOverlayRenderer.SleepTimerModalState.SLOT_TOGGLE);
            sleepModalState.toggleEnabled();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SLEEP_HIT_UP) {
            sleepModalState.setFocusSlot(ModalOverlayRenderer.SleepTimerModalState.SLOT_DURATION);
            sleepModalState.stepUp();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SLEEP_HIT_DOWN) {
            sleepModalState.setFocusSlot(ModalOverlayRenderer.SleepTimerModalState.SLOT_DURATION);
            sleepModalState.stepDown();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SLEEP_HIT_ADD_FIVE) {
            sleepModalState.setFocusSlot(ModalOverlayRenderer.SleepTimerModalState.SLOT_ADD_FIVE);
            sleepModalState.addFiveMinutes();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SLEEP_HIT_SAVE) {
            saveSleepTimerModal();
        }
    }

    private void drawSleepTimerModal(Canvas c) {
        modalRenderer.drawSleepTimerModal(
                getContext(), c, w, h, k,
                sleepModalState, System.currentTimeMillis(), Ui.h24(),
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint);
    }

    private void openSlideDurationModal() {
        slideDurationModalState = new ModalOverlayRenderer.SlideDurationModalState(Prefs.saverIntervalMs());
        slideDurationModalOpen = true;
        slideDurationModalDirty = false;
        slideDurationModalOpenedAt = SystemClock.uptimeMillis();
        lastGamepadAxisDx = 0;
        lastGamepadAxisDy = 0;
        okDown = okHeld = backDown = false;
        toastLayer.invalidate();
        wake();
        click.open();
    }

    private void persistSlideDurationModalValue() {
        if (slideDurationModalState == null) return;
        Prefs.setSaverIntervalMs(slideDurationModalState.getIntervalMs());
        slideDurationModalDirty = true;
        rebuildSettingsTree();
        heroDirty = true;
        heroLayer.invalidate();
    }

    private void closeSlideDurationModal() {
        if (!slideDurationModalOpen) return;
        ModalOverlayRenderer.SlideDurationModalState closingState = slideDurationModalState;
        boolean changed = slideDurationModalDirty;
        if (closingState != null && changed) {
            Prefs.setSaverIntervalMs(closingState.getIntervalMs());
        }
        slideDurationModalOpen = false;
        slideDurationModalDirty = false;
        slideDurationModalState = null;
        lastGamepadAxisDx = 0;
        lastGamepadAxisDy = 0;
        okDown = okHeld = backDown = false;
        if (changed) {
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
        }
        toastLayer.invalidate();
        wake();
        click.move();
        if (changed && closingState != null) {
            showToast(Ui.tr("Slide Duration: ") + Ui.tr(closingState.getLabel()));
        }
    }

    private boolean handleSlideDurationModalKey(KeyEvent e) {
        if (!slideDurationModalOpen || slideDurationModalState == null) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && SystemClock.uptimeMillis() - slideDurationModalOpenedAt < 180L) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    slideDurationModalState.stepUp();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    slideDurationModalState.stepDown();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    slideDurationModalState.stepUp();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    slideDurationModalState.stepDown();
                    persistSlideDurationModalValue();
                    click.move();
                    toastLayer.invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (!down && !e.isCanceled()) {
                    closeSlideDurationModal();
                }
                return true;
            default:
                return true;
        }
    }

    private void handleSlideDurationModalTap(float x, float y) {
        if (!slideDurationModalOpen || slideDurationModalState == null) return;
        int hit = modalRenderer.hitTestSlideDurationModal(x, y);
        if (hit == ModalOverlayRenderer.SD_HIT_OUTSIDE) {
            closeSlideDurationModal();
        } else if (hit == ModalOverlayRenderer.SD_HIT_UP) {
            slideDurationModalState.stepUp();
            persistSlideDurationModalValue();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SD_HIT_DOWN) {
            slideDurationModalState.stepDown();
            persistSlideDurationModalValue();
            click.move();
            toastLayer.invalidate();
        }
    }

    private void drawSlideDurationModal(Canvas c) {
        modalRenderer.drawSlideDurationModal(
                getContext(), c, w, h, k,
                slideDurationModalState,
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint);
    }

    private void openStreamQualityModal() {
        SettingsTree.StateSnapshot snap = SettingsTree.captureState(getContext(), data);
        streamQualityModalState = new ModalOverlayRenderer.StreamQualityModalState(
                snap.gamingResolution,
                snap.gamingFps,
                snap.gamingBitrateMbps,
                snap.tvDisplayWidth,
                snap.tvDisplayHeight,
                snap.tvRefreshRateHz,
                snap.pcDisplayWidth,
                snap.pcDisplayHeight,
                snap.pcDisplayRefreshHz,
                snap.networkType,
                snap.wifiLinkSpeedMbps);
        streamQualityModalOpen = true;
        streamQualityModalDirty = false;
        streamQualityModalOpenedAt = SystemClock.uptimeMillis();
        lastGamepadAxisDx = 0;
        lastGamepadAxisDy = 0;
        okDown = okHeld = backDown = false;
        toastLayer.invalidate();
        wake();
        click.open();
    }

    private void persistStreamQualityModalValues() {
        if (streamQualityModalState == null) return;
        Prefs.setGamingResolution(streamQualityModalState.getResolution());
        Prefs.setGamingFps(streamQualityModalState.getFps());
        Prefs.setGamingBitrateMbps(streamQualityModalState.getBitrateMbps());
        streamQualityModalDirty = true;
    }

    private void closeStreamQualityModal() {
        if (!streamQualityModalOpen) return;
        ModalOverlayRenderer.StreamQualityModalState closingState = streamQualityModalState;
        boolean changed = streamQualityModalDirty;
        if (closingState != null && changed) {
            Prefs.setGamingResolution(closingState.getResolution());
            Prefs.setGamingFps(closingState.getFps());
            Prefs.setGamingBitrateMbps(closingState.getBitrateMbps());
        }
        streamQualityModalOpen = false;
        streamQualityModalDirty = false;
        streamQualityModalState = null;
        lastGamepadAxisDx = 0;
        lastGamepadAxisDy = 0;
        okDown = okHeld = backDown = false;
        if (changed) {
            rebuildScreenRows();
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
        }
        toastLayer.invalidate();
        wake();
        click.move();
        if (changed && closingState != null) {
            showToast(Ui.tr("Stream Quality: ")
                    + ModalOverlayRenderer.StreamQualityModalState.formatCompactSummary(
                            closingState.getResolution(),
                            closingState.getFps(),
                            closingState.getBitrateMbps()));
        }
    }

    private boolean handleStreamQualityModalKey(KeyEvent e) {
        if (!streamQualityModalOpen || streamQualityModalState == null) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && SystemClock.uptimeMillis() - streamQualityModalOpenedAt < 180L) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (streamQualityModalState.moveFocus(0, -1)) {
                        persistStreamQualityModalValues();
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (streamQualityModalState.moveFocus(0, 1)) {
                        persistStreamQualityModalValues();
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    if (streamQualityModalState.moveFocus(-1, 0)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    if (streamQualityModalState.moveFocus(1, 0)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (down) {
                    if (streamQualityModalState.switchColumn(-1)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) {
                    if (streamQualityModalState.switchColumn(1)) {
                        click.move();
                        toastLayer.invalidate();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (!down && !e.isCanceled()) {
                    closeStreamQualityModal();
                }
                return true;
            default:
                return true;
        }
    }

    private void handleStreamQualityModalTap(float x, float y) {
        if (!streamQualityModalOpen || streamQualityModalState == null) return;
        int hit = modalRenderer.hitTestStreamQualityModal(x, y);
        if (hit == ModalOverlayRenderer.SQ_HIT_OUTSIDE) {
            closeStreamQualityModal();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_RES_CARD) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_RES);
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_FPS_CARD) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_FPS);
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_BIT_CARD) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_BITRATE);
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_RES_UP) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_RES);
            if (streamQualityModalState.stepColumn(0, 1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_RES_DOWN) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_RES);
            if (streamQualityModalState.stepColumn(0, -1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_FPS_UP) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_FPS);
            if (streamQualityModalState.stepColumn(1, 1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_FPS_DOWN) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_FPS);
            if (streamQualityModalState.stepColumn(1, -1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_BIT_UP) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_BITRATE);
            if (streamQualityModalState.stepColumn(2, 1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        } else if (hit == ModalOverlayRenderer.SQ_HIT_BIT_DOWN) {
            streamQualityModalState.setFocusSlot(ModalOverlayRenderer.StreamQualityModalState.SLOT_BITRATE);
            if (streamQualityModalState.stepColumn(2, -1)) persistStreamQualityModalValues();
            click.move();
            toastLayer.invalidate();
        }
    }

    private void drawStreamQualityModal(Canvas c) {
        modalRenderer.drawStreamQualityModal(
                getContext(), c, w, h, k,
                streamQualityModalState,
                scene, tone(glassLight(), SMOKE), tone(0.84f, 0.74f),
                dark() > 0.5f, L_INK, L_DIM, WHITE,
                fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint);
    }

    /**
     * Routes gaming controller analog stick ({@code AXIS_X}/{@code AXIS_Y}) and digital hat
     * ({@code AXIS_HAT_X}/{@code AXIS_HAT_Y}) motion events into active Liquid Glass modals.
     */
    boolean onGamepadMotion(MotionEvent e) {
        if (e == null || e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        int src = e.getSource();
        boolean isController = (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                || (src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD;
        if (!isController) return false;
        if (!streamQualityModalOpen && !sleepModalOpen && !slideDurationModalOpen) return false;

        int dx = ModalOverlayRenderer.resolveGamepadAxisDirection(
                e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_HAT_X));
        int dy = ModalOverlayRenderer.resolveGamepadAxisDirection(
                e.getAxisValue(MotionEvent.AXIS_Y), e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        // Prioritize stronger axis when moved diagonally so values don't jump sideways unintentionally
        if (dx != 0 && dy != 0) {
            float absX = Math.max(Math.abs(e.getAxisValue(MotionEvent.AXIS_X)), Math.abs(e.getAxisValue(MotionEvent.AXIS_HAT_X)));
            float absY = Math.max(Math.abs(e.getAxisValue(MotionEvent.AXIS_Y)), Math.abs(e.getAxisValue(MotionEvent.AXIS_HAT_Y)));
            if (absX >= absY) dy = 0;
            else dx = 0;
        }
        if (dx == 0 && dy == 0) {
            lastGamepadAxisDx = 0;
            lastGamepadAxisDy = 0;
            return true;
        }
        long now = SystemClock.uptimeMillis();
        boolean sameDir = (dx == lastGamepadAxisDx && dy == lastGamepadAxisDy);
        if (sameDir && (now - lastGamepadAxisMoveAt) < 180L) {
            return true;
        }
        lastGamepadAxisDx = dx;
        lastGamepadAxisDy = dy;
        lastGamepadAxisMoveAt = now;
        Recs.notifyUserInteraction();
        wake();
        if (streamQualityModalOpen && streamQualityModalState != null) {
            if (streamQualityModalState.moveFocus(dx, dy)) {
                if (dy != 0) {
                    persistStreamQualityModalValues();
                }
                click.move();
                toastLayer.invalidate();
            }
            return true;
        }
        if (slideDurationModalOpen && slideDurationModalState != null) {
            if (slideDurationModalState.moveFocus(dx, dy)) {
                persistSlideDurationModalValue();
                click.move();
                toastLayer.invalidate();
            }
            return true;
        }
        if (sleepModalOpen && sleepModalState != null) {
            sleepModalState.moveFocus(dx, dy);
            click.move();
            toastLayer.invalidate();
            return true;
        }
        return false;
    }

    private void drawToast(Canvas c) {
        if (lgModalOpen) {
            drawLiquidGlassModal(c);
            return;
        }
        if (streamQualityModalOpen && streamQualityModalState != null) {
            drawStreamQualityModal(c);
            return;
        }
        if (slideDurationModalOpen && slideDurationModalState != null) {
            drawSlideDurationModal(c);
            return;
        }
        if (sleepModalOpen && sleepModalState != null) {
            drawSleepTimerModal(c);
            return;
        }
        if (activePendingPairing != null) {
            drawPairingModal(c);
            return;
        }
        if (gameLaunchModalOpen) {
            drawGameLaunchModal(c);
            return;
        }
        if (syncModalOpen) {
            drawSyncModal(c);
            return;
        }
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        // Centered, unless that covers the title (a long one, such as Continue Watching): then just right of it.
        float left = Math.max((w - tw) / 2f - padX, Math.min(titleRight + 40 * k, w - tw - 2 * padX - MARGIN * k));
        tmp.set(left, toastTop, left + tw + 2 * padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        final int orderAtLoad = orderRevision;
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons(c) : null;   // they change with Prefs too
            // Read Continue Watching when shown or when in Picks mode (for the Continue Watching tab).
            final String mode = Prefs.topContent();
            final boolean allowed = WatchNext.allowed(c);
            final List<WatchNext.Item> w = (mode.equals(Prefs.TOP_CONTINUE) || mode.equals(Prefs.TOP_PICKS))
                    ? WatchNext.load(c) : new ArrayList<>();
            handler.post(() -> {
                // A provider/package refresh must not cancel a drag or undo a move that was just saved.
                boolean keepOrder = reordering || pendingOrderSaves > 0 || orderAtLoad != orderRevision;
                if (top != null && !keepOrder) setLightButtons(top);
                setTopContent(mode, w, allowed);
                if (s != null && !keepOrder) setShelf(s);
                if (apps && keepOrder) appsStale = true;
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;
                rebuildAllAppsList();
                rebuildScreenRows();
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    /** A mouse (or a touch screen) clicks what is under it, like OK on the focused item. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = e.getX();
                touchY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.hypot(e.getX() - touchX, e.getY() - touchY) < 24 * k) tap(e.getX(), e.getY());
                return true;
            default:
                return true;
        }
    }

    /** A mouse moves the focus to what is under the pointer, like a trackpad on tvOS. */
    @Override public boolean onHoverEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) hover(e.getX(), e.getY());
        return true;
    }

    /** The mouse wheel moves through the shelf, the cards, the drawer and All Apps. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (onGamepadMotion(e)) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v != 0) {
                wheel(v < 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    private int hovered = -1;
    private static final int H_MENU = 7, H_GRID = 8;

    private boolean entered(int zone, int index) {
        int code = index < 0 ? -1 : zone * 1000 + index;
        if (code == hovered) return false;
        hovered = code;
        return code >= 0;
    }

    private void hover(float x, float y) {
        wake();
        if (reordering) return;
        if (menuOpen) {
            int row = menuAt(x, y);
            if (entered(H_MENU, row)) moveMenu(row - menuIndex);
            return;
        }
        int hit = topAt(x, y);
        if (hit >= 0 && entered(Z_TOP, hit)) {
            zone = Z_TOP;
            topIndex = hit;
            selectScreen(hit);
            retarget(false);
        }
    }

    private void wheel(int dir) {
        Recs.notifyUserInteraction();
        wake();
        if (lgModalOpen) {
            moveLiquidGlassModalFocus(0, dir);
            return;
        }
        if (streamQualityModalOpen && streamQualityModalState != null) {
            if (streamQualityModalState.moveFocus(0, dir)) {
                click.move();
                toastLayer.invalidate();
            }
            return;
        }
        if (slideDurationModalOpen && slideDurationModalState != null) {
            if (slideDurationModalState.moveFocus(0, dir)) {
                persistSlideDurationModalValue();
                click.move();
                toastLayer.invalidate();
            }
            return;
        }
        if (sleepModalOpen && sleepModalState != null) {
            sleepModalState.moveFocus(0, dir);
            click.move();
            toastLayer.invalidate();
            return;
        }
        if (activePendingPairing != null) {
            pairingModalFocus = HomeInputRouter.movePairingModalFocus(pairingModalFocus, 0, dir);
            click.move();
            toastLayer.invalidate();
            return;
        }
        if (reordering) return;
        if (menuOpen) {
            moveMenu(dir);
        } else if (sideShelfOpen && zone == Z_SHELF) {
            int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dir));
            if (next != shelfIndex) {
                shelfIndex = next;
                retarget(false);
            }
        } else if (activeScreen == SCREEN_APPS) {
            int next = Math.max(0, Math.min(allAppsList.size() - 1, gridIndex + dir * COLUMNS));
            if (next != gridIndex) {
                zone = Z_CARDS;
                gridIndex = next;
                retarget(false);
            }
        } else if (!browserRows.isEmpty()) {
            int next = Math.max(0, Math.min(browserRows.size() - 1, homeRow + dir));
            if (next != homeRow) {
                BrowserRow cur = browserRows.get(homeRow);
                if (cur.kind != ROW_MEDIA_SELECTOR) {
                    rowScrollMemory.save(rowMemoryKey(cur), homeCol, cur.scrollX);
                }
                zone = Z_CARDS;
                homeRow = next;
                BrowserRow r = browserRows.get(homeRow);
                if (r.kind == ROW_MEDIA_SELECTOR) {
                    homeCol = subTab;
                    r.scrollX = 0f;
                } else {
                    String key = rowMemoryKey(r);
                    homeCol = rowScrollMemory.restoreCol(key, r.count(), 0);
                    r.scrollX = rowScrollMemory.restoreScrollX(key, 0f);
                }
                retarget(false);
            }
        }
    }

    private void tap(float x, float y) {
        wake();
        if (lgModalOpen) {
            handleLiquidGlassModalTap(x, y);
            return;
        }
        if (streamQualityModalOpen) {
            handleStreamQualityModalTap(x, y);
            return;
        }
        if (slideDurationModalOpen) {
            handleSlideDurationModalTap(x, y);
            return;
        }
        if (sleepModalOpen) {
            handleSleepTimerModalTap(x, y);
            return;
        }
        if (activePendingPairing != null) {
            handlePairingModalTap(x, y);
            return;
        }
        if (gameLaunchModalOpen) {
            if (gameLaunchCancelRect.isEmpty() || gameLaunchCancelRect.contains(x, y)) {
                cancelGameLaunchModalByUser();
            }
            return;
        }
        if (syncModalOpen) {
            syncModalOpen = false;
            toastLayer.invalidate();
            click.move();
            showToast(Ui.tr("Continuing sync in background…"));
            return;
        }
        if (reordering) return;
        if (menuOpen) {
            int row = menuAt(x, y);
            if (row < 0) {
                closeMenu(false);
                click.move();
                return;
            }
            menuIndex = row;
            menuPick.target = row;
            pickMenu();
            return;
        }
        int hit = topAt(x, y);
        if (hit >= 0) {
            zone = Z_TOP;
            topIndex = hit;
            selectScreen(hit);
            retarget(false);
            activate();
            return;
        }
        if (sideShelfOpen) {
            hit = tileAt(x, y);
            if (hit >= 0) {
                zone = Z_SHELF;
                shelfIndex = hit;
                retarget(false);
                activate();
                return;
            }
        }
        if (activeScreen == SCREEN_SETTINGS && isCurrentSettingsColumnSearchable()) {
            int sHit = settingsRenderer.hitTestSettingsSearchBar(x, y);
            if (sHit == SettingsScreenRenderer.SEARCH_HIT_CLEAR) {
                zone = Z_CARDS;
                settingsSearchBarFocused = true;
                settingsSearchClearFocused = false;
                updateSettingsSearchQuery("", false);
                click.move();
                return;
            } else if (sHit == SettingsScreenRenderer.SEARCH_HIT_BAR) {
                zone = Z_CARDS;
                settingsSearchBarFocused = true;
                settingsSearchClearFocused = false;
                showSettingsSearchSoftInput();
                heroDirty = true;
                heroLayer.invalidate();
                click.open();
                return;
            }
        }
        activate();
    }

    /** The menu's row under a point, if any. */
    private int menuAt(float x, float y) {
        return modalRenderer.menuAt(x, y, menuOptionCount, k, menuPanel, menuOptionsTop, hitBox);
    }

    private int topAt(float x, float y) {
        for (int i = 0; i < TOP_BAR_COUNT; i++) {
            if (!topRects[i].isEmpty() && topRects[i].contains(x, y)) return i;
        }
        return -1;
    }

    private int cardAt(float x, float y, boolean still) {
        int hit = hit(cardRects, x, y);
        if (hit < 0 || hit >= cards.size()) return -1;
        return still && Math.max(0, hit - 2) != cardScroll.target ? -1 : hit;
    }

    private int watchAt(float x, float y, boolean still) {
        if (!continueMode()) return -1;
        for (int i = 0; i < watching.size(); i++) {
            watchRect(i, hitBox);
            if (hitBox.left > w || hitBox.right <= watchLeftEdge()) continue;
            if (!hitBox.contains(x, y)) continue;
            return still && Math.max(0, Math.min(i - 2, watching.size() - 3)) != watchScroll.target ? -1 : i;
        }
        return -1;
    }

    private int tileAt(float x, float y) {
        int last = Math.min(tileRects.length, shelfFirst + Math.min(shelf.size(), SIDE_SHELF_VISIBLE));
        for (int i = shelfFirst; i < last; i++) if (tileRects[i].contains(x, y)) return i;
        return -1;
    }

    private static int hit(RectF[] rects, float x, float y) {
        for (int i = 0; i < rects.length; i++) if (!rects[i].isEmpty() && rects[i].contains(x, y)) return i;
        return -1;
    }

    private int gridAt(float x, float y, boolean still) {
        List<HomeApps.Item> list = activeScreen == SCREEN_APPS ? allAppsList : others;
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k, top = GRID_TOP * k - scroll.value * GRID_ROW * k;
        float cw = GRID_W * k, ch = GRID_H * k, gap = GRID_GAP * k, row = GRID_ROW * k;
        for (int i = 0; i < list.size(); i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float x0 = left + col * (cw + gap), y0 = top + r * row;
            if (x >= x0 && x < x0 + cw && y >= y0 && y < y0 + ch) {
                if (still && (r < scroll.target || r > scroll.target + VISIBLE_ROWS - 1)) return -1;
                focusedCell.set(x0, y0, x0 + cw, y0 + ch);
                return i;
            }
        }
        return -1;
    }

    boolean onKey(KeyEvent e) {
        Recs.notifyUserInteraction();
        wake();
        if (lgModalOpen) {
            return handleLiquidGlassModalKey(e);
        }
        if (streamQualityModalOpen) {
            return handleStreamQualityModalKey(e);
        }
        if (slideDurationModalOpen) {
            return handleSlideDurationModalKey(e);
        }
        if (sleepModalOpen) {
            return handleSleepTimerModalKey(e);
        }
        if (activePendingPairing != null) {
            return handlePairingModalKey(e);
        }
        if (gameLaunchModalOpen) {
            int kCode = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                    && (kCode == KeyEvent.KEYCODE_DPAD_CENTER || kCode == KeyEvent.KEYCODE_ENTER
                    || kCode == KeyEvent.KEYCODE_NUMPAD_ENTER || kCode == KeyEvent.KEYCODE_BACK
                    || kCode == KeyEvent.KEYCODE_ESCAPE || kCode == KeyEvent.KEYCODE_BUTTON_B)) {
                cancelGameLaunchModalByUser();
            }
            return true;
        }
        if (syncModalOpen) {
            int kCode = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                    && (kCode == KeyEvent.KEYCODE_DPAD_CENTER || kCode == KeyEvent.KEYCODE_ENTER
                    || kCode == KeyEvent.KEYCODE_NUMPAD_ENTER || kCode == KeyEvent.KEYCODE_BACK)) {
                syncModalOpen = false;
                okDown = okHeld = backDown = false;
                toastLayer.invalidate();
                click.move();
                showToast(Ui.tr("Continuing sync in background…"));
            }
            return true;
        }
        if (RemoteKeys.checkDoubleVolDownMute(getContext(), e)) {
            try {
                android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    boolean muted = am.isStreamMute(android.media.AudioManager.STREAM_MUSIC);
                    showToast(muted ? Ui.tr("TV Audio Muted") : Ui.tr("TV Audio Unmuted"));
                }
            } catch (RuntimeException ignored) {
            }
            return true;
        }
        if (menuOpen) return menuKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (recents != null && recents.isOpen()) {
            int was = recents.focus();
            boolean handled = recents.key(e);
            recentLayer.invalidate();
            if (recents.focus() != was) spoken.focusMoved();
            if (!recents.visible() || recents.count() == 0) spoken.readAgain();
            return handled;
        }
        if (down && e.getKeyCode() == KeyEvent.KEYCODE_APP_SWITCH && !Prefs.recents().equals("off")) {
            openRecents();
            return true;
        }
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER && code != KeyEvent.KEYCODE_BUTTON_A) {
            holdArmed = false;
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : (canHold() || canReorder());
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                if (down && e.getRepeatCount() == 0) showSettingsScreen();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (activeScreen == SCREEN_SETTINGS && zone == Z_CARDS && isCurrentSettingsColumnSearchable()) {
                    if (down && settingsSearchQuery != null && !settingsSearchQuery.isEmpty()) {
                        settingsSearchBarFocused = true;
                        updateSettingsSearchQuery(
                                settingsSearchQuery.substring(0, settingsSearchQuery.length() - 1), false);
                        click.move();
                    }
                    return true;
                }
                return false;
            default:
                if (down && activeScreen == SCREEN_SETTINGS && zone == Z_CARDS && isCurrentSettingsColumnSearchable()) {
                    int uch = e.getUnicodeChar();
                    if (uch >= 32 && uch != 127) {
                        String cur = settingsSearchQuery != null ? settingsSearchQuery : "";
                        if (cur.length() < 64) {
                            settingsSearchBarFocused = true;
                            updateSettingsSearchQuery(cur + (char) uch, false);
                            click.move();
                        }
                        return true;
                    }
                }
                return false;
        }
    }

    private void back() {
        if (lgModalOpen) {
            closeLiquidGlassModal(true);
            return;
        }
        if (streamQualityModalOpen) {
            closeStreamQualityModal();
            return;
        }
        if (slideDurationModalOpen) {
            closeSlideDurationModal();
            return;
        }
        if (sleepModalOpen) {
            closeSleepTimerModal();
            return;
        }
        if (activePendingPairing != null) {
            executePairingModalAction(3);
            return;
        }
        if (sideShelfOpen) {
            closeSideShelf();
            click.move();
            return;
        }
        if (sheetOpen) {
            closeSheet();
            click.move();
            return;
        }
        if (activeScreen == SCREEN_SETTINGS && zone == Z_CARDS) {
            if (backSettings()) {
                return;
            }
        }
        if (activeScreen == SCREEN_APPS && zone == Z_CARDS && showHiddenAppsInline) {
            showHiddenAppsInline = false;
            rebuildAllAppsList();
            retargetAppsGrid(false);
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return;
        }
        if (activeScreen == SCREEN_GAMING && zone == Z_CARDS && showHiddenGamesInline) {
            showHiddenGamesInline = false;
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return;
        }
        if (zone == Z_TOP) {
            flushPendingTopBarScreenSwitch();
            zone = Z_CARDS;
            retarget(false);
            click.move();
            return;
        }
        if (!backStack.isEmpty()) {
            HomeInputRouter.ScreenCursorState prevState = backStack.popPreviousState(activeScreen, SCREEN_HOME);
            selectScreenInternal(prevState.screen, false);
            if (activeScreen == SCREEN_HOME && subTab != prevState.subTab) {
                subTab = Math.max(0, Math.min(1, prevState.subTab));
                recs.setActiveSubKind(subTab == 0 ? "tv" : "movie");
                rebuildScreenRows();
            }
            if (activeScreen == SCREEN_APPS) {
                gridIndex = Math.max(0, Math.min(prevState.gridIndex, Math.max(0, allAppsList.size() - 1)));
                retargetAppsGrid(false);
            } else if (!browserRows.isEmpty()) {
                homeRow = Math.max(0, Math.min(prevState.homeRow, browserRows.size() - 1));
                BrowserRow targetRow = browserRows.get(homeRow);
                homeCol = Math.max(0, Math.min(prevState.homeCol, Math.max(0, targetRow.count() - 1)));
            }
            zone = Z_CARDS;
            retarget(false);
            click.move();
            return;
        }
        if (activeScreen != SCREEN_HOME) {
            selectScreenInternal(SCREEN_HOME, false);
            zone = Z_CARDS;
            retarget(false);
            click.move();
            return;
        }
        if (homeRow > 0 || homeCol > 0) {
            zone = Z_CARDS;
            homeRow = 0;
            homeCol = 0;
            rowScrollMemory.clear();
            rebuildScreenRows();
            retarget(false);
            click.move();
        }
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        List<HomeApps.Item> list = activeScreen == SCREEN_APPS ? allAppsList : others;
        int n = list.size();
        if (n == 0) return;
        int next = HomeInputRouter.moveGridIndex(gridIndex, dx, dy, COLUMNS, n);
        if (next < 0) {
            if (activeScreen == SCREEN_APPS) {
                zone = Z_TOP;
                topIndex = SCREEN_APPS;
                retarget(false);
                click.move();
            }
            return;
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        heroDirty = true;
        click.move();
        gridDirty = true;
    }

    /** Home pressed twice (HomeActivity). */
    void showRecents() {
        openRecents();
    }

    /** Recent Apps over a blurred copy of the screen (without the copy, over a dark veil). */
    private void openRecents() {
        if (recents == null) {
            recents = new RecentApps(activity, new RecentApps.Host() {
                @Override public void open(HomeApps.Item item) {
                    handler.post(() -> {
                        if (!HomeApps.open(activity, item, HomeView.this, getWidth() / 2, getHeight() / 2, 1, 1)) {
                            showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
                        }
                    });
                }
                @Override public void repaint() {
                    recentLayer.postInvalidate();
                    handler.post(() -> { wake(); spoken.readAgain(); });
                }
            }, k);
        }
        ScreenBlur.capture(activity, handler, blurred -> {
            recents.setBackdrop(blurred);
            recents.open();
            recentLayer.setVisibility(VISIBLE);
            recentLayer.invalidate();
            spoken.readAgain();
            wake();
        });
    }

    private void activate() {
        wake();
        if (reordering) {
            dropReorder();
            return;
        }
        if (menuOpen) {
            pickMenu();
            return;
        }
        if (zone == Z_TOP) {
            click.open();
            flushPendingTopBarScreenSwitch();
            if (topIndex == SCREEN_REELS) {
                launchReelsPlayer(false);
                return;
            }
            if (activeScreen != topIndex) {
                selectScreen(topIndex);
            }
            zone = Z_CARDS;
            retarget(false);
            return;
        }
        if (zone == Z_SHELF && sideShelfOpen) {
            if (shelf.isEmpty()) return;
            HomeApps.Item it = shelf.get(Math.min(shelfIndex, shelf.size() - 1));
            RectF r = tileRects[Math.min(shelfIndex, tileRects.length - 1)];
            click.open();
            boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                    Math.round(r.width()), Math.round(r.height()));
            if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
            return;
        }
        if (sheetOpen || activeScreen == SCREEN_APPS) {
            List<HomeApps.Item> list = activeScreen == SCREEN_APPS ? allAppsList : others;
            if (list.isEmpty()) return;
            HomeApps.Item it = list.get(Math.min(gridIndex, list.size() - 1));
            click.open();
            if (it.kind == HomeApps.HIDDEN_APPS_TILE) {
                showHiddenAppsInline = !showHiddenAppsInline;
                rebuildAllAppsList();
                retargetAppsGrid(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                return;
            }
            boolean opened = HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
            if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
            return;
        }
        if (activeScreen == SCREEN_SETTINGS) {
            activateSettings();
            return;
        }
        if (browserRows.isEmpty()) return;
        homeRow = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow row = browserRows.get(homeRow);
        int col = Math.max(0, Math.min(homeCol, Math.max(0, row.count() - 1)));
        click.open();
        switch (row.kind) {
            case ROW_MEDIA_SELECTOR:
                selectSubTab(col);
                break;
            case ROW_CONTINUE: {
                if (!row.watchLoaded) {
                    return;
                }
                if (col < row.watchItems.size()) {
                    openWatching(row.watchItems.get(col));
                } else {
                    int pIdx = col - row.watchItems.size();
                    if (pIdx >= 0 && pIdx < row.pickItems.size()) {
                        Recs.Pick p = row.pickItems.get(pIdx);
                        if (p != null && p.pkg != null) {
                            HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
                            if (!HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                                showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
                            }
                        }
                    } else {
                        showToast(Ui.tr("Start watching a show or movie to see it here."));
                    }
                }
                break;
            }
            case ROW_PICKS:
            case ROW_GENRE: {
                if (col < row.pickItems.size()) {
                    Recs.Pick p = row.pickItems.get(col);
                    if (p != null && p.pkg != null) {
                        HomeApps.Item it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
                        if (!HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
                        }
                    }
                }
                break;
            }
            case ROW_GAMES: {
                if (col < row.gameItems.size()) {
                    GamingZone.GameItem game = row.gameItems.get(col);
                    if (GamingZone.HIDDEN_TILE_ID.equals(game.id)) {
                        showHiddenGamesInline = !showHiddenGamesInline;
                        rebuildScreenRows();
                        retarget(false);
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        break;
                    }
                    GamingZone.recordGamePlayed(getContext(), game.id);
                    rebuildScreenRows();
                    heroDirty = true;
                    GamingZone.launchGameOnTvAndLaptop(activity, this, game, "");
                }
                break;
            }
            case ROW_ACTIONS: {
                if (col < row.actionItems.size()) {
                    ActionCard act = row.actionItems.get(col);
                    executeActionCard(act);
                }
                break;
            }
            case ROW_SMARTHOME: {
                if (col < row.smartItems.size()) {
                    HomeApps.Item it = row.smartItems.get(col);
                    if (it.kind == HomeApps.SMART_DEVICE) {
                        pressHome(it);
                        rebuildScreenRows();
                        heroDirty = true;
                    } else {
                        if (!HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void executeActionCard(ActionCard act) {
        if (act == null) return;
        if ("search_bar".equals(act.id) || "ai_search_prompt".equals(act.id)) {
            promptAiSearchDialog();
        } else if ("search_voice".equals(act.id)) {
            startVoiceSearch();
        } else if ("search_clear".equals(act.id)) {
            performUnifiedSearch("");
        } else if (act.id.startsWith("youtube_query:")) {
            String yq = act.id.substring("youtube_query:".length());
            try {
                String encoded = java.net.URLEncoder.encode(yq, "UTF-8");
                onRemoteCastUrl("https://www.youtube.com/results?search_query=" + encoded, yq, "YouTube");
            } catch (Exception e) {
                onRemoteCastUrl("https://www.youtube.com", yq, "YouTube");
            }
        } else if (act.id.startsWith("ai_query:")) {
            performUnifiedSearch(act.id.substring("ai_query:".length()));
        } else if ("gaming_wake".equals(act.id)) {
            showToast(GamingZone.launch(activity, this));
            refreshGamingScreenData(false);
        } else if ("gaming_sync".equals(act.id)) {
            refreshGamingScreenData(true);
        } else if ("gaming_hub".equals(act.id)) {
            GamingZone.launch(activity, this);
        } else if ("gaming_stream_settings".equals(act.id)) {
            openStreamQualityModal();
        } else if ("gaming_hdmi_ports".equals(act.id)) {
            GamingZone.showHdmiPortsSetupDialog(activity, () -> {
                rebuildScreenRows();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });
        } else if (act.id.startsWith("gaming_console:") && act.appItem != null) {
            if (!HomeApps.open(activity, act.appItem, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                showToast(Ui.trf("Couldn't switch to {app}.", "app", act.appItem.label));
            }
        } else if (act.id.startsWith("gaming_launch:")) {
            String gameId = act.id.substring("gaming_launch:".length());
            GamingZone.GameItem sysItem = new GamingZone.GameItem(gameId, act.title, "system", "", false);
            GamingZone.launchGameOnTvAndLaptop(activity, this, sysItem, "");
        } else if ("sh_reorder_devices".equals(act.id)) {
            if (!browserRows.isEmpty() && browserRows.get(0).kind == ROW_SMARTHOME) {
                homeRow = 0;
                homeCol = 0;
                retarget(false);
                startReorder();
            }
        } else if ("sh_ac_controls".equals(act.id) || "sh_lights_dim".equals(act.id) || "sh_boiler_timer".equals(act.id)) {
            SmartHomeManager mgr = SmartHomeManager.get(getContext());
            SmartHomeManager.Device targetDev = null;
            for (SmartHomeManager.Device d : mgr.getDevices()) {
                if ("sh_ac_controls".equals(act.id) && (d.getProtocol() == SmartHomeManager.Protocol.SENSIBO_AC || d.getProtocol() == SmartHomeManager.Protocol.TUYA_AC)) { targetDev = d; break; }
                if ("sh_lights_dim".equals(act.id) && d.getProtocol() == SmartHomeManager.Protocol.WIZ_LIGHT) { targetDev = d; break; }
                if ("sh_boiler_timer".equals(act.id) && d.getProtocol() == SmartHomeManager.Protocol.SWITCHER_BOILER) { targetDev = d; break; }
            }
            if (targetDev != null) {
                openSmartDeviceControlCard(targetDev);
            }
        } else if ("sh_routine_movie".equals(act.id)) {
            SmartHomeManager.get(getContext()).executeRoutine(activity, "movie_time", (ok, msg) -> handler.post(() -> {
                showToast(msg);
                homeChanged();
                rebuildScreenRows();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }));
        } else if ("sh_routine_night".equals(act.id)) {
            SmartHomeManager.get(getContext()).executeRoutine(activity, "good_night", (ok, msg) -> handler.post(() -> {
                showToast(msg);
                homeChanged();
                rebuildScreenRows();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }));
        } else if ("sh_google_home".equals(act.id)) {
            SmartHomeManager.get(getContext()).launchGoogleHomeVoice(activity, (ok, msg) -> showToast(msg));
        } else if ("sh_wiz_scan".equals(act.id)) {
            showToast(Ui.tr("Scanning Wi-Fi subnet for WiZ light bulbs…"));
            SmartHomeManager.get(getContext()).executeRoutine(activity, "discover_wiz", (ok, msg) -> handler.post(() -> {
                showToast(msg);
                homeChanged();
                rebuildScreenRows();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            }));
        } else if ("reel_open_feed".equals(act.id)) {
            launchReelsPlayer(false);
        } else if ("reel_open_watch_later".equals(act.id)) {
            launchReelsPlayer(true);
        } else if ("reel_open_setup".equals(act.id)) {
            launchReelsConnectSetup();
        } else if (act.id.startsWith("reel_url:")) {
            String url = act.id.substring("reel_url:".length());
            onRemoteCastUrl(url, act.title, act.subtitle);
        } else if ("trakt_connect".equals(act.id)) {
            openTraktAuthDialog();
        } else if ("stremio_connect".equals(act.id)) {
            openStremioAuthDialog();
        } else if (act.id.startsWith("setting_jump:")) {
            jumpToSettingsPath(act.id.substring("setting_jump:".length()));
        } else if ("settings_soartv".equals(act.id)) {
            HomeApps.open(activity, HomeApps.settings(), this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
        } else if ("settings_tv".equals(act.id)) {
            HomeApps.open(activity, HomeApps.tvSettings(), this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
        } else if ("settings_profile".equals(act.id)) {
            UserProfileManager.Profile nextProfile = UserProfileManager.get(getContext()).cycleNextProfile();
            recs.refreshIfStale();
            reload();
            updateCards();
            showToast(Ui.tr("Profile: ") + nextProfile.displayName);
        } else if ("settings_theme".equals(act.id)) {
            toggleThemeMode();
            rebuildScreenRows();
            heroDirty = true;
        } else if ("settings_side_shelf".equals(act.id)) {
            selectScreen(SCREEN_HOME);
            openSideShelf();
        } else if (act.appItem != null) {
            if (!HomeApps.open(activity, act.appItem, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                showToast(Ui.trf("Couldn't open {app}.", "app", act.title));
            }
        }
    }

    // MARK: - Holding OK: Apps screen pins/unpins app on side-shelf; Side-Shelf reorders apps; Genre rows reorder genres; Cards open menu

    private void hold() {
        if (sheetOpen) {
            toggleShelf();
        } else if (canHold()) {
            handler.removeCallbacks(holdStart);
            holdArmed = false;
            okDown = false;
            okHeld = true;
            showMenu();
        } else if (canReorder()) {
            startReorder();
        }
    }

    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        List<HomeApps.Item> list = activeScreen == SCREEN_APPS ? allAppsList : others;
        if ((!sheetOpen && activeScreen != SCREEN_APPS) || i < 0 || i >= list.size()) return false;
        HomeApps.Item it = list.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        List<HomeApps.Item> src = activeScreen == SCREEN_APPS ? allAppsList : others;
        final HomeApps.Item it = src.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving side-shelf apps, genre rows, and smart home items

    private boolean reorderDrawer;

    private boolean canReorder() {
        if (sheetOpen || menuOpen) return false;
        if (zone == Z_SHELF && sideShelfOpen) {
            return shelfIndex >= 0 && shelfIndex < shelf.size() && shelf.size() > 1;
        }
        if (activeScreen == SCREEN_SETTINGS && zone == Z_CARDS) {
            if (settingsSearchBarFocused) return false;
            if (settingsSearchQuery != null && !settingsSearchQuery.trim().isEmpty()) return false;
            SettingsTree.Node sel = currentSettingsFocusedNode();
            if (sel != null && !sel.grayedOut
                    && (sel.id.startsWith("genre_tv_item:") || sel.id.startsWith("genre_movie_item:"))) {
                return true;
            }
            return false;
        }
        if (activeScreen == SCREEN_APPS && zone == Z_CARDS) {
            return gridIndex >= 0 && gridIndex < allAppsList.size()
                    && allAppsList.get(gridIndex).kind == HomeApps.APP
                    && !allAppsList.get(gridIndex).isHiddenItem
                    && allAppsList.size() > 1;
        }
        if (zone == Z_CARDS && !browserRows.isEmpty() && homeRow >= 0 && homeRow < browserRows.size()) {
            BrowserRow row = browserRows.get(homeRow);
            if (row.kind == ROW_GENRE) return activeGenres != null && activeGenres.size() > 1;
            if (row.kind == ROW_SMARTHOME) return row.smartItems.size() > 1;
            if (row.kind == ROW_PICKS && Recs.WATCHLIST.equals(row.pickKind)) {
                return homeCol >= 0 && homeCol < row.pickItems.size() && row.pickItems.size() > 1;
            }
            if (row.kind == ROW_GAMES) {
                return homeCol >= 0 && homeCol < row.gameItems.size()
                        && !GamingZone.HIDDEN_TILE_ID.equals(row.gameItems.get(homeCol).id)
                        && !row.gameItems.get(homeCol).hidden
                        && row.gameItems.size() > 1;
            }
        }
        return false;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        cancelReorder(true);
        wake();
        reorderTop = false;
        BrowserRow row = (!browserRows.isEmpty() && homeRow >= 0 && homeRow < browserRows.size()) ? browserRows.get(homeRow) : null;
        reorderSettingsGenre = (activeScreen == SCREEN_SETTINGS && zone == Z_CARDS && !sideShelfOpen);
        reorderAppsGrid = (!reorderSettingsGenre && activeScreen == SCREEN_APPS && zone == Z_CARDS && !sideShelfOpen);
        reorderGenre = (!reorderSettingsGenre && !reorderAppsGrid && zone == Z_CARDS && row != null && row.kind == ROW_GENRE);
        reorderDrawer = (!reorderSettingsGenre && !reorderAppsGrid && zone == Z_CARDS && row != null && row.kind == ROW_SMARTHOME);
        reorderGames = (!reorderSettingsGenre && !reorderAppsGrid && zone == Z_CARDS && row != null && row.kind == ROW_GAMES);
        reorderWatchlist = (!reorderSettingsGenre && !reorderAppsGrid && zone == Z_CARDS && row != null && row.kind == ROW_PICKS && Recs.WATCHLIST.equals(row.pickKind));
        orderRevision++;
        reordering = reorderAnim = okHeld = true;
        if (reorderSettingsGenre) {
            reorderOrigin = null;
            liftIndex = 0;
            showToast(Ui.tr("↑ ↓ to reorder genre · OK to save · Back to cancel"));
        } else if (reorderAppsGrid) {
            reorderOrigin = new ArrayList<>(allAppsList);
            liftIndex = gridIndex;
            showToast(Ui.tr("← → ↑ ↓ to reorder app · OK to save · Back to cancel"));
        } else if (reorderGenre) {
            reorderOrigin = null;
            liftIndex = homeRow;
            showToast(Ui.tr("↑ ↓ to reorder genre row · OK to save · Back to cancel"));
        } else if (reorderDrawer) {
            reorderOrigin = new ArrayList<>(drawerItems);
            liftIndex = homeCol;
            showToast(Ui.tr("← → to reorder Smart-Home · OK to save · Back to cancel"));
        } else if (reorderGames) {
            reorderOrigin = null;
            liftIndex = homeCol;
            showToast(Ui.tr("← → to reorder game · OK to save · Back to cancel"));
        } else if (reorderWatchlist) {
            reorderOrigin = null;
            liftIndex = homeCol;
            showToast(Ui.tr("← → to reorder Watch List · OK to save · Back to cancel"));
        } else {
            reorderOrigin = new ArrayList<>(shelf);
            liftIndex = shelfIndex;
            showToast(Ui.tr("↑ ↓ to move app · OK to drop · Back to cancel"));
        }
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        retarget(false);
    }

    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int step = reorderAppsGrid ? COLUMNS : 1;
                    shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void shiftReorder(int delta) {
        if (reorderSettingsGenre) {
            SettingsTree.Node sel = currentSettingsFocusedNode();
            if (sel == null || sel.grayedOut) return;
            boolean isTv = sel.id.startsWith("genre_tv_item:");
            boolean isMovie = sel.id.startsWith("genre_movie_item:");
            if (!isTv && !isMovie) return;
            String mediaType = isTv ? "tv" : "movie";
            String genre = sel.id.substring(sel.id.indexOf(':') + 1);
            List<String> vis = new ArrayList<>(Prefs.visibleGenreOrder(mediaType));
            int idx = vis.indexOf(genre);
            int nextIdx = idx + delta;
            if (idx < 0 || nextIdx < 0 || nextIdx >= vis.size()) return;
            String otherGenre = vis.get(nextIdx);
            List<String> fullOrder = new ArrayList<>(Prefs.genreOrder(mediaType));
            int fa = fullOrder.indexOf(genre), fb = fullOrder.indexOf(otherGenre);
            if (fa >= 0 && fb >= 0) {
                java.util.Collections.swap(fullOrder, fa, fb);
                Prefs.setGenreOrder(mediaType, fullOrder);
            }
            if (settingsFocusRight) {
                settingsRightIndex = nextIdx;
            } else {
                int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                settingsDepthIndices[depth] = nextIdx;
            }
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return;
        }
        if (reorderAppsGrid) {
            int i = gridIndex, j = i + delta;
            if (i < 0 || j < 0 || j >= allAppsList.size()) return;
            if (allAppsList.get(i).kind != HomeApps.APP || allAppsList.get(j).kind != HomeApps.APP) return;
            if (allAppsList.get(i).isHiddenItem || allAppsList.get(j).isHiddenItem) return;
            java.util.Collections.swap(allAppsList, i, j);
            List<String> order = new ArrayList<>();
            for (HomeApps.Item app : allAppsList) {
                if (app.kind == HomeApps.APP && !app.isHiddenItem && app.pkg != null) order.add(app.pkg);
            }
            Prefs.setAppOrder(order);
            gridIndex = liftIndex = j;
            gridLabels = null;
            retargetAppsGrid(false);
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return;
        }
        if (reorderGenre) {
            if (homeRow < 0 || homeRow >= browserRows.size()) return;
            BrowserRow curr = browserRows.get(homeRow);
            if (curr.kind != ROW_GENRE || curr.genreKey == null) return;
            int targetRow = homeRow + delta;
            if (targetRow < 0 || targetRow >= browserRows.size()) return;
            BrowserRow adj = browserRows.get(targetRow);
            if (adj.kind != ROW_GENRE || adj.genreKey == null) return;
            String subKind = subTab == 0 ? "tv" : "movie";
            List<String> fullOrder = new ArrayList<>(Prefs.genreOrder(subKind));
            int fa = fullOrder.indexOf(curr.genreKey), fb = fullOrder.indexOf(adj.genreKey);
            if (fa >= 0 && fb >= 0) {
                java.util.Collections.swap(fullOrder, fa, fb);
                Prefs.setGenreOrder(subKind, fullOrder);
            }
            homeRow = targetRow;
            liftIndex = targetRow;
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            wake();
            click.move();
            return;
        }
        if (reorderDrawer) {
            if (homeRow < 0 || homeRow >= browserRows.size()) return;
            BrowserRow row = browserRows.get(homeRow);
            int i = homeCol, j = i + delta;
            if (i < 0 || j < 0 || j >= row.smartItems.size()) return;
            HomeApps.Item itemA = row.smartItems.get(i);
            HomeApps.Item itemB = row.smartItems.get(j);
            int da = -1, db = -1;
            for (int kIdx = 0; kIdx < drawerItems.size(); kIdx++) {
                if (itemA.key.equals(drawerItems.get(kIdx).key)) da = kIdx;
                if (itemB.key.equals(drawerItems.get(kIdx).key)) db = kIdx;
            }
            if (da >= 0 && db >= 0) {
                java.util.Collections.swap(drawerItems, da, db);
            }
            homeCol = liftIndex = j;
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            wake();
            click.move();
            return;
        }
        if (reorderGames) {
            if (homeRow < 0 || homeRow >= browserRows.size()) return;
            BrowserRow row = browserRows.get(homeRow);
            int i = homeCol, j = i + delta;
            if (i < 0 || j < 0 || j >= row.gameItems.size()) return;
            if (GamingZone.HIDDEN_TILE_ID.equals(row.gameItems.get(i).id)
                    || GamingZone.HIDDEN_TILE_ID.equals(row.gameItems.get(j).id)
                    || row.gameItems.get(i).hidden
                    || row.gameItems.get(j).hidden) return;
            java.util.Collections.swap(row.gameItems, i, j);
            List<String> order = new ArrayList<>();
            for (GamingZone.GameItem g : row.gameItems) {
                if (!GamingZone.HIDDEN_TILE_ID.equals(g.id) && !g.hidden) order.add(g.id);
            }
            GamingZone.setGameOrder(getContext(), order);
            gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
            homeCol = liftIndex = j;
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            wake();
            click.move();
            return;
        }
        if (reorderWatchlist) {
            if (homeRow < 0 || homeRow >= browserRows.size()) return;
            BrowserRow row = browserRows.get(homeRow);
            int i = homeCol, j = i + delta;
            if (i < 0 || j < 0 || j >= row.pickItems.size()) return;
            if (row.pickItems.get(i).watched != row.pickItems.get(j).watched) return;
            java.util.Collections.swap(row.pickItems, i, j);
            String subKind = subTab == 0 ? "tv" : "movie";
            List<String> order = new ArrayList<>(row.pickItems.size());
            for (Recs.Pick p : row.pickItems) {
                String k = Recs.key(p);
                if (k != null && !k.isEmpty()) order.add(k);
            }
            Prefs.setWatchlistOrder(subKind, order);
            recs.invalidateRowCache();
            homeCol = liftIndex = j;
            rebuildScreenRows();
            retarget(false);
            heroDirty = true;
            wake();
            click.move();
            return;
        }
        int i = shelfIndex, j = i + delta;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].top - tileRects[j].top;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        orderRevision++;
        moveLift.target = 0;
        if (reorderSettingsGenre) {
            reorderSettingsGenre = false;
            liftIndex = -1;
            reorderOrigin = null;
            recs.onUserOrderChanged(subTab == 0 ? "tv" : "movie");
            click.open();
            showToast(Ui.tr("Genre order saved"));
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            return;
        }
        if (reorderAppsGrid) {
            reorderAppsGrid = false;
            liftIndex = -1;
            reorderOrigin = null;
            click.open();
            showToast(Ui.tr("Apps order saved"));
            heroDirty = true;
            wake();
            return;
        }
        if (reorderGenre) {
            reorderGenre = false;
            liftIndex = -1;
            recs.onUserOrderChanged(subTab == 0 ? "tv" : "movie");
            click.open();
            String label = subTab == 0 ? Ui.tr("TV Shows genre order saved") : Ui.tr("Movies genre order saved");
            showToast(label);
            heroDirty = true;
            wake();
            return;
        }
        if (reorderDrawer) {
            reorderDrawer = false;
            liftIndex = -1;
            List<String> keys = new ArrayList<>(drawerItems.size());
            for (HomeApps.Item it : drawerItems) if (it.key != null) keys.add(it.key);
            Prefs.setDrawerOrder(keys);
            reorderOrigin = null;
            click.open();
            showToast(Ui.tr("Smart-Home order saved"));
            heroDirty = true;
            wake();
            return;
        }
        if (reorderGames) {
            reorderGames = false;
            liftIndex = -1;
            reorderOrigin = null;
            click.open();
            showToast(Ui.tr("Games order saved"));
            heroDirty = true;
            wake();
            return;
        }
        if (reorderWatchlist) {
            reorderWatchlist = false;
            liftIndex = -1;
            reorderOrigin = null;
            recs.onUserOrderChanged(subTab == 0 ? "tv" : "movie");
            click.open();
            showToast(Ui.tr("Watch List order saved"));
            heroDirty = true;
            wake();
            return;
        }
        List<HomeApps.Item> items = shelf;
        boolean changed = false;
        for (int i = 0; reorderOrigin != null && i < items.size() && !changed; i++) changed = items.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) { if (appsStale && running) reloadSoon(); return; }
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        pendingOrderSaves++;
        LOADER.execute(() -> {
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> {
                Prefs.setShelf(save);
                pendingOrderSaves--;
                if (appsStale && running) reloadSoon();
            });
        });
    }

    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            orderRevision++;
            moveLift.target = 0;
            int n = shelf.size();
            if (!reorderSettingsGenre && !reorderAppsGrid && !reorderGenre && !reorderDrawer && !reorderGames && !reorderWatchlist && reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].top - tileRects[a].top;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderSettingsGenre = false;
            reorderAppsGrid = false;
            reorderGenre = false;
            reorderDrawer = false;
            reorderGames = false;
            reorderWatchlist = false;
            reorderOrigin = null;
            retarget(snap);
            if (appsStale && running) reloadSoon();
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            for (Spring s : topSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            reorderTop = false;
            statusDirty = true;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | step(topSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        if (!moving) { reorderTop = false; liftIndex = -1; }
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9, WIDGETS = 8, MENU_ITEMS = 10, RECENT_CARDS = 11;

    private boolean recentsOpen() {
        return recents != null && recents.isOpen();
    }

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (recentsOpen()) {
            for (int i = 0; i < Math.max(1, recents.count()); i++) out.add(a11yId(RECENT_CARDS, i));
            return;
        }
        if (menuOpen) {
            for (int i = 0; i < menuOptionCount; i++) out.add(a11yId(MENU_ITEMS, i));
            return;
        }
        boolean[] mask = FeatureTabs.currentMask(getContext());
        for (int i = 0; i < TOP_BAR_COUNT; i++) {
            if (mask[i]) out.add(a11yId(Z_TOP, i));
        }
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + SIDE_SHELF_VISIBLE); i++) out.add(a11yId(Z_SHELF, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case RECENT_CARDS:
                if (!recentsOpen()) return "";
                return recents.count() == 0 ? Ui.tr("Recent Apps") + ". " + recents.emptyText() : recents.spoken(i);
            case MENU_ITEMS:
                if (!menuOpen || i >= menuOptionCount) return "";
                return (i == 0 && menuItem != null ? menuItem.title + ". " : "") + menuOptions[i];
            case Z_TOP:
                if (i >= 0 && i < TOP_BAR_COUNT) return topBarLabels()[i];
                return "";
            case Z_SHELF:
                return label(shelf, i);
            default:
                return "";
        }
    }

    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case Z_TOP:
                if (i >= TOP_BAR_COUNT) return false;
                a11yRect.set(topRects[i]);
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                break;
            default:
                return false;
        }
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private boolean a11yHolds(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == RECENT_CARDS) return recentsOpen() && recents.stoppable(i);
        if (group == Z_SHELF) return shelf.size() > 1 && i < shelf.size();
        return false;
    }

    private int a11yFocused() {
        if (recentsOpen()) return a11yId(RECENT_CARDS, recents.count() == 0 ? 0 : recents.focus());
        if (menuOpen) return a11yId(MENU_ITEMS, menuIndex);
        if (zone == Z_TOP) return a11yId(Z_TOP, topIndex);
        if (zone == Z_SHELF) return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
        return -1;
    }

    private List<Integer> widgets() {
        return java.util.Collections.emptyList();
    }

    private final Runnable gameBackdropLoaded = new Runnable() {
        @Override public void run() {
            if (running && activeScreen == SCREEN_GAMING) {
                handler.removeCallbacks(bgFollow);
                bgFollow.run();
            }
        }
    };

    private GamingZone.GameItem focusedGameOnGaming() {
        if (browserRows.isEmpty()) return null;
        int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow row = browserRows.get(r);
        if (row.kind == ROW_GAMES && row.gameItems != null && !row.gameItems.isEmpty()) {
            return row.gameItems.get(Math.max(0, Math.min(homeCol, row.gameItems.size() - 1)));
        }
        return null;
    }

    private final Runnable bgFollow = new Runnable() {
        @Override public void run() {
            if (!running || browserRows.isEmpty()) return;
            if (activeScreen == SCREEN_GAMING) {
                int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
                BrowserRow row = browserRows.get(r);
                Backdrop gb = null;
                if (row.kind == ROW_GAMES && row.gameItems != null && !row.gameItems.isEmpty()) {
                    int col = Math.max(0, Math.min(homeCol, row.gameItems.size() - 1));
                    GamingZone.GameItem g = row.gameItems.get(col);
                    gb = GamingZone.getGameBackdrop(getContext(), g, gameBackdropLoaded);
                    // Preload adjacent neighbors so rapid D-Pad scrolling swaps backdrops in 0ms
                    for (int ni = Math.max(0, col - 2); ni <= Math.min(row.gameItems.size() - 1, col + 3); ni++) {
                        if (ni != col) {
                            GamingZone.preloadGameBackdropAsync(getContext(), row.gameItems.get(ni), null);
                        }
                    }
                }
                // Keep the currently loaded game background displayed until the newly focused game's hero finishes loading
                if (gb != null && gb != bgTo) {
                    showBackdrop(gb);
                    wake();
                } else if (gb == null && bgTo == null) {
                    Backdrop fallbackReady = GamingZone.firstReadyGameBackdrop(collectPrioritizedGamingItems());
                    if (fallbackReady != null) {
                        showBackdrop(fallbackReady);
                        wake();
                    }
                }
                return;
            }
            if (activeScreen != SCREEN_HOME) return;
            int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
            BrowserRow row = browserRows.get(r);
            if (row.kind == ROW_CONTINUE && homeCol >= 0 && homeCol < row.watchItems.size()) {
                WatchNext.Item wIt = row.watchItems.get(homeCol);
                Backdrop wb = recs.backdropForWatchItem(wIt, watchArt);
                if (wb != null && wb != bgTo) {
                    showBackdrop(wb);
                    wake();
                }
                return;
            }
            Recs.Pick p = focusedPickOnHome();
            if (p == null) return;
            Backdrop b = recs.backdrop(p);
            if (b == null || b == bgTo) return;
            showBackdrop(b);
            wake();
        }
    };

    private Backdrop peekFocusedHomeBackdrop() {
        if (browserRows.isEmpty()) return null;
        int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow curRow = browserRows.get(r);
        if (curRow.kind == ROW_CONTINUE && homeCol >= 0 && homeCol < curRow.watchItems.size()) {
            return recs.peekWatchBackdrop(curRow.watchItems.get(homeCol));
        }
        Recs.Pick fp = focusedPickOnHome();
        return fp != null ? recs.peekBackdrop(fp) : null;
    }

    private String focusedHomeBackdropKey() {
        if (browserRows.isEmpty()) return null;
        int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow curRow = browserRows.get(r);
        if (curRow.kind == ROW_CONTINUE && homeCol >= 0 && homeCol < curRow.watchItems.size()) {
            return recs.watchBackdropKey(curRow.watchItems.get(homeCol));
        }
        Recs.Pick fp = focusedPickOnHome();
        if (fp == null || fp.backdrop == null || fp.backdrop.isEmpty()) return null;
        return fp.backdrop;
    }

    private Recs.Pick focusedPickOnHome() {
        if (browserRows.isEmpty()) return null;
        int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow row = browserRows.get(r);
        if ((row.kind == ROW_PICKS || row.kind == ROW_GENRE) && !row.pickItems.isEmpty()) {
            return row.pickItems.get(Math.max(0, Math.min(homeCol, row.pickItems.size() - 1)));
        }
        if (row.kind == ROW_CONTINUE) {
            if (homeCol >= row.watchItems.size()) {
                int idx = homeCol - row.watchItems.size();
                if (idx >= 0 && idx < row.pickItems.size()) return row.pickItems.get(idx);
            }
            // Never fall through to Watch List's first item while hovering a Continue Watching item
            return null;
        }
        for (BrowserRow br : browserRows) {
            if (!br.pickItems.isEmpty()) return br.pickItems.get(0);
        }
        return null;
    }

    // MARK: - Milestone 4: Top Details Header & Auto-Scroll Overview Marquee

    private void updateHeaderFocus(boolean snap) {
        if (activeScreen != SCREEN_HOME || browserRows.isEmpty()) return;

        int r = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow row = browserRows.get(r);

        tmpHeader.clear();
        tmpHeader.subTab = subTab;

        if (row.kind == ROW_MEDIA_SELECTOR) {
            // Selector row stability: Retain previous media item if subTab hasn't changed
            if (!currentHeader.title.isEmpty() && currentHeader.subTab == subTab) {
                return;
            }
            Recs.Pick defPick = findFirstMediaPick();
            if (defPick != null) {
                bindHeaderFromPick(tmpHeader, defPick);
            }
        } else if (row.kind == ROW_PICKS || row.kind == ROW_GENRE) {
            if (!row.pickItems.isEmpty()) {
                int c = Math.max(0, Math.min(homeCol, row.pickItems.size() - 1));
                Recs.Pick p = row.pickItems.get(c);
                bindHeaderFromPick(tmpHeader, p);
                if (p != null && (!p.detailed || p.overview.isEmpty() || p.imdbScore.isEmpty()
                        || p.backdrop == null || p.backdrop.isEmpty())) {
                    final String focusKey = tmpHeader.mediaKey;
                    recs.ensureMetadataAsync(p, updatedPick -> {
                        if (focusKey.equals(currentHeader.mediaKey)) {
                            bindHeaderFromPick(currentHeader, updatedPick);
                            resetOverview(currentHeader.overviewText);
                            bgFollow.run();
                            heroDirty = true;
                            heroLayer.invalidate();
                            wake();
                        }
                    });
                }
            }
        } else if (row.kind == ROW_CONTINUE) {
            if (!row.watchItems.isEmpty() && homeCol < row.watchItems.size()) {
                int c = Math.max(0, Math.min(homeCol, row.watchItems.size() - 1));
                WatchNext.Item wIt = row.watchItems.get(c);
                bindHeaderFromWatchItem(tmpHeader, wIt);
                Recs.Pick matched = findPickForWatchItem(wIt);
                boolean needEpisodeOv = wIt.isSeries && (recs.getCachedEpisodeOverview(wIt, matched).isEmpty()
                        || wIt.episodeTitle == null || wIt.episodeTitle.isEmpty());
                if (matched == null || !matched.detailed || matched.overview.isEmpty() || matched.imdbScore.isEmpty()
                        || needEpisodeOv) {
                    final String focusKey = tmpHeader.mediaKey;
                    recs.ensureWatchItemMetadataAsync(wIt, updatedPick -> {
                        if (focusKey.equals(currentHeader.mediaKey)) {
                            bindHeaderFromWatchItem(currentHeader, wIt);
                            resetOverview(currentHeader.overviewText);
                            bgFollow.run();
                            heroDirty = true;
                            heroLayer.invalidate();
                            wake();
                        }
                    });
                }
            } else if (!row.pickItems.isEmpty()) {
                int c = Math.max(0, Math.min(homeCol - row.watchItems.size(), row.pickItems.size() - 1));
                Recs.Pick p = row.pickItems.get(c);
                bindHeaderFromContinuePick(tmpHeader, p);
                if (p != null) {
                    final String focusKey = tmpHeader.mediaKey;
                    if (!p.detailed || p.overview.isEmpty() || p.imdbScore.isEmpty()
                            || p.backdrop == null || p.backdrop.isEmpty()) {
                        recs.ensureMetadataAsync(p, updatedPick -> {
                            if (focusKey.equals(currentHeader.mediaKey)) {
                                bindHeaderFromContinuePick(currentHeader, updatedPick);
                                resetOverview(currentHeader.overviewText);
                                bgFollow.run();
                                heroDirty = true;
                                heroLayer.invalidate();
                                wake();
                            }
                        });
                    }
                    if ("tv".equals(p.kind) && recs.getCachedPickEpisodeOverview(p).isEmpty()) {
                        recs.ensurePickEpisodeOverviewAsync(p, () -> {
                            if (focusKey.equals(currentHeader.mediaKey)) {
                                bindHeaderFromContinuePick(currentHeader, p);
                                resetOverview(currentHeader.overviewText);
                                heroDirty = true;
                                heroLayer.invalidate();
                                wake();
                            }
                        });
                    }
                }
            }
        } else if (row.kind == ROW_ACTIONS) {
            if (!row.actionItems.isEmpty()) {
                int c = Math.max(0, Math.min(homeCol, row.actionItems.size() - 1));
                ActionCard card = row.actionItems.get(c);
                bindHeaderFromActionCard(tmpHeader, card);
            }
        }

        if (tmpHeader.mediaKey.isEmpty()) return;

        // Check if the focused item actually changed
        if (tmpHeader.mediaKey.equals(currentHeader.mediaKey)) {
            boolean changed = false;
            if (!tmpHeader.continueStatusText.equals(currentHeader.continueStatusText)) {
                currentHeader.continueStatusText = tmpHeader.continueStatusText;
                changed = true;
            }
            if (!tmpHeader.overviewText.isEmpty() && !tmpHeader.overviewText.equals(currentHeader.overviewText)) {
                currentHeader.overviewText = tmpHeader.overviewText;
                currentHeader.overviewLayout = tmpHeader.overviewLayout;
                currentHeader.overviewLayoutWidth = tmpHeader.overviewLayoutWidth;
                currentHeader.overviewTextSize = tmpHeader.overviewTextSize;
                resetOverview(currentHeader.overviewText);
                changed = true;
            }
            if (!currentHeader.hasRt && tmpHeader.hasRt) {
                currentHeader.hasRt = true;
                currentHeader.rtScoreText = tmpHeader.rtScoreText;
                currentHeader.hasRow2 = true;
                changed = true;
            }
            if (!currentHeader.hasMc && tmpHeader.hasMc) {
                currentHeader.hasMc = true;
                currentHeader.mcScoreText = tmpHeader.mcScoreText;
                currentHeader.hasRow2 = true;
                changed = true;
            }
            if (!currentHeader.hasImdb && tmpHeader.hasImdb) {
                currentHeader.hasImdb = true;
                currentHeader.imdbScoreText = tmpHeader.imdbScoreText;
                currentHeader.hasRow2 = true;
                changed = true;
            }
            if (currentHeader.durationText == null && tmpHeader.durationText != null) {
                currentHeader.durationText = tmpHeader.durationText;
                currentHeader.hasRow2 = true;
                changed = true;
            }
            if (currentHeader.datesText == null && tmpHeader.datesText != null) {
                currentHeader.datesText = tmpHeader.datesText;
                currentHeader.hasRow2 = true;
                changed = true;
            }
            if (currentHeader.genresText.isEmpty() && !tmpHeader.genresText.isEmpty()) {
                currentHeader.genresText = tmpHeader.genresText;
                changed = true;
            }
            if (changed) {
                heroDirty = true;
                wake();
            }
            return;
        }

        boolean firstLoad = currentHeader.title.isEmpty();
        previousHeader.copyFrom(currentHeader);
        currentHeader.copyFrom(tmpHeader);

        long now = SystemClock.uptimeMillis();
        headerFadeAt = (snap || firstLoad) ? 0L : now;

        resetOverview(currentHeader.overviewText);

        heroDirty = true;
        wake();
    }

    private void bindHeaderFromPick(HeaderState out, Recs.Pick p) {
        if (p == null) return;
        out.mediaKey = "pick:" + p.id + ":" + (p.kind != null ? p.kind : "") + ":" + (p.title != null ? p.title : "");
        out.subTab = subTab;
        out.title = p.title != null ? p.title.trim() : "";

        float maxTitleW = 1150 * k;
        if (headerTitlePaint != null && maxTitleW > 0 && headerTitlePaint.measureText(out.title) > maxTitleW) {
            int count = headerTitlePaint.breakText(out.title, true, maxTitleW - headerTitlePaint.measureText("…"), null);
            out.displayTitle = (count > 0 && count < out.title.length()) ? (out.title.substring(0, count) + "…") : out.title;
        } else {
            out.displayTitle = out.title;
        }

        out.durationText = Recs.formatDuration(p);
        out.datesText = Recs.formatDates(p);

        String rt = p.rtScore != null ? p.rtScore.trim() : "";
        if (!rt.isEmpty() && !"N/A".equalsIgnoreCase(rt) && !"0".equals(rt) && !"0%".equals(rt)) {
            out.hasRt = true;
            out.rtScoreText = rt.endsWith("%") ? rt : (rt + "%");
        } else {
            out.hasRt = false;
            out.rtScoreText = null;
        }

        String mc = Recs.formatMetacritic(p);
        if (mc != null && !mc.isEmpty()) {
            out.hasMc = true;
            out.mcScoreText = mc;
        } else {
            out.hasMc = false;
            out.mcScoreText = null;
        }

        String imdb = p.imdbScore != null ? p.imdbScore.trim() : "";
        if (!imdb.isEmpty() && !"N/A".equalsIgnoreCase(imdb) && !"0".equals(imdb) && !"0.0".equals(imdb)) {
            out.hasImdb = true;
            out.imdbScoreText = "IMDb " + imdb;
        } else if (p.rating > 0.0 && p.votesCount > 0) {
            out.hasImdb = true;
            out.imdbScoreText = String.format(Locale.US, "IMDb %.1f", p.rating);
        } else {
            out.hasImdb = false;
            out.imdbScoreText = null;
        }

        out.hasRow2 = (out.durationText != null || out.datesText != null || out.hasRt || out.hasMc || out.hasImdb);

        String rawGenres = Recs.formatGenres(p);
        float maxGenreW = 1150 * k;
        if (headerGenrePaint != null && maxGenreW > 0 && headerGenrePaint.measureText(rawGenres) > maxGenreW) {
            int count = headerGenrePaint.breakText(rawGenres, true, maxGenreW - headerGenrePaint.measureText("…"), null);
            out.genresText = (count > 0 && count < rawGenres.length()) ? (rawGenres.substring(0, count) + "…") : rawGenres;
        } else {
            out.genresText = rawGenres != null ? rawGenres : "";
        }

        out.continueStatusText = "";
        out.overviewText = Recs.formatOverviewText(p.overview);
        out.overviewLayout = null;
        ensureOverviewLayout(out);
    }

    private void bindHeaderFromContinuePick(HeaderState out, Recs.Pick p) {
        if (p == null) return;
        bindHeaderFromPick(out, p);
        out.mediaKey = "continue_pick:" + p.id + ":" + (p.kind != null ? p.kind : "") + ":" + (p.title != null ? p.title : "");
        boolean isSeries = "tv".equals(p.kind);
        out.continueStatusText = WatchNext.formatContinueWatchingStatus(
                p.progressSeason, p.progressEpisode, p.tag, isSeries);
        String epOv = isSeries ? recs.getCachedPickEpisodeOverview(p) : "";
        out.overviewText = resolveContinueWatchingOverview(isSeries, epOv, p.overview);
        out.overviewLayout = null;
        ensureOverviewLayout(out);
    }

    private void bindHeaderFromWatchItem(HeaderState out, WatchNext.Item wIt) {
        if (wIt == null) return;
        Recs.Pick matched = findPickForWatchItem(wIt);
        String epOverview = recs.getCachedEpisodeOverview(wIt, matched);
        if (matched != null) {
            bindHeaderFromPick(out, matched);
            out.mediaKey = "watch:" + wIt.pkg + ":" + (wIt.title != null ? wIt.title : "");
            if (out.title.isEmpty() && wIt.title != null) {
                out.title = wIt.title.trim();
                out.displayTitle = out.title;
            }
            if (out.durationText == null && wIt.duration > 0) {
                out.durationText = Recs.formatDuration((int) (wIt.duration / 60_000L));
                out.hasRow2 = true;
            }
            out.continueStatusText = WatchNext.formatContinueWatchingStatus(wIt);
            out.overviewText = resolveContinueWatchingOverview(wIt.isSeries, epOverview, matched.overview);
            out.overviewLayout = null;
            ensureOverviewLayout(out);
            return;
        }

        // Clean fallback for external items without TMDB metadata
        out.mediaKey = "watch:" + wIt.pkg + ":" + (wIt.title != null ? wIt.title : "");
        out.subTab = subTab;
        out.title = wIt.title != null ? wIt.title.trim() : "";
        out.displayTitle = out.title;
        if (wIt.duration > 0) {
            out.durationText = Recs.formatDuration((int) (wIt.duration / 60_000L));
        } else {
            out.durationText = null;
        }
        out.datesText = null;
        out.hasRt = false;
        out.rtScoreText = null;
        out.hasMc = false;
        out.mcScoreText = null;
        out.hasImdb = false;
        out.imdbScoreText = null;
        out.hasRow2 = (out.durationText != null);

        out.genresText = wIt.app != null ? wIt.app.trim() : "";
        out.continueStatusText = WatchNext.formatContinueWatchingStatus(wIt);
        out.overviewText = resolveContinueWatchingOverview(wIt.isSeries, epOverview, wIt.description);
        out.overviewLayout = null;
        ensureOverviewLayout(out);
    }

    private void bindHeaderFromActionCard(HeaderState out, ActionCard card) {
        if (card == null) return;
        out.mediaKey = "action:" + card.id;
        out.subTab = subTab;
        out.title = card.title != null ? card.title.trim() : "";
        out.displayTitle = out.title;
        out.durationText = null;
        out.datesText = null;
        out.hasRt = false;
        out.rtScoreText = null;
        out.hasMc = false;
        out.mcScoreText = null;
        out.hasImdb = false;
        out.imdbScoreText = null;
        out.hasRow2 = false;
        out.genresText = "";
        out.overviewText = Recs.formatOverviewText(card.subtitle);
        out.overviewLayout = null;
        ensureOverviewLayout(out);
    }

    private void ensureOverviewLayout(HeaderState s) {
        if (s == null || overviewPaint == null || k <= 0f) return;
        if (s.overviewText == null || s.overviewText.isEmpty()) {
            s.overviewLayout = null;
            s.overviewLayoutWidth = 0;
            s.overviewTextSize = 0f;
            return;
        }
        int wrapW = Math.max(320, Math.round(Math.min(OVERVIEW_WRAP_WIDTH * k, Math.max(320f, w - 2f * MARGIN * k))));
        float textSize = overviewPaint.getTextSize();
        if (s.overviewLayout != null && s.overviewLayoutWidth == wrapW && Math.abs(s.overviewTextSize - textSize) < 0.05f) {
            return;
        }
        s.overviewLayout = StaticLayout.Builder.obtain(s.overviewText, 0, s.overviewText.length(), overviewPaint, wrapW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f * k, 1.12f)
                .setIncludePad(false)
                .build();
        s.overviewLayoutWidth = wrapW;
        s.overviewTextSize = textSize;
    }

    private Recs.Pick findPickForWatchItem(WatchNext.Item wIt) {
        if (wIt == null) return null;
        Recs.Pick fromRecs = recs.findMatchingPick(wIt);
        if (fromRecs != null) return fromRecs;
        if (wIt.title == null || wIt.title.trim().isEmpty()) return null;
        String target = wIt.title.trim();
        for (BrowserRow br : browserRows) {
            if (br.pickItems != null) {
                for (Recs.Pick p : br.pickItems) {
                    if (p != null && p.title != null && p.title.trim().equalsIgnoreCase(target)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private Recs.Pick findFirstMediaPick() {
        for (BrowserRow br : browserRows) {
            if ((br.kind == ROW_PICKS || br.kind == ROW_GENRE) && br.pickItems != null && !br.pickItems.isEmpty()) {
                return br.pickItems.get(0);
            }
        }
        return null;
    }

    void resetOverview(String newOverview) {
        overviewScrollY = 0f;
        overviewStartAt = SystemClock.uptimeMillis();
        lastOverviewCycle = 0L;
        handler.removeCallbacks(overviewWake);

        ensureOverviewLayout(currentHeader);
        if (currentHeader.overviewText.isEmpty() || overviewPaint == null || currentHeader.overviewLayout == null || k <= 0f) {
            overviewMaxScroll = 0f;
            overviewActive = false;
        } else {
            float boxH = (OVERVIEW_BOX_BOTTOM - effectiveOverviewBoxTop(currentHeader)) * k;
            float layoutH = currentHeader.overviewLayout.getHeight();
            overviewMaxScroll = layoutH > boxH + 2f * k ? (layoutH - boxH) : 0f;
            overviewActive = (overviewMaxScroll > 0f);
            if (overviewActive) {
                handler.postDelayed(overviewWake, OVERVIEW_PAUSE_START_MS);
            }
        }
    }

    static float computeVerticalOverviewScrollY(long elapsedRaw, float maxScrollY, float scaleK) {
        if (maxScrollY <= 0f || scaleK <= 0f) return 0f;
        float speedPxPerSec = OVERVIEW_SCROLL_SPEED * scaleK;
        long scrollDurationMs = Math.max(1L, (long) ((maxScrollY / speedPxPerSec) * 1000f));
        long totalCycleMs = OVERVIEW_PAUSE_START_MS + scrollDurationMs + OVERVIEW_PAUSE_END_MS + OVERVIEW_RESET_DURATION_MS;
        long elapsed = elapsedRaw <= 0L ? 0L : (elapsedRaw % totalCycleMs);
        if (elapsed < OVERVIEW_PAUSE_START_MS) {
            return 0f;
        } else if (elapsed < OVERVIEW_PAUSE_START_MS + scrollDurationMs) {
            float scrollElapsedSec = (elapsed - OVERVIEW_PAUSE_START_MS) / 1000f;
            return Math.min(maxScrollY, scrollElapsedSec * speedPxPerSec);
        } else if (elapsed < OVERVIEW_PAUSE_START_MS + scrollDurationMs + OVERVIEW_PAUSE_END_MS) {
            return maxScrollY;
        } else {
            float resetElapsed = elapsed - (OVERVIEW_PAUSE_START_MS + scrollDurationMs + OVERVIEW_PAUSE_END_MS);
            float t = Math.max(0f, Math.min(1f, resetElapsed / (float) OVERVIEW_RESET_DURATION_MS));
            float ease = 1f - (t * t * (3f - 2f * t));
            return maxScrollY * ease;
        }
    }

    private boolean stepOverview(long up) {
        if (!running || activeScreen != SCREEN_HOME || !overviewActive || overviewMaxScroll <= 0f
                || sideShelfOpen || menuOpen || sleepModalOpen || slideDurationModalOpen || streamQualityModalOpen || syncModalOpen || gameLaunchModalOpen || drawerOpen || sheetOpen) {
            if (overviewScrollY != 0f) {
                overviewScrollY = 0f;
                return true;
            }
            return false;
        }

        float speedPxPerSec = OVERVIEW_SCROLL_SPEED * k;
        long scrollDurationMs = Math.max(1L, (long) ((overviewMaxScroll / speedPxPerSec) * 1000f));
        long totalCycleMs = OVERVIEW_PAUSE_START_MS + scrollDurationMs + OVERVIEW_PAUSE_END_MS + OVERVIEW_RESET_DURATION_MS;

        long diff = up - overviewStartAt;
        long elapsed = diff <= 0L ? 0L : (diff % totalCycleMs);
        float prevScrollY = overviewScrollY;
        overviewScrollY = computeVerticalOverviewScrollY(diff, overviewMaxScroll, k);

        long cycle = diff <= 0L ? 0L : (diff / totalCycleMs);
        if (cycle > lastOverviewCycle) {
            lastOverviewCycle = cycle;
            handler.removeCallbacks(overviewWake);
            long remainingPause = OVERVIEW_PAUSE_START_MS - elapsed;
            if (remainingPause > 0L) {
                handler.postDelayed(overviewWake, remainingPause);
            }
        }

        boolean moved = Math.abs(overviewScrollY - prevScrollY) > 0.01f;
        boolean inActiveWindow = (overviewActive && elapsed >= OVERVIEW_PAUSE_START_MS && elapsed < totalCycleMs);
        return moved || inActiveWindow;
    }

    private boolean tickHeaderFade(long up) {
        if (headerFadeAt == 0L) return false;
        float t = (up - headerFadeAt) / HEADER_FADE_MS;
        if (t >= 1.0f) {
            headerFadeAt = 0L;
            previousHeader.clear();
            heroDirty = true;
            return true;
        }
        return true;
    }

    private void drawTopDetailsHeader(Canvas c) {
        if (currentHeader.mediaKey.isEmpty()) {
            updateHeaderFocus(true);
        }
        long up = SystemClock.uptimeMillis();
        boolean fading = (headerFadeAt != 0L);
        float alpha = 1.0f;
        if (fading) {
            float t = (up - headerFadeAt) / HEADER_FADE_MS;
            if (t >= 1.0f) {
                headerFadeAt = 0L;
                previousHeader.clear();
                fading = false;
            } else {
                alpha = smoothstep(0f, 1f, t);
            }
        }

        if (fading && previousHeader.title != null && !previousHeader.title.isEmpty()) {
            drawHeaderState(c, previousHeader, 1.0f - alpha, false);
        }
        if (currentHeader.title != null && !currentHeader.title.isEmpty()) {
            drawHeaderState(c, currentHeader, fading ? alpha : 1.0f, true);
        }
    }

    private void drawHeaderState(Canvas c, HeaderState s, float alpha, boolean allowMarquee) {
        if (s == null || alpha <= 0.01f) return;
        float left = MARGIN * k;
        int origTitleAlpha = headerTitlePaint != null ? headerTitlePaint.getAlpha() : 255;
        int origMetaAlpha = headerMetaPaint != null ? headerMetaPaint.getAlpha() : 255;
        int origGenreAlpha = headerGenrePaint != null ? headerGenrePaint.getAlpha() : 255;
        int origContinueAlpha = headerContinuePaint != null ? headerContinuePaint.getAlpha() : 255;
        int origOverviewAlpha = overviewPaint != null ? overviewPaint.getAlpha() : 255;

        if (alpha < 0.999f) {
            if (headerTitlePaint != null) headerTitlePaint.setAlpha(Math.round(origTitleAlpha * alpha));
            if (headerMetaPaint != null) headerMetaPaint.setAlpha(Math.round(origMetaAlpha * alpha));
            if (headerGenrePaint != null) headerGenrePaint.setAlpha(Math.round(origGenreAlpha * alpha));
            if (headerContinuePaint != null) headerContinuePaint.setAlpha(Math.round(origContinueAlpha * alpha));
            if (overviewPaint != null) overviewPaint.setAlpha(Math.round(origOverviewAlpha * alpha));
        }

        // --- Row 1: Title (baseline = 172 * k) ---
        if (s.title != null && !s.title.isEmpty() && headerTitlePaint != null) {
            float base1 = 172 * k;
            String text = s.displayTitle != null && !s.displayTitle.isEmpty() ? s.displayTitle : s.title;
            c.drawText(text, left, base1, headerTitlePaint);
        }

        // --- Row 2: Duration · Dates · Ratings (baseline = 216 * k) ---
        if (s.hasRow2 && headerMetaPaint != null) {
            float base2 = 216 * k;
            float x = left;
            float iconPointSize = 20 * k;
            float cy = base2 - Ui.halfCap(headerMetaPaint);
            int baseIconColor = tone(L_INK, 0xFFFFFFFF);
            int iconColor = (alpha < 0.999f) ? Ui.alpha(baseIconColor, alpha) : baseIconColor;
            boolean needDot = false;

            // 1. Duration (e.g. "45min" or "1:37h")
            if (s.durationText != null) {
                c.drawText(s.durationText, x, base2, headerMetaPaint);
                x += headerMetaPaint.measureText(s.durationText);
                needDot = true;
            }

            // 2. Dates (e.g. "2009-2016", "2021-Now", "2023", "Jan 2026")
            if (s.datesText != null) {
                if (needDot) {
                    c.drawText(SEP_DOT, x, base2, headerMetaPaint);
                    x += sepDotWidth;
                }
                c.drawText(s.datesText, x, base2, headerMetaPaint);
                x += headerMetaPaint.measureText(s.datesText);
                needDot = true;
            }

            // Ratings block: strict order IMDb -> Metacritic -> Rotten Tomatoes, separated by spaces (never cdots)
            boolean hasDrawnRating = false;

            // 3. IMDb (Letter Text e.g. "IMDb 8.5")
            if (s.hasImdb && s.imdbScoreText != null) {
                if (needDot) {
                    c.drawText(SEP_DOT, x, base2, headerMetaPaint);
                    x += sepDotWidth;
                    needDot = false;
                }
                c.drawText(s.imdbScoreText, x, base2, headerMetaPaint);
                x += headerMetaPaint.measureText(s.imdbScoreText);
                hasDrawnRating = true;
            }

            // 4. Metacritic (Procedural Vector Icon + "7.8")
            if (s.hasMc && s.mcScoreText != null) {
                if (hasDrawnRating) {
                    x += ratingSepWidth;
                } else if (needDot) {
                    c.drawText(SEP_DOT, x, base2, headerMetaPaint);
                    x += sepDotWidth;
                    needDot = false;
                }
                float iconW = Symbols.width(getContext(), "mc", iconPointSize);
                float cx = x + iconW / 2f;
                Symbols.draw(getContext(), c, "mc", cx, cy, iconPointSize, iconColor);
                x += iconW + 6 * k;
                c.drawText(s.mcScoreText, x, base2, headerMetaPaint);
                x += headerMetaPaint.measureText(s.mcScoreText);
                hasDrawnRating = true;
            }

            // 5. Rotten Tomatoes (Procedural Vector Icon + "94%")
            if (s.hasRt && s.rtScoreText != null) {
                if (hasDrawnRating) {
                    x += ratingSepWidth;
                } else if (needDot) {
                    c.drawText(SEP_DOT, x, base2, headerMetaPaint);
                    x += sepDotWidth;
                    needDot = false;
                }
                float iconW = Symbols.width(getContext(), "rt", iconPointSize);
                float cx = x + iconW / 2f;
                Symbols.draw(getContext(), c, "rt", cx, cy, iconPointSize, iconColor);
                x += iconW + 6 * k;
                c.drawText(s.rtScoreText, x, base2, headerMetaPaint);
                x += headerMetaPaint.measureText(s.rtScoreText);
            }
        }

        // --- Row 3: Genres (baseline = 256 * k) ---
        boolean hasGenres = s.genresText != null && !s.genresText.isEmpty() && headerGenrePaint != null;
        if (hasGenres) {
            float base3 = 256 * k;
            c.drawText(s.genresText, left, base3, headerGenrePaint);
        }

        // --- Separate Gray Continue Watching Status Line (e.g. "Continue Watching S1E10") ---
        // (Status text line removed from header; episode info is displayed directly on Continue Watching cards)
        boolean hasContinueStatus = false;

        // --- Row 4: Multi-Line Summary with Slow Vertical (Up-Down) Auto-Scroll ---
        if (s.overviewText != null && !s.overviewText.isEmpty() && overviewPaint != null && s.overviewLayout != null) {
            float boxTop = effectiveOverviewBoxTop(s) * k;
            float boxBottom = OVERVIEW_BOX_BOTTOM * k;
            float boxRight = left + s.overviewLayoutWidth + 8f * k;
            float scrollY = (allowMarquee && overviewActive) ? overviewScrollY : 0f;

            if (!allowMarquee || overviewMaxScroll <= 0f) {
                overviewPaint.setShader(null);
                c.save();
                c.translate(left, boxTop);
                s.overviewLayout.draw(c);
                c.restore();
            } else {
                c.save();
                c.clipRect(left - 4f * k, boxTop, boxRight, boxBottom);
                boolean hasTopFade = scrollY > 2f * k;
                boolean hasBottomFade = scrollY < overviewMaxScroll - 2f * k;

                LinearGradient activeGrad = null;
                if (alpha >= 0.999f) {
                    if (hasContinueStatus && overviewFadeBothCompact != null) {
                        if (hasTopFade && hasBottomFade) activeGrad = overviewFadeBothCompact;
                        else if (hasBottomFade) activeGrad = overviewFadeBottomCompact;
                        else if (hasTopFade) activeGrad = overviewFadeTopCompact;
                    } else {
                        if (hasTopFade && hasBottomFade) activeGrad = overviewFadeBoth;
                        else if (hasBottomFade) activeGrad = overviewFadeBottom;
                        else if (hasTopFade) activeGrad = overviewFadeTop;
                    }
                }
                if (activeGrad != null) {
                    overviewShaderMatrix.setTranslate(0f, scrollY);
                    activeGrad.setLocalMatrix(overviewShaderMatrix);
                    overviewPaint.setShader(activeGrad);
                } else {
                    overviewPaint.setShader(null);
                }

                c.translate(left, boxTop - scrollY);
                s.overviewLayout.draw(c);
                overviewPaint.setShader(null);
                c.restore();
            }
        }

        if (alpha < 0.999f) {
            if (headerTitlePaint != null) headerTitlePaint.setAlpha(origTitleAlpha);
            if (headerMetaPaint != null) headerMetaPaint.setAlpha(origMetaAlpha);
            if (headerGenrePaint != null) headerGenrePaint.setAlpha(origGenreAlpha);
            if (headerContinuePaint != null) headerContinuePaint.setAlpha(origContinueAlpha);
            if (overviewPaint != null) overviewPaint.setAlpha(origOverviewAlpha);
        }
    }

    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == Z_TOP && i >= 0 && i < TOP_BAR_COUNT) {
            zone = Z_TOP;
            topIndex = i;
            selectScreen(i);
            retarget(false);
            click.move();
        } else if (group == Z_SHELF && i >= 0 && i < shelf.size()) {
            openSideShelf();
            shelfIndex = i;
            retarget(false);
            click.move();
        }
    }

    private static String label(List<HomeApps.Item> list, int i) {
        if (i < 0 || i >= list.size() || list.get(i).label == null) return "";
        HomeApps.Item it = list.get(i);
        if (it.kind == HomeApps.SMART_DEVICE && it.smartDevice != null) {
            return it.smartDevice.getName() + ", " + it.smartDevice.getStatusText();
        }
        return it.label;
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    // MARK: - Top-Bar (7 Screens) & Full-Screen Multi-Row Browser

    private void layoutLight() {
        if (lTab == null) return;
        String[] labels = topBarLabels();
        float tabTop = 36 * k, tabBottom = 94 * k;
        float padX = 20 * k, iconSize = 24 * k, gap = 12 * k;
        float x = MARGIN * k;
        boolean[] mask = FeatureTabs.currentMask(getContext());
        for (int i = 0; i < TOP_BAR_COUNT; i++) {
            if (!mask[i]) {
                topRects[i].setEmpty();
                continue;
            }
            String icon = TOP_BAR_ICONS[i];
            Symbols.warm(getContext(), icon, iconSize);
            float iw = Symbols.width(getContext(), icon, iconSize);
            float tw = Math.max(lTabOn.measureText(labels[i]), lTab.measureText(labels[i]));
            float pillW = padX + iw + 10 * k + tw + padX;
            topRects[i].set(x, tabTop, x + pillW, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + gap;
        }
        if (Prefs.statusIcons()) {
            for (String name : STATUS_SYMBOLS) {
                Symbols.warm(getContext(), name, Symbols.pointSizeForHeight(getContext(), name, L_STATUS * k * statusScale(name)));
            }
        }
        for (String sym : new String[] {"search", "mic", "home", "apps", "gamepad", "tiktok", "lightbulb", "settings", "tv", "sparkles"}) {
            Symbols.warm(getContext(), sym, 32 * k);
        }
        if (topIndex >= 0 && topIndex < TOP_BAR_COUNT && !topRects[topIndex].isEmpty()
                && topPill.left.value == 0 && topPill.right.value == 0) {
            topPill.retarget(topRects[topIndex], !sheetOpen && zone == Z_TOP, true);
        }
        heroDirty = statusDirty = true;
    }

    private void layoutCards() {
        // Handled dynamically by drawBrowserRows
    }

    private void rebuildScreenRows() {
        browserRows.clear();
        Context c = getContext().getApplicationContext();
        switch (activeScreen) {
            case SCREEN_HOME: {
                // Row 0: Continue Watching (Mixed between TV Shows and Movies, ordered by last-watched; Trakt progress is already merged & filtered in WatchNext.load)
                browserRows.add(new BrowserRow(ROW_CONTINUE, Ui.tr("Continue Watching"), null,
                        allWatching, java.util.Collections.emptyList(), null, null, null, watchLoaded));

                // Row 1: Right below Continue Watching, user chooses TV Shows (default, 0) or Movies (1)
                browserRows.add(new BrowserRow(ROW_MEDIA_SELECTOR, Ui.tr("Content Type"), null,
                        null, null, null, null, null));

                String subKind = (subTab == 0) ? "tv" : "movie";
                String mediaLabel = (subTab == 0) ? Ui.tr("TV Shows") : Ui.tr("Movies");

                // Row 2: Watch List (filtered by TV Shows / Movies)
                List<Recs.Pick> watchListPicks = recs.now(Recs.WATCHLIST, subKind, "All");
                if (!watchListPicks.isEmpty()) {
                    browserRows.add(new BrowserRow(ROW_PICKS, Ui.tr("Watch List") + " · " + mediaLabel, null, Recs.WATCHLIST,
                            null, watchListPicks, null, null, null, true, SCREEN_HOME));
                } else {
                    List<ActionCard> wlCards = new ArrayList<>();
                    if (!StremioAccountClient.isConfigured(c)) {
                        wlCards.add(new ActionCard("stremio_connect",
                                Ui.tr("Connect Stremio Account (Library Sync)"),
                                Ui.tr("Sign in to Stremio to sync your Stremio Library & Continue Watching"),
                                "tv", 0xFF7C3AED, 0xFF4C1D95, null));
                    }
                    if (Trakt.available() && !Trakt.hasOAuth(c)) {
                        wlCards.add(new ActionCard("trakt_connect",
                                Ui.tr("Connect Private Trakt Account (trakt.tv/activate)"),
                                Ui.tr("Authenticate OAuth to sync your private Watch List, Ratings, Favorites & History"),
                                "tv", 0xFFDC2626, 0xFF7F1D1D, null));
                    }
                    if (!wlCards.isEmpty()) {
                        browserRows.add(new BrowserRow(ROW_ACTIONS, Ui.tr("Watch List") + " · " + mediaLabel, null,
                                null, null, null, wlCards, null));
                    }
                }

                // Row 3: Recommended (filtered by TV Shows / Movies)
                List<Recs.Pick> topPicks = recs.now(Recs.EXPLORE, subKind, "All");
                if (!topPicks.isEmpty()) {
                    browserRows.add(new BrowserRow(ROW_PICKS, Ui.tr("Recommended") + " · " + mediaLabel, null, Recs.EXPLORE,
                            null, topPicks, null, null, null, true, SCREEN_HOME));
                }

                // Row 4: Trending (filtered by TV Shows / Movies)
                List<Recs.Pick> trendingPicks = recs.now(Recs.TRENDING, subKind, "All");
                if (!trendingPicks.isEmpty()) {
                    browserRows.add(new BrowserRow(ROW_PICKS, Ui.tr("Trending") + " · " + mediaLabel, null, Recs.TRENDING,
                            null, trendingPicks, null, null, null, true, SCREEN_HOME));
                }

                // Rows 5+: Genre Rows (separate saved order & visibility for TV Shows and Movies; Hold OK to reorder)
                List<String> orderedGenres = Prefs.visibleGenreOrder(subKind);
                activeGenres = new ArrayList<>();
                for (String genre : orderedGenres) {
                    List<Recs.Pick> genrePicks = recs.genreRow(subKind, genre);
                    if (!genrePicks.isEmpty()) {
                        activeGenres.add(genre);
                        browserRows.add(new BrowserRow(ROW_GENRE, Ui.tr(genre), genre, "genre",
                                null, genrePicks, null, null, null));
                    }
                }
                break;
            }
            case SCREEN_GAMING: {
                if (gamingStatus == null) {
                    gamingStatus = GamingZone.getCachedLaptopStatus(c);
                }
                showHiddenGamesInline = gamingRenderer.buildGamingScreenRows(
                        c, gamingStatus, showHiddenGamesInline, browserRows);
                break;
            }
            case SCREEN_SEARCH: {
                List<ActionCard> searchCards = new ArrayList<>();
                String barTitle = searchQuery.isEmpty()
                        ? Ui.tr("Search shows, movies, YouTube, apps, or settings…")
                        : (Ui.tr("Search: ") + searchQuery);
                searchCards.add(new ActionCard("search_bar", barTitle,
                        Ui.tr("Press OK to type with keyboard"), "search",
                        0xFF2563EB, 0xFF1E3A8A, null));
                searchCards.add(new ActionCard("search_voice", Ui.tr("Voice Search"),
                        Ui.tr("Speak into remote microphone"), "mic",
                        0xFF7C3AED, 0xFF4C1D95, null));
                if (!searchQuery.isEmpty()) {
                    searchCards.add(new ActionCard("search_clear", Ui.tr("Clear Search"),
                            Ui.tr("Reset search results"), "settings",
                            0xFF475569, 0xFF1E293B, null));
                }
                browserRows.add(new BrowserRow(ROW_ACTIONS, Ui.tr("Search"), null,
                        null, null, null, searchCards, null));

                if (!searchQuery.isEmpty()) {
                    if (!searchMediaResults.isEmpty()) {
                        browserRows.add(new BrowserRow(ROW_PICKS, Ui.tr("Shows & Movies in Installed Apps"), null, null,
                                null, searchMediaResults, null, null, null, true, SCREEN_SEARCH));
                    }
                    if (!searchYoutubeResults.isEmpty()) {
                        browserRows.add(new BrowserRow(ROW_ACTIONS, Ui.tr("YouTube Videos"), null,
                                null, null, null, searchYoutubeResults, null));
                    }
                    if (!searchAppAndSettingResults.isEmpty()) {
                        browserRows.add(new BrowserRow(ROW_ACTIONS, Ui.tr("Installed Apps & TV Settings"), null,
                                null, null, null, searchAppAndSettingResults, null));
                    }
                }
                break;
            }
            case SCREEN_REELS: {
                ReelsRepository reelsRepo = ReelsRepository.get(c);
                List<String> connectedSources = reelsRepo.getConnectedSourceIds();
                int wlCount = reelsRepo.loadWatchLater().size();
                int cachedCount = reelsRepo.loadCachedFeedItems().size();
                List<ActionCard> reelCards = new ArrayList<>();
                String feedSub;
                if (connectedSources.isEmpty()) {
                    feedSub = Ui.tr("Press OK or Down to connect Instagram & TikTok");
                } else if (cachedCount > 0) {
                    feedSub = cachedCount + " " + Ui.tr("unwatched · Oldest first");
                } else {
                    feedSub = Ui.tr("Instant full-screen vertical & 16:9 feed");
                }
                reelCards.add(new ActionCard("reel_open_feed",
                        Ui.tr("Watch Reels Feed"), feedSub, "tiktok",
                        0xFF2563EB, 0xFF1D4ED8, null));
                reelCards.add(new ActionCard("reel_open_watch_later",
                        Ui.tr("Watch Later Folder"),
                        wlCount > 0 ? (wlCount + " " + Ui.tr("saved videos")) : Ui.tr("No saved videos yet"),
                        "folder", 0xFF4F46E5, 0xFF3730A3, null));
                reelCards.add(new ActionCard("reel_open_setup",
                        Ui.tr("TV Accounts & Family Names"),
                        connectedSources.isEmpty()
                                ? Ui.tr("Connect Instagram & TikTok group chats")
                                : (connectedSources.size() + " " + Ui.tr("connected · Rename senders")),
                        "users", 0xFF2C2D31, 0xFF1F2430, null));
                browserRows.add(new BrowserRow(ROW_ACTIONS, Ui.tr("Reels & TikTok"), null,
                        null, null, null, reelCards, null));
                break;
            }
            case SCREEN_SMARTHOME: {
                smartHomeRenderer.buildSmartHomeScreenRows(c, drawerItems, browserRows);
                break;
            }
            case SCREEN_SETTINGS: {
                rebuildSettingsTree();
                break;
            }
            default:
                break;
        }
        if (!browserRows.isEmpty()) {
            for (BrowserRow br : browserRows) {
                br.scrollX = (br.kind == ROW_MEDIA_SELECTOR) ? 0f : rowScrollMemory.restoreScrollX(rowMemoryKey(br), 0f);
            }
            homeRow = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
            BrowserRow r = browserRows.get(homeRow);
            if (r.kind == ROW_MEDIA_SELECTOR) homeCol = subTab;
            else homeCol = Math.max(0, Math.min(homeCol, Math.max(0, r.count() - 1)));
            prefetchLeadRowRatings();
        }
    }

    private void prefetchLeadRowRatings() {
        List<String> pinnedHeroKeys = new ArrayList<>(4);
        for (int rIdx = 0; rIdx < browserRows.size(); rIdx++) {
            BrowserRow br = browserRows.get(rIdx);
            if (br.kind == ROW_CONTINUE && br.watchItems != null && !br.watchItems.isEmpty()) {
                WatchNext.Item leadWatch = br.watchItems.get(0);
                String wk = recs.watchBackdropKey(leadWatch);
                if (wk != null && pinnedHeroKeys.size() < 4) {
                    pinnedHeroKeys.add(wk);
                }
                recs.preloadWatchBackdropAsync(leadWatch);
                continue;
            }
            if (br.kind != ROW_PICKS && br.kind != ROW_GENRE) continue;
            if (!br.pickItems.isEmpty() && pinnedHeroKeys.size() < 4) {
                Recs.Pick leadPick = br.pickItems.get(0);
                if (leadPick != null && leadPick.backdrop != null && !leadPick.backdrop.isEmpty()) {
                    pinnedHeroKeys.add(leadPick.backdrop);
                    recs.preloadBackdropAsync(leadPick);
                }
            }
            int lim = Math.min(4, br.pickItems.size());
            for (int i = 0; i < lim; i++) {
                recs.ensureRatingsAsync(br.pickItems.get(i));
            }
        }
        recs.setPinnedBackdropKeys(pinnedHeroKeys);
    }

    private void updateCards() {
        cardSlots = Prefs.cards();
        rebuildScreenRows();
        if (activeScreen == SCREEN_HOME) {
            if (!browserRows.isEmpty() && homeRow == 0) {
                BrowserRow row = browserRows.get(0);
                if (row.kind == ROW_CONTINUE && homeCol >= 0 && homeCol < row.watchItems.size()) {
                    Backdrop wb = recs.backdropForWatchItem(row.watchItems.get(homeCol), watchArt);
                    if (wb != null) {
                        showBackdrop(wb);
                    }
                    retarget(false);
                    heroDirty = true;
                    spoken.focusMoved();
                    return;
                }
            }
            Recs.Pick focusPick = focusedPickOnHome();
            if (focusPick != null) {
                Backdrop b = recs.backdrop(focusPick);
                if (b != null) showBackdrop(b);
            }
        } else if (activeScreen == SCREEN_GAMING) {
            bgFollow.run();
        }
        retarget(false);
        heroDirty = true;
        spoken.focusMoved();
    }

    // MARK: - Continue watching & media helpers

    private boolean picksMode() {
        return topMode.equals(Prefs.TOP_PICKS) && picksOn();
    }

    private boolean clockMode() {
        return false;
    }

    private boolean continueTab() {
        return activeScreen == SCREEN_HOME && homeRow == 0;
    }

    private boolean continueMode() {
        return activeScreen == SCREEN_HOME && homeRow == 0;
    }

    private List<WatchNext.Item> filterWatchingBySubTab(List<WatchNext.Item> src) {
        return src == null ? new ArrayList<>() : src;
    }

    private void setTopContent(String mode, List<WatchNext.Item> list, boolean allowed) {
        if (!hiddenNow.isEmpty()) {
            List<WatchNext.Item> shown = new ArrayList<>(list.size());
            for (WatchNext.Item it : list) {
                if (!hiddenNow.contains(WatchNext.hideKey(it))
                        && !hiddenNow.contains(WatchNext.rawHideKey(it))
                        && !hiddenNow.contains(WatchNext.titleHideKey(it))) {
                    shown.add(it);
                }
            }
            list = shown;
        }
        if (watchLoaded && mode.equals(topMode) && allowed == watchAllowed && sameWatching(allWatching, list)) {
            return;
        }
        allWatching = list;
        watching = list;
        watchAllowed = allowed;
        watchLoaded = true;
        topMode = mode;
        rebuildScreenRows();
        retarget(false);
        heroDirty = true;
        recs.ensureContinueWatchingEpisodeTitlesAsync(list, () -> {
            heroDirty = true;
            heroLayer.invalidate();
            wake();
        });
    }

    static boolean sameWatching(List<WatchNext.Item> a, List<WatchNext.Item> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WatchNext.Item x = a.get(i), y = b.get(i);
            if (x == y) continue;
            if (x == null || y == null) return false;
            if (!java.util.Objects.equals(x.pkg, y.pkg) || !java.util.Objects.equals(x.title, y.title)
                    || x.position != y.position || x.duration != y.duration
                    || !java.util.Objects.equals(x.episode, y.episode)
                    || !java.util.Objects.equals(x.episodeTitle, y.episodeTitle)
                    || !java.util.Objects.equals(x.intent, y.intent)
                    || !java.util.Objects.equals(x.picture(), y.picture())) return false;
        }
        return true;
    }

    static boolean isStremioPackage(String pkg) {
        return "com.stremio.tv".equals(pkg) || "com.stremio.one".equals(pkg);
    }

    private void watchRect(int i, RectF out) {
        float step = (WatchArt.W + WATCH_GAP) * k;
        float x = MARGIN * k + i * step - watchScroll.value * step, y = L_CARDS_TOP * k;
        out.set(x, y, x + WatchArt.W * k, y + WatchArt.H * k);
    }

    private static String timeLeft(WatchNext.Item it) {
        if (it.progress() < 0) return null;
        long min = Math.max(1, (it.duration - it.position) / 60_000);
        return min >= 60 ? Ui.trf("{h} h {m} min left", "h", min / 60, "m", min % 60) : Ui.trf("{n} min left", "n", min);
    }

    private float watchLeftEdge() {
        return (MARGIN - 32) * k;
    }

    private static int readable(int ink, int bg) {
        float lb = luminance(bg);
        int target = lb > 0.18f ? 0xFF000000 : 0xFFFFFFFF;
        for (int i = 0; i < 10; i++) {
            int c = blend(ink, target, i / 10f);
            float lc = luminance(blend(bg, c | 0xFF000000, (c >>> 24) / 255f));
            if ((Math.max(lb, lc) + 0.05f) / (Math.min(lb, lc) + 0.05f) >= 4.5f) return c;
        }
        return target;
    }

    private static float luminance(int c) {
        return 0.2126f * linear((c >> 16) & 255) + 0.7152f * linear((c >> 8) & 255) + 0.0722f * linear(c & 255);
    }

    private static float linear(int v) {
        float x = v / 255f;
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4);
    }

    static int watchingIntentFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
    }

    static String resolveWatchingUri(WatchNext.Item it) {
        if (it == null) return null;
        String uri = it.intent;
        if (uri != null && uri.startsWith("stremio:///detail/series/") && it.seasonNumber > 0 && it.episodeNumber > 0) {
            String path = uri.substring("stremio:///detail/series/".length());
            if (!path.contains("/")) {
                return uri + "/" + path + ":" + it.seasonNumber + ":" + it.episodeNumber;
            }
        }
        return uri;
    }

    private void openWatching(WatchNext.Item it) {
        if (it == null) return;
        String uri = resolveWatchingUri(it);
        Intent link = uri == null ? null : linkIntent(uri, it.pkg);
        if (link != null) {
            link.addFlags(watchingIntentFlags());
            try {
                activity.startActivity(link);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                if (isStremioPackage(it.pkg)) {
                    String altPkg = "com.stremio.tv".equals(it.pkg) ? "com.stremio.one" : "com.stremio.tv";
                    link.setPackage(altPkg);
                    try {
                        activity.startActivity(link);
                        return;
                    } catch (ActivityNotFoundException | SecurityException e2) {
                        AppLog.w("continue watching alternate link: " + e2.getMessage());
                    }
                }
                AppLog.w("continue watching link: " + e.getMessage());
            }
        }
        if (!openApp(it)) showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
    }

    static Intent linkIntent(String uri, String pkg) {
        if (HomeMode.PKG.equals(pkg)) return null;
        Intent intent;
        try {
            intent = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {
            AppLog.w("continue watching link: " + e.getMessage());
            return null;
        }
        if (intent.getComponent() != null && !intent.getComponent().getPackageName().equals(pkg)
                && !(isStremioPackage(pkg) && isStremioPackage(intent.getComponent().getPackageName()))) return null;
        intent.setSelector(null);
        if (pkg != null) intent.setPackage(pkg);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return intent;
    }

    private static String appName(WatchNext.Item it) {
        return it.app != null && !it.app.isEmpty() ? it.app : it.pkg;
    }

    private boolean openApp(WatchNext.Item it) {
        Intent launch = HomeApps.launchIntent(activity.getPackageManager(), it.pkg);
        if (launch == null && isStremioPackage(it.pkg)) {
            String altPkg = "com.stremio.tv".equals(it.pkg) ? "com.stremio.one" : "com.stremio.tv";
            launch = HomeApps.launchIntent(activity.getPackageManager(), altPkg);
        }
        if (launch == null) return false;
        try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.pkg + ": " + e.getMessage());
            return false;
        }
    }

    // MARK: - Continue Watching & Recommendation Cards: the menu of a held card

    private boolean canHold() {
        if (sheetOpen) return false;
        if (sideShelfOpen && zone == Z_SHELF) {
            return !shelf.isEmpty() && shelfIndex >= 0 && shelfIndex < shelf.size();
        }
        if (activeScreen == SCREEN_APPS && zone == Z_CARDS) {
            if (gridIndex < 0 || gridIndex >= allAppsList.size()) return false;
            HomeApps.Item it = allAppsList.get(gridIndex);
            return it != null && it.kind == HomeApps.APP && it.pkg != null;
        }
        if (sideShelfOpen || zone != Z_CARDS || browserRows.isEmpty()) return false;
        if (homeRow < 0 || homeRow >= browserRows.size()) return false;
        BrowserRow row = browserRows.get(homeRow);
        if (row.kind == ROW_CONTINUE) {
            return homeCol < row.watchItems.size() + row.pickItems.size();
        }
        if (row.kind == ROW_GAMES) {
            if (homeCol < 0 || homeCol >= row.gameItems.size()) return false;
            return !GamingZone.HIDDEN_TILE_ID.equals(row.gameItems.get(homeCol).id);
        }
        if (row.kind == ROW_SMARTHOME) {
            if (homeCol < 0 || homeCol >= row.smartItems.size()) return false;
            HomeApps.Item it = row.smartItems.get(homeCol);
            // Only Smart-Home devices open the interactive Device Control Card; TV Inputs & Sources directly reorder
            return it != null && it.kind == HomeApps.SMART_DEVICE && it.smartDevice != null;
        }
        return row.kind == ROW_PICKS && homeCol < row.pickItems.size();
    }

    private void openSmartDeviceControlCard(SmartHomeManager.Device dev) {
        if (dev == null || menuTitlePaint == null) return;
        float width = (MENU_W - 2 * MENU_PAD) * k;
        menuItem = null;
        menuPickCard = null;
        menuGameItem = null;
        menuSmartItem = null;
        menuShelfApp = null;
        menuGridApp = null;
        smartCardOpen = true;
        menuRenderSmartCard = true;
        smartCardDevice = dev;
        smartCardSession.device = dev;
        smartCardSession.roomIndex = 0;
        smartCardSession.bulbIndex = 0;
        refreshSmartCardRows();
        menuTitle = TextUtils.ellipsize(dev.getName(), new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
        menuSub = TextUtils.ellipsize(dev.getStatusText(), new TextPaint(menuSubPaint), width, TextUtils.TruncateAt.END).toString();
        menuOptionCount = smartCardRowCount;
        layoutMenu();
        menuOpen = true;
        menuIndex = 0;
        menuPick.snap(0);
        menu.target = 1;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
        wake();
        click.open();
        spoken.focusMoved();
    }

    private void refreshSmartCardRows() {
        smartCardSession.device = smartCardDevice;
        smartCardRowCount = smartHomeRenderer.populateSmartDeviceControlRows(
                getContext(), smartCardSession, smartCardLabels, smartCardValues, menuIcons);
        menuOptionCount = smartCardRowCount;
    }

    private void adjustSmartCardValue(int dir) {
        if (smartCardDevice == null || dir == 0) return;
        smartCardSession.device = smartCardDevice;
        smartHomeRenderer.adjustSmartDeviceValue(getContext(), smartCardSession, menuIndex, dir, (ok, msg) -> {
            showToast(msg);
            homeChanged();
            rebuildScreenRows();
            heroDirty = true;
            heroLayer.invalidate();
            wake();
        });
        refreshSmartCardRows();
        menuIndex = Math.max(0, Math.min(menuIndex, Math.max(0, smartCardRowCount - 1)));
        menuPick.target = menuIndex;
        float width = (MENU_W - 2 * MENU_PAD) * k;
        menuSub = TextUtils.ellipsize(smartCardDevice.getStatusText(), new TextPaint(menuSubPaint), width, TextUtils.TruncateAt.END).toString();
        layoutMenu();
        menuLayer.invalidate();
        wake();
        click.move();
    }

    private void showMenu() {
        if (menuOpen || !canHold() || menuTitlePaint == null) return;
        float width = (MENU_W - 2 * MENU_PAD) * k;
        smartCardOpen = false;
        menuRenderSmartCard = false;
        smartCardDevice = null;
        menuItem = null;
        menuPickCard = null;
        menuPickRowKind = null;
        menuGameItem = null;
        menuSmartItem = null;
        menuShelfApp = null;
        menuGridApp = null;
        if (sideShelfOpen && zone == Z_SHELF) {
            menuShelfApp = shelf.get(shelfIndex);
            menuTitle = TextUtils.ellipsize(menuShelfApp.label == null ? "" : menuShelfApp.label,
                    new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
            menuSub = Ui.tr("Favorite Apps Shelf");
            menuOptions[0] = Ui.tr("Reorder App");
            menuIcons[0] = "reorder";
            menuOptions[1] = Ui.tr("Remove from Shelf (Unfavorite)");
            menuIcons[1] = "star_off";
            menuOptions[2] = Ui.tr("Cancel");
            menuIcons[2] = "close";
            menuOptionCount = 3;
        } else if (activeScreen == SCREEN_APPS && zone == Z_CARDS) {
            menuGridApp = allAppsList.get(gridIndex);
            menuTitle = TextUtils.ellipsize(menuGridApp.label == null ? "" : menuGridApp.label,
                    new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
            if (menuGridApp.isHiddenItem) {
                menuSub = Ui.tr("Hidden App");
                menuOptions[0] = Ui.tr("Unhide App");
                menuIcons[0] = "eye";
                menuOptions[1] = Ui.tr("Permanently Hide App");
                menuIcons[1] = "ban";
                menuOptions[2] = Ui.tr("Uninstall App");
                menuIcons[2] = "trash";
                menuOptions[3] = Ui.tr("Cancel");
                menuIcons[3] = "close";
                menuOptionCount = 4;
            } else {
                boolean inShelf = false;
                for (HomeApps.Item s : shelf) {
                    if (s != null && menuGridApp.pkg.equals(s.pkg)) {
                        inShelf = true;
                        break;
                    }
                }
                menuSub = inShelf ? Ui.tr("On Favorite Apps Shelf") : Ui.tr("Installed App");
                menuOptions[0] = Ui.tr("Reorder App");
                menuIcons[0] = "reorder";
                menuOptions[1] = inShelf ? Ui.tr("Remove from Shelf (Unfavorite)") : Ui.tr("Add to Shelf (Favorite)");
                menuIcons[1] = inShelf ? "star_off" : "star";
                menuOptions[2] = Ui.tr("Hide App");
                menuIcons[2] = "eye_off";
                menuOptions[3] = Ui.tr("Permanently Hide App");
                menuIcons[3] = "ban";
                menuOptions[4] = Ui.tr("Uninstall App");
                menuIcons[4] = "trash";
                menuOptions[5] = Ui.tr("Cancel");
                menuIcons[5] = "close";
                menuOptionCount = 6;
            }
        } else {
            BrowserRow row = browserRows.get(homeRow);
            if (row.kind == ROW_GAMES && homeCol < row.gameItems.size()) {
                menuGameItem = row.gameItems.get(homeCol);
                menuTitle = TextUtils.ellipsize(menuGameItem.title, new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
                if (menuGameItem.hidden) {
                    menuSub = Ui.tr("Hidden Game");
                    menuOptions[0] = Ui.tr("Unhide Game");
                    menuIcons[0] = "eye";
                    menuOptions[1] = Ui.tr("Permanently Hide Game");
                    menuIcons[1] = "ban";
                    menuOptions[2] = Ui.tr("Cancel");
                    menuIcons[2] = "close";
                    menuOptionCount = 3;
                } else {
                    menuSub = "steam".equalsIgnoreCase(menuGameItem.category) ? "Steam" : "PC Game";
                    menuOptions[0] = Ui.tr("Reorder Game");
                    menuIcons[0] = "reorder";
                    menuOptions[1] = Ui.tr("Hide from Games Library");
                    menuIcons[1] = "eye_off";
                    menuOptions[2] = Ui.tr("Permanently Hide Game");
                    menuIcons[2] = "ban";
                    menuOptions[3] = Ui.tr("Cancel");
                    menuIcons[3] = "close";
                    menuOptionCount = 4;
                }
            } else if (row.kind == ROW_SMARTHOME && homeCol < row.smartItems.size()) {
                HomeApps.Item smartItem = row.smartItems.get(homeCol);
                if (smartItem != null && smartItem.smartDevice != null) {
                    openSmartDeviceControlCard(smartItem.smartDevice);
                }
                return;
            } else if (row.kind == ROW_CONTINUE && homeCol < row.watchItems.size()) {
                menuItem = row.watchItems.get(homeCol);
                String title = menuItem.title + (menuItem.episode != null ? " · " + menuItem.episode : "");
                menuTitle = TextUtils.ellipsize(title, new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
                String appLabel = (menuItem.app == null || menuItem.app.equalsIgnoreCase("Stremio")) ? "" : menuItem.app;
                menuSub = appLabel.isEmpty() ? "" : TextUtils.ellipsize(appLabel, new TextPaint(menuSubPaint), width,
                        TextUtils.TruncateAt.END).toString();
                menuOptions[0] = Ui.tr("Hide from Continue Watching");
                menuIcons[0] = "eye_off";
                menuOptions[1] = Ui.tr("Open the app");
                menuIcons[1] = "tv";
                menuOptions[2] = Ui.tr("Cancel");
                menuIcons[2] = "close";
                menuOptionCount = 3;
            } else {
                int idx = row.kind == ROW_CONTINUE ? (homeCol - row.watchItems.size()) : homeCol;
                if (idx < 0 || idx >= row.pickItems.size()) return;
                menuPickCard = row.pickItems.get(idx);
                menuPickRowKind = row.pickKind;
                menuTitle = TextUtils.ellipsize(menuPickCard.title == null ? "" : menuPickCard.title,
                        new TextPaint(menuTitlePaint), width, TextUtils.TruncateAt.END).toString();
                String sub = Recs.ratingsSubtitle(menuPickCard);
                menuSub = sub == null || sub.isEmpty() ? "" : TextUtils.ellipsize(sub, new TextPaint(menuSubPaint), width,
                        TextUtils.TruncateAt.END).toString();
                if (Recs.WATCHLIST.equals(menuPickRowKind)) {
                    menuOptions[0] = menuPickCard.watched ? Ui.tr("Mark as Unwatched") : Ui.tr("Mark as Watched");
                    menuIcons[0] = "check";
                    menuOptions[1] = Ui.tr("Reorder");
                    menuIcons[1] = "reorder";
                    menuOptions[2] = Ui.tr("Sort by Similarity");
                    menuIcons[2] = "sliders";
                    menuOptions[3] = Ui.tr("Thumbs Up");
                    menuIcons[3] = "thumb_up";
                    menuOptions[4] = Ui.tr("Thumbs Down");
                    menuIcons[4] = "thumb_down";
                    menuOptions[5] = Ui.tr("Remove from Watch List");
                    menuIcons[5] = "trash";
                    menuOptions[6] = Ui.tr("Cancel");
                    menuIcons[6] = "close";
                    menuOptionCount = 7;
                } else {
                    menuOptions[0] = Ui.tr("Mark as Watched");
                    menuIcons[0] = "check";
                    menuOptions[1] = Ui.tr("Thumbs Up (More like this)");
                    menuIcons[1] = "thumb_up";
                    menuOptions[2] = Ui.tr("Thumbs Down (Not for me)");
                    menuIcons[2] = "thumb_down";
                    menuOptions[3] = Ui.tr("Add to Watch List");
                    menuIcons[3] = "bookmark_add";
                    menuOptions[4] = Ui.tr("Cancel");
                    menuIcons[4] = "close";
                    menuOptionCount = 5;
                }
            }
        }
        layoutMenu();
        menuOpen = true;
        menuIndex = 0;
        menuPick.snap(0);
        menu.target = 1;
        menuLayer.setVisibility(VISIBLE);
        menuLayer.invalidate();
        wake();
        click.open();
        spoken.focusMoved();
    }

    private void layoutMenu() {
        menuOptionsTop = modalRenderer.layoutMenu(w, h, k, menuOptionCount, !menuSub.isEmpty(), menuPanel);
    }

    private void menuRow(int i, RectF out) {
        modalRenderer.menuRow(i, k, menuPanel, menuOptionsTop, out);
    }

    private void closeMenu(boolean snap) {
        if (!menuOpen && !snap) return;
        boolean was = menuOpen;
        menuOpen = false;
        smartCardOpen = false;
        smartCardDevice = null;
        menu.target = 0;
        if (snap) {
            menu.snap(0);
            menuLayer.setVisibility(INVISIBLE);
        }
        menuLayer.invalidate();
        wake();
        if (was) spoken.focusMoved();
    }

    private boolean menuKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveMenu(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveMenu(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && smartCardOpen) adjustSmartCardValue(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && smartCardOpen) adjustSmartCardValue(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) {
                        if (smartCardOpen) {
                            if (smartCardDevice != null
                                    && smartCardDevice.getProtocol() == SmartHomeManager.Protocol.WIZ_LIGHT
                                    && menuIndex == 1) {
                                adjustSmartCardValue(1);
                            } else {
                                closeMenu(false);
                                click.open();
                            }
                        } else {
                            pickMenu();
                        }
                    }
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closeMenu(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void moveMenu(int d) {
        int next = Math.max(0, Math.min(menuOptionCount - 1, menuIndex + d));
        if (next == menuIndex) return;
        menuIndex = next;
        menuPick.target = next;
        menuLayer.invalidate();
        wake();
        click.move();
        spoken.focusMoved();
    }

    private void pickMenu() {
        WatchNext.Item it = menuItem;
        Recs.Pick pickCard = menuPickCard;
        GamingZone.GameItem gameCard = menuGameItem;
        HomeApps.Item shelfApp = menuShelfApp;
        HomeApps.Item gridApp = menuGridApp;
        int choice = menuIndex;
        closeMenu(false);
        if (shelfApp != null) {
            if (choice == 0) {
                startReorder();
            } else if (choice == 1) {
                toggleAppFavorite(shelfApp);
            } else {
                click.move();
            }
            return;
        }
        if (gridApp != null) {
            if (gridApp.isHiddenItem) {
                if (choice == 0) {
                    click.open();
                    Prefs.setAppHidden(gridApp.pkg, false);
                    rebuildAllAppsList();
                    retargetAppsGrid(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Restored app: ") + gridApp.label);
                } else if (choice == 1) {
                    click.open();
                    confirmPermanentlyHideApp(gridApp);
                } else if (choice == 2) {
                    click.open();
                    uninstallApp(gridApp);
                } else {
                    click.move();
                }
                return;
            }
            if (choice == 0) {
                startReorder();
            } else if (choice == 1) {
                toggleAppFavorite(gridApp);
            } else if (choice == 2) {
                click.open();
                Prefs.setAppHidden(gridApp.pkg, true);
                rebuildAllAppsList();
                retargetAppsGrid(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                showToast(Ui.tr("Hidden app: ") + gridApp.label);
            } else if (choice == 3) {
                click.open();
                confirmPermanentlyHideApp(gridApp);
            } else if (choice == 4) {
                click.open();
                uninstallApp(gridApp);
            } else {
                click.move();
            }
            return;
        }
        if (gameCard != null) {
            if (gameCard.hidden) {
                if (choice == 0) {
                    click.open();
                    GamingZone.setGameHidden(getContext(), gameCard.id, false);
                    gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
                    rebuildScreenRows();
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Restored to Games Library: ") + gameCard.title);
                } else if (choice == 1) {
                    click.open();
                    confirmPermanentlyHideGame(gameCard);
                } else {
                    click.move();
                }
                return;
            }
            if (choice == 0) {
                startReorder();
            } else if (choice == 1) {
                click.open();
                GamingZone.setGameHidden(getContext(), gameCard.id, true);
                gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
                rebuildScreenRows();
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                showToast(Ui.tr("Hidden from Games Library: ") + gameCard.title);
            } else if (choice == 2) {
                click.open();
                confirmPermanentlyHideGame(gameCard);
            } else {
                click.move();
            }
            return;
        }
        if (pickCard != null) {
            String mediaKey = Recs.key(pickCard);
            String mediaType = mediaKey.startsWith("tv:") ? "tv" : "movie";
            String userId = UserProfileManager.get(getContext()).getActiveProfile().id;
            String title = pickCard.title == null ? "" : pickCard.title;
            boolean isWatchlistMenu = Recs.WATCHLIST.equals(menuPickRowKind);
            BrowserRow activeRow = (!browserRows.isEmpty() && homeRow >= 0 && homeRow < browserRows.size())
                    ? browserRows.get(homeRow) : null;
            if (isWatchlistMenu) {
                if (choice == 0) {
                    click.open();
                    boolean nextWatched = !pickCard.watched;
                    pickCard.watched = nextWatched;
                    if (activeRow != null && activeRow.pickItems != null) {
                        String wlSubKind = subTab == 1 ? "movie" : "tv";
                        sortWatchlistRowAsync(activeRow, Prefs.watchlistOrder(wlSubKind), false);
                    }
                    recs.markPickWatched(pickCard, nextWatched);
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast((nextWatched ? Ui.tr("Marked as watched: ") : Ui.tr("Marked as unwatched: ")) + title);
                } else if (choice == 1) {
                    startReorder();
                } else if (choice == 2) {
                    // Sort by Similarity: re-cluster the entire Watch List row asynchronously (PERF-08)
                    click.open();
                    String wlSubKind = subTab == 1 ? "movie" : "tv";
                    Prefs.clearWatchlistOrder(wlSubKind);
                    if (activeRow != null && activeRow.pickItems != null && !activeRow.pickItems.isEmpty()) {
                        sortWatchlistRowAsync(activeRow, java.util.Collections.emptyList(), true);
                    }
                    recs.onUserOrderChanged(wlSubKind);
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Sorted Watch List by similarity"));
                } else if (choice == 3) {
                    // In Watch List: voting up also marks item as watched (0ms UI lag, background recommendation update)
                    click.open();
                    pickCard.watched = true;
                    if (activeRow != null && activeRow.pickItems != null) {
                        String wlSubKind = subTab == 1 ? "movie" : "tv";
                        sortWatchlistRowAsync(activeRow, Prefs.watchlistOrder(wlSubKind), false);
                    }
                    recs.markPickWatched(pickCard, true);
                    SupabaseClient.get(getContext()).castVoteAsync(userId, mediaKey, mediaType, title, 1,
                            () -> recs.onVoteCastAsync(pickCard, 1));
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Liked & marked as watched: ") + title);
                } else if (choice == 4) {
                    // In Watch List: voting down also marks item as watched
                    click.open();
                    pickCard.watched = true;
                    if (activeRow != null && activeRow.pickItems != null) {
                        String wlSubKind = subTab == 1 ? "movie" : "tv";
                        sortWatchlistRowAsync(activeRow, Prefs.watchlistOrder(wlSubKind), false);
                    }
                    recs.markPickWatched(pickCard, true);
                    SupabaseClient.get(getContext()).castVoteAsync(userId, mediaKey, mediaType, title, -1,
                            () -> recs.onVoteCastAsync(pickCard, -1));
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Disliked & marked as watched: ") + title);
                } else if (choice == 5) {
                    click.open();
                    pickCard.onWatchlist = false;
                    if (activeRow != null && activeRow.pickItems != null) {
                        activeRow.pickItems.remove(pickCard);
                        homeCol = Math.max(0, Math.min(homeCol, Math.max(0, activeRow.count() - 1)));
                    }
                    SupabaseClient.get(getContext()).removeWatchlistItemAsync(userId, mediaKey,
                            () -> recs.prewarmRowCacheAsync());
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Removed from Watch List: ") + title);
                } else {
                    click.move();
                }
            } else {
                // Recommended, Trending & Genre rows:
                // choice 0 = Mark as Watched
                // choice 1 = Thumbs Up (also marks as watched!)
                // choice 2 = Thumbs Down (does NOT mark as watched!)
                // choice 3 = Add to Watch List
                if (choice == 0) {
                    click.open();
                    if (activeRow != null && activeRow.pickItems != null) {
                        activeRow.pickItems.remove(pickCard);
                        homeCol = Math.max(0, Math.min(homeCol, Math.max(0, activeRow.count() - 1)));
                    }
                    recs.markPickWatched(pickCard, true);
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Marked as watched: ") + title);
                } else if (choice == 1) {
                    click.open();
                    if (activeRow != null && activeRow.pickItems != null) {
                        activeRow.pickItems.remove(pickCard);
                        homeCol = Math.max(0, Math.min(homeCol, Math.max(0, activeRow.count() - 1)));
                    }
                    recs.markPickWatched(pickCard, true);
                    SupabaseClient.get(getContext()).castVoteAsync(userId, mediaKey, mediaType, title, 1,
                            () -> recs.onVoteCastAsync(pickCard, 1));
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Liked & marked as watched: ") + title);
                } else if (choice == 2) {
                    click.open();
                    if (activeRow != null && activeRow.pickItems != null) {
                        activeRow.pickItems.remove(pickCard);
                        homeCol = Math.max(0, Math.min(homeCol, Math.max(0, activeRow.count() - 1)));
                    }
                    SupabaseClient.get(getContext()).castVoteAsync(userId, mediaKey, mediaType, title, -1,
                            () -> recs.onVoteCastAsync(pickCard, -1));
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Disliked (not for me): ") + title);
                } else if (choice == 3) {
                    click.open();
                    pickCard.onWatchlist = true;
                    if (activeRow != null && activeRow.pickItems != null) {
                        activeRow.pickItems.remove(pickCard);
                        homeCol = Math.max(0, Math.min(homeCol, Math.max(0, activeRow.count() - 1)));
                    }
                    String wlSubKind = subTab == 0 ? "tv" : "movie";
                    for (BrowserRow br : browserRows) {
                        if (br.kind == ROW_PICKS && Recs.WATCHLIST.equals(br.pickKind) && br.pickItems != null) {
                            if (!br.pickItems.contains(pickCard)) {
                                br.pickItems.add(pickCard);
                                sortWatchlistRowAsync(br, Prefs.watchlistOrder(wlSubKind), false);
                            }
                            break;
                        }
                    }
                    SupabaseClient.get(getContext()).addWatchlistItemAsync(
                            userId, mediaKey, mediaType, title,
                            "", "", "stremio", pickCard.link == null ? "" : pickCard.link,
                            () -> recs.prewarmRowCacheAsync());
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Added to Watch List: ") + title);
                } else {
                    click.move();
                }
            }
            return;
        }
        if (it == null) return;
        if (choice == 0) {
            click.open();
            hideWatching(it);
        } else if (choice == 1) {
            click.open();
            if (openApp(it)) return;
            showToast(Ui.trf("Couldn't open {app}.", "app", appName(it)));
        } else {
            click.move();
        }
    }

    private void hideWatching(WatchNext.Item it) {
        String key = WatchNext.hideKey(it);
        String rawKey = WatchNext.rawHideKey(it);
        String titleKey = WatchNext.titleHideKey(it);
        String stateSig = WatchNext.stateSignature(it);
        long now = System.currentTimeMillis();
        Prefs.hideWatchItem(key, now, stateSig);
        Prefs.hideWatchItem(rawKey, now, stateSig);
        Prefs.hideWatchItem(titleKey, now, stateSig);
        hiddenNow.add(key);
        hiddenNow.add(rawKey);
        hiddenNow.add(titleKey);
        List<WatchNext.Item> list = new ArrayList<>(allWatching.size());
        for (WatchNext.Item x : allWatching) {
            if (!key.equals(WatchNext.hideKey(x))
                    && !rawKey.equals(WatchNext.rawHideKey(x))
                    && !titleKey.equals(WatchNext.titleHideKey(x))) {
                list.add(x);
            }
        }
        allWatching = list;
        watching = list;
        rebuildScreenRows();
        retarget(false);
        heroDirty = true;
        showToast(Ui.tr("Hidden from Continue Watching until watched again"));
    }

    private void drawMenu(Canvas c) {
        modalRenderer.drawMenu(
                getContext(), c, menu.value, menuPick.value, k, screen, menuPanel, menuOptionsTop,
                menuTitle, menuSub, menuOptionCount, menuOptions, menuIcons,
                menuRenderSmartCard, smartCardLabels, smartCardValues, smartHomeRenderer,
                dark() > 0.5f, L_INK, L_DIM, WHITE, DEEP_BLUR,
                fill, border, shadowDeep, menuTitlePaint, menuSubPaint, menuOptionPaint,
                shadows, tmp);
    }

    private void toggleAppFavorite(HomeApps.Item app) {
        if (app == null || app.pkg == null) return;
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            boolean on = list.remove(app.pkg);
            final String text;
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", app.label);
            } else {
                list.add(app.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", app.label);
            }
            Prefs.setShelf(list);
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    private void uninstallApp(HomeApps.Item app) {
        if (app == null || app.pkg == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            showToast(Ui.trf("Couldn't uninstall {app}.", "app", app.label));
        }
    }

    private void confirmPermanentlyHideApp(HomeApps.Item app) {
        if (app == null || app.pkg == null) return;
        try {
            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Permanently Hide App?"))
                    .setMessage(Ui.tr("Are you sure you want to permanently hide \"") + app.label + "\"?\n\n"
                            + Ui.tr("Warning: This action cannot be undone. Permanently hidden apps will not appear in the Hidden Apps tile."))
                    .setPositiveButton(Ui.tr("Permanently Hide"), (d, w) -> {
                        Prefs.permanentlyHideApp(app.pkg);
                        List<String> shelfList = new ArrayList<>(HomeApps.shelfPackages(getContext()));
                        if (shelfList.remove(app.pkg)) {
                            Prefs.setShelf(shelfList);
                        }
                        reload();
                        rebuildAllAppsList();
                        retargetAppsGrid(false);
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("Permanently hidden: ") + app.label);
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        } catch (RuntimeException e) {
            AppLog.w("confirmPermanentlyHideApp failed: " + e.getMessage());
        }
    }

    private void sortWatchlistRowAsync(
            final BrowserRow targetRow,
            final List<String> orderOrEmpty,
            final boolean resetToStart
    ) {
        if (targetRow == null || targetRow.pickItems == null || targetRow.pickItems.isEmpty()) return;
        final List<Recs.Pick> snapshot = new ArrayList<>(targetRow.pickItems);
        final List<String> orderCopy = orderOrEmpty != null
                ? new ArrayList<>(orderOrEmpty)
                : java.util.Collections.emptyList();
        final int gen = watchlistSortGen.incrementAndGet();
        LOADER.execute(() -> {
            final List<Recs.Pick> sorted = WatchlistSimilaritySorter.sortWatchlist(snapshot, orderCopy);
            handler.post(() -> {
                if (watchlistSortGen.get() != gen) return;
                if (!browserRows.contains(targetRow) || targetRow.pickItems == null) return;
                targetRow.pickItems.clear();
                targetRow.pickItems.addAll(sorted);
                if (resetToStart && !browserRows.isEmpty() && browserRows.get(homeRow) == targetRow) {
                    homeCol = 0;
                    targetRow.scrollX = 0f;
                    rowScrollMemory.save(rowMemoryKey(targetRow), 0, 0f);
                }
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });
        });
    }

    private void confirmPermanentlyHideGame(final GamingZone.GameItem game) {
        if (game == null || game.id == null) return;
        openLiquidGlassChoiceModal(
                Ui.tr("Permanently Hide Game?"),
                Ui.tr("Permanently hide \"") + game.title + "\"? " + Ui.tr("This action cannot be undone."),
                new String[] { Ui.tr("Permanently Hide") },
                null,
                Ui.tr("Cancel"),
                idx -> {
                    if (idx == 0) {
                        GamingZone.permanentlyHideGame(getContext(), game.id);
                        gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
                        rebuildScreenRows();
                        retarget(false);
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("Permanently hidden: ") + game.title);
                    }
                },
                null
        );
    }

    private void showHiddenAppsDialog() {
        Set<String> hiddenPkgs = Prefs.hiddenApps();
        Set<String> permPkgs = new HashSet<>(Prefs.permHiddenApps());
        List<HomeApps.Item> hiddenItems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HomeApps.Item it : shelf) {
            if (it != null && it.pkg != null && hiddenPkgs.contains(it.pkg) && !permPkgs.contains(it.pkg) && seen.add(it.pkg)) {
                hiddenItems.add(it);
            }
        }
        for (HomeApps.Item it : others) {
            if (it != null && it.pkg != null && hiddenPkgs.contains(it.pkg) && !permPkgs.contains(it.pkg) && seen.add(it.pkg)) {
                hiddenItems.add(it);
            }
        }
        if (hiddenItems.isEmpty()) {
            showToast(Ui.tr("No hidden apps"));
            return;
        }
        String[] labels = new String[hiddenItems.size()];
        for (int i = 0; i < hiddenItems.size(); i++) {
            labels[i] = hiddenItems.get(i).label + " · " + Ui.tr("Press OK to unhide");
        }
        try {
            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Hidden Apps"))
                    .setItems(labels, (d, which) -> {
                        if (which >= 0 && which < hiddenItems.size()) {
                            HomeApps.Item restored = hiddenItems.get(which);
                            Prefs.setAppHidden(restored.pkg, false);
                            rebuildAllAppsList();
                            retargetAppsGrid(false);
                            heroDirty = true;
                            heroLayer.invalidate();
                            wake();
                            showToast(Ui.tr("Restored app: ") + restored.label);
                        }
                    })
                    .setNegativeButton(Ui.tr("Close"), null)
                    .show();
        } catch (RuntimeException e) {
            AppLog.w("showHiddenAppsDialog failed: " + e.getMessage());
        }
    }

    private void showHiddenGamesDialog() {
        final List<GamingZone.GameItem> hiddenGames = GamingZone.getTemporarilyHiddenGames(getContext());
        if (hiddenGames.isEmpty()) {
            showToast(Ui.tr("No hidden games"));
            return;
        }
        String[] labels = new String[hiddenGames.size()];
        for (int i = 0; i < hiddenGames.size(); i++) {
            labels[i] = hiddenGames.get(i).title + " · " + Ui.tr("Press OK to unhide");
        }
        openLiquidGlassChoiceModal(
                Ui.tr("Hidden Games"),
                Ui.tr("Select a game to restore to Games Library:"),
                labels,
                null,
                Ui.tr("Close"),
                which -> {
                    if (which >= 0 && which < hiddenGames.size()) {
                        GamingZone.GameItem restored = hiddenGames.get(which);
                        GamingZone.setGameHidden(getContext(), restored.id, false);
                        gamingStatus = GamingZone.getCachedLaptopStatus(getContext());
                        rebuildScreenRows();
                        retarget(false);
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("Restored game: ") + restored.title);
                    }
                },
                null
        );
    }

    private void openTraktAuthDialog() {
        final Context c = getContext().getApplicationContext();
        // Rule 6 & 7: When already connected, clicking asks "Would you like to disconnect?"
        if (Trakt.hasOAuth(c)) {
            try {
                String user = Trakt.user(c);
                new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(Ui.tr("Trakt Account Connected") + (user.isEmpty() ? "" : (" (@" + user + ")")))
                        .setMessage(Ui.tr("Would you like to disconnect your Trakt account?"))
                        .setPositiveButton(Ui.tr("Disconnect"), (d, w) -> {
                            Trakt.signOut(c);
                            recs.prewarmRowCacheAsync();
                            rebuildSettingsTree();
                            heroDirty = true;
                            heroLayer.invalidate();
                            wake();
                            showToast(Ui.tr("Disconnected Trakt account"));
                        })
                        .setNegativeButton(Ui.tr("Cancel"), null)
                        .show();
            } catch (RuntimeException e) {
                AppLog.w("openTraktAuthDialog connected failed: " + e.getMessage());
            }
            return;
        }
        showToast(Ui.tr("Requesting Trakt activation code…"));
        Trakt.signIn(c, () -> handler.post(() -> {
            // Rule 2: Auto-close the OAuth dialog as soon as authentication succeeds, and show the Sync Waiting Screen
            if (Trakt.signedIn(c)) {
                if (traktAuthDialog != null) {
                    try {
                        traktAuthDialog.dismiss();
                    } catch (RuntimeException ignored) {
                    }
                    traktAuthDialog = null;
                }
                rebuildSettingsTree();
                startSyncModal(
                        Ui.tr("Syncing Trakt Account (@") + Trakt.user(c) + ")",
                        Ui.tr("Fetching Watch List, Ratings, Favorites & Watch History…"),
                        15);
                recs.forceRefreshNow();
                reload();
                return;
            }
            String err = Trakt.message();
            if (err != null && !err.isEmpty()) {
                if (traktAuthDialog != null) {
                    try {
                        traktAuthDialog.dismiss();
                    } catch (RuntimeException ignored) {
                    }
                    traktAuthDialog = null;
                }
                showToast(err);
                return;
            }
            String code = Trakt.code();
            String url = Trakt.url();
            if (code != null) {
                try {
                    if (traktAuthDialog != null) {
                        try {
                            traktAuthDialog.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        traktAuthDialog = null;
                    }
                    String activateUrl = Trakt.normalizeActivationUrl(url);
                    android.widget.LinearLayout container = modalRenderer.buildTraktOAuthDialogView(activity, activateUrl, code, k);

                    traktAuthDialog = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Ui.tr("Connect Private Trakt Account"))
                            .setView(container)
                            .setNegativeButton(Ui.tr("Cancel"), (d, w) -> {
                                Trakt.cancelSignIn();
                                traktAuthDialog = null;
                            })
                            .create();
                    traktAuthDialog.setOnDismissListener(d -> {
                        if (!Trakt.signedIn(c)) {
                            Trakt.cancelSignIn();
                        }
                        traktAuthDialog = null;
                    });
                    traktAuthDialog.show();
                } catch (RuntimeException e) {
                    showToast(Ui.tr("Go to trakt.tv/activate and enter code: ") + code);
                }
            }
        }));
    }

    private void openStremioAuthDialog() {
        final Context c = getContext().getApplicationContext();
        // Rule 6 & 7: When already connected, clicking asks "Would you like to disconnect?"
        if (StremioAccountClient.isConfigured(c)) {
            try {
                String email = StremioAccountClient.getAccountEmail(c);
                new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(Ui.tr("Stremio Account Connected") + (email.isEmpty() ? "" : (" (" + email + ")")))
                        .setMessage(Ui.tr("Would you like to disconnect your Stremio account?"))
                        .setPositiveButton(Ui.tr("Disconnect"), (d, w) -> {
                            StremioAccountClient.saveAuthKey(c, "", "");
                            recs.prewarmRowCacheAsync();
                            rebuildSettingsTree();
                            heroDirty = true;
                            heroLayer.invalidate();
                            wake();
                            showToast(Ui.tr("Disconnected Stremio account"));
                        })
                        .setNegativeButton(Ui.tr("Cancel"), null)
                        .show();
            } catch (RuntimeException e) {
                AppLog.w("openStremioAuthDialog connected failed: " + e.getMessage());
            }
            return;
        }
        showToast("Requesting Stremio QR link code…");
        StremioAccountClient.signInWithQr(c, () -> handler.post(() -> {
            if (StremioAccountClient.isConfigured(c)) {
                if (stremioAuthDialog != null) {
                    try {
                        stremioAuthDialog.dismiss();
                    } catch (RuntimeException ignored) {
                    }
                    stremioAuthDialog = null;
                }
                rebuildSettingsTree();
                startSyncModal(
                        Ui.tr("Connecting Stremio Account"),
                        Ui.tr("Authenticating & importing Stremio Library…"),
                        25);
                recs.forceRefreshNow();
                reload();
                return;
            }
            String err = StremioAccountClient.qrMessage();
            if (err != null && !err.isEmpty()) {
                if (stremioAuthDialog != null) {
                    try {
                        stremioAuthDialog.dismiss();
                    } catch (RuntimeException ignored) {
                    }
                    stremioAuthDialog = null;
                }
                showToast(err);
                openStremioEmailPasswordDialog();
                return;
            }
            String code = StremioAccountClient.qrCode();
            String linkUrl = StremioAccountClient.qrLink();
            if (code != null && !code.isEmpty()) {
                try {
                    if (stremioAuthDialog != null) {
                        try {
                            stremioAuthDialog.dismiss();
                        } catch (RuntimeException ignored) {
                        }
                        stremioAuthDialog = null;
                    }
                    String targetUrl = (linkUrl != null && !linkUrl.isEmpty())
                            ? linkUrl
                            : ("https://link.stremio.com/" + code);
                    android.widget.LinearLayout container =
                            modalRenderer.buildTraktOAuthDialogView(activity, targetUrl, code, k);
                    stremioAuthDialog = new android.app.AlertDialog.Builder(
                            activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle(Ui.tr("Connect Stremio Account (Library Sync)"))
                            .setView(container)
                            .setNeutralButton("Email & Password", (d, w) -> {
                                StremioAccountClient.cancelQrSignIn();
                                stremioAuthDialog = null;
                                openStremioEmailPasswordDialog();
                            })
                            .setNegativeButton(Ui.tr("Cancel"), (d, w) -> StremioAccountClient.cancelQrSignIn())
                            .create();
                    stremioAuthDialog.setOnDismissListener(d -> StremioAccountClient.cancelQrSignIn());
                    stremioAuthDialog.show();
                } catch (RuntimeException e) {
                    openStremioEmailPasswordDialog();
                }
            }
        }));
    }

    private void openStremioEmailPasswordDialog() {
        final Context c = getContext().getApplicationContext();
        try {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = Math.round(24 * k);
            layout.setPadding(pad, Math.round(12 * k), pad, Math.round(12 * k));

            final android.widget.EditText emailInput = new android.widget.EditText(activity);
            emailInput.setId(android.view.View.generateViewId());
            emailInput.setHint(Ui.tr("Stremio Email"));
            emailInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            emailInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
            emailInput.setSingleLine(true);

            // Rule 4: Censor the password and add a Show / Hide Password button
            final android.widget.EditText passInput = new android.widget.EditText(activity);
            passInput.setId(android.view.View.generateViewId());
            passInput.setHint(Ui.tr("Stremio Password"));
            passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passInput.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            passInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            passInput.setSingleLine(true);

            final android.widget.Button showHideBtn = new android.widget.Button(activity);
            showHideBtn.setId(android.view.View.generateViewId());
            showHideBtn.setText(Ui.tr("Show Password"));
            final boolean[] showingPass = {false};
            showHideBtn.setOnClickListener(v -> {
                showingPass[0] = !showingPass[0];
                int sel = Math.max(0, passInput.getSelectionEnd());
                if (showingPass[0]) {
                    passInput.setTransformationMethod(null);
                    showHideBtn.setText(Ui.tr("Hide Password"));
                } else {
                    passInput.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                    showHideBtn.setText(Ui.tr("Show Password"));
                }
                passInput.setSelection(Math.min(sel, passInput.getText().length()));
            });

            // Guarantee confirming email always advances directly into password field (never skips/dismisses)
            emailInput.setNextFocusDownId(passInput.getId());
            passInput.setNextFocusUpId(emailInput.getId());
            passInput.setNextFocusDownId(showHideBtn.getId());
            showHideBtn.setNextFocusUpId(passInput.getId());
            emailInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    passInput.requestFocus();
                    return true;
                }
                return false;
            });

            layout.addView(emailInput);
            layout.addView(passInput);
            layout.addView(showHideBtn);

            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
                    activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Connect Stremio Account (Library Sync)"))
                    .setMessage(Ui.tr("Sign in with your Stremio account to sync your Stremio Library (Watch List) and playback progress directly into SoarTV."))
                    .setView(layout)
                    .setPositiveButton(Ui.tr("Sign In & Sync"), null)
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            dialog.setOnShowListener(di -> {
                // Anchor dialog in upper TV viewport so Android TV on-screen keyboard never covers email/password/buttons
                android.view.Window win = dialog.getWindow();
                if (win != null) {
                    android.view.WindowManager.LayoutParams lp = win.getAttributes();
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
                    lp.y = Math.round(28 * k);
                    win.setAttributes(lp);
                    win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                android.widget.Button okBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                if (okBtn != null) {
                    okBtn.setOnClickListener(v -> {
                        final String email = emailInput.getText().toString().trim();
                        final String pass = passInput.getText().toString();
                        if (email.isEmpty()) {
                            showToast(Ui.tr("Please enter your Stremio email and password"));
                            emailInput.requestFocus();
                            return;
                        }
                        if (pass.isEmpty()) {
                            showToast(Ui.tr("Please enter your Stremio email and password"));
                            passInput.requestFocus();
                            return;
                        }
                        dialog.dismiss();
                        startSyncModal(
                                Ui.tr("Connecting Stremio Account"),
                                Ui.tr("Authenticating & importing Stremio Library…"),
                                15);
                        LOADER.execute(() -> {
                            String authKey = StremioAccountClient.loginWithEmail(c, email, pass);
                            final boolean ok = authKey != null && !authKey.isEmpty();
                            handler.post(() -> {
                                if (ok) {
                                    rebuildSettingsTree();
                                    updateSyncModal(Ui.tr("Stremio Library imported · Building rows…"), 45, false);
                                    recs.forceRefreshNow();
                                    reload();
                                } else {
                                    syncModalOpen = false;
                                    toastLayer.invalidate();
                                    showToast(Ui.tr("Stremio sign-in failed. Check email/password."));
                                }
                            });
                        });
                    });
                }
            });
            dialog.show();
        } catch (RuntimeException e) {
            AppLog.w("openStremioEmailPasswordDialog failed: " + e.getMessage());
        }
    }

    private interface SwatchPickCallback {
        void onColorPicked(String colorName, int colorArgb);
    }

    private android.view.View createCircleSwatchView(int colorArgb, int diameterPx) {
        android.view.View v = new android.view.View(activity);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(0xFF000000 | colorArgb);
        gd.setStroke(Math.max(2, Math.round(2f * k)), 0x88FFFFFF);
        v.setBackground(gd);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(diameterPx, diameterPx);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private static String defaultFriendlyControllerNameForColor(String colorName) {
        if (colorName == null || colorName.trim().isEmpty()) return "Controller";
        if ("Soft Green".equals(colorName) || "Emerald Green".equals(colorName) || "Lime Green".equals(colorName)) {
            return "Green";
        }
        if ("Soft Yellow".equals(colorName) || "Gold".equals(colorName)) {
            return "Yellow";
        }
        if ("Hot Pink".equals(colorName)) {
            return "Pink";
        }
        return colorName;
    }

    private BluetoothControllerPairer activeBtPairer;
    private android.app.AlertDialog activeBtPairDialog;

    /**
     * Opens SoarTV's native in-app Bluetooth controller & remote scanner and pairer so the user
     * never has to leave SoarTV for Android TV's system Settings UI.
     */
    private void openInAppBluetoothPairingDialog(final Runnable onPairedDone) {
        try {
            if (activeBtPairer != null) {
                activeBtPairer.stop();
                activeBtPairer = null;
            }
            if (activeBtPairDialog != null) {
                try {
                    activeBtPairDialog.dismiss();
                } catch (Throwable ignored) {}
                activeBtPairDialog = null;
            }
            final BluetoothControllerPairer pairer = new BluetoothControllerPairer(getContext());
            final List<BluetoothControllerPairer.DiscoveredDevice> items = new ArrayList<>();

            android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(activity);
            rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = Math.round(24 * k);
            rootLayout.setPadding(pad, Math.round(16 * k), pad, Math.round(12 * k));

            final android.widget.TextView statusText = new android.widget.TextView(activity);
            statusText.setText(Ui.tr("Put your controller in pairing mode — scanning nearby controllers…"));
            statusText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 20 * k);
            statusText.setTextColor(0xFFD1D5DB);
            statusText.setPadding(0, 0, 0, Math.round(10 * k));
            rootLayout.addView(statusText);

            final android.widget.Button filterToggleBtn = new android.widget.Button(activity);
            filterToggleBtn.setAllCaps(false);
            filterToggleBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16.5f * k);
            filterToggleBtn.setText(Ui.tr("Filter: Controllers & Remotes Only (Select to Show All)"));
            filterToggleBtn.setOnClickListener(v -> {
                boolean nextAll = !pairer.isShowAllDevices();
                pairer.setShowAllDevices(nextAll);
                filterToggleBtn.setText(nextAll
                        ? Ui.tr("Filter: All Nearby Bluetooth Devices (Select for Controllers Only)")
                        : Ui.tr("Filter: Controllers & Remotes Only (Select to Show All)"));
            });
            android.widget.LinearLayout.LayoutParams filterLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            filterLp.bottomMargin = Math.round(10 * k);
            rootLayout.addView(filterToggleBtn, filterLp);

            final android.widget.ListView listView = new android.widget.ListView(activity);
            listView.setFocusable(true);
            listView.setItemsCanFocus(false);
            listView.setDividerHeight(Math.round(4 * k));

            final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
                @Override
                public int getCount() {
                    return Math.max(1, items.size());
                }

                @Override
                public Object getItem(int position) {
                    return position >= 0 && position < items.size() ? items.get(position) : null;
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public boolean isEnabled(int position) {
                    return !items.isEmpty();
                }

                @Override
                public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                    android.widget.LinearLayout row = new android.widget.LinearLayout(activity);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    int rPadX = Math.round(18 * k);
                    int rPadY = Math.round(14 * k);
                    row.setPadding(rPadX, rPadY, rPadX, rPadY);

                    if (items.isEmpty()) {
                        android.widget.TextView emptyView = new android.widget.TextView(activity);
                        emptyView.setText(Ui.tr("Searching for controllers in pairing mode…"));
                        emptyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 20 * k);
                        emptyView.setTextColor(0xFF9CA3AF);
                        row.addView(emptyView);
                        return row;
                    }

                    BluetoothControllerPairer.DiscoveredDevice d = items.get(position);
                    android.widget.LinearLayout col = new android.widget.LinearLayout(activity);
                    col.setOrientation(android.widget.LinearLayout.VERTICAL);
                    android.widget.LinearLayout.LayoutParams colLp =
                            new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    col.setLayoutParams(colLp);

                    android.widget.TextView titleView = new android.widget.TextView(activity);
                    titleView.setText(d.name);
                    titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22 * k);
                    titleView.setTextColor(0xFFFFFFFF);
                    col.addView(titleView);

                    android.widget.TextView subView = new android.widget.TextView(activity);
                    String sig = d.rssi >= -65 ? Ui.tr("Strong Signal") : Ui.tr("Nearby");
                    subView.setText((d.gamepadHint ? Ui.tr("Game Controller · ") : "") + sig + " · " + Ui.tr("Press OK to Pair"));
                    subView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * k);
                    subView.setTextColor(0xFF9CA3AF);
                    col.addView(subView);
                    row.addView(col);

                    android.widget.TextView actionBadge = new android.widget.TextView(activity);
                    actionBadge.setText(Ui.tr("Pair"));
                    actionBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19 * k);
                    actionBadge.setTextColor(0xFF86EFAC);
                    row.addView(actionBadge);
                    return row;
                }
            };
            listView.setAdapter(adapter);
            android.widget.LinearLayout.LayoutParams listLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.round(340 * k));
            rootLayout.addView(listView, listLp);

            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
                    activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Pair Bluetooth Controller or Remote"))
                    .setView(rootLayout)
                    .setPositiveButton(Ui.tr("Rescan"), null)
                    .setNeutralButton(Ui.tr("System Bluetooth…"), (d, w) -> {
                        pairer.stop();
                        openAndroidSettingIntent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    })
                    .setNegativeButton(Ui.tr("Close"), (d, w) -> pairer.stop())
                    .create();

            activeBtPairer = pairer;
            activeBtPairDialog = dialog;
            dialog.setOnDismissListener(d -> {
                pairer.stop();
                if (activeBtPairer == pairer) {
                    activeBtPairer = null;
                    activeBtPairDialog = null;
                }
            });
            listView.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < items.size()) {
                    pairer.pairDevice(items.get(position));
                }
            });

            dialog.show();
            android.widget.Button rescanBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            if (rescanBtn != null) {
                rescanBtn.setOnClickListener(v -> pairer.restartDiscovery());
            }

            pairer.start(activity, new BluetoothControllerPairer.Listener() {
                @Override
                public void onDevicesUpdated(List<BluetoothControllerPairer.DiscoveredDevice> devices, boolean scanning, String statusMessage) {
                    boolean wasEmpty = items.isEmpty();
                    items.clear();
                    if (devices != null) items.addAll(devices);
                    statusText.setText(Ui.tr(statusMessage));
                    adapter.notifyDataSetChanged();
                    if (wasEmpty && !items.isEmpty()) {
                        listView.requestFocus();
                        listView.setSelection(0);
                    }
                }

                @Override
                public void onDevicePaired(String address, String name) {
                    pairer.stop();
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {}
                    ControllerManager.refreshHardwareLocationsAsync(getContext(), HomeView.this::onControllerConnectionChanged);
                    rebuildSettingsTree();
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();

                    String btDesc = ControllerManager.canonicalBluetoothDescriptor(address);
                    boolean alreadyTagged = ControllerManager.isTagged(getContext(), btDesc);
                    ControllerManager.ControllerInfo pairedInfo = new ControllerManager.ControllerInfo(
                            -1,
                            btDesc,
                            name != null && !name.isEmpty() ? name : "Wireless Controller",
                            ControllerManager.getCustomName(getContext(), btDesc),
                            ControllerManager.getAssignedColor(getContext(), btDesc),
                            alreadyTagged,
                            -1,
                            false,
                            true
                    );
                    showToast(Ui.tr("Paired: ") + pairedInfo.deviceName);
                    if (ControllerManager.isFirstTimeSetup(pairedInfo)) {
                        openControllerFirstTimeSetupDialog(pairedInfo, onPairedDone);
                    } else if (onPairedDone != null) {
                        onPairedDone.run();
                    }
                }

                @Override
                public void onPairingFailed(String address, String name, String reason) {
                    statusText.setText(Ui.tr(reason));
                    showToast(Ui.tr(reason));
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("openInAppBluetoothPairingDialog failed: " + e.getMessage());
        }
    }

    private void openGuidedControllerSetupWizard(final int step) {
        openLiquidGlassChoiceModal(
                Ui.tr("Set Up Controller #") + step,
                Ui.tr("Connect via USB dongle or pair wirelessly via Bluetooth:"),
                new String[] {
                        Ui.tr("Detect USB / Active Controller"),
                        Ui.tr("Pair via Bluetooth")
                },
                null,
                Ui.tr("Cancel"),
                choice -> {
                    if (choice == 0) {
                        ControllerManager.refreshHardwareLocationsAsync(getContext(), () -> showGuidedUsbControllerCandidates(step));
                    } else if (choice == 1) {
                        openInAppBluetoothPairingDialog(() -> askConfigureNextWizardController(step));
                    }
                },
                null
        );
    }

    private void showGuidedUsbControllerCandidates(final int step) {
        List<ControllerManager.ControllerInfo> list = ControllerManager.getAllKnownControllers(getContext());
        if (list.isEmpty()) {
            showToast(Ui.tr("No controller detected yet. Turn on your gamepad or pair via Bluetooth."));
            openGuidedControllerSetupWizard(step);
            return;
        }
        List<ControllerManager.ControllerInfo> untagged = new ArrayList<>();
        for (ControllerManager.ControllerInfo ci : list) {
            if (!ci.tagged) {
                untagged.add(ci);
            }
        }
        final List<ControllerManager.ControllerInfo> candidates = !untagged.isEmpty() ? untagged : list;
        if (candidates.size() == 1) {
            promptTagControllerWizard(candidates.get(0), step);
            return;
        }
        String[] choices = new String[candidates.size()];
        int[] sw = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ControllerManager.ControllerInfo u = candidates.get(i);
            String stateLabel = u.connected ? Ui.tr("Connected") : Ui.tr("Paired");
            choices[i] = u.getDisplayName() + " · " + stateLabel;
            sw[i] = u.color;
        }
        openLiquidGlassChoiceModal(
                Ui.tr("Select Controller #") + step,
                Ui.tr("Choose which controller to configure:"),
                choices,
                sw,
                Ui.tr("Cancel"),
                which -> {
                    if (which >= 0 && which < candidates.size()) {
                        promptTagControllerWizard(candidates.get(which), step);
                    }
                },
                null
        );
    }

    private void askConfigureNextWizardController(final int completedStep) {
        Runnable finishAll = () -> {
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            showToast(Ui.tr("All controllers saved!"));
        };
        openLiquidGlassChoiceModal(
                Ui.tr("Controller #") + completedStep + " Ready!",
                Ui.tr("Would you like to set up Controller #") + (completedStep + 1) + "?",
                new String[] {
                        Ui.tr("Set Up Next Controller"),
                        Ui.tr("Done")
                },
                null,
                Ui.tr("Done"),
                idx -> {
                    if (idx == 0) {
                        openGuidedControllerSetupWizard(completedStep + 1);
                    } else {
                        finishAll.run();
                    }
                },
                finishAll
        );
    }

    private void promptTagControllerWizard(final ControllerManager.ControllerInfo chosen, final int step) {
        if (chosen == null) return;
        Runnable after = () -> askConfigureNextWizardController(step);
        if (ControllerManager.isFirstTimeSetup(chosen)) {
            openControllerFirstTimeSetupDialog(chosen, after);
        } else {
            openControllerEditDialog(chosen, after);
        }
    }

    /**
     * Opens the controller interaction dialog appropriate for the controller's state:
     * First-Time Setup for newly connected/paired controllers vs. Manage/Edit for already-configured ones.
     */
    private void openControllerSetupOrEditDialog(final ControllerManager.ControllerInfo ci, final Runnable onDone) {
        if (ci == null) return;
        if (ControllerManager.isFirstTimeSetup(ci)) {
            openControllerFirstTimeSetupDialog(ci, onDone);
        } else {
            openControllerEditDialog(ci, onDone);
        }
    }

    /**
     * First-Time Setup flow for an unconfigured controller:
     * Step 1: Pick a visual color swatch (rainbow order, zero hex codes).
     * Step 2: Confirm or customize the controller name (pre-filled with the color name for 1-click remote convenience).
     */
    private void openControllerFirstTimeSetupDialog(final ControllerManager.ControllerInfo ci, final Runnable onDone) {
        if (ci == null) return;
        showRainbowColorPickerDialog(
                Ui.tr("Set Up Controller · 1. Choose Color"),
                0,
                (pickedColorName, pickedArgb) -> openFirstTimeControllerNameStep(ci, pickedColorName, pickedArgb, onDone),
                null
        );
    }

    private void openFirstTimeControllerNameStep(
            final ControllerManager.ControllerInfo ci,
            final String pickedColorName,
            final int pickedArgb,
            final Runnable onDone
    ) {
        final String suggested = defaultFriendlyControllerNameForColor(pickedColorName);
        openLiquidGlassInputModal(
                Ui.tr("Set Up Controller · 2. Name Controller"),
                pickedColorName + " · " + ci.deviceName,
                Ui.tr("Controller Name (e.g. Pink, Green, Player 1)"),
                suggested,
                false,
                Ui.tr("Save Controller"),
                entered -> {
                    String chosenName = entered != null ? entered.trim() : "";
                    if (chosenName.isEmpty()) chosenName = suggested;
                    ControllerManager.saveTag(getContext(), ci.descriptor, chosenName, pickedArgb, ci.deviceName);
                    rebuildSettingsTree();
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Controller saved: ") + chosenName);
                    if (onDone != null) onDone.run();
                },
                () -> openControllerFirstTimeSetupDialog(ci, onDone)
        );
    }

    /**
     * Separate Edit / Manage dialog for an already-configured controller:
     * lets the user independently Rename Controller, Change Color (1-click save), Reset Setup, or Unpair Bluetooth.
     */
    private void openControllerEditDialog(final ControllerManager.ControllerInfo ci, final Runnable onDone) {
        if (ci == null) return;
        final List<String> actions = ControllerManager.getControllerEditActions(ci);
        String[] actionArr = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            actionArr[i] = Ui.tr(actions.get(i));
        }
        String status = ci.connected ? Ui.tr("Connected") : Ui.tr("Paired");
        String pwr = ci.connected ? ci.getPowerStatusDisplay() : "";
        String sub = status + (!pwr.isEmpty() ? (" · " + Ui.tr(pwr)) : "")
                + " · " + ControllerManager.findColorName(ci.color) + " · " + ci.deviceName;

        openLiquidGlassChoiceModal(
                ci.getDisplayName(),
                sub,
                actionArr,
                null,
                Ui.tr("Close"),
                which -> {
                    if (which < 0 || which >= actions.size()) return;
                    String act = actions.get(which);
                    if ("Rename Controller".equals(act)) {
                        openRenameControllerDialog(ci, onDone);
                    } else if ("Change Color".equals(act)) {
                        showRainbowColorPickerDialog(
                                Ui.tr("Change Color · ") + ci.getDisplayName(),
                                ci.color,
                                (pickedName, pickedArgb) -> {
                                    String keepName = !ci.customName.isEmpty() ? ci.customName : ci.deviceName;
                                    ControllerManager.saveTag(getContext(), ci.descriptor, keepName, pickedArgb, ci.deviceName);
                                    rebuildSettingsTree();
                                    heroDirty = true;
                                    heroLayer.invalidate();
                                    wake();
                                    showToast(keepName + " · " + pickedName);
                                    if (onDone != null) onDone.run();
                                },
                                () -> openControllerEditDialog(ci, onDone)
                        );
                    } else if ("Reset Name & Color".equals(act)) {
                        ControllerManager.clearTag(getContext(), ci.descriptor);
                        rebuildSettingsTree();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("Reset controller setup: ") + ci.deviceName);
                        if (onDone != null) onDone.run();
                    } else if ("Unpair Bluetooth Controller".equals(act)) {
                        openLiquidGlassChoiceModal(
                                Ui.tr("Unpair ") + ci.getDisplayName() + "?",
                                Ui.tr("This will disconnect and unpair the Bluetooth controller from the TV."),
                                new String[] { Ui.tr("Unpair") },
                                null,
                                Ui.tr("Cancel"),
                                idx -> {
                                    if (idx == 0) {
                                        BluetoothControllerPairer.unpairDevice(getContext(), ci.descriptor);
                                        ControllerManager.clearTag(getContext(), ci.descriptor);
                                        rebuildSettingsTree();
                                        heroDirty = true;
                                        heroLayer.invalidate();
                                        wake();
                                        showToast(Ui.tr("Unpaired: ") + ci.getDisplayName());
                                        if (onDone != null) onDone.run();
                                    }
                                },
                                () -> openControllerEditDialog(ci, onDone)
                        );
                    }
                },
                null
        );
    }

    private void openRenameControllerDialog(final ControllerManager.ControllerInfo ci, final Runnable onDone) {
        if (ci == null) return;
        String initial = !ci.customName.isEmpty() ? ci.customName : ci.deviceName;
        openLiquidGlassInputModal(
                Ui.tr("Rename Controller"),
                ci.deviceName,
                Ui.tr("Controller Name"),
                initial,
                false,
                Ui.tr("Save Name"),
                entered -> {
                    String newName = entered != null ? entered.trim() : "";
                    if (newName.isEmpty()) newName = ci.deviceName;
                    ControllerManager.saveTag(getContext(), ci.descriptor, newName, ci.color, ci.deviceName);
                    rebuildSettingsTree();
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    showToast(Ui.tr("Renamed to: ") + newName);
                    if (onDone != null) onDone.run();
                },
                () -> openControllerEditDialog(ci, onDone)
        );
    }

    /**
     * Renders a visual rainbow-ordered swatch picker using Liquid Glass colored circles (never exposing hex strings).
     */
    private void showRainbowColorPickerDialog(
            final String dialogTitle,
            final int selectedColor,
            final SwatchPickCallback onPicked,
            final Runnable onBack
    ) {
        final List<ControllerManager.ColorOption> swatches = ControllerManager.RAINBOW_COLORS;
        final int totalRows = swatches.size() + 1;
        String[] labels = new String[totalRows];
        int[] swatchColors = new int[totalRows];
        int initialIdx = 0;
        for (int i = 0; i < swatches.size(); i++) {
            ControllerManager.ColorOption opt = swatches.get(i);
            boolean isCurrent = selectedColor != 0 && (0xFF000000 | opt.argb) == (0xFF000000 | selectedColor);
            labels[i] = Ui.tr(opt.name) + (isCurrent ? (" · " + Ui.tr("Current")) : "");
            swatchColors[i] = opt.argb;
            if (isCurrent) initialIdx = i;
        }
        labels[swatches.size()] = Ui.tr("Custom Color…");
        swatchColors[swatches.size()] = ControllerManager.COLOR_NEUTRAL_FALLBACK;

        openLiquidGlassChoiceModal(
                dialogTitle,
                Ui.tr("Select a color swatch:"),
                labels,
                swatchColors,
                initialIdx,
                onBack != null ? Ui.tr("Back") : Ui.tr("Cancel"),
                position -> {
                    if (position >= 0 && position < swatches.size()) {
                        ControllerManager.ColorOption opt = swatches.get(position);
                        if (onPicked != null) {
                            onPicked.onColorPicked(opt.name, opt.argb);
                        }
                    } else {
                        openCustomColorShadeDialog(onPicked);
                    }
                },
                onBack
        );
    }

    private void openCustomColorShadeDialog(final SwatchPickCallback onPicked) {
        openLiquidGlassInputModal(
                Ui.tr("Custom Color"),
                Ui.tr("Enter custom RGB color code (e.g. #EC4899):"),
                Ui.tr("Enter custom color code"),
                "",
                false,
                Ui.tr("Apply"),
                entered -> {
                    String raw = entered != null ? entered.trim() : "";
                    int parsed = ControllerManager.parseColorHex(raw, ControllerManager.COLOR_NEUTRAL_FALLBACK);
                    String friendly = ControllerManager.findColorName(parsed);
                    if (onPicked != null) {
                        onPicked.onColorPicked(friendly, parsed);
                    } else {
                        promptApplyColorToController(friendly, parsed);
                    }
                },
                null
        );
    }

    private void promptApplyColorToController(final String colorName, final int color) {
        final List<ControllerManager.ControllerInfo> list = ControllerManager.getAllKnownControllers(getContext());
        if (list.isEmpty()) {
            showToast(colorName + " · " + Ui.tr("Pair or connect a controller first"));
            return;
        }
        if (list.size() == 1) {
            ControllerManager.ControllerInfo target = list.get(0);
            String nameToSave = !target.customName.isEmpty()
                    ? target.customName
                    : defaultFriendlyControllerNameForColor(colorName);
            ControllerManager.saveTag(getContext(), target.descriptor, nameToSave, color, target.deviceName);
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            showToast(nameToSave + " · " + colorName);
            return;
        }
        String[] choices = new String[list.size()];
        int[] sw = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ControllerManager.ControllerInfo ci = list.get(i);
            String status = ci.connected ? Ui.tr("Connected") : Ui.tr("Paired");
            choices[i] = ci.getDisplayName() + " · " + status;
            sw[i] = ci.color;
        }
        openLiquidGlassChoiceModal(
                Ui.tr("Assign ") + colorName,
                Ui.tr("Select controller:"),
                choices,
                sw,
                Ui.tr("Cancel"),
                which -> {
                    if (which >= 0 && which < list.size()) {
                        ControllerManager.ControllerInfo target = list.get(which);
                        String nameToSave = !target.customName.isEmpty()
                                ? target.customName
                                : defaultFriendlyControllerNameForColor(colorName);
                        ControllerManager.saveTag(getContext(), target.descriptor, nameToSave, color, target.deviceName);
                        rebuildSettingsTree();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(nameToSave + " · " + colorName);
                    }
                },
                null
        );
    }

    private void selectTab(int index) {
        selectScreen(index);
    }

    private void selectSubTab(int newSubTab) {
        int clamped = Math.max(0, Math.min(1, newSubTab));
        if (clamped == subTab) return;
        subTab = clamped;
        recs.onUserOrderChanged(subTab == 0 ? "tv" : "movie");
        rebuildScreenRows();
        retarget(false);
        heroDirty = true;
        heroLayer.invalidate();
        wake();
    }

    private void selectGenre(int newGenreIndex) {
        // Genres are now dedicated vertical rows on the Home Screen
    }

    private void drawUnifiedAmbientBackground(Canvas c, android.graphics.ColorFilter veilF) {
        if (unifiedBgBitmap == null || Math.round(night * 64f) != lastUnifiedNightStep) {
            updateVignetteShaders();
        }
        if (unifiedBgBitmap != null) {
            unifiedBgBasePaint.setShader(null);
            unifiedBgBasePaint.setAlpha(255);
            unifiedBgBasePaint.setColorFilter(veilF);
            c.drawBitmap(unifiedBgBitmap, null, screen, unifiedBgBasePaint);
            unifiedBgBasePaint.setColorFilter(null);
            return;
        }
        if (unifiedBgBaseGrad != null) {
            unifiedBgBasePaint.setShader(unifiedBgBaseGrad);
            unifiedBgBasePaint.setColorFilter(veilF);
            c.drawRect(screen, unifiedBgBasePaint);
            unifiedBgBasePaint.setColorFilter(null);
        }
        if (unifiedBgGlowGrad != null) {
            unifiedBgGlowPaint.setColorFilter(veilF);
            c.drawRect(screen, unifiedBgGlowPaint);
            unifiedBgGlowPaint.setColorFilter(null);
        }
    }

    private boolean isCoveredByOpaqueSideShelf() {
        return activeScreen == SCREEN_HOME && sideShelfOpen && sideShelfSpring.value >= 0.995f
                && sideShelfBlur != null && !sideShelfBlur.isRecycled();
    }

    private void drawLightSky(Canvas c) {
        if (isCoveredByOpaqueSideShelf()) return;
        android.graphics.ColorFilter veilF = veil(tone(0.18f, 0.34f) * Ui.clamp(sideShelfSpring.value), blend(0xFFFFFFFF, 0xFF000000, night));

        // Non-hero screens (Search, Apps, Reels, Smart-Home, Settings) share one unified ambient background
        if (!usesHeroBackdropMode(activeScreen)) {
            drawUnifiedAmbientBackground(c, veilF);
            return;
        }

        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        int themeColor = tone(LIGHT_HERO_BG, 0xFF0B0B0D);
        float v = Math.max(0.12f, 1f - Ui.clamp(sideShelfSpring.value) * 0.72f);

        boolean agslCapable = useShaderVignette && scene.canDrawHero();
        boolean validTo = bgTo != null && !bgTo.isRecycled();
        boolean validFrom = fading && bgFrom != null && bgFrom != bgTo && !bgFrom.isRecycled();
        boolean hasBackdrop = validTo || validFrom;
        if (!hasBackdrop) {
            c.drawColor(themeColor);
            drawUnifiedAmbientBackground(c, veilF);
            if (activeScreen == SCREEN_HOME) {
                leftVigPaint.setColorFilter(veilF);
                bottomVigPaint.setColorFilter(veilF);
                topVigPaint.setColorFilter(veilF);
                leftVigPaint.setAlpha(255);
                bottomVigPaint.setAlpha(255);
                topVigPaint.setAlpha(255);

                c.drawRect(0, 0, w * 0.86f, h, leftVigPaint);
                c.drawRect(0, h * 0.26f, w, h, bottomVigPaint);
                c.drawRect(0, 0, w, h * 0.25f, topVigPaint);

                leftVigPaint.setColorFilter(null);
                bottomVigPaint.setColorFilter(null);
                topVigPaint.setColorFilter(null);
            }
            return;
        }
        boolean canAgslTo = (!validTo) || scene.canDrawHero(bgTo);
        boolean canAgslFrom = (!validFrom) || scene.canDrawHero(bgFrom);
        boolean useAgsl = agslCapable && canAgslTo && canAgslFrom;

        if (useAgsl) {
            c.drawColor(themeColor);
            if (validFrom && bgFrom.image != null) {
                scene.drawHero(c, bgFrom, themeColor, (!validTo ? (1 - e) : 1.0f) * v, veilF, false);
            }
            if (validTo && bgTo.image != null) {
                scene.drawHero(c, bgTo, themeColor, (fading ? e : 1.0f) * v, veilF, false);
            }
        } else {
            c.drawColor(themeColor);
            imagePaint.setColorFilter(veilF);
            boolean fallbackLightBlur = (night < 0.5f);
            if (validFrom) drawLightImage(c, bgFrom, (!validTo ? 1 - e : 1) * v, fallbackLightBlur);
            if (validTo) drawLightImage(c, bgTo, (fading ? e : 1) * v, fallbackLightBlur);
            imagePaint.setColorFilter(null);

            leftVigPaint.setColorFilter(veilF);
            bottomVigPaint.setColorFilter(veilF);
            topVigPaint.setColorFilter(veilF);
            leftVigPaint.setAlpha(255);
            bottomVigPaint.setAlpha(255);
            topVigPaint.setAlpha(255);

            c.drawRect(0, 0, w * 0.86f, h, leftVigPaint);
            c.drawRect(0, h * 0.26f, w, h, bottomVigPaint);
            c.drawRect(0, 0, w, h * 0.25f, topVigPaint);

            leftVigPaint.setColorFilter(null);
            bottomVigPaint.setColorFilter(null);
            topVigPaint.setColorFilter(null);
        }
    }

    private void drawTint(Canvas c) {
        fill.setShader(null);
        fill.setColor(tintColor(blend(tintFrom, tintTo, Ui.clamp(tintMix.value))));
        c.drawRect(screen, fill);
        if (tintShade == null) tintShade = new LinearGradient(0, 0, 0, h, 0x00000000, 0x33000000, Shader.TileMode.CLAMP);
        fill.setShader(tintShade);
        c.drawRect(screen, fill);
        fill.setShader(null);
    }

    private void updateTint() {
        // Background follows focused pick backdrop
    }

    private void easeGlass() {
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha, boolean blurred) {
        if (b == null) return;
        if (blurred && b.softBlurBitmap != null) {
            drawImage(c, b.softBlurBitmap, alpha);
            return;
        }
        if (b.image != null) {
            drawImage(c, b.image, alpha);
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        if (isCoveredByOpaqueSideShelf()) return;
        applyTone();
        titleRight = 0;
        if (activeScreen == SCREEN_APPS) {
            drawAppsScreenGrid(c);
            return;
        }
        if (activeScreen == SCREEN_SETTINGS) {
            drawSettingsScreen(c);
            return;
        }
        if (browserRows.isEmpty()) rebuildScreenRows();

        if (activeScreen == SCREEN_HOME) {
            drawTopDetailsHeader(c);
        }

        float startY = (activeScreen == SCREEN_HOME) ? HOME_ROWS_START_Y * k : 132 * k;
        float clipTop = (activeScreen == SCREEN_HOME) ? startY : 112 * k;

        c.save();
        c.clipRect(0, clipTop, w, h);
        float y = startY - homeScrollY.value;

        if (activeScreen == SCREEN_GAMING) {
            y = drawGamingStatusBanner(c, y);
        }

        for (int r = 0; r < browserRows.size(); r++) {
            BrowserRow row = browserRows.get(r);
            float rh = row.height(k, activeScreen);
            if (y + rh > clipTop + 1f * k && y <= h + 40 * k) {
                boolean rowFocused = (zone == Z_CARDS && !sideShelfOpen && homeRow == r);
                drawBrowserRow(c, row, r, y, rowFocused);
            }
            y += rh;
        }
        c.restore();
    }

    private float drawGamingStatusBanner(Canvas c, float y) {
        return gamingRenderer.drawGamingStatusBanner(
                getContext(), c, y, w, k, MARGIN, gamingStatus,
                dark() > 0.5f, L_DIM, fill, border, lSubTabOn, tmp);
    }

    private float drawSmartHomeBanner(Canvas c, float y) {
        return smartHomeRenderer.drawSmartHomeBanner(
                c, y, k, MARGIN, CONTENT_R, dark() > 0.5f, fill, lSubTab, fitPaint, tmp);
    }

    // MARK: - Native Two-Panel (38% Left Rail / 62% Right Pane) Settings Screen

    private void jumpToSettingsPath(String encodedPath) {
        selectScreen(SCREEN_SETTINGS);
        clearSettingsSearchState(true);
        settingsPathIds.clear();
        settingsStepDepthStack.clear();
        java.util.Arrays.fill(settingsDepthIndices, 0);
        settingsFocusRight = false;
        settingsRightIndex = 0;
        rebuildSettingsTree();
        if (encodedPath != null && !encodedPath.isEmpty() && settingsRoot != null) {
            String[] segs = encodedPath.split(">");
            SettingsTree.Node cur = settingsRoot;
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i];
                int matchIdx = -1;
                SettingsTree.Node matchNode = null;
                for (int ci = 0; ci < cur.children.size(); ci++) {
                    SettingsTree.Node ch = cur.children.get(ci);
                    if (seg.equals(ch.id)) {
                        matchIdx = ci;
                        matchNode = ch;
                        break;
                    }
                }
                if (matchNode == null) break;
                boolean isLast = (i == segs.length - 1);
                if (isLast) {
                    if (settingsPathIds.isEmpty() && i == 0) {
                        settingsDepthIndices[0] = matchIdx;
                        settingsFocusRight = false;
                    } else if (i == settingsPathIds.size() + 1) {
                        settingsFocusRight = true;
                        settingsRightIndex = matchIdx;
                    } else {
                        int d = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                        settingsDepthIndices[d] = matchIdx;
                        settingsFocusRight = false;
                    }
                    break;
                }
                if (i == segs.length - 2) {
                    // Position parent on Left Rail and target on Right Pane
                    int d = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                    settingsDepthIndices[d] = matchIdx;
                    cur = matchNode;
                    continue;
                }
                settingsPathIds.add(matchNode.id);
                settingsStepDepthStack.push(1);
                cur = matchNode;
            }
            rebuildSettingsTree();
        }
        zone = Z_CARDS;
        heroDirty = true;
        heroLayer.invalidate();
        wake();
        click.open();
    }

    private void rebuildSettingsTree() {
        invalidateSettingsSearchCache();
        settingsRoot = SettingsTree.buildRoot(SettingsTree.captureState(getContext(), data));
        SettingsTree.Node cur = settingsRoot;
        for (int i = 0; i < settingsPathIds.size(); i++) {
            String id = settingsPathIds.get(i);
            SettingsTree.Node match = null;
            for (SettingsTree.Node child : cur.children) {
                if (id.equals(child.id)) {
                    match = child;
                    break;
                }
            }
            if (match == null || !match.hasChildren()) {
                while (settingsPathIds.size() > i) {
                    settingsPathIds.remove(settingsPathIds.size() - 1);
                }
                clearSettingsSearchState(true);
                break;
            }
            cur = match;
        }
        boolean searchable = isSettingsColumnSearchable(cur);
        if (!searchable) {
            settingsSearchQuery = "";
            settingsSearchBarFocused = false;
            settingsSearchClearFocused = false;
        }
        List<SettingsTree.Node> displayList = currentSettingsDisplayLeftList(cur);
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        int leftCount = displayList.size();
        if (leftCount > 0) {
            int clamped = Math.max(0, Math.min(settingsDepthIndices[depth], leftCount - 1));
            settingsDepthIndices[depth] = SettingsScreenRenderer.nextSelectableIndex(displayList, clamped, 0);
        } else {
            settingsDepthIndices[depth] = 0;
        }
        SettingsTree.Node leftSel = (leftCount > 0 && settingsDepthIndices[depth] >= 0 && settingsDepthIndices[depth] < leftCount)
                ? displayList.get(settingsDepthIndices[depth]) : null;
        boolean isLastCol = SettingsScreenRenderer.isLastColumnFullWidth(settingsPathIds, cur);
        boolean isPreview = SettingsScreenRenderer.isPreviewColumn(cur);
        if (leftSel == null || !leftSel.hasChildren() || isLastCol || isPreview) {
            settingsFocusRight = false;
            settingsRightIndex = 0;
        } else {
            int rClamped = Math.max(0, Math.min(settingsRightIndex, leftSel.children.size() - 1));
            settingsRightIndex = SettingsScreenRenderer.nextSelectableIndex(leftSel.children, rClamped, 0);
        }
        heroDirty = true;
    }

    private SettingsTree.Node currentSettingsLeftParent() {
        if (settingsRoot == null) rebuildSettingsTree();
        SettingsTree.Node cur = settingsRoot;
        for (String id : settingsPathIds) {
            SettingsTree.Node next = null;
            for (SettingsTree.Node child : cur.children) {
                if (id.equals(child.id)) {
                    next = child;
                    break;
                }
            }
            if (next == null || !next.hasChildren()) break;
            cur = next;
        }
        return cur;
    }

    private SettingsTree.Node currentSettingsLeftNode() {
        SettingsTree.Node parent = currentSettingsLeftParent();
        List<SettingsTree.Node> list = currentSettingsDisplayLeftList(parent);
        if (list.isEmpty()) return null;
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        int idx = SettingsScreenRenderer.nextSelectableIndex(list, settingsDepthIndices[depth], 0);
        if (idx < 0 || idx >= list.size()) return null;
        return list.get(idx);
    }

    private SettingsTree.Node currentSettingsFocusedNode() {
        if (settingsSearchBarFocused && isCurrentSettingsColumnSearchable()) {
            return null;
        }
        SettingsTree.Node left = currentSettingsLeftNode();
        if (settingsFocusRight && left != null && left.hasChildren()) {
            int rIdx = SettingsScreenRenderer.nextSelectableIndex(left.children, settingsRightIndex, 0);
            if (rIdx >= 0 && rIdx < left.children.size()) {
                return left.children.get(rIdx);
            }
        }
        return left;
    }

    private static boolean anyGrandchild(SettingsTree.Node node) {
        return SettingsScreenRenderer.anyGrandchild(node);
    }

    private void drawSettingsScreen(Canvas c) {
        if (settingsRoot == null) rebuildSettingsTree();
        SettingsTree.Node leftParent = currentSettingsLeftParent();
        List<SettingsTree.Node> leftList = currentSettingsDisplayLeftList(leftParent);
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        int leftIdx = leftList.isEmpty() ? 0
                : SettingsScreenRenderer.nextSelectableIndex(leftList, settingsDepthIndices[depth], 0);
        boolean searchable = isSettingsColumnSearchable(leftParent);
        settingsRenderer.drawSettingsSplitScreen(
                getContext(), c, settingsRoot, leftParent, leftList, settingsPathIds, leftIdx,
                settingsFocusRight, settingsRightIndex, zone == Z_CARDS,
                reordering && reorderSettingsGenre,
                searchable ? settingsSearchQuery : "",
                searchable && settingsSearchBarFocused,
                searchable && settingsSearchBarFocused && settingsSearchClearFocused,
                k, MARGIN,
                clockText, dateText, L_INK, L_DIM, WHITE, SHADOW_BLUR,
                dark() > 0.5f, fill, border, shadow, imagePaint,
                lCardTitle, lSubTab, lSubTabOn, fitPaint, shadows, tmp,
                LOADER, onSettingsPreviewDecoded);
    }

    private void moveSettings(int dx, int dy) {
        if (settingsRoot == null) rebuildSettingsTree();
        SettingsTree.Node leftParent = currentSettingsLeftParent();
        boolean searchable = isSettingsColumnSearchable(leftParent);
        List<SettingsTree.Node> leftList = currentSettingsDisplayLeftList(leftParent);
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        int leftIdx = leftList.isEmpty() ? 0
                : SettingsScreenRenderer.nextSelectableIndex(leftList, settingsDepthIndices[depth], 0);
        SettingsTree.Node hoveredLeft = (leftList.isEmpty() || leftIdx < 0 || leftIdx >= leftList.size())
                ? null : leftList.get(leftIdx);

        if (searchable && settingsSearchBarFocused) {
            if (dy < 0) {
                hideSettingsSearchSoftInput();
                zone = Z_TOP;
                topIndex = SCREEN_SETTINGS;
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                click.move();
                return;
            }
            if (dy > 0) {
                hideSettingsSearchSoftInput();
                if (!leftList.isEmpty()) {
                    settingsSearchBarFocused = false;
                    settingsSearchClearFocused = false;
                    settingsDepthIndices[depth] = SettingsScreenRenderer.firstSelectableIndex(leftList);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                }
                return;
            }
            boolean hasQ = settingsSearchQuery != null && !settingsSearchQuery.trim().isEmpty();
            if (dx > 0) {
                if (hasQ && !settingsSearchClearFocused) {
                    hideSettingsSearchSoftInput();
                    settingsSearchClearFocused = true;
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                }
                return;
            }
            if (dx < 0) {
                if (settingsSearchClearFocused) {
                    settingsSearchClearFocused = false;
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                } else {
                    backSettings();
                }
                return;
            }
            return;
        }

        if (dy != 0) {
            if (settingsFocusRight && hoveredLeft != null && hoveredLeft.hasChildren()) {
                int nextR = SettingsScreenRenderer.nextSelectableIndex(hoveredLeft.children, settingsRightIndex, dy);
                if (nextR < 0) {
                    zone = Z_TOP;
                    topIndex = SCREEN_SETTINGS;
                    retarget(false);
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                    return;
                }
                if (nextR != settingsRightIndex) {
                    settingsRightIndex = nextR;
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                }
                return;
            }
            int nextL = leftList.isEmpty() ? -1 : SettingsScreenRenderer.nextSelectableIndex(leftList, leftIdx, dy);
            if (nextL < 0) {
                if (searchable) {
                    settingsSearchBarFocused = true;
                    settingsSearchClearFocused = false;
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                    click.move();
                    return;
                }
                zone = Z_TOP;
                topIndex = SCREEN_SETTINGS;
                retarget(false);
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                click.move();
                return;
            }
            if (nextL != leftIdx) {
                settingsDepthIndices[depth] = nextL;
                SettingsTree.Node nextNode = leftList.get(nextL);
                settingsRightIndex = (nextNode != null && nextNode.hasChildren())
                        ? SettingsScreenRenderer.firstSelectableIndex(nextNode.children)
                        : 0;
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                click.move();
            }
            return;
        }

        if (dx > 0) {
            drillDownSettings(false);
        } else if (dx < 0) {
            backSettings();
        }
    }

    private void activateSettings() {
        drillDownSettings(true);
    }

    private void drillDownSettings(boolean fromOk) {
        if (settingsRoot == null) rebuildSettingsTree();
        SettingsTree.Node leftParent = currentSettingsLeftParent();
        boolean searchable = isSettingsColumnSearchable(leftParent);
        if (searchable && settingsSearchBarFocused) {
            if (fromOk) {
                if (settingsSearchClearFocused) {
                    settingsSearchClearFocused = false;
                    updateSettingsSearchQuery("", false);
                    click.move();
                } else {
                    click.open();
                    showSettingsSearchSoftInput();
                }
            }
            return;
        }
        List<SettingsTree.Node> leftList = currentSettingsDisplayLeftList(leftParent);
        if (leftList.isEmpty()) return;
        int depth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        int leftIdx = SettingsScreenRenderer.nextSelectableIndex(leftList, settingsDepthIndices[depth], 0);
        if (leftIdx < 0 || leftIdx >= leftList.size()) return;
        SettingsTree.Node hoveredLeft = leftList.get(leftIdx);

        boolean isLastCol = SettingsScreenRenderer.isLastColumnFullWidth(settingsPathIds, leftParent);
        boolean isPreview = SettingsScreenRenderer.isPreviewColumn(leftParent);

        // 1. Currently working in the Right Active Pane (62%): act on focused item or slide 1/2 steps deeper
        if (settingsFocusRight && hoveredLeft.hasChildren() && !isLastCol && !isPreview) {
            int rIdx = SettingsScreenRenderer.nextSelectableIndex(hoveredLeft.children, settingsRightIndex, 0);
            if (rIdx < 0 || rIdx >= hoveredLeft.children.size()) return;
            SettingsTree.Node rightNode = hoveredLeft.children.get(rIdx);
            if (rightNode.hasChildren()) {
                if (fromOk) applyIntermediateSettingsNodeIfNeeded(rightNode);
                boolean openAsTerminalOrPreview = !anyGrandchild(rightNode)
                        || SettingsScreenRenderer.isPreviewColumn(rightNode);
                clearSettingsSearchState(true);
                if (openAsTerminalOrPreview) {
                    // Push 2 levels so leaf list expands to 100% (or live preview 42%/58%) and Back returns right here
                    settingsPathIds.add(hoveredLeft.id);
                    int midDepth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                    settingsDepthIndices[midDepth] = rIdx;
                    settingsPathIds.add(rightNode.id);
                    int leafDepth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                    boolean nextSearchable = SettingsScreenRenderer.isSearchableColumn(rightNode);
                    settingsDepthIndices[leafDepth] = nextSearchable
                            ? SettingsScreenRenderer.firstSelectableIndex(rightNode.children)
                            : findCheckedOrFirstIndex(rightNode.children);
                    settingsSearchBarFocused = nextSearchable;
                    settingsSearchClearFocused = false;
                    settingsStepDepthStack.push(2);
                    settingsRightIndex = 0;
                    settingsFocusRight = false;
                } else {
                    // Slide 1 level: rightNode becomes anchored on the Left Rail (38%), its children open in Right Pane (62%)
                    settingsPathIds.add(hoveredLeft.id);
                    int newDepth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
                    settingsDepthIndices[newDepth] = rIdx;
                    settingsStepDepthStack.push(1);
                    settingsRightIndex = findCheckedOrFirstIndex(rightNode.children);
                    settingsFocusRight = true;
                }
                rebuildSettingsTree();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
                click.open();
            } else if (fromOk) {
                click.open();
                executeSettingsNode(rightNode);
            }
            return;
        }

        // 2. Currently focused in Left Rail (38%), Live Preview Options (42%), or Full-Width Leaf (100%)
        if (!hoveredLeft.hasChildren()) {
            if (fromOk) {
                click.open();
                executeSettingsNode(hoveredLeft);
            }
            return;
        }

        if (fromOk) {
            applyIntermediateSettingsNodeIfNeeded(hoveredLeft);
        }

        // True Native Two-Panel behavior: pressing Right or OK on a Left Rail category moves focus directly
        // into the spacious Right Pane while keeping the Left Rail anchored for effortless orientation.
        if (!isLastCol && !isPreview) {
            settingsFocusRight = true;
            settingsRightIndex = findCheckedOrFirstIndex(hoveredLeft.children);
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.open();
            return;
        }

        clearSettingsSearchState(true);
        settingsPathIds.add(hoveredLeft.id);
        int newDepth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
        boolean nextSearchable = SettingsScreenRenderer.isSearchableColumn(hoveredLeft);
        settingsDepthIndices[newDepth] = nextSearchable
                ? SettingsScreenRenderer.firstSelectableIndex(hoveredLeft.children)
                : findCheckedOrFirstIndex(hoveredLeft.children);
        settingsSearchBarFocused = nextSearchable;
        settingsSearchClearFocused = false;
        settingsStepDepthStack.push(1);
        settingsRightIndex = 0;
        settingsFocusRight = false;
        rebuildSettingsTree();
        heroDirty = true;
        heroLayer.invalidate();
        wake();
        click.open();
    }

    private static int findCheckedOrFirstIndex(List<SettingsTree.Node> children) {
        return SettingsScreenRenderer.findCheckedOrFirstIndex(children);
    }

    private void applyIntermediateSettingsNodeIfNeeded(SettingsTree.Node node) {
        if (node == null) return;
        if ("theme_auto".equals(node.id)) {
            setThemePreference("auto", null);
        } else if ("theme_auto_sun".equals(node.id)) {
            setThemePreference("auto", "sun");
            showToast(Ui.tr("Theme: Auto (By Sunrise / Sunset)"));
        } else if ("theme_auto_hours".equals(node.id)) {
            setThemePreference("auto", "hours");
            showToast(Ui.tr("Theme: Auto (By Hours)"));
        }
    }

    private boolean backSettings() {
        hideSettingsSearchSoftInput();
        if (isCurrentSettingsColumnSearchable() && settingsSearchQuery != null && !settingsSearchQuery.isEmpty()) {
            settingsSearchBarFocused = true;
            settingsSearchClearFocused = false;
            updateSettingsSearchQuery("", false);
            click.move();
            return true;
        }
        clearSettingsSearchState(true);
        if (settingsFocusRight) {
            settingsFocusRight = false;
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return true;
        }
        if (!settingsPathIds.isEmpty()) {
            int poppedDepth = Math.min(settingsPathIds.size(), settingsDepthIndices.length - 1);
            int stepsToPop = settingsStepDepthStack.isEmpty() ? 1 : settingsStepDepthStack.pop();
            int actualPopped = 0;
            while (actualPopped < stepsToPop && !settingsPathIds.isEmpty()) {
                settingsPathIds.remove(settingsPathIds.size() - 1);
                actualPopped++;
            }
            settingsFocusRight = (actualPopped >= 2);
            if (!settingsFocusRight) {
                SettingsTree.Node curLeft = currentSettingsLeftNode();
                int childCount = (curLeft != null && curLeft.hasChildren()) ? curLeft.children.size() : 0;
                int restoredIdx = HomeInputRouter.computeBackSettingsRightIndex(settingsDepthIndices, poppedDepth, childCount);
                if (curLeft != null && curLeft.hasChildren() && restoredIdx >= 0 && restoredIdx < curLeft.children.size()
                        && !curLeft.children.get(restoredIdx).isSectionHeader()) {
                    settingsRightIndex = restoredIdx;
                } else {
                    settingsRightIndex = (curLeft != null && curLeft.hasChildren())
                            ? SettingsScreenRenderer.firstSelectableIndex(curLeft.children)
                            : 0;
                }
            }
            rebuildSettingsTree();
            heroDirty = true;
            heroLayer.invalidate();
            wake();
            click.move();
            return true;
        }
        return false;
    }

    private void openPairedRemoteManageModal(Context c, String deviceId) {
        RemoteAuthManager auth = RemoteAuthManager.get(c);
        RemoteAuthManager.PairedDevice target = null;
        for (RemoteAuthManager.PairedDevice d : auth.listDevices()) {
            if (d != null && deviceId.equals(d.deviceId)) {
                target = d;
                break;
            }
        }
        if (target == null) {
            rebuildSettingsTree();
            showToast(Ui.tr("Remote is no longer paired"));
            return;
        }
        final RemoteAuthManager.PairedDevice dev = target;
        String[] choices = new String[] {
                Ui.tr("Rename Phone / Set Nickname"),
                Ui.tr("Set as Guest (30 Days)"),
                Ui.tr("Set as Household"),
                Ui.tr("Set as Admin"),
                Ui.tr("Kick / Revoke Remote")
        };
        int initialIdx = 0;
        openLiquidGlassChoiceModal(
                dev.deviceName,
                Ui.tr("Rename this phone, select its access level, or revoke it"),
                choices,
                null,
                initialIdx,
                Ui.tr("Cancel"),
                idx -> {
                    if (idx == 0) {
                        openLiquidGlassInputModal(
                                Ui.tr("Rename Companion Phone"),
                                Ui.tr("Give this linked phone a friendly nickname (e.g. Alex's Phone)"),
                                Ui.tr("Phone nickname"),
                                dev.deviceName,
                                false,
                                Ui.tr("Save Name"),
                                newName -> {
                                    String clean = newName != null ? newName.trim() : "";
                                    if (clean.isEmpty()) {
                                        showToast(Ui.tr("Nickname cannot be empty"));
                                        return;
                                    }
                                    if (auth.renameDevice(dev.deviceId, clean)) {
                                        showToast(Ui.tr("Renamed phone to: ") + clean);
                                        rebuildSettingsTree();
                                        heroDirty = true;
                                        heroLayer.invalidate();
                                        wake();
                                    }
                                },
                                null);
                        return;
                    } else if (idx == 1) {
                        auth.setDeviceRole(dev.deviceId, RemoteAuthManager.Role.GUEST);
                        showToast(Ui.tr("Updated to Guest (30 days): ") + dev.deviceName);
                    } else if (idx == 2) {
                        auth.setDeviceRole(dev.deviceId, RemoteAuthManager.Role.HOUSEHOLD);
                        showToast(Ui.tr("Updated to Household: ") + dev.deviceName);
                    } else if (idx == 3) {
                        auth.setDeviceRole(dev.deviceId, RemoteAuthManager.Role.ADMIN);
                        showToast(Ui.tr("Updated to Admin: ") + dev.deviceName);
                    } else if (idx == 4) {
                        auth.revokeDevice(dev.deviceId);
                        showToast(Ui.tr("Revoked remote: ") + dev.deviceName);
                    }
                    rebuildSettingsTree();
                    heroDirty = true;
                    heroLayer.invalidate();
                    wake();
                },
                null);
    }

    private void triggerManualCloudSync(Context c) {
        if (!CloudSyncManager.get(c).isCloudSyncConfigured()) {
            showToast(Ui.tr("Link this TV with Setup Studio (Supabase URL, Anon Key & Household Key) first"));
            return;
        }
        showToast(Ui.tr("Syncing Cloud Settings & Encrypted Vault..."));
        CloudSyncManager.get(c).syncNowAsync("manual", () -> {
            Prefs.reload();
            recs.clearRowCache();
            recs.refreshIfStale();
            reload();
            rebuildSettingsTree();
            layoutLight();
            statusDirty = true;
            heroDirty = true;
            invalidateAll();
            CloudSyncManager.SyncSummary sum = CloudSyncManager.get(c).getSummary();
            String msg = (sum.statusText == null || sum.statusText.isEmpty())
                    ? Ui.tr("Cloud sync complete")
                    : (Ui.tr("Cloud Sync: ") + sum.statusText);
            showToast(msg);
        });
    }

    private void executeSettingsNode(SettingsTree.Node node) {
        if (node == null) return;
        String id = node.id;
        Context c = getContext().getApplicationContext();

        if ("theme_dark".equals(id)) {
            setThemePreference("dark", null);
            showToast(Ui.tr("Theme: Dark Mode"));
        } else if ("theme_light".equals(id)) {
            setThemePreference("light", null);
            showToast(Ui.tr("Theme: Light Mode"));
        } else if ("theme_auto".equals(id) || "theme_auto_sun".equals(id)) {
            setThemePreference("auto", "sun");
            showToast(Ui.tr("Theme: Auto (By Sunrise / Sunset)"));
        } else if ("theme_auto_hours".equals(id)) {
            setThemePreference("auto", "hours");
            showToast(Ui.tr("Theme: Auto (By Hours)"));
        } else if ("theme_sun_location".equals(id)) {
            setThemePreference("auto", "sun");
            openCitySearchDialog();
        } else if ("theme_sun_timezone".equals(id) || "theme_sun_sunrise".equals(id) || "theme_sun_sunset".equals(id)) {
            setThemePreference("auto", "sun");
            showToast(node.title + ": " + node.value);
        } else if (id.startsWith("theme_set_dark_hour:")) {
            try {
                int h = Integer.parseInt(id.substring("theme_set_dark_hour:".length()));
                Prefs.setAutoDarkHour(h);
                setThemePreference("auto", "hours");
                showToast(Ui.tr("Dark Mode starts at ") + SettingsTree.formatHour(h));
            } catch (NumberFormatException ignored) {}
        } else if (id.startsWith("theme_set_light_hour:")) {
            try {
                int h = Integer.parseInt(id.substring("theme_set_light_hour:".length()));
                Prefs.setAutoLightHour(h);
                setThemePreference("auto", "hours");
                showToast(Ui.tr("Light Mode starts at ") + SettingsTree.formatHour(h));
            } catch (NumberFormatException ignored) {}
        } else if (id.startsWith("profile_select:")) {
            String profId = id.substring("profile_select:".length());
            UserProfileManager.get(c).switchProfile(profId);
            recs.clearRowCache();
            recs.refreshIfStale();
            reload();
            updateCards();
            rebuildSettingsTree();
            showToast(Ui.tr("Profile: ") + node.title);
        } else if ("profile_cycle".equals(id)) {
            UserProfileManager.Profile nextProf = UserProfileManager.get(c).cycleNextProfile();
            recs.clearRowCache();
            recs.refreshIfStale();
            reload();
            updateCards();
            rebuildSettingsTree();
            showToast(Ui.tr("Profile: ") + nextProf.displayName);
        } else if ("trakt_auth".equals(id)) {
            openTraktAuthDialog();
        } else if ("stremio_auth".equals(id)) {
            openStremioAuthDialog();
        } else if ("reels_connect_accounts".equals(id) || "setup_reels_accounts".equals(id)) {
            launchReelsConnectSetup();
        } else if ("users_sync_all".equals(id)) {
            Prefs.setLastAccountSyncAt(System.currentTimeMillis());
            rebuildSettingsTree();
            startSyncModal(
                    Ui.tr("Syncing Trakt & Stremio Accounts"),
                    Ui.tr("Fetching your latest Watch List, Stremio Library, watch history, and recommendations…"),
                    12);
            handler.postDelayed(() -> {
                recs.forceRefreshNow();
                reload();
            }, 32);
        } else if ("setup_wizard_start".equals(id)) {
            runGuidedInitialSetupStep(c);
        } else if ("setup_pair_remote".equals(id)) {
            openInitialSetupRemotePairing(c);
        } else if ("setup_mark_done".equals(id)) {
            Prefs.setInitialSetupDone(true);
            rebuildSettingsTree();
            if (settingsPathIds.isEmpty() && settingsRoot != null) {
                for (int i = 0; i < settingsRoot.children.size(); i++) {
                    SettingsTree.Node child = settingsRoot.children.get(i);
                    if (child != null && "initial_setup".equals(child.id)) {
                        settingsDepthIndices[0] = i;
                        if (settingsFocusRight && child.hasChildren()) {
                            int rClamped = Math.max(0, Math.min(settingsRightIndex, child.children.size() - 1));
                            settingsRightIndex = SettingsScreenRenderer.nextSelectableIndex(child.children, rClamped, 0);
                        }
                        break;
                    }
                }
            }
            showToast(Ui.tr("Initial Setup marked as complete"));
        } else if ("setup_google_home".equals(id)) {
            SmartHomeManager.get(c).executeRoutine(activity, "google_home_voice", (ok, msg) -> handler.post(() -> showToast(msg)));
        } else if (id.startsWith("setup_key_")) {
            openApiKeySetupDialog(id);
        } else if ("cloud_sync_now".equals(id) || "setup_cloud_sync_now".equals(id)) {
            triggerManualCloudSync(c);
        } else if ("stremio_use_household".equals(id)) {
            boolean next = !Prefs.useHouseholdStremio();
            Prefs.setUseHouseholdStremio(next);
            recs.clearRowCache();
            recs.refreshIfStale();
            reload();
            rebuildSettingsTree();
            showToast(Ui.tr("Household Shared Stremio: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("tv_override_shelf".equals(id)) {
            boolean next = !Prefs.isTvOverrideShelfEnabled();
            Prefs.setTvOverrideShelfEnabled(next);
            reload();
            rebuildSettingsTree();
            showToast(Ui.tr("Favorite Apps Shelf: ") + (next ? Ui.tr("Custom for This TV") : Ui.tr("Synced with Profile")));
        } else if ("tv_override_hidden_apps".equals(id)) {
            boolean next = !Prefs.isTvOverrideHiddenAppsEnabled();
            Prefs.setTvOverrideHiddenAppsEnabled(next);
            reload();
            rebuildSettingsTree();
            showToast(Ui.tr("Hidden Apps: ") + (next ? Ui.tr("Custom for This TV") : Ui.tr("Synced with Profile")));
        } else if ("tv_override_screensaver".equals(id)) {
            boolean next = !Prefs.isTvOverrideScreensaverEnabled();
            Prefs.setTvOverrideScreensaverEnabled(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Screensaver: ") + (next ? Ui.tr("Custom for This TV") : Ui.tr("Synced with Profile")));
        } else if (id.startsWith("remote_invite:")) {
            String roleStr = id.substring("remote_invite:".length());
            RemoteAuthManager.Role role = RemoteAuthManager.Role.fromString(roleStr, RemoteAuthManager.Role.GUEST);
            String lanUrl = RemoteControlServer.get(c).getLanUrl();
            RemoteAuthManager.get(c).createInvite(role, lanUrl);
            refreshPairingPromptState();
            rebuildSettingsTree();
        } else if (id.startsWith("remote_manage:")) {
            openPairedRemoteManageModal(c, id.substring("remote_manage:".length()));
        } else if (id.startsWith("tv_room_set:")) {
            String room = id.substring("tv_room_set:".length());
            SmartHomeManager.get(c).setTvRoom(room);
            homeChanged();
            rebuildSettingsTree();
            showToast(Ui.tr("TV Room set to: ") + room);
        } else if ("tv_room_add".equals(id)) {
            openAddRoomDialog();
        } else if (id.startsWith("notif_dismiss:")) {
            String key = id.substring("notif_dismiss:".length());
            NowPlaying.dismissNotice(key);
            rebuildSettingsTree();
            showToast(Ui.tr("Notification dismissed"));
        } else if ("notif_clear_all".equals(id)) {
            NowPlaying.clearAllNotices();
            rebuildSettingsTree();
            showToast(Ui.tr("Cleared all system notifications"));
        } else if ("users_google".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_SYNC_SETTINGS);
        } else if (id.startsWith("ci_tuner:")) {
            String mode = id.substring("ci_tuner:".length());
            Prefs.setTunerMode(mode);
            rebuildSettingsTree();
            showToast(Ui.tr("Tuner Mode: ") + node.title);
        } else if ("ci_auto_update".equals(id)) {
            boolean next = !Prefs.autoUpdateChannels();
            Prefs.setAutoUpdateChannels(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Update Channels Automatically: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ci_channel_scan".equals(id) || "ci_manual_digital".equals(id) || "ci_manual_analog".equals(id)) {
            openTvChannelOrInputSetting(node.title);
        } else if (id.startsWith("ci_input_id:")) {
            openTvInputById(id.substring("ci_input_id:".length()), node.title);
        } else if (id.startsWith("ci_input:")) {
            openTvInputSource(id.substring("ci_input:".length()), node.title);
        } else if ("ci_cast_open".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent(android.provider.Settings.ACTION_CAST_SETTINGS),
                    new Intent(android.provider.Settings.ACTION_SETTINGS));
        } else if ("ci_hdmi_cec".equals(id) || "ci_airplay_open".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_SETTINGS);
        } else if ("bt_sys_accessories".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent("com.android.tv.settings.DEVICES").setPackage("com.android.tv.settings"),
                    new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                    new Intent(android.provider.Settings.ACTION_SETTINGS));
        } else if ("ds_res_4k".equals(id)) {
            Ui.request4kDisplayMode(activity.getWindow());
            showToast(Ui.tr("4K UHD (3840×2160) display mode active"));
        } else if ("ds_nav_sounds".equals(id)) {
            boolean next = !Prefs.sounds();
            Prefs.setSounds(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Navigation Click Sounds: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ds_display_open".equals(id) || "ds_picture_open".equals(id)
                || "ds_hdr".equals(id) || "ds_overscan".equals(id) || "ds_pic_mode".equals(id)
                || "ds_pic_backlight".equals(id) || "ds_pic_color".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).setPackage("com.android.tv.settings"),
                    new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
        } else if ("ds_sound_open".equals(id) || "ds_audio_out".equals(id) || "ds_dolby".equals(id) || "ds_sys_sound".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage("com.android.tv.settings"),
                    new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
        } else if ("net_wifi".equals(id) || "net_advanced".equals(id) || "net_ip_dhcp".equals(id) || "net_ip_static".equals(id)
                || "net_proxy_none".equals(id) || "net_proxy_manual".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_WIFI_SETTINGS);
        } else if ("net_details".equals(id) || "sys_about_android".equals(id) || "sys_about_soartv".equals(id)) {
            showToast(node.title + (node.value.isEmpty() ? "" : (": " + node.value)));
        } else if ("net_status_icons".equals(id)) {
            boolean next = !Prefs.statusIcons();
            Prefs.setStatusIcons(next);
            layoutLight();
            statusDirty = true;
            status.invalidate();
            rebuildSettingsTree();
            showToast(Ui.tr("Top-Bar Status Icons: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if (id.startsWith("ui_clock_fmt:")) {
            String fmt = id.substring("ui_clock_fmt:".length());
            Prefs.setClockFormat(fmt);
            clock24 = Ui.h24();
            clockMinute = -1;
            updateClock();
            statusDirty = true;
            status.invalidate();
            rebuildSettingsTree();
            showToast(Ui.tr("Clock Format: ") + node.title);
        } else if (id.startsWith("ui_temp_unit:")) {
            String unit = id.substring("ui_temp_unit:".length());
            Prefs.setTemperature(unit);
            data.settingsChanged();
            rebuildSettingsTree();
            showToast(Ui.tr("Temperature Unit: ") + node.title);
        } else if (id.startsWith("ui_recents:")) {
            String mode = id.substring("ui_recents:".length());
            Prefs.setRecents(mode);
            rebuildSettingsTree();
            showToast(Ui.tr("Recent Apps: ") + node.title);
        } else if (id.startsWith("ui_tab_gaming:")) {
            String mode = id.substring("ui_tab_gaming:".length());
            FeatureTabs.setGamingModePref(mode);
            layoutLight();
            rebuildSettingsTree();
            statusDirty = true;
            status.invalidate();
            showToast(Ui.tr("Gaming Tab: ") + node.title);
        } else if (id.startsWith("ui_tab_smarthome:")) {
            String mode = id.substring("ui_tab_smarthome:".length());
            FeatureTabs.setSmartHomeModePref(mode);
            layoutLight();
            rebuildSettingsTree();
            statusDirty = true;
            status.invalidate();
            showToast(Ui.tr("Smart Home Tab: ") + node.title);
        } else if ("priv_sys_location".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        } else if ("priv_assistant_open".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
                    new Intent(android.provider.Settings.ACTION_SETTINGS));
        } else if ("priv_usage".equals(id) || "priv_ads".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_PRIVACY_SETTINGS);
        } else if ("priv_perm_manage".equals(id) || "apps_see_all".equals(id) || "apps_permissions".equals(id) || "apps_spec_system".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        } else if ("priv_perm_special".equals(id) || "apps_spec_overlay".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        } else if ("apps_spec_usage".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
        } else if ("apps_security".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
        } else if ("sys_about_device".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
        } else if ("sys_dev_options".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        } else if ("sys_dt_open".equals(id) || "sys_dt_tz".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_DATE_SETTINGS);
        } else if (id.startsWith("sys_soartv_lang:")) {
            String code = id.substring("sys_soartv_lang:".length());
            Prefs.setLanguage(code);
            data.settingsChanged();
            rebuildScreenRows();
            rebuildSettingsTree();
            showToast(Ui.tr("Language: ") + node.title);
        } else if ("sys_language".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_LOCALE_SETTINGS);
        } else if ("sys_keyboard".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
        } else if ("sys_storage".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        } else if ("sys_sleep_modal".equals(id)) {
            openSleepTimerModal();
        } else if ("sys_sleep_show".equals(id)) {
            boolean next = !Prefs.sleepShown();
            Prefs.setSleepShown(next);
            SleepOverlay.update(c);
            rebuildSettingsTree();
            showToast(Ui.tr("Show Sleep Time Left: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if (id.startsWith("sys_sleep_place:")) {
            try {
                int place = Integer.parseInt(id.substring("sys_sleep_place:".length()));
                Prefs.setSleepPlace(place);
                SleepOverlay.update(c);
                rebuildSettingsTree();
                showToast(Ui.tr("Timer Badge Position: ") + node.title);
            } catch (NumberFormatException ignored) {}
        } else if (id.startsWith("sys_power_on:")) {
            String val = id.substring("sys_power_on:".length());
            Prefs.setPowerOn(val);
            rebuildSettingsTree();
            showToast(Ui.tr("At Power-On: ") + node.title);
        } else if (id.startsWith("sys_sleep:")) {
            try {
                int mins = Integer.parseInt(id.substring("sys_sleep:".length()));
                SleepTimer.set(c, Math.max(0, mins));
                updateSleep();
                rebuildSettingsTree();
                showToast(mins > 0 ? (Ui.tr("Sleep Timer: ") + mins + " min") : Ui.tr("Sleep Timer: Off"));
            } catch (NumberFormatException ignored) {}
        } else if ("sys_restart".equals(id) || "sys_restart_tv".equals(id)) {
            openRestartTvConfirmationModal(c);
        } else if ("sys_power_open".equals(id) || "atv_open_full".equals(id) || "native_settings".equals(id)) {
            HomeApps.open(activity, HomeApps.tvSettings(), this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
        } else if ("sys_a11y".equals(id)) {
            openAndroidSettingIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        } else if ("ss_sys_dream".equals(id)) {
            openAndroidSettingIntentWithFallback(
                    new Intent(android.provider.Settings.ACTION_DREAM_SETTINGS),
                    new Intent(android.provider.Settings.ACTION_SETTINGS));
        } else if ("gaming_bt_pair".equals(id)) {
            openInAppBluetoothPairingDialog(null);
        } else if ("gaming_ctrl_wizard".equals(id)) {
            openGuidedControllerSetupWizard(1);
        } else if (id.startsWith("gaming_ctrl_item:")) {
            String desc = id.substring("gaming_ctrl_item:".length());
            ControllerManager.ControllerInfo target = null;
            for (ControllerManager.ControllerInfo ci : ControllerManager.getAllKnownControllers(c)) {
                if (desc.equals(ci.descriptor)) {
                    target = ci;
                    break;
                }
            }
            if (target != null) {
                openControllerSetupOrEditDialog(target, null);
            }
        } else if (id.startsWith("gaming_color_swatch:")) {
            String name = id.substring("gaming_color_swatch:".length());
            for (ControllerManager.ColorOption opt : ControllerManager.RAINBOW_COLORS) {
                if (opt.name.equals(name)) {
                    promptApplyColorToController(opt.name, opt.argb);
                    break;
                }
            }
        } else if ("gaming_color_custom".equals(id)) {
            openCustomColorShadeDialog(null);
        } else if ("rem_buttons".equals(id) || "lu_homemode".equals(id) || "lu_classic".equals(id)) {
            HomeApps.open(activity, HomeApps.settings(), this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
        } else if ("lu_ota".equals(id)) {
            Updates.check(c);
            showToast(Ui.tr("Checking GitHub for SoarTV updates…"));
        } else if (id.startsWith("genre_tv_item:") || id.startsWith("genre_movie_item:")) {
            boolean isTv = id.startsWith("genre_tv_item:");
            String mediaType = isTv ? "tv" : "movie";
            String g = id.substring(id.indexOf(':') + 1);
            boolean nextHidden = !Prefs.isGenreHidden(mediaType, g);
            Prefs.setGenreHidden(mediaType, g, nextHidden);
            recs.onUserOrderChanged(mediaType);
            updateCards();
            rebuildSettingsTree();
            showToast(g + ": " + (nextHidden ? Ui.tr("Hidden from Home Screen") : Ui.tr("Visible on Home Screen")));
        } else if ("hg_cartoons".equals(id)) {
            boolean nextHide = !Prefs.hideAnimation();
            Prefs.setHideAnimation(nextHide);
            recs.clearRowCache();
            recs.refreshIfStale();
            rebuildSettingsTree();
            showToast(Ui.tr("Cartoons in General Rows: ") + (!nextHide ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("hg_reset_genres".equals(id)) {
            Prefs.setGenreOrder("tv", java.util.Arrays.asList(Prefs.DEFAULT_GENRES));
            Prefs.setGenreOrder("movie", java.util.Arrays.asList(Prefs.DEFAULT_GENRES));
            for (String g : Prefs.DEFAULT_GENRES) {
                Prefs.setGenreHidden("tv", g, false);
                Prefs.setGenreHidden("movie", g, false);
            }
            recs.onUserOrderChanged(subTab == 0 ? "tv" : "movie");
            updateCards();
            rebuildSettingsTree();
            showToast(Ui.tr("Genres reset to default (all visible)"));
        } else if ("ss_preview".equals(id)) {
            try {
                if (activity instanceof HomeActivity) {
                    ((HomeActivity) activity).startScreensaverNow();
                } else {
                    Intent previewIntent = new Intent(activity, SettingsActivity.class)
                            .putExtra("screensaver", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(previewIntent);
                }
            } catch (RuntimeException e) {
                showToast(Ui.tr("Unable to start screensaver"));
            }
        } else if (id.startsWith("ss_cat:")) {
            String cat = id.substring("ss_cat:".length());
            boolean success = Prefs.toggleSaverCategory(cat);
            if (!success) {
                showToast(Ui.tr("At least one category must remain enabled"));
            } else {
                ScreensaverCatalog.reprioritizePrefetch(getContext());
                rebuildSettingsTree();
                boolean nowChecked = Prefs.saverCategories().contains(cat.toLowerCase(Locale.ROOT));
                showToast(node.title + ": " + (nowChecked ? Ui.tr("Enabled") : Ui.tr("Disabled")));
            }
        } else if (id.startsWith("ss_clock:")) {
            String st = id.substring("ss_clock:".length());
            Prefs.setSaverClockStyle(st);
            rebuildSettingsTree();
            showToast(Ui.tr("Screensaver Clock: ") + node.title);
        } else if ("ss_animation".equals(id)) {
            boolean next = !Prefs.saverAnimation();
            Prefs.setSaverAnimation(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Image Animations: ") + (next ? Ui.tr("Enabled") : Ui.tr("Disabled")));
        } else if ("ss_interval".equals(id)) {
            openSlideDurationModal();
        } else if (id.startsWith("ss_interval:")) {
            try {
                long ms = Long.parseLong(id.substring("ss_interval:".length()));
                Prefs.setSaverIntervalMs(ms);
                rebuildSettingsTree();
                showToast(Ui.tr("Slide Duration: ") + node.title);
            } catch (NumberFormatException ignored) {}
        } else if ("ss_toggle_clock".equals(id)) {
            boolean next = !Prefs.saverShowLocalTime();
            Prefs.setSaverShowLocalTime(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Main Clock: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_weather".equals(id)) {
            boolean next = !Prefs.saverShowWeather();
            Prefs.setSaverShowWeather(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Weather: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_title".equals(id)) {
            boolean next = !Prefs.saverShowArtworkTitle();
            Prefs.setSaverShowArtworkTitle(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Artwork Title: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_artist".equals(id)) {
            boolean next = !Prefs.saverShowArtist();
            Prefs.setSaverShowArtist(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Artist Name: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_location".equals(id)) {
            boolean next = !Prefs.saverShowLocation();
            Prefs.setSaverShowLocation(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Location: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_world_clock".equals(id)) {
            boolean next = !Prefs.saverShowWorldClock();
            Prefs.setSaverShowWorldClock(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Second Clock: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("ss_toggle_calendar".equals(id)) {
            boolean next = !Prefs.saverShowCalendar();
            Prefs.setSaverShowCalendar(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Date & Calendar: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if (id.startsWith("ss_tz:")) {
            String[] parts = id.substring("ss_tz:".length()).split(":", 2);
            if (parts.length >= 2) {
                Prefs.setSaverWorldClock(parts[0], parts[1]);
                rebuildSettingsTree();
                showToast(Ui.tr("Second Clock: ") + node.title);
            }
        } else if ("shg_wiz_scan".equals(id)) {
            startSyncModal(
                    Ui.tr("Scanning Wi-Fi for Smart Bulbs"),
                    Ui.tr("Broadcasting UDP discovery on local network for WiZ smart bulbs…"),
                    28);
            SmartHomeManager.get(c).executeRoutine(activity, "discover_wiz", (ok, msg) -> handler.post(() -> {
                updateSyncModal(msg, 100, true);
                showToast(msg);
                homeChanged();
                rebuildSettingsTree();
            }));
        } else if ("shg_hdmi_ports".equals(id)) {
            GamingZone.showHdmiPortsSetupDialog(activity, () -> {
                rebuildScreenRows();
                rebuildSettingsTree();
            });
        } else if ("shg_pc_pair".equals(id)) {
            GamingZone.promptLaptopPairingDialog(activity, this, () -> {
                rebuildScreenRows();
                rebuildSettingsTree();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });
        } else if ("shg_pc_ip".equals(id)) {
            GamingZone.promptLaptopIpDialog(activity, this, () -> {
                rebuildScreenRows();
                rebuildSettingsTree();
                heroDirty = true;
                heroLayer.invalidate();
                wake();
            });
        } else if ("shg_pc_sync".equals(id)) {
            startSyncModal(
                    Ui.tr("Syncing Gaming PC Library"),
                    Ui.tr("Connecting to Sunshine & Steam on Gaming PC…"),
                    30);
            refreshGamingScreenData(true);
            handler.postDelayed(() -> updateSyncModal(Ui.tr("Gaming PC sync finished"), 100, true), 1800);
        } else if ("shg_pc_wake".equals(id)) {
            showToast(GamingZone.launch(activity, this));
        } else if (id.startsWith("gaming_set_res:")) {
            String res = id.substring("gaming_set_res:".length());
            Prefs.setGamingResolution(res);
            rebuildSettingsTree();
            showToast(Ui.tr("Stream Resolution: ") + res.toUpperCase(java.util.Locale.ROOT));
        } else if (id.startsWith("gaming_set_fps:")) {
            try {
                int fps = Integer.parseInt(id.substring("gaming_set_fps:".length()));
                Prefs.setGamingFps(fps);
                rebuildSettingsTree();
                showToast(Ui.tr("Stream Frame Rate: ") + fps + " FPS");
            } catch (NumberFormatException ignored) {}
        } else if (id.startsWith("gaming_set_bitrate:")) {
            try {
                int mbps = Integer.parseInt(id.substring("gaming_set_bitrate:".length()));
                Prefs.setGamingBitrateMbps(mbps);
                rebuildSettingsTree();
                showToast(Ui.tr("Video Bitrate: ") + mbps + " Mbps");
            } catch (NumberFormatException ignored) {}
        } else if (id.startsWith("gaming_set_pacing:")) {
            String pacing = id.substring("gaming_set_pacing:".length());
            Prefs.setGamingFramePacing(pacing);
            rebuildSettingsTree();
            showToast(Ui.tr("Frame Pacing: ") + node.title);
        } else if (id.startsWith("gaming_set_codec:")) {
            String codec = id.substring("gaming_set_codec:".length());
            Prefs.setGamingCodec(codec);
            rebuildSettingsTree();
            showToast(Ui.tr("Video Codec: ") + node.title);
        } else if ("gaming_stream_hdr".equals(id)) {
            boolean next = !Prefs.gamingHdr();
            Prefs.setGamingHdr(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Stream HDR10: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("gaming_stream_optimize".equals(id)) {
            boolean next = !Prefs.gamingOptimizeSettings();
            Prefs.setGamingOptimizeSettings(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Optimize Game Settings: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if (id.startsWith("gaming_set_audio:")) {
            String audio = id.substring("gaming_set_audio:".length());
            Prefs.setGamingAudioConfig(audio);
            rebuildSettingsTree();
            showToast(Ui.tr("Stream Audio: ") + node.title);
        } else if ("gaming_stream_pc_audio".equals(id)) {
            boolean next = !Prefs.gamingPlayAudioOnPc();
            Prefs.setGamingPlayAudioOnPc(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Play Audio on Gaming PC: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("gaming_stream_rumble".equals(id)) {
            boolean next = !Prefs.gamingRumble();
            Prefs.setGamingRumble(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Controller Rumble: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("gaming_stream_mouse_emu".equals(id)) {
            boolean next = !Prefs.gamingMouseEmulation();
            Prefs.setGamingMouseEmulation(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Virtual Mouse Mode: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        } else if ("gaming_stream_perf_overlay".equals(id)) {
            boolean next = !Prefs.gamingPerfOverlay();
            Prefs.setGamingPerfOverlay(next);
            rebuildSettingsTree();
            showToast(Ui.tr("Performance Overlay: ") + (next ? Ui.tr("On") : Ui.tr("Off")));
        }
        heroDirty = true;
        heroLayer.invalidate();
        wake();
    }

    private void openInitialSetupRemotePairing(Context c) {
        RemoteAuthManager auth = RemoteAuthManager.get(c);
        String lanUrl = RemoteControlServer.get(c).getLanUrl();
        if (!auth.hasAnyAdminDevice()) {
            auth.createInvite(RemoteAuthManager.Role.ADMIN, lanUrl);
            refreshPairingPromptState();
            rebuildSettingsTree();
            return;
        }
        String[] choices = new String[] {
                Ui.tr("Pair Another Admin Phone (QR)"),
                Ui.tr("Invite Household Phone (QR)"),
                Ui.tr("Invite Guest Phone — 30 Days (QR)"),
                Ui.tr("Manage & Rename Paired Phones")
        };
        openLiquidGlassChoiceModal(
                Ui.tr("Companion Phone Remote"),
                Ui.tr("Pair an additional phone or rename already-linked phones"),
                choices,
                null,
                0,
                Ui.tr("Cancel"),
                idx -> {
                    if (idx == 0) {
                        auth.createInvite(RemoteAuthManager.Role.ADMIN, lanUrl);
                        refreshPairingPromptState();
                    } else if (idx == 1) {
                        auth.createInvite(RemoteAuthManager.Role.HOUSEHOLD, lanUrl);
                        refreshPairingPromptState();
                    } else if (idx == 2) {
                        auth.createInvite(RemoteAuthManager.Role.GUEST, lanUrl);
                        refreshPairingPromptState();
                    } else if (idx == 3) {
                        settingsPathIds.clear();
                        settingsPathIds.add("remotes_accessories");
                        settingsPathIds.add("paired_remotes");
                        rebuildSettingsTree();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                    }
                },
                null);
    }

    private void runGuidedInitialSetupStep(Context c) {
        RemoteAuthManager auth = RemoteAuthManager.get(c);
        long nowMs = System.currentTimeMillis();
        boolean hasPairedRemote = false;
        for (RemoteAuthManager.PairedDevice d : auth.listDevices()) {
            if (d != null && !d.isExpired(nowMs)) {
                hasPairedRemote = true;
                break;
            }
        }
        if (!hasPairedRemote) {
            openInitialSetupRemotePairing(c);
            return;
        }
        if (!Trakt.hasOAuth(c)) {
            openTraktAuthDialog();
            return;
        }
        if (!StremioAccountClient.isConfigured(c)) {
            openStremioAuthDialog();
            return;
        }
        if (!Prefs.initialSetupDone()) {
            Prefs.setInitialSetupDone(true);
            rebuildSettingsTree();
            showToast(Ui.tr("Initial Setup completed!"));
        } else {
            showToast(Ui.tr("All primary accounts are connected. Select any step below to reconfigure."));
        }
    }

    private void openAddRoomDialog() {
        try {
            final Context c = getContext().getApplicationContext();
            final android.widget.EditText input = new android.widget.EditText(activity);
            input.setSingleLine(true);
            input.setHint(Ui.tr("e.g. Office, Guest Room, Master Bedroom…"));
            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Add New Room"))
                    .setMessage(Ui.tr("Enter the room name for this TV. It will be saved in SoarTV and used as the default room for Smart-Home lights and AC controls."))
                    .setView(input)
                    .setPositiveButton(Ui.tr("Add & Assign Room"), (d, w) -> {
                        String room = input.getText().toString().trim();
                        if (room.isEmpty()) return;
                        SmartHomeManager.get(c).addCustomRoom(room);
                        homeChanged();
                        rebuildSettingsTree();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("TV Room set to: ") + room);
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        } catch (RuntimeException e) {
            AppLog.w("openAddRoomDialog failed: " + e.getMessage());
        }
    }

    private void openApiKeySetupDialog(final String keyId) {
        try {
            final Context c = getContext().getApplicationContext();
            String title = Ui.tr("Configure Private API Key");
            String message = "";
            String currentVal = "";
            if ("setup_key_omdb".equals(keyId)) {
                title = Ui.tr("OMDb API Key (Rotten Tomatoes & Metacritic)");
                message = Ui.tr("Get a free key (1,000 req/day) at omdbapi.com/apikey.aspx and enter it below, or push via tools/send_key.py:");
                currentVal = Prefs.omdbKey();
            } else if ("setup_key_mdblist".equals(keyId)) {
                title = Ui.tr("MDBList API Key (Rotten Tomatoes & Metacritic)");
                message = Ui.tr("Sign in directly at mdblist.com/preferences/ (1,000 req/day free) and enter your API key below:");
                currentVal = Prefs.mdblistKey();
            } else if ("setup_key_gemini".equals(keyId)) {
                title = Ui.tr("Gemini AI Key (AI Search)");
                message = Ui.tr("Get a free Gemini API key at aistudio.google.com/app/apikey for natural-language AI Search:");
                currentVal = Prefs.geminiKey();
            } else if ("setup_key_supabase".equals(keyId)) {
                title = Ui.tr("Supabase Cloud URL (Companion Remote & Reels)");
                message = Ui.tr("Enter your free Supabase project URL (https://<project-ref>.supabase.co):");
                currentVal = Prefs.supabaseUrl();
            } else if ("setup_key_tmdb".equals(keyId)) {
                title = Ui.tr("TMDB API Key");
                message = Ui.tr("TMDB key is configured via config.properties / keys.txt (tmdb_key) or tools/send_key.py.");
                currentVal = Recs.hasKey(c) ? "Configured" : "";
            } else if ("setup_key_sensibo".equals(keyId)) {
                title = Ui.tr("Sensibo AC API Key");
                message = Ui.tr("Get your Sensibo API key at home.sensibo.com/me/api (or configure SENSIBO_API_KEY in keys.txt):");
                currentVal = "";
            } else if ("setup_key_tuya".equals(keyId)) {
                title = Ui.tr("Tuya Smart-Home Cloud Credentials");
                message = Ui.tr("Configure TUYA_ACCESS_ID and TUYA_ACCESS_SECRET in keys.txt (Central Europe data center) and run ./build.sh.");
                currentVal = "";
            }

            final android.widget.EditText input = new android.widget.EditText(activity);
            input.setSingleLine(true);
            input.setText(currentVal);
            input.setSelection(input.getText().length());
            input.setHint(Ui.tr("Paste key or URL…"));

            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(Ui.tr("Save"), (d, w) -> {
                        String val = input.getText().toString().trim();
                        if ("setup_key_omdb".equals(keyId)) {
                            Prefs.setOmdbKey(val);
                            recs.invalidateRowCache();
                            recs.refreshIfStale();
                        } else if ("setup_key_mdblist".equals(keyId)) {
                            Prefs.setMdblistKey(val);
                            recs.invalidateRowCache();
                            recs.refreshIfStale();
                        } else if ("setup_key_gemini".equals(keyId)) {
                            Prefs.setGeminiKey(val);
                        } else if ("setup_key_supabase".equals(keyId)) {
                            Prefs.setSupabaseUrl(val);
                        }
                        rebuildSettingsTree();
                        heroDirty = true;
                        heroLayer.invalidate();
                        wake();
                        showToast(Ui.tr("Saved configuration"));
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        } catch (RuntimeException e) {
            AppLog.w("openApiKeySetupDialog failed: " + e.getMessage());
        }
    }

    private void openCitySearchDialog() {
        try {
            final android.widget.EditText input = new android.widget.EditText(activity);
            input.setSingleLine(true);
            input.setText(Prefs.city());
            input.setSelection(input.getText().length());
            input.setHint(Ui.tr("Enter city name (e.g. Tel Aviv, London, New York)…"));
            new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Set Location (Sunrise / Sunset & Weather)"))
                    .setMessage(Ui.tr("Search your city to automatically calculate local time-zone, sunrise time, and sunset time."))
                    .setView(input)
                    .setPositiveButton(Ui.tr("Search City"), (d, w) -> {
                        final String q = input.getText().toString().trim();
                        if (q.isEmpty()) return;
                        showToast(Ui.tr("Searching location: ") + q);
                        LOADER.execute(() -> {
                            try {
                                final List<String[]> found = HomeData.findCity(q);
                                handler.post(() -> {
                                    if (found == null || found.isEmpty()) {
                                        showToast(Ui.tr("No matching city found for: ") + q);
                                        return;
                                    }
                                    if (found.size() == 1) {
                                        String[] c0 = found.get(0);
                                        Prefs.setCity(c0[1], c0[2], c0[3]);
                                        data.settingsChanged();
                                        data.refreshIfStale();
                                        checkNight(true);
                                        rebuildSettingsTree();
                                        heroDirty = true;
                                        heroLayer.invalidate();
                                        wake();
                                        showToast(Ui.tr("Location set to: ") + c0[0]);
                                        return;
                                    }
                                    String[] labels = new String[found.size()];
                                    for (int i = 0; i < found.size(); i++) labels[i] = found.get(i)[0];
                                    new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                            .setTitle(Ui.tr("Select Location"))
                                            .setItems(labels, (d2, which) -> {
                                                if (which >= 0 && which < found.size()) {
                                                    String[] chosen = found.get(which);
                                                    Prefs.setCity(chosen[1], chosen[2], chosen[3]);
                                                    data.settingsChanged();
                                                    data.refreshIfStale();
                                                    checkNight(true);
                                                    rebuildSettingsTree();
                                                    heroDirty = true;
                                                    heroLayer.invalidate();
                                                    wake();
                                                    showToast(Ui.tr("Location set to: ") + chosen[0]);
                                                }
                                            })
                                            .setNegativeButton(Ui.tr("Cancel"), null)
                                            .show();
                                });
                            } catch (Exception e) {
                                handler.post(() -> showToast(Ui.tr("Location search failed: ") + e.getMessage()));
                            }
                        });
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        } catch (RuntimeException e) {
            AppLog.w("openCitySearchDialog failed: " + e.getMessage());
        }
    }

    /**
     * Opens a Liquid Glass confirmation dialog before restarting the TV from System &gt; Power &amp; Energy.
     */
    private void openRestartTvConfirmationModal(final Context appCtx) {
        String[] choices = new String[] {
                Ui.tr("Restart Now"),
                Ui.tr("Cancel")
        };
        openLiquidGlassChoiceModal(
                Ui.tr("Restart TV?"),
                Ui.tr("Your TV will shut down and restart immediately."),
                choices,
                null,
                0,
                Ui.tr("Cancel"),
                idx -> {
                    if (idx == 0) {
                        restartTvNow(appCtx);
                    }
                },
                null);
    }

    /**
     * Performs a clean Android runtime reboot over the TV's loopback ADB bridge (Zero-PC dependency),
     * falling back cleanly to Native Android TV Device Settings if loopback debugging is unavailable.
     */
    private void restartTvNow(final Context appCtx) {
        showToast(Ui.tr("Restarting TV…"));
        LOADER.execute(() -> {
            Adb adb = null;
            try {
                adb = Adb.connectSignedOnly(appCtx);
                if (adb == null) {
                    adb = Adb.connect(appCtx, 10_000, null);
                }
                String out = adb.shell("svc power reboot");
                if (out != null && out.toLowerCase(Locale.ROOT).contains("error")) {
                    adb.shell("reboot");
                }
            } catch (Exception ex) {
                AppLog.w("TV restart via loopback ADB failed: " + ex.getMessage());
                handler.post(() -> {
                    showToast(Ui.tr("Restart requires USB Debugging enabled — opening Native Settings"));
                    openAndroidSettingIntent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS);
                });
            } finally {
                if (adb != null) {
                    adb.close();
                }
            }
        });
    }

    private void openAndroidSettingIntent(String action) {
        openAndroidSettingIntentWithFallback(new Intent(action));
    }

    private void openAndroidSettingIntentWithFallback(Intent... candidates) {
        if (candidates != null) {
            for (Intent raw : candidates) {
                if (raw == null) continue;
                try {
                    Intent intent = new Intent(raw)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    if (intent.resolveActivity(activity.getPackageManager()) != null) {
                        activity.startActivity(intent);
                        return;
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        HomeApps.open(activity, HomeApps.tvSettings(), this, Math.round(w / 2f), Math.round(h / 2f), 100, 100);
    }

    private void openTvInputById(String inputId, String label) {
        try {
            List<HomeApps.Item> inputs = HomeApps.allInputs(getContext());
            if (inputs != null) {
                for (HomeApps.Item it : inputs) {
                    if (it == null) continue;
                    boolean idMatch = inputId != null && !inputId.isEmpty()
                            && (inputId.equals(it.inputId) || inputId.equals(it.key));
                    boolean labelMatch = label != null && !label.isEmpty() && label.equalsIgnoreCase(it.label);
                    if ((idMatch || labelMatch)
                            && HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) {
                        return;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        openTvInputSource(label != null ? label : "", label);
    }

    private void openTvChannelOrInputSetting(String label) {
        for (HomeApps.Item it : drawerItems) {
            if (it.kind == HomeApps.LIVE_TV) {
                if (HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) return;
            }
        }
        openAndroidSettingIntent(android.provider.Settings.ACTION_SETTINGS);
    }

    private void openTvInputSource(String key, String label) {
        String match = key.toLowerCase(Locale.ROOT);
        for (HomeApps.Item it : drawerItems) {
            if (it.kind == HomeApps.INPUT && it.label != null && it.label.toLowerCase(Locale.ROOT).contains(match)) {
                if (HomeApps.open(activity, it, this, Math.round(w / 2f), Math.round(h / 2f), 100, 100)) return;
            }
        }
        openAndroidSettingIntent(android.provider.Settings.ACTION_SETTINGS);
    }

    private void drawAppsScreenGrid(Canvas c) {
        if (allAppsList.isEmpty()) rebuildAllAppsList();
        int n = allAppsList.size();
        float shift = scroll.value * GRID_ROW * k;
        if (reordering && reorderAppsGrid) {
            int prev = lCardTitle.getColor();
            lCardTitle.setColor(0xFF3B82F6);
            c.drawText(Ui.tr("← → ↑ ↓ Moving App (OK to save · Back to cancel)"),
                    MARGIN * k, 152 * k - shift, lCardTitle);
            lCardTitle.setColor(prev);
        }
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(allAppsList.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = (GRID_TOP - 28) * k;
        c.save();
        c.clipRect(0, 112 * k, w, h);
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 100 * k) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = i < gridFocus.length ? Ui.clamp(gridFocus[i].value) : ((zone == Z_CARDS && i == gridIndex) ? 1f : 0f);
            boolean liftedApp = reordering && reorderAppsGrid && i == gridIndex;
            if (liftedApp) {
                c.save();
                c.rotate(wiggle, gridCell.centerX(), gridCell.centerY());
            }
            drawCard(c, allAppsList.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (liftedApp) {
                c.restore();
            }
            if (zone == Z_CARDS && i == gridIndex) focusedCell.set(cell);
            boolean hiddenApp = allAppsList.get(i).isHiddenItem;
            int lblColor = hiddenApp
                    ? blend(tone(0xB3444446, 0x55FFFFFF), tone(L_INK, 0xBBFFFFFF), f)
                    : blend(tone(L_DIM, 0x99FFFFFF), tone(L_INK, 0xFFFFFFFF), f);
            gridLabel.setColor(lblColor);
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (38 + 4 * f) * k, gridLabel);
        }
        c.restore();
    }

    private void drawBrowserRow(Canvas c, BrowserRow row, int rowIdx, float y, boolean rowFocused) {
        float left = MARGIN * k;
        if (row.kind == ROW_MEDIA_SELECTOR) {
            String[] opts = {Ui.tr("TV Shows"), Ui.tr("Movies")};
            float pillH = 52 * k, padX = 28 * k, gap = 14 * k;
            float x = left;
            for (int i = 0; i < 2; i++) {
                float tw = Math.max(lTabOn.measureText(opts[i]), lTab.measureText(opts[i]));
                tmpCardBox.set(x, y + 10 * k, x + tw + 2 * padX, y + 10 * k + pillH);
                boolean active = (subTab == i);
                boolean focused = rowFocused && (homeCol == i);
                drawSubPill(c, tmpCardBox, opts[i], active, focused, false);
                x = tmpCardBox.right + gap;
            }
            return;
        }

        // Row header title (only show reorder instructions AFTER the user holds OK)
        boolean liftedGenre = reordering && reorderGenre && rowFocused;
        boolean liftedSmart = reordering && reorderDrawer && rowFocused;
        boolean liftedGame = reordering && reorderGames && rowFocused;
        boolean liftedWatchlist = reordering && reorderWatchlist && rowFocused && row.kind == ROW_PICKS && Recs.WATCHLIST.equals(row.pickKind);
        String header = row.title;
        if (liftedGenre) {
            header = row.title + "   " + Ui.tr("· ↑↓ Moving Genre Row (OK to save)");
        } else if (liftedSmart) {
            header = row.title + "   " + Ui.tr("· ←→ Moving Smart-Home (OK to save)");
        } else if (liftedGame) {
            header = row.title + "   " + Ui.tr("· ←→ Moving Game (OK to save)");
        } else if (liftedWatchlist) {
            header = row.title + "   " + Ui.tr("· ←→ Moving Watch List Item (OK to save)");
        }
        int prevTitleColor = lCardTitle.getColor();
        if (liftedGenre || liftedSmart || liftedGame || liftedWatchlist) lCardTitle.setColor(0xFF3B82F6);
        c.drawText(header, left, y + 32 * k, lCardTitle);
        lCardTitle.setColor(prevTitleColor);

        boolean isHomeMediaRow = (activeScreen == SCREEN_HOME
                && (row.kind == ROW_CONTINUE || row.kind == ROW_PICKS || row.kind == ROW_GENRE));
        float cardsTop = y + (isHomeMediaRow ? MEDIA_CARDS_TOP_OFFSET : 68f) * k;
        float cardW = (isHomeMediaRow ? MEDIA_CARD_W : WIDE_BROWSER_CARD_W) * k;
        float cardH = (isHomeMediaRow ? MEDIA_CARD_H : WIDE_BROWSER_CARD_H) * k;
        float gap = (isHomeMediaRow ? MEDIA_CARD_GAP : WIDE_BROWSER_CARD_GAP) * k;
        int count = row.count();

        c.save();
        c.clipRect(left - 28 * k, y, w, y + row.height(k, activeScreen) + 52 * k);

        // Two-pass rendering: non-focused cards first, then focused card on top (Z-order fix)
        int focusedIdx = (rowFocused && homeCol >= 0 && homeCol < count) ? homeCol : -1;

        // Pass 1: non-focused cards
        for (int i = 0; i < count; i++) {
            if (i == focusedIdx) continue;
            float cx = left + i * (cardW + gap) - row.scrollX;
            if (cx + cardW < left - 40 * k || cx > w + 20 * k) continue;
            // Subtle vertical separator line between high-quality suggestions and lesser-quality suggestions
            if ((row.kind == ROW_PICKS || row.kind == ROW_GENRE)
                    && i > 0 && i < row.pickItems.size()
                    && row.pickItems.get(i - 1).highQuality
                    && !row.pickItems.get(i).highQuality) {
                float sepX = cx - gap / 2f;
                fill.setShader(null);
                fill.setColor(Ui.alpha(tone(L_INK, 0xFFFFFFFF), 0.24f));
                tmp.set(sepX - 1.25f * k, cardsTop + 14 * k, sepX + 1.25f * k, cardsTop + cardH - 14 * k);
                c.drawRoundRect(tmp, 1.25f * k, 1.25f * k, fill);
            }
            tmpCardBox.set(cx, cardsTop, cx + cardW, cardsTop + cardH);
            drawBrowserCard(c, row, i, tmpCardBox, false, false);
        }

        // Pass 2: focused card on top (unclipped shadow, white rim, 1.12x scale)
        if (focusedIdx >= 0) {
            float cx = left + focusedIdx * (cardW + gap) - row.scrollX;
            if (cx + cardW >= left - 40 * k && cx <= w + 20 * k) {
                // Subtle vertical separator line between high-quality suggestions and lesser-quality suggestions
                if ((row.kind == ROW_PICKS || row.kind == ROW_GENRE)
                        && focusedIdx > 0 && focusedIdx < row.pickItems.size()
                        && row.pickItems.get(focusedIdx - 1).highQuality
                        && !row.pickItems.get(focusedIdx).highQuality) {
                    float sepX = cx - gap / 2f;
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(tone(L_INK, 0xFFFFFFFF), 0.24f));
                    tmp.set(sepX - 1.25f * k, cardsTop + 14 * k, sepX + 1.25f * k, cardsTop + cardH - 14 * k);
                    c.drawRoundRect(tmp, 1.25f * k, 1.25f * k, fill);
                }
                tmpCardBox.set(cx, cardsTop, cx + cardW, cardsTop + cardH);
                drawBrowserCard(c, row, focusedIdx, tmpCardBox, true, liftedGenre || ((liftedSmart || liftedGame || liftedWatchlist) && true));
            }
        }
        c.restore();
    }

    private void drawBrowserCard(Canvas c, BrowserRow row, int idx, RectF base, boolean focused, boolean lifted) {
        float s = focused ? 1.12f : 1.0f;
        float bw = base.width(), bh = base.height(), r0 = 18 * k, r = r0 * s;
        float cx = base.centerX(), cy = base.centerY() - (focused ? 8 * k : 0);
        cell.set(cx - bw * s / 2f, cy - bh * s / 2f, cx + bw * s / 2f, cy + bh * s / 2f);

        if (lifted) {
            c.save();
            c.rotate(wiggle, cx, cy);
        }

        // Shadow
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2f, -bh / 2f, bw / 2f, bh / 2f);
        tmp.offset(0, (focused ? 20 : 8) * k);
        shadowDeep.setColor(Ui.alpha(tone(0xFF282C36, 0xFF000000), focused ? tone(0.26f, 0.62f) : tone(0.10f, 0.30f)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();

        String title = "";
        String sub = "";
        boolean grayedOutCard = false;

        switch (row.kind) {
            case ROW_CONTINUE: {
                if (!row.watchLoaded) {
                    fill.setShader(null);
                    fill.setColor(tone(0xFFE6EAF2, 0xFF1E293B));
                    c.drawRoundRect(cell, r, r, fill);

                    long now = System.currentTimeMillis();
                    float pulse = (float) (Math.sin((now / 350.0) + idx * 0.7) * 0.5 + 0.5);
                    int sheenAlpha = (int) (0x12 + pulse * 0x1A);
                    fill.setColor((sheenAlpha << 24) | 0x00FFFFFF);
                    c.drawRoundRect(cell, r, r, fill);

                    fill.setColor(0x1AFFFFFF);
                    float inset = 16 * k * s;
                    tmp.set(cell.left + inset, cell.bottom - 44 * k * s, cell.left + inset + (cell.width() - 2 * inset) * 0.65f, cell.bottom - 32 * k * s);
                    c.drawRoundRect(tmp, 4 * k * s, 4 * k * s, fill);
                    tmp.set(cell.left + inset, cell.bottom - 24 * k * s, cell.right - inset, cell.bottom - 18 * k * s);
                    c.drawRoundRect(tmp, 3 * k * s, 3 * k * s, fill);
                    break;
                }
                if (idx < row.watchItems.size()) {
                    WatchNext.Item it = row.watchItems.get(idx);
                    Bitmap img = watchArt.get(it.pictureUri, it.pictureKey, it.bannerKey, it.pkg, it.title, it.isSeries);
                    if (img != null) {
                        roundImage(c, img, watchArt.shader(img), cell, r);
                    } else {
                        fill.setShader(null);
                        fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                        c.drawRoundRect(cell, r, r, fill);
                    }

                    // Tall, high-contrast 3-stop bottom gradient behind "S2E10" and progress bar (always on at full opacity on every card)
                    float scrimH = Math.min(cell.height(), CONTINUE_SCRIM_HEIGHT_DP * k * s);
                    float scrimTop = cell.bottom - scrimH;
                    c.save();
                    c.clipRect(cell.left, scrimTop, cell.right, cell.bottom);
                    continueScrimMatrix.setScale(1f, Math.max(1f, scrimH));
                    continueScrimMatrix.postTranslate(cell.left, scrimTop);
                    continueScrimUnitGrad.setLocalMatrix(continueScrimMatrix);
                    fill.setColor(0xFFFFFFFF);
                    fill.setShader(continueScrimUnitGrad);
                    c.drawRoundRect(cell, r, r, fill);
                    fill.setShader(null);
                    c.restore();

                    // Episode text formatted as "S2E10"
                    String epText = it.formatEpisodeLine();
                    if (epText != null && !epText.isEmpty() && continueEpisodePaint != null) {
                        float inset = 14 * k * s;
                        continueEpisodePaint.setTextSize(20 * k * s);
                        float maxW = Math.max(0, cell.width() - 2 * inset);
                        String epDisplay = fastEllipsize(epText, continueEpisodePaint, maxW);
                        float baseline = continueEpisodeBaseline(cell.bottom, k, s);
                        c.drawText(epDisplay, cell.left + inset, baseline, continueEpisodePaint);
                    }

                    // Progress bar (vertical space reserved below episode text baseline)
                    float progress = it.progress();
                    if (progress >= 0) {
                        float inset = 14 * k * s, bar = 6 * k * s, bottom = cell.bottom - inset;
                        fill.setShader(null);
                        fill.setColor(PROGRESS_TRACK_COLOR);
                        tmp.set(cell.left + inset, bottom - bar, cell.right - inset, bottom);
                        c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
                        fill.setColor(PROGRESS_FILL_COLOR);
                        tmp.right = tmp.left + Math.max(bar, tmp.width() * progress);
                        c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
                    }
                    title = it.title == null ? "" : it.title;
                    String appLabel = (it.app == null || it.app.equalsIgnoreCase("Stremio")) ? "" : it.app;
                    StringBuilder m = new StringBuilder(appLabel);
                    if (it.episode != null) m.append(m.length() > 0 ? " · " : "").append(it.episode);
                    String left = timeLeft(it);
                    if (left != null) m.append(m.length() > 0 ? " · " : "").append(left);
                    sub = m.toString();
                } else {
                    int pIdx = idx - row.watchItems.size();
                    if (pIdx >= 0 && pIdx < row.pickItems.size()) {
                        Recs.Pick p = row.pickItems.get(pIdx);
                        drawPickArtwork(c, p, cell, r, false);
                        float scrimH = Math.min(cell.height(), CONTINUE_SCRIM_HEIGHT_DP * k * s);
                        float scrimTop = cell.bottom - scrimH;
                        c.save();
                        c.clipRect(cell.left, scrimTop, cell.right, cell.bottom);
                        continueScrimMatrix.setScale(1f, Math.max(1f, scrimH));
                        continueScrimMatrix.postTranslate(cell.left, scrimTop);
                        continueScrimUnitGrad.setLocalMatrix(continueScrimMatrix);
                        fill.setColor(0xFFFFFFFF);
                        fill.setShader(continueScrimUnitGrad);
                        c.drawRoundRect(cell, r, r, fill);
                        fill.setShader(null);
                        c.restore();

                        int[] se = WatchNext.extractSeasonEpisode(p.progressSeason, p.progressEpisode, p.tag, p.link);
                        String epText = WatchNext.formatEpisodeLine(se[0], se[1], null);
                        if (epText != null && !epText.isEmpty() && continueEpisodePaint != null) {
                            float inset = 14 * k * s;
                            continueEpisodePaint.setTextSize(20 * k * s);
                            float maxW = Math.max(0, cell.width() - 2 * inset);
                            String epDisplay = fastEllipsize(epText, continueEpisodePaint, maxW);
                            float baseline = continueEpisodeBaseline(cell.bottom, k, s);
                            c.drawText(epDisplay, cell.left + inset, baseline, continueEpisodePaint);
                        }
                        if (p.progressPct > 0f) {
                            float progress = Math.min(1f, p.progressPct / 100f);
                            float inset = 14 * k * s, bar = 6 * k * s, bottom = cell.bottom - inset;
                            fill.setShader(null);
                            fill.setColor(PROGRESS_TRACK_COLOR);
                            tmp.set(cell.left + inset, bottom - bar, cell.right - inset, bottom);
                            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
                            fill.setColor(PROGRESS_FILL_COLOR);
                            tmp.right = tmp.left + Math.max(bar, tmp.width() * progress);
                            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
                        }
                        title = p.title == null ? "" : p.title;
                        sub = p.tag != null ? p.tag : (p.meta != null ? p.meta : "");
                    } else {
                        fill.setShader(null);
                        fill.setColor(tone(0xFFE5E5EA, 0xFF2C2C2E));
                        c.drawRoundRect(cell, r, r, fill);
                        title = Ui.tr("Nothing paused yet");
                        sub = Ui.tr("Watch a show or movie to resume here");
                    }
                }
                break;
            }
            case ROW_PICKS:
            case ROW_GENRE: {
                if (idx < row.pickItems.size()) {
                    Recs.Pick p = row.pickItems.get(idx);
                    boolean grayWatched = Recs.WATCHLIST.equals(row.pickKind) && p.watched;
                    grayedOutCard = grayWatched;
                    drawPickArtwork(c, p, cell, r, grayWatched);
                    title = p.title == null ? "" : p.title;
                    sub = Recs.ratingsSubtitle(p);
                    if (grayWatched) {
                        sub = sub == null || sub.isEmpty() ? Ui.tr("Watched") : (Ui.tr("Watched") + " · " + sub);
                    }
                }
                break;
            }
            case ROW_GAMES: {
                if (idx < row.gameItems.size()) {
                    GamingScreenRenderer.CardMeta meta = gamingRenderer.drawGameBrowserCard(
                            getContext(), c, row.gameItems.get(idx), cell, r, k, s,
                            showHiddenGamesInline, fill, imageFill, veil, GRAYSCALE_DIM_FILTER, gameCardCallbacks);
                    title = meta.title;
                    sub = meta.subtitle;
                    grayedOutCard = meta.grayedOut;
                }
                break;
            }
            case ROW_ACTIONS: {
                if (idx < row.actionItems.size()) {
                    ActionCard act = row.actionItems.get(idx);
                    fill.setShader(null);
                    fill.setColor(0xFF1E293B);
                    c.drawRoundRect(cell, r, r, fill);
                    fill.setShader(cachedActionCardGradient(act.colorTop, act.colorBottom, cell));
                    fill.setAlpha(255);
                    c.drawRoundRect(cell, r, r, fill);
                    fill.setShader(null);
                    Symbols.draw(getContext(), c, act.symbol, cell.centerX(), cell.centerY(), 46 * k * s, 0xF2FFFFFF);
                    title = act.title;
                    sub = act.subtitle;
                }
                break;
            }
            case ROW_SMARTHOME: {
                if (idx < row.smartItems.size()) {
                    SmartHomeScreenRenderer.CardMeta meta = smartHomeRenderer.drawSmartHomeBrowserCard(
                            getContext(), c, row.smartItems.get(idx), cell, r, k, s, dark(), L_INK, fill);
                    title = meta.title;
                    sub = meta.subtitle;
                }
                break;
            }
            default:
                break;
        }

        if (focused) {
            rim.setShader(null);
            rim.setColor(0xFFFFFFFF);
            rim.setStrokeWidth(3f * k);
            c.drawRoundRect(cell, r, r, rim);
        }

        if (lifted) {
            c.restore();
        }

        boolean isMediaCard = (activeScreen == SCREEN_HOME
                && (row.kind == ROW_PICKS || row.kind == ROW_GENRE
                || (row.kind == ROW_CONTINUE && (idx < row.watchItems.size() || (idx - row.watchItems.size() < row.pickItems.size()) || !row.watchLoaded))));

        if (!isMediaCard) {
            fitPaint.set(lCardTitle);
            fitPaint.setTextSize(23 * k);
            if (grayedOutCard) fitPaint.setAlpha(135);
            String tLine = fastEllipsize(title, fitPaint, bw);
            c.drawText(tLine, base.left, base.bottom + 36 * k, fitPaint);

            if (sub != null && !sub.isEmpty()) {
                fitPaint.set(lCardMeta);
                fitPaint.setTextSize(19 * k);
                if (grayedOutCard) fitPaint.setAlpha(120);
                String sLine = fastEllipsize(sub, fitPaint, bw);
                c.drawText(sLine, base.left, base.bottom + 62 * k, fitPaint);
            }
        }
    }

    private static String fastEllipsize(String text, TextPaint paint, float maxW) {
        if (text == null || text.isEmpty() || maxW <= 0f) return "";
        if (paint.measureText(text) <= maxW) return text;
        return TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END).toString();
    }

    private void drawPickArtwork(Canvas c, Recs.Pick p, RectF targetCell, float radius, boolean grayedOut) {
        if (p == null) return;
        // Priority 1: 2:3 TMDB portrait poster (ideal for Stremio-style tall cards)
        Recs.Poster poster = recs.poster(p);
        if (poster != null && poster.bitmap != null && poster.shader != null) {
            if (grayedOut) imageFill.setColorFilter(GRAYSCALE_DIM_FILTER);
            coverImage(c, poster.bitmap, poster.shader, targetCell, radius, 1f);
            if (grayedOut) {
                imageFill.setColorFilter(null);
                veil.setColor(0x55000000);
                c.drawRoundRect(targetCell, radius, radius, veil);
            }
            return;
        }

        // Priority 2: Only when Pick has NO poster URL at all, try WatchArt for meaningful multi-char titles
        if ((p.poster == null || p.poster.isEmpty()) && p.title != null && !p.title.isEmpty()) {
            String cleanTitle = p.title.trim();
            if (cleanTitle.replaceAll("[^\\p{L}\\p{N}]+", "").length() >= 3) {
                Bitmap wImg = watchArt.get(null, null, null, p.pkg != null ? p.pkg : "com.stremio.one", cleanTitle, "tv".equals(p.kind));
                if (wImg != null) {
                    if (grayedOut) imageFill.setColorFilter(GRAYSCALE_DIM_FILTER);
                    coverImage(c, wImg, watchArt.shader(wImg), targetCell, radius, 1f);
                    if (grayedOut) {
                        imageFill.setColorFilter(null);
                        veil.setColor(0x55000000);
                        c.drawRoundRect(targetCell, radius, radius, veil);
                    }
                    return;
                }
            }
        }

        // Priority 3: Clean placeholder with dark background while portrait poster is decoding (never 16:9 still)
        fill.setShader(null);
        fill.setColor(0xFF1E293B);
        c.drawRoundRect(targetCell, radius, radius, fill);
    }

    private void drawSubPill(Canvas c, RectF r, String label, boolean active, boolean focused, boolean lifted) {
        float rr = r.height() / 2f;
        if (lifted) {
            c.save();
            c.rotate(wiggle, r.centerX(), r.centerY());
        }
        if (focused) {
            tmp.set(r);
            tmp.offset(0, 6 * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.20f, 0.38f)));
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, rr, rr, fill);
        } else if (active) {
            fill.setShader(null);
            fill.setColor(tone(0x22000000, 0x33FFFFFF));
            c.drawRoundRect(r, rr, rr, fill);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0x10000000, 0x16FFFFFF));
            c.drawRoundRect(r, rr, rr, fill);
        }
        Paint p = (active || focused) ? lSubTabOn : lSubTab;
        int prevColor = p.getColor();
        if (focused) p.setColor(L_INK);
        else if (active) p.setColor(tone(L_INK, WHITE));
        else p.setColor(tone(L_DIM, 0xFF98989D));
        c.drawText(label, r.centerX() - p.measureText(label) / 2f, r.centerY() + Ui.halfCap(p), p);
        p.setColor(prevColor);
        if (lifted) c.restore();
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    /**
     * Top-Bar (7 options in order: Search, Home, Apps, Gaming, Reels, Smart-Home, Settings).
     * Search uses search icon ("search"), Reels uses TikTok icon ("tiktok").
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        if (topRects[0].isEmpty()) layoutLight();
        String[] labels = topBarLabels();
        float iconSize = 24 * k, padX = 20 * k;
        boolean topBarFocused = (zone == Z_TOP);
        boolean[] mask = FeatureTabs.currentMask(getContext());

        // Pass 1: Draw subtle resting active-screen pill ONLY when focus is outside the Top Bar
        if (!topBarFocused) {
            for (int i = 0; i < TOP_BAR_COUNT; i++) {
                if (!mask[i] || topRects[i].isEmpty()) continue;
                if (activeScreen == i) {
                    RectF r = topRects[i];
                    float rr = r.height() / 2f;
                    fill.setShader(null);
                    fill.setColor(tone(0x1E000000, 0x2EFFFFFF));
                    c.drawRoundRect(r, rr, rr, fill);
                }
            }
        }

        // Pass 2: Draw single snappy, springy focused indicator pill
        if (topPill.isVisible() && topRects[0].height() > 0) {
            topPill.computePillRect(topRects[0].top, topRects[0].bottom, topPillRect);
            float rr = topPillRect.height() / 2f;
            float pillAlpha = Ui.clamp(topPill.alpha.value);
            tmp.set(topPillRect);
            tmp.offset(0, 6 * k * Math.max(0.4f, pillAlpha));
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.42f) * Math.max(0.4f, pillAlpha) * pillAlpha));
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, pillAlpha));
            c.drawRoundRect(topPillRect, rr, rr, fill);
        }

        // Pass 3: Draw icons and text labels for each tab
        for (int i = 0; i < TOP_BAR_COUNT; i++) {
            if (!mask[i] || topRects[i].isEmpty()) continue;
            RectF r = topRects[i];
            boolean active = (activeScreen == i);
            boolean focused = topBarFocused && (topIndex == i);
            float f = Ui.clamp(topFocus[i].value);
            float s = 1f + 0.05f * f;
            cell.set(r.centerX() - r.width() * s / 2f, r.centerY() - r.height() * s / 2f,
                    r.centerX() + r.width() * s / 2f, r.centerY() + r.height() * s / 2f);

            boolean hasWhitePill = topBarFocused && topPill.alpha.value > 0.4f
                    && Math.abs(topPill.computePillCenterX() - r.centerX()) < r.width() * 0.48f;
            int ink = hasWhitePill ? L_INK : ((active && !topBarFocused) ? tone(L_INK, WHITE) : tone(L_DIM, 0xB3FFFFFF));
            String icon = TOP_BAR_ICONS[i];
            float iw = Symbols.width(getContext(), icon, iconSize);
            float iconCx = cell.left + padX * s + iw / 2f;
            Symbols.draw(getContext(), c, icon, iconCx, cell.centerY(), iconSize * s, ink);

            Paint tp = (active || focused) ? lTabOn : lTab;
            int prev = tp.getColor();
            tp.setColor(ink);
            c.drawText(labels[i], cell.left + padX * s + iw + 10 * k, cell.centerY() + Ui.halfCap(tp), tp);
            tp.setColor(prev);
        }

        // Right-aligned clock and status icons
        float right = CONTENT_R * k;
        float cy = 65 * k;
        if (clockText != null && !clockText.isEmpty()) {
            float cw = lTabOn.measureText(clockText);
            int prev = lTabOn.getColor();
            lTabOn.setColor(tone(L_INK, 0xEBFFFFFF));
            c.drawText(clockText, right - cw, cy + Ui.halfCap(lTabOn), lTabOn);
            lTabOn.setColor(prev);
            right -= cw + 24 * k;
        }
        if (Prefs.statusIcons()) {
            float height = L_STATUS * k, gap = L_STATUS_GAP * k;
            int color = Ui.alpha(tone(L_INK, 0xEBFFFFFF), 0.85f);
            if (usbConnected) right = drawStatusIconRight(c, "usb", right, cy, height, gap, color);
            if (bluetoothConnected) right = drawStatusIconRight(c, "bluetooth", right, cy, height, gap, color);
            if (network != 0) drawStatusIconRight(c, network == 2 ? "ethernet" : "wifi", right, cy, height, gap, color);
        }
    }

    private float drawStatusIconRight(Canvas c, String name, float right, float cy, float height, float gap, int color) {
        float size = Symbols.pointSizeForHeight(getContext(), name, height * statusScale(name));
        float width = Symbols.width(getContext(), name, size);
        Symbols.draw(getContext(), c, name, right - width / 2f, cy, size, color);
        return right - width - gap;
    }

    private static float statusScale(String name) {
        if ("bluetooth".equals(name)) return 1.18f;
        if ("usb".equals(name)) return 1.3f;
        return 1;
    }

    private boolean moveLight(int dx, int dy) {
        // 1. Vertical Side-Shelf open (Favorite Apps sidebar)
        if (sideShelfOpen && zone == Z_SHELF) {
            boolean closeDir = isRtl() ? (dx < 0) : (dx > 0);
            if (closeDir) {
                closeSideShelf();
                click.move();
                return true;
            }
            if (dy != 0 && !shelf.isEmpty()) {
                int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dy));
                if (next != shelfIndex) {
                    shelfIndex = next;
                    retarget(false);
                    click.move();
                }
                return true;
            }
            return true;
        }

        // 2. Top-Bar navigation (0: Search, 1: Home, 2: Apps, 3: Gaming, 4: Reels, 5: Smart-Home, 6: Settings)
        if (zone == Z_TOP) {
            if (dx != 0) {
                int next = FeatureTabs.stepEnabledScreen(topIndex, dx, FeatureTabs.currentMask(getContext()));
                if (next != topIndex) {
                    topIndex = next;
                    if (topRects[0].isEmpty()) layoutLight();
                    aim(topFocus, topIndex, false);
                    topPill.retarget(topRects[topIndex], true, false);
                    statusDirty = true;
                    status.invalidate();
                    wake();
                    click.move();
                    pendingTopBarScreen = topIndex;
                    handler.removeCallbacks(commitTopBarScreenRunnable);
                    handler.postDelayed(commitTopBarScreenRunnable, TOP_BAR_SWITCH_DEBOUNCE_MS);
                }
                return true;
            }
            if (dy > 0) {
                flushPendingTopBarScreenSwitch();
                if (topIndex == SCREEN_REELS) {
                    click.open();
                    launchReelsPlayer(false);
                    return true;
                }
                zone = Z_CARDS;
                homeRow = 0;
                if (!browserRows.isEmpty()) {
                    BrowserRow firstRow = browserRows.get(0);
                    if (firstRow.kind == ROW_MEDIA_SELECTOR) {
                        homeCol = subTab;
                        firstRow.scrollX = 0f;
                    } else {
                        String key = rowMemoryKey(firstRow);
                        homeCol = rowScrollMemory.restoreCol(key, firstRow.count(), 0);
                        firstRow.scrollX = rowScrollMemory.restoreScrollX(key, 0f);
                    }
                } else {
                    homeCol = 0;
                }
                retarget(false);
                click.move();
                return true;
            }
            return true;
        }

        // 3. Apps Screen (full-screen grid)
        if (activeScreen == SCREEN_APPS) {
            moveGrid(dx, dy);
            return true;
        }

        // 3b. Recursive 60-40 Two-Column Settings Screen
        if (activeScreen == SCREEN_SETTINGS) {
            moveSettings(dx, dy);
            return true;
        }

        // 4. Main Screen Content Rows (Z_CARDS)
        if (browserRows.isEmpty()) rebuildScreenRows();
        if (browserRows.isEmpty()) {
            if (dy < 0) {
                zone = Z_TOP;
                topIndex = (activeScreen == SCREEN_HOME) ? SCREEN_HOME : activeScreen;
                retarget(false);
                click.move();
            }
            return true;
        }

        homeRow = Math.max(0, Math.min(homeRow, browserRows.size() - 1));
        BrowserRow row = browserRows.get(homeRow);
        int count = row.count();

        if (dx != 0) {
            boolean openSideDir = isRtl() ? (dx > 0 && homeCol == 0) : (dx < 0 && homeCol == 0);
            if (openSideDir && activeScreen == SCREEN_HOME && !shelf.isEmpty()) {
                openSideShelf();
                click.move();
                return true;
            }
            int nextCol = Math.max(0, Math.min(Math.max(0, count - 1), homeCol + dx));
            if (nextCol != homeCol) {
                homeCol = nextCol;
                if (row.kind == ROW_MEDIA_SELECTOR) {
                    selectSubTab(homeCol);
                }
                retarget(false);
                click.move();
            }
            return true;
        }

        if (dy < 0) {
            if (homeRow == 0) {
                if (row.kind != ROW_MEDIA_SELECTOR) {
                    rowScrollMemory.save(rowMemoryKey(row), homeCol, row.scrollX);
                }
                // Going UP to the Top-Bar from the Home screen defaults to the 2nd option (Home = 1), NOT the 1st (Search = 0)
                zone = Z_TOP;
                topIndex = (activeScreen == SCREEN_HOME) ? SCREEN_HOME : activeScreen;
                retarget(false);
                click.move();
                return true;
            }
            if (row.kind != ROW_MEDIA_SELECTOR) {
                rowScrollMemory.save(rowMemoryKey(row), homeCol, row.scrollX);
            }
            homeRow--;
            BrowserRow prevRow = browserRows.get(homeRow);
            if (prevRow.kind == ROW_MEDIA_SELECTOR) {
                homeCol = subTab;
                prevRow.scrollX = 0f;
            } else {
                String prevKey = rowMemoryKey(prevRow);
                homeCol = rowScrollMemory.restoreCol(prevKey, prevRow.count(), 0);
                prevRow.scrollX = rowScrollMemory.restoreScrollX(prevKey, 0f);
            }
            retarget(false);
            click.move();
            return true;
        }

        if (dy > 0) {
            if (homeRow + 1 < browserRows.size()) {
                if (row.kind != ROW_MEDIA_SELECTOR) {
                    rowScrollMemory.save(rowMemoryKey(row), homeCol, row.scrollX);
                }
                homeRow++;
                BrowserRow nextRow = browserRows.get(homeRow);
                if (nextRow.kind == ROW_MEDIA_SELECTOR) {
                    homeCol = subTab;
                    nextRow.scrollX = 0f;
                } else {
                    String nextKey = rowMemoryKey(nextRow);
                    homeCol = rowScrollMemory.restoreCol(nextKey, nextRow.count(), 0);
                    nextRow.scrollX = rowScrollMemory.restoreScrollX(nextKey, 0f);
                }
                retarget(false);
                click.move();
            }
            return true;
        }
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private LinearGradient cachedActionCardGradient(int colorTop, int colorBottom, RectF cell) {
        long key = (((long) colorTop) << 32) | (colorBottom & 0xFFFFFFFFL);
        LinearGradient grad = actionCardGradients.get(key);
        if (grad == null) {
            grad = new LinearGradient(0f, 0f, 1f, 1f, colorTop, colorBottom, Shader.TileMode.CLAMP);
            actionCardGradients.put(key, grad);
        }
        cardGradMatrix.setScale(Math.max(1f, cell.width()), Math.max(1f, cell.height()));
        cardGradMatrix.postTranslate(cell.left, cell.top);
        grad.setLocalMatrix(cardGradMatrix);
        return grad;
    }

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
