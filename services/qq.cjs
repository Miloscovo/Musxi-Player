'use strict';
const {spawn}=require('node:child_process');
const path=require('node:path');
const readline=require('node:readline');
const {cacheAudio}=require('./netease.cjs');
class QQAdapter {
  constructor(){this.child=null;this.pending=null;}
  start(request){
    if(this.child)return;
    const python=path.resolve(__dirname,'../runtime/python/python.exe');
    this.child=spawn(python,['-u',path.join(__dirname,'qq_bridge.py')],{windowsHide:true,env:{...process.env,PYTHONUTF8:'1',MUSXI_PROFILE_DIR:request.profileDir || ''},stdio:['pipe','pipe','ignore']});
    this.child.on('error',()=>this.stop('QQ 运行环境未就绪，请运行 setup-cloud.ps1'));
    this.child.on('exit',()=>this.stop('QQ 接口连接已中断，请重新扫码'));
    readline.createInterface({input:this.child.stdout}).on('line',line=>{
      const pending=this.pending;if(!pending)return;this.pending=null;clearTimeout(pending.timer);
      try{const reply=JSON.parse(line);reply.ok?pending.resolve(reply.data):pending.reject(Error(reply.error || 'QQ 请求失败'));}catch{pending.reject(Error('QQ 接口返回数据无效'));}
    });
  }
  stop(message){const child=this.child;this.child=null;if(child)child.kill();if(this.pending){clearTimeout(this.pending.timer);this.pending.reject(Error(message));this.pending=null;}}
  async run(request){
    this.start(request);if(this.pending)throw Error('QQ 正在处理请求');
    const result=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>this.stop('QQ 请求超时，请稍后重试'),85000);this.pending={resolve,reject,timer};
      this.child.stdin.write(JSON.stringify(request)+'\n',error=>{if(error)this.stop('QQ 请求发送失败');});
    });
    if(request.op==='audio')return cacheAudio(result.url,result.track,result.quality,request.cacheDir);
    return result;
  }
}
module.exports={QQAdapter};
