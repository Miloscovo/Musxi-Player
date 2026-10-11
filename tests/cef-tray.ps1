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
 $point=New-Object TrayCheck+Point;[void][TrayCheck]::GetCursorPos([ref]$point)
 [void][TrayCheck]::PostMessage($window,0x802C,[IntPtr]::Zero,[IntPtr]0x1007B)
 Wait-For { (Find-TestWindow 'MusxiTrayMenu') -ne [IntPtr]::Zero } 'Tray menu did not open'
 $panel=Find-TestWindow 'MusxiTrayMenu';$script:content=[IntPtr]::Zero
 $bounds=New-Object TrayCheck+Rect;[void][TrayCheck]::GetWindowRect($panel,[ref]$bounds)
 $scale=[TrayCheck]::GetDpiForWindow($panel)/96.0
 if($bounds.bottom -gt $point.y -or ($bounds.right-$bounds.left) -ne [int](208*$scale) -or
   ($bounds.bottom-$bounds.top) -ne [int](84*$scale)) {throw 'Tray menu has a backing-window margin or is below the pointer'}
 $region=[TrayCheck]::CreateRectRgn(0,0,0,0)
 try {
  if([TrayCheck]::GetWindowRgn($panel,$region) -eq 0 -or [TrayCheck]::PtInRegion($region,0,0) -or
    -not [TrayCheck]::PtInRegion($region,20,20)) {throw 'Tray backing-window corners are visible'}
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
$app=Start-Process -FilePath (Resolve-Path -LiteralPath $HostPath).Path -ArgumentList '--test-app' -WindowStyle Hidden -PassThru
$window=[IntPtr]::Zero
$originalCursor=New-Object TrayCheck+Point;[void][TrayCheck]::GetCursorPos([ref]$originalCursor)
try {
 Wait-For { (Find-TestWindow 'MusxiPlayerTestHost') -ne [IntPtr]::Zero } 'No test window (close an existing test instance before running)'
 $window=Find-TestWindow 'MusxiPlayerTestHost';Start-Sleep -Seconds 2
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
 $content=Open-Menu;Click-Menu $content 60
 if(-not $app.WaitForExit(10000) -or $app.ExitCode -ne 0){throw 'Shadcn tray exit did not complete cleanly'}
 Write-Output 'PASS: shadcn close/cancel/minimize, background lifetime, shadcn tray show/hide/exit, double click and taskbar minimize'
} finally {
 [void][TrayCheck]::SetCursorPos($originalCursor.x,$originalCursor.y)
 if(-not $app.HasExited -and $window -ne [IntPtr]::Zero) {
  [void][TrayCheck]::PostMessage($window,0x10,[IntPtr]::Zero,[IntPtr]::Zero)
  [void]$app.WaitForExit(10000)
 }
}
