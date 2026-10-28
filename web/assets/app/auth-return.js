/* A guide deep link survives sign-in; external and unrelated redirects do not. */
(function () {
  'use strict';
  function destination(value, origin) {
    if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\s]/.test(value)) return null;
    try {
      const url = new URL(value, origin);
      if (url.origin !== origin || !['/app/help.html', '/documentation.html'].includes(url.pathname)) return null;
      return '/app/help.html' + url.search + url.hash;
    } catch { return null; }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { destination };
  if (typeof window === 'undefined') return;
  const next = destination(new URLSearchParams(location.search).get('next'), location.origin);
  window.TorveAuthReturn = () => next || '/account.html';
  if (!next) return;
  document.querySelectorAll('a[href="/signin.html"], a[href="/signup.html"]').forEach(link => {
    link.href = link.pathname + '?next=' + encodeURIComponent(next);
  });
  const heading = document.querySelector('.auth-form-title');
  const subtitle = document.querySelector('.auth-form-subtitle');
  if (heading && location.pathname.includes('signin')) heading.textContent = 'Sign in to read the guide';
  if (subtitle) subtitle.textContent = 'After signing in, you’ll return to the guide page you opened.';
  fetch('/web/auth/session', { credentials: 'same-origin', cache: 'no-store' })
    .then(response => response.ok ? response.json() : null)
    .then(session => { if (session?.authenticated) location.replace(next); })
    .catch(() => {}); // The sign-in form remains usable if the session check fails.
})();
