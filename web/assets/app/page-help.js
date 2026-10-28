/* Article data is fetched only from the cookie-authenticated guide endpoint. */
(function () {
  'use strict';
  const root = document.getElementById('helpContent');
  if (!root) return;
  let catalogue = null;
  let activeRequest = null;
  let platform = new URLSearchParams(location.search).get('platform') || 'all';
  let query = '';
  let results;
  let search;
  document.querySelector('.skip-link')?.addEventListener('click', event => {
    event.preventDefault();
    root.tabIndex = -1; root.focus();
  });
  const el = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
  };
  const link = (label, href, className) => {
    const node = el('a', label, className); node.href = href; return node;
  };
  const articleURL = id => '#article:' + encodeURIComponent(id);
  const categoryURL = id => '#cat:' + encodeURIComponent(id);
  const relevant = article => platform === 'all' || article.platforms.includes(platform);
  const platformNames = article => article.platforms.length === catalogue.platforms.length
    ? 'All apps · see platform notes' : catalogue.platforms.filter(p => article.platforms.includes(p.id)).map(p => p.label).join(' · ');
  function requireSignIn() {
    catalogue = null;
    root.replaceChildren(el('p', 'Sign in to read the guide.'));
    const next = '/app/help.html' + location.search + location.hash;
    location.replace('/signin.html?next=' + encodeURIComponent(next));
  }
  function showError() {
    catalogue = null;
    const box = el('section', undefined, 'guide-message');
    box.append(el('h1', 'The guide could not load'), el('p', 'Your connection or the guide service may be unavailable. Try again to check your session and reload it.'));
    const retry = el('button', 'Try again', 't-btn t-btn-primary'); retry.type = 'button';
    retry.addEventListener('click', () => load());
    box.append(retry, link('Back to account', '/account.html', 't-btn t-btn-secondary'));
    root.replaceChildren(box);
  }
  async function load(recheck = false) {
    activeRequest?.abort();
    const controller = new AbortController(); activeRequest = controller;
    if (!recheck) root.replaceChildren(el('p', 'Checking your sign-in…', 'guide-loading'));
    const deadline = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch('/web/auth/guide', {credentials: 'same-origin', cache: 'no-store', signal: controller.signal});
      if (response.status === 401) { requireSignIn(); return; }
      if (!response.ok) throw new Error('Guide unavailable');
      const data = await response.json();
      if (!Array.isArray(data.articles) || !Array.isArray(data.categories) || !Array.isArray(data.platforms)) throw new Error('Invalid guide');
      if (controller !== activeRequest) return;
      // Rechecking a session must not dismiss an open select or reset the
      // reader's focus, search, and scroll position when the edition is unchanged.
      if (recheck && catalogue?.revision === data.revision && results?.isConnected) return;
      catalogue = data;
      if (!catalogue.platforms.some(p => p.id === platform)) platform = 'all';
      renderShell();
    } catch {
      if (controller === activeRequest) showError();
    } finally { clearTimeout(deadline); }
  }
  function renderShell() {
    const intro = el('header', undefined, 'guide-intro');
    const eyebrow = el('div', 'ACCOUNT GUIDE', 'eyebrow');
    const top = el('div', undefined, 'guide-topline');
    top.append(eyebrow, link('Your account ↗', '/account.html', 'guide-account'));
    intro.append(top, el('h1', 'The Torve guide'), el('p', 'Find the right steps for your device, from first connection to the next episode.', 'guide-lead'));
    const reviewed = el('p', undefined, 'guide-reviewed');
    const date = new Date(catalogue.reviewedOn + 'T12:00:00Z');
    reviewed.append(el('span', 'Reviewed ' + new Intl.DateTimeFormat('en-GB', {day:'numeric', month:'long', year:'numeric', timeZone:'UTC'}).format(date)), el('span', catalogue.articles.length + ' topics · ' + catalogue.platforms.length + ' app families · English edition'));
    intro.append(reviewed);
    const tools = el('div', undefined, 'guide-tools');
    const searchLabel = el('label', 'Search the guide', 'guide-field');
    search = el('input'); search.type = 'search'; search.id = 'guideSearch'; search.placeholder = 'Audio languages, Panda, downloads…'; search.value = query;
    search.addEventListener('input', () => { query = search.value.trim(); renderRoute(); });
    searchLabel.append(search);
    const platformLabel = el('label', 'Your app', 'guide-field');
    const select = el('select'); select.id = 'guidePlatform';
    [{id:'all',label:'All apps'}, ...catalogue.platforms].forEach(p => { const option = el('option', p.label); option.value = p.id; select.append(option); });
    select.value = platform;
    select.addEventListener('change', () => {
      platform = select.value;
      const url = new URL(location.href);
      if (platform === 'all') url.searchParams.delete('platform'); else url.searchParams.set('platform', platform);
      url.hash = ''; history.replaceState(null, '', url.pathname + url.search);
      renderRoute();
    });
    platformLabel.append(select); tools.append(searchLabel, platformLabel);
    const scope = el('p', 'Features depend on your installed build. Platform notes distinguish implementation from released and device-tested availability.', 'guide-scope');
    results = el('div', undefined, 'guide-results'); results.id = 'guideResults';
    root.replaceChildren(intro, tools, scope, results);
    renderRoute();
  }
  function card(article) {
    const item = link('', articleURL(article.id), 'guide-article-link');
    item.append(el('span', article.title, 'guide-link-title'), el('span', article.summary, 'guide-link-summary'), el('span', platformNames(article), 'guide-platforms'));
    return item;
  }
  function breadcrumb(category, title) {
    const nav = el('nav', undefined, 'guide-breadcrumbs'); nav.setAttribute('aria-label', 'Guide breadcrumb');
    nav.append(link('Guide', '#'));
    if (category) nav.append(el('span', '/'), link(category.title, categoryURL(category.id)));
    if (title) { nav.append(el('span', '/')); const current = el('span', title); current.setAttribute('aria-current', 'page'); nav.append(current); }
    return nav;
  }
  function renderRoute() {
    if (!catalogue || !results) return;
    results.replaceChildren();
    root.dataset.view = 'home';
    if (query) {
      root.dataset.view = 'search';
      const words = query.toLocaleLowerCase().split(/\s+/);
      const matches = catalogue.articles.filter(relevant).filter(a => {
        const text = [a.title, a.summary, ...a.tags, ...a.sections.flatMap(s => [s.title, ...(s.paragraphs || []), ...(s.steps || []), ...(s.items || [])])].join(' ').toLocaleLowerCase();
        return words.every(word => text.includes(word));
      }).sort((a, b) => {
        const score = item => words.reduce((total, word) => total + (item.title.toLocaleLowerCase().includes(word) ? 4 : 0) + (item.summary.toLocaleLowerCase().includes(word) ? 2 : 0), 0);
        return score(b) - score(a);
      });
      const count = el('p', matches.length + (matches.length === 1 ? ' topic found' : ' topics found'), 'guide-result-count'); count.setAttribute('role', 'status');
      results.append(count);
      const list = el('div', undefined, 'guide-article-list'); matches.forEach(a => list.append(card(a))); results.append(list);
      if (!matches.length) results.append(el('p', 'Try a shorter phrase or choose All apps. You can also clear the search to browse every category.', 'guide-empty'));
      document.title = 'Search the guide · Torve'; return;
    }
    let hash;
    try { hash = decodeURIComponent(location.hash.slice(1)); } catch { hash = ''; }
    if (hash.startsWith('article:')) {
      const id = hash.slice(8), canonical = catalogue.aliases[id] || id;
      const article = catalogue.articles.find(a => a.id === canonical);
      if (article) { root.dataset.view = 'article'; renderArticle(article); return; }
    } else if (hash.startsWith('cat:')) {
      const id = hash.slice(4), canonical = catalogue.categoryAliases[id] || id;
      const category = catalogue.categories.find(c => c.id === canonical);
      if (category) {
        root.dataset.view = 'category';
        results.append(breadcrumb(null, category.title), el('h2', category.title), el('p', category.summary, 'guide-section-lead'));
        const list = el('div', undefined, 'guide-article-list');
        catalogue.articles.filter(a => a.category === canonical && relevant(a)).forEach(a => list.append(card(a)));
        results.append(list);
        if (!list.childElementCount) results.append(el('p', 'This category has no articles for the selected app. Choose All apps to view its platform-specific instructions.'));
        document.title = category.title + ' · Torve guide'; return;
      }
    }
    if (hash) results.append(el('p', 'That old guide link is no longer available. Find the current topic below.', 'guide-message'));
    renderHome();
  }
  function renderHome() {
    document.title = 'The Torve guide';
    const start = el('section', undefined, 'guide-start');
    start.append(el('h2', 'Start with your screen'), el('p', 'Setup, navigation, and current availability for each app.'));
    const apps = el('nav', undefined, 'guide-app-links'); apps.setAttribute('aria-label', 'App guides');
    catalogue.platforms.filter(p => platform === 'all' || platform === p.id).forEach(p => apps.append(link(p.label + ' ↗', articleURL(p.article), 't-btn t-btn-secondary')));
    start.append(apps); results.append(start, el('h2', 'Explore the guide'));
    const grid = el('div', undefined, 'guide-category-grid');
    catalogue.categories.forEach(c => {
      const count = catalogue.articles.filter(a => a.category === c.id && relevant(a)).length;
      if (!count) return;
      const item = link('', categoryURL(c.id), 'guide-category');
      item.append(el('span', count + ' topics', 'guide-count'), el('h3', c.title), el('p', c.summary), el('span', 'Explore ↗', 'guide-explore'));
      grid.append(item);
    });
    results.append(grid);
  }
  function renderArticle(article) {
    document.title = article.title + ' · Torve guide';
    const category = catalogue.categories.find(c => c.id === article.category);
    results.append(breadcrumb(category, article.title));
    const layout = el('div', undefined, 'guide-reading-layout');
    const body = el('article', undefined, 'guide-article');
    body.append(el('p', platformNames(article), 'guide-article-platforms'), el('h2', article.title), el('p', article.summary, 'guide-section-lead'));
    if (!relevant(article)) body.append(el('p', 'This topic is for another app. The platform labels above show where it applies.', 'guide-message'));
    const contents = el('nav', undefined, 'guide-contents'); contents.setAttribute('aria-label', 'In this article');
    contents.append(el('h3', 'In this article'));
    article.sections.forEach((section, index) => {
      const sectionNode = el('section'); sectionNode.id = 'guide-section-' + index;
      sectionNode.append(el('h3', section.title));
      (section.paragraphs || []).forEach(p => sectionNode.append(el('p', p)));
      if (section.steps || section.items) {
        const list = el(section.steps ? 'ol' : 'ul'); (section.steps || section.items).forEach(t => list.append(el('li', t))); sectionNode.append(list);
      }
      body.append(sectionNode);
      const jump = el('button', section.title); jump.type = 'button';
      jump.addEventListener('click', () => { sectionNode.scrollIntoView({block:'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'}); sectionNode.tabIndex = -1; sectionNode.focus({preventScroll:true}); });
      contents.append(jump);
    });
    const aside = el('aside'); aside.append(contents);
    layout.append(body, aside); results.append(layout);
    const related = el('section', undefined, 'guide-related'); related.append(el('h2', 'Keep exploring'));
    const list = el('div', undefined, 'guide-article-list');
    article.related.map(id => catalogue.articles.find(a => a.id === id)).filter(Boolean).forEach(a => list.append(card(a)));
    related.append(list); results.append(related);
  }
  window.addEventListener('hashchange', () => {
    query = ''; if (search) search.value = '';
    renderRoute();
    if (catalogue) { results.tabIndex = -1; results.focus({preventScroll:true}); results.scrollIntoView({block:'start'}); }
  });
  // Revalidate cached navigation and a return from another tab where the reader
  // may have signed out. Keep controls mounted during a normal focus change.
  window.addEventListener('pagehide', () => { activeRequest?.abort(); catalogue = null; root.replaceChildren(); });
  window.addEventListener('pageshow', event => { if (event.persisted) load(); });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') load(true);
  });
  load();
})();
