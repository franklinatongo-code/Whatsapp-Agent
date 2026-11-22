(function () {
  var existingState = window.__waBootState && window.__waBootState.state;
  if (window.__waMainLoopActive && existingState && existingState !== 'failed' && existingState !== 'stopped') {
    window.__waBootState.lastReinjectAt = Date.now();
    if (typeof window.__waHeartbeat === 'function') {
      try { window.__waHeartbeat(); } catch (e) {}
    }
    return;
  }

  window.__waMainLoopActive = true;
  window.__waBootState = {
    state: 'starting', // stopped | starting | waiting_for_modules | ready | recovering | failed
    startedAt: Date.now(),
    lastTransitionAt: Date.now(),
    lastError: '',
  };

  function setBootState(nextState, errMsg) {
    if (!window.__waBootState) return;
    if (window.__waBootState.state !== nextState || errMsg) {
      window.__waBootState.state = nextState;
      window.__waBootState.lastTransitionAt = Date.now();
      if (errMsg !== undefined) window.__waBootState.lastError = String(errMsg || '');
    }
  }

  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  function log() {
    var parts = [].slice.call(arguments).map(function (x) {
      if (typeof x === 'string') return x;
      try { return JSON.stringify(x); } catch (e) { return String(x); }
    });
    try { AndroidWA.log(parts.join(' ')); } catch (e) {}
  }

  function emit(fn, payload) {
    try {
      var arg = typeof payload === 'string' ? payload : JSON.stringify(payload);
      AndroidWA[fn](arg);
    } catch (e) {
      log('emit error ' + fn + ': ' + e);
    }
  }

  var lastQrRef = '';
  var lastQrImgHash = '';
  var wasConnected = false;
  var msgHooked = false;
  var stateHooked = false;
  var chatsSynced = false;
  var lastChatsSyncAt = 0;

  function scanDomStatus() {
    try {
      // 1. Check if authenticated chat list is visible in DOM
      var chatPane = document.querySelector('#pane-side, [data-testid="chat-list"], [aria-label="Chat list"]');
      if (chatPane) {
        var info = (window.WWebJS && window.WWebJS.getMeInfo()) || { meJid: '', meName: '' };
        if (!wasConnected) {
          wasConnected = true;
          emit('onConnected', JSON.stringify(info));
        }
        lastQrRef = '';
        return;
      }

      // 2. Check QR ref attribute rendered by WhatsApp Web
      var refEl = document.querySelector('[data-ref]');
      var refVal = refEl ? refEl.getAttribute('data-ref') : '';
      if (refVal && refVal.length >= 16) {
        if (refVal !== lastQrRef) {
          lastQrRef = refVal;
          emit('onQr', refVal);
        }
        return;
      }

      // 3. Fallback: snapshot rendered QR canvas image if data-ref is hidden
      var qrCanvas = document.querySelector('[data-testid="qrcode"] canvas, canvas[aria-label*="Scan"], canvas[aria-label*="QR"]');
      if (qrCanvas && qrCanvas.width >= 120 && typeof qrCanvas.toDataURL === 'function') {
        var dataUrl = qrCanvas.toDataURL('image/png');
        if (dataUrl && dataUrl.length > 200 && dataUrl !== lastQrImgHash) {
          lastQrImgHash = dataUrl;
          emit('onQrImage', dataUrl);
        }
      }
    } catch (e) {}
  }

  function defineGlobalActions() {
    if (!window.__waSend) {
      window.__waSend = async function (requestId, chatId, text, quotedId) {
        return await window.__waReply(requestId, chatId, text, quotedId, 4000);
      };
    }

    if (!window.__waReply) {
      window.__waReply = async function (requestId, chatId, text, quotedId, chunkSize) {
        var maxLen = Number(chunkSize || 4000);
        try {
          var chat = await window.WWebJS.getChat(chatId);
          if (!chat) throw new Error('Chat not found: ' + chatId);

          var parts = [];
          var rest = String(text || '');
          while (rest.length > maxLen) {
            var cut = rest.lastIndexOf('\n', maxLen);
            if (cut < maxLen * 0.5) cut = rest.lastIndexOf(' ', maxLen);
            if (cut < maxLen * 0.5) cut = maxLen;
            parts.push(rest.slice(0, cut));
            rest = rest.slice(cut).replace(/^\s+/, '');
          }
          if (rest) parts.push(rest);
          if (parts.length === 0) return null;

          var firstSentId = null;
          for (var i = 0; i < parts.length; i++) {
            var opts = (i === 0 && quotedId) ? { quotedMessageId: quotedId } : {};
            var sent;
            try {
              sent = await window.WWebJS.sendMessage(chat, parts[i], opts);
            } catch (e) {
              if (i !== 0 || !quotedId) throw e;
              sent = await window.WWebJS.sendMessage(chat, parts[i], {});
            }
            if (i === 0) {
              firstSentId = (sent && sent.id && (sent.id._serialized || sent.id.id)) || ('wa_' + Date.now());
            }
          }
          emit('onSent', { requestId: requestId || '', chatId: chatId, id: String(firstSentId || '') });
          return firstSentId;
        } catch (e) {
          emit('onError', { requestId: requestId || '', chatId: chatId, error: String(e) });
          return null;
        }
      };
    }

    if (!window.__waSendMedia) {
      window.__waSendMedia = async function (requestId, chatId, base64Data, mimeType, fileName, caption) {
        try {
          var chat = await window.WWebJS.getChat(chatId);
          if (!chat) throw new Error('Chat not found: ' + chatId);
          var sent = await window.WWebJS.sendMedia(chat, base64Data, mimeType, fileName, caption);
          var sentId = (sent && sent.id && (sent.id._serialized || sent.id.id)) || ('wamedia_' + Date.now());
          emit('onSent', { requestId: requestId || '', chatId: chatId, id: String(sentId) });
          return sentId;
        } catch (e) {
          emit('onError', { requestId: requestId || '', chatId: chatId, error: String(e) });
          return null;
        }
      };
    }

    if (!window.__waSendReaction) {
      window.__waSendReaction = async function (msgId, emoji) {
        try { await window.WWebJS.sendReaction(msgId, emoji); } catch (e) {}
      };
    }

    if (!window.__waSendChatState) {
      window.__waSendChatState = async function (chatId, composing) {
        try { await window.WWebJS.sendChatState(chatId, Boolean(composing)); } catch (e) {}
      };
    }

    if (!window.__waSendSeen) {
      window.__waSendSeen = async function (chatId) {
        try { await window.WWebJS.sendSeen(chatId); } catch (e) {}
      };
    }

    if (!window.__waSeen) {
      window.__waSeen = window.__waSendSeen;
    }

    if (!window.__waPresence) {
      window.__waPresence = async function (available) {
        try { await window.WWebJS.sendPresence(Boolean(available)); } catch (e) {}
      };
    }

    if (!window.__waRequestPhoneCode) {
      window.__waRequestPhoneCode = async function (phoneDigits) {
        try {
          var code = await window.WWebJS.requestPairingCode(phoneDigits);
          emit('onPairingCode', String(code));
        } catch (e) {
          emit('onError', { requestId: 'pairing_code', chatId: '', error: String(e) });
        }
      };
    }

    if (!window.__waHeartbeat) {
      window.__waHeartbeat = function () {
        try {
          scanDomStatus();
          if (typeof window.WWebJS !== 'undefined') {
            hookWhatsAppModules();
          }
          var sock = '';
          try { sock = window.WWebJS.safeRequire('WAWebSocketModel').Socket.state || ''; } catch (e) {}
          return sock || (wasConnected ? 'CONNECTED' : 'IDLE');
        } catch (e) {
          return 'ERR';
        }
      };
    }

    if (!window.__waFetchMediaBase64) {
      window.__waFetchMediaBase64 = async function (msgId) {
        try {
          var req = window.WWebJS && window.WWebJS.safeRequire;
          if (!req) return null;
          var cols = req('WAWebCollections');
          var Msg = cols && cols.Msg;
          var target = Msg && typeof Msg.get === 'function' ? Msg.get(msgId) : null;
          if (!target && Msg && typeof Msg.getModelsArray === 'function') {
            var arr = Msg.getModelsArray() || [];
            for (var i = arr.length - 1; i >= 0; i--) {
              var m = arr[i];
              var sid = m && window.WWebJS.serializeId(m.id);
              if (sid === msgId || (m && m.id && m.id.id === msgId)) {
                target = m;
                break;
              }
            }
          }
          if (!target) return null;
          var dl = await Promise.race([
            window.WWebJS.downloadMedia(target),
            sleep(4000).then(function () { return null; }),
          ]);
          if (dl && dl.data) {
            return JSON.stringify({
              data: dl.data,
              mimetype: dl.mimetype || target.mimetype || '',
              isThumbnail: Boolean(dl.isThumbnail),
            });
          }
          return null;
        } catch (e) {
          return null;
        }
      };
    }
  }

  var seenEmittedIds = Object.create(null);

  async function pushMessageModel(msg, overrideType, targetMsgId, isBacklog) {
    try {
      var model = window.WWebJS.getMessageModel(msg);
      if (overrideType) model.type = overrideType;
      if (targetMsgId) model.targetMessageId = targetMsgId;
      model.isBacklog = Boolean(isBacklog);

      if (!model.id || !model.id._serialized || !model.id.remote) return;
      if (model.id.remote === 'status@broadcast') return;
      if (!overrideType && seenEmittedIds[model.id._serialized]) return;
      seenEmittedIds[model.id._serialized] = true;

      // Fast inline media capture (capped at 1.5s); Kotlin persists message immediately & upgrades full-res CDN cleanly
      if (!model.id.fromMe && (model.type === 'ptt' || model.type === 'audio' || model.type === 'image' || model.type === 'sticker' || model.type === 'document')) {
        try {
          var dl = await Promise.race([
            window.WWebJS.downloadMedia(msg),
            sleep(1500).then(function () { return null; }),
          ]);
          if (dl && dl.data) {
            model.mediaBase64 = dl.data;
            model.mediaIsThumbnail = Boolean(dl.isThumbnail);
            if (dl.mimetype) model.mimetype = dl.mimetype;
            log('media ok (' + model.type + ' via ' + (dl.source || 'blob') + (dl.isThumbnail ? ' [thumb]' : ' [full]') + ', b64len=' + dl.data.length + ')');
          } else {
            model.mediaIsThumbnail = false;
            log('media deferred to native CDN for ' + model.type + ' (hasKey=' + Boolean(model.mediaKey) + ', hasPath=' + Boolean(model.directPath || model.staticUrl) + ')');
          }
        } catch (e) {
          log('media err: ' + e);
        }
      }

      emit('onMessage', model);
    } catch (e) {
      log('pushMessageModel err: ' + e);
    }
  }

  function scanInitialRecentMessages(req) {
    try {
      var cols = req('WAWebCollections');
      var Chat = cols && cols.Chat;
      if (!Chat || typeof Chat.getModelsArray !== 'function') return;
      var nowSec = Math.floor(Date.now() / 1000);
      var chats = Chat.getModelsArray() || [];
      for (var i = 0; i < Math.min(chats.length, 30); i++) {
        var c = chats[i];
        var cid = window.WWebJS.serializeId(c.id);
        if (!cid || cid === 'status@broadcast') continue;
        var msgsArr = (c.msgs && typeof c.msgs.getModelsArray === 'function') ? c.msgs.getModelsArray() : [];
        if (!msgsArr || msgsArr.length === 0) continue;
        var tail = msgsArr.slice(-4);
        for (var j = 0; j < tail.length; j++) {
          var m = tail[j];
          if (!m || m.type === 'ciphertext' || m.type === 'revoked') continue;
          var ts = Number(m.t || m.timestamp || 0);
          var unreadWindow = Boolean(c.unreadCount > 0 && (nowSec - ts) <= 900);
          var liveWindow = (nowSec - ts) <= 120;
          pushMessageModel(m, null, null, !(unreadWindow || liveWindow));
        }
      }
    } catch (e) {}
  }

  function hookWhatsAppModules() {
    var req = window.WWebJS && window.WWebJS.safeRequire;
    if (!req) return false;

    if (!stateHooked) {
      try {
        var sockMod = req('WAWebSocketModel');
        var Sock = sockMod && sockMod.Socket;
        if (Sock && typeof Sock.on === 'function') {
          var notifySock = function (st) {
            emit('onState', { state: String(st || '') });
            if (st === 'CONNECTED') {
              wasConnected = true;
              emit('onConnected', JSON.stringify(window.WWebJS.getMeInfo()));
            } else if ((st === 'UNPAIRED' || st === 'UNPAIRED_IDLE') && wasConnected) {
              wasConnected = false;
              emit('onLoggedOut', 'unpaired');
            }
          };
          Sock.on('change:state', function (_s, st) { notifySock(st); });
          notifySock(Sock.state);
          stateHooked = true;
        }
      } catch (e) {}
    }

    if (!msgHooked) {
      try {
        var cols = req('WAWebCollections');
        var Msg = cols && cols.Msg;
        if (Msg && typeof Msg.on === 'function') {
          Msg.on('add', function (msg) {
            try {
              if (!msg) return;
              var nowSec = Math.floor(Date.now() / 1000);
              var msgTs = Number(msg.t || msg.timestamp || nowSec);
              // Allow newly arriving messages even if initial store hydration omitted isNewMsg within recent window
              var looksLive = Boolean(msg.isNewMsg) || ((nowSec - msgTs) <= 180);
              if (!looksLive) return;
              if (msg.type === 'ciphertext') {
                msg.once('change:type', function (decrypted) {
                  if (decrypted && decrypted.type !== 'revoked') {
                    pushMessageModel(decrypted, null, null, false);
                  }
                });
                return;
              }
              if (msg.type === 'revoked') {
                pushMessageModel(msg, 'revoked', window.WWebJS.serializeId(msg.id), false);
                return;
              }
              pushMessageModel(msg, null, null, false);
            } catch (e) {
              log('Msg.add err: ' + e);
            }
          });

          Msg.on('change:body', function (msg) {
            try {
              if (!msg || !msg.isEdited) return;
              pushMessageModel(msg, 'edited', window.WWebJS.serializeId(msg.id), false);
            } catch (e) {}
          });

          Msg.on('change:type', function (msg) {
            try {
              if (msg && msg.type === 'revoked') {
                pushMessageModel(msg, 'revoked', window.WWebJS.serializeId(msg.id), false);
              }
            } catch (e) {}
          });

          msgHooked = true;
          log('boot: hooked WAWebCollections.Msg');
          setTimeout(function () { scanInitialRecentMessages(req); }, 600);
        }
      } catch (e) {}
    }

    var nowMs = Date.now();
    if (msgHooked && (!chatsSynced || (nowMs - lastChatsSyncAt) > 30000)) {
      try {
        var cols2 = req('WAWebCollections');
        var Chat = cols2 && cols2.Chat;
        if (Chat && typeof Chat.getModelsArray === 'function') {
          var arr = Chat.getModelsArray();
          if (arr && arr.length > 0) {
            var recent = arr.slice(0, 50).map(function (c) {
              var cid = window.WWebJS.serializeId(c.id);
              var isGrp = cid.endsWith('@g.us');
              var groupTitle = c.name || c.formattedTitle || (c.groupMetadata && c.groupMetadata.subject) || '';
              var last = c.msgs && typeof c.msgs.last === 'function' ? c.msgs.last() : null;
              return {
                chatJid: cid,
                displayName: isGrp ? (groupTitle || cid) : (groupTitle || window.WWebJS.getContactName(cid) || cid),
                isGroup: isGrp,
                lastMessagePreview: last ? (last.body || last.caption || ('[' + (last.type || 'msg') + ']')) : '',
                lastTimestampMs: Number((c.t || (last && last.t) || Math.floor(Date.now() / 1000)) * 1000),
                unreadCount: Number(c.unreadCount || 0),
              };
            }).filter(function (item) {
              return item.chatJid && item.chatJid !== 'status@broadcast';
            });
            if (recent.length > 0) {
              emit('onChatsSnapshot', JSON.stringify(recent));
              chatsSynced = true;
              lastChatsSyncAt = nowMs;
            }
          }
        }
      } catch (e) {}
    }

    return msgHooked;
  }

  try {
    if (document.body && typeof MutationObserver !== 'undefined') {
      var obs = new MutationObserver(function () { scanDomStatus(); });
      obs.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-ref'] });
    }
  } catch (e) {}

  async function main() {
    log('boot: monitoring WhatsApp Web DOM & modules');
    setBootState('waiting_for_modules');
    var consecutiveErrors = 0;
    for (;;) {
      try {
        scanDomStatus();
        if (typeof window.require === 'function') {
          if (typeof window.WWebJS === 'undefined' && typeof window.__loadWWebUtils === 'function') {
            try { window.__loadWWebUtils(); } catch (e) { log('LoadUtils err: ' + e); }
          }
          if (typeof window.WWebJS !== 'undefined') {
            defineGlobalActions();
            var hooked = hookWhatsAppModules();
            if (hooked) {
              setBootState('ready', '');
              consecutiveErrors = 0;
            } else {
              setBootState('waiting_for_modules');
            }
          } else {
            setBootState('waiting_for_modules');
          }
        } else {
          setBootState('waiting_for_modules');
        }
      } catch (e) {
        consecutiveErrors++;
        log('boot loop err: ' + e);
        setBootState(consecutiveErrors >= 5 ? 'recovering' : 'waiting_for_modules', e);
      }
      await sleep(wasConnected && msgHooked && chatsSynced ? 3500 : 800);
    }
  }

  main();
})();
