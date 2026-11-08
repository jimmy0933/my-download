package com.jimmy.mydownload

/**
 * Optional X progress controls. Both switches default to false, preserving
 * X's original UI. Never resize, reparent, restyle, or reload a video element.
 *
 * The original timeline filter marks only thin seek elements. It does not
 * hide the player, its control row, or any parent container.
 */
internal fun xVideoProgressScript(options: XDisplayOptions): String {
    val settings = "{hideNative:" + options.hideNativeXVideoProgress +
        ",showCustom:" + options.showCustomXVideoProgress +
        ",hideAuthor:" + options.hideViewerAuthorShortcut + "}"
    return X_OPTIONAL_VIDEO_PROGRESS_SCRIPT.replace("__SETTINGS__", settings)
}

private val X_OPTIONAL_VIDEO_PROGRESS_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return 'not-x';

  const settings = __SETTINGS__;
  // Fully opt-in: with both switches off, leave X's DOM and playback
  // rendering unchanged. Previously saved overlay settings are not reused.
  if (!settings.hideNative && !settings.showCustom && !settings.hideAuthor &&
      !window.__mydownloadOptionalXSeek) return 'untouched-native';
  const state = window.__mydownloadOptionalXSeek ||
    (window.__mydownloadOptionalXSeek = {
      root:null,zone:null,line:null,fill:null,video:null,seekVideo:null,
      playButton:null,pauseButton:null,elapsedNode:null,durationNode:null,
      seeking:false,pointerId:null,oldTracks:new Set(),authorLinks:new Set(),
      lastFraction:-1,observer:null,refresh:null,busy:false,
      boundVideo:null,boundListener:null,settings:null,
      nativeToggle:null,nativeToggleMarks:new Set()
    });

  state.settings=settings;
  state.authorLinks=state.authorLinks||new Set();
  const cssId='mydownload-optional-native-seek-css';
  if (!document.getElementById(cssId)) {
    const style=document.createElement('style');
    style.id=cssId;
    // Apply to future X controls BEFORE they are measured or individually
    // tagged. CSS persists across React video changes, preventing a one-frame
    // reappearance while the next seekbar mounts.
    style.textContent=`
      html[data-mydownload-hide-x-timeline="1"]
      [data-mydownload-x-native-track="1"],
      html[data-mydownload-hide-x-timeline="1"]
      :is([data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],
          [data-testid="videoPlayer"],#layers [role="dialog"]:has(video))
      :is(
        [role="slider"]:not([aria-label*="volume" i]):not([aria-label*="音量"]):not([data-testid*="volume" i]),
        input[type="range"]:not([aria-label*="volume" i]):not([aria-label*="音量"]),
        [data-testid*="seek" i],[data-testid*="scrub" i],
        [data-testid*="video-progress" i],[data-testid*="video-timeline" i],
        [aria-label*="seek" i],[aria-label*="timeline" i],
        [aria-label*="播放進度"]
      ) {
        opacity:0 !important;
        pointer-events:none !important;
      }
      html[data-mydownload-hide-x-author="1"]
      [data-mydownload-x-author-shortcut="1"],
      html[data-mydownload-hide-x-native-toggle="1"]
      [data-mydownload-x-native-toggle="1"] {
        opacity:0 !important;
        pointer-events:none !important;
      }
      html[data-mydownload-hide-x-native-toggle="1"]
      :is([data-testid="videoPlayer"],[data-testid="vss-scroll-view"],
          [data-testid="immersive-viewer"])
      :is(button,[role="button"]):is(
          [aria-label="Play" i],[aria-label="Pause" i],
          [aria-label="播放"],[aria-label="暫停"],
          [data-testid="playButton"],[data-testid="pauseButton"],
          [data-testid*="videoPlayButton" i],
          [data-testid*="videoPauseButton" i]
      ) {
        opacity:0 !important;
        pointer-events:none !important;
      }
    `;
    (document.head || document.documentElement).appendChild(style);
  }

  const clearNative=()=>{
    for (const el of state.oldTracks) {
      if (el.isConnected) el.removeAttribute('data-mydownload-x-native-track');
    }
    state.oldTracks.clear();
    document.documentElement.removeAttribute('data-mydownload-hide-x-timeline');
  };

  const create=()=>{
    if (state.root?.isConnected) return;
    // Only a narrow control strip receives touches; the rest of the
    // WebView, video surface and X's vertical swipe carousel are untouched.
    const root=document.createElement('div');
    root.id='mydownload-optional-x-progress';
    Object.assign(root.style,{
      position:'fixed',left:'12px',right:'12px',
      bottom:'68px',height:'56px',zIndex:'2147483647',
      display:'none',alignItems:'center',gap:'7px',
      pointerEvents:'none',background:'transparent',
      boxSizing:'border-box',isolation:'isolate'
    });
    const control=(text,label)=>{
      const button=document.createElement('button');
      button.type='button';
      button.textContent=text;
      button.setAttribute('aria-label',label);
      button.title=label;
      Object.assign(button.style,{
        flex:'0 0 38px',width:'38px',height:'38px',
        border:'1px solid rgba(215,235,242,.35)',borderRadius:'22px',
        background:'rgba(9,20,33,.92)',color:'#f0ffff',
        fontSize:'19px',fontWeight:'700',padding:'0',
        display:'flex',alignItems:'center',justifyContent:'center',
        appearance:'none',WebkitAppearance:'none',
        zIndex:'2',position:'relative',
        cursor:'pointer',pointerEvents:'auto',touchAction:'manipulation'
      });
      // X's author layer has pointer and click handlers. Prevent them
      // receiving new control gestures, but keep our own click handler active.
      for(const type of ['pointerdown','pointerup','touchstart','touchend']){
        button.addEventListener(type,event=>event.stopPropagation());
      }
      return button;
    };
    const play=control('▶','播放影片');
    const pause=control('❚❚','暫停影片');
    const middle=document.createElement('div');
    Object.assign(middle.style,{
      flex:'1 1 auto',minWidth:'0',display:'flex',
      flexDirection:'column',justifyContent:'center',gap:'3px'
    });
    const clocks=document.createElement('div');
    Object.assign(clocks.style,{
      display:'flex',justifyContent:'space-between',
      color:'#f1fbff',fontSize:'12px',fontFamily:'system-ui,sans-serif',
      lineHeight:'16px',textShadow:'0 1px 3px rgba(0,0,0,.85)',
      fontVariantNumeric:'tabular-nums',pointerEvents:'none'
    });
    const elapsed=document.createElement('span');
    const duration=document.createElement('span');
    const elapsedNode=document.createTextNode('0:00');
    const durationNode=document.createTextNode('--:--');
    elapsed.appendChild(elapsedNode);duration.appendChild(durationNode);
    clocks.appendChild(elapsed);clocks.appendChild(duration);
    const zone=document.createElement('div');
    zone.setAttribute('role','slider');
    zone.setAttribute('aria-label','自訂影片播放進度');
    zone.setAttribute('aria-valuemin','0');
    zone.setAttribute('aria-valuemax','100');
    zone.setAttribute('aria-valuenow','0');
    zone.tabIndex=0;
    Object.assign(zone.style,{
      height:'27px',width:'100%',display:'flex',alignItems:'center',
      touchAction:'none',pointerEvents:'auto',cursor:'pointer'
    });
    const line=document.createElement('div');
    Object.assign(line.style,{
      background:'rgba(219,233,240,.62)',width:'100%',
      height:'5px',borderRadius:'5px',overflow:'hidden'
    });
    const fill=document.createElement('div');
    Object.assign(fill.style,{
      height:'100%',width:'0%',background:'#27DBB3',
      borderRadius:'5px'
    });
    line.appendChild(fill);zone.appendChild(line);
    middle.appendChild(clocks);middle.appendChild(zone);
    root.appendChild(play);root.appendChild(middle);root.appendChild(pause);
    // Stay above X's author/content portal. X mounts its video UI under
    // #layers; placing our controls at the end of that layer avoids
    // underlying profile links intercepting taps.
    (document.getElementById('layers')||document.body||
      document.documentElement).appendChild(root);
    state.root=root;state.zone=zone;state.line=line;state.fill=fill;
    state.playButton=play;state.pauseButton=pause;
    state.elapsedNode=elapsedNode;state.durationNode=durationNode;
    state.lastFraction=-1;

    // X sometimes keeps its own React-controlled play state. Dispatch the
    // equivalent action through X's original toggle (hidden visually but
    // preserved), then set HTMLVideoElement state as an idempotent fallback.
    const setPlayback=shouldPlay=>{
      const video=state.video;
      if(!video || (shouldPlay?!video.paused:video.paused))return;
      const native=state.nativeToggle;
      if(native?.isConnected){
        try{native.click();}catch(_){}
      }
      try{
        if(shouldPlay){
          if(video.paused){
            const result=video.play();
            result?.catch?.(()=>{});
          }
        }else if(!video.paused)video.pause();
      }catch(_){}
      state.updateClock?.();
    };
    play.addEventListener('click',event=>{
      event.preventDefault();event.stopPropagation();
      setPlayback(true);
    });
    pause.addEventListener('click',event=>{
      event.preventDefault();event.stopPropagation();
      setPlayback(false);
    });

    const setTime=event=>{
      const v=state.seekVideo;
      if (!v || !Number.isFinite(v.duration) || v.duration<=0) return;
      const r=zone.getBoundingClientRect();
      if (!r.width) return;
      const fraction=Math.max(0,Math.min(1,(event.clientX-r.left)/r.width));
      let target=fraction*v.duration;
      if (v.seekable?.length) {
        const earliest=v.seekable.start(0);
        const latest=v.seekable.end(v.seekable.length-1);
        if (latest>=earliest) target=Math.max(earliest,Math.min(latest,target));
      }
      try { v.currentTime=target; } catch (_) {}
      state.updateProgress(fraction);
      state.updateClock?.();
    };
    zone.addEventListener('pointerdown',event=>{
      const v=state.video;
      if (!v || !Number.isFinite(v.duration) || v.duration<=0) return;
      event.preventDefault();event.stopPropagation();
      state.seeking=true;state.seekVideo=v;state.pointerId=event.pointerId;
      try { zone.setPointerCapture(event.pointerId); } catch (_) {}
      setTime(event);
    });
    zone.addEventListener('pointermove',event=>{
      if (!state.seeking || event.pointerId!==state.pointerId) return;
      event.preventDefault();event.stopPropagation();setTime(event);
    });
    const finish=event=>{
      if (!state.seeking || event.pointerId!==state.pointerId) return;
      event.preventDefault();event.stopPropagation();
      if (event.type==='pointerup') setTime(event);
      try { zone.releasePointerCapture(event.pointerId); } catch (_) {}
      state.seeking=false;state.seekVideo=null;state.pointerId=null;
      state.updateClock?.();
    };
    zone.addEventListener('pointerup',finish);
    zone.addEventListener('pointercancel',finish);
    zone.addEventListener('keydown',event=>{
      const v=state.video;
      if (!v || !Number.isFinite(v.duration) || v.duration<=0) return;
      const step=event.key==='ArrowRight'?5:event.key==='ArrowLeft'?-5:0;
      if (!step) return;
      event.preventDefault();event.stopPropagation();
      try { v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+step)); }
      catch (_) {}
    });
  };

  state.updateProgress=fraction=>{
    if (!state.fill || !state.zone) return;
    const safe=Math.max(0,Math.min(1,Number.isFinite(fraction)?fraction:0));
    if (Math.abs(safe-state.lastFraction)>0.0008) {
      state.fill.style.width=(100*safe).toFixed(2)+'%';
      state.zone.setAttribute('aria-valuenow',String(Math.round(100*safe)));
      state.lastFraction=safe;
    }
  };

  // Use existing <video> media events to update time without timers, video
  // reloads or WebView polling. Text node changes don't trigger childList.
  const clock=seconds=>{
    if(!Number.isFinite(seconds)||seconds<0)return '--:--';
    const n=Math.floor(seconds);
    const h=Math.floor(n/3600),m=Math.floor(n%3600/60),sec=n%60;
    const pad=v=>String(v).padStart(2,'0');
    return h>0?h+':'+pad(m)+':'+pad(sec):m+':'+pad(sec);
  };
  state.updateClock=()=>{
    const video=state.video;
    if(!video||!state.elapsedNode||!state.durationNode)return;
    const elapsed=clock(video.currentTime);
    const duration=clock(video.duration);
    if(state.elapsedNode.nodeValue!==elapsed)state.elapsedNode.nodeValue=elapsed;
    if(state.durationNode.nodeValue!==duration)state.durationNode.nodeValue=duration;
    if(state.playButton&&state.pauseButton){
      state.playButton.style.opacity=video.paused?'1':'.73';
      state.pauseButton.style.opacity=video.paused?'.73':'1';
    }
  };
  const videoEvents=[
    'timeupdate','durationchange','loadedmetadata','seeking','seeked',
    'play','pause','playing','ended'
  ];
  const bindVideo=video=>{
    if(state.boundVideo===video)return;
    if(state.boundVideo&&state.boundListener){
      for(const name of videoEvents){
        state.boundVideo.removeEventListener(name,state.boundListener);
      }
    }
    state.boundVideo=video;
    if(!video)return;
    if(!state.boundListener){
      state.boundListener=()=>{
        const active=state.video;
        if(!active)return;
        state.updateClock?.();
        if(!state.seeking&&Number.isFinite(active.duration)&&active.duration>0)
          state.updateProgress(active.currentTime/active.duration);
      };
    }
    for(const name of videoEvents)video.addEventListener(name,state.boundListener);
  };

  const currentVideo=()=>{
    const immersive=document.querySelector(
      '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"]');
    const dialog=document.querySelector(
      '#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])');
    const fullscreen=!!window.__mydownloadVideoFullscreen?.enabled;
    const videoRoute=/\/status\/\d+\/(?:video|photo)\/\d+\/?$/.test(location.pathname) ||
      /^\/i\/status\/\d+\/?$/.test(location.pathname);
    if (!immersive && !dialog && !fullscreen && !videoRoute) return null;
    const cx=innerWidth/2,cy=innerHeight/2;
    let winner=null,rank=-Infinity;
    for (const v of document.querySelectorAll('video')) {
      const r=v.getBoundingClientRect();
      const visW=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
      const visH=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
      if (visW<110||visH<65) continue;
      const centered=cx>=r.left&&cx<=r.right&&cy>=r.top&&cy<=r.bottom;
      const score=(centered?1e6:0)+visW*visH-
        Math.abs((r.top+r.bottom)/2-cy)*200;
      if (score>rank){winner=v;rank=score;}
    }
    return winner;
  };

  const hideNativeTracks=video=>{
    // Never clear existing marks on a normal X refresh: that briefly paints
    // the original progress before it is found again.
    for (const el of state.oldTracks) {
      if (!el.isConnected) state.oldTracks.delete(el);
    }
    if (!video || !settings.hideNative) return;
    const vr=video.getBoundingClientRect();
    let scope=video.closest(
      '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],' +
      '#layers [role="dialog"]');
    if (!scope) {
      scope=video.parentElement;
      for (let i=0;i<3&&scope?.parentElement &&
          scope.parentElement!==document.body;i++) scope=scope.parentElement;
    }
    if (!scope) return;
    const minWidth=Math.max(105,Math.min(180,vr.width*0.38));
    const minTop=Math.max(vr.top+vr.height*0.43,innerHeight*0.52);
    let matches=0;
    const mark=el=>{
      if (!el || el.closest('#mydownload-optional-x-progress')) return false;
      if (el.closest('button,[role="button"],a,nav,[role="navigation"]')) return false;
      if (el.querySelector('video,button,[role="button"]')) return false;
      const r=el.getBoundingClientRect();
      if (r.width<minWidth || r.height<=0 || r.height>32 ||
          r.top<minTop || r.top>=innerHeight ||
          r.right<=0 || r.left>=innerWidth) return false;
      if (Math.min(r.right,vr.right)-Math.max(r.left,vr.left)<minWidth*.55)
        return false;
      const label=[
        el.getAttribute('aria-label')||'',
        el.getAttribute('data-testid')||'',
        typeof el.className==='string'?el.className:''
      ].join(' ').toLowerCase();
      if (/volume|speaker|mute|audio|音量|靜音|download|下載/.test(label)) return false;
      const explicit=/seek|scrub|timeline|progress|播放進度|播放位置|時間軸|進度/.test(label) ||
        el.matches('[role="slider"],input[type="range"]');
      if (!explicit) {
        // Do not hide arbitrary controls; only an unlabelled, painted
        // thin line in the active video's lower area can be a seek track.
        if (r.height>7) return false;
        const cs=getComputedStyle(el);
        const color=cs.backgroundColor;
        const painted=color && color!=='transparent' &&
          color!=='rgba(0, 0, 0, 0)' && color!=='rgba(0,0,0,0)';
        if (!painted && cs.backgroundImage==='none') return false;
      }
      el.setAttribute('data-mydownload-x-native-track','1');
      state.oldTracks.add(el);
      return true;
    };
    // X has both semantic sliders and unlabelled narrow tracks. The
    // latter are searched ONLY within the current viewer, not the feed.
    const selectors=[
      '[role="slider"]','input[type="range"]',
      '[data-testid*="seek" i]','[data-testid*="scrub" i]',
      '[data-testid*="progress" i]','[data-testid*="timeline" i]',
      '[aria-label*="seek" i]','[aria-label*="timeline" i]'
    ].join(',');
    for (const el of scope.querySelectorAll(selectors)) {
      if (mark(el)) matches++;
    }
    // Avoid broad style changes: mark only elements up to seven pixels
    // tall, never their parent containers (which can paint the video black).
    if (matches===0) {
      const elements=scope.querySelectorAll('div,span');
      for (let i=0;i<Math.min(elements.length,1400);i++) {
        const el=elements[i];
        const r=el.getBoundingClientRect();
        if (r.height>0&&r.height<=7 && mark(el))matches++;
        if (matches>=3) break;
      }
    }
  };

  // Only the native play/pause icon is removed while our own controls
  // are visible. It is kept in the DOM so a programmatic click can still
  // change X's React playback state. No parent/control bar is hidden.
  const clearNativeToggle=()=>{
    for(const el of state.nativeToggleMarks){
      if(el.isConnected)el.removeAttribute('data-mydownload-x-native-toggle');
    }
    state.nativeToggleMarks.clear();
    state.nativeToggle=null;
    document.documentElement.removeAttribute('data-mydownload-hide-x-native-toggle');
  };
  const locateNativeToggle=video=>{
    if(!video)return;
    const vr=video.getBoundingClientRect();
    const bottom=innerWidth>innerHeight?24:68;
    const barTop=innerHeight-bottom-56;
    const low=Math.max(vr.top+vr.height*.55,barTop-225);
    const high=Math.min(innerHeight-36,barTop+35);
    const candidates=document.querySelectorAll(
      'button,[role="button"],[data-testid*="playButton" i],' +
      '[data-testid*="pauseButton" i]');
    let winner=null,best=-Infinity;
    for(const el of candidates){
      if(el.closest('#mydownload-optional-x-progress') ||
          el.closest('nav,[role="navigation"]') ||
          el.querySelector('video'))continue;
      const r=el.getBoundingClientRect();
      if(r.width<12||r.width>96||r.height<12||r.height>82||
         r.top<low||r.top>high||r.left<vr.left-8||
         r.right>Math.min(vr.left+Math.max(130,vr.width*.38),innerWidth))continue;
      const descriptor=[
        el.getAttribute('aria-label')||'',
        el.getAttribute('title')||'',
        el.getAttribute('data-testid')||''
      ].join(' ').toLowerCase();
      const explicit=/^(play|pause|播放|暫停)$/.test(descriptor.trim()) ||
        /(?:play|pause)button|video.?play|video.?pause/.test(descriptor);
      const excluded=/volume|mute|share|like|follow|back|close|sound|more|下載|音量|分享/.test(descriptor);
      if(excluded)continue;
      const viewer=video.closest(
        '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],' +
        '#layers [role="dialog"],[data-testid="videoPlayer"]');
      if(viewer && !viewer.contains(el) && !el.closest('#layers'))continue;
      // Prefer positively identified playback controls; permit an
      // unlabelled small SVG control only immediately above our seekbar.
      if(!explicit){
        if(!el.querySelector('svg') || r.width>58||r.height>58||
           r.top<barTop-145||r.top>barTop+8)continue;
      }
      const score=(explicit?10000:0)-Math.abs(r.top-(barTop-95))*8-r.left;
      if(score>best){best=score;winner=el;}
    }
    if(!winner)return;
    for(const el of state.nativeToggleMarks){
      if(el!==winner && el.isConnected){
        el.removeAttribute('data-mydownload-x-native-toggle');
        state.nativeToggleMarks.delete(el);
      }
    }
    winner.setAttribute('data-mydownload-x-native-toggle','1');
    state.nativeToggleMarks.add(winner);
    state.nativeToggle=winner;
    document.documentElement.setAttribute('data-mydownload-hide-x-native-toggle','1');
  };

  // Hide only the profile shortcut link in X's active video viewer.
  // A valid X profile URL has one path segment (@handle), unlike status links.
  // Geometry limits removal to a small clickable target near the custom bar.
  const clearAuthor=()=>{
    for(const el of state.authorLinks){
      if(el.isConnected)el.removeAttribute('data-mydownload-x-author-shortcut');
    }
    state.authorLinks.clear();
    document.documentElement.removeAttribute('data-mydownload-hide-x-author');
  };
  const markAuthor=video=>{
    if(!video||!settings.hideAuthor)return;
    const viewer=video.closest(
      '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],' +
      '#layers [role="dialog"]');
    const layers=document.getElementById('layers');
    // X's author/footer is often a sibling of the active video, not a
    // descendant of its player. Restrict by media viewer + screen location.
    if(!viewer && !layers)return;
    for(const el of state.authorLinks){
      if(!el.isConnected)state.authorLinks.delete(el);
    }
    const bottom=innerWidth>innerHeight?24:68;
    const barTop=innerHeight-bottom-56;
    const minY=Math.max(innerHeight*.47,barTop-160);
    const maxY=Math.min(innerHeight-10,barTop+105);
    const candidates=document.querySelectorAll(
      'a[href],[data-testid="User-Name"],[data-testid="UserName"],' +
      '[data-testid="User-Names"],[data-testid^="UserAvatar-Container"]'
    );
    for(const candidate of candidates){
      if(candidate.closest('#mydownload-optional-x-progress'))continue;
      if(candidate.querySelector('video'))continue;
      if(viewer && !viewer.contains(candidate) &&
         !(layers?.contains(candidate)))continue;
      const r=candidate.getBoundingClientRect();
      if(r.width<8||r.width>innerWidth-8||r.height<8||r.height>90||
         r.left<0||r.left>innerWidth*.63||
         r.top<minY||r.top>maxY||r.bottom>innerHeight+5)continue;
      let isProfile=false;
      if(candidate.matches('a[href]')){
        let target;
        try{target=new URL(candidate.getAttribute('href'),location.href);}
        catch(_){continue;}
        if(target.hostname!==location.hostname)continue;
        if(!/^\/[A-Za-z0-9_]{1,15}\/?$/.test(target.pathname))continue;
        const handle=target.pathname.slice(1).replace(/\/$/,'').toLowerCase();
        isProfile=!/^(home|explore|search|notifications|messages|compose|settings|i|grok|login)$/.test(handle);
      }else{
        isProfile=true; // Exact X User-Name/avatar testids only.
      }
      if(!isProfile)continue;
      candidate.setAttribute('data-mydownload-x-author-shortcut','1');
      state.authorLinks.add(candidate);
    }
  };

  // Markers and observer are active only while the user enables the option.
  // Set the root flag immediately, even before loadedmetadata, so X's next
  // seekbar is hidden by the persistent CSS on its very first paint.
  if (settings.hideNative) {
    document.documentElement.setAttribute('data-mydownload-hide-x-timeline','1');
  } else {
    clearNative();
  }
  if(settings.hideAuthor){
    document.documentElement.setAttribute('data-mydownload-hide-x-author','1');
  } else {
    clearAuthor();
  }
  if(!settings.hideNative&&!settings.hideAuthor&&!settings.showCustom){
    state.observer?.disconnect();
    state.observer=null;
  }

  state.refresh=()=>{
    if (state.busy) return;
    state.busy=true;
    try {
      const video=currentVideo();
      const playable=video && Number.isFinite(video.duration) &&
        video.duration>0 && video.readyState>=1;
      if (settings.hideNative && video) hideNativeTracks(video);
      if (settings.hideAuthor && video) markAuthor(video);
      if (!settings.showCustom || !playable) {
        clearNativeToggle();
        if (state.root) state.root.style.display='none';
        state.video=null;bindVideo(null);
        state.seeking=false;state.seekVideo=null;
        return 'native-only';
      }
      create();
      state.video=video;
      bindVideo(video);
      state.root.style.bottom=(innerWidth>innerHeight?'24px':'68px');
      state.root.style.display='flex';
      locateNativeToggle(video);
      if (!state.seeking) state.updateProgress(video.currentTime/video.duration);
      state.updateClock();
      return 'custom-visible';
    } finally {
      state.busy=false;
    }
  };

  // X's vertical carousel mounts the next controls without a page reload.
  // A MutationObserver runs at the DOM microtask checkpoint, before paint,
  // unlike the browser's existing 950ms refresh. Observe only structural
  // changes, and never touch the video or controls container.
  if ((settings.hideNative || settings.hideAuthor || settings.showCustom) && !state.observer) {
    state.observer=new MutationObserver(mutations=>{
      if (!(state.settings?.hideNative || state.settings?.hideAuthor ||
            state.settings?.showCustom) ||
          !mutations.some(m=>m.addedNodes.length||m.removedNodes.length))return;
      state.refresh?.();
    });
    state.observer.observe(document.documentElement,{
      childList:true,subtree:true
    });
  }
  return state.refresh();
})()
""".trimIndent()
