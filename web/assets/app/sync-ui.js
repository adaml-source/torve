/**
 * Torve Sync UI - reusable components for sync messaging.
 * Uses i18n t() for translations where available.
 */
var SyncUI = (function() {
  'use strict';

  var _noticeTimer = null;

  function _t(key, params) {
    return typeof t === 'function' ? t(key, params) : key;
  }

  function showSaveNotice(label) {
    var el = document.getElementById('saveNotice');
    if (!el) {
      el = document.createElement('div');
      el.id = 'saveNotice';
      el.className = 'save-notice';
      el.innerHTML =
        '<div class="save-notice-title">' +
          '<svg viewBox="0 0 24 24" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>' +
          '<span id="saveNoticeLabel"></span>' +
        '</div>' +
        '<div class="save-notice-body"></div>' +
        '<div class="save-notice-tip"></div>';
      document.body.appendChild(el);
    }
    document.getElementById('saveNoticeLabel').textContent = _t('setup.saved', { name: label });
    el.querySelector('.save-notice-body').textContent = _t('setup.savedToAccount');
    el.querySelector('.save-notice-tip').textContent = _t('setup.savedTvTip');
    el.classList.add('visible');
    if (_noticeTimer) clearTimeout(_noticeTimer);
    _noticeTimer = setTimeout(function() { el.classList.remove('visible'); }, 5000);
  }

  function renderSyncMatrix() {
    var items = [
      { name: _t('sync.accountSettings'), sync: 'yes', note: _t('sync.accountSettingsDetail') },
      { name: _t('sync.integrationsAccount'), sync: 'yes', note: _t('sync.integrationsAccountDetail') },
      { name: _t('sync.integrationsDevice'), sync: 'no', note: _t('sync.integrationsDeviceDetail') },
      { name: _t('sync.channelSources'), sync: 'yes', note: _t('sync.channelSourcesDetail') },
      { name: _t('sync.watchlistHistory'), sync: 'partial', note: _t('sync.watchlistHistoryDetail') },
      { name: _t('sync.favorites'), sync: 'no', note: _t('sync.favoritesDetail') },
      { name: _t('sync.epg'), sync: 'no', note: _t('sync.epgDetail') },
      { name: _t('sync.decoderAudio'), sync: 'no', note: _t('sync.decoderAudioDetail') }
    ];

    var h = '<table class="sync-matrix"><thead><tr><th>' + _t('sync.whatSyncs').split(' ')[0] + '</th><th>' + _t('sync.syncs') + '</th><th>Details</th></tr></thead><tbody>';
    items.forEach(function(r) {
      var cls = r.sync === 'yes' ? 'sync-yes' : r.sync === 'no' ? 'sync-no' : 'sync-partial';
      var label = r.sync === 'yes' ? _t('common.yes') : r.sync === 'no' ? _t('common.no') : _t('common.partial');
      h += '<tr><td class="data-name">' + r.name + '</td><td class="' + cls + '">' + label + '</td><td>' + r.note + '</td></tr>';
    });
    h += '</tbody></table>';
    return h;
  }

  function renderTvHelper() {
    return '<div class="tv-helper">' +
      '<div class="tv-helper-header">' +
        '<div class="tv-helper-icon"><svg viewBox="0 0 24 24" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="15" rx="2" ry="2"/><polyline points="17 2 12 7 7 2"/></svg></div>' +
        '<h3>' + _t('setup.tvSyncTitle') + '</h3>' +
      '</div>' +
      '<p>' + _t('setup.tvSyncExplain') + '</p>' +
    '</div>';
  }

  function renderSyncInfo() {
    return '<div class="sync-info-inline">' +
      '<h4>' + _t('sync.syncs') + '</h4>' +
      '<p>' + _t('setup.savedToAccount') + '</p>' +
      '<p>' + _t('setup.savedTvTip') + '</p>' +
    '</div>';
  }

  return {
    showSaveNotice: showSaveNotice,
    renderSyncMatrix: renderSyncMatrix,
    renderTvHelper: renderTvHelper,
    renderSyncInfo: renderSyncInfo
  };
})();
