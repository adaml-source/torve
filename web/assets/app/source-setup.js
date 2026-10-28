/* Direct, profile-scoped source editors. Provider credentials stay masked. */
var TorveSources = (function () {
  'use strict';
  var state = {}, errors = {}, generation = 0, busy = false, active = null, opener = null;
  var dialog = document.getElementById('sourceDialog');
  var labels = { none:'None', easynews:'Easynews', generic:'NNTP provider', torbox:'TorBox', premiumize:'Premiumize',
    alldebrid:'AllDebrid', nzbget:'NZBGet', sabnzbd:'SABnzbd', nzbgeek:'NZBgeek', scenenzbs:'SceneNZBs',
    dognzb:'DOGnzb', nzbplanet:'NZBPlanet', custom:'Custom Newznab' };
  var titles = { usenet:'Usenet', indexers:'NZB indexers', replays:'Sports replays', nzbdav:'NzbDAV playback' };
  function esc(v) { return String(v == null ? '' : v).replace(/[&<>"']/g,function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function profile() { return TorveProfiles.selectedId(); }
  function endpoint(key) { return key === 'nzbdav' ? '/integrations/nzbdav/status' : '/me/source-setup/' + key; }
  function load() {
    var ticket = ++generation, captured = profile(); state = {}; errors = {};
    return Promise.all(['usenet','replays','nzbdav'].map(function(key) {
      return TorveAPI.get(endpoint(key)).then(function(result) {
        if (ticket === generation && captured === profile()) state[key] = result;
      }).catch(function(error) { if (ticket === generation && captured === profile()) errors[key] = error.message || 'Could not load. Try again.'; });
    }));
  }
  function sourceState(key) { return state[key === 'indexers' ? 'usenet' : key]; }
  function status(key) {
    var saved = sourceState(key), values = saved && saved.values || {};
    if (errors[key === 'indexers' ? 'usenet' : key]) return 'Could not load';
    if (!saved) return 'Loading';
    if (key === 'usenet') return values.enableUsenet ? 'Enabled' : 'Off';
    if (key === 'indexers') return (values.nzbIndexers || []).length ? (values.nzbIndexers.length + ' saved') : 'Not set up';
    if (key === 'replays') return values.enabled ? 'Enabled' : 'Off';
    return saved.configured ? (saved.degraded ? 'Needs attention' : 'Saved') : 'Not set up';
  }
  function summary(key) {
    var saved = sourceState(key), v = saved && saved.values || {};
    if (key === 'usenet') {
      var parts = [];
      if (v.usenetProvider && v.usenetProvider !== 'none') parts.push(labels[v.usenetProvider] || v.usenetProvider);
      if (v.downloadClient && v.downloadClient !== 'none') parts.push((labels[v.downloadClient] || v.downloadClient) + ' download client');
      return parts.join(' · ') || 'Connect Easynews, an NNTP provider, or an NZB download client.';
    }
    if (key === 'indexers') return (v.nzbIndexers || []).map(function(r) { return labels[r.type] || r.type; }).join(' · ') || 'Add the Newznab indexers Torve searches for NZB releases.';
    if (key === 'replays') return (v.websites || []).length ? v.websites.length + ' website' + (v.websites.length === 1 ? '' : 's') + ' saved · ' + v.websites.filter(function(s) { return s.enabled !== false; }).length + ' active' : 'Add your replay websites to browse them in Sports in the app.';
    return 'Connect your NzbDAV server for prepared Usenet playback.';
  }
  function card(key) {
    var error = errors[key === 'indexers' ? 'usenet' : key], saved = sourceState(key);
    var enabled = status(key) === 'Enabled' || status(key) === 'Saved' || /saved$/.test(status(key));
    return '<article class="setup-card source-card"><div class="setup-card-header"><h3>' + titles[key] + '</h3><span class="status-badge '+(error?'status-attention':enabled?'status-connected':'status-default')+'">'+esc(status(key))+'</span></div>'+
      '<p>'+esc(error || summary(key))+'</p>' + (key === 'nzbdav' ? '<span class="profile-scope-label">Shared across this account</span>' : '') +
      '<div class="setup-card-actions"><button type="button" class="btn-sm '+(error?'btn-edit':'btn-setup')+'" '+(error?'data-source-retry':'data-source-edit="'+key+'"')+(!saved&&!error?' disabled':'')+'>'+(error?'Retry loading':key==='indexers'?'Manage indexers':key==='replays'?'Manage websites':'Configure')+'</button></div></article>';
  }
  function render(section) { return '<div class="setup-cards">'+card(section)+(section === 'usenet' ? card('nzbdav') : '')+'</div>'; }
  function notice(message, error) {
    var el = document.getElementById('setupNotice'); el.hidden = false; el.textContent = message;
    el.className = 'setup-notice' + (error ? ' is-error' : ''); el.setAttribute('role', error ? 'alert' : 'status');
  }
  function bind() {
    document.querySelectorAll('[data-source-edit]').forEach(function(btn) { btn.onclick=function() { open(btn.dataset.sourceEdit, btn); }; });
    document.querySelectorAll('[data-source-retry]').forEach(function(btn) { btn.onclick=function() {
      btn.disabled=true; btn.textContent='Loading…'; load().then(function() { TorveSetup.refreshView(); });
    }; });
  }
  function field(id,label,value,type,extra) {
    return '<div class="form-group"><label for="'+id+'">'+esc(label)+'</label><input id="'+id+'" type="'+(type||'text')+'" value="'+esc(value)+'" '+(extra||'')+'></div>';
  }
  function secret(id,label,saved) { return field(id,label,'','password','autocomplete="new-password" placeholder="'+(saved?'Saved — leave blank to keep':'Enter '+esc(label.toLowerCase()))+'"'); }
  function select(id,label,items,value) { return '<div class="form-group"><label for="'+id+'">'+esc(label)+'</label><select id="'+id+'">'+items.map(function(v) { return '<option value="'+v+'"'+(v===value?' selected':'')+'>'+esc(labels[v]||v)+'</option>'; }).join('')+'</select></div>'; }
  function check(id,label,value) { return '<label class="source-check"><input type="checkbox" id="'+id+'"'+(value?' checked':'')+'> '+esc(label)+'</label>'; }
  function val(id) { return document.getElementById(id).value.trim(); }
  function checked(id) { return document.getElementById(id).checked; }
  function showFields() {
    var provider = val('sourceProvider'), client = val('sourceClient');
    dialog.querySelector('[data-provider-fields]').hidden = provider === 'none';
    dialog.querySelector('[data-nntp-fields]').hidden = provider !== 'generic';
    dialog.querySelector('[data-client-key]').hidden = ['none','nzbget'].indexOf(client) !== -1;
    dialog.querySelector('[data-client-url]').hidden = ['nzbget','sabnzbd'].indexOf(client) === -1;
    dialog.querySelector('[data-client-login]').hidden = client !== 'nzbget';
    dialog.querySelector('[data-easynews-prefer]').hidden = provider !== 'easynews';
  }
  function usenetForm(v) {
    return check('sourceEnabled','Enable Usenet in this profile',v.enableUsenet)+
      '<fieldset><legend>Usenet provider</legend><p>Easynews searches directly. NNTP providers need indexers and a playback service configured separately.</p>'+
      select('sourceProvider','Provider',['none','easynews','generic'],v.usenetProvider||'none')+
      '<div data-provider-fields>'+field('sourceUsername','Usenet username',v.usenetUsername,'text','autocomplete="off"')+secret('sourcePassword','Usenet password',v.usenetPasswordSaved)+
      '<div data-nntp-fields>'+field('sourceHost','NNTP hostname',v.usenetHost,'text','placeholder="news.example.com"')+'<div class="source-field-grid">'+field('sourcePort','Port',v.usenetPort||563,'number','min="1" max="65535"')+field('sourceConnections','Connections',v.usenetConnections||10,'number','min="1" max="100"')+'</div>'+check('sourceSSL','Use SSL / TLS',v.usenetSSL!==false)+'</div></div></fieldset>'+
      '<fieldset><legend>NZB download client</legend><p>Choose the service that handles NZB releases from your indexers.</p>'+select('sourceClient','Download client',['none','torbox','premiumize','alldebrid','sabnzbd','nzbget'],v.downloadClient||'none')+
      '<div data-client-key>'+secret('sourceClientKey','Download client API key',v.downloadClientApiKeySaved)+'</div><div data-client-url>'+field('sourceClientUrl','Server URL',v.downloadClientUrl,'url','placeholder="https://downloads.example.com"')+'</div><div data-client-login>'+field('sourceClientUsername','Download client username',v.downloadClientUsername)+secret('sourceClientPassword','Download client password',v.downloadClientPasswordSaved)+'</div><div data-easynews-prefer>'+check('sourcePreferNzb','Prefer NZB results to reduce Easynews data usage',v.easynewsPreferNzb)+'</div></fieldset>'+
      '<p class="source-help">Add or change your NZB indexers in the separate NZB indexers section.</p>';
  }
  function indexerRow(row) {
    return '<fieldset class="source-list-row" data-indexer><legend>Indexer</legend>'+select('indexerType'+(++rowCount),'Indexer service',['nzbgeek','scenenzbs','dognzb','nzbplanet','custom'],row.type||'custom')+
      '<div data-indexer-url>'+field('indexerUrl'+rowCount,'Newznab URL',row.url,'url','placeholder="https://indexer.example.com"')+'</div>'+secret('indexerKey'+rowCount,'Indexer API key',row.keySaved)+
      '<button type="button" class="source-remove" data-remove-row>Remove indexer</button></fieldset>';
  }
  var rowCount = 0;
  function replayRow(row) {
    var id = ++rowCount;
    return '<fieldset class="source-list-row" data-replay data-id="'+esc(row.id||'')+'"><legend>Replay website</legend>'+field('replayName'+id,'Website name',row.name,'text','required maxlength="80" placeholder="My replay library"')+
      field('replayUrl'+id,'Website URL',row.url,'url','required placeholder="https://replays.example.com"')+check('replayEnabled'+id,'Include this website',row.enabled!==false)+
      '<button type="button" class="source-remove" data-remove-row>Remove website</button></fieldset>';
  }
  function listMessage() {
    var rows = dialog.querySelectorAll('.source-list-row'), empty=dialog.querySelector('[data-empty-list]'), add=dialog.querySelector('[data-add-row]');
    if(empty) empty.hidden=rows.length>0;
    if(add) add.disabled=rows.length>=(active && active.key==='indexers'?10:12);
    dialog.querySelectorAll('[data-indexer]').forEach(function(row) { row.querySelector('[data-indexer-url]').hidden=row.querySelector('select').value!=='custom'; });
  }
  function open(key, button) {
    var saved=sourceState(key); if(!saved || busy) return;
    active={key:key,profile:profile(),revision:saved.revision}; opener=button; rowCount=0;
    var v=saved.values||{}, body='';
    if(key==='usenet') body=usenetForm(v);
    if(key==='indexers') body='<p>Indexers find NZB releases. Configure the service that plays them under Usenet. Leave saved API key fields blank to keep them.</p><div data-source-rows>'+(v.nzbIndexers||[]).map(indexerRow).join('')+'</div><p data-empty-list>No indexers yet. Add an indexer to start.</p><button type="button" class="btn-sm btn-edit" data-add-row>Add indexer</button><p class="source-help">Up to 10 indexers. Removing an indexer keeps your other sources.</p>';
    if(key==='replays') body=check('replaysEnabled','Enable sports replays in this profile',v.enabled)+'<p>Add public replay listing pages you use. Browse and play supported results in the Torve app.</p><div data-source-rows>'+(v.websites||[]).map(replayRow).join('')+'</div><p data-empty-list>No replay websites yet.</p><button type="button" class="btn-sm btn-edit" data-add-row>Add website</button><p class="source-help">Up to 12 websites. Turning a website off keeps it saved.</p>';
    if(key==='nzbdav') body='<p>Use a server URL reachable by Torve and your NzbDAV API key. This playback connection is shared across your account.</p>'+field('nzbdavUrl','NzbDAV server URL','','url','required placeholder="https://nzbdav.example.com"')+secret('nzbdavKey','NzbDAV API key',false)+'<button type="button" class="btn-sm btn-edit" data-test-nzbdav>Test connection</button><p class="source-help">'+(saved.configured?'Enter the URL and key to replace the saved NzbDAV connection.':'')+'</p>';
    dialog.innerHTML='<form id="sourceForm"><header><div><p class="source-eyebrow">'+esc(key==='nzbdav'?'Account connection':TorveProfiles.selectedName())+'</p><h2 id="sourceDialogTitle">'+titles[key]+'</h2></div><button type="button" class="source-close" data-source-close aria-label="Close">×</button></header><div class="source-dialog-body">'+(saved.shared?'<p class="note">This connection is shared with another profile. Changes apply wherever it is used.</p>':'')+body+'</div><p class="source-form-status" data-source-status role="status" aria-live="polite"></p><footer><button type="button" class="btn-sm btn-edit" data-source-close>Cancel</button><button type="submit" class="btn-sm btn-setup">Save '+(key==='indexers'?'indexers':key==='replays'?'websites':'connection')+'</button></footer></form>';
    dialog.querySelector('form').onsubmit=submit;
    dialog.querySelectorAll('[data-source-close]').forEach(function(btn) { btn.onclick=function() { if(!busy) dialog.close(); }; });
    if(key==='usenet') { document.getElementById('sourceProvider').onchange=showFields; document.getElementById('sourceClient').onchange=showFields; showFields(); }
    dialog.onchange=listMessage;
    dialog.onclick=function(event) {
      if(event.target.closest('[data-remove-row]')) { event.target.closest('.source-list-row').remove(); listMessage(); }
      if(event.target.closest('[data-add-row]')) { dialog.querySelector('[data-source-rows]').insertAdjacentHTML('beforeend',key==='indexers'?indexerRow({}):replayRow({})); listMessage(); var added=dialog.querySelector('[data-source-rows]').lastElementChild; added.querySelector('input,select').focus(); }
      if(event.target.closest('[data-test-nzbdav]')) testNzbdav(event.target.closest('button'));
    };
    listMessage(); dialog.showModal();
  }
  function values(key) {
    if(key==='usenet') {
      var provider=val('sourceProvider'), client=val('sourceClient'), v={enableUsenet:checked('sourceEnabled'),usenetProvider:provider,downloadClient:client};
      if(provider!=='none') { v.usenetUsername=val('sourceUsername'); if(val('sourcePassword')) v.usenetPassword=val('sourcePassword'); }
      if(provider==='generic') Object.assign(v,{usenetHost:val('sourceHost'),usenetPort:Number(val('sourcePort')),usenetConnections:Number(val('sourceConnections')),usenetSSL:checked('sourceSSL')});
      if(['torbox','premiumize','alldebrid','sabnzbd'].indexOf(client)!==-1 && val('sourceClientKey')) v.downloadClientApiKey=val('sourceClientKey');
      if(['nzbget','sabnzbd'].indexOf(client)!==-1) v.downloadClientUrl=val('sourceClientUrl');
      if(client==='nzbget') { v.downloadClientUsername=val('sourceClientUsername'); if(val('sourceClientPassword')) v.downloadClientPassword=val('sourceClientPassword'); }
      if(provider==='easynews') v.easynewsPreferNzb=checked('sourcePreferNzb');
      var before=state.usenet.values;
      if(provider!==before.usenetProvider && provider!=='none' && !v.usenetPassword) throw new Error('Enter the password for the selected Usenet provider.');
      if(client!==before.downloadClient && client!=='none' && !v.downloadClientApiKey && !v.downloadClientPassword) throw new Error('Enter credentials for the selected download client.');
      return v;
    }
    if(key==='indexers') return {nzbIndexers:Array.from(dialog.querySelectorAll('[data-indexer]')).map(function(row) {
      var type=row.querySelector('select').value; return {type:type,url:type==='custom'?row.querySelector('input[type=url]').value.trim():'',apiKey:row.querySelector('input[type=password]').value.trim()};
    })};
    if(key==='replays') return {enabled:checked('replaysEnabled'),websites:Array.from(dialog.querySelectorAll('[data-replay]')).map(function(row) {
      return {id:row.dataset.id,name:row.querySelector('input[type=text]').value.trim(),url:row.querySelector('input[type=url]').value.trim(),enabled:row.querySelector('input[type=checkbox]').checked};
    })};
    return {base_url:val('nzbdavUrl'),api_key:val('nzbdavKey')};
  }
  function setBusy(value) { busy=value; dialog.querySelectorAll('input,select,button').forEach(function(el) { el.disabled=value; }); }
  function submit(event) {
    event.preventDefault(); if(busy || !active) return;
    var captured=active, key=captured.key, statusEl=dialog.querySelector('[data-source-status]'), data;
    statusEl.textContent='';
    if(captured.profile!==profile()) { statusEl.textContent='The profile changed. Close and reopen this form.'; return; }
    try { data=values(key); } catch(error) { statusEl.textContent=error.message; return; }
    setBusy(true); statusEl.textContent='Saving…';
    var request=key==='nzbdav'?TorveAPI.put('/integrations/nzbdav/config',data):
      (key==='replays'?TorveAPI.put:TorveAPI.patch)('/me/source-setup/'+key,{expectedRevision:captured.revision,values:data});
    request.then(function(result) {
      if(key==='nzbdav') state.nzbdav=result; else state[key==='indexers'?'usenet':key]=result;
      dialog.close(); TorveSetup.refreshView(); notice(titles[key]+' saved for '+(key==='nzbdav'?'this account':TorveProfiles.selectedName())+'. Signed-in apps receive the change when they sync.');
    }).catch(function(error) { statusEl.textContent=error.message||'Could not save. Your entries are still here. Try again.'; })
      .finally(function() { setBusy(false); listMessage(); });
  }
  function testNzbdav(button) {
    if(!dialog.querySelector('form').reportValidity()) return;
    var data=values('nzbdav'), statusEl=dialog.querySelector('[data-source-status]');
    if(!data.api_key) { statusEl.textContent='Enter the NzbDAV API key.'; return; }
    setBusy(true); statusEl.textContent='Testing connection…';
    TorveAPI.post('/integrations/nzbdav/test',data).then(function(result) { statusEl.textContent=result.ok?'Connection test passed. Save to use it.':result.reason||'Connection test failed. Check the URL and key.'; })
      .catch(function(error) { statusEl.textContent=error.message||'Connection test failed.'; }).finally(function() { setBusy(false); });
  }
  dialog.addEventListener('cancel',function(event) { if(busy) event.preventDefault(); });
  dialog.addEventListener('close',function() { dialog.querySelectorAll('input[type=password]').forEach(function(input) { input.value=''; }); dialog.innerHTML=''; active=null; if(opener && opener.isConnected) opener.focus(); });
  return { load:load, render:render, bind:bind, status:status, isOpen:function() { return dialog.open; } };
})();
