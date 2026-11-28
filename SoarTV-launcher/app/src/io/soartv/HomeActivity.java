package io.soartv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The SoarTV home screen (Android's "Home" role in SoarTV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.soartv.ALL_APPS";
    /** Opens HDMI / Console inputs on TVs whose stock input overlay is disabled in SoarTV mode (e.g. Hisense). */
    static final String EXTRA_INPUTS = "io.soartv.INPUTS";
    /** Opens the dedicated Gaming screen directly (e.g. when exiting an active PC game stream). */
    public static final String EXTRA_OPEN_GAMING = "io.soartv.OPEN_GAMING";
    /** Returns focus cleanly to the Reels Top-Bar tab when exiting {@link ReelsPlayerActivity}. */
    public static final String EXTRA_OPEN_REELS = "io.soartv.OPEN_REELS";
    /** Opens SoarTV's dedicated Settings tab directly (e.g. from the TV remote's Settings button). */
    public static final String EXTRA_OPEN_SETTINGS = "io.soartv.OPEN_SETTINGS";
    /** Retains exact navigation state across Activity recreations (e.g. language change or system restart). */
    private static volatile HomeInputRouter.NavigationSnapshot retainedNavSnapshot;

    private HomeView home;
    private DreamView dreamView;
    private long dreamStartedAt;
    private int prefsVersion = Prefs.version;
    private String appliedLanguage;
    private String appliedCity;

    /**
     * Returns {@code true} only when a preference change requires full {@link Activity#recreate()}
     * (i.e. display language changed). Routine preference bumps (Trakt/Stremio sync, theme, genres,
     * gaming, screensaver) refresh in-place so returning from Native Settings never loses navigation state.
     */
    static boolean shouldRecreateForLanguageChange(String prevLanguage, String currentLanguage) {
        String a = prevLanguage == null ? "" : prevLanguage;
        String b = currentLanguage == null ? "" : currentLanguage;
        return !a.equals(b);
    }

    private static final String STATE_ACTIVE_SCREEN = "nav_active_screen";
    private static final String STATE_TOP_INDEX = "nav_top_index";
    private static final String STATE_ZONE = "nav_zone";
    private static final String STATE_SUB_TAB = "nav_sub_tab";
    private static final String STATE_HOME_ROW = "nav_home_row";
    private static final String STATE_HOME_COL = "nav_home_col";
    private static final String STATE_GRID_INDEX = "nav_grid_index";
    private static final String STATE_SETTINGS_PATH = "nav_settings_path";
    private static final String STATE_SETTINGS_DEPTHS = "nav_settings_depths";
    private static final String STATE_SETTINGS_FOCUS_RIGHT = "nav_settings_focus_right";
    private static final String STATE_SETTINGS_RIGHT_INDEX = "nav_settings_right_index";

    private static HomeInputRouter.NavigationSnapshot readSnapshotFromBundle(Bundle b) {
        if (b == null || !b.containsKey(STATE_ACTIVE_SCREEN)) return null;
        java.util.ArrayList<String> path = b.getStringArrayList(STATE_SETTINGS_PATH);
        int[] depths = b.getIntArray(STATE_SETTINGS_DEPTHS);
        return new HomeInputRouter.NavigationSnapshot(
                b.getInt(STATE_ACTIVE_SCREEN, HomeView.SCREEN_HOME),
                b.getInt(STATE_TOP_INDEX, HomeView.SCREEN_HOME),
                b.getInt(STATE_ZONE, 0),
                b.getInt(STATE_SUB_TAB, 0),
                b.getInt(STATE_HOME_ROW, 0),
                b.getInt(STATE_HOME_COL, 0),
                b.getInt(STATE_GRID_INDEX, 0),
                path,
                depths,
                b.getBoolean(STATE_SETTINGS_FOCUS_RIGHT, false),
                b.getInt(STATE_SETTINGS_RIGHT_INDEX, 0),
                null);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (home != null && outState != null) {
            HomeInputRouter.NavigationSnapshot snap = home.captureNavigationSnapshot();
            retainedNavSnapshot = snap;
            outState.putInt(STATE_ACTIVE_SCREEN, snap.activeScreen);
            outState.putInt(STATE_TOP_INDEX, snap.topIndex);
            outState.putInt(STATE_ZONE, snap.zone);
            outState.putInt(STATE_SUB_TAB, snap.subTab);
            outState.putInt(STATE_HOME_ROW, snap.homeRow);
            outState.putInt(STATE_HOME_COL, snap.homeCol);
            outState.putInt(STATE_GRID_INDEX, snap.gridIndex);
            outState.putStringArrayList(STATE_SETTINGS_PATH, new java.util.ArrayList<>(snap.settingsPathIds));
            outState.putIntArray(STATE_SETTINGS_DEPTHS, snap.settingsDepthIndices);
            outState.putBoolean(STATE_SETTINGS_FOCUS_RIGHT, snap.settingsFocusRight);
            outState.putInt(STATE_SETTINGS_RIGHT_INDEX, snap.settingsRightIndex);
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.request4kDisplayMode(getWindow());
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            org.json.JSONObject s = HomeMode.state(this);
            if (!HomeMode.FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted")) {
                // Already reverted to the stock launcher: clear the crash counter so opening SoarTV works immediately
                Watchdog.clear(this);
            } else {
                HomeMode.requestRevertHome(this);
                Watchdog.clear(this);
                TextView t = new TextView(this);
                t.setText(Ui.tr("SoarTV's home screen ran into a problem.\nGoing back to the TV's original home screen…\n\n(Press OK or Back to reopen SoarTV)"));
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                t.setGravity(Gravity.CENTER);
                t.setBackgroundColor(Color.rgb(7, 11, 30));
                t.setFocusable(true);
                t.setOnClickListener(v -> {
                    Watchdog.clear(this);
                    recreate();
                });
                setContentView(t);
                return;
            }
        }
        Ui.init(this);
        appliedLanguage = Prefs.language();
        appliedCity = Prefs.city();
        prefsVersion = Prefs.version;
        UserProfileManager.get(this).syncWithCloudAsync(null);
        CloudSyncManager.get(this).syncNowAsync("boot", null);
        ScreensaverCatalog.prefetchAsync(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) {
            retainedNavSnapshot = null;
            home.showAllApps();
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_GAMING, false)
                || getIntent().getBooleanExtra(EXTRA_INPUTS, false)) {
            retainedNavSnapshot = null;
            home.showGamingScreen();
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_REELS, false)) {
            retainedNavSnapshot = null;
            home.showReelsScreen();
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            retainedNavSnapshot = null;
            home.showSettingsScreen();
        } else if (retainedNavSnapshot != null) {
            home.restoreNavigationSnapshot(retainedNavSnapshot);
            retainedNavSnapshot = null;
        } else {
            HomeInputRouter.NavigationSnapshot fromBundle = readSnapshotFromBundle(savedInstanceState);
            if (fromBundle != null) {
                home.restoreNavigationSnapshot(fromBundle);
            }
        }
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in SoarTV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            if (HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted")) home.postDelayed(powerOnLive, 10_000);
        }
    }

    private boolean resumed, keyPressed;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!resumed || keyPressed) return;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (TvDeviceProfile.current().requiresOpaqueResumeFix()) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            String curCity = Prefs.city();
            if (appliedCity == null || !appliedCity.equals(curCity)) {
                appliedCity = curCity;
                HomeData.get(this).settingsChanged();
            }
            String curLang = Prefs.language();
            if (shouldRecreateForLanguageChange(appliedLanguage, curLang)) {
                appliedLanguage = curLang;
                if (home != null) {
                    retainedNavSnapshot = home.captureNavigationSnapshot();
                }
                recreate();
                return;
            }
        }
        if (home != null) home.resume();
        CloudSyncManager.get(this).onHomeResumed();
        Updates.tellOnce(this);   // an update the week's check got ready: said once
        GamingZone.handleLauncherResume(this);
    }

    @Override protected void onPause() {
        resumed = false;
        CloudSyncManager.get(this).onHomePaused();
        stopScreensaverNow(false);
        if (home != null) {
            retainedNavSnapshot = home.captureNavigationSnapshot();
            home.pause();
        }
        super.onPause();
    }

    /**
     * Immediately starts the 4K UHD photography screensaver directly inside {@link HomeActivity}.
     * Pressing any remote navigation, OK, or Back key exits cleanly back to the exact Settings node.
     */
    void startScreensaverNow() {
        if (dreamView != null) return;
        if (home != null) home.pause();
        dreamView = new DreamView(this);
        dreamStartedAt = SystemClock.uptimeMillis();
        setContentView(dreamView);
        dreamView.start();
    }

    /**
     * Stops the active in-activity screensaver and restores {@link HomeView}.
     */
    void stopScreensaverNow(boolean resumeHome) {
        if (dreamView == null) return;
        dreamView.stop();
        dreamView = null;
        if (home != null) {
            setContentView(home);
            if (resumeHome && resumed) home.resume();
        }
    }

    /** Opens SoarTV's Settings tab directly when invoked by the remote Settings button while already in HomeActivity. */
    void openSettingsTabFromRemote() {
        stopScreensaverNow(true);
        if (home != null) {
            home.showSettingsScreen();
        }
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            home.showAllApps();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_GAMING, false)
                || intent.getBooleanExtra(EXTRA_INPUTS, false)) {
            home.showGamingScreen();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_REELS, false)) {
            home.showReelsScreen();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            home.showSettingsScreen();
            return;
        }
        retainedNavSnapshot = null;
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) home.showRecents();
        else home.reset(true);
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        stopScreensaverNow(false);
        if (home != null) {
            home.removeCallbacks(powerOnLive);
            home.destroy();
        }
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Recs.setBackgroundActivityPaused(true);
            Recs.get(this).trim();
            GamingZone.trimMemoryForStreaming();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyPressed = true;
        if (dreamView != null) {
            // Ignore the trailing ACTION_UP from the OK press that launched "Start Screensaver"
            if (SystemClock.uptimeMillis() - dreamStartedAt < 400 && event.getAction() == KeyEvent.ACTION_UP) {
                return true;
            }
            if (dreamView.handleKeyEvent(event)) return true;
            int code = event.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean backOrHome = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_HOME;
            if (ok || backOrHome) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    stopScreensaverNow(true);
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (home != null && home.onGamepadMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

}
