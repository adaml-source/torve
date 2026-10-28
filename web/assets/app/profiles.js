/* Profile-aware owner setup. Uses the app's household records and encrypted connections. */
var TorveProfiles = (function () {
  'use strict';
  var root = document.getElementById('profileWorkspace'), dialog = document.getElementById('profileDialog');
  var selected = 'default', grant = '', family = null, profiles = [], schema = [], familyVersion = 0, refreshConnections;
  var busy = false, loading = false, timer = null;
  function controlsBusy() { root.querySelectorAll('button,select').forEach(function(el) { el.disabled=busy||loading; }); }
  var base = '/me/family/profiles';
  var labels = { DEBRID_API_KEY_TORBOX:'TorBox', DEBRID_API_KEY_REAL_DEBRID:'Real-Debrid', DEBRID_API_KEY_ALL_DEBRID:'AllDebrid',
    DEBRID_API_KEY_PREMIUMIZE:'Premiumize', TRAKT_TOKENS:'Trakt', SIMKL_ACCESS_TOKEN:'SIMKL', OMDB_API_KEY:'OMDb',
    MDBLIST_API_KEY:'MDBList', JELLYFIN_API_KEY:'Jellyfin', PLEX_ACCESS_TOKEN:'Plex' };
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function context() { TorveAPI.setProfileContext(selected, grant); }
  function current() { return profiles.find(function(p) { return p.id === selected; }); }
  function content(visible) { document.querySelectorAll('[data-profile-content],.connection-sections').forEach(function(el) { el.hidden = !visible; }); }
  function message(text, error) { var el=root.querySelector('[data-profile-status]'); if(el) { el.textContent=text; el.setAttribute('role',error?'alert':'status'); } }
  function errorText(error) { return typeof error.message === 'string' ? error.message : 'Could not save. Check your entries and try again.'; }
  function requestFailure(error) {
    if (error.status === 403 && family && family.enabled) { grant=''; context(); content(false); render(); }
    message(error.status === 409 ? 'Changed on another device. Close this form, reload profiles and try again.' : errorText(error), true);
  }
  function options(values, value) { return values.map(function(v) { var pair=Array.isArray(v)?v:[v,v.replace(/_/g,' ')]; return '<option value="'+esc(pair[0])+'"'+(String(pair[0])===String(value)?' selected':'')+'>'+esc(pair[1])+'</option>'; }).join(''); }
  function field(id, label, value, type, extra) { return '<label for="'+id+'">'+esc(label)+'</label><input id="'+id+'" name="'+id+'" type="'+(type||'text')+'" value="'+esc(value)+'" '+(extra||'')+'>'; }
  function row(inner) { return '<div class="profile-field">'+inner+'</div>'; }
  function select(id, label, values, value) { return row('<label for="'+id+'">'+esc(label)+'</label><select id="'+id+'">'+options(values,value)+'</select>'); }
  function checkbox(id, label, checked) { return '<label class="profile-check"><input type="checkbox" id="'+id+'"'+(checked?' checked':'')+'> '+esc(label)+'</label>'; }
  function show(title, body, submitLabel, submit) {
    dialog.innerHTML='<form method="dialog" id="profileForm"><header><h2 id="profileDialogTitle">'+esc(title)+'</h2><button type="button" data-close aria-label="Close">×</button></header><div class="profile-dialog-body">'+body+'<p data-form-status role="alert"></p></div><footer><button type="button" class="btn-sm btn-edit" data-close>Cancel</button><button type="submit" class="btn-sm btn-setup">'+esc(submitLabel)+'</button></footer></form>';
    dialog.querySelectorAll('[data-close]').forEach(function(el) { el.onclick=function() { if(!busy) dialog.close(); }; });
    dialog.querySelector('form').onsubmit=function(event) {
      event.preventDefault(); if(busy) return;
      var status=dialog.querySelector('[data-form-status]'); status.textContent='';
      if (!event.target.reportValidity()) return;
      busy=true; controlsBusy(); dialog.querySelector('[type=submit]').disabled=true;
      Promise.resolve().then(submit).then(function() { dialog.close(); return reload(); }).then(function() { message('Saved to your account. Signed-in apps receive these changes when they sync.'); })
        .catch(function(error) { if(dialog.open) status.textContent=errorText(error); requestFailure(error); })
        .finally(function() { busy=false; controlsBusy(); var button=dialog.querySelector('[type=submit]'); if(button) button.disabled=false; });
    };
    dialog.showModal();
  }
  dialog.addEventListener('cancel',function(event) { if(busy) event.preventDefault(); });
  dialog.addEventListener('close',function() { dialog.querySelectorAll('[type=password]').forEach(function(el) { el.value=''; }); });
  function value(id) { return document.getElementById(id).value.trim(); }
  function checked(id) { return document.getElementById(id).checked; }
  function accept(proof) {
    family=proof.configuration; familyVersion=family.version; grant=proof.grant || ''; context();
    clearTimeout(timer);
    if(grant) timer=setTimeout(function() { grant=''; context(); content(false); render(); }, 5*60*1000);
  }
  function unlock() {
    show('Verify parent PIN',row(field('parentPin','Parent PIN','','password','required inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="off"'))+
      '<p>The parent PIN protects adult profiles, child rules and saved connections.</p><button type="button" class="profile-link" id="forgotParent">Forgot PIN?</button>', 'Unlock settings',function() {
        return TorveAPI.post('/me/family/authorize',{pin:value('parentPin')}).then(accept);
      });
    document.getElementById('forgotParent').onclick=function() { dialog.close(); recover(); };
  }
  function recover() {
    show('Recover parent PIN','<p>A recovery code will be sent to the verified account owner’s email.</p>','Send recovery code',function() {
      return TorveAPI.post('/me/family/recovery/request',{}).then(function(result) {
        // Open the next form after the first submit lifecycle has completed.
        setTimeout(function() {
          show('Reset parent PIN','<p>Enter the six-digit code sent to '+esc(result.destination)+'. It expires in 10 minutes.</p>'+row(field('recoveryCode','Email code','','text','required inputmode="numeric" pattern="[0-9]{6}" autocomplete="one-time-code"'))+
            row(field('recoveryPin','New parent PIN','','password','required inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="new-password"'))+
            row(field('recoveryConfirm','Repeat new PIN','','password','required inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="new-password"')),
            'Reset PIN',function() { if(value('recoveryPin')!==value('recoveryConfirm')) throw new Error('Enter the same new PIN twice.');
              return TorveAPI.post('/me/family/recovery/confirm',{code:value('recoveryCode'),pin:value('recoveryPin')}).then(accept); });
        },0);
      });
    });
  }
  function familySettings(mode) {
    var disable=mode==='disable', title=disable?'Turn off Family controls':family.enabled?'Change parent PIN':'Enable Family controls';
    var body=disable?'<p>Adult profiles will no longer require the parent PIN. Individual profile rating limits remain active.</p>':
      '<p>Use a 4–6 digit parent PIN to protect adult profiles and profile management. Recovery uses the verified account owner’s email.</p>'+row(field('familyPin','Parent PIN','','password','required inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="new-password"'))+
      row(field('familyConfirm','Repeat PIN','','password','required inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="new-password"'));
    show(title,body,disable?'Turn off':'Save parent PIN',function() {
      if(!disable && value('familyPin')!==value('familyConfirm')) throw new Error('Enter the same PIN twice.');
      var configuration=Object.assign({},family,{enabled:!disable});
      var data={configuration:configuration,expectedVersion:family.version}; if(!disable) data.pin=value('familyPin');
      return TorveAPI.put('/me/family',data).then(accept);
    });
  }
  function basicFields(p, create) {
    var body=row(field('profileName','Profile name',p.name,'text','required maxlength="60" autocomplete="off"'));
    body+=select('profileAvatar','Avatar',['Person','Profile','Star','Heart','Bolt','Flame','Leaf','Moon'].map(function(name,i) { return [String(i),name]; }),p.avatar || 0);
    body+=select('profileRating','Maximum US content rating',[['','Unrestricted'],['G','G'],['PG','PG'],['PG_13','PG-13'],['R','R'],['NC_17','NC-17 (unrestricted)']],p.rating || '');
    body+=row(field('profilePin',create?'Profile PIN (optional)':'New profile PIN (leave empty to keep current PIN)','','password','inputmode="numeric" pattern="[0-9]{4,6}" autocomplete="new-password"'));
    if(p.protected) body+=checkbox('removeProfilePin','Remove this profile’s PIN',false);
    return body;
  }
  function preferenceFields(p) {
    var groups={}; schema.forEach(function(f) { (groups[f.group]||(groups[f.group]=[])).push(f); });
    return Object.keys(groups).map(function(group) {
      return '<details class="profile-preferences"><summary>'+esc(group)+'</summary>'+groups[group].map(function(f) {
        var raw=p.preferences[f.key], id='pref_'+f.key, v=raw == null ? '' : raw;
        var label='<label for="'+id+'">'+esc(f.label)+'</label>', input;
        if(f.type==='select' || f.type==='boolean') {
          var choices=f.type==='boolean'?[['true','Yes'],['false','No']]:f.options;
          input='<select id="'+id+'" data-pref="'+f.key+'"><option value=""'+(v!==''?' disabled':'')+'>Use app default</option>';
          if(v && !choices.some(function(choice) { return (Array.isArray(choice)?choice[0]:choice)===v; })) input+='<option selected value="'+esc(v)+'">'+esc(v)+'</option>';
          input+=options(choices,v)+'</select>';
        } else if(f.type==='json') {
          var pretty=v; try { if(v) pretty=JSON.stringify(JSON.parse(v),null,2); } catch(e) {}
          input='<textarea id="'+id+'" data-pref="'+f.key+'" rows="4" spellcheck="false">'+esc(pretty)+'</textarea><small>Advanced layout or list configuration. Uses the same structure as the app.</small>';
        } else input='<input id="'+id+'" data-pref="'+f.key+'" type="'+(f.type==='number'?'number':'text')+'" value="'+esc(v)+'"'+(f.type==='number'?' min="0" max="10000000"':'')+'>';
        return row(label+input);
      }).join('')+'</details>';
    }).join('');
  }
  function childFields(p) {
    if(!family.enabled) return '<p class="profile-hint">Family controls are optional. A profile rating limit applies even when Family controls are off.</p>';
    var rules=p.kidsRules || {maximumAge:0,ratingCountry:'US',hideUnrated:true,allowedSources:['catalogue'],blockedTitles:[]};
    var sources={catalogue:'Movies & shows catalogue',downloads:'Offline library'};
    rules.allowedSources.forEach(function(s) { if(!sources[s]) sources[s]=s; });
    (TorveSetup.getSavedAddons()||[]).forEach(function(a) { sources['extension:'+a.addon_id]=a.name||a.addon_id; });
    var body=checkbox('childProfile','Child profile',!!p.kidsRules);
    body+='<div id="childFields"'+(!p.kidsRules?' hidden':'')+'>'+row(field('childAge','Maximum age',rules.maximumAge,'number','min="0" max="17" required'));
    body+=select('childCountry','Rating country',[['US','United States'],['DE','Germany'],['GB','United Kingdom'],['FR','France'],['AU','Australia']],rules.ratingCountry);
    body+=checkbox('hideUnrated','Hide titles without a known age rating',rules.hideUnrated);
    body+='<fieldset><legend>Allowed libraries and sources</legend>'+Object.keys(sources).map(function(s,i) {
      return '<label class="profile-check"><input type="checkbox" data-source="'+esc(s)+'"'+(rules.allowedSources.indexOf(s)>=0?' checked':'')+'> '+esc(sources[s])+'</label>';
    }).join('')+'</fieldset>';
    body+=row('<label for="blockedTitles">Blocked titles</label><textarea id="blockedTitles" rows="3" placeholder="MOVIE:863">'+esc(rules.blockedTitles.join('\n'))+'</textarea><small>One title ID per line, for example MOVIE:863 or SERIES:tt1234567. Other source restrictions stay in effect.</small>');
    body+=row('<label for="otherSources">Additional library or channel source IDs</label><textarea id="otherSources" rows="2" placeholder="jellyfin:library-id"></textarea><small>Add one source ID per line, such as jellyfin:library-id or channels:playlist-id:category. URLs and credentials are never entered here.</small>');
    return body+'</div>';
  }
  function editProfile(create) {
    var p=create?{name:'',avatar:0,rating:null,preferences:{}}:current();
    show(create?'Add profile':'Edit '+p.name,basicFields(p,create)+(create?'':childFields(p)+'<h3>Synced preferences</h3><p>Only changed fields are saved. Downloads, device capabilities and temporary filters stay on each device.</p>'+preferenceFields(p)),
      create?'Create profile':'Save profile',function() {
        var data={name:value('profileName'),avatar:Number(value('profileAvatar')),rating:value('profileRating')||null};
        if(value('profilePin')) data.pin=value('profilePin');
        if(create) return TorveAPI.post(base,data).then(function(result) { selected=result.id; context(); });
        data.clearPin=!!document.getElementById('removeProfilePin')&&checked('removeProfilePin');
        data.expectedChangedAt=p.changedAt; data.expectedFamilyVersion=familyVersion;
        data.kidsRules=null;
        if(family.enabled && checked('childProfile')) data.kidsRules={maximumAge:Number(value('childAge')),ratingCountry:value('childCountry'),hideUnrated:checked('hideUnrated'),
          allowedSources:Array.from(dialog.querySelectorAll('[data-source]:checked')).map(function(el) { return el.dataset.source; }).concat(value('otherSources').split(/\s+/).filter(Boolean)),
          blockedTitles:value('blockedTitles').split(/\s+/).filter(Boolean)};
        data.preferences={}; data.expectedPreferences={};
        schema.forEach(function(f) {
          var el=document.getElementById('pref_'+f.key), raw=el.value, prior=p.preferences[f.key];
          if(f.type==='json' && raw) { try { raw=JSON.stringify(JSON.parse(raw)); } catch(e) { throw new Error(f.label+': enter valid JSON.'); }
            if(prior) { try { prior=JSON.stringify(JSON.parse(prior)); } catch(e) {} } }
          if(raw===(prior==null?'':prior)) return;
          if(!raw && (f.type==='json'||f.type==='number'||f.type==='select'||f.type==='boolean')) throw new Error('Choose a value for '+f.label+'.');
          data.preferences[f.key]=raw; data.expectedPreferences[f.key]=p.preferences[f.key] == null?null:p.preferences[f.key];
        });
        return TorveAPI.put(base+'/'+encodeURIComponent(p.id),data).then(function() { return TorveAPI.get('/me/family').then(function(s) { family=s.configuration; }); });
      });
    if(!create && family.enabled) document.getElementById('childProfile').onchange=function() { document.getElementById('childFields').hidden=!this.checked; };
  }
  function deleteProfile() {
    var p=current();
    show('Delete '+p.name,'<p>This profile will be removed from your account and signed-in apps. Other profiles remain available.</p>','Delete profile',function() {
      // TorveAPI.del has no body parameter; use the explicit profile removal action.
      return TorveAPI.post(base+'/'+encodeURIComponent(p.id)+'/delete',{expectedChangedAt:p.changedAt,expectedFamilyVersion:familyVersion}).then(function() { selected='default'; context(); });
    });
  }
  function copyConnections() {
    var target=current(), sources=profiles.filter(function(p) { return p.id!==target.id; });
    if(!sources.length) return message('Add another profile before copying connections.',true);
    var source=sources[0].id;
    show('Copy connections to '+target.name,select('copySource','Copy from',sources.map(function(p) { return [p.id,p.name]; }),source)+
      '<div id="copyServices" role="status">Loading saved connections…</div>'+checkbox('replaceConnections','Replace existing destination connections',false)+
      '<p>Only selected account-synced connections are copied. Trakt copies share the same Trakt account, including its external history and lists. Torve watch history is kept separate.</p>',
      'Copy selected',function() {
        var services=Array.from(dialog.querySelectorAll('[data-service]:checked')).map(function(el) { return el.dataset.service; });
        if(!services.length) throw new Error('Choose at least one connection.');
        return TorveAPI.post('/me/family/connections/copy',{sourceProfile:value('copySource'),targetProfile:target.id,services:services,replaceExisting:checked('replaceConnections')});
      });
    var choicesRequest=0;
    function loadChoices() {
      var request=++choicesRequest; dialog.querySelector('[type=submit]').disabled=true;
      var captured=value('copySource'), area=document.getElementById('copyServices'); area.textContent='Loading saved connections…';
      TorveAPI.get('/me/family/connections/'+encodeURIComponent(captured)).then(function(result) {
        if(!dialog.open || request!==choicesRequest || value('copySource')!==captured) return;
        dialog.querySelector('[type=submit]').disabled=!result.services.length;
        area.innerHTML=result.services.length?result.services.map(function(s) { return '<label class="profile-check"><input type="checkbox" data-service="'+esc(s)+'"> '+esc(labels[s]||s.replace(/_/g,' '))+'</label>'; }).join(''):'<p>No account-synced connections in this profile. Save the connection with account sync in the app first.</p>';
      }).catch(function(error) { if(request===choicesRequest) area.textContent=errorText(error); });
    }
    document.getElementById('copySource').onchange=loadChoices; loadChoices();
  }
  function render() {
    if(!family) return;
    if(family.enabled && !grant) {
      root.innerHTML='<div class="profile-card"><h2>Profiles & Family</h2><p>Verify the parent PIN to manage profiles, child restrictions and connections.</p><div class="profile-actions"><button class="btn-sm btn-setup" id="unlockProfiles">Unlock settings</button><button class="btn-sm btn-edit" id="recoverProfiles">Forgot PIN?</button></div><p data-profile-status role="status"></p></div>';
      document.getElementById('unlockProfiles').onclick=unlock; document.getElementById('recoverProfiles').onclick=recover; controlsBusy(); return;
    }
    var p=current(); if(!p) return;
    root.innerHTML='<div class="profile-card"><div class="profile-toolbar"><div class="profile-select"><label for="selectedProfile">Editing profile</label><select id="selectedProfile">'+options(profiles.map(function(p) { return [p.id,p.name+(p.kidsRules?' · Child':'')]; }),selected)+'</select></div><div class="profile-actions"><button class="btn-sm btn-setup" id="editProfile">Edit profile</button><button class="btn-sm btn-edit" id="addProfile">Add profile</button></div></div>'+
      '<p class="profile-hint">'+esc(p.name)+' · '+(p.rating?'Maximum rating '+esc(p.rating.replace('_','-')):'No profile rating limit')+(p.protected?' · PIN protected':'')+'</p>'+
      '<details class="profile-management"><summary>Profile &amp; family settings</summary><div class="profile-actions"><button class="btn-sm btn-edit" id="copyConnections">Copy connections</button><button class="btn-sm btn-edit" id="reloadProfiles">Reload profiles</button>'+(p.id!=='default'?'<button class="btn-sm btn-danger" id="deleteProfile">Delete profile</button>':'')+'</div>'+
      '<div class="profile-family"><div><strong>Family controls: '+(family.enabled?'On':'Off')+'</strong><p>Protect adult profiles and manage age limits and library access.</p></div><div class="profile-actions"><button class="btn-sm btn-edit" id="familySettings">'+(family.enabled?'Change parent PIN':'Enable Family controls')+'</button>'+(family.enabled?'<button class="profile-link" id="disableFamily">Turn off</button><button class="profile-link" id="lockProfiles">Lock settings</button>':'')+'</div></div>'+
      '</details><p data-profile-status role="status"></p></div>';
    document.getElementById('selectedProfile').onchange=function() {
      if(busy || document.getElementById('modal').classList.contains('visible') || (typeof TorveSources !== 'undefined' && TorveSources.isOpen())) { this.value=selected; return message('Finish or close the connection form before switching profiles.',true); }
      selected=this.value; context(); reload().catch(requestFailure);
    };
    document.getElementById('editProfile').onclick=function() { editProfile(false); };
    document.getElementById('addProfile').onclick=function() { editProfile(true); };
    document.getElementById('copyConnections').onclick=copyConnections;
    document.getElementById('reloadProfiles').onclick=function() { reload().catch(requestFailure); };
    document.getElementById('familySettings').onclick=function() { familySettings(); };
    if(p.id!=='default') document.getElementById('deleteProfile').onclick=deleteProfile;
    controlsBusy();
    if(family.enabled) {
      document.getElementById('disableFamily').onclick=function() { familySettings('disable'); };
      document.getElementById('lockProfiles').onclick=function() { grant=''; context(); content(false); render(); window.scrollTo({top:0}); document.getElementById('unlockProfiles').focus({preventScroll:true}); };
    }
  }
  function reload() {
    loading=true; controlsBusy(); content(false);
    return TorveAPI.get('/me/family').then(function(s) {
      family=s.configuration; familyVersion=family.version;
      if(family.enabled&&!grant) { render(); return; }
      return TorveAPI.get(base).then(function(result) {
        profiles=result.profiles; schema=result.preferenceSchema; familyVersion=result.familyVersion;
        if(!current()) selected='default'; context(); render();
        return refreshConnections().then(function() { content(true); });
      });
    }).finally(function() { loading=false; controlsBusy(); });
  }
  return { selectedId:function() { return selected; }, selectedName:function() { return current()?current().name:'Default'; },
    start:function(onChange) { refreshConnections=onChange; context(); return reload().catch(function(error) {
      root.innerHTML='<div class="profile-card"><h2>Profiles could not be loaded</h2><p data-profile-status role="alert"></p><button class="btn-sm btn-edit" id="retryProfiles">Try again</button></div>';
      message(errorText(error),true); document.getElementById('retryProfiles').onclick=function() { reload().catch(requestFailure); };
    }); } };
})();
