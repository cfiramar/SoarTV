import {
  AdminInviteResponse,
  AuthConfigResponse,
  GamingGameItem,
  GamingStatus,
  HouseholdDirectoryCreds,
  HouseholdTvDevice,
  InstallPlatform,
  PairedDeviceInfo,
  RealtimeTvRemoteBridge,
  RemoteForbiddenError,
  RemoteKeyName,
  RemoteRole,
  RemoteUnauthorizedError,
  SmartHomeDevice,
  SmartHomeIconKind,
  SmartHomeRoutineId,
  TvActiveModal,
  TvFocusedInput,
  TvRemoteService,
  TvStatus,
  buildInviteShareText,
  buildTvInputSyncSignature,
  computeKeyboardOffsetPx,
  detectInstallPlatform,
  extractInitialQrPairUrl,
  formatFocusedInputCue,
  formatGameSubtitle,
  formatPairingSheetCopy,
  getThemeColorMeta,
  isIosStandaloneWebApp,
  pickBestDeviceNickname,
  resolveFallbackDockTab,
  resolveLiveMirrorDecision,
  resolveLivePollIntervalMs,
  resolveMirroredModalVisibility,
  resolveSmartHomeIconKind,
  resolveTabVisibility,
  resolveTouchpadTapAction,
  resolveTvInputSyncDecision,
} from './services/TvRemoteService';

interface SoarTvNativeBridgeInterface {
  discoverTv?(hintUrl: string): string;
  discoverAllTvsOnLan?(): string;
  startNativeTvDiscovery?(hintUrl: string): void;
  setSystemBarTheme?(dark: boolean): void;
  openNativeQrScanner?(): void;
  closeNativeQrScanner?(): void;
  openExternalUrl?(url: string): void;
  getDeviceName?(): string;
  isBiometricAvailable(): boolean;
  hasSavedPcUnlockPin(): boolean;
  enrollPcUnlockPin(pin: string): void;
  authenticatePcUnlock(): void;
  clearSavedPcUnlockPin(): void;
}

interface BarcodeDetectorLike {
  detect(source: ImageBitmapSource): Promise<Array<{ rawValue?: string }>>;
}

declare global {
  interface Window {
    SoarTvNative?: SoarTvNativeBridgeInterface;
    SoarTvNativeRemote?: SoarTvNativeBridgeInterface;
    BarcodeDetector?: new (opts?: { formats?: string[] }) => BarcodeDetectorLike;
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
    __toastTimer?: ReturnType<typeof setTimeout>;
    onSoarTvGoogleAuthCallback?: (accessToken: string) => void;
    onSoarTvDiscovered?: (baseUrl: string) => void;
    onSoarTvQrScanned?: (rawUri: string) => void;
    onSoarTvNativeQrDetected?: () => void;
    onSoarTvNativeQrClosed?: (reason: string) => void;
    onBiometricEnrollResult?: (ok: boolean, errorMessage: string) => void;
    onBiometricPcPinReady?: (pin: string) => void;
    onBiometricPcPinError?: (errorMessage: string) => void;
    [key: string]: unknown;
  }
}

interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  onresult: ((ev: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onerror: (() => void) | null;
  onend: (() => void) | null;
  start(): void;
}

const STORAGE_BASE_URL = 'soartv_base_url';
const STORAGE_TOKEN = 'soartv_remote_token';
const STORAGE_ROLE = 'soartv_remote_role';
const STORAGE_CLIENT_ID = 'soartv_client_id';
const STORAGE_PAD_MODE = 'soartv_pad_mode';
const STORAGE_SUPABASE_URL = 'soartv_hh_supabase_url';
const STORAGE_SUPABASE_ANON = 'soartv_hh_supabase_anon';
const STORAGE_HOUSEHOLD_KEY = 'soartv_hh_key';
const STORAGE_ACTIVE_TV_ID = 'soartv_active_tv_id';
const STORAGE_KNOWN_TVS = 'soartv_known_tvs';
const SCREEN_NAMES = ['Search', 'Home', 'Apps', 'Gaming', 'Reels', 'Smart-Home', 'Settings'];

const nativeBridge: SoarTvNativeBridgeInterface | undefined =
  typeof window !== 'undefined' ? window.SoarTvNative || window.SoarTvNativeRemote : undefined;

const IS_NATIVE_APK =
  typeof window !== 'undefined' &&
  (window.location.protocol === 'file:' ||
    window.location.hostname.toLowerCase() === TvRemoteService.NATIVE_REMOTE_HOST ||
    window.location.search.includes('native=1') ||
    Boolean(nativeBridge));

function readStorage(key: string): string {
  try {
    return (localStorage.getItem(key) || '').trim();
  } catch {
    return '';
  }
}

function writeStorage(key: string, val: string): void {
  try {
    if (val) {
      localStorage.setItem(key, val);
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    // Ignore private-browsing storage restrictions
  }
}

function getOrCreateClientId(): string {
  let id = readStorage(STORAGE_CLIENT_ID);
  if (!id) {
    const rnd = Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
    id = `cid_${rnd}`;
    writeStorage(STORAGE_CLIENT_ID, id);
  }
  return id;
}

function detectDeviceName(): string {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent || '' : '';
  let nativeName = '';
  if (nativeBridge && typeof nativeBridge.getDeviceName === 'function') {
    try {
      nativeName = nativeBridge.getDeviceName() || '';
    } catch {
      nativeName = '';
    }
  }
  return pickBestDeviceNickname(nativeName, ua, IS_NATIVE_APK);
}

let currentStatus: TvStatus | null = null;
let currentAuthConfig: AuthConfigResponse | null = null;
let currentRole: RemoteRole | '' = (readStorage(STORAGE_ROLE) as RemoteRole | '') || '';
let isPaired = false;
let isGuestExpiredState = false;
let activePairRequestId = '';
let pairPollTimer: ReturnType<typeof setInterval> | null = null;
let showHiddenGames = false;
let localThemeOverride: boolean | null = null;
let selectedSmartDeviceId = '';
let activeFocusedInput: TvFocusedInput | null = null;
let lastSyncedTvInputSig = '';
let isSearchInputDirty = false;
let fastInputPollTimer: ReturnType<typeof setInterval> | null = null;
let lastMirroredTvText = '';
let lastLocalEditAtMs = 0;
let liveMirrorTimer: ReturnType<typeof setTimeout> | null = null;
let liveMirrorInFlight = false;
let liveMirrorQueuedText: string | null = null;
let activeTvModal: TvActiveModal | null = null;
let dismissedTvModalId = '';
let lastRenderedTvModalSig = '';
let lastAutoWakeAtMs = 0;
let livePollIntervalActiveMs = 0;
let livePollInFlight = false;

// Diff signatures to avoid wiping DOM innerHTML on unchanged 6-second polls (PERF-15)
let lastShelfSig = '';
let lastSmartHomeSig = '';
let lastReelsSig = '';
let lastUsersSig = '';
let lastGamingSig = '';
let lastAdminDevicesSig = '';
let knownHouseholdTvs: HouseholdTvDevice[] = loadCachedHouseholdTvs();
let activeTvDeviceId = readStorage(STORAGE_ACTIVE_TV_ID);
let activeRealtimeBridge: RealtimeTvRemoteBridge | null = null;
let activeRealtimeTv: HouseholdTvDevice | null = null;

function loadCachedHouseholdTvs(): HouseholdTvDevice[] {
  try {
    const raw = readStorage(STORAGE_KNOWN_TVS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as HouseholdTvDevice[]) : [];
  } catch {
    return [];
  }
}

function saveCachedHouseholdTvs(list: HouseholdTvDevice[]): void {
  knownHouseholdTvs = list;
  try {
    writeStorage(STORAGE_KNOWN_TVS, JSON.stringify(list));
  } catch {
    // Ignore quota errors
  }
}

function getHouseholdDirectoryCreds(): HouseholdDirectoryCreds | null {
  if (currentRole !== 'ADMIN' && currentRole !== 'HOUSEHOLD') {
    return null;
  }
  const supabaseUrl = readStorage(STORAGE_SUPABASE_URL);
  const supabaseAnonKey = readStorage(STORAGE_SUPABASE_ANON);
  const householdKey = readStorage(STORAGE_HOUSEHOLD_KEY);
  if (!supabaseUrl || !supabaseAnonKey || !householdKey) {
    return null;
  }
  return { supabaseUrl, supabaseAnonKey, householdKey };
}

function persistHouseholdDirectoryCreds(cfg: AuthConfigResponse | null): void {
  if (!cfg) return;
  if (cfg.tvDeviceId) {
    activeTvDeviceId = cfg.tvDeviceId;
    writeStorage(STORAGE_ACTIVE_TV_ID, cfg.tvDeviceId);
  }
  if (cfg.role === 'ADMIN' || cfg.role === 'HOUSEHOLD') {
    if (cfg.supabaseUrl && cfg.supabaseAnonKey && cfg.householdKey) {
      writeStorage(STORAGE_SUPABASE_URL, cfg.supabaseUrl);
      writeStorage(STORAGE_SUPABASE_ANON, cfg.supabaseAnonKey);
      writeStorage(STORAGE_HOUSEHOLD_KEY, cfg.householdKey);
    }
  } else if (cfg.role === 'GUEST') {
    // Security boundary: Guest (30-day) remotes NEVER retain household cloud directory keys
    writeStorage(STORAGE_SUPABASE_ANON, '');
    writeStorage(STORAGE_HOUSEHOLD_KEY, '');
  }
}

function clearHouseholdDirectoryCreds(): void {
  writeStorage(STORAGE_SUPABASE_URL, '');
  writeStorage(STORAGE_SUPABASE_ANON, '');
  writeStorage(STORAGE_HOUSEHOLD_KEY, '');
}

function formatTvPlatformBadge(tv: HouseholdTvDevice): string {
  const plat = (tv.platform || '').toLowerCase();
  if (plat === 'lg-webos') return 'LG webOS';
  if (plat === 'samsung-tizen') return 'Samsung Tizen';
  return tv.brand ? tv.brand : 'Android TV';
}

function renderHouseholdTvSwitcher(): void {
  const section = document.getElementById('householdTvsSection');
  const listEl = document.getElementById('householdTvsList');
  if (!section || !listEl) return;
  if (knownHouseholdTvs.length <= 1) {
    section.style.display = 'none';
    return;
  }
  section.style.display = 'flex';
  listEl.innerHTML = knownHouseholdTvs
    .map((tv) => {
      const isActive =
        (activeRealtimeTv && activeRealtimeTv.deviceId === tv.deviceId) ||
        (!activeRealtimeTv &&
          (tv.deviceId === activeTvDeviceId ||
            (Boolean(tv.lanIp) && TvRemoteService.buildTvBaseUrl(tv) === remoteService.getBaseUrl())));
      const subParts: string[] = [];
      if (tv.roomName) subParts.push(tv.roomName);
      if (TvRemoteService.isWebRealtimeTv(tv)) {
        subParts.push('Cloud Realtime');
      } else if (tv.lanIp) {
        subParts.push(tv.lanIp);
      }
      const badge = formatTvPlatformBadge(tv);
      return `<button class="household-tv-row${isActive ? ' active' : ''}" onclick="selectHouseholdTv('${escapeAttr(tv.deviceId)}')">
        <span class="household-tv-meta">
          <span class="household-tv-name">${escapeHtml(tv.deviceName || tv.deviceId)}</span>
          <span class="household-tv-sub">${escapeHtml(subParts.join(' · ') || 'Household TV')}</span>
        </span>
        <span class="household-tv-badge">${escapeHtml(badge)}</span>
      </button>`;
    })
    .join('');
}

async function refreshHouseholdTvDirectory(showFeedback: boolean = false): Promise<void> {
  const mergedById = new Map<string, HouseholdTvDevice>();
  for (const prev of knownHouseholdTvs) {
    if (prev.deviceId) mergedById.set(prev.deviceId, prev);
  }

  // 1. Pull household directory from Supabase (ADMIN / HOUSEHOLD only)
  const creds = getHouseholdDirectoryCreds();
  if (creds) {
    const cloudTvs = await TvRemoteService.fetchHouseholdTvDevices(creds);
    for (const tv of cloudTvs) {
      mergedById.set(tv.deviceId, tv);
    }
  }

  // 2. Merge instant native LAN UDP discovery replies (works 100% offline!)
  if (nativeBridge && typeof nativeBridge.discoverAllTvsOnLan === 'function') {
    try {
      const raw = nativeBridge.discoverAllTvsOnLan();
      const udpList = JSON.parse(raw || '[]') as Array<Record<string, unknown>>;
      if (Array.isArray(udpList)) {
        for (const item of udpList) {
          const id = String(item.deviceId || '').trim();
          const lanIp = String(item.lanIp || '').trim();
          if (!id || !lanIp) continue;
          const prev = mergedById.get(id);
          mergedById.set(id, {
            deviceId: id,
            deviceName: String(item.deviceName || prev?.deviceName || 'SoarTV'),
            roomName: String(item.roomName || prev?.roomName || ''),
            platform: String(item.platform || prev?.platform || 'android-tv'),
            brand: String(item.brand || prev?.brand || ''),
            model: String(item.model || prev?.model || ''),
            lanIp,
            remotePort: Number(item.port) || prev?.remotePort || 8484,
            mdnsHost: String(item.mdnsHost || prev?.mdnsHost || ''),
            realtimeChannel: prev?.realtimeChannel || '',
            lastSeenAt: new Date().toISOString(),
          });
        }
      }
    } catch {
      // Ignore native bridge parse failures
    }
  }

  // 3. Ensure currently connected TV is represented
  if (currentAuthConfig?.tvDeviceId) {
    const existing = mergedById.get(currentAuthConfig.tvDeviceId);
    const curBase = remoteService.getBaseUrl();
    const curHostMatch = curBase.match(/^https?:\/\/([^:/]+)/i);
    mergedById.set(currentAuthConfig.tvDeviceId, {
      deviceId: currentAuthConfig.tvDeviceId,
      deviceName: currentAuthConfig.tvName || existing?.deviceName || 'SoarTV',
      roomName: currentAuthConfig.tvRoom || existing?.roomName || '',
      platform: currentAuthConfig.tvPlatform || existing?.platform || 'android-tv',
      brand: currentAuthConfig.tvBrand || existing?.brand || '',
      model: existing?.model || '',
      lanIp: (curHostMatch ? curHostMatch[1] : '') || existing?.lanIp || '',
      remotePort: existing?.remotePort || 8484,
      mdnsHost: currentAuthConfig.mdnsHost || existing?.mdnsHost || '',
      realtimeChannel: existing?.realtimeChannel || '',
      lastSeenAt: new Date().toISOString(),
    });
  }

  saveCachedHouseholdTvs(Array.from(mergedById.values()));
  renderHouseholdTvSwitcher();
  if (showFeedback) {
    showToast(`Found ${knownHouseholdTvs.length} household TV${knownHouseholdTvs.length === 1 ? '' : 's'}`);
  }
}

async function selectHouseholdTv(deviceId: string): Promise<void> {
  const target = knownHouseholdTvs.find((t) => t.deviceId === deviceId);
  if (!target) return;

  activeTvDeviceId = target.deviceId;
  writeStorage(STORAGE_ACTIVE_TV_ID, target.deviceId);

  if (activeRealtimeBridge) {
    activeRealtimeBridge.disconnect();
    activeRealtimeBridge = null;
    activeRealtimeTv = null;
  }

  if (TvRemoteService.isWebRealtimeTv(target)) {
    const creds = getHouseholdDirectoryCreds();
    const channel = target.realtimeChannel || target.deviceId;
    if (!creds || !channel) {
      showToast('Cloud Realtime credentials required for LG / Samsung TVs');
      return;
    }
    activeRealtimeTv = target;
    activeRealtimeBridge = new RealtimeTvRemoteBridge(creds, channel, {
      onStatusChange: (connected) => {
        document.getElementById('statusDot')?.classList.toggle('offline', !connected);
      },
    });
    activeRealtimeBridge.connect();
    const tvModelEl = document.getElementById('tvModelText');
    const tvSubEl = document.getElementById('tvSubText');
    const pillText = document.getElementById('tvPillName');
    if (tvModelEl) tvModelEl.textContent = target.deviceName;
    if (tvSubEl) tvSubEl.textContent = `${target.roomName || formatTvPlatformBadge(target)} · Cloud Realtime`;
    if (pillText) pillText.textContent = target.deviceName;
    renderHouseholdTvSwitcher();
    closeSheet('screens');
    showToast(`Switched to ${target.deviceName}`);
    return;
  }

  const nextUrl = TvRemoteService.buildTvBaseUrl(target);
  if (!nextUrl) {
    showToast(`Locating ${target.deviceName}...`);
    const resolved = await TvRemoteService.resolveReachableTv(target, '', getHouseholdDirectoryCreds());
    if (!resolved || !resolved.baseUrl) {
      showToast(`Could not reach ${target.deviceName} on Wi-Fi`);
      return;
    }
    remoteService.setBaseUrl(resolved.baseUrl);
    writeStorage(STORAGE_BASE_URL, resolved.baseUrl);
  } else {
    remoteService.setBaseUrl(nextUrl);
    writeStorage(STORAGE_BASE_URL, nextUrl);
  }
  syncApkDownloadLinks();
  renderHouseholdTvSwitcher();
  closeSheet('screens');
  showToast(`Switched to ${target.deviceName}`);
  await bootstrapConnectionAndAuth();
}

const initialBaseUrl =
  typeof window !== 'undefined'
    ? TvRemoteService.resolveDynamicBaseUrl(window.location, readStorage(STORAGE_BASE_URL))
    : '';

if (
  initialBaseUrl &&
  typeof window !== 'undefined' &&
  window.location.port === '8484' &&
  window.location.hostname.toLowerCase() !== TvRemoteService.NATIVE_REMOTE_HOST
) {
  // Persist live TV origin automatically so router/Wi-Fi IP changes stay in sync
  writeStorage(STORAGE_BASE_URL, initialBaseUrl);
}

const remoteService = new TvRemoteService(initialBaseUrl, undefined, {
  authToken: readStorage(STORAGE_TOKEN),
  timeoutMs: TvRemoteService.DEFAULT_TIMEOUT_MS,
  onAuthError: (err) => {
    if (err instanceof RemoteUnauthorizedError) {
      handleUnauthorizedTransition(err.guestExpired);
    } else if (err instanceof RemoteForbiddenError) {
      showToast('Action requires Admin permission');
    }
  },
});

function escapeHtml(str: unknown): string {
  return String(str ?? '').replace(/[&<>"']/g, (c) => {
    switch (c) {
      case '&':
        return '&amp;';
      case '<':
        return '&lt;';
      case '>':
        return '&gt;';
      case '"':
        return '&quot;';
      default:
        return '&#39;';
    }
  });
}

function escapeAttr(str: unknown): string {
  return String(str ?? '').replace(/['"\\]/g, '');
}

function showToast(msg: string): void {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('visible');
  if (window.__toastTimer) clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(() => el.classList.remove('visible'), 2200);
}

function getInstallPlatform(): InstallPlatform {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent || '' : '';
  const isStandalonePwa =
    (typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(display-mode: standalone)').matches) ||
    Boolean(
      typeof navigator !== 'undefined' &&
        (navigator as Navigator & { standalone?: boolean }).standalone
    );
  return detectInstallPlatform(ua, IS_NATIVE_APK, isStandalonePwa);
}

function syncApkDownloadLinks(): void {
  const base = remoteService.getBaseUrl();
  const href = base ? remoteService.getAndroidApkDownloadUrl() : '/SoarTV-Remote.apk';
  const bannerLink = document.getElementById('apkDownloadLink') as HTMLAnchorElement | null;
  const tab5Link = document.getElementById('tab5ApkDownloadBtn') as HTMLAnchorElement | null;
  const sheetApkLink = document.getElementById('installSheetApkLink') as HTMLAnchorElement | null;
  if (bannerLink) bannerLink.href = href;
  if (tab5Link) tab5Link.href = href;
  if (sheetApkLink) sheetApkLink.href = href;
}

function applyRoleVisibility(role: RemoteRole | ''): void {
  currentRole = role;
  if (role) writeStorage(STORAGE_ROLE, role);

  const roleBadge = document.getElementById('roleHeaderBadge');
  const pairBtn = document.getElementById('headerPairBtn');
  const roleSub = document.getElementById('settingsRoleSub');
  const upgradeBtn = document.getElementById('settingsUpgradeRoleBtn');
  const quickInviteAdminBtn = document.getElementById('settingsQuickInviteAdminBtn');
  const roleLabel = role === 'ADMIN' ? 'Admin' : role === 'HOUSEHOLD' ? 'Household' : role ? 'Guest' : '';
  if (roleBadge) {
    if (!role) {
      roleBadge.style.display = 'none';
    } else {
      roleBadge.style.display = 'inline-flex';
      roleBadge.textContent = roleLabel;
    }
  }
  if (quickInviteAdminBtn) {
    quickInviteAdminBtn.style.display = role === 'ADMIN' ? 'inline-flex' : 'none';
  }
  if (upgradeBtn) {
    upgradeBtn.style.display = role && role !== 'ADMIN' ? 'inline-flex' : 'none';
  }
  if (roleSub) {
    roleSub.textContent = role ? `Connected · ${roleLabel}` : 'Not paired';
  }
  if (pairBtn) {
    pairBtn.style.display = role ? 'none' : 'inline-flex';
  }

  const isAdmin = role === 'ADMIN';
  const adminDevicesSec = document.getElementById('adminDevicesSection');
  if (adminDevicesSec) {
    adminDevicesSec.style.display = isAdmin ? 'block' : 'none';
  }

  const pcUnlockSec = document.getElementById('pcUnlockCard');
  if (pcUnlockSec) {
    pcUnlockSec.style.display = isAdmin ? 'flex' : 'none';
  }

  const launcherUpdateSec = document.getElementById('adminSystemCard');
  if (launcherUpdateSec) {
    launcherUpdateSec.style.display = isAdmin ? 'flex' : 'none';
  }

  refreshBiometricUiState();
}

function refreshBiometricUiState(): void {
  const bioRow = document.getElementById('biometricUnlockRow');
  const bioUnlockBtn = document.getElementById('biometricUnlockBtn');
  const saveBioBtn = document.getElementById('savePinBiometricBtn');
  const clearBioBtn = document.getElementById('clearPinBiometricBtn');

  const available = Boolean(nativeBridge && nativeBridge.isBiometricAvailable() && currentRole === 'ADMIN');
  if (!bioRow) return;
  if (!available) {
    bioRow.style.display = 'none';
    return;
  }
  bioRow.style.display = 'flex';
  const hasSaved = Boolean(nativeBridge && nativeBridge.hasSavedPcUnlockPin());
  if (bioUnlockBtn) {
    bioUnlockBtn.style.display = hasSaved ? 'inline-flex' : 'none';
  }
  if (saveBioBtn) {
    saveBioBtn.textContent = hasSaved ? 'Update Fingerprint PIN' : 'Save PIN to Fingerprint';
  }
  if (clearBioBtn) {
    clearBioBtn.style.display = hasSaved ? 'inline-flex' : 'none';
  }
}

function stopPairingPoll(): void {
  if (pairPollTimer) {
    clearInterval(pairPollTimer);
    pairPollTimer = null;
  }
}

let qrMediaStream: MediaStream | null = null;
let qrScanTimer: ReturnType<typeof setInterval> | null = null;
let qrFinderConsecutiveHits = 0;
let qrVerifyInFlight = false;

function stopQrScanner(): void {
  if (qrScanTimer) {
    clearInterval(qrScanTimer);
    qrScanTimer = null;
  }
  qrFinderConsecutiveHits = 0;
  if (qrMediaStream) {
    for (const track of qrMediaStream.getTracks()) {
      try {
        track.stop();
      } catch {
        // Ignore
      }
    }
    qrMediaStream = null;
  }
  const video = document.getElementById('qrVideo') as HTMLVideoElement | null;
  if (video) {
    video.srcObject = null;
  }
  const box = document.getElementById('qrScannerBox');
  if (box) {
    box.style.display = 'none';
  }
}

function setPairingOverlayVisible(visible: boolean): void {
  const overlay = document.getElementById('pairingOverlay');
  if (!overlay) return;
  overlay.classList.toggle('visible', visible);
  if (!visible) {
    stopPairingPoll();
    stopQrScanner();
  }
}

function closePairingOverlayManual(): void {
  setPairingOverlayVisible(false);
}

function handlePairingBackdropClick(ev: MouseEvent): void {
  if (ev.target === ev.currentTarget && isPaired) {
    closePairingOverlayManual();
  }
}

function rePairAsAdmin(): void {
  closeSheet('settings');
  isPaired = false;
  isGuestExpiredState = false;
  activePairRequestId = '';
  remoteService.setAuthToken('');
  writeStorage(STORAGE_TOKEN, '');
  writeStorage(STORAGE_ROLE, '');
  clearHouseholdDirectoryCreds();
  applyRoleVisibility('');
  setPairingOverlayVisible(true);
  renderGoogleAuthAvailability(currentAuthConfig);
  updateOverlayStatusText(
    'Upgrade to Admin',
    'Sign in with Google below or select Make Admin on your TV screen.'
  );
  void requestTvPairPrompt(true);
}

function updateOverlayStatusText(title: string, subtitle: string): void {
  const t = document.getElementById('pairingOverlayTitle');
  const s = document.getElementById('pairingOverlaySub');
  if (t) t.textContent = title;
  if (s) s.textContent = subtitle;
}

async function ensureTvDiscovered(forceRescan: boolean = false): Promise<string> {
  if (!forceRescan && remoteService.getBaseUrl()) {
    return remoteService.getBaseUrl();
  }
  const hint = remoteService.getBaseUrl() || readStorage(STORAGE_BASE_URL);
  const discovered = await TvRemoteService.discoverLanTv(hint);
  if (discovered) {
    remoteService.setBaseUrl(discovered);
    writeStorage(STORAGE_BASE_URL, discovered);
    syncApkDownloadLinks();
    return discovered;
  }
  return remoteService.getBaseUrl();
}

async function handleScannedQrPayload(
  rawPayload: string,
  fromBrowserInviteLink: boolean = false
): Promise<boolean> {
  const parsed = TvRemoteService.parsePairingQrPayload(rawPayload);
  if (!parsed.baseUrl && !parsed.qrSecret && !parsed.code && !parsed.token) {
    return false;
  }
  if (parsed.baseUrl) {
    remoteService.setBaseUrl(parsed.baseUrl);
    writeStorage(STORAGE_BASE_URL, parsed.baseUrl);
    syncApkDownloadLinks();
  } else if (!remoteService.getBaseUrl()) {
    await ensureTvDiscovered(true);
  }
  if (parsed.token) {
    stopQrScanner();
    await completePairingSuccess(parsed.token, parsed.role || 'GUEST', fromBrowserInviteLink);
    return true;
  }
  if (!remoteService.getBaseUrl()) {
    showToast('Searching for SoarTV on Wi-Fi...');
    return false;
  }
  try {
    qrVerifyInFlight = true;
    const verified = await remoteService.verifyPairing({
      qrSecret: parsed.qrSecret,
      code: parsed.code,
      deviceName: detectDeviceName(),
    });
    stopQrScanner();
    await completePairingSuccess(verified.token, verified.role, fromBrowserInviteLink);
    return true;
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'QR code expired — scan the fresh QR on TV');
    void requestTvPairPrompt();
    return false;
  } finally {
    qrVerifyInFlight = false;
  }
}

async function startQrScanner(): Promise<void> {
  setPairingOverlayVisible(true);
  void requestTvPairPrompt(true);

  // Native Android APK: open full-screen hardware Camera2 QR viewfinder immediately on tap
  if (nativeBridge && typeof nativeBridge.openNativeQrScanner === 'function') {
    try {
      nativeBridge.openNativeQrScanner();
      return;
    } catch {
      // Fall through to browser WebRTC scanner if native bridge fails
    }
  }

  const box = document.getElementById('qrScannerBox');
  const video = document.getElementById('qrVideo') as HTMLVideoElement | null;
  const hint = document.getElementById('qrScannerHint');
  if (box) box.style.display = 'flex';
  if (hint) hint.textContent = 'Opening camera — point at the QR code on your TV...';

  if (typeof navigator === 'undefined' || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    if (hint) hint.textContent = 'Tap Snap QR to scan the TV QR code with your camera.';
    const fileInput = document.getElementById('qrFileInput') as HTMLInputElement | null;
    fileInput?.click();
    return;
  }

  stopQrScanner();
  if (box) box.style.display = 'flex';

  try {
    qrMediaStream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 640 },
        height: { ideal: 480 },
      },
      audio: false,
    });
    if (video) {
      video.srcObject = qrMediaStream;
      await video.play().catch(() => {});
    }
    if (hint) hint.textContent = 'Point your phone at the QR code on the TV screen...';
  } catch {
    if (hint) hint.textContent = 'Tap Snap QR to capture the TV QR code.';
    return;
  }

  let detector: BarcodeDetectorLike | null = null;
  if (typeof window !== 'undefined' && window.BarcodeDetector) {
    try {
      detector = new window.BarcodeDetector({ formats: ['qr_code'] });
    } catch {
      detector = null;
    }
  }

  const canvas = document.createElement('canvas');
  const cw = 160;
  const ch = 120;
  canvas.width = cw;
  canvas.height = ch;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const lumaBuf = new Uint8Array(cw * ch);

  qrFinderConsecutiveHits = 0;
  qrScanTimer = setInterval(async () => {
    if (!video || video.readyState < 2 || qrVerifyInFlight) return;

    // 1. Hardware / OS BarcodeDetector (extracts full URL + qrSecret directly from QR)
    if (detector) {
      try {
        const codes = await detector.detect(video);
        if (codes && codes.length > 0 && codes[0].rawValue) {
          await handleScannedQrPayload(codes[0].rawValue);
          return;
        }
      } catch {
        // Fall through to ISO-18004 Finder-Pattern lock
      }
    }

    // 2. Built-in ISO-18004 1:1:3:1:1 QR Finder-Pattern Lock + Wi-Fi Auto-Discovery
    if (ctx) {
      try {
        ctx.drawImage(video, 0, 0, cw, ch);
        const img = ctx.getImageData(0, 0, cw, ch).data;
        for (let i = 0, p = 0; i < img.length; i += 4, p++) {
          lumaBuf[p] = (img[i] * 77 + img[i + 1] * 150 + img[i + 2] * 29) >> 8;
        }
        if (TvRemoteService.hasQrFinderPatternInLuma(lumaBuf, cw, ch)) {
          qrFinderConsecutiveHits++;
          if (qrFinderConsecutiveHits >= 2 && activePairRequestId && remoteService.getBaseUrl()) {
            qrFinderConsecutiveHits = 0;
            qrVerifyInFlight = true;
            try {
              const verified = await remoteService.verifyPairing({
                requestId: activePairRequestId,
                clientId: getOrCreateClientId(),
                qrCameraDetected: true,
                deviceName: detectDeviceName(),
              });
              stopQrScanner();
              await completePairingSuccess(verified.token, verified.role);
            } catch {
              // Keep scanning
            } finally {
              qrVerifyInFlight = false;
            }
          }
        } else {
          qrFinderConsecutiveHits = 0;
        }
      } catch {
        // Ignore frame capture errors
      }
    }
  }, 220);
}

async function scanQrImageFile(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement | null;
  const file = input?.files?.[0];
  if (!file) return;
  try {
    if (typeof createImageBitmap === 'function') {
      const bmp = await createImageBitmap(file);
      if (typeof window !== 'undefined' && window.BarcodeDetector) {
        try {
          const det = new window.BarcodeDetector({ formats: ['qr_code'] });
          const codes = await det.detect(bmp);
          if (codes && codes.length > 0 && codes[0].rawValue) {
            await handleScannedQrPayload(codes[0].rawValue);
            return;
          }
        } catch {
          // Fall through to Finder-Pattern check
        }
      }
      const canvas = document.createElement('canvas');
      canvas.width = 240;
      canvas.height = 180;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.drawImage(bmp, 0, 0, 240, 180);
        const img = ctx.getImageData(0, 0, 240, 180).data;
        const luma = new Uint8Array(240 * 180);
        for (let i = 0, p = 0; i < img.length; i += 4, p++) {
          luma[p] = (img[i] * 77 + img[i + 1] * 150 + img[i + 2] * 29) >> 8;
        }
        if (TvRemoteService.hasQrFinderPatternInLuma(luma, 240, 180) && activePairRequestId) {
          const verified = await remoteService.verifyPairing({
            requestId: activePairRequestId,
            clientId: getOrCreateClientId(),
            qrCameraDetected: true,
            deviceName: detectDeviceName(),
          });
          stopQrScanner();
          await completePairingSuccess(verified.token, verified.role);
          return;
        }
      }
    }
    showToast('Could not read QR — point live camera at TV or tap Approve on TV');
  } catch {
    showToast('Could not scan photo');
  } finally {
    if (input) input.value = '';
  }
}

function applyPairingSheetCopy(bootstrapAdmin: boolean, guestExpired: boolean, tvName?: string): void {
  const copy = formatPairingSheetCopy(bootstrapAdmin, guestExpired, tvName);
  updateOverlayStatusText(copy.title, copy.subtitle);
  const badge = document.getElementById('pairingOwnerBadge');
  if (badge) {
    badge.style.display = copy.badge ? 'inline-flex' : 'none';
    if (copy.badge) badge.textContent = copy.badge;
  }
  const more = document.getElementById('pairingMoreOptions') as HTMLDetailsElement | null;
  if (more) {
    more.open = copy.expandManualByDefault;
  }
}

function handleUnauthorizedTransition(guestExpired: boolean): void {
  isPaired = false;
  isGuestExpiredState = guestExpired;
  remoteService.setAuthToken('');
  writeStorage(STORAGE_TOKEN, '');
  writeStorage(STORAGE_ROLE, '');
  clearHouseholdDirectoryCreds();
  applyRoleVisibility('');
  updateFastInputPollingState();
  setPairingOverlayVisible(true);
  applyPairingSheetCopy(Boolean(currentAuthConfig?.bootstrapAdmin), guestExpired, currentAuthConfig?.tvName);
  void requestTvPairPrompt();
}

function openInstallAppSheet(roleOverride?: RemoteRole | ''): void {
  const platform = getInstallPlatform();
  if (platform === 'installed_app') return;
  syncApkDownloadLinks();
  const role = roleOverride || currentRole;
  const roleLabel =
    role === 'ADMIN' ? 'Admin' : role === 'HOUSEHOLD' ? 'Household' : role === 'GUEST' ? 'Guest (30d)' : '';
  const badgeEl = document.getElementById('installSheetBadge');
  const titleEl = document.getElementById('installSheetTitle');
  const subEl = document.getElementById('installSheetSub');
  const androidFlow = document.getElementById('installAndroidFlow');
  const iosFlow = document.getElementById('installIosFlow');

  if (badgeEl) {
    badgeEl.style.display = roleLabel ? 'inline-flex' : 'none';
    if (roleLabel) badgeEl.textContent = `Paired as ${roleLabel}`;
  }
  if (platform === 'ios_browser') {
    if (titleEl) titleEl.textContent = 'Add SoarTV to Home Screen';
    if (subEl) subEl.textContent = 'Use SoarTV full-screen on iPhone like a native app';
    if (androidFlow) androidFlow.style.display = 'none';
    if (iosFlow) iosFlow.style.display = 'flex';
  } else {
    if (titleEl) titleEl.textContent = 'Install SoarTV Remote App';
    if (subEl) subEl.textContent = 'Edge-to-edge native app with hardware volume & fingerprint';
    if (androidFlow) androidFlow.style.display = 'flex';
    if (iosFlow) iosFlow.style.display = 'none';
  }
  openSheet('install-app');
}

async function completePairingSuccess(
  token: string,
  role: RemoteRole,
  fromBrowserInviteLink: boolean = false
): Promise<void> {
  stopPairingPoll();
  stopQrScanner();
  activePairRequestId = '';
  isPaired = true;
  isGuestExpiredState = false;
  remoteService.setAuthToken(token);
  writeStorage(STORAGE_TOKEN, token);
  applyRoleVisibility(role);
  setPairingOverlayVisible(false);
  showToast(
    role === 'ADMIN'
      ? 'Paired as Owner / Admin'
      : role === 'HOUSEHOLD'
        ? 'Paired as Household Member'
        : 'Paired as Guest (30 Days)'
  );
  await refreshStatus();
  if (role === 'ADMIN' || role === 'HOUSEHOLD') {
    try {
      const freshCfg = await remoteService.getAuthConfig();
      currentAuthConfig = freshCfg;
      persistHouseholdDirectoryCreds(freshCfg);
      void refreshHouseholdTvDirectory(false);
    } catch {
      // Non-fatal
    }
  } else {
    clearHouseholdDirectoryCreds();
  }
  updateFastInputPollingState();
  void wakeTvOnRemoteActivity();
  void loadGamingZone(false);
  if (role === 'ADMIN') {
    void refreshPairedDevices();
  }
  if (fromBrowserInviteLink) {
    const platform = getInstallPlatform();
    if (platform === 'android_browser' || platform === 'ios_browser') {
      openInstallAppSheet(role);
    }
  }
}

async function requestTvPairPrompt(forceFresh: boolean = false): Promise<void> {
  const waitBadge = document.getElementById('pairPollStatusText');
  let base = remoteService.getBaseUrl();
  if (!base) {
    if (waitBadge) waitBadge.textContent = 'Finding your SoarTV on Wi-Fi...';
    base = await ensureTvDiscovered(true);
  }
  if (!base) {
    if (waitBadge) {
      waitBadge.textContent = 'Tap Scan TV QR Code above to locate and pair with your TV.';
    }
    return;
  }

  try {
    const cfg = await remoteService.getAuthConfig();
    currentAuthConfig = cfg;
    renderGoogleAuthAvailability(cfg);
    if (!forceFresh && cfg.paired && cfg.role) {
      await completePairingSuccess(remoteService.getAuthToken(), cfg.role);
      return;
    }
    if (!forceFresh && cfg.handoffToken) {
      await completePairingSuccess(cfg.handoffToken, cfg.handoffRole || 'GUEST');
      return;
    }
    applyPairingSheetCopy(Boolean(cfg.bootstrapAdmin), isGuestExpiredState, cfg.tvName);
  } catch {
    // Cached TV address may have changed after router/TV restart: auto-discover on Wi-Fi
    if (waitBadge) waitBadge.textContent = 'Locating SoarTV on Wi-Fi...';
    const recovered = await ensureTvDiscovered(true);
    if (!recovered) {
      document.getElementById('statusDot')?.classList.add('offline');
      if (waitBadge) {
        waitBadge.textContent = 'Scan the TV QR code above or verify phone and TV are on the same Wi-Fi.';
      }
      return;
    }
  }

  try {
    const res = await remoteService.requestPairing(detectDeviceName(), getOrCreateClientId());
    activePairRequestId = res.requestId;
    const isBootstrap = Boolean(res.bootstrapAdmin || currentAuthConfig?.bootstrapAdmin);
    applyPairingSheetCopy(isBootstrap, isGuestExpiredState, currentAuthConfig?.tvName);
    if (waitBadge) {
      waitBadge.textContent = formatPairingSheetCopy(
        isBootstrap,
        isGuestExpiredState,
        currentAuthConfig?.tvName
      ).waitingStatus;
    }
    startPairingPollLoop();
  } catch (err) {
    if (waitBadge) {
      waitBadge.textContent = err instanceof Error ? err.message : 'Could not open pairing prompt on TV';
    }
  }
}

function startPairingPollLoop(): void {
  stopPairingPoll();
  if (!activePairRequestId) return;
  pairPollTimer = setInterval(async () => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    if (!activePairRequestId) {
      stopPairingPoll();
      return;
    }
    try {
      const poll = await remoteService.pollPairing(activePairRequestId);
      const waitBadge = document.getElementById('pairPollStatusText');
      if (poll.status === 'approved' && poll.token && poll.role) {
        await completePairingSuccess(poll.token, poll.role);
      } else if (poll.status === 'dismissed') {
        activePairRequestId = '';
        stopPairingPoll();
        if (waitBadge) waitBadge.textContent = 'Prompt dismissed on TV. Tap Show QR on TV to try again.';
      } else if (poll.status === 'expired') {
        activePairRequestId = '';
        stopPairingPoll();
        if (waitBadge) waitBadge.textContent = 'Pairing code expired. Tap Show QR on TV for a fresh QR code.';
      }
    } catch {
      // Transient LAN hiccup; next poll will retry
    }
  }, 1500);
}

function renderGoogleAuthAvailability(cfg: AuthConfigResponse | null): void {
  const googleWrap = document.getElementById('googleAdminAuthWrap');
  if (!googleWrap) return;
  googleWrap.style.display = cfg && cfg.googleAuthEnabled && cfg.supabaseUrl ? 'flex' : 'none';
}

async function submitSixDigitPairCode(): Promise<void> {
  const input = document.getElementById('pairCodeInput') as HTMLInputElement | null;
  const code = input ? input.value.trim() : '';
  if (!/^\d{6}$/.test(code)) {
    showToast('Enter the 6-digit code shown on your TV');
    return;
  }
  if (!remoteService.getBaseUrl()) {
    await ensureTvDiscovered(true);
  }
  try {
    const res = await remoteService.verifyPairing({
      code,
      deviceName: detectDeviceName(),
    });
    if (input) input.value = '';
    await completePairingSuccess(res.token, res.role);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Invalid 6-digit code');
  }
}

async function signInWithGoogleAdmin(): Promise<void> {
  try {
    if (!remoteService.getBaseUrl()) {
      await ensureTvDiscovered(true);
    }
    if (!currentAuthConfig) {
      currentAuthConfig = await remoteService.getAuthConfig();
    }
    if (!currentAuthConfig.googleAuthEnabled || !currentAuthConfig.supabaseUrl) {
      showToast('Configure Supabase URL on TV first for Google Sign-In');
      return;
    }
    const webOrigin = remoteService.getBaseUrl() || window.location.origin;
    remoteService.startGoogleSignIn(currentAuthConfig.supabaseUrl, IS_NATIVE_APK, undefined, webOrigin);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Cannot reach TV for Google Sign-In');
  }
}

async function bootstrapConnectionAndAuth(): Promise<void> {
  syncApkDownloadLinks();

  // 1. Check if user opened a QR pairing link (`/?pair=<128-bit-secret>`)
  // IMPORTANT: Capture full href BEFORE replaceState cleans the address bar!
  const initialQrHref = extractInitialQrPairUrl(window.location.href);
  if (initialQrHref) {
    if (window.history && window.history.replaceState) {
      const cleanUrl = window.location.pathname + (IS_NATIVE_APK ? '?native=1' : '');
      window.history.replaceState(null, '', cleanUrl);
    }
    const ok = await handleScannedQrPayload(initialQrHref, true);
    if (ok) return;
  }

  // 2. Ensure TV endpoint is discovered on Wi-Fi automatically (never ask user for an IP)
  if (!remoteService.getBaseUrl()) {
    await ensureTvDiscovered(true);
  }

  // 3. Validate existing token (or same-phone Chrome->APK handoff token) via `/api/auth/config`
  try {
    if (!remoteService.getBaseUrl()) {
      throw new Error('TV not yet discovered');
    }
    const cfg = await remoteService.getAuthConfig();
    currentAuthConfig = cfg;
    persistHouseholdDirectoryCreds(cfg);
    renderGoogleAuthAvailability(cfg);
    if (cfg.paired && cfg.role) {
      isPaired = true;
      isGuestExpiredState = false;
      applyRoleVisibility(cfg.role);
      setPairingOverlayVisible(false);
      updateFastInputPollingState();
      void wakeTvOnRemoteActivity();
      await refreshStatus();
      void refreshHouseholdTvDirectory(false);
      void loadGamingZone(false);
      if (cfg.role === 'ADMIN') {
        void refreshPairedDevices();
      }
    } else if (cfg.handoffToken) {
      await completePairingSuccess(cfg.handoffToken, cfg.handoffRole || 'GUEST');
    } else {
      handleUnauthorizedTransition(Boolean(cfg.guestExpired));
    }
  } catch {
    document.getElementById('statusDot')?.classList.add('offline');
    // Cached TV address may be stale after power-cycle or DHCP change: self-heal via Supabase tv_devices -> mDNS -> UDP/LAN
    const previousUrl = remoteService.getBaseUrl();
    const activeKnown = knownHouseholdTvs.find((t) => t.deviceId === activeTvDeviceId) || null;
    const resolved = await TvRemoteService.resolveReachableTv(
      activeKnown,
      previousUrl,
      getHouseholdDirectoryCreds()
    );
    if (resolved && resolved.mode === 'lan' && resolved.baseUrl && resolved.baseUrl !== previousUrl) {
      remoteService.setBaseUrl(resolved.baseUrl);
      writeStorage(STORAGE_BASE_URL, resolved.baseUrl);
      syncApkDownloadLinks();
      void bootstrapConnectionAndAuth();
      return;
    }
    if (!isPaired) {
      setPairingOverlayVisible(true);
      updateOverlayStatusText(
        'Pair Companion Remote',
        'Tap Pair (Scan TV QR Code) below to connect to your TV automatically.'
      );
      if (nativeBridge && typeof nativeBridge.startNativeTvDiscovery === 'function') {
        nativeBridge.startNativeTvDiscovery(previousUrl);
      }
    }
  }
}

function openQrPairScanner(): void {
  void startQrScanner();
}

function openPairingOverlayManual(forceFresh: boolean = false): void {
  setPairingOverlayVisible(true);
  void requestTvPairPrompt(forceFresh);
}

function toggleFullscreen(): void {
  const doc = document as Document & {
    webkitFullscreenElement?: Element;
    webkitExitFullscreen?: () => void;
  };
  const el = doc.documentElement as HTMLElement & {
    webkitRequestFullscreen?: () => void;
  };
  if (!doc.fullscreenElement && !doc.webkitFullscreenElement) {
    if (el.requestFullscreen) el.requestFullscreen().catch(() => {});
    else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen();
    showToast('Full-screen mode');
  } else {
    if (doc.exitFullscreen) doc.exitFullscreen().catch(() => {});
    else if (doc.webkitExitFullscreen) doc.webkitExitFullscreen();
  }
}

function dismissApkBanner(): void {
  const b = document.getElementById('apkInstallBanner');
  if (b) b.classList.add('hidden');
  try {
    sessionStorage.setItem('soartv_hide_apk_banner', '1');
  } catch {
    // Ignore
  }
}

function applyTheme(dark: boolean): void {
  const mode = dark ? 'dark' : 'light';
  document.documentElement.setAttribute('data-theme', mode);
  const meta = getThemeColorMeta(dark);
  const metaEl = document.getElementById('themeColorMeta') as HTMLMetaElement | null;
  if (metaEl) {
    metaEl.content = meta.themeColor;
  }
  if (nativeBridge && typeof nativeBridge.setSystemBarTheme === 'function') {
    try {
      nativeBridge.setSystemBarTheme(dark);
    } catch {
      // Ignore native bridge mismatch on older APKs
    }
  }
  const icon = document.getElementById('themeIcon');
  if (icon) {
    icon.innerHTML = dark
      ? '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>'
      : '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/>';
  }
  const themeLbl = document.getElementById('themeToggleLabel');
  if (themeLbl) {
    themeLbl.textContent = dark ? 'Dark Theme' : 'Light Theme';
  }
}

function openSheet(sheetName: string): void {
  document.querySelectorAll('.sheet-overlay').forEach((el) => {
    if (el.id !== 'pairingOverlay') el.classList.remove('visible');
  });
  const el = document.getElementById(`sheet-${sheetName}`);
  if (el) el.classList.add('visible');
  if (sheetName === 'settings' && currentRole === 'ADMIN') {
    void refreshPairedDevices();
  }
}

function closeSheet(sheetName: string): void {
  const el = document.getElementById(`sheet-${sheetName}`);
  if (el) el.classList.remove('visible');
  if (sheetName === 'search') {
    isSearchInputDirty = false;
    updateFastInputPollingState();
  }
}

function closeAllSheets(): void {
  document.querySelectorAll('.sheet-overlay').forEach((el) => {
    if (el.id !== 'pairingOverlay') el.classList.remove('visible');
  });
  isSearchInputDirty = false;
  updateFastInputPollingState();
}

function handleSheetBackdropClick(ev: MouseEvent, sheetName: string): void {
  if (ev.target === ev.currentTarget) {
    closeSheet(sheetName);
  }
}

async function pollLiveState(): Promise<void> {
  if (!isPaired || !remoteService.getBaseUrl() || livePollInFlight) return;
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
  livePollInFlight = true;
  try {
    const live = await remoteService.getLiveState();
    document.getElementById('statusDot')?.classList.remove('offline');
    if (typeof live.activeScreen === 'number' && currentStatus) {
      currentStatus.activeScreen = live.activeScreen;
    }
    renderFocusedInputState(live.focusedInput);
    renderActiveTvModal(live.activeModal);
  } catch (err) {
    if (err instanceof RemoteUnauthorizedError) {
      return;
    }
    // Older TV server without /api/live falls back gracefully to regular /api/status polling
  } finally {
    livePollInFlight = false;
  }
}

function scheduleLiveBurstPolls(): void {
  setTimeout(() => void pollLiveState(), 220);
  setTimeout(() => void pollLiveState(), 650);
  setTimeout(() => void pollLiveState(), 1350);
  setTimeout(() => void pollLiveState(), 2250);
}

function updateFastInputPollingState(): void {
  const sheetOpen = Boolean(document.getElementById('sheet-search')?.classList.contains('visible'));
  const modalOpen = Boolean(activeTvModal?.active);
  const isHidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
  const targetMs = resolveLivePollIntervalMs(
    isPaired,
    isHidden,
    Boolean(sheetOpen || activeFocusedInput?.active || modalOpen)
  );
  if (targetMs === livePollIntervalActiveMs && (targetMs === 0 ? !fastInputPollTimer : Boolean(fastInputPollTimer))) {
    return;
  }
  if (fastInputPollTimer) {
    clearInterval(fastInputPollTimer);
    fastInputPollTimer = null;
  }
  livePollIntervalActiveMs = targetMs;
  if (targetMs > 0) {
    fastInputPollTimer = setInterval(() => {
      void pollLiveState();
    }, targetMs);
  }
}

function wakeTvOnRemoteActivity(): void {
  if (!isPaired || !remoteService.getBaseUrl()) return;
  const now = Date.now();
  if (now - lastAutoWakeAtMs < 4000) return;
  lastAutoWakeAtMs = now;
  void remoteService.wakeTv();
}

function renderActiveTvModal(incoming?: TvActiveModal | null): void {
  const overlay = document.getElementById('sheet-tv-modal');
  const pairingOpen = Boolean(document.getElementById('pairingOverlay')?.classList.contains('visible'));
  const decision = resolveMirroredModalVisibility(incoming, dismissedTvModalId, pairingOpen);

  if (!incoming || !incoming.active) {
    activeTvModal = null;
    dismissedTvModalId = '';
    lastRenderedTvModalSig = '';
    overlay?.classList.remove('visible');
    updateFastInputPollingState();
    return;
  }

  activeTvModal = incoming;
  if (!decision.visible) {
    overlay?.classList.remove('visible');
    updateFastInputPollingState();
    return;
  }

  const sig = JSON.stringify(incoming);
  if (sig !== lastRenderedTvModalSig) {
    lastRenderedTvModalSig = sig;
    const titleEl = document.getElementById('tvModalTitle');
    const subEl = document.getElementById('tvModalSub');
    const codeRow = document.getElementById('tvModalCodeRow');
    const codeText = document.getElementById('tvModalCodeText');
    const urlBtn = document.getElementById('tvModalOpenUrlBtn');
    const urlLbl = document.getElementById('tvModalOpenUrlLabel');
    const actionsEl = document.getElementById('tvModalActionsList');

    if (titleEl) titleEl.textContent = incoming.title || 'Action on TV';
    if (subEl) {
      subEl.textContent = incoming.subtitle || '';
      subEl.style.display = incoming.subtitle ? 'block' : 'none';
    }
    if (codeRow && codeText) {
      const hasCode = Boolean(incoming.code && incoming.code.trim());
      codeRow.style.display = hasCode ? 'flex' : 'none';
      if (hasCode) codeText.textContent = incoming.code!.trim();
    }
    if (urlBtn && urlLbl) {
      const hasUrl = Boolean(incoming.url && incoming.url.startsWith('https://'));
      urlBtn.style.display = hasUrl ? 'inline-flex' : 'none';
      if (hasUrl) {
        urlLbl.textContent = incoming.primaryUrlLabel || 'Open in Browser';
      }
    }
    if (actionsEl) {
      const actions = Array.isArray(incoming.actions) ? incoming.actions : [];
      actionsEl.innerHTML = actions
        .map((act) => {
          const styleCls =
            act.style === 'primary'
              ? 'btn pill-focus'
              : act.style === 'blue'
                ? 'btn btn-blue'
                : act.style === 'danger'
                  ? 'btn btn-danger'
                  : 'btn';
          return `<button class="${styleCls}" style="border-radius:999px; padding:11.5px 16px;" onclick="triggerTvModalAction('${escapeAttr(act.id)}')">${escapeHtml(act.label)}</button>`;
        })
        .join('');
    }
  }
  overlay?.classList.add('visible');
  updateFastInputPollingState();
}

function dismissMirroredTvModalLocally(): void {
  if (activeTvModal) {
    dismissedTvModalId = (activeTvModal.id || activeTvModal.title || '').trim();
  }
  document.getElementById('sheet-tv-modal')?.classList.remove('visible');
}

function handleTvModalBackdropClick(ev: MouseEvent): void {
  if (ev.target === ev.currentTarget) {
    dismissMirroredTvModalLocally();
  }
}

async function copyTvModalCode(): Promise<void> {
  const code = activeTvModal?.code?.trim() || '';
  if (!code) return;
  const ok = await writeToPhoneClipboard(code);
  showToast(ok ? `Copied code: ${code}` : code);
}

async function openTvModalExternalLink(): Promise<void> {
  const url = activeTvModal?.url?.trim() || '';
  if (!url || !url.startsWith('https://')) return;
  const code = activeTvModal?.code?.trim() || '';
  if (code) {
    await writeToPhoneClipboard(code);
    showToast(`Copied ${code} · Opening browser...`);
  } else {
    showToast('Opening link in browser...');
  }
  if (nativeBridge && typeof nativeBridge.openExternalUrl === 'function') {
    nativeBridge.openExternalUrl(url);
    return;
  }
  try {
    window.open(url, '_blank', 'noopener');
  } catch {
    window.location.href = url;
  }
}

async function triggerTvModalAction(actionId: string): Promise<void> {
  if (!actionId) return;
  const ok = await remoteService.executeModalAction(actionId);
  if (ok) {
    if (actionId === 'dismiss' || actionId.startsWith('pair_') || actionId.startsWith('lg_choice:') || actionId.startsWith('menu_pick:') || actionId === 'sleep_save' || actionId === 'stremio_manual') {
      document.getElementById('sheet-tv-modal')?.classList.remove('visible');
    }
    scheduleLiveBurstPolls();
    setTimeout(() => void refreshStatus(), 400);
  }
}

function updateCopyTvInputButtonVisibility(): void {
  const copyBtn = document.getElementById('copyTvInputBtn');
  if (!copyBtn) return;
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  const isPwd = Boolean(activeFocusedInput?.active && activeFocusedInput.isPassword);
  const hasText = Boolean(!isPwd && ((input && input.value.length > 0) || activeFocusedInput?.text));
  copyBtn.style.display = hasText ? 'inline-flex' : 'none';
}

function renderFocusedInputState(incoming?: TvFocusedInput | null): void {
  const active = Boolean(incoming && incoming.active);
  activeFocusedInput = active && incoming ? incoming : null;

  const cue = formatFocusedInputCue(activeFocusedInput);
  const banner = document.getElementById('liveInputBanner');
  const appEl = document.getElementById('liveInputAppLabel');
  const previewEl = document.getElementById('liveInputPreview');
  const bannerCopyBtn = document.getElementById('liveInputCopyBtn');
  const searchOrb = document.getElementById('openSearchSheetBtn');

  if (searchOrb) {
    searchOrb.classList.toggle('has-live-input', cue.visible);
  }
  if (banner) {
    banner.classList.toggle('visible', cue.visible);
  }
  if (appEl && cue.visible) {
    appEl.textContent = `${cue.appLabel} · TV Input`;
  }
  if (previewEl && cue.visible) {
    previewEl.textContent = cue.previewText;
  }
  if (bannerCopyBtn) {
    bannerCopyBtn.style.display = cue.canCopy ? 'inline-flex' : 'none';
  }

  const titleEl = document.getElementById('searchSheetTitle');
  const subEl = document.getElementById('searchSheetSub');
  const primaryLabel = document.getElementById('searchPrimaryLabel');
  const clearBtn = document.getElementById('clearTvInputBtn');
  const geminiBtn = document.getElementById('geminiSearchBtn');
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  const activelyTyping = liveMirrorInFlight || Date.now() - lastLocalEditAtMs < 1400;

  if (active && activeFocusedInput) {
    const appName = (activeFocusedInput.appLabel || '').trim() || 'TV';
    if (titleEl) titleEl.textContent = `Typing in ${appName}`;
    if (subEl) {
      subEl.style.display = 'block';
      subEl.textContent = activeFocusedInput.isPassword
        ? 'Password field · Typing mirrors live to TV'
        : 'Live sync · Typing and pasting mirror straight to TV';
    }
    if (primaryLabel) primaryLabel.textContent = 'Done';
    if (clearBtn) clearBtn.style.display = 'inline-flex';
    if (geminiBtn) geminiBtn.style.display = 'none';
    if (input) {
      input.type = activeFocusedInput.isPassword ? 'password' : 'text';
      input.placeholder = activeFocusedInput.isPassword
        ? 'Type password for TV...'
        : activeFocusedInput.hint || `Type in ${appName}...`;
      const decision = resolveTvInputSyncDecision(
        lastSyncedTvInputSig,
        activeFocusedInput,
        isSearchInputDirty || activelyTyping
      );
      if (decision.shouldSync) {
        input.value = decision.nextValue;
        lastSyncedTvInputSig = decision.nextSig;
        lastMirroredTvText = decision.nextValue;
      }
    }
  } else {
    lastSyncedTvInputSig = '';
    if (titleEl) titleEl.textContent = 'Keyboard & AI Search';
    if (subEl) subEl.style.display = 'none';
    if (primaryLabel) primaryLabel.textContent = 'Search';
    if (clearBtn) clearBtn.style.display = 'none';
    if (geminiBtn) geminiBtn.style.display = 'inline-flex';
    if (input) {
      input.type = 'text';
      input.placeholder = 'Type, search, or paste to TV...';
    }
  }

  updateCopyTvInputButtonVisibility();
  updateFastInputPollingState();
}

function flushLiveTvMirror(textToSend: string): void {
  const hasFocused = Boolean(activeFocusedInput?.active);
  const onSearch = Boolean(currentStatus && currentStatus.activeScreen === 0);
  const decision = resolveLiveMirrorDecision(textToSend, lastMirroredTvText, hasFocused, onSearch);
  if (!decision.shouldMirror) return;

  if (liveMirrorInFlight) {
    liveMirrorQueuedText = decision.value;
    return;
  }

  liveMirrorInFlight = true;
  const sentValue = decision.value;
  const subEl = document.getElementById('searchSheetSub');
  void remoteService
    .updateTvInputField(sentValue, false)
    .then((ok) => {
      if (ok) {
        lastMirroredTvText = sentValue;
        isSearchInputDirty = false;
        if (activeFocusedInput?.active && !activeFocusedInput.isPassword) {
          activeFocusedInput = { ...activeFocusedInput, text: sentValue };
          lastSyncedTvInputSig = buildTvInputSyncSignature(activeFocusedInput);
        }
        if (subEl && activeFocusedInput?.active) {
          const appName = (activeFocusedInput.appLabel || '').trim() || 'TV';
          subEl.textContent = activeFocusedInput.isPassword
            ? `Synced live to ${appName} password field`
            : `Synced live to ${appName}`;
        }
      }
    })
    .finally(() => {
      liveMirrorInFlight = false;
      if (liveMirrorQueuedText !== null) {
        const nextVal = liveMirrorQueuedText;
        liveMirrorQueuedText = null;
        if (nextVal !== lastMirroredTvText) {
          flushLiveTvMirror(nextVal);
        }
      }
    });
}

function scheduleLiveTvMirror(immediate: boolean = false): void {
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  if (!input) return;
  const val = input.value;
  if (liveMirrorTimer) {
    clearTimeout(liveMirrorTimer);
    liveMirrorTimer = null;
  }
  if (immediate) {
    flushLiveTvMirror(val);
    return;
  }
  liveMirrorTimer = setTimeout(() => {
    liveMirrorTimer = null;
    const latest = document.getElementById('remoteTextInput') as HTMLInputElement | null;
    if (latest) flushLiveTvMirror(latest.value);
  }, 55);
}

function clearTvInputField(): void {
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  if (input) {
    input.value = '';
    input.focus();
  }
  lastLocalEditAtMs = Date.now();
  isSearchInputDirty = true;
  updateCopyTvInputButtonVisibility();
  scheduleLiveTvMirror(true);
}

function openSearchSheet(): void {
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  isSearchInputDirty = false;
  lastLocalEditAtMs = 0;
  if (activeFocusedInput?.active && input) {
    const initial = activeFocusedInput.isPassword ? '' : activeFocusedInput.text || '';
    input.value = initial;
    lastMirroredTvText = initial;
    lastSyncedTvInputSig = buildTvInputSyncSignature(activeFocusedInput);
  } else if (currentStatus && currentStatus.activeScreen === 0 && input && !input.value) {
    lastMirroredTvText = '';
  }
  updateCopyTvInputButtonVisibility();
  openSheet('search');
  updateFastInputPollingState();
  // Refresh status immediately so any text already on the TV populates the phone box right away
  void refreshStatus().then(() => {
    const el = document.getElementById('remoteTextInput') as HTMLInputElement | null;
    if (el && activeFocusedInput?.active && !isSearchInputDirty && Date.now() - lastLocalEditAtMs > 1200) {
      const freshVal = activeFocusedInput.isPassword ? '' : activeFocusedInput.text || '';
      if (el.value !== freshVal) {
        el.value = freshVal;
        lastMirroredTvText = freshVal;
        lastSyncedTvInputSig = buildTvInputSyncSignature(activeFocusedInput);
        updateCopyTvInputButtonVisibility();
        try {
          el.setSelectionRange(freshVal.length, freshVal.length);
        } catch {
          // Ignore non-selectable input types
        }
      }
    }
  });
  setTimeout(() => {
    const el = document.getElementById('remoteTextInput') as HTMLInputElement | null;
    if (el) {
      el.focus();
      try {
        const len = el.value.length;
        el.setSelectionRange(len, len);
      } catch {
        // Ignore non-selectable input types
      }
    }
  }, 120);
}

function setPadMode(mode: 'pad' | 'keys'): void {
  const stage = document.getElementById('navStage');
  if (stage) stage.setAttribute('data-pad-mode', mode);
  document.getElementById('modePadBtn')?.classList.toggle('active', mode === 'pad');
  document.getElementById('modeKeysBtn')?.classList.toggle('active', mode === 'keys');
  writeStorage(STORAGE_PAD_MODE, mode);
}

async function toggleTheme(): Promise<void> {
  const currentDark = document.documentElement.getAttribute('data-theme') !== 'light';
  localThemeOverride = !currentDark;
  applyTheme(localThemeOverride);
  await sendKey('theme');
  showToast(localThemeOverride ? 'Dark Twilight Aurora' : 'Light Pearl Glass');
}

function switchTab(tabId: string): void {
  document.querySelectorAll('.tab-pane').forEach((p) => p.classList.remove('active'));
  document.querySelectorAll('.dock-tab').forEach((b) => b.classList.remove('active'));
  const pane = document.getElementById(`tab-${tabId}`);
  if (pane) pane.classList.add('active');
  const btn = document.querySelector(`.dock-tab[data-tab="${tabId}"]`);
  if (btn) btn.classList.add('active');
  if (tabId === 'gaming') void loadGamingZone(false);
  if (tabId === 'apps' && currentRole === 'ADMIN') void refreshPairedDevices();
  void refreshStatus();
}

async function switchTvScreen(idx: number, keyName: RemoteKeyName): Promise<void> {
  document.querySelectorAll('.tv-screen-pill').forEach((b) => {
    b.classList.toggle('active', Number(b.getAttribute('data-screen')) === idx);
  });
  const topLbl = document.getElementById('topScreenLabel');
  if (topLbl) topLbl.textContent = `· ${SCREEN_NAMES[idx] || 'Home'}`;
  closeSheet('screens');
  await sendKey(keyName);
  setTimeout(() => void refreshStatus(), 220);
}

async function switchSubTab(idx: number): Promise<void> {
  document.getElementById('subTabTvBtn')?.classList.toggle('active', idx === 0);
  document.getElementById('subTabMovieBtn')?.classList.toggle('active', idx === 1);
  await sendKey(idx === 0 ? 'subtab_tv' : 'subtab_movie');
  showToast(idx === 0 ? 'Home: TV Shows' : 'Home: Movies');
  setTimeout(() => void refreshStatus(), 220);
}

async function sendKey(key: RemoteKeyName): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.vibrate) {
    navigator.vibrate(key === 'hold_ok' ? [18, 30, 18] : 10);
  }
  if (activeRealtimeBridge) {
    activeRealtimeBridge.sendKey(key);
    return;
  }
  const ok = await remoteService.sendKey(key);
  if (!ok && !isPaired) return;
  if (key === 'users' || key === 'sideshelf' || key === 'theme' || key === 'ok' || key === 'hold_ok' || key === 'trakt' || key === 'back') {
    scheduleLiveBurstPolls();
    setTimeout(() => void refreshStatus(), 320);
  }
}

async function writeToPhoneClipboard(text: string): Promise<boolean> {
  if (!text) return false;
  if (typeof navigator !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Fall back to execCommand for non-HTTPS LAN http://<tv-ip>:8484 origins
    }
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', 'true');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    ta.style.pointerEvents = 'none';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

async function copyTvInputToPhone(ev?: Event): Promise<void> {
  if (ev) ev.stopPropagation();
  if (activeFocusedInput?.active && activeFocusedInput.isPassword) {
    showToast('Password fields cannot be copied');
    return;
  }
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  const sheetOpen = Boolean(document.getElementById('sheet-search')?.classList.contains('visible'));
  const textToCopy =
    (sheetOpen && input && input.value) ||
    (activeFocusedInput?.active ? activeFocusedInput.text : '') ||
    (input ? input.value : '');
  if (!textToCopy) {
    showToast('Nothing to copy yet');
    return;
  }
  const ok = await writeToPhoneClipboard(textToCopy);
  const src = activeFocusedInput?.appLabel || 'TV';
  showToast(ok ? `Copied from ${src}` : 'Select text in box to copy');
}

async function submitText(submit: boolean): Promise<void> {
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  const rawValue = input ? input.value : '';

  if (activeFocusedInput?.active) {
    const ok = await remoteService.updateTvInputField(rawValue, submit);
    if (ok) {
      isSearchInputDirty = false;
      const appName = (activeFocusedInput.appLabel || '').trim() || 'TV';
      if (!activeFocusedInput.isPassword) {
        activeFocusedInput = { ...activeFocusedInput, text: rawValue };
        lastSyncedTvInputSig = buildTvInputSyncSignature(activeFocusedInput);
        renderFocusedInputState(activeFocusedInput);
      }
      showToast(submit ? `Sent to ${appName}` : `Updated ${appName}`);
      if (submit) {
        closeSheet('search');
      }
      setTimeout(() => void refreshStatus(), 260);
    }
    return;
  }

  const text = rawValue.trim();
  if (!text) {
    if (submit) await switchTvScreen(0, 'search');
    return;
  }
  const ok = await remoteService.sendText(text, submit);
  if (ok) {
    isSearchInputDirty = false;
    showToast(submit ? `Unified Search on TV: ${text}` : 'Copied to TV clipboard');
    if (submit && input) {
      input.value = '';
      updateCopyTvInputButtonVisibility();
      setTimeout(() => void refreshStatus(), 300);
    }
  }
}

async function askGeminiAi(): Promise<void> {
  const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  const box = document.getElementById('aiResultBox');
  const query =
    (input && input.value.trim()) ||
    'Suggest a great high-rated sci-fi thriller series or movie to watch tonight';
  showToast('Asking Gemini AI...');
  if (box) {
    box.style.display = 'block';
    box.textContent = 'Gemini AI is thinking...';
  }
  try {
    const res = await remoteService.askGeminiAi(query, false);
    if (box) {
      const top = res.topItem;
      const summary = res.summary || (res.titles && res.titles.join(', ')) || 'Recommendation ready';
      if (top && top.deepLink) {
        box.innerHTML = `
          <div style="display:flex; align-items:center; justify-content:space-between; gap:10px;">
            <div style="min-width:0;">
              <div style="font-weight:700; color:var(--ink);">${escapeHtml(top.name)}</div>
              <div style="font-size:11.5px; color:var(--ink-72); margin-top:2px;">${escapeHtml(summary)}</div>
            </div>
            <button class="btn pill-focus btn-chip" style="flex-shrink:0;" onclick="quickCastUrl('${escapeAttr(top.deepLink)}', '${escapeAttr(top.name)}')">Open on TV</button>
          </div>
        `;
      } else {
        box.textContent = summary;
      }
    }
  } catch {
    if (box) box.textContent = 'Could not reach Gemini AI on TV';
  }
}

async function pasteFromPhoneClipboard(): Promise<void> {
  try {
    const clip = await navigator.clipboard.readText();
    const input = document.getElementById('remoteTextInput') as HTMLInputElement | null;
    if (clip && input) {
      input.value = clip;
      lastLocalEditAtMs = Date.now();
      isSearchInputDirty = true;
      updateCopyTvInputButtonVisibility();
      if (activeFocusedInput?.active || (currentStatus && currentStatus.activeScreen === 0)) {
        scheduleLiveTvMirror(true);
        showToast('Pasted straight to TV');
      } else {
        await remoteService.sendText(clip, false);
        showToast('Pasted to TV clipboard');
      }
    }
  } catch {
    showToast('Long-press the text box to paste');
  }
}

async function pasteToCastUrl(): Promise<void> {
  try {
    const clip = await navigator.clipboard.readText();
    const input = document.getElementById('castUrlInput') as HTMLInputElement | null;
    if (clip && input) {
      input.value = clip;
      showToast('Pasted link from clipboard');
    }
  } catch {
    showToast('Long-press the URL field to paste');
  }
}

function startVoiceInput(): void {
  const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
  const btn = document.getElementById('voiceBtn');
  if (!SpeechRec) {
    const manual = prompt(
      'Speak or type a voice command (e.g. "Movie time", "Turn on water boiler", "Severance"):'
    );
    if (manual && manual.trim()) {
      void remoteService.sendVoiceCommand(manual.trim());
      showToast(`Voice: "${manual.trim()}"`);
    }
    return;
  }
  const rec = new SpeechRec();
  rec.lang = 'en-US';
  rec.interimResults = false;
  btn?.classList.add('listening');
  showToast('Listening...');
  rec.onresult = async (e) => {
    const transcript = e.results[0][0].transcript;
    btn?.classList.remove('listening');
    showToast(`"${transcript}"`);
    await remoteService.sendVoiceCommand(transcript);
    setTimeout(() => void refreshStatus(), 350);
  };
  rec.onerror = () => btn?.classList.remove('listening');
  rec.onend = () => btn?.classList.remove('listening');
  rec.start();
}

/* ==========================================================================
   GAMING BROWSER & BIOMETRIC FINGERPRINT UNLOCK
   ========================================================================== */
async function loadGamingZone(forceRefresh: boolean): Promise<void> {
  if (!isPaired) return;
  try {
    const st = forceRefresh
      ? await remoteService.syncGamingLibrary()
      : await remoteService.getGamingStatus();
    renderGamingZone(st);
  } catch {
    // Handled via auth listener or offline state
  }
}

async function syncGamingWithPc(): Promise<void> {
  showToast('Syncing library with Laptop Gaming Agent...');
  await loadGamingZone(true);
}

async function wakeGamingLaptop(): Promise<void> {
  showToast('Sending Wake-on-LAN to Gaming PC...');
  try {
    const st = await remoteService.wakeGamingPc();
    renderGamingZone(st);
  } catch {
    showToast('Failed to send Wake-on-LAN');
  }
}

function toggleShowHiddenGames(): void {
  showHiddenGames = !showHiddenGames;
  lastGamingSig = ''; // Force re-render
  const btn = document.getElementById('toggleHiddenGamesBtn');
  const span = btn?.querySelector('span');
  if (span) span.textContent = showHiddenGames ? 'Hide Hidden' : 'Show Hidden';
  void loadGamingZone(false);
}

async function unlockGamingLaptop(): Promise<void> {
  const pinEl = document.getElementById('gamingPinInput') as HTMLInputElement | null;
  const pin = pinEl ? pinEl.value.trim() : '';
  if (!pin) {
    if (nativeBridge && nativeBridge.isBiometricAvailable() && nativeBridge.hasSavedPcUnlockPin()) {
      unlockPcWithFingerprint();
      return;
    }
    showToast('Enter your Windows Hello PIN first');
    return;
  }
  showToast('Unlocking Windows Laptop...');
  try {
    const st = await remoteService.unlockLaptop(pin);
    if (pinEl) pinEl.value = '';
    renderGamingZone(st);
    showToast('Windows PC unlocked');
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Failed to unlock PC');
  }
}

function unlockPcWithFingerprint(): void {
  if (!nativeBridge || !nativeBridge.isBiometricAvailable()) {
    showToast('Fingerprint unlock is available in the Native Android Remote App');
    return;
  }
  if (!nativeBridge.hasSavedPcUnlockPin()) {
    showToast('Type your Windows PIN once and tap Save PIN to Fingerprint');
    return;
  }
  nativeBridge.authenticatePcUnlock();
}

function savePinToFingerprint(): void {
  if (!nativeBridge || !nativeBridge.isBiometricAvailable()) {
    showToast('Fingerprint hardware unavailable');
    return;
  }
  const pinEl = document.getElementById('gamingPinInput') as HTMLInputElement | null;
  const pin = pinEl ? pinEl.value.trim() : '';
  if (!pin) {
    showToast('Enter your Windows PIN in the box first, then tap Save PIN to Fingerprint');
    return;
  }
  nativeBridge.enrollPcUnlockPin(pin);
}

function clearSavedFingerprintPin(): void {
  if (!nativeBridge) return;
  nativeBridge.clearSavedPcUnlockPin();
  refreshBiometricUiState();
  showToast('Cleared saved fingerprint PC PIN');
}

async function launchGamingItem(gameId: string, title: string): Promise<void> {
  const pinEl = document.getElementById('gamingPinInput') as HTMLInputElement | null;
  const pin = pinEl && currentRole === 'ADMIN' ? pinEl.value.trim() : '';
  showToast(`Launching ${title} on TV...`);
  try {
    const st = await remoteService.launchGame(gameId, pin);
    if (pinEl) pinEl.value = '';
    renderGamingZone(st);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Could not launch game');
  }
}

async function setGamingItemHidden(gameId: string, hidden: boolean, ev?: Event): Promise<void> {
  if (ev) ev.stopPropagation();
  try {
    const st = await remoteService.setGameHidden(gameId, hidden);
    lastGamingSig = '';
    renderGamingZone(st);
  } catch {
    showToast('Could not update game visibility');
  }
}

function renderGameCardHtml(g: GamingGameItem, compact: boolean): string {
  const art = g.imageUrl
    ? `<img src="${escapeAttr(g.imageUrl)}" alt="${escapeAttr(g.title)}" loading="lazy" onerror="this.style.display='none'" />`
    : '';
  const subLabel = formatGameSubtitle(g);
  const hideBtn = !compact
    ? `<button class="game-hide-btn" onclick="setGamingItemHidden('${escapeAttr(g.id)}', ${!g.hidden}, event)">${g.hidden ? 'Unhide' : 'Hide'}</button>`
    : '';
  return `
    <div class="game-item ${compact ? 'compact' : ''}" onclick="launchGamingItem('${escapeAttr(g.id)}', '${escapeAttr(g.title)}')">
      <div class="game-art-16x9">
        <svg class="icon icon-tile" viewBox="0 0 24 24"><line x1="6" y1="12" x2="10" y2="12"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="15" y1="13" x2="15.01" y2="13"/><line x1="18" y1="11" x2="18.01" y2="11"/><rect x="2" y="6" width="20" height="12" rx="4"/></svg>
        ${art}
        ${hideBtn}
      </div>
      <div class="game-title">${escapeHtml(g.title)}</div>
      <div class="game-sub">${escapeHtml(subLabel)}</div>
    </div>
  `;
}

function renderGamingZone(gz: GamingStatus | null): void {
  if (!gz) return;
  const sig = JSON.stringify({ gz, showHiddenGames });
  if (sig === lastGamingSig) return;
  lastGamingSig = sig;

  const boltSvg =
    '<svg class="icon icon-sm" style="width:13px;height:13px;margin-left:2px;flex-shrink:0;" viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>';
  const pillsRow = document.getElementById('gamingStatusPillsRow');
  const activeCtrls = Array.isArray(gz.controllers) ? gz.controllers : [];
  if (pillsRow) {
    let ctrlHtml = '';
    if (activeCtrls.length > 0) {
      ctrlHtml = activeCtrls
        .map((c) => {
          const name = escapeHtml(c.name || 'Gamepad');
          const pct =
            typeof c.batteryPercent === 'number' && c.batteryPercent >= 0
              ? ` · ${c.batteryPercent}%`
              : c.charging
                ? ' · Connected to Power'
                : '';
          const swatch = escapeAttr(c.color || '#64748b');
          return `<div class="status-pill connected"><span style="width:12px;height:12px;border-radius:50%;background:${swatch};border:1.5px solid rgba(255,255,255,0.85);display:inline-block;flex-shrink:0;"></span><span>${name}${pct}</span>${c.charging ? boltSvg : ''}</div>`;
        })
        .join('');
    } else {
      const summary = escapeHtml(gz.controllerSummary || '0 Controllers Connected');
      ctrlHtml = `<div id="controllerStatusPill" class="status-pill"><svg class="icon icon-sm" viewBox="0 0 24 24"><line x1="6" y1="12" x2="10" y2="12"/><line x1="8" y1="10" x2="8" y2="14"/><line x1="15" y1="13" x2="15.01" y2="13"/><line x1="18" y1="11" x2="18.01" y2="11"/><rect x="2" y="6" width="20" height="12" rx="4"/></svg><span id="controllerStatusText">${summary}</span></div>`;
    }
    const mode = gz.hdmiConnected
      ? 'PC Online · Cable'
      : gz.awake
        ? 'PC Online · Wireless'
        : 'PC Offline';
    const lockTxt = gz.awake && gz.locked ? ' · Locked' : '';
    const pcBatt =
      gz.awake && typeof gz.batteryPercent === 'number' && gz.batteryPercent >= 0
        ? ` · ${gz.batteryPercent}%`
        : '';
    const pcBolt =
      gz.awake && gz.batteryCharging && typeof gz.batteryPercent === 'number' && gz.batteryPercent >= 0
        ? boltSvg
        : '';
    const pcCls = gz.awake ? 'status-pill online connected' : 'status-pill';
    pillsRow.innerHTML =
      ctrlHtml +
      `<div id="pcStatusPill" class="${pcCls}"><svg class="icon icon-sm" viewBox="0 0 24 24"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg><span id="gamingStatusBadge">${escapeHtml(mode + lockTxt + pcBatt)}</span>${pcBolt}</div>`;
  }

  const contSec = document.getElementById('continuePlayingSection');
  const contList = document.getElementById('continuePlayingList');
  if (contSec && contList) {
    const played = Array.isArray(gz.continuePlaying) ? gz.continuePlaying : [];
    if (played.length > 0) {
      contSec.style.display = 'block';
      contList.innerHTML = played.map((g) => renderGameCardHtml(g, true)).join('');
    } else {
      contSec.style.display = 'none';
    }
  }

  const list = document.getElementById('gamingGamesList');
  if (list && Array.isArray(gz.games)) {
    const gamesOnly = gz.games.filter(
      (g) => g.category !== 'system' && (showHiddenGames || !g.hidden)
    );
    list.innerHTML = gamesOnly.map((g) => renderGameCardHtml(g, false)).join('');
  }
}

/* ==========================================================================
   SMART-HOME RICH CONTROLS & ROUTINES
   ========================================================================== */
async function runSmartRoutine(routineId: SmartHomeRoutineId): Promise<void> {
  showToast('Running Smart-Home action...');
  try {
    const updated = await remoteService.executeSmartHomeRoutine(routineId);
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Smart-Home action failed');
  }
}

async function toggleDevice(deviceId: string): Promise<void> {
  selectedSmartDeviceId = deviceId;
  try {
    const updated = await remoteService.toggleSmartHomeDevice(deviceId);
    lastSmartHomeSig = '';
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Device action failed');
  }
}

function selectSmartDevice(deviceId: string, ev?: Event): void {
  if (ev) ev.stopPropagation();
  selectedSmartDeviceId = deviceId;
  lastSmartHomeSig = '';
  if (currentStatus && Array.isArray(currentStatus.smartHome)) {
    renderSmartHomeSection(currentStatus.smartHome);
  }
  openSheet('device');
}

function smartHomeIconSvg(kind: SmartHomeIconKind): string {
  if (kind === 'ac') {
    return '<svg class="icon icon-tile" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="8" rx="2"/><line x1="6" y1="9" x2="18" y2="9"/><path d="M8 16c-.8 1-.8 2 0 3"/><path d="M12 16c-.8 1-.8 2 0 3"/><path d="M16 16c-.8 1-.8 2 0 3"/></svg>';
  }
  if (kind === 'lightbulb') {
    return '<svg class="icon icon-tile" viewBox="0 0 24 24"><path d="M9 18h6"/><path d="M10 22h4"/><path d="M15.09 14c.18-.98.65-1.74 1.41-2.5A4.65 4.65 0 0 0 18 8 6 6 0 0 0 6 8c0 1 .23 2.23 1.5 3.5A4.61 4.61 0 0 1 8.91 14"/></svg>';
  }
  if (kind === 'shower') {
    return '<svg class="icon icon-tile" viewBox="0 0 24 24"><path d="M4 4h4a5 5 0 0 1 5 5v1H7a3 3 0 0 1 3-3"/><path d="M6 10h12"/><line x1="8" y1="14" x2="7.5" y2="16.5"/><line x1="12" y1="14" x2="12" y2="16.5"/><line x1="16" y1="14" x2="16.5" y2="16.5"/><line x1="10" y1="18.5" x2="9.5" y2="20.5"/><line x1="14" y1="18.5" x2="14.5" y2="20.5"/></svg>';
  }
  return '<svg class="icon icon-tile" viewBox="0 0 24 24"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg>';
}

function renderSmartDeviceTile(d: SmartHomeDevice, isSelected: boolean): string {
  const isOn = Boolean(d.isOn || d.on);
  const statusTxt = d.statusText || d.status || (isOn ? 'On' : 'Off');
  const iconKind = resolveSmartHomeIconKind(d);
  return `
    <div class="sh-tile-item ${isOn ? 'is-on' : ''} ${isSelected ? 'is-selected' : ''}" onclick="toggleDevice('${escapeAttr(d.id)}')">
      <div class="sh-tile-face">
        ${smartHomeIconSvg(iconKind)}
        <button class="sh-tile-tune" onclick="selectSmartDevice('${escapeAttr(d.id)}', event)" title="Adjust ${escapeAttr(d.name)}" aria-label="Adjust ${escapeAttr(d.name)}">
          <svg class="icon icon-sm" style="width:13px;height:13px;" viewBox="0 0 24 24"><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg>
        </button>
      </div>
      <div class="sh-tile-meta">
        <div class="sh-tile-name">${escapeHtml(d.name)}</div>
        <div class="sh-tile-status">${escapeHtml(statusTxt)}</div>
      </div>
    </div>
  `;
}

function renderSelectedSmartDeviceControls(d: SmartHomeDevice): string {
  const isOn = Boolean(d.isOn || d.on);
  const statusTxt = d.statusText || d.status || (isOn ? 'On' : 'Off');
  const temp = d.targetTempCelsius || d.targetTemp || 23;
  const mode = (d.acMode || 'cool').toLowerCase();
  const fan = (d.fanLevel || 'auto').toLowerCase();
  const dim = d.brightness || 100;
  const ct = d.colorTempK || 2700;
  const timer = d.timerMinutes || 15;
  const proto = (d.protocol || '').toUpperCase();

  let controlsRows = '';
  if (proto === 'SENSIBO_AC' || proto === 'TUYA_AC') {
    controlsRows = `
      <div class="tv-row">
        <div class="device-controls-row">
          <span class="controls-label">Temp</span>
          <button class="btn btn-chip" onclick="adjustTemp('${escapeAttr(d.id)}', -1, event)">-1°C</button>
          <span style="font-size:13.5px; font-weight:700; min-width:40px; text-align:center;">${temp}°C</span>
          <button class="btn btn-chip" onclick="adjustTemp('${escapeAttr(d.id)}', 1, event)">+1°C</button>
        </div>
        <div class="device-controls-row">
          <span class="controls-label">Mode</span>
          ${['cool', 'heat', 'dry', 'fan']
            .map(
              (m) => `
            <button class="btn btn-chip ${mode === m ? 'pill-focus' : ''}" onclick="setAcParams('${escapeAttr(d.id)}', '${m}', ${temp}, '${fan}', event)">${m.charAt(0).toUpperCase() + m.slice(1)}</button>
          `
            )
            .join('')}
        </div>
        <div class="device-controls-row">
          <span class="controls-label">Fan</span>
          ${['auto', 'low', 'medium', 'high']
            .map(
              (f) => `
            <button class="btn btn-chip ${fan === f ? 'pill-focus' : ''}" onclick="setAcParams('${escapeAttr(d.id)}', '${mode}', ${temp}, '${f}', event)">${f.charAt(0).toUpperCase() + f.slice(1)}</button>
          `
            )
            .join('')}
        </div>
      </div>
    `;
  } else if (proto === 'WIZ_LIGHT') {
    controlsRows = `
      <div class="tv-row">
        <div class="device-controls-row">
          <span class="controls-label">Dim</span>
          ${[10, 20, 50, 75, 100]
            .map(
              (pct) => `
            <button class="btn btn-chip ${isOn && dim === pct ? 'pill-focus' : ''}" onclick="setLightParams('${escapeAttr(d.id)}', ${pct}, ${ct}, event)">${pct}%</button>
          `
            )
            .join('')}
        </div>
        <div class="device-controls-row">
          <span class="controls-label">Tone</span>
          <button class="btn btn-chip ${isOn && ct <= 3000 ? 'pill-focus' : ''}" onclick="setLightParams('${escapeAttr(d.id)}', ${dim}, 2700, event)">Warm</button>
          <button class="btn btn-chip ${isOn && ct > 3000 && ct < 5000 ? 'pill-focus' : ''}" onclick="setLightParams('${escapeAttr(d.id)}', ${dim}, 4200, event)">Daylight</button>
          <button class="btn btn-chip ${isOn && ct >= 5000 ? 'pill-focus' : ''}" onclick="setLightParams('${escapeAttr(d.id)}', ${dim}, 6000, event)">Cool</button>
        </div>
      </div>
    `;
  } else if (proto === 'SWITCHER_BOILER') {
    controlsRows = `
      <div class="tv-row">
        <div class="device-controls-row">
          <span class="controls-label">Timer</span>
          ${[15, 30, 45, 60]
            .map(
              (m) => `
            <button class="btn btn-chip ${isOn && timer === m ? 'pill-focus' : ''}" onclick="setBoilerTimer('${escapeAttr(d.id)}', ${m}, event)">${m}m</button>
          `
            )
            .join('')}
        </div>
      </div>
    `;
  }

  return `
    <div class="tv-row is-active">
      <div class="tv-row-head">
        <div class="tv-row-main">
          <span class="dot ${isOn ? 'dot-emerald' : 'dot-amber'}"></span>
          <div style="min-width:0;">
            <div class="tv-row-title">${escapeHtml(d.name)}</div>
            <div class="tv-row-sub">${escapeHtml(statusTxt)}</div>
          </div>
        </div>
        <button class="btn btn-chip ${isOn ? 'btn-blue' : 'pill-focus'}" onclick="toggleDevice('${escapeAttr(d.id)}')" style="flex-shrink:0;">
          ${isOn ? 'Turn Off' : 'Turn On'}
        </button>
      </div>
    </div>
    ${controlsRows}
  `;
}

function renderSmartHomeSection(devices: SmartHomeDevice[]): void {
  const shGrid = document.getElementById('smartHomeGrid');
  const controlsPanel = document.getElementById('smartDeviceControlsPanel');
  if (!shGrid) return;

  if (!devices || devices.length === 0) {
    shGrid.innerHTML = '';
    if (controlsPanel) {
      controlsPanel.style.display = 'flex';
      controlsPanel.innerHTML =
        '<div class="tv-row"><div class="tv-row-sub">No Smart-Home devices connected yet. Tap Scan WiZ or configure in TV Settings.</div></div>';
    }
    return;
  }

  const activeDev =
    devices.find((d) => d.id === selectedSmartDeviceId) || devices[0];
  selectedSmartDeviceId = activeDev.id;

  shGrid.innerHTML = devices
    .map((d) => renderSmartDeviceTile(d, false))
    .join('');

  const sheetTitle = document.getElementById('deviceSheetTitle');
  if (sheetTitle) sheetTitle.textContent = activeDev.name;

  if (controlsPanel) {
    controlsPanel.style.display = 'flex';
    controlsPanel.innerHTML = renderSelectedSmartDeviceControls(activeDev);
  }
}

async function adjustTemp(deviceId: string, tempDelta: number, ev?: Event): Promise<void> {
  if (ev) ev.stopPropagation();
  try {
    const updated = await remoteService.adjustAcTemperature(deviceId, tempDelta);
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'AC update failed');
  }
}

async function setAcParams(
  deviceId: string,
  acMode: string,
  targetTemp: number,
  fanLevel: string,
  ev?: Event
): Promise<void> {
  if (ev) ev.stopPropagation();
  try {
    const updated = await remoteService.setAcParameters(deviceId, true, acMode, targetTemp, fanLevel);
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'AC update failed');
  }
}

async function setLightParams(
  deviceId: string,
  brightness: number,
  colorTempK: number,
  ev?: Event
): Promise<void> {
  if (ev) ev.stopPropagation();
  try {
    const updated = await remoteService.setLightParameters(deviceId, true, brightness, colorTempK);
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Light update failed');
  }
}

async function setBoilerTimer(deviceId: string, timerMinutes: number, ev?: Event): Promise<void> {
  if (ev) ev.stopPropagation();
  showToast(`Water Boiler: On (${timerMinutes}m Timer)`);
  try {
    const updated = await remoteService.setBoilerTimer(deviceId, timerMinutes);
    renderStatus(updated);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Boiler update failed');
  }
}

/* ==========================================================================
   CAST, APPS, PROFILES, PAIRED DEVICES ADMIN & SYSTEM
   ========================================================================== */
async function triggerReelsAction(
  action: 'open_feed' | 'open_watch_later' | 'open_setup' | 'done_watching' | 'watch_later' | 'next' | 'prev' | 'play_pause'
): Promise<void> {
  const ok = await remoteService.reelsAction(action);
  if (ok) {
    const labels: Record<string, string> = {
      open_feed: 'Opening Reels Feed on TV...',
      open_watch_later: 'Opening Watch Later Folder on TV...',
      open_setup: 'Opening Reels Accounts Setup on TV...',
      done_watching: 'Done Watching sent to TV...',
      watch_later: 'Toggled Watch Later on TV',
      next: 'Next video',
      prev: 'Previous video',
      play_pause: 'Play / Pause',
    };
    showToast(labels[action] || 'Sent to TV');
  }
}

async function castToTv(saveReel: boolean): Promise<void> {
  const urlInput = document.getElementById('castUrlInput') as HTMLInputElement | null;
  const titleInput = document.getElementById('castTitleInput') as HTMLInputElement | null;
  const url = urlInput ? urlInput.value.trim() : '';
  if (!url) {
    showToast('Enter an http://, https://, or stremio:// URL first');
    return;
  }
  const title = (titleInput && titleInput.value.trim()) || 'Shared Reel';
  const sender = (currentStatus && currentStatus.activeUser && currentStatus.activeUser.displayName) || 'Family';
  const ok = await remoteService.castMedia(url, title, sender, saveReel);
  if (ok) {
    showToast(`Casting on TV: ${title}`);
    if (urlInput) urlInput.value = '';
    if (titleInput) titleInput.value = '';
    setTimeout(() => void refreshStatus(), 500);
  } else {
    showToast('Only http://, https://, or stremio:// links are allowed');
  }
}

async function quickCastUrl(url: string, title: string): Promise<void> {
  const ok = await remoteService.castMedia(url, title, 'Remote', false);
  if (ok) showToast(`Opening ${title} on TV...`);
}

async function launchApp(pkg: string): Promise<void> {
  const ok = await remoteService.launchApp(pkg);
  if (ok) showToast('Launching on TV...');
}

async function setLauncherMode(mode: 'soartv' | 'original' | 'settings'): Promise<void> {
  const ok = await remoteService.setLauncherMode(mode);
  if (ok) {
    showToast(
      mode === 'original' ? 'Restoring Native TV Launcher...' : 'Opening SoarTV Settings on TV...'
    );
  }
}

async function triggerSelfUpdate(): Promise<void> {
  const tokenInput = document.getElementById('githubTokenInput') as HTMLInputElement | null;
  const githubToken = tokenInput ? tokenInput.value.trim() : '';
  const ok = await remoteService.triggerSelfUpdate(githubToken, '');
  if (tokenInput) tokenInput.value = '';
  if (ok) showToast('Checking GitHub releases for updates...');
}

async function switchUser(username: string): Promise<void> {
  try {
    const updated = await remoteService.switchUserProfile(username);
    renderStatus(updated);
    showToast(`Switched user to ${username}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Could not switch profile');
  }
}

async function createUserProfile(): Promise<void> {
  const nameInput = document.getElementById('newProfileName') as HTMLInputElement | null;
  const traktInput = document.getElementById('newProfileTrakt') as HTMLInputElement | null;
  const displayName = nameInput ? nameInput.value.trim() : '';
  if (!displayName) return;
  try {
    const updated = await remoteService.addUserProfile(
      displayName,
      traktInput ? traktInput.value.trim() : ''
    );
    renderStatus(updated);
    if (nameInput) nameInput.value = '';
    if (traktInput) traktInput.value = '';
    showToast(`Saved profile: ${displayName}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Could not save profile');
  }
}

/* ==========================================================================
   ADMIN PAIRED DEVICES & GUEST MANAGEMENT
   ========================================================================== */
async function refreshPairedDevices(): Promise<void> {
  if (currentRole !== 'ADMIN' || !isPaired) return;
  try {
    const devices = await remoteService.listPairedDevices();
    renderPairedDevicesList(devices);
  } catch {
    // Handled via auth listener
  }
}

function renderPairedDevicesList(devices: PairedDeviceInfo[]): void {
  const container = document.getElementById('pairedDevicesList');
  if (!container) return;
  const sig = JSON.stringify(devices);
  if (sig === lastAdminDevicesSig) return;
  lastAdminDevicesSig = sig;

  if (!devices || devices.length === 0) {
    container.innerHTML =
      '<div class="glass reel-item"><span class="tv-sub">No paired devices recorded yet.</span></div>';
    return;
  }

  container.innerHTML = devices
    .map((d) => {
      const roleLabel =
        d.role === 'ADMIN'
          ? 'Admin'
          : d.role === 'HOUSEHOLD'
            ? 'Household'
            : d.expired
              ? 'Guest · Expired'
              : `Guest · ${d.daysRemaining || 30}d left`;
      const youTag = d.isCurrentDevice ? ' · This Phone' : '';
      const emailSub = d.googleEmail ? ` · ${escapeHtml(d.googleEmail)}` : '';
      return `
        <div class="tv-row ${d.isCurrentDevice ? 'is-active' : ''}">
          <div class="device-admin-row">
            <div style="min-width:0; flex:1;">
              <div class="tv-row-title">
                <span class="dot ${d.expired ? 'dot-amber' : 'dot-emerald'}"></span>
                <span>${escapeHtml(d.deviceName)}</span>
                <span class="status-pill ${d.role === 'ADMIN' ? 'connected' : ''}" style="padding:2px 8px; font-size:10px;">${escapeHtml(roleLabel)}</span>
              </div>
              <div class="tv-row-sub">${escapeHtml(d.deviceId)}${youTag}${emailSub}</div>
            </div>
            <div style="display:flex; align-items:center; gap:6px; flex-shrink:0; flex-wrap:wrap; justify-content:flex-end;">
              <button class="btn btn-chip" onclick="renamePairedDevicePrompt('${escapeAttr(d.deviceId)}', '${escapeAttr(d.deviceName)}')">
                Rename
              </button>
              <select aria-label="Device Role" class="role-select" onchange="changePairedDeviceRole('${escapeAttr(d.deviceId)}', this.value)">
                <option value="GUEST" ${d.role === 'GUEST' ? 'selected' : ''}>Guest (30d)</option>
                <option value="HOUSEHOLD" ${d.role === 'HOUSEHOLD' ? 'selected' : ''}>Household</option>
                <option value="ADMIN" ${d.role === 'ADMIN' ? 'selected' : ''}>Admin</option>
              </select>
              <button class="btn btn-chip btn-danger" onclick="revokePairedDevice('${escapeAttr(d.deviceId)}')">
                Revoke
              </button>
            </div>
          </div>
        </div>
      `;
    })
    .join('');
}

async function renamePairedDevicePrompt(deviceId: string, currentName: string): Promise<void> {
  const next = typeof window !== 'undefined' ? window.prompt('Enter phone nickname:', currentName || '') : null;
  if (next === null) return;
  const clean = next.trim();
  if (!clean) {
    showToast('Nickname cannot be empty');
    return;
  }
  try {
    const updated = await remoteService.renamePairedDevice(deviceId, clean);
    lastAdminDevicesSig = '';
    renderPairedDevicesList(updated);
    showToast(`Renamed to ${clean}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Failed to rename device');
  }
}

async function revokePairedDevice(deviceId: string): Promise<void> {
  try {
    const updated = await remoteService.revokeDevice(deviceId);
    lastAdminDevicesSig = '';
    renderPairedDevicesList(updated);
    showToast('Revoked remote device access');
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Failed to revoke device');
  }
}

async function changePairedDeviceRole(deviceId: string, role: string): Promise<void> {
  const validRole: RemoteRole =
    role === 'ADMIN' ? 'ADMIN' : role === 'HOUSEHOLD' ? 'HOUSEHOLD' : 'GUEST';
  try {
    const updated = await remoteService.setDeviceRole(deviceId, validRole);
    lastAdminDevicesSig = '';
    renderPairedDevicesList(updated);
    showToast(`Updated role to ${validRole}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Failed to update role');
  }
}

let latestAdminInvite: AdminInviteResponse | null = null;

function sanitizeInlineQrSvg(rawSvg?: string): string {
  if (!rawSvg) return '';
  const trimmed = rawSvg.trim();
  if (!trimmed.startsWith('<svg ') || !trimmed.endsWith('</svg>')) return '';
  if (/script|onload|onerror|javascript:/i.test(trimmed)) return '';
  return trimmed;
}

async function quickInviteAdmin(): Promise<void> {
  const details = document.getElementById('adminDevicesDisclosure') as HTMLDetailsElement | null;
  if (details) {
    details.open = true;
  }
  await createAdminInvite('ADMIN');
  document.getElementById('adminInviteResultBox')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function createAdminInvite(role: RemoteRole): Promise<void> {
  try {
    const inv: AdminInviteResponse = await remoteService.createInvite(role);
    latestAdminInvite = inv;
    const box = document.getElementById('adminInviteResultBox');
    const roleLabel =
      inv.role === 'ADMIN' ? 'Admin' : inv.role === 'HOUSEHOLD' ? 'Household' : 'Guest (30d)';
    const digits = (inv.code || '').replace(/\D/g, '');
    const formattedCode = digits.length === 6 ? `${digits.slice(0, 3)} ${digits.slice(3)}` : inv.code;
    const safeQrSvg = sanitizeInlineQrSvg(inv.qrSvg);
    if (box) {
      box.style.display = 'flex';
      box.innerHTML = `
        <div style="display:flex; flex-direction:column; gap:10px; width:100%;">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <div style="min-width:0; flex:1;">
              <div class="tv-row-title">${escapeHtml(roleLabel)} Invite · <span style="letter-spacing:0.12em;">${escapeHtml(formattedCode)}</span></div>
              <div class="tv-row-sub">QR shown on TV &amp; below for 5 min · Same Wi-Fi</div>
            </div>
          </div>
          ${safeQrSvg ? `<div class="invite-qr-card">${safeQrSvg}</div>` : ''}
          <div class="tv-row-sub" style="text-align:center;">
            Scan with iPhone or Android camera — pairs as <strong>${escapeHtml(roleLabel)}</strong> &amp; prompts to install the app.
          </div>
          <div class="input-row">
            <button class="btn pill-focus" style="flex:1;" onclick="shareInviteLink()">Share Invite</button>
            <button class="btn" style="flex:1;" onclick="copyInviteLink('${escapeAttr(inv.pairUrl)}')">Copy Link</button>
          </div>
          <div class="tv-row-sub" style="font-size:10.5px; text-align:center; opacity:0.84;">
            iPhone tip: Connect to home Wi-Fi &amp; allow Safari Local Network in iOS Settings, then Share &rarr; Add to Home Screen.
          </div>
        </div>
      `;
    }
    showToast(`${roleLabel} invite ready: ${formattedCode}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : 'Could not create invite');
  }
}

async function shareInviteLink(): Promise<void> {
  if (!latestAdminInvite) return;
  const tvName = currentStatus?.tvModel || currentAuthConfig?.tvName || 'SoarTV';
  const shareText = buildInviteShareText(
    latestAdminInvite.role,
    latestAdminInvite.code,
    latestAdminInvite.pairUrl,
    tvName
  );
  if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
    try {
      await navigator.share({
        title: `SoarTV ${latestAdminInvite.role} Remote Invite`,
        text: shareText,
        url: latestAdminInvite.pairUrl,
      });
      return;
    } catch {
      // User cancelled or WebView blocked share; fall back cleanly to clipboard copy
    }
  }
  const copied = await writeToPhoneClipboard(shareText);
  showToast(copied ? 'Invite message & link copied to clipboard' : latestAdminInvite.pairUrl);
}

async function copyInviteLink(url: string): Promise<void> {
  const ok = await writeToPhoneClipboard(url);
  showToast(ok ? 'Copied pairing & download link' : url);
}

/* ==========================================================================
   STATUS RENDERING WITH DIFFED DOM UPDATES (PERF-15)
   ========================================================================== */
function renderStatus(st: TvStatus | null): void {
  if (!st) return;
  currentStatus = st;
  document.getElementById('statusDot')?.classList.remove('offline');
  const modelEl = document.getElementById('tvModelText');
  if (modelEl) modelEl.textContent = st.tvModel ? `SoarTV · ${st.tvModel}` : 'SoarTV';

  if (st.authRole && st.authRole !== currentRole) {
    applyRoleVisibility(st.authRole);
  }

  if (typeof st.darkMode === 'boolean') {
    if (localThemeOverride === null) {
      applyTheme(st.darkMode);
    } else if (st.darkMode === localThemeOverride) {
      localThemeOverride = null;
    }
  }

  if (st.enabledTabs) {
    const vis = resolveTabVisibility(st.enabledTabs);

    const setElVisible = (selector: string, visible: boolean) => {
      document.querySelectorAll<HTMLElement>(selector).forEach((el) => {
        el.style.display = visible ? '' : 'none';
      });
    };

    setElVisible('.tv-screen-pill[data-screen="3"], .dock-tab[data-tab="gaming"]', vis.gaming);
    setElVisible('.tv-screen-pill[data-screen="4"], .dock-tab[data-tab="cast"]', vis.reels);
    setElVisible('.tv-screen-pill[data-screen="5"], .dock-tab[data-tab="smarthome"]', vis.smarthome);

    const activePaneId = document.querySelector('.tab-pane.active')?.id || 'tab-remote';
    const activeTabId = activePaneId.replace(/^tab-/, '');
    const fallbackTab = resolveFallbackDockTab(activeTabId, vis);
    if (fallbackTab !== activeTabId) {
      switchTab(fallbackTab);
    }
  }

  const scrIdx = typeof st.activeScreen === 'number' ? st.activeScreen : 1;
  const scrName = SCREEN_NAMES[scrIdx] || 'Home';
  const subName = st.subTab === 1 ? 'Movies' : 'TV Shows';
  const topLbl = document.getElementById('topScreenLabel');
  if (topLbl) {
    topLbl.textContent = `· ${scrName}`;
  }
  renderFocusedInputState(st.focusedInput);
  renderActiveTvModal(st.activeModal);
  const subEl = document.getElementById('tvSubText');
  if (subEl) {
    subEl.textContent =
      scrIdx === 1
        ? `Home · ${subName}${st.sideShelfOpen ? ' · Shelf Open' : ''}`
        : `On TV: ${scrName}`;
  }

  document.querySelectorAll('.tv-screen-pill').forEach((b) => {
    b.classList.toggle('active', Number(b.getAttribute('data-screen')) === scrIdx);
  });

  const subTv = document.getElementById('subTabTvBtn');
  const subMv = document.getElementById('subTabMovieBtn');
  if (subTv && subMv && typeof st.subTab === 'number') {
    subTv.classList.toggle('active', st.subTab === 0);
    subMv.classList.toggle('active', st.subTab === 1);
  }

  const shelfBtn = document.getElementById('sideShelfToggleBtn');
  if (shelfBtn) {
    shelfBtn.classList.toggle('pill-focus', Boolean(st.sideShelfOpen));
  }

  if (st.activeUser) {
    const userTxt = document.getElementById('activeUserText');
    if (userTxt) userTxt.textContent = st.activeUser.displayName;
  }

  if (st.controllerSummary) {
    const ctrlText = document.getElementById('controllerStatusText');
    const ctrlPill = document.getElementById('controllerStatusPill');
    if (ctrlText && ctrlPill) {
      ctrlText.textContent = st.controllerSummary;
      ctrlPill.classList.toggle(
        'connected',
        !st.controllerSummary.startsWith('No ') && !st.controllerSummary.startsWith('0 ')
      );
    }
  }

  const traktTitle = document.getElementById('traktStatusTitle');
  const traktSub = document.getElementById('traktStatusSub');
  const traktDot = document.getElementById('traktStatusDot');
  if (traktTitle && traktSub) {
    if (st.traktConnected) {
      traktTitle.textContent = `Trakt Connected: @${st.traktUser || 'trakt'}`;
      traktSub.textContent = 'Watch List, Ratings & Favorites synced';
      if (traktDot) traktDot.className = 'dot dot-emerald';
    } else {
      traktTitle.textContent = 'Connect Private Trakt Account';
      traktSub.textContent = 'Tap to open trakt.tv/activate code on TV';
      if (traktDot) traktDot.className = 'dot dot-amber';
    }
  }

  const favGrid = document.getElementById('favoriteAppsGrid');
  if (favGrid && Array.isArray(st.shelfApps) && st.shelfApps.length > 0) {
    const shelfSig = JSON.stringify(st.shelfApps);
    if (shelfSig !== lastShelfSig) {
      lastShelfSig = shelfSig;
      favGrid.innerHTML = st.shelfApps
        .map(
          (a) => `
        <button class="app-tile lg-sm" onclick="launchApp('${escapeAttr(a.pkg)}')">
          <span>${escapeHtml(a.label)}</span>
        </button>
      `
        )
        .join('');
    }
  }

  if (Array.isArray(st.smartHome)) {
    const shSig = JSON.stringify({ devices: st.smartHome, selected: selectedSmartDeviceId });
    if (shSig !== lastSmartHomeSig) {
      lastSmartHomeSig = shSig;
      renderSmartHomeSection(st.smartHome);
    }
  }

  const reelsList = document.getElementById('sharedReelsList');
  if (reelsList && Array.isArray(st.sharedReels)) {
    const reelsSig = JSON.stringify(st.sharedReels);
    if (reelsSig !== lastReelsSig) {
      lastReelsSig = reelsSig;
      if (st.sharedReels.length === 0) {
        reelsList.innerHTML =
          '<div class="glass reel-item"><span class="tv-sub">No Shared Reels yet — paste a link above.</span></div>';
      } else {
        reelsList.innerHTML = st.sharedReels
          .map(
            (r) => `
          <div class="lg-sm reel-item">
            <div style="overflow:hidden;">
              <div style="font-weight:700; font-size:13px;">${escapeHtml(r.title || 'Shared Reel')}</div>
              <div class="tv-row-sub">From ${escapeHtml(r.sender || 'Family')} · ${escapeHtml(r.platform || 'video')}</div>
            </div>
            <button class="btn pill-focus btn-chip" onclick="quickCastUrl('${escapeAttr(r.url)}', '${escapeAttr(r.title || 'Shared Reel')}')">Play</button>
          </div>
        `
          )
          .join('');
      }
    }
  }

  const usersList = document.getElementById('userProfilesList');
  if (usersList && Array.isArray(st.users)) {
    const usersSig = JSON.stringify({
      users: st.users,
      active: st.activeUser ? st.activeUser.username : '',
    });
    if (usersSig !== lastUsersSig) {
      lastUsersSig = usersSig;
      usersList.innerHTML = st.users
        .map((u) => {
          const active = st.activeUser && st.activeUser.username === u.username;
          return `
            <button class="btn ${active ? 'pill-focus' : ''}" onclick="switchUser('${escapeAttr(u.username)}')">
              <span>${escapeHtml(u.displayName)}</span>
              ${u.traktUsername ? `<span style="font-size:11px; opacity:0.7;">(@${escapeHtml(u.traktUsername)})</span>` : ''}
            </button>
          `;
        })
        .join('');
    }
  }
}

let consecutiveOfflinePolls = 0;
let lanRecoveryInFlight = false;

async function refreshStatus(): Promise<void> {
  if (activeRealtimeBridge) return;
  if (!isPaired || !remoteService.getBaseUrl()) return;
  try {
    const st = await remoteService.getStatus();
    consecutiveOfflinePolls = 0;
    renderStatus(st);
  } catch (err) {
    if (!(err instanceof RemoteUnauthorizedError)) {
      document.getElementById('statusDot')?.classList.add('offline');
      consecutiveOfflinePolls++;
      if (consecutiveOfflinePolls >= 2 && !lanRecoveryInFlight) {
        lanRecoveryInFlight = true;
        const activeKnown = knownHouseholdTvs.find((t) => t.deviceId === activeTvDeviceId) || null;
        void TvRemoteService.resolveReachableTv(
          activeKnown,
          remoteService.getBaseUrl(),
          getHouseholdDirectoryCreds()
        )
          .then((resolved) => {
            if (
              resolved &&
              resolved.mode === 'lan' &&
              resolved.baseUrl &&
              resolved.baseUrl !== remoteService.getBaseUrl()
            ) {
              remoteService.setBaseUrl(resolved.baseUrl);
              writeStorage(STORAGE_BASE_URL, resolved.baseUrl);
              syncApkDownloadLinks();
              consecutiveOfflinePolls = 0;
              showToast('Reconnected to SoarTV');
              void refreshStatus();
            }
          })
          .finally(() => {
            lanRecoveryInFlight = false;
          });
      }
    }
  }
}

/* ==========================================================================
   NATIVE ANDROID CALLBACKS & INTERACTION INITIALIZATION
   ========================================================================== */
function registerNativeCallbacksAndGlobals(): void {
  window.onSoarTvDiscovered = (discoveredUrl: string) => {
    const normalized = TvRemoteService.normalizeHostInput(discoveredUrl);
    if (!normalized) return;
    if (normalized !== remoteService.getBaseUrl() || !isPaired) {
      remoteService.setBaseUrl(normalized);
      writeStorage(STORAGE_BASE_URL, normalized);
      syncApkDownloadLinks();
      void bootstrapConnectionAndAuth();
    }
  };

  window.onSoarTvQrScanned = (rawUri: string) => {
    if (!rawUri) return;
    void handleScannedQrPayload(rawUri);
  };

  window.onSoarTvNativeQrDetected = async () => {
    if (qrVerifyInFlight) return;
    if (!activePairRequestId) {
      await requestTvPairPrompt(true);
    }
    if (!activePairRequestId || !remoteService.getBaseUrl()) {
      showToast('Show QR on TV first, then scan again');
      return;
    }
    qrVerifyInFlight = true;
    try {
      const verified = await remoteService.verifyPairing({
        requestId: activePairRequestId,
        clientId: getOrCreateClientId(),
        qrCameraDetected: true,
        deviceName: detectDeviceName(),
      });
      await completePairingSuccess(verified.token, verified.role);
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'QR expired — tap Show QR on TV and scan again');
      void requestTvPairPrompt(true);
    } finally {
      qrVerifyInFlight = false;
    }
  };

  window.onSoarTvNativeQrClosed = (reason: string) => {
    if (reason && reason.trim()) {
      showToast(reason.trim());
    }
  };

  window.onSoarTvGoogleAuthCallback = async (accessToken: string) => {
    if (!accessToken) return;
    showToast('Verifying Google Admin account with TV...');
    try {
      const res = await remoteService.exchangeGoogleToken(accessToken, detectDeviceName());
      await completePairingSuccess(res.token, res.role);
      if (Array.isArray(res.devices)) {
        lastAdminDevicesSig = '';
        renderPairedDevicesList(res.devices);
      }
    } catch (err) {
      if (err instanceof RemoteForbiddenError) {
        showToast('Google account is not in the TV owner whitelist');
      } else {
        showToast(err instanceof Error ? err.message : 'Google Admin verification failed');
      }
    }
  };

  window.onBiometricEnrollResult = (ok: boolean, errorMessage: string) => {
    refreshBiometricUiState();
    if (ok) {
      const pinEl = document.getElementById('gamingPinInput') as HTMLInputElement | null;
      if (pinEl) pinEl.value = '';
      showToast('Saved Windows PIN to Android Fingerprint KeyStore');
    } else if (errorMessage && errorMessage !== 'Cancelled') {
      showToast(errorMessage);
    }
  };

  window.onBiometricPcPinReady = async (pin: string) => {
    if (!pin) return;
    showToast('Fingerprint verified — unlocking Gaming PC...');
    try {
      const st = await remoteService.unlockLaptop(pin);
      renderGamingZone(st);
      showToast('Gaming PC unlocked wirelessly');
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Could not reach Gaming PC');
    }
  };

  window.onBiometricPcPinError = (errorMessage: string) => {
    refreshBiometricUiState();
    if (errorMessage && errorMessage !== 'Cancelled') {
      showToast(errorMessage);
    }
  };

  // Expose DOM event handlers on window for clean declarative HTML attributes
  Object.assign(window, {
    openQrPairScanner,
    startQrScanner,
    stopQrScanner,
    scanQrImageFile,
    closePairingOverlayManual,
    handlePairingBackdropClick,
    openPairingOverlayManual,
    rePairAsAdmin,
    requestTvPairPrompt,
    submitSixDigitPairCode,
    signInWithGoogleAdmin,
    toggleFullscreen,
    dismissApkBanner,
    toggleTheme,
    openSheet,
    closeSheet,
    closeAllSheets,
    handleSheetBackdropClick,
    openSearchSheet,
    setPadMode,
    switchTab,
    switchTvScreen,
    switchSubTab,
    selectHouseholdTv,
    refreshHouseholdTvDirectory,
    sendKey,
    submitText,
    clearTvInputField,
    copyTvInputToPhone,
    dismissMirroredTvModalLocally,
    handleTvModalBackdropClick,
    copyTvModalCode,
    openTvModalExternalLink,
    triggerTvModalAction,
    askGeminiAi,
    pasteFromPhoneClipboard,
    pasteToCastUrl,
    startVoiceInput,
    syncGamingWithPc,
    wakeGamingLaptop,
    toggleShowHiddenGames,
    unlockGamingLaptop,
    unlockPcWithFingerprint,
    savePinToFingerprint,
    clearSavedFingerprintPin,
    launchGamingItem,
    setGamingItemHidden,
    runSmartRoutine,
    toggleDevice,
    selectSmartDevice,
    adjustTemp,
    setAcParams,
    setLightParams,
    setBoilerTimer,
    triggerReelsAction,
    castToTv,
    quickCastUrl,
    launchApp,
    setLauncherMode,
    triggerSelfUpdate,
    switchUser,
    createUserProfile,
    refreshPairedDevices,
    renamePairedDevicePrompt,
    revokePairedDevice,
    changePairedDeviceRole,
    quickInviteAdmin,
    createAdminInvite,
    shareInviteLink,
    copyInviteLink,
    openInstallAppSheet,
  });
}

function initNativeAndBannerState(): void {
  const b = document.getElementById('apkInstallBanner');
  const fsBtn = document.getElementById('fullscreenBtn');
  const tab5Btn = document.getElementById('tab5ApkDownloadBtn');
  const bannerTitle = document.getElementById('installBannerTitle');
  const bannerSub = document.getElementById('installBannerSub');
  const apkLink = document.getElementById('apkDownloadLink');
  const iosBtn = document.getElementById('iosInstallGuideBtn');
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent || '' : '';
  const isStandalonePwa =
    (typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(display-mode: standalone)').matches) ||
    Boolean(
      typeof navigator !== 'undefined' &&
        (navigator as Navigator & { standalone?: boolean }).standalone
    );
  if (typeof document !== 'undefined') {
    document.documentElement.classList.toggle('ios-standalone', isIosStandaloneWebApp(ua, isStandalonePwa));
  }
  const platform = getInstallPlatform();

  if (platform === 'installed_app') {
    if (b) b.classList.add('hidden');
    if (fsBtn) fsBtn.style.display = 'none';
    if (IS_NATIVE_APK && tab5Btn) tab5Btn.style.display = 'none';
    return;
  }

  if (platform === 'ios_browser') {
    if (bannerTitle) bannerTitle.textContent = 'Add SoarTV to iPhone Home Screen';
    if (bannerSub) bannerSub.textContent = 'Full-screen app icon · Keeps your access automatically';
    if (apkLink) apkLink.style.display = 'none';
    if (iosBtn) iosBtn.style.display = 'inline-flex';
  } else {
    if (bannerTitle) bannerTitle.textContent = 'Install SoarTV Remote App';
    if (bannerSub) bannerSub.textContent = 'Edge-to-edge · Hardware Volume · Fingerprint';
    if (apkLink) apkLink.style.display = 'inline-flex';
    if (iosBtn) iosBtn.style.display = 'none';
  }

  try {
    if (sessionStorage.getItem('soartv_hide_apk_banner') === '1' && b) {
      b.classList.add('hidden');
    }
  } catch {
    // Ignore
  }
}

function initOkButtonHold(): void {
  const okBtn = document.getElementById('okCenterBtn');
  if (!okBtn) return;
  let holdTimer: ReturnType<typeof setTimeout> | null = null;
  let held = false;

  const startHold = () => {
    held = false;
    if (holdTimer) clearTimeout(holdTimer);
    holdTimer = setTimeout(() => {
      held = true;
      void sendKey('hold_ok');
      showToast('Hold OK: TV Context Menu');
    }, 420);
  };

  const endHold = (e?: Event) => {
    if (holdTimer) clearTimeout(holdTimer);
    if (!held) {
      void sendKey('ok');
    }
    if (e && e.cancelable) e.preventDefault();
  };

  okBtn.addEventListener('touchstart', startHold, { passive: true });
  okBtn.addEventListener('touchend', endHold);
  okBtn.addEventListener('mousedown', startHold);
  okBtn.addEventListener('mouseup', endHold);
}

function initTouchpad(): void {
  const pad = document.getElementById('touchpad');
  const glow = document.getElementById('padTouchGlow');
  if (!pad) return;
  let startX = 0;
  let startY = 0;
  let lastX = 0;
  let lastY = 0;
  let moved = false;
  let held = false;
  let activePointer = false;
  let holdTimer: ReturnType<typeof setTimeout> | null = null;
  const STEP_PX = 34;

  const moveGlow = (clientX: number, clientY: number) => {
    if (!glow) return;
    const rect = pad.getBoundingClientRect();
    glow.style.left = `${clientX - rect.left}px`;
    glow.style.top = `${clientY - rect.top}px`;
    glow.classList.add('visible');
  };

  const hideGlow = () => {
    pad.classList.remove('is-pressing');
    if (glow) glow.classList.remove('visible');
  };

  const handleStart = (clientX: number, clientY: number) => {
    activePointer = true;
    startX = clientX;
    startY = clientY;
    lastX = clientX;
    lastY = clientY;
    moved = false;
    held = false;
    pad.classList.add('is-pressing');
    moveGlow(clientX, clientY);
    if (holdTimer) clearTimeout(holdTimer);
    holdTimer = setTimeout(() => {
      if (!moved && activePointer) {
        held = true;
        void sendKey('hold_ok');
        showToast('Context Menu');
      }
    }, 430);
  };

  const handleMove = (clientX: number, clientY: number) => {
    if (!activePointer) return;
    moveGlow(clientX, clientY);
    const dx = clientX - lastX;
    const dy = clientY - lastY;
    if (Math.abs(dx) >= STEP_PX || Math.abs(dy) >= STEP_PX) {
      moved = true;
      if (holdTimer) clearTimeout(holdTimer);
      const stepX = Math.trunc(dx / STEP_PX);
      const stepY = Math.trunc(dy / STEP_PX);
      lastX += stepX * STEP_PX;
      lastY += stepY * STEP_PX;
      void remoteService.sendScroll(stepX, stepY);
    }
  };

  const handleEnd = () => {
    if (!activePointer) return;
    activePointer = false;
    if (holdTimer) clearTimeout(holdTimer);
    hideGlow();
    if (!moved && !held) {
      const rect = pad.getBoundingClientRect();
      const relX = rect.width > 0 ? (startX - rect.left) / rect.width : 0.5;
      const relY = rect.height > 0 ? (startY - rect.top) / rect.height : 0.5;
      const action = resolveTouchpadTapAction(relX, relY);
      void sendKey(action);
    }
  };

  pad.addEventListener(
    'touchstart',
    (e) => {
      if (e.touches.length === 1) {
        handleStart(e.touches[0].clientX, e.touches[0].clientY);
      }
    },
    { passive: true }
  );

  pad.addEventListener(
    'touchmove',
    (e) => {
      if (e.touches.length === 1) {
        handleMove(e.touches[0].clientX, e.touches[0].clientY);
      }
    },
    { passive: true }
  );

  pad.addEventListener('touchend', handleEnd);
  pad.addEventListener('touchcancel', () => {
    activePointer = false;
    if (holdTimer) clearTimeout(holdTimer);
    hideGlow();
  });

  // Mouse support for desktop testing & foldables
  pad.addEventListener('mousedown', (e) => {
    if (e.button === 0) handleStart(e.clientX, e.clientY);
  });
  window.addEventListener('mousemove', (e) => {
    if (activePointer) handleMove(e.clientX, e.clientY);
  });
  window.addEventListener('mouseup', () => {
    if (activePointer) handleEnd();
  });

  // Restore persisted Clickpad vs D-Pad preference
  const savedMode = readStorage(STORAGE_PAD_MODE);
  if (savedMode === 'keys' || savedMode === 'pad') {
    setPadMode(savedMode);
  }

  // Live keystroke & paste mirroring straight to TV + Enter/Done submits
  const remoteInputEl = document.getElementById('remoteTextInput') as HTMLInputElement | null;
  remoteInputEl?.addEventListener('input', () => {
    lastLocalEditAtMs = Date.now();
    isSearchInputDirty = true;
    updateCopyTvInputButtonVisibility();
    scheduleLiveTvMirror(false);
  });
  remoteInputEl?.addEventListener('paste', () => {
    lastLocalEditAtMs = Date.now();
    isSearchInputDirty = true;
    setTimeout(() => {
      updateCopyTvInputButtonVisibility();
      if (activeFocusedInput?.active || (currentStatus && currentStatus.activeScreen === 0)) {
        scheduleLiveTvMirror(true);
      } else if (remoteInputEl && remoteInputEl.value.trim()) {
        void remoteService.sendText(remoteInputEl.value.trim(), false);
      }
    }, 0);
  });
  remoteInputEl?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void submitText(true);
    }
  });
  document.getElementById('pairCodeInput')?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      void submitSixDigitPairCode();
    }
  });

  // Escape key closes open sheets cleanly
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeAllSheets();
  });
}

function initVisibilityAwarePolling(): void {
  // PERF-15: Pause background polling when phone screen is off or tab is hidden
  setInterval(() => {
    if (document.visibilityState === 'hidden') return;
    void refreshStatus();
  }, 6000);

  document.addEventListener('visibilitychange', () => {
    updateFastInputPollingState();
    if (document.visibilityState === 'visible') {
      if (isPaired) {
        wakeTvOnRemoteActivity();
        void pollLiveState();
        void refreshStatus();
      } else if (activePairRequestId && !pairPollTimer) {
        startPairingPollLoop();
      }
    }
  });
}

function initKeyboardViewportLift(): void {
  if (typeof window === 'undefined' || !window.visualViewport) return;
  const vv = window.visualViewport;
  const updateOffset = () => {
    const kbOffset = computeKeyboardOffsetPx(window.innerHeight, vv.height, vv.offsetTop);
    document.documentElement.style.setProperty('--kb-offset', `${kbOffset}px`);
  };
  vv.addEventListener('resize', updateOffset);
  vv.addEventListener('scroll', updateOffset);
  window.addEventListener('resize', updateOffset);
  updateOffset();
}

function bootCompanionRemote(): void {
  if (typeof document === 'undefined') return;
  registerNativeCallbacksAndGlobals();
  initNativeAndBannerState();
  initKeyboardViewportLift();
  applyRoleVisibility(currentRole);
  initOkButtonHold();
  initTouchpad();
  initVisibilityAwarePolling();
  void bootstrapConnectionAndAuth();
}

bootCompanionRemote();
