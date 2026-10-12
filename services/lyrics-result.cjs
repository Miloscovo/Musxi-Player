'use strict';
// Transport normalization only. Timeline parsing belongs to the C++ lyrics library.
function foundLyrics(text) {
  if(typeof text!=='string' || !text.trim() || Buffer.byteLength(text,'utf8')>1024*1024 || text.includes('\0'))
    throw Error('歌词内容无效');
  return {outcome:'found',format:'lrc',text};
}
module.exports={foundLyrics};
