/* Reproducible website-only previews, modelled on the supplied Android screenshots.
 * Capture at 540 × 1090 (phone) / 1440 × 810 (TV). Film art: /credits.html.
 * No network APIs, real accounts, invented ratings, or playback availability claims. */
(() => {
  const path = '/web/assets/open-films/';
  const films = {
    charge: {title:'Charge',year:2022,genre:'Science fiction',poster:'charge-poster.jpg',scene:'charge-scene.png',description:'In a world run by machines, an old man takes on a fully automated factory.'},
    spring: {title:'Spring',year:2019,genre:'Fantasy · Animation',poster:'spring-poster.jpg',scene:'spring-scene.png',description:'A shepherd girl and her dog face ancient spirits in a journey through the cycle of life.'},
    sprite: {title:'Sprite Fright',year:2021,genre:'Horror · Animation',poster:'sprite-fright-poster.jpg',scene:'sprite-fright-scene.jpg',description:'A trip into the forest takes an unexpected turn when a group of teenagers meets its tiny inhabitants.'},
    tears: {title:'Tears of Steel',year:2012,genre:'Science fiction',poster:'tears-of-steel-poster.jpg',scene:'tears-of-steel-scene.jpg',description:'A group of warriors and scientists gathers in Amsterdam to save the world from destructive robots.'},
    sintel: {title:'Sintel',year:2010,genre:'Fantasy · Animation',poster:'sintel-poster.jpg'},
    wing: {title:'Wing It!',year:2023,genre:'Comedy · Animation',poster:'wing-it-poster.png'}
  };
  const params = new URLSearchParams(location.search);
  const device = params.get('device') === 'tv' ? 'tv' : 'phone';
  const mode = ['discover','watchlist','search','tv'].includes(params.get('screen')) ? params.get('screen') : 'discover';
  const featured = films[({discover:'charge',watchlist:'spring',search:'tears',tv:'sprite'})[mode]];
  document.body.className = device + ' mode-' + mode;
  const icons = {
    home:'<path d="m3 10 9-8 9 8v11h-6v-7H9v7H3z"/>',
    movie:'<path d="M3 8h18v13H3zM3 3h18v5H3zM7 3l3 5m4-5 3 5"/>',
    tv:'<rect x="2" y="4" width="20" height="14" rx="1"/><path d="M8 22h8m-4-4v4"/>',
    channels:'<rect x="2" y="7" width="20" height="15" rx="1"/><path d="m7 1 5 6 5-6m-8 11 7 3-7 4z"/>',
    search:'<circle cx="10" cy="10" r="7"/><path d="m15 15 7 7"/>',
    save:'<path d="M5 3h14v19l-7-5-7 5z"/>',
    settings:'<path d="M9 2h6l1 4 4 1 2 5-3 3v5l-5 2-3-3-5 1-3-5 2-4-2-5 5-3z"/><circle cx="12" cy="12" r="3"/>',
    play:'<path d="m7 3 14 9-14 9z"/>',
    check:'<path d="m4 12 5 5L21 5"/>'
  };
  const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name]}</svg>`;
  const navigation = [['home','Home'],['movie','Movies'],['tv','TV Shows'],['channels','Channels'],[device === 'tv' ? 'search':'save',device === 'tv' ? 'Search':'Watchlist'],['settings','Settings']];
  const nav = `<nav class="app-nav">${device === 'tv' ? '<img class="app-logo" src="/web/assets/torve-logo-transparent.png" alt="Torve">':''}${navigation.map(([key,label])=>`<div class="nav-item ${((mode === 'watchlist' && key === 'save') || (mode === 'search' && key === 'search') || (mode === 'discover' && key === 'home') || (mode === 'tv' && key === 'movie')) ? 'selected':''}">${icon(key)}<span>${label}</span></div>`).join('')}</nav>`;
  function posters(ids, saved = false) {
    return `<div class="posters">${ids.map(id=>{const f=films[id];return `<figure class="poster"><div class="poster-art"><img class="${id === 'wing'?'wing':''}" src="${path+f.poster}" alt="${f.title} official poster">${saved?`<span class="saved">${icon('check')}</span>`:''}</div><figcaption>${f.title}<small>${f.year}</small></figcaption></figure>`;}).join('')}</div>`;
  }
  const rowHeading = title => `<div class="row-heading"><h2>${title}</h2><span>See all →</span></div>`;
  const buttons = `<div class="app-actions"><span class="primary">${icon('play')} Details</span><span>${icon('save')} Watchlist</span></div>`;
  const heading = `<h1>${featured.title}</h1><p class="metadata">${featured.year} <b>·</b> ${featured.genre}</p><p class="synopsis">${featured.description}</p>`;
  let content;
  if (device === 'tv') {
    const menu = mode === 'watchlist' ? 'MY WATCHLIST' : mode === 'search' ? 'SEARCH' : mode === 'tv' ? 'MOVIES' : 'HOME';
    content = `${nav}<div class="tv-backdrop" style="background-image:url('${path+featured.scene}')"></div><aside class="title-sidebar"><p class="section-label">${menu}</p><h1>${featured.title}</h1><p class="metadata">${featured.year}</p><p class="synopsis">${featured.description}</p><div class="genre-tag">${featured.genre}</div><p class="collection-label">Your next movie night</p></aside><section class="tv-content">${mode === 'search'?`<div class="search-field">${icon('search')} Robots, future cities and adventure <span>AI search</span></div>`:`<p class="section-label">${menu}</p>`}${heading}${buttons}${rowHeading(mode==='watchlist'?'My Watchlist':mode==='search'?'Explore these titles':'Movies for your watchlist')}${posters(mode === 'search'?['tears','charge','sintel','wing','spring']:mode==='watchlist'?['spring','sintel','wing','sprite','charge']:['charge','spring','sprite','tears','sintel'],mode === 'watchlist')}</section>`;
  } else if (mode === 'watchlist' || mode === 'search') {
    content = `<header class="phone-header"><img src="/web/assets/torve-logo-transparent.png" alt="Torve">${icon('settings')}</header><section class="phone-collection"><h1>${mode === 'watchlist'?'My Watchlist':'Search'}</h1>${mode === 'search'?`<div class="search-field">${icon('search')} A magical adventure</div><div class="filter-chips"><span class="active">✧ AI search</span><span>Movies</span></div><p class="search-hint">Find a story for your mood.</p>`:`<div class="filter-chips"><span class="active">All</span><span>Movies</span><span>TV Shows</span></div>`}${posters(mode==='search'?['spring','sintel','wing','sprite']:['spring','charge','tears','sprite','sintel','wing'],mode==='watchlist')}</section>${nav}`;
  } else {
    content = `<div class="phone-backdrop" style="background-image:url('${path+featured.poster}')"></div><section class="phone-feature">${heading}${buttons}<div class="app-dots"><b></b><i></i><i></i><i></i></div></section><section class="phone-rows">${rowHeading('Continue Watching')}<div class="continue-row"><figure><img src="${path}spring-scene.png" alt="Spring"><span class="play-badge">${icon('play')}</span><div class="progress"></div><figcaption>Spring<small>Resume watching</small></figcaption></figure><figure><img src="${path}tears-of-steel-scene.jpg" alt="Tears of Steel"><span class="play-badge">${icon('play')}</span><div class="progress second"></div><figcaption>Tears of Steel<small>Resume watching</small></figcaption></figure></div>${rowHeading('My Watchlist')}${posters(['spring','wing','sintel','tears'])}</section>${nav}`;
  }
  document.getElementById('screen').innerHTML = content;
})();
