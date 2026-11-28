/* SoarTV Modular Reels Bridge — TikTok Web (tiktok.com/messages)
 * Runs strictly inside the user's own logged-in on-TV residential WebView (:reels).
 * Supports both native 1-on-1 chats AND group chats surfaced from TikTok Web's IM IndexedDB/Redux/DOM.
 */
(function () {
  if (window.__SoarTvTtBridge) return;

  var intercepted = {
    conversations: {},
    messagesByConv: {}
  };

  function emit(callbackId, ok, data, error) {
    try {
      if (window.SoarTvBridge && typeof window.SoarTvBridge.onBridgeResult === 'function') {
        window.SoarTvBridge.onBridgeResult(
          String(callbackId),
          JSON.stringify({ ok: !!ok, data: data || null, error: error ? String(error) : '' })
        );
      }
    } catch (e) {}
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function hasSession() {
    return document.cookie.indexOf('sessionid=') !== -1 ||
      document.cookie.indexOf('sid_tt=') !== -1 ||
      document.cookie.indexOf('uid_tt=') !== -1;
  }

  function getSelfId() {
    try {
      var m = document.cookie.match(/(?:^|; )uid_tt=([^;]*)/);
      if (m && m[1]) return decodeURIComponent(m[1]);
      if (window.__INIT_PROPS__ && window.__INIT_PROPS__['/messages']) {
        var p = window.__INIT_PROPS__['/messages'];
        if (p && p.user && p.user.uid) return String(p.user.uid);
      }
    } catch (e) {}
    return '';
  }

  // Scan IndexedDB stores created by TikTok Web IM SDK (ByteDance IM persists both 1-on-1 and group threads locally)
  async function readImIndexedDb() {
    var result = { dbs: [], conversations: [], messages: [] };
    if (!window.indexedDB || typeof window.indexedDB.databases !== 'function') {
      return result;
    }
    try {
      var dbs = await window.indexedDB.databases();
      for (var i = 0; i < dbs.length; i++) {
        var name = dbs[i] && dbs[i].name;
        if (!name) continue;
        result.dbs.push(name);
        if (!/im|message|chat|conv|byted|tt/i.test(name)) continue;
        await new Promise(function (resolve) {
          try {
            var req = window.indexedDB.open(name);
            req.onerror = function () { resolve(); };
            req.onsuccess = function () {
              var db = req.result;
              try {
                var storeNames = Array.from(db.objectStoreNames || []);
                var pending = storeNames.length;
                if (pending === 0) { db.close(); resolve(); return; }
                storeNames.forEach(function (sn) {
                  try {
                    var tx = db.transaction(sn, 'readonly');
                    var st = tx.objectStore(sn);
                    var allReq = st.getAll();
                    allReq.onsuccess = function () {
                      var rows = allReq.result || [];
                      for (var r = 0; r < rows.length; r++) {
                        var row = rows[r];
                        if (!row || typeof row !== 'object') continue;
                        if (row.conversationId || row.conversation_id || row.shortId) {
                          if (row.content || row.serverId || row.msgId || row.messageId) {
                            result.messages.push(row);
                          } else {
                            result.conversations.push(row);
                          }
                        }
                      }
                      if (--pending <= 0) { db.close(); resolve(); }
                    };
                    allReq.onerror = function () { if (--pending <= 0) { db.close(); resolve(); } };
                  } catch (e) {
                    if (--pending <= 0) { db.close(); resolve(); }
                  }
                });
              } catch (e) {
                try { db.close(); } catch (e2) {}
                resolve();
              }
            };
          } catch (e) { resolve(); }
        });
      }
    } catch (e) {}
    return result;
  }

  function extractThreadsFromDom() {
    var threads = [];
    var selectors = [
      '[data-e2e="chat-list-item"]',
      '[class*="DivItemWrapper"]',
      '[class*="ConversationItem"]',
      'a[href*="/messages?"]'
    ];
    var seen = {};
    for (var s = 0; s < selectors.length; s++) {
      var nodes = document.querySelectorAll(selectors[s]);
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var nicknameEl = el.querySelector('[data-e2e="chat-nickname"], [class*="PInfoNickname"], [class*="Nickname"], p, span');
        var title = (nicknameEl ? nicknameEl.textContent : el.textContent || '').trim().split('\n')[0].trim();
        if (!title || title.length > 80) continue;
        var tid = el.getAttribute('data-conversation-id') ||
          el.getAttribute('data-thread-id') ||
          ('dom:' + title.toLowerCase());
        if (seen[tid]) continue;
        seen[tid] = true;
        threads.push({
          source_id: 'tiktok',
          thread_id: tid,
          title: title,
          is_group: false,
          participants: [{ sender_id: tid, raw_username: title, display_name: title }]
        });
      }
      if (threads.length > 0) break;
    }
    return threads;
  }

  function parseImMessageRow(row, threadId) {
    var msgId = String(row.serverId || row.server_id || row.msgId || row.messageId || row.clientId || '');
    if (!msgId) return null;
    var created = Number(row.createdAt || row.createTime || row.create_time || row.timestamp || 0);
    if (created > 0 && created < 1e11) created = created * 1000;
    var senderId = String(row.sender || row.senderId || row.sender_id || row.fromUid || '');
    var rawContent = row.content;
    var parsed = null;
    if (typeof rawContent === 'string' && rawContent.trim().startsWith('{')) {
      try { parsed = JSON.parse(rawContent); } catch (e) {}
    } else if (rawContent && typeof rawContent === 'object') {
      parsed = rawContent;
    }

    var shareUrl = '';
    var caption = '';
    var thumb = '';
    var msgText = '';
    var variants = [];

    if (parsed) {
      var awemeId = String(parsed.itemId || parsed.aweme_id || parsed.video_id || (parsed.aweme && parsed.aweme.aweme_id) || '');
      var author = String(parsed.uid || parsed.author_username || (parsed.aweme && parsed.aweme.author && parsed.aweme.author.unique_id) || 'user');
      if (awemeId) {
        shareUrl = 'https://www.tiktok.com/@' + encodeURIComponent(author) + '/video/' + encodeURIComponent(awemeId);
      } else if (parsed.url || parsed.share_url) {
        shareUrl = String(parsed.url || parsed.share_url);
      }
      caption = String(parsed.desc || parsed.title || (parsed.aweme && parsed.aweme.desc) || '');
      if (parsed.cover_url && Array.isArray(parsed.cover_url.url_list) && parsed.cover_url.url_list.length > 0) {
        thumb = String(parsed.cover_url.url_list[0]);
      } else if (typeof parsed.cover === 'string') {
        thumb = parsed.cover;
      }
      msgText = String(parsed.text || '');
    } else if (typeof rawContent === 'string') {
      msgText = rawContent;
      var m = rawContent.match(/https?:\/\/(?:www\.|vm\.|vt\.)?tiktok\.com\/\S+/i);
      if (m) shareUrl = m[0];
    }

    if (!shareUrl && !msgText) return null;
    return {
      id: 'tiktok:' + msgId,
      source_id: 'tiktok',
      item_kind: shareUrl ? 'video' : 'text',
      thread_id: String(threadId),
      message_id: msgId,
      sender_id: senderId,
      sender_raw_name: senderId,
      sent_at_ms: created,
      share_url: shareUrl,
      direct_video_url: '',
      variants: variants,
      thumbnail_url: thumb,
      caption: caption,
      message_text: msgText
    };
  }

  function extractMessagesFromOpenDom(threadId) {
    var out = [];
    var chatItems = document.querySelectorAll('[data-e2e="chat-item"], [class*="DivChatItemContainer"], [class*="MessageItem"]');
    var baseTs = Date.now() - (chatItems.length * 60000);
    for (var i = 0; i < chatItems.length; i++) {
      var el = chatItems[i];
      var mid = el.getAttribute('data-message-id') || el.getAttribute('data-msg-id') || ('dom_' + i);
      var linkEl = el.querySelector('a[href*="/video/"], a[href*="tiktok.com"]');
      var shareUrl = linkEl ? (linkEl.href || '') : '';
      var imgEl = el.querySelector('img');
      var thumb = imgEl ? (imgEl.src || '') : '';
      var textEl = el.querySelector('[data-e2e="chat-item-text"], [class*="PText"], p');
      var msgText = textEl ? (textEl.textContent || '').trim() : '';
      var senderAttr = el.getAttribute('data-sender-id') || '';
      var isSelf = /right|self|mine|own/i.test(el.className || '');
      var senderId = senderAttr || (isSelf ? (getSelfId() || 'self') : ('peer:' + threadId));
      var senderName = isSelf ? 'You' : (threadId.replace(/^dom:/, '') || 'Member');
      if (!shareUrl && !msgText) continue;
      out.push({
        id: 'tiktok:' + mid,
        source_id: 'tiktok',
        item_kind: shareUrl ? 'video' : 'text',
        thread_id: String(threadId),
        message_id: String(mid),
        sender_id: senderId,
        sender_raw_name: senderName,
        sent_at_ms: baseTs + (i * 60000),
        share_url: shareUrl,
        direct_video_url: '',
        variants: [],
        thumbnail_url: thumb,
        caption: '',
        message_text: msgText
      });
    }
    return out;
  }

  async function selectDomThreadIfNeeded(threadId) {
    if (!threadId) return;
    var cleanTitle = threadId.indexOf('dom:') === 0 ? threadId.substring(4).toLowerCase() : '';
    var items = document.querySelectorAll('[data-e2e="chat-list-item"], [class*="DivItemWrapper"]');
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      var cid = el.getAttribute('data-conversation-id') || '';
      var txt = (el.textContent || '').toLowerCase();
      if ((cid && cid === threadId) || (cleanTitle && txt.indexOf(cleanTitle) !== -1)) {
        el.click();
        await sleep(700);
        return;
      }
    }
  }

  async function sendSingleDomMessage(text) {
    var editor = document.querySelector('[data-e2e="message-input-area"] [contenteditable="true"], [contenteditable="true"][role="textbox"], .public-DraftEditor-content, [contenteditable="true"]');
    if (!editor) return false;
    editor.focus();
    try {
      document.execCommand('selectAll', false, null);
      document.execCommand('insertText', false, text);
    } catch (e) {
      editor.textContent = text;
      editor.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
    }
    await sleep(40);
    var sendBtn = document.querySelector('[data-e2e="message-send"], button[aria-label*="Send"], svg[data-e2e="message-send"]');
    if (sendBtn) {
      sendBtn.click();
    } else {
      editor.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    }
    return true;
  }

  window.__SoarTvTtBridge = {
    getStatus: function (cbId) {
      emit(cbId, true, {
        source: 'tiktok',
        loggedIn: hasSession(),
        selfSenderId: getSelfId(),
        url: location.href
      });
    },

    getDiagnostics: async function (cbId) {
      var idb = await readImIndexedDb();
      emit(cbId, true, {
        loggedIn: hasSession(),
        url: location.href,
        indexedDbs: idb.dbs,
        idbConversationCount: idb.conversations.length,
        idbMessageCount: idb.messages.length,
        domThreadCount: extractThreadsFromDom().length
      });
    },

    listThreads: async function (cbId) {
      try {
        if (!hasSession()) {
          emit(cbId, false, null, 'not_logged_in');
          return;
        }
        var out = [];
        var seen = {};
        var idb = await readImIndexedDb();
        for (var i = 0; i < idb.conversations.length; i++) {
          var c = idb.conversations[i];
          var cid = String(c.conversationId || c.conversation_id || c.shortId || '');
          if (!cid || seen[cid]) continue;
          seen[cid] = true;
          var core = c.coreInfo || c.core_info || {};
          var isGrp = Number(c.type || c.conversationType || 0) === 2 || !!core.name;
          var title = String(core.name || c.name || c.title || (isGrp ? 'TikTok Group' : 'TikTok Chat'));
          var parts = [];
          var members = c.participants || c.members || [];
          if (Array.isArray(members)) {
            for (var m = 0; m < members.length; m++) {
              var mem = members[m];
              var mid = String(mem.userId || mem.uid || mem.id || mem);
              parts.push({
                sender_id: mid,
                raw_username: String(mem.alias || mem.nickname || mem.uniqueId || mid),
                display_name: String(mem.alias || mem.nickname || mem.uniqueId || mid)
              });
            }
          }
          out.push({
            source_id: 'tiktok',
            thread_id: cid,
            title: title,
            is_group: isGrp,
            self_sender_id: getSelfId(),
            participants: parts
          });
        }
        var domThreads = extractThreadsFromDom();
        for (var d = 0; d < domThreads.length; d++) {
          if (!seen[domThreads[d].thread_id]) {
            seen[domThreads[d].thread_id] = true;
            domThreads[d].self_sender_id = getSelfId();
            out.push(domThreads[d]);
          }
        }
        emit(cbId, true, { self_sender_id: getSelfId(), threads: out });
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    fetchMessages: async function (cbId, threadId, watermarkSentAtMs, maxItems) {
      try {
        if (!hasSession()) {
          emit(cbId, false, null, 'not_logged_in');
          return;
        }
        var items = [];
        var idb = await readImIndexedDb();
        for (var i = 0; i < idb.messages.length; i++) {
          var row = idb.messages[i];
          var cid = String(row.conversationId || row.conversation_id || '');
          if (cid && cid === String(threadId)) {
            var parsed = parseImMessageRow(row, threadId);
            if (parsed) items.push(parsed);
          }
        }
        if (items.length === 0) {
          await selectDomThreadIfNeeded(threadId);
          items = extractMessagesFromOpenDom(threadId);
        }
        emit(cbId, true, {
          source_id: 'tiktok',
          thread_id: String(threadId),
          self_sender_id: getSelfId(),
          participants: [],
          items: items
        });
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    reactEye: async function (cbId, threadId, messageId, emoji) {
      try {
        var targetEmoji = emoji || '\uD83D\uDC40';
        await selectDomThreadIfNeeded(threadId);
        var target = document.querySelector('[data-message-id="' + messageId + '"], [data-msg-id="' + messageId + '"]');
        if (!target) {
          var all = document.querySelectorAll('[data-e2e="chat-item"], [class*="DivChatItemContainer"]');
          if (all.length > 0) target = all[all.length - 1];
        }
        if (!target) {
          emit(cbId, false, null, 'message_dom_not_found');
          return;
        }
        target.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true }));
        target.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
        await sleep(220);
        var reactBtn = target.querySelector('[data-e2e*="react"], [aria-label*="React"], [aria-label*="emoji"], button[class*="React"]');
        if (!reactBtn) {
          emit(cbId, false, null, 'react_button_not_available_on_web');
          return;
        }
        reactBtn.click();
        await sleep(220);
        var choices = document.querySelectorAll('button, span, div[role="button"]');
        for (var i = 0; i < choices.length; i++) {
          if ((choices[i].textContent || '').trim() === targetEmoji) {
            choices[i].click();
            emit(cbId, true, { reacted: true, method: 'dom', messageId: String(messageId) });
            return;
          }
        }
        emit(cbId, false, null, 'eye_emoji_picker_not_found');
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    sendEyeBurst: async function (cbId, threadId, delaysMsJson, emoji) {
      try {
        var targetEmoji = emoji || '\uD83D\uDC40';
        var delays = JSON.parse(delaysMsJson || '[]');
        await selectDomThreadIfNeeded(threadId);
        var sent = 0;
        for (var i = 0; i < delays.length; i++) {
          var waitMs = Math.max(0, Number(delays[i] || 0));
          if (waitMs > 0) await sleep(waitMs);
          var ok = await sendSingleDomMessage(targetEmoji);
          if (!ok) {
            emit(cbId, false, { sent: sent }, 'composer_not_found');
            return;
          }
          sent++;
        }
        emit(cbId, true, { sent: sent });
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    }
  };
})();
