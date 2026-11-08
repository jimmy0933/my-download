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
        ",showCustom:" + options.showCustomXVideoProgress + "}"
    return X_OPTIONAL_VIDEO_PROGRESS_SCRIPT.replace("__SETTINGS__", settings)
}

private val X_OPTIONAL_VIDEO_PROGRESS_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return 'not-x';

  const settings = __SETTINGS__;
  const state = window.__mydownloadOptionalXSeek ||
    (window.__mydownloadOptionalXSeek = {
      root:null,zone:null,line:null,fill:null,video:null,seekVideo:null,
      seeking:false,pointerId:null,oldTracks:new Set(),lastFraction:-1
    });

  const cssId='mydownload-optional-native-seek-css';
  if (!document.getElementById(cssId)) {
    const style=document.createElement('style');
    style.id=cssId;
    style.textContent=
      'html[data-mydownload-hide-x-timeline="1"] ' +
      '[data-mydownload-x-native-track="1"] {' +
      'opacity:0 !important;pointer-events:none !important;}';
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
    // A narrow fixed bar with no filter, transform, video overlay or
    // full-screen background: only the 30px seek zone handles touches.
    const root=document.createElement('div');
    root.id='mydownload-optional-x-progress';
    Object.assign(root.style,{
      position:'fixed',left:'14px',right:'14px',
      bottom:'68px',height:'30px',zIndex:'2147482000',
      display:'none',pointerEvents:'none',background:'transparent'
    });
    const zone=document.createElement('div');
    zone.setAttribute('role','slider');
    zone.setAttribute('aria-label','自訂影片播放進度');
    zone.setAttribute('aria-valuemin','0');
    zone.setAttribute('aria-valuemax','100');
    zone.setAttribute('aria-valuenow','0');
    zone.tabIndex=0;
    Object.assign(zone.style,{
      height:'30px',width:'100%',display:'flex',alignItems:'center',
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
    line.appendChild(fill);zone.appendChild(line);root.appendChild(zone);
    (document.body||document.documentElement).appendChild(root);
    state.root=root;state.zone=zone;state.line=line;state.fill=fill;
    state.lastFraction=-1;

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
    clearNative();
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
    if (matches) document.documentElement.setAttribute(
      'data-mydownload-hide-x-timeline','1');
  };

  const video=currentVideo();
  const playable=video && Number.isFinite(video.duration) &&
    video.duration>0 && video.readyState>=1;
  // The two options are independent: users can show both or neither.
  if (!settings.hideNative && state.oldTracks.size) clearNative();
  if (settings.hideNative && playable) hideNativeTracks(video);
  else clearNative();
  if (!settings.showCustom || !playable) {
    if (state.root) state.root.style.display='none';
    state.video=null;
    state.seeking=false;state.seekVideo=null;
    return 'native-only';
  }
  create();
  state.video=video;
  state.root.style.bottom=(innerWidth>innerHeight?'24px':'68px');
  state.root.style.display='block';
  if (!state.seeking) state.updateProgress(video.currentTime/video.duration);
  return 'custom-visible';
})()
""".trimIndent()
