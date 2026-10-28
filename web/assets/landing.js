(() => {
  'use strict';
  const features = Object.fromEntries(['discover','watchlist','search','tv'].map(screen => [screen, {
    image:'/assets/product-previews/phone-' + screen + '.jpg?v=20261012-2',
    alt:'Recreated Torve phone ' + screen + ' preview with licensed film artwork',
    tvImage:'/assets/product-previews/tv-' + screen + '.jpg?v=20261012-2',
    tvAlt:'Recreated Torve TV ' + screen + ' preview with licensed film artwork'
  }]));
  const motion = matchMedia('(prefers-reduced-motion:reduce)');
  const story = document.querySelector('.product-story');
  const stage = document.querySelector('.media-stage');
  const group = document.querySelector('.feature-tabs');
  const featureButtons = [...group.querySelectorAll('[data-feature]')];
  const pause = document.querySelector('.preview-pause');
  let activeFeature = 'discover';
  let change = 0;
  let paused = motion.matches;
  let manualAt = null;
  let visible = false;
  let timer;
  let lastScrollIndex = -1;

  async function replaceScreens(feature) {
    const version = ++change;
    const screens = [
      { element:document.getElementById('feature-image'), src:feature.image, alt:feature.alt },
      { element:document.getElementById('feature-tv-image'), src:feature.tvImage, alt:feature.tvAlt }
    ];
    const ready = await Promise.all(screens.map(({src}) => new Promise(resolve => {
      const image = new Image();
      image.onload = () => resolve(true);
      image.onerror = () => resolve(false);
      image.src = src;
      if (image.complete) resolve(image.naturalWidth > 0);
    })));
    if (version !== change || ready.some(result => !result)) return;
    screens.forEach(({element}) => element.classList.add('is-changing'));
    setTimeout(() => {
      if (version !== change) return;
      screens.forEach(({element,src,alt}) => {
        element.src = src;
        element.alt = alt;
        element.classList.remove('is-changing');
      });
    }, motion.matches ? 0 : 160);
  }
  function activate(button, tabGroup) {
    tabGroup.querySelectorAll('[role="tab"]').forEach(tab => {
      const selected = tab === button;
      tab.setAttribute('aria-selected',String(selected));
      tab.tabIndex = selected ? 0 : -1;
      const panel = document.getElementById(tab.getAttribute('aria-controls'));
      if (panel) panel.hidden = !selected;
    });
    if (button.dataset.feature && activeFeature !== button.dataset.feature) {
      activeFeature = button.dataset.feature;
      replaceScreens(features[activeFeature]);
    }
  }
  function schedule() {
    clearInterval(timer);
    if (paused || motion.matches || document.hidden || !visible) return;
    timer = setInterval(() => {
      // Leave a user's selected panel in place while they interact with it.
      if (group.contains(document.activeElement) || stage.matches(':hover')) return;
      const index = featureButtons.findIndex(button => button.dataset.feature === activeFeature);
      activate(featureButtons[(index + 1) % featureButtons.length], group);
    }, 7500);
  }
  function updatePause() {
    pause.setAttribute('aria-pressed',String(paused));
    pause.setAttribute('aria-label',paused ? 'Play app screen previews' : 'Pause app screen previews');
    pause.textContent = paused ? 'Play previews ▷' : 'Pause previews Ⅱ';
    pause.hidden = motion.matches;
    schedule();
  }
  document.querySelectorAll('[role="tablist"]').forEach(tabGroup => {
    function select(button) {
      if (button.dataset.feature) manualAt = window.scrollY;
      activate(button, tabGroup);
      schedule();
    }
    tabGroup.addEventListener('click',event => {
      const button = event.target.closest('[role="tab"]');
      if (button && tabGroup.contains(button)) select(button);
    });
    tabGroup.addEventListener('keydown',event => {
      const buttons = [...tabGroup.querySelectorAll('[role="tab"]')];
      const current = buttons.indexOf(document.activeElement);
      if (current < 0) return;
      let next;
      if (event.key === 'ArrowRight') next = (current + 1) % buttons.length;
      else if (event.key === 'ArrowLeft') next = (current - 1 + buttons.length) % buttons.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = buttons.length - 1;
      else return;
      event.preventDefault();
      buttons[next].focus();
      select(buttons[next]);
    });
  });
  pause.addEventListener('click',() => { paused = !paused; updatePause(); });
  new IntersectionObserver(entries => {
    visible = entries[0].isIntersecting;
    schedule();
  }, {threshold:.35}).observe(stage);
  document.addEventListener('visibilitychange',schedule);
  updatePause();

  const hero = document.querySelector('.film-hero');
  const gallery = document.querySelector('.film-gallery');
  const clamp = value => Math.max(0,Math.min(1,value));
  let scrollFrame;
  function updateScroll() {
    scrollFrame = null;
    if (motion.matches) return;
    const heroBox = hero.getBoundingClientRect();
    hero.style.setProperty('--hero-shift',Math.min(65,Math.max(0,-heroBox.top * .13)) + 'px');
    const box = story.getBoundingClientRect();
    const progress = clamp(-box.top / Math.max(1,box.height-window.innerHeight));
    stage.style.setProperty('--tv-turn',(-7 + progress * 13) + 'deg');
    stage.style.setProperty('--tv-pitch',(2-progress * 4) + 'deg');
    stage.style.setProperty('--tv-lift',(-progress * 12) + 'px');
    stage.style.setProperty('--phone-lift',(progress * 16) + 'px');
    if (window.innerWidth > 780 && window.innerHeight > 720 && box.top < 80 && box.bottom > window.innerHeight && !paused) {
      if (manualAt !== null && Math.abs(window.scrollY-manualAt) > 220) manualAt = null;
      const index = Math.min(featureButtons.length-1,Math.floor(progress*featureButtons.length));
      if (manualAt === null && index !== lastScrollIndex) activate(featureButtons[index],group);
      lastScrollIndex = index;
    }
    if (gallery) {
      const galleryBox = gallery.getBoundingClientRect();
      const progress = clamp((window.innerHeight-galleryBox.top)/(window.innerHeight+galleryBox.height));
      gallery.style.setProperty('--film-turn',((progress-.5)*7) + 'deg');
    }
  }
  function queueScroll() { if (!scrollFrame) scrollFrame = requestAnimationFrame(updateScroll); }
  window.addEventListener('scroll',queueScroll,{passive:true});
  window.addEventListener('resize',queueScroll);
  motion.addEventListener('change',() => { paused = motion.matches; updatePause(); queueScroll(); });
  queueScroll();
  document.querySelectorAll('.answers-list details').forEach(item => {
    item.addEventListener('toggle',() => {
      if (item.open) document.querySelectorAll('.answers-list details').forEach(other => { if (other !== item) other.open = false; });
    });
  });
  if (['#apple','#free-software','#tv'].includes(location.hash)) {
    const platform = document.getElementById(location.hash === '#tv' ? 'device-tvos' : 'device-ios');
    activate(platform,platform.closest('[role="tablist"]'));
    document.getElementById('devices').scrollIntoView();
  }
})();
