(function () {
  var API = '';
  var secret = sessionStorage.getItem('torve_admin_secret') || '';
  var PAGE_SIZE = 25;
  var state = { offset: 0, total: 0, expandedId: null, lastQuery: null };
  var detailCache = {}; // user_id -> detail JSON
  var activityCache = {}; // user_id:device_id -> activity JSON

  // ── Auth ──────────────────────────────────────────────────────
  document.getElementById('authBtn').addEventListener('click', doAuth);
  document.getElementById('secretInput').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') doAuth();
  });

  function doAuth() {
    secret = document.getElementById('secretInput').value.trim();
    if (!secret) return;
    fetch(API + '/admin/users?limit=1', { headers: { 'X-Admin-Secret': secret } })
      .then(function (r) {
        if (r.ok) {
          sessionStorage.setItem('torve_admin_secret', secret);
          showPanel();
        } else {
          authMsg('Invalid secret.', 'error');
        }
      })
      .catch(function () { authMsg('Connection failed.', 'error'); });
  }

  function authMsg(text, kind) {
    var el = document.getElementById('authMsg');
    el.textContent = text;
    el.style.color = kind === 'error' ? '#f87171' : '#4ade80';
  }

  function showPanel() {
    document.getElementById('authGate').style.display = 'none';
    document.getElementById('adminPanel').style.display = 'block';
    bindFilters();
    loadUsers();
  }

  // ── Fetch helper ──────────────────────────────────────────────
  function adminFetch(path, opts) {
    opts = opts || {};
    opts.headers = Object.assign(
      { 'X-Admin-Secret': secret, 'Content-Type': 'application/json' },
      opts.headers || {}
    );
    return fetch(API + path, opts).then(function (r) {
      if (r.status === 403) {
        sessionStorage.removeItem('torve_admin_secret');
        location.reload();
        return Promise.reject(new Error('forbidden'));
      }
      return r.json().then(function (j) { return { ok: r.ok, status: r.status, data: j }; });
    });
  }

  // ── Filters ───────────────────────────────────────────────────
  var debouncedSearch = debounce(function () { state.offset = 0; loadUsers(); }, 250);

  function bindFilters() {
    document.getElementById('searchInput').addEventListener('input', debouncedSearch);
    ['filterPlatform','filterApple'].forEach(function(id) { document.getElementById(id).addEventListener('change', function(){ state.offset=0; loadUsers(); }); });
    document.getElementById('filterVerified').addEventListener('change', function () { state.offset = 0; loadUsers(); });
    document.getElementById('filterPremium').addEventListener('change', function () { state.offset = 0; loadUsers(); });
    document.getElementById('filterActive').addEventListener('change', function () { state.offset = 0; loadUsers(); });
    document.getElementById('prevBtn').addEventListener('click', function () {
      if (state.offset >= PAGE_SIZE) { state.offset -= PAGE_SIZE; loadUsers(); }
    });
    document.getElementById('nextBtn').addEventListener('click', function () {
      if (state.offset + PAGE_SIZE < state.total) { state.offset += PAGE_SIZE; loadUsers(); }
    });
  }

  function buildQuery() {
    var q = document.getElementById('searchInput').value.trim();
    var v = document.getElementById('filterVerified').value;
    var p = document.getElementById('filterPremium').value;
    var a = document.getElementById('filterActive').value;
    var params = '?limit=' + PAGE_SIZE + '&offset=' + state.offset;
    if (q) params += '&q=' + encodeURIComponent(q);
    if (v) params += '&is_verified=' + v;
    if (p) params += '&has_premium=' + p;
    if (a) params += '&is_active=' + a;
    var platform = document.getElementById('filterPlatform').value;
    var apple = document.getElementById('filterApple').value;
    if (platform) params += '&platform=' + encodeURIComponent(platform);
    if (apple) params += '&apple_status=' + encodeURIComponent(apple);
    return params;
  }

  function loadUsers() {
    var query = buildQuery();
    state.lastQuery = query;
    document.getElementById('userList').innerHTML = '<div class="empty-msg">Loading...</div>';
    adminFetch('/admin/account-insights' + query).then(function (res) {
      // If the user has typed more by the time we return, ignore stale.
      if (state.lastQuery !== query) return;
      if (!res.ok) {
        document.getElementById('userList').innerHTML = '<div class="msg msg-error">Failed to load users.</div>';
        return;
      }
      state.total = res.data.total;
      renderUsers(res.data.users);
      renderPagination();
    }).catch(function () {
      if (state.lastQuery !== query) return;
      document.getElementById('paginationBar').style.display = 'none';
      document.getElementById('userList').innerHTML = '<div class="msg msg-error" role="alert">Could not reach the account service. <button class="btn btn-secondary" id="retryUsers">Try again</button></div>';
      document.getElementById('retryUsers').addEventListener('click', loadUsers);
    });
  }

  function renderPagination() {
    var bar = document.getElementById('paginationBar');
    if (state.total === 0) { bar.style.display = 'none'; return; }
    bar.style.display = 'flex';
    var end = Math.min(state.offset + PAGE_SIZE, state.total);
    document.getElementById('paginationInfo').textContent = (state.offset + 1) + '–' + end + ' of ' + state.total;
    document.getElementById('prevBtn').disabled = state.offset === 0;
    document.getElementById('nextBtn').disabled = state.offset + PAGE_SIZE >= state.total;
  }

  function renderUsers(users) {
    if (!users.length) {
      document.getElementById('userList').innerHTML = '<div class="empty-msg">No users found.</div>';
      return;
    }
    var h = '<table class="user-list"><thead><tr>';
    h += '<th>Account</th><th>Platforms</th><th>Apple access</th><th>Last contact</th><th>Measured use</th><th>Account state</th><th>Beta</th>';
    h += '</tr></thead><tbody id="userTbody">';
    users.forEach(function (u) {
      h += '<tr class="row-clickable" tabindex="0" aria-expanded="false" data-uid="' + u.id + '">';
      h += '<td class="email">' + esc(u.email) + '</td>';
      h += '<td>' + platformBadges(u.platforms) + '</td>';
      h += '<td>' + appleBadge(u.apple_subscription) + '</td>';
      h += '<td>' + formatDate(u.last_seen_at) + '</td>';
      h += '<td>' + (u.usage && u.usage.measured_since ? duration(u.usage.foreground_ms) : 'Not yet measured') + '</td>';
      h += '<td>' + statusBadges(u) + '</td>';
      h += '<td>' + formatBetaAccess(u.beta_access, true) + '</td>';


      h += '</tr>';
      h += '<tr id="detail-row-' + u.id + '" style="display:none"><td colspan="7" style="padding:0"><div id="detail-' + u.id + '"></div></td></tr>';
    });
    h += '</tbody></table>';
    document.getElementById('userList').innerHTML = h;

    document.querySelectorAll('tr.row-clickable').forEach(function (tr) {
      tr.addEventListener('click', function () { toggleDetail(this.getAttribute('data-uid')); });
      tr.addEventListener('keydown',function(ev){if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();toggleDetail(this.getAttribute('data-uid'));}});
    });

    if (state.expandedId && document.getElementById('detail-row-' + state.expandedId)) {
      // Re-expand if still on page (e.g., after pagination back)
      openDetail(state.expandedId);
    }
  }

  function statusBadges(u) {
    var out = '';
    if (!u.is_active) out += '<span class="badge badge-revoked">Locked</span>';
    if (u.has_lifetime_access) out += '<span class="badge badge-muted">Legacy lifetime</span>';
    else if (u.has_premium_access) out += '<span class="badge badge-muted">Legacy grant</span>';
    if (u.is_verified) out += '<span class="badge badge-muted">Verified</span>';
    else out += '<span class="badge badge-warn">Unverified</span>';
    return out || '<span class="badge badge-muted">Free</span>';
  }

  // ── Detail expand ─────────────────────────────────────────────
  function toggleDetail(uid) {
    if (state.expandedId === uid) {
      closeDetail(uid);
      state.expandedId = null;
    } else {
      if (state.expandedId) closeDetail(state.expandedId);
      state.expandedId = uid;
      openDetail(uid);
    }
  }

  function closeDetail(uid) {
    var row = document.getElementById('detail-row-' + uid);
    if (row) row.style.display = 'none';
    var clickRow = document.querySelector('tr[data-uid="' + uid + '"]');
    if (clickRow) { clickRow.classList.remove('row-expanded'); clickRow.setAttribute('aria-expanded','false'); }
  }

  function openDetail(uid) {
    var row = document.getElementById('detail-row-' + uid);
    var clickRow = document.querySelector('tr[data-uid="' + uid + '"]');
    if (!row) return;
    row.style.display = 'table-row';
    if (clickRow) { clickRow.classList.add('row-expanded'); clickRow.setAttribute('aria-expanded','true'); }
    var holder = document.getElementById('detail-' + uid);
    holder.innerHTML = '<div class="detail-panel"><div class="empty-msg">Loading...</div></div>';
    fetchDetail(uid).then(function (d) { renderDetail(uid, d); }).catch(function () { renderDetail(uid, null); });
  }

  function fetchDetail(uid, force) {
    if (!force && detailCache[uid]) return Promise.resolve(detailCache[uid]);
    return Promise.all([adminFetch('/admin/users/' + uid),adminFetch('/admin/account-insights/' + uid)]).then(function(responses){
      if(!responses[0].ok || !responses[1].ok) return null;
      var d=responses[0].data, insights=responses[1].data;
      d.insights=insights; d.user=Object.assign({},d.user,insights.user); d.devices=insights.devices;
      detailCache[uid]=d; return d;
    });
  }

  function renderDetail(uid, d) {
    var holder = document.getElementById('detail-' + uid);
    if (!holder) return;
    if (!d) {
      holder.innerHTML = '<div class="detail-panel"><div class="msg msg-error" role="alert">Could not load this account. <button class="btn btn-secondary" data-retry-detail>Try again</button></div></div>';
      holder.querySelector('[data-retry-detail]').addEventListener('click', function () { openDetail(uid); });
      return;
    }
    var u = d.user;
    var h = '<div class="detail-panel">';

    // Profile grid
    h += '<div class="detail-grid">';
    h += kv('User ID', '<code>' + u.id + '</code>');
    h += kv('Email', esc(u.email));
    h += kv('Display name', esc(u.display_name || '—'));
    h += kv('Status', statusBadges(u));
    h += kv('Beta access', formatBetaAccess(d.beta_access || u.beta_access));
    h += kv('Created', formatDate(u.created_at));
    h += kv('Updated', formatDate(u.updated_at));
    h += kv('First registered device', platformName(u.first_registered_platform));
    h += kv('Last device contact', formatDate(u.last_seen_at));
    h += kv('Apple subscription', appleBadge(u.apple_subscription));
    h += kv('Apple slots', u.apple_subscription.activated_devices + ' / 5 · Apple only');
    h += kv('Device cap override', u.device_cap_override == null ? 'Default (' + esc(u.device_limit || 'server') + ')' : String(u.device_cap_override));
    h += kv('Active devices', formatDeviceUsage(u));
    h += '</div>';

    h += usageSummary(u.usage,d.insights.daily_usage);
    h += '<p class="insights-note">Android and Fire TV access is independent. Legacy grants, beta access and the account device-cap override do not unlock Apple Premium.</p>';
    // Actions
    h += '<div class="detail-actions">';
    if (!u.is_verified) h += actionBtn('btn-secondary', 'force-verify', uid, 'Force-verify email');
    if (u.is_active) h += actionBtn('btn-danger', 'lock', uid, 'Lock account');
    else h += actionBtn('btn-secondary', 'unlock', uid, 'Unlock account');
    if (!u.has_lifetime_access) h += actionBtn('btn-secondary', 'grant-lifetime', uid, 'Grant legacy lifetime');
    h += actionBtn('btn-secondary', 'grant-temporary', uid, 'Grant legacy temporary');
    h += actionBtn('btn-secondary', 'extend-beta', uid, betaActionLabel(d.beta_access || u.beta_access));
    if ((d.beta_access || u.beta_access || {}).active) h += actionBtn('btn-danger', 'revoke-beta', uid, 'Revoke beta');
    h += actionBtn('btn-secondary', 'set-device-cap', uid, 'Set device cap');
    h += actionBtn('btn-secondary', 'refresh-flags', uid, 'Recompute flags');
    h += '<span id="detail-msg-' + uid + '" style="margin-left:auto"></span>';
    h += '</div>';

    h += '<div class="sub-section"><h4>Discord beta access</h4>';
    h += '<div class="detail-grid">';
    h += kv('Grant state', formatBetaAccess(d.beta_access || u.beta_access));
    h += kv('Grant ID', (d.beta_access && d.beta_access.id) ? '<code>' + esc(d.beta_access.id) + '</code>' : '—');
    h += kv('Discord user ID', (d.beta_access && d.beta_access.discord_user_id) ? '<code>' + esc(d.beta_access.discord_user_id) + '</code>' : '—');
    h += kv('Starts', formatDate(d.beta_access && d.beta_access.starts_at));
    h += kv('Expires', formatDate(d.beta_access && d.beta_access.expires_at));
    h += kv('Revoked', formatDate(d.beta_access && d.beta_access.revoked_at));
    h += '</div>';
    h += '</div>';

    h += '<div class="sub-section"><h4>Registered account devices (' + d.devices.length + ')</h4><div class="device-grid">';
    d.devices.forEach(function(dev){ h+=deviceCard(uid,dev,false); });
    h += '</div></div>';
    h += '<div class="sub-section"><h4>Apple device slots &amp; activity</h4><div class="device-grid">';
    d.insights.apple_devices.forEach(function(dev){ h+=deviceCard(uid,dev,true); });
    h += '</div>';
    if(!d.insights.apple_devices.length) h+='<div class="empty-msg">No Apple device has reported or been activated. Android devices consume no Apple slots.</div>';
    h += '</div>' + appleLedger(d.insights);

    // Entitlements
    h += '<div class="sub-section"><h4>Entitlements (' + d.entitlements.length + ')</h4>';
    if (!d.entitlements.length) h += '<div class="empty-msg">No entitlements.</div>';
    else {
      h += '<table class="sub-table"><thead><tr><th>Type</th><th>Source</th><th>Source ref</th><th>Status</th><th>Granted</th><th>Expires</th><th>Origin device</th><th></th></tr></thead><tbody>';
      d.entitlements.forEach(function (e) {
        h += '<tr>';
        h += '<td>' + esc(e.entitlement_type) + '</td>';
        h += '<td>' + esc(e.source) + '</td>';
        h += '<td><code>' + esc(e.source_ref) + '</code></td>';
        h += '<td>' + entStatusBadge(e.status) + '</td>';
        h += '<td>' + formatDate(e.granted_at) + '</td>';
        h += '<td>' + (e.expires_at ? formatDate(e.expires_at) : '—') + '</td>';
        h += '<td>' + (e.originating_device_id ? '<code>' + e.originating_device_id.slice(0, 8) + '…</code>' : '—') + '</td>';
        h += '<td>' + (e.status === 'active' ? '<button class="btn btn-danger btn-sm" data-action="revoke-entitlement" data-uid="' + uid + '" data-id="' + e.id + '">Revoke</button>' : '') + '</td>';
        h += '</tr>';
      });
      h += '</tbody></table>';
    }
    h += '</div>';

    // Recent payments
    if (d.payments.length) {
      h += '<div class="sub-section"><h4>Recent Paddle payments (' + d.payments.length + ')</h4>';
      h += '<table class="sub-table"><thead><tr><th>Txn</th><th>Amount</th><th>Status</th><th>Discount</th><th>Granted?</th><th>When</th></tr></thead><tbody>';
      d.payments.forEach(function (p) {
        h += '<tr>';
        h += '<td><code>' + esc(p.paddle_transaction_id) + '</code></td>';
        h += '<td>' + esc(p.amount) + ' ' + esc(p.currency) + '</td>';
        h += '<td>' + esc(p.status) + (p.refunded_at ? ' (refunded)' : '') + '</td>';
        h += '<td>' + (p.discount_code ? '<code>' + esc(p.discount_code) + '</code>' : '—') + '</td>';
        h += '<td>' + (p.entitlement_granted ? '✓' : '—') + '</td>';
        h += '<td>' + formatDate(p.created_at) + '</td>';
        h += '</tr>';
      });
      h += '</tbody></table></div>';
    }

    // Lifetime grant ledger
    if (d.lifetime_grants.length) {
      h += '<div class="sub-section"><h4>Lifetime grant ledger (' + d.lifetime_grants.length + ')</h4>';
      h += '<table class="sub-table"><thead><tr><th>Source</th><th>Source ref</th><th>Granted</th><th>Revoked</th><th>Notes</th></tr></thead><tbody>';
      d.lifetime_grants.forEach(function (g) {
        h += '<tr>';
        h += '<td>' + esc(g.source) + '</td>';
        h += '<td><code>' + esc(g.source_ref) + '</code></td>';
        h += '<td>' + formatDate(g.granted_at) + '</td>';
        h += '<td>' + (g.revoked_at ? formatDate(g.revoked_at) + ' (' + esc(g.revoke_reason || '') + ')' : '—') + '</td>';
        h += '<td>' + esc(g.notes || '—') + '</td>';
        h += '</tr>';
      });
      h += '</tbody></table></div>';
    }

    h += '</div>';
    holder.innerHTML = h;

    holder.querySelectorAll('[data-action]').forEach(function (btn) {
      btn.addEventListener('click', function (ev) {
        ev.stopPropagation();
        handleAction(btn.getAttribute('data-action'), btn.getAttribute('data-uid'), btn.getAttribute('data-id'));
      });
    });
  }


  function duration(ms) {
    if(ms==null) return 'Not yet measured';
    var seconds=Math.max(0,Math.floor(ms/1000)),minutes=Math.floor(seconds/60),hours=Math.floor(minutes/60);
    return hours ? hours+'h '+(minutes%60)+'m' : minutes ? minutes+'m '+(seconds%60)+'s' : seconds+'s';
  }
  function platformName(p) { return ({android:'Android', 'android-tv':'Android TV',firetv:'Fire TV',ios:'iPhone',ipados:'iPad',tvos:'Apple TV',desktop:'Desktop',web:'Web',unknown:'Not reported'})[p] || 'Not reported'; }
  function platformBadges(platforms) { return Object.keys(platforms||{}).map(function(p){return '<span class="badge badge-muted">'+platformName(p)+' · '+platforms[p]+'</span>';}).join('') || 'No devices'; }
  function appleBadge(a) {
    if(!a) return '<span class="badge badge-muted">Unavailable</span>';
    if(a.production.active) return '<span class="badge badge-ok">Apple paid</span>';
    if(a.sandbox.active) return '<span class="badge badge-warn">TestFlight sandbox'+(a.sandbox_allowed?'':' · not enabled')+'</span>';
    return '<span class="badge '+(a.production.status==='refunded'?'badge-revoked':'badge-muted')+'">'+(a.production.status==='none'?'No Apple subscription':esc(a.production.status))+'</span>';
  }
  function usageSummary(u,days) {
    var h='<div class="account-metrics"><div><small>Measured app use</small><strong>'+duration(u.measured_since?u.foreground_ms:null)+'</strong></div><div><small>Playback within app use</small><strong>'+duration(u.measured_since?u.playback_ms:null)+'</strong></div><div><small>Recorded foreground sessions</small><strong>'+(u.measured_since?u.session_count:'—')+'</strong></div></div>';
    h+='<p class="insights-note">'+esc(u.definition)+' Measurement started: '+formatDate(u.measured_since)+'.</p>';
    if(days&&days.length) {var max=Math.max.apply(null,days.map(function(d){return d.foreground_ms;}));h+='<div class="usage-chart" aria-label="Daily measured app usage over the last 30 days">';days.forEach(function(d){h+='<span style="height:'+Math.max(2,Math.round(d.foreground_ms/Math.max(1,max)*48))+'px" title="'+esc(d.day)+' UTC · '+duration(d.foreground_ms)+'"></span>';});h+='</div><p class="insights-note">Daily recorded use · UTC · '+esc(days[0].day)+' – '+esc(days[days.length-1].day)+'</p>';}
    return h;
  }
  function deviceCard(uid,d,apple) {
    var u=d.usage||{}, active=apple?d.active:d.is_active, id=apple?d.device_id:d.id;
    var h='<article class="device-card"><h5>'+esc(d.display_name||u.device_model||platformName(d.platform_family||d.platform))+'</h5><div class="device-meta">'+platformName(d.platform_family||d.platform)+' · <span class="badge '+(active?'badge-ok':'badge-muted')+'">'+esc(apple?d.slot_state:active?'Registered':'Removed')+'</span></div>';
    h+='<div class="device-metrics"><div><small>App use</small><strong>'+duration(u.foreground_ms)+'</strong></div><div><small>Playback</small><strong>'+duration(u.playback_ms)+'</strong></div><div><small>Sessions</small><strong>'+(u.session_count==null?'—':u.session_count)+'</strong></div></div>';
    h+='<p class="device-meta">Last contact: '+formatDate(d.last_seen_at)+'<br>Last measured use: '+formatDate(u.last_activity_at)+'</p><details><summary>Device details</summary><dl>';
    [['Model',u.device_model],['Manufacturer',u.manufacturer],['OS version',u.os_version],['App version',u.app_version||d.app_version],['App build',u.app_build],['Device type',apple?d.platform:d.device_type],['Created',d.created_at?formatDate(d.created_at):null],['Measurement began',u.measured_since?formatDate(u.measured_since):null],['Device ID',id],['Installation ID',d.installation_id],['Stable device ID',d.stable_device_id],['Revoked',d.revoked_at?formatDate(d.revoked_at):null]].forEach(function(pair){h+='<dt>'+pair[0]+'</dt><dd>'+esc(pair[1]||'Not reported')+'</dd>';});
    h+='</dl></details><div class="device-actions">';
    if(!apple) h+='<button class="btn btn-secondary btn-sm" data-action="device-activity" data-uid="'+uid+'" data-id="'+id+'">Event history</button>';
    if(active) h+='<button class="btn btn-danger btn-sm" data-action="'+(apple?'revoke-apple-device':'revoke-device')+'" data-uid="'+uid+'" data-id="'+id+'">'+(apple?'Remove Apple slot':'Revoke device')+'</button>';
    return h+'</div></article>';
  }
  function appleLedger(d) {
    var a=d.user.apple_subscription,h='<div class="sub-section"><h4>Apple subscription ledger</h4><div class="detail-grid">';
    h+=kv('Product',esc(a.product_id));h+=kv('Production',esc(a.production.status)+' · expires '+formatDate(a.production.expires_at));h+=kv('TestFlight',esc(a.sandbox.status)+' · expires '+formatDate(a.sandbox.expires_at));h+=kv('Sandbox testing',a.sandbox_allowed?'Enabled for this account':'Not enabled for this account');h+=kv('Apple verification service',a.service_enabled?'Enabled':'Not enabled');h+='</div>';
    if(!d.apple_transactions.length) return h+'<div class="empty-msg">No verified Apple purchase. Account registration never creates an Apple subscription.</div></div>';
    h+='<table class="sub-table"><thead><tr><th>Environment</th><th>Transaction</th><th>Verified</th><th>Expires</th><th>Refund</th></tr></thead><tbody>';
    d.apple_transactions.forEach(function(t){h+='<tr><td>'+esc(t.environment)+'</td><td><code>'+esc(t.transaction_id)+'</code></td><td>'+formatDate(t.verified_at)+'</td><td>'+formatDate(t.expires_at)+'</td><td>'+formatDate(t.refunded_at)+'</td></tr>';});return h+'</tbody></table></div>';
  }
  function actionBtn(cls, action, uid, label) {
    return '<button class="btn ' + cls + '" data-action="' + action + '" data-uid="' + uid + '">' + esc(label) + '</button>';
  }

  function entStatusBadge(s) {
    if (s === 'active') return '<span class="badge badge-ok">Active</span>';
    if (s === 'expired') return '<span class="badge badge-muted">Expired</span>';
    if (s === 'revoked') return '<span class="badge badge-revoked">Revoked</span>';
    if (s === 'paused') return '<span class="badge badge-warn">Paused</span>';
    if (s === 'grace_period') return '<span class="badge badge-warn">Grace</span>';
    return '<span class="badge badge-muted">' + esc(s) + '</span>';
  }

  // ── Actions ───────────────────────────────────────────────────
  function handleAction(action, uid, subId) {
    if (action === 'revoke-apple-device') {
      confirmDialog('Remove this Apple device?', 'Frees an Apple slot. The subscription continues. A cached offline lease can remain valid for up to 24 hours.', function(){
        runAction('/admin/account-insights/'+uid+'/apple-devices/'+subId+'/revoke','POST',uid,'Apple slot removed.');
      },true);
    } else if (action === 'force-verify') {
      confirmDialog('Force-verify email?', 'This marks the user as verified. They\'ll regain access on all their devices and the verification gate stops nagging.', function () {
        runAction('/admin/users/' + uid + '/force-verify', 'POST', uid, 'Verified.');
      });
    } else if (action === 'lock') {
      confirmDialog('Lock this account?', 'Sets is_active=false. Login will be rejected. Reversible.', function () {
        runAction('/admin/users/' + uid + '/lock', 'POST', uid, 'Locked.');
      }, true);
    } else if (action === 'unlock') {
      runAction('/admin/users/' + uid + '/unlock', 'POST', uid, 'Unlocked.');
    } else if (action === 'grant-lifetime') {
      promptDialog('Grant lifetime access', 'Adds a lifetime entitlement and writes a ledger row. Optional note (visible to admins only):', function (notes) {
        runAction('/admin/users/' + uid + '/grant-lifetime', 'POST', uid, 'Lifetime granted.', { notes: notes || null });
      });
    } else if (action === 'grant-temporary') {
      grantTemporaryDialog(function (payload) {
        runAction('/admin/users/' + uid + '/grant-temporary', 'POST', uid, 'Temporary access granted.', payload);
      });
    } else if (action === 'extend-beta') {
      betaExtendDialog(detailCache[uid] && detailCache[uid].beta_access, function (payload) {
        runAction('/admin/users/' + uid + '/beta/extend', 'POST', uid, 'Beta access updated.', payload);
      });
    } else if (action === 'revoke-beta') {
      promptDialog('Revoke beta access', 'Revokes active discord_beta grants only. Paid subscriptions and lifetime access are not changed. Optional reason:', function (reason) {
        runAction('/admin/users/' + uid + '/beta/revoke', 'POST', uid, 'Beta access revoked.', { reason: reason || 'admin_revoked' });
      }, true);
    } else if (action === 'set-device-cap') {
      var user = detailCache[uid] && detailCache[uid].user;
      deviceCapDialog(user, function (payload) {
        runAction('/admin/users/' + uid + '/device-cap', 'PATCH', uid, 'Device cap updated.', payload);
      });
    } else if (action === 'refresh-flags') {
      runAction('/admin/users/' + uid + '/refresh-flags', 'POST', uid, 'Flags recomputed.');
    } else if (action === 'device-activity') {
      showDeviceActivity(uid, subId);
    } else if (action === 'revoke-device') {
      confirmDialog('Revoke this device?', 'Marks it inactive. The user must re-add the device to get access on it again.', function () {
        runAction('/admin/users/' + uid + '/devices/' + subId + '/revoke', 'POST', uid, 'Device revoked.');
      }, true);
    } else if (action === 'revoke-entitlement') {
      confirmDialog('Revoke this entitlement?', 'Marks status=revoked and recomputes the user\'s premium flags so the badges update immediately.', function () {
        runAction('/admin/users/' + uid + '/entitlements/' + subId + '/revoke', 'POST', uid, 'Entitlement revoked.');
      }, true);
    }
  }

  function runAction(path, method, uid, successMsg, body) {
    var msgEl = document.getElementById('detail-msg-' + uid);
    if (msgEl) { msgEl.className = 'msg'; msgEl.textContent = ''; }
    adminFetch(path, { method: method, body: body ? JSON.stringify(body) : undefined }).then(function (res) {
      if (!res.ok) {
        if (msgEl) { msgEl.className = 'msg msg-error'; msgEl.textContent = 'Failed: ' + (res.data.detail || res.status); }
        return;
      }
      if (msgEl) { msgEl.className = 'msg msg-ok'; msgEl.textContent = successMsg; }
      delete detailCache[uid];
      Object.keys(activityCache).forEach(function (key) { if (key.indexOf(uid + ':') === 0) delete activityCache[key]; });
      // Refresh detail + the row in the list (badges may change).
      fetchDetail(uid, true).then(function (d) {
        renderDetail(uid, d);
        updateRowFromDetail(uid, d);
      });
    });
  }

  function updateRowFromDetail(uid, d) {
    if (!d) return;
    var row = document.querySelector('tr[data-uid="' + uid + '"]');
    if (!row) return;
    var cells = row.querySelectorAll('td');
    if (cells.length >= 4) {
      cells[1].innerHTML = statusBadges(d.user);
      cells[2].innerHTML = formatBetaAccess(d.beta_access || d.user.beta_access, true);
      cells[3].textContent = formatDeviceUsage(d.user);
    }
  }

  // ── Device activity ──────────────────────────────────────────────
  function fetchDeviceActivity(uid, deviceId, force) {
    var key = uid + ':' + deviceId;
    if (!force && activityCache[key]) return Promise.resolve(activityCache[key]);
    return adminFetch('/admin/users/' + uid + '/devices/' + deviceId + '/activity?limit=30').then(function (res) {
      if (!res.ok) throw new Error((res.data && res.data.detail) || 'Failed to load device activity.');
      activityCache[key] = res.data;
      return res.data;
    });
  }

  function showDeviceActivity(uid, deviceId) {
    var key = uid + ':' + deviceId;
    showInfoModal('Device activity', '<div class="empty-msg">Loading...</div>', key);
    fetchDeviceActivity(uid, deviceId).then(function (data) {
      var body = document.getElementById('modalBody');
      if (body.getAttribute('data-activity-key') !== key) return;
      body.innerHTML = renderDeviceActivity(data);
    }).catch(function (err) {
      var body = document.getElementById('modalBody');
      if (body.getAttribute('data-activity-key') === key) {
        body.innerHTML = '<div class="msg msg-error">' + esc(err.message || 'Failed to load device activity.') + '</div>';
      }
    });
  }

  function renderDeviceActivity(data) {
    var dev = data.device || {};
    var h = '<div class="activity-device-title">';
    h += '<strong>' + esc(dev.display_name || dev.device_type || 'Device') + '</strong>';
    h += '<code>' + esc(dev.id ? dev.id.slice(0, 8) + '...' : 'unknown') + '</code>';
    h += '</div>';

    h += '<div class="activity-category-bar">';
    (data.available_categories || []).forEach(function (cat) {
      var count = data.categories && data.categories[cat.id] ? data.categories[cat.id].count : 0;
      h += '<span class="activity-chip">' + esc(cat.label) + ' <b>' + count + '</b></span>';
    });
    h += '</div>';

    (data.available_categories || []).forEach(function (cat) {
      var section = data.categories && data.categories[cat.id];
      if (!section) return;
      h += '<div class="activity-section">';
      h += '<h4>' + esc(section.label || cat.label) + ' (' + esc(section.count || 0) + ')</h4>';
      if (!section.events || !section.events.length) {
        h += '<div class="empty-msg">No stored events.</div>';
      } else {
        section.events.forEach(function (event) { h += renderActivityEvent(event); });
      }
      h += '</div>';
    });
    return h;
  }

  function renderActivityEvent(event) {
    var h = '<div class="activity-event">';
    h += '<div class="activity-time">' + formatDate(event.at) + '</div>';
    h += '<div>';
    h += '<div class="activity-summary">' + esc(event.summary || event.action || 'Activity') + '</div>';
    h += '<div class="activity-action">' + esc(event.action || '') + '</div>';
    h += renderActivityMeta(event.metadata || {});
    h += '</div></div>';
    return h;
  }

  function renderActivityMeta(meta) {
    var keys = Object.keys(meta).filter(function (key) {
      return meta[key] !== null && meta[key] !== undefined && meta[key] !== '';
    });
    if (!keys.length) return '';
    var h = '<div class="activity-meta">';
    keys.forEach(function (key) {
      h += '<span><b>' + esc(humanKey(key)) + '</b> ' + formatActivityValue(key, meta[key]) + '</span>';
    });
    h += '</div>';
    return h;
  }

  function formatActivityValue(key, value) {
    if (Array.isArray(value)) value = value.join(', ');
    if (typeof value === 'boolean') value = value ? 'true' : 'false';
    var text = value == null ? '—' : String(value);
    if (key === 'title' || key === 'display_name' || key === 'device_name') return esc(text);
    return '<code>' + esc(text) + '</code>';
  }

  // ── Modal helpers ─────────────────────────────────────────────
  function confirmDialog(title, body, onConfirm, danger) {
    document.getElementById('modalTitle').textContent = title;
    document.getElementById('modalBody').textContent = body;
    document.getElementById('modalInput').style.display = 'none';
    var btn = document.getElementById('modalConfirm');
    btn.className = 'btn ' + (danger ? 'btn-danger' : 'btn-primary');
    btn.textContent = 'Confirm';
    showModal(onConfirm);
  }

  function promptDialog(title, body, onConfirm, danger) {
    document.getElementById('modalTitle').textContent = title;
    document.getElementById('modalBody').textContent = body;
    var input = document.getElementById('modalInput');
    input.style.display = 'block';
    input.value = '';
    var btn = document.getElementById('modalConfirm');
    btn.className = 'btn ' + (danger ? 'btn-danger' : 'btn-primary');
    btn.textContent = 'Confirm';
    showModal(function () { onConfirm(input.value.trim()); });
  }

  function grantTemporaryDialog(onConfirm) {
    document.getElementById('modalTitle').textContent = 'Grant temporary access';
    var body = document.getElementById('modalBody');
    body.innerHTML =
      '<p style="font-size:13px;color:#ccc;margin:0 0 12px">' +
        'Creates a subscription_monthly entitlement that expires automatically. No auto-renew.' +
      '</p>' +
      '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px" id="presetRow">' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="7">1 week</button>' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="30">1 month</button>' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="90">3 months</button>' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="365">1 year</button>' +
      '</div>' +
      '<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px">' +
        '<label style="font-size:12px;color:#888;display:block">Duration (days)' +
          '<input type="number" id="grantDays" min="1" placeholder="e.g. 14" style="margin-top:4px;width:100%;padding:7px 10px;font-size:13px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:6px;color:#fff;outline:none;box-sizing:border-box" />' +
        '</label>' +
        '<label style="font-size:12px;color:#888;display:block">…or expiry date' +
          '<input type="date" id="grantUntil" style="margin-top:4px;width:100%;padding:6px 10px;font-size:13px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:6px;color:#fff;outline:none;box-sizing:border-box;color-scheme:dark" />' +
        '</label>' +
      '</div>' +
      '<label style="font-size:12px;color:#888;display:block;margin-bottom:6px">Notes (admins only)</label>';
    var input = document.getElementById('modalInput');
    input.style.display = 'block';
    input.value = '';
    input.placeholder = 'e.g. trial for support ticket #1234';

    body.querySelectorAll('[data-days]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        document.getElementById('grantDays').value = btn.getAttribute('data-days');
        document.getElementById('grantUntil').value = '';
      });
    });
    document.getElementById('grantDays').addEventListener('input', function () {
      if (this.value) document.getElementById('grantUntil').value = '';
    });
    document.getElementById('grantUntil').addEventListener('input', function () {
      if (this.value) document.getElementById('grantDays').value = '';
    });

    var btn = document.getElementById('modalConfirm');
    btn.className = 'btn btn-primary';
    btn.textContent = 'Grant';

    showModal(function () {
      var days = document.getElementById('grantDays').value.trim();
      var until = document.getElementById('grantUntil').value;  // YYYY-MM-DD
      var notes = input.value.trim();
      var payload = { notes: notes || null };
      if (days) payload.duration_days = parseInt(days, 10);
      else if (until) payload.expires_at = until + 'T23:59:59Z';
      else { alert('Pick a duration or an expiry date.'); return; }
      onConfirm(payload);
    });
  }

  function deviceCapDialog(user, onConfirm) {
    user = user || {};
    document.getElementById('modalTitle').textContent = 'Set account device cap';
    var body = document.getElementById('modalBody');
    body.innerHTML =
      '<p style="font-size:13px;color:#ccc;margin:0 0 12px">' +
        'Set a manual active-device limit for this account. Leave blank to use the global default.' +
      '</p>' +
      '<label style="font-size:12px;color:#888;display:block">Device cap override' +
        '<input type="number" id="deviceCapInput" min="1" max="100" placeholder="Default: ' + esc(user.device_limit || 'server') + '" ' +
        'value="' + esc(user.device_cap_override == null ? '' : user.device_cap_override) + '" ' +
        'style="margin-top:4px;width:100%;padding:7px 10px;font-size:13px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:6px;color:#fff;outline:none;box-sizing:border-box" />' +
      '</label>';
    var input = document.getElementById('modalInput');
    input.style.display = 'none';
    input.value = '';
    var btn = document.getElementById('modalConfirm');
    btn.className = 'btn btn-primary';
    btn.textContent = 'Save cap';

    showModal(function () {
      var value = document.getElementById('deviceCapInput').value.trim();
      if (!value) {
        onConfirm({ device_cap_override: null });
        return;
      }
      var cap = parseInt(value, 10);
      if (!cap || cap < 1 || cap > 100) {
        alert('Enter a device cap from 1 to 100, or leave it blank for default.');
        return;
      }
      onConfirm({ device_cap_override: cap });
    });
  }

  function betaExtendDialog(betaAccess, onConfirm) {
    betaAccess = betaAccess || {};
    document.getElementById('modalTitle').textContent = betaAccess.active ? 'Extend beta access' : 'Grant beta access';
    var body = document.getElementById('modalBody');
    body.innerHTML =
      '<p style="font-size:13px;color:#ccc;margin:0 0 12px">' +
        'Creates or extends a discord_beta grant only. Paid subscriptions, lifetime access, Paddle, and store records are not changed. Expiry is capped by the beta campaign end.' +
      '</p>' +
      '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px" id="betaPresetRow">' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="7">1 week</button>' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="30">30 days</button>' +
        '<button type="button" class="btn btn-secondary btn-sm" data-days="60">60 days</button>' +
      '</div>' +
      '<div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:10px">' +
        '<label style="font-size:12px;color:#888;display:block">Add duration (days)' +
          '<input type="number" id="betaGrantDays" min="1" max="365" placeholder="Default: 30" style="margin-top:4px;width:100%;padding:7px 10px;font-size:13px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:6px;color:#fff;outline:none;box-sizing:border-box" />' +
        '</label>' +
        '<label style="font-size:12px;color:#888;display:block">…or expiry date' +
          '<input type="date" id="betaGrantUntil" style="margin-top:4px;width:100%;padding:6px 10px;font-size:13px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.1);border-radius:6px;color:#fff;outline:none;box-sizing:border-box;color-scheme:dark" />' +
        '</label>' +
      '</div>' +
      '<label style="font-size:12px;color:#888;display:block;margin-bottom:6px">Reason (admins only)</label>';
    var input = document.getElementById('modalInput');
    input.style.display = 'block';
    input.value = '';
    input.placeholder = betaAccess.active ? 'e.g. extended after staff review' : 'e.g. manual beta invite';

    body.querySelectorAll('[data-days]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        document.getElementById('betaGrantDays').value = btn.getAttribute('data-days');
        document.getElementById('betaGrantUntil').value = '';
      });
    });
    document.getElementById('betaGrantDays').addEventListener('input', function () {
      if (this.value) document.getElementById('betaGrantUntil').value = '';
    });
    document.getElementById('betaGrantUntil').addEventListener('input', function () {
      if (this.value) document.getElementById('betaGrantDays').value = '';
    });

    var btn = document.getElementById('modalConfirm');
    btn.className = 'btn btn-primary';
    btn.textContent = betaAccess.active ? 'Extend beta' : 'Grant beta';

    showModal(function () {
      var days = document.getElementById('betaGrantDays').value.trim();
      var until = document.getElementById('betaGrantUntil').value;
      var reason = input.value.trim();
      var payload = { reason: reason || null };
      if (days) payload.duration_days = parseInt(days, 10);
      else if (until) payload.expires_at = until + 'T23:59:59Z';
      else payload.duration_days = 30;
      onConfirm(payload);
    });
  }

  function showModal(onConfirm) {
    var modal = document.getElementById('modal');
    modal.style.display = 'flex';
    var box = modal.querySelector('.modal-box');
    if (box) box.classList.remove('modal-wide');
    var btn = document.getElementById('modalConfirm');
    var cancel = document.getElementById('modalCancel');
    document.getElementById('modalBody').removeAttribute('data-activity-key');
    btn.style.display = 'inline-flex';
    cancel.textContent = 'Cancel';
    function close() { modal.style.display = 'none'; btn.onclick = null; cancel.onclick = null; }
    btn.onclick = function () { close(); onConfirm(); };
    cancel.onclick = close;
  }

  function showInfoModal(title, html, activityKey) {
    var modal = document.getElementById('modal');
    var box = modal.querySelector('.modal-box');
    var body = document.getElementById('modalBody');
    var input = document.getElementById('modalInput');
    var btn = document.getElementById('modalConfirm');
    var cancel = document.getElementById('modalCancel');
    document.getElementById('modalTitle').textContent = title;
    body.innerHTML = html;
    if (activityKey) body.setAttribute('data-activity-key', activityKey);
    else body.removeAttribute('data-activity-key');
    input.style.display = 'none';
    input.value = '';
    if (box) box.classList.add('modal-wide');
    btn.style.display = 'none';
    btn.onclick = null;
    cancel.textContent = 'Close';
    function close() {
      modal.style.display = 'none';
      cancel.onclick = null;
      body.removeAttribute('data-activity-key');
      if (box) box.classList.remove('modal-wide');
      btn.style.display = 'inline-flex';
      cancel.textContent = 'Cancel';
    }
    cancel.onclick = close;
    modal.style.display = 'flex';
  }

  // ── Helpers ───────────────────────────────────────────────────
  function esc(s) { var d = document.createElement('div'); d.textContent = s == null ? '' : String(s); return d.innerHTML; }
  function formatDate(iso) { if (!iso) return '—'; return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); }
  function betaActionLabel(betaAccess) { return betaAccess && betaAccess.active ? 'Extend beta' : 'Grant beta'; }
  function formatBetaAccess(betaAccess, compact) {
    betaAccess = betaAccess || {};
    if (betaAccess.active) {
      var label = compact ? 'Beta until ' + formatDate(betaAccess.expires_at) : 'Active until ' + formatDate(betaAccess.expires_at);
      return '<span class="badge badge-ok">' + esc(label) + '</span>';
    }
    if (betaAccess.status === 'revoked') return '<span class="badge badge-revoked">Beta revoked</span>';
    if (betaAccess.status === 'expired') return '<span class="badge badge-muted">Beta expired</span>';
    return '<span class="badge badge-muted">No beta</span>';
  }
  function formatDeviceUsage(u) {
    var limit = u && u.device_limit ? u.device_limit : 'server';
    return (u && u.device_count != null ? u.device_count : 0) + ' / ' + limit;
  }
  function humanKey(key) {
    return String(key || '')
      .replace(/_/g, ' ')
      .replace(/\b\w/g, function (c) { return c.toUpperCase(); });
  }
  function kv(k, v) { return '<div class="k">' + esc(k) + '</div><div class="v">' + v + '</div>'; }
  function debounce(fn, ms) { var t; return function () { clearTimeout(t); t = setTimeout(fn, ms); }; }
  if (secret) showPanel();
})();
