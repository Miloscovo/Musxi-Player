'use strict';
// Parent/child JSON-lines IPC only. No listening TCP port or third-party proxy.
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const readline = require('node:readline');
const VENDOR = path.join(__dirname, 'vendor', 'KuGouMusicApi-a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e');
const ALLOWED = new Set(['login_qr_key', 'login_qr_create', 'login_qr_check', 'user_detail', 'user_playlist', 'playlist_track_all_new', 'song_url', 'search', 'playlist_tracks_add', 'playlist_tracks_del', 'user_vip_detail', 'youth_month_vip_record', 'youth_day_vip']);

function liveCaller() {
  process.env.platform = 'lite';
  require('axios').defaults.timeout = 18000;
  // Upstream diagnostics must never leak credentials onto our IPC/log streams.
  console.log = console.warn = console.error = () => {};
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
function failure(body) {
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
function coverUrl(value) {
  try {
    const u = new URL(text(value).replace(/\{size\}/g, '200'));
    if (!['http:', 'https:'].includes(u.protocol) || !/(^|\.)(kugou\.com|kgimg\.com)$/.test(u.hostname) || u.username || u.password) return '';
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
  return { id, editable: Number(item.is_edit)===1, favorite: Number(item.is_def)===2, cover: cover(item), name: text(item.name) || '未命名歌单', count: Number(item.count ?? item.songcount ?? item.song_count ?? 0), globalId: text(item.global_collection_id), owner: text(item.list_create_userid) };
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
    albumId: text(item.album_id || audio.album_id),
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
    let res;
    try { res = await this.call(name, { ...params, cookie: { ...this.cookie } }); }
    catch (error) {
      if (error?.body) throw failure(error.body);
      throw new Error('无法连接酷狗服务，请检查网络后重试');
    }
    const body = res.body;
    if (body?.ssaCode) throw new Error('账号需要在官方客户端完成安全验证，请验证后重新扫码');
    if (!body || (body.status !== undefined && ![1, 200].includes(Number(body.status))) || (body.error_code && Number(body.error_code) !== 0)) throw failure(body);
    for (const entry of res.cookie || []) {
      const pair = entry.split(';')[0]; const at = pair.indexOf('=');
      if (at > 0) this.cookie[pair.slice(0, at)] = pair.slice(at + 1);
    }
    return body;
  }
  auth() { if (!this.cookie.token || !this.cookie.userid) throw new Error('请先扫码登录酷狗概念版'); }
  async refreshPlaylists() {
    this.auth();const list=await this.pages('user_playlist', {}, ['info','lists'], playlist);
    this.knownPlaylists=new Map(list.map(p=>[p.id,p]));return list;
  }
  async playlistTracks(p) {
    const list=await this.pages('playlist_track_all_new',{listid:p.id},['info','songs','lists'],row=>{
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
      case 'vip_auto': {
        this.auth();
        try {
          const current=vipState((await this.api('user_vip_detail')).data);
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
          const active=vipState((await this.api('user_vip_detail')).data);
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
        return {id:t.id,playlist:target,tracks:after,playlists:lists,message:kind==='favorite'?(adding?'已收藏到“我喜欢”':'已取消收藏'):(submitted?'已添加到歌单':'这首歌曲已在该歌单中')};
      }
      case 'covers': {
        const urls = [...new Set((Array.isArray(request.urls) ? request.urls : []).map(coverUrl).filter(Boolean))].slice(0, 8);
        const images = await Promise.all(urls.map(async url => {
          try {
            const r = await fetch(url, { signal: AbortSignal.timeout(8000), redirect: 'error' });
            if (!r.ok) throw Error('image');
            const chunks = [];let size = 0;
            for await (const chunk of r.body) {
              size += chunk.length;if (size > 1024 * 1024) throw Error('size');chunks.push(Buffer.from(chunk));
            }
            return { url, image: 'data:image/jpeg;base64,' + Buffer.concat(chunks).toString('base64') };
          } catch { return { url, image: '' }; }
        }));
        return { images };
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
            albumId: text(item.AlbumID || item.album_id), audioId: text(item.MixSongID || item.mixsongid),
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
        this.reset();
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
        const list = await this.pages('user_playlist', {}, ['info', 'lists'], playlist);
        this.knownPlaylists = new Map(list.map(p => [p.id, p]));
        return { playlists: list, profile: this.profile, syncedAt: new Date().toISOString(), session: this.session() };
      }
      case 'tracks': {
        this.auth(); const p = this.knownPlaylists.get(text(request.id));
        if (!p) throw new Error('请先同步歌单');
        const list = await this.playlistTracks(p);
        return { playlist: p, tracks: list, syncedAt: new Date().toISOString() };
      }
      case 'audio': {
        this.auth(); const t = this.knownTracks.get(text(request.id));
        if (!t || !/^[a-f0-9]{32}$/.test(t.hash)) throw new Error('此歌曲暂时没有可播放的音频标识');
        const body = await this.api('song_url', { hash: t.hash, album_id: t.albumId, album_audio_id: t.audioId, quality: 128 });
        const data = body.data || body;
        t.cover = cover(data) || t.cover;
        const value = Array.isArray(data.url) ? data.url[0] : data.url;
        if (!value) throw new Error('此账号暂时无法播放该歌曲，可能需要会员或歌曲已下架');
        const url = new URL(value);
        if (!['http:', 'https:'].includes(url.protocol)) throw new Error('音频地址无效');
        // Audio comes only from the account-authorized URL returned by upstream.
        if (!request.cacheDir) return { url: url.href, track: t };
        // Fetch only the playback URL provided for this account. No alternate sources.
        const dir = path.resolve(request.cacheDir);
        await fs.mkdir(dir, { recursive: true });
        const file = path.join(dir, crypto.randomBytes(16).toString('hex') + '.mp3');
        let handle;
        try {
          const response = await fetch(url, { signal: AbortSignal.timeout(75000) });
          if (!response.ok) throw new Error('音频服务器拒绝了请求');
          handle = await fs.open(file, 'wx');
          let size = 0;
          for await (const chunk of response.body) {
            size += chunk.length;
            if (size > 128 * 1024 * 1024) throw new Error('音频文件超过当前播放缓存上限');
            await handle.write(chunk);
          }
          if (size < 128) throw new Error('音频内容为空');
          await handle.close(); handle = null;
          return { path: file, track: t };
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
  try { adapter = new Adapter(liveCaller()); }
  catch { send(JSON.stringify({ ok: false, error: '接口依赖未安装，请运行 setup-cloud.ps1' }) + '\n'); process.exitCode = 1; return; }
  const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of lines) {
    if (line.length > 1024 * 1024) { send('{"ok":false,"error":"请求过大"}\n'); continue; }
    try {
      const result = await adapter.run(JSON.parse(line));
      send(JSON.stringify({ ok: true, data: result }) + '\n');
    } catch (error) { send(JSON.stringify({ ok: false, error: error.message || '同步失败' }) + '\n'); }
  }
}
if (require.main === module) main().catch(() => { process.exitCode = 1; });
module.exports = { Adapter, playlist, track };
