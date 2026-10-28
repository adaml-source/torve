'use strict';
const assert = require('node:assert/strict');
const { destination } = require('../web/assets/app/auth-return.js');
const origin = 'https://torve.app';
assert.equal(destination('/app/help.html#article:audio-language', origin), '/app/help.html#article:audio-language');
assert.equal(destination('/documentation.html?platform=tvos#cat:streaming', origin), '/app/help.html?platform=tvos#cat:streaming');
for (const input of [null, '', '//outside.invalid', '/\\outside.invalid', 'https://outside.invalid/', 'javascript:alert(1)', '/account.html', '/app/help.html/extra', '/app/../signin.html', '/%2f%2foutside.invalid', '/app/help.html\n']) {
  assert.equal(destination(input, origin), null, `Rejected unsafe or unsupported redirect: ${input}`);
}
console.log('Guide return links: valid deep links preserved; external and unsupported redirects rejected.');
