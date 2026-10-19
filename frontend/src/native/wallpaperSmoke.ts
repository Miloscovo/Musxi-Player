import { native } from './client';
import { request,type CefTransport } from './transport';

async function waitFor(check:()=>boolean,message='Wallpaper UI did not settle') {
  const until=Date.now()+3000;while(!check()){if(Date.now()>until)throw new Error(`${message}: ${check.toString()}`);await new Promise(resolve=>setTimeout(resolve,20));}
}
const button=(scope:string,text:string)=>Array.from(document.querySelectorAll<HTMLButtonElement>(`${scope} button`)).find(item=>item.textContent?.trim()===text)!;
const toggle=(label:string)=>document.querySelector<HTMLButtonElement>(`[aria-labelledby=${label}]`)!;
async function setToggle(label:string,on:boolean) {
  const control=toggle(label);if((control.getAttribute('aria-checked')==='true')!==on)control.click();
  await waitFor(()=>toggle(label).getAttribute('aria-checked')===String(on));
}
async function appearancePage() {
  document.querySelector<HTMLButtonElement>('.settings-nav')!.click();await waitFor(()=>!!document.querySelector('.settings-sections'));
  document.querySelector<HTMLButtonElement>('.settings-sections button')!.click();await waitFor(()=>!!document.querySelector('[aria-labelledby=wallpaper-label]'));
}
async function level(value:number) {
  const input=document.querySelector<HTMLInputElement>('#appearance-transparency')!;
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,String(value));input.dispatchEvent(new Event('input',{bubbles:true}));
  await waitFor(()=>input.value===String(value) && document.querySelector('.appearance-level output')?.textContent===`${value}%`);
}
export async function verifyWallpaperUI() {
  const host=window as unknown as CefTransport;
  const savedMode=localStorage.getItem('musxi-appearance-mode')||'light',savedTone=localStorage.getItem('musxi-tone')||'green';
  const savedLevel=Number(localStorage.getItem('musxi-transparency')||50),savedTransparent=localStorage.getItem('musxi-transparent')==='true';
  const savedPlayer=localStorage.getItem('musxi-player-transparent')==='true';
  const nextChoice=(format:string)=>request(host,'wallpaper.testNextChoice',{format});
  try {
    await appearancePage();await setToggle('transparency-label',true);await setToggle('player-transparency-label',true);
    await setToggle('transparency-label',false);
    if(!toggle('wallpaper-label').disabled)throw new Error('Wallpaper enabled without main transparency');
    if(!document.querySelector<HTMLInputElement>('#appearance-transparency')?.disabled || !document.querySelector('.wallpaper-thumbnail')
      || button('.wallpaper-actions',document.querySelector('.wallpaper-thumbnail img')?'更换壁纸':'选择图片').disabled)
      throw new Error('Wallpaper selection must remain available without transparency');
    if(!toggle('player-transparency-label').disabled || toggle('player-transparency-label').getAttribute('aria-checked')!=='true'
      || getComputedStyle(document.documentElement).getPropertyValue('--player-background-opacity').trim()!=='1')throw new Error('Dock transparency was not suspended with its preference retained');
    button('.wallpaper-actions','恢复默认').click();await waitFor(()=>toggle('wallpaper-label').getAttribute('aria-checked')==='false' && !document.querySelector('.wallpaper-thumbnail img'));
    await nextChoice('png');button('.wallpaper-actions','选择图片').click();await waitFor(()=>!!document.querySelector('.wallpaper-thumbnail img'));
    if(document.querySelector('.app-wallpaper-layer') || await native.wallpaper.getState())throw new Error('Candidate wallpaper applied before preview');
    button('.wallpaper-actions','预览效果').click();await waitFor(()=>!!document.querySelector('.wallpaper-preview-bar'));
    if(toggle('transparency-label').getAttribute('aria-checked')!=='true' || toggle('wallpaper-label').getAttribute('aria-checked')!=='true'
      || localStorage.getItem('musxi-transparent')!=='false' || toggle('player-transparency-label').disabled)
      throw new Error('Preview must temporarily enable transparency and wallpaper without saving');
    const persisted=localStorage.getItem('musxi-appearance-mode'),persistedLevel=localStorage.getItem('musxi-transparency');
    const veilAlpha=()=>{
      const canvas=document.createElement('canvas');canvas.width=canvas.height=1;const context=canvas.getContext('2d')!;
      context.fillStyle=getComputedStyle(document.querySelector('.app-wallpaper-layer')!,'::after').backgroundColor;
      context.fillRect(0,0,1,1);return context.getImageData(0,0,1,1).data[3];
    };
    for(const mode of ['浅色','深色']) {
      button('[aria-labelledby=appearance-mode-label]',mode).click();
      await level(50);if(veilAlpha()<=0 || veilAlpha()>=255)throw new Error('Wallpaper veil must soften intermediate transparency');
      await level(100);if(veilAlpha()!==0)throw new Error('Wallpaper veil must be fully transparent at 100 percent');
    }
    button('[aria-labelledby=appearance-mode-label]','深色').click();await waitFor(()=>document.documentElement.dataset.theme==='glass');
    await level(70);
    if(localStorage.getItem('musxi-appearance-mode')!==persisted || localStorage.getItem('musxi-transparency')!==persistedLevel)throw new Error('Preview wrote appearance preferences');
    const layer=document.querySelector('.app-wallpaper-layer');
    button('.sidebar','最近播放').click();await waitFor(()=>!!document.querySelector('.recent-title'));
    if(!document.querySelector('.app-wallpaper-layer'))throw new Error('Wallpaper vanished on navigation');
    await appearancePage();
    if(layer!==document.querySelector('.app-wallpaper-layer'))throw new Error('Navigation remounted the wallpaper');
    button('.wallpaper-preview-bar','取消预览').click();await waitFor(()=>!document.querySelector('.wallpaper-preview-bar'));
    if(document.querySelector('.app-wallpaper-layer') || localStorage.getItem('musxi-transparency')!==persistedLevel
      || toggle('transparency-label').getAttribute('aria-checked')!=='false' || toggle('wallpaper-label').getAttribute('aria-checked')!=='false')throw new Error('Cancel failed to restore original appearance');
    for(const format of ['png','jpg','webp']) {
      const oldId=document.querySelector<HTMLImageElement>('.wallpaper-thumbnail img')?.dataset.wallpaperId;
      await nextChoice(format);
      button('.wallpaper-actions',document.querySelector('.wallpaper-thumbnail img')?'更换壁纸':'选择图片').click();
      await waitFor(()=>document.querySelector<HTMLImageElement>('.wallpaper-thumbnail img')?.dataset.wallpaperId!==oldId,'New wallpaper choice did not settle');
      await waitFor(()=>document.querySelector<HTMLImageElement>('.wallpaper-thumbnail img')?.src.startsWith('data:image/jpeg')===true,'Supported image was not converted to a display copy');
      button('.wallpaper-actions','预览效果').click();await waitFor(()=>!!document.querySelector('.wallpaper-preview-bar'));
    }
    const previousUrl=document.querySelector<HTMLImageElement>('.app-wallpaper-layer img')!.src;
    await nextChoice('cancel');button('.wallpaper-actions','更换壁纸').click();await waitFor(()=>!!button('.wallpaper-actions','更换壁纸') && !button('.wallpaper-actions','更换壁纸').disabled);
    await nextChoice('invalid');button('.wallpaper-actions','更换壁纸').click();
    await waitFor(()=>!!document.querySelector('.appearance-wallpaper [role=alert]'), 'Invalid image not rejected');
    if(document.querySelector<HTMLImageElement>('.app-wallpaper-layer img')!.src!==previousUrl)throw new Error('Invalid choice replaced a valid preview');
    button('.wallpaper-preview-bar','应用壁纸').click();await waitFor(()=>!document.querySelector('.wallpaper-preview-bar'));
    if(!(await native.wallpaper.getState())?.url.startsWith('data:image/jpeg'))throw new Error('Wallpaper display copy was not committed');
    if(localStorage.getItem('musxi-transparent')!=='true' || localStorage.getItem('musxi-wallpaper-enabled')!=='true')throw new Error('Applying preview did not save enabled transparency and wallpaper');
    await setToggle('transparency-label',false);if(document.querySelector('.app-wallpaper-layer'))throw new Error('Wallpaper survived disabled transparency');
    if(!document.querySelector('.wallpaper-thumbnail img') || button('.wallpaper-actions','更换壁纸').disabled
      || button('.wallpaper-actions','预览效果').disabled)throw new Error('Disabled transparency must retain the thumbnail and allow wallpaper actions');
    await setToggle('transparency-label',true);await waitFor(()=>!!document.querySelector('.app-wallpaper-layer'));
    button('.wallpaper-actions','预览效果').click();await waitFor(()=>!!document.querySelector('.wallpaper-preview-bar'));
    await setToggle('transparency-label',false);await waitFor(()=>!document.querySelector('.wallpaper-preview-bar'));
    if(localStorage.getItem('musxi-transparent')!=='false')throw new Error('Disabling transparency during preview was not saved');
    await setToggle('transparency-label',true);button('.wallpaper-actions','预览效果').click();await waitFor(()=>!!document.querySelector('.wallpaper-preview-bar'));
    document.querySelector<HTMLButtonElement>('[aria-label="收起主界面"]')!.click();
    await waitFor(()=>!!document.querySelector('.compact-player'));if(document.querySelector('.wallpaper-preview-bar'))throw new Error('Compact mode retained preview');
    document.querySelector<HTMLButtonElement>('[aria-label="展开主界面"]')!.click();await waitFor(()=>!document.querySelector('.compact-player'));
  } finally {
    if(document.querySelector('.wallpaper-preview-bar'))button('.wallpaper-preview-bar','取消预览').click();
    await appearancePage();await setToggle('transparency-label',true);await setToggle('wallpaper-label',true);
    button('.wallpaper-actions','恢复默认').click();await waitFor(()=>toggle('wallpaper-label').getAttribute('aria-checked')==='false' && !document.querySelector('.wallpaper-thumbnail img'));
    const mode={light:'浅色',dark:'深色',system:'跟随系统'}[savedMode]||'浅色';button('[aria-labelledby=appearance-mode-label]',mode).click();
    const tone={green:'绿色',blue:'蓝色',pink:'粉色',red:'红色'}[savedTone]||'绿色';button('.appearance-tone',tone).click();
    await level(savedLevel);await setToggle('player-transparency-label',savedPlayer);await setToggle('transparency-label',savedTransparent);
  }
}
