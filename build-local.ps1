# Local build for LaneWise (Windows / PowerShell).
#   .\build-local.ps1            -> unit tests + debug APK
#   .\build-local.ps1 -Release   -> unit tests + debug + release APK
#   .\build-local.ps1 -Install   -> also installs the debug APK on a USB-connected phone (adb)
param([switch]$Release, [switch]$Install)
$ErrorActionPreference = "Stop"

# Gradle 8.14 cannot run on Java 25 (Android Studio's bundled JBR); use JDK 17-21.
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
}
# Separate Gradle home so a broken/shared ~/.gradle cache cannot break this project.
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$env:LOCALAPPDATA\LaneWise\gradle-home" }

$tasks = @("testDebugUnitTest", "assembleDebug")
if ($Release) { $tasks += "assembleRelease" }
& "$PSScriptRoot\gradlew.bat" @tasks --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Get-ChildItem "$PSScriptRoot\app\build\outputs\apk" -Recurse -Filter *.apk | Select-Object FullName, Length

if ($Install) {
    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    & $adb install -r "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
}
