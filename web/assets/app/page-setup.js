(function() {
  'use strict';

  var sectionsEl = document.getElementById('setupSections');
  var modal = document.getElementById('modal');
  var modalTitle = document.getElementById('modalTitle');
  var modalSubtitle = document.getElementById('modalSubtitle');
  var modalNote = document.getElementById('modalNote');
  var modalAlert = document.getElementById('modalAlert');
  var modalForm = document.getElementById('modalForm');
  var modalSave = document.getElementById('modalSave');
  var modalCancel = document.getElementById('modalCancel');
  var toast = document.getElementById('toast');

  var _editingType = null;

  function GROUPS() {
    return [
      { id: 'essentials', title: t('setup.essentials'), desc: t('setup.essentialsDesc'), categories: ['streaming'], help: 'what-is-streaming-service' },
      { id: 'personalization', title: t('setup.personalization'), desc: t('setup.personalizationDesc'), categories: ['tracking', 'metadata', 'ai'], help: 'connect-trakt' },
      { id: 'channels', title: t('setup.channels'), desc: t('setup.channelsDesc'), categories: ['channels'], help: 'url-import-vs-service-login' },
      { id: 'sync', title: t('setup.sync'), desc: t('setup.syncDesc'), categories: ['sync'], help: 'what-syncs' }
    ];
  }

  TorveSetup.onUpdate(render);

  var checkReady = setInterval(function() {
    if (document.getElementById('appShell').style.display !== 'none') {
      clearInterval(checkReady);
      TorveSetup.loadAll().then(render);
    }
  }, 100);

  function render() {
    translateStatic();
    updateRail();
    renderGroups();
    initRailNav();
  }

  function translated(key, fallback) {
    var value = t(key);
    return value === key ? fallback : value;
  }

  function integrationLabel(type) {
    var def = TorveSetup.INTEGRATIONS[type];
    return translated('setup.labels.' + type, def ? def.label : type);
  }

  function integrationDescription(type) {
    var def = TorveSetup.INTEGRATIONS[type];
    return translated('setup.desc.' + type, def ? def.description : '');
  }

  function translateStatic() {
    var title = document.querySelector('.setup-page-title');
    var intro = document.querySelector('.setup-page-intro');
    if (title) title.textContent = t('setup.title');
    if (intro) intro.textContent = t('setup.intro');

    // Rail labels
    var progressTitle = document.querySelector('.rail-progress-title');
    if (progressTitle) progressTitle.textContent = t('setup.progress');

    var railGuide = document.querySelector('.portal-rail-help');
    if (railGuide) railGuide.textContent = t('setup.setupGuide');

    // Subnav links (translate based on data-section)
    document.querySelectorAll('.rail-nav a, .setup-subnav-link').forEach(function(a) {
      var section = a.getAttribute('data-section');
      if (section) a.textContent = t('setup.' + section) || a.textContent;
    });

    // Rail: recommended next label, all done text, set up button, setup guide link
    var nextLabel = document.querySelector('.rail-next-label');
    if (nextLabel) nextLabel.textContent = t('setup.recommendedNext');
    var nextBtn = document.getElementById('nextStepBtn');
    if (nextBtn) nextBtn.textContent = t('common.setUp');
    var allDone = document.getElementById('allDoneArea');
    if (allDone && allDone.querySelector('div')) allDone.querySelector('div').textContent = t('setup.allConnected');
    var guideLink = document.querySelector('.rail-help-link');
    if (guideLink) guideLink.textContent = t('setup.setupGuide');
  }

  // ── Rail updates ────────────────────────────────────────────────────

  function updateRail() {
    var p = TorveSetup.getProgress();
    var pct = p.total > 0 ? Math.round((p.connected / p.total) * 100) : 0;
    document.getElementById('progressCount').textContent = p.connected;
    document.getElementById('progressTotal').textContent = p.total;
    document.getElementById('progressFill').style.width = pct + '%';

    var nextArea = document.getElementById('nextStepArea');
    var doneArea = document.getElementById('allDoneArea');
    // Recommend the user's streaming-source outcome first. PANDA_ADDON is the
    // stable backend identity, not the user-facing setup concept.
    var priority = ['PANDA_ADDON', 'OMDB_API_KEY', 'TRAKT_TOKENS'];
    var nextType = null;

    for (var i = 0; i < priority.length; i++) {
      if (TorveSetup.getStatus(priority[i]) !== 'connected') { nextType = priority[i]; break; }
    }

    if (nextType) {
      var def = TorveSetup.INTEGRATIONS[nextType];
      document.getElementById('nextStepTitle').textContent = integrationLabel(nextType);
      document.getElementById('nextStepDesc').textContent = integrationDescription(nextType);
      document.getElementById('nextStepBtn').onclick = function() { openModal(nextType); };
      nextArea.style.display = 'block';
      doneArea.style.display = 'none';
    } else {
      nextArea.style.display = 'none';
      doneArea.style.display = 'block';
    }
  }

  // ── Main rendering ──────────────────────────────────────────────────

  function renderGroups() {
    var html = '';
    GROUPS().forEach(function(group) {
      html += '<div class="setup-group" id="' + group.id + '">';
      html += '<div class="setup-group-header">';
      html += '<h2>' + group.title + '</h2>';
      html += '<span class="setup-group-desc">' + group.desc + '</span>';
      html += '<a href="/app/help.html#article:' + group.help + '">' + t('common.learnMore') + '</a>';
      html += '</div>';

      group.categories.forEach(function(catId) {
        if (catId === 'channels') html += renderChannels();
        else if (catId === 'sync') html += renderSyncAccordion();
        else {
          var types = Object.keys(TorveSetup.INTEGRATIONS).filter(function(t) {
            return TorveSetup.INTEGRATIONS[t].category === catId;
          });
          if (types.length > 0) {
            html += '<div class="setup-cards">';
            types.forEach(function(type) { html += renderCard(type); });
            html += '</div>';
          }
        }
      });
      html += '</div>';
    });
    sectionsEl.innerHTML = html;
    bindCardActions();
    bindSyncAccordion();
    wireChannelForms();
  }

  function renderCard(type) {
    var def = TorveSetup.INTEGRATIONS[type];
    var status = TorveSetup.getStatus(type);
    var label = TorveSetup.getStatusLabel(status);
    var cls = TorveSetup.getStatusClass(status);

    var h = '<div class="setup-card" data-type="' + type + '">';
    h += '<div class="setup-card-header"><h3>' + integrationLabel(type) + '</h3>';
    h += '<span class="status-badge ' + cls + '">' + label + '</span></div>';

    if (type === 'AI_SEARCH_API_KEY' && status === 'connected') {
      var prov = TorveSetup.getAiProviderLabel();
      if (prov) h += '<p>' + t('setup.providerLabel') + ': ' + prov + '</p>';
    } else {
      h += '<p>' + integrationDescription(type) + '</p>';
    }

    h += '<div class="setup-card-actions">';
    if (status === 'connected') {
      h += '<button class="btn-sm btn-edit" data-action="edit" data-type="' + type + '">' + t('common.edit') + '</button>';
      // External-setup integrations (Panda) aren't testable via /me/integrations/*/test.
      if (!def.externalSetup) {
        h += '<button class="btn-sm btn-edit" data-action="test" data-type="' + type + '">' + t('common.test') + '</button>';
      }
      h += '<button class="btn-sm btn-danger" data-action="remove" data-type="' + type + '">' + t('common.disconnect') + '</button>';
    } else {
      h += '<button class="btn-sm btn-setup" data-action="setup" data-type="' + type + '">' + t('common.setUp') + '</button>';
    }
    h += '</div></div>';
    return h;
  }

  function renderChannels() {
    var playlists = TorveSetup.getSavedPlaylists();
    var h = '';
    if (playlists.length > 0) {
      h += '<div class="setup-cards">';
      playlists.forEach(function(p) {
        var epgUrl = p.epg_url || '';
        h += '<div class="setup-card">';
        h += '<div class="setup-card-header"><h3>' + esc(p.name) + '</h3>';
        h += '<span class="status-badge status-connected">' + p.playlist_type.toUpperCase() + '</span></div>';
        h += '<p>' + esc(p.playlist_type === 'xtream' ? ('Server: ' + (p.server || '')) : ('URL: ' + shortUrl(p.url || ''))) + '</p>';
        if (epgUrl) {
          h += '<p>' + esc('EPG: ' + shortUrl(epgUrl)) + '</p>';
        }
        h += '<div class="setup-card-actions">';
        if (epgUrl) {
          h += '<button class="btn-sm btn-edit" data-action="check-epg" data-epg-url="' + escAttr(epgUrl) + '">' + t('setup.checkEpgBtn') + '</button>';
        }
        h += '<button class="btn-sm btn-danger" data-action="remove-playlist" data-playlist-id="' + esc(p.playlist_id) + '" data-playlist-name="' + esc(p.name) + '">' + t('common.remove') + '</button>';
        h += '</div></div>';
      });
      h += '</div>';
    }

    // Add channel source forms
    h += '<div class="info-card" style="margin-top:16px">';
    h += '<h3>' + t('setup.addChannelTitle') + '</h3>';
    h += '<p>' + t('setup.addChannelDesc') + '</p>';

    // Tab selector
    h += '<div style="display:flex;gap:8px;margin:14px 0 12px">';
    h += '<button class="btn-sm btn-edit channel-tab-btn active" data-tab="m3u">' + t('setup.m3uTab') + '</button>';
    h += '<button class="btn-sm btn-edit channel-tab-btn" data-tab="xtream">' + t('setup.xtreamTab') + '</button>';
    h += '</div>';

    // M3U form
    h += '<div id="channelTabM3u" class="channel-tab-content">';
    h += '<div class="form-row"><label>' + t('setup.nameLabel') + '</label><input type="text" id="m3uName" placeholder="' + t('setup.m3uNamePlaceholder') + '" maxlength="255" /></div>';
    h += '<div class="form-row"><label>' + t('setup.m3uUrlLabel') + '</label><input type="text" id="m3uUrl" placeholder="https://example.com/playlist.m3u" maxlength="2000" /></div>';
    h += '<div class="form-row"><label>' + t('setup.epgLabel') + '</label><div class="input-action-row"><input type="text" id="m3uEpg" placeholder="https://example.com/guide.xml" maxlength="2000" /><button class="btn-sm btn-edit" type="button" id="checkM3uEpgBtn">' + t('setup.checkEpgBtn') + '</button></div></div>';
    h += '<div style="margin-top:10px"><button class="btn-sm btn-setup" id="saveM3uBtn">' + t('setup.saveM3uBtn') + '</button></div>';
    h += '<div id="m3uStatus" style="margin-top:8px;font-size:12px"></div>';
    h += '</div>';

    // Xtream form
    h += '<div id="channelTabXtream" class="channel-tab-content" style="display:none">';
    h += '<div class="form-row"><label>' + t('setup.nameLabel') + '</label><input type="text" id="xtreamName" placeholder="' + t('setup.xtreamNamePlaceholder') + '" maxlength="255" /></div>';
    h += '<div class="form-row"><label>' + t('setup.serverUrlLabel') + '</label><input type="text" id="xtreamServer" placeholder="http://provider.example.com" maxlength="2000" /></div>';
    h += '<div class="form-row"><label>' + t('setup.usernameLabel') + '</label><input type="text" id="xtreamUser" placeholder="" maxlength="255" /></div>';
    h += '<div class="form-row"><label>' + t('setup.passwordLabel') + '</label><input type="password" id="xtreamPass" placeholder="" maxlength="255" /></div>';
    h += '<div class="form-row"><label>' + t('setup.epgLabel') + '</label><div class="input-action-row"><input type="text" id="xtreamEpg" placeholder="https://example.com/guide.xml" maxlength="2000" /><button class="btn-sm btn-edit" type="button" id="checkXtreamEpgBtn">' + t('setup.checkEpgBtn') + '</button></div></div>';
    h += '<div style="margin-top:10px"><button class="btn-sm btn-setup" id="saveXtreamBtn">' + t('setup.saveXtreamBtn') + '</button></div>';
    h += '<div id="xtreamStatus" style="margin-top:8px;font-size:12px"></div>';
    h += '</div>';

    h += '<p style="color:#52525b;font-size:12px;margin-top:12px">' + t('setup.channelsDisclaimer') + '</p>';
    h += '</div>';
    return h;
  }

  function wireChannelForms() {
    // Tab switching
    document.querySelectorAll('.channel-tab-btn').forEach(function(btn) {
      btn.addEventListener('click', function() {
        document.querySelectorAll('.channel-tab-btn').forEach(function(b) { b.classList.remove('active'); });
        btn.classList.add('active');
        var tab = btn.getAttribute('data-tab');
        document.getElementById('channelTabM3u').style.display = tab === 'm3u' ? '' : 'none';
        document.getElementById('channelTabXtream').style.display = tab === 'xtream' ? '' : 'none';
      });
    });

    // Save M3U
    var m3uBtn = document.getElementById('saveM3uBtn');
    if (m3uBtn) m3uBtn.addEventListener('click', function() {
      var name = document.getElementById('m3uName').value.trim();
      var url = document.getElementById('m3uUrl').value.trim();
      var epg = document.getElementById('m3uEpg').value.trim();
      var status = document.getElementById('m3uStatus');

      if (!name || !url) {
        status.style.color = '#fca5a5';
        status.textContent = t('setup.fillRequired');
        return;
      }

      var playlistId = 'm3u_' + Date.now();
      m3uBtn.disabled = true;
      status.style.color = 'var(--text-muted)';
      status.textContent = t('setup.saving');

      TorveAPI.put('/me/playlists/' + playlistId, {
        playlist_id: playlistId,
        name: name,
        playlist_type: 'm3u',
        url: url,
        epg_url: epg || null
      }).then(function() {
        status.style.color = '#86efac';
        status.textContent = name + ': ' + t('setup.savedToAccount');
        document.getElementById('m3uName').value = '';
        document.getElementById('m3uUrl').value = '';
        document.getElementById('m3uEpg').value = '';
        m3uBtn.disabled = false;
        TorveSetup.loadAll().then(render);
      }).catch(function(err) {
        status.style.color = '#fca5a5';
        status.textContent = err.message || t('setup.couldNotSave');
        m3uBtn.disabled = false;
      });
    });

    var checkM3uEpgBtn = document.getElementById('checkM3uEpgBtn');
    if (checkM3uEpgBtn) checkM3uEpgBtn.addEventListener('click', function() {
      var epg = document.getElementById('m3uEpg').value.trim();
      var status = document.getElementById('m3uStatus');
      if (!epg) {
        status.style.color = '#fca5a5';
        status.textContent = t('setup.epgUrlRequired');
        return;
      }
      doCheckEpg(epg, checkM3uEpgBtn, status);
    });

    var checkXtreamEpgBtn = document.getElementById('checkXtreamEpgBtn');
    if (checkXtreamEpgBtn) checkXtreamEpgBtn.addEventListener('click', function() {
      var epg = document.getElementById('xtreamEpg').value.trim();
      var status = document.getElementById('xtreamStatus');
      if (!epg) {
        status.style.color = '#fca5a5';
        status.textContent = t('setup.epgUrlRequired');
        return;
      }
      doCheckEpg(epg, checkXtreamEpgBtn, status);
    });

    // Save Xtream
    var xtreamBtn = document.getElementById('saveXtreamBtn');
    if (xtreamBtn) xtreamBtn.addEventListener('click', function() {
      var name = document.getElementById('xtreamName').value.trim();
      var server = document.getElementById('xtreamServer').value.trim();
      var user = document.getElementById('xtreamUser').value.trim();
      var pass = document.getElementById('xtreamPass').value.trim();
      var epg = document.getElementById('xtreamEpg').value.trim();
      var status = document.getElementById('xtreamStatus');

      if (!name || !server || !user || !pass) {
        status.style.color = '#fca5a5';
        status.textContent = t('setup.fillRequired');
        return;
      }

      var playlistId = 'xtream_' + Date.now();
      xtreamBtn.disabled = true;
      status.style.color = 'var(--text-muted)';
      status.textContent = t('setup.saving');

      TorveAPI.put('/me/playlists/' + playlistId, {
        playlist_id: playlistId,
        name: name,
        playlist_type: 'xtream',
        server: server,
        epg_url: epg || null,
        username: user,
        password: pass
      }).then(function() {
        status.style.color = '#86efac';
        status.textContent = name + ': ' + t('setup.savedToAccount');
        document.getElementById('xtreamName').value = '';
        document.getElementById('xtreamServer').value = '';
        document.getElementById('xtreamUser').value = '';
        document.getElementById('xtreamPass').value = '';
        document.getElementById('xtreamEpg').value = '';
        xtreamBtn.disabled = false;
        TorveSetup.loadAll().then(render);
      }).catch(function(err) {
        status.style.color = '#fca5a5';
        status.textContent = err.message || t('setup.couldNotSave');
        xtreamBtn.disabled = false;
      });
    });
  }

  function renderSyncAccordion() {
    var items = [
      { title: t('sync.accountSettings'), sync: 'yes', detail: t('sync.accountSettingsDetail') },
      { title: t('sync.integrationsAccount'), sync: 'yes', detail: t('sync.integrationsAccountDetail') },
      { title: t('sync.integrationsDevice'), sync: 'no', detail: t('sync.integrationsDeviceDetail') },
      { title: t('sync.channelSources'), sync: 'yes', detail: t('sync.channelSourcesDetail') },
      { title: t('sync.watchlistHistory'), sync: 'partial', detail: t('sync.watchlistHistoryDetail') },
      { title: t('sync.favorites'), sync: 'no', detail: t('sync.favoritesDetail') },
      { title: t('sync.epg'), sync: 'no', detail: t('sync.epgDetail') },
      { title: t('sync.decoderAudio'), sync: 'no', detail: t('sync.decoderAudioDetail') }
    ];

    var h = '<div class="info-card" style="margin-bottom:12px">';
    h += '<h3>' + t('setup.tvSyncTitle') + '</h3>';
    h += '<p>' + t('setup.tvSyncExplain') + '</p>';
    h += '</div>';

    h += '<div class="sync-accordion">';
    items.forEach(function(item) {
      var badgeCls = item.sync === 'yes' ? 'sync-yes-badge' : item.sync === 'no' ? 'sync-no-badge' : 'sync-partial-badge';
      var badgeText = item.sync === 'yes' ? t('sync.syncs') : item.sync === 'no' ? t('sync.localOnly') : t('common.partial');
      h += '<div class="sync-accordion-item">';
      h += '<div class="sync-accordion-header"><h4>' + item.title + '</h4><span class="sync-badge ' + badgeCls + '">' + badgeText + '</span></div>';
      h += '<div class="sync-accordion-body">' + item.detail + '</div>';
      h += '</div>';
    });
    h += '</div>';
    return h;
  }

  function bindSyncAccordion() {
    sectionsEl.querySelectorAll('.sync-accordion-header').forEach(function(header) {
      header.addEventListener('click', function() {
        this.parentElement.classList.toggle('open');
      });
    });
  }

  // ── Rail nav ────────────────────────────────────────────────────────

  function initRailNav() {
    var links = document.querySelectorAll('.rail-nav a');
    links.forEach(function(link) {
      link.addEventListener('click', function(e) {
        e.preventDefault();
        var target = document.getElementById(this.getAttribute('data-section'));
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        links.forEach(function(l) { l.classList.remove('active'); });
        this.classList.add('active');
      });
    });

    if ('IntersectionObserver' in window) {
      var observer = new IntersectionObserver(function(entries) {
        entries.forEach(function(entry) {
          if (entry.isIntersecting) {
            var id = entry.target.id;
            links.forEach(function(l) { l.classList.toggle('active', l.getAttribute('data-section') === id); });
          }
        });
      }, { rootMargin: '-100px 0px -60% 0px' });
      GROUPS().forEach(function(g) { var el = document.getElementById(g.id); if (el) observer.observe(el); });
    }
  }

  // ── Card actions ────────────────────────────────────────────────────

  function bindCardActions() {
    sectionsEl.querySelectorAll('[data-action]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var action = this.getAttribute('data-action');
        var type = this.getAttribute('data-type');
        if (action === 'setup' || action === 'edit') openModal(type);
        else if (action === 'test') doTest(type, this);
        else if (action === 'remove') doRemove(type, this);
        else if (action === 'remove-playlist') doRemovePlaylist(this);
        else if (action === 'check-epg') doCheckEpg(this.getAttribute('data-epg-url'), this);
      });
    });
  }

  function doCheckEpg(epgUrl, btn, statusEl) {
    var originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = t('setup.checkingEpg');
    if (statusEl) {
      statusEl.style.color = 'var(--text-muted)';
      statusEl.textContent = t('setup.checkingEpg');
    }
    TorveSetup.validateEpgUrl(epgUrl).then(function(result) {
      var message = result.message || (result.success ? t('setup.epgValid') : t('setup.epgInvalid'));
      if (statusEl) {
        statusEl.style.color = result.success ? '#86efac' : '#fca5a5';
        statusEl.textContent = message;
      } else {
        showToast(result.success ? 'success' : 'error', message);
      }
      btn.disabled = false;
      btn.textContent = originalText;
    }).catch(function(err) {
      var message = err.message || t('setup.epgInvalid');
      if (statusEl) {
        statusEl.style.color = '#fca5a5';
        statusEl.textContent = message;
      } else {
        showToast('error', message);
      }
      btn.disabled = false;
      btn.textContent = originalText;
    });
  }

  function doRemovePlaylist(btn) {
    var id = btn.getAttribute('data-playlist-id');
    var name = btn.getAttribute('data-playlist-name') || 'this source';
    if (!confirm(t('setup.confirmRemoveSource', { name: name }))) return;
    btn.disabled = true; btn.textContent = t('setup.removing');
    TorveSetup.removePlaylist(id).then(function() {
      if (typeof TorveAnalytics !== 'undefined') TorveAnalytics.track('playlist_removed');
      showToast('success', '"' + name + '" removed.');
    }).catch(function(err) { btn.disabled = false; btn.textContent = t('common.remove'); showToast('error', err.message || t('setup.couldNotRemove')); });
  }

  // ── Modal ───────────────────────────────────────────────────────────

  function openModal(type) {
    var def = TorveSetup.INTEGRATIONS[type];
    if (!def) return;
    _editingType = type;
    modalTitle.textContent = integrationLabel(type);
    modalSubtitle.textContent = integrationDescription(type);
    modalAlert.className = 'modal-alert'; modalAlert.style.display = 'none';
    modalNote.style.display = 'none';

    // External-setup integrations (Panda) get a custom UI: step-by-step
    // instructions + a manifest URL paste field, no credential form.
    if (def.externalSetup) {
      var savedPanda = null;
      var addons = (TorveSetup.getSavedAddons && TorveSetup.getSavedAddons()) || [];
      for (var i = 0; i < addons.length; i++) {
        if (addons[i].addon_id === def.addonId) { savedPanda = addons[i]; break; }
      }
      var html = '';
      var pandaLink = '<a href="' + def.externalUrl + '" target="_blank" rel="noopener" style="color:var(--accent)">' + t('setup.openConnectionSetup') + '</a>';
      var manifestExample = '<code style="background:rgba(0,0,0,.3);padding:1px 5px;border-radius:3px;font-size:11px">https://panda.torve.app/u/&lt;token&gt;/manifest.json</code>';
      html += '<div class="form-group" style="margin-bottom:16px">';
      html += '<div style="display:grid;gap:10px">';
      html += '<div style="padding:12px 14px;background:var(--bg-surface,#15151c);border:1px solid var(--border,#27272a);border-radius:8px;font-size:13px;line-height:1.5">';
      html += '<strong>1.</strong> ' + t('setup.pandaStep1', { url: pandaLink });
      html += '</div>';
      html += '<div style="padding:12px 14px;background:var(--bg-surface,#15151c);border:1px solid var(--border,#27272a);border-radius:8px;font-size:13px;line-height:1.5">';
      html += '<strong>2.</strong> ' + t('setup.pandaStep2', { example: manifestExample });
      html += '</div>';
      html += '<div style="padding:12px 14px;background:var(--bg-surface,#15151c);border:1px solid var(--border,#27272a);border-radius:8px;font-size:13px;line-height:1.5">';
      html += '<strong>3.</strong> ' + t('setup.pandaStep3');
      html += '</div></div></div>';
      html += '<div class="form-group">';
      html += '<label for="field_panda_url">' + t('setup.pandaManifestLabel') + '</label>';
      html += '<input type="url" id="field_panda_url" placeholder="https://panda.torve.app/u/..../manifest.json" value="' + (savedPanda ? (savedPanda.manifest_url || '') : '') + '" />';
      html += '</div>';
      if (savedPanda) {
        html += '<p style="font-size:12px;color:var(--text-muted);margin:0">' + t('setup.pandaInstalled') + '</p>';
      }
      modalForm.innerHTML = html;
      modalSave.disabled = false;
      modalSave.textContent = savedPanda ? t('setup.replace') : t('setup.install');
      modal.classList.add('visible');
      return;
    }

    var html = '';
    if (def.hasProviderSelection && def.providers) {
      var cur = TorveSetup.getAiProvider() || '';
      html += '<div class="form-group"><label for="field_provider">' + t('setup.providerLabel') + '</label>';
      html += '<select id="field_provider" style="width:100%;padding:12px 14px;font-size:15px;font-family:inherit;color:var(--text);background:var(--bg-input);border:1px solid var(--border);border-radius:8px;outline:none">';
      html += '<option value="">' + t('setup.selectProvider') + '</option>';
      def.providers.forEach(function(p) { html += '<option value="' + p.id + '"' + (p.id === cur ? ' selected' : '') + '>' + p.label + '</option>'; });
      html += '</select></div>';
    }
    def.fields.forEach(function(f) {
      html += '<div class="form-group"><label for="field_' + f.key + '">' + f.label + (f.required ? '' : ' (optional)') + '</label>';
      html += '<input type="' + (f.type || 'text') + '" id="field_' + f.key + '" placeholder="' + (f.placeholder || '') + '"' + (f.required ? ' required' : '') + (f.type === 'password' ? ' autocomplete="off"' : '') + ' /></div>';
    });
    modalForm.innerHTML = html;

    if (def.hasProviderSelection && def.providers) {
      var ps = document.getElementById('field_provider'), ki = document.getElementById('field_api_key');
      if (ps && ki) { ps.addEventListener('change', function() { var s = this.value; for (var i = 0; i < def.providers.length; i++) { if (def.providers[i].id === s) { ki.placeholder = def.providers[i].keyPlaceholder || 'Your API key'; break; } } }); ps.dispatchEvent(new Event('change')); }
    }
    modalSave.disabled = false; modalSave.textContent = t('common.save');
    modal.classList.add('visible');
  }

  function closeModal() { modal.classList.remove('visible'); _editingType = null; modalForm.querySelectorAll('input[type="password"]').forEach(function(el) { el.value = ''; }); modalForm.innerHTML = ''; }
  modalCancel.addEventListener('click', closeModal);
  modal.addEventListener('click', function(e) { if (e.target === modal) closeModal(); });

  modalSave.addEventListener('click', function() {
    if (!_editingType) return;
    var def = TorveSetup.INTEGRATIONS[_editingType];

    // External-setup flow: install the pasted manifest URL as an addon.
    if (def.externalSetup) {
      var urlEl = document.getElementById('field_panda_url');
      var url = urlEl ? urlEl.value.trim() : '';
      if (!url || !/^https?:\/\//i.test(url)) {
        showModalAlert('error', t('setup.pandaPasteUrl'));
        return;
      }
      modalSave.disabled = true; modalSave.textContent = t('setup.saving');
      hideModalAlert();
      TorveAPI.post('/me/addons', {
        manifest_url: url,
        installed_from: 'web_setup'
      }).then(function() {
        return TorveSetup.loadAll();
      }).then(function() {
        var savedLabel = integrationLabel(_editingType);
        closeModal();
        SyncUI.showSaveNotice(savedLabel);
      }).catch(function(err) {
        showModalAlert('error', err.message || t('setup.pandaInstallError'));
        modalSave.disabled = false;
        modalSave.textContent = t('setup.install');
      });
      return;
    }

    var creds = {}, valid = true, selProv = null;
    if (def.hasProviderSelection) { var pe = document.getElementById('field_provider'); if (pe) { selProv = pe.value; if (!selProv) { showModalAlert('error', t('setup.selectProvider')); return; } } }
    def.fields.forEach(function(f) { var v = document.getElementById('field_' + f.key).value.trim(); if (f.required && !v) valid = false; if (v) creds[f.key] = v; });
    if (!valid) { showModalAlert('error', t('setup.fillRequired')); return; }
    modalSave.disabled = true; modalSave.textContent = t('setup.saving'); hideModalAlert();
    var chain;
    if (selProv) { var pl = selProv; for (var i = 0; i < (def.providers || []).length; i++) { if (def.providers[i].id === selProv) { pl = def.providers[i].label; break; } } chain = TorveSetup.saveAiProvider(selProv).then(function() { return TorveSetup.saveIntegration(_editingType, creds, pl); }); }
    else { chain = TorveSetup.saveIntegration(_editingType, creds, integrationLabel(_editingType)); }
    chain.then(function() { if (typeof TorveAnalytics !== 'undefined') TorveAnalytics.trackIntegrationSave(_editingType); var savedLabel = integrationLabel(_editingType); modalForm.querySelectorAll('input[type="password"]').forEach(function(el) { el.value = ''; }); closeModal(); SyncUI.showSaveNotice(savedLabel); })
    .catch(function(err) { showModalAlert('error', err.message || t('setup.couldNotSave')); modalSave.disabled = false; modalSave.textContent = t('common.save'); });
  });

  function showModalAlert(type, msg) { modalAlert.className = 'modal-alert ' + type + ' visible'; modalAlert.textContent = msg; }
  function hideModalAlert() { modalAlert.className = 'modal-alert'; modalAlert.style.display = 'none'; }

  function doTest(type, btn) { btn.disabled = true; btn.textContent = t('setup.testing'); TorveSetup.testIntegration(type).then(function(r) { btn.disabled = false; btn.textContent = t('common.test'); showToast(r.success ? 'success' : 'error', r.message); }).catch(function(err) { btn.disabled = false; btn.textContent = t('common.test'); showToast('error', err.message || t('setup.testFailed')); }); }

  function doRemove(type, btn) {
    var def = TorveSetup.INTEGRATIONS[type];
    var label = integrationLabel(type);
    if (!confirm(t('setup.confirmDisconnect', { name: label }))) return;
    btn.disabled = true;

    // External-setup addons (Panda) are uninstalled via /me/addons/<id>,
    // not /me/integrations/*.
    if (def.externalSetup && def.addonId) {
      var addon = TorveSetup.findAddonByManifestId(def.addonId);
      if (!addon) { btn.disabled = false; return; }
      TorveAPI.del('/me/addons/' + addon.id)
        .then(function() { return TorveSetup.loadAll(); })
        .then(function() { showToast('success', t('setup.disconnected', { name: label })); })
        .catch(function(err) { btn.disabled = false; showToast('error', err.message || t('setup.couldNotDisconnect')); });
      return;
    }

    var chain = TorveSetup.removeIntegration(type);
    if (type === 'AI_SEARCH_API_KEY') chain = chain.then(function() { return TorveSetup.clearAiProvider(); });
    chain.then(function() { showToast('success', t('setup.disconnected', { name: label })); }).catch(function(err) { btn.disabled = false; showToast('error', err.message || t('setup.couldNotDisconnect')); });
  }

  function showToast(type, msg) { toast.className = 'toast ' + type + ' visible'; toast.textContent = msg; setTimeout(function() { toast.classList.remove('visible'); }, 3500); }
  function esc(s) { var d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
  function escAttr(s) { return esc(s).replace(/"/g, '&quot;'); }
  function shortUrl(s) { s = s || ''; return s.length > 72 ? s.substring(0, 69) + '...' : s; }
})();
