/**
 * Torve i18n - lightweight internationalization system.
 *
 * Supported locales: en, de, es, fr, it, pt, tr
 * Default/fallback: en
 *
 * Prevents flash of untranslated content (FOUTC):
 * - If locale is non-English, body is hidden until translations are applied
 * - Page scripts call TorveI18n.ready() after applying translations to reveal
 * - English locale skips hiding since HTML is already in English
 */
var TorveI18n = (function() {
  'use strict';

  var SUPPORTED = ['en', 'de', 'es', 'fr', 'it', 'pt', 'tr'];
  var DEFAULT = 'en';
  var STORAGE_KEY = 'torve_lang';
  var _locale = DEFAULT;
  var _strings = {};
  var _loaded = {};
  var _listeners = [];
  var _revealed = false;

  function detectLocale() {
    var stored = localStorage.getItem(STORAGE_KEY);
    if (stored && SUPPORTED.indexOf(stored) >= 0) return stored;
    var nav = (navigator.language || navigator.userLanguage || '').toLowerCase();
    var short = nav.split('-')[0];
    if (SUPPORTED.indexOf(short) >= 0) return short;
    return DEFAULT;
  }

  function setLocale(code) {
    if (SUPPORTED.indexOf(code) < 0) code = DEFAULT;
    _locale = code;
    localStorage.setItem(STORAGE_KEY, code);
    document.documentElement.lang = code;
    _listeners.forEach(function(fn) { try { fn(code); } catch(e) {} });
  }

  function getLocale() { return _locale; }
  function getSupported() { return SUPPORTED.slice(); }

  function registerStrings(locale, strings) {
    if (!_strings[locale]) _strings[locale] = {};
    mergeDeep(_strings[locale], strings);
    _loaded[locale] = true;
  }

  function t(key, params) {
    var val = resolve(_strings[_locale], key) || resolve(_strings[DEFAULT], key) || key;
    if (params) {
      Object.keys(params).forEach(function(k) {
        val = val.replace(new RegExp('\\{' + k + '\\}', 'g'), params[k]);
      });
    }
    return val;
  }

  function resolve(obj, path) {
    if (!obj) return null;
    var parts = path.split('.');
    var cur = obj;
    for (var i = 0; i < parts.length; i++) {
      if (cur[parts[i]] === undefined) return null;
      cur = cur[parts[i]];
    }
    return typeof cur === 'string' ? cur : null;
  }

  function mergeDeep(target, source) {
    Object.keys(source).forEach(function(key) {
      if (source[key] && typeof source[key] === 'object' && !Array.isArray(source[key])) {
        if (!target[key]) target[key] = {};
        mergeDeep(target[key], source[key]);
      } else {
        target[key] = source[key];
      }
    });
  }

  function onChange(fn) {
    _listeners.push(fn);
    return function() { _listeners = _listeners.filter(function(f) { return f !== fn; }); };
  }

  function isLoaded(locale) { return !!_loaded[locale || _locale]; }

  // Reveal the page (called after translations are applied)
  function ready() {
    if (_revealed) return;
    _revealed = true;
    document.documentElement.classList.remove('i18n-loading');
    document.documentElement.classList.add('i18n-ready');
  }

  // Auto-detect on load
  _locale = detectLocale();
  document.documentElement.lang = _locale;

  // Hide body immediately for non-English to prevent FOUTC
  if (_locale !== DEFAULT) {
    document.documentElement.classList.add('i18n-loading');
  } else {
    _revealed = true;
  }

  // Safety: reveal after timeout even if ready() is never called
  setTimeout(function() { ready(); }, 1500);

  return {
    t: t,
    setLocale: setLocale,
    getLocale: getLocale,
    getSupported: getSupported,
    registerStrings: registerStrings,
    onChange: onChange,
    isLoaded: isLoaded,
    ready: ready,
    SUPPORTED: SUPPORTED,
    DEFAULT: DEFAULT
  };
})();

var t = TorveI18n.t;
