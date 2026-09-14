#define AppVersion "0.1"
[Setup]
AppId={{A696A45D-3218-45C2-A565-EAC5642B976E}
AppName=Musxi Player
AppVersion={#AppVersion}
VersionInfoVersion=0.1.0.0
DefaultDirName={localappdata}\Programs\Musxi Player
DefaultGroupName=Musxi Player
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist
OutputBaseFilename=MusxiPlayer-0.1-Setup-x64
Compression=lzma2
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
Source: "..\build\MusxiPlayer.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\third_party\LICENSE-json.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\services\bridge.cjs"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\services\package.json"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\services\package-lock.json"; DestDir: "{app}\services"; Flags: ignoreversion
Source: "..\build\services\node_modules\*"; DestDir: "{app}\services\node_modules"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: ".cache\*,*.log"
Source: "..\build\services\vendor\*"; DestDir: "{app}\services\vendor"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: ".git\*,.env,.env.*,*.log"

[Icons]
Name: "{group}\Musxi Player"; Filename: "{app}\MusxiPlayer.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Musxi Player"; Filename: "{app}\MusxiPlayer.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\MusxiPlayer.exe"; Description: "Launch Musxi Player"; Flags: nowait postinstall skipifsilent
