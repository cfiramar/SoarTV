#!/usr/bin/env bash
# Builds SoarTV.apk and SoarTV-Remote.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK (17+) and the Android SDK command-line tools (build-tools 34+, platform android-34). See README.md.
#   ./build.sh             with config.properties (your city, your TMDB key)
#   ./build.sh --generic   without it, as the APK on GitHub releases: no personal keys baked in
set -euo pipefail
cd "$(dirname "$0")"

# Serialize concurrent build.sh invocations to protect out/ directory
BUILD_LOCK=".build_lock"
LOCK_WAITED=0
while ! mkdir "$BUILD_LOCK" 2>/dev/null; do
  sleep 1
  LOCK_WAITED=$((LOCK_WAITED + 1))
  if [ "$LOCK_WAITED" -ge 180 ]; then
    rm -rf "$BUILD_LOCK" 2>/dev/null || true
    LOCK_WAITED=0
  fi
done
trap 'rm -rf "$BUILD_LOCK" 2>/dev/null || true' EXIT INT TERM

GENERIC=false
for arg in "$@"; do [ "$arg" = "--generic" ] && GENERIC=true; done

# --- Tools
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
           /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools /usr/lib/android-sdk; do
    if [ -d "$d/build-tools" ]; then SDK="$d"; break; fi
  done
fi
if command -v cygpath >/dev/null 2>&1 && [ -n "$SDK" ]; then
  SDK="$(cygpath -m "$SDK")"
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Install it and set ANDROID_HOME (see README.md)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
if [ -z "${JAVA_HOME:-}" ] && [ -d "/c/Program Files/Java" ]; then
  JAVA_HOME="$(ls -d "/c/Program Files/Java"/jdk-* 2>/dev/null | sort -V | tail -1 || true)"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || { echo "JDK not found. Install JDK 17 and set JAVA_HOME."; exit 1; }

# --- Settings: config.properties (and optional ../keys.txt / ../.env) -> Config.java
CONFIG=config.properties
KEYS_TXT=../keys.txt
DOTENV=../.env
MAIN_REPO_ROOT=""
if command -v git >/dev/null 2>&1; then
  GIT_COMMON="$(git rev-parse --git-common-dir 2>/dev/null || true)"
  if [ -n "$GIT_COMMON" ] && [ -d "$GIT_COMMON" ]; then
    MAIN_REPO_ROOT="$(cd "$GIT_COMMON/.." 2>/dev/null && pwd || true)"
  fi
fi
if $GENERIC; then
  CONFIG=/dev/null
  KEYS_TXT=/dev/null
  DOTENV=/dev/null
else
  if [ -n "$MAIN_REPO_ROOT" ] && [ ! -f "$DOTENV" ] && [ -f "$MAIN_REPO_ROOT/.env" ]; then
    DOTENV="$MAIN_REPO_ROOT/.env"
  fi
  if [ ! -f config.properties ]; then
    if [ -n "$MAIN_REPO_ROOT" ] && [ -f "$MAIN_REPO_ROOT/SoarTV-launcher/config.properties" ]; then
      cp "$MAIN_REPO_ROOT/SoarTV-launcher/config.properties" config.properties
    else
      cp config.example.properties config.properties
      echo "Created config.properties: add your TMDB key and city there (optional), then build again."
    fi
  elif [ -n "$MAIN_REPO_ROOT" ] && [ -f "$MAIN_REPO_ROOT/SoarTV-launcher/config.properties" ] \
       && [ "$(cd "$MAIN_REPO_ROOT/SoarTV-launcher" && pwd)" != "$(pwd)" ] \
       && [ -z "$(grep -iE '^[[:space:]]*tmdb_key[[:space:]]*=[[:space:]]*[^[:space:]]' config.properties 2>/dev/null || true)" ] \
       && [ -n "$(grep -iE '^[[:space:]]*tmdb_key[[:space:]]*=[[:space:]]*[^[:space:]]' "$MAIN_REPO_ROOT/SoarTV-launcher/config.properties" 2>/dev/null || true)" ]; then
    cp "$MAIN_REPO_ROOT/SoarTV-launcher/config.properties" config.properties
  fi
fi
case "$(head -c 2 "$CONFIG" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
  fffe|feff) echo "config.properties is saved as UTF-16. Save it as UTF-8 and build again." >&2; exit 1 ;;
esac
dotenv_var_for() {
  case "$1" in
    tmdb_key) printf 'TMDB_API_KEY' ;;
    omdb_key) printf 'OMDB_API_KEY' ;;
    mdblist_key) printf 'MDBLIST_API_KEY' ;;
    trakt_client_id) printf 'TRAKT_CLIENT_ID' ;;
    trakt_username) printf 'TRAKT_USERNAME' ;;
    supabase_url) printf 'SUPABASE_URL' ;;
    supabase_anon_key) printf 'SUPABASE_ANON_KEY' ;;
    aiostreams_manifest_url) printf 'AIOSTREAMS_MANIFEST_URL' ;;
    cinemeta_url) printf 'CINEMETA_URL' ;;
    gemini_api_key) printf 'GEMINI_API_KEY' ;;
    github_repo) printf 'GITHUB_REPO' ;;
    admin_emails) printf 'ADMIN_EMAILS' ;;
    gaming_pc_ip) printf 'GAMING_PC_IP' ;;
    gaming_pc_mac) printf 'GAMING_PC_MAC' ;;
    enable_gaming) printf 'ENABLE_GAMING' ;;
    enable_smarthome) printf 'ENABLE_SMARTHOME' ;;
    enable_reels) printf 'ENABLE_REELS' ;;
    tmdb_region) printf 'REGION' ;;
    currency) printf 'CURRENCY' ;;
    temperature) printf 'TEMPERATURE_UNIT' ;;
    latitude) printf 'LATITUDE' ;;
    longitude) printf 'LONGITUDE' ;;
    city) printf 'CITY' ;;
    *) printf '' ;;
  esac
}
prop() {
  local v env_var
  v="$(grep -iE "^[[:space:]]*$1[[:space:]]*=" "$CONFIG" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
  v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr -d '"\\')"
  if [ -z "$v" ] && [ -f "$KEYS_TXT" ]; then
    v="$(grep -iE "^[[:space:]]*$1[[:space:]]*=" "$KEYS_TXT" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
    v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | tr -d '"\\')"
  fi
  if [ -z "$v" ] && [ -f "$DOTENV" ]; then
    env_var="$(dotenv_var_for "$1")"
    if [ -n "$env_var" ]; then
      v="$(grep -E "^[[:space:]]*${env_var}[[:space:]]*=" "$DOTENV" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
      v="$(printf '%s' "$v" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e "s/^'//" -e "s/'$//" | tr -d '"\\')"
    fi
  fi
  printf '%s' "${v:-$2}"
}
number() {
  local v; v="$(prop "$1" "$2")"
  [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] || { echo "config.properties: $1 must be a number (got \"$v\")" >&2; exit 1; }
  printf '%s' "$v"
}
if $GENERIC; then
  LAT=""; LON=""; REGION=""; CURRENCY=""
  DEFAULT_GITHUB_REPO="${SOARTV_RELEASE_REPO:-}"
else
  LAT="$(number latitude 40.7128)"
  LON="$(number longitude -74.0060)"
  REGION="$(prop tmdb_region US)"
  CURRENCY="$(prop currency USD)"
  DEFAULT_GITHUB_REPO="${SOARTV_RELEASE_REPO:-}"
  if [ -z "$DEFAULT_GITHUB_REPO" ] && command -v git >/dev/null 2>&1; then
    REMOTE_URL="$(git remote get-url origin 2>/dev/null || true)"
    DEFAULT_GITHUB_REPO="$(printf '%s' "$REMOTE_URL" | sed -nE 's#^.*github\.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(\.git)?$#\1#p')"
  fi
fi
ASSIST=false; [ "$(prop disable_assistant false)" = "true" ] && ASSIST=true
ANIMATION=false; [ "$(prop hide_animation false)" = "true" ] && ANIMATION=true
SAVER=true; [ "$(prop screensaver true)" = "false" ] && SAVER=false
STREMIO=false; [ "$(prop open_in_stremio false)" = "true" ] && STREMIO=true
if ! $GENERIC && [ -f "$DOTENV" ] \
   && [ -n "$(grep -E '^[[:space:]]*TMDB_API_KEY[[:space:]]*=[[:space:]]*[^[:space:]]' "$DOTENV" 2>/dev/null || true)" ] \
   && [ -z "$(prop tmdb_key '')" ]; then
  echo "build.sh: Refusing to build a personalized (non---generic) APK with an empty tmdb_key while $DOTENV has TMDB_API_KEY." >&2
  exit 1
fi

# SEC-01: Sensitive passwords, PATs, session tokens, and Smart-Home secrets NEVER go into generated Config.java / classes.dex.
# They are stored encrypted at rest via AndroidKeyStore in Secrets.java (`python3 tools/send_key.py <TV_IP> --sync-config`).
rm -rf out && mkdir -p out/config/io/soartv out/gen out/classes
cat > out/config/io/soartv/Config.java <<EOF
package io.soartv;

/** Generated by build.sh from config.properties: edit that file, not this one. */
final class Config {
    private Config() {}

    static final String TMDB_KEY = "$(prop tmdb_key '')";
    static final String OMDB_KEY = "$(prop omdb_key '')";
    static final String MDBLIST_KEY = "$(prop mdblist_key '')";
    static final String TRAKT_CLIENT_ID = "$(prop trakt_client_id '')";
    static final String TRAKT_USERNAME = "$(prop trakt_username '')";
    static final String STREMIO_AUTH_KEY = "";
    static final String STREMIO_EMAIL = "$(prop stremio_email '')";
    static final String STREMIO_PASSWORD = "";
    static final String AIOSTREAMS_MANIFEST_URL = "$(prop aiostreams_manifest_url '')";
    static final String CINEMETA_URL = "$(prop cinemeta_url 'https://v3-cinemeta.strem.io')";
    static final String SUPABASE_URL = "$(prop supabase_url '')";
    static final String SUPABASE_ANON_KEY = "$(prop supabase_anon_key '')";
    static final String GITHUB_REPO = "$(if $GENERIC; then printf '%s' "$DEFAULT_GITHUB_REPO"; else prop github_repo "$DEFAULT_GITHUB_REPO"; fi)";
    static final String GITHUB_TOKEN = "";
    static final String GEMINI_API_KEY = "$(prop gemini_api_key '')";
    static final String SENSIBO_API_KEY = "";
    static final String SENSIBO_DEVICE_ID = "$(prop sensibo_device_id '')";
    static final String WIZ_LIGHT_IPS = "$(prop wiz_light_ips '')";
    static final String SWITCHER_DEVICE_IP = "$(prop switcher_device_ip '')";
    static final String SWITCHER_DEVICE_ID = "$(prop switcher_device_id '')";
    static final String TUYA_DEVICE_ID = "$(prop tuya_device_id '')";
    static final String TUYA_ACCESS_ID = "$(prop tuya_access_id '')";
    static final String TUYA_ACCESS_SECRET = "";
    static final String GAMING_PC_MAC = "$(prop gaming_pc_mac '')";
    static final String GAMING_PC_IP = "$(prop gaming_pc_ip '')";
    static final String GAMING_PC_HDMI = "$(prop gaming_pc_hdmi 'HDMI 3')";
    static final String HDMI_PORT_ROLES = "$(prop hdmi_port_roles '')";
    static final String ADMIN_EMAILS = "$(prop admin_emails '')";
    static final int REMOTE_SERVER_PORT = $(number remote_server_port 8484);
    static final String TMDB_REGION = "$REGION";
    static final String LATITUDE = "$LAT";
    static final String LONGITUDE = "$LON";
    static final String CITY = "$(prop city '')";
    static final String LANGUAGE = "$(prop language auto)";
    static final String CURRENCY = "$CURRENCY";
    static final String TEMPERATURE = "$(prop temperature auto)";
    static final String HIDE_SERVICES = "$(prop hide_services '')";
    static final String THEME = "$(prop theme auto)";
    static final boolean DISABLE_ASSISTANT = $ASSIST;
    static final boolean HIDE_ANIMATION = $ANIMATION;
    static final boolean SCREENSAVER = $SAVER;
    static final boolean GENERIC = $GENERIC;
    static final boolean OPEN_IN_STREMIO = $STREMIO;
    static final String ENABLE_GAMING = "$(if $GENERIC; then printf 'auto'; else prop enable_gaming 'auto'; fi)";
    static final String ENABLE_SMARTHOME = "$(if $GENERIC; then printf 'auto'; else prop enable_smarthome 'auto'; fi)";
    static final String ENABLE_REELS = "false";
}
EOF

# Strict HTTPS network security policy (local Sunshine/Laptop Gaming HTTP connections use explicit socket guards)
mkdir -p out/res/xml
cat > out/res/xml/network_security_config.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
EOF

# --- Build
CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
d8() {
  if [ -n "$BAT" ]; then "$JAVA" -Xmx1024M -Xss1m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 "$@"
  else "$BT/d8" "$@"; fi
}
apksigner() {
  if [ -n "$BAT" ]; then "$JAVA" -jar "$BT/lib/apksigner.jar" "$@"
  else "$BT/apksigner" "$@"; fi
}
sign_apk() {
  local in_apk="$1" out_apk="$2"
  if [ -n "${SOARTV_KEYSTORE:-}" ]; then
    [ -n "${SOARTV_KEYSTORE_PASS:-}" ] || { echo "SOARTV_KEYSTORE_PASS is empty"; exit 1; }
    apksigner sign --ks "$SOARTV_KEYSTORE" --ks-pass env:SOARTV_KEYSTORE_PASS --key-pass env:SOARTV_KEYSTORE_PASS \
      --out "$out_apk" "$in_apk"
  else
    if [ ! -f soartv.keystore ]; then
      "$KEYTOOL" -genkeypair -keystore soartv.keystore -alias soartv -keyalg RSA -keysize 2048 -validity 20000 \
        -storepass soartvkeystore -keypass soartvkeystore -dname "CN=SoarTV" >/dev/null 2>&1
    fi
    apksigner sign --ks soartv.keystore --ks-pass pass:soartvkeystore --key-pass pass:soartvkeystore --out "$out_apk" "$in_apk"
  fi
}

case "$(uname -s 2>/dev/null)" in
  MINGW*|MSYS*|CYGWIN*)
    cat > out/FixAssets.java <<'EOF'
import java.nio.file.*;
import java.util.zip.*;

class FixAssets {
    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Path tmp = apk.resolveSibling(apk.getFileName() + ".fixed");
        int renamed = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                String name = e.getName().replace('\\', '/');
                if (!name.equals(e.getName())) renamed++;
                ZipEntry n = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) {
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
        if (renamed > 0) Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        else Files.delete(tmp);
        System.out.println("FixAssets: renamed " + renamed + " asset paths in " + apk.getFileName());
    }
}
EOF
    ;;
esac

# --- Bundle Companion Remote TypeScript -> SoarTV-remote/public/app.bundle.js
if [ -f ../SoarTV-remote/scripts/bundle.mjs ] && command -v node >/dev/null 2>&1; then
  node ../SoarTV-remote/scripts/bundle.mjs 2>/dev/null || true
fi

rm -f ../SoarTV-remote/public/SoarTV-Remote.apk

# --- Build Standalone Native Android Companion Remote APK (SoarTV-Remote.apk)
if [ -d ../SoarTV-remote/android ] && [ -d ../SoarTV-remote/public ]; then
  mkdir -p out/remote/gen out/remote/classes out/remote/assets
  cp -f ../SoarTV-remote/public/index.html out/remote/assets/
  [ -f ../SoarTV-remote/public/app.bundle.js ] && cp -f ../SoarTV-remote/public/app.bundle.js out/remote/assets/
  [ -f ../SoarTV-remote/public/manifest.json ] && cp -f ../SoarTV-remote/public/manifest.json out/remote/assets/
  cp -f ../SoarTV-remote/public/*.png out/remote/assets/ 2>/dev/null || true
  "$BT/aapt2" compile --dir ../SoarTV-remote/android/res -o out/remote/res.zip
  "$BT/aapt2" link -o out/remote/base.apk -I "$AJ" --manifest ../SoarTV-remote/android/AndroidManifest.xml \
    --java out/remote/gen out/remote/res.zip --min-sdk-version 24 --target-sdk-version 34 -A out/remote/assets
  [ -f out/FixAssets.java ] && "$JAVA" out/FixAssets.java out/remote/base.apk
  "$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" -classpath "$AJ" \
    -encoding UTF-8 -d out/remote/classes $(find ../SoarTV-remote/android/src out/remote/gen -name '*.java')
  d8 --release --min-api 24 --lib "$AJ" --output out/remote $(find out/remote/classes -name '*.class')
  if command -v zip >/dev/null; then (cd out/remote && zip -qj base.apk classes.dex)
  else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f out/remote/base.apk -C out/remote classes.dex; fi
  "$BT/zipalign" -f -p 4 out/remote/base.apk out/remote/aligned.apk
  sign_apk out/remote/aligned.apk SoarTV-Remote.apk
  echo "Built SoarTV-Remote.apk ($(du -h SoarTV-Remote.apk | cut -f1))."
fi

if [ -d ../SoarTV-remote/public ]; then
  mkdir -p app/assets/remote
  cp -rf ../SoarTV-remote/public/* app/assets/remote/
  if [ -f SoarTV-Remote.apk ]; then
    cp -f SoarTV-Remote.apk app/assets/remote/SoarTV-Remote.apk
  fi
fi

"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" compile --dir out/res -o out/res-gen.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip out/res-gen.zip \
  --min-sdk-version 26 --target-sdk-version 33 -A app/assets -0 ttf -0 apk
[ -f out/FixAssets.java ] && "$JAVA" out/FixAssets.java out/base.apk
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -encoding UTF-8 -d out/classes $(find app/src out/gen out/config -name '*.java')
d8 --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
if command -v zip >/dev/null; then (cd out && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f out/base.apk -C out classes.dex; fi
if [ -d app/jniLibs ]; then
  cat > out/PackNativeLibs.java <<'EOF'
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

class PackNativeLibs {
    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Path jniRoot = Paths.get(args[1]);
        List<Path> soFiles = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(jniRoot)) {
            s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".so")).sorted().forEach(soFiles::add);
        }
        if (soFiles.isEmpty()) return;
        Path tmp = apk.resolveSibling(apk.getFileName() + ".withjni");
        Set<String> seen = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(apk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                String name = e.getName().replace((char) 92, '/');
                if (!seen.add(name)) continue;
                ZipEntry n = new ZipEntry(name);
                if (e.getMethod() == ZipEntry.STORED) {
                    n.setMethod(ZipEntry.STORED);
                    n.setSize(data.length);
                    n.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    n.setCrc(crc.getValue());
                }
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
            for (Path so : soFiles) {
                String rel = jniRoot.relativize(so).toString().replace(File.separatorChar, '/');
                String entryName = "lib/" + rel;
                if (!seen.add(entryName)) continue;
                byte[] data = Files.readAllBytes(so);
                ZipEntry n = new ZipEntry(entryName);
                n.setMethod(ZipEntry.STORED);
                n.setSize(data.length);
                n.setCompressedSize(data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                n.setCrc(crc.getValue());
                out.putNextEntry(n);
                out.write(data);
                out.closeEntry();
            }
        }
        Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("PackNativeLibs: embedded " + soFiles.size() + " native .so library(ies) into " + apk.getFileName());
    }
}
EOF
  "$JAVA" out/PackNativeLibs.java out/base.apk app/jniLibs
fi
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

# --- Sign with your own key (created once; updates need the same key, so keep soartv.keystore).
sign_apk out/aligned.apk SoarTV.apk
if [ -d tests ] && [ -n "$(find tests -name '*.java' 2>/dev/null)" ]; then
  MSYS2_ARG_CONV_EXCL="*" "$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ$CPSEP$BT/core-lambda-stubs.jar" \
    -classpath "out/classes$CPSEP$AJ" -encoding UTF-8 -d out/classes $(find tests -name '*.java')
  for suite in \
    SoarTvCoreTests \
    NativeGameStreamingTests \
    TrackBSupabaseVaultTests \
    TrackCRemoteAuthSecretsTests \
    Stage2ModularizationAndBridgeTests \
    ScreensaverFollowUp5PointTests \
    ScreensaverOverhaulE2ETests \
    AdversarialCorrectnessM1Challenge \
    AdversarialM11ResolutionAndScrimsSuite \
    AdversarialM22Suite \
    AdversarialM2Recheck2Suite \
    AdversarialM3LifecycleSuite \
    AdversarialM3SettingsAndPrefsSuite \
    AdversarialM3ScreensaverDeckSuite \
    AdversarialM4TimezoneSuite \
    AdversarialM4MetadataSuite \
    AdversarialM5VisualSuite \
    AdversarialM5CatalogSuite \
    SettingsRedesignTests \
    FeatureTabsTest \
    CloudSyncV2Tests \
    ReelsFeatureTests; do
    if [ -f "tests/${suite}.java" ]; then
      MSYS2_ARG_CONV_EXCL="*" "$JAVA" -cp "out/classes$CPSEP$AJ" "io.soartv.${suite}"
    fi
  done
fi
rm -rf out 2>/dev/null || true
echo "Built SoarTV.apk ($(du -h SoarTV.apk | cut -f1)). Install: adb install -r SoarTV.apk"
