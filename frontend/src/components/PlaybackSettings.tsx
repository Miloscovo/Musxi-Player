import { useEffect, useState } from 'react';
import { native } from '../native/client';
import type { AudioQuality, PlaybackSettings as Settings } from '../native/library';
import { Button } from './ui/button';

export default function PlaybackSettings() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    native.library.getPlaybackSettings(controller.signal).then(setSettings).catch(e => {
      if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '读取播放设置失败');
    });
    return () => controller.abort();
  }, []);
  async function update(next?: Pick<Settings, 'defaultQuality' | 'outputDevice'>) {
    setBusy(true);setError('');
    try {setSettings(await (next ? native.library.setPlaybackSettings(next) : native.library.getPlaybackSettings()));}
    catch(e) {setError(e instanceof Error ? e.message : '保存播放设置失败');}
    finally {setBusy(false);}
  }
  return <section className="settings-content" aria-label="播放设置" aria-busy={busy}>
    {error && <p className="playback-settings-error" role="alert">{error}</p>}
    {settings ? <>
      <div className="playback-setting">
        <label htmlFor="default-audio-quality">默认音质</label>
        <select id="default-audio-quality" value={settings.defaultQuality} disabled={busy}
          onChange={e => void update({ defaultQuality: e.currentTarget.value as AudioQuality, outputDevice: settings.outputDevice })}>
          <option value="128">标准 · 128 kbps</option><option value="320">高品质 · 320 kbps</option><option value="flac">无损 · FLAC</option>
        </select>
        <p>用于新播放的在线歌曲，不可用时降级；本地歌曲使用原始音质。</p>
      </div>
      <div className="playback-setting">
        <label htmlFor="audio-output-device">音频输出设备</label>
        <select id="audio-output-device" value={settings.outputDevice} disabled={busy}
          onChange={e => void update({ defaultQuality: settings.defaultQuality, outputDevice: e.currentTarget.value })}>
          <option value="">系统默认</option>
          {settings.outputDevice && !settings.devices.some(device => device.id === settings.outputDevice) && <option value={settings.outputDevice} disabled>已保存的设备不可用（使用系统默认）</option>}
          {settings.devices.map(device => <option key={device.id} value={device.id}>{device.name}</option>)}
        </select>
      </div>
    </> : !error && <p className="playback-settings-loading">正在读取播放设置…</p>}
    {!settings && error && <Button variant="secondary" disabled={busy} onClick={() => void update()}>重试</Button>}
  </section>;
}
