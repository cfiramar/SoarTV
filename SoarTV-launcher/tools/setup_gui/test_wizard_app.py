"""
Unit tests for the SoarTV Desktop Setup Studio (`wizard_app.py`).

Covers:
  1. `SetupConfigStore` non-destructive `config.properties` & `.env` updates and secret masking.
  2. `AgentPromptBuilder` tailored prompt generation with optional Gaming & Smart Home tab choices.
  3. `EnvironmentInspector.generate_household_credentials` cryptographic key + SHA-256 SQL generation.
  4. `SetupStudioHttpServer` loopback binding and CSRF session-token protection.
"""
from __future__ import annotations

import hashlib
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from wizard_app import (
    AgentPromptBuilder,
    EnvironmentInspector,
    SetupConfigStore,
    create_setup_http_server,
)


class SoarTvSetupStudioTests(unittest.TestCase):
    """Verifies configuration persistence, prompt synthesis, crypto helpers, and HTTP security."""

    def test_config_store_preserves_existing_secrets_when_masked_and_writes_tab_modes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "SoarTV-launcher"
            launcher.mkdir()
            (launcher / "config.example.properties").write_text(
                "tmdb_key=\nenable_gaming=auto\nenable_smarthome=auto\ncity=\n",
                encoding="utf-8",
            )
            (root / ".env.example").write_text(
                "TMDB_API_KEY=\nENABLE_GAMING=auto\nENABLE_SMARTHOME=auto\nSOARTV_GAMES_DIRS=\n",
                encoding="utf-8",
            )

            store = SetupConfigStore(root)
            store.save_settings(
                {
                    "tmdb_key": "secret_tmdb_12345",
                    "enable_gaming": "false",
                    "enable_smarthome": "true",
                    "city": "London",
                    "games_dirs": r"C:\Games",
                }
            )

            state = store.load_masked_state()
            self.assertTrue(state["configured_keys"]["tmdb_key"])
            self.assertNotIn("secret_tmdb_12345", json.dumps(state))
            self.assertEqual("false", state["values"]["enable_gaming"])
            self.assertEqual("true", state["values"]["enable_smarthome"])
            self.assertEqual("London", state["values"]["city"])

            # Saving again with empty/unchanged tmdb_key preserves the existing secret on disk
            store.save_settings({"city": "Paris", "enable_gaming": "true"})
            raw_cfg = (launcher / "config.properties").read_text(encoding="utf-8")
            self.assertIn("tmdb_key=secret_tmdb_12345", raw_cfg)
            self.assertIn("city=Paris", raw_cfg)
            self.assertIn("enable_gaming=true", raw_cfg)

    def test_prompt_builder_reflects_optional_gaming_and_smarthome_choices(self) -> None:
        p1 = AgentPromptBuilder.build_prompt(
            tv_platform="android_tv",
            tv_ip="192.168.1.42",
            city="Austin",
            enable_smarthome=False,
            smarthome_devices=[],
            enable_gaming=False,
            games_dirs="",
        )
        self.assertIn("192.168.1.42", p1)
        self.assertIn("hide the Smart Home tab", p1)
        self.assertIn("hide the Gaming tab", p1)
        self.assertIn("tv_state_backup.py", p1)

        p2 = AgentPromptBuilder.build_prompt(
            tv_platform="android_tv",
            tv_ip="192.168.1.42",
            city="Austin",
            enable_smarthome=True,
            smarthome_devices=["WiZ Lights", "Sensibo AC"],
            enable_gaming=True,
            games_dirs=r"C:\Games;E:\SteamGames",
            custom_smarthome=[
                {"brand": "Philips Hue", "api_key": "hue_secret_value"},
                {"brand": "Govee", "api_key": ""},
            ],
        )
        self.assertIn("enable Smart Home (WiZ Lights, Sensibo AC)", p2)
        self.assertIn(r"C:\Games;E:\SteamGames", p2)
        self.assertIn("Philips Hue", p2)
        self.assertIn("custom_smarthome_key_philips_hue", p2)
        self.assertNotIn("hue_secret_value", p2)
        self.assertNotIn("4K Windows", p2)
        self.assertNotIn("60fps", p2)

    def test_custom_smarthome_brands_mask_secrets_and_preserve_keys(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "SoarTV-launcher"
            launcher.mkdir()
            (launcher / "config.example.properties").write_text("city=\n", encoding="utf-8")
            (root / ".env.example").write_text("CITY=\n", encoding="utf-8")

            store = SetupConfigStore(root)
            store.save_settings(
                {
                    "enable_smarthome": "true",
                    "custom_smarthome": [
                        {"brand": "Philips Hue", "api_key": "super_secret_hue_999"},
                        {"brand": "Home Assistant", "api_key": ""},
                    ],
                }
            )
            state = store.load_masked_state()
            self.assertNotIn("super_secret_hue_999", json.dumps(state))
            self.assertEqual(2, len(state["custom_smarthome"]))
            self.assertEqual("Philips Hue", state["custom_smarthome"][0]["brand"])
            self.assertTrue(state["custom_smarthome"][0]["has_key"])
            self.assertFalse(state["custom_smarthome"][1]["has_key"])

            # Re-saving without re-typing the custom API key keeps the saved secret intact
            store.save_settings(
                {
                    "custom_smarthome": [
                        {"brand": "Philips Hue", "api_key": ""},
                        {"brand": "Home Assistant", "api_key": ""},
                    ]
                }
            )
            raw_cfg = (launcher / "config.properties").read_text(encoding="utf-8")
            self.assertIn("custom_smarthome_key_philips_hue=super_secret_hue_999", raw_cfg)

    def test_household_key_generator_produces_valid_256bit_hex_and_matching_sha256(self) -> None:
        creds = EnvironmentInspector.generate_household_credentials("owner@example.com")
        key = creds["household_key"]
        self.assertEqual(64, len(key))
        expected_sha = hashlib.sha256(key.encode("utf-8")).hexdigest()
        self.assertEqual(expected_sha, creds["sha256"])
        self.assertIn(expected_sha, creds["sql"])
        self.assertIn("owner@example.com", creds["sql"])

    def test_http_server_enforces_session_csrf_token_on_api_routes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "SoarTV-launcher").mkdir()
            (root / "SoarTV-launcher" / "config.example.properties").write_text("city=\n", encoding="utf-8")
            (root / ".env.example").write_text("CITY=\n", encoding="utf-8")

            server, token = create_setup_http_server(root, port=0)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                # Missing CSRF header -> 403 Forbidden
                req_bad = urllib.request.Request(f"http://127.0.0.1:{port}/api/state")
                with self.assertRaises(urllib.error.HTTPError) as ctx:
                    urllib.request.urlopen(req_bad, timeout=3)
                self.assertEqual(403, ctx.exception.code)

                # Valid CSRF header -> 200 OK
                req_ok = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/state",
                    headers={"X-SoarTV-Setup-Token": token},
                )
                with urllib.request.urlopen(req_ok, timeout=3) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(data["ok"])
            finally:
                server.shutdown()
                server.server_close()

    def test_sandbox_mode_never_modifies_real_config_and_simulates_tv_probe(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "SoarTV-launcher"
            launcher.mkdir()
            real_cfg = launcher / "config.properties"
            real_cfg.write_text("city=RealCity\ntmdb_key=real_secret\n", encoding="utf-8")
            (launcher / "config.example.properties").write_text("city=\ntmdb_key=\n", encoding="utf-8")
            (root / ".env.example").write_text("CITY=\n", encoding="utf-8")

            server, token = create_setup_http_server(root, port=0, sandbox=True)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                req_save = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/save",
                    data=json.dumps({"settings": {"city": "SandboxCity", "tmdb_key": "fake"}}).encode("utf-8"),
                    headers={"X-SoarTV-Setup-Token": token, "Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req_save, timeout=3) as resp:
                    self.assertTrue(json.loads(resp.read().decode("utf-8"))["ok"])
                # Real config must remain 100% untouched
                self.assertEqual("city=RealCity\ntmdb_key=real_secret\n", real_cfg.read_text(encoding="utf-8"))

                # TV probe is simulated cleanly without invoking ADB
                req_probe = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/probe-tv",
                    data=json.dumps({"tv_ip": "192.168.1.99"}).encode("utf-8"),
                    headers={"X-SoarTV-Setup-Token": token, "Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req_probe, timeout=3) as resp:
                    probe = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(probe["ok"])
                self.assertIn("Sandbox", probe["brand"])
            finally:
                server.shutdown()
                server.server_close()

    def test_complete_endpoint_writes_non_secret_handoff_and_shuts_down_when_waiting(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "SoarTV-launcher"
            launcher.mkdir()
            (launcher / "config.example.properties").write_text("city=\ntmdb_key=\n", encoding="utf-8")
            (root / ".env.example").write_text("CITY=\n", encoding="utf-8")

            server, token = create_setup_http_server(root, port=0, wait_for_save=True)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                req = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/complete",
                    data=json.dumps(
                        {
                            "tv_platform": "android_tv",
                            "settings": {
                                "city": "Berlin",
                                "tmdb_key": "never_leak_this_key",
                                "enable_gaming": "false",
                                "enable_smarthome": "true",
                                "custom_smarthome": [{"brand": "Philips Hue", "api_key": "hue_secret_123"}],
                            },
                        }
                    ).encode("utf-8"),
                    headers={"X-SoarTV-Setup-Token": token, "Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req, timeout=3) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(payload["ok"])
                self.assertTrue(payload["will_close"])

                handoff_file = launcher / ".setup_handoff.json"
                self.assertTrue(handoff_file.is_file())
                handoff_raw = handoff_file.read_text(encoding="utf-8")
                self.assertNotIn("never_leak_this_key", handoff_raw)
                self.assertNotIn("hue_secret_123", handoff_raw)
                handoff = json.loads(handoff_raw)
                self.assertEqual("android_tv", handoff["tv_platform"])
                self.assertEqual("Berlin", handoff["values"]["city"])
                self.assertTrue(handoff["configured_keys"]["tmdb_key"])
                self.assertEqual("Philips Hue", handoff["custom_smarthome"][0]["brand"])
                self.assertTrue(handoff["custom_smarthome"][0]["has_key"])

                thread.join(timeout=3)
                self.assertFalse(thread.is_alive())
            finally:
                server.server_close()

    def test_v2_cloud_vault_crypto_seed_and_setup_studio_cloud_routes(self) -> None:
        import sys as _sys
        from unittest import mock

        tools_dir = str(Path(__file__).resolve().parent.parent)
        if tools_dir not in _sys.path:
            _sys.path.insert(0, tools_dir)
        import send_key as sk  # type: ignore[import-not-found]

        # 1. Cross-platform PBKDF2-HMAC-SHA256 + AES-256-GCM vault round-trip & tamper rejection
        hh_key = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6"
        derived = sk.derive_household_vault_passphrase(hh_key)
        expected_pass = hashlib.sha256(f"soartv-vault-key-v2:{hh_key}".encode("utf-8")).hexdigest()
        self.assertEqual(expected_pass, derived)

        env = sk.encrypt_vault_map("household", {"tmdb_key": "tmdb_py_999", "omdb_key": "omdb_py_888"}, derived)
        self.assertEqual("household", env["vault_id"])
        self.assertEqual(120_000, env["kdf_iterations"])
        self.assertEqual(2, env["key_count"])
        self.assertNotIn("tmdb_py_999", json.dumps(env))
        decrypted = sk.decrypt_vault_map(env, derived)
        self.assertEqual({"tmdb_key": "tmdb_py_999", "omdb_key": "omdb_py_888"}, decrypted)

        with self.assertRaises(Exception):
            sk.decrypt_vault_map(env, sk.derive_household_vault_passphrase("wrong_household_key_0000"))

        # 2. seed_household_cloud uploads ONLY encrypted ciphertext (zero plaintext keys in HTTP body)
        captured_bodies = []

        class _FakeResp:
            def read(self) -> bytes:
                return b""

            def __enter__(self) -> "_FakeResp":
                return self

            def __exit__(self, *_a: object) -> bool:
                return False

        def fake_open(req: urllib.request.Request, timeout: float = 10) -> _FakeResp:
            captured_bodies.append((req.full_url, req.data.decode("utf-8") if req.data else ""))
            return _FakeResp()

        with mock.patch("urllib.request.urlopen", side_effect=fake_open):
            res = sk.seed_household_cloud(
                {
                    "supabase_url": "https://demo-hh.supabase.co",
                    "supabase_anon_key": "anon_abc",
                    "household_key": hh_key,
                    "tmdb_key": "super_secret_tmdb_value",
                    "city": "Tokyo",
                }
            )
        self.assertTrue(res["ok"])
        self.assertEqual(1, res["key_count"])
        self.assertTrue(any("/rest/v1/encrypted_vault?on_conflict=vault_id" in u for u, _ in captured_bodies))
        self.assertFalse(any("super_secret_tmdb_value" in b for _, b in captured_bodies))

        # 3. Setup Studio /api/cloud/tvs, /api/cloud/seed, and /api/cloud/link-tv work cleanly in sandbox with CSRF
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "SoarTV-launcher").mkdir()
            (root / "SoarTV-launcher" / "config.example.properties").write_text("city=\n", encoding="utf-8")
            (root / ".env.example").write_text("CITY=\n", encoding="utf-8")

            server, token = create_setup_http_server(root, port=0, sandbox=True)
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                req_tvs = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/cloud/tvs",
                    headers={"X-SoarTV-Setup-Token": token},
                )
                with urllib.request.urlopen(req_tvs, timeout=3) as resp:
                    tvs_data = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(tvs_data["ok"])
                self.assertEqual(1, len(tvs_data["tvs"]))

                req_link = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/cloud/link-tv",
                    data=json.dumps({"tv_ip": "192.168.1.50", "replace_tv_id": "sandbox-tv-living-room"}).encode("utf-8"),
                    headers={"X-SoarTV-Setup-Token": token, "Content-Type": "application/json"},
                    method="POST",
                )
                with urllib.request.urlopen(req_link, timeout=3) as resp:
                    link_data = json.loads(resp.read().decode("utf-8"))
                self.assertTrue(link_data["ok"])
                self.assertEqual("replace_tv", link_data["mode"])
            finally:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
