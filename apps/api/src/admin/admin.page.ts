/**
 * The Viro admin console.
 *
 * One page, no build step, served by the API — the same shape as the web
 * companion, for the same reason: a dashboard that needs its own toolchain is
 * a dashboard nobody updates.
 *
 * It holds no data of its own. The key is asked for on arrival, kept in
 * sessionStorage so a refresh does not log you out, and sent on every request;
 * closing the tab forgets it. Nothing is cached.
 *
 * What it deliberately does NOT show: message contents, room contents, or
 * anything sealed. Running the platform is a reason to see that a room exists
 * and who opened it, never a licence to read what is being said in it.
 */
export const ADMIN_CONSOLE_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>Viro — console</title>
<style>
  :root {
    --bg: #00122C; --surface: #001A3F; --raised: #0A2548;
    --text: #F2F4F7; --muted: #9AA3AD; --accent: #42A5F5;
    --good: #4CD964; --bad: #FF6B4A; --line: rgba(255,255,255,.08);
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font: 15px/1.5 -apple-system, "Segoe UI", Roboto, system-ui, sans-serif;
  }
  header {
    display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap;
    padding: 18px 20px; border-bottom: 1px solid var(--line);
  }
  h1 { font-size: 20px; margin: 0; font-weight: 600; }
  header .sub { color: var(--muted); font-size: 13px; }
  header .spacer { flex: 1; }
  nav { display: flex; gap: 4px; padding: 10px 14px; flex-wrap: wrap; border-bottom: 1px solid var(--line); }
  nav button {
    background: transparent; border: 0; color: var(--muted); padding: 8px 14px;
    border-radius: 20px; cursor: pointer; font-size: 14px;
  }
  nav button[aria-current="true"] { background: var(--raised); color: var(--text); }
  main { padding: 20px; max-width: 1100px; }
  .cards { display: flex; flex-wrap: wrap; gap: 12px; }
  .card { background: var(--surface); border-radius: 14px; padding: 14px 16px; min-width: 150px; flex: 1; }
  .card .n { font-size: 26px; font-weight: 600; }
  .card .k { color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
  table { width: 100%; border-collapse: collapse; margin-top: 10px; }
  th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid var(--line); font-size: 14px; vertical-align: top; }
  th { color: var(--muted); font-weight: 500; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
  input, textarea, select, button.action {
    background: var(--raised); border: 1px solid var(--line); color: var(--text);
    border-radius: 10px; padding: 10px 12px; font: inherit;
  }
  input, textarea, select { width: 100%; }
  button.action { cursor: pointer; background: var(--accent); color: #00122C; border: 0; font-weight: 600; }
  button.ghost { background: transparent; border: 1px solid var(--line); color: var(--text); cursor: pointer;
    border-radius: 10px; padding: 7px 12px; font: inherit; }
  button.danger { color: var(--bad); }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .pill { font-size: 12px; padding: 2px 9px; border-radius: 20px; background: var(--raised); color: var(--muted); }
  .pill.good { color: var(--good); } .pill.bad { color: var(--bad); }
  .note { color: var(--muted); font-size: 13px; }
  .gate { max-width: 380px; margin: 14vh auto; text-align: center; }
  .gate input { margin: 14px 0 10px; text-align: center; }
  .err { color: var(--bad); font-size: 13px; min-height: 18px; }
  .unbuilt { background: var(--surface); border-radius: 14px; padding: 18px; }
  .unbuilt li { margin: 6px 0; color: var(--muted); }
  form.stack { display: grid; gap: 10px; max-width: 460px; }
  .promo {
    background: var(--raised); border: 1px solid var(--line); border-radius: 12px;
    padding: 10px 12px; margin: 8px 0; display: flex; gap: 10px; align-items: flex-start;
    cursor: grab;
  }
  .promo.dragging { opacity: .4; }
  .promo.over { border-color: var(--accent); }
  .grip { color: var(--muted); font-size: 18px; line-height: 1.2; cursor: grab; user-select: none; }
  .promo .t { font-weight: 600; }
  .promo .b { color: var(--muted); font-size: 13px; }
  .split { display: flex; gap: 20px; flex-wrap: wrap; align-items: flex-start; }
  .split > * { flex: 1; min-width: 300px; }
  .field { display: grid; gap: 4px; margin-bottom: 10px; }
  label { font-size: 13px; color: var(--muted); }
</style>
</head>
<body>
<div id="gate" class="gate">
  <h1>Viro console</h1>
  <p class="note">Admin key, or an access token for an admin account.</p>
  <input id="key" type="password" placeholder="Admin key" autocomplete="off" autofocus>
  <button class="action" id="enter" style="width:100%">Open</button>
  <p class="err" id="gate-err"></p>
</div>

<div id="app" hidden>
  <header>
    <h1>Viro</h1>
    <span class="sub" id="whoami">console</span>
    <span class="spacer"></span>
    <button class="ghost" id="refresh">Refresh</button>
    <button class="ghost" id="signout">Sign out</button>
  </header>
  <nav id="tabs"></nav>
  <main id="view"></main>
</div>

<script>
(function () {
  var KEY = 'viro.admin.key';
  var key = sessionStorage.getItem(KEY) || '';
  var tab = 'overview';

  var TABS = [
    ['overview', 'Overview'],
    ['people', 'People'],
    ['moments', 'Moments'],
    ['subscribers', 'Subscribers'],
    ['campaigns', 'Campaigns'],
    ['notify', 'Notifications'],
    ['security', 'Security'],
    ['roadmap', 'Not built yet'],
  ];

  function el(id) { return document.getElementById(id); }
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function when(v) {
    if (!v) return '—';
    var d = new Date(v);
    return isNaN(d) ? '—' : d.toLocaleString();
  }

  // Both credential shapes the guard accepts: a shared key, or an admin's
  // own bearer token. Whichever was typed is tried as both.
  function headers() {
    return { 'X-Admin-Key': key, 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json' };
  }

  function api(path, options) {
    var opts = options || {};
    opts.headers = headers();
    opts.cache = 'no-store';
    return fetch('/api/v1/admin' + path, opts).then(function (r) {
      if (r.status === 401) throw new Error('Those credentials were refused.');
      if (!r.ok) return r.text().then(function (t) { throw new Error(t || ('Request failed: ' + r.status)); });
      return r.status === 204 ? null : r.json();
    });
  }

  function show() {
    el('gate').hidden = true;
    el('app').hidden = false;
    el('tabs').innerHTML = TABS.map(function (t) {
      return '<button data-tab="' + t[0] + '" aria-current="' + (t[0] === tab) + '">' + t[1] + '</button>';
    }).join('');
    Array.prototype.forEach.call(el('tabs').children, function (b) {
      b.onclick = function () { tab = b.dataset.tab; show(); };
    });
    render();
  }

  function render() {
    var v = el('view');
    v.innerHTML = '<p class="note">Loading…</p>';
    if (tab === 'overview') return overview(v);
    if (tab === 'people') return people(v);
    if (tab === 'moments') return moments(v);
    if (tab === 'subscribers') return subscribers(v);
    if (tab === 'campaigns') return campaigns(v);
    if (tab === 'notify') return notify(v);
    if (tab === 'security') return security(v);
    if (tab === 'roadmap') return roadmap(v);
  }

  function fail(v, e) { v.innerHTML = '<p class="err">' + esc(e.message) + '</p>'; }

  function overview(v) {
    api('/overview').then(function (o) {
      var cards = [
        ['People', o.users], ['New this week', o.new_this_week], ['Suspended', o.suspended],
        ['Devices', o.devices], ['Devices with keys', o.keyed_devices],
        ['Moments live', o.live_moments], ['Moments this week', o.moments_this_week],
        ['Messages today', o.messages_today], ['Security events today', o.events_today],
      ];
      v.innerHTML = '<div class="cards">' + cards.map(function (c) {
        return '<div class="card"><div class="n">' + esc(c[1] == null ? '—' : c[1]) + '</div><div class="k">' + esc(c[0]) + '</div></div>';
      }).join('') + '</div>' +
      '<p class="note" style="margin-top:16px">Counted live from the database each time this loads, so nothing here can drift from the truth.</p>';
    }).catch(function (e) { fail(v, e); });
  }

  function people(v) {
    v.innerHTML = '<div class="row"><input id="q" placeholder="Name, Viro ID or phone number" style="max-width:360px">' +
      '<button class="ghost" id="go">Search</button></div><div id="list"></div>';
    function load(q) {
      api('/users?limit=50' + (q ? '&q=' + encodeURIComponent(q) : '')).then(function (rows) {
        el('list').innerHTML = rows.length === 0 ? '<p class="note">Nobody matched.</p>' :
          '<table><tr><th>Person</th><th>Status</th><th>Joined</th><th></th></tr>' + rows.map(function (u) {
            var suspended = u.status === 'SUSPENDED';
            return '<tr><td>' + esc(u.displayName || 'Viro user') + '<div class="note">' + esc(u.viroId || u.id) + '</div></td>' +
              '<td><span class="pill ' + (suspended ? 'bad' : 'good') + '">' + esc(u.status || '—') + '</span></td>' +
              '<td class="note">' + when(u.createdAt) + '</td>' +
              '<td><button class="ghost ' + (suspended ? '' : 'danger') + '" data-id="' + esc(u.id) + '" data-on="' + suspended + '">' +
              (suspended ? 'Restore' : 'Suspend') + '</button></td></tr>';
          }).join('') + '</table>';
        Array.prototype.forEach.call(el('list').querySelectorAll('button[data-id]'), function (b) {
          b.onclick = function () {
            var path = '/users/' + b.dataset.id + (b.dataset.on === 'true' ? '/unsuspend' : '/suspend');
            b.disabled = true;
            api(path, { method: 'POST' }).then(function () { load(el('q').value); }).catch(function (e) { alert(e.message); b.disabled = false; });
          };
        });
      }).catch(function (e) { fail(v, e); });
    }
    el('go').onclick = function () { load(el('q').value); };
    el('q').onkeydown = function (e) { if (e.key === 'Enter') load(el('q').value); };
    load('');
  }

  function moments(v) {
    api('/moments').then(function (rows) {
      v.innerHTML = '<p class="note">Live Moments. Contents are never shown here — only that a room exists, who opened it and how many are in it.</p>' +
        (rows.length === 0 ? '<p class="note">Nothing is open right now.</p>' :
        '<table><tr><th>Host</th><th>Activity</th><th>People</th><th>Ends</th><th></th></tr>' + rows.map(function (m) {
          return '<tr><td>' + esc(m.display_name || 'Viro user') + '</td>' +
            '<td>' + esc(m.intent || m.type) + (m.mood ? ' <span class="pill">' + esc(m.mood.toLowerCase()) + '</span>' : '') + '</td>' +
            '<td>' + esc(m.participants) + '</td><td class="note">' + when(m.expires_at) + '</td>' +
            '<td><button class="ghost danger" data-end="' + esc(m.id) + '">End</button></td></tr>';
        }).join('') + '</table>');
      Array.prototype.forEach.call(v.querySelectorAll('button[data-end]'), function (b) {
        b.onclick = function () {
          if (!confirm('End this Moment for everyone in it?')) return;
          b.disabled = true;
          api('/moments/' + b.dataset.end, { method: 'DELETE' }).then(render).catch(function (e) { alert(e.message); b.disabled = false; });
        };
      });
    }).catch(function (e) { fail(v, e); });
  }

  function subscribers(v) {
    Promise.all([api('/plan-totals'), api('/subscriptions')]).then(function (r) {
      var totals = r[0], rows = r[1];
      v.innerHTML = '<div class="cards">' + (totals.length ? totals.map(function (t) {
        return '<div class="card"><div class="n">' + esc(t.people) + '</div><div class="k">' +
          esc((t.plan_name || 'no plan') + ' · ' + t.status) + '</div></div>';
      }).join('') : '<p class="note">Nobody has a plan yet.</p>') + '</div>' +
      (rows.length ? '<table><tr><th>Person</th><th>Plan</th><th>Status</th><th>Since</th><th>Expires</th></tr>' + rows.map(function (s) {
        return '<tr><td>' + esc(s.display_name || 'Viro user') + '</td><td>' + esc(s.plan_name || '—') + '</td>' +
          '<td>' + esc(s.status) + '</td><td class="note">' + when(s.created_at) + '</td><td class="note">' + when(s.expires_at) + '</td></tr>';
      }).join('') + '</table>' : '');
    }).catch(function (e) { fail(v, e); });
  }

  // Campaigns: a period, an audience, and promotions in an order somebody
  // arranged by dragging them.
  function campaigns(v) {
    api('/campaigns').then(function (rows) {
      v.innerHTML = '<div class="split"><div>' +
        '<h3 style="margin:0 0 8px">Campaigns</h3>' +
        (rows.length === 0 ? '<p class="note">None yet.</p>' :
          '<table><tr><th>Name</th><th>When</th><th>State</th><th></th></tr>' + rows.map(function (c) {
            var period = (c.starts_at ? when(c.starts_at) : 'any time') + ' → ' + (c.ends_at ? when(c.ends_at) : 'no end');
            return '<tr><td><strong>' + esc(c.name) + '</strong><div class="note">' + esc(c.audience.toLowerCase()) +
              ' · ' + esc(c.promotions) + ' promotion' + (c.promotions === 1 ? '' : 's') + '</div></td>' +
              '<td class="note">' + esc(period) + '</td>' +
              '<td><span class="pill ' + (c.running ? 'good' : '') + '">' +
                esc(c.running ? 'running' : c.status.toLowerCase()) + '</span></td>' +
              '<td><button class="ghost" data-open="' + esc(c.id) + '">Open</button></td></tr>';
          }).join('') + '</table>') +
        '</div><div>' +
        '<h3 style="margin:0 0 8px">New campaign</h3>' +
        '<form class="stack" id="nc">' +
          '<div class="field"><label>Name</label><input id="nc-name" maxlength="80" required></div>' +
          '<div class="field"><label>Audience</label><select id="nc-aud">' +
            '<option value="EVERYONE">Everyone</option>' +
            '<option value="SUBSCRIBERS">Subscribers</option>' +
            '<option value="FREE">People without a plan</option>' +
          '</select></div>' +
          '<div class="field"><label>Starts</label><input id="nc-from" type="datetime-local"></div>' +
          '<div class="field"><label>Ends</label><input id="nc-to" type="datetime-local"></div>' +
          '<button class="action" type="submit">Create as draft</button>' +
          '<p class="err" id="nc-err"></p>' +
        '</form>' +
        '<p class="note">A campaign only reaches anybody once it is Scheduled and inside its period. Leave a date empty for open-ended.</p>' +
        '</div></div>';

      Array.prototype.forEach.call(v.querySelectorAll('button[data-open]'), function (b) {
        b.onclick = function () { campaignDetail(v, b.dataset.open); };
      });

      el('nc').onsubmit = function (e) {
        e.preventDefault();
        var body = {
          name: el('nc-name').value,
          audience: el('nc-aud').value,
          startsAt: local(el('nc-from').value),
          endsAt: local(el('nc-to').value),
        };
        if (!body.startsAt) delete body.startsAt;
        if (!body.endsAt) delete body.endsAt;
        api('/campaigns', { method: 'POST', body: JSON.stringify(body) })
          .then(function (c) { campaignDetail(v, c.id); })
          .catch(function (err) { el('nc-err').textContent = err.message; });
      };
    }).catch(function (e) { fail(v, e); });
  }

  // datetime-local gives "2026-09-22T18:30" with no zone; the server wants a
  // real instant, so it is read as this machine's time rather than guessed at.
  function local(value) {
    if (!value) return undefined;
    var d = new Date(value);
    return isNaN(d) ? undefined : d.toISOString();
  }
  function forInput(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d)) return '';
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function campaignDetail(v, id) {
    api('/campaigns/' + id).then(function (c) {
      v.innerHTML = '<button class="ghost" id="back">← All campaigns</button>' +
        '<h3 style="margin:14px 0 4px">' + esc(c.name) + '</h3>' +
        '<p class="note">' + (c.running ? 'Running now' : 'Not running') + ' · ' + esc(c.status.toLowerCase()) + '</p>' +
        '<div class="split"><div>' +
          '<h4>Period and audience</h4>' +
          '<form class="stack" id="ed">' +
            '<div class="field"><label>Name</label><input id="ed-name" maxlength="80" value="' + esc(c.name) + '"></div>' +
            '<div class="field"><label>Audience</label><select id="ed-aud">' +
              ['EVERYONE', 'SUBSCRIBERS', 'FREE'].map(function (a) {
                return '<option value="' + a + '"' + (c.audience === a ? ' selected' : '') + '>' + a.toLowerCase() + '</option>';
              }).join('') + '</select></div>' +
            '<div class="field"><label>Starts</label><input id="ed-from" type="datetime-local" value="' + forInput(c.starts_at) + '"></div>' +
            '<div class="field"><label>Ends</label><input id="ed-to" type="datetime-local" value="' + forInput(c.ends_at) + '"></div>' +
            '<div class="field"><label>State</label><select id="ed-status">' +
              ['DRAFT', 'SCHEDULED', 'ARCHIVED'].map(function (a) {
                return '<option value="' + a + '"' + (c.status === a ? ' selected' : '') + '>' + a.toLowerCase() + '</option>';
              }).join('') + '</select></div>' +
            '<button class="action" type="submit">Save</button>' +
            '<p class="err" id="ed-err"></p>' +
          '</form>' +
          '<button class="ghost danger" id="del" style="margin-top:10px">Delete campaign</button>' +
        '</div><div>' +
          '<h4>Promotions <span class="note">— drag to reorder</span></h4>' +
          '<div id="promos"></div>' +
          '<form class="stack" id="np" style="margin-top:12px">' +
            '<div class="field"><label>Title</label><input id="np-t" maxlength="80" required></div>' +
            '<div class="field"><label>Message</label><textarea id="np-b" rows="2" maxlength="240"></textarea></div>' +
            '<div class="field"><label>Opens (a Viro route, optional)</label><input id="np-a" maxlength="120" placeholder="moments"></div>' +
            '<button class="action" type="submit">Add promotion</button>' +
            '<p class="err" id="np-err"></p>' +
          '</form>' +
        '</div></div>';

      el('back').onclick = function () { campaigns(v); };

      el('ed').onsubmit = function (e) {
        e.preventDefault();
        api('/campaigns/' + id, { method: 'PATCH', body: JSON.stringify({
          name: el('ed-name').value,
          audience: el('ed-aud').value,
          status: el('ed-status').value,
          startsAt: local(el('ed-from').value) || '',
          endsAt: local(el('ed-to').value) || '',
        }) }).then(function () { campaignDetail(v, id); })
          .catch(function (err) { el('ed-err').textContent = err.message; });
      };

      el('del').onclick = function () {
        if (!confirm('Delete this campaign and everything in it?')) return;
        api('/campaigns/' + id, { method: 'DELETE' }).then(function () { campaigns(v); })
          .catch(function (err) { alert(err.message); });
      };

      el('np').onsubmit = function (e) {
        e.preventDefault();
        api('/campaigns/' + id + '/promotions', { method: 'POST', body: JSON.stringify({
          title: el('np-t').value, body: el('np-b').value, action: el('np-a').value,
        }) }).then(function () { campaignDetail(v, id); })
          .catch(function (err) { el('np-err').textContent = err.message; });
      };

      drawPromos(id, c.promotions);
    }).catch(function (e) { fail(v, e); });
  }

  /**
   * The draggable list.
   *
   * Plain HTML5 drag events, no library: the order is rearranged in the page
   * as you drag, and the whole order is sent once on drop. Sending a move per
   * hover would write to the database every few pixels.
   */
  function drawPromos(campaignId, promos) {
    var host = el('promos');
    if (!promos.length) { host.innerHTML = '<p class="note">Nothing in this campaign yet.</p>'; return; }
    host.innerHTML = promos.map(function (p) {
      return '<div class="promo" draggable="true" data-id="' + esc(p.id) + '">' +
        '<span class="grip" aria-hidden="true">⠿</span>' +
        '<div style="flex:1"><div class="t">' + esc(p.title) + '</div>' +
        (p.body ? '<div class="b">' + esc(p.body) + '</div>' : '') +
        (p.action ? '<div class="b">opens: ' + esc(p.action) + '</div>' : '') + '</div>' +
        '<button class="ghost danger" data-rm="' + esc(p.id) + '">Remove</button></div>';
    }).join('');

    var dragging = null;
    Array.prototype.forEach.call(host.querySelectorAll('.promo'), function (row) {
      row.addEventListener('dragstart', function () { dragging = row; row.classList.add('dragging'); });
      row.addEventListener('dragend', function () {
        row.classList.remove('dragging');
        Array.prototype.forEach.call(host.querySelectorAll('.promo'), function (r) { r.classList.remove('over'); });
        dragging = null;
        save();
      });
      row.addEventListener('dragover', function (e) {
        e.preventDefault();
        if (!dragging || dragging === row) return;
        row.classList.add('over');
        // Above or below the midpoint decides which side it lands on, so the
        // list moves under the cursor rather than after it.
        var box = row.getBoundingClientRect();
        var after = (e.clientY - box.top) > box.height / 2;
        host.insertBefore(dragging, after ? row.nextSibling : row);
      });
      row.addEventListener('dragleave', function () { row.classList.remove('over'); });
    });

    Array.prototype.forEach.call(host.querySelectorAll('button[data-rm]'), function (b) {
      b.onclick = function () {
        api('/promotions/' + b.dataset.rm, { method: 'DELETE' })
          .then(function () { campaignDetail(el('view'), campaignId); })
          .catch(function (e) { alert(e.message); });
      };
    });

    function save() {
      var ids = Array.prototype.map.call(host.querySelectorAll('.promo'), function (r) { return r.dataset.id; });
      api('/campaigns/' + campaignId + '/order', { method: 'PATCH', body: JSON.stringify({ ids: ids }) })
        .catch(function (e) { alert('Order not saved: ' + e.message); });
    }
  }

  function notify(v) {
    v.innerHTML = '<form class="stack" id="f">' +
      '<label>Who <input id="uid" placeholder="User id, or leave empty for everyone with a device"></label>' +
      '<label>Title <input id="t" maxlength="80" required></label>' +
      '<label>Message <textarea id="b" rows="3" maxlength="240" required></textarea></label>' +
      '<button class="action" type="submit">Send</button>' +
      '<p class="err" id="ne"></p></form>' +
      '<p class="note">A broadcast reaches everyone with a registered device, up to five thousand, and is written to the security log with who sent it.</p>';
    el('f').onsubmit = function (e) {
      e.preventDefault();
      var uid = el('uid').value.trim();
      if (!uid && !confirm('Send this to everyone with a device?')) return;
      api('/notify', { method: 'POST', body: JSON.stringify({ userId: uid || undefined, title: el('t').value, body: el('b').value }) })
        .then(function (r) { el('ne').textContent = 'Sent to ' + r.sentTo + '.'; el('t').value = ''; el('b').value = ''; })
        .catch(function (err) { el('ne').textContent = err.message; });
    };
  }

  function security(v) {
    api('/security-events?limit=100').then(function (rows) {
      v.innerHTML = rows.length === 0 ? '<p class="note">Nothing logged.</p>' :
        '<table><tr><th>When</th><th>Event</th><th>Severity</th><th>Detail</th></tr>' + rows.map(function (e) {
          return '<tr><td class="note">' + when(e.createdAt || e.created_at) + '</td><td>' + esc(e.eventType || e.event_type) + '</td>' +
            '<td>' + esc(e.severity) + '</td><td class="note">' + esc(JSON.stringify(e.metadata || {})) + '</td></tr>';
        }).join('') + '</table>';
    }).catch(function (e) { fail(v, e); });
  }

  // Said plainly rather than shown as controls that do nothing. A console
  // with dead buttons is worse than one that admits its own edges.
  function roadmap(v) {
    v.innerHTML = '<div class="unbuilt"><p>These were asked for and are <strong>not built</strong>. Nothing on the other tabs is a mock — everything there is wired to a real endpoint.</p><ul>' +
      [       'Theme changing from here — the app ships one palette and a wallpaper each person sets; pushing a theme centrally is a new capability, not a switch.',
       'Account recovery — recovery today is the encrypted-backup key held on the phone, and deliberately not something an admin can perform. Any admin-side recovery has to be designed against that promise first.',
       'Customer support threads — a person can be found and suspended here, but there is no ticketing, no conversation and no notes.',
       'Creating or editing Moments on somebody\\'s behalf — ending one is here; opening one as another person is impersonation and needs a decision before it is code.',
       'Blocking controls beyond suspension — per-pair blocks exist in the app and are not exposed here.'
      ].map(function (s) { return '<li>' + s + '</li>'; }).join('') + '</ul></div>';
  }

  el('enter').onclick = function () {
    var v = el('key').value.trim();
    if (!v) return;
    key = v;
    api('/overview').then(function () {
      sessionStorage.setItem(KEY, key);
      show();
    }).catch(function (e) { el('gate-err').textContent = e.message; key = ''; });
  };
  el('key').onkeydown = function (e) { if (e.key === 'Enter') el('enter').click(); };
  el('refresh').onclick = render;
  el('signout').onclick = function () {
    sessionStorage.removeItem(KEY); key = '';
    el('app').hidden = true; el('gate').hidden = false; el('key').value = '';
  };

  if (key) {
    api('/overview').then(show).catch(function () { sessionStorage.removeItem(KEY); key = ''; });
  }
})();
</script>
</body>
</html>`;
