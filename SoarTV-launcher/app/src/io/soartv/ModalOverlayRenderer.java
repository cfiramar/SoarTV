package io.soartv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

/**
 * Encapsulates Liquid Glass modal &amp; overlay rendering for {@link HomeView}:
 * <ul>
 *   <li>Cancellable Sync Waiting modal ({@link #drawSyncModal})</li>
 *   <li>Trakt OAuth QR + activation-code view builder ({@link #buildTraktOAuthDialogView})</li>
 *   <li>{@code "New Remote Detected"} Liquid Glass QR + 6-digit Pairing Platter ({@link #drawPairingModal})</li>
 *   <li>Liquid Glass Choice &amp; Masked Passphrase/PIN Input modal ({@link #drawLiquidGlassModal},
 *       {@link LiquidGlassInputState})</li>
 *   <li>Hold-OK Context Menu &amp; Smart-Home Device Control Card overlay ({@link #drawMenu})</li>
 * </ul>
 *
 * <p><b>Why:</b> Isolates all modal overlays into a cohesive single-responsibility collaborator with lazily
 * allocated {@link RectF} bounds (zero per-frame heap allocations in {@code onDraw}, zero {@code Paint}/{@code RectF}
 * calls in {@code <clinit>} or constructor for 100% headless Desktop JVM test compatibility).
 *
 * <p><b>What:</b> Renders all modal platters onto {@code toastLayer} and {@code menuLayer} and performs
 * deterministic pointer hit-testing against cached layout rectangles.
 */
public final class ModalOverlayRenderer {

    /** Number of selectable actions on the on-TV "New Remote Detected" pairing platter. */
    public static final int PAIRING_ACTION_COUNT = 4;

    /** Zero-emoji action labels for the on-TV "New Remote Detected" pairing platter. */
    public static final String[] PAIRING_ACTION_LABELS = {
            "Approve as Guest (30 Days)",
            "Approve as Household",
            "Approve as Admin",
            "Dismiss"
    };

    /** Hit-test outcomes for {@link #hitTestLiquidGlassModal}. */
    static final int LG_HIT_OUTSIDE = -2;
    static final int LG_HIT_NONE = -1;
    static final int LG_HIT_CANCEL = 100;
    static final int LG_HIT_INPUT_BOX = 101;
    static final int LG_HIT_REVEAL = 102;
    static final int LG_HIT_CONFIRM = 103;

    static final float MENU_W = 720f;
    static final float MENU_PAD = 44f;
    static final float MENU_INSET = 16f;
    static final float MENU_ROW = 76f;
    static final float MENU_STEP = 80f;
    static final float MENU_R = 36f;

    /** Hit-test outcomes for {@link #hitTestSleepTimerModal}. */
    static final int SLEEP_HIT_OUTSIDE = -2;
    static final int SLEEP_HIT_NONE = -1;
    static final int SLEEP_HIT_TOGGLE = 0;
    static final int SLEEP_HIT_UP = 10;
    static final int SLEEP_HIT_DOWN = 11;
    static final int SLEEP_HIT_ADD_FIVE = 2;
    static final int SLEEP_HIT_SAVE = 3;
    static final int SLEEP_HIT_CANCEL = 4;

    /** Hit-test outcomes for {@link #hitTestSlideDurationModal}. */
    static final int SD_HIT_OUTSIDE = -2;
    static final int SD_HIT_NONE = -1;
    static final int SD_HIT_UP = 10;
    static final int SD_HIT_DOWN = 11;

    /** Hit-test outcomes for {@link #hitTestStreamQualityModal}. */
    static final int SQ_HIT_OUTSIDE = -2;
    static final int SQ_HIT_NONE = -1;
    static final int SQ_HIT_RES_CARD = 0;
    static final int SQ_HIT_FPS_CARD = 1;
    static final int SQ_HIT_BIT_CARD = 2;
    static final int SQ_HIT_RES_UP = 10;
    static final int SQ_HIT_RES_DOWN = 11;
    static final int SQ_HIT_FPS_UP = 20;
    static final int SQ_HIT_FPS_DOWN = 21;
    static final int SQ_HIT_BIT_UP = 30;
    static final int SQ_HIT_BIT_DOWN = 31;

    private static final float GAMEPAD_AXIS_DEADZONE = 0.55f;

    /**
     * Converts a gaming controller's analog stick axis and D-pad hat axis into a discrete step
     * direction ({@code -1}, {@code 0}, or {@code +1}), prioritizing the digital hat when active.
     */
    public static int resolveGamepadAxisDirection(float stickAxis, float hatAxis) {
        if (Math.abs(hatAxis) >= GAMEPAD_AXIS_DEADZONE) {
            return hatAxis < 0f ? -1 : 1;
        }
        if (Math.abs(stickAxis) >= GAMEPAD_AXIS_DEADZONE) {
            return stickAxis < 0f ? -1 : 1;
        }
        return 0;
    }

    /**
     * Pure-Java interactive state model &amp; dynamic hardware advisor for the 3-column Liquid Glass
     * Stream Quality modal (Resolution | Frame Rate | Bitrate).
     *
     * <p><b>Why:</b> Lets couch gamers tune Resolution, FPS, and Bitrate side-by-side immediately
     * using Left/Right (switch column) and Up/Down (step value) from either a TV remote or a game
     * controller, guided by dynamic color-coded ranges and short truthful explanations computed
     * from live TV, PC, and network telemetry—with zero extra buttons or key-legend clutter.
     */
    public static final class StreamQualityModalState {
        public static final int SLOT_RES = 0;
        public static final int SLOT_FPS = 1;
        public static final int SLOT_BITRATE = 2;

        public static final int TONE_BALANCED = 0;
        public static final int TONE_RECOMMENDED = 1;
        public static final int TONE_CAUTION = 2;

        public static final String[] RES_KEYS = {"720p", "1080p", "1440p", "4k"};
        public static final String[] RES_LABELS = {"720p", "1080p", "1440p", "4K"};
        public static final String[] RES_DIMS = {"1280\u00D7720", "1920\u00D71080", "2560\u00D71440", "3840\u00D72160"};
        public static final int[] RES_WIDTHS = {1280, 1920, 2560, 3840};
        public static final int[] RES_HEIGHTS = {720, 1080, 1440, 2160};
        public static final int[] FPS_STEPS = {30, 60, 90, 120};
        public static final int[] BITRATE_STEPS = {10, 15, 20, 30, 40, 60, 80, 100, 120};

        private int resIndex;
        private int fpsIndex;
        private int bitrateIndex;
        private int focusSlot;
        private final int tvWidth;
        private final int tvHeight;
        private final int tvHz;
        private final int pcWidth;
        private final int pcHeight;
        private final int pcHz;
        private final String networkType;
        private final int wifiLinkMbps;

        public StreamQualityModalState(
                String initialResolution,
                int initialFps,
                int initialBitrateMbps,
                int tvWidth,
                int tvHeight,
                int tvHz,
                int pcWidth,
                int pcHeight,
                int pcHz,
                String networkType,
                int wifiLinkMbps) {
            this.resIndex = findResolutionIndex(initialResolution);
            this.fpsIndex = findNearestIndex(FPS_STEPS, initialFps, 1);
            this.bitrateIndex = findNearestIndex(BITRATE_STEPS, initialBitrateMbps, 3);
            this.focusSlot = SLOT_RES;
            this.tvWidth = Math.max(0, tvWidth);
            this.tvHeight = Math.max(0, tvHeight);
            this.tvHz = Math.max(0, tvHz);
            this.pcWidth = Math.max(0, pcWidth);
            this.pcHeight = Math.max(0, pcHeight);
            this.pcHz = Math.max(0, pcHz);
            this.networkType = networkType != null ? networkType.trim() : "";
            this.wifiLinkMbps = Math.max(0, wifiLinkMbps);
        }

        public static String formatResolutionShort(String res) {
            if ("720p".equalsIgnoreCase(res)) return "720p";
            if ("1440p".equalsIgnoreCase(res)) return "1440p";
            if ("4k".equalsIgnoreCase(res)) return "4K";
            return "1080p";
        }

        public static String formatCompactSummary(String res, int fps, int bitrateMbps) {
            return formatResolutionShort(res) + " \u00B7 " + fps + " FPS \u00B7 " + bitrateMbps + "M";
        }

        private static int findResolutionIndex(String res) {
            if (res != null) {
                for (int i = 0; i < RES_KEYS.length; i++) {
                    if (RES_KEYS[i].equalsIgnoreCase(res.trim())) return i;
                }
            }
            return 1; // 1080p default
        }

        private static int findNearestIndex(int[] steps, int target, int fallbackIdx) {
            if (target <= 0) return fallbackIdx;
            int best = 0;
            int bestDist = Math.abs(steps[0] - target);
            for (int i = 1; i < steps.length; i++) {
                int d = Math.abs(steps[i] - target);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return best;
        }

        public int getWifiSafeCeilingMbps() {
            if ("Wi-Fi".equalsIgnoreCase(networkType) && wifiLinkMbps > 0) {
                return Math.max(15, (wifiLinkMbps * 45) / 100);
            }
            return 0;
        }

        private int clampBitrateToNetwork(int targetMbps) {
            int safeCeiling = getWifiSafeCeilingMbps();
            int capped = (safeCeiling > 0 && targetMbps > safeCeiling) ? safeCeiling : targetMbps;
            int best = BITRATE_STEPS[0];
            for (int step : BITRATE_STEPS) {
                if (step <= capped) {
                    best = step;
                } else if (safeCeiling <= 0 && Math.abs(step - capped) < Math.abs(best - capped)) {
                    best = step;
                }
            }
            return best;
        }

        public String getResolution() { return RES_KEYS[resIndex]; }
        public String getResolutionLabel() { return RES_LABELS[resIndex]; }
        public String getResolutionDims() { return RES_DIMS[resIndex]; }
        public int getResolutionIndex() { return resIndex; }
        public int getFps() { return FPS_STEPS[fpsIndex]; }
        public int getFpsIndex() { return fpsIndex; }
        public int getBitrateMbps() { return BITRATE_STEPS[bitrateIndex]; }
        public int getBitrateIndex() { return bitrateIndex; }
        public int getFocusSlot() { return focusSlot; }

        public void setFocusSlot(int slot) {
            this.focusSlot = Math.max(SLOT_RES, Math.min(SLOT_BITRATE, slot));
        }

        public boolean canStepUp(int col) {
            if (col == SLOT_RES) return resIndex < RES_KEYS.length - 1;
            if (col == SLOT_FPS) return fpsIndex < FPS_STEPS.length - 1;
            if (col == SLOT_BITRATE) return bitrateIndex < BITRATE_STEPS.length - 1;
            return false;
        }

        public boolean canStepDown(int col) {
            if (col == SLOT_RES) return resIndex > 0;
            if (col == SLOT_FPS) return fpsIndex > 0;
            if (col == SLOT_BITRATE) return bitrateIndex > 0;
            return false;
        }

        public boolean stepColumn(int col, int delta) {
            if (col == SLOT_RES) {
                int next = Math.max(0, Math.min(RES_KEYS.length - 1, resIndex + delta));
                if (next != resIndex) { resIndex = next; return true; }
            } else if (col == SLOT_FPS) {
                int next = Math.max(0, Math.min(FPS_STEPS.length - 1, fpsIndex + delta));
                if (next != fpsIndex) { fpsIndex = next; return true; }
            } else if (col == SLOT_BITRATE) {
                int next = Math.max(0, Math.min(BITRATE_STEPS.length - 1, bitrateIndex + delta));
                if (next != bitrateIndex) { bitrateIndex = next; return true; }
            }
            return false;
        }

        public boolean switchColumn(int delta) {
            int next = Math.max(SLOT_RES, Math.min(SLOT_BITRATE, focusSlot + delta));
            if (next != focusSlot) {
                focusSlot = next;
                return true;
            }
            return false;
        }

        /**
         * Routes TV remote D-pad and gaming controller directional inputs across the 3 side-by-side
         * columns (Left/Right switches column, Up/Down adjusts value).
         */
        public boolean moveFocus(int dx, int dy) {
            if (dy < 0) return stepColumn(focusSlot, 1);
            if (dy > 0) return stepColumn(focusSlot, -1);
            if (dx > 0) return switchColumn(1);
            if (dx < 0) return switchColumn(-1);
            return false;
        }

        public int getResolutionToneForIndex(int idx) {
            if (idx < 0 || idx >= RES_WIDTHS.length) return TONE_BALANCED;
            int w = RES_WIDTHS[idx];
            int h = RES_HEIGHTS[idx];
            boolean hasPc = pcWidth > 0 && pcHeight > 0;
            boolean hasTv = tvWidth > 0 && tvHeight > 0;
            if (hasTv && w > tvWidth && h > tvHeight) return TONE_CAUTION;
            if (hasPc && pcWidth == w && pcHeight == h) return TONE_RECOMMENDED;
            if (!hasPc && hasTv && tvWidth == w && tvHeight == h) return TONE_RECOMMENDED;
            return TONE_BALANCED;
        }

        public int getResolutionTone() {
            return getResolutionToneForIndex(resIndex);
        }

        public String getResolutionHint() {
            int w = RES_WIDTHS[resIndex];
            int h = RES_HEIGHTS[resIndex];
            boolean hasPc = pcWidth > 0 && pcHeight > 0;
            boolean hasTv = tvWidth > 0 && tvHeight > 0;
            if (hasTv && w > tvWidth && h > tvHeight) {
                return "Exceeds TV " + formatResolutionShort(RES_KEYS[findNearestIndex(RES_WIDTHS, tvWidth, 1)]);
            }
            if (hasPc && pcWidth == w && pcHeight == h) {
                return "Matches PC " + RES_LABELS[resIndex];
            }
            if (!hasPc && hasTv && tvWidth == w && tvHeight == h) {
                return "Native TV " + RES_LABELS[resIndex];
            }
            if (hasPc && w > pcWidth) {
                return (hasTv && w == tvWidth) ? ("TV " + RES_LABELS[resIndex] + " \u00B7 upscales PC") : ("Above PC " + pcWidth + "p");
            }
            if (resIndex == 0) return "Lowest bandwidth";
            if (resIndex == 1) return "Balanced Full HD";
            if (resIndex == 2) return "Crisp QHD detail";
            return "Ultra HD clarity";
        }

        public int getFpsToneForIndex(int idx) {
            if (idx < 0 || idx >= FPS_STEPS.length) return TONE_BALANCED;
            int fps = FPS_STEPS[idx];
            if (tvHz > 0) {
                if (fps > tvHz + 2) return TONE_CAUTION;
                if (Math.abs(fps - tvHz) <= 2) return TONE_RECOMMENDED;
                return TONE_BALANCED;
            }
            return fps == 60 ? TONE_RECOMMENDED : TONE_BALANCED;
        }

        public int getFpsTone() {
            return getFpsToneForIndex(fpsIndex);
        }

        public String getFpsHint() {
            int fps = FPS_STEPS[fpsIndex];
            if (tvHz > 0) {
                if (fps > tvHz + 2) return "Exceeds TV " + tvHz + "Hz";
                if (Math.abs(fps - tvHz) <= 2) {
                    if (pcHz > 0 && Math.abs(fps - pcHz) <= 2) {
                        return "Matches TV & PC " + tvHz + "Hz";
                    }
                    return "Matches TV " + tvHz + "Hz";
                }
                if (fps == 30) return "Half rate \u00B7 low load";
                return "Within TV " + tvHz + "Hz";
            }
            if (fps == 30) return "Low bandwidth";
            if (fps == 60) return "Standard smooth";
            return "For " + fps + "Hz+ panels";
        }

        public int getRecommendedBitrateForCurrentCombo() {
            int baseRec = SettingsTree.computeRecommendedBitrateMbps(getResolution(), getFps());
            return clampBitrateToNetwork(baseRec);
        }

        public int getBitrateToneForIndex(int idx) {
            if (idx < 0 || idx >= BITRATE_STEPS.length) return TONE_BALANCED;
            int mbps = BITRATE_STEPS[idx];
            int safeWifi = getWifiSafeCeilingMbps();
            if (safeWifi > 0 && mbps > safeWifi) return TONE_CAUTION;
            int target = getRecommendedBitrateForCurrentCombo();
            if (mbps == target) return TONE_RECOMMENDED;
            int targetIdx = findNearestIndex(BITRATE_STEPS, target, 3);
            if (idx < targetIdx - 1) return TONE_CAUTION;
            return TONE_BALANCED;
        }

        public int getBitrateTone() {
            return getBitrateToneForIndex(bitrateIndex);
        }

        public String getBitrateHint() {
            int mbps = BITRATE_STEPS[bitrateIndex];
            int safeWifi = getWifiSafeCeilingMbps();
            if (safeWifi > 0 && mbps > safeWifi) {
                return "High for " + wifiLinkMbps + "M Wi-Fi";
            }
            int target = getRecommendedBitrateForCurrentCombo();
            String resLbl = getResolutionLabel();
            if (mbps == target) {
                return "Ideal for " + resLbl + " " + getFps();
            }
            int targetIdx = findNearestIndex(BITRATE_STEPS, target, 3);
            if (bitrateIndex < targetIdx - 1) {
                return "Low for " + resLbl + " (rec " + target + "M)";
            }
            if (bitrateIndex == targetIdx - 1 || bitrateIndex == targetIdx + 1) {
                return "Good for " + resLbl + " " + getFps();
            }
            return "Extra clarity \u00B7 high load";
        }

        public int getOverallTone() {
            int r = getResolutionTone();
            int f = getFpsTone();
            int b = getBitrateTone();
            if (r == TONE_CAUTION || f == TONE_CAUTION || b == TONE_CAUTION) {
                return TONE_CAUTION;
            }
            if (f == TONE_RECOMMENDED && b == TONE_RECOMMENDED) {
                return TONE_RECOMMENDED;
            }
            return TONE_BALANCED;
        }

        public String getOverallBannerText() {
            if (getFpsTone() == TONE_CAUTION) {
                return "Heads up \u00B7 " + getFpsHint();
            }
            if (getBitrateTone() == TONE_CAUTION) {
                return "Heads up \u00B7 " + getBitrateHint();
            }
            if (getResolutionTone() == TONE_CAUTION) {
                return "Heads up \u00B7 " + getResolutionHint();
            }
            if (getOverallTone() == TONE_RECOMMENDED) {
                return "Optimal Match \u00B7 " + getFpsHint() + " \u00B7 " + getBitrateHint();
            }
            return formatCompactSummary(getResolution(), getFps(), getBitrateMbps()) + " \u00B7 " + getBitrateHint();
        }

        public String getHardwareTelemetryLabel() {
            StringBuilder sb = new StringBuilder();
            if (tvWidth > 0 && tvHeight > 0) {
                sb.append("TV ").append(formatResolutionShort(RES_KEYS[findNearestIndex(RES_WIDTHS, tvWidth, 1)]));
                if (tvHz > 0) sb.append(' ').append(tvHz).append("Hz");
            } else if (tvHz > 0) {
                sb.append("TV ").append(tvHz).append("Hz");
            }
            if (pcWidth > 0 && pcHeight > 0) {
                if (sb.length() > 0) sb.append(" \u00B7 ");
                sb.append("PC ").append(formatResolutionShort(RES_KEYS[findNearestIndex(RES_WIDTHS, pcWidth, 1)]));
                if (pcHz > 0) sb.append(' ').append(pcHz).append("Hz");
            }
            if ("Ethernet".equalsIgnoreCase(networkType)) {
                if (sb.length() > 0) sb.append(" \u00B7 ");
                sb.append("Ethernet");
            } else if ("Wi-Fi".equalsIgnoreCase(networkType) && wifiLinkMbps > 0) {
                if (sb.length() > 0) sb.append(" \u00B7 ");
                sb.append("Wi-Fi ").append(wifiLinkMbps).append("M");
            }
            return sb.toString();
        }
    }

    /**
     * Pure-Java interactive state model for the Liquid Glass Up/Down Sleep Timer modal.
     *
     * <p><b>Why:</b> Isolates D-pad navigation, smart duration preset stepping, unlimited extension
     * past 120 minutes, quick {@code +5 min} additions, and On/Off memory so every remote interaction
     * is 100% deterministic and Desktop JVM unit-testable.
     */
    public static final class SleepTimerModalState {
        public static final int SLOT_TOGGLE = 0;
        public static final int SLOT_DURATION = 1;
        public static final int SLOT_ADD_FIVE = 2;
        public static final int SLOT_SAVE = 3;
        public static final int SLOT_CANCEL = 4;

        private int minutes;
        private int lastPositiveMinutes;
        private int focusSlot;

        public SleepTimerModalState(int initialMinutes) {
            this.minutes = Math.max(0, Math.min(SleepTimer.MAX_MINUTES, initialMinutes));
            this.lastPositiveMinutes = this.minutes > 0 ? this.minutes : 30;
            this.focusSlot = SLOT_DURATION;
        }

        public int getMinutes() {
            return minutes;
        }

        public int getFocusSlot() {
            return focusSlot;
        }

        public void setFocusSlot(int slot) {
            this.focusSlot = Math.max(SLOT_TOGGLE, Math.min(SLOT_CANCEL, slot));
        }

        public boolean isEnabled() {
            return minutes > 0;
        }

        public void toggleEnabled() {
            if (minutes > 0) {
                lastPositiveMinutes = minutes;
                minutes = 0;
            } else {
                minutes = Math.max(10, lastPositiveMinutes);
            }
        }

        public void stepUp() {
            minutes = SleepTimer.nextDurationUp(minutes);
            if (minutes > 0) lastPositiveMinutes = minutes;
        }

        public void stepDown() {
            minutes = SleepTimer.nextDurationDown(minutes);
            if (minutes > 0) lastPositiveMinutes = minutes;
        }

        public void addFiveMinutes() {
            minutes = SleepTimer.addMinutes(minutes, SleepTimer.FINE_ADD_MINUTES);
            if (minutes > 0) lastPositiveMinutes = minutes;
        }

        /**
         * Routes D-pad movement inside the Sleep Timer modal:
         * <ul>
         *   <li>On {@link #SLOT_DURATION}: Up/Down step duration through smart intervals and beyond;
         *       Left/Right switch to On/Off toggle or {@code +5 min}.</li>
         *   <li>On {@link #SLOT_TOGGLE}: Up/Down toggle On/Off (or Down moves to Save); Right moves to Duration.</li>
         *   <li>On {@link #SLOT_ADD_FIVE}: Left moves to Duration; Down moves to Cancel.</li>
         *   <li>On {@link #SLOT_SAVE}/{@link #SLOT_CANCEL}: Left/Right swap buttons; Up returns to Duration.</li>
         * </ul>
         */
        public void moveFocus(int dx, int dy) {
            if (focusSlot == SLOT_DURATION) {
                if (dy < 0) {
                    stepUp();
                } else if (dy > 0) {
                    stepDown();
                } else if (dx < 0) {
                    focusSlot = SLOT_TOGGLE;
                } else if (dx > 0) {
                    focusSlot = SLOT_ADD_FIVE;
                }
            } else if (focusSlot == SLOT_TOGGLE) {
                if (dx > 0) {
                    focusSlot = SLOT_DURATION;
                } else if (dy > 0) {
                    focusSlot = SLOT_SAVE;
                } else if (dy < 0) {
                    toggleEnabled();
                }
            } else if (focusSlot == SLOT_ADD_FIVE) {
                if (dx < 0) {
                    focusSlot = SLOT_DURATION;
                } else if (dy > 0) {
                    focusSlot = SLOT_CANCEL;
                }
            } else if (focusSlot == SLOT_SAVE) {
                if (dx > 0) {
                    focusSlot = SLOT_CANCEL;
                } else if (dy < 0) {
                    focusSlot = SLOT_DURATION;
                }
            } else if (focusSlot == SLOT_CANCEL) {
                if (dx < 0) {
                    focusSlot = SLOT_SAVE;
                } else if (dy < 0) {
                    focusSlot = SLOT_DURATION;
                }
            }
        }
    }

    /**
     * Pure-Java interactive state model for the Liquid Glass Up/Down Screensaver Slide Duration modal.
     *
     * <p><b>Why:</b> Replaces a flat closed submenu with an effortless cyclic Up/Down duration wheel
     * spanning {@code "Don't change image"} ({@code 0}) through {@code 2h} and wrapping back to {@code 0}.
     */
    public static final class SlideDurationModalState {
        private long intervalMs;

        public SlideDurationModalState(long initialMs) {
            this.intervalMs = Math.max(0L, initialMs);
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public int getStepIndex() {
            return Prefs.saverIntervalIndex(intervalMs);
        }

        public int getTotalSteps() {
            return Prefs.SAVER_INTERVALS_MS.length;
        }

        public boolean isStaticMode() {
            return intervalMs <= 0L;
        }

        public String getLabel() {
            return Prefs.formatSaverIntervalLabel(intervalMs);
        }

        public String getNextLabel() {
            return Prefs.formatSaverIntervalLabel(Prefs.nextSaverIntervalUp(intervalMs));
        }

        public String getPrevLabel() {
            return Prefs.formatSaverIntervalLabel(Prefs.nextSaverIntervalDown(intervalMs));
        }

        public String getCaption() {
            if (intervalMs <= 0L) {
                return "Photo stays fixed until you press Left or Right";
            }
            return "Switches to a new 4K photo every " + getLabel();
        }

        public void stepUp() {
            intervalMs = Prefs.nextSaverIntervalUp(intervalMs);
        }

        public void stepDown() {
            intervalMs = Prefs.nextSaverIntervalDown(intervalMs);
        }

        /**
         * Routes TV remote D-pad and gaming controller directional inputs:
         * Up (or Right) advances to the next duration (wrapping 2h -> 0);
         * Down (or Left) steps to the previous duration (wrapping 0 -> 2h).
         */
        public boolean moveFocus(int dx, int dy) {
            if (dy < 0 || dx > 0) {
                stepUp();
                return true;
            }
            if (dy > 0 || dx < 0) {
                stepDown();
                return true;
            }
            return false;
        }
    }

    /**
     * Pure state model for Canvas-rendered Liquid Glass text, PIN, and Vault passphrase modals ({@code INC-04}).
     */
    public static class LiquidGlassInputState {
        private String text;
        private final boolean secretMode;
        private boolean revealed;

        public LiquidGlassInputState(String initialText, boolean secretMode) {
            this.text = initialText != null ? initialText : "";
            this.secretMode = secretMode;
            this.revealed = false;
        }

        public void setText(String next) {
            this.text = next != null ? next : "";
        }

        public String getText() {
            return text;
        }

        public boolean isSecretMode() {
            return secretMode;
        }

        public boolean isRevealed() {
            return revealed;
        }

        public void toggleReveal() {
            if (secretMode) {
                revealed = !revealed;
            }
        }

        public void appendChar(char ch) {
            if (ch < 32 || text.length() >= 128) return;
            text = text + ch;
        }

        public void backspace() {
            if (!text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
            }
        }

        public String displayValue() {
            if (text.isEmpty()) return "";
            if (!secretMode || revealed) return text;
            char[] bullets = new char[text.length()];
            Arrays.fill(bullets, '\u2022');
            return new String(bullets);
        }
    }

    // Lazily initialized hit-test & layout RectFs (zero per-frame allocations; never allocated in constructor)
    private RectF lgPanelRect;
    private RectF lgInputBoxRect;
    private RectF lgRevealBtnRect;
    private RectF lgConfirmBtnRect;
    private RectF lgCancelBtnRect;
    private RectF[] lgVisibleChoiceRects;

    private RectF pairingPanelRect;
    private RectF pairingQrCardRect;
    private RectF pairingQrBitmapRect;
    private RectF[] pairingActionRects;
    private Paint monoCodePaint;

    private RectF gameLaunchPanelRect;
    private RectF gameLaunchSpinnerOuterRect;
    private RectF gameLaunchSpinnerInnerRect;
    private Paint gameLaunchArcPaint;

    private RectF sleepPanelRect;
    private RectF sleepToggleRect;
    private RectF sleepWheelRect;
    private RectF sleepUpArrowRect;
    private RectF sleepDownArrowRect;
    private RectF sleepAddFiveRect;
    private RectF sleepSaveBtnRect;
    private RectF sleepCancelBtnRect;
    private RectF sleepSwitchPillRect;

    private RectF sqPanelRect;
    private RectF[] sqColRects;
    private RectF[] sqUpRects;
    private RectF[] sqDownRects;
    private RectF sqScratchRect;

    private RectF sdPanelRect;
    private RectF sdCardRect;
    private RectF sdUpArrowRect;
    private RectF sdDownArrowRect;
    private RectF sdScratchRect;

    /**
     * Creates a new {@link ModalOverlayRenderer} safe for headless Desktop JVM instantiation.
     */
    public ModalOverlayRenderer() {
    }

    private void ensureRectsInitialized() {
        if (lgPanelRect == null) {
            lgPanelRect = new RectF();
            lgInputBoxRect = new RectF();
            lgRevealBtnRect = new RectF();
            lgConfirmBtnRect = new RectF();
            lgCancelBtnRect = new RectF();
            lgVisibleChoiceRects = new RectF[] {
                    new RectF(), new RectF(), new RectF(), new RectF(),
                    new RectF(), new RectF(), new RectF(), new RectF()
            };
            pairingPanelRect = new RectF();
            pairingQrCardRect = new RectF();
            pairingQrBitmapRect = new RectF();
            pairingActionRects = new RectF[] {
                    new RectF(), new RectF(), new RectF(), new RectF()
            };
            gameLaunchPanelRect = new RectF();
            gameLaunchSpinnerOuterRect = new RectF();
            gameLaunchSpinnerInnerRect = new RectF();
            sleepPanelRect = new RectF();
            sleepToggleRect = new RectF();
            sleepWheelRect = new RectF();
            sleepUpArrowRect = new RectF();
            sleepDownArrowRect = new RectF();
            sleepAddFiveRect = new RectF();
            sleepSaveBtnRect = new RectF();
            sleepCancelBtnRect = new RectF();
            sleepSwitchPillRect = new RectF();
            sqPanelRect = new RectF();
            sqColRects = new RectF[] {new RectF(), new RectF(), new RectF()};
            sqUpRects = new RectF[] {new RectF(), new RectF(), new RectF()};
            sqDownRects = new RectF[] {new RectF(), new RectF(), new RectF()};
            sqScratchRect = new RectF();
            sdPanelRect = new RectF();
            sdCardRect = new RectF();
            sdUpArrowRect = new RectF();
            sdDownArrowRect = new RectF();
            sdScratchRect = new RectF();
        }
    }

    /**
     * Computes the scaled pixel height of the Hold-OK context menu / Smart-Home control card.
     *
     * @param optionCount Number of rows in the menu.
     * @param hasSubtitle Whether a subtitle line is displayed under the title.
     * @param k           Screen scale factor ({@code h / 1080f}).
     * @return Total menu height in pixels.
     */
    public float computeMenuHeight(int optionCount, boolean hasSubtitle, float k) {
        return (MENU_PAD + 34f + (hasSubtitle ? 38f : 0f) + 32f
                + Math.max(0, optionCount - 1) * MENU_STEP + MENU_ROW + MENU_INSET) * k;
    }

    /**
     * Populates {@code outPanel} for the Hold-OK menu and returns the Y coordinate of row 0.
     */
    float layoutMenu(int w, int h, float k, int optionCount, boolean hasSubtitle, RectF outPanel) {
        float rowsH = computeMenuHeight(optionCount, hasSubtitle, k);
        float left = (w - MENU_W * k) / 2f;
        float top = (h - rowsH) / 2f;
        outPanel.set(left, top, left + MENU_W * k, top + rowsH);
        return top + (MENU_PAD + 34f + (hasSubtitle ? 38f : 0f) + 32f) * k;
    }

    /**
     * Populates {@code outRow} with the bounds of Hold-OK menu row {@code index}.
     */
    void menuRow(int index, float k, RectF menuPanel, float menuOptionsTop, RectF outRow) {
        float y = menuOptionsTop + index * MENU_STEP * k;
        outRow.set(menuPanel.left + MENU_INSET * k, y, menuPanel.right - MENU_INSET * k, y + MENU_ROW * k);
    }

    /**
     * Hit-tests pointer coordinates against Hold-OK menu rows, returning {@code 0..optionCount-1} or {@code -1}.
     */
    int menuAt(float x, float y, int optionCount, float k, RectF menuPanel, float menuOptionsTop, RectF scratchBox) {
        for (int i = 0; i < optionCount; i++) {
            menuRow(i, k, menuPanel, menuOptionsTop, scratchBox);
            if (scratchBox.contains(x, y)) return i;
        }
        return -1;
    }

    /**
     * Builds the horizontal QR code + extra-large activation code {@link LinearLayout} used inside the
     * Trakt OAuth pairing dialog.
     */
    LinearLayout buildTraktOAuthDialogView(Activity activity, String activateUrl, String code, float k) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(28 * k);
        container.setPadding(pad, pad, pad, pad);

        Bitmap qrBmp = Qr.encode(activateUrl, Math.round(280 * k), 2, 0xFF000000, 0xFFFFFFFF);
        if (qrBmp != null) {
            ImageView qrView = new ImageView(activity);
            qrView.setImageBitmap(qrBmp);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                    Math.round(280 * k), Math.round(280 * k));
            qrLp.rightMargin = Math.round(32 * k);
            container.addView(qrView, qrLp);
        }

        LinearLayout rightCol = new LinearLayout(activity);
        rightCol.setOrientation(LinearLayout.VERTICAL);

        TextView step1View = new TextView(activity);
        step1View.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21 * k);
        step1View.setTextColor(0xFFCCCCCC);
        step1View.setText(Ui.tr("1. Scan the QR code with your phone or visit:\n") + activateUrl + "\n\n"
                + Ui.tr("2. Enter this activation code on your phone:"));
        rightCol.addView(step1View);

        TextView codeView = new TextView(activity);
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 54 * k);
        codeView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        codeView.setTextColor(0xFFD1D1D6);
        codeView.setLetterSpacing(0.14f);
        codeView.setPadding(0, Math.round(14 * k), 0, Math.round(14 * k));
        codeView.setText(code);
        rightCol.addView(codeView);

        TextView hintView = new TextView(activity);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19 * k);
        hintView.setTextColor(0xFFD1D1D6);
        hintView.setText(Ui.tr("This window will close automatically as soon as you approve on your phone."));
        rightCol.addView(hintView);

        container.addView(rightCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return container;
    }

    /**
     * Renders the cancellable Liquid Glass Sync Waiting modal onto {@code toastLayer}.
     */
    void drawSyncModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            RectF screenRect,
            String syncModalTitle,
            String syncModalStep,
            int syncModalProgress,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            float deepBlurVirtual,
            Paint fill,
            Paint border,
            Paint shadowDeep,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            Shadows shadows,
            RectF tmp,
            RectF syncCancelRect) {
        fill.setShader(null);
        fill.setColor(0xD80A0C12);
        c.drawRect(screenRect, fill);

        float mw = 840 * k;
        float mh = 390 * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        float mr = 34 * k;
        tmp.set(mx, my + 18 * k, mx + mw, my + mh + 18 * k);
        shadowDeep.setColor(0x88000000);
        shadows.draw(c, tmp, mr, deepBlurVirtual * k, shadowDeep);

        tmp.set(mx, my, mx + mw, my + mh);
        fill.setColor(isDark ? 0xF21C1C1E : 0xF8FFFFFF);
        c.drawRoundRect(tmp, mr, mr, fill);
        border.setColor(isDark ? 0x28FFFFFF : 0x1E000000);
        c.drawRoundRect(tmp, mr, mr, border);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xFFD1D1D6 : lDimColor;

        Symbols.draw(ctx, c, "sync", mx + 58 * k, my + 66 * k, 32 * k, subInk);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(30 * k);
        fitPaint.setColor(ink);
        String tStr = TextUtils.ellipsize(syncModalTitle, fitPaint, mw - 120 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(tStr, mx + 94 * k, my + 76 * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(22 * k);
        fitPaint.setColor(subInk);
        String sStr = TextUtils.ellipsize(syncModalStep, fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(sStr, mx + 48 * k, my + 136 * k, fitPaint);

        float barX = mx + 48 * k;
        float barY = my + 182 * k;
        float barW = mw - 96 * k;
        float barH = 18 * k;
        tmp.set(barX, barY, barX + barW, barY + barH);
        fill.setColor(isDark ? 0x26FFFFFF : 0x1E000000);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, fill);

        float pctFrac = Ui.clamp(syncModalProgress / 100f);
        tmp.set(barX, barY, barX + Math.max(barH, barW * pctFrac), barY + barH);
        fill.setColor(isDark ? 0xFFD1D1D6 : 0xFF48484A);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, fill);

        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(20 * k);
        fitPaint.setColor(subInk);
        String pctText = syncModalProgress + "%";
        c.drawText(pctText, barX + barW - fitPaint.measureText(pctText), barY + 46 * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(19 * k);
        fitPaint.setColor(subInk);
        c.drawText(Ui.tr("Press OK or Back anytime to continue in background"), barX, barY + 46 * k, fitPaint);

        float btnW = 360 * k;
        float btnH = 62 * k;
        float btnX = mx + (mw - btnW) / 2f;
        float btnY = my + mh - btnH - 34 * k;
        syncCancelRect.set(btnX, btnY, btnX + btnW, btnY + btnH);
        fill.setColor(ink);
        c.drawRoundRect(syncCancelRect, btnH / 2f, btnH / 2f, fill);

        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(22 * k);
        fitPaint.setColor(isDark ? lInkColor : whiteColor);
        String btnLabel = Ui.tr("Cancel (Continue in Background)");
        float bw = fitPaint.measureText(btnLabel);
        c.drawText(btnLabel, syncCancelRect.centerX() - bw / 2f, syncCancelRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
    }

    /**
     * Computes the inner counter-rotating orbital spinner angle in degrees from the outer phase.
     *
     * <p><b>Why:</b> Using a non-integer multiplier (such as {@code 1.35f}) against a 0..360 wrapped
     * outer angle causes a 126-degree backwards snap every revolution. An exact integer harmonic
     * ({@code -2x}) guarantees identical angle and angular velocity across the 360 -> 0 boundary.
     */
    static float computeInnerSpinnerAngleDeg(float outerPhaseDeg) {
        float raw = (-outerPhaseDeg * 2f + 150f) % 360f;
        return raw < 0f ? raw + 360f : raw;
    }

    /**
     * Renders the 60fps animated Liquid Glass Game Launch Modal onto {@code toastLayer} with dual
     * counter-rotating orbital spinner arcs, procedural stage icon, explanatory step headline &amp; subtitle,
     * 4-stage progress dots, smooth gliding progress bar, and Cancel action pill (zero per-frame heap allocations).
     */
    void drawGameLaunchModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            RectF screenRect,
            String gameTitle,
            String stepHeadline,
            String stepSubtitle,
            String iconName,
            int stageIndex,
            float displayProgress,
            float spinnerPhaseDeg,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint,
            RectF tmp,
            RectF outCancelRect
    ) {
        ensureRectsInitialized();
        if (gameLaunchArcPaint == null) {
            gameLaunchArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gameLaunchArcPaint.setStyle(Paint.Style.STROKE);
            gameLaunchArcPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        fill.setShader(null);
        fill.setColor(0xDC060913);
        c.drawRect(screenRect, fill);

        float mw = 880f * k;
        float mh = 446f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        float mr = 38f * k;
        gameLaunchPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, gameLaunchPanelRect, mr, glassBaseColor, glassBlurMix, 0xFF0EA5E9, 0.18f, 0.03f, 1f);
        border.setColor(isDark ? 0x3CFFFFFF : 0x2A000000);
        c.drawRoundRect(gameLaunchPanelRect, mr, mr, border);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xCFD8E6F5 : lDimColor;
        int accent = isDark ? 0xFF38BDF8 : 0xFF0284C7;
        int accentSecondary = isDark ? 0xFFA78BFA : 0xFF6D28D9;

        // 1. 60fps Dual Counter-Rotating Orbital Spinner + Centered Stage Vector Icon
        float cx = mx + mw / 2f;
        float cy = my + 88f * k;
        float rOuter = 42f * k;
        float rInner = 31f * k;
        gameLaunchSpinnerOuterRect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter);
        gameLaunchSpinnerInnerRect.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner);

        gameLaunchArcPaint.setStrokeWidth(3.5f * k);
        gameLaunchArcPaint.setColor(isDark ? 0x22FFFFFF : 0x18000000);
        c.drawOval(gameLaunchSpinnerOuterRect, gameLaunchArcPaint);

        gameLaunchArcPaint.setStrokeWidth(4.5f * k);
        gameLaunchArcPaint.setColor(accent);
        c.drawArc(gameLaunchSpinnerOuterRect, spinnerPhaseDeg, 114f, false, gameLaunchArcPaint);

        gameLaunchArcPaint.setStrokeWidth(3.2f * k);
        gameLaunchArcPaint.setColor(accentSecondary);
        float innerStart = computeInnerSpinnerAngleDeg(spinnerPhaseDeg);
        c.drawArc(gameLaunchSpinnerInnerRect, innerStart, 78f, false, gameLaunchArcPaint);

        String safeIcon = (iconName != null && !iconName.isEmpty()) ? iconName : "controller";
        Symbols.draw(ctx, c, safeIcon, cx, cy, 27f * k, ink);

        // 2. Target Game Eyebrow + Step Headline + Explanatory Subtitle
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(18.5f * k);
        fitPaint.setColor(accent);
        String safeGame = (gameTitle != null && !gameTitle.isEmpty()) ? gameTitle : "Gaming PC";
        String eyebrow = TextUtils.ellipsize(
                ("GAMING ZONE  ·  " + safeGame.toUpperCase(java.util.Locale.ROOT)),
                fitPaint,
                mw - 120f * k,
                TextUtils.TruncateAt.END
        ).toString();
        c.drawText(eyebrow, cx - fitPaint.measureText(eyebrow) / 2f, my + 164f * k, fitPaint);

        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(31f * k);
        fitPaint.setColor(ink);
        String head = TextUtils.ellipsize(
                stepHeadline != null ? stepHeadline : "",
                fitPaint,
                mw - 104f * k,
                TextUtils.TruncateAt.END
        ).toString();
        c.drawText(head, cx - fitPaint.measureText(head) / 2f, my + 206f * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(21f * k);
        fitPaint.setColor(subInk);
        String sub = TextUtils.ellipsize(
                stepSubtitle != null ? stepSubtitle : "",
                fitPaint,
                mw - 104f * k,
                TextUtils.TruncateAt.END
        ).toString();
        c.drawText(sub, cx - fitPaint.measureText(sub) / 2f, my + 244f * k, fitPaint);

        // 3. Smooth Gliding Progress Bar + 4 Stage Progress Dots
        float barX = mx + 68f * k;
        float barY = my + 278f * k;
        float barW = mw - 136f * k;
        float barH = 14f * k;
        tmp.set(barX, barY, barX + barW, barY + barH);
        fill.setColor(isDark ? 0x26FFFFFF : 0x1E000000);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, fill);

        float pctFrac = Ui.clamp(displayProgress / 100f);
        tmp.set(barX, barY, barX + Math.max(barH, barW * pctFrac), barY + barH);
        fill.setColor(accent);
        c.drawRoundRect(tmp, barH / 2f, barH / 2f, fill);

        float dotY = barY + 38f * k;
        float dotR = 5.5f * k;
        float dotGap = 20f * k;
        for (int i = 0; i < 4; i++) {
            float dx = barX + dotR + i * dotGap;
            fill.setColor(i <= stageIndex ? accent : (isDark ? 0x33FFFFFF : 0x24000000));
            c.drawCircle(dx, dotY, i == stageIndex ? dotR * 1.22f : dotR, fill);
        }

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(18.5f * k);
        fitPaint.setColor(subInk);
        String stepCounter = Ui.tr("Step ") + Math.min(4, Math.max(1, stageIndex + 1)) + " / 4";
        c.drawText(stepCounter, barX + 4 * dotGap + 8f * k, dotY + Ui.halfCap(fitPaint), fitPaint);

        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(19.5f * k);
        fitPaint.setColor(ink);
        String pctText = Math.max(0, Math.min(100, Math.round(displayProgress))) + "%";
        c.drawText(pctText, barX + barW - fitPaint.measureText(pctText), dotY + Ui.halfCap(fitPaint), fitPaint);

        // 4. Cancel Pill
        float btnW = 248f * k;
        float btnH = 56f * k;
        float btnX = mx + (mw - btnW) / 2f;
        float btnY = my + mh - btnH - 28f * k;
        outCancelRect.set(btnX, btnY, btnX + btnW, btnY + btnH);
        fill.setColor(isDark ? 0x28FFFFFF : 0x18000000);
        c.drawRoundRect(outCancelRect, btnH / 2f, btnH / 2f, fill);
        border.setColor(isDark ? 0x44FFFFFF : 0x2E000000);
        c.drawRoundRect(outCancelRect, btnH / 2f, btnH / 2f, border);

        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(21f * k);
        fitPaint.setColor(ink);
        String cancelLabel = Ui.tr("Cancel");
        c.drawText(
                cancelLabel,
                outCancelRect.centerX() - fitPaint.measureText(cancelLabel) / 2f,
                outCancelRect.centerY() + Ui.halfCap(fitPaint),
                fitPaint
        );
    }

    /**
     * Renders the Apple-TV Liquid Glass {@code "New Remote Detected"} Pairing Platter on {@code toastLayer}
     * with a cached scannable QR code on the left, extra-large 6-digit code on the right, and 4 selectable
     * Liquid Glass buttons ({@code Guest 30d}, {@code Household}, {@code Admin}, {@code Dismiss}) below.
     *
     * <p>Zero {@link Bitmap} or {@link RectF} objects are allocated during {@code onDraw}: {@code cachedQrBitmap}
     * is generated once per {@code requestId} on {@link HomeView}.
     */
    void drawPairingModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            RemoteAuthManager.PendingPairRequest req,
            Bitmap cachedQrBitmap,
            int focusedActionIdx,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint bitmapPaint,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        if (req == null) return;
        ensureRectsInitialized();
        if (monoCodePaint == null) {
            monoCodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            monoCodePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        fill.setShader(null);
        fill.setColor(0xA805070E);
        c.drawRect(0, 0, w, h, fill);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xCCFFFFFF : lDimColor;
        int invInk = isDark ? lInkColor : whiteColor;

        float mw = 960f * k;
        float mh = 564f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        pairingPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, pairingPanelRect, 38 * k, glassBaseColor, glassBlurMix, 0xFF0EA5E9, 0.16f, 0.03f, 1f);
        border.setColor(isDark ? 0x3CFFFFFF : 0x30000000);
        c.drawRoundRect(pairingPanelRect, 38 * k, 38 * k, border);

        // Header: shield/cast symbol + Title + Subtitle tailored to Owner Setup, Admin/Role Invite, or Incoming Request
        boolean isAdminInvite = req.presetRole == RemoteAuthManager.Role.ADMIN && !req.bootstrapAdmin;
        boolean isOwnerSetup = req.bootstrapAdmin;
        boolean isRoleInvite = req.presetRole != null && !req.bootstrapAdmin;
        Symbols.draw(ctx, c, (isOwnerSetup || isAdminInvite) ? "shield" : "cast", mx + 56 * k, my + 56 * k, 30 * k, ink);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(32 * k);
        fitPaint.setColor(ink);
        String title;
        if (isOwnerSetup) {
            title = Ui.tr("Pair Owner Remote (Admin)");
        } else if (req.presetRole == RemoteAuthManager.Role.ADMIN) {
            title = Ui.tr("Invite Admin Remote");
        } else if (req.presetRole == RemoteAuthManager.Role.HOUSEHOLD) {
            title = Ui.tr("Invite Household Remote");
        } else if (req.presetRole == RemoteAuthManager.Role.GUEST) {
            title = Ui.tr("Invite Guest Remote (30d)");
        } else {
            title = Ui.tr("New Remote Detected");
        }
        c.drawText(title, mx + 86 * k, my + 66 * k, fitPaint);

        String devName = (req.deviceName != null && !req.deviceName.isEmpty()) ? req.deviceName : "Companion Remote";
        String subLine;
        if (isOwnerSetup) {
            subLine = devName + " \u00B7 "
                    + Ui.tr("First phone becomes TV Owner (Admin) — press OK on Make Admin or scan QR");
        } else if (isRoleInvite) {
            subLine = Ui.tr("Scan with iPhone or Android camera to pair & install app (same Wi-Fi)");
        } else {
            subLine = devName + " \u00B7 "
                    + Ui.tr("Scan QR code or enter 6-digit code on phone, or approve below");
        }
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(20 * k);
        fitPaint.setColor(subInk);
        String clippedSub = TextUtils.ellipsize(subLine, fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(clippedSub, mx + 48 * k, my + 106 * k, fitPaint);

        // Left side: crisp white card framing the cached QR code bitmap
        float qrBoxSize = 236f * k;
        float qrLeft = mx + 52 * k;
        float qrTop = my + 134 * k;
        pairingQrCardRect.set(qrLeft, qrTop, qrLeft + qrBoxSize, qrTop + qrBoxSize);
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(pairingQrCardRect, 22 * k, 22 * k, fill);
        border.setColor(0x28000000);
        c.drawRoundRect(pairingQrCardRect, 22 * k, 22 * k, border);

        if (cachedQrBitmap != null && !cachedQrBitmap.isRecycled()) {
            float padQr = 14f * k;
            pairingQrBitmapRect.set(qrLeft + padQr, qrTop + padQr,
                    qrLeft + qrBoxSize - padQr, qrTop + qrBoxSize - padQr);
            c.drawBitmap(cachedQrBitmap, null, pairingQrBitmapRect, bitmapPaint);
        } else {
            Symbols.draw(ctx, c, "cast", pairingQrCardRect.centerX(), pairingQrCardRect.centerY(), 56 * k, 0xFF111827);
        }

        // Right side: extra-large 6-digit code + LAN URL hint
        float rightColX = qrLeft + qrBoxSize + 44 * k;
        float rightAvailW = mx + mw - 48 * k - rightColX;

        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(17.5f * k);
        fitPaint.setColor(subInk);
        c.drawText(Ui.tr("6-DIGIT PAIRING CODE"), rightColX, qrTop + 44 * k, fitPaint);

        String formattedCode = HomeInputRouter.formatSixDigitCode(req.sixDigitCode);
        monoCodePaint.setTextSize(68 * k);
        monoCodePaint.setLetterSpacing(0.10f);
        monoCodePaint.setColor(ink);
        c.drawText(formattedCode, rightColX, qrTop + 128 * k, monoCodePaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(19.5f * k);
        fitPaint.setColor(subInk);
        String urlHint = req.pairUrl != null ? req.pairUrl : "";
        int qMark = urlHint.indexOf("/?pair=");
        String cleanLanBase = qMark > 0 ? urlHint.substring(0, qMark) : urlHint;
        String urlCaption = Ui.tr("Phone URL: ") + cleanLanBase;
        String clippedUrl = TextUtils.ellipsize(urlCaption, fitPaint, rightAvailW, TextUtils.TruncateAt.END).toString();
        c.drawText(clippedUrl, rightColX, qrTop + 178 * k, fitPaint);

        String roleHint;
        if (isOwnerSetup) {
            roleHint = Ui.tr("Recommended: Press OK on Make Admin below (or scan QR)");
        } else if (isRoleInvite) {
            roleHint = Ui.tr("Same Wi-Fi · Android APK & iPhone Safari") + " · " + req.presetRole.name();
        } else {
            roleHint = Ui.tr("Or approve directly with your TV remote below:");
        }
        String clippedRoleHint = TextUtils.ellipsize(roleHint, fitPaint, rightAvailW, TextUtils.TruncateAt.END).toString();
        c.drawText(clippedRoleHint, rightColX, qrTop + 214 * k, fitPaint);

        // Bottom 2x2 grid of 4 selectable Liquid Glass buttons
        float gridLeft = mx + 48 * k;
        float gridTop = qrTop + qrBoxSize + 28 * k;
        float btnGapX = 20 * k;
        float btnGapY = 14 * k;
        float btnW = (mw - 96 * k - btnGapX) / 2f;
        float btnH = 58 * k;
        String[] icons = {"clock", "users", "shield", "close"};

        for (int i = 0; i < PAIRING_ACTION_COUNT; i++) {
            int row = i / 2;
            int col = i % 2;
            float bx = gridLeft + col * (btnW + btnGapX);
            float by = gridTop + row * (btnH + btnGapY);
            RectF br = pairingActionRects[i];
            br.set(bx, by, bx + btnW, by + btnH);

            boolean focused = (i == focusedActionIdx);
            fill.setShader(null);
            fill.setColor(focused ? ink : (isDark ? 0x22FFFFFF : 0x16000000));
            c.drawRoundRect(br, btnH / 2f, btnH / 2f, fill);
            if (!focused) {
                border.setColor(isDark ? 0x2EFFFFFF : 0x22000000);
                c.drawRoundRect(br, btnH / 2f, btnH / 2f, border);
            }

            int btnInk = focused ? invInk : ink;
            Symbols.draw(ctx, c, icons[i], br.left + 32 * k, br.centerY(), 22 * k, btnInk);

            fitPaint.set(focused ? lSubTabOn : lSubTab);
            fitPaint.setTextSize(21.5f * k);
            fitPaint.setColor(btnInk);
            String label = Ui.tr(PAIRING_ACTION_LABELS[i]);
            String clippedBtn = TextUtils.ellipsize(label, fitPaint, btnW - 72 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(clippedBtn, br.left + 54 * k, br.centerY() + Ui.halfCap(fitPaint), fitPaint);
        }
    }

    /**
     * Hit-tests pointer coordinates against the active Sleep Timer modal.
     */
    int hitTestSleepTimerModal(float x, float y) {
        if (sleepPanelRect == null || sleepPanelRect.isEmpty()) return SLEEP_HIT_NONE;
        if (!sleepPanelRect.contains(x, y)) return SLEEP_HIT_OUTSIDE;
        if (sleepToggleRect.contains(x, y)) return SLEEP_HIT_TOGGLE;
        if (sleepUpArrowRect.contains(x, y)) return SLEEP_HIT_UP;
        if (sleepDownArrowRect.contains(x, y)) return SLEEP_HIT_DOWN;
        if (sleepAddFiveRect.contains(x, y)) return SLEEP_HIT_ADD_FIVE;
        if (sleepSaveBtnRect.contains(x, y)) return SLEEP_HIT_SAVE;
        if (sleepCancelBtnRect.contains(x, y)) return SLEEP_HIT_CANCEL;
        return SLEEP_HIT_NONE;
    }

    /**
     * Renders the interactive Apple TV / Liquid Glass Up-Down Sleep Timer modal onto {@code toastLayer}
     * with On/Off card, smart Up/Down duration stepper, projected sleep clock, {@code +5 min} quick-add,
     * and Save/Cancel pills (zero per-frame heap allocations).
     */
    void drawSleepTimerModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            SleepTimerModalState state,
            long nowEpochMs,
            boolean h24,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        if (state == null) return;
        ensureRectsInitialized();

        fill.setShader(null);
        fill.setColor(0x9C05070E);
        c.drawRect(0, 0, w, h, fill);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xFFD1D1D6 : lDimColor;
        int invInk = isDark ? lInkColor : whiteColor;
        int emerald = isDark ? 0xFF34D399 : 0xFF059669;

        float mw = 900f * k;
        float mh = 468f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        sleepPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, sleepPanelRect, 36 * k, glassBaseColor, glassBlurMix, 0xFF6366F1, 0.14f, 0.03f, 1f);
        border.setColor(isDark ? 0x3CFFFFFF : 0x2C000000);
        c.drawRoundRect(sleepPanelRect, 36 * k, 36 * k, border);

        // Header
        Symbols.draw(ctx, c, "bedtime", mx + 54 * k, my + 56 * k, 29 * k, ink);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(31 * k);
        fitPaint.setColor(ink);
        c.drawText(Ui.tr("Sleep Timer"), mx + 86 * k, my + 66 * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(19.5f * k);
        fitPaint.setColor(subInk);
        c.drawText(Ui.tr("Put the TV to sleep automatically after the selected duration"), mx + 48 * k, my + 104 * k, fitPaint);

        // Main 3-card interactive deck: [ Status On/Off ]  [ ▲ Duration ▼ ]  [ +5 min ]
        float deckTop = my + 132 * k;
        float deckH = 192 * k;
        float gap = 20 * k;
        float sideW = 212 * k;
        float centerW = mw - 96 * k - 2 * sideW - 2 * gap;
        float leftX = mx + 48 * k;
        float centerX = leftX + sideW + gap;
        float rightX = centerX + centerW + gap;
        float cardR = 24 * k;

        int slot = state.getFocusSlot();
        boolean on = state.isEnabled();

        // Card 0: Status Toggle
        boolean togFoc = (slot == SleepTimerModalState.SLOT_TOGGLE);
        sleepToggleRect.set(leftX, deckTop, leftX + sideW, deckTop + deckH);
        fill.setShader(null);
        fill.setColor(togFoc ? ink : (isDark ? 0x1AFFFFFF : 0x12000000));
        c.drawRoundRect(sleepToggleRect, cardR, cardR, fill);
        if (!togFoc) {
            border.setColor(isDark ? 0x28FFFFFF : 0x1E000000);
            c.drawRoundRect(sleepToggleRect, cardR, cardR, border);
        }
        int togInk = togFoc ? invInk : ink;
        int togSub = togFoc ? (isDark ? 0xFF3A3A3C : 0xE6FFFFFF) : subInk;
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(15.5f * k);
        fitPaint.setLetterSpacing(0.08f);
        fitPaint.setColor(togSub);
        String statusHdr = Ui.tr("STATUS");
        c.drawText(statusHdr, sleepToggleRect.centerX() - fitPaint.measureText(statusHdr) / 2f, deckTop + 38 * k, fitPaint);
        fitPaint.setLetterSpacing(0f);

        // Capsule Switch inside Status Card
        float swW = 64 * k;
        float swH = 34 * k;
        float swL = sleepToggleRect.centerX() - swW / 2f;
        float swT = deckTop + 66 * k;
        sleepSwitchPillRect.set(swL, swT, swL + swW, swT + swH);
        fill.setColor(on ? emerald : (togFoc ? (isDark ? 0x33000000 : 0x44FFFFFF) : (isDark ? 0x2CFFFFFF : 0x24000000)));
        c.drawRoundRect(sleepSwitchPillRect, swH / 2f, swH / 2f, fill);
        float kCx = on ? (swL + swW - swH / 2f) : (swL + swH / 2f);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(kCx, swT + swH / 2f, 12.5f * k, fill);

        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(26 * k);
        fitPaint.setColor(togInk);
        String onOffLbl = Ui.tr(on ? "On" : "Off");
        c.drawText(onOffLbl, sleepToggleRect.centerX() - fitPaint.measureText(onOffLbl) / 2f, deckTop + 144 * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(16.5f * k);
        fitPaint.setColor(togSub);
        String togHint = Ui.tr("OK to toggle");
        c.drawText(togHint, sleepToggleRect.centerX() - fitPaint.measureText(togHint) / 2f, deckTop + 174 * k, fitPaint);

        // Card 1: Up/Down Duration Wheel (Hero center control)
        boolean wheelFoc = (slot == SleepTimerModalState.SLOT_DURATION);
        sleepWheelRect.set(centerX, deckTop, centerX + centerW, deckTop + deckH);
        sleepUpArrowRect.set(centerX, deckTop, centerX + centerW, deckTop + 56 * k);
        sleepDownArrowRect.set(centerX, deckTop + deckH - 56 * k, centerX + centerW, deckTop + deckH);
        fill.setShader(null);
        fill.setColor(wheelFoc ? ink : (isDark ? 0x22FFFFFF : 0x16000000));
        c.drawRoundRect(sleepWheelRect, cardR, cardR, fill);
        if (!wheelFoc) {
            border.setColor(isDark ? 0x36FFFFFF : 0x26000000);
            c.drawRoundRect(sleepWheelRect, cardR, cardR, border);
        }
        int wheelInk = wheelFoc ? invInk : ink;
        int wheelSub = wheelFoc ? (isDark ? 0xFF3A3A3C : 0xE6FFFFFF) : subInk;

        boolean canUp = state.getMinutes() < SleepTimer.MAX_MINUTES;
        boolean canDown = state.getMinutes() > 0;
        Symbols.draw(ctx, c, "chevron_up", sleepWheelRect.centerX(), deckTop + 30 * k, 22 * k,
                Ui.alpha(wheelInk, canUp ? 0.92f : 0.25f));
        Symbols.draw(ctx, c, "chevron_down", sleepWheelRect.centerX(), deckTop + deckH - 28 * k, 22 * k,
                Ui.alpha(wheelInk, canDown ? 0.92f : 0.25f));

        String durText = Ui.tr(SleepTimer.formatDurationLabel(state.getMinutes()));
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(46 * k);
        fitPaint.setColor(wheelInk);
        float durW = fitPaint.measureText(durText);
        c.drawText(durText, sleepWheelRect.centerX() - durW / 2f, deckTop + 104 * k, fitPaint);

        String sleepAt = SleepTimer.formatSleepAtClock(state.getMinutes(), nowEpochMs, h24);
        String wheelCaption = sleepAt.isEmpty()
                ? Ui.tr("Press Up to start timer")
                : (Ui.tr("TV sleeps at ") + sleepAt);
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(18.5f * k);
        fitPaint.setColor(wheelSub);
        float capW = fitPaint.measureText(wheelCaption);
        c.drawText(wheelCaption, sleepWheelRect.centerX() - capW / 2f, deckTop + 138 * k, fitPaint);

        // Card 2: Quick +5 min Add
        boolean addFoc = (slot == SleepTimerModalState.SLOT_ADD_FIVE);
        sleepAddFiveRect.set(rightX, deckTop, rightX + sideW, deckTop + deckH);
        fill.setShader(null);
        fill.setColor(addFoc ? ink : (isDark ? 0x1AFFFFFF : 0x12000000));
        c.drawRoundRect(sleepAddFiveRect, cardR, cardR, fill);
        if (!addFoc) {
            border.setColor(isDark ? 0x28FFFFFF : 0x1E000000);
            c.drawRoundRect(sleepAddFiveRect, cardR, cardR, border);
        }
        int addInk = addFoc ? invInk : ink;
        int addSub = addFoc ? (isDark ? 0xFF3A3A3C : 0xE6FFFFFF) : subInk;
        Symbols.draw(ctx, c, "plus", sleepAddFiveRect.centerX(), deckTop + 62 * k, 32 * k, addInk);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(28 * k);
        fitPaint.setColor(addInk);
        String plusLbl = "+5 min";
        c.drawText(plusLbl, sleepAddFiveRect.centerX() - fitPaint.measureText(plusLbl) / 2f, deckTop + 124 * k, fitPaint);
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(16.5f * k);
        fitPaint.setColor(addSub);
        String plusSub = Ui.tr("Add extra time");
        c.drawText(plusSub, sleepAddFiveRect.centerX() - fitPaint.measureText(plusSub) / 2f, deckTop + 158 * k, fitPaint);

        // Bottom Buttons: [ Set Timer ]   [ Cancel ]
        float btnTop = deckTop + deckH + 26 * k;
        float btnH = 56 * k;
        float btnW = 260 * k;
        float btnGap = 24 * k;
        float saveX = mx + (mw - (2 * btnW + btnGap)) / 2f;
        float cancelX = saveX + btnW + btnGap;

        boolean saveFoc = (slot == SleepTimerModalState.SLOT_SAVE);
        sleepSaveBtnRect.set(saveX, btnTop, saveX + btnW, btnTop + btnH);
        fill.setShader(null);
        fill.setColor(saveFoc ? ink : (isDark ? 0x24FFFFFF : 0x18000000));
        c.drawRoundRect(sleepSaveBtnRect, btnH / 2f, btnH / 2f, fill);
        if (!saveFoc) {
            border.setColor(isDark ? 0x32FFFFFF : 0x24000000);
            c.drawRoundRect(sleepSaveBtnRect, btnH / 2f, btnH / 2f, border);
        }
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(21.5f * k);
        fitPaint.setColor(saveFoc ? invInk : ink);
        String saveLbl = Ui.tr(on ? "Set Timer" : "Turn Off Timer");
        c.drawText(saveLbl, sleepSaveBtnRect.centerX() - fitPaint.measureText(saveLbl) / 2f,
                sleepSaveBtnRect.centerY() + Ui.halfCap(fitPaint), fitPaint);

        boolean cancelFoc = (slot == SleepTimerModalState.SLOT_CANCEL);
        sleepCancelBtnRect.set(cancelX, btnTop, cancelX + btnW, btnTop + btnH);
        fill.setShader(null);
        fill.setColor(cancelFoc ? ink : (isDark ? 0x1CFFFFFF : 0x12000000));
        c.drawRoundRect(sleepCancelBtnRect, btnH / 2f, btnH / 2f, fill);
        if (!cancelFoc) {
            border.setColor(isDark ? 0x2AFFFFFF : 0x20000000);
            c.drawRoundRect(sleepCancelBtnRect, btnH / 2f, btnH / 2f, border);
        }
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(21.5f * k);
        fitPaint.setColor(cancelFoc ? invInk : ink);
        String cancelLbl = Ui.tr("Cancel");
        c.drawText(cancelLbl, sleepCancelBtnRect.centerX() - fitPaint.measureText(cancelLbl) / 2f,
                sleepCancelBtnRect.centerY() + Ui.halfCap(fitPaint), fitPaint);

        // Footer key hint
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(17.5f * k);
        fitPaint.setColor(subInk);
        String footer = Ui.tr("Up / Down Adjust duration  \u00B7  Left / Right Switch control  \u00B7  OK Save  \u00B7  Back Cancel");
        String clipped = TextUtils.ellipsize(footer, fitPaint, mw - 72 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(clipped, mx + (mw - fitPaint.measureText(clipped)) / 2f, my + mh - 20 * k, fitPaint);
    }

    /**
     * Hit-tests pointer tap coordinates against the active Screensaver Slide Duration modal.
     */
    int hitTestSlideDurationModal(float x, float y) {
        if (sdPanelRect == null || sdPanelRect.isEmpty()) return SD_HIT_NONE;
        if (!sdPanelRect.contains(x, y)) return SD_HIT_OUTSIDE;
        if (sdUpArrowRect.contains(x, y)) return SD_HIT_UP;
        if (sdDownArrowRect.contains(x, y)) return SD_HIT_DOWN;
        return SD_HIT_NONE;
    }

    /**
     * Renders the cyclic Liquid Glass Up/Down Screensaver Slide Duration picker onto {@code toastLayer}
     * with previous/next peek labels, 17-step progress pips, and auto-saving instant feedback
     * (zero per-frame heap allocations).
     */
    void drawSlideDurationModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            SlideDurationModalState state,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        if (state == null) return;
        ensureRectsInitialized();

        fill.setShader(null);
        fill.setColor(0x9C05070E);
        c.drawRect(0, 0, w, h, fill);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xFFD1D1D6 : lDimColor;
        int invInk = isDark ? lInkColor : whiteColor;
        int invSub = isDark ? 0xFF48484A : 0xD9FFFFFF;
        int accent = isDark ? 0xFF34D399 : 0xFF059669;

        float mw = 740f * k;
        float mh = 444f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        sdPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, sdPanelRect, 36 * k, glassBaseColor, glassBlurMix, 0xFF6366F1, 0.14f, 0.03f, 1f);
        border.setColor(isDark ? 0x3CFFFFFF : 0x2C000000);
        c.drawRoundRect(sdPanelRect, 36 * k, 36 * k, border);

        // Header
        Symbols.draw(ctx, c, "timer", mx + 54 * k, my + 56 * k, 29 * k, ink);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(31 * k);
        fitPaint.setColor(ink);
        c.drawText(Ui.tr("Slide Duration"), mx + 86 * k, my + 66 * k, fitPaint);

        fitPaint.set(lSubTab);
        fitPaint.setTextSize(19.5f * k);
        fitPaint.setColor(subInk);
        String headerSub = TextUtils.ellipsize(
                Ui.tr("How long each screensaver photo stays on screen"),
                fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(headerSub, mx + 48 * k, my + 104 * k, fitPaint);

        // Centered Hero Cyclic Up/Down Wheel Card
        float cardL = mx + 56 * k;
        float cardT = my + 128 * k;
        float cardW = mw - 112 * k;
        float cardH = 228 * k;
        float cardR = 26 * k;
        sdCardRect.set(cardL, cardT, cardL + cardW, cardT + cardH);
        sdUpArrowRect.set(cardL, cardT, cardL + cardW, cardT + 72 * k);
        sdDownArrowRect.set(cardL, cardT + cardH - 72 * k, cardL + cardW, cardT + cardH);

        fill.setShader(null);
        fill.setColor(ink);
        c.drawRoundRect(sdCardRect, cardR, cardR, fill);

        // Up chevron + Next step peek
        Symbols.draw(ctx, c, "chevron_up", sdCardRect.centerX(), cardT + 26 * k, 21 * k,
                Ui.alpha(invInk, 0.88f));
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(17.5f * k);
        fitPaint.setColor(Ui.alpha(invSub, 0.82f));
        String nextPeek = Ui.tr(state.getNextLabel());
        c.drawText(nextPeek, sdCardRect.centerX() - fitPaint.measureText(nextPeek) / 2f, cardT + 56 * k, fitPaint);

        // Hero current value
        String curLabel = Ui.tr(state.getLabel());
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize((state.isStaticMode() ? 36f : 48f) * k);
        fitPaint.setColor(invInk);
        String clippedCur = TextUtils.ellipsize(curLabel, fitPaint, cardW - 48 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(clippedCur, sdCardRect.centerX() - fitPaint.measureText(clippedCur) / 2f, cardT + 114 * k, fitPaint);

        // 17-step progress pips showing exact position along cyclic ladder
        int totalSteps = state.getTotalSteps();
        int stepIdx = state.getStepIndex();
        float pipGap = 7f * k;
        float pipH = 6.5f * k;
        float pipWNormal = 11f * k;
        float pipWActive = 26f * k;
        float totalPipsW = (totalSteps - 1) * pipWNormal + pipWActive + (totalSteps - 1) * pipGap;
        float px = sdCardRect.centerX() - totalPipsW / 2f;
        float py = cardT + 136 * k;
        for (int p = 0; p < totalSteps; p++) {
            float pw = (p == stepIdx) ? pipWActive : pipWNormal;
            if (p == stepIdx) {
                fill.setColor(state.isStaticMode() ? invInk : accent);
            } else {
                fill.setColor(Ui.alpha(invInk, 0.22f));
            }
            sdScratchRect.set(px, py, px + pw, py + pipH);
            c.drawRoundRect(sdScratchRect, pipH / 2f, pipH / 2f, fill);
            px += pw + pipGap;
        }

        // Previous step peek + Down chevron
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(17.5f * k);
        fitPaint.setColor(Ui.alpha(invSub, 0.82f));
        String prevPeek = Ui.tr(state.getPrevLabel());
        c.drawText(prevPeek, sdCardRect.centerX() - fitPaint.measureText(prevPeek) / 2f, cardT + cardH - 42 * k, fitPaint);
        Symbols.draw(ctx, c, "chevron_down", sdCardRect.centerX(), cardT + cardH - 22 * k, 21 * k,
                Ui.alpha(invInk, 0.88f));

        // Explanatory status caption below card
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(19 * k);
        fitPaint.setColor(ink);
        String caption = TextUtils.ellipsize(
                Ui.tr(state.getCaption()), fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(caption, sdPanelRect.centerX() - fitPaint.measureText(caption) / 2f, cardT + cardH + 34 * k, fitPaint);

        // Footer hint
        fitPaint.set(lSubTab);
        fitPaint.setTextSize(17.5f * k);
        fitPaint.setColor(subInk);
        String footer = TextUtils.ellipsize(
                Ui.tr("Up / Down Change duration  \u00B7  OK or Back Done"),
                fitPaint, mw - 72 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(footer, sdPanelRect.centerX() - fitPaint.measureText(footer) / 2f, my + mh - 20 * k, fitPaint);
    }

    /**
     * Hit-tests pointer tap coordinates against the active 3-column Liquid Glass Stream Quality modal.
     */
    int hitTestStreamQualityModal(float x, float y) {
        if (sqPanelRect == null || sqPanelRect.isEmpty()) return SQ_HIT_NONE;
        if (!sqPanelRect.contains(x, y)) return SQ_HIT_OUTSIDE;
        for (int i = 0; i < 3; i++) {
            if (sqUpRects[i].contains(x, y)) {
                return i == 0 ? SQ_HIT_RES_UP : (i == 1 ? SQ_HIT_FPS_UP : SQ_HIT_BIT_UP);
            }
            if (sqDownRects[i].contains(x, y)) {
                return i == 0 ? SQ_HIT_RES_DOWN : (i == 1 ? SQ_HIT_FPS_DOWN : SQ_HIT_BIT_DOWN);
            }
            if (sqColRects[i].contains(x, y)) {
                return i;
            }
        }
        return SQ_HIT_NONE;
    }

    private static int streamToneColor(int tone, boolean isDark) {
        if (tone == StreamQualityModalState.TONE_RECOMMENDED) {
            return isDark ? 0xFF34D399 : 0xFF059669;
        }
        if (tone == StreamQualityModalState.TONE_CAUTION) {
            return isDark ? 0xFFFBBF24 : 0xFFD97706;
        }
        return isDark ? 0xFF38BDF8 : 0xFF0284C7;
    }

    /**
     * Renders the minimal 3-column Liquid Glass Stream Quality modal (Resolution | Frame Rate | Bitrate)
     * onto {@code toastLayer} with semantic range pips and concise dynamic hardware hints (zero per-frame
     * heap allocations, zero redundant preset boxes, action buttons, or key legends).
     */
    void drawStreamQualityModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            StreamQualityModalState state,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        if (state == null) return;
        ensureRectsInitialized();

        fill.setShader(null);
        fill.setColor(0xA805070E);
        c.drawRect(0, 0, w, h, fill);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xFFD1D1D6 : lDimColor;
        float prevStrokeW = border.getStrokeWidth();

        float mw = 1120f * k;
        float mh = 406f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        sqPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, sqPanelRect, 36 * k, glassBaseColor, glassBlurMix, 0xFF0EA5E9, 0.14f, 0.03f, 1f);
        border.setStrokeWidth(1.5f * k);
        border.setColor(isDark ? 0x3CFFFFFF : 0x2C000000);
        c.drawRoundRect(sqPanelRect, 36 * k, 36 * k, border);

        // Header: Title + Live Hardware Telemetry Pill
        float padX = 44f * k;
        Symbols.draw(ctx, c, "sliders", mx + padX + 14 * k, my + 52 * k, 28 * k, ink);
        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(31 * k);
        fitPaint.setColor(ink);
        c.drawText(Ui.tr("Stream Quality"), mx + padX + 44 * k, my + 62 * k, fitPaint);

        String hwText = state.getHardwareTelemetryLabel();
        if (!hwText.isEmpty()) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(16.5f * k);
            float hwW = fitPaint.measureText(hwText) + 26 * k;
            float pillH = 34 * k;
            sqScratchRect.set(mx + mw - padX - hwW, my + 35 * k, mx + mw - padX, my + 35 * k + pillH);
            fill.setShader(null);
            fill.setColor(isDark ? 0x1EFFFFFF : 0x14000000);
            c.drawRoundRect(sqScratchRect, pillH / 2f, pillH / 2f, fill);
            fitPaint.setColor(subInk);
            c.drawText(hwText, sqScratchRect.left + 13 * k, sqScratchRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
        }

        // Dynamic Overall Status Line
        int overallTone = state.getOverallTone();
        int overallColor = streamToneColor(overallTone, isDark);
        fill.setShader(null);
        fill.setColor(overallColor);
        c.drawCircle(mx + padX + 6 * k, my + 95 * k, 5.5f * k, fill);
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(18.5f * k);
        fitPaint.setColor(subInk);
        String banner = TextUtils.ellipsize(state.getOverallBannerText(), fitPaint, mw - padX * 2 - 24 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(banner, mx + padX + 20 * k, my + 101 * k, fitPaint);

        // Main 3 Side-by-Side Liquid-Glass Columns: [ RESOLUTION ]  [ FRAME RATE ]  [ BITRATE ]
        float deckTop = my + 124 * k;
        float deckH = 244 * k;
        float colGap = 20 * k;
        float colW = (mw - padX * 2 - colGap * 2) / 3f;
        float cardR = 26 * k;
        int slot = state.getFocusSlot();

        for (int col = 0; col < 3; col++) {
            float cx0 = mx + padX + col * (colW + colGap);
            RectF card = sqColRects[col];
            card.set(cx0, deckTop, cx0 + colW, deckTop + deckH);
            sqUpRects[col].set(cx0, deckTop + 34 * k, cx0 + colW, deckTop + 92 * k);
            sqDownRects[col].set(cx0, deckTop + deckH - 92 * k, cx0 + colW, deckTop + deckH - 36 * k);

            boolean focused = (slot == col);
            int colTone = col == 0 ? state.getResolutionTone() : (col == 1 ? state.getFpsTone() : state.getBitrateTone());
            int toneCol = streamToneColor(colTone, isDark);

            fill.setShader(null);
            if (focused) {
                fill.setColor(isDark ? 0x2BFFFFFF : 0xEBFFFFFF);
            } else {
                fill.setColor(isDark ? 0x16FFFFFF : 0x12000000);
            }
            c.drawRoundRect(card, cardR, cardR, fill);

            if (focused) {
                border.setStrokeWidth(3.0f * k);
                border.setColor(toneCol);
                c.drawRoundRect(card, cardR, cardR, border);
                border.setStrokeWidth(1.5f * k);
            } else {
                border.setColor(isDark ? 0x28FFFFFF : 0x1E000000);
                c.drawRoundRect(card, cardR, cardR, border);
            }

            // Column Header + Tone Badge ("REC" / "WARN" / dot)
            String colTitle = col == 0 ? Ui.tr("RESOLUTION") : (col == 1 ? Ui.tr("FRAME RATE") : Ui.tr("BITRATE"));
            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize(14.5f * k);
            fitPaint.setLetterSpacing(0.08f);
            fitPaint.setColor(focused ? ink : subInk);
            c.drawText(colTitle, cx0 + 20 * k, deckTop + 32 * k, fitPaint);
            fitPaint.setLetterSpacing(0f);

            String badgeText = colTone == StreamQualityModalState.TONE_RECOMMENDED
                    ? Ui.tr("REC")
                    : (colTone == StreamQualityModalState.TONE_CAUTION ? Ui.tr("WARN") : "");
            if (!badgeText.isEmpty()) {
                fitPaint.set(lSubTabOn);
                fitPaint.setTextSize(12.5f * k);
                float bw = fitPaint.measureText(badgeText) + 16 * k;
                float bh = 22 * k;
                sqScratchRect.set(cx0 + colW - 18 * k - bw, deckTop + 16 * k, cx0 + colW - 18 * k, deckTop + 16 * k + bh);
                fill.setColor(Ui.alpha(toneCol, isDark ? 0.24f : 0.16f));
                c.drawRoundRect(sqScratchRect, bh / 2f, bh / 2f, fill);
                fitPaint.setColor(toneCol);
                c.drawText(badgeText, sqScratchRect.centerX() - fitPaint.measureText(badgeText) / 2f,
                        sqScratchRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
            } else {
                fill.setColor(toneCol);
                c.drawCircle(cx0 + colW - 24 * k, deckTop + 27 * k, 4.5f * k, fill);
            }

            // Up Chevron
            boolean canUp = state.canStepUp(col);
            Symbols.draw(ctx, c, "chevron_up", card.centerX(), deckTop + 60 * k, 20 * k,
                    Ui.alpha(focused ? toneCol : ink, canUp ? (focused ? 0.95f : 0.45f) : 0.16f));

            // Hero Value
            String valText = col == 0
                    ? state.getResolutionLabel()
                    : (col == 1 ? (state.getFps() + " FPS") : (state.getBitrateMbps() + " Mbps"));
            fitPaint.set(lCardTitle);
            fitPaint.setTextSize(42 * k);
            fitPaint.setColor(ink);
            float vw = fitPaint.measureText(valText);
            c.drawText(valText, card.centerX() - vw / 2f, deckTop + 120 * k, fitPaint);

            // Range Step Pips (shows exact position, emerald recommended pip, amber caution pips)
            int count = col == 0
                    ? StreamQualityModalState.RES_KEYS.length
                    : (col == 1 ? StreamQualityModalState.FPS_STEPS.length : StreamQualityModalState.BITRATE_STEPS.length);
            int curIdx = col == 0 ? state.getResolutionIndex() : (col == 1 ? state.getFpsIndex() : state.getBitrateIndex());
            float pipGap = (count > 5 ? 7f : 11f) * k;
            float pipH = 7f * k;
            float pipWNormal = (count > 5 ? 14f : 26f) * k;
            float pipWActive = (count > 5 ? 26f : 42f) * k;
            float totalPipsW = (count - 1) * pipWNormal + pipWActive + (count - 1) * pipGap;
            float px = card.centerX() - totalPipsW / 2f;
            float py = deckTop + 144 * k;
            for (int p = 0; p < count; p++) {
                float pw = (p == curIdx) ? pipWActive : pipWNormal;
                int pTone = col == 0
                        ? state.getResolutionToneForIndex(p)
                        : (col == 1 ? state.getFpsToneForIndex(p) : state.getBitrateToneForIndex(p));
                int pColor;
                if (p == curIdx) {
                    pColor = streamToneColor(pTone, isDark);
                } else if (pTone == StreamQualityModalState.TONE_RECOMMENDED) {
                    pColor = Ui.alpha(streamToneColor(StreamQualityModalState.TONE_RECOMMENDED, isDark), 0.58f);
                } else if (pTone == StreamQualityModalState.TONE_CAUTION) {
                    pColor = Ui.alpha(streamToneColor(StreamQualityModalState.TONE_CAUTION, isDark), 0.42f);
                } else {
                    pColor = isDark ? 0x38FFFFFF : 0x26000000;
                }
                sqScratchRect.set(px, py, px + pw, py + pipH);
                fill.setColor(pColor);
                c.drawRoundRect(sqScratchRect, pipH / 2f, pipH / 2f, fill);
                px += pw + pipGap;
            }

            // Down Chevron
            boolean canDown = state.canStepDown(col);
            Symbols.draw(ctx, c, "chevron_down", card.centerX(), deckTop + 178 * k, 20 * k,
                    Ui.alpha(focused ? toneCol : ink, canDown ? (focused ? 0.95f : 0.45f) : 0.16f));

            // Short, subtle "Why" hint at bottom of column
            String hint = col == 0 ? state.getResolutionHint() : (col == 1 ? state.getFpsHint() : state.getBitrateHint());
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(16.5f * k);
            fitPaint.setColor(colTone == StreamQualityModalState.TONE_BALANCED ? subInk : toneCol);
            String clippedHint = TextUtils.ellipsize(hint, fitPaint, colW - 28 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(clippedHint, card.centerX() - fitPaint.measureText(clippedHint) / 2f, deckTop + deckH - 20 * k, fitPaint);
        }

        border.setStrokeWidth(prevStrokeW);
    }

    /**
     * Hit-tests pointer tap coordinates against the active Pairing Platter.
     *
     * @return {@code 0..3} if an action button was tapped, {@code -2} if tapped outside the platter (dismiss),
     *         or {@code -1} if tapped inside the platter outside buttons.
     */
    int hitTestPairingModal(float x, float y) {
        if (pairingPanelRect == null || pairingPanelRect.isEmpty()) return -1;
        if (!pairingPanelRect.contains(x, y)) {
            return -2;
        }
        for (int i = 0; i < PAIRING_ACTION_COUNT; i++) {
            if (pairingActionRects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Hit-tests pointer tap coordinates against the Liquid Glass Choice or Input modal.
     *
     * @return {@code 0..N-1} for choice row index, {@link #LG_HIT_OUTSIDE}, {@link #LG_HIT_CANCEL},
     *         {@link #LG_HIT_INPUT_BOX}, {@link #LG_HIT_REVEAL}, {@link #LG_HIT_CONFIRM}, or {@link #LG_HIT_NONE}.
     */
    int hitTestLiquidGlassModal(
            float x,
            float y,
            boolean isInputModal,
            int choiceCount,
            int choiceFirstRow,
            boolean secretMode) {
        if (lgPanelRect == null || lgPanelRect.isEmpty()) return LG_HIT_NONE;
        if (!lgPanelRect.contains(x, y)) {
            return LG_HIT_OUTSIDE;
        }
        if (!isInputModal) {
            int maxVis = Math.min(lgVisibleChoiceRects.length, Math.min(6, choiceCount - choiceFirstRow));
            for (int i = 0; i < maxVis; i++) {
                if (lgVisibleChoiceRects[i].contains(x, y)) {
                    return choiceFirstRow + i;
                }
            }
            if (lgCancelBtnRect.contains(x, y)) {
                return LG_HIT_CANCEL;
            }
            return LG_HIT_NONE;
        }
        if (lgInputBoxRect.contains(x, y)) return LG_HIT_INPUT_BOX;
        if (secretMode && lgRevealBtnRect.contains(x, y)) return LG_HIT_REVEAL;
        if (lgConfirmBtnRect.contains(x, y)) return LG_HIT_CONFIRM;
        if (lgCancelBtnRect.contains(x, y)) return LG_HIT_CANCEL;
        return LG_HIT_NONE;
    }

    /**
     * Renders either the Liquid Glass Choice modal or the Liquid Glass Text/PIN/Passphrase Input modal.
     */
    void drawLiquidGlassModal(
            Context ctx,
            Canvas c,
            int w,
            int h,
            float k,
            boolean isInputModal,
            String modalTitle,
            String modalSubtitle,
            String modalHint,
            String confirmLabel,
            String cancelLabel,
            String[] choiceLabels,
            int[] choiceSwatches,
            int choiceFocus,
            int choiceFirstRow,
            LiquidGlassInputState inputState,
            int inputFocusSlot,
            int confirmSlot,
            int cancelSlot,
            Scene scene,
            int glassBaseColor,
            float glassBlurMix,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            Paint fill,
            Paint border,
            Paint lCardTitle,
            Paint lSubTab,
            Paint lSubTabOn,
            TextPaint fitPaint) {
        ensureRectsInitialized();
        fill.setShader(null);
        fill.setColor(0x8805070E);
        c.drawRect(0, 0, w, h, fill);

        int ink = isDark ? whiteColor : lInkColor;
        int subInk = isDark ? 0xCCFFFFFF : lDimColor;
        int invInk = isDark ? lInkColor : whiteColor;

        if (!isInputModal) {
            int visRows = Math.min(6, choiceLabels.length);
            boolean hasSub = !modalSubtitle.isEmpty();
            float headerH = (hasSub ? 148f : 106f) * k;
            float rowH = 66f * k;
            float rowGap = 8f * k;
            float listH = visRows > 0 ? (visRows * rowH + Math.max(0, visRows - 1) * rowGap) : 0f;
            float mw = 860f * k;
            float mh = headerH + listH + 118f * k;
            float mx = (w - mw) / 2f;
            float my = (h - mh) / 2f;
            lgPanelRect.set(mx, my, mx + mw, my + mh);

            scene.glass(c, lgPanelRect, 36 * k, glassBaseColor, glassBlurMix, 0xFF0EA5E9, 0.14f, 0.03f, 1f);
            border.setColor(isDark ? 0x38FFFFFF : 0x30000000);
            c.drawRoundRect(lgPanelRect, 36 * k, 36 * k, border);

            fitPaint.set(lCardTitle);
            fitPaint.setTextSize(30 * k);
            fitPaint.setColor(ink);
            String tStr = TextUtils.ellipsize(Ui.tr(modalTitle), fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(tStr, mx + 48 * k, my + 64 * k, fitPaint);

            if (hasSub) {
                fitPaint.set(lSubTab);
                fitPaint.setTextSize(20 * k);
                fitPaint.setColor(subInk);
                String sStr = TextUtils.ellipsize(Ui.tr(modalSubtitle), fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(sStr, mx + 48 * k, my + 106 * k, fitPaint);
            }

            for (RectF rect : lgVisibleChoiceRects) rect.setEmpty();
            float ryStart = my + headerH;
            for (int i = 0; i < visRows; i++) {
                int idx = choiceFirstRow + i;
                if (idx >= choiceLabels.length) break;
                float ry = ryStart + i * (rowH + rowGap);
                RectF rb = lgVisibleChoiceRects[i];
                rb.set(mx + 36 * k, ry, mx + mw - 36 * k, ry + rowH);
                boolean focused = (choiceFocus == idx);
                fill.setShader(null);
                fill.setColor(focused ? ink : (isDark ? 0x1AFFFFFF : 0x12000000));
                c.drawRoundRect(rb, 18 * k, 18 * k, fill);

                float textX = rb.left + 26 * k;
                if (choiceSwatches != null && idx < choiceSwatches.length && choiceSwatches[idx] != 0) {
                    float swR = 13 * k;
                    float cx = rb.left + 30 * k;
                    fill.setColor(0xFF000000 | choiceSwatches[idx]);
                    c.drawCircle(cx, rb.centerY(), swR, fill);
                    border.setColor(focused ? (isDark ? 0x55000000 : 0x66FFFFFF) : (isDark ? 0x66FFFFFF : 0x40000000));
                    c.drawCircle(cx, rb.centerY(), swR, border);
                    textX = rb.left + 58 * k;
                }

                fitPaint.set(focused ? lSubTabOn : lSubTab);
                fitPaint.setTextSize(23 * k);
                fitPaint.setColor(focused ? invInk : ink);
                String lbl = TextUtils.ellipsize(choiceLabels[idx], fitPaint, rb.right - textX - 24 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(lbl, textX, rb.centerY() + Ui.halfCap(fitPaint), fitPaint);
            }

            boolean cancelFocused = (choiceFocus >= choiceLabels.length);
            float btnW = 240 * k;
            float btnH = 58 * k;
            float btnX = mx + (mw - btnW) / 2f;
            float btnY = my + mh - btnH - 28 * k;
            lgCancelBtnRect.set(btnX, btnY, btnX + btnW, btnY + btnH);
            fill.setShader(null);
            fill.setColor(cancelFocused ? ink : (isDark ? 0x22FFFFFF : 0x16000000));
            c.drawRoundRect(lgCancelBtnRect, btnH / 2f, btnH / 2f, fill);
            fitPaint.set(lSubTabOn);
            fitPaint.setTextSize(21 * k);
            fitPaint.setColor(cancelFocused ? invInk : ink);
            String cLabel = Ui.tr(cancelLabel);
            c.drawText(cLabel, lgCancelBtnRect.centerX() - fitPaint.measureText(cLabel) / 2f,
                    lgCancelBtnRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
            return;
        }

        // Input Modal (Text / Masked PIN / Vault Passphrase)
        float mw = 820f * k;
        float mh = 374f * k;
        float mx = (w - mw) / 2f;
        float my = (h - mh) / 2f;
        lgPanelRect.set(mx, my, mx + mw, my + mh);

        scene.glass(c, lgPanelRect, 36 * k, glassBaseColor, glassBlurMix, 0xFF0EA5E9, 0.14f, 0.03f, 1f);
        border.setColor(isDark ? 0x38FFFFFF : 0x30000000);
        c.drawRoundRect(lgPanelRect, 36 * k, 36 * k, border);

        fitPaint.set(lCardTitle);
        fitPaint.setTextSize(30 * k);
        fitPaint.setColor(ink);
        String tStr = TextUtils.ellipsize(Ui.tr(modalTitle), fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
        c.drawText(tStr, mx + 48 * k, my + 66 * k, fitPaint);

        if (!modalSubtitle.isEmpty()) {
            fitPaint.set(lSubTab);
            fitPaint.setTextSize(20 * k);
            fitPaint.setColor(subInk);
            String sStr = TextUtils.ellipsize(Ui.tr(modalSubtitle), fitPaint, mw - 96 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(sStr, mx + 48 * k, my + 110 * k, fitPaint);
        }

        boolean secret = inputState.isSecretMode();
        float boxRightMargin = secret ? (186f * k) : (48f * k);
        lgInputBoxRect.set(mx + 48 * k, my + 146 * k, mx + mw - boxRightMargin, my + 220 * k);
        boolean boxFocused = (inputFocusSlot == 0);
        fill.setShader(null);
        fill.setColor(isDark ? 0x24FFFFFF : 0x16000000);
        c.drawRoundRect(lgInputBoxRect, 18 * k, 18 * k, fill);
        border.setColor(boxFocused ? ink : (isDark ? 0x38FFFFFF : 0x32000000));
        c.drawRoundRect(lgInputBoxRect, 18 * k, 18 * k, border);

        String shown = inputState.displayValue();
        boolean isPlaceholder = shown.isEmpty();
        String renderStr = isPlaceholder ? Ui.tr(modalHint) : shown;
        fitPaint.set(isPlaceholder ? lSubTab : lSubTabOn);
        fitPaint.setTextSize(25 * k);
        fitPaint.setColor(isPlaceholder ? subInk : ink);
        String clipped = TextUtils.ellipsize(renderStr, fitPaint, lgInputBoxRect.width() - 48 * k, TextUtils.TruncateAt.START).toString();
        c.drawText(clipped, lgInputBoxRect.left + 24 * k, lgInputBoxRect.centerY() + Ui.halfCap(fitPaint), fitPaint);

        if (secret) {
            lgRevealBtnRect.set(lgInputBoxRect.right + 14 * k, lgInputBoxRect.top, mx + mw - 48 * k, lgInputBoxRect.bottom);
            boolean revFocused = (inputFocusSlot == 1);
            fill.setColor(revFocused ? ink : (isDark ? 0x24FFFFFF : 0x16000000));
            c.drawRoundRect(lgRevealBtnRect, 18 * k, 18 * k, fill);
            int revInk = revFocused ? invInk : ink;
            Symbols.draw(ctx, c, inputState.isRevealed() ? "eye_off" : "eye",
                    lgRevealBtnRect.centerX(), lgRevealBtnRect.centerY(), 26 * k, revInk);
        } else {
            lgRevealBtnRect.setEmpty();
        }

        float btnH = 58 * k;
        float btnW = 240 * k;
        float btnGap = 22 * k;
        float totalBtnsW = btnW * 2f + btnGap;
        float startBtnX = mx + (mw - totalBtnsW) / 2f;
        float btnY = my + mh - btnH - 34 * k;

        lgConfirmBtnRect.set(startBtnX, btnY, startBtnX + btnW, btnY + btnH);
        boolean confFocused = (inputFocusSlot == confirmSlot);
        fill.setColor(confFocused ? ink : (isDark ? 0x24FFFFFF : 0x16000000));
        c.drawRoundRect(lgConfirmBtnRect, btnH / 2f, btnH / 2f, fill);
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(21 * k);
        fitPaint.setColor(confFocused ? invInk : ink);
        String okLabel = Ui.tr(confirmLabel);
        c.drawText(okLabel, lgConfirmBtnRect.centerX() - fitPaint.measureText(okLabel) / 2f,
                lgConfirmBtnRect.centerY() + Ui.halfCap(fitPaint), fitPaint);

        lgCancelBtnRect.set(startBtnX + btnW + btnGap, btnY, startBtnX + btnW * 2f + btnGap, btnY + btnH);
        boolean cancFocused = (inputFocusSlot == cancelSlot);
        fill.setColor(cancFocused ? ink : (isDark ? 0x24FFFFFF : 0x16000000));
        c.drawRoundRect(lgCancelBtnRect, btnH / 2f, btnH / 2f, fill);
        fitPaint.set(lSubTabOn);
        fitPaint.setTextSize(21 * k);
        fitPaint.setColor(cancFocused ? invInk : ink);
        String cLabel = Ui.tr(cancelLabel);
        c.drawText(cLabel, lgCancelBtnRect.centerX() - fitPaint.measureText(cLabel) / 2f,
                lgCancelBtnRect.centerY() + Ui.halfCap(fitPaint), fitPaint);
    }

    /**
     * Renders the animated Hold-OK Context Menu &amp; Smart-Home Device Control Card overlay on {@code menuLayer}.
     */
    void drawMenu(
            Context ctx,
            Canvas c,
            float menuValue,
            float menuPickValue,
            float k,
            RectF screenRect,
            RectF menuPanel,
            float menuOptionsTop,
            String menuTitle,
            String menuSub,
            int menuOptionCount,
            String[] menuOptions,
            String[] menuIcons,
            boolean menuRenderSmartCard,
            String[] smartCardLabels,
            String[] smartCardValues,
            SmartHomeScreenRenderer smartHomeRenderer,
            boolean isDark,
            int lInkColor,
            int lDimColor,
            int whiteColor,
            float deepBlurVirtual,
            Paint fill,
            Paint border,
            Paint shadowDeep,
            Paint menuTitlePaint,
            Paint menuSubPaint,
            Paint menuOptionPaint,
            Shadows shadows,
            RectF tmp) {
        float a = Ui.clamp(menuValue);
        if (a <= 0.004f) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(isDark ? 0x80000000 : 0x38000000, a));
        c.drawRect(screenRect, fill);
        float s = 0.92f + 0.08f * menuValue;
        float r = MENU_R * k;
        c.save();
        c.scale(s, s, menuPanel.centerX(), menuPanel.centerY());
        tmp.set(menuPanel);
        tmp.offset(0, 18 * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, (isDark ? 0.5f : 0.22f) * a));
        shadows.draw(c, tmp, r, deepBlurVirtual * k, shadowDeep);
        fill.setColor(Ui.alpha(isDark ? 0xFF2C2C2E : 0xFFFFFFFF, a));
        c.drawRoundRect(menuPanel, r, r, fill);
        border.setColor(Ui.alpha(isDark ? 0x1FFFFFFF : 0x14000000, a));
        c.drawRoundRect(menuPanel, r, r, border);
        float x = menuPanel.left + MENU_PAD * k;
        float y = menuPanel.top + (MENU_PAD + 34) * k;
        int ink = isDark ? whiteColor : lInkColor;
        int onPill = isDark ? lInkColor : whiteColor;
        menuTitlePaint.setColor(Ui.alpha(ink, a));
        c.drawText(menuTitle, x, y, menuTitlePaint);
        if (!menuSub.isEmpty()) {
            menuSubPaint.setColor(Ui.alpha(isDark ? 0xFFD1D1D6 : lDimColor, a));
            c.drawText(menuSub, x, y + 38 * k, menuSubPaint);
        }
        float rowR = (MENU_R - MENU_INSET) * k;
        menuRow(0, k, menuPanel, menuOptionsTop, tmp);
        tmp.offset(0, menuPickValue * MENU_STEP * k);
        fill.setColor(Ui.alpha(ink, a));
        c.drawRoundRect(tmp, rowR, rowR, fill);
        for (int i = 0; i < menuOptionCount; i++) {
            menuRow(i, k, menuPanel, menuOptionsTop, tmp);
            float f = Ui.clamp(1f - Math.abs(menuPickValue - i));
            int base = (menuOptionCount > 1) ? ink : (isDark ? 0xFFFF6961 : 0xFFD70015);
            int optColor = Ui.alpha(blendColors(base, onPill, f), a);
            menuOptionPaint.setColor(optColor);
            String icon = menuIcons[i];
            float textX = tmp.left + 28 * k;
            if (icon != null && !icon.isEmpty()) {
                Symbols.draw(ctx, c, icon, tmp.left + 36 * k, tmp.centerY(), 24 * k, optColor);
                textX = tmp.left + 68 * k;
            }
            if (menuRenderSmartCard && smartHomeRenderer != null) {
                smartHomeRenderer.drawSmartDeviceControlRow(
                        ctx, c, tmp, textX, k, smartCardLabels[i], smartCardValues[i], optColor, menuOptionPaint);
            } else {
                String opt = menuOptions[i] == null ? "" : menuOptions[i];
                c.drawText(opt, textX, tmp.centerY() + Ui.halfCap(menuOptionPaint), menuOptionPaint);
            }
        }
        c.restore();
    }

    private static int blendColors(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24)
                | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8)
                | Math.round(ab + (bb - ab) * f);
    }
}
