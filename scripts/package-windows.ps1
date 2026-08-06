param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [switch]$SkipInstaller
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot 'build'
$DistRoot = Join-Path $ProjectRoot 'dist'
$JPackage = Join-Path $JdkHome 'bin\jpackage.exe'
$Java = Join-Path $JdkHome 'bin\java.exe'
$Version = '0.9.0-rc1'
$PackageVersion = '0.9.0'
$BaseName = "FileCompareTool-$Version-win-x64"

function Remove-WithRetry {
    param([Parameter(Mandatory = $true)][string]$Path)

    for ($Attempt = 1; $Attempt -le 5; $Attempt++) {
        if (-not (Test-Path -LiteralPath $Path)) { return }
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return
        } catch {
            if ($Attempt -eq 5) { throw }
            Start-Sleep -Milliseconds 500
        }
    }
}

function Clear-Directory {
    param([Parameter(Mandatory = $true)][string]$Path)

    New-Item -ItemType Directory -Force $Path | Out-Null
    Get-ChildItem -LiteralPath $Path -Force | ForEach-Object {
        Remove-WithRetry $_.FullName
    }
}

function Ensure-WixTools {
    if ((Get-Command 'candle.exe' -ErrorAction SilentlyContinue) -and
            (Get-Command 'light.exe' -ErrorAction SilentlyContinue)) {
        return
    }

    $LocalWixZip = Join-Path $ProjectRoot 'wix314-binaries.zip'
    if (Test-Path -LiteralPath $LocalWixZip) {
        $LocalWixDir = Join-Path $BuildRoot 'tools\wix314'
        Remove-WithRetry $LocalWixDir
        New-Item -ItemType Directory -Force $LocalWixDir | Out-Null
        Expand-Archive -Path $LocalWixZip -DestinationPath $LocalWixDir -Force
        $env:PATH = $LocalWixDir + [IO.Path]::PathSeparator + $env:PATH
    }

    if (-not (Get-Command 'candle.exe' -ErrorAction SilentlyContinue) -or
            -not (Get-Command 'light.exe' -ErrorAction SilentlyContinue)) {
        throw 'WiX Toolset 3 candle.exe and light.exe are required for installer generation. Add WiX to PATH or place wix314-binaries.zip in the project root.'
    }
}

if (-not (Test-Path -LiteralPath $JPackage)) {
    throw "jpackage.exe not found under $JdkHome. Use a Windows x64 JDK 21 LTS."
}

$VersionLog = Join-Path $env:TEMP 'file-compare-tool-java-version.log'
Remove-WithRetry $VersionLog
Start-Process -FilePath $Java -ArgumentList '-version' -NoNewWindow -Wait -RedirectStandardError $VersionLog | Out-Null
$RuntimeVersion = (Get-Content $VersionLog -ErrorAction Stop | Select-Object -First 1)
if ($RuntimeVersion -notmatch 'version "21[\.]') {
    throw "Windows packaging requires JDK 21 LTS. Found: $RuntimeVersion"
}
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'assets\app-icon.ico'))) {
    throw 'Missing assets\app-icon.ico.'
}
if (-not $SkipInstaller) {
    Ensure-WixTools
}

& (Join-Path $PSScriptRoot 'build.ps1') -JavaHome $JdkHome
Clear-Directory $DistRoot

$InputDir = Join-Path $BuildRoot 'app'
$InputLegal = Join-Path $InputDir 'legal'
Copy-Item (Join-Path $ProjectRoot 'README-WINDOWS.md') $InputDir -Force
Copy-Item (Join-Path $ProjectRoot 'CHANGELOG.md') $InputDir -Force
Copy-Item (Join-Path $ProjectRoot 'THIRD_PARTY_NOTICES.md') $InputDir -Force
Copy-Item (Join-Path $ProjectRoot 'LICENSE.txt') $InputDir -Force
Remove-WithRetry $InputLegal
Copy-Item (Join-Path $ProjectRoot 'legal') $InputLegal -Recurse

$AppImageRoot = Join-Path $BuildRoot 'package'
Clear-Directory $AppImageRoot

$CommonArgs = @(
    '--name', 'FileCompareTool',
    '--input', $InputDir,
    '--main-jar', 'FileCompareTool.jar',
    '--main-class', 'FileCompareTool',
    '--app-version', $PackageVersion,
    '--vendor', 'File Compare Tool contributors',
    '--description', 'File and directory compare, edit, and safe sync tool',
    '--icon', (Join-Path $ProjectRoot 'assets\app-icon.ico'),
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', "-Dfilecompare.version=$Version",
    '--java-options', '-Dfilecompare.channel=release-candidate',
    '--java-options', "-Dfilecompare.build.date=$((Get-Date).ToString('yyyy-MM-dd'))",
    '--java-options', "-Dfilecompare.build.commit=$((Get-Content (Join-Path $BuildRoot 'BUILD-COMMIT.txt') -Raw).Trim())"
)

$AppImageArgs = @('--verbose', '--type', 'app-image', '--dest', $AppImageRoot) + $CommonArgs
& $JPackage $AppImageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage app-image failed.' }

$AppImage = Join-Path $AppImageRoot 'FileCompareTool'
Copy-Item (Join-Path $ProjectRoot 'README-WINDOWS.md') $AppImage
Copy-Item (Join-Path $ProjectRoot 'CHANGELOG.md') $AppImage
Copy-Item (Join-Path $ProjectRoot 'THIRD_PARTY_NOTICES.md') $AppImage
Copy-Item (Join-Path $ProjectRoot 'LICENSE.txt') $AppImage
Copy-Item (Join-Path $ProjectRoot 'legal') $AppImage -Recurse

$PortableZip = Join-Path $DistRoot "$BaseName-portable.zip"
Compress-Archive -Path (Join-Path $AppImage '*') -DestinationPath $PortableZip

if (-not $SkipInstaller) {
    $InstallerArgs = @('--verbose', '--type', 'exe', '--dest', $DistRoot) + $CommonArgs + @(
        '--win-menu',
        '--win-menu-group', 'File Compare Tool',
        '--win-shortcut',
        '--win-dir-chooser',
        '--win-per-user-install',
        '--win-upgrade-uuid', '2d94cb1f-b09e-4cb6-b73e-c9f6fc64a56b'
    )
    & $JPackage $InstallerArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage installer failed.' }
    $GeneratedExe = Get-ChildItem $DistRoot -Filter 'FileCompareTool-*.exe' | Select-Object -First 1
    if ($GeneratedExe) {
        $SetupName = "$BaseName-setup.exe"
        Remove-WithRetry (Join-Path $DistRoot $SetupName)
        Rename-Item -LiteralPath $GeneratedExe.FullName -NewName $SetupName
    }
}

$ChecksumFile = Join-Path $DistRoot 'SHA256SUMS.txt'
$Lines = Get-ChildItem $DistRoot -File | Where-Object Name -ne 'SHA256SUMS.txt' |
        Sort-Object Name | ForEach-Object {
            $Hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$Hash  $($_.Name)"
        }
[IO.File]::WriteAllLines($ChecksumFile, $Lines, [Text.UTF8Encoding]::new($false))

Write-Host "Windows packages written to $DistRoot"
