const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('C:/Users/Anwender/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');

const root = path.resolve(__dirname, '../..');
const out = path.join(root, 'output/generic-extensions-2026-10-16');
fs.mkdirSync(out, { recursive: true });
const html = fs.readFileSync(path.join(root, 'web/app/extensions.html'), 'utf8')
  .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').replace(/<link\b[^>]*>/gi, '');
const script = fs.readFileSync(path.join(root, 'web/assets/app/page-extensions.js'), 'utf8');
const checks = [];
function check(name, condition) { assert.ok(condition, name); checks.push({ name, passed: true }); }

(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.hostname !== 'provider.example') return route.abort();
      let body;
      if (url.pathname.startsWith('/invalid/')) body = '<html>Not JSON</html>';
      else if (url.pathname.startsWith('/code/')) body = JSON.stringify({ id: 'code', name: 'Executable', version: '1', resources: ['stream'], entryPointV4: 'https://provider.example/code.js' });
      else body = JSON.stringify({
        id: 'org.independent.test', name: 'Independent playback', version: '1.0.0', resources: url.pathname.startsWith('/setup/') ? [] : ['stream'],
        builtIn: true, origin: 'built-in', description: 'Fixture provider; no real service account.',
        behaviorHints: { configurable: true, configurationRequired: url.pathname.startsWith('/setup/') },
        configurationUrl: 'https://provider.example/configure'
      });
      await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body });
    });
    await page.setContent(html);
    await page.addStyleTag({ content: ':root{--bg-card:#171c24;--bg-surface:#10151c;--border:#303743;--radius:14px;--text:#f3f5f7;--text-muted:#aeb8c6;--accent:#efc465;color-scheme:dark;font-family:system-ui}body{background:#0c1016;color:var(--text);margin:0}#loading,#verifyBanner,.site-header,.account-subnav{display:none}.portal-page{max-width:1200px;margin:40px auto;padding:20px}.portal-2col{display:grid;grid-template-columns:minmax(0,1fr) 260px;gap:32px}.portal-page-intro{color:var(--text-muted)}.portal-rail{padding-top:90px}.portal-rail-card{border:1px solid var(--border);border-radius:14px;padding:20px;margin-bottom:20px}.portal-rail-nav{list-style:none;padding:0;line-height:2}a{color:var(--accent)}@media(max-width:700px){.portal-2col{display:block}.portal-rail{padding-top:20px}.portal-page{margin:0}}' });
    await page.evaluate(() => {
      document.getElementById('appShell').style.display = 'block';
      window.__calls = []; window.__posts = [];
      window.__addons = [{ id: 'fixture-1', name: 'Independent playback', addon_id: 'org.independent.test',
        manifest_url: 'https://provider.example/u/config/manifest.json', installed_from: 'app', built_in: true,
        has_streams: true, has_catalog: false, is_enabled: true, sort_order: 0, version: '1.0.0' }];
      const strings = { 'extensions.title': 'Extensions', 'extensions.intro': 'Manage your installed extensions. Extensions sync across signed-in devices.',
        'extensions.count': '{0} external extension(s), {1} playback-enabled', 'extensions.installedTitle': 'Installed extensions',
        'extensions.installedDesc': 'Extensions you have added to your account.', 'extensions.addTitle': 'Add extension', 'extensions.addBtn': 'Add',
        'extensions.addPlaceholder': 'https://provider.example/manifest.json', 'extensions.aboutTitle': 'About extensions',
        'extensions.railInstalled': 'Installed external', 'extensions.railStreamCapable': 'Playback-capable',
        'extensions.railAddExtension': 'Add extension manually', 'extensions.railWhatAre': 'What are extensions?',
        'extensions.railCatalogVsStream': 'Catalogue and playback', 'extensions.railNoStreams': 'No playback sources?',
        'extensions.stream': 'Playback', 'extensions.disable': 'Disable', 'extensions.enable': 'Enable', 'extensions.remove': 'Remove',
        'extensions.learnMore': 'Learn more about extensions', 'extensions.adding': 'Installing…' };
      window.TorveI18n = { t: key => strings[key] || key };
      window.TorveAPI = {
        get: async endpoint => { window.__calls.push(endpoint); if (endpoint === '/me/addons') return window.__addons; throw new Error('Unexpected endpoint'); },
        post: async (endpoint, body) => {
          window.__posts.push({ endpoint, body });
          if (endpoint !== '/me/addons') throw new Error('Unexpected write');
          const addon = { ...body, id: 'fixture-2', is_enabled: true, sort_order: 1 };
          window.__addons.push(addon); return addon;
        }
      };
    });
    await page.addScriptTag({ content: script });
    await page.locator('.ext-row').waitFor();
    check('Generic page has no provider recommendation or branded setup URL', !/Panda|panda\.torve\.app/.test(await page.locator('body').innerText()));
    check('Included app catalogue is explicitly Built-in', await page.locator('section[aria-label="Included with Torve"] .origin-badge').textContent() === 'Built-in');
    check('Imported app-origin add-on remains External despite built-in metadata claim', await page.locator('.ext-row .origin-badge').textContent() === 'External');
    check('Loading extensions does not request a specific provider setup token', JSON.stringify(await page.evaluate(() => window.__calls)) === '["/me/addons"]');
    await page.getByText('How extensions work', { exact: true }).click();
    check('Generic instructions explain manifest JSON and provider configuration', (await page.locator('#extensionHelp').innerText()).includes('Opening it in a browser displays JSON'));
    check('Generic instructions do not recommend an extension directory',
      await page.locator('#extensionHelp a').count() === 0 &&
      !/stremio|community directory|directory listing/i.test(await page.locator('#extensionHelp').innerText()));
    await page.locator('[data-action="details"]').click();
    await page.locator('#extensionConfigureLink:not([hidden])').waitFor();
    check('Any installed provider can expose configuration without a branded route', await page.locator('#extensionConfigureLink').getAttribute('href') === 'https://provider.example/configure');
    check('Configuration host is visible before following its link', (await page.locator('#extensionDetailsStatus').innerText()).includes('provider.example'));
    await page.screenshot({ path: path.join(out, 'web-provider-details.png'), fullPage: true });
    await page.getByRole('button', { name: 'Done', exact: true }).click();
    for (const [name, url] of [
      ['Invalid JSON is not installed', 'https://provider.example/invalid/manifest.json'],
      ['Executable plug-in is not installed', 'https://provider.example/code/manifest.json'],
      ['Setup-only placeholder is not installed', 'https://provider.example/setup/manifest.json'],
      ['Public cleartext manifest is not installed', 'http://provider.example/manifest.json'],
      ['A URL without manifest.json is not installed', 'https://provider.example/sources.json']
    ]) {
      await page.locator('#manifestInput').fill(url);
      await page.locator('#addBtn').click();
      await page.locator('#addStatus.error').waitFor();
      check(name, (await page.evaluate(() => window.__posts)).length === 0);
    }
    await page.locator('#manifestInput').fill('stremio://provider.example/u/another-config/manifest.json');
    await page.locator('#addBtn').click();
    await page.locator('#addStatus.success').waitFor();
    check('Configured independent extension installs through ordinary account endpoint', (await page.evaluate(() => window.__posts))[0].body.manifest_url === 'https://provider.example/u/another-config/manifest.json');
    check('All imported rows retain External origin', (await page.locator('.ext-row .origin-badge').allTextContents()).every(label => label === 'External'));
    await page.locator('#manifestInput').fill('');
    await page.screenshot({ path: path.join(out, 'web-generic-extensions-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    check('Mobile layout fits without horizontal page overflow', await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(out, 'web-generic-extensions-mobile.png'), fullPage: true });
    check('No browser script errors', errors.length === 0);
    fs.writeFileSync(path.join(out, 'web-checks.json'), JSON.stringify({ passed: true, fixture: 'Local HTML and client script; isolated account and provider mocks; no live writes', checks }, null, 2));
    console.log(JSON.stringify({ passed: true, checks: checks.length, output: out }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
