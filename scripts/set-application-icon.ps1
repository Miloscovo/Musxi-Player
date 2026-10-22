param(
    [Parameter(Mandatory=$true)][string]$BootstrapPath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$IconPath
)
$ErrorActionPreference='Stop'
if (-not ('MusxiIconResources' -as [type])) {
Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class MusxiIconResources {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr BeginUpdateResource(string file,bool deleteExisting);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] public static extern bool UpdateResource(IntPtr update,IntPtr type,IntPtr name,ushort language,byte[] data,uint size);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool EndUpdateResource(IntPtr update,bool discard);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr LoadLibraryEx(string file,IntPtr reserved,uint flags);
 [DllImport("kernel32.dll")] public static extern bool FreeLibrary(IntPtr module);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr FindResourceEx(IntPtr module,IntPtr type,IntPtr name,ushort language);
 [DllImport("kernel32.dll",SetLastError=true)] static extern uint SizeofResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr LoadResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr LockResource(IntPtr resource);
 public static byte[] Read(IntPtr module,int type,int name) {
  IntPtr resource=FindResourceEx(module,(IntPtr)type,(IntPtr)name,1033);
  if(resource==IntPtr.Zero)throw new Win32Exception(Marshal.GetLastWin32Error());
  uint size=SizeofResource(module,resource);IntPtr data=LockResource(LoadResource(module,resource));
  if(size==0 || data==IntPtr.Zero)throw new Win32Exception(Marshal.GetLastWin32Error());
  byte[] bytes=new byte[size];Marshal.Copy(data,bytes,0,bytes.Length);return bytes;
 }
}
'@
}
$bootstrap=(Resolve-Path -LiteralPath $BootstrapPath).ProviderPath
$output=[IO.Path]::GetFullPath($OutputPath)
if ([string]::Equals($bootstrap,$output,[StringComparison]::OrdinalIgnoreCase)) { throw 'The CEF SDK bootstrap must not be modified.' }
if ([IO.Path]::GetExtension($output) -ine '.exe') { throw 'Output must be an application EXE.' }
$bytes=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $IconPath).ProviderPath)
$reader=[IO.BinaryReader]::new([IO.MemoryStream]::new($bytes,$false))
$groupStream=[IO.MemoryStream]::new()
$group=[IO.BinaryWriter]::new($groupStream)
$frames=@()
try {
    if ($bytes.Length -lt 6 -or $reader.ReadUInt16() -ne 0 -or $reader.ReadUInt16() -ne 1) { throw 'Invalid ICO header.' }
    $count=$reader.ReadUInt16()
    if ($count -lt 1 -or $count -gt 64 -or $bytes.Length -lt 6+16*$count) { throw 'Invalid ICO directory.' }
    $group.Write([uint16]0);$group.Write([uint16]1);$group.Write([uint16]$count)
    for ($index=0;$index -lt $count;$index++) {
        $entry=$reader.ReadBytes(12);$offset=$reader.ReadUInt32();$length=[BitConverter]::ToUInt32($entry,8)
        if ($entry[3] -ne 0 -or $length -eq 0 -or $offset -lt 6+16*$count -or [long]$offset+$length -gt $bytes.Length) { throw 'Invalid ICO image bounds.' }
        $image=[byte[]]::new($length);[Array]::Copy($bytes,[long]$offset,$image,0,[long]$length)
        $frames+=,@{data=$image;id=$index+1}
        $group.Write($entry);$group.Write([uint16]($index+1))
    }
    $group.Flush();$groupBytes=$groupStream.ToArray()
} finally { $reader.Dispose();$group.Dispose();$groupStream.Dispose() }
# shortcut: the pinned CEF bootstrap uses one en-US IDI_APPLICATION group; check this layout when updating the SDK.
$module=[MusxiIconResources]::LoadLibraryEx($bootstrap,[IntPtr]::Zero,0x22)
if ($module -eq [IntPtr]::Zero) { throw [ComponentModel.Win32Exception]::new() }
try { $null=[MusxiIconResources]::Read($module,14,32512) } finally { $null=[MusxiIconResources]::FreeLibrary($module) }
$null=New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($output))
$temporary=$output+'.icon-tmp'
$update=[IntPtr]::Zero
try {
    Copy-Item -LiteralPath $bootstrap -Destination $temporary -Force
    $update=[MusxiIconResources]::BeginUpdateResource($temporary,$false)
    if ($update -eq [IntPtr]::Zero) { throw [ComponentModel.Win32Exception]::new() }
    foreach ($frame in $frames) {
        if (![MusxiIconResources]::UpdateResource($update,[IntPtr]3,[IntPtr]$frame.id,1033,$frame.data,$frame.data.Length)) { throw [ComponentModel.Win32Exception]::new() }
    }
    if (![MusxiIconResources]::UpdateResource($update,[IntPtr]14,[IntPtr]32512,1033,$groupBytes,$groupBytes.Length)) { throw [ComponentModel.Win32Exception]::new() }
    $saved=[MusxiIconResources]::EndUpdateResource($update,$false);$update=[IntPtr]::Zero
    if (!$saved) { throw [ComponentModel.Win32Exception]::new() }
    if ([IO.File]::Exists($output)) { [IO.File]::Replace($temporary,$output,[NullString]::Value) }
    else { [IO.File]::Move($temporary,$output) }
} finally {
    if ($update -ne [IntPtr]::Zero) { $null=[MusxiIconResources]::EndUpdateResource($update,$true) }
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
