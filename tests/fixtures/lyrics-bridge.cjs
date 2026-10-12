// Offline IPC fixture. Keep one operation pending until its job is cancelled.
const readline=require('node:readline');
readline.createInterface({input:process.stdin}).on('line',line=>{
  const request=JSON.parse(line);
  if(request.op==='hang')return;
  if(request.op==='lyrics_search') {
    process.stdout.write(JSON.stringify({ok:true,data:{tracks:[{id:'netease:123',name:'Fixture Live',artist:'Fixture',album:'Live',duration:180000}],hasMore:false}})+'\n');return;
  }
  process.stdout.write(JSON.stringify({ok:true,data:{outcome:'found',format:'lrc',text:'[00:01.00]IPC fixture'}})+'\n');
});
