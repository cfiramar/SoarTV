package io.soartv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Instant full-screen adaptive vertical (9:16) &amp; horizontal (16:9) Reels &amp; TikTok player
 * running inside the isolated {@code :reels} process with Zero PC Dependency.
 *
 * <p><b>Why:</b> Delivers all requirements from the Reels specification natively on Android TV:
 * <ul>
 *   <li>Instant full-screen feed ordered oldest-first across all connected plugins</li>
 *   <li>Adaptive 16:9 horizontal full-bleed playback (preferred whenever available) and centered 9:16 vertical stage</li>
 *   <li>Top-left corner Liquid Glass pill with customizable sender name + monochrome source-app icon</li>
 *   <li>Non-emoji chat text message overlays &amp; chronological text message cards</li>
 *   <li>Done Watching button committing inclusive watched progress, reacting {@code 👀} in each touched app,
 *       and sending 10 randomized {@code 👀} messages (~1.5s) in each app where caught up</li>
 *   <li>Watch Later save button + Watch Later folder feed with isolated Done Watching cleanup</li>
 * </ul>
 */
public class ReelsPlayerActivity extends Activity {
    public static final String EXTRA_OPEN_WATCH_LATER = "io.soartv.REELS_OPEN_WATCH_LATER";
    public static final String ACTION_REMOTE_CMD = "io.soartv.REELS_REMOTE_CMD";
    public static final String EXTRA_CMD = "cmd";

    private static final int RAIL_DONE_WATCHING = 0;
    private static final int RAIL_WATCH_LATER_TOGGLE = 1;
    private static final int RAIL_WATCH_LATER_FOLDER = 2;
    private static final int RAIL_ACCOUNTS_SETUP = 3;
    private static final int RAIL_COUNT = 4;

    private static final long CAROUSEL_INTERVAL_MS = 3_600L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaIo = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "soartv-reels-io");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ReelStreamResolver.ResolvedMedia> resolvedCache = new ConcurrentHashMap<>();

    private ReelsRepository repo;
    private ReelsWebBridgeHost bridgeHost;

    private boolean watchLaterMode;
    private List<ReelsModels.FeedItem> activeQueue = Collections.emptyList();
    private Map<String, String> latestChatIds = new java.util.LinkedHashMap<>();
    private Map<String, ReelsModels.SenderAlias> senderAliases = new java.util.LinkedHashMap<>();
    private int currentIndex = 0;
    private int furthestWatchedIndex = -1;
    private int railIndex = 0;
    private boolean railFocused = false;
    private boolean exitPromptOpen = false;
    private int exitPromptFocus = 0;
    private boolean executingDoneWatching = false;

    // Views
    private FrameLayout root;
    private TextureView videoTexture;
    private ImageView carouselImageView;
    private WebView fallbackEmbedWebView;
    private LinearLayout textCardOverlay;
    private TextView textCardSender;
    private TextView textCardBody;
    private LinearLayout cornerBadge;
    private ImageView cornerSourceIcon;
    private TextView cornerSenderName;
    private TextView cornerMeta;
    private LinearLayout noteBubble;
    private TextView noteBubbleSender;
    private TextView noteBubbleBody;
    private LinearLayout actionRail;
    private final LinearLayout[] railButtons = new LinearLayout[RAIL_COUNT];
    private final ImageView[] railIcons = new ImageView[RAIL_COUNT];
    private final TextView[] railLabels = new TextView[RAIL_COUNT];
    private LinearLayout emptyStateView;
    private TextView emptyTitle;
    private TextView emptySub;
    private LinearLayout emptyActionsRow;
    private LinearLayout progressOverlay;
    private TextView progressText;
    private LinearLayout exitPromptOverlay;
    private TextView exitPromptBtnDone;
    private TextView exitPromptBtnLeave;
    private TextView pauseIndicator;
    private ProgressBar loadingSpinner;

    // MediaPlayer state
    private Surface videoSurface;
    private MediaPlayer mediaPlayer;
    private MediaPlayer carouselAudioPlayer;
    private int currentVideoW = 0;
    private int currentVideoH = 0;
    private boolean isPaused = false;
    private List<String> activeCarouselImages = Collections.emptyList();
    private int carouselSlideIndex = 0;
    private final Runnable carouselAdvanceTask = new Runnable() {
        @Override
        public void run() {
            if (activeCarouselImages.size() > 1 && !isPaused) {
                carouselSlideIndex = (carouselSlideIndex + 1) % activeCarouselImages.size();
                loadCarouselBitmapAsync(activeCarouselImages.get(carouselSlideIndex));
                mainHandler.postDelayed(this, CAROUSEL_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver remoteCmdReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String cmd = intent.getStringExtra(EXTRA_CMD);
            if ("done_watching".equals(cmd)) {
                triggerDoneWatching();
            } else if ("watch_later".equals(cmd)) {
                toggleCurrentWatchLater();
            } else if ("watch_later_folder".equals(cmd)) {
                toggleWatchLaterFolder();
            } else if ("next".equals(cmd)) {
                stepQueue(+1);
            } else if ("prev".equals(cmd)) {
                stepQueue(-1);
            } else if ("play_pause".equals(cmd)) {
                togglePlayPause();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReelsWebBridgeHost.ensureProcessWebViewDir();
        Ui.request4kDisplayMode(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.init(this);

        repo = ReelsRepository.get(this);
        bridgeHost = new ReelsWebBridgeHost(this);
        watchLaterMode = getIntent().getBooleanExtra(EXTRA_OPEN_WATCH_LATER, false);

        buildUi();
        registerRemoteReceiver();
        loadInitialAndSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        senderAliases = repo.loadSenderAliases();
        if (!executingDoneWatching && activeQueue.isEmpty()) {
            loadInitialAndSync();
        } else {
            refreshHudForCurrentItem();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(remoteCmdReceiver);
        } catch (RuntimeException ignored) {
        }
        releaseMediaPlayers();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (fallbackEmbedWebView != null) {
            try {
                fallbackEmbedWebView.stopLoading();
                fallbackEmbedWebView.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        if (bridgeHost != null) bridgeHost.destroy();
        mediaIo.shutdownNow();
    }

    private void registerRemoteReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_REMOTE_CMD);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(remoteCmdReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(remoteCmdReceiver, filter);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        GradientDrawable aurora = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF3E1E66, 0xFF102446, 0xFF0E384A, 0xFF161422 });
        root.setBackground(aurora);

        // 1. Primary Video TextureView (dynamically sized to 16:9 full-bleed OR 9:16 vertical centered)
        videoTexture = new TextureView(this);
        videoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                videoSurface = new Surface(surface);
                if (mediaPlayer != null) {
                    try { mediaPlayer.setSurface(videoSurface); } catch (RuntimeException ignored) {}
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        root.addView(videoTexture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 2. Photo Carousel ImageView (TikTok slideshows)
        carouselImageView = new ImageView(this);
        carouselImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carouselImageView.setVisibility(View.GONE);
        root.addView(carouselImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 3. Clean Fallback Embed WebView (never grabs D-pad focus away from TV remote)
        fallbackEmbedWebView = new WebView(this);
        ReelsWebBridgeHost.configureWebView(fallbackEmbedWebView);
        fallbackEmbedWebView.setWebViewClient(new WebViewClient());
        fallbackEmbedWebView.setFocusable(false);
        fallbackEmbedWebView.setFocusableInTouchMode(false);
        fallbackEmbedWebView.setBackgroundColor(Color.BLACK);
        fallbackEmbedWebView.setVisibility(View.GONE);
        root.addView(fallbackEmbedWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // 4. Standalone Non-Emoji Text Message Card (Req #8)
        textCardOverlay = new LinearLayout(this);
        textCardOverlay.setOrientation(LinearLayout.VERTICAL);
        textCardOverlay.setGravity(Gravity.CENTER);
        textCardOverlay.setBackground(glassBox(0x22FFFFFF, 0x3DFFFFFF, dp(28)));
        textCardOverlay.setPadding(dp(44), dp(36), dp(44), dp(36));
        textCardOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams tcLp = new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(textCardOverlay, tcLp);

        textCardSender = new TextView(this);
        textCardSender.setTextColor(0xFFD1D1D6);
        textCardSender.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textCardSender.setTypeface(Typeface.DEFAULT_BOLD);
        textCardOverlay.addView(textCardSender);

        textCardBody = new TextView(this);
        textCardBody.setTextColor(Color.WHITE);
        textCardBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        textCardBody.setTypeface(Typeface.DEFAULT_BOLD);
        textCardBody.setGravity(Gravity.CENTER);
        textCardBody.setPadding(0, dp(16), 0, 0);
        textCardOverlay.addView(textCardBody);

        // 5. Top-Left Corner Sender & Source-App Liquid Glass Pill (Req #3 — Zero Emojis!)
        cornerBadge = new LinearLayout(this);
        cornerBadge.setOrientation(LinearLayout.HORIZONTAL);
        cornerBadge.setGravity(Gravity.CENTER_VERTICAL);
        cornerBadge.setBackground(glassBox(0x9A0E1222, 0x3DFFFFFF, dp(24)));
        cornerBadge.setPadding(dp(16), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cbLp.leftMargin = dp(28);
        cbLp.topMargin = dp(24);
        root.addView(cornerBadge, cbLp);

        cornerSourceIcon = new ImageView(this);
        cornerSourceIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams csiLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        csiLp.rightMargin = dp(10);
        cornerBadge.addView(cornerSourceIcon, csiLp);

        LinearLayout cornerTexts = new LinearLayout(this);
        cornerTexts.setOrientation(LinearLayout.VERTICAL);
        cornerBadge.addView(cornerTexts);

        cornerSenderName = new TextView(this);
        cornerSenderName.setTextColor(Color.WHITE);
        cornerSenderName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cornerSenderName.setTypeface(Typeface.DEFAULT_BOLD);
        cornerTexts.addView(cornerSenderName);

        cornerMeta = new TextView(this);
        cornerMeta.setTextColor(0xFFD1D1D6);
        cornerMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cornerTexts.addView(cornerMeta);

        // 6. Accompanying Chat Text Message Bubble (Bottom-Left, RTL Hebrew & LTR English safe — Req #8)
        noteBubble = new LinearLayout(this);
        noteBubble.setOrientation(LinearLayout.VERTICAL);
        noteBubble.setBackground(glassBox(0xA6101528, 0x38FFFFFF, dp(18)));
        noteBubble.setPadding(dp(16), dp(12), dp(16), dp(12));
        noteBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams nbLp = new FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        nbLp.leftMargin = dp(28);
        nbLp.bottomMargin = dp(34);
        root.addView(noteBubble, nbLp);

        noteBubbleSender = new TextView(this);
        noteBubbleSender.setTextColor(0xFF93C5FD);
        noteBubbleSender.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        noteBubbleSender.setTypeface(Typeface.DEFAULT_BOLD);
        noteBubble.addView(noteBubbleSender);

        noteBubbleBody = new TextView(this);
        noteBubbleBody.setTextColor(Color.WHITE);
        noteBubbleBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        noteBubbleBody.setPadding(0, dp(4), 0, 0);
        noteBubble.addView(noteBubbleBody);

        // 7. Right-Side Vertical Liquid Glass Action Rail (Done Watching, Watch Later, Watch Later Folder, Setup)
        actionRail = new LinearLayout(this);
        actionRail.setOrientation(LinearLayout.VERTICAL);
        actionRail.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END);
        railLp.rightMargin = dp(28);
        root.addView(actionRail, railLp);

        String[] defaultIcons = new String[] { "check", "bookmark_add", "folder", "users" };
        String[] defaultLabels = new String[] {
            Ui.tr("Done Watching"),
            Ui.tr("Watch Later"),
            Ui.tr("Watch Later Folder"),
            Ui.tr("Accounts & Names")
        };
        for (int i = 0; i < RAIL_COUNT; i++) {
            final int idx = i;
            LinearLayout btn = new LinearLayout(this);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setPadding(dp(16), dp(12), dp(18), dp(12));
            btn.setBackground(glassBox(0x88101526, 0x33FFFFFF, dp(20)));
            btn.setOnClickListener(v -> {
                railFocused = true;
                railIndex = idx;
                updateRailVisuals();
                executeRailAction(idx);
            });

            ImageView iv = new ImageView(this);
            Bitmap bmp = Symbols.get(this, defaultIcons[i]);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setColorFilter(Color.WHITE);
            }
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ivLp.rightMargin = dp(10);
            btn.addView(iv, ivLp);

            TextView tv = new TextView(this);
            tv.setText(defaultLabels[i]);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            btn.addView(tv);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bLp.bottomMargin = dp(12);
            actionRail.addView(btn, bLp);

            railButtons[i] = btn;
            railIcons[i] = iv;
            railLabels[i] = tv;
        }

        // Subtle navigation hint along bottom center
        TextView hintBar = new TextView(this);
        hintBar.setText(Ui.tr("Up / Down: Switch Video   |   OK: Play / Pause   |   Right: Actions"));
        hintBar.setTextColor(0x99FFFFFF);
        hintBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hLp.bottomMargin = dp(14);
        root.addView(hintBar, hLp);

        // 8. Pause & Loading Indicators
        pauseIndicator = new TextView(this);
        pauseIndicator.setText(Ui.tr("Paused"));
        pauseIndicator.setTextColor(Color.WHITE);
        pauseIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pauseIndicator.setTypeface(Typeface.DEFAULT_BOLD);
        pauseIndicator.setBackground(glassBox(0xB80E1222, 0x44FFFFFF, dp(24)));
        pauseIndicator.setPadding(dp(26), dp(12), dp(26), dp(12));
        pauseIndicator.setVisibility(View.GONE);
        root.addView(pauseIndicator, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        loadingSpinner = new ProgressBar(this);
        loadingSpinner.setVisibility(View.GONE);
        root.addView(loadingSpinner, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        // 9. Empty / Caught-Up State Overlay
        emptyStateView = new LinearLayout(this);
        emptyStateView.setOrientation(LinearLayout.VERTICAL);
        emptyStateView.setGravity(Gravity.CENTER);
        emptyStateView.setBackground(glassBox(0x20FFFFFF, 0x35FFFFFF, dp(28)));
        emptyStateView.setPadding(dp(44), dp(36), dp(44), dp(36));
        emptyStateView.setVisibility(View.GONE);
        root.addView(emptyStateView, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        emptyTitle = new TextView(this);
        emptyTitle.setTextColor(Color.WHITE);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyStateView.addView(emptyTitle);

        emptySub = new TextView(this);
        emptySub.setTextColor(0xFFD1D1D6);
        emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, dp(10), 0, dp(22));
        emptyStateView.addView(emptySub);

        emptyActionsRow = new LinearLayout(this);
        emptyActionsRow.setOrientation(LinearLayout.HORIZONTAL);
        emptyActionsRow.setGravity(Gravity.CENTER);
        emptyStateView.addView(emptyActionsRow);

        // 10. Done Watching Progress Modal
        progressOverlay = new LinearLayout(this);
        progressOverlay.setOrientation(LinearLayout.VERTICAL);
        progressOverlay.setGravity(Gravity.CENTER);
        progressOverlay.setBackgroundColor(0xD80A0D1C);
        progressOverlay.setVisibility(View.GONE);
        root.addView(progressOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar pb = new ProgressBar(this);
        progressOverlay.addView(pb);
        progressText = new TextView(this);
        progressText.setTextColor(Color.WHITE);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setPadding(0, dp(16), 0, 0);
        progressOverlay.addView(progressText);

        // 11. Smart Back Confirmation Modal (prevents losing Done Watching bookmark by mistake)
        exitPromptOverlay = new LinearLayout(this);
        exitPromptOverlay.setOrientation(LinearLayout.VERTICAL);
        exitPromptOverlay.setGravity(Gravity.CENTER);
        exitPromptOverlay.setBackgroundColor(0xD0090C18);
        exitPromptOverlay.setVisibility(View.GONE);
        root.addView(exitPromptOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout exitCard = new LinearLayout(this);
        exitCard.setOrientation(LinearLayout.VERTICAL);
        exitCard.setGravity(Gravity.CENTER);
        exitCard.setBackground(glassBox(0x28FFFFFF, 0x45FFFFFF, dp(24)));
        exitCard.setPadding(dp(36), dp(28), dp(36), dp(28));
        exitPromptOverlay.addView(exitCard, new LinearLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView epTitle = new TextView(this);
        epTitle.setText(Ui.tr("Finish Watching Reels?"));
        epTitle.setTextColor(Color.WHITE);
        epTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        epTitle.setTypeface(Typeface.DEFAULT_BOLD);
        exitCard.addView(epTitle);

        TextView epSub = new TextView(this);
        epSub.setText(Ui.tr("Choose Done Watching to save your place and react in your chats, or exit without marking watched."));
        epSub.setTextColor(0xFFD1D1D6);
        epSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        epSub.setGravity(Gravity.CENTER);
        epSub.setPadding(0, dp(8), 0, dp(20));
        exitCard.addView(epSub);

        LinearLayout epRow = new LinearLayout(this);
        epRow.setOrientation(LinearLayout.HORIZONTAL);
        epRow.setGravity(Gravity.CENTER);
        exitCard.addView(epRow);

        exitPromptBtnDone = makeModalPill(Ui.tr("Done Watching"), true);
        LinearLayout.LayoutParams ep1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep1.rightMargin = dp(14);
        epRow.addView(exitPromptBtnDone, ep1);

        exitPromptBtnLeave = makeModalPill(Ui.tr("Exit Without Marking"), false);
        epRow.addView(exitPromptBtnLeave);

        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if ((r - l) != (or_ - ol) || (b - t) != (ob - ot)) {
                applyAdaptiveStageLayout(currentVideoW, currentVideoH);
            }
        });
    }

    private void loadInitialAndSync() {
        senderAliases = repo.loadSenderAliases();
        if (watchLaterMode) {
            enterQueue(repo.loadWatchLater(), true);
            return;
        }
        Map<String, ReelsModels.SourceWatermark> watermarks = repo.loadWatermarks();
        List<ReelsModels.FeedItem> cached = ReelsFeedEngine.mergeOldestFirst(
                repo.loadCachedFeedItems(), watermarks);
        latestChatIds = repo.loadLatestChatMessageIds();
        if (!cached.isEmpty()) {
            enterQueue(cached, false);
        } else {
            loadingSpinner.setVisibility(View.VISIBLE);
        }

        // Pull cloud sync state & refresh connected social chats on-demand
        repo.syncFromCloudAsync(() -> mainHandler.post(() -> {
            senderAliases = repo.loadSenderAliases();
            Map<String, ReelsModels.SourceWatermark> latestWms = repo.loadWatermarks();
            if (repo.getConnectedSourceIds().isEmpty()) {
                loadingSpinner.setVisibility(View.GONE);
                if (activeQueue.isEmpty()) showEmptyState(true);
                return;
            }
            bridgeHost.fetchAllConnectedFeeds(latestWms, (rawItems, latestIds, authNeeded) -> {
                loadingSpinner.setVisibility(View.GONE);
                senderAliases = repo.loadSenderAliases();
                if (!latestIds.isEmpty()) latestChatIds.putAll(latestIds);
                List<ReelsModels.FeedItem> merged = ReelsFeedEngine.mergeOldestFirst(rawItems, repo.loadWatermarks());
                if (!merged.isEmpty() || activeQueue.isEmpty()) {
                    repo.saveCachedFeedSnapshot(merged, latestChatIds);
                    enterQueue(merged, false);
                }
                if (!authNeeded.isEmpty()) {
                    Toast.makeText(this, Ui.tr("Sign-in needed for: ") + joinStrings(authNeeded, ", "),
                            Toast.LENGTH_LONG).show();
                }
            });
        }));
    }

    private void enterQueue(List<ReelsModels.FeedItem> items, boolean isWatchLater) {
        this.watchLaterMode = isWatchLater;
        this.activeQueue = items == null ? Collections.<ReelsModels.FeedItem>emptyList() : new ArrayList<>(items);
        this.currentIndex = 0;
        this.furthestWatchedIndex = this.activeQueue.isEmpty() ? -1 : 0;
        this.railFocused = false;
        this.isPaused = false;
        updateRailVisuals();

        if (this.activeQueue.isEmpty()) {
            releaseMediaPlayers();
            showEmptyState(repo.getConnectedSourceIds().isEmpty() && !watchLaterMode);
        } else {
            emptyStateView.setVisibility(View.GONE);
            cornerBadge.setVisibility(View.VISIBLE);
            actionRail.setVisibility(View.VISIBLE);
            playCurrentItem();
        }
    }

    private void showEmptyState(boolean needsSetup) {
        videoTexture.setVisibility(View.GONE);
        carouselImageView.setVisibility(View.GONE);
        fallbackEmbedWebView.setVisibility(View.GONE);
        textCardOverlay.setVisibility(View.GONE);
        noteBubble.setVisibility(View.GONE);
        cornerBadge.setVisibility(View.GONE);
        emptyStateView.setVisibility(View.VISIBLE);
        actionRail.setVisibility(View.VISIBLE);

        if (watchLaterMode) {
            emptyTitle.setText(Ui.tr("Watch Later Folder Is Empty"));
            emptySub.setText(Ui.tr("Save any TikTok or Instagram Reel with the Watch Later button to collect favorites here."));
        } else if (needsSetup) {
            emptyTitle.setText(Ui.tr("Connect Your TV Reels Chats"));
            emptySub.setText(Ui.tr("Create a dedicated TV account on Instagram or TikTok, add it to your couple or family group chat, and sign in once to start watching together."));
        } else {
            emptyTitle.setText(Ui.tr("You're All Caught Up"));
            emptySub.setText(Ui.tr("No unwatched videos or messages waiting right now."));
        }

        emptyActionsRow.removeAllViews();
        int wlCount = repo.loadWatchLater().size();
        if (watchLaterMode) {
            addEmptyAction(Ui.tr("Back to Feed"), true, () -> {
                watchLaterMode = false;
                loadInitialAndSync();
            });
        } else {
            addEmptyAction(needsSetup ? Ui.tr("Connect Accounts") : Ui.tr("Refresh Chats"), true, () -> {
                if (needsSetup) {
                    startActivity(new Intent(this, ReelsConnectActivity.class));
                } else {
                    loadInitialAndSync();
                }
            });
            if (wlCount > 0) {
                addEmptyAction(Ui.tr("Watch Later") + " (" + wlCount + ")", false, () -> {
                    watchLaterMode = true;
                    enterQueue(repo.loadWatchLater(), true);
                });
            }
            if (!needsSetup) {
                addEmptyAction(Ui.tr("Accounts & Setup"), false, () ->
                        startActivity(new Intent(this, ReelsConnectActivity.class)));
            }
        }
    }

    private void addEmptyAction(String label, boolean primary, Runnable onClick) {
        TextView btn = makeModalPill(label, primary);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnFocusChangeListener((v, hasFocus) -> styleModalPill(btn, hasFocus));
        btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        emptyActionsRow.addView(btn, lp);
        if (primary) btn.requestFocus();
    }

    private void playCurrentItem() {
        if (activeQueue.isEmpty() || currentIndex < 0 || currentIndex >= activeQueue.size()) return;
        furthestWatchedIndex = Math.max(furthestWatchedIndex, currentIndex);
        isPaused = false;
        pauseIndicator.setVisibility(View.GONE);
        releaseMediaPlayers();
        mainHandler.removeCallbacks(carouselAdvanceTask);

        final ReelsModels.FeedItem item = activeQueue.get(currentIndex);
        refreshHudForCurrentItem();

        if (item.kind == ReelsModels.ItemKind.TEXT_MESSAGE) {
            videoTexture.setVisibility(View.GONE);
            carouselImageView.setVisibility(View.GONE);
            fallbackEmbedWebView.setVisibility(View.GONE);
            loadingSpinner.setVisibility(View.GONE);
            noteBubble.setVisibility(View.GONE);
            textCardOverlay.setVisibility(View.VISIBLE);
            String sender = ReelsFeedEngine.resolveSenderDisplayName(item, senderAliases);
            String plat = ReelsSourceRegistry.getDefault().displayNameFor(item.sourceId);
            textCardSender.setText(sender + " · " + plat);
            textCardBody.setText(item.messageText);
            preloadItemAsync(currentIndex + 1);
            return;
        }

        textCardOverlay.setVisibility(View.GONE);
        loadingSpinner.setVisibility(View.VISIBLE);

        // Pre-size stage immediately using known dimensions (horizontal 16:9 if already known, else 9:16)
        currentVideoW = item.videoWidth;
        currentVideoH = item.videoHeight;
        applyAdaptiveStageLayout(currentVideoW, currentVideoH);

        final String itemId = item.id;
        ReelStreamResolver.ResolvedMedia cachedMedia = resolvedCache.get(itemId);
        if (cachedMedia != null) {
            startResolvedPlayback(item, cachedMedia);
            preloadItemAsync(currentIndex + 1);
            return;
        }
        mediaIo.execute(() -> {
            ReelStreamResolver.ResolvedMedia resolved = ReelStreamResolver.getDefault().resolveSync(item);
            resolvedCache.put(itemId, resolved);
            mainHandler.post(() -> {
                if (activeQueue.isEmpty() || currentIndex >= activeQueue.size()
                        || !itemId.equals(activeQueue.get(currentIndex).id)) {
                    return;
                }
                startResolvedPlayback(item, resolved);
                preloadItemAsync(currentIndex + 1);
            });
        });
    }

    private void preloadItemAsync(int idx) {
        if (idx < 0 || idx >= activeQueue.size()) return;
        final ReelsModels.FeedItem next = activeQueue.get(idx);
        if (next == null || next.kind == ReelsModels.ItemKind.TEXT_MESSAGE || resolvedCache.containsKey(next.id)) return;
        mediaIo.execute(() -> {
            ReelStreamResolver.ResolvedMedia res = ReelStreamResolver.getDefault().resolveSync(next);
            if (res != null) resolvedCache.put(next.id, res);
        });
    }

    private void startResolvedPlayback(ReelsModels.FeedItem item, ReelStreamResolver.ResolvedMedia media) {
        if (media == null) {
            loadingSpinner.setVisibility(View.GONE);
            Toast.makeText(this, Ui.tr("Unable to load video"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (media.hasDirectVideo()) {
            carouselImageView.setVisibility(View.GONE);
            fallbackEmbedWebView.setVisibility(View.GONE);
            videoTexture.setVisibility(View.VISIBLE);
            currentVideoW = media.width;
            currentVideoH = media.height;
            applyAdaptiveStageLayout(currentVideoW, currentVideoH);
            startNativeVideo(media.videoUrl, media.embedFallbackUrl);
            return;
        }
        if (media.hasCarousel()) {
            videoTexture.setVisibility(View.GONE);
            fallbackEmbedWebView.setVisibility(View.GONE);
            carouselImageView.setVisibility(View.VISIBLE);
            loadingSpinner.setVisibility(View.GONE);
            activeCarouselImages = media.carouselImages;
            carouselSlideIndex = 0;
            loadCarouselBitmapAsync(activeCarouselImages.get(0));
            if (activeCarouselImages.size() > 1) {
                mainHandler.postDelayed(carouselAdvanceTask, CAROUSEL_INTERVAL_MS);
            }
            if (!media.audioUrl.isEmpty()) {
                startCarouselAudio(media.audioUrl);
            }
            return;
        }
        if (media.hasEmbedFallback()) {
            startEmbedFallback(media.embedFallbackUrl);
            return;
        }
        loadingSpinner.setVisibility(View.GONE);
        Toast.makeText(this, Ui.tr("This video could not be played directly"), Toast.LENGTH_SHORT).show();
    }

    private void startNativeVideo(final String directUrl, final String embedFallbackUrl) {
        releaseMediaPlayers();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mp.setLooping(true);
            if (videoSurface != null) mp.setSurface(videoSurface);
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                if (width > 0 && height > 0) {
                    currentVideoW = width;
                    currentVideoH = height;
                    applyAdaptiveStageLayout(width, height);
                    refreshHudForCurrentItem();
                }
            });
            mp.setOnPreparedListener(player -> {
                loadingSpinner.setVisibility(View.GONE);
                if (!isPaused) player.start();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (embedFallbackUrl != null && !embedFallbackUrl.isEmpty()) {
                    startEmbedFallback(embedFallbackUrl);
                } else {
                    loadingSpinner.setVisibility(View.GONE);
                }
                return true;
            });
            mp.setDataSource(directUrl);
            mp.prepareAsync();
            mediaPlayer = mp;
        } catch (Exception e) {
            if (embedFallbackUrl != null && !embedFallbackUrl.isEmpty()) {
                startEmbedFallback(embedFallbackUrl);
            } else {
                loadingSpinner.setVisibility(View.GONE);
            }
        }
    }

    private void startCarouselAudio(String audioUrl) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mp.setLooping(true);
            mp.setDataSource(audioUrl);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
            carouselAudioPlayer = mp;
        } catch (Exception ignored) {
        }
    }

    private void startEmbedFallback(String embedUrl) {
        releaseMediaPlayers();
        loadingSpinner.setVisibility(View.GONE);
        videoTexture.setVisibility(View.GONE);
        carouselImageView.setVisibility(View.GONE);
        fallbackEmbedWebView.setVisibility(View.VISIBLE);
        applyAdaptiveStageLayout(currentVideoW, currentVideoH);
        fallbackEmbedWebView.loadUrl(embedUrl);
    }

    private void loadCarouselBitmapAsync(final String url) {
        mediaIo.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        mainHandler.post(() -> {
                            carouselImageView.setImageBitmap(bmp);
                            applyAdaptiveStageLayout(bmp.getWidth(), bmp.getHeight());
                        });
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void applyAdaptiveStageLayout(int videoW, int videoH) {
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw <= 0 || rh <= 0) return;
        ReelsModels.StageRect stage = ReelsFeedEngine.computeVideoStageRect(rw, rh, videoW, videoH);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(stage.width(), stage.height(), Gravity.CENTER);
        videoTexture.setLayoutParams(lp);
        carouselImageView.setLayoutParams(lp);
        fallbackEmbedWebView.setLayoutParams(lp);
    }

    private void refreshHudForCurrentItem() {
        updateRailVisuals();
        if (activeQueue.isEmpty() || currentIndex < 0 || currentIndex >= activeQueue.size()) return;
        ReelsModels.FeedItem item = activeQueue.get(currentIndex);
        String senderName = ReelsFeedEngine.resolveSenderDisplayName(item, senderAliases);
        String iconName = ReelsSourceRegistry.getDefault().iconNameFor(item.sourceId);
        Bitmap bmp = Symbols.get(this, iconName);
        if (bmp != null) {
            cornerSourceIcon.setImageBitmap(bmp);
            cornerSourceIcon.setColorFilter(Color.WHITE);
        }
        cornerSenderName.setText(senderName);

        String relTime = ReelsFeedEngine.formatRelativeTime(item.sentAtMs, System.currentTimeMillis());
        String modePrefix = watchLaterMode ? (Ui.tr("Watch Later") + " · ") : "";
        String ratioTag = (currentVideoW > 0 && currentVideoH > 0 && currentVideoW > currentVideoH) ? " · 16:9" : "";
        String meta = modePrefix + (currentIndex + 1) + " / " + activeQueue.size()
                + (relTime.isEmpty() ? "" : (" · " + relTime)) + ratioTag;
        cornerMeta.setText(meta);

        // Accompanying non-emoji text message bubble (Req #8)
        String cleanNote = ReelsFeedEngine.extractDisplayableText(item.messageText);
        if (item.kind != ReelsModels.ItemKind.TEXT_MESSAGE && !cleanNote.isEmpty()) {
            noteBubbleSender.setText(senderName);
            noteBubbleBody.setText(cleanNote);
            noteBubble.setVisibility(View.VISIBLE);
        } else {
            noteBubble.setVisibility(View.GONE);
        }
    }

    private void updateRailVisuals() {
        ReelsModels.FeedItem cur = (!activeQueue.isEmpty() && currentIndex >= 0 && currentIndex < activeQueue.size())
                ? activeQueue.get(currentIndex) : null;
        boolean saved = cur != null && repo.isInWatchLater(cur);
        int wlCount = repo.loadWatchLater().size();

        railLabels[RAIL_DONE_WATCHING].setText(watchLaterMode ? Ui.tr("Done (Clear Watched)") : Ui.tr("Done Watching"));
        railLabels[RAIL_WATCH_LATER_TOGGLE].setText(saved ? Ui.tr("Saved in Watch Later") : Ui.tr("Watch Later"));
        railLabels[RAIL_WATCH_LATER_FOLDER].setText(watchLaterMode
                ? Ui.tr("Back to Feed")
                : (Ui.tr("Watch Later Folder") + " (" + wlCount + ")"));
        railLabels[RAIL_ACCOUNTS_SETUP].setText(Ui.tr("Accounts & Names"));

        Bitmap wlBmp = Symbols.get(this, saved ? "bookmark" : "bookmark_add");
        if (wlBmp != null) railIcons[RAIL_WATCH_LATER_TOGGLE].setImageBitmap(wlBmp);

        for (int i = 0; i < RAIL_COUNT; i++) {
            boolean focused = railFocused && railIndex == i;
            int bg = focused ? 0xFFFFFFFF : (i == RAIL_WATCH_LATER_TOGGLE && saved ? 0xCC2563EB : 0x88101526);
            int stroke = focused ? 0xFFFFFFFF : 0x36FFFFFF;
            int fg = focused ? 0xFF121622 : Color.WHITE;
            railButtons[i].setBackground(glassBox(bg, stroke, dp(20)));
            railLabels[i].setTextColor(fg);
            railIcons[i].setColorFilter(fg);
        }
    }

    private void executeRailAction(int idx) {
        if (idx == RAIL_DONE_WATCHING) {
            triggerDoneWatching();
        } else if (idx == RAIL_WATCH_LATER_TOGGLE) {
            toggleCurrentWatchLater();
        } else if (idx == RAIL_WATCH_LATER_FOLDER) {
            toggleWatchLaterFolder();
        } else if (idx == RAIL_ACCOUNTS_SETUP) {
            startActivity(new Intent(this, ReelsConnectActivity.class));
        }
    }

    private void toggleCurrentWatchLater() {
        if (activeQueue.isEmpty() || currentIndex < 0 || currentIndex >= activeQueue.size()) return;
        ReelsModels.FeedItem item = activeQueue.get(currentIndex);
        boolean nowSaved = repo.toggleWatchLater(item);
        Toast.makeText(this, nowSaved ? Ui.tr("Saved to Watch Later") : Ui.tr("Removed from Watch Later"),
                Toast.LENGTH_SHORT).show();
        updateRailVisuals();
    }

    private void toggleWatchLaterFolder() {
        if (watchLaterMode) {
            watchLaterMode = false;
            loadInitialAndSync();
        } else {
            watchLaterMode = true;
            enterQueue(repo.loadWatchLater(), true);
        }
    }

    private void triggerDoneWatching() {
        if (executingDoneWatching) return;
        if (activeQueue.isEmpty() || furthestWatchedIndex < 0) {
            finishToHome();
            return;
        }
        if (watchLaterMode) {
            List<String> removeIds = ReelsFeedEngine.computeWatchLaterRemovalIds(activeQueue, furthestWatchedIndex);
            int removed = repo.removeFromWatchLaterByIds(removeIds);
            Toast.makeText(this, Ui.tr("Cleared ") + removed + Ui.tr(" watched items from Watch Later"),
                    Toast.LENGTH_SHORT).show();
            watchLaterMode = false;
            loadInitialAndSync();
            return;
        }

        // Main feed Done Watching: inclusive up to furthestWatchedIndex, dual-app 👀 reactions + ~1.5s 10x 👀 if caught up
        executingDoneWatching = true;
        exitPromptOpen = false;
        exitPromptOverlay.setVisibility(View.GONE);
        releaseMediaPlayers();

        Map<String, ReelsModels.SourceWatermark> existingWms = repo.loadWatermarks();
        final ReelsModels.DoneWatchingPlan plan = ReelsFeedEngine.computeDoneWatchingPlan(
                activeQueue, furthestWatchedIndex, latestChatIds, existingWms, System.currentTimeMillis());

        // Persist updated watermarks immediately so next launch starts at the exact oldest unwatched video
        repo.saveWatermarks(plan.updatedWatermarks);
        List<ReelsModels.FeedItem> remaining = new ArrayList<>();
        for (int i = furthestWatchedIndex + 1; i < activeQueue.size(); i++) {
            remaining.add(activeQueue.get(i));
        }
        repo.saveCachedFeedSnapshot(remaining, latestChatIds);

        if (plan.actions.isEmpty()) {
            finishToHome();
            return;
        }

        progressOverlay.setVisibility(View.VISIBLE);
        boolean anyTenEyes = false;
        for (ReelsModels.DoneWatchingAction act : plan.actions) {
            if (act.sendTenEyes) anyTenEyes = true;
        }
        progressText.setText(anyTenEyes
                ? Ui.tr("Caught up! Reacting and sending eyes to your chats...")
                : Ui.tr("Saving progress and reacting in your chats..."));

        bridgeHost.executeDoneWatchingPlan(plan, (okCount, warnings) -> {
            progressOverlay.setVisibility(View.GONE);
            executingDoneWatching = false;
            if (!warnings.isEmpty()) {
                Toast.makeText(this, Ui.tr("Progress saved (some chat reactions skipped)"), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, Ui.tr("Done Watching saved"), Toast.LENGTH_SHORT).show();
            }
            finishToHome();
        });
    }

    private void stepQueue(int delta) {
        if (activeQueue.isEmpty()) return;
        int next = currentIndex + delta;
        if (next < 0) {
            Toast.makeText(this, Ui.tr("Oldest unwatched video"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (next >= activeQueue.size()) {
            Toast.makeText(this, Ui.tr("Last video in queue — press Right for Done Watching"), Toast.LENGTH_SHORT).show();
            railFocused = true;
            railIndex = RAIL_DONE_WATCHING;
            updateRailVisuals();
            return;
        }
        currentIndex = next;
        playCurrentItem();
    }

    private void togglePlayPause() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    isPaused = true;
                    pauseIndicator.setVisibility(View.VISIBLE);
                } else {
                    mediaPlayer.start();
                    isPaused = false;
                    pauseIndicator.setVisibility(View.GONE);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (executingDoneWatching) return true;

        int code = event.getKeyCode();
        if (exitPromptOpen) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                exitPromptFocus = (exitPromptFocus == 0) ? 1 : 0;
                styleModalPill(exitPromptBtnDone, exitPromptFocus == 0);
                styleModalPill(exitPromptBtnLeave, exitPromptFocus == 1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (exitPromptFocus == 0) triggerDoneWatching();
                else finishToHome();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                exitPromptOpen = false;
                exitPromptOverlay.setVisibility(View.GONE);
                return true;
            }
            return true;
        }

        if (emptyStateView.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                finishToHome();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (code == KeyEvent.KEYCODE_BACK) {
            if (railFocused) {
                railFocused = false;
                updateRailVisuals();
                return true;
            }
            if (furthestWatchedIndex >= 0 && !activeQueue.isEmpty()) {
                exitPromptOpen = true;
                exitPromptFocus = 0;
                styleModalPill(exitPromptBtnDone, true);
                styleModalPill(exitPromptBtnLeave, false);
                exitPromptOverlay.setVisibility(View.VISIBLE);
                return true;
            }
            finishToHome();
            return true;
        }

        if (railFocused) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                railIndex = Math.max(0, railIndex - 1);
                updateRailVisuals();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                railIndex = Math.min(RAIL_COUNT - 1, railIndex + 1);
                updateRailVisuals();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                railFocused = false;
                updateRailVisuals();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                executeRailAction(railIndex);
                return true;
            }
            return true;
        }

        // Video stage controls (vertical feed: Down = next, Up = prev, Right = action rail, OK = play/pause)
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            stepQueue(+1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            stepQueue(-1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            railFocused = true;
            updateRailVisuals();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlayPause();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void releaseMediaPlayers() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (RuntimeException ignored) {
            }
            mediaPlayer = null;
        }
        if (carouselAudioPlayer != null) {
            try {
                carouselAudioPlayer.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                carouselAudioPlayer.release();
            } catch (RuntimeException ignored) {
            }
            carouselAudioPlayer = null;
        }
    }

    private void finishToHome() {
        Intent home = new Intent(this, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_OPEN_REELS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(home);
        } catch (RuntimeException ignored) {
        }
        finish();
    }

    private TextView makeModalPill(String text, boolean focused) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(22), dp(12), dp(22), dp(12));
        styleModalPill(tv, focused);
        return tv;
    }

    private void styleModalPill(TextView tv, boolean focused) {
        tv.setBackground(glassBox(focused ? 0xFFFFFFFF : 0x22FFFFFF, focused ? 0xFFFFFFFF : 0x3DFFFFFF, dp(22)));
        tv.setTextColor(focused ? 0xFF121622 : Color.WHITE);
    }

    private GradientDrawable glassBox(int fill, int stroke, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private static String joinStrings(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
