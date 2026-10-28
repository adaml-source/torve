(() => {
  'use strict';
  // The guide reads the stable release manifest; version-specific Downloader codes cannot be updated.
  const installUrl = 'https://torve.app/firetv';
  document.querySelectorAll('[data-copy-firetv-url]').forEach(button => button.addEventListener('click', async () => {
    const message = button.closest('[data-firetv-install-block]').querySelector('[data-code-feedback]');
    try { await navigator.clipboard.writeText(installUrl); message.textContent = 'Address copied'; }
    catch { message.textContent = 'Enter torve.app/firetv in Downloader.'; }
  }));
  const dialog = document.getElementById('download-dialog');
  if (!dialog || typeof dialog.showModal !== 'function') return;
  document.querySelectorAll('[data-open-download]').forEach(link => link.addEventListener('click', event => { event.preventDefault(); dialog.showModal(); }));
  dialog.querySelector('.dialog-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const box = dialog.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dialog.close();
  });
})();
