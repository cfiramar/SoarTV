/* SoarTV Modular Reels Bridge — Instagram Web (instagram.com/direct/)
 * Runs strictly inside the user's own logged-in on-TV residential WebView (:reels).
 * Never stores credentials or bypasses login/2FA/CAPTCHAs.
 */
(function () {
  if (window.__SoarTvIgBridge) return;

  function getCookie(name) {
    var m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : '';
  }

  function getAppId() {
    try {
      var scripts = document.querySelectorAll('script[type="application/json"]');
      for (var i = 0; i < scripts.length; i++) {
        var txt = scripts[i].textContent || '';
        if (txt.indexOf('ssr_qpl_app_id') !== -1) {
          var m = txt.match(/"ssr_qpl_app_id"\s*,\s*"(\d+)"/);
          if (m && m[1]) return m[1];
        }
      }
    } catch (e) {}
    return '936619743392459';
  }

  function headers(isPost) {
    var h = {
      'accept': 'application/json, text/plain, */*',
      'x-ig-app-id': getAppId(),
      'x-csrftoken': getCookie('csrftoken'),
      'x-requested-with': 'XMLHttpRequest'
    };
    if (isPost) {
      h['content-type'] = 'application/x-www-form-urlencoded';
    }
    return h;
  }

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

  function extractMediaFromItem(item) {
    var variants = [];
    var shareUrl = '';
    var thumb = '';
    var caption = '';
    var mediaObj = null;

    if (item.clip && item.clip.clip) {
      mediaObj = item.clip.clip;
    } else if (item.reel_share && item.reel_share.media) {
      mediaObj = item.reel_share.media;
    } else if (item.media_share) {
      mediaObj = item.media_share;
    } else if (item.felix_share && item.felix_share.video) {
      mediaObj = item.felix_share.video;
    } else if (item.xma_media_share && item.xma_media_share.length > 0) {
      var xma = item.xma_media_share[0];
      if (xma && xma.target_url) shareUrl = xma.target_url;
      if (xma && xma.preview_url) thumb = xma.preview_url;
      if (xma && xma.title_text) caption = xma.title_text;
      if (xma && xma.playable_url) {
        variants.push({
          url: xma.playable_url,
          width: xma.playable_width || 0,
          height: xma.playable_height || 0
        });
      }
    }

    if (mediaObj) {
      var code = mediaObj.code || '';
      if (code) {
        shareUrl = 'https://www.instagram.com/reel/' + code + '/';
      }
      if (mediaObj.caption && mediaObj.caption.text) {
        caption = mediaObj.caption.text;
      }
      if (mediaObj.image_versions2 && mediaObj.image_versions2.candidates && mediaObj.image_versions2.candidates.length > 0) {
        thumb = mediaObj.image_versions2.candidates[0].url || thumb;
      }
      if (Array.isArray(mediaObj.video_versions)) {
        for (var i = 0; i < mediaObj.video_versions.length; i++) {
          var vv = mediaObj.video_versions[i];
          if (vv && vv.url) {
            variants.push({
              url: vv.url,
              width: Number(vv.width || 0),
              height: Number(vv.height || 0)
            });
          }
        }
      }
    }

    if (!shareUrl && item.link && item.link.link_context && item.link.link_context.link_url) {
      shareUrl = item.link.link_context.link_url;
    }

    return { variants: variants, shareUrl: shareUrl, thumb: thumb, caption: caption };
  }

  function detectShareUrlInText(txt) {
    if (!txt) return '';
    var m = txt.match(/https?:\/\/(?:www\.)?(?:instagram\.com|instagr\.am|tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com|youtube\.com\/shorts|youtu\.be|x\.com|twitter\.com)\/\S+/i);
    return m ? m[0] : '';
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  window.__SoarTvIgBridge = {
    getStatus: function (cbId) {
      var loggedIn = !!getCookie('ds_user_id');
      emit(cbId, true, {
        source: 'instagram',
        loggedIn: loggedIn,
        selfSenderId: getCookie('ds_user_id') || '',
        url: location.href
      });
    },

    listThreads: async function (cbId) {
      try {
        var selfId = getCookie('ds_user_id') || '';
        if (!selfId) {
          emit(cbId, false, null, 'not_logged_in');
          return;
        }
        var resp = await fetch(
          '/api/v1/direct_v2/inbox/?persistent_badging=true&folder=0&limit=30&thread_message_limit=1',
          { method: 'GET', headers: headers(false), credentials: 'include' }
        );
        if (!resp.ok) {
          emit(cbId, false, null, 'http_' + resp.status);
          return;
        }
        var json = await resp.json();
        var threads = (json && json.inbox && Array.isArray(json.inbox.threads)) ? json.inbox.threads : [];
        var out = [];
        for (var i = 0; i < threads.length; i++) {
          var t = threads[i];
          var participants = [];
          if (Array.isArray(t.users)) {
            for (var j = 0; j < t.users.length; j++) {
              var u = t.users[j];
              participants.push({
                sender_id: String(u.pk || u.pk_id || u.id || ''),
                raw_username: String(u.username || u.full_name || ''),
                display_name: String(u.full_name || u.username || '')
              });
            }
          }
          var title = t.thread_title || '';
          if (!title && participants.length > 0) {
            title = participants.map(function (p) { return p.display_name || p.raw_username; }).join(', ');
          }
          out.push({
            source_id: 'instagram',
            thread_id: String(t.thread_id || t.thread_v2_id || ''),
            title: title || 'Instagram Chat',
            is_group: !!(t.is_group || participants.length > 1),
            self_sender_id: selfId,
            participants: participants
          });
        }
        emit(cbId, true, { self_sender_id: selfId, threads: out });
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    fetchMessages: async function (cbId, threadId, watermarkSentAtMs, maxItems) {
      try {
        var selfId = getCookie('ds_user_id') || '';
        if (!selfId) {
          emit(cbId, false, null, 'not_logged_in');
          return;
        }
        var cap = Math.max(20, Math.min(160, Number(maxItems || 80)));
        var wmMs = Number(watermarkSentAtMs || 0);
        var cursor = '';
        var userMap = {};
        var participants = [];
        var collected = [];
        var pages = 0;

        while (pages < 4 && collected.length < cap) {
          pages++;
          var url = '/api/v1/direct_v2/threads/' + encodeURIComponent(threadId) +
            '/?limit=40&visual_message_return_type=unseen' +
            (cursor ? ('&cursor=' + encodeURIComponent(cursor)) : '');
          var resp = await fetch(url, { method: 'GET', headers: headers(false), credentials: 'include' });
          if (!resp.ok) {
            emit(cbId, false, null, 'http_' + resp.status);
            return;
          }
          var json = await resp.json();
          var thread = json && json.thread;
          if (!thread) break;

          if (pages === 1 && Array.isArray(thread.users)) {
            for (var u = 0; u < thread.users.length; u++) {
              var usr = thread.users[u];
              var uid = String(usr.pk || usr.pk_id || usr.id || '');
              var uname = String(usr.full_name || usr.username || uid);
              userMap[uid] = { username: String(usr.username || ''), display: uname };
              participants.push({ sender_id: uid, raw_username: String(usr.username || ''), display_name: uname });
            }
          }
          var items = Array.isArray(thread.items) ? thread.items : [];
          if (items.length === 0) break;

          var reachedOlder = false;
          for (var k = 0; k < items.length; k++) {
            var it = items[k];
            if (!it || !it.item_id) continue;
            var tsMicros = Number(it.timestamp || 0);
            var sentAtMs = tsMicros > 1e14 ? Math.floor(tsMicros / 1000) : tsMicros;
            if (wmMs > 0 && sentAtMs > 0 && sentAtMs < wmMs - 2000) {
              reachedOlder = true;
            }
            var senderId = String(it.user_id || '');
            var uInfo = userMap[senderId];
            var senderRaw = uInfo ? (uInfo.display || uInfo.username) : (senderId === selfId ? 'You' : senderId);
            var media = extractMediaFromItem(it);
            var msgText = String(it.text || (it.link && it.link.text) || (it.reel_share && it.reel_share.text) || '');

            var textUrl = !media.shareUrl ? detectShareUrlInText(msgText) : '';
            var finalShareUrl = media.shareUrl || textUrl;
            var isMedia = media.variants.length > 0 || !!finalShareUrl;

            if (isMedia) {
              collected.push({
                id: 'instagram:' + String(it.item_id),
                source_id: 'instagram',
                item_kind: 'video',
                thread_id: String(threadId),
                message_id: String(it.item_id),
                sender_id: senderId,
                sender_raw_name: senderRaw,
                sent_at_ms: sentAtMs,
                share_url: finalShareUrl,
                direct_video_url: media.variants.length > 0 ? media.variants[0].url : '',
                video_width: media.variants.length > 0 ? media.variants[0].width : 0,
                video_height: media.variants.length > 0 ? media.variants[0].height : 0,
                variants: media.variants,
                thumbnail_url: media.thumb,
                caption: media.caption,
                message_text: msgText
              });
            } else if (it.item_type === 'text' && msgText) {
              collected.push({
                id: 'instagram:' + String(it.item_id),
                source_id: 'instagram',
                item_kind: 'text',
                thread_id: String(threadId),
                message_id: String(it.item_id),
                sender_id: senderId,
                sender_raw_name: senderRaw,
                sent_at_ms: sentAtMs,
                share_url: '',
                direct_video_url: '',
                variants: [],
                thumbnail_url: '',
                caption: '',
                message_text: msgText
              });
            }
          }

          if (reachedOlder || !thread.has_older || !thread.oldest_cursor) break;
          cursor = thread.oldest_cursor;
        }

        emit(cbId, true, {
          source_id: 'instagram',
          thread_id: String(threadId),
          self_sender_id: selfId,
          participants: participants,
          items: collected
        });
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    reactEye: async function (cbId, threadId, itemId, emoji) {
      try {
        var targetEmoji = emoji || '\uD83D\uDC40';
        var body = new URLSearchParams();
        body.append('thread_ids', '[' + JSON.stringify(String(threadId)) + ']');
        body.append('item_id', String(itemId));
        body.append('node_type', 'item');
        body.append('reaction_type', 'like');
        body.append('reaction_status', 'created');
        body.append('emoji', targetEmoji);
        body.append('client_context', String(Date.now()) + String(Math.floor(Math.random() * 10000)));

        var resp = await fetch('/api/v1/direct_v2/threads/broadcast/reaction/', {
          method: 'POST',
          headers: headers(true),
          credentials: 'include',
          body: body.toString()
        });
        if (resp.ok) {
          emit(cbId, true, { reacted: true, method: 'api', itemId: String(itemId) });
          return;
        }
        emit(cbId, false, null, 'react_http_' + resp.status);
      } catch (e) {
        emit(cbId, false, null, e && e.message ? e.message : String(e));
      }
    },

    sendEyeBurst: async function (cbId, threadId, delaysMsJson, emoji) {
      try {
        var targetEmoji = emoji || '\uD83D\uDC40';
        var delays = JSON.parse(delaysMsJson || '[]');
        if (!Array.isArray(delays) || delays.length === 0) {
          emit(cbId, true, { sent: 0 });
          return;
        }
        var sent = 0;
        for (var i = 0; i < delays.length; i++) {
          var waitMs = Math.max(0, Number(delays[i] || 0));
          if (waitMs > 0) await sleep(waitMs);
          var body = new URLSearchParams();
          body.append('action', 'send_item');
          body.append('thread_ids', '[' + JSON.stringify(String(threadId)) + ']');
          body.append('text', targetEmoji);
          body.append('client_context', String(Date.now()) + '_' + i + '_' + Math.floor(Math.random() * 10000));
          var resp = await fetch('/api/v1/direct_v2/threads/broadcast/text/', {
            method: 'POST',
            headers: headers(true),
            credentials: 'include',
            body: body.toString()
          });
          if (resp.status === 429) {
            emit(cbId, false, { sent: sent }, 'rate_limited_429');
            return;
          }
          if (!resp.ok) {
            emit(cbId, false, { sent: sent }, 'send_http_' + resp.status);
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
