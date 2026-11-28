/*
 * Preferences, Weather, and Multi-Row Media Discovery Engine for LG webOS & Samsung Tizen.
 *
 * Parity with Android TV Recs.java:
 *  - Multi-row Home: Continue Watching, Watch List (Trakt + Stremio Library + Supabase + local),
 *    Recommended (personal matchScore), Trending, and reorderable Genre Rows (strict Anime isolation
 *    and TV Sci-Fi vs Fantasy separation).
 *  - On-focus TMDB detail hydration (runtime, dates, genres, en-US overview fallback) + external
 *    critic ratings (MDBList / OMDb / Cinemeta) in strict IMDb -> Metacritic -> Rotten Tomatoes order.
 *  - Full backwards compatibility with all legacy Prefs and Data methods (tested by WebPrefsTest.js).
 *  - Strict ES5 (XMLHttpRequest only; all localStorage keys strictly under soartv.* namespace).
 */
var CONFIG = window.CONFIG || {};

I18N.dateNames = function (kind) {
  if (kind === 'months') {
    return [tr('monthday|January'), tr('monthday|February'), tr('monthday|March'), tr('monthday|April'),
      tr('monthday|May'), tr('monthday|June'), tr('monthday|July'), tr('monthday|August'), tr('monthday|September'),
      tr('monthday|October'), tr('monthday|November'), tr('monthday|December')];
  }
  if (kind === 'shortMonths') {
    return [tr('month|Jan'), tr('month|Feb'), tr('month|Mar'), tr('month|Apr'), tr('month|May'), tr('month|Jun'),
      tr('month|Jul'), tr('month|Aug'), tr('month|Sep'), tr('month|Oct'), tr('month|Nov'), tr('month|Dec')];
  }
  if (kind === 'weekdays') {
    return [tr('weekday|Sunday'), tr('weekday|Monday'), tr('weekday|Tuesday'), tr('weekday|Wednesday'),
      tr('weekday|Thursday'), tr('weekday|Friday'), tr('weekday|Saturday')];
  }
  return [tr('weekday|Sun'), tr('weekday|Mon'), tr('weekday|Tue'), tr('weekday|Wed'), tr('weekday|Thu'),
    tr('weekday|Fri'), tr('weekday|Sat')];
};

var Place = (function () {
  'use strict';
  var EURO = /^(AT|BE|BG|CY|DE|EE|ES|FI|FR|GR|HR|IE|IT|LT|LU|LV|MT|NL|PT|SI|SK)$/;

  function zone(name) {
    var all = window.ZONES || {}, v = all[name], hops = 0;
    while (v && v.charAt(0) === '=' && hops++ < 3) { name = v.slice(1); v = all[name]; }
    if (!v) return null;
    var p = v.split(',');
    return {country: p[0], lat: p[1], lon: p[2], name: p[3] || name.slice(name.lastIndexOf('/') + 1).replace(/_/g, ' ')};
  }

  var tz = zone(TV.zone()), set = CONFIG.latitude !== '' && CONFIG.latitude !== undefined;
  var country = (CONFIG.tmdbRegion || (tz ? tz.country : '') || 'US').toUpperCase();
  return {
    lat: set ? CONFIG.latitude : tz ? tz.lat : '40.7128',
    lon: set ? CONFIG.longitude : tz ? tz.lon : '-74.0060',
    city: CONFIG.city || (!set && tz ? tz.name : ''),
    country: country,
    currency: (CONFIG.currency || (country === 'BR' ? 'BRL' : EURO.test(country) ? 'EUR' : 'USD')).toUpperCase(),
    fahrenheit: tz ? tz.country === 'US' : /-US$/i.test(navigator.language || '')
  };
})();

var Prefs = (function () {
  'use strict';
  var KEY = 'soartv.prefs';
  var v = {};
  var changeListeners = [];
  try { v = JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) {}
  function save() { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {} }
  function emitChange(name) {
    for (var i = 0; i < changeListeners.length; i++) {
      try { changeListeners[i](name); } catch (e) {}
    }
  }
  function pick(name, fallback) { return v[name] === undefined ? fallback : v[name]; }
  function str(name, cfgKey, defVal) {
    var val = pick(name, (CONFIG && CONFIG[cfgKey]) || defVal || '');
    return String(val === undefined || val === null ? '' : val).replace(/^\s+|\s+$/g, '');
  }
  function modeOf(name, cfgKey) {
    var raw = String(pick(name, (CONFIG && CONFIG[cfgKey]) || 'auto')).toLowerCase().replace(/^\s+|\s+$/g, '');
    return raw === 'true' || raw === 'false' ? raw : 'auto';
  }
  return {
    version: 0,
    language: function () { return pick('language', CONFIG.language || 'auto'); },
    temperature: function () { return pick('temperature', CONFIG.temperature || 'auto'); },
    theme: function () { return pick('theme', CONFIG.theme || 'dark'); },
    weather: function () { return pick('weather', true); },
    saverWatch: function () { return pick('saverWatch', false) === true; },
    saver: function () { return pick('saver', 'photos') === 'clock' ? 'clock' : 'photos'; },
    dim: function () { return pick('dim', 'off'); },
    forecast: function () { return pick('forecast', 'hours') === 'days' ? 'days' : 'hours'; },
    cards: function () { var n = pick('cards', 3); return n < 3 ? 3 : n > 6 ? 6 : n; },
    hideAnimation: function () { return pick('hideAnimation', CONFIG.hideAnimation === true) === true; },
    shelf: function () {
      if (pick('tvOverrideShelf', false) === true) {
        var ov = pick('tvShelfOverride', null);
        if (Array.isArray(ov)) return ov;
      }
      return pick('shelf', null);
    },
    profileShelf: function () { return pick('shelf', null); },
    tvOverrideShelfList: function () { return pick('tvShelfOverride', null); },
    tvOverrideShelf: function () { return pick('tvOverrideShelf', false) === true; },
    tvOverrideHiddenApps: function () { return pick('tvOverrideHiddenApps', false) === true; },
    tvOverrideScreensaver: function () { return pick('tvOverrideScreensaver', false) === true; },
    useHouseholdStremio: function () { return pick('useHouseholdStremio', false) === true; },
    deviceName: function () { return str('deviceName', 'deviceName', ''); },
    hiddenApps: function () {
      var key = pick('tvOverrideHiddenApps', false) === true ? 'tvHiddenAppsOverride' : 'hiddenApps';
      var a = pick(key, null);
      return Array.isArray(a) ? a : [];
    },
    shelfConfig: function () { return pick('shelfConfig', ''); },
    city: function () { return pick('city', Place.city); },
    latitude: function () { return pick('latitude', Place.lat); },
    longitude: function () { return pick('longitude', Place.lon); },
    tmdbKey: function () { return String(pick('tmdbKey', CONFIG.tmdbKey || '')).replace(/^\s+|\s+$/g, ''); },
    tmdbKeyOnTV: function () { return v.tmdbKey !== undefined; },
    setCity: function (name, lat, lon) { v.city = name; v.latitude = lat; v.longitude = lon; save(); this.version++; emitChange('city'); },
    serviceOff: function (key) { return pick('servicesOff', CONFIG.hideServices || []).indexOf(key) >= 0; },
    setServiceOff: function (key, off) {
      var list = pick('servicesOff', CONFIG.hideServices || []).filter(function (k) { return k !== key; });
      if (off) list.push(key);
      this.set('servicesOff', list);
    },
    openPicks: function () { return pick('openPicks', CONFIG.openPicksIn === 'nuvio' ? 'nuvio' : (CONFIG.openInStremio ? 'stremio' : 'apps')); },
    boxInput: function () {
      var k = String(pick('boxInput', 'live')).toLowerCase();
      return /^hdmi[1-4]$/.test(k) ? k : 'live';
    },
    liveInput: function () { return pick('liveInput', true) !== false; },
    inputsOrder: function () { var a = pick('inputsOrder', null); return Array.isArray(a) ? a : null; },
    inputsHidden: function () {
      var a = pick('inputsHidden', null);
      return Array.isArray(a) ? a : (this.liveInput() ? [] : ['live']);
    },
    homeReturns: function () { return pick('homeReturns', CONFIG.homeButton === true) === true; },
    clockFormat: function () {
      var c = String(pick('clockFormat', CONFIG.clockFormat));
      return c === '24' || c === '12' ? c : 'auto';
    },

    // FeatureTabs tri-state modes ('auto' | 'true' | 'false')
    gamingMode: function () { return modeOf('gamingMode', 'enableGaming'); },
    smartHomeMode: function () { return modeOf('smartHomeMode', 'enableSmartHome'); },

    // Discovery, ratings & AI keys
    omdbKey: function () { return str('omdbKey', 'omdbKey', ''); },
    mdblistKey: function () { return str('mdblistKey', 'mdblistKey', ''); },
    geminiKey: function () { return str('geminiKey', 'geminiKey', ''); },
    traktClientId: function () { return str('traktClientId', 'traktClientId', ''); },

    // Smart-Home settings
    sensiboKey: function () { return str('sensiboKey', 'sensiboApiKey', ''); },
    sensiboPodId: function () { return str('sensiboPodId', 'sensiboDeviceId', ''); },
    tuyaAccessId: function () { return str('tuyaAccessId', 'tuyaAccessId', ''); },
    tuyaAccessSecret: function () { return str('tuyaAccessSecret', 'tuyaAccessSecret', ''); },
    tuyaDeviceId: function () { return str('tuyaDeviceId', 'tuyaDeviceId', ''); },
    tuyaRegion: function () {
      var r = str('tuyaRegion', 'tuyaRegion', 'eu').toLowerCase();
      return (r === 'eu' || r === 'we' || r === 'us' || r === 'in') ? r : 'eu';
    },
    wizSeedIps: function () { return str('wizSeedIps', 'wizLightIps', ''); },
    tvRoom: function () { return str('tvRoom', 'tvRoom', 'Living Room') || 'Living Room'; },

    // Supabase & Cloud Remote settings
    supabaseUrl: function () { return str('supabaseUrl', 'supabaseUrl', ''); },
    supabaseAnonKey: function () { return str('supabaseAnonKey', 'supabaseAnonKey', ''); },
    supabaseHouseholdKey: function () { return str('supabaseHouseholdKey', 'householdKey', ''); },
    cloudRemoteEnabled: function () { return pick('cloudRemoteEnabled', true) !== false; },
    cloudRemoteChannel: function () { return str('cloudRemoteChannel', 'cloudRemoteChannel', ''); },

    // Gaming settings
    gamingHdmi: function () {
      var k = str('gamingHdmi', 'gamingPcHdmi', '').toLowerCase().replace(/\s+/g, '');
      return /^hdmi[1-4]$/.test(k) ? k : '';
    },
    gamingPcIp: function () { return str('gamingPcIp', 'gamingPcIp', ''); },
    gamingPcMac: function () { return str('gamingPcMac', 'gamingPcMac', ''); },

    // Home browsing state
    subTab: function () { return pick('subTab', 'tv') === 'movie' ? 'movie' : 'tv'; },
    hiddenGenres: function (kind) {
      var a = pick('hiddenGenres_' + (kind === 'movie' ? 'movie' : 'tv'), null);
      return Array.isArray(a) ? a : [];
    },
    genreOrder: function (kind) {
      var a = pick('genreOrder_' + (kind === 'movie' ? 'movie' : 'tv'), null);
      return Array.isArray(a) ? a : null;
    },

    set: function (name, value) {
      if (name === 'shelf' && pick('tvOverrideShelf', false) === true) {
        v.tvShelfOverride = value;
        save();
        this.version++;
        emitChange('tvShelfOverride');
        return;
      }
      v[name] = value;
      save();
      this.version++;
      emitChange(name);
    },
    setSilent: function (name, value) {
      if (value === undefined) delete v[name];
      else v[name] = value;
      save();
      this.version++;
    },
    rawSnapshot: function () {
      var copy = {}, k;
      for (k in v) if (Object.prototype.hasOwnProperty.call(v, k)) copy[k] = v[k];
      return copy;
    },
    onChange: function (fn) { if (typeof fn === 'function') changeListeners.push(fn); }
  };
})();

var Data = (function () {
  'use strict';

  function store(key, value) { try { localStorage.setItem('soartv.' + key, JSON.stringify(value)); } catch (e) {} }
  function stored(key) { try { return JSON.parse(localStorage.getItem('soartv.' + key)); } catch (e) { return null; } }

  function get(url, headers, done) {
    var x = new XMLHttpRequest();
    var over = false;
    x.open('GET', url, true);
    x.timeout = 10000;
    for (var h in headers) if (Object.prototype.hasOwnProperty.call(headers, h)) x.setRequestHeader(h, headers[h]);
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (x.status !== 200) { done(new Error(x.status === 401 ? 'TMDB rejected the key' : 'HTTP ' + x.status)); return; }
      var json;
      try { json = JSON.parse(x.responseText); } catch (e) { done(e); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(new Error('no connection')); } };
    x.send();
  }

  function getMeteo(path, done) {
    get('https://' + path, {}, function (err, body) {
      if (err && /^(no connection|HTTP 0)$/.test(err.message)) {
        get('http://' + path, {}, done);
        return;
      }
      done(err, body);
    });
  }

  var listeners = [];
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  // MARK: - Weather & Air Quality

  function place() { return Prefs.latitude() + ',' + Prefs.longitude(); }
  var QUERY = '&current=temperature_2m,weather_code,is_day'
      + '&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code,uv_index_max'
      + '&minutely_15=precipitation,precipitation_probability&forecast_minutely_15=14'
      + '&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=6&forecast_hours=48';
  var weather = null, weatherAt = 0, fetchingWeather = false;
  (function () {
    var w = stored('weather');
    if (w && w.place === place()) { weather = parseWeather(w.body); weatherAt = w.query === QUERY ? w.at : 0; }
  })();

  function localTime(iso, offsetSeconds) {
    var m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)/.exec(iso || '');
    if (!m) return 0;
    return Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5]) - offsetSeconds * 1000;
  }

  function parseWeather(o) {
    if (!o || !o.current) return null;
    var off = o.utc_offset_seconds || 0, dl = o.daily, now = Date.now();
    var w = {
      temp: o.current.temperature_2m, code: o.current.weather_code, isDay: o.current.is_day !== 0,
      max: dl.temperature_2m_max[0], min: dl.temperature_2m_min[0],
      uv: dl.uv_index_max ? dl.uv_index_max[0] : null,
      sunrise: localTime(dl.sunrise[0], off), sunset: localTime(dl.sunset[0], off), hours: [], days: []
    };
    var mr = o.minutely_15;
    if (mr && mr.time) {
      w.steps = [];
      for (var k = 0; k < mr.time.length; k++) {
        var pr = mr.precipitation_probability ? mr.precipitation_probability[k] || 0 : 0;
        var mm = mr.precipitation ? mr.precipitation[k] || 0 : 0;
        w.steps.push([localTime(mr.time[k], off), pr >= 50 || mm >= 0.2]);
      }
    }
    var hr = o.hourly;
    for (var i = 0; hr && i < hr.time.length && w.hours.length < 6; i++) {
      var at = localTime(hr.time[i], off);
      if (at <= now) continue;
      w.hours.push({at: at, temp: hr.temperature_2m[i], code: hr.weather_code[i], day: !hr.is_day || hr.is_day[i] !== 0});
    }
    for (var j = 0; dl.weather_code && j < dl.time.length && w.days.length < 5; j++) {
      if (localTime(dl.time[j] + 'T12:00', off) - 12 * 3600000 <= now) continue;
      var p = dl.time[j].split('-');
      w.days.push({at: new Date(+p[0], p[1] - 1, +p[2], 12).getTime(), max: dl.temperature_2m_max[j],
        min: dl.temperature_2m_min[j], code: dl.weather_code[j]});
    }
    return w;
  }

  function rainIn(now) {
    var s = weather && weather.steps, STEP = 15 * 60000, end = now + 2 * 3600000;
    if (!s || !s.length) return null;
    for (var i = 0; i < s.length && s[i][0] < end; i++) {
      if (s[i][0] + STEP <= now) continue;
      if (s[i][1]) return Math.max(0, Math.round((s[i][0] - now) / 60000));
    }
    return s[s.length - 1][0] + STEP >= end ? -1 : null;
  }

  function refreshWeather() {
    var now = Date.now();
    if (fetchingWeather || (now - weatherAt < 30 * 60000 && now >= weatherAt)) return;
    fetchingWeather = true;
    var at = place();
    getMeteo('api.open-meteo.com/v1/forecast?latitude=' + Prefs.latitude() + '&longitude=' + Prefs.longitude() + QUERY, function (err, body) {
      fetchingWeather = false;
      if (at !== place()) { refreshWeather(); return; }
      if (err) return;
      weather = parseWeather(body);
      weatherAt = Date.now();
      store('weather', {place: at, body: body, at: weatherAt, query: QUERY});
      changed();
    });
  }

  var AIR_QUERY = 'current=us_aqi,pm2_5&timezone=auto';
  var air = null, airAt = 0, airTried = 0, fetchingAir = false;
  (function () {
    var a = stored('air');
    if (a && a.place === place() && a.query === AIR_QUERY) { air = a.body; airAt = a.at; }
  })();

  function refreshAir() {
    var now = Date.now();
    if (!Prefs.weather() || fetchingAir || (now - airAt < 30 * 60000 && now >= airAt) || (now - airTried < 5 * 60000 && now >= airTried)) return;
    airTried = now;
    fetchingAir = true;
    var at = place();
    getMeteo('air-quality-api.open-meteo.com/v1/air-quality?latitude=' + Prefs.latitude() + '&longitude='
        + Prefs.longitude() + '&' + AIR_QUERY, function (err, body) {
      fetchingAir = false;
      if (at !== place()) { refreshAir(); return; }
      if (err || !body || !body.current) return;
      air = {aqi: body.current.us_aqi, pm25: body.current.pm2_5, at: Date.now()};
      airAt = air.at;
      store('air', {place: at, body: air, at: airAt, query: AIR_QUERY});
      changed();
    });
  }

  function findCity(name, done) {
    var lang = I18N.lang.replace(/-.*/, '');
    getMeteo('geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=' + lang + '&name='
        + encodeURIComponent(name.replace(/^\s+|\s+$/g, '')), function (err, body) {
      if (err) { done(err); return; }
      var results = (body && body.results) || [], out = [], seen = {};
      for (var i = 0; i < results.length; i++) {
        var r = results[i], city = r.name || '';
        if (!city || typeof r.latitude !== 'number' || typeof r.longitude !== 'number') continue;
        var label = city, parts = [r.admin1, r.country];
        for (var j = 0; j < parts.length; j++) if (parts[j] && parts[j] !== city) label += ', ' + parts[j];
        if (seen['#' + label]) continue;
        seen['#' + label] = true;
        out.push({label: label, city: city, lat: r.latitude.toFixed(4), lon: r.longitude.toFixed(4)});
      }
      done(null, out);
    });
  }

  // MARK: - Media Discovery, Multi-Row Catalog & Ratings

  var KEY = Prefs.tmdbKey();
  var REGION = Place.country;
  var API = 'https://api.tmdb.org/3';
  var ANIMATION = 16;

  var SERVICES = [
    ['stremio', 'stremio', /stremio/i, /stremio/i, 'Stremio'],
    ['netflix', 'netflix', /^netflix$/, /netflix/i, 'Netflix'],
    ['prime video', 'prime', /^amazon$/, /prime video|amazon/i, 'Prime Video'],
    ['disney', 'disney', /disney/, /disney/i, 'Disney+'],
    ['hulu', 'hulu', /^hulu/, /hulu/i, 'Hulu'],
    ['paramount', 'paramount', /paramount|cbs/, /paramount/i, 'Paramount+'],
    ['peacock', 'peacock', /peacock/, /peacock/i, 'Peacock'],
    ['globoplay', 'globoplay', /globoplay/, /globoplay/i, 'Globoplay'],
    ['max', 'max', /hbo|wbd|\.max/, /^(hbo )?max$|hbo max/i, 'Max'],
    ['apple tv', 'appletv', /appletv|apple\.tv/, /apple tv/i, 'Apple TV'],
    ['hotstar', 'hotstar', /hotstar/, /hotstar/i, 'JioHotstar'],
    ['crunchyroll', 'crunchyroll', /crunchyroll/, /crunchyroll/i, 'Crunchyroll'],
    ['now tv', 'now', /nowtv|now\.tv/, /^now( tv)?$/i, 'NOW']
  ];

  /** Default genre row order matching Android TV Recs.java. */
  var DEFAULT_GENRES = [
    'Comedy', 'Rom-Coms', 'Thriller', 'Action', 'Drama', 'Sci-Fi', 'Crime', 'Adventure',
    'Animation', 'Anime', 'Horror', 'Mystery', 'Fantasy', 'Documentary', 'Family', 'K-Drama'
  ];

  var GENRE_ID_MAP = {
    28: 'Action', 12: 'Adventure', 16: 'Animation', 35: 'Comedy', 80: 'Crime', 99: 'Documentary',
    18: 'Drama', 10751: 'Family', 14: 'Fantasy', 36: 'History', 27: 'Horror', 10402: 'Music',
    9648: 'Mystery', 10749: 'Romance', 878: 'Sci-Fi', 53: 'Thriller', 10752: 'War & Politics',
    37: 'Western', 10759: 'Action', 10765: 'Sci-Fi', 10768: 'War & Politics', 10764: 'Reality', 10767: 'Stand-Up & Talk'
  };

  /** Exact TMDB genre discovery query strings ported from Recs.tmdbGenreQueryFor(). */
  function tmdbGenreQueryFor(kind, genreName) {
    var tv = kind === 'tv';
    switch (genreName) {
      case 'Action': return (tv ? 'with_genres=10759' : 'with_genres=28') + '&without_genres=16';
      case 'Adventure': return (tv ? 'with_genres=10759' : 'with_genres=12') + '&without_genres=16';
      case 'Comedy': return 'with_genres=35&without_genres=16';
      case 'Rom-Coms': return (tv ? 'with_genres=35&with_keywords=9799|9840|9673' : 'with_genres=35,10749') + '&with_original_language=en|he&without_genres=16';
      case 'Thriller': return (tv ? 'with_genres=80,9648' : 'with_genres=53') + '&without_genres=16';
      case 'Drama': return 'with_genres=18&without_genres=16';
      case 'Sci-Fi': return (tv ? 'with_genres=10765&with_keywords=9882|4565|310|1612|9951|4379|1430' : 'with_genres=878') + '&without_genres=16';
      case 'Fantasy': return (tv ? 'with_genres=10765&with_keywords=818|2343|12554|170362|6152|9715|10988' : 'with_genres=14') + '&without_genres=16';
      case 'Crime': return 'with_genres=80&without_genres=16';
      case 'Mystery': return 'with_genres=9648&without_genres=16';
      case 'Documentary': return 'with_genres=99&without_genres=16';
      case 'Animation': return 'with_genres=16&with_original_language=en|fr|es|he';
      case 'Anime': return 'with_genres=16&with_original_language=ja';
      case 'Horror': return (tv ? 'with_genres=9648,10765&with_keywords=315058|10292|12339' : 'with_genres=27') + '&without_genres=16';
      case 'Family': return 'with_genres=10751';
      case 'K-Drama': return 'with_genres=18&with_original_language=ko&without_genres=16';
      default: return 'without_genres=16';
    }
  }

  var installed = [];
  function appFor(key) {
    for (var s = 0; s < SERVICES.length; s++) {
      if (SERVICES[s][1] !== key) continue;
      for (var i = 0; i < installed.length; i++) {
        if (SERVICES[s][2].test(installed[i].id) || SERVICES[s][3].test(installed[i].title)) return installed[i];
      }
    }
    return null;
  }
  function usable(key) { return !!appFor(key) && !Prefs.serviceOff(key); }
  function services() {
    var out = [];
    for (var s = 0; s < SERVICES.length; s++) if (appFor(SERVICES[s][1])) out.push({key: SERVICES[s][1], name: SERVICES[s][4]});
    return out;
  }
  function service(providerName, providerId) {
    var n = (providerName || '').toLowerCase();
    if (n.indexOf(' channel') >= 0) return null;
    for (var i = 0; i < SERVICES.length; i++) {
      var s = SERVICES[i];
      if (s[1] === 'appletv' && providerId !== 350) continue;
      if (s[0] === 'max' ? /\bmax\b/.test(n) : n.indexOf(s[0]) >= 0) return s;
    }
    return null;
  }

  function tmdb(path, done, key) {
    key = key || KEY;
    if (!key) { done(new Error('No TMDB key')); return; }
    var bearer = key.indexOf('eyJ') === 0;
    var url = API + path + (bearer ? '' : (path.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + encodeURIComponent(key));
    get(url, bearer ? {Authorization: 'Bearer ' + key, Accept: 'application/json'} : {Accept: 'application/json'}, done);
  }

  function checkKey(key, done) {
    tmdb('/configuration', function (err) {
      if (err && /rejected/.test(err.message)) err.rejected = true;
      done(err || null);
    }, key);
  }

  function lang() {
    if (I18N.lang === 'es') return /-ES\b/i.test(navigator.language || '') ? 'es-ES' : 'es-MX';
    if (I18N.lang === 'de') return 'de-DE';
    return I18N.locale().replace(/-u-.*$/, '');
  }

  function genre(name) {
    switch (name) {
      case 'Action & Adventure': return tr('genre|Action & Adventure');
      case 'Sci-Fi & Fantasy': return tr('genre|Sci-Fi & Fantasy');
      case 'War & Politics': return tr('genre|War & Politics');
      case 'Kids': return tr('genre|Kids');
      case 'News': return tr('genre|News');
      case 'Soap': return tr('genre|Soap');
      case 'Talk': return tr('genre|Talk');
      default: return name;
    }
  }

  // Local user interaction state (per active profile when SupabaseService exists)
  function profileSuffix() {
    if (typeof SupabaseService !== 'undefined' && SupabaseService.getActiveProfile) {
      return '.' + SupabaseService.getActiveProfile().slug;
    }
    return '.main';
  }
  function loadLocalMap(prefix) { return stored(prefix + profileSuffix()) || {}; }
  function saveLocalMap(prefix, obj) { store(prefix + profileSuffix(), obj); }

  function pickKey(p) {
    if (!p) return '';
    if (p.id && String(p.id).indexOf('tt') === 0) return (p.kind === 'movie' ? 'movie' : 'tv') + ':' + p.id;
    return (p.kind === 'movie' ? 'movie' : 'tv') + ':' + p.id;
  }

  function isWatched(p) {
    var k = pickKey(p), loc = loadLocalMap('watched');
    if (loc[k] === true) return true;
    if (loc[k] === false) return false;
    if (typeof TraktClient !== 'undefined') {
      var tl = TraktClient.getCachedLists();
      if (tl && tl.watched && tl.watched[k]) return true;
    }
    return !!p.watched;
  }

  function getVote(p) {
    var k = pickKey(p), loc = loadLocalMap('votes');
    if (loc[k]) return loc[k];
    if (typeof SupabaseService !== 'undefined') {
      var sv = SupabaseService.getCachedVotes();
      if (sv && sv[k]) return sv[k];
    }
    return 0;
  }

  function isOnWatchlist(p) {
    var k = pickKey(p), loc = loadLocalMap('watchlist');
    if (loc[k] && loc[k].removed) return false;
    if (loc[k] && loc[k].item) return true;
    if (p.onWatchlist) return true;
    return false;
  }

  function toggleWatched(p) {
    var k = pickKey(p), loc = loadLocalMap('watched');
    var next = !isWatched(p);
    loc[k] = next;
    saveLocalMap('watched', loc);
    p.watched = next;
    changed();
    return next;
  }

  function toggleWatchlist(p) {
    var k = pickKey(p), loc = loadLocalMap('watchlist');
    var on = isOnWatchlist(p);
    if (on) {
      loc[k] = {removed: true};
      p.onWatchlist = false;
      if (typeof SupabaseService !== 'undefined') SupabaseService.removeWatchlistItem(k);
    } else {
      loc[k] = {item: {
        id: p.id, imdbId: p.imdbId || '', kind: p.kind, title: p.title,
        poster: p.poster || '', posterUrl: p.posterUrl || '', backdrop: p.backdrop || '',
        backdropUrl: p.backdropUrl || '', overview: p.overview || '', genres: p.genres || [],
        rating: p.rating || 0, releaseDate: p.releaseDate || '', firstAirDate: p.firstAirDate || ''
      }};
      p.onWatchlist = true;
      if (typeof SupabaseService !== 'undefined') {
        SupabaseService.addWatchlistItem({
          mediaKey: k, imdbId: p.imdbId || '', kind: p.kind, title: p.title,
          posterUrl: posterUrlOf(p), backdropUrl: backdropUrlOf(p)
        });
      }
    }
    saveLocalMap('watchlist', loc);
    changed();
    return !on;
  }

  function castVote(p, vote) {
    var k = pickKey(p), loc = loadLocalMap('votes');
    var norm = vote >= 0 ? 1 : -1;
    loc[k] = norm;
    saveLocalMap('votes', loc);
    if (norm > 0) {
      var wl = loadLocalMap('watched');
      wl[k] = true;
      saveLocalMap('watched', wl);
      p.watched = true;
    }
    if (typeof SupabaseService !== 'undefined') {
      SupabaseService.castVote(k, p.kind, p.title, norm);
    }
    changed();
  }

  // Catalog storage
  var cache = stored('recs') || {};
  var picks = cache.picks || [];
  var genreBuckets = cache.genreBuckets || {tv: {}, movie: {}};
  var listAt = cache.listAt || 0, period = cache.period, listLang = cache.lang || '', stamp = cache.stamp || '';
  var fetchingRecs = false, failedAt = 0, recsError = '';
  var demoMode = false;

  function saveRecs() {
    store('recs', {
      picks: picks.slice(0, 140),
      genreBuckets: genreBuckets,
      listAt: listAt, period: period, lang: listLang, stamp: stamp
    });
  }

  function hourPeriod(d) {
    var h = d.getHours();
    return h >= 5 && h < 12 ? 0 : h >= 12 && h < 18 ? 1 : 2;
  }

  function sourceStamp() {
    var s = KEY.length + ':' + KEY.slice(-6) + ':' + REGION + (Prefs.hideAnimation() ? ':no-anim' : '');
    for (var i = 0; i < SERVICES.length; i++) s += usable(SERVICES[i][1]) ? '1' : '0';
    return s;
  }

  function normalizeTmdbPick(o, kind, explicitGenre) {
    if (!o || (!o.poster_path && !o.backdrop_path)) return null;
    var gIds = o.genre_ids || [], isAnim = false, genres = [];
    for (var i = 0; i < gIds.length; i++) {
      if (gIds[i] === ANIMATION) isAnim = true;
      var gn = GENRE_ID_MAP[gIds[i]];
      if (gn && genres.indexOf(gn) < 0) genres.push(gn);
    }
    var origLang = String(o.original_language || 'en').toLowerCase();
    var isAnime = isAnim && (origLang === 'ja' || origLang === 'zh' || origLang === 'ko');
    if (isAnime) {
      genres = ['Anime', 'Animation'];
    } else if (explicitGenre && genres.indexOf(explicitGenre) < 0) {
      genres.unshift(explicitGenre);
    }
    if (Prefs.hideAnimation() && isAnim && explicitGenre !== 'Animation' && explicitGenre !== 'Anime') return null;
    var voteAvg = typeof o.vote_average === 'number' ? o.vote_average : 0;
    var voteCnt = typeof o.vote_count === 'number' ? o.vote_count : 0;
    var pop = typeof o.popularity === 'number' ? o.popularity : 10;
    // Bayesian weighted score (m=250, C=6.8 matching Recs.java)
    var bayes = ((voteCnt / (voteCnt + 250)) * voteAvg) + ((250 / (voteCnt + 250)) * 6.8);
    return {
      id: o.id,
      imdbId: o.imdb_id || '',
      kind: kind,
      title: kind === 'movie' ? (o.title || o.original_title || '') : (o.name || o.original_name || ''),
      backdrop: o.backdrop_path || '',
      poster: o.poster_path || '',
      overview: o.overview || '',
      genres: genres,
      lang: origLang,
      isAnime: isAnime,
      rating: voteAvg,
      votesCount: voteCnt,
      popularity: pop,
      matchScore: bayes * 10 + Math.min(20, Math.log(pop + 1) * 3),
      trendingScore: pop + bayes * 4,
      releaseDate: o.release_date || '',
      firstAirDate: o.first_air_date || '',
      lastAirDate: '',
      runtime: 0,
      status: '',
      imdbScore: '',
      mcScore: '',
      rtScore: '',
      meta: genres.length ? genres.slice(0, 2).join(' · ') : '',
      app: null,
      detailed: false
    };
  }

  function each(items, fn, done) {
    var i = 0;
    (function next() {
      if (i >= items.length) { done(); return; }
      fn(items[i++], next);
    })();
  }

  function fetchList(done) {
    var l = lang();
    var allList = [], seen = {};
    var nextBuckets = {tv: {}, movie: {}};
    var common = '&language=' + l + '&include_adult=false&sort_by=popularity.desc&vote_count.gte=15';

    function pushUnique(p) {
      if (!p) return;
      var k = pickKey(p);
      if (!seen[k]) { seen[k] = p; allList.push(p); }
    }

    each(['tv', 'movie'], function (kind, nextKind) {
      tmdb('/trending/' + kind + '/week?language=' + l, function (e1, r1) {
        var res1 = (!e1 && r1 && r1.results) || [], i, p;
        for (i = 0; i < res1.length && i < 20; i++) {
          p = normalizeTmdbPick(res1[i], kind, null);
          if (p) { p.isTrending = true; pushUnique(p); }
        }
        tmdb('/discover/' + kind + '?vote_average.gte=7.0&vote_count.gte=80' + common, function (e2, r2) {
          var res2 = (!e2 && r2 && r2.results) || [];
          for (i = 0; i < res2.length && i < 20; i++) {
            p = normalizeTmdbPick(res2[i], kind, null);
            if (p) { p.isRecommended = true; pushUnique(p); }
          }
          // Fetch top 8 visible genres for immediate multi-row Home experience
          var topGenres = visibleGenres(kind).slice(0, 8);
          each(topGenres, function (gName, nextG) {
            var gq = tmdbGenreQueryFor(kind, gName);
            tmdb('/discover/' + kind + '?' + gq + common, function (eg, rg) {
              var arr = (!eg && rg && rg.results) || [];
              var bucket = [];
              for (var j = 0; j < arr.length && bucket.length < 14; j++) {
                var gp = normalizeTmdbPick(arr[j], kind, gName);
                if (gp) { bucket.push(gp); pushUnique(gp); }
              }
              nextBuckets[kind][gName] = bucket;
              nextG();
            });
          }, nextKind);
        });
      });
    }, function () {
      if (!allList.length) { done(new Error('TMDB returned no recommendations')); return; }
      picks = allList;
      genreBuckets = nextBuckets;
      done(null);
    });
  }

  function bestBackdrop(images) {
    var list = images && images.backdrops, best = null, score = -1;
    for (var i = 0; list && i < list.length; i++) {
      var b = list[i];
      if (!b.file_path) continue;
      var s = (b.iso_639_1 === null ? 1e7 : 0) + Math.min(b.width || 0, 3840) * 100 + (b.vote_average || 0);
      if (s > score) { score = s; best = b.file_path; }
    }
    return best;
  }

  /** Hydrates a focused Pick with full TMDB metadata, en-US overview fallback, and MDBList/OMDb/Cinemeta ratings. */
  var enriching = {};
  function enrichPick(p, done) {
    if (!p) { if (done) done(null); return; }
    var k = pickKey(p);
    if (p.imdbId && typeof RatingsClient !== 'undefined') {
      var cachedR = RatingsClient.getCached(p.imdbId);
      if (cachedR) {
        if (cachedR.imdb) p.imdbScore = cachedR.imdb;
        if (cachedR.mc) p.mcScore = cachedR.mc;
        if (cachedR.rt) p.rtScore = cachedR.rt;
      }
    }
    if (p.detailed && (!p.imdbId || p.imdbScore || p.rtScore || p.mcScore)) {
      if (done) done(p);
      return;
    }
    if (enriching[k]) return;
    enriching[k] = true;

    function finishRatings() {
      if (p.imdbId && typeof RatingsClient !== 'undefined') {
        RatingsClient.fetchRatings(p.imdbId, p.kind, function (r) {
          enriching[k] = false;
          if (r) {
            if (r.imdb) p.imdbScore = r.imdb;
            if (r.mc) p.mcScore = r.mc;
            if (r.rt) p.rtScore = r.rt;
          }
          if (done) done(p);
        });
      } else {
        enriching[k] = false;
        if (done) done(p);
      }
    }

    if (!KEY || typeof p.id !== 'number') {
      p.detailed = true;
      finishRatings();
      return;
    }
    tmdb('/' + p.kind + '/' + p.id + '?language=' + lang() + '&append_to_response=external_ids,watch/providers,images&include_image_language=null,' + I18N.base + ',en',
      function (err, o) {
        if (err || !o) { finishRatings(); return; }
        var best = bestBackdrop(o.images);
        if (best) p.backdrop = best;
        if (o.poster_path) p.poster = o.poster_path;
        if (o.overview) p.overview = o.overview;
        if (o.imdb_id) p.imdbId = o.imdb_id;
        else if (o.external_ids && o.external_ids.imdb_id) p.imdbId = o.external_ids.imdb_id;
        if (p.kind === 'movie') {
          if (o.runtime > 0) p.runtime = o.runtime;
          if (o.release_date) p.releaseDate = o.release_date;
        } else {
          if (o.episode_run_time && o.episode_run_time.length && o.episode_run_time[0] > 0) p.runtime = o.episode_run_time[0];
          else if (o.last_episode_to_air && o.last_episode_to_air.runtime > 0) p.runtime = o.last_episode_to_air.runtime;
          if (o.first_air_date) p.firstAirDate = o.first_air_date;
          if (o.last_air_date) p.lastAirDate = o.last_air_date;
          p.status = o.status || '';
          p.seasonsCount = o.number_of_seasons || 0;
        }
        if (o.genres && o.genres.length) {
          p.genres = [];
          for (var g = 0; g < o.genres.length; g++) p.genres.push(genre(o.genres[g].name));
        }
        var wp = o['watch/providers'], here = wp && wp.results && wp.results[REGION], flat = here && here.flatrate;
        for (var i = 0; flat && i < flat.length && !p.app; i++) {
          var s = service(flat[i].provider_name, flat[i].provider_id);
          if (s && usable(s[1])) { p.app = s[1]; p.service = s[4]; }
        }
        p.detailed = true;
        if (!p.overview && lang().indexOf('en') !== 0) {
          tmdb('/' + p.kind + '/' + p.id + '?language=en-US', function (eEn, oEn) {
            if (!eEn && oEn && oEn.overview) p.overview = oEn.overview;
            finishRatings();
          });
        } else {
          finishRatings();
        }
      });
  }

  function refreshRecs() {
    if (!KEY || fetchingRecs) return;
    var t = Date.now(), p = hourPeriod(new Date()), st = sourceStamp();
    if (st === stamp && failedAt && t - failedAt < 10 * 60000 && t >= failedAt) return;
    var stale = st !== stamp || p !== period || t - listAt > 6 * 3600000 || t < listAt || !picks.length || listLang !== I18N.lang;
    if (!stale) return;
    fetchingRecs = true;
    stamp = st;
    fetchList(function (err) {
      fetchingRecs = false;
      if (err) { recsError = err.message; failedAt = Date.now(); }
      else { recsError = ''; failedAt = 0; listAt = Date.now(); period = p; listLang = I18N.lang; }
      saveRecs();
      changed();
    });
  }

  /** Syncs Trakt, Stremio Library, and Supabase profile watchlist/votes in the background. */
  function syncAccounts(done) {
    var pending = 0;
    function one() { if (--pending <= 0) { changed(); if (done) done(); } }
    if (typeof TraktClient !== 'undefined' && TraktClient.isSignedIn()) { pending++; TraktClient.syncLists(one); }
    if (typeof StremioClient !== 'undefined' && StremioClient.isConfigured()) { pending++; StremioClient.syncLibrary(one); }
    if (typeof SupabaseService !== 'undefined' && SupabaseService.isConfigured()) {
      pending += 2;
      SupabaseService.fetchWatchlist(one);
      SupabaseService.fetchVotes(one);
    }
    if (pending === 0 && done) done();
  }

  function visibleGenres(kind) {
    var order = Prefs.genreOrder(kind) || DEFAULT_GENRES;
    var hidden = Prefs.hiddenGenres(kind), out = [];
    for (var i = 0; i < order.length; i++) {
      if (hidden.indexOf(order[i]) < 0) out.push(order[i]);
    }
    return out;
  }

  function findPickInPool(key, imdbId) {
    for (var i = 0; i < picks.length; i++) {
      if (pickKey(picks[i]) === key || (imdbId && picks[i].imdbId === imdbId)) return picks[i];
    }
    return null;
  }

  /** Builds the complete Android-parity multi-row Home dataset for `subKind` ('tv' | 'movie'). */
  function homeRows(subKind) {
    var kind = subKind === 'movie' ? 'movie' : 'tv';
    var rows = [];
    var wlSet = {}, wlItems = [], seenWl = {};

    // 1. Continue Watching (mixed TV & Movies from Trakt + Stremio Library)
    var cw = [], seenCw = {};
    if (typeof TraktClient !== 'undefined') {
      var tl = TraktClient.getCachedLists();
      for (var ti = 0; tl && tl.progress && ti < tl.progress.length; ti++) {
        var tp = tl.progress[ti];
        if (seenCw[tp.key]) continue;
        seenCw[tp.key] = true;
        var match = findPickInPool(tp.key, tp.imdb);
        cw.push({
          id: match ? match.id : (tp.tmdb || tp.imdb || tp.key),
          imdbId: tp.imdb || (match && match.imdbId) || '',
          kind: tp.kind,
          title: (match && match.title) || tp.title || tp.key,
          poster: match ? match.poster : '',
          posterUrl: match ? '' : (tp.imdb ? 'https://images.metahub.space/poster/medium/' + tp.imdb + '/img' : ''),
          backdrop: match ? match.backdrop : '',
          backdropUrl: match ? '' : (tp.imdb ? 'https://images.metahub.space/background/large/' + tp.imdb + '/img' : ''),
          overview: match ? match.overview : '',
          genres: match ? match.genres : [],
          season: tp.season || 0,
          episode: tp.episode || 0,
          progressPct: tp.progressPct || 0,
          engaged: tp.engaged || 0,
          imdbScore: match ? match.imdbScore : '',
          mcScore: match ? match.mcScore : '',
          rtScore: match ? match.rtScore : '',
          isContinue: true
        });
      }
    }
    if (typeof StremioClient !== 'undefined') {
      var sl = StremioClient.getCachedLibrary();
      for (var si = 0; si < sl.length; si++) {
        var it = sl[si];
        var sk = it.kind + ':' + it.imdbId;
        if (it.hasContinue && !seenCw[sk]) {
          seenCw[sk] = true;
          cw.push({
            id: it.imdbId, imdbId: it.imdbId, kind: it.kind, title: it.title,
            posterUrl: it.posterUrl, backdropUrl: it.backdropUrl,
            season: it.season, episode: it.episode, progressPct: it.progressPct,
            engaged: it.engaged, genres: [], overview: '', isContinue: true
          });
        }
      }
    }
    if (cw.length) {
      cw.sort(function (a, b) { return (b.engaged || 0) - (a.engaged || 0); });
      rows.push({id: 'continue', kind: 'continue', title: tr('Continue Watching'), items: cw});
    }

    // 2. Watch List (merged local + Supabase + Stremio Library + Trakt, filtered to `kind`)
    function addWlItem(item) {
      if (!item || item.kind !== kind) return;
      var k = pickKey(item);
      if (seenWl[k]) return;
      var loc = loadLocalMap('watchlist');
      if (loc[k] && loc[k].removed) return;
      seenWl[k] = true;
      wlSet[k] = true;
      item.onWatchlist = true;
      item.watched = isWatched(item);
      wlItems.push(item);
    }

    var localWl = loadLocalMap('watchlist'), lk;
    for (lk in localWl) {
      if (Object.prototype.hasOwnProperty.call(localWl, lk) && localWl[lk] && localWl[lk].item) {
        addWlItem(localWl[lk].item);
      }
    }
    for (var px = 0; px < picks.length; px++) {
      if (picks[px] && picks[px].onWatchlist) addWlItem(picks[px]);
    }
    if (typeof SupabaseService !== 'undefined') {
      var supaWl = SupabaseService.getCachedWatchlist();
      for (var wi = 0; wi < supaWl.length; wi++) {
        var sw = supaWl[wi];
        addWlItem({
          id: sw.imdb_id || sw.media_key, imdbId: sw.imdb_id || '', kind: sw.kind === 'movie' ? 'movie' : 'tv',
          title: sw.title || sw.media_key, posterUrl: sw.poster_url || '', backdropUrl: sw.backdrop_url || '',
          genres: [], overview: ''
        });
      }
    }
    if (typeof StremioClient !== 'undefined') {
      var sl2 = StremioClient.getCachedLibrary();
      for (var sj = 0; sj < sl2.length; sj++) {
        var sItem = sl2[sj];
        if (sItem.isWatchlist) {
          addWlItem({
            id: sItem.imdbId, imdbId: sItem.imdbId, kind: sItem.kind, title: sItem.title,
            posterUrl: sItem.posterUrl, backdropUrl: sItem.backdropUrl,
            watched: sItem.watched, genres: [], overview: ''
          });
        }
      }
    }
    if (typeof TraktClient !== 'undefined') {
      var tl2 = TraktClient.getCachedLists();
      var trWl = kind === 'movie' ? (tl2.watchlistMovies || []) : (tl2.watchlistShows || []);
      for (var tk = 0; tk < trWl.length; tk++) {
        var tw = trWl[tk];
        var found = findPickInPool(tw.key, tw.imdb);
        if (found) {
          addWlItem(found);
        } else if (tw.imdb) {
          addWlItem({
            id: tw.tmdb || tw.imdb, imdbId: tw.imdb, kind: kind, title: tw.title || tw.imdb,
            posterUrl: 'https://images.metahub.space/poster/medium/' + tw.imdb + '/img',
            backdropUrl: 'https://images.metahub.space/background/large/' + tw.imdb + '/img',
            genres: [], overview: ''
          });
        }
      }
    }
    // Watched items stay grayed out and sorted last in Watch List (Android parity)
    wlItems.sort(function (a, b) { return (a.watched ? 1 : 0) - (b.watched ? 1 : 0); });
    if (wlItems.length) {
      rows.push({id: 'watchlist', kind: 'watchlist', title: tr('Watch List'), items: wlItems});
    }

    // Compute genre affinity from positive/negative votes
    var genreBonus = {};
    for (var pi = 0; pi < picks.length; pi++) {
      var pv = getVote(picks[pi]);
      if (pv !== 0 && picks[pi].genres) {
        for (var gi = 0; gi < picks[pi].genres.length; gi++) {
          var gn = picks[pi].genres[gi];
          genreBonus[gn] = (genreBonus[gn] || 0) + (pv > 0 ? 7.5 : -6.5);
        }
      }
    }

    function eligibleForDiscovery(p) {
      if (!p || p.kind !== kind) return false;
      var k = pickKey(p);
      if (wlSet[k] || isWatched(p) || getVote(p) < 0) return false;
      return true;
    }

    // 3. Recommended (sorted by personal matchScore + genre affinity + Trakt recommendations boost)
    var traktRecs = {};
    if (typeof TraktClient !== 'undefined') {
      var tl3 = TraktClient.getCachedLists();
      var trList = kind === 'movie' ? (tl3.recommendedMovies || []) : (tl3.recommendedShows || []);
      for (var ri = 0; ri < trList.length; ri++) traktRecs[trList[ri].key] = true;
    }
    var recItems = [];
    for (pi = 0; pi < picks.length; pi++) {
      if (!eligibleForDiscovery(picks[pi])) continue;
      if (picks[pi].isAnime) continue;
      recItems.push(picks[pi]);
    }
    recItems.sort(function (a, b) {
      var sa = (a.matchScore || 0) + (traktRecs[pickKey(a)] ? 25 : 0);
      var sb = (b.matchScore || 0) + (traktRecs[pickKey(b)] ? 25 : 0);
      var i;
      for (i = 0; a.genres && i < a.genres.length; i++) sa += genreBonus[a.genres[i]] || 0;
      for (i = 0; b.genres && i < b.genres.length; i++) sb += genreBonus[b.genres[i]] || 0;
      return sb - sa;
    });
    if (recItems.length) {
      rows.push({
        id: 'recommended', kind: 'recommended',
        title: tr('Recommended') + ' · ' + (kind === 'tv' ? tr('TV Shows') : tr('Movies')),
        items: recItems.slice(0, 24)
      });
    }

    // 4. Trending (distinct order sorted by trendingScore)
    var trendItems = [];
    for (pi = 0; pi < picks.length; pi++) {
      if (!eligibleForDiscovery(picks[pi])) continue;
      trendItems.push(picks[pi]);
    }
    trendItems.sort(function (a, b) { return (b.trendingScore || 0) - (a.trendingScore || 0); });
    if (trendItems.length) {
      rows.push({
        id: 'trending', kind: 'trending',
        title: tr('Trending') + ' · ' + (kind === 'tv' ? tr('TV Shows') : tr('Movies')),
        items: trendItems.slice(0, 24)
      });
    }

    // 5. Reorderable Genre Rows
    var genres = visibleGenres(kind);
    var buckets = (genreBuckets && genreBuckets[kind]) || {};
    for (var gx = 0; gx < genres.length; gx++) {
      var gName = genres[gx];
      var list = [], seenG = {}, src = buckets[gName] || [];
      for (var bi = 0; bi < src.length; bi++) {
        if (eligibleForDiscovery(src[bi]) && !seenG[pickKey(src[bi])]) {
          seenG[pickKey(src[bi])] = true;
          list.push(src[bi]);
        }
      }
      if (list.length < 6) {
        for (pi = 0; pi < picks.length; pi++) {
          var cand = picks[pi];
          if (!eligibleForDiscovery(cand) || seenG[pickKey(cand)]) continue;
          if (cand.isAnime && gName !== 'Anime' && gName !== 'Animation') continue;
          if (cand.genres && cand.genres.indexOf(gName) >= 0) {
            seenG[pickKey(cand)] = true;
            list.push(cand);
          }
        }
      }
      if (list.length) {
        rows.push({id: 'genre_' + gName, kind: 'genre', genre: gName, title: gName, items: list.slice(0, 24)});
      }
    }

    return rows;
  }

  /** Legacy hour-picks accessor kept for screensaver and backwards compatibility. */
  function now(kind) {
    var list = [];
    if (!KEY && !demoMode) return list;
    for (var i = 0; i < picks.length; i++) {
      var p = picks[i];
      if (kind === null || kind === p.kind) list.push(p);
    }
    return list.slice(0, Math.max(Prefs.cards(), 12));
  }

  function all() {
    return picks.slice(0, 24);
  }

  /** Searches TMDB /search/multi (or keyless Cinemeta when no TMDB key is configured). */
  function searchMedia(query, done) {
    var q = String(query || '').replace(/^\s+|\s+$/g, '');
    if (!q) { done([]); return; }
    if (!KEY) {
      if (typeof GeminiSearchClient !== 'undefined') GeminiSearchClient.searchCinemeta(q, done);
      else done([]);
      return;
    }
    tmdb('/search/multi?language=' + lang() + '&include_adult=false&query=' + encodeURIComponent(q), function (err, data) {
      var res = (!err && data && data.results) || [], out = [];
      for (var i = 0; i < res.length && out.length < 16; i++) {
        var r = res[i];
        if (r.media_type !== 'movie' && r.media_type !== 'tv') continue;
        var p = normalizeTmdbPick(r, r.media_type, null);
        if (p) out.push(p);
      }
      done(out);
    });
  }

  /** Formats runtime minutes matching Android TV: "49min" when < 60, "1:37h" when >= 60. */
  function formatDuration(mins) {
    var m = parseInt(mins, 10);
    if (!m || m <= 0) return '';
    if (m < 60) return m + 'min';
    var h = Math.floor(m / 60), rem = m % 60;
    return h + ':' + (rem < 10 ? '0' : '') + rem + 'h';
  }

  /** Formats release/air dates matching Android TV Top Details Header Row 2. */
  function formatDates(p) {
    if (!p) return '';
    var curYear = new Date().getFullYear();
    var months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    function fmtOne(iso) {
      var m = /^(\d{4})-(\d{2})/.exec(iso || '');
      if (!m) return '';
      var yr = parseInt(m[1], 10), mo = parseInt(m[2], 10) - 1;
      return yr === curYear && mo >= 0 && mo < 12 ? (months[mo] + ' ' + yr) : String(yr);
    }
    if (p.kind === 'movie') return fmtOne(p.releaseDate);
    var first = (p.firstAirDate || '').slice(0, 4);
    var last = (p.lastAirDate || '').slice(0, 4);
    if (!first) return '';
    if (p.status === 'Returning Series') return first + '-Now';
    if (last && last !== first) return first + '-' + last;
    return fmtOne(p.firstAirDate);
  }

  function image(path, size) {
    if (!path) return '';
    if (path.indexOf('http://') === 0 || path.indexOf('https://') === 0 || path.indexOf('data:') === 0) return path;
    return 'https://image.tmdb.org/t/p/' + (size || 'w1280') + path;
  }

  function posterUrlOf(p) {
    if (!p) return '';
    if (p.posterUrl) return p.posterUrl;
    if (p.poster) return image(p.poster, 'w342');
    if (p.backdropUrl) return p.backdropUrl;
    if (p.backdrop) return image(p.backdrop, 'w780');
    return '';
  }

  function backdropUrlOf(p) {
    if (!p) return '';
    if (p.backdropUrl) return p.backdropUrl;
    if (p.backdrop) return image(p.backdrop, 'w1280');
    return '';
  }

  /** Generates crisp SVG data-URI demo posters for desktop browser preview when no TMDB key is configured. */
  function demoCardArt(title, c1, c2, wide) {
    var w = wide ? 960 : 300, h = wide ? 540 : 450;
    var safe = String(title).replace(/&/g, '&amp;').replace(/</g, '&lt;');
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '">' +
      '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="' + c1 + '"/>' +
      '<stop offset="100%" stop-color="' + c2 + '"/></linearGradient></defs>' +
      '<rect width="100%" height="100%" fill="url(#g)"/>' +
      '<circle cx="' + Math.round(w * 0.78) + '" cy="' + Math.round(h * 0.28) + '" r="' + Math.round(h * 0.34) + '" fill="rgba(255,255,255,0.12)"/>' +
      (wide ? '' : '<text x="24" y="' + (h - 38) + '" font-family="Inter,Arial,sans-serif" font-size="28" font-weight="700" fill="#FFFFFF">' + safe + '</text>') +
      '</svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg).replace(/\(/g, '%28').replace(/\)/g, '%29');
  }

  /** Populates offline browser-preview sample picks (NEVER called on real webOS or Tizen TVs). */
  function enablePreviewDemo() {
    if (typeof TV !== 'undefined' && TV.name !== 'browser') return;
    if (picks.length) return;
    demoMode = true;
    var samples = [
      {id: 9001, imdbId: 'tt11280740', kind: 'tv', title: 'Severance', runtime: 49, firstAirDate: '2022-02-18', status: 'Returning Series', imdbScore: '8.7', mcScore: '83', rtScore: '97%', genres: ['Sci-Fi', 'Thriller', 'Drama'], overview: 'Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.', c1: '#1d4ed8', c2: '#0f172a', onWatchlist: true, isRecommended: true, isTrending: true},
      {id: 9002, imdbId: 'tt0903747', kind: 'tv', title: 'Better Call Saul', runtime: 47, firstAirDate: '2015-02-08', lastAirDate: '2022-08-15', status: 'Ended', imdbScore: '9.0', mcScore: '86', rtScore: '98%', genres: ['Crime', 'Drama'], overview: 'The trials and tribulations of criminal lawyer Jimmy McGill in the years leading up to his fateful run-in with Walter White.', c1: '#b91c1c', c2: '#1f2937', isRecommended: true, isTrending: true},
      {id: 9003, imdbId: 'tt14269590', kind: 'tv', title: 'Silo', runtime: 52, firstAirDate: '2023-05-05', status: 'Returning Series', imdbScore: '8.1', mcScore: '75', rtScore: '88%', genres: ['Sci-Fi', 'Mystery', 'Drama'], overview: 'In a ruined and toxic future, thousands live in a giant silo deep underground with strict regulations.', c1: '#0f766e', c2: '#111827', isRecommended: true, isTrending: true},
      {id: 9004, imdbId: 'tt10986410', kind: 'tv', title: 'Ted Lasso', runtime: 34, firstAirDate: '2020-08-14', lastAirDate: '2023-05-31', status: 'Ended', imdbScore: '8.8', mcScore: '76', rtScore: '90%', genres: ['Comedy', 'Drama'], overview: 'American college football coach Ted Lasso heads to London to manage AFC Richmond despite having no soccer experience.', c1: '#0284c7', c2: '#1e1b4b', isRecommended: true, isTrending: true},
      {id: 9005, imdbId: 'tt13406094', kind: 'tv', title: 'The White Lotus', runtime: 58, firstAirDate: '2021-07-11', status: 'Returning Series', imdbScore: '8.0', mcScore: '82', rtScore: '92%', genres: ['Comedy', 'Drama', 'Mystery'], overview: 'The exploits of various guests and employees at an exclusive tropical resort over the span of a week.', c1: '#c2410c', c2: '#312e81', isRecommended: true, isTrending: true},
      {id: 9006, imdbId: 'tt1160419', kind: 'movie', title: 'Dune: Part Two', runtime: 166, releaseDate: '2024-03-01', imdbScore: '8.5', mcScore: '79', rtScore: '92%', genres: ['Sci-Fi', 'Adventure', 'Action'], overview: 'Paul Atreides unites with the Fremen while on a warpath of revenge against the conspirators who destroyed his family.', c1: '#d97706', c2: '#27272a', onWatchlist: true, isRecommended: true, isTrending: true},
      {id: 9007, imdbId: 'tt15398776', kind: 'movie', title: 'Oppenheimer', runtime: 180, releaseDate: '2023-07-21', imdbScore: '8.3', mcScore: '90', rtScore: '93%', genres: ['Drama', 'Thriller'], overview: 'The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.', c1: '#9a3412', c2: '#18181b', isRecommended: true, isTrending: true},
      {id: 9008, imdbId: 'tt6751668', kind: 'movie', title: 'Parasite', runtime: 132, releaseDate: '2019-05-30', imdbScore: '8.5', mcScore: '96', rtScore: '99%', genres: ['Thriller', 'Comedy', 'Drama'], overview: 'Greed and class discrimination threaten the newly formed symbiotic relationship between the wealthy Park family and the destitute Kim clan.', c1: '#047857', c2: '#111827', isRecommended: true, isTrending: true}
    ];
    picks = [];
    for (var i = 0; i < samples.length; i++) {
      var s = samples[i];
      picks.push({
        id: s.id, imdbId: s.imdbId, kind: s.kind, title: s.title, runtime: s.runtime,
        releaseDate: s.releaseDate || '', firstAirDate: s.firstAirDate || '', lastAirDate: s.lastAirDate || '',
        status: s.status || '', imdbScore: s.imdbScore, mcScore: s.mcScore, rtScore: s.rtScore,
        genres: s.genres, overview: s.overview,
        posterUrl: demoCardArt(s.title, s.c1, s.c2, false),
        backdropUrl: demoCardArt(s.title, s.c1, s.c2, true),
        matchScore: 95 - i * 2, trendingScore: 90 + (i % 4) * 5,
        onWatchlist: !!s.onWatchlist, isRecommended: !!s.isRecommended, isTrending: !!s.isTrending, detailed: true
      });
    }
  }

  return {
    get city() { return Prefs.city(); },
    onChange: function (fn) { listeners.push(fn); },
    setApps: function (apps) { installed = apps; },
    appFor: appFor,
    usable: usable,
    services: services,
    refresh: function () { refreshWeather(); refreshAir(); refreshRecs(); },
    syncAccounts: syncAccounts,
    settingsChanged: function () { changed(); },
    findCity: findCity,
    cityChanged: function () { weather = null; weatherAt = 0; air = null; airAt = 0; airTried = 0; refreshWeather(); refreshAir(); changed(); },
    get weather() { return weather; },
    get air() { return air; },
    rainIn: rainIn,
    get weatherAt() { return weatherAt; },
    hasKey: function () { return !!KEY || demoMode; },
    hasRealKey: function () { return !!KEY; },
    checkKey: checkKey,
    keyChanged: function () { KEY = Prefs.tmdbKey(); recsError = ''; failedAt = 0; demoMode = false; picks = []; refreshRecs(); changed(); },
    recsError: function () { return recsError; },
    picks: now,
    showcase: all,
    homeRows: homeRows,
    enrichPick: enrichPick,
    searchMedia: searchMedia,
    pickKey: pickKey,
    isWatched: isWatched,
    isOnWatchlist: isOnWatchlist,
    getVote: getVote,
    toggleWatched: toggleWatched,
    toggleWatchlist: toggleWatchlist,
    castVote: castVote,
    formatDuration: formatDuration,
    formatDates: formatDates,
    image: image,
    posterUrlOf: posterUrlOf,
    backdropUrlOf: backdropUrlOf,
    tmdbGenreQueryFor: tmdbGenreQueryFor,
    defaultGenres: function () { return DEFAULT_GENRES.slice(); },
    enablePreviewDemo: enablePreviewDemo,

    isNight: function (at) {
      var theme = Prefs.theme();
      if (theme === 'dark') return true;
      if (theme === 'light') return false;
      var d = new Date(at), mins = d.getHours() * 60 + d.getMinutes();
      function hm(t, fallback) { if (!t) return fallback; var x = new Date(t); return x.getHours() * 60 + x.getMinutes(); }
      var rise = hm(weather && weather.sunrise, 360), set = hm(weather && weather.sunset, 1080);
      return mins < rise || mins >= set;
    },
    degrees: function (v) {
      if (v === undefined || v === null || isNaN(v)) return '—';
      var unit = Prefs.temperature();
      var f = unit === 'F' || (unit === 'auto' && Place.fahrenheit);
      return I18N.digits(Math.round(f ? v * 9 / 5 + 32 : v)) + '°';
    },
    hour: function (at) { return formatDate('format|h a', at); },
    weekday: function (at) {
      var s = formatDate('format|EEE', at).replace(/\.$/, '');
      return s.charAt(0).toUpperCase() + s.substring(1);
    },
    time: function (at) { return formatDate('format|h:mm a', at); },
    staleness: function (at, every) {
      if (!at) return '';
      var age = Date.now() - at;
      if (age < every * 2) return '';
      var m = Math.floor(age / 60000), h = Math.floor(m / 60);
      return m < 60 ? trf('{n} min ago', {n: m}) : trf('{n} h ago', {n: h});
    },
    condition: function (code, day) {
      if (code === undefined || code === null || code < 0) return '';
      if (code === 0) return day ? tr('Clear') : tr('night|Clear');
      if (code === 1) return day ? tr('Mostly Sunny') : tr('Mostly Clear');
      if (code === 2) return tr('Partly Cloudy');
      if (code === 3) return tr('Cloudy');
      if (code === 45 || code === 48) return tr('Fog');
      if (code >= 51 && code <= 57) return tr('Drizzle');
      if (code === 65 || code === 67 || code === 82) return tr('Heavy Rain');
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return tr('Snow');
      if (code >= 95) return tr('Thunderstorms');
      return tr('Rain');
    },
    symbol: function (code, day) {
      if (code <= 1) return day ? 'sunny' : 'clear_night';
      if (code === 2) return day ? 'partly_cloudy_day' : 'partly_cloudy_night';
      if (code === 3) return 'cloud';
      if (code === 45 || code === 48) return 'foggy';
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snowflake';
      if (code >= 95) return 'thunderstorm';
      return 'rainy';
    }
  };
})();
