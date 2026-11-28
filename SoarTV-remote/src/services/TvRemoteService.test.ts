import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  RealtimeTvRemoteBridge,
  TvRemoteService,
  TvStatus,
  RemoteForbiddenError,
  RemoteUnauthorizedError,
  computeKeyboardOffsetPx,
  formatControllerBadgeLabel,
  formatFocusedInputCue,
  formatGameSubtitle,
  formatPairingSheetCopy,
  getThemeColorMeta,
  pickBestDeviceNickname,
  buildInviteShareText,
  detectInstallPlatform,
  extractInitialQrPairUrl,
  isIosStandaloneWebApp,
  resolveFallbackDockTab,
  resolveLiveMirrorDecision,
  resolveLivePollIntervalMs,
  resolveMirroredModalVisibility,
  resolveSmartHomeIconKind,
  resolveTabVisibility,
  resolveTouchpadTapAction,
  resolveTvInputSyncDecision,
} from './TvRemoteService';

const MOCK_STATUS: TvStatus = {
  tvModel: 'SmartTV-4K',
  tvBrand: 'XIAOMI',
  remoteUrl: 'http://192.168.1.50:8484',
  activeScreen: 1,
  subTab: 0,
  sideShelfOpen: false,
  darkMode: true,
  traktConnected: true,
  traktUser: 'alex_tv',
  controllerSummary: '1 Controller Connected: Xbox Wireless Controller',
  activeUser: {
    id: '824cf01b-96f3-4a6b-a554-621938e5a280',
    username: 'alex',
    displayName: 'Alex',
    avatarColor: '#38BDF8',
    traktUsername: 'alex_tv',
    isDefault: true,
  },
  users: [
    {
      id: '824cf01b-96f3-4a6b-a554-621938e5a280',
      username: 'alex',
      displayName: 'Alex',
      avatarColor: '#38BDF8',
      traktUsername: 'alex_tv',
      isDefault: true,
    },
  ],
  smartHome: [
    {
      id: 'sensibo_living_ac',
      name: 'Living AC',
      protocol: 'SENSIBO_AC',
      icon: 'snowflake',
      isOn: true,
      statusText: '22°C',
      targetTempCelsius: 22,
    },
    {
      id: 'wiz_house_lights',
      name: 'WiZ Lights',
      protocol: 'WIZ_LIGHT',
      icon: 'lightbulb',
      isOn: true,
      statusText: 'On',
      targetTempCelsius: 0,
    },
  ],
  sharedReels: [
    {
      id: 'reel-1',
      sender: 'Alex',
      url: 'https://www.youtube.com/shorts/abc123',
      platform: 'youtube',
      title: 'Funny Cat Short',
    },
  ],
};

describe('TvRemoteService', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('normalizes trailing slashes in base URL', () => {
    const service = new TvRemoteService('http://192.168.1.50:8484///');
    expect(service.getBaseUrl()).toBe('http://192.168.1.50:8484');
  });

  it('fetches TV status snapshot from /api/status', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => MOCK_STATUS,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    const status = await service.getStatus();

    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/status', {
      method: 'GET',
    });
    expect(status.tvModel).toBe('SmartTV-4K');
    expect(status.activeUser.traktUsername).toBe('alex_tv');
    expect(status.smartHome).toHaveLength(2);
  });

  it('sends D-Pad and remote key commands to /api/key', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    const ok = await service.sendKey('gaming');

    expect(ok).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'gaming' }),
    });
  });

  it('rounds scroll deltas and posts to /api/scroll', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    const ok = await service.sendScroll(0.4, -3.7);

    expect(ok).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/scroll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dx: 0, dy: -4 }),
    });
  });

  it('sends copy/paste text and AI search flag to /api/text', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.sendText('   ', true)).toBe(false);
    expect(await service.sendText('  Severance  ', true)).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/text', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: 'Severance', submit: true }),
    });
  });

  it('sends microphone voice transcripts to /api/voice', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.sendVoiceCommand('Turn on water boiler')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/voice', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ transcript: 'Turn on water boiler' }),
    });
  });

  it('casts shared Reels and media URLs via /api/cast', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    const ok = await service.castMedia(
      'https://www.tiktok.com/@user/video/123',
      'Cool TikTok',
      'Alex',
      true
    );
    expect(ok).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/cast', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://www.tiktok.com/@user/video/123',
        title: 'Cool TikTok',
        sender: 'Alex',
        saveReel: true,
      }),
    });
  });

  it('toggles Smart Home devices and adjusts AC temperature via /api/smarthome', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => MOCK_STATUS,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    await service.toggleSmartHomeDevice('wiz_house_lights');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'wiz_house_lights', tempDelta: 0 }),
    });

    await service.adjustAcTemperature('sensibo_living_ac', -1);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: 'sensibo_living_ac', tempDelta: -1 }),
    });
  });

  it('switches and adds user profiles via /api/user', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => MOCK_STATUS,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    await service.switchUserProfile('Alex');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/user', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'switch', username: 'alex' }),
    });

    await service.addUserProfile('Guest', 'guest_trakt');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/user', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'add', displayName: 'Guest', traktUsername: 'guest_trakt' }),
    });
  });

  it('handles network failures gracefully without throwing in postOk methods', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network unreachable'));
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.sendKey('ok')).toBe(false);
  });

  it('restores native launcher or opens settings via /api/launcher_mode', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.setLauncherMode('original')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/launcher_mode', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode: 'original' }),
    });
  });

  it('triggers private GitHub self-update via /api/update', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.triggerSelfUpdate('github_pat_test123', 'owner/SoarTV')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        githubToken: 'github_pat_test123',
        githubRepo: 'owner/SoarTV',
      }),
    });
  });

  it('fetches Gaming Zone status and launches/hides/unlocks games via /api/gaming', async () => {
    const mockGamingStatus = {
      laptopIp: '192.168.1.60',
      awake: true,
      locked: true,
      hdmiConnected: false,
      sunshineRunning: true,
      games: [
        {
          id: 'steam_728880',
          title: 'Overcooked! 2',
          category: 'steam',
          cmd: 'steam://rungameid/728880',
          hidden: false,
        },
      ],
    };
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => mockGamingStatus,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    const status = await service.getGamingStatus();
    expect(status.sunshineRunning).toBe(true);
    expect(status.games[0].title).toBe('Overcooked! 2');

    await service.launchGame('steam_728880', 'Abc123Pin');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/gaming', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'launch',
        gameId: 'steam_728880',
        pin: 'Abc123Pin',
      }),
    });

    await service.unlockLaptop('WinHello99');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/gaming', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'unlock',
        pin: 'WinHello99',
      }),
    });

    await service.setGameHidden('exe_worms_armageddon', true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/gaming', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'hide',
        gameId: 'exe_worms_armageddon',
        hidden: true,
      }),
    });

    await service.wakeGamingPc();
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/gaming', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'wake' }),
    });

    await service.syncGamingLibrary();
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/gaming', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'refresh' }),
    });
  });

  it('controls SoarTV side-shelf, TV Shows/Movies sub-tabs, and all 7 Top-Bar screens via /api/key', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.sendKey('sideshelf')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'sideshelf' }),
    });

    expect(await service.sendKey('subtab_movie')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'subtab_movie' }),
    });

    expect(await service.sendKey('reels')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: 'reels' }),
    });
  });

  it('sends rich Smart Home AC, WiZ Light, Boiler Timer, and Routine commands to /api/smarthome', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => MOCK_STATUS,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    await service.setAcParameters('sensibo_living_ac', true, 'cool', 21, 'high');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'ac_params',
        deviceId: 'sensibo_living_ac',
        on: true,
        acMode: 'cool',
        targetTemp: 21,
        fanLevel: 'high',
      }),
    });

    await service.setLightParameters('wiz_house_lights', true, 20, 2700);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'light_params',
        deviceId: 'wiz_house_lights',
        on: true,
        brightness: 20,
        colorTempK: 2700,
      }),
    });

    await service.setBoilerTimer('switcher_boiler', 30);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'boiler_timer',
        deviceId: 'switcher_boiler',
        timerMinutes: 30,
      }),
    });

    await service.executeSmartHomeRoutine('movie_time');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/smarthome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action: 'routine',
        routineId: 'movie_time',
      }),
    });
  });

  it('builds downloadable Android APK URL from baseUrl for native Android installation', () => {
    const service = new TvRemoteService('http://192.168.1.50:8484/');
    expect(service.getAndroidApkDownloadUrl()).toBe('http://192.168.1.50:8484/SoarTV-Remote.apk');
  });

  it('formats controller battery percentage and connected-to-power labels consistently', () => {
    expect(formatControllerBadgeLabel({ name: 'Green', batteryPercent: 85, charging: false })).toBe('Green · 85%');
    expect(formatControllerBadgeLabel({ name: 'Yellow', batteryPercent: 100, charging: true })).toBe('Yellow · 100%');
    expect(formatControllerBadgeLabel({ name: 'Blue', batteryPercent: -1, charging: true })).toBe('Blue · Connected to Power');
    expect(formatControllerBadgeLabel({ name: 'Pink', batteryPercent: -1, charging: false })).toBe('Pink');
  });

  it('injects Authorization Bearer token on authenticated GET and POST requests', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => MOCK_STATUS,
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch, {
      authToken: 'soartv_tok_secret_256',
    });

    await service.getStatus();
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/status', {
      method: 'GET',
      headers: { Authorization: 'Bearer soartv_tok_secret_256' },
    });

    await service.sendKey('up');
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/key', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer soartv_tok_secret_256',
      },
      body: JSON.stringify({ key: 'up' }),
    });
  });

  it('completes 6-digit code, QR ?pair= link, and 1-click TV remote poll pairing flows', async () => {
    expect(
      TvRemoteService.extractPairSecretFromUrl('http://192.168.1.50:8484/?pair=QrSec_128bit_Test99#top')
    ).toBe('QrSec_128bit_Test99');
    expect(TvRemoteService.extractPairSecretFromUrl('http://192.168.1.50:8484/')).toBe('');

    const mockFetch = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          requestId: 'req_abc',
          expiresAtMs: 1700000300000,
          bootstrapAdmin: false,
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ status: 'pending', expiresAtMs: 1700000300000 }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          status: 'approved',
          ok: true,
          token: 'tv_approved_tok_1',
          deviceId: 'rem_1',
          role: 'HOUSEHOLD',
          expiresAtMs: 0,
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          token: 'qr_verified_tok_2',
          deviceId: 'rem_2',
          role: 'GUEST',
          expiresAtMs: 1702592000000,
        }),
      });

    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    const req = await service.requestPairing('Pixel 9 Pro', 'cid_123');
    expect(req.requestId).toBe('req_abc');

    const pendingPoll = await service.pollPairing('req_abc');
    expect(pendingPoll.status).toBe('pending');
    expect(service.getAuthToken()).toBe('');

    const approvedPoll = await service.pollPairing('req_abc');
    expect(approvedPoll.status).toBe('approved');
    expect(approvedPoll.role).toBe('HOUSEHOLD');
    expect(service.getAuthToken()).toBe('tv_approved_tok_1');

    const qrRes = await service.verifyPairing({
      qrSecret: 'QrSec_128bit_Test99',
      deviceName: 'Guest iPhone',
    });
    expect(qrRes.role).toBe('GUEST');
    expect(service.getAuthToken()).toBe('qr_verified_tok_2');
  });

  it('surfaces 30-day guest_expired 401 responses via RemoteUnauthorizedError and onAuthError listener', async () => {
    const authErrors: Array<RemoteUnauthorizedError | RemoteForbiddenError> = [];
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ error: 'guest_expired', canRepair: true }),
    });

    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch, {
      authToken: 'expired_guest_tok',
      onAuthError: (err) => authErrors.push(err),
    });

    await expect(service.getStatus()).rejects.toBeInstanceOf(RemoteUnauthorizedError);
    expect(authErrors).toHaveLength(1);
    const first = authErrors[0] as RemoteUnauthorizedError;
    expect(first.guestExpired).toBe(true);
    expect(first.canRepair).toBe(true);

    // postOk methods return false without crashing while still notifying onAuthError
    expect(await service.sendKey('ok')).toBe(false);
    expect(authErrors).toHaveLength(2);
  });

  it('builds Google OAuth URLs for native APK vs mobile web and exchanges tokens with whitelist enforcement', async () => {
    const mockFetch = vi
      .fn()
      .mockResolvedValueOnce({
        ok: false,
        status: 403,
        json: async () => ({ error: 'email_not_whitelisted' }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          role: 'ADMIN',
          token: 'admin_google_tok_999',
          deviceId: 'rem_owner',
          email: 'owner@example.com',
          devices: [],
        }),
      });

    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    const nativeUrl = service.buildGoogleSignInUrl('https://demo.supabase.co/', true);
    expect(nativeUrl).toBe(
      'https://demo.supabase.co/auth/v1/authorize?provider=google&redirect_to=soartvremote%3A%2F%2Fauth'
    );

    const webUrl = service.buildGoogleSignInUrl(
      'https://demo.supabase.co',
      false,
      'http://192.168.1.50:8484/'
    );
    expect(webUrl).toBe(
      'https://demo.supabase.co/auth/v1/authorize?provider=google&redirect_to=http%3A%2F%2F192.168.1.50%3A8484%2Fauth%2Fcallback'
    );

    // Random non-whitelisted Gmail must be rejected with RemoteForbiddenError
    await expect(service.exchangeGoogleToken('stranger_jwt', 'Stranger Phone')).rejects.toBeInstanceOf(
      RemoteForbiddenError
    );
    expect(service.getAuthToken()).toBe('');

    // Whitelisted owner Gmail receives permanent local ADMIN token
    const ownerRes = await service.exchangeGoogleToken('owner_jwt', 'Owner Pixel');
    expect(ownerRes.role).toBe('ADMIN');
    expect(service.getAuthToken()).toBe('admin_google_tok_999');
  });

  it('manages paired devices (list, change role, create invite, and lost-phone revoke) via /api/admin/devices', async () => {
    const sampleDevices = [
      {
        deviceId: 'rem_owner',
        deviceName: 'Owner Pixel',
        role: 'ADMIN',
        googleEmail: 'owner@example.com',
        createdAtMs: 1700000000000,
        expiresAtMs: 0,
        lastSeenMs: 1700000050000,
        expired: false,
        daysRemaining: 0,
        isCurrentDevice: true,
      },
      {
        deviceId: 'rem_lost',
        deviceName: 'Old Phone',
        role: 'GUEST',
        googleEmail: '',
        createdAtMs: 1700000000000,
        expiresAtMs: 1702592000000,
        lastSeenMs: 1700000010000,
        expired: false,
        daysRemaining: 30,
        isCurrentDevice: false,
      },
    ];

    const mockFetch = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ ok: true, devices: sampleDevices }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          devices: [sampleDevices[0], { ...sampleDevices[1], role: 'HOUSEHOLD', expiresAtMs: 0 }],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ ok: true, devices: [sampleDevices[0]] }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          requestId: 'inv_1',
          code: '654321',
          qrSecret: 'InviteSecret128BitTest',
          pairUrl: 'http://192.168.1.50:8484/?pair=InviteSecret128BitTest',
          role: 'GUEST',
          expiresAtMs: 1700000300000,
        }),
      });

    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch, {
      authToken: 'admin_tok',
    });

    const listed = await service.listPairedDevices();
    expect(listed).toHaveLength(2);

    const promoted = await service.setDeviceRole('rem_lost', 'HOUSEHOLD');
    expect(promoted[1].role).toBe('HOUSEHOLD');

    const afterRevoke = await service.revokeDevice('rem_lost');
    expect(afterRevoke).toHaveLength(1);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/admin/devices', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer admin_tok',
      },
      body: JSON.stringify({ action: 'revoke', deviceId: 'rem_lost' }),
    });

    const invite = await service.createInvite('GUEST');
    expect(invite.code).toBe('654321');
    expect(invite.pairUrl).toContain('?pair=InviteSecret128BitTest');
  });

  it('aborts stalled LAN requests after 3500ms via AbortController (BP-09)', async () => {
    vi.useFakeTimers();

    const hangingFetch = vi.fn().mockImplementation((_url: string, init?: { signal?: AbortSignal }) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new Error('Aborted after 3500ms timeout'));
        });
      });
    });

    const service = new TvRemoteService('http://192.168.1.50:8484', hangingFetch, {
      timeoutMs: 3500,
      enforceTimeoutOnCustomFetch: true,
    });

    const pendingPromise = service.getStatus();
    const assertion = expect(pendingPromise).rejects.toThrow('Aborted after 3500ms timeout');
    await vi.advanceTimersByTimeAsync(3500);
    await assertion;
  });

  it('resolves dynamic TV base URL across Wi-Fi router swaps without any hardcoded LAN IP (INC-02)', async () => {
    // 1. Served directly from TV on new Wi-Fi subnet -> uses live origin even if old IP was saved
    expect(
      TvRemoteService.resolveDynamicBaseUrl(
        {
          protocol: 'http:',
          hostname: '10.0.0.44',
          port: '8484',
          origin: 'http://10.0.0.44:8484',
        },
        'http://192.168.1.99:8484'
      )
    ).toBe('http://10.0.0.44:8484');

    // 2. Inside hardened Native Android APK (`http://remote.soartv.local`) with no saved IP -> empty (never hardcoded 192.168.1.50)
    expect(
      TvRemoteService.resolveDynamicBaseUrl(
        {
          protocol: 'http:',
          hostname: 'remote.soartv.local',
          port: '',
          origin: 'http://remote.soartv.local',
        },
        ''
      )
    ).toBe('');

    // 3. Inside Native Android APK with user-configured IP -> normalizes to http://<ip>:8484
    expect(
      TvRemoteService.resolveDynamicBaseUrl(
        {
          protocol: 'http:',
          hostname: 'remote.soartv.local',
          port: '',
          origin: 'http://remote.soartv.local',
        },
        '192.168.50.14'
      )
    ).toBe('http://192.168.50.14:8484');

    // 4. Parallel LAN TV discovery returns first valid SoarTV /api/auth/config responder
    const probeFetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.startsWith('http://192.168.50.14:8484')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({ paired: false, tvName: 'MiTV' }),
        };
      }
      throw new Error('Host unreachable');
    });

    const found = await TvRemoteService.probeLanTv(
      ['192.168.50.2', '192.168.50.14'],
      probeFetch,
      500
    );
    expect(found).toBe('http://192.168.50.14:8484');
  });

  it('automatically discovers new TV IP on the /24 subnet when router DHCP changes .27 -> .8', async () => {
    const candidates = TvRemoteService.buildSubnetCandidates('http://192.168.1.50:8484');
    expect(candidates[0]).toBe('http://Android.local:8484');
    expect(candidates).toContain('http://192.168.1.8:8484');
    expect(candidates).not.toContain('http://192.168.1.50:8484');

    const subnetFetch = vi.fn().mockImplementation(async (url: string) => {
      if (url === 'http://192.168.1.8:8484/api/auth/config') {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            paired: true,
            role: 'ADMIN',
            tvName: 'SmartTV-4K',
          }),
        };
      }
      throw new Error('ECONNREFUSED');
    });

    const discoveredUrl = await TvRemoteService.discoverLanTv(
      'http://192.168.1.50:8484',
      subnetFetch,
      6,
      250
    );
    expect(discoveredUrl).toBe('http://192.168.1.8:8484');
  });

  it('parses QR pairing payloads and detects ISO-18004 1:1:3:1:1 QR finder patterns without manual IP entry', () => {
    const httpQr = TvRemoteService.parsePairingQrPayload(
      'http://192.168.1.8:8484/?pair=QrSecret_Live_128Bit_99'
    );
    expect(httpQr.baseUrl).toBe('http://192.168.1.8:8484');
    expect(httpQr.qrSecret).toBe('QrSecret_Live_128Bit_99');

    const deepLinkQr = TvRemoteService.parsePairingQrPayload(
      'soartvremote://pair?url=http%3A%2F%2F192.168.1.8%3A8484&pair=QrSecret_Deep_77'
    );
    expect(deepLinkQr.baseUrl).toBe('http://192.168.1.8:8484');
    expect(deepLinkQr.qrSecret).toBe('QrSecret_Deep_77');

    const codeOnly = TvRemoteService.parsePairingQrPayload('482910');
    expect(codeOnly.code).toBe('482910');

    // Synthetic 64x64 luma image with two 7x7 ISO-18004 finder patterns (scale = 2px per module -> 14x14)
    const width = 64;
    const height = 64;
    const luma = new Uint8Array(width * height);
    luma.fill(240); // White background

    const drawFinder = (ox: number, oy: number, scale: number) => {
      for (let my = 0; my < 7; my++) {
        for (let mx = 0; mx < 7; mx++) {
          const border = mx === 0 || mx === 6 || my === 0 || my === 6;
          const center = mx >= 2 && mx <= 4 && my >= 2 && my <= 4;
          const dark = border || center;
          for (let dy = 0; dy < scale; dy++) {
            for (let dx = 0; dx < scale; dx++) {
              const px = ox + mx * scale + dx;
              const py = oy + my * scale + dy;
              luma[py * width + px] = dark ? 16 : 240;
            }
          }
        }
      }
    };

    expect(TvRemoteService.hasQrFinderPatternInLuma(luma, width, height)).toBe(false);
    drawFinder(6, 6, 2);
    drawFinder(38, 6, 2);
    expect(TvRemoteService.hasQrFinderPatternInLuma(luma, width, height)).toBe(true);
  });

  it('defaults self-update githubRepo to empty string and castMedia sender to Remote', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.triggerSelfUpdate('github_pat_abc')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        githubToken: 'github_pat_abc',
        githubRepo: '',
      }),
    });

    expect(await service.castMedia('https://www.youtube.com/shorts/xyz')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/cast', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://www.youtube.com/shorts/xyz',
        title: 'Shared Media',
        sender: 'Remote',
        saveReel: true,
      }),
    });
  });

  it('respects enabledTabs dynamically across TV Top-Bar screens and Bottom Dock tabs', () => {
    const publicMinimal = resolveTabVisibility({ gaming: false, reels: false, smarthome: false });
    expect(publicMinimal.visibleDockTabs).toEqual(['remote', 'apps']);
    expect(publicMinimal.visibleScreenIndices).toEqual([0, 1, 2, 6]);
    expect(resolveFallbackDockTab('gaming', publicMinimal)).toBe('remote');
    expect(resolveFallbackDockTab('smarthome', publicMinimal)).toBe('remote');
    expect(resolveFallbackDockTab('cast', publicMinimal)).toBe('remote');
    expect(resolveFallbackDockTab('apps', publicMinimal)).toBe('apps');

    const fullPersonal = resolveTabVisibility({ gaming: true, reels: true, smarthome: true });
    expect(fullPersonal.visibleDockTabs).toEqual(['remote', 'gaming', 'smarthome', 'cast', 'apps']);
    expect(fullPersonal.visibleScreenIndices).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(resolveFallbackDockTab('cast', fullPersonal)).toBe('cast');

    const defaultVisibility = resolveTabVisibility(undefined);
    expect(defaultVisibility.reels).toBe(false);
    expect(defaultVisibility.visibleDockTabs).toEqual(['remote', 'gaming', 'smarthome', 'apps']);
  });

  it('maps Smart-Home icons, Gaming subtitles, and Twilight Aurora / Pearl Glass theme colors', () => {
    expect(resolveSmartHomeIconKind({ protocol: 'SENSIBO_AC' })).toBe('ac');
    expect(resolveSmartHomeIconKind({ protocol: 'TUYA_AC' })).toBe('ac');
    expect(resolveSmartHomeIconKind({ protocol: 'WIZ_LIGHT' })).toBe('lightbulb');
    expect(resolveSmartHomeIconKind({ protocol: 'SWITCHER_BOILER' })).toBe('shower');
    expect(resolveSmartHomeIconKind({ protocol: 'CUSTOM', symbol: 'ac' })).toBe('ac');
    expect(resolveSmartHomeIconKind({ protocol: 'UNKNOWN' })).toBe('device');

    expect(formatGameSubtitle({ category: 'steam', hidden: false, lastPlayedAt: 1700000 })).toBe(
      'Steam · Recently Played'
    );
    expect(formatGameSubtitle({ category: 'heroic', hidden: false, lastPlayedAt: 0 })).toBe(
      'Heroic Games'
    );
    expect(formatGameSubtitle({ category: 'epic', hidden: true, lastPlayedAt: 1700000 })).toBe(
      'Epic Games · Hidden'
    );
    expect(formatGameSubtitle({ category: 'standalone', hidden: false })).toBe('PC Game');

    expect(getThemeColorMeta(true)).toEqual({ themeColor: '#0E1024', navColor: '#0A1522' });
    expect(getThemeColorMeta(false)).toEqual({ themeColor: '#F7F9FF', navColor: '#EEF3FC' });

    expect(resolveTouchpadTapAction(0.5, 0.5)).toBe('ok');
    expect(resolveTouchpadTapAction(0.62, 0.42)).toBe('ok');
    expect(resolveTouchpadTapAction(0.5, 0.12)).toBe('up');
    expect(resolveTouchpadTapAction(0.5, 0.88)).toBe('down');
    expect(resolveTouchpadTapAction(0.12, 0.5)).toBe('left');
    expect(resolveTouchpadTapAction(0.88, 0.5)).toBe('right');
  });

  it('syncs focused TV input text (e.g. Netflix "mean gilrs"), protects active typing, and updates/clears TV fields', async () => {
    const netflixInput = {
      active: true,
      text: 'mean gilrs',
      hint: 'Search',
      packageName: 'com.netflix.ninja',
      appLabel: 'Netflix',
      isPassword: false,
      revision: 4,
    };

    // 1. Pre-fills "mean gilrs" on first sync
    const first = resolveTvInputSyncDecision('', netflixInput, false);
    expect(first.shouldSync).toBe(true);
    expect(first.nextValue).toBe('mean gilrs');

    // 2. Does not needlessly re-sync identical revision/text
    const same = resolveTvInputSyncDecision(first.nextSig, netflixInput, false);
    expect(same.shouldSync).toBe(false);

    // 3. Protects unsent phone edits while user is actively fixing typo
    const updatedTv = { ...netflixInput, text: 'mean gilrs 2', revision: 5 };
    const protectedEdit = resolveTvInputSyncDecision(first.nextSig, updatedTv, true);
    expect(protectedEdit.shouldSync).toBe(false);
    expect(protectedEdit.nextSig).toBe(first.nextSig);

    // 4. Redacts password fields & hides cue when inactive
    const pwdCue = formatFocusedInputCue({ ...netflixInput, text: 'secret', isPassword: true });
    expect(pwdCue).toEqual({
      visible: true,
      appLabel: 'Netflix',
      previewText: 'Password field',
      canCopy: false,
    });
    expect(formatFocusedInputCue(netflixInput)).toEqual({
      visible: true,
      appLabel: 'Netflix',
      previewText: 'mean gilrs',
      canCopy: true,
    });
    expect(formatFocusedInputCue({ active: false, text: '' }).visible).toBe(false);

    // 5. Sends replaceInput:true to /api/text for both typo correction and clearing
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true, appliedToField: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);

    expect(await service.updateTvInputField('mean girls', true)).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/text', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: 'mean girls', submit: true, replaceInput: true }),
    });

    expect(await service.updateTvInputField('', false)).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/text', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: '', submit: false, replaceInput: true }),
    });
  });

  it('mirrors every keystroke/paste live to TV and lifts bottom sheets cleanly above phone keyboard', () => {
    // Live keystroke & paste mirroring when a TV input field is focused
    expect(resolveLiveMirrorDecision('m', '', true, false)).toEqual({
      shouldMirror: true,
      mode: 'focused_input',
      value: 'm',
    });
    expect(resolveLiveMirrorDecision('mean girls', 'mean gilrs', true, false)).toEqual({
      shouldMirror: true,
      mode: 'focused_input',
      value: 'mean girls',
    });
    // Clearing field mirrors empty string immediately
    expect(resolveLiveMirrorDecision('', 'mean girls', true, false)).toEqual({
      shouldMirror: true,
      mode: 'focused_input',
      value: '',
    });
    // Unchanged value skips redundant HTTP calls
    expect(resolveLiveMirrorDecision('mean girls', 'mean girls', true, false).shouldMirror).toBe(false);
    // On SoarTV Search screen without external focusedInput, mirrors directly into Search
    expect(resolveLiveMirrorDecision('severance', 'severanc', false, true)).toEqual({
      shouldMirror: true,
      mode: 'search',
      value: 'severance',
    });
    // Outside input fields & Search, does not mirror unsolicited typing
    expect(resolveLiveMirrorDecision('hello', '', false, false).shouldMirror).toBe(false);

    // Keyboard viewport lift math (iOS Safari / Android Chrome vs already-resized Native APK)
    expect(computeKeyboardOffsetPx(844, 510, 0)).toBe(334);
    expect(computeKeyboardOffsetPx(844, 510, 12)).toBe(322);
    expect(computeKeyboardOffsetPx(510, 510, 0)).toBe(0);
    expect(computeKeyboardOffsetPx(844, 820, 0)).toBe(0);
  });

  it('mirrors active TV user-action/QR modals cleanly and posts /api/wake and /api/modal commands', async () => {
    const qrModal = {
      active: true,
      id: 'stremio_qr:FWG4',
      kind: 'qr_link',
      title: 'Connect Stremio Account',
      url: 'https://link.stremio.com/FWG4',
      code: 'FWG4',
    };
    expect(resolveMirroredModalVisibility(qrModal, '', false)).toEqual({
      visible: true,
      modalId: 'stremio_qr:FWG4',
    });
    // Locally dismissing stops re-popping for the same modal ID
    expect(resolveMirroredModalVisibility(qrModal, 'stremio_qr:FWG4', false).visible).toBe(false);
    // A new modal ID pops up cleanly
    expect(
      resolveMirroredModalVisibility({ ...qrModal, id: 'trakt_qr:ABCD' }, 'stremio_qr:FWG4', false).visible
    ).toBe(true);
    // Suppressed while pairing sheet is active or TV modal closed
    expect(resolveMirroredModalVisibility(qrModal, '', true).visible).toBe(false);
    expect(resolveMirroredModalVisibility({ active: false }, '', false).visible).toBe(false);

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    expect(await service.wakeTv()).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/wake', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    });
    expect(await service.executeModalAction('lg_choice:0')).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith('http://192.168.1.50:8484/api/modal', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ actionId: 'lg_choice:0' }),
    });
  });

  it('prefers native phone Bluetooth/device nickname, computes fast /api/live cadence, and simplifies first-time Admin copy', async () => {
    // 1. Phone nickname resolution
    expect(pickBestDeviceNickname("Alex's S25 Ultra", 'Mozilla/5.0 (Linux; Android 16; SM-S938B)', true)).toBe(
      "Alex's S25 Ultra"
    );
    expect(pickBestDeviceNickname('Android', 'Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro Build/AP2A)', true)).toBe(
      'Pixel 9 Pro'
    );
    expect(pickBestDeviceNickname('', 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', false)).toBe(
      'iPhone Remote'
    );

    // 2. Foreground-only fast /api/live poll cadence (near-instant mirrored modals, zero background drain)
    expect(resolveLivePollIntervalMs(true, false, false)).toBe(650);
    expect(resolveLivePollIntervalMs(true, false, true)).toBe(500);
    expect(resolveLivePollIntervalMs(true, true, true)).toBe(0);
    expect(resolveLivePollIntervalMs(false, false, false)).toBe(0);

    // 3. First-time Owner / Admin pairing sheet guidance copy
    const ownerCopy = formatPairingSheetCopy(true, false, 'SmartTV-4K');
    expect(ownerCopy.badge).toContain('Owner');
    expect(ownerCopy.title).toContain('Owner');
    expect(ownerCopy.subtitle).toContain('Admin');
    expect(ownerCopy.expandManualByDefault).toBe(false);

    const guestExpiredCopy = formatPairingSheetCopy(false, true, 'SmartTV-4K');
    expect(guestExpiredCopy.title).toContain('Expired');
    expect(guestExpiredCopy.expandManualByDefault).toBe(true);

    // 4. GET /api/live and POST /api/admin/devices (action: rename)
    const mockFetch = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          activeScreen: 5,
          activeModal: { active: true, id: 'stremio_qr:ABCD', kind: 'qr_link', code: 'ABCD' },
          focusedInput: { active: false },
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({
          ok: true,
          devices: [
            {
              deviceId: 'rem_1',
              deviceName: 'Bedroom Phone',
              role: 'ADMIN',
              createdAtMs: 1,
              expiresAtMs: 0,
              lastSeenMs: 2,
              expired: false,
              daysRemaining: 0,
              isCurrent: true,
            },
          ],
        }),
      });
    const service = new TvRemoteService('http://192.168.1.50:8484', mockFetch);
    service.setAuthToken('tok_admin');
    const live = await service.getLiveState();
    expect(live.activeModal?.id).toBe('stremio_qr:ABCD');
    expect(mockFetch).toHaveBeenNthCalledWith(1, 'http://192.168.1.50:8484/api/live', {
      method: 'GET',
      headers: { Authorization: 'Bearer tok_admin' },
    });

    const renamedList = await service.renamePairedDevice('rem_1', 'Bedroom Phone');
    expect(renamedList[0].deviceName).toBe('Bedroom Phone');
    expect(mockFetch).toHaveBeenNthCalledWith(2, 'http://192.168.1.50:8484/api/admin/devices', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok_admin' },
      body: JSON.stringify({ action: 'rename', deviceId: 'rem_1', deviceName: 'Bedroom Phone' }),
    });
  });

  it('preserves initial QR invite URL before history cleanup, detects iOS vs Android install surface, and formats shareable Admin invites', () => {
    // 1. Initial QR pairing URL must be preserved before history.replaceState cleans the address bar
    const qrHref = 'http://192.168.1.50:8484/?pair=QrSecret128BitAdminInvite';
    expect(extractInitialQrPairUrl(qrHref)).toBe(qrHref);
    expect(extractInitialQrPairUrl('http://192.168.1.50:8484/')).toBe('');

    // 2. Platform-aware install guidance (never offer Android .apk to iPhone Safari!)
    expect(
      detectInstallPlatform(
        'Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1',
        false,
        false
      )
    ).toBe('ios_browser');
    expect(
      detectInstallPlatform(
        'Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro) AppleWebKit/537.36 Chrome/133.0.0.0 Mobile Safari/537.36',
        false,
        false
      )
    ).toBe('android_browser');
    expect(
      detectInstallPlatform('Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X)', false, true)
    ).toBe('installed_app');
    expect(
      detectInstallPlatform('Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro)', true, false)
    ).toBe('installed_app');
    expect(
      detectInstallPlatform('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/133.0.0.0', false, false)
    ).toBe('desktop_browser');

    // 3. Shareable Admin invite text includes role, code, link, same Wi-Fi, and Android + iPhone install guidance
    const shareText = buildInviteShareText('ADMIN', '482910', qrHref, 'SmartTV-4K');
    expect(shareText).toContain('Admin');
    expect(shareText).toContain('482 910');
    expect(shareText).toContain(qrHref);
    expect(shareText).toContain('Wi-Fi');
    expect(shareText).toContain('iPhone');
    expect(shareText).toContain('Android');

    // 4. iOS standalone Home Screen detection for top-bar blur clearance
    expect(isIosStandaloneWebApp('Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X)', true)).toBe(true);
    expect(isIosStandaloneWebApp('Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X)', false)).toBe(false);
    expect(isIosStandaloneWebApp('Mozilla/5.0 (Linux; Android 16; Pixel 9 Pro)', true)).toBe(false);
  });

  it('fetches Household TVs from Supabase, self-heals after router DHCP IP change, and bridges LG/Samsung via Realtime', async () => {
    const creds = {
      supabaseUrl: 'https://demo-hh.supabase.co',
      supabaseAnonKey: 'anon_key_abc',
      householdKey: 'hh_secret_key_xyz',
    };

    const cloudRows = [
      {
        device_id: 'tv-living-uuid',
        device_name: 'Living Room TV',
        room_name: 'Living Room',
        platform: 'android-tv',
        brand: 'SONY',
        model: 'XR-65A80L',
        lan_ip: '192.168.50.88',
        remote_port: 8484,
        mdns_host: 'soartv-living-room-tv.local',
        realtime_channel: '',
        last_seen_at: '2026-11-28T10:00:00Z',
      },
      {
        device_id: 'tv-bedroom-lg',
        device_name: 'Bedroom OLED',
        room_name: 'Bedroom',
        platform: 'lg-webos',
        brand: 'LG_WEBOS',
        model: 'OLED55C3',
        lan_ip: '192.168.50.91',
        remote_port: 8484,
        mdns_host: 'soartv-bedroom-oled.local',
        realtime_channel: 'tv-bedroom-lg',
        last_seen_at: '2026-11-28T09:50:00Z',
      },
    ];

    const mockFetch = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/rest/v1/tv_devices')) {
        return { ok: true, status: 200, json: async () => cloudRows };
      }
      if (url === 'http://192.168.50.88:8484/api/auth/config') {
        return { ok: true, status: 200, json: async () => ({ paired: true, role: 'ADMIN', tvName: 'Living Room TV' }) };
      }
      throw new Error('ECONNREFUSED');
    });

    const tvs = await TvRemoteService.fetchHouseholdTvDevices(creds, mockFetch);
    expect(tvs).toHaveLength(2);
    expect(tvs[0].deviceId).toBe('tv-living-uuid');
    expect(TvRemoteService.isWebRealtimeTv(tvs[0])).toBe(false);
    expect(TvRemoteService.isWebRealtimeTv(tvs[1])).toBe(true);

    // Self-healing cascade: old IP .42 fails -> fetches fresh .88 from Supabase tv_devices -> reconnects!
    const healed = await TvRemoteService.resolveReachableTv(
      { ...tvs[0], lanIp: '192.168.50.42' },
      'http://192.168.50.42:8484',
      creds,
      mockFetch
    );
    expect(healed?.mode).toBe('lan');
    expect(healed?.source).toBe('supabase_lan');
    expect(healed?.baseUrl).toBe('http://192.168.50.88:8484');

    // LG webOS TV routes directly to Supabase Realtime mode
    const lgConn = await TvRemoteService.resolveReachableTv(tvs[1], '', creds, mockFetch);
    expect(lgConn?.mode).toBe('realtime');
    expect(lgConn?.source).toBe('realtime');

    // RealtimeTvRemoteBridge sends Phoenix phx_join + remote_command broadcast frames
    const sentFrames: string[] = [];
    let statusObserved = false;
    let fakeWs: {
      readyState: number;
      onopen: (() => void) | null;
      onmessage: ((ev: { data: string }) => void) | null;
      onerror: (() => void) | null;
      onclose: (() => void) | null;
      send: (data: string) => void;
      close: () => void;
    } | null = null;
    const bridge = new RealtimeTvRemoteBridge(creds, 'tv-bedroom-lg', {
      wsFactory: () => {
        fakeWs = {
          readyState: 1,
          onopen: null,
          onmessage: null,
          onerror: null,
          onclose: null,
          send: (d: string) => sentFrames.push(d),
          close: () => {
            if (fakeWs) fakeWs.readyState = 3;
          },
        };
        return fakeWs;
      },
      onStatusChange: (ok) => {
        statusObserved = ok;
      },
    });
    bridge.connect();
    fakeWs!.onopen?.();
    fakeWs!.onmessage?.({ data: JSON.stringify({ event: 'phx_reply', payload: { status: 'ok' } }) });
    expect(statusObserved).toBe(true);
    expect(bridge.isConnected()).toBe(true);
    expect(bridge.sendKey('right')).toBe(true);
    expect(bridge.sendText('Severance', true)).toBe(true);
    expect(sentFrames.some((f) => f.includes('"event":"phx_join"') && f.includes('realtime:soartv-remote:tv-bedroom-lg'))).toBe(true);
    expect(sentFrames.some((f) => f.includes('"event":"remote_command"') && f.includes('"key":"right"'))).toBe(true);
    expect(sentFrames.some((f) => f.includes('"text":"Severance"') && f.includes('"ai":true'))).toBe(true);
    bridge.disconnect();
  });
});
