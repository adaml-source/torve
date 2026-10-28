/**
 * Torve Portal Analytics - lightweight event tracking.
 * Logs events locally. Can be connected to a real analytics service later.
 * No external tracking services are loaded. No PII is logged.
 */
var TorveAnalytics = (function() {
  'use strict';

  var _enabled = true;

  function track(event, data) {
    if (!_enabled) return;
    var entry = {
      event: event,
      timestamp: new Date().toISOString(),
      path: window.location.pathname + window.location.hash
    };
    if (data) entry.data = data;

    // Currently logs to console only in non-production.
    // Replace this with a real endpoint when analytics infrastructure is ready.
    if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
      console.log('[analytics]', entry);
    }
  }

  // Convenience methods
  function trackSignIn(success) { track('auth_signin', { success: success }); }
  function trackSignUp(success) { track('auth_signup', { success: success }); }
  function trackSignOut() { track('auth_signout'); }
  function trackSetupOpen(category) { track('setup_section_open', { category: category }); }
  function trackIntegrationSave(type) { track('integration_saved', { type: type }); }
  function trackIntegrationRemove(type) { track('integration_removed', { type: type }); }
  function trackDeviceRemove() { track('device_removed'); }
  function trackHelpArticle(articleId) { track('help_article_viewed', { article: articleId }); }
  function trackHelpSearch(query) { track('help_search', { query_length: query.length }); }
  function trackDeepLink(from, to) { track('deep_link', { from: from, to: to }); }

  return {
    track: track,
    trackSignIn: trackSignIn,
    trackSignUp: trackSignUp,
    trackSignOut: trackSignOut,
    trackSetupOpen: trackSetupOpen,
    trackIntegrationSave: trackIntegrationSave,
    trackIntegrationRemove: trackIntegrationRemove,
    trackDeviceRemove: trackDeviceRemove,
    trackHelpArticle: trackHelpArticle,
    trackHelpSearch: trackHelpSearch,
    trackDeepLink: trackDeepLink
  };
})();
