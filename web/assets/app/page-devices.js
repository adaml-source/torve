// Torve Devices page - fully localized

// Render sync content
(function() {
  var check = setInterval(function() {
    var el = document.getElementById('syncContent');
    if (el && document.getElementById('appShell').style.display !== 'none') {
      clearInterval(check);
      el.innerHTML = SyncUI.renderTvHelper() + SyncUI.renderSyncMatrix() + SyncUI.renderSyncInfo();
    }
  }, 100);
})();

(function() {
  'use strict';
  var _deviceLimit = null;
  var _devices = [], _pairings = [], _accessState = null;
  var _pendingRemoveId = null, _pendingRenameId = null;

  var deviceListEl = document.getElementById('deviceList');
  var pairingListEl = document.getElementById('pairingList');
  var countText = document.getElementById('countText');
  var countFill = document.getElementById('countFill');
  var limitWarning = document.getElementById('limitWarning');
  var removeModal = document.getElementById('removeModal');
  var renameModal = document.getElementById('renameModal');
  var toast = document.getElementById('toast');

  var check = setInterval(function() {
    if (document.getElementById('appShell').style.display !== 'none') {
      clearInterval(check);
      translateStatic();
      loadAll();
    }
  }, 100);

  function translateStatic() {
    // Page title and intro
    var title = document.querySelector('.portal-page-title');
    var intro = document.querySelector('.portal-page-intro');
    if (title) title.textContent = t('devices.title');
    if (intro) intro.textContent = t('devices.intro');

    // Section headers
    var sections = document.querySelectorAll('.portal-section-header h2');
    var descs = document.querySelectorAll('.portal-section-header .portal-section-desc');
    if (sections[0]) sections[0].textContent = t('devices.activatedDevices');
    if (sections[1]) sections[1].textContent = t('devices.pairings');
    if (sections[2]) sections[2].textContent = t('devices.syncTitle');
    if (descs[0]) descs[0].textContent = deviceLimitText('devices.activatedDesc', 'Devices connected to your Torve account.');
    if (descs[1]) descs[1].textContent = t('devices.pairingsDesc');
    if (descs[2]) descs[2].textContent = t('devices.syncDesc');

    // "Learn more" links in section headers
    var learnLinks = document.querySelectorAll('.portal-section-header a');
    learnLinks.forEach(function(a) { a.textContent = t('common.learnMore'); });

    // Rail
    var railTitle = document.querySelector('.portal-rail-card h3');
    if (railTitle) railTitle.textContent = t('devices.deviceLimit');
    var railLabel = document.querySelector('.portal-rail-stat-label');
    if (railLabel) railLabel.textContent = t('devices.activeDevices');
    var railHelp = document.querySelector('.portal-rail-help');
    if (railHelp) railHelp.textContent = t('devices.devicesGuide');

    // Rail nav items
    var railNavLinks = document.querySelectorAll('.portal-rail-nav a');
    var railNavKeys = ['devices.activatedDevices', 'devices.pairings', 'devices.syncTitle'];
    railNavLinks.forEach(function(a, i) {
      if (railNavKeys[i]) a.textContent = t(railNavKeys[i]);
    });

    // Modal buttons
    var removeCancelBtn = document.getElementById('removeCancelBtn');
    var renameTitle = renameModal ? renameModal.querySelector('h2') : null;
    var renameCancelBtn = document.getElementById('renameCancelBtn');
    var renameConfirmBtn = document.getElementById('renameConfirmBtn');
    var renameLabel = renameModal ? renameModal.querySelector('label') : null;
    if (removeCancelBtn) removeCancelBtn.textContent = t('common.cancel');
    var removeConfirmBtn = document.getElementById('removeConfirmBtn');
    if (removeConfirmBtn) removeConfirmBtn.textContent = t('devices.removeDevice');
    if (renameTitle) renameTitle.textContent = t('devices.renameDevice');
    if (renameCancelBtn) renameCancelBtn.textContent = t('common.cancel');
    if (renameConfirmBtn) renameConfirmBtn.textContent = t('common.save');
    if (renameLabel) renameLabel.textContent = t('devices.displayName');

    updateDeviceLimitCopy();
  }

  function loadAll() {
    Promise.all([
      TorveAPI.get('/me/devices?include_revoked=false').catch(function() { return null; }),
      TorveAPI.get('/me/pairings').catch(function() { return null; }),
      TorveAPI.get('/me/access-state').catch(function() { return null; })
    ]).then(function(r) {
      if (r[0] === null) { showDeviceError(); return; }
      _devices = r[0] || [];
      _pairings = r[1] || [];
      _accessState = r[2] || null;
      _deviceLimit = resolveDeviceLimit(_accessState);
      render();
    });
  }

  function render() {
    var active = _devices.filter(function(d) { return d.is_active; });
    var count = active.length;
    var pct = _deviceLimit ? Math.min(100, Math.round((count / _deviceLimit) * 100)) : 0;

    updateDeviceLimitCopy();
    countText.textContent = _deviceLimit ? count + ' / ' + _deviceLimit : String(count);
    countFill.style.width = pct + '%';
    countFill.className = 'device-count-fill ' + (_deviceLimit && count >= _deviceLimit ? 'full' : 'ok');
    limitWarning.classList.toggle('visible', !!(_deviceLimit && count >= _deviceLimit));

    renderDevices(active);
    renderPairings();
  }

  function resolveDeviceLimit(accessState) {
    if (accessState && accessState.device_limit_unlimited === true) return null;
    var limit = accessState && parseInt(accessState.device_limit, 10);
    return limit && limit > 0 ? limit : null;
  }

  function updateDeviceLimitCopy() {
    var railTitle = document.querySelector('.portal-rail-card h3');
    if (railTitle) railTitle.textContent = t(_deviceLimit ? 'devices.deviceLimit' : 'devices.activeDevices');
    if (countFill && countFill.parentElement) countFill.parentElement.hidden = !_deviceLimit;
    var desc = document.querySelectorAll('.portal-section-header .portal-section-desc')[0];
    if (desc) desc.textContent = deviceLimitText('devices.activatedDesc', 'Devices connected to your Torve account.');
    if (limitWarning) limitWarning.textContent = deviceLimitText('devices.limitReached', 'You have reached your active-device limit. Remove one below to free a slot.');
  }

  function deviceLimitText(key, fallback) {
    return _deviceLimit ? t(key, { limit: _deviceLimit }) : fallback;
  }

  function renderDevices(active) {
    if (active.length === 0) {
      deviceListEl.innerHTML = '<div class="portal-empty"><p>' + t('devices.noDevices') + '</p></div>';
      return;
    }

    var html = '<div class="device-list">';
    active.forEach(function(d) {
      var icon = d.device_type === 'tv' ?
        '<svg viewBox="0 0 24 24"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"/><polyline points="17 2 12 7 7 2"/></svg>' :
        d.device_type === 'tablet' ?
        '<svg viewBox="0 0 24 24"><rect x="4" y="2" width="16" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>' :
        '<svg viewBox="0 0 24 24"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>';

      var typeLbl = t('devices.' + d.device_type) || d.device_type;
      var platform = d.platform || '';
      if (platform === 'android_tv') platform = 'Android TV';
      else if (platform === 'firetv' || platform === 'fire_tv') platform = 'Fire TV';
      else if (platform === 'android') platform = 'Android';
      else if (platform === 'ios') platform = 'iOS';

      var lastSeen = t('devices.lastSeen', { time: formatTime(d.last_seen_at) });

      html += '<div class="device-row">';
      html += '<div class="device-icon">' + icon + '</div>';
      html += '<div class="device-info">';
      html += '<div class="device-name">' + esc(d.display_name || d.device_type);
      html += ' <span class="tag tag-active">' + typeLbl + '</span></div>';
      html += '<div class="device-meta">';
      if (platform) html += '<span>' + platform + '</span>';
      if (d.app_version) html += '<span>v' + esc(d.app_version) + '</span>';
      html += '<span>' + lastSeen + '</span>';
      html += '</div></div>';
      html += '<div class="device-actions">';
      html += '<button class="btn-icon" title="' + t('devices.renameDevice') + '" data-action="rename" data-id="' + d.id + '" data-name="' + esc(d.display_name || '') + '">';
      html += '<svg viewBox="0 0 24 24"><path d="M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg></button>';
      html += '<button class="btn-icon danger" title="' + t('common.remove') + '" data-action="remove" data-id="' + d.id + '" data-name="' + esc(d.display_name || d.device_type) + '">';
      html += '<svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg></button>';
      html += '</div></div>';
    });
    html += '</div>';
    deviceListEl.innerHTML = html;
    bindDeviceActions();
  }

  function renderPairings() {
    if (_pairings.length === 0) {
      pairingListEl.innerHTML = '<div class="portal-empty"><p>' + t('devices.noPairings') + '</p></div>';
      return;
    }

    var deviceMap = {};
    _devices.forEach(function(d) { deviceMap[d.id] = d; });

    var html = '<div class="device-list">';
    _pairings.forEach(function(p) {
      var ctrl = deviceMap[p.controller_device_id];
      var tgt = deviceMap[p.target_device_id];
      var ctrlName = ctrl ? (ctrl.display_name || t('devices.phone')) : t('devices.phone');
      var tgtName = tgt ? (tgt.display_name || t('devices.tv')) : t('devices.tv');
      var created = formatTime(p.created_at);

      html += '<div class="pairing-row">';
      html += '<div class="device-icon"><svg viewBox="0 0 24 24"><path d="M8 6h13"/><path d="M8 12h13"/><path d="M8 18h13"/><path d="M3 6h.01"/><path d="M3 12h.01"/><path d="M3 18h.01"/></svg></div>';
      html += '<div class="pairing-info">';
      html += '<div class="pair-label">' + esc(ctrlName) + ' &#8594; ' + esc(tgtName) + '</div>';
      html += '<div class="pair-meta">' + t('devices.paired', { time: created }) + '</div>';
      html += '</div>';
      html += '<button class="btn-icon danger" title="' + t('common.remove') + '" data-action="unpair" data-id="' + p.id + '"><svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>';
      html += '</div>';
    });
    html += '</div>';
    pairingListEl.innerHTML = html;
    bindPairingActions();
  }

  function showDeviceError() {
    deviceListEl.innerHTML = '<div class="portal-empty"><p>' + t('devices.couldNotLoad') + '</p><button class="btn-sm btn-edit" data-action="reload">' + t('common.retry') + '</button></div>';
  }

  function bindDeviceActions() {
    deviceListEl.querySelectorAll('[data-action]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var action = this.getAttribute('data-action');
        var id = this.getAttribute('data-id');
        var name = this.getAttribute('data-name');
        if (action === 'remove') showRemoveModal(id, name);
        else if (action === 'rename') showRenameModal(id, name);
      });
    });
  }

  function bindPairingActions() {
    pairingListEl.querySelectorAll('[data-action="unpair"]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var id = this.getAttribute('data-id');
        if (!confirm(t('devices.pairingRemoved').replace('.', '?'))) return;
        btn.disabled = true;
        TorveAPI.post('/me/pairings/' + id + '/revoke').then(function() {
          showToast('success', t('devices.pairingRemoved'));
          loadAll();
        }).catch(function(err) {
          showToast('error', err.message || t('devices.couldNotLoad'));
          btn.disabled = false;
        });
      });
    });
  }

  function showRemoveModal(id, name) {
    _pendingRemoveId = id;
    document.getElementById('removeTitle').textContent = t('devices.removeTitle', { name: name || t('devices.phone') });
    document.getElementById('removeDesc').textContent = t('devices.removeExplain', { name: name || t('devices.phone') });
    var confirmBtn = document.getElementById('removeConfirmBtn');
    confirmBtn.disabled = false;
    confirmBtn.textContent = t('devices.removeDevice');
    removeModal.classList.add('visible');
  }

  document.getElementById('removeCancelBtn').addEventListener('click', function() {
    removeModal.classList.remove('visible'); _pendingRemoveId = null;
  });
  removeModal.addEventListener('click', function(e) {
    if (e.target === removeModal) { removeModal.classList.remove('visible'); _pendingRemoveId = null; }
  });

  document.getElementById('removeConfirmBtn').addEventListener('click', function() {
    if (!_pendingRemoveId) return;
    var btn = this;
    btn.disabled = true;
    btn.textContent = t('setup.removing');
    TorveAPI.post('/me/devices/' + _pendingRemoveId + '/revoke').then(function() {
      removeModal.classList.remove('visible');
      _pendingRemoveId = null;
      btn.textContent = t('devices.removeDevice');
      showToast('success', t('devices.deviceRemoved'));
      loadAll();
    }).catch(function(err) {
      btn.disabled = false;
      btn.textContent = t('devices.removeDevice');
      showToast('error', err.message || t('devices.couldNotLoad'));
    });
  });

  function showRenameModal(id, currentName) {
    _pendingRenameId = id;
    document.getElementById('renameInput').value = currentName || '';
    document.getElementById('renameConfirmBtn').disabled = false;
    renameModal.classList.add('visible');
    document.getElementById('renameInput').focus();
  }

  document.getElementById('renameCancelBtn').addEventListener('click', function() {
    renameModal.classList.remove('visible'); _pendingRenameId = null;
  });
  renameModal.addEventListener('click', function(e) {
    if (e.target === renameModal) { renameModal.classList.remove('visible'); _pendingRenameId = null; }
  });

  document.getElementById('renameConfirmBtn').addEventListener('click', function() {
    if (!_pendingRenameId) return;
    var name = document.getElementById('renameInput').value.trim();
    if (!name) return;
    var btn = this;
    btn.disabled = true;
    TorveAPI.post('/me/devices/' + _pendingRenameId + '/rename', { display_name: name }).then(function() {
      renameModal.classList.remove('visible');
      _pendingRenameId = null;
      btn.disabled = false;
      showToast('success', t('devices.deviceRenamed'));
      loadAll();
    }).catch(function(err) {
      btn.disabled = false;
      showToast('error', err.message || t('devices.couldNotLoad'));
    });
  });

  function formatTime(iso) {
    if (!iso) return '?';
    var d = new Date(iso);
    var now = new Date();
    var diff = now - d;
    if (diff < 60000) return t('devices.justNow');
    if (diff < 3600000) return Math.floor(diff / 60000) + 'm';
    if (diff < 86400000) return Math.floor(diff / 3600000) + 'h';
    if (diff < 604800000) return Math.floor(diff / 86400000) + 'd';
    return d.toLocaleDateString();
  }

  function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
  function showToast(type, msg) { toast.className = 'toast ' + type + ' visible'; toast.textContent = msg; setTimeout(function() { toast.classList.remove('visible'); }, 3500); }
})();

document.addEventListener('click', function(e) {
  if (e.target.getAttribute('data-action') === 'reload') location.reload();
});
