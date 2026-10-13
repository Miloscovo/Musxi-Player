'use strict';
const {inflateSync}=require('node:zlib');
const {TextDecoder}=require('node:util');
const MAX_BYTES=1024*1024;
// KRC1 is a four-byte header followed by XOR-obfuscated zlib data.
const KEY=Buffer.from([64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105]);
function decodeKrc(content) {
  if(typeof content!=='string' || content.length>Math.ceil(MAX_BYTES/3)*4 ||
    content.length%4 || !/^[A-Za-z0-9+/]*={0,2}$/.test(content))throw Error('KRC 内容无效');
  const bytes=Buffer.from(content,'base64');
  if(bytes.toString('base64')!==content)throw Error('KRC 编码无效');
  if(bytes.length<=4 || bytes.length>MAX_BYTES || !bytes.subarray(0,4).equals(Buffer.from('krc1')))throw Error('KRC 文件头无效');
  const compressed=Buffer.from(bytes.subarray(4));
  for(let i=0;i<compressed.length;i++)compressed[i]^=KEY[i%KEY.length];
  // Bound during inflation, before allocating the complete decoded lyrics.
  const decoded=inflateSync(compressed,{maxOutputLength:MAX_BYTES});
  const text=new TextDecoder('utf-8',{fatal:true}).decode(decoded);
  if(!text.trim() || text.includes('\0'))throw Error('KRC 文本无效');
  return text;
}
module.exports={decodeKrc};
