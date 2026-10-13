import type { Lyrics, Track, MusicPlatform } from '../native/library.ts';
import type { MatchState } from '../native/lyricsMatch.ts';
export function createDemoLyricsMatch(track:(id:string)=>Track) {
  const saved=new Map<string,{original:Lyrics;preview:Lyrics;name:string;platform:MusicPlatform | ''}>();let sequence=0;let state:MatchState | undefined;
  let original:Lyrics | null=null;
  function run(p:Record<string,unknown>) {
    const id=String(p.id),song=track(id);
    if(!id.startsWith('local:'))throw Error('请选择本地歌曲');
    if(p.action==='cancel'){if(state?.session===p.session)state=undefined;return {status:'cancelled'};}
    if(p.action==='begin') {
      const binding=saved.get(id);original=binding?.original || null;
      state={status:'ready',id,session:String(++sequence),job:String(sequence),candidates:[],hasMore:false,
        preview:binding?.preview || null,selectedName:binding?.name || '',platform:binding?.platform || '',
        suggestion:{title:song.name.replace(/\.[^.]+$/,''),artist:song.artist,album:song.album || '',duration:song.duration}};
      return state;
    }
    if(!state || state.id!==id || state.session!==p.session)throw Error('匹配会话已过期');
    if(p.action==='search') {
      original=null;state={...state,preview:null,selectedName:'',platform:'',candidates:Number(p.page)===1?[{key:'0',name:String(p.keywords),artist:'演示歌手',album:'演示录音室版',duration:song.duration,platform:p.platform as MusicPlatform}]:[]};
    }else if(p.action==='preview') {
      const candidate=state.candidates?.find(c=>c.key===p.candidate);if(!candidate)throw Error('候选已过期');
      original={status:'ready',source:'local',id,platform:'local',origin:'matched',offsetMs:0,kind:'synced',translation:'available',
        lines:Array.from({length:30},(_,i)=>({timeMs:8000*i,text:`Matched demo lyric · line ${i+1}`,...(i%5!==2?{translation:`匹配演示译文 · 第 ${i+1} 行`}:{})}))};
      state={...state,selectedName:candidate.name,platform:candidate.platform,preview:original};
    }else if(p.action==='adjust' || p.action==='save') {
      const offset=Number(p.offsetMs);if(!original || !Number.isInteger(offset) || Math.abs(offset)>600000)throw Error('时间校正无效');
      const baseOffset=original.offsetMs || 0;
      const preview={...original,offsetMs:offset,lines:original.lines.map(line=>({...line,timeMs:Math.max(0,line.timeMs-baseOffset+offset)}))};
      state={...state,preview};if(p.action==='save')saved.set(id,{original,preview,name:state.selectedName || '',platform:state.platform || ''});
    }else if(p.action==='reset'){saved.delete(id);original=null;state={...state,preview:null,selectedName:'',platform:''};}
    return state;
  }
  return {run,lyrics:(id:string)=>saved.get(id)?.preview};
}
