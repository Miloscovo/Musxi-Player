#define AppVersion "0.3.0"
[Setup]
AppId={{A696A45D-3218-45C2-A565-EAC5642B976E}
AppName=Musxi Player 测试版
AppVersion={#AppVersion}
VersionInfoVersion=0.3.0.0
DefaultDirName={localappdata}\Programs\Musxi Player
DefaultGroupName=Musxi Player
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist
OutputBaseFilename=MusxiPlayer-Setup-{#AppVersion}
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\MusxiPlayer.exe
CloseApplications=yes
CloseApplicationsFilter=MusxiPlayer.exe
RestartApplications=no
DisableProgramGroupPage=yes
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\build\cef-msvc\src\cef\Release\MusxiPlayerWeb.exe"; DestDir: "{app}"; DestName: "MusxiPlayer.exe"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\MusxiPlayerWeb.dll"; DestDir: "{app}"; DestName: "MusxiPlayer.dll"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\*.dll"; DestDir: "{app}"; Flags: ignoreversion; Excludes: "MusxiPlayerWeb.dll,d3dcompiler_47.dll"
Source: "..\build\cef-msvc\src\cef\Release\*.pak"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\icudtl.dat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\v8_context_snapshot.bin"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\vk_swiftshader_icd.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\locales\*"; DestDir: "{app}\locales"; Flags: ignoreversion recursesubdirs
Source: "..\frontend\dist\*"; DestDir: "{app}\ui-vue"; Flags: ignoreversion recursesubdirs
Source: "..\build\cef-license.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\ffmpeg-license.txt"; DestDir: "{app}"; DestName: "LICENSE-FFmpeg.txt"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\release-source-record.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\third_party\LICENSE-json.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\services\bridge.cjs"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\services\package.json"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\services\package-lock.json"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\build\services\node_modules\*"; DestDir: "{app}\services\node_modules"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: ".cache\*,*.log"
Source: "..\build\services\vendor\*"; DestDir: "{app}\services\vendor"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: ".git\*,.env,.env.*,*.log"

[InstallDelete]
Type: files; Name: "{app}\d3dcompiler_47.dll"

[Icons]
Name: "{group}\Musxi Player 测试版"; Filename: "{app}\MusxiPlayer.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Musxi Player 测试版"; Filename: "{app}\MusxiPlayer.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\MusxiPlayer.exe"; Description: "Launch Musxi Player 测试版"; Flags: nowait postinstall skipifsilent
