package com.jimmy.mydownload

/**
 * Overlay is independent of X's vertical carousel and never resizes, scrolls
 * or reloads the video. Only the narrow progress area accepts touch events.
 */
internal fun xVideoOverlayScript(options: XDisplayOptions): String {
    val config = "{enabled:" + options.showVideoProgressOverlay +
        ",portraitBottom:" + options.progressPortraitBottomDp.coerceIn(4, 140) +
        ",landscapeBottom:" + options.progressLandscapeBottomDp.coerceIn(4, 100) +
        ",thickness:" + options.progressThicknessDp.coerceIn(2, 14) +
        ",opacity:" + options.progressOpacityPercent.coerceIn(20, 100) +
        ",showResolution:" + options.showVideoResolution + "}"
    return X_VIDEO_PROGRESS_OVERLAY_SCRIPT.replace("__CONFIG__", config)
}

private val X_VIDEO_PROGRESS_OVERLAY_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname))
    return 'not-x';
  const config=__CONFIG__;
  const state=window.__mydownloadXProgress ||
    (window.__mydownloadXProgress={
      root:null,zone:null,line:null,fill:null,label:null,
      video:null,seekVideo:null,scrubbing:false,pointerId:null,
      timer:0,visible:false,options:null,lastProgress:-1
    });
  state.options=config;

  // X still renders its own seek/timeline control. Hide just that native
  // timeline while our functional replacement is visible; don't hide the
  // playback controls, volume slider or loading indicator. Visibility (rather
  // than display) also avoids shifting X's carousel/controls in landscape.
  const nativeStyleId='mydownload-hide-native-seek';
  let nativeStyle=document.getElementById(nativeStyleId);
  if(!nativeStyle){
    nativeStyle=document.createElement('style');
    nativeStyle.id=nativeStyleId;
    nativeStyle.textContent=`
      html[data-mydownload-custom-progress="1"]
      :is([data-testid="videoPlayer"],[data-testid="vss-scroll-view"],
          [data-testid="immersive-viewer"],#layers [role="dialog"]:has(video))
      :is(
        [role="slider"]:not([aria-label*="volume" i]):not([aria-label*="音量"]),
        input[type="range"]:not([aria-label*="volume" i]):not([aria-label*="音量"]),
        [data-testid*="seek" i],[data-testid*="scrub" i],
        [data-testid*="progress" i]:not([role="progressbar"]),
        [aria-label*="seek" i],[aria-label*="timeline" i],
        [aria-label*="播放進度"]
      ),
      html[data-mydownload-custom-progress="1"]
      :is([data-testid*="video-progress" i],
          [data-testid*="video-seek" i],
          [data-testid*="video-scrub" i])
      { visibility:hidden !important; pointer-events:none !important; }
      html[data-mydownload-custom-progress="1"]
      [data-mydownload-native-seek="1"],
      html[data-mydownload-custom-progress="1"]
      [data-mydownload-native-seek="1"]::before,
      html[data-mydownload-custom-progress="1"]
      [data-mydownload-native-seek="1"]::after
      { visibility:hidden !important; pointer-events:none !important; }
      html[data-mydownload-custom-progress="1"]
      video::-webkit-media-controls-timeline
      { display:none !important; }
    `;
    (document.head||document.documentElement).appendChild(nativeStyle);
  }
  const showCustomTimeline=visible=>{
    if(visible)document.documentElement.setAttribute(
      'data-mydownload-custom-progress','1');
    else document.documentElement.removeAttribute(
      'data-mydownload-custom-progress');
  };

  // X renders its video seek track in several different layers. In some
  // immersive viewers it is a plain div, a sibling of videoPlayerControls,
  // and not a [role=slider]. Styling only videoPlayer descendants missed it.
  // Locate narrow, wide seek tracks near the ACTIVE video, without touching
  // play/pause, volume, fullscreen or the vertical-swipe carousel.
  state.nativeSeekMarks=state.nativeSeekMarks||new Set();
  const clearNativeSeeks=()=>{
    for(const el of state.nativeSeekMarks){
      if(el.isConnected)el.removeAttribute('data-mydownload-native-seek');
    }
    state.nativeSeekMarks.clear();
  };
  const nativeCandidates=[
    '[role="slider"]','input[type="range"]',
    '[data-testid*="seek" i]','[data-testid*="scrub" i]',
    '[data-testid*="progress" i]','[data-testid*="timeline" i]',
    '[aria-label*="seek" i]','[aria-label*="scrub" i]',
    '[aria-label*="timeline" i]','[aria-label*="progress" i]',
    '[aria-label*="進度"]','[aria-label*="時間軸"]',
    '[class*="seek" i]','[class*="scrub" i]',
    '[class*="progress" i]','progress'
  ].join(',');
  const markNativeSeeks=video=>{
    if(!video){clearNativeSeeks();state.nativeSeekVideo=null;return;}
    const now=Date.now();
    if(state.nativeSeekVideo===video && now-(state.nativeSeekAt||0)<700)return;
    state.nativeSeekVideo=video;state.nativeSeekAt=now;
    clearNativeSeeks();

    const vr=video.getBoundingClientRect();
    const threshold=Math.max(106,Math.min(200,vr.width*0.4));
    const earliestY=Math.max(0,Math.min(vr.top+vr.height*0.40,innerHeight*0.62)-40);
    const inTrackArea=node=>{
      if(!node.isConnected||node.closest('#mydownload-video-progress'))return false;
      if(node.closest('button,[role="button"],nav,[role="navigation"]'))return false;
      const r=node.getBoundingClientRect();
      if(r.width<threshold||r.height<1||r.height>58)return false;
      if(r.top<earliestY||r.top>=innerHeight||r.bottom<=0)return false;
      if(Math.min(r.right,innerWidth)<=0||r.left>=innerWidth)return false;
      const overlap=Math.min(r.right,vr.right)-Math.max(r.left,vr.left);
      return overlap>=Math.min(72,vr.width*0.28);
    };
    const mark=node=>{
      if(state.nativeSeekMarks.has(node))return;
      let target=node;
      const parent=node.parentElement;
      if(parent && parent!==document.body && parent!==document.documentElement &&
          !parent.querySelector('button,[role="button"],video')){
        const r=parent.getBoundingClientRect(),n=node.getBoundingClientRect();
        if(r.height>0&&r.height<=42&&r.width>=n.width*0.8)target=parent;
      }
      target.setAttribute('data-mydownload-native-seek','1');
      state.nativeSeekMarks.add(target);
    };
    // The semantic search is global because X can portal controls outside
    // videoPlayer and even outside #layers. Geometry keeps other posts intact.
    for(const node of document.querySelectorAll(nativeCandidates)){
      if(!inTrackArea(node))continue;
      const label=((node.getAttribute('aria-label')||'')+' '+
        (node.getAttribute('data-testid')||'')+' '+
        (typeof node.className==='string'?node.className:'')).toLowerCase();
      if(/volume|speaker|mute|sound|音量|靜音|亮度/.test(label))continue;
      const slider=node.matches('[role="slider"],input[type="range"]');
      const seekName=/seek|scrub|timeline|progress|播放進度|播放位置|時間軸|進度/.test(label);
      // Ignore download/loading progress bars, which are not video timelines.
      if(!slider&&!seekName)continue;
      mark(node);
    }

    // The immersive X timeline can be an unlabelled 2-6px line. Inspect
    // only the selected player's subtree, never the entire feed.
    const player=video.closest(
      '[data-testid="videoPlayer"],[data-testid="videoComponent"]');
    const viewer=video.closest(
      '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],'+
      '#layers [role="dialog"],[data-testid="photoViewer"]');
    let scope=viewer||player;
    if(!scope){
      scope=video;
      for(let hop=0;hop<6&&scope.parentElement;hop++)scope=scope.parentElement;
    }
    const nodes=scope.querySelectorAll('div,span');
    const count=Math.min(nodes.length,1800);
    for(let i=0;i<count;i++){
      const node=nodes[i];
      if(!inTrackArea(node))continue;
      const r=node.getBoundingClientRect();
      if(r.height>12)continue;
      const style=getComputedStyle(node);
      if(style.display==='none'||Number(style.opacity)===0)continue;
      const color=style.backgroundColor;
      const before=getComputedStyle(node,'::before');
      const after=getComputedStyle(node,'::after');
      const colored=value=>!!value&&value!=='transparent'&&
        value!=='rgba(0, 0, 0, 0)'&&value!=='rgba(0,0,0,0)';
      const trackPaint=colored(color)||style.backgroundImage!=='none'||
        (before.content!=='none'&&colored(before.backgroundColor))||
        (after.content!=='none'&&colored(after.backgroundColor));
      if(!trackPaint)continue;
      mark(node);
    }
  };

  const create=()=>{
    if(state.root?.isConnected)return;
    const root=document.createElement('div');
    root.id='mydownload-video-progress';
    root.setAttribute('aria-label','自訂影片進度條');
    Object.assign(root.style,{
      position:'fixed',zIndex:'2147483500',left:'16px',right:'16px',
      pointerEvents:'none',boxSizing:'border-box',display:'none'
    });
    const label=document.createElement('div');
    label.setAttribute('aria-label','目前影片實際解析度');
    Object.assign(label.style,{
      display:'none',width:'fit-content',marginBottom:'3px',
      padding:'3px 7px',borderRadius:'8px',
      background:'rgba(8,17,27,.64)',fontSize:'11px',
      fontWeight:'600',lineHeight:'16px',color:'#e1f5f4',
      fontFamily:'system-ui,sans-serif',whiteSpace:'nowrap'
    });
    const zone=document.createElement('div');
    zone.setAttribute('role','slider');
    zone.setAttribute('aria-label','影片播放進度，拖曳可快轉');
    zone.setAttribute('aria-valuemin','0');
    zone.setAttribute('aria-valuemax','100');
    zone.setAttribute('aria-valuenow','0');
    zone.tabIndex=0;
    Object.assign(zone.style,{
      width:'100%',height:'26px',display:'flex',alignItems:'center',
      position:'relative',pointerEvents:'auto',touchAction:'none',
      cursor:'pointer',boxSizing:'border-box'
    });
    const line=document.createElement('div');
    Object.assign(line.style,{
      position:'relative',width:'100%',borderRadius:'999px',
      background:'rgba(216,232,241,.42)',overflow:'hidden',
      boxShadow:'0 1px 5px rgba(0,0,0,.35)'
    });
    const fill=document.createElement('div');
    Object.assign(fill.style,{
      width:'0%',height:'100%',borderRadius:'999px',
      background:'#29d9c0',transition:'width 130ms linear'
    });
    line.appendChild(fill);
    zone.appendChild(line);
    root.appendChild(label);
    root.appendChild(zone);
    (document.body||document.documentElement).appendChild(root);
    state.root=root;state.zone=zone;state.line=line;state.fill=fill;
    state.label=label;state.lastProgress=-1;

    const seek=event=>{
      const video=state.seekVideo;
      if(!video || !Number.isFinite(video.duration) || video.duration<=0)return;
      const rect=zone.getBoundingClientRect();
      if(rect.width<=0)return;
      const fraction=Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width));
      let time=fraction*video.duration;
      const seekable=video.seekable;
      if(seekable && seekable.length){
        const first=seekable.start(0);
        const last=seekable.end(seekable.length-1);
        if(last>first)time=Math.max(first,Math.min(last,time));
      }
      if(Number.isFinite(time)){
        try{video.currentTime=time;}catch(_){}
        state.renderProgress(fraction);
      }
    };
    zone.addEventListener('pointerdown',event=>{
      const video=state.video;
      if(!video || !Number.isFinite(video.duration) || video.duration<=0)return;
      event.preventDefault();event.stopPropagation();
      state.seekVideo=video;
      state.scrubbing=true;
      state.pointerId=event.pointerId;
      try{zone.setPointerCapture(event.pointerId);}catch(_){}
      seek(event);
    });
    zone.addEventListener('pointermove',event=>{
      if(!state.scrubbing||state.pointerId!==event.pointerId)return;
      event.preventDefault();event.stopPropagation();seek(event);
    });
    const stop=event=>{
      if(!state.scrubbing||state.pointerId!==event.pointerId)return;
      event.preventDefault();event.stopPropagation();
      if(event.type==='pointerup')seek(event);
      try{zone.releasePointerCapture(event.pointerId);}catch(_){}
      state.scrubbing=false;state.pointerId=null;state.seekVideo=null;
    };
    zone.addEventListener('pointerup',stop);
    zone.addEventListener('pointercancel',stop);
    zone.addEventListener('keydown',event=>{
      const video=state.video;
      if(!video || !Number.isFinite(video.duration)||video.duration<=0)return;
      const amount=event.key==='ArrowLeft'?-5:event.key==='ArrowRight'?5:0;
      if(!amount)return;
      event.preventDefault();event.stopPropagation();
      try{video.currentTime=Math.max(0,Math.min(video.duration,video.currentTime+amount));}
      catch(_){}
    });
  };

  state.renderProgress=fraction=>{
    const percent=Math.max(0,Math.min(100,fraction*100));
    if(Math.abs(percent-state.lastProgress)>0.08){
      state.fill.style.width=percent.toFixed(2)+'%';
      state.lastProgress=percent;
      state.zone.setAttribute('aria-valuenow',String(Math.round(percent)));
    }
  };
  const activeVideo=()=>{
    const viewer=!!window.__mydownloadVideoFullscreen?.enabled ||
      !!document.querySelector('[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"]') ||
      !!document.querySelector('#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])');
    if(!viewer)return null;
    const cx=innerWidth*0.5,cy=innerHeight*0.5;
    let best=null,score=-Infinity;
    for(const video of document.querySelectorAll('video')){
      const r=video.getBoundingClientRect();
      const visibleWidth=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
      const visibleHeight=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
      if(visibleWidth<110||visibleHeight<70)continue;
      const centered=cx>=r.left&&cx<=r.right&&cy>=r.top&&cy<=r.bottom;
      const rank=(centered?1000000:0)+visibleWidth*visibleHeight-
        Math.abs((r.top+r.bottom)/2-cy)*200+(video.paused?0:500);
      if(rank>score){best=video;score=rank;}
    }
    return best;
  };
  state.update=()=>{
    const opts=state.options;
    const video=activeVideo();
    const playable=video&&Number.isFinite(video.duration)&&
      video.duration>0&&video.readyState>=1;
    state.visible=!!playable;
    if(!playable){
      showCustomTimeline(false);
      markNativeSeeks(null);
      state.root.style.display='none';
      state.video=null;
      return;
    }
    state.video=video;
    state.root.style.display='block';
    showCustomTimeline(true);
    markNativeSeeks(video);
    state.root.style.opacity=String(opts.opacity/100);
    state.root.style.bottom=(innerWidth>innerHeight?
      opts.landscapeBottom:opts.portraitBottom)+'px';
    state.line.style.height=opts.thickness+'px';
    if(!state.scrubbing)state.renderProgress(video.currentTime/video.duration);
    if(opts.showResolution&&video.videoWidth>0&&video.videoHeight>0){
      state.label.textContent=video.videoWidth+' × '+video.videoHeight;
      state.label.style.display='block';
    }else{
      state.label.style.display='none';
    }
  };

  if(!config.enabled){
    showCustomTimeline(false);
    markNativeSeeks(null);
    clearTimeout(state.timer);state.timer=0;state.visible=false;
    if(state.root)state.root.style.display='none';
    return 'disabled';
  }
  create();
  state.update();
  if(!state.timer){
    const tick=()=>{
      state.timer=0;
      if(!state.options?.enabled)return;
      if(!state.root?.isConnected)create();
      state.update();
      state.timer=setTimeout(tick,state.visible?280:900);
    };
    state.timer=setTimeout(tick,state.visible?280:900);
  }
  return 'enabled';
})()
""".trimIndent()
