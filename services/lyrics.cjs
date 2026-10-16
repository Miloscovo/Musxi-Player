'use strict';
// Dedicated IPC process: no account initialization, library sync or playback requests.
const readline=require('node:readline');
const {errorReason,lyricsError}=require('./lyrics-result.cjs');
class LyricsService {
  constructor(create) {this.create=create;this.adapters=new Map();}
  async run(request) {
    if(request.op==='lyrics_search') {
      const platform=request.platform;
      if(!['kugou','netease','qq'].includes(platform) || typeof request.keywords!=='string' ||
        !request.keywords.trim() || request.keywords.length>120 || !Number.isInteger(request.page) || request.page<1 || request.page>10)
        throw Error('无效歌词搜索');
      if(!this.adapters.has(platform))this.adapters.set(platform,this.create(platform));
      const result=await this.adapters.get(platform).run({...request,op:'search'});
      if(!Array.isArray(result.tracks))throw Error('歌曲搜索结果无效');
      return {tracks:result.tracks.slice(0,30),hasMore:!!result.hasMore};
    }
    if(request.op!=='lyrics' || !['kugou','netease','qq'].includes(request.platform) ||
      typeof request.id!=='string' || request.id.length>1024)throw Error('无效歌词请求');
    const expected=request.id.startsWith('netease:')?'netease':request.id.startsWith('qq:')?'qq':'kugou';
    if(expected!==request.platform)throw Error('歌词平台不匹配');
    for(const field of ['translationOnly','includeTranslation'])
      if(request[field]!==undefined && (typeof request[field]!=='boolean' || (request[field] && expected!=='kugou')))
        throw Error('无效翻译请求');
    if(!this.adapters.has(expected))this.adapters.set(expected,this.create(expected));
    let timer;
    try {return await Promise.race([this.adapters.get(expected).run(request),new Promise((_,reject)=>{
      timer=setTimeout(()=>reject(lyricsError('timeout')),14000);
    })]);}finally{clearTimeout(timer);}
  }
  close(){for(const adapter of this.adapters.values())adapter.stop?.('歌词服务已关闭');}
}
function create(platform) {
  if(platform==='qq')return new (require('./qq.cjs').QQAdapter)();
  if(platform==='netease')return require('./netease.cjs').liveNetease();
  const {Adapter,liveCaller}=require('./bridge.cjs');return new Adapter(liveCaller());
}
async function main() {
  const send=process.stdout.write.bind(process.stdout);
  // Vendor diagnostics must never enter the IPC stream or reveal private responses.
  console.log=console.info=console.debug=console.warn=console.error=()=>{};
  const service=new LyricsService(create);
  try {for await(const line of readline.createInterface({input:process.stdin,crlfDelay:Infinity})) {
    try {
      if(Buffer.byteLength(line)>1024*1024)throw Error();
      const data=await service.run(JSON.parse(line));send(JSON.stringify({ok:true,data})+'\n');
    }catch(error) {send(JSON.stringify({ok:false,error:'歌词获取失败，请稍后重试',reason:errorReason(error)})+'\n');}
  }}finally{service.close();}
}
if(require.main===module)main().catch(()=>{process.exitCode=1;});
module.exports={LyricsService};
