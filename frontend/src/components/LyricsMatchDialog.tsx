import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from './ui/dialog';
import { Button } from './ui/button';
import { native } from '../native/client';
import { platformNames, type MusicPlatform } from '../native/library';
import type { MatchState } from '../native/lyricsMatch';
import { activeLyricLine } from '../composables/lyrics';
import { useLyricsTranslation } from '../composables/lyricsTranslation';

const durationText=(ms: number)=>`${Math.floor(ms/60000)}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}`;
export default function LyricsMatchDialog({id,positionMs,canSeek,onSeek,onClose,onSaved}: {
  id: string; positionMs: number; canSeek: boolean; onSeek: (ms:number)=>void; onClose:()=>void; onSaved:()=>void;
}) {
  const [state,setState]=useState<MatchState>();const [busy,setBusy]=useState(true);const [error,setError]=useState('');
  const [keywords,setKeywords]=useState('');const [platform,setPlatform]=useState<MusicPlatform>('netease');
  const [localDuration,setLocalDuration]=useState(0);const [page,setPage]=useState(1);const [offset,setOffset]=useState('0');
  const session=useRef('');const controller=useRef<AbortController | null>(null);const mounted=useRef(true);
  const list=useRef<HTMLOListElement>(null);const scrollAt=useRef(0);const [selected,setSelected]=useState('');
  const preview=state?.preview;const lines=preview?.lines || [];const active=preview?.kind==='synced'?activeLyricLine(lines,positionMs):-1;
  const [showTranslation,setShowTranslation]=useLyricsTranslation();
  const translated=preview?.kind==='synced' && lines.some(line=>line.translation);const dual=translated && showTranslation;
  async function perform(action:string,params:Record<string,unknown>={}) {
    controller.current?.abort();const task=new AbortController();controller.current=task;
    setBusy(true);setError('');
    if(action==='search' || action==='preview')setState(value=>value?{...value,preview:null,selectedName:'',platform:''}:value);
    try {
      const value=await native.lyricsMatch.run(id,action,{...params,...(action==='begin'?{}:{session:session.current})},task.signal,
        value=>{session.current=value;});
      if(!mounted.current || task.signal.aborted)return;
      setState(value);setOffset(String((value.preview?.offsetMs || 0)/1000));
      if(action==='begin' && value.suggestion) {
        setKeywords([value.suggestion.title,value.suggestion.artist].filter(Boolean).join(' ').slice(0,120));setLocalDuration(value.suggestion.duration);
      }
      if(action==='save' || action==='reset'){onSaved();onClose();}
    }catch(e){if(mounted.current && !task.signal.aborted)setError(e instanceof Error?e.message:'请求失败，请重试');}
    finally{if(mounted.current && !task.signal.aborted)setBusy(false);}
  }
  useEffect(()=>{
    mounted.current=true;void perform('begin');
    return ()=>{mounted.current=false;controller.current?.abort();if(session.current)void native.lyricsMatch.cancel(id,session.current).catch(()=>{});};
  },[id]);
  useLayoutEffect(()=>{
    const row=list.current?.children[Math.max(0,active)] as HTMLElement | undefined;
    if(list.current && row && Date.now()-scrollAt.current>3000)list.current.scrollTo({top:row.offsetTop-list.current.clientHeight/2+row.clientHeight/2,behavior:'smooth'});
  },[active,lines,dual]);
  const offsetMs=Math.round(Number(offset)*1000);const validOffset=offset.trim()!=='' && Number.isFinite(offsetMs) && Math.abs(offsetMs)<=600000;
  function search(nextPage:number){if(busy || !keywords.trim() || !state)return;setPage(nextPage);setSelected('');void perform('search',{platform,keywords:keywords.trim(),page:nextPage});}
  function adjust(ms:number){void perform('adjust',{offsetMs:ms});}
  return <Dialog open onOpenChange={open=>{if(!open)onClose();}}><DialogContent className="lyrics-match-dialog">
    <div className="lyrics-match-heading"><DialogTitle>为本地歌曲选择歌词</DialogTitle><Button variant="secondary" size="sm" onClick={onClose}>关闭</Button></div>
    <DialogDescription>先预览，再确认使用。指定歌词会替代当前自动选择，原歌词文件保持不变。</DialogDescription>
    <form className="lyrics-match-search" onSubmit={e=>{e.preventDefault();search(1);}}>
      <label>平台<select value={platform} disabled={busy} onChange={e=>{setPlatform(e.target.value as MusicPlatform);setState(v=>v?{...v,candidates:[],hasMore:false}:v);setPage(1);}}>
        {(['netease','qq','kugou'] as const).map(p=><option key={p} value={p}>{platformNames[p]}</option>)}</select></label>
      <label className="lyrics-match-query">歌名 / 歌手<input maxLength={120} value={keywords} onChange={e=>setKeywords(e.target.value)} placeholder="输入歌名或歌手"/></label>
      <Button disabled={busy || !keywords.trim() || !state} type="submit">搜索</Button>
    </form>
    <div className="lyrics-match-body">
      <div className="lyrics-match-results"><p className="lyrics-match-hint">本地时长 {durationText(localDuration)} · 同名歌曲可能有不同版本</p>
        <ul aria-label="歌词候选">{state?.candidates?.map(c=><li key={c.key}><Button variant="ghost" disabled={busy} aria-pressed={selected===c.key} onClick={()=>{setSelected(c.key);void perform('preview',{candidate:c.key});}}>
          <strong>{c.name}</strong><span>{c.artist} · {c.album || '未知专辑'}</span>
          <small>{platformNames[c.platform]} · {durationText(c.duration)}{localDuration>0 && c.duration>0?` · 相差 ${Math.round(Math.abs(c.duration-localDuration)/1000)} 秒`:''}</small>
        </Button></li>)}</ul>
        {state?.candidates?.length===0 && !busy && <p>输入关键词搜索，或换个平台试试。</p>}
        <div className="lyrics-match-pages"><Button variant="secondary" size="sm" disabled={busy || page<=1} onClick={()=>search(page-1)}>上一页</Button><span>第 {page} 页</span><Button variant="secondary" size="sm" disabled={busy || !state?.hasMore || page>=10} onClick={()=>search(page+1)}>下一页</Button></div>
      </div>
      <div className="lyrics-match-preview"><strong>{state?.selectedName || '歌词预览'}</strong><small>{state?.platform?platformNames[state.platform]:''}</small>
        {translated && <Button variant="unstyled" className="lyrics-translation-toggle" role="switch" aria-checked={showTranslation}
          onClick={()=>setShowTranslation(!showTranslation)}><span aria-hidden="true"/>显示翻译</Button>}
        {preview ? <ol ref={list} className={dual?'dual':undefined} onWheel={()=>{scrollAt.current=Date.now();}} onPointerDown={()=>{scrollAt.current=Date.now();}} onKeyDown={()=>{scrollAt.current=Date.now();}}>
          {lines.map((line,index)=><li key={index} aria-current={active===index?'true':undefined}>{preview.kind==='synced'?<Button variant="ghost" disabled={!canSeek} onClick={()=>onSeek(line.timeMs)}>{line.text || '♪'}{dual && line.translation && <small>{line.translation}</small>}</Button>:<span>{line.text || '♪'}</span>}</li>)}
        </ol>:<p>{selected?'此候选暂无可用歌词，请选择其他版本。':'选择候选后，可随当前音频预览歌词。'}</p>}
      </div>
    </div>
    <div className="lyrics-match-offset"><span>时间校正</span><Button variant="secondary" size="sm" disabled={busy || preview?.kind!=='synced'} onClick={()=>adjust((preview?.offsetMs || 0)-500)}>提前 0.5 秒</Button>
      <Button variant="secondary" size="sm" disabled={busy || preview?.kind!=='synced'} onClick={()=>adjust((preview?.offsetMs || 0)+500)}>延后 0.5 秒</Button>
      <label>秒<input type="number" min={-600} max={600} step={0.5} value={offset} disabled={busy || preview?.kind!=='synced'} onChange={e=>setOffset(e.target.value)}/></label>
      <Button variant="secondary" size="sm" disabled={busy || !preview || !validOffset} onClick={()=>adjust(offsetMs)}>应用到预览</Button>
      <Button variant="ghost" size="sm" disabled={busy || !preview} onClick={()=>adjust(0)}>重置时间</Button>
    </div>
    <p className="lyrics-match-hint">正数让歌词延后，负数让歌词提前。整体校正无法修复中途剪辑或变速。</p>
    <div role="status" aria-live="polite">{busy?'正在处理…':error || (preview?`预览校正：${(preview.offsetMs || 0)/1000} 秒`:'')}</div>
    <div className="lyrics-match-actions"><Button variant="secondary" disabled={busy || !state} onClick={()=>void perform('reset')}>恢复自动选择</Button>
      {!state && !busy && <Button variant="secondary" onClick={()=>void perform('begin')}>重新加载</Button>}
      <Button disabled={busy || !preview || !validOffset} onClick={()=>void perform('save',{offsetMs})}>使用这份歌词</Button></div>
  </DialogContent></Dialog>;
}
