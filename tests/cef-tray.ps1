param([Parameter(Mandatory=$true)][string]$HostPath)
$ErrorActionPreference='Stop'
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class TrayCheck {
 public delegate bool Callback(IntPtr window,IntPtr arg);
 [DllImport("user32.dll")] public static extern bool EnumWindows(Callback callback,IntPtr arg);
 [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent,Callback callback,IntPtr arg);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window,out uint pid);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr window,StringBuilder name,int length);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr window);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
 [DllImport("user32.dll")] public static extern IntPtr GetWindowLongPtr(IntPtr window,int index);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
 [DllImport("shell32.dll")] public static extern int Shell_NotifyIconGetRect(ref IconIdentifier identifier,out Rect rect);
 public struct IconIdentifier {public uint size;public IntPtr window;public uint id;public Guid guid;}
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr window,out Rect rect);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window,out Rect rect);
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point point);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern IntPtr MonitorFromPoint(Point point,uint flags);
 [DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr monitor,ref MonitorInfo info);
 [DllImport("user32.dll")] public static extern int GetWindowRgn(IntPtr window,IntPtr region);
 [DllImport("gdi32.dll")] public static extern IntPtr CreateRectRgn(int left,int top,int right,int bottom);
 [DllImport("gdi32.dll")] public static extern bool PtInRegion(IntPtr region,int x,int y);
 [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr value);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr window);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr window,uint msg,IntPtr wp,IntPtr lp);
 public struct Rect {public int left,top,right,bottom;}
 public struct Point {public int x,y;}
 public struct MonitorInfo {public int size;public Rect monitor,work;public uint flags;}
}
'@
function Class-Name([IntPtr]$window) {
 $name=New-Object Text.StringBuilder 128;[void][TrayCheck]::GetClassName($window,$name,128);return $name.ToString()
}
function Find-TestWindow([string]$class,[bool]$includeHidden=$false) {
 $script:found=[IntPtr]::Zero
 [void][TrayCheck]::EnumWindows({param($window,$unused)
  $owner=[uint32]0;[void][TrayCheck]::GetWindowThreadProcessId($window,[ref]$owner)
  if($owner -eq $app.Id -and (Class-Name $window) -eq $class -and ($includeHidden -or [TrayCheck]::IsWindowVisible($window))) {$script:found=$window}
  return $true
 },[IntPtr]::Zero)
 return $script:found
}
function Wait-For([scriptblock]$condition,[string]$reason) {
 $deadline=[Diagnostics.Stopwatch]::StartNew()
 while(-not (& $condition)) {
  if($app.HasExited -or $deadline.ElapsedMilliseconds -gt 10000) {throw $reason}
  Start-Sleep -Milliseconds 30
 }
}
function Click-At([IntPtr]$target,[int]$x,[int]$y) {
 $point=[IntPtr](($y -shl 16) -bor $x)
 [void][TrayCheck]::PostMessage($target,0x200,[IntPtr]::Zero,$point)
 [void][TrayCheck]::PostMessage($target,0x201,[IntPtr]1,$point)
 [void][TrayCheck]::PostMessage($target,0x202,[IntPtr]::Zero,$point)
}
function Key([int]$key) {
 [void][TrayCheck]::PostMessage($window,0x100,[IntPtr]$key,[IntPtr]1)
 [void][TrayCheck]::PostMessage($window,0x101,[IntPtr]$key,[IntPtr]0x40000001)
}
function Click-Close {
 $rect=New-Object TrayCheck+Rect;[void][TrayCheck]::GetClientRect($window,[ref]$rect)
 Click-At $window ($rect.right-22) 15
 Start-Sleep -Milliseconds 300
}
function Click-Menu([IntPtr]$target,[int]$y) {
 $scale=[TrayCheck]::GetDpiForWindow($target)/96.0
 Click-At $target ([int](80*$scale)) ([int]($y*$scale))
}
function Open-Menu {
 Wait-For { (Find-TestWindow 'MusxiTrayMenu' $true) -eq [IntPtr]::Zero } 'Previous tray menu did not finish closing'
 $point=New-Object TrayCheck+Point;[void][TrayCheck]::GetCursorPos([ref]$point)
 [void][TrayCheck]::PostMessage($window,0x802C,[IntPtr]::Zero,[IntPtr]0x1007B)
 Wait-For { (Find-TestWindow 'MusxiTrayMenu') -ne [IntPtr]::Zero } 'Tray menu did not open'
 $panel=Find-TestWindow 'MusxiTrayMenu';$script:content=[IntPtr]::Zero
 $bounds=New-Object TrayCheck+Rect;[void][TrayCheck]::GetWindowRect($panel,[ref]$bounds)
 $scale=[TrayCheck]::GetDpiForWindow($panel)/96.0
 if($bounds.bottom -gt $point.y -or ($bounds.right-$bounds.left) -ne [int](208*$scale) -or
   ($bounds.bottom-$bounds.top) -ne [int](156*$scale)) {throw 'Tray menu has a backing-window margin or is below the pointer'}
 $region=[TrayCheck]::CreateRectRgn(0,0,0,0)
 try {
  $kind=[TrayCheck]::GetWindowRgn($panel,$region);$corner=[TrayCheck]::PtInRegion($region,0,0);$inside=[TrayCheck]::PtInRegion($region,20,20)
  if($kind -eq 0 -or $corner -or -not $inside) {throw "Tray region incorrect: kind=$kind corner=$corner inside=$inside"}
 } finally {[void][TrayCheck]::DeleteObject($region)}
 Wait-For {
  [void][TrayCheck]::EnumChildWindows($panel,{param($child,$unused)
   if((Class-Name $child) -eq 'Chrome_RenderWidgetHostHWND') {$script:content=$child};return $true
  },[IntPtr]::Zero)
  $script:content -ne [IntPtr]::Zero
 } 'Tray menu CEF renderer did not start'
 Start-Sleep -Milliseconds 700
 return $script:content
}
# PowerShell is DPI-unaware; without this, window and cursor coordinates are
# virtualized on scaled displays and the physical-pixel menu checks fail.
[void][TrayCheck]::SetThreadDpiAwarenessContext([IntPtr](-4))
$hostExe=(Resolve-Path -LiteralPath $HostPath).Path
$preferences=Join-Path $env:LOCALAPPDATA 'MusxiPlayer-Test\desktop-lyrics.json'
$savedPreferences=if(Test-Path -LiteralPath $preferences){[IO.File]::ReadAllBytes($preferences)}else{$null}
$mainPreferences=Join-Path $env:LOCALAPPDATA 'MusxiPlayer-Test\window.json'
$savedMainPreferences=if(Test-Path -LiteralPath $mainPreferences){[IO.File]::ReadAllBytes($mainPreferences)}else{$null}
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($preferences)) | Out-Null
[IO.File]::WriteAllText($preferences,'{"version":1,"visible":false,"locked":false,"dual":true,"x":-2147483648,"y":-2147483648,"width":640,"height":200}')
[IO.File]::WriteAllText($mainPreferences,'{"version":1,"x":2147483647,"y":0,"width":2147483647,"height":850,"maximized":false}')
$app=Start-Process -FilePath $hostExe -ArgumentList '--test-app' -WindowStyle Hidden -PassThru
$window=[IntPtr]::Zero
$originalCursor=New-Object TrayCheck+Point;[void][TrayCheck]::GetCursorPos([ref]$originalCursor)
try {
 Wait-For { (Find-TestWindow 'MusxiPlayerTestHost') -ne [IntPtr]::Zero } 'No test window (close an existing test instance before running)'
 $window=Find-TestWindow 'MusxiPlayerTestHost';Start-Sleep -Seconds 2
 [void][TrayCheck]::SetCursorPos(300,300)
 if((Find-TestWindow 'MusxiDesktopLyrics') -ne [IntPtr]::Zero) {
  $content=Open-Menu;Click-Menu $content 60
  Wait-For { (Find-TestWindow 'MusxiDesktopLyrics' $true) -eq [IntPtr]::Zero } 'Existing desktop lyrics did not close'
 }
 $content=Open-Menu;Click-Menu $content 60
 Wait-For { (Find-TestWindow 'MusxiDesktopLyrics') -ne [IntPtr]::Zero } 'Tray desktop open failed'
 $desktop=Find-TestWindow 'MusxiDesktopLyrics'
 $style=[TrayCheck]::GetWindowLongPtr($desktop,-20).ToInt64()
 if(($style -band 0x08080088) -ne 0x08080088){throw 'Desktop layered/tool/topmost/noactivate style missing'}
 $content=Open-Menu;Click-Menu $content 96
 Wait-For { ([TrayCheck]::GetWindowLongPtr($desktop,-20).ToInt64() -band 0x20) -ne 0 } 'Tray desktop lock failed'
 $content=Open-Menu;Click-Menu $content 96
 Wait-For { ([TrayCheck]::GetWindowLongPtr($desktop,-20).ToInt64() -band 0x20) -eq 0 } 'Tray desktop unlock failed'
 $focus=[TrayCheck]::GetForegroundWindow();$dpi=[TrayCheck]::GetDpiForWindow($desktop)/96.0
 [void][TrayCheck]::SetWindowPos($desktop,[IntPtr]::Zero,80,120,[int](480*$dpi),[int](180*$dpi),0x14)
 Start-Sleep -Milliseconds 300
 if([TrayCheck]::GetForegroundWindow() -ne $focus){throw 'Desktop resize stole focus'}
 $icon=New-Object TrayCheck+IconIdentifier
 $icon.size=[Runtime.InteropServices.Marshal]::SizeOf($icon);$icon.window=$window;$icon.id=1
 Wait-For {
  $bounds=New-Object TrayCheck+Rect
  [TrayCheck]::Shell_NotifyIconGetRect([ref]$icon,[ref]$bounds) -eq 0
 } 'Tray icon is missing before the first minimize'
 if([TrayCheck]::IsIconic($window)){throw 'Startup unexpectedly minimized the main window'}
 Click-Close;Key 27;Start-Sleep -Milliseconds 200
 if(-not [TrayCheck]::IsWindowVisible($window)){throw 'Cancel hid the window'}
 Click-Close;Key 13
 Wait-For { -not [TrayCheck]::IsWindowVisible($window) } 'Shadcn minimize did not hide the window'
 Start-Sleep -Milliseconds 500
 if($app.HasExited){throw 'Tray minimize terminated the application'}
 if(-not [TrayCheck]::IsWindowVisible($desktop)){throw 'Main hide also hid desktop lyrics'}
 $monitor=New-Object TrayCheck+MonitorInfo;$monitor.size=[Runtime.InteropServices.Marshal]::SizeOf($monitor)
 [void][TrayCheck]::GetMonitorInfo([TrayCheck]::MonitorFromPoint($originalCursor,2),[ref]$monitor)
 [void][TrayCheck]::SetCursorPos(($monitor.work.left+300),($monitor.work.top+300))
 $content=Open-Menu
 if([TrayCheck]::IsWindowVisible($window)){throw 'Opening the tray menu restored the main window'}
 Click-Menu $content 24
 Wait-For { [TrayCheck]::IsWindowVisible($window) } 'Shadcn tray show command failed'
 Wait-For { (Find-TestWindow 'MusxiTrayMenu' $true) -eq [IntPtr]::Zero } 'Tray menu was not dismissed'
 $content=Open-Menu;Click-Menu $content 24
 Wait-For { -not [TrayCheck]::IsWindowVisible($window) } 'Shadcn tray hide command failed'
 Wait-For { (Find-TestWindow 'MusxiTrayMenu' $true) -eq [IntPtr]::Zero } 'Tray menu was not destroyed'
 [void][TrayCheck]::PostMessage($window,0x802C,[IntPtr]::Zero,[IntPtr]0x10203)
 Wait-For { [TrayCheck]::IsWindowVisible($window) } 'Tray double click did not restore'
 [void][TrayCheck]::PostMessage($window,0x112,[IntPtr]0xF020,[IntPtr]::Zero)
 Wait-For { [TrayCheck]::IsIconic($window) } 'Normal minimize no longer uses taskbar'
 [void][TrayCheck]::PostMessage($window,0x802C,[IntPtr]::Zero,[IntPtr]0x10203)
 Wait-For { -not [TrayCheck]::IsIconic($window) } 'Tray restore left window minimized'
 [void][TrayCheck]::PostMessage($desktop,0x10,[IntPtr]::Zero,[IntPtr]::Zero)
 Wait-For { (Find-TestWindow 'MusxiDesktopLyrics' $true) -eq [IntPtr]::Zero } 'Desktop-only close failed'
 if($app.HasExited){throw 'Closing desktop terminated main player'}
 $content=Open-Menu;Click-Menu $content 60
 Wait-For { (Find-TestWindow 'MusxiDesktopLyrics') -ne [IntPtr]::Zero } 'Desktop reopen failed'
 $desktop=Find-TestWindow 'MusxiDesktopLyrics'
 $content=Open-Menu;Click-Menu $content 96
 Wait-For { ([TrayCheck]::GetWindowLongPtr($desktop,-20).ToInt64() -band 0x20) -ne 0 } 'Desktop did not lock before exit'
 $content=Open-Menu;Click-Menu $content 132
 if(-not $app.WaitForExit(10000) -or $app.ExitCode -ne 0){throw 'Shadcn tray exit did not complete cleanly'}
 $settings=Get-Content -LiteralPath $preferences -Raw | ConvertFrom-Json
 if(-not $settings.visible -or -not $settings.locked -or $settings.width -ne 480 -or $settings.height -ne 180) {
  throw "Desktop exit did not preserve visibility, lock and DIP size: $($settings | ConvertTo-Json -Compress)"
 }
 # Simulate a removed monitor and extreme coordinates; restoration must clamp.
 $settings.x=[int]::MaxValue;$settings.y=[int]::MaxValue
 [IO.File]::WriteAllText($preferences,($settings | ConvertTo-Json))
 $app=Start-Process -FilePath $hostExe -ArgumentList '--test-app' -WindowStyle Hidden -PassThru
 Wait-For { (Find-TestWindow 'MusxiPlayerTestHost') -ne [IntPtr]::Zero } 'Restart main window failed'
 $window=Find-TestWindow 'MusxiPlayerTestHost'
 Wait-For { (Find-TestWindow 'MusxiDesktopLyrics') -ne [IntPtr]::Zero } 'Desktop preference did not restore'
 $desktop=Find-TestWindow 'MusxiDesktopLyrics';$bounds=New-Object TrayCheck+Rect
 [void][TrayCheck]::GetWindowRect($desktop,[ref]$bounds)
 $point=New-Object TrayCheck+Point;$point.x=$bounds.left;$point.y=$bounds.top
 [void][TrayCheck]::GetMonitorInfo([TrayCheck]::MonitorFromPoint($point,2),[ref]$monitor)
 if($bounds.left -lt $monitor.work.left -or $bounds.top -lt $monitor.work.top -or $bounds.right -gt $monitor.work.right -or $bounds.bottom -gt $monitor.work.bottom) {
  throw 'Restored desktop is outside the work area'
 }
 if(([TrayCheck]::GetWindowLongPtr($desktop,-20).ToInt64() -band 0x20) -eq 0){throw 'Restored desktop did not retain click-through'}
 $content=Open-Menu;Click-Menu $content 132
 if(-not $app.WaitForExit(10000) -or $app.ExitCode -ne 0){throw 'Restarted player exit failed'}
 Write-Output 'PASS: main/tray lifecycle, desktop lock/unlock/independent close/resize, focus, preference restore and offscreen clamp'
} finally {
 [void][TrayCheck]::SetCursorPos($originalCursor.x,$originalCursor.y)
 if(-not $app.HasExited -and $window -ne [IntPtr]::Zero) {
  [void][TrayCheck]::PostMessage($window,0x10,[IntPtr]::Zero,[IntPtr]::Zero)
  [void]$app.WaitForExit(10000)
 }
 if($app.HasExited) {
  if($null -ne $savedPreferences){[IO.File]::WriteAllBytes($preferences,$savedPreferences)}
  elseif(Test-Path -LiteralPath $preferences){Remove-Item -LiteralPath $preferences}
  if($null -ne $savedMainPreferences){[IO.File]::WriteAllBytes($mainPreferences,$savedMainPreferences)}
  elseif(Test-Path -LiteralPath $mainPreferences){Remove-Item -LiteralPath $mainPreferences}
 }
}
