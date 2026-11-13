param([Parameter(Mandatory=$true)][string]$HostPath,[string]$MinecraftFixturePath='',[switch]$CompatibilityOnly,[switch]$FocusOnly,[switch]$HoverOnly,[switch]$NavigationOnly,[switch]$UnifiedOnly,[switch]$MigrationOnly,[switch]$ResizeOnly,[switch]$GlassOnly)
$ErrorActionPreference='Stop'
$hostExe=(Resolve-Path -LiteralPath $HostPath).Path
$node=(Get-Command node -ErrorAction Stop).Source
if(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'MusxiPlayerTest.exe' -or ($_.Name -like 'MusxiPlayer*' -and $_.CommandLine -match '--test-app') }) {
 throw 'Close the existing test player before running this test; no settings were changed.'
}
Add-Type @'
using System;using System.Text;using System.Runtime.InteropServices;using System.Threading;using System.Collections.Concurrent;
public static class GameOverlayCheck {
 public delegate bool Callback(IntPtr window,IntPtr arg);
 public struct Rect {public int left,top,right,bottom;}
 public struct Point {public int x,y;}
 [StructLayout(LayoutKind.Sequential)] public struct MouseInput {public int dx,dy;public uint data,flags,time;public UIntPtr extra;}
 [StructLayout(LayoutKind.Sequential)] public struct KeyboardInput {public ushort key,scan;public uint flags,time;public UIntPtr extra;}
 [StructLayout(LayoutKind.Explicit)] public struct Input {[FieldOffset(0)]public uint type;[FieldOffset(8)]public MouseInput mouse;[FieldOffset(8)]public KeyboardInput keyboard;}
 [StructLayout(LayoutKind.Sequential)] public struct Message {public IntPtr window;public uint message;public UIntPtr wp;public IntPtr lp;public uint time;public Point point;public uint extra;}
 public struct MonitorInfo {public int size;public Rect monitor,work;public uint flags;}
 public struct GuiInfo {public uint size,flags;public IntPtr active,focus,capture,menu,move,caret;public Rect caretRect;}
 [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint thread,ref GuiInfo info);
 [DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback,IntPtr arg);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr window,StringBuilder name,int length);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr window,StringBuilder name,int length);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern bool SetWindowText(IntPtr window,string text);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window,int command);
 [DllImport("user32.dll")] public static extern IntPtr GetWindowLongPtr(IntPtr window,int index);
 [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr window,uint command);
 public static bool Above(IntPtr first,IntPtr second) {
  for(var window=GetWindow(second,3);window!=IntPtr.Zero;window=GetWindow(window,3))if(window==first)return true;
  return false;
 }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window,out Rect rect);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr GetProp(IntPtr window,string name);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr window,out Rect rect);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr window,ref Point point);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr window);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window,uint msg,IntPtr wp,IntPtr lp);
 [DllImport("user32.dll")] public static extern IntPtr MonitorFromPoint(Point point,uint flags);
 [DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr monitor,ref MonitorInfo info);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr CreateWindowEx(uint ex,string cls,string title,uint style,int x,int y,int width,int height,IntPtr parent,IntPtr menu,IntPtr instance,IntPtr param);
 [DllImport("user32.dll")] public static extern bool DestroyWindow(IntPtr window);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] static extern int GetMessage(out Message message,IntPtr window,uint min,uint max);
 [DllImport("user32.dll")] static extern bool TranslateMessage(ref Message message);
 [DllImport("user32.dll")] static extern IntPtr DispatchMessage(ref Message message);
 [DllImport("user32.dll")] static extern void PostQuitMessage(int code);
 delegate IntPtr Subclass(IntPtr window,uint message,IntPtr wp,IntPtr lp,UIntPtr id,UIntPtr data);
 [DllImport("comctl32.dll")] static extern bool SetWindowSubclass(IntPtr window,Subclass callback,UIntPtr id,UIntPtr data);
 [DllImport("comctl32.dll")] static extern IntPtr DefSubclassProc(IntPtr window,uint message,IntPtr wp,IntPtr lp);
 static readonly Subclass sinkProc=SinkProc;
 static Thread sinkThread;public static IntPtr Sink;
 public static readonly ConcurrentQueue<int> ReceivedKeys=new ConcurrentQueue<int>();
 public static readonly ConcurrentQueue<int> ReleasedKeys=new ConcurrentQueue<int>();
 static IntPtr SinkProc(IntPtr window,uint message,IntPtr wp,IntPtr lp,UIntPtr id,UIntPtr data) {
  if(message==0x800A && !cursorHidden){ShowCursor(false);cursorHidden=true;return IntPtr.Zero;}
  if((message==0x800B || message==2) && cursorHidden){ShowCursor(true);cursorHidden=false;}
  if(message==0x100 || message==0x104)ReceivedKeys.Enqueue(wp.ToInt32());
  if(message==0x101 || message==0x105)ReleasedKeys.Enqueue(wp.ToInt32());
  if(message==2)PostQuitMessage(0);
  return DefSubclassProc(window,message,wp,lp);
 }
 static bool cursorHidden;
 [DllImport("user32.dll")] static extern int ShowCursor(bool show);
 public static void StartSink() {
  var ready=new ManualResetEventSlim();
  sinkThread=new Thread(()=>{
   Sink=CreateWindowEx(8,"STATIC","Shortcut input test",0x90000000,40,40,400,160,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero);
   if(Sink!=IntPtr.Zero && !SetWindowSubclass(Sink,sinkProc,UIntPtr.Zero,UIntPtr.Zero)){DestroyWindow(Sink);Sink=IntPtr.Zero;}
   ready.Set();if(Sink==IntPtr.Zero)return;
   Message message;while(GetMessage(out message,IntPtr.Zero,0,0)>0){TranslateMessage(ref message);DispatchMessage(ref message);}
  });sinkThread.IsBackground=true;sinkThread.Start();
  if(!ready.Wait(5000) || Sink==IntPtr.Zero)throw new Exception("Keyboard input test window unavailable");
 }
 public static void StopSink(){if(Sink!=IntPtr.Zero)PostMessage(Sink,0x10,IntPtr.Zero,IntPtr.Zero);if(sinkThread!=null)sinkThread.Join(3000);}
 public static void Key(ushort key,bool down) {
  var input=new Input{type=1,keyboard=new KeyboardInput{key=key,flags=down?0u:2u}};
  if(SendInput(1,new[]{input},Marshal.SizeOf<Input>())!=1)throw new Exception("Keyboard input was not delivered");
 }
 [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr window,int id,uint modifiers,uint key);
 [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr window,int id);
 [DllImport("user32.dll",SetLastError=true)] public static extern IntPtr SendMessageTimeout(IntPtr window,uint msg,IntPtr wp,IntPtr lp,uint flags,uint timeout,out UIntPtr result);
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point point);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int key);
 [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
 [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point point);
 [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr window,uint flags);
 [DllImport("user32.dll",SetLastError=true)] public static extern uint SendInput(uint count,Input[] input,int size);
 public static int Hit(IntPtr window,int x,int y) {
  UIntPtr result;var point=new IntPtr(unchecked((int)(((uint)(ushort)y<<16)|(ushort)x)));
  if(SendMessageTimeout(window,0x84,IntPtr.Zero,point,2,1000,out result)==IntPtr.Zero)throw new Exception("Widget hit test timed out");
  return unchecked((int)result.ToUInt64());
 }
 public static void Move(int x,int y) {
  int left=GetSystemMetrics(76),top=GetSystemMetrics(77),width=GetSystemMetrics(78),height=GetSystemMetrics(79);
  var input=new Input{mouse=new MouseInput{dx=(int)Math.Round((x-left)*65535.0/(width-1)),dy=(int)Math.Round((y-top)*65535.0/(height-1)),flags=0xC001}};
  if(SendInput(1,new[]{input},Marshal.SizeOf<Input>())!=1)throw new Exception("Mouse move was not delivered");
 }
 public static void Button(bool down) {
  var input=new Input{mouse=new MouseInput{flags=down?2u:4u}};
  if(SendInput(1,new[]{input},Marshal.SizeOf<Input>())!=1)throw new Exception("Mouse button was not delivered");
 }
}
'@
function Require([bool]$ok,[string]$reason) {if(-not $ok){throw $reason}}
function Wait-For([scriptblock]$condition,[string]$reason) {
 $timer=[Diagnostics.Stopwatch]::StartNew()
 while(-not (& $condition)) {
  if(($app -and $app.HasExited) -or $timer.ElapsedMilliseconds -gt 15000){throw $reason}
  Start-Sleep -Milliseconds 60
 }
}
function Find-Window([string]$class,[string]$title='',[int]$ownerId=0) {
 $expectedOwner=if($ownerId){$ownerId}else{$app.Id}
 $script:found=[IntPtr]::Zero
 [void][GameOverlayCheck]::EnumWindows({param($window,$unused)
  $owner=[uint32]0;[void][GameOverlayCheck]::GetWindowThreadProcessId($window,[ref]$owner)
  if($owner -eq $expectedOwner){
   $name=[Text.StringBuilder]::new(128);[void][GameOverlayCheck]::GetClassName($window,$name,128)
   $text=[Text.StringBuilder]::new(128);[void][GameOverlayCheck]::GetWindowText($window,$text,128)
   if($name.ToString() -eq $class -and (-not $title -or $text.ToString() -eq $title)){$script:found=$window}
  };return $true
 },[IntPtr]::Zero)
 return $script:found
}
function Has-Style([IntPtr]$window,[long]$mask) {return ([GameOverlayCheck]::GetWindowLongPtr($window,-20).ToInt64() -band $mask) -eq $mask}
function Track-Height {
 $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$bounds)
 return ($bounds.bottom-$bounds.top)*96/[GameOverlayCheck]::GetDpiForWindow($track)
}
function Wait-TrackFit {
 Wait-For { $height=Evaluate "document.querySelector('.game-overlay')?Math.ceil(document.querySelector('.game-overlay').getBoundingClientRect().height):0" 'track';$height -gt 0 -and [Math]::Abs((Track-Height)-$height) -le 1 } 'Track native window did not fit its visible content'
}
function Check-OverlayKeys([bool]$available) {
 foreach($key in @(132,133)){
  $acquired=[GameOverlayCheck]::RegisterHotKey($keyProbe,$key,0x4007,$key)
  if($acquired){[void][GameOverlayCheck]::UnregisterHotKey($keyProbe,$key)}
  Require ($acquired -eq $available) "Overlay shortcut $key ownership is incorrect for the module state"
 }
}
function Press-TestShortcut([int]$key,[bool]$expectDelivery=$true,[IntPtr]$target=[GameOverlayCheck]::Sink,[scriptblock]$whileHeld=$null) {
 foreach($held in @(160,162,164,$key)){Require (([GameOverlayCheck]::GetAsyncKeyState($held) -band 0x8000) -eq 0) 'Release test keys and modifiers before running keyboard checks'}
 [void][GameOverlayCheck]::SetForegroundWindow($target)
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $target} 'Could not focus the shortcut test target; no keys sent'
 $before=@([GameOverlayCheck]::ReceivedKeys.ToArray() | Where-Object {$_ -eq $key}).Count
 $released=@([GameOverlayCheck]::ReleasedKeys.ToArray() | Where-Object {$_ -eq $key}).Count
 $editingBefore=if($key -eq 133){(State).editing}else{$null}
 $pressed=[Collections.Generic.List[int]]::new()
 try {
  foreach($code in @(162,164,160,$key)){[GameOverlayCheck]::Key($code,$true);$pressed.Add($code)}
  [GameOverlayCheck]::Key($key,$true)
  Start-Sleep -Milliseconds 100
  if($whileHeld){& $whileHeld}
  if($key -eq 133){Require ((State).editing -eq $editingBefore) 'Editing changed before the original game key was released'}
 } finally {for($i=$pressed.Count-1;$i -ge 0;--$i){[GameOverlayCheck]::Key($pressed[$i],$false)}}
 # Windows can combine repeats; editing deliberately changes the input target.
 if($expectDelivery){
  try {Wait-For {@([GameOverlayCheck]::ReceivedKeys.ToArray() | Where-Object {$_ -eq $key}).Count -gt $before} 'Bound key did not reach the foreground application'}
  catch {throw "Key $key did not reach the input window (before=$before, received=$([GameOverlayCheck]::ReceivedKeys.ToArray() -join ','), foreground=$([GameOverlayCheck]::GetForegroundWindow()), sink=$([GameOverlayCheck]::Sink))"}
  Wait-For {@([GameOverlayCheck]::ReleasedKeys.ToArray() | Where-Object {$_ -eq $key}).Count -gt $released} "Original key $key release did not reach the foreground application"
 }
}
function Evaluate([string]$expression,[string]$page='main') {
 $encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($expression))
 $reply=& $node $driver $port $page $encoded
 if($LASTEXITCODE -ne 0){throw "CDP evaluation failed ($page)"}
 return $reply|ConvertFrom-Json
}
function Widget-Point([IntPtr]$window,[string]$page,[string]$selector) {
 $rect=Evaluate "(()=>{const r=document.querySelector('$selector').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()" $page
 $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$bounds)
 $scale=[GameOverlayCheck]::GetDpiForWindow($window)/96.0
 return [GameOverlayCheck+Point]@{x=[int][Math]::Round($bounds.left+$rect.x*$scale);y=[int][Math]::Round($bounds.top+$rect.y*$scale)}
}
function Hit-Widget([IntPtr]$window,[string]$page,[string]$selector) {
 $point=Widget-Point $window $page $selector
 return [GameOverlayCheck]::Hit($window,$point.x,$point.y)
}
function Drag-Title([IntPtr]$window,[string]$page,[string]$reason,[switch]$Edge) {
 $before=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$before)
 $start=Widget-Point $window $page '.overlay-widget-header>span'
 if($Edge){$scale=[GameOverlayCheck]::GetDpiForWindow($window)/96.0;$start=[GameOverlayCheck+Point]@{x=$before.left+[int][Math]::Round(9*$scale);y=[int][Math]::Round(($before.top+$before.bottom)/2)}}
 Require (([GameOverlayCheck]::GetAsyncKeyState(1) -band 0x8000) -eq 0) 'Release the left mouse button before running the drag test'
 [GameOverlayCheck]::Move($start.x,$start.y);Start-Sleep -Milliseconds 80
 $observed=[GameOverlayCheck+Point]::new();[void][GameOverlayCheck]::GetCursorPos([ref]$observed)
 Require ($observed.x -eq $start.x -and $observed.y -eq $start.y) "Mouse was clipped or moved before dragging: expected $($start.x),$($start.y), observed $($observed.x),$($observed.y); no click sent"
 Require ([GameOverlayCheck]::GetAncestor([GameOverlayCheck]::WindowFromPoint($observed),2) -eq $window) 'Drag target is covered by another window; no mouse button was pressed'
 try {
  [GameOverlayCheck]::Button($true);$script:mouseHeld=$true
  for($step=1;$step -le 6;$step++){
   [GameOverlayCheck]::Move($start.x+8*$step,$start.y+[int][Math]::Round(32*$step/6))
   Start-Sleep -Milliseconds 35
  }
 } finally {
  if($script:mouseHeld){[GameOverlayCheck]::Button($false);$script:mouseHeld=$false}
 }
 Wait-For {
  $after=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$after)
  [Math]::Abs($after.left-$before.left-48) -le 2 -and [Math]::Abs($after.top-$before.top-32) -le 2 -and $after.right-$after.left -eq $before.right-$before.left -and $after.bottom-$after.top -eq $before.bottom-$before.top
 } $reason
}
function Bridge([string]$command,[hashtable]$params=@{},[string]$page='main',[switch]$AllowFailure) {
 $envelope=@{version=1;command=$command;params=$params}|ConvertTo-Json -Depth 6 -Compress
 $expression="new Promise(resolve=>window.cefQuery({request:JSON.stringify($envelope),persistent:false,onSuccess:text=>resolve({ok:true,value:JSON.parse(text).result}),onFailure:code=>resolve({ok:false,code})}))"
 $result=Evaluate $expression $page
 if(-not $result.ok -and -not $AllowFailure){throw "Bridge rejected $command with code $($result.code)"}
 if($AllowFailure){return $result};return $result.value
}
function State {return Bridge 'gameOverlay.getState'}
function Stop-TestPlayer {
 if($app -and -not $app.HasExited){
  $main=Find-Window 'MusxiPlayerTestHost'
  if($main -ne [IntPtr]::Zero){[void][GameOverlayCheck]::PostMessage($main,0x10,[IntPtr]::Zero,[IntPtr]::Zero)}
  Require ($app.WaitForExit(12000)) 'Test player did not close; settings will not be overwritten while it is running'
 }
}
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('musxi-overlay-test-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
$driver=Join-Path $temporary 'bridge.cjs'
@'
const [port,page,encoded]=process.argv.slice(2);
const capture=encoded==='--screenshot';
const fixture=encoded==='--lyric-fixture';
const reduced=encoded.startsWith('--reduce-motion:');
const expression=capture||fixture?'':Buffer.from(reduced?encoded.slice(16):encoded,'base64').toString('utf8');
let socket;
const deadline=setTimeout(()=>{console.error('CDP timed out');process.exit(1)},12000);
(async()=>{
 const targets=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
 const target=targets.find(t=>{if(t.type!=='page')return false;const q=new URL(t.url).searchParams;return page==='main'?!q.has('gameOverlay')&&!q.has('desktopLyrics')&&!q.has('tray'):q.get('gameOverlay')===page});
 if(!target)throw Error(`No ${page} page`);
 socket=new WebSocket(target.webSocketDebuggerUrl);
 await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true})});
 let next=0;const pending=new Map();
 socket.addEventListener('message',e=>{const r=JSON.parse(e.data),p=pending.get(r.id);if(p){pending.delete(r.id);r.error?p.reject(Error(r.error.message)):p.resolve(r.result)}});
 const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}))});
 if(reduced)await send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
 if(fixture){
  await send('Page.enable');await send('Runtime.enable');
  await send('Page.addScriptToEvaluateOnNewDocument',{source:`(()=>{
   if(new URLSearchParams(location.search).get('gameOverlay')!=='lyrics')return;
   const query=window.cefQuery,cancel=window.cefQueryCancel,active=new Set();let next=-1;
   window.__overlayFixtureProbe={nativeQuery:typeof query,commands:[],calls:{},players:0,lyricObservers:0};
   const probe=window.__overlayFixtureProbe,players=new Map(),Observer=window.ResizeObserver;
   window.ResizeObserver=class extends Observer {observe(element,options){if(element.classList.contains('overlay-lyrics-list'))probe.lyricObservers++;return super.observe(element,options)}};
   const state={opened:true,playing:false,positionMs:100000,durationMs:200000,volumePercent:50,trackId:'local:overlay-drag-fixture',phase:'paused',pending:false,requestedPlaying:false,error:''};
   const lyrics={status:'ready',source:'local',id:state.trackId,platform:'local',origin:'lrc-file',kind:'synced',translation:'available',lines:Array.from({length:200},(_,index)=>({timeMs:index*1000,text:'Drag fixture line '+index,translation:'Translation '+index}))};
   probe.setPosition=position=>{state.positionMs=position;for(const notify of players.values())notify(JSON.stringify({version:1,event:'player.stateChanged',state}))};
   const canvas=document.createElement('canvas');canvas.width=canvas.height=8;
   const context=canvas.getContext('2d');context.fillStyle='#e14646';context.fillRect(0,0,8,8);
   const image=canvas.toDataURL('image/png'),cover='https://overlay-fixture.invalid/cover.png';
   const fixtureQuery=options=>{
    let request;try{request=JSON.parse(options.request)}catch{return query(options)}
    probe.calls[request.command]=(probe.calls[request.command]||0)+1;
    if(window.__overlayFixtureProbe.commands.length<12)window.__overlayFixtureProbe.commands.push(request.command);
    let result;if(request.command==='player.subscribe')result={version:1,event:'player.stateChanged',state};
    else if(request.command==='player.getState')result={version:1,result:state};
    else if(request.command==='library.lyrics')result={version:1,result:lyrics};
    else if(request.command==='library.getState')return query({...options,onSuccess:text=>{
     const reply=JSON.parse(text);reply.result.now.cover=cover;options.onSuccess(JSON.stringify(reply));
    }});
    else if(request.command==='library.image' && request.params.url===cover)result={version:1,result:image};
    else return query(options);
    const id=next--;if(request.command==='player.subscribe'){players.set(id,options.onSuccess);probe.players=players.size}active.add(id);queueMicrotask(()=>{if(active.has(id)){options.onSuccess(JSON.stringify(result));if(!options.persistent)active.delete(id)}});return id;
   };
   const fixtureCancel=id=>{if(id<0){active.delete(id);players.delete(id);probe.players=players.size;return}return cancel(id)};
   // CEF's query functions are read-only; replace only the client's test host.
   Object.defineProperty(window,'globalThis',{configurable:true,writable:true,value:new Proxy({}, {get:(_,key)=>key==='cefQuery'?fixtureQuery:key==='cefQueryCancel'?fixtureCancel:Reflect.get(window,key,window)})});
  })()`});
  await send('Page.reload',{ignoreCache:true});
  let loaded=false;
  for(let attempt=0;attempt<80;attempt++){
   try{const r=await send('Runtime.evaluate',{expression:"document.querySelectorAll('.overlay-lyrics-list li').length===200",returnByValue:true});loaded=r.result?.value===true}catch{}
   if(loaded)break;await new Promise(resolve=>setTimeout(resolve,100));
  }
  if(!loaded){
   const diagnostic=await send('Runtime.evaluate',{expression:"JSON.stringify({ready:document.readyState,host:globalThis.cefQuery===cefQuery?'native':'fixture',probe:window.__overlayFixtureProbe,rows:document.querySelectorAll('.overlay-lyrics-list li').length,message:document.querySelector('.overlay-message')?.textContent,error:document.querySelector('.overlay-error')?.textContent})",returnByValue:true});
   console.error(diagnostic.result?.value);throw Error('React did not render the 200-line lyric fixture');
  }
  console.log('true');return;
 }
 const result=await send(capture?'Page.captureScreenshot':'Runtime.evaluate',capture?{format:'png'}:{expression,returnByValue:true,awaitPromise:true});
 if(result.exceptionDetails)throw Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text||'Page evaluation failed');
 console.log(JSON.stringify(capture?result.data:result.result.value));
})().catch(e=>{console.error(e.message);process.exitCode=1}).finally(()=>{clearTimeout(deadline);socket?.close()});
'@|Set-Content -LiteralPath $driver -Encoding utf8
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
$profile=Join-Path $env:LOCALAPPDATA 'MusxiPlayer-Test'
[void][IO.Directory]::CreateDirectory($profile)
$saved=@{}
foreach($name in @('game-overlay.json','desktop-lyrics.json','window.json','shortcuts.json')){
 $path=Join-Path $profile $name
 $saved[$name]=if(Test-Path -LiteralPath $path){[IO.File]::ReadAllBytes($path)}else{$null}
}
$oldDpi=[GameOverlayCheck]::SetThreadDpiAwarenessContext([IntPtr](-4))
$app=$null
$minecraft=$null
$minecraftFixture=if($MinecraftFixturePath){[IO.Path]::GetFullPath($MinecraftFixturePath)}else{[IO.Path]::GetFullPath((Join-Path (Split-Path $hostExe) '../../../minecraft-fixture/Release/GameWindowFixture.exe'))}
$keyProbe=[IntPtr]::Zero
$mouseHeld=$false
$originalCursor=[GameOverlayCheck+Point]::new();[void][GameOverlayCheck]::GetCursorPos([ref]$originalCursor)
try {
 [GameOverlayCheck]::Move(0,0)
 $keyProbe=[GameOverlayCheck]::CreateWindowEx(0,'STATIC','',0,0,0,0,0,[IntPtr](-3),[IntPtr]::Zero,[IntPtr]::Zero,[IntPtr]::Zero)
 Require ($keyProbe -ne [IntPtr]::Zero) 'Could not create the isolated shortcut ownership probe'
 [IO.File]::WriteAllText((Join-Path $profile 'game-overlay.json'),'{"version":1,"enabled":false,"minecraftCompatibility":true,"visible":true,"lyricsVisible":true,"trackVisible":true,"lyrics":{"x":1000000,"y":1000000,"width":600,"height":320},"track":{"x":1000000,"y":1000000,"width":360,"height":160}}')
 [IO.File]::WriteAllText((Join-Path $profile 'desktop-lyrics.json'),'{"version":1,"visible":false,"locked":false,"dual":true,"x":0,"y":0,"width":680,"height":180}')
 $bindings=[ordered]@{}
 foreach($action in @('playPause','previous','next','forward','backward','volumeUp','volumeDown','mute','playbackOrder','visibility','overlayVisibility','overlayEditing','overlayPlayer')){$bindings[$action]=$null}
 $bindings.visibility=@{key=135;modifiers=7};$bindings.overlayPlayer=@{key=134;modifiers=7};$bindings.overlayScreenshot=$bindings.visibility
 [IO.File]::WriteAllText((Join-Path $profile 'shortcuts.json'),(@{global=$false;bindings=$bindings}|ConvertTo-Json -Depth 4 -Compress))
 $arguments=@('--test-app',"--remote-debugging-port=$port")
 $app=Start-Process -FilePath $hostExe -ArgumentList $arguments -WindowStyle Hidden -PassThru
 Wait-For {try{[void](Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/list" -TimeoutSec 1);return $true}catch{return $false}} 'CEF debugging endpoint did not start'
 Wait-For {(Find-Window 'MusxiPlayerTestHost') -ne [IntPtr]::Zero} 'Main player window did not start'
 $main=Find-Window 'MusxiPlayerTestHost'
 $initialTopmost=Has-Style $main 8
 $state=State
 Require ($state.available -and $state.enabled -and -not $state.visible -and -not $state.editing) 'Unexpected initial overlay state'
 if($MigrationOnly) {
  Stop-TestPlayer
  $scenarios=@(
   @{name='game wins';enabled=$true;game=$true;desktop=$true;mode='multi';width=600;visible=$true},
   @{name='desktop wins';enabled=$true;game=$false;desktop=$true;mode='single';width=680;visible=$true},
   @{name='disabled game with desktop';enabled=$false;game=$true;desktop=$true;mode='single';width=680;visible=$true},
   @{name='both hidden';enabled=$true;game=$false;desktop=$false;mode='multi';width=600;visible=$false},
   @{name='new user';fresh=$true;desktop=$false;mode='multi';width=520;visible=$false}
  )
  foreach($scenario in $scenarios) {
   $layout=@{version=1;enabled=$scenario.enabled;visible=$scenario.game;lyricsVisible=$true;trackVisible=$true;lyrics=@{x=120;y=140;width=600;height=320};track=@{x=750;y=140;width=360;height=120}}
   $legacy=@{version=1;visible=$scenario.desktop;locked=$true;dual=$true;fontSize=34;x=220;y=240;width=680;height=180}
   [IO.File]::WriteAllText((Join-Path $profile 'desktop-lyrics.json'),($legacy|ConvertTo-Json -Depth 4 -Compress))
   if($scenario.fresh){Remove-Item -LiteralPath (Join-Path $profile 'game-overlay.json')}else{[IO.File]::WriteAllText((Join-Path $profile 'game-overlay.json'),($layout|ConvertTo-Json -Depth 4 -Compress))}
   $app=Start-Process -FilePath $hostExe -ArgumentList $arguments -WindowStyle Hidden -PassThru
   Wait-For {try{[void](Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/list" -TimeoutSec 1);return $true}catch{return $false}} 'Migration test endpoint unavailable'
   Wait-For {(Find-Window 'MusxiPlayerTestHost') -ne [IntPtr]::Zero} 'Migration test window unavailable'
   $result=State
   Require ($result.enabled -and $result.visible-eq$scenario.visible -and $result.lyricsMode-eq$scenario.mode -and !$result.editing) "Wrong migration state: $($scenario.name)"
   $prefs=Get-Content -LiteralPath (Join-Path $profile 'game-overlay.json') -Raw|ConvertFrom-Json
   Require ($prefs.version-eq2 -and $prefs.lyrics.width-eq$scenario.width) "Wrong migrated layout: $($scenario.name)"
   if($scenario.mode-eq'single'){Require ($result.lyricsFontSize-eq34 -and !$result.trackVisible) 'Desktop migration lost font or opened song info'}
   Require ((Find-Window 'MusxiDesktopLyrics')-eq[IntPtr]::Zero) 'Migration created a duplicate lyrics window'
   [void](Bridge 'gameOverlay.setVisible' @{visible=$false})
   [void](Bridge 'gameOverlay.setTopmost' @{topmost=$false})
   [void](Bridge 'gameOverlay.setLyricsMode' @{lyricsMode='single'})
   Stop-TestPlayer
   # Legacy visible preferences must not resurrect a hidden, already-migrated window.
   $app=Start-Process -FilePath $hostExe -ArgumentList $arguments -WindowStyle Hidden -PassThru
   Wait-For {try{[void](Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/list" -TimeoutSec 1);return $true}catch{return $false}} 'Migration restart endpoint unavailable'
   Wait-For {(Find-Window 'MusxiPlayerTestHost') -ne [IntPtr]::Zero} 'Migration restart window unavailable'
   $result=State
   Require (!$result.visible -and !$result.topmost -and $result.lyricsMode-eq'single') 'Migration was repeated or new preferences were lost on restart'
   Stop-TestPlayer
   Write-Output "PASS: migration $($scenario.name), version 2 restart preserves visibility/topmost/mode"
  }
  return
 }
 Require (-not $state.hideOnHover) 'Older overlay preferences did not default hover hiding off'
 Require (-not $state.minecraftCompatibility -and $state.minecraftStatus -eq 'off') 'Legacy settings did not default Minecraft compatibility off'
 Wait-For {Evaluate "!!document.querySelector('button[aria-label=扩展]')"} 'Sidebar did not finish rendering'
 [void](Evaluate "document.querySelector('button[aria-label=扩展]').click();true")
 Wait-For {Evaluate "!!document.querySelector('.wallpaper-extension-card')&&!document.querySelector('.extension-card')"} 'Extensions did not show the wallpaper extension without the built-in widget card'
 $screenshot=& $node $driver $port 'main' '--screenshot'
 Require ($LASTEXITCODE -eq 0) 'Extension page screenshot failed'
 $screenshots=Join-Path (Split-Path -Parent $PSScriptRoot) 'build\extensions-qa'
 [void][IO.Directory]::CreateDirectory($screenshots)
 [IO.File]::WriteAllBytes((Join-Path $screenshots 'game-player-module.png'),[Convert]::FromBase64String(($screenshot|ConvertFrom-Json)))
 $invalid=Bridge 'gameOverlay.setEnabled' @{enabled=1} -AllowFailure
 Require (-not $invalid.ok -and $invalid.code -eq 400) 'Nonboolean overlay setting was accepted'
 $invalid=Bridge 'gameOverlay.setVisible' @{visible=$true;extra=$true} -AllowFailure
 Require (-not $invalid.ok -and $invalid.code -eq 400) 'Extra overlay parameters were accepted'
 [void](Bridge 'gameOverlay.setEnabled' @{enabled=$true})
 Require (-not (State).visible) 'Enabling the module automatically showed widgets'
 Require (-not [GameOverlayCheck]::IsWindowVisible((Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件')) -and -not [GameOverlayCheck]::IsWindowVisible((Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息'))) 'Enabling the module showed widgets before manual display'
 $shortcutState=Bridge 'shortcuts.getState'
 Require ($shortcutState.bindings.PSObject.Properties.Name.Count -eq 12) 'Unexpected shortcut actions remain'
 Require (-not $shortcutState.global -and $shortcutState.bindings.visibility.key-eq135 -and $shortcutState.bindings.visibility.modifiers-eq7) 'Retiring panel shortcuts discarded another binding or the global preference'
 Require ((State).PSObject.Properties.Name -notcontains 'panelVisible') 'Removed panel state remains exposed'
 foreach($action in @('overlayPlayer','overlayScreenshot')){
  $removed=Bridge 'shortcuts.execute' @{action=$action} -AllowFailure
  Require (-not $removed.ok -and $removed.code-eq400) 'Removed shortcut still executes'
 }
 $removedPanel=Bridge 'gameOverlay.setPanelVisible' @{panelVisible=$true} -AllowFailure
 Require (-not $removedPanel.ok -and $removedPanel.code-eq404) 'Removed panel interface remains callable'
 $shortcutsBeforeJump=$shortcutState | ConvertTo-Json -Depth 8 -Compress
 [void](Evaluate "document.querySelector('button[aria-label=设置]').click();true")
 Wait-For {Evaluate "Array.from(document.querySelectorAll('.library-section')).some(b=>b.textContent==='歌词与组件')"} 'Game player settings category unavailable'
 [void](Evaluate "Array.from(document.querySelectorAll('.library-section')).find(b=>b.textContent==='歌词与组件').click();true")
 Wait-For {Evaluate "Array.from(document.querySelectorAll('.game-overlay-settings button')).some(b=>b.textContent==='组件快捷键设置')"} 'Game shortcut settings entry unavailable'
 Require (Evaluate "!Array.from(document.querySelectorAll('.game-overlay-settings button')).some(b=>b.textContent==='打开选歌面板'||b.textContent==='收起选歌面板')") 'Old selection-panel settings button was retained'
 [void](Evaluate "Array.from(document.querySelectorAll('.game-overlay-settings button')).find(b=>b.textContent==='组件快捷键设置').click();true")
 Wait-For {Evaluate "(()=>{const g=document.querySelector('#game-overlay-shortcuts'),s=document.querySelector('.page-scroll');if(!g||!s)return false;const a=g.getBoundingClientRect(),b=s.getBoundingClientRect();return document.querySelector('.library-section[aria-pressed=true]')?.textContent==='快捷键'&&g.querySelectorAll('.shortcut-row').length===2&&a.top>=b.top-1&&a.top<b.bottom&&s.scrollTop>0})()"} 'Settings entry did not switch category and scroll to the game shortcut group'
 Require ((Bridge 'shortcuts.getState' | ConvertTo-Json -Depth 8 -Compress) -eq $shortcutsBeforeJump) 'Navigation changed shortcut bindings or global enable state'
 Require (Evaluate "!document.querySelector('.game-panel-return')") 'Removed return-to-game button remains visible'
 [void](Evaluate "Array.from(document.querySelectorAll('.library-section')).find(b=>b.textContent==='歌词与组件').click();true")
 Wait-For {Evaluate "!!document.querySelector('.game-overlay-settings')"} 'Could not return to game player settings'
 [void](Evaluate "Array.from(document.querySelectorAll('.library-section')).find(b=>b.textContent==='快捷键').click();true")
 Wait-For {Evaluate "!!document.querySelector('.shortcut-settings')&&document.querySelector('.page-scroll').scrollTop===0"} 'Ordinary shortcut navigation inherited the one-time scroll target'
 Write-Output 'PASS: game shortcut settings entry switches category, scrolls once to both bindings, preserves shortcuts and rejects retired panel commands'
 if($NavigationOnly){return}
 if($GlassOnly) {
  [void](Evaluate "window.__glassStorageBackup=Object.entries(localStorage);document.querySelector('button[aria-label=设置]').click();true")
  [void](Evaluate "Array.from(document.querySelectorAll('.settings-sections button')).find(b=>b.textContent==='外观').click();true")
  Wait-For {Evaluate "!!document.querySelector('[aria-labelledby=liquid-glass-label]')"} 'Liquid glass setting did not appear'
  $glassToggle="document.querySelector('[aria-labelledby=liquid-glass-label]')"
  try {
   Wait-For {Evaluate "!document.querySelector('[aria-labelledby=wallpaper-label]').disabled"} 'Wallpaper switch did not become available'
   [void](Evaluate "(()=>{const e=document.querySelector('[aria-labelledby=wallpaper-label]');if(e.getAttribute('aria-checked')==='true')e.click();return true})()")
   [void](Evaluate "(()=>{const e=document.querySelector('[aria-labelledby=transparency-label]');if(e.getAttribute('aria-checked')==='true')e.click();return true})()")
   Require (Evaluate "!document.querySelector('[aria-labelledby=wallpaper-label]').disabled" ) 'Opaque appearance disabled the wallpaper switch'
   [void](Evaluate "document.querySelector('[aria-labelledby=wallpaper-label]').click();true")
   Wait-For {Evaluate "document.querySelector('[aria-labelledby=transparency-label]').getAttribute('aria-checked')==='true'&&localStorage.getItem('musxi-transparent')==='true'&&localStorage.getItem('musxi-wallpaper-enabled')==='true'"} 'Wallpaper enable did not enable and save transparency'
   [void](Evaluate "document.querySelector('[aria-labelledby=wallpaper-label]').click();true")
   Wait-For {Evaluate "document.querySelector('[aria-labelledby=transparency-label]').getAttribute('aria-checked')==='false'&&localStorage.getItem('musxi-transparent')==='false'&&localStorage.getItem('musxi-wallpaper-enabled')==='false'"} 'Wallpaper disable did not disable and save transparency'
   Write-Output 'PASS: wallpaper toggle remains available when opaque and controls saved transparency in both directions'
   [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
   $track=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息'
   if(Evaluate "$glassToggle.getAttribute('aria-checked')==='true'"){[void](Evaluate "$glassToggle.click();true")}
   Wait-For {Evaluate "!document.querySelector('.liquid-glass-surface')"} 'Disabled glass changed the main appearance'
   [void](Evaluate "$glassToggle.click();true")
   Wait-For {Evaluate "document.querySelectorAll('.liquid-glass-surface').length===2&&localStorage.getItem('musxi-liquid-glass')==='true'"} 'Glass did not enable or persist on both main surfaces'
   Wait-For {Evaluate "!!document.querySelector('.liquid-glass-surface')&&!document.querySelector('.liquid-glass-warp')" 'track'} 'Track did not synchronize the material-only glass setting'
   # A striped wallpaper in the actual app proves pixel refraction, not just CSS support.
   [void](Evaluate "(()=>{const c=document.createElement('canvas');c.width=c.height=1024;const x=c.getContext('2d');for(let i=0;i<1024;i+=8){x.fillStyle=i%16?'#31a7dc':'#dd4a68';x.fillRect(i,0,8,1024)}const layer=document.createElement('div');layer.className='app-wallpaper-layer';layer.id='glass-fixture';const img=new Image();img.src=c.toDataURL();layer.append(img);document.querySelector('.app-shell').prepend(layer);for(const e of document.querySelectorAll('.sidebar,.player')){e.style.setProperty('--liquid-fill','transparent');e.style.setProperty('--liquid-opacity','.5')}return true})()")
   Start-Sleep -Milliseconds 250
   $glassEvidence=Join-Path (Split-Path -Parent $PSScriptRoot) 'build/liquid-glass-qa'
   [void][IO.Directory]::CreateDirectory($glassEvidence)
   Add-Type -AssemblyName System.Drawing
   $wasMaximized=(Bridge 'window.getState').maximized
   foreach($layout in @('normal','maximized')) {
    $maximize=$layout-eq'maximized'
    if((Bridge 'window.getState').maximized-ne$maximize){[void](Bridge 'window.maximize')}
    Wait-For {(Bridge 'window.getState').maximized-eq$maximize} 'Glass viewport did not reach the requested layout'
    foreach($variant in @('refracted','blur-only')) {
     [void](Evaluate "for(const e of document.querySelectorAll('.liquid-glass-warp')){if(!e.dataset.filter)e.dataset.filter=e.style.filter;e.style.filter=e.dataset.filter}true")
     if($variant-eq'blur-only'){[void](Evaluate "for(const e of document.querySelectorAll('.liquid-glass-warp'))e.style.filter='none';true")}
     Start-Sleep -Milliseconds 250
     $shot=& $node $driver $port 'main' '--screenshot'
     Require ($LASTEXITCODE-eq0) 'Glass screenshot failed'
     [IO.File]::WriteAllBytes((Join-Path $glassEvidence "$layout-$variant.png"),[Convert]::FromBase64String(($shot|ConvertFrom-Json)))
    }
    $a=[Drawing.Bitmap]::new((Join-Path $glassEvidence "$layout-refracted.png"));$b=[Drawing.Bitmap]::new((Join-Path $glassEvidence "$layout-blur-only.png"))
    try {
     $regions=Evaluate "({width:innerWidth,panes:Array.from(document.querySelectorAll('.sidebar,.player')).map(e=>{const r=e.getBoundingClientRect();return {name:e.className,x:r.x,y:r.y,width:r.width,height:r.height}})})"
     $scale=$a.Width/$regions.width
     foreach($region in $regions.panes) {
      $edge=0;$center=0
      $left=[int][Math]::Ceiling($region.x*$scale);$top=[int][Math]::Ceiling($region.y*$scale)
      $right=[int][Math]::Floor(($region.x+$region.width)*$scale);$bottom=[int][Math]::Floor(($region.y+$region.height)*$scale)
      for($y=$top+1;$y-lt$bottom-1;$y+=3){for($x=$left+1;$x-lt$right-1;$x+=3){
       $distance=[Math]::Min([Math]::Min($x-$left,$right-$x),[Math]::Min($y-$top,$bottom-$y))/$scale
       $p=$a.GetPixel($x,$y);$q=$b.GetPixel($x,$y)
       if([Math]::Abs($p.R-$q.R)+[Math]::Abs($p.G-$q.G)+[Math]::Abs($p.B-$q.B)-gt12){if($distance-lt12){$edge++}elseif($distance-gt20){$center++}}
      }}
      Write-Output "$layout $($region.name): changed edge samples=$edge, center samples=$center"
      Require ($edge-gt20) 'CEF did not render the edge refraction'
      Require ($center-le3) 'Refraction changed pixels outside the fixed edge band'
     }
    }finally{$a.Dispose();$b.Dispose()}
   }
   if((Bridge 'window.getState').maximized-ne$wasMaximized){[void](Bridge 'window.maximize')}
   [void](Evaluate "for(const e of document.querySelectorAll('.sidebar,.player')){e.style.removeProperty('--liquid-fill');e.style.removeProperty('--liquid-opacity')}for(const e of document.querySelectorAll('.liquid-glass-warp'))e.style.filter=e.dataset.filter;true")
   foreach($mode in @(@{label='浅色';theme='light'},@{label='深色';theme='dark'})) {
    [void](Evaluate "Array.from(document.querySelectorAll('[aria-labelledby=appearance-mode-label] button')).find(b=>b.textContent==='$($mode.label)').click();true")
    $transparentTheme=if($mode.theme-eq'light'){'glass-light'}else{'glass'}
    Wait-For {Evaluate "document.documentElement.dataset.theme==='$($mode.theme)'||document.documentElement.dataset.theme==='$transparentTheme'"} 'Glass theme did not update'
    Require (Evaluate "Array.from(document.querySelectorAll('.sidebar strong,.player h2')).every(e=>getComputedStyle(e).opacity==='1'&&getComputedStyle(e).filter==='none')" ) 'Glass changed foreground text opacity or filtering'
   }
   if(Evaluate "document.querySelector('[aria-labelledby=transparency-label]').getAttribute('aria-checked')!=='true'"){[void](Evaluate "document.querySelector('[aria-labelledby=transparency-label]').click();true")}
   if(Evaluate "document.querySelector('[aria-labelledby=player-transparency-label]').getAttribute('aria-checked')!=='true'"){[void](Evaluate "document.querySelector('[aria-labelledby=player-transparency-label]').click();true")}
   foreach($level in @(0,25,50,75,100)) {
    [void](Evaluate "(()=>{const e=document.querySelector('#appearance-transparency');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,$level);e.dispatchEvent(new Event('input',{bubbles:true}));return true})()")
    $expected=(1-$level/100).ToString([Globalization.CultureInfo]::InvariantCulture)
    Wait-For {Evaluate "Array.from(document.querySelectorAll('.sidebar,.player')).every(e=>{const c=document.createElement('canvas');c.width=c.height=1;const x=c.getContext('2d');x.fillStyle=getComputedStyle(e.querySelector('.liquid-glass-tint')).backgroundColor;x.fillRect(0,0,1,1);return Math.abs(x.getImageData(0,0,1,1).data[3]/255-$expected)<.005&&['.liquid-glass-blur','.liquid-glass-warp'].every(s=>getComputedStyle(e.querySelector(s)).opacity==='1')&&Math.abs(Number(getComputedStyle(e.querySelector('.liquid-glass-shine')).opacity)-Number(getComputedStyle(e).getPropertyValue('--liquid-shine')))<.001})"} 'Tint transparency changed glass effect strength or failed to update'
   }
   # At 100% tint transparency, actual wallpaper pixels must still be blurred.
   foreach($variant in @('transparent-glass','transparent-clear')) {
    if($variant-eq'transparent-clear'){[void](Evaluate "for(const e of document.querySelectorAll('.liquid-glass-blur,.liquid-glass-warp')){e.style.backdropFilter='none';e.style.filter='none'}true")}
    Start-Sleep -Milliseconds 200
    $shot=& $node $driver $port 'main' '--screenshot'
    Require ($LASTEXITCODE-eq0) 'Transparent glass screenshot failed'
    [IO.File]::WriteAllBytes((Join-Path $glassEvidence "$variant.png"),[Convert]::FromBase64String(($shot|ConvertFrom-Json)))
   }
   $a=[Drawing.Bitmap]::new((Join-Path $glassEvidence 'transparent-glass.png'));$b=[Drawing.Bitmap]::new((Join-Path $glassEvidence 'transparent-clear.png'))
   try {
    $regions=Evaluate "({width:innerWidth,panes:Array.from(document.querySelectorAll('.sidebar,.player')).map(e=>{const r=e.getBoundingClientRect();return {name:e.className,x:r.x,y:r.y,width:r.width,height:r.height}})})"
    $scale=$a.Width/$regions.width
    foreach($region in $regions.panes) {
     $changed=0
     for($y=[int](($region.y+24)*$scale);$y-lt($region.y+$region.height-24)*$scale;$y+=4){for($x=[int](($region.x+24)*$scale);$x-lt($region.x+$region.width-24)*$scale;$x+=4){
      $p=$a.GetPixel($x,$y);$q=$b.GetPixel($x,$y)
      if([Math]::Abs($p.R-$q.R)+[Math]::Abs($p.G-$q.G)+[Math]::Abs($p.B-$q.B)-gt20){$changed++}
     }}
     Write-Output "100% transparent $($region.name): blurred center samples=$changed"
     Require ($changed-gt100) 'Fully transparent tint lost its visible backdrop blur'
    }
   }finally{$a.Dispose();$b.Dispose()}
   [void](Evaluate "document.querySelector('#glass-fixture').remove();for(const e of document.querySelectorAll('.liquid-glass-blur,.liquid-glass-warp'))e.style.removeProperty('backdrop-filter');for(const e of document.querySelectorAll('.liquid-glass-warp'))e.style.filter=e.dataset.filter;true")
   [void](Evaluate "localStorage.setItem('musxi-game-track-transparency','100');window.dispatchEvent(new Event('musxi-game-track-transparency'));true" 'track')
   Wait-For {Evaluate "(()=>{const c=document.createElement('canvas');c.width=c.height=1;const x=c.getContext('2d');x.fillStyle=getComputedStyle(document.querySelector('.liquid-glass-tint')).backgroundColor;x.fillRect(0,0,1,1);return getComputedStyle(document.querySelector('.liquid-glass-shine')).opacity==='0'&&x.getImageData(0,0,1,1).data[3]===0&&getComputedStyle(document.querySelector('.overlay-track-details')).opacity==='1'})()" 'track'} 'Glass did not preserve full track transparency and clear text'
   [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
   Wait-TrackFit
   Wait-For {(Hit-Widget $track 'track' '.overlay-widget-header>span')-eq2} 'Transparent glass track lost its edit drag region'
   Require ([GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),700,180,0,0,0x11)) 'Could not position glass track for real input'
   Drag-Title $track 'track' 'Glass decoration blocked real title dragging'
   $before=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$before)
   $edgeX=$before.right-2;$edgeY=[int](($before.top+$before.bottom)/2)
   Require (([GameOverlayCheck]::Hit($track,$edgeX,$edgeY))-eq11) 'Glass track lost its right resize edge'
   Require (([GameOverlayCheck]::GetAsyncKeyState(1)-band0x8000)-eq0) 'Release the mouse before glass resize testing'
   [GameOverlayCheck]::Move($edgeX,$edgeY)
   try {
    [GameOverlayCheck]::Button($true);$mouseHeld=$true
    for($step=1;$step-le20;$step++){[GameOverlayCheck]::Move($edgeX+3*$step,$edgeY);Start-Sleep -Milliseconds 25}
   }finally{if($mouseHeld){[GameOverlayCheck]::Button($false);$mouseHeld=$false}}
   Wait-For {$after=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$after);[Math]::Abs($after.right-$after.left-($before.right-$before.left)-60)-le2} 'Glass track did not complete real edge resizing'
   Wait-TrackFit
   Require ((State).editing) 'Glass resizing ended manual editing'
   [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
   [void](Evaluate "$glassToggle.click();true")
   Wait-For {!(Evaluate "!!document.querySelector('.liquid-glass-surface')") -and !(Evaluate "!!document.querySelector('.liquid-glass-surface')" 'track')} 'Disabling glass did not restore both main and track appearances'
   Write-Output 'PASS: opt-in glass, persistence, cross-window sync, CEF pixel displacement, clear text, zero-alpha material and edit drag region'
  }finally {
   [void](Evaluate "localStorage.clear();for(const [key,value] of window.__glassStorageBackup)localStorage.setItem(key,value);true")
  }
  return
 }
 if($ResizeOnly) {
  [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
  [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
  Wait-For {(Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件')-ne[IntPtr]::Zero -and (Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息')-ne[IntPtr]::Zero} 'Resize windows did not open'
  $lyrics=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件';$track=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息'
  [void][GameOverlayCheck]::SetWindowPos($lyrics,[IntPtr](-1),80,100,600,380,0x10)
  [void][GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),1000,100,360,160,0x10)
  $fixture=& $node $driver $port 'lyrics' '--lyric-fixture'
  Require ($LASTEXITCODE-eq0) 'Resize lyric fixture did not load'
  Start-Sleep -Milliseconds 500
  $evidence=Join-Path (Split-Path -Parent $PSScriptRoot) 'build/resize-qa'
  [void][IO.Directory]::CreateDirectory($evidence)
  $trackMetrics="(()=>{const e=document.querySelector('.game-overlay'),c=e.querySelector('.cover,.vinyl-cover'),b=e.querySelector('.overlay-widget-header button');return {width:e.getBoundingClientRect().width,title:e.querySelector('h1').getBoundingClientRect().height,progress:e.querySelector('progress').getBoundingClientRect().height,padding:parseFloat(getComputedStyle(e).paddingTop),cover:c?.getBoundingClientRect().width||0,button:b?.getBoundingClientRect().width||0}})()"
  foreach($case in @(@{page='lyrics';window=$lyrics;delta=240},@{page='track';window=$track;delta=240},@{page='lyrics';window=$lyrics;delta=-160},@{page='track';window=$track;delta=-160})) {
   $page=$case.page;$window=$case.window;$delta=$case.delta
   if($page-eq'track'){$beforeMetrics=Evaluate $trackMetrics 'track'}
   [void](Evaluate "(()=>{window.__resizeProbe={frames:[],sizes:[],running:true};const p=window.__resizeProbe,m=document.createElement('div');m.style.cssText='position:absolute;left:3px;top:3px;width:4px;height:4px;background:red;pointer-events:none';document.body.append(m);const frame=()=>{if(!p.running){m.remove();return}p.frames.push(Date.now());m.style.opacity=p.frames.length%2?'1':'.5';requestAnimationFrame(frame)};requestAnimationFrame(frame);p.observer=new ResizeObserver(()=>p.sizes.push({time:Date.now(),width:innerWidth,height:innerHeight}));p.observer.observe(document.documentElement);return true})()" $page)
   $before=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$before)
   $x=$before.right-3;$y=[int](($before.top+$before.bottom)/2)
   Require (([GameOverlayCheck]::GetAsyncKeyState(1)-band0x8000)-eq0) 'Release the mouse before the resize test'
   [GameOverlayCheck]::Move($x,$y);Start-Sleep -Milliseconds 120
   Require ([GameOverlayCheck]::GetAncestor([GameOverlayCheck]::WindowFromPoint([GameOverlayCheck+Point]@{x=$x;y=$y}),2)-eq$window) 'Resize target is covered; no click sent'
   Require ([GameOverlayCheck]::Hit($window,$x,$y)-eq11) 'Resize edge is not HTRIGHT; no click sent'
   $samples=[Collections.Generic.List[object]]::new();$started=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
   try {
    [GameOverlayCheck]::Button($true);$mouseHeld=$true
    for($step=1;$step-le100;$step++) {
     [GameOverlayCheck]::Move($x+[int][Math]::Round($delta*$step/100),$y)
     Start-Sleep -Milliseconds 16
     $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$bounds)
     $samples.Add(@{time=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();width=$bounds.right-$bounds.left;height=$bounds.bottom-$bounds.top})
    }
   }finally{if($mouseHeld){[GameOverlayCheck]::Button($false);$mouseHeld=$false}}
   $ended=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
   Wait-For {$bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$bounds);[Math]::Abs($bounds.right-$bounds.left-($before.right-$before.left)-$delta)-le3} 'Resize did not reach the final requested width'
   $probe=Evaluate '(()=>{const p=window.__resizeProbe;p.running=false;p.observer.disconnect();return {frames:p.frames,sizes:p.sizes}})()' $page
   $frames=@($probe.frames|Where-Object {$_-ge$started -and $_-le$ended})
   $sizes=@($probe.sizes|Where-Object {$_.time-ge$started -and $_.time-le$ended})
   $changes=0;$lastWidth=$before.right-$before.left;$lastChange=$started;$maxGap=0
   foreach($sample in $samples){if($sample.width-ne$lastWidth){$changes++;$maxGap=[Math]::Max($maxGap,$sample.time-$lastChange);$lastChange=$sample.time;$lastWidth=$sample.width}}
   $maxGap=[Math]::Max($maxGap,$ended-$lastChange)
   $summary=@{widget=$page;delta=$delta;durationMs=$ended-$started;edgeUpdates=$changes;maxEdgeGapMs=$maxGap;rendererFrames=$frames.Count;rendererSizes=$sizes.Count}
   [IO.File]::WriteAllText((Join-Path $evidence "$page-$delta-external.json"),(@{summary=$summary;samples=$samples;renderer=$probe}|ConvertTo-Json -Depth 6))
   Write-Output ($summary|ConvertTo-Json -Compress)
   Require ($changes-ge20) 'Continuous resizing did not produce enough edge updates'
   Require ($sizes.Count-le2) 'Frozen resize viewport reflowed continuously during the drag'
   $scale=[GameOverlayCheck]::GetDpiForWindow($window)/96.0
   $expectedWidth=$before.right-$before.left+$delta
   try {Wait-For {[Math]::Abs((Evaluate 'innerWidth' $page)*$scale-$expectedWidth)-le1} 'Renderer did not settle at the final window width'} catch {
    $current=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$current)
    Write-Output (@{frozen=[GameOverlayCheck]::GetProp($window,'MusxiResizeView').ToInt64();width=$current.right-$current.left;renderer=Evaluate '({width:innerWidth,height:innerHeight,ready:document.readyState})' $page;state=State}|ConvertTo-Json -Depth 5 -Compress)
    throw
   }
   $settled=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($window,[ref]$settled)
   Require ($settled.left-eq$before.left -and $settled.top-eq$before.top) 'Resizing shifted the component origin'
   if($page-eq'track'){
    Wait-TrackFit
    $afterMetrics=Evaluate $trackMetrics 'track';$ratio=$afterMetrics.width/$beforeMetrics.width
    foreach($metric in @('title','progress','padding','cover')){
     Require ([Math]::Abs($afterMetrics.$metric-$beforeMetrics.$metric*$ratio)-le1) "Track $metric did not scale with width"
    }
    Require ([Math]::Abs($afterMetrics.button-$beforeMetrics.button)-lt1) 'Track editor buttons scaled with content'
    Require ((($delta-gt0)-and((Track-Height)-gt($before.bottom-$before.top))) -or (($delta-lt0)-and((Track-Height)-lt($before.bottom-$before.top)))) 'Track height did not follow scaled content'
   }
   Require ((State).editing) 'Resizing ended manual editing'
  }
  $originalCover=Evaluate "localStorage.getItem('musxi-lyrics-player-style')" 'lyrics'
  try {
   [GameOverlayCheck]::Move(0,0)
   foreach($coverStyle in @('vinyl','square','none')){
    [void](Evaluate "localStorage.setItem('musxi-lyrics-player-style','$coverStyle');true" 'lyrics')
    $selector=switch($coverStyle){'vinyl'{'.overlay-track-details>.vinyl-cover'} 'square'{'.overlay-track-details>.cover'} 'none'{'.overlay-track-details:not(:has(.cover))'}}
    Wait-For {Evaluate "!!document.querySelector('$selector')" 'track'} 'Scale test cover style did not synchronize'
    foreach($width in @(300,360,720,1600)){
     [void][GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),80,100,$width,200,0x10)
     Wait-For {[Math]::Abs((Evaluate 'innerWidth' 'track')-$width)-le1} 'Scale test width did not settle'
     Wait-TrackFit
     $metrics=Evaluate $trackMetrics 'track';$factor=$metrics.width/360
     Require ([Math]::Abs($metrics.title-21.6*$factor)-le1 -and [Math]::Abs($metrics.progress-4*$factor)-le1 -and [Math]::Abs($metrics.padding-12*$factor)-le1) 'Content dimensions do not match the default-width scale'
     Require (Evaluate "(()=>{const title=document.querySelector('h1'),style=getComputedStyle(title),buttons=document.querySelector('.overlay-widget-header>div'),scale=Number(getComputedStyle(document.querySelector('.overlay-track-content')).zoom);return title.getBoundingClientRect().right-parseFloat(style.paddingRight)*scale<=buttons.getBoundingClientRect().left-4})()" 'track') 'Scaled title overlaps fixed editor buttons'
     $editingHeight=Track-Height
     [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
     Wait-TrackFit
     Require ([Math]::Abs((Track-Height)-$editingHeight)-le1) 'Scaled track changed size when leaving editing'
     [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
     Wait-TrackFit
     Require ([Math]::Abs((Track-Height)-$editingHeight)-le1) 'Scaled track changed size when entering editing'
    }
   }
  } finally {
   $restoreCover=if($null-eq$originalCover){"localStorage.removeItem('musxi-lyrics-player-style');true"}else{"localStorage.setItem('musxi-lyrics-player-style',"+($originalCover|ConvertTo-Json -Compress)+");true"}
   [void](Evaluate $restoreCover 'lyrics')
  }
  Write-Output 'PASS: real mouse resizing, proportional content at 300/360/720/1600 widths, fixed editor buttons, all cover styles and stable edit dimensions'
  return
 }
 if($UnifiedOnly) {
  [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
  Wait-For {(Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件') -ne [IntPtr]::Zero} 'Unified lyrics did not open'
  $lyrics=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件';$track=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息'
  Require ((Find-Window 'MusxiDesktopLyrics') -eq [IntPtr]::Zero) 'Duplicate legacy lyrics window exists'
  Require ([GameOverlayCheck]::SetWindowPos($lyrics,[IntPtr]::Zero,80,100,0,0,0x15)) 'Could not position lyrics for hover coverage'
  Require ([GameOverlayCheck]::SetWindowPos($track,[IntPtr]::Zero,1000,100,0,0,0x15)) 'Could not position track for hover coverage'
  foreach($kind in @('Lyrics','Track')) {
   [void](Bridge 'gameOverlay.setVisible' @{visible=$false})
   if($kind-eq'Lyrics') {
    [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$true})
    Require ((State).visible -and [GameOverlayCheck]::IsWindowVisible($lyrics) -and -not [GameOverlayCheck]::IsWindowVisible($track)) 'Independent lyrics switch did not restore only lyrics after group hide'
   } else {
    [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$true})
    Require ((State).visible -and [GameOverlayCheck]::IsWindowVisible($track) -and -not [GameOverlayCheck]::IsWindowVisible($lyrics)) 'Independent track switch did not restore only track after group hide'
   }
  }
  [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$true})
  foreach($flag in @($false,$true)) {
   [void](Bridge 'gameOverlay.setTopmost' @{topmost=$flag})
   Start-Sleep -Milliseconds 400
   Require ((Has-Style $lyrics 8)-eq$flag -and (Has-Style $track 8)-eq$flag) 'Shared topmost did not affect both widgets'
   [void][GameOverlayCheck]::PostMessage($lyrics,0x7e,[IntPtr]::Zero,[IntPtr]::Zero)
   [void][GameOverlayCheck]::PostMessage($track,0x7e,[IntPtr]::Zero,[IntPtr]::Zero)
   Start-Sleep -Milliseconds 400
   Require ((Has-Style $lyrics 8)-eq$flag -and (Has-Style $track 8)-eq$flag) 'Display refresh changed the shared topmost preference'
  }
  [void](Bridge 'desktopLyrics.setVisible' @{visible=$false})
  Require (-not [GameOverlayCheck]::IsWindowVisible($lyrics) -and [GameOverlayCheck]::IsWindowVisible($track)) 'Legacy lyrics hide affected the track widget'
  [void](Bridge 'desktopLyrics.setVisible' @{visible=$true})
  Require ([GameOverlayCheck]::IsWindowVisible($lyrics) -and (Bridge 'desktopLyrics.getState').visible) 'Legacy show did not target the unified window'
  Require ((Find-Window 'MusxiDesktopLyrics') -eq [IntPtr]::Zero) 'Legacy show created a second window'
  [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
  $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$bounds)
  [GameOverlayCheck]::Move([int](($bounds.left+$bounds.right)/2),[int](($bounds.top+$bounds.bottom)/2))
  Wait-For {Evaluate "!!document.querySelector('.dl-capsule')&&!document.querySelector('.dl-colors')" 'lyrics'} 'Hover toolbar is missing during manual editing'
  foreach($page in @('lyrics','track')) {
   Require (Evaluate "(()=>{const h=document.querySelector('.overlay-widget-header');return h?.querySelector('span')?.textContent===''&&Array.from(h.querySelectorAll('button')).map(b=>b.textContent).join(',')==='完成,关闭'})()" $page) 'Widget editor retained removed labels or controls'
   Require ((Hit-Widget $(if($page-eq'lyrics'){$lyrics}else{$track}) $page '.overlay-widget-header>span') -eq 2) 'Empty widget header lost its drag region'
  }
  Require (Evaluate "!document.querySelector('.overlay-track-content button')" 'track') 'Song info retained playback controls'
  Require (Evaluate "(()=>{const title=document.querySelector('.overlay-track-details h1'),buttons=document.querySelector('.overlay-widget-header>div'),style=getComputedStyle(title),zoom=Number(getComputedStyle(document.querySelector('.overlay-track-content')).zoom);return title.getBoundingClientRect().right-parseFloat(style.paddingRight)*zoom<=buttons.getBoundingClientRect().left-4&&style.textOverflow==='ellipsis'})()" 'track') 'Track title text overlaps editing buttons'
  Wait-TrackFit
  [void](Bridge 'desktopLyrics.setFontSize' @{fontSize=36})
  Require ((State).lyricsFontSize-eq36) 'Toolbar font is not shared with widget settings'
  foreach($mode in @('single','multi')) {
   [void](Bridge 'gameOverlay.setLyricsMode' @{lyricsMode=$mode})
   Require ((State).lyricsMode-eq$mode) 'Lyric mode did not update'
  }
  $fixture=& $node $driver $port 'lyrics' '--lyric-fixture'
  Require ($LASTEXITCODE-eq0) 'Could not load lyrics for unified mode verification'
  [void](Evaluate "localStorage.setItem('musxi-lyrics-translation','on');window.dispatchEvent(new Event('musxi-lyrics-translation'));true" 'lyrics')
  [void](Bridge 'gameOverlay.setLyricsMode' @{lyricsMode='single'})
  Wait-For {Evaluate "(()=>{const l=document.querySelector('.overlay-lyrics-list');return l?.children.length===1&&l.querySelector('span')?.textContent==='Drag fixture line 100'&&l.querySelector('small')?.textContent==='Translation 100'&&getComputedStyle(l.firstElementChild).fontSize==='36px'})()" 'lyrics'} 'Single-line mode lost the current sentence, translation or independent font'
  Require (Evaluate "getComputedStyle(document.querySelector('.overlay-single-content')).opacity==='1'" 'lyrics') 'First single-line sentence played an entry animation'
  $animation=Evaluate "(async()=>{window.__overlayFixtureProbe.setPosition(101000);await new Promise(r=>setTimeout(r,100));return Array.from(document.querySelectorAll('.overlay-single-content')).map(e=>({text:e.textContent,opacity:Number(getComputedStyle(e).opacity),y:new DOMMatrix(getComputedStyle(e).transform).m42}))})()" 'lyrics'
  Require ($animation.Count-eq2 -and $animation[0].text-like'*Translation 100*' -and $animation[1].text-like'*Translation 101*') 'Single-line sentence and translation did not crossfade as complete groups'
  Require ($animation[0].y-lt0 -and $animation[1].y-gt0 -and $animation[0].opacity-gt0 -and $animation[0].opacity-lt1 -and $animation[1].opacity-gt0 -and $animation[1].opacity-lt1) 'Single-line transition lost its opposing motion or opacity animation'
  Wait-For {Evaluate "document.querySelectorAll('.overlay-single-content').length===1&&document.querySelector('.overlay-single-content').textContent.includes('Translation 101')&&getComputedStyle(document.querySelector('.overlay-single-content')).opacity==='1'" 'lyrics'} 'Single-line animation did not settle on the new sentence'
  [void](Evaluate "window.__overlayFixtureProbe.setPosition(100000);true" 'lyrics')
  Start-Sleep -Milliseconds 400
  $reducedCheck=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("(async()=>{window.__overlayFixtureProbe.setPosition(102000);await new Promise(r=>setTimeout(r,80));const e=document.querySelector('.overlay-single-content');return matchMedia('(prefers-reduced-motion: reduce)').matches&&document.querySelectorAll('.overlay-single-content').length===1&&e.textContent.includes('Translation 102')&&getComputedStyle(e).opacity==='1'&&new DOMMatrix(getComputedStyle(e).transform).m42===0})()"))
  $reducedResult=& $node $driver $port 'lyrics' "--reduce-motion:$reducedCheck"
  Require ($LASTEXITCODE-eq0 -and ($reducedResult|ConvertFrom-Json)) 'Reduced motion did not directly settle the next sentence'
  [void](Evaluate "window.__overlayFixtureProbe.setPosition(100000);true" 'lyrics')
  Start-Sleep -Milliseconds 400
  [void](Evaluate "document.querySelector('[aria-label=显示翻译]').click();true" 'lyrics')
  Wait-For {Evaluate "!document.querySelector('.overlay-lyrics-list small')&&localStorage.getItem('musxi-lyrics-translation')==='off'" 'lyrics'} 'Toolbar did not synchronize the shared translation setting'
  [void](Evaluate "localStorage.setItem('musxi-lyrics-translation','on');window.dispatchEvent(new Event('musxi-lyrics-translation'));true")
  Wait-For {Evaluate "!!document.querySelector('.overlay-lyrics-list small')" 'lyrics'} 'Translation did not sync from the player to the unified window'
  [void](Bridge 'gameOverlay.setLyricsMode' @{lyricsMode='multi'})
  Wait-For {Evaluate "document.querySelectorAll('.overlay-lyrics-list li').length===200&&document.querySelector('.overlay-lyrics-list').scrollTop>1000" 'lyrics'} 'Multiline mode did not restore scrolling context'
  [void](Evaluate "window.__unifiedDisplay=localStorage.getItem('musxi-lyrics-display');localStorage.setItem('musxi-lyrics-display',JSON.stringify({fontSize:16,colorMode:'cover'}));true")
  Wait-For {Evaluate "!!document.querySelector('.overlay-lyrics-viewport')?.style.getPropertyValue('--vinyl-color')&&getComputedStyle(document.querySelector('.overlay-lyrics-list li')).fontSize==='36px'" 'lyrics'} 'Cover color did not sync or page font overwrote independent floating size'
  [void](Evaluate "if(window.__unifiedDisplay===null)localStorage.removeItem('musxi-lyrics-display');else localStorage.setItem('musxi-lyrics-display',window.__unifiedDisplay);true")
  $invalid=Bridge 'gameOverlay.setLyricsMode' @{lyricsMode='dual'} -AllowFailure
  Require (-not $invalid.ok -and $invalid.code-eq400) 'Invalid lyric mode was accepted'
  [void](Bridge 'desktopLyrics.setLocked' @{locked=$true})
  Wait-For {-not (State).editing -and (Has-Style $lyrics 0x20) -and !(Evaluate "!!document.querySelector('.dl-capsule')" 'lyrics')} 'Toolbar lock did not finish editing'
  $capture=& $node $driver $port 'lyrics' '--screenshot'
  Require ($LASTEXITCODE-eq0) 'Unified lyrics screenshot failed'
  [IO.File]::WriteAllBytes((Join-Path $screenshots 'unified-lyrics.png'),[Convert]::FromBase64String(($capture|ConvertFrom-Json)))
  foreach($legacyCommand in @('desktopLyrics.setColor','desktopLyrics.setDual')) {
   $legacyParams=if($legacyCommand-eq'desktopLyrics.setColor'){@{color='dark'}}else{@{dual=$false}}
   $reply=Bridge $legacyCommand $legacyParams -AllowFailure
   Require (-not $reply.ok -and $reply.code-eq409) 'Obsolete independent color/translation command silently succeeded'
  }
  $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$bounds)
  $insideX=[int](($bounds.left+$bounds.right)/2);$insideY=[int](($bounds.top+$bounds.bottom)/2)
  [GameOverlayCheck]::Move(0,0)
  Start-Sleep -Milliseconds 200
  $returnWindow=[GameOverlayCheck]::GetForegroundWindow()
  [GameOverlayCheck]::Move($insideX,$insideY)
  Wait-For {(State).lyricsEditing -and !(State).editing -and [GameOverlayCheck]::GetForegroundWindow()-eq$lyrics} 'Hover did not enter the lyrics-only focused editor'
  Require ((Has-Style $track 0x20) -and (Has-Style $track 0x08000000)) 'Hovering lyrics unlocked or activated the song-info widget'
  Wait-For {Evaluate "!!document.querySelector('.dl-capsule')&&!!document.querySelector('.overlay-widget-header')&&!document.querySelector('.dl-unlock')&&!document.querySelector('[aria-label=锁定歌词悬浮组件]')" 'lyrics'} 'Hover editor or toolbar controls are incorrect'
  [void][GameOverlayCheck]::PostMessage($lyrics,0x205,[IntPtr]::Zero,[IntPtr]::Zero)
  Start-Sleep -Milliseconds 160
  Require (Evaluate "!!document.querySelector('.dl-capsule')" 'lyrics') 'Right click still toggles the hover toolbar'
  # Native move/resize and captured drags must survive pointer excursions.
  [void][GameOverlayCheck]::PostMessage($lyrics,0x231,[IntPtr]::Zero,[IntPtr]::Zero)
  Start-Sleep -Milliseconds 100
  [GameOverlayCheck]::Move(0,0)
  Start-Sleep -Milliseconds 200
  Require ((State).lyricsEditing) 'Moving/resizing ended editing when pointer left the window'
  [void][GameOverlayCheck]::PostMessage($lyrics,0x232,[IntPtr]::Zero,[IntPtr]::Zero)
  Wait-For {!(State).lyricsEditing -and [GameOverlayCheck]::GetForegroundWindow()-eq$returnWindow} 'Resize completion did not lock lyrics and return focus after leaving'
  Wait-For {!(Evaluate "!!document.querySelector('.dl-capsule')||!!document.querySelector('.overlay-widget-header')" 'lyrics')} 'Leaving lyrics retained editing controls'
  [GameOverlayCheck]::Move($insideX,$insideY)
  Wait-For {(State).lyricsEditing} 'Hover did not re-enter after leaving'
  [void][GameOverlayCheck]::PostMessage($lyrics,0x201,[IntPtr](1),[IntPtr](0x00640014))
  Start-Sleep -Milliseconds 100
  [GameOverlayCheck]::Move(0,0)
  Start-Sleep -Milliseconds 200
  Require ((State).lyricsEditing) 'Captured pointer interaction ended editing outside the lyric window'
  [void][GameOverlayCheck]::PostMessage($lyrics,0x202,[IntPtr]::Zero,[IntPtr](0x00640014))
  Wait-For {!(State).lyricsEditing} 'Releasing pointer capture did not finish outside hover editing'
  [GameOverlayCheck]::Move($insideX,$insideY)
  Wait-For {(State).lyricsEditing} 'Hover did not resume after captured interaction'
  [void](Evaluate "document.querySelector('[aria-label=完成编辑]').click();true" 'lyrics')
  Wait-For {!(State).lyricsEditing} 'Done did not end hover editing'
  Start-Sleep -Milliseconds 240
  Require (!(State).lyricsEditing) 'Done immediately re-entered while cursor stayed inside'
  [GameOverlayCheck]::Move(0,0);Start-Sleep -Milliseconds 160
  [GameOverlayCheck]::Move($insideX,$insideY)
  Wait-For {(State).lyricsEditing} 'Leaving and returning did not clear Done suppression'
  [void][GameOverlayCheck]::SetForegroundWindow($main)
  Wait-For {!(State).lyricsEditing -and [GameOverlayCheck]::GetForegroundWindow()-eq$main} 'Switching applications retained hover editing or stole focus back'
  Start-Sleep -Milliseconds 240
  Require ([GameOverlayCheck]::GetForegroundWindow()-eq$main -and !(State).lyricsEditing) 'Hover stole focus after the user switched away'
  [GameOverlayCheck]::Move(0,0);Start-Sleep -Milliseconds 160
  [GameOverlayCheck]::Move($insideX,$insideY)
  Wait-For {(State).lyricsEditing} 'Hover did not resume after leaving the switched-away region'
  [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
  [GameOverlayCheck]::Move(0,0)
  Start-Sleep -Milliseconds 200
  Require ((State).editing -and (State).lyricsEditing -and !(Has-Style $track 0x20)) 'Manual editing ended on pointer exit or failed to unlock song info'
  Wait-For {!(Evaluate "!!document.querySelector('.dl-capsule')" 'lyrics')} 'Manual editor retained the toolbar after pointer exit'
  [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
  Require (!(State).editing -and !(State).lyricsEditing) 'Manual Done did not lock both widgets'
  # The same hover lifecycle applies independently to song info.
  [GameOverlayCheck]::Move(0,0);Start-Sleep -Milliseconds 160
  # Overlap two locked, input-transparent widgets; only the visually upper one may activate.
  $lyricBounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$lyricBounds)
  Require ([GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),$lyricBounds.left+80,$lyricBounds.top+60,0,0,0x11)) 'Could not prepare overlapping hover targets'
  $trackBounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$trackBounds)
  $trackX=[int](($trackBounds.left+$trackBounds.right)/2);$trackY=$trackBounds.top+40
  $trackReturn=[GameOverlayCheck]::GetForegroundWindow()
  [GameOverlayCheck]::Move($trackX,$trackY)
  Wait-For {(State).trackEditing -and !(State).lyricsEditing -and !(State).editing -and [GameOverlayCheck]::GetForegroundWindow()-eq$track} 'Track hover did not enter its isolated focused editor'
  Require ((Has-Style $lyrics 0x20) -and !(Has-Style $track 0x20)) 'Track hover unlocked lyrics or left track click-through'
  Wait-TrackFit
  $editingTrackBounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$editingTrackBounds)
  Require (($editingTrackBounds.right-$editingTrackBounds.left)-eq($trackBounds.right-$trackBounds.left) -and ($editingTrackBounds.bottom-$editingTrackBounds.top)-eq($trackBounds.bottom-$trackBounds.top)) 'Track hover editing changed the window size'
  [void][GameOverlayCheck]::PostMessage($track,0x231,[IntPtr]::Zero,[IntPtr]::Zero)
  [GameOverlayCheck]::Move(0,0);Start-Sleep -Milliseconds 200
  Require ((State).trackEditing) 'Track move/resize did not retain hover editing outside its bounds'
  [void][GameOverlayCheck]::PostMessage($track,0x232,[IntPtr]::Zero,[IntPtr]::Zero)
  Wait-For {!(State).trackEditing -and [GameOverlayCheck]::GetForegroundWindow()-eq$trackReturn} 'Track hover did not lock and restore focus after move/resize'
  [GameOverlayCheck]::Move($trackX,$trackY)
  Wait-For {(State).trackEditing} 'Track hover did not resume'
  [void](Evaluate "document.querySelector('[aria-label=完成编辑]').click();true" 'track')
  Wait-For {!(State).trackEditing} 'Track Done did not lock'
  Wait-TrackFit
  Require ((Track-Height)-eq($trackBounds.bottom-$trackBounds.top)) 'Finishing track editing changed the window height'
  Start-Sleep -Milliseconds 240
  Require (!(State).trackEditing) 'Track Done re-entered before pointer exit'
  [GameOverlayCheck]::Move(0,0);Start-Sleep -Milliseconds 160
  [GameOverlayCheck]::Move($trackX,$trackY)
  Wait-For {(State).trackEditing} 'Track Done suppression did not clear after leaving'
  [GameOverlayCheck]::Move(0,0)
  $prefs=Get-Content -LiteralPath (Join-Path $profile 'game-overlay.json') -Raw|ConvertFrom-Json
  Require ($prefs.version-eq2 -and $prefs.lyricsFontSize-eq36 -and $prefs.topmost -and $prefs.lyricsMode-eq'multi') 'Unified preferences were not persisted'
  Write-Output 'PASS: unified lyrics, hover-only editor/toolbar and focus return, isolated track, Done suppression, resize guard, application switch exceptions, persistent manual editor and prior mode/appearance checks'
  return
 }

 [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
 Require (Test-Path -LiteralPath $minecraftFixture) 'Build minecraft-fixture before the CEF test'
 Require (-not (State).windowCompatibility -and (State).windowCompatibilityStatus -eq 'off') 'Manual tool must start off even with legacy automatic preferences'
 $legacy=Bridge 'gameOverlay.setMinecraftCompatibility' @{minecraftCompatibility=$true} -AllowFailure
 Require (-not $legacy.ok -and $legacy.code-eq409) 'Legacy command resumed automatic management'
 foreach($params in @(@{windowCompatibility=1},@{windowCompatibility=$true;extra=$true})) {
  $invalid=Bridge 'gameOverlay.setWindowCompatibility' $params -AllowFailure
  Require (-not $invalid.ok -and $invalid.code-eq400) 'Malformed manual tool setting was accepted'
 }
 $minecraft=Start-Process -FilePath $minecraftFixture -WindowStyle Hidden -PassThru
 Wait-For {(Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id) -ne [IntPtr]::Zero} 'Window fixture did not open'
 $minecraftWindow=Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id
 $minecraftOriginal=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$minecraftOriginal)
 Start-Sleep -Milliseconds 600
 $untouched=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$untouched)
 Require ($untouched.right-$untouched.left-eq600 -and $untouched.bottom-$untouched.top-eq400) 'Window was managed without clicking apply'
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$true})
 $choices=Bridge 'gameOverlay.listCompatibilityWindows'
 Require (-not @($choices|Where-Object {$_.program -like 'MusxiPlayer*'}).Count) 'Window list included the player'
 $choice=@($choices|Where-Object {$_.program-eq'GameWindowFixture.exe' -and $_.title-eq'Minecraft compatibility fixture'})
 Require ($choice.Count-eq1) 'Generic executable was not listed by title and program'
 $oldId=$choice[0].id
 $fresh=Bridge 'gameOverlay.listCompatibilityWindows'
 $stale=Bridge 'gameOverlay.applyWindowCompatibility' @{id=$oldId} -AllowFailure
 Require (-not $stale.ok -and $stale.code-eq409) 'Refreshed window list accepted stale choice'
 $id=@($fresh|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 [void][GameOverlayCheck]::PostMessage($minecraftWindow,0x8001,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {([GameOverlayCheck]::GetWindowLongPtr($minecraftWindow,-16).ToInt64()-band0xc00000)-eq0} 'Fixture did not enter popup mode'
 $fullscreen=Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id} -AllowFailure
 Require (-not $fullscreen.ok -and $fullscreen.code-eq409) 'Popup/fullscreen target was adjusted'
 [void][GameOverlayCheck]::PostMessage($minecraftWindow,0x8002,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {([GameOverlayCheck]::GetWindowLongPtr($minecraftWindow,-16).ToInt64()-band0xc00000)-eq0xc00000} 'Fixture did not return windowed'
 $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 $focus=[GameOverlayCheck]::GetForegroundWindow()
 Require ([GameOverlayCheck]::SetWindowText($minecraftWindow,'Game title changed')) 'Fixture title update failed'
 [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
 Require ((State).windowCompatibilityStatus-eq'applied' -and [GameOverlayCheck]::GetForegroundWindow()-eq$focus) 'Manual apply failed or stole focus'
 Require ((State).windowCompatibilityTarget-eq'Game title changed') 'Dynamic title invalidated a live window or retained stale target text'
 $client=[GameOverlayCheck+Rect]::new();$origin=[GameOverlayCheck+Point]::new()
 [void][GameOverlayCheck]::GetClientRect($minecraftWindow,[ref]$client);[void][GameOverlayCheck]::ClientToScreen($minecraftWindow,[ref]$origin)
 $monitor=[GameOverlayCheck+MonitorInfo]::new();$monitor.size=[Runtime.InteropServices.Marshal]::SizeOf($monitor)
 [void][GameOverlayCheck]::GetMonitorInfo([GameOverlayCheck]::MonitorFromPoint($origin,2),[ref]$monitor)
 Require ($origin.x-eq$monitor.monitor.left -and $origin.y-eq$monitor.monitor.top -and $client.right-eq($monitor.monitor.right-$monitor.monitor.left) -and $client.bottom-eq($monitor.monitor.bottom-$monitor.monitor.top)) 'Game client has a crop or gap'
 $duplicate=Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id} -AllowFailure
 Require (-not $duplicate.ok -and $duplicate.code-eq409) 'Second application replaced the managed target'
 [void][GameOverlayCheck]::SetWindowPos($minecraftWindow,[IntPtr]::Zero,25,25,800,600,0x14)
 Wait-For {(State).windowCompatibilityStatus-eq'userChanged'} 'Manual game resize was not respected'
 Start-Sleep -Milliseconds 600
 [void](Bridge 'gameOverlay.restoreWindowCompatibility')
 $manual=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$manual)
 Require ($manual.left-eq25 -and $manual.top-eq25 -and $manual.right-eq825 -and $manual.bottom-eq625) 'Ending management undid user layout'
 [void][GameOverlayCheck]::PostMessage($minecraftWindow,0x8002,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {([GameOverlayCheck]::GetWindowLongPtr($minecraftWindow,-16).ToInt64()-band0xc00000)-eq0xc00000} 'Fixture did not reset windowed'
 $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$false})
 $restored=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$restored)
 Require ($restored.left-eq$minecraftOriginal.left -and $restored.top-eq$minecraftOriginal.top -and $restored.right-eq$minecraftOriginal.right -and $restored.bottom-eq$minecraftOriginal.bottom) 'Closing tool did not restore original layout'
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$true})
 $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$false})
 Require (-not (State).windowCompatibility) 'Module disable retained manual tool enablement'
 [void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$restored)
 Require ($restored.right-$restored.left-eq600 -and $restored.bottom-$restored.top-eq400) 'Module disable did not restore game'
 [void](Bridge 'gameOverlay.setEnabled' @{enabled=$true})
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$true})
 $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
 [void][GameOverlayCheck]::PostMessage($minecraftWindow,0x10,[IntPtr]::Zero,[IntPtr]::Zero)
 Require ($minecraft.WaitForExit(5000)) 'Managed fixture did not exit'
 Wait-For {(State).windowCompatibilityTarget-eq'' -and (State).windowCompatibilityStatus-eq'ready'} 'Closed target retained management'
 $minecraft=Start-Process -FilePath $minecraftFixture -WindowStyle Hidden -PassThru
 Wait-For {(Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id) -ne [IntPtr]::Zero} 'Restarted fixture did not open'
 $minecraftWindow=Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id;Start-Sleep -Milliseconds 600
 [void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$restored)
 Require ($restored.right-$restored.left-eq600 -and $restored.bottom-$restored.top-eq400 -and (State).windowCompatibilityTarget-eq'') 'Restarted game was automatically managed'
 $closed=Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id} -AllowFailure
 Require (-not $closed.ok -and $closed.code-eq409) 'Closed game session choice was reused'
 $savedPreferences=Get-Content -LiteralPath (Join-Path $profile 'game-overlay.json') -Raw|ConvertFrom-Json
 Require (-not ($savedPreferences.PSObject.Properties.Name -contains 'windowCompatibility') -and -not ($savedPreferences.PSObject.Properties.Name -contains 'minecraftCompatibility')) 'Compatibility enablement was persisted'
 if($CompatibilityOnly) {
  $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
  [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
  Stop-TestPlayer
  Require ($app.ExitCode-eq0) 'Player exit failed during manual compatibility check'
  [void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$restored)
  Require ($restored.right-$restored.left-eq600 -and $restored.bottom-$restored.top-eq400) 'Player exit did not restore the managed game'
  Write-Output 'PASS: manual window compatibility lifecycle, no auto application, expired selection rejection, complete monitor client, no focus change, manual takeover, tool shutdown and player-exit restoration'
  return
 }
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$false})
 [void][GameOverlayCheck]::PostMessage($minecraftWindow,0x10,[IntPtr]::Zero,[IntPtr]::Zero);Require ($minecraft.WaitForExit(5000)) 'Window fixture did not close'
 $minecraft=$null
 Write-Output 'PASS: manual window selection, stale/fullscreen rejection, exact client bounds, focus preservation, restoration, user changes, tool shutdown, legacy auto removal and no reapply after game restart'
 [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
 Wait-For {(Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件') -ne [IntPtr]::Zero -and (Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息') -ne [IntPtr]::Zero} 'Both overlay windows were not created'
 $lyrics=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌词悬浮组件';$track=Find-Window 'MusxiGameOverlay' 'Musxi Player 歌曲信息'
 foreach($widget in @($lyrics,$track)){
  Require ([GameOverlayCheck]::IsWindowVisible($widget)) 'Enabled widget is hidden'
  Require (Has-Style $widget 0x08080028) 'Widget lacks layered/topmost/noactivate/click-through styles'
  $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($widget,[ref]$bounds)
  $monitor=[GameOverlayCheck+MonitorInfo]::new();$monitor.size=[Runtime.InteropServices.Marshal]::SizeOf($monitor)
  [void][GameOverlayCheck]::GetMonitorInfo([GameOverlayCheck]::MonitorFromPoint([GameOverlayCheck+Point]@{x=$bounds.left;y=$bounds.top},2),[ref]$monitor)
  Require ($bounds.left -ge $monitor.work.left -and $bounds.top -ge $monitor.work.top -and $bounds.right -le $monitor.work.right -and $bounds.bottom -le $monitor.work.bottom) 'Offscreen widget was not clamped to a monitor'
 }
 [GameOverlayCheck]::StartSink()
 $sinkBounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect([GameOverlayCheck]::Sink,[ref]$sinkBounds)
 Require ([GameOverlayCheck]::SetWindowPos([GameOverlayCheck]::Sink,[IntPtr](-1),$monitor.work.left,$monitor.work.top,$monitor.work.right-$monitor.work.left,$monitor.work.bottom-$monitor.work.top,0x10)) 'Could not expand the simulated foreground game for cursor checks'
 $hoverBounds=@{}
 foreach($entry in @(@($lyrics,40),@($track,720))){
  $bounds=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($entry[0],[ref]$bounds);$hoverBounds["$($entry[0])"]=$bounds
  Require ([GameOverlayCheck]::SetWindowPos($entry[0],[IntPtr](-1),$monitor.work.left+$entry[1],$monitor.work.top+240,0,0,0x11)) 'Could not arrange nonoverlapping hover targets'
 }
 $hoverPoint=Widget-Point $lyrics 'lyrics' '.game-overlay'
 if(-not [GameOverlayCheck]::SetForegroundWindow([GameOverlayCheck]::Sink)){
  # Activate the test window through its uncovered corner when Windows denies programmatic focus.
  [GameOverlayCheck]::Move($monitor.work.left+10,$monitor.work.bottom-10)
  [GameOverlayCheck]::Button($true);[GameOverlayCheck]::Button($false)
 }
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Could not activate the hover test application'
 [GameOverlayCheck]::Move($hoverPoint.x,$hoverPoint.y)
 Start-Sleep -Milliseconds 200
 Require ([GameOverlayCheck]::IsWindowVisible($lyrics)) 'Disabled hover option hid a component'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 [void][GameOverlayCheck]::SetForegroundWindow([GameOverlayCheck]::Sink)
 Wait-For {[GameOverlayCheck]::GetForegroundWindow()-eq[GameOverlayCheck]::Sink} 'Could not return to the game after hovering lyrics'
 foreach($invalid in @(@{},@{hideOnHover='true'},@{hideOnHover=$true;extra=1})){
  $reply=Bridge 'gameOverlay.setHideOnHover' $invalid -AllowFailure
  Require (-not $reply.ok -and $reply.code -eq 400) 'Malformed hover preference was accepted'
 }
 [void](Bridge 'gameOverlay.setHideOnHover' @{hideOnHover=$true})
 Wait-For {-not [GameOverlayCheck]::IsWindowVisible($lyrics) -and -not [GameOverlayCheck]::IsWindowVisible($track)} 'Visible cursor did not hide both components'
 Require ((State).visible -and (State).lyricsVisible -and (State).trackVisible) 'Cursor hiding changed user selection'
 Require ([GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink) 'Hover hiding stole focus'
 [void][GameOverlayCheck]::PostMessage([GameOverlayCheck]::Sink,0x800A,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {[GameOverlayCheck]::IsWindowVisible($lyrics) -and [GameOverlayCheck]::IsWindowVisible($track)} 'Hidden system cursor did not restore both components'
 [void][GameOverlayCheck]::PostMessage([GameOverlayCheck]::Sink,0x800B,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {-not [GameOverlayCheck]::IsWindowVisible($lyrics)} 'Showing the cursor did not resume hover hiding'
 $point=Widget-Point $track 'track' '.game-overlay';[GameOverlayCheck]::Move($point.x,$point.y)
 Start-Sleep -Milliseconds 200
 Require (-not [GameOverlayCheck]::IsWindowVisible($lyrics) -and -not [GameOverlayCheck]::IsWindowVisible($track)) 'Moving between components restored them while the cursor remained visible'
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$false})
 [GameOverlayCheck]::Move($monitor.work.left+10,$monitor.work.top+10)
 Start-Sleep -Milliseconds 200
 Require (-not [GameOverlayCheck]::IsWindowVisible($lyrics) -and -not [GameOverlayCheck]::IsWindowVisible($track)) 'Moving outside component bounds restored them while the cursor remained visible'
 [void][GameOverlayCheck]::PostMessage([GameOverlayCheck]::Sink,0x800A,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {[GameOverlayCheck]::IsWindowVisible($lyrics)} 'Hiding cursor outside component bounds did not restore lyrics'
 Require (-not [GameOverlayCheck]::IsWindowVisible($track)) 'Hiding cursor restored a user-disabled component'
 [void][GameOverlayCheck]::PostMessage([GameOverlayCheck]::Sink,0x800B,[IntPtr]::Zero,[IntPtr]::Zero)
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$true})
 [void][GameOverlayCheck]::SetForegroundWindow($main)
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $main -and [GameOverlayCheck]::IsWindowVisible($lyrics) -and [GameOverlayCheck]::IsWindowVisible($track)} 'Player foreground did not restore both components'
 [void][GameOverlayCheck]::SetForegroundWindow([GameOverlayCheck]::Sink)
 [GameOverlayCheck]::Move($hoverPoint.x,$hoverPoint.y)
 Wait-For {-not [GameOverlayCheck]::IsWindowVisible($lyrics)} 'Lyrics did not hide again'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 Require ([GameOverlayCheck]::IsWindowVisible($lyrics) -and [GameOverlayCheck]::IsWindowVisible($track)) 'Editing retained hover-hidden components'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 Wait-For {-not [GameOverlayCheck]::IsWindowVisible($lyrics)} 'Locking did not resume hover hiding'
 [void](Bridge 'gameOverlay.setHideOnHover' @{hideOnHover=$false})
 Wait-For {[GameOverlayCheck]::IsWindowVisible($lyrics) -and [GameOverlayCheck]::IsWindowVisible($track)} 'Disabling hover hiding did not restore components'
 foreach($widget in @($lyrics,$track)){$bounds=$hoverBounds["$widget"];[void][GameOverlayCheck]::SetWindowPos($widget,[IntPtr](-1),$bounds.left,$bounds.top,0,0,0x11)}
 [void][GameOverlayCheck]::SetWindowPos([GameOverlayCheck]::Sink,[IntPtr](-1),$sinkBounds.left,$sinkBounds.top,$sinkBounds.right-$sinkBounds.left,$sinkBounds.bottom-$sinkBounds.top,0x10)
 Write-Output 'PASS: cursor visibility hides/restores both components regardless of position, unchanged selection/focus, player foreground, editing and option-off restoration'
 if($HoverOnly){
  [void](Bridge 'gameOverlay.setHideOnHover' @{hideOnHover=$true})
  Stop-TestPlayer
  $app=Start-Process -FilePath $hostExe -ArgumentList $arguments -WindowStyle Hidden -PassThru
  Wait-For {try{(State).hideOnHover}catch{$false}} 'Hover preference did not survive player restart'
  Write-Output 'PASS: hover preference persists across player restart'
  return
 }
 $focusSettings=Bridge 'shortcuts.getState'
 $focusBindings=$focusSettings.bindings | ConvertTo-Json -Depth 5 | ConvertFrom-Json
 $focusBindings.overlayEditing=[pscustomobject]@{key=133;modifiers=7}
 [void](Bridge 'shortcuts.setSettings' @{global=$true;bindings=$focusBindings})
 Press-TestShortcut 134
 Require ([GameOverlayCheck]::GetForegroundWindow()-eq[GameOverlayCheck]::Sink -and !(State).editing) 'Retired panel shortcut still changes focus or editing'
 Press-TestShortcut 133
 Wait-For {(State).editing -and [GameOverlayCheck]::GetForegroundWindow() -eq $lyrics} 'Editing did not take actual foreground focus'
 foreach($widget in @($lyrics,$track)){Require (-not (Has-Style $widget 0x08000000) -and -not (Has-Style $widget 0x20)) 'Editing widget still refuses activation or mouse input'}
 Wait-For {Evaluate 'document.hasFocus()' 'lyrics'} 'CEF lyrics did not receive editing focus'
 Wait-For {Evaluate "!!document.querySelector('.overlay-widget-header>span')" 'track'} 'Track editing header unavailable'
 Drag-Title $track 'track' 'Editing did not allow a real drag of the other component'
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $track} 'Clicking track did not switch editing focus'
 Press-TestShortcut 133 $false $track
 Wait-For {-not (State).editing -and [GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Locking did not return focus to the original game'
 foreach($widget in @($lyrics,$track)){Require (Has-Style $widget 0x08000020) 'Locked widget did not restore noactivate and click-through'}
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Finishing editing lost the original game return target'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$false})
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $track} 'Hiding the focused editor did not activate the remaining component'
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$false})
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Hiding all components stranded editor focus'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$true})
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$true})
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 Wait-For {Evaluate "!!document.querySelector('button[title=完成编辑]')" 'lyrics'} 'Complete editing button unavailable'
 [void](Evaluate "document.querySelector('button[title=完成编辑]').focus();true" 'lyrics')
 Require ([GameOverlayCheck]::GetForegroundWindow() -eq $lyrics) 'Keyboard target is not the foreground editor; no keys sent'
 try {[GameOverlayCheck]::Key(32,$true)} finally {[GameOverlayCheck]::Key(32,$false)}
 Wait-For {-not (State).editing -and [GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'CEF keyboard input did not complete editing and restore game focus'
 # Keep the cursor outside lyrics when asserting that ordinary display does not activate hover editing.
 [GameOverlayCheck]::Move(0,0)
 foreach($ending in @('lock','hide')){
  [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
  Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $lyrics} 'Editing did not reacquire focus'
  switch($ending){
   'lock' {
    [void][GameOverlayCheck]::SetForegroundWindow($main)
    Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $main} 'Could not simulate switching away from editor'
    [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
    Require ([GameOverlayCheck]::GetForegroundWindow() -eq $main) 'Locking stole focus after the user switched away'
    [void][GameOverlayCheck]::SetForegroundWindow([GameOverlayCheck]::Sink)
   }
   'hide' {[void](Bridge 'gameOverlay.setVisible' @{visible=$false})}
   'disable' {[void](Bridge 'gameOverlay.setEnabled' @{enabled=$false})}
  }
  if($ending -ne 'lock'){Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Ending editing did not restore game focus'}
  if($ending -eq 'disable'){[void](Bridge 'gameOverlay.setEnabled' @{enabled=$true})}
  [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
  Require ([GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink) 'Ordinary component display stole focus'
 }
 [void](Bridge 'shortcuts.setSettings' @{global=$focusSettings.global;bindings=$focusSettings.bindings})
 Write-Output 'PASS: editing foreground/CEF/keyboard focus, real track drag, key-down/up passthrough, individual visibility, lock/hide return, user focus preservation and locked noactivate'
 if($FocusOnly){
  [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
  Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq $lyrics} 'Exit test editor did not take focus'
  Stop-TestPlayer
  Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Player exit stranded editor focus'
  Write-Output 'PASS: normal player exit returns editing focus to the original game'
  return
 }
 foreach($page in @('lyrics','track')){
  foreach($command in @('library.search','library.login','window.close','updates.getState','gameOverlay.setMinecraftCompatibility',
    'gameOverlay.setWindowCompatibility','gameOverlay.listCompatibilityWindows','gameOverlay.applyWindowCompatibility','gameOverlay.restoreWindowCompatibility','gameOverlay.setHideOnHover')){
   $reply=Bridge $command @{} $page -AllowFailure
   Require (-not $reply.ok -and $reply.code -eq 403) "Widget may invoke forbidden command $command"
  }
 }
 Require (Evaluate "(()=>{const e=document.querySelector('.game-overlay'),s=e&&getComputedStyle(e);return !!s&&s.backgroundColor==='rgba(0, 0, 0, 0)'&&s.boxShadow==='none'&&['Top','Right','Bottom','Left'].every(side=>s['border'+side+'Width']==='0px'||s['border'+side+'Color']==='rgba(0, 0, 0, 0)')})()" 'lyrics') 'Locked lyrics retained a background, border or shadow'
 Wait-TrackFit
 $compactHeight=Track-Height
 Require ($compactHeight -lt 150) 'Locked track widget retained empty vertical space'
 [void](Evaluate "window.__originalPlayerStyle=localStorage.getItem('musxi-lyrics-player-style');true" 'lyrics')
 foreach($style in @('vinyl','square','none')){
  [void](Evaluate "localStorage.setItem('musxi-lyrics-player-style','$style');true" 'lyrics')
  $selector=switch($style){'vinyl'{'.overlay-track-details>.vinyl-cover'} 'square'{'.overlay-track-details>.cover'} 'none'{'.overlay-track-details:not(:has(.cover))'}}
  Wait-For {Evaluate "!!document.querySelector('$selector')" 'track'} "Track cover did not follow $style across windows"
  Wait-TrackFit
  Require ((Track-Height) -lt 150) 'Cover style left empty vertical space'
  Require (Evaluate "(()=>{const e=document.querySelector('.overlay-track-title');return e.getBoundingClientRect().width>0&&!!e.querySelector('h1')})()" 'track') 'Hiding the cover hid the song information'
 }
 [void](Evaluate "(()=>{const value=window.__originalPlayerStyle;if(value===null)localStorage.removeItem('musxi-lyrics-player-style');else localStorage.setItem('musxi-lyrics-player-style',value);return true})()" 'lyrics')
 Wait-TrackFit
 foreach($page in @('main','lyrics')){
  $reply=Bridge 'gameOverlay.reportTrackHeight' @{height=120} $page -AllowFailure
  Require (-not $reply.ok -and $reply.code -eq 403) 'A different page resized the track widget'
 }
 foreach($params in @(@{height=79},@{height=1001},@{height=120.5},@{height=120;extra=$true})){
  $reply=Bridge 'gameOverlay.reportTrackHeight' $params 'track' -AllowFailure
  Require (-not $reply.ok -and $reply.code -eq 400) 'Invalid track height report was accepted'
 }
 [void](Evaluate "window.__trackOriginalAppearance=Object.fromEntries(['musxi-appearance-mode','musxi-transparent','musxi-transparency','musxi-game-track-transparency'].map(key=>[key,localStorage.getItem(key)]));true" 'track')
 $readTrackAlpha="(()=>{const e=document.querySelector('.game-overlay'),c=document.createElement('canvas');c.width=c.height=1;const x=c.getContext('2d');x.fillStyle=getComputedStyle(e).backgroundColor;x.fillRect(0,0,1,1);return {alpha:x.getImageData(0,0,1,1).data[3],opacity:getComputedStyle(e).opacity,text:getComputedStyle(document.querySelector('.overlay-track-details h1')).opacity}})()"
 foreach($level in @(0,50,100)){
  [void](Evaluate "localStorage.setItem('musxi-game-track-transparency','$level');window.dispatchEvent(new Event('musxi-game-track-transparency'));true")
  Wait-For { $alpha=Evaluate $readTrackAlpha 'track';[Math]::Abs($alpha.alpha-255*(1-$level/100))-le1 -and $alpha.opacity-eq'1' -and $alpha.text-eq'1' } 'Track independent transparency did not sync across windows or faded foreground content'
 }
 [void](Evaluate "localStorage.setItem('musxi-game-track-transparency','64');true")
 Wait-For { [Math]::Abs((Evaluate $readTrackAlpha 'track').alpha-255*.36)-le1 } 'Track transparency did not reach independent saved value'
 foreach($mode in @('light','dark')){foreach($transparent in @($false,$true)){foreach($level in @(0,50,100)){
  $flag=if($transparent){'true'}else{'false'}
  [void](Evaluate "localStorage.setItem('musxi-appearance-mode','$mode');localStorage.setItem('musxi-transparent','$flag');localStorage.setItem('musxi-transparency','$level');window.dispatchEvent(new StorageEvent('storage'));true" 'track')
  $alpha=Evaluate $readTrackAlpha 'track'
  Require ([Math]::Abs($alpha.alpha-255*.36)-le1 -and $alpha.opacity-eq'1' -and $alpha.text-eq'1') 'Global appearance changed independent track transparency or faded foreground content'
 }}}
 [void](Evaluate "(()=>{for(const [key,value] of Object.entries(window.__trackOriginalAppearance)){if(value===null)localStorage.removeItem(key);else localStorage.setItem(key,value)}window.dispatchEvent(new StorageEvent('storage'));return true})()" 'track')
 Require ((Hit-Widget $lyrics 'lyrics' '.overlay-lyrics-list,.overlay-message') -eq -1) 'Locked lyrics did not pass native hit testing through'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 Wait-TrackFit
 Require ([Math]::Abs((Track-Height)-$compactHeight)-le1) 'Editing changed the track window height'
 Wait-For {!(Has-Style $lyrics 0x20) -and !(Has-Style $track 0x20)} 'Editing did not release click-through'
 Wait-For {Evaluate "!!document.querySelector('.overlay-widget-header>span')" 'lyrics'} 'Editing header did not render'
 Require (Evaluate "getComputedStyle(document.querySelector('.game-overlay')).backgroundColor!=='rgba(0, 0, 0, 0)'" 'lyrics') 'Editing lyrics have no visible background'
 Wait-For {(Hit-Widget $lyrics 'lyrics' '.overlay-widget-header>span') -eq 2} 'Editing title is not a native drag region'
 Require ((Hit-Widget $lyrics 'lyrics' '.overlay-widget-header button') -eq 1) 'Editing button became draggable'
 Require ((Hit-Widget $lyrics 'lyrics' '.overlay-lyrics-list,.overlay-message') -eq 1) 'Lyrics content became draggable instead of scrollable'
 Wait-For {(Hit-Widget $track 'track' '.overlay-widget-header>span') -eq 2} 'Track widget title is not a native drag region'
 Require ((Hit-Widget $track 'track' '.overlay-widget-header button') -eq 1) 'Track widget button became draggable'
 Require ((Hit-Widget $track 'track' '.overlay-track-content') -eq 2) 'Track widget content lost its edit drag region'
 [void](Evaluate "localStorage.setItem('musxi-game-track-transparency','100');true")
 Wait-For { (Evaluate $readTrackAlpha 'track').alpha -eq 0 } 'Fully transparent track did not retain a zero-alpha background in editing'
 $trackBeforeTransparentDrag=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($track,[ref]$trackBeforeTransparentDrag)
 Require ([GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),$monitor.work.left+32,$monitor.work.top+32,0,0,0x11)) 'Could not place track for transparent drag checks'
 Drag-Title $track 'track' 'Fully transparent track title did not move with a real mouse drag'
 Drag-Title $track 'track' 'Fully transparent track edge did not move with a real mouse drag' -Edge
 Require ((Hit-Widget $track 'track' '.overlay-widget-header button') -eq 1) 'Transparent editing made the track buttons draggable'
 Require ((Evaluate "localStorage.getItem('musxi-game-track-transparency')" 'track') -eq '100') 'Transparent dragging changed the saved transparency'
 Require ([GameOverlayCheck]::SetWindowPos($track,[IntPtr](-1),$trackBeforeTransparentDrag.left,$trackBeforeTransparentDrag.top,0,0,0x11)) 'Could not restore track position after transparent dragging'
 $scale=[GameOverlayCheck]::GetDpiForWindow($lyrics)/96.0
 $width=[Math]::Min([int](520*$scale),$monitor.work.right-$monitor.work.left-32)
 $height=[Math]::Min([int](300*$scale),$monitor.work.bottom-$monitor.work.top-32)
 Require ([GameOverlayCheck]::SetWindowPos($lyrics,[IntPtr]::Zero,$monitor.work.left+16,$monitor.work.top+16,$width,$height,0x14)) 'Widget resize failed'
 [void][GameOverlayCheck]::PostMessage($lyrics,0x232,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {
  $placement=(Get-Content -LiteralPath (Join-Path $profile 'game-overlay.json') -Raw | ConvertFrom-Json).lyrics
  $placement.width -eq [Math]::Round($width/$scale) -and $placement.height -eq [Math]::Round($height/$scale)
 } 'Widget placement was not persisted in logical pixels'
 $beforeDrag=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$beforeDrag)
 Require ([GameOverlayCheck]::Hit($lyrics,$beforeDrag.left+[int](10*$scale),[int](($beforeDrag.top+$beforeDrag.bottom)/2)) -eq 2) 'Editing padding is not draggable'
 Drag-Title $lyrics 'lyrics' 'A real mouse drag did not move the empty lyric title by the requested delta'
 $fixture=& $node $driver $port 'lyrics' '--lyric-fixture'
 Require ($LASTEXITCODE -eq 0) 'Could not install the isolated React lyric fixture'
 # Check scroll layout before the optional perspective transform changes visual bounds.
 Wait-For {Evaluate "(()=>{const c=document.querySelector('.overlay-lyrics-list'),r=c?.querySelector('[aria-current=true]');if(!r)return false;return c.children.length===200&&c.scrollTop>1000&&Math.abs(r.offsetTop-(c.clientHeight-r.clientHeight)/2-c.scrollTop)<2})()" 'lyrics'} 'Loaded lyrics did not scroll to the current line'
 $observers=Evaluate 'window.__overlayFixtureProbe.lyricObservers' 'lyrics'
 Start-Sleep -Milliseconds 4300
 Require ((Evaluate 'window.__overlayFixtureProbe.lyricObservers' 'lyrics') -eq $observers) 'Identical lyric polling restarted scroll observation'
 $measureFrames="new Promise(resolve=>{let first,frames=0;const frame=time=>{if(first===undefined)first=time;if(time-first>=750)resolve(Math.round(frames*1000/(time-first)));else{frames++;requestAnimationFrame(frame)}};requestAnimationFrame(frame)})"
 $mainFrames=Evaluate $measureFrames
 $lyricFrames=Evaluate $measureFrames 'lyrics'
 Require ($mainFrames -gt 0 -and $lyricFrames -gt 0) 'Visible lyric renderer stopped delivering animation frames'
 Write-Output "Observed animation frames per second: player=$mainFrames, game lyrics=$lyricFrames (runtime load dependent)"
 [void](Evaluate "(()=>{window.__overlayOriginalDisplay=localStorage.getItem('musxi-lyrics-display');localStorage.setItem('musxi-lyrics-display',JSON.stringify({colorMode:'theme'}));window.dispatchEvent(new Event('musxi-lyrics-display'));return true})()" 'lyrics')
 Start-Sleep -Milliseconds 350
 $themeColor=Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-list [aria-current=true]')).color" 'lyrics'
 $lyricMask=Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-viewport')).maskImage" 'lyrics'
 Require ($lyricMask -like 'linear-gradient*') 'Editing lyrics have no edge fade mask'
 [void](Evaluate "(()=>{window.__originalGameLyricsStyle=localStorage.getItem('musxi-game-lyrics-style');localStorage.setItem('musxi-game-lyrics-style','perspective');return true})()" 'track')
 Wait-For {Evaluate "document.querySelector('.overlay-lyrics-viewport').dataset.style==='perspective'&&getComputedStyle(document.querySelector('.overlay-lyrics-list')).transform.startsWith('matrix3d')" 'lyrics'} 'Lyric style did not synchronize between windows'
 [void](Evaluate "(()=>{localStorage.setItem('musxi-lyrics-display',JSON.stringify({colorMode:'cover'}));window.dispatchEvent(new Event('musxi-lyrics-display'));return true})()" 'lyrics')
 Wait-For {(Evaluate "document.querySelector('.overlay-lyrics-viewport').style.getPropertyValue('--vinyl-color')" 'lyrics') -eq 'rgb(225, 70, 70)'} 'Overlay did not sample the configured cover color'
 Require ((Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-list li')).color!==getComputedStyle(document.querySelector('.overlay-lyrics-list [aria-current=true]')).color" 'lyrics')) 'Cover-colored lyrics lost the active line hierarchy'
 [void](Evaluate "(()=>{localStorage.setItem('musxi-lyrics-display',JSON.stringify({colorMode:'theme'}));window.dispatchEvent(new Event('musxi-lyrics-display'));return true})()" 'lyrics')
 Wait-For {(Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-list [aria-current=true]')).color" 'lyrics') -eq $themeColor} 'Theme setting retained the previous cover color'
 $beforeWheel=Evaluate "document.querySelector('.overlay-lyrics-list').scrollTop" 'lyrics'
 $wheelPoint=Widget-Point $lyrics 'lyrics' '.overlay-lyrics-list'
 $wheelPosition=([long]($wheelPoint.y -band 0xffff) -shl 16) -bor ($wheelPoint.x -band 0xffff)
 Require ([GameOverlayCheck]::PostMessage($lyrics,0x20a,[IntPtr](120 -shl 16),[IntPtr]$wheelPosition)) 'Native lyric wheel message was not delivered'
 Wait-For {(Evaluate "document.querySelector('.overlay-lyrics-list').scrollTop" 'lyrics') -lt $beforeWheel-1} 'Native lyric scrolling did not move the list'
 try {Wait-For {(Hit-Widget $lyrics 'lyrics' '.overlay-widget-header>span') -eq 2} 'Scrolled loaded lyrics covered the native header drag region'} catch {
  $diagnostic=Evaluate "(()=>{const data={supportsNone:CSS.supports('-webkit-app-region','none')};for(const selector of ['.game-overlay','.overlay-widget-header','.overlay-widget-header>span','.overlay-widget-header>div','.overlay-lyrics-viewport','.overlay-lyrics-list','.overlay-lyrics-list li','.overlay-lyrics-list li>span','.overlay-lyrics-list li[aria-current=true]']){const e=document.querySelector(selector);if(!e){data[selector]=null;continue}const r=e.getBoundingClientRect(),s=getComputedStyle(e);data[selector]={x:r.x,y:r.y,width:r.width,height:r.height,region:s.getPropertyValue('-webkit-app-region'),scrollTop:e.scrollTop}}return data})()" 'lyrics'
  [pscustomobject]@{HeaderHit=(Hit-Widget $lyrics 'lyrics' '.overlay-widget-header>span');BodyHit=(Hit-Widget $lyrics 'lyrics' '.overlay-lyrics-list');Layout=$diagnostic}|ConvertTo-Json -Depth 5|Write-Output
  throw
 }
 Require ((Hit-Widget $lyrics 'lyrics' '.overlay-widget-header button') -eq 1) 'Scrolled lyric editor button became draggable'
 Require ((Hit-Widget $lyrics 'lyrics' '.overlay-lyrics-list') -eq 1) 'Scrolled lyrics lost their normal client hit region'
 Drag-Title $lyrics 'lyrics' 'A real mouse drag did not move the scrolled lyric title by the requested delta'
 [void](Evaluate "(()=>{window.__manualOverlayList=document.querySelector('.overlay-lyrics-list');window.__manualOverlayScroll=window.__manualOverlayList.scrollTop;return true})()" 'lyrics')
 [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$false})
 Wait-For {(Evaluate 'window.__overlayFixtureProbe.players' 'lyrics') -eq 0} 'Hiding manually scrolled lyrics retained the player subscription'
 [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$true})
 Wait-For {(Evaluate 'window.__overlayFixtureProbe.players' 'lyrics') -eq 1} 'Manually scrolled lyrics did not reconnect'
 Require (Evaluate "document.querySelector('.overlay-lyrics-list')===window.__manualOverlayList&&Math.abs(window.__manualOverlayList.scrollTop-window.__manualOverlayScroll)<2" 'lyrics') 'Hiding and showing reset manual lyric browsing'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 Wait-For {(Has-Style $lyrics 0x20) -and (Has-Style $track 0x20)} 'Leaving editing did not restore click-through'
 Wait-TrackFit
 Require ([Math]::Abs((Track-Height)-$compactHeight)-le1) 'Leaving editing retained empty track space'
 Require ((Evaluate $readTrackAlpha 'track').alpha -eq 0 -and (Evaluate "localStorage.getItem('musxi-game-track-transparency')" 'track') -eq '100') 'Leaving editing did not preserve true transparent background and preference'
 Require ((Hit-Widget $track 'track' '.overlay-track-content') -eq -1) 'Leaving transparent editing did not restore track click-through'
 [void](Evaluate "(()=>{const value=window.__trackOriginalAppearance['musxi-game-track-transparency'];if(value===null)localStorage.removeItem('musxi-game-track-transparency');else localStorage.setItem('musxi-game-track-transparency',value);window.dispatchEvent(new Event('musxi-game-track-transparency'));return true})()" 'track')
 Require ((Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-viewport')).maskImage" 'lyrics') -eq $lyricMask) 'Locked lyrics lost the edge fade mask'
 Require ((Evaluate "document.querySelector('.overlay-lyrics-viewport').dataset.style==='perspective'" 'lyrics')) 'Locking lyrics lost their perspective style'
 [void](Evaluate "(()=>{localStorage.setItem('musxi-game-lyrics-style','flat');return true})()" 'track')
 Wait-For {Evaluate "getComputedStyle(document.querySelector('.overlay-lyrics-list')).transform==='none'" 'lyrics'} 'Flat style retained the perspective transform'
 [void](Evaluate "(()=>{const value=window.__originalGameLyricsStyle;if(value===null)localStorage.removeItem('musxi-game-lyrics-style');else localStorage.setItem('musxi-game-lyrics-style',value);return true})()" 'track')
 [void](Evaluate "(()=>{const value=window.__overlayOriginalDisplay;if(value===null)localStorage.removeItem('musxi-lyrics-display');else localStorage.setItem('musxi-lyrics-display',value);window.dispatchEvent(new Event('musxi-lyrics-display'));return true})()" 'lyrics')
 [void][GameOverlayCheck]::PostMessage($lyrics,0x10,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For {![GameOverlayCheck]::IsWindowVisible($lyrics)} 'Closing lyrics did not hide it'
 Require ([GameOverlayCheck]::IsWindowVisible($track) -and -not (State).lyricsVisible) 'Closing lyrics affected track widget or failed to update state'
 Wait-For {(Evaluate 'window.__overlayFixtureProbe.players' 'lyrics') -eq 0} 'Hidden lyrics retained the player subscription'
 $hiddenCalls=Evaluate 'window.__overlayFixtureProbe.calls["library.lyrics"]' 'lyrics'
 Start-Sleep -Milliseconds 4500
 Require ((Evaluate 'window.__overlayFixtureProbe.calls["library.lyrics"]' 'lyrics') -eq $hiddenCalls) 'Hidden lyrics continued polling'
 [void](Bridge 'gameOverlay.setLyricsVisible' @{lyricsVisible=$true})
 Wait-For {(Evaluate 'window.__overlayFixtureProbe.players===1' 'lyrics') -and ((Evaluate 'window.__overlayFixtureProbe.calls["library.lyrics"]' 'lyrics') -gt $hiddenCalls)} 'Showing lyrics did not immediately reconnect and refresh'
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$false})
 Require ([GameOverlayCheck]::IsWindowVisible($lyrics) -and -not [GameOverlayCheck]::IsWindowVisible($track)) 'Independent track visibility failed'
 [void](Bridge 'gameOverlay.setTrackVisible' @{trackVisible=$true})
 $settings=Bridge 'shortcuts.getState'
 # Keep hover editing from changing the input target during shortcut delivery checks.
 [GameOverlayCheck]::Move(0,0)
 foreach($entry in @(@('overlayVisibility',132),@('overlayEditing',133))){$settings.bindings.($entry[0])=[pscustomobject]@{key=$entry[1];modifiers=7}}
 $settings.bindings.volumeUp=[pscustomobject]@{key=131;modifiers=7}
 $registered=Bridge 'shortcuts.setSettings' @{global=$true;bindings=$settings.bindings}
 Require ($registered.failed.Count -eq 0) 'Overlay test shortcuts failed to register'
 Check-OverlayKeys $true
 $beforeOrder=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$beforeOrder)
 [void][GameOverlayCheck]::SetForegroundWindow([GameOverlayCheck]::Sink)
 Wait-For {[GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink} 'Could not activate the simulated topmost game'
 Wait-For {[GameOverlayCheck]::Above($lyrics,[GameOverlayCheck]::Sink) -and [GameOverlayCheck]::Above($track,[GameOverlayCheck]::Sink)} 'Topmost foreground game covered the overlay widgets'
 # Fullscreen games can restack themselves after activation without another focus change.
 Require ([GameOverlayCheck]::SetWindowPos([GameOverlayCheck]::Sink,[IntPtr](-1),0,0,0,0,0x13)) 'Could not simulate a delayed game restack'
 Wait-For {[GameOverlayCheck]::Above($lyrics,[GameOverlayCheck]::Sink) -and [GameOverlayCheck]::Above($track,[GameOverlayCheck]::Sink)} 'Delayed game restack covered the overlay widgets'
 $afterOrder=[GameOverlayCheck+Rect]::new();[void][GameOverlayCheck]::GetWindowRect($lyrics,[ref]$afterOrder)
 Require ($beforeOrder.left-eq$afterOrder.left -and $beforeOrder.top-eq$afterOrder.top -and $beforeOrder.right-eq$afterOrder.right -and $beforeOrder.bottom-eq$afterOrder.bottom) 'Restoring overlay order changed its bounds'
 Require ([GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink) 'Restoring overlay order stole game focus'
 $volume=(Bridge 'player.getState').volumePercent
 [void](Bridge 'player.setVolume' @{volumePercent=50})
 Press-TestShortcut 131
 Wait-For {(Bridge 'player.getState').volumePercent -eq 55} 'Playback shortcut did not trigger exactly once while passing the key through'
 [void](Bridge 'player.setVolume' @{volumePercent=$volume})
 foreach($step in @(
  @{index=10;field='visible';expected=$false},@{index=10;field='visible';expected=$true},
  @{index=11;field='editing';expected=$true},@{index=11;field='editing';expected=$false}
 )){
  $index=$step.index
  $key=132+$index-10
  if($index -eq 11 -and -not $step.expected){Press-TestShortcut $key $false $lyrics}
  else{Press-TestShortcut $key}
  Wait-For {(State).($step.field) -eq $step.expected} "Shortcut did not toggle $($step.field)"
  $state=State
  if($index -eq 10 -or ($index -eq 11 -and -not $step.expected)){Require ([GameOverlayCheck]::GetForegroundWindow() -eq [GameOverlayCheck]::Sink) 'Locked widget shortcut stole foreground focus'}
  switch($index){
   10 {Require ([GameOverlayCheck]::IsWindowVisible($lyrics) -eq $state.visible) 'Visibility shortcut and HWND visibility disagree'}
   11 {
    Require ((Has-Style $lyrics 0x20) -eq (-not $state.editing)) 'Editing shortcut and click-through disagree'
    if($state.editing){Require ([GameOverlayCheck]::GetForegroundWindow() -eq $lyrics) 'Editing shortcut did not take component focus'}
   }
  }
 }
 Press-TestShortcut 134
 Require ([GameOverlayCheck]::GetForegroundWindow()-eq[GameOverlayCheck]::Sink) 'Retired panel key still takes focus'
 Press-TestShortcut 133 $true ([GameOverlayCheck]::Sink) {[void](Bridge 'shortcuts.suspend' @{recording=$true;editing=$false})}
 Require (-not (State).editing) 'Recording failed to cancel the pending editing key release'
 [void](Bridge 'shortcuts.suspend' @{recording=$false;editing=$false})
 [void](Bridge 'shortcuts.suspend' @{recording=$true;editing=$false})
 Press-TestShortcut 132
 Require ((State).visible) 'Recording retained the global listener'
 [void](Bridge 'shortcuts.suspend' @{recording=$false;editing=$false})
 [void](Bridge 'gameOverlay.setEditing' @{editing=$true})
 $disabled=Bridge 'gameOverlay.setEnabled' @{enabled=$false} -AllowFailure
 Require (-not $disabled.ok -and $disabled.code-eq409 -and (State).enabled) 'Always-available widgets could be disabled'
 [void](Bridge 'gameOverlay.setEditing' @{editing=$false})
 [void](Bridge 'gameOverlay.setVisible' @{visible=$false})
 Check-OverlayKeys $true
 $playbackKey=[GameOverlayCheck]::RegisterHotKey($keyProbe,131,0x4007,131)
 if($playbackKey){[void][GameOverlayCheck]::UnregisterHotKey($keyProbe,131)}
 Require ($playbackKey) 'Hiding widgets stopped shortcut passthrough'
 [void](Bridge 'gameOverlay.setVisible' @{visible=$true})
 $minecraft=Start-Process -FilePath $minecraftFixture -WindowStyle Hidden -PassThru
 Wait-For {(Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id) -ne [IntPtr]::Zero} 'Shutdown Java fixture did not open'
 $minecraftWindow=Find-Window 'GLFW30' 'Minecraft compatibility fixture' $minecraft.Id
 [void](Bridge 'gameOverlay.setWindowCompatibility' @{windowCompatibility=$true})
 $id=@((Bridge 'gameOverlay.listCompatibilityWindows')|Where-Object {$_.program-eq'GameWindowFixture.exe'})[0].id
 [void](Bridge 'gameOverlay.applyWindowCompatibility' @{id=$id})
 Require ((State).windowCompatibilityStatus -eq 'applied') 'Manual compatibility did not apply before shutdown'
 Stop-TestPlayer
 Require ($app.ExitCode -eq 0) 'Test player exited with an error'
 [void][GameOverlayCheck]::GetWindowRect($minecraftWindow,[ref]$restored)
 Require ($restored.right-$restored.left-eq600 -and $restored.bottom-$restored.top-eq400) 'Player exit did not restore Minecraft window'
 Write-Output 'PASS: overlay lifecycle, module enable/manual display, independent visibility, transparent lyrics, native drag hit regions/real mouse drag, click-through editing, placement persistence/clamp, retired panel rejection and binding migration, shortcut dispatch/release/restore, and widget command permissions'
} finally {
 try {
  if($mouseHeld){[GameOverlayCheck]::Button($false);$mouseHeld=$false}
 } finally {
  try {Stop-TestPlayer} finally {
  if($minecraft -and -not $minecraft.HasExited){[void]$minecraft.CloseMainWindow();[void]$minecraft.WaitForExit(5000)}
  if(-not $app -or $app.HasExited){
   foreach($name in $saved.Keys){$path=Join-Path $profile $name;if($null -ne $saved[$name]){[IO.File]::WriteAllBytes($path,$saved[$name])}elseif(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}
  }
  if($keyProbe -ne [IntPtr]::Zero){[void][GameOverlayCheck]::DestroyWindow($keyProbe)}
  [GameOverlayCheck]::StopSink()
  if($mouseHeld){[GameOverlayCheck]::Button($false)}
  [void][GameOverlayCheck]::SetCursorPos($originalCursor.x,$originalCursor.y)
  [void][GameOverlayCheck]::SetThreadDpiAwarenessContext($oldDpi)
  }
 }
}
