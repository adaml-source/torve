(() => {
  'use strict';
  const hero = document.querySelector('.film-hero');
  if (!hero) return;
  const slides = [...hero.querySelectorAll('.hero-film')];
  const choices = [...hero.querySelectorAll('[data-hero-slide]')];
  const pause = hero.querySelector('[data-hero-pause]');
  const title = hero.querySelector('[data-hero-title]');
  const credit = hero.querySelector('[data-hero-credit]');
  const count = hero.querySelector('[data-hero-count]');
  const status = hero.querySelector('[data-hero-status]');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let active = 0;
  let requested = 0;
  let requestVersion = 0;
  let paused = reduced.matches;
  let visible = true;
  let hovering = false;
  let focused = false;
  let timer;

  function schedule() {
    clearTimeout(timer);
    const playing = !paused && !reduced.matches && visible && !document.hidden && !hovering && !focused;
    hero.classList.toggle('hero-playing', playing);
    if (playing) timer = setTimeout(() => select((active + 1) % slides.length, false), 8500);
  }
  function updatePause() {
    pause.hidden = reduced.matches;
    pause.setAttribute('aria-pressed', String(paused));
    pause.setAttribute('aria-label', paused ? 'Play film carousel' : 'Pause film carousel');
    pause.textContent = paused ? '▷' : 'Ⅱ';
    schedule();
  }
  async function select(index, manual = true) {
    requested = index;
    const version = ++requestVersion;
    clearTimeout(timer);
    const next = slides[index];
    const image = next.querySelector('img');
    if (image.dataset.src) image.src = image.dataset.src;
    try { await image.decode(); } catch {
      // Keep the current frame and its matching credit if a file cannot load.
      if (version === requestVersion) {
        status.textContent = 'This film image could not load. Choose another film.';
        paused = true;
        updatePause();
      }
      return;
    }
    if (version !== requestVersion) return;
    active = index;
    slides.forEach((slide, i) => slide.classList.toggle('is-active', i === active));
    choices.forEach((button, i) => button.setAttribute('aria-pressed', String(i === active)));
    title.textContent = next.dataset.title + ' · ' + next.dataset.year;
    credit.textContent = 'Blender Foundation · ' + next.dataset.license + ' ↗';
    credit.href = '/credits.html#' + next.dataset.film;
    count.textContent = String(active + 1).padStart(2, '0') + ' / ' + String(slides.length).padStart(2, '0');
    // Only announce changes requested by the reader; autoplay is decorative.
    status.textContent = manual ? next.dataset.title + ', image ' + (active + 1) + ' of ' + slides.length : '';
    schedule();
  }
  choices.forEach((button, i) => button.addEventListener('click', () => select(i)));
  hero.querySelector('[data-hero-previous]').addEventListener('click', () => select((requested + slides.length - 1) % slides.length));
  hero.querySelector('[data-hero-next]').addEventListener('click', () => select((requested + 1) % slides.length));
  pause.addEventListener('click', () => { paused = !paused; updatePause(); });
  hero.addEventListener('pointerenter', event => { if (event.pointerType === 'mouse') { hovering = true; schedule(); } });
  hero.addEventListener('pointerleave', () => { hovering = false; schedule(); });
  hero.addEventListener('focusin', () => { focused = true; schedule(); });
  hero.addEventListener('focusout', event => { if (!hero.contains(event.relatedTarget)) { focused = false; schedule(); } });
  document.addEventListener('visibilitychange', schedule);
  new IntersectionObserver(entries => { visible = entries[0].isIntersecting; schedule(); }, {threshold:0.1}).observe(hero);
  reduced.addEventListener('change', () => { paused = reduced.matches; updatePause(); });
  updatePause();
})();
