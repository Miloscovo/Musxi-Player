import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { useLibrary } from '../composables/useLibrary';
import type { PlaybackOrder } from '../native/library';
import { platformNames, type MusicPlatform } from '../native/library';
import { Button } from './ui/button';
import { Popover, PopoverTrigger, PopoverContent } from './ui/popover';

const orders: { id: PlaybackOrder; name: string; path: string }[] = [
  { id: 'sequential', name: '顺序播放', path: 'M4 6h13m-3-3 3 3-3 3M20 18H7m3-3-3 3 3 3' },
  { id: 'random', name: '随机播放', path: 'M3 5h3l12 14h3m-3-3 3 3-3 3M3 19h3l4-5m4-4 4-5h3m-3-3 3 3-3 3' },
  { id: 'repeat-one', name: '单曲循环', path: 'M4 10V6h15m-3-3 3 3-3 3M20 14v4H5m3-3-3 3 3 3M11 10l2-1v6' },
];

export default function PlayerActions({ library, currentId, onNotice, children }: {
  library: ReturnType<typeof useLibrary>; currentId: string; onNotice: (text: string) => void; children: ReactNode;
}) {
  const [addOpen, setAddOpen] = useState(false);
  const [orderOpen, setOrderOpen] = useState(false);
  const [qualityOpen, setQualityOpen] = useState(false);
  const [favoriteOpen, setFavoriteOpen] = useState(false);
  const fetched = useRef({ track: '', update: '' });
  const local = currentId.startsWith('local:');
  const state = library.state;
  const update = state?.operation.kind === 'song_update' && state.operation.status === 'completed' ? state.operation.id : '';
  useEffect(() => {
    if (!currentId || local || !state?.connected || state.busy || library.pending
      || (fetched.current.track === currentId && (!update || fetched.current.update === update))) return;
    fetched.current = { track: currentId, update };
    void library.menu(currentId);
  }, [currentId, local, update, state?.connected, state?.busy, library.pending, library.menu]);
  const ready = state?.menu.id === currentId;
  const currentPlatform=state?.now.platform || (currentId.startsWith('netease:')?'netease':currentId.startsWith('qq:')?'qq':'kugou');
  const providers=ready ? state.menu.providers : undefined;
  const liked=!local && (providers?.some(provider=>provider.liked) || !!state?.now.liked);
  useEffect(()=>setFavoriteOpen(false),[currentId]);
  const unavailable = !currentId || library.pending || (!local && (!state?.connected || state.busy));
  const order = orders.find(item => item.id === state?.playbackOrder) || orders[0];
  const qualityReady=state?.qualities?.id===currentId;
  function localNotice() { onNotice('本地歌曲无法收藏和添加到歌单'); }
  return <div className="play-controls">
    <Popover open={favoriteOpen} onOpenChange={setFavoriteOpen}>
    <PopoverTrigger asChild><Button variant="unstyled" aria-label={liked ? '取消收藏当前歌曲' : '收藏当前歌曲'}
      aria-pressed={liked} disabled={unavailable} className="player-favorite"
      onClick={event => { if(local) {event.preventDefault();localNotice();} else void library.menu(currentId); }}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20S3 14.5 3 8.5a4.5 4.5 0 0 1 9-1 4.5 4.5 0 0 1 9 1C21 14.5 12 20 12 20Z"/></svg>
    </Button></PopoverTrigger>
    <PopoverContent side="top" sideOffset={16} className="player-action-menu" aria-label="收藏平台">
      {ready ? (['netease','kugou','qq'] as MusicPlatform[]).filter(platform=>(state.accounts?.find(account=>account.platform===platform)?.connected ?? (platform==='kugou' && state.connected)) && (platform===currentPlatform || state.now.platforms?.includes(platform) || providers?.some(provider=>provider.platform===platform))).map(platform=>{
        const provider=providers?.find(provider=>provider.platform===platform);
        const selected=provider?.liked ?? (platform===currentPlatform && state.now.liked) ?? false;
        const canFavorite=provider?.canFavorite ?? (!providers && platform===currentPlatform && state.menu.canFavorite);
        return <Button key={platform} variant="unstyled" aria-pressed={selected} disabled={library.pending || !canFavorite}
          onClick={()=>{void library.favorite(currentId,!selected,provider?platform:undefined);setFavoriteOpen(false);}}>
          {platformNames[platform]}
        </Button>;
      }) : <span>正在获取收藏状态…</span>}
    </PopoverContent>
    </Popover>
    <Popover open={addOpen} onOpenChange={setAddOpen}>
      <PopoverTrigger asChild><Button variant="unstyled" aria-label="添加当前歌曲到歌单" disabled={unavailable}
        onClick={event => { if (local) { event.preventDefault(); localNotice(); } else void library.menu(currentId); }}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 5h12M3 10h12M3 15h7M17 12v9m-4-4.5h8"/></svg>
      </Button></PopoverTrigger>
      <PopoverContent side="top" sideOffset={16} className="player-action-menu" aria-label="添加到歌单">
        <strong>添加到歌单</strong>
        {ready && state.menu.playlists.map(list => <Button key={list.id} variant="unstyled" disabled={library.pending}
          onClick={() => { void library.add(currentId, list.id); setAddOpen(false); }}>{list.name}</Button>)}
        {(!ready || !state?.menu.playlists.length) && <span>{ready ? '暂无可添加的歌单' : '正在获取歌单…'}</span>}
      </PopoverContent>
    </Popover>
    {children}
    <Popover open={orderOpen} onOpenChange={setOrderOpen}>
      <PopoverTrigger asChild><Button variant="unstyled" aria-label={`播放顺序：${order.name}`} title={order.name}>
        <svg viewBox="-4 -4 32 32" aria-hidden="true"><path d={order.path}/></svg>
      </Button></PopoverTrigger>
      <PopoverContent side="top" sideOffset={16} className="player-action-menu" aria-label="播放顺序">
        {orders.map(item => <Button key={item.id} variant="unstyled" aria-pressed={order.id === item.id} disabled={library.pending}
          onClick={() => { void library.setPlaybackOrder(item.id); setOrderOpen(false); }}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d={item.path}/></svg>{item.name}
        </Button>)}
      </PopoverContent>
    </Popover>
    <Popover open={qualityOpen} onOpenChange={setQualityOpen}>
      <PopoverTrigger asChild><Button variant="unstyled" aria-label="音质选择" title="音质选择" disabled={unavailable}
        onClick={() => { if(!local)void library.qualities(currentId); }}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 14v-3a7 7 0 0 1 14 0v3M5 12H4v7h3v-7H5Zm14 0h1v7h-3v-7h2Z"/></svg>
      </Button></PopoverTrigger>
      <PopoverContent side="top" sideOffset={16} className="player-action-menu" aria-label="音质选择">
        <strong>音质选择</strong>
        {local ? <Button variant="unstyled" aria-pressed="true" onClick={() => setQualityOpen(false)}>原始音质</Button> :
          qualityReady && state.qualities!.options.length ? state.qualities!.options.map(option =>
            <Button key={option.id} variant="unstyled" aria-pressed={state.qualities!.current===option.id} disabled={library.pending || state.busy}
              onClick={() => { void library.setQuality(currentId,option.id);setQualityOpen(false); }}>{option.name}</Button>) :
            <span>{state?.operation.kind==='qualities' && state.operation.status==='pending' || library.pending ? '正在获取可用音质…' : '暂无可确认的可用音质'}</span>}
      </PopoverContent>
    </Popover>
  </div>;
}
