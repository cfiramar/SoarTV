/*
 * SoarTV Web Launcher UI Controller for LG webOS (3+) and Samsung Tizen (2.3+).
 *
 * Feature Parity with Android TV HomeView + SettingsScreenRenderer + SmartHomeScreenRenderer:
 *  - Floating Top-Bar with FeatureTabs auto-hide for Gaming & Smart-Home
 *  - Multi-row Home (Continue Watching, Watch List, Recommended, Trending, Genre Rows),
 *    Top 4-Row Details Header (IMDb -> MC -> RT), and Hidden Left Favorite Apps Side-Shelf
 *  - Unified + Google Gemini AI Search
 *  - Apps 16:9 Grid with Hold-OK Favorite toggle
 *  - Gaming Hub (Moonlight-TV, Gaming PC HDMI, Consoles, optional PC status)
 *  - Smart-Home Screen (Sensibo HTTPS, Tuya OpenAPI v2.0, LG webOS WiZ UDP 38899, Routines,
 *    interactive Hold-OK Device Control Card, TV Inputs & Sources)
 *  - Two-Panel Settings (38% Left Rail / 62% Right Pane), Trakt QR Device OAuth, Stremio Sync,
 *    Supabase Multi-User Profiles & Realtime Cloud Remote
 *
 * Strict ES5: zero ES6 syntax or unsupported browser APIs, zero emojis.
 */
(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function ico(name, parent, size, cls) {
    return SoarTvIcons.el(name, parent, size || 22, cls);
  }

  function sym(name, parent, size) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = 'url(symbols/' + name + '.png)';
    s.style.maskImage = 'url(symbols/' + name + '.png)';
    if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
    return s;
  }

  function hasClass(e, c) { return !!e && (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0; }
  function setClass(e, c, on) {
    if (!e) return;
    if (on && !hasClass(e, c)) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!on && hasClass(e, c)) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  var stage = $('stage'), body = document.body;

  function fit() {
    var s = TV.name !== 'browser' ? window.innerWidth / 1920 : Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    stage.style.webkitTransform = stage.style.transform = s === 1 ? '' : 'scale(' + s + ')';
  }

  // MARK: - RTL Support

  var rtl = false;
  function direction() {
    rtl = I18N.rtl;
    setClass(body, 'rtl', rtl);
    document.documentElement.dir = rtl ? 'rtl' : 'ltr';
  }
  function startAt(node, x) {
    node.style.left = rtl ? 'auto' : x + 'px';
    node.style.right = rtl ? x + 'px' : 'auto';
  }
  function slideX(node, x) {
    node.style.webkitTransform = node.style.transform = 'translateX(' + (rtl ? -x : x) + 'px)';
  }
  function slideY(node, y) {
    node.style.webkitTransform = node.style.transform = 'translateY(' + y + 'px)';
  }

  // MARK: - App Branding & Logo Lifting

  var SHELF = [
    ['stremio', /stremio/i, /stremio/i, ['#7B5BF5', '#3B2892']],
    ['netflix', /^netflix$/, /netflix/i, ['#0A0A0A', '#000000']],
    ['youtube', /youtube/, /^youtube$/i, ['#1C1C1E', '#09090A']],
    ['prime', /^amazon$/, /prime video|amazon/i, ['#2AA8FF', '#0A68D4']],
    ['disney', /disney/, /disney/i, ['#173584', '#08143F']],
    ['max', /hbo|wbd/, /^(hbo )?max$|hbo max/i, ['#5C30E8', '#221684']],
    ['appletv', /appletv|apple\.tv/, /apple tv/i, ['#1C1C1E', '#000000']],
    ['hulu', /^hulu/, /hulu/i, ['#1CE783', '#0B8F4E']],
    ['paramount', /paramount|cbs/, /paramount/i, ['#0064FF', '#002A86']],
    ['peacock', /peacock/, /peacock/i, ['#1C1C1E', '#000000']],
    ['globoplay', /globoplay/, /globoplay/i, ['#FF8A1F', '#FF2D5A']],
    ['jellyfin', /jellyfin/i, /jellyfin/i, ['#AA5CC3', '#00A4DC']],
    ['spotify', /spotify/, /spotify/i, ['#1DB954', '#0F7A36']],
    ['moonlight', /moonlight|limelight/i, /moonlight/i, ['#2563EB', '#1E3A8A']]
  ];

  function shelfBrand(app) {
    for (var i = 0; i < SHELF.length; i++) if (SHELF[i][1].test(app.id) || SHELF[i][2].test(app.title)) return SHELF[i];
    return null;
  }

  function darken(hex, f) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#161618';
    var n = parseInt(m[1], 16), r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return 'rgb(' + Math.round(r * f) + ',' + Math.round(g * f) + ',' + Math.round(b * f) + ')';
  }

  function paintGrad(box, top, bottom) {
    box.style.background = '-webkit-linear-gradient(top, ' + top + ', ' + bottom + ')';
    box.style.background = 'linear-gradient(' + top + ', ' + bottom + ')';
  }

  function wordFit(title, width, max) {
    return Math.max(18, Math.min(max, Math.floor(width * 0.8 / (Math.max(4, String(title || '').length) * 0.56))));
  }

  function renderAppFace(app, box, wordSize) {
    var k = shelfBrand(app);
    var top = k ? k[3][0] : app.color ? darken(app.color, 0.92) : '#2A2C38';
    var bottom = k ? k[3][1] : app.color ? darken(app.color, 0.62) : '#181A24';
    paintGrad(box, top, bottom);
    var word = el('div', 'word', box, I18N.name(app.title));
    if (wordSize) word.style.fontSize = wordSize + 'px';
    if (app.icon) {
      var img = el('img', 'logo', box);
      word.style.visibility = 'hidden';
      img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); word.style.visibility = ''; };
      img.src = app.icon;
    }
  }

  // MARK: - Application State

  var apps = [], shelf = [], inputs = [];
  var homeRowsData = [], rowCols = {};
  var gamingRowsData = [], smartHomeRowsData = [], searchRowsData = [];
  var gamingPcStatus = null;
  var lastSearchQuery = '', lastAiResult = null;
  var tvAboutInfo = null;

  var Z = {
    TOP: 'top',
    HOME_SUBTAB: 'home_subtab',
    HOME_ROWS: 'home_rows',
    SIDESHELF: 'sideshelf',
    SEARCH_BAR: 'search_bar',
    SEARCH_ROWS: 'search_rows',
    APPS: 'apps',
    GAMING: 'gaming',
    SMARTHOME: 'smarthome',
    SETTINGS_LEFT: 'settings_left',
    SETTINGS_RIGHT: 'settings_right',
    MODAL_CONTEXT: 'modal_context',
    MODAL_TRAKT: 'modal_trakt',
    MODAL_INPUT: 'modal_input',
    EDIT: 'edit',
    LANG: 'lang',
    CITY: 'city',
    KEY: 'key',
    ABOUT: 'about'
  };

  var st = {
    screen: 'home',
    zone: Z.HOME_ROWS,
    prevZone: Z.HOME_ROWS,
    topIdx: 1,
    subTab: Prefs.subTab(),
    subTabFocus: 0,
    homeRow: 0,
    homeCol: 0,
    shelfIdx: 0,
    searchBarIdx: 0,
    searchRow: 0,
    searchCol: 0,
    appsIdx: 0,
    gamingRow: 0,
    gamingCol: 0,
    shRow: 0,
    shCol: 0,
    setCatIdx: 0,
    setRowIdx: 0,
    modalIdx: 0,
    editIdx: 0,
    langIdx: 0,
    cityIdx: 0,
    keyIdx: 0
  };

  function visibleScreens() {
    var gamingOn = FeatureTabs.resolveFeatureEnabled(
      Prefs.gamingMode(),
      CONFIG.enableGaming || 'auto',
      GamingService.hasGamingSetup(apps, inputs)
    );
    var shOn = FeatureTabs.resolveFeatureEnabled(
      Prefs.smartHomeMode(),
      CONFIG.enableSmartHome || 'auto',
      SmartHomeClient.hasAnyConfigured()
    );
    return FeatureTabs.buildScreenList(gamingOn, shOn);
  }

  function modalOpen() {
    return st.zone === Z.MODAL_CONTEXT || st.zone === Z.MODAL_TRAKT || st.zone === Z.MODAL_INPUT ||
      st.zone === Z.EDIT || st.zone === Z.LANG || st.zone === Z.CITY || st.zone === Z.KEY || st.zone === Z.ABOUT;
  }

  // MARK: - Top-Bar & Status Cluster

  var TAB_DEFS = {
    search: {icon: 'search', label: function () { return tr('Search'); }},
    home: {icon: 'home', label: function () { return tr('Home'); }},
    apps: {icon: 'apps', label: function () { return tr('Apps'); }},
    gaming: {icon: 'gaming', label: function () { return tr('Gaming'); }},
    smarthome: {icon: 'smarthome', label: function () { return tr('Smart-Home'); }},
    settings: {icon: 'settings', label: function () { return tr('Settings'); }}
  };

  function renderTopBar() {
    var screens = visibleScreens();
    st.screen = FeatureTabs.sanitizeScreen(st.screen, screens);
    var nav = $('nav-tabs');
    nav.innerHTML = '';
    for (var i = 0; i < screens.length; i++) {
      (function (scr, idx) {
        var def = TAB_DEFS[scr];
        var btn = el('button', 'nav-tab', nav);
        ico(def.icon, btn, 22);
        btn.appendChild(document.createTextNode(def.label()));
        setClass(btn, 'active', st.screen === scr);
        setClass(btn, 'focus', st.zone === Z.TOP && st.topIdx === idx);
        btn.onmouseover = function () {
          if (modalOpen() || saverOn || ambientOn) return;
          st.zone = Z.TOP;
          st.topIdx = idx;
          updateFocus();
        };
        btn.onclick = function () {
          if (modalOpen()) return;
          goScreen(scr, false);
        };
      })(screens[i], i);
    }

    // Profile badge
    var prof = SupabaseService.getActiveProfile();
    var pBtn = $('profile-pill');
    pBtn.innerHTML = '';
    var dot = el('span', 'avatar-dot', pBtn);
    dot.style.background = prof.avatar_color || '#38BDF8';
    pBtn.appendChild(document.createTextNode(prof.display_name || 'Main'));
    setClass(pBtn, 'focus', st.zone === Z.TOP && st.topIdx === screens.length);
    pBtn.onclick = function () {
      var next = SupabaseService.cycleProfile();
      toast(next.display_name);
      Data.syncAccounts(function () { renderHome(); });
      renderTopBar();
      if (st.screen === 'settings') renderSettings();
    };

    // Cloud Remote indicator
    var rPill = $('remote-pill');
    var rtOn = SupabaseService.isConfigured() && Prefs.cloudRemoteEnabled();
    setClass(rPill, 'hidden', !rtOn);
    if (rtOn) {
      rPill.innerHTML = '';
      el('span', SupabaseService.isRemoteConnected() ? 'dot-ok' : 'dot-warn', rPill);
      rPill.appendChild(document.createTextNode(SupabaseService.isRemoteConnected() ? tr('Connected') : tr('Connecting…')));
    }

    // Weather pill (shows current temperature & Rain/UV/AQI alert when Weather widget is enabled)
    var wPill = $('weather-pill');
    var w = Prefs.weather() ? Data.weather : null;
    setClass(wPill, 'hidden', !w);
    if (w) {
      wPill.innerHTML = '';
      sym(Data.symbol(w.code, w.isDay), wPill, 20);
      var txt = ' ' + Data.degrees(w.temp);
      var extra = weatherExtraSummary(w);
      if (extra) txt += ' · ' + extra;
      wPill.appendChild(document.createTextNode(txt));
    }

    renderClock();
  }

  function weatherExtraSummary(w) {
    if (!w) return '';
    var parts = [], rain = Data.rainIn(Date.now());
    if (rain > 0) parts.push(trf('Rain in ~{time}', {time: trf('{n} min', {n: Math.max(5, Math.round(rain / 5) * 5)})}));
    if (w.uv >= 3 && w.isDay) parts.push(trf('UV {n}', {n: Math.round(w.uv)}));
    var air = Data.air;
    if (air && air.aqi !== null && air.aqi !== undefined && air.aqi >= 51) parts.push(trf('AQI {n}', {n: Math.round(air.aqi)}));
    return parts.slice(0, 1).join(' · ');
  }

  function renderClock() {
    var d = new Date();
    var tStr = formatDate('format|h:mm', d.getTime());
    $('top-clock').textContent = tStr;
    var clk = $('clock');
    if (clk && clk.children.length >= 2) {
      clk.children[0].textContent = tStr;
      clk.children[1].textContent = formatDate('format|EEEE, MMMM d', d.getTime());
      var sub = Data.city ? (Data.city + (Data.weather ? ' · ' + Data.degrees(Data.weather.temp) + ' ' + Data.condition(Data.weather.code, Data.weather.isDay) : '')) : tr('For You');
      clk.children[2].textContent = sub;
    }
  }

  function tickClock() {
    if (!resting()) renderClock();
    setTimeout(tickClock, 60000 - Date.now() % 60000 + 50);
  }

  // MARK: - Screen Switching

  function goScreen(scr, keepTopFocus) {
    var screens = visibleScreens();
    scr = FeatureTabs.sanitizeScreen(scr, screens);
    st.screen = scr;
    st.topIdx = Math.max(0, screens.indexOf(scr));
    closeSideShelf(true);
    var all = ['home', 'search', 'apps', 'gaming', 'smarthome', 'settings'];
    for (var i = 0; i < all.length; i++) {
      setClass($('screen-' + all[i]), 'active', all[i] === scr);
    }
    if (!keepTopFocus) {
      if (scr === 'home') st.zone = homeRowsData.length ? Z.HOME_ROWS : Z.TOP;
      else if (scr === 'search') st.zone = Z.SEARCH_BAR;
      else if (scr === 'apps') st.zone = Z.APPS;
      else if (scr === 'gaming') st.zone = Z.GAMING;
      else if (scr === 'smarthome') st.zone = Z.SMARTHOME;
      else if (scr === 'settings') { st.zone = Z.SETTINGS_LEFT; st.setRowIdx = 0; }
    }
    if (scr === 'home') renderHome();
    else if (scr === 'search') renderSearch();
    else if (scr === 'apps') renderAppsScreen();
    else if (scr === 'gaming') { renderGaming(); GamingService.fetchPcStatus(function (res) { gamingPcStatus = res; if (st.screen === 'gaming') renderGaming(); }); }
    else if (scr === 'smarthome') { renderSmartHome(); SmartHomeClient.refreshAll(function () { if (st.screen === 'smarthome') renderSmartHome(); }); }
    else if (scr === 'settings') renderSettings();
    renderTopBar();
    updateHeroBackdropVisibility();
    updateFocus();
    broadcastTvState();
  }

  // MARK: - Hero Backdrop Crossfade (Home Screen Only)

  var heroUrl = '', heroFront = 'heroA', heroTimer = 0;

  function updateHeroBackdropVisibility() {
    var showHero = st.screen === 'home' && homeRowsData.length > 0 && !!heroUrl;
    setClass($('hero-bg'), 'on', showHero);
  }

  function setHeroBackdrop(url) {
    if (url === heroUrl) { updateHeroBackdropVisibility(); return; }
    heroUrl = url || '';
    if (!heroUrl) {
      setClass($('heroA'), 'on', false);
      setClass($('heroB'), 'on', false);
      updateHeroBackdropVisibility();
      return;
    }
    var nextId = heroFront === 'heroA' ? 'heroB' : 'heroA';
    var nextEl = $(nextId), prevEl = $(heroFront);
    var img = new Image();
    img.onload = function () {
      if (heroUrl !== url) return;
      nextEl.style.backgroundImage = 'url("' + url + '")';
      setClass(nextEl, 'on', true);
      setClass(prevEl, 'on', false);
      heroFront = nextId;
      updateHeroBackdropVisibility();
    };
    img.src = url;
  }

  function focusedHomePick() {
    var row = homeRowsData[st.homeRow];
    if (!row || !row.items || !row.items.length) return null;
    return row.items[Math.min(st.homeCol, row.items.length - 1)] || null;
  }

  function renderHeroDetails(pick) {
    if (!pick) {
      $('hero-title').textContent = tr('For You');
      $('hero-meta').innerHTML = '';
      $('hero-genres').textContent = '';
      $('hero-overview').textContent = '';
      setClass($('hero-continue'), 'on', false);
      return;
    }
    $('hero-title').textContent = I18N.name(pick.title || '');
    var meta = $('hero-meta');
    meta.innerHTML = '';
    var segments = [];
    var dur = Data.formatDuration(pick.runtime);
    if (dur) segments.push(dur);
    var dates = Data.formatDates(pick);
    if (dates) segments.push(dates);
    if (segments.length) {
      meta.appendChild(document.createTextNode(segments.join(' · ')));
    }
    var hasRatings = !!(pick.imdbScore || pick.mcScore || pick.rtScore);
    if (hasRatings) {
      if (segments.length) meta.appendChild(document.createTextNode(' · '));
      var firstR = true;
      // Strict IMDb -> Metacritic -> Rotten Tomatoes order (space-separated, never middot between scores)
      if (pick.imdbScore) {
        var b1 = el('span', 'rating-badge' + (firstR ? ' first' : ''), meta);
        b1.textContent = 'IMDb ' + pick.imdbScore;
        firstR = false;
      }
      var mcFmt = RatingsClient.formatMetacritic(pick.mcScore);
      if (mcFmt) {
        var b2 = el('span', 'rating-badge' + (firstR ? ' first' : ''), meta);
        ico('mc', b2, 20);
        b2.appendChild(document.createTextNode(mcFmt));
        firstR = false;
      }
      if (pick.rtScore) {
        var b3 = el('span', 'rating-badge' + (firstR ? ' first' : ''), meta);
        ico('rt', b3, 20);
        b3.appendChild(document.createTextNode(pick.rtScore));
      }
    }
    $('hero-genres').textContent = (pick.genres && pick.genres.length) ? pick.genres.slice(0, 4).join(' | ') : (pick.kind === 'tv' ? tr('TV Shows') : tr('Movies'));

    var cwEl = $('hero-continue');
    if (pick.isContinue && pick.season > 0 && pick.episode > 0) {
      cwEl.textContent = tr('Continue Watching') + ' S' + pick.season + 'E' + pick.episode;
      setClass(cwEl, 'on', true);
    } else {
      setClass(cwEl, 'on', false);
    }
    $('hero-overview').textContent = pick.overview || '';
  }

  function scheduleHeroFollow() {
    clearTimeout(heroTimer);
    if (st.screen !== 'home') { updateHeroBackdropVisibility(); return; }
    var p = focusedHomePick();
    renderHeroDetails(p);
    if (!p) { setHeroBackdrop(''); return; }
    heroTimer = setTimeout(function () {
      var cur = focusedHomePick();
      if (!cur || cur !== p) return;
      setHeroBackdrop(Data.backdropUrlOf(cur));
      Data.enrichPick(cur, function (updated) {
        if (updated && focusedHomePick() === cur && st.screen === 'home') {
          renderHeroDetails(updated);
          var bUrl = Data.backdropUrlOf(updated);
          if (bUrl) setHeroBackdrop(bUrl);
        }
      });
    }, 180);
  }

  // MARK: - Home Screen (Multi-Row Portrait Cards + [TV Shows | Movies] + Side-Shelf Hint)

  function renderSubTabBar() {
    var bar = $('subtab-bar');
    bar.innerHTML = '';
    var opts = [
      {id: 'tv', label: tr('TV Shows')},
      {id: 'movie', label: tr('Movies')}
    ];
    for (var i = 0; i < opts.length; i++) {
      (function (opt, idx) {
        var b = el('button', 'subtab-pill', bar, opt.label);
        setClass(b, 'active', st.subTab === opt.id);
        setClass(b, 'focus', st.zone === Z.HOME_SUBTAB && st.subTabFocus === idx);
        b.onclick = function () { setHomeSubTab(opt.id); };
      })(opts[i], i);
    }
  }

  function setHomeSubTab(mode) {
    var next = mode === 'movie' ? 'movie' : 'tv';
    if (next === st.subTab) return;
    st.subTab = next;
    Prefs.set('subTab', next);
    st.homeRow = 0;
    st.homeCol = 0;
    rowCols = {};
    renderHome();
    updateFocus();
    broadcastTvState();
  }

  function renderHome() {
    homeRowsData = Data.homeRows(st.subTab);
    setClass(body, 'no-picks', homeRowsData.length === 0);
    var hint = $('shelf-hint');
    hint.innerHTML = '';
    ico(rtl ? 'chevron_right' : 'chevron_left', hint, 16);
    hint.onclick = function () { openSideShelf(); };

    renderSubTabBar();
    var container = $('home-rows');
    container.innerHTML = '';

    for (var r = 0; r < homeRowsData.length; r++) {
      var row = homeRowsData[r];
      var rowEl = el('div', 'media-row', container);
      el('div', 'media-row-title', rowEl, row.title);
      var track = el('div', 'media-row-track', rowEl);
      for (var c = 0; c < row.items.length; c++) {
        (function (ri, ci, item) {
          var card = el('div', 'poster-card' + (item.watched ? ' watched' : ''), track);
          startAt(card, 76 + ci * 220);
          var art = Data.posterUrlOf(item);
          if (art) card.style.backgroundImage = 'url("' + art + '")';
          else el('div', 'poster-fallback', card, I18N.name(item.title || ''));
          if (item.isContinue && (item.episode > 0 || item.progressPct > 0)) {
            var scrim = el('div', 'cw-scrim', card);
            var lbl = item.episode > 0 ? ('S' + (item.season || 1) + 'E' + item.episode) : (item.progressPct + '%');
            el('div', 'cw-label', scrim, lbl);
            if (item.progressPct > 0) {
              var bar = el('div', 'cw-bar', scrim);
              el('div', 'cw-fill', bar).style.width = Math.min(100, item.progressPct) + '%';
            }
          }
          card.onmouseover = function () {
            if (modalOpen() || isSideShelfOpen()) return;
            st.zone = Z.HOME_ROWS;
            st.homeRow = ri;
            st.homeCol = ci;
            rowCols[row.id] = ci;
            updateFocus();
          };
          card.onclick = function () {
            st.zone = Z.HOME_ROWS; st.homeRow = ri; st.homeCol = ci;
            openMediaPick(item);
          };
          card.oncontextmenu = function (ev) {
            ev.preventDefault();
            st.zone = Z.HOME_ROWS; st.homeRow = ri; st.homeCol = ci;
            openMediaContextMenu(item);
          };
        })(r, c, row.items[c]);
      }
    }

    st.homeRow = Math.min(st.homeRow, Math.max(0, homeRowsData.length - 1));
    if (homeRowsData.length) {
      st.homeCol = Math.min(st.homeCol, Math.max(0, homeRowsData[st.homeRow].items.length - 1));
    } else {
      st.homeCol = 0;
    }
    scheduleHeroFollow();
  }

  function updateHomeRowFocus() {
    var rowsWrap = $('home-rows');
    slideY(rowsWrap, -st.homeRow * 396);
    var rowEls = rowsWrap.children;
    for (var r = 0; r < rowEls.length; r++) {
      var track = rowEls[r].children[1];
      var colForRow = r === st.homeRow ? st.homeCol : (rowCols[homeRowsData[r].id] || 0);
      var scrollCols = Math.max(0, colForRow - 5);
      slideX(track, -scrollCols * 220);
      var cards = track.children;
      for (var c = 0; c < cards.length; c++) {
        setClass(cards[c], 'focus', st.zone === Z.HOME_ROWS && r === st.homeRow && c === st.homeCol);
      }
    }
  }

  // MARK: - Hidden Favorite Apps Side-Shelf

  function isSideShelfOpen() { return st.zone === Z.SIDESHELF; }

  function pickShelfApps() {
    var wanted = (CONFIG.shelfApps || []).map(function (s) { return s.toLowerCase(); });
    if (wanted.length && Prefs.shelfConfig() !== wanted.join(',')) {
      Prefs.set('shelf', null);
      Prefs.set('shelfConfig', wanted.join(','));
    }
    var chosen = Prefs.shelf(), out = [], i, j;
    if (Array.isArray(chosen)) {
      for (i = 0; i < chosen.length; i++) {
        for (j = 0; j < apps.length; j++) if (apps[j].id === chosen[i]) out.push(apps[j]);
      }
    } else if (wanted.length) {
      for (i = 0; i < wanted.length; i++) {
        for (j = 0; j < apps.length; j++) {
          if ((apps[j].id.toLowerCase().indexOf(wanted[i]) >= 0 || apps[j].title.toLowerCase().indexOf(wanted[i]) >= 0) && out.indexOf(apps[j]) < 0) {
            out.push(apps[j]); break;
          }
        }
      }
    } else {
      for (i = 0; i < SHELF.length && out.length < 8; i++) {
        for (j = 0; j < apps.length; j++) {
          if ((SHELF[i][1].test(apps[j].id) || SHELF[i][2].test(apps[j].title)) && out.indexOf(apps[j]) < 0) {
            out.push(apps[j]); break;
          }
        }
      }
    }
    if (!out.length) out = apps.slice(0, 8);
    shelf = out;
  }

  function renderSideShelf() {
    $('sideshelf-title').textContent = tr('Shelf');
    var list = $('sideshelf-list');
    list.innerHTML = '';
    for (var i = 0; i < shelf.length; i++) {
      (function (idx, app) {
        var tile = el('div', 'shelf-item', list);
        renderAppFace(app, tile, wordFit(app.title, 224, 26));
        tile.onmouseover = function () { st.shelfIdx = idx; updateFocus(); };
        tile.onclick = function () { launcher(app.id, app.title)(); closeSideShelf(); };
      })(i, shelf[i]);
    }
    var arr = $('sideshelf-arrow');
    arr.innerHTML = '';
    ico(rtl ? 'chevron_left' : 'chevron_right', arr, 20);
  }

  function openSideShelf() {
    if (!shelf.length) return;
    renderSideShelf();
    st.prevZone = st.zone;
    st.zone = Z.SIDESHELF;
    st.shelfIdx = Math.min(st.shelfIdx, Math.max(0, shelf.length - 1));
    setClass(body, 'sideshelf-open', true);
    updateFocus();
    broadcastTvState();
  }

  function closeSideShelf(silent) {
    if (!hasClass(body, 'sideshelf-open')) return;
    setClass(body, 'sideshelf-open', false);
    if (st.zone === Z.SIDESHELF) st.zone = homeRowsData.length ? Z.HOME_ROWS : Z.TOP;
    if (!silent) { updateFocus(); broadcastTvState(); }
  }

  $('sideshelf-veil').onclick = function () { closeSideShelf(); };

  // MARK: - Search Screen (Unified + Google Gemini AI)

  function renderSearch() {
    var icoWrap = $('search-ico');
    icoWrap.innerHTML = '';
    ico('search', icoWrap, 26);
    var inp = $('search-input');
    inp.placeholder = tr('Search') + ' · ' + tr('Explore') + '…';
    if (inp.value !== lastSearchQuery) inp.value = lastSearchQuery;

    var btnGo = $('search-btn-go');
    btnGo.innerHTML = '';
    ico('search', btnGo, 22);
    btnGo.appendChild(document.createTextNode(tr('Search')));
    btnGo.onclick = function () { runUnifiedSearch(inp.value, false); };

    var btnAi = $('search-btn-ai');
    btnAi.innerHTML = '';
    ico('sparkles', btnAi, 22);
    btnAi.appendChild(document.createTextNode(tr('AI Search')));
    btnAi.onclick = function () { runUnifiedSearch(inp.value, true); };

    var aiCard = $('search-ai-card');
    if (lastAiResult && lastAiResult.summary) {
      aiCard.innerHTML = '';
      el('div', 'ai-tag', aiCard, tr('AI Search') + ' · Gemini 2.5 Flash');
      el('div', '', aiCard, lastAiResult.summary);
      setClass(aiCard, 'on', true);
    } else {
      setClass(aiCard, 'on', false);
    }

    var resWrap = $('search-results');
    resWrap.innerHTML = '';
    for (var r = 0; r < searchRowsData.length; r++) {
      var row = searchRowsData[r];
      var rowEl = el('div', 'wide-row', resWrap);
      el('div', 'wide-row-title', rowEl, row.title);
      var track = el('div', 'wide-row-track', rowEl);
      for (var c = 0; c < row.items.length; c++) {
        (function (ri, ci, item) {
          var tile = el('div', 'wide-tile', track);
          startAt(tile, 76 + ci * 380);
          var box = el('div', 'tile-box', tile);
          if (item.kind === 'media') {
            var art = Data.backdropUrlOf(item.data) || Data.posterUrlOf(item.data);
            if (art) { box.style.backgroundImage = 'url("' + art + '")'; box.style.backgroundSize = 'cover'; }
            else ico('play', box, 42);
          } else if (item.kind === 'app') {
            renderAppFace(item.data, box, 26);
          } else {
            paintGrad(box, '#3B40D4', '#262994');
            ico(item.icon || 'settings', box, 44);
          }
          el('div', 'tile-title', tile, item.title);
          el('div', 'tile-sub', tile, item.subtitle || '');
          tile.onmouseover = function () { st.zone = Z.SEARCH_ROWS; st.searchRow = ri; st.searchCol = ci; updateFocus(); };
          tile.onclick = function () { activateSearchItem(item); };
        })(r, c, row.items[c]);
      }
    }
  }

  function runUnifiedSearch(rawQuery, useGemini) {
    var q = String(rawQuery || '').replace(/^\s+|\s+$/g, '');
    lastSearchQuery = q;
    $('search-input').blur();
    if (!q) { searchRowsData = []; lastAiResult = null; renderSearch(); updateFocus(); return; }
    var ql = q.toLowerCase();
    var nextRows = [];

    // 1. Local Installed Apps match
    var appHits = [];
    for (var i = 0; i < apps.length; i++) {
      if (apps[i].title.toLowerCase().indexOf(ql) >= 0) {
        appHits.push({kind: 'app', data: apps[i], title: apps[i].title, subtitle: tr('Apps')});
      }
    }
    if (appHits.length) nextRows.push({title: tr('Apps'), items: appHits.slice(0, 10)});

    // 2. Settings Tree match
    var setHits = searchSettingsItems(ql);
    if (setHits.length) nextRows.push({title: tr('Settings'), items: setHits.slice(0, 10)});

    searchRowsData = nextRows;
    lastAiResult = null;
    st.searchRow = 0;
    st.searchCol = 0;
    renderSearch();
    updateFocus();

    // 3. Gemini AI Search (if requested and configured)
    if (useGemini) {
      if (!GeminiSearchClient.isConfigured()) {
        toast('Configure Gemini API key in Settings -> Home & Discovery');
      } else {
        toast(tr('Searching…'));
        GeminiSearchClient.ask(q, function (err, ai) {
          if (err || !ai) {
            toast(err ? err.message : 'No results');
            return;
          }
          lastAiResult = ai;
          var primary = ai.primaryTitle || q;
          Data.searchMedia(primary, function (mediaHits) {
            var cards = [];
            for (var m = 0; m < mediaHits.length; m++) {
              cards.push({kind: 'media', data: mediaHits[m], title: mediaHits[m].title, subtitle: mediaHits[m].kind === 'tv' ? tr('TV Shows') : tr('Movies')});
            }
            for (var c = 0; ai.candidates && c < ai.candidates.length; c++) {
              var cand = ai.candidates[c];
              cards.push({kind: 'gemini_cand', query: cand.title, title: cand.title, subtitle: (cand.year ? cand.year + ' · ' : '') + cand.reason, icon: 'sparkles'});
            }
            if (cards.length) {
              searchRowsData = [{title: tr('AI Search'), items: cards}].concat(nextRows);
              st.zone = Z.SEARCH_ROWS;
              st.searchRow = 0;
              st.searchCol = 0;
            }
            renderSearch();
            updateFocus();
          });
        });
        return;
      }
    }

    // 4. TMDB / Cinemeta catalog search
    Data.searchMedia(q, function (mediaHits) {
      if (lastSearchQuery !== q) return;
      var mCards = [];
      for (var j = 0; j < mediaHits.length; j++) {
        mCards.push({kind: 'media', data: mediaHits[j], title: mediaHits[j].title, subtitle: (mediaHits[j].kind === 'tv' ? tr('Shows') : tr('Movies'))});
      }
      if (mCards.length) {
        searchRowsData = [{title: tr('Explore'), items: mCards}].concat(nextRows);
        st.zone = Z.SEARCH_ROWS;
        st.searchRow = 0;
        st.searchCol = 0;
        renderSearch();
        updateFocus();
      }
    });
  }

  function activateSearchItem(item) {
    if (!item) return;
    if (item.kind === 'media') openMediaPick(item.data);
    else if (item.kind === 'app') launcher(item.data.id, item.data.title)();
    else if (item.kind === 'gemini_cand') { $('search-input').value = item.query; runUnifiedSearch(item.query, false); }
    else if (item.kind === 'setting') {
      st.setCatIdx = item.catIdx;
      st.setRowIdx = item.rowIdx;
      goScreen('settings', false);
      st.zone = Z.SETTINGS_RIGHT;
      renderSettings();
      updateFocus();
    }
  }

  // MARK: - Apps Screen (5-Column 16:9 Grid, No Header Title)

  function renderAppsScreen() {
    var grid = $('apps-grid');
    grid.innerHTML = '';
    for (var i = 0; i < apps.length; i++) {
      (function (idx, app) {
        var r = Math.floor(idx / 5), c = idx % 5;
        var cell = el('div', 'app-cell', grid);
        startAt(cell, 76 + c * 362);
        cell.style.top = (16 + r * 244) + 'px';
        renderAppFace(app, el('div', 'face', cell), wordFit(app.title, 316, 30));
        el('div', 'label', cell, I18N.name(app.title));
        cell.onmouseover = function () { st.zone = Z.APPS; st.appsIdx = idx; updateFocus(); };
        cell.onclick = function () { launcher(app.id, app.title)(); };
        cell.oncontextmenu = function (ev) { ev.preventDefault(); openAppContextMenu(app); };
      })(i, apps[i]);
    }
    st.appsIdx = Math.min(st.appsIdx, Math.max(0, apps.length - 1));
  }

  // MARK: - Gaming Screen (Moonlight-TV + Gaming PC HDMI + Consoles)

  function renderGaming() {
    var pills = $('gaming-pills');
    pills.innerHTML = '';
    var ml = TV.findMoonlightApp(apps);
    var p1 = el('span', 'gaming-pill' + (ml ? ' ok' : ''), pills);
    ico('moonlight', p1, 18);
    p1.appendChild(document.createTextNode(ml ? 'Moonlight-TV Installed' : 'Moonlight-TV Not Installed'));

    var hdmi = GamingService.configuredHdmiKey();
    if (hdmi) {
      var p2 = el('span', 'gaming-pill ok', pills);
      ico('hdmi', p2, 18);
      p2.appendChild(document.createTextNode('Gaming PC · ' + hdmi.toUpperCase()));
    }
    if (gamingPcStatus && gamingPcStatus.awake) {
      var p3 = el('span', 'gaming-pill ok', pills);
      ico('bolt', p3, 18);
      p3.appendChild(document.createTextNode('PC Online' + (typeof gamingPcStatus.batteryPercent === 'number' && gamingPcStatus.batteryPercent >= 0 ? (' · ' + gamingPcStatus.batteryPercent + '%') : '')));
    }

    gamingRowsData = [];
    var tiles = GamingService.buildTiles(apps, inputs);
    if (tiles.length) {
      gamingRowsData.push({title: tr('Gaming'), items: tiles});
    }
    if (gamingPcStatus && gamingPcStatus.games && gamingPcStatus.games.length) {
      var gItems = [];
      for (var g = 0; g < gamingPcStatus.games.length && g < 16; g++) {
        var gm = gamingPcStatus.games[g];
        if (gm.hidden) continue;
        gItems.push({
          id: 'pc_game_' + gm.id, title: gm.title, subtitle: gm.category || 'PC Game',
          icon: 'gaming', imageUrl: gm.imageUrl || '', colorTop: '#1E293B', colorBottom: '#0F172A',
          run: (function (gameObj) {
            return function () {
              if (ml) launcher(ml.id, ml.title)();
              else toast(gameObj.title);
            };
          })(gm)
        });
      }
      if (gItems.length) gamingRowsData.push({title: 'PC Games Library', items: gItems});
    }
    gamingRowsData.push({
      title: 'Gaming Setup & Controls',
      items: [
        {id: 'cfg_gaming_hdmi', title: 'Gaming PC HDMI Port', subtitle: hdmi ? hdmi.toUpperCase() : tr('Not set'), icon: 'hdmi', colorTop: '#027DB8', colorBottom: '#0369A1',
          run: function () { cycleGamingHdmi(); }},
        {id: 'cfg_gaming_pc_ip', title: 'Gaming PC IP', subtitle: Prefs.gamingPcIp() || tr('Not set'), icon: 'wifi', colorTop: '#3B40D4', colorBottom: '#262994',
          run: function () { openInputModal('Gaming PC IP', Prefs.gamingPcIp(), '192.168.1.100', function (val) { Prefs.set('gamingPcIp', val); renderGaming(); }); }},
        {id: 'open_gaming_settings', title: tr('Settings'), subtitle: 'Gaming & Inputs', icon: 'settings', colorTop: '#2C2D31', colorBottom: '#1E2025',
          run: function () { st.setCatIdx = findSettingsCategoryIndex('gaming'); st.setRowIdx = 0; goScreen('settings', false); }}
      ]
    });

    renderWideRows($('gaming-rows'), gamingRowsData, Z.GAMING);
    st.gamingRow = Math.min(st.gamingRow, Math.max(0, gamingRowsData.length - 1));
    st.gamingCol = Math.min(st.gamingCol, Math.max(0, (gamingRowsData[st.gamingRow] ? gamingRowsData[st.gamingRow].items.length : 1) - 1));
  }

  function cycleGamingHdmi() {
    var vals = ['', 'hdmi1', 'hdmi2', 'hdmi3', 'hdmi4'];
    var cur = Prefs.gamingHdmi();
    var next = vals[(vals.indexOf(cur) + 1) % vals.length];
    Prefs.set('gamingHdmi', next);
    toast('Gaming HDMI: ' + (next ? next.toUpperCase() : tr('Not set')));
    renderTopBar();
    renderGaming();
    updateFocus();
  }

  // MARK: - Smart-Home Screen (Sensibo HTTPS, Tuya HTTPS, webOS WiZ UDP 38899, Routines, Inputs)

  function renderSmartHome() {
    smartHomeRowsData = [];
    var devs = SmartHomeClient.getDevices();
    var devTiles = [];
    for (var i = 0; i < devs.length; i++) {
      (function (d) {
        devTiles.push({
          id: d.id, title: d.name, subtitle: d.status, icon: d.icon, on: d.on, device: d,
          run: function () {
            SmartHomeClient.toggleDevice(d.id, function (ok, msg) {
              toast(msg || (ok ? d.name : 'Error'));
              renderSmartHome();
              updateFocus();
            });
          },
          hold: function () { openSmartDeviceControlCard(d); }
        });
      })(devs[i]);
    }
    if (devTiles.length) {
      smartHomeRowsData.push({title: tr('Smart-Home Devices'), items: devTiles});
    }

    var routines = [
      {id: 'sh_ac_ctrl', title: tr('AC Mode, Temperature & Fan'), subtitle: 'Cool / Heat · 16°C–30°C · Fan', icon: 'ac', colorTop: '#027DB8', colorBottom: '#0369A1',
        run: function () {
          var ac = devs[0];
          for (var j = 0; j < devs.length; j++) if (devs[j].protocol === 'SENSIBO_AC' || devs[j].protocol === 'TUYA_AC') { ac = devs[j]; break; }
          if (ac) openSmartDeviceControlCard(ac); else toast('Configure Sensibo or Tuya in Settings');
        }},
      {id: 'sh_light_ctrl', title: tr('Dim Lights & Color Temp'), subtitle: '10%–100% · Warm / Daylight', icon: 'lightbulb', colorTop: '#C2640A', colorBottom: '#92400E',
        run: function () {
          openSmartDeviceControlCard({id: 'wiz_lights', protocol: 'WIZ_LIGHT', name: 'Lights'});
        }},
      {id: 'sh_movie', title: tr('Routine: Movie Time'), subtitle: 'Warm 20% Lights + AC 22°C', icon: 'sparkles', colorTop: '#7835E6', colorBottom: '#4C1D95',
        run: function () { SmartHomeClient.executeRoutine('movie_time', function (ok, msg) { toast(msg); renderSmartHome(); }); }},
      {id: 'sh_night', title: tr('Routine: Good Night'), subtitle: 'Turn off Lights & ACs', icon: 'power', colorTop: '#1E293B', colorBottom: '#0F172A',
        run: function () { SmartHomeClient.executeRoutine('good_night', function (ok, msg) { toast(msg); renderSmartHome(); }); }}
    ];
    if (TV.supportsLocalWiz) {
      routines.push({
        id: 'sh_wiz_scan', title: tr('Scan Wi-Fi for New Bulbs'), subtitle: 'WiZ Local UDP 38899', icon: 'wifi', colorTop: '#2563EB', colorBottom: '#1D4ED8',
        run: function () {
          toast('Scanning Wi-Fi for WiZ bulbs (UDP 38899)…');
          SmartHomeClient.discoverWizBulbs(function (ok, msg) { toast(msg); renderTopBar(); renderSmartHome(); updateFocus(); });
        }
      });
    }
    smartHomeRowsData.push({title: tr('Advanced Controls & Routines'), items: routines});

    var inpTiles = [], shownInputs = orderedInputs();
    for (var k = 0; k < shownInputs.length; k++) {
      (function (inp) {
        inpTiles.push({
          id: 'inp_' + inp.key,
          title: inp.label || tr('Live TV'),
          subtitle: inp.connected ? tr('Connected') : tr('Standby'),
          icon: inp.key === 'live' ? 'live_tv' : 'hdmi',
          on: !!inp.connected,
          run: launcher(inp.id, inp.label || tr('Live TV'))
        });
      })(shownInputs[k]);
    }
    if (inpTiles.length) {
      smartHomeRowsData.push({title: tr('TV Inputs & Sources'), items: inpTiles});
    }

    renderWideRows($('smarthome-rows'), smartHomeRowsData, Z.SMARTHOME);
    st.shRow = Math.min(st.shRow, Math.max(0, smartHomeRowsData.length - 1));
    st.shCol = Math.min(st.shCol, Math.max(0, (smartHomeRowsData[st.shRow] ? smartHomeRowsData[st.shRow].items.length : 1) - 1));
  }

  function renderWideRows(container, rowsList, zoneName) {
    container.innerHTML = '';
    for (var r = 0; r < rowsList.length; r++) {
      var row = rowsList[r];
      var rowEl = el('div', 'wide-row', container);
      el('div', 'wide-row-title', rowEl, row.title);
      var track = el('div', 'wide-row-track', rowEl);
      for (var c = 0; c < row.items.length; c++) {
        (function (ri, ci, item) {
          var tile = el('div', 'wide-tile' + (item.on ? ' on' : ''), track);
          startAt(tile, 76 + ci * 380);
          var box = el('div', 'tile-box', tile);
          if (item.imageUrl) {
            box.style.backgroundImage = 'url("' + item.imageUrl + '")';
            box.style.backgroundSize = 'cover';
          } else {
            if (item.colorTop && item.colorBottom) paintGrad(box, item.colorTop, item.colorBottom);
            ico(item.icon || 'sparkles', box, 54);
          }
          el('div', 'tile-title', tile, item.title);
          el('div', 'tile-sub', tile, item.subtitle || '');
          tile.onmouseover = function () {
            if (modalOpen()) return;
            st.zone = zoneName;
            if (zoneName === Z.GAMING) { st.gamingRow = ri; st.gamingCol = ci; }
            else { st.shRow = ri; st.shCol = ci; }
            updateFocus();
          };
          tile.onclick = function () {
            if (item.run) item.run();
            else if (item.appId) launcher(item.appId, item.title)();
          };
          tile.oncontextmenu = function (ev) {
            ev.preventDefault();
            if (item.hold) item.hold();
          };
        })(r, c, row.items[c]);
      }
    }
  }

  // MARK: - Two-Panel Settings (38% Left Rail / 62% Right Pane, settings.webp)

  var settingsTree = [];

  function dimName() {
    var v = Prefs.dim();
    return v === 'low' ? tr('dim|Low') : v === 'medium' ? tr('dim|Medium') : v === 'high' ? tr('dim|High') : v === 'auto' ? tr('Automatic') : tr('Off');
  }
  function featureModeLabel(m) { return m === 'true' ? 'Shown' : m === 'false' ? 'Hidden' : tr('Automatic'); }
  function maskKey(k) { return k && k.length >= 4 ? ('•••• ' + k.slice(-4)) : tr('Not set'); }

  function buildSettingsTree() {
    var auto = tr('Automatic'), unit = Prefs.temperature(), theme = Prefs.theme(), clock = Prefs.clockFormat();
    var picked = pickedLanguage();
    var prof = SupabaseService.getActiveProfile();
    var ml = TV.findMoonlightApp(apps);

    // Build streaming services rows
    var discRows = [
      {label: tr('TMDB key'), sub: 'Catalog, posters & 4K hero backdrops', icon: 'key', value: maskKey(Prefs.tmdbKey()), tone: Prefs.tmdbKey() ? 'ok' : 'warn',
        step: function (d) { if (d >= 0) openKey(); }},
      {label: 'OMDb API Key', sub: 'Rotten Tomatoes & Metacritic critic scores (1,000/day free)', icon: 'key', value: maskKey(Prefs.omdbKey()), tone: Prefs.omdbKey() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('OMDb API Key', Prefs.omdbKey(), 'Enter OMDb key', function (val) { Prefs.set('omdbKey', val); renderSettings(); }); }},
      {label: 'MDBList API Key', sub: 'IMDb, Rotten Tomatoes & Metacritic scores (mdblist.com)', icon: 'key', value: maskKey(Prefs.mdblistKey()), tone: Prefs.mdblistKey() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('MDBList API Key', Prefs.mdblistKey(), 'Enter MDBList key', function (val) { Prefs.set('mdblistKey', val); renderSettings(); }); }},
      {label: 'Gemini API Key', sub: 'Google Gemini 2.5 Flash natural-language AI Search', icon: 'sparkles', value: maskKey(Prefs.geminiKey()), tone: Prefs.geminiKey() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Gemini API Key', Prefs.geminiKey(), 'Enter Gemini API key', function (val) { Prefs.set('geminiKey', val); renderSettings(); }); }},
      {label: tr('Cartoons and anime'), sub: 'Show animated titles inside general discovery rows', icon: 'palette', toggle: !Prefs.hideAnimation(),
        step: function () { Prefs.set('hideAnimation', !Prefs.hideAnimation()); Data.refresh(); }},
      {label: tr('Picks per tab'), sub: 'Legacy quick picks density', icon: 'sliders', value: String(I18N.digits(Prefs.cards())),
        step: function (d) { Prefs.set('cards', 3 + (Prefs.cards() - 3 + (d || 1) + 4) % 4); }},
      {label: 'Gaming Tab', sub: 'Auto-shows when Moonlight-TV or Gaming HDMI is configured', icon: 'gaming', value: featureModeLabel(Prefs.gamingMode()),
        step: function (d) { cycle('gamingMode', ['auto', 'true', 'false'], Prefs.gamingMode(), d); renderTopBar(); }},
      {label: 'Smart-Home Tab', sub: 'Auto-shows when Sensibo, Tuya, or WiZ bulbs are configured', icon: 'smarthome', value: featureModeLabel(Prefs.smartHomeMode()),
        step: function (d) { cycle('smartHomeMode', ['auto', 'true', 'false'], Prefs.smartHomeMode(), d); renderTopBar(); }}
    ];
    if (nuvio() || TV.findStremioApp(apps)) {
      var open = Prefs.openPicks();
      discRows.push({
        label: tr('Open picks in'), sub: 'Default app when selecting a movie or TV show', icon: 'play',
        value: open === 'nuvio' ? 'Nuvio' : open === 'stremio' ? 'Stremio' : tr('Streaming apps'),
        step: function (d) { cycle('openPicks', ['apps', 'stremio', 'nuvio'], open, d); }
      });
    }
    var sv = Data.services();
    if (sv.length) {
      var someOff = false;
      for (var q = 0; q < sv.length; q++) someOff = someOff || Prefs.serviceOff(sv[q].key);
      discRows.push({
        label: tr('Picks from'), sub: 'Reset all installed streaming services to active', icon: 'apps',
        value: someOff ? tr('services|Some') : tr('services|All'),
        step: function () { Prefs.set('servicesOff', []); Data.settingsChanged(); }
      });
      for (var j = 0; j < sv.length; j++) {
        (function (key, name) {
          discRows.push({
            label: name, sub: tr('Streaming apps'), icon: 'play', toggle: !Prefs.serviceOff(key),
            step: function () { Prefs.setServiceOff(key, !Prefs.serviceOff(key)); Data.refresh(); }
          });
        })(sv[j].key, sv[j].name);
      }
    }

    var inputsRows = [
      {label: tr('Live TV button'), sub: 'Default quick TV source', icon: 'live_tv', value: boxLabel(),
        step: function (d) { cycle('boxInput', ['live', 'hdmi1', 'hdmi2', 'hdmi3', 'hdmi4'], Prefs.boxInput(), d); }},
      {label: tr('Inputs'), sub: 'Show, hide & reorder TV inputs', icon: 'hdmi', value: '›', inputs: true,
        step: function (d) { if (d >= 0) openEdit('inputs'); }},
      {label: tr('Shelf'), sub: 'Reorder favorite apps in the Home Side-Shelf', icon: 'apps', value: '›', shelf: true,
        step: function (d) { if (d >= 0) openEdit('shelf'); }},
      {label: 'Custom Favorite Apps Shelf for This TV', sub: 'Keep a separate Favorite Apps list on this TV instead of syncing with your profile', icon: 'apps', toggle: Prefs.tvOverrideShelf(),
        step: function () { Prefs.set('tvOverrideShelf', !Prefs.tvOverrideShelf()); pickShelfApps(); }}
    ];
    if (TV.canReturnHome) {
      inputsRows.push({
        label: tr('Home button'), sub: 'Samsung Tizen return-to-SoarTV watcher', icon: 'home',
        value: Prefs.homeReturns() ? 'SoarTV' : tr('home|TV home screen'),
        step: function () { Prefs.set('homeReturns', !Prefs.homeReturns()); TV.returnHome(Prefs.homeReturns()); }
      });
    }

    var displayRows = [
      {label: tr('Theme'), sub: 'Twilight Aurora (Dark), Daylight (Light), or Sunset-to-Sunrise', icon: 'palette',
        value: theme === 'light' ? tr('Light') : theme === 'dark' ? tr('Dark') : auto,
        step: function (d) { cycle('theme', ['dark', 'light', 'auto'], theme, d); checkNight(); }},
      {label: tr('Dim'), sub: 'Dark-room software dimming layer', icon: 'display', value: dimName(),
        step: function (d) { cycle('dim', ['off', 'low', 'medium', 'high', 'auto'], Prefs.dim(), d); applyDim(); }},
      {label: tr('Screensaver'), sub: 'Rotating media photos or OLED drifting clock', icon: 'clock',
        value: Prefs.saver() === 'clock' ? tr('Clock') : tr('Photos'),
        step: function (d) { cycle('saver', ['photos', 'clock'], Prefs.saver(), d); }},
      {label: 'Custom Screensaver for This TV', sub: 'Keep separate screensaver preferences only on this TV', icon: 'display', toggle: Prefs.tvOverrideScreensaver(),
        step: function () { Prefs.set('tvOverrideScreensaver', !Prefs.tvOverrideScreensaver()); }},
      {label: tr('Watch from the screensaver'), sub: 'Press OK on a screensaver photo to open its app', icon: 'play', toggle: Prefs.saverWatch(),
        step: function () { Prefs.set('saverWatch', !Prefs.saverWatch()); }},
      {label: tr('Weather widget'), sub: 'Show live weather pill and ambient forecast', icon: 'sunny', toggle: Prefs.weather(),
        step: function () { Prefs.set('weather', !Prefs.weather()); renderTopBar(); }},
      {label: tr('City'), sub: 'Location for local weather and sunrise/sunset', icon: 'globe', value: Prefs.city() || '—', city: true,
        step: function (d) { if (d >= 0) openCities(); }},
      {label: tr('Forecast'), sub: 'Hourly or multi-day weather preview', icon: 'sunny',
        value: Prefs.forecast() === 'days' ? tr('Next days') : tr('Next hours'),
        step: function (d) { cycle('forecast', ['hours', 'days'], Prefs.forecast(), d); }},
      {label: tr('Temperature'), sub: 'Degrees unit', icon: 'thermometer',
        value: unit === 'C' ? '°C' : unit === 'F' ? '°F' : tr('unit|Automatic'),
        step: function (d) { cycle('temperature', ['auto', 'C', 'F'], unit, d); renderTopBar(); }},
      {label: tr('Clock'), sub: 'Time format', icon: 'clock',
        value: clock === '24' ? tr('24 hours') : clock === '12' ? tr('12 hours') : auto,
        step: function (d) { cycle('clockFormat', ['auto', '24', '12'], clock, d); I18N.setClock(Prefs.clockFormat()); renderClock(); }}
    ];

    var cloudRows = [
      {label: 'Supabase Project URL', sub: 'Syncs multi-user profiles, watchlist & cloud remote', icon: 'cloud',
        value: Prefs.supabaseUrl() ? Prefs.supabaseUrl().replace(/^https?:\/\//i, '').slice(0, 22) : tr('Not set'),
        tone: Prefs.supabaseUrl() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Supabase Project URL', Prefs.supabaseUrl(), 'https://your-project.supabase.co', function (val) { Prefs.set('supabaseUrl', val); reconnectSupabase(); renderSettings(); }); }},
      {label: 'Supabase Anon Key', sub: 'Publishable project key', icon: 'key', value: maskKey(Prefs.supabaseAnonKey()), tone: Prefs.supabaseAnonKey() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Supabase Anon Key', Prefs.supabaseAnonKey(), 'eyJ...', function (val) { Prefs.set('supabaseAnonKey', val); reconnectSupabase(); renderSettings(); }); }},
      {label: 'Household Secret Key', sub: 'Optional Dual-Path RLS x-soartv-household-key', icon: 'key', value: maskKey(Prefs.supabaseHouseholdKey()),
        step: function (d) { if (d >= 0) openInputModal('Household Secret Key', Prefs.supabaseHouseholdKey(), 'Optional household key', function (val) { Prefs.set('supabaseHouseholdKey', val); reconnectSupabase(); renderSettings(); }); }},
      {label: 'Cloud Remote (Supabase Realtime)', sub: 'Control this LG/Samsung TV from phone without port 8484', icon: 'remote', toggle: Prefs.cloudRemoteEnabled(),
        step: function () { Prefs.set('cloudRemoteEnabled', !Prefs.cloudRemoteEnabled()); reconnectSupabase(); renderTopBar(); }},
      {label: 'Cloud Remote Channel', sub: 'Topic: ' + SupabaseService.channelTopic(), icon: 'wifi',
        value: SupabaseService.channelCode(), tone: SupabaseService.isRemoteConnected() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Cloud Remote Channel ID', SupabaseService.channelCode(), 'living-room-tv', function (val) { Prefs.set('cloudRemoteChannel', val); reconnectSupabase(); renderSettings(); }); }},
      {label: 'Sync Cloud Settings & Encrypted Vault Now', sub: CloudSyncService.getStatus().status + ' · Automatic Household-Key AES-256-GCM sync', icon: 'sync',
        value: CloudSyncService.isConfigured() ? 'Sync' : tr('Not set'), tone: CloudSyncService.isConfigured() ? 'ok' : '',
        step: function () {
          toast('Syncing Cloud Settings & Encrypted Vault…');
          var appIds = apps.map(function (a) { return a.id; });
          CloudSyncService.syncNow('manual', function () {
            pickShelfApps();
            renderAll();
            toast('Cloud Sync: ' + CloudSyncService.getStatus().status);
          }, appIds);
        }}
    ];

    var gamingRows = [
      {label: 'Moonlight-TV App', sub: ml ? ('Detected: ' + ml.id) : 'Install moonlight-tv on webOS/Tizen for PC streaming', icon: 'moonlight',
        value: ml ? tr('Connected') : tr('Not set'), tone: ml ? 'ok' : '',
        step: function () { if (ml) launcher(ml.id, ml.title)(); else toast('Install moonlight-tv from Homebrew/TizenBrew'); }},
      {label: 'Gaming PC HDMI Port', sub: 'Direct HDMI switch tile on the Gaming tab', icon: 'hdmi',
        value: Prefs.gamingHdmi() ? Prefs.gamingHdmi().toUpperCase() : tr('Not set'),
        step: function (d) { cycle('gamingHdmi', ['', 'hdmi1', 'hdmi2', 'hdmi3', 'hdmi4'], Prefs.gamingHdmi(), d); renderTopBar(); }},
      {label: 'Gaming PC LAN IP', sub: 'Optional SoarTV PC Agent (:8485) status & library', icon: 'wifi',
        value: Prefs.gamingPcIp() || tr('Not set'),
        step: function (d) { if (d >= 0) openInputModal('Gaming PC IP', Prefs.gamingPcIp(), '192.168.1.100', function (val) { Prefs.set('gamingPcIp', val); renderTopBar(); renderSettings(); }); }}
    ];

    var shRows = [
      {label: 'TV Room', sub: 'Default room for Lights and climate tiles', icon: 'home', value: Prefs.tvRoom(),
        step: function (d) { cycle('tvRoom', ['Living Room', 'Bedroom', 'Office', 'Kitchen'], Prefs.tvRoom(), d); }},
      {label: 'Sensibo API Key', sub: 'Direct HTTPS v2 AC control (home.sensibo.com)', icon: 'ac', value: maskKey(Prefs.sensiboKey()), tone: Prefs.sensiboKey() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Sensibo API Key', Prefs.sensiboKey(), 'Enter Sensibo API key', function (val) { Prefs.set('sensiboKey', val); SmartHomeClient.syncSensibo(); renderTopBar(); renderSettings(); }); }},
      {label: 'Tuya Access ID', sub: 'Tuya OpenAPI v2.0 Client ID', icon: 'ac', value: maskKey(Prefs.tuyaAccessId()), tone: SmartHomeClient.hasTuya() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('Tuya Access ID', Prefs.tuyaAccessId(), 'Enter Tuya Access ID', function (val) { Prefs.set('tuyaAccessId', val); renderTopBar(); renderSettings(); }); }},
      {label: 'Tuya Access Secret', sub: 'Signed locally in ES5 via HMAC-SHA256', icon: 'key', value: maskKey(Prefs.tuyaAccessSecret()),
        step: function (d) { if (d >= 0) openInputModal('Tuya Access Secret', Prefs.tuyaAccessSecret(), 'Enter Tuya Secret', function (val) { Prefs.set('tuyaAccessSecret', val); renderTopBar(); renderSettings(); }); }},
      {label: 'Tuya Data Center', sub: 'eu = Central Europe, we = Western Europe, us = Americas', icon: 'globe', value: Prefs.tuyaRegion().toUpperCase(),
        step: function (d) { cycle('tuyaRegion', ['eu', 'we', 'us', 'in'], Prefs.tuyaRegion(), d); }},
      {label: 'WiZ Light Bulb IPs', sub: TV.supportsLocalWiz ? 'LG webOS local UDP 38899 (optional comma-separated seed IPs)' : 'Local WiZ UDP requires LG webOS service', icon: 'lightbulb',
        value: SmartHomeClient.bulbs().length ? (SmartHomeClient.bulbs().length + ' found') : (Prefs.wizSeedIps() || tr('Not set')),
        tone: SmartHomeClient.hasWiz() ? 'ok' : '',
        step: function (d) { if (d >= 0) openInputModal('WiZ Bulb IPs (optional)', Prefs.wizSeedIps(), '192.168.1.40, 192.168.1.41', function (val) { Prefs.set('wizSeedIps', val); renderTopBar(); renderSettings(); }); }}
    ];
    if (TV.supportsLocalWiz) {
      shRows.push({
        label: tr('Scan Wi-Fi for New Bulbs'), sub: 'Broadcast UDP 38899 getPilot on local LAN', icon: 'wifi', value: '›',
        step: function () {
          toast('Scanning local Wi-Fi for WiZ bulbs…');
          SmartHomeClient.discoverWizBulbs(function (ok, msg) { toast(msg); renderTopBar(); renderSettings(); });
        }
      });
    }

    var acctRows = [
      {label: 'Household Profile', sub: 'Press OK or Left/Right to switch active user profile', icon: 'user',
        value: prof.display_name + ' (' + SupabaseService.getProfiles().length + ')', tone: 'ok',
        step: function () {
          var next = SupabaseService.cycleProfile();
          toast('Profile: ' + next.display_name);
          Data.syncAccounts(function () { renderHome(); });
          renderTopBar();
          renderSettings();
        }},
      {label: 'Add Household Profile', sub: 'Saves locally and syncs to Supabase user_profiles', icon: 'user', value: '›',
        step: function (d) { if (d >= 0) openInputModal('New Profile Name', '', 'e.g. Alex', function (val) {
          if (!val) return;
          SupabaseService.upsertProfile(val, '', function () {
            toast('Profile created: ' + val);
            renderTopBar();
            renderSettings();
          });
        }); }},
      {label: 'Trakt Client ID', sub: 'Required for Trakt device-code OAuth (urn:ietf:wg:oauth:2.0:oob)', icon: 'key', value: maskKey(Prefs.traktClientId()),
        step: function (d) { if (d >= 0) openInputModal('Trakt Client ID', Prefs.traktClientId(), 'Enter Trakt Client ID', function (val) { Prefs.set('traktClientId', val); renderSettings(); }); }},
      {label: 'Trakt Account', sub: TraktClient.isSignedIn() ? ('Signed in as @' + (TraktClient.username() || 'user') + ' · OK to sign out') : 'Device-code OAuth with QR code (trakt.tv/activate)', icon: 'sync',
        value: TraktClient.isSignedIn() ? tr('Connected') : tr('Not set'), tone: TraktClient.isSignedIn() ? 'ok' : '',
        step: function (d) {
          if (d < 0) return;
          if (TraktClient.isSignedIn()) {
            TraktClient.signOut(function () { toast('Trakt signed out'); renderHome(); renderSettings(); });
          } else {
            openTraktAuthModal();
          }
        }},
      {label: 'Stremio Account (Library Sync)', sub: StremioClient.isConfigured() ? (StremioClient.email() || 'AuthKey active · OK to sign out') : 'Sync Stremio Library & Continue Watching via api.strem.io', icon: 'cloud',
        value: StremioClient.isConfigured() ? tr('Connected') : tr('Not set'), tone: StremioClient.isConfigured() ? 'ok' : '',
        step: function (d) {
          if (d < 0) return;
          if (StremioClient.isConfigured()) {
            StremioClient.signOut();
            toast('Stremio disconnected');
            renderHome();
            renderSettings();
          } else {
            openStremioLoginFlow();
          }
        }},
      {label: 'Sync Accounts Now', sub: 'Refresh Trakt, Stremio Library & Supabase Profiles', icon: 'sync', value: '›',
        step: function () {
          toast('Syncing accounts…');
          SupabaseService.fetchProfiles(function () {
            Data.syncAccounts(function () {
              toast('Accounts synced');
              renderTopBar();
              renderSettings();
            });
          });
        }}
    ];

    var sysRows = [
      {label: tr('Language'), sub: 'Display language for SoarTV interface and TMDB metadata', icon: 'globe',
        value: picked === 'auto' ? auto : I18N.languageName(picked), language: true,
        step: function (d) { if (d >= 0) openLanguages(); }},
      {label: tr('About this TV'), sub: 'Model, firmware, OS, IP address & SoarTV version', icon: 'info', value: '›', about: true,
        step: function (d) { if (d >= 0) openAbout(); }}
    ];
    if (TV.canOpenSettings) {
      sysRows.push({
        label: tr('TV Settings'), sub: 'Open native LG webOS / Samsung Tizen settings', icon: 'external', value: '›',
        step: function (d) { if (d >= 0) tvSettings(); }
      });
    }

    settingsTree = [
      {section: 'TV & CONNECTIVITY', id: 'inputs', icon: 'hdmi', label: tr('Inputs') + ' & ' + tr('Shelf'), val: shelf.length + ' apps', rows: inputsRows},
      {section: null, id: 'display', icon: 'display', label: tr('Theme') + ' & ' + tr('Weather widget'), val: theme === 'light' ? tr('Light') : theme === 'dark' ? tr('Dark') : auto, rows: displayRows},
      {section: null, id: 'cloud', icon: 'remote', label: 'Cloud & Remote', val: SupabaseService.isRemoteConnected() ? tr('Connected') : (SupabaseService.isConfigured() ? 'Ready' : tr('Not set')), tone: SupabaseService.isRemoteConnected() ? 'ok' : '', rows: cloudRows},
      {section: 'SOARTV', id: 'discovery', icon: 'sparkles', label: 'Discovery & AI', val: Prefs.tmdbKey() ? 'TMDB' : tr('Not set'), tone: Prefs.tmdbKey() ? 'ok' : 'warn', rows: discRows},
      {section: null, id: 'gaming', icon: 'gaming', label: tr('Gaming'), val: ml ? 'Moonlight' : (Prefs.gamingHdmi() ? Prefs.gamingHdmi().toUpperCase() : tr('Not set')), tone: ml ? 'ok' : '', rows: gamingRows},
      {section: null, id: 'smarthome', icon: 'smarthome', label: tr('Smart-Home'), val: SmartHomeClient.hasAnyConfigured() ? Prefs.tvRoom() : tr('Not set'), tone: SmartHomeClient.hasAnyConfigured() ? 'ok' : '', rows: shRows},
      {section: 'ACCOUNTS & SYSTEM', id: 'accounts', icon: 'user', label: 'Accounts & Profiles', val: prof.display_name, tone: (TraktClient.isSignedIn() || StremioClient.isConfigured()) ? 'ok' : '', rows: acctRows},
      {section: null, id: 'system', icon: 'settings', label: tr('System'), val: TV.name.toUpperCase(), rows: sysRows}
    ];
  }

  function findSettingsCategoryIndex(id) {
    buildSettingsTree();
    for (var i = 0; i < settingsTree.length; i++) if (settingsTree[i].id === id) return i;
    return 0;
  }

  function searchSettingsItems(ql) {
    buildSettingsTree();
    var hits = [];
    for (var c = 0; c < settingsTree.length; c++) {
      var cat = settingsTree[c];
      for (var r = 0; r < cat.rows.length; r++) {
        var row = cat.rows[r];
        if ((row.label + ' ' + (row.sub || '')).toLowerCase().indexOf(ql) >= 0) {
          hits.push({kind: 'setting', catIdx: c, rowIdx: r, title: row.label, subtitle: cat.label, icon: row.icon});
        }
      }
    }
    return hits;
  }

  function renderSettings() {
    buildSettingsTree();
    st.setCatIdx = Math.min(st.setCatIdx, Math.max(0, settingsTree.length - 1));
    var curCat = settingsTree[st.setCatIdx];
    st.setRowIdx = Math.min(st.setRowIdx, Math.max(0, curCat.rows.length - 1));

    $('settings-left-head').textContent = tr('Settings');
    var leftList = $('settings-left-list');
    leftList.innerHTML = '';
    for (var c = 0; c < settingsTree.length; c++) {
      (function (idx, cat) {
        if (cat.section) el('div', 'set-section-head', leftList, cat.section);
        var row = el('div', 'set-cat-row', leftList);
        row.setAttribute('data-cat', String(idx));
        ico(cat.icon, row, 20);
        row.appendChild(document.createTextNode(cat.label));
        var val = el('span', 'cat-val', row);
        if (cat.tone === 'ok') el('span', 'dot-ok', val);
        else if (cat.tone === 'warn') el('span', 'dot-warn', val);
        val.appendChild(document.createTextNode(cat.val || ''));
        row.onmouseover = function () {
          st.zone = Z.SETTINGS_LEFT;
          st.setCatIdx = idx;
          st.setRowIdx = 0;
          renderSettingsRight();
          updateFocus();
        };
        row.onclick = function () {
          st.setCatIdx = idx;
          st.setRowIdx = 0;
          st.zone = Z.SETTINGS_RIGHT;
          renderSettingsRight();
          updateFocus();
        };
      })(c, settingsTree[c]);
    }
    renderSettingsRight();
    $('settings-footer').textContent = tr('OK or the arrows change the value.') + '  ·  ' + tr('Back closes.');
  }

  function renderSettingsRight() {
    var cat = settingsTree[st.setCatIdx];
    if (!cat) return;
    $('settings-right-head').textContent = cat.label;
    var rowsEl = $('settings-right-rows');
    rowsEl.innerHTML = '';
    for (var i = 0; i < cat.rows.length; i++) {
      (function (idx, r) {
        var row = el('div', 'set-detail-row', rowsEl);
        el('div', 'focus-bar', row);
        ico(r.icon || 'settings', row, 22, 'row-ico');
        var textBox = el('div', 'row-text', row);
        el('div', 'row-title', textBox, I18N.name(r.label));
        if (r.sub) el('div', 'row-sub', textBox, r.sub);
        if (r.toggle !== undefined) {
          setClass(el('div', 'toggle', row), 'on', !!r.toggle);
        } else {
          var v = el('span', 'row-val', row);
          if (r.tone === 'ok') el('span', 'dot-ok', v);
          else if (r.tone === 'warn') el('span', 'dot-warn', v);
          v.appendChild(document.createTextNode(I18N.name(r.value || '')));
        }
        row.onmouseover = function () { st.zone = Z.SETTINGS_RIGHT; st.setRowIdx = idx; updateFocus(); };
        row.onclick = function () {
          st.zone = Z.SETTINGS_RIGHT; st.setRowIdx = idx;
          if (r.step) { r.step(1); if (st.screen === 'settings' && !modalOpen()) { renderSettings(); updateFocus(); } }
        };
      })(i, cat.rows[i]);
    }
  }

  function cycle(name, values, current, d) {
    var i = values.indexOf(current);
    Prefs.set(name, values[(i + (d || 1) + values.length) % values.length]);
  }

  // MARK: - Context Menus & Smart-Home Device Control Card

  var contextModel = null;

  function openMediaContextMenu(pick) {
    if (!pick) return;
    var onWl = Data.isOnWatchlist(pick);
    var watched = Data.isWatched(pick);
    contextModel = {
      kind: 'menu',
      title: pick.title,
      icon: 'play',
      hint: tr('OK picks, Back cancels.'),
      rows: [
        {label: 'Watch', icon: 'play', run: function () { closeModals(); openMediaPick(pick); }},
        {label: onWl ? tr('Remove from Watch List') : tr('Add to Watch List'), icon: 'bookmark', run: function () {
          var added = Data.toggleWatchlist(pick);
          toast((added ? 'Added to Watch List: ' : 'Removed from Watch List: ') + pick.title);
          closeModals(); renderHome(); updateFocus();
        }},
        {label: watched ? tr('Mark as Unwatched') : tr('Mark as Watched'), icon: 'check', run: function () {
          var w = Data.toggleWatched(pick);
          toast((w ? 'Marked as watched: ' : 'Marked as unwatched: ') + pick.title);
          closeModals(); renderHome(); updateFocus();
        }},
        {label: tr('Thumbs Up'), icon: 'thumb_up', run: function () {
          Data.castVote(pick, 1); toast('Thumbs Up: ' + pick.title); closeModals(); renderHome(); updateFocus();
        }},
        {label: tr('Thumbs Down'), icon: 'thumb_down', run: function () {
          Data.castVote(pick, -1); toast('Thumbs Down: ' + pick.title); closeModals(); renderHome(); updateFocus();
        }},
        {label: tr('Cancel'), icon: 'close', run: closeModals}
      ]
    };
    showContextModal();
  }

  function openAppContextMenu(app) {
    if (!app) return;
    var fav = shelf.indexOf(app) >= 0;
    contextModel = {
      kind: 'menu',
      title: app.title,
      icon: 'apps',
      hint: tr('OK picks, Back cancels.'),
      rows: [
        {label: 'Open ' + app.title, icon: 'play', run: function () { closeModals(); launcher(app.id, app.title)(); }},
        {label: fav ? 'Remove from Shelf' : 'Add to Shelf (Favorite)', icon: 'bookmark', run: function () {
          var ids = shelf.map(function (a) { return a.id; });
          var at = ids.indexOf(app.id);
          if (at >= 0) ids.splice(at, 1); else ids.push(app.id);
          Prefs.set('shelf', ids);
          pickShelfApps();
          toast(at >= 0 ? ('Removed: ' + app.title) : ('Added to Shelf: ' + app.title));
          closeModals();
        }},
        {label: tr('Cancel'), icon: 'close', run: closeModals}
      ]
    };
    showContextModal();
  }

  function openSmartDeviceControlCard(dev) {
    if (!dev) return;
    var isAc = dev.protocol === 'SENSIBO_AC' || dev.protocol === 'TUYA_AC';
    var modes = ['cool', 'heat', 'dry', 'fan'];
    var fans = ['auto', 'low', 'medium', 'high'];
    var kelvins = [2700, 3000, 4000, 5000, 6500];

    function buildRows() {
      if (isAc) {
        var src = dev.protocol === 'SENSIBO_AC' ? SmartHomeClient.state.sensibo : SmartHomeClient.state.tuya;
        var apply = function () {
          if (dev.protocol === 'SENSIBO_AC') SmartHomeClient.setSensibo(src.on, src.mode, src.temp, src.fan);
          else SmartHomeClient.setTuya(src.on, src.mode, src.temp, src.fan);
          renderSmartHome();
        };
        return [
          {label: tr('Turn On / Off'), icon: 'power', value: src.on ? tr('ON') : tr('OFF'),
            adjust: function () { src.on = !src.on; apply(); }},
          {label: tr('Mode'), icon: 'sliders', value: src.mode.toUpperCase(),
            adjust: function (d) { src.on = true; src.mode = modes[(modes.indexOf(src.mode) + (d || 1) + modes.length) % modes.length]; apply(); }},
          {label: tr('Temp'), icon: 'thermometer', value: src.temp + '°C',
            adjust: function (d) { src.on = true; src.temp = Math.max(16, Math.min(30, src.temp + (d || 1))); apply(); }},
          {label: tr('Fan Strength'), icon: 'fan', value: src.fan.toUpperCase(),
            adjust: function (d) { src.on = true; src.fan = fans[(fans.indexOf(src.fan) + (d || 1) + fans.length) % fans.length]; apply(); }}
        ];
      }
      var w = SmartHomeClient.state.wiz;
      var applyW = function () { SmartHomeClient.setWizLights(w.on, w.dimming, w.temp); renderSmartHome(); };
      return [
        {label: tr('Turn On / Off'), icon: 'power', value: w.on ? tr('ON') : tr('OFF'),
          adjust: function () { w.on = !w.on; applyW(); }},
        {label: tr('Strength'), icon: 'sunny', value: w.dimming + '%',
          adjust: function (d) { w.on = true; w.dimming = Math.max(10, Math.min(100, w.dimming + (d || 1) * 10)); applyW(); }},
        {label: tr('Temperature'), icon: 'thermometer', value: SmartHomeClient.kelvinLabel(w.temp) + ' (' + w.temp + 'K)',
          adjust: function (d) { w.on = true; var ki = kelvins.indexOf(w.temp); if (ki < 0) ki = 0; w.temp = kelvins[(ki + (d || 1) + kelvins.length) % kelvins.length]; applyW(); }}
      ];
    }

    contextModel = {
      kind: 'smartcard',
      title: dev.name,
      icon: isAc ? 'ac' : 'lightbulb',
      hint: tr('OK or the arrows change the value.') + '  ·  ' + tr('Back closes.'),
      buildRows: buildRows,
      rows: buildRows()
    };
    showContextModal();
  }

  function showContextModal() {
    st.prevZone = modalOpen() ? st.prevZone : st.zone;
    st.zone = Z.MODAL_CONTEXT;
    st.modalIdx = 0;
    renderContextModal();
    setClass($('context-modal'), 'open', true);
    updateFocus();
  }

  function renderContextModal() {
    if (!contextModel) return;
    if (contextModel.buildRows) contextModel.rows = contextModel.buildRows();
    var box = $('context-modal').firstChild;
    box.innerHTML = '';
    var title = el('div', 'title', box);
    ico(contextModel.icon || 'sliders', title, 28);
    title.appendChild(document.createTextNode(contextModel.title));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    for (var i = 0; i < contextModel.rows.length; i++) {
      (function (idx, r) {
        var row = el('div', 'row', holder);
        ico(r.icon || 'chevron_right', row, 22);
        row.appendChild(document.createTextNode(r.label));
        if (r.value !== undefined) {
          el('span', 'value', row, '‹  ' + r.value + '  ›');
        }
        row.onmouseover = function () { st.modalIdx = idx; updateFocus(); };
        row.onclick = function () {
          st.modalIdx = idx;
          if (r.run) r.run();
          else if (r.adjust) { r.adjust(1); renderContextModal(); updateFocus(); }
        };
      })(i, contextModel.rows[i]);
    }
    el('div', 'hint', box, contextModel.hint || '');
  }

  // MARK: - Trakt Device-Code OAuth Modal (with QR Code SVG)

  function openTraktAuthModal() {
    if (!TraktClient.isAvailable()) {
      toast('Configure Trakt Client ID first');
      return;
    }
    st.prevZone = st.zone;
    st.zone = Z.MODAL_TRAKT;
    renderTraktModal(null, 'Requesting activation code from Trakt…');
    setClass($('trakt-modal'), 'open', true);
    updateFocus();
    TraktClient.startDeviceAuth(function (info) {
      if (st.zone !== Z.MODAL_TRAKT) return;
      renderTraktModal(info, 'Waiting for authorization on your phone…');
    }, function (err, user) {
      if (st.zone === Z.MODAL_TRAKT) closeModals();
      if (err) { toast('Trakt: ' + err.message); return; }
      toast('Trakt connected' + (user ? (': @' + user) : ''));
      Data.syncAccounts(function () { renderHome(); });
      if (st.screen === 'settings') renderSettings();
    });
  }

  function renderTraktModal(info, statusMsg) {
    var box = $('trakt-modal').firstChild;
    box.innerHTML = '';
    var title = el('div', 'title', box);
    ico('sync', title, 28);
    title.appendChild(document.createTextNode('Trakt Sign-In'));
    var bodyDiv = el('div', 'trakt-body', box);
    var left = el('div', 'trakt-info', bodyDiv);
    el('div', 'hint', left, '1. Scan the QR code or visit on your phone:');
    el('div', 'trakt-url', left, info ? info.verificationUrl.replace(/^https?:\/\//i, '') : 'trakt.tv/activate');
    el('div', 'hint', left, '2. Enter this activation code:');
    el('div', 'trakt-code', left, info ? info.userCode : '······');
    if (info && info.verificationUrl) {
      var qrBox = el('div', 'trakt-qr', bodyDiv);
      qrBox.innerHTML = SoarTvIcons.qrSvg(info.verificationUrl, 190);
    }
    el('div', 'hint', box, statusMsg || '');
    var cancelBtn = el('div', 'row focus', box);
    ico('close', cancelBtn, 22);
    cancelBtn.appendChild(document.createTextNode(tr('Cancel')));
    cancelBtn.onclick = function () { TraktClient.cancelDeviceAuth(); closeModals(); };
  }

  // MARK: - Stremio Login Flow & Reusable Input Modal

  var inputModel = null;

  function openInputModal(title, initialVal, placeholder, onSave, isPassword) {
    st.prevZone = modalOpen() ? st.prevZone : st.zone;
    st.zone = Z.MODAL_INPUT;
    st.modalIdx = 0;
    inputModel = {title: title, value: initialVal || '', placeholder: placeholder || '', onSave: onSave, isPassword: !!isPassword};
    renderInputModal();
    setClass($('input-modal'), 'open', true);
    var f = $('input-modal').querySelector('input');
    if (f) f.focus();
    updateFocus();
  }

  function renderInputModal() {
    if (!inputModel) return;
    var box = $('input-modal').firstChild;
    box.innerHTML = '';
    var title = el('div', 'title', box);
    ico('key', title, 26);
    title.appendChild(document.createTextNode(inputModel.title));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    var row0 = el('div', 'row field', holder);
    var inp = el('input', '', row0);
    inp.type = inputModel.isPassword ? 'password' : 'text';
    inp.value = inputModel.value;
    inp.placeholder = inputModel.placeholder;
    inp.setAttribute('autocomplete', 'off');
    inp.setAttribute('autocapitalize', 'none');
    inp.setAttribute('spellcheck', 'false');
    row0.onclick = function () { st.modalIdx = 0; inp.focus(); updateFocus(); };

    var saveRow = el('div', 'row', holder);
    ico('check', saveRow, 22);
    saveRow.appendChild(document.createTextNode(tr('Save')));
    saveRow.onclick = function () { commitInputModal(); };

    var cancelRow = el('div', 'row', holder);
    ico('close', cancelRow, 22);
    cancelRow.appendChild(document.createTextNode(tr('Cancel')));
    cancelRow.onclick = function () { closeModals(); };
    el('div', 'hint', box, tr('OK picks, Back cancels.'));
  }

  function commitInputModal() {
    var f = $('input-modal').querySelector('input');
    var val = f ? f.value.replace(/^\s+|\s+$/g, '') : '';
    var cb = inputModel && inputModel.onSave;
    if (f) f.blur();
    closeModals();
    if (cb) cb(val);
  }

  function openStremioLoginFlow() {
    openInputModal('Stremio Email (or paste AuthKey)', StremioClient.email(), 'email@example.com', function (emailOrKey) {
      if (!emailOrKey) return;
      if (emailOrKey.indexOf('@') < 0) {
        StremioClient.setAuthKey(emailOrKey, '');
        toast('Syncing Stremio Library…');
        StremioClient.syncLibrary(function () { toast('Stremio Library synced'); renderHome(); renderSettings(); });
        return;
      }
      openInputModal('Stremio Password (never saved)', '', 'Password', function (pwd) {
        if (!pwd) return;
        toast('Connecting Stremio Account…');
        StremioClient.login(emailOrKey, pwd, function (err) {
          if (err) { toast('Stremio: ' + err.message); return; }
          StremioClient.syncLibrary(function () {
            toast('Stremio Library synced');
            renderHome();
            renderSettings();
          });
        });
      }, true);
    });
  }

  // MARK: - Legacy Sheets (Language, City, TMDB Key, Shelf/Inputs Edit, About this TV)

  var langHint = null;
  function languageChoices() { return ['auto'].concat(I18N.languages()); }
  function pickedLanguage() { return Prefs.language() === 'auto' ? 'auto' : I18N.lang; }

  function openLanguages() {
    st.prevZone = st.zone; st.zone = Z.LANG;
    st.langIdx = Math.max(0, languageChoices().indexOf(pickedLanguage()));
    renderLanguages();
    setClass($('langpick'), 'open', true);
    updateFocus();
  }
  function renderLanguages() {
    var box = $('langpick').firstChild, choices = languageChoices(), current = choices.indexOf(pickedLanguage());
    box.innerHTML = '';
    el('div', 'title', box, tr('Language'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(choices.length, 7) * 76 + 'px';
    for (var i = 0; i < choices.length; i++) {
      (function (idx, code) {
        var row = el('div', 'row', holder, code === 'auto' ? tr('Automatic') : I18N.languageName(code));
        if (idx === current) ico('check', el('span', 'value', row), 22);
        row.onmouseover = function () { st.langIdx = idx; updateFocus(); };
        row.onclick = function () { st.langIdx = idx; closeLanguages(true); };
      })(i, choices[i]);
    }
    langHint = el('div', 'hint', box);
  }
  function closeLanguages(pick) {
    setClass($('langpick'), 'open', false);
    langHint = null;
    var code = languageChoices()[st.langIdx];
    if (pick && code !== pickedLanguage()) {
      Prefs.set('language', code);
      I18N.set(Prefs.language());
      direction();
      renderAll();
      Data.refresh();
    }
    st.zone = Z.SETTINGS_RIGHT;
    renderSettings();
    updateFocus();
  }

  // City Picker
  var cityFound = [], cityStatus = '', citySeq = 0;
  function openCities() {
    st.prevZone = st.zone; st.zone = Z.CITY; st.cityIdx = 0;
    cityFound = []; cityStatus = '';
    renderCities();
    setClass($('citypick'), 'open', true);
    var f = $('citypick').querySelector('input'); if (f) f.focus();
    updateFocus();
  }
  function renderCities() {
    var box = $('citypick').firstChild, old = $('citypick').querySelector('input'), typed = old ? old.value : '';
    box.innerHTML = '';
    el('div', 'title', box, tr('City'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(2 + cityFound.length, 7) * 76 + 'px';
    var fRow = el('div', 'row field', holder), inp = el('input', '', fRow);
    inp.type = 'text'; inp.value = typed; inp.placeholder = tr("Type the city's name");
    var sRow = el('div', 'row', holder, tr('Search'));
    sRow.onclick = function () { searchCity(); };
    for (var i = 0; i < cityFound.length; i++) {
      (function (idx, place) {
        var r = el('div', 'row', holder, place.label);
        r.onclick = function () { st.cityIdx = 2 + idx; closeCities(true); };
      })(i, cityFound[i]);
    }
    el('div', 'hint', box, cityStatus || tr('OK picks, Back cancels.'));
  }
  function searchCity() {
    var f = $('citypick').querySelector('input'), name = f ? f.value : '';
    if (f) f.blur();
    if (!name.replace(/^\s+|\s+$/g, '')) return;
    cityStatus = tr('Searching…');
    renderCities();
    var mine = ++citySeq;
    Data.findCity(name, function (err, found) {
      if (mine !== citySeq || st.zone !== Z.CITY) return;
      cityFound = found || [];
      if (cityFound.length) st.cityIdx = 2;
      cityStatus = err ? trf("Couldn't search now: {error}", {error: err.message}) : (cityFound.length ? '' : tr('No city with that name.'));
      renderCities();
      updateFocus();
    });
  }
  function closeCities(pick) {
    var place = pick && cityFound[st.cityIdx - 2];
    var f = $('citypick').querySelector('input'); if (f) f.blur();
    setClass($('citypick'), 'open', false);
    if (place) { Prefs.setCity(place.city, place.lat, place.lon); Data.cityChanged(); }
    st.zone = Z.SETTINGS_RIGHT;
    renderSettings();
    updateFocus();
  }

  // TMDB Key Picker
  var keyStatus = '', keyWarn = false, keyAsking = false, keySeq = 0;
  function keySteps() {
    return tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.") + '\n'
      + tr('Free, and it takes two minutes:') + '\n' + I18N.digits(1) + '. ' + tr('Create an account at themoviedb.org.')
      + '\n' + I18N.digits(2) + '. ' + tr('Open Settings → API and request a developer key.');
  }
  function openKey() {
    st.prevZone = st.zone; st.zone = Z.KEY; st.keyIdx = 0;
    keyStatus = ''; keyAsking = false; keyWarn = false;
    renderKey();
    setClass($('keypick'), 'open', true);
    var f = $('keypick').querySelector('input'); if (f) f.focus();
    updateFocus();
  }
  function renderKey() {
    var box = $('keypick').firstChild, old = $('keypick').querySelector('input'), typed = old ? old.value : '';
    box.innerHTML = '';
    el('div', 'title', box, keyAsking ? tr('Remove the TMDB key?') : tr('TMDB key'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    if (keyAsking) {
      var c = el('div', 'row', holder, tr('Cancel')); c.onclick = function () { keyAsking = false; renderKey(); updateFocus(); };
      var rm = el('div', 'row', holder, tr('Remove')); rm.onclick = function () { Prefs.set('tmdbKey', undefined); closeKey(); Data.keyChanged(); };
      return;
    }
    var fRow = el('div', 'row field', holder), inp = el('input', '', fRow);
    inp.type = 'text'; inp.value = typed; inp.placeholder = tr('API Read Access Token or API Key');
    var save = el('div', 'row', holder, tr('Save')); save.onclick = saveKey;
    if (Prefs.tmdbKeyOnTV()) {
      var del = el('div', 'row', holder, tr('Remove the key'));
      del.onclick = function () { keyAsking = true; st.keyIdx = 0; renderKey(); updateFocus(); };
    }
    if (keyStatus) setClass(el('div', 'hint', box, keyStatus), 'warn', keyWarn);
    el('div', 'hint steps', box, keySteps());
  }
  function saveKey() {
    var f = $('keypick').querySelector('input'), raw = f ? f.value.replace(/\s+/g, '') : '';
    if (f) f.blur();
    if (!/^[A-Za-z0-9._-]{16,1024}$/.test(raw)) {
      keyStatus = tr("That doesn't look like a TMDB key."); keyWarn = true; renderKey(); updateFocus(); return;
    }
    keyStatus = tr('Checking the key with TMDB…'); keyWarn = false; renderKey(); updateFocus();
    var mine = ++keySeq;
    Data.checkKey(raw, function (err) {
      if (mine !== keySeq || st.zone !== Z.KEY) return;
      if (err) {
        keyStatus = err.rejected ? tr('TMDB refused this key.') : trf("Couldn't reach TMDB: {error}", {error: err.message});
        keyWarn = true; renderKey(); updateFocus(); return;
      }
      Prefs.set('tmdbKey', raw);
      Data.keyChanged();
      keyStatus = tr('Saved. The picks arrive in a moment.'); keyWarn = false;
      renderKey(); updateFocus();
    });
  }
  function closeKey() {
    var f = $('keypick').querySelector('input'); if (f) f.blur();
    setClass($('keypick'), 'open', false);
    st.zone = Z.SETTINGS_RIGHT;
    renderSettings();
    updateFocus();
  }

  // Shelf & Inputs Editor
  var editKind = 'shelf';
  function editList() {
    var ids = shelf.map(function (a) { return a.id; });
    return {ids: ids, rows: apps.slice().sort(function (a, b) {
      var ia = ids.indexOf(a.id), ib = ids.indexOf(b.id);
      if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
      return a.title.toLowerCase() < b.title.toLowerCase() ? -1 : 1;
    })};
  }
  function inputsEditList() {
    var shown = orderedInputs(), hidden = Prefs.inputsHidden(), rows = shown.slice();
    for (var i = 0; i < inputs.length; i++) if (hidden.indexOf(inputs[i].key) >= 0) rows.push(inputs[i]);
    return {keys: shown.map(function (x) { return x.key; }), rows: rows};
  }
  function openEdit(kind) {
    editKind = kind; st.prevZone = st.zone; st.zone = Z.EDIT; st.editIdx = 0;
    renderEdit();
    setClass($('shelfedit'), 'open', true);
    updateFocus();
  }
  function renderEdit() {
    var forInputs = editKind === 'inputs', box = $('shelfedit').firstChild;
    var e = forInputs ? inputsEditList() : editList();
    box.innerHTML = '';
    var title = el('div', 'title', box);
    ico(forInputs ? 'hdmi' : 'apps', title, 26);
    title.appendChild(document.createTextNode(forInputs ? tr('Inputs') : tr('Shelf')));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(e.rows.length, 7) * 76 + 'px';
    for (var i = 0; i < e.rows.length; i++) {
      (function (idx, item) {
        var at = (forInputs ? e.keys : e.ids).indexOf(forInputs ? item.key : item.id);
        var row = el('div', 'row', holder, I18N.name(forInputs ? (item.label || tr('Live TV')) : item.title));
        el('span', 'value', row, at >= 0 ? ('#' + (at + 1)) : tr('shelf|Off'));
        row.onclick = function () { st.editIdx = idx; if (forInputs) editInputs(0); else editShelf(0); };
      })(i, e.rows[i]);
    }
    el('div', 'hint', box, forInputs ? tr('OK shows or hides an input. ← → change the order.') : tr('OK adds or removes an app. ← → change the order.'));
  }
  function editShelf(dx) {
    var e = editList(), app = e.rows[st.editIdx]; if (!app) return;
    var ids = e.ids.slice(), at = ids.indexOf(app.id);
    if (dx === 0) { if (at >= 0) ids.splice(at, 1); else ids.push(app.id); }
    else if (at >= 0) { var to = Math.max(0, Math.min(ids.length - 1, at + dx)); ids.splice(at, 1); ids.splice(to, 0, app.id); }
    Prefs.set('shelf', ids); pickShelfApps();
    var again = editList();
    for (var i = 0; i < again.rows.length; i++) if (again.rows[i].id === app.id) st.editIdx = i;
    renderEdit(); updateFocus();
  }
  function editInputs(dx) {
    var e = inputsEditList(), x = e.rows[st.editIdx]; if (!x) return;
    var order = (Prefs.inputsOrder() || []).slice(), hidden = Prefs.inputsHidden().slice(), k;
    for (k = 0; k < e.keys.length; k++) if (order.indexOf(e.keys[k]) < 0) order.push(e.keys[k]);
    if (dx === 0) {
      var at = order.indexOf(x.key);
      if (at >= 0) { order.splice(at, 1); hidden.push(x.key); }
      else { order.push(x.key); var h = hidden.indexOf(x.key); if (h >= 0) hidden.splice(h, 1); }
    } else {
      var at2 = e.keys.indexOf(x.key); if (at2 < 0) return;
      var to = Math.max(0, Math.min(e.keys.length - 1, at2 + dx));
      if (to === at2) return;
      var at3 = order.indexOf(x.key); if (at3 >= 0) order.splice(at3, 1);
      var ni = order.indexOf(e.keys[to]);
      if (ni < 0) order.push(x.key); else order.splice(dx > 0 ? ni + 1 : ni, 0, x.key);
    }
    Prefs.set('inputsOrder', order); Prefs.set('inputsHidden', hidden);
    var again = inputsEditList();
    for (var i = 0; i < again.rows.length; i++) if (again.rows[i].key === x.key) st.editIdx = i;
    renderEdit(); updateFocus();
  }

  // About this TV
  function openAbout() {
    st.prevZone = st.zone; st.zone = Z.ABOUT;
    renderAbout(tvAboutInfo);
    setClass($('about'), 'open', true);
    updateFocus();
    TV.about(function (info) { tvAboutInfo = info; if (st.zone === Z.ABOUT) renderAbout(info); });
  }
  function renderAbout(info) {
    var box = $('about').firstChild;
    box.innerHTML = '';
    el('div', 'title', box, tr('About this TV'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    var rows = [
      [tr('Model'), info && info.model],
      [tr('Firmware'), info && info.firmware],
      [tr('System'), info && info.system],
      [tr('IP address'), info && info.ip],
      [tr('Time zone'), TV.zone()],
      ['SoarTV', info && info.version]
    ];
    for (var i = 0; i < rows.length; i++) {
      var r = el('div', 'row', holder, rows[i][0]);
      el('span', 'value', r, info ? (rows[i][1] || tr('Unavailable')) : '…');
    }
    el('div', 'hint', box, tr('Back closes.'));
  }

  function closeModals() {
    TraktClient.cancelDeviceAuth();
    var ids = ['context-modal', 'trakt-modal', 'input-modal', 'shelfedit', 'langpick', 'citypick', 'keypick', 'about'];
    for (var i = 0; i < ids.length; i++) {
      var sheet = $(ids[i]);
      var inp = sheet.querySelector('input');
      if (inp) inp.blur();
      setClass(sheet, 'open', false);
    }
    langHint = null;
    if (modalOpen()) {
      st.zone = st.prevZone && !modalOpenZone(st.prevZone) ? st.prevZone : (st.screen === 'settings' ? Z.SETTINGS_RIGHT : Z.HOME_ROWS);
    }
    updateFocus();
  }
  function modalOpenZone(z) {
    return z === Z.MODAL_CONTEXT || z === Z.MODAL_TRAKT || z === Z.MODAL_INPUT ||
      z === Z.EDIT || z === Z.LANG || z === Z.CITY || z === Z.KEY || z === Z.ABOUT;
  }

  // MARK: - Media Launching & Helpers

  function nuvio() {
    if (TV.name !== 'webos') return null;
    for (var i = 0; i < apps.length; i++) if (/nuvio/i.test(apps[i].id) || /^nuvio\b/i.test(apps[i].title)) return apps[i];
    return null;
  }

  function pickTarget(p) {
    if (!p) return null;
    var mode = Prefs.openPicks();
    if (mode === 'nuvio') {
      var nv = nuvio();
      if (nv) return {app: nv, params: {contentTarget: 'nuvio://tmdb/' + (p.kind === 'tv' ? 'series' : 'movie') + '/' + p.id}};
    }
    if (mode === 'stremio' || p.isContinue || p.onWatchlist) {
      var strApp = TV.findStremioApp(apps);
      if (strApp) {
        var uri = p.imdbId ? ('stremio:///detail/' + (p.kind === 'tv' ? 'series' : 'movie') + '/' + p.imdbId) : null;
        return {app: strApp, params: uri && TV.name === 'webos' ? {contentTarget: uri} : null};
      }
    }
    var app = Data.appFor(p.app) || TV.findStremioApp(apps) || apps[0];
    return app ? {app: app, params: null} : null;
  }

  function openMediaPick(p) {
    var t = pickTarget(p);
    if (!t) { toast(p ? p.title : ''); return; }
    launcher(t.app.id, t.app.title, t.params)();
  }

  function launcher(id, name, params) {
    return function () {
      TV.launch(id, params || null, function (ok) {
        if (!ok) toast(trf("Couldn't open {app}.", {app: I18N.name(name || id)}));
      });
    };
  }

  function tvSettings() {
    TV.settings(function (ok) { if (!ok) toast(tr("Couldn't open TV Settings")); });
  }

  function inputByKey(key) {
    for (var i = 0; i < inputs.length; i++) if (inputs[i].key === key) return inputs[i];
    return null;
  }
  function orderedInputs() {
    var order = Prefs.inputsOrder(), hidden = Prefs.inputsHidden(), out = [], placed = {}, i, x;
    if (order) {
      for (i = 0; i < order.length; i++) {
        x = inputByKey(order[i]);
        if (x && !placed[x.key] && hidden.indexOf(x.key) < 0) { out.push(x); placed[x.key] = true; }
      }
    }
    for (i = 0; i < inputs.length; i++) {
      x = inputs[i];
      if (!placed[x.key] && hidden.indexOf(x.key) < 0) { out.push(x); placed[x.key] = true; }
    }
    return out;
  }
  function boxLabel() {
    var want = Prefs.boxInput(), box = inputByKey(want);
    return box && box.key !== 'live' && box.label ? box.label : tr('Live TV');
  }

  // MARK: - Focus Sync

  function focusModalRows(sheetId, idx, on) {
    var rows = $(sheetId).querySelectorAll('.row');
    for (var i = 0; i < rows.length; i++) setClass(rows[i], 'focus', on && i === idx);
    var holder = $(sheetId).querySelector('.rows');
    if (!holder) return;
    var first = Math.max(0, Math.min(rows.length - 7, idx - 3));
    slideY(holder, -first * 76);
  }

  function updateWideFocus(container, rowsList, curRow, curCol, active) {
    slideY(container, -Math.max(0, curRow - 1) * 332);
    var rEls = container.children;
    for (var r = 0; r < rEls.length; r++) {
      var track = rEls[r].children[1];
      var sc = r === curRow ? Math.max(0, curCol - 3) : 0;
      slideX(track, -sc * 380);
      var tiles = track.children;
      for (var c = 0; c < tiles.length; c++) {
        setClass(tiles[c], 'focus', active && r === curRow && c === curCol);
      }
    }
  }

  function updateFocus() {
    // Top-Bar
    var screens = visibleScreens();
    var tabs = $('nav-tabs').children;
    for (var t = 0; t < tabs.length; t++) {
      setClass(tabs[t], 'active', screens[t] === st.screen);
      setClass(tabs[t], 'focus', st.zone === Z.TOP && st.topIdx === t);
    }
    setClass($('profile-pill'), 'focus', st.zone === Z.TOP && st.topIdx === screens.length);

    // Home
    var subBtns = $('subtab-bar').children;
    for (var s = 0; s < subBtns.length; s++) {
      setClass(subBtns[s], 'focus', st.zone === Z.HOME_SUBTAB && st.subTabFocus === s);
    }
    if (st.screen === 'home') {
      updateHomeRowFocus();
      scheduleHeroFollow();
    }

    // Side-Shelf
    var shelfNodes = $('sideshelf-list').children;
    for (var sf = 0; sf < shelfNodes.length; sf++) {
      setClass(shelfNodes[sf], 'focus', st.zone === Z.SIDESHELF && st.shelfIdx === sf);
    }
    slideY($('sideshelf-list'), -Math.max(0, st.shelfIdx - 4) * 134);

    // Search
    setClass($('search-input-wrap'), 'focus', st.zone === Z.SEARCH_BAR && st.searchBarIdx === 0);
    setClass($('search-btn-go'), 'focus', st.zone === Z.SEARCH_BAR && st.searchBarIdx === 1);
    setClass($('search-btn-ai'), 'focus', st.zone === Z.SEARCH_BAR && st.searchBarIdx === 2);
    if (st.screen === 'search') updateWideFocus($('search-results'), searchRowsData, st.searchRow, st.searchCol, st.zone === Z.SEARCH_ROWS);

    // Apps
    if (st.screen === 'apps') {
      var cells = $('apps-grid').children;
      for (var a = 0; a < cells.length; a++) setClass(cells[a], 'focus', st.zone === Z.APPS && st.appsIdx === a);
      var rowA = Math.floor(st.appsIdx / 5);
      slideY($('apps-grid'), -Math.max(0, rowA - 2) * 244);
    }

    // Gaming & Smart-Home
    if (st.screen === 'gaming') updateWideFocus($('gaming-rows'), gamingRowsData, st.gamingRow, st.gamingCol, st.zone === Z.GAMING);
    if (st.screen === 'smarthome') updateWideFocus($('smarthome-rows'), smartHomeRowsData, st.shRow, st.shCol, st.zone === Z.SMARTHOME);

    // Two-Panel Settings
    if (st.screen === 'settings') {
      var cats = $('settings-left-list').querySelectorAll('.set-cat-row');
      for (var ci = 0; ci < cats.length; ci++) {
        var isSel = ci === st.setCatIdx;
        setClass(cats[ci], 'selected', isSel);
        setClass(cats[ci], 'focus', st.zone === Z.SETTINGS_LEFT && isSel);
      }
      var rRows = $('settings-right-rows').children;
      for (var ri = 0; ri < rRows.length; ri++) {
        setClass(rRows[ri], 'focus', st.zone === Z.SETTINGS_RIGHT && ri === st.setRowIdx);
      }
      var firstR = Math.max(0, Math.min(rRows.length - 8, st.setRowIdx - 4));
      slideY($('settings-right-rows'), -firstR * 90);
    }

    // Modals
    if (st.zone === Z.MODAL_CONTEXT) focusModalRows('context-modal', st.modalIdx, true);
    if (st.zone === Z.MODAL_INPUT) focusModalRows('input-modal', st.modalIdx, true);
    if (st.zone === Z.EDIT) focusModalRows('shelfedit', st.editIdx, true);
    if (st.zone === Z.LANG) {
      focusModalRows('langpick', st.langIdx, true);
      if (langHint) langHint.textContent = I18N.digits((st.langIdx + 1) + ' / ' + languageChoices().length) + '  ·  ' + tr('OK picks, Back cancels.');
    }
    if (st.zone === Z.CITY) focusModalRows('citypick', st.cityIdx, true);
    if (st.zone === Z.KEY) focusModalRows('keypick', st.keyIdx, true);
  }

  // MARK: - D-Pad Navigation, Activate, Hold-OK & Back

  function move(dx, dy) {
    if (rtl && (st.zone === Z.TOP || st.zone === Z.HOME_SUBTAB || st.zone === Z.HOME_ROWS || st.zone === Z.APPS || st.zone === Z.GAMING || st.zone === Z.SMARTHOME || st.zone === Z.SEARCH_ROWS || st.zone === Z.EDIT)) {
      dx = -dx;
    }
    var screens = visibleScreens();
    switch (st.zone) {
      case Z.TOP:
        if (dx) {
          st.topIdx = Math.max(0, Math.min(screens.length, st.topIdx + dx));
          if (st.topIdx < screens.length && screens[st.topIdx] !== st.screen) goScreen(screens[st.topIdx], true);
        } else if (dy > 0) {
          goScreen(st.screen, false);
          return;
        }
        break;
      case Z.HOME_SUBTAB:
        if (dx) st.subTabFocus = Math.max(0, Math.min(1, st.subTabFocus + dx));
        else if (dy < 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('home'); }
        else if (dy > 0 && homeRowsData.length) st.zone = Z.HOME_ROWS;
        break;
      case Z.HOME_ROWS:
        if (dx < 0 && st.homeCol === 0) { openSideShelf(); return; }
        if (dx) {
          var len = homeRowsData[st.homeRow] ? homeRowsData[st.homeRow].items.length : 0;
          st.homeCol = Math.max(0, Math.min(len - 1, st.homeCol + dx));
          if (homeRowsData[st.homeRow]) rowCols[homeRowsData[st.homeRow].id] = st.homeCol;
        } else if (dy < 0) {
          if (st.homeRow === 0) { st.zone = Z.HOME_SUBTAB; st.subTabFocus = st.subTab === 'movie' ? 1 : 0; }
          else {
            st.homeRow--;
            st.homeCol = Math.min(rowCols[homeRowsData[st.homeRow].id] || 0, homeRowsData[st.homeRow].items.length - 1);
          }
        } else if (dy > 0 && st.homeRow < homeRowsData.length - 1) {
          st.homeRow++;
          st.homeCol = Math.min(rowCols[homeRowsData[st.homeRow].id] || 0, homeRowsData[st.homeRow].items.length - 1);
        }
        break;
      case Z.SIDESHELF:
        if (dy) st.shelfIdx = Math.max(0, Math.min(shelf.length - 1, st.shelfIdx + dy));
        else if (dx > 0) { closeSideShelf(); return; }
        break;
      case Z.SEARCH_BAR:
        if (dx) st.searchBarIdx = Math.max(0, Math.min(2, st.searchBarIdx + dx));
        else if (dy < 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('search'); }
        else if (dy > 0 && searchRowsData.length) st.zone = Z.SEARCH_ROWS;
        break;
      case Z.SEARCH_ROWS:
        if (dx) {
          var slen = searchRowsData[st.searchRow] ? searchRowsData[st.searchRow].items.length : 0;
          st.searchCol = Math.max(0, Math.min(slen - 1, st.searchCol + dx));
        } else if (dy < 0) {
          if (st.searchRow === 0) st.zone = Z.SEARCH_BAR;
          else { st.searchRow--; st.searchCol = Math.min(st.searchCol, searchRowsData[st.searchRow].items.length - 1); }
        } else if (dy > 0 && st.searchRow < searchRowsData.length - 1) {
          st.searchRow++; st.searchCol = Math.min(st.searchCol, searchRowsData[st.searchRow].items.length - 1);
        }
        break;
      case Z.APPS:
        if (dy < 0 && st.appsIdx < 5) { st.zone = Z.TOP; st.topIdx = screens.indexOf('apps'); }
        else {
          var nextA = st.appsIdx + dx + dy * 5;
          if (nextA >= 0 && nextA < apps.length && (dx === 0 || Math.floor(nextA / 5) === Math.floor(st.appsIdx / 5))) st.appsIdx = nextA;
        }
        break;
      case Z.GAMING:
        if (dx) st.gamingCol = Math.max(0, Math.min((gamingRowsData[st.gamingRow] ? gamingRowsData[st.gamingRow].items.length : 1) - 1, st.gamingCol + dx));
        else if (dy < 0) {
          if (st.gamingRow === 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('gaming'); }
          else { st.gamingRow--; st.gamingCol = Math.min(st.gamingCol, gamingRowsData[st.gamingRow].items.length - 1); }
        } else if (dy > 0 && st.gamingRow < gamingRowsData.length - 1) {
          st.gamingRow++; st.gamingCol = Math.min(st.gamingCol, gamingRowsData[st.gamingRow].items.length - 1);
        }
        break;
      case Z.SMARTHOME:
        if (dx) st.shCol = Math.max(0, Math.min((smartHomeRowsData[st.shRow] ? smartHomeRowsData[st.shRow].items.length : 1) - 1, st.shCol + dx));
        else if (dy < 0) {
          if (st.shRow === 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('smarthome'); }
          else { st.shRow--; st.shCol = Math.min(st.shCol, smartHomeRowsData[st.shRow].items.length - 1); }
        } else if (dy > 0 && st.shRow < smartHomeRowsData.length - 1) {
          st.shRow++; st.shCol = Math.min(st.shCol, smartHomeRowsData[st.shRow].items.length - 1);
        }
        break;
      case Z.SETTINGS_LEFT:
        if (dy < 0 && st.setCatIdx === 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('settings'); }
        else if (dy) {
          st.setCatIdx = Math.max(0, Math.min(settingsTree.length - 1, st.setCatIdx + dy));
          st.setRowIdx = 0;
          renderSettingsRight();
        } else if (dx > 0) {
          st.zone = Z.SETTINGS_RIGHT;
          st.setRowIdx = 0;
        }
        break;
      case Z.SETTINGS_RIGHT:
        var curCat = settingsTree[st.setCatIdx];
        if (dy < 0 && st.setRowIdx === 0) { st.zone = Z.TOP; st.topIdx = screens.indexOf('settings'); }
        else if (dy && curCat) st.setRowIdx = Math.max(0, Math.min(curCat.rows.length - 1, st.setRowIdx + dy));
        else if (dx < 0) st.zone = Z.SETTINGS_LEFT;
        else if (dx > 0 && curCat && curCat.rows[st.setRowIdx] && curCat.rows[st.setRowIdx].step) {
          curCat.rows[st.setRowIdx].step(1);
          if (st.screen === 'settings' && !modalOpen()) renderSettings();
        }
        break;
      case Z.MODAL_CONTEXT:
        if (contextModel) {
          if (dy) st.modalIdx = Math.max(0, Math.min(contextModel.rows.length - 1, st.modalIdx + dy));
          else if (dx && contextModel.rows[st.modalIdx] && contextModel.rows[st.modalIdx].adjust) {
            contextModel.rows[st.modalIdx].adjust(dx);
            renderContextModal();
          }
        }
        break;
      case Z.MODAL_INPUT:
        if (dy) st.modalIdx = Math.max(0, Math.min(2, st.modalIdx + dy));
        break;
      case Z.EDIT:
        var eRows = editKind === 'inputs' ? inputsEditList().rows : editList().rows;
        if (dy) st.editIdx = Math.max(0, Math.min(eRows.length - 1, st.editIdx + dy));
        else if (dx) { if (editKind === 'inputs') editInputs(dx); else editShelf(dx); return; }
        break;
      case Z.LANG:
        if (dy) st.langIdx = Math.max(0, Math.min(languageChoices().length - 1, st.langIdx + dy));
        break;
      case Z.CITY:
        if (dy) st.cityIdx = Math.max(0, Math.min(1 + cityFound.length, st.cityIdx + dy));
        break;
      case Z.KEY:
        if (dy) st.keyIdx = Math.max(0, Math.min((keyAsking ? 1 : (Prefs.tmdbKeyOnTV() ? 2 : 1)), st.keyIdx + dy));
        break;
    }
    updateFocus();
  }

  function activate() {
    var screens = visibleScreens();
    switch (st.zone) {
      case Z.TOP:
        if (st.topIdx === screens.length) { $('profile-pill').onclick(); return; }
        if (screens[st.topIdx]) goScreen(screens[st.topIdx], false);
        return;
      case Z.HOME_SUBTAB:
        setHomeSubTab(st.subTabFocus === 1 ? 'movie' : 'tv');
        return;
      case Z.HOME_ROWS:
        openMediaPick(focusedHomePick());
        return;
      case Z.SIDESHELF:
        if (shelf[st.shelfIdx]) { launcher(shelf[st.shelfIdx].id, shelf[st.shelfIdx].title)(); closeSideShelf(); }
        return;
      case Z.SEARCH_BAR:
        if (st.searchBarIdx === 0) $('search-input').focus();
        else if (st.searchBarIdx === 1) runUnifiedSearch($('search-input').value, false);
        else runUnifiedSearch($('search-input').value, true);
        return;
      case Z.SEARCH_ROWS:
        var sRow = searchRowsData[st.searchRow];
        if (sRow && sRow.items[st.searchCol]) activateSearchItem(sRow.items[st.searchCol]);
        return;
      case Z.APPS:
        if (apps[st.appsIdx]) launcher(apps[st.appsIdx].id, apps[st.appsIdx].title)();
        return;
      case Z.GAMING:
        var gRow = gamingRowsData[st.gamingRow], gItem = gRow && gRow.items[st.gamingCol];
        if (gItem) { if (gItem.run) gItem.run(); else if (gItem.appId) launcher(gItem.appId, gItem.title)(); }
        return;
      case Z.SMARTHOME:
        var shRow = smartHomeRowsData[st.shRow], shItem = shRow && shRow.items[st.shCol];
        if (shItem && shItem.run) shItem.run();
        return;
      case Z.SETTINGS_LEFT:
        st.zone = Z.SETTINGS_RIGHT; st.setRowIdx = 0; updateFocus();
        return;
      case Z.SETTINGS_RIGHT:
        var cat = settingsTree[st.setCatIdx], r = cat && cat.rows[st.setRowIdx];
        if (r && r.step) { r.step(1); if (st.screen === 'settings' && !modalOpen()) { renderSettings(); updateFocus(); } }
        return;
      case Z.MODAL_CONTEXT:
        if (contextModel && contextModel.rows[st.modalIdx]) {
          var cr = contextModel.rows[st.modalIdx];
          if (cr.run) cr.run();
          else if (cr.adjust) { cr.adjust(1); renderContextModal(); updateFocus(); }
        }
        return;
      case Z.MODAL_TRAKT:
        TraktClient.cancelDeviceAuth(); closeModals();
        return;
      case Z.MODAL_INPUT:
        if (st.modalIdx === 0) { var f = $('input-modal').querySelector('input'); if (f) f.focus(); }
        else if (st.modalIdx === 1) commitInputModal();
        else closeModals();
        return;
      case Z.EDIT:
        if (editKind === 'inputs') editInputs(0); else editShelf(0);
        return;
      case Z.LANG:
        closeLanguages(true);
        return;
      case Z.CITY:
        if (st.cityIdx === 0) { var cf = $('citypick').querySelector('input'); if (cf) cf.focus(); }
        else if (st.cityIdx === 1) searchCity();
        else closeCities(true);
        return;
      case Z.KEY:
        if (keyAsking) { if (st.keyIdx === 1) { Prefs.set('tmdbKey', undefined); closeKey(); Data.keyChanged(); } else { keyAsking = false; renderKey(); updateFocus(); } }
        else if (st.keyIdx === 0) { var kf = $('keypick').querySelector('input'); if (kf) kf.focus(); }
        else if (st.keyIdx === 1) saveKey();
        else { keyAsking = true; st.keyIdx = 0; renderKey(); updateFocus(); }
        return;
      case Z.ABOUT:
        closeModals();
        return;
    }
  }

  function holdOk() {
    if (st.zone === Z.HOME_ROWS) openMediaContextMenu(focusedHomePick());
    else if (st.zone === Z.APPS && apps[st.appsIdx]) openAppContextMenu(apps[st.appsIdx]);
    else if (st.zone === Z.SMARTHOME) {
      var shRow = smartHomeRowsData[st.shRow], shItem = shRow && shRow.items[st.shCol];
      if (shItem && shItem.hold) shItem.hold();
    }
  }

  function back() {
    if (st.zone === Z.LANG) { closeLanguages(false); return; }
    if (st.zone === Z.CITY) { closeCities(false); return; }
    if (st.zone === Z.KEY) { if (keyAsking) { keyAsking = false; renderKey(); updateFocus(); } else closeKey(); return; }
    if (modalOpen()) { closeModals(); return; }
    if (isSideShelfOpen()) { closeSideShelf(); return; }
    if (st.zone === Z.SETTINGS_RIGHT) { st.zone = Z.SETTINGS_LEFT; updateFocus(); return; }
    if (st.zone === Z.TOP) { goScreen(st.screen, false); return; }
    if (st.screen !== 'home') { goScreen('home', false); return; }
    if (st.homeRow > 0 || st.homeCol > 0) { st.homeRow = 0; st.homeCol = 0; updateFocus(); return; }
    TV.back();
  }

  // MARK: - Supabase Cloud Remote Command Dispatcher

  function broadcastTvState() {
    if (!SupabaseService.isRemoteConnected()) return;
    var prof = SupabaseService.getActiveProfile();
    SupabaseService.sendStateSnapshot({
      screen: st.screen,
      subTab: st.subTab,
      sideShelfOpen: isSideShelfOpen(),
      profile: prof.display_name,
      darkMode: hasClass(body, 'night'),
      visibleScreens: visibleScreens()
    });
  }

  function handleRemoteCommand(cmd) {
    if (!cmd || typeof cmd !== 'object') return;
    poke(false);
    var type = cmd.type || 'key';
    if (type === 'ping') { broadcastTvState(); return; }
    if (type === 'key') {
      var k = String(cmd.key || '').toLowerCase();
      if (k === 'left') move(-1, 0);
      else if (k === 'right') move(1, 0);
      else if (k === 'up') move(0, -1);
      else if (k === 'down') move(0, 1);
      else if (k === 'ok') activate();
      else if (k === 'hold_ok' || k === 'menu') holdOk();
      else if (k === 'back') back();
      else if (k === 'home') goScreen('home', false);
      else if (k === 'search') goScreen('search', false);
      else if (k === 'apps') goScreen('apps', false);
      else if (k === 'gaming') goScreen('gaming', false);
      else if (k === 'smarthome') goScreen('smarthome', false);
      else if (k === 'settings') goScreen('settings', false);
      else if (k === 'sideshelf') { if (isSideShelfOpen()) closeSideShelf(); else openSideShelf(); }
      else if (k === 'subtab_tv') setHomeSubTab('tv');
      else if (k === 'subtab_movie') setHomeSubTab('movie');
      else if (k === 'theme') { cycle('theme', ['dark', 'light', 'auto'], Prefs.theme(), 1); checkNight(); }
      broadcastTvState();
    } else if (type === 'text' && cmd.text) {
      goScreen('search', false);
      $('search-input').value = String(cmd.text);
      runUnifiedSearch(String(cmd.text), !!cmd.ai);
    } else if (type === 'launch' && cmd.appId) {
      launcher(String(cmd.appId), cmd.title || String(cmd.appId))();
    } else if (type === 'smarthome') {
      if (cmd.routine) SmartHomeClient.executeRoutine(cmd.routine, function (ok, msg) { toast(msg); renderSmartHome(); });
      else if (cmd.deviceId) SmartHomeClient.toggleDevice(cmd.deviceId, function (ok, msg) { toast(msg); renderSmartHome(); });
    } else if (type === 'profile' && cmd.slug) {
      var p = SupabaseService.switchProfile(cmd.slug);
      toast('Profile: ' + p.display_name);
      renderTopBar();
      Data.syncAccounts(function () { renderHome(); });
      broadcastTvState();
    }
  }

  function reconnectSupabase() {
    if (!SupabaseService.isConfigured()) {
      SupabaseService.disconnectRemote();
      CloudSyncService.stopPeriodic();
      return;
    }
    var appIds = apps.map(function (a) { return a.id; });
    SupabaseService.fetchProfiles(function () {
      renderTopBar();
      CloudSyncService.syncNow('boot', function () {
        pickShelfApps();
        renderAll();
      }, appIds);
      CloudSyncService.startPeriodic();
    });
    if (tvAboutInfo) SupabaseService.registerTvDevice(tvAboutInfo);
    if (Prefs.cloudRemoteEnabled()) {
      SupabaseService.connectRemote(handleRemoteCommand, function () {
        renderTopBar();
        if (st.screen === 'settings') renderSettings();
      });
    } else {
      SupabaseService.disconnectRemote();
    }
  }

  // MARK: - Toast, Night, Dim & Screensavers

  var toastTimer = 0;
  function toast(text) {
    if (!text) return;
    var t = $('toast');
    t.textContent = text;
    setClass(t, 'on', true);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { setClass(t, 'on', false); }, 5000);
  }

  var lastNight = null;
  function checkNight() {
    var night = Data.isNight(Date.now());
    setClass(body, 'night', night);
    if (night === lastNight) return;
    lastNight = night;
    document.documentElement.style.background = night ? '#161826' : '#F4F6FA';
    try { localStorage.setItem('soartv.night', night ? 'true' : 'false'); } catch (e) {}
  }

  function dimLevel() {
    var v = Prefs.dim();
    if (v === 'auto') { var h = new Date().getHours(); return h >= 22 || h < 7 ? 2 : 0; }
    return v === 'low' ? 1 : v === 'medium' ? 2 : v === 'high' ? 3 : 0;
  }
  function applyDim() {
    var level = dimLevel();
    $('dim').className = 'layer' + (level ? ' d' + level : '');
  }

  var saverOn = false, ambientOn = false, idleAt = Date.now(), saverIndex = 0, saverTimer = 0, saverPick = null, ambientTimer = 0;
  var SAVER_AFTER = 5 * 60000;

  function saverPhotos() {
    return Data.showcase().filter(function (p) { return !!(p.backdrop || p.backdropUrl); });
  }
  function showSaver() {
    var photos = saverPhotos();
    saverOn = true; saverPick = null;
    var s = $('saver'); s.innerHTML = '';
    var a = el('div', 'photo', s), b = el('div', 'photo', s);
    el('div', 'shade', s);
    var clock = el('div', 'clock', s);
    el('div', 'time', clock); el('div', 'date', clock); el('div', 'title', clock);
    var hint = el('div', 'hint', clock);
    var front = a;
    function next() {
      if (!saverOn) return;
      var p = photos.length ? photos[saverIndex++ % photos.length] : null;
      var back = front === a ? b : a;
      if (p) {
        back.style.backgroundImage = 'url("' + Data.backdropUrlOf(p) + '")';
        back.style.webkitTransform = back.style.transform = 'scale(1.0)';
        clock.children[2].textContent = I18N.name(p.title);
        saverPick = p;
        var target = Prefs.saverWatch() ? pickTarget(p) : null;
        hint.textContent = target ? trf('Press OK to watch on {app} · any other button to close', {app: I18N.name(target.app.title)}) : '';
        setTimeout(function () {
          setClass(back, 'on', true); setClass(front, 'on', false);
          back.style.webkitTransform = back.style.transform = 'scale(1.06)';
          front = back;
        }, 60);
      }
      var d = new Date();
      clock.children[0].textContent = formatDate('format|h:mm', d.getTime());
      clock.children[1].textContent = formatDate('format|EEEE, MMMM d', d.getTime());
      saverTimer = setTimeout(next, 12000);
    }
    setClass(s, 'on', true);
    next();
  }
  function hideSaver() { saverOn = false; clearTimeout(saverTimer); setClass($('saver'), 'on', false); }

  function showAmbient() {
    if (ambientOn) return;
    var a = $('ambient'); a.innerHTML = '';
    var shift = el('div', 'shift', a);
    el('div', 'clock', shift); el('div', 'date', shift);
    var side = el('div', 'side', shift);
    el('div', 'temp', side); el('div', 'cond', side);
    ambientOn = true; setClass(a, 'on', true);
    ambientTimer = setInterval(tickAmbient, 30000);
    tickAmbient();
  }
  function tickAmbient() {
    if (!ambientOn) return;
    var a = $('ambient'), d = new Date(), w = Prefs.weather() ? Data.weather : null;
    a.querySelector('.clock').textContent = formatDate('format|h:mm', d.getTime());
    a.querySelector('.date').textContent = formatDate('format|EEEE, MMMM d', d.getTime());
    a.querySelector('.temp').textContent = w ? Data.degrees(w.temp) : '';
    a.querySelector('.cond').textContent = w ? Data.condition(w.code, w.isDay) : '';
    var phase = 2 * Math.PI * (d.getTime() / 1000 % 600) / 600;
    var shift = a.querySelector('.shift');
    shift.style.webkitTransform = shift.style.transform =
      'translate(' + Math.round(40 * Math.sin(phase)) + 'px,' + Math.round(26 * Math.sin(2 * phase + 0.7)) + 'px)';
  }
  function hideAmbient() { ambientOn = false; clearInterval(ambientTimer); setClass($('ambient'), 'on', false); }

  function poke(ok) {
    idleAt = Date.now();
    if (ambientOn) { hideAmbient(); return true; }
    if (!saverOn) return false;
    var watched = saverPick;
    hideSaver();
    if (ok && Prefs.saverWatch() && watched) openMediaPick(watched);
    return true;
  }

  // MARK: - Keyboard & Remote Input (with Hold-OK Detection)

  var okDownAt = 0, okHoldFired = false, okHoldTimer = 0;

  function activeInput() {
    var a = document.activeElement;
    return a && a.tagName === 'INPUT' ? a : null;
  }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (poke(k === TV.keys.ok)) { e.preventDefault(); return; }
    var inp = activeInput();
    if (inp) {
      if (k === TV.keys.ok || k === 65376) {
        if (st.zone === Z.MODAL_INPUT) commitInputModal();
        else if (st.zone === Z.CITY) searchCity();
        else if (st.zone === Z.KEY) saveKey();
        else if (st.screen === 'search') runUnifiedSearch(inp.value, false);
        e.preventDefault();
      } else if (k === TV.keys.back || k === 27 || k === 65385) {
        inp.blur(); updateFocus(); e.preventDefault();
      } else if (k === TV.keys.down || k === TV.keys.up) {
        inp.blur(); move(0, k === TV.keys.up ? -1 : 1); e.preventDefault();
      }
      return;
    }
    if (k === TV.keys.ok) {
      if (!okDownAt) {
        okDownAt = Date.now();
        okHoldFired = false;
        clearTimeout(okHoldTimer);
        okHoldTimer = setTimeout(function () {
          if (okDownAt) { okHoldFired = true; holdOk(); }
        }, 440);
      }
      e.preventDefault();
      return;
    }
    if (k === TV.keys.left) move(-1, 0);
    else if (k === TV.keys.right) move(1, 0);
    else if (k === TV.keys.up) move(0, -1);
    else if (k === TV.keys.down) move(0, 1);
    else if (k === TV.keys.back || k === 27 || k === 8) back();
    else if (k === TV.keys.menu || k === TV.keys.yellow || k === 77) holdOk();
    else return;
    e.preventDefault();
  });

  document.addEventListener('keyup', function (e) {
    if (e.keyCode === TV.keys.ok && okDownAt) {
      clearTimeout(okHoldTimer);
      var wasHold = okHoldFired;
      okDownAt = 0; okHoldFired = false;
      if (!wasHold) activate();
      e.preventDefault();
    }
  });

  document.addEventListener('mousemove', function () { poke(false); });

  // MARK: - Startup & Lifecycle

  var HIDE = (CONFIG.hideApps || []).map(function (s) { return s.toLowerCase(); });
  function isAppHidden(app) {
    var id = (app.id || '').toLowerCase(), title = (app.title || '').toLowerCase();
    for (var i = 0; i < HIDE.length; i++) if (id.indexOf(HIDE[i]) >= 0 || title.indexOf(HIDE[i]) >= 0) return true;
    return false;
  }

  function loadApps(done) {
    TV.returnHome(Prefs.homeReturns());
    TV.apps(function (list) {
      apps = (list || []).filter(function (a) { return !isAppHidden(a); });
      Data.setApps(apps);
      pickShelfApps();
      TV.inputs(function (list2) {
        inputs = list2 || [];
        if (done) done();
      });
    });
  }

  function renderAll() {
    renderTopBar();
    renderHome();
    if (st.screen === 'search') renderSearch();
    if (st.screen === 'apps') renderAppsScreen();
    if (st.screen === 'gaming') renderGaming();
    if (st.screen === 'smarthome') renderSmartHome();
    if (st.screen === 'settings') renderSettings();
    checkNight();
    updateFocus();
  }

  function resting() { return TV.name === 'tizen' && document.hidden; }

  function reopened() {
    if (window.PalmSystem && window.PalmSystem.activate) window.PalmSystem.activate();
    hideSaver(); hideAmbient(); closeModals(); closeSideShelf(true);
    st.homeRow = 0; st.homeCol = 0;
    loadApps(function () { goScreen('home', false); Data.refresh(); });
  }

  Prefs.onChange(function (prefName) {
    if (typeof CloudSyncService !== 'undefined') CloudSyncService.handlePrefMutation(prefName);
  });
  CloudSyncService.setOnApplied(function () {
    I18N.set(Prefs.language());
    direction();
    I18N.setClock(Prefs.clockFormat());
    pickShelfApps();
    renderAll();
  });

  Data.onChange(function () {
    renderTopBar();
    if (st.screen === 'home') renderHome();
    checkNight();
    updateFocus();
  });

  window.addEventListener('resize', fit);
  document.addEventListener('webOSRelaunch', reopened);
  window.addEventListener('appcontrol', reopened);
  document.addEventListener('visibilitychange', function () {
    hideSaver(); hideAmbient();
    if (document.hidden) {
      CloudSyncService.stopPeriodic();
      if (TV.name === 'tizen') SupabaseService.disconnectRemote();
      return;
    }
    idleAt = Date.now();
    TV.noteChannel();
    CloudSyncService.startPeriodic();
    CloudSyncService.syncNow('resume', null, apps.map(function (a) { return a.id; }));
    if (TV.name === 'tizen') { renderClock(); checkNight(); applyDim(); reconnectSupabase(); }
    loadApps(function () { renderAll(); Data.refresh(); });
  });

  I18N.set(Prefs.language());
  direction();
  I18N.setClock(Prefs.clockFormat());
  checkNight();
  TV.holdScreenSaver(function () { return !modalOpen() && (Prefs.saver() === 'clock' || saverPhotos().length > 0); });
  fit();
  applyDim();
  tickClock();

  loadApps(function () {
    if (TV.name === 'browser' && !Data.hasRealKey() && window.location && window.location.search.indexOf('nodemo=1') < 0) {
      Data.enablePreviewDemo();
    }
    renderAll();
    goScreen('home', false);
    Data.refresh();
    Data.syncAccounts(function () { renderHome(); updateFocus(); });
    TV.about(function (info) {
      tvAboutInfo = info;
      reconnectSupabase();
    });
  });

  setInterval(function () {
    if (resting()) return;
    checkNight();
    Data.refresh();
    applyDim();
    if (!document.hidden && !saverOn && !ambientOn && !modalOpen() && Date.now() - idleAt > SAVER_AFTER) {
      if (Prefs.saver() === 'clock') showAmbient(); else showSaver();
    }
    if (ambientOn) tickAmbient();
  }, 30000);

  window.SoarTV = {
    state: st,
    goScreen: goScreen,
    move: move,
    activate: activate,
    holdOk: holdOk,
    back: back,
    openSideShelf: openSideShelf,
    closeSideShelf: closeSideShelf,
    handleRemoteCommand: handleRemoteCommand,
    showSaver: showSaver,
    hideSaver: hideSaver,
    showAmbient: showAmbient,
    hideAmbient: hideAmbient,
    applyDim: applyDim,
    toast: toast,
    enableDemo: function () { Data.enablePreviewDemo(); renderHome(); updateFocus(); }
  };
})();
