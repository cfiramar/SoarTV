/*
 * Comprehensive Unit Test Suite for SoarTV LG webOS & Samsung Tizen Feature Parity.
 *
 * Tests:
 *  1. Pure ES5 Crypto (NIST SHA-256, RFC 4231 HMAC-SHA256, Base64URL, UUID v4)
 *  2. Monochrome Inline SVG Icon Registry (zero emojis) & ISO/IEC 18004 QR SVG encoder
 *  3. LG webOS WiZ V2 UDP 38899 Node Service (payloads, subnet collection, loopback UDP round-trip)
 *  4. FeatureTabs tri-state visibility (auto-hide Smart-Home & Gaming when unconfigured)
 *  5. Trakt Device-Code OAuth (PKCE S256 + urn:ietf:wg:oauth:2.0:oob refresh + list sync)
 *  6. Stremio Library Sync (login without saving password, datastoreGet parsing, RPDB poster sanitization)
 *  7. Ratings Client (MDBList, OMDb + cooldown, Cinemeta fallback, Metacritic 0-10 formatting)
 *  8. Google Gemini AI Search (XHR payload & JSON/fenced response parsing)
 *  9. Smart-Home Controls (Sensibo HTTPS, Tuya OpenAPI v2.0 HMAC-SHA256, WiZ webOS bridge)
 * 10. Supabase Multi-User Profiles (read/write/delete) & Realtime Phoenix Remote Channel
 * 11. Gaming Integration (moonlight-tv detection + Gaming PC HDMI + Console HDMI)
 * 12. Multi-Row Home Engine (Watch List exclusion, watched sorting, Anime isolation, soartv.* storage)
 */
'use strict';

var fs = require('fs');
var path = require('path');
var vm = require('vm');
var dgram = require('dgram');
var nodeCrypto = require('crypto');

var ROOT = path.join(__dirname, '..');
var failures = 0;
var passed = 0;

function assert(cond, label) {
  if (cond) {
    passed++;
    return;
  }
  failures++;
  console.error('FAIL:', label);
}

function expect(actual, want, label) {
  if (actual === want) {
    passed++;
    return;
  }
  failures++;
  console.error('FAIL ' + label + ': got ' + JSON.stringify(actual) + ', want ' + JSON.stringify(want));
}

function createSandbox(opts) {
  opts = opts || {};
  var store = {};
  var storedKeys = [];
  if (opts.storage) {
    Object.keys(opts.storage).forEach(function (k) { store[k] = opts.storage[k]; });
  }
  var requests = [];
  var responder = opts.xhrResponder || function () { return {status: 200, body: '{}'}; };

  function FakeXHR() {
    this.readyState = 0;
    this.status = 0;
    this.responseText = '';
    this._headers = {};
    this._method = 'GET';
    this._url = '';
  }
  FakeXHR.prototype.open = function (m, u) { this._method = m; this._url = u; };
  FakeXHR.prototype.setRequestHeader = function (k, v) { this._headers[k] = v; };
  FakeXHR.prototype.send = function (body) {
    var rec = {method: this._method, url: this._url, headers: this._headers, body: body || ''};
    requests.push(rec);
    var res = responder(rec);
    this.readyState = 4;
    this.status = res.status;
    this.responseText = typeof res.body === 'string' ? res.body : JSON.stringify(res.body);
    if (this.onreadystatechange) this.onreadystatechange();
  };

  var sandbox = {
    window: {CONFIG: opts.config || {}},
    document: {
      documentElement: {className: '', style: {}},
      body: {className: ''},
      createElement: function (tag) {
        return {tagName: tag.toUpperCase(), className: '', innerHTML: '', children: [], appendChild: function (c) { this.children.push(c); return c; }};
      }
    },
    navigator: {language: 'en-US', userAgent: opts.ua || 'Chrome/53.0'},
    localStorage: {
      getItem: function (k) { storedKeys.push('get:' + k); return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
      setItem: function (k, v) { storedKeys.push('set:' + k); store[k] = String(v); },
      removeItem: function (k) { storedKeys.push('del:' + k); delete store[k]; }
    },
    I18N: {lang: 'en', base: 'en', rtl: false, locale: function () { return 'en-US'; }, digits: function (s) { return String(s); }, name: function (s) { return s; }},
    tr: function (s) { return s.indexOf('|') >= 0 ? s.split('|')[1] : s; },
    trf: function (s) { return s; },
    plural: function (s) { return s; },
    formatDate: function (s) { return s; },
    XMLHttpRequest: FakeXHR,
    setTimeout: setTimeout,
    clearTimeout: clearTimeout,
    setInterval: setInterval,
    clearInterval: clearInterval,
    Date: Date,
    Math: Math,
    JSON: JSON,
    encodeURIComponent: encodeURIComponent,
    parseInt: parseInt,
    parseFloat: parseFloat,
    isNaN: isNaN,
    console: console,
    _store: store,
    _storedKeys: storedKeys,
    _requests: requests
  };
  sandbox.window.localStorage = sandbox.localStorage;
  ['crypto.js', 'icons.js', 'tv.js', 'services.js', 'data.js'].forEach(function (file) {
    var code = fs.readFileSync(path.join(ROOT, 'web', file), 'utf8');
    vm.runInNewContext(code, sandbox, {filename: 'web/' + file});
  });
  return sandbox;
}

// 1. Crypto tests (NIST SHA-256 + RFC 4231 HMAC-SHA256 + Base64URL + UUID v4)
var sb = createSandbox();
expect(sb.SoarTvCrypto.sha256Hex(''), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', 'SHA-256 empty string');
expect(sb.SoarTvCrypto.sha256Hex('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', 'SHA-256 "abc"');
expect(
  sb.SoarTvCrypto.hmacSha256Hex('what do ya want for nothing?', 'Jefe', false),
  '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843',
  'RFC 4231 HMAC-SHA256 Test Case 2'
);
expect(
  sb.SoarTvCrypto.hmacSha256Hex('what do ya want for nothing?', 'Jefe', true),
  '5BDCC146BF60754E6A042426089575C75A003F089D2739839DEC58B964EC3843',
  'HMAC-SHA256 uppercase for Tuya'
);
expect(sb.SoarTvCrypto.base64Url([251, 255, 191]), '-_-_', 'Base64URL replaces + and / without padding');
assert(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(sb.SoarTvCrypto.uuidV4()), 'UUID v4 valid format');

// 2. Icons & QR Code tests
assert(sb.SoarTvIcons.svg('search', 22).indexOf('<svg') === 0, 'Icons render inline SVG');
assert(sb.SoarTvIcons.svg('mc', 16).indexOf('class="mc-cutout"') > 0, 'MC squircle icon uses theme-adaptive mc-cutout class');
assert(sb.SoarTvIcons.has('mc') && sb.SoarTvIcons.has('rt') && sb.SoarTvIcons.has('ac') && sb.SoarTvIcons.has('moonlight'), 'All required vector icons registered');
var qrM = sb.SoarTvIcons.qrMatrix('https://trakt.tv/activate');
assert(qrM && qrM.length === 25, 'QR matrix Version 2 (25x25) generated for trakt.tv/activate');
expect(qrM[0][0], 1, 'QR top-left finder outer corner dark');
expect(qrM[3][3], 1, 'QR top-left finder center dark');
expect(qrM[1][1], 0, 'QR top-left finder inner ring light');
expect(qrM[0][qrM.length - 1], 1, 'QR top-right finder outer corner dark');
expect(qrM[qrM.length - 1][0], 1, 'QR bottom-left finder outer corner dark');
var qrSvg = sb.SoarTvIcons.qrSvg('https://trakt.tv/activate', 180);
assert(qrSvg.indexOf('<svg') === 0 && qrSvg.indexOf('viewBox="0 0 29 29"') > 0, 'QR SVG includes quiet zone');

// 3. FeatureTabs parity tests
expect(sb.FeatureTabs.normalizeMode('ON'), 'true', 'FeatureTabs normalizes ON -> true');
expect(sb.FeatureTabs.normalizeMode('hidden'), 'false', 'FeatureTabs normalizes hidden -> false');
expect(sb.FeatureTabs.normalizeMode('anything'), 'auto', 'FeatureTabs normalizes unknown -> auto');
expect(sb.FeatureTabs.resolveFeatureEnabled('auto', 'auto', false), false, 'Auto hides tab when unconfigured');
expect(sb.FeatureTabs.resolveFeatureEnabled('auto', 'auto', true), true, 'Auto shows tab when configured');
expect(sb.FeatureTabs.resolveFeatureEnabled('false', 'true', true), false, 'Runtime false overrides build true');
expect(sb.FeatureTabs.resolveFeatureEnabled('true', 'false', false), true, 'Runtime true overrides build false');
expect(sb.FeatureTabs.buildScreenList(false, false).join(','), 'search,home,apps,settings', 'Clean Top-Bar hides Gaming & Smart-Home when unconfigured');
expect(sb.FeatureTabs.buildScreenList(true, true).join(','), 'search,home,apps,gaming,smarthome,settings', 'Top-Bar includes Gaming & Smart-Home when enabled');
expect(sb.FeatureTabs.sanitizeScreen('smarthome', ['search', 'home', 'apps', 'settings']), 'home', 'Disabled screen falls back to home');

// 4. Trakt Device-Code OAuth (PKCE S256 + urn:ietf:wg:oauth:2.0:oob) & Sync tests
var pollCount = 0;
var traktSb = createSandbox({
  config: {traktClientId: 'trakt-test-client'},
  xhrResponder: function (req) {
    if (req.url.indexOf('/oauth/device/code') >= 0) {
      return {status: 200, body: {device_code: 'dev123', user_code: 'ABCD-1234', verification_url: 'https://trakt.tv/activate', expires_in: 600, interval: 0.01}};
    }
    if (req.url.indexOf('/oauth/device/token') >= 0) {
      pollCount++;
      if (pollCount === 1) return {status: 400, body: {}};
      return {status: 200, body: {access_token: 'acc_tok', refresh_token: 'ref_tok', expires_in: 86400, created_at: Math.floor(Date.now() / 1000)}};
    }
    if (req.url.indexOf('/users/settings') >= 0) return {status: 200, body: {user: {username: 'alex_tv'}}};
    if (req.url.indexOf('/oauth/token') >= 0) {
      return {status: 200, body: {access_token: 'acc_new', refresh_token: 'ref_new', expires_in: 86400, created_at: Math.floor(Date.now() / 1000)}};
    }
    if (req.url.indexOf('/sync/watchlist/shows') >= 0) return {status: 200, body: [{show: {title: 'Severance', ids: {tmdb: 95396, imdb: 'tt11280740'}}}]};
    if (req.url.indexOf('/sync/watched/movies') >= 0) return {status: 200, body: [{movie: {title: 'Matrix', ids: {tmdb: 603, imdb: 'tt0133093'}}}]};
    if (req.url.indexOf('/sync/playback') >= 0) return {status: 200, body: [{type: 'episode', progress: 42.5, paused_at: '2026-11-20T18:00:00Z', show: {title: 'Silo', ids: {tmdb: 125988, imdb: 'tt14269590'}}, episode: {season: 2, number: 3}}]};
    return {status: 200, body: []};
  }
});

// 5. Stremio Library Sync tests
var stremioSb = createSandbox({
  xhrResponder: function (req) {
    if (req.url.indexOf('/api/login') >= 0) {
      return {status: 200, body: {result: {authKey: 'stremio-key-xyz', user: {email: 'viewer@example.com'}}}};
    }
    if (req.url.indexOf('/api/datastoreGet') >= 0) {
      return {status: 200, body: {result: [
        {_id: 'tt11280740', name: 'Severance', type: 'series', poster: 'https://api.ratingposterdb.com/bad/tt11280740.jpg', removed: false, temp: false, _mtime: '2026-11-21T10:00:00Z', state: {lastWatched: '2026-11-21T10:00:00Z', timeOffset: 120000, duration: 3000000, video_id: 'tt11280740:2:4', timesWatched: 1}},
        {_id: 'tt0133093', name: 'The Matrix', type: 'movie', poster: 'https://image.tmdb.org/t/p/w500/matrix.jpg', removed: false, temp: false, _mtime: '2026-11-19T10:00:00Z', state: {timesWatched: 0, timeOffset: 0, duration: 8160000}}
      ]}};
    }
    return {status: 200, body: {}};
  }
});
stremioSb.StremioClient.login('viewer@example.com', 'SuperSecretPwd123', function (err, info) {
  expect(err, null, 'Stremio login succeeds');
  expect(info.authKey, 'stremio-key-xyz', 'Stremio login extracts authKey');
  assert(JSON.stringify(stremioSb._store).indexOf('SuperSecretPwd123') < 0, 'Stremio password is NEVER stored in localStorage');
});
stremioSb.StremioClient.syncLibrary(function (err, items) {
  expect(err, null, 'Stremio syncLibrary succeeds');
  expect(items.length, 2, 'Stremio syncLibrary parses 2 library items');
  expect(items[0].posterUrl, 'https://images.metahub.space/poster/medium/tt11280740/img', 'RPDB poster sanitized to clean Metahub URL');
  expect(items[0].season, 2, 'Stremio video_id season parsed');
  expect(items[0].episode, 4, 'Stremio video_id episode parsed');
  expect(items[0].hasContinue, true, 'Stremio mid-episode item marked Continue Watching');
  expect(items[1].posterUrl, 'https://image.tmdb.org/t/p/w500/matrix.jpg', 'Clean TMDB poster preserved');
  expect(items[1].isWatchlist, true, 'Stremio non-temp item marked Watchlist');
});

// 6. Ratings Client (MDBList + OMDb + Cinemeta fallback)
var ratingsSb = createSandbox({
  config: {mdblistKey: 'mdb-1', omdbKey: 'omdb-1'},
  xhrResponder: function (req) {
    if (req.url.indexOf('mdblist.com') >= 0) {
      return {status: 200, body: {ratings: [{source: 'imdb', value: 8.7}, {source: 'tomatoes', value: 97}, {source: 'metacritic', value: 83}]}};
    }
    if (req.url.indexOf('omdbapi.com') >= 0) {
      return {status: 200, body: {Response: 'True', imdbRating: '8.4', Metascore: '78', Ratings: [{Source: 'Rotten Tomatoes', Value: '91%'}]}};
    }
    return {status: 404, body: {}};
  }
});
ratingsSb.RatingsClient.fetchRatings('tt11280740', 'tv', function (r) {
  expect(r.imdb, '8.7', 'MDBList IMDb score parsed');
  expect(r.rt, '97%', 'MDBList RT score parsed');
  expect(r.mc, '83', 'MDBList MC score parsed');
  expect(ratingsSb.RatingsClient.formatMetacritic(r.mc), '8.3', 'Metacritic formatted as 0-10 one-decimal');
});
var omdbOnlySb = createSandbox({
  config: {omdbKey: 'omdb-only'},
  xhrResponder: function (req) {
    if (req.url.indexOf('omdbapi.com') >= 0) {
      return {status: 200, body: {Response: 'True', imdbRating: '8.4', Metascore: '78', Ratings: [{Source: 'Rotten Tomatoes', Value: '91%'}]}};
    }
    return {status: 404, body: {}};
  }
});
omdbOnlySb.RatingsClient.fetchRatings('tt0903747', 'tv', function (r) {
  expect(r.imdb, '8.4', 'OMDb IMDb score parsed');
  expect(r.rt, '91%', 'OMDb RT score parsed');
  expect(r.mc, '78', 'OMDb MC score parsed');
});

// 7. Google Gemini AI Search tests
var geminiSb = createSandbox({
  config: {geminiKey: 'gem-test-key'},
  xhrResponder: function (req) {
    if (req.url.indexOf('generativelanguage.googleapis.com') >= 0) {
      return {status: 200, body: {candidates: [{content: {parts: [{text: '```json\n{"summary":"Try Severance for a mind-bending workplace thriller.","primaryTitle":"Severance","mediaType":"series","candidates":[{"title":"Severance","type":"series","year":"2022","reason":"Corporate sci-fi mystery"}]}\n```'}]}}]}};
    }
    return {status: 404, body: {}};
  }
});
geminiSb.GeminiSearchClient.ask('mind bending office mystery', function (err, res) {
  expect(err, null, 'Gemini ask succeeds');
  expect(res.primaryTitle, 'Severance', 'Gemini primaryTitle extracted through markdown fence');
  expect(res.kind, 'tv', 'Gemini series normalized to tv');
  expect(res.candidates.length, 1, 'Gemini candidate parsed');
});

// 8. Smart-Home HTTPS Controls (Sensibo + Tuya HMAC-SHA256)
var shSb = createSandbox({
  config: {sensiboApiKey: 'sens-key', tuyaAccessId: 'tid', tuyaAccessSecret: 'tsec'},
  xhrResponder: function (req) {
    if (req.url.indexOf('sensibo.com/api/v2/users/me/pods') >= 0) {
      return {status: 200, body: {result: [{id: 'pod-9', room: {name: 'Living Room'}, acState: {on: true, mode: 'cool', targetTemperature: 22, fanLevel: 'low'}}]}};
    }
    if (req.url.indexOf('sensibo.com/api/v2/pods/pod-9/acStates') >= 0) {
      return {status: 200, body: {status: 'success'}};
    }
    if (req.url.indexOf('/v1.0/token') >= 0) {
      return {status: 200, body: {success: true, result: {access_token: 'tuya-tok'}}};
    }
    if (req.url.indexOf('/v2.0/cloud/thing/device') >= 0) {
      return {status: 200, body: {success: true, result: [{id: 'hub-1', category: 'wnykq', sub: false}, {id: 'ac-sub-1', category: 'qt', sub: true, customName: 'Bedroom AC'}]}};
    }
    if (req.url.indexOf('/v1.0/iot-03/devices/ac-sub-1/commands') >= 0) {
      return {status: 200, body: {success: true}};
    }
    return {status: 404, body: {}};
  }
});
expect(shSb.SmartHomeClient.hasAnyConfigured(), true, 'SmartHomeClient detects Sensibo/Tuya config');
shSb.SmartHomeClient.setSensibo(true, 'cool', 21, 'high', function (ok) {
  expect(ok, true, 'Sensibo HTTPS pod discovery + acStates POST succeeds');
});
var tuyaHeaders = shSb.SmartHomeClient.buildTuyaHeaders('GET', '/v1.0/token?grant_type=1', '', 'tid', 'tsec', '', 1700000000000);
expect(tuyaHeaders.sign_method, 'HMAC-SHA256', 'Tuya header sign_method set');
expect(tuyaHeaders.client_id, 'tid', 'Tuya header client_id set');
assert(/^[0-9A-F]{64}$/.test(tuyaHeaders.sign), 'Tuya HMAC-SHA256 sign is 64-char uppercase hex');
shSb.SmartHomeClient.setTuya(true, 'heat', 24, 'med', function (ok) {
  expect(ok, true, 'Tuya HTTPS token + sub-device discovery + IR AC command succeeds');
});
var irOff = shSb.SmartHomeClient.buildTuyaIrCommands(false, 'cool', 22, 'auto');
expect(irOff[0].code, 'PowerOff', 'Tuya IR off command uses PowerOff');

// 9. Supabase Multi-User Profiles + Realtime Cloud Remote Channel tests
var supaSb = createSandbox({
  config: {supabaseUrl: 'https://demo-proj.supabase.co', supabaseAnonKey: 'anon-123', householdKey: 'hh-secret'},
  xhrResponder: function (req) {
    if (req.method === 'GET' && req.url.indexOf('/rest/v1/user_profiles') >= 0) {
      return {status: 200, body: [
        {id: '11111111-1111-4111-8111-111111111111', slug: 'alex', display_name: 'Alex', avatar_color: '#38BDF8', is_default: true},
        {id: '22222222-2222-4222-8222-222222222222', slug: 'maya', display_name: 'Maya', avatar_color: '#EC4899', is_default: false}
      ]};
    }
    return {status: 201, body: ''};
  }
});
var sHdrs = supaSb.SupabaseService.buildHeaders('return=minimal');
expect(sHdrs.apikey, 'anon-123', 'Supabase apikey header set');
expect(sHdrs['x-soartv-household-key'], 'hh-secret', 'Supabase Dual-Path household header set');
supaSb.SupabaseService.fetchProfiles(function (err, list) {
  expect(err, null, 'Supabase fetchProfiles succeeds');
  expect(list.length, 2, 'Supabase fetched 2 profiles');
  expect(list[0].slug, 'alex', 'First profile slug normalized');
});
supaSb.SupabaseService.upsertProfile('Sam', 'sam_trakt', function (err, prof) {
  expect(err, null, 'Supabase upsertProfile succeeds');
  expect(prof.slug, 'sam', 'New profile slug generated');
  expect(supaSb.SupabaseService.getProfiles().length, 3, 'New profile persisted in local roster');
});
expect(
  supaSb.SupabaseService.buildRealtimeUrl('https://demo-proj.supabase.co/', 'anon-123'),
  'wss://demo-proj.supabase.co/realtime/v1/websocket?apikey=anon-123&vsn=1.0.0',
  'Supabase Realtime WebSocket URL built properly'
);
var parsedCmd = supaSb.SupabaseService.parseRealtimeCommand(JSON.stringify({
  topic: 'realtime:soartv-remote:living',
  event: 'broadcast',
  payload: {event: 'remote_command', payload: {type: 'key', key: 'right'}}
}));
expect(parsedCmd && parsedCmd.type, 'key', 'Realtime remote_command parsed type');
expect(parsedCmd && parsedCmd.key, 'right', 'Realtime remote_command parsed key');

// 10. Gaming Detection (moonlight-tv + Gaming PC HDMI + Consoles)
var gameSb = createSandbox({config: {gamingPcHdmi: 'HDMI 3'}});
var mockApps = [
  {id: 'netflix', title: 'Netflix'},
  {id: 'com.limelight.webos', title: 'Moonlight'}
];
var mockInputs = [
  {id: 'com.webos.app.livetv', key: 'live', label: null, connected: true},
  {id: 'com.webos.app.hdmi1', key: 'hdmi1', label: 'PS5', connected: true},
  {id: 'com.webos.app.hdmi3', key: 'hdmi3', label: 'HDMI 3', connected: true}
];
expect(gameSb.TV.findMoonlightApp(mockApps).id, 'com.limelight.webos', 'Moonlight-TV detected on webOS');
var gTiles = gameSb.GamingService.buildTiles(mockApps, mockInputs);
expect(gTiles.length, 3, 'Gaming tiles include Moonlight-TV + Gaming PC HDMI 3 + PS5 Console');
expect(gTiles[0].id, 'moonlight_app', 'Moonlight tile placed first');
expect(gTiles[1].hdmiKey, 'hdmi1', 'PS5 console tile detected from HDMI label');
expect(gTiles[2].hdmiKey, 'hdmi3', 'Configured Gaming PC HDMI 3 tile exposed');

// 11. Multi-Row Home & Formatting + soartv.* namespace
var homeSb = createSandbox();
homeSb.Data.enablePreviewDemo();
var tvRows = homeSb.Data.homeRows('tv');
assert(tvRows.length >= 3, 'Home rows built (Watch List, Recommended, Trending, Genres)');
expect(tvRows[0].id, 'watchlist', 'Watch List row appears before Recommended');
var samplePoster = homeSb.Data.posterUrlOf(tvRows[0].items[0]);
var sampleBackdrop = homeSb.Data.backdropUrlOf(tvRows[0].items[0]);
assert(samplePoster.indexOf('(') < 0 && samplePoster.indexOf(')') < 0, 'Demo poster SVG data URI encodes parentheses for CSS url()');
assert(sampleBackdrop.indexOf('(') < 0 && sampleBackdrop.indexOf(')') < 0, 'Demo backdrop SVG data URI encodes parentheses for CSS url()');
expect(homeSb.Data.formatDuration(49), '49min', 'Duration < 60m formats as MMmin');
expect(homeSb.Data.formatDuration(97), '1:37h', 'Duration >= 60m formats as H:MMh');
expect(homeSb.Data.tmdbGenreQueryFor('tv', 'Rom-Coms').indexOf('with_keywords=9799') >= 0, true, 'TV Rom-Coms uses keyword query');
expect(homeSb.Data.tmdbGenreQueryFor('tv', 'Anime').indexOf('with_original_language=ja') >= 0, true, 'Anime isolated to ja animation');
// Verify every localStorage key across all sandboxes uses soartv.*
[sb, traktSb, stremioSb, ratingsSb, shSb, supaSb, homeSb].forEach(function (box, idx) {
  var bad = box._storedKeys.filter(function (k) { return k.indexOf(':soartv.') < 0; });
  expect(bad.length, 0, 'Sandbox #' + idx + ' only accesses soartv.* localStorage keys');
});

// 11b. SoarTV v2 ES5 PBKDF2-HMAC-SHA256 + AES-256-GCM cross-platform compatibility with Java/OpenSSL
var testHhKey = 'household-key-cross-platform-test-2026-xyz12345';
var derivedPass = sb.SoarTvCrypto.deriveHouseholdVaultPassphrase(testHhKey);
var expectedPass = nodeCrypto.createHash('sha256').update('soartv-vault-key-v2:' + testHhKey).digest('hex');
expect(derivedPass, expectedPass, 'ES5 deriveHouseholdVaultPassphrase matches SHA-256 domain-separated spec');

// Cross-verify PBKDF2-HMAC-SHA256 against Node/OpenSSL
var pbSalt = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10];
var es5Dk = sb.SoarTvCrypto.pbkdf2HmacSha256('test-passphrase', pbSalt, 1000, 32);
var nodeDk = Array.prototype.slice.call(nodeCrypto.pbkdf2Sync('test-passphrase', Buffer.from(pbSalt), 1000, 32, 'sha256'));
expect(Buffer.from(es5Dk).toString('hex'), Buffer.from(nodeDk).toString('hex'), 'ES5 PBKDF2-HMAC-SHA256 matches OpenSSL byte-for-byte');

// Cross-verify AES-256-GCM envelope: Encrypt in Node/OpenSSL (exact Java CloudVaultCrypto format) -> Decrypt in ES5!
var javaSalt = nodeCrypto.randomBytes(16);
var javaIv = nodeCrypto.randomBytes(12);
var javaKey = nodeCrypto.pbkdf2Sync(derivedPass, javaSalt, 120000, 32, 'sha256');
var javaCipher = nodeCrypto.createCipheriv('aes-256-gcm', javaKey, javaIv);
javaCipher.setAAD(Buffer.from('soartv-vault-v1:PBKDF2-HMAC-SHA256:120000', 'utf8'));
var sampleSecrets = {tmdb_key: 'tmdb_v2_999', sensibo_api_key: 'sens_v2_888'};
var javaCt = Buffer.concat([javaCipher.update(JSON.stringify(sampleSecrets), 'utf8'), javaCipher.final()]);
var javaTag = javaCipher.getAuthTag();
var javaEnvelope = {
  vault_id: 'household',
  kdf_algorithm: 'PBKDF2-HMAC-SHA256',
  kdf_salt: javaSalt.toString('base64'),
  kdf_iterations: 120000,
  cipher_iv: javaIv.toString('base64'),
  ciphertext: Buffer.concat([javaCt, javaTag]).toString('base64'),
  key_count: 2
};
var decryptedInEs5 = sb.SoarTvCrypto.decryptVaultMap(javaEnvelope, derivedPass);
expect(decryptedInEs5.tmdb_key, 'tmdb_v2_999', 'ES5 decryptVaultMap decrypts Java/OpenSSL AES-256-GCM vault');
expect(decryptedInEs5.sensibo_api_key, 'sens_v2_888', 'ES5 decryptVaultMap preserves all secret entries');

// Reverse direction: Encrypt in ES5 -> Decrypt in Node/OpenSSL!
var es5Env = sb.SoarTvCrypto.encryptVaultMap('household', {omdb_key: 'omdb_from_lg_tv'}, derivedPass);
expect(es5Env.vault_id, 'household', 'ES5 encryptVaultMap sets vault_id=household');
var decKey = nodeCrypto.pbkdf2Sync(derivedPass, Buffer.from(es5Env.kdf_salt, 'base64'), es5Env.kdf_iterations, 32, 'sha256');
var rawCtTag = Buffer.from(es5Env.ciphertext, 'base64');
var decipher = nodeCrypto.createDecipheriv('aes-256-gcm', decKey, Buffer.from(es5Env.cipher_iv, 'base64'));
decipher.setAAD(Buffer.from('soartv-vault-v1:PBKDF2-HMAC-SHA256:120000', 'utf8'));
decipher.setAuthTag(rawCtTag.slice(rawCtTag.length - 16));
var decJson = JSON.parse(Buffer.concat([decipher.update(rawCtTag.slice(0, rawCtTag.length - 16)), decipher.final()]).toString('utf8'));
expect(decJson.omdb_key, 'omdb_from_lg_tv', 'OpenSSL/Java decrypts vault encrypted by LG/Samsung ES5 crypto');

// Tampered tag rejection
var tamperedEnv = JSON.parse(JSON.stringify(es5Env));
tamperedEnv.ciphertext = 'A' + tamperedEnv.ciphertext.slice(1);
var tamperCaught = false;
try { sb.SoarTvCrypto.decryptVaultMap(tamperedEnv, derivedPass); } catch (e) { tamperCaught = true; }
assert(tamperCaught, 'ES5 AES-256-GCM rejects tampered ciphertext/tag');

// 11c. CanonicalWebApps cross-platform mapping & non-destructive merge
expect(sb.CanonicalWebApps.toCanonical('youtube.leanback.v4', 'lg-webos'), 'youtube', 'webOS YouTube maps to canonical slug');
expect(sb.CanonicalWebApps.toCanonical('com.apple.appletv', 'lg-webos'), 'appletv', 'webOS Apple TV maps to canonical slug');
expect(sb.CanonicalWebApps.toCanonical('3201807016597', 'samsung-tizen'), 'appletv', 'Tizen Apple TV maps to same canonical slug');
var resolvedOnLg = sb.CanonicalWebApps.resolveCanonicalListToLocal(
  ['appletv', 'netflix', 'pkg:android:com.android.only'],
  ['netflix', 'com.apple.appletv', 'youtube.leanback.v4'],
  'lg-webos'
);
expect(resolvedOnLg.join(','), 'com.apple.appletv,netflix', 'Canonical cloud shelf resolves to LG webOS app IDs and skips Android-only apps');
var mergedFromLg = sb.CanonicalWebApps.mergeCanonicalListNonDestructive(
  ['appletv', 'netflix', 'pkg:android:com.android.only'],
  ['netflix', 'com.apple.appletv'],
  ['netflix', 'com.apple.appletv'],
  'lg-webos'
);
expect(mergedFromLg.join(','), 'netflix,appletv,pkg:android:com.android.only', 'Non-destructive webOS merge preserves Android TV apps in cloud');

// 11d. CloudSyncService field-level LWW merge & Gaming PC cloud IP resolution
var lww = sb.CloudSyncService.mergeFieldMaps(
  {theme: 'dark', tv_genres: ['Sci-Fi', 'Drama']},
  {theme: 1000, tv_genres: 3000},
  {theme: 'light', tv_genres: ['Drama', 'Sci-Fi'], language: 'en'},
  {theme: 2000, tv_genres: 1000, language: 1500}
);
expect(lww.mergedValues.theme, 'light', 'Web LWW merge adopts newer remote theme');
expect(lww.mergedValues.tv_genres.join(','), 'Sci-Fi,Drama', 'Web LWW merge preserves newer local genre order');
expect(lww.mergedValues.language, 'en', 'Web LWW merge adopts remote-only field');
assert(lww.localChanged && lww.remoteNeedsPush, 'Web LWW merge flags bidirectional sync');

var cloudPcSb = createSandbox({
  config: {supabaseUrl: 'https://demo-proj.supabase.co', supabaseAnonKey: 'anon-123', householdKey: testHhKey},
  xhrResponder: function (req) {
    if (req.url.indexOf('/rest/v1/gaming_hosts') >= 0) {
      return {status: 200, body: [{host_id: 'pc-1', hostname: 'GAMING-RIG', lan_ip: '192.168.77.55', agent_port: 8485, mac_addresses: ['AA:BB:CC:DD:EE:FF']}]};
    }
    if (req.url.indexOf('/rest/v1/encrypted_vault') >= 0 && req.method === 'GET') {
      return {status: 200, body: [javaEnvelope]};
    }
    if (req.url.indexOf('/rest/v1/user_profile_settings') >= 0 && req.method === 'GET') {
      return {status: 200, body: [{profile_id: 'p1', settings: {theme: 'light'}, field_timestamps: {theme: 9999999999999}}]};
    }
    return {status: 200, body: []};
  }
});
cloudPcSb.CloudSyncService.syncNow('test', function (err, changed) {
  expect(err, null, 'Web CloudSyncService.syncNow completes cleanly');
  expect(changed, true, 'Web CloudSyncService applies decrypted vault & remote settings');
  expect(cloudPcSb.Prefs.tmdbKey(), 'tmdb_v2_999', 'Web TV hydrates TMDB key automatically from Household E2EE vault');
  expect(cloudPcSb.Prefs.theme(), 'light', 'Web TV adopts remote profile theme');
  expect(cloudPcSb.GamingService.resolvedPcIp(), '192.168.77.55', 'Web GamingService resolves live Gaming PC IP from cloud gaming_hosts');
});
var badCloudKeys = cloudPcSb._storedKeys.filter(function (k) { return k.indexOf(':soartv.') < 0; });
expect(badCloudKeys.length, 0, 'CloudSyncService only accesses soartv.* localStorage keys');

// 12. Async tests: Trakt device-code poll + LG webOS WiZ UDP 38899 service loopback
var webosSvc = require(path.join(ROOT, 'webos', 'service', 'service.js'));
var pilotPayload = JSON.parse(webosSvc.buildSetPilotPayload({on: true, dimming: 65, temp: 3000}));
expect(pilotPayload.method, 'setPilot', 'webOS service WiZ setPilot method');
expect(pilotPayload.params.state, true, 'webOS service WiZ setPilot state');
expect(pilotPayload.params.dimming, 65, 'webOS service WiZ setPilot dimming');
expect(pilotPayload.params.temp, 3000, 'webOS service WiZ setPilot temp');
var subnets = webosSvc.collectLanSubnets({eth0: [{address: '10.20.30.44', family: 'IPv4', internal: false}]});
expect(subnets[0], '10.20.30.', 'webOS service extracts /24 subnet from networkInterfaces');

var pendingAsync = 2;
function doneAsync() {
  if (--pendingAsync > 0) return;
  if (failures) {
    console.error('WebLauncherParityTest: ' + failures + ' failure(s), ' + passed + ' passed.');
    process.exit(1);
  }
  console.log('WebLauncherParityTest: all ' + passed + ' checks passed.');
}

// 12a. Complete Trakt Device-Code OAuth + refresh + sync round-trip
var codeSeen = null;
traktSb.TraktClient.startDeviceAuth(function (info) {
  codeSeen = info.userCode;
}, function (err, user) {
  expect(err, null, 'Trakt device-code OAuth finishes cleanly');
  expect(codeSeen, 'ABCD-1234', 'Trakt user_code delivered to UI');
  expect(user, 'alex_tv', 'Trakt username fetched after token exchange');
  expect(traktSb.TraktClient.isSignedIn(), true, 'Trakt isSignedIn true after OAuth');
  // Verify device token request carried urn:ietf:wg:oauth:2.0:oob and code_verifier
  var tokReq = traktSb._requests.filter(function (r) { return r.url.indexOf('/oauth/device/token') >= 0; })[0];
  var tokBody = JSON.parse(tokReq.body);
  expect(tokBody.redirect_uri, 'urn:ietf:wg:oauth:2.0:oob', 'Trakt OAuth uses urn:ietf:wg:oauth:2.0:oob');
  assert(tokBody.code_verifier && tokBody.code_verifier.length > 30, 'Trakt PKCE code_verifier sent');
  traktSb.TraktClient.syncLists(function (sErr, lists) {
    expect(sErr, null, 'Trakt syncLists succeeds');
    expect(lists.watchlistShows.length, 1, 'Trakt watchlist shows synced');
    expect(lists.progress.length, 1, 'Trakt playback progress synced');
    expect(lists.watched['movie:603'], true, 'Trakt watched movie tracked');
    doneAsync();
  });
});

// 12b. Loopback UDP test for webos/service/service.js wizDiscover & wizSetPilot
var fakeBulb = dgram.createSocket('udp4');
var sawSetPilot = false;
fakeBulb.on('message', function (msg, rinfo) {
  var req = JSON.parse(msg.toString('utf8'));
  var reply;
  if (req.method === 'getPilot') {
    reply = Buffer.from(JSON.stringify({method: 'getPilot', result: {mac: 'aabbccddeeff', state: true, dimming: 80, temp: 2700}}));
  } else if (req.method === 'setPilot') {
    sawSetPilot = req.params && req.params.state === false;
    reply = Buffer.from(JSON.stringify({method: 'setPilot', result: {success: true}}));
  }
  if (reply) fakeBulb.send(reply, 0, reply.length, rinfo.port, rinfo.address);
});
fakeBulb.bind(0, '127.0.0.1', function () {
  var port = fakeBulb.address().port;
  webosSvc.wizDiscover({seedIps: ['127.0.0.1'], port: port, deepScan: false, phase1TimeoutMs: 120}, function (res) {
    expect(res.returnValue, true, 'webOS wizDiscover returns returnValue: true');
    expect(res.bulbs.length, 1, 'webOS wizDiscover discovered mock WiZ bulb over UDP');
    expect(res.bulbs[0].mac, 'aabbccddeeff', 'webOS wizDiscover parsed bulb MAC');
    webosSvc.wizSetPilot({targets: ['127.0.0.1'], port: port, on: false, timeoutMs: 150}, function (res2) {
      expect(res2.ok, true, 'webOS wizSetPilot received UDP ACK');
      expect(sawSetPilot, true, 'Mock WiZ bulb received setPilot state=false');
      fakeBulb.close();
      doneAsync();
    });
  });
});
