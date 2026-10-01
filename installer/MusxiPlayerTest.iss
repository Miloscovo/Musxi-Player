#define AppVersion "0.2"
[Setup]
AppId={{E9085FC0-8A35-4EE3-BF87-0FA424B02156}
AppName=Musxi Player 测试版
AppVersion={#AppVersion}
VersionInfoVersion=0.2.0.0
DefaultDirName={localappdata}\Programs\Musxi Player 测试版
DefaultGroupName=Musxi Player 测试版
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist
OutputBaseFilename=MusxiPlayer-Test-0.2-Setup-x64
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\MusxiPlayerTest.exe
CloseApplications=yes
CloseApplicationsFilter=MusxiPlayerTest.exe
RestartApplications=no
DisableProgramGroupPage=yes
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\build\cef-msvc\src\cef\Release\MusxiPlayerWeb.exe"; DestDir: "{app}"; DestName: "MusxiPlayerTest.exe"; Flags: ignoreversion
Source: "..\build\cef-msvc\src\cef\Release\MusxiPlayerWeb.dll"; DestDir: "{app}"; DestName: "MusxiPlayerTest.dll"; Flags: ignoreversion
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
Name: "{group}\Musxi Player 测试版"; Filename: "{app}\MusxiPlayerTest.exe"; Parameters: "--test-app"; WorkingDir: "{app}"
Name: "{autodesktop}\Musxi Player 测试版"; Filename: "{app}\MusxiPlayerTest.exe"; Parameters: "--test-app"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\MusxiPlayerTest.exe"; Parameters: "--test-app"; Description: "Launch Musxi Player 测试版"; Flags: nowait postinstall skipifsilent
