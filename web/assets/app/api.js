/**
 * Torve API client - cookie-backed BFF session model.
 *
 * All auth state is managed via HttpOnly cookies set by the server.
 * Browser JS never sees or stores tokens. API calls go through
 * the same-origin /web/ proxy which injects auth from cookies.
 *
 * CSRF: state-changing requests include the CSRF token from the
 * torve_csrf cookie in the X-CSRF-Token header.
 */
var TorveAPI = (function() {
  'use strict';

  var BFF_BASE = '/web';
  var USER_KEY = 'torve_user';  // Cached user metadata only (no secrets)

  var _profileContext = {}; // Parent proof remains in memory for this tab only.
  var _user = null;
  var _listeners = [];
  var _sessionChecked = false;

  // â”€â”€ User cache (metadata only, no tokens) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  function storeUser(user) {
    _user = user;
    if (user) {
      try { sessionStorage.setItem(USER_KEY, JSON.stringify(user)); } catch(e) {}
    } else {
      sessionStorage.removeItem(USER_KEY);
    }
  }

  function getCachedUser() {
    if (_user) return _user;
    try {
      var s = sessionStorage.getItem(USER_KEY);
      if (s) { _user = JSON.parse(s); return _user; }
    } catch(e) {}
    return null;
  }

  function clearUserCache() {
    _profileContext = {};
    _user = null;
    sessionStorage.removeItem(USER_KEY);
    // Clear any legacy localStorage tokens from previous implementation
    try {
      localStorage.removeItem('torve_access_token');
      localStorage.removeItem('torve_refresh_token');
      localStorage.removeItem('torve_user');
      sessionStorage.removeItem('torve_access_token');
    } catch(e) {}
  }

  // â”€â”€ CSRF â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  function getCsrfToken() {
    var match = document.cookie.match(/(?:^|;\s*)torve_csrf=([^;]*)/);
    return match ? match[1] : '';
  }

  // â”€â”€ Auth state notifications â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  function notifyListeners() {
    var state = getAuthState();
    for (var i = 0; i < _listeners.length; i++) {
      try { _listeners[i](state); } catch(e) { console.error('Auth listener error:', e); }
    }
  }

  function onAuthChange(fn) {
    _listeners.push(fn);
    return function() {
      _listeners = _listeners.filter(function(f) { return f !== fn; });
    };
  }

  function getAuthState() {
    return {
      isAuthenticated: !!getCachedUser(),
      user: getCachedUser(),
      isLoading: false
    };
  }

  // â”€â”€ HTTP helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  function request(method, path, body, options) {
    options = options || {};

    var headers = { 'Content-Type': 'application/json' };

    if (!options.noAuth) Object.keys(_profileContext).forEach(function(key) { headers[key] = _profileContext[key]; });

    // Add CSRF token for state-changing requests
    if (method !== 'GET' && method !== 'HEAD') {
      var csrf = getCsrfToken();
      if (csrf) headers['X-CSRF-Token'] = csrf;
    }

    // Determine URL: BFF proxy for authenticated calls, direct for public
    var url;
    if (options.noAuth) {
      // Public endpoints go through BFF session routes
      url = BFF_BASE + path;
    } else {
      // Authenticated endpoints go through BFF API proxy
      url = BFF_BASE + '/api' + path;
    }

    return fetch(url, {
      method: method,
      headers: headers,
      body: body ? JSON.stringify(body) : undefined,
      credentials: 'same-origin'
    }).then(function(res) {
      if (res.status === 401 && !options.noRetry) {
        // Session expired and couldn't be refreshed
        clearUserCache();
        notifyListeners();
        return Promise.reject({ status: 401, message: 'Session expired' });
      }
      if (res.status === 204) {
        return undefined;  // No content
      }
      return res.json().then(function(data) {
        if (!res.ok) {
          var detail = data && data.detail;
          var errorCode = detail && typeof detail === 'object' ? detail.error_code : data && data.error_code;
          var message = detail && typeof detail === 'object' ? detail.message : detail;
          return Promise.reject({
            status: res.status,
            error_code: errorCode,
            message: message || data.message || 'Request failed',
            data: data
          });
        }
        return data;
      });
    });
  }

  function get(path, options) { return request('GET', path, null, options); }
  function post(path, body, options) { return request('POST', path, body, options); }
  function put(path, body, options) { return request('PUT', path, body, options); }
  function del(path, options) { return request('DELETE', path, null, options); }
  function patch(path, body, options) { return request('PATCH', path, body, options); }

  // â”€â”€ Auth flows â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  function signup(email, password, displayName) {
    // Signup still uses the direct API (creates account, then we login via BFF)
    return fetch('/web/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ email: email, password: password })
    }).then(function(res) {
      // If login fails (account doesn't exist yet), do signup first
      if (!res.ok) {
        // Need to create account via the real API first
        return fetch(BFF_BASE + '/api/auth/signup', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ email: email, password: password, display_name: displayName || null })
        }).then(function(signupRes) {
          if (!signupRes.ok) {
            return signupRes.json().then(function(d) {
              return Promise.reject({ status: signupRes.status, message: d.detail || 'Could not create account' });
            });
          }
          // Account created, now login via BFF for cookie session
          return login(email, password);
        });
      }
      return res.json().then(function(data) {
        if (data.user) storeUser(data.user);
        notifyListeners();
        return data;
      });
    });
  }

  function login(email, password) {
    return fetch(BFF_BASE + '/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ email: email, password: password })
    }).then(function(res) {
      return res.json().then(function(data) {
        if (!res.ok) return Promise.reject({ status: res.status, message: data.detail || 'Invalid email or password' });
        if (data.user) storeUser(data.user);
        notifyListeners();
        return data;
      });
    });
  }

  function logout() {
    return fetch(BFF_BASE + '/auth/logout', {
      method: 'POST',
      credentials: 'same-origin'
    }).catch(function() {}).then(function() {
      clearUserCache();
      notifyListeners();
    });
  }

  function restoreSession() {
    if (_sessionChecked && getCachedUser()) {
      return Promise.resolve(getCachedUser());
    }
    return fetch(BFF_BASE + '/auth/session', {
      method: 'GET',
      credentials: 'same-origin'
    }).then(function(res) {
      return res.json();
    }).then(function(data) {
      _sessionChecked = true;
      if (data.authenticated && data.user) {
        storeUser(data.user);
        notifyListeners();
        return data.user;
      }
      clearUserCache();
      return null;
    }).catch(function() {
      clearUserCache();
      return null;
    });
  }

  function fetchCurrentUser() {
    return get('/me').then(function(user) {
      storeUser(user);
      notifyListeners();
      return user;
    });
  }

  function getAccessState() {
    return get('/me/access-state');
  }

  function createStripeCheckoutSession(purchaseType) {
    return post('/billing/stripe/checkout-session', { purchase_type: purchaseType });
  }

  function createStripePortalSession() {
    return post('/billing/stripe/portal-session', {});
  }

  function forgotPassword(email) {
    return post('/auth/password-reset/request', { email: email }, { noAuth: true });
  }

  function resetPassword(token, newPassword) {
    return post('/auth/password-reset/confirm', { token: token, new_password: newPassword }, { noAuth: true });
  }

  function resendVerification(email) {
    return post('/auth/resend-verification', { email: email }, { noAuth: true });
  }

  // Ensure CSRF cookie is set on page load
  function ensureCsrf() {
    if (!getCsrfToken()) {
      fetch(BFF_BASE + '/auth/csrf', { credentials: 'same-origin' }).catch(function() {});
    }
  }

  // â”€â”€ Backward compat: getAccessToken returns truthiness based on session â”€â”€

  function getAccessToken() {
    return getCachedUser() ? 'cookie-session' : null;
  }

  // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  return {
    setProfileContext: function(profile, grant) {
      _profileContext = { 'X-Torve-Profile-Id': profile || 'default' };
      if (grant) _profileContext['X-Torve-Parent-Grant'] = grant;
    },
    signup: signup,
    login: login,
    logout: logout,
    restoreSession: restoreSession,
    fetchCurrentUser: fetchCurrentUser,
    getAccessState: getAccessState,
    createStripeCheckoutSession: createStripeCheckoutSession,
    createStripePortalSession: createStripePortalSession,
    forgotPassword: forgotPassword,
    resetPassword: resetPassword,
    resendVerification: resendVerification,
    getAuthState: getAuthState,
    onAuthChange: onAuthChange,
    getAccessToken: getAccessToken,
    ensureCsrf: ensureCsrf,
    get: get,
    post: post,
    put: put,
    del: del,
    patch: patch
  };
})();

// Ensure CSRF cookie on load
TorveAPI.ensureCsrf();
