(function() {
  'use strict';

  var t = typeof TorveI18n !== 'undefined' ? TorveI18n.t : function(k) { return k; };
  var addons = [];

  function load() {
    TorveAPI.get('/me/addons').then(function(data) {
      addons = data || [];
      render();
    }).catch(function() {
      document.getElementById('extList').innerHTML =
        '<div class="ext-error">' + t('extensions.loadError') + '</div>';
    });
  }

  function render() {
    var list = document.getElementById('extList');
    var countEl = document.getElementById('extCount');
    var railCount = document.getElementById('railCount');
    var railStreamCount = document.getElementById('railStreamCount');

    var enabledStreams = addons.filter(function(a) { return a.has_streams && a.is_enabled; });
    var streamCapable = addons.filter(function(a) { return a.has_streams; });

    if (railCount) railCount.textContent = addons.length;
    if (railStreamCount) railStreamCount.textContent = streamCapable.length;

    var countStr = t('extensions.count') || '{0} extension(s) installed, {1} stream-capable and enabled';
    countStr = countStr.replace('{0}', addons.length).replace('{1}', enabledStreams.length);
    countEl.textContent = countStr;

    if (addons.length === 0) {
      list.innerHTML =
        '<div class="ext-empty">' +
          '<p>' + t('extensions.empty') + '</p>' +
          '<p>' + t('extensions.emptyHint') + '</p>' +
        '</div>';
      return;
    }

    var html = '';
    addons.sort(function(a, b) { return a.sort_order - b.sort_order; });

    for (var i = 0; i < addons.length; i++) {
      var a = addons[i];
      var iconClass = a.has_streams && a.has_catalog ? 'both' : (a.has_streams ? 'stream' : 'catalog');
      var iconSymbol = a.has_streams ? '&#9654;' : '&#9733;';
      var rowClass = a.is_enabled ? '' : ' disabled';

      html += '<div class="ext-row' + rowClass + '" data-id="' + a.id + '">';
      if (a.logo_url) {
        // Fallback to symbol icon if the image fails to load
        html += '<div class="ext-icon ' + iconClass + ' has-logo">' +
                  '<img src="' + esc(a.logo_url) + '" alt="" ' +
                  'onerror="this.parentNode.classList.remove(\'has-logo\');this.parentNode.innerHTML=\'' + iconSymbol + '\'">' +
                '</div>';
      } else {
        html += '<div class="ext-icon ' + iconClass + '">' + iconSymbol + '</div>';
      }
      html += '<div class="ext-info">';
      html += '<div class="ext-name">' + esc(a.name || a.addon_id || 'Unknown') + '</div>';
      html += '<div class="ext-meta">';
      // Every account add-on was imported from a URL. installed_from says
      // app/web/sync; neither it nor remote metadata establishes built-in origin.
      html += '<span class="ext-badge origin-badge">External</span>';

      if (a.has_catalog) html += '<span class="ext-badge catalog-badge">' + t('extensions.catalog') + '</span>';
      if (a.has_streams) html += '<span class="ext-badge stream-badge">' + t('extensions.stream') + '</span>';
      if (!a.is_enabled) html += '<span class="ext-badge disabled-badge">' + t('extensions.disabled') + '</span>';
      if (a.version) html += '<span>v' + esc(a.version) + '</span>';
      html += '<span>' + esc(a.installed_from) + '</span>';

      html += '</div></div>';
      html += '<div class="ext-actions">';
      if (a.manifest_url) {
        html += '<button class="ext-btn" data-action="details" data-id="' + a.id + '">Details &amp; configuration</button>';
      }
      html += '<button class="ext-btn" data-action="toggle" data-id="' + a.id + '">' + (a.is_enabled ? t('extensions.disable') : t('extensions.enable')) + '</button>';
      html += '<button class="ext-btn danger" data-action="remove" data-id="' + a.id + '" data-name="' + esc(a.name || '') + '">' + t('extensions.remove') + '</button>';
      html += '</div>';
      html += '</div>';
    }

    list.innerHTML = html;

    // Attach event listeners (no inline handlers)
    list.querySelectorAll('[data-action="toggle"]').forEach(function(btn) {
      btn.addEventListener('click', function() { toggleAddon(btn.getAttribute('data-id')); });
    });
    list.querySelectorAll('[data-action="remove"]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        removeAddon(btn.getAttribute('data-id'), btn.getAttribute('data-name'));
      });
    });
    list.querySelectorAll('[data-action="details"]').forEach(function(btn) {
      btn.addEventListener('click', function() {
        var addon = addons.find(function(a) { return String(a.id) === btn.getAttribute('data-id'); });
        if (addon) showDetails(addon);
      });
    });
  }

  function esc(s) {
    if (!s) return '';
    var d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function extensionURL(text, base) {
    text = text.trim().replace(/^stremio:\/\//i, 'https://');
    var url = new URL(text, base);
    var local = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
    var parts = url.hostname.split('.').map(Number);
    if (parts.length === 4 && parts.every(function(v) { return Number.isInteger(v) && v >= 0 && v <= 255; })) {
      local = local || parts[0] === 10 || (parts[0] === 192 && parts[1] === 168) || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31);
    }
    if (!url.hostname || url.username || url.password || url.hash ||
        (url.protocol !== 'https:' && !(url.protocol === 'http:' && local))) {
      throw new Error('Use an HTTPS URL, or HTTP for a server on your local network.');
    }
    return url;
  }

  function showDetails(addon) {
    var dialog = document.getElementById('extensionDetails');
    var status = document.getElementById('extensionDetailsStatus');
    var configure = document.getElementById('extensionConfigureLink');
    document.getElementById('extensionDetailsTitle').textContent = addon.name || 'Extension details';
    document.getElementById('extensionDetailsProvider').textContent = '';
    document.getElementById('extensionDetailsDescription').textContent = addon.description || '';
    configure.hidden = true;
    configure.removeAttribute('href');
    status.textContent = 'Loading provider details…';
    dialog.dataset.addonId = String(addon.id);
    dialog.showModal();
    fetchManifest(addon.manifest_url).then(function(meta) {
      if (!dialog.open || dialog.dataset.addonId !== String(addon.id)) return;
      document.getElementById('extensionDetailsProvider').textContent = new URL(addon.manifest_url).host;
      document.getElementById('extensionDetailsDescription').textContent = meta.description || '';
      status.textContent = meta.configuration_url ? 'Configuration opens on the provider’s website: ' + new URL(meta.configuration_url).host : 'This manifest does not provide a configuration link.';
      if (meta.configuration_url) { configure.href = meta.configuration_url; configure.hidden = false; }
    }).catch(function() {
      if (dialog.open && dialog.dataset.addonId === String(addon.id)) status.textContent = 'Could not load this provider’s manifest. Try again later.';
    });
  }

  // ── Add extension ──────────────────────────────────────────────────

  function addExtension() {
    var input = document.getElementById('manifestInput');
    var btn = document.getElementById('addBtn');
    var status = document.getElementById('addStatus');
    var url = (input.value || '').trim();

    if (!url) {
      status.className = 'ext-add-status error';
      status.textContent = t('extensions.urlRequired');
      return;
    }

    btn.disabled = true;
    status.className = 'ext-add-status';
    status.textContent = t('extensions.adding');

    fetchManifest(url).then(function(meta) {
      if (meta.requires_configuration) throw new Error('Configure this extension with its provider, then paste the personal manifest URL it supplies.');
      return TorveAPI.post('/me/addons', {
        manifest_url: meta.manifest_url,
        addon_id: meta.id || null,
        name: meta.name || null,
        description: meta.description || null,
        version: meta.version || null,
        has_catalog: meta.has_catalog || false,
        has_streams: meta.has_streams || false,
        installed_from: 'web'
      });
    }).then(function(result) {
      btn.disabled = false;
      input.value = '';
      status.className = 'ext-add-status success';
      status.textContent = (result.name || 'Extension') + ' installed.';
      load();
      setTimeout(function() { status.textContent = ''; }, 4000);
    }).catch(function(err) {
      btn.disabled = false;
      status.className = 'ext-add-status error';
      status.textContent = err.message || t('extensions.addError');
    });
  }

  function fetchManifest(url) {
    return Promise.resolve().then(function() {
      var manifestUrl = extensionURL(url);
      if (!manifestUrl.pathname.endsWith('/manifest.json') || manifestUrl.search) throw new Error('Use the provider’s complete URL ending in /manifest.json.');
      return fetch(manifestUrl.href, { method: 'GET', mode: 'cors', credentials: 'omit', referrerPolicy: 'no-referrer' });
    })
      .then(function(resp) {
        if (!resp.ok) throw new Error('Could not fetch manifest');
        return resp.json();
      })
      .then(function(manifest) {
        if (!manifest.id || !manifest.name || !manifest.version || manifest.entryPointV3 || manifest.entryPointV4) throw new Error('This is not a compatible HTTP/JSON extension manifest.');
        var resources = manifest.resources || [];
        var has_catalog = false;
        var has_streams = false;

        for (var i = 0; i < resources.length; i++) {
          var r = resources[i];
          var name = typeof r === 'string' ? r : (r.name || '');
          if (name === 'catalog') has_catalog = true;
          if (name === 'stream') has_streams = true;
        }

        var hints = manifest.behaviorHints || {};
        var configurationOnly = hints.configurable === true && hints.configurationRequired === true;
        if (!configurationOnly && !resources.some(function(r) { return ['catalog', 'meta', 'stream', 'subtitles'].indexOf(typeof r === 'string' ? r : r.name) >= 0; })) throw new Error('This manifest does not declare a supported extension capability. Enter a compatible manifest URL from your provider.');
        var configureLink = (manifest.links || []).find(function(link) { return /configure/i.test(link.name || ''); });
        var configureURL = manifest.configurationUrl || hints.configurationUrl || (configureLink && configureLink.url);
        if (!configureURL && hints.configurable) configureURL = new URL('configure', extensionURL(url)).href;
        if (configureURL) configureURL = extensionURL(configureURL, extensionURL(url).href).href;

        return {
          id: manifest.id || null,
          name: manifest.name || null,
          description: manifest.description || null,
          version: manifest.version || null,
          has_catalog: has_catalog,
          has_streams: has_streams,
          manifest_url: extensionURL(url).href,
          configuration_url: configureURL || null,
          requires_configuration: hints.configurationRequired === true || (manifest.config || []).some(function(item) { return item.key === 'configure'; })
        };
      });
  }

  // ── Toggle / Remove ────────────────────────────────────────────────

  function toggleAddon(id) {
    TorveAPI.post('/me/addons/' + id + '/toggle', {}).then(function() {
      load();
    }).catch(function() {
      alert(t('extensions.toggleError'));
    });
  }

  function removeAddon(id, name) {
    var msg = t('extensions.removeConfirm').replace('{0}', name || 'this extension');
    if (!confirm(msg)) return;

    TorveAPI.del('/me/addons/' + id).then(function() {
      load();
    }).catch(function() {
      alert(t('extensions.removeError'));
    });
  }

  // ── Init ───────────────────────────────────────────────────────────

  // Translate static HTML elements
  var titleEl = document.querySelector('.portal-page-title');
  if (titleEl) titleEl.textContent = t('extensions.title');
  var introEl = document.querySelector('.portal-page-intro');
  if (introEl) introEl.textContent = t('extensions.intro');

  var addTitleEl = document.querySelector('.ext-add-form h3');
  if (addTitleEl) addTitleEl.textContent = t('extensions.addTitle');
  var addDescEl = document.querySelector('.ext-add-form p');
  // Keep generic provider-neutral guidance; old translation bundles may recommend a provider.

  var addBtnEl = document.getElementById('addBtn');
  if (addBtnEl) addBtnEl.textContent = t('extensions.addBtn');
  var inputEl = document.getElementById('manifestInput');
  if (inputEl) inputEl.placeholder = t('extensions.addPlaceholder') || inputEl.placeholder;

  var instTitleEl = document.querySelector('.portal-section-header h2');
  if (instTitleEl) instTitleEl.textContent = t('extensions.installedTitle');
  var instDescEl = document.querySelector('.portal-section-header span');
  if (instDescEl) instDescEl.textContent = t('extensions.installedDesc') || instDescEl.textContent;

  var aboutTitleEl = document.querySelector('#extensionAbout h3');
  if (aboutTitleEl) aboutTitleEl.textContent = t('extensions.aboutTitle');
  var learnMoreEl = document.querySelector('#extensionAbout a');
  if (learnMoreEl) {
    var vl = t('extensions.learnMore');
    if (vl !== 'extensions.learnMore') learnMoreEl.textContent = vl;
  }

  var railLabelEls = document.querySelectorAll('.portal-rail-stat-label');
  if (railLabelEls[0]) railLabelEls[0].textContent = t('extensions.railInstalled');
  if (railLabelEls[1]) railLabelEls[1].textContent = t('extensions.railStreamCapable');

  // Translate rail links
  var railLinks = document.querySelectorAll('.portal-rail-nav a');
  var railKeys = ['extensions.railAddExtension', 'extensions.railWhatAre', 'extensions.railCatalogVsStream', 'extensions.railNoStreams'];
  railLinks.forEach(function(a, i) {
    if (railKeys[i]) { var v = t(railKeys[i]); if (v !== railKeys[i]) a.textContent = v; }
  });

  // Wire add button and enter key (no inline handlers)
  if (addBtnEl) {
    addBtnEl.removeAttribute('onclick');
    addBtnEl.addEventListener('click', addExtension);
  }
  if (inputEl) {
    inputEl.addEventListener('keydown', function(e) {
      if (e.key === 'Enter') { e.preventDefault(); addExtension(); }
    });
  }

  var scrollToAddEl = document.getElementById('scrollToAdd');
  if (scrollToAddEl) {
    scrollToAddEl.addEventListener('click', function(e) {
      e.preventDefault();
      document.querySelector('.ext-add-form').scrollIntoView({ behavior: 'smooth' });
    });
  }

  var closeDetails = document.getElementById('extensionDetailsClose');
  if (closeDetails) closeDetails.addEventListener('click', function() { document.getElementById('extensionDetails').close(); });

  load();
})();
