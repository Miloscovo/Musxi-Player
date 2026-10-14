'use strict';
const LIMIT=256*1024;
function audioHeader(bytes) {
  if(bytes.length>=42 && bytes.toString('latin1',0,4)==='fLaC' && (bytes[4]&127)===0 && bytes.readUIntBE(5,3)===34) {
    const packed=bytes.readBigUInt64BE(18);
    const rate=Number(packed>>44n),bits=Number((packed>>36n)&31n)+1,samples=Number(packed&0xfffffffffn);
    if(rate<8000 || bits<8 || bits>32 || !samples)return null;
    return {extension:'.flac',rate,bits,duration:samples/rate*1000};
  }
  return null;
}
async function rangeBytes(url,range) {
  const response=await fetch(url,{headers:{Range:range},signal:AbortSignal.timeout(12000)});
  if(!response.ok || !response.body)throw Error('音频格式检查失败，请稍后重试');
  const chunks=[];let size=0;
  for await(const chunk of response.body){const part=Buffer.from(chunk).subarray(0,LIMIT-size);chunks.push(part);size+=part.length;if(size>=LIMIT)break;}
  return Buffer.concat(chunks);
}
async function inspectAudio(url) {
  const head=await rangeBytes(url,`bytes=0-${LIMIT-1}`);
  const info=audioHeader(head);if(info)return info;
  throw Error('该高级音质为加密或暂不支持的音频格式，已保留当前播放');
}
module.exports={audioHeader,inspectAudio};
