param([string]$BootstrapPath,[string]$IconPath,[string]$WorkRoot,[string]$ApplicationPath)
$ErrorActionPreference='Stop'
$scriptPath=Join-Path $PSScriptRoot '../scripts/set-application-icon.ps1'
$null=New-Item -ItemType Directory -Force $WorkRoot
$output=Join-Path $WorkRoot 'MusxiPlayerWeb.exe'
$originalHash=(Get-FileHash -LiteralPath $BootstrapPath -Algorithm SHA256).Hash
& $scriptPath -BootstrapPath $BootstrapPath -OutputPath $output -IconPath $IconPath
Add-Type @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class ApplicationIconCheck {
 delegate bool TypeCallback(IntPtr module,IntPtr type,IntPtr arg);
 delegate bool NameCallback(IntPtr module,IntPtr type,IntPtr name,IntPtr arg);
 delegate bool LanguageCallback(IntPtr module,IntPtr type,IntPtr name,ushort language,IntPtr arg);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool EnumResourceTypes(IntPtr module,TypeCallback callback,IntPtr arg);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool EnumResourceNames(IntPtr module,IntPtr type,NameCallback callback,IntPtr arg);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool EnumResourceLanguages(IntPtr module,IntPtr type,IntPtr name,LanguageCallback callback,IntPtr arg);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern IntPtr FindResourceEx(IntPtr module,IntPtr type,IntPtr name,ushort language);
 [DllImport("kernel32.dll")] static extern uint SizeofResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll")] static extern IntPtr LoadResource(IntPtr module,IntPtr resource);
 [DllImport("kernel32.dll")] static extern IntPtr LockResource(IntPtr resource);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr LoadImage(IntPtr module,IntPtr name,uint type,int width,int height,uint flags);
 [DllImport("user32.dll")] public static extern bool DestroyIcon(IntPtr icon);
 static string Name(IntPtr value){return (ulong)value.ToInt64()<=65535?value.ToString():Marshal.PtrToStringUni(value);}
 public static string OtherResources(IntPtr module) {
  var records=new SortedDictionary<string,string>();
  EnumResourceTypes(module,(m,type,a)=>{
   if(type==(IntPtr)3 || type==(IntPtr)14)return true;
   return EnumResourceNames(m,type,(n,t,name,b)=>EnumResourceLanguages(n,t,name,(o,u,v,language,c)=>{
    IntPtr resource=FindResourceEx(o,u,v,language);byte[] bytes=new byte[SizeofResource(o,resource)];
    Marshal.Copy(LockResource(LoadResource(o,resource)),bytes,0,bytes.Length);
    records[Name(u)+"/"+Name(v)+"/"+language]=Convert.ToBase64String(bytes);return true;
   },IntPtr.Zero),IntPtr.Zero);
  },IntPtr.Zero);
  if(records.Count==0)throw new InvalidOperationException("No non-icon bootstrap resources checked");
  return String.Join("\n",records);
 }
}
'@
Add-Type -AssemblyName System.Drawing
$pngPath=[IO.Path]::ChangeExtension($IconPath,'.png')
if (Test-Path -LiteralPath $pngPath) {
    $artwork=[Drawing.Bitmap]::new($pngPath)
    try {
        $lightSamples=0;$blueSamples=0
        for ($y=0;$y -lt $artwork.Height;$y+=8) {
            for ($x=0;$x -lt $artwork.Width;$x+=8) {
                $pixel=$artwork.GetPixel($x,$y)
                $radius=$artwork.Width*.2
                if ($x -ge $radius -and $x -lt $artwork.Width-$radius -and $y -ge $radius -and $y -lt $artwork.Height-$radius -and $pixel.A -ne 255) { throw "Artwork became transparent at ($x,$y)." }
                if ($pixel.A -eq 255 -and $pixel.R -gt 140 -and $pixel.G -gt 180 -and $pixel.B -gt 200) { $lightSamples++ }
                if ($pixel.A -eq 255 -and $pixel.B -gt $pixel.R+30 -and $pixel.B -gt 100) { $blueSamples++ }
            }
        }
        if ($lightSamples -lt 100 -or $blueSamples -lt 100) {
            throw 'Icon lost its light artwork or blue background.'
        }
        foreach ($point in @(@(0,0),@(($artwork.Width-1),0),@(0,($artwork.Height-1)),@(($artwork.Width-1),($artwork.Height-1)))) {
            if ($artwork.GetPixel($point[0],$point[1]).A -ne 0) { throw 'Rounded PNG corner is not transparent.' }
        }
    } finally { $artwork.Dispose() }
}
$applications=@($output)
if ($ApplicationPath) { $applications+=$ApplicationPath }
foreach ($application in $applications) {
$source=[MusxiIconResources]::LoadLibraryEx($BootstrapPath,[IntPtr]::Zero,0x22)
$target=[MusxiIconResources]::LoadLibraryEx($application,[IntPtr]::Zero,0x22)
if ($source -eq [IntPtr]::Zero -or $target -eq [IntPtr]::Zero) { throw 'Could not inspect bootstrap resources.' }
try {
    if ([ApplicationIconCheck]::OtherResources($source) -cne [ApplicationIconCheck]::OtherResources($target)) { throw 'Non-icon bootstrap resources changed.' }
    $group=[MusxiIconResources]::Read($target,14,32512)
    $bytes=[IO.File]::ReadAllBytes($IconPath);$count=[BitConverter]::ToUInt16($bytes,4)
    if ($count -ne 9 -or [BitConverter]::ToUInt16($group,4) -ne $count) { throw 'Missing icon sizes.' }
    for ($index=0;$index -lt $count;$index++) {
        $offset=[BitConverter]::ToUInt32($bytes,6+16*$index+12);$length=[BitConverter]::ToUInt32($bytes,6+16*$index+8)
        $expected=[byte[]]::new($length);[Array]::Copy($bytes,[long]$offset,$expected,0,[long]$length)
        $image=[MusxiIconResources]::Read($target,3,$index+1)
        if ([Convert]::ToBase64String($image) -cne [Convert]::ToBase64String($expected)) { throw 'Embedded image differs from supplied ICO.' }
        $size=[int]$bytes[6+16*$index];if ($size -eq 0) { $size=256 }
        $handle=[ApplicationIconCheck]::LoadImage($target,[IntPtr]32512,1,$size,$size,0)
        if ($handle -eq [IntPtr]::Zero) { throw "Windows cannot load the ${size}px icon." }
        $icon=[Drawing.Icon]::FromHandle($handle);$bitmap=$icon.ToBitmap()
        try {
            if ($bitmap.Width -ne $size -or $bitmap.Height -ne $size) { throw 'Unexpected icon dimensions.' }
            $lightPixels=0;$bluePixels=0
            for ($y=0;$y -lt $size;$y++) {
                for ($x=0;$x -lt $size;$x++) {
                    $pixel=$bitmap.GetPixel($x,$y)
                    $radius=$size*.2
                    if ($x -ge $radius -and $x -lt $size-$radius -and $y -ge $radius -and $y -lt $size-$radius -and $pixel.A -ne 255) { throw 'Icon interior contains unwanted transparency.' }
                    if ($pixel.A -eq 255 -and $pixel.R -gt 140 -and $pixel.G -gt 180 -and $pixel.B -gt 200) { $lightPixels++ }
                    if ($pixel.A -eq 255 -and $pixel.B -gt $pixel.R+30 -and $pixel.B -gt 100) { $bluePixels++ }
                }
            }
            if ($lightPixels -eq 0 -or $bluePixels -lt $size*$size*.3) { throw 'Icon lost its light artwork or blue background.' }
            foreach ($point in @(@(0,0),@(($size-1),0),@(0,($size-1)),@(($size-1),($size-1)))) {
                if ($bitmap.GetPixel($point[0],$point[1]).A -ne 0) { throw 'Rounded ICO corner is not transparent.' }
            }
        } finally { $bitmap.Dispose();$icon.Dispose();$null=[ApplicationIconCheck]::DestroyIcon($handle) }
    }
} finally { $null=[MusxiIconResources]::FreeLibrary($source);$null=[MusxiIconResources]::FreeLibrary($target) }
}
$hash=(Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
$invalid=Join-Path $WorkRoot 'invalid.ico';[IO.File]::WriteAllBytes($invalid,[byte[]]@(0,0,1,0,1,0))
$rejected=$false
try { & $scriptPath -BootstrapPath $BootstrapPath -OutputPath $output -IconPath $invalid } catch { $rejected=$true }
if (!$rejected -or (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash -ne $hash) { throw 'Invalid ICO was not safely rejected.' }
& $scriptPath -BootstrapPath $BootstrapPath -OutputPath $output -IconPath $IconPath
if ((Get-FileHash -LiteralPath $BootstrapPath -Algorithm SHA256).Hash -ne $originalHash) { throw 'CEF SDK bootstrap was modified.' }
Write-Output 'Nine native icon sizes, alpha, bootstrap resources, repeat updates and invalid-input handling passed.'
