/**
 * Torve Language Switcher.
 * On switch: saves locale, reloads the page.
 * The i18n.js FOUTC prevention hides the body until translations apply,
 * so the reload feels like a clean transition rather than a visible flash.
 */
var TorveLangSwitcher = (function() {
  'use strict';

  var _rendered = false;

  function buildSelect(id) {
    var current = TorveI18n.getLocale();
    var supported = TorveI18n.getSupported();
    var html = '<select class="lang-select" id="' + id + '" aria-label="Language">';
    supported.forEach(function(code) {
      var label = t('languages.' + code) || code.toUpperCase();
      html += '<option value="' + code + '"' + (code === current ? ' selected' : '') + '>' + label + '</option>';
    });
    html += '</select>';
    return html;
  }

  function bindSelect(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.addEventListener('change', function() {
      var newLocale = this.value;
      // Fade out, switch, reload
      document.documentElement.classList.add('i18n-loading');
      document.documentElement.classList.remove('i18n-ready');
      TorveI18n.setLocale(newLocale);
      setTimeout(function() {
        window.location.reload();
      }, 120);
    });
  }

  function render(containerId) {
    var container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = buildSelect('langSelect_' + containerId);
    bindSelect('langSelect_' + containerId);
    _rendered = true;
  }

  function autoRender() {
    if (_rendered) return;
    var headerActions = document.querySelector('.site-header-actions');
    if (headerActions && !headerActions.querySelector('.lang-select')) {
      var span = document.createElement('span');
      span.innerHTML = buildSelect('langSelectAuto');
      headerActions.insertBefore(span, headerActions.firstChild);
      bindSelect('langSelectAuto');
      _rendered = true;
      return;
    }
    if (!document.querySelector('.lang-select')) {
      var wrapper = document.createElement('div');
      wrapper.style.cssText = 'position:fixed;bottom:16px;right:16px;z-index:999';
      wrapper.innerHTML = buildSelect('langSelectFloat');
      document.body.appendChild(wrapper);
      bindSelect('langSelectFloat');
      _rendered = true;
    }
  }

  setTimeout(autoRender, 300);

  return { render: render };
})();
