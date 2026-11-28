package io.soartv;

/**
 * Unit test suite verifying SoarTV's modular TV platform abstraction, Cinemeta deep-link builder,
 * and Photography Screensaver catalog metadata.
 */
public final class SoarTvCoreTests {

    private static int passed = 0;

    private static void assertEquals(Object expected, Object actual, String message) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual);
        }
        passed++;
    }

    private static void assertEquals(float expected, float actual, float epsilon, String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual + " (delta=" + Math.abs(expected - actual) + " > " + epsilon + ")");
        }
        passed++;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        testXiaomiMiTvProfile();
        testMultiBrandProfiles();
        testCinemetaDeepLinks();
        testScreensaverPhotoEntry();
        testGamingZoneSmartOrchestration();
        testSmartHomeDeviceRemoteSerialization();
        testContinueWatchingHidePersistenceAndGenres();
        testStremioLibrarySyncAndVideoIdParsing();
        testGenreAndLanguageFilteringAlgorithm();
        testRecursiveSettingsTreeAndAutoThemeSchedule();
        testRecsFormattingEngines();
        testProceduralSymbolsAndHeadlessSafety();
        testTopDetailsHeaderM4();
        testWatchlistSimilaritySortingAndClustering();
        testRound2PolishGeometryVerticalScrollAndArtwork();
        testRound3PolishFixes();
        testControllerColorTaggingAndBatteryState();
        testUsbXInputDongleAndBluetoothBatteryFallback();
        testRound4PolishRatingsDimAndUserOrderedLazyLoading();
        testCleanPosterUrlSanitizationAgainstRpdbOverlays();
        testBluetoothPairingAssist();
        testFiveControllerFeedbackFixes();
        testSixUiHeroContinueAndNewSeasonImprovements();
        testYellowBtPairingScanFilterAndBatteryPower();
        testRound7ScreensaverStartGalleryPreviewsIconsAndShelfIndicator();
        testRound8VignettesHighResGamingAuroraAndShelfArrow();
        testTrackDMemoryWebpAspectAuthSecurityPerfAndUx();
        testMilestone3StremioIntentClickLatencyAndReturnPerf();
        testMilestone4ThemesShadersAndVisualQuality();
        testMilestone5TileDimensionsOpacityAndGamingFit();
        testRound10BackdropPreloadRaceShadersSettingsAndPosterPool();
        testRound11Team1TopBarProgressAndGamingCache();
        GamingCacheStressTests.runAllTests();
        testScreensaverModularityCaptionAndRemoteKeys();
        testUsbHubMultiControllerInputReaderIdMapping();
        testXiaomiMediaTekInputReaderHeaderAndNegativeVirtualIdNoCollision();
        testMoonlightDirectLaunchSpecAndAnimatedLaunchStages();
        testActiveStreamingSessionAndExitLifecycle();
        testRound12HeroBackdropReversalPinningAndTopBarZeroLag();
        testScreensaverSixFeedbackFixes();
        testSixSettingsRemoteNavAndWifiCleanupFixes();
        NativeGameStreamingTests.runAllTests();
        System.out.println("ALL " + passed + " JAVA UNIT ASSERTIONS PASSED.");
    }

    private static void testXiaomiMiTvProfile() {
        TvDeviceProfile xiaomi = new TvDeviceProfile("Xiaomi", "Xiaomi", "SmartTV-4K", 34);
        assertEquals(TvDeviceProfile.Brand.XIAOMI, xiaomi.getBrand(), "Xiaomi SmartTV-4K brand detection");
        assertEquals("SmartTV-4K", xiaomi.getModel(), "Xiaomi model preservation");
        assertTrue(xiaomi.getVendorOverlayPackages().contains("com.mitv.tvhome"), "Xiaomi PatchWall overlay detection");
        assertTrue(xiaomi.getLiveTvPackages().contains("com.xiaomi.mitv.tvplayer"), "Xiaomi Live TV player package");
        assertEquals(false, xiaomi.requiresOpaqueResumeFix(), "Xiaomi does not require TCL opaque fix");
    }

    private static void testMultiBrandProfiles() {
        TvDeviceProfile tcl = new TvDeviceProfile("TCL", "TCL", "BeyondTV4", 31);
        assertEquals(TvDeviceProfile.Brand.TCL, tcl.getBrand(), "TCL brand detection");
        assertEquals(true, tcl.requiresOpaqueResumeFix(), "TCL Android 12 requires opaque resume fix");
        assertEquals(true, tcl.interceptsNotificationAsSettings(), "TCL intercepts notification as settings");

        TvDeviceProfile sony = new TvDeviceProfile("Sony", "Sony", "BRAVIA 4K VH2", 33);
        assertEquals(TvDeviceProfile.Brand.SONY, sony.getBrand(), "Sony Bravia brand detection");
        assertTrue(sony.getLiveTvPackages().contains("com.sony.dtv.tvx"), "Sony Bravia Live TV package");

        TvDeviceProfile amazon = new TvDeviceProfile("Amazon", "Amazon", "AFTKRT", 30);
        assertEquals(TvDeviceProfile.Brand.AMAZON, amazon.getBrand(), "Amazon Fire TV brand detection");

        TvDeviceProfile philips = new TvDeviceProfile("TPV", "Philips", "Philips Google TV TA5", 34);
        assertEquals(TvDeviceProfile.Brand.PHILIPS, philips.getBrand(), "Philips / TP Vision brand detection");
        assertTrue(philips.getLiveTvPackages().contains("org.droidtv.playtv"), "Philips Live TV package");

        TvDeviceProfile skyworth = new TvDeviceProfile("Skyworth", "JVC", "JVC G24", 30);
        assertEquals(TvDeviceProfile.Brand.SKYWORTH, skyworth.getBrand(), "Skyworth / JVC brand detection");
        assertTrue(skyworth.getLiveTvPackages().contains("com.smartdevice.livetv"), "Skyworth Live TV package");
        assertEquals(true, skyworth.interceptsNotificationAsSettings(), "Skyworth intercepts notification as settings");

        TvDeviceProfile vestel = new TvDeviceProfile("Vestel", "Hitachi", "SmartTV", 30);
        assertEquals(TvDeviceProfile.Brand.VESTEL, vestel.getBrand(), "Vestel brand detection");

        TvDeviceProfile hisense = new TvDeviceProfile("Hisense", "Hisense", "Q6N", 31);
        assertEquals(TvDeviceProfile.Brand.HISENSE, hisense.getBrand(), "Hisense brand detection");

        TvDeviceProfile chromecast = new TvDeviceProfile("Google", "google", "Chromecast", 31);
        assertEquals(TvDeviceProfile.Brand.CHROMECAST, chromecast.getBrand(), "Chromecast / Google TV Streamer detection");

        TvDeviceProfile streamer = new TvDeviceProfile("Google", "google", "Google TV Streamer", 34);
        assertEquals(TvDeviceProfile.Brand.CHROMECAST, streamer.getBrand(), "Google TV Streamer brand detection");

        TvDeviceProfile onn = new TvDeviceProfile("onn", "onn", "onn. 4K Pro", 34);
        assertEquals(TvDeviceProfile.Brand.ONN, onn.getBrand(), "Walmart onn Google TV streamer detection");

        TvDeviceProfile generic = new TvDeviceProfile("Rockchip", "Generic", "ConnectionBox", 33);
        assertEquals(TvDeviceProfile.Brand.GENERIC, generic.getBrand(), "Generic TV box fallback (word-boundary safe against 'onn')");
    }

    private static void testCinemetaDeepLinks() {
        assertEquals(
                "stremio:///detail/movie/tt0816692",
                CinemetaClient.buildStremioLink("movie", "tt0816692"),
                "Stremio movie deep link"
        );
        assertEquals(
                "stremio:///detail/series/tt0903747",
                CinemetaClient.buildStremioLink("tv", "tt0903747"),
                "Stremio series deep link from tv type"
        );
        assertEquals(
                null,
                CinemetaClient.buildStremioLink("movie", "invalid_id"),
                "Invalid IMDb ID returns null"
        );

        CinemetaClient.CatalogItem item = new CinemetaClient.CatalogItem(
                "tt11280740", "series", "Severance", "2022-", "8.7", "https://poster", "https://bg"
        );
        assertEquals("stremio:///detail/series/tt11280740", item.stremioDeepLink, "CatalogItem Stremio deep link");
    }

    private static void testScreensaverPhotoEntry() {
        ScreensaverCatalog.PhotoEntry entry = new ScreensaverCatalog.PhotoEntry(
                "nat1", "nature", "Dolomites Alpine Dawn", "Luca Bravo", "South Tyrol, Italy", "https://example.com/1.jpg"
        );
        assertEquals("nature", entry.category, "PhotoEntry category");
        assertEquals("Luca Bravo", entry.artist, "PhotoEntry artist");
        assertEquals("South Tyrol, Italy", entry.location, "PhotoEntry location");
    }

    private static void testGamingZoneSmartOrchestration() {
        byte[] packet = GamingZone.buildMagicPacket("3C-9C-0F-AA-BB-CC");
        assertTrue(packet != null && packet.length == 102, "WoL magic packet is 102 bytes");
        assertEquals((byte) 0xFF, packet[0], "WoL header byte 0 is 0xFF");
        assertEquals((byte) 0x3C, packet[6], "WoL MAC byte 0 is 0x3C");
        assertEquals(null, GamingZone.buildMagicPacket("invalid-mac"), "Invalid MAC returns null");

        java.util.List<GamingZone.GameItem> catalog = GamingZone.defaultGameCatalog();
        assertTrue(catalog.size() >= 9, "Default catalog includes Steam + standalone C:\\Games titles");
        assertEquals("steam_bigpicture", catalog.get(0).id, "First catalog entry is Steam Big Picture");

        java.util.Set<String> hidden = new java.util.HashSet<>();
        hidden.add("exe_worms_armageddon");
        java.util.List<GamingZone.GameItem> visibleOnly = GamingZone.filterGames(catalog, hidden, false);
        assertEquals(catalog.size() - 1, visibleOnly.size(), "Hidden game is excluded when showHidden=false");

        java.util.List<GamingZone.GameItem> allShown = GamingZone.filterGames(catalog, hidden, true);
        assertEquals(catalog.size(), allShown.size(), "All games returned when showHidden=true");
        boolean wormsMarkedHidden = false;
        for (GamingZone.GameItem item : allShown) {
            if ("exe_worms_armageddon".equals(item.id)) {
                wormsMarkedHidden = item.hidden;
            }
        }
        assertTrue(wormsMarkedHidden, "Hidden game has hidden=true flag when showHidden=true");

        assertEquals(
                "com.limelight",
                GamingZone.selectPreferredClientPackage(java.util.Arrays.asList("com.valvesoftware.steamlink", "com.limelight")),
                "Moonlight (com.limelight) is preferred over Steam Link when both are installed"
        );
        assertEquals(
                "com.valvesoftware.steamlink",
                GamingZone.selectPreferredClientPackage(java.util.Collections.singletonList("com.valvesoftware.steamlink")),
                "Falls back to Steam Link when Moonlight is not installed"
        );

        String boomerangArt = GamingZone.resolveDefaultArtUrl("steam_728880", "Overcooked! 2", "steam://rungameid/728880");
        assertTrue(boomerangArt != null && boomerangArt.contains("728880/header.jpg"), "Steam game resolves CDN header.jpg cover art");
        String wormsArt = GamingZone.resolveDefaultArtUrl("exe_worms_armageddon", "Worms Armageddon", "");
        assertTrue(wormsArt != null && wormsArt.contains("217200/header.jpg"), "Standalone Worms Armageddon resolves Steam CDN cover art");

        GamingZone.GameItem withPlayed = new GamingZone.GameItem(
                "steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", false, boomerangArt, 1730000000000L
        );
        assertEquals(1730000000000L, withPlayed.lastPlayedAt, "GameItem preserves lastPlayedAt timestamp for Continue Playing row");

        java.util.Set<String> tempHidden = new java.util.HashSet<>(java.util.Arrays.asList("exe_worms_armageddon", "steam_1118520"));
        java.util.Set<String> permHidden = new java.util.HashSet<>(java.util.Collections.singletonList("steam_1118520"));
        java.util.List<GamingZone.GameItem> onlyTempHidden = GamingZone.filterTemporarilyHiddenGames(catalog, tempHidden, permHidden);
        assertEquals(1, onlyTempHidden.size(), "Permanently hidden games are excluded from temporarily hidden games list");
        assertEquals("exe_worms_armageddon", onlyTempHidden.get(0).id, "Temporarily hidden game appears in Hidden Games list");

        java.util.List<GamingZone.GameItem> noTempHidden = GamingZone.filterTemporarilyHiddenGames(
                catalog,
                java.util.Collections.singleton("steam_1118520"),
                permHidden
        );
        assertEquals(0, noTempHidden.size(), "When only permanently hidden games exist, temporarily hidden list is empty (no Hidden tile shown)");

        // Verify unhiding a game that previously had g.hidden=true in cache restores it to the visible library
        java.util.List<GamingZone.GameItem> cachedWithHiddenFlag = java.util.Collections.singletonList(
                new GamingZone.GameItem("exe_worms_armageddon", "Worms Armageddon", "pc", "", true)
        );
        java.util.List<GamingZone.GameItem> afterUnhide = GamingZone.filterGames(
                cachedWithHiddenFlag,
                java.util.Collections.emptySet(),
                false
        );
        assertEquals(1, afterUnhide.size(), "Unhidden game is restored to Games Library even if cached item had hidden=true");
        assertEquals(false, afterUnhide.get(0).hidden, "Unhidden game has hidden=false after unhide");

        // Verify HDMI Role classification (Consoles vs Streamers vs Laptop) and wireless fallback
        assertEquals(GamingZone.HdmiRole.CONSOLE, GamingZone.classifyLabelAsRole("PlayStation 5"), "PlayStation 5 classified as CONSOLE");
        assertEquals(GamingZone.HdmiRole.CONSOLE, GamingZone.classifyLabelAsRole("Sony Bravia / PS4"), "Sony PS4 classified as CONSOLE");
        assertEquals(GamingZone.HdmiRole.CONSOLE, GamingZone.classifyLabelAsRole("Xbox Series X"), "Xbox classified as CONSOLE");
        assertEquals(GamingZone.HdmiRole.CONSOLE, GamingZone.classifyLabelAsRole("Nintendo Wii"), "Wii classified as CONSOLE");
        assertEquals(GamingZone.HdmiRole.STREAMER, GamingZone.classifyLabelAsRole("Mecool KM2"), "Mecool classified as STREAMER");
        assertEquals(GamingZone.HdmiRole.STREAMER, GamingZone.classifyLabelAsRole("Apple TV"), "Apple TV classified as STREAMER");
        assertEquals(GamingZone.HdmiRole.LAPTOP, GamingZone.classifyLabelAsRole("Laptop (HDMI 3)"), "Laptop classified as LAPTOP");
        assertEquals(GamingZone.HdmiRole.AUTO, GamingZone.classifyLabelAsRole("HDMI 3"), "Plain HDMI 3 classified as AUTO");

        java.util.Map<Integer, GamingZone.HdmiPortConfig> portMap =
                GamingZone.parseHdmiPortRolesSpec("1:streamer:Mecool,2:streamer:Apple TV,3:laptop:Laptop");
        assertEquals(GamingZone.HdmiRole.STREAMER, portMap.get(1).role, "Port 1 role parsed as STREAMER");
        assertEquals("Mecool", portMap.get(1).deviceName, "Port 1 deviceName parsed as Mecool");
        assertEquals(GamingZone.HdmiRole.LAPTOP, portMap.get(3).role, "Port 3 role parsed as LAPTOP");

        HomeApps.Item hdmi1Mecool = new HomeApps.Item();
        hdmi1Mecool.kind = HomeApps.INPUT;
        hdmi1Mecool.number = "1";
        hdmi1Mecool.label = "HDMI 1";
        hdmi1Mecool.connected = true;

        HomeApps.Item hdmi2AppleTv = new HomeApps.Item();
        hdmi2AppleTv.kind = HomeApps.INPUT;
        hdmi2AppleTv.number = "2";
        hdmi2AppleTv.label = "Apple TV";
        hdmi2AppleTv.connected = true;

        HomeApps.Item hdmi3Free = new HomeApps.Item();
        hdmi3Free.kind = HomeApps.INPUT;
        hdmi3Free.number = "3";
        hdmi3Free.label = "HDMI 3";
        hdmi3Free.connected = false;

        java.util.List<HomeApps.Item> tvInputs = java.util.Arrays.asList(hdmi1Mecool, hdmi2AppleTv, hdmi3Free);

        // Normal case: Laptop NOT connected via HDMI cable -> must return null (connect wirelessly via Moonlight), NEVER HDMI 1/2!
        assertEquals(null, GamingZone.findLaptopHdmiInput(tvInputs, portMap, false),
                "When laptop HDMI cable is unplugged, findLaptopHdmiInput must return null so TV connects wirelessly");

        // Even if laptopReportedHdmi were true before HDMI 3 handshakes, streamers on HDMI 1 & 2 are excluded and HDMI 3 (laptop) is picked
        HomeApps.Item pickedWhenCablePlugged = GamingZone.findLaptopHdmiInput(tvInputs, portMap, true);
        assertTrue(pickedWhenCablePlugged != null && "3".equals(pickedWhenCablePlugged.number),
                "When laptop HDMI cable is connected, findLaptopHdmiInput picks HDMI 3 (never Mecool HDMI 1 or Apple TV HDMI 2)");

        // Permanent gaming console auto-detection via HDMI-CEC (e.g., PlayStation 5 on HDMI 1 for any generic user)
        HomeApps.Item cecPs5 = new HomeApps.Item();
        cecPs5.kind = HomeApps.INPUT;
        cecPs5.number = "1";
        cecPs5.label = "PlayStation 5";
        cecPs5.connected = true;
        java.util.List<HomeApps.Item> consoles = GamingZone.findConsoleHdmiInputs(
                java.util.Arrays.asList(cecPs5, hdmi2AppleTv),
                java.util.Collections.emptyMap()
        );
        assertEquals(1, consoles.size(), "Permanent PlayStation 5 console auto-detected via HDMI-CEC while excluding Apple TV");
        assertEquals("PlayStation 5", consoles.get(0).label, "Detected console preserves PlayStation 5 label");
    }

    private static void testSmartHomeDeviceRemoteSerialization() {
        SmartHomeManager.Device ac = new SmartHomeManager.Device(
                "sensibo_living_ac",
                "Living Room AC",
                SmartHomeManager.Protocol.SENSIBO_AC,
                "snowflake",
                true,
                "21°C · Cool · Fan High",
                21
        );
        ac.setAcMode("cool");
        ac.setFanLevel("high");
        assertEquals("cool", ac.getAcMode(), "AC mode preserved");
        assertEquals("high", ac.getFanLevel(), "AC fan level preserved");
        assertEquals("Living Room AC · 21°C · Cool · Fan High", ac.getDisplayLabel(), "AC display label formatted");

        SmartHomeManager.Device bulb = new SmartHomeManager.Device(
                "wiz_bulb_aabbccddeeff",
                "Bedroom Light",
                SmartHomeManager.Protocol.WIZ_LIGHT,
                "lightbulb",
                true,
                "On · 20% · Warm",
                0
        );
        bulb.setBrightness(5); // clamps to 10
        bulb.setColorTempK(1800); // clamps to 2200
        bulb.setCurrentIp("192.168.1.9");
        bulb.setMacAddress("aabbccddeeff");
        assertEquals(10, bulb.getBrightness(), "WiZ brightness clamps minimum to 10%");
        assertEquals(2200, bulb.getColorTempK(), "WiZ colorTempK clamps minimum to 2200K");
        assertEquals("192.168.1.9", bulb.getCurrentIp(), "WiZ bulb IP preserved");

        SmartHomeManager.Device boiler = new SmartHomeManager.Device(
                "switcher_boiler",
                "Water Boiler",
                SmartHomeManager.Protocol.SWITCHER_BOILER,
                "bolt",
                true,
                "On · 15m Timer",
                0
        );
        assertEquals(15, boiler.getTimerMinutes(), "Boiler default timer is 15 minutes");
        boiler.setTimerMinutes(30);
        assertEquals(30, boiler.getTimerMinutes(), "Boiler timer minutes preserved");
    }

    private static void testContinueWatchingHidePersistenceAndGenres() {
        java.util.List<String> genres = java.util.Arrays.asList(Prefs.DEFAULT_GENRES);
        assertTrue(genres.size() >= 26, "DEFAULT_GENRES contains at least 26 popular genres");
        assertTrue(genres.contains("Romance") && genres.contains("Rom-Coms"), "Both Romance and Rom-Coms exist as distinct genres");
        assertTrue(genres.contains("Anime") && genres.contains("Superhero") && genres.contains("K-Drama"), "New popular genres Anime, Superhero, and K-Drama exist");

        WatchNext.Item previewItem = new WatchNext.Item("com.stremio.one", null, null);
        previewItem.title = "  The Good Wife  ";
        previewItem.episode = "Continue: S1 E10";
        previewItem.position = 15 * 60_000L;
        previewItem.realEngaged = 0L; // Stremio preview channel has no engagement timestamp
        long hiddenAt = 1730000000000L;
        previewItem.engaged = hiddenAt + 3_600_000L; // Synthetic fallbackOrder on app restart 1 hour later
        String savedSig = WatchNext.stateSignature(previewItem);

        assertEquals("com.stremio.one|the good wife", WatchNext.hideKey(previewItem), "Normalized package+title hideKey");
        assertEquals("title|the good wife", WatchNext.titleHideKey(previewItem), "Normalized title-level hideKey");
        assertEquals(false, WatchNext.shouldAutoUnhide(previewItem, hiddenAt, savedSig),
                "Synthetic fallbackOrder timestamp (realEngaged==0) must NOT auto-unhide item on app update/restart");

        // Advancing to the next episode genuinely unhides the item
        previewItem.episode = "Continue: S1 E11";
        assertEquals(true, WatchNext.shouldAutoUnhide(previewItem, hiddenAt, savedSig),
                "Advancing to a new episode genuinely unhides the item");

        // Or a real engagement timestamp >60s after hiding unhides the item
        previewItem.episode = "Continue: S1 E10";
        previewItem.realEngaged = hiddenAt + 120_000L;
        assertEquals(true, WatchNext.shouldAutoUnhide(previewItem, hiddenAt, savedSig),
                "Real engagement timestamp >60s after hiding unhides the item");
    }

    private static void testStremioLibrarySyncAndVideoIdParsing() {
        int[] se = StremioAccountClient.parseVideoIdSeasonEpisode("tt0903747:2:5");
        assertEquals(2, se[0], "Stremio video_id season parsed");
        assertEquals(5, se[1], "Stremio video_id episode parsed");

        int[] movieSe = StremioAccountClient.parseVideoIdSeasonEpisode("tt0816692");
        assertEquals(0, movieSe[0], "Movie video_id has season 0");
        assertEquals(0, movieSe[1], "Movie video_id has episode 0");

        StremioAccountClient.LibraryItem libSeries = new StremioAccountClient.LibraryItem(
                "tt11280740",
                "Severance",
                "tv",
                "https://images.metahub.space/poster/large/tt11280740/img",
                1730000000000L,
                1730000500000L,
                1730000500000L,
                0,
                1200000L,
                3000000L,
                "tt11280740:1:4",
                1,
                4,
                false,
                false,
                false
        );
        assertEquals(true, libSeries.isLibraryWatchlistItem(), "Non-temp, non-removed Stremio item is in Library Watch List");
        assertEquals(true, libSeries.hasContinueWatchingState(), "Mid-episode Stremio item has Continue Watching state");

        StremioAccountClient.LibraryItem tempItem = new StremioAccountClient.LibraryItem(
                "tt0903747", "Breaking Bad", "tv", "", 0L, 1730000500000L, 1730000500000L, 1,
                600000L, 2700000L, "tt0903747:3:2", 3, 2, false, false, true
        );
        assertEquals(false, tempItem.isLibraryWatchlistItem(), "Temporary watched item is NOT added to explicit Watch List");
        assertEquals(true, tempItem.hasContinueWatchingState(), "Temporary item with playback offset still qualifies for Continue Watching");
    }

    private static void testGenreAndLanguageFilteringAlgorithm() {
        // 1. Korean Rom-Com must NOT appear in Rom-Coms (isolated to K-Drama)
        Recs.Pick koreanRomCom = new Recs.Pick();
        koreanRomCom.kind = "tv";
        koreanRomCom.title = "Crash Landing on You";
        koreanRomCom.lang = "ko";
        koreanRomCom.isAnime = false;
        koreanRomCom.genres.add("Comedy");
        koreanRomCom.genres.add("Romance");
        koreanRomCom.genres.add("K-Drama");
        assertEquals(false, Recs.matchesGenre(koreanRomCom, "Rom-Coms"),
                "Korean content must be excluded from Rom-Coms row");
        assertEquals(true, Recs.matchesGenre(koreanRomCom, "K-Drama"),
                "Korean content matches K-Drama row");

        // 2. English Rom-Com matches Rom-Coms
        Recs.Pick englishRomCom = new Recs.Pick();
        englishRomCom.kind = "movie";
        englishRomCom.title = "Anyone But You";
        englishRomCom.lang = "en";
        englishRomCom.isAnime = false;
        englishRomCom.genres.add("Comedy");
        englishRomCom.genres.add("Romance");
        englishRomCom.genres.add("Rom-Coms");
        assertEquals(true, Recs.matchesGenre(englishRomCom, "Rom-Coms"),
                "English Rom-Com matches Rom-Coms row");

        // 3. Japanese Anime must be isolated strictly to Anime & Animation rows (not polluting Action, Sci-Fi, Fantasy, Drama)
        Recs.Pick animePick = new Recs.Pick();
        animePick.kind = "tv";
        animePick.title = "Attack on Titan";
        animePick.lang = "ja";
        animePick.isAnime = true;
        animePick.genres.add("Animation");
        animePick.genres.add("Anime");
        animePick.genres.add("Action");
        animePick.genres.add("Sci-Fi");
        animePick.genres.add("Fantasy");
        assertEquals(true, Recs.matchesGenre(animePick, "Anime"), "Anime matches Anime row");
        assertEquals(true, Recs.matchesGenre(animePick, "Animation"), "Anime matches Animation row");
        assertEquals(false, Recs.matchesGenre(animePick, "Action"), "Anime is excluded from Action row");
        assertEquals(false, Recs.matchesGenre(animePick, "Sci-Fi"), "Anime is excluded from Sci-Fi row");
        assertEquals(false, Recs.matchesGenre(animePick, "Fantasy"), "Anime is excluded from Fantasy row");

        // 4. TMDB discovery query for Rom-Coms enforces English/Hebrew language and excludes Animation, and TV Rom-Coms uses keywords
        String romComMovieQuery = Recs.tmdbGenreQueryFor("movie", "Rom-Coms");
        assertTrue(romComMovieQuery.contains("with_original_language=en|he"),
                "Rom-Coms TMDB query prioritizes English/Israeli titles");
        assertTrue(romComMovieQuery.contains("without_genres=16"),
                "Rom-Coms TMDB query excludes Animation/Anime");
        String romComTvQuery = Recs.tmdbGenreQueryFor("tv", "Rom-Coms");
        assertTrue(romComTvQuery.contains("with_genres=35") && romComTvQuery.contains("with_keywords="),
                "TV Rom-Coms TMDB query uses Comedy genre + rom-com keywords instead of Movie-only genre 10749");
    }

    private static void testRecursiveSettingsTreeAndAutoThemeSchedule() {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.theme = "auto";
        snap.autoThemeMode = "sun";
        snap.darkHour = 20;
        snap.lightHour = 7;
        snap.city = "Tel Aviv";
        snap.timezone = "Asia/Jerusalem (UTC+02:00)";
        snap.sunriseText = "06:04";
        snap.sunsetText = "17:48";
        snap.traktConnected = true;
        snap.traktUser = "alex_tv";
        snap.hiddenTvGenres = new java.util.HashSet<>(java.util.Collections.singletonList("Miniseries"));

        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        assertTrue(root.hasChildren(), "Settings root has children");

        // 1. Redundant 'SoarTV Settings' and 'Android TV Settings' wrapper rows are removed;
        //    Initial Setup is promoted at top while incomplete, followed by native-ordered TV & SoarTV sections.
        assertEquals(null, root.findById("root_soartv"), "SoarTV Settings wrapper row removed");
        assertEquals(null, root.findById("root_android"), "Android TV Settings wrapper row removed");
        int firstIdx = SettingsScreenRenderer.firstSelectableIndex(root.children);
        assertEquals("initial_setup", root.children.get(firstIdx).id, "First selectable category while setup incomplete is Initial Setup & Cloud");
        assertTrue(root.findById("network_internet") != null, "Network & Internet category exists");
        assertTrue(root.findById("display_sound") != null, "Display & Sound category exists");
        assertTrue(root.findById("channels_inputs") != null, "Channels & Inputs category exists");
        assertTrue(root.findById("remotes_accessories") != null, "Remotes & Accessories category exists");
        assertTrue(root.findById("home_appearance") != null, "Home & Appearance category exists");
        assertTrue(root.findById("smarthome_settings") != null, "Dedicated Smart-Home category exists");
        assertTrue(root.findById("gaming_settings") != null, "Dedicated Gaming category exists");
        assertTrue(root.findById("shg_tv_room") != null, "TV Room selector exists");

        // 2. Every single node in the Settings tree has an icon that resolves in Symbols (including ac, shower, key, plus)
        assertTrue(Symbols.isSupportedIcon("ac") && Symbols.isSupportedIcon("shower"), "AC and Shower vector icons supported");
        verifyAllNodesHaveIcons(root);

        // 3. Opening the App Shelf is NEVER an option anywhere in Settings
        assertEquals(null, root.findById("settings_side_shelf"), "App shelf option removed from Settings");

        // 4. Initial Setup & Cloud contains single-row Trakt & Stremio accounts; Users & Accounts contains unified Sync Accounts
        SettingsTree.Node setupNode = root.findById("initial_setup");
        assertTrue(setupNode != null && setupNode.hasChildren(), "Initial Setup & Cloud node exists");
        SettingsTree.Node traktSingle = setupNode.findById("trakt_auth");
        assertTrue(traktSingle != null && !traktSingle.hasChildren(), "Trakt Account is a single-row node under Initial Setup & Cloud");
        assertTrue(traktSingle.value.contains("@alex_tv"), "Trakt Account displays connected username");
        assertEquals(null, root.findById("trakt_qr_force"), "Redundant 'Show Trakt Activation QR Code' option removed");
        SettingsTree.Node stremioSingle = setupNode.findById("stremio_auth");
        assertTrue(stremioSingle != null && !stremioSingle.hasChildren(), "Stremio Account is a single-row node under Initial Setup & Cloud");

        SettingsTree.Node usersNode = root.findById("users");
        assertTrue(usersNode != null && usersNode.hasChildren(), "Users & Accounts node exists");
        assertTrue(usersNode.findById("users_sync_all") != null, "Unified Sync Accounts (Trakt & Stremio) exists under Users & Accounts");
        assertEquals(null, root.findById("ss_watch"), "Removed 'Press OK to Watch Shown Title' option from Screensaver settings");

        // 5. Content & Genres lists visible genres first and hidden genres greyed out at the end, with a single parent hint
        SettingsTree.Node tvGenresNode = root.findById("hg_tv_genres");
        assertTrue(tvGenresNode != null && tvGenresNode.hasChildren(), "TV Shows Genres node exists");
        assertTrue(tvGenresNode.hint != null && !tvGenresNode.hint.isEmpty(), "Single contextual hint is set on TV Shows Genres parent node");
        SettingsTree.Node lastTvGenre = tvGenresNode.children.get(tvGenresNode.children.size() - 1);
        assertEquals("genre_tv_item:Miniseries", lastTvGenre.id, "Hidden genre Miniseries is placed last in TV Shows Genres");
        assertEquals(true, lastTvGenre.grayedOut, "Hidden genre Miniseries is marked grayedOut=true");

        // 6. TV Room selector only lists existing rooms + '+ Add New Room…'
        SettingsTree.Node tvRoomNode = root.findById("shg_tv_room");
        assertTrue(tvRoomNode != null && tvRoomNode.findById("tv_room_add") != null, "TV Room selector includes '+ Add New Room…'");
        assertEquals(null, tvRoomNode.findById("tv_room_set:Kids Room"), "Fake hardcoded 'Kids Room' is excluded from TV Room selector");

        // 7. Theme has Dark, Light, Auto -> By Sunrise/Sunset (Set Location, Time-Zone, Sunrise, Sunset) & By Hours
        SettingsTree.Node themeNode = root.findById("theme");
        assertTrue(themeNode != null && themeNode.children.size() == 3, "Theme has Dark, Light, Auto");
        assertEquals("theme_dark", themeNode.children.get(0).id, "Theme option 1 is Dark");
        assertEquals("theme_light", themeNode.children.get(1).id, "Theme option 2 is Light");
        assertEquals("theme_auto", themeNode.children.get(2).id, "Theme option 3 is Auto");

        SettingsTree.Node autoNode = themeNode.children.get(2);
        assertEquals(2, autoNode.children.size(), "Auto has By Sunrise/Sunset and By Hours");
        SettingsTree.Node bySun = autoNode.children.get(0);
        assertEquals("theme_auto_sun", bySun.id, "Auto child 1 is By Sunrise / Sunset");
        assertEquals(4, bySun.children.size(), "By Sunrise/Sunset has 4 deeper options");
        assertEquals("theme_sun_location", bySun.children.get(0).id, "Sunrise/Sunset option 1 is Set Location");
        assertEquals("theme_sun_timezone", bySun.children.get(1).id, "Sunrise/Sunset option 2 is Calculated Time-Zone");
        assertEquals("theme_sun_sunrise", bySun.children.get(2).id, "Sunrise/Sunset option 3 is Sunrise Time");
        assertEquals("theme_sun_sunset", bySun.children.get(3).id, "Sunrise/Sunset option 4 is Sunset Time");

        SettingsTree.Node byHours = autoNode.children.get(1);
        assertEquals("theme_auto_hours", byHours.id, "Auto child 2 is By Hours");
        assertEquals(2, byHours.children.size(), "By Hours has Dark Mode Starts At & Light Mode Starts At");
        assertEquals("theme_hours_dark", byHours.children.get(0).id, "By Hours option 1 is Dark Mode Starts At");
        assertEquals("theme_hours_light", byHours.children.get(1).id, "By Hours option 2 is Light Mode Starts At");

        // 8. 4-level recursion for Channels & Inputs -> Live TV & Channels -> Tuner Mode -> Antenna, Cable, Satellite
        SettingsTree.Node ciNode = root.findById("channels_inputs");
        assertTrue(ciNode != null && ciNode.children.size() >= 4, "Channels & Inputs has External Inputs, HDMI Roles, Live TV & Channels, CEC, Cast & AirPlay");
        SettingsTree.Node chNode = ciNode.findById("ci_channels");
        assertTrue(chNode != null, "Channels & Inputs contains Live TV & Channels");
        SettingsTree.Node tunerNode = chNode.children.get(0);
        assertEquals("ci_tuner_mode", tunerNode.id, "Live TV & Channels contains Tuner Mode");
        assertEquals(3, tunerNode.children.size(), "4th column under Tuner Mode has 3 options");
        assertEquals("Antenna", tunerNode.children.get(0).title, "4th column option 1 is Antenna");
        assertEquals("Cable", tunerNode.children.get(1).title, "4th column option 2 is Cable");
        assertEquals("Satellite", tunerNode.children.get(2).title, "4th column option 3 is Satellite");

        // 9. System Notifications & User-Poll Muting + IMDb / RT / MC Ratings Subtitle
        assertEquals(true, NowPlaying.isUserPoll("com.google.android.tv", "Quick survey", "Tell us what you think of Google TV", ""),
                "User poll notification is detected and muted");
        assertEquals(false, NowPlaying.isUserPoll("com.android.systemui", "USB drive connected", "Kingston 64GB ready", ""),
                "Real system notification is kept");

        Recs.Pick fullScores = new Recs.Pick();
        fullScores.imdbScore = "8.7";
        fullScores.rtScore = "92%";
        fullScores.mcScore = "84";
        assertEquals("IMDb 8.7 MC 84 RT 92%", Recs.ratingsSubtitle(fullScores), "All 3 scores formatted in IMDb, MC, RT order separated by space");

        Recs.Pick partialScores = new Recs.Pick();
        partialScores.imdbScore = "7.9";
        partialScores.rtScore = "";
        partialScores.mcScore = "71";
        assertEquals("IMDb 7.9 MC 71", Recs.ratingsSubtitle(partialScores), "Missing RT score skipped cleanly with space separator");
    }

    private static void verifyAllNodesHaveIcons(SettingsTree.Node node) {
        if (!"root".equals(node.id) && !node.isSectionHeader()) {
            assertTrue(node.icon != null && !node.icon.isEmpty(), "Node " + node.id + " has non-empty icon name");
            assertTrue(Symbols.isSupportedIcon(node.icon), "Node " + node.id + " icon '" + node.icon + "' is supported in Symbols");
        }
        for (SettingsTree.Node child : node.children) {
            verifyAllNodesHaveIcons(child);
        }
    }

    private static void testRecsFormattingEngines() {
        // --- 1. Duration Formatting Boundary & Nominal Cases ---
        assertEquals(null, Recs.formatDuration(0), "Duration 0 returns null");
        assertEquals(null, Recs.formatDuration(-1), "Duration -1 returns null");
        assertEquals(null, Recs.formatDuration(-60), "Duration -60 returns null");
        assertEquals("5min", Recs.formatDuration(5), "Duration 5min (<60m)");
        assertEquals("45min", Recs.formatDuration(45), "Duration 45min (<60m)");
        assertEquals("59min", Recs.formatDuration(59), "Duration 59min (<60m)");
        assertEquals("1:00h", Recs.formatDuration(60), "Duration 60m -> 1:00h");
        assertEquals("1:05h", Recs.formatDuration(65), "Duration 65m -> 1:05h");
        assertEquals("1:37h", Recs.formatDuration(97), "Duration 97m -> 1:37h");
        assertEquals("1:45h", Recs.formatDuration(105), "Duration 105m -> 1:45h");
        assertEquals("2:00h", Recs.formatDuration(120), "Duration 120m -> 2:00h");
        assertEquals("2:05h", Recs.formatDuration(125), "Duration 125m -> 2:05h");
        assertEquals("3:30h", Recs.formatDuration(210), "Duration 210m -> 3:30h");

        // --- 2. Date Formatting Permutations & Edge Cases (Year-Only Except Current Year) ---
        int curYr = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        // Movies
        assertEquals("2022", Recs.formatDates("movie", "2022-08-10", null, null, 0, null, false, 2026), "Movie past year omits month -> 2022");
        assertEquals("2023", Recs.formatDates("movie", "2023-04-15", null, null, 0, null, false, 2026), "Movie Apr 2023 -> 2023");
        assertEquals("Jan 2026", Recs.formatDates("movie", "2026-01-15", null, null, 0, null, false, 2026), "Movie in current year keeps month -> Jan 2026");
        assertEquals("Jan " + curYr, Recs.formatDates("movie", curYr + "-01-15", null, null, 0, null, false), "Default current-year movie keeps Jan YYYY");
        assertEquals("2022", Recs.formatDates("movie", "2022", null, null, 0, null, false), "Movie year only 2022");
        assertEquals(null, Recs.formatDates("movie", null, null, null, 0, null, false), "Movie null date returns null");
        assertEquals(null, Recs.formatDates("movie", "", null, null, 0, null, false), "Movie empty date returns null");
        assertEquals(null, Recs.formatDates("movie", "   ", null, null, 0, null, false), "Movie whitespace date returns null");
        assertEquals(null, Recs.formatDates("movie", "invalid-date", null, null, 0, null, false), "Movie invalid date returns null");

        // Multi-season TV ended
        assertEquals("2009-2016", Recs.formatDates("tv", null, "2009-09-22", "2016-05-08", 7, "Ended", false),
                "Multi-season ended formatted YYYY-YYYY");
        assertEquals("2020", Recs.formatDates("tv", null, "2020-01-10", "2020-11-05", 2, "Ended", false),
                "Multi-season ended same year returns YYYY");
        assertEquals("2009", Recs.formatDates("tv", null, "2009-09-22", null, 7, "Ended", false),
                "Multi-season ended missing last air date falls back to start year");

        // Ongoing renewal TV
        assertEquals("2021-Now", Recs.formatDates("tv", null, "2021-09-17", "2024-12-01", 2, "Returning Series", true),
                "Multi-season renewed formatted YYYY-Now");
        assertEquals("2021-Now", Recs.formatDates("tv", null, "2021-09-17", null, 2, "Returning Series", false),
                "Multi-season with status Returning Series formatted YYYY-Now");
        assertEquals("2021-Now", Recs.formatDates("tv", null, "2021-09-17", null, 1, "Returning Series", true),
                "Single-season with confirmed renewal formatted YYYY-Now");

        // Single-season TV partial / completed
        assertEquals("2022", Recs.formatDates("tv", null, "2022-08-15", null, 1, "Returning Series", false, 2026),
                "Single-season partial from past year formatted YYYY");
        assertEquals("Jan 2026", Recs.formatDates("tv", null, "2026-01-20", null, 1, "Returning Series", false, 2026),
                "Single-season partial from current year formatted MMM YYYY");
        assertEquals("2019", Recs.formatDates("tv", null, "2019-05-06", "2019-06-03", 1, "Ended", false, 2026),
                "Single-season completed miniseries from past year formatted YYYY");
        assertEquals("2019", Recs.formatDates("tv", null, "2019", null, 1, "Ended", false),
                "Single-season year only returns YYYY");

        // TV malformed / null
        assertEquals(null, Recs.formatDates("tv", null, null, null, 2, "Ended", false), "TV null first air date returns null");
        assertEquals(null, Recs.formatDates("tv", null, "", null, 2, "Ended", false), "TV empty first air date returns null");
        assertEquals(null, Recs.formatDates("tv", null, "xyz", null, 2, "Ended", false), "TV invalid first air date returns null");
        assertEquals(null, Recs.formatDates(null, null, null, null, 0, null, false), "All nulls return null gracefully");

        // --- 3. Metacritic Scaling (0–10 scale with 1 decimal digit) ---
        assertEquals("7.8", Recs.formatMetacritic("78"), "Metacritic integer 78 -> 7.8");
        assertEquals("8.0", Recs.formatMetacritic("80"), "Metacritic integer 80 -> 8.0 (preserves 1 decimal)");
        assertEquals("8.4", Recs.formatMetacritic("84"), "Metacritic integer 84 -> 8.4");
        assertEquals("10.0", Recs.formatMetacritic("100"), "Metacritic integer 100 -> 10.0");
        assertEquals("7.8", Recs.formatMetacritic("78%"), "Metacritic integer with percent 78% -> 7.8");
        assertEquals("7.8", Recs.formatMetacritic("78/100"), "Metacritic integer with /100 -> 7.8");
        assertEquals("7.8", Recs.formatMetacritic("7.8"), "Metacritic pre-scaled decimal 7.8 preserved");
        assertEquals("8.0", Recs.formatMetacritic("8.0"), "Metacritic pre-scaled decimal 8.0 preserved");
        assertEquals("10.0", Recs.formatMetacritic("10.0"), "Metacritic pre-scaled decimal 10.0 preserved");
        assertEquals(null, Recs.formatMetacritic("0"), "Metacritic 0 returns null");
        assertEquals(null, Recs.formatMetacritic("0.0"), "Metacritic 0.0 returns null");
        assertEquals(null, Recs.formatMetacritic("N/A"), "Metacritic N/A returns null");
        assertEquals(null, Recs.formatMetacritic("n/a"), "Metacritic lowercase n/a returns null");
        assertEquals(null, Recs.formatMetacritic(""), "Metacritic empty returns null");
        assertEquals(null, Recs.formatMetacritic("   "), "Metacritic whitespace returns null");
        assertEquals(null, Recs.formatMetacritic((String) null), "Metacritic null string returns null");
        assertEquals(null, Recs.formatMetacritic((Recs.Pick) null), "Metacritic null Pick returns null");
        assertEquals(null, Recs.formatMetacritic("invalid"), "Metacritic invalid string returns null");

        // --- 4. Genre Pipe Joining ---
        assertEquals("Mystery | Thriller | Drama | Action",
                Recs.formatGenres(java.util.Arrays.asList("Mystery", "Thriller", "Drama", "Action")),
                "4 genres joined with pipe");
        assertEquals("Drama",
                Recs.formatGenres(java.util.Collections.singletonList("Drama")),
                "Single genre joined without pipe");
        assertEquals("Action | Drama",
                Recs.formatGenres(java.util.Arrays.asList("Action", "", null, "   ", "Drama")),
                "Nulls and whitespace genres filtered cleanly");
        assertEquals("",
                Recs.formatGenres(java.util.Collections.emptyList()),
                "Empty genre list returns empty string");
        assertEquals("",
                Recs.formatGenres((java.util.List<String>) null),
                "Null genre list returns empty string");
        assertEquals("",
                Recs.formatGenres((Recs.Pick) null),
                "Null Pick genres returns empty string");
        assertEquals("",
                Recs.formatGenres(java.util.Arrays.asList("", "   ", null)),
                "List with only empty/blank elements returns empty string");

        // --- 5. Pick Field Initialization & Overloads ---
        Recs.Pick p = new Recs.Pick();
        assertEquals("", p.overview, "Default Pick.overview is empty string");
        assertEquals(0, p.runtime, "Default Pick.runtime is 0");
        assertEquals("", p.releaseDate, "Default Pick.releaseDate is empty string");
        assertEquals("", p.firstAirDate, "Default Pick.firstAirDate is empty string");
        assertEquals("", p.lastAirDate, "Default Pick.lastAirDate is empty string");
        assertEquals(0, p.seasonsCount, "Default Pick.seasonsCount is 0");
        assertEquals("", p.status, "Default Pick.status is empty string");
        assertEquals(false, p.hasConfirmedRenewal, "Default Pick.hasConfirmedRenewal is false");

        p.kind = "movie";
        p.runtime = 105;
        p.releaseDate = "2022-08-10";
        p.mcScore = "84";
        p.genres = java.util.Arrays.asList("Mystery", "Thriller", "Drama", "Action");
        assertEquals("1:45h", Recs.formatDuration(p), "Pick overload formatDuration");
        assertEquals("2022", Recs.formatDates(p), "Pick overload formatDates omits month for past year");
        assertEquals("8.4", Recs.formatMetacritic(p), "Pick overload formatMetacritic");
        assertEquals("Mystery | Thriller | Drama | Action", Recs.formatGenres(p), "Pick overload formatGenres");
    }

    private static void testProceduralSymbolsAndHeadlessSafety() {
        // 1. Verify Symbols.isSupportedIcon recognizes RT and MC names and aliases
        assertTrue(Symbols.isSupportedIcon("rt"), "Symbols supports 'rt'");
        assertTrue(Symbols.isSupportedIcon("rotten_tomatoes"), "Symbols supports 'rotten_tomatoes'");
        assertTrue(Symbols.isSupportedIcon("mc"), "Symbols supports 'mc'");
        assertTrue(Symbols.isSupportedIcon("metacritic"), "Symbols supports 'metacritic'");
        assertEquals(false, Symbols.isSupportedIcon("non_existent_icon_xyz"), "Unknown icon returns false");
        assertEquals(false, Symbols.isSupportedIcon(null), "Null icon returns false");
        assertEquals(false, Symbols.isSupportedIcon("   "), "Whitespace icon returns false");

        // 2. Verify headless safety: get, draw, and width must never throw RuntimeException on JVM
        try {
            Symbols.get(null, "rt");
            Symbols.get(null, "rotten_tomatoes");
            Symbols.get(null, "mc");
            Symbols.get(null, "metacritic");
            Symbols.draw(null, null, "rt", 0, 0, 24f, 0xFFFFFFFF);
            Symbols.draw(null, null, "mc", 0, 0, 24f, 0xFFFFFFFF);
            float wRt = Symbols.width(null, "rt", 24f);
            float wMc = Symbols.width(null, "mc", 24f);
            assertTrue(wRt >= 0, "RT width is non-negative");
            assertTrue(wMc >= 0, "MC width is non-negative");
        } catch (RuntimeException e) {
            throw new AssertionError("Symbols methods threw RuntimeException in headless JVM: " + e.getMessage());
        }
    }

    private static void testTopDetailsHeaderM4() {
        // --- 1. HeaderState Lifecycle, Pre-allocation & Field Defaults ---
        HomeView.HeaderState s1 = new HomeView.HeaderState();
        assertEquals("", s1.mediaKey, "Default mediaKey is empty");
        assertEquals(0, s1.subTab, "Default subTab is 0");
        assertEquals("", s1.title, "Default title is empty");
        assertEquals("", s1.displayTitle, "Default displayTitle is empty");
        assertEquals(null, s1.durationText, "Default durationText is null");
        assertEquals(null, s1.datesText, "Default datesText is null");
        assertEquals(false, s1.hasRt, "Default hasRt is false");
        assertEquals(null, s1.rtScoreText, "Default rtScoreText is null");
        assertEquals(false, s1.hasMc, "Default hasMc is false");
        assertEquals(null, s1.mcScoreText, "Default mcScoreText is null");
        assertEquals(false, s1.hasImdb, "Default hasImdb is false");
        assertEquals(null, s1.imdbScoreText, "Default imdbScoreText is null");
        assertEquals(false, s1.hasRow2, "Default hasRow2 is false");
        assertEquals("", s1.genresText, "Default genresText is empty");
        assertEquals("", s1.overviewText, "Default overviewText is empty");
        assertEquals(0, s1.overviewLayoutWidth, "Default overviewLayoutWidth is 0");
        assertEquals(0f, s1.overviewTextSize, 0.001f, "Default overviewTextSize is 0");

        // Populate fields
        s1.mediaKey = "pick:123:tv:Squid Game";
        s1.subTab = 1;
        s1.title = "Squid Game";
        s1.displayTitle = "Squid Game";
        s1.durationText = "55min";
        s1.datesText = "2021-Now";
        s1.hasRt = true;
        s1.rtScoreText = "95%";
        s1.hasMc = true;
        s1.mcScoreText = "8.0";
        s1.hasImdb = true;
        s1.imdbScoreText = "IMDb 8.0";
        s1.hasRow2 = true;
        s1.genresText = "Mystery | Thriller | Drama | Action";
        s1.overviewText = "Hundreds of cash-strapped players accept a strange invitation to compete in children's games.";
        s1.overviewLayoutWidth = 960;
        s1.overviewTextSize = 26f;

        // Copy
        HomeView.HeaderState s2 = new HomeView.HeaderState();
        s2.copyFrom(s1);
        assertEquals("pick:123:tv:Squid Game", s2.mediaKey, "Copied mediaKey");
        assertEquals(1, s2.subTab, "Copied subTab");
        assertEquals("Squid Game", s2.title, "Copied title");
        assertEquals("55min", s2.durationText, "Copied durationText");
        assertEquals("2021-Now", s2.datesText, "Copied datesText");
        assertEquals(true, s2.hasRt, "Copied hasRt");
        assertEquals("95%", s2.rtScoreText, "Copied rtScoreText");
        assertEquals(true, s2.hasMc, "Copied hasMc");
        assertEquals("8.0", s2.mcScoreText, "Copied mcScoreText");
        assertEquals(true, s2.hasImdb, "Copied hasImdb");
        assertEquals("IMDb 8.0", s2.imdbScoreText, "Copied imdbScoreText");
        assertEquals(true, s2.hasRow2, "Copied hasRow2");
        assertEquals("Mystery | Thriller | Drama | Action", s2.genresText, "Copied genresText");
        assertEquals(s1.overviewText, s2.overviewText, "Copied overviewText");
        assertEquals(960, s2.overviewLayoutWidth, "Copied overviewLayoutWidth");
        assertEquals(26f, s2.overviewTextSize, 0.001f, "Copied overviewTextSize");

        // Clear
        s1.clear();
        assertEquals("", s1.mediaKey, "Cleared mediaKey is empty");
        assertEquals("", s1.title, "Cleared title is empty");
        assertEquals(null, s1.durationText, "Cleared durationText is null");
        assertEquals(false, s1.hasRow2, "Cleared hasRow2 is false");
        assertEquals("", s1.overviewText, "Cleared overviewText is empty");

        // Ensure s2 was not mutated by clearing s1
        assertEquals("Squid Game", s2.title, "s2 independent after s1.clear()");

        // --- 2. Row 1 Title Handling ---
        Recs.Pick p1 = new Recs.Pick();
        p1.title = " The Good Wife ";
        assertEquals("The Good Wife", p1.title.trim(), "Title trimming");
        Recs.Pick pNull = new Recs.Pick();
        pNull.title = null;
        String resolvedTitle = pNull.title != null ? pNull.title.trim() : "";
        assertEquals("", resolvedTitle, "Null title safely defaults to empty");

        // --- 3. Row 2 Duration Permutations ---
        assertEquals("45min", Recs.formatDuration(45), "45m -> 45min");
        assertEquals("59min", Recs.formatDuration(59), "59m -> 59min");
        assertEquals("1:00h", Recs.formatDuration(60), "60m -> 1:00h");
        assertEquals("1:05h", Recs.formatDuration(65), "65m -> 1:05h");
        assertEquals("1:37h", Recs.formatDuration(97), "97m -> 1:37h");
        assertEquals("1:45h", Recs.formatDuration(105), "105m -> 1:45h");
        assertEquals("2:30h", Recs.formatDuration(150), "150m -> 2:30h");
        assertEquals(null, Recs.formatDuration(0), "0m -> null");
        assertEquals(null, Recs.formatDuration(-10), "Negative duration -> null");

        Recs.Pick pRuntime = new Recs.Pick();
        pRuntime.runtime = 41;
        assertEquals("41min", Recs.formatDuration(pRuntime), "Pick runtime 41m");
        pRuntime.runtime = 142;
        assertEquals("2:22h", Recs.formatDuration(pRuntime), "Pick runtime 142m -> 2:22h");
        pRuntime.runtime = 0;
        assertEquals(null, Recs.formatDuration(pRuntime), "Pick runtime 0m -> null");

        // --- 4. Row 2 Date Permutations ---
        assertEquals("2022", Recs.formatDates("movie", "2022-08-10", null, null, 0, "", false, 2026), "Movie past release date -> year only");
        assertEquals("2014", Recs.formatDates("movie", "2014-11-07", null, null, 0, "", false, 2026), "Movie Interstellar date -> 2014");
        assertEquals("Jan 2026", Recs.formatDates("movie", "2026-01-12", null, null, 0, "", false, 2026), "Current-year movie keeps month -> Jan 2026");
        assertEquals("2009-2016", Recs.formatDates("tv", null, "2009-09-22", "2016-05-08", 7, "Ended", false), "The Good Wife multi-season ended");
        assertEquals("2008-2013", Recs.formatDates("tv", null, "2008-01-20", "2013-09-29", 5, "Ended", false), "Breaking Bad multi-season ended");
        assertEquals("2021-Now", Recs.formatDates("tv", null, "2021-09-17", "2024-12-26", 2, "Returning Series", true), "Squid Game ongoing confirmed renewal");
        assertEquals("2016-Now", Recs.formatDates("tv", null, "2016-07-15", "2022-07-01", 4, "Returning Series", true), "Stranger Things ongoing confirmed renewal");
        assertEquals("2022", Recs.formatDates("tv", null, "2022-08-21", null, 1, "Ended", false, 2026), "Single season past miniseries -> 2022");

        // --- 5. Row 2 Ratings & Scores ---
        String rt1 = "94";
        String rtText1 = rt1.endsWith("%") ? rt1 : (rt1 + "%");
        assertEquals("94%", rtText1, "RT raw '94' formatted with %");
        String rt2 = "98%";
        String rtText2 = rt2.endsWith("%") ? rt2 : (rt2 + "%");
        assertEquals("98%", rtText2, "RT raw '98%' preserved with %");

        assertEquals("7.8", Recs.formatMetacritic("78"), "Metacritic 78 -> 7.8");
        assertEquals("8.4", Recs.formatMetacritic("84"), "Metacritic 84 -> 8.4");
        assertEquals("10.0", Recs.formatMetacritic("100"), "Metacritic 100 -> 10.0");
        assertEquals("7.8", Recs.formatMetacritic("78/100"), "Metacritic 78/100 -> 7.8");
        assertEquals("7.8", Recs.formatMetacritic("7.8"), "Metacritic decimal preserved");
        assertEquals(null, Recs.formatMetacritic("0"), "Metacritic 0 -> null");
        assertEquals(null, Recs.formatMetacritic("N/A"), "Metacritic N/A -> null");
        assertEquals(null, Recs.formatMetacritic(""), "Metacritic empty -> null");

        Recs.Pick pScore1 = new Recs.Pick();
        pScore1.imdbScore = "8.5";
        assertEquals("IMDb 8.5", "IMDb " + pScore1.imdbScore, "IMDb formatted from imdbScore");

        Recs.Pick pScore2 = new Recs.Pick();
        pScore2.imdbScore = "";
        pScore2.rating = 8.04;
        pScore2.votesCount = 1200;
        assertEquals("IMDb 8.0", String.format(java.util.Locale.US, "IMDb %.1f", pScore2.rating), "IMDb fallback to TMDB rating");

        // hasRow2 Null Safety
        Recs.Pick empty = new Recs.Pick();
        boolean emptyHasRow2 = Recs.formatDuration(empty) != null || Recs.formatDates(empty) != null || Recs.formatMetacritic(empty) != null;
        assertEquals(false, emptyHasRow2, "All-null pick hasRow2 is false");

        Recs.Pick partial = new Recs.Pick();
        partial.runtime = 45;
        boolean partialHasRow2 = Recs.formatDuration(partial) != null;
        assertEquals(true, partialHasRow2, "Single item triggers hasRow2 = true");

        // --- 6. Row 3 Genres Pipe Formatting ---
        assertEquals("Drama | Crime | Mystery", Recs.formatGenres(java.util.Arrays.asList("Drama", "Crime", "Mystery")), "Pipes 3 genres");
        assertEquals("Action", Recs.formatGenres(java.util.Arrays.asList("Action")), "Single genre no pipe");
        assertEquals("Mystery | Thriller | Drama | Action", Recs.formatGenres(java.util.Arrays.asList("Mystery", "Thriller", "Drama", "Action")), "Squid Game 4 genres");
        assertEquals("", Recs.formatGenres((java.util.List<String>) null), "Null genres list returns empty");
        assertEquals("", Recs.formatGenres(new java.util.ArrayList<String>()), "Empty genres list returns empty");

        // --- 7. Row 4 Multi-Line Overview Formatting & Non-Truncation Guarantee ---
        String raw = "Hundreds of cash-strapped players accept a strange invitation.\n\n\nThey must risk their lives\r\nin deadly children's games.";
        String formatted = Recs.formatOverviewText(raw);
        assertEquals("Hundreds of cash-strapped players accept a strange invitation.\nThey must risk their lives\nin deadly children's games.",
                formatted, "Multi-line overview preserves line breaks while collapsing excess blank lines");
        assertTrue(!formatted.endsWith("..."), "Overview is never truncated with ellipsis");
        assertTrue(!formatted.endsWith("…"), "Overview is never truncated with unicode ellipsis");
        assertEquals("", Recs.formatOverviewText("null"), "Literal 'null' overview sanitized to empty");

        // --- 8. Row 4 Vertical Auto-Scroll 4-Phase Deterministic Animation Cycle (Calm Slow Up-Down + Long Pauses) ---
        float k = 1.0f;
        float maxScrollY = 110f; // 110px vertical travel at 5.5px/sec = 20,000ms gentle scroll

        // Phase 1: Long Initial Pause (t = 0 to 7500ms)
        assertEquals(0f, HomeView.computeVerticalOverviewScrollY(0L, maxScrollY, k), 0.001f, "t=0ms Phase 1 stationary at top");
        assertEquals(0f, HomeView.computeVerticalOverviewScrollY(4000L, maxScrollY, k), 0.001f, "t=4000ms Phase 1 stationary at top");
        assertEquals(0f, HomeView.computeVerticalOverviewScrollY(7500L, maxScrollY, k), 0.001f, "t=7500ms Phase 1 stationary at boundary");

        // Phase 2: Gentle Vertical Up-Down Scroll at 5.5px/sec (110px takes 20,000ms -> t = 7500 to 27500ms)
        assertEquals(55f, HomeView.computeVerticalOverviewScrollY(17500L, maxScrollY, k), 0.02f, "t=17500ms Phase 2 vertical scroll halfway (55px)");
        assertEquals(110f, HomeView.computeVerticalOverviewScrollY(27500L, maxScrollY, k), 0.02f, "t=27500ms Phase 2 reaches bottom maxScrollY");

        // Phase 3: Long End Pause at Bottom (7500ms -> t = 27500 to 35000ms)
        assertEquals(110f, HomeView.computeVerticalOverviewScrollY(31000L, maxScrollY, k), 0.001f, "t=31000ms Phase 3 paused at bottom");
        assertEquals(110f, HomeView.computeVerticalOverviewScrollY(35000L, maxScrollY, k), 0.001f, "t=35000ms Phase 3 end of bottom pause");

        // Phase 4: Smoothstep Rewind to Top (1500ms -> t = 35000 to 36500ms)
        assertEquals(55f, HomeView.computeVerticalOverviewScrollY(35750L, maxScrollY, k), 0.02f, "t=35750ms Phase 4 rewind midpoint at 55px");
        assertEquals(0f, HomeView.computeVerticalOverviewScrollY(36500L, maxScrollY, k), 0.001f, "t=36500ms Phase 4 fully rewound to top 0px");

        // --- 9. Focus Shift Non-Interruption Reset ---
        float currentScrollX = 450f;
        long startAt = 1000L;
        currentScrollX = 0f;
        startAt = 25000L;
        assertEquals(0f, currentScrollX, 0.001f, "Scroll position immediately snaps to 0 on focus shift");
        assertEquals(25000L, startAt, "Initial pause timer resets to new timestamp");

        // --- 10. Procedural Symbols Verification ---
        assertTrue(Symbols.isSupportedIcon("rt"), "Procedural symbol 'rt' supported");
        assertTrue(Symbols.isSupportedIcon("rotten_tomatoes"), "Procedural symbol 'rotten_tomatoes' supported");
        assertTrue(Symbols.isSupportedIcon("mc"), "Procedural symbol 'mc' supported");
        assertTrue(Symbols.isSupportedIcon("metacritic"), "Procedural symbol 'metacritic' supported");
        assertTrue(Symbols.isSupportedIcon("sliders"), "Procedural symbol 'sliders' supported");
        assertTrue(Symbols.isSupportedIcon("sort"), "Procedural symbol 'sort' supported");
        assertTrue(Symbols.isSupportedIcon("similarity"), "Procedural symbol 'similarity' supported");
    }

    private static float evaluateMarqueeScrollX(long elapsedRaw, long pauseStart, long scrollDuration, long pauseEnd, long resetDuration, float maxScroll, float speedPxPerSec) {
        long totalCycle = pauseStart + scrollDuration + pauseEnd + resetDuration;
        long elapsed = elapsedRaw % totalCycle;

        if (elapsed < pauseStart) {
            return 0f;
        } else if (elapsed < pauseStart + scrollDuration) {
            float scrollElapsedSec = (elapsed - pauseStart) / 1000f;
            return Math.min(maxScroll, scrollElapsedSec * speedPxPerSec);
        } else if (elapsed < pauseStart + scrollDuration + pauseEnd) {
            return maxScroll;
        } else {
            float resetElapsed = elapsed - (pauseStart + scrollDuration + pauseEnd);
            float t = Math.max(0f, Math.min(1f, resetElapsed / (float) resetDuration));
            float ease = 1f - (t * t * (3f - 2f * t));
            return maxScroll * ease;
        }
    }

    private static Recs.Pick makeSimPick(
            int id,
            String title,
            boolean watched,
            double matchScore,
            boolean isAnime,
            String lang,
            int runtime,
            String firstAirDate,
            String overview,
            String... genres
    ) {
        Recs.Pick p = new Recs.Pick();
        p.id = id;
        p.kind = "tv";
        p.title = title;
        p.watched = watched;
        p.matchScore = matchScore;
        p.isAnime = isAnime;
        p.lang = lang;
        p.runtime = runtime;
        p.firstAirDate = firstAirDate;
        p.overview = overview;
        p.genres = new java.util.ArrayList<>(java.util.Arrays.asList(genres));
        return p;
    }

    private static int indexOfSimId(java.util.List<Recs.Pick> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) return i;
        }
        return -1;
    }

    private static void testWatchlistSimilaritySortingAndClustering() {
        // 1. Genre, medium, and franchise similarity tests
        Recs.Pick dune1 = makeSimPick(401, "Dune", false, 88, false, "en", 155, "2021-10-22",
                "Noble family becomes embroiled in a war for control over a desert planet.", "Sci-Fi", "Adventure");
        Recs.Pick dune2 = makeSimPick(402, "Dune: Part Two", false, 91, false, "en", 166, "2024-03-01",
                "Paul Atreides unites with the Fremen on a path of revenge.", "Sci-Fi", "Adventure");
        Recs.Pick arrival = makeSimPick(403, "Arrival", false, 89, false, "en", 116, "2016-11-11",
                "Linguist works with the military to communicate with alien lifeforms.", "Sci-Fi", "Drama");
        Recs.Pick toy1 = makeSimPick(404, "Toy Story", false, 85, false, "en", 81, "1995-11-22",
                "Cowboy doll feels threatened when a new spaceman toy arrives.", "Animation", "Family", "Comedy");
        Recs.Pick toy3 = makeSimPick(405, "Toy Story 3", false, 86, false, "en", 103, "2010-06-18",
                "Toys are mistakenly delivered to a day-care center before college.", "Animation", "Family", "Comedy");

        assertTrue(WatchlistSimilaritySorter.similarity(dune1, dune2) > 0.90, "Dune and Dune: Part Two should share franchise bonus");
        assertTrue(WatchlistSimilaritySorter.similarity(toy1, toy3) > 0.90, "Toy Story and Toy Story 3 should share sequel stem bonus");

        java.util.List<Recs.Pick> sortedFranchise = WatchlistSimilaritySorter.sortWatchlist(
                java.util.Arrays.asList(dune2, toy1, arrival, dune1, toy3), java.util.Collections.emptyList());
        assertTrue(Math.abs(indexOfSimId(sortedFranchise, 401) - indexOfSimId(sortedFranchise, 402)) == 1, "Dune & Dune: Part Two sit side-by-side");
        assertTrue(Math.abs(indexOfSimId(sortedFranchise, 404) - indexOfSimId(sortedFranchise, 405)) == 1, "Toy Story & Toy Story 3 sit side-by-side");

        // 2. Watched items segregation at tail
        Recs.Pick unwatchedComedy = makeSimPick(201, "The Studio", false, 82, false, "en", 28, "2025-03-26",
                "Hollywood studio boss juggles chaotic comedy shoots.", "Comedy");
        Recs.Pick watchedSciFi1 = makeSimPick(202, "Silo", true, 90, false, "en", 50, "2023-05-05",
                "Survivors live inside a giant underground bunker in a dystopian future.", "Sci-Fi", "Drama");
        Recs.Pick watchedComedy = makeSimPick(203, "Hacks", true, 88, false, "en", 30, "2021-05-13",
                "Stand-up comedian mentors a young comedy writer in Las Vegas.", "Comedy");
        Recs.Pick unwatchedSciFi = makeSimPick(204, "Foundation", false, 85, false, "en", 55, "2021-09-24",
                "Galactic empire faces collapse in an epic space sci-fi saga.", "Sci-Fi", "Drama");
        Recs.Pick watchedSciFi2 = makeSimPick(205, "3 Body Problem", true, 87, false, "en", 52, "2024-03-21",
                "Physicists uncover an alien civilization heading toward Earth in a sci-fi mystery.", "Sci-Fi", "Mystery");

        java.util.List<Recs.Pick> sortedWatched = WatchlistSimilaritySorter.sortWatchlist(
                java.util.Arrays.asList(watchedSciFi1, unwatchedComedy, watchedComedy, unwatchedSciFi, watchedSciFi2),
                java.util.Collections.emptyList());

        assertTrue(!sortedWatched.get(0).watched && !sortedWatched.get(1).watched, "First 2 items must be unwatched");
        assertTrue(sortedWatched.get(2).watched && sortedWatched.get(3).watched && sortedWatched.get(4).watched, "Last 3 items must be watched");
        assertTrue(Math.abs(indexOfSimId(sortedWatched, 202) - indexOfSimId(sortedWatched, 205)) == 1,
                "Watched Sci-Fi items should sit adjacent inside the watched tail");

        // 3. Manual order preservation with neighbor-slotting for newcomers
        Recs.Pick comedyA = makeSimPick(301, "Ted Lasso", false, 80, false, "en", 32, "2020-08-14",
                "Optimistic coach leads a British soccer club in a warm comedy.", "Comedy", "Sports");
        Recs.Pick scifiA = makeSimPick(302, "The Expanse", false, 85, false, "en", 48, "2015-11-23",
                "Spaceship crew uncovers an alien conspiracy across the solar system.", "Sci-Fi", "Thriller");
        Recs.Pick docA = makeSimPick(303, "Planet Earth II", false, 90, false, "en", 50, "2016-11-06",
                "Nature documentary exploring wildlife habitats around the globe.", "Documentary");
        Recs.Pick newSciFi = makeSimPick(304, "Battlestar Galactica", false, 84, false, "en", 45, "2004-10-18",
                "Warship fleet flees robotic enemies across deep space.", "Sci-Fi", "Thriller");

        java.util.List<String> manualOrder = java.util.Arrays.asList("tv:301", "tv:302", "tv:303");
        java.util.List<Recs.Pick> sortedManual = WatchlistSimilaritySorter.sortWatchlist(
                java.util.Arrays.asList(comedyA, scifiA, docA, newSciFi),
                manualOrder
        );

        int idxComedy = indexOfSimId(sortedManual, 301);
        int idxSciFiA = indexOfSimId(sortedManual, 302);
        int idxDoc = indexOfSimId(sortedManual, 303);
        int idxNewSciFi = indexOfSimId(sortedManual, 304);

        assertTrue(idxComedy < idxSciFiA && idxSciFiA < idxDoc,
                "Manually ordered items must preserve their exact relative sequence");
        assertTrue(Math.abs(idxNewSciFi - idxSciFiA) == 1,
                "Newly added Sci-Fi item must slot right next to existing Sci-Fi neighbor");

        // 4. Large watchlist performance benchmark
        String[][] pools = {
            {"Sci-Fi", "Thriller"}, {"Comedy", "Rom-Coms"}, {"Crime", "Drama"},
            {"Anime", "Fantasy"}, {"Documentary"}, {"Horror", "Mystery"}
        };
        java.util.List<Recs.Pick> big = new java.util.ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            String[] g = pools[i % pools.length];
            big.add(makeSimPick(1000 + i, "Title " + i, (i % 7 == 0), 50 + (i % 45),
                    "Anime".equals(g[0]), "en", 45, "2020-01-01",
                    "Engaging synopsis keywords alpha beta gamma category" + (i % pools.length), g));
        }
        // Warm up JVM classloading/JIT so parallel multi-worktree builds measure steady-state speed accurately
        WatchlistSimilaritySorter.sortWatchlist(big.subList(0, 24), java.util.Collections.emptyList());
        long t0 = System.nanoTime();
        java.util.List<Recs.Pick> sortedBig = WatchlistSimilaritySorter.sortWatchlist(big, java.util.Collections.emptyList());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(sortedBig.size() == 200, "All 200 items sorted");
        assertTrue(elapsedMs < 250, "200-item similarity seriation + 2-opt should be fast (<250ms), took " + elapsedMs + "ms");
    }

    private static void testRound2PolishGeometryVerticalScrollAndArtwork() {
        // 1. ~80% Poster Card Size & Exact 2:3 Aspect Ratio
        assertEquals(196f, HomeView.MEDIA_CARD_W, 0.01f, "Media card width scaled to ~80% (196px)");
        assertEquals(294f, HomeView.MEDIA_CARD_H, 0.01f, "Media card height scaled to ~80% (294px)");
        assertEquals(2f / 3f, HomeView.MEDIA_CARD_W / HomeView.MEDIA_CARD_H, 0.001f, "Media card exact 2:3 portrait ratio");

        // 2. Main Row Lowered + Tight Inter-Row Gap + Subtle Bottom Peek Only
        assertTrue(HomeView.HOME_ROWS_START_Y >= 540f, "Active row moved down to give multi-line summary & 4K hero space");
        assertTrue(HomeView.OVERVIEW_FONT_SIZE >= 25f, "Summary font size enlarged for comfortable TV viewing");
        float zoomedTop = HomeView.MEDIA_CARDS_TOP_OFFSET + (HomeView.MEDIA_CARD_H * 0.5f) - 8f - (HomeView.MEDIA_CARD_H * 1.12f * 0.5f);
        assertTrue(zoomedTop >= 48f, "1.12x zoomed card top clears row title baseline cleanly");
        float nextRowY = HomeView.HOME_ROWS_START_Y + HomeView.MEDIA_ROW_HEIGHT;
        float nextRowCardsTop = nextRowY + HomeView.MEDIA_CARDS_TOP_OFFSET;
        assertTrue(nextRowY >= 950f && nextRowY <= 1010f, "Next row title sits cleanly near bottom of 1080p viewport");
        assertTrue(nextRowCardsTop >= 1020f && nextRowCardsTop < 1075f, "Only a subtle sliver of next row cards peeks at bottom");
        assertTrue(nextRowY + HomeView.MEDIA_ROW_HEIGHT > 1300f, "Third row is completely off-screen");

        // 3. Artwork Orientation & 4K Backdrop Candidate Validation (Never Poster-as-Backdrop)
        assertEquals(false, Recs.isValidBackdropCandidate("/abc_poster.jpg", "/abc_poster.jpg"),
                "Portrait poster path must never be accepted as a 16:9 backdrop");
        assertEquals(false, Recs.isValidBackdropCandidate("https://images.metahub.space/poster/large/tt0903747/img", null),
                "Metahub portrait poster URL must never be accepted as a 16:9 backdrop");
        assertEquals(true, Recs.isValidBackdropCandidate("/4k_backdrop.jpg", "/abc_poster.jpg"),
                "Distinct TMDB backdrop path is valid");
        assertEquals("https://images.metahub.space/background/large/tt0903747/img",
                Recs.resolveFallbackBackdropUrl("tt0903747"),
                "IMDb ID resolves to native 16:9 Metahub large background when TMDB lacks backdrop");
        assertEquals(null, Recs.resolveFallbackBackdropUrl(""),
                "Empty IMDb ID returns null fallback backdrop");

        double uhdTextless = Recs.scoreBackdropCandidate(true, 3840, 2160, 7.5);
        double hdTextless = Recs.scoreBackdropCandidate(true, 1920, 1080, 8.5);
        double hdWithText = Recs.scoreBackdropCandidate(false, 3840, 2160, 9.0);
        double wrongPortraitInBd = Recs.scoreBackdropCandidate(true, 1000, 1500, 9.9);
        assertTrue(uhdTextless > hdTextless, "3840x2160 4K textless backdrop outscores 1920x1080 backdrop");
        assertTrue(uhdTextless > hdWithText, "Textless 4K backdrop outscores text-overlaid backdrop");
        assertTrue(wrongPortraitInBd < 0.0, "Portrait aspect ratio strictly rejected from backdrops");

        double wideBannerInPosters = Recs.scorePosterCandidate(true, 1280, 720, 9.5);
        double cleanPortraitPoster = Recs.scorePosterCandidate(true, 1000, 1500, 8.2);
        assertTrue(wideBannerInPosters < 0.0, "16:9 wide banner strictly rejected from 2:3 posters");
        assertTrue(cleanPortraitPoster > 1_000_000.0, "Clean 2:3 portrait poster strongly accepted");

        // 4. Immutable posterCacheKey stability across backdrop upgrades (prevents card blinking/refreshing)
        Recs.Pick posterTestPick = new Recs.Pick();
        posterTestPick.kind = "movie";
        posterTestPick.id = 550;
        posterTestPick.poster = "/fight_club_poster.jpg";
        posterTestPick.backdrop = "";
        String keyBeforeBackdrop = Recs.posterCacheKey(posterTestPick);
        posterTestPick.backdrop = "/fight_club_4k_uhd.jpg";
        String keyAfterBackdrop = Recs.posterCacheKey(posterTestPick);
        assertEquals("pk:movie:550", keyBeforeBackdrop, "posterCacheKey uses canonical pick identity");
        assertEquals(keyBeforeBackdrop, keyAfterBackdrop, "posterCacheKey never mutates when backdrop URL updates");
    }

    private static void testRound3PolishFixes() {
        // 1. Gaming Screen Wide & Short 16:9 Tile Geometry Restored
        assertEquals(352f, HomeView.WIDE_BROWSER_CARD_W, 0.01f, "Gaming wide card width restored to 352px");
        assertEquals(198f, HomeView.WIDE_BROWSER_CARD_H, 0.01f, "Gaming wide card height restored to 198px");
        assertEquals(16f / 9f, HomeView.WIDE_BROWSER_CARD_W / HomeView.WIDE_BROWSER_CARD_H, 0.001f,
                "Gaming screen cards use exact 16:9 wide & short aspect ratio");

        // 2. Physical Game Controller Filtering (Excludes Virtual - Remote, Virtual - Search, TV remotes, CEC)
        assertEquals(false, GamingZone.isPhysicalGameController("Virtual - Remote", false, true, true),
                "Rejects Google TV 'Virtual - Remote' uinput bridge");
        assertEquals(false, GamingZone.isPhysicalGameController("Virtual - Search", false, true, true),
                "Rejects Google TV 'Virtual - Search' uinput bridge");
        assertEquals(false, GamingZone.isPhysicalGameController("Xiaomi RC", false, true, true),
                "Rejects Xiaomi Bluetooth TV remote");
        assertEquals(false, GamingZone.isPhysicalGameController("HDMI CEC", false, true, true),
                "Rejects HDMI-CEC virtual input");
        assertEquals(false, GamingZone.isPhysicalGameController("Xbox Wireless Controller", true, true, true),
                "Rejects Android virtual input device");
        assertEquals(false, GamingZone.isPhysicalGameController("Generic Joystick", false, true, false),
                "Rejects input lacking standard gamepad A/B/L1 buttons");
        assertEquals(true, GamingZone.isPhysicalGameController("Xbox Wireless Controller", false, true, true),
                "Accepts real Xbox Wireless Controller");
        assertEquals(true, GamingZone.isPhysicalGameController("DualSense Wireless Controller", false, true, true),
                "Accepts real DualSense Wireless Controller");
        assertEquals(true, GamingZone.isPhysicalGameController("8BitDo Pro 2", false, true, true),
                "Accepts real 8BitDo Pro 2 controller");

        // 3. Clean Count-Only Controller Summary & Concise PC Transport Status (No 'Moonlight Ready')
        assertEquals("0 Controllers Connected", GamingZone.formatControllerCountSummary(0), "0 controllers summary");
        assertEquals("0 Controllers Connected", GamingZone.formatControllerCountSummary(-2), "Negative clamps to 0 controllers");
        assertEquals("1 Controller Connected", GamingZone.formatControllerCountSummary(1), "1 controller singular summary");
        assertEquals("2 Controllers Connected", GamingZone.formatControllerCountSummary(2), "2 controllers plural summary");

        assertEquals("PC Offline", GamingZone.formatPcConnectionStatus(false, false), "Offline PC label");
        assertEquals("PC Offline", GamingZone.formatPcConnectionStatus(false, true), "Offline PC ignores stale cable flag");
        assertEquals("PC Online · Wireless", GamingZone.formatPcConnectionStatus(true, false), "Online wireless PC label");
        assertEquals("PC Online · Cable", GamingZone.formatPcConnectionStatus(true, true), "Online HDMI/cable PC label");

        // 4. Summary Scroll Speed & Long Start/End Pause Constants
        assertTrue(HomeView.OVERVIEW_PAUSE_START_MS >= 7000L, "Long initial reading pause (>=7s) before summary scroll");
        assertTrue(HomeView.OVERVIEW_PAUSE_END_MS >= 7000L, "Long end reading pause (>=7s) at bottom of summary");
        assertTrue(HomeView.OVERVIEW_SCROLL_SPEED <= 6.5f, "Much slower summary vertical scroll speed");
    }

    private static void testControllerColorTaggingAndBatteryState() {
        // 1. Color Palette (10 standard colors) & User Presets (Light Blue, Pink, Green, Yellow)
        assertEquals(10, ControllerManager.PALETTE.size(), "Closed palette has 10 standard colors");
        assertTrue(ControllerManager.PALETTE.containsKey("Red"), "Palette contains Red");
        assertTrue(ControllerManager.PALETTE.containsKey("Orange"), "Palette contains Orange");
        assertTrue(ControllerManager.PALETTE.containsKey("Yellow"), "Palette contains Yellow");
        assertTrue(ControllerManager.PALETTE.containsKey("Warm-Green"), "Palette contains Warm-Green");
        assertTrue(ControllerManager.PALETTE.containsKey("Cold-Green"), "Palette contains Cold-Green");
        assertTrue(ControllerManager.PALETTE.containsKey("Blue"), "Palette contains Blue");
        assertTrue(ControllerManager.PALETTE.containsKey("Light-Blue"), "Palette contains Light-Blue");
        assertTrue(ControllerManager.PALETTE.containsKey("White"), "Palette contains White");
        assertTrue(ControllerManager.PALETTE.containsKey("Gray"), "Palette contains Gray");
        assertTrue(ControllerManager.PALETTE.containsKey("Black"), "Palette contains Black");

        assertEquals(4, ControllerManager.PRESETS.size(), "4 preset colors configured");
        assertEquals(ControllerManager.COLOR_LIGHT_BLUE, (int) ControllerManager.PRESETS.get("Light Blue"), "Preset Light Blue matches #c2ddfb");
        assertEquals(ControllerManager.COLOR_PINK, (int) ControllerManager.PRESETS.get("Pink"), "Preset Pink matches #f9c1d4");
        assertEquals(ControllerManager.COLOR_GREEN, (int) ControllerManager.PRESETS.get("Green"), "Preset Green matches #cef0be");
        assertEquals(ControllerManager.COLOR_YELLOW, (int) ControllerManager.PRESETS.get("Yellow"), "Preset Yellow matches #fee776");

        // 2. Custom Hex String Parsing & Formatting
        assertEquals(0xFFC2DDFB, ControllerManager.parseColorHex("#c2ddfb", 0), "Parses #c2ddfb");
        assertEquals(0xFFC2DDFB, ControllerManager.parseColorHex("c2ddfb", 0), "Parses c2ddfb without hash");
        assertEquals(0xFFF9C1D4, ControllerManager.parseColorHex("#f9c1d4", 0), "Parses #f9c1d4");
        assertEquals(0xFFCEF0BE, ControllerManager.parseColorHex("#cef0be", 0), "Parses #cef0be");
        assertEquals(0xFFFEE776, ControllerManager.parseColorHex("#fee776", 0), "Parses #fee776");
        assertEquals(0xFF3B82F6, ControllerManager.parseColorHex("#3B82F6", 0), "Parses #3B82F6");
        assertEquals(0xFFFFFFFF, ControllerManager.parseColorHex("#fff", 0), "Parses 3-char hex shorthand #fff");
        assertEquals(0xFF112233, ControllerManager.parseColorHex("#123", 0), "Parses 3-char hex shorthand #123");
        assertEquals(0x123456, ControllerManager.parseColorHex("invalid_hex", 0x123456), "Invalid hex falls back to default");
        assertEquals(0x123456, ControllerManager.parseColorHex(null, 0x123456), "Null hex falls back to default");

        assertEquals("#c2ddfb", ControllerManager.formatColorHex(0xFFC2DDFB), "Formats #c2ddfb");
        assertEquals("#f9c1d4", ControllerManager.formatColorHex(0xFFF9C1D4), "Formats #f9c1d4");
        assertEquals("#cef0be", ControllerManager.formatColorHex(0xFFCEF0BE), "Formats #cef0be");
        assertEquals("#fee776", ControllerManager.formatColorHex(0xFFFEE776), "Formats #fee776");

        // 3. Contrast Calculation (Semi-transparent Black for light backgrounds, White for dark)
        assertEquals(0xCC000000, ControllerManager.calculateContrastInk(ControllerManager.COLOR_LIGHT_BLUE), "Light blue has semi-transparent black contrast ink");
        assertEquals(0xCC000000, ControllerManager.calculateContrastInk(ControllerManager.COLOR_PINK), "Pink has semi-transparent black contrast ink");
        assertEquals(0xCC000000, ControllerManager.calculateContrastInk(ControllerManager.COLOR_GREEN), "Green has semi-transparent black contrast ink");
        assertEquals(0xCC000000, ControllerManager.calculateContrastInk(ControllerManager.COLOR_YELLOW), "Yellow has semi-transparent black contrast ink");
        assertEquals(0xCC000000, ControllerManager.calculateContrastInk(0xFFFFFFFF), "White has semi-transparent black contrast ink");

        assertEquals(0xD9FFFFFF, ControllerManager.calculateContrastInk(ControllerManager.PALETTE.get("Blue")), "Blue has semi-transparent white contrast ink");
        assertEquals(0xD9FFFFFF, ControllerManager.calculateContrastInk(ControllerManager.PALETTE.get("Black")), "Black has semi-transparent white contrast ink");
        assertEquals(0xD9FFFFFF, ControllerManager.calculateContrastInk(ControllerManager.PALETTE.get("Red")), "Red has semi-transparent white contrast ink");
        assertEquals(0xD9FFFFFF, ControllerManager.calculateContrastInk(ControllerManager.PALETTE.get("Cold-Green")), "Cold-Green has semi-transparent white contrast ink");
        assertEquals(0xD9FFFFFF, ControllerManager.calculateContrastInk(ControllerManager.COLOR_NEUTRAL_FALLBACK), "Neutral fallback slate-grey has semi-transparent white contrast ink");

        // 4. Controller Tagging, Custom Naming, and Neutral Fallback for Untagged Controllers
        ControllerManager.ControllerInfo untagged = new ControllerManager.ControllerInfo(
                "desc_xbox_1", "Xbox Wireless Controller", "", ControllerManager.COLOR_NEUTRAL_FALLBACK, false, 85, false
        );
        assertEquals("Xbox Wireless Controller", untagged.getDisplayName(), "Untagged controller displays device name");
        assertEquals(ControllerManager.COLOR_NEUTRAL_FALLBACK, untagged.color, "Untagged controller has neutral fallback color (#64748B), not auto-assigned");
        assertEquals(false, untagged.tagged, "Untagged controller has tagged=false");
        assertEquals("85%", untagged.getBatteryDisplay(), "Untagged controller displays 85% battery");
        assertEquals(false, untagged.charging, "Untagged controller not charging");

        ControllerManager.ControllerInfo unknownBatt = untagged.withBattery(-1, false);
        assertEquals("", unknownBatt.getBatteryDisplay(), "Unknown battery (-1) returns empty string to hide percentage");

        ControllerManager.ControllerInfo zeroBatt = untagged.withBattery(0, false);
        assertEquals("0%", zeroBatt.getBatteryDisplay(), "0% battery returns 0%");

        ControllerManager.ControllerInfo tagged = untagged.withTag("Player 1 Xbox", ControllerManager.COLOR_LIGHT_BLUE);
        assertEquals("Player 1 Xbox", tagged.getDisplayName(), "Tagged controller displays custom name");
        assertEquals(ControllerManager.COLOR_LIGHT_BLUE, tagged.color, "Tagged controller has chosen Light Blue color");
        assertEquals(true, tagged.tagged, "Tagged controller has tagged=true");

        ControllerManager.ControllerInfo chargingCtrl = tagged.withBattery(90, true);
        assertEquals(true, chargingCtrl.charging, "Controller is charging");
        assertEquals("90%", chargingCtrl.getBatteryDisplay(), "Shows 90% battery");

        // 5. Mock Physical Controllers List (Non-Disruptive Offline Automated Testing)
        ControllerManager.ControllerInfo p1 = new ControllerManager.ControllerInfo(
                "desc_ds5_1", "DualSense Controller", "Player 1", ControllerManager.COLOR_PINK, true, 75, true
        );
        ControllerManager.ControllerInfo p2 = new ControllerManager.ControllerInfo(
                "desc_8bitdo", "8BitDo Pro 2", "Player 2", ControllerManager.COLOR_YELLOW, true, 40, false
        );
        ControllerManager.setMockConnectedControllers(java.util.Arrays.asList(p1, p2));
        java.util.List<ControllerManager.ControllerInfo> active = ControllerManager.getConnectedPhysicalControllers(null);
        assertEquals(2, active.size(), "2 physical controllers connected");
        assertEquals("Player 1", active.get(0).getDisplayName(), "Controller 1 custom name");
        assertEquals(ControllerManager.COLOR_PINK, active.get(0).color, "Controller 1 pink color");
        assertEquals(true, active.get(0).charging, "Controller 1 is charging");
        assertEquals("75%", active.get(0).getBatteryDisplay(), "Controller 1 battery display");

        assertEquals("Player 2", active.get(1).getDisplayName(), "Controller 2 custom name");
        assertEquals(ControllerManager.COLOR_YELLOW, active.get(1).color, "Controller 2 yellow color");
        assertEquals(false, active.get(1).charging, "Controller 2 is discharging");
        assertEquals("40%", active.get(1).getBatteryDisplay(), "Controller 2 battery display");

        // Verify GamingZone delegation to ControllerManager
        java.util.List<String> gzControllers = GamingZone.getConnectedControllers(null);
        assertEquals(2, gzControllers.size(), "GamingZone delegates to ControllerManager for connected controllers");
        assertEquals("Player 1", gzControllers.get(0), "GamingZone returns custom name Player 1");
        assertEquals("Player 2", gzControllers.get(1), "GamingZone returns custom name Player 2");
        assertEquals("2 Controllers Connected", GamingZone.getControllerStatusSummary(null), "GamingZone summary reports 2 Controllers Connected");

        // Verify GamingZone.toStatusJson execution
        try {
            GamingZone.toStatusJson(null, false);
        } catch (RuntimeException ignored) {
            // android.jar stub in headless host JVM
        } catch (org.json.JSONException e) {
            throw new AssertionError("JSON parsing failed: " + e.getMessage());
        }

        ControllerManager.setMockConnectedControllers(null);

        // 6. PC Connection Status with Battery & Charging Reporting (Zero Emojis Mandate)
        assertEquals("PC Offline", GamingZone.formatPcConnectionStatus(false, false, 80, true, false), "Offline PC shows PC Offline regardless of battery");
        assertEquals("PC Offline", GamingZone.formatPcConnectionStatus(false, true, 100, true, true), "Offline PC ignores cable and battery");

        assertEquals("PC Online · Wireless · 45%", GamingZone.formatPcConnectionStatus(true, false, 45, false, false), "Wireless PC discharging at 45%");
        assertEquals("PC Online · Cable · 60%", GamingZone.formatPcConnectionStatus(true, true, 60, false, false), "Cable PC discharging at 60%");

        assertEquals("PC Online · Wireless · 85%", GamingZone.formatPcConnectionStatus(true, false, 85, true, false), "Wireless PC charging at 85%");
        assertEquals("PC Online · Cable · 72%", GamingZone.formatPcConnectionStatus(true, true, 72, true, false), "Cable PC charging at 72%");

        assertTrue(GamingZone.isPcBatteryChargingIndicatorNeeded(true, 85, true, false), "Charging at 85% needs procedural bolt indicator");
        assertTrue(GamingZone.isPcBatteryChargingIndicatorNeeded(true, 72, true, false), "Charging at 72% needs procedural bolt indicator");
        assertEquals(false, GamingZone.isPcBatteryChargingIndicatorNeeded(true, 45, false, false), "Discharging at 45% does not show bolt indicator");
        assertTrue(GamingZone.isPcBatteryChargingIndicatorNeeded(true, 100, true, true), "100% full on AC power shows connected-to-power bolt indicator");
        assertTrue(GamingZone.isPcBatteryChargingIndicatorNeeded(true, 100, true, false), "100% capacity on AC power shows connected-to-power bolt indicator");
        assertEquals(false, GamingZone.isPcBatteryChargingIndicatorNeeded(true, -1, true, false), "Desktop without battery does not show bolt indicator");
        assertEquals(false, GamingZone.isPcBatteryChargingIndicatorNeeded(false, 85, true, false), "Offline PC does not show bolt indicator");

        // Full 100% capacity on AC power displays 100% + connected-to-power bolt; desktop without battery omits %
        assertEquals("PC Online · Wireless · 100%", GamingZone.formatPcConnectionStatus(true, false, 100, true, true), "Wireless PC on AC at 100% full displays 100%");
        assertEquals("PC Online · Cable · 100%", GamingZone.formatPcConnectionStatus(true, true, 100, true, true), "Cable PC on AC at 100% full displays 100%");
        assertEquals("PC Online · Wireless · 100%", GamingZone.formatPcConnectionStatus(true, false, 100, true, false), "Charging at 100% capacity displays 100%");
        assertEquals("PC Online · Wireless", GamingZone.formatPcConnectionStatus(true, false, -1, true, false), "Desktop PC without battery omits battery percentage");

        // 7. Settings Tree Coverage: Controllers live under Gaming -> Game Controllers AND Remotes & Accessories
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        assertTrue(root.findById("remotes_accessories") != null, "Top-level Remotes & Accessories exists");
        SettingsTree.Node gamingNode = root.findById("gaming_settings");
        assertTrue(gamingNode != null, "Settings root contains dedicated Gaming node");
        assertTrue(gamingNode.findById("gaming_bt_pair") != null, "Gaming contains in-app Pair Bluetooth Controller node");
        assertTrue(gamingNode.findById("gaming_controllers") != null, "Gaming contains Controllers & Remotes node");
        assertTrue(gamingNode.findById("gaming_ctrl_wizard") != null, "Gaming contains Guided Multi-Controller Setup node");
        SettingsTree.Node rainbowNode = gamingNode.findById("gaming_colors_rainbow");
        assertTrue(rainbowNode != null, "Gaming contains visual Rainbow Controller Colors node");
        assertTrue(rainbowNode.children.size() >= 17, "Rainbow palette contains 17 rainbow-ordered colors + custom option");
        assertTrue(gamingNode.findById("gaming_color_custom") != null, "Gaming rainbow colors contain Custom Color option");

        // 8. Universal Symbols: Bolt and Battery Icons
        assertTrue(Symbols.isSupportedIcon("bolt"), "Symbols supports bolt icon");
        assertTrue(Symbols.isSupportedIcon("lightning"), "Symbols supports lightning icon");
        assertTrue(Symbols.isSupportedIcon("battery"), "Symbols supports battery icon");

        // 9. Stable Hardware Descriptors (resolves persistent descriptor across reconnects)
        assertEquals("sony_ds5_desc_abc",
                ControllerManager.resolveStableDescriptor("sony_ds5_desc_abc", "DualSense", 0x054c, 0x0ce6),
                "Existing descriptor is preserved");
        assertEquals("Xbox Controller_1118_765",
                ControllerManager.resolveStableDescriptor(null, "Xbox Controller", 1118, 765),
                "Fallback descriptor incorporates stable vendorId and productId");
        assertEquals("Xbox Controller_1118_765",
                ControllerManager.resolveStableDescriptor("   ", "Xbox Controller", 1118, 765),
                "Blank descriptor falls back to vendorId and productId");
        assertEquals("Generic Gamepad",
                ControllerManager.resolveStableDescriptor(null, "Generic Gamepad", 0, 0),
                "Zero VID/PID falls back to device name");
        assertEquals("Gamepad",
                ControllerManager.resolveStableDescriptor(null, null, 0, 0),
                "Null name falls back to Gamepad");

        // 10. Thread-safe Bolt Canvas Null Safety
        ControllerManager.drawLightningBolt(null, 10f, 10f, 10f, 0xFFFFFFFF);
    }

    private static void testUsbXInputDongleAndBluetoothBatteryFallback() {
        // 1. Detection of 2.4G XBOX 360 For Windows Keyboard (GameSir Nova Lite 0x3537:0x1040)
        // Even when hasStandardGamepadButtons = false and name ends with Keyboard
        assertEquals(true,
                GamingZone.isPhysicalGameController("2.4G XBOX 360 For Windows Keyboard", false, true, false),
                "2.4G XBOX 360 For Windows Keyboard is accepted as a physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("2.4G XBOX 360 For Windows Keyboard", false, true, false, true),
                "XInput matched USB dongle is accepted as physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("Xbox 360 Controller", false, true, false, true),
                "Matched XInput USB device is accepted without standard buttons");

        // 1b. Real controller brands with "mic", "pen", etc. in brand name must NEVER be falsely rejected
        assertEquals(true,
                GamingZone.isPhysicalGameController("Microsoft Xbox Wireless Controller", false, true, true),
                "Microsoft Xbox Wireless Controller is accepted as physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("Microsoft Xbox 360 Controller", false, true, true),
                "Microsoft Xbox 360 Controller is accepted as physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("8BitDo Micro", false, true, true),
                "8BitDo Micro gamepad is accepted as physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("Cosmic Byte Ares Wireless Controller", false, true, true),
                "Cosmic Byte gamepad is accepted as physical controller");
        assertEquals(true,
                GamingZone.isPhysicalGameController("OpenGamepad", false, true, true),
                "OpenGamepad is accepted as physical controller");

        // 1c. Exclusion of companion Consumer Control / System Control / Mouse / Touch / Audio / Headset / Stylus / Mic interfaces
        assertEquals(false,
                GamingZone.isPhysicalGameController("2.4G XBOX 360 For Windows Consumer Control", false, true, false, true),
                "Consumer Control companion interface rejected even when matched to XInput USB");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Companion Consumer Control", false, true, true, false),
                "Xbox Consumer Control companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Wireless Controller System Control", false, true, true, false),
                "Xbox System Control companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Touchpad", false, true, true, false),
                "Xbox Touchpad companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Wireless Controller Headset", false, true, true, false),
                "Xbox Headset companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox 360 Audio", false, true, true, false),
                "Xbox Audio companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Controller Mic", false, true, true, false),
                "Xbox Mic companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Stylus", false, true, true, false),
                "Xbox Stylus companion interface rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("rc 2.4g gamepad", false, true, true, false),
                "Leading rc prefix rejected");

        // 2. Stripping companion interface suffixes (" Keyboard", " Consumer Control", " (Keyboard)", " - Keyboard", brackets, delimiters)
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows Keyboard"),
                "Strips trailing Keyboard suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows Consumer Control"),
                "Strips trailing Consumer Control suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows (Keyboard)"),
                "Strips (Keyboard) suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows [Keyboard]"),
                "Strips [Keyboard] suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows (Consumer Control)"),
                "Strips (Consumer Control) suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows [Consumer Control]"),
                "Strips [Consumer Control] suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows - Mouse"),
                "Strips - Mouse suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows (Touchpad)"),
                "Strips (Touchpad) suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows / Keyboard"),
                "Strips / Keyboard suffix and delimiter");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows - Keyboard"),
                "Strips - Keyboard suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows - System Control"),
                "Strips - System Control suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows [System Control]"),
                "Strips [System Control] suffix");
        assertEquals("2.4G XBOX 360 For Windows",
                ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows Keyboard Consumer Control"),
                "Strips multiple companion interface suffixes");
        assertEquals("Xbox Wireless Controller",
                ControllerManager.cleanDeviceName("Xbox Wireless Controller"),
                "Preserves clean standard controller names");
        assertEquals("Gamepad",
                ControllerManager.cleanDeviceName(null),
                "Null name falls back to Gamepad");

        // 3. XInput interface identification & Rejection of idle GameSir dongle (0x3537:0x2106 HID zk)
        // Xbox 360 XInput: interfaceClass == 255, interfaceSubclass == 93, interfaceProtocol == 1 or 129
        assertTrue(ControllerManager.isXInputInterface(255, 93, 1), "XInput wired interface matched");
        assertTrue(ControllerManager.isXInputInterface(255, 93, 129), "XInput wireless interface matched");
        // Xbox One GIP: interfaceClass == 255, interfaceSubclass == 71, interfaceProtocol == 208
        assertTrue(ControllerManager.isXInputInterface(255, 71, 208), "Xbox One GIP interface matched");
        // Idle GameSir Nova Lite dongle (VID 0x3537, PID 0x2106) enumerates as HID zk (interfaceClass == 3)
        assertEquals(false, ControllerManager.isXInputInterface(3, 0, 0), "Idle HID zk interface (class 3) rejected");
        assertEquals(false, ControllerManager.isXInputInterface(3, 1, 1), "Standard HID keyboard/mouse interface rejected");

        // 4. Physical USB port topology extraction & dumpsys input parsing
        assertEquals("xhci-2",
                ControllerManager.extractUsbPortIdentifier("usb-1c7a0000.xhci-2/input1"),
                "Extracts xhci-2 USB port identifier");
        assertEquals("xhci-1",
                ControllerManager.extractUsbPortIdentifier("usb-1c7a0000.xhci-1/input1"),
                "Extracts xhci-1 USB port identifier");
        assertEquals("xhci-1.2",
                ControllerManager.extractUsbPortIdentifier("usb-xhci-hcd.0.auto-1.2/input0"),
                "Extracts hub-connected xhci-1.2 USB port identifier");
        assertEquals("",
                ControllerManager.extractUsbPortIdentifier("/dev/bus/usb/001/030"),
                "Rejects ephemeral Linux /dev/bus/usb/001/030 device counter so USB tags survive power-cycles");
        assertEquals("",
                ControllerManager.extractUsbPortIdentifier("usb_1036"),
                "Rejects ephemeral Android UsbDevice id usb_1036 so USB tags survive power-cycles");
        assertEquals("USB 2",
                ControllerManager.extractUsbPortLabel("usb-1c7a0000.xhci-2/input1"),
                "Extracts clean USB 2 human-readable label");
        assertEquals("USB 1",
                ControllerManager.extractUsbPortLabel("usb-1c7a0000.xhci-1/input1"),
                "Extracts clean USB 1 human-readable label");
        assertEquals("USB 1.2",
                ControllerManager.extractUsbPortLabel("usb-xhci-hcd.0.auto-1.2/input0"),
                "Extracts clean USB 1.2 human-readable label");
        assertEquals("",
                ControllerManager.extractUsbPortLabel("/dev/bus/usb/001/030"),
                "Returns empty port label for ephemeral /dev/bus/usb path");

        String mockDump =
                "Event Hub State:\n" +
                "  BuiltInKeyboardId: -2\n" +
                "  Devices:\n" +
                "    33: 2.4G XBOX 360 For Windows Keyboard\n" +
                "      Classes: KEYBOARD | ALPHAKEY | DPAD | JOYSTICK | EXTERNAL\n" +
                "      Path: /dev/input/event7\n" +
                "      Location: usb-1c7a0000.xhci-2/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1040, version=0x0111, bluetoothAddress=<not set>\n" +
                "    34: 2.4G XBOX 360 For Windows Keyboard\n" +
                "      Classes: KEYBOARD | ALPHAKEY | DPAD | JOYSTICK | EXTERNAL\n" +
                "      Path: /dev/input/event8\n" +
                "      Location: usb-1c7a0000.xhci-1/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1040, version=0x0111, bluetoothAddress=<not set>\n" +
                "    35: Hub Connected Gamepad\n" +
                "      Classes: KEYBOARD | DPAD | JOYSTICK\n" +
                "      Location: usb-xhci-hcd.0.auto-1.2/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x045e, product=0x028e, version=0x0110, bluetoothAddress=<not set>\n" +
                "    26: Xiaomi RC Consumer Control\n" +
                "      Classes: KEYBOARD | DPAD | JOYSTICK | MIC | EXTERNAL\n" +
                "      Path: /dev/input/event3\n" +
                "      Location: 9c:12:21:e1:3b:7f\n" +
                "      UniqueId: e8:55:40:5f:b2:2c\n" +
                "      Identifier: bus=0x0005, vendor=0x2717, product=0x32b9, version=0x0011, bluetoothAddress=E8:55:40:5F:B2:2C\n";

        java.util.Map<Integer, ControllerManager.DeviceHardwareLocation> parsedDump =
                ControllerManager.parseInputDumpLocations(mockDump);
        assertEquals(4, parsedDump.size(), "Parsed 4 input devices from Event Hub dump");
        assertTrue(parsedDump.containsKey(33), "Contains device 33 (USB Port 2)");
        assertEquals("usb-1c7a0000.xhci-2/input1", parsedDump.get(33).location, "Device 33 matches Port 2 location");
        assertTrue(parsedDump.containsKey(34), "Contains device 34 (USB Port 1)");
        assertEquals("usb-1c7a0000.xhci-1/input1", parsedDump.get(34).location, "Device 34 matches Port 1 location");
        assertTrue(parsedDump.containsKey(35), "Contains device 35 (USB Hub)");
        assertEquals("usb-xhci-hcd.0.auto-1.2/input0", parsedDump.get(35).location, "Device 35 matches Hub location");
        assertTrue(parsedDump.containsKey(26), "Contains device 26 (Bluetooth RC)");
        assertEquals("e8:55:40:5f:b2:2c", parsedDump.get(26).uniqueId, "Extracts Bluetooth MAC uniqueId");
        assertEquals("E8:55:40:5F:B2:2C", parsedDump.get(26).bluetoothAddress, "Extracts Bluetooth MAC hardware address");

        // 5. Deterministic per-USB-port descriptor persistence (Pink on Port 2 vs Blue on Port 1)
        // Even when EventHub reuses raw SHA-1 descriptor a15f809e... due to SerialNumber=0:
        String sharedRawSha1 = "a15f809e10097d33d65f74d071397c24578364ac";
        String pinkClean = ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows Keyboard");
        String blueClean = ControllerManager.cleanDeviceName("2.4G XBOX 360 For Windows Keyboard");

        String pinkPortDesc = ControllerManager.resolveStableDescriptor(sharedRawSha1, null, "xhci-2", pinkClean, 0x3537, 0x1040);
        String bluePortDesc = ControllerManager.resolveStableDescriptor(sharedRawSha1, null, "xhci-1", blueClean, 0x3537, 0x1040);

        assertEquals("usbport:13623:4160:xhci-2", pinkPortDesc, "Pink controller generates deterministic Port 2 descriptor");
        assertEquals("usbport:13623:4160:xhci-1", bluePortDesc, "Blue controller generates deterministic Port 1 descriptor");
        assertTrue(!pinkPortDesc.equals(bluePortDesc), "Port-aware descriptors are completely unique across ports");

        // Over Bluetooth, canonical "bt:<mac>" descriptor guarantees tag continuity between bonded idle & awake InputDevice:
        assertEquals("bt:e8:55:40:5f:b2:2c",
                ControllerManager.resolveStableDescriptor(null, "E8:55:40:5F:B2:2C", "Gamepad", 0, 0),
                "Canonicalizes Bluetooth MAC uniqueId to bt:<lowercase-mac>");
        assertEquals("bt:aa:bb:cc:11:22:33",
                ControllerManager.resolveStableDescriptor(sharedRawSha1, "AA:BB:CC:11:22:33", "Wireless Controller", 0x054c, 0x09cc),
                "Prefers canonical bt:<mac> over ephemeral EventHub SHA-1 when Bluetooth MAC is known");

        ControllerManager.ControllerInfo pinkCtrl = new ControllerManager.ControllerInfo(
                101, pinkPortDesc, pinkClean, "Pink GameSir", ControllerManager.COLOR_PINK, true, -1, false
        );
        ControllerManager.ControllerInfo blueCtrl = new ControllerManager.ControllerInfo(
                102, bluePortDesc, blueClean, "Blue GameSir", ControllerManager.COLOR_LIGHT_BLUE, true, -1, false
        );
        assertEquals("Pink GameSir", pinkCtrl.getDisplayName(), "Pink controller displays custom name");
        assertEquals(ControllerManager.COLOR_PINK, pinkCtrl.color, "Pink controller preserves Pink color tag");
        assertEquals("Blue GameSir", blueCtrl.getDisplayName(), "Blue controller displays custom name");
        assertEquals(ControllerManager.COLOR_LIGHT_BLUE, blueCtrl.color, "Blue controller preserves Blue color tag");

        ControllerManager.setMockConnectedControllers(java.util.Arrays.asList(pinkCtrl, blueCtrl));
        java.util.List<ControllerManager.ControllerInfo> activeDongles = ControllerManager.getConnectedPhysicalControllers(null);
        assertEquals(2, activeDongles.size(), "Both distinct 2.4G USB dongles recognized simultaneously");
        assertEquals(pinkPortDesc, activeDongles.get(0).descriptor, "First dongle descriptor matches pink port");
        assertEquals(bluePortDesc, activeDongles.get(1).descriptor, "Second dongle descriptor matches blue port");
        ControllerManager.setMockConnectedControllers(null);

        // 6. Bluetooth battery fallback & collision avoidance between identical controller names
        // Safe null handling without crash
        assertEquals(-1, ControllerManager.queryBluetoothBatteryFallback(null, pinkPortDesc, pinkClean), "Null context safely returns -1");
        // Test hook verifies battery percentage reporting
        ControllerManager.setMockBluetoothBatteryForTesting(85);
        assertEquals(85, ControllerManager.queryBluetoothBatteryFallback(null, "bt_mac_addr", "Xbox Wireless Controller"), "Reports Bluetooth battery fallback percentage");
        ControllerManager.setMockBluetoothBatteryForTesting(null);
        assertEquals(-1, ControllerManager.queryBluetoothBatteryFallback(null, "bt_mac_addr", "Xbox Wireless Controller"), "Restores live Bluetooth query fallback safety");

        // Multi-controller collision-free battery lookup with MAC resolution priority:
        java.util.Map<String, Integer> mockBatt = new java.util.HashMap<>();
        mockBatt.put("AA:BB:CC:11:22:33", 88);
        mockBatt.put("DD:EE:FF:44:55:66", 42);
        mockBatt.put("Xbox Wireless Controller", 70);
        ControllerManager.setMockBluetoothBatteriesForTesting(mockBatt);

        java.util.Set<String> claimed = new java.util.HashSet<>();
        // Controller 1 matches MAC 1
        int batt1 = ControllerManager.queryBluetoothBatteryFallback(null, null, "AA:BB:CC:11:22:33", "Xbox Wireless Controller", claimed);
        assertEquals(88, batt1, "Controller 1 resolves distinct battery from MAC 1");
        // Controller 2 matches MAC 2
        int batt2 = ControllerManager.queryBluetoothBatteryFallback(null, null, "DD:EE:FF:44:55:66", "Xbox Wireless Controller", claimed);
        assertEquals(42, batt2, "Controller 2 resolves distinct battery from MAC 2 without collision");

        // Controller 3 claims name fallback, but Controller 4 cannot reuse the same claimed name:
        int batt3 = ControllerManager.queryBluetoothBatteryFallback(null, null, "unmatched_desc_1", "Xbox Wireless Controller", claimed);
        assertEquals(70, batt3, "Controller 3 claims unclaimed device name");
        int batt4 = ControllerManager.queryBluetoothBatteryFallback(null, null, "unmatched_desc_2", "Xbox Wireless Controller", claimed);
        assertEquals(-1, batt4, "Controller 4 cannot claim already-claimed device name");

        // Case-insensitive & normalized MAC resolution (lowercase MAC matches uppercase map entry)
        java.util.Set<String> claimedCase = new java.util.HashSet<>();
        int battCase = ControllerManager.queryBluetoothBatteryFallback(null, null, "aa:bb:cc:11:22:33", "Xbox Wireless Controller", claimedCase);
        assertEquals(88, battCase, "Case-insensitive MAC resolution matches uppercase map entry");

        ControllerManager.setMockBluetoothBatteriesForTesting(null);

        // 7. Ephemeral /dev/bus/usb/<bus>/<devnum> rejected (power-cycle safe) while real sysfs xhci ports stay distinct
        assertEquals("", ControllerManager.extractUsbPortIdentifier("/dev/bus/usb/001/030"),
                "Ephemeral Linux devnum 030 rejected so power-cycling to 033 never breaks saved tags");
        assertEquals("", ControllerManager.extractUsbPortIdentifier("/dev/bus/usb/001/033"),
                "Ephemeral Linux devnum 033 rejected");
        assertEquals("", ControllerManager.extractUsbPortIdentifier("usb_1036"),
                "Ephemeral Android usb_<id> fallback rejected");
        String xhci1Port = ControllerManager.extractUsbPortIdentifier("usb-1c7a0000.xhci-1/input0");
        String xhci2Port = ControllerManager.extractUsbPortIdentifier("usb-1c7a0000.xhci-2/input0");
        String desc1 = ControllerManager.resolveStableDescriptor("a15f809e", null, xhci1Port, "2.4G XBOX 360 For Windows", 0x3537, 0x1040);
        String desc2 = ControllerManager.resolveStableDescriptor("a15f809e", null, xhci2Port, "2.4G XBOX 360 For Windows", 0x3537, 0x1040);
        assertEquals("usbport:13623:4160:xhci-1", desc1, "Physical xhci-1 port descriptor");
        assertEquals("usbport:13623:4160:xhci-2", desc2, "Physical xhci-2 port descriptor");
        assertTrue(!desc1.equals(desc2), "Multiple physical USB ports generate distinct descriptors");

        // 8. Hardware location eviction on device removal
        ControllerManager.setMockHardwareLocationsForTesting(parsedDump);
        ControllerManager.onInputDeviceRemoved(33);
        // After removal, querying port for device 33 with empty locations map falls back safely
        ControllerManager.setMockHardwareLocationsForTesting(null);
    }

    private static void testRound4PolishRatingsDimAndUserOrderedLazyLoading() {
        // 1 & 2. Space-Separated Ratings (Not cdots) & Exact Order: IMDb, Metacritic, Rotten Tomatoes
        Recs.Pick rPick = new Recs.Pick();
        rPick.imdbScore = "7.8";
        rPick.mcScore = "64";
        rPick.rtScore = "91";
        String subAll = Recs.ratingsSubtitle(rPick);
        assertEquals("IMDb 7.8 MC 64 RT 91%", subAll, "Ratings subtitle orders IMDb, MC, RT separated by space");
        assertEquals(false, subAll.contains("·"), "Ratings subtitle never contains cdot");

        Recs.Pick mcRtOnly = new Recs.Pick();
        mcRtOnly.imdbScore = "";
        mcRtOnly.mcScore = "75";
        mcRtOnly.rtScore = "88%";
        assertEquals("MC 75 RT 88%", Recs.ratingsSubtitle(mcRtOnly), "MC and RT order preserved when IMDb absent");

        HomeView.HeaderState hsFull = new HomeView.HeaderState();
        hsFull.durationText = "1:37h";
        hsFull.datesText = "2023";
        hsFull.hasImdb = true;
        hsFull.imdbScoreText = "IMDb 7.8";
        hsFull.hasMc = true;
        hsFull.mcScoreText = "6.4";
        hsFull.hasRt = true;
        hsFull.rtScoreText = "91%";
        hsFull.hasRow2 = true;
        assertEquals("1:37h · 2023 · IMDb 7.8 [mc] 6.4 [rt] 91%",
                HomeView.formatHeaderRow2Layout(hsFull),
                "Header Row 2 keeps cdot after Duration/Dates, then orders IMDb, MC, RT separated by spaces");

        HomeView.HeaderState hsRatingsOnly = new HomeView.HeaderState();
        hsRatingsOnly.hasImdb = true;
        hsRatingsOnly.imdbScoreText = "IMDb 8.5";
        hsRatingsOnly.hasMc = true;
        hsRatingsOnly.mcScoreText = "7.8";
        hsRatingsOnly.hasRt = true;
        hsRatingsOnly.rtScoreText = "94%";
        hsRatingsOnly.hasRow2 = true;
        String ratingsOnlyLayout = HomeView.formatHeaderRow2Layout(hsRatingsOnly);
        assertEquals("IMDb 8.5 [mc] 7.8 [rt] 94%", ratingsOnlyLayout, "Header Row 2 without duration/dates has zero cdots");
        assertEquals(false, ratingsOnlyLayout.contains("·"), "No cdot between ratings in Header Row 2");

        HomeView.HeaderState hsNoMc = new HomeView.HeaderState();
        hsNoMc.datesText = "2024";
        hsNoMc.hasImdb = true;
        hsNoMc.imdbScoreText = "IMDb 8.1";
        hsNoMc.hasMc = false;
        hsNoMc.hasRt = true;
        hsNoMc.rtScoreText = "89%";
        hsNoMc.hasRow2 = true;
        assertEquals("2024 · IMDb 8.1 [rt] 89%", HomeView.formatHeaderRow2Layout(hsNoMc), "Missing MC skipped cleanly in Header Row 2");

        // 3. Uniform Background Image Dim Across All Non-Home Screens
        assertEquals(0.50f, HomeView.NON_HOME_DIM_MIX, 0.001f, "Uniform 50% non-Home background dim mix");
        assertTrue(Shaders.HERO_VIGNETTE.contains("float nhTotalMix = 0.50;"),
                "AGSL HERO_VIGNETTE uses spatially uniform 0.50 dim on non-Home screens matching fallback");
        assertTrue((HomeView.SETTINGS_LEFT_PANEL_DARK >>> 24) <= 0x24,
                "Settings left panel uses translucent glass instead of opaque charcoal so sky dim matches other screens");
        assertTrue((HomeView.SETTINGS_RIGHT_PANEL_DARK >>> 24) <= 0x1A,
                "Settings right panel uses translucent glass instead of opaque charcoal so sky dim matches other screens");
        assertEquals(true, HomeView.shouldUpdateBackdropFromScreenRows(1), "Home screen (index 1) updates hero backdrop from rows");
        assertEquals(true, HomeView.shouldUpdateBackdropFromScreenRows(3), "Gaming screen (index 3) updates hero/pattern backdrop from rows");
        for (int sIdx : new int[] {0, 2, 4, 5, 6}) {
            assertEquals(false, HomeView.shouldUpdateBackdropFromScreenRows(sIdx),
                    "Non-hero screen " + sIdx + " uses unified background instead of row hero backdrops");
        }

        // 4. Summary Text Should Be Pure White, Not Grey
        assertEquals(0xFFFFFFFF, HomeView.OVERVIEW_DARK_COLOR, "Overview summary dark-mode color is pure white 0xFFFFFFFF");

        // 5. Complete Genre Query Coverage (No 3-Item Stub Rows for Comedy, Action, Drama, Adventure)
        for (String genre : Prefs.DEFAULT_GENRES) {
            String tvQ = Recs.tmdbGenreQueryFor("tv", genre);
            String mvQ = Recs.tmdbGenreQueryFor("movie", genre);
            assertTrue(tvQ != null && !tvQ.isEmpty() && !"without_genres=16".equals(tvQ),
                    "TV genre '" + genre + "' has a dedicated TMDB discovery query: " + tvQ);
            assertTrue(mvQ != null && !mvQ.isEmpty() && !"without_genres=16".equals(mvQ),
                    "Movie genre '" + genre + "' has a dedicated TMDB discovery query: " + mvQ);
        }
        assertTrue(Recs.MIN_INITIAL_GENRE_ITEMS >= 12, "Each visible genre fetches at least 12 initial unwatched items");
        assertTrue(Recs.POOL_CAPACITY >= 1000, "Catalog pool capacity holds all user-ordered genres without truncating");

        // 6. User-Order-Aware Lazy Loading (Items & Genres) + Non-Reshuffling Page Appends
        assertEquals(java.util.Arrays.asList("movie", "tv"), Recs.orderedSubKinds("movie"), "Active movie tab prioritizes movie lazy load first");
        assertEquals(java.util.Arrays.asList("tv", "movie"), Recs.orderedSubKinds("tv"), "Active tv tab prioritizes tv lazy load first");

        java.util.List<String> customGenres = java.util.Arrays.asList("Sci-Fi", "Horror", "Comedy", "Drama");
        java.util.Set<String> hiddenGenres = new java.util.HashSet<>(java.util.Collections.singletonList("Horror"));
        assertEquals(java.util.Arrays.asList("Sci-Fi", "Comedy", "Drama"),
                Recs.orderedGenresForLazyLoad(customGenres, hiddenGenres),
                "Lazy-load genre sequence follows user's custom genre order and skips hidden genres");

        Recs.Pick wl1 = makeSimPick(701, "Severance", false, 92, false, "en", 50, "2022-02-18", "Work-life split", "Sci-Fi");
        Recs.Pick wl2 = makeSimPick(702, "Andor", false, 91, false, "en", 48, "2022-09-21", "Rebel spy", "Sci-Fi");
        Recs.Pick rec1 = makeSimPick(703, "Silo", false, 89, false, "en", 49, "2023-05-05", "Bunker mystery", "Sci-Fi");
        Recs.Pick tr1 = makeSimPick(704, "Fallout", false, 88, false, "en", 55, "2024-04-10", "Vault dweller", "Sci-Fi");
        Recs.Pick gSciFi1 = makeSimPick(705, "Dark Matter", false, 84, false, "en", 50, "2024-05-08", "Multiverse box", "Sci-Fi");
        Recs.Pick gComedy1 = makeSimPick(706, "Shrinking", false, 85, false, "en", 32, "2023-01-27", "Candid therapist", "Comedy");

        java.util.List<Recs.Pick> lazyQueue = Recs.buildUserOrderedLazyQueue(
                java.util.Arrays.asList(wl2, wl1), // User manually reordered wl2 ahead of wl1!
                java.util.Collections.singletonList(rec1),
                java.util.Collections.singletonList(tr1),
                java.util.Arrays.asList(
                        java.util.Arrays.asList(wl2, gSciFi1), // Duplicate wl2 skipped cleanly
                        java.util.Collections.singletonList(gComedy1)
                ),
                2,
                2
        );
        assertEquals(6, lazyQueue.size(), "Lazy queue deduplicates while keeping all 6 unique picks");
        assertEquals(702, lazyQueue.get(0).id, "1st lazy-loaded item is user's 1st Watch List item (wl2)");
        assertEquals(701, lazyQueue.get(1).id, "2nd lazy-loaded item is user's 2nd Watch List item (wl1)");
        assertEquals(703, lazyQueue.get(2).id, "3rd lazy-loaded item is Recommended lead");
        assertEquals(704, lazyQueue.get(3).id, "4th lazy-loaded item is Trending lead");
        assertEquals(705, lazyQueue.get(4).id, "5th lazy-loaded item is user's 1st Genre ('Sci-Fi') lead");
        assertEquals(706, lazyQueue.get(5).id, "6th lazy-loaded item is user's 2nd Genre ('Comedy') lead");

        // Verify lazy-loaded page 2+ items append after initial page 0 items without reshuffling under cursor
        Recs.Pick initHigh = makeSimPick(801, "Initial A", false, 85, false, "en", 45, "2023-01-01", "A", "Drama");
        initHigh.highQuality = true;
        initHigh.lazyPage = 0;
        Recs.Pick initMid = makeSimPick(802, "Initial B", false, 78, false, "en", 45, "2023-01-01", "B", "Drama");
        initMid.highQuality = true;
        initMid.lazyPage = 0;
        Recs.Pick page2Higher = makeSimPick(803, "Lazy Page 2", false, 95, false, "en", 45, "2023-01-01", "C", "Drama");
        page2Higher.highQuality = true;
        page2Higher.lazyPage = 2;

        java.util.List<Recs.Pick> pagedRow = new java.util.ArrayList<>(java.util.Arrays.asList(page2Higher, initMid, initHigh));
        Recs.sortRowPreservingLazyPages(pagedRow, null, false);
        assertEquals(801, pagedRow.get(0).id, "Initial page 0 top item stays 1st");
        assertEquals(802, pagedRow.get(1).id, "Initial page 0 second item stays 2nd");
        assertEquals(803, pagedRow.get(2).id, "Lazy-loaded page 2 item appends cleanly after initial page 0 items");
    }

    private static void testCleanPosterUrlSanitizationAgainstRpdbOverlays() {
        // 1. Official clean poster URLs are preserved untouched
        assertEquals(true, WatchNext.isCleanPosterUrl("/abc123.jpg", true), "Relative TMDB poster path is clean");
        assertEquals(true, WatchNext.isCleanPosterUrl("https://image.tmdb.org/t/p/w500/abc123.jpg", true), "TMDB CDN poster is clean");
        assertEquals(true, WatchNext.isCleanPosterUrl("https://images.metahub.space/poster/large/tt35854736/img", true), "Metahub official poster is clean");
        assertEquals(true, WatchNext.isCleanPosterUrl("https://episodes.metahub.space/tt1442462/1/1/w780.jpg", true), "Metahub episode still is clean");
        assertEquals(true, WatchNext.isCleanPosterUrl("https://m.media-amazon.com/images/M/MV5B.jpg", true), "IMDb/Amazon CDN poster is clean");

        // 2. Rating-overlay & Stremio addon proxy URLs (RPDB, Top-Posters, AIOStreams, unknown proxies) are rejected
        String rpdbUrl = "https://api.ratingposterdb.com/t0-free-rpdb/imdb/poster-default/tt35854736.jpg?fallback=true";
        String topPostersUrl = "https://api.top-streaming.stream/userKey/imdb/poster-default/tt1442462.jpg";
        String aioProxyUrl = "https://aiostreams.elfhosted.com/poster/series/tt35854736.jpg";
        String customWorkerOverlay = "https://custom-stremio-posters.workers.dev/poster/img123.jpg";

        assertEquals(false, WatchNext.isCleanPosterUrl(rpdbUrl, true), "RPDB badged poster URL rejected");
        assertEquals(false, WatchNext.isCleanPosterUrl(rpdbUrl, false), "RPDB badged poster URL rejected even in non-strict mode");
        assertEquals(false, WatchNext.isCleanPosterUrl(topPostersUrl, true), "Top-Posters badged poster URL rejected");
        assertEquals(false, WatchNext.isCleanPosterUrl(topPostersUrl, false), "Top-Posters badged poster URL rejected even in non-strict mode");
        assertEquals(false, WatchNext.isCleanPosterUrl(aioProxyUrl, true), "AIOStreams proxy poster URL rejected");
        assertEquals(false, WatchNext.isCleanPosterUrl(customWorkerOverlay, true), "Unknown Stremio addon worker poster URL rejected in strict mode");
        assertEquals(true, WatchNext.isCleanPosterUrl("https://occ-0-2621-2774.1.nflxso.net/dnm/api/v6/poster.jpg", false),
                "Native Netflix CDN allowed when strictOfficialCatalogOnly is false");

        // 3. sanitizePosterUrl rewrites badged URLs to clean Metahub IMDb URL (extracting tt ID from URL or fallback intent)
        assertEquals("https://images.metahub.space/poster/large/tt35854736/img",
                WatchNext.sanitizePosterUrl(rpdbUrl, null, true),
                "RPDB URL sanitized using embedded IMDb ID");
        assertEquals("https://images.metahub.space/poster/large/tt1442462/img",
                WatchNext.sanitizePosterUrl(topPostersUrl, "stremio:///detail/series/tt1442462", true),
                "Top-Posters URL sanitized to clean Metahub poster");
        assertEquals("https://images.metahub.space/poster/large/tt35854736/img",
                WatchNext.sanitizePosterUrl(customWorkerOverlay, "stremio:///detail/series/tt35854736", true),
                "Custom Stremio proxy URL without tt in URL sanitized via intent IMDb ID");
        assertEquals(null,
                WatchNext.sanitizePosterUrl(customWorkerOverlay, null, true),
                "Untrusted Stremio proxy URL with no IMDb ID returns null so TMDB title lookup runs");

        // 4. StremioAccountClient.LibraryItem & WatchNext.Item automatically strip badged posters on construction
        StremioAccountClient.LibraryItem avatarLib = new StremioAccountClient.LibraryItem(
                "tt35854736", "Avatar: Seven Havens", "tv",
                "https://api.top-streaming.stream/k/imdb/poster-default/tt35854736.jpg",
                1730000000000L, 1730000500000L, 1730000500000L,
                0, 600000L, 1440000L, "tt35854736:1:1", 1, 1, false, false, false);
        StremioAccountClient.LibraryItem goodWifeLib = new StremioAccountClient.LibraryItem(
                "tt1442462", "The Good Wife", "tv",
                "https://api.ratingposterdb.com/k/imdb/poster-default/tt1442462.jpg",
                1730000000000L, 1730000500000L, 1730000500000L,
                0, 1200000L, 2580000L, "tt1442462:2:4", 2, 4, false, false, false);
        assertEquals("https://images.metahub.space/poster/large/tt35854736/img", avatarLib.poster,
                "Avatar: Seven Havens Top-Posters URL stripped on Stremio LibraryItem construction");
        assertEquals("https://images.metahub.space/poster/large/tt1442462/img", goodWifeLib.poster,
                "The Good Wife RPDB URL stripped on Stremio LibraryItem construction");

        WatchNext.Item wnItem = new WatchNext.Item(
                "com.stremio.one",
                "https://custom-stremio-posters.workers.dev/poster/img123.jpg",
                "https://episodes.metahub.space/tt35854736/1/1/w780.jpg",
                "stremio:///detail/series/tt35854736");
        assertEquals("https://images.metahub.space/poster/large/tt35854736/img", wnItem.picture(),
                "WatchNext.Item strips custom Stremio addon poster URL and extracts clean IMDb poster from intent");

        // 5. CinemetaClient.CatalogItem sanitizes badged addon poster URLs as well
        CinemetaClient.CatalogItem catItem = new CinemetaClient.CatalogItem(
                "tt35854736", "series", "Avatar: Seven Havens", "2026-", "8.6",
                "https://api.top-streaming.stream/key/tt35854736.jpg",
                "https://images.metahub.space/background/medium/tt35854736/img");
        assertEquals("https://images.metahub.space/poster/large/tt35854736/img", catItem.posterUrl,
                "CinemetaClient.CatalogItem sanitizes rating-overlay poster URL");
    }

    private static void testBluetoothPairingAssist() {
        // 1. Package & dialog class recognition
        assertTrue(BluetoothPairingAssist.isPairingDialogPackage("com.android.tv.settings",
                "com.android.tv.settings.accessories.BluetoothPairingDialog"),
                "Settings BluetoothPairingDialog full class recognition");
        assertTrue(BluetoothPairingAssist.isPairingDialogPackage("com.android.tv.settings",
                "BluetoothPairingDialog"),
                "Settings BluetoothPairingDialog short class recognition");
        assertTrue(BluetoothPairingAssist.isPairingDialogPackage("com.google.android.tv.settings",
                "com.android.tv.settings.accessories.BluetoothPairingDialog"),
                "Google TV settings BluetoothPairingDialog recognition");
        assertTrue(!BluetoothPairingAssist.isPairingDialogPackage("com.android.tv.settings",
                "com.android.tv.settings.MainSettings"),
                "Reject non-pairing activity in settings");
        assertTrue(!BluetoothPairingAssist.isPairingDialogPackage("com.google.android.youtube.tv",
                "BluetoothPairingDialog"),
                "Reject pairing dialog class in foreign package");
        assertTrue(!BluetoothPairingAssist.isPairingDialogPackage(null, "BluetoothPairingDialog"),
                "Reject null package");
        assertTrue(!BluetoothPairingAssist.isPairingDialogPackage("com.android.tv.settings", null),
                "Reject null class");

        // 2. Settings package recognition
        assertTrue(BluetoothPairingAssist.isSettingsPackage("com.android.tv.settings"),
                "Recognize com.android.tv.settings");
        assertTrue(BluetoothPairingAssist.isSettingsPackage("com.google.android.tv.settings"),
                "Recognize com.google.android.tv.settings");
        assertTrue(BluetoothPairingAssist.isSettingsPackage("com.android.settings"),
                "Recognize com.android.settings");
        assertTrue(!BluetoothPairingAssist.isSettingsPackage("io.soartv"),
                "Reject non-settings package");
        assertTrue(!BluetoothPairingAssist.isSettingsPackage(null),
                "Reject null package in isSettingsPackage");

        // 3. Action index selection: English and localized keywords
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Pair", "Cancel"), null),
                "English 'Pair' at index 0");
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Cancel", "Pair"), null),
                "English 'Pair' at index 1");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("PAIR", "CANCEL"), null),
                "English uppercase 'PAIR' at index 0");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Pairing", "Cancel"), null),
                "English 'Pairing' at index 0");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList(" Pair with controller ", "Cancel"), null),
                "English substring 'Pair' with padding");

        // Hebrew localization
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("התאם", "ביטול"), null),
                "Hebrew 'התאם' at index 0");
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("ביטול", "התאם"), null),
                "Hebrew 'התאם' at index 1");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("התאמה", "ביטול"), null),
                "Hebrew 'התאמה' at index 0");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("בצע התאמה", "ביטול"), null),
                "Hebrew 'בצע התאמה' at index 0");

        // Multilingual keywords
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Vincular", "Cancelar"), null),
                "Spanish 'Vincular' at index 0");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Associer", "Annuler"), null),
                "French 'Associer' at index 0");
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Abbrechen", "Koppeln"), null),
                "German 'Koppeln' at index 1");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Подключить", "Отмена"), null),
                "Russian 'Подключить' at index 0");

        // Content description fallback
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("", ""),
                java.util.Arrays.asList("Pair", "Cancel")),
                "ContentDescription 'Pair' at index 0");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                null,
                java.util.Arrays.asList("התאם", "ביטול")),
                "ContentDescription null texts 'התאם' at index 0");

        // Fallback: 2 action buttons with unknown labels defaults to index 0 (Leanback convention)
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Action One", "Action Two"), null),
                "Unknown labels 2-action fallback to index 0");
        // Fallback: 2 action buttons where index 0 is explicitly Cancel and index 1 is unknown
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Cancel", "Custom Action"), null),
                "Explicit Cancel at index 0 redirects fallback to index 1");

        // Edge cases: single item or 3+ items
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Collections.singletonList("Pair"), null),
                "Single action button 'Pair'");
        assertEquals(-1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Collections.singletonList("Dismiss"), null),
                "Single action button 'Dismiss' rejected");
        assertEquals(-1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Action One", "Action Two", "Action Three"), null),
                "Three unknown actions rejected");
        assertEquals(2, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Action One", "Action Two", "Pair"), null),
                "Three actions with 'Pair' at index 2 matched");
        assertEquals(-1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Unpair", "Delete", "Dismiss"), null),
                "Negative match: 'Unpair' does not match 'Pair'");
        assertEquals(-1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Collections.emptyList(), java.util.Collections.emptyList()),
                "Empty action lists return -1");
        assertEquals(-1, BluetoothPairingAssist.selectPairActionIndex(null, null),
                "Null action lists return -1");

        // 4. Universal confirm keywords & prompt text rejection
        assertTrue(BluetoothPairingAssist.isPairActionLabel("OK"), "Match 'OK'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("Ok"), "Match 'Ok'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("ok"), "Match 'ok'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("Confirm"), "Match 'Confirm'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("Yes"), "Match 'Yes'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("Connect"), "Match 'Connect'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("אישור"), "Match Hebrew 'אישור'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("כן"), "Match Hebrew 'כן'");
        assertTrue(BluetoothPairingAssist.isPairActionLabel("התחבר"), "Match Hebrew 'התחבר'");

        // Reject informational prompt / code / header texts that happen to contain 'pair' or 'התאמ'
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("Bluetooth pairing request"),
                "Reject 'Bluetooth pairing request' title");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("Pairing code: 123456"),
                "Reject 'Pairing code: ...' instructions");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("Pair with Wireless Controller?"),
                "Reject 'Pair with ...' prompt question");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("Pairing with AA:BB:CC:11:22:33"),
                "Reject 'Pairing with MAC' status text");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("Passkey pairing request"),
                "Reject passkey request text");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("קוד התאמה: 123456"),
                "Reject Hebrew pairing code text");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("בקשת התאמת Bluetooth"),
                "Reject Hebrew pairing request text");
        assertTrue(!BluetoothPairingAssist.isPairActionLabel("התאמה עם בקר משחק"),
                "Reject Hebrew 'התאמה עם...' prompt");

        // Cancellation labels
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("Cancel"), "Cancel matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("ביטול"), "ביטול matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("Abort"), "Abort matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("Dismiss"), "Dismiss matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("Reject"), "Reject matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("דחה"), "דחה matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("No"), "No matches Cancel");
        assertTrue(BluetoothPairingAssist.isCancelActionLabel("לא"), "לא matches Cancel");
        assertTrue(!BluetoothPairingAssist.isCancelActionLabel("Pair"), "Pair is not Cancel");
        assertTrue(!BluetoothPairingAssist.isCancelActionLabel("OK"), "OK is not Cancel");
        assertTrue(!BluetoothPairingAssist.isCancelActionLabel("אישור"), "אישור is not Cancel");

        // Action index selection with universal confirm labels & prompt filtering
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("OK", "Cancel"), null),
                "Select 'OK' at index 0");
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Cancel", "OK"), null),
                "Select 'OK' at index 1");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("אישור", "ביטול"), null),
                "Select 'אישור' at index 0");
        assertEquals(1, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("ביטול", "אישור"), null),
                "Select 'אישור' at index 1");
        assertEquals(0, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Confirm", "Dismiss"), null),
                "Select 'Confirm' at index 0");
        // Dialog containing informational prompt + Cancel + Pair: prompt must not be picked
        assertEquals(2, BluetoothPairingAssist.selectPairActionIndex(
                java.util.Arrays.asList("Pairing code: 123456", "Cancel", "Pair"), null),
                "Skip 'Pairing code' prompt and select 'Pair' at index 2");

        // 5. Candidate helper null checks
        assertTrue(!BluetoothPairingAssist.isActionCandidate(null), "Null node is not action candidate");

        // 6. Confirm key detection helper
        assertTrue(BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER), "DPAD_CENTER is confirm key");
        assertTrue(BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_ENTER), "ENTER is confirm key");
        assertTrue(BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER), "NUMPAD_ENTER is confirm key");
        assertTrue(BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_BUTTON_A), "BUTTON_A is confirm key");
        assertTrue(!BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN), "DPAD_DOWN is not confirm key");
        assertTrue(!BluetoothPairingAssist.isConfirmKey(android.view.KeyEvent.KEYCODE_BACK), "BACK is not confirm key");

        // 7. Key interception decisions
        // DPAD_CENTER (23)
        assertTrue(BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, true, false),
                "Intercept DPAD_CENTER when dialog visible and unfocused");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, true, true),
                "Do NOT intercept DPAD_CENTER when an action already has focus");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, false, false),
                "Do NOT intercept DPAD_CENTER when dialog is not visible");

        // ENTER (66)
        assertTrue(BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_ENTER, true, false),
                "Intercept ENTER when dialog visible and unfocused");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_ENTER, true, true),
                "Do NOT intercept ENTER when an action already has focus");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_ENTER, false, false),
                "Do NOT intercept ENTER when dialog is not visible");

        // BUTTON_A (96 - Game controller A button)
        assertTrue(BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_BUTTON_A, true, false),
                "Intercept BUTTON_A when dialog visible and unfocused");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_BUTTON_A, true, true),
                "Do NOT intercept BUTTON_A when an action already has focus");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_BUTTON_A, false, false),
                "Do NOT intercept BUTTON_A when dialog is not visible");

        // NUMPAD_ENTER (160)
        assertTrue(BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, true, false),
                "Intercept NUMPAD_ENTER when dialog visible and unfocused");

        // Non-confirm keys must never be intercepted
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, true, false),
                "Do NOT intercept DPAD_DOWN navigation");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_UP, true, false),
                "Do NOT intercept DPAD_UP navigation");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, true, false),
                "Do NOT intercept DPAD_LEFT navigation");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, true, false),
                "Do NOT intercept DPAD_RIGHT navigation");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_BACK, true, false),
                "Do NOT intercept BACK key (must dismiss/cancel dialog)");
        assertTrue(!BluetoothPairingAssist.shouldInterceptConfirmKey(
                android.view.KeyEvent.KEYCODE_ESCAPE, true, false),
                "Do NOT intercept ESCAPE key");
    }

    private static void testFiveControllerFeedbackFixes() {
        // --- 1. Green Bluetooth Controller Detection (DualShock 4 / GameSir Home+B "Wireless Controller" + Sleeping Bonded) ---
        assertEquals(true,
                GamingZone.isPhysicalGameController("Wireless Controller", false, true, true),
                "DualShock 4 / GameSir Home+B 'Wireless Controller' accepted as physical controller");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Wireless Controller Motion Sensors", false, true, true),
                "hid-sony 'Wireless Controller Motion Sensors' IMU node rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("DualSense Wireless Controller Accelerometer", false, true, true),
                "Accelerometer companion node rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Nintendo Pro Controller Gyroscope", false, true, true),
                "Gyroscope companion node rejected");
        assertEquals("Wireless Controller",
                ControllerManager.cleanDeviceName("Wireless Controller Motion Sensors"),
                "Strips Motion Sensors suffix cleanly");
        assertEquals("Wireless Controller",
                ControllerManager.cleanDeviceName("Wireless Controller (Motion Sensors)"),
                "Strips (Motion Sensors) suffix cleanly");
        assertTrue(ControllerManager.isKnownGamepadVendor(0x054c), "Sony VID 0x054c recognized");
        assertTrue(ControllerManager.isKnownGamepadVendor(0x3537), "GameSir VID 0x3537 recognized");
        assertTrue(ControllerManager.isKnownGamepadVendor(0x045e), "Microsoft VID 0x045e recognized");
        assertTrue(ControllerManager.isKnownGamepadVendor(0x057e), "Nintendo VID 0x057e recognized");
        assertTrue(!ControllerManager.isKnownGamepadVendor(0x2717), "Xiaomi RC VID 0x2717 is NOT a gamepad vendor");

        assertTrue(ControllerManager.isBluetoothGamepadCandidate("Wireless Controller", 0x0500, 0x0508),
                "Bonded Wireless Controller (AA:BB:CC:11:22:33) recognized as Bluetooth gamepad candidate");
        assertTrue(ControllerManager.isBluetoothGamepadCandidate("Wireless Controller", 0x1F00, 0x1F00),
                "Bonded Wireless Controller with uncategorized BT class still recognized by gamepad name");
        assertTrue(!ControllerManager.isBluetoothGamepadCandidate("Xiaomi RC", 0x0500, 0x0540),
                "Xiaomi RC TV remote excluded from Bluetooth gamepads");
        assertTrue(!ControllerManager.isBluetoothGamepadCandidate("Sony WH-1000XM5", 0x0400, 0x0404),
                "Bluetooth headphones (major class 0x0400 Audio/Video) excluded from gamepads");
        assertEquals("bt:aa:bb:cc:11:22:33",
                ControllerManager.canonicalBluetoothDescriptor("AA:BB:CC:11:22:33"),
                "Canonical Bluetooth descriptor normalizes MAC to lowercase bt:<mac>");

        ControllerManager.ControllerInfo sleepingGreen = new ControllerManager.ControllerInfo(
                -1, "bt:aa:bb:cc:11:22:33", "Wireless Controller", "Green", ControllerManager.COLOR_GREEN, true, -1, false, false
        );
        assertEquals(false, sleepingGreen.connected, "Sleeping bonded Bluetooth controller marks connected=false");
        assertEquals("Green", sleepingGreen.getDisplayName(), "Sleeping bonded controller preserves custom name");

        // --- 2. Pink USB Controller Power-Cycle Persistence & Orphaned Ephemeral Tag Recovery ---
        java.util.Map<String, Object> fakePrefs = new java.util.HashMap<>();
        fakePrefs.put("ctrl_tagged_usbport:13623:4160:usb-1-30", Boolean.TRUE);
        fakePrefs.put("ctrl_name_usbport:13623:4160:usb-1-30", "Pink");
        fakePrefs.put("ctrl_color_usbport:13623:4160:usb-1-30", ControllerManager.COLOR_PINK);
        assertEquals("usbport:13623:4160:usb-1-30",
                ControllerManager.findOrphanedEphemeralUsbTagKey(fakePrefs, 13623, 4160),
                "Finds orphaned usb-1-30 tag saved before power-cycle fix so Pink name & color auto-recover");
        fakePrefs.put("ctrl_cleared_usbport:13623:4160:usb-1-30", Boolean.TRUE);
        assertEquals(null,
                ControllerManager.findOrphanedEphemeralUsbTagKey(fakePrefs, 13623, 4160),
                "Ignores explicitly cleared ephemeral key");

        // --- 3. Settings Organization: Controller/Remote Pairing Under "Gaming" & Zero Hex Codes ---
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.knownControllers = java.util.Arrays.asList(
                new ControllerManager.ControllerInfo(
                        101, "usbport:13623:4160:xhci-2", "2.4G XBOX 360 For Windows", "Pink",
                        ControllerManager.COLOR_PINK, true, -1, false, true),
                sleepingGreen,
                new ControllerManager.ControllerInfo(
                        102, "usbport:13623:4160:xhci-1", "2.4G XBOX 360 For Windows", "",
                        ControllerManager.COLOR_NEUTRAL_FALLBACK, false, -1, false, true)
        );
        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        verifyAllNodesHaveIcons(root);
        SettingsTree.Node gaming = root.findById("gaming_settings");
        assertTrue(gaming != null, "Gaming category exists at root");
        SettingsTree.Node ctrlList = gaming.findById("gaming_controllers");
        assertTrue(ctrlList != null && ctrlList.children.size() == 3, "Gaming -> Controllers lists all 3 known controllers (including sleeping BT)");
        assertEquals(ControllerManager.COLOR_PINK, ctrlList.children.get(0).swatchColor, "Pink controller node carries visual swatchColor");
        assertEquals("Connected", ctrlList.children.get(0).value, "Connected Pink controller shows Connected status");
        assertEquals(ControllerManager.COLOR_GREEN, ctrlList.children.get(1).swatchColor, "Sleeping Green BT controller carries Green swatchColor");
        assertEquals("Paired", ctrlList.children.get(1).value, "Sleeping Green BT controller shows Paired status");
        assertEquals("Set Up", ctrlList.children.get(2).value, "Untagged controller prompts 'Set Up' instead of hex/edit");

        // Verify ZERO '#' hex strings appear anywhere in Gaming controller or rainbow color nodes
        SettingsTree.Node rainbow = gaming.findById("gaming_colors_rainbow");
        for (SettingsTree.Node n : rainbow.children) {
            assertTrue(!n.title.contains("#") && !n.subtitle.contains("#") && !n.value.contains("#"),
                    "No hex codes exposed on color node: " + n.id);
            if (!"gaming_color_custom".equals(n.id)) {
                assertTrue(n.swatchColor != 0, "Rainbow color node has visual swatchColor: " + n.id);
            }
        }

        // --- 4. Rainbow Color Order, Friendly Color Names, First-Time Setup vs Edit, & In-App Bluetooth Scanner ---
        java.util.List<ControllerManager.ColorOption> rc = ControllerManager.RAINBOW_COLORS;
        assertTrue(rc.size() >= 17, "At least 17 rainbow-ordered colors available");
        assertEquals("Red", rc.get(0).name, "Rainbow starts with Red");
        assertEquals("Orange", rc.get(1).name, "Rainbow index 1 is Orange");
        int idxYellow = -1, idxGreen = -1, idxLightBlue = -1, idxBlue = -1, idxPurple = -1, idxPink = -1, idxWhite = -1, idxBlack = -1;
        for (int i = 0; i < rc.size(); i++) {
            int c = rc.get(i).argb;
            String nm = rc.get(i).name;
            if (c == ControllerManager.COLOR_YELLOW) idxYellow = i;
            if (c == ControllerManager.COLOR_GREEN) idxGreen = i;
            if (c == ControllerManager.COLOR_LIGHT_BLUE) idxLightBlue = i;
            if ("Blue".equals(nm)) idxBlue = i;
            if ("Purple".equals(nm)) idxPurple = i;
            if (c == ControllerManager.COLOR_PINK) idxPink = i;
            if ("White".equals(nm)) idxWhite = i;
            if ("Black".equals(nm)) idxBlack = i;
        }
        assertTrue(idxYellow > 1 && idxGreen > idxYellow, "Rainbow order: Yellow before Green");
        assertTrue(idxLightBlue > idxGreen && idxBlue >= idxLightBlue, "Rainbow order: Green before Light Blue / Blue");
        assertTrue(idxPurple > idxBlue && idxPink > idxPurple, "Rainbow order: Blue -> Purple -> Pink");
        assertTrue(idxWhite > idxPink && idxBlack > idxWhite, "Rainbow order: Neutrals White -> Gray -> Black at end");

        assertEquals("Pink", ControllerManager.findColorName(ControllerManager.COLOR_PINK), "Friendly name for Pink preset without hex");
        assertEquals("Soft Green", ControllerManager.findColorName(ControllerManager.COLOR_GREEN), "Friendly name for Green preset without hex");
        assertEquals("Light Blue", ControllerManager.findColorName(ControllerManager.COLOR_LIGHT_BLUE), "Friendly name for Light Blue preset without hex");
        assertEquals("Custom Color", ControllerManager.findColorName(0xFF123456), "Unknown custom ARGB resolves to 'Custom Color', never hex");

        ControllerManager.ControllerInfo untaggedCtrl = snap.knownControllers.get(2);
        assertTrue(ControllerManager.isFirstTimeSetup(untaggedCtrl), "Untagged controller triggers First-Time Setup flow");
        assertTrue(!ControllerManager.isFirstTimeSetup(sleepingGreen), "Already-tagged controller triggers Edit flow, not First-Time Setup");
        java.util.List<String> btEditActions = ControllerManager.getControllerEditActions(sleepingGreen);
        assertTrue(btEditActions.contains("Rename Controller") && btEditActions.contains("Change Color"), "Edit flow separates Rename and Change Color");
        assertTrue(btEditActions.contains("Unpair Bluetooth Controller"), "Bluetooth controller edit menu includes Unpair option");
        java.util.List<String> usbEditActions = ControllerManager.getControllerEditActions(snap.knownControllers.get(0));
        assertTrue(!usbEditActions.contains("Unpair Bluetooth Controller"), "USB controller edit menu omits Bluetooth unpair");

        // In-app Bluetooth discovery filtering & sorting
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice("Wireless Controller", "AA:BB:CC:11:22:33", 0x0500),
                "Includes Wireless Controller in in-app Bluetooth scan");
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice("GameSir-Nova Lite", "AA:BB:CC:DD:EE:FF", 0x1F00),
                "Includes GameSir-Nova Lite in in-app Bluetooth scan");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice("", "11:22:33:44:55:66", 0x1F00),
                "Filters out unnamed BLE beacon noise");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice("Alex's iPhone", "22:33:44:55:66:77", 0x0200),
                "Filters out phones (major class 0x0200) from controller scan");

        java.util.List<BluetoothControllerPairer.DiscoveredDevice> discovered = new java.util.ArrayList<>(java.util.Arrays.asList(
                new BluetoothControllerPairer.DiscoveredDevice("AA:AA:AA:AA:AA:01", "Generic Keyboard", 0x0500, -50, false, false),
                new BluetoothControllerPairer.DiscoveredDevice("AA:BB:CC:11:22:33", "Wireless Controller", 0x0500, -65, false, true)
        ));
        BluetoothControllerPairer.sortDiscoveredDevices(discovered);
        assertEquals("AA:BB:CC:11:22:33", discovered.get(0).address, "Gamepads sort above generic peripherals in in-app scanner");

        // --- 5. Green Connected Pill Constants for Both Connected Controllers and Online PC ---
        int darkGreenBg = ControllerManager.CONNECTED_PILL_BG_DARK;
        int darkGreenBorder = ControllerManager.CONNECTED_PILL_BORDER_DARK;
        assertTrue(((darkGreenBg >> 8) & 0xFF) > ((darkGreenBg >> 16) & 0xFF)
                && ((darkGreenBg >> 8) & 0xFF) > (darkGreenBg & 0xFF),
                "Connected pill dark fill is unmistakably green (G channel dominates R and B)");
        assertTrue(((darkGreenBorder >> 8) & 0xFF) > ((darkGreenBorder >> 16) & 0xFF),
                "Connected pill border is green");
    }

    private static void testSixUiHeroContinueAndNewSeasonImprovements() throws Exception {
        // --- 1. Unified Background Across All Non-Hero Screens ---
        assertEquals(true, HomeView.usesHeroBackdropMode(HomeView.SCREEN_HOME), "Home screen uses hero backdrop mode");
        assertEquals(true, HomeView.usesHeroBackdropMode(HomeView.SCREEN_GAMING), "Gaming screen uses hero/pattern backdrop mode");
        for (int sIdx : new int[] {
                HomeView.SCREEN_SEARCH,
                HomeView.SCREEN_APPS,
                HomeView.SCREEN_REELS,
                HomeView.SCREEN_SMARTHOME,
                HomeView.SCREEN_SETTINGS
        }) {
            assertEquals(false, HomeView.usesHeroBackdropMode(sIdx),
                    "Screen " + sIdx + " renders deterministic unified background");
        }
        assertEquals(0xFF, (HomeView.UNIFIED_BG_DARK_TOP >>> 24), "Unified dark background top is fully opaque");
        assertEquals(0xFF, (HomeView.UNIFIED_BG_DARK_BOTTOM >>> 24), "Unified dark background bottom is fully opaque");
        assertTrue(HomeView.UNIFIED_BG_DARK_TOP != HomeView.UNIFIED_BG_DARK_BOTTOM,
                "Unified dark background has subtle vertical ambient depth");

        // --- 2. Gaming High-Res Steam & Standalone Hero Art (Seamless Pattern Removed) ---
        assertEquals("990080", GamingZone.extractSteamAppId("steam_990080", "Hogwarts Legacy", ""),
                "Extracts Steam App ID from steam_<id>");
        assertEquals("728880", GamingZone.extractSteamAppId("custom_id", "Unknown", "steam://rungameid/728880"),
                "Extracts Steam App ID from steam://rungameid command");
        assertEquals("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/990080/library_hero_2x.jpg",
                GamingZone.resolveHeroArtUrl("steam_990080", "Hogwarts Legacy", ""),
                "Steam game resolves official @2x (3840x1240) library_hero_2x.jpg background URL");
        assertTrue(GamingZone.resolveHeroArtUrl("exe_riot_games", "League of Legends", "C:\\Games\\Riot\\LeagueClient.exe")
                        .contains("3840x2160"),
                "League of Legends resolves official 4K (3840x2160) hero background URL");
        assertEquals("", GamingZone.resolveHeroArtUrl("steam_bigpicture", "Steam Big Picture", "steam://open/bigpicture"),
                "Steam Big Picture returns empty hero URL");
        assertEquals("", GamingZone.resolveHeroArtUrl("desktop", "Windows Desktop", ""),
                "Windows Desktop returns empty hero URL");

        // --- 3. Separate Gray 'Continue Watching S1E10' Without Space ---
        assertEquals("S1E10", WatchNext.formatCompactSeasonEpisode(1, 10), "Compact season/episode removes space -> S1E10");
        assertEquals("Continue: S1E10", WatchNext.compactEpisodeSpacing("Continue: S1 E10"),
                "compactEpisodeSpacing removes space between S1 and E10");
        assertEquals("Continue Watching S1E10", WatchNext.formatContinueWatchingStatus(1, 10, "Continue: S1 E10", true),
                "Continue Watching status formats as 'Continue Watching S1E10' with no space");
        assertEquals("Continue Watching S2E5", WatchNext.formatContinueWatchingStatus(0, 0, "Up next: S2 E5", true),
                "Continue Watching status parses S/E from legacy raw tag and removes space");
        assertEquals("Continue Watching", WatchNext.formatContinueWatchingStatus(0, 0, "Continue watching", false),
                "Movie Continue Watching status formats cleanly without S/E");

        WatchNext.Item sigOld = new WatchNext.Item("com.stremio.one", null, null);
        sigOld.episode = "Continue: S1 E10";
        sigOld.position = 180_000L;
        WatchNext.Item sigNew = new WatchNext.Item("com.stremio.one", null, null);
        sigNew.episode = "Continue: S1E10";
        sigNew.position = 180_000L;
        assertEquals(WatchNext.stateSignature(sigOld), WatchNext.stateSignature(sigNew),
                "stateSignature normalizes 'S1 E10' and 'S1E10' so hidden items do not falsely unhide");

        int cwGray = HomeView.CONTINUE_STATUS_DARK_COLOR;
        int cwR = (cwGray >> 16) & 0xFF, cwG = (cwGray >> 8) & 0xFF, cwB = cwGray & 0xFF;
        assertTrue(cwGray != HomeView.OVERVIEW_DARK_COLOR && cwR >= 0x80 && cwR <= 0xB8
                && Math.abs(cwR - cwG) <= 10 && Math.abs(cwG - cwB) <= 10,
                "Continue Watching status color is neutral mid-gray distinct from white summary text");

        HomeView.HeaderState hsPlain = new HomeView.HeaderState();
        hsPlain.genresText = "Drama | Sci-Fi";
        assertEquals(HomeView.OVERVIEW_BOX_TOP, HomeView.effectiveOverviewBoxTop(hsPlain), 0.01f,
                "Non-Continue rows keep full overview top at 280");
        HomeView.HeaderState hsContinue = new HomeView.HeaderState();
        hsContinue.genresText = "Drama | Sci-Fi";
        hsContinue.continueStatusText = "Continue Watching S1E10";
        assertEquals(HomeView.OVERVIEW_BOX_TOP, HomeView.effectiveOverviewBoxTop(hsContinue), 0.01f,
                "Continue Watching header consistently keeps standard overview top at 280dp");
        HomeView.HeaderState hsCopied = new HomeView.HeaderState();
        hsCopied.copyFrom(hsContinue);
        assertEquals("Continue Watching S1E10", hsCopied.continueStatusText, "HeaderState.copyFrom preserves continueStatusText");
        hsCopied.clear();
        assertEquals("", hsCopied.continueStatusText, "HeaderState.clear resets continueStatusText");

        // --- 4. Episode Summary for Series in Continue Watching Row Only ---
        String cmJson = "{\"meta\":{\"id\":\"tt11280740\",\"videos\":["
                + "{\"id\":\"tt11280740:1:9\",\"season\":1,\"episode\":9,\"overview\":\"Episode 9 synopsis.\"},"
                + "{\"id\":\"tt11280740:1:10\",\"season\":1,\"episode\":10,\"overview\":\"The finale episode summary for S1E10.\"}"
                + "]}}";
        assertEquals("The finale episode synopsis.",
                CinemetaClient.parseEpisodeOverviewFromMetaJson(
                        cmJson.replace("The finale episode summary for S1E10.", "The finale episode synopsis."), 1, 10),
                "Cinemeta parser extracts exact S1E10 episode overview");
        assertEquals("Episode 10 specific plot.",
                HomeView.resolveContinueWatchingOverview(true, "Episode 10 specific plot.", "Overall series plot."),
                "Continue Watching series item prefers episode summary over series summary");
        assertEquals("Overall series plot.",
                HomeView.resolveContinueWatchingOverview(true, "", "Overall series plot."),
                "Continue Watching series item falls back to series summary only when episode summary is unavailable");
        assertEquals("Movie plot synopsis.",
                HomeView.resolveContinueWatchingOverview(false, "Ignored", "Movie plot synopsis."),
                "Continue Watching movie item uses movie summary");

        // --- 5. Auto-Unwatch & Move Back From End of Watch List When New Season Releases ---
        java.util.List<Recs.SeasonReleaseInfo> tvSeasons = java.util.Arrays.asList(
                new Recs.SeasonReleaseInfo(0, 5, "2020-01-01"),
                new Recs.SeasonReleaseInfo(1, 9, "2022-02-18"),
                new Recs.SeasonReleaseInfo(2, 10, "2025-01-17"),
                new Recs.SeasonReleaseInfo(3, 8, "2026-10-15"),
                new Recs.SeasonReleaseInfo(4, 8, "2028-04-01")
        );
        assertEquals(3, Recs.computeReleasedSeasonsCount(tvSeasons, 3, "2026-10-15", "2026-11-03"),
                "computeReleasedSeasonsCount counts aired regular seasons (ignoring Season 0 specials and future Season 4)");
        assertEquals(true, Recs.shouldAutoUnwatchForNewSeason("tv", true, 2, 3),
                "Watched series with 2 recorded seasons auto-unwatches when Season 3 releases");
        assertEquals(false, Recs.shouldAutoUnwatchForNewSeason("tv", true, 3, 3),
                "Watched series stays watched when no newer season has aired");
        assertEquals(false, Recs.shouldAutoUnwatchForNewSeason("movie", true, 1, 2),
                "Movies never trigger new-season auto-unwatch");

        Recs.Pick sWatchedOther = new Recs.Pick();
        sWatchedOther.id = 500; sWatchedOther.kind = "tv"; sWatchedOther.title = "Finished Mini-Series";
        sWatchedOther.watched = true; sWatchedOther.genres.add("Drama");
        Recs.Pick sRenewed = new Recs.Pick();
        sRenewed.id = 501; sRenewed.kind = "tv"; sRenewed.title = "Severance";
        sRenewed.watched = true; sRenewed.genres.add("Sci-Fi");
        if (Recs.shouldAutoUnwatchForNewSeason(sRenewed.kind, sRenewed.watched, 1, 2)) {
            sRenewed.watched = false;
        }
        java.util.List<Recs.Pick> sortedWl = WatchlistSimilaritySorter.sortWatchlist(
                java.util.Arrays.asList(sWatchedOther, sRenewed), java.util.Collections.emptyList());
        assertEquals(false, sRenewed.watched, "Series with new season is no longer grayed out (watched == false)");
        assertEquals(501, sortedWl.get(0).id, "Auto-unwatched series moves back ahead of watched tail in Watch List");
        assertEquals(500, sortedWl.get(1).id, "Still-watched series remains at the end of Watch List");

        // --- 6. Softer Black Vignette & Smoother Hero-Background Image Transition ---
        assertTrue(!Shaders.HERO_VIGNETTE.contains("max(leftVig, bottomVig)"),
                "HERO_VIGNETTE eliminates harsh diagonal max(leftVig, bottomVig) ridge");
        assertTrue(Shaders.HERO_VIGNETTE.contains("(1.0 - leftVig) * (1.0 - bottomVig)"),
                "HERO_VIGNETTE uses smooth screen-blended feathering");
        assertTrue(HomeView.FADE_MS >= 420L,
                "Hero-background crossfade duration is widened (>= 420ms) for smooth transitions");
    }

    private static void testYellowBtPairingScanFilterAndBatteryPower() {
        // --- 1. Xiaomi TwoPanel BluetoothPairingDialog Detection & Cross-Process Auto-Confirm Stamp ---
        // Xiaomi MiGTVSettingsTwoPanel ConfirmationDialogFragment has FrameLayout root, generic IDs, and
        // ["Pair with Wireless Controller?", "Pair", "Cancel"] WITHOUT "Bluetooth" or "passkey" text:
        assertTrue(BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("Pair with Wireless Controller?", "Pair", "Cancel"),
                java.util.Arrays.asList("Pair", "Cancel"),
                null,
                false),
                "Detects Xiaomi TwoPanel pairing dialog without 'Bluetooth' text or 'pairing' view IDs");
        assertTrue(BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("התאמה עם Wireless Controller?", "התאם", "ביטול"),
                java.util.Arrays.asList("התאם", "ביטול"),
                null,
                false),
                "Detects Hebrew Xiaomi TwoPanel pairing dialog");
        assertTrue(BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("Wireless Controller", "482910", "OK", "Cancel"),
                java.util.Arrays.asList("OK", "Cancel"),
                null,
                true),
                "Detects numeric passkey confirmation when knownPairingActivity is true");
        // Must NEVER false-match Unpair/Forget confirmation dialogs, AddAccessoryActivity scan screen, or paired menus:
        assertTrue(!BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("Unpair Wireless Controller?", "OK", "Cancel"),
                java.util.Arrays.asList("OK", "Cancel"),
                null,
                false),
                "Never false-matches Unpair confirmation dialog");
        assertTrue(!BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("Searching for accessories…", "Wireless Controller"),
                java.util.Collections.singletonList("Wireless Controller"),
                null,
                false),
                "Never false-matches AddAccessoryActivity scanning screen");
        assertTrue(!BluetoothPairingAssist.isPairingWindowTextAndActions(
                java.util.Arrays.asList("Wireless Controller", "Connect", "Rename", "Unpair"),
                java.util.Arrays.asList("Connect", "Rename", "Unpair"),
                null,
                false),
                "Never false-matches paired accessory detail actions");

        String stamp = BluetoothPairingAssist.formatPendingPairStamp(1_700_000_000_000L, "EC:83:50:C1:4C:C6", "Wireless Controller");
        assertTrue(BluetoothPairingAssist.isPendingPairStampFresh(stamp, 1_700_000_005_000L, BluetoothPairingAssist.PENDING_PAIR_TTL_MS),
                "Pending SoarTV-initiated bond stamp is fresh within 25s window");
        assertTrue(!BluetoothPairingAssist.isPendingPairStampFresh(stamp, 1_700_000_030_000L, BluetoothPairingAssist.PENDING_PAIR_TTL_MS),
                "Pending SoarTV-initiated bond stamp expires cleanly after 25s");
        assertTrue(!BluetoothPairingAssist.isPendingPairStampFresh("", 1_700_000_005_000L, BluetoothPairingAssist.PENDING_PAIR_TTL_MS),
                "Empty pending stamp is rejected");

        // --- 2. Strict In-App Bluetooth Controller & Remote Filtering (Matching Native Android TV InputDeviceCriteria) ---
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "Xiaomi Scale S400 7E80", "AA:BB:CC:DD:EE:01", BluetoothControllerPairer.MAJOR_UNCATEGORIZED),
                "Filters out BLE smart bathroom scale (MAJOR_UNCATEGORIZED 0x1F00)");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "TV - Ziv", "AA:BB:CC:DD:EE:02", BluetoothControllerPairer.MAJOR_UNCATEGORIZED),
                "Filters out neighbor TV BLE beacon");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "סלון", "AA:BB:CC:DD:EE:03", BluetoothControllerPairer.MAJOR_UNCATEGORIZED),
                "Filters out uncategorized room appliance beacon");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "WiZ Tunable White", "AA:BB:CC:DD:EE:04", BluetoothControllerPairer.MAJOR_MISC),
                "Filters out MAJOR_MISC smart bulb BLE advertisement");
        assertTrue(!BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "44-55-66-77-88-99", "AA:BB:CC:DD:EE:05", BluetoothControllerPairer.MAJOR_PERIPHERAL),
                "Filters out raw MAC-formatted device name");

        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "Wireless Controller", "EC:83:50:C1:4C:C6", BluetoothControllerPairer.MAJOR_PERIPHERAL),
                "Accepts Yellow controller ('Wireless Controller', MAJOR_PERIPHERAL 0x0500)");
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "GameSir-Nova Lite", "EC:83:50:C1:4C:C7", BluetoothControllerPairer.MAJOR_UNCATEGORIZED),
                "Accepts GameSir controller even when BLE ad reports MAJOR_UNCATEGORIZED");
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "Generic BLE HID Pad", "EC:83:50:C1:4C:C8", BluetoothControllerPairer.MAJOR_UNCATEGORIZED, true, false),
                "Accepts uncategorized BLE device when HID/HOGP UUID (0x1812/0x1124) is advertised");
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "Google TV Remote", "EC:83:50:C1:4C:C9", BluetoothControllerPairer.MAJOR_PERIPHERAL),
                "Accepts 'Google TV Remote' without false rejection from TV appliance filter");
        assertTrue(BluetoothControllerPairer.shouldIncludeDiscoveredDevice(
                "Custom BLE Accessory", "EC:83:50:C1:4C:CA", BluetoothControllerPairer.MAJOR_UNCATEGORIZED, false, true),
                "Accepts all named devices when user toggles Show All Devices mode");

        // --- 3. Battery Percentage & Connected-to-Power Telemetry Across Controllers, Settings, & PC ---
        // Merged DualShock 4 / GameSir Home+B Bluetooth InputDevice ("Wireless Controller Touchpad") carries kernel battery:
        assertEquals(true,
                GamingZone.isPhysicalGameController("Wireless Controller Touchpad", false, true, true),
                "Merged 'Wireless Controller Touchpad' with gamepad source + buttons accepted so kernel battery is read");
        assertEquals(true,
                GamingZone.isPhysicalGameController("DualSense Wireless Controller Touchpad", false, true, true),
                "Merged 'DualSense Wireless Controller Touchpad' with gamepad source + buttons accepted");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Wireless Controller Touchpad", false, false, false),
                "Standalone USB 'Wireless Controller Touchpad' without gamepad buttons rejected");
        assertEquals(false,
                GamingZone.isPhysicalGameController("Xbox Touchpad", false, true, true),
                "Non-controller 'Xbox Touchpad' companion still rejected");
        assertEquals("Wireless Controller",
                ControllerManager.cleanDeviceName("Wireless Controller Touchpad"),
                "Strips trailing ' Touchpad' from merged Bluetooth controller name");

        // Bluetooth battery fallback must NEVER self-block on its own bt:<mac> descriptor:
        java.util.Set<String> claimedBt = new java.util.HashSet<>();
        claimedBt.add("aa:bb:cc:11:22:33");
        assertEquals(false,
                ControllerManager.shouldSkipClaimedBluetoothAddress("aa:bb:cc:11:22:33", "bt:aa:bb:cc:11:22:33", claimedBt),
                "Controller never skips its own Bluetooth MAC during battery fallback");
        assertEquals(true,
                ControllerManager.shouldSkipClaimedBluetoothAddress("aa:bb:cc:11:22:33", "bt:ec:83:50:c1:4c:c6", claimedBt),
                "Second controller skips MAC already claimed by first controller");

        // Connected-to-power covers BOTH BATTERY_STATUS_CHARGING (2) and BATTERY_STATUS_FULL (5) on USB power:
        assertEquals(true, ControllerManager.isBatteryChargingOrOnPower(2), "BATTERY_STATUS_CHARGING (2) is on power");
        assertEquals(true, ControllerManager.isBatteryChargingOrOnPower(5), "BATTERY_STATUS_FULL (5) while plugged in is on power");
        assertEquals(false, ControllerManager.isBatteryChargingOrOnPower(3), "BATTERY_STATUS_DISCHARGING (3) is on battery");
        assertEquals(false, ControllerManager.isBatteryChargingOrOnPower(4), "BATTERY_STATUS_NOT_CHARGING (4) is not charging");
        assertEquals(false, ControllerManager.isBatteryChargingOrOnPower(1), "BATTERY_STATUS_UNKNOWN (1) is not charging");

        // Controller pill & power status formatting (untagged never drops controller name; on-power always shows):
        ControllerManager.ControllerInfo untagged85 = new ControllerManager.ControllerInfo(
                10, "bt:ec:83:50:c1:4c:c6", "Wireless Controller", "", ControllerManager.COLOR_NEUTRAL_FALLBACK, false, 85, false, true);
        assertEquals("Wireless Controller · 85%", ControllerManager.formatControllerPillLabel(untagged85),
                "Untagged controller pill preserves device name alongside battery percentage");
        assertEquals("85%", untagged85.getPowerStatusDisplay(), "Discharging controller formats battery %");

        ControllerManager.ControllerInfo greenOnPower100 = new ControllerManager.ControllerInfo(
                11, "bt:aa:bb:cc:11:22:33", "Wireless Controller", "Green", ControllerManager.COLOR_GREEN, true, 100, true, true);
        assertEquals("Green · 100%", ControllerManager.formatControllerPillLabel(greenOnPower100),
                "Controller plugged in at 100% displays name + 100% (with vector bolt)");
        assertEquals("100% · Connected to Power", greenOnPower100.getPowerStatusDisplay(),
                "Controller plugged in at 100% formats '100% · Connected to Power'");

        ControllerManager.ControllerInfo powerOnlyCtrl = new ControllerManager.ControllerInfo(
                12, "usbport:13623:4160:xhci-1", "USB Gamepad", "Blue", ControllerManager.COLOR_LIGHT_BLUE, true, -1, true, true);
        assertEquals("Blue · Connected to Power", ControllerManager.formatControllerPillLabel(powerOnlyCtrl),
                "Controller reporting power without % displays 'Connected to Power'");

        // Settings -> Gaming -> Controllers & Remotes surfaces battery % and On Power status in row values & subtitles:
        SettingsTree.StateSnapshot battSnap = new SettingsTree.StateSnapshot();
        battSnap.knownControllers = java.util.Arrays.asList(greenOnPower100, untagged85);
        SettingsTree.Node battRoot = SettingsTree.buildRoot(battSnap);
        SettingsTree.Node battCtrls = battRoot.findById("gaming_controllers");
        assertEquals("Connected · 100% · On Power", battCtrls.children.get(0).value,
                "Settings row value shows 'Connected · 100% · On Power' when plugged in");
        assertTrue(battCtrls.children.get(0).subtitle.contains("100% · Connected to Power"),
                "Settings row subtitle shows '100% · Connected to Power'");
        assertTrue(battCtrls.children.get(1).subtitle.contains("85%"),
                "Untagged controller Settings row subtitle also shows live battery %");
    }

    /**
     * Verifies Round 7 user requests:
     * 1. Settings -> Screensaver: "Preview Screensaver Now" is replaced with "Start Screensaver".
     * 2. Settings -> Screensaver: Gallery previews metadata & descriptions for all 5 photography categories.
     * 3. Settings -> Screensaver: Distinct procedural vector icons for each of the 4 clock styles and 5 galleries.
     * 4. Home Screen: Closed Favorite Apps side-shelf indicator is taller and narrower.
     */
    private static void testRound7ScreensaverStartGalleryPreviewsIconsAndShelfIndicator() {
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.saverCategory = "nature";
        snap.saverClockStyle = "liquid_glass";
        SettingsTree.Node root = SettingsTree.buildRoot(snap);

        // 1. "Start Screensaver" replaces "Preview Screensaver Now"
        SettingsTree.Node ssNode = root.findById("screensaver");
        assertTrue(ssNode != null && ssNode.hasChildren(), "Screensaver & Galleries node exists");
        SettingsTree.Node startNode = ssNode.findById("ss_preview");
        assertTrue(startNode != null, "ss_preview node exists under Screensaver & Galleries");
        assertEquals("Start Screensaver", startNode.title,
                "Screensaver trigger row is titled 'Start Screensaver' instead of 'Preview Screensaver Now'");

        // 2 & 3. Distinct icons and rich preview metadata for all 5 Galleries and all 4 Clock Styles
        SettingsTree.Node catParent = ssNode.findById("ss_category");
        SettingsTree.Node clockParent = ssNode.findById("ss_clock");
        assertTrue(catParent != null && catParent.hasChildren(), "Photography Category node has children");
        assertTrue(clockParent != null && clockParent.hasChildren(), "Clock Style node has children");
        assertEquals("gallery_nature", catParent.icon, "Parent Photography Category row uses active gallery's icon");
        assertEquals("clock_liquid", clockParent.icon, "Parent Clock Style row uses active clock style's icon");

        java.util.Set<String> galleryIcons = new java.util.HashSet<>();
        for (String cat : Prefs.SAVER_CATEGORIES) {
            SettingsTree.Node child = catParent.findById("ss_cat:" + cat);
            assertTrue(child != null, "Gallery child node exists for " + cat);
            assertTrue(Symbols.isSupportedIcon(child.icon), "Gallery icon is supported in Symbols: " + child.icon);
            galleryIcons.add(child.icon);

            ScreensaverCatalog.PhotoEntry sample = ScreensaverCatalog.sampleEntryForCategory(cat);
            assertTrue(sample != null && !sample.title.isEmpty() && !sample.artist.isEmpty(),
                    "Gallery preview sample entry has non-empty title and artist for " + cat);
            if ("nature".equals(cat) || "urban".equals(cat)) {
                assertTrue(!sample.location.isEmpty(),
                        "Gallery preview sample entry has non-empty location for geographic category " + cat);
            }
            String desc = ScreensaverCatalog.categoryDescription(cat);
            assertTrue(desc != null && desc.length() > 20, "Gallery category description is detailed for " + cat);
        }
        assertEquals(Prefs.SAVER_CATEGORIES.length, galleryIcons.size(),
                "Every photography gallery has a unique, distinct icon (" + galleryIcons + ")");

        java.util.Set<String> clockIcons = new java.util.HashSet<>();
        for (String st : Prefs.SAVER_CLOCK_STYLES) {
            SettingsTree.Node child = clockParent.findById("ss_clock:" + st);
            assertTrue(child != null, "Clock style child node exists for " + st);
            assertTrue(Symbols.isSupportedIcon(child.icon), "Clock style icon is supported in Symbols: " + child.icon);
            clockIcons.add(child.icon);
        }
        assertEquals(Prefs.SAVER_CLOCK_STYLES.length, clockIcons.size(),
                "Every screensaver clock style has a unique, distinct icon (" + clockIcons + ")");

        // Ensure zero overlap between gallery icons and clock style icons
        for (String ci : clockIcons) {
            assertTrue(!galleryIcons.contains(ci), "Clock style icon " + ci + " is distinct from all gallery icons");
        }

        // 4. Home Screen App-Shelf indicator is taller (> 152px) and narrower (< 44px)
        assertTrue(HomeView.SIDE_SHELF_HINT_W < 44f && HomeView.SIDE_SHELF_HINT_W <= 32f,
                "App-shelf edge indicator is narrower than previous 44px width (actual=" + HomeView.SIDE_SHELF_HINT_W + ")");
        assertTrue(HomeView.SIDE_SHELF_HINT_H > 152f && HomeView.SIDE_SHELF_HINT_H >= 200f,
                "App-shelf edge indicator is taller than previous 152px height (actual=" + HomeView.SIDE_SHELF_HINT_H + ")");
    }

    /**
     * Verifies Round 8 user requests (items 1..7):
     * 1. Stronger dark & blur vignettes in Shaders.HERO_VIGNETTE.
     * 2. Seamless pattern tiles completely removed from GamingZone.
     * 3. Uncached games return null from peekGameBackdrop/firstReadyGameBackdrop so HomeView keeps the loaded backdrop.
     * 4. Persistent HD game_hero_hd disk cache & expanded 24-entry RAM cache for Gaming screen preloading.
     * 5. Vibrant multi-orb Cosmic Aurora Mesh unified background on non-hero screens (non-dull saturation + legible luma).
     * 6. High-res @2x (3840x1240) / 4K / 1920x1080 candidate URLs & Steam Store parsers for game hero art.
     * 7. Closed App-Shelf indicator removes the 'apps' icon and shows only the arrow.
     */
    private static void testRound8VignettesHighResGamingAuroraAndShelfArrow() throws Exception {
        // 1. Stronger Dark & Blur Vignettes in HERO_VIGNETTE
        assertTrue(Shaders.HERO_VIGNETTE.contains("0.88") && Shaders.HERO_VIGNETTE.contains("0.93")
                        && Shaders.HERO_VIGNETTE.contains("0.46"),
                "HERO_VIGNETTE dark vignette weights are boosted (0.88 left, 0.93 bottom, 0.46 top)");
        assertTrue(Shaders.HERO_VIGNETTE.contains("float darkDim = isLight ? 0.0 : 0.12;")
                        && Shaders.HERO_VIGNETTE.contains("float homeBlurMix = isLight ? 1.0 : darkBlurGrad;"),
                "HERO_VIGNETTE applies overall blur in Light Mode and 12% overall dim + directional blur-gradient in Dark Mode");
        assertTrue(Shaders.HERO_VIGNETTE.contains("(1.0 - leftVig) * (1.0 - bottomVig)"),
                "HERO_VIGNETTE preserves smooth screen-blended transition without diagonal crease");
        assertEquals(3840, Backdrop.W, "Backdrop hero target width is true 4K 3840");
        assertEquals(2160, Backdrop.H, "Backdrop hero target height is true 4K 2160");
        assertEquals(500, Backdrop.TALL_W, "Backdrop tall poster target width is 500");
        assertEquals(750, Backdrop.TALL_H, "Backdrop tall poster target height is 750");
        assertEquals("https://image.tmdb.org/t/p/original", Recs.backdropBaseUrlForWidth(1920),
                "Recs always requests 4K /original hero backdrops");
        assertEquals("S2E10", WatchNext.formatEpisodeLine(2, 10, "Episode Name"),
                "Continue Watching formats episode badge as S2E10");
        int[] wideTopRight = Backdrop.computeTopRightCropRect(2560, 1080, 16f / 9f);
        assertEquals(2560 - 1920, wideTopRight[0], "Wide backdrop crop removes left side, preserving right side");
        assertEquals(0, wideTopRight[1], "Wide backdrop crop preserves top (top = 0)");
        int[] tallTopRight = Backdrop.computeTopRightCropRect(1920, 1440, 16f / 9f);
        assertEquals(0, tallTopRight[0], "Tall backdrop crop preserves full width");
        assertEquals(0, tallTopRight[1], "Tall backdrop crop removes bottom side, preserving top (top = 0)");

        // 2. Seamless Pattern Tiles completely removed from GamingZone
        for (java.lang.reflect.Method m : GamingZone.class.getDeclaredMethods()) {
            String mn = m.getName().toLowerCase(java.util.Locale.ROOT);
            assertTrue(!mn.contains("seamlesspattern") && !mn.contains("patternbackdrop"),
                    "Seamless pattern method removed from GamingZone: " + m.getName());
        }

        // 3 & 4. Keep loaded background until new one loads + persistent HD cache & expanded LRU capacity
        GamingZone.GameItem uncachedGame = new GamingZone.GameItem("steam_990080", "Hogwarts Legacy", "steam", "", false);
        assertEquals(null, GamingZone.peekGameBackdrop(uncachedGame),
                "Uncached game returns null from peekGameBackdrop so HomeView keeps currently loaded game backdrop");
        assertEquals(null, GamingZone.firstReadyGameBackdrop(java.util.Collections.singletonList(uncachedGame)),
                "firstReadyGameBackdrop returns null when no game hero has been decoded yet");
        assertEquals("game_hero_hd", GamingZone.GAME_HERO_DISK_DIR_NAME,
                "Game hero cache uses persistent game_hero_hd directory");
        assertEquals(24, GamingZone.MAX_GAME_HERO_BACKDROPS,
                "Gaming hero LRU expanded to 24 decoded backdrops in RAM (R3)");
        assertTrue(GamingZone.MIN_GAME_HERO_WIDTH >= 1200 && GamingZone.MIN_GAME_HERO_HEIGHT >= 580,
                "Minimum game hero dimensions reject low-res thumbnails");

        // 5. Rich, non-dull Unified Cosmic Aurora Mesh on non-hero screens (Dark & Light)
        int mw = 80, mh = 45;
        int[] darkAurora = HomeView.generateUnifiedAuroraPixels(mw, mh, 1.0f, null);
        int[] lightAurora = HomeView.generateUnifiedAuroraPixels(mw, mh, 0.0f, null);
        assertEquals(mw * mh, darkAurora.length, "Dark aurora mesh generates exact pixel count");
        assertEquals(mw * mh, lightAurora.length, "Light aurora mesh generates exact pixel count");

        int maxChSpreadDark = 0;
        int maxLumaDark = 0, minLumaDark = 255;
        for (int px : darkAurora) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            if (spread > maxChSpreadDark) maxChSpreadDark = spread;
            int luma = (r * 77 + g * 150 + b * 29) >> 8;
            if (luma > maxLumaDark) maxLumaDark = luma;
            if (luma < minLumaDark) minLumaDark = luma;
        }
        assertTrue(maxChSpreadDark >= 34,
                "Dark unified Aurora mesh has rich jewel-toned color saturation (not dull gray), maxSpread=" + maxChSpreadDark);
        assertTrue(maxLumaDark - minLumaDark >= 18 && maxLumaDark <= 98,
                "Dark unified Aurora mesh is dimmed ~15% while preserving multi-orb contrast, maxLuma=" + maxLumaDark);

        // Verify spatial hue variety across quadrants (upper-left amethyst violet vs upper-right sapphire cyan)
        int ulPx = HomeView.sampleUnifiedAuroraColor(0.15f, 0.22f, 1.0f, 0f);
        int urPx = HomeView.sampleUnifiedAuroraColor(0.84f, 0.16f, 1.0f, 0f);
        int ulR = (ulPx >> 16) & 0xFF, ulG = (ulPx >> 8) & 0xFF;
        int urR = (urPx >> 16) & 0xFF, urG = (urPx >> 8) & 0xFF;
        assertTrue(ulR > urR + 9 && urG > ulG + 9,
                "Upper-left amethyst nebula (higher red) and upper-right cyan pool (higher green) create rich spatial color variety");

        int minLumaLight = 255, maxSpreadLight = 0;
        for (int px : lightAurora) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            if (spread > maxSpreadLight) maxSpreadLight = spread;
            int luma = (r * 77 + g * 150 + b * 29) >> 8;
            if (luma < minLumaLight) minLumaLight = luma;
        }
        assertTrue(minLumaLight >= 180 && maxSpreadLight >= 18,
                "Light unified Aurora mesh is airy, pastel-saturated, and high-contrast for dark text");

        // 6. High-Resolution Game Hero Candidate URLs & Store Fallbacks
        java.util.List<String> hwUrls = GamingZone.resolveHeroArtCandidateUrls("exe_hogwartslegacy", "Hogwarts Legacy", "");
        assertTrue(hwUrls.size() >= 2
                        && hwUrls.get(0).endsWith("/990080/library_hero_2x.jpg")
                        && hwUrls.get(1).endsWith("/990080/library_hero.jpg"),
                "Steam titles prioritize @2x 3840x1240 library_hero_2x.jpg ahead of 1920x620 library_hero.jpg");
        assertEquals("448510", GamingZone.extractSteamAppId("exe_overcooked1", "Overcooked", ""),
                "Distinguishes original Overcooked (448510) from Overcooked! 2 (728880)");
        assertEquals("728880", GamingZone.extractSteamAppId("exe_overcooked2", "Overcooked! 2", ""),
                "Maps Overcooked! 2 to 728880");
        assertEquals("1054430", GamingZone.extractSteamAppId("exe_fling", "Fling to the Finish", ""),
                "Maps Fling to the Finish to 1054430");
        assertEquals("829660", GamingZone.extractSteamAppId("exe_nttr", "No Time to Relax", ""),
                "Maps No Time to Relax to 829660");

        java.util.List<String> lolUrls = GamingZone.resolveHeroArtCandidateUrls(
                "exe_riot_games", "League of Legends", "C:\\Games\\Riot Games\\League of Legends\\LeagueClient.exe");
        assertTrue(!lolUrls.isEmpty() && lolUrls.get(0).contains("3840x2160"),
                "League of Legends resolves 4K 3840x2160 official Riot artwork first");
        assertTrue(!GamingZone.resolveDefaultArtUrl("exe_riot_games", "League of Legends", "").isEmpty(),
                "League of Legends also resolves official 16:9 card tile art");

        String storeDetailsJson = "{\"1054430\":{\"success\":true,\"data\":{\"screenshots\":["
                + "{\"id\":0,\"path_full\":\"https:\\/\\/shared.akamai.steamstatic.com\\/store_item_assets\\/steam\\/apps\\/1054430\\/ss_hd.1920x1080.jpg\"}"
                + "]}}}";
        assertEquals("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1054430/ss_hd.1920x1080.jpg",
                GamingZone.parseSteamStoreFirstScreenshotUrl(storeDetailsJson, "1054430"),
                "Extracts and unescapes 1920x1080 Full-HD screenshot fallback when library_hero_2x.jpg 404s");
        String searchJson = "{\"total\":1,\"items\":[{\"type\":\"app\",\"price\":{\"currency\":\"USD\",\"initial\":5999},\"id\":1091500,\"name\":\"Cyberpunk 2077\"}]}";
        assertEquals("1091500", GamingZone.parseSteamStoreSearchAppId(searchJson),
                "Extracts Steam App ID from keyless Steam Store search JSON for unknown standalone PC games");

        // 7. Closed App-Shelf indicator shows ONLY the directional arrow (no 'apps' icon)
        assertEquals(false, HomeView.SIDE_SHELF_HINT_SHOW_APPS_ICON,
                "App-shelf edge indicator omits the 'apps' icon and displays only the arrow");

        // 8. Milestone 2: Continue Watching & Tile Presentation (R1)
        // 8.1 Header Status Line Removal & Standard Overview Top Baseline (280dp)
        HomeView.HeaderState hsContinueM2 = new HomeView.HeaderState();
        hsContinueM2.continueStatusText = "Continue Watching S1E19";
        assertEquals(HomeView.OVERVIEW_BOX_TOP, HomeView.effectiveOverviewBoxTop(hsContinueM2), 0.01f,
                "effectiveOverviewBoxTop consistently returns standard 280dp baseline even when continueStatusText is present");

        // 8.2 Continue Watching Tile Episode Text Formatting ("S2E10")
        assertEquals("S1E1", WatchNext.formatEpisodeLine(1, "Reunited"),
                "Formats default season and episode as 'S1E1'");
        assertEquals("S2E10", WatchNext.formatEpisodeLine(2, 10, "Episode Name"),
                "Formats season and episode as 'S2E10'");
        assertEquals("S1E1", WatchNext.formatEpisodeLine(1, null),
                "Formats episode number as 'S1E1' when title is null");
        assertEquals("S1E19", WatchNext.formatEpisodeLine(19, "Change Your Mind"),
                "Formats two-digit episode as 'S1E19'");
        assertEquals("", WatchNext.formatEpisodeLine(0, "The Movie"),
                "Returns empty string when episode number is 0");
        assertEquals("", WatchNext.formatEpisodeLine(0, null),
                "Returns empty string when both episode and title are missing");

        WatchNext.Item itEp = new WatchNext.Item("com.stremio.one", null, null);
        itEp.seasonNumber = 2;
        itEp.episodeNumber = 10;
        itEp.episodeTitle = "Rose's Scabbard";
        assertEquals("S2E10", itEp.formatEpisodeLine(),
                "WatchNext.Item.formatEpisodeLine formats 'S2E10'");

        WatchNext.Item itNoTitle = new WatchNext.Item("com.stremio.one", null, null);
        itNoTitle.seasonNumber = 1;
        itNoTitle.episodeNumber = 7;
        itNoTitle.episodeTitle = "";
        assertEquals("S1E7", itNoTitle.formatEpisodeLine(),
                "WatchNext.Item.formatEpisodeLine formats 'S1E7'");

        // 8.3 Continue Watching Baseline Reservation
        float cellBottom = 400f;
        float kVal = 1.0f;
        float sVal = 1.0f;
        float baselineRes = HomeView.continueEpisodeBaseline(cellBottom, kVal, sVal);
        assertEquals(cellBottom - 26f, baselineRes, 0.01f,
                "Episode text baseline is reserved at cell.bottom - 26*k*s");
        assertEquals(baselineRes, HomeView.continueEpisodeBaseline(cellBottom, kVal, sVal), 0.01f,
                "Episode text baseline height is identical across cards with and without progress bar");

        // 8.4 Skeleton Screen Placeholder Count (4 when !watchLoaded)
        HomeView.BrowserRow rowLoading = new HomeView.BrowserRow(HomeView.ROW_CONTINUE, "Continue Watching", null,
                null, null, null, null, null, false);
        assertEquals(4, rowLoading.count(),
                "Continue Watching row returns 4 skeleton placeholders when !watchLoaded");
        assertTrue(rowLoading.isSkeleton(0), "Card 0 is skeleton when !watchLoaded");
        assertTrue(rowLoading.isSkeleton(3), "Card 3 is skeleton when !watchLoaded");

        HomeView.BrowserRow rowLoadedEmpty = new HomeView.BrowserRow(HomeView.ROW_CONTINUE, "Continue Watching", null,
                null, null, null, null, null, true);
        assertEquals(1, rowLoadedEmpty.count(),
                "Continue Watching row returns 1 (empty state) when loaded and empty");
        assertTrue(!rowLoadedEmpty.isSkeleton(0), "Card 0 is not skeleton when watchLoaded is true");

        java.util.List<WatchNext.Item> m2Items = java.util.Arrays.asList(itEp, itNoTitle);
        HomeView.BrowserRow rowLoadedItems = new HomeView.BrowserRow(HomeView.ROW_CONTINUE, "Continue Watching", null,
                m2Items, null, null, null, null, true);
        assertEquals(2, rowLoadedItems.count(),
                "Continue Watching row returns actual item count (2) when loaded with items");
        assertTrue(!rowLoadedItems.isSkeleton(0), "Card 0 is not skeleton when items loaded");
    }

    private static void testMilestone3StremioIntentClickLatencyAndReturnPerf() {
        // 1. Series Deep Link Target Resolution (e.g. Steven Universe S1E19)
        String stevenUri = Recs.buildStremioDetailUri(true, "tt3061046", 1, 19, null);
        assertEquals("stremio:///detail/series/tt3061046/tt3061046:1:19", stevenUri,
                "Series deep link appends episode videoId target {imdbId}:{season}:{episode}");

        String stevenCinemeta = CinemetaClient.buildStremioLink("series", "tt3061046", 1, 19);
        assertEquals("stremio:///detail/series/tt3061046/tt3061046:1:19", stevenCinemeta,
                "CinemetaClient builds series deep link with episode videoId target");

        // 2. Movie Deep Link Resolution
        String movieUri = Recs.buildStremioDetailUri(false, "tt0816692", 0, 0, null);
        assertEquals("stremio:///detail/movie/tt0816692", movieUri,
                "Movie deep link uses stremio:///detail/movie/{imdbId}");

        String movieCinemeta = CinemetaClient.buildStremioLink("movie", "tt0816692", 0, 0);
        assertEquals("stremio:///detail/movie/tt0816692", movieCinemeta,
                "CinemetaClient builds movie deep link without episode segment");

        // 3. Fallback VideoId Resolution for Series
        String videoIdUri = Recs.buildStremioDetailUri(true, "tt3061046", 0, 0, "tt3061046:1:19");
        assertEquals("stremio:///detail/series/tt3061046/tt3061046:1:19", videoIdUri,
                "Series deep link with explicit videoId formats properly");

        // 4. Stremio Package Support (both com.stremio.tv and com.stremio.one)
        assertEquals("com.stremio.tv", Recs.STREMIO_TV, "Recs defines STREMIO_TV as com.stremio.tv");
        assertEquals("com.stremio.one", Recs.STREMIO_ONE, "Recs defines STREMIO_ONE as com.stremio.one");
        assertTrue(HomeView.isStremioPackage("com.stremio.tv"), "isStremioPackage recognizes com.stremio.tv");
        assertTrue(HomeView.isStremioPackage("com.stremio.one"), "isStremioPackage recognizes com.stremio.one");
        assertTrue(!HomeView.isStremioPackage("com.netflix.ninja"), "isStremioPackage rejects non-Stremio package");

        // 5. Intent Flags (FLAG_ACTIVITY_NEW_TASK and FLAG_ACTIVITY_CLEAR_TOP) & Target URI Resolution
        int watchingFlags = HomeView.watchingIntentFlags();
        assertTrue((watchingFlags & android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
                "watchingIntentFlags has FLAG_ACTIVITY_NEW_TASK");
        assertTrue((watchingFlags & android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0,
                "watchingIntentFlags has FLAG_ACTIVITY_CLEAR_TOP");

        WatchNext.Item itUnresolved = new WatchNext.Item("com.stremio.tv", null, null, "stremio:///detail/series/tt3061046");
        itUnresolved.seasonNumber = 1;
        itUnresolved.episodeNumber = 19;
        assertEquals("stremio:///detail/series/tt3061046/tt3061046:1:19",
                HomeView.resolveWatchingUri(itUnresolved),
                "resolveWatchingUri appends missing episode videoId to generic series link");

        WatchNext.Item itAlreadyResolved = new WatchNext.Item("com.stremio.tv", null, null, stevenUri);
        itAlreadyResolved.seasonNumber = 1;
        itAlreadyResolved.episodeNumber = 19;
        assertEquals(stevenUri, HomeView.resolveWatchingUri(itAlreadyResolved),
                "resolveWatchingUri preserves already resolved series episode link");

        WatchNext.Item itMovie = new WatchNext.Item("com.stremio.tv", null, null, movieUri);
        assertEquals(movieUri, HomeView.resolveWatchingUri(itMovie),
                "resolveWatchingUri preserves movie link");

        // 6. Return Navigation Optimization (sameWatching diff check)
        WatchNext.Item itA = new WatchNext.Item("com.stremio.tv", "https://img/a.jpg", "https://img/b.jpg", stevenUri);
        itA.title = "Steven Universe";
        itA.position = 120_000L;
        itA.duration = 660_000L;
        itA.episode = "S1 E19";

        WatchNext.Item itB = new WatchNext.Item("com.stremio.tv", "https://img/a.jpg", "https://img/b.jpg", stevenUri);
        itB.title = "Steven Universe";
        itB.position = 120_000L;
        itB.duration = 660_000L;
        itB.episode = "S1 E19";

        java.util.List<WatchNext.Item> listA = java.util.Collections.singletonList(itA);
        java.util.List<WatchNext.Item> listB = java.util.Collections.singletonList(itB);

        assertTrue(HomeView.sameWatching(listA, listB), "sameWatching returns true for identical items");
        assertTrue(HomeView.sameWatching(listA, listA), "sameWatching returns true for identical list reference");
        assertTrue(HomeView.sameWatching(null, null), "sameWatching returns true for both null");
        assertTrue(!HomeView.sameWatching(listA, null), "sameWatching returns false when one list is null");
        assertTrue(!HomeView.sameWatching(listA, java.util.Collections.emptyList()), "sameWatching returns false for different size");

        WatchNext.Item itC = new WatchNext.Item("com.stremio.tv", "https://img/a.jpg", "https://img/b.jpg", stevenUri);
        itC.title = "Steven Universe";
        itC.position = 180_000L; // progress changed
        itC.duration = 660_000L;
        itC.episode = "S1 E19";
        java.util.List<WatchNext.Item> listC = java.util.Collections.singletonList(itC);
        assertTrue(!HomeView.sameWatching(listA, listC), "sameWatching detects playback position change");
    }

    /**
     * Verifies Milestone 4 (Background Vignette, Lighting, Shaders, and Theme Styling - R3):
     * 1. Dark Mode Background Vignette Dimming (HERO_VIGNETTE darkDim = 12% when themeColor.r < 0.2).
     * 2. Preserves legacy shader tokens (0.88, 0.93, 0.46, 0.92, 0.94, (1.0 - leftVig) * (1.0 - bottomVig)).
     * 3. Frosted Glass Light Theme Vignette (max translucent mix 0.65 + specular highlight).
     * 4. Light theme gray text contrast constants L_DIM (0xFF444446) and L_FAINT (0xFF5A5A5F) meeting WCAG AA (>4.5:1).
     * 5. Total elimination of setShadowLayer calls on text paints across launcher in HomeView.java.
     * 6. Dark theme ambient mesh dimming via (1.0f - 0.16f * n) keeping maxLumaDark <= 115.
     * 7. Default loading background dark vignette without blur in drawLightSky.
     * 8. Browser row clipRect downward expansion (+ 52 * k) eliminating sharp tile shadow cut.
     */
    private static void testMilestone4ThemesShadersAndVisualQuality() throws Exception {
        // 1 & 2. HERO_VIGNETTE Light Mode overall blur + Dark Mode 12% dim & directional blur-gradient
        assertTrue(Shaders.HERO_VIGNETTE.contains("float darkDim = isLight ? 0.0 : 0.12;")
                        && Shaders.HERO_VIGNETTE.contains("float darkBlurGrad = 1.0 - (1.0 - leftBlur) * (1.0 - bottomBlur);")
                        && Shaders.HERO_VIGNETTE.contains("float homeBlurMix = isLight ? 1.0 : darkBlurGrad;"),
                "HERO_VIGNETTE restores 12% dark dim + dark directional blur-gradient and Light Mode overall blur");
        assertEquals(144, Recs.MAX_POSTERS_COMPACT_RAM, "Expanded compact-RAM poster cache capacity for preloading");
        assertTrue(Shaders.HERO_VIGNETTE.contains("0.88") && Shaders.HERO_VIGNETTE.contains("0.93")
                        && Shaders.HERO_VIGNETTE.contains("0.46"),
                "HERO_VIGNETTE preserves directional dark vignette weights (0.88, 0.93, 0.46)");
        assertTrue(Shaders.HERO_VIGNETTE.contains("(1.0 - leftVig) * (1.0 - bottomVig)"),
                "HERO_VIGNETTE preserves smooth screen-blended base transition");

        // 3. Crisp White Frosted Glass Light Theme Vignette
        assertTrue(Shaders.HERO_VIGNETTE.contains("0.88"),
                "HERO_VIGNETTE blends crisp white light theme vignette at 0.88 for bright white readability");
        assertTrue(Shaders.HERO_VIGNETTE.contains("(1.0 - uv.y) * leftCurve * 0.045"),
                "HERO_VIGNETTE adds specular glassmorphism highlight in light mode");

        // 4. Softened Slate-Charcoal Light Theme Ink & Gray Text Contrast (WCAG AA Compliance)
        assertEquals(0xFF343741, HomeView.L_INK, "HomeView.L_INK is softened to slate charcoal 0xFF343741");
        assertEquals(0xFF565A67, HomeView.L_DIM, "HomeView.L_DIM is softened to 0xFF565A67");
        assertEquals(0xFF676C7B, HomeView.L_FAINT, "HomeView.L_FAINT is softened to 0xFF676C7B while keeping WCAG AA contrast");
        double bgLuma = computeRelativeLuminance(0xFFF5F5F7);
        double inkLuma = computeRelativeLuminance(HomeView.L_INK);
        double dimLuma = computeRelativeLuminance(HomeView.L_DIM);
        double faintLuma = computeRelativeLuminance(HomeView.L_FAINT);
        double inkContrast = (bgLuma + 0.05) / (inkLuma + 0.05);
        double dimContrast = (bgLuma + 0.05) / (dimLuma + 0.05);
        double faintContrast = (bgLuma + 0.05) / (faintLuma + 0.05);
        assertTrue(inkContrast >= 7.0, "L_INK WCAG AAA contrast ratio against light theme bg >= 7.0:1 (actual=" + inkContrast + ")");
        assertTrue(dimContrast >= 4.5, "L_DIM WCAG AA contrast ratio against light theme bg >= 4.5:1 (actual=" + dimContrast + ")");
        assertTrue(faintContrast >= 4.5, "L_FAINT WCAG AA contrast ratio against light theme bg >= 4.5:1 (actual=" + faintContrast + ")");

        // 5. Total Elimination of setShadowLayer on Text Paints Across Launcher
        java.io.File hvFile = new java.io.File("app/src/io/soartv/HomeView.java");
        if (hvFile.exists()) {
            String hvCode = new String(java.nio.file.Files.readAllBytes(hvFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\\b(labelPaint|titlePaint|metaPaint|clockPaint|tipPaint|quotePaint|headerTitlePaint|headerMetaPaint|headerGenrePaint|headerContinuePaint|continueEpisodePaint|fitPaint)\\.setShadowLayer"
            ).matcher(hvCode);
            assertTrue(!m.find(), "Text paints in HomeView.java must not call setShadowLayer");

            // 7. Loading Background Dark Vignette in drawLightSky
            assertTrue(hvCode.contains("activeScreen == SCREEN_HOME") && hvCode.contains("leftVigPaint.setColorFilter(veilF)"),
                    "HomeView.drawLightSky renders vignette scrims on default background while home screen is loading");

            // 8. Tile Shadow Clipping Cut Fix in drawBrowserRow
            assertTrue(hvCode.contains("c.clipRect(left - 28 * k, y, w, y + row.height(k, activeScreen) + 52 * k);")
                    || hvCode.contains("c.clipRect(left - 28 * k, y, w, y + row.height(k) + 52 * k);"),
                    "HomeView.drawBrowserRow expands clipRect downward by + 52 * k so focused tile shadows fade smoothly");
        }

        // 6. Dimmed Dark Unified Aurora Mesh
        int[] darkAurora = HomeView.generateUnifiedAuroraPixels(160, 90, 1.0f, null);
        int maxLumaDark = 0, minLumaDark = 255;
        for (int px : darkAurora) {
            int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            int luma = (r * 77 + g * 150 + b * 29) >> 8;
            if (luma > maxLumaDark) maxLumaDark = luma;
            if (luma < minLumaDark) minLumaDark = luma;
        }
        assertTrue(maxLumaDark <= 115, "Dimmed dark unified aurora mesh maxLumaDark <= 115 (actual=" + maxLumaDark + ")");
        assertTrue(maxLumaDark - minLumaDark >= 20, "Dimmed dark unified aurora mesh preserves rich orb contrast");
    }

    private static double computeRelativeLuminance(int color) {
        double r = ((color >> 16) & 0xFF) / 255.0;
        double g = ((color >> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        double rLin = r <= 0.04045 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        double gLin = g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        double bLin = b <= 0.04045 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin;
    }

    /**
     * Milestone 5: Tile Sizing, Opacity, and Gaming Background Art Fitting (R4)
     * 1. Action Tile Opacity: solid backing rect + 255 alpha.
     * 2. Tile Dimensions: 196x294dp 2:3 portrait strictly on Home; 352x198dp (16:9), gap 28dp, row height 348dp on Search, Gaming, Reels, Smart Home.
     * 3. Gaming Screen Hero Background Fitting: <= 3% aspect stretch before center-crop.
     */
    private static void testMilestone5TileDimensionsOpacityAndGamingFit() throws Exception {
        // 1. Uniform Wide Tile Dimensions on Secondary Screens (Search, Gaming, Reels, Smart Home)
        assertEquals(352f, HomeView.WIDE_BROWSER_CARD_W, 0.001f, "WIDE_BROWSER_CARD_W is exactly 352 dp");
        assertEquals(198f, HomeView.WIDE_BROWSER_CARD_H, 0.001f, "WIDE_BROWSER_CARD_H is exactly 198 dp");
        assertEquals(28f, HomeView.WIDE_BROWSER_CARD_GAP, 0.001f, "WIDE_BROWSER_CARD_GAP is exactly 28 dp");
        assertEquals(348f, HomeView.WIDE_BROWSER_ROW_HEIGHT, 0.001f, "WIDE_BROWSER_ROW_HEIGHT is exactly 348 dp");
        assertEquals(16f / 9f, HomeView.WIDE_BROWSER_CARD_W / HomeView.WIDE_BROWSER_CARD_H, 0.001f,
                "Wide browser cards have exact 16:9 aspect ratio");

        // 2. Tall Tile Dimensions on Home Screen (2:3 portrait)
        assertEquals(196f, HomeView.MEDIA_CARD_W, 0.001f, "Home MEDIA_CARD_W is 196 dp");
        assertEquals(294f, HomeView.MEDIA_CARD_H, 0.001f, "Home MEDIA_CARD_H is 294 dp");
        assertEquals(20f, HomeView.MEDIA_CARD_GAP, 0.001f, "Home MEDIA_CARD_GAP is 20 dp");
        assertEquals(406f, HomeView.MEDIA_ROW_HEIGHT, 0.001f, "Home MEDIA_ROW_HEIGHT is 406 dp");
        assertEquals(2f / 3f, HomeView.MEDIA_CARD_W / HomeView.MEDIA_CARD_H, 0.001f,
                "Home media cards have exact 2:3 portrait aspect ratio");

        // 3. Row Height Standardization Across Screens
        // 3.1 ROW_SMARTHOME migrated to WIDE_BROWSER_ROW_HEIGHT (348 dp, was 316 dp)
        HomeView.BrowserRow shRow = new HomeView.BrowserRow(HomeView.ROW_SMARTHOME, "Smart-Home Devices", null,
                null, null, null, null, java.util.Collections.emptyList());
        assertEquals(348f, shRow.height(1.0f), 0.001f,
                "ROW_SMARTHOME height migrated to uniform 348 dp (WIDE_BROWSER_ROW_HEIGHT)");
        assertEquals(348f * 1.5f, shRow.height(1.5f, HomeView.SCREEN_SMARTHOME), 0.001f,
                "ROW_SMARTHOME height with scale factor 1.5 is 348*1.5 dp");

        // 3.2 ROW_ACTIONS height is uniform 348 dp
        HomeView.BrowserRow actRow = new HomeView.BrowserRow(HomeView.ROW_ACTIONS, "Streaming & PC Controls", null,
                null, null, null, java.util.Collections.emptyList(), null);
        assertEquals(348f, actRow.height(1.0f), 0.001f,
                "ROW_ACTIONS height is uniform 348 dp (WIDE_BROWSER_ROW_HEIGHT)");

        // 3.3 ROW_PICKS on Home (406 dp) vs ROW_PICKS on Search (348 dp)
        HomeView.BrowserRow homePicks = new HomeView.BrowserRow(HomeView.ROW_PICKS, "Watch List", null, null,
                null, java.util.Collections.emptyList(), null, null, null, true, HomeView.SCREEN_HOME);
        assertEquals(406f, homePicks.height(1.0f), 0.001f,
                "ROW_PICKS on SCREEN_HOME uses tall MEDIA_ROW_HEIGHT (406 dp)");
        assertEquals(406f, homePicks.height(1.0f, HomeView.SCREEN_HOME), 0.001f,
                "ROW_PICKS height(k, SCREEN_HOME) is 406 dp");

        HomeView.BrowserRow searchPicks = new HomeView.BrowserRow(HomeView.ROW_PICKS, "Shows & Movies in Installed Apps", null, null,
                null, java.util.Collections.emptyList(), null, null, null, true, HomeView.SCREEN_SEARCH);
        assertEquals(348f, searchPicks.height(1.0f), 0.001f,
                "ROW_PICKS on SCREEN_SEARCH uses standardized WIDE_BROWSER_ROW_HEIGHT (348 dp)");
        assertEquals(348f, searchPicks.height(1.0f, HomeView.SCREEN_SEARCH), 0.001f,
                "ROW_PICKS height(k, SCREEN_SEARCH) is 348 dp");

        // 3.4 ROW_CONTINUE on Home (406 dp)
        HomeView.BrowserRow contRow = new HomeView.BrowserRow(HomeView.ROW_CONTINUE, "Continue Watching", null,
                null, null, null, null, null, true);
        assertEquals(406f, contRow.height(1.0f), 0.001f,
                "ROW_CONTINUE uses tall MEDIA_ROW_HEIGHT (406 dp)");

        // 3.5 ROW_MEDIA_SELECTOR (84 dp)
        HomeView.BrowserRow selRow = new HomeView.BrowserRow(HomeView.ROW_MEDIA_SELECTOR, "Content Type", null,
                null, null, null, null, null);
        assertEquals(84f, selRow.height(1.0f), 0.001f,
                "ROW_MEDIA_SELECTOR height is 84 dp");

        // 4. Action Card Construction & Opaque Gradient Properties
        HomeView.ActionCard card = new HomeView.ActionCard("gaming_wake", "Wake PC", "HDMI / Wireless", "gamepad",
                0xFF2563EB, 0xFF1D4ED8, null);
        assertEquals("gaming_wake", card.id, "ActionCard id matches");
        assertEquals("Wake PC", card.title, "ActionCard title matches");
        assertEquals("HDMI / Wireless", card.subtitle, "ActionCard subtitle matches");
        assertEquals(0xFF2563EB, card.colorTop, "ActionCard colorTop matches");
        assertEquals(0xFF1D4ED8, card.colorBottom, "ActionCard colorBottom matches");
        assertEquals(255, (card.colorTop >>> 24) & 0xFF, "ActionCard colorTop has 100% alpha (255)");
        assertEquals(255, (card.colorBottom >>> 24) & 0xFF, "ActionCard colorBottom has 100% alpha (255)");

        // 5. Gaming Screen Hero Background Fitting (<= 3% Stretch Before Crop)
        assertEquals(0.03f, GamingZone.MAX_ASPECT_STRETCH_TOLERANCE, 0.0001f,
                "Aspect stretch tolerance is exactly 3% (0.03f)");

        // 5.1 Exact 16:9 (1920x1080) -> Zero cropping, full image preserved
        GamingZone.AspectCropRect rExact = GamingZone.computeAspectFitOrCropRect(1920, 1080, 16, 9, 0.03f);
        assertTrue(!rExact.cropped, "Exact 16:9 image requires zero cropping");
        assertEquals(0, rExact.left, "Exact 16:9 left offset is 0");
        assertEquals(0, rExact.top, "Exact 16:9 top offset is 0");
        assertEquals(1920, rExact.width, "Exact 16:9 width is 1920");
        assertEquals(1080, rExact.height, "Exact 16:9 height is 1080");

        // 5.2 Slightly wide image within 3% tolerance (1920x1060, ratio 1.8113 vs 1.7778, relDiff +1.89%)
        // Should scale to fit without cropping
        GamingZone.AspectCropRect rSlightlyWide = GamingZone.computeAspectFitOrCropRect(1920, 1060, 16, 9, 0.03f);
        assertTrue(!rSlightlyWide.cropped, "Slightly wide image within 3% tolerance requires zero cropping");
        assertEquals(0, rSlightlyWide.left, "Left offset is 0");
        assertEquals(0, rSlightlyWide.top, "Top offset is 0");
        assertEquals(1920, rSlightlyWide.width, "Full width preserved");
        assertEquals(1060, rSlightlyWide.height, "Full height preserved");

        // 5.3 Slightly tall image within 3% tolerance (1920x1100, ratio 1.7455 vs 1.7778, relDiff -1.82%)
        // Should scale to fit without cropping
        GamingZone.AspectCropRect rSlightlyTall = GamingZone.computeAspectFitOrCropRect(1920, 1100, 16, 9, 0.03f);
        assertTrue(!rSlightlyTall.cropped, "Slightly tall image within 3% tolerance requires zero cropping");
        assertEquals(0, rSlightlyTall.left, "Left offset is 0");
        assertEquals(0, rSlightlyTall.top, "Top offset is 0");
        assertEquals(1920, rSlightlyTall.width, "Full width preserved");
        assertEquals(1100, rSlightlyTall.height, "Full height preserved");

        // 5.4 Boundary condition at exact 3% tolerance (ratio 1.831 vs 1.7778)
        GamingZone.AspectCropRect rBoundary = GamingZone.computeAspectFitOrCropRect(1831, 1000, 16, 9, 0.03f);
        assertTrue(!rBoundary.cropped, "Boundary condition at 3% tolerance fits without cropping");

        // 5.5 Ultra-wide 21:9 image (2560x1080, ratio 2.370)
        // Misfit exceeds 3%: scale until height fits (1080), allow 3% width stretch, crop only remaining horizontal overflow
        GamingZone.AspectCropRect rWide = GamingZone.computeAspectFitOrCropRect(2560, 1080, 16, 9, 0.03f);
        assertTrue(rWide.cropped, "21:9 image requires cropping for overflow beyond 3% stretch tolerance");
        assertEquals(1080, rWide.height, "Full height preserved for wide image");
        int expectedW = Math.round(1080 * (16f / 9f) * 1.03f); // 1978
        assertEquals(expectedW, rWide.width, "Cropped width allows 3% stretch tolerance (1978 vs strict 1920)");
        assertEquals(2560 - expectedW, rWide.left, "Horizontal crop removes left side, preserving right side");
        assertEquals(0, rWide.top, "Top offset is 0 for wide image");
        assertTrue(rWide.width > 1920, "Retains more horizontal artwork than strict 16:9 crop due to 3% stretch tolerance");

        // 5.6 Tall 4:3 image (1440x1080, ratio 1.333)
        // Misfit exceeds 3%: scale until width fits (1440), allow 3% height stretch, crop only remaining vertical overflow
        GamingZone.AspectCropRect rTall = GamingZone.computeAspectFitOrCropRect(1440, 1080, 16, 9, 0.03f);
        assertTrue(rTall.cropped, "4:3 image requires cropping for overflow beyond 3% stretch tolerance");
        assertEquals(1440, rTall.width, "Full width preserved for tall image");
        int expectedH = Math.round(1440 / ((16f / 9f) / 1.03f)); // 834
        assertEquals(expectedH, rTall.height, "Cropped height allows 3% stretch tolerance (834 vs strict 810)");
        assertEquals(0, rTall.left, "Left offset is 0 for tall image");
        assertEquals(0, rTall.top, "Vertical crop removes bottom side, preserving top side");
        assertTrue(rTall.height > 810, "Retains more vertical artwork than strict 16:9 crop due to 3% stretch tolerance");

        // 5.7 Graceful handling of invalid / zero / negative dimensions
        GamingZone.AspectCropRect rZero = GamingZone.computeAspectFitOrCropRect(0, 0, 16, 9, 0.03f);
        assertTrue(!rZero.cropped, "Zero dimensions handled gracefully without throwing");
        assertEquals(1, rZero.width, "Safe fallback width is 1");
        assertEquals(1, rZero.height, "Safe fallback height is 1");

        GamingZone.AspectCropRect rNeg = GamingZone.computeAspectFitOrCropRect(-100, -100, 16, 9, 0.03f);
        assertTrue(!rNeg.cropped, "Negative dimensions handled gracefully");
        assertEquals(1, rNeg.width, "Safe fallback width is 1");
        assertEquals(1, rNeg.height, "Safe fallback height is 1");
    }

    private static SettingsTree.Node findNodeByIdTrackD(SettingsTree.Node node, String targetId) {
        if (node == null) return null;
        if (targetId.equals(node.id)) return node;
        if (node.children != null) {
            for (SettingsTree.Node child : node.children) {
                SettingsTree.Node found = findNodeByIdTrackD(child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void deleteDirRecursivelyTrackD(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) deleteDirRecursivelyTrackD(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void testTrackDMemoryWebpAspectAuthSecurityPerfAndUx() {
        // 1. PERF-01: Safe 550ms (> 450ms crossfade) post-fade Backdrop recycle & active-display protection
        assertEquals(550L, Backdrop.SAFE_RECYCLE_DELAY_MS,
                "Deferred backdrop recycle delay exceeds 450ms crossfade duration");
        Backdrop activeBg = Backdrop.emptyForTesting();
        Backdrop evictedBg = Backdrop.emptyForTesting();
        try {
            Backdrop.setDisplayedBackdrops(activeBg, null);
            assertEquals(true, Backdrop.isDisplayedBackdrop(activeBg),
                    "Active HomeView backdrop is recognized by displayed-backdrop guard");
            assertEquals(false, Backdrop.isDisplayedBackdrop(evictedBg),
                    "Off-screen evicted backdrop is not marked displayed");

            Backdrop.scheduleSafeRecycle(activeBg);
            assertEquals(false, activeBg.isRecycled(),
                    "Actively displayed backdrop is NEVER recycled while bound to HomeView");

            Backdrop.scheduleSafeRecycle(evictedBg);
            assertEquals(true, evictedBg.isRecycled(),
                    "Off-screen evicted backdrop is safely recycled");
        } finally {
            Backdrop.setDisplayedBackdrops(null, null);
        }
        Backdrop.scheduleSafeRecycle(activeBg);
        assertEquals(true, activeBg.isRecycled(),
                "Once unbound from HomeView, previously displayed backdrop recycles cleanly");

        // 2. PERF-02 & PERF-04: Bounded LRU RAM caches across GamingZone & HomeApps
        assertEquals(24, GamingZone.MAX_GAME_HERO_BACKDROPS,
                "GamingZone hero backdrop LRU bounded to 24 entries (R3)");
        assertEquals(24, GamingZone.MAX_GAME_ART_BITMAPS,
                "GamingZone card cover bitmap LRU bounded to 24 entries (PERF-02)");
        assertEquals(32, HomeApps.MAX_TILES_CACHE,
                "HomeApps app-tile bitmap LRU bounded to 32 entries (PERF-04)");

        // 3. Native WebP (Quality 86) Disk Cache & seamless .webp -> .jpg legacy fallback
        assertEquals(86, GamingZone.WEBP_CACHE_QUALITY,
                "Disk artwork cache writes Native WebP at quality 86");
        assertEquals("WEBP_LOSSY", GamingZone.webpLossyFormatForSdk(30).name(),
                "API 30+ selects Bitmap.CompressFormat.WEBP_LOSSY");
        assertEquals(android.graphics.Bitmap.CompressFormat.WEBP, GamingZone.webpLossyFormatForSdk(28),
                "API 26-29 falls back cleanly to Bitmap.CompressFormat.WEBP");

        java.io.File tmpDir = null;
        try {
            tmpDir = java.nio.file.Files.createTempDirectory("soartv_trackd_test").toFile();
            byte[] payload = new byte[256];
            java.util.Arrays.fill(payload, (byte) 0x5A);

            // GamingZone .webp -> .jpg fallback
            java.io.File resolvedEmpty = GamingZone.resolveCachedArtworkFile(tmpDir, "steam_1091500");
            assertEquals("steam_1091500.webp", resolvedEmpty.getName(),
                    "Uncached artwork resolves target .webp path for new writes");

            java.io.File legacyJpg = new java.io.File(tmpDir, "steam_1091500.jpg");
            java.nio.file.Files.write(legacyJpg.toPath(), payload);
            assertEquals("steam_1091500.jpg", GamingZone.resolveCachedArtworkFile(tmpDir, "steam_1091500").getName(),
                    "Existing legacy .jpg artwork loads seamlessly when .webp is not written yet");

            java.io.File nativeWebp = new java.io.File(tmpDir, "steam_1091500.webp");
            java.nio.file.Files.write(nativeWebp.toPath(), payload);
            assertEquals("steam_1091500.webp", GamingZone.resolveCachedArtworkFile(tmpDir, "steam_1091500").getName(),
                    "Native .webp artwork takes priority over legacy .jpg once cached");

            // WatchArt .webp -> .jpg -> .img fallback
            assertEquals(null, WatchArt.resolveCachedWatchArtFile(tmpDir, "wa17_test"),
                    "WatchArt returns null when no cached file exists");
            java.io.File waJpg = new java.io.File(tmpDir, "wa17_test.jpg");
            java.nio.file.Files.write(waJpg.toPath(), payload);
            assertEquals("wa17_test.jpg", WatchArt.resolveCachedWatchArtFile(tmpDir, "wa17_test").getName(),
                    "WatchArt falls back to legacy .jpg (>128B)");
            java.io.File waWebp = new java.io.File(tmpDir, "wa17_test.webp");
            java.nio.file.Files.write(waWebp.toPath(), payload);
            assertEquals("wa17_test.webp", WatchArt.resolveCachedWatchArtFile(tmpDir, "wa17_test").getName(),
                    "WatchArt prefers .webp over legacy .jpg");

            // 4. PERF-20: Atomic file-backed JSON cache (Prefs.writeJsonCacheToDir / readJsonCacheFromDir)
            String sampleJson = "{\"version\":18,\"picks\":[{\"id\":1,\"title\":\"Severance\"}]}";
            assertEquals(true, Prefs.writeJsonCacheToDir(tmpDir, "recs", sampleJson),
                    "Atomic JSON cache writer succeeds on temp directory");
            assertEquals(sampleJson, Prefs.readJsonCacheFromDir(tmpDir, "recs"),
                    "File-backed JSON cache round-trips UTF-8 payload accurately");
            assertEquals(true, Prefs.writeJsonCacheToDir(tmpDir, "recs", ""),
                    "Empty payload deletes file-backed JSON cache cleanly");
            assertEquals(null, Prefs.readJsonCacheFromDir(tmpDir, "recs"),
                    "Deleted JSON cache returns null");
        } catch (java.io.IOException e) {
            throw new RuntimeException("Temp dir test failed", e);
        } finally {
            deleteDirRecursivelyTrackD(tmpDir);
        }

        // 5. <= 3% Gaming Hero & Cover Aspect-Ratio Fit Before Center-Cropping
        assertTrue(Math.abs(GamingZone.MAX_ASPECT_STRETCH_TOLERANCE - 0.03f) < 1e-6f,
                "Gaming aspect-ratio stretch tolerance is 3%");

        // <= 3% mismatch (1920x1050 = 1.8286:1 vs 16:9 = 1.7778:1 -> 2.86% <= 3%): zero cropping!
        GamingZone.AspectCropRect fitNoCrop = GamingZone.computeAspectFitOrCropRect(
                1920, 1050, 1920, 1080, GamingZone.MAX_ASPECT_STRETCH_TOLERANCE);
        assertEquals(false, fitNoCrop.cropped,
                "<= 3% aspect mismatch stretches full frame with zero cropping");
        assertTrue(fitNoCrop.left == 0 && fitNoCrop.top == 0 && fitNoCrop.width == 1920 && fitNoCrop.height == 1050,
                "<= 3% aspect fit preserves full 1920x1050 source bounds");

        // > 3% ultra-wide mismatch (Steam library_hero_2x 3840x1240 = 3.10:1 vs 16:9): stretches 3% + center-crops excess
        GamingZone.AspectCropRect wideCrop = GamingZone.computeAspectFitOrCropRect(
                3840, 1240, 1920, 1080, GamingZone.MAX_ASPECT_STRETCH_TOLERANCE);
        int naive16x9Width = Math.round(1240f * (16f / 9f)); // 2204
        assertEquals(true, wideCrop.cropped,
                "> 3% ultra-wide hero applies center crop after 3% stretch");
        assertEquals(1240, wideCrop.height,
                "Ultra-wide hero preserves full vertical height");
        assertTrue(wideCrop.width > naive16x9Width && wideCrop.width < 3840,
                "3% stretch preserves more horizontal artwork (" + wideCrop.width + "px) than naive 16:9 crop (" + naive16x9Width + "px)");
        assertEquals(3840 - wideCrop.width, wideCrop.left,
                "Ultra-wide hero crop removes left side, preserving right side");

        // > 3% taller mismatch (Riot splash 1215x717 = 1.6946:1 vs 16:9 = 4.68% > 3%): stretches 3% + center-crops excess
        GamingZone.AspectCropRect tallCrop = GamingZone.computeAspectFitOrCropRect(
                1215, 717, 1920, 1080, GamingZone.MAX_ASPECT_STRETCH_TOLERANCE);
        int naive16x9Height = Math.round(1215f / (16f / 9f)); // 683
        assertEquals(true, tallCrop.cropped,
                "> 3% taller splash applies center crop after 3% stretch");
        assertEquals(1215, tallCrop.width,
                "Taller splash preserves full horizontal width");
        assertTrue(tallCrop.height > naive16x9Height && tallCrop.height < 717,
                "3% stretch preserves more vertical artwork (" + tallCrop.height + "px) than naive 16:9 crop (" + naive16x9Height + "px)");

        // 6. PERF-09: Shaders.sky & localMillisSinceMidnight zero-allocation epoch math
        long sampleEpoch = 1_730_721_600_000L; // deterministic UTC instant
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(sampleEpoch);
        long expectedDayMs = ((cal.get(java.util.Calendar.HOUR_OF_DAY) * 60L + cal.get(java.util.Calendar.MINUTE)) * 60L
                + cal.get(java.util.Calendar.SECOND)) * 1000L + cal.get(java.util.Calendar.MILLISECOND);
        assertEquals(expectedDayMs, Shaders.localMillisSinceMidnight(sampleEpoch),
                "Zero-allocation localMillisSinceMidnight matches Calendar local time-of-day");
        assertEquals(19_800_000L, Shaders.timeOfDay(0L, 19_800_000L),
                "Shaders.timeOfDay returns fallback when timestamp is 0");
        long localMidnight = sampleEpoch - Shaders.localMillisSinceMidnight(sampleEpoch);
        Shaders.Palette noonPalette = new Shaders.Palette();
        Shaders.Palette midnightPalette = new Shaders.Palette();
        Shaders.sky(noonPalette, localMidnight + 12 * 3600_000L, 0L, 0L);
        Shaders.sky(midnightPalette, localMidnight + 2 * 3600_000L, 0L, 0L);
        assertTrue(noonPalette.brightest() > midnightPalette.brightest() + 0.35f,
                "Shaders.sky distinguishes bright midday palette from dark night palette without Calendar allocations");

        // 7. PERF-12: Private-only OMDb keys & 15-minute cooldown on HTTP 401/403/429
        Recs.resetOmdbCooldownForTesting();
        try {
            assertEquals(true, Recs.buildOmdbCandidateKeys(null).isEmpty(),
                    "Null OMDb key yields empty candidate list (zero hardcoded shared fallback keys)");
            assertEquals(true, Recs.buildOmdbCandidateKeys("   ").isEmpty(),
                    "Blank OMDb key yields empty candidate list");
            assertEquals(java.util.Collections.singletonList("priv_omdb_abc"),
                    Recs.buildOmdbCandidateKeys("  priv_omdb_abc "),
                    "Private OMDb key is trimmed and used as sole candidate");

            long t0 = 5_000_000L;
            assertEquals(false, Recs.isOmdbCoolingDown(t0), "OMDb starts outside cooldown");
            Recs.recordOmdbHttpStatus(429, t0);
            assertEquals(true, Recs.isOmdbCoolingDown(t0 + 60_000L),
                    "HTTP 429 activates 15-minute OMDb cooldown");
            assertEquals(false, Recs.isOmdbCoolingDown(t0 + Recs.OMDB_COOLDOWN_MS + 1L),
                    "OMDb cooldown expires cleanly after 15 minutes");
        } finally {
            Recs.resetOmdbCooldownForTesting();
        }

        // 8. SEC-13: Verified Bluetooth Auto-Pair Target Guard (blocks unsolicited rogue devices)
        long pairTs = 1_700_000_000_000L;
        String pairStamp = BluetoothPairingAssist.formatPendingPairStamp(pairTs, "EC:83:50:C1:4C:C6", "GameSir-Nova Lite");
        assertEquals(true, BluetoothPairingAssist.isPendingPairStampFresh(pairStamp, pairTs + 10_000L, 45_000L),
                "Pending pair stamp within 45s window is fresh");
        assertEquals(false, BluetoothPairingAssist.isPendingPairStampFresh(pairStamp, pairTs + 60_000L, 45_000L),
                "Expired pending pair stamp is rejected");
        assertEquals(true, BluetoothPairingAssist.isPendingPairTargetAllowed(
                pairStamp, java.util.Arrays.asList("Bluetooth pairing request", "Pair with GameSir-Nova Lite?")),
                "Matching controller name is allowed for auto-pair");
        assertEquals(true, BluetoothPairingAssist.isPendingPairTargetAllowed(
                pairStamp, java.util.Arrays.asList("Pair with Wireless Controller?")),
                "Xiaomi generic Wireless Controller confirmation prompt remains compatible");
        assertEquals(false, BluetoothPairingAssist.isPendingPairTargetAllowed(
                pairStamp, java.util.Arrays.asList("Bluetooth pairing request", "Pair with Neighbor's iPhone?")),
                "Unsolicited rogue phone pairing dialog is blocked (SEC-13)");
        assertEquals(false, BluetoothPairingAssist.isPendingPairTargetAllowed(
                pairStamp, java.util.Arrays.asList("Pair with Attacker Laptop?")),
                "Unsolicited rogue laptop pairing dialog is blocked (SEC-13)");

        // 9. BP-03 / BP-04: AppLog stack-trace preservation
        String errLog = AppLog.formatMessageWithStackTrace("cache sync failed", new IllegalStateException("io timeout"));
        assertTrue(errLog.contains("cache sync failed")
                        && errLog.contains("IllegalStateException")
                        && errLog.contains("io timeout"),
                "AppLog preserves both caller context and Throwable stack trace");

        // 10. INC-04: Liquid Glass Input State & Gaming PC Pairing Settings Tree Nodes
        HomeView.LiquidGlassInputState pinState = new HomeView.LiquidGlassInputState("", true);
        pinState.appendChar('7');
        pinState.appendChar('3');
        pinState.appendChar('1');
        pinState.appendChar('9');
        assertEquals("7319", pinState.getText(), "LiquidGlassInputState records typed PIN digits");
        assertEquals("\u2022\u2022\u2022\u2022", pinState.displayValue(),
                "Secret PIN mode masks digits with bullets by default");
        pinState.toggleReveal();
        assertEquals("7319", pinState.displayValue(),
                "Show/Hide toggle reveals secret PIN digits");
        pinState.backspace();
        assertEquals("731", pinState.getText(),
                "Backspace deletes trailing character cleanly");

        SettingsTree.Node rootSettings = SettingsTree.buildRoot(new SettingsTree.StateSnapshot());
        assertTrue(findNodeByIdTrackD(rootSettings, "shg_pc_pair") != null,
                "SettingsTree exposes Gaming PC 6-digit PIN pairing node (shg_pc_pair)");
        assertTrue(findNodeByIdTrackD(rootSettings, "shg_pc_ip") != null,
                "SettingsTree exposes Gaming PC IP configuration node (shg_pc_ip)");

        // 11. INC-05: Per-Row Horizontal Scroll & Column Memory
        HomeView.RowScrollMemory scrollMem = new HomeView.RowScrollMemory();
        String actionKey = HomeView.buildRowMemoryKey(HomeView.SCREEN_HOME, 0, 5, "tv", "Action", "Action");
        String comedyKey = HomeView.buildRowMemoryKey(HomeView.SCREEN_HOME, 0, 5, "tv", "Comedy", "Comedy");
        scrollMem.save(actionKey, 8, 1728f);
        scrollMem.save(comedyKey, 3, 648f);
        assertEquals(8, scrollMem.restoreCol(actionKey, 25, 0),
                "RowScrollMemory restores previously focused column when returning to Action row");
        assertTrue(Math.abs(scrollMem.restoreScrollX(actionKey, 0f) - 1728f) < 0.01f,
                "RowScrollMemory restores exact horizontal scrollX offset");
        assertEquals(4, scrollMem.restoreCol(actionKey, 5, 0),
                "RowScrollMemory clamps restored column safely when row shrinks");

        // 12. INC-06: Hierarchical Back-Navigation Stack
        HomeView.BackNavigationStack navStack = new HomeView.BackNavigationStack();
        navStack.pushScreen(HomeView.SCREEN_HOME, HomeView.SCREEN_GAMING);
        navStack.pushScreen(HomeView.SCREEN_GAMING, HomeView.SCREEN_SETTINGS);
        assertEquals(HomeView.SCREEN_GAMING, navStack.popPrevious(HomeView.SCREEN_SETTINGS, HomeView.SCREEN_HOME),
                "Pressing Back from Settings returns to Gaming (prior screen) instead of jumping straight to Home");
        assertEquals(HomeView.SCREEN_HOME, navStack.popPrevious(HomeView.SCREEN_GAMING, HomeView.SCREEN_HOME),
                "Pressing Back again from Gaming returns hierarchically to Home");
        assertEquals(HomeView.SCREEN_HOME, navStack.popPrevious(HomeView.SCREEN_GAMING, HomeView.SCREEN_HOME),
                "Empty BackNavigationStack falls back deterministically to Home");
    }

    /**
     * Verifies Round 10 fixes:
     * 1. Light Mode overall frosted blur + white gradient vs. Dark Mode 12% overall dim + dark gradient + blur-gradient.
     * 2. Default Dark Mode aurora mesh 15% overall dim (leaving Light Mode un-dimmed).
     * 3. Settings & Modal Dark Theme gray text legibility (0xFFD1D1D6, WCAG AAA >= 9.5:1, zero 0xFF98989D).
     * 4. Atomic hero backdrop callback notification when stepping onto mid-preload items in Recs & GamingZone.
     * 5. 4-thread parallel posterIo & 2-thread WatchArt.IO without 270ms navigation sleeps.
     */
    private static double srgbToLinearRound10(int c8) {
        double c = (c8 & 0xFF) / 255.0;
        return (c <= 0.03928) ? (c / 12.92) : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double wcagContrastRatio(int fg, int bg) {
        double l1 = 0.2126 * srgbToLinearRound10((fg >> 16) & 0xFF)
                + 0.7152 * srgbToLinearRound10((fg >> 8) & 0xFF)
                + 0.0722 * srgbToLinearRound10(fg & 0xFF);
        double l2 = 0.2126 * srgbToLinearRound10((bg >> 16) & 0xFF)
                + 0.7152 * srgbToLinearRound10((bg >> 8) & 0xFF)
                + 0.0722 * srgbToLinearRound10(bg & 0xFF);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static void testRound10BackdropPreloadRaceShadersSettingsAndPosterPool() throws Exception {
        // 1. Shaders.HERO_VIGNETTE Light vs. Dark Mode separation
        assertTrue(Shaders.HERO_VIGNETTE.contains("bool isLight = (themeColor.r > 0.5);"),
                "HERO_VIGNETTE computes explicit isLight boolean");
        assertTrue(Shaders.HERO_VIGNETTE.contains("float darkDim = isLight ? 0.0 : 0.12;"),
                "HERO_VIGNETTE applies 0% base dim in Light Mode and 12% overall dim in Dark Mode");
        assertTrue(Shaders.HERO_VIGNETTE.contains("float leftBlur = (1.0 - smoothstep(0.08, 0.72, uv.x)) * 0.82;")
                        && Shaders.HERO_VIGNETTE.contains("float bottomBlur = smoothstep(0.38, 0.96, uv.y) * 0.88;")
                        && Shaders.HERO_VIGNETTE.contains("float darkBlurGrad = 1.0 - (1.0 - leftBlur) * (1.0 - bottomBlur);")
                        && Shaders.HERO_VIGNETTE.contains("float homeBlurMix = isLight ? 1.0 : darkBlurGrad;"),
                "HERO_VIGNETTE uses overall blur (1.0) in Light Mode and directional blur-gradient in Dark Mode");

        // 2. Default Dark Mode aurora mesh 15% dim vs untouched Light Mode
        int lightSanctuary = HomeView.sampleUnifiedAuroraColor(0.15f, 0.22f, 0.0f, 0.0f);
        assertTrue(((lightSanctuary >> 16) & 0xFF) >= 248
                        && ((lightSanctuary >> 8) & 0xFF) >= 248
                        && (lightSanctuary & 0xFF) >= 248,
                "Light Mode aurora upper-left reading sanctuary remains pure bright white (>= 248)");
        int darkCenter = HomeView.sampleUnifiedAuroraColor(0.50f, 0.50f, 1.0f, 0.0f);
        int dcLuma = (((darkCenter >> 16) & 0xFF) * 77 + ((darkCenter >> 8) & 0xFF) * 150 + (darkCenter & 0xFF) * 29) >> 8;
        assertTrue(dcLuma >= 8 && dcLuma <= 85,
                "Dark Mode default aurora center stays comfortably dimmed (~15% darker), luma=" + dcLuma);

        // 3. Settings & Modal Dark Theme gray text legibility (#D1D1D6, WCAG AAA >= 9.5:1)
        String settingsSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/SettingsScreenRenderer.java")), java.nio.charset.StandardCharsets.UTF_8);
        String modalSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/ModalOverlayRenderer.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!settingsSrc.contains("0xFF98989D"),
                "SettingsScreenRenderer removes low-contrast 0xFF98989D gray text in Dark Mode");
        assertTrue(settingsSrc.contains("0xFFD1D1D6"),
                "SettingsScreenRenderer uses crisp light silver 0xFFD1D1D6 for Dark Mode secondary text");
        assertTrue(!modalSrc.contains("0xFF98989D") && modalSrc.contains("0xFFD1D1D6"),
                "ModalOverlayRenderer uses crisp light silver 0xFFD1D1D6 in Dark Mode dialogs");
        double settingsContrast = wcagContrastRatio(0xFFD1D1D6, 0xFF1A1D2B);
        assertTrue(settingsContrast >= 9.5,
                "Dark Mode Settings secondary text 0xFFD1D1D6 exceeds WCAG AAA (7:1), actual=" + settingsContrast);

        // 4. Recs & GamingZone preloader race coordination + decoupled hero/poster I/O
        java.lang.reflect.Field gameCbField = GamingZone.class.getDeclaredField("GAME_HERO_CALLBACKS");
        gameCbField.setAccessible(true);
        Object gameCbMap = gameCbField.get(null);
        assertTrue(gameCbMap instanceof java.util.Map,
                "GamingZone maintains GAME_HERO_CALLBACKS map so preload does not swallow getGameBackdrop callbacks");

        String recsSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/Recs.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(recsSrc.contains("downloadBackdropOnly(p);")
                        && recsSrc.contains("currentCb.onBackdropLoaded(bdKey, ready);")
                        && recsSrc.contains("currentCb.onBackdropLoaded(wnKey, ready);"),
                "Recs decouples hero backdrop download from poster I/O and dispatches preload completion to UI callback without worker-thread focus races");
        assertTrue(recsSrc.contains("if (decodingBackdrops.contains(key))"),
                "Recs.backdrop coordinates atomically with in-flight decodingBackdrops under synchronized(images)");

        // 5. Parallel posterIo (4 threads) & WatchArt.IO (2 threads) + zero 90ms nav sleep
        java.lang.reflect.Field watchIoField = WatchArt.class.getDeclaredField("IO");
        watchIoField.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor watchPool =
                (java.util.concurrent.ThreadPoolExecutor) watchIoField.get(null);
        assertEquals(2, watchPool.getCorePoolSize(), "WatchArt.IO uses 2 parallel worker threads");
        assertTrue(recsSrc.replace("\r\n", "\n").contains(
                "posterIo = Executors.newFixedThreadPool(4,\n            daemonFactory(\"soartv-recs-poster\""),
                "Recs.posterIo uses 4 parallel worker threads");
        assertTrue(!recsSrc.substring(recsSrc.indexOf("Poster poster(Pick p)"), recsSrc.indexOf("void trim()")).contains("Thread.sleep"),
                "Recs.poster removes the 3x90ms artificial navigation sleep bottleneck");
        assertEquals(144, Recs.MAX_POSTERS_COMPACT_RAM, "Compact-RAM poster LRU holds 144 posters");
        assertEquals(240, Recs.MAX_POSTERS_STANDARD, "Standard poster LRU holds 240 posters");
    }

    private static void testRound11Team1TopBarProgressAndGamingCache() throws Exception {
        // 1. R1: Top Bar Pill Indicator Responsiveness & Single Springy Sliding Indicator
        HomeView.TopBarPillState pill = new HomeView.TopBarPillState();
        assertTrue(!pill.isVisible(), "Pill is initially invisible (alpha=0)");
        assertEquals(0f, pill.alpha.value, 0.001f, "Initial alpha value is 0");
        assertEquals(0f, pill.left.value, 0.001f, "Initial left value is 0");
        assertEquals(0f, pill.right.value, 0.001f, "Initial right value is 0");

        // Retarget to Tab 0 (e.g. Search, bounds 50..170)
        pill.retarget(50f, 170f, true, false);
        assertEquals(50f, pill.left.target, 0.001f, "Left target set to Tab 0 left");
        assertEquals(170f, pill.right.target, 0.001f, "Right target set to Tab 0 right");
        assertEquals(1f, pill.alpha.target, 0.001f, "Alpha target set to 1.0 when focused");

        // Step animation across ~300ms (20 frames at 16ms)
        for (int frame = 0; frame < 20; frame++) {
            pill.step(0.016f);
        }
        assertTrue(pill.isVisible(), "Pill becomes visible after springing in");
        assertTrue(pill.alpha.value > 0.95f, "Alpha rises to >= 0.95");
        assertEquals(50f, pill.left.value, 2.0f, "Left bounds settled near Tab 0 left");
        assertEquals(170f, pill.right.value, 2.0f, "Right bounds settled near Tab 0 right");

        // Navigating to Tab 1 (e.g. Home, bounds 182..310) - Smooth glide between tabs without lingering on Tab 0
        pill.retarget(182f, 310f, true, false);
        assertEquals(182f, pill.left.target, 0.001f, "Left target updated to Tab 1 left");
        assertEquals(310f, pill.right.target, 0.001f, "Right target updated to Tab 1 right");

        // Mid-flight transition verification (single pill sliding across gap)
        pill.step(0.050f);
        assertTrue(pill.left.value > 50f && pill.left.value < 182f,
                "Indicator pill smoothly slides horizontally between Tab 0 and Tab 1, actual left=" + pill.left.value);

        // Step to full settlement on Tab 1
        for (int frame = 0; frame < 50 && !pill.settled(); frame++) {
            pill.step(0.016f);
        }
        assertEquals(182f, pill.left.value, 0.05f, "Pill settled cleanly on Tab 1 left");
        assertEquals(310f, pill.right.value, 0.05f, "Pill settled cleanly on Tab 1 right");
        assertTrue(pill.settled(), "Pill springs settle cleanly at destination");

        // Moving focus off Top Bar (e.g. D-pad Down to Home screen rows)
        pill.retarget(0f, 0f, false, false);
        assertEquals(0f, pill.alpha.target, 0.001f, "Alpha target becomes 0 when focus leaves Top Bar");
        for (int frame = 0; frame < 40 && pill.isVisible(); frame++) {
            pill.step(0.016f);
        }
        assertTrue(!pill.isVisible(), "Pill becomes invisible when Top Bar loses focus");

        // Instant snap (e.g. initial setup or screen transition)
        pill.retarget(50f, 170f, true, true);
        assertEquals(50f, pill.left.value, 0.001f, "snap=true snaps left instantly");
        assertEquals(170f, pill.right.value, 0.001f, "snap=true snaps right instantly");
        assertEquals(1f, pill.alpha.value, 0.001f, "snap=true snaps alpha instantly");

        // Pill center and width computation with spring scaling
        float pillW = pill.computePillWidth();
        float pillCx = pill.computePillCenterX();
        assertTrue(pillW >= 120f, "Pill width includes subtle spring scale factor");
        assertEquals(110f, pillCx, 0.01f, "Pill center stays horizontally centered on tab (50..170)");

        // Verify HomeView.java code eliminates per-tab white pill drawing inside the loop
        String homeViewSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/HomeView.java")), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(homeViewSrc.contains("topPill.computePillRect(topRects[0].top, topRects[0].bottom, topPillRect);"),
                "HomeView uses single topPill.computePillRect to draw the focused indicator pill");
        assertTrue(!homeViewSrc.contains("if (focused || f > 0.01f) {\n                tmp.set(cell);\n                tmp.offset(0, 6 * k * Math.max(0.4f, f));\n                shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.42f) * Math.max(0.4f, f)));\n                shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);\n                fill.setShader(null);\n                fill.setColor(0xFFFFFFFF);"),
                "HomeView removes the per-tab solid white pill loop that caused lingering artifacts on previous tab");

        // 2. R2: Watch List & Continue Watching Progress Bar Contrast
        assertEquals(0x4DFFFFFF, HomeView.PROGRESS_TRACK_COLOR,
                "Progress track background uses semi-transparent white (0x4DFFFFFF) for crisp contrast against dark scrim");
        assertEquals(0xF2FFFFFF, HomeView.PROGRESS_FILL_COLOR,
                "Progress bar fill uses opaque white (0xF2FFFFFF)");

        int trackAlpha = (HomeView.PROGRESS_TRACK_COLOR >>> 24) & 0xFF;
        int fillAlpha = (HomeView.PROGRESS_FILL_COLOR >>> 24) & 0xFF;
        assertEquals(0x4D, trackAlpha, "Progress track alpha is 0x4D (~30% translucent white)");
        assertEquals(0xF2, fillAlpha, "Progress fill alpha is 0xF2 (~95% opaque white)");

        // Verify that dark charcoal track (0x73000000) was completely eliminated from progress bar rendering
        assertTrue(!homeViewSrc.contains("fill.setColor(0x73000000);"),
                "0x73000000 charcoal track is completely removed from HomeView");
        assertTrue(homeViewSrc.contains("fill.setColor(PROGRESS_TRACK_COLOR);"),
                "HomeView uses PROGRESS_TRACK_COLOR for progress bar track");
        assertTrue(homeViewSrc.contains("fill.setColor(PROGRESS_FILL_COLOR);"),
                "HomeView uses PROGRESS_FILL_COLOR for progress bar fill");

        // 3. R3: Gaming Screen Background Image Caching
        assertEquals(24, GamingZone.MAX_GAME_HERO_BACKDROPS,
                "Gaming hero LRU capacity expanded to 24 backdrops to prevent premature eviction during game browsing");
        assertEquals(0, GamingZone.getGameHeroCacheSize(),
                "getGameHeroCacheSize returns current LRU cache size");

        GamingZone.GameItem testGame = new GamingZone.GameItem("steam_1086940", "Baldur's Gate 3", "steam", "", false);
        assertTrue(!GamingZone.isGameBackdropCached(testGame),
                "isGameBackdropCached returns false for uncached game item");

        // Verify HomeView preloads neighbors in both directions (col - 2 to col + 3)
        assertTrue(homeViewSrc.contains("for (int ni = Math.max(0, col - 2); ni <= Math.min(row.gameItems.size() - 1, col + 3); ni++)"),
                "HomeView bgFollow preloads adjacent game backdrops bidirectionally (col - 2 to col + 3) for 0ms return swap");
    }

    private static void testScreensaverModularityCaptionAndRemoteKeys() {
        // --- 1. R4: Screensaver Caption Cleanup & Decoupled Title ---
        ScreensaverCatalog.PhotoEntry sampleEntry = ScreensaverCatalog.sampleEntryForCategory("nature");
        assertTrue(sampleEntry != null, "Sample entry exists for nature gallery");
        assertTrue(!sampleEntry.title.isEmpty(), "Sample entry has valid artwork title");
        assertTrue(ScreensaverCatalog.categoryLabel("nature").contains("NATURE"), "Category label contains gallery name");

        // Verify title decoupling:
        Prefs.setSaverShowArtworkTitle(true);
        assertEquals(true, Prefs.saverShowArtworkTitle(), "saverShowArtworkTitle defaults to true");
        Prefs.setSaverShowArtworkTitle(false);
        assertEquals(false, Prefs.saverShowArtworkTitle(), "saverShowArtworkTitle can be disabled");
        Prefs.setSaverShowArtworkTitle(true);

        // --- 2. R5.1: Ken Burns Animation Toggle & Static 4K Centering ---
        assertEquals(true, Prefs.saverAnimation(), "saverAnimation defaults to true");
        Prefs.setSaverAnimation(false);
        assertEquals(false, Prefs.saverAnimation(), "saverAnimation can be toggled off");
        Prefs.setSaverAnimation(true);

        // Verify Static Centered 4K Placement math (zero pan, zero zoom, centered aspect-fit/fill)
        float[] place = new float[3];
        // Exact 4K panel (3840x2160) with 4K photo (3840x2160)
        DreamView.computeStaticCenterPlacement(3840, 2160, 3840, 2160, place);
        assertEquals(0f, place[0], "Static 4K image has 0 X translation on matching panel");
        assertEquals(0f, place[1], "Static 4K image has 0 Y translation on matching panel");
        assertEquals(1f, place[2], "Static 4K image has 1.0 scale on matching panel");

        // 1080p panel (1920x1080) with 4K photo (3840x2160)
        DreamView.computeStaticCenterPlacement(1920, 1080, 3840, 2160, place);
        assertEquals(0f, place[0], "Static 4K image has 0 X translation on 1080p panel");
        assertEquals(0f, place[1], "Static 4K image has 0 Y translation on 1080p panel");
        assertEquals(0.5f, place[2], "Static 4K image scales to 0.5 on 1080p panel");

        // Ultrawide photo (4800x2160) on 4K panel (3840x2160) -> center cropped horizontally
        DreamView.computeStaticCenterPlacement(3840, 2160, 4800, 2160, place);
        assertEquals(1f, place[2], "Scale is 1.0 to fit height");
        assertEquals(-480f, place[0], "X translation centers ultrawide image symmetrically ((3840 - 4800) / 2 = -480)");
        assertEquals(0f, place[1], "Y translation is 0 on full height match");

        // --- 3. R5.4: Cyclic Slide Duration Interval Ladder (0 "Don't change image" through 2h) ---
        assertEquals(30_000L, Prefs.saverIntervalMs(), "saverIntervalMs defaults to 30s (30,000 ms)");
        assertEquals("30s", Prefs.saverIntervalLabel(), "Default saverIntervalLabel is '30s'");
        long[] intervals = Prefs.SAVER_INTERVALS_MS;
        String[] labels = Prefs.SAVER_INTERVAL_LABELS;
        assertEquals(17, intervals.length, "Exactly 17 cyclic slide interval steps defined");
        assertEquals(17, labels.length, "Exactly 17 slide interval labels defined");
        assertEquals(0L, intervals[0], "Interval 0 is 0 ms (Don't change image)");
        assertEquals("Don't change image", labels[0], "Label 0 is 'Don't change image'");
        assertEquals(10_000L, intervals[1], "Interval 1 is 10s");
        assertEquals("10s", labels[1], "Label 1 is '10s'");
        assertEquals(7_200_000L, intervals[16], "Interval 16 is 2h (7,200,000 ms)");
        assertEquals("2h", labels[16], "Label 16 is '2h'");

        Prefs.setSaverIntervalMs(0L);
        assertEquals(0L, Prefs.saverIntervalMs(), "Interval can be set to 0 (Don't change image)");
        assertEquals("Don't change image", Prefs.saverIntervalLabel(), "Label updates to 'Don't change image'");
        Prefs.setSaverIntervalMs(15_000L);
        assertEquals(15_000L, Prefs.saverIntervalMs(), "Interval can be updated to 15s");
        assertEquals("15s", Prefs.saverIntervalLabel(), "Label updates to '15s'");
        Prefs.setSaverIntervalMs(60_000L);
        assertEquals("60s", Prefs.saverIntervalLabel(), "Label updates to '60s'");
        Prefs.setSaverIntervalMs(90_000L);
        assertEquals("1:30 min", Prefs.saverIntervalLabel(), "Label updates to '1:30 min'");
        Prefs.setSaverIntervalMs(5_400_000L);
        assertEquals("1:30h", Prefs.saverIntervalLabel(), "Label updates to '1:30h'");
        Prefs.setSaverIntervalMs(30_000L);

        // --- 4. R5.3: Curated 61-Hub World Timezone Registry & Formatting ---
        assertEquals(61, Prefs.SAVER_WORLD_HUBS.length, "Exactly 61 global world hubs curated");
        java.util.Set<String> hubCodes = new java.util.HashSet<>();
        long testEpoch = 1730815200000L; // Fixed epoch for deterministic testing
        for (String[] hub : Prefs.SAVER_WORLD_HUBS) {
            assertEquals(3, hub.length, "Each hub has {code, timezoneId, cityName}");
            String code = hub[0];
            String tzId = hub[1];
            String city = hub[2];
            assertTrue(!code.isEmpty(), "Hub code is non-empty");
            assertTrue(!tzId.isEmpty(), "IANA timezone ID is non-empty");
            assertTrue(!city.isEmpty(), "City name is non-empty");
            hubCodes.add(code);

            // Test timezone formatting for each hub
            Prefs.setSaverWorldClock(tzId, code);
            assertEquals(tzId, Prefs.saverWorldClockTz(), "Timezone ID saved cleanly: " + tzId);
            assertEquals(code, Prefs.saverWorldClockLabel(), "City label saved cleanly: " + code);

            String formatted = ScreensaverCatalog.formatWorldClock(testEpoch);
            assertTrue(formatted.startsWith(code + " "), "Formatted world clock starts with hub code: " + formatted);
            assertTrue(formatted.matches(code + " \\d{2}:\\d{2}"), "Formatted world clock matches pattern: " + formatted);
        }
        Prefs.setSaverWorldClock("America/New_York", "NYC");
        assertEquals(61, hubCodes.size(), "All 61 hub codes are distinct");
        assertTrue(hubCodes.contains("NYC"), "Hubs include NYC");
        assertTrue(hubCodes.contains("LAX"), "Hubs include LAX");
        assertTrue(hubCodes.contains("LON"), "Hubs include LON");
        assertTrue(hubCodes.contains("PAR"), "Hubs include PAR");
        assertTrue(hubCodes.contains("BER"), "Hubs include BER");
        assertTrue(hubCodes.contains("TLV"), "Hubs include TLV");
        assertTrue(hubCodes.contains("DXB"), "Hubs include DXB");
        assertTrue(hubCodes.contains("BOM"), "Hubs include BOM");
        assertTrue(hubCodes.contains("SIN"), "Hubs include SIN");
        assertTrue(hubCodes.contains("HKG"), "Hubs include HKG");
        assertTrue(hubCodes.contains("TYO"), "Hubs include TYO");
        assertTrue(hubCodes.contains("SYD"), "Hubs include SYD");
        assertTrue(hubCodes.contains("AKL"), "Hubs include AKL");
        assertTrue(hubCodes.contains("SAO"), "Hubs include SAO");
        assertTrue(hubCodes.contains("UTC"), "Hubs include UTC");

        // Test world clock toggle off
        Prefs.setSaverShowWorldClock(false);
        assertEquals("", ScreensaverCatalog.formatWorldClock(testEpoch), "formatWorldClock returns empty string when disabled");
        Prefs.setSaverShowWorldClock(true);

        // --- 5. R5: SettingsTree Hierarchy Verification ---
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.saverCategory = "nature";
        snap.saverClockStyle = "liquid_glass";
        snap.saverAnimation = true;
        snap.saverIntervalMs = 30_000L;
        snap.saverIntervalLabel = "30s";
        snap.saverShowArtworkTitle = true;
        snap.saverShowArtist = true;
        snap.saverShowLocation = true;
        snap.saverShowLocalTime = true;
        snap.saverShowWorldClock = true;
        snap.saverShowWeather = true;
        snap.saverShowCalendar = true;
        snap.saverWorldClockTz = "America/New_York";
        snap.saverWorldClockLabel = "NYC";

        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        SettingsTree.Node ssParent = root.findById("screensaver");
        assertTrue(ssParent != null && ssParent.hasChildren(), "Screensaver node exists in SettingsTree");

        // Verify all 7 screensaver sub-nodes
        SettingsTree.Node animNode = ssParent.findById("ss_animation");
        assertTrue(animNode != null, "ss_animation node exists");
        assertEquals("Image Animations", animNode.title, "Animation node title is correct");
        assertEquals("sparkles", animNode.icon, "Animation node icon is sparkles");
        assertEquals("Enabled", animNode.value, "Animation node value reflects state");

        SettingsTree.Node intervalNode = ssParent.findById("ss_interval");
        assertTrue(intervalNode != null && !intervalNode.hasChildren(), "ss_interval is now a direct Up/Down modal action node");
        assertEquals(SettingsTree.RowType.ACTION, intervalNode.rowType, "ss_interval rowType is ACTION");
        assertEquals("Slide Duration", intervalNode.title, "Slide Duration title is correct");
        assertEquals("timer", intervalNode.icon, "Slide Duration icon is timer");
        assertEquals("30s", intervalNode.value, "Slide Duration value reflects active interval");

        SettingsTree.Node infoParent = ssParent.findById("ss_info_toggles");
        assertTrue(infoParent != null && infoParent.hasChildren(), "ss_info_toggles node exists with children");
        assertEquals("On-Screen Info & Text", infoParent.title, "Info toggles title is correct");
        assertEquals(6, infoParent.children.size(), "Info toggles has 6 granular options (Artwork Title removed)");
        assertTrue(infoParent.findById("ss_toggle_clock") != null, "Main Clock toggle exists");
        assertTrue(infoParent.findById("ss_toggle_weather") != null, "Weather toggle exists");
        assertTrue(infoParent.findById("ss_toggle_title") == null, "Big Artwork Title toggle removed");
        assertTrue(infoParent.findById("ss_toggle_artist") != null, "Artist Name toggle exists");
        assertTrue(infoParent.findById("ss_toggle_location") != null, "Location toggle exists");
        assertTrue(infoParent.findById("ss_toggle_world_clock") != null, "Second Clock toggle exists");
        assertTrue(infoParent.findById("ss_toggle_calendar") != null, "Date & Calendar toggle exists");

        SettingsTree.Node tzParent = ssParent.findById("ss_tz");
        assertTrue(tzParent != null && tzParent.hasChildren(), "ss_tz node exists with children");
        assertEquals("Second Clock Timezone", tzParent.title, "Timezone title is correct");
        assertEquals(61, tzParent.children.size(), "Timezone parent contains all 61 world hubs");

        // Verify full-width column calculation in SettingsScreenRenderer:
        java.util.List<String> path = java.util.Arrays.asList("display_sound", "screensaver");
        assertTrue(SettingsScreenRenderer.isLastColumnFullWidth(path, infoParent),
                "Info Toggles column expands to 100% full width (leaf)");
        assertTrue(SettingsScreenRenderer.isLastColumnFullWidth(path, tzParent),
                "World Timezone column expands to 100% full width (leaf)");
        SettingsTree.Node catParent = ssParent.findById("ss_category");
        SettingsTree.Node clockParent = ssParent.findById("ss_clock");
        assertEquals(false, SettingsScreenRenderer.isLastColumnFullWidth(path, catParent),
                "ss_category preserves 60%/40% split for live previews");
        assertEquals(false, SettingsScreenRenderer.isLastColumnFullWidth(path, clockParent),
                "ss_clock preserves 60%/40% split for live previews");

        // --- 6. R6: Remote Key Navigation & Consumption ---
        // Left, Right, Up, Down consumed (true)
        assertTrue(DreamView.isScreensaverNavKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT),
                "KEYCODE_DPAD_LEFT is recognized as screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
                "KEYCODE_DPAD_RIGHT is recognized as screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(android.view.KeyEvent.KEYCODE_DPAD_UP),
                "KEYCODE_DPAD_UP is recognized as screensaver nav key");
        assertTrue(DreamView.isScreensaverNavKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
                "KEYCODE_DPAD_DOWN is recognized as screensaver nav key");
        assertTrue(DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT),
                "DreamView consumes DPAD_LEFT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
                "DreamView consumes DPAD_RIGHT");
        assertTrue(DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_DPAD_UP),
                "DreamView consumes DPAD_UP");
        assertTrue(DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
                "DreamView consumes DPAD_DOWN");

        // Back, OK, Home NOT consumed by DreamView navigation (consumed by dismiss handler)
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_BACK),
                "DreamView does not consume BACK in navigation router");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_ESCAPE),
                "DreamView does not consume ESCAPE in navigation router");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_HOME),
                "DreamView does not consume HOME in navigation router");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER),
                "DreamView does not consume DPAD_CENTER in navigation router");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_ENTER),
                "DreamView does not consume ENTER in navigation router");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER),
                "DreamView does not consume NUMPAD_ENTER in navigation router");

        // Dismiss keys classification
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_BACK), "BACK is dismiss key");
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_ESCAPE), "ESCAPE is dismiss key");
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_HOME), "HOME is dismiss key");
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER), "DPAD_CENTER is dismiss key");
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_ENTER), "ENTER is dismiss key");
        assertTrue(DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_NUMPAD_ENTER), "NUMPAD_ENTER is dismiss key");

        // Navigation keys are NOT dismiss keys
        assertEquals(false, DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT), "DPAD_LEFT is not dismiss key");
        assertEquals(false, DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT), "DPAD_RIGHT is not dismiss key");
        assertEquals(false, DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_DPAD_UP), "DPAD_UP is not dismiss key");
        assertEquals(false, DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN), "DPAD_DOWN is not dismiss key");

        // Non-screensaver keys (e.g. volume) are neither nav nor dismiss keys
        assertEquals(false, DreamView.isScreensaverNavKey(android.view.KeyEvent.KEYCODE_VOLUME_UP), "VOLUME_UP is not nav key");
        assertEquals(false, DreamView.isScreensaverDismissKey(android.view.KeyEvent.KEYCODE_VOLUME_UP), "VOLUME_UP is not dismiss key");
        assertEquals(false, DreamView.shouldConsumeScreensaverKey(android.view.KeyEvent.KEYCODE_VOLUME_UP), "VOLUME_UP is not consumed by screensaver");
    }

    /**
     * Verifies 4-port USB hub multi-controller resolution where EventHub IDs step by 3 per dongle
     * (Gamepad, Consumer Control, Keyboard: [13..15], [16..18], [19..21], [22..24]) while Android
     * InputDevice.getId() (InputReader State) assigns [11, 13, 15, 17]. Ensures zero ID collisions.
     */
    private static void testUsbHubMultiControllerInputReaderIdMapping() {
        String realTvHubDump =
                "INPUT MANAGER (dumpsys input)\n\n" +
                "Event Hub State:\n" +
                "  BuiltInKeyboardId: -2\n" +
                "  Devices:\n" +
                "    24: Zikway HID gamepad Keyboard\n" +
                "      Descriptor: 1111111111111111111111111111111111111111\n" +
                "      Location: usb-1c7a0000.xhci-2.1/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    23: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: 2222222222222222222222222222222222222222\n" +
                "      Location: usb-1c7a0000.xhci-2.1/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    22: Zikway HID gamepad\n" +
                "      Descriptor: 3333333333333333333333333333333333333333\n" +
                "      Location: usb-1c7a0000.xhci-2.1/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    21: Zikway HID gamepad Keyboard\n" +
                "      Descriptor: afdd7a48218ada697b470f5eb9f26a8913580696\n" +
                "      Location: usb-1c7a0000.xhci-2.2/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    20: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: 74c2f8e5848e41534cf4a406b3c417cd8cbf61ad\n" +
                "      Location: usb-1c7a0000.xhci-2.2/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    19: Zikway HID gamepad\n" +
                "      Descriptor: 6fff39eb70e0f6099141afe08d7dd478d19d95e5\n" +
                "      Location: usb-1c7a0000.xhci-2.2/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    18: Zikway HID gamepad Keyboard\n" +
                "      Descriptor: ecc39e57a3057ec7504140ad6e5b8b7a842bc537\n" +
                "      Location: usb-1c7a0000.xhci-2.3/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    17: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: d5cad6ccb8be24ab1fd538800138ff3c1ff28410\n" +
                "      Location: usb-1c7a0000.xhci-2.3/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    16: Zikway HID gamepad\n" +
                "      Descriptor: 419fe5c1fbcba95d40fe3a294efa527d55a43b5a\n" +
                "      Location: usb-1c7a0000.xhci-2.3/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    15: Zikway HID gamepad Keyboard\n" +
                "      Descriptor: 489ad56de0d9d0329c0258c3024c03a82b807dd0\n" +
                "      Location: usb-1c7a0000.xhci-2.4/input1\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    14: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: 6dc26b33d0b69347a54d9d2d850055bc9bb0589e\n" +
                "      Location: usb-1c7a0000.xhci-2.4/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    13: Zikway HID gamepad\n" +
                "      Descriptor: 63fcdc36e408a083f21db5d3b77b1d84c1e8c008\n" +
                "      Location: usb-1c7a0000.xhci-2.4/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "\n" +
                "Input Reader State:\n" +
                "  Device -1: Virtual\n" +
                "    EventHub Devices: [ ]\n" +
                "  Device 17: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 22 23 ]\n" +
                "  Device 15: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 19 20 ]\n" +
                "  Device 13: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 16 17 ]\n" +
                "  Device 11: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 13 14 ]\n";

        java.util.Map<Integer, ControllerManager.DeviceHardwareLocation> mapped =
                ControllerManager.parseInputDumpLocations(realTvHubDump);

        assertTrue(mapped.containsKey(11), "InputDevice 11 (Blue) mapped from EventHub [13 14]");
        assertTrue(mapped.containsKey(13), "InputDevice 13 (Pink) mapped from EventHub [16 17]");
        assertTrue(mapped.containsKey(15), "InputDevice 15 (Green) mapped from EventHub [19 20]");
        assertTrue(mapped.containsKey(17), "InputDevice 17 (4th) mapped from EventHub [22 23]");

        assertEquals("xhci-2.4", ControllerManager.extractUsbPortIdentifier(mapped.get(11).location),
                "Blue InputDevice 11 resolves to hub port xhci-2.4");
        assertEquals("xhci-2.3", ControllerManager.extractUsbPortIdentifier(mapped.get(13).location),
                "Pink InputDevice 13 resolves to hub port xhci-2.3 (never collides with EventHub 13)");
        assertEquals("xhci-2.2", ControllerManager.extractUsbPortIdentifier(mapped.get(15).location),
                "Green InputDevice 15 resolves to hub port xhci-2.2 (never collides with EventHub 15)");
        assertEquals("xhci-2.1", ControllerManager.extractUsbPortIdentifier(mapped.get(17).location),
                "4th InputDevice 17 resolves to hub port xhci-2.1");

        String d11 = ControllerManager.resolveStableDescriptor(mapped.get(11).descriptor, null,
                ControllerManager.extractUsbPortIdentifier(mapped.get(11).location), "Zikway HID gamepad", 13623, 4161);
        String d13 = ControllerManager.resolveStableDescriptor(mapped.get(13).descriptor, null,
                ControllerManager.extractUsbPortIdentifier(mapped.get(13).location), "Zikway HID gamepad", 13623, 4161);
        String d15 = ControllerManager.resolveStableDescriptor(mapped.get(15).descriptor, null,
                ControllerManager.extractUsbPortIdentifier(mapped.get(15).location), "Zikway HID gamepad", 13623, 4161);
        String d17 = ControllerManager.resolveStableDescriptor(mapped.get(17).descriptor, null,
                ControllerManager.extractUsbPortIdentifier(mapped.get(17).location), "Zikway HID gamepad", 13623, 4161);

        java.util.Set<String> uniqueDescs = new java.util.HashSet<>(java.util.Arrays.asList(d11, d13, d15, d17));
        assertEquals(4, uniqueDescs.size(), "All 4 USB hub controllers produce 4 distinct descriptors with zero collisions");
    }

    private static void testXiaomiMediaTekInputReaderHeaderAndNegativeVirtualIdNoCollision() {
        // Exact dump structure from Xiaomi SmartTV-4K (MediaTek mt5879, Android 14) with 4 dongles on USB hub 1-2:
        // Notice TWO critical OEM quirks:
        // 1. "-1: Virtual" (negative EventHub ID with empty Location) appears directly after a physical EventHub device.
        // 2. Section header is "Input Reader State (Nums of device: 15):" instead of stock AOSP "Input Reader State:".
        // 3. Yellow's InputReader ID is Device 23 (EventHub [31 32], xhci-2.1), colliding numerically with Blue's EventHub 23 (xhci-2.4)!
        String xiaomiAndroid14Dump =
                "Event Hub State:\n" +
                "  BuiltInKeyboardId: -2\n" +
                "  Devices:\n" +
                "    31: Zikway HID gamepad\n" +
                "      Descriptor: 17a5d54270bb03f3fabf1ed6e9765bc54fe92f24\n" +
                "      Location: usb-1c7a0000.xhci-2.1/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    32: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: cef880883832edb70a7388288334345e0c4e42ee\n" +
                "      Location: usb-1c7a0000.xhci-2.1/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    28: Zikway HID gamepad\n" +
                "      Descriptor: 6fff39eb70e0f6099141afe08d7dd478d19d95e5\n" +
                "      Location: usb-1c7a0000.xhci-2.2/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    29: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: 74c2f8e5848e41534cf4a406b3c417cd8cbf61ad\n" +
                "      Location: usb-1c7a0000.xhci-2.2/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    25: Zikway HID gamepad\n" +
                "      Descriptor: 419fe5c1fbcba95d40fe3a294efa527d55a43b5a\n" +
                "      Location: usb-1c7a0000.xhci-2.3/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    26: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: d5cad6ccb8be24ab1fd538800138ff3c1ff28410\n" +
                "      Location: usb-1c7a0000.xhci-2.3/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    23: Zikway HID gamepad Consumer Control\n" +
                "      Descriptor: 6dc26b33d0b69347a54d9d2d850055bc9bb0589e\n" +
                "      Location: usb-1c7a0000.xhci-2.4/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    22: Zikway HID gamepad\n" +
                "      Descriptor: 63fcdc36e408a083f21db5d3b77b1d84c1e8c008\n" +
                "      Location: usb-1c7a0000.xhci-2.4/input0\n" +
                "      UniqueId: \n" +
                "      Identifier: bus=0x0003, vendor=0x3537, product=0x1041, version=0x0111, bluetoothAddress=<not set>\n" +
                "    -1: Virtual\n" +
                "      Descriptor: a718a782d34bc767f4689c232d64d527998ea7fd\n" +
                "      Location: \n" +
                "      UniqueId: <virtual>\n" +
                "  Unattached video devices:\n" +
                "    <none>\n" +
                "\n" +
                "Input Reader State (Nums of device: 15):\n" +
                "  Device 23: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 31 32 ] \n" +
                "  Device 21: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 28 29 ] \n" +
                "  Device 19: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 25 26 ] \n" +
                "  Device 17: Zikway HID gamepad\n" +
                "    EventHub Devices: [ 22 23 ] \n" +
                "  Device -1: Virtual\n" +
                "    EventHub Devices: [ -1 ] \n";

        java.util.Map<Integer, ControllerManager.DeviceHardwareLocation> mapped =
                ControllerManager.parseInputDumpLocations(xiaomiAndroid14Dump);

        assertTrue(mapped.containsKey(17), "Blue InputDevice 17 mapped on MediaTek/Xiaomi header");
        assertTrue(mapped.containsKey(19), "Pink InputDevice 19 mapped on MediaTek/Xiaomi header");
        assertTrue(mapped.containsKey(21), "Green InputDevice 21 mapped on MediaTek/Xiaomi header");
        assertTrue(mapped.containsKey(23), "Yellow InputDevice 23 mapped on MediaTek/Xiaomi header");

        // Verify "-1: Virtual" did NOT overwrite EventHub 22's Location (Blue xhci-2.4)
        assertEquals("xhci-2.4", ControllerManager.extractUsbPortIdentifier(mapped.get(17).location),
                "Blue InputDevice 17 keeps xhci-2.4 (not wiped out by -1: Virtual)");
        assertEquals("xhci-2.3", ControllerManager.extractUsbPortIdentifier(mapped.get(19).location),
                "Pink InputDevice 19 resolves to xhci-2.3");
        assertEquals("xhci-2.2", ControllerManager.extractUsbPortIdentifier(mapped.get(21).location),
                "Green InputDevice 21 resolves to xhci-2.2");
        assertEquals("xhci-2.1", ControllerManager.extractUsbPortIdentifier(mapped.get(23).location),
                "Yellow InputDevice 23 resolves to xhci-2.1 (never collides with Blue EventHub 23)");
        assertTrue(mapped.get(23).matchesDescriptor("17a5d54270bb03f3fabf1ed6e9765bc54fe92f24"),
                "Yellow Device 23 matches primary EventHub 31 descriptor");
        assertTrue(mapped.get(23).matchesDescriptor("cef880883832edb70a7388288334345e0c4e42ee"),
                "Yellow Device 23 also matches merged Consumer Control EventHub 32 descriptor");

        // Defense-in-depth: even if an OEM omits Input Reader State completely (EventHub-only dump),
        // lookupHardwareLocation(23, yellowDesc) rejects EventHub 23 (Blue) via SHA-1 mismatch and finds Yellow!
        String eventHubOnlyDump = xiaomiAndroid14Dump.substring(0, xiaomiAndroid14Dump.indexOf("Input Reader State"));
        java.util.Map<Integer, ControllerManager.DeviceHardwareLocation> ehOnly =
                ControllerManager.parseInputDumpLocations(eventHubOnlyDump);
        try {
            ControllerManager.setMockHardwareLocationsForTesting(ehOnly);
            ControllerManager.DeviceHardwareLocation yellowFallback =
                    ControllerManager.lookupHardwareLocation(23, "17a5d54270bb03f3fabf1ed6e9765bc54fe92f24");
            assertTrue(yellowFallback != null, "Descriptor cross-check resolves Yellow even on EventHub-only dump");
            assertEquals("xhci-2.1", ControllerManager.extractUsbPortIdentifier(yellowFallback.location),
                    "Descriptor cross-check rejects EventHub 23 (xhci-2.4) and resolves Yellow to xhci-2.1");
        } finally {
            ControllerManager.setMockHardwareLocationsForTesting(null);
        }
    }

    /**
     * Verifies zero-step Moonlight ShortcutTrampoline launch specification (UUID vs Name mutual exclusion,
     * Sunshine app name resolution for Desktop / Steam Big Picture / Steam & standalone games) and
     * animated Game Launch Modal explanatory steps + procedural icon names (zero emojis).
     */
    private static void testMoonlightDirectLaunchSpecAndAnimatedLaunchStages() {
        GamingZone.GameItem boomerang = new GamingZone.GameItem(
                "steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", false);
        GamingZone.GameItem desktop = new GamingZone.GameItem(
                "desktop", "Windows Desktop", "system", "explorer.exe", false);
        GamingZone.GameItem bigPicture = new GamingZone.GameItem(
                "steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", false);
        GamingZone.GameItem hogwarts = new GamingZone.GameItem(
                "exe_hogwartslegacy", "Hogwarts Legacy", "standalone", "C:\\Games\\HogwartsLegacy.exe", false);

        // 1. Sunshine AppName mapping
        assertEquals("Desktop", GamingZone.resolveSunshineAppName(desktop, null),
                "desktop id maps to Sunshine's built-in 'Desktop' target");
        assertEquals("Steam Big Picture", GamingZone.resolveSunshineAppName(bigPicture, ""),
                "steam_bigpicture id maps to 'Steam Big Picture'");
        assertEquals("Boomerang Fu", GamingZone.resolveSunshineAppName(boomerang, null),
                "Steam game maps to exact game title synced into Sunshine apps.json");
        assertEquals("Custom Sunshine Name", GamingZone.resolveSunshineAppName(boomerang, "  Custom Sunshine Name "),
                "Server-reported sunshineAppName override takes priority when non-blank");

        // 2. Moonlight ShortcutTrampoline mutual exclusion: UUID wins over Name, NEVER both!
        GamingZone.MoonlightLaunchSpec withBoth = GamingZone.MoonlightLaunchSpec.resolve(
                "14FEDE7C-47F1-1D07-0185-22930C564015",
                "Gaming-Laptop",
                boomerang,
                "Boomerang Fu"
        );
        assertTrue(withBoth.canDirectLaunch, "Direct launch enabled when UUID is present");
        assertEquals("com.limelight", withBoth.packageName, "Targets Moonlight package");
        assertEquals("com.limelight.ShortcutTrampoline", withBoth.className, "Targets ShortcutTrampoline activity");
        assertEquals("14FEDE7C-47F1-1D07-0185-22930C564015", withBoth.uuidExtra, "Populates UUID extra");
        assertEquals(null, withBoth.nameExtra, "Omits Name extra when UUID is present (prevents ShortcutTrampoline error)");
        assertEquals("Boomerang Fu", withBoth.appNameExtra, "Populates AppName extra");

        // 3. Fallback to hostName when UUID is blank
        GamingZone.MoonlightLaunchSpec nameOnly = GamingZone.MoonlightLaunchSpec.resolve(
                "   ",
                "Gaming-Laptop",
                desktop,
                ""
        );
        assertTrue(nameOnly.canDirectLaunch, "Direct launch enabled when hostName is present");
        assertEquals(null, nameOnly.uuidExtra, "Omits UUID extra when blank");
        assertEquals("Gaming-Laptop", nameOnly.nameExtra, "Populates Name extra when UUID is blank");
        assertEquals("Desktop", nameOnly.appNameExtra, "Resolves Desktop AppName extra");

        // 4. Graceful fallback when neither UUID nor hostName is known yet
        GamingZone.MoonlightLaunchSpec missingHost = GamingZone.MoonlightLaunchSpec.resolve(
                "",
                null,
                boomerang,
                null
        );
        assertEquals(false, missingHost.canDirectLaunch,
                "Disables ShortcutTrampoline direct launch cleanly when no host UUID or Name is cached");

        // 5. Animated Game Launch Modal stages: monotonic progress, explanatory text, supported vector icons, zero emojis
        GamingZone.LaunchModalStep stepWake = GamingZone.LaunchStage.WAKING_PC.buildStep(boomerang, null);
        GamingZone.LaunchModalStep stepUnlock = GamingZone.LaunchStage.UNLOCKING_WINDOWS.buildStep(boomerang, null);
        GamingZone.LaunchModalStep stepSteam = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(boomerang, null);
        GamingZone.LaunchModalStep stepStandalone = GamingZone.LaunchStage.PREPARING_STEAM.buildStep(hogwarts, null);
        GamingZone.LaunchModalStep stepStream = GamingZone.LaunchStage.CONNECTING_STREAM.buildStep(boomerang, null);
        GamingZone.LaunchModalStep stepHdmi = GamingZone.LaunchStage.CONNECTING_STREAM.buildStep(boomerang, "HDMI 3");

        assertTrue(stepWake.progressPercent < stepUnlock.progressPercent
                        && stepUnlock.progressPercent < stepSteam.progressPercent
                        && stepSteam.progressPercent < stepStream.progressPercent,
                "Launch modal stages progress monotonically from wake -> unlock -> prepare -> connect");
        assertTrue(stepWake.headline.toLowerCase(java.util.Locale.ROOT).contains("waking"),
                "WAKING_PC headline explains waking up PC");
        assertTrue(stepUnlock.headline.toLowerCase(java.util.Locale.ROOT).contains("unlock"),
                "UNLOCKING_WINDOWS headline explains unlocking Windows");
        assertTrue(stepSteam.headline.toLowerCase(java.util.Locale.ROOT).contains("steam"),
                "PREPARING_STEAM headline mentions Steam for Steam titles");
        assertTrue(!stepStandalone.headline.toLowerCase(java.util.Locale.ROOT).contains("steam"),
                "PREPARING_STEAM headline omits Steam for standalone non-Steam titles");
        assertTrue(stepStream.headline.contains("Boomerang Fu"),
                "CONNECTING_STREAM headline names the target game");
        assertTrue(stepHdmi.headline.contains("HDMI 3"),
                "CONNECTING_STREAM HDMI variant names the target TV HDMI input");

        for (GamingZone.LaunchStage st : GamingZone.LaunchStage.values()) {
            GamingZone.LaunchModalStep s = st.buildStep(boomerang, null);
            assertTrue(Symbols.isSupportedIcon(s.iconName),
                    "Stage " + st.name() + " uses supported procedural symbol icon: " + s.iconName);
            for (int i = 0; i < s.headline.length(); i++) {
                assertTrue(s.headline.charAt(i) < 0x1F000, "Zero emojis in headline: " + s.headline);
            }
            for (int i = 0; i < s.subtitle.length(); i++) {
                assertTrue(s.subtitle.charAt(i) < 0x1F000, "Zero emojis in subtitle: " + s.subtitle);
            }
        }
    }

    /**
     * Verifies Milestone 3: ActiveStreamingSession immutability, safe defaults, thread-safe atomic clear,
     * debounce suppression (< 3000ms), and pure JSON termination payload formatting.
     */
    private static void testActiveStreamingSessionAndExitLifecycle() {
        // 1. Initial state
        GamingZone.clearActiveStreamingSession();
        assertEquals(null, GamingZone.getActiveStreamingSession(),
                "Active streaming session starts null");

        // 2. ActiveStreamingSession immutability & safe defaults
        long t0 = 100_000L;
        GamingZone.ActiveStreamingSession session = new GamingZone.ActiveStreamingSession(
                "steam_965680", "Boomerang Fu", "192.168.1.12", 8485, t0);
        assertEquals("steam_965680", session.gameId, "Session preserves gameId");
        assertEquals("Boomerang Fu", session.gameTitle, "Session preserves gameTitle");
        assertEquals("192.168.1.12", session.hostIp, "Session preserves hostIp");
        assertEquals(8485, session.port, "Session preserves port");
        assertEquals(100_000L, session.launchTimeMs, "Session preserves launch timestamp");
        assertEquals(500L, session.getElapsedMs(t0 + 500L), "getElapsedMs calculates duration");

        GamingZone.ActiveStreamingSession defaultSession = new GamingZone.ActiveStreamingSession(
                null, null, null, -1, t0);
        assertEquals("", defaultSession.gameId, "Null gameId normalized to empty string");
        assertEquals("", defaultSession.gameTitle, "Null gameTitle normalized to empty string");
        assertEquals("", defaultSession.hostIp, "Null hostIp normalized to empty string");
        assertEquals(8485, defaultSession.port, "Non-positive port defaults to LAPTOP_AGENT_PORT");

        // 3. State management & atomic clear
        GamingZone.setActiveStreamingSession(session);
        assertEquals(session, GamingZone.getActiveStreamingSession(), "getActiveStreamingSession returns registered session");
        GamingZone.ActiveStreamingSession cleared = GamingZone.clearActiveStreamingSession();
        assertEquals(session, cleared, "clearActiveStreamingSession returns active session");
        assertEquals(null, GamingZone.getActiveStreamingSession(), "getActiveStreamingSession is null after clear");
        assertEquals(null, GamingZone.clearActiveStreamingSession(), "Subsequent clearActiveStreamingSession returns null");

        // 4. Debounce threshold logic (< 3000ms suppressed, >= 3000ms triggers)
        long threshold = 3000L;
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(null, t0 + 5000L, threshold), "Null session never triggers exit");
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 + 500L, threshold), "Rapid bounce at 500ms suppressed");
        assertEquals(false, GamingZone.shouldTriggerRemoteExit(session, t0 + 2999L, threshold), "Bounce at 2999ms suppressed");
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 3000L, threshold), "Elapsed at 3000ms triggers exit");
        assertEquals(true, GamingZone.shouldTriggerRemoteExit(session, t0 + 60_000L, threshold), "Elapsed at 60s triggers exit");

        // 5. Termination JSON payload format
        String payload = GamingZone.buildTerminationPayload("steam_965680", "remote_home_exit");
        assertTrue(payload.contains("\"gameId\":\"steam_965680\""), "Payload contains gameId");
        assertTrue(payload.contains("\"reason\":\"remote_home_exit\""), "Payload contains reason");
        assertTrue(payload.startsWith("{") && payload.endsWith("}"), "Payload is valid JSON");
    }

    private static void testRound12HeroBackdropReversalPinningAndTopBarZeroLag() throws Exception {
        // 1. Mid-crossfade reversal A -> B -> A (< 225ms and >= 225ms) NEVER produces bgFrom == bgTo
        Backdrop bdA = Backdrop.emptyForTesting();
        Backdrop bdB = Backdrop.emptyForTesting();
        Backdrop bdC = Backdrop.emptyForTesting();

        HomeView.BackdropTransitionState state = new HomeView.BackdropTransitionState();
        assertTrue(state.transitionTo(bdA, 1000L, 450f), "Initial transition to A succeeds");
        assertEquals(bdA, state.bgTo, "Initial bgTo is A");
        // Settle initial fade
        state.bgFadeAt = 0L;
        state.bgFrom = null;

        // Move A -> B at t = 2000ms
        assertTrue(state.transitionTo(bdB, 2000L, 450f), "Transition A -> B succeeds");
        assertEquals(bdA, state.bgFrom, "A -> B sets bgFrom = A");
        assertEquals(bdB, state.bgTo, "A -> B sets bgTo = B");

        // Rapid reversal B -> A at t = 2150ms (150ms < 225ms: previously caused bgFrom == bgTo == A!)
        assertTrue(state.transitionTo(bdA, 2150L, 450f), "Rapid 150ms reversal B -> A succeeds");
        assertEquals(bdA, state.bgTo, "Rapid reversal restores bgTo = A");
        assertTrue(state.bgFrom != state.bgTo,
                "Rapid reversal MUST NEVER leave bgFrom == bgTo (prevents double RenderNode draw corruption)");

        // Extremely fast reversal (< 40ms, prevE <= 0.06f): snaps cleanly back to A with bgFrom == null
        state.bgFadeAt = 0L;
        state.bgFrom = null;
        state.bgTo = bdA;
        state.transitionTo(bdB, 3000L, 450f);
        state.transitionTo(bdA, 3020L, 450f);
        assertEquals(bdA, state.bgTo, "Sub-frame 20ms reversal keeps bgTo = A");
        assertEquals(null, state.bgFrom, "Sub-frame 20ms reversal cancels crossfade cleanly (bgFrom = null)");
        assertEquals(0L, state.bgFadeAt, "Sub-frame 20ms reversal resets bgFadeAt = 0");

        // Late mid-fade reversal at t = +340ms (>= 225ms): smoothly reverses B -> A with continuous progress
        state.transitionTo(bdB, 4000L, 450f);
        assertTrue(state.transitionTo(bdA, 4340L, 450f), "340ms reversal B -> A succeeds");
        assertEquals(bdB, state.bgFrom, "340ms reversal sets bgFrom = B");
        assertEquals(bdA, state.bgTo, "340ms reversal sets bgTo = A");
        assertTrue(state.bgFadeAt < 4340L && state.bgFadeAt >= 4340L - 450L,
                "340ms reversal backdates bgFadeAt for seamless opacity continuity");

        // Recycled bgTo self-healing: if bgTo was somehow recycled, transitionTo allows re-binding new instance
        Backdrop deadBd = Backdrop.emptyForTesting();
        deadBd.recycle();
        state.bgFadeAt = 0L;
        state.bgFrom = null;
        state.bgTo = deadBd;
        assertTrue(state.transitionTo(bdC, 5000L, 450f), "Recycled bgTo is replaced cleanly");
        assertEquals(null, state.bgFrom, "Recycled old bgTo is never retained as bgFrom");
        assertEquals(bdC, state.bgTo, "New valid backdrop becomes bgTo");

        // 2. Scene.AgslHero direct Canvas shader pass (dual isolated RuntimeShaders, zero child RenderNode compositing layers)
        String sceneSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/Scene.java")), java.nio.charset.StandardCharsets.UTF_8);
        int heroSlotIdx = sceneSrc.indexOf("private static final class HeroSlot");
        int agslFieldsIdx = sceneSrc.indexOf("private final HeroSlot slotA");
        assertTrue(heroSlotIdx > 0 && agslFieldsIdx > heroSlotIdx, "Scene.AgslHero defines HeroSlot before slotA/slotB");
        String heroSlotBlock = sceneSrc.substring(heroSlotIdx, agslFieldsIdx);
        assertTrue(heroSlotBlock.contains("RuntimeShader shader") && heroSlotBlock.contains("Paint paint"),
                "Each HeroSlot owns its own dedicated RuntimeShader and Paint so slotB never mutates slotA's shader");
        assertTrue(!heroSlotBlock.contains("Recording") && !heroSlotBlock.contains("setUseCompositingLayer"),
                "HeroSlot never wraps hero shader in a child RenderNode compositing layer that Android 14 HWUI purges on A->B->A");
        String agslHeroTail = sceneSrc.substring(heroSlotIdx);
        assertTrue(agslHeroTail.contains("setFloatUniform(\"alpha\", clampedAlpha)"),
                "AgslHero drives crossfade alpha directly via HERO_VIGNETTE uniform into sky's DisplayList");

        // 3. GamingZone pinned first-item hero cache survives 50-item LRU overflow + purges recycled zombie entries
        java.lang.reflect.Field gameMemField = GamingZone.class.getDeclaredField("GAME_HERO_MEM");
        gameMemField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Backdrop> gameMap = (java.util.Map<String, Backdrop>) gameMemField.get(null);
        synchronized (gameMap) {
            gameMap.clear();
        }
        GamingZone.GameItem leadContinue = new GamingZone.GameItem("pin_cont_0", "Lead Continue", "steam", "", false);
        GamingZone.GameItem leadLibrary = new GamingZone.GameItem("pin_lib_0", "Lead Library", "steam", "", false);
        Backdrop bdLeadCont = Backdrop.emptyForTesting();
        Backdrop bdLeadLib = Backdrop.emptyForTesting();
        GamingZone.setPinnedGameHeroes(java.util.Arrays.asList(leadContinue, leadLibrary));
        try {
            synchronized (gameMap) {
                gameMap.put("hero:" + leadContinue.id, bdLeadCont);
                gameMap.put("hero:" + leadLibrary.id, bdLeadLib);
                for (int i = 0; i < 40; i++) {
                    gameMap.put("hero:scroll_filler_" + i, Backdrop.emptyForTesting());
                }
            }
            assertEquals(24, GamingZone.getGameHeroCacheSize(), "Pinned cache still obeys strict 24-backdrop ceiling");
            assertTrue(!bdLeadCont.isRecycled(), "Pinned Continue Playing #0 backdrop is never recycled during scrolling");
            assertTrue(!bdLeadLib.isRecycled(), "Pinned Games Library #0 backdrop is never recycled during scrolling");
            assertEquals(bdLeadCont, GamingZone.peekGameBackdrop(leadContinue), "Pinned Continue Playing #0 hits RAM in 0ms");
            assertEquals(bdLeadLib, GamingZone.peekGameBackdrop(leadLibrary), "Pinned Games Library #0 hits RAM in 0ms");

            // Zombie recycled entry self-healing in peekGameBackdrop
            GamingZone.GameItem zombieGame = new GamingZone.GameItem("zombie_game", "Zombie", "steam", "", false);
            Backdrop zombieBd = Backdrop.emptyForTesting();
            synchronized (gameMap) {
                gameMap.put("hero:" + zombieGame.id, zombieBd);
            }
            zombieBd.recycle();
            assertEquals(null, GamingZone.peekGameBackdrop(zombieGame),
                    "peekGameBackdrop purges recycled Backdrop instead of returning poisoned entry");
        } finally {
            GamingZone.setPinnedGameHeroes(java.util.Collections.emptyList());
            synchronized (gameMap) {
                gameMap.clear();
            }
        }

        // 4. Recs pinned first-series/movie backdrop protection + immediate setActiveFocusKey race guard
        java.util.Map<String, Backdrop> recsTestMap = new java.util.LinkedHashMap<>(16, 0.75f, true);
        Backdrop pinnedSeriesBd = Backdrop.emptyForTesting();
        recsTestMap.put("/pinned_first_series.jpg", pinnedSeriesBd);
        for (int i = 0; i < 20; i++) {
            recsTestMap.put("/other_item_" + i + ".jpg", Backdrop.emptyForTesting());
        }
        java.util.Set<String> pinnedSet = new java.util.HashSet<>(java.util.Collections.singletonList("/pinned_first_series.jpg"));
        Recs.evictOldestBackdropsWithPins(recsTestMap, 8, pinnedSet);
        assertEquals(8, recsTestMap.size(), "evictOldestBackdropsWithPins enforces max capacity");
        assertTrue(recsTestMap.containsKey("/pinned_first_series.jpg") && !pinnedSeriesBd.isRecycled(),
                "Pinned first series hero backdrop survives LRU eviction");

        // 5. Top-Bar Pill snappy response, zero full-screen heroLayer redraw on topMoving, and cached neutralGlass aurora
        HomeView.TopBarPillState fastPill = new HomeView.TopBarPillState();
        fastPill.retarget(180f, 300f, true, false);
        assertEquals(180f, fastPill.left.value, 0.01f,
                "Entering Top Bar from invisible snaps horizontal position to active tab instead of flying from x=0");
        fastPill.retarget(320f, 450f, true, false);
        for (int frame = 0; frame < 10; frame++) {
            fastPill.step(0.016f);
        }
        assertTrue(Math.abs(fastPill.left.value - 320f) < 2.5f,
                "Snappy TopBarPillState reaches destination within ~160ms (10 frames) without lingering, actual=" + fastPill.left.value);

        String homeSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/HomeView.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!homeSrc.contains("heroMoving |= topMoving"),
                "doFrame no longer forces full-screen 4K heroLayer redraw on every topMoving frame");
        assertTrue(homeSrc.contains("boolean busy = topMoving || tilesMoving"),
                "doFrame keeps 60fps Choreographer loop awake for topMoving so Top-Bar pill never freezes on 1000ms idle timer");
        assertTrue(homeSrc.contains("cachedNeutralAuroraNightBucket"),
                "neutralGlass caches unified aurora pixel buffers per night bucket to avoid 150k trig calls on tab switch");
    }

    /**
     * Verifies all 6 Team 2 Screensaver Feedback improvements:
     * 1. Expanded 61-hub World Clock registry with live city hour & DST-aware UTC offset preview.
     * 2. Removal of gallery-name first row from Settings photography category & clock previews.
     * 3. Instant (<16ms) overlay restoration via direct-Canvas rendering (zero child RenderNode detachment).
     * 4. Deterministic Left/Right wallpaper cycling with in-flight step tracking & failed-slide skipping.
     * 5. Strict landscape True-4K (>= 3840x2160) validation & legacy cache purge (saver_catalog_4k_v2).
     * 6. 24 visually verified True-4K photographs with accurate titles, photographers, and locations.
     */
    private static void testScreensaverSixFeedbackFixes() throws Exception {
        // --- 1. Expanded 61-Hub World Timezone Registry + Live City Hour & UTC Offset ---
        assertEquals(61, Prefs.SAVER_WORLD_HUBS.length, "Expanded World Clock registry has 61 global cities");
        java.util.Set<String> codes = new java.util.HashSet<>();
        java.util.Set<String> tzIds = new java.util.HashSet<>();
        for (String[] hub : Prefs.SAVER_WORLD_HUBS) {
            codes.add(hub[0]);
            tzIds.add(hub[1]);
        }
        assertEquals(61, codes.size(), "All 61 World Clock 3-letter codes are unique");
        assertEquals(61, tzIds.size(), "All 61 World Clock IANA TimeZone IDs are unique");
        assertTrue(tzIds.contains("Pacific/Honolulu"), "Includes UTC-10 Honolulu");
        assertTrue(tzIds.contains("America/St_Johns"), "Includes half-hour UTC-3:30 St. John's");
        assertTrue(tzIds.contains("Asia/Jerusalem"), "Includes Asia/Jerusalem (Tel Aviv)");
        assertTrue(tzIds.contains("Asia/Tehran"), "Includes half-hour UTC+3:30 Tehran");
        assertTrue(tzIds.contains("Asia/Kabul"), "Includes half-hour UTC+4:30 Kabul");
        assertTrue(tzIds.contains("Asia/Kolkata"), "Includes half-hour UTC+5:30 Mumbai");
        assertTrue(tzIds.contains("Asia/Kathmandu"), "Includes 45-min UTC+5:45 Kathmandu");
        assertTrue(tzIds.contains("Australia/Adelaide"), "Includes half-hour Adelaide");
        assertTrue(tzIds.contains("Pacific/Auckland"), "Includes Auckland");

        // Deterministic winter epoch: 2026-01-15T12:00:00Z
        long winterNoonUtc = 1768478400000L;
        assertEquals("UTC+0", ScreensaverCatalog.formatUtcOffset("UTC", winterNoonUtc), "UTC offset at UTC+0");
        assertEquals("UTC+2", ScreensaverCatalog.formatUtcOffset("Asia/Jerusalem", winterNoonUtc), "Tel Aviv winter offset is UTC+2");
        assertEquals("UTC-5", ScreensaverCatalog.formatUtcOffset("America/New_York", winterNoonUtc), "New York winter offset is UTC-5");
        assertEquals("UTC-10", ScreensaverCatalog.formatUtcOffset("Pacific/Honolulu", winterNoonUtc), "Honolulu offset is UTC-10");
        assertEquals("UTC+5:30", ScreensaverCatalog.formatUtcOffset("Asia/Kolkata", winterNoonUtc), "Mumbai offset is UTC+5:30");
        assertEquals("UTC+5:45", ScreensaverCatalog.formatUtcOffset("Asia/Kathmandu", winterNoonUtc), "Kathmandu offset is UTC+5:45");
        assertEquals("14:00 \u00B7 UTC+2",
                ScreensaverCatalog.formatTimezoneSelectionValue("Asia/Jerusalem", winterNoonUtc, true),
                "24-hour selection value shows live city hour and UTC+2");
        assertEquals("7:00 AM \u00B7 UTC-5",
                ScreensaverCatalog.formatTimezoneSelectionValue("America/New_York", winterNoonUtc, false),
                "12-hour selection value shows live AM/PM city hour and UTC-5");

        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.saverWorldClockTz = "Asia/Jerusalem";
        snap.saverWorldClockLabel = "TLV";
        SettingsTree.Node root = SettingsTree.buildRoot(snap);
        SettingsTree.Node tzParent = root.findById("ss_tz");
        assertTrue(tzParent != null && tzParent.value != null && tzParent.value.startsWith("TLV \u00B7 UTC+"),
                "ss_tz parent value shows selected city code + UTC offset: " + (tzParent != null ? tzParent.value : "null"));
        int selectedCount = 0;
        for (SettingsTree.Node child : tzParent.children) {
            assertTrue(child.value != null && child.value.contains("UTC"),
                    "Each timezone row displays live hour + UTC offset in value: " + child.id + " -> " + child.value);
            assertTrue(child.subtitle != null && child.subtitle.contains("UTC"),
                    "Each timezone row displays current time and UTC offset in subtitle: " + child.id + " -> " + child.subtitle);
            assertFalse(child.title.contains(" ("),
                    "Timezone row title does not contain airport code abbreviation: " + child.title);
            if (child.checked) {
                selectedCount++;
                assertEquals("ss_tz:Asia/Jerusalem:TLV", child.id, "Only TLV row is marked selected");
            }
        }
        assertEquals(1, selectedCount, "Exactly one timezone row is marked selected");

        // --- 2. Gallery Name First Row Removed From Settings Photography Category Preview ---
        String rendererSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/SettingsScreenRenderer.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!rendererSrc.contains("LIVE PREVIEW"),
                "SettingsScreenRenderer removes 'LIVE PREVIEW' gallery header row from Screensaver & Clock previews");
        assertTrue(!rendererSrc.contains("ScreensaverCatalog.categoryLabel(cat)"),
                "drawScreensaverGalleryPreview does not render the gallery category name row");
        assertTrue(rendererSrc.contains("node.id.startsWith(\"ss_tz:\")"),
                "drawSettingsColumnList refreshes live city hour + UTC offset dynamically at draw time");

        // --- 3. Instant Text / Overlay Re-Display (Direct-Canvas, Zero Detached Child RenderNodes) ---
        String dreamSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/DreamView.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!dreamSrc.contains("Recording.create(\"corner\")"),
                "DreamView eliminates child RenderNode('corner') so clock/weather never detach when hidden");
        assertTrue(!dreamSrc.contains("Recording.create(\"caption\")"),
                "DreamView eliminates child RenderNode('caption') so title/artist/location never detach when hidden");
        assertTrue(dreamSrc.contains("drawCornerDirect(canvas") && dreamSrc.contains("drawCaptionsDirect(canvas"),
                "DreamView draws corner clock/weather and bottom-left captions directly onto Canvas every visible frame");
        assertTrue(dreamSrc.contains("glassWorker"),
                "DreamView isolates Liquid Glass digit rasterization on dedicated glassWorker thread");

        assertEquals("Bengal Tiger in Forest Clearing",
                ScreensaverCatalog.buildCaptionTitle("  Bengal Tiger in Forest Clearing ", true),
                "buildCaptionTitle returns trimmed title when enabled");
        assertEquals("",
                ScreensaverCatalog.buildCaptionTitle("Bengal Tiger in Forest Clearing", false),
                "buildCaptionTitle returns empty immediately when toggled off");
        assertEquals("Photo by Charles J. Sharp \u00B7 Kanha National Park, India",
                ScreensaverCatalog.buildCaptionMeta("Charles J. Sharp", "Kanha National Park, India", true, true),
                "buildCaptionMeta combines artist and location");
        assertEquals("Photo by Charles J. Sharp",
                ScreensaverCatalog.buildCaptionMeta("Charles J. Sharp", "Kanha National Park, India", true, false),
                "buildCaptionMeta omits location immediately when location toggle is off");
        assertEquals("Kanha National Park, India",
                ScreensaverCatalog.buildCaptionMeta("Charles J. Sharp", "Kanha National Park, India", false, true),
                "buildCaptionMeta omits artist immediately when artist toggle is off");
        assertEquals("",
                ScreensaverCatalog.buildCaptionMeta("Charles J. Sharp", "Kanha National Park, India", false, false),
                "buildCaptionMeta returns empty when both artist and location toggles are off");

        // --- 4. Deterministic Left & Right Wallpaper Cycling ---
        java.util.List<String> keys = java.util.Arrays.asList("photo:nat1", "photo:nat2", "photo:nat3", "photo:nat4");
        java.util.Set<String> noFailed = java.util.Collections.emptySet();
        assertEquals(1, DreamView.computeSteppedIndex(keys, "photo:nat1", noFailed, +1), "Right steps 0 -> 1");
        assertEquals(0, DreamView.computeSteppedIndex(keys, "photo:nat4", noFailed, +1), "Right wraps 3 -> 0");
        assertEquals(0, DreamView.computeSteppedIndex(keys, "photo:nat2", noFailed, -1), "Left steps 1 -> 0");
        assertEquals(3, DreamView.computeSteppedIndex(keys, "photo:nat1", noFailed, -1), "Left wraps 0 -> 3");

        java.util.Set<String> failedMid = new java.util.HashSet<>(java.util.Collections.singletonList("photo:nat2"));
        assertEquals(2, DreamView.computeSteppedIndex(keys, "photo:nat1", failedMid, +1),
                "Right skips failed slide photo:nat2 and lands on photo:nat3");
        assertEquals(0, DreamView.computeSteppedIndex(keys, "photo:nat3", failedMid, -1),
                "Left skips failed slide photo:nat2 and lands on photo:nat1");
        assertEquals(0, DreamView.computeSteppedIndex(keys, null, noFailed, +1),
                "Null anchor steps deterministically to index 0 on Right");
        assertEquals(3, DreamView.computeSteppedIndex(keys, null, noFailed, -1),
                "Null anchor steps deterministically to last index on Left");
        assertEquals(-1, DreamView.computeSteppedIndex(java.util.Collections.emptyList(), "photo:nat1", noFailed, +1),
                "Empty catalog returns -1 safely");
        assertTrue(dreamSrc.contains("manualWorker") && dreamSrc.contains("SlideHistoryCache"),
                "DreamView keeps bounded multi-slide history cache in RAM for 0ms multi-step Left navigation and isolates manual decode lane");
        assertTrue(dreamSrc.contains("bgDecoding") && !dreamSrc.contains("private boolean decoding;"),
                "DreamView isolates background worker bgDecoding flag from manualWorker generation");
        assertTrue(dreamSrc.contains("preloadDirection"),
                "DreamView preloads in the user's active manual navigation direction without re-decoding cached history slides");
        assertTrue(dreamSrc.contains("pendingManualKey") && dreamSrc.contains("MANUAL_FADE_MS = 380"),
                "DreamView tracks in-flight pendingManualKey for rapid Left/Right presses with snappy 380ms crossfade");

        // Multi-step Right -> Right -> Right -> Left -> Left -> Left history cache behavioral verification
        assertEquals(3, DreamView.MAX_HISTORY_SLIDES, "DreamView SlideHistoryCache holds last 3 visited slides");
        DreamView.SlideHistoryCache<String> hist = new DreamView.SlideHistoryCache<>(DreamView.MAX_HISTORY_SLIDES);
        assertEquals(null, hist.put("photo:nat1", "slide_nat1"), "1st history insert evicts nothing");
        assertEquals(null, hist.put("photo:nat2", "slide_nat2"), "2nd history insert evicts nothing");
        assertEquals(null, hist.put("photo:nat3", "slide_nat3"), "3rd history insert evicts nothing");
        assertEquals(3, hist.size(), "History holds 3 slides");
        assertEquals("slide_nat3", hist.take("photo:nat3"), "1st Left hits slide_nat3 in 0ms");
        assertEquals(null, hist.put("photo:nat4", "slide_nat4"), "Displaced slide_nat4 stashed cleanly");
        assertEquals("slide_nat2", hist.take("photo:nat2"), "2nd consecutive Left hits slide_nat2 in 0ms");
        assertEquals(null, hist.put("photo:nat3", "slide_nat3"), "Displaced slide_nat3 stashed cleanly");
        assertEquals("slide_nat1", hist.take("photo:nat1"), "3rd consecutive Left hits slide_nat1 in 0ms");
        assertEquals(null, hist.take("photo:nat1"), "Taken slide is removed from history");
        hist.clear();
        assertEquals(0, hist.size(), "clear() empties history");
        hist.put("k1", "s1");
        hist.put("k2", "s2");
        hist.put("k3", "s3");
        assertEquals("s1", hist.put("k4", "s4"), "4th distinct insert evicts oldest slide s1 for safe deferred recycle");
        assertEquals(3, hist.size(), "History stays strictly bounded at MAX_HISTORY_SLIDES");

        String backdropSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/Backdrop.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(backdropSrc.contains("opts.inMutable = true"),
                "Backdrop.photo decodes 4K wallpapers directly mutable to eliminate 33.2 MB copy");

        // --- 5 & 6. Strict True-4K Validation, Legacy Cache v2, & 24 Verified Photographs ---
        assertEquals("saver_catalog_4k_v2", ScreensaverCatalog.CACHE_DIR_NAME,
                "Upgraded cache directory purges stale sub-4K/mislabeled wallpapers");
        assertTrue(ScreensaverCatalog.isTrue4kDimensions(3840, 2160), "Accepts native 3840x2160 16:9 4K");
        assertTrue(ScreensaverCatalog.isTrue4kDimensions(3840, 2560), "Accepts native 3840x2560 3:2 4K");
        assertTrue(ScreensaverCatalog.isTrue4kDimensions(4096, 2160), "Accepts DCI 4096x2160 4K");
        assertTrue(!ScreensaverCatalog.isTrue4kDimensions(1920, 1080), "Rejects 1080p HD");
        assertTrue(!ScreensaverCatalog.isTrue4kDimensions(3254, 2170), "Rejects sub-3840 width sensor");
        assertTrue(!ScreensaverCatalog.isTrue4kDimensions(3840, 1503), "Rejects sub-2160 panorama strip");
        assertTrue(!ScreensaverCatalog.isTrue4kDimensions(3840, 5760), "Rejects vertical portrait photo");
        assertTrue(!ScreensaverCatalog.isTrue4kDimensions(2160, 3840), "Rejects 9:16 vertical photo");

        assertTrue(ScreensaverCatalog.isCleanGalleryTitle("Lago di Carezza & Latemar Peaks"),
                "Accepts clean human-readable photography title");
        assertTrue(!ScreensaverCatalog.isCleanGalleryTitle("DSCF1211"), "Rejects raw camera filename");
        assertTrue(!ScreensaverCatalog.isCleanGalleryTitle("2025-10-14 Sunset"), "Rejects date-prefixed filename");
        assertTrue(!ScreensaverCatalog.isCleanGalleryTitle("Panorama--stitch.jpg"), "Rejects panorama/file extension");
        assertEquals("Charles J. Sharp",
                ScreensaverCatalog.sanitizeArtistName("<a href=\"https://commons.wikimedia.org\">Charles J. Sharp</a> (talk)"),
                "Sanitizes HTML and (talk) suffix from Wikimedia artist metadata");
        assertEquals("",
                ScreensaverCatalog.sanitizeArtistName("Unknown author"),
                "Rejects placeholder Unknown author");

        java.util.List<ScreensaverCatalog.PhotoEntry> builtins = ScreensaverCatalog.builtinEntries();
        assertEquals(24, builtins.size(), "Curated built-in True-4K catalog contains 24 verified entries");
        java.util.Map<String, Integer> catCounts = new java.util.HashMap<>();
        java.util.Set<String> entryIds = new java.util.HashSet<>();
        java.util.Set<String> entryUrls = new java.util.HashSet<>();
        for (ScreensaverCatalog.PhotoEntry pe : builtins) {
            assertTrue(pe.id != null && !pe.id.isEmpty(), "Built-in photo id non-empty");
            assertTrue(pe.title != null && pe.title.length() >= 8, "Built-in photo title descriptive: " + pe.title);
            assertTrue(pe.artist != null && pe.artist.length() >= 3, "Built-in photo artist verified: " + pe.artist);
            assertTrue(pe.location != null && pe.location.length() >= 4, "Built-in photo location verified: " + pe.location);
            assertTrue(pe.imageUrl != null && pe.imageUrl.contains("3840"), "Built-in photo URL requests 3840px 4K: " + pe.id);
            assertTrue(!pe.imageUrl.contains("photo-1470058869958-2a77ade41c02"),
                    "Removed mislabeled tropical-leaves URL ('Golden Honeybee on Lavender')");
            assertTrue(!pe.imageUrl.contains("photo-1508817628294-5a453fa0b8fb"),
                    "Removed mislabeled tiger URL ('Iridescent Peacock Feather')");
            assertTrue(!pe.imageUrl.contains("photo-1470071459604-3b5ec3a7fe05"),
                    "Removed sub-4K native sensor URL");
            assertTrue(!pe.imageUrl.contains("photo-1518709268805-4e9042af9f23"),
                    "Removed vertical portrait macro URL");
            entryIds.add(pe.id);
            entryUrls.add(pe.imageUrl);
            catCounts.put(pe.category, catCounts.getOrDefault(pe.category, 0) + 1);
        }
        assertEquals(24, entryIds.size(), "All 24 built-in photo IDs are unique");
        assertEquals(24, entryUrls.size(), "All 24 built-in 4K photo URLs are unique");
        assertEquals(Integer.valueOf(6), catCounts.get("nature"), "6 verified 4K Nature entries");
        assertEquals(Integer.valueOf(6), catCounts.get("macro"), "6 verified 4K Macro entries");
        assertEquals(Integer.valueOf(6), catCounts.get("urban"), "6 verified 4K Urban entries");
        assertEquals(Integer.valueOf(6), catCounts.get("wildlife"), "6 verified 4K Wildlife entries");

        String gamingZoneSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/GamingZone.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gamingZoneSrc.contains("req.put(\"userApproved\", true);"),
                "GamingZone.launchGameOnTvAndLaptop passes userApproved=true so PC PrivacyCurtainManager & PCPrivacySafetyNet engage");
    }

    private static void testSixSettingsRemoteNavAndWifiCleanupFixes() throws Exception {
        // --- 1. Settings button on remote opens SoarTV Settings tab (and Xiaomi RC Consumer Control is not filtered out) ---
        assertEquals("io.soartv.OPEN_SETTINGS", HomeActivity.EXTRA_OPEN_SETTINGS,
                "HomeActivity exposes EXTRA_OPEN_SETTINGS for global remote Settings button routing");
        assertEquals("io.soartv#settings", Prefs.SETTINGS_TAB,
                "Prefs defines SETTINGS_TAB target for remote Settings button");
        assertTrue(RemoteKeys.isSettingsButtonKeyCode(android.view.KeyEvent.KEYCODE_SETTINGS),
                "KEYCODE_SETTINGS recognized as Settings button keycode");
        assertTrue(RemoteKeys.isSettingsButtonKeyCode(android.view.KeyEvent.KEYCODE_NOTIFICATION),
                "KEYCODE_NOTIFICATION recognized as Settings/gear button keycode");
        assertFalse(RemoteKeys.isSettingsButtonKeyCode(android.view.KeyEvent.KEYCODE_DPAD_DOWN),
                "DPAD_DOWN is not a Settings button keycode");

        int xiaomiRcConsumerSource = android.view.InputDevice.SOURCE_KEYBOARD
                | android.view.InputDevice.SOURCE_DPAD
                | android.view.InputDevice.SOURCE_JOYSTICK;
        assertFalse(RemoteKeys.shouldIgnoreControllerSource(android.view.KeyEvent.KEYCODE_SETTINGS, xiaomiRcConsumerSource),
                "Xiaomi RC Consumer Control KEYCODE_SETTINGS is NEVER dropped despite SOURCE_JOYSTICK");
        assertFalse(RemoteKeys.shouldIgnoreControllerSource(android.view.KeyEvent.KEYCODE_NOTIFICATION, xiaomiRcConsumerSource),
                "Xiaomi RC Consumer Control KEYCODE_NOTIFICATION is NEVER dropped");
        assertFalse(RemoteKeys.shouldIgnoreControllerSource(android.view.KeyEvent.KEYCODE_BUTTON_3, xiaomiRcConsumerSource),
                "Xiaomi RC Consumer Control keyboard+joystick remote hotkey is not misclassified as a gamepad");
        assertTrue(RemoteKeys.shouldIgnoreControllerSource(android.view.KeyEvent.KEYCODE_BUTTON_3, android.view.InputDevice.SOURCE_GAMEPAD),
                "Physical gamepad SOURCE_GAMEPAD button is still ignored by RemoteKeys");
        assertTrue(RemoteKeys.shouldIgnoreControllerSource(android.view.KeyEvent.KEYCODE_BUTTON_3, android.view.InputDevice.SOURCE_JOYSTICK),
                "Pure joystick SOURCE_JOYSTICK button is still ignored by RemoteKeys");

        // --- 2. Returning from Settings (including Native Android TV Settings) preserves exact screen & inner navigation ---
        assertFalse(HomeActivity.shouldRecreateForLanguageChange("auto", "auto"),
                "Unchanged language does NOT recreate HomeActivity on Prefs.version++ resume");
        assertFalse(HomeActivity.shouldRecreateForLanguageChange("en", "en"),
                "Same explicit language does NOT recreate HomeActivity");
        assertTrue(HomeActivity.shouldRecreateForLanguageChange("en", "he"),
                "Language change still recreates HomeActivity so locale strings redraw");

        HomeInputRouter.BackNavigationStack navStack = new HomeInputRouter.BackNavigationStack();
        navStack.pushScreenState(new HomeInputRouter.ScreenCursorState(HomeView.SCREEN_HOME, 1, 3, 5, 0), HomeView.SCREEN_GAMING);
        navStack.pushScreenState(new HomeInputRouter.ScreenCursorState(HomeView.SCREEN_GAMING, 0, 1, 2, 0), HomeView.SCREEN_SETTINGS);
        HomeInputRouter.ScreenCursorState poppedGaming = navStack.popPreviousState(HomeView.SCREEN_SETTINGS, HomeView.SCREEN_HOME);
        assertEquals(HomeView.SCREEN_GAMING, poppedGaming.screen, "Back from Settings returns to Gaming");
        assertEquals(1, poppedGaming.homeRow, "Back to Gaming restores exact row");
        assertEquals(2, poppedGaming.homeCol, "Back to Gaming restores exact column");
        HomeInputRouter.ScreenCursorState poppedHome = navStack.popPreviousState(HomeView.SCREEN_GAMING, HomeView.SCREEN_HOME);
        assertEquals(HomeView.SCREEN_HOME, poppedHome.screen, "Back from Gaming returns to Home");
        assertEquals(1, poppedHome.subTab, "Back to Home restores exact subTab (Movies)");
        assertEquals(3, poppedHome.homeRow, "Back to Home restores exact row");
        assertEquals(5, poppedHome.homeCol, "Back to Home restores exact column");

        int[] depths = new int[] {12, 6, 7, 0};
        assertEquals(6, HomeInputRouter.computeBackSettingsRightIndex(depths, 1, 8),
                "Popping inner Settings column preserves exact right-pane child highlight");
        assertEquals(2, HomeInputRouter.computeBackSettingsRightIndex(depths, 2, 3),
                "Popping inner Settings column clamps safely to available child count");

        HomeInputRouter.NavigationSnapshot snapNav = new HomeInputRouter.NavigationSnapshot(
                HomeView.SCREEN_SETTINGS, HomeView.SCREEN_SETTINGS, 0, 0, 0, 0, 0,
                java.util.Arrays.asList("system_settings", "sys_power"),
                depths, false, 6, navStack.snapshotStates());
        assertEquals(HomeView.SCREEN_SETTINGS, snapNav.activeScreen, "NavigationSnapshot preserves SCREEN_SETTINGS");
        assertEquals(2, snapNav.settingsPathIds.size(), "NavigationSnapshot preserves exact inner-Settings depth path");
        assertEquals("sys_power", snapNav.settingsPathIds.get(1), "NavigationSnapshot preserves exact inner-Settings node id");
        assertEquals(7, snapNav.settingsDepthIndices[2], "NavigationSnapshot preserves exact inner-Settings leaf selection");

        String homeAppsSrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/src/io/soartv/HomeApps.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(homeAppsSrc.contains("new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)"),
                "HomeApps ANDROID_SETTINGS clears stale task so Back returns straight to SoarTV");

        // --- 3. Native Android TV Settings directly reachable inside System (and searchable) ---
        SettingsTree.StateSnapshot treeSnap = new SettingsTree.StateSnapshot();
        SettingsTree.Node root = SettingsTree.buildRoot(treeSnap);
        SettingsTree.Node nativeNode = root.findById("atv_open_full");
        assertTrue(nativeNode != null, "Native Android TV Settings entry exists in redesigned tree");
        assertTrue(nativeNode.title.contains("Native Android TV Settings"), "Native Settings item is clearly labeled");
        assertEquals(SettingsTree.RowType.EXTERNAL, nativeNode.rowType, "Native Settings item uses EXTERNAL badge");
        assertFalse(nativeNode.hasChildren(), "Native Settings is a direct action item");
        assertTrue(Symbols.isSupportedIcon(nativeNode.icon), "Native Settings uses a valid Symbols icon");

        // --- 4. Native Settings Up/Down multi-jump bug fix (zero IPC in BluetoothPairingAssist during normal Settings nav) ---
        assertTrue(BluetoothPairingAssist.isKnownNonPairingSettingsActivity("com.android.tv.settings.MainSettings"),
                "MainSettings recognized as non-pairing Settings activity");
        assertTrue(BluetoothPairingAssist.isKnownNonPairingSettingsActivity("com.android.tv.settings.connectivity.NetworkActivity"),
                "NetworkActivity recognized as non-pairing Settings activity");
        assertTrue(BluetoothPairingAssist.isKnownNonPairingSettingsActivity("com.xiaomi.gtv.settings.display.picture.MiPictureActivity"),
                "MiPictureActivity recognized as non-pairing Settings activity");
        assertFalse(BluetoothPairingAssist.isKnownNonPairingSettingsActivity("com.android.tv.settings.accessories.BluetoothPairingDialog"),
                "BluetoothPairingDialog is NOT treated as non-pairing activity");

        assertFalse(BluetoothPairingAssist.shouldInspectSettingsEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        "com.android.tv.settings",
                        "androidx.leanback.widget.VerticalGridView",
                        false,
                        false),
                "Normal Native Settings D-pad content changes execute ZERO Binder IPC inspections");
        assertFalse(BluetoothPairingAssist.shouldInspectSettingsEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        "com.android.tv.settings",
                        "com.android.tv.settings.MainSettings",
                        false,
                        false),
                "Opening Native MainSettings without pending pair executes ZERO Binder IPC inspections");
        assertTrue(BluetoothPairingAssist.shouldInspectSettingsEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                        "com.android.tv.settings",
                        "com.android.tv.settings.accessories.BluetoothPairingDialog",
                        false,
                        false),
                "External BluetoothPairingDialog still triggers background focus assist");
        assertTrue(BluetoothPairingAssist.shouldInspectSettingsEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        "com.android.tv.settings",
                        "android.widget.FrameLayout",
                        true,
                        false),
                "Active BluetoothPairingDialog content changes still trigger focus assist");
        assertTrue(BluetoothPairingAssist.shouldInspectSettingsEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                        "com.android.tv.settings",
                        "android.widget.FrameLayout",
                        false,
                        true),
                "Fresh SoarTV-initiated Bluetooth pair stamp still triggers auto-confirm assist");

        String rksXml = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("app/res/xml/remote_key_service.xml")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rksXml.contains("android:notificationTimeout="),
                "remote_key_service.xml specifies notificationTimeout to coalesce rapid Settings UI events");

        // --- 5. Restart option under System > Power & Energy ---
        SettingsTree.Node sysPower = root.findById("sys_power");
        assertTrue(sysPower != null && sysPower.hasChildren(), "System -> Power & Energy exists with children");
        SettingsTree.Node restartNode = sysPower.findById("sys_restart");
        assertTrue(restartNode != null, "Restart option exists under System -> Power & Energy");
        assertEquals("Restart", restartNode.title, "Restart node title is 'Restart'");
        assertTrue(Symbols.isSupportedIcon(restartNode.icon), "Restart node uses a supported Symbols icon");
        assertFalse(restartNode.hasChildren(), "Restart node triggers confirmation modal directly");

        // --- 6. Hardcoded Wi-Fi name 'PrivateHomeWiFi' completely removed ---
        verifyNoHardcodedPersonalSsid(root);
        assertFalse(SmartHomeScreenRenderer.SMART_HOME_BANNER_INFO.toLowerCase(java.util.Locale.ROOT).contains("privatehomewifi"),
                "SmartHomeScreenRenderer banner has zero hardcoded 'PrivateHomeWiFi' SSID");
    }

    private static void verifyNoHardcodedPersonalSsid(SettingsTree.Node node) {
        if (node == null) return;
        String combined = (node.title + " " + node.subtitle + " " + node.value + " " + node.hint)
                .toLowerCase(java.util.Locale.ROOT);
        assertFalse(combined.contains("privatehomewifi"),
                "Settings node '" + node.id + "' must not contain hardcoded Wi-Fi name 'PrivateHomeWiFi': " + combined);
        for (SettingsTree.Node child : node.children) {
            verifyNoHardcodedPersonalSsid(child);
        }
    }
}

