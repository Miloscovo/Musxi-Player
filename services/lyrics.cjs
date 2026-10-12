'use strict';
// Dedicated IPC process: no account initialization, library sync or playback requests.
const readline=require('node:readline');
class LyricsService {
  constructor(create) {this.create=create;this.adapters=new Map();}
  async run(request) {
    if(request.op!=='lyrics' || !['kugou','netease','qq'].includes(request.platform) ||
      typeof request.id!=='string' || request.id.length>1024)throw Error('无效歌词请求');
    const expected=request.id.startsWith('netease:')?'netease':request.id.startsWith('qq:')?'qq':'kugou';
    if(expected!==request.platform)throw Error('歌词平台不匹配');
    if(!this.adapters.has(expected))this.adapters.set(expected,this.create(expected));
    return this.adapters.get(expected).run(request);
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
    }catch {send(JSON.stringify({ok:false,error:'歌词获取失败，请稍后重试'})+'\n');}
  }}finally{service.close();}
}
if(require.main===module)main().catch(()=>{process.exitCode=1;});
module.exports={LyricsService};
