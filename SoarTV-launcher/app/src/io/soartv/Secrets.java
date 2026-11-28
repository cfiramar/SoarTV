package io.soartv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hardware-backed encrypted secret storage ({@code AndroidKeyStore} {@code AES/GCM/NoPadding}) and
 * ADB-only {@link ContentProvider} ({@code content://io.soartv.secrets/<name>}).
 *
 * <p><b>Why:</b> Any installed app on Android TV can read {@code classes.dex} inside the APK, and root/backup
 * processes could inspect raw files if stored unencrypted. {@code Secrets} keeps sensitive tokens, API keys,
 * Stremio session keys, and paired remote device records out of {@code Config.java} and encrypted at rest
 * with an {@code AndroidKeyStore} 256-bit AES-GCM master key ({@code ENC1:} prefix + 12-byte IV + ciphertext).
 *
 * <p><b>What:</b> Transparently encrypts values on write, decrypts on read, auto-migrates any legacy plaintext
 * secret file to {@code ENC1:} on first access, and falls back cleanly to a deterministic file-backed key in
 * headless Desktop JVM unit tests where {@code AndroidKeyStore} is unavailable.
 */
public class Secrets extends ContentProvider {
    /** adb's shell (Process.SHELL_UID, which the SDK only names from Android 10). */
    private static final int SHELL_UID = 2000;
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_KEY_ALIAS = "soartv_secrets_master_key";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ENC_PREFIX = "ENC1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Object LOCK = new Object();
    private static final SecureRandom RNG = new SecureRandom();

    static final String ADB_KEY = "adb_key";
    public static final String SUPABASE_URL = "supabase_url";
    public static final String SUPABASE_ANON_KEY = "supabase_anon_key";
    public static final String SENSIBO_API_KEY = "sensibo_api_key";
    public static final String TUYA_ACCESS_ID = "tuya_access_id";
    public static final String TUYA_ACCESS_SECRET = "tuya_access_secret";
    public static final String GITHUB_TOKEN = "github_token";
    public static final String STREMIO_AUTH_KEY = "stremio_auth_key";
    public static final String HOUSEHOLD_STREMIO_AUTH_KEY = "household_stremio_auth_key";
    public static final String HOUSEHOLD_KEY = "household_key";
    public static final String GAMING_PC_TOKEN = "gaming_pc_token";
    public static final String TMDB_KEY = "tmdb_key";
    public static final String OMDB_KEY = "omdb_key";
    public static final String MDBLIST_KEY = "mdblist_key";
    public static final String GEMINI_API_KEY = "gemini_api_key";
    public static final String ADMIN_EMAILS = "admin_emails";
    public static final String REMOTE_PAIRED_DEVICES = "remote_paired_devices";
    public static final String TRAKT_CLIENT_ID = "trakt_client_id";
    public static final String SWITCHER_API_KEY = "switcher_api_key";
    public static final String TV_BOOTSTRAP_DEVICE_ID = "tv_bootstrap_device_id";
    public static final String TV_BOOTSTRAP_NAME = "tv_bootstrap_name";
    public static final String TV_BOOTSTRAP_ROOM = "tv_bootstrap_room";
    public static final String TV_BOOTSTRAP_REPLACE_ID = "tv_bootstrap_replace_id";

    /** Household-shared secret names synchronized via {@code encrypted_vault['household']}. */
    static final List<String> HOUSEHOLD_VAULT_KEYS = Collections.unmodifiableList(Arrays.asList(
            SENSIBO_API_KEY,
            TUYA_ACCESS_ID,
            TUYA_ACCESS_SECRET,
            GITHUB_TOKEN,
            HOUSEHOLD_STREMIO_AUTH_KEY,
            GAMING_PC_TOKEN,
            TMDB_KEY,
            OMDB_KEY,
            MDBLIST_KEY,
            GEMINI_API_KEY,
            ADMIN_EMAILS,
            REMOTE_PAIRED_DEVICES,
            TRAKT_CLIENT_ID,
            SWITCHER_API_KEY
    ));

    /** Per-profile secret keys synchronized via {@code encrypted_vault['profile:<uuid>']}. */
    static final List<String> PROFILE_VAULT_KEYS = Collections.unmodifiableList(Arrays.asList(
            "trakt_access",
            "trakt_refresh",
            "trakt_expires",
            "trakt_user",
            "stremio_auth_key",
            "stremio_email"
    ));

    static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            ADB_KEY,
            SUPABASE_URL,
            SUPABASE_ANON_KEY,
            SENSIBO_API_KEY,
            TUYA_ACCESS_ID,
            TUYA_ACCESS_SECRET,
            GITHUB_TOKEN,
            STREMIO_AUTH_KEY,
            HOUSEHOLD_STREMIO_AUTH_KEY,
            HOUSEHOLD_KEY,
            GAMING_PC_TOKEN,
            TMDB_KEY,
            OMDB_KEY,
            MDBLIST_KEY,
            GEMINI_API_KEY,
            ADMIN_EMAILS,
            REMOTE_PAIRED_DEVICES,
            TRAKT_CLIENT_ID,
            "wiz_light_ips",
            SWITCHER_API_KEY,
            "enable_gaming",
            "enable_smarthome",
            TV_BOOTSTRAP_DEVICE_ID,
            TV_BOOTSTRAP_NAME,
            TV_BOOTSTRAP_ROOM,
            TV_BOOTSTRAP_REPLACE_ID
    ));

    /** Optional directory override used strictly by headless Desktop JVM unit tests when Context is null/stub. */
    private static volatile File jvmTestSecretsDir;

    /**
     * Lock-free O(1) RAM cache of decrypted secrets (and {@code ""} negative sentinels for absent secrets)
     * keyed by {@code "<canonicalDir>\n<secretName>"} so 60fps UI/recommendation calls never hit flash or
     * {@code AndroidKeyStore} Binder IPC on repeated reads.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> MEMORY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger DECRYPT_COUNT_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile long lastDirMtimeCheckAtMs = 0L;
    private static volatile long lastObservedDirMtimeMs = -1L;

    /**
     * Sets or clears the secrets directory override for headless Desktop JVM unit tests.
     *
     * @param dir Temporary test directory, or {@code null} to clear.
     */
    static void setJvmTestSecretsDir(File dir) {
        jvmTestSecretsDir = dir;
        MEMORY_CACHE.clear();
        lastDirMtimeCheckAtMs = 0L;
        lastObservedDirMtimeMs = -1L;
    }

    static void resetDecryptCountForTest() {
        DECRYPT_COUNT_FOR_TEST.set(0);
    }

    static int getDecryptCountForTest() {
        return DECRYPT_COUNT_FOR_TEST.get();
    }

    static void clearMemoryCacheForTest() {
        MEMORY_CACHE.clear();
        lastDirMtimeCheckAtMs = 0L;
        lastObservedDirMtimeMs = -1L;
    }

    private static String cacheKey(File dir, String name) {
        return dir.getAbsolutePath() + "\n" + name;
    }

    private static void maybeInvalidateOnExternalWrite(File dir) {
        long now = System.currentTimeMillis();
        if (now - lastDirMtimeCheckAtMs < 5_000L) return;
        lastDirMtimeCheckAtMs = now;
        long mtime = dir.isDirectory() ? dir.lastModified() : 0L;
        long prev = lastObservedDirMtimeMs;
        if (prev >= 0L && mtime != prev) {
            MEMORY_CACHE.clear();
        }
        lastObservedDirMtimeMs = mtime;
    }

    /**
     * Performs an unthrottled cross-process directory mtime check so an isolated process (such as
     * {@code :stream}) immediately observes freshly paired tokens written by {@code :home}.
     */
    public static void refreshIfDirChanged(Context c) {
        refreshIfDirChangedForDir(resolveSecretsDir(c));
    }

    static void refreshIfDirChangedForDir(File dir) {
        if (dir == null) return;
        long mtime = dir.isDirectory() ? dir.lastModified() : 0L;
        long prev = lastObservedDirMtimeMs;
        if (prev < 0L || mtime != prev) {
            MEMORY_CACHE.clear();
            lastObservedDirMtimeMs = mtime;
        }
        lastDirMtimeCheckAtMs = System.currentTimeMillis();
    }

    private static boolean isValidSecretName(String name) {
        return name != null && !name.isEmpty() && name.matches("[a-zA-Z0-9_.-]{1,96}") && !name.contains("..");
    }

    /** Builds a filesystem-safe per-profile secret name ({@code "prof_<safeId>_<key>"}). */
    public static String profileSecretName(String profileId, String key) {
        if (profileId == null || key == null) return "";
        String safeId = profileId.trim().replaceAll("[^a-zA-Z0-9_-]", "");
        String safeKey = key.trim().replaceAll("[^a-zA-Z0-9_.-]", "");
        if (safeId.isEmpty() || safeKey.isEmpty()) return "";
        if (safeId.length() > 40) safeId = safeId.substring(0, 40);
        return "prof_" + safeId + "_" + safeKey;
    }

    /** Reads a hardware-encrypted per-profile secret, or {@code ""} if unset. */
    public static String getProfileSecret(Context c, String profileId, String key) {
        String name = profileSecretName(profileId, key);
        return name.isEmpty() ? "" : getSecret(c, name);
    }

    /** Encrypts and persists a per-profile secret at rest (or deletes it when empty). */
    public static boolean putProfileSecret(Context c, String profileId, String key, String value) {
        String name = profileSecretName(profileId, key);
        return !name.isEmpty() && putSecret(c, name, value);
    }

    /** Exports household-shared secrets (excluding device-local ADB/bootstrap keys and household_key itself). */
    public static Map<String, String> exportHouseholdVaultSecrets(Context c) {
        File dir = resolveSecretsDir(c);
        Map<String, String> out = new LinkedHashMap<>();
        if (dir == null) return out;
        synchronized (LOCK) {
            for (String key : HOUSEHOLD_VAULT_KEYS) {
                String val = getSecretFromDir(dir, key);
                if (val != null && !val.isEmpty()) {
                    out.put(key, val);
                }
            }
        }
        return out;
    }

    /** Imports household-shared secrets into hardware-encrypted storage. */
    public static void importHouseholdVaultSecrets(Context c, Map<String, String> map) {
        File dir = resolveSecretsDir(c);
        if (dir == null || map == null || map.isEmpty()) return;
        synchronized (LOCK) {
            for (String key : HOUSEHOLD_VAULT_KEYS) {
                if (!map.containsKey(key)) continue;
                String val = map.get(key);
                if (val != null && !val.isEmpty()) {
                    putSecretToDir(dir, key, val);
                }
            }
        }
    }

    /** Exports per-profile secrets (Trakt OAuth tokens + Stremio authKey/email) for {@code profileId}. */
    public static Map<String, String> exportProfileVaultSecrets(Context c, String profileId) {
        File dir = resolveSecretsDir(c);
        Map<String, String> out = new LinkedHashMap<>();
        if (dir == null || profileId == null || profileId.trim().isEmpty()) return out;
        synchronized (LOCK) {
            for (String key : PROFILE_VAULT_KEYS) {
                String full = profileSecretName(profileId, key);
                String val = getSecretFromDir(dir, full);
                if (val != null && !val.isEmpty()) {
                    out.put(key, val);
                }
            }
        }
        return out;
    }

    /** Imports per-profile secrets (Trakt OAuth tokens + Stremio authKey/email) for {@code profileId}. */
    public static void importProfileVaultSecrets(Context c, String profileId, Map<String, String> map) {
        File dir = resolveSecretsDir(c);
        if (dir == null || profileId == null || profileId.trim().isEmpty() || map == null || map.isEmpty()) return;
        synchronized (LOCK) {
            for (String key : PROFILE_VAULT_KEYS) {
                if (!map.containsKey(key)) continue;
                String val = map.get(key);
                String full = profileSecretName(profileId, key);
                if (val != null && !val.isEmpty()) {
                    putSecretToDir(dir, full, val);
                }
            }
        }
    }

    private static File resolveSecretsDir(Context c) {
        if (c != null) {
            try {
                File filesDir = c.getFilesDir();
                if (filesDir != null) {
                    return new File(filesDir, "secrets");
                }
            } catch (RuntimeException ignored) {
                // Headless JVM test stub fallback
            }
        }
        return jvmTestSecretsDir;
    }

    private static File file(Context c, String name) {
        File dir = resolveSecretsDir(c);
        return dir != null ? new File(dir, name) : null;
    }

    private static void safeLog(String msg) {
        try {
            AppLog.w(msg);
        } catch (RuntimeException ignored) {
            // Headless JVM test stub
        }
    }

    /**
     * Package-private backward-compatible accessor returning the decrypted secret for {@code name}, or {@code ""}.
     */
    static String get(Context c, String name) {
        return getSecret(c, name);
    }

    /**
     * Reads and decrypts the secret stored under {@code name}, or returns {@code ""} if absent.
     * Automatically migrates any legacy unencrypted secret file on disk to {@code ENC1:} AES-256-GCM format.
     *
     * @param c Android context (or {@code null} in JVM unit tests when {@link #setJvmTestSecretsDir} is active).
     * @param name Secret identifier.
     * @return Decrypted secret value, or {@code ""}.
     */
    public static String getSecret(Context c, String name) {
        return getSecretFromDir(resolveSecretsDir(c), name);
    }

    /**
     * Encrypts and atomically persists {@code value} under {@code name} using {@code AES/GCM/NoPadding}.
     * Passing {@code null} or empty {@code value} deletes the secret.
     *
     * @param c Android context.
     * @param name Secret identifier.
     * @param value Plaintext secret to encrypt at rest.
     * @return {@code true} if persisted (or deleted when empty) cleanly.
     */
    public static boolean putSecret(Context c, String name, String value) {
        return putSecretToDir(resolveSecretsDir(c), name, value);
    }

    /**
     * Deletes the encrypted secret file for {@code name}.
     *
     * @param c Android context.
     * @param name Secret identifier.
     * @return {@code true} if the file existed and was deleted.
     */
    public static boolean deleteSecret(Context c, String name) {
        return deleteSecretFromDir(resolveSecretsDir(c), name);
    }

    /**
     * Exports all present user/API secrets (excluding device-local {@code adb_key}) as a decrypted map
     * suitable for client-side E2EE Cloud Vault backup.
     *
     * @param c Android context.
     * @return Map of secret name to decrypted plaintext value.
     */
    public static Map<String, String> exportVaultSecrets(Context c) {
        return exportVaultSecretsFromDir(resolveSecretsDir(c));
    }

    /**
     * Imports a map of decrypted secrets from the E2EE Cloud Vault and encrypts each non-empty entry at rest.
     *
     * @param c Android context.
     * @param map Map of secret names to plaintext values.
     */
    public static void importVaultSecrets(Context c, Map<String, String> map) {
        importVaultSecretsToDir(resolveSecretsDir(c), map);
    }

    static String getSecretFromDir(File dir, String name) {
        if (dir == null || !isValidSecretName(name)) return "";
        maybeInvalidateOnExternalWrite(dir);
        String ck = cacheKey(dir, name);
        String cached = MEMORY_CACHE.get(ck);
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            cached = MEMORY_CACHE.get(ck);
            if (cached != null) {
                return cached;
            }
            File target = new File(dir, name);
            if (!target.isFile()) {
                MEMORY_CACHE.put(ck, "");
                return "";
            }
            String raw;
            try {
                raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                return "";
            }
            if (raw.isEmpty()) {
                MEMORY_CACHE.put(ck, "");
                return "";
            }
            if (raw.startsWith(ENC_PREFIX)) {
                try {
                    String plain = decryptPayload(dir, raw.substring(ENC_PREFIX.length()));
                    MEMORY_CACHE.put(ck, plain != null ? plain : "");
                    return plain != null ? plain : "";
                } catch (Exception e) {
                    safeLog("secrets: failed to decrypt " + name);
                    MEMORY_CACHE.put(ck, "");
                    return "";
                }
            }
            // Legacy plaintext secret file: migrate to ENC1: AES-256-GCM immediately
            putSecretToDir(dir, name, raw);
            MEMORY_CACHE.put(ck, raw);
            return raw;
        }
    }

    static boolean putSecretToDir(File dir, String name, String value) {
        if (dir == null || !isValidSecretName(name)) return false;
        if (value == null || value.isEmpty()) {
            deleteSecretFromDir(dir, name);
            return true;
        }
        synchronized (LOCK) {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            try {
                String encrypted = ENC_PREFIX + encryptPayload(dir, value);
                File target = new File(dir, name);
                File tmp = new File(dir, "." + name + ".enc.tmp");
                Files.write(tmp.toPath(), encrypted.getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                lastObservedDirMtimeMs = dir.lastModified();
                lastDirMtimeCheckAtMs = System.currentTimeMillis();
                MEMORY_CACHE.put(cacheKey(dir, name), value);
                return true;
            } catch (Exception e) {
                safeLog("secrets: failed to encrypt " + name);
                return false;
            }
        }
    }

    static boolean deleteSecretFromDir(File dir, String name) {
        if (dir == null || !isValidSecretName(name)) return false;
        synchronized (LOCK) {
            File target = new File(dir, name);
            boolean deleted = target.exists() && target.delete();
            lastObservedDirMtimeMs = dir.isDirectory() ? dir.lastModified() : 0L;
            lastDirMtimeCheckAtMs = System.currentTimeMillis();
            MEMORY_CACHE.put(cacheKey(dir, name), "");
            return deleted;
        }
    }

    static Map<String, String> exportVaultSecretsFromDir(File dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dir == null) return out;
        synchronized (LOCK) {
            for (String key : NAMES) {
                if (ADB_KEY.equals(key)) continue;
                String val = getSecretFromDir(dir, key);
                if (val != null && !val.isEmpty()) {
                    out.put(key, val);
                }
            }
        }
        return out;
    }

    static void importVaultSecretsToDir(File dir, Map<String, String> map) {
        if (dir == null || map == null || map.isEmpty()) return;
        synchronized (LOCK) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (isValidSecretName(k) && !ADB_KEY.equals(k) && v != null && !v.isEmpty()) {
                    putSecretToDir(dir, k, v);
                }
            }
        }
    }

    private static final class MasterKeyHolder {
        final SecretKey key;
        final boolean androidKeyStoreBacked;

        MasterKeyHolder(SecretKey key, boolean androidKeyStoreBacked) {
            this.key = key;
            this.androidKeyStoreBacked = androidKeyStoreBacked;
        }
    }

    private static MasterKeyHolder resolveMasterKey(File secretsDir) throws Exception {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            KeyStore.Entry existing = ks.getEntry(MASTER_KEY_ALIAS, null);
            if (existing instanceof KeyStore.SecretKeyEntry) {
                return new MasterKeyHolder(((KeyStore.SecretKeyEntry) existing).getSecretKey(), true);
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();
            kg.init(spec);
            return new MasterKeyHolder(kg.generateKey(), true);
        } catch (Throwable keystoreUnavailable) {
            // Headless Desktop JVM unit test fallback where AndroidKeyStore throws KeyStoreException or Stub!
            if (!secretsDir.isDirectory() && !secretsDir.mkdirs()) {
                throw new IOException("Cannot create secrets directory");
            }
            File fallbackFile = new File(secretsDir, ".jvm_master_key");
            byte[] keyBytes = null;
            if (fallbackFile.isFile()) {
                try {
                    String b64 = new String(Files.readAllBytes(fallbackFile.toPath()), StandardCharsets.UTF_8).trim();
                    byte[] decoded = Base64.getDecoder().decode(b64);
                    if (decoded.length == 32) {
                        keyBytes = decoded;
                    }
                } catch (Exception ignored) {
                }
            }
            if (keyBytes == null) {
                keyBytes = new byte[32];
                RNG.nextBytes(keyBytes);
                Files.write(fallbackFile.toPath(), Base64.getEncoder().encode(keyBytes));
            }
            return new MasterKeyHolder(new SecretKeySpec(keyBytes, "AES"), false);
        }
    }

    private static String encryptPayload(File secretsDir, String plaintext) throws Exception {
        MasterKeyHolder holder = resolveMasterKey(secretsDir);
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        byte[] iv;
        if (holder.androidKeyStoreBacked) {
            cipher.init(Cipher.ENCRYPT_MODE, holder.key);
            iv = cipher.getIV();
            if (iv == null || iv.length != GCM_IV_BYTES) {
                throw new IOException("Unexpected AndroidKeyStore GCM IV length");
            }
        } else {
            iv = new byte[GCM_IV_BYTES];
            RNG.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, holder.key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        }
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static String decryptPayload(File secretsDir, String encodedB64) throws Exception {
        DECRYPT_COUNT_FOR_TEST.incrementAndGet();
        byte[] combined = Base64.getDecoder().decode(encodedB64.trim());
        if (combined.length <= GCM_IV_BYTES + 16) {
            throw new IOException("Truncated encrypted secret payload");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_BYTES);
        byte[] ct = Arrays.copyOfRange(combined, GCM_IV_BYTES, combined.length);
        MasterKeyHolder holder = resolveMasterKey(secretsDir);
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, holder.key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plainBytes = cipher.doFinal(ct);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    @Override public boolean onCreate() {
        Context c = getContext();
        if (c != null) {
            // Proactively migrate any legacy unencrypted files in files/secrets/ to ENC1: on startup
            File dir = resolveSecretsDir(c);
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (f.isFile() && !n.startsWith(".") && isValidSecretName(n)) {
                            getSecretFromDir(dir, n);
                        }
                    }
                }
            }
        }
        return true;
    }

    private static String name(Uri uri) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends keys");
        String name = uri.getLastPathSegment();
        if (!NAMES.contains(name)) throw new FileNotFoundException("one of " + NAMES + ": content://io.soartv.secrets/<name>");
        return name;
    }

    /** Printable ASCII payload (4..16384 chars, allowing spaces/commas/JSON for admin_emails & vault structures). */
    static boolean whole(File f) {
        if (f == null || !f.isFile() || f.length() == 0 || f.length() > 16500) return false;
        try {
            String v = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return isValidPlaintextPayload(v);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isValidPlaintextPayload(String v) {
        if (v == null || v.length() < 4 || v.length() > 16384) return false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < 0x20 || ch > 0x7E) return false;
        }
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if (!mode.contains("w")) throw new FileNotFoundException("write only");
        File done = file(getContext(), name);
        if (done == null) throw new FileNotFoundException("no files dir");
        File dir = done.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("can't create " + dir);
        File part = new File(dir, "." + name + ".part");
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean ok = false;
                        try {
                            if (part.isFile() && part.length() > 0 && part.length() <= 16500) {
                                String plaintext = new String(Files.readAllBytes(part.toPath()), StandardCharsets.UTF_8).trim();
                                if (isValidPlaintextPayload(plaintext)) {
                                    ok = putSecret(getContext(), name, plaintext);
                                }
                            }
                        } catch (IOException ignored) {
                        } finally {
                            // Always delete temporary plaintext .part file so raw plaintext never sits on flash
                            //noinspection ResultOfMethodCallIgnored
                            part.delete();
                        }
                        safeLog("secrets: " + name + (ok ? " received (encrypted)" : " not received"));
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        try {
            return deleteSecret(getContext(), name(uri)) ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    /** One row: present (1 or 0) and when it was received (ms, 0 if absent). Never the value. */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String name;
        try {
            name = name(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        File f = file(getContext(), name);
        boolean present = f != null && f.isFile();
        MatrixCursor row = new MatrixCursor(new String[] {"present", "received"});
        row.addRow(new Object[] {present ? 1 : 0, present ? f.lastModified() : 0});
        return row;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
