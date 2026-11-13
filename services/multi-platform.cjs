'use strict';
const fs=require('node:fs/promises');
const {cacheAudio,closeAudioDownloads}=require('./netease.cjs');
const PLATFORMS = ['kugou', 'netease', 'qq'];
const NAMES = {kugou:'酷狗概念版',netease:'网易云音乐',qq:'QQ 音乐'};
const platformOf = id => String(id).startsWith('netease:') ? 'netease' : String(id).startsWith('qq:') ? 'qq' : 'kugou';
const trackKey = track => JSON.stringify([track.name,track.artist,track.album || ''].map(value=>String(value).trim().toLowerCase()));
const FAVORITES='musxi:cloud-favorites';
function mergeTracks(tracks,groups=new Map()) {
  for(const track of tracks){
    const key=trackKey(track);
    let group=groups.get(key);
    if(!group){group={...track,sources:[]};groups.set(key,group);}
    const platform=platformOf(track.id);
    if(!group.sources.some(source=>platformOf(source.id)===platform))group.sources.push({...track,platform});
  }
  return [...groups.values()].map(group=>({...group,platforms:['netease','kugou','qq'].filter(p=>group.sources.some(source=>source.platform===p))}));
}
class MultiPlatform {
  constructor(adapters) { this.adapters=adapters; this.active='kugou'; this.sessions={}; this.accounts={}; this.playlists={}; this.favoriteKeys={}; this.searchKeywords=''; this.searchGroups=new Map(); this.readCache=new Map();this.readEpoch=0; }
  async close() {for(const controller of Object.values(this.startupJob?.controllers || {}))controller.abort();this.loading?.controller.abort();const pending=this.cancelPreparation();this.readCache.clear();for(const adapter of Object.values(this.adapters))adapter.stop?.('接口已关闭');await pending?.promise;await this.loading?.promise;await this.startupJob?.promise;await closeAudioDownloads(); }
  startStartup(request) {
    const previous=this.startupJob;
    for(const controller of Object.values(previous?.controllers || {}))controller.abort();
    const saved=request.session?.platform==='multi'?request.session.accounts:{kugou:request.session};
    this.sessions={...saved};this.accounts={};this.playlists={};this.favoriteKeys={};
    const job={pending:true,revision:0,controllers:{},tasks:{},snapshots:{},verified:{},vipClaimed:false};this.startupJob=job;
    const tracks=(request.tracks || []).flatMap(row=>[row,...(row.sources || [])]);
    for(const platform of PLATFORMS) {
      const controller=new AbortController();job.controllers[platform]=controller;
      this.accounts[platform]={connected:false,verifying:true,playlistsReady:false};
      this.playlists[platform]=(request.cachedPlaylists || []).filter(row=>row.platform===platform);
      job.tasks[platform]=(async()=>{
        const signal=controller.signal;
        try {
          await previous?.tasks[platform];signal.throwIfAborted();
          const result=await this.call(platform,{op:'init',profileDir:request.profileDir,session:saved?.[platform],tracks:tracks.filter(row=>platformOf(row.id)===platform),signal});
          signal.throwIfAborted();this.accounts[platform].verifying=false;
          if(!result.connected)return;
          const accountId=String(result.profile?.id || '');
          if(!accountId)throw Error('账号标识缺失');job.verified[platform]=accountId;
          if(accountId!==String(saved?.[platform]?.profile?.id || ''))this.playlists[platform]=[];
          job.revision++;
          await this.call(platform,{op:'sync',light:true,signal});
          this.accounts[platform].playlistsReady=true;
          if(platform==='kugou') {
            job.vipPromise=this.call(platform,{op:'vip_auto',signal}).then(result=>{job.vipClaimed=result.outcome==='claimed' && result.notify===true;},()=>{});
            await job.vipPromise;signal.throwIfAborted();job.revision++;
          }
          try{await this.readFavorites(platform,signal);job.revision++;}catch{signal.throwIfAborted();}
          if(platform==='kugou')try{await this.call(platform,{op:'playlist_covers',signal});}catch{signal.throwIfAborted();}
        } catch {
          if(!signal.aborted)this.accounts[platform]={...this.accounts[platform],verifying:false,error:`${NAMES[platform]}歌单未更新，请稍后重试`};
        } finally{job.revision++;}
      })();
    }
    job.promise=(async()=>{
      await Promise.allSettled(Object.values(job.tasks));
      if(this.startupJob!==job)return;
      if(!Object.values(job.controllers).some(controller=>controller.signal.aborted))await this.run({op:'avatar'},true).catch(()=>{});
      job.pending=false;job.revision++;
    })();
    return this.startupState();
  }
  startupState(revision) {
    const job=this.startupJob;if(!job)throw Error('启动同步已结束');
    const playlistsRefreshing=this.accountState().some(account=>account.verifying || account.connected && !account.playlistsReady && !account.error);
    return this.decorate({startupPending:job.pending,startupRevision:job.revision,playlistsRefreshing,
      ...(revision!==job.revision?{playlists:this.allPlaylists(),playlistSnapshots:job.snapshots,verifiedAccounts:job.verified,vipClaimed:job.vipClaimed,image:this.accounts.kugou?.avatar || ''}:{})});
  }
  async startTracks(request) {
    this.loading?.controller.abort();
    const platform=platformOf(request.id),key=request.id===FAVORITES?FAVORITES:JSON.stringify([platform,'tracks',request.id]);
    const cached=this.readCache.get(key);
    let firstBatch;const first=new Promise(resolve=>{firstBatch=resolve;});
    const job={token:request.token,controller:new AbortController(),status:'pending',revision:0,data:cached?structuredClone(cached.result):null};this.loading=job;
    job.promise=(async()=>{
      try {
        const data=await this.run({op:'tracks',id:request.id,refresh:true,signal:job.controller.signal,onProgress:data=>{
          if(!job.controller.signal.aborted && !cached){job.data=structuredClone(data);job.revision++;}
          firstBatch();
        }},true);
        job.controller.signal.throwIfAborted();job.data=data;job.status='completed';job.revision++;
      } catch {job.status='failed';job.revision++;job.error='歌单未完整加载，请重试；当前显示的是缓存或已加载部分';}
    })();
    if(!cached) {
      let timer;
      try{await Promise.race([first,job.promise,new Promise(resolve=>{timer=setTimeout(resolve,80);})]);}
      finally{clearTimeout(timer);}
    }
    return this.trackProgress(request.token);
  }
  trackProgress(token,revision) {
    const job=this.loading;
    if(!job || job.token!==token)throw Error('歌单加载已失效');
    return this.decorate({token,status:job.status,revision:job.revision,...(job.data && revision!==job.revision?structuredClone(job.data):{}),...(job.error?{error:job.error}:{})});
  }
  cancelPreparation(token) {
    const job=this.preparing;
    if(!job || token!==undefined && job.token!==token)return;
    this.preparing=null;job.controller.abort();
    if(job.data?.path)job.promise=job.promise.then(()=>fs.unlink(job.data.path).catch(()=>{}));
    return job;
  }
  prepareAudio(request) {
    this.cancelPreparation();const controller=new AbortController();
    const job={token:request.token,controller,status:'pending'};this.preparing=job;
    job.promise=(async()=>{
      try {
        // The QQ child has one response slot; let an already sent authorization finish before reusing it.
        if(this.adapters.qq?.pending)await this.audioResolution?.catch(()=>{});
        controller.signal.throwIfAborted();
        job.resolving=this.run({...request,op:'audio',cacheDir:undefined,signal:controller.signal},true);
        this.audioResolution=job.resolving;
        const audio=await job.resolving;controller.signal.throwIfAborted();
        const cached=await cacheAudio(audio.url,audio.track,audio.quality,request.cacheDir,controller.signal);
        if(controller.signal.aborted){await fs.unlink(cached.path).catch(()=>{});return;}
        job.data={...cached,...(audio.requestedId?{requestedId:audio.requestedId}:{}),platform:audio.platform};job.status='ready';
      } catch {if(!controller.signal.aborted){job.status='failed';job.error='下一首预加载失败，将按正常流程播放';}}
    })();
    return {token:job.token,status:'pending'};
  }
  accountState() { return PLATFORMS.map(platform=>({platform,name:NAMES[platform],connected:!!this.accounts[platform]?.connected,user:this.accounts[platform]?.profile?.name || '',error:this.accounts[platform]?.error || '',avatar:this.accounts[platform]?.connected ? this.accounts[platform]?.avatar || '' : '',...(this.startupJob?{verifying:!!this.accounts[platform]?.verifying,playlistsReady:!!this.accounts[platform]?.playlistsReady}:{}),...(this.accounts[platform]?.connected && this.accounts[platform]?.membership ? {membership:this.accounts[platform].membership}: {})})); }
  allPlaylists() { return PLATFORMS.flatMap(platform=>(this.playlists[platform] || []).map(row=>({...row,platform}))); }
  decorate(result) {
    const favoritesCount=new Set(PLATFORMS.filter(p=>this.accounts[p]?.connected).flatMap(p=>[...(this.favoriteKeys[p] || [])])).size;
    return {favoritesCount,...(this.startupJob?{favoritesCountKnown:this.accountState().every(account=>!account.verifying && (!account.connected || !!this.favoriteKeys[account.platform])),playlistsRefreshing:this.accountState().some(account=>account.verifying || account.connected && !account.playlistsReady && !account.error)}:{}),...result,
      ...(this.startupJob && Array.isArray(result.playlists)?{playlistSnapshots:this.startupJob.snapshots,verifiedAccounts:this.startupJob.verified}:{}),
      accounts:this.accountState(),session:{platform:'multi',accounts:this.sessions},connected:this.accountState().some(a=>a.connected),profile:{name:this.accountState().filter(a=>a.connected).map(a=>a.user || a.name).join(' / ')}};
  }
  async call(platform,request,useCache=true) {
    request.signal?.throwIfAborted();
    if(['init','poll','logout','sync','song_update'].includes(request.op)){
      this.readCache.clear();this.readEpoch++;
      if(['init','poll','logout','song_update'].includes(request.op) && !request.signal)this.loading?.controller.abort();
    }
    const epoch=this.readEpoch;
    const key=useCache?(request.op==='tracks'?JSON.stringify([platform,'tracks',request.id]):request.op==='search'?JSON.stringify([platform,'search',request.keywords,request.page]):null):null;
    const cached=key && this.readCache.get(key);
    const result=cached && cached.expires>Date.now() && !request.refresh?structuredClone(cached.result):await this.adapters[platform].run(request);
    request.signal?.throwIfAborted();
    if(key && epoch===this.readEpoch && (request.refresh || !cached || cached.expires<=Date.now())) {
      this.readCache.delete(key);this.readCache.set(key,{expires:Date.now()+30000,result:structuredClone(result)});
      if(this.readCache.size>24)this.readCache.delete(this.readCache.keys().next().value);
    }
    if(result.session && !(request.op==='init' && !result.connected && this.sessions[platform]))this.sessions[platform]=result.session;
    if(request.op==='init')this.accounts[platform]={...this.accounts[platform],connected:result.connected,profile:result.profile,error:result.verificationFailed?`${NAMES[platform]}登录状态验证失败，请稍后重试`:''};
    if(request.op==='poll' && result.status==='connected')this.accounts[platform]={connected:true,profile:result.profile};
    if(request.op==='logout') {this.accounts[platform]={connected:false};delete this.sessions[platform];delete this.favoriteKeys[platform];this.playlists[platform]=[];
      if(this.startupJob){delete this.startupJob.snapshots[platform];this.startupJob.verified[platform]='';this.startupJob.revision++;}}
    if(result.playlists){this.playlists[platform]=result.playlists;if(this.startupJob && request.op==='sync')this.accounts[platform].playlistsReady=true;}
    if(['tracks','song_update'].includes(request.op) && result.playlist && Array.isArray(result.tracks)) {
      const playlist={...result.playlist,count:result.tracks.length};
      if(playlist.automaticCover || !playlist.cover)playlist.cover=result.tracks[0]?.cover || '';
      result.playlist=playlist;
      this.playlists[platform]=(this.playlists[platform] || []).map(row=>row.id===playlist.id?{...row,...playlist}:row);
      if(result.playlists)result.playlists=this.playlists[platform];
    }
    if(this.startupJob && result.playlists && this.accounts[platform]?.connected && this.accounts[platform]?.profile?.id) {
      const accountId=String(this.accounts[platform].profile.id);this.startupJob.verified[platform]=accountId;
      this.startupJob.snapshots[platform]={accountId,syncedAt:new Date().toISOString(),playlists:structuredClone(this.playlists[platform])};this.startupJob.revision++;
    }
    return result;
  }
  async sourceFor(track,platform) {
    const source=[track,...(track.sources || [])].find(row=>platformOf(row.id)===platform && trackKey(row)===trackKey(track));
    if(source)return source;
    const result=await this.call(platform,{op:'search',keywords:`${track.name} ${track.artist}`,page:1},false);
    return result.tracks?.find(row=>platformOf(row.id)===platform && trackKey(row)===trackKey(track));
  }
  async audioWithDefault(platform,request) {
    if(platform==='kugou')await this.startupJob?.vipPromise;
    if(request.defaultQuality===undefined || request.quality!==undefined)return this.call(platform,request);
    const qualities=request.defaultQuality==='best'?([...({netease:['netease:jyeffect','netease:hires'],qq:[],kugou:['kugou:high']}[platform]),'flac','320','128']):['flac','320','128'];
    const start=request.defaultQuality==='best'?0:qualities.indexOf(request.defaultQuality);
    if(start<0)throw Error('无效默认音质');
    let rights=true;
    for(const quality of qualities.slice(start)) {
      request.signal?.throwIfAborted();
      try {return await this.call(platform,{...request,defaultQuality:undefined,quality});}
      catch(error) {rights=rights && error?.rights===true; /* Try a lower quality without exposing private service errors. */}
    }
    // Every quality refused for rights reasons is a permission problem, not a quality problem.
    throw rights?Object.assign(Error(`此歌曲在${NAMES[platform]}需要购买或会员权限，暂时无法播放`),{rights:true}):Error('该平台没有可播放的音质');
  }
  async readFavorites(platform,signal) {
    const tracks=[];
    for(const list of (this.playlists[platform] || []).filter(p=>p.favorite && p.editable)) {
      const result=await this.call(platform,{op:'tracks',id:list.id,signal,...(signal?{onProgress:()=>{}}:{})});tracks.push(...result.tracks);
    }
    signal?.throwIfAborted();this.favoriteKeys[platform]=new Set(tracks.map(trackKey));
    return tracks;
  }
  async favorites(request={}) {
    const targets=PLATFORMS.filter(p=>this.accounts[p]?.connected);
    if(!targets.length)throw Error('请先登录音乐平台');
    const cached=this.readCache.get(FAVORITES);
    if(cached && cached.expires>Date.now() && !request.refresh)return structuredClone(cached.result);
    const partial=new Map();
    const results=await Promise.allSettled(targets.map(async platform=>{
      await this.call(platform,{op:'sync',signal:request.signal});
      const rows=await this.readFavorites(platform,request.signal);request.signal?.throwIfAborted();partial.set(platform,rows);
      request.onProgress?.({playlist:{id:FAVORITES,name:'云端收藏整合',editable:false},tracks:mergeTracks(targets.flatMap(p=>partial.get(p) || []))});
      return rows;
    }));
    request.signal?.throwIfAborted();
    const fulfilled=results.filter(r=>r.status==='fulfilled');
    if(!fulfilled.length)throw Error('云端收藏读取失败，请稍后重试');
    return this.favoriteSnapshot(fulfilled.flatMap(r=>r.value),targets.filter((p,i)=>results[i].status==='rejected').map(p=>`${NAMES[p]}收藏读取失败，当前列表仅包含其他平台的收藏`));
  }
  favoriteSnapshot(rows,warnings) {
    const tracks=mergeTracks(rows);
    const result={favoritesCount:tracks.length,playlist:{id:FAVORITES,name:'云端收藏整合',editable:false,count:tracks.length},tracks,
      playlists:this.allPlaylists(),warnings};
    if(!result.warnings.length){
      this.readCache.set(FAVORITES,{expires:Date.now()+30000,result:structuredClone(result)});
      if(this.readCache.size>24)this.readCache.delete(this.readCache.keys().next().value);
    }
    return result;
  }
  async run(request,background=false) {
    const op=request.op;
    if(op==='startup')return this.startStartup(request);
    if(op==='startup_state')return this.startupState(request.revision);
    if(!background && ['init','qr','poll','logout','sync'].includes(op) && this.startupJob) {
      const targets=['init','sync'].includes(op)?PLATFORMS:[request.platform || this.active];
      for(const platform of targets)this.startupJob.controllers[platform]?.abort();
      await Promise.allSettled(targets.map(platform=>this.startupJob.tasks[platform]));
    }
    if(op==='tracks_start' || op==='tracks_progress') {
      if(typeof request.token!=='string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.token))throw Error('无效歌单加载标识');
      if(op==='tracks_start') {
        if(typeof request.id!=='string' || !request.id)throw Error('无效歌单');
        return this.startTracks(request);
      }
      return this.trackProgress(request.token,request.revision);
    }
    if(['audio_prepare','audio_prepared','audio_cancel'].includes(op)) {
      if(typeof request.token!=='string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.token))throw Error('无效预加载标识');
      if(op==='audio_prepare') {
        if(typeof request.id!=='string' || !request.id || typeof request.cacheDir!=='string' || !request.cacheDir)throw Error('无效预加载请求');
        return this.prepareAudio(request);
      }
      if(op==='audio_cancel'){this.cancelPreparation(request.token);return {token:request.token,status:'cancelled'};}
      const job=this.preparing;
      if(!job || job.token!==request.token)return {token:request.token,status:'failed',error:'预加载已失效'};
      const reply={token:job.token,status:job.status,...(job.data?{data:job.data}:{}),...(job.error?{error:job.error}:{})};
      if(job.status!=='pending')this.preparing=null;
      return reply;
    }
    if(!background) {
      if(['sync','init','poll','logout','song_update'].includes(op))this.loading?.controller.abort();
      if(['audio','init','logout'].includes(op))this.cancelPreparation();
      if(this.adapters.qq?.pending)await this.audioResolution?.catch(()=>{});
    }
    request.signal?.throwIfAborted();
    if(op==='tracks' && request.id===FAVORITES)return this.decorate(await this.favorites(request));
    if(op==='init') {
      const saved=request.session?.platform==='multi'?request.session.accounts:{kugou:request.session};
      this.sessions={...saved};
      const tracks=(request.tracks || []).flatMap(row=>[row,...(row.sources || [])]);
      await Promise.all(PLATFORMS.map(async platform=>{
        try {await this.call(platform,{...request,session:saved?.[platform],tracks:tracks.filter(row=>platformOf(row.id)===platform)});}
        catch {this.accounts[platform]={connected:false,error:`${NAMES[platform]}连接失败，请稍后重试`};}
      }));
      return this.decorate({});
    }
    if(op==='qr') {if(!PLATFORMS.includes(request.platform || 'kugou'))throw Error('未知账号平台');this.active=request.platform || 'kugou';
      if(this.startupJob){this.accounts[this.active]={...this.accounts[this.active],connected:false,verifying:false,playlistsReady:false};this.startupJob.revision++;}}
    if(op==='logout') {
      const platform=request.platform || 'kugou'; if(!PLATFORMS.includes(platform))throw Error('未知账号平台');
      await this.call(platform,request);return this.decorate({playlists:this.allPlaylists(),platform});
    }
    if(op==='sync' || op==='search') {
      const targets=op==='sync'?PLATFORMS.filter(p=>this.accounts[p]?.connected):PLATFORMS;
      const favoriteWarnings=[];
      const favoriteTracks=new Map();
      const results=await Promise.allSettled(targets.map(async platform=>{
        const result=await this.call(platform,request);
        if(op==='sync')try {favoriteTracks.set(platform,await this.readFavorites(platform));} catch {favoriteWarnings.push(`${NAMES[platform]}收藏数量读取失败，已保留原有数量`);}
        return result;
      }));
      const fulfilled=results.filter(r=>r.status==='fulfilled').map(r=>r.value);
      if(!fulfilled.length)throw Error('各平台请求均失败，请检查网络和登录状态');
      const warnings=targets.filter((p,i)=>results[i].status==='rejected').map(p=>`${NAMES[p]}请求失败，已保留原有数据`);
      if(op==='sync') {
        warnings.push(...favoriteWarnings);
        if(!warnings.length)this.favoriteSnapshot(targets.flatMap(platform=>favoriteTracks.get(platform)),[]);
        return this.decorate({playlists:this.allPlaylists(),syncedAt:new Date().toISOString(),warnings});
      }
      if(request.page===1 || this.searchKeywords!==request.keywords){this.searchGroups.clear();this.searchKeywords=request.keywords;}
      const tracks=mergeTracks(fulfilled.flatMap(result=>result.tracks),this.searchGroups);
      return this.decorate({keywords:request.keywords,page:request.page,tracks,total:fulfilled.reduce((sum,r)=>sum+r.total,0),hasMore:fulfilled.some(r=>r.hasMore),warnings});
    }
    if(op==='covers')return this.adapters.kugou.run(request);
    if(op==='avatar') {
      await Promise.all(PLATFORMS.filter(p=>this.accounts[p]?.connected).map(async platform=>{
        const account=this.accounts[platform];
        const avatar=this.adapters[platform].run(request);
        await Promise.all([(async()=>{
          // QQ's child accepts one command at a time; read its stored avatar URL before membership.
          if(platform==='qq')try {await avatar;} catch { /* Still check membership. */ }
          try {
            const membership=await this.adapters[platform].run({op:'membership'});
            account.membership=typeof membership.name==='string'?membership:{name:'会员状态暂不可用'};
          } catch {account.membership={name:'会员状态暂不可用'};}
        })(),(async()=>{
          try {
            const result=await avatar;
            const image=result.url ? (await this.adapters.kugou.run({op:'covers',urls:[result.url]})).images?.[0]?.image : result.image;
            account.avatar=typeof image==='string' && /^data:image\/(jpeg|png|webp);base64,/.test(image)?image:'';
          } catch { /* Avatar failures must not invalidate a signed-in account. */ }
        })()]);
      }));
      return this.decorate({image:this.accounts.kugou?.avatar || ''});
    }
    if(op==='vip_auto') {
      return this.decorate(this.accounts.kugou?.connected?await this.call('kugou',request):{outcome:'skipped'});
    }
    if(op==='song_menu' && request.track){
      const targets=['netease','kugou','qq'].filter(p=>this.accounts[p]?.connected);
      const results=await Promise.allSettled(targets.map(async platform=>{
        const source=await this.sourceFor(request.track,platform);
        if(!source)return null;
        const menu=await this.call(platform,{op:'song_menu',id:source.id});
        return {...menu,id:source.id,platform};
      }));
      const providers=results.filter(r=>r.status==='fulfilled' && r.value).map(r=>r.value);
      const current=providers.find(p=>p.id===request.id) || {liked:false,canFavorite:false,playlists:[]};
      const warnings=targets.filter((p,i)=>results[i].status==='rejected').map(p=>`${NAMES[p]}收藏状态读取失败，暂不提供该平台操作`);
      return this.decorate({...current,id:request.menuId || request.id,sourceId:request.id,providers,warnings});
    }
    if(op==='audio' && request.platform){
      const platform=request.platform;
      if(!PLATFORMS.includes(platform))throw Error('未知播放平台');
      if(!this.accounts[platform]?.connected)throw Error(`请先登录${NAMES[platform]}`);
      const current=request.track;
      if(!current || current.id!==request.id || typeof current.name!=='string' || typeof current.artist!=='string')throw Error('当前歌曲信息不可用');
      const sources=[current,...(Array.isArray(current.sources)?current.sources:[])].filter(row=>row && typeof row.id==='string' && trackKey(row)===trackKey(current));
      let source;
      try {source=await this.sourceFor(current,platform);} catch {throw Error('目标平台搜索失败，请稍后重试');}
      if(!source)throw Error('该平台未找到同名、同歌手、同专辑的歌曲');
      try {
        const result=await this.call(platform,{op:'audio',id:source.id,cacheDir:request.cacheDir,stream:request.stream});
        if(!result.track || (!result.path && !result.url))throw Error('No audio');
        const candidates=PLATFORMS.map(p=>p===platform?source:sources.find(row=>platformOf(row.id)===p)).filter(Boolean).map(row=>{const {sources, ...candidate}=row;return candidate;});
        return this.decorate({...result,requestedId:request.id,platform,track:{...result.track,platform,sources:candidates}});
      } catch {throw Error('该平台无法播放这首歌曲，已保留原播放源');}
    }
    if(op==='audio' && !request.quality && Array.isArray(request.sources) && request.sources.length){
      for(const platform of ['netease','kugou','qq']){
        const source=request.sources.find(row=>platformOf(row.id)===platform);
        if(!source || !this.accounts[platform]?.connected)continue;
        try {
          const result=await this.audioWithDefault(platform,{...request,id:source.id,sources:undefined});
          if(!result.track || (!result.path && !result.url))continue;
          return this.decorate({...result,requestedId:request.id,platform,track:{...result.track,platform,sources:request.sources}});
        } catch(error) {if(request.sources.length===1)throw error; /* Otherwise try the next signed-in platform. */}
      }
      throw Error('这些平台均无法播放该歌曲，请检查登录状态或播放权限');
    }
    const platform=['qr','poll','avatar'].includes(op)?this.active:platformOf(request.id);
    if(request.playlistId && platformOf(request.playlistId)!==platform)throw Error('只能添加到同一平台的歌单');
    const result=op==='audio'?await this.audioWithDefault(platform,request):await this.call(platform,request);
    if(op==='song_update' && request.aggregate)return this.decorate({...await this.favorites(),message:result.message});
    if(op==='song_menu' && request.menuId){result.sourceId=request.id;result.id=request.menuId;}
    if(op==='song_update') {
      try {await this.readFavorites(platform);} catch {result.warnings=[...(result.warnings || []),`${NAMES[platform]}收藏数量更新失败，请重新同步`];}
      result.playlists=this.allPlaylists();
    }
    return this.decorate({...result,platform});
  }
}
module.exports={MultiPlatform,platformOf,PLATFORMS};
