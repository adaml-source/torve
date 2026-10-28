(() => {
  'use strict';
  const reducedMotion = matchMedia('(prefers-reduced-motion:reduce)');
  const downloadTabs = document.querySelector('.download-tabs');
  const downloadGrid = document.querySelector('.downloads-interactive');
  if (downloadTabs && downloadGrid) {
    document.body.classList.add('has-device-atlas');
    const buttons = [...downloadTabs.querySelectorAll('[role="tab"]')];
    const cards = buttons.map(button => document.getElementById(button.getAttribute('aria-controls')));
    const labels = buttons.map(button => button.textContent.trim());
    const atlas = document.createElement('div'); atlas.className = 'download-atlas';
    downloadTabs.before(atlas);
    const main = document.createElement('div'); main.className = 'atlas-main';
    const figure = document.createElement('figure'); figure.className = 'atlas-art';
    const selector = document.createElement('div'); selector.className = 'atlas-mobile-selector';
    const label = document.createElement('label'); label.htmlFor = 'atlas-device-select'; label.textContent = 'Your device';
    const select = document.createElement('select'); select.id = 'atlas-device-select';
    labels.forEach((text, index) => { const option = document.createElement('option'); option.value = index; option.textContent = text; select.append(option); });
    selector.append(label, select);
    main.append(selector, figure, downloadGrid); atlas.append(downloadTabs, main);
    buttons.forEach((button, index) => {
      button.setAttribute('aria-label', labels[index]);
      const number = document.createElement('span'); number.className = 'atlas-tab-index'; number.setAttribute('aria-hidden', 'true'); number.textContent = String(index + 1).padStart(2, '0');
      const text = document.createElement('span'); text.textContent = labels[index];
      button.replaceChildren(number, text);
    });
    const logo = '<image href="/assets/torve-logo-transparent.png" x="248" y="105" width="300" height="150" opacity=".95" />';
    function television(remote) {
      return '<g class="atlas-device"><path d="M160 63L650 90 647 367 158 340Z" fill="url(#atlas-surface)" stroke="url(#atlas-edge)" stroke-width="2.6"/><path d="M176 82L630 105 628 347 176 323Z" fill="#090a0b" stroke="#292822"/>' + logo + '<path d="M401 352v45M301 413l212 11" stroke="url(#atlas-edge)" stroke-width="4" stroke-linecap="round"/>' + (remote ? '<g transform="translate(640,252) rotate(17)"><rect x="0" y="0" width="39" height="173" rx="18" fill="url(#atlas-surface)" stroke="url(#atlas-edge)"/><circle cx="19" cy="35" r="11" fill="none" stroke="#d6b672"/><circle cx="19" cy="35" r="4" fill="#d6b672"/><path d="M12 78h14M12 99h14M12 120h14" stroke="#8b8575" stroke-width="2.6"/></g>' : '') + '</g>';
    }
    function phone(tablet) {
      const background = tablet ? '<g transform="rotate(-9 370 240)"><rect x="206" y="30" width="340" height="413" rx="24" fill="url(#atlas-surface)" stroke="url(#atlas-edge)" stroke-width="2.6"/><rect x="221" y="45" width="310" height="381" rx="12" fill="#090a0b"/>'+logo+'</g>' : '';
      return background + '<g class="atlas-device" transform="rotate(9 470 260)"><rect x="345" y="36" width="192" height="414" rx="35" fill="url(#atlas-surface)" stroke="url(#atlas-edge)" stroke-width="2.6"/><rect x="354" y="46" width="174" height="393" rx="29" fill="#090a0b"/><rect x="416" y="58" width="52" height="8" rx="4" fill="#302d25"/><image href="/assets/torve-logo-transparent.png" x="350" y="168" width="183" height="100"/><path d="M411 423h61" stroke="#8b8575" stroke-width="3" stroke-linecap="round"/></g>';
    }
    function laptop() {
      return '<g class="atlas-device"><path d="M218 75H605L626 341H196Z" fill="url(#atlas-surface)" stroke="url(#atlas-edge)" stroke-width="2.6"/><path d="M233 92H590L607 323H216Z" fill="#090a0b"/>'+logo+'<path d="M196 341H626L694 397H127Z" fill="url(#atlas-surface)" stroke="url(#atlas-edge)" stroke-width="2.6"/><path d="M127 397L143 412H678L694 397M349 353H480L493 376H334Z" fill="none" stroke="#7b7057"/></g>';
    }
    function source() {
      return '<g class="atlas-device"><path d="M246 119l-92 123 92 123M556 119l92 123-92 123M454 88L342 399" fill="none" stroke="url(#atlas-edge)" stroke-width="8" stroke-linecap="round"/><path d="M198 416H605" stroke="#8b8575" stroke-width="1"/><text x="400" y="464" text-anchor="middle" fill="#d6b672" font-family="system-ui" font-size="13" letter-spacing="5">AGPL-3.0-OR-LATER</text></g>';
    }
    function render() {
      let index = buttons.findIndex(button => button.getAttribute('aria-selected') === 'true');
      if (index < 0) index = 2;
      select.value = index;
      const name = labels[index];
      const text = cards[index].textContent.toLowerCase();
      const status = /preparation/.test(text) ? 'In preparation' : /controlled testing/.test(text) ? 'Controlled testing' : /source code/i.test(name) ? 'Open source' : 'Available to download';
      const kind = /source code/i.test(name) ? source() : /desktop/i.test(name) ? laptop() : /iphone/i.test(name) ? phone(true) : /phone/i.test(name) ? phone(false) : television(true);
      figure.innerHTML = '<svg viewBox="0 0 800 520" aria-hidden="true"><defs><linearGradient id="atlas-surface" x1="0" y1="0" x2=".9" y2="1"><stop stop-color="#514b3d"/><stop offset=".28" stop-color="#151615"/><stop offset="1" stop-color="#0a0b0c"/></linearGradient><linearGradient id="atlas-edge" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#f0e0ba"/><stop offset=".36" stop-color="#b4a27e"/><stop offset=".65" stop-color="#665d49"/><stop offset="1" stop-color="#d6b672"/></linearGradient></defs><ellipse class="atlas-orbit" cx="404" cy="258" rx="336" ry="176" transform="rotate(-25 404 258)"/><ellipse class="atlas-orbit" cx="404" cy="258" rx="360" ry="214" transform="rotate(15 404 258)"/>'+kind+'</svg><figcaption>'+name+' · Torve device illustration</figcaption>';
      let state = cards[index].querySelector('.atlas-state');
      if (!state) { state = document.createElement('span'); state.className = 'atlas-state'; cards[index].prepend(state); }
      state.textContent = status;
    }
    downloadTabs.addEventListener('click', render);
    downloadTabs.addEventListener('keydown', event => { if (['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) render(); });
    select.addEventListener('change', () => buttons[Number(select.value)].click());
    figure.addEventListener('pointermove', event => {
      if (reducedMotion.matches || event.pointerType === 'touch') return;
      const rect = figure.getBoundingClientRect();
      const x = (event.clientX - rect.left) / rect.width;
      const y = (event.clientY - rect.top) / rect.height;
      figure.style.setProperty('--tilt-x', (0.5 - y) * 7 + 'deg');
      figure.style.setProperty('--tilt-y', (x - 0.5) * 9 + 'deg');
      figure.style.setProperty('--pointer-x', x * 100 + '%');
      figure.style.setProperty('--pointer-y', y * 100 + '%');
    });
    figure.addEventListener('pointerleave', () => { figure.style.setProperty('--tilt-x','0deg'); figure.style.setProperty('--tilt-y','0deg'); figure.style.setProperty('--pointer-x','55%'); figure.style.setProperty('--pointer-y','45%'); });
    render();
  }
})();
