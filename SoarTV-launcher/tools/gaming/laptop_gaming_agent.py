"""
SoarTV Laptop Gaming Agent & Sunshine/Steam Synchronizer.

Why: Enables the SoarTV Launcher's Gaming Zone to automatically wake the Windows gaming laptop
from Modern Standby (S0 Low Power Idle) or Wake-on-WLAN, detect TV HDMI cable vs. Wi-Fi connection
in-process without CLI subprocess churn, securely unlock the Windows Hello Alphanumeric PIN lock
screen using native Win32 SendInput (with Windows 11 Winlogon UIPI helper fallback), authenticate
LAN clients via SHA-256 paired tokens and 6-digit pairing codes with strict sliding-window rate
limiting, switch Windows display output to 'TV Screen Only' (DisplaySwitch.exe /external) on HDMI
with automatic restoration (/internal) on disconnect, and synchronize Steam + standalone game folder
titles into Sunshine and the SoarTV Gaming Zone picker.

What: Provides OOP domain services (AgentLogger, SlidingWindowRateLimiter, AgentAuthManager,
Win32InputInjector, Win32SystemProbes, WifiWakeConfigurator, GameCatalogScanner,
SunshineConfigManager, HiddenGamesManager, DisplayModeController, WindowsSessionController,
SystemPowerController, LaptopGamingAgentApp) and an embedded HTTP JSON server on port 8485.
"""

from __future__ import annotations

import atexit
import base64
from collections import deque
import ctypes
from ctypes import wintypes
from dataclasses import dataclass, replace
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import logging
import math
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
from typing import (
    Any,
    Callable,
    Deque,
    Dict,
    FrozenSet,
    List,
    Mapping,
    Optional,
    Sequence,
    Set,
    TextIO,
    Tuple,
)
import urllib.parse
import urllib.request


# -----------------------------------------------------------------------------
# Named Domain, Security, Rate-Limit, and Win32 Constants (No Magic Numbers)
# -----------------------------------------------------------------------------

DEFAULT_AGENT_PORT: int = 8485
SUNSHINE_HTTP_PORT: int = 47989
SUNSHINE_STREAM_HTTPS_PORT: int = 47984
SUNSHINE_HTTPS_PORT: int = 47990
SUNSHINE_API_TIMEOUT_SEC: int = 20
SUNSHINE_SOCKET_TIMEOUT_SEC: float = 0.20
SUNSHINE_LIFECYCLE_TIMEOUT_SEC: float = 25.0
HTTP_MAX_BODY_BYTES: int = 65536
HTTP_REQUEST_TIMEOUT_SEC: float = 10.0
BACKGROUND_POLL_INTERVAL_SEC: float = 3.5
CATALOG_REFRESH_EVERY_TICKS: int = 20
HDMI_RECENT_WINDOW_SEC: float = 7200.0
MAX_EXE_SCAN_DEPTH: int = 2
STEAM_REDISTRIBUTABLE_APPID: str = "228980"
STEAM_COLD_RETRY_DELAY_SEC: float = 14.0
EPIC_COLD_RETRY_DELAY_SEC: float = 14.0
HEROIC_COLD_RETRY_DELAY_SEC: float = 14.0
DEFAULT_STEAM_IDLE_GRACE_SECONDS: float = 600.0
STEAM_GRACEFUL_SHUTDOWN_WAIT_SEC: float = 15.0
EPIC_GRACEFUL_SHUTDOWN_WAIT_SEC: float = 15.0
HEROIC_GRACEFUL_SHUTDOWN_WAIT_SEC: float = 15.0
DEFAULT_EPIC_MANIFESTS_DIR: Path = Path(
    r"C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests"
)
DEFAULT_HEROIC_CONFIG_DIR: Path = Path(os.environ.get("APPDATA", "")) / "heroic"
DEFAULT_LEGENDARY_CONFIG_DIR: Path = Path.home() / ".config" / "legendary"
IGNORED_LAUNCHER_FOLDER_NAMES: FrozenSet[str] = frozenset(
    {"steam", "steamapps", "common", "temp", "downloading", "heroic", "epic games"}
)

PIN_VALIDATION_PATTERN: re.Pattern[str] = re.compile(r"^[A-Za-z0-9]{4,32}$")
SUNSHINE_PIN_PATTERN: re.Pattern[str] = re.compile(r"^\d{4}$")
PAIR_CODE_PATTERN: re.Pattern[str] = re.compile(r"^\d{6}$")
PAIR_CODE_MIN_VALUE: int = 100000
PAIR_CODE_SPAN: int = 900000
PAIR_CODE_TTL_SECONDS: int = 300
TOKEN_RANDOM_BYTES: int = 32
MIN_CONFIG_TOKEN_LENGTH: int = 8
MAX_DEVICE_NAME_LENGTH: int = 64

RATE_LIMIT_WINDOW_SECONDS: float = 60.0
RATE_LIMIT_MAX_PER_IP: int = 3
RATE_LIMIT_MAX_GLOBAL: int = 5

LOCALHOST_ORIGIN_PATTERN: re.Pattern[str] = re.compile(
    r"^https?://(127\.0\.0\.1|localhost)(:\d{1,5})?$",
    re.IGNORECASE,
)

LOCK_CURTAIN_DISMISS_DELAY_SEC: float = 0.25
UNLOCK_HELPER_TASK_NAME: str = "SoarTV-Winlogon-Unlock"
IPC_TOKEN_TTL_SECONDS: float = 10.0
IPC_ASYNC_CLEANUP_SEC: float = 3.0
HELPER_EXEC_TIMEOUT_SEC: float = 0.35
WIFI_CONFIG_CMD_TIMEOUT_SEC: float = 5.0

# Win32 Input & Desktop Constants
INPUT_MOUSE: int = 0
INPUT_KEYBOARD: int = 1
INPUT_HARDWARE: int = 2
KEYEVENTF_KEYUP: int = 0x0002
KEYEVENTF_UNICODE: int = 0x0004
MOUSEEVENTF_MOVE: int = 0x0001
VK_RETURN: int = 0x0D
VK_SPACE: int = 0x20
WM_KEYDOWN: int = 0x0100
WM_KEYUP: int = 0x0101

STEAM_INTERSTITIAL_MIN_WIDTH: int = 480
STEAM_INTERSTITIAL_MAX_WIDTH: int = 940
STEAM_INTERSTITIAL_MIN_HEIGHT: int = 320
STEAM_INTERSTITIAL_MAX_HEIGHT: int = 640
STEAM_INTERSTITIAL_WATCH_SECONDS: float = 20.0
STEAM_INTERSTITIAL_POLL_SECONDS: float = 0.60

# Win32 Privacy Curtain & Cursor Constants
SM_XVIRTUALSCREEN: int = 76
SM_YVIRTUALSCREEN: int = 77
SM_CXVIRTUALSCREEN: int = 78
SM_CYVIRTUALSCREEN: int = 79
WS_POPUP: int = 0x80000000
WS_VISIBLE: int = 0x10000000
WS_EX_TOPMOST: int = 0x00000008
WS_EX_TOOLWINDOW: int = 0x00000080
SWP_NOACTIVATE: int = 0x0010
SWP_SHOWWINDOW: int = 0x0040
SW_HIDE: int = 0
SW_SHOWNORMAL: int = 1
SW_SHOWMINIMIZED: int = 2
SW_SHOWMAXIMIZED: int = 3
SW_SHOW: int = 5
SW_MINIMIZE: int = 6
SW_RESTORE: int = 9
VK_ESCAPE: int = 0x1B
CLSCTX_INPROC_SERVER: int = 1
CLSCTX_ALL: int = 23
CLSID_MM_DEVICE_ENUMERATOR: str = "{BCDE0395-E52F-467C-8E3D-C4579291692E}"
IID_IMM_DEVICE_ENUMERATOR: str = "{A95664D2-9614-4F35-A746-DE8DB63617E6}"
IID_IAUDIO_ENDPOINT_VOLUME: str = "{5CDF2C82-841E-4546-9722-0CF74078229A}"
WM_DESTROY: int = 0x0002
WM_CLOSE: int = 0x0010
WM_SETCURSOR: int = 0x0020
PM_REMOVE: int = 0x0001
BLACK_BRUSH: int = 4
DWMWA_CLOAKED: int = 14

PRIVACY_CURTAIN_CLASS_NAME: str = "SoarTVPrivacyCurtainWindow"
PRIVACY_CURTAIN_POLL_SEC: float = 0.25
PRIVACY_CURTAIN_FALLBACK_GRACE_SEC: float = 2.0
PRIVACY_CURTAIN_CONNECT_TIMEOUT_SEC: float = 75.0
PRIVACY_CURTAIN_TOPMOST_REASSERT_SEC: float = 0.35
PRIVACY_CURTAIN_MAX_SESSION_SEC: float = 43200.0
PRIVACY_CURTAIN_DISCONNECT_GRACE_SEC: float = 5.0
GAME_TERMINATE_TIMEOUT_SEC: float = 3.0
PROCESS_TERMINATE: int = 0x0001
MIN_GAME_WINDOW_WIDTH: int = 640
MIN_GAME_WINDOW_HEIGHT: int = 480
MIN_BIGPICTURE_WINDOW_WIDTH: int = 1200
MIN_BIGPICTURE_WINDOW_HEIGHT: int = 700

DESKTOP_READOBJECTS: int = 0x0001
DESKTOP_HOOKCONTROL: int = 0x0008
DESKTOP_JOURNALPLAYBACK: int = 0x0020
DESKTOP_WRITEOBJECTS: int = 0x0080
DESKTOP_SWITCHDESKTOP: int = 0x0100
DESKTOP_INJECT_ACCESS: int = (
    DESKTOP_READOBJECTS
    | DESKTOP_HOOKCONTROL
    | DESKTOP_JOURNALPLAYBACK
    | DESKTOP_WRITEOBJECTS
    | DESKTOP_SWITCHDESKTOP
)
UOI_NAME: int = 2

# Win32 Display Configuration (CCD) & EnumDisplayDevices Constants
ERROR_SUCCESS: int = 0
QDC_ONLY_ACTIVE_PATHS: int = 0x00000002
DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME: int = 2
DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_PREFERRED_MODE: int = 3
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI: int = 5
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_LVDS: int = 6
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EXTERNAL: int = 10
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EMBEDDED: int = 11
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_UDI_EMBEDDED: int = 13
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INDIRECT_VIRTUAL: int = 17
DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL: int = 0x80000000

EDD_GET_DEVICE_INTERFACE_NAME: int = 0x00000001
DISPLAY_DEVICE_ATTACHED_TO_DESKTOP: int = 0x00000001
DISPLAY_DEVICE_ACTIVE: int = 0x00000001

# Win32 Session (WTS), Process Snapshot, Power, & Service Control Manager Constants
WTS_CURRENT_SERVER_HANDLE: int = 0
WTS_SESSION_INFO_EX_CLASS: int = 25
WTS_SESSIONSTATE_LOCK: int = 0x00000000
WTS_SESSIONSTATE_UNLOCK: int = 0x00000001
WTS_INVALID_SESSION_ID: int = 0xFFFFFFFF

TH32CS_SNAPPROCESS: int = 0x00000002
MAX_PATH_LEN: int = 260

SC_MANAGER_CONNECT: int = 0x0001
SERVICE_QUERY_STATUS: int = 0x0004
SERVICE_START: int = 0x0010
SERVICE_STOP: int = 0x0020
SERVICE_CONTROL_STOP: int = 0x00000001
SC_STATUS_PROCESS_INFO: int = 0
SERVICE_STOPPED: int = 1
SERVICE_START_PENDING: int = 2
SERVICE_STOP_PENDING: int = 3
SERVICE_RUNNING: int = 4

BATTERY_FLAG_CHARGING: int = 8
BATTERY_FLAG_NO_SYSTEM_BATTERY: int = 128
BATTERY_VALUE_UNKNOWN: int = 255
BATTERY_MAX_PERCENT: int = 100

NETWORK_ADAPTER_CLASS_REG_PATH: str = (
    r"SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}"
)

IGNORED_EXE_PATTERNS: Tuple[str, ...] = (
    "crashreport",
    "unitycrashhandler",
    "unins",
    "vc_redist",
    "vcredist",
    "dxsetup",
    "bankeditor",
    "dedicated_server",
    "leagueclientux",
    "riotclientservices",
    "tftclient",
)

KNOWN_STANDALONE_OVERRIDES: Dict[str, Tuple[str, str]] = {
    "hogwartslegacy": ("Hogwarts Legacy", "HogwartsLegacy.exe"),
    "worms armageddon": ("Worms Armageddon", "WA.exe"),
    "riot games": ("League of Legends", r"League of Legends\LeagueClient.exe"),
}


def _is_automated_test_env() -> bool:
    """
    Returns True when running under an automated test suite unless SOARTV_ALLOW_LIVE_OS_MUTATIONS=1.

    Why: Prevents unmocked unit tests from mutating live Windows audio mute state, minimizing open
    windows, hiding desktop icons, moving the cursor, or changing monitor refresh rate.
    """
    return (
        "unittest" in sys.modules
        or "pytest" in sys.modules
        or os.environ.get("SOARTV_TESTING") == "1"
        or os.environ.get("SOARTV_TEST_MODE") == "1"
    ) and os.environ.get("SOARTV_ALLOW_LIVE_OS_MUTATIONS") != "1"


# -----------------------------------------------------------------------------
# Structured Diagnostic Logger (Secret-Safe)
# -----------------------------------------------------------------------------


class AgentLogger:
    """
    Secret-safe diagnostic logger for the SoarTV Laptop Gaming Agent.

    Why: Ensures exceptions are never silently swallowed while guaranteeing that
    sensitive values (Windows Hello PINs, auth tokens, Sunshine credentials) are never logged.
    What: Wraps a dedicated Python logging.Logger with helper methods for debug, info, and warning.
    """

    _logger: logging.Logger = logging.getLogger("soartv.gaming_agent")
    if not _logger.handlers:
        _logger.addHandler(logging.NullHandler())

    @classmethod
    def configure_console(cls, level: int = logging.INFO) -> None:
        """Attaches an encoding-safe console handler when running interactively or as a daemon."""
        if not any(isinstance(h, logging.StreamHandler) for h in cls._logger.handlers):
            if hasattr(sys.stderr, "reconfigure"):
                try:
                    sys.stderr.reconfigure(errors="replace")
                except (OSError, ValueError):
                    pass
            handler = logging.StreamHandler(sys.stderr)
            handler.setFormatter(logging.Formatter("[%(asctime)s] %(levelname)s: %(message)s"))
            cls._logger.addHandler(handler)
        cls._logger.setLevel(level)

    @classmethod
    def debug(cls, message: str, *args: Any) -> None:
        """Logs low-level diagnostic telemetry."""
        cls._logger.debug(message, *args)

    @classmethod
    def info(cls, message: str, *args: Any) -> None:
        """Logs operational lifecycle milestones (such as local pairing requests)."""
        cls._logger.info(message, *args)

    @classmethod
    def warning(cls, message: str, *args: Any) -> None:
        """Logs recoverable operational warnings without swallowing error context."""
        cls._logger.warning(message, *args)


# -----------------------------------------------------------------------------
# Win32 ctypes ABI Structures
# -----------------------------------------------------------------------------


class MOUSEINPUT(ctypes.Structure):
    """Win32 tagMOUSEINPUT structure required for accurate sizeof(INPUT) union alignment."""

    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_size_t),
    ]


class KEYBDINPUT(ctypes.Structure):
    """Win32 tagKEYBDINPUT structure for virtual-key and Unicode keystroke injection."""

    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_size_t),
    ]


class HARDWAREINPUT(ctypes.Structure):
    """Win32 tagHARDWAREINPUT structure completing the tagINPUT union."""

    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_UNION(ctypes.Union):
    """Union inside Win32 tagINPUT ensuring 40-byte size on x64 and 28-byte size on x86."""

    _fields_ = [
        ("mi", MOUSEINPUT),
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class WIN32_INPUT(ctypes.Structure):
    """
    Win32 tagINPUT structure passed to user32.SendInput.

    Why: user32.SendInput rejects calls if cbSize does not exactly match native sizeof(INPUT).
    What: Combines DWORD type with MOUSEINPUT/KEYBDINPUT/HARDWAREINPUT union.
    """

    _anonymous_ = ("_u",)
    _fields_ = [
        ("type", wintypes.DWORD),
        ("_u", _INPUT_UNION),
    ]


class LUID(ctypes.Structure):
    """Win32 locally unique identifier for display adapters."""

    _fields_ = [
        ("LowPart", wintypes.DWORD),
        ("HighPart", wintypes.LONG),
    ]


class DISPLAYCONFIG_RATIONAL(ctypes.Structure):
    """Win32 refresh rate rational fraction."""

    _fields_ = [
        ("Numerator", ctypes.c_uint32),
        ("Denominator", ctypes.c_uint32),
    ]


class DISPLAYCONFIG_PATH_SOURCE_INFO(ctypes.Structure):
    """Win32 CCD display source path descriptor."""

    _fields_ = [
        ("adapterId", LUID),
        ("id", ctypes.c_uint32),
        ("modeInfoIdx", ctypes.c_uint32),
        ("statusFlags", ctypes.c_uint32),
    ]


class DISPLAYCONFIG_PATH_TARGET_INFO(ctypes.Structure):
    """Win32 CCD display target path descriptor."""

    _fields_ = [
        ("adapterId", LUID),
        ("id", ctypes.c_uint32),
        ("modeInfoIdx", ctypes.c_uint32),
        ("outputTechnology", ctypes.c_uint32),
        ("rotation", ctypes.c_uint32),
        ("scaling", ctypes.c_uint32),
        ("refreshRate", DISPLAYCONFIG_RATIONAL),
        ("scanLineOrdering", ctypes.c_uint32),
        ("targetAvailable", wintypes.BOOL),
        ("statusFlags", ctypes.c_uint32),
    ]


class DISPLAYCONFIG_PATH_INFO(ctypes.Structure):
    """Win32 CCD active display path entry (72 bytes)."""

    _fields_ = [
        ("sourceInfo", DISPLAYCONFIG_PATH_SOURCE_INFO),
        ("targetInfo", DISPLAYCONFIG_PATH_TARGET_INFO),
        ("flags", ctypes.c_uint32),
    ]


class _DISPLAYCONFIG_MODE_INFO_UNION(ctypes.Union):
    """48-byte, 8-byte-aligned union matching DISPLAYCONFIG_MODE_INFO in wingdi.h."""

    _fields_ = [
        ("raw", ctypes.c_uint64 * 6),
    ]


class DISPLAYCONFIG_MODE_INFO(ctypes.Structure):
    """Win32 CCD display mode entry (64 bytes)."""

    _fields_ = [
        ("infoType", ctypes.c_uint32),
        ("id", ctypes.c_uint32),
        ("adapterId", LUID),
        ("modeUnion", _DISPLAYCONFIG_MODE_INFO_UNION),
    ]


class DISPLAYCONFIG_DEVICE_INFO_HEADER(ctypes.Structure):
    """Win32 CCD device info request header (20 bytes)."""

    _fields_ = [
        ("type", ctypes.c_uint32),
        ("size", ctypes.c_uint32),
        ("adapterId", LUID),
        ("id", ctypes.c_uint32),
    ]


class DISPLAYCONFIG_TARGET_DEVICE_NAME(ctypes.Structure):
    """Win32 CCD target monitor EDID device name & hardware path packet (420 bytes)."""

    _fields_ = [
        ("header", DISPLAYCONFIG_DEVICE_INFO_HEADER),
        ("flags", ctypes.c_uint32),
        ("outputTechnology", ctypes.c_uint32),
        ("edidManufactureId", ctypes.c_uint16),
        ("edidProductCodeId", ctypes.c_uint16),
        ("connectorInstance", ctypes.c_uint32),
        ("monitorFriendlyDeviceName", ctypes.c_wchar * 64),
        ("monitorDevicePath", ctypes.c_wchar * 128),
    ]


class DISPLAYCONFIG_TARGET_PREFERRED_MODE(ctypes.Structure):
    """Win32 CCD target monitor preferred native resolution packet (80 bytes)."""

    _fields_ = [
        ("header", DISPLAYCONFIG_DEVICE_INFO_HEADER),
        ("width", ctypes.c_uint32),
        ("height", ctypes.c_uint32),
        ("targetMode", _DISPLAYCONFIG_MODE_INFO_UNION),
    ]


class DISPLAY_DEVICEW(ctypes.Structure):
    """Win32 DISPLAY_DEVICEW structure for EnumDisplayDevicesW fallback."""

    _fields_ = [
        ("cb", wintypes.DWORD),
        ("DeviceName", wintypes.WCHAR * 32),
        ("DeviceString", wintypes.WCHAR * 128),
        ("StateFlags", wintypes.DWORD),
        ("DeviceID", wintypes.WCHAR * 128),
        ("DeviceKey", wintypes.WCHAR * 128),
    ]


class WTSINFOEX_LEVEL1_W(ctypes.Structure):
    """Win32 WTSINFOEX_LEVEL1_W structure carrying SessionFlags lock state."""

    _fields_ = [
        ("SessionId", wintypes.ULONG),
        ("SessionState", ctypes.c_int),
        ("SessionFlags", wintypes.LONG),
        ("WinStationName", ctypes.c_wchar * 33),
        ("UserName", ctypes.c_wchar * 21),
        ("DomainName", ctypes.c_wchar * 18),
        ("LogonTime", ctypes.c_int64),
        ("ConnectTime", ctypes.c_int64),
        ("DisconnectTime", ctypes.c_int64),
        ("LastInputTime", ctypes.c_int64),
        ("CurrentTime", ctypes.c_int64),
        ("IncomingBytes", ctypes.c_uint32),
        ("OutgoingBytes", ctypes.c_uint32),
        ("IncomingFrames", ctypes.c_uint32),
        ("OutgoingFrames", ctypes.c_uint32),
        ("IncomingCompressedBytes", ctypes.c_uint32),
        ("OutgoingCompressedBytes", ctypes.c_uint32),
    ]


class WTSINFOEXW(ctypes.Structure):
    """Win32 WTSINFOEXW envelope returned by WTSQuerySessionInformationW(WTSSessionInfoEx)."""

    _fields_ = [
        ("Level", wintypes.DWORD),
        ("Data", WTSINFOEX_LEVEL1_W),
    ]


class PROCESSENTRY32W(ctypes.Structure):
    """Win32 Toolhelp32 PROCESSENTRY32W structure for zero-subprocess process inspection."""

    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", wintypes.LONG),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", wintypes.WCHAR * MAX_PATH_LEN),
    ]


class SERVICE_STATUS_PROCESS(ctypes.Structure):
    """Win32 SERVICE_STATUS_PROCESS structure returned by advapi32.QueryServiceStatusEx."""

    _fields_ = [
        ("dwServiceType", wintypes.DWORD),
        ("dwCurrentState", wintypes.DWORD),
        ("dwControlsAccepted", wintypes.DWORD),
        ("dwWin32ExitCode", wintypes.DWORD),
        ("dwServiceSpecificExitCode", wintypes.DWORD),
        ("dwCheckPoint", wintypes.DWORD),
        ("dwWaitHint", wintypes.DWORD),
        ("dwProcessId", wintypes.DWORD),
        ("dwServiceFlags", wintypes.DWORD),
    ]


class SYSTEM_POWER_STATUS(ctypes.Structure):
    """
    Win32 SYSTEM_POWER_STATUS structure using unsigned c_ubyte fields.

    Why: Win32 uses 255 (0xFF) for unknown battery status/percent; signed c_byte would map 255 to -1.
    What: Mirrors kernel32.GetSystemPowerStatus binary layout.
    """

    _fields_ = [
        ("ACLineStatus", ctypes.c_ubyte),
        ("BatteryFlag", ctypes.c_ubyte),
        ("BatteryLifePercent", ctypes.c_ubyte),
        ("SystemStatusFlag", ctypes.c_ubyte),
        ("BatteryLifeTime", ctypes.c_ulong),
        ("BatteryFullLifeTime", ctypes.c_ulong),
    ]


# -----------------------------------------------------------------------------
# Security: Sliding-Window Rate Limiter & Paired Token Authentication Manager
# -----------------------------------------------------------------------------


class SlidingWindowRateLimiter:
    """
    Thread-safe sliding-window rate limiter enforcing both per-IP and global attempt ceilings.

    Why: Protects Windows Hello against TPM anti-hammering lockouts and prevents brute-force
    guessing of 6-digit pairing codes over the local network (SEC-04).
    What: Tracks timestamps per client IP and globally within a rolling window (default 60s),
    enforcing max_per_ip (default 3) and max_global (default 5).
    """

    def __init__(
        self,
        max_per_ip: int = RATE_LIMIT_MAX_PER_IP,
        max_global: int = RATE_LIMIT_MAX_GLOBAL,
        window_seconds: float = RATE_LIMIT_WINDOW_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.max_per_ip = max_per_ip
        self.max_global = max_global
        self.window_seconds = window_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._per_ip: Dict[str, Deque[float]] = {}
        self._global: Deque[float] = deque()

    def _prune_unlocked(self, now: float) -> None:
        cutoff = now - self.window_seconds
        while self._global and self._global[0] <= cutoff:
            self._global.popleft()
        stale_ips: List[str] = []
        for ip_key, dq in self._per_ip.items():
            while dq and dq[0] <= cutoff:
                dq.popleft()
            if not dq:
                stale_ips.append(ip_key)
        for ip_key in stale_ips:
            del self._per_ip[ip_key]

    def _compute_retry_after_unlocked(self, ip_key: str, now: float) -> int:
        candidates: List[float] = []
        ip_dq = self._per_ip.get(ip_key)
        if ip_dq and len(ip_dq) >= self.max_per_ip:
            candidates.append(self.window_seconds - (now - ip_dq[0]))
        if len(self._global) >= self.max_global:
            candidates.append(self.window_seconds - (now - self._global[0]))
        if not candidates:
            return 1
        return max(1, int(math.ceil(max(candidates))))

    def check(self, client_ip: str) -> Tuple[bool, int]:
        """
        Checks whether client_ip is currently within rate limits without recording a new event.

        Why: Used before verifying a pairing code so valid attempts are checked against prior
        failures while only failed pairing attempts increment the failure counter.
        What: Returns (allowed, retry_after_seconds).
        """
        ip_key = (client_ip or "unknown").strip() or "unknown"
        with self._lock:
            now = self._clock()
            self._prune_unlocked(now)
            ip_dq = self._per_ip.get(ip_key)
            if (ip_dq and len(ip_dq) >= self.max_per_ip) or len(self._global) >= self.max_global:
                return False, self._compute_retry_after_unlocked(ip_key, now)
            return True, 0

    def record_event(self, client_ip: str) -> None:
        """Records a failed or rate-limited attempt for client_ip and the global window."""
        ip_key = (client_ip or "unknown").strip() or "unknown"
        with self._lock:
            now = self._clock()
            self._prune_unlocked(now)
            dq = self._per_ip.setdefault(ip_key, deque())
            dq.append(now)
            self._global.append(now)

    def check_and_record(self, client_ip: str) -> Tuple[bool, int]:
        """
        Atomically checks rate limits and records the attempt if permitted.

        Why: Used on /api/gaming/unlock so at most 3 unlock attempts per IP (and 5 globally)
        can reach Windows Hello within any 60-second window.
        What: Returns (allowed, retry_after_seconds).
        """
        ip_key = (client_ip or "unknown").strip() or "unknown"
        with self._lock:
            now = self._clock()
            self._prune_unlocked(now)
            ip_dq = self._per_ip.get(ip_key)
            if (ip_dq and len(ip_dq) >= self.max_per_ip) or len(self._global) >= self.max_global:
                return False, self._compute_retry_after_unlocked(ip_key, now)
            dq = self._per_ip.setdefault(ip_key, deque())
            dq.append(now)
            self._global.append(now)
            return True, 0

    def clear(self) -> None:
        """Clears all recorded rate-limit windows (primarily for deterministic unit testing)."""
        with self._lock:
            self._per_ip.clear()
            self._global.clear()


class AgentAuthManager:
    """
    Manages SHA-256 hashed client tokens, config.properties pre-seeding, 6-digit pairing, and CORS checks.

    Why: Eliminates unauthenticated LAN access and drive-by browser attacks against the host PC
    while storing zero plaintext tokens on disk (SEC-04, SEC-06).
    What: Stores SHA-256 token digests in ~/.soartv_gaming_agent_auth.json, syncs gaming_pc_token from
    SoarTV-launcher/config.properties, issues 5-minute 6-digit pairing codes to
    ~/.soartv_gaming_agent_pair_code.txt, and validates X-SoarTV-Token / Bearer headers in constant time.
    """

    def __init__(
        self,
        auth_path: Optional[Path] = None,
        pair_code_path: Optional[Path] = None,
        config_properties_path: Optional[Path] = None,
        dotenv_path: Optional[Path] = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        home = Path.home()
        self.auth_path: Path = auth_path or (home / ".soartv_gaming_agent_auth.json")
        self.pair_code_path: Path = pair_code_path or (home / ".soartv_gaming_agent_pair_code.txt")
        self.config_properties_path: Path = config_properties_path or (
            Path(__file__).resolve().parents[2] / "config.properties"
        )
        if dotenv_path is not None:
            self.dotenv_path: Path = dotenv_path
        else:
            default_env = Path(__file__).resolve().parents[3] / ".env"
            self.dotenv_path = default_env if default_env.is_file() else (Path.cwd() / ".env")
        self._explicit_dotenv = dotenv_path is not None
        self._explicit_cfg = config_properties_path is not None
        self._clock = clock
        self._lock = threading.Lock()
        self._token_entries: List[Dict[str, Any]] = []
        self._active_pair_code: Optional[str] = None
        self._pair_expires_at: float = 0.0
        self._load_persisted_tokens()
        self.sync_preseeded_token_from_config()

    @staticmethod
    def hash_token(raw_token: str) -> str:
        """Computes the lowercase SHA-256 hex digest of a raw client token."""
        return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

    def _load_persisted_tokens(self) -> None:
        """Loads SHA-256 token digests from ~/.soartv_gaming_agent_auth.json if present."""
        if not self.auth_path.is_file():
            return
        try:
            data = json.loads(self.auth_path.read_text(encoding="utf-8"))
            loaded: List[Dict[str, Any]] = []
            items = data.get("tokens", []) if isinstance(data, dict) else data
            if isinstance(items, list):
                for item in items:
                    if isinstance(item, dict) and isinstance(item.get("sha256"), str):
                        digest = item["sha256"].strip().lower()
                        if len(digest) == 64:
                            loaded.append(
                                {
                                    "sha256": digest,
                                    "deviceName": str(item.get("deviceName", "SoarTV")),
                                    "source": str(item.get("source", "paired")),
                                    "createdAt": float(item.get("createdAt", 0.0)),
                                }
                            )
                    elif isinstance(item, str) and len(item.strip()) == 64:
                        loaded.append(
                            {
                                "sha256": item.strip().lower(),
                                "deviceName": "SoarTV",
                                "source": "paired",
                                "createdAt": 0.0,
                            }
                        )
            with self._lock:
                self._token_entries = loaded
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            AgentLogger.warning("Failed reading auth store %s: %s", self.auth_path, type(exc).__name__)

    def _save_persisted_tokens_unlocked(self) -> None:
        """Persists ONLY SHA-256 token digests to ~/.soartv_gaming_agent_auth.json."""
        payload = {
            "version": 1,
            "tokens": list(self._token_entries),
        }
        try:
            self.auth_path.parent.mkdir(parents=True, exist_ok=True)
            serialized = json.dumps(payload, indent=2)
            self.auth_path.write_text(serialized, encoding="utf-8")
            try:
                os.chmod(self.auth_path, 0o600)
            except OSError as chmod_exc:
                AgentLogger.debug("chmod skipped on %s: %s", self.auth_path, chmod_exc)
        except OSError as exc:
            AgentLogger.warning("Failed saving auth store %s: %s", self.auth_path, type(exc).__name__)

    def _register_digest_unlocked(self, digest: str, device_name: str, source: str) -> None:
        clean_digest = digest.strip().lower()
        for existing in self._token_entries:
            if hmac.compare_digest(str(existing.get("sha256", "")), clean_digest):
                return
        self._token_entries.append(
            {
                "sha256": clean_digest,
                "deviceName": device_name[:MAX_DEVICE_NAME_LENGTH] or "SoarTV",
                "source": source,
                "createdAt": self._clock(),
            }
        )
        self._save_persisted_tokens_unlocked()

    def _extract_tokens_from_kv_file(self, path: Path, allowed_keys: Tuple[str, ...]) -> List[str]:
        if not path.is_file():
            return []
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except OSError as exc:
            AgentLogger.warning("Unable to inspect %s: %s", path.name, type(exc).__name__)
            return []
        found: List[str] = []
        allowed_lower = {k.lower() for k in allowed_keys}
        for raw_line in lines:
            stripped = raw_line.strip()
            if not stripped or stripped.startswith("#") or stripped.startswith("!"):
                continue
            if "=" not in stripped:
                continue
            key, val = stripped.split("=", 1)
            if key.strip().lower() in allowed_lower:
                clean_token = val.strip().strip('"').strip("'")
                if len(clean_token) >= MIN_CONFIG_TOKEN_LENGTH:
                    found.append(clean_token)
        return found

    def sync_preseeded_token_from_config(self) -> bool:
        """
        Inspects config.properties and .env for gaming_pc_token / GAMING_PC_TOKEN and stores SHA-256 digests.

        Why: Allows the owner's TV (GamingZone.java) and PC agent to authenticate seamlessly out of
        the box—even when config.properties or .env is populated after daemon startup—without ever
        logging or storing raw tokens in ~/.soartv_gaming_agent_auth.json.
        What: Parses gaming_pc_token / GAMING_PC_TOKEN, hashes with SHA-256, and persists digests.
        """
        registered_any = False
        cfg_candidates: List[Path] = [self.config_properties_path]
        if not self._explicit_cfg:
            fallback_cfg = Path.cwd() / "SoarTV-launcher" / "config.properties"
            if fallback_cfg not in cfg_candidates:
                cfg_candidates.append(fallback_cfg)

        for cfg_file in cfg_candidates:
            for tok in self._extract_tokens_from_kv_file(cfg_file, ("gaming_pc_token",)):
                digest = self.hash_token(tok)
                with self._lock:
                    self._register_digest_unlocked(digest, "SoarTV (config.properties)", "config")
                registered_any = True

        if self._explicit_dotenv or not self._explicit_cfg:
            for tok in self._extract_tokens_from_kv_file(
                self.dotenv_path, ("gaming_pc_token", "GAMING_PC_TOKEN")
            ):
                digest = self.hash_token(tok)
                with self._lock:
                    self._register_digest_unlocked(digest, "SoarTV (.env)", "dotenv")
                registered_any = True
        return registered_any

    def _matches_cached_digest(self, candidate_digest: str) -> bool:
        with self._lock:
            entries = list(self._token_entries)
        matched = False
        for entry in entries:
            stored = str(entry.get("sha256", ""))
            if hmac.compare_digest(stored, candidate_digest):
                matched = True
        return matched

    def verify_token(self, raw_token: str) -> bool:
        """
        Verifies a candidate client token against registered SHA-256 digests in constant time,
        hot-reloading config.properties / .env on cache miss so post-startup token updates work immediately.
        """
        candidate = (raw_token or "").strip()
        if not candidate:
            return False
        candidate_digest = self.hash_token(candidate)
        if self._matches_cached_digest(candidate_digest):
            return True
        if self.sync_preseeded_token_from_config():
            return self._matches_cached_digest(candidate_digest)
        return False

    @staticmethod
    def extract_request_token(headers: Mapping[str, str]) -> str:
        """
        Extracts the client auth token from X-SoarTV-Token (case-insensitive) or Authorization: Bearer.
        """
        for k, v in headers.items():
            if k.lower() == "x-soartv-token" and v and v.strip():
                return v.strip()
        for k, v in headers.items():
            if k.lower() == "authorization" and v:
                val = v.strip()
                if val.lower().startswith("bearer "):
                    return val[7:].strip()
        return ""

    def is_request_authenticated(self, headers: Mapping[str, str]) -> bool:
        """Returns True iff the HTTP headers carry a valid paired or pre-seeded SoarTV token."""
        token = self.extract_request_token(headers)
        return self.verify_token(token)

    def _clear_pair_code_file_unlocked(self) -> None:
        """Securely overwrites and deletes ~/.soartv_gaming_agent_pair_code.txt."""
        self._active_pair_code = None
        self._pair_expires_at = 0.0
        if self.pair_code_path.exists():
            try:
                self.pair_code_path.write_text("000000\n", encoding="utf-8")
                self.pair_code_path.unlink(missing_ok=True)
            except OSError as exc:
                AgentLogger.debug("Pair code file cleanup warning: %s", type(exc).__name__)

    def request_pairing_code(self) -> Dict[str, Any]:
        """
        Generates a cryptographic 6-digit pairing code (5-minute TTL) for local PC-to-TV pairing.

        Why: Enables secure one-time pairing from the TV without exposing the code in HTTP responses.
        What: Writes the 6-digit code to ~/.soartv_gaming_agent_pair_code.txt, logs a local prompt,
        and returns {"ok": True, "expiresInSeconds": 300}.
        """
        code = f"{secrets.randbelow(PAIR_CODE_SPAN) + PAIR_CODE_MIN_VALUE:06d}"
        now = self._clock()
        with self._lock:
            self._active_pair_code = code
            self._pair_expires_at = now + PAIR_CODE_TTL_SECONDS
            try:
                self.pair_code_path.parent.mkdir(parents=True, exist_ok=True)
                self.pair_code_path.write_text(f"{code}\n", encoding="utf-8")
                try:
                    os.chmod(self.pair_code_path, 0o600)
                except OSError as chmod_exc:
                    AgentLogger.debug("chmod skipped on pair code file: %s", chmod_exc)
            except OSError as exc:
                AgentLogger.warning("Failed writing pairing code file: %s", type(exc).__name__)

        AgentLogger.info("PAIRING CODE REQUESTED — Enter 6-digit code on SoarTV")
        return {"ok": True, "expiresInSeconds": PAIR_CODE_TTL_SECONDS}

    def verify_pairing_code(self, submitted_code: str, device_name: str = "SoarTV") -> Optional[str]:
        """
        Verifies a 6-digit pairing code and issues a new 64-hex-character client token on match.

        Why: Completes pairing and immediately destroys the one-time pairing code file.
        What: Returns the newly generated raw hex token (storing only its SHA-256 digest), or None.
        """
        clean_code = (submitted_code or "").strip()
        if not PAIR_CODE_PATTERN.fullmatch(clean_code):
            return None

        now = self._clock()
        with self._lock:
            if not self._active_pair_code or now > self._pair_expires_at:
                self._clear_pair_code_file_unlocked()
                return None
            if not hmac.compare_digest(self._active_pair_code, clean_code):
                return None

            self._clear_pair_code_file_unlocked()
            raw_token = secrets.token_hex(TOKEN_RANDOM_BYTES)
            digest = self.hash_token(raw_token)
            clean_device = (device_name or "SoarTV").strip()[:MAX_DEVICE_NAME_LENGTH] or "SoarTV"
            self._register_digest_unlocked(digest, clean_device, "paired")
            return raw_token

    @staticmethod
    def is_cross_origin_forbidden(headers: Mapping[str, str]) -> bool:
        """
        Detects cross-origin browser requests to block drive-by web attacks (SEC-06).

        Why: Malicious websites visited in a PC/phone browser could otherwise send fetch() requests
        to 127.0.0.1:8485 or <LAN_IP>:8485.
        What: Rejects Sec-Fetch-Site: cross-site and any Origin header other than localhost/127.0.0.1.
        """
        for k, v in headers.items():
            kl = k.lower()
            val = (v or "").strip()
            if kl == "sec-fetch-site" and val.lower() == "cross-site":
                return True
            if kl == "origin" and val:
                if not LOCALHOST_ORIGIN_PATTERN.fullmatch(val):
                    return True
        return False

    @staticmethod
    def resolve_allowed_cors_origin(headers: Mapping[str, str]) -> Optional[str]:
        """Returns the exact validated localhost Origin header if present, never wildcard '*'."""
        for k, v in headers.items():
            if k.lower() == "origin" and v:
                val = v.strip()
                if LOCALHOST_ORIGIN_PATTERN.fullmatch(val):
                    return val
        return None


# -----------------------------------------------------------------------------
# Native Win32 Keystroke Injector & Windows 11 Winlogon UIPI Fallback
# -----------------------------------------------------------------------------


class Win32InputInjector:
    """
    Injects validated alphanumeric Windows Hello PIN keystrokes via Win32 SendInput.

    Why: Eliminates PowerShell command-injection RCE (SEC-03), removes 1.5s PowerShell cold-start
    overhead (BP-08), removes HTTP handler thread sleep (BP-07), and handles Windows 11 Winlogon
    Secure Desktop UIPI isolation cleanly.
    What: Validates PINs against ^[A-Za-z0-9]{4,32}$, sends VK_SPACE + Unicode characters + VK_RETURN
    in-process via ctypes.windll.user32.SendInput, and falls back to the SYSTEM Scheduled Task
    'SoarTV-Winlogon-Unlock' via a user-ACL-protected ephemeral inbox file (never CLI args or PowerShell).
    """

    def __init__(
        self,
        ipc_dir: Optional[Path] = None,
        wake_pause_sec: float = LOCK_CURTAIN_DISMISS_DELAY_SEC,
        send_batch_fn: Optional[Callable[[List[WIN32_INPUT]], int]] = None,
        command_runner: Optional[Callable[[List[str], float], subprocess.CompletedProcess[str]]] = None,
    ) -> None:
        self.ipc_dir: Path = ipc_dir or (Path.home() / ".soartv_gaming_agent_ipc")
        self.wake_pause_sec: float = wake_pause_sec
        self._send_batch_fn = send_batch_fn
        self._has_custom_command_runner: bool = command_runner is not None
        self._command_runner = command_runner or self._default_command_runner

    @staticmethod
    def _default_command_runner(
        args: List[str], timeout: float
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            args,
            timeout=timeout,
            check=False,
            capture_output=True,
            text=True,
        )

    @staticmethod
    def validate_pin(pin: str) -> str:
        """
        Strictly validates that a Windows Hello PIN is 4 to 32 ASCII alphanumeric characters.

        Why: Blocks OS command injection and malformed input before any Win32 or IPC call occurs.
        What: Returns the validated PIN string or raises ValueError immediately.
        """
        if not isinstance(pin, str) or not PIN_VALIDATION_PATTERN.fullmatch(pin):
            raise ValueError("Invalid PIN: must match ^[A-Za-z0-9]{4,32}$.")
        return pin

    @staticmethod
    def _make_vk_event(vk_code: int, key_up: bool = False) -> WIN32_INPUT:
        ev = WIN32_INPUT()
        ev.type = INPUT_KEYBOARD
        ev.ki.wVk = vk_code
        ev.ki.wScan = 0
        ev.ki.dwFlags = KEYEVENTF_KEYUP if key_up else 0
        ev.ki.time = 0
        ev.ki.dwExtraInfo = 0
        return ev

    @staticmethod
    def _make_unicode_event(codepoint: int, key_up: bool = False) -> WIN32_INPUT:
        ev = WIN32_INPUT()
        ev.type = INPUT_KEYBOARD
        ev.ki.wVk = 0
        ev.ki.wScan = codepoint
        ev.ki.dwFlags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if key_up else 0)
        ev.ki.time = 0
        ev.ki.dwExtraInfo = 0
        return ev

    @classmethod
    def build_pin_input_events(cls, pin: str) -> Tuple[List[WIN32_INPUT], List[WIN32_INPUT]]:
        """
        Constructs the two Win32 INPUT batches (curtain-dismiss VK_SPACE, then Unicode PIN + VK_RETURN).

        Why: Separates pure event construction from Win32 syscalls for deterministic cross-OS testing.
        What: Returns (wake_events, pin_and_enter_events).
        """
        clean_pin = cls.validate_pin(pin)
        wake_events: List[WIN32_INPUT] = [
            cls._make_vk_event(VK_SPACE, key_up=False),
            cls._make_vk_event(VK_SPACE, key_up=True),
        ]
        pin_events: List[WIN32_INPUT] = []
        for ch in clean_pin:
            cp = ord(ch)
            pin_events.append(cls._make_unicode_event(cp, key_up=False))
            pin_events.append(cls._make_unicode_event(cp, key_up=True))
        pin_events.append(cls._make_vk_event(VK_RETURN, key_up=False))
        pin_events.append(cls._make_vk_event(VK_RETURN, key_up=True))
        return wake_events, pin_events

    @staticmethod
    def _attach_thread_to_active_desktop() -> Optional[int]:
        """
        Attempts to attach the current worker thread to the active input desktop (or Winlogon).

        Why: On Windows 11 lock screens, LogonUI.exe runs on the Winlogon desktop; calling
        OpenInputDesktop + SetThreadDesktop routes SendInput to the active desktop when permitted.
        What: Returns the opened HDESK handle (caller closes after SendInput) or None.
        """
        if sys.platform != "win32" or _is_automated_test_env():
            return None
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            u32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            u32.OpenInputDesktop.restype = wintypes.HANDLE
            u32.OpenDesktopW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            u32.OpenDesktopW.restype = wintypes.HANDLE
            u32.SetThreadDesktop.argtypes = [wintypes.HANDLE]
            u32.SetThreadDesktop.restype = wintypes.BOOL

            hdesk = u32.OpenInputDesktop(0, False, DESKTOP_INJECT_ACCESS)
            if not hdesk:
                hdesk = u32.OpenDesktopW("Winlogon", 0, False, DESKTOP_INJECT_ACCESS)
            if hdesk:
                u32.SetThreadDesktop(hdesk)
                return int(hdesk)
        except Exception as exc:
            AgentLogger.debug("Desktop attach skipped: %s", type(exc).__name__)
        return None

    @staticmethod
    def _close_desktop_handle(hdesk: Optional[int]) -> None:
        if sys.platform != "win32" or not hdesk or _is_automated_test_env():
            return
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            u32.CloseDesktop.argtypes = [wintypes.HANDLE]
            u32.CloseDesktop.restype = wintypes.BOOL
            u32.CloseDesktop(wintypes.HANDLE(hdesk))
        except Exception as exc:
            AgentLogger.debug("CloseDesktop skipped: %s", type(exc).__name__)

    @classmethod
    def _native_send_batch(cls, events: List[WIN32_INPUT]) -> int:
        """Executes user32.SendInput for a batch of WIN32_INPUT structures."""
        if sys.platform != "win32" or not events or _is_automated_test_env():
            return 0
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        u32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(WIN32_INPUT), ctypes.c_int]
        u32.SendInput.restype = wintypes.UINT
        arr_type = WIN32_INPUT * len(events)
        arr = arr_type(*events)
        return int(u32.SendInput(len(events), arr, ctypes.sizeof(WIN32_INPUT)))

    def _deliver_in_worker_thread(self, pin: str) -> bool:
        """Runs OpenInputDesktop + SetThreadDesktop + SendInput on a clean non-GUI worker thread."""
        wake_events, pin_events = self.build_pin_input_events(pin)
        send_fn = self._send_batch_fn or self._native_send_batch
        result: List[bool] = [False]

        def worker() -> None:
            hdesk1 = self._attach_thread_to_active_desktop() if self._send_batch_fn is None else None
            try:
                sent_wake = send_fn(wake_events)
                if sent_wake != len(wake_events):
                    return
                if self.wake_pause_sec > 0:
                    time.sleep(self.wake_pause_sec)
            finally:
                self._close_desktop_handle(hdesk1)

            hdesk2 = self._attach_thread_to_active_desktop() if self._send_batch_fn is None else None
            try:
                sent_pin = send_fn(pin_events)
                result[0] = sent_pin == len(pin_events)
            finally:
                self._close_desktop_handle(hdesk2)

        t = threading.Thread(target=worker, daemon=True, name="win32-pin-injector")
        t.start()
        t.join(timeout=max(1.0, self.wake_pause_sec + 0.5))
        return result[0]

    def _ensure_secure_ipc_dir(self) -> Path:
        """Creates the local IPC directory and restricts NTFS ACLs to Current User, SYSTEM, and Admins."""
        created = not self.ipc_dir.exists()
        self.ipc_dir.mkdir(parents=True, exist_ok=True)
        if created and sys.platform == "win32" and (
            self._has_custom_command_runner or not _is_automated_test_env()
        ):
            username = os.environ.get("USERNAME", "").strip()
            icacls_cmd = [
                "icacls.exe",
                str(self.ipc_dir),
                "/inheritance:r",
                "/grant:r",
                "*S-1-5-18:(OI)(CI)F",
                "*S-1-5-32-544:(OI)(CI)F",
            ]
            if username:
                icacls_cmd.append(f"{username}:(OI)(CI)F")
            try:
                self._command_runner(icacls_cmd, WIFI_CONFIG_CMD_TIMEOUT_SEC)
            except (OSError, subprocess.SubprocessError) as exc:
                AgentLogger.debug("icacls lockdown warning: %s", type(exc).__name__)
        return self.ipc_dir

    @staticmethod
    def _wipe_and_unlink(file_path: Path) -> None:
        if not file_path.exists():
            return
        try:
            file_path.write_bytes(b"\x00" * 128)
            file_path.unlink(missing_ok=True)
        except OSError as exc:
            AgentLogger.debug("IPC token wipe warning: %s", type(exc).__name__)

    def _trigger_uipi_scheduled_task_fallback(self, validated_pin: str) -> bool:
        """
        Triggers the pre-registered SYSTEM Scheduled Task 'SoarTV-Winlogon-Unlock' when UIPI blocks SendInput.

        Why: Windows 11 LogonUI.exe runs at SYSTEM integrity on the Winlogon desktop; UIPI blocks
        SendInput from medium-integrity user processes (returning 0).
        What: Writes the validated PIN to a short-lived user-ACL-protected inbox file and invokes
        schtasks.exe /Run /TN SoarTV-Winlogon-Unlock with zero CLI secret arguments and zero PowerShell.
        """
        if not self._has_custom_command_runner and _is_automated_test_env():
            return False
        ipc_folder = self._ensure_secure_ipc_dir()
        token_file = ipc_folder / "unlock_request.json"
        payload = {
            "pin": validated_pin,
            "expiresAt": time.time() + IPC_TOKEN_TTL_SECONDS,
        }
        try:
            token_file.write_text(json.dumps(payload), encoding="utf-8")
            proc = self._command_runner(
                ["schtasks.exe", "/Run", "/TN", UNLOCK_HELPER_TASK_NAME],
                HELPER_EXEC_TIMEOUT_SEC,
            )
            if proc.returncode != 0:
                self._wipe_and_unlink(token_file)
                return False

            if token_file.exists():
                timer = threading.Timer(
                    IPC_ASYNC_CLEANUP_SEC,
                    self._wipe_and_unlink,
                    args=(token_file,),
                )
                timer.daemon = True
                timer.start()
            return True
        except (OSError, subprocess.SubprocessError) as exc:
            self._wipe_and_unlink(token_file)
            AgentLogger.warning("UIPI helper fallback failed: %s", type(exc).__name__)
            return False

    def inject_pin(self, pin: str, allow_helper_fallback: bool = True) -> bool:
        """
        Validates and injects the alphanumeric PIN via Win32 SendInput (with optional UIPI fallback).
        """
        clean_pin = self.validate_pin(pin)
        if self._send_batch_fn is None and sys.platform != "win32":
            return False
        if self._deliver_in_worker_thread(clean_pin):
            return True
        if allow_helper_fallback:
            return self._trigger_uipi_scheduled_task_fallback(clean_pin)
        return False

    def run_unlock_from_stdin(self, stdin_stream: Optional[TextIO] = None) -> int:
        """
        Executes the '--unlock-from-stdin' helper mode (reading strictly from stdin or ACL inbox file).

        Why: Allows the SYSTEM Scheduled Task SoarTV-Winlogon-Unlock (or local pipe caller) to inject
        keystrokes onto the Winlogon desktop without ever passing the PIN on the command line.
        What: Returns exit code 0 on success, 2 on validation failure, or 1 on injection failure.
        """
        candidate_pin = ""
        token_file = self.ipc_dir / "unlock_request.json"
        try:
            stream = stdin_stream if stdin_stream is not None else sys.stdin
            if stream is not None and (stdin_stream is not None or not stream.isatty()):
                raw_line = stream.readline(128)
                if raw_line:
                    candidate_pin = raw_line.rstrip("\r\n")

            if not candidate_pin and token_file.is_file():
                try:
                    data = json.loads(token_file.read_text(encoding="utf-8"))
                    if float(data.get("expiresAt", 0.0)) >= time.time():
                        candidate_pin = str(data.get("pin", ""))
                finally:
                    self._wipe_and_unlink(token_file)

            validated = self.validate_pin(candidate_pin)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            self._wipe_and_unlink(token_file)
            AgentLogger.warning("Unlock helper rejected input: %s", type(exc).__name__)
            return 2

        ok = self.inject_pin(validated, allow_helper_fallback=False)
        return 0 if ok else 1

    def install_unlock_helper(self) -> Dict[str, Any]:
        """
        One-time Administrator setup ('--install-unlock-helper') registering SoarTV-Winlogon-Unlock.

        Why: Grants a locked-down SYSTEM Scheduled Task permission to type the validated PIN into
        Windows 11's Winlogon secure desktop when UIPI blocks standard user processes.
        What: Locks down the local IPC directory ACLs and registers the on-demand SYSTEM task.
        """
        if sys.platform != "win32":
            return {"ok": False, "error": "unsupported_platform"}
        try:
            is_admin = bool(ctypes.windll.shell32.IsUserAnAdmin())
        except Exception:
            is_admin = False
        if not is_admin:
            return {
                "ok": False,
                "isAdmin": False,
                "error": "admin_required",
                "message": "Run --install-unlock-helper from an Administrator terminal.",
            }

        ipc_folder = self._ensure_secure_ipc_dir()
        script_path = Path(__file__).resolve()
        task_cmd = f'"{sys.executable}" "{script_path}" --unlock-from-stdin --ipc-dir "{ipc_folder}"'
        proc = self._command_runner(
            [
                "schtasks.exe",
                "/Create",
                "/TN",
                UNLOCK_HELPER_TASK_NAME,
                "/TR",
                task_cmd,
                "/SC",
                "ONCE",
                "/ST",
                "00:00",
                "/RU",
                "SYSTEM",
                "/RL",
                "HIGHEST",
                "/F",
            ],
            WIFI_CONFIG_CMD_TIMEOUT_SEC,
        )
        ok = proc.returncode == 0
        return {
            "ok": ok,
            "isAdmin": True,
            "taskName": UNLOCK_HELPER_TASK_NAME,
            "ipcDir": str(ipc_folder),
        }


# -----------------------------------------------------------------------------
# In-Process Win32 System Probes (Zero CLI Subprocesses — PERF-10)
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class DisplayTargetDescriptor:
    """
    Immutable value object describing an attached display target discovered via Win32 CCD / GDI.

    Why: Decouples pure EDID hardware-ID filtering from live Win32 API calls so pytest can test
    all monitor combinations deterministically on any machine.
    What: Stores monitorDevicePath, friendly name, outputTechnology, availability, and preferred size.
    """

    device_path: str
    friendly_name: str
    output_technology: int
    target_available: bool = True
    preferred_width: int = 0
    preferred_height: int = 0


class Win32SystemProbes:
    """
    In-process Win32 ctypes probes replacing 72,000/day powershell/tasklist/sc.exe subprocess polls.

    Why: Spawning external CLI processes every 3.5s caused 15-25% idle CPU usage and prevented
    Modern Standby S0 deep C-states on the gaming laptop (PERF-10).
    What: Queries active displays via QueryDisplayConfig + DisplayConfigGetDeviceInfo (with
    EnumDisplayDevicesW fallback), session lock state via WTSQuerySessionInformationW (with
    Toolhelp32 / input-desktop fallback), and SunshineService via QueryServiceStatusEx (with
    local TCP 127.0.0.1:47990 fallback) in <0.5ms with zero subprocess spawns.
    """

    @classmethod
    def query_active_displays(cls) -> List[DisplayTargetDescriptor]:
        """Queries active attached displays via Win32 QueryDisplayConfig with EnumDisplayDevicesW fallback."""
        if sys.platform != "win32":
            return []
        ccd_targets = cls._query_displays_ccd()
        if ccd_targets is not None:
            return ccd_targets
        return cls._query_displays_gdi_fallback()

    @classmethod
    def _query_displays_ccd(cls) -> Optional[List[DisplayTargetDescriptor]]:
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            u32.GetDisplayConfigBufferSizes.argtypes = [
                ctypes.c_uint32,
                ctypes.POINTER(ctypes.c_uint32),
                ctypes.POINTER(ctypes.c_uint32),
            ]
            u32.GetDisplayConfigBufferSizes.restype = ctypes.c_long
            u32.QueryDisplayConfig.argtypes = [
                ctypes.c_uint32,
                ctypes.POINTER(ctypes.c_uint32),
                ctypes.POINTER(DISPLAYCONFIG_PATH_INFO),
                ctypes.POINTER(ctypes.c_uint32),
                ctypes.POINTER(DISPLAYCONFIG_MODE_INFO),
                ctypes.c_void_p,
            ]
            u32.QueryDisplayConfig.restype = ctypes.c_long
            u32.DisplayConfigGetDeviceInfo.argtypes = [
                ctypes.POINTER(DISPLAYCONFIG_DEVICE_INFO_HEADER)
            ]
            u32.DisplayConfigGetDeviceInfo.restype = ctypes.c_long

            num_paths = ctypes.c_uint32(0)
            num_modes = ctypes.c_uint32(0)
            rc = u32.GetDisplayConfigBufferSizes(
                QDC_ONLY_ACTIVE_PATHS, ctypes.byref(num_paths), ctypes.byref(num_modes)
            )
            if rc != ERROR_SUCCESS:
                return None
            if num_paths.value == 0:
                return []

            paths = (DISPLAYCONFIG_PATH_INFO * num_paths.value)()
            modes = (DISPLAYCONFIG_MODE_INFO * max(1, num_modes.value))()
            rc = u32.QueryDisplayConfig(
                QDC_ONLY_ACTIVE_PATHS,
                ctypes.byref(num_paths),
                paths,
                ctypes.byref(num_modes),
                modes,
                None,
            )
            if rc != ERROR_SUCCESS:
                return None

            results: List[DisplayTargetDescriptor] = []
            seen_keys: Set[Tuple[int, int, int]] = set()
            for idx in range(num_paths.value):
                tinfo = paths[idx].targetInfo
                dedup_key = (
                    int(tinfo.adapterId.LowPart),
                    int(tinfo.adapterId.HighPart),
                    int(tinfo.id),
                )
                if dedup_key in seen_keys:
                    continue
                seen_keys.add(dedup_key)

                name_pkt = DISPLAYCONFIG_TARGET_DEVICE_NAME()
                name_pkt.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME
                name_pkt.header.size = ctypes.sizeof(DISPLAYCONFIG_TARGET_DEVICE_NAME)
                name_pkt.header.adapterId = tinfo.adapterId
                name_pkt.header.id = tinfo.id
                rc_name = u32.DisplayConfigGetDeviceInfo(ctypes.byref(name_pkt.header))

                pref_w = 0
                pref_h = 0
                pref_pkt = DISPLAYCONFIG_TARGET_PREFERRED_MODE()
                pref_pkt.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_PREFERRED_MODE
                pref_pkt.header.size = ctypes.sizeof(DISPLAYCONFIG_TARGET_PREFERRED_MODE)
                pref_pkt.header.adapterId = tinfo.adapterId
                pref_pkt.header.id = tinfo.id
                if u32.DisplayConfigGetDeviceInfo(ctypes.byref(pref_pkt.header)) == ERROR_SUCCESS:
                    pref_w = int(pref_pkt.width)
                    pref_h = int(pref_pkt.height)

                dev_path = name_pkt.monitorDevicePath if rc_name == ERROR_SUCCESS else ""
                friendly = name_pkt.monitorFriendlyDeviceName if rc_name == ERROR_SUCCESS else ""
                tech = (
                    int(name_pkt.outputTechnology)
                    if rc_name == ERROR_SUCCESS and int(name_pkt.outputTechnology) != 0
                    else int(tinfo.outputTechnology)
                )
                results.append(
                    DisplayTargetDescriptor(
                        device_path=dev_path,
                        friendly_name=friendly,
                        output_technology=tech,
                        target_available=bool(tinfo.targetAvailable),
                        preferred_width=pref_w,
                        preferred_height=pref_h,
                    )
                )
            return results
        except Exception as exc:
            AgentLogger.debug("QueryDisplayConfig failed, falling back to EnumDisplayDevicesW: %s", exc)
            return None

    @classmethod
    def _query_displays_gdi_fallback(cls) -> List[DisplayTargetDescriptor]:
        if sys.platform != "win32":
            return []
        results: List[DisplayTargetDescriptor] = []
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            u32.EnumDisplayDevicesW.argtypes = [
                wintypes.LPCWSTR,
                wintypes.DWORD,
                ctypes.POINTER(DISPLAY_DEVICEW),
                wintypes.DWORD,
            ]
            u32.EnumDisplayDevicesW.restype = wintypes.BOOL

            adapter_idx = 0
            while True:
                adapter = DISPLAY_DEVICEW()
                adapter.cb = ctypes.sizeof(DISPLAY_DEVICEW)
                if not u32.EnumDisplayDevicesW(None, adapter_idx, ctypes.byref(adapter), 0):
                    break
                adapter_idx += 1
                if not (adapter.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP):
                    continue
                mon_idx = 0
                while True:
                    mon = DISPLAY_DEVICEW()
                    mon.cb = ctypes.sizeof(DISPLAY_DEVICEW)
                    if not u32.EnumDisplayDevicesW(
                        adapter.DeviceName,
                        mon_idx,
                        ctypes.byref(mon),
                        EDD_GET_DEVICE_INTERFACE_NAME,
                    ):
                        break
                    mon_idx += 1
                    if not (mon.StateFlags & DISPLAY_DEVICE_ACTIVE):
                        continue
                    results.append(
                        DisplayTargetDescriptor(
                            device_path=mon.DeviceID,
                            friendly_name=mon.DeviceString,
                            output_technology=0,
                            target_available=True,
                        )
                    )
        except Exception as exc:
            AgentLogger.debug("EnumDisplayDevicesW fallback error: %s", type(exc).__name__)
        return results

    @classmethod
    def _query_wts_session_lock_flag(cls) -> Optional[int]:
        """Queries WTSSessionInfoEx SessionFlags (0 = locked, 1 = unlocked on Windows 8+/11)."""
        if sys.platform != "win32":
            return None
        wts = ctypes.WinDLL("wtsapi32", use_last_error=True)
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.WTSGetActiveConsoleSessionId.argtypes = []
        k32.WTSGetActiveConsoleSessionId.restype = wintypes.DWORD
        wts.WTSQuerySessionInformationW.argtypes = [
            wintypes.HANDLE,
            wintypes.DWORD,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_void_p),
            ctypes.POINTER(wintypes.DWORD),
        ]
        wts.WTSQuerySessionInformationW.restype = wintypes.BOOL
        wts.WTSFreeMemory.argtypes = [ctypes.c_void_p]
        wts.WTSFreeMemory.restype = None

        session_id = int(k32.WTSGetActiveConsoleSessionId())
        if session_id == WTS_INVALID_SESSION_ID:
            return None

        buf = ctypes.c_void_p()
        bytes_returned = wintypes.DWORD(0)
        ok = wts.WTSQuerySessionInformationW(
            wintypes.HANDLE(WTS_CURRENT_SERVER_HANDLE),
            wintypes.DWORD(session_id),
            WTS_SESSION_INFO_EX_CLASS,
            ctypes.byref(buf),
            ctypes.byref(bytes_returned),
        )
        if not ok or not buf.value:
            return None
        try:
            if bytes_returned.value < ctypes.sizeof(WTSINFOEXW):
                return None
            info = WTSINFOEXW.from_address(buf.value)
            if int(info.Level) != 1:
                return None
            flags = int(info.Data.SessionFlags)
            if flags in (WTS_SESSIONSTATE_LOCK, WTS_SESSIONSTATE_UNLOCK):
                return flags
            return None
        finally:
            wts.WTSFreeMemory(buf)

    @classmethod
    def snapshot_process_map(cls) -> Dict[int, str]:
        """
        Captures a mapping of {pid: lowercase_exe_name} in-process via CreateToolhelp32Snapshot.

        Why: Enables zero-subprocess process existence and window-owner checks (PERF-10).
        What: Returns a dictionary of active Win32 process IDs to executable filenames.
        """
        if sys.platform != "win32":
            return {}
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        k32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
        k32.Process32FirstW.restype = wintypes.BOOL
        k32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
        k32.Process32NextW.restype = wintypes.BOOL
        k32.CloseHandle.argtypes = [wintypes.HANDLE]
        k32.CloseHandle.restype = wintypes.BOOL

        invalid_handle = ctypes.c_void_p(-1).value
        snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if not snap or int(snap) == invalid_handle:
            return {}
        procs: Dict[int, str] = {}
        try:
            entry = PROCESSENTRY32W()
            entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
            has_proc = k32.Process32FirstW(snap, ctypes.byref(entry))
            while has_proc:
                procs[int(entry.th32ProcessID)] = entry.szExeFile.lower()
                has_proc = k32.Process32NextW(snap, ctypes.byref(entry))
            return procs
        finally:
            k32.CloseHandle(snap)

    @classmethod
    def snapshot_process_entries(cls) -> List[Tuple[int, int, str]]:
        """
        Captures active processes as [(pid, ppid, lowercase_exe_name), ...] via Toolhelp32.

        Why: Enables recursive process tree traversal to find child helper processes
        spawned by games (crash reporters, CEF renderers, audio engines) for clean shutdown.
        What: Returns a list of (pid, ppid, exe_name) tuples.
        """
        if sys.platform != "win32":
            return []
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        k32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
        k32.Process32FirstW.restype = wintypes.BOOL
        k32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(PROCESSENTRY32W)]
        k32.Process32NextW.restype = wintypes.BOOL
        k32.CloseHandle.argtypes = [wintypes.HANDLE]
        k32.CloseHandle.restype = wintypes.BOOL

        invalid_handle = ctypes.c_void_p(-1).value
        snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if not snap or int(snap) == invalid_handle:
            return []
        entries: List[Tuple[int, int, str]] = []
        try:
            entry = PROCESSENTRY32W()
            entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
            has_proc = k32.Process32FirstW(snap, ctypes.byref(entry))
            while has_proc:
                entries.append(
                    (
                        int(entry.th32ProcessID),
                        int(entry.th32ParentProcessID),
                        entry.szExeFile.lower(),
                    )
                )
                has_proc = k32.Process32NextW(snap, ctypes.byref(entry))
            return entries
        finally:
            k32.CloseHandle(snap)

    @classmethod
    def snapshot_process_names(cls) -> Set[str]:
        """Returns the set of lowercase running executable filenames via Toolhelp32."""
        return set(cls.snapshot_process_map().values())

    @classmethod
    def is_process_running(cls, exe_name: str) -> bool:
        """Returns True if exe_name (case-insensitive) is currently running in the active session."""
        target = exe_name.strip().lower()
        if not target:
            return False
        return target in cls.snapshot_process_map().values()

    @classmethod
    def _is_logonui_process_present(cls) -> bool:
        """Checks for LogonUI.exe in-process via CreateToolhelp32Snapshot (zero tasklist.exe)."""
        return cls.is_process_running("logonui.exe")

    @classmethod
    def _is_input_desktop_winlogon(cls) -> bool:
        """Checks whether the active input desktop is named 'Winlogon' via OpenInputDesktop."""
        if sys.platform != "win32":
            return False
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            u32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            u32.OpenInputDesktop.restype = wintypes.HANDLE
            u32.GetUserObjectInformationW.argtypes = [
                wintypes.HANDLE,
                ctypes.c_int,
                ctypes.c_void_p,
                wintypes.DWORD,
                ctypes.POINTER(wintypes.DWORD),
            ]
            u32.GetUserObjectInformationW.restype = wintypes.BOOL
            u32.CloseDesktop.argtypes = [wintypes.HANDLE]
            u32.CloseDesktop.restype = wintypes.BOOL

            hdesk = u32.OpenInputDesktop(0, False, DESKTOP_SWITCHDESKTOP)
            if not hdesk:
                return False
            try:
                name_buf = ctypes.create_unicode_buffer(64)
                needed = wintypes.DWORD(0)
                if u32.GetUserObjectInformationW(
                    hdesk, UOI_NAME, name_buf, ctypes.sizeof(name_buf), ctypes.byref(needed)
                ):
                    return name_buf.value.strip().lower() == "winlogon"
                return False
            finally:
                u32.CloseDesktop(hdesk)
        except Exception:
            return False

    @classmethod
    def is_session_locked(cls) -> bool:
        """
        Determines whether the active Windows console session is locked without spawning tasklist.exe.
        """
        if sys.platform != "win32":
            return False
        try:
            wts_flag = cls._query_wts_session_lock_flag()
            if wts_flag is not None:
                return wts_flag == WTS_SESSIONSTATE_LOCK
        except Exception as exc:
            AgentLogger.debug("WTS lock check fallback triggered: %s", type(exc).__name__)

        return cls._is_input_desktop_winlogon() or cls._is_logonui_process_present()

    @classmethod
    def _query_service_state(cls, service_name: str) -> Optional[int]:
        """Queries Windows SCM for service_name's dwCurrentState in-process via advapi32."""
        if sys.platform != "win32":
            return None
        adv = ctypes.WinDLL("advapi32", use_last_error=True)
        adv.OpenSCManagerW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
        adv.OpenSCManagerW.restype = wintypes.HANDLE
        adv.OpenServiceW.argtypes = [wintypes.HANDLE, wintypes.LPCWSTR, wintypes.DWORD]
        adv.OpenServiceW.restype = wintypes.HANDLE
        adv.QueryServiceStatusEx.argtypes = [
            wintypes.HANDLE,
            ctypes.c_int,
            ctypes.c_void_p,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
        ]
        adv.QueryServiceStatusEx.restype = wintypes.BOOL
        adv.CloseServiceHandle.argtypes = [wintypes.HANDLE]
        adv.CloseServiceHandle.restype = wintypes.BOOL

        scm = adv.OpenSCManagerW(None, None, SC_MANAGER_CONNECT)
        if not scm:
            return None
        try:
            svc = adv.OpenServiceW(scm, service_name, SERVICE_QUERY_STATUS)
            if not svc:
                return None
            try:
                status = SERVICE_STATUS_PROCESS()
                bytes_needed = wintypes.DWORD(0)
                ok = adv.QueryServiceStatusEx(
                    svc,
                    SC_STATUS_PROCESS_INFO,
                    ctypes.byref(status),
                    ctypes.sizeof(status),
                    ctypes.byref(bytes_needed),
                )
                if not ok:
                    return None
                return int(status.dwCurrentState)
            finally:
                adv.CloseServiceHandle(svc)
        finally:
            adv.CloseServiceHandle(scm)

    @staticmethod
    def _can_connect_local_port(host: str, port: int, timeout: float = SUNSHINE_SOCKET_TIMEOUT_SEC) -> bool:
        try:
            with socket.create_connection((host, port), timeout=timeout):
                return True
        except OSError:
            return False

    @classmethod
    def is_sunshine_running(cls) -> bool:
        """
        Checks if SunshineService is SERVICE_RUNNING via SCM, with fast local TCP 127.0.0.1:47990 fallback.
        """
        if sys.platform != "win32":
            return False
        try:
            state = cls._query_service_state("SunshineService")
            if state == SERVICE_RUNNING:
                return True
        except Exception as exc:
            AgentLogger.debug("SCM SunshineService probe fallback: %s", type(exc).__name__)
        return cls._can_connect_local_port("127.0.0.1", SUNSHINE_HTTPS_PORT)


# -----------------------------------------------------------------------------
# Wireless Wake-on-WLAN Configurator (--enable-wifi-wake)
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class WifiWakeStatus:
    """
    Structured diagnostic result returned by WifiWakeConfigurator.enable_wifi_wake().

    Why: Gives users and automated setup tools clear verification of whether Wake-on-WLAN
    (*WakeOnMagicPacket, *WakeOnPattern, and powercfg /deviceenablewake) succeeded.
    What: Captures elevation state, adapter name, individual setting flags, and messages.
    """

    ok: bool
    is_admin: bool
    adapter_subkey: str
    adapter_description: str
    magic_packet_enabled: bool
    pattern_match_enabled: bool
    powercfg_wake_enabled: bool
    messages: Tuple[str, ...]

    def to_dict(self) -> Dict[str, Any]:
        """Serializes the Wi-Fi wake configuration status to a JSON-compatible dictionary."""
        return {
            "ok": self.ok,
            "isAdmin": self.is_admin,
            "adapterSubkey": self.adapter_subkey,
            "adapterDescription": self.adapter_description,
            "magicPacketEnabled": self.magic_packet_enabled,
            "patternMatchEnabled": self.pattern_match_enabled,
            "powercfgWakeEnabled": self.powercfg_wake_enabled,
            "messages": list(self.messages),
        }


class WifiWakeConfigurator:
    """
    Configures Windows Wi-Fi network adapter Wake-on-Magic-Packet for wireless wake from sleep.

    Why: Modern laptops often ship with Wi-Fi Wake-on-Magic-Packet disabled in NDIS registry
    properties or un-armed in Windows power management, preventing wireless Wake-on-LAN over Wi-Fi.
    What: When invoked once with Administrator privileges via --enable-wifi-wake, enables
    *WakeOnMagicPacket=1, *WakeOnPattern=1, and runs powercfg /deviceenablewake <Wi-Fi Adapter>.
    """

    WIFI_KEYWORDS: Tuple[str, ...] = ("wi-fi", "wifi", "wireless", "802.11", "wlan", "centrino", "killer")
    EXCLUDED_ADAPTER_KEYWORDS: Tuple[str, ...] = ("virtual", "direct", "bluetooth", "wan miniport", "hyper-v", "loopback")

    def __init__(
        self,
        is_admin_fn: Optional[Callable[[], bool]] = None,
        discover_wifi_adapter_fn: Optional[Callable[[], Optional[Tuple[str, str]]]] = None,
        set_adapter_reg_value_fn: Optional[Callable[[str, str, str], bool]] = None,
        command_runner: Optional[Callable[[List[str], float], subprocess.CompletedProcess[str]]] = None,
    ) -> None:
        self._is_admin_fn = is_admin_fn or self._default_is_admin
        self._discover_fn = discover_wifi_adapter_fn or self._discover_wifi_adapter_from_registry
        self._set_reg_fn = set_adapter_reg_value_fn or self._set_adapter_registry_string
        self._command_runner = command_runner or Win32InputInjector._default_command_runner

    @staticmethod
    def _default_is_admin() -> bool:
        if sys.platform != "win32":
            return False
        try:
            return bool(ctypes.windll.shell32.IsUserAnAdmin())
        except Exception:
            return False

    @classmethod
    def is_physical_wifi_description(cls, description: str) -> bool:
        """Pure helper checking whether a network adapter DriverDesc represents physical Wi-Fi."""
        lower = (description or "").strip().lower()
        if not lower:
            return False
        if any(ex in lower for ex in cls.EXCLUDED_ADAPTER_KEYWORDS):
            return False
        return any(kw in lower for kw in cls.WIFI_KEYWORDS)

    @classmethod
    def _discover_wifi_adapter_from_registry(cls) -> Optional[Tuple[str, str]]:
        if sys.platform != "win32":
            return None
        import winreg

        try:
            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                NETWORK_ADAPTER_CLASS_REG_PATH,
                0,
                winreg.KEY_READ,
            ) as class_key:
                idx = 0
                while True:
                    try:
                        subkey_name = winreg.EnumKey(class_key, idx)
                        idx += 1
                    except OSError:
                        break
                    if not re.fullmatch(r"\d{4}", subkey_name):
                        continue
                    try:
                        with winreg.OpenKey(class_key, subkey_name, 0, winreg.KEY_READ) as dev_key:
                            desc, _ = winreg.QueryValueEx(dev_key, "DriverDesc")
                            if cls.is_physical_wifi_description(str(desc)):
                                return (subkey_name, str(desc).strip())
                    except OSError:
                        continue
        except OSError as exc:
            AgentLogger.warning("Wi-Fi registry discovery failed: %s", type(exc).__name__)
        return None

    @staticmethod
    def _set_adapter_registry_string(subkey_name: str, value_name: str, value_data: str) -> bool:
        if sys.platform != "win32":
            return False
        import winreg

        full_subkey = f"{NETWORK_ADAPTER_CLASS_REG_PATH}\\{subkey_name}"
        try:
            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                full_subkey,
                0,
                winreg.KEY_SET_VALUE,
            ) as dev_key:
                winreg.SetValueEx(dev_key, value_name, 0, winreg.REG_SZ, value_data)
            return True
        except OSError as exc:
            AgentLogger.warning("Failed setting %s on %s: %s", value_name, subkey_name, type(exc).__name__)
            return False

    def enable_wifi_wake(self) -> WifiWakeStatus:
        """
        Enables *WakeOnMagicPacket, *WakeOnPattern, and powercfg /deviceenablewake on the Wi-Fi NIC.
        """
        if not self._is_admin_fn():
            return WifiWakeStatus(
                ok=False,
                is_admin=False,
                adapter_subkey="",
                adapter_description="",
                magic_packet_enabled=False,
                pattern_match_enabled=False,
                powercfg_wake_enabled=False,
                messages=(
                    "Administrator privileges are required. Re-run in an elevated Administrator shell with --enable-wifi-wake.",
                ),
            )

        discovered = self._discover_fn()
        if not discovered:
            return WifiWakeStatus(
                ok=False,
                is_admin=True,
                adapter_subkey="",
                adapter_description="",
                magic_packet_enabled=False,
                pattern_match_enabled=False,
                powercfg_wake_enabled=False,
                messages=("No physical Wi-Fi network adapter was found in the Windows NDIS class registry.",),
            )

        subkey, desc = discovered
        notes: List[str] = [f"Discovered Wi-Fi adapter [{subkey}]: {desc}"]

        magic_ok = self._set_reg_fn(subkey, "*WakeOnMagicPacket", "1")
        pattern_ok = self._set_reg_fn(subkey, "*WakeOnPattern", "1")
        notes.append(f"*WakeOnMagicPacket=1: {'configured' if magic_ok else 'failed'}")
        notes.append(f"*WakeOnPattern=1: {'configured' if pattern_ok else 'failed'}")

        powercfg_ok = False
        try:
            proc_enable = self._command_runner(
                ["powercfg.exe", "/deviceenablewake", desc],
                WIFI_CONFIG_CMD_TIMEOUT_SEC,
            )
            if proc_enable.returncode == 0:
                powercfg_ok = True
            else:
                # Match exact powercfg wake_programmable name if DriverDesc has a suffix (#2, etc.)
                proc_list = self._command_runner(
                    ["powercfg.exe", "/devicequery", "wake_programmable"],
                    WIFI_CONFIG_CMD_TIMEOUT_SEC,
                )
                for line in (proc_list.stdout or "").splitlines():
                    candidate = line.strip()
                    if candidate and self.is_physical_wifi_description(candidate):
                        retry = self._command_runner(
                            ["powercfg.exe", "/deviceenablewake", candidate],
                            WIFI_CONFIG_CMD_TIMEOUT_SEC,
                        )
                        if retry.returncode == 0:
                            powercfg_ok = True
                            desc = candidate
                            break
            notes.append(f"powercfg /deviceenablewake '{desc}': {'enabled' if powercfg_ok else 'failed'}")
        except (OSError, subprocess.SubprocessError) as exc:
            notes.append(f"powercfg execution error: {type(exc).__name__}")

        overall_ok = magic_ok and pattern_ok and powercfg_ok
        return WifiWakeStatus(
            ok=overall_ok,
            is_admin=True,
            adapter_subkey=subkey,
            adapter_description=desc,
            magic_packet_enabled=magic_ok,
            pattern_match_enabled=pattern_ok,
            powercfg_wake_enabled=powercfg_ok,
            messages=tuple(notes),
        )


# -----------------------------------------------------------------------------
# Game Catalog, Sunshine Configuration, Display Mode, Session, and Power Domain
# -----------------------------------------------------------------------------


@dataclass(frozen=True)
class GameEntry:
    """
    Immutable value object representing a launchable game or desktop target on the gaming laptop.

    Why: Encapsulates metadata needed by both Sunshine (apps.json) and the SoarTV Launcher UI.
    What: Holds the unique game_id, display title, category ('system', 'steam', 'epic', 'standalone'),
    launch command, working directory, artwork URL, hidden state, and optional primary executable name.
    """

    game_id: str
    title: str
    category: str
    launch_cmd: str
    working_dir: str
    poster_url: str = ""
    hidden: bool = False
    exe_name: str = ""

    def to_dict(self) -> Dict[str, Any]:
        """Serializes the GameEntry into a JSON-compatible dictionary for the TV Launcher."""
        return {
            "id": self.game_id,
            "title": self.title,
            "category": self.category,
            "cmd": self.launch_cmd,
            "workingDir": self.working_dir,
            "posterUrl": self.poster_url,
            "hidden": self.hidden,
            "exeName": self.exe_name,
        }


def _read_repo_dotenv_value(var_name: str) -> str:
    """Reads an optional variable from OS env first, then git-ignored repo root .env."""
    env_val = os.environ.get(var_name, "").strip()
    if env_val:
        return env_val
    if _is_automated_test_env():
        return ""
    dotenv_path = Path(__file__).resolve().parents[3] / ".env"
    if not dotenv_path.is_file():
        return ""
    try:
        for raw in dotenv_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            if k.strip() == var_name:
                return v.strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def resolve_default_steam_root() -> Path:
    """Resolves the primary Steam installation root via SOARTV_STEAM_ROOT, Windows Registry, or standard paths."""
    configured = _read_repo_dotenv_value("SOARTV_STEAM_ROOT")
    if configured:
        return Path(configured)
    if sys.platform == "win32" and not _is_automated_test_env():
        try:
            import winreg  # type: ignore

            for hive, subkey, val_name in (
                (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam", "InstallPath"),
                (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
            ):
                try:
                    with winreg.OpenKey(hive, subkey) as k:
                        val, _ = winreg.QueryValueEx(k, val_name)
                        if val and Path(str(val)).is_dir():
                            return Path(str(val))
                except OSError:
                    continue
        except Exception:
            pass
    pf86 = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam"
    if pf86.is_dir():
        return pf86
    for drv in ("C", "D", "E", "F"):
        cand = Path(f"{drv}:\\Games\\Steam")
        if cand.is_dir():
            return cand
    return pf86


def resolve_default_games_dirs() -> List[Path]:
    """Resolves standalone/library game folders from SOARTV_GAMES_DIRS or existing <Drive>:\\Games roots."""
    configured = _read_repo_dotenv_value("SOARTV_GAMES_DIRS")
    if configured:
        parts = [p.strip() for p in re.split(r"[;,]", configured) if p.strip()]
        if parts:
            return [Path(p) for p in parts]
    if not _is_automated_test_env():
        found = [Path(f"{drv}:\\Games") for drv in ("C", "D", "E", "F") if Path(f"{drv}:\\Games").is_dir()]
        if found:
            return found
    return [Path(r"C:\Games")]


class GameCatalogScanner:
    """
    Scans Steam libraries, Heroic/Legendary, Epic Games Launcher manifests, and standalone game folders.

    Why: Users store games across Steam libraryfolders.vdf, Heroic, Epic Games Launcher, and one or more
    custom standalone game directories (configured via SOARTV_GAMES_DIRS or auto-detected across drives).
    What: Parses Steam .acf manifests (including all VDF library folders), Heroic/Legendary configs,
    Epic .item manifests, deduplicates launcher-managed install folders, and discovers playable .exe binaries.
    """

    def __init__(
        self,
        games_root: Optional[Path] = None,
        steam_root: Optional[Path] = None,
        epic_manifests_dir: Optional[Path] = None,
        heroic_config_dir: Optional[Path] = None,
        legendary_config_dir: Optional[Path] = None,
        extra_games_dirs: Optional[Sequence[Path]] = None,
    ) -> None:
        if games_root is not None:
            self.games_root = games_root
            self.games_dirs: List[Path] = [games_root] + list(extra_games_dirs or ())
        else:
            resolved_dirs = resolve_default_games_dirs()
            self.games_root = resolved_dirs[0]
            self.games_dirs = resolved_dirs + list(extra_games_dirs or ())
        self.steam_root = steam_root if steam_root is not None else resolve_default_steam_root()
        if epic_manifests_dir is not None:
            self.epic_manifests_dir: Optional[Path] = epic_manifests_dir
        elif _is_automated_test_env():
            self.epic_manifests_dir = None
        else:
            self.epic_manifests_dir = DEFAULT_EPIC_MANIFESTS_DIR

        if heroic_config_dir is not None:
            self.heroic_config_dir: Optional[Path] = heroic_config_dir
        elif _is_automated_test_env():
            self.heroic_config_dir = None
        else:
            self.heroic_config_dir = DEFAULT_HEROIC_CONFIG_DIR

        if legendary_config_dir is not None:
            self.legendary_config_dir: Optional[Path] = legendary_config_dir
        elif _is_automated_test_env():
            self.legendary_config_dir = None
        else:
            self.legendary_config_dir = DEFAULT_LEGENDARY_CONFIG_DIR

    @staticmethod
    def parse_steam_acf(acf_text: str) -> Optional[GameEntry]:
        """
        Parses a Valve Steam appmanifest_*.acf file into a GameEntry.

        Why: Extracts the Steam AppID and human-readable game title without requiring Steam Web API keys.
        What: Uses regex matching on 'appid' and 'name' fields and builds a steam://rungameid/<id> URI.
        """
        app_match = re.search(r'"appid"\s+"(\d+)"', acf_text)
        name_match = re.search(r'"name"\s+"([^"]+)"', acf_text)
        if not app_match or not name_match:
            return None
        appid = app_match.group(1).strip()
        name = name_match.group(1).strip()
        if appid == STEAM_REDISTRIBUTABLE_APPID or "Steamworks Common Redistributables" in name:
            return None
        poster = f"https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/{appid}/library_600x900.jpg"
        return GameEntry(
            game_id=f"steam_{appid}",
            title=name,
            category="steam",
            launch_cmd=f"steam://rungameid/{appid}",
            working_dir="",
            poster_url=poster,
        )

    @staticmethod
    def ensure_library_folder_in_vdf(vdf_text: str, extra_library_path: str) -> str:
        """
        Ensures an additional Steam library directory (such as C:\\Games) is registered in libraryfolders.vdf.

        Why: When games are stored in a custom steamapps folder outside the default Steam install folder,
        registering that parent folder in libraryfolders.vdf lets Steam launch them directly via steam://rungameid/.
        What: Checks if the escaped path is already present; if not, appends a new numbered block.
        """
        escaped = extra_library_path.replace("\\", "\\\\")
        quoted_target = f'"{escaped}"'.lower()
        if quoted_target in vdf_text.lower():
            return vdf_text
        indices = [int(m) for m in re.findall(r'"(\d+)"\s*\{', vdf_text)]
        next_idx = max(indices) + 1 if indices else 1
        insertion = (
            f'\t"{next_idx}"\n'
            f"\t{{\n"
            f'\t\t"path"\t\t"{escaped}"\n'
            f'\t\t"label"\t\t"SoarTV Games"\n'
            f'\t\t"apps"\n'
            f"\t\t{{\n"
            f"\t\t}}\n"
            f"\t}}\n"
        )
        close_pos = vdf_text.rfind("}")
        if close_pos == -1:
            return vdf_text
        return vdf_text[:close_pos] + insertion + vdf_text[close_pos:]

    def sync_steam_library_vdf(self) -> bool:
        """Updates Steam's steamapps/libraryfolders.vdf if a custom games_root/steamapps folder exists."""
        vdf_path = self.steam_root / "steamapps" / "libraryfolders.vdf"
        parent_steamapps = self.games_root / "steamapps"
        if not vdf_path.exists() or not parent_steamapps.exists():
            return False
        try:
            original = vdf_path.read_text(encoding="utf-8", errors="ignore")
            updated = self.ensure_library_folder_in_vdf(original, str(self.games_root))
            if updated != original:
                vdf_path.write_text(updated, encoding="utf-8")
                return True
        except OSError as exc:
            AgentLogger.warning("Failed updating Steam libraryfolders.vdf: %s", type(exc).__name__)
        return False

    def discover_games(self) -> List[GameEntry]:
        """
        Discovers all playable Steam and standalone games under games_root.

        Why: Populates both Sunshine and the SoarTV Gaming Zone UI automatically while skipping
        save-data-only folders (e.g., 'data A Little To The Left') and helper/crash executables.
        What: Returns built-in system targets followed by sorted Steam and standalone games.
        """
        entries: List[GameEntry] = [
            GameEntry(
                game_id="steam_bigpicture",
                title="Steam Big Picture",
                category="system",
                launch_cmd="steam://open/bigpicture",
                working_dir="",
            ),
            GameEntry(
                game_id="desktop",
                title="Windows Desktop",
                category="system",
                launch_cmd="explorer.exe",
                working_dir="",
            ),
        ]
        seen_ids: Set[str] = {e.game_id for e in entries}

        steamapps_dirs: List[Path] = []
        for gdir in getattr(self, "games_dirs", [self.games_root]):
            steamapps_dirs.append(gdir / "steamapps")
        steamapps_dirs.append(self.steam_root / "steamapps")
        vdf_file = self.steam_root / "steamapps" / "libraryfolders.vdf"
        if vdf_file.is_file():
            try:
                vdf_txt = vdf_file.read_text(encoding="utf-8", errors="ignore")
                for m_lib in re.finditer(r'"path"\s+"([^"]+)"', vdf_txt):
                    lib_dir = Path(m_lib.group(1).replace("\\\\", "\\")) / "steamapps"
                    if lib_dir not in steamapps_dirs:
                        steamapps_dirs.append(lib_dir)
            except OSError:
                pass
        steam_entries: List[GameEntry] = []
        for sdir in steamapps_dirs:
            if not sdir.is_dir():
                continue
            for acf_file in sorted(sdir.glob("appmanifest_*.acf")):
                try:
                    parsed = self.parse_steam_acf(
                        acf_file.read_text(encoding="utf-8", errors="ignore")
                    )
                    if parsed and parsed.game_id not in seen_ids:
                        seen_ids.add(parsed.game_id)
                        steam_entries.append(parsed)
                except OSError as exc:
                    AgentLogger.debug("Skipping unreadable ACF %s: %s", acf_file.name, exc)

        steam_entries.sort(key=lambda g: g.title.lower())
        entries.extend(steam_entries)

        # Discover Heroic Games Launcher (Epic/GOG/Amazon/Sideload) & optional standalone Legendary FIRST
        # so games registered in Heroic/Legendary take priority over official Epic Launcher manifests.
        heroic_entries: List[GameEntry] = []
        managed_install_dirs: Set[str] = set()
        claimed_epic_apps: Set[str] = set()
        if (self.heroic_config_dir is not None and self.heroic_config_dir.is_dir()) or (
            self.legendary_config_dir is not None and self.legendary_config_dir.is_dir()
        ):
            for parsed_h in HeroicPreparationManager.discover_heroic_games(
                heroic_config_dir=self.heroic_config_dir,
                legendary_config_dir=self.legendary_config_dir,
            ):
                if parsed_h.game_id not in seen_ids:
                    seen_ids.add(parsed_h.game_id)
                    heroic_entries.append(parsed_h)
                    if parsed_h.working_dir:
                        norm_dir = self._normalize_path_key(Path(parsed_h.working_dir))
                        if norm_dir:
                            managed_install_dirs.add(norm_dir)
                    claimed_app = HeroicPreparationManager.extract_epic_app_name(parsed_h)
                    if claimed_app:
                        claimed_epic_apps.add(claimed_app.lower())

        heroic_entries.sort(key=lambda g: g.title.lower())
        entries.extend(heroic_entries)

        epic_entries: List[GameEntry] = []
        if self.epic_manifests_dir is not None and self.epic_manifests_dir.is_dir():
            for parsed_epic in EpicPreparationManager.discover_epic_games(self.epic_manifests_dir):
                epic_app_slug = parsed_epic.game_id.removeprefix("epic_").lower()
                if epic_app_slug and epic_app_slug in claimed_epic_apps:
                    continue
                if parsed_epic.working_dir:
                    norm_dir = self._normalize_path_key(Path(parsed_epic.working_dir))
                    if norm_dir and norm_dir in managed_install_dirs:
                        continue
                if parsed_epic.game_id not in seen_ids:
                    seen_ids.add(parsed_epic.game_id)
                    epic_entries.append(parsed_epic)
                    if parsed_epic.working_dir:
                        norm_dir = self._normalize_path_key(Path(parsed_epic.working_dir))
                        if norm_dir:
                            managed_install_dirs.add(norm_dir)

        epic_entries.sort(key=lambda g: g.title.lower())
        entries.extend(epic_entries)

        standalone_entries: List[GameEntry] = []
        for scan_root in getattr(self, "games_dirs", [self.games_root]):
            if not scan_root.is_dir():
                continue
            for child in sorted(scan_root.iterdir()):
                if not child.is_dir():
                    continue
                if self._normalize_path_key(child) in managed_install_dirs:
                    continue
                folder_lower = child.name.lower()
                if folder_lower in IGNORED_LAUNCHER_FOLDER_NAMES:
                    continue
                if folder_lower.startswith("data "):
                    exes = self._find_candidate_exes(child)
                    if not exes:
                        continue

                if folder_lower in KNOWN_STANDALONE_OVERRIDES:
                    friendly_title, rel_exe = KNOWN_STANDALONE_OVERRIDES[folder_lower]
                    exe_path = child / rel_exe
                    if exe_path.is_file():
                        gid = "exe_" + re.sub(r"[^a-z0-9]+", "_", folder_lower).strip("_")
                        if gid not in seen_ids:
                            seen_ids.add(gid)
                            standalone_entries.append(
                                GameEntry(
                                    game_id=gid,
                                    title=friendly_title,
                                    category="standalone",
                                    launch_cmd=str(exe_path),
                                    working_dir=str(exe_path.parent),
                                )
                            )
                        continue

                exes = self._find_candidate_exes(child)
                if not exes:
                    continue
                best_exe = exes[0]
                friendly = re.sub(r"([a-z])([A-Z])", r"\1 \2", child.name).strip()
                gid = "exe_" + re.sub(r"[^a-z0-9]+", "_", folder_lower).strip("_")
                if gid not in seen_ids:
                    seen_ids.add(gid)
                    standalone_entries.append(
                        GameEntry(
                            game_id=gid,
                            title=friendly,
                            category="standalone",
                            launch_cmd=str(best_exe),
                            working_dir=str(best_exe.parent),
                        )
                    )

        standalone_entries.sort(key=lambda g: g.title.lower())
        entries.extend(standalone_entries)
        return entries

    @staticmethod
    def _normalize_path_key(path: Path) -> str:
        """Returns a normalized lowercase filesystem key for cross-launcher directory deduplication."""
        try:
            return os.path.normcase(os.path.normpath(str(path.resolve())))
        except OSError:
            return os.path.normcase(os.path.normpath(str(path)))

    @staticmethod
    def _find_candidate_exes(folder: Path) -> List[Path]:
        """Finds playable .exe files up to depth 2 inside a game folder, excluding crash/setup tools."""
        candidates: List[Path] = []
        try:
            for root, dirs, files in os.walk(folder):
                rel_depth = len(Path(root).relative_to(folder).parts)
                if rel_depth > MAX_EXE_SCAN_DEPTH:
                    dirs[:] = []
                    continue
                for fname in files:
                    if not fname.lower().endswith(".exe"):
                        continue
                    lower_name = fname.lower()
                    if any(pat in lower_name for pat in IGNORED_EXE_PATTERNS):
                        continue
                    candidates.append(Path(root) / fname)
        except OSError as exc:
            AgentLogger.debug("EXE walk skipped in %s: %s", folder, exc)
            return []
        candidates.sort(key=lambda p: (len(p.relative_to(folder).parts), -p.stat().st_size))
        return candidates


class SunshineConfigManager:
    """
    Manages Sunshine's apps.json configuration so all Steam and standalone games appear in Moonlight.

    Why: Allows Moonlight on the TV to see and direct-launch every game discovered on the laptop.
    What: Merges GameEntry objects into Sunshine's apps.json without duplicating existing apps.
    """

    DEFAULT_PATHS: Tuple[Path, ...] = (
        Path(r"C:\Program Files\Sunshine\config\apps.json"),
        Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "apps.json",
        Path(__file__).resolve().parent / ".state" / "sunshine_apps.json",
    )
    DEFAULT_STATE_PATHS: Tuple[Path, ...] = (
        Path(r"C:\Program Files\Sunshine\config\sunshine_state.json"),
        Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine_state.json",
    )

    @classmethod
    def read_host_uuid(cls, state_path: Optional[Path] = None) -> str:
        """
        Reads Sunshine's unique host UUID ('root.uniqueid') from sunshine_state.json.

        Why: Moonlight's ShortcutTrampoline requires the paired Sunshine host UUID (or host Name,
        never both) to jump directly into a game stream with zero intermediate menus.
        What: Returns the UUID string if readable, or '' when unavailable.
        """
        candidates: Sequence[Path] = (state_path,) if state_path is not None else cls.DEFAULT_STATE_PATHS
        for path in candidates:
            if path is None or not path.is_file():
                continue
            try:
                parsed = json.loads(path.read_text(encoding="utf-8", errors="ignore"))
                root = parsed.get("root", {}) if isinstance(parsed, dict) else {}
                uid = str(root.get("uniqueid", "")).strip() if isinstance(root, dict) else ""
                if uid:
                    return uid
            except (OSError, json.JSONDecodeError) as exc:
                AgentLogger.debug("Failed reading Sunshine state %s: %s", path, type(exc).__name__)
        return ""

    @staticmethod
    def read_host_name() -> str:
        """Returns the local PC hostname used as Sunshine's default ComputerName in Moonlight."""
        try:
            return socket.gethostname().strip()
        except OSError:
            return ""

    @staticmethod
    def resolve_sunshine_app_name(game: GameEntry) -> str:
        """
        Maps a SoarTV GameEntry to its exact application 'name' inside Sunshine's apps.json.

        Why: Sunshine names the desktop stream 'Desktop' while SoarTV displays 'Windows Desktop';
        ShortcutTrampoline matches NvApp.appName.equals(AppName) case-sensitively.
        What: Returns 'Desktop', 'Steam Big Picture', or game.title.
        """
        if game.game_id == "desktop":
            return "Desktop"
        if game.game_id == "steam_bigpicture":
            return "Steam Big Picture"
        return game.title.strip()

    @staticmethod
    def merge_apps_config(
        existing_config: Dict[str, Any], games: List[GameEntry]
    ) -> Dict[str, Any]:
        """
        Merges discovered GameEntry items into a Sunshine apps.json structure.

        Why: Preserves existing user customizations in Sunshine while adding any newly discovered games.
        What: Returns a new dictionary with 'env' and 'apps' populated.
        """
        result: Dict[str, Any] = {
            "env": dict(existing_config.get("env", {})),
            "apps": [dict(a) for a in existing_config.get("apps", []) if isinstance(a, dict)],
        }
        existing_names: Set[str] = {
            str(a.get("name", "")).strip().lower() for a in result["apps"]
        }

        for game in games:
            if game.game_id == "desktop":
                if "desktop" not in existing_names:
                    result["apps"].append({"name": "Desktop", "output": ""})
                    existing_names.add("desktop")
                continue

            key = game.title.strip().lower()
            if key in existing_names:
                continue

            cmd_lower = game.launch_cmd.lower()
            if (
                cmd_lower.startswith("steam://")
                or cmd_lower.startswith("com.epicgames.launcher://")
                or cmd_lower.startswith("heroic://")
            ):
                app_entry: Dict[str, Any] = {
                    "name": game.title,
                    "detached": [f'cmd /C start "" "{game.launch_cmd}"'],
                }
            elif cmd_lower.startswith("legendary://launch/"):
                leg_app = game.launch_cmd.split("/", 3)[-1].strip()
                app_entry = {
                    "name": game.title,
                    "detached": [f'legendary launch "{leg_app}"'],
                }
            else:
                app_entry = {
                    "name": game.title,
                    "cmd": f'"{game.launch_cmd}"',
                    "working-dir": game.working_dir,
                }
            result["apps"].append(app_entry)
            existing_names.add(key)

        return result

    @classmethod
    def sync_to_disk(cls, games: List[GameEntry], target_path: Optional[Path] = None) -> Optional[Path]:
        """Writes the merged apps.json to Sunshine's config folder if installed."""
        paths = [target_path] if target_path else list(cls.DEFAULT_PATHS)
        for path in paths:
            if path is None:
                continue
            if path.parent.exists():
                existing: Dict[str, Any] = {"env": {}, "apps": []}
                if path.exists():
                    try:
                        existing = json.loads(path.read_text(encoding="utf-8"))
                    except (OSError, json.JSONDecodeError) as exc:
                        AgentLogger.debug("Resetting unreadable Sunshine config %s: %s", path, type(exc).__name__)
                        existing = {"env": {}, "apps": []}
                merged = cls.merge_apps_config(existing, games)
                try:
                    path.write_text(json.dumps(merged, indent=2), encoding="utf-8")
                    return path
                except OSError as exc:
                    AgentLogger.debug("Cannot write Sunshine config %s: %s", path, type(exc).__name__)
                    continue
        return None

    @staticmethod
    def parse_credentials(keys_text: str) -> Optional[Tuple[str, str]]:
        """
        Extracts the Sunshine (username, password) tuple from keys.txt content without logging secrets.
        """
        match = re.search(
            r"\*\*\s*Sunshine:.*?\*\s*username:\s*\r?\n([^\r\n]+).*?\*\s*password:\s*\r?\n([^\r\n]+)",
            keys_text,
            re.IGNORECASE | re.DOTALL,
        )
        if not match:
            return None
        return (match.group(1).strip(), match.group(2).strip())

    @classmethod
    def load_credentials_from_repo(cls) -> Optional[Tuple[str, str]]:
        """Reads Sunshine credentials from the git-ignored keys.txt in the repository root."""
        candidates = [
            Path(__file__).resolve().parents[3] / "keys.txt",
            Path.cwd() / "keys.txt",
        ]
        for p in candidates:
            if p.is_file():
                try:
                    creds = cls.parse_credentials(p.read_text(encoding="utf-8", errors="ignore"))
                    if creds:
                        return creds
                except OSError as exc:
                    AgentLogger.debug("Skipping unreadable keys file: %s", type(exc).__name__)
                    continue
        return None

    @classmethod
    def _sunshine_api_request(
        cls, method: str, path: str, payload: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """Calls Sunshine's local HTTPS REST API on port 47990 using Basic Auth from keys.txt."""
        creds = cls.load_credentials_from_repo()
        if not creds:
            return None
        user, pw = creds
        token = base64.b64encode(f"{user}:{pw}".encode("utf-8")).decode("ascii")
        url = f"https://127.0.0.1:{SUNSHINE_HTTPS_PORT}{path}"
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Basic {token}")
        req.add_header("Content-Type", "application/json")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=SUNSHINE_API_TIMEOUT_SEC) as resp:
                raw = resp.read().decode("utf-8", errors="ignore")
                return json.loads(raw) if raw else {"ok": True}
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            AgentLogger.debug("Sunshine API %s %s unavailable: %s", method, path, type(exc).__name__)
            return None

    @classmethod
    def build_sunshine_app_payload(
        cls,
        game: GameEntry,
        steam_exe: Optional[Path] = None,
        index: int = -1,
        heroic_exe: Optional[Path] = None,
        legendary_exe: Optional[Path] = None,
    ) -> Dict[str, Any]:
        """
        Builds a Sunshine /api/apps JSON payload that avoids Sunshine v2026's broken '--' steam:// rewrite
        and launches Heroic silently with '--no-gui'.
        """
        app_name = cls.resolve_sunshine_app_name(game)
        cmd_val = ""
        work_dir = ""
        detached_list: List[str] = []
        cmd_lower = game.launch_cmd.strip().lower()

        if game.game_id == "steam_bigpicture" or cmd_lower == "steam://open/bigpicture":
            if steam_exe is not None:
                detached_list = [f'"{steam_exe}" -bigpicture']
            else:
                detached_list = ['cmd.exe /C start "" "steam://open/bigpicture"']
        elif game.launch_cmd.startswith("steam://rungameid/"):
            appid = game.launch_cmd.rsplit("/", 1)[-1].strip()
            if steam_exe is not None and appid.isdigit():
                detached_list = [f'"{steam_exe}" -applaunch {appid}']
            else:
                detached_list = [f'cmd.exe /C start "" "{game.launch_cmd}"']
        elif cmd_lower.startswith("steam://"):
            if steam_exe is not None:
                detached_list = [f'"{steam_exe}" "{game.launch_cmd}"']
            else:
                detached_list = [f'cmd.exe /C start "" "{game.launch_cmd}"']
        elif cmd_lower.startswith("heroic://"):
            if heroic_exe is not None:
                detached_list = [f'"{heroic_exe}" --no-gui "{game.launch_cmd}"']
            else:
                detached_list = [f'cmd.exe /C start "" "{game.launch_cmd}"']
        elif cmd_lower.startswith("legendary://launch/"):
            leg_app = game.launch_cmd.split("/", 3)[-1].strip()
            if legendary_exe is not None:
                detached_list = [f'"{legendary_exe}" launch "{leg_app}"']
            else:
                detached_list = [f'legendary launch "{leg_app}"']
        elif cmd_lower.startswith("com.epicgames.launcher://"):
            detached_list = [f'cmd.exe /C start "" "{game.launch_cmd}"']
        else:
            cmd_val = f'"{game.launch_cmd}"'
            work_dir = game.working_dir

        return {
            "index": index,
            "name": app_name,
            "output": "",
            "cmd": cmd_val,
            "working-dir": work_dir,
            "detached": detached_list,
            "prep-cmd": [],
            "auto-detach": False,
            "wait-all": True,
            "image-path": "",
        }

    @staticmethod
    def needs_sunshine_app_update(
        existing_app: Mapping[str, Any], desired_payload: Mapping[str, Any]
    ) -> bool:
        """
        Determines whether an existing Sunshine /api/apps entry has a stale or broken launch command.
        """
        existing_cmd = str(existing_app.get("cmd", "") or "").strip()
        desired_cmd = str(desired_payload.get("cmd", "") or "").strip()
        existing_detached_raw = existing_app.get("detached")
        existing_detached = (
            [str(x).strip() for x in existing_detached_raw]
            if isinstance(existing_detached_raw, list)
            else []
        )
        desired_detached = [str(x).strip() for x in desired_payload.get("detached", [])]

        if existing_cmd.lower().startswith("steam://"):
            return True
        if any(item.lower().startswith("steam://") for item in existing_detached):
            return True
        if existing_cmd != desired_cmd or existing_detached != desired_detached:
            return True
        return False

    @classmethod
    def sync_via_api(
        cls,
        games: List[GameEntry],
        steam_root: Optional[Path] = None,
    ) -> int:
        """
        Pushes discovered games into Sunshine via https://127.0.0.1:47990/api/apps and upgrades stale entries.

        Why: SunshineService runs as SYSTEM and manages C:\\Program Files\\Sunshine\\config\\apps.json
        through its HTTPS API without needing UAC elevation prompts.
        What: Fetches existing Sunshine apps, repairs any broken 'steam://' commands in-place by index,
        and appends newly discovered titles with index=-1.
        """
        current = cls._sunshine_api_request("GET", "/api/apps")
        if not current or not isinstance(current.get("apps"), list):
            return 0

        resolved_steam_exe = SteamPreparationManager.resolve_steam_exe(
            steam_root if steam_root is not None else resolve_default_steam_root()
        )
        resolved_heroic_exe = HeroicPreparationManager.resolve_heroic_exe()
        resolved_legendary_exe = HeroicPreparationManager.resolve_legendary_exe()
        existing_by_name: Dict[str, Tuple[int, Dict[str, Any]]] = {}
        for idx, item in enumerate(current["apps"]):
            if isinstance(item, dict):
                nm = str(item.get("name", "")).strip().lower()
                if nm and nm not in existing_by_name:
                    existing_by_name[nm] = (idx, item)

        changed = 0
        for g in games:
            if g.game_id == "desktop":
                continue
            target_name = cls.resolve_sunshine_app_name(g)
            key = target_name.strip().lower()
            existing_pair = existing_by_name.get(key)
            if existing_pair is not None:
                existing_idx, existing_dict = existing_pair
                desired = cls.build_sunshine_app_payload(
                    g,
                    steam_exe=resolved_steam_exe,
                    index=existing_idx,
                    heroic_exe=resolved_heroic_exe,
                    legendary_exe=resolved_legendary_exe,
                )
                if existing_dict.get("image-path"):
                    desired["image-path"] = str(existing_dict["image-path"])
                if not cls.needs_sunshine_app_update(existing_dict, desired):
                    continue
                res = cls._sunshine_api_request("POST", "/api/apps", desired)
                if res is not None:
                    changed += 1
                    existing_by_name[key] = (existing_idx, desired)
                continue

            if g.game_id == "steam_bigpicture":
                continue
            payload = cls.build_sunshine_app_payload(
                g,
                steam_exe=resolved_steam_exe,
                index=-1,
                heroic_exe=resolved_heroic_exe,
                legendary_exe=resolved_legendary_exe,
            )
            res = cls._sunshine_api_request("POST", "/api/apps", payload)
            if res is not None:
                changed += 1
                existing_by_name[key] = (len(existing_by_name), payload)
        return changed

    @classmethod
    def close_active_app(cls) -> bool:
        """
        Closes any active or stuck detached application session inside Sunshine via POST /api/apps/close.

        Why: If a prior session exited early and left Sunshine believing an app is still running,
        subsequent Moonlight launches resume the bare desktop instead of spawning the newly clicked game.
        What: Sends POST /api/apps/close and returns True when Sunshine responds.
        """
        res = cls._sunshine_api_request("POST", "/api/apps/close", {})
        return res is not None

    @classmethod
    def restart_service(cls) -> bool:
        """
        Requests a clean non-elevated Sunshine restart via POST /api/restart.

        Why: Re-initializes Sunshine's UDP/qWAVE streaming sockets cleanly (e.g. after network driver
        filter changes that otherwise trigger WSASendMsg 10022 packet errors).
        What: Sends POST /api/restart and returns True when accepted.
        """
        res = cls._sunshine_api_request("POST", "/api/restart", {})
        return res is not None

    @classmethod
    def submit_pairing_pin(cls, pin: str, device_name: str = "Xiaomi MiTV") -> bool:
        """
        Submits a 4-digit Moonlight pairing PIN to Sunshine's /api/pin endpoint.

        Why: Sunshine v2026+ requires the 32-hex-character 'pairing_id' returned by GET /api/pin
        when completing a pending Moonlight pairing request.
        What: Fetches pending pairings via GET /api/pin and submits the PIN with pairing_id.
        """
        clean_pin = pin.strip()
        if not SUNSHINE_PIN_PATTERN.fullmatch(clean_pin):
            return False
        payload: Dict[str, Any] = {"pin": clean_pin, "name": device_name}
        pending = cls._sunshine_api_request("GET", "/api/pin")
        if pending and isinstance(pending.get("pairings"), list) and pending["pairings"]:
            first = pending["pairings"][0]
            if isinstance(first, dict) and first.get("id"):
                payload["pairing_id"] = str(first["id"])
        res = cls._sunshine_api_request("POST", "/api/pin", payload)
        return bool(res and (res.get("status") is True or str(res.get("status", "")).lower() == "true"))

    @classmethod
    def submit_pairing_pin_with_retry(
        cls,
        pin: str,
        device_name: str = "SoarTV",
        timeout_sec: float = 6.0,
        poll_interval_sec: float = 0.12,
    ) -> bool:
        """
        Retries submit_pairing_pin until Sunshine registers the in-flight /pair request and accepts the PIN.

        Why: GameStream /pair?phrase=getservercert blocks on Sunshine :47984 while Sunshine registers the
        pending pairing session; retrying every 120ms guarantees automatic headless pairing succeeds.
        What: Polls submit_pairing_pin up to timeout_sec and returns True as soon as Sunshine returns status=true.
        """
        clean_pin = (pin or "").strip()
        if not SUNSHINE_PIN_PATTERN.fullmatch(clean_pin):
            return False
        deadline = time.monotonic() + max(0.25, timeout_sec)
        while True:
            if cls.submit_pairing_pin(clean_pin, device_name=device_name):
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(poll_interval_sec)

    OPTIMAL_OPTIMUS_SUNSHINE_CONFIG: Dict[str, Any] = {
        "gamepad_driver": "vigembus",
        "encoder": "quicksync",
        "hevc_mode": 1,
        "av1_mode": 1,
        "minimum_fps_target": 60,
        "fec_percentage": 20,
        "qsv_preset": "veryfast",
        "qsv_coder": "cabac",
        "nvenc_preset": 1,
        "nvenc_twopass": "quarter_res",
        "nvenc_spatial_aq": "disabled",
        "nvenc_realtime_hags": "enabled",
        "dd_configuration_option": "verify_only",
        "dd_resolution_option": "disabled",
        "dd_refresh_rate_option": "auto",
        "dd_config_revert_on_disconnect": "enabled",
    }

    @classmethod
    def ensure_optimal_sunshine_conf(cls, restart_on_change: bool = False) -> bool:
        """
        Applies optimal zero-stutter NVIDIA Optimus + Intel UHD scanout parameters via Sunshine /api/config.

        Why: On Optimus laptops where scanout displays attach to Intel UHD (0x8086:0x9BC4),
        ``verify_only`` activates Sunshine's ``dd_refresh_rate_option = auto`` without detaching
        secondary monitors (avoiding minimized-window migration), ``qsv_coder = cabac`` improves
        H.264 QSV compression efficiency over CAVLC, ``hevc_mode = 1`` avoids unsupported hevc_qsv
        probe errors on Gen9.5 UHD, and ``minimum_fps_target = 60`` locks 60fps floor.
        What: Merges OPTIMAL_OPTIMUS_SUNSHINE_CONFIG into GET /api/config, posts via POST /api/config,
        and optionally restarts Sunshine when changed and ``restart_on_change`` is True.
        """
        current = cls._sunshine_api_request("GET", "/api/config")
        merged: Dict[str, Any] = dict(current) if isinstance(current, dict) else {}
        changed = False
        for k, v in cls.OPTIMAL_OPTIMUS_SUNSHINE_CONFIG.items():
            if str(merged.get(k, "")) != str(v):
                merged[k] = v
                changed = True
        if not changed and current is not None:
            return True
        res = cls._sunshine_api_request("POST", "/api/config", merged)
        if res is None:
            return False
        if changed and restart_on_change:
            cls.restart_service()
        return True


class SteamPreparationManager:
    """
    Prepares Steam for hands-free wireless streaming without pre-launching games on PC speakers
    or blocking on Steam's 'Grab a controller and kick back!' desktop interstitial popup.

    Why: When Moonlight launches a controller-recommended Steam game before any gamepad button has
    been pressed, Windows initially has 0 XInput pads attached, causing Steam to pause on a desktop
    popup ('Deck_ConfiguratorInterstitialApps_GamepadRecommended') unless already acknowledged.
    What: Pre-marks discovered Steam AppIDs in localconfig.vdf when Steam is closed, pre-warms
    'steam.exe -silent' in the background, and runs a bounded Win32 popup watcher during stream start.
    """

    INTERSTITIAL_KEY: str = "Deck_ConfiguratorInterstitialApps_GamepadRecommended"
    _EXISTING_KEY_RE: re.Pattern[str] = re.compile(
        r'("Deck_ConfiguratorInterstitialApps_GamepadRecommended"\s+")\[([^\]]*)\](")'
    )
    _STEAM_BLOCK_RE: re.Pattern[str] = re.compile(r'(\n(\t*)"Steam"\s*\n\2\{\n)')

    @staticmethod
    def extract_steam_app_ids(games: Sequence[GameEntry]) -> List[int]:
        """Extracts sorted positive integer Steam AppIDs from discovered steam://rungameid/<id> entries."""
        ids: Set[int] = set()
        for g in games:
            if g.launch_cmd.startswith("steam://rungameid/"):
                tail = g.launch_cmd.rsplit("/", 1)[-1].strip()
                if tail.isdigit() and int(tail) > 0:
                    ids.add(int(tail))
        return sorted(ids)

    @classmethod
    def suppress_controller_interstitial_in_vdf(
        cls, vdf_text: str, app_ids: Sequence[int]
    ) -> str:
        """
        Pure VDF transformer ensuring all app_ids are listed in Steam's controller interstitial ack array.

        Why: Steam skips the 'Grab a controller and kick back!' modal for every AppID recorded in
        Deck_ConfiguratorInterstitialApps_GamepadRecommended inside userdata/*/config/localconfig.vdf.
        What: Merges app_ids with any existing acknowledged IDs and returns the updated VDF text.
        """
        clean_ids: Set[int] = {int(x) for x in app_ids if int(x) > 0}
        if not clean_ids:
            return vdf_text

        match = cls._EXISTING_KEY_RE.search(vdf_text)
        if match:
            existing_raw = match.group(2)
            for tok in existing_raw.split(","):
                tok_clean = tok.strip()
                if tok_clean.isdigit() and int(tok_clean) > 0:
                    clean_ids.add(int(tok_clean))
            merged_str = ",".join(str(x) for x in sorted(clean_ids))
            replacement = f"{match.group(1)}[{merged_str}]{match.group(3)}"
            return vdf_text[: match.start()] + replacement + vdf_text[match.end() :]

        merged_str = ",".join(str(x) for x in sorted(clean_ids))
        block_match = cls._STEAM_BLOCK_RE.search(vdf_text)
        if block_match:
            indent = block_match.group(2) + "\t"
            injected = f'{block_match.group(1)}{indent}"{cls.INTERSTITIAL_KEY}"\t\t"[{merged_str}]"\n'
            return vdf_text[: block_match.start()] + injected + vdf_text[block_match.end() :]

        return vdf_text

    @staticmethod
    def is_steam_interstitial_popup(
        process_name: str,
        class_name: str,
        title: str,
        width: int,
        height: int,
    ) -> bool:
        """
        Pure classifier identifying Steam's modal launch interstitial popup window (e.g. 717x500 SDL_app)
        while rejecting the main Steam browser window (>= 980px), Friends list, and non-Steam processes.
        """
        proc_lower = process_name.strip().lower()
        if proc_lower not in ("steamwebhelper.exe", "steam.exe"):
            return False
        if class_name.strip() not in ("SDL_app", "vguiPopupWindow"):
            return False
        title_lower = title.strip().lower()
        if any(excluded in title_lower for excluded in ("friends list", "settings", "big picture")):
            return False
        if not (STEAM_INTERSTITIAL_MIN_WIDTH <= width <= STEAM_INTERSTITIAL_MAX_WIDTH):
            return False
        if not (STEAM_INTERSTITIAL_MIN_HEIGHT <= height <= STEAM_INTERSTITIAL_MAX_HEIGHT):
            return False
        return True

    @staticmethod
    def resolve_steam_exe(steam_root: Path) -> Optional[Path]:
        """Resolves the installed steam.exe path across configured, registry, and standard locations."""
        candidates: List[Path] = [
            steam_root / "steam.exe",
            resolve_default_steam_root() / "steam.exe",
            Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam" / "steam.exe",
            Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Steam" / "steam.exe",
        ]
        for gdir in resolve_default_games_dirs():
            candidates.append(gdir / "Steam" / "steam.exe")
        for candidate in candidates:
            if candidate.is_file():
                return candidate
        return None

    @classmethod
    def _update_localconfig_files_when_steam_closed(
        cls, steam_exe: Path, app_ids: Sequence[int]
    ) -> int:
        """Updates userdata/*/config/localconfig.vdf safely ONLY while steam.exe is not running."""
        if not app_ids:
            return 0
        userdata_dir = steam_exe.parent / "userdata"
        if not userdata_dir.is_dir():
            return 0
        updated_files = 0
        for cfg_path in userdata_dir.glob("*/config/localconfig.vdf"):
            try:
                original = cfg_path.read_text(encoding="utf-8", errors="ignore")
                transformed = cls.suppress_controller_interstitial_in_vdf(original, app_ids)
                if transformed != original:
                    cfg_path.write_text(transformed, encoding="utf-8")
                    updated_files += 1
            except OSError as exc:
                AgentLogger.debug("Skipping localconfig.vdf update %s: %s", cfg_path, type(exc).__name__)
        return updated_files

    @classmethod
    def _dismiss_steam_interstitial_once(cls, dismissed_hwnds: Set[int]) -> bool:
        """Scans visible Win32 top-level windows and sends VK_RETURN to any new Steam interstitial modal."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        proc_map = Win32SystemProbes.snapshot_process_map()
        if not proc_map:
            return False

        u32 = ctypes.WinDLL("user32", use_last_error=True)
        enum_proc_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        hit: List[bool] = [False]

        def enum_cb(hwnd: int, _lparam: int) -> bool:
            if not hwnd or hwnd in dismissed_hwnds or not u32.IsWindowVisible(hwnd):
                return True
            pid = wintypes.DWORD(0)
            u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            proc_name = proc_map.get(int(pid.value), "")
            if proc_name not in ("steamwebhelper.exe", "steam.exe"):
                return True

            cls_buf = ctypes.create_unicode_buffer(128)
            u32.GetClassNameW(hwnd, cls_buf, 128)
            title_len = int(u32.GetWindowTextLengthW(hwnd))
            title_buf = ctypes.create_unicode_buffer(max(1, title_len + 1))
            if title_len > 0:
                u32.GetWindowTextW(hwnd, title_buf, title_len + 1)

            rect = wintypes.RECT()
            if not u32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            width = int(rect.right - rect.left)
            height = int(rect.bottom - rect.top)

            if cls.is_steam_interstitial_popup(
                proc_name, cls_buf.value, title_buf.value, width, height
            ):
                dismissed_hwnds.add(int(hwnd))
                u32.PostMessageW(hwnd, WM_KEYDOWN, VK_RETURN, 0)
                u32.PostMessageW(hwnd, WM_KEYUP, VK_RETURN, 0)
                hit[0] = True
            return True

        try:
            u32.EnumWindows(enum_proc_type(enum_cb), 0)
        except Exception as exc:
            AgentLogger.debug("Steam interstitial scan skipped: %s", type(exc).__name__)
        return hit[0]

    @classmethod
    def prepare_for_stream(
        cls,
        steam_root: Path,
        all_games: Sequence[GameEntry],
        prewarm_steam: bool = True,
    ) -> bool:
        """
        Prepares Steam for hands-free wireless streaming, suppresses controller warning popups
        in localconfig.vdf when Steam is closed, optionally pre-warms 'steam.exe -silent', and
        launches a bounded 20s background watcher to auto-accept any Steam launch modal.

        Why: When prelaunch_before_stream is launching a Steam game directly, spawning a separate
        'steam.exe -silent' 1 ms before 'steam.exe -applaunch <appid>' races Steam's cold-start
        bootstrapper and causes Steam to silently drop '-applaunch'. Setting prewarm_steam=False
        lets WindowsSessionController launch a single 'steam.exe -silent -applaunch <appid>'.
        """
        steam_exe = cls.resolve_steam_exe(steam_root)
        app_ids = cls.extract_steam_app_ids(all_games)
        steam_running = Win32SystemProbes.is_process_running("steam.exe")

        if steam_exe is not None and not steam_running:
            cls._update_localconfig_files_when_steam_closed(steam_exe, app_ids)
            if prewarm_steam:
                try:
                    subprocess.Popen([str(steam_exe), "-silent"], shell=False)
                except OSError as exc:
                    AgentLogger.debug("Failed pre-warming steam.exe -silent: %s", type(exc).__name__)

        if sys.platform == "win32":
            def watcher() -> None:
                deadline = time.time() + STEAM_INTERSTITIAL_WATCH_SECONDS
                dismissed: Set[int] = set()
                while time.time() < deadline:
                    cls._dismiss_steam_interstitial_once(dismissed)
                    time.sleep(STEAM_INTERSTITIAL_POLL_SECONDS)

            t = threading.Thread(target=watcher, daemon=True, name="steam-interstitial-watcher")
            t.start()
        return True


class EpicPreparationManager:
    """
    Discovers installed Epic Games Launcher titles, resolves the launcher executable, and manages
    clean silent launches and graceful Epic shutdown without ever touching Steam.

    Why: Games bought on the Epic Games Store (such as Hogwarts Legacy) require Epic Games Launcher
    URI launch for entitlement validation and must remain strictly isolated from Steam.
    What: Parses C:\\ProgramData\\Epic\\EpicGamesLauncher\\Data\\Manifests\\*.item JSON manifests,
    detects EpicGamesLauncher.exe running state, and closes Epic cleanly after idle grace expiry.
    """

    EPIC_PROCESS_NAME: str = "epicgameslauncher.exe"
    EPIC_HELPER_PROCESS_NAMES: FrozenSet[str] = frozenset(
        {
            "epicgameslauncher.exe",
            "epicwebhelper.exe",
            "epicgameslauncher-win64-shipping.exe",
        }
    )

    @staticmethod
    def parse_epic_manifest(manifest_text: str) -> Optional[GameEntry]:
        """
        Parses an Epic Games Launcher .item JSON manifest into a GameEntry.

        Why: Automatically discovers installed Epic titles (filtering out incomplete installs, DLCs,
        and non-game tools) and constructs the official silent launch URI.
        What: Returns a GameEntry with category='epic', com.epicgames.launcher:// silent URI,
        install directory as working_dir, and LaunchExecutable filename in exe_name, or None.
        """
        try:
            data = json.loads(manifest_text)
        except (ValueError, json.JSONDecodeError):
            return None
        if not isinstance(data, dict):
            return None
        if data.get("bIsIncompleteInstall") is True:
            return None
        if data.get("bIsApplication") is False:
            return None

        app_name = str(data.get("AppName", "") or "").strip()
        main_game_app = str(data.get("MainGameAppName", "") or "").strip()
        if not app_name:
            return None
        if main_game_app and main_game_app.lower() != app_name.lower():
            return None

        categories = data.get("AppCategories")
        if isinstance(categories, list) and categories:
            lower_cats = {str(c).strip().lower() for c in categories}
            if "games" not in lower_cats:
                return None

        display_name = str(data.get("DisplayName", "") or "").strip()
        namespace = str(data.get("CatalogNamespace", "") or "").strip()
        item_id = str(data.get("CatalogItemId", "") or "").strip()
        if not display_name or not namespace or not item_id:
            return None

        install_location = str(data.get("InstallLocation", "") or "").strip()
        launch_exe_raw = str(data.get("LaunchExecutable", "") or "").strip()
        exe_name = Path(launch_exe_raw).name if launch_exe_raw else ""

        launch_uri = (
            f"com.epicgames.launcher://apps/"
            f"{namespace}%3A{item_id}%3A{app_name}?action=launch&silent=true"
        )
        safe_id = re.sub(r"[^a-zA-Z0-9_-]+", "_", app_name).strip("_").lower()
        return GameEntry(
            game_id=f"epic_{safe_id}",
            title=display_name,
            category="epic",
            launch_cmd=launch_uri,
            working_dir=install_location,
            exe_name=exe_name,
        )

    @classmethod
    def discover_epic_games(cls, manifests_dir: Optional[Path] = None) -> List[GameEntry]:
        """Scans an Epic Games Manifests folder for all installed main-game .item manifests."""
        target_dir = manifests_dir if manifests_dir is not None else DEFAULT_EPIC_MANIFESTS_DIR
        if not target_dir or not target_dir.is_dir():
            return []
        entries: List[GameEntry] = []
        seen_ids: Set[str] = set()
        for item_file in sorted(target_dir.glob("*.item")):
            try:
                parsed = cls.parse_epic_manifest(
                    item_file.read_text(encoding="utf-8", errors="ignore")
                )
                if parsed and parsed.game_id not in seen_ids:
                    seen_ids.add(parsed.game_id)
                    entries.append(parsed)
            except OSError as exc:
                AgentLogger.debug("Skipping unreadable Epic manifest %s: %s", item_file.name, exc)
        return entries

    @staticmethod
    def resolve_epic_exe(epic_exe_override: Optional[Path] = None) -> Optional[Path]:
        """Resolves the installed EpicGamesLauncher.exe path across registry hints and standard paths."""
        candidates: List[Path] = []
        if epic_exe_override is not None:
            candidates.append(epic_exe_override)

        if sys.platform == "win32" and not _is_automated_test_env():
            try:
                import winreg  # type: ignore

                reg_probes = (
                    (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Epic Games\EOS", "ModSdkCommand"),
                    (winreg.HKEY_CLASSES_ROOT, r"com.epicgames.launcher\shell\open\command", ""),
                    (
                        winreg.HKEY_LOCAL_MACHINE,
                        r"SOFTWARE\Classes\com.epicgames.launcher\shell\open\command",
                        "",
                    ),
                )
                for root_key, subkey, val_name in reg_probes:
                    try:
                        with winreg.OpenKey(root_key, subkey) as k:
                            raw_val, _ = winreg.QueryValueEx(k, val_name)
                            exe_str = BrowserLinkIsolationGuard._extract_exe_path(str(raw_val or ""))
                            if exe_str:
                                candidates.append(Path(exe_str))
                    except OSError:
                        continue
            except Exception:
                pass

        for pf_env in ("ProgramFiles(x86)", "ProgramFiles"):
            pf_base = os.environ.get(pf_env, "")
            if pf_base:
                candidates.append(Path(pf_base) / "Epic Games" / "Launcher" / "Portal" / "Binaries" / "Win64" / "EpicGamesLauncher.exe")
                candidates.append(Path(pf_base) / "Epic Games" / "Launcher" / "Portal" / "Binaries" / "Win32" / "EpicGamesLauncher.exe")
        for gdir in resolve_default_games_dirs():
            candidates.append(gdir / "Epic Games" / "Launcher" / "Portal" / "Binaries" / "Win64" / "EpicGamesLauncher.exe")
        for cand in candidates:
            try:
                if cand.is_file():
                    return cand
            except OSError:
                continue
        return None

    @classmethod
    def is_epic_running(cls) -> bool:
        """Returns True iff EpicGamesLauncher.exe is currently running."""
        return Win32SystemProbes.is_process_running(cls.EPIC_PROCESS_NAME)

    @classmethod
    def prepare_for_stream(cls, target_game: Optional[GameEntry] = None) -> bool:
        """
        Prepares an Epic Games title for hands-free wireless streaming without touching Steam.

        Why: Provides an explicit, symmetric lifecycle hook parallel to SteamPreparationManager
        so Epic launches remain cleanly isolated and auditable.
        """
        if target_game is not None:
            AgentLogger.info(
                "EpicPreparationManager preparing Epic launch for %s (epicRunning=%s).",
                target_game.game_id,
                cls.is_epic_running(),
            )
        return True

    @classmethod
    def shutdown_epic(
        cls,
        graceful_wait_sec: float = EPIC_GRACEFUL_SHUTDOWN_WAIT_SEC,
    ) -> bool:
        """
        Gracefully closes Epic Games Launcher (WM_CLOSE -> wait -> fallback terminate) on idle expiry.

        Why: Keeps Epic Games Launcher and its EpicWebHelper Chromium processes from idling on the
        laptop after a TV gaming session finishes. Fail-closed under automated tests.
        """
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        if not cls.is_epic_running():
            return True

        entries = Win32SystemProbes.snapshot_process_entries()
        epic_pids = {
            pid for pid, _ppid, name in entries if name.lower() == cls.EPIC_PROCESS_NAME
        }
        if epic_pids:
            hwnds = GameProcessController._default_find_hwnds_for_pids(epic_pids)
            for hwnd in hwnds:
                GameProcessController._default_post_message(hwnd, WM_CLOSE, 0, 0)

        deadline = time.monotonic() + max(0.5, float(graceful_wait_sec))
        while time.monotonic() < deadline:
            if not cls.is_epic_running():
                AgentLogger.info("Epic Games Launcher exited gracefully after idle grace period.")
                return True
            time.sleep(0.5)

        remaining_entries = Win32SystemProbes.snapshot_process_entries()
        remaining_pids = [
            pid
            for pid, _ppid, name in remaining_entries
            if name.lower() in cls.EPIC_HELPER_PROCESS_NAMES
        ]
        for pid in remaining_pids:
            GameProcessController._default_terminate_process(pid)
        AgentLogger.info(
            "Closed remaining Epic Games Launcher PID(s) after graceful timeout: %s",
            remaining_pids,
        )
        return True


class HeroicPreparationManager:
    """
    Discovers installed Heroic Games Launcher titles (Epic/Legendary, GOG, Amazon/Nile, Sideloaded)
    and optional standalone Legendary CLI titles, resolves executables, launches silently with --no-gui,
    and closes Heroic cleanly on idle expiry without ever touching Steam or official Epic Games Launcher.

    Why: Heroic Games Launcher provides a lightweight, open-source alternative to Epic Games Launcher
    across Epic, GOG, Amazon, and Sideloaded libraries; invoking Heroic with '--no-gui' (or invoking
    standalone Legendary CLI directly) avoids opening an Electron store window on the streamed desktop.
    What: Parses Heroic's AppData JSON stores and ~/.config/legendary/installed.json, builds --no-gui
    launch commands, detects Heroic.exe running state, and handles graceful shutdown after grace expiry.
    """

    HEROIC_PROCESS_NAME: str = "heroic.exe"
    HEROIC_HELPER_PROCESS_NAMES: FrozenSet[str] = frozenset(
        {
            "heroic.exe",
            "legendary.exe",
            "gogdl.exe",
            "nile.exe",
            "comet.exe",
            "galaxycommunication.exe",
        }
    )

    @staticmethod
    def _safe_slug(raw: str) -> str:
        return re.sub(r"[^a-zA-Z0-9_-]+", "_", (raw or "").strip()).strip("_").lower()

    @staticmethod
    def _extract_exe_name(raw_exe: str) -> str:
        clean = (raw_exe or "").strip()
        if not clean:
            return ""
        return Path(clean).name

    @classmethod
    def parse_legendary_installed_json(
        cls, json_text: str, use_heroic_uri: bool = True
    ) -> List[GameEntry]:
        """Parses Heroic's legendaryConfig/legendary/installed.json or standalone ~/.config/legendary/installed.json."""
        try:
            data = json.loads(json_text)
        except (ValueError, json.JSONDecodeError):
            return []
        if not isinstance(data, dict):
            return []
        entries: List[GameEntry] = []
        for key, item in data.items():
            if not isinstance(item, dict):
                continue
            if item.get("is_dlc") is True:
                continue
            app_name = str(item.get("app_name") or key or "").strip()
            title = str(item.get("title") or app_name).strip()
            install_path = str(item.get("install_path") or "").strip()
            exe_name = cls._extract_exe_name(str(item.get("executable") or ""))
            if not app_name or not title:
                continue
            slug = cls._safe_slug(app_name)
            if use_heroic_uri:
                encoded_app = urllib.parse.quote(app_name, safe="")
                entries.append(
                    GameEntry(
                        game_id=f"heroic_legendary_{slug}",
                        title=title,
                        category="heroic",
                        launch_cmd=f"heroic://launch?appName={encoded_app}&runner=legendary",
                        working_dir=install_path,
                        exe_name=exe_name,
                    )
                )
            else:
                entries.append(
                    GameEntry(
                        game_id=f"legendary_{slug}",
                        title=title,
                        category="legendary",
                        launch_cmd=f"legendary://launch/{app_name}",
                        working_dir=install_path,
                        exe_name=exe_name,
                    )
                )
        return entries

    @classmethod
    def parse_heroic_gog_installed_json(
        cls, installed_text: str, library_text: str = ""
    ) -> List[GameEntry]:
        """Parses Heroic's gog_store/installed.json (enriched with titles from gog_store/library.json)."""
        try:
            data = json.loads(installed_text)
        except (ValueError, json.JSONDecodeError):
            return []
        titles: Dict[str, str] = {}
        if library_text:
            try:
                lib = json.loads(library_text)
                raw_games = lib.get("games", []) if isinstance(lib, dict) else (lib if isinstance(lib, list) else [])
                for g in raw_games:
                    if isinstance(g, dict):
                        aid = str(g.get("app_name") or g.get("appName") or g.get("id") or "").strip()
                        ttl = str(g.get("title") or "").strip()
                        if aid and ttl:
                            titles[aid] = ttl
            except (ValueError, json.JSONDecodeError):
                pass

        items = data.get("installed", []) if isinstance(data, dict) else (data if isinstance(data, list) else [])
        entries: List[GameEntry] = []
        for item in items:
            if not isinstance(item, dict) or item.get("is_dlc") is True:
                continue
            app_name = str(item.get("appName") or item.get("app_name") or "").strip()
            install_path = str(item.get("install_path") or item.get("installPath") or "").strip()
            if not app_name:
                continue
            title = str(item.get("title") or titles.get(app_name) or (Path(install_path).name if install_path else app_name)).strip()
            exe_name = cls._extract_exe_name(str(item.get("executable") or ""))
            encoded_app = urllib.parse.quote(app_name, safe="")
            entries.append(
                GameEntry(
                    game_id=f"heroic_gog_{cls._safe_slug(app_name)}",
                    title=title,
                    category="heroic",
                    launch_cmd=f"heroic://launch?appName={encoded_app}&runner=gog",
                    working_dir=install_path,
                    exe_name=exe_name,
                )
            )
        return entries

    @classmethod
    def parse_heroic_nile_installed_json(
        cls, installed_text: str, library_text: str = ""
    ) -> List[GameEntry]:
        """Parses Heroic's nile_config/nile/installed.json (Amazon Games) enriched with nile_store/library.json."""
        try:
            data = json.loads(installed_text)
        except (ValueError, json.JSONDecodeError):
            return []
        titles: Dict[str, str] = {}
        if library_text:
            try:
                lib = json.loads(library_text)
                raw_games = (
                    lib.get("library", lib.get("games", []))
                    if isinstance(lib, dict)
                    else (lib if isinstance(lib, list) else [])
                )
                for g in raw_games:
                    if isinstance(g, dict):
                        aid = str(g.get("app_name") or g.get("appName") or g.get("id") or "").strip()
                        ttl = str(g.get("title") or "").strip()
                        if aid and ttl:
                            titles[aid] = ttl
            except (ValueError, json.JSONDecodeError):
                pass

        items = data if isinstance(data, list) else (data.get("installed", []) if isinstance(data, dict) else [])
        entries: List[GameEntry] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            app_name = str(item.get("id") or item.get("app_name") or item.get("appName") or "").strip()
            install_path = str(item.get("path") or item.get("install_path") or "").strip()
            if not app_name:
                continue
            title = str(item.get("title") or titles.get(app_name) or (Path(install_path).name if install_path else app_name)).strip()
            exe_name = cls._extract_exe_name(str(item.get("executable") or ""))
            encoded_app = urllib.parse.quote(app_name, safe="")
            entries.append(
                GameEntry(
                    game_id=f"heroic_nile_{cls._safe_slug(app_name)}",
                    title=title,
                    category="heroic",
                    launch_cmd=f"heroic://launch?appName={encoded_app}&runner=nile",
                    working_dir=install_path,
                    exe_name=exe_name,
                )
            )
        return entries

    @classmethod
    def parse_heroic_sideload_library_json(cls, library_text: str) -> List[GameEntry]:
        """Parses Heroic's sideload_apps/library.json custom non-store games."""
        try:
            data = json.loads(library_text)
        except (ValueError, json.JSONDecodeError):
            return []
        raw_games = data.get("games", []) if isinstance(data, dict) else (data if isinstance(data, list) else [])
        entries: List[GameEntry] = []
        for item in raw_games:
            if not isinstance(item, dict):
                continue
            app_name = str(item.get("app_name") or item.get("appName") or "").strip()
            title = str(item.get("title") or app_name).strip()
            install_obj = item.get("install") if isinstance(item.get("install"), dict) else {}
            exe_full = str(install_obj.get("executable") or "").strip()
            work_dir = str(Path(exe_full).parent) if exe_full else str(item.get("folder_name") or "").strip()
            exe_name = cls._extract_exe_name(exe_full)
            if not app_name or not title:
                continue
            encoded_app = urllib.parse.quote(app_name, safe="")
            entries.append(
                GameEntry(
                    game_id=f"heroic_sideload_{cls._safe_slug(app_name)}",
                    title=title,
                    category="heroic",
                    launch_cmd=f"heroic://launch?appName={encoded_app}&runner=sideload",
                    working_dir=work_dir,
                    exe_name=exe_name,
                )
            )
        return entries

    @classmethod
    def extract_epic_app_name(cls, game: GameEntry) -> str:
        """Extracts the Epic/Legendary appName from a Heroic Legendary or standalone Legendary GameEntry."""
        cmd = (game.launch_cmd or "").strip()
        lower = cmd.lower()
        if lower.startswith("heroic://launch"):
            parsed = urllib.parse.urlparse(cmd)
            qs = urllib.parse.parse_qs(parsed.query)
            runner = (qs.get("runner", [""])[0] or "").strip().lower()
            app = (qs.get("appName", [""])[0] or "").strip()
            if runner == "legendary" and app:
                return app
        elif lower.startswith("legendary://launch/"):
            return cmd.split("/", 3)[-1].strip()
        return ""

    @classmethod
    def discover_heroic_games(
        cls,
        heroic_config_dir: Optional[Path] = None,
        legendary_config_dir: Optional[Path] = None,
    ) -> List[GameEntry]:
        """Discovers installed Heroic (Epic, GOG, Amazon, Sideloaded) and optional standalone Legendary games."""
        h_dir = heroic_config_dir if heroic_config_dir is not None else DEFAULT_HEROIC_CONFIG_DIR
        l_dir = legendary_config_dir if legendary_config_dir is not None else DEFAULT_LEGENDARY_CONFIG_DIR

        entries: List[GameEntry] = []
        seen_ids: Set[str] = set()
        seen_epic_apps: Set[str] = set()

        def _append_unique(items: Sequence[GameEntry]) -> None:
            for g in items:
                if g.game_id in seen_ids:
                    continue
                epic_app = cls.extract_epic_app_name(g).lower()
                if epic_app and epic_app in seen_epic_apps:
                    continue
                seen_ids.add(g.game_id)
                if epic_app:
                    seen_epic_apps.add(epic_app)
                entries.append(g)

        if h_dir and h_dir.is_dir():
            leg_json = h_dir / "legendaryConfig" / "legendary" / "installed.json"
            if leg_json.is_file():
                try:
                    _append_unique(
                        cls.parse_legendary_installed_json(
                            leg_json.read_text(encoding="utf-8", errors="ignore"),
                            use_heroic_uri=True,
                        )
                    )
                except OSError as exc:
                    AgentLogger.debug("Skipping Heroic legendary installed.json: %s", exc)

            gog_json = h_dir / "gog_store" / "installed.json"
            if gog_json.is_file():
                try:
                    gog_lib_path = h_dir / "gog_store" / "library.json"
                    gog_lib = gog_lib_path.read_text(encoding="utf-8", errors="ignore") if gog_lib_path.is_file() else ""
                    _append_unique(
                        cls.parse_heroic_gog_installed_json(
                            gog_json.read_text(encoding="utf-8", errors="ignore"),
                            gog_lib,
                        )
                    )
                except OSError as exc:
                    AgentLogger.debug("Skipping Heroic GOG installed.json: %s", exc)

            nile_json = h_dir / "nile_config" / "nile" / "installed.json"
            if nile_json.is_file():
                try:
                    nile_lib_path = h_dir / "nile_store" / "library.json"
                    nile_lib = nile_lib_path.read_text(encoding="utf-8", errors="ignore") if nile_lib_path.is_file() else ""
                    _append_unique(
                        cls.parse_heroic_nile_installed_json(
                            nile_json.read_text(encoding="utf-8", errors="ignore"),
                            nile_lib,
                        )
                    )
                except OSError as exc:
                    AgentLogger.debug("Skipping Heroic Nile installed.json: %s", exc)

            side_json = h_dir / "sideload_apps" / "library.json"
            if side_json.is_file():
                try:
                    _append_unique(
                        cls.parse_heroic_sideload_library_json(
                            side_json.read_text(encoding="utf-8", errors="ignore")
                        )
                    )
                except OSError as exc:
                    AgentLogger.debug("Skipping Heroic sideload library.json: %s", exc)

        if l_dir and l_dir.is_dir():
            standalone_leg = l_dir / "installed.json"
            if standalone_leg.is_file():
                try:
                    _append_unique(
                        cls.parse_legendary_installed_json(
                            standalone_leg.read_text(encoding="utf-8", errors="ignore"),
                            use_heroic_uri=False,
                        )
                    )
                except OSError as exc:
                    AgentLogger.debug("Skipping standalone Legendary installed.json: %s", exc)

        return entries

    @staticmethod
    def resolve_heroic_exe(heroic_exe_override: Optional[Path] = None) -> Optional[Path]:
        """Resolves Heroic.exe across Windows heroic:// registry handler and standard install folders."""
        candidates: List[Path] = []
        if heroic_exe_override is not None:
            candidates.append(heroic_exe_override)

        if sys.platform == "win32" and not _is_automated_test_env():
            try:
                import winreg  # type: ignore

                reg_probes = (
                    (winreg.HKEY_CLASSES_ROOT, r"heroic\shell\open\command", ""),
                    (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Classes\heroic\shell\open\command", ""),
                    (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Classes\heroic\shell\open\command", ""),
                )
                for root_key, subkey, val_name in reg_probes:
                    try:
                        with winreg.OpenKey(root_key, subkey) as k:
                            raw_val, _ = winreg.QueryValueEx(k, val_name)
                            exe_str = BrowserLinkIsolationGuard._extract_exe_path(str(raw_val or ""))
                            if exe_str:
                                candidates.append(Path(exe_str))
                    except OSError:
                        continue
            except Exception:
                pass

        local_appdata = os.environ.get("LOCALAPPDATA", "")
        if local_appdata:
            candidates.append(Path(local_appdata) / "Programs" / "heroic" / "Heroic.exe")
        for pf_env in ("ProgramFiles", "ProgramFiles(x86)"):
            pf_base = os.environ.get(pf_env, "")
            if pf_base:
                candidates.append(Path(pf_base) / "Heroic" / "Heroic.exe")
        for gdir in resolve_default_games_dirs():
            candidates.append(gdir / "Heroic" / "Heroic.exe")
        for cand in candidates:
            try:
                if cand.is_file():
                    return cand
            except OSError:
                continue
        return None

    @staticmethod
    def resolve_legendary_exe(legendary_exe_override: Optional[Path] = None) -> Optional[Path]:
        """Resolves optional standalone legendary CLI binary if installed on PATH or user bin folder."""
        if legendary_exe_override is not None:
            try:
                if legendary_exe_override.is_file():
                    return legendary_exe_override
            except OSError:
                pass
        for name in ("legendary.exe", "legendary"):
            found = shutil.which(name)
            if found:
                p = Path(found)
                if p.is_file():
                    return p
        local_cand = Path.home() / ".local" / "bin" / "legendary.exe"
        if local_cand.is_file():
            return local_cand
        return None

    @classmethod
    def build_silent_launch_args(
        cls,
        game: GameEntry,
        heroic_exe: Optional[Path] = None,
        legendary_exe: Optional[Path] = None,
    ) -> Optional[List[str]]:
        """Builds headless CLI launch args for Heroic ('--no-gui') or standalone Legendary."""
        cmd = (game.launch_cmd or "").strip()
        lower = cmd.lower()
        if lower.startswith("heroic://"):
            exe = heroic_exe if heroic_exe is not None else cls.resolve_heroic_exe()
            if exe is not None and exe.is_file():
                return [str(exe), "--no-gui", cmd]
            return None
        if lower.startswith("legendary://launch/"):
            app_name = cmd.split("/", 3)[-1].strip()
            if not app_name:
                return None
            exe = legendary_exe if legendary_exe is not None else cls.resolve_legendary_exe()
            if exe is not None and exe.is_file():
                return [str(exe), "launch", app_name]
            return None
        return None

    @classmethod
    def is_heroic_running(cls) -> bool:
        """Returns True iff Heroic.exe is currently running."""
        return Win32SystemProbes.is_process_running(cls.HEROIC_PROCESS_NAME)

    @classmethod
    def prepare_for_stream(cls, target_game: Optional[GameEntry] = None) -> bool:
        """Prepares a Heroic / Legendary launch without touching Steam or official Epic Games Launcher."""
        if target_game is not None:
            AgentLogger.info(
                "HeroicPreparationManager preparing silent launch for %s (heroicRunning=%s).",
                target_game.game_id,
                cls.is_heroic_running(),
            )
        return True

    @classmethod
    def shutdown_heroic(
        cls,
        graceful_wait_sec: float = HEROIC_GRACEFUL_SHUTDOWN_WAIT_SEC,
    ) -> bool:
        """Gracefully closes Heroic Games Launcher (WM_CLOSE -> wait -> fallback terminate) on idle expiry."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        if not cls.is_heroic_running():
            return True

        entries = Win32SystemProbes.snapshot_process_entries()
        heroic_pids = {
            pid for pid, _ppid, name in entries if name.lower() == cls.HEROIC_PROCESS_NAME
        }
        if heroic_pids:
            hwnds = GameProcessController._default_find_hwnds_for_pids(heroic_pids)
            for hwnd in hwnds:
                GameProcessController._default_post_message(hwnd, WM_CLOSE, 0, 0)

        deadline = time.monotonic() + max(0.5, float(graceful_wait_sec))
        while time.monotonic() < deadline:
            if not cls.is_heroic_running():
                AgentLogger.info("Heroic Games Launcher exited gracefully after idle grace period.")
                return True
            time.sleep(0.5)

        remaining_entries = Win32SystemProbes.snapshot_process_entries()
        remaining_pids = [
            pid
            for pid, _ppid, name in remaining_entries
            if name.lower() == cls.HEROIC_PROCESS_NAME
        ]
        for pid in remaining_pids:
            GameProcessController._default_terminate_process(pid)
        AgentLogger.info(
            "Closed remaining Heroic Games Launcher PID(s) after graceful timeout: %s",
            remaining_pids,
        )
        return True


class SunshineLifecycleController:
    """
    Manages on-demand startup and post-game idle shutdown of Sunshine (SunshineService / sunshine.exe).

    Why: Keeping Sunshine running continuously when nobody is gaming consumes background CPU/GPU
    telemetry cycles. This controller stops Sunshine cleanly after the 10-minute post-game grace
    window and starts it transparently on demand before the next wireless game stream begins.
    What: Uses native Win32 advapi32 SCM calls (with fail-closed test guards), exposes a one-time
    Administrator SDDL permission grant (--enable-sunshine-power-control) so the unprivileged agent
    can start/stop SunshineService without UAC prompts, and waits for local port readiness on start.
    """

    SERVICE_NAME: str = "SunshineService"
    DEFAULT_SUNSHINE_EXE: Path = Path(r"C:\Program Files\Sunshine\sunshine.exe")
    _IU_ACE_RE: re.Pattern[str] = re.compile(r"\(A;;([^;]*);;;IU\)")

    def __init__(
        self,
        service_name: str = SERVICE_NAME,
        is_running_fn: Optional[Callable[[], bool]] = None,
        is_port_ready_fn: Optional[Callable[[], bool]] = None,
        can_control_service_fn: Optional[Callable[[], bool]] = None,
        start_service_fn: Optional[Callable[[], bool]] = None,
        stop_service_fn: Optional[Callable[[], bool]] = None,
        close_active_app_fn: Optional[Callable[[], bool]] = None,
        poll_interval_sec: float = 0.25,
    ) -> None:
        self._service_name = service_name
        self._is_running_fn = is_running_fn or Win32SystemProbes.is_sunshine_running
        self._is_port_ready_fn = is_port_ready_fn or (
            lambda: Win32SystemProbes._can_connect_local_port("127.0.0.1", SUNSHINE_HTTP_PORT)
            and Win32SystemProbes._can_connect_local_port("127.0.0.1", SUNSHINE_STREAM_HTTPS_PORT)
        )
        self._can_control_service_fn = can_control_service_fn or (
            lambda: self._native_can_control_service(self._service_name)
        )
        self._has_custom_start_fn = start_service_fn is not None
        self._start_service_fn = start_service_fn or (
            lambda: self._native_control_service(self._service_name, start=True)
        )
        self._stop_service_fn = stop_service_fn or (
            lambda: self._native_control_service(self._service_name, start=False)
        )
        self._close_active_app_fn = close_active_app_fn or SunshineConfigManager.close_active_app
        self._poll_interval_sec = max(0.005, float(poll_interval_sec))

    @classmethod
    def build_service_sddl_with_interactive_control(cls, current_sddl: str) -> str:
        """
        Pure SDDL transformer granting Interactive Users (IU) Start (RP) and Stop (WP) rights.

        Why: Windows installs SunshineService with read-only IU permissions (CCLCSWLOCRRC). Adding
        RPWP lets the local user's gaming agent start/stop SunshineService cleanly without SYSTEM
        escalation or UAC prompts on every game launch.
        """
        clean = (current_sddl or "").strip()
        if not clean:
            return clean
        match = cls._IU_ACE_RE.search(clean)
        if match:
            rights = match.group(1)
            updated_rights = rights
            if "RP" not in updated_rights:
                updated_rights += "RP"
            if "WP" not in updated_rights:
                updated_rights += "WP"
            if updated_rights == rights:
                return clean
            replacement = f"(A;;{updated_rights};;;IU)"
            return clean[: match.start()] + replacement + clean[match.end() :]
        if "S:" in clean:
            s_idx = clean.index("S:")
            return clean[:s_idx] + "(A;;CCLCSWLOCRRCRPWP;;;IU)" + clean[s_idx:]
        return clean + "(A;;CCLCSWLOCRRCRPWP;;;IU)"

    @staticmethod
    def _native_can_control_service(service_name: str = SERVICE_NAME) -> bool:
        """Read-only SCM probe checking whether the current user has Start+Stop access on SunshineService."""
        if sys.platform != "win32":
            return False
        try:
            adv = ctypes.WinDLL("advapi32", use_last_error=True)
            adv.OpenSCManagerW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
            adv.OpenSCManagerW.restype = wintypes.HANDLE
            adv.OpenServiceW.argtypes = [wintypes.HANDLE, wintypes.LPCWSTR, wintypes.DWORD]
            adv.OpenServiceW.restype = wintypes.HANDLE
            adv.CloseServiceHandle.argtypes = [wintypes.HANDLE]
            adv.CloseServiceHandle.restype = wintypes.BOOL

            scm = adv.OpenSCManagerW(None, None, SC_MANAGER_CONNECT)
            if not scm:
                return False
            try:
                svc = adv.OpenServiceW(
                    scm, service_name, SERVICE_START | SERVICE_STOP | SERVICE_QUERY_STATUS
                )
                if not svc:
                    return False
                adv.CloseServiceHandle(svc)
                return True
            finally:
                adv.CloseServiceHandle(scm)
        except Exception:
            return False

    @classmethod
    def _native_control_service(
        cls, service_name: str = SERVICE_NAME, start: bool = True
    ) -> bool:
        """Starts or stops SunshineService via Win32 advapi32 SCM; fail-closed under automated tests."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        try:
            adv = ctypes.WinDLL("advapi32", use_last_error=True)
            adv.OpenSCManagerW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
            adv.OpenSCManagerW.restype = wintypes.HANDLE
            adv.OpenServiceW.argtypes = [wintypes.HANDLE, wintypes.LPCWSTR, wintypes.DWORD]
            adv.OpenServiceW.restype = wintypes.HANDLE
            adv.StartServiceW.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.c_void_p]
            adv.StartServiceW.restype = wintypes.BOOL
            adv.ControlService.argtypes = [
                wintypes.HANDLE,
                wintypes.DWORD,
                ctypes.c_void_p,
            ]
            adv.ControlService.restype = wintypes.BOOL
            adv.CloseServiceHandle.argtypes = [wintypes.HANDLE]
            adv.CloseServiceHandle.restype = wintypes.BOOL

            scm = adv.OpenSCManagerW(None, None, SC_MANAGER_CONNECT)
            if not scm:
                return False
            try:
                desired_access = (
                    (SERVICE_START | SERVICE_QUERY_STATUS)
                    if start
                    else (SERVICE_STOP | SERVICE_QUERY_STATUS)
                )
                svc = adv.OpenServiceW(scm, service_name, desired_access)
                if not svc:
                    err = ctypes.get_last_error()
                    if err == 5:
                        AgentLogger.warning(
                            "SunshineService %s denied by Windows Service ACL. Run once in Admin PowerShell: "
                            "python laptop_gaming_agent.py --enable-sunshine-power-control",
                            "start" if start else "stop",
                        )
                    return False
                try:
                    if start:
                        ok = bool(adv.StartServiceW(svc, 0, None))
                        if not ok:
                            # ERROR_SERVICE_ALREADY_RUNNING (1056) is success
                            return ctypes.get_last_error() == 1056
                        return True
                    status_buf = (wintypes.DWORD * 7)()
                    ok = bool(
                        adv.ControlService(svc, SERVICE_CONTROL_STOP, ctypes.byref(status_buf))
                    )
                    if not ok:
                        # ERROR_SERVICE_NOT_ACTIVE (1062) means already stopped
                        return ctypes.get_last_error() == 1062
                    return True
                finally:
                    adv.CloseServiceHandle(svc)
            finally:
                adv.CloseServiceHandle(scm)
        except Exception as exc:
            AgentLogger.warning(
                "SunshineService %s error: %s", "start" if start else "stop", type(exc).__name__
            )
            return False

    def is_running(self) -> bool:
        """Returns True iff Sunshine is currently running."""
        return bool(self._is_running_fn())

    def can_control_service(self) -> bool:
        """Returns True iff this agent currently holds Windows permission to start/stop SunshineService."""
        return bool(self._can_control_service_fn())

    def ensure_running(self, timeout_sec: float = SUNSHINE_LIFECYCLE_TIMEOUT_SEC) -> bool:
        """
        Ensures Sunshine is running and listening before starting a wireless game stream.

        Returns immediately when Sunshine is already running; otherwise starts SunshineService
        (or standalone sunshine.exe fallback) and polls until ready within timeout_sec.
        """
        if self._is_running_fn() and self._is_port_ready_fn():
            return True
        if not self._has_custom_start_fn and _is_automated_test_env():
            return False

        AgentLogger.info("Sunshine is stopped; starting Sunshine on demand for incoming game stream.")
        deadline = time.monotonic() + max(0.1, float(timeout_sec))
        started = bool(self._start_service_fn())
        if not started and not self._is_running_fn():
            # Service may briefly be in STOP_PENDING after a recent stop; retry briefly before giving up
            retry_until = min(deadline, time.monotonic() + 3.0)
            while not started and time.monotonic() < retry_until:
                time.sleep(self._poll_interval_sec)
                if self._is_running_fn():
                    started = True
                    break
                started = bool(self._start_service_fn())
        if not started and sys.platform == "win32" and not _is_automated_test_env():
            if not self._is_running_fn() and self.DEFAULT_SUNSHINE_EXE.is_file():
                try:
                    subprocess.Popen([str(self.DEFAULT_SUNSHINE_EXE)], shell=False)
                    started = True
                except OSError as exc:
                    AgentLogger.warning("Standalone sunshine.exe start warning: %s", type(exc).__name__)
        if not started and not self._is_running_fn():
            return False

        while time.monotonic() < deadline:
            if self._is_running_fn() and self._is_port_ready_fn():
                AgentLogger.info("Sunshine is now online and ready to stream.")
                return True
            time.sleep(self._poll_interval_sec)
        return bool(self._is_running_fn() and self._is_port_ready_fn())

    def stop_sunshine(self, timeout_sec: float = SUNSHINE_LIFECYCLE_TIMEOUT_SEC) -> bool:
        """
        Cleanly closes any active Sunshine app session and stops SunshineService / sunshine.exe.
        """
        if not self._is_running_fn():
            return True
        try:
            self._close_active_app_fn()
        except Exception as exc:
            AgentLogger.debug("Pre-stop Sunshine close_active_app warning: %s", type(exc).__name__)

        stopped_cmd = bool(self._stop_service_fn())
        deadline = time.monotonic() + max(0.1, float(timeout_sec))
        while time.monotonic() < deadline:
            if not self._is_running_fn():
                AgentLogger.info("Sunshine stopped cleanly after idle grace period.")
                return True
            time.sleep(self._poll_interval_sec)

        # Fallback: close standalone user-mode sunshine.exe if running outside SunshineService
        if sys.platform == "win32" and not _is_automated_test_env():
            entries = Win32SystemProbes.snapshot_process_entries()
            sun_pids = [pid for pid, _ppid, name in entries if name.lower() == "sunshine.exe"]
            for pid in sun_pids:
                GameProcessController._default_terminate_process(pid)
        return stopped_cmd or (not self._is_running_fn())

    @classmethod
    def enable_sunshine_power_control(
        cls,
        set_manual_start: bool = False,
        command_runner: Optional[
            Callable[[List[str], float], subprocess.CompletedProcess[str]]
        ] = None,
        is_admin_fn: Optional[Callable[[], bool]] = None,
    ) -> Dict[str, Any]:
        """
        One-time Administrator setup granting the interactive user Start/Stop rights on SunshineService.

        Why: Lets SoarTV auto-stop Sunshine after 10 idle minutes and auto-start Sunshine on demand
        when launching a game, without ever triggering a UAC prompt at runtime.
        """
        if sys.platform != "win32":
            return {"ok": False, "error": "unsupported_platform"}
        admin_check = is_admin_fn or WifiWakeConfigurator._default_is_admin
        if not admin_check():
            return {
                "ok": False,
                "isAdmin": False,
                "error": "admin_required",
                "message": "Run --enable-sunshine-power-control from an Administrator terminal.",
            }
        runner = command_runner or Win32InputInjector._default_command_runner
        show_proc = runner(["sc.exe", "sdshow", cls.SERVICE_NAME], WIFI_CONFIG_CMD_TIMEOUT_SEC)
        if show_proc.returncode != 0:
            return {
                "ok": False,
                "isAdmin": True,
                "error": "sdshow_failed",
                "stderr": (show_proc.stderr or show_proc.stdout or "").strip(),
            }
        current_sddl = "".join(
            line.strip() for line in (show_proc.stdout or "").splitlines() if line.strip()
        )
        new_sddl = cls.build_service_sddl_with_interactive_control(current_sddl)
        set_proc = runner(
            ["sc.exe", "sdset", cls.SERVICE_NAME, new_sddl], WIFI_CONFIG_CMD_TIMEOUT_SEC
        )
        ok = set_proc.returncode == 0
        manual_ok = True
        if ok and set_manual_start:
            cfg_proc = runner(
                ["sc.exe", "config", cls.SERVICE_NAME, "start=", "demand"],
                WIFI_CONFIG_CMD_TIMEOUT_SEC,
            )
            manual_ok = cfg_proc.returncode == 0
        return {
            "ok": ok and manual_ok,
            "isAdmin": True,
            "serviceName": cls.SERVICE_NAME,
            "sddlUpdated": ok,
            "manualStartConfigured": bool(set_manual_start and manual_ok),
        }


class GamingIdleShutdownManager:
    """
    Closes Steam, Epic Games Launcher, and Sunshine cleanly after a configurable post-game idle grace window.

    Why: Keeping Steam, Epic Games Launcher, and Sunshine running indefinitely after leaving a TV game
    wastes laptop CPU, GPU telemetry cycles, and RAM. This manager arms a single one-shot, self-terminating
    waiter thread (10-minute default grace) that exits cleanly once it fires or is cancelled—leaving zero
    background polling threads behind.
    What: Gracefully shuts down Steam ('steam.exe -shutdown'), Epic Games Launcher (WM_CLOSE + fallback),
    and Sunshine (SunshineService stop) if running at grace expiry, cancels immediately on any new game
    launch, and skips shutdown if a TV session or any discovered PC game is running.
    """

    def __init__(
        self,
        grace_seconds: float = DEFAULT_STEAM_IDLE_GRACE_SECONDS,
        steam_root: Optional[Path] = None,
        is_steam_running_fn: Optional[Callable[[], bool]] = None,
        is_epic_running_fn: Optional[Callable[[], bool]] = None,
        is_heroic_running_fn: Optional[Callable[[], bool]] = None,
        is_sunshine_running_fn: Optional[Callable[[], bool]] = None,
        is_session_active_fn: Optional[Callable[[], bool]] = None,
        is_any_game_running_fn: Optional[Callable[[], bool]] = None,
        shutdown_steam_fn: Optional[Callable[[], bool]] = None,
        shutdown_epic_fn: Optional[Callable[[], bool]] = None,
        shutdown_heroic_fn: Optional[Callable[[], bool]] = None,
        shutdown_sunshine_fn: Optional[Callable[[], bool]] = None,
        graceful_wait_sec: float = STEAM_GRACEFUL_SHUTDOWN_WAIT_SEC,
    ) -> None:
        self._grace_seconds = max(0.0, float(grace_seconds))
        self._steam_root = steam_root or resolve_default_steam_root()
        isolated_steam_only = (
            is_steam_running_fn is not None
            and is_epic_running_fn is None
            and is_heroic_running_fn is None
            and is_sunshine_running_fn is None
            and shutdown_epic_fn is None
            and shutdown_heroic_fn is None
            and shutdown_sunshine_fn is None
        )
        isolated_no_heroic = (
            (is_steam_running_fn is not None or is_epic_running_fn is not None or is_sunshine_running_fn is not None)
            and is_heroic_running_fn is None
            and shutdown_heroic_fn is None
        )
        self._is_steam_running_fn = is_steam_running_fn or (
            lambda: Win32SystemProbes.is_process_running("steam.exe")
        )
        self._is_epic_running_fn = is_epic_running_fn or (
            (lambda: False) if isolated_steam_only else EpicPreparationManager.is_epic_running
        )
        self._is_heroic_running_fn = is_heroic_running_fn or (
            (lambda: False) if (isolated_steam_only or isolated_no_heroic) else HeroicPreparationManager.is_heroic_running
        )
        self._is_sunshine_running_fn = is_sunshine_running_fn or (
            (lambda: False) if isolated_steam_only else Win32SystemProbes.is_sunshine_running
        )
        self._is_session_active_fn = is_session_active_fn or (lambda: False)
        self._is_any_game_running_fn = is_any_game_running_fn or (lambda: False)
        self._graceful_wait_sec = max(0.1, float(graceful_wait_sec))
        self._shutdown_steam_fn = shutdown_steam_fn or (
            lambda: self._default_shutdown_steam(self._steam_root, self._graceful_wait_sec)
        )
        self._shutdown_epic_fn = shutdown_epic_fn or (
            lambda: EpicPreparationManager.shutdown_epic(self._graceful_wait_sec)
        )
        self._shutdown_heroic_fn = shutdown_heroic_fn or (
            lambda: HeroicPreparationManager.shutdown_heroic(self._graceful_wait_sec)
        )
        self._shutdown_sunshine_fn = shutdown_sunshine_fn or (
            lambda: SunshineLifecycleController().stop_sunshine()
        )
        self._lock = threading.Lock()
        self._generation: int = 0
        self._cancel_event: Optional[threading.Event] = None
        self._thread: Optional[threading.Thread] = None
        self._scheduled_reason: str = ""

    @staticmethod
    def _default_shutdown_steam(
        steam_root: Path,
        graceful_wait_sec: float = STEAM_GRACEFUL_SHUTDOWN_WAIT_SEC,
    ) -> bool:
        """
        Requests graceful Steam shutdown via 'steam.exe -shutdown', falling back to terminating
        hung steam.exe processes only if Steam stays alive past graceful_wait_sec.

        Why: '-shutdown' flushes Steam Cloud saves and cleanly tears down steamwebhelper.exe children.
        What: Fail-closed no-op under automated tests or non-Windows platforms.
        """
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        if not Win32SystemProbes.is_process_running("steam.exe"):
            return True

        steam_exe = SteamPreparationManager.resolve_steam_exe(steam_root)
        if steam_exe is not None and steam_exe.is_file():
            try:
                subprocess.Popen([str(steam_exe), "-shutdown"], shell=False)
            except OSError as exc:
                AgentLogger.warning("Graceful steam.exe -shutdown failed: %s", type(exc).__name__)

        deadline = time.monotonic() + max(0.5, float(graceful_wait_sec))
        while time.monotonic() < deadline:
            if not Win32SystemProbes.is_process_running("steam.exe"):
                AgentLogger.info("Steam exited gracefully after idle grace period.")
                return True
            time.sleep(0.5)

        entries = Win32SystemProbes.snapshot_process_entries()
        steam_pids = [pid for pid, _ppid, name in entries if name.lower() == "steam.exe"]
        for pid in steam_pids:
            GameProcessController._default_terminate_process(pid)
        AgentLogger.info("Closed remaining Steam PID(s) after graceful shutdown timeout: %s", steam_pids)
        return True

    def _any_target_running(self) -> Tuple[bool, bool, bool, bool]:
        """Probes (steam_running, epic_running, heroic_running, sunshine_running) safely."""
        def _probe(fn: Callable[[], bool]) -> bool:
            try:
                return bool(fn())
            except Exception:
                return False

        return (
            _probe(self._is_steam_running_fn),
            _probe(self._is_epic_running_fn),
            _probe(self._is_heroic_running_fn),
            _probe(self._is_sunshine_running_fn),
        )

    def is_pending(self) -> bool:
        """Returns True while a scheduled idle shutdown waiter is active and uncancelled."""
        with self._lock:
            return self._cancel_event is not None and not self._cancel_event.is_set()

    def cancel(self, reason: str = "new_game_launch") -> bool:
        """Cancels any pending idle shutdown timer so the waiter thread terminates immediately."""
        with self._lock:
            ev = self._cancel_event
            if ev is None:
                return False
            self._generation += 1
            ev.set()
            self._cancel_event = None
            self._thread = None
            self._scheduled_reason = ""
        AgentLogger.info("Cancelled pending gaming idle shutdown (%s).", reason)
        return True

    def schedule_shutdown(self, reason: str = "game_exit") -> bool:
        """
        Schedules a one-shot self-terminating waiter to close Steam, Epic, Heroic, and Sunshine after grace_seconds.

        Returns False without spawning any thread if none of Steam, Epic, Heroic, or Sunshine are running.
        """
        steam_on, epic_on, heroic_on, sunshine_on = self._any_target_running()
        if not (steam_on or epic_on or heroic_on or sunshine_on):
            self.cancel("all_launchers_already_closed")
            return False

        with self._lock:
            if self._cancel_event is not None:
                self._cancel_event.set()
            self._generation += 1
            my_gen = self._generation
            cancel_ev = threading.Event()
            self._cancel_event = cancel_ev
            self._scheduled_reason = reason

            def _wait_and_shutdown() -> None:
                try:
                    cancelled = cancel_ev.wait(timeout=self._grace_seconds)
                    if cancelled or cancel_ev.is_set():
                        return
                    with self._lock:
                        if self._generation != my_gen:
                            return
                    try:
                        if self._is_session_active_fn():
                            AgentLogger.info("Skipping idle shutdown: TV gaming session is active.")
                            return
                        if self._is_any_game_running_fn():
                            AgentLogger.info("Skipping idle shutdown: game process is active.")
                            return
                        st_now, ep_now, hr_now, sun_now = self._any_target_running()
                        if not (st_now or ep_now or hr_now or sun_now):
                            return
                        AgentLogger.info(
                            "Idle grace (%.0fs) elapsed after '%s'; shutting down active targets "
                            "(steam=%s, epic=%s, heroic=%s, sunshine=%s).",
                            self._grace_seconds,
                            reason,
                            st_now,
                            ep_now,
                            hr_now,
                            sun_now,
                        )
                        if st_now:
                            try:
                                self._shutdown_steam_fn()
                            except Exception as exc:
                                AgentLogger.warning("Steam idle shutdown error: %s", type(exc).__name__)
                        if ep_now:
                            try:
                                self._shutdown_epic_fn()
                            except Exception as exc:
                                AgentLogger.warning("Epic idle shutdown error: %s", type(exc).__name__)
                        if hr_now:
                            try:
                                self._shutdown_heroic_fn()
                            except Exception as exc:
                                AgentLogger.warning("Heroic idle shutdown error: %s", type(exc).__name__)
                        if sun_now:
                            try:
                                self._shutdown_sunshine_fn()
                            except Exception as exc:
                                AgentLogger.warning("Sunshine idle shutdown error: %s", type(exc).__name__)
                    except Exception as exc:
                        AgentLogger.warning("Gaming idle shutdown error: %s", type(exc).__name__)
                finally:
                    with self._lock:
                        if self._generation == my_gen:
                            self._cancel_event = None
                            self._thread = None
                            self._scheduled_reason = ""

            worker = threading.Thread(
                target=_wait_and_shutdown,
                daemon=True,
                name="gaming-idle-shutdown",
            )
            self._thread = worker
            worker.start()

        AgentLogger.info(
            "Scheduled self-terminating idle shutdown in %.0fs (%s; steam=%s, epic=%s, heroic=%s, sunshine=%s).",
            self._grace_seconds,
            reason,
            steam_on,
            epic_on,
            heroic_on,
            sunshine_on,
        )
        return True


# Backwards-compatible alias for tests and callers referencing the original class name
SteamIdleShutdownManager = GamingIdleShutdownManager


SM_CXSCREEN: int = 0
SM_CYSCREEN: int = 1
SWP_FRAMECHANGED: int = 0x0020
SW_MAXIMIZE: int = 3


class PrimaryMonitorEnforcer:
    """
    Ensures streamed PC games open and stay on the Windows primary monitor captured by Sunshine.

    Why: On multi-monitor setups (e.g., primary desk monitor at (0,0) + secondary laptop panel at
    (-1920,0)), Unity and other engines occasionally launch on the secondary monitor, causing
    Sunshine (capturing the primary monitor) to display an empty desktop.
    What: Checks window geometry against the primary monitor rect (0, 0, SM_CXSCREEN, SM_CYSCREEN)
    and smoothly relocates off-primary game windows onto the primary monitor.
    """

    def __init__(
        self,
        get_primary_rect_fn: Optional[Callable[[], Tuple[int, int, int, int]]] = None,
        get_window_rect_fn: Optional[Callable[[int], Optional[Tuple[int, int, int, int]]]] = None,
        is_zoomed_fn: Optional[Callable[[int], bool]] = None,
        move_window_fn: Optional[Callable[[int, int, int, int, int], bool]] = None,
    ) -> None:
        self._get_primary_rect_fn = get_primary_rect_fn or self._default_get_primary_rect
        self._get_window_rect_fn = get_window_rect_fn or self._default_get_window_rect
        self._is_zoomed_fn = is_zoomed_fn or self._default_is_zoomed
        self._move_window_fn = move_window_fn or self._default_move_window

    @staticmethod
    def _default_get_primary_rect() -> Tuple[int, int, int, int]:
        if sys.platform != "win32" or _is_automated_test_env():
            return (0, 0, 1920, 1080)
        try:
            u32 = ctypes.windll.user32
            pw = int(u32.GetSystemMetrics(SM_CXSCREEN))
            ph = int(u32.GetSystemMetrics(SM_CYSCREEN))
            if pw > 0 and ph > 0:
                return (0, 0, pw, ph)
        except Exception:
            pass
        return (0, 0, 1920, 1080)

    @staticmethod
    def _default_get_window_rect(hwnd: int) -> Optional[Tuple[int, int, int, int]]:
        if sys.platform != "win32" or hwnd <= 0 or _is_automated_test_env():
            return None
        try:
            rect = wintypes.RECT()
            if ctypes.windll.user32.GetWindowRect(wintypes.HWND(hwnd), ctypes.byref(rect)):
                return (int(rect.left), int(rect.top), int(rect.right), int(rect.bottom))
        except Exception:
            pass
        return None

    @staticmethod
    def _default_is_zoomed(hwnd: int) -> bool:
        if sys.platform != "win32" or hwnd <= 0 or _is_automated_test_env():
            return False
        try:
            return bool(ctypes.windll.user32.IsZoomed(wintypes.HWND(hwnd)))
        except Exception:
            return False

    @staticmethod
    def _default_move_window(hwnd: int, x: int, y: int, width: int, height: int) -> bool:
        if sys.platform != "win32" or hwnd <= 0 or _is_automated_test_env():
            return False
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            was_zoomed = bool(u32.IsZoomed(wintypes.HWND(hwnd)))
            if was_zoomed:
                u32.ShowWindow(wintypes.HWND(hwnd), SW_RESTORE)
            ok = bool(
                u32.SetWindowPos(
                    wintypes.HWND(hwnd),
                    wintypes.HWND(0),
                    int(x),
                    int(y),
                    int(width),
                    int(height),
                    SWP_SHOWWINDOW | SWP_FRAMECHANGED,
                )
            )
            if was_zoomed:
                u32.ShowWindow(wintypes.HWND(hwnd), SW_MAXIMIZE)
            return ok
        except Exception as exc:
            AgentLogger.debug("PrimaryMonitorEnforcer move error: %s", type(exc).__name__)
            return False

    @staticmethod
    def is_rect_on_primary_monitor(
        window_rect: Tuple[int, int, int, int],
        primary_rect: Tuple[int, int, int, int],
    ) -> bool:
        """Returns True iff the window center lies inside the primary monitor bounds."""
        wl, wt, wr, wb = window_rect
        pl, pt, pr, pb = primary_rect
        if wr <= wl or wb <= wt or pr <= pl or pb <= pt:
            return True
        cx = (wl + wr) / 2.0
        cy = (wt + wb) / 2.0
        return (pl <= cx < pr) and (pt <= cy < pb)

    @staticmethod
    def compute_primary_target_rect(
        window_rect: Tuple[int, int, int, int],
        primary_rect: Tuple[int, int, int, int],
    ) -> Tuple[int, int, int, int]:
        """Computes (x, y, width, height) to place an off-primary game window cleanly on the primary monitor."""
        wl, wt, wr, wb = window_rect
        pl, pt, pr, pb = primary_rect
        pw = max(1, pr - pl)
        ph = max(1, pb - pt)
        ww = max(1, wr - wl)
        wh = max(1, wb - wt)
        # Fullscreen or near-fullscreen window -> fill primary monitor cleanly
        if ww >= int(pw * 0.92) and wh >= int(ph * 0.92):
            return (pl, pt, pw, ph)
        tw = min(ww, pw)
        th = min(wh, ph)
        tx = pl + (pw - tw) // 2
        ty = pt + (ph - th) // 2
        return (tx, ty, tw, th)

    def ensure_on_primary_monitor(self, hwnd: int) -> bool:
        """Relocates hwnd onto the primary monitor if it opened on a secondary display."""
        if hwnd <= 0:
            return False
        w_rect = self._get_window_rect_fn(hwnd)
        if w_rect is None:
            return True
        p_rect = self._get_primary_rect_fn()
        if self.is_rect_on_primary_monitor(w_rect, p_rect):
            return True
        tx, ty, tw, th = self.compute_primary_target_rect(w_rect, p_rect)
        AgentLogger.info(
            "Relocating off-primary game window 0x%x %s -> primary monitor (%d,%d %dx%d).",
            hwnd,
            w_rect,
            tx,
            ty,
            tw,
            th,
        )
        return bool(self._move_window_fn(hwnd, tx, ty, tw, th))


class BrowserLinkIsolationGuard:
    """
    Isolates external browser links opened by games during TV streaming so existing tabs stay private.

    Why: Clicking an external URL inside a Steam game (e.g. Discord, website, store) invokes the
    Windows default browser, which otherwise restores the user's minimized personal Chrome window
    and exposes all open tabs on the TV.
    What: Temporarily configures an HKCU user-level ProgId command override (e.g., Chrome
    '--new-window --incognito -- "%1"') backed by a crash-recovery JSON file, and restores normal
    browser behavior unconditionally on exit, atexit, or startup crash recovery.
    """

    BROWSER_PROCESS_NAMES: FrozenSet[str] = frozenset(
        {
            "chrome.exe",
            "msedge.exe",
            "firefox.exe",
            "brave.exe",
            "opera.exe",
            "vivaldi.exe",
            "arc.exe",
        }
    )

    def __init__(
        self,
        backup_path: Optional[Path] = None,
        read_user_progid_fn: Optional[Callable[[], str]] = None,
        read_hklm_command_fn: Optional[Callable[[str], Optional[str]]] = None,
        read_hkcu_command_fn: Optional[Callable[[str], Optional[str]]] = None,
        write_hkcu_command_fn: Optional[Callable[[str, str], bool]] = None,
        delete_hkcu_command_fn: Optional[Callable[[str], bool]] = None,
    ) -> None:
        self._backup_path = backup_path or (Path.home() / ".soartv_browser_isolation_backup.json")
        self._read_user_progid_fn = read_user_progid_fn or self._native_read_user_https_progid
        self._read_hklm_command_fn = read_hklm_command_fn or self._native_read_hklm_command
        self._read_hkcu_command_fn = read_hkcu_command_fn or self._native_read_hkcu_command
        self._write_hkcu_command_fn = write_hkcu_command_fn or self._native_write_hkcu_command
        self._delete_hkcu_command_fn = delete_hkcu_command_fn or self._native_delete_hkcu_command
        self._lock = threading.Lock()
        self._engaged: bool = False
        self._active_prog_id: str = ""
        self._had_prior_hkcu: bool = False
        self._prior_hkcu_command: Optional[str] = None

    @staticmethod
    def _extract_exe_path(command: str) -> str:
        raw = (command or "").strip()
        if not raw:
            return ""
        if raw.startswith('"'):
            end_q = raw.find('"', 1)
            if end_q > 1:
                return raw[1:end_q].strip()
        parts = raw.split()
        return parts[0].strip('"') if parts else ""

    @classmethod
    def build_isolated_command(cls, prog_id: str, base_command: str) -> Optional[str]:
        """Builds a private/new-window launch command for known Windows browsers, or None if unsupported."""
        exe = cls._extract_exe_path(base_command)
        if not exe:
            return None
        pid_l = (prog_id or "").strip().lower()
        exe_l = Path(exe).name.lower()
        if "chrome" in pid_l or exe_l == "chrome.exe" or "brave" in pid_l or exe_l == "brave.exe":
            return f'"{exe}" --new-window --incognito -- "%1"'
        if "msedge" in pid_l or exe_l == "msedge.exe":
            return f'"{exe}" --new-window --inprivate -- "%1"'
        if "firefox" in pid_l or exe_l == "firefox.exe":
            return f'"{exe}" -private-window "%1"'
        if "vivaldi" in pid_l or exe_l == "vivaldi.exe" or "opera" in pid_l or exe_l == "opera.exe":
            return f'"{exe}" --new-window --incognito -- "%1"'
        return None

    @staticmethod
    def _native_read_user_https_progid() -> str:
        if sys.platform != "win32" or _is_automated_test_env():
            return ""
        try:
            import winreg  # type: ignore

            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                r"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice",
            ) as k:
                val, _ = winreg.QueryValueEx(k, "ProgId")
                return str(val or "").strip()
        except Exception:
            return ""

    @staticmethod
    def _native_read_hklm_command(prog_id: str) -> Optional[str]:
        if sys.platform != "win32" or not prog_id or _is_automated_test_env():
            return None
        try:
            import winreg  # type: ignore

            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                rf"SOFTWARE\Classes\{prog_id}\shell\open\command",
            ) as k:
                val, _ = winreg.QueryValueEx(k, "")
                return str(val) if val else None
        except Exception:
            return None

    @staticmethod
    def _native_read_hkcu_command(prog_id: str) -> Optional[str]:
        if sys.platform != "win32" or not prog_id or _is_automated_test_env():
            return None
        try:
            import winreg  # type: ignore

            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                rf"Software\Classes\{prog_id}\shell\open\command",
            ) as k:
                val, _ = winreg.QueryValueEx(k, "")
                return str(val) if val else None
        except Exception:
            return None

    @staticmethod
    def _native_write_hkcu_command(prog_id: str, command: str) -> bool:
        if sys.platform != "win32" or not prog_id or not command or _is_automated_test_env():
            return False
        try:
            import winreg  # type: ignore

            with winreg.CreateKey(
                winreg.HKEY_CURRENT_USER,
                rf"Software\Classes\{prog_id}\shell\open\command",
            ) as k:
                winreg.SetValueEx(k, "", 0, winreg.REG_SZ, command)
            return True
        except Exception as exc:
            AgentLogger.warning("BrowserLinkIsolationGuard write warning: %s", type(exc).__name__)
            return False

    @staticmethod
    def _native_delete_hkcu_command(prog_id: str) -> bool:
        if sys.platform != "win32" or not prog_id or _is_automated_test_env():
            return False
        try:
            import winreg  # type: ignore

            for sub in (
                rf"Software\Classes\{prog_id}\shell\open\command",
                rf"Software\Classes\{prog_id}\shell\open",
                rf"Software\Classes\{prog_id}\shell",
                rf"Software\Classes\{prog_id}",
            ):
                try:
                    winreg.DeleteKey(winreg.HKEY_CURRENT_USER, sub)
                except OSError:
                    pass
            return True
        except Exception:
            return False

    def engage(self) -> bool:
        """Installs the temporary in-session HKCU private browser override with crash-safe backup."""
        with self._lock:
            if self._engaged:
                return True
            prog_id = self._read_user_progid_fn()
            if not prog_id:
                return False
            prior_hkcu = self._read_hkcu_command_fn(prog_id)
            base_cmd = prior_hkcu or self._read_hklm_command_fn(prog_id)
            if not base_cmd:
                return False
            isolated_cmd = self.build_isolated_command(prog_id, base_cmd)
            if not isolated_cmd:
                return False

            backup_payload = {
                "progId": prog_id,
                "hadPriorHkcu": prior_hkcu is not None,
                "priorHkcuCommand": prior_hkcu,
                "updatedAt": time.time(),
            }
            try:
                self._backup_path.parent.mkdir(parents=True, exist_ok=True)
                self._backup_path.write_text(json.dumps(backup_payload), encoding="utf-8")
            except OSError:
                return False

            if not self._write_hkcu_command_fn(prog_id, isolated_cmd):
                try:
                    self._backup_path.unlink(missing_ok=True)
                except OSError:
                    pass
                return False

            self._engaged = True
            self._active_prog_id = prog_id
            self._had_prior_hkcu = prior_hkcu is not None
            self._prior_hkcu_command = prior_hkcu
            AgentLogger.info("BrowserLinkIsolationGuard engaged for ProgId '%s'.", prog_id)
            return True

    def restore(self) -> bool:
        """Restores the original browser handler and removes the crash-recovery backup."""
        with self._lock:
            if not self._engaged and not self._backup_path.is_file():
                return True
            prog_id = self._active_prog_id
            had_prior = self._had_prior_hkcu
            prior_cmd = self._prior_hkcu_command
            if not prog_id and self._backup_path.is_file():
                try:
                    saved = json.loads(self._backup_path.read_text(encoding="utf-8"))
                    prog_id = str(saved.get("progId", "")).strip()
                    had_prior = bool(saved.get("hadPriorHkcu", False))
                    prior_cmd = saved.get("priorHkcuCommand")
                except Exception:
                    prog_id = ""

            ok = True
            if prog_id:
                if had_prior and prior_cmd:
                    ok = bool(self._write_hkcu_command_fn(prog_id, str(prior_cmd)))
                else:
                    ok = bool(self._delete_hkcu_command_fn(prog_id))

            self._engaged = False
            self._active_prog_id = ""
            self._had_prior_hkcu = False
            self._prior_hkcu_command = None
            try:
                self._backup_path.unlink(missing_ok=True)
            except OSError:
                pass
            if prog_id:
                AgentLogger.info("BrowserLinkIsolationGuard restored ProgId '%s'.", prog_id)
            return ok

    def recover_stale_override(self) -> bool:
        """Cleans up any leftover browser override left behind by an unclean power loss or crash."""
        if not self._backup_path.is_file():
            return False
        return self.restore()


class CursorPrivacyController:
    """
    Parks the Windows desktop mouse cursor off-screen during gamepad streaming and restores it on exit.

    Why: Sunshine's DXGI Desktop Duplication composites the hardware mouse cursor whenever it sits
    inside the captured monitor, leaving a distracting arrow over controller-driven TV games.
    Parking inside the primary monitor's bottom-right corner also prevents multi-monitor games from
    spawning on a secondary display that happens to sit to the right of the primary display.
    What: Saves the initial cursor (x, y), parks the cursor at the extreme bottom-right pixel of the
    primary monitor, and restores the original coordinates when the streaming session ends.
    """

    def __init__(
        self,
        get_cursor_pos_fn: Optional[Callable[[], Optional[Tuple[int, int]]]] = None,
        set_cursor_pos_fn: Optional[Callable[[int, int], bool]] = None,
        get_virtual_bounds_fn: Optional[Callable[[], Tuple[int, int, int, int]]] = None,
        get_primary_bounds_fn: Optional[Callable[[], Tuple[int, int, int, int]]] = None,
    ) -> None:
        self._get_pos = get_cursor_pos_fn or self._default_get_cursor_pos
        self._set_pos = set_cursor_pos_fn or self._default_set_cursor_pos
        if get_primary_bounds_fn is not None:
            self._get_bounds = get_primary_bounds_fn
        elif get_virtual_bounds_fn is not None:
            self._get_bounds = get_virtual_bounds_fn
        else:
            self._get_bounds = PrimaryMonitorEnforcer._default_get_primary_rect
        self._lock = threading.Lock()
        self._saved_pos: Optional[Tuple[int, int]] = None

    @staticmethod
    def _default_get_cursor_pos() -> Optional[Tuple[int, int]]:
        if sys.platform != "win32" or _is_automated_test_env():
            return None
        pt = wintypes.POINT()
        if ctypes.windll.user32.GetCursorPos(ctypes.byref(pt)):
            return (int(pt.x), int(pt.y))
        return None

    @staticmethod
    def _default_set_cursor_pos(x: int, y: int) -> bool:
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        return bool(ctypes.windll.user32.SetCursorPos(int(x), int(y)))

    @staticmethod
    def _default_get_virtual_bounds() -> Tuple[int, int, int, int]:
        if sys.platform != "win32" or _is_automated_test_env():
            return (0, 0, 1920, 1080)
        u32 = ctypes.windll.user32
        vx = int(u32.GetSystemMetrics(SM_XVIRTUALSCREEN))
        vy = int(u32.GetSystemMetrics(SM_YVIRTUALSCREEN))
        vw = int(u32.GetSystemMetrics(SM_CXVIRTUALSCREEN))
        vh = int(u32.GetSystemMetrics(SM_CYVIRTUALSCREEN))
        if vw <= 0 or vh <= 0:
            return (0, 0, 1920, 1080)
        return (vx, vy, vw, vh)

    def park_cursor(self) -> bool:
        """Saves the current cursor position (if not already saved) and parks at bottom-right corner."""
        with self._lock:
            if self._saved_pos is None:
                self._saved_pos = self._get_pos()
            vx, vy, vw, vh = self._get_bounds()
            park_x = vx + max(1, vw) - 1
            park_y = vy + max(1, vh) - 1
            return self._set_pos(park_x, park_y)

    def re_park_without_overwriting_saved(self) -> bool:
        """Moves cursor back to the parked edge without overwriting the saved pre-stream position."""
        vx, vy, vw, vh = self._get_bounds()
        park_x = vx + max(1, vw) - 1
        park_y = vy + max(1, vh) - 1
        return self._set_pos(park_x, park_y)

    def restore_cursor(self) -> bool:
        """Restores the cursor to its pre-stream position if one was saved."""
        with self._lock:
            saved = self._saved_pos
            self._saved_pos = None
        if saved is None:
            return False
        return self._set_pos(saved[0], saved[1])


ENUM_CURRENT_SETTINGS: int = -1
DM_PELSWIDTH: int = 0x00080000
DM_PELSHEIGHT: int = 0x00100000
DM_DISPLAYFREQUENCY: int = 0x00400000
CDS_TEST: int = 0x00000002
DISP_CHANGE_SUCCESSFUL: int = 0
TARGET_STREAM_REFRESH_HZ: int = 60
FRACTIONAL_NTSC_REFRESH_HZ: int = 59


class DisplayRefreshRateController:
    """
    Synchronizes a 59.94Hz (Windows '59Hz') primary monitor to exact 60.00Hz during active 60fps streaming.

    Why: When the primary scanout monitor runs at 59.9401Hz while Sunshine & the TV run at 60.0000fps,
    DXGI Desktop Duplication misses 1 VSYNC beat every ~16.7s and micro-judders regardless of Mbps or resolution.
    What: Probes primary DEVMODEW.dmDisplayFrequency via EnumDisplaySettingsW, switches 59Hz -> 60Hz if
    CDS_TEST confirms hardware support at identical resolution, and restores 59Hz deterministically on exit.
    """

    def __init__(
        self,
        get_primary_refresh_hz_fn: Optional[Callable[[], int]] = None,
        set_primary_refresh_hz_fn: Optional[Callable[[int], bool]] = None,
    ) -> None:
        self._custom_get_fn = get_primary_refresh_hz_fn
        self._custom_set_fn = set_primary_refresh_hz_fn
        self._lock = threading.Lock()
        self._saved_hz: int = 0
        self._changed_by_us: bool = False

    @staticmethod
    def _is_automated_test_env() -> bool:
        return (
            _is_automated_test_env()
            and os.environ.get("SOARTV_ALLOW_LIVE_DISPLAY_HZ_SWITCH") != "1"
        )

    @classmethod
    def _build_devmode_struct(cls) -> Any:
        class DEVMODEW(ctypes.Structure):
            _fields_ = [
                ("dmDeviceName", wintypes.WCHAR * 32),
                ("dmSpecVersion", wintypes.WORD),
                ("dmDriverVersion", wintypes.WORD),
                ("dmSize", wintypes.WORD),
                ("dmDriverExtra", wintypes.WORD),
                ("dmFields", wintypes.DWORD),
                ("dmPositionX", wintypes.LONG),
                ("dmPositionY", wintypes.LONG),
                ("dmDisplayOrientation", wintypes.DWORD),
                ("dmDisplayFixedOutput", wintypes.DWORD),
                ("dmColor", ctypes.c_short),
                ("dmDuplex", ctypes.c_short),
                ("dmYResolution", wintypes.SHORT),
                ("dmTTOption", wintypes.SHORT),
                ("dmCollate", wintypes.SHORT),
                ("dmFormName", wintypes.WCHAR * 32),
                ("dmLogPixels", wintypes.WORD),
                ("dmBitsPerPel", wintypes.DWORD),
                ("dmPelsWidth", wintypes.DWORD),
                ("dmPelsHeight", wintypes.DWORD),
                ("dmDisplayFlags", wintypes.DWORD),
                ("dmDisplayFrequency", wintypes.DWORD),
                ("dmICMMethod", wintypes.DWORD),
                ("dmICMIntent", wintypes.DWORD),
                ("dmMediaType", wintypes.DWORD),
                ("dmDitherType", wintypes.DWORD),
                ("dmReserved1", wintypes.DWORD),
                ("dmReserved2", wintypes.DWORD),
                ("dmPanningWidth", wintypes.DWORD),
                ("dmPanningHeight", wintypes.DWORD),
            ]
        dm = DEVMODEW()
        dm.dmSize = ctypes.sizeof(DEVMODEW)
        return dm

    @classmethod
    def get_primary_display_mode(cls) -> Tuple[int, int, int]:
        """Returns (width, height, refresh_hz) of the primary Windows display via EnumDisplaySettingsW."""
        if sys.platform != "win32" or cls._is_automated_test_env():
            return (0, 0, 0)
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            dm = cls._build_devmode_struct()
            if u32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
                return (int(dm.dmPelsWidth), int(dm.dmPelsHeight), int(dm.dmDisplayFrequency))
        except Exception as exc:
            AgentLogger.debug("EnumDisplaySettingsW display mode read skipped: %s", exc)
        return (0, 0, 0)

    @classmethod
    def _native_get_primary_refresh_hz(cls) -> int:
        if sys.platform != "win32" or cls._is_automated_test_env():
            return 0
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            dm = cls._build_devmode_struct()
            if u32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
                return int(dm.dmDisplayFrequency)
        except Exception as exc:
            AgentLogger.debug("EnumDisplaySettingsW read skipped: %s", exc)
        return 0

    @classmethod
    def _native_set_primary_refresh_hz(cls, target_hz: int) -> bool:
        if sys.platform != "win32" or target_hz <= 0 or cls._is_automated_test_env():
            return False
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            dm = cls._build_devmode_struct()
            if not u32.EnumDisplaySettingsW(None, ENUM_CURRENT_SETTINGS, ctypes.byref(dm)):
                return False
            dm.dmDisplayFrequency = int(target_hz)
            dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY
            test_res = int(u32.ChangeDisplaySettingsExW(None, ctypes.byref(dm), None, CDS_TEST, None))
            if test_res != DISP_CHANGE_SUCCESSFUL:
                return False
            apply_res = int(u32.ChangeDisplaySettingsExW(None, ctypes.byref(dm), None, 0, None))
            return apply_res == DISP_CHANGE_SUCCESSFUL
        except Exception as exc:
            AgentLogger.debug("ChangeDisplaySettingsExW %dHz skipped: %s", target_hz, exc)
            return False

    def engage_60hz(self) -> bool:
        """Switches primary display from 59Hz (59.94Hz) to 60Hz if needed; idempotent."""
        with self._lock:
            if self._changed_by_us:
                return True
            get_fn = self._custom_get_fn or self._native_get_primary_refresh_hz
            set_fn = self._custom_set_fn or self._native_set_primary_refresh_hz
            cur = int(get_fn() or 0)
            if cur == FRACTIONAL_NTSC_REFRESH_HZ:
                if set_fn(TARGET_STREAM_REFRESH_HZ):
                    self._saved_hz = cur
                    self._changed_by_us = True
                    AgentLogger.info("DisplayRefreshRateController: locked primary display 59Hz -> 60Hz.")
                    return True
                return False
            return True

    def restore(self) -> bool:
        """Restores the pre-session refresh rate if DisplayRefreshRateController changed it."""
        with self._lock:
            if not self._changed_by_us or self._saved_hz <= 0:
                return True
            set_fn = self._custom_set_fn or self._native_set_primary_refresh_hz
            ok = bool(set_fn(self._saved_hz))
            if ok:
                AgentLogger.info("DisplayRefreshRateController: restored primary display to %dHz.", self._saved_hz)
                self._changed_by_us = False
                self._saved_hz = 0
            return ok


class PCPrivacySafetyNet:
    """
    Guarantees PC privacy during streaming: mutes audio, minimizes open windows, and hides desktop icons.

    Why: Sunshine DXGI desktop duplication captures everything on the active monitor and default audio
    endpoint; without a privacy safety net, private browser tabs, chats, desktop icons, and speakers
    are exposed to the TV and anyone in the PC's room.
    What: Uses native COM IAudioEndpointVolume to mute master audio (recording was_muted and restoring on exit),
    Win32 EnumWindows to minimize visible application windows (restoring on exit), and SHELLDLL_DefView to
    hide desktop icons (restoring on exit). Fully idempotent, thread-safe, and registered with atexit.
    """

    def __init__(
        self,
        mute_audio_fn: Optional[Callable[[], Tuple[bool, bool]]] = None,
        unmute_audio_fn: Optional[Callable[[bool], bool]] = None,
        minimize_windows_fn: Optional[Callable[[], List[int]]] = None,
        restore_windows_fn: Optional[Callable[[Sequence[int]], None]] = None,
        hide_desktop_fn: Optional[Callable[[], Tuple[bool, int]]] = None,
        restore_desktop_fn: Optional[Callable[[bool, int], None]] = None,
        refresh_controller: Optional[DisplayRefreshRateController] = None,
        browser_isolation_guard: Optional[BrowserLinkIsolationGuard] = None,
    ) -> None:
        self._lock = threading.RLock()
        self._engaged: bool = False
        self._was_muted: bool = False
        self._audio_muted_by_us: bool = False
        self._minimized_hwnds: List[int] = []
        self._preexisting_browser_hwnds: List[int] = []
        self._desktop_was_visible: bool = False
        self._defview_hwnd: int = 0

        self._custom_mute_audio_fn = mute_audio_fn
        self._custom_unmute_audio_fn = unmute_audio_fn
        self._custom_minimize_windows_fn = minimize_windows_fn
        self._custom_restore_windows_fn = restore_windows_fn
        self._custom_hide_desktop_fn = hide_desktop_fn
        self._custom_restore_desktop_fn = restore_desktop_fn
        self._refresh_controller: Optional[DisplayRefreshRateController] = refresh_controller
        self._browser_isolation_guard: Optional[BrowserLinkIsolationGuard] = (
            browser_isolation_guard or BrowserLinkIsolationGuard()
        )

        try:
            atexit.register(self.restore)
        except Exception as exc:
            AgentLogger.debug("atexit registration warning: %s", exc)

    @classmethod
    def _native_mute_audio(cls) -> Tuple[bool, bool]:
        """Queries audio mute status and mutes default multimedia endpoint via COM IAudioEndpointVolume."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False, False
        ole32 = ctypes.oledll.ole32
        ole32.CoInitialize(None)
        try:
            class GUID(ctypes.Structure):
                _fields_ = [
                    ("Data1", wintypes.DWORD),
                    ("Data2", wintypes.WORD),
                    ("Data3", wintypes.WORD),
                    ("Data4", ctypes.c_byte * 8),
                ]

                def __init__(self, s: str):
                    super().__init__()
                    ole32.CLSIDFromString(ctypes.c_wchar_p(s), ctypes.byref(self))

            clsid_enum = GUID(CLSID_MM_DEVICE_ENUMERATOR)
            iid_enum = GUID(IID_IMM_DEVICE_ENUMERATOR)
            iid_vol = GUID(IID_IAUDIO_ENDPOINT_VOLUME)

            p_enum = ctypes.c_void_p()
            hr = ole32.CoCreateInstance(
                ctypes.byref(clsid_enum),
                None,
                CLSCTX_ALL,
                ctypes.byref(iid_enum),
                ctypes.byref(p_enum),
            )
            if hr != 0 or not p_enum.value:
                return False, False

            vtbl_enum = ctypes.cast(
                p_enum, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            get_endpoint = ctypes.WINFUNCTYPE(
                ctypes.HRESULT,
                ctypes.c_void_p,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.POINTER(ctypes.c_void_p),
            )(vtbl_enum[4])

            p_dev = ctypes.c_void_p()
            hr = get_endpoint(p_enum, 0, 1, ctypes.byref(p_dev))
            if hr != 0 or not p_dev.value:
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_enum[2])(p_enum)
                return False, False

            vtbl_dev = ctypes.cast(
                p_dev, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            activate = ctypes.WINFUNCTYPE(
                ctypes.HRESULT,
                ctypes.c_void_p,
                ctypes.POINTER(GUID),
                wintypes.DWORD,
                ctypes.c_void_p,
                ctypes.POINTER(ctypes.c_void_p),
            )(vtbl_dev[3])

            p_vol = ctypes.c_void_p()
            hr = activate(p_dev, ctypes.byref(iid_vol), CLSCTX_ALL, None, ctypes.byref(p_vol))
            if hr != 0 or not p_vol.value:
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_dev[2])(p_dev)
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_enum[2])(p_enum)
                return False, False

            vtbl_vol = ctypes.cast(
                p_vol, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            get_mute = ctypes.WINFUNCTYPE(
                ctypes.HRESULT, ctypes.c_void_p, ctypes.POINTER(wintypes.BOOL)
            )(vtbl_vol[15])
            is_muted = wintypes.BOOL()
            get_mute(p_vol, ctypes.byref(is_muted))
            was_muted = bool(is_muted.value)

            if not was_muted:
                set_mute = ctypes.WINFUNCTYPE(
                    ctypes.HRESULT, ctypes.c_void_p, wintypes.BOOL, ctypes.c_void_p
                )(vtbl_vol[14])
                set_mute(p_vol, wintypes.BOOL(True), None)

            rel = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)
            rel(vtbl_vol[2])(p_vol)
            rel(vtbl_dev[2])(p_dev)
            rel(vtbl_enum[2])(p_enum)
            return True, was_muted
        except Exception as exc:
            AgentLogger.warning("Native COM audio mute warning: %s", exc)
            return False, False
        finally:
            ole32.CoUninitialize()

    @classmethod
    def _native_unmute_audio(cls, restore_muted_state: bool) -> bool:
        """Restores audio mute state via COM IAudioEndpointVolume."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        ole32 = ctypes.oledll.ole32
        ole32.CoInitialize(None)
        try:
            class GUID(ctypes.Structure):
                _fields_ = [
                    ("Data1", wintypes.DWORD),
                    ("Data2", wintypes.WORD),
                    ("Data3", wintypes.WORD),
                    ("Data4", ctypes.c_byte * 8),
                ]

                def __init__(self, s: str):
                    super().__init__()
                    ole32.CLSIDFromString(ctypes.c_wchar_p(s), ctypes.byref(self))

            clsid_enum = GUID(CLSID_MM_DEVICE_ENUMERATOR)
            iid_enum = GUID(IID_IMM_DEVICE_ENUMERATOR)
            iid_vol = GUID(IID_IAUDIO_ENDPOINT_VOLUME)

            p_enum = ctypes.c_void_p()
            hr = ole32.CoCreateInstance(
                ctypes.byref(clsid_enum),
                None,
                CLSCTX_ALL,
                ctypes.byref(iid_enum),
                ctypes.byref(p_enum),
            )
            if hr != 0 or not p_enum.value:
                return False

            vtbl_enum = ctypes.cast(
                p_enum, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            get_endpoint = ctypes.WINFUNCTYPE(
                ctypes.HRESULT,
                ctypes.c_void_p,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.POINTER(ctypes.c_void_p),
            )(vtbl_enum[4])

            p_dev = ctypes.c_void_p()
            hr = get_endpoint(p_enum, 0, 1, ctypes.byref(p_dev))
            if hr != 0 or not p_dev.value:
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_enum[2])(p_enum)
                return False

            vtbl_dev = ctypes.cast(
                p_dev, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            activate = ctypes.WINFUNCTYPE(
                ctypes.HRESULT,
                ctypes.c_void_p,
                ctypes.POINTER(GUID),
                wintypes.DWORD,
                ctypes.c_void_p,
                ctypes.POINTER(ctypes.c_void_p),
            )(vtbl_dev[3])

            p_vol = ctypes.c_void_p()
            hr = activate(p_dev, ctypes.byref(iid_vol), CLSCTX_ALL, None, ctypes.byref(p_vol))
            if hr != 0 or not p_vol.value:
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_dev[2])(p_dev)
                ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)(vtbl_enum[2])(p_enum)
                return False

            vtbl_vol = ctypes.cast(
                p_vol, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))
            ).contents
            set_mute = ctypes.WINFUNCTYPE(
                ctypes.HRESULT, ctypes.c_void_p, wintypes.BOOL, ctypes.c_void_p
            )(vtbl_vol[14])
            set_mute(p_vol, wintypes.BOOL(restore_muted_state), None)

            rel = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)
            rel(vtbl_vol[2])(p_vol)
            rel(vtbl_dev[2])(p_dev)
            rel(vtbl_enum[2])(p_enum)
            return True
        except Exception as exc:
            AgentLogger.warning("Native COM audio unmute warning: %s", exc)
            return False
        finally:
            ole32.CoUninitialize()

    @classmethod
    def _native_minimize_open_windows(cls) -> List[int]:
        """Minimizes visible non-system top-level windows via EnumWindows and returns their HWNDs."""
        if sys.platform != "win32" or _is_automated_test_env():
            return []
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        try:
            dwmapi = ctypes.WinDLL("dwmapi", use_last_error=True)
        except OSError:
            dwmapi = None

        minimized: List[int] = []
        enum_proc_t = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

        def enum_cb(hwnd: int, _lparam: int) -> bool:
            if not hwnd or not u32.IsWindowVisible(hwnd) or u32.IsIconic(hwnd):
                return True
            cls_buf = ctypes.create_unicode_buffer(128)
            u32.GetClassNameW(hwnd, cls_buf, 128)
            cname = cls_buf.value.strip()
            if cname in (
                "Progman",
                "WorkerW",
                "Shell_TrayWnd",
                "Shell_SecondaryTrayWnd",
                PRIVACY_CURTAIN_CLASS_NAME,
            ):
                return True
            if dwmapi is not None:
                cloaked = wintypes.DWORD(0)
                if (
                    dwmapi.DwmGetWindowAttribute(
                        wintypes.HWND(hwnd),
                        DWMWA_CLOAKED,
                        ctypes.byref(cloaked),
                        ctypes.sizeof(cloaked),
                    )
                    == 0
                    and cloaked.value != 0
                ):
                    return True
            try:
                u32.ShowWindow(wintypes.HWND(hwnd), SW_MINIMIZE)
                minimized.append(int(hwnd))
            except Exception as exc:
                AgentLogger.debug("Minimize window 0x%x error: %s", hwnd, exc)
            return True

        try:
            u32.EnumWindows(enum_proc_t(enum_cb), 0)
        except Exception as exc:
            AgentLogger.warning("EnumWindows minimization warning: %s", exc)
        return minimized

    def get_preexisting_browser_hwnds(self) -> List[int]:
        """Returns HWNDs of pre-existing personal browser windows minimized at session start."""
        with self._lock:
            return list(self._preexisting_browser_hwnds)

    def set_preexisting_browser_hwnds_for_test(self, hwnds: Sequence[int]) -> None:
        """Sets tracked pre-existing personal browser HWNDs for deterministic unit testing."""
        with self._lock:
            self._preexisting_browser_hwnds = [int(h) for h in hwnds]

    @classmethod
    def _filter_browser_hwnds(cls, hwnds: Sequence[int]) -> List[int]:
        if sys.platform != "win32" or not hwnds or _is_automated_test_env():
            return []
        proc_map = Win32SystemProbes.snapshot_process_map()
        if not proc_map:
            return []
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        out: List[int] = []
        for hwnd in hwnds:
            try:
                pid = wintypes.DWORD(0)
                u32.GetWindowThreadProcessId(wintypes.HWND(hwnd), ctypes.byref(pid))
                pname = proc_map.get(int(pid.value), "").lower()
                if pname in BrowserLinkIsolationGuard.BROWSER_PROCESS_NAMES:
                    out.append(int(hwnd))
            except Exception:
                pass
        return out

    @classmethod
    def reminimize_unminimized_windows(cls, hwnds: Sequence[int]) -> int:
        """Immediately re-minimizes any tracked personal browser windows restored during streaming."""
        if sys.platform != "win32" or not hwnds or _is_automated_test_env():
            return 0
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        count = 0
        for hwnd in hwnds:
            try:
                wh = wintypes.HWND(hwnd)
                if u32.IsWindow(wh) and u32.IsWindowVisible(wh) and not u32.IsIconic(wh):
                    u32.ShowWindow(wh, SW_MINIMIZE)
                    count += 1
            except Exception:
                pass
        return count

    @classmethod
    def _native_restore_minimized_windows(cls, hwnds: Sequence[int]) -> None:
        """Restores previously minimized windows."""
        if sys.platform != "win32" or not hwnds or _is_automated_test_env():
            return
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        for hwnd in hwnds:
            try:
                if u32.IsWindow(wintypes.HWND(hwnd)) and u32.IsIconic(wintypes.HWND(hwnd)):
                    u32.ShowWindow(wintypes.HWND(hwnd), SW_RESTORE)
            except Exception as exc:
                AgentLogger.debug("Restore window 0x%x error: %s", hwnd, exc)

    @classmethod
    def _find_defview_hwnd(cls) -> int:
        """Locates the SHELLDLL_DefView window handle with 64-bit HWND type safety."""
        if sys.platform != "win32":
            return 0
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        u32.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
        u32.FindWindowW.restype = wintypes.HWND
        u32.FindWindowExW.argtypes = [wintypes.HWND, wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR]
        u32.FindWindowExW.restype = wintypes.HWND
        progman = u32.FindWindowW("Progman", None)
        if progman:
            defview = u32.FindWindowExW(progman, None, "SHELLDLL_DefView", None)
            if defview:
                return int(defview)
        workerw = None
        while True:
            workerw = u32.FindWindowExW(None, workerw, "WorkerW", None)
            if not workerw:
                break
            defview = u32.FindWindowExW(workerw, None, "SHELLDLL_DefView", None)
            if defview:
                return int(defview)
        return 0

    @classmethod
    def _native_hide_desktop(cls) -> Tuple[bool, int]:
        """Hides desktop icons via SHELLDLL_DefView and returns (was_visible, defview_hwnd)."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False, 0
        defview = cls._find_defview_hwnd()
        if not defview:
            return False, 0
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        was_visible = bool(u32.IsWindowVisible(wintypes.HWND(defview)))
        if was_visible:
            u32.ShowWindow(wintypes.HWND(defview), SW_HIDE)
        return was_visible, defview

    @classmethod
    def _native_restore_desktop(cls, was_visible: bool, defview_hwnd: int) -> None:
        """Restores desktop icons via SHELLDLL_DefView."""
        if sys.platform != "win32" or not defview_hwnd or not was_visible or _is_automated_test_env():
            return
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        if u32.IsWindow(wintypes.HWND(defview_hwnd)):
            u32.ShowWindow(wintypes.HWND(defview_hwnd), SW_SHOW)

    def engage(self) -> bool:
        """Engages the full PC privacy safety net: mutes audio, minimizes windows, and hides desktop."""
        with self._lock:
            if self._engaged:
                return True

            try:
                # 1. Audio muting
                if self._custom_mute_audio_fn is not None:
                    ok, was_muted = self._custom_mute_audio_fn()
                else:
                    ok, was_muted = self._native_mute_audio()
                if ok:
                    self._was_muted = was_muted
                    self._audio_muted_by_us = not was_muted

                # 2. Window minimization
                if self._custom_minimize_windows_fn is not None:
                    minimized = self._custom_minimize_windows_fn()
                else:
                    minimized = self._native_minimize_open_windows()
                self._minimized_hwnds = list(minimized)
                if not self._preexisting_browser_hwnds:
                    self._preexisting_browser_hwnds = self._filter_browser_hwnds(self._minimized_hwnds)

                # 3. Desktop icon hiding
                if self._custom_hide_desktop_fn is not None:
                    was_vis, defview = self._custom_hide_desktop_fn()
                else:
                    was_vis, defview = self._native_hide_desktop()
                self._desktop_was_visible = was_vis
                self._defview_hwnd = defview

                # 4. Lock 59.94Hz primary display to exact 60.00Hz for tear-free 60fps capture
                if not hasattr(self, "_refresh_controller") or self._refresh_controller is None:
                    self._refresh_controller = DisplayRefreshRateController()
                self._refresh_controller.engage_60hz()

                # 5. Isolate external browser links opened by games into fresh private/incognito windows
                if hasattr(self, "_browser_isolation_guard") and self._browser_isolation_guard is not None:
                    try:
                        self._browser_isolation_guard.engage()
                    except Exception as iso_exc:
                        AgentLogger.debug("BrowserLinkIsolationGuard engage skipped: %s", iso_exc)

                self._engaged = True
                AgentLogger.info(
                    "PCPrivacySafetyNet engaged: audio muted (was_muted=%s), %d windows minimized, desktop hidden=%s.",
                    self._was_muted,
                    len(self._minimized_hwnds),
                    self._desktop_was_visible,
                )
                return True
            except Exception as exc:
                AgentLogger.warning("PCPrivacySafetyNet engage warning: %s", exc)
                self._engaged = False
                # Rollback any already-engaged step
                if self._desktop_was_visible:
                    try:
                        if self._custom_restore_desktop_fn is not None:
                            self._custom_restore_desktop_fn(self._desktop_was_visible, self._defview_hwnd)
                        else:
                            self._native_restore_desktop(self._desktop_was_visible, self._defview_hwnd)
                    except Exception as rb_exc:
                        AgentLogger.debug("PCPrivacySafetyNet desktop rollback error: %s", rb_exc)
                    finally:
                        self._desktop_was_visible = False
                        self._defview_hwnd = 0

                if self._minimized_hwnds:
                    try:
                        if self._custom_restore_windows_fn is not None:
                            self._custom_restore_windows_fn(self._minimized_hwnds)
                        else:
                            self._native_restore_minimized_windows(self._minimized_hwnds)
                    except Exception as rb_exc:
                        AgentLogger.debug("PCPrivacySafetyNet windows rollback error: %s", rb_exc)
                    finally:
                        self._minimized_hwnds = []

                if self._audio_muted_by_us:
                    try:
                        if self._custom_unmute_audio_fn is not None:
                            self._custom_unmute_audio_fn(self._was_muted)
                        else:
                            self._native_unmute_audio(self._was_muted)
                    except Exception as rb_exc:
                        AgentLogger.debug("PCPrivacySafetyNet audio rollback error: %s", rb_exc)
                    finally:
                        self._audio_muted_by_us = False
                        self._was_muted = False

                return False

    def restore(self) -> bool:
        """Restores PC state: unhides desktop icons, restores minimized windows, and restores audio."""
        with self._lock:
            if not self._engaged and not self._audio_muted_by_us and not self._minimized_hwnds and not self._desktop_was_visible:
                return True

            all_ok = True

            # 1. Restore desktop icons
            if self._desktop_was_visible:
                try:
                    if self._custom_restore_desktop_fn is not None:
                        self._custom_restore_desktop_fn(self._desktop_was_visible, self._defview_hwnd)
                    else:
                        self._native_restore_desktop(self._desktop_was_visible, self._defview_hwnd)
                    self._desktop_was_visible = False
                    self._defview_hwnd = 0
                except Exception as exc:
                    AgentLogger.warning("PCPrivacySafetyNet desktop restore warning: %s", exc)
                    all_ok = False
            else:
                self._desktop_was_visible = False
                self._defview_hwnd = 0

            # 2. Restore windows
            if self._minimized_hwnds:
                try:
                    hwnds = list(self._minimized_hwnds)
                    if self._custom_restore_windows_fn is not None:
                        self._custom_restore_windows_fn(hwnds)
                    else:
                        self._native_restore_minimized_windows(hwnds)
                    self._minimized_hwnds = []
                except Exception as exc:
                    AgentLogger.warning("PCPrivacySafetyNet windows restore warning: %s", exc)
                    all_ok = False

            # 3. Restore audio
            if self._audio_muted_by_us:
                try:
                    if self._custom_unmute_audio_fn is not None:
                        self._custom_unmute_audio_fn(self._was_muted)
                    else:
                        self._native_unmute_audio(self._was_muted)
                    self._audio_muted_by_us = False
                    self._was_muted = False
                except Exception as exc:
                    AgentLogger.warning("PCPrivacySafetyNet audio restore warning: %s", exc)
                    all_ok = False
            else:
                self._audio_muted_by_us = False
                self._was_muted = False

            if hasattr(self, "_refresh_controller") and self._refresh_controller is not None:
                try:
                    self._refresh_controller.restore()
                except Exception as hz_exc:
                    AgentLogger.debug("Refresh rate restore warning: %s", hz_exc)

            if hasattr(self, "_browser_isolation_guard") and self._browser_isolation_guard is not None:
                try:
                    self._browser_isolation_guard.restore()
                except Exception as iso_exc:
                    AgentLogger.debug("BrowserLinkIsolationGuard restore warning: %s", iso_exc)
            self._preexisting_browser_hwnds = []

            if all_ok:
                self._engaged = False
                AgentLogger.info("PCPrivacySafetyNet restored: desktop, windows, and audio reverted.")
                return True
            else:
                AgentLogger.warning("PCPrivacySafetyNet restore partial failure; remaining state preserved for retry.")
                return False

    def is_engaged(self) -> bool:
        """Returns True if the safety net is currently engaged."""
        with self._lock:
            return self._engaged


class PhysicalEscAbortListener:
    """
    Background daemon listener that polls physical ESC key state to provide an instant fail-safe abort.

    Why: If streaming automation freezes or the user physically touches the PC, pressing ESC
    must immediately restore the PC to its normal state, dismiss the curtain, unmute audio,
    restore windows, and cancel the stream without requiring Task Manager.
    What: Polls GetAsyncKeyState(0x1B) at ~30Hz in a background daemon thread, executing
    the abort callback on key-down.
    """

    VK_ESCAPE: int = 0x1B
    POLL_INTERVAL_SEC: float = 0.033  # ~30Hz

    def __init__(
        self,
        poll_interval_sec: float = POLL_INTERVAL_SEC,
        key_state_fn: Optional[Callable[[int], int]] = None,
    ) -> None:
        self._poll_interval = poll_interval_sec
        self._key_state_fn = key_state_fn
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        self._on_abort: Optional[Callable[[], None]] = None

    def start(self, on_abort: Callable[[], None]) -> bool:
        """Starts the background ESC polling listener."""
        with self._lock:
            self._on_abort = on_abort
            self._stop_event.clear()
            if self._thread is not None and self._thread.is_alive():
                return True
            self._thread = threading.Thread(
                target=self._poll_loop,
                daemon=True,
                name="soartv-esc-abort-listener",
            )
            self._thread.start()
            return True

    def stop(self) -> None:
        """Stops the background ESC polling listener."""
        with self._lock:
            self._stop_event.set()
            thread = self._thread
            self._thread = None
        if thread is not None and thread.is_alive() and threading.current_thread() != thread:
            thread.join(timeout=0.20)

    def is_running(self) -> bool:
        """Returns True if the listener thread is active."""
        with self._lock:
            return self._thread is not None and self._thread.is_alive()

    def _poll_loop(self) -> None:
        if (sys.platform != "win32" or _is_automated_test_env()) and self._key_state_fn is None:
            return
        u32 = ctypes.windll.user32 if (sys.platform == "win32" and self._key_state_fn is None) else None
        while not self._stop_event.is_set():
            try:
                if self._key_state_fn is not None:
                    state = self._key_state_fn(self.VK_ESCAPE)
                elif u32 is not None:
                    state = u32.GetAsyncKeyState(self.VK_ESCAPE)
                else:
                    state = 0
                if bool(state & 0x8000):
                    if self._stop_event.is_set():
                        break
                    AgentLogger.info("Physical ESC key detected; triggering abort callback.")
                    with self._lock:
                        cb = self._on_abort
                    if cb is not None:
                        try:
                            cb()
                        except Exception as exc:
                            AgentLogger.warning("ESC abort callback error: %s", exc)
                    break
            except Exception as exc:
                AgentLogger.debug("ESC poll exception: %s", exc)
            self._stop_event.wait(self._poll_interval)


class PrivacyCurtainManager:
    """
    Masks personal Windows desktop windows with a pitch-black native Win32 curtain during game streaming.

    Why: Sunshine captures the monitor via DXGI Desktop Duplication; without a privacy curtain, the TV
    displays the user's open browsers, messages, and taskbar before a game opens, behind windowed games,
    or if a game exits early.
    What: Opens a borderless pitch-black virtual-desktop window with a blank cursor, keeps it TOPMOST
    until a real game window renders, slots it directly behind the game HWND during play, triggers a
    guaranteed interactive game launch fallback if Sunshine resumed Desktop without spawning, and
    destroys the curtain automatically as soon as Moonlight disconnects.
    """

    DEFAULT_SUNSHINE_LOGS: Tuple[Path, ...] = (
        Path(r"C:\Program Files\Sunshine\config\sunshine.log"),
        Path(os.environ.get("ProgramData", r"C:\ProgramData")) / "Sunshine" / "config" / "sunshine.log",
    )

    NON_GAME_PROCESS_NAMES: FrozenSet[str] = frozenset(
        {
            "explorer.exe",
            "searchhost.exe",
            "searchapp.exe",
            "shellexperiencehost.exe",
            "startmenuexperiencehost.exe",
            "textinputhost.exe",
            "lockapp.exe",
            "logonui.exe",
            "applicationframehost.exe",
            "systemsettings.exe",
            "taskmgr.exe",
            "mmc.exe",
            "control.exe",
            "dwm.exe",
            "csrss.exe",
            "svchost.exe",
            "sihost.exe",
            "ctfmon.exe",
            "widgets.exe",
            "widgetservice.exe",
            "gamebar.exe",
            "gamebarftserver.exe",
            "nvidia share.exe",
            "nvcontainer.exe",
            "nvidia overlay.exe",
            "sunshine.exe",
            "sunshinesvc.exe",
            "cmd.exe",
            "powershell.exe",
            "pwsh.exe",
            "conhost.exe",
            "windowsterminal.exe",
            "openconsole.exe",
            "wscript.exe",
            "cscript.exe",
            "python.exe",
            "pythonw.exe",
            "code.exe",
            "antigravity.exe",
            "devenv.exe",
            "notepad.exe",
            "notepad++.exe",
            "chrome.exe",
            "msedge.exe",
            "msedgewebview2.exe",
            "firefox.exe",
            "brave.exe",
            "opera.exe",
            "vivaldi.exe",
            "vlc.exe",
            "discord.exe",
            "spotify.exe",
            "whatsapp.exe",
            "telegram.exe",
            "slack.exe",
            "teams.exe",
            "zoom.exe",
            "outlook.exe",
            "olk.exe",
            "onedrive.exe",
            "pycharm64.exe",
            "killernetworkservice.exe",
            "killercontrolcenter.exe",
            "epicgameslauncher.exe",
            "epicwebhelper.exe",
            "epicgameslauncher-win64-shipping.exe",
            "eosoverlayrenderer-win64-shipping.exe",
            "heroic.exe",
            "legendary.exe",
            "gogdl.exe",
            "nile.exe",
            "comet.exe",
            "galaxycommunication.exe",
        }
    )

    NON_GAME_WINDOW_CLASSES: FrozenSet[str] = frozenset(
        {
            PRIVACY_CURTAIN_CLASS_NAME,
            "Progman",
            "WorkerW",
            "Shell_TrayWnd",
            "Shell_SecondaryTrayWnd",
            "Windows.UI.Core.CoreWindow",
            "XamlExplorerHostIslandWindow",
        }
    )

    _class_registered_lock = threading.Lock()
    _class_registered: bool = False
    _wndproc_ref: Any = None
    _blank_cursor_handle: int = 0

    def __init__(
        self,
        sunshine_log_path: Optional[Path] = None,
        cursor_controller: Optional[CursorPrivacyController] = None,
        find_game_hwnd_fn: Optional[Callable[[GameEntry], int]] = None,
        apply_curtain_z_order_fn: Optional[Callable[[str, int], None]] = None,
        fallback_launch_fn: Optional[Callable[[GameEntry], bool]] = None,
        create_native_window: Optional[bool] = None,
        poll_interval_sec: float = PRIVACY_CURTAIN_POLL_SEC,
        fallback_spawn_grace_sec: float = PRIVACY_CURTAIN_FALLBACK_GRACE_SEC,
        connect_timeout_sec: float = PRIVACY_CURTAIN_CONNECT_TIMEOUT_SEC,
        max_session_sec: float = PRIVACY_CURTAIN_MAX_SESSION_SEC,
        safety_net: Optional[PCPrivacySafetyNet] = None,
        esc_listener: Optional[PhysicalEscAbortListener] = None,
        process_controller: Optional[Any] = None,
        get_hwnd_pid_fn: Optional[Callable[[int], int]] = None,
        process_snapshot_fn: Optional[Callable[[], Dict[int, str]]] = None,
        close_sunshine_app_fn: Optional[Callable[[], bool]] = None,
        disconnect_grace_sec: float = PRIVACY_CURTAIN_DISCONNECT_GRACE_SEC,
        steam_cold_retry_delay_sec: float = STEAM_COLD_RETRY_DELAY_SEC,
        is_steam_ready_fn: Optional[Callable[[], bool]] = None,
        steam_retry_launch_fn: Optional[Callable[[GameEntry], bool]] = None,
        epic_cold_retry_delay_sec: float = EPIC_COLD_RETRY_DELAY_SEC,
        is_epic_ready_fn: Optional[Callable[[], bool]] = None,
        epic_retry_launch_fn: Optional[Callable[[GameEntry], bool]] = None,
        on_session_end_fn: Optional[Callable[[str], Any]] = None,
        ensure_on_primary_monitor_fn: Optional[Callable[[int], bool]] = None,
        shield_personal_browsers_fn: Optional[Callable[[Sequence[int]], int]] = None,
    ) -> None:
        self._explicit_log_path = sunshine_log_path
        self.cursor_controller = cursor_controller or CursorPrivacyController()
        self._find_game_hwnd_fn = find_game_hwnd_fn or self.find_visible_game_hwnd
        self._custom_z_order_fn = apply_curtain_z_order_fn
        self._fallback_launch_fn = fallback_launch_fn or (
            lambda g: WindowsSessionController.launch_game(
                g, force_hdmi_only=False, user_approved=True
            )
        )
        self._create_native_window = (
            (sys.platform == "win32") if create_native_window is None else create_native_window
        )
        self._poll_interval_sec = poll_interval_sec
        self._fallback_spawn_grace_sec = fallback_spawn_grace_sec
        self._connect_timeout_sec = connect_timeout_sec
        self._max_session_sec = max_session_sec
        self.safety_net = safety_net or PCPrivacySafetyNet()
        self.esc_listener = esc_listener or PhysicalEscAbortListener()

        self.process_controller = process_controller
        self._get_hwnd_pid_fn = get_hwnd_pid_fn or self._default_get_hwnd_pid
        self._process_snapshot_fn = process_snapshot_fn or Win32SystemProbes.snapshot_process_map
        self._close_sunshine_app_fn = close_sunshine_app_fn or SunshineConfigManager.close_active_app
        self._disconnect_grace_sec = disconnect_grace_sec
        self._steam_cold_retry_delay_sec = max(0.0, float(steam_cold_retry_delay_sec))
        self._is_steam_ready_fn = is_steam_ready_fn or (
            lambda: Win32SystemProbes.is_process_running("steamwebhelper.exe")
        )
        self._steam_retry_launch_fn = steam_retry_launch_fn or self._fallback_launch_fn
        self._epic_cold_retry_delay_sec = max(0.0, float(epic_cold_retry_delay_sec))
        self._is_epic_ready_fn = is_epic_ready_fn or EpicPreparationManager.is_epic_running
        self._epic_retry_launch_fn = epic_retry_launch_fn or self._fallback_launch_fn
        self._on_session_end_fn = on_session_end_fn
        self._primary_monitor_enforcer = PrimaryMonitorEnforcer()
        self._ensure_on_primary_monitor_fn = (
            ensure_on_primary_monitor_fn or self._primary_monitor_enforcer.ensure_on_primary_monitor
        )
        self._shield_personal_browsers_fn = (
            shield_personal_browsers_fn or PCPrivacySafetyNet.reminimize_unminimized_windows
        )

        self._lock = threading.Lock()
        self._lifecycle_lock = threading.RLock()
        self._session_token: int = 0
        self._active: bool = False
        self._active_game_id: str = ""
        self._game_window_ready: bool = False
        self._started_at_monotonic: float = 0.0
        self._target_pid: int = 0
        self._target_hwnd: int = 0
        self._stop_event: threading.Event = threading.Event()
        self._desired_z_state: Tuple[str, int] = ("topmost", 0)

    @staticmethod
    def _default_get_hwnd_pid(hwnd: int) -> int:
        if sys.platform != "win32" or hwnd <= 0:
            return 0
        try:
            pid = wintypes.DWORD(0)
            ctypes.windll.user32.GetWindowThreadProcessId(wintypes.HWND(hwnd), ctypes.byref(pid))
            return int(pid.value)
        except Exception:
            return 0

    def get_target_pid(self) -> int:
        with self._lock:
            return self._target_pid

    def get_target_hwnd(self) -> int:
        with self._lock:
            return self._target_hwnd

    @classmethod
    def _terminate_game_process(
        cls,
        hwnd: int = 0,
        pid: int = 0,
        wait_timeout_sec: float = GAME_TERMINATE_TIMEOUT_SEC,
        send_msg_fn: Optional[Callable[[int, int, int, int], None]] = None,
        kill_proc_fn: Optional[Callable[[int], None]] = None,
        is_pid_alive_fn: Optional[Callable[[int], bool]] = None,
    ) -> bool:
        """
        Terminates a game cleanly: posts WM_CLOSE to hwnd; if alive after wait_timeout_sec, terminates pid.
        Fully testable via injectable callables.
        """
        if send_msg_fn is None:
            def default_send_msg(h: int, msg: int, w: int, l: int) -> None:
                if sys.platform == "win32" and h > 0 and not _is_automated_test_env():
                    ctypes.windll.user32.PostMessageW(wintypes.HWND(h), msg, w, l)
            send_msg_fn = default_send_msg

        if is_pid_alive_fn is None:
            def default_is_pid_alive(p: int) -> bool:
                return p > 0 and p in Win32SystemProbes.snapshot_process_map()
            is_pid_alive_fn = default_is_pid_alive

        if kill_proc_fn is None:
            def default_kill_pid(p: int) -> None:
                if p <= 0 or _is_automated_test_env():
                    return
                if sys.platform == "win32":
                    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
                    h_proc = k32.OpenProcess(PROCESS_TERMINATE, False, wintypes.DWORD(p))
                    if h_proc:
                        try:
                            k32.TerminateProcess(h_proc, 1)
                        finally:
                            k32.CloseHandle(h_proc)
                else:
                    try:
                        os.kill(p, signal.SIGTERM)
                    except OSError:
                        pass
            kill_proc_fn = default_kill_pid

        if hwnd > 0:
            send_msg_fn(hwnd, WM_CLOSE, 0, 0)

        deadline = time.monotonic() + wait_timeout_sec
        while time.monotonic() < deadline:
            if pid <= 0 or not is_pid_alive_fn(pid):
                return True
            time.sleep(0.02)

        if pid > 0 and is_pid_alive_fn(pid):
            AgentLogger.warning("Game PID %d still active after WM_CLOSE timeout; terminating.", pid)
            kill_proc_fn(pid)
        return True

    def terminate_session(self, reason: str = "remote_exit") -> Dict[str, Any]:
        """Tears down active streaming session, terminates running game, and restores PC."""
        target_hwnd = self.get_target_hwnd()
        target_pid = self.get_target_pid()
        if self.process_controller is not None and hasattr(self.process_controller, "terminate_game"):
            return self.process_controller.terminate_game(
                explicit_pid=target_pid, explicit_hwnd=target_hwnd, reason=reason
            )
        if target_hwnd > 0 or target_pid > 0:
            self._terminate_game_process(target_hwnd, target_pid)
        try:
            self._close_sunshine_app_fn()
        except Exception:
            pass
        self.stop()
        return {
            "ok": True,
            "status": "ok",
            "terminated": True,
            "pcRestored": not self.safety_net.is_engaged(),
            "reason": reason,
        }

    @classmethod
    def resolve_expected_process_names(cls, target_game: Optional[GameEntry]) -> Set[str]:
        """
        Extracts lowercase process names that can legitimately represent target_game.

        Why: Prevents arbitrary open applications (browsers, chats, media players) from
        being falsely classified as the game window and exposing private windows.
        What: Resolves process names from launch_cmd, working_dir, overrides, and Steam directories.
        """
        if target_game is None:
            return set()

        gid = target_game.game_id.lower()
        if gid == "desktop":
            return set()
        if gid == "steam_bigpicture":
            return {"steam.exe", "steamwebhelper.exe"}

        names: Set[str] = set()

        # 0. Explicit primary executable metadata (e.g. from Epic .item LaunchExecutable)
        if target_game.exe_name:
            ename = Path(target_game.exe_name.strip()).name.lower()
            if ename.endswith(".exe") and ename not in cls.NON_GAME_PROCESS_NAMES:
                names.add(ename)

        # 1. From launch_cmd
        cmd = target_game.launch_cmd.strip()
        cmd_lower = cmd.lower()
        if (
            cmd
            and not cmd_lower.startswith("steam://")
            and not cmd_lower.startswith("com.epicgames.launcher://")
            and not cmd_lower.startswith("heroic://")
            and not cmd_lower.startswith("legendary://")
        ):
            try:
                exe_name = Path(
                    cmd.split('"')[1] if cmd.startswith('"') else cmd.split()[0]
                ).name.lower()
                if exe_name.endswith(".exe"):
                    names.add(exe_name)
            except Exception:
                pass
            try:
                pname = Path(cmd).name.lower()
                if pname.endswith(".exe"):
                    names.add(pname)
            except Exception:
                pass

        # 2. From working_dir (root + Unreal Engine Binaries/Win64 subtrees)
        wdir = target_game.working_dir.strip()
        if wdir and os.path.isdir(wdir):
            try:
                wpath = Path(wdir)
                for item in os.listdir(wdir):
                    ilow = item.lower()
                    if (
                        ilow.endswith(".exe")
                        and ilow not in cls.NON_GAME_PROCESS_NAMES
                        and not any(pat in ilow for pat in IGNORED_EXE_PATTERNS)
                    ):
                        names.add(ilow)
                # Unreal Engine titles (such as Hogwarts Legacy) spawn their main window from
                # <Project>/Binaries/Win64/<Game>-Win64-Shipping.exe or <Game>.exe.
                for bin_pattern in ("Binaries/Win64/*.exe", "*/Binaries/Win64/*.exe"):
                    for bin_exe in wpath.glob(bin_pattern):
                        blow = bin_exe.name.lower()
                        if (
                            blow not in cls.NON_GAME_PROCESS_NAMES
                            and not any(pat in blow for pat in IGNORED_EXE_PATTERNS)
                        ):
                            names.add(blow)
            except OSError:
                pass

        # 3. From known overrides
        for key, (_t_name, exe_sub) in KNOWN_STANDALONE_OVERRIDES.items():
            if key in gid or key in target_game.title.lower():
                names.add(Path(exe_sub).name.lower())

        # 4. From Steam apps directories
        if cmd.startswith("steam://rungameid/"):
            clean_title = re.sub(r"[^a-zA-Z0-9]", "", target_game.title).lower()
            if clean_title:
                names.add(f"{clean_title}.exe")

            steam_common_dirs: List[Path] = [
                resolve_default_steam_root() / "steamapps" / "common",
                Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Steam" / "steamapps" / "common",
            ]
            for gdir in resolve_default_games_dirs():
                steam_common_dirs.append(gdir / "steamapps" / "common")
                steam_common_dirs.append(gdir / "Steam" / "steamapps" / "common")
            for cdir in steam_common_dirs:
                if cdir.is_dir():
                    try:
                        for folder in cdir.iterdir():
                            if folder.is_dir() and (
                                folder.name.lower() in target_game.title.lower()
                                or target_game.title.lower() in folder.name.lower()
                            ):
                                for exe_file in folder.glob("*.exe"):
                                    ename = exe_file.name.lower()
                                    if ename not in cls.NON_GAME_PROCESS_NAMES:
                                        names.add(ename)
                    except OSError:
                        pass

        return names

    @classmethod
    def _ensure_game_focused(cls, game_hwnd: int) -> bool:
        """Verifies responsiveness and ensures foreground focus via AttachThreadInput / SetForegroundWindow."""
        if sys.platform != "win32" or game_hwnd <= 0 or _is_automated_test_env():
            return True
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            if u32.IsHungAppWindow(wintypes.HWND(game_hwnd)):
                return False

            fg = u32.GetForegroundWindow()
            if fg == game_hwnd:
                return True

            fg_pid = wintypes.DWORD(0)
            fg_tid = u32.GetWindowThreadProcessId(wintypes.HWND(fg), ctypes.byref(fg_pid))
            cur_tid = k32.GetCurrentThreadId()
            if fg_tid and cur_tid != fg_tid:
                u32.AttachThreadInput(cur_tid, fg_tid, True)
                u32.SetForegroundWindow(wintypes.HWND(game_hwnd))
                u32.AttachThreadInput(cur_tid, fg_tid, False)
            else:
                u32.SetForegroundWindow(wintypes.HWND(game_hwnd))
            return not bool(u32.IsHungAppWindow(wintypes.HWND(game_hwnd)))
        except Exception as exc:
            AgentLogger.debug("Focus check exception: %s", exc)
            return True

    @classmethod
    def is_game_foreground_window(
        cls,
        process_name: str,
        class_name: str,
        title: str,
        width: int,
        height: int,
        target_game: Optional[GameEntry] = None,
    ) -> bool:
        """
        Pure classifier returning True iff a visible Win32 top-level window belongs to the target game.
        """
        proc_lower = process_name.strip().lower()
        if not proc_lower or proc_lower in cls.NON_GAME_PROCESS_NAMES:
            return False
        clean_cls = class_name.strip()
        if clean_cls in cls.NON_GAME_WINDOW_CLASSES:
            return False

        is_bp_target = target_game is not None and target_game.game_id == "steam_bigpicture"
        if proc_lower in ("steam.exe", "steamwebhelper.exe"):
            if not is_bp_target:
                return False
            return (
                width >= MIN_BIGPICTURE_WINDOW_WIDTH
                and height >= MIN_BIGPICTURE_WINDOW_HEIGHT
                and "big picture" in title.strip().lower()
            )

        if target_game is not None:
            expected = cls.resolve_expected_process_names(target_game)
            if expected:
                if proc_lower not in expected:
                    return False
            else:
                clean_title = re.sub(r"[^a-zA-Z0-9]", "", target_game.title).lower()
                clean_proc = re.sub(r"[^a-zA-Z0-9]", "", proc_lower.replace(".exe", ""))
                clean_wtitle = re.sub(r"[^a-zA-Z0-9]", "", title).lower()
                if clean_title and (clean_title in clean_proc or clean_title in clean_wtitle):
                    pass
                else:
                    return False

        return width >= MIN_GAME_WINDOW_WIDTH and height >= MIN_GAME_WINDOW_HEIGHT

    @classmethod
    def find_visible_game_hwnd(cls, target_game: GameEntry) -> int:
        """Finds the frontmost visible, responsive, unminimized, uncloaked game HWND in Win32 Z-order, or 0."""
        if sys.platform != "win32":
            return 0
        proc_map = Win32SystemProbes.snapshot_process_map()
        if not proc_map:
            return 0

        u32 = ctypes.WinDLL("user32", use_last_error=True)
        try:
            dwmapi = ctypes.WinDLL("dwmapi", use_last_error=True)
        except OSError:
            dwmapi = None

        enum_proc_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        found: List[int] = [0]

        def enum_cb(hwnd: int, _lparam: int) -> bool:
            if not hwnd or not u32.IsWindowVisible(hwnd) or u32.IsIconic(hwnd):
                return True
            if u32.IsHungAppWindow(wintypes.HWND(hwnd)):
                return True
            if dwmapi is not None:
                cloaked = wintypes.DWORD(0)
                if (
                    dwmapi.DwmGetWindowAttribute(
                        wintypes.HWND(hwnd),
                        DWMWA_CLOAKED,
                        ctypes.byref(cloaked),
                        ctypes.sizeof(cloaked),
                    )
                    == 0
                    and cloaked.value != 0
                ):
                    return True

            pid = wintypes.DWORD(0)
            u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            proc_name = proc_map.get(int(pid.value), "")
            if not proc_name:
                return True

            cls_buf = ctypes.create_unicode_buffer(128)
            u32.GetClassNameW(hwnd, cls_buf, 128)
            title_len = int(u32.GetWindowTextLengthW(hwnd))
            title_buf = ctypes.create_unicode_buffer(max(1, title_len + 1))
            if title_len > 0:
                u32.GetWindowTextW(hwnd, title_buf, title_len + 1)

            rect = wintypes.RECT()
            if not u32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            width = int(rect.right - rect.left)
            height = int(rect.bottom - rect.top)

            if cls.is_game_foreground_window(
                proc_name, cls_buf.value, title_buf.value, width, height, target_game
            ):
                found[0] = int(hwnd)
                return False
            return True

        try:
            u32.EnumWindows(enum_proc_type(enum_cb), 0)
        except Exception as exc:
            AgentLogger.debug("Game HWND scan skipped: %s", type(exc).__name__)
        return found[0]

    def _resolve_sunshine_log(self) -> Optional[Path]:
        if self._explicit_log_path is not None:
            return self._explicit_log_path
        for candidate in self.DEFAULT_SUNSHINE_LOGS:
            if candidate.is_file():
                return candidate
        return self.DEFAULT_SUNSHINE_LOGS[0]

    def is_active(self) -> bool:
        """Returns True while the privacy curtain is masking the desktop for an active game session."""
        with self._lock:
            return self._active

    def stop(self) -> None:
        """Tears down any active privacy curtain immediately and restores the saved cursor position."""
        with self._lifecycle_lock:
            with self._lock:
                self._session_token += 1
                self._stop_event.set()
                was_active = self._active
                self._active = False
                self._active_game_id = ""
                self._game_window_ready = False
                self._started_at_monotonic = 0.0
                self._target_pid = 0
                self._target_hwnd = 0
            self.esc_listener.stop()
            self.safety_net.restore()
            if was_active:
                self.cursor_controller.restore_cursor()

    def _set_z_order(self, mode: str, game_hwnd: int) -> None:
        with self._lock:
            self._desired_z_state = (mode, int(game_hwnd))
        if self._custom_z_order_fn is not None:
            self._custom_z_order_fn(mode, int(game_hwnd))

    @classmethod
    def _ensure_win32_curtain_class(cls, u32: Any, gdi32: Any, k32: Any) -> int:
        with cls._class_registered_lock:
            hinst = k32.GetModuleHandleW(None)
            if cls._class_registered:
                return int(cls._blank_cursor_handle)

            lresult_t = ctypes.c_ssize_t
            wparam_t = ctypes.c_size_t
            lparam_t = ctypes.c_ssize_t
            u32.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT, wparam_t, lparam_t]
            u32.DefWindowProcW.restype = lresult_t
            u32.CreateCursor.restype = ctypes.c_void_p
            u32.SetCursor.argtypes = [ctypes.c_void_p]
            u32.SetCursor.restype = ctypes.c_void_p
            gdi32.GetStockObject.argtypes = [ctypes.c_int]
            gdi32.GetStockObject.restype = ctypes.c_void_p

            and_plane = (ctypes.c_ubyte * 4)(0xFF, 0xFF, 0xFF, 0xFF)
            xor_plane = (ctypes.c_ubyte * 4)(0x00, 0x00, 0x00, 0x00)
            blank_cur = u32.CreateCursor(hinst, 0, 0, 1, 1, and_plane, xor_plane)
            cls._blank_cursor_handle = int(blank_cur or 0)

            wndproc_type = ctypes.WINFUNCTYPE(lresult_t, wintypes.HWND, wintypes.UINT, wparam_t, lparam_t)

            def wnd_proc(hwnd: int, msg: int, wparam: int, lparam: int) -> int:
                if msg == WM_SETCURSOR and cls._blank_cursor_handle:
                    u32.SetCursor(ctypes.c_void_p(cls._blank_cursor_handle))
                    return 1
                return int(u32.DefWindowProcW(hwnd, msg, wparam, lparam))

            cls._wndproc_ref = wndproc_type(wnd_proc)

            class WNDCLASSW(ctypes.Structure):
                _fields_ = [
                    ("style", wintypes.UINT),
                    ("lpfnWndProc", wndproc_type),
                    ("cbClsExtra", ctypes.c_int),
                    ("cbWndExtra", ctypes.c_int),
                    ("hInstance", ctypes.c_void_p),
                    ("hIcon", ctypes.c_void_p),
                    ("hCursor", ctypes.c_void_p),
                    ("hbrBackground", ctypes.c_void_p),
                    ("lpszMenuName", wintypes.LPCWSTR),
                    ("lpszClassName", wintypes.LPCWSTR),
                ]

            wc = WNDCLASSW()
            wc.style = 0
            wc.lpfnWndProc = cls._wndproc_ref
            wc.cbClsExtra = 0
            wc.cbWndExtra = 0
            wc.hInstance = hinst
            wc.hIcon = None
            wc.hCursor = ctypes.c_void_p(cls._blank_cursor_handle) if cls._blank_cursor_handle else None
            wc.hbrBackground = gdi32.GetStockObject(BLACK_BRUSH)
            wc.lpszMenuName = None
            wc.lpszClassName = PRIVACY_CURTAIN_CLASS_NAME

            u32.RegisterClassW(ctypes.byref(wc))
            cls._class_registered = True
            return cls._blank_cursor_handle

    def _run_native_curtain_ui_loop(
        self, stop_event: threading.Event, ready_event: threading.Event
    ) -> None:
        if sys.platform != "win32" or _is_automated_test_env():
            ready_event.set()
            return
        try:
            u32 = ctypes.WinDLL("user32", use_last_error=True)
            gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            k32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
            k32.GetModuleHandleW.restype = ctypes.c_void_p

            self._ensure_win32_curtain_class(u32, gdi32, k32)
            hinst = k32.GetModuleHandleW(None)

            vx = int(u32.GetSystemMetrics(SM_XVIRTUALSCREEN))
            vy = int(u32.GetSystemMetrics(SM_YVIRTUALSCREEN))
            vw = max(1920, int(u32.GetSystemMetrics(SM_CXVIRTUALSCREEN)))
            vh = max(1080, int(u32.GetSystemMetrics(SM_CYVIRTUALSCREEN)))

            u32.CreateWindowExW.argtypes = [
                wintypes.DWORD,
                wintypes.LPCWSTR,
                wintypes.LPCWSTR,
                wintypes.DWORD,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.c_int,
                wintypes.HWND,
                wintypes.HMENU,
                ctypes.c_void_p,
                wintypes.LPVOID,
            ]
            u32.CreateWindowExW.restype = wintypes.HWND
            u32.SetWindowPos.argtypes = [
                wintypes.HWND,
                ctypes.c_void_p,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.c_int,
                ctypes.c_int,
                wintypes.UINT,
            ]
            u32.SetWindowPos.restype = wintypes.BOOL

            hwnd = u32.CreateWindowExW(
                WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
                PRIVACY_CURTAIN_CLASS_NAME,
                "",
                WS_POPUP | WS_VISIBLE,
                vx,
                vy,
                vw,
                vh,
                None,
                None,
                hinst,
                None,
            )
            ready_event.set()
            if not hwnd:
                return

            ptr_mask = (1 << (ctypes.sizeof(ctypes.c_void_p) * 8)) - 1
            hwnd_topmost = ctypes.c_void_p((-1) & ptr_mask)
            hwnd_notopmost = ctypes.c_void_p((-2) & ptr_mask)

            msg = wintypes.MSG()
            applied: Tuple[str, int] = ("", -1)
            last_topmost_assert: float = 0.0
            try:
                while not stop_event.is_set():
                    while u32.PeekMessageW(ctypes.byref(msg), None, 0, 0, PM_REMOVE):
                        u32.TranslateMessage(ctypes.byref(msg))
                        u32.DispatchMessageW(ctypes.byref(msg))

                    cur_vx = int(u32.GetSystemMetrics(SM_XVIRTUALSCREEN))
                    cur_vy = int(u32.GetSystemMetrics(SM_YVIRTUALSCREEN))
                    cur_vw = max(1920, int(u32.GetSystemMetrics(SM_CXVIRTUALSCREEN)))
                    cur_vh = max(1080, int(u32.GetSystemMetrics(SM_CYVIRTUALSCREEN)))
                    geom_changed = (cur_vx, cur_vy, cur_vw, cur_vh) != (vx, vy, vw, vh)
                    if geom_changed:
                        vx, vy, vw, vh = cur_vx, cur_vy, cur_vw, cur_vh
                    with self._lock:
                        desired = self._desired_z_state
                    now_ui = time.monotonic()
                    need_topmost_reassert = (
                        desired[0] != "behind_game"
                        and (now_ui - last_topmost_assert) >= PRIVACY_CURTAIN_TOPMOST_REASSERT_SEC
                    )
                    if desired != applied or geom_changed or need_topmost_reassert:
                        z_mode, g_hwnd = desired
                        flags = SWP_NOACTIVATE | SWP_SHOWWINDOW
                        if z_mode == "behind_game" and g_hwnd > 0:
                            u32.SetWindowPos(hwnd, hwnd_notopmost, vx, vy, vw, vh, flags)
                            u32.SetWindowPos(hwnd, ctypes.c_void_p(g_hwnd), vx, vy, vw, vh, flags)
                        else:
                            u32.SetWindowPos(hwnd, hwnd_topmost, vx, vy, vw, vh, flags)
                            last_topmost_assert = now_ui
                        applied = desired
                    stop_event.wait(0.03)
            finally:
                u32.DestroyWindow(hwnd)
        except Exception as exc:
            ready_event.set()
            AgentLogger.warning("Privacy curtain Win32 UI loop warning: %s", type(exc).__name__)

    def _handle_esc_abort(self) -> None:
        """Invoked when physical ESC is detected during an active curtain session."""
        AgentLogger.info(
            "Physical ESC abort triggered: dismissing curtain, restoring PC, closing Sunshine app."
        )
        self.stop()
        try:
            SunshineConfigManager.close_active_app()
        except Exception as exc:
            AgentLogger.debug("Close Sunshine app on abort: %s", exc)

    def _handle_esc_abort_for_session(
        self, expected_token: int, session_stop_event: threading.Event
    ) -> None:
        """
        Dispatches physical ESC teardown off the listener thread so esc_listener.stop()
        inside _lifecycle_lock can never deadlock on a concurrent ESC callback.
        """
        if session_stop_event.is_set():
            return

        def _run_abort() -> None:
            with self._lifecycle_lock:
                if session_stop_event.is_set():
                    return
                with self._lock:
                    if self._session_token != expected_token or not self._active:
                        return
                self._handle_esc_abort()

        threading.Thread(
            target=_run_abort,
            daemon=True,
            name="soartv-esc-abort-worker",
        ).start()

    def get_launch_readiness(self) -> Dict[str, Any]:
        """Returns thread-safe readiness telemetry for GET /api/gaming/launch-status."""
        with self._lock:
            active = self._active
            gid = self._active_game_id
            ready = self._game_window_ready
            hwnd = self._target_hwnd
            pid = self._target_pid
            started = self._started_at_monotonic
        elapsed_ms = int(max(0.0, (time.monotonic() - started) * 1000.0)) if active and started > 0.0 else 0
        return {
            "ok": True,
            "active": active,
            "gameId": gid,
            "gameWindowReady": ready,
            "hwnd": hwnd,
            "pid": pid,
            "elapsedMs": elapsed_ms,
        }

    def start_for_game(
        self,
        target_game: GameEntry,
        use_hdmi: bool = False,
        prelaunched: bool = False,
    ) -> bool:
        """
        Starts the black privacy curtain, parks the cursor, and monitors Sunshine & game windows.

        Returns False without masking when target_game is 'desktop' or use_hdmi is True.
        """
        with self._lifecycle_lock:
            if use_hdmi or target_game.game_id == "desktop":
                self.stop()
                return False

            self.stop()
            stop_event = threading.Event()
            ui_ready = threading.Event()

            # Engage privacy safety net before curtain presentation and start physical ESC listener
            self.safety_net.engage()

            log_path = self._resolve_sunshine_log()
            initial_offset = 0
            if log_path is not None and log_path.is_file():
                try:
                    initial_offset = log_path.stat().st_size
                except OSError:
                    initial_offset = 0

            with self._lock:
                self._session_token += 1
                my_token = self._session_token
                self._stop_event = stop_event
                self._desired_z_state = ("topmost", 0)
                self._active = True
                self._active_game_id = target_game.game_id
                self._game_window_ready = False
                self._started_at_monotonic = time.monotonic()

            self.esc_listener.start(
                lambda tok=my_token, ev=stop_event: self._handle_esc_abort_for_session(tok, ev)
            )
            self.cursor_controller.park_cursor()
            self._set_z_order("topmost", 0)

            ui_thread: Optional[threading.Thread] = None
            if self._create_native_window:
                ui_thread = threading.Thread(
                    target=self._run_native_curtain_ui_loop,
                    args=(stop_event, ui_ready),
                    daemon=True,
                    name="soartv-privacy-curtain-ui",
                )
                ui_thread.start()
                ui_ready.wait(timeout=0.50)

            def monitor_loop() -> None:
                started_at = time.monotonic()
                connected_at: Optional[float] = None
                disconnected_at: Optional[float] = None
                saw_spawn = False
                saw_desktop_exec = False
                fallback_fired = bool(prelaunched)
                steam_retry_fired = False
                steam_ready_since: Optional[float] = None
                epic_retry_fired = False
                epic_ready_since: Optional[float] = None
                first_game_seen = False
                target_pid: int = 0
                target_hwnd: int = 0
                last_z: Tuple[str, int] = ("topmost", 0)
                offset = initial_offset
                is_steam_app_target = target_game.launch_cmd.startswith("steam://rungameid/")
                is_epic_app_target = target_game.launch_cmd.lower().startswith(
                    "com.epicgames.launcher://"
                )

                try:
                    while not stop_event.is_set():
                        now = time.monotonic()
                        if log_path is not None and log_path.is_file():
                            try:
                                size = log_path.stat().st_size
                                if size < offset:
                                    offset = 0
                                if size > offset:
                                    with log_path.open("rb") as fh:
                                        fh.seek(offset)
                                        chunk = fh.read(size - offset).decode("utf-8", errors="ignore")
                                    offset = size
                                    for line in chunk.splitlines():
                                        if "Spawning [" in line or "Executing: [" in line:
                                            saw_spawn = True
                                        if "Executing [Desktop]" in line:
                                            saw_desktop_exec = True
                                        if "CLIENT CONNECTED" in line:
                                            connected_at = now
                                            disconnected_at = None
                                        if "CLIENT DISCONNECTED" in line and connected_at is not None:
                                            if disconnected_at is None:
                                                disconnected_at = now
                            except OSError:
                                pass

                        # Sunshine disconnect grace evaluation
                        if disconnected_at is not None:
                            if (now - disconnected_at) >= self._disconnect_grace_sec:
                                AgentLogger.info(
                                    "Sunshine client disconnected for %.1fs. Triggering clean teardown for %s.",
                                    now - disconnected_at,
                                    target_game.game_id,
                                )
                                if target_hwnd > 0 or target_pid > 0:
                                    self._terminate_game_process(target_hwnd, target_pid)
                                try:
                                    self._close_sunshine_app_fn()
                                except Exception as exc:
                                    AgentLogger.warning("Disconnect teardown error: %s", exc)
                                return

                        if connected_at is None and (now - started_at) >= self._connect_timeout_sec:
                            return
                        if (now - started_at) >= self._max_session_sec:
                            return

                        if connected_at is not None and not fallback_fired:
                            if saw_desktop_exec or (
                                not saw_spawn and (now - connected_at) >= self._fallback_spawn_grace_sec
                            ):
                                fallback_fired = True
                                try:
                                    self._fallback_launch_fn(target_game)
                                except Exception as exc:
                                    AgentLogger.warning(
                                        "Fallback game launch warning: %s", type(exc).__name__
                                    )

                        # Defense-in-depth: immediately re-minimize any pre-existing personal browser window
                        # that tried to restore during streaming (e.g. from a game link or shortcut).
                        get_browsers_fn = getattr(self.safety_net, "get_preexisting_browser_hwnds", None)
                        personal_browsers = get_browsers_fn() if callable(get_browsers_fn) else []
                        if personal_browsers:
                            try:
                                if self._shield_personal_browsers_fn(personal_browsers) > 0 and target_hwnd > 0:
                                    self._ensure_game_focused(target_hwnd)
                            except Exception as exc:
                                AgentLogger.debug("Personal browser shield warning: %s", type(exc).__name__)

                        game_hwnd = self._find_game_hwnd_fn(target_game)
                        if game_hwnd > 0:
                            try:
                                self._ensure_on_primary_monitor_fn(game_hwnd)
                            except Exception as exc:
                                AgentLogger.debug("Primary monitor check warning: %s", type(exc).__name__)
                        if game_hwnd > 0 and self._ensure_game_focused(game_hwnd):
                            target_hwnd = game_hwnd
                            with self._lock:
                                self._target_hwnd = game_hwnd
                                self._game_window_ready = True
                            cur_pid = self._get_hwnd_pid_fn(game_hwnd)
                            if cur_pid > 0:
                                target_pid = cur_pid
                                with self._lock:
                                    self._target_pid = cur_pid

                            desired = ("behind_game", game_hwnd)
                            if desired != last_z:
                                self._set_z_order("behind_game", game_hwnd)
                                last_z = desired
                            if not first_game_seen:
                                first_game_seen = True
                                self.cursor_controller.re_park_without_overwriting_saved()
                        else:
                            if (
                                prelaunched
                                and is_steam_app_target
                                and not first_game_seen
                                and not steam_retry_fired
                            ):
                                if self._is_steam_ready_fn():
                                    if steam_ready_since is None:
                                        steam_ready_since = now
                                    elif (now - steam_ready_since) >= self._steam_cold_retry_delay_sec:
                                        proc_map_retry = self._process_snapshot_fn()
                                        expected_retry = self.resolve_expected_process_names(target_game)
                                        already_spawning = bool(
                                            expected_retry and any(p in expected_retry for p in proc_map_retry.values())
                                        )
                                        if not already_spawning:
                                            steam_retry_fired = True
                                            AgentLogger.info(
                                                "Steam ready for %.1fs without game window; issuing single-shot relaunch for %s.",
                                                now - steam_ready_since,
                                                target_game.game_id,
                                            )
                                            try:
                                                self._steam_retry_launch_fn(target_game)
                                            except Exception as exc:
                                                AgentLogger.warning(
                                                    "Steam cold-start retry launch warning: %s", type(exc).__name__
                                                )
                                else:
                                    steam_ready_since = None

                            if (
                                prelaunched
                                and is_epic_app_target
                                and not first_game_seen
                                and not epic_retry_fired
                            ):
                                if self._is_epic_ready_fn():
                                    if epic_ready_since is None:
                                        epic_ready_since = now
                                    elif (now - epic_ready_since) >= self._epic_cold_retry_delay_sec:
                                        proc_map_epic = self._process_snapshot_fn()
                                        expected_epic = self.resolve_expected_process_names(target_game)
                                        already_spawning_epic = bool(
                                            expected_epic
                                            and any(p in expected_epic for p in proc_map_epic.values())
                                        )
                                        if not already_spawning_epic:
                                            epic_retry_fired = True
                                            AgentLogger.info(
                                                "Epic Games ready for %.1fs without game window; issuing single-shot relaunch for %s.",
                                                now - epic_ready_since,
                                                target_game.game_id,
                                            )
                                            try:
                                                self._epic_retry_launch_fn(target_game)
                                            except Exception as exc:
                                                AgentLogger.warning(
                                                    "Epic cold-start retry launch warning: %s",
                                                    type(exc).__name__,
                                                )
                                else:
                                    epic_ready_since = None

                            if last_z != ("topmost", 0):
                                self._set_z_order("topmost", 0)
                                last_z = ("topmost", 0)

                            # Autonomous exit detection
                            if first_game_seen:
                                proc_map = self._process_snapshot_fn()
                                is_pid_alive = (target_pid > 0 and target_pid in proc_map)
                                if not is_pid_alive:
                                    expected_names = self.resolve_expected_process_names(target_game)
                                    if expected_names and any(p in expected_names for p in proc_map.values()):
                                        is_pid_alive = True

                                if not is_pid_alive:
                                    AgentLogger.info(
                                        "Autonomous exit detected: game window closed and PID %d terminated for %s.",
                                        target_pid,
                                        target_game.game_id,
                                    )
                                    try:
                                        self._close_sunshine_app_fn()
                                    except Exception as exc:
                                        AgentLogger.warning("Autonomous exit close Sunshine error: %s", exc)
                                    return

                        stop_event.wait(self._poll_interval_sec)
                finally:
                    stop_event.set()
                    with self._lifecycle_lock:
                        should_restore = False
                        with self._lock:
                            if self._session_token == my_token and self._active:
                                should_restore = True
                        if should_restore:
                            self.esc_listener.stop()
                            self.safety_net.restore()
                        if ui_thread is not None:
                            ui_thread.join(timeout=0.50)
                        should_restore_cursor = False
                        with self._lock:
                            if self._session_token == my_token and self._active:
                                self._session_token += 1
                                self._active = False
                                self._active_game_id = ""
                                self._game_window_ready = False
                                self._started_at_monotonic = 0.0
                                self._target_pid = 0
                                self._target_hwnd = 0
                                should_restore_cursor = True
                        if should_restore_cursor:
                            self.cursor_controller.restore_cursor()
                            if self._on_session_end_fn is not None:
                                try:
                                    self._on_session_end_fn("session_end")
                                except Exception as exc:
                                    AgentLogger.warning("Session end callback warning: %s", type(exc).__name__)

            mon_thread = threading.Thread(
                target=monitor_loop, daemon=True, name="soartv-privacy-curtain-monitor"
            )
            mon_thread.start()
            return True


class GameProcessController:
    """
    Manages process tree discovery, graceful WM_CLOSE, forced TerminateProcess, and session cleanup.

    Why: Encapsulates game lifecycle termination logic with full dependency injection for isolated unit testing.
    What: Traverses Toolhelp32 process trees, posts WM_CLOSE, waits grace period, falls back to
    kernel32.TerminateProcess, signals Sunshine /api/apps/close, and restores PC privacy state.
    """

    def __init__(
        self,
        safety_net: Optional[PCPrivacySafetyNet] = None,
        privacy_curtain: Optional[Any] = None,
        post_message_fn: Optional[Callable[[int, int, int, int], bool]] = None,
        terminate_process_fn: Optional[Callable[[int], bool]] = None,
        is_pid_alive_fn: Optional[Callable[[int], bool]] = None,
        snapshot_procs_fn: Optional[Callable[[], List[Tuple[int, int, str]]]] = None,
        find_hwnds_fn: Optional[Callable[[Set[int]], List[int]]] = None,
        close_sunshine_fn: Optional[Callable[[], bool]] = None,
        poll_interval_sec: float = 0.05,
    ) -> None:
        self.safety_net = safety_net
        self.privacy_curtain = privacy_curtain
        self._post_message_fn = post_message_fn or self._default_post_message
        self._terminate_process_fn = terminate_process_fn or self._default_terminate_process
        self._is_pid_alive_fn = is_pid_alive_fn or self._default_is_pid_alive
        self._snapshot_procs_fn = snapshot_procs_fn or Win32SystemProbes.snapshot_process_entries
        self._find_hwnds_fn = find_hwnds_fn or self._default_find_hwnds_for_pids
        self._close_sunshine_fn = close_sunshine_fn or SunshineConfigManager.close_active_app
        self._poll_interval_sec = poll_interval_sec
        self._late_watch_lock = threading.Lock()
        self._late_watch_epoch: int = 0

    def cancel_late_spawn_watch(self) -> None:
        """Disarms any in-flight post-exit late-spawn reaper when a new game launch begins."""
        with self._late_watch_lock:
            self._late_watch_epoch += 1

    @staticmethod
    def _default_post_message(hwnd: int, msg: int, wparam: int, lparam: int) -> bool:
        if sys.platform != "win32" or hwnd <= 0 or _is_automated_test_env():
            return False
        try:
            return bool(ctypes.windll.user32.PostMessageW(wintypes.HWND(hwnd), msg, wparam, lparam))
        except Exception as exc:
            AgentLogger.debug("PostMessageW failed: %s", exc)
            return False

    @staticmethod
    def _default_terminate_process(pid: int) -> bool:
        if pid <= 0 or _is_automated_test_env():
            return False
        if sys.platform == "win32":
            try:
                k32 = ctypes.WinDLL("kernel32", use_last_error=True)
                h_proc = k32.OpenProcess(PROCESS_TERMINATE, False, wintypes.DWORD(pid))
                if h_proc:
                    try:
                        return bool(k32.TerminateProcess(h_proc, 1))
                    finally:
                        k32.CloseHandle(h_proc)
            except Exception as exc:
                AgentLogger.debug("TerminateProcess failed on PID %d: %s", pid, exc)
                return False
            return False
        else:
            try:
                os.kill(pid, signal.SIGTERM)
                return True
            except OSError:
                return False

    @staticmethod
    def _default_is_pid_alive(pid: int) -> bool:
        if pid <= 0:
            return False
        if sys.platform == "win32":
            return pid in Win32SystemProbes.snapshot_process_map()
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    @staticmethod
    def _default_find_hwnds_for_pids(pids: Set[int]) -> List[int]:
        if sys.platform != "win32" or not pids:
            return []
        u32 = ctypes.WinDLL("user32", use_last_error=True)
        enum_proc_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        matching: List[int] = []

        def enum_cb(hwnd: int, _lparam: int) -> bool:
            if not hwnd or not u32.IsWindow(hwnd):
                return True
            pid = wintypes.DWORD(0)
            u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            if int(pid.value) in pids:
                matching.append(int(hwnd))
            return True

        try:
            u32.EnumWindows(enum_proc_type(enum_cb), 0)
        except Exception as exc:
            AgentLogger.debug("find_hwnds EnumWindows error: %s", exc)
        return matching

    def snapshot_process_entries(self) -> List[Tuple[int, int, str]]:
        return self._snapshot_procs_fn()

    @staticmethod
    def get_process_tree(
        root_pid: int,
        entries: Optional[List[Tuple[int, int, str]]] = None,
    ) -> Set[int]:
        """
        Given a root PID, performs BFS using parent PID relationships to return
        all descendant PIDs (including root_pid), while strictly excluding ancestors.
        """
        if root_pid <= 0:
            return set()
        if entries is None:
            entries = Win32SystemProbes.snapshot_process_entries()

        children_map: Dict[int, List[int]] = {}
        for pid, ppid, _exe in entries:
            children_map.setdefault(ppid, []).append(pid)

        tree: Set[int] = {root_pid}
        queue: Deque[int] = deque([root_pid])
        while queue:
            curr = queue.popleft()
            for child in children_map.get(curr, []):
                if child not in tree:
                    tree.add(child)
                    queue.append(child)
        return tree

    def find_game_pids(
        self,
        target_game: Optional[GameEntry] = None,
        explicit_pid: int = 0,
    ) -> Set[int]:
        all_pids: Set[int] = set()
        entries = self.snapshot_process_entries()

        if explicit_pid > 0:
            all_pids.add(explicit_pid)
            all_pids.update(self.get_process_tree(explicit_pid, entries))

        if self.privacy_curtain is not None and hasattr(self.privacy_curtain, "get_target_pid"):
            curtain_pid = self.privacy_curtain.get_target_pid()
            if curtain_pid > 0:
                all_pids.add(curtain_pid)
                all_pids.update(self.get_process_tree(curtain_pid, entries))

        if target_game is not None:
            expected = PrivacyCurtainManager.resolve_expected_process_names(target_game)
            if expected:
                for pid, _ppid, exe_name in entries:
                    if exe_name in expected:
                        all_pids.add(pid)
                        all_pids.update(self.get_process_tree(pid, entries))

        return all_pids

    def find_hwnds_for_pids(self, pids: Set[int]) -> List[int]:
        return self._find_hwnds_fn(pids)

    def terminate_game(
        self,
        target_game: Optional[GameEntry] = None,
        explicit_pid: int = 0,
        explicit_hwnd: int = 0,
        grace_period_sec: float = GAME_TERMINATE_TIMEOUT_SEC,
        reason: str = "remote_exit",
        late_spawn_watch_sec: Optional[float] = None,
    ) -> Dict[str, Any]:
        """
        Terminates the game cleanly: posts WM_CLOSE to window handles; waits up to grace_period_sec;
        falls back to TerminateProcess on hung processes; closes Sunshine active app; restores PC state.
        """
        pids = self.find_game_pids(target_game=target_game, explicit_pid=explicit_pid)
        hwnds = {h for h in self.find_hwnds_for_pids(pids) if h > 0 and h != 0xFFFF}
        if explicit_hwnd > 0 and explicit_hwnd != 0xFFFF:
            hwnds.add(explicit_hwnd)
        if self.privacy_curtain is not None and hasattr(self.privacy_curtain, "get_target_hwnd"):
            curtain_hwnd = self.privacy_curtain.get_target_hwnd()
            if curtain_hwnd > 0 and curtain_hwnd != 0xFFFF:
                hwnds.add(curtain_hwnd)

        # 1. Post WM_CLOSE to all game HWNDs
        for h in hwnds:
            try:
                self._post_message_fn(h, WM_CLOSE, 0, 0)
            except Exception as exc:
                AgentLogger.warning("PostMessage WM_CLOSE error for HWND %d: %s", h, exc)

        # 2. Wait up to grace_period_sec for PIDs to terminate cleanly
        forced = False
        terminated_pids = sorted(pids)
        if pids:
            deadline = time.monotonic() + grace_period_sec
            while time.monotonic() < deadline:
                alive = {p for p in pids if self._is_pid_alive_fn(p)}
                if not alive:
                    break
                time.sleep(self._poll_interval_sec)

            # 3. Fallback kill for any PID that survived grace period
            alive_after_wait = {p for p in pids if self._is_pid_alive_fn(p)}
            if alive_after_wait:
                forced = True
                for p in alive_after_wait:
                    try:
                        self._terminate_process_fn(p)
                    except Exception as exc:
                        AgentLogger.warning("TerminateProcess error for PID %d: %s", p, exc)

        # 4. Sunshine close active app
        try:
            self._close_sunshine_fn()
        except Exception as exc:
            AgentLogger.warning("Sunshine close on termination warning: %s", exc)

        # 5. Restore PC safety net
        if self.safety_net is not None:
            try:
                self.safety_net.restore()
            except Exception as exc:
                AgentLogger.warning("Safety net restore warning: %s", exc)

        # 6. Stop privacy curtain & restore cursor
        if self.privacy_curtain is not None:
            try:
                self.privacy_curtain.stop()
            except Exception as exc:
                AgentLogger.warning("Curtain stop warning: %s", exc)

        if not terminated_pids and target_game is not None and target_game.game_id != "desktop":
            expected_late = PrivacyCurtainManager.resolve_expected_process_names(target_game)
            if expected_late:
                in_test = (
                    "unittest" in sys.modules
                    or "pytest" in sys.modules
                    or os.environ.get("SOARTV_TEST_MODE") == "1"
                )
                watch_sec = (0.0 if in_test else 20.0) if late_spawn_watch_sec is None else float(late_spawn_watch_sec)
                if watch_sec > 0.0:
                    with self._late_watch_lock:
                        self._late_watch_epoch += 1
                        my_epoch = self._late_watch_epoch

                    def _reap_late_spawn() -> None:
                        end_t = time.monotonic() + watch_sec
                        step = max(0.02, min(0.4, self._poll_interval_sec))
                        while time.monotonic() < end_t:
                            with self._late_watch_lock:
                                if self._late_watch_epoch != my_epoch:
                                    return
                            late_pids = self.find_game_pids(target_game=target_game)
                            if late_pids:
                                for lh in {h for h in self.find_hwnds_for_pids(late_pids) if h > 0 and h != 0xFFFF}:
                                    try:
                                        self._post_message_fn(lh, WM_CLOSE, 0, 0)
                                    except Exception:
                                        pass
                                wait_end = time.monotonic() + min(1.0, grace_period_sec)
                                while time.monotonic() < wait_end:
                                    if not any(self._is_pid_alive_fn(lp) for lp in late_pids):
                                        return
                                    time.sleep(step)
                                for lp in late_pids:
                                    if self._is_pid_alive_fn(lp):
                                        try:
                                            self._terminate_process_fn(lp)
                                        except Exception:
                                            pass
                                return
                            time.sleep(step)

                    threading.Thread(target=_reap_late_spawn, daemon=True, name="late-spawn-reaper").start()

        pc_restored = not self.safety_net.is_engaged() if self.safety_net else True

        return {
            "ok": True,
            "status": "ok",
            "terminated": len(terminated_pids) > 0,
            "pidsTerminated": terminated_pids,
            "forced": forced,
            "pcRestored": pc_restored,
            "reason": reason,
        }


class HiddenGamesManager:
    """
    Persists user-hidden game IDs on disk and filters or annotates game lists.

    Why: Includes all discovered standalone games while allowing manual hiding and re-showing.
    What: Reads/writes a JSON set of hidden game IDs and applies visibility rules.
    """

    def __init__(self, storage_path: Path) -> None:
        self.storage_path = storage_path
        self._hidden_ids: Set[str] = self._load()

    def _load(self) -> Set[str]:
        if not self.storage_path.exists():
            return set()
        try:
            data = json.loads(self.storage_path.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return {str(x) for x in data}
        except (OSError, json.JSONDecodeError) as exc:
            AgentLogger.warning("Failed reading hidden games %s: %s", self.storage_path, type(exc).__name__)
        return set()

    def _save(self) -> None:
        try:
            self.storage_path.parent.mkdir(parents=True, exist_ok=True)
            self.storage_path.write_text(
                json.dumps(sorted(self._hidden_ids), indent=2), encoding="utf-8"
            )
        except OSError as exc:
            AgentLogger.warning("Failed saving hidden games %s: %s", self.storage_path, type(exc).__name__)

    def set_hidden(self, game_id: str, hidden: bool) -> None:
        """Marks a game_id as hidden or visible (re-shown) and persists to disk."""
        clean_id = game_id.strip()
        if not clean_id:
            return
        if hidden:
            self._hidden_ids.add(clean_id)
        else:
            self._hidden_ids.discard(clean_id)
        self._save()

    def is_hidden(self, game_id: str) -> bool:
        """Returns True if game_id is currently hidden."""
        return game_id in self._hidden_ids

    def apply_visibility(
        self, games: List[GameEntry], include_hidden: bool = False
    ) -> List[GameEntry]:
        """
        Annotates each GameEntry with its hidden flag and optionally filters out hidden entries.

        Why: Lets the UI switch between normal view (visible games only) and 'Show Hidden Games' mode.
        What: Returns a list of GameEntry objects with updated .hidden attributes.
        """
        annotated = [replace(g, hidden=(g.game_id in self._hidden_ids)) for g in games]
        if include_hidden:
            return annotated
        return [g for g in annotated if not g.hidden]


class DisplayModeController:
    """
    Controls Windows multi-monitor projection mode (TV Screen Only on HDMI vs. PC Screen on disconnect).

    Why: When connected via HDMI cable, 'TV Screen Only' (/external) turns off the PC panel so 100% of GPU
    power drives the TV. When HDMI is unplugged or wireless streaming is used, it restores '/internal' while
    ignoring internal laptop screens and any user-configured desk monitors (SOARTV_NON_TV_MONITORS).
    What: Filters active Win32 CCD/GDI displays against internal/embedded output technologies and optional
    SOARTV_NON_TV_MONITORS tokens, exposes list_connected_displays() for AI-agent onboarding, and wraps DisplaySwitch.exe.
    """

    NON_TV_MONITOR_TOKENS: Tuple[str, ...] = ()
    INTERNAL_OUTPUT_TECHNOLOGIES: FrozenSet[int] = frozenset(
        {
            DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL,
            DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EMBEDDED,
            DISPLAYCONFIG_OUTPUT_TECHNOLOGY_LVDS,
            DISPLAYCONFIG_OUTPUT_TECHNOLOGY_UDI_EMBEDDED,
            DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INDIRECT_VIRTUAL,
        }
    )

    @classmethod
    def get_non_tv_monitor_tokens(cls) -> Tuple[str, ...]:
        """Returns configured non-TV desk monitor EDID/friendly tokens (lowercase)."""
        tokens: List[str] = [t.strip().lower() for t in cls.NON_TV_MONITOR_TOKENS if t and t.strip()]
        raw_env = _read_repo_dotenv_value("SOARTV_NON_TV_MONITORS")
        if raw_env:
            for item in re.split(r"[;,]", raw_env):
                clean = item.strip().lower()
                if clean and clean not in tokens:
                    tokens.append(clean)
        return tuple(tokens)

    @staticmethod
    def get_displayswitch_flag(hdmi_connected: bool) -> str:
        """Returns '/external' (Second screen only) when HDMI is connected, or '/internal' otherwise."""
        return "/external" if hdmi_connected else "/internal"

    @classmethod
    def is_external_tv_display(cls, target: DisplayTargetDescriptor) -> bool:
        """
        Pure hardware-ID filter determining whether a DisplayTargetDescriptor is an external TV display.

        Why: Preserves wireless streaming as default when a PC sits at a desk with its internal laptop
        panel (eDP / LVDS / Internal) and/or permanently attached desk monitors (SOARTV_NON_TV_MONITORS).
        What: Excludes unavailable targets, internal/embedded/virtual output technologies, and configured
        non-TV monitor tokens.
        """
        if not target.target_available:
            return False
        tech_unsigned = int(target.output_technology) & 0xFFFFFFFF
        if tech_unsigned in cls.INTERNAL_OUTPUT_TECHNOLOGIES:
            return False
        combined = f"{target.device_path} {target.friendly_name}".strip().lower()
        if not combined:
            return False
        if any(tok in combined for tok in cls.get_non_tv_monitor_tokens()):
            return False
        return True

    @classmethod
    def list_connected_displays(cls) -> List[Dict[str, Any]]:
        """Enumerates connected displays for --list-monitors onboarding discovery."""
        if sys.platform != "win32":
            return []
        out: List[Dict[str, Any]] = []
        for t in Win32SystemProbes.query_active_displays():
            tech_unsigned = int(t.output_technology) & 0xFFFFFFFF
            is_internal = tech_unsigned in cls.INTERNAL_OUTPUT_TECHNOLOGIES
            out.append(
                {
                    "friendlyName": t.friendly_name,
                    "devicePath": t.device_path,
                    "available": bool(t.target_available),
                    "isInternalPanel": is_internal,
                    "consideredTvDisplay": cls.is_external_tv_display(t),
                }
            )
        return out

    @classmethod
    def filter_external_tv_displays(
        cls, targets: Sequence[DisplayTargetDescriptor]
    ) -> List[DisplayTargetDescriptor]:
        """Returns all active external TV displays from targets, excluding internal and desk monitors."""
        return [t for t in targets if cls.is_external_tv_display(t)]

    @classmethod
    def is_external_display_connected(cls) -> bool:
        """
        Checks in-process via Win32 ctypes whether a TV HDMI display is attached to the laptop.
        """
        if sys.platform != "win32":
            return False
        active_targets = Win32SystemProbes.query_active_displays()
        return len(cls.filter_external_tv_displays(active_targets)) > 0

    @classmethod
    def apply_display_mode(cls, hdmi_connected: bool) -> bool:
        """Runs DisplaySwitch.exe with /external (HDMI TV Only) or /internal (Laptop Screen)."""
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        flag = cls.get_displayswitch_flag(hdmi_connected)
        try:
            subprocess.Popen(["DisplaySwitch.exe", flag])
            return True
        except OSError as exc:
            AgentLogger.warning("DisplaySwitch.exe invocation failed: %s", type(exc).__name__)
            return False


class WindowsSessionController:
    """
    Handles Modern Standby (S0) wake-up, in-process lock/Sunshine checks, native PIN unlock, and game launch.

    Why: Enables couch-to-desktop automation without spawning CLI processes or shell scripts.
    What: Uses Win32 ctypes APIs (SetThreadExecutionState, WTSQuerySessionInformationW, SendInput,
    QueryServiceStatusEx) to wake display, check session lock, enter PIN securely, and start games.
    """

    ES_CONTINUOUS: int = 0x80000000
    ES_SYSTEM_REQUIRED: int = 0x00000001
    ES_DISPLAY_REQUIRED: int = 0x00000002

    _default_injector: Win32InputInjector = Win32InputInjector()

    @classmethod
    def wake_from_standby(cls) -> bool:
        """
        Wakes the Windows system and display out of Modern Standby (S0 Low Power Idle).

        Why: Over Wi-Fi, S0 Modern Standby keeps the network alive; calling SetThreadExecutionState
        and injecting a 1px mouse jiggle wakes the GPU and screen immediately.
        What: Invokes kernel32.SetThreadExecutionState and user32.mouse_event.
        """
        if sys.platform != "win32" or _is_automated_test_env():
            return False
        try:
            ctypes.windll.kernel32.SetThreadExecutionState(
                cls.ES_CONTINUOUS | cls.ES_SYSTEM_REQUIRED | cls.ES_DISPLAY_REQUIRED
            )
            ctypes.windll.user32.mouse_event(MOUSEEVENTF_MOVE, 1, 1, 0, 0)
            ctypes.windll.user32.mouse_event(MOUSEEVENTF_MOVE, -1, -1, 0, 0)
            return True
        except Exception as exc:
            AgentLogger.warning("wake_from_standby failed: %s", type(exc).__name__)
            return False

    @staticmethod
    def is_session_locked() -> bool:
        """
        Checks in-process via Win32 WTS / Desktop / Toolhelp32 whether the Windows session is locked.

        Why: Tells the SoarTV Launcher whether it needs to prompt for the Windows Hello PIN with
        zero tasklist.exe subprocess spawns (PERF-10).
        What: Delegates to Win32SystemProbes.is_session_locked().
        """
        return Win32SystemProbes.is_session_locked()

    @classmethod
    def is_windows_locked(cls) -> bool:
        """Backwards-compatible alias for is_session_locked()."""
        return cls.is_session_locked()

    @staticmethod
    def is_sunshine_running() -> bool:
        """Checks in-process via Win32 SCM / local TCP socket whether Sunshine is running."""
        return Win32SystemProbes.is_sunshine_running()

    @classmethod
    def unlock_with_pin(cls, pin: str) -> bool:
        """
        Validates and injects the user's alphanumeric Windows Hello PIN via Win32 SendInput.

        Why: Eliminates PowerShell RCE (SEC-03) and blocking HTTP thread sleeps (BP-07).
        What: Strictly validates ^[A-Za-z0-9]{4,32}$ (returning False with zero shell execution on
        invalid input), wakes display, and delivers keystrokes via Win32InputInjector.
        """
        try:
            validated = Win32InputInjector.validate_pin(pin)
        except ValueError:
            AgentLogger.warning("Rejected unlock attempt due to invalid PIN format.")
            return False

        if sys.platform != "win32":
            return False
        cls.wake_from_standby()
        return cls._default_injector.inject_pin(validated, allow_helper_fallback=True)

    @staticmethod
    def classify_game_launcher(game: Optional[GameEntry]) -> str:
        """
        Determines which storefront launcher (if any) owns a GameEntry.

        Why: Enforces strict single-launcher isolation so launching a Heroic/Legendary/Epic game never
        opens Steam, launching a Steam game never opens Heroic or Epic, and standalone executables
        open no storefront launcher at all.
        What: Returns 'steam', 'heroic', 'legendary', 'epic', or 'none'.
        """
        if game is None:
            return "none"
        gid = (game.game_id or "").strip().lower()
        cat = (game.category or "").strip().lower()
        cmd = (game.launch_cmd or "").strip().lower()
        if gid == "desktop":
            return "none"
        if cat == "heroic" or gid.startswith("heroic_") or cmd.startswith("heroic://"):
            return "heroic"
        if cat == "legendary" or gid.startswith("legendary_") or cmd.startswith("legendary://"):
            return "legendary"
        if cat == "epic" or gid.startswith("epic_") or cmd.startswith("com.epicgames.launcher://"):
            return "epic"
        if cat == "steam" or gid == "steam_bigpicture" or gid.startswith("steam_") or cmd.startswith("steam://"):
            return "steam"
        return "none"

    @classmethod
    def launch_game(
        cls,
        game: GameEntry,
        force_hdmi_only: bool = False,
        user_approved: bool = False,
        steam_root: Optional[Path] = None,
    ) -> bool:
        """
        Launches the specified GameEntry on the laptop and configures display output without shell=True.

        Why: Orchestrates display switching ('TV Screen Only' when HDMI is active) and strictly invokes
        ONLY the game's owning launcher (Steam for Steam titles, Epic for Epic titles, or direct .exe
        for standalone games—never opening an unrelated storefront launcher).
        What: Enforces safety guardrails (blocking execution if unapproved or in test environment),
        calls DisplayModeController.apply_display_mode and spawns the executable or launcher URI safely.
        """
        is_test = (
            "unittest" in sys.modules
            or "pytest" in sys.modules
            or os.environ.get("SOARTV_TEST_MODE") == "1"
        )
        allow_real = os.environ.get("SOARTV_ENABLE_REAL_GAME_LAUNCH") == "1"

        if is_test and not allow_real:
            AgentLogger.info(
                "Dry-run: Real game launch blocked in test environment for %s", game.game_id
            )
            return True

        if not user_approved and not allow_real:
            AgentLogger.warning(
                "Real game launch for '%s' rejected without explicit user approval.", game.game_id
            )
            raise PermissionError(
                f"Real game launch for '{game.game_id}' rejected without explicit user approval."
            )

        cls.wake_from_standby()
        if force_hdmi_only and DisplayModeController.is_external_display_connected():
            DisplayModeController.apply_display_mode(hdmi_connected=True)

        raw_cmd = (game.launch_cmd or "").strip()
        exe_token = ""
        if raw_cmd:
            try:
                tokens = shlex.split(raw_cmd, posix=False)
            except ValueError:
                tokens = raw_cmd.split()
            if tokens:
                exe_token = tokens[0].strip('"').strip("'")
        clean_cmd_name = Path(exe_token).name.lower() if exe_token else ""
        if game.game_id == "desktop" or clean_cmd_name in ("explorer.exe", "explorer"):
            AgentLogger.info("Windows Desktop target requires no process spawn; skipping explorer.exe.")
            return True

        launcher = cls.classify_game_launcher(game)

        try:
            if launcher == "steam":
                steam_exe = SteamPreparationManager.resolve_steam_exe(
                    steam_root or resolve_default_steam_root()
                )
                if game.launch_cmd.startswith("steam://rungameid/"):
                    appid = game.launch_cmd.split("/")[-1].strip()
                    if steam_exe is not None and steam_exe.is_file() and appid.isdigit():
                        steam_running = Win32SystemProbes.is_process_running("steam.exe")
                        launch_args = (
                            [str(steam_exe), "-applaunch", appid]
                            if steam_running
                            else [str(steam_exe), "-silent", "-applaunch", appid]
                        )
                        subprocess.Popen(launch_args, shell=False)
                    else:
                        os.startfile(game.launch_cmd)  # type: ignore[attr-defined]
                else:
                    if steam_exe is not None and steam_exe.is_file():
                        steam_running = Win32SystemProbes.is_process_running("steam.exe")
                        launch_args = (
                            [str(steam_exe), game.launch_cmd]
                            if steam_running
                            else [str(steam_exe), "-silent", game.launch_cmd]
                        )
                        subprocess.Popen(launch_args, shell=False)
                    else:
                        os.startfile(game.launch_cmd)  # type: ignore[attr-defined]
            elif launcher == "heroic":
                heroic_args = HeroicPreparationManager.build_silent_launch_args(game)
                if heroic_args:
                    subprocess.Popen(heroic_args, shell=False)
                else:
                    os.startfile(game.launch_cmd)  # type: ignore[attr-defined]
            elif launcher == "legendary":
                leg_args = HeroicPreparationManager.build_silent_launch_args(game)
                if leg_args:
                    subprocess.Popen(leg_args, shell=False)
                else:
                    fallback_exe = (
                        Path(game.working_dir) / game.exe_name
                        if game.working_dir and game.exe_name
                        else None
                    )
                    if fallback_exe is not None and fallback_exe.is_file():
                        subprocess.Popen([str(fallback_exe)], cwd=str(fallback_exe.parent), shell=False)
                    else:
                        AgentLogger.warning("Standalone legendary CLI not found for %s.", game.game_id)
                        return False
            elif launcher == "epic":
                epic_exe = EpicPreparationManager.resolve_epic_exe()
                if game.launch_cmd.lower().startswith("com.epicgames.launcher://"):
                    if epic_exe is not None and epic_exe.is_file():
                        subprocess.Popen([str(epic_exe), game.launch_cmd], shell=False)
                    else:
                        try:
                            os.startfile(game.launch_cmd)  # type: ignore[attr-defined]
                        except OSError as uri_exc:
                            # Fallback to offline executable if Epic Launcher protocol isn't installed
                            fallback_exe = (
                                Path(game.working_dir) / game.exe_name
                                if game.working_dir and game.exe_name
                                else None
                            )
                            if fallback_exe is not None and fallback_exe.is_file():
                                AgentLogger.warning(
                                    "Epic Games Launcher not installed/registered (%s); falling back to %s.",
                                    type(uri_exc).__name__,
                                    fallback_exe,
                                )
                                subprocess.Popen(
                                    [str(fallback_exe)], cwd=str(fallback_exe.parent), shell=False
                                )
                            else:
                                raise
                else:
                    cwd = (
                        game.working_dir
                        if game.working_dir and os.path.isdir(game.working_dir)
                        else None
                    )
                    subprocess.Popen([game.launch_cmd], cwd=cwd, shell=False)
            else:
                cwd = (
                    game.working_dir
                    if game.working_dir and os.path.isdir(game.working_dir)
                    else None
                )
                subprocess.Popen([game.launch_cmd], cwd=cwd, shell=False)
            return True
        except OSError as exc:
            AgentLogger.warning("Failed launching game %s: %s", game.game_id, type(exc).__name__)
            return False


@dataclass(frozen=True)
class PowerStatus:
    """
    Immutable value object representing the PC's battery and charging state.

    Why: Encapsulates battery percentage and AC charging state to report to the TV Launcher.
    What: Holds battery_percent (0-100 or None if no battery/desktop), charging (bool), and full (bool).
    """

    battery_percent: Optional[int]
    charging: bool
    full: bool

    def to_dict(self) -> Dict[str, Any]:
        """Serializes power status into JSON-compatible dictionary."""
        return {
            "batteryPercent": self.battery_percent,
            "charging": self.charging,
            "batteryCharging": self.charging,
            "full": self.full,
            "batteryFull": self.full,
        }


class SystemPowerController:
    """
    Reads PC battery and AC power status via Windows system power APIs.

    Why: Reports gaming laptop battery level and AC power/charging state so the user
    can monitor battery health and know when the laptop is plugged in or fully charged.
    What: Calls kernel32.GetSystemPowerStatus and computes battery percent, charging, and full status.
    """

    @classmethod
    def read_system_power_status(cls) -> Optional[SYSTEM_POWER_STATUS]:
        """Invokes GetSystemPowerStatus Win32 API via ctypes."""
        if os.name != "nt":
            return None
        try:
            status = SYSTEM_POWER_STATUS()
            success = ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(status))
            if success:
                return status
        except Exception as exc:
            AgentLogger.debug("GetSystemPowerStatus failed: %s", type(exc).__name__)
        return None

    @classmethod
    def get_power_status(cls) -> PowerStatus:
        """Calculates normalized battery percentage, charging state, and 100% full state."""
        raw = cls.read_system_power_status()
        if raw is None:
            return PowerStatus(battery_percent=None, charging=False, full=False)

        ac_line = int(raw.ACLineStatus)
        flag = int(raw.BatteryFlag)
        percent_raw = int(raw.BatteryLifePercent)

        if (
            (flag & BATTERY_FLAG_NO_SYSTEM_BATTERY) != 0
            or flag == BATTERY_VALUE_UNKNOWN
            or percent_raw == BATTERY_VALUE_UNKNOWN
            or percent_raw > BATTERY_MAX_PERCENT
        ):
            charging = ac_line == 1
            return PowerStatus(battery_percent=None, charging=charging, full=False)

        percent = max(0, min(BATTERY_MAX_PERCENT, percent_raw))
        charging = (ac_line == 1) or bool(flag & BATTERY_FLAG_CHARGING)
        full = charging and percent >= BATTERY_MAX_PERCENT
        return PowerStatus(battery_percent=percent, charging=charging, full=full)


# -----------------------------------------------------------------------------
# Application Orchestrator & Embedded Authenticated HTTP Server
# -----------------------------------------------------------------------------


AGENT_SERVICE_ID = "soartv-laptop-gaming-agent"
DISCOVERY_UDP_REQUEST_OP = "SOARTV_GAMING_DISCOVER_V1"
DISCOVERY_UDP_REPLY_OP = "SOARTV_GAMING_HERE_V1"
DEFAULT_TV_REMOTE_PORT = 8484


class LocalNetworkInspector:
    """
    Zero-subprocess LAN IPv4 and hardware MAC address inspector for DHCP/Wi-Fi resilience.

    Why: Routers and Wi-Fi reconnects can assign a new DHCP IPv4 address to the PC or TV at any time.
    Inspecting active socket routes and Win32 adapter tables in-process (<0.2ms, 0 CLI spawns) enables
    automatic IP-change detection and Wake-on-LAN MAC discovery.
    What: Provides get_primary_lan_ipv4() and get_local_mac_addresses().
    """

    @staticmethod
    def get_primary_lan_ipv4() -> str:
        """Returns the PC's primary outbound LAN IPv4 address without sending any network packets."""
        for probe_target in (("192.0.2.1", 80), ("8.8.8.8", 80)):
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.connect(probe_target)
                    ip = str(s.getsockname()[0]).strip()
                    if ip and not ip.startswith("127."):
                        return ip
            except OSError:
                continue
        return ""

    @staticmethod
    def _format_mac_bytes(raw_bytes: bytes) -> str:
        if len(raw_bytes) < 6:
            return ""
        mac = ":".join(f"{b:02X}" for b in raw_bytes[:6])
        if mac in ("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"):
            return ""
        return mac

    @classmethod
    def get_local_mac_addresses(cls) -> List[str]:
        """Returns normalized uppercase XX:XX:XX:XX:XX:XX hardware MAC addresses for physical NICs."""
        seen: List[str] = []
        if sys.platform == "win32":
            try:
                class IP_ADDR_STRING(ctypes.Structure):
                    pass

                IP_ADDR_STRING._fields_ = [
                    ("Next", ctypes.POINTER(IP_ADDR_STRING)),
                    ("IpAddress", ctypes.c_char * 16),
                    ("IpMask", ctypes.c_char * 16),
                    ("Context", wintypes.DWORD),
                ]

                class IP_ADAPTER_INFO(ctypes.Structure):
                    pass

                IP_ADAPTER_INFO._fields_ = [
                    ("Next", ctypes.POINTER(IP_ADAPTER_INFO)),
                    ("ComboIndex", wintypes.DWORD),
                    ("AdapterName", ctypes.c_char * 260),
                    ("Description", ctypes.c_char * 132),
                    ("AddressLength", ctypes.c_uint),
                    ("Address", ctypes.c_ubyte * 8),
                    ("Index", wintypes.DWORD),
                    ("Type", ctypes.c_uint),
                    ("DhcpEnabled", ctypes.c_uint),
                    ("CurrentIpAddress", ctypes.POINTER(IP_ADDR_STRING)),
                    ("IpAddressList", IP_ADDR_STRING),
                    ("GatewayList", IP_ADDR_STRING),
                    ("DhcpServer", IP_ADDR_STRING),
                    ("HaveWins", wintypes.BOOL),
                    ("PrimaryWinsServer", IP_ADDR_STRING),
                    ("SecondaryWinsServer", IP_ADDR_STRING),
                    ("LeaseObtained", ctypes.c_int64),
                    ("LeaseExpires", ctypes.c_int64),
                ]

                iphlpapi = ctypes.WinDLL("iphlpapi", use_last_error=True)
                buf_len = wintypes.ULONG(0)
                iphlpapi.GetAdaptersInfo(None, ctypes.byref(buf_len))
                if buf_len.value > 0:
                    raw_buf = (ctypes.c_ubyte * buf_len.value)()
                    p_info = ctypes.cast(raw_buf, ctypes.POINTER(IP_ADAPTER_INFO))
                    if iphlpapi.GetAdaptersInfo(p_info, ctypes.byref(buf_len)) == 0:
                        cur = p_info
                        while cur:
                            info = cur.contents
                            desc = info.Description.decode("ascii", errors="ignore").lower()
                            if info.AddressLength == 6 and not any(
                                v in desc for v in ("virtual", "hyper-v", "vmware", "loopback", "bluetooth")
                            ):
                                mac = cls._format_mac_bytes(bytes(info.Address[:6]))
                                if mac and mac not in seen:
                                    seen.append(mac)
                            cur = info.Next
            except Exception as exc:
                AgentLogger.debug("GetAdaptersInfo MAC lookup fallback: %s", exc)

        try:
            import uuid as uuid_mod
            node = uuid_mod.getnode()
            if (node >> 40) % 2 == 0:
                fallback_mac = ":".join(f"{(node >> shift) & 0xFF:02X}" for shift in range(40, -1, -8))
                if fallback_mac not in ("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF") and fallback_mac not in seen:
                    seen.append(fallback_mac)
        except Exception:
            pass
        return seen


class UdpDiscoveryResponder:
    """
    Lightweight daemon UDP responder on port 8485 for instant (<15ms) zero-config TV LAN discovery.

    Why: When a Wi-Fi router restarts or DHCP assigns a new IPv4 address to the PC or TV, the TV can
    broadcast a single UDP packet on port 8485 and discover the PC's new IP in ~10ms before falling
    back to a /24 TCP scan.
    What: Listens on UDP (host, port) and replies to SOARTV_GAMING_DISCOVER_V1 with SOARTV_GAMING_HERE_V1.
    """

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = DEFAULT_AGENT_PORT,
        tcp_port: int = DEFAULT_AGENT_PORT,
        uuid_provider: Optional[Callable[[], str]] = None,
        hostname_provider: Optional[Callable[[], str]] = None,
    ) -> None:
        self._host = host
        self._port = port
        self._tcp_port = tcp_port
        self._uuid_provider = uuid_provider or SunshineConfigManager.read_host_uuid
        self._hostname_provider = hostname_provider or SunshineConfigManager.read_host_name
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self.bound_port: int = 0

    def build_discovery_reply(self, raw_packet: bytes) -> Optional[bytes]:
        """Validates an incoming UDP discovery datagram and builds the JSON reply payload."""
        if not raw_packet or len(raw_packet) > 2048:
            return None
        try:
            text = raw_packet.decode("utf-8", errors="ignore").strip()
            if DISCOVERY_UDP_REQUEST_OP not in text:
                return None
            parsed = json.loads(text) if text.startswith("{") else {"op": text}
            if not isinstance(parsed, dict) or parsed.get("op") != DISCOVERY_UDP_REQUEST_OP:
                return None
        except (ValueError, json.JSONDecodeError):
            return None

        reply = {
            "op": DISCOVERY_UDP_REPLY_OP,
            "agentService": AGENT_SERVICE_ID,
            "port": self._tcp_port,
            "sunshineHostUuid": self._uuid_provider() or "",
            "sunshineHostName": self._hostname_provider() or "",
        }
        return json.dumps(reply).encode("utf-8")

    def start(self) -> bool:
        """Binds the UDP socket and starts the daemon listener thread."""
        if self._sock is not None:
            return True
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self._host, self._port))
            sock.settimeout(0.5)
            self.bound_port = int(sock.getsockname()[1])
            self._sock = sock
            self._stop_event.clear()
            self._thread = threading.Thread(
                target=self._serve_loop, daemon=True, name="soartv-udp-discovery"
            )
            self._thread.start()
            return True
        except OSError as exc:
            AgentLogger.warning("UDP discovery bind warning on %s:%d: %s", self._host, self._port, exc)
            return False

    def _serve_loop(self) -> None:
        while not self._stop_event.is_set():
            sock = self._sock
            if sock is None:
                break
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            reply = self.build_discovery_reply(data)
            if reply is not None:
                try:
                    sock.sendto(reply, addr)
                except OSError:
                    pass

    def stop(self) -> None:
        """Stops the UDP listener thread and closes the socket."""
        self._stop_event.set()
        sock = self._sock
        self._sock = None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self._thread = None


class TvBeaconNotifier:
    """
    Remembers the paired TV's LAN endpoint and pushes an announce trigger whenever the PC's IP changes.

    Why: If the PC restarts or Wi-Fi reassigns the PC's DHCP IP while the TV stays on, proactively
    notifying POST http://<tv_ip>:8484/api/gaming/pc-announce prompts the TV to verify and adopt the
    PC's new IP in 0ms—without sending any secret token over the announce request.
    What: Stores (tv_ip, tv_port) on authenticated TV requests and triggers pc-announce on startup/IP change.
    """

    def __init__(
        self,
        endpoint_store_path: Optional[Path] = None,
        default_tv_port: int = DEFAULT_TV_REMOTE_PORT,
    ) -> None:
        self._path = endpoint_store_path or (Path.home() / ".soartv_last_tv_endpoint.json")
        self._default_tv_port = default_tv_port
        self._lock = threading.Lock()
        self._last_announced_pc_ip: str = ""

    @staticmethod
    def is_loopback_or_invalid_ip(ip_str: str) -> bool:
        """
        Returns True for empty, 'unknown', 'localhost', loopback (`127.0.0.0/8`, `::1`, `::ffff:127.x`),
        unspecified (`0.0.0.0`, `::`), or multicast addresses so local probes never overwrite the TV IP.
        """
        clean = (ip_str or "").strip()
        if not clean:
            return True
        if clean.lower() in ("unknown", "localhost"):
            return True
        if clean.startswith("[") and clean.endswith("]") and len(clean) > 2:
            clean = clean[1:-1]
        if "%" in clean:
            clean = clean.split("%", 1)[0]
        try:
            addr = ipaddress.ip_address(clean)
        except ValueError:
            return True
        if addr.version == 6 and addr.ipv4_mapped is not None:
            addr = addr.ipv4_mapped
        return bool(addr.is_loopback or addr.is_unspecified or addr.is_multicast)

    def record_authenticated_tv(
        self,
        client_ip: str,
        tv_port_header: Optional[str] = None,
        tv_id_header: Optional[str] = None,
    ) -> None:
        """Persists an authenticated TV's LAN IPv4/IPv6 address and port (supports multiple household TVs)."""
        clean_ip = (client_ip or "").strip()
        if not clean_ip or clean_ip.lower() == "unknown":
            return
        if self.is_loopback_or_invalid_ip(clean_ip):
            try:
                is_prod_store = self._path.resolve() == (Path.home() / ".soartv_last_tv_endpoint.json").resolve()
            except OSError:
                is_prod_store = False
            if is_prod_store:
                return
            existing = self.load_saved_tv_endpoint()
            if existing is not None and not self.is_loopback_or_invalid_ip(existing[0]):
                return
            try:
                parsed_addr = ipaddress.ip_address(clean_ip.split("%", 1)[0].strip("[]"))
                if parsed_addr.version == 6 and parsed_addr.ipv4_mapped is not None:
                    parsed_addr = parsed_addr.ipv4_mapped
                if not parsed_addr.is_loopback:
                    return
            except ValueError:
                return
        port = self._default_tv_port
        if tv_port_header:
            try:
                parsed_p = int(str(tv_port_header).strip())
                if 1 <= parsed_p <= 65535:
                    port = parsed_p
            except ValueError:
                pass
        tv_key = (tv_id_header or "").strip() or f"{clean_ip}:{port}"
        now_ts = time.time()
        with self._lock:
            tvs_map: Dict[str, Dict[str, Any]] = {}
            try:
                if self._path.is_file():
                    prev = json.loads(self._path.read_text(encoding="utf-8"))
                    if isinstance(prev, dict):
                        if isinstance(prev.get("tvs"), dict):
                            for k, entry in prev["tvs"].items():
                                if isinstance(entry, dict) and entry.get("tvIp"):
                                    tvs_map[str(k)] = dict(entry)
                        elif prev.get("tvIp"):
                            old_ip = str(prev.get("tvIp", "")).strip()
                            old_port = int(prev.get("tvPort", self._default_tv_port))
                            if old_ip:
                                tvs_map[f"{old_ip}:{old_port}"] = {
                                    "tvIp": old_ip,
                                    "tvPort": old_port,
                                    "updatedAt": float(prev.get("updatedAt", now_ts)),
                                }
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                pass
            tvs_map[tv_key] = {"tvIp": clean_ip, "tvPort": port, "updatedAt": now_ts}
            payload = {
                "tvIp": clean_ip,
                "tvPort": port,
                "updatedAt": now_ts,
                "tvs": tvs_map,
            }
            try:
                self._path.parent.mkdir(parents=True, exist_ok=True)
                self._path.write_text(json.dumps(payload), encoding="utf-8")
            except OSError:
                pass

    def load_saved_tv_endpoint(self) -> Optional[Tuple[str, int]]:
        """Loads the most recently authenticated (tv_ip, tv_port) tuple if available."""
        with self._lock:
            try:
                if not self._path.is_file():
                    return None
                raw = json.loads(self._path.read_text(encoding="utf-8"))
                tv_ip = str(raw.get("tvIp", "")).strip()
                tv_port = int(raw.get("tvPort", self._default_tv_port))
                if tv_ip and 1 <= tv_port <= 65535:
                    return (tv_ip, tv_port)
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                return None
        return None

    def load_all_saved_tv_endpoints(self) -> List[Tuple[str, int]]:
        """Loads every unique saved household (tv_ip, tv_port) endpoint (multi-TV support)."""
        out: List[Tuple[str, int]] = []
        seen: Set[Tuple[str, int]] = set()
        with self._lock:
            try:
                if not self._path.is_file():
                    return []
                raw = json.loads(self._path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    tvs_map = raw.get("tvs")
                    if isinstance(tvs_map, dict):
                        for entry in tvs_map.values():
                            if not isinstance(entry, dict):
                                continue
                            ip = str(entry.get("tvIp", "")).strip()
                            port = int(entry.get("tvPort", self._default_tv_port))
                            if ip and 1 <= port <= 65535 and (ip, port) not in seen:
                                seen.add((ip, port))
                                out.append((ip, port))
                    top_ip = str(raw.get("tvIp", "")).strip()
                    top_port = int(raw.get("tvPort", self._default_tv_port))
                    if top_ip and 1 <= top_port <= 65535 and (top_ip, top_port) not in seen:
                        seen.add((top_ip, top_port))
                        out.append((top_ip, top_port))
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                return []
        return out

    @staticmethod
    def discover_lan_tvs_udp(tv_port: int = DEFAULT_TV_REMOTE_PORT, timeout_sec: float = 0.25) -> List[Tuple[str, int]]:
        """Broadcasts SOARTV_TV_DISCOVER_V1 on UDP 8484 to locate household TVs even after simultaneous DHCP IP changes."""
        found: List[Tuple[str, int]] = []
        seen: Set[Tuple[str, int]] = set()
        probe = json.dumps({"op": "SOARTV_TV_DISCOVER_V1"}).encode("utf-8")
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.settimeout(min(0.15, max(0.05, timeout_sec)))
                sock.sendto(probe, ("255.255.255.255", tv_port))
                deadline = time.monotonic() + max(0.08, timeout_sec)
                while time.monotonic() < deadline:
                    try:
                        data, addr = sock.recvfrom(1024)
                    except socket.timeout:
                        break
                    try:
                        msg = json.loads(data.decode("utf-8", errors="ignore"))
                    except json.JSONDecodeError:
                        continue
                    if isinstance(msg, dict) and msg.get("op") == "SOARTV_TV_HERE_V1":
                        lan_ip = str(msg.get("lanIp") or addr[0]).strip()
                        port = int(msg.get("port") or tv_port)
                        if lan_ip and 1 <= port <= 65535 and (lan_ip, port) not in seen:
                            seen.add((lan_ip, port))
                            found.append((lan_ip, port))
        except OSError:
            pass
        return found

    @staticmethod
    def broadcast_udp_announce(current_pc_ip: str, agent_port: int = DEFAULT_AGENT_PORT) -> bool:
        """Emits a zero-secret LAN UDP presence packet so nearby TVs learn the PC's new IP immediately."""
        payload = json.dumps(
            {
                "op": "SOARTV_GAMING_HERE_V1",
                "agentService": AGENT_SERVICE_ID,
                "agentPort": agent_port,
                "pcIp": current_pc_ip or "",
            }
        ).encode("utf-8")
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.sendto(payload, ("255.255.255.255", agent_port))
                return True
        except OSError:
            return False

    def notify_ip_changed(
        self,
        current_pc_ip: str,
        agent_port: int = DEFAULT_AGENT_PORT,
        discover_udp: bool = False,
    ) -> bool:
        """Sends POST /api/gaming/pc-announce to every saved household TV (and optional UDP-discovered TVs)."""
        endpoints = self.load_all_saved_tv_endpoints()
        if discover_udp:
            for ep in self.discover_lan_tvs_udp(tv_port=self._default_tv_port):
                if ep not in endpoints:
                    endpoints.append(ep)
        if not endpoints:
            return False
        body = json.dumps(
            {
                "agentService": AGENT_SERVICE_ID,
                "agentPort": agent_port,
                "pcIp": current_pc_ip or "",
            }
        ).encode("utf-8")
        any_ok = False
        for tv_ip, tv_port in endpoints:
            req = urllib.request.Request(
                f"http://{tv_ip}:{tv_port}/api/gaming/pc-announce",
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=1.5) as resp:
                    if 200 <= resp.status < 300:
                        any_ok = True
            except Exception as exc:
                AgentLogger.debug("TV pc-announce skipped (%s:%d): %s", tv_ip, tv_port, type(exc).__name__)
        if any_ok:
            self._last_announced_pc_ip = current_pc_ip
        return any_ok

    def check_and_notify_if_ip_changed(self, agent_port: int = DEFAULT_AGENT_PORT) -> None:
        """Checks the PC's primary LAN IPv4 and notifies all household TVs + LAN UDP whenever it changes."""
        now_ip = LocalNetworkInspector.get_primary_lan_ipv4()
        if now_ip and now_ip != self._last_announced_pc_ip:
            self.broadcast_udp_announce(now_ip, agent_port=agent_port)
            if not self.notify_ip_changed(now_ip, agent_port=agent_port, discover_udp=True):
                # Still record locally after one attempt so offline TVs aren't polled every 2s,
                # while re-notifying immediately if Wi-Fi drops and assigns yet another IP.
                self._last_announced_pc_ip = now_ip


class SupabaseHostReporter:
    """
    Optional zero-dependency cloud rendezvous reporter for `public.gaming_hosts` (SoarTV v2 IP resilience).

    Why: If the Gaming PC and every TV in the household change routers, subnets, or DHCP leases at the
    same time (or if a TV is on LG webOS / Samsung Tizen without raw LAN UDP sockets), upserting the
    PC's live LAN IP + NIC MAC addresses into Supabase `gaming_hosts` lets every household TV locate
    and wake the PC automatically. Silently no-ops when Supabase credentials are absent or offline.
    What: Loads optional `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `HOUSEHOLD_KEY` from env / `.env` /
    `config.properties` and upserts `POST /rest/v1/gaming_hosts?on_conflict=host_id` on boot & IP change.
    """

    def __init__(
        self,
        supabase_url: Optional[str] = None,
        supabase_anon_key: Optional[str] = None,
        household_key: Optional[str] = None,
        repo_root: Optional[Path] = None,
    ) -> None:
        self._repo_root = repo_root or Path(__file__).resolve().parents[3]
        self._explicit_url = supabase_url
        self._explicit_anon = supabase_anon_key
        self._explicit_hh = household_key
        self._last_reported_ip: str = ""
        self._lock = threading.Lock()

    @staticmethod
    def _read_kv_file(path: Path) -> Dict[str, str]:
        out: Dict[str, str] = {}
        try:
            if not path.is_file():
                return out
            for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, _, v = line.partition("=")
                ck = k.strip()
                cv = v.strip().strip('"').strip("'")
                if ck and cv:
                    out[ck] = cv
        except OSError:
            pass
        return out

    def resolve_credentials(self) -> Tuple[str, str, str]:
        """Returns `(supabase_url, supabase_anon_key, household_key)` from explicit args, env, or local config."""
        url = (self._explicit_url or os.environ.get("SUPABASE_URL", "")).strip()
        anon = (self._explicit_anon or os.environ.get("SUPABASE_ANON_KEY", "")).strip()
        hh = (self._explicit_hh or os.environ.get("HOUSEHOLD_KEY", "")).strip()
        if not (url and anon and hh):
            env_map = self._read_kv_file(self._repo_root / ".env")
            cfg_map = self._read_kv_file(self._repo_root / "SoarTV-launcher" / "config.properties")
            url = url or env_map.get("SUPABASE_URL", "") or cfg_map.get("supabase_url", "")
            anon = anon or env_map.get("SUPABASE_ANON_KEY", "") or cfg_map.get("supabase_anon_key", "")
            hh = hh or env_map.get("HOUSEHOLD_KEY", "") or cfg_map.get("household_key", "")
        return (url.strip().rstrip("/"), anon.strip(), hh.strip())

    def is_configured(self) -> bool:
        url, anon, hh = self.resolve_credentials()
        return bool(url and anon and len(hh) >= 16)

    @staticmethod
    def compute_host_id(sunshine_uuid: str = "", hostname: str = "") -> str:
        """Builds a deterministic host_id anchored to Sunshine UUID or sanitized hostname."""
        clean_uuid = (sunshine_uuid or "").strip().lower()
        if clean_uuid:
            return f"sunshine-{clean_uuid}"
        clean_host = re.sub(r"[^a-z0-9_-]+", "-", (hostname or socket.gethostname() or "gaming-pc").strip().lower()).strip("-")
        return f"pc-{clean_host or 'gaming'}"

    def build_host_payload(
        self,
        lan_ip: str,
        agent_port: int = DEFAULT_AGENT_PORT,
        mac_addresses: Optional[Sequence[str]] = None,
        sunshine_uuid: Optional[str] = None,
        hostname: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Builds the canonical `public.gaming_hosts` upsert JSON payload."""
        s_uuid = sunshine_uuid if sunshine_uuid is not None else (SunshineConfigManager.read_host_uuid() or "")
        host_name = (hostname or socket.gethostname() or "Gaming-PC").strip()
        macs = list(mac_addresses) if mac_addresses is not None else LocalNetworkInspector.get_local_mac_addresses()
        return {
            "host_id": self.compute_host_id(s_uuid, host_name),
            "hostname": host_name,
            "lan_ip": (lan_ip or "").strip(),
            "agent_port": int(agent_port),
            "mac_addresses": macs,
            "sunshine_uuid": s_uuid,
            "last_seen_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }

    def report_host(
        self,
        lan_ip: str,
        agent_port: int = DEFAULT_AGENT_PORT,
        mac_addresses: Optional[Sequence[str]] = None,
        sunshine_uuid: Optional[str] = None,
        hostname: Optional[str] = None,
    ) -> bool:
        """Upserts this Gaming PC's live LAN IP and MACs to Supabase `public.gaming_hosts`."""
        if not lan_ip or not self.is_configured():
            return False
        url, anon, hh = self.resolve_credentials()
        payload = self.build_host_payload(
            lan_ip=lan_ip,
            agent_port=agent_port,
            mac_addresses=mac_addresses,
            sunshine_uuid=sunshine_uuid,
            hostname=hostname,
        )
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{url}/rest/v1/gaming_hosts?on_conflict=host_id",
            data=body,
            headers={
                "Content-Type": "application/json; charset=UTF-8",
                "Accept": "application/json",
                "apikey": anon,
                "Authorization": f"Bearer {anon}",
                "x-soartv-household-key": hh,
                "Prefer": "resolution=merge-duplicates,return=minimal",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=4.0) as resp:
                if 200 <= resp.status < 300:
                    with self._lock:
                        self._last_reported_ip = lan_ip.strip()
                    return True
        except Exception as exc:
            AgentLogger.debug("Supabase gaming_hosts upsert skipped: %s", type(exc).__name__)
        return False

    def check_and_report_if_ip_changed(self, agent_port: int = DEFAULT_AGENT_PORT) -> None:
        """Upserts `gaming_hosts` on startup and whenever the PC's LAN IPv4 address changes."""
        if not self.is_configured():
            return
        now_ip = LocalNetworkInspector.get_primary_lan_ipv4()
        if not now_ip:
            return
        with self._lock:
            already = self._last_reported_ip == now_ip
        if already:
            return
        if not self.report_host(now_ip, agent_port=agent_port):
            with self._lock:
                self._last_reported_ip = now_ip


class LaptopGamingAgentApp:
    """
    Coordinates game scanning, hidden-game state, Sunshine sync, auth/rate-limiting, and Win32 probes.

    Why: Provides a single thread-safe domain facade consumed by both CLI tools and the HTTP server.
    What: Caches sub-millisecond Win32 probe telemetry, manages AgentAuthManager and rate limiters,
    and builds authenticated vs. unauthenticated status payloads.
    """

    def __init__(
        self,
        games_root: Optional[Path] = None,
        steam_root: Optional[Path] = None,
        state_dir: Optional[Path] = None,
        auth_manager: Optional[AgentAuthManager] = None,
        tv_beacon: Optional[TvBeaconNotifier] = None,
    ) -> None:
        base_dir = state_dir or (Path(__file__).resolve().parent / ".state")
        self.scanner = GameCatalogScanner(games_root=games_root, steam_root=steam_root)
        self.hidden_mgr = HiddenGamesManager(base_dir / "hidden_games.json")
        if auth_manager is not None:
            self.auth_manager = auth_manager
        elif state_dir is not None:
            self.auth_manager = AgentAuthManager(
                auth_path=base_dir / "auth.json",
                pair_code_path=base_dir / "pair_code.txt",
            )
        else:
            self.auth_manager = AgentAuthManager()
        self.tv_beacon = tv_beacon or TvBeaconNotifier(
            endpoint_store_path=(base_dir / "last_tv.json") if state_dir is not None else None
        )
        self.supabase_reporter = SupabaseHostReporter()
        self.unlock_rate_limiter = SlidingWindowRateLimiter()
        self.pair_rate_limiter = SlidingWindowRateLimiter()
        self.sunshine_lifecycle = SunshineLifecycleController()
        self.steam_idle_shutdown = GamingIdleShutdownManager(
            grace_seconds=DEFAULT_STEAM_IDLE_GRACE_SECONDS,
            steam_root=steam_root,
            is_steam_running_fn=lambda: Win32SystemProbes.is_process_running("steam.exe"),
            is_epic_running_fn=EpicPreparationManager.is_epic_running,
            is_heroic_running_fn=HeroicPreparationManager.is_heroic_running,
            is_sunshine_running_fn=self.sunshine_lifecycle.is_running,
            is_session_active_fn=lambda: self.privacy_curtain.is_active(),
            is_any_game_running_fn=self.is_any_discovered_game_running,
            shutdown_epic_fn=lambda: EpicPreparationManager.shutdown_epic(),
            shutdown_heroic_fn=lambda: HeroicPreparationManager.shutdown_heroic(),
            shutdown_sunshine_fn=lambda: self.sunshine_lifecycle.stop_sunshine(),
        )
        self.idle_shutdown = self.steam_idle_shutdown
        self.privacy_curtain = PrivacyCurtainManager(
            steam_retry_launch_fn=lambda g: WindowsSessionController.launch_game(
                g, force_hdmi_only=False, user_approved=True, steam_root=self.scanner.steam_root
            ),
            epic_retry_launch_fn=lambda g: WindowsSessionController.launch_game(
                g, force_hdmi_only=False, user_approved=True, steam_root=self.scanner.steam_root
            ),
            on_session_end_fn=lambda reason: self.steam_idle_shutdown.schedule_shutdown(reason=reason),
        )
        self.safety_net = self.privacy_curtain.safety_net
        if getattr(self.safety_net, "_browser_isolation_guard", None) is not None:
            try:
                self.safety_net._browser_isolation_guard.recover_stale_override()
            except Exception:
                pass
        self.esc_listener = self.privacy_curtain.esc_listener
        self.process_controller = GameProcessController(
            safety_net=self.safety_net,
            privacy_curtain=self.privacy_curtain,
        )
        self.privacy_curtain.process_controller = self.process_controller
        self._lock = threading.Lock()
        self._cached_all_games: List[GameEntry] = []
        self._cached_hdmi: bool = False
        self._last_hdmi_connected_ts: float = 0.0
        self._cached_locked: bool = False
        self._cached_sunshine: bool = True
        self._cached_steam_running: bool = False
        self._cached_epic_running: bool = False
        self._cached_heroic_running: bool = False
        self._cached_sunshine_power_ctrl: bool = False
        self._sunshine_sync_needed: bool = False
        self._cached_power: PowerStatus = PowerStatus(None, False, False)
        self._cached_power_probed: bool = False
        self._cached_primary_display: Tuple[int, int, int] = (0, 0, 0)

    @staticmethod
    def resolve_connection_mode(requested_mode: str, hdmi_connected: bool) -> str:
        """
        Determines whether to use 'hdmi' or 'wifi' mode when launching a game.

        Why: Even if the TV requests 'auto' or 'hdmi' mode, the laptop must ONLY switch to HDMI
        ('TV Screen Only') if an external TV HDMI display is physically connected to the laptop.
        What: Returns 'hdmi' iff hdmi_connected is True and requested_mode is not 'wifi'.
        """
        mode = (requested_mode or "auto").strip().lower()
        if mode == "wifi" or not hdmi_connected:
            return "wifi"
        return "hdmi"

    def refresh_and_sync(self, include_hidden: bool = False, sync_sunshine: bool = True) -> List[GameEntry]:
        """Links Steam VDF, discovers all games, optionally syncs Sunshine apps.json & API, and returns filtered games."""
        self.scanner.sync_steam_library_vdf()
        all_games = self.scanner.discover_games()
        if sync_sunshine:
            SunshineConfigManager.sync_to_disk(all_games)
            if WindowsSessionController.is_sunshine_running():
                SunshineConfigManager.sync_via_api(all_games)
                with self._lock:
                    self._sunshine_sync_needed = False
            else:
                with self._lock:
                    self._sunshine_sync_needed = True
        with self._lock:
            self._cached_all_games = list(all_games)
        return self.hidden_mgr.apply_visibility(all_games, include_hidden=include_hidden)

    def ensure_sunshine_ready_and_synced(self) -> bool:
        """Starts Sunshine on demand (if stopped) and syncs any pending app updates once online."""
        self.steam_idle_shutdown.cancel("sunshine_start_requested")
        ready = self.sunshine_lifecycle.ensure_running()
        with self._lock:
            self._cached_sunshine = ready
            need_sync = self._sunshine_sync_needed
            cached_games = list(self._cached_all_games)
        if ready and need_sync:
            try:
                games_to_sync = cached_games or self.get_cached_games(include_hidden=True)
                SunshineConfigManager.sync_via_api(games_to_sync)
                with self._lock:
                    self._sunshine_sync_needed = False
            except Exception as exc:
                AgentLogger.debug("Deferred Sunshine app sync warning: %s", type(exc).__name__)
        return ready

    def update_runtime_probe(self) -> bool:
        """Polls HDMI display, Windows lock, Steam/Epic/Heroic/Sunshine state, battery, and primary display mode via Win32 APIs."""
        hdmi = DisplayModeController.is_external_display_connected()
        locked = WindowsSessionController.is_session_locked()
        sunshine = WindowsSessionController.is_sunshine_running()
        steam_on = Win32SystemProbes.is_process_running("steam.exe")
        epic_on = Win32SystemProbes.is_process_running("epicgameslauncher.exe")
        heroic_on = Win32SystemProbes.is_process_running("heroic.exe")
        sunshine_ctrl_ready = self.sunshine_lifecycle.can_control_service()
        power = SystemPowerController.get_power_status()
        disp_mode = DisplayRefreshRateController.get_primary_display_mode()
        now = time.time()
        with self._lock:
            self._cached_hdmi = hdmi
            if hdmi:
                self._last_hdmi_connected_ts = now
            self._cached_locked = locked
            self._cached_sunshine = sunshine
            self._cached_steam_running = steam_on
            self._cached_epic_running = epic_on
            self._cached_heroic_running = heroic_on
            self._cached_sunshine_power_ctrl = sunshine_ctrl_ready
            self._cached_power = power
            self._cached_power_probed = True
            if disp_mode[0] > 0 and disp_mode[1] > 0:
                self._cached_primary_display = disp_mode
            if not self._cached_all_games:
                self._cached_all_games = self.scanner.discover_games()
        return hdmi

    def get_cached_games(self, include_hidden: bool = True) -> List[GameEntry]:
        """Returns the in-memory cached game catalog with current visibility flags applied (<1ms)."""
        with self._lock:
            base = list(self._cached_all_games)
        if not base:
            base = self.refresh_and_sync(include_hidden=True, sync_sunshine=False)
        return self.hidden_mgr.apply_visibility(base, include_hidden=include_hidden)

    def is_any_discovered_game_running(self) -> bool:
        """
        Checks whether any discovered game executable is currently running on the PC.

        Why: Prevents SteamIdleShutdownManager from closing Steam if the user started playing a game
        directly on their laptop during the 10-minute grace window.
        """
        try:
            running_names = Win32SystemProbes.snapshot_process_names()
            if not running_names:
                return False
            for game in self.get_cached_games(include_hidden=True):
                if game.game_id in ("desktop", "steam_bigpicture"):
                    continue
                expected = PrivacyCurtainManager.resolve_expected_process_names(game)
                if expected and any(name in running_names for name in expected):
                    return True
        except Exception:
            return False
        return False

    def terminate_active_session(
        self, game_id: str = "", reason: str = "remote_home_exit"
    ) -> Dict[str, Any]:
        """Terminates active streaming session, terminates running game, restores PC state, and arms Steam idle shutdown."""
        target_game: Optional[GameEntry] = None
        if game_id:
            games = self.get_cached_games(include_hidden=True)
            target_game = next((g for g in games if g.game_id == game_id), None)
        was_active = self.privacy_curtain.is_active()
        res = self.process_controller.terminate_game(target_game=target_game, reason=reason)
        if was_active:
            res["terminated"] = True
        self.steam_idle_shutdown.schedule_shutdown(reason=f"terminate:{reason}")
        return res

    def build_status_payload(
        self,
        include_hidden: bool = True,
        fast: bool = True,
        authenticated: bool = True,
    ) -> Dict[str, Any]:
        """
        Builds the JSON status dictionary returned by GET /api/gaming/status and GET /health.

        Why: Authenticated callers receive the full game catalog and paired=True, whereas
        unauthenticated callers receive safe read-only online/battery/HDMI telemetry with
        paired=False, pairingRequired=True, and games=[] so the TV knows pairing is required.
        What: Returns a dictionary matching the GamingZone.java wire contract.
        """
        if not fast:
            self.update_runtime_probe()
            games = (
                self.refresh_and_sync(include_hidden=include_hidden, sync_sunshine=False)
                if authenticated
                else []
            )
        else:
            games = self.get_cached_games(include_hidden=include_hidden) if authenticated else []

        with self._lock:
            hdmi = self._cached_hdmi
            last_hdmi_ts = self._last_hdmi_connected_ts
            locked = self._cached_locked
            sunshine = self._cached_sunshine
            steam_running = self._cached_steam_running
            epic_running = self._cached_epic_running
            heroic_running = self._cached_heroic_running
            sunshine_power_ctrl = self._cached_sunshine_power_ctrl
            power = self._cached_power
            probed = self._cached_power_probed
            primary_disp = self._cached_primary_display

        if not fast or not probed:
            power = SystemPowerController.get_power_status()
            disp_probe = DisplayRefreshRateController.get_primary_display_mode()
            with self._lock:
                self._cached_power = power
                self._cached_power_probed = True
                if disp_probe[0] > 0 and disp_probe[1] > 0:
                    self._cached_primary_display = disp_probe
                    primary_disp = disp_probe

        if not authenticated:
            return {
                "ok": True,
                "agentService": AGENT_SERVICE_ID,
                "authenticated": False,
                "online": True,
                "awake": True,
                "paired": False,
                "pairingRequired": True,
                "hdmiConnected": hdmi,
                "connectionMode": "hdmi" if hdmi else "wifi",
                "sunshineRunning": sunshine,
                "steamRunning": steam_running,
                "epicRunning": epic_running,
                "heroicRunning": heroic_running,
                "sunshineHostUuid": SunshineConfigManager.read_host_uuid(),
                "sunshineHostName": SunshineConfigManager.read_host_name(),
                "batteryPercent": power.battery_percent,
                "charging": power.charging,
                "batteryCharging": power.charging,
                "full": power.full,
                "batteryFull": power.full,
                "games": [],
            }

        hdmi_recent = hdmi or (
            last_hdmi_ts > 0.0 and (time.time() - last_hdmi_ts) <= HDMI_RECENT_WINDOW_SEC and hdmi
        )
        return {
            "ok": True,
            "agentService": AGENT_SERVICE_ID,
            "authenticated": True,
            "online": True,
            "awake": True,
            "paired": True,
            "pairingRequired": False,
            "locked": locked,
            "hdmiConnected": hdmi,
            "hdmiConnectedRecently": hdmi_recent,
            "connectionMode": "hdmi" if hdmi else "wifi",
            "sunshineRunning": sunshine,
            "steamRunning": steam_running,
            "epicRunning": epic_running,
            "heroicRunning": heroic_running,
            "sunshinePowerControlReady": sunshine_power_ctrl,
            "sunshineHostUuid": SunshineConfigManager.read_host_uuid(),
            "sunshineHostName": SunshineConfigManager.read_host_name(),
            "lanIp": LocalNetworkInspector.get_primary_lan_ipv4(),
            "macAddresses": LocalNetworkInspector.get_local_mac_addresses(),
            "batteryPercent": power.battery_percent,
            "charging": power.charging,
            "batteryCharging": power.charging,
            "full": power.full,
            "batteryFull": power.full,
            "primaryDisplayWidth": primary_disp[0],
            "primaryDisplayHeight": primary_disp[1],
            "primaryDisplayRefreshHz": primary_disp[2],
            "games": [g.to_dict() for g in games],
        }


def create_http_server(
    app: LaptopGamingAgentApp,
    host: str = "0.0.0.0",
    port: int = DEFAULT_AGENT_PORT,
    unlock_handler: Optional[Callable[[str], bool]] = None,
) -> ThreadingHTTPServer:
    """
    Constructs a ThreadingHTTPServer bound to (host, port) enforcing CORS, token auth, and rate limits.

    Why: Extracting server construction enables fast, isolated integration tests on ephemeral ports
    while running identical HTTP security middleware in production.
    What: Returns a configured ThreadingHTTPServer ready for serve_forever().
    """
    unlock_fn = unlock_handler or WindowsSessionController.unlock_with_pin

    class Handler(BaseHTTPRequestHandler):
        timeout = HTTP_REQUEST_TIMEOUT_SEC

        def _client_ip(self) -> str:
            if isinstance(self.client_address, tuple) and self.client_address:
                return str(self.client_address[0])
            return "unknown"

        def _send_json(
            self,
            code: int,
            payload: Dict[str, Any],
            extra_headers: Optional[Dict[str, str]] = None,
        ) -> None:
            raw = json.dumps(payload).encode("utf-8")
            allowed_origin = AgentAuthManager.resolve_allowed_cors_origin(self.headers)
            try:
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                if allowed_origin:
                    self.send_header("Access-Control-Allow-Origin", allowed_origin)
                    self.send_header("Vary", "Origin")
                    self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    self.send_header(
                        "Access-Control-Allow-Headers",
                        "Content-Type, X-SoarTV-Token, Authorization",
                    )
                if extra_headers:
                    for hk, hv in extra_headers.items():
                        self.send_header(hk, hv)
                self.end_headers()
                self.wfile.write(raw)
            except (OSError, BrokenPipeError, ConnectionAbortedError, ConnectionResetError) as exc:
                AgentLogger.debug("Client socket closed early: %s", type(exc).__name__)

        def _reject_if_cross_origin(self) -> bool:
            if AgentAuthManager.is_cross_origin_forbidden(self.headers):
                try:
                    length = min(
                        HTTP_MAX_BODY_BYTES,
                        max(0, int(self.headers.get("Content-Length", "0") or "0")),
                    )
                    if length > 0:
                        self.rfile.read(length)
                except (ValueError, OSError, TimeoutError, socket.timeout):
                    self.close_connection = True
                    pass
                self._send_json(403, {"ok": False, "error": "cross_origin_forbidden"})
                return True
            return False

        def do_OPTIONS(self) -> None:  # noqa: N802
            if self._reject_if_cross_origin():
                return
            self._send_json(200, {"ok": True})

        def do_GET(self) -> None:  # noqa: N802
            if self._reject_if_cross_origin():
                return
            clean_path = self.path.split("?", 1)[0]
            if clean_path in ("/api/gaming/status", "/health"):
                is_authed = app.auth_manager.is_request_authenticated(self.headers)
                if is_authed:
                    client_ip = self._client_ip()
                    if not TvBeaconNotifier.is_loopback_or_invalid_ip(client_ip):
                        app.tv_beacon.record_authenticated_tv(
                            client_ip,
                            self.headers.get("X-SoarTV-Tv-Port"),
                            self.headers.get("X-SoarTV-Tv-Id"),
                        )
                self._send_json(
                    200,
                    app.build_status_payload(
                        include_hidden=True,
                        fast=True,
                        authenticated=is_authed,
                    ),
                )
            elif clean_path == "/api/gaming/launch-status":
                if not app.auth_manager.is_request_authenticated(self.headers):
                    self._send_json(
                        401,
                        {"ok": False, "error": "unauthorized", "pairingRequired": True},
                    )
                    return
                self._send_json(200, app.privacy_curtain.get_launch_readiness())
            else:
                self._send_json(404, {"ok": False, "error": "not_found"})

        def do_POST(self) -> None:  # noqa: N802
            if self._reject_if_cross_origin():
                return
            try:
                length = min(
                    HTTP_MAX_BODY_BYTES,
                    max(0, int(self.headers.get("Content-Length", "0") or "0")),
                )
            except ValueError:
                length = 0
            try:
                body = self.rfile.read(length).decode("utf-8", errors="ignore") if length > 0 else "{}"
            except (TimeoutError, socket.timeout, OSError):
                self.close_connection = True
                return
            try:
                parsed = json.loads(body) if body else {}
                req: Dict[str, Any] = parsed if isinstance(parsed, dict) else {}
            except json.JSONDecodeError:
                req = {}

            clean_path = self.path.split("?", 1)[0]
            client_ip = self._client_ip()

            if clean_path == "/api/gaming/pair/request":
                self._send_json(200, app.auth_manager.request_pairing_code())
                return

            if clean_path == "/api/gaming/pair/verify":
                allowed, retry_after = app.pair_rate_limiter.check(client_ip)
                if not allowed:
                    self._send_json(
                        429,
                        {
                            "ok": False,
                            "error": "rate_limited",
                            "retryAfterSeconds": retry_after,
                        },
                        extra_headers={"Retry-After": str(retry_after)},
                    )
                    return

                code_str = str(req.get("code", ""))
                dev_name = str(req.get("deviceName", "SoarTV"))
                issued_token = app.auth_manager.verify_pairing_code(code_str, dev_name)
                if not issued_token:
                    app.pair_rate_limiter.record_event(client_ip)
                    self._send_json(
                        401,
                        {"ok": False, "paired": False, "error": "invalid_or_expired_code"},
                    )
                    return

                if not TvBeaconNotifier.is_loopback_or_invalid_ip(client_ip):
                    app.tv_beacon.record_authenticated_tv(
                        client_ip,
                        self.headers.get("X-SoarTV-Tv-Port"),
                        self.headers.get("X-SoarTV-Tv-Id"),
                    )
                self._send_json(200, {"ok": True, "paired": True, "token": issued_token})
                return

            # Every mutation endpoint strictly requires a valid X-SoarTV-Token / Bearer token
            if not app.auth_manager.is_request_authenticated(self.headers):
                self._send_json(
                    401,
                    {"ok": False, "error": "unauthorized", "pairingRequired": True},
                )
                return

            if not TvBeaconNotifier.is_loopback_or_invalid_ip(client_ip):
                app.tv_beacon.record_authenticated_tv(
                    client_ip,
                    self.headers.get("X-SoarTV-Tv-Port"),
                    self.headers.get("X-SoarTV-Tv-Id"),
                )

            if clean_path == "/api/gaming/sunshine/start":
                ready = app.ensure_sunshine_ready_and_synced()
                self._send_json(
                    200 if ready else 503,
                    {
                        "ok": ready,
                        "sunshineRunning": ready,
                        "sunshinePowerControlReady": app.sunshine_lifecycle.can_control_service(),
                        "sunshineHostUuid": SunshineConfigManager.read_host_uuid(),
                        "sunshineHostName": SunshineConfigManager.read_host_name(),
                    },
                )
                return

            if clean_path == "/api/gaming/sunshine/stop":
                stopped = app.sunshine_lifecycle.stop_sunshine()
                with app._lock:
                    app._cached_sunshine = not stopped
                self._send_json(
                    200 if stopped else 500,
                    {
                        "ok": stopped,
                        "sunshineRunning": not stopped,
                    },
                )
                return

            if clean_path == "/api/gaming/sunshine-pair":
                pin_val = str(req.get("pin", "")).strip()
                dev_name = str(req.get("deviceName") or req.get("name") or "SoarTV").strip() or "SoarTV"
                if not SUNSHINE_PIN_PATTERN.fullmatch(pin_val):
                    self._send_json(400, {"ok": False, "paired": False, "error": "invalid_pin"})
                    return
                app.ensure_sunshine_ready_and_synced()
                paired_ok = SunshineConfigManager.submit_pairing_pin_with_retry(
                    pin_val, device_name=dev_name
                )
                self._send_json(
                    200 if paired_ok else 409,
                    {"ok": paired_ok, "paired": paired_ok},
                )
                return

            if clean_path == "/api/gaming/terminate":
                game_id = str(req.get("gameId", "")).strip()
                reason = str(req.get("reason", "remote_home_exit")).strip()
                res = app.terminate_active_session(game_id=game_id, reason=reason)
                self._send_json(200, res)
                return

            if clean_path == "/api/gaming/wake":
                WindowsSessionController.wake_from_standby()
                self._send_json(
                    200,
                    app.build_status_payload(include_hidden=True, fast=True, authenticated=True),
                )
                return

            if clean_path == "/api/gaming/unlock":
                allowed, retry_after = app.unlock_rate_limiter.check_and_record(client_ip)
                if not allowed:
                    self._send_json(
                        429,
                        {
                            "ok": False,
                            "error": "rate_limited",
                            "retryAfterSeconds": retry_after,
                        },
                        extra_headers={"Retry-After": str(retry_after)},
                    )
                    return

                pin = str(req.get("pin", ""))
                try:
                    Win32InputInjector.validate_pin(pin)
                except ValueError:
                    self._send_json(400, {"ok": False, "unlocked": False, "error": "invalid_pin"})
                    return

                delivered = bool(unlock_fn(pin))
                app.update_runtime_probe()
                status = app.build_status_payload(include_hidden=True, fast=True, authenticated=True)
                status["pinDelivered"] = delivered
                status["unlocked"] = delivered
                self._send_json(200, status)
                return

            if clean_path == "/api/gaming/sync":
                synced = app.refresh_and_sync(include_hidden=True, sync_sunshine=True)
                app.update_runtime_probe()
                status = app.build_status_payload(include_hidden=True, fast=True, authenticated=True)
                status["synced"] = True
                status["syncedCount"] = len(synced)
                self._send_json(200, status)
                return

            if clean_path == "/api/gaming/hide":
                game_id = str(req.get("gameId", ""))
                hidden = bool(req.get("hidden", True))
                app.hidden_mgr.set_hidden(game_id, hidden)
                self._send_json(
                    200,
                    app.build_status_payload(include_hidden=True, fast=True, authenticated=True),
                )
                return

            if clean_path == "/api/gaming/launch":
                game_id = str(req.get("gameId", "steam_bigpicture"))
                pin = str(req.get("pin", ""))
                requested_mode = str(req.get("connectionMode", "auto")).lower()
                raw_approved = req.get("userApproved", False)
                if isinstance(raw_approved, str):
                    user_approved = raw_approved.strip().lower() in ("true", "1")
                else:
                    user_approved = raw_approved is True

                if not user_approved and os.environ.get("SOARTV_ENABLE_REAL_GAME_LAUNCH") != "1":
                    self._send_json(
                        400,
                        {
                            "ok": False,
                            "launched": False,
                            "error": "explicit_user_approval_required",
                            "message": "Launch requires userApproved: true in request body",
                        },
                    )
                    return

                WindowsSessionController.wake_from_standby()
                if pin and WindowsSessionController.is_session_locked():
                    allowed, retry_after = app.unlock_rate_limiter.check_and_record(client_ip)
                    if not allowed:
                        self._send_json(
                            429,
                            {
                                "ok": False,
                                "error": "rate_limited",
                                "retryAfterSeconds": retry_after,
                            },
                            extra_headers={"Retry-After": str(retry_after)},
                        )
                        return
                    try:
                        Win32InputInjector.validate_pin(pin)
                    except ValueError:
                        self._send_json(400, {"ok": False, "launched": False, "error": "invalid_pin"})
                        return
                    unlock_fn(pin)

                app.process_controller.cancel_late_spawn_watch()
                app.steam_idle_shutdown.cancel("new_game_launch")
                delegate_to_moonlight = bool(req.get("delegateLaunchToMoonlight", False))
                prelaunch_before_stream = bool(req.get("prelaunchBeforeStream", False))
                games = app.get_cached_games(include_hidden=True)
                target = next((g for g in games if g.game_id == game_id), games[0])
                actual_hdmi = DisplayModeController.is_external_display_connected()
                active_mode = LaptopGamingAgentApp.resolve_connection_mode(requested_mode, actual_hdmi)
                use_hdmi = active_mode == "hdmi"

                sunshine_uuid = SunshineConfigManager.read_host_uuid()
                sunshine_host = SunshineConfigManager.read_host_name()
                sunshine_app_name = SunshineConfigManager.resolve_sunshine_app_name(target)
                target_launcher = WindowsSessionController.classify_game_launcher(target)

                if not use_hdmi:
                    app.ensure_sunshine_ready_and_synced()

                if not use_hdmi and prelaunch_before_stream:
                    SunshineConfigManager.close_active_app()
                    if target.game_id == "desktop":
                        app.privacy_curtain.stop()
                        launched = True
                    else:
                        app.privacy_curtain.start_for_game(
                            target, use_hdmi=False, prelaunched=True
                        )
                        if target_launcher == "steam":
                            SteamPreparationManager.prepare_for_stream(
                                app.scanner.steam_root, games, prewarm_steam=False
                            )
                        elif target_launcher == "epic":
                            EpicPreparationManager.prepare_for_stream(target)
                        elif target_launcher in ("heroic", "legendary"):
                            HeroicPreparationManager.prepare_for_stream(target)
                        launched = WindowsSessionController.launch_game(
                            target,
                            force_hdmi_only=False,
                            user_approved=user_approved,
                            steam_root=app.scanner.steam_root,
                        )
                    sunshine_app_name = "Desktop"
                    delegated = False
                elif not use_hdmi and delegate_to_moonlight:
                    SunshineConfigManager.close_active_app()
                    SunshineConfigManager.sync_via_api([target])
                    if target.game_id == "desktop":
                        app.privacy_curtain.stop()
                    else:
                        app.privacy_curtain.start_for_game(target, use_hdmi=False)
                    if target_launcher == "steam":
                        SteamPreparationManager.prepare_for_stream(app.scanner.steam_root, games)
                    elif target_launcher == "epic":
                        EpicPreparationManager.prepare_for_stream(target)
                    elif target_launcher in ("heroic", "legendary"):
                        HeroicPreparationManager.prepare_for_stream(target)
                    launched = True
                    delegated = True
                else:
                    app.privacy_curtain.stop()
                    if not use_hdmi and target.game_id == "desktop":
                        launched = True
                    else:
                        launched = WindowsSessionController.launch_game(
                            target, force_hdmi_only=use_hdmi, user_approved=user_approved
                        )
                    delegated = False

                status = app.build_status_payload(include_hidden=True, fast=True, authenticated=True)
                status["status"] = "ok"
                status["hdmiConnected"] = actual_hdmi
                status["launched"] = launched
                status["delegatedToMoonlight"] = delegated
                status["sunshineHostUuid"] = sunshine_uuid
                status["sunshineHostName"] = sunshine_host
                status["sunshineAppName"] = sunshine_app_name
                status["sunshineApp"] = sunshine_app_name
                status["curtainEngaged"] = app.privacy_curtain.is_active()
                status["launchedGame"] = target.to_dict()
                status["activeMode"] = active_mode
                status["userApproved"] = user_approved
                self._send_json(200, status)
                return

            self._send_json(404, {"ok": False, "error": "unknown_endpoint"})

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return

    return ThreadingHTTPServer((host, port), Handler)


def run_server(port: int = DEFAULT_AGENT_PORT) -> None:
    """Starts the Laptop Gaming Agent HTTP server, UDP LAN discovery responder, and Win32 watcher."""
    AgentLogger.configure_console()
    app = LaptopGamingAgentApp()
    app.refresh_and_sync(include_hidden=True, sync_sunshine=True)
    app.update_runtime_probe()

    udp_responder = UdpDiscoveryResponder(host="0.0.0.0", port=port, tcp_port=port)
    udp_responder.start()
    app.tv_beacon.check_and_notify_if_ip_changed(agent_port=port)
    app.supabase_reporter.check_and_report_if_ip_changed(agent_port=port)

    def hdmi_safety_watcher() -> None:
        was_hdmi = app.update_runtime_probe()
        ticks = 0
        while True:
            time.sleep(BACKGROUND_POLL_INTERVAL_SEC)
            try:
                now_hdmi = app.update_runtime_probe()
                if was_hdmi and not now_hdmi:
                    DisplayModeController.apply_display_mode(hdmi_connected=False)
                was_hdmi = now_hdmi
                app.tv_beacon.check_and_notify_if_ip_changed(agent_port=port)
                app.supabase_reporter.check_and_report_if_ip_changed(agent_port=port)
                ticks += 1
                if ticks % CATALOG_REFRESH_EVERY_TICKS == 0:
                    app.refresh_and_sync(include_hidden=True, sync_sunshine=False)
            except Exception as exc:
                AgentLogger.warning("Background watcher iteration warning: %s", type(exc).__name__)

    watcher = threading.Thread(target=hdmi_safety_watcher, daemon=True, name="hdmi-safety-watcher")
    watcher.start()

    server = create_http_server(app=app, host="0.0.0.0", port=port)
    try:
        print(f"SoarTV Laptop Gaming Agent listening on http://0.0.0.0:{port}")
    except OSError as exc:
        AgentLogger.debug("Stdout unavailable in hidden session: %s", type(exc).__name__)
    server.serve_forever()


def _extract_cli_option(argv: Sequence[str], flag: str) -> Optional[str]:
    """Extracts '--flag value' from argv if present."""
    for idx, arg in enumerate(argv):
        if arg == flag and idx + 1 < len(argv):
            return argv[idx + 1]
    return None


def main(argv: Optional[Sequence[str]] = None) -> int:
    """CLI entrypoint supporting daemon server, catalog sync, Winlogon unlock helper, and Wi-Fi wake."""
    args = list(sys.argv[1:] if argv is None else argv)
    ipc_arg = _extract_cli_option(args, "--ipc-dir")
    ipc_dir = Path(ipc_arg) if ipc_arg else None

    if "--unlock-from-stdin" in args:
        injector = Win32InputInjector(ipc_dir=ipc_dir)
        return injector.run_unlock_from_stdin()

    if "--install-unlock-helper" in args:
        AgentLogger.configure_console()
        injector = Win32InputInjector(ipc_dir=ipc_dir)
        res = injector.install_unlock_helper()
        print(json.dumps(res, indent=2))
        return 0 if res.get("ok") else 1

    if "--enable-wifi-wake" in args:
        AgentLogger.configure_console()
        status = WifiWakeConfigurator().enable_wifi_wake()
        print(json.dumps(status.to_dict(), indent=2))
        return 0 if status.ok else 1

    if "--enable-sunshine-power-control" in args:
        AgentLogger.configure_console()
        set_manual = "--manual-start" in args
        res = SunshineLifecycleController.enable_sunshine_power_control(
            set_manual_start=set_manual
        )
        print(json.dumps(res, indent=2))
        return 0 if res.get("ok") else 1

    if "--list-monitors" in args:
        print(json.dumps({"monitors": DisplayModeController.list_connected_displays()}, indent=2))
        return 0

    if "--sync-only" in args:
        AgentLogger.configure_console()
        agent = LaptopGamingAgentApp()
        discovered = agent.refresh_and_sync(include_hidden=True)
        print(f"Synced {len(discovered)} games:")
        for item in discovered:
            print(f" - [{item.category}] {item.title} ({item.game_id}) -> {item.launch_cmd}")
        return 0

    run_server(DEFAULT_AGENT_PORT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
