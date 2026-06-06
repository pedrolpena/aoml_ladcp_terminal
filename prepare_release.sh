#!/usr/bin/env bash
#
# AOML LADCP Terminal — release preparation script
# -------------------------------------------------
# Stages a PRE-COMPILED build into per-OS release folders, each containing the
# correct installer for that platform. The program lives in a folder named
# "application"; an optional bundled JRE sits beside it in "jre" (preferred at
# runtime, with fallback to system Java).
#
# Per-OS release layout produced by this script:
#
#   <out>/ladcp-terminal-<version>-<target>/
#   ├── <installer for the target OS>
#   ├── application/
#   │   └── ladcp-terminal-<version>.jar
#   ├── jre/                         (only if a JRE was provided for that target)
#   │   └── bin/...
#   └── icons/
#       └── ladcp.(png|ico|icns)
#
# Targets: linux  windows  macos-x86  macos-arm   (or "all")
#
# Examples:
#   ./prepare-release.sh linux
#   ./prepare-release.sh --version 1.0 --jar target/ladcp-terminal-1.0-SNAPSHOT.jar linux windows
#   ./prepare-release.sh --jre-linux /opt/jre-linux --archive linux
#   ./prepare-release.sh --emit-installers        # just write installers to repo root, no staging
#   ./prepare-release.sh all                      # build every target it has inputs for
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration / defaults  (override via flags)
# ---------------------------------------------------------------------------
PROJECT="ladcp-terminal"
VERSION="1.0-SNAPSHOT"
JAR_PATH=""                       # auto-detected if empty
ICON_PNG="images/ladcp.png"       # Linux
ICON_ICO="images/ladcp.ico"       # Windows
ICON_ICNS="images/ladcp.icns"     # macOS
JRE_LINUX=""
JRE_WINDOWS=""
JRE_MACOS_X86=""
JRE_MACOS_ARM=""
OUT_DIR="dist"
ARCHIVE=0
EMIT_INSTALLERS=0

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

# ---------------------------------------------------------------------------
# Arg parsing
# ---------------------------------------------------------------------------
TARGETS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --version)        VERSION="$2"; shift 2 ;;
    --jar)            JAR_PATH="$2"; shift 2 ;;
    --icon-png)       ICON_PNG="$2"; shift 2 ;;
    --icon-ico)       ICON_ICO="$2"; shift 2 ;;
    --icon-icns)      ICON_ICNS="$2"; shift 2 ;;
    --jre-linux)      JRE_LINUX="$2"; shift 2 ;;
    --jre-windows)    JRE_WINDOWS="$2"; shift 2 ;;
    --jre-macos-x86)  JRE_MACOS_X86="$2"; shift 2 ;;
    --jre-macos-arm)  JRE_MACOS_ARM="$2"; shift 2 ;;
    --out)            OUT_DIR="$2"; shift 2 ;;
    --archive)        ARCHIVE=1; shift ;;
    --emit-installers) EMIT_INSTALLERS=1; shift ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    linux|windows|macos-x86|macos-arm) TARGETS+=("$1"); shift ;;
    all) TARGETS=(linux windows macos-x86 macos-arm); shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

JAR_NAME="${PROJECT}-${VERSION}.jar"

# ---------------------------------------------------------------------------
# Locate the built JAR if not supplied
# ---------------------------------------------------------------------------
resolve_jar() {
  if [ -n "$JAR_PATH" ]; then
    [ -f "$JAR_PATH" ] || { echo "ERROR: --jar '$JAR_PATH' not found." >&2; exit 1; }
    JAR_NAME="$(basename "$JAR_PATH")"
    return
  fi
  # Try the conventional Maven output
  local guess="$REPO_DIR/target/${PROJECT}-${VERSION}.jar"
  if [ -f "$guess" ]; then JAR_PATH="$guess"; JAR_NAME="$(basename "$guess")"; return; fi
  # Fall back to the newest matching jar under target/
  local found
  found="$(ls -t "$REPO_DIR"/target/${PROJECT}-*.jar 2>/dev/null | head -n1 || true)"
  if [ -n "$found" ]; then
    JAR_PATH="$found"; JAR_NAME="$(basename "$found")"
    echo "==> Using detected JAR: $JAR_NAME"
    return
  fi
  echo "ERROR: Could not find a built JAR. Build it first (mvn clean package)" >&2
  echo "       or pass one with --jar <path>." >&2
  exit 1
}

# ===========================================================================
# Installer generators  (each writes one installer file to the given path)
# The program directory is "application"; bundled JRE is "jre" beside it.
# ===========================================================================

write_linux_installer() {  # $1 = dest file
  cat > "$1" <<'SH_EOF'
#!/usr/bin/env bash
# AOML LADCP Terminal — Linux installer (installs a pre-built app).
set -euo pipefail

APP_NAME="AOML LADCP Terminal"
APP_ID="ladcp-terminal"
JAR_NAME="__JAR_NAME__"
ICON_SRC_NAME="ladcp.png"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

if [ "$(id -u)" -eq 0 ]; then
  PREFIX="/opt/$APP_ID"; BIN_LINK="/usr/local/bin/$APP_ID"
  DESKTOP_DIR="/usr/share/applications"
  ICON_DIR="/usr/share/icons/hicolor/256x256/apps"; ICON_ROOT="/usr/share/icons/hicolor"
  MODE="system-wide"
else
  PREFIX="$HOME/.local/share/$APP_ID"; BIN_LINK="$HOME/.local/bin/$APP_ID"
  DESKTOP_DIR="$HOME/.local/share/applications"
  ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"; ICON_ROOT="$HOME/.local/share/icons/hicolor"
  MODE="per-user"
fi

echo "==> Installing $APP_NAME ($MODE)"
echo "    Target: $PREFIX"

if [ ! -f "$SCRIPT_DIR/application/$JAR_NAME" ]; then
  echo "ERROR: Cannot find application/$JAR_NAME next to this installer." >&2
  exit 1
fi

mkdir -p "$PREFIX/application" "$(dirname "$BIN_LINK")" "$DESKTOP_DIR" "$ICON_DIR"

echo "==> Copying application..."
cp "$SCRIPT_DIR/application/$JAR_NAME" "$PREFIX/application/"

if [ -d "$SCRIPT_DIR/jre" ]; then
  echo "==> Bundled JRE found — installing it (preferred at runtime)..."
  rm -rf "$PREFIX/jre"; cp -a "$SCRIPT_DIR/jre" "$PREFIX/jre"
  chmod +x "$PREFIX/jre/bin/java" 2>/dev/null || true
else
  echo "==> No bundled JRE — launcher will fall back to system Java."
fi

echo "==> Installing launcher..."
cat > "$PREFIX/$APP_ID" <<'LAUNCHER_EOF'
#!/usr/bin/env bash
# Prefers bundled JRE at <install-dir>/jre, falls back to system java.
SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
  SOURCE="$(readlink "$SOURCE")"; [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
APP_DIR="$(cd -P "$(dirname "$SOURCE")" >/dev/null 2>&1 && pwd)"
JAR="$APP_DIR/application/__JAR_NAME__"
BUNDLED_JAVA="$APP_DIR/jre/bin/java"
if [ -x "$BUNDLED_JAVA" ]; then JAVA="$BUNDLED_JAVA"
elif command -v java >/dev/null 2>&1; then JAVA="java"
else
  if command -v zenity >/dev/null 2>&1; then
    zenity --error --text="No Java runtime found.\nNo bundled JRE at:\n$BUNDLED_JAVA\nand 'java' is not on your PATH."
  else
    echo "ERROR: No Java runtime found. Looked for $BUNDLED_JAVA and 'java' on PATH." >&2
  fi
  exit 1
fi
exec "$JAVA" -jar "$JAR" "$@"
LAUNCHER_EOF
sed -i "s/__JAR_NAME__/$JAR_NAME/g" "$PREFIX/$APP_ID"
chmod +x "$PREFIX/$APP_ID"
ln -sf "$PREFIX/$APP_ID" "$BIN_LINK"

ICON_INSTALLED=0
if [ -f "$SCRIPT_DIR/icons/$ICON_SRC_NAME" ]; then
  cp "$SCRIPT_DIR/icons/$ICON_SRC_NAME" "$ICON_DIR/$APP_ID.png"; ICON_INSTALLED=1
fi

echo "==> Creating desktop launcher..."
cat > "$DESKTOP_DIR/$APP_ID.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=Terminal for RDI/Teledyne LADCP instruments
Exec=$PREFIX/$APP_ID
Icon=$( [ "$ICON_INSTALLED" -eq 1 ] && echo "$APP_ID" || echo "utilities-terminal" )
Terminal=false
Categories=Science;Utility;
StartupNotify=true
EOF
chmod 644 "$DESKTOP_DIR/$APP_ID.desktop"

cat > "$PREFIX/uninstall.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
echo "==> Removing $APP_NAME..."
rm -f "$BIN_LINK" "$DESKTOP_DIR/$APP_ID.desktop" "$ICON_DIR/$APP_ID.png"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
rm -rf "$PREFIX"
echo "==> Uninstalled."
EOF
chmod +x "$PREFIX/uninstall.sh"

command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
command -v gtk-update-icon-cache  >/dev/null 2>&1 && gtk-update-icon-cache -f "$ICON_ROOT" 2>/dev/null || true

if ! id -nG "$USER" 2>/dev/null | grep -qw dialout; then
  echo; echo "NOTE: For serial access, join the 'dialout' group:"
  echo "      sudo usermod -a -G dialout \$USER  (then log out/in)"
fi
echo; echo "==> Done. Launch from the menu or run: $APP_ID"
echo "    Uninstall: $PREFIX/uninstall.sh"
SH_EOF
  sed -i "s/__JAR_NAME__/$JAR_NAME/g" "$1"
  chmod +x "$1"
}

write_windows_installer() {  # $1 = dest file
  cat > "$1" <<'PS_EOF'
# AOML LADCP Terminal - Windows 11 installer (installs a pre-built app).
# Run: right-click > "Run with PowerShell"  or
#      powershell -ExecutionPolicy Bypass -File install.ps1
$ErrorActionPreference = "Stop"

$AppName = "AOML LADCP Terminal"
$AppId   = "LADCPTerminal"
$JarName = "__JAR_NAME__"
$IconSrcName = "ladcp.ico"

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Definition
$InstallDir = Join-Path $env:LOCALAPPDATA "Programs\$AppId"

Write-Host "==> Installing $AppName"
Write-Host "    Target: $InstallDir"

$StagedJar = Join-Path $ScriptDir "application\$JarName"
if (-not (Test-Path $StagedJar)) {
    Write-Error "Cannot find application\$JarName next to this installer."
    exit 1
}

New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir "application") | Out-Null
Write-Host "==> Copying application..."
Copy-Item $StagedJar -Destination (Join-Path $InstallDir "application\$JarName") -Force

$StagedJre = Join-Path $ScriptDir "jre"
if (Test-Path $StagedJre) {
    Write-Host "==> Bundled JRE found - installing it (preferred at runtime)..."
    $DestJre = Join-Path $InstallDir "jre"
    if (Test-Path $DestJre) { Remove-Item $DestJre -Recurse -Force }
    Copy-Item $StagedJre -Destination $DestJre -Recurse -Force
} else {
    Write-Host "==> No bundled JRE - launcher will fall back to system Java."
}

Write-Host "==> Installing launcher..."
$LauncherPath = Join-Path $InstallDir "ladcp-terminal.bat"
$LauncherLines = @(
    '@echo off',
    'setlocal',
    'set "APP_DIR=%~dp0"',
    "set ""JAR=%APP_DIR%application\$JarName""",
    'set "BUNDLED_JAVA=%APP_DIR%jre\bin\javaw.exe"',
    'if exist "%BUNDLED_JAVA%" (',
    '    start "" "%BUNDLED_JAVA%" -jar "%JAR%" %*',
    '    goto :eof',
    ')',
    'where javaw >nul 2>nul',
    'if %ERRORLEVEL%==0 (',
    '    start "" javaw -jar "%JAR%" %*',
    '    goto :eof',
    ')',
    'echo No Java runtime found.',
    'echo Looked for bundled JRE at: %BUNDLED_JAVA%',
    'echo and ''javaw'' on PATH. Install Java 11+ or reinstall with a bundled JRE.',
    'pause',
    'endlocal'
)
Set-Content -Path $LauncherPath -Value $LauncherLines -Encoding ASCII

$IconSrc  = Join-Path $ScriptDir "icons\$IconSrcName"
$IconDest = Join-Path $InstallDir "ladcp.ico"
$HaveIcon = Test-Path $IconSrc
if ($HaveIcon) { Copy-Item $IconSrc -Destination $IconDest -Force; Write-Host "==> Icon installed." }
else { Write-Host "==> No icon at icons\$IconSrcName - using default icon." }

Write-Host "==> Creating shortcuts..."
$WshShell = New-Object -ComObject WScript.Shell
function New-Shortcut($LinkPath) {
    $sc = $WshShell.CreateShortcut($LinkPath)
    $sc.TargetPath = $LauncherPath
    $sc.WorkingDirectory = $InstallDir
    $sc.WindowStyle = 7
    $sc.Description = "Terminal for RDI/Teledyne LADCP instruments"
    if ($HaveIcon) { $sc.IconLocation = $IconDest }
    $sc.Save()
}
$StartMenuDir = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
New-Shortcut (Join-Path $StartMenuDir "$AppName.lnk")
New-Shortcut (Join-Path ([Environment]::GetFolderPath("Desktop")) "$AppName.lnk")

$UninstallPath = Join-Path $InstallDir "uninstall.ps1"
$UninstallLines = @(
    '$ErrorActionPreference = "SilentlyContinue"',
    "`$AppName = `"$AppName`"",
    "`$AppId   = `"$AppId`"",
    "`$InstallDir = `"$InstallDir`"",
    'Write-Host "==> Removing $AppName..."',
    'Remove-Item (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\$AppName.lnk") -Force',
    'Remove-Item (Join-Path ([Environment]::GetFolderPath("Desktop")) "$AppName.lnk") -Force',
    'Remove-Item "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppId" -Recurse -Force',
    'if (Test-Path $InstallDir) {',
    '    Start-Process powershell -ArgumentList @("-NoProfile","-Command","Start-Sleep -Seconds 2; Remove-Item -LiteralPath `"$InstallDir`" -Recurse -Force") -WindowStyle Hidden',
    '}',
    'Write-Host "==> Uninstalled."'
)
Set-Content -Path $UninstallPath -Value $UninstallLines -Encoding UTF8

$RegPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppId"
New-Item -Path $RegPath -Force | Out-Null
Set-ItemProperty -Path $RegPath -Name "DisplayName"     -Value $AppName
Set-ItemProperty -Path $RegPath -Name "DisplayVersion"  -Value "__VERSION__"
Set-ItemProperty -Path $RegPath -Name "Publisher"       -Value "NOAA AOML"
Set-ItemProperty -Path $RegPath -Name "InstallLocation" -Value $InstallDir
Set-ItemProperty -Path $RegPath -Name "NoModify"        -Value 1 -Type DWord
Set-ItemProperty -Path $RegPath -Name "NoRepair"        -Value 1 -Type DWord
if ($HaveIcon) { Set-ItemProperty -Path $RegPath -Name "DisplayIcon" -Value $IconDest }
Set-ItemProperty -Path $RegPath -Name "UninstallString" -Value "powershell -ExecutionPolicy Bypass -File `"$UninstallPath`""

Write-Host ""
Write-Host "==> Done. Launch '$AppName' from the Start Menu or Desktop shortcut."
PS_EOF
  sed -i "s/__JAR_NAME__/$JAR_NAME/g; s/__VERSION__/$VERSION/g" "$1"
}

write_macos_installer() {  # $1 = dest file
  cat > "$1" <<'CMD_EOF'
#!/usr/bin/env bash
# AOML LADCP Terminal - macOS installer.
# Builds a "LADCP Terminal.app" bundle in ~/Applications.
# A bundled JRE (if staged) is placed inside the bundle and preferred at runtime.
set -euo pipefail

APP_NAME="LADCP Terminal"
JAR_NAME="__JAR_NAME__"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

if [ ! -f "$SCRIPT_DIR/application/$JAR_NAME" ]; then
  echo "ERROR: Cannot find application/$JAR_NAME next to this installer." >&2
  exit 1
fi

DEST="$HOME/Applications/$APP_NAME.app"
echo "==> Installing $APP_NAME to $DEST"
rm -rf "$DEST"
mkdir -p "$DEST/Contents/MacOS" "$DEST/Contents/Resources/application"

cp "$SCRIPT_DIR/application/$JAR_NAME" "$DEST/Contents/Resources/application/"

if [ -d "$SCRIPT_DIR/jre" ]; then
  echo "==> Bundling JRE (preferred at runtime)..."
  cp -a "$SCRIPT_DIR/jre" "$DEST/Contents/Resources/jre"
  chmod +x "$DEST/Contents/Resources/jre/bin/java" 2>/dev/null || true
else
  echo "==> No bundled JRE - will fall back to system Java."
fi

if [ -f "$SCRIPT_DIR/icons/ladcp.icns" ]; then
  cp "$SCRIPT_DIR/icons/ladcp.icns" "$DEST/Contents/Resources/ladcp.icns"
  ICON_LINE="    <key>CFBundleIconFile</key>
    <string>ladcp.icns</string>"
else
  ICON_LINE=""
fi

cat > "$DEST/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundleExecutable</key>
    <string>launcher</string>
    <key>CFBundleIdentifier</key>
    <string>gov.noaa.aoml.ladcpterminal</string>
    <key>CFBundleVersion</key>
    <string>__VERSION__</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
$ICON_LINE
</dict>
</plist>
PLIST

cat > "$DEST/Contents/MacOS/launcher" <<'LAUNCHER_EOF'
#!/usr/bin/env bash
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
RES="$(cd "$HERE/../Resources" >/dev/null 2>&1 && pwd)"
JAR="$RES/application/__JAR_NAME__"
BUNDLED_JAVA="$RES/jre/bin/java"
if [ -x "$BUNDLED_JAVA" ]; then JAVA="$BUNDLED_JAVA"
elif command -v java >/dev/null 2>&1; then JAVA="java"
else
  osascript -e 'display alert "No Java runtime found" message "No bundled JRE and no system Java on PATH. Install Java 11+."'
  exit 1
fi
exec "$JAVA" -jar "$JAR" "$@"
LAUNCHER_EOF
sed -i '' "s/__JAR_NAME__/$JAR_NAME/g" "$DEST/Contents/MacOS/launcher" 2>/dev/null \
  || sed -i "s/__JAR_NAME__/$JAR_NAME/g" "$DEST/Contents/MacOS/launcher"
chmod +x "$DEST/Contents/MacOS/launcher"

echo "==> Done. '$APP_NAME' is in your ~/Applications folder."
echo "    (First launch: right-click the app > Open, to clear Gatekeeper.)"
CMD_EOF
  sed -i "s/__JAR_NAME__/$JAR_NAME/g; s/__VERSION__/$VERSION/g" "$1"
  chmod +x "$1"
}

# ---------------------------------------------------------------------------
# Emit-installers mode: write canonical installers to the repo root and exit
# ---------------------------------------------------------------------------
if [ "$EMIT_INSTALLERS" -eq 1 ]; then
  resolve_jar
  echo "==> Writing installers to repo root ($REPO_DIR)"
  write_linux_installer   "$REPO_DIR/install.sh"
  write_windows_installer "$REPO_DIR/install.ps1"
  write_macos_installer   "$REPO_DIR/install.command"
  echo "==> Wrote install.sh, install.ps1, install.command"
  exit 0
fi

# ---------------------------------------------------------------------------
# Helpers for staging a target
# ---------------------------------------------------------------------------
copy_icon() {  # $1 = release dir, $2 = target
  mkdir -p "$1/icons"
  case "$2" in
    linux)
      [ -f "$REPO_DIR/$ICON_PNG" ] && cp "$REPO_DIR/$ICON_PNG" "$1/icons/ladcp.png" \
        || echo "    (icon $ICON_PNG not found — skipping)" ;;
    windows)
      [ -f "$REPO_DIR/$ICON_ICO" ] && cp "$REPO_DIR/$ICON_ICO" "$1/icons/ladcp.ico" \
        || echo "    (icon $ICON_ICO not found — add it later and re-run)" ;;
    macos-x86|macos-arm)
      [ -f "$REPO_DIR/$ICON_ICNS" ] && cp "$REPO_DIR/$ICON_ICNS" "$1/icons/ladcp.icns" \
        || echo "    (icon $ICON_ICNS not found — skipping)" ;;
  esac
}

copy_jre() {  # $1 = release dir, $2 = target
  local jre=""
  case "$2" in
    linux)     jre="$JRE_LINUX" ;;
    windows)   jre="$JRE_WINDOWS" ;;
    macos-x86) jre="$JRE_MACOS_X86" ;;
    macos-arm) jre="$JRE_MACOS_ARM" ;;
  esac
  if [ -n "$jre" ]; then
    [ -d "$jre" ] || { echo "ERROR: JRE path '$jre' not found." >&2; exit 1; }
    echo "    Bundling JRE from: $jre"
    cp -a "$jre" "$1/jre"
  else
    echo "    No JRE provided for $2 — release will rely on system Java."
  fi
}

stage_target() {  # $1 = target
  local target="$1"
  local relname="${PROJECT}-${VERSION}-${target}"
  local reldir="$OUT_DIR/$relname"

  echo
  echo "==> Staging $target  ->  $reldir"
  rm -rf "$reldir"
  mkdir -p "$reldir/application"

  cp "$JAR_PATH" "$reldir/application/$JAR_NAME"

  case "$target" in
    linux)     write_linux_installer   "$reldir/install.sh" ;;
    windows)   write_windows_installer "$reldir/install.ps1" ;;
    macos-x86|macos-arm) write_macos_installer "$reldir/install.command" ;;
  esac

  copy_icon "$reldir" "$target"
  copy_jre  "$reldir" "$target"

  if [ "$ARCHIVE" -eq 1 ]; then
    echo "    Creating archive..."
    ( cd "$OUT_DIR"
      case "$target" in
        windows) command -v zip >/dev/null 2>&1 && zip -rq "$relname.zip" "$relname" \
                   || echo "    (zip not installed — leaving folder unarchived)" ;;
        *)       tar -czf "$relname.tar.gz" "$relname" ;;
      esac )
  fi
  echo "    Done: $reldir"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
if [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "No target specified. Use one of: linux windows macos-x86 macos-arm all" >&2
  echo "Run with --help for usage." >&2
  exit 1
fi

resolve_jar
echo "==> Project : $PROJECT"
echo "==> Version : $VERSION"
echo "==> JAR     : $JAR_PATH"
echo "==> Output  : $OUT_DIR"
mkdir -p "$OUT_DIR"

for t in "${TARGETS[@]}"; do
  stage_target "$t"
done

echo
echo "==> All requested targets staged under: $OUT_DIR/"
