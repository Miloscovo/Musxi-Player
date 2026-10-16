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
// Only stable codes cross IPC. Never classify errors by private response text.
function errorReason(error) {
  if(['network','timeout','platform_error','invalid_data','unsupported_format','version_mismatch','internal_error'].includes(error?.lyricsReason))return error.lyricsReason;
  if(['ETIMEDOUT','ECONNABORTED','UND_ERR_CONNECT_TIMEOUT','UND_ERR_HEADERS_TIMEOUT'].includes(error?.code) || error?.name==='TimeoutError')return 'timeout';
  if(['ENOTFOUND','EAI_AGAIN','ECONNREFUSED','ECONNRESET','ENETUNREACH','EHOSTUNREACH','ERR_NETWORK'].includes(error?.code))return 'network';
  return 'internal_error';
}
function lyricsError(reason,message='歌词请求失败') {return Object.assign(Error(message),{lyricsReason:reason});}
module.exports={foundLyrics,errorReason,lyricsError};
