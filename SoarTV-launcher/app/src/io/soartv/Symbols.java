package io.soartv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 * The sizes the layout uses are scaled down ahead of time (warm, in steps of at most 2×) and drawn pixel for pixel at a
 * whole-pixel position: a GPU shrinking a ~150 px PNG to 40 px in one bilinear step skips pixels, and the edges come
 * out jagged. Drawing never makes a bitmap: a size in between (a focus animation) is the closest larger copy, filtered.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** Scaled copies per symbol, newest first; only touched holding PAINT. A few: the layout's sizes change with the
     *  screen and the language, never per frame, and the oldest one goes. */
    private static final int SIZES = 6;
    private static final Map<String, Bitmap[]> SIZED = new HashMap<>();
    private static final Object LOCK = new Object();
    private static final Paint PAINT;
    private static final RectF RECT;
    static {
        Paint p = null;
        RectF r = null;
        try {
            p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            r = new RectF();
        } catch (RuntimeException ignored) {
            // Host JVM unit test environment where android.jar stubs throw RuntimeException("Stub!")
        }
        PAINT = p;
        RECT = r;
    }

    /** Returns true if the icon name is non-empty and supported as either a bundled PNG asset or a procedural vector icon. */
    static boolean isSupportedIcon(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        java.io.File assetPng = new java.io.File("app/assets/symbols/" + ("sun".equals(name) ? "sunny" : ("moon".equals(name) ? "bedtime" : name)) + ".png");
        if (assetPng.isFile()) return true;
        switch (name) {
            case "rt":
            case "rotten_tomatoes":
            case "mc":
            case "metacritic":
            case "search":
            case "tiktok":
            case "instagram":
            case "youtube":
            case "x_social":
            case "folder":
            case "bookmark":
            case "message":
            case "controller":
            case "fan":
            case "ac":
            case "boiler":
            case "shower":
            case "key":
            case "plus":
            case "check":
            case "thumb_up":
            case "thumb_down":
            case "bookmark_add":
            case "reorder":
            case "star":
            case "star_off":
            case "eye":
            case "eye_off":
            case "ban":
            case "trash":
            case "close":
            case "chevron_right":
            case "chevron_left":
            case "chevron_up":
            case "chevron_down":
            case "external_link":
            case "pc":
            case "remote":
            case "restart":
            case "code":
            case "sliders":
            case "sort":
            case "similarity":
            case "thermometer":
            case "temp":
            case "user":
            case "users":
            case "palette":
            case "theme":
            case "clock":
            case "timer":
            case "location":
            case "pin":
            case "shield":
            case "privacy":
            case "power":
            case "globe":
            case "language":
            case "keyboard":
            case "storage":
            case "info":
            case "bolt":
            case "lightning":
            case "battery":
            case "clock_liquid":
            case "clock_pill":
            case "clock_stacked":
            case "clock_split":
            case "clock_serif":
            case "clock_mono":
            case "clock_minimal":
            case "clock_outline":
            case "clock_editorial":
            case "clock_classic":
            case "gallery_mixed":
            case "gallery_nature":
            case "gallery_macro":
            case "gallery_urban":
            case "gallery_wildlife":
                return true;
            default:
                return false;
        }
    }

    static Bitmap get(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        // Map common alias names to bundled PNG symbol assets where available
        if ("sun".equals(name)) name = "sunny";
        else if ("moon".equals(name)) name = "bedtime";
        else if ("rotten_tomatoes".equals(name)) name = "rt";
        else if ("metacritic".equals(name)) name = "mc";
        else if ("sort".equals(name) || "similarity".equals(name)) name = "sliders";
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            if (c != null) {
                try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                    b = BitmapFactory.decodeStream(in);
                    if (b != null) {
                        b = b.extractAlpha();
                        b.setHasMipMap(true);
                    }
                } catch (IOException ignored) {
                }
            }
            if (b == null) {
                b = buildProceduralSymbol(name);
            }
            CACHE.put(name, b);
            return b;
        }
    }

    /**
     * Renders crisp procedural icons (such as {@code "search"}, {@code "tiktok"}, and {@code "controller"})
     * at the native 172px symbol scale when not present as a static PNG asset.
     */
    private static Bitmap buildProceduralSymbol(String name) {
        if (name == null) return null;
        try {
            int size = 160;
            Bitmap rgba = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(rgba);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            if ("rt".equals(name) || "rotten_tomatoes".equals(name)) {
                // Rotten Tomatoes: Plump tomato silhouette, 5-point leafy calyx star, and curved top stalk
                p.setStyle(Paint.Style.FILL);
                // 1. Tomato body with natural top-center depression (dimple) and rounded shoulders
                Path tomato = new Path();
                tomato.moveTo(80f, 54f);
                tomato.cubicTo(56f, 42f, 18f, 62f, 18f, 94f);
                tomato.cubicTo(18f, 128f, 52f, 144f, 80f, 144f);
                tomato.cubicTo(108f, 144f, 142f, 128f, 142f, 94f);
                tomato.cubicTo(142f, 62f, 104f, 42f, 80f, 54f);
                tomato.close();
                cv.drawPath(tomato, p);
                // 2. Leafy calyx star (5 organic leaf points radiating from center (80, 48))
                Path calyx = new Path();
                calyx.moveTo(80f, 48f);
                calyx.lineTo(50f, 24f);   // Top-left leaf tip
                calyx.lineTo(64f, 44f);   // Notch
                calyx.lineTo(28f, 44f);   // Far-left leaf tip
                calyx.lineTo(64f, 54f);   // Notch
                calyx.lineTo(80f, 68f);   // Bottom-center leaf tip
                calyx.lineTo(96f, 54f);   // Notch
                calyx.lineTo(132f, 44f);  // Far-right leaf tip
                calyx.lineTo(96f, 44f);   // Notch
                calyx.lineTo(110f, 24f);  // Top-right leaf tip
                calyx.close();
                cv.drawPath(calyx, p);
                // 3. Center stem stalk curving upwards from calyx center
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(10f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path stem = new Path();
                stem.moveTo(80f, 46f);
                stem.quadTo(78f, 30f, 88f, 18f);
                cv.drawPath(stem, p);
            } else if ("mc".equals(name) || "metacritic".equals(name)) {
                // Metacritic: Crisp rounded squircle badge with stencil cutout lowercase 'm'
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(new RectF(18f, 18f, 142f, 142f), 32f, 32f, p);
                Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
                clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                clear.setStyle(Paint.Style.STROKE);
                clear.setStrokeWidth(16f);
                clear.setStrokeCap(Paint.Cap.ROUND);
                clear.setStrokeJoin(Paint.Join.ROUND);
                // Three vertical stems: left (46), middle (80), right (114)
                cv.drawLine(46f, 67f, 46f, 114f, clear);
                cv.drawLine(80f, 67f, 80f, 114f, clear);
                cv.drawLine(114f, 67f, 114f, 114f, clear);
                // Two connecting top arches tangentially joined to vertical stems
                cv.drawArc(new RectF(46f, 50f, 80f, 84f), 180f, 180f, false, clear);
                cv.drawArc(new RectF(80f, 50f, 114f, 84f), 180f, 180f, false, clear);
            } else if ("search".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(16f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawCircle(68f, 68f, 42f, p);
            cv.drawLine(100f, 100f, 140f, 140f, p);
        } else if ("tiktok".equals(name)) {
            // Stylised TikTok musical note icon
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(16f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // Vertical stem
            cv.drawLine(96f, 22f, 96f, 108f, p);
            // Top right wave hook
            android.graphics.Path hook = new android.graphics.Path();
            hook.moveTo(96f, 26f);
            hook.cubicTo(96f, 52f, 118f, 68f, 140f, 68f);
            cv.drawPath(hook, p);
            // Bottom note loop
            RectF loop = new RectF(32f, 80f, 96f, 142f);
            cv.drawArc(loop, 0f, 305f, false, p);
        } else if ("mic".equals(name)) {
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(new RectF(58f, 20f, 102f, 92f), 22f, 22f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawArc(new RectF(38f, 52f, 122f, 118f), 0f, 180f, false, p);
            cv.drawLine(80f, 118f, 80f, 142f, p);
            cv.drawLine(56f, 142f, 104f, 142f, p);
        } else if ("thumb_up".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(22f, 68f, 52f, 134f), 8f, 8f, p);
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(52f, 74f);
            body.lineTo(78f, 26f);
            body.quadTo(96f, 24f, 96f, 44f);
            body.lineTo(92f, 68f);
            body.lineTo(130f, 68f);
            body.quadTo(142f, 68f, 138f, 84f);
            body.lineTo(126f, 124f);
            body.quadTo(122f, 134f, 108f, 134f);
            body.lineTo(52f, 134f);
            body.close();
            cv.drawPath(body, p);
        } else if ("thumb_down".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(22f, 26f, 52f, 92f), 8f, 8f, p);
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(52f, 86f);
            body.lineTo(78f, 134f);
            body.quadTo(96f, 136f, 96f, 116f);
            body.lineTo(92f, 92f);
            body.lineTo(130f, 92f);
            body.quadTo(142f, 92f, 138f, 76f);
            body.lineTo(126f, 36f);
            body.quadTo(122f, 26f, 108f, 26f);
            body.lineTo(52f, 26f);
            body.close();
            cv.drawPath(body, p);
        } else if ("bookmark_add".equals(name) || "plus".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(80f, 34f, 80f, 126f, p);
            cv.drawLine(34f, 80f, 126f, 80f, p);
        } else if ("reorder".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // Up arrow
            cv.drawLine(56f, 62f, 80f, 34f, p);
            cv.drawLine(104f, 62f, 80f, 34f, p);
            // Down arrow
            cv.drawLine(56f, 98f, 80f, 126f, p);
            cv.drawLine(104f, 98f, 80f, 126f, p);
            // Center lines
            cv.drawLine(42f, 80f, 118f, 80f, p);
        } else if ("star".equals(name) || "star_off".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path star = new android.graphics.Path();
            float cx = 80f, cy = 82f, rOut = 54f, rIn = 24f;
            for (int i = 0; i < 10; i++) {
                double ang = Math.toRadians(-90 + i * 36);
                float r = (i % 2 == 0) ? rOut : rIn;
                float x = cx + (float) (Math.cos(ang) * r);
                float y = cy + (float) (Math.sin(ang) * r);
                if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
            }
            star.close();
            cv.drawPath(star, p);
            if ("star_off".equals(name)) {
                cv.drawLine(28f, 28f, 132f, 132f, p);
            }
        } else if ("eye".equals(name) || "eye_off".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path eye = new android.graphics.Path();
            eye.moveTo(18f, 80f);
            eye.quadTo(80f, 26f, 142f, 80f);
            eye.quadTo(80f, 134f, 18f, 80f);
            eye.close();
            cv.drawPath(eye, p);
            cv.drawCircle(80f, 80f, 18f, p);
            if ("eye_off".equals(name)) {
                cv.drawLine(28f, 28f, 132f, 132f, p);
            }
        } else if ("ban".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawCircle(80f, 80f, 52f, p);
            cv.drawLine(43f, 43f, 117f, 117f, p);
        } else if ("trash".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(32f, 44f, 128f, 44f, p);
            cv.drawRoundRect(new RectF(60f, 24f, 100f, 44f), 6f, 6f, p);
            cv.drawRoundRect(new RectF(42f, 44f, 118f, 136f), 12f, 12f, p);
            cv.drawLine(68f, 66f, 68f, 114f, p);
            cv.drawLine(92f, 66f, 92f, 114f, p);
        } else if ("close".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(42f, 42f, 118f, 118f, p);
            cv.drawLine(118f, 42f, 42f, 118f, p);
        } else if ("chevron_right".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(62f, 36f, 106f, 80f, p);
            cv.drawLine(106f, 80f, 62f, 124f, p);
        } else if ("chevron_left".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(98f, 36f, 54f, 80f, p);
            cv.drawLine(54f, 80f, 98f, 124f, p);
        } else if ("chevron_up".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(36f, 98f, 80f, 54f, p);
            cv.drawLine(80f, 54f, 124f, 98f, p);
        } else if ("chevron_down".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(36f, 62f, 80f, 106f, p);
            cv.drawLine(80f, 106f, 124f, 62f, p);
        } else if ("external_link".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path box = new Path();
            box.moveTo(86f, 34f);
            box.lineTo(36f, 34f);
            box.lineTo(36f, 124f);
            box.lineTo(126f, 124f);
            box.lineTo(126f, 74f);
            cv.drawPath(box, p);
            cv.drawLine(76f, 84f, 130f, 30f, p);
            cv.drawLine(96f, 30f, 130f, 30f, p);
            cv.drawLine(130f, 30f, 130f, 64f, p);
        } else if ("pc".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(22f, 30f, 138f, 104f), 14f, 14f, p);
            cv.drawLine(80f, 104f, 80f, 128f, p);
            cv.drawLine(50f, 128f, 110f, 128f, p);
        } else if ("remote".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(46f, 18f, 114f, 142f), 22f, 22f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 48f, 12f, p);
            cv.drawCircle(68f, 84f, 6f, p);
            cv.drawCircle(92f, 84f, 6f, p);
            cv.drawCircle(68f, 108f, 6f, p);
            cv.drawCircle(92f, 108f, 6f, p);
        } else if ("restart".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawArc(new RectF(30f, 30f, 130f, 130f), -55f, 285f, false, p);
            cv.drawLine(104f, 24f, 126f, 44f, p);
            cv.drawLine(126f, 44f, 96f, 52f, p);
        } else if ("code".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(58f, 48f, 26f, 80f, p);
            cv.drawLine(26f, 80f, 58f, 112f, p);
            cv.drawLine(102f, 48f, 134f, 80f, p);
            cv.drawLine(134f, 80f, 102f, 112f, p);
            cv.drawLine(88f, 38f, 72f, 122f, p);
        } else if ("sliders".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(28f, 46f, 132f, 46f, p);
            cv.drawLine(28f, 80f, 132f, 80f, p);
            cv.drawLine(28f, 114f, 132f, 114f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(62f, 46f, 14f, p);
            cv.drawCircle(104f, 80f, 14f, p);
            cv.drawCircle(52f, 114f, 14f, p);
        } else if ("check".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(15f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawLine(34f, 84f, 66f, 116f, p);
            cv.drawLine(66f, 116f, 128f, 46f, p);
        } else if ("thermometer".equals(name) || "temp".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // Thermometer tube + bottom bulb
            cv.drawRoundRect(new RectF(64f, 22f, 96f, 104f), 16f, 16f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 116f, 24f, p);
            cv.drawLine(80f, 58f, 80f, 116f, p);
            // Measurement ticks on right side
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10f);
            cv.drawLine(108f, 38f, 122f, 38f, p);
            cv.drawLine(108f, 58f, 118f, 58f, p);
            cv.drawLine(108f, 78f, 122f, 78f, p);
        } else if ("user".equals(name) || "users".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawCircle(80f, 52f, 24f, p);
            cv.drawArc(new RectF(32f, 88f, 128f, 152f), 180f, 180f, false, p);
        } else if ("palette".equals(name) || "theme".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            cv.drawCircle(80f, 80f, 52f, p);
            p.setStyle(Paint.Style.FILL);
            android.graphics.Path half = new android.graphics.Path();
            half.addArc(new RectF(28f, 28f, 132f, 132f), -90f, 180f);
            half.close();
            cv.drawPath(half, p);
        } else if ("clock".equals(name) || "timer".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawCircle(80f, 80f, 52f, p);
            cv.drawLine(80f, 48f, 80f, 80f, p);
            cv.drawLine(80f, 80f, 106f, 96f, p);
        } else if ("location".equals(name) || "pin".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path pin = new android.graphics.Path();
            pin.moveTo(80f, 138f);
            pin.cubicTo(48f, 102f, 36f, 80f, 36f, 62f);
            pin.arcTo(new RectF(36f, 20f, 124f, 104f), 180f, 180f, false);
            pin.cubicTo(124f, 80f, 112f, 102f, 80f, 138f);
            pin.close();
            cv.drawPath(pin, p);
            cv.drawCircle(80f, 62f, 15f, p);
        } else if ("shield".equals(name) || "privacy".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            android.graphics.Path sh = new android.graphics.Path();
            sh.moveTo(80f, 22f);
            sh.lineTo(128f, 40f);
            sh.lineTo(128f, 78f);
            sh.quadTo(128f, 118f, 80f, 138f);
            sh.quadTo(32f, 118f, 32f, 78f);
            sh.lineTo(32f, 40f);
            sh.close();
            cv.drawPath(sh, p);
        } else if ("power".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawArc(new RectF(34f, 38f, 126f, 130f), -50f, 280f, false, p);
            cv.drawLine(80f, 22f, 80f, 74f, p);
        } else if ("globe".equals(name) || "language".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            cv.drawCircle(80f, 80f, 52f, p);
            cv.drawOval(new RectF(54f, 28f, 106f, 132f), p);
            cv.drawLine(28f, 80f, 132f, 80f, p);
        } else if ("keyboard".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            cv.drawRoundRect(new RectF(22f, 42f, 138f, 118f), 14f, 14f, p);
            p.setStyle(Paint.Style.FILL);
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 4; c++) {
                    cv.drawCircle(44f + c * 24f, 64f + r * 20f, 5f, p);
                }
            }
            cv.drawRoundRect(new RectF(50f, 96f, 110f, 104f), 4f, 4f, p);
        } else if ("storage".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            cv.drawRoundRect(new RectF(26f, 34f, 134f, 74f), 10f, 10f, p);
            cv.drawRoundRect(new RectF(26f, 86f, 134f, 126f), 10f, 10f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(48f, 54f, 6f, p);
            cv.drawCircle(48f, 106f, 6f, p);
        } else if ("info".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawCircle(80f, 80f, 52f, p);
            cv.drawLine(80f, 74f, 80f, 110f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 50f, 8f, p);
        } else if ("shower".equals(name) || "boiler".equals(name)) {
            // Crisp overhead shower pipe + angled shower head + falling water drops
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // Pipe from top-left curving down to shower head center
            android.graphics.Path pipe = new android.graphics.Path();
            pipe.moveTo(30f, 26f);
            pipe.lineTo(68f, 26f);
            pipe.quadTo(88f, 26f, 88f, 46f);
            cv.drawPath(pipe, p);
            // Shower head dome & flat nozzle plate
            cv.drawArc(new RectF(52f, 44f, 124f, 92f), 180f, 180f, true, p);
            cv.drawLine(46f, 68f, 130f, 68f, p);
            // Water droplets below
            p.setStrokeWidth(10f);
            cv.drawLine(60f, 88f, 54f, 104f, p);
            cv.drawLine(88f, 88f, 88f, 106f, p);
            cv.drawLine(116f, 88f, 122f, 104f, p);
            cv.drawLine(50f, 120f, 44f, 136f, p);
            cv.drawLine(76f, 122f, 74f, 138f, p);
            cv.drawLine(102f, 122f, 104f, 138f, p);
            cv.drawLine(126f, 120f, 132f, 136f, p);
        } else if ("ac".equals(name)) {
            // Crisp wall-mounted split Air Conditioner unit + cool airflow waves
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // AC unit body
            cv.drawRoundRect(new RectF(20f, 28f, 140f, 84f), 14f, 14f, p);
            // Bottom louver vent line
            p.setStrokeWidth(9f);
            cv.drawLine(36f, 68f, 124f, 68f, p);
            // Status LED dot on right
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(122f, 46f, 5f, p);
            // Three curved airflow breeze waves underneath
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10f);
            android.graphics.Path w1 = new android.graphics.Path();
            w1.moveTo(46f, 98f);
            w1.quadTo(36f, 114f, 46f, 126f);
            w1.quadTo(56f, 136f, 44f, 144f);
            cv.drawPath(w1, p);
            android.graphics.Path w2 = new android.graphics.Path();
            w2.moveTo(80f, 98f);
            w2.quadTo(70f, 114f, 80f, 126f);
            w2.quadTo(90f, 136f, 78f, 144f);
            cv.drawPath(w2, p);
            android.graphics.Path w3 = new android.graphics.Path();
            w3.moveTo(114f, 98f);
            w3.quadTo(104f, 114f, 114f, 126f);
            w3.quadTo(124f, 136f, 112f, 144f);
            cv.drawPath(w3, p);
        } else if ("key".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawCircle(54f, 80f, 28f, p);
            cv.drawLine(82f, 80f, 138f, 80f, p);
        } else if ("bolt".equals(name) || "lightning".equals(name)) {
            p.setStyle(Paint.Style.FILL);
            Path bolt = new Path();
            bolt.moveTo(88f, 22f);
            bolt.lineTo(44f, 88f);
            bolt.lineTo(76f, 88f);
            bolt.lineTo(68f, 138f);
            bolt.lineTo(116f, 72f);
            bolt.lineTo(84f, 72f);
            bolt.close();
            cv.drawPath(bolt, p);
        } else if ("battery".equals(name)) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawRoundRect(new RectF(36f, 50f, 116f, 110f), 12f, 12f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRoundRect(new RectF(118f, 68f, 128f, 92f), 4f, 4f, p);
        } else if ("clock_liquid".equals(name)) {
            // Liquid Glass Clock: Rounded refractive squircle frame, specular top-left glass arc, and clock hands
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(24f, 24f, 136f, 136f), 34f, 34f, p);
            p.setStrokeWidth(7f);
            cv.drawArc(new RectF(38f, 38f, 122f, 122f), 195f, 75f, false, p);
            p.setStrokeWidth(11f);
            cv.drawLine(80f, 82f, 80f, 52f, p);
            cv.drawLine(80f, 82f, 106f, 96f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 82f, 7f, p);
        } else if ("clock_minimal".equals(name)) {
            // Minimal Clock: Frameless open dial with 4 cardinal tick dots and ultra-clean thin hands
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 24f, 6.5f, p);
            cv.drawCircle(136f, 80f, 6.5f, p);
            cv.drawCircle(80f, 136f, 6.5f, p);
            cv.drawCircle(24f, 80f, 6.5f, p);
            cv.drawCircle(80f, 80f, 6f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(8.5f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(80f, 80f, 80f, 42f, p);
            cv.drawLine(80f, 80f, 114f, 80f, p);
        } else if ("clock_editorial".equals(name)) {
            // Editorial Clock: Bold architectural double-ring bezel with high-contrast heavy hands
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            cv.drawCircle(80f, 80f, 56f, p);
            p.setStrokeWidth(5f);
            cv.drawCircle(80f, 80f, 41f, p);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.SQUARE);
            cv.drawLine(80f, 80f, 80f, 48f, p);
            cv.drawLine(80f, 80f, 106f, 80f, p);
        } else if ("clock_pill".equals(name)) {
            // Frosted Pill Clock: Stadium glass capsule framing compact horizontal digital bars & colon
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawRoundRect(new RectF(16f, 44f, 144f, 116f), 36f, 36f, p);
            p.setStrokeWidth(9f);
            cv.drawLine(42f, 80f, 64f, 80f, p);
            cv.drawLine(96f, 80f, 118f, 80f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 70f, 5f, p);
            cv.drawCircle(80f, 90f, 5f, p);
        } else if ("clock_stacked".equals(name)) {
            // Stacked Duo Clock: Two-story vertical architectural blocks (HH stacked over MM)
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10.5f);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(34f, 22f, 74f, 72f), 10f, 10f, p);
            cv.drawRoundRect(new RectF(86f, 22f, 126f, 72f), 10f, 10f, p);
            cv.drawRoundRect(new RectF(34f, 88f, 74f, 138f), 10f, 10f, p);
            cv.drawRoundRect(new RectF(86f, 88f, 126f, 138f), 10f, 10f, p);
        } else if ("clock_split".equals(name)) {
            // Split Cards Clock: Twin Braun-style flip-card modules with horizontal mechanical split line
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10.5f);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(18f, 36f, 74f, 124f), 12f, 12f, p);
            cv.drawRoundRect(new RectF(86f, 36f, 142f, 124f), 12f, 12f, p);
            p.setStrokeWidth(6.5f);
            cv.drawLine(22f, 80f, 70f, 80f, p);
            cv.drawLine(90f, 80f, 138f, 80f, p);
        } else if ("clock_serif".equals(name)) {
            // Editorial Serif Clock: Classical Roman numeral XII pillars with top & bottom serif bars
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(34f, 34f, 126f, 34f, p);
            cv.drawLine(34f, 126f, 126f, 126f, p);
            cv.drawLine(46f, 34f, 74f, 126f, p);
            cv.drawLine(74f, 34f, 46f, 126f, p);
            cv.drawLine(98f, 34f, 98f, 126f, p);
            cv.drawLine(118f, 34f, 118f, 126f, p);
        } else if ("clock_mono".equals(name)) {
            // Tabular Mono Clock: Segmented 7-segment technical numeral frame with twin colon dots
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10f);
            p.setStrokeJoin(Paint.Join.MITER);
            cv.drawRoundRect(new RectF(24f, 38f, 64f, 122f), 6f, 6f, p);
            cv.drawLine(28f, 80f, 60f, 80f, p);
            cv.drawRoundRect(new RectF(96f, 38f, 136f, 122f), 6f, 6f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 64f, 6f, p);
            cv.drawCircle(80f, 96f, 6f, p);
        } else if ("clock_outline".equals(name)) {
            // Fine Outline Clock: Double-contour hollow geometric ring & hollow hands
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(7.5f);
            cv.drawCircle(80f, 80f, 58f, p);
            cv.drawCircle(80f, 80f, 44f, p);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(80f, 78f, 80f, 50f, p);
            cv.drawLine(80f, 80f, 104f, 92f, p);
        } else if ("clock_classic".equals(name)) {
            // Classic Clock: Traditional circular timepiece with 4 hour tick marks and balanced hands
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawCircle(80f, 80f, 56f, p);
            p.setStrokeWidth(8f);
            cv.drawLine(80f, 26f, 80f, 38f, p);
            cv.drawLine(134f, 80f, 122f, 80f, p);
            cv.drawLine(80f, 134f, 80f, 122f, p);
            cv.drawLine(26f, 80f, 38f, 80f, p);
            p.setStrokeWidth(10.5f);
            cv.drawLine(80f, 80f, 80f, 48f, p);
            cv.drawLine(80f, 80f, 106f, 96f, p);
        } else if ("gallery_mixed".equals(name)) {
            // Mixed Top Wallpapers: Layered gallery photo frames with alpine peaks and sun
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(10.5f);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(22f, 44f, 114f, 126f), 14f, 14f, p);
            cv.drawRoundRect(new RectF(44f, 26f, 138f, 108f), 14f, 14f, p);
            Path mount = new Path();
            mount.moveTo(54f, 98f);
            mount.lineTo(78f, 70f);
            mount.lineTo(96f, 88f);
            mount.lineTo(114f, 66f);
            mount.lineTo(128f, 98f);
            cv.drawPath(mount, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(72f, 50f, 8f, p);
        } else if ("gallery_nature".equals(name)) {
            // Nature Photography: Twin alpine mountain peaks with sun disc above the valley
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(116f, 42f, 14f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path peaks = new Path();
            peaks.moveTo(18f, 124f);
            peaks.lineTo(62f, 48f);
            peaks.lineTo(96f, 102f);
            peaks.lineTo(114f, 74f);
            peaks.lineTo(142f, 124f);
            peaks.close();
            cv.drawPath(peaks, p);
        } else if ("gallery_macro".equals(name)) {
            // Macro Photography: Botanical leaf silhouette with vein and crystalline morning dewdrop
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11.5f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path leaf = new Path();
            leaf.moveTo(30f, 130f);
            leaf.quadTo(28f, 42f, 126f, 28f);
            leaf.quadTo(114f, 126f, 30f, 130f);
            cv.drawPath(leaf, p);
            cv.drawLine(26f, 134f, 96f, 64f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(122f, 114f, 12f, p);
        } else if ("gallery_urban".equals(name)) {
            // Urban Photography: Architectural skyline towers with illuminated windows
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(11f);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(26f, 62f, 68f, 134f), 6f, 6f, p);
            cv.drawRoundRect(new RectF(68f, 28f, 118f, 134f), 6f, 6f, p);
            cv.drawLine(18f, 134f, 142f, 134f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(82f, 46f, 92f, 58f, p);
            cv.drawRect(98f, 46f, 108f, 58f, p);
            cv.drawRect(82f, 68f, 92f, 80f, p);
            cv.drawRect(98f, 68f, 108f, 80f, p);
            cv.drawRect(42f, 80f, 52f, 92f, p);
        } else if ("instagram".equals(name)) {
            // Instagram camera glyph: rounded square + lens circle + top-right dot
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(26f, 26f, 134f, 134f), 30f, 30f, p);
            cv.drawCircle(80f, 80f, 26f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(114f, 46f, 8f, p);
        } else if ("youtube".equals(name)) {
            // YouTube Shorts / video play pill
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(18f, 36f, 142f, 124f), 26f, 26f, p);
            p.setStyle(Paint.Style.FILL);
            Path tri = new Path();
            tri.moveTo(68f, 58f);
            tri.lineTo(106f, 80f);
            tri.lineTo(68f, 102f);
            tri.close();
            cv.drawPath(tri, p);
        } else if ("x_social".equals(name)) {
            // X social stylized diagonal strokes
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(34f, 32f, 126f, 128f, p);
            cv.drawLine(126f, 32f, 34f, 128f, p);
        } else if ("folder".equals(name)) {
            // Watch Later folder glyph
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path fld = new Path();
            fld.moveTo(22f, 44f);
            fld.lineTo(62f, 44f);
            fld.lineTo(74f, 58f);
            fld.lineTo(138f, 58f);
            fld.lineTo(138f, 126f);
            fld.lineTo(22f, 126f);
            fld.close();
            cv.drawPath(fld, p);
        } else if ("bookmark".equals(name)) {
            // Filled Watch Later saved bookmark
            p.setStyle(Paint.Style.FILL);
            Path bm = new Path();
            bm.moveTo(42f, 26f);
            bm.lineTo(118f, 26f);
            bm.lineTo(118f, 134f);
            bm.lineTo(80f, 106f);
            bm.lineTo(42f, 134f);
            bm.close();
            cv.drawPath(bm, p);
        } else if ("message".equals(name)) {
            // Chat text bubble glyph
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawRoundRect(new RectF(24f, 30f, 136f, 108f), 22f, 22f, p);
            cv.drawLine(46f, 108f, 36f, 132f, p);
            cv.drawLine(36f, 132f, 68f, 108f, p);
        } else if ("gallery_wildlife".equals(name)) {
            // Wildlife Photography: Soaring eagle / bird silhouette with curved wingspan
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(12f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path wingL = new Path();
            wingL.moveTo(80f, 92f);
            wingL.quadTo(48f, 44f, 18f, 62f);
            cv.drawPath(wingL, p);
            Path wingR = new Path();
            wingR.moveTo(80f, 92f);
            wingR.quadTo(112f, 44f, 142f, 62f);
            cv.drawPath(wingR, p);
            Path body = new Path();
            body.moveTo(64f, 118f);
            body.lineTo(80f, 74f);
            body.lineTo(96f, 118f);
            cv.drawPath(body, p);
        } else {
            // Fallback procedural icon so no row ever lacks an icon
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(13f);
            cv.drawCircle(80f, 80f, 46f, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(80f, 80f, 14f, p);
        }
        Bitmap alpha = rgba.extractAlpha();
        rgba.recycle();
        if (alpha != null) alpha.setHasMipMap(true);
        return alpha;
        } catch (RuntimeException e) {
            // Headless JVM unit test environment where android.jar stubs throw RuntimeException("Stub!")
            return null;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** The point size that draws the symbol's bitmap at the given height. */
    static float pointSizeForHeight(Context c, String name, float height) {
        Bitmap b = get(c, name);
        return b == null ? 0 : height * PX_PER_POINT / b.getHeight();
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null || PAINT == null) return;
        synchronized (LOCK) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, name, b, cx, cy, pointSize, color);
        }
    }

    /** Scales `pointSize` ahead of time (the layout step, or a background thread), so drawing it is pixel for pixel. */
    static void warm(Context c, String name, float pointSize) {
        Bitmap b = pointSize > 0 ? get(c, name) : null;   // 0 before the first layout
        if (b == null) return;
        int w = px(b.getWidth(), pointSize), h = px(b.getHeight(), pointSize);
        if (w >= b.getWidth() || h >= b.getHeight()) return;   // full size or larger: the PNG itself
        synchronized (LOCK) {
            Bitmap[] sizes = SIZED.get(name);
            if (sizes == null) SIZED.put(name, sizes = new Bitmap[SIZES]);
            for (Bitmap s : sizes) if (s != null && s.getWidth() == w && s.getHeight() == h) return;
            Bitmap s = b;
            while (s.getWidth() / 2 >= w && s.getHeight() / 2 >= h) s = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
            System.arraycopy(sizes, 0, sizes, 1, SIZES - 1);
            sizes[0] = Bitmap.createScaledBitmap(s, w, h, true);
        }
    }

    private static int px(int png, float pointSize) {
        return Math.max(1, Math.round(png * pointSize / PX_PER_POINT));
    }

    private static void drawLocked(Canvas canvas, String name, Bitmap b, float cx, float cy, float pointSize, int color) {
        PAINT.setColor(color);
        int w = px(b.getWidth(), pointSize), h = px(b.getHeight(), pointSize);
        // The smallest copy at least this size (the PNG when there's none).
        Bitmap s = b;
        Bitmap[] sizes = SIZED.get(name);
        if (sizes != null) {
            for (Bitmap z : sizes) {
                if (z != null && z.getWidth() >= w && z.getHeight() >= h && z.getWidth() < s.getWidth()) s = z;
            }
        }
        if (s != b && s.getWidth() == w && s.getHeight() == h) {
            canvas.drawBitmap(s, Math.round(cx - w / 2f), Math.round(cy - h / 2f), PAINT);
            return;
        }
        float fw = b.getWidth() * pointSize / PX_PER_POINT, fh = b.getHeight() * pointSize / PX_PER_POINT;
        RECT.set(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2);
        canvas.drawBitmap(s, null, RECT, PAINT);
    }

    /** Every symbol weather() returns, warmed at `pointSize` (the card shows whichever the forecast brings). */
    static void warmWeather(Context c, float pointSize) {
        for (String name : new String[] {"sunny", "clear_night", "partly_cloudy_day", "partly_cloudy_night", "cloud",
                "foggy", "rainy", "snowflake", "thunderstorm"}) {
            warm(c, name, pointSize);
        }
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
