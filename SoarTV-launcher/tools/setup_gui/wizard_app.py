#!/usr/bin/env python3
"""
SoarTV Liquid Glass Desktop Setup Studio (`wizard_app.py`).

Why:
  Gives non-technical users a friendly, zero-dependency visual setup assistant matching
  SoarTV's Liquid Glass design language — generating tailored AI-agent prompts, guiding
  free API key creation with live validation, writing git-ignored `config.properties`
  and `.env` safely, and answering common TV setup & troubleshooting questions.

What:
  Encapsulates configuration persistence (`SetupConfigStore`), local environment & ADB
  diagnostics (`EnvironmentInspector`), tailored prompt generation (`AgentPromptBuilder`),
  and a loopback-only CSRF-protected HTTP server (`create_setup_http_server`).
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SECRET_CONFIG_KEYS = frozenset(
    {
        "tmdb_key",
        "omdb_key",
        "mdblist_key",
        "gemini_api_key",
        "trakt_client_id",
        "supabase_anon_key",
        "household_key",
        "sensibo_api_key",
        "tuya_access_secret",
        "github_token",
        "stremio_auth_key",
    }
)

CONFIG_TO_DOTENV_MAP: Dict[str, str] = {
    "tmdb_key": "TMDB_API_KEY",
    "omdb_key": "OMDB_API_KEY",
    "mdblist_key": "MDBLIST_API_KEY",
    "gemini_api_key": "GEMINI_API_KEY",
    "trakt_client_id": "TRAKT_CLIENT_ID",
    "trakt_username": "TRAKT_USERNAME",
    "supabase_url": "SUPABASE_URL",
    "supabase_anon_key": "SUPABASE_ANON_KEY",
    "household_key": "HOUSEHOLD_KEY",
    "admin_emails": "ADMIN_EMAILS",
    "sensibo_api_key": "SENSIBO_API_KEY",
    "wiz_light_ips": "WIZ_LIGHT_IPS",
    "tuya_access_id": "TUYA_ACCESS_ID",
    "tuya_access_secret": "TUYA_ACCESS_SECRET",
    "tuya_device_id": "TUYA_DEVICE_ID",
    "switcher_device_ip": "SWITCHER_DEVICE_IP",
    "switcher_device_id": "SWITCHER_DEVICE_ID",
    "gaming_pc_ip": "GAMING_PC_IP",
    "gaming_pc_mac": "GAMING_PC_MAC",
    "enable_gaming": "ENABLE_GAMING",
    "enable_smarthome": "ENABLE_SMARTHOME",
    "enable_reels": "ENABLE_REELS",
    "custom_smarthome_brands": "CUSTOM_SMARTHOME_BRANDS",
    "city": "CITY",
    "latitude": "LATITUDE",
    "longitude": "LONGITUDE",
    "tmdb_region": "REGION",
    "temperature": "TEMPERATURE_UNIT",
    "tv_ip": "TV_IP",
    "games_dirs": "SOARTV_GAMES_DIRS",
    "non_tv_monitors": "SOARTV_NON_TV_MONITORS",
}


class SetupConfigStore:
    """Manages safe UTF-8 reading and updating of git-ignored `config.properties` and `.env`."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = repo_root.resolve()
        self.launcher_dir = self.repo_root / "SoarTV-launcher"
        self.config_path = self.launcher_dir / "config.properties"
        self.config_example = self.launcher_dir / "config.example.properties"
        self.handoff_path = self.launcher_dir / ".setup_handoff.json"
        self.dotenv_path = self.repo_root / ".env"
        self.dotenv_example = self.repo_root / ".env.example"

    def ensure_files_exist(self) -> None:
        """Creates `config.properties` and `.env` from example templates if missing."""
        if not self.config_path.exists() and self.config_example.exists():
            shutil.copyfile(self.config_example, self.config_path)
        elif not self.config_path.exists():
            self.config_path.write_text("# SoarTV local settings\n", encoding="utf-8")

        if not self.dotenv_path.exists() and self.dotenv_example.exists():
            shutil.copyfile(self.dotenv_example, self.dotenv_path)
        elif not self.dotenv_path.exists():
            self.dotenv_path.write_text("# SoarTV local environment\n", encoding="utf-8")

    @staticmethod
    def _parse_kv(path: Path) -> Dict[str, str]:
        result: Dict[str, str] = {}
        if not path.is_file():
            return result
        for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            result[k.strip()] = v.strip().strip('"').strip("'")
        return result

    @staticmethod
    def _upsert_kv_file(path: Path, updates: Dict[str, str]) -> None:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.is_file() else []
        remaining = dict(updates)
        out_lines: List[str] = []
        for line in lines:
            m = re.match(r"^(\s*)([A-Za-z0-9_]+)(\s*=\s*)(.*)$", line)
            if m and m.group(2) in remaining:
                key = m.group(2)
                out_lines.append(f"{m.group(1)}{key}={remaining.pop(key)}")
            else:
                out_lines.append(line)
        for key, val in remaining.items():
            out_lines.append(f"{key}={val}")
        path.write_text("\n".join(out_lines) + "\n", encoding="utf-8", newline="\n")

    @staticmethod
    def slugify_brand(name: str) -> str:
        """Converts a user-entered smart-home company name into a safe config key slug."""
        slug = re.sub(r"[^a-z0-9]+", "_", name.strip().lower()).strip("_")
        return slug[:40] or "custom_device"

    def load_masked_state(self) -> Dict[str, Any]:
        """Returns non-secret config values and boolean presence flags for secret keys (never raw secrets)."""
        cfg = self._parse_kv(self.config_path)
        env = self._parse_kv(self.dotenv_path)

        def get_val(cfg_key: str, default: str = "") -> str:
            if cfg.get(cfg_key):
                return cfg[cfg_key]
            env_key = CONFIG_TO_DOTENV_MAP.get(cfg_key, "")
            if env_key and env.get(env_key):
                return env[env_key]
            return default

        configured_keys = {k: bool(get_val(k)) for k in SECRET_CONFIG_KEYS}
        raw_brands = get_val("custom_smarthome_brands", "")
        custom_smarthome: List[Dict[str, Any]] = []
        for raw_name in [b.strip() for b in raw_brands.split(",") if b.strip()]:
            slug = self.slugify_brand(raw_name)
            cfg_key = f"custom_smarthome_key_{slug}"
            env_key = cfg_key.upper()
            has_key = bool(cfg.get(cfg_key) or env.get(env_key))
            custom_smarthome.append({"brand": raw_name, "slug": slug, "has_key": has_key})

        values = {
            "tv_ip": env.get("TV_IP", ""),
            "city": get_val("city", ""),
            "latitude": get_val("latitude", ""),
            "longitude": get_val("longitude", ""),
            "tmdb_region": get_val("tmdb_region", "US"),
            "temperature": get_val("temperature", "auto"),
            "enable_gaming": get_val("enable_gaming", "auto"),
            "enable_smarthome": get_val("enable_smarthome", "auto"),
            "enable_reels": get_val("enable_reels", "false"),
            "trakt_username": get_val("trakt_username", ""),
            "stremio_email": cfg.get("stremio_email", ""),
            "supabase_url": get_val("supabase_url", ""),
            "admin_emails": get_val("admin_emails", ""),
            "wiz_light_ips": get_val("wiz_light_ips", ""),
            "sensibo_device_id": cfg.get("sensibo_device_id", ""),
            "tuya_access_id": get_val("tuya_access_id", ""),
            "tuya_device_id": get_val("tuya_device_id", ""),
            "switcher_device_ip": get_val("switcher_device_ip", ""),
            "gaming_pc_hdmi": cfg.get("gaming_pc_hdmi", "HDMI 3"),
            "games_dirs": env.get("SOARTV_GAMES_DIRS", ""),
            "non_tv_monitors": env.get("SOARTV_NON_TV_MONITORS", ""),
        }
        return {
            "values": values,
            "configured_keys": configured_keys,
            "custom_smarthome": custom_smarthome,
        }

    def save_settings(self, payload: Dict[str, Any]) -> None:
        """Writes non-empty secrets, custom smart-home brands, and settings into `config.properties` and `.env`."""
        self.ensure_files_exist()
        cfg_updates: Dict[str, str] = {}
        env_updates: Dict[str, str] = {}

        dotenv_only = {"tv_ip", "games_dirs", "non_tv_monitors"}

        if isinstance(payload.get("custom_smarthome"), list):
            brand_names: List[str] = []
            seen_slugs = set()
            for entry in payload["custom_smarthome"]:
                if not isinstance(entry, dict):
                    continue
                brand = str(entry.get("brand", "")).strip().replace("\r", "").replace("\n", "").replace(",", " ")
                if not brand:
                    continue
                slug = self.slugify_brand(brand)
                if slug not in seen_slugs:
                    seen_slugs.add(slug)
                    brand_names.append(brand)
                api_key = str(entry.get("api_key", "")).strip().replace("\r", "").replace("\n", "")
                if api_key:
                    cfg_key = f"custom_smarthome_key_{slug}"
                    cfg_updates[cfg_key] = api_key
                    env_updates[cfg_key.upper()] = api_key
            brands_csv = ", ".join(brand_names)
            cfg_updates["custom_smarthome_brands"] = brands_csv
            env_updates["CUSTOM_SMARTHOME_BRANDS"] = brands_csv

        for raw_key, raw_val in payload.items():
            if raw_val is None or raw_key == "custom_smarthome":
                continue
            val = str(raw_val).strip().replace("\r", "").replace("\n", "")
            # Never overwrite an existing secret with an empty string unless explicitly requested
            if raw_key in SECRET_CONFIG_KEYS and val == "":
                continue
            if raw_key not in dotenv_only:
                cfg_updates[raw_key] = val
            env_name = CONFIG_TO_DOTENV_MAP.get(raw_key)
            if env_name:
                env_updates[env_name] = val

        if cfg_updates:
            self._upsert_kv_file(self.config_path, cfg_updates)
        if env_updates:
            self._upsert_kv_file(self.dotenv_path, env_updates)

    def write_handoff_summary(self, tv_platform: str = "android_tv") -> Path:
        """Writes a non-secret JSON summary (`SoarTV-launcher/.setup_handoff.json`) for the AI agent to read."""
        self.ensure_files_exist()
        masked = self.load_masked_state()
        handoff = {
            "saved_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "tv_platform": tv_platform,
            "values": masked["values"],
            "configured_keys": masked["configured_keys"],
            "custom_smarthome": masked["custom_smarthome"],
            "secrets_location": {
                "config_properties": "SoarTV-launcher/config.properties",
                "dotenv": ".env",
            },
            "agent_notes": [
                "Never print or echo raw secret values from config.properties or .env in chat.",
                "If custom_smarthome is non-empty, inspect SmartHomeManager.java and add modular support for those brands.",
                "If values.enable_gaming == 'true', run laptop_gaming_agent.py --list-monitors automatically to populate SOARTV_NON_TV_MONITORS.",
            ],
        }
        self.handoff_path.write_text(
            json.dumps(handoff, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        return self.handoff_path


class AgentPromptBuilder:
    """Synthesizes a concise, copy-pasteable AI-agent setup prompt from the user's preferences."""

    PLATFORM_LABELS = {
        "android_tv": "Android TV / Google TV",
        "fire_tv": "Amazon Fire TV",
        "lg_webos": "LG webOS TV",
        "samsung_tizen": "Samsung Tizen TV",
    }

    @classmethod
    def build_prompt(
        cls,
        tv_platform: str,
        tv_ip: str,
        city: str,
        enable_smarthome: bool,
        smarthome_devices: List[str],
        enable_gaming: bool,
        games_dirs: str,
        custom_smarthome: Optional[List[Dict[str, Any]]] = None,
        onboarding_mode: str = "add_tv",
        replace_tv_id: str = "",
        enable_reels: bool = False,
    ) -> str:
        """Builds a clear, human-readable instruction prompt for Claude Code, Antigravity, Cursor, or Codex."""
        plat = cls.PLATFORM_LABELS.get(tv_platform, "Smart TV")
        ip_part = f" at IP `{tv_ip.strip()}`" if tv_ip.strip() else " (ask me for my TV's Wi-Fi IP)"
        city_part = f" My city is `{city.strip()}`." if city.strip() else ""
        cloud_line = ""
        if onboarding_mode == "replace_tv" and replace_tv_id.strip():
            cloud_line = (
                f"\n- Multi-TV Cloud Onboarding: REPLACE EXISTING TV (`{replace_tv_id.strip()}`) — "
                "seed household secrets via `send_key.py --seed-cloud`, then run "
                f"`send_key.py <TV_IP> --link-tv --replace-tv {replace_tv_id.strip()}` so portable per-TV settings migrate cleanly across brands."
            )
        elif onboarding_mode == "add_tv":
            cloud_line = (
                "\n- Multi-TV Cloud Onboarding: Link this TV via the 3-key cloud bootstrap "
                "(`send_key.py --seed-cloud` + `send_key.py <TV_IP> --link-tv`) so household keys and profiles hydrate automatically from Supabase."
            )

        custom_entries = [
            entry for entry in (custom_smarthome or [])
            if isinstance(entry, dict) and str(entry.get("brand", "")).strip()
        ]
        if enable_smarthome or custom_entries:
            if smarthome_devices:
                devs = ", ".join(smarthome_devices)
                sh_line = f"- Smart Home Tab: ENABLE — enable Smart Home ({devs}); SoarTV will automatically display discovered/configured devices."
            else:
                sh_line = "- Smart Home Tab: ENABLE — enable Smart Home and guide me if any device API key is missing (SoarTV automatically shows only found/configured devices)."
            if custom_entries:
                parts: List[str] = []
                for entry in custom_entries:
                    brand = str(entry.get("brand", "")).strip()
                    slug = SetupConfigStore.slugify_brand(brand)
                    has_key = bool(entry.get("has_key") or str(entry.get("api_key", "")).strip())
                    if has_key:
                        parts.append(f"`{brand}` (API key saved locally as `custom_smarthome_key_{slug}`)")
                    else:
                        parts.append(f"`{brand}` (no key saved yet)")
                sh_line += (
                    f"\n- Custom Smart-Home Devices to Support: {', '.join(parts)}. "
                    "Please inspect `SmartHomeManager.java` and implement modular support for these brands before building (never print secret values in chat)."
                )
        else:
            sh_line = "- Smart Home Tab: DISABLE — I do not need Smart Home controls right now, so hide the Smart Home tab (`enable_smarthome=false`)."

        if enable_gaming:
            folders = f" (standalone game folders: `{games_dirs.strip()}`)" if games_dirs.strip() else ""
            gm_line = (
                f"- PC Gaming Tab: ENABLE — set up Windows PC Game Streaming{folders}, "
                "automatically inspect my connected monitors via `laptop_gaming_agent.py --list-monitors` to configure `SOARTV_NON_TV_MONITORS`, "
                "and walk me through Sunshine pairing."
            )
        else:
            gm_line = "- PC Gaming Tab: DISABLE — I will not stream PC games to this TV right now, so hide the Gaming tab (`enable_gaming=false`)."

        if enable_reels:
            reels_line = (
                "\n- Reels & TikTok Tab: ENABLE (`enable_reels=true`) — guide me through creating dedicated free TV Instagram/TikTok accounts, "
                "adding them to our couple or 3+ family group chats, signing in once on the TV, and naming each family member."
            )
        else:
            reels_line = ""

        return (
            f"Read `AGENTS.md` and `docs/agents/install-playbook.md` and help me install and configure SoarTV on my {plat}{ip_part}.{city_part}\n"
            f"{sh_line}\n"
            f"{gm_line}{cloud_line}{reels_line}\n"
            "First save an immutable local backup of my TV's initial native state (`python3 SoarTV-launcher/tools/tv_state_backup.py <TV_IP> --backup`) before making any changes, "
            "check any keys I already saved in `SoarTV-launcher/config.properties` / `.env` (never print secret values in chat), "
            "guide me step-by-step for anything missing, verify my TV brand compatibility safely, build, and install SoarTV."
        )


class EnvironmentInspector:
    """Performs read-only local toolchain checks, live key/city helpers, and bounded ADB probes."""

    @staticmethod
    def check_local_tools() -> Dict[str, Any]:
        """Checks whether JDK 17+, Android SDK, ADB, Node.js, and Python are installed."""
        sdk_candidates = [
            os.environ.get("ANDROID_HOME", ""),
            os.environ.get("ANDROID_SDK_ROOT", ""),
            str(Path.home() / "AppData/Local/Android/Sdk"),
            str(Path.home() / "Library/Android/sdk"),
            str(Path.home() / "Android/Sdk"),
        ]
        sdk_found = next((p for p in sdk_candidates if p and (Path(p) / "build-tools").is_dir()), "")
        return {
            "python": sys.version.split()[0],
            "java_installed": bool(shutil.which("javac") or Path(r"C:\Program Files\Java").is_dir()),
            "android_sdk": bool(sdk_found),
            "adb_installed": bool(shutil.which("adb") or (sdk_found and (Path(sdk_found) / "platform-tools").is_dir())),
            "node_installed": bool(shutil.which("node")),
        }

    @staticmethod
    def generate_household_credentials(admin_email: str = "") -> Dict[str, str]:
        """Generates a random 256-bit `household_key` and its ready-to-run Supabase SHA-256 SQL snippet."""
        key = secrets.token_hex(32)
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
        clean_email = admin_email.strip().lower() or "you@gmail.com"
        sql = (
            "UPDATE private.household_auth_config\n"
            f"   SET household_key_sha256 = '{digest}',\n"
            f"       allowed_emails = ARRAY['{clean_email}'],\n"
            "       updated_at = now()\n"
            " WHERE id = 1;"
        )
        return {"household_key": key, "sha256": digest, "sql": sql}

    @staticmethod
    def validate_tmdb_key(raw_key: str) -> Tuple[bool, str]:
        """Validates a TMDB v3 API key or v4 Read Access Token against `api.tmdb.org`."""
        clean = raw_key.strip()
        if not clean:
            return False, "Please paste your TMDB API key first."
        url = "https://api.tmdb.org/3/configuration"
        headers = {"Accept": "application/json"}
        if len(clean) > 60 and "." in clean:
            headers["Authorization"] = f"Bearer {clean}"
        else:
            url = f"{url}?api_key={urllib.parse.quote(clean)}"
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=6) as resp:
                if resp.status == 200:
                    return True, "TMDB API key verified successfully."
        except urllib.error.HTTPError as exc:
            if exc.code == 401:
                return False, "TMDB rejected this key (HTTP 401). Double-check the key from themoviedb.org/settings/api."
            return False, f"TMDB returned HTTP {exc.code}."
        except Exception as exc:
            return False, f"Could not reach api.tmdb.org: {exc}"
        return False, "Unexpected response from TMDB."

    @staticmethod
    def lookup_city(city_query: str) -> Dict[str, Any]:
        """Resolves city name to latitude, longitude, and ISO country code via Open-Meteo geocoding."""
        q = city_query.strip()
        if not q:
            return {"ok": False, "error": "Enter a city name first."}
        url = f"https://geocoding-api.open-meteo.com/v1/search?name={urllib.parse.quote(q)}&count=1&language=en&format=json"
        try:
            with urllib.request.urlopen(url, timeout=6) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            results = payload.get("results") or []
            if not results:
                return {"ok": False, "error": f"No city found matching '{q}'."}
            top = results[0]
            return {
                "ok": True,
                "city": top.get("name", q),
                "latitude": str(round(float(top.get("latitude", 0.0)), 4)),
                "longitude": str(round(float(top.get("longitude", 0.0)), 4)),
                "country_code": str(top.get("country_code", "US")).upper(),
            }
        except Exception as exc:
            return {"ok": False, "error": f"City lookup failed: {exc}"}

    @staticmethod
    def browse_folder_dialog(title: str = "Select a Game Folder for SoarTV") -> Dict[str, Any]:
        """Opens the native OS folder chooser (Windows Explorer, macOS Finder, or Linux dialog) cleanly."""
        safe_title = title.replace('"', "").replace("'", "")
        tk_script = (
            "import tkinter as tk\n"
            "from tkinter import filedialog\n"
            "root = tk.Tk()\n"
            "root.withdraw()\n"
            "try:\n"
            "    root.attributes('-topmost', True)\n"
            "except Exception:\n"
            "    pass\n"
            f"path = filedialog.askdirectory(title={safe_title!r})\n"
            "root.destroy()\n"
            "print(path or '')\n"
        )

        # macOS: native Finder chooser first
        if sys.platform == "darwin" and shutil.which("osascript"):
            try:
                proc = subprocess.run(
                    [
                        "osascript",
                        "-e",
                        f'POSIX path of (choose folder with prompt "{safe_title}")',
                    ],
                    capture_output=True,
                    text=True,
                    timeout=120,
                    check=False,
                )
                chosen = proc.stdout.strip().rstrip("/")
                if proc.returncode == 0 and chosen:
                    return {"ok": True, "path": chosen}
                if proc.returncode != 0 and "User canceled" in (proc.stderr or ""):
                    return {"ok": False, "cancelled": True}
            except Exception:
                pass

        # Linux: native GTK/KDE chooser first
        if sys.platform.startswith("linux"):
            for cmd in (
                ["zenity", "--file-selection", "--directory", f"--title={safe_title}"],
                ["kdialog", "--getexistingdirectory", str(Path.home()), "--title", safe_title],
            ):
                if shutil.which(cmd[0]):
                    try:
                        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120, check=False)
                        chosen = proc.stdout.strip()
                        if proc.returncode == 0 and chosen:
                            return {"ok": True, "path": chosen}
                        return {"ok": False, "cancelled": True}
                    except Exception:
                        pass

        # Cross-platform standard-library Tkinter folder dialog (runs in child process for main-thread safety)
        try:
            proc = subprocess.run(
                [sys.executable, "-c", tk_script],
                capture_output=True,
                text=True,
                timeout=120,
                check=False,
            )
            if proc.returncode == 0:
                chosen = proc.stdout.strip()
                if chosen:
                    return {"ok": True, "path": os.path.normpath(chosen)}
                return {"ok": False, "cancelled": True}
        except Exception:
            pass

        # Windows PowerShell WinForms fallback when tkinter is absent
        if sys.platform == "win32":
            ps_cmd = (
                "Add-Type -AssemblyName System.Windows.Forms; "
                "$d = New-Object System.Windows.Forms.FolderBrowserDialog; "
                f"$d.Description = '{safe_title}'; "
                "$d.UseDescriptionForTitle = $true; "
                "if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $d.SelectedPath }"
            )
            try:
                proc = subprocess.run(
                    ["powershell", "-NoProfile", "-STA", "-Command", ps_cmd],
                    capture_output=True,
                    text=True,
                    timeout=120,
                    check=False,
                )
                chosen = proc.stdout.strip()
                if chosen:
                    return {"ok": True, "path": os.path.normpath(chosen)}
                return {"ok": False, "cancelled": True}
            except Exception as exc:
                return {"ok": False, "error": f"Could not open folder picker: {exc}"}

        return {"ok": False, "error": "Native folder picker is unavailable in this environment — type or paste the path directly."}

    @staticmethod
    def detect_windows_monitors(repo_root: Path) -> Dict[str, Any]:
        """Runs `laptop_gaming_agent.py --list-monitors` on Windows to list connected displays."""
        agent_script = repo_root / "SoarTV-launcher" / "tools" / "gaming" / "laptop_gaming_agent.py"
        if sys.platform != "win32" or not agent_script.is_file():
            return {"ok": False, "error": "Monitor auto-detection is only needed on Windows Gaming PCs."}
        try:
            proc = subprocess.run(
                [sys.executable, str(agent_script), "--list-monitors"],
                capture_output=True,
                text=True,
                timeout=8,
                check=False,
            )
            return {"ok": proc.returncode == 0, "output": (proc.stdout or proc.stderr).strip()}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    @staticmethod
    def probe_tv_adb(tv_ip: str) -> Dict[str, Any]:
        """Briefly tests ADB connection to `<tv_ip>:5555`, reads brand/model, and immediately disconnects."""
        clean_ip = tv_ip.strip()
        if not re.match(r"^[0-9a-zA-Z._-]+$", clean_ip):
            return {"ok": False, "error": "Enter a valid TV IPv4 address (e.g. 192.168.1.50)."}
        adb_bin = shutil.which("adb")
        if not adb_bin:
            for cand in [
                Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
                Path.home() / "Library/Android/sdk/platform-tools/adb",
                Path.home() / "Android/Sdk/platform-tools/adb",
            ]:
                if cand.is_file():
                    adb_bin = str(cand)
                    break
        if not adb_bin:
            return {"ok": False, "error": "ADB is not installed yet — your AI agent can install Android platform-tools for you."}
        target = clean_ip if ":" in clean_ip else f"{clean_ip}:5555"
        try:
            subprocess.run([adb_bin, "connect", target], capture_output=True, text=True, timeout=6, check=False)
            brand = subprocess.run(
                [adb_bin, "-s", target, "shell", "getprop", "ro.product.brand"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            ).stdout.strip()
            model = subprocess.run(
                [adb_bin, "-s", target, "shell", "getprop", "ro.product.model"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            ).stdout.strip()
            if not brand and not model:
                return {
                    "ok": False,
                    "error": "Connected or waiting for approval — check your TV screen and tap 'Allow USB/Wireless Debugging', then try again.",
                }
            return {"ok": True, "brand": brand, "model": model}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}
        finally:
            subprocess.run([adb_bin, "disconnect"], capture_output=True, text=True, timeout=4, check=False)


class CloudBootstrapService:
    """
    Coordinates SoarTV v2 Household Cloud Vault seeding, Supabase TV directory lookup, and
    guided Replace-Existing-TV vs. Add-Additional-TV 3-key onboarding from Setup Studio.

    Why: New or replacement TVs only need 3 bootstrap credentials (`supabase_url`, `supabase_anon_key`,
    `household_key`) while all household API keys and settings hydrate automatically from Supabase.
    What: Reuses `tools/send_key.py` crypto & PostgREST helpers without ever returning raw secrets to HTTP clients.
    """

    @staticmethod
    def _import_send_key() -> Any:
        tools_dir = str(Path(__file__).resolve().parent.parent)
        if tools_dir not in sys.path:
            sys.path.insert(0, tools_dir)
        import send_key as sk_mod  # type: ignore[import-not-found]
        return sk_mod

    @classmethod
    def list_household_tvs(cls, store: SetupConfigStore) -> Dict[str, Any]:
        """Lists active household TVs from Supabase `public.tv_devices` (sanitized non-secret fields)."""
        sk = cls._import_send_key()
        cfg = sk.load_local_config_map(str(store.repo_root))
        if not (cfg.get("supabase_url") and cfg.get("supabase_anon_key") and cfg.get("household_key")):
            return {"ok": True, "configured": False, "tvs": []}
        try:
            raw_tvs = sk.fetch_household_tv_devices(
                cfg["supabase_url"], cfg["supabase_anon_key"], cfg["household_key"], active_only=True
            )
            safe_tvs = [
                {
                    "device_id": str(t.get("device_id") or ""),
                    "device_name": str(t.get("device_name") or ""),
                    "room_name": str(t.get("room_name") or ""),
                    "platform": str(t.get("platform") or ""),
                    "brand": str(t.get("brand") or ""),
                    "lan_ip": str(t.get("lan_ip") or ""),
                    "last_seen_at": str(t.get("last_seen_at") or ""),
                }
                for t in raw_tvs
                if isinstance(t, dict) and t.get("device_id")
            ]
            return {"ok": True, "configured": True, "tvs": safe_tvs}
        except Exception as exc:
            return {"ok": False, "configured": True, "tvs": [], "error": str(exc)}

    @classmethod
    def seed_household_vault(cls, store: SetupConfigStore) -> Dict[str, Any]:
        """Encrypts and uploads all configured household secrets + settings to Supabase."""
        sk = cls._import_send_key()
        cfg = sk.load_local_config_map(str(store.repo_root))
        try:
            return sk.seed_household_cloud(cfg)
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    @classmethod
    def link_tv_bootstrap(
        cls,
        store: SetupConfigStore,
        tv_ip: str,
        tv_name: str = "",
        tv_room: str = "",
        replace_tv_id: str = "",
    ) -> Dict[str, Any]:
        """Pushes ONLY the 3 bootstrap credentials (+ optional TV name/room/replace ID) via ADB and disconnects."""
        clean_ip = (tv_ip or "").strip()
        if not clean_ip:
            return {"ok": False, "error": "Enter your TV's Wi-Fi IP address first."}
        sk = cls._import_send_key()
        cfg = sk.load_local_config_map(str(store.repo_root))
        if not shutil.which("adb"):
            return {"ok": False, "error": "ADB not found on PATH."}
        target = clean_ip if ":" in clean_ip else f"{clean_ip}:5555"
        try:
            r = subprocess.run(["adb", "connect", target], capture_output=True, text=True, timeout=8, check=False)
            if "connected" not in (r.stdout or "").lower():
                return {"ok": False, "error": f"Could not connect to {target}"}
            sk.SERIAL[:] = ["-s", target]
            rc = sk.link_tv_bootstrap(
                cfg,
                tv_name=tv_name,
                tv_room=tv_room,
                replace_tv_id=replace_tv_id,
            )
            return {
                "ok": rc == 0,
                "mode": "replace_tv" if replace_tv_id.strip() else "add_tv",
                "message": "TV linked to Household Cloud (3-key bootstrap complete)." if rc == 0 else "TV link failed.",
            }
        except Exception as exc:
            return {"ok": False, "error": str(exc)}
        finally:
            sk.SERIAL[:] = []
            subprocess.run(["adb", "disconnect"], capture_output=True, text=True, timeout=4, check=False)


def create_setup_http_server(
    repo_root: Path,
    port: int = 8765,
    sandbox: bool = False,
    wait_for_save: bool = False,
) -> Tuple[ThreadingHTTPServer, str]:
    """Creates a loopback-only (`127.0.0.1`) HTTP server with per-session CSRF token protection.

    When `sandbox=True`, all config reads/writes go to an isolated temporary directory and TV ADB
    probing is simulated so the user can test every button safely without touching their TV or real files.
    When `wait_for_save=True`, `POST /api/complete` persists `.setup_handoff.json` and gracefully stops
    the server so a launching AI agent can immediately resume installation.
    """
    session_token = secrets.token_urlsafe(24)
    effective_root = repo_root
    if sandbox:
        tmp_dir = Path(tempfile.mkdtemp(prefix="soartv_setup_sandbox_"))
        (tmp_dir / "SoarTV-launcher").mkdir(parents=True, exist_ok=True)
        for rel in ("SoarTV-launcher/config.example.properties", ".env.example"):
            src = repo_root / rel
            if src.is_file():
                shutil.copyfile(src, tmp_dir / rel)
        effective_root = tmp_dir

    store = SetupConfigStore(effective_root)
    static_dir = Path(__file__).resolve().parent / "static"
    static_html_path = static_dir / "index.html"
    guides_dir = static_dir / "guides"
    server_holder: Dict[str, Optional[ThreadingHTTPServer]] = {"httpd": None}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:
            return  # Keep terminal clean and never log request query/body data

        def _send_json(self, status: int, data: Dict[str, Any]) -> None:
            body = json.dumps(data).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _check_csrf(self) -> bool:
            supplied = self.headers.get("X-SoarTV-Setup-Token", "")
            if not secrets.compare_digest(supplied, session_token):
                self._send_json(403, {"ok": False, "error": "Invalid session token"})
                return False
            return True

        def do_GET(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path in ("/", "/index.html"):
                html = static_html_path.read_text(encoding="utf-8") if static_html_path.is_file() else "<h1>SoarTV Setup</h1>"
                html = html.replace("__SOARTV_SETUP_TOKEN__", session_token)
                body = html.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if parsed.path.startswith("/guides/"):
                fname = parsed.path[len("/guides/"):]
                if not re.match(r"^[a-z0-9_-]+\.(png|webp|jpg)$", fname):
                    self._send_json(404, {"ok": False, "error": "Guide image not found"})
                    return
                img_path = guides_dir / fname
                if not img_path.is_file():
                    self._send_json(404, {"ok": False, "error": "Guide image not found"})
                    return
                mime = {
                    ".png": "image/png",
                    ".webp": "image/webp",
                    ".jpg": "image/jpeg",
                }[img_path.suffix.lower()]
                data = img_path.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return

            if parsed.path == "/api/state":
                if not self._check_csrf():
                    return
                state = store.load_masked_state()
                tools = EnvironmentInspector.check_local_tools()
                self._send_json(
                    200,
                    {
                        "ok": True,
                        "sandbox": sandbox,
                        "wait_for_save": wait_for_save,
                        "state": state,
                        "tools": tools,
                    },
                )
                return

            if parsed.path == "/api/cloud/tvs":
                if not self._check_csrf():
                    return
                if sandbox:
                    self._send_json(
                        200,
                        {
                            "ok": True,
                            "configured": True,
                            "tvs": [
                                {
                                    "device_id": "sandbox-tv-living-room",
                                    "device_name": "Living Room TV (Sandbox)",
                                    "room_name": "Living Room",
                                    "platform": "android-tv",
                                    "brand": "SONY",
                                    "lan_ip": "192.168.1.50",
                                    "last_seen_at": "2026-11-28T10:00:00Z",
                                }
                            ],
                        },
                    )
                    return
                self._send_json(200, CloudBootstrapService.list_household_tvs(store))
                return

            self._send_json(404, {"ok": False, "error": "Not found"})

        def do_POST(self) -> None:
            if not self._check_csrf():
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 65536:
                self._send_json(413, {"ok": False, "error": "Payload too large"})
                return
            raw = self.rfile.read(length) if length > 0 else b"{}"
            try:
                body = json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                self._send_json(400, {"ok": False, "error": "Invalid JSON"})
                return

            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/api/save":
                store.save_settings(body.get("settings") or {})
                tv_plat = str(body.get("tv_platform", "android_tv"))
                handoff = store.write_handoff_summary(tv_platform=tv_plat)
                self._send_json(
                    200,
                    {
                        "ok": True,
                        "state": store.load_masked_state(),
                        "handoff_file": str(handoff),
                    },
                )
                return
            if parsed.path == "/api/complete":
                store.save_settings(body.get("settings") or {})
                tv_plat = str(body.get("tv_platform", "android_tv"))
                handoff = store.write_handoff_summary(tv_platform=tv_plat)
                self._send_json(
                    200,
                    {
                        "ok": True,
                        "state": store.load_masked_state(),
                        "handoff_file": str(handoff),
                        "will_close": wait_for_save,
                    },
                )
                if wait_for_save and server_holder["httpd"] is not None:
                    httpd_ref = server_holder["httpd"]
                    threading.Timer(0.6, httpd_ref.shutdown).start()
                return
            if parsed.path == "/api/prompt":
                prompt = AgentPromptBuilder.build_prompt(
                    tv_platform=str(body.get("tv_platform", "android_tv")),
                    tv_ip=str(body.get("tv_ip", "")),
                    city=str(body.get("city", "")),
                    enable_smarthome=bool(body.get("enable_smarthome", False)),
                    smarthome_devices=[str(x) for x in (body.get("smarthome_devices") or [])],
                    enable_gaming=bool(body.get("enable_gaming", False)),
                    games_dirs=str(body.get("games_dirs", "")),
                    custom_smarthome=body.get("custom_smarthome") if isinstance(body.get("custom_smarthome"), list) else None,
                    onboarding_mode=str(body.get("onboarding_mode", "add_tv")),
                    replace_tv_id=str(body.get("replace_tv_id", "")),
                    enable_reels=bool(body.get("enable_reels", False)),
                )
                self._send_json(200, {"ok": True, "prompt": prompt})
                return
            if parsed.path == "/api/cloud/seed":
                if sandbox:
                    self._send_json(200, {"ok": True, "vault_id": "household", "key_count": 3, "sandbox": True})
                    return
                self._send_json(200, CloudBootstrapService.seed_household_vault(store))
                return
            if parsed.path == "/api/cloud/link-tv":
                if sandbox:
                    rep = str(body.get("replace_tv_id", "")).strip()
                    self._send_json(
                        200,
                        {
                            "ok": True,
                            "mode": "replace_tv" if rep else "add_tv",
                            "message": "Simulated 3-key TV link in Sandbox Mode.",
                            "sandbox": True,
                        },
                    )
                    return
                self._send_json(
                    200,
                    CloudBootstrapService.link_tv_bootstrap(
                        store,
                        tv_ip=str(body.get("tv_ip", "")),
                        tv_name=str(body.get("tv_name", "")),
                        tv_room=str(body.get("tv_room", "")),
                        replace_tv_id=str(body.get("replace_tv_id", "")),
                    ),
                )
                return
            if parsed.path == "/api/browse-folder":
                self._send_json(200, EnvironmentInspector.browse_folder_dialog())
                return
            if parsed.path == "/api/validate-tmdb":
                ok, msg = EnvironmentInspector.validate_tmdb_key(str(body.get("tmdb_key", "")))
                self._send_json(200, {"ok": ok, "message": msg})
                return
            if parsed.path == "/api/lookup-city":
                self._send_json(200, EnvironmentInspector.lookup_city(str(body.get("city", ""))))
                return
            if parsed.path == "/api/generate-household-key":
                creds = EnvironmentInspector.generate_household_credentials(str(body.get("admin_email", "")))
                self._send_json(200, {"ok": True, **creds})
                return
            if parsed.path == "/api/list-monitors":
                self._send_json(200, EnvironmentInspector.detect_windows_monitors(repo_root))
                return
            if parsed.path == "/api/probe-tv":
                if sandbox:
                    ip = str(body.get("tv_ip", "")).strip() or "192.168.1.50"
                    self._send_json(
                        200,
                        {
                            "ok": True,
                            "brand": "Sandbox Preview",
                            "model": f"(Simulated TV at {ip} — ADB skipped in Sandbox Mode)",
                        },
                    )
                    return
                self._send_json(200, EnvironmentInspector.probe_tv_adb(str(body.get("tv_ip", ""))))
                return

            self._send_json(404, {"ok": False, "error": "Unknown endpoint"})

    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    except OSError:
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server_holder["httpd"] = httpd
    return httpd, session_token


def main() -> None:
    parser = argparse.ArgumentParser(description="Launch the SoarTV Liquid Glass Desktop Setup Studio.")
    parser.add_argument("--port", type=int, default=8765, help="Local port on 127.0.0.1 (default: 8765)")
    parser.add_argument("--no-browser", action="store_true", help="Do not open web browser automatically")
    parser.add_argument("--sandbox", action="store_true", help="Run in isolated test mode (never touches real config files or TV)")
    parser.add_argument("--wait-for-save", action="store_true", help="Exit cleanly right after the user clicks Save so an AI agent can resume")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    server, _ = create_setup_http_server(
        repo_root,
        port=args.port,
        sandbox=args.sandbox,
        wait_for_save=args.wait_for_save,
    )
    actual_port = server.server_address[1]
    url = f"http://127.0.0.1:{actual_port}/"
    print(f"SoarTV Setup Studio running locally at {url}", flush=True)
    if args.wait_for_save:
        print("Waiting for user to complete setup and click Save...", flush=True)
    else:
        print("Press Ctrl+C to close when you are done.", flush=True)
    if not args.no_browser:
        threading.Timer(0.35, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nClosing SoarTV Setup Studio.")
    finally:
        server.server_close()
        if args.wait_for_save:
            handoff = repo_root / "SoarTV-launcher" / ".setup_handoff.json"
            if handoff.is_file():
                print(f"SETUP_SAVED handoff={handoff}", flush=True)


if __name__ == "__main__":
    main()
