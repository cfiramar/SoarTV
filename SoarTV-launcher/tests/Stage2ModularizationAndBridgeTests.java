package io.soartv;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive Desktop JVM unit test suite for Stage 2 ({@code BP-05}) Modularization and
 * Cross-Track Integration Bridges across Tracks A, B, C, and D.
 *
 * <p><b>Why:</b> Verifies that extracting cohesive single-responsibility collaborators from {@link HomeView}
 * preserves 100% of existing Launcher behavior and headless Desktop JVM compatibility, while guaranteeing
 * that on-TV Remote Pairing, Paired Remote RBAC management, and Encrypted Cloud Key Vault Backup/Restore
 * work seamlessly end-to-end.
 *
 * <p><b>What:</b> Exercises:
 * <ul>
 *   <li>All 5 extracted collaborators: {@link HomeInputRouter}, {@link ModalOverlayRenderer},
 *       {@link SettingsScreenRenderer}, {@link GamingScreenRenderer}, and {@link SmartHomeScreenRenderer}</li>
 *   <li>Backward-compatible {@link HomeView} delegators ({@code RowScrollMemory}, {@code BackNavigationStack},
 *       {@code LiquidGlassInputState}, {@code buildRowMemoryKey})</li>
 *   <li>Cross-track {@link Prefs} &lt;-&gt; {@link Secrets} fallback resolution and vault mirroring</li>
 *   <li>{@code "New Remote Detected"} lifecycle: request -&gt; prompt notification -&gt; 1-click TV approval
 *       ({@code GUEST}, {@code HOUSEHOLD}, {@code ADMIN}) &amp; dismiss -&gt; invite -&gt; role change &amp; kick/revoke</li>
 *   <li>{@link SettingsTree} {@code paired_remotes}, {@code remote_invite:*}, {@code remote_manage:*},
 *       {@code vault_backup}, {@code vault_restore}, zero-emoji compliance, and universal {@link Symbols} icon validity</li>
 *   <li>End-to-end {@link Secrets} -&gt; {@link CloudVaultCrypto#encryptMap} -&gt; {@link CloudVaultCrypto#decryptMap}
 *       -&gt; {@link Secrets#importVaultSecrets} round-trip</li>
 * </ul>
 */
public final class Stage2ModularizationAndBridgeTests {

    private static int passed = 0;

    private Stage2ModularizationAndBridgeTests() {
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if ((expected == null && actual != null) || (expected != null && !expected.equals(actual))) {
            throw new AssertionError(message + " | expected=" + expected + ", actual=" + actual);
        }
        passed++;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        testExtractedCollaboratorsHeadlessInstantiationAndPureLogic();
        testHomeViewBackwardCompatibleDelegators();
        testCrossTrackPrefsSecretsFallbackAndVaultSync();
        testNewRemoteDetectedPairingApprovalDismissInviteAndKickLifecycle();
        testSettingsTreePairedRemotesVaultNodesAndUniversalSymbolIcons();
        testEndToEndSecretsToCloudVaultCryptoRoundTrip();
        testSecretsMemoryCacheEliminatesPerFrameDecryptAndNegativeLookupOverhead();
        testLightModeCrispWhiteBackgroundAndSoftenedDarkestBlack();
        testContinueWatchingEpisodeTitleExtractionAndHighContrastScrim();
        testControllerPinkVsBlueTagIsolation();
        testBluetoothPairingAssistZeroIpcKeyFastPathAndBleSafeDiscoveryCap();
        testBackgroundPauseDebouncedSaveAndStreamingMemoryTrim();
        testTvInputFieldSyncSnapshotPersistenceSecurityAndPrecedence();
        System.out.println("ALL " + passed + " STAGE 2 MODULARIZATION & BRIDGE ASSERTIONS PASSED.");
    }

    private static void testExtractedCollaboratorsHeadlessInstantiationAndPureLogic() {
        // 1. HomeInputRouter: headless instantiation + row memory + back stack + pairing modal nav + 6-digit formatting
        HomeInputRouter router = new HomeInputRouter();
        String rowKey = HomeInputRouter.buildRowMemoryKey(HomeView.SCREEN_HOME, 0, HomeView.ROW_GENRE, "tv", "Sci-Fi", "Sci-Fi");
        router.rowScrollMemory().save(rowKey, 6, 1340f);
        assertEquals(6, router.rowScrollMemory().restoreCol(rowKey, 15, 0),
                "HomeInputRouter RowScrollMemory restores saved column");
        assertTrue(Math.abs(router.rowScrollMemory().restoreScrollX(rowKey, 0f) - 1340f) < 0.01f,
                "HomeInputRouter RowScrollMemory restores saved scrollX");

        router.backStack().pushScreen(HomeView.SCREEN_HOME, HomeView.SCREEN_SMARTHOME);
        router.backStack().pushScreen(HomeView.SCREEN_SMARTHOME, HomeView.SCREEN_SETTINGS);
        assertEquals(HomeView.SCREEN_SMARTHOME, router.backStack().popPrevious(HomeView.SCREEN_SETTINGS, HomeView.SCREEN_HOME),
                "HomeInputRouter BackNavigationStack pops prior screen");
        assertEquals(HomeView.SCREEN_HOME, router.backStack().popPrevious(HomeView.SCREEN_SMARTHOME, HomeView.SCREEN_HOME),
                "HomeInputRouter BackNavigationStack pops back to Home");

        assertEquals("482 917", HomeInputRouter.formatSixDigitCode("482917"),
                "HomeInputRouter formats 6-digit code with clean middle space");
        assertEquals("005 120", HomeInputRouter.formatSixDigitCode("005120"),
                "HomeInputRouter preserves leading zeros in 6-digit code");
        assertEquals(1, HomeInputRouter.movePairingModalFocus(0, 1, 0),
                "Pairing modal D-pad Right moves from button 0 (Guest) to 1 (Household)");
        assertEquals(2, HomeInputRouter.movePairingModalFocus(1, 0, 1),
                "Pairing modal D-pad Down moves from button 1 (Household) to 2 (Admin)");
        assertEquals(3, HomeInputRouter.movePairingModalFocus(2, 1, 0),
                "Pairing modal D-pad Right moves from button 2 (Admin) to 3 (Dismiss)");
        assertEquals(3, HomeInputRouter.movePairingModalFocus(3, 1, 0),
                "Pairing modal focus clamps at button 3 (Dismiss)");
        assertEquals(0, HomeInputRouter.movePairingModalFocus(0, -1, 0),
                "Pairing modal focus clamps at button 0 (Guest)");

        assertEquals(6, HomeInputRouter.moveGridIndex(1, 0, 1, 5, 14),
                "moveGridIndex moves down by 1 row of 5 columns");
        assertEquals(-1, HomeInputRouter.moveGridIndex(2, 0, -1, 5, 14),
                "moveGridIndex returns -1 sentinel when moving Up out of top row");

        // 2. ModalOverlayRenderer: headless instantiation without Android Paint/RectF Stub! errors
        ModalOverlayRenderer modalRenderer = new ModalOverlayRenderer();
        assertEquals(4, ModalOverlayRenderer.PAIRING_ACTION_COUNT,
                "Pairing platter exposes 4 selectable role/dismiss actions");
        for (String label : ModalOverlayRenderer.PAIRING_ACTION_LABELS) {
            assertTrue(label != null && !label.isEmpty() && !containsEmoji(label),
                    "Pairing platter action label contains zero emojis: " + label);
        }
        ModalOverlayRenderer.LiquidGlassInputState inputState = new ModalOverlayRenderer.LiquidGlassInputState("", true);
        inputState.appendChar('V');
        inputState.appendChar('a');
        inputState.appendChar('u');
        inputState.appendChar('l');
        inputState.appendChar('t');
        assertEquals("Vault", inputState.getText(), "ModalOverlayRenderer.LiquidGlassInputState stores passphrase");
        assertEquals("\u2022\u2022\u2022\u2022\u2022", inputState.displayValue(), "Passphrase masked by default");
        inputState.toggleReveal();
        assertEquals("Vault", inputState.displayValue(), "Passphrase revealed after toggle");
        assertTrue(modalRenderer.computeMenuHeight(4, true, 1f) > modalRenderer.computeMenuHeight(2, false, 1f),
                "ModalOverlayRenderer computes larger menu height for more rows and subtitle");

        // 3. SettingsScreenRenderer: headless instantiation + split/full-width layout & scroll math
        SettingsScreenRenderer settingsRenderer = new SettingsScreenRenderer();
        SettingsTree.Node leafA = new SettingsTree.Node("a", "Leaf A", "", "", "check", false, null);
        SettingsTree.Node leafB = new SettingsTree.Node("b", "Leaf B", "", "", "check", true, null);
        SettingsTree.Node parentOfLeaves = new SettingsTree.Node("parent_leaves", "Parent", "", "", "settings", false,
                Arrays.asList(leafA, leafB));
        SettingsTree.Node grandParent = new SettingsTree.Node("grand", "Grand", "", "", "settings", false,
                Collections.singletonList(parentOfLeaves));

        assertEquals(false, SettingsScreenRenderer.anyGrandchild(parentOfLeaves),
                "Parent containing only leaf nodes has no grandchildren");
        assertEquals(true, SettingsScreenRenderer.anyGrandchild(grandParent),
                "Grandparent node detects grandchild presence");
        assertEquals(1, SettingsScreenRenderer.findCheckedOrFirstIndex(parentOfLeaves.children),
                "findCheckedOrFirstIndex selects checked child index");
        assertEquals(true, SettingsScreenRenderer.isLastColumnFullWidth(Arrays.asList("users", "paired_remotes"), parentOfLeaves),
                "Final leaf column expands to 100% full width");
        assertEquals(false, SettingsScreenRenderer.isLastColumnFullWidth(Collections.singletonList("ss_clock"),
                new SettingsTree.Node("ss_clock", "Clock", "", "", "clock", false, Arrays.asList(leafA, leafB))),
                "Screensaver clock preview column keeps 60/40 split for live preview");
        assertTrue(Math.abs(SettingsScreenRenderer.computeLeftColumnWidth(1760f, 28f, true) - 1760f) < 0.01f,
                "Full-width leaf column uses 100% width");
        assertTrue(Math.abs(SettingsScreenRenderer.computeLeftColumnWidth(1760f, 28f, false) - (1732f * 0.38f)) < 0.01f,
                "Two-panel left rail uses 38% of available width");
        assertEquals(3, SettingsScreenRenderer.computeFirstVisibleRow(12, 6, 8),
                "computeFirstVisibleRow scrolls list to keep selected row visible");
        settingsRenderer.recycleBitmaps();

        // 4. GamingScreenRenderer: headless instantiation + game catalog filtering + subtitle + pill wrap
        GamingScreenRenderer gamingRenderer = new GamingScreenRenderer();
        assertTrue(gamingRenderer != null, "GamingScreenRenderer instantiates cleanly on headless JVM");
        List<GamingZone.GameItem> rawGames = Arrays.asList(
                new GamingZone.GameItem("g1", "Cyberpunk 2077", "steam", "", false),
                new GamingZone.GameItem("g2", "Perm Hidden Game", "steam", "", false),
                new GamingZone.GameItem("sys1", "Desktop", "system", "", false),
                new GamingZone.GameItem("g3", "Hades II", "pc", "", true)
        );
        Set<String> permHidden = new HashSet<>(Collections.singletonList("g2"));
        List<GamingZone.GameItem> filtered = GamingScreenRenderer.filterVisibleGames(rawGames, permHidden);
        assertEquals(1, filtered.size(), "filterVisibleGames excludes permanently hidden, inline-hidden, and system entries");
        assertEquals("g1", filtered.get(0).id, "Cyberpunk 2077 remains visible");
        assertEquals("Steam", GamingScreenRenderer.formatGameSubtitle(rawGames.get(0), "Hidden", "Recently Played"),
                "Steam game subtitle formatted cleanly");
        assertEquals(true, GamingScreenRenderer.shouldWrapPillToNextLine(1500f, 380f, 1840f, 80f),
                "shouldWrapPillToNextLine wraps overflowing controller/PC status pill");
        assertEquals(false, GamingScreenRenderer.shouldWrapPillToNextLine(80f, 380f, 1840f, 80f),
                "shouldWrapPillToNextLine never wraps the first pill at left margin");

        // 5. SmartHomeScreenRenderer: headless instantiation + Kelvin labels + WiZ bulb row visibility + step helpers
        SmartHomeScreenRenderer smartRenderer = new SmartHomeScreenRenderer();
        assertTrue(smartRenderer != null, "SmartHomeScreenRenderer instantiates cleanly on headless JVM");
        assertEquals("Warm (2700K)", SmartHomeScreenRenderer.formatKelvinLabel(2700, "Warm", "Soft White", "Neutral", "Daylight", "Cool"),
                "2700K formatted as Warm (2700K)");
        assertEquals("Daylight (5000K)", SmartHomeScreenRenderer.formatKelvinLabel(5000, "Warm", "Soft White", "Neutral", "Daylight", "Cool"),
                "5000K formatted as Daylight (5000K)");
        assertEquals(false, SmartHomeScreenRenderer.shouldShowWizBulbsRow("All", 4),
                "Smart-Home Rule 1: Bulbs row hidden when Room is All");
        assertEquals(false, SmartHomeScreenRenderer.shouldShowWizBulbsRow("Bedroom", 1),
                "Bulbs row hidden when room has only 1 bulb");
        assertEquals(true, SmartHomeScreenRenderer.shouldShowWizBulbsRow("Living Room", 3),
                "Bulbs row shown when specific room has multiple bulbs");
        assertEquals(10, SmartHomeScreenRenderer.stepBrightness(10, -1), "Brightness clamps at 10% minimum");
        assertEquals(100, SmartHomeScreenRenderer.stepBrightness(95, 1), "Brightness clamps at 100% maximum");
        assertEquals(20, SmartHomeScreenRenderer.stepBoilerMinutes(15, 1), "Boiler timer steps by 5 minutes");
    }

    private static void testHomeViewBackwardCompatibleDelegators() {
        HomeView.RowScrollMemory mem = new HomeView.RowScrollMemory();
        String key = HomeView.buildRowMemoryKey(HomeView.SCREEN_HOME, 1, HomeView.ROW_GENRE, "movie", "Thriller", "Thriller");
        mem.save(key, 4, 880f);
        assertEquals(4, mem.restoreCol(key, 10, 0), "HomeView.RowScrollMemory forwarder works identically");

        HomeView.BackNavigationStack stack = new HomeView.BackNavigationStack();
        stack.pushScreen(HomeView.SCREEN_HOME, HomeView.SCREEN_REELS);
        assertEquals(HomeView.SCREEN_HOME, stack.popPrevious(HomeView.SCREEN_REELS, HomeView.SCREEN_HOME),
                "HomeView.BackNavigationStack forwarder works identically");

        HomeView.LiquidGlassInputState st = new HomeView.LiquidGlassInputState("abc", true);
        assertEquals("\u2022\u2022\u2022", st.displayValue(),
                "HomeView.LiquidGlassInputState forwarder masks secret characters");
    }

    private static void testCrossTrackPrefsSecretsFallbackAndVaultSync() throws Exception {
        File tempSecretsDir = Files.createTempDirectory("soartv_stage2_prefs_secrets_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tempSecretsDir);
            Secrets.putSecret(null, Secrets.OMDB_KEY, "vault_omdb_key_9001");
            Secrets.putSecret(null, Secrets.MDBLIST_KEY, "vault_mdblist_key_9002");
            Secrets.putSecret(null, Secrets.GEMINI_API_KEY, "vault_gemini_key_9003");
            Secrets.putSecret(null, Secrets.GITHUB_TOKEN, "vault_github_pat_9004");

            // Priority check: runtime override > Secrets > Config fallback
            assertEquals("runtime_override",
                    Prefs.resolveKeyWithVaultFallback("runtime_override", null, Secrets.OMDB_KEY, "config_fallback"),
                    "Non-empty SharedPreferences override wins first");
            assertEquals("vault_omdb_key_9001",
                    Prefs.resolveKeyWithVaultFallback("", null, Secrets.OMDB_KEY, "config_fallback"),
                    "Empty SharedPreferences override resolves hardware-encrypted Secrets value before Config");
            assertEquals("config_fallback",
                    Prefs.resolveKeyWithVaultFallback("", null, "non_existent_secret_name", "config_fallback"),
                    "Falls back to Config value when both SharedPreferences and Secrets are empty");

            // Direct Prefs accessors in headless JVM (app == null, Secrets dir set)
            assertEquals("vault_omdb_key_9001", Prefs.omdbKey(), "Prefs.omdbKey() reads encrypted Secrets fallback");
            assertEquals("vault_mdblist_key_9002", Prefs.mdblistKey(), "Prefs.mdblistKey() reads encrypted Secrets fallback");
            assertEquals("vault_gemini_key_9003", Prefs.geminiKey(), "Prefs.geminiKey() reads encrypted Secrets fallback");
            assertEquals("vault_github_pat_9004", Prefs.githubToken(), "Prefs.githubToken() reads encrypted Secrets fallback");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(tempSecretsDir);
        }
    }

    private static void testNewRemoteDetectedPairingApprovalDismissInviteAndKickLifecycle() throws Exception {
        File tempSecretsDir = Files.createTempDirectory("soartv_stage2_pairing_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tempSecretsDir);
            AtomicLong fakeClock = new AtomicLong(1_760_000_000_000L);
            RemoteAuthManager auth = new RemoteAuthManager(null, fakeClock::get);
            AtomicInteger promptCount = new AtomicInteger(0);
            auth.setPromptListener(promptCount::incrementAndGet);

            // 1. Phone triggers "New Remote Detected" -> TV receives prompt callback and active pending request
            RemoteAuthManager.IssueResult reqRes = auth.createPairRequest(
                    "Guest iPhone 16", "client_guest_1", "192.168.1.44", "http://192.168.1.100:8484");
            assertEquals(200, reqRes.httpStatus, "createPairRequest succeeds");
            assertEquals(1, promptCount.get(), "PromptListener notified when pairing request arrives");

            RemoteAuthManager.PendingPairRequest pending = auth.getActivePendingRequest();
            assertTrue(pending != null, "Active pending request available for on-TV Liquid Glass modal");
            assertEquals("Guest iPhone 16", pending.deviceName, "Pending request preserves sanitized phone name");
            assertEquals(6, pending.sixDigitCode.length(), "Pending request carries 6-digit code");
            assertTrue(pending.pairUrl.startsWith("http://192.168.1.100:8484/?pair="), "Pending request carries scannable QR pairUrl");

            // 2. TV user clicks Button 0: "Approve as Guest (30 Days)"
            RemoteAuthManager.PairedDevice guestDev = auth.approvePendingOnTv(RemoteAuthManager.Role.GUEST);
            assertTrue(guestDev != null, "approvePendingOnTv(GUEST) creates paired Guest device");
            assertEquals(RemoteAuthManager.Role.GUEST, guestDev.role, "Approved device has GUEST role");
            assertEquals(fakeClock.get() + RemoteAuthManager.GUEST_TTL_MS, guestDev.expiresAtMs, "Guest device expires in 30 days");
            assertEquals(null, auth.getActivePendingRequest(), "Active pending modal closes immediately after TV approval");
            assertEquals(2, promptCount.get(), "PromptListener notified when modal closes on approval");

            // Phone polls and receives token
            RemoteAuthManager.IssueResult pollApproved = auth.pollPairRequest(pending.requestId);
            assertTrue(pollApproved.jsonBody.contains("\"status\":\"approved\""), "Polling phone receives approved token");

            // 3. Second request -> TV user clicks Button 3 / Back: "Dismiss"
            auth.createPairRequest("Unknown Phone", "client_2", "192.168.1.55", "http://192.168.1.100:8484");
            RemoteAuthManager.PendingPairRequest toDismiss = auth.getActivePendingRequest();
            assertTrue(toDismiss != null, "Second pending request active");
            auth.dismissPendingOnTv();
            assertEquals(null, auth.getActivePendingRequest(), "Active pending request cleared after dismissPendingOnTv");
            RemoteAuthManager.IssueResult pollDismissed = auth.pollPairRequest(toDismiss.requestId);
            assertTrue(pollDismissed.jsonBody.contains("\"status\":\"dismissed\""), "Polling phone sees dismissed status");

            // 4. Settings -> Invite Household Remote & Invite Admin Remote
            RemoteAuthManager.PendingPairRequest householdInvite = auth.createInvite(
                    RemoteAuthManager.Role.HOUSEHOLD, "http://192.168.1.100:8484");
            assertEquals(RemoteAuthManager.Role.HOUSEHOLD, householdInvite.presetRole, "Household invite presets HOUSEHOLD role");
            RemoteAuthManager.IssueResult verifyHousehold = auth.verifyPairing(
                    householdInvite.sixDigitCode, "", "Partner Pixel 9", "192.168.1.60");
            assertEquals(200, verifyHousehold.httpStatus, "6-digit code verifies Household invite");
            assertEquals(RemoteAuthManager.Role.HOUSEHOLD, verifyHousehold.device.role, "Partner phone receives HOUSEHOLD role");
            assertEquals(0L, verifyHousehold.device.expiresAtMs, "HOUSEHOLD role never expires");

            // 5. Role promote/demote & Kick/Revoke from Settings -> Paired Remotes & Guests
            assertEquals(2, auth.listDevices().size(), "Two remotes currently paired");
            assertTrue(auth.setDeviceRole(guestDev.deviceId, RemoteAuthManager.Role.ADMIN), "Promote Guest remote to ADMIN");
            RemoteAuthManager.PairedDevice promoted = null;
            for (RemoteAuthManager.PairedDevice d : auth.listDevices()) {
                if (d.deviceId.equals(guestDev.deviceId)) promoted = d;
            }
            assertTrue(promoted != null && promoted.role == RemoteAuthManager.Role.ADMIN && promoted.expiresAtMs == 0L,
                    "Promoted ADMIN remote has permanent non-expiring access");

            assertTrue(auth.revokeDevice(guestDev.deviceId), "Kick / Revoke Remote removes device immediately");
            assertEquals(1, auth.listDevices().size(), "Only 1 paired device remains after kick");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(tempSecretsDir);
        }
    }

    private static void testSettingsTreePairedRemotesVaultNodesAndUniversalSymbolIcons() {
        long nowMs = System.currentTimeMillis();
        SettingsTree.StateSnapshot snap = new SettingsTree.StateSnapshot();
        snap.referenceNowMs = nowMs;
        snap.hasSupabase = true;
        snap.pairedRemotes = Arrays.asList(
                new RemoteAuthManager.PairedDevice("rem_admin1", "Alex iPhone", "a".repeat(64),
                        RemoteAuthManager.Role.ADMIN, "owner@example.com", nowMs, 0L, nowMs),
                new RemoteAuthManager.PairedDevice("rem_house1", "Living Room iPad", "b".repeat(64),
                        RemoteAuthManager.Role.HOUSEHOLD, "", nowMs, 0L, nowMs),
                new RemoteAuthManager.PairedDevice("rem_guest1", "Visitor Phone", "c".repeat(64),
                        RemoteAuthManager.Role.GUEST, "", nowMs, nowMs + 12L * 86_400_000L, nowMs),
                new RemoteAuthManager.PairedDevice("rem_exp1", "Old Visitor", "d".repeat(64),
                        RemoteAuthManager.Role.GUEST, "", nowMs - 40L * 86_400_000L, nowMs - 86_400_000L, nowMs - 86_400_000L)
        );

        SettingsTree.Node root = SettingsTree.buildRoot(snap);

        // Verify Paired Remotes & Guests submenu under Users & Accounts
        SettingsTree.Node pairedRemotes = root.findById("paired_remotes");
        assertTrue(pairedRemotes != null, "SettingsTree contains 'paired_remotes' under Users & Accounts");
        assertTrue(pairedRemotes.findById("remote_invite:GUEST") != null, "Contains Invite Guest Remote (30 Days)");
        assertTrue(pairedRemotes.findById("remote_invite:HOUSEHOLD") != null, "Contains Invite Household Remote");
        assertTrue(pairedRemotes.findById("remote_invite:ADMIN") != null, "Contains Invite Admin Remote");

        SettingsTree.Node adminRow = pairedRemotes.findById("remote_manage:rem_admin1");
        SettingsTree.Node houseRow = pairedRemotes.findById("remote_manage:rem_house1");
        SettingsTree.Node guestRow = pairedRemotes.findById("remote_manage:rem_guest1");
        SettingsTree.Node expRow = pairedRemotes.findById("remote_manage:rem_exp1");
        assertTrue(adminRow != null && "Admin".equals(adminRow.value), "Admin remote displays 'Admin' status");
        assertTrue(houseRow != null && "Household".equals(houseRow.value), "Household remote displays 'Household' status");
        assertTrue(guestRow != null && guestRow.value.startsWith("Guest · ") && guestRow.value.endsWith("d left"),
                "Active Guest remote displays days remaining: " + (guestRow != null ? guestRow.value : "null"));
        assertTrue(expRow != null && "Expired".equals(expRow.value), "Expired Guest remote displays 'Expired' status");

        // Verify Cloud Sync v2, Automatic Encrypted Vault, Household TVs & Per-TV Override controls
        SettingsTree.Node setupKeys = root.findById("setup_keys");
        assertTrue(setupKeys != null, "SettingsTree contains 'setup_keys'");
        assertTrue(setupKeys.findById("setup_cloud_sync_now") != null, "setup_keys contains 'setup_cloud_sync_now'");
        assertTrue(setupKeys.findById("setup_cloud_tv_devices") != null, "setup_keys contains 'setup_cloud_tv_devices'");
        assertTrue(root.findById("cloud_sync_now") != null, "users contains 'cloud_sync_now'");
        assertTrue(root.findById("cloud_tv_devices") != null, "users contains 'cloud_tv_devices'");
        assertTrue(root.findById("stremio_use_household") != null, "users contains 'stremio_use_household'");
        assertTrue(root.findById("tv_override_shelf") != null, "home_interface contains 'tv_override_shelf'");
        assertTrue(root.findById("tv_override_hidden_apps") != null, "home_interface contains 'tv_override_hidden_apps'");
        assertTrue(root.findById("tv_override_screensaver") != null, "screensaver contains 'tv_override_screensaver'");

        // Universal audit: EVERY node in the entire SettingsTree must use a supported Symbols icon and contain ZERO emojis
        List<SettingsTree.Node> allNodes = new ArrayList<>();
        collectAllNodes(root, allNodes);
        assertTrue(allNodes.size() > 60, "SettingsTree has comprehensive node hierarchy");
        for (SettingsTree.Node n : allNodes) {
            if (!n.isSectionHeader()) {
                assertTrue(Symbols.isSupportedIcon(n.icon),
                        "Every SettingsTree.Node must use a valid Symbols icon (node=" + n.id + ", icon=" + n.icon + ")");
            }
            assertTrue(!containsEmoji(n.title) && !containsEmoji(n.subtitle) && !containsEmoji(n.value),
                    "Zero emojis permitted in SettingsTree.Node strings (node=" + n.id + ")");
        }
    }

    private static void testEndToEndSecretsToCloudVaultCryptoRoundTrip() throws Exception {
        File sourceDir = Files.createTempDirectory("soartv_stage2_vault_src_").toFile();
        File targetDir = Files.createTempDirectory("soartv_stage2_vault_dst_").toFile();
        char[] masterPassphrase = "Stage2-Zero-Knowledge-Vault-Passphrase!".toCharArray();
        try {
            Secrets.setJvmTestSecretsDir(sourceDir);
            Secrets.putSecret(null, Secrets.OMDB_KEY, "omdb_e2e_secret_111");
            Secrets.putSecret(null, Secrets.MDBLIST_KEY, "mdblist_e2e_secret_222");
            Secrets.putSecret(null, Secrets.GEMINI_API_KEY, "gemini_e2e_secret_333");
            Secrets.putSecret(null, Secrets.SENSIBO_API_KEY, "sensibo_e2e_secret_444");
            Secrets.putSecret(null, Secrets.ADB_KEY, "device_local_adb_private_key_excluded");

            Map<String, String> exported = Secrets.exportVaultSecrets(null);
            assertEquals(4, exported.size(), "exportVaultSecrets exports 4 user keys and excludes device-local adb_key");

            CloudVaultCrypto.VaultEnvelope envelope = CloudVaultCrypto.encryptMap(exported, masterPassphrase);
            String wireJson = envelope.toJsonString();
            assertTrue(!wireJson.contains("omdb_e2e_secret_111") && !wireJson.contains("omdb_key"),
                    "Wire JSON envelope leaks zero secret values or secret key names");

            CloudVaultCrypto.VaultEnvelope parsedEnvelope = CloudVaultCrypto.VaultEnvelope.fromJsonString(wireJson);
            Map<String, String> decrypted = CloudVaultCrypto.decryptMap(parsedEnvelope, masterPassphrase);

            // Import into a brand-new TV's Secrets directory and verify Prefs resolves them immediately
            Secrets.setJvmTestSecretsDir(targetDir);
            Secrets.importVaultSecrets(null, decrypted);

            assertEquals("omdb_e2e_secret_111", Prefs.omdbKey(), "Restored OMDb key immediately available via Prefs.omdbKey()");
            assertEquals("mdblist_e2e_secret_222", Prefs.mdblistKey(), "Restored MDBList key immediately available via Prefs.mdblistKey()");
            assertEquals("gemini_e2e_secret_333", Prefs.geminiKey(), "Restored Gemini key immediately available via Prefs.geminiKey()");
            assertEquals("sensibo_e2e_secret_444", Secrets.getSecret(null, Secrets.SENSIBO_API_KEY),
                    "Restored Sensibo key encrypted at rest in target Secrets directory");
        } finally {
            CloudVaultCrypto.wipePassphrase(masterPassphrase);
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(sourceDir);
            deleteRecursively(targetDir);
        }
    }

    private static void testSecretsMemoryCacheEliminatesPerFrameDecryptAndNegativeLookupOverhead() throws Exception {
        File tempDir = Files.createTempDirectory("soartv_secrets_memcache_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tempDir);
            Secrets.resetDecryptCountForTest();
            Secrets.putSecret(null, Secrets.OMDB_KEY, "omdb_fast_key_777");

            for (int i = 0; i < 60; i++) {
                assertEquals("omdb_fast_key_777", Secrets.getSecret(null, Secrets.OMDB_KEY),
                        "Memory cache returns present secret immediately");
                assertEquals("", Secrets.getSecret(null, Secrets.MDBLIST_KEY),
                        "Negative memory cache returns empty string immediately without hitting disk/keystore");
            }
            assertEquals(0, Secrets.getDecryptCountForTest(),
                    "putSecret warms RAM cache and missing secret uses negative cache (0 decrypt calls)");

            Secrets.clearMemoryCacheForTest();
            Secrets.resetDecryptCountForTest();
            for (int i = 0; i < 60; i++) {
                assertEquals("omdb_fast_key_777", Secrets.getSecret(null, Secrets.OMDB_KEY),
                        "Cold read decrypts once then serves from RAM");
            }
            assertEquals(1, Secrets.getDecryptCountForTest(),
                    "60 consecutive reads after cold cache clear perform exactly 1 AES-GCM decrypt");

            Secrets.deleteSecret(null, Secrets.OMDB_KEY);
            assertEquals("", Secrets.getSecret(null, Secrets.OMDB_KEY),
                    "deleteSecret immediately invalidates RAM cache to empty sentinel");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(tempDir);
        }
    }

    private static void testLightModeCrispWhiteBackgroundAndSoftenedDarkestBlack() {
        assertEquals(0xFFFFFFFF, HomeView.LIGHT_HERO_BG,
                "Light Mode hero base color is pure crisp white (#FFFFFF)");
        int upperLeftLight = HomeView.sampleUnifiedAuroraColor(0.18f, 0.20f, 0.0f, 0.0f);
        int ulR = (upperLeftLight >> 16) & 0xFF;
        int ulG = (upperLeftLight >> 8) & 0xFF;
        int ulB = upperLeftLight & 0xFF;
        assertTrue(ulR >= 248 && ulG >= 248 && ulB >= 248,
                "Light Mode unified background includes crisp pure-white sanctuary in reading zone: rgb=(" + ulR + "," + ulG + "," + ulB + ")");

        int inkR = (HomeView.L_INK >> 16) & 0xFF;
        int inkG = (HomeView.L_INK >> 8) & 0xFF;
        int inkB = HomeView.L_INK & 0xFF;
        assertTrue(inkR >= 48 && inkR <= 72 && inkG >= 48 && inkG <= 76 && inkB >= 52 && inkB <= 84,
                "Light Mode darkest black (L_INK) is softened to refined slate charcoal rather than harsh pitch black: 0x"
                        + Integer.toHexString(HomeView.L_INK));

        assertEquals("https://image.tmdb.org/t/p/original", Recs.backdropBaseUrlForWidth(1920),
                "Always uses TMDB 4K original backdrops for crisp hero quality");
        assertEquals("https://image.tmdb.org/t/p/original", Recs.backdropBaseUrlForWidth(3840),
                "Native 4K UI layer uses TMDB original backdrops");

        long nowMs = 1_700_000_000_000L;
        assertEquals(false, Recs.shouldSkipFailedPoster(null, nowMs),
                "Unseen poster keys are never skipped");
        assertEquals(true, Recs.shouldSkipFailedPoster(nowMs - 5_000L, nowMs),
                "Recently failed/404 poster keys enter cooldown instead of spinning an infinite retry loop");
        assertEquals(false, Recs.shouldSkipFailedPoster(nowMs - Recs.POSTER_RETRY_COOLDOWN_MS - 1L, nowMs),
                "Failed poster keys become eligible again after POSTER_RETRY_COOLDOWN_MS");

        assertEquals(true, Shadows.canUseSingleBitmapShadow(196, 294),
                "Portrait media cards render shadow in 1 cached ALPHA_8 drawBitmap call with 0 BlurMaskFilter FBOs");
        assertEquals(true, Shadows.canUseSingleBitmapShadow(340, 192),
                "Wide browser cards render shadow in 1 cached ALPHA_8 drawBitmap call");
        assertEquals(false, Shadows.canUseSingleBitmapShadow(920, 540),
                "Oversized dialogs fall back to bounded 9-slice cached ALPHA_8 shadow");

        assertEquals(true, HomeView.isRealSlowAnimationFrame(42_000_000L),
                "42ms active VSYNC frame is counted as a slow animation frame");
        assertEquals(false, HomeView.isRealSlowAnimationFrame(1_000_000_000L),
                "1000ms idle postFrameCallbackDelayed wakeup never false-trips shader fallback");
        assertEquals(false, HomeView.isRealSlowAnimationFrame(16_600_000L),
                "16.6ms 60fps VSYNC frame is healthy");
        assertEquals(1920, Scene.heroLayerWidth(1920),
                "Preserves full 1920px hero layer width without downsampling");
        assertEquals(1080, Scene.heroLayerHeight(1080),
                "Preserves full 1080px hero layer height without downsampling");
        assertEquals(3840, Scene.heroLayerWidth(3840),
                "Preserves full 3840px hero layer width");
    }

    private static void testContinueWatchingEpisodeTitleExtractionAndHighContrastScrim() {
        String cinemetaJson = "{\"meta\":{\"id\":\"tt11280740\",\"name\":\"Severance\",\"videos\":["
                + "{\"id\":\"tt11280740:1:9\",\"season\":1,\"episode\":9,\"name\":\"The We We Are\",\"overview\":\"Season finale.\"},"
                + "{\"id\":\"tt11280740:1:10\",\"season\":1,\"number\":10,\"title\":\"Cobel's Return\",\"description\":\"Bonus chapter.\"}"
                + "]}}";
        assertEquals("The We We Are", CinemetaClient.parseEpisodeTitleFromMetaJson(cinemetaJson, 1, 9),
                "Extracts Cinemeta episode 'name' for S1E9");
        assertEquals("Cobel's Return", CinemetaClient.parseEpisodeTitleFromMetaJson(cinemetaJson, 1, 10),
                "Extracts Cinemeta episode 'title' + 'number' fallback for S1E10");

        assertEquals("", WatchNext.sanitizeEpisodeTitle("Episode 10", "Severance", 1, 10),
                "Filters generic 'Episode 10' placeholder");
        assertEquals("", WatchNext.sanitizeEpisodeTitle("Severance", "Severance", 1, 10),
                "Filters duplicate series title used as episode title");
        assertEquals("The We We Are", WatchNext.sanitizeEpisodeTitle("S1E9 - The We We Are", "Severance", 1, 9),
                "Strips redundant S1E9 prefix from episode title");
        assertEquals("S1E9", WatchNext.formatEpisodeLine(9, "The We We Are"),
                "Formats season + episode as S1E9 on Continue Watching tile");
        assertEquals("S2E10", WatchNext.formatEpisodeLine(2, 10, "Cobel's Return"),
                "Formats season + episode as S2E10 on Continue Watching tile");

        assertTrue(HomeView.CONTINUE_SCRIM_HEIGHT_DP >= 132f,
                "Continue Watching bottom dark gradient is tall enough (>= 132dp) to cover the E10: name row");
        assertTrue(HomeView.continueScrimAlphaAtFraction(0.65f) >= 0.72f,
                "Continue Watching bottom dark gradient provides strong dark contrast (>= 72%) behind E10: name text");
    }

    private static void testControllerPinkVsBlueTagIsolation() {
        Map<String, Object> prefsWithBlueTagged = new java.util.HashMap<>();
        prefsWithBlueTagged.put("ctrl_tagged_usbport:13623:4160:xhci-2", Boolean.TRUE);
        prefsWithBlueTagged.put("ctrl_name_usbport:13623:4160:xhci-2", "Blue Controller");
        prefsWithBlueTagged.put("ctrl_color_usbport:13623:4160:xhci-2", 0xFF3B82F6);
        prefsWithBlueTagged.put("ctrl_tagged_a15f809e77c976b79bad43b497eb9b7d72f5f59d", Boolean.TRUE);

        assertTrue(!ControllerManager.canMigrateLegacyControllerTag(
                        prefsWithBlueTagged,
                        "usbport:13623:4160:xhci-1",
                        "a15f809e77c976b79bad43b497eb9b7d72f5f59d",
                        13623,
                        4160),
                "Pink USB controller on xhci-1 NEVER inherits Blue controller's legacy EventHub hash tag");

        assertTrue(!ControllerManager.canMigrateLegacyControllerTag(
                        prefsWithBlueTagged,
                        "bt:aa:bb:cc:11:22:33",
                        "a15f809e77c976b79bad43b497eb9b7d72f5f59d",
                        1356,
                        2508),
                "Pink Bluetooth controller NEVER inherits Blue controller's legacy EventHub hash tag");

        Map<String, Object> freshSingleLegacyOnly = new java.util.HashMap<>();
        freshSingleLegacyOnly.put("ctrl_tagged_legacy_raw_hash", Boolean.TRUE);
        assertTrue(ControllerManager.canMigrateLegacyControllerTag(
                        freshSingleLegacyOnly,
                        "bt:aa:bb:cc:11:22:33",
                        "legacy_raw_hash",
                        1356,
                        2508),
                "Single legacy controller with zero existing canonical bt:/usbport: tags can still migrate once");
    }

    private static void testBluetoothPairingAssistZeroIpcKeyFastPathAndBleSafeDiscoveryCap() {
        BluetoothPairingAssist assist = new BluetoothPairingAssist();
        assertTrue(!assist.isPendingUnfocusedPairAction(),
                "BluetoothPairingAssist starts with pendingUnfocusedPairAction=false so onKeyEvent executes 0 Binder calls");
        assertEquals(2, BluetoothControllerPairer.MAX_AUTO_DISCOVERY_CYCLES,
                "BluetoothControllerPairer caps automatic BR/EDR discovery cycles to 2 to prevent BLE remote starvation");
        assertTrue(BluetoothControllerPairer.DISCOVERY_BLE_BREATHING_DELAY_MS >= 2000L,
                "BluetoothControllerPairer leaves >= 2000ms BLE breathing window between scan cycles");
    }

    private static void testBackgroundPauseDebouncedSaveAndStreamingMemoryTrim() {
        assertTrue(!Recs.isBackgroundActivityPaused(),
                "Recs starts with background activity unpaused");
        Recs.setBackgroundActivityPaused(true);
        assertTrue(Recs.isBackgroundActivityPaused(),
                "Recs marks background activity paused when HomeView pauses for streaming/playback");
        assertTrue(Recs.shouldSkipBackgroundWork(true, false),
                "Background-only metadata/poster work yields immediately while launcher is in background");
        assertTrue(!Recs.shouldSkipBackgroundWork(true, true),
                "Explicit user-focused callback still executes if requested");
        Recs.setBackgroundActivityPaused(false);
        assertTrue(!Recs.isBackgroundActivityPaused(),
                "Recs resumes background activity when HomeView returns to foreground");
        assertTrue(Recs.METADATA_SAVE_DEBOUNCE_MS >= 10_000L,
                "Recs coalesces incremental metadata disk saves to >= 10s instead of serializing 1200 picks per item");

        GamingZone.trimMemoryForStreaming();
        assertEquals(0, GamingZone.getCachedHeroBackdropCount(),
                "GamingZone.trimMemoryForStreaming clears hero backdrop RAM cache before Moonlight MediaCodec starts");
    }

    private static void testTvInputFieldSyncSnapshotPersistenceSecurityAndPrecedence() throws Exception {
        // 1. Safe text extraction: normal typo, hint suppression, and strict password redaction
        assertEquals("mean gilrs",
                TvInputFieldSync.extractSafeText("mean gilrs", "Search", false, false),
                "extractSafeText preserves exact typed TV input text");
        assertEquals("",
                TvInputFieldSync.extractSafeText("Search movies & shows", "Search movies & shows", true, false),
                "extractSafeText suppresses placeholder hint text when showingHint=true");
        assertEquals("",
                TvInputFieldSync.extractSafeText("SuperSecret99!", "Password", false, true),
                "extractSafeText NEVER leaks password/PIN field characters");

        // 2. Friendly app label resolution for popular Android TV streaming apps
        assertEquals("Netflix", TvInputFieldSync.friendlyAppName("com.netflix.ninja", ""),
                "Maps com.netflix.ninja to Netflix");
        assertEquals("YouTube", TvInputFieldSync.friendlyAppName("com.google.android.youtube.tv", ""),
                "Maps YouTube TV package to YouTube");
        assertEquals("Stremio", TvInputFieldSync.friendlyAppName("com.stremio.one", ""),
                "Maps Stremio package to Stremio");
        assertEquals("Prime Video", TvInputFieldSync.friendlyAppName("com.amazon.amazonvideo.livingroom", ""),
                "Maps Prime Video package cleanly");
        assertEquals("Custom Media", TvInputFieldSync.friendlyAppName("com.example.custom", "Custom Media"),
                "Prefers PackageManager label when non-empty");

        // 3. JSON & atomic disk round-trip (including quotes/escaped chars)
        long now = 1_760_000_000_000L;
        TvInputFieldSync.InputSnapshot netflixSnap = new TvInputFieldSync.InputSnapshot(
                true, "mean gilrs \"2004\"", "Search Netflix", "com.netflix.ninja", "Netflix", false, 7L, now);
        File tmpDir = Files.createTempDirectory("soartv-input-sync-test").toFile();
        try {
            TvInputFieldSync.writeSnapshot(tmpDir, netflixSnap);
            TvInputFieldSync.InputSnapshot loaded = TvInputFieldSync.readSnapshot(tmpDir);
            assertTrue(loaded.active, "Persisted Netflix snapshot loads as active");
            assertEquals("mean gilrs \"2004\"", loaded.text, "Round-trips quotes and spaces accurately");
            assertEquals("Search Netflix", loaded.hint, "Round-trips hint accurately");
            assertEquals("com.netflix.ninja", loaded.packageName, "Round-trips packageName");
            assertEquals("Netflix", loaded.appLabel, "Round-trips appLabel");
            assertEquals(7L, loaded.revision, "Round-trips monotonic revision");
            assertTrue(!loaded.isPassword, "Round-trips isPassword=false");

            // Password redaction defense-in-depth during deserialization
            String forgedPasswordJson = "{\"active\":true,\"text\":\"1234\",\"hint\":\"PIN\","
                    + "\"packageName\":\"com.netflix.ninja\",\"appLabel\":\"Netflix\","
                    + "\"isPassword\":true,\"revision\":8,\"updatedAtMs\":" + now + "}";
            TvInputFieldSync.InputSnapshot redacted = TvInputFieldSync.InputSnapshot.fromJsonString(forgedPasswordJson);
            assertTrue(redacted.active && redacted.isPassword, "Password snapshot stays active+isPassword");
            assertEquals("", redacted.text, "fromJsonString redacts text whenever isPassword=true");
        } finally {
            deleteRecursively(tmpDir);
        }

        // 4. Resolution precedence across Netflix (background host) vs SoarTV foreground vs AlertDialog vs TTL expiry
        TvInputFieldSync.InputSnapshot hostModal = new TvInputFieldSync.InputSnapshot(
                true, "severance", "Search SoarTV", "io.soartv", "SoarTV", false, 3L, now);
        TvInputFieldSync.InputSnapshot soartvDialog = new TvInputFieldSync.InputSnapshot(
                true, "dune", "Search", "io.soartv", "SoarTV", false, 4L, now);

        TvInputFieldSync.InputSnapshot extWhileNetflixFg = TvInputFieldSync.resolveActiveSnapshot(
                false, null, netflixSnap, "io.soartv", now + 2_000L);
        assertTrue(extWhileNetflixFg.active && "Netflix".equals(extWhileNetflixFg.appLabel),
                "Exposes Netflix focused input when SoarTV host is backgrounded");

        TvInputFieldSync.InputSnapshot staleNetflixOnHome = TvInputFieldSync.resolveActiveSnapshot(
                true, null, netflixSnap, "io.soartv", now + 2_000L);
        assertTrue(!staleNetflixOnHome.active,
                "Hides stale third-party Netflix snapshot once SoarTV Home is back in foreground");

        TvInputFieldSync.InputSnapshot soartvAlertDialog = TvInputFieldSync.resolveActiveSnapshot(
                true, null, soartvDialog, "io.soartv", now + 2_000L);
        assertTrue(soartvAlertDialog.active && "dune".equals(soartvAlertDialog.text),
                "Exposes SoarTV AlertDialog EditText when packageName matches selfPackage");

        TvInputFieldSync.InputSnapshot hostPreferred = TvInputFieldSync.resolveActiveSnapshot(
                true, hostModal, netflixSnap, "io.soartv", now + 2_000L);
        assertTrue(hostPreferred.active && "severance".equals(hostPreferred.text),
                "Prefers live SoarTV Host Liquid Glass input state when attached and active");

        TvInputFieldSync.InputSnapshot expired = TvInputFieldSync.resolveActiveSnapshot(
                false, null, netflixSnap, "io.soartv", now + TvInputFieldSync.SNAPSHOT_TTL_MS + 10_000L);
        assertTrue(!expired.active, "Expires stale snapshots beyond SNAPSHOT_TTL_MS");

        // 5. Submit action button detection
        assertTrue(TvInputFieldSync.isSubmitButtonLabel("Search"), "Recognizes Search submit label");
        assertTrue(TvInputFieldSync.isSubmitButtonLabel("Go"), "Recognizes Go submit label");
        assertTrue(TvInputFieldSync.isSubmitButtonLabel("חיפוש"), "Recognizes Hebrew search submit label");
        assertTrue(!TvInputFieldSync.isSubmitButtonLabel("Cancel"), "Rejects Cancel as submit label");

        // 6. Companion Remote AndroidManifest LAUNCHER category regression guard (ensures phone app-drawer visibility)
        File remoteManifest = new File("../SoarTV-remote/android/AndroidManifest.xml");
        if (remoteManifest.isFile()) {
            String xml = new String(Files.readAllBytes(remoteManifest.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("android.intent.action.MAIN"),
                    "SoarTV-Remote manifest declares android.intent.action.MAIN");
            assertTrue(xml.contains("android.intent.category.LAUNCHER"),
                    "SoarTV-Remote manifest declares android.intent.category.LAUNCHER so phone launchers display the app");
            assertTrue(!xml.contains("android.intent.action.LAUNCHER"),
                    "SoarTV-Remote manifest must never contain invalid android.intent.action.LAUNCHER typo");
            assertTrue(xml.contains("android.permission.CAMERA"),
                    "SoarTV-Remote manifest declares CAMERA permission for native Camera2 QR scanner");
            assertTrue(xml.contains("android.permission.VIBRATE"),
                    "SoarTV-Remote manifest declares VIBRATE permission for gentle QR scan feedback");
            assertTrue(xml.contains("android:windowSoftInputMode=\"adjustResize\""),
                    "SoarTV-Remote manifest uses adjustResize so phone keyboard lifts modals cleanly");
        }
        File remoteActivity = new File("../SoarTV-remote/android/src/io/soartv/remote/RemoteAppActivity.java");
        if (remoteActivity.isFile()) {
            String javaSrc = new String(Files.readAllBytes(remoteActivity.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(javaSrc.contains("public void openNativeQrScanner()"),
                    "RemoteAppActivity exposes openNativeQrScanner() native Camera2 bridge");
            assertTrue(javaSrc.contains("pickCameraAnalysisSize("),
                    "RemoteAppActivity selects hardware-supported YUV_420_888 analysis size");
            assertTrue(javaSrc.contains("installImeInsetsResizeListener()"),
                    "RemoteAppActivity installs IME WindowInsets overlap listener for Android 11-16 keyboard lift");
            int setContentIdx = javaSrc.indexOf("setContentView(rootContainer);");
            int applyBarIdx = javaSrc.indexOf("applySystemBarAppearance(true);");
            assertTrue(setContentIdx > 0 && applyBarIdx > setContentIdx,
                    "RemoteAppActivity calls setContentView before applySystemBarAppearance to prevent Android 11-16 NPE");
        }
        File remoteHtml = new File("../SoarTV-remote/public/index.html");
        if (remoteHtml.isFile()) {
            String html = new String(Files.readAllBytes(remoteHtml.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(html.contains("id=\"pairingOverlay\" class=\"sheet-overlay\""),
                    "Pairing overlay matches Liquid Glass slide-up sheet styling");
            assertTrue(html.contains("id=\"settingsUpgradeRoleBtn\"") && html.contains("rePairAsAdmin()"),
                    "Settings TV Connection includes Upgrade Admin action for accidental Guest pairing recovery");
        }
        // Companion Remote touchpad swipe -> D-Pad routing (allows reaching Top-Bar Z_TOP and Settings/Modals)
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(0, -1) == HomeInputRouter.KEYCODE_DPAD_UP,
                "Touchpad swipe up maps to KEYCODE_DPAD_UP so user can reach Top-Bar tabs row");
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(1, -2) == HomeInputRouter.KEYCODE_DPAD_UP,
                "Diagonal upward swipe prioritizes KEYCODE_DPAD_UP");
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(0, 1) == HomeInputRouter.KEYCODE_DPAD_DOWN,
                "Touchpad swipe down maps to KEYCODE_DPAD_DOWN");
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(-1, 0) == HomeInputRouter.KEYCODE_DPAD_LEFT,
                "Touchpad swipe left maps to KEYCODE_DPAD_LEFT");
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(2, 1) == HomeInputRouter.KEYCODE_DPAD_RIGHT,
                "Horizontal dominant swipe right maps to KEYCODE_DPAD_RIGHT");
        assertTrue(HomeInputRouter.resolveRemoteScrollKeyCode(0, 0) == 0,
                "Zero scroll delta maps to no-op");
        File homeViewFile = new File("app/src/io/soartv/HomeView.java");
        if (homeViewFile.isFile()) {
            String hvSrc = new String(Files.readAllBytes(homeViewFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            int scrollIdx = hvSrc.indexOf("public void onRemoteScroll(int dx, int dy)");
            assertTrue(scrollIdx > 0, "HomeView defines onRemoteScroll");
            String scrollBody = hvSrc.substring(scrollIdx, Math.min(hvSrc.length(), scrollIdx + 300));
            assertTrue(scrollBody.contains("HomeInputRouter.resolveRemoteScrollKeyCode(dx, dy)")
                    && scrollBody.contains("onRemoteKey(keyCode)")
                    && !scrollBody.contains("wheel("),
                    "HomeView.onRemoteScroll must route through onRemoteKey (never clamping wheel()) so touchpad swipe UP reaches Top-Bar tabs");
        }
        // Stremio official TV QR Link API parsers
        StremioAccountClient.LinkCodeResult linkRes = StremioAccountClient.parseLinkCodeResponse(
                "{\"result\":{\"success\":true,\"code\":\"FWG4\",\"link\":\"https://link.stremio.com/FWG4\"}}");
        assertTrue(linkRes != null && linkRes.isValid()
                        && "FWG4".equals(linkRes.code)
                        && "https://link.stremio.com/FWG4".equals(linkRes.link),
                "StremioAccountClient.parseLinkCodeResponse parses official link.stremio.com QR code response");
        assertTrue(StremioAccountClient.parseLinkCodeResponse("{\"error\":{\"code\":500}}") == null,
                "StremioAccountClient.parseLinkCodeResponse returns null on error payload");
        assertTrue("".equals(StremioAccountClient.parseLinkReadAuthKey(
                "{\"error\":{\"code\":101,\"message\":\"Invalid or expired token\"}}")),
                "StremioAccountClient.parseLinkReadAuthKey treats code 101 as pending");
        assertTrue("strem_auth_xyz123".equals(StremioAccountClient.parseLinkReadAuthKey(
                "{\"result\":{\"authKey\":\"strem_auth_xyz123\"}}")),
                "StremioAccountClient.parseLinkReadAuthKey extracts approved authKey");

        // Mirrored TV modals + TV Wake-Up bridge security & cooldown invariants
        assertTrue(TvRemoteBridge.isSafeExternalModalUrl("https://trakt.tv/activate"),
                "HTTPS Trakt URL allowed in mirrored QR modal");
        assertTrue(TvRemoteBridge.isSafeExternalModalUrl("https://link.stremio.com/FWG4"),
                "HTTPS Stremio URL allowed in mirrored QR modal");
        assertTrue(!TvRemoteBridge.isSafeExternalModalUrl("javascript:alert(1)"),
                "javascript: scheme rejected in mirrored modal URL");
        assertTrue(!TvRemoteBridge.isSafeExternalModalUrl("http://insecure.example/x"),
                "Non-HTTPS scheme rejected in mirrored modal URL");

        assertTrue(TvRemoteBridge.shouldDispatchAdbWake(false, false, false, 0L, 10_000L),
                "Non-interactive TV triggers loopback ADB KEYCODE_WAKEUP");
        assertTrue(TvRemoteBridge.shouldDispatchAdbWake(true, true, false, 0L, 10_000L),
                "Active screensaver triggers loopback ADB KEYCODE_WAKEUP");
        assertTrue(!TvRemoteBridge.shouldDispatchAdbWake(true, false, true, 0L, 10_000L),
                "Already-awake non-dreaming TV skips redundant ADB wakeup");
        assertTrue(!TvRemoteBridge.shouldDispatchAdbWake(false, false, true, 9_500L, 10_000L),
                "ADB wakeup respects 2-second cooldown");

        // RBAC enforcement on /api/wake and /api/modal
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/wake", "POST", "", false),
                "GUEST may wake TV via /api/wake");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/modal", "POST", "dismiss", false),
                "GUEST may dismiss standard TV modal");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/modal", "POST", "pair_admin", false),
                "GUEST must NEVER approve pairing prompts via /api/modal");
        assertTrue(!RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/modal", "POST", "pair_guest", false),
                "HOUSEHOLD must NEVER approve pairing prompts via /api/modal");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.ADMIN, "/api/modal", "POST", "pair_admin", false),
                "ADMIN may approve pairing prompts via /api/modal");

        // v2.8: Instant /api/live polling, Trakt URL/pending helpers, first-time Admin pairing UX, renameDevice, and Initial Setup pairing
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.GUEST, "/api/live", "GET", "", false),
                "GUEST may poll lightweight /api/live for instant mirrored modals");
        assertTrue(RemoteAuthManager.isAuthorized(RemoteAuthManager.Role.HOUSEHOLD, "/api/live", "GET", "", false),
                "HOUSEHOLD may poll lightweight /api/live");

        assertEquals("https://auth.trakt.tv/activate", Trakt.normalizeActivationUrl("auth.trakt.tv/activate"),
                "Trakt.normalizeActivationUrl prepends https:// to scheme-less host URLs");
        assertEquals("https://trakt.tv/activate", Trakt.normalizeActivationUrl(""),
                "Trakt.normalizeActivationUrl falls back to https://trakt.tv/activate");
        assertEquals("https://auth.trakt.tv/activate", Trakt.normalizeActivationUrl("https://auth.trakt.tv/activate"),
                "Trakt.normalizeActivationUrl preserves full https:// URLs");
        assertTrue(Trakt.isPendingTokenPoll(400, ""),
                "Trakt HTTP 400 with empty body is pending activation");
        assertTrue(!Trakt.isPendingTokenPoll(400, "{\"error\":\"invalid_grant\"}"),
                "Trakt HTTP 400 with explicit invalid_grant error is NOT pending");
        assertTrue(!Trakt.isPendingTokenPoll(404, ""),
                "Trakt HTTP 404 is NOT pending");

        assertEquals(2, HomeInputRouter.initialPairingModalFocus(null, true),
                "First-time Owner setup focuses Make Admin (slot 2) by default");
        assertEquals(0, HomeInputRouter.initialPairingModalFocus(null, false),
                "Subsequent generic remote request focuses Allow Guest (slot 0) by default");
        assertEquals(1, HomeInputRouter.initialPairingModalFocus(RemoteAuthManager.Role.HOUSEHOLD, false),
                "Household invite focuses Allow Household (slot 1)");
        assertEquals(2, HomeInputRouter.initialPairingModalFocus(RemoteAuthManager.Role.ADMIN, false),
                "Admin invite focuses Make Admin (slot 2)");

        // Test RemoteAuthManager.renameDevice + bootstrapAdmin flag on PendingPairRequest
        File tmpDir2 = Files.createTempDirectory("soartv_v28_auth_test").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tmpDir2);
            AtomicLong clk2 = new AtomicLong(1_770_000_000_000L);
            RemoteAuthManager auth2 = new RemoteAuthManager(null, clk2::get);
            auth2.createPairRequest("Alex's S25 Ultra", "c_owner", "192.168.1.11", "http://192.168.1.50:8484");
            RemoteAuthManager.PendingPairRequest firstReq = auth2.getActivePendingRequest();
            assertTrue(firstReq != null && firstReq.bootstrapAdmin,
                    "First pairing request when no Admin exists marks bootstrapAdmin=true");
            RemoteAuthManager.PairedDevice owner = auth2.approvePendingOnTv(RemoteAuthManager.Role.ADMIN);
            assertTrue(owner != null && "Alex's S25 Ultra".equals(owner.deviceName),
                    "Owner paired with Bluetooth/phone nickname preserved");

            assertTrue(auth2.renameDevice(owner.deviceId, "Living Room Phone"),
                    "renameDevice updates paired phone nickname");
            assertTrue(!auth2.renameDevice(owner.deviceId, "   "),
                    "renameDevice rejects blank nickname");
            RemoteAuthManager.PairedDevice renamed = auth2.listDevices().get(0);
            assertEquals("Living Room Phone", renamed.deviceName, "Renamed nickname persisted");
            assertEquals(RemoteAuthManager.Role.ADMIN, renamed.role, "Rename preserves ADMIN role");

            auth2.createPairRequest("Guest Pixel", "c_guest", "192.168.1.12", "http://192.168.1.50:8484");
            RemoteAuthManager.PendingPairRequest secondReq = auth2.getActivePendingRequest();
            assertTrue(secondReq != null && !secondReq.bootstrapAdmin,
                    "Subsequent pairing request after Admin exists marks bootstrapAdmin=false");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(tmpDir2);
        }

        // Verify Phone Remote Pairing is Step 1 inside Initial Setup & Cloud
        SettingsTree.StateSnapshot freshInit = new SettingsTree.StateSnapshot();
        freshInit.initialSetupDone = false;
        freshInit.pairedRemotes = Collections.emptyList();
        SettingsTree.Node freshRoot = SettingsTree.buildRoot(freshInit);
        SettingsTree.Node initNode = freshRoot.findById("initial_setup");
        SettingsTree.Node pairStep = initNode != null ? initNode.findById("setup_pair_remote") : null;
        assertTrue(pairStep != null, "Initial Setup includes 'setup_pair_remote' step");
        assertEquals(SettingsTree.StatusTone.WARNING, pairStep.statusTone,
                "Unpaired Initial Setup remote step uses WARNING status tone");

        freshInit.pairedRemotes = Collections.singletonList(
                new RemoteAuthManager.PairedDevice("rem_1", "Alex's S25", "a".repeat(64),
                        RemoteAuthManager.Role.ADMIN, "", 1000L, 0L, 1000L));
        SettingsTree.Node pairedInitNode = SettingsTree.buildRoot(freshInit).findById("initial_setup");
        SettingsTree.Node pairedStep = pairedInitNode.findById("setup_pair_remote");
        assertTrue(pairedStep != null && pairedStep.checked && pairedStep.value.contains("Alex's S25"),
                "Paired Initial Setup remote step shows phone nickname & POSITIVE status");

        // v2.9: Quick Invite Admin shortcuts, ISO-18004 quiet zone >= 4, vector SVG QR, IPv4-mapped IPv6 normalization, and iOS/Android same-IP handoff
        assertEquals("192.168.1.50", RemoteControlServer.normalizeSocketIp("::ffff:192.168.1.50"),
                "normalizeSocketIp strips IPv4-mapped IPv6 prefix");
        assertEquals("192.168.1.50", RemoteControlServer.normalizeSocketIp("192.168.1.50%wlan0"),
                "normalizeSocketIp strips interface scope suffix");
        assertEquals("http://192.168.1.50:8484",
                RemoteControlServer.validateCorsOrigin("http://192.168.1.50:8484", "", "::ffff:192.168.1.50", 8484),
                "validateCorsOrigin accepts LAN origin even when dual-stack socket reports ::ffff: IP");

        assertTrue(RemoteAuthManager.PAIRING_QR_QUIET_ZONE >= 4,
                "Pairing QR uses ISO-18004 >= 4 module quiet zone for reliable iPhone camera scanning");
        String qrSvg = RemoteAuthManager.encodePairingQrSvg("http://192.168.1.50:8484/?pair=QrSecret128BitTestValue");
        assertTrue(qrSvg != null && qrSvg.startsWith("<svg ") && qrSvg.contains("<path ") && qrSvg.endsWith("</svg>"),
                "encodePairingQrSvg emits crisp self-contained inline SVG QR code");

        File tmpDir3 = Files.createTempDirectory("soartv_v29_invite_test").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tmpDir3);
            AtomicLong clk3 = new AtomicLong(1_780_000_000_000L);
            RemoteAuthManager auth3 = new RemoteAuthManager(null, clk3::get);
            RemoteAuthManager.PendingPairRequest inv = auth3.createInvite(
                    RemoteAuthManager.Role.ADMIN, "http://192.168.1.50:8484");
            assertTrue(inv != null && inv.presetRole == RemoteAuthManager.Role.ADMIN,
                    "Admin invite carries ADMIN presetRole");
            assertTrue(inv.pairUrl.startsWith("http://192.168.1.50:8484/?pair="),
                    "Admin invite URL uses LAN base with /?pair= query");

            RemoteAuthManager.IssueResult verified = auth3.verifyPairing(
                    "", inv.qrSecret, "", "", false, "iPhone Remote", "192.168.1.77");
            assertEquals(200, verified.httpStatus, "Partner verifying Admin QR invite succeeds");
            assertTrue(verified.device != null && verified.device.role == RemoteAuthManager.Role.ADMIN,
                    "Partner receives ADMIN role from Admin invite QR");

            // Simulates iPhone Add-to-Home-Screen standalone PWA (or Android newly-installed APK) opening with empty localStorage on same IP
            String handoffCfg = auth3.buildAuthConfigJson("", "SmartTV-4K", "192.168.1.77");
            assertTrue(handoffCfg.contains("\"handoffToken\"") && handoffCfg.contains("\"handoffRole\":\"ADMIN\""),
                    "Same-IP handoff automatically transfers ADMIN token to newly installed iOS PWA / Android APK");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRecursively(tmpDir3);
        }

        // Verify quick Invite Admin shortcut is directly reachable in Remotes & Accessories AND first inside Companion Phone Remotes
        SettingsTree.Node remotesCat = SettingsTree.buildRoot(freshInit).findById("remotes_accessories");
        assertTrue(remotesCat != null && remotesCat.findById("remote_invite:ADMIN") != null,
                "Remotes & Accessories exposes quick Invite Admin Remote action");
        SettingsTree.Node pairedGroup = remotesCat.findById("paired_remotes");
        assertTrue(pairedGroup != null && !pairedGroup.children.isEmpty()
                        && "remote_invite:ADMIN".equals(pairedGroup.children.get(0).id),
                "Invite Admin Remote is placed first inside Companion Phone Remotes");
    }

    private static void collectAllNodes(SettingsTree.Node node, List<SettingsTree.Node> out) {
        if (node == null) return;
        out.add(node);
        for (SettingsTree.Node child : node.children) {
            collectAllNodes(child, out);
        }
    }

    private static boolean containsEmoji(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0xFE00 && cp <= 0xFE0F)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
