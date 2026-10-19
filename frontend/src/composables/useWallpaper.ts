import { useEffect, useRef, useState } from 'react';
import { native } from '../native/client';
import type { WallpaperImage } from '../native/wallpaper';
import type { useWindow } from './useWindow';

async function decodeWallpaper(image:WallpaperImage) {
  const picture = new Image();picture.src=image.url;
  try { await picture.decode(); }
  catch { throw new Error('图片无法解码，请选择有效的静态图片'); }
  if (!picture.naturalWidth || !picture.naturalHeight || picture.naturalWidth>16384 || picture.naturalHeight>16384
    || picture.naturalWidth*picture.naturalHeight>40000000) throw new Error('图片尺寸无效或过大');
}
export function useWallpaper(shell:ReturnType<typeof useWindow>) {
  const [saved,setSaved] = useState<WallpaperImage|null>(null);
  const [candidate,setCandidate] = useState<WallpaperImage|null>(null);
  const [previewImage,setPreviewImage] = useState<WallpaperImage|null>(null);
  const [loaded,setLoaded] = useState(false),[busy,setBusy] = useState(false),[error,setError] = useState('');
  const locked = useRef(false), current = useRef(shell);current.current=shell;
  const failure = (e:unknown) => setError(e instanceof Error ? e.message : '壁纸操作失败，请重试');
  useEffect(() => {
    let active=true;
    native.wallpaper.getState().then(async image => {
      if(image)await decodeWallpaper(image);
      if(active){setSaved(image);if(!image && current.current.wallpaperEnabled)setError('未找到已保存的壁纸，已使用默认背景，请重新选择图片。');}
    })
      .catch(() => { if(active)setError('已保存的壁纸无法读取，已使用默认背景，请重新选择图片。'); })
      .finally(() => { if(active)setLoaded(true); });
    return () => { active=false; };
  },[]);
  useEffect(() => { if(!shell.preview)setPreviewImage(null); },[shell.preview]);
  async function choose() {
    if(locked.current || !loaded)return;
    locked.current=true;setBusy(true);setError('');let incoming:WallpaperImage|null=null;
    try {
      incoming=await native.wallpaper.choose();if(!incoming)return;
      await decodeWallpaper(incoming);await native.wallpaper.accept(incoming.id);
      setCandidate(incoming);if(current.current.preview)setPreviewImage(incoming);
    } catch(e) {
      if(incoming)try{await native.wallpaper.discard(incoming.id);}catch{/* A later choice or shutdown may already have released it. */}
      failure(e);
    } finally { locked.current=false;setBusy(false); }
  }
  function preview() {
    if(locked.current || !loaded)return;
    const image=candidate ?? saved;if(!image)return;
    setError('');setPreviewImage(image);shell.beginPreview();
  }
  function cancel() { if(locked.current)return;shell.cancelPreview();setPreviewImage(null); }
  async function apply() {
    if(locked.current || !shell.preview || !previewImage)return;
    locked.current=true;setBusy(true);setError('');
    try {
      const image=await native.wallpaper.apply(previewImage.id);if(!image)throw new Error('壁纸已丢失，请重新选择');
      setSaved(image);setCandidate(null);setPreviewImage(null);current.current.applyPreview();
    }catch(e){failure(e);}finally{locked.current=false;setBusy(false);}
  }
  async function reset() {
    if(locked.current)return;locked.current=true;setBusy(true);setError('');
    try { await native.wallpaper.reset();setSaved(null);setCandidate(null);setPreviewImage(null);current.current.resetWallpaper(); }
    catch(e){failure(e);}finally{locked.current=false;setBusy(false);}
  }
  const image=shell.preview ? previewImage : saved;
  return { loaded,busy,error, thumbnail:candidate ?? saved, previewing:shell.preview,
    image:shell.appearance.transparent && shell.wallpaperEnabled ? image : null,
    choose,preview,cancel,apply,reset,
    imageFailed:() => { if(shell.preview)shell.cancelPreview();else setSaved(null);setError('壁纸无法显示，已恢复默认背景。'); } };
}
export type WallpaperController = ReturnType<typeof useWallpaper>;
