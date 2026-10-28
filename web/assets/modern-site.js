(() => {
 'use strict';
 const menuButton=document.querySelector('.site-menu-toggle');
 const mainMenu=document.getElementById('site-navigation');
 if(menuButton && mainMenu) {
   function setMenu(open) {
     menuButton.setAttribute('aria-expanded',String(open));
     menuButton.setAttribute('aria-label',open?'Close menu':'Open menu');
     mainMenu.classList.toggle('is-open',open);
   }
   menuButton.addEventListener('click',()=>setMenu(menuButton.getAttribute('aria-expanded')!=='true'));
   mainMenu.addEventListener('click',event=>{if(event.target.closest('a'))setMenu(false);});
   document.addEventListener('keydown',event=>{if(event.key==='Escape' && menuButton.getAttribute('aria-expanded')==='true'){setMenu(false);menuButton.focus();}});
   matchMedia('(min-width:961px)').addEventListener('change',()=>setMenu(false));
 }
 document.querySelectorAll('[data-current-year]').forEach(element=>{element.textContent=new Date().getFullYear();});
 // Policy navigation keeps the full text visible and offers a shorter route to each section.
 document.querySelectorAll('main .article').forEach(article => {
   const headings=[...article.querySelectorAll('h2')];
   if(headings.length<3) return;
   const shell=document.createElement('div'); shell.className='article-shell';
   article.before(shell); shell.append(article);
   const aside=document.createElement('aside'); aside.className='article-nav';
   const details=document.createElement('details'); details.open=matchMedia('(min-width:641px)').matches;
   const summary=document.createElement('summary'); summary.textContent='On this page'; details.append(summary);
   const nav=document.createElement('nav'); nav.setAttribute('aria-label','Page sections');
   headings.forEach((heading,index) => {
     if(!heading.id) heading.id='section-'+index+'-'+heading.textContent.trim().toLowerCase().replace(/[^a-z0-9]+/g,'-');
     const link=document.createElement('a'); link.href='#'+heading.id; link.textContent=heading.textContent; nav.append(link);
   });
   details.append(nav); aside.append(details); shell.prepend(aside);
 });
 const grid=document.querySelector('.platform-panels');
 if(grid) {
   const cards=[...grid.querySelectorAll(':scope > article[data-platform]')];
   const tabs=document.createElement('div');tabs.className='download-tabs';tabs.setAttribute('role','tablist');tabs.setAttribute('aria-label','Choose your platform');
   const buttons=[];
   function select(index,updateURL=false) {
     cards.forEach((card,i)=>{card.hidden=i!==index;buttons[i].setAttribute('aria-selected',String(i===index));buttons[i].tabIndex=i===index?0:-1;});
     if(updateURL) history.replaceState(null,'','#'+cards[index].dataset.platform);
   }
   cards.forEach((card,i)=>{
     const button=document.createElement('button');button.type='button';button.id='download-tab-'+card.dataset.platform;button.textContent=card.dataset.label;
     button.setAttribute('role','tab');button.setAttribute('aria-controls','download-panel-'+card.dataset.platform);
     card.id='download-panel-'+card.dataset.platform;card.setAttribute('role','tabpanel');card.setAttribute('aria-labelledby',button.id);card.tabIndex=0;
     button.addEventListener('click',()=>select(i,true));buttons.push(button);tabs.append(button);
   });
   tabs.addEventListener('keydown',event=>{
     const i=buttons.indexOf(document.activeElement);if(i<0)return;let n;
     if(event.key==='ArrowRight')n=(i+1)%buttons.length;else if(event.key==='ArrowLeft')n=(i-1+buttons.length)%buttons.length;else if(event.key==='Home')n=0;else if(event.key==='End')n=buttons.length-1;else return;
     event.preventDefault();select(n,true);buttons[n].focus();
   });
   grid.before(tabs);grid.classList.add('is-interactive');
   const fromHash=cards.findIndex(card=>card.dataset.platform===location.hash.slice(1));
   select(fromHash<0?0:fromHash);
   window.addEventListener('hashchange',()=>{const i=cards.findIndex(card=>card.dataset.platform===location.hash.slice(1));if(i>=0)select(i);});
 }
 const overview=document.querySelector('[data-session-view]');
 if(overview) { const nav=document.createElement('nav'); nav.className='account-nav'; nav.setAttribute('aria-label','Account shortcuts'); [['/app/setup.html','Connections'],['/app/devices.html','Devices'],['/app/extensions.html','Extensions'],['#seerr-setup','Media automation'],['/account-deletion.html','Account & data']].forEach(([href,label])=>{const a=document.createElement('a');a.href=href;a.textContent=label;nav.append(a);}); overview.after(nav); }
 const connectionGroups=document.getElementById('setupSections');
 if(connectionGroups) {
   const nav=document.createElement('nav'); nav.className='connection-sections'; nav.setAttribute('role','tablist'); nav.setAttribute('aria-label','Connection sections'); connectionGroups.before(nav);
   const sectionId=()=>location.hash.slice(1)==='essentials'?'usenet':location.hash.slice(1)||'usenet';
   let selected=sectionId();
   function renderConnectionNav() {
     const groups=[...connectionGroups.children].filter(group=>group.id);
     if(!groups.length)return;
     const names=groups.map(group=>[group.id,group.querySelector('h2')?.textContent||group.id]); names.push(['all','All']);
     if(!names.some(([id])=>id===selected))selected=groups[0].id;
     nav.replaceChildren();
     names.forEach(([id,label])=>{
       const button=document.createElement('button');button.type='button';button.id='connection-tab-'+id;button.textContent=label;
       button.setAttribute('role','tab');button.setAttribute('aria-selected',String(id===selected));button.tabIndex=id===selected?0:-1;
       if(id!=='all')button.setAttribute('aria-controls',id);
       button.addEventListener('click',()=>{selected=id;history.replaceState(null,'','#'+id);renderConnectionNav();document.getElementById('connection-tab-'+id).focus({preventScroll:true});});
       nav.append(button);
     });
     groups.forEach(group=>{group.hidden=selected!=='all'&&group.id!==selected;group.setAttribute('role','tabpanel');group.setAttribute('aria-labelledby','connection-tab-'+group.id);});
     document.querySelectorAll('.rail-nav a').forEach(link=>link.classList.toggle('active',link.dataset.section===selected));
   }
   nav.addEventListener('keydown',event=>{
     const buttons=[...nav.querySelectorAll('button')],index=buttons.indexOf(document.activeElement);if(index<0)return;
     let next;if(event.key==='ArrowRight')next=(index+1)%buttons.length;else if(event.key==='ArrowLeft')next=(index-1+buttons.length)%buttons.length;else if(event.key==='Home')next=0;else if(event.key==='End')next=buttons.length-1;else return;
     event.preventDefault();buttons[next].click();
   });
   new MutationObserver(renderConnectionNav).observe(connectionGroups,{childList:true});
   window.addEventListener('hashchange',()=>{selected=sectionId();renderConnectionNav();});
   renderConnectionNav();
 }
 const helpContent=document.getElementById('helpContent');
 if(helpContent) {
   // Keep the public guide's language control with its content, away from the install actions.
   if(document.body.classList.contains('documentation-page') && window.TorveLangSwitcher) {
     const languages=document.createElement('div');languages.className='documentation-tools';languages.id='documentation-language';
     helpContent.before(languages);window.TorveLangSwitcher.render(languages.id);
   }
   function accessibleHelp() {
     helpContent.querySelectorAll('.cat-card[data-cat],.article-link[data-id],.search-result[data-id]').forEach(item=>{item.tabIndex=0;item.setAttribute('role','link');});
     const search=helpContent.querySelector('#searchInput');if(search) search.setAttribute('aria-label','Search documentation');
     if(window.TorveI18n) window.TorveI18n.ready();
   }
   new MutationObserver(accessibleHelp).observe(helpContent,{childList:true,subtree:true});
   helpContent.addEventListener('keydown',event=>{if((event.key==='Enter'||event.key===' ')&&event.target.matches('[role="link"]')){event.preventDefault();event.target.click();}});
   accessibleHelp();
 }
 document.querySelectorAll('a[href]').forEach(link=>{ if(link.origin===location.origin && link.pathname===location.pathname && !link.hash && link.closest('.site-header-nav'))link.setAttribute('aria-current','page'); });
})();
