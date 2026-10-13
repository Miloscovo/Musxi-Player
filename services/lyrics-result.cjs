'use strict';
// Transport normalization only. Timeline parsing belongs to the C++ lyrics library.
const MAX_BYTES=1024*1024;
function usable(text) {
  return typeof text==='string' && Buffer.byteLength(text,'utf8')<=MAX_BYTES && !text.includes('\0');
}
// translation: undefined = this platform cannot report one (state unknown).
// A damaged translation never rejects the original; it stays unknown and may be retried.
function foundLyrics(text,translation) {
  if(!usable(text) || !text.trim())throw Error('歌词内容无效');
  const result={outcome:'found',format:'lrc',text,translationState:'unknown'};
  if(translation===undefined)return result;
  if(translation===null || (typeof translation==='string' && !translation.trim()))return {...result,translationState:'none'};
  if(!usable(translation) || /^[0-9a-fA-F]+$/.test(translation.trim()))return result;
  return {...result,translationState:'available',translation};
}
module.exports={foundLyrics};
