'use strict';
const crypto=require('node:crypto');
const fs=require('node:fs/promises');
const path=require('node:path');
const QUALITIES=[{id:'128',name:'标准 · 128 kbps',level:'standard'},{id:'320',name:'高品质 · 320 kbps',level:'exhigh'},{id:'flac',name:'无损 · FLAC',level:'lossless'}];
async function cacheAudio(url,track,quality,cacheDir) {
  const parsed=new URL(url);if(!['http:','https:'].includes(parsed.protocol))throw Error('音频地址无效');
  if(!cacheDir)return {url:parsed.href,track,quality};
  const dir=path.resolve(cacheDir);await fs.mkdir(dir,{recursive:true});
  const file=path.join(dir,crypto.randomBytes(16).toString('hex')+(quality==='flac'?'.flac':'.mp3'));
  let handle;
  try {
    const response=await fetch(parsed,{signal:AbortSignal.timeout(75000)});if(!response.ok)throw Error();
    handle=await fs.open(file,'wx');let size=0;
    for await(const chunk of response.body){size+=chunk.length;if(size>128*1024*1024)throw Error();await handle.write(chunk);}
    if(size<128)throw Error();await handle.close();handle=null;return {path:file,track,quality};
  }catch{if(handle)await handle.close();await fs.unlink(file).catch(()=>{});throw Error('音频缓存失败，请检查网络或尝试其他歌曲');}
}
class NeteaseAdapter {
  constructor(call){this.call=call;this.cookie='';this.profile=null;this.key='';this.tracks=new Map();this.lists=new Map();}
  session(){return {platform:'netease',cookie:this.cookie,profile:this.profile};}
  async api(name,params={}){
    let result;try{result=await this.call(name,{...params,cookie:this.cookie});}catch{throw Error('无法连接网易云服务，请稍后重试');}
    let body=result.body;if(body?.body)body=body.body;
    if(!body || body.code && ![200,800,801,802,803].includes(Number(body.code)))throw Error('网易云接口拒绝请求，请在官方客户端确认账号状态');
    return body;
  }
  auth(){if(!this.profile)throw Error('请先扫码登录网易云音乐');}
  song(row){const song={id:`netease:${row.id}`,serviceId:String(row.id),platform:'netease',name:row.name || '未命名歌曲',artist:(row.ar || row.artists || []).map(a=>a.name).join('、'),album:(row.al || row.album)?.name || '',cover:(row.al || row.album)?.picUrl || '',duration:Number(row.dt || row.duration || 0),count:0,editable:false};this.tracks.set(song.id,song);return song;}
  async sync(){this.auth();const rows=[];for(let offset=0;offset<10000;offset+=100){const b=await this.api('user_playlist',{uid:this.profile.id,limit:100,offset});rows.push(...(b.playlist || []));if(!b.more)break;if(offset===9900)throw Error('网易云歌单过多，请缩小范围');}
    const lists=rows.map(p=>({id:`netease:${p.id}`,serviceId:String(p.id),platform:'netease',name:p.name,artist:'',cover:p.coverImgUrl || '',duration:0,count:p.trackCount || 0,editable:String(p.creator?.userId)===this.profile.id,favorite:!!p.specialType}));this.lists=new Map(lists.map(p=>[p.id,p]));return lists;}
  async playlist(id){const p=this.lists.get(id);if(!p)throw Error('请先同步网易云歌单');const detail=await this.api('playlist_detail',{id:p.serviceId});const ids=detail.playlist?.trackIds;if(!Array.isArray(ids))throw Error('网易云歌单数据无效');if(ids.length>10000)throw Error('歌单超过当前歌曲上限');const rows=[];for(let i=0;i<ids.length;i+=100){const b=await this.api('song_detail',{ids:ids.slice(i,i+100).map(row=>row.id).join(',')});if(!Array.isArray(b.songs))throw Error('歌曲数据无效');rows.push(...b.songs.map(row=>this.song(row)));}return {playlist:p,tracks:rows};}
  async audioInfo(track,quality){const level=QUALITIES.find(q=>q.id===quality);if(!level)throw Error('无效音质');const b=await this.api('song_url_v1',{id:track.serviceId,level:level.level});const d=b.data?.[0];if(!d?.url || d.freeTrialInfo)throw Error('此账号暂无该歌曲完整播放权限');const actual=d.type==='flac' || ['lossless','hires','jyeffect','sky','jymaster'].includes(d.level)?'flac':Number(d.br)>=320000?'320':'128';return {url:d.url,quality:actual};}
  async run(r){switch(r.op){
    case 'init':for(const row of r.tracks || [])if(row.id?.startsWith('netease:'))this.tracks.set(row.id,{...row,serviceId:row.id.slice(8),platform:'netease'});if(r.session?.cookie){this.cookie=r.session.cookie;try{const b=await this.api('login_status');const p=b.data?.profile;if(p?.userId)this.profile={id:String(p.userId),name:p.nickname};}catch{this.profile=null;}}return {connected:!!this.profile,profile:this.profile,session:this.session()};
    case 'qr':{const key=await this.api('login_qr_key');this.key=key.data?.unikey;if(!this.key)throw Error('未收到网易云二维码');const b=await this.api('login_qr_create',{key:this.key,qrimg:true});return {image:b.data?.qrimg || '',status:'waiting'};}
    case 'poll':{if(!this.key)throw Error('请重新生成二维码');const b=await this.api('login_qr_check',{key:this.key});if(![800,801,802,803].includes(b.code))throw Error('扫码状态无效，请刷新二维码');if(b.code===800)return {status:'expired'};if(b.code===803){if(typeof b.cookie!=='string' || !b.cookie)throw Error('未收到登录凭证，请重新扫码');this.cookie=b.cookie;const status=await this.api('login_status');const p=status.data?.profile;if(!p?.userId)throw Error('网易云账号验证失败');this.profile={id:String(p.userId),name:p.nickname};this.key='';return {status:'connected',profile:this.profile,session:this.session()};}return {status:b.code===802?'confirm':'waiting'};}
    case 'sync':return {playlists:await this.sync(),session:this.session()};
    case 'tracks':this.auth();return this.playlist(r.id);
    case 'search':{const b=await this.api('cloudsearch',{keywords:r.keywords,type:1,limit:30,offset:(r.page-1)*30});const tracks=(b.result?.songs || []).map(row=>this.song(row));const total=b.result?.songCount || 0;return {tracks,total,hasMore:r.page*30<total};}
    case 'qualities':{this.auth();const t=this.tracks.get(r.id);if(!t)throw Error('歌曲已失效');const options=[];for(const q of QUALITIES){try{const info=await this.audioInfo(t,q.id);if(info.quality===q.id)options.push({id:q.id,name:q.name});}catch{}}return {id:r.id,options};}
    case 'audio':{this.auth();const t=this.tracks.get(r.id);if(!t)throw Error('歌曲已失效');const info=await this.audioInfo(t,r.quality || '128');if(r.quality && info.quality!==r.quality)throw Error('所选音质暂无权限，已保留当前播放');return cacheAudio(info.url,t,info.quality,r.cacheDir);}
    case 'song_menu':{this.auth();const lists=await this.sync();const b=await this.api('likelist',{uid:this.profile.id});return {id:r.id,liked:(b.ids || []).map(String).includes(this.tracks.get(r.id)?.serviceId),canFavorite:true,playlists:lists.filter(p=>p.editable)};}
    case 'song_update':{this.auth();const t=this.tracks.get(r.id);if(!t)throw Error('歌曲已失效');const lists=await this.sync();let p;if(r.kind==='favorite'){await this.api('like',{id:t.serviceId,like:String(r.enabled)});p=lists.find(p=>p.favorite && p.editable);const liked=await this.api('likelist',{uid:this.profile.id});if((liked.ids || []).map(String).includes(t.serviceId)!==r.enabled)throw Error('操作已提交，暂未确认收藏结果，请稍后同步');}else {p=this.lists.get(r.playlistId);if(!p?.editable)throw Error('目标歌单不可编辑');await this.api('playlist_tracks',{op:'add',pid:p.serviceId,tracks:t.serviceId});}if(!p)throw Error('未找到对应歌单，请重新同步');const result=await this.playlist(p.id);if(r.kind==='add' && !result.tracks.some(row=>row.id===t.id))throw Error('操作已提交，暂未确认添加结果');return {...result,playlists:await this.sync(),message:r.kind==='favorite'?(r.enabled?'已收藏':'已取消收藏'):'已添加到歌单'};}
    case 'logout':this.cookie='';this.profile=null;this.key='';this.lists.clear();return {connected:false};
    case 'avatar':{this.auth();const b=await this.api('login_status');return {url:b.data?.profile?.avatarUrl || ''};}
    default:throw Error('不支持的网易云操作');
  }}
}
function liveNetease(){
  const root=path.join(__dirname,'vendor/NeteaseCloudMusicApi-2aab9957dfd5231e5b192aecdb177f93ace4c92e');
  const allowed=new Set(['login_qr_key','login_qr_create','login_qr_check','login_status','user_playlist','playlist_detail','song_detail','cloudsearch','song_url_v1','like','likelist','playlist_tracks']);
  let ready;
  async function initialize(){
    const anonymous=path.join(require('node:os').tmpdir(),'anonymous_token');
    try{await fs.writeFile(anonymous,'',{flag:'wx'});}catch(error){if(error.code!=='EEXIST')throw error;}
    const {generateDeviceId}=require(path.join(root,'util/index.js'));
    global.deviceId ||= generateDeviceId();
    const key=path.join(require('node:os').tmpdir(),'xeapi_public_key');
    try{await fs.access(key);}catch{
      const publicKey=await require(path.join(root,'util/xeapiKey.js')).getXeapiPublicKey({},global.deviceId);
      await fs.writeFile(key,JSON.stringify(publicKey));
    }
  }
  return new NeteaseAdapter(async(name,params)=>{
    if(!allowed.has(name))throw Error('不支持的网易云接口');
    if(name==='song_url_v1'){ready ||= initialize().catch(error=>{ready=undefined;throw error;});await ready;}
    else {const anonymous=path.join(require('node:os').tmpdir(),'anonymous_token');try{await fs.writeFile(anonymous,'',{flag:'wx'});}catch(error){if(error.code!=='EEXIST')throw error;}}
    const {cookieToJson}=require(path.join(root,'util/index.js'));
    const request=require(path.join(root,'util/request.js'));
    return require(path.join(root,'module',name+'.js'))({...params,unblock:'false',cookie:cookieToJson(params.cookie || '')},request);
  });
}
module.exports={NeteaseAdapter,liveNetease,cacheAudio};
