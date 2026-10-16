// Offline IPC fixture. Keep one operation pending until its job is cancelled.
const readline=require('node:readline');
const ORIGINAL='[00:01.00]IPC fixture\n[00:02.00]Second line';
readline.createInterface({input:process.stdin}).on('line',line=>{
  const request=JSON.parse(line);
  if(request.op==='hang')return;
  const mode=request.track?.name;
  if(mode==='Fixture timeout') {
    process.stdout.write(JSON.stringify({ok:false,reason:'timeout',error:'synthetic'})+'\n');return;
  }
  if(mode==='Fixture delayed') {
    setTimeout(()=>process.stdout.write(JSON.stringify({ok:true,data:{outcome:'found',format:'lrc',text:ORIGINAL,
      translationState:'available',translation:'[00:01.00]IPC 夹具\n[00:02.00]第二行'}})+'\n'),150);return;
  }
  if(request.op==='lyrics_search') {
    const track=request.platform==='kugou'?{id:'fixture:kugou',hash:'a'.repeat(32),name:'Fixture Live',duration:180000}:
      {id:'netease:123',name:'Fixture Live',artist:'Fixture',album:'Live',duration:180000};
    process.stdout.write(JSON.stringify({ok:true,data:{tracks:[track],hasMore:false}})+'\n');return;
  }
  if(request.platform==='kugou') {
    let original='[1000,1000]<0,1000,0>IPC fixture\n[2000,1000]<0,1000,0>Second line';
    if(request.id==='fixture:kugou-different')original='[1000,1000]Different recording\n[2000,1000]Different second line';
    const language={version:1,content:[{type:1,language:0,lyricContent:[['IPC 夹具'],['第二行']]}]};
    const tag=request.id==='fixture:kugou-none'?'':request.id==='fixture:kugou-damaged'?'[language:bad]\n':
      '[language:'+Buffer.from(JSON.stringify(language)).toString('base64')+']\n';
    const krc=tag+original;
    const data=request.translationOnly?{outcome:'found',format:'krc',text:krc}:
      {outcome:'found',format:'lrc',text:ORIGINAL,translationState:'unknown',...(request.includeTranslation?{krc}:{})};
    process.stdout.write(JSON.stringify({ok:true,data})+'\n');return;
  }
  // netease:789 has a translation; netease:790 returns a newer original the user never chose.
  const data={outcome:'found',format:'lrc',text:ORIGINAL,translationState:'none'};
  if(request.id==='netease:789')Object.assign(data,{translationState:'available',translation:'[00:01.00]IPC 夹具\n[00:02.00]第二行'});
  if(request.id==='netease:790')Object.assign(data,{text:'[00:05.00]Remastered',translationState:'available',translation:'[00:05.00]重制'});
  if(mode==='Fixture same-time different')Object.assign(data,{text:'[00:01.00]Different words\n[00:02.00]Other words',
    translationState:'available',translation:'[00:01.00]错误版本\n[00:02.00]另一行'});
  if(mode==='Fixture none')Object.assign(data,{translationState:'none',translation:undefined});
  process.stdout.write(JSON.stringify({ok:true,data})+'\n');
});
