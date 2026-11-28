package io.soartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Encapsulates recursive 2-column (60% Left / 40% Right, or 100% Full-Width Leaf) Settings screen rendering,
 * Screensaver Clock Style live previews, and 4K Photography Gallery previews for {@link HomeView}.
 *
 * <p><b>Why:</b> Extracts over 600 lines of Settings split-screen layout math, column scrollbar rendering,
 * shader caching, and asynchronous 4K preview decoding out of {@link HomeView} while lazily initializing
 * Android graphics primitives on first draw so headless Desktop JVM unit tests instantiate cleanly.
 *
 * <p><b>What:</b> Provides pure layout helpers ({@link #anyGrandchild}, {@link #findCheckedOrFirstIndex},
 * {@link #isLastColumnFullWidth}, {@link #computeLeftColumnWidth}, {@link #computeFirstVisibleRow}) and
 * zero-per-frame-allocation Canvas renderers ({@link #drawSettingsSplitScreen}, {@link #drawClockSettingsPreview},
 * {@link #drawScreensaverGalleryPreview}).
 */
public final class SettingsScreenRenderer {

    static final int SETTINGS_LEFT_PANEL_DARK = 0x1AFFFFFF;
    static final int SETTINGS_LEFT_PANEL_LIGHT = 0x46FFFFFF;
    static final int SETTINGS_RIGHT_PANEL_DARK = 0x13FFFFFF;
    static final int SETTINGS_RIGHT_PANEL_LIGHT = 0x34FFFFFF;

    /** Native Google TV Two-Panel ratio: 38% compact Left Navigation Rail / 62% spacious Right Active Pane. */
    static final float LEFT_RAIL_FRACTION = 0.38f;
    /** Live preview split ratio (Screensaver Clock & Galleries): 42% options / 58% large 4K preview. */
    static final float PREVIEW_LEFT_FRACTION = 0.42f;
    /**
     * Target vertical anchor fraction for the focused row when overflowing rows exist below (66% down
     * the viewport, leaving ~34% visible below the hovered row), clamping smoothly to the bottom when
     * no more hidden rows exist so empty rows are never shown.
     */
    static final float SCROLL_ANCHOR_FRACTION = 0.66f;
    /** Minimum selectable rows in a terminal leaf Settings column to automatically enable the inline search bar. */
    static final int SEARCHABLE_MIN_ITEMS = 15;
    /** Hit-test outcomes for {@link #hitTestSettingsSearchBar(float, float)}. */
    static final int SEARCH_HIT_NONE = 0;
    static final int SEARCH_HIT_BAR = 1;
    static final int SEARCH_HIT_CLEAR = 2;

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Map<String, String> TZ_HUB_SEARCH_ALIASES = buildTimezoneHubAliases();

    private static Map<String, String> buildTimezoneHubAliases() {
        Map<String, String> m = new HashMap<>(80);
        m.put("HNL", "hawaii usa united states america");
        m.put("ANC", "alaska usa united states america");
        m.put("YVR", "canada british columbia");
        m.put("LAX", "california usa united states america");
        m.put("PHX", "arizona usa united states america");
        m.put("DEN", "colorado usa united states america");
        m.put("MEX", "mexico");
        m.put("CHI", "illinois usa united states america");
        m.put("BOG", "colombia");
        m.put("LIM", "peru");
        m.put("YYZ", "canada ontario");
        m.put("NYC", "new york usa united states america east coast");
        m.put("CCS", "venezuela");
        m.put("SCL", "chile");
        m.put("YHZ", "canada nova scotia");
        m.put("YYT", "canada newfoundland");
        m.put("BUE", "argentina");
        m.put("SAO", "brazil brasil");
        m.put("REK", "iceland");
        m.put("UTC", "gmt greenwich universal coordinated");
        m.put("LIS", "portugal");
        m.put("DUB", "ireland");
        m.put("LON", "uk united kingdom england britain");
        m.put("MAD", "spain espana");
        m.put("PAR", "france");
        m.put("AMS", "netherlands holland dutch");
        m.put("ZRH", "switzerland swiss");
        m.put("BER", "germany deutschland");
        m.put("ROM", "italy italia");
        m.put("STO", "sweden");
        m.put("VIE", "austria");
        m.put("PRG", "czech republic czechia");
        m.put("WAW", "poland");
        m.put("ATH", "greece");
        m.put("BUH", "romania");
        m.put("CAI", "egypt");
        m.put("JNB", "south africa");
        m.put("TLV", "israel tel aviv jerusalem");
        m.put("IST", "turkey turkiye");
        m.put("MOW", "russia");
        m.put("RUH", "saudi arabia");
        m.put("NBO", "kenya");
        m.put("THR", "iran");
        m.put("DXB", "uae united arab emirates");
        m.put("KBL", "afghanistan");
        m.put("KHI", "pakistan");
        m.put("BOM", "india mumbai kolkata delhi");
        m.put("KTM", "nepal");
        m.put("DAC", "bangladesh");
        m.put("BKK", "thailand");
        m.put("JKT", "indonesia");
        m.put("SIN", "singapore");
        m.put("HKG", "hong kong china");
        m.put("TPE", "taiwan");
        m.put("MNL", "philippines");
        m.put("PER", "australia");
        m.put("SEL", "south korea");
        m.put("TYO", "japan");
        m.put("ADL", "australia");
        m.put("SYD", "australia");
        m.put("AKL", "new zealand");
        return Collections.unmodifiableMap(m);
    }

    private static final int STATUS_POSITIVE_DARK = 0xFF34D399;
    private static final int STATUS_POSITIVE_LIGHT = 0xFF059669;
    private static final int STATUS_WARNING_DARK = 0xFFFBBF24;
    private static final int STATUS_WARNING_LIGHT = 0xFFD97706;

    // Lazily initialized graphics scratch & shader caches (never allocated in <clinit> or constructor)
    private RectF tmpSettingsRowBox;
    private RectF tmpToggleRect;
    private RectF tmpPreviewBox;
    private RectF tmpClockCardRect;
    private RectF tmpSearchBarRect;
    private RectF tmpSearchClearRect;
    private Path tmpPreviewClip;

    private LinearGradient clockPreviewBgGrad;
    private float clockPreviewBgL, clockPreviewBgT, clockPreviewBgR, clockPreviewBgB;
    private LinearGradient clockPreviewDigitGrad;
    private float clockPreviewDigitX, clockPreviewDigitY;

    private LinearGradient gallerySceneGrad;
    private String gallerySceneGradCat;
    private float gallerySceneGradL, gallerySceneGradT, gallerySceneGradR, gallerySceneGradB;
    private LinearGradient galleryScrimGrad;
    private float galleryScrimGradL, galleryScrimGradT, galleryScrimGradB;

    private final Map<String, Bitmap> galleryPreviewBitmaps = new ConcurrentHashMap<>();
    private final Set<String> galleryPreviewLoading = Collections.synchronizedSet(new HashSet<>());

    /**
     * Creates a new {@link SettingsScreenRenderer} safe for headless Desktop JVM instantiation.
     */
    public SettingsScreenRenderer() {
    }

    private void ensureScratchInitialized() {
        if (tmpSettingsRowBox == null) {
            tmpSettingsRowBox = new RectF();
            tmpToggleRect = new RectF();
            tmpPreviewBox = new RectF();
            tmpClockCardRect = new RectF();
            tmpSearchBarRect = new RectF();
            tmpSearchClearRect = new RectF();
            tmpPreviewClip = new Path();
        }
    }

    /**
     * Safely schedules recycling for any cached 4K gallery preview bitmaps when the host view is destroyed.
     */
    public void recycleBitmaps() {
        if (galleryPreviewBitmaps.isEmpty()) return;
        for (Bitmap gb : galleryPreviewBitmaps.values()) {
            Backdrop.scheduleSafeBitmapRecycle(gb);
        }
        galleryPreviewBitmaps.clear();
    }

    /**
     * Checks whether any direct selectable child of {@code node} itself has children (grandchildren).
     */
    public static boolean anyGrandchild(SettingsTree.Node node) {
        if (node == null || !node.hasChildren()) return false;
        for (SettingsTree.Node child : node.children) {
            if (child != null && !child.isSectionHeader() && child.hasChildren()) return true;
        }
        return false;
    }

    /**
     * Returns whether {@code node} is a Screensaver live-preview column ({@code ss_clock} or {@code ss_category}).
     */
    public static boolean isPreviewColumn(SettingsTree.Node node) {
        return node != null && ("ss_clock".equals(node.id) || "ss_category".equals(node.id));
    }

    /**
     * Returns the first non-header selectable row index in {@code items}, or {@code 0} if empty.
     */
    public static int firstSelectableIndex(List<SettingsTree.Node> items) {
        if (items == null || items.isEmpty()) return 0;
        for (int i = 0; i < items.size(); i++) {
            SettingsTree.Node n = items.get(i);
            if (n != null && !n.isSectionHeader()) return i;
        }
        return 0;
    }

    /**
     * Moves focus vertically by {@code dy} ({@code -1} or {@code +1}), automatically skipping
     * {@link SettingsTree.RowType#SECTION_HEADER} rows. Returns {@code -1} when pressing Up from
     * the first selectable item so the host can promote focus cleanly to the Top-Bar.
     */
    public static int nextSelectableIndex(List<SettingsTree.Node> items, int currentIdx, int dy) {
        if (items == null || items.isEmpty()) return 0;
        int clamped = Math.max(0, Math.min(items.size() - 1, currentIdx));
        if (dy < 0) {
            for (int i = clamped - 1; i >= 0; i--) {
                SettingsTree.Node n = items.get(i);
                if (n != null && !n.isSectionHeader()) return i;
            }
            return -1;
        }
        if (dy > 0) {
            for (int i = clamped + 1; i < items.size(); i++) {
                SettingsTree.Node n = items.get(i);
                if (n != null && !n.isSectionHeader()) return i;
            }
            return clamped;
        }
        SettingsTree.Node cur = items.get(clamped);
        return (cur != null && !cur.isSectionHeader()) ? clamped : firstSelectableIndex(items);
    }

    /**
     * Finds the first selectable radio-choice child node ({@link SettingsTree.RowType#CHECK}) marked {@code checked},
     * or {@link #firstSelectableIndex(List)}. Category/group/toggle/action rows that use {@code checked} as a status
     * indicator do not steal initial focus away from row 0 when opening a category pane.
     */
    public static int findCheckedOrFirstIndex(List<SettingsTree.Node> children) {
        if (children == null || children.isEmpty()) return 0;
        for (int i = 0; i < children.size(); i++) {
            SettingsTree.Node n = children.get(i);
            if (n != null && !n.isSectionHeader() && n.checked && n.rowType == SettingsTree.RowType.CHECK) return i;
        }
        return firstSelectableIndex(children);
    }

    /**
     * Determines whether the active Settings column is a terminal leaf column that should expand to 100%
     * full width (excluding {@code ss_clock} and {@code ss_category}, which preserve the 42%/58% live preview split).
     */
    public static boolean isLastColumnFullWidth(List<String> pathIds, SettingsTree.Node leftParent) {
        return pathIds != null && !pathIds.isEmpty() && !anyGrandchild(leftParent) && !isPreviewColumn(leftParent);
    }

    /**
     * Computes the pixel width of the Left Settings column (100% when {@code isLastColumn}, otherwise 38%
     * Native Two-Panel Rail of {@code totalW - colGap}).
     */
    public static float computeLeftColumnWidth(float totalW, float colGap, boolean isLastColumn) {
        return isLastColumn ? totalW : (totalW - colGap) * LEFT_RAIL_FRACTION;
    }

    /**
     * Computes the pixel width of the Left options column for live-preview columns ({@code ss_clock},
     * {@code ss_category}), reserving 58% of the screen width for the large 4K live preview on the right.
     */
    public static float computePreviewLeftWidth(float totalW, float colGap) {
        return (totalW - colGap) * PREVIEW_LEFT_FRACTION;
    }

    /**
     * Computes the first visible row index so {@code selectedIdx} stays smoothly within the visible viewport.
     */
    public static int computeFirstVisibleRow(int totalItems, int visibleRows, int selectedIdx) {
        if (totalItems <= 0 || visibleRows <= 0 || selectedIdx < visibleRows) return 0;
        return Math.min(Math.max(0, totalItems - visibleRows), selectedIdx - visibleRows + 1);
    }

    /**
     * Computes the vertical pixel scroll offset for a Settings column list so that when overflowing
     * rows exist below, the focused row stays anchored around {@link #SCROLL_ANCHOR_FRACTION} (66%
     * down the viewport) — exposing more upcoming rows underneath — while clamping smoothly to
     * {@code totalContentH - viewportH} at the bottom (never showing empty trailing rows) and to
     * {@code 0} at the top.
     */
    public static float computeColumnScrollY(
            float totalContentH,
            float viewportH,
            float selTop,
            float selBottom) {
        if (totalContentH <= viewportH || viewportH <= 0f) return 0f;
        float maxScroll = totalContentH - viewportH;
        float selCenter = (selTop + selBottom) * 0.5f;
        float desired = selCenter - viewportH * SCROLL_ANCHOR_FRACTION;
        float scrollY = Math.max(0f, Math.min(maxScroll, desired));
        if (selBottom - scrollY > viewportH) {
            scrollY = Math.min(maxScroll, selBottom - viewportH);
        }
        if (selTop - scrollY < 0f) {
            scrollY = Math.max(0f, selTop);
        }
        return Math.max(0f, Math.min(maxScroll, scrollY));
    }

    /**
     * Returns whether a full-width terminal leaf Settings column should display an inline Search Bar
     * (Second Clock Timezone, TV Shows &amp; Movies Genres, SoarTV Display Language, Controller Color
     * Swatches, or any leaf column with at least {@link #SEARCHABLE_MIN_ITEMS} selectable rows).
     */
    public static boolean isSearchableColumn(SettingsTree.Node node) {
        if (node == null || !node.hasChildren() || anyGrandchild(node) || isPreviewColumn(node)) {
            return false;
        }
        if ("ss_tz".equals(node.id)
                || "hg_tv_genres".equals(node.id)
                || "hg_movie_genres".equals(node.id)
                || "sys_soartv_lang".equals(node.id)
                || "gaming_colors_rainbow".equals(node.id)) {
            return true;
        }
        int selectable = 0;
        for (SettingsTree.Node child : node.children) {
            if (child != null && !child.isSectionHeader()) {
                selectable++;
                if (selectable >= SEARCHABLE_MIN_ITEMS) return true;
            }
        }
        return false;
    }

    /**
     * Returns a tailored placeholder hint for the inline Search Bar of a searchable Settings column.
     */
    public static String searchPlaceholderForColumn(SettingsTree.Node node) {
        if (node == null) return "Search options…";
        if ("ss_tz".equals(node.id)) return "Search city, country, timezone, or UTC offset…";
        if ("hg_tv_genres".equals(node.id)) return "Search TV Show genres…";
        if ("hg_movie_genres".equals(node.id)) return "Search Movie genres…";
        if ("sys_soartv_lang".equals(node.id)) return "Search language (e.g. English, Deutsch, Japanese)…";
        if ("gaming_colors_rainbow".equals(node.id)) return "Search color (e.g. Blue, Emerald, Pink)…";
        return "Search options…";
    }

    /**
     * Normalizes text for accent- and case-insensitive Settings search (e.g. {@code "São Paulo"} -> {@code "sao paulo"}).
     */
    public static String normalizeSearchText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD);
        String stripped = DIACRITICS_PATTERN.matcher(decomposed).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Filters and ranks child nodes of a searchable Settings column against {@code rawQuery}.
     * Supports multi-token queries, diacritic folding, IANA timezone/airport-code/country matching
     * for {@code ss_tz}, and English language-name matching for {@code sys_soartv_lang}.
     */
    public static List<SettingsTree.Node> filterNodesForSearch(
            SettingsTree.Node parent,
            List<SettingsTree.Node> items,
            String rawQuery) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        String normQ = normalizeSearchText(rawQuery);
        if (normQ.isEmpty()) return items;

        String[] tokens = normQ.split("\\s+");
        List<SettingsTree.Node> tier4 = new ArrayList<>();
        List<SettingsTree.Node> tier3 = new ArrayList<>();
        List<SettingsTree.Node> tier2 = new ArrayList<>();
        List<SettingsTree.Node> tier1 = new ArrayList<>();
        List<SettingsTree.Node> tier0 = new ArrayList<>();

        for (SettingsTree.Node n : items) {
            if (n == null || n.isSectionHeader()) continue;
            String titleNorm = normalizeSearchText(n.title);
            StringBuilder hay = new StringBuilder(96);
            hay.append(titleNorm);
            if (!n.subtitle.isEmpty()) hay.append(' ').append(normalizeSearchText(n.subtitle));
            if (!n.value.isEmpty()) hay.append(' ').append(normalizeSearchText(n.value));
            appendDomainSearchMetadata(n, hay);

            String haystack = hay.toString();
            boolean allMatched = true;
            for (String tok : tokens) {
                if (!tok.isEmpty() && !haystack.contains(tok)) {
                    allMatched = false;
                    break;
                }
            }
            if (!allMatched) continue;

            if (titleNorm.equals(normQ)) {
                tier4.add(n);
            } else if (titleNorm.startsWith(normQ)) {
                tier3.add(n);
            } else if (containsWordStartingWith(titleNorm, normQ)) {
                tier2.add(n);
            } else if (titleNorm.contains(normQ)) {
                tier1.add(n);
            } else {
                tier0.add(n);
            }
        }

        List<SettingsTree.Node> out = new ArrayList<>(
                tier4.size() + tier3.size() + tier2.size() + tier1.size() + tier0.size());
        out.addAll(tier4);
        out.addAll(tier3);
        out.addAll(tier2);
        out.addAll(tier1);
        out.addAll(tier0);
        return out;
    }

    private static boolean containsWordStartingWith(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return false;
        int len = haystack.length();
        for (int i = 0; i < len; i++) {
            char ch = haystack.charAt(i);
            boolean boundary = (i == 0) || !Character.isLetterOrDigit(haystack.charAt(i - 1));
            if (boundary && Character.isLetterOrDigit(ch) && haystack.startsWith(needle, i)) {
                return true;
            }
        }
        return false;
    }

    private static void appendDomainSearchMetadata(SettingsTree.Node node, StringBuilder out) {
        if (node == null || node.id == null) return;
        if (node.id.startsWith("ss_tz:")) {
            String[] parts = node.id.substring("ss_tz:".length()).split(":", 2);
            if (parts.length >= 1 && !parts[0].isEmpty()) {
                out.append(' ').append(normalizeSearchText(parts[0].replace('/', ' ').replace('_', ' ')));
            }
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                String code = parts[1].toUpperCase(Locale.ROOT);
                out.append(' ').append(code.toLowerCase(Locale.ROOT));
                String aliases = TZ_HUB_SEARCH_ALIASES.get(code);
                if (aliases != null) {
                    out.append(' ').append(aliases);
                }
            }
        } else if (node.id.startsWith("sys_soartv_lang:")) {
            String code = node.id.substring("sys_soartv_lang:".length());
            if (!code.isEmpty() && !"auto".equalsIgnoreCase(code)) {
                out.append(' ').append(normalizeSearchText(code));
                try {
                    Locale loc = Locale.forLanguageTag(code);
                    String engName = loc.getDisplayLanguage(Locale.ENGLISH);
                    if (engName != null && !engName.isEmpty()) {
                        out.append(' ').append(normalizeSearchText(engName));
                    }
                    String engCountry = loc.getDisplayCountry(Locale.ENGLISH);
                    if (engCountry != null && !engCountry.isEmpty()) {
                        out.append(' ').append(normalizeSearchText(engCountry));
                    }
                } catch (Throwable ignored) {
                }
                if ("zh-CN".equalsIgnoreCase(code)) out.append(" chinese simplified mandarin");
                else if ("zh-TW".equalsIgnoreCase(code)) out.append(" chinese traditional taiwan");
            }
        }
    }

    /**
     * Hit-tests pointer coordinates against the inline Settings Search Bar and its Clear pill.
     */
    int hitTestSettingsSearchBar(float x, float y) {
        if (tmpSearchClearRect != null && !tmpSearchClearRect.isEmpty() && tmpSearchClearRect.contains(x, y)) {
            return SEARCH_HIT_CLEAR;
        }
        if (tmpSearchBarRect != null && !tmpSearchBarRect.isEmpty() && tmpSearchBarRect.contains(x, y)) {
            return SEARCH_HIT_BAR;
        }
        return SEARCH_HIT_NONE;
    }

    /**
     * Renders the complete Native Two-Panel (38% Compact Left Rail / 62% Spacious Right Pane, 42%/58%
     * Live Preview Split, or 100% Full-Width Leaf) Settings screen with Liquid Glass controls.
     */
    void drawSettingsSplitScreen(
            Context ctx,
            Canvas c,
            SettingsTree.Node settingsRoot,
            SettingsTree.Node leftParent,
            List<String> settingsPathIds,
            int leftIdx,
            boolean settingsFocusRight,
            int settingsRightIndex,
            boolean cardsZoneFocused,
            boolean reorderingGenre,
            float k,
            float marginVirtual,
            String clockText,
            String dateText,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            float shadowBlurVirtual,
            boolean isDark,
            Paint fill,
            Paint border,
            Paint shadow,
            Paint imagePaint,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            Shadows shadows,
            RectF tmp,
            Executor loaderExecutor,
            Runnable onPreviewDecodedOnUi) {
        drawSettingsSplitScreen(
                ctx, c, settingsRoot, leftParent, null, settingsPathIds, leftIdx,
                settingsFocusRight, settingsRightIndex, cardsZoneFocused, reorderingGenre,
                "", false, false,
                k, marginVirtual, clockText, dateText, lInkColor, lDimColor, whiteColor,
                shadowBlurVirtual, isDark, fill, border, shadow, imagePaint,
                lCardTitle, lSubTab, lSubTabOn, fitPaint, shadows, tmp,
                loaderExecutor, onPreviewDecodedOnUi);
    }

    /**
     * Full overload supporting cached filtered node lists and inline Search Bar chrome for long leaf columns.
     */
    void drawSettingsSplitScreen(
            Context ctx,
            Canvas c,
            SettingsTree.Node settingsRoot,
            SettingsTree.Node leftParent,
            List<SettingsTree.Node> displayLeftList,
            List<String> settingsPathIds,
            int leftIdx,
            boolean settingsFocusRight,
            int settingsRightIndex,
            boolean cardsZoneFocused,
            boolean reorderingGenre,
            String searchQuery,
            boolean searchBarFocused,
            boolean searchClearFocused,
            float k,
            float marginVirtual,
            String clockText,
            String dateText,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            float shadowBlurVirtual,
            boolean isDark,
            Paint fill,
            Paint border,
            Paint shadow,
            Paint imagePaint,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            Shadows shadows,
            RectF tmp,
            Executor loaderExecutor,
            Runnable onPreviewDecodedOnUi) {
        ensureScratchInitialized();
        tmpSearchBarRect.setEmpty();
        tmpSearchClearRect.setEmpty();

        List<SettingsTree.Node> leftList = displayLeftList != null
                ? displayLeftList
                : (leftParent != null ? leftParent.children : Collections.emptyList());
        int clampedLeftIdx = leftList.isEmpty() ? 0 : nextSelectableIndex(leftList, leftIdx, 0);
        SettingsTree.Node hoveredLeft = (leftList.isEmpty() || clampedLeftIdx < 0 || clampedLeftIdx >= leftList.size())
                ? null : leftList.get(clampedLeftIdx);

        boolean isLastColumn = isLastColumnFullWidth(settingsPathIds, leftParent);
        boolean isPreviewSplit = !isLastColumn && isPreviewColumn(leftParent);
        boolean showSearchBar = isLastColumn && isSearchableColumn(leftParent) && !reorderingGenre;
        String safeQuery = searchQuery == null ? "" : searchQuery;
        boolean hasQuery = !safeQuery.trim().isEmpty();

        float topY = 122 * k;
        float footerH = 42 * k;
        float panelBottomY = 1042 * k - footerH;
        float totalW = (1920 - 2 * marginVirtual) * k;
        float colGap = 24 * k;
        float leftColW = isPreviewSplit
                ? computePreviewLeftWidth(totalW, colGap)
                : computeLeftColumnWidth(totalW, colGap, isLastColumn);
        float rightColW = totalW - colGap - leftColW;
        float leftColX = marginVirtual * k;
        float rightColX = leftColX + leftColW + colGap;
        float panelR = 26 * k;

        boolean leftPanelFocused = cardsZoneFocused && (!settingsFocusRight || isLastColumn || isPreviewSplit);
        boolean rightPanelFocused = cardsZoneFocused && settingsFocusRight && !isLastColumn && !isPreviewSplit;

        // 1. Left Column glass panel (38% Rail, 42% Preview Options, or 100% Full-Width Leaf)
        tmp.set(leftColX, topY, leftColX + leftColW, panelBottomY);
        fill.setShader(null);
        fill.setColor(isDark
                ? (leftPanelFocused ? 0x1EFFFFFF : SETTINGS_LEFT_PANEL_DARK)
                : (leftPanelFocused ? 0x52FFFFFF : SETTINGS_LEFT_PANEL_LIGHT));
        c.drawRoundRect(tmp, panelR, panelR, fill);
        border.setColor(leftPanelFocused
                ? (isDark ? 0x3CFFFFFF : 0x2C000000)
                : (isDark ? 0x1EFFFFFF : 0x16000000));
        c.drawRoundRect(tmp, panelR, panelR, border);

        // 2. Right Column glass panel (62% Active Workspace or 58% Live Preview)
        if (!isLastColumn) {
            tmp.set(rightColX, topY, rightColX + rightColW, panelBottomY);
            fill.setColor(isDark
                    ? (rightPanelFocused ? 0x1EFFFFFF : SETTINGS_RIGHT_PANEL_DARK)
                    : (rightPanelFocused ? 0x52FFFFFF : SETTINGS_RIGHT_PANEL_LIGHT));
            c.drawRoundRect(tmp, panelR, panelR, fill);
            border.setColor(rightPanelFocused
                    ? (isDark ? 0x3CFFFFFF : 0x2C000000)
                    : (isDark ? 0x1BFFFFFF : 0x14000000));
            c.drawRoundRect(tmp, panelR, panelR, border);
        }

        // Left Column Breadcrumb Header
        StringBuilder crumb = new StringBuilder(Ui.tr("Settings"));
        SettingsTree.Node walker = settingsRoot;
        if (walker != null && settingsPathIds != null) {
            for (String id : settingsPathIds) {
                for (SettingsTree.Node ch : walker.children) {
                    if (id.equals(ch.id)) {
                        crumb.append("  /  ").append(Ui.tr(ch.title));
                        walker = ch;
                        break;
                    }
                }
            }
        }
        int primaryInk = isDark ? whiteColor : lInkColor;
        int invPrimaryInk = isDark ? lInkColor : whiteColor;
        int dimInk = isDark ? 0xFFD1D1D6 : lDimColor;
        float headerY = topY + 44 * k;
        float headerTextX = leftColX + 28 * k;
        if (settingsPathIds != null && !settingsPathIds.isEmpty()) {
            Symbols.draw(ctx, c, "chevron_left", leftColX + 34 * k, headerY - 8 * k, 19 * k, primaryInk);
            headerTextX = leftColX + 54 * k;
        }
        float headerRightReserve = 0f;
        if (showSearchBar && leftParent != null && leftParent.value != null && !leftParent.value.isEmpty()) {
            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize(17.5f * k);
            String activeVal = Ui.tr(leftParent.value);
            float badgeW = Math.min(300 * k, fitPaint.measureText(activeVal) + 28 * k);
            float badgeH = 34 * k;
            float badgeR = leftColX + leftColW - 24 * k;
            float badgeL = badgeR - badgeW;
            float badgeT = topY + 18 * k;
            tmp.set(badgeL, badgeT, badgeR, badgeT + badgeH);
            fill.setShader(null);
            fill.setColor(isDark ? 0x1EFFFFFF : 0x14000000);
            c.drawRoundRect(tmp, badgeH / 2f, badgeH / 2f, fill);
            fitPaint.setColor(dimInk);
            String clippedBadge = TextUtils.ellipsize(activeVal, fitPaint, badgeW - 22 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(clippedBadge, tmp.centerX() - fitPaint.measureText(clippedBadge) / 2f,
                    tmp.centerY() + Ui.halfCap(fitPaint), fitPaint);
            headerRightReserve = badgeW + 20 * k;
        }
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(24.5f * k);
        fitPaint.setColor(primaryInk);
        String crumbLine = TextUtils.ellipsize(crumb.toString(), fitPaint,
                Math.max(120 * k, leftColW - 72 * k - headerRightReserve), TextUtils.TruncateAt.START).toString();
        c.drawText(crumbLine, headerTextX, headerY, fitPaint);

        // Subtle divider under headers
        fill.setColor(isDark ? 0x1AFFFFFF : 0x14000000);
        c.drawRect(leftColX + 22 * k, topY + 62 * k, leftColX + leftColW - 22 * k, topY + 63.5f * k, fill);
        if (!isLastColumn) {
            c.drawRect(rightColX + 22 * k, topY + 62 * k, rightColX + rightColW - 22 * k, topY + 63.5f * k, fill);
        }

        // Compact Left Rail when at Two-Panel mode (all 10 root categories fit with zero scrolling!)
        boolean useCompactRail = !isLastColumn && !isPreviewSplit;
        float leftListTopY = topY + 74 * k;
        if (reorderingGenre) {
            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize(19 * k);
            fitPaint.setColor(isDark ? 0xFFE5E5EA : 0xFF48484A);
            String hintStr = TextUtils.ellipsize(
                    Ui.tr("Moving Genre (Up / Down) \u00B7 Press OK to save \u00B7 Back to cancel"),
                    fitPaint, leftColW - 56 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(hintStr, leftColX + 28 * k, topY + 90 * k, fitPaint);
            leftListTopY = topY + 108 * k;
        } else if (showSearchBar) {
            float barH = 58 * k;
            float barL = leftColX + 16 * k;
            float barR = leftColX + leftColW - 16 * k;
            float barT = topY + 74 * k;
            float barB = barT + barH;
            float barRad = 18 * k;

            float clearReserve = 0f;
            if (hasQuery) {
                float clearW = 122 * k;
                float clearGap = 12 * k;
                tmpSearchClearRect.set(barR - clearW, barT, barR, barB);
                clearReserve = clearW + clearGap;
            }
            tmpSearchBarRect.set(barL, barT, barR - clearReserve, barB);

            boolean inputBoxActive = leftPanelFocused && searchBarFocused && !searchClearFocused;
            fill.setShader(null);
            if (inputBoxActive) {
                tmp.set(tmpSearchBarRect);
                tmp.offset(0, 4 * k);
                shadow.setColor(Ui.alpha(0xFF000000, isDark ? 0.34f : 0.15f));
                shadows.draw(c, tmp, barRad, shadowBlurVirtual * k, shadow);
                fill.setColor(primaryInk);
                c.drawRoundRect(tmpSearchBarRect, barRad, barRad, fill);
            } else {
                fill.setColor(isDark ? 0x1AFFFFFF : 0x14000000);
                c.drawRoundRect(tmpSearchBarRect, barRad, barRad, fill);
                border.setColor(isDark ? 0x34FFFFFF : 0x24000000);
                c.drawRoundRect(tmpSearchBarRect, barRad, barRad, border);
            }

            int barIconInk = inputBoxActive ? invPrimaryInk : (hasQuery ? primaryInk : dimInk);
            int barTextInk = inputBoxActive ? invPrimaryInk : primaryInk;
            int barHintInk = inputBoxActive ? (isDark ? 0xFF48484A : 0xCCFFFFFF) : dimInk;

            Symbols.draw(ctx, c, "search", tmpSearchBarRect.left + 30 * k, tmpSearchBarRect.centerY(), 22 * k, barIconInk);

            float textRightBound = tmpSearchBarRect.right - 20 * k;
            if (hasQuery) {
                int count = leftList.size();
                String countLabel = (count == 1) ? "1 match" : (count + " matches");
                fitPaint.set(lSubTabOn);
                fitPaint.setTextSize(16.5f * k);
                float cw = fitPaint.measureText(countLabel);
                fitPaint.setColor(barHintInk);
                c.drawText(countLabel, textRightBound - cw, tmpSearchBarRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
                textRightBound -= (cw + 18 * k);
            }

            float textStartX = tmpSearchBarRect.left + 58 * k;
            float availSearchW = Math.max(80 * k, textRightBound - textStartX);
            String placeholder = Ui.tr(searchPlaceholderForColumn(leftParent));
            String displayText = hasQuery ? safeQuery : placeholder;
            fitPaint.set(hasQuery ? lSubTabOn : lSubTab);
            fitPaint.setTextSize(21.5f * k);
            fitPaint.setColor(hasQuery ? barTextInk : barHintInk);
            String clippedSearch = TextUtils.ellipsize(
                    displayText, fitPaint, availSearchW,
                    hasQuery ? TextUtils.TruncateAt.START : TextUtils.TruncateAt.END).toString();
            c.drawText(clippedSearch, textStartX, tmpSearchBarRect.centerY() + Ui.halfCap(fitPaint), fitPaint);

            if (hasQuery) {
                boolean clearActive = leftPanelFocused && searchBarFocused && searchClearFocused;
                fill.setShader(null);
                if (clearActive) {
                    tmp.set(tmpSearchClearRect);
                    tmp.offset(0, 4 * k);
                    shadow.setColor(Ui.alpha(0xFF000000, isDark ? 0.34f : 0.15f));
                    shadows.draw(c, tmp, barRad, shadowBlurVirtual * k, shadow);
                    fill.setColor(primaryInk);
                    c.drawRoundRect(tmpSearchClearRect, barRad, barRad, fill);
                } else {
                    fill.setColor(isDark ? 0x1AFFFFFF : 0x14000000);
                    c.drawRoundRect(tmpSearchClearRect, barRad, barRad, fill);
                    border.setColor(isDark ? 0x34FFFFFF : 0x24000000);
                    c.drawRoundRect(tmpSearchClearRect, barRad, barRad, border);
                }
                int clearInk = clearActive ? invPrimaryInk : primaryInk;
                Symbols.draw(ctx, c, "close", tmpSearchClearRect.left + 26 * k, tmpSearchClearRect.centerY(), 18 * k, clearInk);
                fitPaint.set(lSubTabOn);
                fitPaint.setTextSize(19 * k);
                fitPaint.setColor(clearInk);
                c.drawText(Ui.tr("Clear"), tmpSearchClearRect.left + 44 * k,
                        tmpSearchClearRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
            }

            leftListTopY = barB + 14 * k;
        }

        if (showSearchBar && hasQuery && leftList.isEmpty()) {
            float emptyCx = leftColX + leftColW / 2f;
            float emptyCy = leftListTopY + (panelBottomY - leftListTopY) * 0.42f;
            Symbols.draw(ctx, c, "search", emptyCx, emptyCy - 46 * k, 40 * k, Ui.alpha(dimInk, 0.7f));
            fitPaint.set(lCardTitle);
            fitPaint.setTextSize(25 * k);
            fitPaint.setColor(primaryInk);
            String noMatch = TextUtils.ellipsize(
                    Ui.tr("No matches for \"") + safeQuery.trim() + "\"",
                    fitPaint, leftColW - 120 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(noMatch, emptyCx - fitPaint.measureText(noMatch) / 2f, emptyCy + 10 * k, fitPaint);
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(19.5f * k);
            fitPaint.setColor(dimInk);
            String subHint = Ui.tr("Press Back or Clear to show all options");
            c.drawText(subHint, emptyCx - fitPaint.measureText(subHint) / 2f, emptyCy + 46 * k, fitPaint);
        } else {
            int listSelectedIdx = (showSearchBar && searchBarFocused) ? -1 : clampedLeftIdx;
            boolean listFocused = leftPanelFocused && !(showSearchBar && searchBarFocused);
            drawSettingsColumnList(ctx, c, leftList, listSelectedIdx, listFocused, !settingsFocusRight,
                    reorderingGenre, useCompactRail,
                    leftColX + 16 * k, leftListTopY, leftColW - 32 * k, panelBottomY - leftListTopY - 14 * k,
                    isLastColumn, k, lInkColor, lDimColor, whiteColor, shadowBlurVirtual, isDark,
                    fill, border, shadow, lSubTab, lSubTabOn, fitPaint, shadows, tmp);
        }

        // Right Column (62% Active Workspace or 58% Live Preview)
        if (!isLastColumn) {
            String rightHeader = hoveredLeft != null ? Ui.tr(hoveredLeft.title) : Ui.tr("Details");
            fitPaint.set(lCardTitle);
            fitPaint.setTextSize(24.5f * k);
            fitPaint.setColor(primaryInk);
            String rHeaderLine = TextUtils.ellipsize(rightHeader, fitPaint, rightColW - 56 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(rHeaderLine, rightColX + 28 * k, headerY, fitPaint);

            if (hoveredLeft != null && hoveredLeft.hasChildren()) {
                float rightListTopY = topY + 74 * k;
                int rIdx = hoveredLeft.children.isEmpty() ? 0
                        : nextSelectableIndex(hoveredLeft.children, settingsRightIndex, 0);
                if ("ss_clock".equals(hoveredLeft.id)) {
                    drawSettingsColumnList(ctx, c, hoveredLeft.children, rIdx, rightPanelFocused, settingsFocusRight,
                            false, false,
                            rightColX + 16 * k, rightListTopY, rightColW - 32 * k, 344 * k, true,
                            k, lInkColor, lDimColor, whiteColor, shadowBlurVirtual, isDark,
                            fill, border, shadow, lSubTab, lSubTabOn, fitPaint, shadows, tmp);
                    String previewStyle = Prefs.saverClockStyle();
                    if (settingsFocusRight && rIdx >= 0 && rIdx < hoveredLeft.children.size()) {
                        String childId = hoveredLeft.children.get(rIdx).id;
                        if (childId != null && childId.startsWith("ss_clock:")) {
                            previewStyle = childId.substring("ss_clock:".length());
                        }
                    }
                    drawClockSettingsPreview(ctx, c, previewStyle,
                            rightColX + 24 * k, rightListTopY + 356 * k, rightColW - 48 * k,
                            panelBottomY - (rightListTopY + 372 * k),
                            k, clockText, dateText, dimInk, whiteColor, fill, border, imagePaint,
                            lCardTitle, lSubTab, lSubTabOn, fitPaint, tmp,
                            loaderExecutor, onPreviewDecodedOnUi);
                } else if ("ss_category".equals(hoveredLeft.id)) {
                    drawSettingsColumnList(ctx, c, hoveredLeft.children, rIdx, rightPanelFocused, settingsFocusRight,
                            false, false,
                            rightColX + 16 * k, rightListTopY, rightColW - 32 * k, 344 * k, true,
                            k, lInkColor, lDimColor, whiteColor, shadowBlurVirtual, isDark,
                            fill, border, shadow, lSubTab, lSubTabOn, fitPaint, shadows, tmp);
                    String previewCat = "nature";
                    if (settingsFocusRight && rIdx >= 0 && rIdx < hoveredLeft.children.size()) {
                        String childId = hoveredLeft.children.get(rIdx).id;
                        if (childId != null && childId.startsWith("ss_cat:")) {
                            previewCat = childId.substring("ss_cat:".length());
                        }
                    } else {
                        Set<String> cats = Prefs.saverCategories();
                        if (!cats.isEmpty()) previewCat = cats.iterator().next();
                    }
                    drawScreensaverGalleryPreview(ctx, c, previewCat,
                            rightColX + 24 * k, rightListTopY + 356 * k, rightColW - 48 * k,
                            panelBottomY - (rightListTopY + 372 * k),
                            k, clockText, dateText, dimInk, whiteColor, fill, border, imagePaint,
                            lCardTitle, lSubTab, lSubTabOn, fitPaint, tmp,
                            loaderExecutor, onPreviewDecodedOnUi);
                } else {
                    drawSettingsColumnList(ctx, c, hoveredLeft.children, rIdx, rightPanelFocused, settingsFocusRight,
                            false, false,
                            rightColX + 16 * k, rightListTopY, rightColW - 32 * k, panelBottomY - rightListTopY - 14 * k,
                            true, k, lInkColor, lDimColor, whiteColor, shadowBlurVirtual, isDark,
                            fill, border, shadow, lSubTab, lSubTabOn, fitPaint, shadows, tmp);
                }
            } else if (hoveredLeft != null) {
                if (hoveredLeft.id.startsWith("ss_clock:")) {
                    String previewStyle = hoveredLeft.id.substring("ss_clock:".length());
                    drawClockSettingsPreview(ctx, c, previewStyle,
                            rightColX + 28 * k, topY + 86 * k, rightColW - 56 * k, panelBottomY - (topY + 116 * k),
                            k, clockText, dateText, dimInk, whiteColor, fill, border, imagePaint,
                            lCardTitle, lSubTab, lSubTabOn, fitPaint, tmp,
                            loaderExecutor, onPreviewDecodedOnUi);
                } else if (hoveredLeft.id.startsWith("ss_cat:")) {
                    String previewCat = hoveredLeft.id.substring("ss_cat:".length());
                    drawScreensaverGalleryPreview(ctx, c, previewCat,
                            rightColX + 28 * k, topY + 86 * k, rightColW - 56 * k, panelBottomY - (topY + 116 * k),
                            k, clockText, dateText, dimInk, whiteColor, fill, border, imagePaint,
                            lCardTitle, lSubTab, lSubTabOn, fitPaint, tmp,
                            loaderExecutor, onPreviewDecodedOnUi);
                } else {
                    drawSettingsDetailCard(ctx, c, hoveredLeft, rightColX + 32 * k, topY + 94 * k, rightColW - 64 * k,
                            k, primaryInk, dimInk, isDark, fill, border, lCardTitle, lSubTab, lSubTabOn, fitPaint);
                }
            }
        }

        // 3. Clean Single Contextual Footer Bar
        String footerHint = resolveContextualFooterHint(leftParent, hoveredLeft, settingsPathIds,
                settingsFocusRight, isLastColumn, isPreviewSplit, reorderingGenre,
                showSearchBar, searchBarFocused, hasQuery);
        if (!footerHint.isEmpty()) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(17.5f * k);
            fitPaint.setColor(dimInk);
            String clippedFooter = TextUtils.ellipsize(Ui.tr(footerHint), fitPaint, totalW - 32 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(clippedFooter, leftColX + 16 * k, panelBottomY + 29 * k, fitPaint);
        }
    }

    private static String resolveContextualFooterHint(
            SettingsTree.Node leftParent,
            SettingsTree.Node hoveredLeft,
            List<String> pathIds,
            boolean focusRight,
            boolean isLastColumn,
            boolean isPreviewSplit,
            boolean reorderingGenre,
            boolean showSearchBar,
            boolean searchBarFocused,
            boolean hasQuery) {
        if (reorderingGenre) {
            return "Up / Down Move Genre  \u00B7  OK Save  \u00B7  Back Cancel";
        }
        if (showSearchBar && searchBarFocused) {
            return hasQuery
                    ? "OK Edit Search  \u00B7  Right Clear  \u00B7  Down Select Result  \u00B7  Back Clear"
                    : "OK Type to Search  \u00B7  Down Browse List  \u00B7  Left / Back Go Back";
        }
        if (showSearchBar && hasQuery) {
            return "OK Select  \u00B7  Up Back to Search  \u00B7  Back Clear Search";
        }
        if (leftParent != null && leftParent.hint != null && !leftParent.hint.isEmpty() && (isLastColumn || isPreviewSplit)) {
            return leftParent.hint;
        }
        if (focusRight && hoveredLeft != null && hoveredLeft.hint != null && !hoveredLeft.hint.isEmpty()) {
            return hoveredLeft.hint;
        }
        if (isLastColumn || isPreviewSplit) {
            return "OK Select or Toggle  \u00B7  Left / Back Go Back";
        }
        if (focusRight) {
            return "OK Open or Toggle  \u00B7  Left / Back Return to Categories";
        }
        if (pathIds != null && !pathIds.isEmpty()) {
            return "Right / OK Enter Pane  \u00B7  Left / Back Previous Level";
        }
        return "Right / OK Open Category  \u00B7  Up / Down Browse";
    }

    private void drawSettingsColumnList(
            Context ctx,
            Canvas c,
            List<SettingsTree.Node> items,
            int selectedIdx,
            boolean focusedInZone,
            boolean isPrimaryColumn,
            boolean reorderingGenre,
            boolean compactRail,
            float x,
            float topY,
            float width,
            float height,
            boolean wideCol,
            float k,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            float shadowBlurVirtual,
            boolean isDark,
            Paint fill,
            Paint border,
            Paint shadow,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            Shadows shadows,
            RectF tmp) {
        if (items == null || items.isEmpty()) return;

        float rowH = (compactRail ? 53f : 78f) * k;
        float headerH = 26f * k;
        float gap = (compactRail ? 5f : 8f) * k;

        // Compute total height & vertical scroll offset keeping focused row at ~66% down while hidden rows remain below,
        // and clamping cleanly to the bottom (zero empty rows) once the end of the list is in view.
        float totalContentH = 0f;
        float selTop = 0f;
        float selBottom = selectedIdx >= 0 ? rowH : 0f;
        for (int i = 0; i < items.size(); i++) {
            SettingsTree.Node n = items.get(i);
            float hItem = (n != null && n.isSectionHeader()) ? headerH : rowH;
            float extraTop = (n != null && n.isSectionHeader() && i > 0) ? (8f * k) : 0f;
            totalContentH += extraTop;
            if (i == selectedIdx) {
                selTop = totalContentH;
                selBottom = totalContentH + hItem;
            }
            totalContentH += hItem + gap;
        }
        if (totalContentH > gap) totalContentH -= gap;

        float scrollY = (selectedIdx < 0)
                ? 0f
                : computeColumnScrollY(totalContentH, height, selTop, selBottom);

        c.save();
        c.clipRect(x - 8 * k, topY - 4 * k, x + width + 8 * k, topY + height + 4 * k);

        int primaryInk = isDark ? whiteColor : lInkColor;
        int invPrimaryInk = isDark ? lInkColor : whiteColor;
        int dimInk = isDark ? 0xFFD1D1D6 : lDimColor;

        float cursorY = topY - scrollY;
        for (int i = 0; i < items.size(); i++) {
            SettingsTree.Node node = items.get(i);
            if (node == null) continue;
            boolean isHeader = node.isSectionHeader();
            if (isHeader && i > 0) cursorY += 8f * k;
            float itemH = isHeader ? headerH : rowH;
            float ry = cursorY;
            cursorY += itemH + gap;

            if (ry + itemH < topY - 12 * k || ry > topY + height + 12 * k) {
                continue;
            }

            if (isHeader) {
                fitPaint.set(lSubTabOn);
                fitPaint.setTextSize(14f * k);
                fitPaint.setLetterSpacing(0.09f);
                fitPaint.setColor(Ui.alpha(dimInk, isDark ? 0.72f : 0.78f));
                String hdr = TextUtils.ellipsize(Ui.tr(node.title), fitPaint, width - 24 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(hdr, x + 16 * k, ry + itemH - 6 * k, fitPaint);
                fitPaint.setLetterSpacing(0f);
                continue;
            }

            tmpSettingsRowBox.set(x, ry, x + width, ry + itemH);
            RectF rowBox = tmpSettingsRowBox;
            boolean isSelected = (i == selectedIdx);
            float rr = (compactRail ? 14f : 18f) * k;

            boolean isLiftedGenreRow = isSelected && focusedInZone && reorderingGenre;
            if (isLiftedGenreRow) {
                tmp.set(rowBox);
                tmp.offset(0, 4 * k);
                shadow.setColor(Ui.alpha(0xFF000000, isDark ? 0.45f : 0.24f));
                shadows.draw(c, tmp, rr, shadowBlurVirtual * k, shadow);
                fill.setShader(null);
                fill.setColor(isDark ? 0xFF48484A : 0xFF3A3A3C);
                c.drawRoundRect(rowBox, rr, rr, fill);
            } else if (isSelected && focusedInZone) {
                tmp.set(rowBox);
                tmp.offset(0, 4 * k);
                shadow.setColor(Ui.alpha(0xFF000000, isDark ? 0.36f : 0.16f));
                shadows.draw(c, tmp, rr, shadowBlurVirtual * k, shadow);
                fill.setShader(null);
                fill.setColor(primaryInk);
                c.drawRoundRect(rowBox, rr, rr, fill);
            } else if (isSelected && !isPrimaryColumn) {
                // Anchored Left Rail indicator while user is working inside the Right Pane
                fill.setShader(null);
                fill.setColor(isDark ? 0x2AFFFFFF : 0x20000000);
                c.drawRoundRect(rowBox, rr, rr, fill);
                border.setColor(isDark ? 0x42FFFFFF : 0x30000000);
                c.drawRoundRect(rowBox, rr, rr, border);
                tmp.set(rowBox.left + 4 * k, rowBox.top + 11 * k, rowBox.left + 8.5f * k, rowBox.bottom - 11 * k);
                fill.setColor(primaryInk);
                c.drawRoundRect(tmp, 2.5f * k, 2.5f * k, fill);
            } else if (isSelected) {
                fill.setShader(null);
                fill.setColor(isDark ? 0x1EFFFFFF : 0x14000000);
                c.drawRoundRect(rowBox, rr, rr, fill);
            }

            boolean inverted = (isSelected && focusedInZone);
            int titleInk = isLiftedGenreRow ? whiteColor : (inverted ? invPrimaryInk : primaryInk);
            int subInk = isLiftedGenreRow ? 0xF2FFFFFF : (inverted ? (isDark ? 0xFF3A3A3C : 0xE6FFFFFF) : dimInk);
            int accentInk = isLiftedGenreRow ? whiteColor : (inverted ? (isDark ? 0xFF3A3A3C : 0xE6FFFFFF) : dimInk);

            if (node.grayedOut) {
                titleInk = Ui.alpha(titleInk, inverted ? 0.68f : 0.58f);
                subInk = Ui.alpha(subInk, inverted ? 0.62f : 0.54f);
                accentInk = Ui.alpha(accentInk, inverted ? 0.62f : 0.54f);
            }

            float iconX = rowBox.left + (compactRail ? 28f : 32f) * k;
            float iconSize = (compactRail ? 21f : 23.5f) * k;
            if (node.swatchColor != 0) {
                float swR = (compactRail ? 10.5f : 12.5f) * k;
                fill.setShader(null);
                fill.setColor(0xFF000000 | node.swatchColor);
                c.drawCircle(iconX, rowBox.centerY(), swR, fill);
                border.setColor(inverted ? (isDark ? 0x55000000 : 0x66FFFFFF) : (isDark ? 0x66FFFFFF : 0x40000000));
                c.drawCircle(iconX, rowBox.centerY(), swR, border);
            } else {
                Symbols.draw(ctx, c, node.icon, iconX, rowBox.centerY(), iconSize, titleInk);
            }

            // Right-side visual affordances (Toggle Switch / External Link / Chevron / Check / Status Dot / Value)
            float rightBound = rowBox.right - (compactRail ? 16f : 22f) * k;
            boolean isToggle = node.rowType == SettingsTree.RowType.TOGGLE && !compactRail;
            boolean isExternal = node.rowType == SettingsTree.RowType.EXTERNAL && !compactRail;

            if (isToggle) {
                float swW = 48f * k;
                float swH = 26f * k;
                float swLeft = rightBound - swW;
                float swTop = rowBox.centerY() - swH / 2f;
                tmpToggleRect.set(swLeft, swTop, rightBound, swTop + swH);
                fill.setShader(null);
                if (node.checked) {
                    fill.setColor(inverted
                            ? (isDark ? STATUS_POSITIVE_LIGHT : STATUS_POSITIVE_DARK)
                            : (isDark ? STATUS_POSITIVE_DARK : STATUS_POSITIVE_LIGHT));
                } else {
                    fill.setColor(inverted
                            ? (isDark ? 0x38000000 : 0x40FFFFFF)
                            : (isDark ? 0x2EFFFFFF : 0x26000000));
                }
                c.drawRoundRect(tmpToggleRect, swH / 2f, swH / 2f, fill);
                float knobR = 9.5f * k;
                float knobCx = node.checked ? (rightBound - swH / 2f) : (swLeft + swH / 2f);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(knobCx, rowBox.centerY(), knobR, fill);
                rightBound -= (swW + 14f * k);
            } else {
                if (node.hasChildren() && !compactRail) {
                    Symbols.draw(ctx, c, "chevron_right", rightBound - 8 * k, rowBox.centerY(), 17.5f * k, subInk);
                    rightBound -= 26 * k;
                } else if (isExternal) {
                    Symbols.draw(ctx, c, "external_link", rightBound - 9 * k, rowBox.centerY(), 17 * k, subInk);
                    rightBound -= 28 * k;
                }
                if (node.checked && !compactRail && node.rowType == SettingsTree.RowType.CHECK) {
                    Symbols.draw(ctx, c, "check", rightBound - 11 * k, rowBox.centerY(), 19.5f * k, accentInk);
                    rightBound -= 30 * k;
                }
            }

            String displayVal = node.value;
            boolean isTzRow = node.id != null && node.id.startsWith("ss_tz:");
            if (isTzRow) {
                String[] tzParts = node.id.substring("ss_tz:".length()).split(":", 2);
                if (tzParts.length >= 1 && !tzParts[0].isEmpty()) {
                    displayVal = ScreensaverCatalog.formatTimezoneSelectionValue(
                            tzParts[0], System.currentTimeMillis(), Ui.h24());
                }
            }
            // On Toggle switches, omit redundant "On"/"Off"/"Enabled"/"Disabled" text when the switch already says it
            if (isToggle && ("On".equals(displayVal) || "Off".equals(displayVal)
                    || "Enabled".equals(displayVal) || "Disabled".equals(displayVal))) {
                displayVal = "";
            }
            // On External rows, keep real live telemetry values (like "4K · 60Hz" or "6.1 GB free") and omit generic "System"
            if (isExternal && "System".equals(displayVal)) {
                displayVal = "";
            }

            int semanticToneColor = 0;
            if (node.statusTone != SettingsTree.StatusTone.NONE && !isToggle) {
                semanticToneColor = (node.statusTone == SettingsTree.StatusTone.POSITIVE)
                        ? (inverted
                                ? (isDark ? STATUS_POSITIVE_LIGHT : STATUS_POSITIVE_DARK)
                                : (isDark ? STATUS_POSITIVE_DARK : STATUS_POSITIVE_LIGHT))
                        : (inverted
                                ? (isDark ? STATUS_WARNING_LIGHT : STATUS_WARNING_DARK)
                                : (isDark ? STATUS_WARNING_DARK : STATUS_WARNING_LIGHT));
            }

            if (!displayVal.isEmpty()) {
                fitPaint.set(lSubTabOn);
                fitPaint.setTextSize((compactRail ? 16.5f : 19.5f) * k);
                int valColor = semanticToneColor != 0
                        ? semanticToneColor
                        : (node.checked ? accentInk : subInk);
                fitPaint.setColor(valColor);
                float maxValW = compactRail ? (160 * k) : (wideCol ? (320 * k) : (180 * k));
                String rawVal = isTzRow ? displayVal : Ui.tr(displayVal);
                String valStr = TextUtils.ellipsize(rawVal, fitPaint, maxValW, TextUtils.TruncateAt.END).toString();
                float vw = fitPaint.measureText(valStr);
                c.drawText(valStr, rightBound - vw, rowBox.centerY() + Ui.halfCap(fitPaint), fitPaint);
                rightBound -= (vw + 12 * k);
            }

            // Semantic status dot (emerald for Recommended/Connected, amber for Caution/Incomplete)
            if (semanticToneColor != 0) {
                fill.setShader(null);
                fill.setColor(semanticToneColor);
                float dotR = 4.5f * k;
                c.drawCircle(rightBound - dotR, rowBox.centerY(), dotR, fill);
                rightBound -= (dotR * 2f + 10f * k);
            }

            float textLeft = rowBox.left + (compactRail ? 54f : 62f) * k;
            float availTextW = Math.max(72 * k, rightBound - textLeft);
            boolean hasSub = !compactRail && !node.subtitle.isEmpty();

            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize((compactRail ? 21.5f : 23f) * k);
            fitPaint.setColor(titleInk);
            String titleStr = TextUtils.ellipsize(Ui.tr(node.title), fitPaint, availTextW, TextUtils.TruncateAt.END).toString();
            if (hasSub) {
                c.drawText(titleStr, textLeft, rowBox.top + 34 * k, fitPaint);
                fitPaint.set(lSubTab);
                fitPaint.setTextSize(17.5f * k);
                int subRowColor = (node.statusTone == SettingsTree.StatusTone.WARNING && semanticToneColor != 0 && !inverted)
                        ? semanticToneColor
                        : subInk;
                fitPaint.setColor(subRowColor);
                String subText = node.subtitle;
                if (isTzRow) {
                    String[] tzParts = node.id.substring("ss_tz:".length()).split(":", 2);
                    if (tzParts.length >= 1 && !tzParts[0].isEmpty()) {
                        subText = ScreensaverCatalog.formatTimezoneSubtitle(
                                tzParts[0], System.currentTimeMillis(), Ui.h24());
                    }
                }
                String subStr = TextUtils.ellipsize(Ui.tr(subText), fitPaint, availTextW, TextUtils.TruncateAt.END).toString();
                c.drawText(subStr, textLeft, rowBox.top + 60 * k, fitPaint);
            } else {
                c.drawText(titleStr, textLeft, rowBox.centerY() + Ui.halfCap(fitPaint), fitPaint);
            }
        }
        c.restore();

        // Scrollbar rendered only when list actually overflows viewport
        if (totalContentH > height + 4 * k) {
            float trackTop = topY + 6 * k;
            float trackBottom = topY + height - 6 * k;
            float trackH = Math.max(40 * k, trackBottom - trackTop);
            float trackW = 4f * k;
            float trackX = x + width + 4 * k;
            fill.setShader(null);
            fill.setColor(isDark ? 0x22FFFFFF : 0x24343741);
            tmp.set(trackX, trackTop, trackX + trackW, trackBottom);
            c.drawRoundRect(tmp, trackW / 2f, trackW / 2f, fill);

            float thumbH = Math.max(28 * k, trackH * (height / totalContentH));
            float frac = Ui.clamp(scrollY / Math.max(1f, totalContentH - height));
            float thumbTop = trackTop + (trackH - thumbH) * frac;
            fill.setColor(isDark ? 0xCCFFFFFF : 0xA8343741);
            tmp.set(trackX, thumbTop, trackX + trackW, thumbTop + thumbH);
            c.drawRoundRect(tmp, trackW / 2f, trackW / 2f, fill);
        }
    }

    private void drawSettingsDetailCard(
            Context ctx,
            Canvas c,
            SettingsTree.Node node,
            float x,
            float y,
            float width,
            float k,
            int ink,
            int dim,
            boolean isDark,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        if (node.swatchColor != 0) {
            float swR = 22 * k;
            fill.setShader(null);
            fill.setColor(0xFF000000 | node.swatchColor);
            c.drawCircle(x + 26 * k, y + 36 * k, swR, fill);
            border.setColor(isDark ? 0x66FFFFFF : 0x40000000);
            c.drawCircle(x + 26 * k, y + 36 * k, swR, border);
        } else {
            Symbols.draw(ctx, c, node.icon, x + 28 * k, y + 36 * k, 42 * k, ink);
        }

        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(28 * k);
        fitPaint.setColor(ink);
        String t = TextUtils.ellipsize(Ui.tr(node.title), fitPaint, width, TextUtils.TruncateAt.END).toString();
        c.drawText(t, x, y + 104 * k, fitPaint);

        float nextY = y + 146 * k;
        if (!node.value.isEmpty()) {
            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize(23 * k);
            fitPaint.setColor(dim);
            c.drawText(Ui.tr("Current: ") + Ui.tr(node.value), x, nextY, fitPaint);
            nextY += 42 * k;
        }
        if (!node.subtitle.isEmpty()) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(21 * k);
            fitPaint.setColor(dim);
            String sub = TextUtils.ellipsize(Ui.tr(node.subtitle), fitPaint, width, TextUtils.TruncateAt.END).toString();
            c.drawText(sub, x, nextY, fitPaint);
        }
    }

    void drawClockSettingsPreview(
            Canvas c,
            String style,
            float x,
            float y,
            float width,
            float height,
            float k,
            String clockText,
            String dateText,
            int dimInk,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        ensureScratchInitialized();
        drawClockSettingsPreview(null, c, style, x, y, width, height, k, clockText, dateText,
                dimInk, whiteColor, fill, border, fill, lCardTitle, lSubTab, lSubTabOn, fitPaint,
                tmpClockCardRect, null, null);
    }

    /**
     * Renders a live visual preview of any of the 8 curated Screensaver Clock Designs over a real 4K
     * starter photograph in the Right Column of Settings.
     */
    void drawClockSettingsPreview(
            Context ctx,
            Canvas c,
            String rawStyle,
            float x,
            float y,
            float width,
            float height,
            float k,
            String clockText,
            String dateText,
            int dimInk,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint imagePaint,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            RectF tmp,
            Executor loaderExecutor,
            Runnable onPreviewDecodedOnUi) {
        ensureScratchInitialized();
        String style = (rawStyle == null || rawStyle.isEmpty()) ? "liquid_glass" : rawStyle;
        if ("editorial".equals(style)) style = "stacked";
        else if ("classic".equals(style)) style = "frosted_pill";

        float cardH = Math.max(220 * k, Math.min(height, 440 * k));
        float r = 24 * k;
        tmpPreviewBox.set(x, y, x + width, y + cardH);
        RectF box = tmpPreviewBox;

        Set<String> activeCats = Prefs.saverCategories();
        String previewCat = (activeCats != null && !activeCats.isEmpty()) ? activeCats.iterator().next() : "nature";
        Bitmap photoBmp = resolveGalleryPreviewBitmap(ctx, previewCat, loaderExecutor, onPreviewDecodedOnUi);

        c.save();
        tmpPreviewClip.reset();
        tmpPreviewClip.addRoundRect(box, r, r, Path.Direction.CW);
        c.clipPath(tmpPreviewClip);

        if (photoBmp != null && !photoBmp.isRecycled() && imagePaint != null && tmp != null) {
            float bw = photoBmp.getWidth();
            float bh = photoBmp.getHeight();
            float scale = Math.max(box.width() / bw, box.height() / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            float dx = box.centerX() - dw / 2f;
            float dy = box.centerY() - dh / 2f;
            tmp.set(dx, dy, dx + dw, dy + dh);
            c.drawBitmap(photoBmp, null, tmp, imagePaint);
        } else {
            if (clockPreviewBgGrad == null
                    || clockPreviewBgL != box.left || clockPreviewBgT != box.top
                    || clockPreviewBgR != box.right || clockPreviewBgB != box.bottom) {
                clockPreviewBgL = box.left;
                clockPreviewBgT = box.top;
                clockPreviewBgR = box.right;
                clockPreviewBgB = box.bottom;
                clockPreviewBgGrad = new LinearGradient(box.left, box.top, box.right, box.bottom,
                        new int[] {0xFF0A2229, 0xFF0E4A52, 0xFF1E3A5F, 0xFF081018},
                        new float[] {0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP);
            }
            fill.setShader(clockPreviewBgGrad);
            c.drawRect(box, fill);
            fill.setShader(null);
        }

        // Subtle top-right & bottom-left vignettes matching active DreamView readability
        fill.setShader(null);
        fill.setColor(0x2A04070D);
        c.drawRect(box, fill);

        float scrimTop = box.bottom - 120 * k;
        if (galleryScrimGrad == null
                || galleryScrimGradL != box.left || galleryScrimGradT != scrimTop || galleryScrimGradB != box.bottom) {
            galleryScrimGradL = box.left;
            galleryScrimGradT = scrimTop;
            galleryScrimGradB = box.bottom;
            galleryScrimGrad = new LinearGradient(box.left, scrimTop, box.left, box.bottom,
                    new int[] {0x00000000, 0x8005070E, 0xD805070E},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        fill.setShader(galleryScrimGrad);
        c.drawRect(box.left, scrimTop, box.right, box.bottom, fill);
        fill.setShader(null);
        c.restore();

        border.setColor(0x44FFFFFF);
        c.drawRoundRect(box, r, r, border);

        String nowClock = (clockText != null && !clockText.isEmpty()) ? clockText : Ui.clock(System.currentTimeMillis());
        String nowDate = (dateText != null && !dateText.isEmpty()) ? dateText : Ui.date(System.currentTimeMillis());
        fitPaint.set(lSubTabOn);
        fitPaint.setStyle(Paint.Style.FILL);
        fitPaint.setStrokeWidth(0f);
        fitPaint.setShader(null);
        fitPaint.setTextSize(16.5f * k);
        fitPaint.setColor(0xE0FFFFFF);
        float rightEdge = box.right - 28 * k;
        float dateW = fitPaint.measureText(nowDate);
        c.drawText(nowDate, rightEdge - dateW, box.top + 38 * k, fitPaint);

        fitPaint.setAntiAlias(true);
        drawPreviewClockByStyle(c, style, nowClock, rightEdge, box.top + 52 * k, k, fitPaint, fill, border);

        String styleTitle = clockStylePreviewTitle(style);
        String styleDesc = clockStylePreviewDescription(style);
        fitPaint.set(lCardTitle);
        fitPaint.setStyle(Paint.Style.FILL);
        fitPaint.setShader(null);
        fitPaint.setTextSize(21 * k);
        fitPaint.setColor(whiteColor);
        c.drawText(Ui.tr(styleTitle), box.left + 26 * k, box.bottom - 26 * k, fitPaint);

        if (box.bottom + 34 * k <= y + height + 24 * k) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(17.5f * k);
            fitPaint.setColor(dimInk);
            String dLine = TextUtils.ellipsize(Ui.tr(styleDesc), fitPaint, width, TextUtils.TruncateAt.END).toString();
            c.drawText(dLine, x + 4 * k, box.bottom + 32 * k, fitPaint);
        }
    }

    private void drawPreviewClockByStyle(
            Canvas c,
            String style,
            String nowClock,
            float rightEdge,
            float topY,
            float k,
            TextPaint fitPaint,
            Paint fill,
            Paint border) {
        fitPaint.setStyle(Paint.Style.FILL);
        fitPaint.setStrokeWidth(0f);
        fitPaint.setShader(null);
        fitPaint.setLetterSpacing(0f);

        if ("frosted_pill".equals(style)) {
            fitPaint.setTypeface(Ui.displayMedium);
            fitPaint.setTextSize(46 * k);
            fitPaint.setLetterSpacing(0.01f);
            float tw = fitPaint.measureText(nowClock);
            float padX = 18 * k, pillH = 60 * k;
            float pillW = tw + 2 * padX;
            float pLeft = rightEdge - pillW, pTop = topY + 2 * k, pBottom = pTop + pillH;
            tmpClockCardRect.set(pLeft, pTop, rightEdge, pBottom);
            fill.setShader(null);
            fill.setColor(0x5C0B0F18);
            c.drawRoundRect(tmpClockCardRect, pillH * 0.5f, pillH * 0.5f, fill);
            border.setColor(0x4AFFFFFF);
            c.drawRoundRect(tmpClockCardRect, pillH * 0.5f, pillH * 0.5f, border);
            fitPaint.setColor(0xF5FFFFFF);
            c.drawText(nowClock, pLeft + padX, pTop + 45 * k, fitPaint);
        } else if ("stacked".equals(style)) {
            String[] hm = DreamView.splitHourMinute(nowClock);
            fitPaint.setTypeface(Ui.displaySemibold);
            fitPaint.setTextSize(44 * k);
            fitPaint.setLetterSpacing(-0.03f);
            float wH = fitPaint.measureText(hm[0]);
            float wM = fitPaint.measureText(hm[1]);
            float maxW = Math.max(wH, wM);
            tmpClockCardRect.set(rightEdge - maxW - 11 * k, topY + 4 * k, rightEdge - maxW - 8.5f * k, topY + 84 * k);
            fill.setShader(null);
            fill.setColor(0x55FFFFFF);
            c.drawRoundRect(tmpClockCardRect, 1.2f * k, 1.2f * k, fill);
            fitPaint.setColor(0xF8FFFFFF);
            c.drawText(hm[0], rightEdge - wH, topY + 38 * k, fitPaint);
            fitPaint.setColor(0xDCE0ECFA);
            c.drawText(hm[1], rightEdge - wM, topY + 80 * k, fitPaint);
        } else if ("split_flap".equals(style)) {
            String[] hm = DreamView.splitHourMinute(nowClock);
            fitPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            fitPaint.setTextSize(33 * k);
            float cardW = 58 * k, cardH = 54 * k, gap = 7 * k, cr = 9 * k;
            float cTop = topY + 4 * k, cBot = cTop + cardH, cMid = (cTop + cBot) * 0.5f;
            for (int idx = 0; idx < 2; idx++) {
                float cRight = (idx == 0) ? (rightEdge - cardW - gap) : rightEdge;
                float cLeft = cRight - cardW;
                tmpClockCardRect.set(cLeft, cTop, cRight, cBot);
                fill.setShader(null);
                fill.setColor(0x800C1018);
                c.drawRoundRect(tmpClockCardRect, cr, cr, fill);
                border.setColor(0x40FFFFFF);
                c.drawRoundRect(tmpClockCardRect, cr, cr, border);
                fitPaint.setColor(0xF5FFFFFF);
                float pw = fitPaint.measureText(hm[idx]);
                c.drawText(hm[idx], cLeft + (cardW - pw) * 0.5f, cTop + 39 * k, fitPaint);
                fill.setColor(0xAA05070B);
                c.drawRect(cLeft + 1 * k, cMid - 0.6f * k, cRight - 1 * k, cMid + 0.6f * k, fill);
            }
        } else if ("outline".equals(style)) {
            fitPaint.setTypeface(Ui.displaySemibold);
            fitPaint.setTextSize(56 * k);
            fitPaint.setLetterSpacing(0.01f);
            float cw = fitPaint.measureText(nowClock);
            float cx = rightEdge - cw, cy = topY + 48 * k;
            fitPaint.setStyle(Paint.Style.FILL);
            fitPaint.setColor(0x1EFFFFFF);
            c.drawText(nowClock, cx, cy, fitPaint);
            fitPaint.setStyle(Paint.Style.STROKE);
            fitPaint.setStrokeJoin(Paint.Join.ROUND);
            fitPaint.setStrokeWidth(1.6f * k);
            fitPaint.setColor(0xEEFFFFFF);
            c.drawText(nowClock, cx, cy, fitPaint);
        } else if ("liquid_glass".equals(style)) {
            fitPaint.setTypeface(Ui.displaySemibold);
            fitPaint.setTextSize(56 * k);
            fitPaint.setLetterSpacing(-0.01f);
            float cw = fitPaint.measureText(nowClock);
            float cx = rightEdge - cw, cy = topY + 48 * k;
            if (clockPreviewDigitGrad == null || clockPreviewDigitX != cx || clockPreviewDigitY != cy) {
                clockPreviewDigitX = cx;
                clockPreviewDigitY = cy;
                clockPreviewDigitGrad = new LinearGradient(cx, cy - 46 * k, cx, cy,
                        new int[] {0xEEFFFFFF, 0x9EDCE8F5, 0xCCEBF3FA},
                        new float[] {0f, 0.50f, 1f}, Shader.TileMode.CLAMP);
            }
            fitPaint.setStyle(Paint.Style.FILL);
            fitPaint.setShader(clockPreviewDigitGrad);
            fitPaint.setColor(0xFFFFFFFF);
            c.drawText(nowClock, cx, cy, fitPaint);
            fitPaint.setShader(null);
            fitPaint.setStyle(Paint.Style.STROKE);
            fitPaint.setStrokeJoin(Paint.Join.ROUND);
            fitPaint.setStrokeWidth(1.1f * k);
            fitPaint.setColor(0xDCFFFFFF);
            c.drawText(nowClock, cx, cy, fitPaint);
        } else {
            if ("minimal".equals(style)) {
                fitPaint.setTypeface(Ui.thin);
                fitPaint.setTextSize(56 * k);
                fitPaint.setLetterSpacing(0.02f);
                fitPaint.setColor(0xEBFFFFFF);
            } else if ("modern_serif".equals(style)) {
                fitPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                fitPaint.setTextSize(56 * k);
                fitPaint.setLetterSpacing(-0.02f);
                fitPaint.setColor(0xF5FFFDF9);
            } else if ("digital_mono".equals(style)) {
                fitPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                fitPaint.setTextSize(52 * k);
                fitPaint.setLetterSpacing(0.03f);
                fitPaint.setColor(0xF2F8FAFC);
            } else {
                fitPaint.setTypeface(Ui.displaySemibold);
                fitPaint.setTextSize(56 * k);
                fitPaint.setColor(0xF2FFFFFF);
            }
            float cw = fitPaint.measureText(nowClock);
            c.drawText(nowClock, rightEdge - cw, topY + 48 * k, fitPaint);
        }

        fitPaint.setStyle(Paint.Style.FILL);
        fitPaint.setStrokeWidth(0f);
        fitPaint.setShader(null);
        fitPaint.setLetterSpacing(0f);
    }

    /** Returns the human-readable preview card title for any of the 8 curated Screensaver clock designs. */
    public static String clockStylePreviewTitle(String style) {
        if ("frosted_pill".equals(style) || "classic".equals(style)) return "Frosted Pill Clock";
        if ("stacked".equals(style) || "editorial".equals(style)) return "Stacked Duo Clock";
        if ("split_flap".equals(style)) return "Split Cards Clock";
        if ("modern_serif".equals(style)) return "Editorial Serif Clock";
        if ("digital_mono".equals(style)) return "Chrono Mono Clock";
        if ("minimal".equals(style)) return "Minimal Thin Clock";
        if ("outline".equals(style)) return "Architectural Outline Clock";
        return "Liquid Glass Clock";
    }

    /** Returns the subtitle description for any of the 8 curated Screensaver clock designs. */
    public static String clockStylePreviewDescription(String style) {
        if ("frosted_pill".equals(style) || "classic".equals(style)) return "Smoked-glass stadium badge framing time over 4K scenery";
        if ("stacked".equals(style) || "editorial".equals(style)) return "Sculptural two-row vertical hours over minutes layout";
        if ("split_flap".equals(style)) return "Horological twin smoked numeral cards with split-flap seam";
        if ("modern_serif".equals(style)) return "High-contrast magazine serif typography with refined proportions";
        if ("digital_mono".equals(style)) return "Precision instrument tabular monospace numerals";
        if ("minimal".equals(style)) return "Feather-light hairline numerals for pure photo immersion";
        if ("outline".equals(style)) return "Crisp hollow architectural stroke numerals";
        return "Frosted crystal glass numerals refracting the 4K wallpaper";
    }

    private Bitmap resolveGalleryPreviewBitmap(
            Context ctx,
            String category,
            Executor loaderExecutor,
            Runnable onPreviewDecodedOnUi) {
        String key = (category == null || category.isEmpty()) ? "mixed" : category.toLowerCase(Locale.ROOT);
        Bitmap cached = galleryPreviewBitmaps.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        if (ctx != null && loaderExecutor != null && !galleryPreviewLoading.contains(key)) {
            galleryPreviewLoading.add(key);
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            loaderExecutor.execute(() -> {
                try (InputStream in = ScreensaverCatalog.openCategoryPreviewStream(appCtx, key)) {
                    if (in != null) {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 4;
                        Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                        if (bmp != null) {
                            galleryPreviewBitmaps.put(key, bmp);
                            if (onPreviewDecodedOnUi != null) onPreviewDecodedOnUi.run();
                        }
                    }
                    ScreensaverCatalog.prefetchAsync(appCtx);
                } catch (Throwable ignored) {
                } finally {
                    galleryPreviewLoading.remove(key);
                }
            });
        }
        return null;
    }

    /**
     * Renders a live visual preview of the selected/hovered 4K Photography Gallery
     * ({@code mixed}, {@code nature}, {@code macro}, {@code urban}, {@code wildlife}) in the Right Column of Settings.
     */
    void drawScreensaverGalleryPreview(
            Context ctx,
            Canvas c,
            String category,
            float x,
            float y,
            float width,
            float height,
            float k,
            String clockText,
            String dateText,
            int dimInk,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint imagePaint,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            RectF tmp,
            Executor loaderExecutor,
            Runnable onPreviewDecodedOnUi) {
        ensureScratchInitialized();
        String cat = (category == null || category.isEmpty()) ? "mixed" : category.toLowerCase(Locale.ROOT);
        float cardH = Math.max(220 * k, Math.min(height, 440 * k));
        float r = 24 * k;
        tmpPreviewBox.set(x, y, x + width, y + cardH);
        RectF box = tmpPreviewBox;

        Bitmap photoBmp = resolveGalleryPreviewBitmap(ctx, cat, loaderExecutor, onPreviewDecodedOnUi);
        c.save();
        tmpPreviewClip.reset();
        tmpPreviewClip.addRoundRect(box, r, r, Path.Direction.CW);
        c.clipPath(tmpPreviewClip);

        if (photoBmp != null && !photoBmp.isRecycled()) {
            float bw = photoBmp.getWidth();
            float bh = photoBmp.getHeight();
            float scale = Math.max(box.width() / bw, box.height() / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            float dx = box.centerX() - dw / 2f;
            float dy = box.centerY() - dh / 2f;
            tmp.set(dx, dy, dx + dw, dy + dh);
            c.drawBitmap(photoBmp, null, tmp, imagePaint);
        } else {
            if (gallerySceneGrad == null
                    || !cat.equals(gallerySceneGradCat)
                    || gallerySceneGradL != box.left || gallerySceneGradT != box.top
                    || gallerySceneGradR != box.right || gallerySceneGradB != box.bottom) {
                int[] palette;
                if ("nature".equals(cat)) {
                    palette = new int[] {0xFF062C30, 0xFF05595B, 0xFF0F766E, 0xFF081C24};
                } else if ("macro".equals(cat)) {
                    palette = new int[] {0xFF052E16, 0xFF14532D, 0xFF15803D, 0xFF041E11};
                } else if ("urban".equals(cat)) {
                    palette = new int[] {0xFF1E1B4B, 0xFF311042, 0xFF4C1D95, 0xFF090D16};
                } else if ("space".equals(cat)) {
                    palette = new int[] {0xFF0A0A1E, 0xFF18153A, 0xFF311E5E, 0xFF050510};
                } else if ("paintings".equals(cat)) {
                    palette = new int[] {0xFF3A1F04, 0xFF6B3A0E, 0xFF9E5C1B, 0xFF1C0D02};
                } else if ("art".equals(cat)) {
                    palette = new int[] {0xFF1C2833, 0xFF2C3E50, 0xFF34495E, 0xFF0E141B};
                } else if ("wildlife".equals(cat)) {
                    palette = new int[] {0xFF2A1810, 0xFF5C2D16, 0xFF9A3412, 0xFF140C08};
                } else {
                    palette = new int[] {0xFF0F172A, 0xFF1E3A8A, 0xFF0D9488, 0xFF090D16};
                }
                gallerySceneGradCat = cat;
                gallerySceneGradL = box.left;
                gallerySceneGradT = box.top;
                gallerySceneGradR = box.right;
                gallerySceneGradB = box.bottom;
                gallerySceneGrad = new LinearGradient(box.left, box.top, box.right, box.bottom,
                        palette, new float[] {0f, 0.38f, 0.74f, 1f}, Shader.TileMode.CLAMP);
            }
            fill.setShader(gallerySceneGrad);
            c.drawRect(box, fill);
            fill.setShader(null);

            String iconName = SettingsTree.iconForGalleryCategory(cat);
            Symbols.draw(ctx, c, iconName, box.left + 84 * k, box.top + 88 * k, 72 * k, 0x26FFFFFF);
        }

        float scrimTop = box.bottom - 150 * k;
        if (galleryScrimGrad == null
                || galleryScrimGradL != box.left || galleryScrimGradT != scrimTop || galleryScrimGradB != box.bottom) {
            galleryScrimGradL = box.left;
            galleryScrimGradT = scrimTop;
            galleryScrimGradB = box.bottom;
            galleryScrimGrad = new LinearGradient(box.left, scrimTop, box.left, box.bottom,
                    new int[] {0x00000000, 0x9905070E, 0xE605070E},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        fill.setShader(galleryScrimGrad);
        c.drawRect(box.left, scrimTop, box.right, box.bottom, fill);
        fill.setShader(null);
        c.restore();

        border.setColor(0x44FFFFFF);
        c.drawRoundRect(box, r, r, border);

        String nowClock = (clockText != null && !clockText.isEmpty()) ? clockText : Ui.clock(System.currentTimeMillis());
        String nowDate = (dateText != null && !dateText.isEmpty()) ? dateText : Ui.date(System.currentTimeMillis());
        float rightEdge = box.right - 28 * k;
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(17 * k);
        fitPaint.setColor(0xD9FFFFFF);
        float dateW = fitPaint.measureText(nowDate);
        c.drawText(nowDate, rightEdge - dateW, box.top + 38 * k, fitPaint);

        String activeClockStyle = Prefs.saverClockStyle();
        drawPreviewClockByStyle(c, activeClockStyle, nowClock, rightEdge, box.top + 48 * k, k, fitPaint, fill, border);

        ScreensaverCatalog.PhotoEntry sample = ScreensaverCatalog.sampleEntryForCategory(cat);
        String credit = ScreensaverCatalog.buildCaptionMeta(
                sample.category, sample.title, sample.artist, sample.location, sample.building,
                Prefs.saverShowArtist(), Prefs.saverShowLocation());

        if (!credit.isEmpty()) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(16.5f * k);
            fitPaint.setColor(0xD8FFFFFF);
            String creditLine = TextUtils.ellipsize(credit, fitPaint, width - 52 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(creditLine, box.left + 26 * k, box.bottom - 22 * k, fitPaint);
        }

        if (box.bottom + 34 * k <= y + height + 24 * k) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(17.5f * k);
            fitPaint.setColor(dimInk);
            String desc = ScreensaverCatalog.categoryDescription(cat);
            String dLine = TextUtils.ellipsize(Ui.tr(desc), fitPaint, width, TextUtils.TruncateAt.END).toString();
            c.drawText(dLine, x + 4 * k, box.bottom + 32 * k, fitPaint);
        }
    }
}
