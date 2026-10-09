'use strict';
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
  constructor(adapters) { this.adapters=adapters; this.active='kugou'; this.sessions={}; this.accounts={}; this.playlists={}; this.favoriteKeys={}; this.searchKeywords=''; this.searchGroups=new Map(); }
  close() { for(const adapter of Object.values(this.adapters))adapter.stop?.('接口已关闭'); }
  accountState() { return PLATFORMS.map(platform=>({platform,name:NAMES[platform],connected:!!this.accounts[platform]?.connected,user:this.accounts[platform]?.profile?.name || '',error:this.accounts[platform]?.error || '',avatar:this.accounts[platform]?.connected ? this.accounts[platform]?.avatar || '' : ''})); }
  allPlaylists() { return PLATFORMS.flatMap(platform=>(this.playlists[platform] || []).map(row=>({...row,platform}))); }
  decorate(result) {
    const favoritesCount=new Set(PLATFORMS.filter(p=>this.accounts[p]?.connected).flatMap(p=>[...(this.favoriteKeys[p] || [])])).size;
    return {favoritesCount,...result,accounts:this.accountState(),session:{platform:'multi',accounts:this.sessions},connected:this.accountState().some(a=>a.connected),profile:{name:this.accountState().filter(a=>a.connected).map(a=>a.user || a.name).join(' / ')}};
  }
  async call(platform,request) {
    const result=await this.adapters[platform].run(request);
    if(result.session) this.sessions[platform]=result.session;
    if(request.op==='init')this.accounts[platform]={connected:result.connected,profile:result.profile};
    if(request.op==='poll' && result.status==='connected')this.accounts[platform]={connected:true,profile:result.profile};
    if(request.op==='logout') {this.accounts[platform]={connected:false};delete this.sessions[platform];delete this.favoriteKeys[platform];this.playlists[platform]=[];}
    if(result.playlists)this.playlists[platform]=result.playlists;
    return result;
  }
  async sourceFor(track,platform) {
    const source=[track,...(track.sources || [])].find(row=>platformOf(row.id)===platform && trackKey(row)===trackKey(track));
    if(source)return source;
    const result=await this.call(platform,{op:'search',keywords:`${track.name} ${track.artist}`,page:1});
    return result.tracks?.find(row=>platformOf(row.id)===platform && trackKey(row)===trackKey(track));
  }
  async audioWithDefault(platform,request) {
    if(request.defaultQuality===undefined || request.quality!==undefined)return this.call(platform,request);
    const qualities=['flac','320','128'];
    const start=qualities.indexOf(request.defaultQuality);
    if(start<0)throw Error('无效默认音质');
    for(const quality of qualities.slice(start)) {
      try {return await this.call(platform,{...request,defaultQuality:undefined,quality});}
      catch { /* Try a lower quality without exposing private service errors. */ }
    }
    throw Error('该平台没有可播放的音质');
  }
  async readFavorites(platform) {
    const tracks=[];
    for(const list of (this.playlists[platform] || []).filter(p=>p.favorite && p.editable)) {
      const result=await this.call(platform,{op:'tracks',id:list.id});tracks.push(...result.tracks);
    }
    this.favoriteKeys[platform]=new Set(tracks.map(trackKey));
    return tracks;
  }
  async favorites() {
    const targets=PLATFORMS.filter(p=>this.accounts[p]?.connected);
    if(!targets.length)throw Error('请先登录音乐平台');
    const results=await Promise.allSettled(targets.map(async platform=>{
      await this.call(platform,{op:'sync'});
      return this.readFavorites(platform);
    }));
    const fulfilled=results.filter(r=>r.status==='fulfilled');
    if(!fulfilled.length)throw Error('云端收藏读取失败，请稍后重试');
    const tracks=mergeTracks(fulfilled.flatMap(r=>r.value));
    return {favoritesCount:tracks.length,playlist:{id:FAVORITES,name:'云端收藏整合',editable:false,count:tracks.length},tracks,
      playlists:this.allPlaylists(),warnings:targets.filter((p,i)=>results[i].status==='rejected').map(p=>`${NAMES[p]}收藏读取失败，当前列表仅包含其他平台的收藏`)};
  }
  async run(request) {
    const op=request.op;
    if(op==='tracks' && request.id===FAVORITES)return this.decorate(await this.favorites());
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
    if(op==='qr') {if(!PLATFORMS.includes(request.platform || 'kugou'))throw Error('未知账号平台');this.active=request.platform || 'kugou';}
    if(op==='logout') {
      const platform=request.platform || 'kugou'; if(!PLATFORMS.includes(platform))throw Error('未知账号平台');
      await this.call(platform,request);return this.decorate({playlists:this.allPlaylists(),platform});
    }
    if(op==='sync' || op==='search') {
      const targets=op==='sync'?PLATFORMS.filter(p=>this.accounts[p]?.connected):PLATFORMS;
      const results=await Promise.allSettled(targets.map(platform=>this.call(platform,request)));
      const fulfilled=results.filter(r=>r.status==='fulfilled').map(r=>r.value);
      if(!fulfilled.length)throw Error('各平台请求均失败，请检查网络和登录状态');
      const warnings=targets.filter((p,i)=>results[i].status==='rejected').map(p=>`${NAMES[p]}请求失败，已保留原有数据`);
      if(op==='sync') {
        await Promise.all(targets.filter((p,i)=>results[i].status==='fulfilled').map(async platform=>{
          try {await this.readFavorites(platform);} catch {warnings.push(`${NAMES[platform]}收藏数量读取失败，已保留原有数量`);}
        }));
        return this.decorate({playlists:this.allPlaylists(),syncedAt:new Date().toISOString(),warnings});
      }
      if(request.page===1 || this.searchKeywords!==request.keywords){this.searchGroups.clear();this.searchKeywords=request.keywords;}
      const tracks=mergeTracks(fulfilled.flatMap(result=>result.tracks),this.searchGroups);
      return this.decorate({keywords:request.keywords,page:request.page,tracks,total:fulfilled.reduce((sum,r)=>sum+r.total,0),hasMore:fulfilled.some(r=>r.hasMore),warnings});
    }
    if(op==='covers')return this.adapters.kugou.run(request);
    if(op==='avatar') {
      await Promise.all(PLATFORMS.filter(p=>this.accounts[p]?.connected).map(async platform=>{
        try {
          const result=await this.adapters[platform].run(request);
          const image=result.url ? (await this.adapters.kugou.run({op:'covers',urls:[result.url]})).images?.[0]?.image : result.image;
          this.accounts[platform].avatar=typeof image==='string' && /^data:image\/(jpeg|png|webp);base64,/.test(image)?image:'';
        } catch { /* Avatar failures must not invalidate a signed-in account. */ }
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
        const result=await this.call(platform,{op:'audio',id:source.id,cacheDir:request.cacheDir});
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
        } catch { /* Try the next signed-in platform without exposing private service errors. */ }
      }
      throw Error('这些平台均无法播放该歌曲，请检查登录状态或播放权限');
    }
    const platform=['qr','poll','avatar'].includes(op)?this.active:platformOf(request.id);
    if(request.playlistId && platformOf(request.playlistId)!==platform)throw Error('只能添加到同一平台的歌单');
    const result=op==='audio'?await this.audioWithDefault(platform,request):await this.call(platform,request);
    if(op==='song_update' && request.aggregate)return this.decorate({...await this.favorites(),message:result.message});
    if(op==='song_menu' && request.menuId){result.sourceId=request.id;result.id=request.menuId;}
    if(op==='song_update') {
      result.playlists=this.allPlaylists();
      try {await this.readFavorites(platform);} catch {result.warnings=[...(result.warnings || []),`${NAMES[platform]}收藏数量更新失败，请重新同步`];}
    }
    return this.decorate({...result,platform});
  }
}
module.exports={MultiPlatform,platformOf,PLATFORMS};
