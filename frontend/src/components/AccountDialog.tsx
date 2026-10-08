import { useState } from 'react';
import { Button } from './ui/button';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from './ui/dialog';
import { platformNames, safeImage, type MusicPlatform } from '../native/library';
import type { useLibrary } from '../composables/useLibrary';
import neteaseIcon from '../assets/platforms/netease.jpg';
import qqIcon from '../assets/platforms/qq.jpg';
import kugouIcon from '../assets/platforms/kugou.jpg';
const platforms: MusicPlatform[] = ['kugou', 'netease', 'qq'];
const platformIcons = { netease: neteaseIcon, qq: qqIcon, kugou: kugouIcon };
export function PlatformIcon({ platform }: {platform:MusicPlatform}) {
  return <img className="account-platform-icon" src={platformIcons[platform]} alt="" aria-hidden="true" width="40" height="40"/>;
}
export function AccountDialog({open,onOpenChange,library:lib,initialPlatform=null}:{open:boolean;onOpenChange:(open:boolean)=>void;library:ReturnType<typeof useLibrary>;initialPlatform?:MusicPlatform|null}) {
  const [platform,setPlatform]=useState<MusicPlatform | null>(initialPlatform);
  const [confirmLogout,setConfirmLogout]=useState(false);
  const busy=lib.pending || !!lib.state?.busy;
  const account=platform ? lib.state?.accounts?.find(a=>a.platform===platform) : undefined;
  const connected=account?.connected ?? (platform==='kugou' && !!lib.state?.connected);
  const avatar=connected ? safeImage(account?.avatar || (platform==='kugou' ? lib.state?.avatar || '' : '')) : '';
  function close(){setPlatform(null);setConfirmLogout(false);onOpenChange(false);}
  return <Dialog open={open} onOpenChange={next=>next ? onOpenChange(true) : close()}><DialogContent className="account-dialog">
    <div className="account-dialog-heading"><DialogTitle>{platform ? platformNames[platform] : '选择音乐平台'}</DialogTitle><Button variant="ghost" size="icon" aria-label="关闭账号窗口" onClick={close}>×</Button></div>
    <DialogDescription>{platform ? connected ? '账号已登录，其他平台账号可同时保持登录。' : `使用${platform==='qq' ? '手机 QQ' : platformNames[platform]+'手机应用'}扫描二维码并确认登录。` : '选择平台扫码登录，已登录账号的歌单将合并显示。'}</DialogDescription>
    {!platform ? <div className="account-platforms">{platforms.map(p=>{
      const a=lib.state?.accounts?.find(a=>a.platform===p);
      const signedIn=a?.connected ?? (p==='kugou' && lib.state?.connected);
      return <Button key={p} variant="ghost" className="account-platform-button" disabled={busy} onClick={()=>{setPlatform(p);setConfirmLogout(false);if(!signedIn)void lib.login(p);}}><PlatformIcon platform={p}/><strong>{platformNames[p]}</strong><small>{signedIn ? a?.user || '已登录' : '扫码登录'}</small></Button>;
    })}</div> : <>
      <div className="account-qr-body">{connected ? <><strong>{account?.user || lib.state?.user || '已登录'}</strong><div className="account-user-avatar">{avatar && <img src={avatar} alt={`${platformNames[platform!]}用户头像`}/>}</div></> : <>{lib.state?.qr && lib.state.loginPlatform===platform && <img className="qr" src={safeImage(lib.state.qr)} alt={`${platformNames[platform]}登录二维码`}/>}<p>{lib.state?.loginPlatform===platform ? lib.state.status : account?.error}</p></>}</div>
      {lib.error && <p role="alert">{lib.error}</p>}
      <div className="account-dialog-actions"><Button variant="secondary" onClick={()=>{setPlatform(null);setConfirmLogout(false);}}>选择其他平台</Button>{connected ? <Button variant={confirmLogout ? 'destructive' : 'secondary'} disabled={busy} onClick={()=>{if(!confirmLogout)setConfirmLogout(true);else {void lib.logout(platform);setConfirmLogout(false);}}}>{confirmLogout ? '确认退出此平台' : '退出此平台'}</Button> : <Button disabled={busy} onClick={()=>void lib.login(platform)}>刷新二维码</Button>}</div>
    </>}
  </DialogContent></Dialog>;
}
