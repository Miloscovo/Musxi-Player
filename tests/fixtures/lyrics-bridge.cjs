// Offline IPC fixture. Keep one operation pending until its job is cancelled.
const readline=require('node:readline');
readline.createInterface({input:process.stdin}).on('line',line=>{
  const request=JSON.parse(line);
  if(request.op==='hang')return;
  process.stdout.write(JSON.stringify({ok:true,data:{outcome:'found',format:'lrc',text:'[00:01.00]IPC fixture'}})+'\n');
});
