'use strict';
// Parent/child JSON-lines IPC only. No listening TCP port or third-party proxy.
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const readline = require('node:readline');
const {inspectAudio}=require('./kugou-audio.cjs');
const VENDOR = path.join(__dirname, 'vendor', 'KuGouMusicApi-a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e');
const ALLOWED = new Set(['login_qr_key', 'login_qr_create', 'login_qr_check', 'user_detail', 'user_playlist', 'playlist_track_all_new', 'playlist_track_all', 'song_url', 'privilege_lite', 'user_verify', 'song_auth', 'song_url_auth', 'search', 'playlist_tracks_add', 'playlist_tracks_del', 'user_vip_detail', 'youth_month_vip_record', 'youth_day_vip', 'search_lyric', 'lyric']);
const QUALITIES = [{id:'128',name:'标准 · 128 kbps'}, {id:'320',name:'高品质 · 320 kbps'}, {id:'flac',name:'无损 · FLAC'}, {id:'kugou:high',name:'Hi-Res'}];
function confirmedQuality(data, id) {
  const bitrate=Number(data.bitRate ?? data.bitrate ?? data.bit_rate ?? 0);
  const format=String(data.extName ?? data.extname ?? data.ext_name ?? '').toLowerCase();
  if(id==='flac')return format==='flac';
  // Measured bits/s can include small offsets, e.g. 128029 for nominal 128 kbps.
  return format!=='flac' && (bitrate===Number(id) || Math.round(bitrate/1000)===Number(id));
}
function audioUrl(data) {
  const value=Array.isArray(data.url)?data.url[0]:data.url;
  if(!value)return '';
  try {const url=new URL(value);return ['http:','https:'].includes(url.protocol)?url.href:'';} catch {return '';}
}

function liveCaller() {
  process.env.platform = 'lite';
  require('axios').defaults.timeout = 18000;
  // Upstream diagnostics must never leak credentials onto our IPC/log streams.
  console.log = console.info = console.debug = console.warn = console.error = () => {};
  const { createRequest } = require(path.join(VENDOR, 'util/request'));
  return (name, params) => {
    if (!ALLOWED.has(name)) throw new Error('不支持的接口');
    // Search requires an explicit guest userid; upstream omits zero defaults.
    const request = name === 'search' ? options => createRequest({ ...options,
      params: { userid: Number(params.cookie?.userid || 0), ...options.params } }) :
      name === 'playlist_tracks_add' ? options => createRequest({ ...options,
        data: { ...options.data, data: [params.resource] } }) : createRequest;
    return require(path.join(VENDOR, 'module', name + '.js'))(params, request);
  };
}
function failure(body,audio=false) {
  const data=body?.data || body;
  if(audio && data && [2,3].includes(Number(data.status ?? body?.status)) && !audioUrl(data))
    return Object.assign(Error('此歌曲在酷狗需要购买或会员权限，暂时无法播放'),{rights:true});
  const code = body?.error_code ?? body?.code ?? 'unknown';
  return new Error(`酷狗接口返回错误（${String(code).replace(/[^\w-]/g, '').slice(0, 24)}），请重新扫码或稍后重试`);
}
function arrayAt(data, keys) {
  for (const key of keys) if (Array.isArray(data?.[key])) return data[key];
  // KuGou returns info: null (rather than []) after the final cloud-list page.
  // Missing fields or non-null unexpected values still fail closed.
  for (const key of keys) if (data && Object.hasOwn(data, key) && data[key] === null) return [];
  throw new Error('酷狗返回的数据结构发生变化，未覆盖原来的同步结果');
}
const text = value => typeof value === 'string' || typeof value === 'number' ? String(value) : '';
const albumName = item => [item.AlbumName, item.album_name, item.albumname, item.album,
  item.albuminfo?.album_name, item.albuminfo?.name, item.audio_info?.album_name, item.audio_info?.albumname]
  .map(value => text(value).replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').trim()).find(Boolean) || '';
function coverUrl(value) {
  try {
    const u = new URL(text(value).replace(/\{size\}/g, '200'));
    if (!['http:', 'https:'].includes(u.protocol) || !/(^|\.)(kugou\.com|kgimg\.com|music\.126\.net|music\.163\.com|y\.qq\.com|y\.gtimg\.cn|qpic\.cn|qlogo\.cn)$/.test(u.hostname) || u.username || u.password) return '';
    u.protocol = 'https:';return u.href;
  } catch { return ''; }
}
function cover(item) {
  return [item.cover, item.pic, item.img, item.Image, item.AlbumImage, item.sizable_cover,
    item.trans_param?.union_cover, item.albuminfo?.sizable_cover, item.albuminfo?.cover,
    item.audio_info?.cover].map(coverUrl).find(Boolean) || '';
}
const vipFlag=value=>value===0 || value==='0'?0:value===1 || value==='1'?1:null;
function vipState(data) {
  if(!data || !Array.isArray(data.busi_vip)) return null;
  const concept=data.busi_vip.filter(v=>v.busi_type==='concept');
  if(concept.some(v=>vipFlag(v.is_vip)===1) || vipFlag(data.is_vip)===1) return true;
  if(vipFlag(data.is_vip)!==0 || concept.some(v=>vipFlag(v.is_vip)===null)) return null;
  return false;
}
function conceptVipState(data) {
  if(!Array.isArray(data?.busi_vip))return null;
  const products=data.busi_vip.filter(v=>v?.busi_type==='concept');
  const concept=products.filter(v=>v.product_type==='svip');
  if(concept.some(v=>vipFlag(v.is_vip)===1))return true;
  if(concept.length)return concept.every(v=>vipFlag(v.is_vip)===0)?false:null;
  // A known listening entitlement is not evidence of concept VIP; unknown products stay unknown.
  if(products.some(v=>v.product_type!=='tvip' || vipFlag(v.is_vip)===null))return null;
  return products.length>0 || vipFlag(data.is_vip)===0?false:null;
}
function claimRecord(data) {
  if(!data || !Array.isArray(data.list) || !Number.isFinite(data.server_time) || data.server_time<1000000000) return null;
  if(data.list.some(r=>!r || !/^\d{4}-\d{2}-\d{2}$/.test(r.day) || vipFlag(r.receive_vip)===null)) return null;
  const day=new Date(data.server_time*1000+8*3600000).toISOString().slice(0,10);
  const rows=data.list.filter(r=>r.day===day);
  return {day,claimed:rows.some(r=>vipFlag(r.receive_vip)===1)};
}
function responseShape(value, depth = 0) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return { type: 'array', length: value.length, sample: value.length && depth < 4 ? responseShape(value[0], depth + 1) : undefined };
  if (typeof value !== 'object') return typeof value;
  if (depth >= 4) return 'object';
  return Object.fromEntries(Object.entries(value).slice(0, 60).map(([k, v]) => [k, ['status', 'error_code', 'total', 'count', 'total_count', 'has_more'].includes(k) && ['number', 'boolean'].includes(typeof v) ? v : responseShape(v, depth + 1)]));
}
function playlist(item) {
  const id = text(item.listid);
  if (!id) throw new Error('歌单缺少标识，无法完整同步');
  return { id, editable: Number(item.is_edit)===1, favorite: Number(item.is_def)===2, automaticCover: item.is_custom_pic===0 || item.is_custom_pic==='0', cover: cover(item), name: text(item.name) || '未命名歌单', count: Number(item.count ?? item.songcount ?? item.song_count ?? 0), globalId: text(item.global_collection_id), sourceGlobalId: text(item.list_create_gid), owner: text(item.list_create_userid) };
}
function track(item) {
  // Responses vary between cloud-list generations; preserve IDs for playback.
  const audio = item.audio_info || item;
  const hash = text(audio.hash || item.hash).toLowerCase();
  const info = songInfo(item);
  const rawTime=item.collecttime ?? item.collect_time;
  let collectedAt=Number(rawTime);
  if(!Number.isFinite(collectedAt)) collectedAt=Date.parse(text(rawTime).replace(' ','T')+(/(Z|[+-]\d\d:\d\d)$/.test(text(rawTime))?'':'+08:00'));
  if(!Number.isFinite(collectedAt) || collectedAt<0) collectedAt=0;
  else if(collectedAt>0 && collectedAt<100000000000) collectedAt*=1000;
  return {
    collectedAt,
    id: text(item.fileid || item.id || hash), fileId: text(item.fileid), hash, cover: cover(item), ...info,
    album: albumName(item), albumId: text(item.album_id || audio.album_id),
    audioId: text(item.album_audio_id || item.mixsongid || audio.album_audio_id),
    duration: Number(item.timelen ?? audio.timelen ?? (Number(item.duration || 0) * 1000)),
  };
}
function songInfo(item) {
  const clean=v=>text(v).replace(/<[^>]*>/g,'').replace(/&amp;/g,'&').trim();
  const withoutExtension=v=>v.replace(/\.(mp3|wav|wma|flac|m4a|aac|ogg|ape)$/i,'').trim();
  const singers=item.singerinfo || item.Singers || item.authors || [];
  let artist=clean(item.SingerName || item.singername || item.author_name || item.singer) ||
    (Array.isArray(singers)?[...new Set(singers.map(s=>clean(typeof s==='string'?s:s.name || s.author_name || s.singername)).filter(Boolean))].join('、'):'');
  const fileName=clean(item.FileName || item.filename || item.name || item.audio_info?.name);
  const explicit=clean(item.OriSongName || item.songname || item.song_name || item.SongName);
  let name=withoutExtension(explicit || fileName);
  const norm=v=>v.toLowerCase().replace(/[\s,、/&，]+/g,'');
  const divider=name.indexOf(' - ');
  if(divider>0 && ((!explicit && !artist) || artist && norm(name.slice(0,divider))===norm(artist))) {
    if(!artist) artist=name.slice(0,divider).trim();
    name=name.slice(divider+3).trim();
  }
  if(item.OriSongName && clean(item.Suffix) && !name.includes(clean(item.Suffix))) name+=' '+clean(item.Suffix);
  return {name:name || '未命名歌曲',artist,fileName:fileName || (artist?artist+' - ':'')+name};
}
class Adapter {
  constructor(call) {
    this.call = call;
    this.reset();
  }
  reset() {
    this.cookie = { KUGOU_API_GUID: crypto.randomUUID(), KUGOU_API_MID: crypto.randomBytes(16).toString('hex'), KUGOU_API_DEV: crypto.randomBytes(8).toString('hex') };
    this.profile = null; this.key = ''; this.knownPlaylists = new Map(); this.knownTracks = new Map();this.vipAttempts=new Set();
  }
  session() { return { cookie: this.cookie, profile: this.profile, platform: 'lite' }; }
  async api(name, params = {}) {
    const audio=name==='song_url' || name==='song_url_auth';
    let res;
    try { res = await this.call(name, { ...params, cookie: { ...this.cookie } }); }
    catch (error) {
      if (error?.body) throw Object.assign(failure(error.body,audio),{lyricsReason:'platform_error'});
      const {lyricsError,errorReason}=require('./lyrics-result.cjs');
      throw lyricsError(errorReason(error)==='timeout'?'timeout':'network','无法连接酷狗服务，请检查网络后重试');
    }
    const body = res.body;
    if (body?.ssaCode) throw new Error('账号需要在官方客户端完成安全验证，请验证后重新扫码');
    if (name === 'search_lyric' && Number(body?.status) === 404 && Array.isArray(body.candidates) && !body.candidates.length)
      throw Object.assign(failure(body), { notFound: true });
    if (!body || (body.status !== undefined && ![1, 200].includes(Number(body.status))) || (body.error_code && Number(body.error_code) !== 0)) throw Object.assign(failure(body,audio),{lyricsReason:'platform_error'});
    for (const entry of res.cookie || []) {
      const pair = entry.split(';')[0]; const at = pair.indexOf('=');
      if (at > 0) this.cookie[pair.slice(0, at)] = pair.slice(at + 1);
    }
    return body;
  }
  auth() { if (!this.cookie.token || !this.cookie.userid) throw new Error('请先扫码登录酷狗概念版'); }
  async advancedVariants(track) {
    const body=await this.api('privilege_lite',{hash:track.hash,album_id:track.albumId || ''});
    if(!Array.isArray(body.data))throw Error('高级音质信息暂不可用');
    // Keep variants tied to this song, rather than accepting unrelated response rows.
    const row=body.data.find(row=>String(row.hash).toLowerCase()===track.hash.toLowerCase());
    if(!row)throw Error('高级音质信息与当前歌曲不匹配');
    return [row,...(Array.isArray(row.relate_goods)?row.relate_goods:[])];
  }
  async authorizedAudio(params) {
    const verify=await this.api('user_verify');
    if(Number(verify.data?.valid)!==1 || typeof verify.data.auth!=='string' || !verify.data.auth)throw Error('播放登录验证失败');
    this.cookie.auth=verify.data.auth;
    const authorization=await this.api('song_auth',params);
    if(typeof authorization.data?.auth!=='string' || !authorization.data.auth)throw Error('播放授权暂不可用');
    return this.api('song_url_auth',{...params,auth:authorization.data.auth,open_time:authorization.data.open_time});
  }
  async basicAudio(track,quality,variants) {
    let responseReceived=false,original,lastFailure;
    const params={hash:track.hash,album_id:track.albumId,album_audio_id:track.audioId,quality:quality==='flac'?'flac':Number(quality)};
    const playable=(data,hash)=>audioUrl(data) && confirmedQuality(data,quality) &&
      (!data.hash || String(data.hash).toLowerCase()===hash) && !data.freeTrialInfo &&
      !(Number(data.is_free_part || data.IsFreePart)>0) && !(data.hash_offset?.end_ms>0);
    try {
      const body=await this.api('song_url',params),data=body.data || body;
      original=data;
      responseReceived=true;lastFailure=undefined;
      if(playable(data,track.hash))return data;
    } catch(error) {lastFailure=error; /* An older endpoint may reject a valid account entitlement. */}
    // Variant metadata is optional; its failure must not skip account authorization.
    try {variants ||= await this.advancedVariants(track);} catch {variants=[];}
    try {
      const matches=row=>String(row.quality)===quality && /^[a-f0-9]{32}$/i.test(row.hash || '');
      // The parent row can reuse the input hash even when labeled as standard.
      const variant=variants.slice(1).find(matches) || variants.find(matches);
      if(variant?.info?.tracker_type==='none')throw Error();
      if(variant)Object.assign(params,{hash:variant.hash.toLowerCase(),album_id:variant.album_id || track.albumId,album_audio_id:variant.album_audio_id || track.audioId});
      if(original && playable(original,params.hash))return original;
      if(params.hash!==track.hash)try {
        const body=await this.api('song_url',params),data=body.data || body;
        responseReceived=true;lastFailure=undefined;
        if(playable(data,params.hash))return data;
      } catch { /* Try current authorization if the precise variant is rejected. */ }
      const body=await this.authorizedAudio(params),data=body.data || body;
      responseReceived=true;lastFailure=undefined;
      if(String(data.hash).toLowerCase()===params.hash && playable(data,params.hash))return data;
    } catch(error) {lastFailure=error; /* Never expose account credentials or private service errors. */}
    if(lastFailure?.rights)throw lastFailure;
    if(responseReceived)return null;
    throw Error('该音质暂时不可用，已保留当前播放');
  }
  async advancedAudio(track,quality,variants) {
    variants ||= await this.advancedVariants(track);
    const requested=quality.slice('kugou:'.length);
    const variant=variants.find(row=>row.quality===requested && /^[a-f0-9]{32}$/i.test(row.hash || ''));
    if(!variant || variant.info?.tracker_type==='none' || variants.some(row=>row.quality!==requested && String(row.hash).toLowerCase()===variant.hash.toLowerCase()))
      throw Error('该歌曲没有可确认的高级档位，已保留当前播放');
    const params={hash:variant.hash.toLowerCase(),album_id:variant.album_id || track.albumId,album_audio_id:variant.album_audio_id || track.audioId,quality:requested};
    let message='该高级音质暂无完整播放权限，已保留当前播放';
    for(const authorized of [false,true])try {
      let body;
      if(authorized) body=await this.authorizedAudio(params);
      else body=await this.api('song_url',params);
      const data=body.data || body;
      if(!audioUrl(data) || String(data.hash).toLowerCase()!==params.hash || data.freeTrialInfo || Number(data.is_free_part || data.IsFreePart)>0 || data.hash_offset?.end_ms>0)continue;
      let info;
      try{info=await inspectAudio(audioUrl(data));}catch{message='该高级音质为加密或暂不支持的音频格式，已保留当前播放';continue;}
      if(requested==='high' && (!info.rate || info.bits<=16 && info.rate<=48000))continue;
      if(info.duration && track.duration>0 && Math.abs(info.duration-track.duration)>Math.max(5000,track.duration*0.05))continue;
      return {data,extension:info.extension};
    } catch { /* Authenticated failures never expose the private service response. */ }
    throw Error(message);
  }
  async refreshPlaylists() {
    this.auth();const list=await this.pages('user_playlist', {}, ['info','lists'], playlist);
    const pending=list.filter(p=>p.editable && (!p.cover || p.automaticCover) && p.count>0);
    for(let i=0;i<pending.length;i+=3)await Promise.all(pending.slice(i,i+3).map(async p=>{
      try {
        if(p.favorite) {
          const first=(await this.playlistTracks(p))[0];
          if(first?.cover){p.cover=first.cover;p.automaticCover=true;}
        } else {
          const body=await this.api('playlist_track_all_new',{listid:p.id,page:1,pagesize:1});
          const first=arrayAt(body.data,['info','songs','lists'])[0];
          if(first && cover(first)){p.cover=cover(first);p.automaticCover=true;}
        }
      } catch { /* Missing artwork must not prevent playlist synchronization. */ }
    }));
    this.knownPlaylists=new Map(list.map(p=>[p.id,p]));return list;
  }
  async playlistTracks(p) {
    const sourceId=!p.editable && !p.favorite ? p.sourceGlobalId : '';
    const list=await this.pages(sourceId?'playlist_track_all':'playlist_track_all_new',
      sourceId?{id:sourceId}:{listid:p.id},['info','songs','lists'],row=>{
      const t=track(row);t.id=`${p.id}:${t.id}`;return t;
    });
    if(p.favorite) list.sort((a,b)=>b.collectedAt-a.collectedAt);
    for(const t of list) this.knownTracks.set(t.id,t);
    return list;
  }
  sameSong(a,b) {return !!a.hash && a.hash===b.hash || !!a.audioId && a.audioId===b.audioId;}
  async pages(name, params, keys, mapper) {
    const all = [], seen = new Set();
    for (let page = 1; page <= 500; page++) {
      const body = await this.api(name, { ...params, page, pagesize: 100 });
      const rows = arrayAt(body.data, keys);
      let fresh = 0;
      for (const row of rows) {
        const value = mapper(row);
        if (!seen.has(value.id)) { seen.add(value.id); all.push(value); fresh++; }
      }
      // Only stop on an empty page or an explicit total; a server may cap pagesize.
      const total = Number(body.data?.total ?? body.data?.count ?? NaN);
      if (Number.isFinite(total) && total >= 0 && all.length >= total) return all;
      if (rows.length === 0) {
        if (Number.isFinite(total) && all.length < total) throw new Error('歌单未完整返回，请重新同步');
        return all;
      }
      if (!fresh) throw new Error('接口分页重复，已保留上次的完整同步结果');
    }
    throw new Error('歌单数量超出当前同步上限');
  }
  async run(request) {
    switch (request.op) {
      case 'lyrics': {
        const t=request.track;
        if(!t || !/^[a-f0-9]{32}$/i.test(t.hash))throw Error('歌曲缺少有效标识');
        this.cookie={...(request.session?.cookie || {})};
        // All steps share a deadline shorter than the native bridge's 15 seconds.
        const until=Date.now()+13500;
        const call=async(name,params,limit=13500)=>{
          const remaining=Math.min(limit,until-Date.now());
          if(remaining<=0)throw require('./lyrics-result.cjs').lyricsError('timeout','歌词请求超时');
          let timer;
          try {return await Promise.race([this.api(name,params),new Promise((_,reject)=>{
            timer=setTimeout(()=>reject(require('./lyrics-result.cjs').lyricsError('timeout','歌词请求超时')),remaining);
          })]);}finally{clearTimeout(timer);}
        };
        let found;
        // KuGou answers status 404 with an empty candidate list when a song has no lyrics
        // (instrumentals, many remixes); that is "no lyrics", not a failed request.
        try {found=await call('search_lyric',{hash:t.hash,album_audio_id:t.audioId || 0,duration:t.duration || 0});}
        catch(error) {if(error.notFound)return {outcome:'none'};throw error;}
        if(!Array.isArray(found.candidates))throw require('./lyrics-result.cjs').lyricsError('invalid_data','歌词搜索结果无效');
        if(!found.candidates.length)return {outcome:'none'};
        // Hash-scoped search only; never broaden to a title search or another platform.
        const candidate=found.candidates.find(c=>c.id && typeof c.accesskey==='string' && c.accesskey &&
          (!c.hash || String(c.hash).toLowerCase()===t.hash.toLowerCase()));
        if(!candidate)throw require('./lyrics-result.cjs').lyricsError('version_mismatch','未找到匹配的歌词版本');
        const download={id:candidate.id,accesskey:candidate.accesskey};
        const krc=async(limit)=>{
          const body=await call('lyric',{...download,fmt:'krc',decode:false},limit);
          try{return require('./krc.cjs').decodeKrc(body.content);}
          catch{throw require('./lyrics-result.cjs').lyricsError('invalid_data');}
        };
        if(request.translationOnly===true)return {outcome:'found',format:'krc',text:await krc()};
        const body=await call('lyric',{...download,fmt:'lrc',decode:true});
        let result;
        try{result=require('./lyrics-result.cjs').foundLyrics(body.decodeContent);}
        catch{throw require('./lyrics-result.cjs').lyricsError('invalid_data','歌词内容无效');}
        // Matching previews may include translations of this exact candidate.
        // KRC damage or timeout never discards a valid LRC original.
        if(request.includeTranslation===true)try {result.krc=await krc(4000);}
        catch(error){result.translationReason=require('./lyrics-result.cjs').errorReason(error);}
        return result;
      }
      case 'vip_auto': {
        this.auth();
        try {
          const current=conceptVipState((await this.api('user_vip_detail')).data);
          if(current===true) return {outcome:'already_vip',notify:false};
          if(current===null) return {outcome:'unknown_status',notify:false};
          const record=claimRecord((await this.api('youth_month_vip_record')).data);
          if(!record) return {outcome:'unknown_record',notify:false};
          if(record.claimed) return {outcome:'already_claimed',notify:false};
          const key=`${this.cookie.userid}:${record.day}`;
          if(this.vipAttempts.has(key)) return {outcome:'already_attempted',notify:false};
          this.vipAttempts.add(key);
          // Only redeem today's normal reward. Never simulate listening or ad reports.
          const claim=await this.api('youth_day_vip',{receive_day:record.day});
          if(Number(claim.status)!==1) return {outcome:'unverified',notify:false};
          const after=claimRecord((await this.api('youth_month_vip_record')).data);
          const active=conceptVipState((await this.api('user_vip_detail')).data);
          if(after?.day===record.day && after.claimed && active===true)
            return {outcome:'claimed',notify:true,day:record.day};
          return {outcome:'unverified',notify:false};
        } catch {return {outcome:'unavailable',notify:false};}
      }
      case 'vip_inspect': {
        this.auth();
        const status=await this.api('user_vip_detail');
        const record=await this.api('youth_month_vip_record');
        return {isVip:status.data?.is_vip,business:status.data?.busi_vip?.map(v=>({type:v.busi_type,product:v.product_type,isVip:v.is_vip})),serverTime:record.data?.server_time,records:record.data?.list?.slice(0,3),future:record.data?.future_duration};
      }
      case 'song_menu': {
        this.auth();const t=this.knownTracks.get(text(request.id));if(!t) throw Error('歌曲已失效，请重新加载列表');
        const list=await this.refreshPlaylists();
        const favorites=list.filter(p=>p.favorite && p.editable);
        const favorite=favorites.length===1?favorites[0]:null;
        const liked=favorite?(await this.playlistTracks(favorite)).some(row=>this.sameSong(row,t)):false;
        return {id:t.id, liked, canFavorite:!!favorite, playlists:list.filter(p=>p.editable)};
      }
      case 'song_update': {
        this.auth();const t=this.knownTracks.get(text(request.id));
        if(!t || !/^[a-f0-9]{32}$/.test(t.hash)) throw Error('歌曲缺少有效标识，无法修改歌单');
        const kind=request.kind;
        if(!['add','favorite'].includes(kind) || kind==='favorite' && typeof request.enabled!=='boolean') throw Error('无效的歌曲操作');
        const lists=await this.refreshPlaylists();
        const favorites=lists.filter(p=>p.favorite && p.editable);
        const target=kind==='favorite'?(favorites.length===1?favorites[0]:null):lists.find(p=>p.id===text(request.playlistId));
        if(!target || !target.editable) throw Error('目标歌单不可编辑，请重新打开右键菜单');
        const before=await this.playlistTracks(target);
        const matches=before.filter(row=>this.sameSong(row,t));
        const adding=kind==='add' || request.enabled;
        let submitted=false;
        if(adding && matches.length===0) {
          await this.api('playlist_tracks_add',{listid:target.id,resource:{number:1,name:t.fileName || t.name,hash:t.hash,size:0,sort:0,timelen:t.duration||0,bitrate:0,
            album_id:/^\d+$/.test(t.albumId)?Number(t.albumId):0,mixsongid:/^\d+$/.test(t.audioId)?Number(t.audioId):0}});submitted=true;
        } else if(!adding && matches.length) {
          if(matches.some(row=>!/^\d+$/.test(row.fileId)||Number(row.fileId)<=0)) throw Error('收藏歌曲缺少删除标识，未执行取消收藏');
          await this.api('playlist_tracks_del',{listid:target.id,fileids:matches.map(row=>row.fileId).join(',')});submitted=true;
        }
        let after=before;
        if(submitted) {
          try {after=await this.playlistTracks(target);} catch {throw Error('操作已提交，但无法核实结果，请同步歌单后查看');}
          if(after.some(row=>this.sameSong(row,t))!==adding) throw Error('酷狗尚未返回预期结果，请同步歌单后查看');
        }
        target.count=after.length;
        if(target.automaticCover || !target.cover)target.cover=after[0]?.cover || '';
        return {id:t.id,playlist:target,tracks:after,playlists:lists,message:kind==='favorite'?(adding?'已收藏到“我喜欢”':'已取消收藏'):(submitted?'已添加到歌单':'这首歌曲已在该歌单中')};
      }
      case 'covers': {
        const urls = [...new Set((Array.isArray(request.urls) ? request.urls : []).filter(url => typeof url === 'string' && coverUrl(url)))].slice(0, 8);
        const images = await Promise.all(urls.map(async url => {
          const original = coverUrl(url); const candidate = new URL(original);
          if (/^(?:[^.]+\.)*(?:kugou\.com|kgimg\.com)$/.test(candidate.hostname))
            candidate.pathname = candidate.pathname.replace(/^\/200\//, '/400/');
          if (candidate.hostname === 'y.gtimg.cn')
            candidate.pathname = candidate.pathname.replace(/^(\/music\/photo_new\/T002)R300x300(M000[^/]+\.jpg)$/i, '$1R800x800$2');
          for (const source of new Set([candidate.href, original])) {
            try {
              const r = await fetch(source, { signal: AbortSignal.timeout(8000), redirect: 'error' });
              if (!r.ok) throw Error('image');
              const contentType = r.headers?.get('content-type') || '';
              if (contentType && !/^(image\/(jpe?g|png|webp)|application\/octet-stream)(;|$)/i.test(contentType)) throw Error('image type');
              const chunks = [];let size = 0;
              for await (const chunk of r.body) {
                size += chunk.length;if (size > 1024 * 1024) throw Error('size');chunks.push(Buffer.from(chunk));
              }
              if (!size) throw Error('empty image');
              return { url, image: 'data:image/jpeg;base64,' + Buffer.concat(chunks).toString('base64') };
            } catch { /* Try the original image when the larger one is unavailable. */ }
          }
          return { url, image: '' };
        }));
        return { images };
      }
      case 'membership': {
        this.auth();const data=(await this.api('user_vip_detail')).data;
        const active=vipState(data);
        const products=data?.busi_vip?.filter(v=>v.busi_type==='concept' && vipFlag(v.is_vip)===1) || [];
        const entitlement=products.find(v=>v.product_type==='svip') || products.find(v=>v.product_type==='tvip') || products[0] || data;
        const name=entitlement?.busi_type==='concept'?{svip:'概念版 VIP',tvip:'畅听 VIP'}[entitlement.product_type]:undefined;
        const result={name:active===true?name || '酷狗 VIP':active===false?'非会员':'会员状态暂不可用'};
        const end=entitlement?.vip_end_time;
        if(active===true && typeof end==='string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(end)){
          const expiresAt=Date.parse(end.replace(' ','T')+'+08:00');
          if(Number.isFinite(expiresAt) && expiresAt>Date.now() && expiresAt<=253402300799999)result.expiresAt=expiresAt;
        }
        return result;
      }
      case 'avatar': {
        this.auth();
        const body = await this.api('user_detail');
        const value = body.data?.pic || body.data?.user_pic || body.data?.avatar || '';
        if (!value) return { image: '' };
        try {
          const url = new URL(String(value).replace(/\{size\}/g, '200'));
          if (!['https:', 'http:'].includes(url.protocol)) return { image: '' };
          const response = await fetch(url, { signal: AbortSignal.timeout(12000) });
          if (!response.ok) return { image: '' };
          const chunks = []; let size = 0;
          for await (const chunk of response.body) {
            size += chunk.length; if (size > 2 * 1024 * 1024) return { image: '' };
            chunks.push(Buffer.from(chunk));
          }
          return { image: 'data:image/jpeg;base64,' + Buffer.concat(chunks).toString('base64') };
        } catch { return { image: '' }; }
      }
      case 'search': {
        const keywords = text(request.keywords).trim();
        if (!keywords || keywords.length > 120) throw new Error('请输入 1–120 个字符的歌曲或歌手名称');
        const page = Math.max(1, Math.min(500, Math.trunc(Number(request.page) || 1)));
        const body = await this.api('search', { keywords, page, pagesize: 30, type: 'song' });
        const rows = arrayAt(body.data, ['lists', 'info']);
        const tracks = rows.map(item => {
          const hash = text(item.FileHash || item.hash).toLowerCase();
          const t = { id: `search:${hash}:${text(item.MixSongID || item.mixsongid)}`, hash, cover: cover(item),
            ...songInfo(item),
            album: albumName(item), albumId: text(item.AlbumID || item.album_id), audioId: text(item.MixSongID || item.mixsongid),
            duration: Number(item.Duration || item.duration || 0) * 1000 };
          this.knownTracks.set(t.id, t); return t;
        });
        const total = Number(body.data?.total ?? tracks.length);
        return { keywords, page, total, tracks, hasMore: page * 30 < total && tracks.length > 0 };
      }
      case 'diagnose': {
        this.auth();
        const first = await this.api('user_playlist', { page: 1, pagesize: 100 });
        const second = await this.api('user_playlist', { page: 2, pagesize: 100 });
        const sample = first.data?.info?.find(p => Number(p.count) > 0);
        const tracks = sample ? await this.api('playlist_track_all_new', { listid: sample.listid, page: 1, pagesize: 100 }) : null;
        const tracksEnd = sample ? await this.api('playlist_track_all_new', { listid: sample.listid, page: 2, pagesize: 100 }) : null;
        return { playlistFlags: (first.data?.info || []).map(p=>({is_def:p.is_def,is_mine:p.is_mine,is_edit:p.is_edit,type:p.type,favoriteName:p.name==='我喜欢'})), first: responseShape(first), second: responseShape(second), tracks: responseShape(tracks), tracksEnd: responseShape(tracksEnd) };
      }
      case 'init': {
        // Restart metadata can identify songs, but never supplies playback URLs or credentials.
        if(Array.isArray(request.tracks)) for(const row of request.tracks.slice(0,10001)) {
          if(!row || typeof row.id!=='string' || row.id.length>4096 || !row.id || row.id.startsWith('local:') || !/^[a-f0-9]{32}$/i.test(row.hash))continue;
          const t={};
          for(const key of ['id','name','artist','album','hash','fileId','fileName','albumId','audioId'])t[key]=text(row[key]).slice(0,4096);
          t.hash=t.hash.toLowerCase();t.cover=coverUrl(row.cover);t.duration=Number.isFinite(row.duration)&&row.duration>=0?row.duration:0;
          this.knownTracks.set(t.id,t);
        }
        const s = request.session;
        if (s?.platform === 'lite' && s.cookie?.token && s.cookie?.userid) {
          this.cookie = s.cookie;
          try {
            const body = await this.api('user_detail');
            this.profile = { id: text(this.cookie.userid), name: text(body.data?.nickname || s.profile?.name) || '酷狗用户' };
          } catch { this.profile = null; return { connected: false, verificationFailed: true }; }
        }
        return { connected: !!this.profile, profile: this.profile, session: this.session() };
      }
      case 'qr': {
        const tracks=this.knownTracks;this.reset();this.knownTracks=tracks;
        const body = await this.api('login_qr_key');
        this.key = text(body.data?.qrcode);
        if (!this.key) throw new Error('没有收到登录二维码，请稍后重试');
        const image = await this.api('login_qr_create', { key: this.key, qrimg: true });
        if (!image.data?.base64?.startsWith('data:image/png;base64,')) throw new Error('二维码图片无效');
        return { image: image.data.base64, status: 'waiting' };
      }
      case 'poll': {
        if (!this.key) throw new Error('请重新生成二维码');
        const body = await this.api('login_qr_check', { key: this.key });
        const state = Number(body.data?.status);
        if (state === 0) { this.key = ''; return { status: 'expired' }; }
        if (state === 4) {
          this.cookie.token = text(body.data.token); this.cookie.userid = text(body.data.userid);
          this.auth(); this.key = '';
          this.profile = { id: text(body.data.userid), name: text(body.data.nickname || body.data.username) || '酷狗用户' };
          return { status: 'connected', profile: this.profile, session: this.session() };
        }
        if (![1, 2].includes(state)) throw new Error('二维码状态异常，请重新生成');
        return { status: state === 2 ? 'confirm' : 'waiting' };
      }
      case 'sync': {
        this.auth();
        const list = await this.refreshPlaylists();
        return { playlists: list, profile: this.profile, syncedAt: new Date().toISOString(), session: this.session() };
      }
      case 'tracks': {
        this.auth(); const p = this.knownPlaylists.get(text(request.id));
        if (!p) throw new Error('请先同步歌单');
        const list = await this.playlistTracks(p);
        return { playlist: p, tracks: list, syncedAt: new Date().toISOString() };
      }
      case 'qualities': {
        this.auth();const t=this.knownTracks.get(text(request.id));
        if(!t || !/^[a-f0-9]{32}$/.test(t.hash))throw Error('此歌曲暂时没有可播放的音频标识');
        let variants=[];try{variants=await this.advancedVariants(t);}catch{}
        const results=await Promise.all(QUALITIES.map(async option=>{
          try {
            if(option.id.startsWith('kugou:')){await this.advancedAudio(t,option.id,variants);return {option};}
            return {option:await this.basicAudio(t,option.id,variants)?option:null};
          } catch(error) {return {error};}
        }));
        if(results.every(result=>result.error))throw results[0].error;
        return {id:t.id,options:results.flatMap(result=>result.option?[result.option]:[])};
      }
      case 'audio': {
        this.auth(); const t = this.knownTracks.get(text(request.id));
        if (!t || !/^[a-f0-9]{32}$/.test(t.hash)) throw new Error('此歌曲暂时没有可播放的音频标识');
        const quality=request.quality===undefined?'128':String(request.quality);
        if(!QUALITIES.some(option=>option.id===quality))throw Error('无效音质');
        const advanced=quality.startsWith('kugou:')?await this.advancedAudio(t,quality):null;
        let data=advanced?.data;
        if(!advanced)try{data=await this.basicAudio(t,quality);if(!data)throw Error('该音质暂时不可用，已保留当前播放');}catch(error){
          if(request.quality!==undefined)throw error;
          throw Error('此账号暂时无法播放该歌曲，可能需要会员或歌曲已下架');
        }
        const confirmed=!!advanced || confirmedQuality(data,quality);
        if(request.quality!==undefined && !confirmed)throw Error('该音质暂时不可用，已保留当前播放');
        t.cover = cover(data) || t.cover;
        const value = Array.isArray(data.url) ? data.url[0] : data.url;
        if (!value) throw Error('该音质暂时不可用，已保留当前播放');
        const url = new URL(value);
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error('音频地址无效');
        // Audio comes only from the account-authorized URL returned by upstream.
        if (!request.cacheDir) return { url: url.href, track: t, quality:confirmed?quality:'' };
        // Fetch only the playback URL provided for this account. No alternate sources.
        const dir = path.resolve(request.cacheDir);
        await fs.mkdir(dir, { recursive: true });
        const file = path.join(dir, crypto.randomBytes(16).toString('hex') + (advanced?.extension || (quality==='flac'?'.flac':'.mp3')));
        let handle;
        try {
          const response = await fetch(url, { signal: AbortSignal.timeout(75000) });
          if (!response.ok) throw new Error('音频服务器拒绝了请求');
          handle = await fs.open(file, 'wx');
          let size = 0;
          for await (const chunk of response.body) {
            size += chunk.length;
            if (size > 512 * 1024 * 1024) throw new Error('音频文件超过当前播放缓存上限');
            await handle.write(chunk);
          }
          if (size < 128) throw new Error('音频内容为空');
          await handle.close(); handle = null;
          return { path: file, track: t, quality:confirmed?quality:'' };
        } catch {
          if (handle) await handle.close();
          await fs.unlink(file).catch(() => {});
          throw new Error('音频缓存失败，请检查网络或尝试其他歌曲');
        }
      }
      case 'logout': this.reset(); return { connected: false };
      default: throw new Error('未知操作');
    }
  }
}

async function main() {
  const send = process.stdout.write.bind(process.stdout);
  let adapter;
  try { adapter = new (require('./multi-platform.cjs').MultiPlatform)({kugou:new Adapter(liveCaller()),netease:require('./netease.cjs').liveNetease(),qq:new (require('./qq.cjs').QQAdapter)()}); }
  catch { send(JSON.stringify({ ok: false, error: '接口依赖未安装，请运行 setup-cloud.ps1' }) + '\n'); process.exitCode = 1; return; }
  const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  try { for await (const line of lines) {
    if (line.length > 16 * 1024 * 1024) { send('{"ok":false,"error":"请求过大"}\n'); continue; }
    try {
      const result = await adapter.run(JSON.parse(line));
      send(JSON.stringify({ ok: true, data: result }) + '\n');
    } catch (error) { send(JSON.stringify({ ok: false, error: error.message || '同步失败' }) + '\n'); }
  } } finally { adapter.close(); }
}
if (require.main === module) main().catch(() => { process.exitCode = 1; });
module.exports = { Adapter, playlist, track, liveCaller };
