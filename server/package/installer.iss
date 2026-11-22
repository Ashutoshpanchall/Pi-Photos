; Inno Setup Script for PiPhotos Server
; Builds PiPhotos-Server-Setup.exe for Windows 10/11 x64

#define MyAppName "PiPhotos Server"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "PiPhotos Team"
#define MyAppURL "https://github.com/Ashutoshpanchall/Pi-Photos"
#define MyAppExeName "piphotos-server.exe"

[Setup]
AppId={{E65B8672-C7D4-4DF1-9BD5-6BC7F31D219C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\PiPhotos Server
DisableProgramGroupPage=yes
OutputBaseFilename=PiPhotos-Server-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startupicon"; Description: "Start PiPhotos minimized to system tray on Windows log on"; GroupDescription: "Windows Integration:"; Flags: checkedonce

[Files]
Source: "..\..\dist\piphotos-server.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\dist\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: DirExists(ExpandConstant('{src}\..\..\dist'))

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Parameters: "app"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Parameters: "app"; Tasks: desktopicon
Name: "{userstartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Parameters: "app --tray"; Tasks: startupicon

[Run]
; Add Windows Firewall rule during installation
Filename: "netsh.exe"; Parameters: "advfirewall firewall add rule name=""PiPhotos Server"" dir=in action=allow program=""{app}\{#MyAppExeName}"" enable=yes"; Flags: runhidden
; Launch GUI app after setup finishes
Filename: "{app}\{#MyAppExeName}"; Parameters: "app"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Clean up firewall rule on uninstallation
Filename: "netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PiPhotos Server"""; Flags: runhidden
