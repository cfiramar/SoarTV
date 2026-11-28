package io.soartv;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client-side Zero-Knowledge cryptographic vault for backing up and restoring household API keys in
 * {@code public.encrypted_vault}.
 *
 * <p><b>Why:</b> Enables all private API keys (TMDB, OMDb, MDBList, Gemini, Stremio, Sensibo, Tuya,
 * household key) to survive both PC failure and TV hardware replacement via free-tier Supabase storage
 * without a single plaintext secret key or key name ever leaving the device.
 *
 * <p><b>What:</b> Derives a 256-bit AES key from a user-supplied master passphrase using
 * {@code PBKDF2WithHmacSHA256} (120,000 iterations, random 128-bit salt) and encrypts/authenticates the
 * UTF-8 JSON key map using {@code AES/GCM/NoPadding} (random 96-bit IV, 128-bit authentication tag, and
 * authenticated KDF metadata).
 */
public final class CloudVaultCrypto {

    /** Default primary row identifier in {@code public.encrypted_vault}. */
    public static final String DEFAULT_VAULT_ID = "primary";

    /** Household-wide automatic E2EE vault slot identifier. */
    public static final String HOUSEHOLD_VAULT_ID = "household";

    /** Prefix for per-user profile automatic E2EE vault slots ({@code "profile:<profileUuid>"}). */
    public static final String PROFILE_VAULT_PREFIX = "profile:";

    /** Domain separation prefix used when deriving the automatic E2EE vault passphrase from {@code household_key}. */
    public static final String HOUSEHOLD_VAULT_KDF_DOMAIN = "soartv-vault-key-v2:";

    /** Canonical algorithm identifier persisted in {@code public.encrypted_vault.kdf_algorithm}. */
    public static final String KDF_ALGORITHM_LABEL = "PBKDF2-HMAC-SHA256";

    /** Standard JCA SecretKeyFactory algorithm name available on Android API 26+ and Java 8+. */
    public static final String JCA_KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** Standard JCA AEAD cipher transformation. */
    public static final String JCA_CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /** Underlying block cipher key algorithm name. */
    private static final String KEY_ALGORITHM = "AES";

    /** Default PBKDF2-HMAC-SHA256 work factor (120,000 iterations). */
    public static final int DEFAULT_KDF_ITERATIONS = 120_000;

    /** Minimum acceptable PBKDF2 iteration count enforced on decryption to block downgrade attacks. */
    public static final int MIN_KDF_ITERATIONS = 100_000;

    /** Derived AES key length in bits (AES-256). */
    public static final int KEY_LENGTH_BITS = 256;

    /** Random cryptographic salt size in bytes (128 bits). */
    public static final int SALT_LENGTH_BYTES = 16;

    /** NIST SP 800-38D recommended GCM initialization vector length in bytes (96 bits). */
    public static final int GCM_IV_LENGTH_BYTES = 12;

    /** GCM authentication tag length in bits (128 bits). */
    public static final int GCM_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CloudVaultCrypto() {
    }

    /**
     * Immutable wire/storage envelope corresponding 1:1 with a row in {@code public.encrypted_vault}.
     *
     * <p><b>Why:</b> Encapsulates all non-secret KDF parameters, IV, and authenticated ciphertext needed
     * to deterministically verify and decrypt a cloud key backup on any authorized household TV or phone.
     *
     * <p><b>What:</b> Holds Base64-encoded salt, IV, and AES-256-GCM ciphertext alongside canonical
     * {@code snake_case} JSON serialization helpers.
     */
    public static final class VaultEnvelope {
        public final String vaultId;
        public final String kdfAlgorithm;
        public final String kdfSalt;
        public final int kdfIterations;
        public final String cipherIv;
        public final String ciphertext;
        public final int keyCount;

        /**
         * Creates a {@link VaultEnvelope} with explicit metadata and ciphertext fields.
         *
         * @param vaultId       Row primary key (defaults to {@code "primary"} when null/blank).
         * @param kdfAlgorithm  KDF identifier (defaults to {@code "PBKDF2-HMAC-SHA256"} when null/blank).
         * @param kdfSalt       Base64-encoded random salt.
         * @param kdfIterations PBKDF2 iteration count.
         * @param cipherIv      Base64-encoded 12-byte GCM IV.
         * @param ciphertext    Base64-encoded AES-256-GCM ciphertext + 128-bit auth tag.
         * @param keyCount      Count of secret entries encrypted inside the payload.
         */
        public VaultEnvelope(String vaultId, String kdfAlgorithm, String kdfSalt, int kdfIterations,
                             String cipherIv, String ciphertext, int keyCount) {
            this.vaultId = (vaultId == null || vaultId.trim().isEmpty()) ? DEFAULT_VAULT_ID : vaultId.trim();
            this.kdfAlgorithm = (kdfAlgorithm == null || kdfAlgorithm.trim().isEmpty())
                    ? KDF_ALGORITHM_LABEL : kdfAlgorithm.trim();
            this.kdfSalt = kdfSalt == null ? "" : kdfSalt.trim();
            this.kdfIterations = kdfIterations;
            this.cipherIv = cipherIv == null ? "" : cipherIv.trim();
            this.ciphertext = ciphertext == null ? "" : ciphertext.trim();
            this.keyCount = Math.max(0, keyCount);
        }

        /**
         * Serializes this envelope to canonical {@code snake_case} JSON matching {@code public.encrypted_vault}.
         *
         * @return UTF-8 JSON object string ready for Supabase PostgREST upsert.
         */
        public String toJsonString() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vault_id", vaultId);
            row.put("kdf_algorithm", kdfAlgorithm);
            row.put("kdf_salt", kdfSalt);
            row.put("kdf_iterations", kdfIterations);
            row.put("cipher_iv", cipherIv);
            row.put("ciphertext", ciphertext);
            row.put("key_count", keyCount);
            return JsonCodec.toJsonObjectString(row);
        }

        /**
         * Deserializes a {@link VaultEnvelope} from either a JSON object ({@code {...}}) or a PostgREST
         * single-row JSON array ({@code [{...}]}).
         *
         * @param json Raw JSON response body.
         * @return Parsed {@link VaultEnvelope}, or {@code null} if the input is blank or an empty array {@code []}.
         */
        public static VaultEnvelope fromJsonString(String json) {
            if (json == null) return null;
            String trimmed = json.trim();
            if (trimmed.isEmpty() || "[]".equals(trimmed)) return null;
            Map<String, Object> obj;
            if (trimmed.startsWith("[")) {
                List<Map<String, Object>> rows = JsonCodec.parseJsonObjectArray(trimmed);
                if (rows.isEmpty()) return null;
                obj = rows.get(0);
            } else {
                obj = JsonCodec.parseJsonObject(trimmed);
            }
            if (obj.isEmpty()) return null;
            return new VaultEnvelope(
                    JsonCodec.optString(obj, "vault_id", JsonCodec.optString(obj, "vaultId", DEFAULT_VAULT_ID)),
                    JsonCodec.optString(obj, "kdf_algorithm", JsonCodec.optString(obj, "kdfAlgorithm", KDF_ALGORITHM_LABEL)),
                    JsonCodec.optString(obj, "kdf_salt", JsonCodec.optString(obj, "kdfSalt", "")),
                    JsonCodec.optInt(obj, "kdf_iterations", JsonCodec.optInt(obj, "kdfIterations", DEFAULT_KDF_ITERATIONS)),
                    JsonCodec.optString(obj, "cipher_iv", JsonCodec.optString(obj, "cipherIv", "")),
                    JsonCodec.optString(obj, "ciphertext", ""),
                    JsonCodec.optInt(obj, "key_count", JsonCodec.optInt(obj, "keyCount", 0))
            );
        }
    }

    /**
     * Encrypts a map of secret key/value pairs into an authenticated {@link VaultEnvelope} using
     * {@code PBKDF2-HMAC-SHA256} (120,000 iterations) and {@code AES-256-GCM} under the default {@code "primary"} slot.
     *
     * @param secretsMap       Key-value map of API keys to encrypt (null/empty entries are filtered cleanly; null map rejected).
     * @param masterPassphrase Non-empty user master passphrase characters.
     * @return Authenticated {@link VaultEnvelope} containing zero plaintext key names or values.
     * @throws IllegalArgumentException If {@code secretsMap} is null or {@code masterPassphrase} is null/empty.
     */
    public static VaultEnvelope encryptMap(Map<String, String> secretsMap, char[] masterPassphrase) {
        return encryptMap(DEFAULT_VAULT_ID, secretsMap, masterPassphrase);
    }

    /**
     * Encrypts a map of secret key/value pairs into an authenticated {@link VaultEnvelope} for a specific
     * {@code vaultId} slot (e.g., {@code "household"} or {@code "profile:<uuid>"}).
     *
     * @param vaultId          Target vault slot identifier (defaults to {@code "primary"} if blank).
     * @param secretsMap       Key-value map of secrets to encrypt.
     * @param masterPassphrase Non-empty passphrase characters (typically derived from {@code household_key}).
     * @return Authenticated {@link VaultEnvelope} containing zero plaintext key names or values.
     * @throws IllegalArgumentException If {@code secretsMap} is null or {@code masterPassphrase} is null/empty.
     */
    public static VaultEnvelope encryptMap(String vaultId, Map<String, String> secretsMap, char[] masterPassphrase) {
        if (secretsMap == null) {
            throw new IllegalArgumentException("secretsMap must not be null");
        }
        if (masterPassphrase == null || masterPassphrase.length == 0) {
            throw new IllegalArgumentException("masterPassphrase must not be null or empty");
        }
        String resolvedVaultId = (vaultId == null || vaultId.trim().isEmpty()) ? DEFAULT_VAULT_ID : vaultId.trim();

        Map<String, Object> cleanSecrets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : secretsMap.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().trim().isEmpty() && entry.getValue() != null) {
                cleanSecrets.put(entry.getKey().trim(), entry.getValue());
            }
        }

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(iv);

        byte[] plaintextBytes = JsonCodec.toJsonObjectString(cleanSecrets).getBytes(StandardCharsets.UTF_8);
        byte[] derivedKeyBytes = null;
        try {
            derivedKeyBytes = deriveAesKeyBytes(masterPassphrase, salt, DEFAULT_KDF_ITERATIONS);
            SecretKey aesKey = new SecretKeySpec(derivedKeyBytes, KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(JCA_CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
            cipher.updateAAD(buildAadBytes(KDF_ALGORITHM_LABEL, DEFAULT_KDF_ITERATIONS));
            byte[] cipherBytes = cipher.doFinal(plaintextBytes);

            return new VaultEnvelope(
                    resolvedVaultId,
                    KDF_ALGORITHM_LABEL,
                    Base64.getEncoder().encodeToString(salt),
                    DEFAULT_KDF_ITERATIONS,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(cipherBytes),
                    cleanSecrets.size()
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Platform JCA failed AES-256-GCM vault encryption", e);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
            if (derivedKeyBytes != null) {
                Arrays.fill(derivedKeyBytes, (byte) 0);
            }
        }
    }

    /**
     * Derives the domain-separated automatic E2EE vault passphrase from the household authorization key so
     * Java (Android TV), Python (Setup Studio), and ES5 JavaScript (LG webOS / Samsung Tizen) derive identical
     * vault keys without reusing the raw {@code x-soartv-household-key} HTTP header verbatim as the cipher key.
     *
     * @param householdKey Non-empty household key string.
     * @return Lowercase SHA-256 hex passphrase buffer (caller should scrub with {@link #wipePassphrase}).
     * @throws IllegalArgumentException If {@code householdKey} is blank.
     */
    public static char[] deriveHouseholdVaultPassphrase(String householdKey) {
        if (householdKey == null || householdKey.trim().isEmpty()) {
            throw new IllegalArgumentException("householdKey must not be null or empty");
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((HOUSEHOLD_VAULT_KDF_DOMAIN + householdKey.trim()).getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            final char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hex[i * 2] = digits[v >>> 4];
                hex[i * 2 + 1] = digits[v & 0x0F];
            }
            Arrays.fill(digest, (byte) 0);
            return hex;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Builds the canonical per-profile vault slot ID ({@code "profile:<uuid>"}). */
    public static String profileVaultId(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) {
            throw new IllegalArgumentException("profileId must not be null or empty");
        }
        return PROFILE_VAULT_PREFIX + profileId.trim();
    }

    /**
     * Authenticates and decrypts a {@link VaultEnvelope} back into an unmodifiable {@code Map<String, String>}
     * using the supplied master passphrase.
     *
     * @param envelope         Encrypted {@link VaultEnvelope} fetched from {@code public.encrypted_vault}.
     * @param masterPassphrase User master passphrase characters.
     * @return Unmodifiable map of decrypted secret key names to values.
     * @throws GeneralSecurityException If the passphrase is wrong, KDF parameters are invalid/downgraded,
     *                                  Base64 fields are malformed, or the 128-bit GCM authentication tag fails.
     */
    public static Map<String, String> decryptMap(VaultEnvelope envelope, char[] masterPassphrase)
            throws GeneralSecurityException {
        if (envelope == null) {
            throw new GeneralSecurityException("VaultEnvelope must not be null");
        }
        if (masterPassphrase == null || masterPassphrase.length == 0) {
            throw new GeneralSecurityException("Master passphrase must not be empty");
        }
        if (!KDF_ALGORITHM_LABEL.equals(envelope.kdfAlgorithm)) {
            throw new GeneralSecurityException("Unsupported KDF algorithm: " + envelope.kdfAlgorithm);
        }
        if (envelope.kdfIterations < MIN_KDF_ITERATIONS) {
            throw new GeneralSecurityException("KDF iterations below minimum threshold: " + envelope.kdfIterations);
        }

        final byte[] salt;
        final byte[] iv;
        final byte[] cipherBytes;
        try {
            salt = Base64.getDecoder().decode(envelope.kdfSalt);
            iv = Base64.getDecoder().decode(envelope.cipherIv);
            cipherBytes = Base64.getDecoder().decode(envelope.ciphertext);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Malformed Base64 field in VaultEnvelope", e);
        }

        if (salt.length < 8) {
            throw new GeneralSecurityException("Invalid PBKDF2 salt length: " + salt.length);
        }
        if (iv.length != GCM_IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("Invalid AES-GCM IV length: expected " + GCM_IV_LENGTH_BYTES + ", got " + iv.length);
        }
        if (cipherBytes.length < (GCM_TAG_LENGTH_BITS / 8)) {
            throw new GeneralSecurityException("Ciphertext shorter than 128-bit GCM authentication tag");
        }

        byte[] derivedKeyBytes = null;
        byte[] plaintextBytes = null;
        try {
            derivedKeyBytes = deriveAesKeyBytes(masterPassphrase, salt, envelope.kdfIterations);
            SecretKey aesKey = new SecretKeySpec(derivedKeyBytes, KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(JCA_CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
            cipher.updateAAD(buildAadBytes(envelope.kdfAlgorithm, envelope.kdfIterations));
            plaintextBytes = cipher.doFinal(cipherBytes);

            String json = new String(plaintextBytes, StandardCharsets.UTF_8);
            Map<String, Object> parsed = JsonCodec.parseJsonObject(json);
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Decrypted vault payload is not valid JSON", e);
        } finally {
            if (derivedKeyBytes != null) {
                Arrays.fill(derivedKeyBytes, (byte) 0);
            }
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
        }
    }

    /**
     * Zeroes out a caller-owned passphrase character array in memory once backup/restore completes.
     *
     * @param passphrase Character buffer to scrub (no-op if null).
     */
    public static void wipePassphrase(char[] passphrase) {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
        }
    }

    private static byte[] deriveAesKeyBytes(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(JCA_KDF_ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] buildAadBytes(String kdfAlgorithm, int iterations) {
        return ("soartv-vault-v1:" + kdfAlgorithm + ":" + iterations).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Zero-dependency RFC-8259 JSON serializer/parser shared across {@link CloudVaultCrypto},
     * {@link SupabaseClient}, and {@link UserProfileManager} so DTO mapping works identically on
     * Android TV ({@code minSdk 26}) and headless desktop JVM unit tests against stub {@code android.jar}.
     */
    static final class JsonCodec {

        private JsonCodec() {
        }

        static String optString(Map<String, Object> map, String key, String fallback) {
            if (map == null || key == null || !map.containsKey(key)) return fallback;
            Object v = map.get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v);
            return s.isEmpty() && fallback != null && !fallback.isEmpty() ? fallback : s;
        }

        static String optNullableString(Map<String, Object> map, String key, String fallback) {
            if (map == null || key == null || !map.containsKey(key)) return fallback;
            Object v = map.get(key);
            return v == null ? fallback : String.valueOf(v);
        }

        static int optInt(Map<String, Object> map, String key, int fallback) {
            if (map == null || key == null || !map.containsKey(key)) return fallback;
            Object v = map.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) {
                try {
                    return Integer.parseInt(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        static long optLong(Map<String, Object> map, String key, long fallback) {
            if (map == null || key == null || !map.containsKey(key)) return fallback;
            Object v = map.get(key);
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) {
                try {
                    return Long.parseLong(((String) v).trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> optObjectMap(Map<String, Object> map, String key) {
            if (map == null || key == null) return Collections.emptyMap();
            Object v = map.get(key);
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
            return Collections.emptyMap();
        }

        static List<String> optStringList(Map<String, Object> map, String key) {
            if (map == null || key == null) return Collections.emptyList();
            Object v = map.get(key);
            if (!(v instanceof List)) return Collections.emptyList();
            List<?> raw = (List<?>) v;
            List<String> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            return out;
        }

        static boolean optBoolean(Map<String, Object> map, String key, boolean fallback) {
            if (map == null || key == null || !map.containsKey(key)) return fallback;
            Object v = map.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) {
                String s = ((String) v).trim();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
            return fallback;
        }

        static String toJsonObjectString(Map<String, Object> fields) {
            if (fields == null || fields.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder(fields.size() * 32);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (e.getKey() == null) continue;
                if (!first) sb.append(',');
                first = false;
                appendQuotedString(sb, e.getKey());
                sb.append(':');
                appendValue(sb, e.getValue());
            }
            sb.append('}');
            return sb.toString();
        }

        static String toJsonArrayString(List<Map<String, Object>> items) {
            if (items == null || items.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder(items.size() * 64);
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toJsonObjectString(items.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }

        private static void appendValue(StringBuilder sb, Object val) {
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Boolean || val instanceof Integer || val instanceof Long) {
                sb.append(val);
            } else if (val instanceof Number) {
                double d = ((Number) val).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else {
                    sb.append(val);
                }
            } else if (val instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) val;
                sb.append(toJsonObjectString(m));
            } else if (val instanceof List) {
                List<?> list = (List<?>) val;
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    appendValue(sb, list.get(i));
                }
                sb.append(']');
            } else {
                appendQuotedString(sb, String.valueOf(val));
            }
        }

        static void appendQuotedString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                        break;
                }
            }
            sb.append('"');
        }

        static Map<String, Object> parseJsonObject(String json) {
            if (json == null) return Collections.emptyMap();
            Parser p = new Parser(json);
            p.skipWs();
            if (p.eof()) return Collections.emptyMap();
            Object v = p.readValue();
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                return m;
            }
            throw new IllegalArgumentException("Expected JSON object");
        }

        static List<Map<String, Object>> parseJsonObjectArray(String json) {
            if (json == null) return Collections.emptyList();
            Parser p = new Parser(json);
            p.skipWs();
            if (p.eof()) return Collections.emptyList();
            Object v = p.readValue();
            if (!(v instanceof List)) {
                return Collections.emptyList();
            }
            List<?> raw = (List<?>) v;
            List<Map<String, Object>> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) item;
                    out.add(m);
                }
            }
            return out;
        }

        private static final class Parser {
            private final String src;
            private int pos;

            Parser(String src) {
                this.src = src;
                this.pos = 0;
            }

            boolean eof() {
                return pos >= src.length();
            }

            void skipWs() {
                while (pos < src.length()) {
                    char c = src.charAt(pos);
                    if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                        pos++;
                    } else {
                        break;
                    }
                }
            }

            Object readValue() {
                skipWs();
                if (eof()) throw new IllegalArgumentException("Unexpected end of JSON");
                char c = src.charAt(pos);
                switch (c) {
                    case '{':
                        return readObject();
                    case '[':
                        return readArray();
                    case '"':
                        return readString();
                    case 't':
                    case 'f':
                        return readBoolean();
                    case 'n':
                        return readNull();
                    default:
                        if (c == '-' || (c >= '0' && c <= '9')) {
                            return readNumber();
                        }
                        throw new IllegalArgumentException("Unexpected JSON token '" + c + "' at index " + pos);
                }
            }

            Map<String, Object> readObject() {
                pos++; // '{'
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (!eof() && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWs();
                    if (eof() || src.charAt(pos) != '"') {
                        throw new IllegalArgumentException("Expected quoted object key at index " + pos);
                    }
                    String key = readString();
                    skipWs();
                    if (eof() || src.charAt(pos) != ':') {
                        throw new IllegalArgumentException("Expected ':' at index " + pos);
                    }
                    pos++;
                    Object val = readValue();
                    map.put(key, val);
                    skipWs();
                    if (eof()) throw new IllegalArgumentException("Unterminated JSON object");
                    char sep = src.charAt(pos++);
                    if (sep == '}') break;
                    if (sep != ',') throw new IllegalArgumentException("Expected ',' or '}' at index " + (pos - 1));
                }
                return map;
            }

            List<Object> readArray() {
                pos++; // '['
                List<Object> list = new ArrayList<>();
                skipWs();
                if (!eof() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(readValue());
                    skipWs();
                    if (eof()) throw new IllegalArgumentException("Unterminated JSON array");
                    char sep = src.charAt(pos++);
                    if (sep == ']') break;
                    if (sep != ',') throw new IllegalArgumentException("Expected ',' or ']' at index " + (pos - 1));
                }
                return list;
            }

            String readString() {
                pos++; // '"'
                StringBuilder sb = new StringBuilder();
                while (pos < src.length()) {
                    char c = src.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (eof()) throw new IllegalArgumentException("Unterminated escape in JSON string");
                        char esc = src.charAt(pos++);
                        switch (esc) {
                            case '"':
                                sb.append('"');
                                break;
                            case '\\':
                                sb.append('\\');
                                break;
                            case '/':
                                sb.append('/');
                                break;
                            case 'b':
                                sb.append('\b');
                                break;
                            case 'f':
                                sb.append('\f');
                                break;
                            case 'n':
                                sb.append('\n');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case 't':
                                sb.append('\t');
                                break;
                            case 'u':
                                if (pos + 4 > src.length()) {
                                    throw new IllegalArgumentException("Truncated \\uXXXX escape");
                                }
                                int cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
                                sb.append((char) cp);
                                pos += 4;
                                break;
                            default:
                                sb.append(esc);
                                break;
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalArgumentException("Unterminated JSON string");
            }

            Boolean readBoolean() {
                if (src.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                if (src.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("Invalid boolean literal at index " + pos);
            }

            Object readNull() {
                if (src.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                throw new IllegalArgumentException("Invalid null literal at index " + pos);
            }

            Number readNumber() {
                int start = pos;
                boolean isFloat = false;
                if (src.charAt(pos) == '-') pos++;
                while (pos < src.length()) {
                    char c = src.charAt(pos);
                    if (c >= '0' && c <= '9') {
                        pos++;
                    } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                        isFloat = true;
                        pos++;
                    } else {
                        break;
                    }
                }
                String token = src.substring(start, pos);
                if (isFloat) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            }
        }
    }
}
