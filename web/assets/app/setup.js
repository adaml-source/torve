/**
 * Torve Setup Portal - integration management UI logic.
 * Depends on api.js being loaded first.
 */
var TorveSetup = (function() {
  'use strict';

  // â”€â”€ Integration registry â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Maps the app's integration keys to the website's profile-scoped forms.
  // Usenet, NZB indexers and replay websites have dedicated editors.
  function profileId() { return typeof TorveProfiles !== 'undefined' ? TorveProfiles.selectedId() : 'default'; }
  function scopedType(type) { return profileId() === 'default' ? type : type + '::p:' + profileId(); }
  function scopedSettings(settings) {
    if (profileId() === 'default') return settings;
    var prefix='profilepref_v1:' + profileId() + ':', result={};
    Object.keys(settings).forEach(function(key) { if(key.indexOf(prefix)===0) result[key.slice(prefix.length)]=settings[key]; });
    return result;
  }
  var INTEGRATIONS = {
    DEBRID_API_KEY_TORBOX: { category:'debrid', label:'TorBox', description:'Use TorBox with this profile.', fields:[{key:'api_key',label:'API key',type:'password',required:true}] },
    DEBRID_API_KEY_REAL_DEBRID: { category:'debrid', label:'Real-Debrid', description:'Use a Real-Debrid connection with this profile.', fields:[{key:'api_key',label:'API key',type:'password',required:true}] },
    DEBRID_API_KEY_ALL_DEBRID: { category:'debrid', label:'AllDebrid', description:'Use AllDebrid with this profile.', fields:[{key:'api_key',label:'API key',type:'password',required:true}] },
    DEBRID_API_KEY_PREMIUMIZE: { category:'debrid', label:'Premiumize', description:'Use Premiumize with this profile.', fields:[{key:'api_key',label:'API key',type:'password',required:true}] },
    JELLYFIN_API_KEY: { category:'libraries', label:'Jellyfin', description:'Connect a Jellyfin user for this profile.', fields:[{key:'server_url',label:'Server URL',type:'url',required:true},{key:'api_key',label:'API key',type:'password',required:true},{key:'user_id',label:'Jellyfin user ID',type:'text',required:false}] },
    PLEX_ACCESS_TOKEN: { category:'libraries', label:'Plex', description:'Connect Plex for this profile.', fields:[{key:'api_key',label:'Access token',type:'password',required:true}] },
    // Tracking
    TRAKT_TOKENS: {
      category: 'tracking',
      label: 'Trakt',
      description: 'Automatic watch history tracking and scrobbling. Keeps a record of everything you watch.',
      whoFor: 'For tracking what you watch across apps and services.',
      whatYouNeed: 'A Trakt account. You will authorize Torve through Trakt.',
      recommended: true,
      fields: [
        { key: 'access_token', label: 'Access Token', type: 'password', required: true, placeholder: 'OAuth access token' },
        { key: 'refresh_token', label: 'Refresh Token', type: 'password', required: false, placeholder: 'OAuth refresh token (optional)' }
      ],
      note: 'Trakt uses OAuth. For best results, connect Trakt from the Torve app where the OAuth flow is built in. You can paste tokens here if you have them.'
    },
    SIMKL_ACCESS_TOKEN: {
      category: 'tracking',
      label: 'SIMKL',
      description: 'Track your TV shows, anime, and movies. Syncs watch progress and ratings.',
      whoFor: 'For tracking shows, anime, and movies with SIMKL.',
      whatYouNeed: 'A SIMKL account. Connect through the app or paste your access token.',
      recommended: false,
      fields: [
        { key: 'access_token', label: 'Access Token', type: 'password', required: true, placeholder: 'SIMKL access token' }
      ],
      note: 'SIMKL uses OAuth. For best results, connect SIMKL from the Torve app where the OAuth flow is built in.'
    },

    // Metadata & Ratings
    OMDB_API_KEY: {
      category: 'metadata',
      label: 'OMDb',
      description: 'Movie and TV show metadata, ratings, and poster information from the Open Movie Database.',
      whoFor: 'Adds richer ratings and poster data to your library.',
      whatYouNeed: 'A free or paid OMDb API key from omdbapi.com',
      recommended: true,
      fields: [
        { key: 'api_key', label: 'API Key', type: 'text', required: true, placeholder: 'Your OMDb API key' }
      ]
    },
    MDBLIST_API_KEY: {
      category: 'metadata',
      label: 'MDBList',
      description: 'Aggregated ratings and metadata from multiple sources including IMDb, TMDB, Trakt, and more.',
      whoFor: 'Aggregates ratings from IMDb, TMDB, Trakt, and more.',
      whatYouNeed: 'An MDBList API key from mdblist.com',
      recommended: false,
      fields: [
        { key: 'api_key', label: 'API Key', type: 'text', required: true, placeholder: 'Your MDBList API key' }
      ]
    },

    // Search provider
    AI_SEARCH_API_KEY: {
      category: 'ai',
      label: 'Search provider',
      description: 'Search by mood, plot, or title using a supported provider.',
      whoFor: 'Search by mood, plot, or vibe instead of browsing.',
      whatYouNeed: 'An account with a supported search provider and an API key.',
      recommended: false,
      hasProviderSelection: true,
      providers: [],  // Populated dynamically from /meta/ai-providers
      fields: [
        { key: 'api_key', label: 'API Key', type: 'password', required: true, placeholder: 'Your provider API key' }
      ]
    }
  };

  var CATEGORIES = [
    { id: 'streaming', label: 'Streaming sources', icon: 'play', description: 'Connect your Debrid or Usenet provider once, then use that connection on your signed-in devices.', helpArticle: 'what-is-streaming-service' },
    { id: 'tracking', label: 'Tracking', icon: 'activity', description: 'Track what you watch with Trakt, SIMKL, or similar services.', helpArticle: 'connect-trakt' },
    { id: 'metadata', label: 'Metadata & Ratings', icon: 'star', description: 'Enhance content information with additional metadata and ratings sources.', helpArticle: 'setup-omdb' },
    { id: 'ai', label: 'Search', icon: 'search', description: 'Connect a supported search provider for mood and plot searches.', helpArticle: null },
    { id: 'channels', label: 'Channels', icon: 'tv', description: 'Manage your channel sources and playlists.', helpArticle: 'url-import-vs-service-login' },
    { id: 'sync', label: 'TV Sync', icon: 'monitor', description: 'Understand how settings sync between your devices.', helpArticle: 'what-syncs' }
  ];

  // â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  var _savedIntegrations = {};  // integration_type -> IntegrationOut
  var _savedPlaylists = [];
  var _savedAddons = [];        // AddonOut[] used to detect PANDA_ADDON install
  var _accountSettings = {};    // account settings (language, ai_provider, etc.)
  var _onUpdate = null;

  // â”€â”€ AI integration type mapping â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // The Android client's IntegrationSecretKey enum uses per-provider
  // integration_type names (CLAUDE_API_KEY, CHATGPT_API_KEY, etc.). The
  // generic "AI_SEARCH_API_KEY" type the UI used before never got
  // restored on device (AccountSessionCoordinator skips unknown types).
  // We keep AI_SEARCH_API_KEY as the UI-facing abstraction but translate
  // at the API boundary on save / remove / test so what's actually
  // written to /me/integrations/* is recognised by the client.
  var AI_PROVIDER_TO_INTEGRATION_TYPE = {
    CLAUDE: 'CLAUDE_API_KEY',
    CHATGPT: 'CHATGPT_API_KEY',
    GEMINI: 'GEMINI_API_KEY',
    PERPLEXITY: 'PERPLEXITY_API_KEY',
    DEEPSEEK: 'DEEPSEEK_API_KEY'
  };
  // Provider-specific types the client recognises, plus the legacy
  // AI_SEARCH_API_KEY for cleanup of rows written before this fix.
  var ALL_AI_INTEGRATION_TYPES = [
    'CLAUDE_API_KEY', 'CHATGPT_API_KEY', 'GEMINI_API_KEY',
    'PERPLEXITY_API_KEY', 'DEEPSEEK_API_KEY',
    'AI_SEARCH_API_KEY'
  ];

  // Returns {type, record} for the first provider-specific AI integration
  // found in _savedIntegrations, or null.
  function _findExistingAiIntegration() {
    for (var i = 0; i < ALL_AI_INTEGRATION_TYPES.length; i++) {
      var t = ALL_AI_INTEGRATION_TYPES[i];
      if (_savedIntegrations[t]) return { type: t, record: _savedIntegrations[t] };
    }
    return null;
  }

  // Mirror whatever provider-specific AI key exists back to
  // _savedIntegrations.AI_SEARCH_API_KEY so the UI can keep reading via
  // the generic handle without caring about the underlying provider.
  function _hydrateAiSearchAlias() {
    if (_savedIntegrations.AI_SEARCH_API_KEY) return;
    var existing = _findExistingAiIntegration();
    if (existing) _savedIntegrations.AI_SEARCH_API_KEY = existing.record;
  }

  // â”€â”€ Fallback providers (used only if /meta/ai-providers fails) â”€â”€â”€â”€â”€â”€
  var _FALLBACK_PROVIDERS = [
    { value: 'CHATGPT', label: 'ChatGPT', api_key_placeholder: 'sk-...' },
    { value: 'GEMINI', label: 'Gemini', api_key_placeholder: 'Your Gemini API key' },
    { value: 'CLAUDE', label: 'Claude', api_key_placeholder: 'sk-ant-...' },
    { value: 'PERPLEXITY', label: 'Perplexity', api_key_placeholder: 'pplx-...' },
    { value: 'DEEPSEEK', label: 'DeepSeek', api_key_placeholder: 'Your DeepSeek API key' }
  ];

  function _mapProviders(raw) {
    return (raw || []).map(function(p) {
      return { id: p.value, label: p.label, keyPlaceholder: p.api_key_placeholder || 'Your API key' };
    });
  }

  // â”€â”€ Data loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function loadAll() {
    var capturedProfile=profileId();
    return Promise.all([
      TorveAPI.get('/me/integrations'),
      TorveAPI.get('/me/playlists').catch(function() { return []; }),
      TorveAPI.get('/me/account-settings'),
      TorveAPI.get('/meta/ai-providers', { noAuth: true }).catch(function() { return null; }),
      TorveAPI.get('/me/addons').catch(function() { return []; }),
      TorveSources.load()
    ]).then(function(results) {
      if (capturedProfile !== profileId()) return;
      _savedIntegrations = {};
      (results[0] || []).forEach(function(i) {
        var suffix='::p:' + capturedProfile, type=i.integration_type;
        if (capturedProfile === 'default' && type.indexOf('::p:')===-1) _savedIntegrations[type]=i;
        else if(capturedProfile !== 'default' && type.endsWith(suffix)) _savedIntegrations[type.slice(0,-suffix.length)]=i;
      });
      _savedPlaylists = results[1] || [];
      _accountSettings = scopedSettings((results[2] && results[2].settings) ? results[2].settings : {});
      _savedAddons = results[4] || [];

      // Populate search providers from registry (or fallback)
      var rawProviders = results[3] || _FALLBACK_PROVIDERS;
      INTEGRATIONS.AI_SEARCH_API_KEY.providers = _mapProviders(rawProviders);
      // Expose any provider-specific search integration
      // under the generic AI_SEARCH_API_KEY handle so UI reads stay simple.
      _hydrateAiSearchAlias();
      if (_onUpdate) _onUpdate();
      return { integrations: _savedIntegrations, playlists: _savedPlaylists };
    });
  }

  function findAddonByManifestId(manifestId) {
    for (var i = 0; i < _savedAddons.length; i++) {
      if (_savedAddons[i].addon_id === manifestId) return _savedAddons[i];
    }
    return null;
  }

  function getStatus(type) {
    var def = INTEGRATIONS[type];

    // Addon-backed integrations (Panda) track install state via /me/addons
    // rather than /me/integrations. An installed + enabled addon is
    // "connected"; an installed but disabled one needs attention.
    if (def && def.externalSetup && def.addonId) {
      var addon = findAddonByManifestId(def.addonId);
      if (!addon) return def.recommended ? 'recommended' : 'not_setup';
      return addon.is_enabled === false ? 'needs_attention' : 'connected';
    }

    var saved = _savedIntegrations[type];
    if (!saved) return def && def.recommended ? 'recommended' : 'not_setup';
    if (!saved.is_connected) return 'needs_attention';
    return 'connected';
  }

  function getStatusLabel(status) {
    switch(status) {
      case 'connected': return t('common.connected');
      case 'recommended': return t('common.recommended');
      case 'needs_attention': return t('common.needsAttention');
      case 'not_setup': return t('common.notSetUp');
      default: return t('common.notSetUp');
    }
  }

  function getStatusClass(status) {
    switch(status) {
      case 'connected': return 'status-connected';
      case 'recommended': return 'status-recommended';
      case 'needs_attention': return 'status-attention';
      default: return 'status-default';
    }
  }

  // â”€â”€ Save/Remove â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function saveIntegration(type, credentials, displayLabel) {
    if (type === 'AI_SEARCH_API_KEY') {
      return _saveAiIntegration(credentials, displayLabel);
    }
    return TorveAPI.put('/me/integrations/' + scopedType(type), {
      integration_type: scopedType(type),
      config: { torve_profile_id: profileId(), family_copy_profile: profileId() },
      credentials: credentials,
      storage_mode: 'account',
      display_identifier: displayLabel || INTEGRATIONS[type].label || type
    }).then(function(result) {
      _savedIntegrations[type] = result;
      if (_onUpdate) _onUpdate();
      return result;
    });
  }

  // Save the AI key under the currently-selected provider's integration
  // type (e.g. CLAUDE_API_KEY) so the Android client's restoreIntegrations
  // recognises it. Cleans up any stale records for other providers, plus
  // the legacy AI_SEARCH_API_KEY slot, so the user never ends up with
  // multiple AI integrations in different states.
  function _saveAiIntegration(credentials, displayLabel) {
    var provider = _accountSettings.ai_provider;
    if (!provider) {
      return Promise.reject(new Error('Please select a search provider before saving the key.'));
    }
    var resolvedType = AI_PROVIDER_TO_INTEGRATION_TYPE[provider];
    if (!resolvedType) {
      return Promise.reject(new Error('Unsupported search provider: ' + provider));
    }
    var staleTypes = ALL_AI_INTEGRATION_TYPES.filter(function(t) { return t !== resolvedType; });
    var cleanup = Promise.all(staleTypes.map(function(t) {
      if (!_savedIntegrations[t]) return Promise.resolve();
      return TorveAPI.del('/me/integrations/' + scopedType(t))
        .then(function() { delete _savedIntegrations[t]; })
        .catch(function() { /* non-fatal; row may already be gone */ });
    }));
    return cleanup.then(function() {
      return TorveAPI.put('/me/integrations/' + scopedType(resolvedType), {
        integration_type: scopedType(resolvedType),
        config: { torve_profile_id: profileId(), family_copy_profile: profileId() },
        credentials: credentials,
        storage_mode: 'account',
        display_identifier: displayLabel || INTEGRATIONS.AI_SEARCH_API_KEY.label
      }).then(function(result) {
        _savedIntegrations[resolvedType] = result;
        _savedIntegrations.AI_SEARCH_API_KEY = result; // alias for UI reads
        if (_onUpdate) _onUpdate();
        return result;
      });
    });
  }

  function removeIntegration(type) {
    // "Disconnect AI" in the UI clears the key regardless of which
    // provider was active, so the remove path covers all provider slots
    // + the legacy generic row.
    if (type === 'AI_SEARCH_API_KEY' ||
        ALL_AI_INTEGRATION_TYPES.indexOf(type) !== -1) {
      return _removeAllAiIntegrations();
    }
    return TorveAPI.del('/me/integrations/' + scopedType(type)).then(function() {
      delete _savedIntegrations[type];
      if (_onUpdate) _onUpdate();
    });
  }

  function _removeAllAiIntegrations() {
    var dels = ALL_AI_INTEGRATION_TYPES.map(function(t) {
      if (!_savedIntegrations[t]) return Promise.resolve();
      return TorveAPI.del('/me/integrations/' + scopedType(t))
        .then(function() { delete _savedIntegrations[t]; })
        .catch(function() { /* non-fatal */ });
    });
    return Promise.all(dels).then(function() {
      delete _savedIntegrations.AI_SEARCH_API_KEY;
      if (_onUpdate) _onUpdate();
    });
  }

  function testIntegration(type) {
    // For the generic AI handle, dispatch to whichever provider-specific
    // integration is actually saved.
    if (type === 'AI_SEARCH_API_KEY') {
      var existing = _findExistingAiIntegration();
      if (!existing) return Promise.reject(new Error('No search provider saved.'));
      return TorveAPI.post('/me/integrations/' + scopedType(existing.type) + '/test');
    }
    return TorveAPI.post('/me/integrations/' + scopedType(type) + '/test');
  }

  function getAiProvider() {
    return _accountSettings.ai_provider || null;
  }

  function getAiProviderLabel() {
    var providerId = getAiProvider();
    if (!providerId) return null;
    var def = INTEGRATIONS.AI_SEARCH_API_KEY;
    if (!def || !def.providers) return providerId;
    for (var i = 0; i < def.providers.length; i++) {
      if (def.providers[i].id === providerId) return def.providers[i].label;
    }
    return providerId;
  }

  function saveAiProvider(providerId) {
    var settings={}; settings[profileId()==='default' ? 'ai_provider' : 'profilepref_v1:'+profileId()+':ai_provider']=providerId;
    return TorveAPI.patch('/me/account-settings', {
      settings: settings
    }).then(function(result) {
      if (result && result.settings) _accountSettings = scopedSettings(result.settings);
      if (_onUpdate) _onUpdate();
      return result;
    });
  }

  function clearAiProvider() {
    var settings={}; settings[profileId()==='default' ? 'ai_provider' : 'profilepref_v1:'+profileId()+':ai_provider']='';
    return TorveAPI.patch('/me/account-settings', {
      settings: settings
    }).then(function(result) {
      if (result && result.settings) _accountSettings = scopedSettings(result.settings);
      if (_onUpdate) _onUpdate();
      return result;
    });
  }

  function removePlaylist(playlistId) {
    return TorveAPI.del('/me/playlists/' + encodeURIComponent(playlistId)).then(function() {
      _savedPlaylists = _savedPlaylists.filter(function(p) { return p.playlist_id !== playlistId; });
      if (_onUpdate) _onUpdate();
    });
  }

  function savePlaylist(playlistId, payload) {
    return TorveAPI.put('/me/playlists/' + encodeURIComponent(playlistId), payload).then(function(result) {
      var replaced = false;
      _savedPlaylists = _savedPlaylists.map(function(existing) {
        if (existing.playlist_id !== result.playlist_id && existing.playlist_id !== playlistId) return existing;
        replaced = true;
        return result;
      });
      if (!replaced) _savedPlaylists.push(result);
      if (_onUpdate) _onUpdate();
      return result;
    });
  }

  function validateEpgUrl(epgUrl) {
    return TorveAPI.post('/me/playlists/validate-epg', { epg_url: epgUrl });
  }

  // â”€â”€ Progress â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function getProgress() {
    var total = 0;
    var connected = 0;
    Object.keys(INTEGRATIONS).forEach(function(type) {
      if (INTEGRATIONS[type].recommended) {
        total++;
        if (getStatus(type) === 'connected') connected++;
      }
    });
    return { connected: connected, total: total };
  }

  function onUpdate(fn) { _onUpdate = fn; }

  return {
    INTEGRATIONS: INTEGRATIONS,
    CATEGORIES: CATEGORIES,
    loadAll: loadAll,
    getStatus: getStatus,
    getStatusLabel: getStatusLabel,
    getStatusClass: getStatusClass,
    saveIntegration: saveIntegration,
    removeIntegration: removeIntegration,
    savePlaylist: savePlaylist,
    removePlaylist: removePlaylist,
    validateEpgUrl: validateEpgUrl,
    testIntegration: testIntegration,
    getProgress: getProgress,
    getAiProvider: getAiProvider,
    getAiProviderLabel: getAiProviderLabel,
    saveAiProvider: saveAiProvider,
    clearAiProvider: clearAiProvider,
    getSavedIntegrations: function() { return _savedIntegrations; },
    getSavedPlaylists: function() { return _savedPlaylists; },
    getSavedAddons: function() { return _savedAddons; },
    findAddonByManifestId: findAddonByManifestId,
    getAccountSettings: function() { return _accountSettings; },
    refreshView: function() { if (_onUpdate) _onUpdate(); },
    onUpdate: onUpdate
  };
})();
