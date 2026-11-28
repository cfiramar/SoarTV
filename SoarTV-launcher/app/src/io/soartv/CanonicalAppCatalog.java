package io.soartv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cross-platform canonical streaming app registry enabling Favorite Apps Shelf and Hidden Apps
 * lists to sync non-destructively across Android TV brands (Xiaomi, TCL, Sony, Hisense, Philips,
 * Chromecast, Fire TV) and non-Android Smart TV platforms (LG webOS, Samsung Tizen).
 *
 * <p><b>Why:</b> App identifiers differ across TV brands and platforms (e.g., Apple TV is
 * {@code com.apple.atve.androidtv.appletv} on generic Android TV, {@code com.apple.atve.sony.appletv}
 * on Sony BRAVIA, {@code com.apple.appletv} on LG webOS, and {@code 3201807016597} on Samsung Tizen).
 * Storing canonical slugs in the cloud ensures switching or mixing TV brands automatically resolves
 * installed apps while never erasing apps that only exist on another TV in the household.
 *
 * <p><b>What:</b> Maps known Android TV packages ↔ canonical slugs, namespaces unknown platform-specific
 * packages as {@code pkg:android:<packageName>}, resolves canonical cloud lists to installed local
 * packages, and performs non-destructive merges when local edits happen on a TV with a subset of apps.
 */
public final class CanonicalAppCatalog {

    public static final String ANDROID_PKG_PREFIX = "pkg:android:";
    public static final String WEBOS_PKG_PREFIX = "pkg:webos:";
    public static final String TIZEN_PKG_PREFIX = "pkg:tizen:";

    private static final Object LOCK = new Object();
    private static final Map<String, List<String>> SLUG_TO_ANDROID_PKGS = new LinkedHashMap<>();
    private static final Map<String, String> ANDROID_PKG_TO_SLUG = new HashMap<>();

    static {
        registerBuiltIn("stremio", "com.stremio.one", "com.stremio.tv");
        registerBuiltIn("youtube",
                "com.google.android.youtube.tv",
                "com.amazon.firetv.youtube",
                "org.smarttube.stable",
                "com.liskovsoft.smarttubetv.beta",
                "com.liskovsoft.smarttubetv");
        registerBuiltIn("appletv",
                "com.apple.atve.androidtv.appletv",
                "com.apple.atve.sony.appletv",
                "com.apple.atve.amazon.appletv");
        registerBuiltIn("max", "com.wbd.stream", "com.hbo.hbonow");
        registerBuiltIn("netflix", "com.netflix.ninja");
        registerBuiltIn("prime",
                "com.amazon.amazonvideo.livingroom",
                "com.amazon.firebat",
                "com.amazon.avod");
        registerBuiltIn("disney", "com.disney.disneyplus");
        registerBuiltIn("hulu", "com.hulu.livingroomplus", "com.hulu.plus");
        registerBuiltIn("paramount", "com.cbs.ott", "com.cbs.ca");
        registerBuiltIn("peacock", "com.peacocktv.peacockandroid", "com.peacock.peacockfiretv");
        registerBuiltIn("now", "com.bskyb.nowtv.beta");
        registerBuiltIn("globoplay", "com.globo.globotv");
        registerBuiltIn("spotify", "com.spotify.tv.android");
        registerBuiltIn("plex", "com.plexapp.android");
        registerBuiltIn("kodi", "org.xbmc.kodi");
        registerBuiltIn("twitch", "tv.twitch.android.app", "tv.twitch.android.viewer");
        registerBuiltIn("crunchyroll", "com.crunchyroll.crunchyroid");
        registerBuiltIn("ytmusic", "com.google.android.youtube.tvmusic");
        registerBuiltIn("applemusic", "com.apple.android.music");
        registerBuiltIn("kinopoisk", "ru.kinopoisk.tv");
        registerBuiltIn("okko", "ru.more.play");
        registerBuiltIn("premier", "gpm.tnt_premier");
        registerBuiltIn("amediateka", "ru.amediateka");
        registerBuiltIn("wink", "ru.rt.video.app.tv");
        registerBuiltIn("hotstar", "in.startv.hotstar");
        registerBuiltIn("sonyliv", "com.sonyliv");
    }

    private CanonicalAppCatalog() {
    }

    private static void registerBuiltIn(String slug, String... packages) {
        registerAliases(slug, Arrays.asList(packages));
    }

    /**
     * Registers one or more Android package aliases for a canonical app slug (Open/Closed extension point
     * used by regional or custom catalogs without hardcoding them in the generic core).
     *
     * @param slug     Lowercase canonical app slug (e.g., {@code "netflix"}).
     * @param packages Android TV package identifiers associated with that service.
     */
    public static void registerAliases(String slug, List<String> packages) {
        if (slug == null || packages == null) return;
        String cleanSlug = normalizeSlug(slug);
        if (cleanSlug.isEmpty()) return;
        synchronized (LOCK) {
            List<String> list = SLUG_TO_ANDROID_PKGS.get(cleanSlug);
            if (list == null) {
                list = new ArrayList<>();
                SLUG_TO_ANDROID_PKGS.put(cleanSlug, list);
            }
            for (String rawPkg : packages) {
                if (rawPkg == null) continue;
                String pkg = rawPkg.trim();
                if (pkg.isEmpty()) continue;
                String lower = pkg.toLowerCase(Locale.ROOT);
                ANDROID_PKG_TO_SLUG.put(lower, cleanSlug);
                if (!containsIgnoreCase(list, pkg)) {
                    list.add(pkg);
                }
            }
        }
    }

    /**
     * Converts a local Android package name (or already-canonical entry) into a canonical cross-platform
     * identifier suitable for cloud sync.
     *
     * @param raw Android package name or existing canonical identifier.
     * @return Canonical slug (e.g., {@code "netflix"}) or namespaced package ({@code "pkg:android:..."}), or {@code ""}.
     */
    public static String toCanonical(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.startsWith(ANDROID_PKG_PREFIX)
                || trimmed.startsWith(WEBOS_PKG_PREFIX)
                || trimmed.startsWith(TIZEN_PKG_PREFIX)) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        synchronized (LOCK) {
            String mapped = ANDROID_PKG_TO_SLUG.get(lower);
            if (mapped != null) return mapped;
            if (SLUG_TO_ANDROID_PKGS.containsKey(lower)) return lower;
        }
        if (trimmed.indexOf('.') >= 0) {
            return ANDROID_PKG_PREFIX + trimmed;
        }
        return lower;
    }

    /**
     * Resolves a canonical entry to an installed Android TV package on the current device, or {@code null}
     * if this device does not have the app (or the entry targets a non-Android platform).
     *
     * @param canonicalEntry    Canonical slug or {@code pkg:<platform>:<id>} entry.
     * @param installedPackages Set of package names installed on this Android TV (case-sensitive or insensitive).
     * @return Matched local Android package name, or {@code null} when not installed on this TV.
     */
    public static String toLocalAndroidPackage(String canonicalEntry, Set<String> installedPackages) {
        if (canonicalEntry == null || installedPackages == null || installedPackages.isEmpty()) return null;
        String entry = canonicalEntry.trim();
        if (entry.isEmpty()) return null;
        if (entry.startsWith(WEBOS_PKG_PREFIX) || entry.startsWith(TIZEN_PKG_PREFIX)) {
            return null;
        }
        Map<String, String> installedLower = new HashMap<>(installedPackages.size() * 2);
        for (String p : installedPackages) {
            if (p != null && !p.isEmpty()) {
                installedLower.put(p.toLowerCase(Locale.ROOT), p);
            }
        }
        if (entry.startsWith(ANDROID_PKG_PREFIX)) {
            String rawPkg = entry.substring(ANDROID_PKG_PREFIX.length()).trim();
            return installedLower.get(rawPkg.toLowerCase(Locale.ROOT));
        }
        String directMatch = installedLower.get(entry.toLowerCase(Locale.ROOT));
        if (directMatch != null) return directMatch;

        String slug = normalizeSlug(entry);
        List<String> candidates;
        synchronized (LOCK) {
            List<String> list = SLUG_TO_ANDROID_PKGS.get(slug);
            candidates = list == null ? Collections.emptyList() : new ArrayList<>(list);
        }
        for (String cand : candidates) {
            String found = installedLower.get(cand.toLowerCase(Locale.ROOT));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Resolves a canonical cloud app list into an ordered deduplicated list of locally installed Android packages.
     *
     * @param cloudCanonical    Ordered canonical app list from cloud profile/TV settings.
     * @param installedPackages Packages installed on this Android TV.
     * @return Ordered list of installed local Android package names.
     */
    public static List<String> resolveCanonicalListToLocal(List<String> cloudCanonical, Set<String> installedPackages) {
        List<String> out = new ArrayList<>();
        if (cloudCanonical == null || cloudCanonical.isEmpty() || installedPackages == null || installedPackages.isEmpty()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String entry : cloudCanonical) {
            String localPkg = toLocalAndroidPackage(entry, installedPackages);
            if (localPkg != null && seen.add(localPkg)) {
                out.add(localPkg);
            }
        }
        return out;
    }

    /**
     * Converts a local Android package list into canonical cross-platform identifiers, preserving any
     * entries from {@code existingCloudCanonical} that are NOT installed on this device (non-destructive merge).
     *
     * <p><b>Why:</b> If TV A (Xiaomi) has a brand-specific or regional app pinned/hidden and the user reorders
     * apps on TV B (Sony or LG webOS) where that app does not exist, TV B must keep TV A's unavailable entry
     * intact in the cloud list instead of wiping it out.
     *
     * @param existingCloudCanonical Previous canonical list stored in the cloud (may be null/empty).
     * @param localOrderedPackages   Current local ordered Android package list on this TV.
     * @param installedOnThisDevice  All Android packages installed on this TV.
     * @return Updated canonical list preserving unavailable cross-brand/cross-platform entries.
     */
    public static List<String> mergeCanonicalListNonDestructive(
            List<String> existingCloudCanonical,
            List<String> localOrderedPackages,
            Set<String> installedOnThisDevice) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (localOrderedPackages != null) {
            for (String pkg : localOrderedPackages) {
                String canonical = toCanonical(pkg);
                if (!canonical.isEmpty()) {
                    merged.add(canonical);
                }
            }
        }
        if (existingCloudCanonical != null) {
            for (String oldEntry : existingCloudCanonical) {
                String normalized = toCanonical(oldEntry);
                if (normalized.isEmpty() || merged.contains(normalized)) continue;
                // Keep entries that are NOT installed on this TV (so TV B never deletes TV A's apps)
                if (installedOnThisDevice == null || toLocalAndroidPackage(normalized, installedOnThisDevice) == null) {
                    merged.add(normalized);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /**
     * Non-destructive merge for unordered hidden app sets across heterogeneous TVs.
     *
     * @param existingCloudCanonical Previous cloud canonical hidden-app set.
     * @param localHiddenPackages    Current hidden local Android package names on this TV.
     * @param installedOnThisDevice  All Android packages installed on this TV.
     * @return Canonical hidden-app list retaining apps from other TVs/platforms not installed here.
     */
    public static List<String> mergeCanonicalSetNonDestructive(
            Set<String> existingCloudCanonical,
            Set<String> localHiddenPackages,
            Set<String> installedOnThisDevice) {
        List<String> existingList = existingCloudCanonical == null
                ? Collections.emptyList() : new ArrayList<>(existingCloudCanonical);
        List<String> localList = localHiddenPackages == null
                ? Collections.emptyList() : new ArrayList<>(localHiddenPackages);
        return mergeCanonicalListNonDestructive(existingList, localList, installedOnThisDevice);
    }

    /** Returns an unmodifiable snapshot of known Android package aliases for {@code slug}. */
    public static List<String> knownAndroidPackages(String slug) {
        String clean = normalizeSlug(slug);
        synchronized (LOCK) {
            List<String> list = SLUG_TO_ANDROID_PKGS.get(clean);
            return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    private static String normalizeSlug(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        for (String s : list) {
            if (s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
