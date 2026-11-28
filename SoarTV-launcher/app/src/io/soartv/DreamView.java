package io.soartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The SoarTV 4K UHD Photography Screensaver:
 *  - Rotating True-4K (3840x2160) photography galleries with optional Ken Burns pan/zoom and crossfades;
 *  - Top right: date, live World Clock secondary timezone, weather, and Liquid Glass / Minimal / Editorial / Classic clock;
 *  - Bottom left: artwork title, photographer credit, and geographic location (each independently toggleable);
 *  - Direct-Canvas overlay rendering (zero child RenderNode recordings) so toggling text visibility via Up/Down or
 *    Settings restores 100% of enabled fields on the very next VSYNC (< 16 ms);
 *  - Deterministic Left/Right wallpaper navigation with bounded multi-slide RAM history, directional preloading,
 *    and 380 ms 60fps manual crossfade.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, MANUAL_FADE_MS = 380, FIRST_MS = 1_200;
    /** Maximum number of recently displayed slides retained in GPU/RAM history for 0 ms multi-step Left/Right reversals. */
    static final int MAX_HISTORY_SLIDES = 3;
    private static final float ZOOM = 0.1f;

    /**
     * Bounded LRU slide history cache keyed by deterministic photo key ({@code "photo:<id>"}).
     *
     * <p><b>Why:</b> Enables 0 ms instant RAM swaps across consecutive {@code Left -> Left -> Left} or
     * {@code Right -> Left} steps while strictly capping retained GPU buffers and returning evicted entries
     * for deferred post-crossfade recycling.
     */
    static final class SlideHistoryCache<T> {
        private final int maxEntries;
        private final LinkedHashMap<String, T> entries;

        SlideHistoryCache(int maxEntries) {
            this.maxEntries = Math.max(1, maxEntries);
            this.entries = new LinkedHashMap<>(8, 0.75f, true);
        }

        /** Inserts {@code slide} under {@code key} and returns any displaced/oldest evicted entry, or {@code null}. */
        T put(String key, T slide) {
            if (key == null || key.isEmpty() || slide == null) return null;
            T replaced = entries.put(key, slide);
            if (replaced != null && replaced != slide) {
                return replaced;
            }
            if (entries.size() > maxEntries) {
                Iterator<Map.Entry<String, T>> it = entries.entrySet().iterator();
                T oldest = it.next().getValue();
                it.remove();
                return oldest;
            }
            return null;
        }

        /** Removes and returns the cached slide for {@code key} in O(1) time, or {@code null} on cache miss. */
        T take(String key) {
            if (key == null || key.isEmpty()) return null;
            return entries.remove(key);
        }

        /** Live view of currently retained slides for bitmap reference checks and cleanup. */
        Collection<T> values() {
            return entries.values();
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
        }
    }

    /**
     * Resettable Fisher-Yates shuffle bag for non-repeating randomized slide selection.
     * Guarantees uniform random distribution across all active categories, cycles through all items
     * before repeating, and avoids back-to-back duplicates across cycle boundaries.
     */
    static final class ShuffleBag<T> {
        private final List<T> pool = new ArrayList<>();
        private final List<T> bag = new ArrayList<>();
        private final Random rng;
        private int cursor = 0;
        private T lastDrawn;
        private String lastDrawnKey;

        ShuffleBag() {
            this(new Random());
        }

        ShuffleBag(Random rng) {
            this.rng = rng != null ? rng : new Random();
        }

        static String itemKey(Object item) {
            if (item == null) return "";
            return (item instanceof Recs.Pick) ? Recs.key((Recs.Pick) item) : String.valueOf(item);
        }

        synchronized void setPool(Collection<? extends T> items) {
            pool.clear();
            if (items != null) {
                Set<String> seen = new HashSet<>(items.size() * 2);
                for (T it : items) {
                    String k = itemKey(it);
                    if (!k.isEmpty() && seen.add(k)) {
                        pool.add(it);
                    }
                }
            }
            cursor = 0;
            lastDrawn = null;
            lastDrawnKey = null;
            reshuffle();
        }

        /**
         * Merges newly downloaded items into the active shuffle pool by canonical slide key without resetting or
         * duplicating items already visited in the current cycle, shuffling new arrivals into the remaining tail.
         */
        synchronized void mergePool(Collection<? extends T> items) {
            if (items == null || items.isEmpty()) return;
            if (pool.isEmpty()) {
                setPool(items);
                return;
            }
            Set<String> existingKeys = new HashSet<>(pool.size() * 2);
            for (T p : pool) {
                existingKeys.add(itemKey(p));
            }
            List<T> added = new ArrayList<>();
            for (T it : items) {
                String k = itemKey(it);
                if (!k.isEmpty() && existingKeys.add(k)) {
                    pool.add(it);
                    added.add(it);
                }
            }
            if (added.isEmpty()) return;
            List<T> remaining = new ArrayList<>();
            for (int i = cursor; i < bag.size(); i++) {
                remaining.add(bag.get(i));
            }
            remaining.addAll(added);
            Collections.shuffle(remaining, rng);
            while (bag.size() > cursor) {
                bag.remove(bag.size() - 1);
            }
            bag.addAll(remaining);
        }

        synchronized void reshuffle() {
            bag.clear();
            bag.addAll(pool);
            if (bag.size() > 1) {
                Collections.shuffle(bag, rng);
                if (lastDrawnKey != null && lastDrawnKey.equals(itemKey(bag.get(0)))) {
                    int swapIdx = 1 + rng.nextInt(bag.size() - 1);
                    T first = bag.get(0);
                    bag.set(0, bag.get(swapIdx));
                    bag.set(swapIdx, first);
                }
            }
            cursor = 0;
        }

        synchronized T draw(Set<String> failedKeys) {
            return draw(failedKeys, null);
        }

        /**
         * Draws the next randomized non-failed slide from the current cycle, swapping past {@code avoidKey}
         * when at least one distinct valid slide exists so forward steps never repeat the active slide.
         */
        synchronized T draw(Set<String> failedKeys, String avoidKey) {
            if (pool.isEmpty()) return null;
            int attempts = 0;
            int maxAttempts = Math.max(1, pool.size() * 2);
            T fallbackSame = null;
            while (attempts < maxAttempts) {
                if (cursor >= bag.size()) {
                    if (avoidKey != null && !avoidKey.isEmpty()) {
                        lastDrawnKey = avoidKey;
                    }
                    reshuffle();
                }
                if (bag.isEmpty()) return null;
                if (avoidKey != null && !avoidKey.isEmpty() && avoidKey.equals(itemKey(bag.get(cursor)))) {
                    int swapWith = -1;
                    for (int j = cursor + 1; j < bag.size(); j++) {
                        String jk = itemKey(bag.get(j));
                        if (!avoidKey.equals(jk) && (failedKeys == null || !failedKeys.contains(jk))) {
                            swapWith = j;
                            break;
                        }
                    }
                    if (swapWith > cursor) {
                        T curItem = bag.get(cursor);
                        bag.set(cursor, bag.get(swapWith));
                        bag.set(swapWith, curItem);
                    }
                }
                T item = bag.get(cursor++);
                attempts++;
                String k = itemKey(item);
                if (failedKeys != null && failedKeys.contains(k)) {
                    continue;
                }
                if (avoidKey != null && !avoidKey.isEmpty() && avoidKey.equals(k)) {
                    fallbackSame = item;
                    continue;
                }
                lastDrawn = item;
                lastDrawnKey = k;
                return item;
            }
            if (fallbackSame != null) {
                lastDrawn = fallbackSame;
                lastDrawnKey = itemKey(fallbackSame);
            }
            return fallbackSame;
        }

        /**
         * Draws an unvisited slide from the current cycle, preferring an unvisited slide whose 4K file is
         * already cached on disk so rapid manual navigation stays instant without EVER repeating early.
         * Falls through to uniform {@link #draw(Set, String)} when all cached slides in this cycle were visited.
         */
        synchronized T drawPreferringCached(Set<String> failedKeys, String avoidKey) {
            if (pool.isEmpty()) return null;
            if (cursor < bag.size()) {
                int cachedIdx = -1;
                for (int j = cursor; j < bag.size(); j++) {
                    T cand = bag.get(j);
                    String jk = itemKey(cand);
                    if (failedKeys != null && failedKeys.contains(jk)) continue;
                    if (avoidKey != null && !avoidKey.isEmpty() && avoidKey.equals(jk)) continue;
                    if (cand instanceof Recs.Pick && ScreensaverCatalog.isPickCachedOnDisk((Recs.Pick) cand)) {
                        cachedIdx = j;
                        break;
                    }
                }
                if (cachedIdx >= cursor) {
                    if (cachedIdx > cursor) {
                        T tmpItem = bag.get(cursor);
                        bag.set(cursor, bag.get(cachedIdx));
                        bag.set(cachedIdx, tmpItem);
                    }
                    T chosen = bag.get(cursor++);
                    lastDrawn = chosen;
                    lastDrawnKey = itemKey(chosen);
                    return chosen;
                }
            }
            return draw(failedKeys, avoidKey);
        }

        /**
         * Returns an unshown background-prepared item back into the unvisited tail of the current cycle
         * when a manual navigation supersedes an in-flight background decode.
         */
        synchronized void returnUnshown(T item) {
            if (item == null || cursor <= 0) return;
            String k = itemKey(item);
            if (k.isEmpty()) return;
            for (int i = cursor; i < bag.size(); i++) {
                if (k.equals(itemKey(bag.get(i)))) return;
            }
            int span = bag.size() - cursor;
            int insertAt = cursor + (span > 0 ? rng.nextInt(span + 1) : 0);
            bag.add(insertAt, item);
        }

        synchronized boolean isEmpty() {
            return pool.isEmpty();
        }

        synchronized int size() {
            return pool.size();
        }

        synchronized void clear() {
            pool.clear();
            bag.clear();
            cursor = 0;
            lastDrawn = null;
            lastDrawnKey = null;
        }
    }
    /** 4K UHD (3840x2160) photography resolution for the screensaver. */
    private static final int PW = 3840, PH = 2160;
    private static final float MARGIN = 80, RIGHT = 1840, TITLE_W = 900, META_BASE = 1012;
    private static final float DATE_BASE = 72, CLOCK_TOP = 100, CLOCK_SIZE = 104, QUOTES_GAP = 42;
    private static final double DRIFT_PERIOD_S = 600;
    private String range = "", staleLine = "", staleFor = "";
    private HomeData.Weather rangeWeather;
    private int rangeGeneration = -1, rangePrefs = -1, staleGeneration = -1;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner), and cached caption layout. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        long startedAt;
        private StaticLayout cachedTitleLayout;
        private String cachedTitleText = "";
        private int cachedTitleWidth = -1;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }

        StaticLayout getOrBuildTitleLayout(String title, TextPaint paint, int widthPx) {
            if (title == null || title.isEmpty() || paint == null || widthPx <= 0) {
                return null;
            }
            if (cachedTitleLayout == null || widthPx != cachedTitleWidth || !title.equals(cachedTitleText)) {
                cachedTitleText = title;
                cachedTitleWidth = widthPx;
                cachedTitleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), paint, widthPx)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .build();
            }
            return cachedTitleLayout;
        }

        void invalidateLayout() {
            cachedTitleLayout = null;
            cachedTitleWidth = -1;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final TextPaint fit = new TextPaint();
    private String playingCorner, playingBottom;
    private float playingCornerWidth, playingBottomWidth, cornerCap, bottomCap, cornerBase;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockCardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockCardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF tmpCardRect = new RectF();
    private final Rect tmpClockBounds = new Rect();
    private final Rect tmpClockBounds2 = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private ExecutorService manualWorker = Executors.newSingleThreadExecutor();
    private ExecutorService glassWorker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private final Set<String> failed = new HashSet<>();
    private RuntimeShader glassShader;
    private boolean running, dirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean bgDecoding;
    private boolean manualTransition;
    static final int MAX_NAV_HISTORY = 100;
    private final List<String> navHistory = new ArrayList<>(MAX_NAV_HISTORY + 8);
    private int navIndex = -1;
    private final ShuffleBag<Recs.Pick> shuffleBag = new ShuffleBag<>();
    private final Map<String, Recs.Pick> pickMap = new HashMap<>();
    private Set<String> activeCategories = new HashSet<>();
    private int preloadDirection = 1;
    private String pendingManualKey;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private final SlideHistoryCache<Slide> historySlides = new SlideHistoryCache<>(MAX_HISTORY_SLIDES);
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private boolean infoVisible = true;
    private Paint datePaint, clockPaint, weatherPaint, weatherDim, quotePaint, stalePaint;
    private Paint cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, metaPaint, hintPaint;
    private TextPaint titlePaint;
    private Typeface serifBoldTypeface;
    private Typeface monoBoldTypeface;
    private LinearGradient cachedFallbackGlassGrad;
    private float cachedFallbackGlassX = Float.NaN, cachedFallbackGlassY = Float.NaN;
    private final Runnable catalogListener = () -> {
        if (!running) return;
        fallback = false;
        failed.clear();
        prepareNext();
        invalidate();
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            playing.start();
            data.refreshIfStale();
            recs.refreshIfStale();
            recs.prefetchAll();
            dirty = true;
            prepareNext();
            invalidate();
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        recs = Recs.get(c);
        playing = new NowPlaying.Watch(c, () -> {
            fitPlaying();
            onData();
        });
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        Prefs.reload();
        ScreensaverCatalog.ensureStartersExtracted(getContext(), Prefs.saverCategories(getContext()));
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        if (manualWorker.isShutdown()) manualWorker = Executors.newSingleThreadExecutor();
        if (glassWorker.isShutdown()) glassWorker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        minute = -1;
        pendingManualKey = null;
        manualTransition = false;
        preloadDirection = 1;
        failed.clear();
        navHistory.clear();
        navIndex = -1;
        activeCategories.clear();
        shuffleBag.clear();
        fallback = false;
        ScreensaverCatalog.addListener(catalogListener);
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.prefetchAll();
        prepareNext();
        handler.postDelayed(tick, 60_000);
        playing.start();
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        bgDecoding = false;
        pendingManualKey = null;
        manualTransition = false;
        preloadDirection = 1;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        playing.stop();
        ScreensaverCatalog.removeListener(catalogListener);
        data.removeListener(this);
        recs.removeListener(this);
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        for (Slide s : historySlides.values()) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) {
            handler.postDelayed(() -> {
                for (Bitmap b : gone) {
                    if (b != null && !b.isRecycled()) b.recycle();
                }
            }, 1000);
        }
        current = incoming = prepared = null;
        historySlides.clear();
        navHistory.clear();
        navIndex = -1;
        activeCategories.clear();
        shuffleBag.clear();
        glass = null;
        slides = 0;
        glassFor = "";
    }

    private boolean isBitmapReferenced(Bitmap target) {
        if (target == null) return false;
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s != null && (s.image == target || s.frost == target)) return true;
        }
        for (Slide s : historySlides.values()) {
            if (s != null && (s.image == target || s.frost == target)) return true;
        }
        return false;
    }

    private void scheduleSlideRecycle(Slide old) {
        if (old == null) return;
        final Bitmap img = old.image;
        final Bitmap fr = old.frost;
        handler.postDelayed(() -> {
            if (img != null && !img.isRecycled() && !isBitmapReferenced(img)) img.recycle();
            if (fr != null && !fr.isRecycled() && !isBitmapReferenced(fr)) fr.recycle();
        }, 800);
    }

    private void stashInHistory(Slide old) {
        if (old == null || old.pick == null) return;
        if (old.image != null && old.image.isRecycled()) return;
        Slide evicted = historySlides.put(Recs.key(old.pick), old);
        if (evicted != null && evicted != old) {
            scheduleSlideRecycle(evicted);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
        manualWorker.shutdownNow();
        glassWorker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        lastFrame = 0;
        invalidate();
    }

    @Override public void onRecs() {
        if (running) prepareNext();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        scene.setSize(w, h);
        content.setPosition(0, 0, w, h);
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        quotePaint = Ui.numbers(Ui.medium, 24 * k, 0x99FFFFFF);
        stalePaint = Ui.text(Ui.regular, 20 * k, 0x59FFFFFF);
        stalePaint.setTextAlign(Paint.Align.CENTER);
        cornerDate = Ui.numbers(Ui.semibold, 24 * k, 0xE6FFFFFF);
        cornerQuote = Ui.numbers(Ui.regular, 21 * k, 0xC7FFFFFF);
        cornerStale = Ui.text(Ui.medium, 19 * k, 0x99FFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        clockCardStroke.setStyle(Paint.Style.STROKE);
        labelPaint = Ui.text(Ui.medium, 24 * k, 0xB8FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 25 * k, 0xD4FFFFFF);
        hintPaint = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
        metaPaint.setLetterSpacing(0.012f);
        Symbols.warmWeather(getContext(), 26 * k);
        Symbols.warmWeather(getContext(), 32 * k);
        for (Paint p : new Paint[] {cornerDate, cornerQuote, cornerStale, cornerClock, labelPaint, titlePaint, metaPaint, barPaint}) {
            p.setShadowLayer(14 * k, 0, 2 * k, 0x55000000);
        }
        fitPlaying();
        glassFor = "";
        dirty = true;
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s != null) s.invalidateLayout();
        }
        for (Slide s : historySlides.values()) {
            if (s != null) s.invalidateLayout();
        }
    }

    // MARK: - Photos

    /** Rotating True-4K UHD photography wallpapers across all checked categories. */
    private List<Recs.Pick> photos() {
        return new ArrayList<>(ScreensaverCatalog.picks(getContext(), Prefs.saverCategories(getContext())));
    }

    private void syncCatalogPool() {
        List<Recs.Pick> list = photos();
        pickMap.clear();
        for (Recs.Pick p : list) {
            pickMap.put(Recs.key(p), p);
        }
        Set<String> currentCats = Prefs.saverCategories(getContext());
        if (!currentCats.equals(activeCategories)) {
            activeCategories = new HashSet<>(currentCats);
            shuffleBag.setPool(list);
            failed.clear();
            if (prepared != null) {
                Slide oldPrep = prepared;
                prepared = null;
                scheduleSlideRecycle(oldPrep);
            }
            for (Slide s : historySlides.values()) {
                scheduleSlideRecycle(s);
            }
            historySlides.clear();
            navHistory.clear();
            navIndex = -1;
        } else if (shuffleBag.isEmpty() && !list.isEmpty()) {
            shuffleBag.setPool(list);
        } else if (list.size() > shuffleBag.size()) {
            shuffleBag.mergePool(list);
        }
        if (!list.isEmpty()) {
            fallback = false;
        }
    }

    /**
     * Pure deterministic index stepper for Left ({@code delta = -1}) and Right ({@code delta = +1}) wallpaper cycling.
     * Skips any keys present in {@code failedKeys} and wraps seamlessly around catalog boundaries.
     */
    public static int computeSteppedIndex(List<String> keys, String anchorKey, Set<String> failedKeys, int delta) {
        if (keys == null || keys.isEmpty()) return -1;
        int n = keys.size();
        int dir = delta >= 0 ? 1 : -1;
        int anchorIdx = -1;
        if (anchorKey != null && !anchorKey.isEmpty()) {
            for (int i = 0; i < n; i++) {
                if (anchorKey.equals(keys.get(i))) {
                    anchorIdx = i;
                    break;
                }
            }
        }
        if (anchorIdx < 0) {
            for (int step = 0; step < n; step++) {
                int candidate = ((dir > 0 ? step : (n - 1 - step)) % n + n) % n;
                if (failedKeys == null || !failedKeys.contains(keys.get(candidate))) {
                    return candidate;
                }
            }
            return 0;
        }
        for (int step = 1; step <= n; step++) {
            int candidate = ((anchorIdx + step * dir) % n + n) % n;
            String key = keys.get(candidate);
            if (failedKeys == null || !failedKeys.contains(key)) {
                return candidate;
            }
        }
        return anchorIdx;
    }

    /** Loads the next photo in the active direction (one at a time), reusing cached history slides in 0 ms when available. */
    private void prepareNext() {
        if (!running || bgDecoding || prepared != null || pendingManualKey != null) return;
        syncCatalogPool();
        if (pickMap.isEmpty()) return;

        Slide last = incoming != null ? incoming : current;
        String activeKey = last != null ? Recs.key(last.pick) : null;
        String nextKey = null;
        final boolean drewFromShuffleBag;
        if (preloadDirection > 0) {
            if (navIndex < navHistory.size() - 1) {
                nextKey = navHistory.get(navIndex + 1);
                drewFromShuffleBag = false;
            } else {
                // First slide prefers an instant cached starter/photo; subsequent background prepares draw uniformly across all 205+
                Recs.Pick drawn = (current == null)
                        ? shuffleBag.drawPreferringCached(failed, activeKey)
                        : shuffleBag.draw(failed, activeKey);
                nextKey = drawn != null ? Recs.key(drawn) : null;
                drewFromShuffleBag = (drawn != null);
            }
        } else {
            drewFromShuffleBag = false;
            if (navIndex > 0) {
                nextKey = navHistory.get(navIndex - 1);
            }
        }

        if (nextKey == null) {
            if (current == null) fallback = true;
            return;
        }

        Recs.Pick pick = pickMap.get(nextKey);
        if (pick == null) return;

        if (last != null && nextKey.equals(activeKey)) {
            // Only one photo available: reuse with alternating motion
            prepared = newSlide(pick, last.image, last.frost, last.lightCaption, last.lightClock);
            return;
        }
        Slide cached = historySlides.take(nextKey);
        if (cached != null && cached.image != null && !cached.image.isRecycled()) {
            prepared = cached;
            lastFrame = 0;
            invalidate();
            return;
        }
        final File preResolved = (pick.photo == null) ? recs.readyImage(pick) : null;
        if (pick.photo == null && preResolved == null) {
            failed.add(nextKey);
            if (failed.size() < pickMap.size()) prepareNext();
            return;
        }
        bgDecoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        final Context appCtx = getContext();
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                File readyFile = (chosen.photo != null)
                        ? (ScreensaverCatalog.isPickCachedOnDisk(chosen)
                                ? chosen.photo
                                : ((chosen.imageUrl != null && !chosen.imageUrl.isEmpty())
                                        ? ScreensaverCatalog.ensurePickFileReady(appCtx, chosen)
                                        : (chosen.photo.exists() ? chosen.photo : null)))
                        : preResolved;
                if (readyFile != null && readyFile.isFile()) {
                    images = Backdrop.photo(readyFile, PW, PH, light);
                    boolean hasCaption = hasLiveCaption(chosen);
                    images[0] = Backdrop.gpu(withScrims(images[0], hasCaption, light[0], light[1]));
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + (chosen.photo != null ? chosen.photo.getName() : chosen.title) + "”: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                bgDecoding = false;
                if (gen != generation || !running || prepared != null) {
                    if (drewFromShuffleBag && running) {
                        shuffleBag.returnUnshown(chosen);
                    }
                    if (ready != null) {
                        for (Bitmap b : ready) {
                            if (b != null && !b.isRecycled()) b.recycle();
                        }
                    }
                    if (running && prepared == null && pendingManualKey == null) {
                        prepareNext();
                    }
                    return;
                }
                if (ready == null) {
                    failed.add(Recs.key(chosen));
                    if (failed.size() < pickMap.size()) {
                        prepareNext();
                    }
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                lastFrame = 0;
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        return new Slide(pick, image, frost, lightCaption, lightClock, slides++);
    }

    private long activeFadeDurationMs() {
        return manualTransition ? MANUAL_FADE_MS : FADE_MS;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every interval). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            manualTransition = false;
            recordCurrentInHistory(Recs.key(current.pick));
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && pendingManualKey == null
                && shouldAutoAdvanceSlide(up - current.startedAt, showDurationMs())) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            manualTransition = false;
            preloadDirection = 1;
            advanceHistoryOnIncoming(Recs.key(incoming.pick));
            bindPhotos();
            return;
        }
        long fadeDur = activeFadeDurationMs();
        if (incoming != null && up >= incoming.startedAt + fadeDur) {
            Slide old = current;
            current = incoming;
            incoming = null;
            if (manualTransition) {
                // Reset slide timer from completion of manual navigation so user gets full interval on chosen slide
                current.startedAt = up;
                manualTransition = false;
            }
            bindPhotos();
            stashInHistory(old);
            prepareNext();
        }
    }

    private void recordCurrentInHistory(String key) {
        if (key == null || key.isEmpty()) return;
        if (navIndex >= 0 && navIndex < navHistory.size() && key.equals(navHistory.get(navIndex))) {
            return;
        }
        navHistory.add(key);
        navIndex = navHistory.size() - 1;
        if (navHistory.size() > MAX_NAV_HISTORY) {
            navHistory.remove(0);
            navIndex = navHistory.size() - 1;
        }
    }

    private void advanceHistoryOnIncoming(String key) {
        if (key == null || key.isEmpty()) return;
        if (navIndex < navHistory.size() - 1) {
            navIndex++;
        } else {
            recordCurrentInHistory(key);
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null || current.shader == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", (incoming != null && incoming.shader != null) ? incoming.shader : current.shader);
    }

    /** Half-period (in ms) for gentle Ken Burns breathing motion when slide duration is "Don't change image" (0). */
    static final long STATIC_KEN_BURNS_HALF_PERIOD_MS = 45_000L;

    /**
     * Returns whether the active screensaver photo should automatically transition to the next slide.
     * When {@code intervalMs <= 0} ("Don't change image"), automatic transitions are disabled while
     * manual Left/Right remote navigation remains available.
     */
    static boolean shouldAutoAdvanceSlide(long elapsedMs, long intervalMs) {
        return intervalMs > 0L && elapsedMs >= intervalMs;
    }

    /**
     * Computes normalized Ken Burns motion progress {@code [0, 1]} for the active slide.
     * When {@code intervalMs <= 0} ("Don't change image"), oscillates smoothly between 0 and 1 over
     * {@link #STATIC_KEN_BURNS_HALF_PERIOD_MS} so enabled image animations drift gently without snapping.
     */
    static float computeKenBurnsProgress(long elapsedMs, long intervalMs) {
        long safeElapsed = Math.max(0L, elapsedMs);
        if (intervalMs <= 0L) {
            long fullCycle = STATIC_KEN_BURNS_HALF_PERIOD_MS * 2L;
            float phase = (safeElapsed % fullCycle) / (float) STATIC_KEN_BURNS_HALF_PERIOD_MS;
            return phase <= 1f ? phase : (2f - phase);
        }
        return Math.max(0f, Math.min(1f, safeElapsed / (float) (intervalMs + FADE_MS)));
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        if (!Prefs.saverAnimation()) {
            computeStaticCenterPlacement(w, h, s.image.getWidth(), s.image.getHeight(), out);
            return;
        }
        float u = computeKenBurnsProgress(up - s.startedAt, showDurationMs());
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private long showDurationMs() {
        return Prefs.saverIntervalMs();
    }

    public static void computeStaticCenterPlacement(int viewW, int viewH, int imgW, int imgH, float[] out) {
        if (imgW <= 0 || imgH <= 0) return;
        float scale = Math.max((float) viewW / imgW, (float) viewH / imgH);
        out[0] = (viewW - imgW * scale) / 2f;
        out[1] = (viewH - imgH * scale) / 2f;
        out[2] = scale;
    }

    public static boolean isScreensaverNavKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    public static boolean isScreensaverDismissKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    public static boolean shouldConsumeScreensaverKey(int keyCode) {
        return isScreensaverNavKey(keyCode);
    }

    public boolean isInfoVisible() {
        return infoVisible;
    }

    /**
     * Instantly toggles all on-screen text/clock overlays on or off and triggers an immediate next-VSYNC redraw.
     */
    public void toggleInfo() {
        infoVisible = !infoVisible;
        if (infoVisible) {
            long now = System.currentTimeMillis();
            clock = Ui.clock(now);
            date = Ui.date(now);
            minute = now / 60_000;
            if (!glassFor.equals(clock)) buildGlass(clock);
        }
        lastFrame = 0;
        invalidate();
    }

    public boolean handleKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                previousSlide();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                nextSlide();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                toggleInfo();
            }
            return true;
        }
        return false;
    }

    private void promoteSlideToIncoming(Slide chosenSlide, long up) {
        if (chosenSlide == null) return;
        pendingManualKey = null;
        if (current == null) {
            current = chosenSlide;
            current.startedAt = up;
            firstAt = up;
            manualTransition = false;
            bindPhotos();
            lastFrame = 0;
            invalidate();
            prepareNext();
            return;
        }
        if (incoming != null) {
            Slide old = current;
            current = incoming;
            stashInHistory(old);
        }
        incoming = chosenSlide;
        incoming.startedAt = up;
        manualTransition = true;
        bindPhotos();
        lastFrame = 0;
        invalidate();
        prepareNext();
    }

    private void stepSlide(int delta) {
        syncCatalogPool();
        if (pickMap.isEmpty()) return;
        preloadDirection = delta >= 0 ? 1 : -1;
        long up = SystemClock.uptimeMillis();
        if (current != null) current.startedAt = up;

        Slide activeSlide = incoming != null ? incoming : current;
        String currentKey = activeSlide != null ? Recs.key(activeSlide.pick) : null;
        String targetKey = null;
        int targetNavIndex = -1;

        if (delta < 0) {
            // Backward navigation through exact visited history
            if (navIndex <= 0) {
                // Clamped at the beginning of history (testF7_Boundary_RapidLeftPressesClampAtBeginning)
                return;
            }
            targetNavIndex = navIndex - 1;
            targetKey = navHistory.get(targetNavIndex);
        } else {
            // Forward navigation
            if (navIndex < navHistory.size() - 1) {
                // Traversing already-visited history before drawing new random items
                targetNavIndex = navIndex + 1;
                targetKey = navHistory.get(targetNavIndex);
            } else if (prepared != null && prepared.image != null && !prepared.image.isRecycled()
                    && (pickMap.size() <= 1 || !Recs.key(prepared.pick).equals(currentKey))) {
                // Reuse the already pre-decoded frontier slide drawn by prepareNext() for a 0 ms RAM hit
                targetKey = Recs.key(prepared.pick);
                targetNavIndex = navHistory.size();
            } else {
                // At the frontier without a ready prepared slide: prefer an unvisited already-cached random item for instant 0ms response
                Recs.Pick drawn = shuffleBag.drawPreferringCached(failed, currentKey);
                if (drawn != null) {
                    targetKey = Recs.key(drawn);
                    targetNavIndex = navHistory.size();
                }
            }
        }

        if (targetKey == null) return;
        Recs.Pick target = pickMap.get(targetKey);
        if (target == null) return;

        // If only 1 wallpaper available and already displayed
        if (pickMap.size() == 1 && current != null && targetKey.equals(Recs.key(current.pick))) {
            return;
        }

        // Mid-crossfade reversal back to current slide (0 ms RAM hit)
        if (incoming != null && current != null && Recs.key(current.pick).equals(targetKey)
                && current.image != null && !current.image.isRecycled()) {
            Slide reversing = current;
            current = incoming;
            incoming = null;
            if (prepared != null && !Recs.key(prepared.pick).equals(targetKey)) {
                Slide stalePrep = prepared;
                prepared = null;
                stashInHistory(stalePrep);
            }
            applyNavTarget(targetKey, targetNavIndex);
            promoteSlideToIncoming(reversing, up);
            return;
        }

        // Prepared slide match (0 ms hit)
        if (prepared != null && Recs.key(prepared.pick).equals(targetKey)
                && prepared.image != null && !prepared.image.isRecycled()) {
            Slide hit = prepared;
            prepared = null;
            applyNavTarget(targetKey, targetNavIndex);
            promoteSlideToIncoming(hit, up);
            return;
        }

        // Bounded RAM History Cache match (Tier 2 LRU hit, 0 ms hit)
        Slide histHit = historySlides.take(targetKey);
        if (histHit != null && histHit.image != null && !histHit.image.isRecycled()) {
            if (prepared != null) {
                Slide stalePrep = prepared;
                prepared = null;
                stashInHistory(stalePrep);
            }
            applyNavTarget(targetKey, targetNavIndex);
            promoteSlideToIncoming(histHit, up);
            return;
        }

        // Tier 1 on-demand disk decode / network fetch
        File preResolved = (target.photo == null) ? recs.readyImage(target) : null;
        if (target.photo == null && preResolved == null) {
            failed.add(targetKey);
            if (failed.size() < pickMap.size()) {
                stepSlide(delta);
            }
            return;
        }

        loadAndShow(target, preResolved, delta, targetNavIndex, up);
    }

    private void applyNavTarget(String targetKey, int targetNavIndex) {
        if (targetNavIndex >= 0 && targetNavIndex < navHistory.size()) {
            navIndex = targetNavIndex;
        } else {
            recordCurrentInHistory(targetKey);
        }
    }

    private void loadAndShow(Recs.Pick chosen, File preResolved, int delta, final int targetNavIndex, long up) {
        if (prepared != null) {
            Slide stalePrep = prepared;
            prepared = null;
            stashInHistory(stalePrep);
        }
        final String targetKey = Recs.key(chosen);
        pendingManualKey = targetKey;
        final int gen = ++generation;
        final Context appCtx = getContext();
        manualWorker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                File readyFile = (chosen.photo != null)
                        ? (ScreensaverCatalog.isPickCachedOnDisk(chosen)
                                ? chosen.photo
                                : ((chosen.imageUrl != null && !chosen.imageUrl.isEmpty())
                                        ? ScreensaverCatalog.ensurePickFileReady(appCtx, chosen)
                                        : (chosen.photo.exists() ? chosen.photo : null)))
                        : preResolved;
                if (readyFile != null && readyFile.isFile()) {
                    images = Backdrop.photo(readyFile, PW, PH, light);
                    boolean hasCaption = hasLiveCaption(chosen);
                    images[0] = Backdrop.gpu(withScrims(images[0], hasCaption, light[0], light[1]));
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver manual nav: " + e.getMessage());
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation || !running) {
                    if (ready != null) {
                        for (Bitmap b : ready) {
                            if (b != null && !b.isRecycled()) b.recycle();
                        }
                    }
                    return;
                }
                pendingManualKey = null;
                if (ready == null) {
                    failed.add(targetKey);
                    if (failed.size() < pickMap.size()) {
                        stepSlide(delta);
                    }
                    return;
                }
                applyNavTarget(targetKey, targetNavIndex);
                long now = SystemClock.uptimeMillis();
                if (current == null) {
                    current = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                    current.startedAt = now;
                    firstAt = now;
                    manualTransition = false;
                } else {
                    if (incoming != null) {
                        Slide old = current;
                        current = incoming;
                        stashInHistory(old);
                    }
                    incoming = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                    incoming.startedAt = now;
                    manualTransition = true;
                }
                bindPhotos();
                lastFrame = 0;
                invalidate();
                prepareNext();
            });
        });
    }

    public void nextSlide() {
        stepSlide(1);
    }

    public void previousSlide() {
        stepSlide(-1);
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f || s.image == null || s.image.isRecycled()) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 60 Hz during manual Left/Right crossfades, 20 Hz during calm ambient Ken Burns drift

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long minStepNanos = (incoming != null && manualTransition) ? 14_000_000L : 45_000_000L;
        if (frameTimeNanos - lastFrame >= minStepNanos) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        return SystemClock.uptimeMillis();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        float first = current == null ? 0 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) {
            if (infoVisible) {
                drawCornerDirect(canvas, 1f, now);
            }
            return;
        }
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        long fadeDur = activeFadeDurationMs();
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, fadeDur);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (infoVisible) {
            drawCornerDirect(canvas, first, now);
            if (playingCorner != null) {
                float x = RIGHT * k - playingCornerWidth - cornerCap * 0.6f - NowPlaying.barsWidth(cornerCap);
                drawBars(canvas, x, cornerBase, cornerCap, cornerQuote.getColor(), first);
            }
            drawGlass(canvas, first, e);
            drawCaptionsDirect(canvas, up);
        }
    }

    /** What an app is playing, cut to fit the corner and the bottom line: once per title, not while drawing. */
    private void fitPlaying() {
        String now = playing.text;
        if (now == null || quotePaint == null) {
            playingCorner = playingBottom = null;
            return;
        }
        fit.set(cornerQuote);
        playingCorner = TextUtils.ellipsize(now, fit, 860 * k, TextUtils.TruncateAt.END).toString();
        playingCornerWidth = cornerQuote.measureText(playingCorner);
        fit.set(quotePaint);
        playingBottom = TextUtils.ellipsize(now, fit, w - 2 * MARGIN * k - 60 * k, TextUtils.TruncateAt.END).toString();
        playingBottomWidth = quotePaint.measureText(playingBottom);
        cornerCap = Ui.cap(cornerQuote);
        bottomCap = Ui.cap(quotePaint);
        cornerBase = (CLOCK_TOP + Ui.cap(cornerClock) / k + QUOTES_GAP) * k;
    }

    private void drawBars(Canvas c, float x, float base, float cap, int color, float alpha) {
        barPaint.setColor(Ui.alpha(color, alpha));
        NowPlaying.drawBars(c, x, base, cap, barPaint, true, barRect);
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) Math.max(1L, duration));
        return t * t * (3 - 2 * t);
    }

    private static Bitmap withScrims(Bitmap photo, boolean withCaption, float lightCaption, float lightClock) {
        if (photo == null || photo.isRecycled() || photo.getWidth() <= 0 || photo.getHeight() <= 0 || !photo.isMutable()) {
            return photo;
        }
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = withCaption ? scrimStrength(lightCaption, 0.46f) : 0f;
        float corner = scrimStrength(lightClock, 0.38f);

        // 1. Bottom-left caption vignette: smooth elliptical radial centered at (0, h)
        if (caption > 0.01f) {
            float rCaption = w * 0.65f;
            RadialGradient around = new RadialGradient(0, h, rCaption,
                    new int[] {black(0.44f * caption), black(0.24f * caption), black(0.07f * caption), 0},
                    new float[] {0f, 0.36f, 0.72f, 1f}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1.0f, 0.55f, 0, h);
            around.setLocalMatrix(m);
            p.setShader(around);
            c.drawRect(0, 0, w, h, p);
        }

        // 2. Top-right clock vignette: smooth elliptical radial centered at (w, 0)
        if (corner > 0.01f) {
            float rClock = w * 0.52f;
            RadialGradient clockSide = new RadialGradient(w, 0, rClock,
                    new int[] {black(0.38f * corner), black(0.20f * corner), black(0.06f * corner), 0},
                    new float[] {0f, 0.36f, 0.72f, 1f}, Shader.TileMode.CLAMP);
            Matrix mc = new Matrix();
            mc.setScale(1.0f, 0.65f, w, 0);
            clockSide.setLocalMatrix(mc);
            p.setShader(clockSide);
            c.drawRect(0, 0, w, h, p);
        }

        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Direct-Canvas Top-Right Corner: date, world clock, weather, 8 clock designs, prices

    static String[] splitHourMinute(String clockText) {
        if (clockText != null) {
            int colon = clockText.indexOf(':');
            if (colon > 0 && colon + 2 < clockText.length()) {
                String hh = clockText.substring(0, colon).trim();
                String mm = clockText.substring(colon + 1, colon + 3).trim();
                if (hh.length() == 1 && Character.isDigit(hh.charAt(0))) {
                    hh = "0" + hh;
                }
                return new String[] {hh, mm};
            }
        }
        return new String[] {"12", "00"};
    }

    private void resetCornerClockPaint() {
        cornerClock.setStyle(Paint.Style.FILL);
        cornerClock.setStrokeWidth(0f);
        cornerClock.setShader(null);
        cornerClock.setTypeface(Ui.displaySemibold);
        cornerClock.setTextSize(CLOCK_SIZE * k);
        cornerClock.setLetterSpacing(0f);
        cornerClock.setColor(0xF2FFFFFF);
    }

    private void drawCornerDirect(Canvas c, float alpha, long nowMillis) {
        if (alpha <= 0.004f) return;
        int save = alpha < 0.996f
                ? c.saveLayerAlpha(w * 0.32f, 0, w, 420 * k, Math.round(255 * alpha))
                : -1;
        try {
            float right = RIGHT * k;
            float x = right;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather() && Prefs.saverShowWeather()) {
                String temp = HomeData.degrees(wx.temp);
                float tw = cornerDate.measureText(temp);
                c.drawText(temp, x - tw, DATE_BASE * k, cornerDate);
                x -= tw + 9 * k;
                String symbol = Symbols.weather(wx.code, wx.isDay);
                float size = 26 * k, sw = Symbols.width(getContext(), symbol, size);
                Symbols.draw(getContext(), c, symbol, x - sw / 2, DATE_BASE * k - Ui.halfCap(cornerDate), size, 0xE6FFFFFF);
                x -= sw + 22 * k;
            }
            String worldClock = ScreensaverCatalog.formatWorldClock(nowMillis);
            StringBuilder headerLine = new StringBuilder();
            if (Prefs.saverShowCalendar() && date != null && !date.isEmpty()) {
                headerLine.append(date);
            }
            if (Prefs.saverShowWorldClock() && !worldClock.isEmpty()) {
                if (headerLine.length() > 0) headerLine.append("  \u00B7  ");
                headerLine.append(worldClock);
            }
            if (headerLine.length() > 0) {
                String dateAndWorld = headerLine.toString();
                c.drawText(dateAndWorld, x - cornerDate.measureText(dateAndWorld), DATE_BASE * k, cornerDate);
            }

            float effectiveClockBottom = (CLOCK_TOP + Ui.cap(cornerClock) / k) * k;

            if (Prefs.saverShowLocalTime() && clock != null && !clock.isEmpty()) {
                String style = Prefs.saverClockStyle();
                Glass g = ("liquid_glass".equals(style) && current != null) ? glass : null;
                if (g != null && glassShader != null) {
                    shadowPaint.setAlpha(68);
                    c.drawBitmap(g.shadow, g.x, g.y + 3 * k, shadowPaint);
                } else if ("liquid_glass".equals(style)) {
                    // Clean frosted crystal Canvas fallback for API < 33 or pre-load (zero offset ghost duplicate)
                    resetCornerClockPaint();
                    cornerClock.getTextBounds(clock, 0, clock.length(), tmpClockBounds);
                    float clockX = right - tmpClockBounds.right;
                    float clockY = CLOCK_TOP * k - tmpClockBounds.top;
                    float clockH = Math.max(1, tmpClockBounds.height());

                    if (cachedFallbackGlassGrad == null
                            || cachedFallbackGlassX != clockX
                            || cachedFallbackGlassY != clockY) {
                        cachedFallbackGlassX = clockX;
                        cachedFallbackGlassY = clockY;
                        cachedFallbackGlassGrad = new LinearGradient(clockX, clockY - clockH, clockX, clockY,
                                new int[] {0xEEFFFFFF, 0xA8DCE8F5, 0xCCF2F7FC},
                                new float[] {0f, 0.50f, 1f}, Shader.TileMode.CLAMP);
                    }
                    cornerClock.setShader(cachedFallbackGlassGrad);
                    cornerClock.setColor(0xFFFFFFFF);
                    c.drawText(clock, clockX, clockY, cornerClock);
                    cornerClock.setShader(null);

                    cornerClock.setStyle(Paint.Style.STROKE);
                    cornerClock.setStrokeJoin(Paint.Join.ROUND);
                    cornerClock.setStrokeWidth(1.25f * k);
                    cornerClock.setColor(0xDCFFFFFF);
                    c.drawText(clock, clockX, clockY, cornerClock);
                    resetCornerClockPaint();
                } else if ("frosted_pill".equals(style)) {
                    resetCornerClockPaint();
                    cornerClock.setTypeface(Ui.displayMedium);
                    cornerClock.setTextSize(80 * k);
                    cornerClock.setLetterSpacing(0.01f);
                    cornerClock.getTextBounds(clock, 0, clock.length(), tmpClockBounds);
                    float textW = Math.max(1, tmpClockBounds.width());
                    float textH = Math.max(1, tmpClockBounds.height());
                    float padX = 28 * k, padY = 16 * k;
                    float pillW = textW + 2 * padX, pillH = textH + 2 * padY;
                    float pillLeft = right - pillW, pillTop = (CLOCK_TOP - 4) * k;
                    float pillBottom = pillTop + pillH;
                    float pillR = pillH * 0.5f;

                    tmpCardRect.set(pillLeft, pillTop, right, pillBottom);
                    clockCardFill.setColor(0x580B0F18);
                    c.drawRoundRect(tmpCardRect, pillR, pillR, clockCardFill);
                    clockCardStroke.setStrokeWidth(1.3f * k);
                    clockCardStroke.setColor(0x46FFFFFF);
                    c.drawRoundRect(tmpCardRect, pillR, pillR, clockCardStroke);

                    cornerClock.setColor(0xF5FFFFFF);
                    c.drawText(clock, pillLeft + padX - tmpClockBounds.left, pillTop + padY - tmpClockBounds.top, cornerClock);
                    effectiveClockBottom = pillBottom;
                    resetCornerClockPaint();
                } else if ("stacked".equals(style)) {
                    resetCornerClockPaint();
                    String[] hm = splitHourMinute(clock);
                    cornerClock.setTypeface(Ui.displaySemibold);
                    cornerClock.setTextSize(76 * k);
                    cornerClock.setLetterSpacing(-0.03f);
                    cornerClock.getTextBounds(hm[0], 0, hm[0].length(), tmpClockBounds);
                    cornerClock.getTextBounds(hm[1], 0, hm[1].length(), tmpClockBounds2);
                    float topRowY = CLOCK_TOP * k - tmpClockBounds.top;
                    float botRowTop = (CLOCK_TOP + 66) * k;
                    float botRowY = botRowTop - tmpClockBounds2.top;
                    float maxRowW = Math.max(tmpClockBounds.width(), tmpClockBounds2.width());

                    // Subtle architectural accent bar flanking the stacked duo
                    float barRight = right - maxRowW - 14 * k;
                    tmpCardRect.set(barRight - 2.6f * k, CLOCK_TOP * k, barRight, botRowTop + tmpClockBounds2.height());
                    clockCardFill.setColor(0x52FFFFFF);
                    c.drawRoundRect(tmpCardRect, 1.3f * k, 1.3f * k, clockCardFill);

                    cornerClock.setColor(0xF8FFFFFF);
                    c.drawText(hm[0], right - tmpClockBounds.right, topRowY, cornerClock);
                    cornerClock.setColor(0xDCE0ECFA);
                    c.drawText(hm[1], right - tmpClockBounds2.right, botRowY, cornerClock);
                    effectiveClockBottom = botRowTop + tmpClockBounds2.height();
                    resetCornerClockPaint();
                } else if ("split_flap".equals(style)) {
                    resetCornerClockPaint();
                    if (monoBoldTypeface == null) monoBoldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
                    String[] hm = splitHourMinute(clock);
                    float cardW = 96 * k, cardH = 86 * k, cardGap = 10 * k, cardR = 14 * k;
                    float cardTop = (CLOCK_TOP - 2) * k, cardBottom = cardTop + cardH;
                    float cardCy = (cardTop + cardBottom) * 0.5f;
                    cornerClock.setTypeface(monoBoldTypeface);
                    cornerClock.setTextSize(54 * k);
                    cornerClock.setColor(0xF5FFFFFF);

                    for (int idx = 0; idx < 2; idx++) {
                        float cRight = (idx == 0) ? (right - cardW - cardGap) : right;
                        float cLeft = cRight - cardW;
                        tmpCardRect.set(cLeft, cardTop, cRight, cardBottom);
                        clockCardFill.setColor(0x7C0C1018);
                        c.drawRoundRect(tmpCardRect, cardR, cardR, clockCardFill);

                        // Upper-half subtle flap highlight
                        c.save();
                        c.clipRect(cLeft, cardTop, cRight, cardCy);
                        clockCardFill.setColor(0x12FFFFFF);
                        c.drawRoundRect(tmpCardRect, cardR, cardR, clockCardFill);
                        c.restore();

                        clockCardStroke.setStrokeWidth(1.2f * k);
                        clockCardStroke.setColor(0x3CFFFFFF);
                        c.drawRoundRect(tmpCardRect, cardR, cardR, clockCardStroke);

                        String part = hm[idx];
                        cornerClock.getTextBounds(part, 0, part.length(), tmpClockBounds);
                        float tx = cLeft + (cardW - tmpClockBounds.width()) * 0.5f - tmpClockBounds.left;
                        float ty = cardTop + (cardH - tmpClockBounds.height()) * 0.5f - tmpClockBounds.top;
                        c.drawText(part, tx, ty, cornerClock);

                        // Mechanical split-flap horizontal center hinge seam
                        clockCardFill.setColor(0xAA05070B);
                        c.drawRect(cLeft + 1.5f * k, cardCy - 0.8f * k, cRight - 1.5f * k, cardCy + 0.8f * k, clockCardFill);
                    }
                    effectiveClockBottom = cardBottom;
                    resetCornerClockPaint();
                } else if ("outline".equals(style)) {
                    resetCornerClockPaint();
                    cornerClock.setTypeface(Ui.displaySemibold);
                    cornerClock.setLetterSpacing(0.01f);
                    cornerClock.getTextBounds(clock, 0, clock.length(), tmpClockBounds);
                    float clockX = right - tmpClockBounds.right;
                    float clockY = CLOCK_TOP * k - tmpClockBounds.top;

                    cornerClock.setStyle(Paint.Style.FILL);
                    cornerClock.setColor(0x1CFFFFFF);
                    c.drawText(clock, clockX, clockY, cornerClock);

                    cornerClock.setStyle(Paint.Style.STROKE);
                    cornerClock.setStrokeJoin(Paint.Join.ROUND);
                    cornerClock.setStrokeWidth(2.2f * k);
                    cornerClock.setColor(0xEEFFFFFF);
                    c.drawText(clock, clockX, clockY, cornerClock);
                    resetCornerClockPaint();
                } else {
                    resetCornerClockPaint();
                    if ("minimal".equals(style)) {
                        cornerClock.setTypeface(Ui.thin);
                        cornerClock.setLetterSpacing(0.02f);
                        cornerClock.setColor(0xEBFFFFFF);
                    } else if ("modern_serif".equals(style)) {
                        if (serifBoldTypeface == null) serifBoldTypeface = Typeface.create(Typeface.SERIF, Typeface.BOLD);
                        cornerClock.setTypeface(serifBoldTypeface);
                        cornerClock.setLetterSpacing(-0.02f);
                        cornerClock.setColor(0xF5FFFDF9);
                    } else if ("digital_mono".equals(style)) {
                        if (monoBoldTypeface == null) monoBoldTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
                        cornerClock.setTypeface(monoBoldTypeface);
                        cornerClock.setTextSize(94 * k);
                        cornerClock.setLetterSpacing(0.03f);
                        cornerClock.setColor(0xF2F8FAFC);
                    } else {
                        cornerClock.setTypeface(Ui.displayMedium);
                    }
                    cornerClock.getTextBounds(clock, 0, clock.length(), tmpClockBounds);
                    c.drawText(clock, right - tmpClockBounds.right, CLOCK_TOP * k - tmpClockBounds.top, cornerClock);
                    effectiveClockBottom = CLOCK_TOP * k + tmpClockBounds.height();
                    resetCornerClockPaint();
                }
            }

            float base = effectiveClockBottom + QUOTES_GAP * k;
            if (playingCorner != null) {
                c.drawText(playingCorner, right - playingCornerWidth, base, cornerQuote);
            }
        } finally {
            if (save != -1) c.restoreToCount(save);
        }
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        if (!Prefs.saverShowLocalTime() || !"liquid_glass".equals(Prefs.saverClockStyle())) return;
        Glass g = glass;
        if (g == null || glassShader == null || current == null || current.shader == null || placeA[2] <= 0f || alpha <= 0.004f) return;
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null && incoming.shader != null && placeB[2] > 0f) {
            glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
            glassShader.setFloatUniform("mixB", e);
        } else {
            glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
            glassShader.setFloatUniform("mixB", 0f);
        }
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 7.5f * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits on its own background thread (once a minute, never queued behind 4K decoding). */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text == null || text.isEmpty() || cornerClock == null) {
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setColor(0xFFFFFFFF);
        final float right = RIGHT * k, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        glassWorker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (!running || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", linear(g.slope));
                glassShader.setInputShader("cover", linear(g.cover));
                lastFrame = 0;
                invalidate();
            });
        });
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = Math.max(1, ink.width() + 2 * pad), h = Math.max(1, ink.height() + 2 * pad);
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int rBevel = Math.max(1, Math.round(1.8f * k));
        int[] height = blur(alpha, w, h, rBevel, 2);
        int[] soft = blur(alpha, w, h, Math.max(2, Math.round(8 * k)), 2);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                float glyphA = (px[i] >>> 24) / 255f;
                int shA = Math.round(Math.min(255f, soft[i] / 16f) * (1f - glyphA * 0.92f));
                shadow[i] = shA << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Direct-Canvas Photo Caption (bottom left, zero child RenderNode detachment)

    private static String resolveLiveTitle(Recs.Pick pick) {
        return "";
    }

    private static String resolveLiveMeta(Recs.Pick pick) {
        if (pick == null) return "";
        boolean hasRaw = (pick.rawTitle != null && !pick.rawTitle.isEmpty())
                || (pick.rawArtist != null && !pick.rawArtist.isEmpty())
                || (pick.rawLocation != null && !pick.rawLocation.isEmpty())
                || (pick.rawBuilding != null && !pick.rawBuilding.isEmpty())
                || (pick.category != null && !pick.category.isEmpty());
        if (hasRaw) {
            String rawTitle = (pick.rawTitle != null && !pick.rawTitle.isEmpty()) ? pick.rawTitle : pick.title;
            String bld = (pick.rawBuilding != null && !pick.rawBuilding.isEmpty()) ? pick.rawBuilding : pick.building;
            return ScreensaverCatalog.buildCaptionMeta(
                    pick.category, rawTitle, pick.rawArtist, pick.rawLocation, bld,
                    Prefs.saverShowArtist(), Prefs.saverShowLocation());
        }
        return pick.meta != null ? pick.meta : "";
    }

    private static boolean hasLiveCaption(Recs.Pick pick) {
        if (pick == null) return false;
        if (pick.photo == null) return true;
        return !resolveLiveTitle(pick).isEmpty() || !resolveLiveMeta(pick).isEmpty();
    }

    private void drawSlideCaptionDirect(Canvas c, Slide s, float alpha, float transY) {
        if (s == null || alpha <= 0.004f || titlePaint == null) return;
        String title = resolveLiveTitle(s.pick);
        String meta = resolveLiveMeta(s.pick);
        if (title.isEmpty() && meta.isEmpty()) return;

        int save = alpha < 0.996f
                ? c.saveLayerAlpha(0, Math.max(0, h - 380 * k), Math.min(w, (MARGIN + TITLE_W + 80) * k), h, Math.round(255 * alpha))
                : c.save();
        try {
            if (transY != 0f) c.translate(0, transY);
            float x = MARGIN * k;
            float metaBase = META_BASE * k;
            float titleBase = meta.isEmpty() ? metaBase : (metaBase - 58 * k);
            if (!title.isEmpty()) {
                StaticLayout t = s.getOrBuildTitleLayout(title, titlePaint, Math.max(1, Math.round(TITLE_W * k)));
                if (t != null) {
                    float originY = titleBase - t.getLineBaseline(t.getLineCount() - 1);
                    c.save();
                    c.translate(x - Ui.leftBearing(titlePaint, title), originY);
                    t.draw(c);
                    c.restore();
                }
            }
            if (!meta.isEmpty()) {
                c.drawText(meta, x, metaBase, metaPaint);
            }
        } finally {
            c.restoreToCount(save);
        }
    }

    private Recs.Pick watchable() {
        Slide s = incoming != null && SystemClock.uptimeMillis() - incoming.startedAt > 1300 ? incoming : current;
        return s == null || s.pick.photo != null || s.pick.pkg == null ? null : s.pick;
    }

    boolean watch() {
        Recs.Pick p = watchable();
        if (p == null) return false;
        boolean opened = HomeApps.open(getContext(), HomeApps.app(p.pkg, Recs.serviceName(p), p.link), null);
        if (!opened) AppLog.w("screensaver: couldn't open “" + p.title + "” in " + p.pkg);
        return opened;
    }

    private void drawCaptionsDirect(Canvas c, long up) {
        if (incoming == null) {
            float a = current.startedAt == firstAt ? fadeIn(up - firstAt - 200, 600) : 1f;
            drawSlideCaptionDirect(c, current, a, 12 * k * (1 - a));
            return;
        }
        long t = up - incoming.startedAt;
        if (manualTransition) {
            float progress = fadeIn(t, MANUAL_FADE_MS);
            float out = 1f - progress;
            if (out > 0.004f) {
                drawSlideCaptionDirect(c, current, out, 0f);
            }
            if (progress > 0.004f) {
                drawSlideCaptionDirect(c, incoming, progress, 10 * k * (1f - progress));
            }
            return;
        }
        float out = 1 - Ui.clamp(t / 500f);
        if (out > 0.004f) {
            drawSlideCaptionDirect(c, current, out, 0f);
        }
        float in = fadeIn(t - 1300, 900);
        if (in > 0.004f) {
            drawSlideCaptionDirect(c, incoming, in, 14 * k * (1 - in));
        }
    }

    // MARK: - Without photos: the big clock over the aurora

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        if (up - lastSky >= 100) {
            lastSky = up;
            scene.advance(timeOffset + t);
        }
        scene.drawBackground(canvas);
        if (dirty) {
            Canvas rc = content.beginRecording(w, h);
            try {
                drawContent(rc);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * t / DRIFT_PERIOD_S;
        float dx = (float) (40 * k * Math.sin(phase)), dy = (float) (26 * k * Math.sin(2 * phase + 0.7));
        content.setTranslationX(dx);
        content.setTranslationY(dy);
        content.setAlpha(alpha);
        content.draw(canvas);
        if (playingBottom != null) {
            float bars = NowPlaying.barsWidth(bottomCap) + bottomCap * 0.6f;
            drawBars(canvas, w / 2f - (bars + playingBottomWidth) / 2 + dx, h - 92 * k + dy, bottomCap, quotePaint.getColor(), alpha);
        }
    }

    private void drawContent(Canvas c) {
        float cx = w / 2f;
        float clockCap = Ui.cap(clockPaint);
        float clockBase = h * 0.5f + clockCap / 2 - 10 * k;
        c.drawText(date, cx, clockBase - clockCap - 34 * k, datePaint);
        c.drawText(clock, cx, clockBase, clockPaint);

        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && Prefs.weather()) {
            float base = clockBase + 76 * k;
            String symbol = Symbols.weather(wx.code, wx.isDay);
            String now = HomeData.degrees(wx.temp);
            if (wx != rangeWeather || Ui.generation != rangeGeneration || Prefs.version != rangePrefs) {
                range = HomeData.highLow(wx, "  ");
                rangeWeather = wx;
                rangeGeneration = Ui.generation;
                rangePrefs = Prefs.version;
            }
            float sw = Symbols.width(getContext(), symbol, 32 * k);
            float width = sw + 12 * k + weatherPaint.measureText(now) + 18 * k + weatherDim.measureText(range);
            float x = cx - width / 2;
            Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(weatherPaint), 32 * k, 0xC7FFFFFF);
            x += sw + 12 * k;
            c.drawText(now, x, base, weatherPaint);
            x += weatherPaint.measureText(now) + 18 * k;
            c.drawText(range, x, base, weatherDim);
        }

        if (playingBottom != null) {
            float bars = NowPlaying.barsWidth(bottomCap) + bottomCap * 0.6f;
            c.drawText(playingBottom, cx - (bars + playingBottomWidth) / 2 + bars, h - 92 * k, quotePaint);
        }
    }
}
