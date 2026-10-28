(function () {
  fetch('/downloads/releases.json', { cache: 'no-store' })
    .then(function (response) {
      if (!response.ok) throw new Error('manifest');
      return response.json();
    })
    .then(function (manifest) {
      var stable = ((manifest.channels || {}).stable || {});
      updateWindows(stable.windows || null);
      updateFireTv(stable.fire_tv || stable.android_tv || null);
      updateAndroid(stable.android_mobile || null, 'androidMobile');
      updateAndroid(stable.android_tv || null, 'googleTv');
      updateAndroid(stable.amazon_mobile || null, 'amazonMobile');
    })
    .catch(function () {});

  function updateWindows(entry) {
    if (!isAvailable(entry)) return;
    setLink('windowsDownloadBtn', entry.url, 'Download Windows');
    setLink('windowsShaBtn', entry.sha256_url, 'SHA-256');
    setCopy('windowsDownloadCopy', 'Windows desktop build ' + entry.version + ' is available. macOS and Linux packages are not publicly available yet.');
  }

  function updateFireTv(entry) {
    if (!isAvailable(entry)) return;
    setLink('fireTvDownloadBtn', entry.url, 'Download Fire TV APK');
    setLink('fireTvShaBtn', entry.sha256_url);
    setCopy('fireTvDownloadCopy', 'Fire TV uses the direct APK sideload path. This is the ' + entry.version + ' TV build listed in the public release manifest.');
    setCopy('fireTvReleaseVersion', 'Official Torve Fire TV release · ' + entry.version);
  }

  function updateAndroid(entry, key) {
    if (!isAvailable(entry)) return;
    setLink(key + 'DownloadBtn', entry.url);
    setLink(key + 'ShaBtn', entry.sha256_url);
    setCopy(key + 'DownloadCopy', 'Direct APK ' + entry.version + ' · build ' + entry.version_code + '.');
  }

  function isAvailable(entry) {
    return entry && entry.status === 'available' && entry.version && entry.url && entry.sha256_url;
  }

  function setLink(id, href, text) {
    var el = document.getElementById(id);
    if (!el) return;
    el.href = href;
    if (text) el.textContent = text;
  }

  function setCopy(id, text) {
    var el = document.getElementById(id);
    if (el) el.textContent = text;
  }
})();
