/**
 * Torve App Shell - session restore, route guards, nav, sign out.
 * Works with the shared site-header (no separate sidebar).
 */
(function() {
  'use strict';

  var loading = document.getElementById('loading');
  var appShell = document.getElementById('appShell');

  TorveAPI.restoreSession().then(function(user) {
    if (!user && !TorveAPI.getAuthState().isAuthenticated) {
      window.location.replace('/signin.html');
      return;
    }
    initShell(user || TorveAPI.getAuthState().user);
  }).catch(function() {
    window.location.replace('/signin.html');
  });

  function initShell(user) {
    if (!user) {
      window.location.replace('/signin.html');
      return;
    }

    // Populate user info
    var emailEl = document.getElementById('userEmail');
    if (emailEl) emailEl.textContent = user.email || '';

    // Verification banner
    var banner = document.getElementById('verifyBanner');
    if (banner && user.is_verified === false) {
      banner.classList.add('visible');
    }

    // Resend verification
    var resendLink = document.getElementById('resendLink');
    if (resendLink && user.email) {
      resendLink.addEventListener('click', function(e) {
        e.preventDefault();
        TorveAPI.resendVerification(user.email).then(function() {
          resendLink.textContent = t('verifyBanner.sent');
          resendLink.style.pointerEvents = 'none';
        }).catch(function() {
          resendLink.textContent = t('verifyBanner.failed');
        });
      });
    }

    // Translate nav links
    translateNav();

    // Highlight active nav link
    highlightNav();

    // Sign out
    var signoutBtn = document.getElementById('signoutBtn');
    if (signoutBtn) {
      signoutBtn.addEventListener('click', function() {
        if (typeof TorveAnalytics !== 'undefined') TorveAnalytics.trackSignOut();
        TorveAPI.logout().then(function() {
          window.location.replace('/signin.html');
        });
      });
    }

    // Language switcher
    var actions = document.querySelector('.site-header-actions');
    if (actions && typeof TorveLangSwitcher !== 'undefined') {
      var langContainer = document.createElement('span');
      langContainer.id = 'langSwitcher';
      actions.insertBefore(langContainer, actions.firstChild);
      TorveLangSwitcher.render('langSwitcher');
    }

    // Show app and signal locale ready
    if (loading) loading.style.display = 'none';
    if (appShell) appShell.style.display = 'block';
    if (typeof TorveI18n !== 'undefined') TorveI18n.ready();
  }

  function translateNav() {
    if (typeof t !== 'function') return;
    // Translate top nav links by matching href
    var navMap = {
      '/app/': 'nav.overview',
      '/app/setup.html': 'nav.setup',
      '/app/extensions.html': 'nav.extensions',
      '/app/devices.html': 'nav.devices',
      '/app/help.html': 'nav.help',
      '/app/premium.html': 'nav.premium',
      '/app/account.html': 'nav.account'
    };
    document.querySelectorAll('.site-header-nav a').forEach(function(a) {
      var key = navMap[a.getAttribute('href')];
      if (key) a.textContent = t(key);
    });
    // Translate sign out button
    var signoutBtn = document.getElementById('signoutBtn');
    if (signoutBtn) signoutBtn.textContent = t('common.signOut');
    // Translate verification banner
    var banner = document.getElementById('verifyBanner');
    if (banner) {
      var resendLink = document.getElementById('resendLink');
      if (resendLink) {
        banner.childNodes[0].textContent = t('verifyBanner.text') + ' ';
        resendLink.textContent = t('verifyBanner.resendLink');
      }
    }
    // Translate rail nav links if present
    document.querySelectorAll('.portal-rail-nav a').forEach(function(a) {
      var key = navMap[a.getAttribute('href')];
      if (key) a.textContent = t(key);
    });

    // Translate static page content (titles, intros, section headers)
    var path = window.location.pathname;
    var pageMap = {
      '/app/': { title: 'overview.title', intro: 'overview.intro' },
      '/app/index.html': { title: 'overview.title', intro: 'overview.intro' },
      '/app/setup.html': { title: 'setup.title', intro: 'setup.intro' },
      '/app/devices.html': { title: 'devices.title', intro: 'devices.intro' },
      '/app/help.html': { title: 'help.title', intro: 'help.intro' },
      '/app/account.html': { title: 'account.title', intro: 'account.intro' },
      '/app/extensions.html': { title: 'extensions.title', intro: 'extensions.intro' },
      '/app/premium.html': { title: 'premium.title', intro: 'premium.intro' }
    };
    var pm = pageMap[path];
    if (pm) {
      var titleEl = document.querySelector('.portal-page-title, .setup-page-title');
      var introEl = document.querySelector('.portal-page-intro, .setup-page-intro');
      if (titleEl) { var vt = t(pm.title); if (vt !== pm.title) titleEl.textContent = vt; }
      if (introEl) { var vi = t(pm.intro); if (vi !== pm.intro) introEl.textContent = vi; }
    }

    // Translate overview quick access cards
    if (path === '/app/' || path === '/app/index.html') {
      var sectionHeader = document.querySelector('.portal-section-header h2');
      if (sectionHeader) { var vq = t('overview.quickAccess'); if (vq !== 'overview.quickAccess') sectionHeader.textContent = vq; }
      var cards = document.querySelectorAll('.portal-card');
      var cardKeys = [
        { title: 'overview.setupTitle', desc: 'overview.setupDesc' },
        { title: 'nav.extensions', desc: 'extensions.intro' },
        { title: 'overview.devicesTitle', desc: 'overview.devicesDesc' },
        { title: 'overview.helpTitle', desc: 'overview.helpDesc' },
        { title: 'premium.title', desc: 'premium.intro' },
        { title: 'overview.accountTitle', desc: 'overview.accountDesc' }
      ];
      cards.forEach(function(card, i) {
        if (!cardKeys[i]) return;
        var h3 = card.querySelector('h3');
        var p = card.querySelector('p');
        if (h3) { var v = t(cardKeys[i].title); if (v !== cardKeys[i].title) h3.textContent = v; }
        if (p) { var v2 = t(cardKeys[i].desc); if (v2 !== cardKeys[i].desc) p.textContent = v2; }
      });

      // Overview rail
      var railH3 = document.querySelector('.portal-rail-card h3');
      if (railH3) { var vn = t('overview.navigation'); if (vn !== 'overview.navigation') railH3.textContent = vn; }
      var railGuide = document.querySelector('.portal-rail-help');
      if (railGuide) { var vg = t('overview.gettingStarted'); if (vg !== 'overview.gettingStarted') railGuide.textContent = vg; }
    }

    // Translate devices page section headers
    if (path === '/app/devices.html') {
      var sections = document.querySelectorAll('.portal-section-header');
      var devSections = [
        { h2: 'devices.activatedDevices', span: 'devices.activatedDesc' },
        { h2: 'devices.pairings', span: 'devices.pairingsDesc' }
      ];
      sections.forEach(function(sec, i) {
        if (!devSections[i]) return;
        var h2 = sec.querySelector('h2');
        var span = sec.querySelector('span');
        if (h2) { var v = t(devSections[i].h2); if (v !== devSections[i].h2) h2.textContent = v; }
        if (span) { var v2 = t(devSections[i].span); if (v2 !== devSections[i].span) span.textContent = v2; }
      });
    }
  }

  function highlightNav() {
    var path = window.location.pathname;
    // Check site-header-nav links (top nav)
    var links = document.querySelectorAll('.site-header-nav a');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href');
      if (href === path || href === path.replace('.html', '') ||
          (href === '/app/' && (path === '/app/' || path === '/app/index.html'))) {
        links[i].classList.add('active');
      }
    }
    // Also check legacy sidebar links if present
    var sidebarLinks = document.querySelectorAll('.sidebar-link');
    for (var j = 0; j < sidebarLinks.length; j++) {
      var sHref = sidebarLinks[j].getAttribute('href');
      if (path === sHref || (sHref !== '/app/' && path.indexOf(sHref.replace('.html', '')) === 0)) {
        sidebarLinks[j].classList.add('active');
      }
    }
    if (path === '/app/' || path === '/app/index.html') {
      var overview = document.querySelector('[data-section="overview"]');
      if (overview) overview.classList.add('active');
    }
  }

  TorveAPI.onAuthChange(function(state) {
    if (!state.isAuthenticated) {
      window.location.replace('/signin.html');
    }
  });
})();
