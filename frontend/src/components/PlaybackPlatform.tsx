import { useEffect, useState } from 'react';
import type { useLibrary } from '../composables/useLibrary';
import { platformNames, type MusicPlatform } from '../native/library';
import { Button } from './ui/button';
import { Popover, PopoverTrigger, PopoverContent } from './ui/popover';

const labels = { netease: '网易云', kugou: '酷狗', qq: 'QQ', local: '本地' };
export default function PlaybackPlatform({ library, currentId }: {
  library: ReturnType<typeof useLibrary>; currentId: string;
}) {
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [currentId]);
  if (!currentId) return null;
  const platform = library.state?.now.platform || (currentId.startsWith('local:') ? 'local' : currentId.startsWith('netease:') ? 'netease' : currentId.startsWith('qq:') ? 'qq' : 'kugou');
  if (platform === 'local') return <span className="song-platform-tag playback-platform local">本地</span>;
  const busy = library.pending || library.state?.operation.status === 'pending';
  return <Popover open={open} onOpenChange={setOpen}>
    <PopoverTrigger asChild><Button variant="unstyled" className={`song-platform-tag playback-platform ${platform}`} aria-label="切换播放平台">{labels[platform]}</Button></PopoverTrigger>
    <PopoverContent side="top" sideOffset={16} className="player-action-menu" aria-label="播放平台">
      {(['netease', 'kugou', 'qq'] as MusicPlatform[]).filter(target => target === platform || library.state?.now.platforms?.includes(target)).map(target => {
        const connected = library.state?.accounts?.find(account => account.platform === target)?.connected ?? (target === 'kugou' && library.state?.connected);
        return <Button key={target} variant="unstyled" aria-pressed={platform === target} disabled={busy || !connected}
          onClick={() => { if (target !== platform) void library.setPlatform(currentId, target); setOpen(false); }}>
          {platformNames[target]}{!connected && '（未登录）'}
        </Button>;
      })}
    </PopoverContent>
  </Popover>;
}
