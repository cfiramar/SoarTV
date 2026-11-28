#!/usr/bin/env python3
"""Sends private secret keys to SoarTV on the TV via Android's ADB-only Secrets ContentProvider,
and provides SoarTV v2 Cloud Household Vault seeding & 3-key Multi-TV linking.

    python3 tools/send_key.py --seed-cloud                           # encrypt & upload household secrets to Supabase
    python3 tools/send_key.py --list-cloud-tvs                       # list active household TVs from Supabase
    python3 tools/send_key.py 192.168.1.50 --link-tv --name "Living Room TV" --room "Living Room"
    python3 tools/send_key.py 192.168.1.50 --link-tv --replace-tv <old_device_id>
    python3 tools/send_key.py 192.168.1.50 --sync-config             # legacy full key push from config.properties / .env
    python3 tools/send_key.py 192.168.1.50 sensibo_api_key           # prompts for a single key (never echoed)
    python3 tools/send_key.py --remove github_token                  # removes a key from the TV

Keys are encrypted at rest on the TV using AndroidKeyStore AES-256-GCM (Secrets.java) and in Supabase
`public.encrypted_vault` using PBKDF2-HMAC-SHA256 (120,000 iterations) + AES-256-GCM matching Android TV
(`CloudVaultCrypto.java`) and LG webOS / Samsung Tizen (`web/crypto.js`) byte-for-byte.
Raw secret values are NEVER printed or logged to stdout/stderr.
"""
from __future__ import annotations

import base64
import getpass
import hashlib
import json
import os
import re
import secrets as py_secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

URI = 'content://io.soartv.secrets/'
NAMES = (
    'adb_key',
    'supabase_url',
    'supabase_anon_key',
    'sensibo_api_key',
    'tuya_access_id',
    'tuya_access_secret',
    'github_token',
    'stremio_auth_key',
    'household_stremio_auth_key',
    'household_key',
    'gaming_pc_token',
    'tmdb_key',
    'omdb_key',
    'mdblist_key',
    'gemini_api_key',
    'trakt_client_id',
    'admin_emails',
    'remote_paired_devices',
    'wiz_light_ips',
    'switcher_api_key',
    'enable_gaming',
    'enable_smarthome',
    'tv_bootstrap_device_id',
    'tv_bootstrap_name',
    'tv_bootstrap_room',
    'tv_bootstrap_replace_id',
)
BOOTSTRAP_KEYS = (
    'supabase_url',
    'supabase_anon_key',
    'household_key',
)
HOUSEHOLD_VAULT_SECRET_KEYS = (
    'sensibo_api_key',
    'tuya_access_id',
    'tuya_access_secret',
    'github_token',
    'household_stremio_auth_key',
    'gaming_pc_token',
    'tmdb_key',
    'omdb_key',
    'mdblist_key',
    'gemini_api_key',
    'admin_emails',
    'trakt_client_id',
    'switcher_api_key',
)
SYNC_CONFIG_KEYS = (
    'supabase_url',
    'supabase_anon_key',
    'sensibo_api_key',
    'tuya_access_id',
    'tuya_access_secret',
    'github_token',
    'stremio_auth_key',
    'household_stremio_auth_key',
    'household_key',
    'gaming_pc_token',
    'tmdb_key',
    'omdb_key',
    'mdblist_key',
    'gemini_api_key',
    'trakt_client_id',
    'admin_emails',
    'wiz_light_ips',
    'switcher_api_key',
    'enable_gaming',
    'enable_smarthome',
)
DOTENV_KEY_ALIASES: Dict[str, str] = {
    'tmdb_api_key': 'tmdb_key',
    'omdb_api_key': 'omdb_key',
    'mdblist_api_key': 'mdblist_key',
    'gemini_api_key': 'gemini_api_key',
    'trakt_client_id': 'trakt_client_id',
    'supabase_url': 'supabase_url',
    'supabase_anon_key': 'supabase_anon_key',
    'household_key': 'household_key',
    'admin_emails': 'admin_emails',
    'sensibo_api_key': 'sensibo_api_key',
    'tuya_access_id': 'tuya_access_id',
    'tuya_access_secret': 'tuya_access_secret',
    'switcher_api_key': 'switcher_api_key',
    'switcher_device_id': 'switcher_device_id',
    'github_token': 'github_token',
    'gaming_pc_token': 'gaming_pc_token',
    'wiz_light_ips': 'wiz_light_ips',
    'stremio_email': 'stremio_email',
    'stremio_password': 'stremio_password',
    'stremio_auth_key': 'stremio_auth_key',
    'city': 'city',
    'latitude': 'latitude',
    'longitude': 'longitude',
    'enable_gaming': 'enable_gaming',
    'enable_smarthome': 'enable_smarthome',
}

HOUSEHOLD_VAULT_ID = 'household'
HOUSEHOLD_VAULT_KDF_DOMAIN = 'soartv-vault-key-v2:'
VAULT_KDF_ALGO = 'PBKDF2-HMAC-SHA256'
VAULT_KDF_ITERATIONS = 120_000
VAULT_MIN_ITERATIONS = 100_000

SERIAL: List[str] = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(ROOT)


def adb(*args: str, **kw: Any) -> subprocess.CompletedProcess:
    # Not this process's stdin: `adb shell` would read the key piped in for `content write` (and send it to the TV).
    if 'input' not in kw:
        kw['stdin'] = subprocess.DEVNULL
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, **kw)


def _read_kv_file(path: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    try:
        with open(path, encoding='utf-8') as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith('#'):
                    continue
                k, sep, v = line.partition('=')
                if sep:
                    clean_k = k.strip().lower()
                    clean_v = v.strip().strip('"').strip("'")
                    if clean_k and clean_v:
                        canonical_k = DOTENV_KEY_ALIASES.get(clean_k, clean_k)
                        values[canonical_k] = clean_v
    except OSError:
        pass
    return values


def load_local_config_map(repo_root: Optional[str] = None) -> Dict[str, str]:
    """Loads local key-value pairs from .env, keys.txt, and SoarTV-launcher/config.properties.
    Never prints or logs any secret values."""
    base_repo = repo_root or REPO_ROOT
    launcher_dir = os.path.join(base_repo, 'SoarTV-launcher')
    merged: Dict[str, str] = {}
    for candidate in (
        os.path.join(base_repo, '.env'),
        os.path.join(base_repo, 'keys.txt'),
        os.path.join(launcher_dir, 'config.properties'),
    ):
        merged.update(_read_kv_file(candidate))
    return merged


def prop(key: str) -> str:
    """A value from config.properties, .env, or keys.txt, or ''."""
    return load_local_config_map().get(key.lower(), '')


# MARK: - Cross-Platform Zero-Knowledge E2EE Cloud Vault Crypto (Matches Java & Web ES5 Byte-for-Byte)

def derive_household_vault_passphrase(household_key: str) -> str:
    """Derives the domain-separated E2EE vault passphrase identically to Java CloudVaultCrypto and web/crypto.js."""
    clean = (household_key or '').strip()
    if not clean:
        raise ValueError('household_key must not be empty')
    return hashlib.sha256((HOUSEHOLD_VAULT_KDF_DOMAIN + clean).encode('utf-8')).hexdigest()


def _aes256_gcm_encrypt(key: bytes, iv: bytes, plaintext: bytes, aad: bytes) -> bytes:
    """Encrypts with AES-256-GCM returning ciphertext || 16-byte tag."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).encrypt(iv, plaintext, aad)
    except ImportError as exc:
        raise RuntimeError(
            'Python `cryptography` package is required for Cloud Vault AES-256-GCM encryption. '
            'Install with: pip install cryptography'
        ) from exc


def _aes256_gcm_decrypt(key: bytes, iv: bytes, cipher_with_tag: bytes, aad: bytes) -> bytes:
    """Authenticates and decrypts AES-256-GCM ciphertext || 16-byte tag."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM(key).decrypt(iv, cipher_with_tag, aad)
    except ImportError as exc:
        raise RuntimeError(
            'Python `cryptography` package is required for Cloud Vault AES-256-GCM decryption. '
            'Install with: pip install cryptography'
        ) from exc


def encrypt_vault_map(
    vault_id: str,
    secrets_map: Dict[str, str],
    passphrase: str,
    iterations: int = VAULT_KDF_ITERATIONS,
) -> Dict[str, Any]:
    """Encrypts a flat key-value secret map into a Supabase `public.encrypted_vault` row matching CloudVaultCrypto.java."""
    if not passphrase:
        raise ValueError('passphrase must not be empty')
    if iterations < VAULT_MIN_ITERATIONS:
        raise ValueError(f'iterations must be >= {VAULT_MIN_ITERATIONS}')
    slot = (vault_id or '').strip() or HOUSEHOLD_VAULT_ID
    clean: Dict[str, str] = {}
    for k, v in (secrets_map or {}).items():
        ck = (k or '').strip()
        if ck and v is not None and str(v).strip():
            clean[ck] = str(v).strip()

    salt = py_secrets.token_bytes(16)
    iv = py_secrets.token_bytes(12)
    aes_key = hashlib.pbkdf2_hmac('sha256', passphrase.encode('utf-8'), salt, iterations, dklen=32)
    aad = f'soartv-vault-v1:{VAULT_KDF_ALGO}:{iterations}'.encode('utf-8')
    plaintext = json.dumps(clean, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    ct_with_tag = _aes256_gcm_encrypt(aes_key, iv, plaintext, aad)
    return {
        'vault_id': slot,
        'kdf_algorithm': VAULT_KDF_ALGO,
        'kdf_salt': base64.b64encode(salt).decode('ascii'),
        'kdf_iterations': iterations,
        'cipher_iv': base64.b64encode(iv).decode('ascii'),
        'ciphertext': base64.b64encode(ct_with_tag).decode('ascii'),
        'key_count': len(clean),
    }


def decrypt_vault_map(envelope: Dict[str, Any], passphrase: str) -> Dict[str, str]:
    """Authenticates and decrypts a Supabase `public.encrypted_vault` row back into a secret dictionary."""
    if not isinstance(envelope, dict):
        raise ValueError('envelope must be a dict')
    algo = str(envelope.get('kdf_algorithm') or envelope.get('kdfAlgorithm') or '')
    if algo != VAULT_KDF_ALGO:
        raise ValueError(f'Unsupported KDF algorithm: {algo}')
    iterations = int(envelope.get('kdf_iterations') or envelope.get('kdfIterations') or 0)
    if iterations < VAULT_MIN_ITERATIONS:
        raise ValueError(f'KDF iterations below minimum threshold: {iterations}')
    salt = base64.b64decode(str(envelope.get('kdf_salt') or envelope.get('kdfSalt') or ''))
    iv = base64.b64decode(str(envelope.get('cipher_iv') or envelope.get('cipherIv') or ''))
    ct_with_tag = base64.b64decode(str(envelope.get('ciphertext') or ''))
    if len(salt) < 16 or len(iv) != 12 or len(ct_with_tag) < 16:
        raise ValueError('Malformed vault envelope parameters')
    aes_key = hashlib.pbkdf2_hmac('sha256', passphrase.encode('utf-8'), salt, iterations, dklen=32)
    aad = f'soartv-vault-v1:{algo}:{iterations}'.encode('utf-8')
    pt = _aes256_gcm_decrypt(aes_key, iv, ct_with_tag, aad)
    parsed = json.loads(pt.decode('utf-8'))
    if not isinstance(parsed, dict):
        raise ValueError('Decrypted vault payload is not a JSON object')
    return {str(k): str(v) for k, v in parsed.items() if v is not None}


def _supabase_headers(anon_key: str, household_key: str, prefer: Optional[str] = None) -> Dict[str, str]:
    h = {
        'apikey': anon_key.strip(),
        'Authorization': f'Bearer {anon_key.strip()}',
        'x-soartv-household-key': household_key.strip(),
        'Accept': 'application/json',
        'Content-Type': 'application/json; charset=UTF-8',
    }
    if prefer:
        h['Prefer'] = prefer
    return h


def fetch_household_tv_devices(
    supabase_url: str,
    anon_key: str,
    household_key: str,
    active_only: bool = True,
) -> List[Dict[str, Any]]:
    """Queries active household TVs from Supabase `public.tv_devices` using explicit column projection."""
    clean_url = (supabase_url or '').strip().rstrip('/')
    if not (clean_url and anon_key.strip() and household_key.strip()):
        return []
    select_cols = 'device_id,device_name,room_name,platform,brand,model,lan_ip,remote_port,mdns_host,realtime_channel,is_active,last_seen_at'
    filter_q = '&is_active=eq.true' if active_only else ''
    req = urllib.request.Request(
        f'{clean_url}/rest/v1/tv_devices?select={select_cols}{filter_q}&order=last_seen_at.desc',
        headers=_supabase_headers(anon_key, household_key),
        method='GET',
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        data = json.loads(resp.read().decode('utf-8'))
    return data if isinstance(data, list) else []


def seed_household_cloud(cfg: Dict[str, str]) -> Dict[str, Any]:
    """Encrypts household API keys with the Household-Key derived passphrase and upserts them into Supabase."""
    supabase_url = (cfg.get('supabase_url') or '').strip().rstrip('/')
    anon_key = (cfg.get('supabase_anon_key') or '').strip()
    household_key = (cfg.get('household_key') or '').strip()
    if not (supabase_url and anon_key and len(household_key) >= 16):
        raise ValueError('supabase_url, supabase_anon_key, and household_key (16+ chars) are required to seed cloud')

    work_cfg = dict(cfg)
    if not work_cfg.get('household_stremio_auth_key'):
        if work_cfg.get('stremio_auth_key'):
            work_cfg['household_stremio_auth_key'] = work_cfg['stremio_auth_key']
        elif work_cfg.get('stremio_email') and work_cfg.get('stremio_password'):
            exchanged = exchange_stremio_login(work_cfg['stremio_email'], work_cfg['stremio_password'])
            if exchanged:
                work_cfg['household_stremio_auth_key'] = exchanged

    secrets_payload: Dict[str, str] = {}
    for k in HOUSEHOLD_VAULT_SECRET_KEYS:
        val = (work_cfg.get(k) or '').strip()
        if len(val) >= 4:
            secrets_payload[k] = val

    vault_pass = derive_household_vault_passphrase(household_key)
    envelope = encrypt_vault_map(HOUSEHOLD_VAULT_ID, secrets_payload, vault_pass)
    req_vault = urllib.request.Request(
        f'{supabase_url}/rest/v1/encrypted_vault?on_conflict=vault_id',
        data=json.dumps(envelope).encode('utf-8'),
        headers=_supabase_headers(anon_key, household_key, 'resolution=merge-duplicates,return=minimal'),
        method='POST',
    )
    with urllib.request.urlopen(req_vault, timeout=10) as resp:
        _ = resp.read()

    # Also seed non-secret household settings (city/coords, switcher_device_id, github_repo) if present
    hh_settings: Dict[str, Any] = {}
    now_ms = int(time.time() * 1000)
    hh_ts: Dict[str, int] = {}
    for field_key in ('city', 'latitude', 'longitude', 'switcher_device_id', 'github_repo'):
        val = (work_cfg.get(field_key) or '').strip()
        if val:
            hh_settings[field_key] = val
            hh_ts[field_key] = now_ms
    if hh_settings:
        req_hh = urllib.request.Request(
            f'{supabase_url}/rest/v1/household_settings?on_conflict=id',
            data=json.dumps({'id': 1, 'settings': hh_settings, 'field_timestamps': hh_ts}).encode('utf-8'),
            headers=_supabase_headers(anon_key, household_key, 'resolution=merge-duplicates,return=minimal'),
            method='POST',
        )
        with urllib.request.urlopen(req_hh, timeout=10) as resp:
            _ = resp.read()

    return {
        'ok': True,
        'vault_id': HOUSEHOLD_VAULT_ID,
        'key_count': len(secrets_payload),
        'household_fields': sorted(hh_settings.keys()),
    }


def received(name: str) -> Optional[int]:
    """When the TV got this key (Secrets' `received`, ms), 0 if it has none, None if it didn't answer."""
    r = adb('shell', 'content', 'query', '--uri', URI + name, text=True)
    m = re.search(r'present=(\d+), received=(\d+)', r.stdout)
    if not m:
        return None
    return int(m.group(2)) if m.group(1) == '1' else 0


def settled(name: str, done: Any) -> Optional[int]:
    """received(name) once done(it) holds, or its last answer: the app encrypts the key into place when write closes."""
    r = None
    for _ in range(10):
        r = received(name)
        if done(r):
            return r
        time.sleep(0.5)
    return r


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The key isn't sent on to wherever a redirect points, as on the TV."""
    def redirect_request(self, *args: Any) -> Any:
        return None


CHECKS: Dict[str, Any] = {}


def exchange_stremio_login(email: str, password: str) -> str:
    """Exchanges Stremio email + password once locally against https://api.strem.io/api/login and returns ONLY authKey."""
    if not email or not password:
        return ''
    payload = json.dumps({
        'type': 'Login',
        'email': email.strip(),
        'password': password,
        'facebook': False,
    }).encode('utf-8')
    req = urllib.request.Request(
        'https://api.strem.io/api/login',
        data=payload,
        headers={'Content-Type': 'application/json; charset=UTF-8', 'Accept': 'application/json'},
        method='POST',
    )
    try:
        opener = urllib.request.build_opener(NoRedirect)
        with opener.open(req, timeout=10) as resp:
            data = json.load(resp)
            result = data.get('result') or {}
            return str(result.get('authKey') or '').strip()
    except Exception:
        return ''


def verify_tv_ready() -> bool:
    listed = adb('shell', 'pm', 'list', 'packages', 'io.soartv', text=True)
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return False
    if 'package:io.soartv' not in listed.stdout.split():
        print('SoarTV (io.soartv) is not installed on this TV.')
        return False
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk', text=True).stdout.strip()
    if sdk.isdigit() and int(sdk) < 28:
        print('This TV runs Android 8, where adb has no way to write into the app: keys need Android 9 or newer.')
        return False
    return True


def push_secret(name: str, secret_value: str) -> bool:
    before = received(name)
    if before is None:
        print(f'The SoarTV build on this TV does not accept {name} yet: install the updated SoarTV.apk first.', flush=True)
        return False
    payload = secret_value.encode('utf-8') + b'\n'
    after = before
    for attempt in range(4):
        if attempt > 0:
            if len(SERIAL) == 2:
                adb('connect', SERIAL[1], text=True)
            time.sleep(0.4)
        adb('exec-in', 'content', 'write', '--uri', URI + name, input=payload)
        after = settled(name, lambda r: bool(r) and r != before)
        if after and after != before:
            break
    if not after or after == before:
        print(f'The TV rejected {name} (must be 4..16384 printable ASCII characters).', flush=True)
        return False
    print(f'Synced {name} (encrypted at rest via AndroidKeyStore).', flush=True)
    return True


def link_tv_bootstrap(
    cfg: Dict[str, str],
    tv_name: str = '',
    tv_room: str = '',
    replace_tv_id: str = '',
) -> int:
    """Fast 3-key TV bootstrap: pushes ONLY supabase_url, supabase_anon_key, household_key (+ optional TV metadata)."""
    if not verify_tv_ready():
        return 1
    to_push: List[Tuple[str, str]] = []
    for k in BOOTSTRAP_KEYS:
        val = (cfg.get(k) or '').strip()
        if not val or len(val) < 4:
            print(f'Missing required bootstrap credential in local config: {k}')
            return 1
        to_push.append((k, val))
    if tv_name.strip() and len(tv_name.strip()) >= 4:
        to_push.append(('tv_bootstrap_name', tv_name.strip()))
    if tv_room.strip() and len(tv_room.strip()) >= 4:
        to_push.append(('tv_bootstrap_room', tv_room.strip()))
    if replace_tv_id.strip() and len(replace_tv_id.strip()) >= 4:
        to_push.append(('tv_bootstrap_replace_id', replace_tv_id.strip()))

    pushed = 0
    failed = 0
    for key_name, val in to_push:
        if push_secret(key_name, val):
            pushed += 1
        else:
            failed += 1
    print(f'TV 3-key cloud link complete: {pushed} item(s) pushed, {failed} failed.')
    return 0 if failed == 0 else 1


def sync_from_config() -> int:
    if not verify_tv_ready():
        return 1
    cfg = load_local_config_map()
    if not cfg.get('stremio_auth_key') and cfg.get('stremio_email') and cfg.get('stremio_password'):
        print('Exchanging stremio_email + stremio_password for session stremio_auth_key...')
        exchanged = exchange_stremio_login(cfg.get('stremio_email', ''), cfg.get('stremio_password', ''))
        if exchanged:
            cfg['stremio_auth_key'] = exchanged
        else:
            print('Warning: Stremio login exchange did not return an authKey; skipping stremio_auth_key.')

    pushed = 0
    failed = 0
    for key_name in SYNC_CONFIG_KEYS:
        val = cfg.get(key_name, '').strip()
        if not val or len(val) < 4:
            continue
        if push_secret(key_name, val):
            pushed += 1
        else:
            failed += 1

    print(f'Config sync complete: {pushed} key(s) pushed, {failed} failed.')
    return 0 if failed == 0 else 1


def _pop_opt(args: List[str], flag: str) -> str:
    if flag in args:
        idx = args.index(flag)
        val = args[idx + 1] if idx + 1 < len(args) else ''
        del args[idx:idx + (2 if idx + 1 < len(args) else 1)]
        return val
    return ''


def main() -> int:
    args = list(sys.argv[1:])
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2

    remove = '--remove' in args
    sync_cfg = '--sync-config' in args
    seed_cloud = '--seed-cloud' in args
    list_tvs = '--list-cloud-tvs' in args
    link_tv = '--link-tv' in args
    check = '--no-check' not in args

    tv_name = _pop_opt(args, '--name')
    tv_room = _pop_opt(args, '--room')
    replace_tv_id = _pop_opt(args, '--replace-tv')
    args = [a for a in args if a not in ('--remove', '--no-check', '--sync-config', '--seed-cloud', '--list-cloud-tvs', '--link-tv')]

    cfg = load_local_config_map()
    if seed_cloud:
        res = seed_household_cloud(cfg)
        print(f"Seeded Supabase encrypted vault '{res['vault_id']}' ({res['key_count']} keys).")
        if not link_tv and not sync_cfg and not list_tvs and not args:
            return 0

    if list_tvs:
        tvs = fetch_household_tv_devices(
            cfg.get('supabase_url', ''),
            cfg.get('supabase_anon_key', ''),
            cfg.get('household_key', ''),
        )
        print(json.dumps(tvs, indent=2))
        if not link_tv and not sync_cfg and not args:
            return 0

    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1

    connected_target = ''
    try:
        if args and args[0][0].isdigit():
            ip = args.pop(0)
            target = ip if ':' in ip else ip + ':5555'
            r = adb('connect', target, text=True)
            if 'connected' not in r.stdout:
                print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
                return 1
            SERIAL[:] = ['-s', target]
            connected_target = target

        if link_tv:
            return link_tv_bootstrap(cfg, tv_name=tv_name, tv_room=tv_room, replace_tv_id=replace_tv_id)

        if sync_cfg:
            return sync_from_config()

        if len(args) != 1 or args[0] not in NAMES:
            print('Which key? One of: ' + ', '.join(NAMES) + ' (or pass --seed-cloud, --link-tv, --sync-config)')
            return 2
        name = args[0]
        if not verify_tv_ready():
            return 1
        before = received(name)
        if before is None:
            print('The SoarTV on this TV is too old to take this key: install the updated SoarTV.apk, then send it again.')
            return 1

        if remove:
            adb('shell', 'content', 'delete', '--uri', URI + name)
            if settled(name, lambda r: r == 0) != 0:
                print(f'{name} is still on the TV: try again.')
                return 1
            print(f'{name} is off the TV.')
            return 0
        key = (getpass.getpass(f'{name} (not shown): ') if sys.stdin.isatty() else sys.stdin.readline()).strip()
        if not key:
            print('No key given.')
            return 1
        if check and name in CHECKS:
            why = CHECKS[name](key)
            if why:
                print(why)
                return 1
        return 0 if push_secret(name, key) else 1
    finally:
        if connected_target:
            subprocess.run(['adb', 'disconnect'], capture_output=True, text=True, timeout=4, check=False)


if __name__ == '__main__':
    sys.exit(main())
