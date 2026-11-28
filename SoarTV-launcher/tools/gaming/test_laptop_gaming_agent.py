"""
Unit test suite for the SoarTV Laptop Gaming Agent (TDD Red-Green-Refactor).

Why: Verifies that Steam .acf manifests, standalone game folder executables, save-only directory filtering,
Sunshine apps.json synchronization, HDMI 'TV Screen Only' (/external) vs. internal display restoration,
game hide/unhide filtering, RCE-free Win32 SendInput PIN unlock + UIPI fallback, paired SHA-256 token
authentication, 6-digit pairing, sliding-window rate limiting, cross-origin rejection, zero-subprocess
Win32 ctypes probes, and Wi-Fi Wake-on-Magic-Packet configuration work deterministically.
"""

from __future__ import annotations

import ctypes
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
import sys
import unittest
from unittest import mock

_TOOLS_GAMING_DIR = str(Path(__file__).resolve().parent)
if _TOOLS_GAMING_DIR not in sys.path:
    sys.path.insert(0, _TOOLS_GAMING_DIR)

import laptop_gaming_agent as agent_mod
from laptop_gaming_agent import (
    AgentAuthManager,
    CursorPrivacyController,
    DisplayRefreshRateController,
    PCPrivacySafetyNet,
    PhysicalEscAbortListener,
    PrivacyCurtainManager,
    SW_HIDE,
    SW_SHOW,
    SW_MINIMIZE,
    SW_RESTORE,
    VK_ESCAPE,
    DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EMBEDDED,
    DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EXTERNAL,
    DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI,
    DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL,
    DisplayModeController,
    DisplayTargetDescriptor,
    GameCatalogScanner,
    GameEntry,
    HiddenGamesManager,
    KEYEVENTF_KEYUP,
    KEYEVENTF_UNICODE,
    LaptopGamingAgentApp,
    SlidingWindowRateLimiter,
    SunshineConfigManager,
    SYSTEM_POWER_STATUS,
    SystemPowerController,
    VK_RETURN,
    VK_SPACE,
    WIN32_INPUT,
    WifiWakeConfigurator,
    Win32InputInjector,
    Win32SystemProbes,
    WindowsSessionController,
    create_http_server,
    GameProcessController,
    WM_CLOSE,
)

_NATIVE_GUARD_PATCHES: List[Any] = []


def _guard_native_call(orig_fn: Any, name: str) -> Any:
    def _guarded(*args: Any, **kwargs: Any) -> Any:
        if os.environ.get("SOARTV_ALLOW_LIVE_OS_MUTATIONS") == "1" or sys.platform != "win32":
            return orig_fn(*args, **kwargs)
        raise AssertionError(
            f"Unmocked native OS call '{name}' blocked in test_laptop_gaming_agent.py"
        )
    return _guarded


def setUpModule() -> None:
    os.environ["SOARTV_TESTING"] = "1"
    if _NATIVE_GUARD_PATCHES:
        return
    seams = [
        (PCPrivacySafetyNet, "_native_mute_audio", "PCPrivacySafetyNet._native_mute_audio"),
        (PCPrivacySafetyNet, "_native_unmute_audio", "PCPrivacySafetyNet._native_unmute_audio"),
        (PCPrivacySafetyNet, "_native_minimize_open_windows", "PCPrivacySafetyNet._native_minimize_open_windows"),
        (PCPrivacySafetyNet, "_native_restore_minimized_windows", "PCPrivacySafetyNet._native_restore_minimized_windows"),
        (PCPrivacySafetyNet, "_native_hide_desktop", "PCPrivacySafetyNet._native_hide_desktop"),
        (PCPrivacySafetyNet, "_native_restore_desktop", "PCPrivacySafetyNet._native_restore_desktop"),
        (DisplayRefreshRateController, "_native_get_primary_refresh_hz", "DisplayRefreshRateController._native_get_primary_refresh_hz"),
        (DisplayRefreshRateController, "_native_set_primary_refresh_hz", "DisplayRefreshRateController._native_set_primary_refresh_hz"),
        (CursorPrivacyController, "_default_get_cursor_pos", "CursorPrivacyController._default_get_cursor_pos"),
        (CursorPrivacyController, "_default_set_cursor_pos", "CursorPrivacyController._default_set_cursor_pos"),
        (DisplayModeController, "apply_display_mode", "DisplayModeController.apply_display_mode"),
    ]
    for cls, attr, label in seams:
        orig = getattr(cls, attr)
        p = mock.patch.object(cls, attr, side_effect=_guard_native_call(orig, label))
        p.start()
        _NATIVE_GUARD_PATCHES.append(p)


def tearDownModule() -> None:
    while _NATIVE_GUARD_PATCHES:
        p = _NATIVE_GUARD_PATCHES.pop()
        try:
            p.stop()
        except RuntimeError:
            pass


def _make_mock_refresh_controller() -> DisplayRefreshRateController:
    return DisplayRefreshRateController(
        get_primary_refresh_hz_fn=lambda: 60,
        set_primary_refresh_hz_fn=lambda _hz: True,
    )


def _make_mock_cursor_controller() -> CursorPrivacyController:
    return CursorPrivacyController(
        get_cursor_pos_fn=lambda: (500, 500),
        set_cursor_pos_fn=lambda _x, _y: True,
        get_virtual_bounds_fn=lambda: (0, 0, 1920, 1080),
    )


def _make_mock_safety_net() -> PCPrivacySafetyNet:
    return PCPrivacySafetyNet(
        mute_audio_fn=lambda: (True, False),
        unmute_audio_fn=lambda _m: True,
        minimize_windows_fn=lambda: [],
        restore_windows_fn=lambda _h: None,
        hide_desktop_fn=lambda: (True, 0),
        restore_desktop_fn=lambda _v, _h: None,
        refresh_controller=_make_mock_refresh_controller(),
    )


SAMPLE_ACF = '''"AppState"
{
\t"appid"\t\t"728880"
\t"Universe"\t\t"1"
\t"name"\t\t"Overcooked! 2"
\t"StateFlags"\t\t"4"
\t"installdir"\t\t"Overcooked! 2"
}
'''

SAMPLE_VDF_WITHOUT_EXTRA = '''"libraryfolders"
{
\t"0"
\t{
\t\t"path"\t\t"C:\\\\Program Files (x86)\\\\Steam"
\t\t"label"\t\t""
\t\t"apps"
\t\t{
\t\t}
\t}
}
'''


class LaptopGamingAgentTests(unittest.TestCase):
    """Tests game discovery, Sunshine config sync, display switching, security, and Win32 probes."""

    def test_parse_steam_acf_extracts_id_and_title(self) -> None:
        entry = GameCatalogScanner.parse_steam_acf(SAMPLE_ACF)
        self.assertIsNotNone(entry)
        assert entry is not None
        self.assertEqual(entry.game_id, "steam_728880")
        self.assertEqual(entry.title, "Overcooked! 2")
        self.assertEqual(entry.category, "steam")
        self.assertEqual(entry.launch_cmd, "steam://rungameid/728880")

    def test_discover_games_includes_steam_and_standalone_and_skips_save_folders(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            steamapps = root / "steamapps"
            steamapps.mkdir(parents=True)
            (steamapps / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            # Standalone game with real .exe and a crash reporter that should be ignored
            hogwarts = root / "HogwartsLegacy"
            (hogwarts / "Engine" / "Binaries" / "Win64").mkdir(parents=True)
            (hogwarts / "HogwartsLegacy.exe").write_bytes(b"MZ" + b"\x00" * 1024)
            (hogwarts / "Engine" / "Binaries" / "Win64" / "CrashReportClient.exe").write_bytes(
                b"MZ" + b"\x00" * 512
            )

            # Save-only folder (like 'data A Little To The Left') with no .exe
            save_only = root / "data A Little To The Left" / "SteamData"
            save_only.mkdir(parents=True)
            (save_only / "user_stats.ini").write_text("[stats]\n", encoding="utf-8")

            scanner = GameCatalogScanner(games_root=root, steam_root=root / "Steam")
            games = scanner.discover_games()
            ids = [g.game_id for g in games]
            titles = [g.title for g in games]

            self.assertIn("steam_bigpicture", ids)
            self.assertIn("desktop", ids)
            self.assertIn("steam_728880", ids)
            self.assertIn("Overcooked! 2", titles)
            self.assertTrue(any("Hogwarts" in t for t in titles))
            self.assertFalse(any("Little To The Left" in t for t in titles))
            self.assertFalse(any("CrashReportClient" in t for t in titles))

    def test_ensure_steam_library_vdf_links_parent_games_folder(self) -> None:
        updated = GameCatalogScanner.ensure_library_folder_in_vdf(
            SAMPLE_VDF_WITHOUT_EXTRA, r"C:\Games"
        )
        self.assertIn(r'"C:\\Games"', updated)
        # Calling a second time is idempotent
        updated_twice = GameCatalogScanner.ensure_library_folder_in_vdf(updated, r"C:\Games")
        self.assertEqual(updated, updated_twice)

    def test_sunshine_apps_json_merges_without_duplicates(self) -> None:
        games = [
            GameEntry(
                game_id="steam_bigpicture",
                title="Steam Big Picture",
                category="system",
                launch_cmd="steam://open/bigpicture",
                working_dir="",
            ),
            GameEntry(
                game_id="steam_728880",
                title="Overcooked! 2",
                category="steam",
                launch_cmd="steam://rungameid/728880",
                working_dir="",
            ),
            GameEntry(
                game_id="exe_hogwartslegacy",
                title="Hogwarts Legacy",
                category="standalone",
                launch_cmd=r"C:\Games\HogwartsLegacy\HogwartsLegacy.exe",
                working_dir=r"C:\Games\HogwartsLegacy",
            ),
        ]
        existing = {"env": {}, "apps": [{"name": "Desktop", "output": ""}]}
        merged = SunshineConfigManager.merge_apps_config(existing, games)
        app_names = [a["name"] for a in merged["apps"]]
        self.assertIn("Desktop", app_names)
        self.assertIn("Steam Big Picture", app_names)
        self.assertIn("Overcooked! 2", app_names)
        self.assertIn("Hogwarts Legacy", app_names)

        # Merging again should not duplicate entries
        merged_again = SunshineConfigManager.merge_apps_config(merged, games)
        self.assertEqual(len(merged["apps"]), len(merged_again["apps"]))

    def test_display_mode_controller_uses_external_for_hdmi_and_internal_for_restore(self) -> None:
        self.assertEqual(
            DisplayModeController.get_displayswitch_flag(hdmi_connected=True),
            "/external",
        )
        self.assertEqual(
            DisplayModeController.get_displayswitch_flag(hdmi_connected=False),
            "/internal",
        )

    def test_hidden_games_manager_hides_and_unhides_games(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            store_file = Path(tmp) / "hidden_games.json"
            mgr = HiddenGamesManager(store_file)
            games = [
                GameEntry("steam_728880", "Overcooked! 2", "steam", "steam://rungameid/728880", ""),
                GameEntry("exe_wa", "Worms Armageddon", "standalone", r"C:\Games\Worms Armageddon\WA.exe", ""),
            ]

            mgr.set_hidden("exe_wa", True)
            visible_only = mgr.apply_visibility(games, include_hidden=False)
            self.assertEqual(len(visible_only), 1)
            self.assertEqual(visible_only[0].game_id, "steam_728880")

            all_with_flags = mgr.apply_visibility(games, include_hidden=True)
            self.assertEqual(len(all_with_flags), 2)
            self.assertTrue(all_with_flags[1].hidden)

            # Re-show (unhide)
            mgr.set_hidden("exe_wa", False)
            restored = mgr.apply_visibility(games, include_hidden=False)
            self.assertEqual(len(restored), 2)
            self.assertFalse(restored[1].hidden)

    def test_parse_sunshine_credentials_from_keys_text(self) -> None:
        sample = "** Sunshine:\n* username:\ntestuser\n* password:\ntestpass123\n"
        creds = SunshineConfigManager.parse_credentials(sample)
        self.assertEqual(creds, ("testuser", "testpass123"))

    def test_submit_pairing_pin_includes_pending_pairing_id(self) -> None:
        calls = []
        orig_req = SunshineConfigManager._sunshine_api_request

        def fake_api_request(method: str, path: str, payload=None):
            calls.append((method, path, payload))
            if method == "GET" and path == "/api/pin":
                return {"pairings": [{"id": "17aa823b29f62460ff529c4b9fba6a8d", "address": "192.168.1.50"}]}
            if method == "POST" and path == "/api/pin":
                return {"status": True}
            return None

        SunshineConfigManager._sunshine_api_request = classmethod(
            lambda cls, m, p, pl=None: fake_api_request(m, p, pl)
        )  # type: ignore[assignment]
        try:
            ok = SunshineConfigManager.submit_pairing_pin("2671", "Xiaomi MiTV")
            self.assertTrue(ok)
            self.assertEqual(len(calls), 2)
            self.assertEqual(
                calls[1][2],
                {
                    "pin": "2671",
                    "name": "Xiaomi MiTV",
                    "pairing_id": "17aa823b29f62460ff529c4b9fba6a8d",
                },
            )
        finally:
            SunshineConfigManager._sunshine_api_request = orig_req  # type: ignore[assignment]

    def test_resolve_connection_mode_only_uses_hdmi_when_cable_is_actually_connected(self) -> None:
        self.assertEqual(
            LaptopGamingAgentApp.resolve_connection_mode("auto", hdmi_connected=False),
            "wifi",
        )
        self.assertEqual(
            LaptopGamingAgentApp.resolve_connection_mode("hdmi", hdmi_connected=False),
            "wifi",
        )
        self.assertEqual(
            LaptopGamingAgentApp.resolve_connection_mode("auto", hdmi_connected=True),
            "hdmi",
        )
        self.assertEqual(
            LaptopGamingAgentApp.resolve_connection_mode("hdmi", hdmi_connected=True),
            "hdmi",
        )
        self.assertEqual(
            LaptopGamingAgentApp.resolve_connection_mode("wifi", hdmi_connected=True),
            "wifi",
        )

    def test_system_power_status_discharging(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 0
        raw.BatteryFlag = 0
        raw.BatteryLifePercent = 45
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertEqual(p.battery_percent, 45)
            self.assertFalse(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_charging_below_100(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 8
        raw.BatteryLifePercent = 85
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertEqual(p.battery_percent, 85)
            self.assertTrue(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_charging_full_100(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 1
        raw.BatteryLifePercent = 100
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertEqual(p.battery_percent, 100)
            self.assertTrue(p.charging)
            self.assertTrue(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_desktop_no_battery(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 128
        raw.BatteryLifePercent = 255
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertIsNone(p.battery_percent)
            self.assertTrue(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_unknown_battery_percent_255(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 0
        raw.BatteryFlag = 0
        raw.BatteryLifePercent = 255
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertIsNone(p.battery_percent)
            self.assertFalse(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_depleted_battery_zero_percent(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 0
        raw.BatteryFlag = 4
        raw.BatteryLifePercent = 0
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertEqual(p.battery_percent, 0)
            self.assertFalse(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_unknown_battery_flag_255(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 255
        raw.BatteryLifePercent = 50
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertIsNone(p.battery_percent)
            self.assertTrue(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_build_status_payload_includes_battery_fields(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 8
        raw.BatteryLifePercent = 72
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tmp_path = Path(tmp)
                app = LaptopGamingAgentApp(
                    games_root=tmp_path / "Games",
                    steam_root=tmp_path / "Steam",
                    state_dir=tmp_path / "state",
                )
                payload = app.build_status_payload(include_hidden=True, fast=False)
                self.assertEqual(payload["batteryPercent"], 72)
                self.assertTrue(payload["charging"])
                self.assertTrue(payload["batteryCharging"])
                self.assertFalse(payload["full"])
                self.assertFalse(payload["batteryFull"])
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_overflow_percent_above_100(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 1
        raw.BatteryFlag = 1
        raw.BatteryLifePercent = 150
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertIsNone(p.battery_percent)
            self.assertTrue(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    def test_system_power_status_ac_offline_with_charging_flag(self) -> None:
        raw = SYSTEM_POWER_STATUS()
        raw.ACLineStatus = 0
        raw.BatteryFlag = 8
        raw.BatteryLifePercent = 50
        orig = SystemPowerController.read_system_power_status
        SystemPowerController.read_system_power_status = classmethod(lambda cls: raw)  # type: ignore[assignment]
        try:
            p = SystemPowerController.get_power_status()
            self.assertEqual(p.battery_percent, 50)
            self.assertTrue(p.charging)
            self.assertFalse(p.full)
        finally:
            SystemPowerController.read_system_power_status = orig  # type: ignore[assignment]

    # -------------------------------------------------------------------------
    # SEC-03 / BP-07 / BP-08: Strict PIN Validation, Native SendInput, UIPI Helper
    # -------------------------------------------------------------------------

    def test_pin_validation_accepts_alphanumeric_4_to_32_chars(self) -> None:
        self.assertEqual(Win32InputInjector.validate_pin("1234"), "1234")
        self.assertEqual(Win32InputInjector.validate_pin("AbCd9876"), "AbCd9876")
        max_pin = "A1b2C3d4" * 4  # 32 chars
        self.assertEqual(Win32InputInjector.validate_pin(max_pin), max_pin)

    def test_pin_validation_rejects_rce_payloads_and_never_spawns_shell(self) -> None:
        malicious_or_invalid = [
            "",
            "123",
            "A" * 33,
            "'); Start-Process calc.exe; #",
            "1234; whoami",
            "$(calc.exe)",
            "1234`ncalc",
            "1234\n",
            "12 34",
            "1234!",
            "{ENTER}",
        ]
        with mock.patch.object(subprocess, "run", side_effect=AssertionError("subprocess.run called!")), \
             mock.patch.object(subprocess, "Popen", side_effect=AssertionError("subprocess.Popen called!")), \
             mock.patch.object(subprocess, "check_output", side_effect=AssertionError("check_output called!")):
            for bad_pin in malicious_or_invalid:
                with self.assertRaises(ValueError):
                    Win32InputInjector.validate_pin(bad_pin)
                self.assertFalse(WindowsSessionController.unlock_with_pin(bad_pin))

    def test_win32_input_struct_matches_native_abi_size_and_builds_keystrokes(self) -> None:
        expected_size = 40 if ctypes.sizeof(ctypes.c_void_p) == 8 else 28
        self.assertEqual(ctypes.sizeof(WIN32_INPUT), expected_size)

        wake_events, pin_events = Win32InputInjector.build_pin_input_events("Ab99")
        self.assertEqual(len(wake_events), 2)
        self.assertEqual(wake_events[0].ki.wVk, VK_SPACE)
        self.assertEqual(wake_events[0].ki.dwFlags, 0)
        self.assertEqual(wake_events[1].ki.wVk, VK_SPACE)
        self.assertEqual(wake_events[1].ki.dwFlags, KEYEVENTF_KEYUP)

        # 4 unicode chars * 2 (down/up) + VK_RETURN * 2 (down/up) = 10 events
        self.assertEqual(len(pin_events), 10)
        self.assertEqual(pin_events[0].ki.wScan, ord("A"))
        self.assertEqual(pin_events[0].ki.dwFlags, KEYEVENTF_UNICODE)
        self.assertEqual(pin_events[1].ki.wScan, ord("A"))
        self.assertEqual(pin_events[1].ki.dwFlags, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP)
        self.assertEqual(pin_events[-2].ki.wVk, VK_RETURN)
        self.assertEqual(pin_events[-2].ki.dwFlags, 0)
        self.assertEqual(pin_events[-1].ki.wVk, VK_RETURN)
        self.assertEqual(pin_events[-1].ki.dwFlags, KEYEVENTF_UNICODE & 0 | KEYEVENTF_KEYUP)

    def test_uipi_fallback_uses_secure_ipc_inbox_never_cli_args_or_powershell(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ipc_dir = Path(tmp) / "ipc"
            recorded_cmds: List[List[str]] = []

            def fake_send_batch(events: List[WIN32_INPUT]) -> int:
                return 0  # Simulate UIPI blocking SendInput on Windows 11 Winlogon desktop

            def fake_cmd_runner(cmd: List[str], timeout: float) -> subprocess.CompletedProcess[str]:
                recorded_cmds.append(list(cmd))
                # Verify secret PIN NEVER appears in any CLI argument!
                for arg in cmd:
                    self.assertNotIn("Secr3t99", arg)
                    self.assertNotIn("powershell", arg.lower())
                if cmd[0].lower().endswith("schtasks.exe") and "/Run" in cmd:
                    # Simulate the SYSTEM helper consuming the IPC inbox file via run_unlock_from_stdin
                    rc = injector_system.run_unlock_from_stdin(stdin_stream=io.StringIO(""))
                    self.assertEqual(rc, 0)
                    return subprocess.CompletedProcess(cmd, 0, "", "")
                return subprocess.CompletedProcess(cmd, 0, "", "")

            system_batches: List[int] = []
            injector_system = Win32InputInjector(
                ipc_dir=ipc_dir,
                wake_pause_sec=0.01,
                send_batch_fn=lambda evs: (system_batches.append(len(evs)) or len(evs)),
                command_runner=lambda c, t: subprocess.CompletedProcess(c, 0, "", ""),
            )
            injector_user = Win32InputInjector(
                ipc_dir=ipc_dir,
                wake_pause_sec=0.01,
                send_batch_fn=fake_send_batch,
                command_runner=fake_cmd_runner,
            )

            ok = injector_user.inject_pin("Secr3t99", allow_helper_fallback=True)
            self.assertTrue(ok)
            self.assertEqual(system_batches, [2, 18])  # VK_SPACE (2) + 8 chars*2 + VK_RETURN*2 (18)
            # Token file must be wiped after consumption
            self.assertFalse((ipc_dir / "unlock_request.json").exists())
            self.assertTrue(any("SoarTV-Winlogon-Unlock" in " ".join(c) for c in recorded_cmds))

            # Direct stdin mode also works and rejects bad input
            direct_batches: List[int] = []
            stdin_injector = Win32InputInjector(
                ipc_dir=ipc_dir,
                wake_pause_sec=0.01,
                send_batch_fn=lambda evs: (direct_batches.append(len(evs)) or len(evs)),
            )
            self.assertEqual(stdin_injector.run_unlock_from_stdin(stdin_stream=io.StringIO("Pin1234\n")), 0)
            self.assertEqual(direct_batches, [2, 16])
            self.assertNotEqual(
                stdin_injector.run_unlock_from_stdin(stdin_stream=io.StringIO("bad;cmd\n")),
                0,
            )

    # -------------------------------------------------------------------------
    # SEC-04 / SEC-06: SHA-256 Token Auth, Pre-Seeded Config, Pairing, Rate Limit, CORS
    # -------------------------------------------------------------------------

    def test_auth_manager_stores_only_sha256_digests_and_syncs_config_properties(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            auth_file = tmp_dir / "auth.json"
            pair_file = tmp_dir / "pair_code.txt"
            cfg_file = tmp_dir / "config.properties"
            raw_preseeded = "owner_tv_super_secret_token_987654321"
            cfg_file.write_text(
                f"# SoarTV config\ntmdb_key=abc\ngaming_pc_token={raw_preseeded}\n",
                encoding="utf-8",
            )

            mgr = AgentAuthManager(
                auth_path=auth_file,
                pair_code_path=pair_file,
                config_properties_path=cfg_file,
            )
            self.assertTrue(mgr.verify_token(raw_preseeded))
            self.assertFalse(mgr.verify_token("wrong_token"))
            self.assertTrue(mgr.is_request_authenticated({"x-soartv-token": raw_preseeded}))
            self.assertTrue(mgr.is_request_authenticated({"Authorization": f"Bearer {raw_preseeded}"}))

            # Auth JSON on disk must NEVER contain raw token, ONLY its sha256 hex digest
            disk_text = auth_file.read_text(encoding="utf-8")
            self.assertNotIn(raw_preseeded, disk_text)
            expected_hash = hashlib.sha256(raw_preseeded.encode("utf-8")).hexdigest()
            self.assertIn(expected_hash, disk_text)

    def test_sliding_window_rate_limiter_enforces_3_per_ip_and_5_global_per_60s(self) -> None:
        now = [1000.0]
        limiter = SlidingWindowRateLimiter(
            max_per_ip=3,
            max_global=5,
            window_seconds=60.0,
            clock=lambda: now[0],
        )
        # 3 attempts from IP1 succeed
        for _ in range(3):
            allowed, retry = limiter.check_and_record("192.168.1.50")
            self.assertTrue(allowed)
            self.assertEqual(retry, 0)

        # 4th attempt from IP1 within 60s is blocked
        allowed4, retry4 = limiter.check_and_record("192.168.1.50")
        self.assertFalse(allowed4)
        self.assertGreaterEqual(retry4, 1)

        # 2 more attempts from IP2 reach global cap of 5
        self.assertTrue(limiter.check_and_record("192.168.1.51")[0])
        self.assertTrue(limiter.check_and_record("192.168.1.51")[0])

        # 6th attempt within 60s from a brand-new IP3 is blocked by global 5/min cap!
        allowed_global, retry_global = limiter.check_and_record("192.168.1.52")
        self.assertFalse(allowed_global)
        self.assertGreaterEqual(retry_global, 1)

        # Advance past 60s window -> allowed again
        now[0] += 61.0
        self.assertTrue(limiter.check_and_record("192.168.1.50")[0])

    def test_http_server_cors_pairing_auth_and_rate_limiting_contracts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=tmp_dir / "nonexistent_config.properties",
            )
            unlock_calls: List[str] = []
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app.refresh_and_sync(include_hidden=True, sync_sunshine=False)

            server = create_http_server(
                app=app,
                host="127.0.0.1",
                port=0,
                unlock_handler=lambda pin: (unlock_calls.append(pin) is None and True),
            )
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            def request_json(
                method: str,
                path: str,
                body: Optional[Dict[str, Any]] = None,
                headers: Optional[Dict[str, str]] = None,
            ) -> Tuple[int, Dict[str, Any], Dict[str, str]]:
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                try:
                    req_headers = {"Content-Type": "application/json"}
                    if headers:
                        req_headers.update(headers)
                    raw_body = json.dumps(body).encode("utf-8") if body is not None else None
                    conn.request(method, path, body=raw_body, headers=req_headers)
                    resp = conn.getresponse()
                    data = resp.read().decode("utf-8")
                    resp_headers = {k.lower(): v for k, v in resp.getheaders()}
                    return resp.status, (json.loads(data) if data else {}), resp_headers
                finally:
                    conn.close()

            try:
                # 1. Cross-Origin Browser Protection: reject external Origin & cross-site fetch, zero '*'
                st_evil, body_evil, hdr_evil = request_json(
                    "GET",
                    "/api/gaming/status",
                    headers={"Origin": "https://attacker.example"},
                )
                self.assertEqual(st_evil, 403)
                self.assertEqual(body_evil.get("error"), "cross_origin_forbidden")
                self.assertNotEqual(hdr_evil.get("access-control-allow-origin"), "*")

                st_cs, body_cs, _ = request_json(
                    "POST",
                    "/api/gaming/pair/request",
                    body={},
                    headers={"Sec-Fetch-Site": "cross-site"},
                )
                self.assertEqual(st_cs, 403)
                self.assertEqual(body_cs.get("error"), "cross_origin_forbidden")

                st_opt_local, _, hdr_opt_local = request_json(
                    "OPTIONS",
                    "/api/gaming/status",
                    headers={"Origin": f"http://127.0.0.1:{port}"},
                )
                self.assertEqual(st_opt_local, 200)
                self.assertEqual(
                    hdr_opt_local.get("access-control-allow-origin"),
                    f"http://127.0.0.1:{port}",
                )

                # 2. Unauthenticated GET /api/gaming/status and /health return safe read-only status
                for status_path in ("/api/gaming/status", "/health"):
                    st_unauth, body_unauth, hdr_unauth = request_json("GET", status_path)
                    self.assertEqual(st_unauth, 200)
                    self.assertNotIn("access-control-allow-origin", hdr_unauth)
                    self.assertTrue(body_unauth["online"])
                    self.assertFalse(body_unauth["paired"])
                    self.assertTrue(body_unauth["pairingRequired"])
                    self.assertEqual(body_unauth["games"], [])
                    self.assertIn("batteryPercent", body_unauth)
                    self.assertIn("batteryCharging", body_unauth)
                    self.assertIn("hdmiConnected", body_unauth)
                    self.assertIn("sunshineRunning", body_unauth)

                # 3. Protected POST endpoints require X-SoarTV-Token -> 401 Unauthorized
                for protected_path in (
                    "/api/gaming/unlock",
                    "/api/gaming/launch",
                    "/api/gaming/sync",
                    "/api/gaming/hide",
                    "/api/gaming/wake",
                ):
                    st_401, body_401, _ = request_json("POST", protected_path, body={})
                    self.assertEqual(st_401, 401, f"Expected 401 for {protected_path}")
                    self.assertEqual(body_401.get("error"), "unauthorized")
                    self.assertTrue(body_401.get("pairingRequired"))

                # 4. 6-digit pairing request & rate-limited verify
                st_pr, body_pr, _ = request_json("POST", "/api/gaming/pair/request", body={})
                self.assertEqual(st_pr, 200)
                self.assertTrue(body_pr["ok"])
                self.assertEqual(body_pr["expiresInSeconds"], 300)
                self.assertNotIn("code", body_pr)  # Never leak pairing code over HTTP!
                pair_code = (tmp_dir / "pair_code.txt").read_text(encoding="utf-8").strip()
                self.assertRegex(pair_code, r"^\d{6}$")

                # 3 wrong pairing attempts -> 401, 4th -> 429 rate_limited
                wrong_code = "000000" if pair_code != "000000" else "999999"
                for _ in range(3):
                    st_bad, _, _ = request_json(
                        "POST",
                        "/api/gaming/pair/verify",
                        body={"code": wrong_code, "deviceName": "SoarTV"},
                    )
                    self.assertEqual(st_bad, 401)
                st_429, body_429, _ = request_json(
                    "POST",
                    "/api/gaming/pair/verify",
                    body={"code": pair_code, "deviceName": "SoarTV"},
                )
                self.assertEqual(st_429, 429)
                self.assertEqual(body_429.get("error"), "rate_limited")
                self.assertGreater(body_429.get("retryAfterSeconds", 0), 0)

                # Reset pair limiter window for valid pairing verification test
                app.pair_rate_limiter.clear()
                st_pv, body_pv, _ = request_json(
                    "POST",
                    "/api/gaming/pair/verify",
                    body={"code": pair_code, "deviceName": "Xiaomi MiTV"},
                )
                self.assertEqual(st_pv, 200)
                self.assertTrue(body_pv["ok"])
                self.assertTrue(body_pv["paired"])
                token = body_pv["token"]
                self.assertRegex(token, r"^[0-9a-f]{64}$")
                # Pair code file must be deleted after verification
                self.assertFalse((tmp_dir / "pair_code.txt").exists())

                # 5. Authenticated GET /api/gaming/status returns full game list & paired=True
                st_auth, body_auth, _ = request_json(
                    "GET",
                    "/api/gaming/status",
                    headers={"X-SoarTV-Token": token},
                )
                self.assertEqual(st_auth, 200)
                self.assertTrue(body_auth["paired"])
                self.assertFalse(body_auth["pairingRequired"])
                self.assertGreaterEqual(len(body_auth["games"]), 3)

                # 6. Authenticated POST /api/gaming/unlock completes promptly (<0.45s, no 1.2s sleep)
                #    and enforces 3/60s rate limit on 4th unlock call!
                t0 = time.monotonic()
                st_u1, body_u1, _ = request_json(
                    "POST",
                    "/api/gaming/unlock",
                    body={"pin": "Abcd1234"},
                    headers={"x-soartv-token": token},
                )
                elapsed = time.monotonic() - t0
                self.assertEqual(st_u1, 200)
                self.assertTrue(body_u1["ok"])
                self.assertTrue(body_u1["unlocked"])
                self.assertLess(elapsed, 0.45)
                self.assertEqual(unlock_calls, ["Abcd1234"])

                # 2nd and 3rd unlock attempts within 60s
                self.assertEqual(
                    request_json("POST", "/api/gaming/unlock", body={"pin": "Abcd1234"}, headers={"X-SoarTV-Token": token})[0],
                    200,
                )
                self.assertEqual(
                    request_json("POST", "/api/gaming/unlock", body={"pin": "Abcd1234"}, headers={"X-SoarTV-Token": token})[0],
                    200,
                )
                # 4th unlock attempt within 60s -> 429 Too Many Requests!
                st_u4, body_u4, _ = request_json(
                    "POST",
                    "/api/gaming/unlock",
                    body={"pin": "Abcd1234"},
                    headers={"X-SoarTV-Token": token},
                )
                self.assertEqual(st_u4, 429)
                self.assertEqual(body_u4.get("error"), "rate_limited")
            finally:
                server.shutdown()
                server.server_close()

    # -------------------------------------------------------------------------
    # PERF-10: In-Process Win32 ctypes Probes & Critical EDID Display Filtering
    # -------------------------------------------------------------------------

    def test_display_filter_excludes_internal_panel_and_desk_monitor_and_detects_tv_hdmi(self) -> None:
        internal_panel = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#PNL0100#4&130d837c&0&UID265988#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}",
            friendly_name="",
            output_technology=DISPLAYCONFIG_OUTPUT_TECHNOLOGY_INTERNAL,
            target_available=True,
        )
        internal_edp_generic = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#BOE0900#4&130d837c&0&UID1#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}",
            friendly_name="Integrated Monitor",
            output_technology=DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EMBEDDED,
            target_available=True,
        )
        desk_dp = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#MON1234#4&130d837c&0&UID200195#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}",
            friendly_name="DeskMonitor24",
            output_technology=DISPLAYCONFIG_OUTPUT_TECHNOLOGY_DISPLAYPORT_EXTERNAL,
            target_available=True,
        )
        desk_by_name_only = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#UNKNOWN#123",
            friendly_name="Office DeskMonitor24",
            output_technology=DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI,
            target_available=True,
        )
        living_room_tv_hdmi = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#TVX4021#5&2a8b1c&0&UID4096#{e6f07b5f-ee97-4a90-b076-33f57bf4eaa7}",
            friendly_name="Smart TV 4K",
            output_technology=DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI,
            target_available=True,
        )

        with mock.patch.dict(os.environ, {"SOARTV_NON_TV_MONITORS": "MON1234,DeskMonitor24"}):
            # Laptop on desk with internal panel + configured non-TV desk monitors -> MUST be empty!
            self.assertEqual(
                DisplayModeController.filter_external_tv_displays(
                    [internal_panel, internal_edp_generic, desk_dp, desk_by_name_only]
                ),
                [],
            )
            # Plugging in Smart TV over HDMI alongside internal + desk monitor -> MUST detect TV!
            matched = DisplayModeController.filter_external_tv_displays(
                [internal_panel, desk_dp, living_room_tv_hdmi]
            )
            self.assertEqual(matched, [living_room_tv_hdmi])

    def test_runtime_probes_spawn_zero_subprocesses(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
            )
            with mock.patch.object(
                subprocess, "check_output", side_effect=AssertionError("subprocess.check_output forbidden!")
            ), mock.patch.object(
                subprocess, "run", side_effect=AssertionError("subprocess.run forbidden!")
            ), mock.patch.object(
                subprocess, "Popen", side_effect=AssertionError("subprocess.Popen forbidden!")
            ):
                _ = DisplayModeController.is_external_display_connected()
                _ = WindowsSessionController.is_session_locked()
                _ = WindowsSessionController.is_windows_locked()
                _ = WindowsSessionController.is_sunshine_running()
                _ = app.update_runtime_probe()

    def test_wts_session_lock_and_sunshine_service_probes_logic(self) -> None:
        # WTS_SESSIONSTATE_LOCK (0) -> True; WTS_SESSIONSTATE_UNLOCK (1) -> False
        with mock.patch.object(Win32SystemProbes, "_query_wts_session_lock_flag", return_value=0):
            self.assertTrue(Win32SystemProbes.is_session_locked())
        with mock.patch.object(Win32SystemProbes, "_query_wts_session_lock_flag", return_value=1):
            self.assertFalse(Win32SystemProbes.is_session_locked())
        # Fallback to LogonUI / desktop check when WTS returns None
        with mock.patch.object(Win32SystemProbes, "_query_wts_session_lock_flag", return_value=None), \
             mock.patch.object(Win32SystemProbes, "_is_logonui_process_present", return_value=True):
            self.assertTrue(Win32SystemProbes.is_session_locked())

        # Sunshine SCM SERVICE_RUNNING (4) -> True; stopped (1) falls back to TCP 127.0.0.1:47990
        with mock.patch.object(Win32SystemProbes, "_query_service_state", return_value=4):
            self.assertTrue(Win32SystemProbes.is_sunshine_running())
        with mock.patch.object(Win32SystemProbes, "_query_service_state", return_value=1), \
             mock.patch.object(Win32SystemProbes, "_can_connect_local_port", return_value=False):
            self.assertFalse(Win32SystemProbes.is_sunshine_running())
        with mock.patch.object(Win32SystemProbes, "_query_service_state", return_value=None), \
             mock.patch.object(Win32SystemProbes, "_can_connect_local_port", return_value=True):
            self.assertTrue(Win32SystemProbes.is_sunshine_running())

    # -------------------------------------------------------------------------
    # Wireless Wake-on-WLAN Helper (--enable-wifi-wake)
    # -------------------------------------------------------------------------

    def test_wifi_wake_configurator_requires_admin_and_configures_adapter_when_elevated(self) -> None:
        non_admin = WifiWakeConfigurator(is_admin_fn=lambda: False)
        res_denied = non_admin.enable_wifi_wake()
        self.assertFalse(res_denied.ok)
        self.assertFalse(res_denied.is_admin)

        reg_writes: Dict[str, str] = {}
        powercfg_calls: List[List[str]] = []

        def fake_discover() -> Optional[Tuple[str, str]]:
            return (r"0004", "Intel(R) Wireless-AC 9560 160MHz")

        def fake_set_reg(subkey: str, name: str, value: str) -> bool:
            reg_writes[f"{subkey}:{name}"] = value
            return True

        def fake_cmd(args: List[str], timeout: float) -> subprocess.CompletedProcess[str]:
            powercfg_calls.append(list(args))
            if "/devicequery" in args and "wake_armed" in args:
                return subprocess.CompletedProcess(args, 0, "Intel(R) Wireless-AC 9560 160MHz\n", "")
            return subprocess.CompletedProcess(args, 0, "", "")

        admin_cfg = WifiWakeConfigurator(
            is_admin_fn=lambda: True,
            discover_wifi_adapter_fn=fake_discover,
            set_adapter_reg_value_fn=fake_set_reg,
            command_runner=fake_cmd,
        )
        res_ok = admin_cfg.enable_wifi_wake()
        self.assertTrue(res_ok.ok)
        self.assertTrue(res_ok.is_admin)
        self.assertTrue(res_ok.magic_packet_enabled)
        self.assertTrue(res_ok.pattern_match_enabled)
        self.assertTrue(res_ok.powercfg_wake_enabled)
        self.assertEqual(reg_writes["0004:*WakeOnMagicPacket"], "1")
        self.assertEqual(reg_writes["0004:*WakeOnPattern"], "1")
        self.assertIn(
            ["powercfg.exe", "/deviceenablewake", "Intel(R) Wireless-AC 9560 160MHz"],
            powercfg_calls,
        )

    def test_pairing_code_expiry_deletes_code_file_and_rejects_verification(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            fake_now = [5000.0]
            mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=tmp_dir / "missing.properties",
                clock=lambda: fake_now[0],
            )
            res = mgr.request_pairing_code()
            self.assertEqual(res["expiresInSeconds"], 300)
            code = (tmp_dir / "pair_code.txt").read_text(encoding="utf-8").strip()

            # Advance past 300-second TTL
            fake_now[0] += 301.0
            self.assertIsNone(mgr.verify_pairing_code(code, "SoarTV"))
            self.assertFalse((tmp_dir / "pair_code.txt").exists())

    def test_query_active_displays_falls_back_to_gdi_when_ccd_unavailable(self) -> None:
        fallback_target = DisplayTargetDescriptor(
            device_path=r"\\?\DISPLAY#XMI4021#5&2a8b1c&0&UID4096",
            friendly_name="Generic PnP Monitor",
            output_technology=0,
            target_available=True,
        )
        with mock.patch.object(sys_mod := agent_mod.sys, "platform", "win32"), \
             mock.patch.object(Win32SystemProbes, "_query_displays_ccd", return_value=None), \
             mock.patch.object(Win32SystemProbes, "_query_displays_gdi_fallback", return_value=[fallback_target]):
            _ = sys_mod
            displays = Win32SystemProbes.query_active_displays()
            self.assertEqual(displays, [fallback_target])
            self.assertTrue(DisplayModeController.is_external_display_connected())

    def test_authenticated_sync_and_launch_pin_validation_endpoints(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            cfg_file = tmp_dir / "config.properties"
            preseeded = "preseeded_valid_token_abcdef123456"
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            with mock.patch.object(SunshineConfigManager, "sync_to_disk", return_value=None), \
                 mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                 mock.patch.object(WindowsSessionController, "is_session_locked", return_value=True), \
                 mock.patch.object(WindowsSessionController, "launch_game", return_value=True):
                server = create_http_server(app=app, host="127.0.0.1", port=0)
                port = server.server_address[1]
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/sync",
                        body=b"{}",
                        headers={"Content-Type": "application/json", "Authorization": f"Bearer {preseeded}"},
                    )
                    resp = conn.getresponse()
                    sync_body = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    self.assertEqual(resp.status, 200)
                    self.assertTrue(sync_body["synced"])
                    self.assertGreaterEqual(sync_body["syncedCount"], 3)

                    # Invalid RCE PIN inside /api/gaming/launch must be rejected with 400
                    conn2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn2.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps({"gameId": "steam_728880", "pin": "'); calc.exe #", "userApproved": True}).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp2 = conn2.getresponse()
                    launch_err = json.loads(resp2.read().decode("utf-8"))
                    conn2.close()
                    self.assertEqual(resp2.status, 400)
                    self.assertEqual(launch_err["error"], "invalid_pin")
                finally:
                    server.shutdown()
                    server.server_close()

    def test_cli_entrypoints_enable_wifi_wake_and_install_unlock_helper(self) -> None:
        fake_status = agent_mod.WifiWakeStatus(
            ok=True,
            is_admin=True,
            adapter_subkey="0004",
            adapter_description="Intel Wi-Fi",
            magic_packet_enabled=True,
            pattern_match_enabled=True,
            powercfg_wake_enabled=True,
            messages=("ok",),
        )
        with io.StringIO() as buf, mock.patch("sys.stdout", buf), \
             mock.patch.object(agent_mod.AgentLogger, "configure_console"), \
             mock.patch.object(WifiWakeConfigurator, "enable_wifi_wake", return_value=fake_status):
            rc = agent_mod.main(["--enable-wifi-wake"])
            self.assertEqual(rc, 0)
            parsed = json.loads(buf.getvalue())
            self.assertTrue(parsed["ok"])
            self.assertTrue(parsed["magicPacketEnabled"])

        with io.StringIO() as buf2, mock.patch("sys.stdout", buf2), \
             mock.patch.object(agent_mod.AgentLogger, "configure_console"), \
             mock.patch.object(
                 Win32InputInjector,
                 "install_unlock_helper",
                 return_value={"ok": True, "isAdmin": True, "taskName": "SoarTV-Winlogon-Unlock"},
             ):
            rc2 = agent_mod.main(["--install-unlock-helper"])
            self.assertEqual(rc2, 0)
            parsed2 = json.loads(buf2.getvalue())
            self.assertEqual(parsed2["taskName"], "SoarTV-Winlogon-Unlock")

    def test_sunshine_host_uuid_and_app_name_resolution(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state_file = Path(tmp) / "sunshine_state.json"
            state_file.write_text(
                json.dumps({"root": {"uniqueid": "14FEDE7C-47F1-1D07-0185-22930C564015"}}),
                encoding="utf-8",
            )
            self.assertEqual(
                SunshineConfigManager.read_host_uuid(state_file),
                "14FEDE7C-47F1-1D07-0185-22930C564015",
            )
            self.assertEqual(SunshineConfigManager.read_host_uuid(Path(tmp) / "missing.json"), "")

        desktop_entry = GameEntry("desktop", "Windows Desktop", "system", "explorer.exe", "")
        bp_entry = GameEntry("steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", "")
        game_entry = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        self.assertEqual(SunshineConfigManager.resolve_sunshine_app_name(desktop_entry), "Desktop")
        self.assertEqual(SunshineConfigManager.resolve_sunshine_app_name(bp_entry), "Steam Big Picture")
        self.assertEqual(SunshineConfigManager.resolve_sunshine_app_name(game_entry), "Boomerang Fu")

    def test_steam_preparation_suppresses_controller_popup_vdf_and_detects_interstitial_window(self) -> None:
        SteamPreparationManager = agent_mod.SteamPreparationManager
        sample_localconfig = (
            '"UserLocalConfigStore"\n'
            "{\n"
            '\t"Software"\n'
            "\t{\n"
            '\t\t"Valve"\n'
            "\t\t{\n"
            '\t\t\t"Steam"\n'
            "\t\t\t{\n"
            '\t\t\t\t"Deck_ConfiguratorInterstitialApps_GamepadRecommended"\t\t"[965680]"\n'
            "\t\t\t}\n"
            "\t\t}\n"
            "\t}\n"
            "}\n"
        )
        updated = SteamPreparationManager.suppress_controller_interstitial_in_vdf(
            sample_localconfig, [965680, 728880, 448510]
        )
        self.assertIn('"[448510,728880,965680]"', updated)
        self.assertEqual(
            updated,
            SteamPreparationManager.suppress_controller_interstitial_in_vdf(updated, [728880, 965680]),
        )

        fresh_localconfig = (
            '"UserLocalConfigStore"\n'
            "{\n"
            '\t"Steam"\n'
            "\t{\n"
            '\t\t"SmallMode"\t\t"0"\n'
            "\t}\n"
            "}\n"
        )
        inserted = SteamPreparationManager.suppress_controller_interstitial_in_vdf(
            fresh_localconfig, [1054430, 965680]
        )
        self.assertIn('"Deck_ConfiguratorInterstitialApps_GamepadRecommended"\t\t"[965680,1054430]"', inserted)

        self.assertTrue(
            SteamPreparationManager.is_steam_interstitial_popup(
                "steamwebhelper.exe", "SDL_app", "Steam", 717, 500
            )
        )
        self.assertFalse(
            SteamPreparationManager.is_steam_interstitial_popup(
                "steamwebhelper.exe", "SDL_app", "Steam", 1280, 800
            )
        )
        self.assertFalse(
            SteamPreparationManager.is_steam_interstitial_popup(
                "chrome.exe", "Chrome_WidgetWin_1", "Steam", 717, 500
            )
        )

    def test_wifi_launch_delegates_to_moonlight_without_prelaunching_on_pc_speakers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            cfg_props = tmp_dir / "config.properties"
            preseeded = "b" * 64
            cfg_props.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_props,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app.refresh_and_sync(include_hidden=True, sync_sunshine=False)

            with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0) as mock_sync_api, \
                 mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True) as mock_close_app, \
                 mock.patch.object(agent_mod.PrivacyCurtainManager, "start_for_game", return_value=True) as mock_curtain, \
                 mock.patch.object(agent_mod.PrivacyCurtainManager, "stop", return_value=None), \
                 mock.patch.object(
                     SunshineConfigManager,
                     "read_host_uuid",
                     return_value="14FEDE7C-47F1-1D07-0185-22930C564015",
                 ), \
                 mock.patch.object(
                     SunshineConfigManager,
                     "read_host_uuid",
                     return_value="14FEDE7C-47F1-1D07-0185-22930C564015",
                 ), \
                 mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                 mock.patch.object(WindowsSessionController, "is_session_locked", return_value=False), \
                 mock.patch.object(WindowsSessionController, "launch_game", return_value=True) as mock_launch, \
                 mock.patch.object(
                     agent_mod.SteamPreparationManager,
                     "prepare_for_stream",
                     return_value=True,
                 ) as mock_prep:
                server = create_http_server(app=app, host="127.0.0.1", port=0)
                port = server.server_address[1]
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    with mock.patch.object(
                        DisplayModeController, "is_external_display_connected", return_value=False
                    ):
                        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps(
                                {
                                    "gameId": "steam_728880",
                                    "connectionMode": "auto",
                                    "delegateLaunchToMoonlight": True,
                                    "userApproved": True,
                                }
                            ).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                        )
                        resp = conn.getresponse()
                        body = json.loads(resp.read().decode("utf-8"))
                        conn.close()

                        self.assertEqual(resp.status, 200)
                        self.assertEqual(body["activeMode"], "wifi")
                        self.assertTrue(body["delegatedToMoonlight"])
                        self.assertEqual(body["sunshineHostUuid"], "14FEDE7C-47F1-1D07-0185-22930C564015")
                        self.assertEqual(body["sunshineAppName"], "Overcooked! 2")
                        mock_launch.assert_not_called()
                        mock_prep.assert_called_once()
                        mock_sync_api.assert_called()
                        mock_close_app.assert_called_once()
                        mock_curtain.assert_called_once()

                    with mock.patch.object(
                        DisplayModeController, "is_external_display_connected", return_value=True
                    ):
                        conn2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn2.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps(
                                {
                                    "gameId": "steam_728880",
                                    "connectionMode": "auto",
                                    "delegateLaunchToMoonlight": True,
                                    "userApproved": True,
                                }
                            ).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                        )
                        resp2 = conn2.getresponse()
                        body2 = json.loads(resp2.read().decode("utf-8"))
                        conn2.close()

                        self.assertEqual(resp2.status, 200)
                        self.assertEqual(body2["activeMode"], "hdmi")
                        self.assertFalse(body2["delegatedToMoonlight"])
                        mock_launch.assert_called_once()
                finally:
                    server.shutdown()
                    server.server_close()


    def test_sunshine_payload_avoids_broken_steam_uri_cmd_and_upgrades_stale_entries(self) -> None:
        steam_exe = Path(r"C:\Games\Steam\steam.exe")
        steam_game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        bp_game = GameEntry("steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", "")
        exe_game = GameEntry(
            "exe_hogwartslegacy",
            "Hogwarts Legacy",
            "standalone",
            r"C:\Games\HogwartsLegacy\HogwartsLegacy.exe",
            r"C:\Games\HogwartsLegacy",
        )

        steam_payload = SunshineConfigManager.build_sunshine_app_payload(steam_game, steam_exe=steam_exe, index=3)
        self.assertEqual(steam_payload["index"], 3)
        self.assertEqual(steam_payload["cmd"], "")
        self.assertEqual(steam_payload["detached"], [r'"C:\Games\Steam\steam.exe" -applaunch 965680'])
        self.assertFalse(steam_payload["auto-detach"])

        bp_payload = SunshineConfigManager.build_sunshine_app_payload(bp_game, steam_exe=steam_exe, index=1)
        self.assertEqual(bp_payload["cmd"], "")
        self.assertEqual(bp_payload["detached"], [r'"C:\Games\Steam\steam.exe" -bigpicture'])

        exe_payload = SunshineConfigManager.build_sunshine_app_payload(exe_game, steam_exe=steam_exe, index=-1)
        self.assertEqual(exe_payload["cmd"], r'"C:\Games\HogwartsLegacy\HogwartsLegacy.exe"')
        self.assertEqual(exe_payload["working-dir"], r"C:\Games\HogwartsLegacy")
        self.assertEqual(exe_payload["detached"], [])

        posted_payloads: List[Tuple[str, str, Optional[Dict[str, Any]]]] = []

        def fake_api(method: str, path: str, payload: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
            posted_payloads.append((method, path, payload))
            if method == "GET" and path == "/api/apps":
                return {
                    "apps": [
                        {"name": "Desktop", "cmd": ""},
                        {"name": "Steam Big Picture", "detached": ["steam://open/bigpicture"], "cmd": ""},
                        {"name": "Boomerang Fu", "cmd": "steam://rungameid/965680", "detached": []},
                    ]
                }
            return {"status": True}

        with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=fake_api), \
             mock.patch.object(agent_mod.SteamPreparationManager, "resolve_steam_exe", return_value=steam_exe):
            updated_count = SunshineConfigManager.sync_via_api([steam_game, exe_game], steam_root=Path(r"C:\Games\Steam"))
            self.assertEqual(updated_count, 2)
            post_calls = [p for (m, path, p) in posted_payloads if m == "POST" and path == "/api/apps" and p is not None]
            self.assertEqual(len(post_calls), 2)
            by_name = {c["name"]: c for c in post_calls if c is not None}
            self.assertEqual(by_name["Boomerang Fu"]["index"], 2)
            self.assertEqual(by_name["Boomerang Fu"]["cmd"], "")
            self.assertEqual(by_name["Boomerang Fu"]["detached"], [r'"C:\Games\Steam\steam.exe" -applaunch 965680'])
            self.assertEqual(by_name["Hogwarts Legacy"]["index"], -1)

            self.assertTrue(SunshineConfigManager.close_active_app())
            self.assertTrue(SunshineConfigManager.restart_service())
            self.assertTrue(any(m == "POST" and path == "/api/apps/close" for (m, path, _) in posted_payloads))
            self.assertTrue(any(m == "POST" and path == "/api/restart" for (m, path, _) in posted_payloads))

    def test_privacy_curtain_and_cursor_controller_hide_desktop_and_restore_on_disconnect(self) -> None:
        CursorPrivacyController = agent_mod.CursorPrivacyController
        PrivacyCurtainManager = agent_mod.PrivacyCurtainManager

        cursor_pos = [960, 540]
        set_calls: List[Tuple[int, int]] = []
        cursor_ctrl = CursorPrivacyController(
            get_cursor_pos_fn=lambda: (cursor_pos[0], cursor_pos[1]),
            set_cursor_pos_fn=lambda x, y: (
                set_calls.append((x, y)) is None and cursor_pos.__setitem__(slice(None), [x, y]) is None and True
            ),
            get_virtual_bounds_fn=lambda: (0, 0, 3840, 1080),
        )
        self.assertTrue(cursor_ctrl.park_cursor())
        self.assertEqual(cursor_pos, [3839, 1079])
        self.assertTrue(cursor_ctrl.restore_cursor())
        self.assertEqual(cursor_pos, [960, 540])

        target_game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        bp_target = GameEntry("steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", "")
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "chrome.exe", "Chrome_WidgetWin_1", "Gmail - Google Chrome", 1920, 1080, target_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "explorer.exe", "CabinetWClass", "File Explorer", 1200, 800, target_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "steamwebhelper.exe", "SDL_app", "Steam", 1280, 800, target_game
            )
        )
        self.assertTrue(
            PrivacyCurtainManager.is_game_foreground_window(
                "steamwebhelper.exe", "SDL_app", "Steam Big Picture Mode", 1920, 1080, bp_target
            )
        )
        self.assertTrue(
            PrivacyCurtainManager.is_game_foreground_window(
                "BoomerangFu.exe", "UnityWndClass", "Boomerang Fu", 1920, 1080, target_game
            )
        )

        with tempfile.TemporaryDirectory() as tmp:
            log_file = Path(tmp) / "sunshine.log"
            log_file.write_text("[2026-11-05 20:00:00]: Info: Sunshine ready\n", encoding="utf-8")

            z_states: List[Tuple[str, int]] = []
            visible_game_hwnds: List[int] = []
            fallback_launches: List[str] = []

            mgr = PrivacyCurtainManager(
                sunshine_log_path=log_file,
                cursor_controller=cursor_ctrl,
                safety_net=_make_mock_safety_net(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                find_game_hwnd_fn=lambda g: (visible_game_hwnds[0] if visible_game_hwnds else 0),
                apply_curtain_z_order_fn=lambda mode, hwnd: z_states.append((mode, hwnd)),
                fallback_launch_fn=lambda g: (fallback_launches.append(g.game_id) is None and True),
                close_sunshine_app_fn=lambda: True,
                create_native_window=False,
                poll_interval_sec=0.02,
                fallback_spawn_grace_sec=0.05,
                connect_timeout_sec=2.0,
            )

            desktop_entry = GameEntry("desktop", "Windows Desktop", "system", "explorer.exe", "")
            self.assertFalse(mgr.start_for_game(desktop_entry, use_hdmi=False))
            self.assertFalse(mgr.is_active())

            try:
                self.assertTrue(mgr.start_for_game(target_game, use_hdmi=False))
                self.assertTrue(mgr.is_active())
                deadline = time.time() + 1.0
                while ("topmost", 0) not in z_states and time.time() < deadline:
                    time.sleep(0.01)
                self.assertIn(("topmost", 0), z_states)
                self.assertEqual(cursor_pos, [3839, 1079])

                with log_file.open("a", encoding="utf-8") as fh:
                    fh.write("[2026-11-05 20:00:02]: Info: Executing [Desktop]\n")
                    fh.write("[2026-11-05 20:00:02]: Info: CLIENT CONNECTED\n")
                deadline = time.time() + 1.5
                while not fallback_launches and time.time() < deadline:
                    time.sleep(0.02)
                self.assertEqual(fallback_launches, ["steam_965680"])

                visible_game_hwnds.append(4242)
                deadline = time.time() + 1.5
                while ("behind_game", 4242) not in z_states and time.time() < deadline:
                    time.sleep(0.02)
                self.assertIn(("behind_game", 4242), z_states)

                visible_game_hwnds.clear()
                deadline = time.time() + 1.5
                while (not z_states or z_states[-1] != ("topmost", 0)) and time.time() < deadline:
                    time.sleep(0.02)
                self.assertEqual(z_states[-1], ("topmost", 0))

                with log_file.open("a", encoding="utf-8") as fh:
                    fh.write("[2026-11-05 20:05:00]: Info: CLIENT DISCONNECTED\n")
                deadline = time.time() + 1.5
                while mgr.is_active() and time.time() < deadline:
                    time.sleep(0.02)
                self.assertFalse(mgr.is_active())
                self.assertEqual(cursor_pos, [960, 540])
            finally:
                mgr.stop()

    def test_pc_privacy_safety_net_lifecycle_and_idempotency(self) -> None:
        muted_calls: List[str] = []
        minimized_calls: List[str] = []
        desktop_calls: List[str] = []

        def mock_mute() -> Tuple[bool, bool]:
            muted_calls.append("mute")
            return True, False

        def mock_unmute(restore_muted: bool) -> bool:
            muted_calls.append(f"unmute:{restore_muted}")
            return True

        def mock_minimize() -> List[int]:
            minimized_calls.append("minimize")
            return [101, 102, 103]

        def mock_restore_windows(hwnds: Sequence[int]) -> None:
            minimized_calls.append(f"restore:{list(hwnds)}")

        def mock_hide_desktop() -> Tuple[bool, int]:
            desktop_calls.append("hide")
            return True, 5555

        def mock_restore_desktop(was_vis: bool, hwnd: int) -> None:
            desktop_calls.append(f"restore:{was_vis}:{hwnd}")

        net = PCPrivacySafetyNet(
            mute_audio_fn=mock_mute,
            unmute_audio_fn=mock_unmute,
            minimize_windows_fn=mock_minimize,
            restore_windows_fn=mock_restore_windows,
            hide_desktop_fn=mock_hide_desktop,
            restore_desktop_fn=mock_restore_desktop,
            refresh_controller=_make_mock_refresh_controller(),
        )

        self.assertFalse(net.is_engaged())

        # First engage: actions should run
        self.assertTrue(net.engage())
        self.assertTrue(net.is_engaged())
        self.assertEqual(muted_calls, ["mute"])
        self.assertEqual(minimized_calls, ["minimize"])
        self.assertEqual(desktop_calls, ["hide"])

        # Second engage: idempotent, no repeated calls
        self.assertTrue(net.engage())
        self.assertEqual(len(muted_calls), 1)
        self.assertEqual(len(minimized_calls), 1)
        self.assertEqual(len(desktop_calls), 1)

        # First restore: actions should revert
        self.assertTrue(net.restore())
        self.assertFalse(net.is_engaged())
        self.assertEqual(desktop_calls, ["hide", "restore:True:5555"])
        self.assertEqual(minimized_calls, ["minimize", "restore:[101, 102, 103]"])
        self.assertEqual(muted_calls, ["mute", "unmute:False"])

        # Second restore: idempotent, no repeated calls
        self.assertTrue(net.restore())
        self.assertEqual(len(muted_calls), 2)
        self.assertEqual(len(minimized_calls), 2)
        self.assertEqual(len(desktop_calls), 2)

    def test_pc_privacy_safety_net_preserves_audio_if_already_muted(self) -> None:
        unmute_calls: List[bool] = []

        def mock_mute() -> Tuple[bool, bool]:
            return True, True

        def mock_unmute(restore_muted: bool) -> bool:
            unmute_calls.append(restore_muted)
            return True

        net = PCPrivacySafetyNet(
            mute_audio_fn=mock_mute,
            unmute_audio_fn=mock_unmute,
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda _h: None,
            hide_desktop_fn=lambda: (False, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )

        net.engage()
        self.assertTrue(net.is_engaged())
        net.restore()
        self.assertFalse(net.is_engaged())
        self.assertEqual(unmute_calls, [])

    def test_physical_esc_abort_listener_triggers_callback(self) -> None:
        poll_count = 0
        abort_triggered = threading.Event()

        def mock_key_state(vk: int) -> int:
            nonlocal poll_count
            self.assertEqual(vk, VK_ESCAPE)
            poll_count += 1
            if poll_count >= 3:
                return 0x8000
            return 0x0000

        listener = PhysicalEscAbortListener(
            poll_interval_sec=0.01,
            key_state_fn=mock_key_state,
        )

        self.assertFalse(listener.is_running())
        listener.start(on_abort=lambda: abort_triggered.set())
        self.assertTrue(listener.is_running())

        self.assertTrue(abort_triggered.wait(timeout=1.0))
        time.sleep(0.05)
        self.assertFalse(listener.is_running())
        listener.stop()

    def test_privacy_curtain_strict_process_matching(self) -> None:
        standalone_game = GameEntry(
            game_id="hades",
            title="Hades",
            category="standalone",
            launch_cmd=r"C:\Games\Hades\Hades.exe",
            working_dir=r"C:\Games\Hades",
        )

        # Unrelated apps must NEVER match
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "vlc.exe", "Qt5QWindowIcon", "VLC media player", 1280, 720, standalone_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "chrome.exe", "Chrome_WidgetWin_1", "Google Chrome", 1920, 1080, standalone_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "notepad.exe", "Notepad", "Untitled - Notepad", 800, 600, standalone_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "pycharm64.exe", "SunAwtFrame", "PyCharm", 1920, 1080, standalone_game
            )
        )

        # Genuine game executable MUST match
        self.assertTrue(
            PrivacyCurtainManager.is_game_foreground_window(
                "hades.exe", "HadesWindowClass", "Hades", 1920, 1080, standalone_game
            )
        )

        # Steam game strict matching
        steam_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "vlc.exe", "Qt5QWindowIcon", "Overcooked", 1280, 720, steam_game
            )
        )
        self.assertTrue(
            PrivacyCurtainManager.is_game_foreground_window(
                "overcooked2.exe", "UnityWndClass", "Overcooked! 2", 1920, 1080, steam_game
            )
        )

        # Steam Big Picture matching
        bp_game = GameEntry(
            game_id="steam_bigpicture",
            title="Steam Big Picture",
            category="system",
            launch_cmd="steam://open/bigpicture",
            working_dir="",
        )
        self.assertTrue(
            PrivacyCurtainManager.is_game_foreground_window(
                "steam.exe", "CUIEngineWin32", "Steam Big Picture Mode", 1920, 1080, bp_game
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "steam.exe", "vguiPopupWindow", "Steam Friends", 400, 800, bp_game
            )
        )

    def test_privacy_curtain_window_responsiveness_and_focus(self) -> None:
        with mock.patch.dict(os.environ, {"SOARTV_ALLOW_LIVE_OS_MUTATIONS": "1"}), \
             mock.patch("sys.platform", "win32"), \
             mock.patch("ctypes.WinDLL") as mock_windll:
            mock_u32 = mock.MagicMock()
            mock_k32 = mock.MagicMock()

            def dll_selector(name: str, **_kw: Any) -> Any:
                if name == "user32":
                    return mock_u32
                if name == "kernel32":
                    return mock_k32
                return mock.MagicMock()

            mock_windll.side_effect = dll_selector

            # Case 1: Hung window returns False
            mock_u32.IsHungAppWindow.return_value = 1
            self.assertFalse(PrivacyCurtainManager._ensure_game_focused(999))

            # Case 2: Window is already foreground and responsive
            mock_u32.IsHungAppWindow.return_value = 0
            mock_u32.GetForegroundWindow.return_value = 999
            self.assertTrue(PrivacyCurtainManager._ensure_game_focused(999))

    def test_privacy_curtain_esc_abort_cancels_stream_and_restores_pc(self) -> None:
        restored: List[str] = []
        sunshine_closed: List[str] = []

        mock_safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=lambda _m: True,
            minimize_windows_fn=lambda: [100],
            restore_windows_fn=lambda _h: restored.append("windows"),
            hide_desktop_fn=lambda: (True, 200),
            restore_desktop_fn=lambda _v, _h: restored.append("desktop"),
            refresh_controller=_make_mock_refresh_controller(),
        )
        mock_esc = PhysicalEscAbortListener(key_state_fn=lambda _vk: 0)

        game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_log = Path(tmp) / "sunshine.log"
            tmp_log.write_text("[2026-11-05 20:00:00]: Info: Log initialized\n", encoding="utf-8")

            curtain = PrivacyCurtainManager(
                sunshine_log_path=tmp_log,
                cursor_controller=_make_mock_cursor_controller(),
                create_native_window=False,
                safety_net=mock_safety,
                esc_listener=mock_esc,
                connect_timeout_sec=5.0,
            )

            with mock.patch.object(SunshineConfigManager, "close_active_app", side_effect=lambda: sunshine_closed.append("closed")):
                self.assertTrue(curtain.start_for_game(game, use_hdmi=False))
                self.assertTrue(curtain.is_active())
                self.assertTrue(curtain.safety_net.is_engaged())

                curtain._handle_esc_abort()

                self.assertFalse(curtain.is_active())
                self.assertFalse(curtain.safety_net.is_engaged())
                self.assertIn("windows", restored)
                self.assertIn("desktop", restored)
                self.assertEqual(sunshine_closed, ["closed"])

    def test_launch_game_approval_guardrail(self) -> None:
        game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")

        # 1. Without approval and not in test mode: raises PermissionError
        with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "0"}), \
             mock.patch.object(agent_mod, "sys") as mock_sys:
            mock_sys.modules = {}
            mock_sys.platform = "win32"
            with self.assertRaises(PermissionError):
                WindowsSessionController.launch_game(game, user_approved=False)

        # 2. In test mode without SOARTV_ENABLE_REAL_GAME_LAUNCH: returns True as dry-run without subprocess
        with mock.patch("subprocess.Popen") as mock_popen, \
             mock.patch("os.startfile", create=True) as mock_startfile:
            res = WindowsSessionController.launch_game(game, user_approved=False)
            self.assertTrue(res)
            mock_popen.assert_not_called()
            mock_startfile.assert_not_called()

    def test_api_gaming_launch_approval_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            cfg_file = tmp_dir / "config.properties"
            preseeded = "a" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            try:
                # 1. Request WITHOUT userApproved: true must return 400
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request(
                    "POST",
                    "/api/gaming/launch",
                    body=json.dumps({"gameId": "steam_728880", "connectionMode": "auto"}).encode("utf-8"),
                    headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                )
                resp = conn.getresponse()
                err_data = json.loads(resp.read().decode("utf-8"))
                conn.close()
                self.assertEqual(resp.status, 400)
                self.assertEqual(err_data["error"], "explicit_user_approval_required")

                # 2. Request WITH userApproved: true must succeed with 200
                with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "start_for_game", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "is_active", return_value=True), \
                     mock.patch.object(agent_mod.SteamPreparationManager, "prepare_for_stream", return_value=True):
                    conn2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn2.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps({
                            "gameId": "steam_728880",
                            "connectionMode": "auto",
                            "delegateLaunchToMoonlight": True,
                            "userApproved": True,
                        }).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp2 = conn2.getresponse()
                    ok_data = json.loads(resp2.read().decode("utf-8"))
                    conn2.close()
                    self.assertEqual(resp2.status, 200)
                    self.assertEqual(ok_data["status"], "ok")
                    self.assertTrue(ok_data["userApproved"])
                    self.assertTrue(ok_data["curtainEngaged"])
            finally:
                server.shutdown()
                server.server_close()

    def test_safety_net_native_win32_helpers_and_error_handling(self) -> None:
        # Non-win32 platforms return empty/safe defaults
        with mock.patch("sys.platform", "linux"):
            self.assertEqual(PCPrivacySafetyNet._native_mute_audio(), (False, False))
            self.assertFalse(PCPrivacySafetyNet._native_unmute_audio(False))
            self.assertEqual(PCPrivacySafetyNet._native_minimize_open_windows(), [])
            self.assertEqual(PCPrivacySafetyNet._find_defview_hwnd(), 0)
            self.assertEqual(PCPrivacySafetyNet._native_hide_desktop(), (False, 0))

        # Test native minimization and restoration with mock Win32 user32
        with mock.patch.dict(os.environ, {"SOARTV_ALLOW_LIVE_OS_MUTATIONS": "1"}), \
             mock.patch("sys.platform", "win32"), \
             mock.patch("ctypes.WinDLL") as mock_windll:
            mock_u32 = mock.MagicMock()
            mock_windll.return_value = mock_u32

            def fake_enum_windows(cb: Any, _lparam: Any) -> int:
                cb(101, 0)
                cb(102, 0)
                return 1

            mock_u32.EnumWindows.side_effect = fake_enum_windows
            mock_u32.IsWindowVisible.return_value = 1
            mock_u32.IsIconic.return_value = 0
            mock_u32.IsWindow.return_value = 1

            hwnds = PCPrivacySafetyNet._native_minimize_open_windows()
            self.assertEqual(hwnds, [101, 102])
            self.assertEqual(mock_u32.ShowWindow.call_count, 2)

            mock_u32.ShowWindow.reset_mock()
            mock_u32.IsIconic.return_value = 1
            PCPrivacySafetyNet._native_restore_minimized_windows([101, 102])
            self.assertEqual(mock_u32.ShowWindow.call_count, 2)

    def test_resolve_expected_process_names_edge_cases(self) -> None:
        # 1. None or desktop
        self.assertEqual(PrivacyCurtainManager.resolve_expected_process_names(None), set())
        desktop = GameEntry("desktop", "Windows Desktop", "system", "explorer.exe", "")
        self.assertEqual(PrivacyCurtainManager.resolve_expected_process_names(desktop), set())

        # 2. Steam Big Picture
        bp = GameEntry("steam_bigpicture", "Steam Big Picture", "system", "steam://open/bigpicture", "")
        self.assertEqual(
            PrivacyCurtainManager.resolve_expected_process_names(bp),
            {"steam.exe", "steamwebhelper.exe"},
        )

        # 3. Standalone game with quoted executable path
        quoted_game = GameEntry(
            "custom_game",
            "Custom Game",
            "standalone",
            r'"C:\Games\Custom Folder\CustomGame.exe" --fullscreen',
            r"C:\Games\Custom Folder",
        )
        names = PrivacyCurtainManager.resolve_expected_process_names(quoted_game)
        self.assertIn("customgame.exe", names)

        # 4. Known override: Hogwarts Legacy
        hl_game = GameEntry(
            "hogwartslegacy",
            "Hogwarts Legacy",
            "standalone",
            r"C:\Games\HogwartsLegacy\HogwartsLegacy.exe",
            r"C:\Games\HogwartsLegacy",
        )
        hl_names = PrivacyCurtainManager.resolve_expected_process_names(hl_game)
        self.assertIn("hogwartslegacy.exe", hl_names)

    def test_physical_esc_abort_listener_idempotency_and_error_resilience(self) -> None:
        listener = PhysicalEscAbortListener(poll_interval_sec=0.01, key_state_fn=lambda _vk: 0)

        # Stopping when not started is safe
        listener.stop()
        self.assertFalse(listener.is_running())

        # Starting twice is idempotent
        listener.start(on_abort=lambda: None)
        self.assertTrue(listener.is_running())
        listener.start(on_abort=lambda: None)
        self.assertTrue(listener.is_running())
        listener.stop()
        self.assertFalse(listener.is_running())

    def test_pc_privacy_safety_net_concurrency_race_condition(self) -> None:
        """Verifies holding self._lock across engage() prevents concurrent restore() from leaving audio muted or leaking HWNDs."""
        system_audio_is_muted = False
        restored_hwnds: List[int] = []

        def slow_mute() -> Tuple[bool, bool]:
            nonlocal system_audio_is_muted
            time.sleep(0.05)
            system_audio_is_muted = True
            return True, False

        def do_unmute(was_muted: bool) -> bool:
            nonlocal system_audio_is_muted
            system_audio_is_muted = False
            return True

        def slow_minimize() -> List[int]:
            time.sleep(0.05)
            return [101, 102]

        def do_restore_windows(hwnds: Sequence[int]) -> None:
            restored_hwnds.extend(hwnds)

        net = PCPrivacySafetyNet(
            mute_audio_fn=slow_mute,
            unmute_audio_fn=do_unmute,
            minimize_windows_fn=slow_minimize,
            restore_windows_fn=do_restore_windows,
            hide_desktop_fn=lambda: (False, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )

        t1 = threading.Thread(target=net.engage)
        t1.start()
        time.sleep(0.01)

        t2 = threading.Thread(target=net.restore)
        t2.start()

        t1.join()
        t2.join()

        self.assertFalse(system_audio_is_muted, "Audio must not remain muted after concurrent engage/restore")
        self.assertFalse(net.is_engaged())
        self.assertEqual(restored_hwnds, [101, 102])
        self.assertEqual(net._minimized_hwnds, [])

    def test_pc_privacy_safety_net_isolated_restore_error_handling_and_retry(self) -> None:
        """Verifies isolated try blocks in restore() ensure window and audio restore execute even if desktop restore fails."""
        audio_restored = False
        windows_restored: List[int] = []
        fail_desktop = True

        def succeed_audio(was_muted: bool) -> bool:
            nonlocal audio_restored
            audio_restored = True
            return True

        def sometimes_fail_desktop(was_vis: bool, hwnd: int) -> None:
            nonlocal fail_desktop
            if fail_desktop:
                fail_desktop = False
                raise RuntimeError("Transient desktop COM glitch")

        def do_restore_windows(hwnds: Sequence[int]) -> None:
            windows_restored.extend(hwnds)

        net = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=succeed_audio,
            minimize_windows_fn=lambda: [101, 102],
            restore_windows_fn=do_restore_windows,
            hide_desktop_fn=lambda: (True, 1234),
            restore_desktop_fn=sometimes_fail_desktop,
            refresh_controller=_make_mock_refresh_controller(),
        )

        self.assertTrue(net.engage())

        # First restore: desktop fails, but windows and audio MUST still be restored
        res1 = net.restore()
        self.assertFalse(res1)
        self.assertTrue(audio_restored)
        self.assertEqual(windows_restored, [101, 102])
        self.assertTrue(net.is_engaged(), "State remains engaged for retry when a step fails")
        self.assertTrue(net._desktop_was_visible, "Desktop visibility state preserved for retry")
        self.assertEqual(net._defview_hwnd, 1234)
        self.assertEqual(net._minimized_hwnds, [], "Successfully restored windows cleared")
        self.assertFalse(net._audio_muted_by_us, "Successfully restored audio cleared")

        # Second restore: retry succeeds completely
        res2 = net.restore()
        self.assertTrue(res2)
        self.assertFalse(net.is_engaged())
        self.assertFalse(net._desktop_was_visible)
        self.assertEqual(net._defview_hwnd, 0)

    def test_pc_privacy_safety_net_partial_engage_rollback_resilience(self) -> None:
        """Verifies that an exception in engage() resets _engaged to False and rolls back already-engaged steps."""
        audio_is_muted = False
        minimized_count = 0
        fail_minimize = True

        def do_mute() -> Tuple[bool, bool]:
            nonlocal audio_is_muted
            audio_is_muted = True
            return True, False

        def do_unmute(was_muted: bool) -> bool:
            nonlocal audio_is_muted
            audio_is_muted = False
            return True

        def sometimes_fail_minimize() -> List[int]:
            nonlocal fail_minimize, minimized_count
            if fail_minimize:
                fail_minimize = False
                raise RuntimeError("Minimization failed!")
            minimized_count += 1
            return [201, 202]

        net = PCPrivacySafetyNet(
            mute_audio_fn=do_mute,
            unmute_audio_fn=do_unmute,
            minimize_windows_fn=sometimes_fail_minimize,
            restore_windows_fn=lambda _h: None,
            hide_desktop_fn=lambda: (False, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )

        # First engage fails during minimization
        res1 = net.engage()
        self.assertFalse(res1)
        self.assertFalse(net.is_engaged(), "_engaged must be reset to False on failure")
        self.assertFalse(audio_is_muted, "Audio mute must be rolled back on engage failure")
        self.assertFalse(net._audio_muted_by_us)

        # Second engage succeeds completely
        res2 = net.engage()
        self.assertTrue(res2)
        self.assertTrue(net.is_engaged())
        self.assertTrue(audio_is_muted)
        self.assertEqual(minimized_count, 1)
        self.assertEqual(net._minimized_hwnds, [201, 202])

        # Cleanup
        self.assertTrue(net.restore())
        self.assertFalse(audio_is_muted)

    def test_api_gaming_launch_strict_user_approved_type_validation(self) -> None:
        """Verifies /api/gaming/launch strictly rejects 'false', '0', False, 0, None and accepts 'true', '1', True."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            cfg_file = tmp_dir / "config.properties"
            token = "b" * 64
            cfg_file.write_text(f"gaming_pc_token={token}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            try:
                # 1. Invalid / unapproved falsy values MUST return 400
                for bad_val in ("false", "FALSE", " 0 ", "0", False, 0, None):
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps({"gameId": "steam_728880", "userApproved": bad_val}).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                    )
                    resp = conn.getresponse()
                    data = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    self.assertEqual(resp.status, 400, f"Expected 400 for userApproved={bad_val}")
                    self.assertEqual(data["error"], "explicit_user_approval_required")

                # Missing userApproved field MUST return 400
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request(
                    "POST",
                    "/api/gaming/launch",
                    body=json.dumps({"gameId": "steam_728880"}).encode("utf-8"),
                    headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                )
                resp = conn.getresponse()
                data = json.loads(resp.read().decode("utf-8"))
                conn.close()
                self.assertEqual(resp.status, 400)
                self.assertEqual(data["error"], "explicit_user_approval_required")

                # 2. Valid truthy approvals ('true', '1', True) MUST succeed with 200
                with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "start_for_game", return_value=True), \
                     mock.patch.object(app.privacy_curtain, "is_active", return_value=True), \
                     mock.patch.object(agent_mod.SteamPreparationManager, "prepare_for_stream", return_value=True):
                    for good_val in (True, "true", "TRUE", "1", " 1 "):
                        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps({
                                "gameId": "steam_728880",
                                "delegateLaunchToMoonlight": True,
                                "userApproved": good_val,
                            }).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                        )
                        resp = conn.getresponse()
                        data = json.loads(resp.read().decode("utf-8"))
                        conn.close()
                        self.assertEqual(resp.status, 200, f"Expected 200 for userApproved={good_val}")
                        self.assertEqual(data["status"], "ok")
                        self.assertTrue(data["userApproved"])
            finally:
                server.shutdown()
                server.server_close()

    def test_privacy_curtain_non_game_process_names_includes_vlc_and_pycharm(self) -> None:
        """Verifies vlc.exe and pycharm64.exe are in NON_GAME_PROCESS_NAMES and rejected when target_game=None."""
        self.assertIn("vlc.exe", PrivacyCurtainManager.NON_GAME_PROCESS_NAMES)
        self.assertIn("pycharm64.exe", PrivacyCurtainManager.NON_GAME_PROCESS_NAMES)

        # When target_game is None, non-game processes must return False
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "vlc.exe", "Qt5QWindowIcon", "VLC media player", 1920, 1080, target_game=None
            )
        )
        self.assertFalse(
            PrivacyCurtainManager.is_game_foreground_window(
                "pycharm64.exe", "SunAwtFrame", "PyCharm 2024.1", 1920, 1080, target_game=None
            )
        )


class LaptopGamingTerminationTests(unittest.TestCase):
    """
    Unit and integration tests for Milestone 3: Game Exit Lifecycle & TV Launcher Integration.
    Verifies POST /api/gaming/terminate endpoint, Toolhelp32 process tree discovery, WM_CLOSE graceful
    exit, TerminateProcess fallback, autonomous in-game quit detection, Sunshine disconnect grace period,
    transient window recreation guard, and strict mute-state volume preservation.
    """

    def test_terminate_endpoint_unauthorized_rejection(self) -> None:
        """Verifies POST /api/gaming/terminate rejects unauthenticated requests with HTTP 401."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            cfg_file.write_text("gaming_pc_token=valid_token_12345\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            t = threading.Thread(target=server.serve_forever, daemon=True)
            t.start()
            try:
                # 1. No token header -> 401
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request(
                    "POST",
                    "/api/gaming/terminate",
                    body=json.dumps({"gameId": "steam_728880", "reason": "remote_home_exit"}).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                )
                resp = conn.getresponse()
                data = json.loads(resp.read().decode("utf-8"))
                conn.close()
                self.assertEqual(resp.status, 401)
                self.assertFalse(data.get("ok"))
                self.assertEqual(data.get("error"), "unauthorized")
                self.assertTrue(data.get("pairingRequired"))

                # 2. Invalid token header -> 401
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request(
                    "POST",
                    "/api/gaming/terminate",
                    body=json.dumps({"gameId": "steam_728880", "reason": "remote_home_exit"}).encode("utf-8"),
                    headers={"Content-Type": "application/json", "X-SoarTV-Token": "bad_token"},
                )
                resp = conn.getresponse()
                data = json.loads(resp.read().decode("utf-8"))
                conn.close()
                self.assertEqual(resp.status, 401)
                self.assertFalse(data.get("ok"))
            finally:
                server.shutdown()
                server.server_close()

    def test_terminate_endpoint_success_flow(self) -> None:
        """Verifies POST /api/gaming/terminate with valid token returns HTTP 200 with terminated=True and pcRestored=True."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            token = "secret_owner_token_9999"
            cfg_file = tmp_dir / "config.properties"
            cfg_file.write_text(f"gaming_pc_token={token}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            t = threading.Thread(target=server.serve_forever, daemon=True)
            t.start()
            try:
                mock_res = {
                    "ok": True,
                    "status": "ok",
                    "terminated": True,
                    "pidsTerminated": [1234, 1235],
                    "forced": False,
                    "pcRestored": True,
                    "reason": "remote_home_exit",
                }
                with mock.patch.object(app.process_controller, "terminate_game", return_value=mock_res):
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/terminate",
                        body=json.dumps({"gameId": "steam_728880", "reason": "remote_home_exit"}).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                    )
                    resp = conn.getresponse()
                    data = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    self.assertEqual(resp.status, 200)
                    self.assertTrue(data.get("ok"))
                    self.assertEqual(data.get("status"), "ok")
                    self.assertTrue(data.get("terminated"))
                    self.assertTrue(data.get("pcRestored"))
                    self.assertEqual(data.get("reason"), "remote_home_exit")
            finally:
                server.shutdown()
                server.server_close()

    def test_wm_close_graceful_exit_without_forced_kill(self) -> None:
        """Verifies game exiting cleanly upon WM_CLOSE does not invoke TerminateProcess."""
        post_messages: List[Tuple[int, int, int, int]] = []
        term_calls: List[int] = []
        close_sunshine_called = []
        restore_called = []

        alive_sequence = [True, False]

        def mock_is_alive(p: int) -> bool:
            return alive_sequence.pop(0) if alive_sequence else False

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda hwnds: restore_called.append("windows"),
            unmute_audio_fn=lambda was_muted: restore_called.append("audio"),
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )

        controller = GameProcessController(
            safety_net=safety,
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=mock_is_alive,
            snapshot_procs_fn=lambda: [(5000, 1, "game.exe")],
            find_hwnds_fn=lambda pids: [1001],
            close_sunshine_fn=lambda: close_sunshine_called.append(True) or True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=1.0)
        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertFalse(res["forced"])
        self.assertEqual(post_messages, [(1001, WM_CLOSE, 0, 0)])
        self.assertEqual(term_calls, [])
        self.assertEqual(len(close_sunshine_called), 1)
        self.assertTrue(res["pcRestored"])

    def test_terminate_process_fallback_on_hung_process(self) -> None:
        """Verifies hung process surviving grace period triggers TerminateProcess fallback."""
        post_messages: List[Tuple[int, int, int, int]] = []
        term_calls: List[int] = []
        close_sunshine_called = []

        controller = GameProcessController(
            post_message_fn=lambda h, m, w, l: post_messages.append((h, m, w, l)) or True,
            terminate_process_fn=lambda p: term_calls.append(p) or True,
            is_pid_alive_fn=lambda p: True,
            snapshot_procs_fn=lambda: [(5000, 1, "hung_game.exe")],
            find_hwnds_fn=lambda pids: [2002],
            close_sunshine_fn=lambda: close_sunshine_called.append(True) or True,
            poll_interval_sec=0.01,
        )

        res = controller.terminate_game(explicit_pid=5000, grace_period_sec=0.04)
        self.assertTrue(res["ok"])
        self.assertTrue(res["terminated"])
        self.assertTrue(res["forced"])
        self.assertEqual(post_messages, [(2002, WM_CLOSE, 0, 0)])
        self.assertEqual(term_calls, [5000])
        self.assertEqual(len(close_sunshine_called), 1)

    def test_process_tree_descendant_traversal(self) -> None:
        """Verifies recursive process tree traversal finds child and grandchild PIDs, strictly excluding parents."""
        procs = [
            (100, 1, "steam.exe"),
            (200, 100, "game.exe"),
            (201, 200, "game_helper.exe"),
            (202, 201, "crash_reporter.exe"),
            (300, 1, "discord.exe"),
        ]
        tree = GameProcessController.get_process_tree(200, entries=procs)
        self.assertEqual(tree, {200, 201, 202})
        self.assertNotIn(100, tree)
        self.assertNotIn(300, tree)

        single_tree = GameProcessController.get_process_tree(300, entries=procs)
        self.assertEqual(single_tree, {300})

        empty_tree = GameProcessController.get_process_tree(999, entries=procs)
        self.assertEqual(empty_tree, {999})

    def test_idempotent_termination_when_no_game_active(self) -> None:
        """Verifies terminate_game when no game is active returns cleanly with zero errors."""
        close_sunshine_called = []
        controller = GameProcessController(
            snapshot_procs_fn=lambda: [],
            find_hwnds_fn=lambda pids: [],
            close_sunshine_fn=lambda: close_sunshine_called.append(True) or True,
        )
        res = controller.terminate_game(target_game=None, explicit_pid=0)
        self.assertTrue(res["ok"])
        self.assertFalse(res["terminated"])
        self.assertEqual(res["pidsTerminated"], [])
        self.assertFalse(res["forced"])
        self.assertTrue(res["pcRestored"])
        self.assertEqual(len(close_sunshine_called), 1)

    def test_autonomous_exit_detection_triggers_sunshine_close(self) -> None:
        """Verifies PrivacyCurtainManager detects game window closed and PID dead, triggering Sunshine close and PC restore."""
        hwnd_sequence = [1001, 0, 0, 0]
        snapshot_sequence = [
            {5000: "overcooked2.exe"},
            {},
            {},
        ]
        close_sunshine_calls = []
        safety_restored = []

        fake_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda hwnds: safety_restored.append("windows"),
            unmute_audio_fn=lambda was_muted: safety_restored.append("audio"),
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )

        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=lambda g: hwnd_sequence.pop(0) if hwnd_sequence else 0,
            get_hwnd_pid_fn=lambda h: 5000,
            process_snapshot_fn=lambda: snapshot_sequence.pop(0) if snapshot_sequence else {},
            close_sunshine_app_fn=lambda: close_sunshine_calls.append(True) or True,
            create_native_window=False,
            poll_interval_sec=0.02,
            safety_net=safety,
        )

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("CLIENT CONNECTED\n")
            f.flush()
            log_path = Path(f.name)

        try:
            curtain._explicit_log_path = log_path
            curtain.start_for_game(fake_game, use_hdmi=False)
            deadline = time.monotonic() + 2.0
            while (curtain.is_active() or len(close_sunshine_calls) == 0) and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertEqual(len(close_sunshine_calls), 1)
            self.assertFalse(curtain.is_active())
            self.assertFalse(safety.is_engaged())
        finally:
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_autonomous_exit_ignores_transient_window_recreation_if_pid_active(self) -> None:
        """Verifies temporary window recreation (e.g. resolution change) does not trigger exit while PID is active."""
        hwnd_sequence = [1001, 0, 1002, 1002, 1002]
        close_sunshine_calls = []
        z_orders = []

        fake_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )

        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=_make_mock_safety_net(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=lambda g: hwnd_sequence.pop(0) if hwnd_sequence else 1002,
            get_hwnd_pid_fn=lambda h: 5000,
            process_snapshot_fn=lambda: {5000: "overcooked2.exe"},
            close_sunshine_app_fn=lambda: close_sunshine_calls.append(True) or True,
            apply_curtain_z_order_fn=lambda mode, hwnd: z_orders.append((mode, hwnd)),
            create_native_window=False,
            poll_interval_sec=0.02,
        )

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("CLIENT CONNECTED\n")
            f.flush()
            log_path = Path(f.name)

        try:
            curtain._explicit_log_path = log_path
            curtain.start_for_game(fake_game, use_hdmi=False)
            time.sleep(0.12)
            self.assertEqual(len(close_sunshine_calls), 0)
            self.assertTrue(curtain.is_active())
            self.assertIn(("topmost", 0), z_orders)
            self.assertIn(("behind_game", 1002), z_orders)
        finally:
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_sunshine_client_disconnect_grace_period_reconnection_cancels_teardown(self) -> None:
        """Verifies CLIENT DISCONNECTED followed by CLIENT CONNECTED within grace period cancels teardown."""
        close_sunshine_calls = []
        fake_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )

        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=_make_mock_safety_net(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=lambda g: 1001,
            get_hwnd_pid_fn=lambda h: 5000,
            process_snapshot_fn=lambda: {5000: "overcooked2.exe"},
            close_sunshine_app_fn=lambda: close_sunshine_calls.append(True) or True,
            disconnect_grace_sec=0.25,
            create_native_window=False,
            poll_interval_sec=0.02,
        )

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path = Path(f.name)

        try:
            curtain._explicit_log_path = log_path
            curtain.start_for_game(fake_game, use_hdmi=False)
            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
            time.sleep(0.04)

            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT DISCONNECTED\n")
            time.sleep(0.06)

            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
            time.sleep(0.25)

            self.assertEqual(len(close_sunshine_calls), 0)
            self.assertTrue(curtain.is_active())
        finally:
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_sunshine_client_disconnect_grace_period_expiration_triggers_clean_game_exit(self) -> None:
        """Verifies CLIENT DISCONNECTED without reconnection terminates game and restores PC when grace expires."""
        close_sunshine_calls = []
        fake_game = GameEntry(
            game_id="steam_728880",
            title="Overcooked! 2",
            category="steam",
            launch_cmd="steam://rungameid/728880",
            working_dir="",
        )

        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=_make_mock_safety_net(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=lambda g: 1001,
            get_hwnd_pid_fn=lambda h: 5000,
            process_snapshot_fn=lambda: {5000: "overcooked2.exe"},
            close_sunshine_app_fn=lambda: close_sunshine_calls.append(True) or True,
            disconnect_grace_sec=0.05,
            create_native_window=False,
            poll_interval_sec=0.01,
        )

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            log_path = Path(f.name)

        try:
            curtain._explicit_log_path = log_path
            curtain.start_for_game(fake_game, use_hdmi=False)
            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT CONNECTED\n")
            time.sleep(0.03)

            with log_path.open("a", encoding="utf-8") as f:
                f.write("CLIENT DISCONNECTED\n")

            deadline = time.monotonic() + 2.0
            while (curtain.is_active() or len(close_sunshine_calls) == 0) and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertEqual(len(close_sunshine_calls), 1)
            self.assertFalse(curtain.is_active())
        finally:
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_audio_mute_state_preservation_user_rule(self) -> None:
        """
        CRITICAL USER RULE: NEVER increase volume scalar/steps. Mute/unmute ONLY.
        Verifies that if was_muted was True before session, PC remains muted on exit.
        Verifies that if was_muted was False, PC restores unmuted.
        Verifies volume scalar is never touched (0 scalar volume adjustments).
        """
        # Case A: User was ALREADY muted before gaming session
        unmute_calls_a: List[bool] = []
        safety_a = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, True),
            unmute_audio_fn=lambda restore_muted: unmute_calls_a.append(restore_muted) or True,
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _v, _h: None,
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda hwnds: None,
            refresh_controller=_make_mock_refresh_controller(),
        )
        safety_a.engage()
        self.assertFalse(safety_a._audio_muted_by_us)
        self.assertTrue(safety_a._was_muted)
        safety_a.restore()
        self.assertEqual(len(unmute_calls_a), 0)

        # Case B: User was UNMUTED before gaming session
        unmute_calls_b: List[bool] = []
        safety_b = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=lambda restore_muted: unmute_calls_b.append(restore_muted) or True,
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _v, _h: None,
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda hwnds: None,
            refresh_controller=_make_mock_refresh_controller(),
        )
        safety_b.engage()
        self.assertTrue(safety_b._audio_muted_by_us)
        self.assertFalse(safety_b._was_muted)
        safety_b.restore()
        self.assertEqual(unmute_calls_b, [False])


    def test_auth_manager_hot_reloads_token_from_config_and_dotenv_on_cache_miss(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            cfg_path = base / "config.properties"
            env_path = base / ".env"
            cfg_path.write_text("# initially empty\n", encoding="utf-8")
            env_path.write_text("# initially empty\n", encoding="utf-8")

            auth = AgentAuthManager(
                auth_path=base / "auth.json",
                pair_code_path=base / "pair_code.txt",
                config_properties_path=cfg_path,
                dotenv_path=env_path,
            )
            token_cfg = "c" * 64
            self.assertFalse(auth.verify_token(token_cfg))

            cfg_path.write_text(f"gaming_pc_token={token_cfg}\n", encoding="utf-8")
            self.assertTrue(auth.verify_token(token_cfg))

            token_env = "d" * 64
            env_path.write_text(f"GAMING_PC_TOKEN={token_env}\n", encoding="utf-8")
            self.assertTrue(auth.verify_token(token_env))

    def test_display_refresh_rate_controller_locks_59hz_to_60hz_and_restores(self) -> None:
        applied_hz: List[int] = []
        current_hz = [59]

        def get_hz() -> int:
            return current_hz[0]

        def set_hz(hz: int) -> bool:
            applied_hz.append(hz)
            current_hz[0] = hz
            return True

        ctrl = agent_mod.DisplayRefreshRateController(
            get_primary_refresh_hz_fn=get_hz,
            set_primary_refresh_hz_fn=set_hz,
        )
        self.assertTrue(ctrl.engage_60hz())
        self.assertEqual(applied_hz, [60])
        self.assertEqual(current_hz[0], 60)
        self.assertTrue(ctrl.engage_60hz())
        self.assertEqual(applied_hz, [60])

        self.assertTrue(ctrl.restore())
        self.assertEqual(applied_hz, [60, 59])
        self.assertEqual(current_hz[0], 59)

    def test_prelaunch_before_stream_and_launch_status_readiness_gate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_dir = tmp_dir / "Games"
            (games_dir / "steamapps").mkdir(parents=True)
            (games_dir / "steamapps" / "appmanifest_728880.acf").write_text(SAMPLE_ACF, encoding="utf-8")

            cfg_file = tmp_dir / "config.properties"
            preseeded = "e" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=games_dir,
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            visible_hwnd = [0]
            spawned_games: List[str] = []
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                find_game_hwnd_fn=lambda _g: visible_hwnd[0],
                get_hwnd_pid_fn=lambda _h: 7777,
                process_snapshot_fn=lambda: {7777: "overcooked2.exe"},
                close_sunshine_app_fn=lambda: True,
                create_native_window=False,
                poll_interval_sec=0.02,
                safety_net=PCPrivacySafetyNet(
                    mute_audio_fn=lambda: (True, False),
                    unmute_audio_fn=lambda _m: True,
                    minimize_windows_fn=lambda: [101],
                    restore_windows_fn=lambda _h: None,
                    hide_desktop_fn=lambda: (True, 202),
                    restore_desktop_fn=lambda _v, _h: None,
                    refresh_controller=_make_mock_refresh_controller(),
                ),
            )
            app.safety_net = app.privacy_curtain.safety_net
            app.process_controller.privacy_curtain = app.privacy_curtain
            app.process_controller.safety_net = app.safety_net

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()

            try:
                with mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=0), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(agent_mod.SteamPreparationManager, "prepare_for_stream", return_value=True), \
                     mock.patch.object(PrivacyCurtainManager, "_ensure_game_focused", return_value=True), \
                     mock.patch.object(
                         WindowsSessionController,
                         "launch_game",
                         side_effect=lambda g, **_kw: spawned_games.append(g.game_id) or True,
                     ):
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps({
                            "gameId": "steam_728880",
                            "connectionMode": "wifi",
                            "prelaunchBeforeStream": True,
                            "userApproved": True,
                        }).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp = conn.getresponse()
                    launch_data = json.loads(resp.read().decode("utf-8"))
                    conn.close()

                    self.assertEqual(resp.status, 200)
                    self.assertTrue(launch_data["curtainEngaged"])
                    self.assertEqual(launch_data["sunshineAppName"], "Desktop")
                    self.assertEqual(spawned_games, ["steam_728880"])

                    conn_s1 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn_s1.request(
                        "GET",
                        "/api/gaming/launch-status",
                        headers={"X-SoarTV-Token": preseeded},
                    )
                    r_s1 = conn_s1.getresponse()
                    st1 = json.loads(r_s1.read().decode("utf-8"))
                    conn_s1.close()
                    self.assertEqual(r_s1.status, 200)
                    self.assertTrue(st1["active"])
                    self.assertFalse(st1["gameWindowReady"])

                    visible_hwnd[0] = 8888
                    deadline = time.time() + 1.0
                    ready_seen = False
                    while time.time() < deadline:
                        conn_s2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn_s2.request(
                            "GET",
                            "/api/gaming/launch-status",
                            headers={"X-SoarTV-Token": preseeded},
                        )
                        r_s2 = conn_s2.getresponse()
                        st2 = json.loads(r_s2.read().decode("utf-8"))
                        conn_s2.close()
                        if st2.get("gameWindowReady"):
                            ready_seen = True
                            self.assertEqual(st2.get("hwnd"), 8888)
                            break
                        time.sleep(0.03)
                    self.assertTrue(ready_seen, "Expected gameWindowReady=True once game HWND appeared")
            finally:
                app.privacy_curtain.stop()
                server.shutdown()
                server.server_close()

    def test_sunshine_pair_endpoint_retries_until_pin_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            preseeded = "f" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            attempts = [0]

            def flaky_pin(pin: str, device_name: str = "SoarTV") -> bool:
                attempts[0] += 1
                return attempts[0] >= 3 and pin == "4829"

            try:
                with mock.patch.object(SunshineConfigManager, "submit_pairing_pin", side_effect=flaky_pin):
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                    conn.request(
                        "POST",
                        "/api/gaming/sunshine-pair",
                        body=json.dumps({"pin": "4829", "deviceName": "SoarTV"}).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp = conn.getresponse()
                    data = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    self.assertEqual(resp.status, 200)
                    self.assertTrue(data["ok"])
                    self.assertTrue(data["paired"])
                    self.assertGreaterEqual(attempts[0], 3)
            finally:
                server.shutdown()
                server.server_close()

    def test_udp_discovery_responder_replies_with_identity_and_ignores_noise(self) -> None:
        import socket as sock_mod
        from laptop_gaming_agent import UdpDiscoveryResponder

        responder = UdpDiscoveryResponder(
            host="127.0.0.1",
            port=0,
            tcp_port=8485,
            uuid_provider=lambda: "14FEDE7C-47F1-1D07-0185-22930C564015",
            hostname_provider=lambda: "Gaming-Laptop",
        )
        responder.start()
        try:
            udp_port = responder.bound_port
            self.assertGreater(udp_port, 0)
            with sock_mod.socket(sock_mod.AF_INET, sock_mod.SOCK_DGRAM) as cli:
                cli.settimeout(1.0)
                cli.sendto(b'{"op":"UNRELATED"}', ("127.0.0.1", udp_port))
                cli.sendto(b'{"op":"SOARTV_GAMING_DISCOVER_V1"}', ("127.0.0.1", udp_port))
                raw, _addr = cli.recvfrom(4096)
                payload = json.loads(raw.decode("utf-8"))
                self.assertEqual(payload.get("op"), "SOARTV_GAMING_HERE_V1")
                self.assertEqual(payload.get("agentService"), "soartv-laptop-gaming-agent")
                self.assertEqual(payload.get("port"), 8485)
                self.assertEqual(payload.get("sunshineHostUuid"), "14FEDE7C-47F1-1D07-0185-22930C564015")
                self.assertEqual(payload.get("sunshineHostName"), "Gaming-Laptop")
        finally:
            responder.stop()

    def test_status_reports_authenticated_flag_macs_and_records_tv_beacon(self) -> None:
        from http.server import BaseHTTPRequestHandler, HTTPServer
        from laptop_gaming_agent import LocalNetworkInspector, TvBeaconNotifier

        macs = LocalNetworkInspector.get_local_mac_addresses()
        self.assertIsInstance(macs, list)
        for m in macs:
            self.assertRegex(m, r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        announce_hits: List[str] = []

        class TvMockHandler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                content_len = int(self.headers.get("Content-Length", "0") or "0")
                if content_len > 0:
                    self.rfile.read(content_len)
                if self.path == "/api/gaming/pc-announce":
                    announce_hits.append(self.path)
                self.send_response(200)
                self.send_header("Content-Length", "11")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, fmt: str, *args: Any) -> None:
                return

        tv_server = HTTPServer(("127.0.0.1", 0), TvMockHandler)
        tv_port = tv_server.server_address[1]
        tv_thread = threading.Thread(target=tv_server.serve_forever, daemon=True)
        tv_thread.start()

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            preseeded = "a" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
            )
            beacon = TvBeaconNotifier(endpoint_store_path=tmp_dir / "last_tv.json", default_tv_port=tv_port)
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
                tv_beacon=beacon,
            )
            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                c1 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                c1.request("GET", "/api/gaming/status")
                d1 = json.loads(c1.getresponse().read().decode("utf-8"))
                c1.close()
                self.assertFalse(d1.get("authenticated"))
                self.assertEqual(d1.get("agentService"), "soartv-laptop-gaming-agent")
                self.assertNotIn("macAddresses", d1)

                c2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                c2.request(
                    "GET",
                    "/api/gaming/status",
                    headers={"X-SoarTV-Token": preseeded, "X-SoarTV-Tv-Port": str(tv_port)},
                )
                d2 = json.loads(c2.getresponse().read().decode("utf-8"))
                c2.close()
                self.assertTrue(d2.get("authenticated"))
                self.assertIn("macAddresses", d2)

                beacon.record_authenticated_tv("127.0.0.1", str(tv_port))
                self.assertTrue(beacon.notify_ip_changed("192.168.1.99"))
                self.assertEqual(len(announce_hits), 1)
            finally:
                server.shutdown()
                server.server_close()
                tv_server.shutdown()
                tv_server.server_close()

    def test_production_tv_beacon_store_and_automated_test_safeguard(self) -> None:
        from laptop_gaming_agent import SteamPreparationManager, TvBeaconNotifier

        self.assertTrue(TvBeaconNotifier.is_loopback_or_invalid_ip("127.0.0.1"))
        self.assertTrue(TvBeaconNotifier.is_loopback_or_invalid_ip("::1"))
        self.assertTrue(TvBeaconNotifier.is_loopback_or_invalid_ip("0.0.0.0"))
        self.assertTrue(TvBeaconNotifier.is_loopback_or_invalid_ip(""))
        self.assertFalse(TvBeaconNotifier.is_loopback_or_invalid_ip("192.168.1.50"))

        with tempfile.TemporaryDirectory() as tmp:
            store = Path(tmp) / "last_tv.json"
            store.write_text(json.dumps({"tvIp": "192.168.1.50", "tvPort": 8484}), encoding="utf-8")
            beacon = TvBeaconNotifier(endpoint_store_path=store)
            beacon.record_authenticated_tv("127.0.0.1", "8484")
            saved = json.loads(store.read_text(encoding="utf-8"))
            self.assertEqual(saved["tvIp"], "192.168.1.50")

        # Automated test safeguard blocks WinDLL/ctypes hardware calls
        with mock.patch("ctypes.WinDLL", side_effect=AssertionError("ctypes.WinDLL must not be called in tests")):
            self.assertEqual(CursorPrivacyController._default_get_virtual_bounds(), (0, 0, 1920, 1080))
            self.assertEqual(Win32InputInjector._native_send_batch([]), 0)
            self.assertFalse(SteamPreparationManager._dismiss_steam_interstitial_once(set()))
            injector = Win32InputInjector(wake_pause_sec=0.0)
            with mock.patch("subprocess.run", side_effect=AssertionError("subprocess.run must not be called in tests")):
                self.assertFalse(injector.inject_pin("1234", allow_helper_fallback=False))
                self.assertFalse(injector.inject_pin("1234", allow_helper_fallback=True))

    def test_privacy_curtain_rapid_relaunch_does_not_teardown_new_session(self) -> None:
        s1_in_find = threading.Event()
        release_s1 = threading.Event()
        restore_calls: List[str] = []
        esc_stop_calls: List[str] = []
        cursor_restore_calls: List[str] = []
        call_count = [0]

        def find_hwnd(_game: GameEntry) -> int:
            call_count[0] += 1
            if call_count[0] == 1:
                s1_in_find.set()
                release_s1.wait(timeout=2.0)
                return 0
            return 2002

        safety = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=lambda _m: restore_calls.append("audio") or True,
            minimize_windows_fn=lambda: [101],
            restore_windows_fn=lambda _h: restore_calls.append("windows"),
            hide_desktop_fn=lambda: (True, 202),
            restore_desktop_fn=lambda _v, _h: restore_calls.append("desktop"),
            refresh_controller=_make_mock_refresh_controller(),
        )
        cursor_ctrl = CursorPrivacyController(
            get_cursor_pos_fn=lambda: (500, 500),
            set_cursor_pos_fn=lambda _x, _y: True,
            get_virtual_bounds_fn=lambda: (0, 0, 1920, 1080),
        )
        orig_restore_cursor = cursor_ctrl.restore_cursor

        def tracked_restore_cursor() -> bool:
            cursor_restore_calls.append("cursor")
            return orig_restore_cursor()

        cursor_ctrl.restore_cursor = tracked_restore_cursor  # type: ignore[method-assign]
        esc = PhysicalEscAbortListener(key_state_fn=lambda _vk: 0)
        orig_esc_stop = esc.stop

        def tracked_esc_stop() -> None:
            esc_stop_calls.append("esc_stop")
            orig_esc_stop()

        esc.stop = tracked_esc_stop  # type: ignore[method-assign]

        curtain = PrivacyCurtainManager(
            cursor_controller=cursor_ctrl,
            safety_net=safety,
            esc_listener=esc,
            find_game_hwnd_fn=find_hwnd,
            get_hwnd_pid_fn=lambda _h: 5000,
            process_snapshot_fn=lambda: {5000: "game2.exe"},
            close_sunshine_app_fn=lambda: True,
            apply_curtain_z_order_fn=lambda _m, _h: None,
            create_native_window=False,
            poll_interval_sec=0.01,
            connect_timeout_sec=1.0,
        )
        g1 = GameEntry("steam_1", "Game 1", "steam", "steam://rungameid/1", "")
        g2 = GameEntry("steam_2", "Game 2", "steam", "steam://rungameid/2", "")
        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("CLIENT CONNECTED\n")
            f.flush()
            log_path = Path(f.name)
        try:
            curtain._explicit_log_path = log_path
            self.assertTrue(curtain.start_for_game(g1, use_hdmi=False))
            self.assertTrue(s1_in_find.wait(timeout=2.0))
            curtain.stop()
            restore_calls.clear()
            esc_stop_calls.clear()
            cursor_restore_calls.clear()
            self.assertTrue(curtain.start_for_game(g2, use_hdmi=False))
            pre_release_esc = len(esc_stop_calls)
            release_s1.set()
            time.sleep(0.08)
            self.assertTrue(curtain.is_active())
            self.assertTrue(safety.is_engaged())
            self.assertEqual(restore_calls, [])
            self.assertEqual(len(esc_stop_calls), pre_release_esc)
            self.assertEqual(cursor_restore_calls, [])
        finally:
            release_s1.set()
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_privacy_curtain_natural_exit_vs_relaunch_no_toctou(self) -> None:
        # (a) Standalone natural exit: safety restores while curtain is still active, then active becomes False
        events: List[Tuple[str, bool]] = []
        safety_a = PCPrivacySafetyNet(
            mute_audio_fn=lambda: (True, False),
            unmute_audio_fn=lambda _m: events.append(("restore", curtain_a.is_active())) or True,
            minimize_windows_fn=lambda: [],
            restore_windows_fn=lambda _h: None,
            hide_desktop_fn=lambda: (True, 0),
            restore_desktop_fn=lambda _v, _h: None,
            refresh_controller=_make_mock_refresh_controller(),
        )
        curtain_a = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=safety_a,
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            find_game_hwnd_fn=lambda _g: 0,
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.01,
            connect_timeout_sec=0.04,
        )
        g1 = GameEntry("steam_1", "Game 1", "steam", "steam://rungameid/1", "")
        g2 = GameEntry("steam_2", "Game 2", "steam", "steam://rungameid/2", "")
        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("[2026-11-25 12:00:00]: Info: Log initialized\n")
            f.flush()
            log_path_a = Path(f.name)
        try:
            curtain_a._explicit_log_path = log_path_a
            self.assertTrue(curtain_a.start_for_game(g1, use_hdmi=False))
            deadline = time.monotonic() + 2.0
            while curtain_a.is_active() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertFalse(curtain_a.is_active())
            self.assertFalse(safety_a.is_engaged())
            self.assertTrue(any(ev == ("restore", True) for ev in events))
        finally:
            curtain_a.stop()
            try:
                log_path_a.unlink()
            except OSError:
                pass

        # (b) Session 1 pauses inside esc.stop() on natural exit while Thread 2 launches Session 2
        s1_in_esc_stop = threading.Event()
        release_s1_esc_stop = threading.Event()
        esc_stop_calls = [0]

        safety_b = _make_mock_safety_net()
        esc_b = PhysicalEscAbortListener(key_state_fn=lambda _vk: 0)
        orig_stop = esc_b.stop

        def blocking_stop() -> None:
            esc_stop_calls[0] += 1
            if esc_stop_calls[0] == 1:
                s1_in_esc_stop.set()
                release_s1_esc_stop.wait(timeout=2.0)
            orig_stop()

        esc_b.stop = blocking_stop  # type: ignore[method-assign]
        curtain_b = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=safety_b,
            esc_listener=esc_b,
            find_game_hwnd_fn=lambda g: 2002 if g.game_id == "steam_2" else 0,
            get_hwnd_pid_fn=lambda _h: 5000,
            process_snapshot_fn=lambda: {5000: "game2.exe"},
            close_sunshine_app_fn=lambda: True,
            apply_curtain_z_order_fn=lambda _m, _h: None,
            create_native_window=False,
            poll_interval_sec=0.01,
            connect_timeout_sec=0.04,
        )
        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("[2026-11-25 12:00:00]: Info: Log initialized\n")
            f.flush()
            log_path_b = Path(f.name)
        s2_result: List[bool] = []
        try:
            curtain_b._explicit_log_path = log_path_b
            self.assertTrue(curtain_b.start_for_game(g1, use_hdmi=False))
            self.assertTrue(s1_in_esc_stop.wait(timeout=2.0))

            def launch_s2() -> None:
                curtain_b._connect_timeout_sec = 1.0
                s2_result.append(curtain_b.start_for_game(g2, use_hdmi=False))

            t2 = threading.Thread(target=launch_s2, daemon=True)
            t2.start()
            time.sleep(0.03)
            release_s1_esc_stop.set()
            t2.join(timeout=2.0)
            self.assertEqual(s2_result, [True])
            self.assertTrue(curtain_b.is_active())
            self.assertTrue(safety_b.is_engaged())
            self.assertTrue(esc_b.is_running())
        finally:
            release_s1_esc_stop.set()
            curtain_b.stop()
            try:
                log_path_b.unlink()
            except OSError:
                pass

    def test_privacy_curtain_esc_abort_vs_teardown_no_deadlock(self) -> None:
        s1_in_esc_stop = threading.Event()
        release_s1_esc_stop = threading.Event()
        esc_fire = threading.Event()
        close_app_calls: List[str] = []
        safety = _make_mock_safety_net()
        esc = PhysicalEscAbortListener(
            poll_interval_sec=0.005,
            key_state_fn=lambda _vk: 0x8000 if esc_fire.is_set() else 0,
        )
        orig_esc_stop = esc.stop
        stop_count = [0]

        def blocking_esc_stop() -> None:
            stop_count[0] += 1
            if stop_count[0] == 2:
                s1_in_esc_stop.set()
                release_s1_esc_stop.wait(timeout=2.0)
            orig_esc_stop()

        esc.stop = blocking_esc_stop  # type: ignore[method-assign]
        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            safety_net=safety,
            esc_listener=esc,
            find_game_hwnd_fn=lambda g: 2002 if g.game_id == "steam_2" else 0,
            get_hwnd_pid_fn=lambda _h: 5000,
            process_snapshot_fn=lambda: {5000: "game2.exe"},
            close_sunshine_app_fn=lambda: True,
            apply_curtain_z_order_fn=lambda _m, _h: None,
            create_native_window=False,
            poll_interval_sec=0.01,
            connect_timeout_sec=0.04,
        )
        game1 = GameEntry("steam_1", "Game 1", "steam", "steam://rungameid/1", "")
        game2 = GameEntry("steam_2", "Game 2", "steam", "steam://rungameid/2", "")

        with tempfile.NamedTemporaryFile("w+", delete=False, encoding="utf-8") as f:
            f.write("[2026-11-25 12:00:00]: Info: Log initialized\n")
            f.flush()
            log_path = Path(f.name)

        try:
            curtain._explicit_log_path = log_path
            with mock.patch.object(
                SunshineConfigManager,
                "close_active_app",
                side_effect=lambda: close_app_calls.append("closed") or True,
            ):
                self.assertTrue(curtain.start_for_game(game1, use_hdmi=False))
                self.assertTrue(s1_in_esc_stop.wait(timeout=2.0))
                esc_fire.set()
                time.sleep(0.03)
                esc_fire.clear()
                release_s1_esc_stop.set()

                curtain._connect_timeout_sec = 1.0
                self.assertTrue(curtain.start_for_game(game2, use_hdmi=False))
                time.sleep(0.06)
                self.assertTrue(curtain.is_active())
                self.assertTrue(safety.is_engaged())
                self.assertEqual(close_app_calls, [])
        finally:
            release_s1_esc_stop.set()
            curtain.stop()
            try:
                log_path.unlink()
            except OSError:
                pass

    def test_sunshine_config_manager_m3_optimus_coder_and_refresh_alignment(self) -> None:
        opt = SunshineConfigManager.OPTIMAL_OPTIMUS_SUNSHINE_CONFIG
        self.assertEqual(opt.get("encoder"), "quicksync")
        self.assertEqual(opt.get("qsv_coder"), "cabac")
        self.assertGreaterEqual(int(opt.get("minimum_fps_target", 0)), 60)
        self.assertEqual(opt.get("dd_configuration_option"), "verify_only")
        self.assertEqual(opt.get("dd_refresh_rate_option"), "auto")

        posted: List[Dict[str, Any]] = []
        restarted: List[bool] = []

        def fake_api(method: str, path: str, payload: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
            if method == "GET" and path == "/api/config":
                return {"encoder": "quicksync", "qsv_coder": "cavlc", "dd_refresh_rate_option": "disabled"}
            if method == "POST" and path == "/api/config":
                posted.append(dict(payload or {}))
                return {"status": True}
            if method == "POST" and path == "/api/restart":
                restarted.append(True)
                return {"status": True}
            return None

        with mock.patch.object(SunshineConfigManager, "_sunshine_api_request", side_effect=fake_api):
            self.assertTrue(SunshineConfigManager.ensure_optimal_sunshine_conf())
            self.assertEqual(len(posted), 1)
            self.assertEqual(restarted, [])
            self.assertEqual(posted[0].get("qsv_coder"), "cabac")
            self.assertEqual(posted[0].get("dd_configuration_option"), "verify_only")
            self.assertEqual(posted[0].get("dd_refresh_rate_option"), "auto")

            posted.clear()
            self.assertTrue(SunshineConfigManager.ensure_optimal_sunshine_conf(restart_on_change=True))
            self.assertEqual(len(posted), 1)
            self.assertEqual(restarted, [True])

    def test_desktop_launch_never_spawns_explorer_exe(self) -> None:
        from laptop_gaming_agent import (
            DisplayModeController,
            GameEntry,
            LaptopGamingAgentApp,
            WindowsSessionController,
            create_http_server,
        )

        desktop_game = GameEntry("desktop", "Windows Desktop", "system", "explorer.exe", "")
        popen_calls: List[Any] = []
        startfile_calls: List[Any] = []

        with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
            with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                 mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                 mock.patch("subprocess.Popen", side_effect=lambda *a, **kw: popen_calls.append((a, kw))), \
                 mock.patch("os.startfile", side_effect=lambda *a, **kw: startfile_calls.append(a), create=True):
                launched = WindowsSessionController.launch_game(
                    desktop_game, force_hdmi_only=False, user_approved=True
                )
        self.assertTrue(launched)
        self.assertEqual(popen_calls, [])
        self.assertEqual(startfile_calls, [])

        with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
            with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                 mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=True), \
                 mock.patch.object(DisplayModeController, "apply_display_mode", return_value="external") as apply_mock, \
                 mock.patch("subprocess.Popen", side_effect=lambda *a, **kw: popen_calls.append((a, kw))), \
                 mock.patch("os.startfile", side_effect=lambda *a, **kw: startfile_calls.append(a), create=True):
                launched_hdmi = WindowsSessionController.launch_game(
                    GameEntry("desktop", "Windows Desktop", "system", '"explorer.exe"', ""),
                    force_hdmi_only=True,
                    user_approved=True,
                )
        self.assertTrue(launched_hdmi)
        apply_mock.assert_called_once_with(hdmi_connected=True)
        self.assertEqual(popen_calls, [])
        self.assertEqual(startfile_calls, [])

        for flag_cmd in (
            "explorer.exe /root,C:",
            '"C:\\Windows\\explorer.exe" /e',
            "C:\\Windows\\explorer.exe /select,C:\\test",
        ):
            with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
                with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                     mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                     mock.patch("subprocess.Popen", side_effect=lambda *a, **kw: popen_calls.append((a, kw))), \
                     mock.patch("os.startfile", side_effect=lambda *a, **kw: startfile_calls.append(a), create=True):
                    launched_flagged = WindowsSessionController.launch_game(
                        GameEntry("custom_exp", "Custom Explorer", "system", flag_cmd, ""),
                        force_hdmi_only=False,
                        user_approved=True,
                    )
            self.assertTrue(launched_flagged, f"Failed for {flag_cmd}")
            self.assertEqual(popen_calls, [], f"Popen unexpectedly called for {flag_cmd}")
            self.assertEqual(startfile_calls, [], f"startfile unexpectedly called for {flag_cmd}")

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            preseeded = "c" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                create_native_window=False,
                safety_net=_make_mock_safety_net(),
            )
            app.safety_net = app.privacy_curtain.safety_net
            app.process_controller.privacy_curtain = app.privacy_curtain
            app.process_controller.safety_net = app.safety_net

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
                    with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                         mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                         mock.patch("subprocess.Popen", side_effect=lambda *a, **kw: popen_calls.append((a, kw))), \
                         mock.patch("os.startfile", side_effect=lambda *a, **kw: startfile_calls.append(a), create=True):
                        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps({
                                "gameId": "desktop",
                                "connectionMode": "auto",
                                "delegateLaunchToMoonlight": False,
                                "prelaunchBeforeStream": False,
                                "userApproved": True,
                            }).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                        )
                        resp = conn.getresponse()
                        data = json.loads(resp.read().decode("utf-8"))
                        conn.close()
                self.assertEqual(resp.status, 200)
                self.assertTrue(data["launched"])
                self.assertFalse(data["curtainEngaged"])
                self.assertEqual(popen_calls, [])
                self.assertEqual(startfile_calls, [])
            finally:
                server.shutdown()
                server.server_close()

    def test_http_server_sets_request_timeout_and_closes_slowloris_clients(self) -> None:
        from laptop_gaming_agent import (
            HTTP_REQUEST_TIMEOUT_SEC,
            LaptopGamingAgentApp,
            create_http_server,
        )

        self.assertGreater(HTTP_REQUEST_TIMEOUT_SEC, 0.0)
        self.assertEqual(HTTP_REQUEST_TIMEOUT_SEC, 10.0)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            cfg_file.write_text("gaming_pc_token=" + ("f" * 64) + "\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )

            default_srv = create_http_server(app=app, host="127.0.0.1", port=0)
            try:
                self.assertEqual(default_srv.RequestHandlerClass.timeout, 10.0)
            finally:
                default_srv.server_close()

            with mock.patch.object(agent_mod, "HTTP_REQUEST_TIMEOUT_SEC", 0.25):
                server = create_http_server(app=app, host="127.0.0.1", port=0)
                port = server.server_address[1]
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    self.assertEqual(server.RequestHandlerClass.timeout, 0.25)
                    slow = socket.create_connection(("127.0.0.1", port), timeout=2.0)
                    try:
                        slow.sendall(b"GET /api/gaming/status HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                        start_t = time.monotonic()
                        chunk = slow.recv(1024)
                        elapsed = time.monotonic() - start_t
                        self.assertEqual(chunk, b"")
                        self.assertLess(elapsed, 1.5)
                    finally:
                        slow.close()

                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2.0)
                    conn.request("GET", "/api/gaming/status")
                    resp = conn.getresponse()
                    body = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    self.assertEqual(resp.status, 200)
                    self.assertTrue(body.get("ok"))
                finally:
                    server.shutdown()
                    server.server_close()

    def test_steam_cold_start_single_command_silent_applaunch_no_race(self) -> None:
        """Verifies cold-start Steam launch uses one '-silent -applaunch <id>' command while warm-start uses '-applaunch <id>'."""
        from laptop_gaming_agent import (
            DisplayModeController,
            GameEntry,
            LaptopGamingAgentApp,
            SteamPreparationManager,
            Win32SystemProbes,
            WindowsSessionController,
            create_http_server,
        )

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            steam_dir = tmp_dir / "Steam"
            steam_apps = steam_dir / "steamapps"
            user_cfg = steam_dir / "userdata" / "12345" / "config"
            steam_apps.mkdir(parents=True, exist_ok=True)
            user_cfg.mkdir(parents=True, exist_ok=True)
            fake_steam_exe = steam_dir / "steam.exe"
            fake_steam_exe.write_bytes(b"MZ")
            vdf_file = user_cfg / "localconfig.vdf"
            vdf_file.write_text('"UserLocalConfigStore"\n{\n\t"Steam"\n\t{\n\t}\n}\n', encoding="utf-8")
            (steam_apps / "appmanifest_965680.acf").write_text(
                '"AppState"\n{\n\t"appid"\t\t"965680"\n\t"name"\t\t"Boomerang Fu"\n\t"StateFlags"\t\t"4"\n\t"installdir"\t\t"Boomerang Fu"\n}\n',
                encoding="utf-8",
            )
            cfg_file = tmp_dir / "config.properties"
            preseeded = "d" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")

            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=steam_dir,
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                create_native_window=False,
                safety_net=_make_mock_safety_net(),
                find_game_hwnd_fn=lambda _g: 0,
                process_snapshot_fn=lambda: {},
                close_sunshine_app_fn=lambda: True,
                steam_cold_retry_delay_sec=999.0,
            )
            app.safety_net = app.privacy_curtain.safety_net
            app.process_controller.privacy_curtain = app.privacy_curtain
            app.process_controller.safety_net = app.safety_net
            app.refresh_and_sync(include_hidden=True, sync_sunshine=False)

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                # 1. Cold start: Steam NOT running -> single Popen([steam.exe, '-silent', '-applaunch', '965680'])
                cold_popens: List[List[str]] = []
                with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
                    with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                         mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                         mock.patch.object(SteamPreparationManager, "resolve_steam_exe", return_value=fake_steam_exe), \
                         mock.patch.object(Win32SystemProbes, "is_process_running", return_value=False), \
                         mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                         mock.patch("subprocess.Popen", side_effect=lambda cmd, *a, **kw: cold_popens.append(list(cmd))):
                        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps({
                                "gameId": "steam_965680",
                                "connectionMode": "auto",
                                "prelaunchBeforeStream": True,
                                "userApproved": True,
                            }).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                        )
                        resp = conn.getresponse()
                        data = json.loads(resp.read().decode("utf-8"))
                        conn.close()
                self.assertEqual(resp.status, 200)
                self.assertTrue(data["launched"])
                self.assertEqual(
                    cold_popens,
                    [[str(fake_steam_exe), "-silent", "-applaunch", "965680"]],
                    "Cold start must spawn ONE single '-silent -applaunch' Steam process without racing bare '-silent'",
                )
                self.assertIn("965680", vdf_file.read_text(encoding="utf-8"))

                # 2. Warm start: Steam ALREADY running -> single Popen([steam.exe, '-applaunch', '965680'])
                warm_popens: List[List[str]] = []
                with mock.patch.dict(os.environ, {"SOARTV_ENABLE_REAL_GAME_LAUNCH": "1"}):
                    with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                         mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                         mock.patch.object(SteamPreparationManager, "resolve_steam_exe", return_value=fake_steam_exe), \
                         mock.patch.object(Win32SystemProbes, "is_process_running", return_value=True), \
                         mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                         mock.patch("subprocess.Popen", side_effect=lambda cmd, *a, **kw: warm_popens.append(list(cmd))):
                        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                        conn.request(
                            "POST",
                            "/api/gaming/launch",
                            body=json.dumps({
                                "gameId": "steam_965680",
                                "connectionMode": "auto",
                                "prelaunchBeforeStream": True,
                                "userApproved": True,
                            }).encode("utf-8"),
                            headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                        )
                        resp2 = conn.getresponse()
                        data2 = json.loads(resp2.read().decode("utf-8"))
                        conn.close()
                self.assertEqual(resp2.status, 200)
                self.assertTrue(data2["launched"])
                self.assertEqual(
                    warm_popens,
                    [[str(fake_steam_exe), "-applaunch", "965680"]],
                    "Warm start must launch directly with '-applaunch <appid>'",
                )
            finally:
                app.privacy_curtain.stop()
                server.shutdown()
                server.server_close()

    def test_privacy_curtain_steam_cold_start_single_shot_retry(self) -> None:
        """Verifies PrivacyCurtainManager performs at most one Steam relaunch retry when Steam finishes booting without game window."""
        retry_calls: List[str] = []
        steam_game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")

        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 0,
            process_snapshot_fn=lambda: {100: "steam.exe", 101: "steamwebhelper.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            steam_cold_retry_delay_sec=0.05,
            is_steam_ready_fn=lambda: True,
            steam_retry_launch_fn=lambda g: retry_calls.append(g.game_id) or True,
        )
        try:
            self.assertTrue(curtain.start_for_game(steam_game, use_hdmi=False, prelaunched=True))
            time.sleep(0.22)
            self.assertEqual(retry_calls, ["steam_965680"], "Must fire single-shot retry exactly once")
        finally:
            curtain.stop()

        # If expected game process is already running, retry must NOT fire
        retry_calls_running: List[str] = []
        curtain_running = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 0,
            process_snapshot_fn=lambda: {100: "steam.exe", 500: "boomerangfu.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            steam_cold_retry_delay_sec=0.05,
            is_steam_ready_fn=lambda: True,
            steam_retry_launch_fn=lambda g: retry_calls_running.append(g.game_id) or True,
        )
        try:
            self.assertTrue(curtain_running.start_for_game(steam_game, use_hdmi=False, prelaunched=True))
            time.sleep(0.18)
            self.assertEqual(retry_calls_running, [], "Must NOT retry when game process is already running")
        finally:
            curtain_running.stop()

        # Delayed Steam readiness (e.g. Steam self-updating before steamwebhelper.exe starts):
        # retry must wait full steam_cold_retry_delay_sec AFTER is_steam_ready_fn turns True.
        delayed_calls: List[str] = []
        steam_ready_flag = {"ready": False}
        curtain_delayed = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 0,
            process_snapshot_fn=lambda: {100: "steam.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            steam_cold_retry_delay_sec=0.12,
            is_steam_ready_fn=lambda: steam_ready_flag["ready"],
            steam_retry_launch_fn=lambda g: delayed_calls.append(g.game_id) or True,
        )
        try:
            self.assertTrue(curtain_delayed.start_for_game(steam_game, use_hdmi=False, prelaunched=True))
            time.sleep(0.16)
            self.assertEqual(delayed_calls, [], "Must NOT retry while Steam updater is still running")
            steam_ready_flag["ready"] = True
            time.sleep(0.05)
            self.assertEqual(delayed_calls, [], "Must wait full retry delay after Steam becomes ready")
            time.sleep(0.16)
            self.assertEqual(delayed_calls, ["steam_965680"], "Must fire retry once after post-ready delay")
        finally:
            curtain_delayed.stop()

        # While Steam is still self-updating (is_steam_ready_fn=False), retry delay starts only AFTER ready
        retry_calls_updating: List[str] = []
        steam_ready_flag = [False]
        curtain_updating = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 0,
            process_snapshot_fn=lambda: {100: "steam.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            steam_cold_retry_delay_sec=0.10,
            is_steam_ready_fn=lambda: steam_ready_flag[0],
            steam_retry_launch_fn=lambda g: retry_calls_updating.append(g.game_id) or True,
        )
        try:
            self.assertTrue(curtain_updating.start_for_game(steam_game, use_hdmi=False, prelaunched=True))
            time.sleep(0.16)
            self.assertEqual(retry_calls_updating, [], "Must NOT fire retry while Steam is still updating")
            steam_ready_flag[0] = True
            time.sleep(0.04)
            self.assertEqual(retry_calls_updating, [], "Must wait full delay after Steam becomes ready")
            time.sleep(0.14)
            self.assertEqual(retry_calls_updating, ["steam_965680"], "Must fire once after ready delay elapses")
        finally:
            curtain_updating.stop()

    def test_steam_idle_shutdown_manager_grace_cancel_guards_and_self_exit(self) -> None:
        """Verifies 10-minute grace constant, shutdown firing, cancellation on new launch, active-game guard, and listener self-exit."""
        from laptop_gaming_agent import (
            DEFAULT_STEAM_IDLE_GRACE_SECONDS,
            SteamIdleShutdownManager,
        )

        self.assertEqual(DEFAULT_STEAM_IDLE_GRACE_SECONDS, 600.0)

        # 1. Fires after grace period and listener thread self-terminates cleanly
        shutdown_events: List[str] = []
        mgr = SteamIdleShutdownManager(
            grace_seconds=0.08,
            is_steam_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: shutdown_events.append("shutdown") or True,
        )
        self.assertTrue(mgr.schedule_shutdown(reason="test_exit"))
        self.assertTrue(mgr.is_pending())
        waiter_thread = mgr._thread
        self.assertIsNotNone(waiter_thread)
        time.sleep(0.22)
        self.assertEqual(shutdown_events, ["shutdown"])
        self.assertFalse(mgr.is_pending())
        assert waiter_thread is not None
        waiter_thread.join(timeout=0.5)
        self.assertFalse(waiter_thread.is_alive(), "Listener thread must self-terminate after firing")

        # 2. Cancels cleanly when a new game starts before grace expires and thread self-exits
        cancelled_events: List[str] = []
        mgr_cancel = SteamIdleShutdownManager(
            grace_seconds=0.35,
            is_steam_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: cancelled_events.append("shutdown") or True,
        )
        self.assertTrue(mgr_cancel.schedule_shutdown(reason="game_exit"))
        cancel_thread = mgr_cancel._thread
        self.assertTrue(mgr_cancel.cancel(reason="new_game_launch"))
        self.assertFalse(mgr_cancel.is_pending())
        assert cancel_thread is not None
        cancel_thread.join(timeout=0.5)
        self.assertFalse(cancel_thread.is_alive(), "Cancelled listener thread must exit immediately")
        time.sleep(0.42)
        self.assertEqual(cancelled_events, [], "Cancelled timer must never close Steam")

        # 3. Does not schedule or spawn thread if Steam is already closed
        mgr_no_steam = SteamIdleShutdownManager(
            grace_seconds=0.05,
            is_steam_running_fn=lambda: False,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: cancelled_events.append("unexpected") or True,
        )
        self.assertFalse(mgr_no_steam.schedule_shutdown(reason="steam_not_running"))
        self.assertFalse(mgr_no_steam.is_pending())
        self.assertIsNone(mgr_no_steam._thread)

        # 4. Skips shutdown if a TV session or local PC game is active when grace expires
        blocked_events: List[str] = []
        mgr_active_game = SteamIdleShutdownManager(
            grace_seconds=0.06,
            is_steam_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: True,
            shutdown_steam_fn=lambda: blocked_events.append("blocked") or True,
        )
        self.assertTrue(mgr_active_game.schedule_shutdown(reason="game_exit"))
        active_thread = mgr_active_game._thread
        time.sleep(0.18)
        self.assertEqual(blocked_events, [], "Must NOT shut down Steam while a game is running")
        self.assertFalse(mgr_active_game.is_pending())
        assert active_thread is not None
        active_thread.join(timeout=0.5)
        self.assertFalse(active_thread.is_alive(), "Listener must still self-terminate after skipping")

        # 5. Default native Steam shutdown is fail-closed under automated tests
        self.assertFalse(SteamIdleShutdownManager._default_shutdown_steam(Path(r"C:\Games\Steam")))

    def test_http_terminate_schedules_and_launch_cancels_steam_idle_shutdown(self) -> None:
        """Verifies POST /api/gaming/terminate schedules Steam idle shutdown and POST /api/gaming/launch cancels it."""
        from laptop_gaming_agent import (
            DisplayModeController,
            LaptopGamingAgentApp,
            SteamIdleShutdownManager,
            SteamPreparationManager,
            Win32SystemProbes,
            WindowsSessionController,
            create_http_server,
        )

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            preseeded = "e" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            shutdown_fired: List[bool] = []
            app.steam_idle_shutdown = SteamIdleShutdownManager(
                grace_seconds=0.40,
                is_steam_running_fn=lambda: True,
                is_session_active_fn=lambda: app.privacy_curtain.is_active(),
                is_any_game_running_fn=lambda: False,
                shutdown_steam_fn=lambda: shutdown_fired.append(True) or True,
            )
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                create_native_window=False,
                safety_net=_make_mock_safety_net(),
                close_sunshine_app_fn=lambda: True,
                on_session_end_fn=lambda reason: app.steam_idle_shutdown.schedule_shutdown(reason=reason),
            )
            app.safety_net = app.privacy_curtain.safety_net
            app.process_controller = GameProcessController(
                safety_net=app.safety_net,
                privacy_curtain=app.privacy_curtain,
                post_message_fn=lambda *_a: True,
                terminate_process_fn=lambda _p: True,
                is_pid_alive_fn=lambda _p: False,
                snapshot_procs_fn=lambda: [],
                find_hwnds_fn=lambda _pids: [],
                close_sunshine_fn=lambda: True,
            )
            app.refresh_and_sync(include_hidden=True, sync_sunshine=False)

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                # Terminate schedules the Steam idle grace timer
                conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                conn.request(
                    "POST",
                    "/api/gaming/terminate",
                    body=json.dumps({"gameId": "steam_965680", "reason": "tv_remote_back"}).encode("utf-8"),
                    headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                )
                resp = conn.getresponse()
                body = json.loads(resp.read().decode("utf-8"))
                conn.close()
                self.assertEqual(resp.status, 200)
                self.assertTrue(body.get("ok"))
                self.assertTrue(app.steam_idle_shutdown.is_pending())

                # Launching a new game immediately cancels the pending Steam idle timer
                with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                     mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True):
                    conn2 = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn2.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps({
                            "gameId": "desktop",
                            "connectionMode": "auto",
                            "prelaunchBeforeStream": True,
                            "userApproved": True,
                        }).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp2 = conn2.getresponse()
                    resp2.read()
                    conn2.close()
                self.assertEqual(resp2.status, 200)
                self.assertFalse(app.steam_idle_shutdown.is_pending())
                time.sleep(0.45)
                self.assertEqual(shutdown_fired, [])
            finally:
                app.steam_idle_shutdown.cancel("test_cleanup")
                app.privacy_curtain.stop()
                server.shutdown()
                server.server_close()

    def test_primary_monitor_enforcer_relocates_secondary_monitor_game_window(self) -> None:
        """Verifies secondary-monitor game windows are relocated to the primary monitor before curtain drop."""
        from laptop_gaming_agent import (
            CursorPrivacyController,
            GameEntry,
            PhysicalEscAbortListener,
            PrimaryMonitorEnforcer,
            PrivacyCurtainManager,
        )

        primary = (0, 0, 1920, 1080)
        self.assertFalse(PrimaryMonitorEnforcer.is_rect_on_primary_monitor((-1920, 0, 0, 1080), primary))
        self.assertFalse(PrimaryMonitorEnforcer.is_rect_on_primary_monitor((1920, 0, 3840, 1080), primary))
        self.assertTrue(PrimaryMonitorEnforcer.is_rect_on_primary_monitor((0, 0, 1920, 1080), primary))
        self.assertTrue(PrimaryMonitorEnforcer.is_rect_on_primary_monitor((100, 80, 1700, 980), primary))

        # Fullscreen window on left monitor (-1920,0,0,1080) moves to exact primary monitor (0,0,1920,1080)
        self.assertEqual(
            PrimaryMonitorEnforcer.compute_primary_target_rect((-1920, 0, 0, 1080), primary),
            (0, 0, 1920, 1080),
        )
        # Windowed game on secondary monitor centers inside primary monitor
        self.assertEqual(
            PrimaryMonitorEnforcer.compute_primary_target_rect((-1600, 100, -320, 820), primary),
            (320, 180, 1280, 720),
        )

        moved_hwnds: List[int] = []
        moved_calls: List[Tuple[int, int, int, int, int]] = []
        enforcer = PrimaryMonitorEnforcer(
            get_primary_rect_fn=lambda: primary,
            get_window_rect_fn=lambda _h: (-1920, 0, 0, 1080),
            is_zoomed_fn=lambda _h: False,
            move_window_fn=lambda h, x, y, w, ht: moved_calls.append((h, x, y, w, ht)) or True,
        )
        self.assertTrue(enforcer.ensure_on_primary_monitor(7788))
        self.assertEqual(moved_calls, [(7788, 0, 0, 1920, 1080)])

        # CursorPrivacyController parks inside primary monitor even if secondary monitor is to the right
        parked: List[Tuple[int, int]] = []
        cursor = CursorPrivacyController(
            get_cursor_pos_fn=lambda: (500, 500),
            set_cursor_pos_fn=lambda x, y: parked.append((x, y)) or True,
            get_virtual_bounds_fn=lambda: (0, 0, 3840, 1080),
            get_primary_bounds_fn=lambda: (0, 0, 1920, 1080),
        )
        self.assertTrue(cursor.park_cursor())
        self.assertEqual(parked[-1], (1919, 1079), "Cursor must park inside primary monitor bounds")

        # PrivacyCurtainManager enforces primary monitor placement before reporting gameWindowReady
        game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 445566,
            get_hwnd_pid_fn=lambda _h: 9900,
            process_snapshot_fn=lambda: {9900: "boomerang fu.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            ensure_on_primary_monitor_fn=lambda h: moved_hwnds.append(h) or True,
        )
        try:
            self.assertTrue(curtain.start_for_game(game, use_hdmi=False, prelaunched=True))
            time.sleep(0.12)
            self.assertIn(445566, moved_hwnds)
            self.assertTrue(curtain.get_launch_readiness().get("gameWindowReady"))
        finally:
            curtain.stop()

    def test_browser_link_isolation_guard_and_personal_browser_shield(self) -> None:
        """Verifies isolated incognito browser link command, crash-safe backup/restore, and live re-minimize shield."""
        from laptop_gaming_agent import (
            BrowserLinkIsolationGuard,
            GameEntry,
            PhysicalEscAbortListener,
            PrivacyCurtainManager,
        )

        self.assertEqual(
            BrowserLinkIsolationGuard.build_isolated_command(
                "ChromeHTML",
                r'"C:\Program Files\Google\Chrome\Application\chrome.exe" --single-argument %1',
            ),
            r'"C:\Program Files\Google\Chrome\Application\chrome.exe" --new-window --incognito -- "%1"',
        )
        self.assertEqual(
            BrowserLinkIsolationGuard.build_isolated_command(
                "MSEdgeHTM",
                r'"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --single-argument %1',
            ),
            r'"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --new-window --inprivate -- "%1"',
        )
        self.assertEqual(
            BrowserLinkIsolationGuard.build_isolated_command(
                "FirefoxURL-308046B0AF4A39CB",
                r'"C:\Program Files\Mozilla Firefox\firefox.exe" -osint -url "%1"',
            ),
            r'"C:\Program Files\Mozilla Firefox\firefox.exe" -private-window "%1"',
        )

        # Fail-closed under automated tests when using native registry backend
        self.assertFalse(BrowserLinkIsolationGuard._native_write_hkcu_command("ChromeHTML", "cmd"))

        with tempfile.TemporaryDirectory() as tmp:
            backup_path = Path(tmp) / "browser_iso_backup.json"
            fake_hkcu: Dict[str, str] = {}
            guard = BrowserLinkIsolationGuard(
                backup_path=backup_path,
                read_user_progid_fn=lambda: "ChromeHTML",
                read_hklm_command_fn=lambda _p: r'"C:\Program Files\Google\Chrome\Application\chrome.exe" --single-argument %1',
                read_hkcu_command_fn=lambda p: fake_hkcu.get(p),
                write_hkcu_command_fn=lambda p, val: fake_hkcu.__setitem__(p, val) or True,
                delete_hkcu_command_fn=lambda p: fake_hkcu.pop(p, None) is not None or True,
            )
            self.assertTrue(guard.engage())
            self.assertIn("--new-window --incognito", fake_hkcu["ChromeHTML"])
            self.assertTrue(backup_path.is_file())

            # Simulate crash recovery on fresh guard instance before restore()
            guard_after_crash = BrowserLinkIsolationGuard(
                backup_path=backup_path,
                read_hkcu_command_fn=lambda p: fake_hkcu.get(p),
                write_hkcu_command_fn=lambda p, val: fake_hkcu.__setitem__(p, val) or True,
                delete_hkcu_command_fn=lambda p: fake_hkcu.pop(p, None) is not None or True,
            )
            self.assertTrue(guard_after_crash.recover_stale_override())
            self.assertNotIn("ChromeHTML", fake_hkcu)
            self.assertFalse(backup_path.is_file())

        # Live shield in PrivacyCurtainManager re-minimizes any pre-existing personal browser window that wakes up
        reminimized: List[int] = []
        game = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        safety = _make_mock_safety_net()
        safety.set_preexisting_browser_hwnds_for_test([9001, 9002])
        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=safety,
            find_game_hwnd_fn=lambda _g: 445566,
            get_hwnd_pid_fn=lambda _h: 9900,
            process_snapshot_fn=lambda: {9900: "boomerang fu.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            ensure_on_primary_monitor_fn=lambda _h: True,
            shield_personal_browsers_fn=lambda hwnds: reminimized.extend(hwnds) or len(hwnds),
        )
        try:
            self.assertTrue(curtain.start_for_game(game, use_hdmi=False, prelaunched=True))
            time.sleep(0.10)
            self.assertIn(9001, reminimized)
            self.assertIn(9002, reminimized)
        finally:
            curtain.stop()

    def test_status_payload_exposes_primary_display_telemetry(self) -> None:
        """Verifies authenticated status payload exposes live PC primary display width, height, and refresh Hz."""
        from laptop_gaming_agent import DisplayRefreshRateController, LaptopGamingAgentApp

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            cfg_file.write_text("gaming_pc_token=" + ("f" * 64) + "\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            with mock.patch.object(
                DisplayRefreshRateController,
                "get_primary_display_mode",
                return_value=(2560, 1440, 120),
            ):
                app = LaptopGamingAgentApp(
                    games_root=tmp_dir / "Games",
                    steam_root=tmp_dir / "Steam",
                    state_dir=tmp_dir / "state",
                    auth_manager=auth_mgr,
                )
                payload = app.build_status_payload(include_hidden=True, fast=False, authenticated=True)
            self.assertEqual(payload.get("primaryDisplayWidth"), 2560)
            self.assertEqual(payload.get("primaryDisplayHeight"), 1440)
            self.assertEqual(payload.get("primaryDisplayRefreshHz"), 120)

    def test_epic_manifest_parsing_catalog_discovery_and_deduplication(self) -> None:
        """Verifies Epic .item parsing, DLC/incomplete filtering, standalone deduplication, and Epic process matching."""
        from laptop_gaming_agent import (
            EpicPreparationManager,
            GameCatalogScanner,
            PrivacyCurtainManager,
            SunshineConfigManager,
        )

        hogwarts_manifest = json.dumps(
            {
                "bIsIncompleteInstall": False,
                "bIsApplication": True,
                "AppCategories": ["public", "games", "applications"],
                "DisplayName": "Hogwarts Legacy",
                "InstallLocation": "",  # filled below
                "LaunchExecutable": "HogwartsLegacy.exe",
                "CatalogNamespace": "e97659b501af4e3981d5430dad170911",
                "CatalogItemId": "864c7bc2c2394f7dbd1b534aa068ff56",
                "AppName": "fa4240e57a3c46b39f169041b7811293",
                "MainGameAppName": "fa4240e57a3c46b39f169041b7811293",
            }
        )
        dlc_manifest = json.dumps(
            {
                "bIsIncompleteInstall": False,
                "bIsApplication": True,
                "AppCategories": ["public", "games"],
                "DisplayName": "Hogwarts Legacy Dark Arts Pack",
                "InstallLocation": r"C:\Games\HogwartsLegacy",
                "LaunchExecutable": "",
                "CatalogNamespace": "e97659b501af4e3981d5430dad170911",
                "CatalogItemId": "dlc111",
                "AppName": "dlc_app_name",
                "MainGameAppName": "fa4240e57a3c46b39f169041b7811293",
            }
        )
        incomplete_manifest = json.dumps(
            {
                "bIsIncompleteInstall": True,
                "bIsApplication": True,
                "AppCategories": ["public", "games"],
                "DisplayName": "Half Downloaded Game",
                "InstallLocation": r"C:\Games\HalfGame",
                "LaunchExecutable": "HalfGame.exe",
                "CatalogNamespace": "ns1",
                "CatalogItemId": "it1",
                "AppName": "half1",
                "MainGameAppName": "half1",
            }
        )
        self.assertIsNone(EpicPreparationManager.parse_epic_manifest(dlc_manifest))
        self.assertIsNone(EpicPreparationManager.parse_epic_manifest(incomplete_manifest))

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            games_root = root / "Games"
            steam_root = games_root / "Steam"
            manifests_dir = root / "EpicManifests"
            hl_dir = games_root / "HogwartsLegacy"
            (hl_dir / "Phoenix" / "Binaries" / "Win64").mkdir(parents=True)
            (hl_dir / "HogwartsLegacy.exe").write_bytes(b"MZ" + b"\x00" * 512)
            (hl_dir / "Phoenix" / "Binaries" / "Win64" / "HogwartsLegacy-Win64-Shipping.exe").write_bytes(
                b"MZ" + b"\x00" * 4096
            )
            worms_dir = games_root / "Worms Armageddon"
            worms_dir.mkdir(parents=True)
            (worms_dir / "WA.exe").write_bytes(b"MZ" + b"\x00" * 512)
            manifests_dir.mkdir(parents=True)

            m_data = json.loads(hogwarts_manifest)
            m_data["InstallLocation"] = str(hl_dir)
            (manifests_dir / "1F6A.item").write_text(json.dumps(m_data), encoding="utf-8")
            (manifests_dir / "DLC.item").write_text(dlc_manifest, encoding="utf-8")
            (manifests_dir / "INC.item").write_text(incomplete_manifest, encoding="utf-8")

            scanner = GameCatalogScanner(
                games_root=games_root,
                steam_root=steam_root,
                epic_manifests_dir=manifests_dir,
            )
            discovered = scanner.discover_games()
            hl_entries = [g for g in discovered if "hogwarts" in g.title.lower()]
            self.assertEqual(len(hl_entries), 1, f"Hogwarts must be deduplicated into single Epic entry: {hl_entries}")
            hl = hl_entries[0]
            self.assertEqual(hl.category, "epic")
            self.assertEqual(hl.game_id, "epic_fa4240e57a3c46b39f169041b7811293")
            self.assertEqual(
                hl.launch_cmd,
                "com.epicgames.launcher://apps/e97659b501af4e3981d5430dad170911"
                "%3A864c7bc2c2394f7dbd1b534aa068ff56"
                "%3Afa4240e57a3c46b39f169041b7811293?action=launch&silent=true",
            )
            # Standalone game still discovered cleanly alongside Epic
            self.assertTrue(any(g.game_id == "exe_worms_armageddon" and g.category == "standalone" for g in discovered))

            # Privacy curtain matches root + UE shipping exe, and rejects Epic launcher windows
            expected = PrivacyCurtainManager.resolve_expected_process_names(hl)
            self.assertIn("hogwartslegacy.exe", expected)
            self.assertIn("hogwartslegacy-win64-shipping.exe", expected)
            self.assertFalse(
                PrivacyCurtainManager.is_game_foreground_window(
                    "EpicGamesLauncher.exe", "UnrealWindow", "Epic Games Launcher", 1280, 720, target_game=hl
                )
            )
            self.assertTrue(
                PrivacyCurtainManager.is_game_foreground_window(
                    "HogwartsLegacy.exe", "UnrealWindow", "Hogwarts Legacy", 1920, 1080, target_game=hl
                )
            )

            # Sunshine payload for Epic uses detached URI launch, never quoted cmd
            payload = SunshineConfigManager.build_sunshine_app_payload(hl, index=-1)
            self.assertEqual(payload["cmd"], "")
            self.assertFalse(payload["auto-detach"])
            self.assertTrue(any("com.epicgames.launcher://" in d for d in payload["detached"]))

    def test_selective_launcher_isolation_steam_vs_epic_vs_standalone(self) -> None:
        """Verifies Steam games only open Steam, Epic games only open Epic, and standalone games open neither."""
        from laptop_gaming_agent import (
            DisplayModeController,
            EpicPreparationManager,
            GameEntry,
            LaptopGamingAgentApp,
            SteamPreparationManager,
            SunshineConfigManager,
            WindowsSessionController,
            create_http_server,
        )

        steam_g = GameEntry("steam_965680", "Boomerang Fu", "steam", "steam://rungameid/965680", "")
        epic_g = GameEntry(
            "epic_fa4240",
            "Hogwarts Legacy",
            "epic",
            "com.epicgames.launcher://apps/ns%3Aitem%3Afa4240?action=launch&silent=true",
            r"C:\Games\HogwartsLegacy",
            exe_name="HogwartsLegacy.exe",
        )
        standalone_g = GameEntry(
            "exe_worms",
            "Worms Armageddon",
            "standalone",
            r"C:\Games\Worms Armageddon\WA.exe",
            r"C:\Games\Worms Armageddon",
        )
        desktop_g = GameEntry("desktop", "Windows Desktop", "system", "explorer.exe", "")

        self.assertEqual(WindowsSessionController.classify_game_launcher(steam_g), "steam")
        self.assertEqual(WindowsSessionController.classify_game_launcher(epic_g), "epic")
        self.assertEqual(WindowsSessionController.classify_game_launcher(standalone_g), "none")
        self.assertEqual(WindowsSessionController.classify_game_launcher(desktop_g), "none")

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            preseeded = "a" * 64
            cfg_file.write_text(f"gaming_pc_token={preseeded}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app._cached_all_games = [desktop_g, steam_g, epic_g, standalone_g]
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                create_native_window=False,
                safety_net=_make_mock_safety_net(),
                close_sunshine_app_fn=lambda: True,
            )

            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                def post_launch(gid: str) -> int:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    conn.request(
                        "POST",
                        "/api/gaming/launch",
                        body=json.dumps(
                            {
                                "gameId": gid,
                                "connectionMode": "auto",
                                "prelaunchBeforeStream": True,
                                "userApproved": True,
                            }
                        ).encode("utf-8"),
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": preseeded},
                    )
                    resp = conn.getresponse()
                    resp.read()
                    conn.close()
                    return resp.status

                with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                     mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(app.sunshine_lifecycle, "ensure_running", return_value=True) as mock_sun_start, \
                     mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True) as mock_steam_prep, \
                     mock.patch.object(EpicPreparationManager, "prepare_for_stream", return_value=True) as mock_epic_prep, \
                     mock.patch.object(WindowsSessionController, "launch_game", return_value=True):

                    # 1. Launching Epic game -> ONLY Epic prepared, NEVER Steam!
                    self.assertEqual(post_launch("epic_fa4240"), 200)
                    self.assertEqual(mock_epic_prep.call_count, 1)
                    self.assertEqual(mock_steam_prep.call_count, 0)
                    self.assertGreaterEqual(mock_sun_start.call_count, 1)

                    # 2. Launching Steam game -> ONLY Steam prepared, NEVER Epic!
                    mock_epic_prep.reset_mock()
                    mock_steam_prep.reset_mock()
                    self.assertEqual(post_launch("steam_965680"), 200)
                    self.assertEqual(mock_steam_prep.call_count, 1)
                    self.assertEqual(mock_epic_prep.call_count, 0)

                    # 3. Launching Standalone game -> NEITHER Steam NOR Epic prepared!
                    mock_epic_prep.reset_mock()
                    mock_steam_prep.reset_mock()
                    self.assertEqual(post_launch("exe_worms"), 200)
                    self.assertEqual(mock_steam_prep.call_count, 0)
                    self.assertEqual(mock_epic_prep.call_count, 0)
            finally:
                app.privacy_curtain.stop()
                server.shutdown()
                server.server_close()

        # 4. Cold-start Epic retry fires single-shot relaunch once Epic is ready if game hasn't spawned
        epic_retries: List[str] = []
        epic_ready = [False]
        curtain = PrivacyCurtainManager(
            cursor_controller=_make_mock_cursor_controller(),
            esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
            safety_net=_make_mock_safety_net(),
            find_game_hwnd_fn=lambda _g: 0,
            process_snapshot_fn=lambda: {100: "epicgameslauncher.exe"},
            close_sunshine_app_fn=lambda: True,
            create_native_window=False,
            poll_interval_sec=0.02,
            connect_timeout_sec=5.0,
            epic_cold_retry_delay_sec=0.08,
            is_epic_ready_fn=lambda: epic_ready[0],
            epic_retry_launch_fn=lambda g: epic_retries.append(g.game_id) or True,
        )
        try:
            self.assertTrue(curtain.start_for_game(epic_g, use_hdmi=False, prelaunched=True))
            time.sleep(0.12)
            self.assertEqual(epic_retries, [], "Must not retry Epic before Epic launcher is ready")
            epic_ready[0] = True
            time.sleep(0.14)
            self.assertEqual(epic_retries, ["epic_fa4240"], "Must issue single-shot Epic cold retry once ready")
        finally:
            curtain.stop()

    def test_sunshine_lifecycle_controller_and_unified_idle_shutdown(self) -> None:
        """Verifies Sunshine SDDL builder, on-demand start/stop, unified Steam+Epic+Sunshine idle shutdown, and status fields."""
        from laptop_gaming_agent import (
            GamingIdleShutdownManager,
            LaptopGamingAgentApp,
            SunshineLifecycleController,
        )

        raw_sddl = (
            "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)"
            "(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)"
            "(A;;CCLCSWLOCRRC;;;IU)"
            "(A;;CCLCSWLOCRRC;;;SU)"
        )
        updated_sddl = SunshineLifecycleController.build_service_sddl_with_interactive_control(raw_sddl)
        self.assertIn("(A;;CCLCSWLOCRRCRPWP;;;IU)", updated_sddl)
        self.assertIn("(A;;CCLCSWRPWPDTLOCRRC;;;SY)", updated_sddl)
        # Idempotent if already granted
        self.assertEqual(
            SunshineLifecycleController.build_service_sddl_with_interactive_control(updated_sddl),
            updated_sddl,
        )

        # On-demand ensure_running starts stopped Sunshine and polls until ready
        sun_state = {"running": False, "started": 0, "stopped": 0, "closed": 0}
        ctrl = SunshineLifecycleController(
            is_running_fn=lambda: sun_state["running"],
            is_port_ready_fn=lambda: sun_state["running"],
            start_service_fn=lambda: sun_state.__setitem__("started", sun_state["started"] + 1)
            or sun_state.__setitem__("running", True)
            or True,
            stop_service_fn=lambda: sun_state.__setitem__("stopped", sun_state["stopped"] + 1)
            or sun_state.__setitem__("running", False)
            or True,
            close_active_app_fn=lambda: sun_state.__setitem__("closed", sun_state["closed"] + 1) or True,
            poll_interval_sec=0.01,
        )
        self.assertTrue(ctrl.ensure_running(timeout_sec=1.0))
        self.assertEqual(sun_state["started"], 1)
        self.assertTrue(ctrl.ensure_running(timeout_sec=1.0))
        self.assertEqual(sun_state["started"], 1, "ensure_running is no-op when already running")
        self.assertTrue(ctrl.stop_sunshine(timeout_sec=1.0))
        self.assertEqual(sun_state["closed"], 1)
        self.assertEqual(sun_state["stopped"], 1)
        self.assertFalse(sun_state["running"])

        # Native SCM control is fail-closed under automated tests
        self.assertFalse(SunshineLifecycleController._native_control_service("SunshineService", start=False))
        self.assertFalse(SunshineLifecycleController._native_control_service("SunshineService", start=True))

        # Unified idle shutdown closes Steam, Epic, AND Sunshine after grace and self-terminates
        closed_targets: List[str] = []
        mgr = GamingIdleShutdownManager(
            grace_seconds=0.06,
            is_steam_running_fn=lambda: True,
            is_epic_running_fn=lambda: True,
            is_sunshine_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: closed_targets.append("steam") or True,
            shutdown_epic_fn=lambda: closed_targets.append("epic") or True,
            shutdown_sunshine_fn=lambda: closed_targets.append("sunshine") or True,
        )
        self.assertTrue(mgr.schedule_shutdown(reason="game_exit"))
        worker = mgr._thread
        self.assertIsNotNone(worker)
        time.sleep(0.20)
        self.assertEqual(closed_targets, ["steam", "epic", "sunshine"])
        self.assertFalse(mgr.is_pending())
        assert worker is not None
        worker.join(timeout=0.5)
        self.assertFalse(worker.is_alive())

        # Schedules even if ONLY Epic or ONLY Sunshine is running (Steam closed)
        only_epic_sun: List[str] = []
        mgr_epic_only = GamingIdleShutdownManager(
            grace_seconds=0.05,
            is_steam_running_fn=lambda: False,
            is_epic_running_fn=lambda: True,
            is_sunshine_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: only_epic_sun.append("steam") or True,
            shutdown_epic_fn=lambda: only_epic_sun.append("epic") or True,
            shutdown_sunshine_fn=lambda: only_epic_sun.append("sunshine") or True,
        )
        self.assertTrue(mgr_epic_only.schedule_shutdown(reason="epic_exit"))
        time.sleep(0.16)
        self.assertEqual(only_epic_sun, ["epic", "sunshine"])

        # Status payload reports live steamRunning, epicRunning, sunshineRunning, sunshinePowerControlReady
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            cfg_file.write_text("gaming_pc_token=" + ("d" * 64) + "\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            with mock.patch.object(Win32SystemProbes, "is_process_running", side_effect=lambda name: name == "epicgameslauncher.exe"), \
                 mock.patch.object(WindowsSessionController, "is_sunshine_running", return_value=True), \
                 mock.patch.object(app.sunshine_lifecycle, "can_control_service", return_value=True):
                payload = app.build_status_payload(include_hidden=True, fast=False, authenticated=True)
            self.assertFalse(payload.get("steamRunning"))
            self.assertTrue(payload.get("epicRunning"))
            self.assertTrue(payload.get("sunshineRunning"))
            self.assertTrue(payload.get("sunshinePowerControlReady"))

    def test_heroic_all_stores_discovery_priority_dedup_and_silent_launch(self) -> None:
        """Verifies Heroic (Epic/GOG/Amazon/Sideload) + standalone Legendary discovery, priority over Epic, and --no-gui launch."""
        from laptop_gaming_agent import (
            GameCatalogScanner,
            HeroicPreparationManager,
            PrivacyCurtainManager,
            SunshineConfigManager,
            WindowsSessionController,
        )

        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            games_root = tmp_dir / "Games"
            steam_root = games_root / "Steam"
            heroic_install_dir = games_root / "Heroic"
            heroic_install_dir.mkdir(parents=True)
            (heroic_install_dir / "Heroic.exe").write_bytes(b"MZ" + b"\x00" * 512)
            hogwarts_dir = games_root / "HogwartsLegacy"
            (hogwarts_dir / "Phoenix" / "Binaries" / "Win64").mkdir(parents=True)
            (hogwarts_dir / "HogwartsLegacy.exe").write_bytes(b"MZ" + b"\x00" * 128)
            (hogwarts_dir / "Phoenix" / "Binaries" / "Win64" / "HogwartsLegacy.exe").write_bytes(
                b"MZ" + b"\x00" * 1024
            )
            gog_dir = games_root / "Cyberpunk2077"
            gog_dir.mkdir(parents=True)
            (gog_dir / "Cyberpunk2077.exe").write_bytes(b"MZ" + b"\x00" * 256)
            nile_dir = games_root / "FalloutNile"
            nile_dir.mkdir(parents=True)
            (nile_dir / "Fallout.exe").write_bytes(b"MZ" + b"\x00" * 256)
            side_dir = games_root / "CustomIndie"
            side_dir.mkdir(parents=True)
            (side_dir / "Indie.exe").write_bytes(b"MZ" + b"\x00" * 256)
            epic_only_dir = games_root / "AlanWake2"
            epic_only_dir.mkdir(parents=True)
            (epic_only_dir / "AlanWake2.exe").write_bytes(b"MZ" + b"\x00" * 256)

            heroic_cfg = tmp_dir / "appdata_heroic"
            (heroic_cfg / "legendaryConfig" / "legendary").mkdir(parents=True)
            (heroic_cfg / "legendaryConfig" / "legendary" / "installed.json").write_text(
                json.dumps(
                    {
                        "fa4240e57a3c46b39f169041b7811293": {
                            "app_name": "fa4240e57a3c46b39f169041b7811293",
                            "title": "Hogwarts Legacy",
                            "install_path": str(hogwarts_dir),
                            "executable": "HogwartsLegacy.exe",
                            "is_dlc": False,
                        },
                        "dlc_pack": {
                            "app_name": "dlc_pack",
                            "title": "Dark Arts DLC",
                            "install_path": str(hogwarts_dir),
                            "executable": "",
                            "is_dlc": True,
                        },
                    }
                ),
                encoding="utf-8",
            )
            (heroic_cfg / "gog_store").mkdir(parents=True)
            (heroic_cfg / "gog_store" / "installed.json").write_text(
                json.dumps(
                    {
                        "installed": [
                            {
                                "appName": "1423049311",
                                "install_path": str(gog_dir),
                                "is_dlc": False,
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            (heroic_cfg / "gog_store" / "library.json").write_text(
                json.dumps({"games": [{"app_name": "1423049311", "title": "Cyberpunk 2077"}]}),
                encoding="utf-8",
            )
            (heroic_cfg / "nile_config" / "nile").mkdir(parents=True)
            (heroic_cfg / "nile_config" / "nile" / "installed.json").write_text(
                json.dumps([{"id": "amzn1.adg.product.test", "path": str(nile_dir)}]),
                encoding="utf-8",
            )
            (heroic_cfg / "nile_store").mkdir(parents=True)
            (heroic_cfg / "nile_store" / "library.json").write_text(
                json.dumps({"library": [{"app_name": "amzn1.adg.product.test", "title": "Fallout Amazon"}]}),
                encoding="utf-8",
            )
            (heroic_cfg / "sideload_apps").mkdir(parents=True)
            (heroic_cfg / "sideload_apps" / "library.json").write_text(
                json.dumps(
                    {
                        "games": [
                            {
                                "app_name": "indie_hash_1",
                                "title": "Custom Indie",
                                "install": {"executable": str(side_dir / "Indie.exe")},
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            # Official Epic manifests containing BOTH Hogwarts Legacy (imported into Heroic) and Alan Wake 2 (Epic-only)
            epic_manifests = tmp_dir / "EpicManifests"
            epic_manifests.mkdir(parents=True)
            (epic_manifests / "hogwarts.item").write_text(
                json.dumps(
                    {
                        "bIsIncompleteInstall": False,
                        "bIsApplication": True,
                        "AppCategories": ["public", "games"],
                        "DisplayName": "Hogwarts Legacy",
                        "InstallLocation": str(hogwarts_dir),
                        "LaunchExecutable": "HogwartsLegacy.exe",
                        "CatalogNamespace": "ns1",
                        "CatalogItemId": "item1",
                        "AppName": "fa4240e57a3c46b39f169041b7811293",
                        "MainGameAppName": "fa4240e57a3c46b39f169041b7811293",
                    }
                ),
                encoding="utf-8",
            )
            (epic_manifests / "alanwake2.item").write_text(
                json.dumps(
                    {
                        "bIsIncompleteInstall": False,
                        "bIsApplication": True,
                        "AppCategories": ["public", "games"],
                        "DisplayName": "Alan Wake 2",
                        "InstallLocation": str(epic_only_dir),
                        "LaunchExecutable": "AlanWake2.exe",
                        "CatalogNamespace": "ns2",
                        "CatalogItemId": "item2",
                        "AppName": "aw2_app",
                        "MainGameAppName": "aw2_app",
                    }
                ),
                encoding="utf-8",
            )

            scanner = GameCatalogScanner(
                games_root=games_root,
                steam_root=steam_root,
                epic_manifests_dir=epic_manifests,
                heroic_config_dir=heroic_cfg,
                legendary_config_dir=tmp_dir / "no_standalone_legendary",
            )
            discovered = scanner.discover_games()
            by_id = {g.game_id: g for g in discovered}

            # 1. C:\Games\Heroic launcher folder is NOT falsely listed as a game
            self.assertNotIn("exe_heroic", by_id)

            # 2. Hogwarts Legacy prefers Heroic over official Epic & is never duplicated
            hogwarts_matches = [g for g in discovered if g.title == "Hogwarts Legacy"]
            self.assertEqual(len(hogwarts_matches), 1)
            hw = hogwarts_matches[0]
            self.assertEqual(hw.category, "heroic")
            self.assertEqual(
                hw.launch_cmd,
                "heroic://launch?appName=fa4240e57a3c46b39f169041b7811293&runner=legendary",
            )
            self.assertEqual(WindowsSessionController.classify_game_launcher(hw), "heroic")
            self.assertNotIn("epic_fa4240e57a3c46b39f169041b7811293", by_id)
            self.assertNotIn("exe_hogwartslegacy", by_id)

            # 3. All 4 Heroic stores discovered and deduplicated against standalone folders
            self.assertIn("heroic_gog_1423049311", by_id)
            self.assertEqual(by_id["heroic_gog_1423049311"].title, "Cyberpunk 2077")
            self.assertNotIn("exe_cyberpunk2077", by_id)
            self.assertIn("heroic_nile_amzn1_adg_product_test", by_id)
            self.assertEqual(by_id["heroic_nile_amzn1_adg_product_test"].title, "Fallout Amazon")
            self.assertIn("heroic_sideload_indie_hash_1", by_id)
            self.assertEqual(by_id["heroic_sideload_indie_hash_1"].title, "Custom Indie")

            # 4. Epic-only game not imported into Heroic falls back cleanly to official Epic Launcher
            self.assertIn("epic_aw2_app", by_id)
            self.assertEqual(by_id["epic_aw2_app"].category, "epic")
            self.assertEqual(WindowsSessionController.classify_game_launcher(by_id["epic_aw2_app"]), "epic")

            # 5. Silent --no-gui launch args & Sunshine payload & PrivacyCurtain process exclusion
            heroic_exe = heroic_install_dir / "Heroic.exe"
            args = HeroicPreparationManager.build_silent_launch_args(hw, heroic_exe=heroic_exe)
            self.assertEqual(args, [str(heroic_exe), "--no-gui", hw.launch_cmd])
            sun_payload = SunshineConfigManager.build_sunshine_app_payload(hw, heroic_exe=heroic_exe)
            self.assertEqual(sun_payload["cmd"], "")
            self.assertEqual(sun_payload["detached"], [f'"{heroic_exe}" --no-gui "{hw.launch_cmd}"'])
            self.assertIn("heroic.exe", PrivacyCurtainManager.NON_GAME_PROCESS_NAMES)
            self.assertIn("hogwartslegacy.exe", PrivacyCurtainManager.resolve_expected_process_names(hw))

            # 6. Standalone Legendary CLI discovery works when Heroic is absent
            standalone_leg_cfg = tmp_dir / "standalone_legendary"
            standalone_leg_cfg.mkdir(parents=True)
            (standalone_leg_cfg / "installed.json").write_text(
                json.dumps(
                    {
                        "control_app": {
                            "app_name": "control_app",
                            "title": "Control",
                            "install_path": str(tmp_dir / "Control"),
                            "executable": "Control.exe",
                            "is_dlc": False,
                        }
                    }
                ),
                encoding="utf-8",
            )
            leg_entries = HeroicPreparationManager.discover_heroic_games(
                heroic_config_dir=tmp_dir / "missing_heroic",
                legendary_config_dir=standalone_leg_cfg,
            )
            self.assertEqual(len(leg_entries), 1)
            self.assertEqual(leg_entries[0].category, "legendary")
            self.assertEqual(leg_entries[0].launch_cmd, "legendary://launch/control_app")
            self.assertEqual(WindowsSessionController.classify_game_launcher(leg_entries[0]), "legendary")
            leg_exe = tmp_dir / "legendary.exe"
            leg_exe.write_bytes(b"MZ")
            self.assertEqual(
                HeroicPreparationManager.build_silent_launch_args(leg_entries[0], legendary_exe=leg_exe),
                [str(leg_exe), "launch", "control_app"],
            )

    def test_heroic_isolation_idle_shutdown_and_sunshine_start_stop_endpoints(self) -> None:
        """Verifies Heroic single-launcher isolation, 4-target idle shutdown, GameStream port check, and /api/gaming/sunshine/start."""
        from laptop_gaming_agent import (
            EpicPreparationManager,
            GamingIdleShutdownManager,
            HeroicPreparationManager,
            LaptopGamingAgentApp,
            PrivacyCurtainManager,
            SteamPreparationManager,
            SunshineConfigManager,
            SunshineLifecycleController,
            WindowsSessionController,
            create_http_server,
        )

        # 1. Idle shutdown closes Steam + Epic + Heroic + Sunshine together
        closed: List[str] = []
        mgr = GamingIdleShutdownManager(
            grace_seconds=0.05,
            is_steam_running_fn=lambda: True,
            is_epic_running_fn=lambda: True,
            is_heroic_running_fn=lambda: True,
            is_sunshine_running_fn=lambda: True,
            is_session_active_fn=lambda: False,
            is_any_game_running_fn=lambda: False,
            shutdown_steam_fn=lambda: closed.append("steam") or True,
            shutdown_epic_fn=lambda: closed.append("epic") or True,
            shutdown_heroic_fn=lambda: closed.append("heroic") or True,
            shutdown_sunshine_fn=lambda: closed.append("sunshine") or True,
        )
        self.assertTrue(mgr.schedule_shutdown(reason="heroic_exit"))
        time.sleep(0.18)
        self.assertEqual(closed, ["steam", "epic", "heroic", "sunshine"])

        # 2. Default SunshineLifecycleController port check requires BOTH GameStream ports (47989 & 47984)
        port_calls: List[int] = []
        with mock.patch.object(
            Win32SystemProbes,
            "_can_connect_local_port",
            side_effect=lambda _host, port: port_calls.append(port) or (port == 47989),
        ):
            ctrl_partial = SunshineLifecycleController(is_running_fn=lambda: True)
            self.assertFalse(
                ctrl_partial._is_port_ready_fn(),
                "Port 47989 alone without mTLS port 47984 must NOT report stream-ready",
            )
            self.assertEqual(port_calls, [47989, 47984])

        # 3. HTTP endpoints: Heroic launch isolates from Steam/Epic; /api/gaming/sunshine/start wakes Sunshine & cancels idle shutdown
        heroic_g = GameEntry(
            "heroic_legendary_hw",
            "Hogwarts Legacy",
            "heroic",
            "heroic://launch?appName=fa4240e57a3c46b39f169041b7811293&runner=legendary",
            r"C:\Games\HogwartsLegacy",
            exe_name="HogwartsLegacy.exe",
        )
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg_file = tmp_dir / "config.properties"
            token = "e" * 64
            cfg_file.write_text(f"gaming_pc_token={token}\n", encoding="utf-8")
            auth_mgr = AgentAuthManager(
                auth_path=tmp_dir / "auth.json",
                pair_code_path=tmp_dir / "pair_code.txt",
                config_properties_path=cfg_file,
                dotenv_path=tmp_dir / "missing.env",
            )
            app = LaptopGamingAgentApp(
                games_root=tmp_dir / "Games",
                steam_root=tmp_dir / "Steam",
                state_dir=tmp_dir / "state",
                auth_manager=auth_mgr,
            )
            app._cached_all_games = [heroic_g]
            app.privacy_curtain = PrivacyCurtainManager(
                cursor_controller=_make_mock_cursor_controller(),
                esc_listener=PhysicalEscAbortListener(key_state_fn=lambda _vk: 0),
                create_native_window=False,
                safety_net=_make_mock_safety_net(),
                close_sunshine_app_fn=lambda: True,
            )
            server = create_http_server(app=app, host="127.0.0.1", port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                def call_json(method: str, path: str, body: Optional[Dict[str, Any]] = None) -> Tuple[int, Dict[str, Any]]:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
                    raw = json.dumps(body).encode("utf-8") if body is not None else b""
                    conn.request(
                        method,
                        path,
                        body=raw,
                        headers={"Content-Type": "application/json", "X-SoarTV-Token": token},
                    )
                    resp = conn.getresponse()
                    data = json.loads(resp.read().decode("utf-8"))
                    conn.close()
                    return resp.status, data

                with mock.patch.object(WindowsSessionController, "wake_from_standby", return_value=True), \
                     mock.patch.object(DisplayModeController, "is_external_display_connected", return_value=False), \
                     mock.patch.object(SunshineConfigManager, "close_active_app", return_value=True), \
                     mock.patch.object(SunshineConfigManager, "sync_via_api", return_value=1), \
                     mock.patch.object(app.sunshine_lifecycle, "ensure_running", return_value=True) as m_start, \
                     mock.patch.object(app.sunshine_lifecycle, "stop_sunshine", return_value=True) as m_stop, \
                     mock.patch.object(SteamPreparationManager, "prepare_for_stream", return_value=True) as m_steam, \
                     mock.patch.object(EpicPreparationManager, "prepare_for_stream", return_value=True) as m_epic, \
                     mock.patch.object(HeroicPreparationManager, "prepare_for_stream", return_value=True) as m_heroic, \
                     mock.patch.object(WindowsSessionController, "launch_game", return_value=True):

                    # Launch Heroic game -> ONLY Heroic prepared, NEVER Steam or Epic!
                    st_code, _ = call_json(
                        "POST",
                        "/api/gaming/launch",
                        {"gameId": "heroic_legendary_hw", "connectionMode": "auto", "prelaunchBeforeStream": True, "userApproved": True},
                    )
                    self.assertEqual(st_code, 200)
                    self.assertEqual(m_heroic.call_count, 1)
                    self.assertEqual(m_steam.call_count, 0)
                    self.assertEqual(m_epic.call_count, 0)

                    # Explicit POST /api/gaming/sunshine/start starts Sunshine & cancels pending idle shutdown
                    m_start.reset_mock()
                    st_start, body_start = call_json("POST", "/api/gaming/sunshine/start", {})
                    self.assertEqual(st_start, 200)
                    self.assertTrue(body_start.get("ok"))
                    self.assertTrue(body_start.get("sunshineRunning"))
                    self.assertEqual(m_start.call_count, 1)

                    # Explicit POST /api/gaming/sunshine/stop stops Sunshine cleanly
                    st_stop, body_stop = call_json("POST", "/api/gaming/sunshine/stop", {})
                    self.assertEqual(st_stop, 200)
                    self.assertTrue(body_stop.get("ok"))
                    self.assertFalse(body_stop.get("sunshineRunning"))
                    self.assertEqual(m_stop.call_count, 1)

                with mock.patch.object(Win32SystemProbes, "is_process_running", side_effect=lambda name: name == "heroic.exe"), \
                     mock.patch.object(WindowsSessionController, "is_sunshine_running", return_value=False), \
                     mock.patch.object(app.sunshine_lifecycle, "can_control_service", return_value=True):
                    status_payload = app.build_status_payload(include_hidden=True, fast=False, authenticated=True)
                self.assertTrue(status_payload.get("heroicRunning"))
                self.assertFalse(status_payload.get("steamRunning"))
                self.assertFalse(status_payload.get("epicRunning"))
                self.assertFalse(status_payload.get("sunshineRunning"))
                self.assertTrue(status_payload.get("sunshinePowerControlReady"))
            finally:
                app.privacy_curtain.stop()
                server.shutdown()
                server.server_close()

    def test_multi_folder_games_dirs_and_list_monitors_cli(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root_a = Path(tmp) / "DriveA_Games"
            root_b = Path(tmp) / "DriveB_PCGames"
            (root_a / "Celeste").mkdir(parents=True)
            (root_a / "Celeste" / "Celeste.exe").write_bytes(b"MZ" + b"\x00" * 512)
            (root_b / "HollowKnight").mkdir(parents=True)
            (root_b / "HollowKnight" / "Hollow Knight.exe").write_bytes(b"MZ" + b"\x00" * 512)

            with mock.patch.dict(os.environ, {"SOARTV_GAMES_DIRS": f"{root_a};{root_b}"}):
                resolved_dirs = agent_mod.resolve_default_games_dirs()
                self.assertEqual(resolved_dirs, [root_a, root_b])
                scanner = GameCatalogScanner(steam_root=Path(tmp) / "EmptySteam")
                self.assertEqual(scanner.games_dirs, [root_a, root_b])
                titles = [g.title for g in scanner.discover_games()]
                self.assertTrue(any("Celeste" in t for t in titles))
                self.assertTrue(any("Hollow" in t for t in titles))

            sample_monitors = [
                {
                    "friendlyName": "DeskMonitor24",
                    "devicePath": r"\\?\DISPLAY#MON1234#1",
                    "available": True,
                    "isInternalPanel": False,
                    "consideredTvDisplay": False,
                }
            ]
            buf = io.StringIO()
            with mock.patch.object(DisplayModeController, "list_connected_displays", return_value=sample_monitors), \
                 mock.patch("sys.stdout", buf), \
                 mock.patch.object(sys, "argv", ["laptop_gaming_agent.py", "--list-monitors"]):
                rc = agent_mod.main()
            self.assertEqual(rc, 0)
            parsed = json.loads(buf.getvalue())
            self.assertEqual(parsed["monitors"][0]["friendlyName"], "DeskMonitor24")

    def test_multi_tv_beacon_and_supabase_host_reporter_v2_ip_resilience(self) -> None:
        from laptop_gaming_agent import SupabaseHostReporter, TvBeaconNotifier

        with tempfile.TemporaryDirectory() as tmp:
            store = Path(tmp) / "tvs.json"
            beacon = TvBeaconNotifier(endpoint_store_path=store)
            beacon.record_authenticated_tv("192.168.50.11", "8484", "tv-living-uuid")
            beacon.record_authenticated_tv("192.168.50.12", "8484", "tv-bedroom-uuid")
            # Living Room TV changes IP via DHCP (.11 -> .44) -> updates existing TV UUID entry cleanly
            beacon.record_authenticated_tv("192.168.50.44", "8484", "tv-living-uuid")

            endpoints = beacon.load_all_saved_tv_endpoints()
            self.assertIn(("192.168.50.44", 8484), endpoints)
            self.assertIn(("192.168.50.12", 8484), endpoints)
            self.assertNotIn(("192.168.50.11", 8484), endpoints)

            # Verify notify_ip_changed reaches ALL saved household TVs
            notified_urls: List[str] = []

            class _FakeResp:
                status = 200

                def __enter__(self) -> "_FakeResp":
                    return self

                def __exit__(self, *_a: Any) -> bool:
                    return False

            def fake_urlopen(req: Any, timeout: float = 1.5) -> _FakeResp:
                notified_urls.append(req.full_url)
                return _FakeResp()

            with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
                self.assertTrue(beacon.notify_ip_changed("192.168.50.99", agent_port=8485))
            self.assertEqual(len(notified_urls), 2)
            self.assertIn("http://192.168.50.44:8484/api/gaming/pc-announce", notified_urls)
            self.assertIn("http://192.168.50.12:8484/api/gaming/pc-announce", notified_urls)

            # SupabaseHostReporter builds v2 gaming_hosts payload and sends Dual-Path headers
            reporter = SupabaseHostReporter(
                supabase_url="https://demo-proj.supabase.co/",
                supabase_anon_key="anon_key_v2",
                household_key="household_key_0123456789abcdef",
            )
            self.assertTrue(reporter.is_configured())
            captured_reqs: List[Any] = []

            def fake_sb_urlopen(req: Any, timeout: float = 4.0) -> _FakeResp:
                captured_reqs.append(req)
                return _FakeResp()

            with mock.patch("urllib.request.urlopen", side_effect=fake_sb_urlopen):
                ok = reporter.report_host(
                    lan_ip="192.168.50.99",
                    agent_port=8485,
                    mac_addresses=["AA:BB:CC:DD:EE:FF"],
                    sunshine_uuid="sun-uuid-777",
                    hostname="GAMING-PC",
                )
            self.assertTrue(ok)
            self.assertEqual(len(captured_reqs), 1)
            req = captured_reqs[0]
            self.assertEqual(req.full_url, "https://demo-proj.supabase.co/rest/v1/gaming_hosts?on_conflict=host_id")
            self.assertEqual(req.get_header("X-soartv-household-key"), "household_key_0123456789abcdef")
            body = json.loads(req.data.decode("utf-8"))
            self.assertEqual(body["host_id"], "sunshine-sun-uuid-777")
            self.assertEqual(body["lan_ip"], "192.168.50.99")
            self.assertEqual(body["mac_addresses"], ["AA:BB:CC:DD:EE:FF"])

            # Unconfigured reporter silently no-ops
            empty_rep = SupabaseHostReporter(
                supabase_url="", supabase_anon_key="", household_key="", repo_root=Path(tmp)
            )
            with mock.patch.dict(os.environ, {"SUPABASE_URL": "", "SUPABASE_ANON_KEY": "", "HOUSEHOLD_KEY": ""}):
                self.assertFalse(empty_rep.is_configured())
                self.assertFalse(empty_rep.report_host("192.168.50.99"))


if __name__ == "__main__":
    unittest.main()
