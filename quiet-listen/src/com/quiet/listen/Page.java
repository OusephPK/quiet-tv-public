package com.quiet.listen;

/**
 * Script injected into Apple's web player. Three jobs:
 *  1. Remote navigation: arrow keys move an amber focus ring to the nearest thing in that
 *     direction (looking inside web components too), OK clicks it.
 *  2. Now playing: once a second, report title, artist, album, artwork, position to the app.
 *  3. Control: play / pause / next / previous from the remote or the home screen.
 * It runs once per page and never changes what Apple's page shows.
 */
final class Page {

    static final String SCRIPT = "(function(){"
            + "if(window.__quiet)return;window.__quiet=1;"
            // ---- finding things: the page, its open shadow roots, and same-site frames (Apple's sign-in)
            + "const SEL='a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[role=option],[role=menuitem],[role=checkbox],[tabindex]:not([tabindex=\"-1\"])';"
            + "function docs(){const out=[document];for(let i=0;i<out.length;i++){out[i].querySelectorAll('iframe').forEach(f=>{"
            + "try{const d=f.contentDocument;if(d&&!out.includes(d))out.push(d)}catch(_){}})}return out}"
            + "function deep(root,out){root.querySelectorAll(SEL).forEach(e=>out.push(e));"
            + "root.querySelectorAll('*').forEach(e=>{if(e.shadowRoot)deep(e.shadowRoot,out)});return out}"
            + "function every(){const out=[];docs().forEach(d=>deep(d,out));return out}"
            // Position on screen, including the offset of any frame the element sits in.
            + "function R(e){const r=e.getBoundingClientRect();let x=r.left,y=r.top,w=e.ownerDocument.defaultView;"
            + "while(w&&w.frameElement){const f=w.frameElement.getBoundingClientRect();x+=f.left;y+=f.top;w=w.parent}"
            + "return{left:x,top:y,right:x+r.width,bottom:y+r.height,width:r.width,height:r.height}}"
            // Prefer the real link or button over the wrapper around it.
            + "function leaf(e){if(e.matches('a,button,input,textarea,select'))return true;return !e.querySelector('a[href],button')}"
            + "function visible(e){if(!leaf(e))return false;const r=R(e);if(r.width<6||r.height<6)return false;"
            + "if(r.bottom<0||r.right<0||r.top>innerHeight*3||r.left>innerWidth*3)return false;"
            + "const s=e.ownerDocument.defaultView.getComputedStyle(e);if(s.visibility=='hidden'||s.display=='none'||s.opacity=='0'||e.disabled)return false;"
            + "if(e.ownerDocument!==document)return true;"
            // Skip things hidden under a banner or player bar (only checkable while on screen).
            + "const cx=r.left+r.width/2,cy=r.top+r.height/2;if(cx<0||cy<0||cx>innerWidth||cy>innerHeight)return true;"
            + "let h=document.elementFromPoint(cx,cy);if(!h)return true;const host=e.getRootNode().host;"
            + "return h===e||e.contains(h)||h.contains(e)||(host&&(h===host||h.contains(host)||host.contains(h)))}"
            + "function active(){let a=document.activeElement;for(let i=0;i<6;i++){"
            + "if(a&&a.tagName=='IFRAME'){try{a=a.contentDocument.activeElement;continue}catch(_){break}}"
            + "if(a&&a.shadowRoot&&a.shadowRoot.activeElement){a=a.shadowRoot.activeElement;continue}break}return a}"
            + "function editable(a){return a&&(a.tagName=='INPUT'&&!/^(button|submit|checkbox|radio|hidden)$/i.test(a.type)||a.tagName=='TEXTAREA'||a.isContentEditable)}"
            // ---- the focus ring
            + "let cur=null;"
            + "function mark(e){if(cur){cur.style.removeProperty('outline');cur.style.removeProperty('outline-offset')}"
            + "cur=e;e.style.setProperty('outline','3px solid #ffb44a','important');e.style.setProperty('outline-offset','3px','important');"
            + "try{e.focus({preventScroll:true})}catch(_){}e.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'})}"
            + "function c(r){return{x:r.left+r.width/2,y:r.top+r.height/2}}"
            + "function move(d){const all=every().filter(visible);if(!all.length)return;"
            + "if(!cur||!cur.isConnected||!visible(cur)){let b=null,bs=1e9;for(const e of all){const r=R(e);"
            + "if(r.top<0||r.top>innerHeight)continue;const s=r.top*2+r.left;if(s<bs){bs=s;b=e}}if(b)mark(b);return}"
            + "const a=R(cur),ca=c(a);let best=null,bs=1e9;"
            + "for(const e of all){if(e===cur||e.contains(cur)||cur.contains(e))continue;const r=R(e),cc=c(r);"
            + "const ok=d=='L'?r.right<=a.left+6:d=='R'?r.left>=a.right-6:d=='U'?r.bottom<=a.top+6:r.top>=a.bottom-6;if(!ok)continue;"
            + "const h=d=='L'||d=='R';const main=h?Math.abs(cc.x-ca.x):Math.abs(cc.y-ca.y),cross=h?Math.abs(cc.y-ca.y):Math.abs(cc.x-ca.x);"
            + "const s=main+cross*(h?3:1.6);if(s<bs){bs=s;best=e}}"
            + "if(best)mark(best);else window.scrollBy({top:d=='U'?-innerHeight*.6:d=='D'?innerHeight*.6:0,behavior:'smooth'})}"
            + "function onKey(ev){const k=ev.key,ae=active();const typing=editable(ae);"
            + "if(typing&&(k=='ArrowLeft'||k=='ArrowRight'))return;"
            + "if(typing&&k=='Enter'){ev.preventDefault();ev.stopPropagation();QuietBridge.editing(true);return}"
            + "const d={ArrowLeft:'L',ArrowRight:'R',ArrowUp:'U',ArrowDown:'D'}[k];"
            + "if(d){ev.preventDefault();ev.stopPropagation();move(d);return}"
            // OK: activate the element under the ring (inside Apple's frame too).
            + "if(k=='Enter'&&cur&&!typing){if(ae===cur&&cur.ownerDocument===document)return;ev.preventDefault();ev.stopPropagation();"
            + "const t=cur.matches('a,button')?cur:(cur.querySelector('a[href],button')||cur);const w=t.ownerDocument.defaultView;"
            + "t.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,composed:true,view:w}))}}"
            + "function install(d){if(d.__quietKeys)return;d.__quietKeys=1;d.addEventListener('keydown',onKey,true);"
            + "d.addEventListener('focusin',()=>{if(editable(active()))QuietBridge.editing(true)},true)}"
            + "install(document);setInterval(()=>docs().forEach(install),800);"
            + "setTimeout(()=>{if(editable(active()))QuietBridge.editing(true)},400);"
            // ---- signing in: read Apple's real field, and fill it in for real
            + "function fieldsIn(d){return [...d.querySelectorAll('input')].filter(e=>!/^(hidden|checkbox|radio|submit|button)$/i.test(e.type)&&visible(e))}"
            + "function signDoc(){return docs().find(d=>fieldsIn(d).length)}"
            + "function pick(d,fs){const a=active();return fs.find(e=>!e.value)||(fs.includes(a)?a:fs[fs.length-1])}"
            + "window.__quietField=function(){const d=signDoc();if(!d)return JSON.stringify({kind:'none'});const fs=fieldsIn(d),el=pick(d,fs);"
            // everything filled and Apple hasn't complained: it's working, don't ask again
            + "if(fs.every(e=>e.value)&&!d.querySelector('[role=alert],.is-error'))return JSON.stringify({kind:'ready'});"
            + "let kind='text';if(fs.length>=4&&fs.every(e=>e.maxLength==1))kind='code';else if(el.type=='password')kind='password';"
            + "else if(el.type=='email'||/account|email|apple/i.test(el.id+' '+el.name+' '+(el.getAttribute('aria-label')||'')))kind='email';"
            + "else if(/code|verif/i.test(el.id+' '+el.name+' '+(el.getAttribute('aria-label')||'')))kind='code';"
            + "const err=[...d.querySelectorAll('[role=alert],.form-message,.error,.is-error,.form-message-wrapper')]"
            + ".map(e=>(e.innerText||'').trim()).filter(Boolean).join(' ').slice(0,140);"
            + "return JSON.stringify({kind:kind,label:(el.getAttribute('aria-label')||el.placeholder||'').slice(0,60),error:err})};"
            + "window.__quietFill=function(t){const d=signDoc();if(!d)return 'none';const w=d.defaultView,fs=fieldsIn(d);"
            + "const set=(el,v)=>{const s=Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype,'value').set;el.focus();s.call(el,v);"
            + "el.dispatchEvent(new w.Event('input',{bubbles:true}));el.dispatchEvent(new w.Event('change',{bubbles:true}))};"
            + "if(fs.length>=4&&fs.every(e=>e.maxLength==1)){const g=t.replace(/\\D/g,'');fs.forEach((e,i)=>set(e,g[i]||''));return 'code'}"
            + "const el=pick(d,fs);set(el,t);__quietSubmit();return el.type||'text'};"
            // Press Apple's arrow. It switches on a beat after a value lands, so keep trying for ~3 s.
            + "window.__quietSubmit=function(){const d=signDoc();if(!d)return 'none';const el=pick(d,fieldsIn(d));"
            + "let n=0;const go=()=>{const b=d.querySelector('#sign-in')||[...d.querySelectorAll('button[type=submit]')].find(x=>visible(x));"
            + "if(b&&!b.disabled&&b.getAttribute('aria-disabled')!='true')b.click();else if(++n<15)setTimeout(go,200);"
            + "else if(el.form)(el.form.requestSubmit?el.form.requestSubmit():el.form.submit())};setTimeout(go,300);return 'ok'};"
            // ---- what's playing
            + "function mk(){try{return window.MusicKit&&MusicKit.getInstance&&MusicKit.getInstance()}catch(_){return null}}"
            // only the player reports; the sign-in window would report "nothing playing" over it
            + "if(location.hostname=='music.apple.com'){let onAir=null;setInterval(()=>{try{let t='',a='',al='',art='',p=false,pos=0,dur=0,live=false,g='';const m=mk();"
            // live radio: the station is the item; what's on air arrives as timed metadata
            + "if(m&&!m.__quietAir){m.__quietAir=1;m.addEventListener('timedMetadataDidChange',e=>{try{let o={};for(const k in e){try{if(typeof e[k]!='function')o[k]=e[k]}catch(_){}}"
            + "QuietBridge.log('air '+JSON.stringify(o).slice(0,500)+' proto '+Object.getOwnPropertyNames(Object.getPrototypeOf(e)||{}).join(','))}catch(_){}"
            + "onAir=e&&(e.title||e.performer)?e:null})}"
            + "if(m&&m.nowPlayingItem){const it=m.nowPlayingItem,at=it.attributes||{};"
            + "t=it.title||at.name||'';a=at.artistName||it.artistName||'';al=it.albumName||at.albumName||'';"
            + "const u=(at.artwork&&at.artwork.url)||it.artworkURL||'';art=u.replace('{w}','160').replace('{h}','160').replace('{f}','jpg');"
            + "p=!!m.isPlaying;pos=m.currentPlaybackTime||0;dur=m.currentPlaybackDuration||0;g=(at.genreNames||[]).join('|');"
            + "live=!!(it.isLiveRadioStation||at.isLive);if(live){al=t;if(onAir&&onAir.title){t=onAir.title;a=onAir.performer||onAir.artist||''}}else onAir=null}"
            + "else if(navigator.mediaSession&&navigator.mediaSession.metadata){const md=navigator.mediaSession.metadata;"
            + "t=md.title||'';a=md.artist||'';al=md.album||'';if(md.artwork&&md.artwork.length)art=md.artwork[md.artwork.length-1].src;"
            + "const au=document.querySelector('audio,video');if(au){p=!au.paused;pos=au.currentTime||0;dur=au.duration||0}}"
            + "QuietBridge.now(t,a,al,art,p,pos,isFinite(dur)?dur:0,live,g)}catch(_){}},1000)}"
            // ---- the Apple Music API, through Apple's own player (its tokens never leave the page)
            + "async function ready(){for(let i=0;i<150;i++){const m=mk();if(m&&m.api)return m;await new Promise(r=>setTimeout(r,100))}"
            + "throw 'PLAYER NOT READY'}"
            + "window.__quietApi=async function(id,path,params){try{const m=await ready();const r=await m.api.music(path,params||{});"
            + "QuietBridge.reply(id,JSON.stringify(r.data||r))}catch(e){QuietBridge.fail(id,String((e&&(e.errorCode||e.message))||e))}};"
            + "window.__quietPlay=async function(kind,id,start,shuffle){QuietBridge.queued();const t0=Date.now(),lap=w=>{try{QuietBridge.log('play '+w+' +'+(Date.now()-t0)+'ms')}catch(_){}};"
            + "try{const m=await ready();lap('ready');const o={};"
            + "if(kind=='songs')o.songs=id.split(',');else o[kind]=id;if(start>0)o.startWith=start;"
            + "await m.setQueue(o);lap('queued');m.shuffleMode=shuffle?1:0;try{m.autoplayEnabled=true}catch(_){}await m.play();lap('playing');QuietBridge.played(true,'')}"
            + "catch(e){QuietBridge.played(false,String((e&&(e.errorCode||e.message))||e))}};"
            + "window.__quietAuth=async function(){try{const m=await ready();await m.authorize();QuietBridge.auth(true)}catch(e){QuietBridge.auth(false)}};"
            + "setInterval(()=>{try{const m=mk();if(m&&m.api)QuietBridge.state(!!m.isAuthorized,m.storefrontId||'us')}catch(_){}},1000);"
            // ---- control from outside
            // ---- lyrics: Apple's TTML (word-timed when there is, else line-timed) as [{b,e,w:[[text,b,e]]}]
            + "window.__quietLyrics=async function(){try{const m=mk();const it=m&&m.nowPlayingItem;if(!it)return;const a=it.attributes||{};"
            + "const id=String((a.playParams&&a.playParams.catalogId)||it.id);if(a.hasLyrics===false){QuietBridge.lyrics(id,'[]');return}"
            + "let t='';for(const p of ['syllable-lyrics','lyrics']){try{const r=await m.api.music('/v1/catalog/'+m.storefrontId+'/songs/'+id+'/'+p);"
            + "t=r.data.data[0].attributes.ttml||'';if(t)break}catch(_){}}if(!t){QuietBridge.lyrics(id,'[]');return}"
            + "const x=new DOMParser().parseFromString(t,'text/xml');const sec=v=>v?v.split(':').map(Number).reduce((q,n)=>q*60+n,0):0;const out=[];"
            + "x.querySelectorAll('p').forEach(p=>{const pb=sec(p.getAttribute('begin')),pe=sec(p.getAttribute('end')),ws=[];"
            + "const walk=n=>n.childNodes.forEach(c=>{if(c.nodeType==3){if(ws.length)ws[ws.length-1][0]+=c.textContent;else if(c.textContent.trim())ws.push([c.textContent,pb,pe])}"
            + "else if(/span$/.test(c.nodeName)){if(c.getAttribute('ttm:role')=='x-bg')return;if(c.querySelector('span'))walk(c);"
            + "else ws.push([c.textContent,sec(c.getAttribute('begin'))||pb,sec(c.getAttribute('end'))||pe])}});walk(p);"
            + "if(!ws.length&&p.textContent.trim())ws.push([p.textContent,pb,pe]);if(ws.length)out.push({b:pb,e:pe,w:ws})});"
            + "QuietBridge.lyrics(id,JSON.stringify(out))}catch(e){QuietBridge.lyrics('','[]')}};"
            // ---- what's coming up, and loving a song (a rating, plus a copy in your library)
            + "window.__quietQueue=function(){try{const m=mk();const q=m.queue,its=q.items||[],out=[];"
            + "if(q.position>=its.length-1&&lastSong)return JSON.stringify([['AUTOPLAY · SIMILAR MUSIC','']]);"
            + "for(let i=q.position+1;i<its.length&&out.length<8;i++){const a=its[i].attributes||{};"
            + "out.push([its[i].title||a.name||'',its[i].artistName||a.artistName||''])}return JSON.stringify(out)}catch(e){return '[]'}};"
            + "function songId(m){const it=m.nowPlayingItem;if(!it)throw 'NOTHING PLAYING';const a=it.attributes||{};return String((a.playParams&&a.playParams.catalogId)||it.id)}"
            + "async function rating(m,id){try{const r=await m.api.music('/v1/me/ratings/songs/'+id);return r.data.data[0].attributes.value||0}catch(_){return 0}}"
            + "window.__quietRating=async function(){try{const m=await ready();const id=songId(m);QuietBridge.rating(id,await rating(m,id))}catch(_){}};"
            // love toggles; whatever the call answers, the rating read back afterwards is the truth
            + "window.__quietLove=async function(on){try{const m=await ready();const id=songId(m);let err='';"
            + "try{await m.api.music('/v1/me/ratings/songs/'+id,{},{fetchOptions:on?{method:'PUT',body:JSON.stringify({type:'rating',attributes:{value:1}})}:{method:'DELETE'}})}"
            + "catch(e){err=String((e&&(e.errorCode||e.message))||e)}"
            + "if(on)try{await m.api.music('/v1/me/library',{'ids[songs]':id},{fetchOptions:{method:'POST'}})}catch(_){}"
            + "const v=await rating(m,id);QuietBridge.rating(id,v);QuietBridge.loved((v==1)==on,on,err)}"
            + "catch(e){QuietBridge.loved(false,on,String((e&&(e.errorCode||e.message))||e))}};"
            // ---- autoplay: when the queue runs out (or you skip past its end), the last song's station takes over
            + "let lastSong='';async function radio(m){if(!lastSong)return;try{await m.setQueue({station:'ra.'+lastSong});"
            + "try{m.autoplayEnabled=true}catch(_){}await m.play();QuietBridge.autoplay(true)}catch(e){QuietBridge.autoplay(false)}}"
            + "function atEnd(m){const q=m.queue;return !q||!q.items||q.position>=q.items.length-1}"
            + "setInterval(()=>{try{const m=mk();if(!m)return;const it=m.nowPlayingItem;"
            + "if(it&&!it.isLiveRadioStation&&!(it.attributes&&it.attributes.isLive)&&it.type!='stations'){const a=it.attributes||{};"
            + "lastSong=String((a.playParams&&a.playParams.catalogId)||it.id||lastSong)}"
            + "if(!m.__quietEnd){m.__quietEnd=1;m.addEventListener('playbackStateDidChange',e=>{const st=e&&e.state;"
            + "if((st==10||st==5)&&atEnd(m)&&window.__quietAuto!==false)radio(m)})}}catch(_){}},1000);"
            + "window.__quietCtl=function(x){const m=mk();"
            + "if(m&&m.nowPlayingItem&&x=='next'&&atEnd(m)&&window.__quietAuto!==false){radio(m);return}"
            + "if(m&&m.nowPlayingItem){if(x=='toggle')m.isPlaying?m.pause():m.play();else if(x=='play')m.play();"
            + "else if(x=='pause')m.pause();else if(x=='next')m.skipToNextItem();else if(x=='prev')m.skipToPreviousItem();return}"
            + "const au=document.querySelector('audio,video');if(!au)return;"
            + "if(x=='toggle')au.paused?au.play():au.pause();else if(x=='play')au.play();else if(x=='pause')au.pause()};"
            // ---- can this browser play protected (full-length) audio?
            + "if(navigator.requestMediaKeySystemAccess){navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],"
            + "audioCapabilities:[{contentType:'audio/mp4;codecs=\"mp4a.40.2\"'}]}]).then(()=>QuietBridge.drm(true),()=>QuietBridge.drm(false))}"
            + "else QuietBridge.drm(false);"
            + "})();";
}
