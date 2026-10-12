import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';
import { parseLyrics, type Lyrics, type MusicPlatform } from './library.ts';
export interface MatchCandidate { key: string; name: string; artist: string; album: string; duration: number; platform: MusicPlatform }
export interface MatchState {
  status: 'pending' | 'ready'; id: string; session: string; job: string;
  candidates?: MatchCandidate[]; hasMore?: boolean; preview?: Lyrics | null; selectedName?: string; platform?: MusicPlatform | '';
  suggestion?: { title: string; artist: string; album: string; duration: number };
}
const platforms=['netease','qq','kugou'];
export function parseMatchState(value: unknown): MatchState {
  const v=value as MatchState;
  if(!v || !['pending','ready'].includes(v.status) || !['id','session','job'].every(k=>typeof (v as unknown as Record<string,unknown>)[k]==='string'))
    throw new NativeError(502,'Invalid match response');
  if(v.status==='pending')return v;
  if(!Array.isArray(v.candidates) || v.candidates.length>30 || typeof v.hasMore!=='boolean' || typeof v.selectedName!=='string' ||
    !['',...platforms].includes(v.platform || '') || !Object.hasOwn(v,'preview'))throw new NativeError(502,'Invalid match state');
  for(const c of v.candidates)if(!c || !['key','name','artist','album'].every(k=>typeof (c as unknown as Record<string,unknown>)[k]==='string') ||
    !platforms.includes(c.platform) || !Number.isFinite(c.duration) || c.duration<0)throw new NativeError(502,'Invalid match candidate');
  if(v.preview!==null) {parseLyrics(v.preview);if(v.preview?.id!==v.id)throw new NativeError(502,'Mismatched preview');}
  if(v.suggestion && (!['title','artist','album'].every(k=>typeof (v.suggestion as unknown as Record<string,unknown>)[k]==='string') ||
    !Number.isFinite(v.suggestion.duration) || v.suggestion.duration<0))throw new NativeError(502,'Invalid match suggestion');
  return v;
}
export function createLyricsMatchClient(host: CefTransport) {
  async function run(id: string, action: string, params: Record<string,unknown>, signal: AbortSignal,
    onSession?: (session: string) => void): Promise<MatchState> {
    const deadline=Date.now()+20000;
    const pause=()=>new Promise<void>(resolve=>{
      const done=()=>{clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
      const timer=setTimeout(done,150);signal.addEventListener('abort',done,{once:true});if(signal.aborted)done();
    });
    let query: Record<string,unknown>={...params,id,action};
    while(!signal.aborted) {
      const raw=await request(host,'library.lyricsMatch',query,{signal});
      signal.throwIfAborted();
      if((raw as {status?: string})?.status!=='busy') {
        const result=parseMatchState(raw);
        if(result.id!==id || (query.session && result.session!==query.session) || (query.action==='poll' && result.job!==query.job))throw new NativeError(502,'Mismatched matching session');
        onSession?.(result.session);
        if(result.status==='ready')return result;
        query={action:'poll',id,session:result.session,job:result.job};
      }
      if(Date.now()>=deadline)throw new NativeError(408,'歌词匹配请求超时，请重试');
      await pause();
    }
    signal.throwIfAborted();throw new Error('匹配已取消');
  }
  return { run, async cancel(id: string,session: string) {
    await request(host,'library.lyricsMatch',{action:'cancel',id,session});
  }};
}
