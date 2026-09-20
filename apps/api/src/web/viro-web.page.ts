/**
 * Viro on the web: a companion for messaging, linked from the phone.
 *
 * It is served as one page with no build step and no dependencies, because it
 * ships inside the API container and has to keep working on a slow connection.
 * Calls stay on the phone; this is for reading and writing messages.
 */
export const VIRO_WEB_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Viro</title>
<style>
  :root {
    --navy: #0b1526; --surface: #14233c; --raised: #1d3357; --accent: #1565f5;
    --text: #eef3ff; --muted: #9fb0cc; --good: #21c07a;
  }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background: var(--navy); color: var(--text); height: 100vh; overflow: hidden; }
  button { font: inherit; cursor: pointer; border: 0; border-radius: 10px; }
  .primary { background: var(--accent); color: #fff; padding: 12px 20px; font-weight: 600; }
  .primary:disabled { background: var(--raised); color: var(--muted); cursor: default; }
  .ghost { background: transparent; color: var(--muted); padding: 8px 12px; }

  /* Linking */
  #link { height: 100%; display: flex; align-items: center; justify-content: center; padding: 16px; }
  #link .card { max-width: 420px; text-align: center; }
  h1 { font-size: 22px; margin: 0 0 8px; }
  p { color: var(--muted); line-height: 1.5; }
  .code { font-size: 38px; letter-spacing: 8px; font-weight: 700; color: #fff;
          background: var(--surface); border-radius: 14px; padding: 18px 10px; margin: 18px 0 10px; }
  .steps { text-align: left; margin: 18px auto 0; max-width: 340px; }
  .steps li { margin-bottom: 8px; color: var(--muted); }

  /* Chat */
  #app { display: none; height: 100%; grid-template-columns: 320px 1fr; }
  #list { border-right: 1px solid var(--raised); display: flex; flex-direction: column; min-width: 0; }
  .head { padding: 12px 14px; border-bottom: 1px solid var(--raised); display: flex; align-items: center; gap: 10px; }
  .head strong { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #chats { overflow-y: auto; flex: 1; }
  .chat { padding: 12px 14px; border-bottom: 1px solid rgba(255,255,255,.04); cursor: pointer; }
  .chat:hover, .chat.on { background: var(--surface); }
  .chat .name { font-weight: 600; display: flex; justify-content: space-between; gap: 8px; }
  .chat .last { color: var(--muted); font-size: 13px; margin-top: 2px;
                overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .badge { background: var(--accent); border-radius: 10px; padding: 0 7px; font-size: 12px; }

  #thread { display: flex; flex-direction: column; min-width: 0; }
  #messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 8px; }
  .msg { max-width: min(560px, 75%); padding: 8px 12px; border-radius: 14px; background: var(--surface); }
  .msg.mine { align-self: flex-end; background: var(--accent); }
  .msg .who { font-size: 12px; color: var(--muted); margin-bottom: 2px; }
  .msg.mine .who { color: rgba(255,255,255,.75); }
  .msg .at { font-size: 11px; color: var(--muted); margin-top: 3px; text-align: right; }
  .msg.mine .at { color: rgba(255,255,255,.75); }
  .msg img { max-width: 100%; border-radius: 10px; display: block; }
  .msg a { color: inherit; }
  .system { align-self: center; color: var(--muted); font-size: 12px; }
  #composer { display: flex; gap: 8px; padding: 12px; border-top: 1px solid var(--raised); }
  #draft { flex: 1; background: var(--surface); border: 0; border-radius: 22px; padding: 12px 16px;
           color: var(--text); font: inherit; resize: none; max-height: 120px; }
  #empty { margin: auto; color: var(--muted); }
  .note { color: var(--muted); font-size: 12px; padding: 8px 14px; }
  @media (max-width: 760px) {
    #app { grid-template-columns: 1fr; }
    #list.hide, #thread.hide { display: none; }
  }
</style>
</head>
<body>

<section id="link">
  <div class="card">
    <h1>Viro on this computer</h1>
    <p>Open Viro on your phone, go to <b>You &rsaquo; Linked devices</b>, and enter this code.</p>
    <div class="code" id="code">········</div>
    <p id="linkState">Getting a code…</p>
    <ol class="steps">
      <li>On your phone, open Viro.</li>
      <li>Go to You, then Linked devices.</li>
      <li>Tap Link a device and type the code above.</li>
    </ol>
    <button class="ghost" id="newCode">Get a new code</button>
  </div>
</section>

<main id="app">
  <aside id="list">
    <div class="head">
      <strong id="meName">Viro</strong>
      <button class="ghost" id="signOut">Sign out</button>
    </div>
    <div id="chats"></div>
    <div class="note">Calls stay on your phone.</div>
  </aside>
  <section id="thread">
    <div class="head">
      <button class="ghost" id="back" style="display:none">&lsaquo;</button>
      <strong id="peer">Choose a chat</strong>
      <span id="conn" title="Connection"></span>
    </div>
    <div id="messages"><div id="empty">Your chats are on the left.</div></div>
    <form id="composer" style="display:none">
      <textarea id="draft" rows="1" placeholder="Message" autocomplete="off"></textarea>
      <button class="primary" type="submit">Send</button>
    </form>
  </section>
</main>

<script>
(function () {
  var API = location.origin;
  var store = {
    get access() { return localStorage.getItem('viro.access'); },
    get refresh() { return localStorage.getItem('viro.refresh'); },
    save: function (t) {
      localStorage.setItem('viro.access', t.accessToken);
      localStorage.setItem('viro.refresh', t.refreshToken);
      if (t.displayName) localStorage.setItem('viro.name', t.displayName);
      if (t.userId) localStorage.setItem('viro.userId', t.userId);
    },
    clear: function () {
      ['viro.access', 'viro.refresh', 'viro.name', 'viro.userId'].forEach(function (k) { localStorage.removeItem(k); });
    },
  };

  var state = { chats: [], openId: null, names: {}, socket: null, me: localStorage.getItem('viro.userId') };
  var el = function (id) { return document.getElementById(id); };

  // ---------------------------------------------------------------- requests

  function api(path, options, retry) {
    options = options || {};
    var headers = options.headers || {};
    headers['Authorization'] = 'Bearer ' + store.access;
    if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    return fetch(API + path, {
      method: options.method || 'GET',
      headers: headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
    }).then(function (res) {
      if (res.status === 401 && !retry && store.refresh) {
        return refreshTokens().then(function (ok) {
          if (!ok) { signOut(); throw new Error('signed out'); }
          return api(path, options, true);
        });
      }
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.status === 204 ? null : res.json();
    });
  }

  function refreshTokens() {
    return fetch(API + '/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: store.refresh }),
    }).then(function (res) {
      if (!res.ok) return false;
      return res.json().then(function (t) { store.save(t); return true; });
    }).catch(function () { return false; });
  }

  // ------------------------------------------------------------------ linking

  var link = null;
  var pollTimer = null;

  function startLink() {
    el('code').textContent = '········';
    el('linkState').textContent = 'Getting a code…';
    fetch(API + '/api/v1/devices/link/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label: browserLabel() }),
    }).then(function (r) { return r.json(); }).then(function (data) {
      link = data;
      el('code').textContent = data.code;
      el('linkState').textContent = 'Waiting for your phone…';
      clearInterval(pollTimer);
      pollTimer = setInterval(pollLink, 2000);
    }).catch(function () {
      el('linkState').textContent = 'Could not reach Viro. Check your connection.';
    });
  }

  function pollLink() {
    if (!link) return;
    fetch(API + '/api/v1/devices/link/' + link.linkId + '?secret=' + encodeURIComponent(link.secret))
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.status === 'APPROVED') {
          clearInterval(pollTimer);
          store.save(data);
          state.me = data.userId;
          showApp();
        } else if (data.status === 'EXPIRED') {
          clearInterval(pollTimer);
          el('linkState').textContent = 'That code expired. Get a new one.';
        }
      })
      .catch(function () { /* keep waiting */ });
  }

  function browserLabel() {
    var ua = navigator.userAgent;
    var browser = /Edg\\//.test(ua) ? 'Edge' : /Chrome\\//.test(ua) ? 'Chrome'
      : /Firefox\\//.test(ua) ? 'Firefox' : /Safari\\//.test(ua) ? 'Safari' : 'Browser';
    var os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android'
      : /Mac OS/.test(ua) ? 'Mac' : /Linux/.test(ua) ? 'Linux' : 'computer';
    return browser + ' on ' + os;
  }

  // --------------------------------------------------------------------- app

  function showApp() {
    el('link').style.display = 'none';
    el('app').style.display = 'grid';
    el('meName').textContent = localStorage.getItem('viro.name') || 'Viro';
    loadChats();
    connectSocket();
  }

  function signOut() {
    store.clear();
    if (state.socket) try { state.socket.close(); } catch (e) {}
    location.reload();
  }

  function loadChats() {
    return api('/api/v1/messages/conversations').then(function (chats) {
      state.chats = (chats || []).filter(function (c) { return !c.hidden; });
      return namesFor(state.chats).then(renderChats);
    }).catch(function () {});
  }

  /** Names come from each person's profile, so privacy settings still apply. */
  function namesFor(chats) {
    var wanted = [];
    chats.forEach(function (c) {
      if (c.isGroup) return;
      var peer = (c.participants || []).filter(function (p) { return p !== state.me; })[0];
      if (peer && !state.names[peer]) wanted.push(peer);
    });
    if (!wanted.length) return Promise.resolve();
    return Promise.all(wanted.map(function (id) {
      return api('/api/v1/me/profile/' + id)
        .then(function (p) { state.names[id] = p.displayName || 'Viro user'; })
        .catch(function () { state.names[id] = 'Viro user'; });
    }));
  }

  function titleOf(c) {
    if (c.isGroup) return c.title || 'Group';
    var peer = (c.participants || []).filter(function (p) { return p !== state.me; })[0];
    return state.names[peer] || 'Viro user';
  }

  function previewOf(c) {
    var m = c.lastMessage;
    if (!m) return '';
    if (m.deletedAt) return 'This message was deleted';
    if (m.type === 'ENCRYPTED') return '🔒 Encrypted message';
    if (m.type === 'VOICE') return '🎤 Voice message';
    if (m.type === 'IMAGE') return '📷 Photo';
    if (m.type === 'FILE') return '📎 ' + ((m.metadata && m.metadata.file && m.metadata.file.name) || 'Document');
    if (m.type === 'CONTACT') return '👤 Contact';
    if (m.type === 'LOCATION') return '📍 Location';
    if (m.type === 'POLL') return '📊 Poll';
    return m.body || '';
  }

  function renderChats() {
    var box = el('chats');
    box.innerHTML = '';
    state.chats.forEach(function (c) {
      var row = document.createElement('div');
      row.className = 'chat' + (c.id === state.openId ? ' on' : '');
      var name = document.createElement('div');
      name.className = 'name';
      var who = document.createElement('span');
      who.textContent = titleOf(c);
      name.appendChild(who);
      if (c.unread > 0) {
        var b = document.createElement('span');
        b.className = 'badge';
        b.textContent = c.unread;
        name.appendChild(b);
      }
      var last = document.createElement('div');
      last.className = 'last';
      last.textContent = previewOf(c);
      row.appendChild(name);
      row.appendChild(last);
      row.onclick = function () { openChat(c.id); };
      box.appendChild(row);
    });
  }

  function openChat(id) {
    state.openId = id;
    renderChats();
    var chat = state.chats.filter(function (c) { return c.id === id; })[0];
    el('peer').textContent = chat ? titleOf(chat) : 'Chat';
    el('composer').style.display = 'flex';
    // An encrypted chat can only be written to by something that holds keys,
    // and this page holds none. Reading is still possible; sending is not.
    var locked = !!(chat && chat.encrypted);
    el('draft').disabled = locked;
    el('draft').placeholder = locked ? 'Encrypted chat — reply from your phone' : 'Message';
    el('composer').querySelector('button').disabled = locked;
    if (window.matchMedia('(max-width: 760px)').matches) {
      el('list').classList.add('hide');
      el('thread').classList.remove('hide');
      el('back').style.display = 'inline-block';
    }
    api('/api/v1/messages/conversations/' + id).then(function (messages) {
      renderMessages(messages || []);
      return api('/api/v1/messages/conversations/' + id + '/read', { method: 'POST' });
    }).then(loadChats).catch(function () {});
  }

  function renderMessages(messages) {
    var box = el('messages');
    box.innerHTML = '';
    messages.forEach(function (m) { box.appendChild(messageNode(m)); });
    box.scrollTop = box.scrollHeight;
  }

  function messageNode(m) {
    if (m.type === 'SYSTEM') {
      var sys = document.createElement('div');
      sys.className = 'system';
      sys.textContent = m.body || '';
      return sys;
    }
    var node = document.createElement('div');
    node.className = 'msg' + (m.senderUserId === state.me ? ' mine' : '');
    node.dataset.id = m.id;
    var chat = state.chats.filter(function (c) { return c.id === m.conversationId; })[0];
    if (chat && chat.isGroup && m.senderUserId !== state.me) {
      var who = document.createElement('div');
      who.className = 'who';
      who.textContent = state.names[m.senderUserId] || 'Viro user';
      node.appendChild(who);
    }
    var body = document.createElement('div');
    if (m.deletedAt) {
      body.textContent = 'This message was deleted';
      body.style.fontStyle = 'italic';
    } else if (m.type === 'ENCRYPTED') {
      // This computer holds no keys of its own, so it genuinely cannot read
      // this. Saying so is better than an empty bubble.
      body.textContent = '🔒 Encrypted message — open it on your phone';
      body.style.fontStyle = 'italic';
      body.style.opacity = '0.75';
    } else if (m.type === 'IMAGE' && m.media) {
      var img = document.createElement('img');
      img.alt = 'Photo';
      mediaUrl(m.media.id).then(function (url) { if (url) img.src = url; });
      body.appendChild(img);
      if (m.body) body.appendChild(document.createTextNode(m.body));
    } else if (m.type === 'VOICE' && m.media) {
      var audio = document.createElement('audio');
      audio.controls = true;
      mediaUrl(m.media.id).then(function (url) { if (url) audio.src = url; });
      body.appendChild(audio);
    } else if (m.type === 'FILE' && m.media) {
      var name = (m.metadata && m.metadata.file && m.metadata.file.name) || m.media.originalName || 'Document';
      var a = document.createElement('a');
      a.textContent = '📎 ' + name;
      a.href = '#';
      a.onclick = function (e) {
        e.preventDefault();
        mediaUrl(m.media.id).then(function (url) {
          if (!url) return;
          var dl = document.createElement('a');
          dl.href = url;
          dl.download = name;
          dl.click();
        });
      };
      body.appendChild(a);
    } else if (m.type === 'LOCATION' && m.metadata && m.metadata.location) {
      var loc = m.metadata.location;
      var link2 = document.createElement('a');
      link2.textContent = '📍 ' + (loc.label || 'Location');
      link2.target = '_blank';
      link2.rel = 'noopener';
      link2.href = 'https://www.openstreetmap.org/?mlat=' + loc.lat + '&mlon=' + loc.lng + '#map=16/' + loc.lat + '/' + loc.lng;
      body.appendChild(link2);
    } else if (m.type === 'CONTACT' && m.metadata && m.metadata.contact) {
      body.textContent = '👤 ' + m.metadata.contact.name + (m.metadata.contact.phones && m.metadata.contact.phones[0] ? ' · ' + m.metadata.contact.phones[0] : '');
    } else {
      body.textContent = m.body || '';
    }
    node.appendChild(body);
    var at = document.createElement('div');
    at.className = 'at';
    at.textContent = timeOf(m.createdAt);
    node.appendChild(at);
    return node;
  }

  /**
   * The server sends my own message back over the socket as well as in the
   * reply to the send, so a message is placed by id rather than appended.
   */
  function placeMessage(m) {
    if (!m || m.conversationId !== state.openId) return;
    var box = document.getElementById('messages');
    var existing = box.querySelector('[data-id="' + m.id + '"]');
    var node = messageNode(m);
    if (existing) {
      box.replaceChild(node, existing);
      return;
    }
    box.appendChild(node);
    box.scrollTop = box.scrollHeight;
  }

  function timeOf(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  /** Media needs the token, so it is fetched and held as a blob. */
  var mediaCache = {};
  function mediaUrl(id) {
    if (!id) return Promise.resolve(null);
    if (mediaCache[id]) return Promise.resolve(mediaCache[id]);
    return fetch(API + '/api/v1/messages/media/' + id, { headers: { Authorization: 'Bearer ' + store.access } })
      .then(function (res) { return res.ok ? res.blob() : null; })
      .then(function (blob) {
        if (!blob) return null;
        mediaCache[id] = URL.createObjectURL(blob);
        return mediaCache[id];
      })
      .catch(function () { return null; });
  }

  // --------------------------------------------------------------- realtime

  function connectSocket() {
    if (!store.access) return;
    var url = API.replace(/^http/, 'ws') + '/api/v1/signaling/ws?token=' + encodeURIComponent(store.access);
    try { state.socket = new WebSocket(url); } catch (e) { return; }
    state.socket.onopen = function () { el('conn').textContent = ''; };
    state.socket.onclose = function () {
      el('conn').textContent = 'reconnecting…';
      setTimeout(function () { if (store.access) connectSocket(); }, 4000);
    };
    state.socket.onmessage = function (event) {
      var frame;
      try { frame = JSON.parse(event.data); } catch (e) { return; }
      if (frame.type === 'message.new' || frame.type === 'message.updated') {
        var msg = frame.message || (frame.payload && frame.payload.message);
        if (!msg) return;
        if (msg.conversationId === state.openId) {
          placeMessage(msg);
          api('/api/v1/messages/conversations/' + state.openId + '/read', { method: 'POST' }).catch(function () {});
        }
        loadChats();
      }
    };
  }

  // ----------------------------------------------------------------- sending

  el('composer').addEventListener('submit', function (e) {
    e.preventDefault();
    var text = el('draft').value.trim();
    if (!text || !state.openId) return;
    el('draft').value = '';
    api('/api/v1/messages', {
      method: 'POST',
      body: {
        conversationId: state.openId,
        body: text,
        clientMsgId: 'web-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8),
      },
    }).then(function (res) {
      if (res && res.message) placeMessage(res.message);
      loadChats();
    }).catch(function () {
      el('draft').value = text;
      alert('Could not send that message.');
    });
  });

  el('draft').addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      el('composer').dispatchEvent(new Event('submit', { cancelable: true }));
    }
  });

  el('back').onclick = function () {
    el('list').classList.remove('hide');
    el('thread').classList.add('hide');
  };
  el('signOut').onclick = signOut;
  el('newCode').onclick = startLink;

  // ------------------------------------------------------------------ start

  if (store.access) {
    api('/api/v1/me').then(function (me) {
      state.me = me.userId;
      localStorage.setItem('viro.userId', me.userId);
      localStorage.setItem('viro.name', me.displayName || 'Viro');
      showApp();
    }).catch(function () { store.clear(); startLink(); });
  } else {
    startLink();
  }
  setInterval(function () { if (store.access && state.openId === null) loadChats(); }, 20000);
})();
</script>
</body>
</html>`;
