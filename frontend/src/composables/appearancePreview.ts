import { readAppearance, resolveTheme, type Appearance } from './appearance.ts';
export interface VisualSettings { appearance: Appearance; transparency: number; wallpaperEnabled: boolean; playerTransparent: boolean }
export interface VisualState { saved: VisualSettings; preview: VisualSettings | null }
export type VisualAction = { type:'appearance'; value:Appearance } | { type:'transparency'; value:number }
  | { type:'wallpaper' | 'player'; value:boolean } | { type:'begin' } | { type:'apply' } | { type:'cancel' } | { type:'resetWallpaper' };
export function readVisualSettings(storage: Pick<Storage,'getItem'>): VisualSettings {
  const saved = storage.getItem('musxi-transparency');const level = saved === null ? 50 : Number(saved);
  return { appearance:readAppearance(storage), transparency:Number.isFinite(level) ? Math.max(0,Math.min(100,level)) : 50,
    wallpaperEnabled:storage.getItem('musxi-wallpaper-enabled') === 'true', playerTransparent:storage.getItem('musxi-player-transparent') === 'true' };
}
export function reduceVisual(state:VisualState, action:VisualAction): VisualState {
  if (action.type === 'begin') return state.preview ? state : { ...state, preview:{ ...state.saved, appearance:{ ...state.saved.appearance,transparent:true },wallpaperEnabled:true } };
  if (action.type === 'cancel') return { ...state, preview:null };
  if (action.type === 'apply') return { saved:state.preview ?? state.saved, preview:null };
  if (action.type === 'resetWallpaper' || (action.type === 'wallpaper' && !action.value))
    return { saved:{ ...state.saved, wallpaperEnabled:false }, preview:null };
  if (action.type === 'appearance' && !action.value.transparent && state.preview)
    return { saved:{ ...state.saved, appearance:{ ...state.saved.appearance, transparent:false } }, preview:null };
  const current = state.preview ?? state.saved;
  const next = action.type === 'appearance' ? { ...current, appearance:action.value }
    : action.type === 'transparency' ? { ...current, transparency:Math.max(0,Math.min(100,action.value)) }
    : action.type === 'wallpaper' ? { ...current, wallpaperEnabled:action.value } : { ...current, playerTransparent:action.value };
  return state.preview ? { ...state, preview:next } : { saved:next, preview:null };
}
export function writeVisualSettings(storage:Pick<Storage,'getItem'|'setItem'>, value:VisualSettings, systemDark:boolean) {
  const entries:Record<string,string> = { 'musxi-theme':resolveTheme(value.appearance,systemDark), 'musxi-appearance-mode':value.appearance.mode,
    'musxi-transparent':String(value.appearance.transparent), 'musxi-tone':value.appearance.tone, 'musxi-transparency':String(value.transparency),
    'musxi-wallpaper-enabled':String(value.wallpaperEnabled), 'musxi-player-transparent':String(value.playerTransparent) };
  for (const [key,text] of Object.entries(entries)) if (storage.getItem(key) !== text) storage.setItem(key,text);
}
