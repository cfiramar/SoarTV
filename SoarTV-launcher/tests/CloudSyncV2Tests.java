package io.soartv;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comprehensive headless JVM test suite for SoarTV v2 Cloud Settings Sync, Cross-Brand Portability,
 * Automatic Household-Key Multi-Slot E2EE Vault, and IP-Change Resilience.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link CanonicalAppCatalog}: cross-brand slug resolution, Open/Closed regional registration,
 *       and non-destructive merge across heterogeneous TVs.</li>
 *   <li>{@link TvDeviceIdentity}: deterministic UUID derivation, mDNS hostname generation, and LAN UDP discovery.</li>
 *   <li>{@link CloudVaultCrypto}: automatic Household-Key derived passphrase and multi-slot vault round-trip.</li>
 *   <li>{@link CloudSyncManager}: field-level LWW merge conflict resolution, secret merging, portable TV settings
 *       filtering (Replace Existing TV), and Mid-Way Sync 15-minute active-only scheduling rules.</li>
 *   <li>{@link SmartHomeManager#parseSwitcherBroadcastPacket}: IP-resilient Switcher passive UDP discovery.</li>
 *   <li>{@link RemoteAuthManager}: security boundary ensuring ADMIN/HOUSEHOLD receive household directory creds
 *       while GUEST and unauthenticated callers NEVER receive {@code householdKey}.</li>
 *   <li>{@link SupabaseClient}: v2 explicit column projections (zero {@code select=*}) and DTO parsing.</li>
 * </ul>
 */
public final class CloudSyncV2Tests {

    private static int passed = 0;
    private static int failed = 0;

    private static void assertTrue(boolean cond, String message) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        if (eq) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        testCanonicalAppCatalogCrossBrandAndNonDestructive();
        testTvDeviceIdentityAndUdpDiscovery();
        testAutomaticHouseholdKeyMultiSlotVault();
        testFieldLevelLwwMergeAndSecretMerge();
        testPortableTvSettingsExtractionAndMidWayCadence();
        testSwitcherUdpBroadcastParser();
        testRemoteAuthHouseholdDirectorySecurityBoundary();
        testSupabaseV2QueryPathsAndParsers();

        System.out.println("CloudSyncV2Tests: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            throw new AssertionError("CloudSyncV2Tests had " + failed + " failure(s)");
        }
    }

    private static void testCanonicalAppCatalogCrossBrandAndNonDestructive() {
        // Built-in cross-brand resolution (generic Android TV vs Sony BRAVIA vs Fire TV)
        assertEquals("appletv", CanonicalAppCatalog.toCanonical("com.apple.atve.androidtv.appletv"),
                "Generic Apple TV resolves to canonical slug");
        assertEquals("appletv", CanonicalAppCatalog.toCanonical("com.apple.atve.sony.appletv"),
                "Sony BRAVIA Apple TV resolves to same canonical slug");
        assertEquals("youtube", CanonicalAppCatalog.toCanonical("com.google.android.youtube.tv"),
                "Android TV YouTube resolves to youtube");
        assertEquals("youtube", CanonicalAppCatalog.toCanonical("org.smarttube.stable"),
                "SmartTube resolves to youtube");
        assertEquals("pkg:android:com.vendor.customtv",
                CanonicalAppCatalog.toCanonical("com.vendor.customtv"),
                "Unknown Android package is namespaced cleanly");
        assertEquals("pkg:webos:com.lg.gallery",
                CanonicalAppCatalog.toCanonical("pkg:webos:com.lg.gallery"),
                "Existing webOS namespaced entry is preserved");

        // Resolve canonical cloud list onto a Sony TV that has Sony Apple TV + Netflix
        Set<String> sonyInstalled = new HashSet<>(Arrays.asList(
                "com.netflix.ninja",
                "com.apple.atve.sony.appletv",
                "com.google.android.youtube.tv"));
        List<String> cloudShelf = Arrays.asList("appletv", "netflix", "pkg:webos:com.lg.only", "disney");
        List<String> resolvedOnSony = CanonicalAppCatalog.resolveCanonicalListToLocal(cloudShelf, sonyInstalled);
        assertEquals(Arrays.asList("com.apple.atve.sony.appletv", "com.netflix.ninja"), resolvedOnSony,
                "Canonical shelf maps seamlessly to Sony BRAVIA installed packages and ignores webOS/uninstalled apps");

        // Non-destructive merge: Sony TV reorders Netflix ahead of Apple TV; webOS & uninstalled Disney MUST survive
        List<String> sonyReordered = Arrays.asList("com.netflix.ninja", "com.apple.atve.sony.appletv");
        List<String> mergedCloud = CanonicalAppCatalog.mergeCanonicalListNonDestructive(
                cloudShelf, sonyReordered, sonyInstalled);
        assertEquals(Arrays.asList("netflix", "appletv", "pkg:webos:com.lg.only", "disney"), mergedCloud,
                "Non-destructive merge preserves other TVs' apps while applying local reorder");

        // Open/Closed extension registration
        CanonicalAppCatalog.registerAliases("test_regional_tv", Arrays.asList("com.example.regional.tv"));
        assertEquals("test_regional_tv", CanonicalAppCatalog.toCanonical("com.example.regional.tv"),
                "OCP registerAliases maps regional package to canonical slug");
    }

    private static void testTvDeviceIdentityAndUdpDiscovery() {
        String id1 = TvDeviceIdentity.deriveStableDeviceId("a1b2c3d4e5f60708");
        String id2 = TvDeviceIdentity.deriveStableDeviceId("A1B2C3D4E5F60708");
        String idOther = TvDeviceIdentity.deriveStableDeviceId("1111222233334444");
        assertEquals(id1, id2, "deriveStableDeviceId is deterministic and case-insensitive");
        assertTrue(!id1.equals(idOther), "Different hardware ANDROID_IDs produce distinct permanent UUIDs");
        assertTrue(id1.length() == 36 && id1.charAt(8) == '-', "Derived device ID is a valid UUID");

        assertEquals("soartv-living-room-tv.local",
                TvDeviceIdentity.buildMdnsHostname("Living Room TV!", id1),
                "mDNS hostname sanitizes friendly name cleanly");
        assertEquals("soartv-bedroom.local",
                TvDeviceIdentity.buildMdnsHostname("soartv-Bedroom", id1),
                "mDNS hostname does not double-prefix soartv-");

        String validProbe = "{\"op\":\"SOARTV_TV_DISCOVER_V1\"}";
        String reply = TvDeviceIdentity.handleDiscoveryProbeText(
                validProbe, id1, "Living Room TV", "Living Room", "SONY", "XR-65A80L",
                "192.168.50.42", 8484, "soartv-living-room-tv.local");
        assertTrue(reply != null && reply.contains("\"op\":\"SOARTV_TV_HERE_V1\""),
                "Valid UDP discovery probe returns SOARTV_TV_HERE_V1 reply");
        assertTrue(reply != null && reply.contains("\"lanIp\":\"192.168.50.42\"") && reply.contains(id1),
                "UDP discovery reply includes live LAN IP and permanent TV UUID");
        assertEquals(null, TvDeviceIdentity.handleDiscoveryProbeText(
                "{\"op\":\"OTHER_PACKET\"}", id1, "TV", "Room", "GENERIC", "M", "192.168.1.2", 8484, "soartv-tv.local"),
                "Unrelated UDP packets are ignored");
    }

    private static void testAutomaticHouseholdKeyMultiSlotVault() throws Exception {
        String hk = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";
        char[] pass1 = CloudVaultCrypto.deriveHouseholdVaultPassphrase(hk);
        char[] pass2 = CloudVaultCrypto.deriveHouseholdVaultPassphrase("  " + hk + "  ");
        assertEquals(new String(pass1), new String(pass2),
                "Household vault passphrase derivation is deterministic and trims whitespace");
        assertTrue(pass1.length == 64 && !new String(pass1).equals(hk),
                "Derived vault passphrase is domain-separated 64-char SHA-256 hex, distinct from raw household key");

        // Multi-slot household + profile vault round-trip
        Map<String, String> hhSecrets = new LinkedHashMap<>();
        hhSecrets.put(Secrets.TMDB_KEY, "tmdb_v2_key_001");
        hhSecrets.put(Secrets.SENSIBO_API_KEY, "sensibo_v2_002");
        hhSecrets.put(Secrets.REMOTE_PAIRED_DEVICES, "[{\"id\":\"rem_1\",\"role\":\"ADMIN\"}]");

        CloudVaultCrypto.VaultEnvelope hhEnv = CloudVaultCrypto.encryptMap(
                CloudVaultCrypto.HOUSEHOLD_VAULT_ID, hhSecrets, pass1);
        assertEquals("household", hhEnv.vaultId, "Household vault envelope carries vault_id=household");
        assertEquals(3, hhEnv.keyCount, "Household vault records exact key count");
        assertTrue(!hhEnv.ciphertext.contains("tmdb_v2_key_001"),
                "Ciphertext never exposes plaintext API keys");

        Map<String, String> hhDecrypted = CloudVaultCrypto.decryptMap(hhEnv, pass2);
        assertEquals(hhSecrets, hhDecrypted, "Household vault decrypts identically with derived passphrase");

        String profSlot = CloudVaultCrypto.profileVaultId("824cf01b-96f3-4a6b-a554-621938e5a280");
        assertEquals("profile:824cf01b-96f3-4a6b-a554-621938e5a280", profSlot,
                "Profile vault slot uses profile:<uuid> format");
        Map<String, String> profSecrets = new LinkedHashMap<>();
        profSecrets.put("trakt_access_token", "trakt_tok_abc");
        profSecrets.put("stremio_auth_key", "stremio_key_xyz");
        CloudVaultCrypto.VaultEnvelope profEnv = CloudVaultCrypto.encryptMap(profSlot, profSecrets, pass1);
        assertEquals(profSlot, profEnv.vaultId, "Profile vault envelope carries profile slot ID");
        assertEquals(profSecrets, CloudVaultCrypto.decryptMap(profEnv, pass2),
                "Profile vault round-trips per-user Trakt & Stremio credentials");

        // Profile-scoped Secrets storage test
        File tmpDir = Files.createTempDirectory("soartv_v2_secrets_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tmpDir);
            Secrets.putSecret(null, Secrets.TMDB_KEY, "hh_tmdb_999");
            Secrets.putProfileSecret(null, "prof_alice", "trakt_access", "alice_trakt");
            Secrets.putProfileSecret(null, "prof_bob", "trakt_access", "bob_trakt");

            Map<String, String> expHh = Secrets.exportHouseholdVaultSecrets(null);
            Map<String, String> expAlice = Secrets.exportProfileVaultSecrets(null, "prof_alice");
            Map<String, String> expBob = Secrets.exportProfileVaultSecrets(null, "prof_bob");
            assertEquals("hh_tmdb_999", expHh.get(Secrets.TMDB_KEY), "Household export includes TMDB key");
            assertEquals("alice_trakt", expAlice.get("trakt_access"), "Alice profile export isolated");
            assertEquals("bob_trakt", expBob.get("trakt_access"), "Bob profile export isolated");
            assertTrue(!expHh.containsKey("trakt_access"),
                    "Per-user Trakt tokens never bleed into household vault slot");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRec(tmpDir);
        }
    }

    private static void testFieldLevelLwwMergeAndSecretMerge() {
        // TV A edits genre_order at t=2000; TV B edits theme at t=3000 simultaneously
        Map<String, Object> localVals = new LinkedHashMap<>();
        localVals.put("theme", "dark");
        localVals.put("tv_genres", Arrays.asList("Sci-Fi", "Drama"));
        localVals.put("clock_format", "24");
        Map<String, Long> localTs = new HashMap<>();
        localTs.put("theme", 1000L);
        localTs.put("tv_genres", 2000L);
        localTs.put("clock_format", 1500L);

        Map<String, Object> remoteVals = new LinkedHashMap<>();
        remoteVals.put("theme", "light");
        remoteVals.put("tv_genres", Arrays.asList("Drama", "Sci-Fi"));
        remoteVals.put("language", "en");
        Map<String, Long> remoteTs = new HashMap<>();
        remoteTs.put("theme", 3000L);
        remoteTs.put("tv_genres", 1000L);
        remoteTs.put("language", 2500L);

        CloudSyncManager.MergeResult res = CloudSyncManager.mergeFieldMaps(localVals, localTs, remoteVals, remoteTs);
        assertEquals("light", res.mergedValues.get("theme"), "Newer remote theme wins");
        assertEquals(Arrays.asList("Sci-Fi", "Drama"), res.mergedValues.get("tv_genres"),
                "Newer local genre order wins without clobbering remote theme");
        assertEquals("24", res.mergedValues.get("clock_format"), "Local-only field preserved for push");
        assertEquals("en", res.mergedValues.get("language"), "Remote-only field adopted locally");
        assertTrue(res.localChanged && res.remoteNeedsPush,
                "Bidirectional merge marks both localChanged and remoteNeedsPush");

        // Secret map merge respects locallyDirtyKeys while adopting remote updates
        Map<String, String> localSec = new LinkedHashMap<>();
        localSec.put("tmdb_key", "old_tmdb");
        localSec.put("gemini_api_key", "new_local_gemini");
        Map<String, String> remoteSec = new LinkedHashMap<>();
        remoteSec.put("tmdb_key", "rotated_cloud_tmdb");
        remoteSec.put("gemini_api_key", "old_cloud_gemini");
        remoteSec.put("omdb_key", "cloud_omdb");

        Map<String, String> mergedSec = CloudSyncManager.mergeSecretMaps(
                localSec, remoteSec, Collections.singleton("gemini_api_key"));
        assertEquals("rotated_cloud_tmdb", mergedSec.get("tmdb_key"), "Clean local key adopts remote update");
        assertEquals("new_local_gemini", mergedSec.get("gemini_api_key"), "Dirty local key wins over remote");
        assertEquals("cloud_omdb", mergedSec.get("omdb_key"), "Missing remote key imported locally");
    }

    private static void testPortableTvSettingsExtractionAndMidWayCadence() {
        Map<String, Object> sourceTv = new LinkedHashMap<>();
        sourceTv.put("room_name", "Living Room");
        sourceTv.put("enable_gaming", "true");
        sourceTv.put("enable_smarthome", "auto");
        sourceTv.put("override_shelf", Boolean.TRUE);
        sourceTv.put("remote_keys_raw", "KEYCODE_BUTTON_1=com.xiaomi.tv"); // hardware-locked!
        sourceTv.put("hdmi_port_roles", "HDMI 3=PC"); // hardware-locked!
        sourceTv.put("usb_controller_tags", "usbport:1-2=P1"); // hardware-locked!

        Map<String, Object> portable = CloudSyncManager.extractPortableTvSettings(sourceTv);
        assertEquals("Living Room", portable.get("room_name"), "Portable TV copy keeps room_name");
        assertEquals("true", portable.get("enable_gaming"), "Portable TV copy keeps enable_gaming");
        assertEquals(Boolean.TRUE, portable.get("override_shelf"), "Portable TV copy keeps override_shelf");
        assertTrue(!portable.containsKey("remote_keys_raw")
                        && !portable.containsKey("hdmi_port_roles")
                        && !portable.containsKey("usb_controller_tags"),
                "Portable TV copy strictly strips brand/hardware-locked settings");

        long interval = CloudSyncManager.PERIODIC_SYNC_INTERVAL_MS;
        assertTrue(!CloudSyncManager.shouldRunPeriodicSync(false, false, 0L, 10_000_000L, interval),
                "Mid-Way Sync never polls when Home is inactive / TV asleep");
        assertTrue(!CloudSyncManager.shouldRunPeriodicSync(true, true, 0L, 10_000_000L, interval),
                "Mid-Way Sync pauses during gaming or external media apps");
        assertTrue(!CloudSyncManager.shouldRunPeriodicSync(true, false, 10_000_000L, 10_000_000L + 60_000L, interval),
                "Mid-Way Sync respects 15-minute minimum cadence");
        assertTrue(CloudSyncManager.shouldRunPeriodicSync(true, false, 10_000_000L, 10_000_000L + interval, interval),
                "Mid-Way Sync runs when active and 15 minutes have elapsed");
    }

    private static void testSwitcherUdpBroadcastParser() {
        byte[] packet = new byte[165];
        packet[0] = (byte) 0xFE;
        packet[1] = (byte) 0xF0;
        packet[18] = (byte) 0xAB;
        packet[19] = (byte) 0xCD;
        packet[20] = (byte) 0x12;

        SmartHomeManager.SwitcherDiscovery found =
                SmartHomeManager.parseSwitcherBroadcastPacket(packet, packet.length, "192.168.88.77");
        assertTrue(found != null, "Valid Switcher 0xFE 0xF0 UDP packet parses cleanly");
        assertEquals("abcd12", found != null ? found.deviceId : "", "Extracts 3-byte hardware Device ID");
        assertEquals("192.168.88.77", found != null ? found.lanIp : "", "Extracts live sender LAN IP");

        byte[] bad = new byte[100];
        bad[0] = 0x12;
        bad[1] = 0x34;
        assertEquals(null, SmartHomeManager.parseSwitcherBroadcastPacket(bad, bad.length, "192.168.88.77"),
                "Rejects non-Switcher UDP packets");
        assertEquals(null, SmartHomeManager.parseSwitcherBroadcastPacket(packet, 20, "192.168.88.77"),
                "Rejects truncated UDP packets");
    }

    private static void testRemoteAuthHouseholdDirectorySecurityBoundary() throws Exception {
        File tmpDir = Files.createTempDirectory("soartv_v2_remote_auth_").toFile();
        try {
            Secrets.setJvmTestSecretsDir(tmpDir);
            Secrets.putSecret(null, SupabaseClient.SECRET_HOUSEHOLD_KEY, "hh_secret_key_for_admin_and_household_only");

            long now = 1_750_000_000_000L;
            RemoteAuthManager auth = new RemoteAuthManager(null, () -> now);
            auth.setTestAuthOverrides("https://example.supabase.co", "anon_key_test_123", "");

            // 1. First device pairs -> bootstrapped as ADMIN
            auth.createPairRequest("Owner Phone", "cid_admin", "192.168.1.20", "http://192.168.1.5:8484");
            RemoteAuthManager.PendingPairRequest pAdmin = auth.getActivePendingRequest();
            RemoteAuthManager.IssueResult vAdmin = auth.verifyPairing(pAdmin.sixDigitCode, "", "Owner Phone", "192.168.1.20");
            @SuppressWarnings("unchecked")
            Map<String, Object> mAdmin = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(vAdmin.jsonBody);
            String adminToken = String.valueOf(mAdmin.get("token"));
            String adminJson = auth.buildAuthConfigJson("Bearer " + adminToken, "Living Room TV");
            assertTrue(adminJson.contains("\"householdKey\":\"hh_secret_key_for_admin_and_household_only\""),
                    "ADMIN remote receives household directory key for Multi-TV & IP resilience");
            assertTrue(adminJson.contains("\"supabaseAnonKey\":\"anon_key_test_123\""),
                    "ADMIN remote receives supabaseAnonKey");

            // 2. Second device approved as HOUSEHOLD
            RemoteAuthManager.IssueResult rHh = auth.createPairRequest("Family Tablet", "cid_hh", "192.168.1.21", "http://192.168.1.5:8484");
            @SuppressWarnings("unchecked")
            Map<String, Object> reqHh = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(rHh.jsonBody);
            auth.approvePendingOnTv(RemoteAuthManager.Role.HOUSEHOLD);
            RemoteAuthManager.IssueResult pollHh = auth.pollPairRequest(String.valueOf(reqHh.get("requestId")));
            @SuppressWarnings("unchecked")
            Map<String, Object> mHh = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(pollHh.jsonBody);
            String hhToken = String.valueOf(mHh.get("token"));
            String hhJson = auth.buildAuthConfigJson("Bearer " + hhToken, "Living Room TV");
            assertTrue(hhJson.contains("\"householdKey\":\"hh_secret_key_for_admin_and_household_only\""),
                    "HOUSEHOLD remote receives household directory key");

            // 3. Third device pairs via QR -> 30-day GUEST (MUST NEVER receive householdKey)
            auth.createPairRequest("Visitor Phone", "cid_guest", "192.168.1.22", "http://192.168.1.5:8484");
            RemoteAuthManager.PendingPairRequest pGuest = auth.getActivePendingRequest();
            RemoteAuthManager.IssueResult vGuest = auth.verifyPairing("", pGuest.qrSecret, "Visitor Phone", "192.168.1.22");
            @SuppressWarnings("unchecked")
            Map<String, Object> mGuest = (Map<String, Object>) RemoteAuthManager.MiniJson.parse(vGuest.jsonBody);
            String guestToken = String.valueOf(mGuest.get("token"));
            String guestJson = auth.buildAuthConfigJson("Bearer " + guestToken, "Living Room TV");
            assertTrue(!guestJson.contains("householdKey") && !guestJson.contains("hh_secret_key"),
                    "GUEST (30-day) remote NEVER receives householdKey");

            String unauthJson = auth.buildAuthConfigJson("", "Living Room TV");
            assertTrue(!unauthJson.contains("householdKey") && !unauthJson.contains("hh_secret_key"),
                    "Unauthenticated caller NEVER receives householdKey");
        } finally {
            Secrets.setJvmTestSecretsDir(null);
            deleteRec(tmpDir);
        }
    }

    private static void testSupabaseV2QueryPathsAndParsers() {
        List<String> paths = Arrays.asList(
                SupabaseClient.buildHouseholdSettingsQueryPath(),
                SupabaseClient.buildProfileSettingsQueryPath("prof-uuid-1"),
                SupabaseClient.buildTvDevicesQueryPath(true),
                SupabaseClient.buildTvDeviceQueryPath("tv-uuid-1"),
                SupabaseClient.buildGamingHostsQueryPath(),
                SupabaseClient.buildEncryptedVaultQueryPath("household")
        );
        for (String p : paths) {
            assertTrue(p.contains("select=") && !p.contains("select=*"),
                    "v2 Supabase query uses explicit column projection (no select=*): " + p);
        }

        String tvJson = "[{\"device_id\":\"tv-111\",\"device_name\":\"Living Room OLED\","
                + "\"room_name\":\"Living Room\",\"platform\":\"lg-webos\",\"brand\":\"LG\","
                + "\"model\":\"OLED65C3\",\"lan_ip\":\"192.168.50.90\",\"remote_port\":8484,"
                + "\"mdns_host\":\"soartv-living-room-oled.local\",\"realtime_channel\":\"tv-111\","
                + "\"tv_settings\":{\"enable_gaming\":\"false\"},\"field_timestamps\":{\"enable_gaming\":1700000},"
                + "\"is_active\":true}]";
        List<SupabaseClient.TvDeviceRecord> parsedTvs = SupabaseClient.parseTvDevices(tvJson);
        assertEquals(1, parsedTvs.size(), "parseTvDevices parses LG webOS record");
        assertEquals("lg-webos", parsedTvs.get(0).platform, "parseTvDevices reads platform");
        assertEquals("false", parsedTvs.get(0).tvSettings.get("enable_gaming"), "parseTvDevices reads tv_settings");
        assertEquals(Long.valueOf(1700000L), parsedTvs.get(0).fieldTimestamps.get("enable_gaming"),
                "parseTvDevices reads field_timestamps");

        String hostsJson = "[{\"host_id\":\"pc-main\",\"hostname\":\"GAMING-RIG\",\"lan_ip\":\"192.168.50.10\","
                + "\"agent_port\":8085,\"mac_addresses\":[\"AA:BB:CC:DD:EE:FF\"],\"sunshine_uuid\":\"sun-1\"}]";
        List<SupabaseClient.GamingHostRecord> hosts = SupabaseClient.parseGamingHosts(hostsJson);
        assertEquals(1, hosts.size(), "parseGamingHosts parses Gaming PC host record");
        assertEquals("192.168.50.10", hosts.get(0).lanIp, "parseGamingHosts extracts live LAN IP");
        assertEquals(Collections.singletonList("AA:BB:CC:DD:EE:FF"), hosts.get(0).macAddresses,
                "parseGamingHosts extracts NIC MAC addresses for WoL");
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRec(c);
        }
        f.delete();
    }
}
