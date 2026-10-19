import { request, type CefTransport } from './transport.ts';
import { NativeError } from './types.ts';
export interface WallpaperImage { id:string; url:string; width:number; height:number }
export function parseWallpaper(value:unknown):WallpaperImage | null {
  if (value === null) return null;
  const image = value as WallpaperImage;
  if (!image || !/^(saved|candidate-[0-9]+)$/.test(image.id) || typeof image.url !== 'string'
    || image.url.length > Math.ceil(32*1024*1024/3)*4+23
    || !/^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(image.url)
    || !Number.isInteger(image.width) || !Number.isInteger(image.height) || image.width < 1 || image.height < 1
    || image.width > 16384 || image.height > 16384 || image.width*image.height > 40000000) throw new NativeError(502,'无效壁纸图片');
  return image;
}
export function createWallpaperClient(host:CefTransport) {
  return Object.freeze({
    getState:async () => parseWallpaper(await request(host,'wallpaper.getState',{})),
    choose:async () => parseWallpaper(await request(host,'wallpaper.choose',{}, {},24*60*60*1000)),
    accept:async (id:string) => { await request(host,'wallpaper.accept',{id}); },
    discard:async (id:string) => { await request(host,'wallpaper.discard',{id}); },
    apply:async (id:string) => parseWallpaper(await request(host,'wallpaper.apply',{id})),
    reset:async () => { await request(host,'wallpaper.reset',{}); },
  });
}
