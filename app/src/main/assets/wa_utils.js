;(function () {
  if (window.__loadWWebUtils) return;
  window.__loadWWebUtils = function () {
    if (typeof window.WWebJS !== 'undefined') return;

    function safeRequire(name) {
      try {
        if (typeof window.require === 'function') {
          return window.require(name);
        }
      } catch (e) {}
      return null;
    }

    function serializeId(val) {
      if (!val) return '';
      if (typeof val === 'string') return val;
      if (typeof val._serialized === 'string' && val._serialized) return val._serialized;
      if (typeof val.$1 === 'string' && val.$1) return val.$1;
      if (val.user && val.server) return val.user + '@' + val.server;
      try { return String(val); } catch (e) { return ''; }
    }

    function arrayBufferToBase64(buffer) {
      var bytes = new Uint8Array(buffer);
      var chunk = 8192;
      var binary = '';
      for (var i = 0; i < bytes.length; i += chunk) {
        var sub = bytes.subarray(i, Math.min(i + chunk, bytes.length));
        binary += String.fromCharCode.apply(null, sub);
      }
      return window.btoa(binary);
    }

    function base64ToUint8Array(base64) {
      var clean = String(base64 || '').replace(/^data:[^;]+;base64,/, '');
      var binary = window.atob(clean);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
      }
      return bytes;
    }

    var WWebJS = {};
    WWebJS.safeRequire = safeRequire;
    WWebJS.serializeId = serializeId;

    WWebJS.getMeInfo = function () {
      var meJid = '';
      var meName = '';
      try {
        var userPrefs = safeRequire('WAWebUserPrefsMeUser');
        if (userPrefs) {
          var pn = (typeof userPrefs.getMaybeMePnUser === 'function' && userPrefs.getMaybeMePnUser())
            || (typeof userPrefs.getMeUser === 'function' && userPrefs.getMeUser())
            || (typeof userPrefs.getMaybeMeLidUser === 'function' && userPrefs.getMaybeMeLidUser());
          if (pn) meJid = serializeId(pn);
        }
      } catch (e) {}
      try {
        var connMod = safeRequire('WAWebConnModel');
        var Conn = connMod && connMod.Conn;
        if (Conn) {
          if (!meJid && Conn.wid) meJid = serializeId(Conn.wid);
          if (Conn.pushname) meName = String(Conn.pushname);
        }
      } catch (e) {}
      if (!meJid) {
        try {
          var cols = safeRequire('WAWebCollections');
          if (cols && cols.Contact && typeof cols.Contact.getMeContact === 'function') {
            var me = cols.Contact.getMeContact();
            if (me) {
              meJid = serializeId(me.id);
              meName = meName || me.pushname || me.name || '';
            }
          }
        } catch (e) {}
      }
      return { meJid: meJid, meName: meName };
    };

    WWebJS.getContactName = function (jid) {
      try {
        var cols = safeRequire('WAWebCollections');
        if (cols && cols.Contact) {
          var c = cols.Contact.get(jid);
          if (c) return c.name || c.verifiedName || c.pushname || c.shortName || '';
        }
      } catch (e) {}
      return '';
    };

    function normalizeWebChatId(chatId) {
      var s = String(chatId || '').trim();
      if (s.endsWith('@s.whatsapp.net')) {
        return s.slice(0, -'@s.whatsapp.net'.length) + '@c.us';
      }
      return s;
    }

    WWebJS.normalizeWebChatId = normalizeWebChatId;

    WWebJS.getChat = async function (chatId) {
      var webId = normalizeWebChatId(chatId);
      var cols = safeRequire('WAWebCollections');
      if (cols && cols.Chat) {
        var existing = cols.Chat.get(webId) || cols.Chat.get(chatId);
        if (existing) return existing;
      }
      try {
        var widFactory = safeRequire('WAWebWidFactory');
        var wid = widFactory && typeof widFactory.createWid === 'function' ? widFactory.createWid(webId) : null;
        if (wid) {
          if (cols && cols.Chat && typeof cols.Chat.find === 'function') {
            var found = await cols.Chat.find(wid);
            if (found) return found;
          }
          var findChat = safeRequire('WAWebFindChat');
          if (findChat && typeof findChat.findOrCreateLatestChat === 'function') {
            var res = await findChat.findOrCreateLatestChat(wid);
            if (res && res.chat) return res.chat;
            if (res) return res;
          }
        }
      } catch (e) {}
      return null;
    };

    function toBase64Field(val) {
      if (!val) return '';
      if (typeof val === 'string') return val.trim();
      try {
        if (val instanceof ArrayBuffer) return arrayBufferToBase64(val);
        if (ArrayBuffer.isView(val)) {
          return arrayBufferToBase64(val.buffer.slice(val.byteOffset, val.byteOffset + val.byteLength));
        }
        if (val.data) return toBase64Field(val.data);
      } catch (e) {}
      return '';
    }

    WWebJS.getMessageModel = function (msg) {
      var idObj = msg.id || {};
      var remote = serializeId(idObj.remote || msg.from || msg.to);
      var serializedId = serializeId(idObj);
      var fromMe = Boolean(idObj.fromMe);
      var author = serializeId(msg.author || msg.senderObj || msg.from);
      var senderJid = author || remote;
      var notifyName = msg.notifyName || WWebJS.getContactName(senderJid) || '';
      var isGroup = remote.endsWith('@g.us');
      var chatTitle = '';
      try {
        var cols = safeRequire('WAWebCollections');
        var chat = cols && cols.Chat && cols.Chat.get(remote);
        if (chat) {
          chatTitle = chat.name || chat.formattedTitle || (chat.groupMetadata && chat.groupMetadata.subject) || '';
        }
      } catch (e) {}
      var chatName = isGroup ? chatTitle : (chatTitle || notifyName);

      var quotedMsgObj = null;
      try {
        quotedMsgObj = msg.quotedMsg || (typeof msg.getQuotedMsgObj === 'function' ? msg.getQuotedMsgObj() : null);
      } catch (e) {}
      var quotedMsgId =
        (quotedMsgObj && serializeId(quotedMsgObj.id)) ||
        (msg.quotedStanzaID ? String(msg.quotedStanzaID) : '');
      var quotedText = '';
      var quotedFromMe = Boolean(quotedMsgObj && quotedMsgObj.id && quotedMsgObj.id.fromMe);
      var quotedType = (quotedMsgObj && quotedMsgObj.type) ? String(quotedMsgObj.type) : '';
      var quotedParticipant = serializeId(
        msg.quotedParticipant ||
        (quotedMsgObj && (quotedMsgObj.author || quotedMsgObj.from))
      );
      try {
        if (quotedMsgObj) {
          var qBody = quotedMsgObj.caption || (quotedMsgObj.type === 'chat' ? quotedMsgObj.body : '') || quotedMsgObj.filename || '';
          if (!qBody && quotedMsgObj.body && typeof quotedMsgObj.body === 'string' && quotedMsgObj.body.indexOf('/9j/') !== 0 && quotedMsgObj.body.indexOf('iVBOR') !== 0 && quotedMsgObj.body.indexOf('UklGR') !== 0) {
            qBody = quotedMsgObj.body;
          }
          quotedText = qBody || (quotedType ? ('[' + quotedType + ']') : '');
        }
      } catch (e) {}

      var mentionedJidList = [];
      try {
        if (Array.isArray(msg.mentionedJidList)) {
          mentionedJidList = msg.mentionedJidList.map(serializeId).filter(Boolean);
        }
      } catch (e) {}

      var md = msg.mediaData || {};
      return {
        id: {
          _serialized: serializedId,
          fromMe: fromMe,
          remote: remote,
          id: idObj.id || serializedId,
        },
        from: serializeId(msg.from) || remote,
        to: serializeId(msg.to) || remote,
        author: author,
        notifyName: notifyName,
        chatName: chatName,
        chatTitle: chatTitle,
        mentionedJidList: mentionedJidList,
        quotedParticipant: quotedParticipant,
        quotedFromMe: quotedFromMe,
        quotedType: quotedType,
        type: msg.type || 'chat',
        body: msg.body || '',
        caption: msg.caption || '',
        timestamp: Number(msg.t || msg.timestamp || Math.floor(Date.now() / 1000)),
        isNewMsg: Boolean(msg.isNewMsg),
        isMedia: Boolean(msg.isMedia || msg.type === 'ptt' || msg.type === 'audio' || msg.type === 'image' || msg.type === 'video' || msg.type === 'document' || msg.type === 'sticker'),
        mimetype: msg.mimetype || md.mimetype || '',
        filename: msg.filename || '',
        duration: Number(msg.duration || 0),
        directPath: msg.directPath || md.directPath || '',
        deprecatedMms3Url: msg.deprecatedMms3Url || md.deprecatedMms3Url || '',
        staticUrl: msg.staticUrl || md.staticUrl || '',
        mediaKey: toBase64Field(msg.mediaKey || md.mediaKey),
        filehash: msg.filehash || md.filehash || '',
        encFilehash: msg.encFilehash || md.encFilehash || '',
        isAnimated: Boolean(msg.isAnimated || md.isAnimated),
        quotedMsgId: quotedMsgId,
        quotedText: quotedText,
        lat: typeof msg.lat === 'number' ? msg.lat : null,
        lng: typeof msg.lng === 'number' ? msg.lng : null,
        loc: msg.loc || '',
        vcard: msg.body || '',
        vcardFormattedName: msg.vcardFormattedName || '',
      };
    };

    async function fetchUrlToBase64(url) {
      if (!url || typeof url !== 'string') return '';
      try {
        if (url.indexOf('data:') === 0) {
          var idx = url.indexOf('base64,');
          return idx >= 0 ? url.slice(idx + 7).replace(/\s+/g, '') : '';
        }
        if (url.indexOf('blob:') === 0 || url.indexOf('https://') === 0) {
          var resp = await fetch(url);
          if (resp && resp.ok) {
            var ab = await resp.arrayBuffer();
            if (ab && ab.byteLength > 0 && ab.byteLength <= 6 * 1024 * 1024) {
              return arrayBufferToBase64(ab);
            }
          }
        }
      } catch (e) {}
      return '';
    }

    async function readRawBlobToBase64(rawBlob) {
      if (!rawBlob) return '';
      try {
        if (typeof rawBlob.arrayBuffer === 'function') {
          var ab = await rawBlob.arrayBuffer();
          if (ab && ab.byteLength > 0 && ab.byteLength <= 6 * 1024 * 1024) {
            return arrayBufferToBase64(ab);
          }
        }
      } catch (e) {}
      try {
        if (typeof FileReader !== 'undefined' && typeof Blob !== 'undefined' && rawBlob instanceof Blob) {
          var b64 = await new Promise(function (resolve) {
            var r = new FileReader();
            r.onloadend = function () {
              var out = String(r.result || '');
              var idx = out.indexOf('base64,');
              resolve(idx >= 0 ? out.slice(idx + 7) : '');
            };
            r.onerror = function () { resolve(''); };
            r.readAsDataURL(rawBlob);
          });
          if (b64) return b64;
        }
      } catch (e) {}
      return '';
    }

    async function anyBlobToBase64(obj) {
      if (!obj) return '';
      if (typeof obj === 'string') {
        var fromUrl = await fetchUrlToBase64(obj);
        if (fromUrl) return fromUrl;
        var s = obj.replace(/^data:[^;]+;base64,/, '').replace(/\s+/g, '');
        return s.length > 32 ? s : '';
      }
      try {
        if (obj instanceof ArrayBuffer) {
          return obj.byteLength > 0 ? arrayBufferToBase64(obj) : '';
        }
        if (ArrayBuffer.isView(obj)) {
          return obj.byteLength > 0 ? arrayBufferToBase64(obj.buffer.slice(obj.byteOffset, obj.byteOffset + obj.byteLength)) : '';
        }
      } catch (e) {}

      var directRead = await readRawBlobToBase64(obj);
      if (directRead) return directRead;

      if (obj._blob) {
        var b1 = await readRawBlobToBase64(obj._blob);
        if (b1) return b1;
      }
      if (typeof obj.getBlob === 'function') {
        try {
          var gb = await obj.getBlob();
          var b2 = await readRawBlobToBase64(gb);
          if (b2) return b2;
        } catch (e) {}
      }
      if (typeof obj.forceToBlob === 'function') {
        try {
          var fb = await obj.forceToBlob();
          var b3 = await readRawBlobToBase64(fb);
          if (b3) return b3;
        } catch (e) {}
      }
      if (typeof obj.toBlob === 'function') {
        try {
          var tb = await obj.toBlob();
          var b4 = await readRawBlobToBase64(tb);
          if (b4) return b4;
        } catch (e) {}
      }
      try {
        var urlCand = (typeof obj.url === 'function' ? obj.url() : obj.url) || obj._url || obj.renderableUrl || '';
        if (urlCand) {
          var b5 = await fetchUrlToBase64(String(urlCand));
          if (b5) return b5;
        }
      } catch (e) {}
      if (typeof obj.getBase64 === 'function') {
        try {
          var b6 = await obj.getBase64();
          if (b6) return String(b6).replace(/^data:[^;]+;base64,/, '');
        } catch (e) {}
      }
      return '';
    }

    function looksLikeBase64Image(str) {
      if (!str || typeof str !== 'string') return false;
      var clean = str.replace(/^data:[^;]+;base64,/, '').replace(/\s+/g, '');
      if (clean.length < 64) return false;
      return clean.indexOf('/9j/') === 0 || clean.indexOf('iVBOR') === 0 || clean.indexOf('UklGR') === 0 || clean.indexOf('R0lGOD') === 0;
    }

    async function captureDomStickerOrImage(msgId, isStickerType) {
      if (!msgId || typeof document === 'undefined') return null;
      try {
        var escaped = msgId.replace(/"/g, '\\"');
        var container = document.querySelector('[data-id="' + escaped + '"]');
        if (!container) return null;
        var imgs = container.querySelectorAll('img[src^="blob:"], img[src^="data:"], img');
        for (var i = 0; i < imgs.length; i++) {
          var img = imgs[i];
          if (!img || !img.src) continue;
          // Skip tiny blurred preview avatars/icons when expecting a full photo
          var w = img.naturalWidth || img.width || 0;
          if (!isStickerType && w > 0 && w < 240 && img.src.indexOf('blob:') !== 0) continue;
          var fetched = await fetchUrlToBase64(img.src);
          if (fetched) {
            return { data: fetched, isThumbnail: (!isStickerType && w > 0 && w < 320) };
          }
          if (img.complete && w > 0) {
            var c = document.createElement('canvas');
            c.width = img.naturalWidth;
            c.height = img.naturalHeight;
            var ctx = c.getContext('2d');
            if (ctx) {
              ctx.drawImage(img, 0, 0);
              var url = c.toDataURL('image/png');
              var idx = url.indexOf('base64,');
              if (idx >= 0) {
                return { data: url.slice(idx + 7), isThumbnail: (!isStickerType && w < 320) };
              }
            }
          }
        }
        var cvs = container.querySelector('canvas');
        if (cvs && cvs.width > 0 && typeof cvs.toDataURL === 'function') {
          var cUrl = cvs.toDataURL('image/png');
          var cIdx = cUrl.indexOf('base64,');
          if (cIdx >= 0) return { data: cUrl.slice(cIdx + 7), isThumbnail: (!isStickerType && cvs.width < 320) };
        }
      } catch (e) {}
      return null;
    }

    WWebJS.downloadMedia = async function (msg) {
      var sid = serializeId(msg && msg.id);
      var md0 = (msg && msg.mediaData) || {};
      var msgType = (msg && msg.type) || 'image';
      var isSticker = msgType === 'sticker';
      var mimeHint = (msg && msg.mimetype) || md0.mimetype || (isSticker ? 'image/webp' : 'image/jpeg');

      function latestMessage() {
        try {
          var cols = safeRequire('WAWebCollections');
          if (cols && cols.Msg && sid) {
            return cols.Msg.get(sid) || msg;
          }
        } catch (e) {}
        return msg;
      }

      try {
        var cur = latestMessage();
        var dlOpts = { downloadEvenIfExpensive: true, rmsel: 1, rmrReason: 1, isUserInitiated: true };
        if (cur && typeof cur.downloadMedia === 'function') {
          try { await cur.downloadMedia(dlOpts); } catch (e1) {}
        }
        if (cur && cur.mediaData) {
          if (typeof cur.mediaData.forceDownloadMedia === 'function') {
            try { await cur.mediaData.forceDownloadMedia(dlOpts); } catch (e1a) {}
          }
          if (typeof cur.mediaData.downloadMedia === 'function') {
            try { await cur.mediaData.downloadMedia(dlOpts); } catch (e1b) {}
          }
        }
        var dlMediaMod = safeRequire('WAWebDownloadMedia') || safeRequire('WAWebMediaLoadMsg');
        if (dlMediaMod) {
          var fn = dlMediaMod.downloadMedia || dlMediaMod.loadMedia || dlMediaMod.default;
          if (typeof fn === 'function') {
            try { await fn({ msg: cur, downloadEvenIfExpensive: true, isUserInitiated: true, rmsel: 1, rmrReason: 1 }); } catch (e1c) {}
          }
        }

        // Poll up to 5s for WhatsApp Web's internal full-res mediaBlob / renderableUrl / mediaObject
        for (var w = 0; w < 20; w++) {
          cur = latestMessage();
          var md = (cur && cur.mediaData) || {};
          var b64FromBlob = await anyBlobToBase64(md.mediaBlob)
            || await anyBlobToBase64(md.renderableUrl)
            || await anyBlobToBase64(cur && cur.mediaObject);
          if (b64FromBlob && (isSticker || b64FromBlob.length > 24000 || md.mediaStage === 'RESOLVED')) {
            return { mimetype: md.mimetype || mimeHint, data: b64FromBlob, source: 'mediaBlob', isThumbnail: false };
          }
          if (md.mediaStage === 'ERROR_MISSING') break;
          await new Promise(function (r) { setTimeout(r, 250); });
        }

        // Strategy 2: WAWebDownloadManager direct full-resolution decrypt
        cur = latestMessage();
        var cmd = (cur && cur.mediaData) || {};
        var dlMgr = safeRequire('WAWebDownloadManager');
        var mgr = dlMgr && (dlMgr.downloadManager || dlMgr.default || dlMgr);
        var dPath = (cur && cur.directPath) || cmd.directPath || '';
        var dUrl = (cur && cur.deprecatedMms3Url) || cmd.deprecatedMms3Url || '';
        var sUrl = (cur && cur.staticUrl) || cmd.staticUrl || '';
        var mKey = (cur && cur.mediaKey) || cmd.mediaKey;
        if (mgr && typeof mgr.downloadAndMaybeDecrypt === 'function' && (dPath || dUrl || sUrl)) {
          try {
            var decrypted = await mgr.downloadAndMaybeDecrypt({
              directPath: dPath,
              deprecatedMms3Url: dUrl,
              staticUrl: sUrl,
              encFilehash: (cur && cur.encFilehash) || cmd.encFilehash,
              filehash: (cur && cur.filehash) || cmd.filehash,
              mediaKey: mKey,
              mediaKeyTimestamp: (cur && cur.mediaKeyTimestamp) || cmd.mediaKeyTimestamp,
              mimetype: (cur && cur.mimetype) || cmd.mimetype || mimeHint,
              type: (cur && cur.type) || msgType,
              isUserInitiated: true,
              downloadEvenIfExpensive: true,
              signal: typeof AbortController !== 'undefined' ? new AbortController().signal : undefined,
            });
            var payload = (decrypted && decrypted.data) ? decrypted.data : decrypted;
            var b64Dec = await anyBlobToBase64(payload);
            if (b64Dec) {
              return { mimetype: (cur && cur.mimetype) || cmd.mimetype || mimeHint, data: b64Dec, source: 'downloadManager', isThumbnail: false };
            }
          } catch (e2) {}
        }

        // Strategy 3: Static unencrypted HTTPS sticker/media URL
        if (sUrl && sUrl.indexOf('https://') === 0) {
          var b64Static = await fetchUrlToBase64(sUrl);
          if (b64Static) {
            return { mimetype: mimeHint, data: b64Static, source: 'staticUrl', isThumbnail: false };
          }
        }

        // Strategy 4: Full-res DOM rendered sticker / image snapshot
        var domShot = await captureDomStickerOrImage(sid, isSticker);
        if (domShot && domShot.data) {
          return { mimetype: isSticker ? 'image/png' : mimeHint, data: domShot.data, source: 'domElement', isThumbnail: Boolean(domShot.isThumbnail) };
        }

        // Strategy 5 (last-resort preview): Mark isThumbnail=true so Kotlin upgrades it via native CDN decrypt!
        if (cmd.preview || cmd.thumbnailBlob) {
          var b64Prev = await anyBlobToBase64(cmd.preview) || await anyBlobToBase64(cmd.thumbnailBlob);
          if (b64Prev) {
            return {
              mimetype: isSticker ? 'image/webp' : 'image/jpeg',
              data: b64Prev,
              source: 'preview',
              isThumbnail: true,
            };
          }
        }
      } catch (e) {}

      // Strategy 6: Inline blurred micro-thumbnail in msg.body (mark isThumbnail=true!)
      try {
        if ((msgType === 'image' || msgType === 'sticker') && looksLikeBase64Image(msg.body)) {
          var cleanBody = String(msg.body).replace(/^data:[^;]+;base64,/, '').replace(/\s+/g, '');
          var thumbMime = cleanBody.indexOf('UklGR') === 0 ? 'image/webp' : (cleanBody.indexOf('iVBOR') === 0 ? 'image/png' : 'image/jpeg');
          return { mimetype: thumbMime, data: cleanBody, source: 'bodyThumbnail', isThumbnail: true };
        }
      } catch (e3) {}

      return null;
    };

    WWebJS.sendMessage = async function (chat, text, options) {
      var opts = options || {};
      var extra = {};
      if (opts.quotedMessageId) {
        try {
          var cols = safeRequire('WAWebCollections');
          var qm = cols && cols.Msg && cols.Msg.get(opts.quotedMessageId);
          if (qm) extra.quotedMsg = qm;
        } catch (e) {}
      }

      var sendTextMod = safeRequire('WAWebSendTextMsgChatAction');
      if (sendTextMod && typeof sendTextMod.sendTextMsgToChat === 'function') {
        return await sendTextMod.sendTextMsgToChat(chat, String(text), extra);
      }

      var sendMsgMod = safeRequire('WAWebChatSendMessages');
      if (sendMsgMod && typeof sendMsgMod.addAndSendMsgToChat === 'function') {
        var newId = await WWebJS.generateMessageId(chat);
        var userPrefs = safeRequire('WAWebUserPrefsMeUser');
        var me = userPrefs && typeof userPrefs.getMaybeMePnUser === 'function' ? userPrefs.getMaybeMePnUser() : undefined;
        var msgObj = Object.assign({
          id: newId,
          ack: 0,
          body: String(text),
          from: me,
          to: chat.id,
          local: true,
          self: 'out',
          t: Math.floor(Date.now() / 1000),
          isNewMsg: true,
          type: 'chat',
        }, extra);
        var res = await sendMsgMod.addAndSendMsgToChat(chat, msgObj);
        return (res && res[0]) || msgObj;
      }

      if (typeof chat.sendMessage === 'function') {
        return await chat.sendMessage(String(text), extra);
      }
      throw new Error('No WhatsApp Web send function available');
    };

    WWebJS.generateMessageId = async function (chat) {
      var msgKey = safeRequire('WAWebMsgKey');
      var userPrefs = safeRequire('WAWebUserPrefsMeUser');
      var me = userPrefs && typeof userPrefs.getMaybeMePnUser === 'function' ? userPrefs.getMaybeMePnUser() : undefined;
      if (msgKey && typeof msgKey.newId === 'function') {
        var rawId = await msgKey.newId();
        return new msgKey({ from: me, to: chat.id, id: rawId, fromMe: true });
      }
      var fallbackId = '3EB0' + Date.now().toString(16).toUpperCase();
      return {
        fromMe: true,
        remote: chat.id,
        id: fallbackId,
        _serialized: 'true_' + serializeId(chat.id) + '_' + fallbackId,
      };
    };

    WWebJS.sendMedia = async function (chat, base64Data, mimeType, fileName, caption) {
      var bytes = base64ToUint8Array(base64Data);
      var blob = new Blob([bytes], { type: mimeType || 'application/octet-stream' });
      var file = new File([blob], fileName || 'attachment', { type: mimeType || 'application/octet-stream' });

      var opaqueMod = safeRequire('WAWebMediaOpaqueData');
      var prepMod = safeRequire('WAWebMediaPrep');
      if (opaqueMod && prepMod && typeof opaqueMod.createFromData === 'function' && typeof prepMod.prepRawMedia === 'function') {
        var opaque = await opaqueMod.createFromData(file, file.type);
        var prep = prepMod.prepRawMedia(opaque, {
          asDocument: !String(mimeType).startsWith('image/') && !String(mimeType).startsWith('video/'),
        });
        var sendOpts = {};
        if (caption) sendOpts.caption = String(caption);
        if (typeof prep.sendToChat === 'function') {
          return await prep.sendToChat(chat, sendOpts);
        }
      }
      throw new Error('Media preparation module unavailable');
    };

    WWebJS.sendReaction = async function (msgId, emoji) {
      var cols = safeRequire('WAWebCollections');
      var msg = cols && cols.Msg && cols.Msg.get(msgId);
      if (!msg) return false;
      var reactMod = safeRequire('WAWebSendReactionMsgAction');
      if (reactMod && typeof reactMod.sendReactionToMsg === 'function') {
        await reactMod.sendReactionToMsg(msg, emoji);
        return true;
      }
      return false;
    };

    WWebJS.sendChatState = async function (chatId, composing) {
      var chat = await WWebJS.getChat(chatId);
      if (!chat) return false;
      var pres = safeRequire('WAWebChatPresence');
      if (pres) {
        if (composing && typeof pres.markComposing === 'function') {
          await pres.markComposing(chat);
          return true;
        }
        if (!composing && typeof pres.markPaused === 'function') {
          await pres.markPaused(chat);
          return true;
        }
      }
      return false;
    };

    WWebJS.sendSeen = async function (chatId) {
      var chat = await WWebJS.getChat(chatId);
      if (!chat) return false;
      var seenMod = safeRequire('WAWebSendSeen');
      if (seenMod && typeof seenMod.sendSeen === 'function') {
        await seenMod.sendSeen(chat);
        return true;
      }
      return false;
    };

    WWebJS.sendPresence = async function (available) {
      var pres = safeRequire('WAWebChatPresence') || safeRequire('WAWebPresenceUtils');
      if (!pres) return false;
      if (available && typeof pres.sendPresenceAvailable === 'function') {
        await pres.sendPresenceAvailable();
        return true;
      }
      if (!available && typeof pres.sendPresenceUnavailable === 'function') {
        await pres.sendPresenceUnavailable();
        return true;
      }
      return false;
    };

    WWebJS.requestPairingCode = async function (phoneDigits) {
      var mod = safeRequire('WAWebLinkDeviceWithPhoneNumber') || safeRequire('WAWebApiLinkDevice');
      if (mod) {
        var fn = mod.requestLinkDeviceCode || mod.genLinkDeviceCodeForPhoneNumber || mod.requestPhoneNumberLinkingCode;
        if (typeof fn === 'function') {
          var code = await fn(String(phoneDigits));
          if (code) return String(code);
        }
      }
      throw new Error('Phone pairing API unavailable; open Live WhatsApp Web to link by phone.');
    };

    window.WWebJS = WWebJS;
  };
})();
