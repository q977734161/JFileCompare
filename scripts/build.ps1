param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Commit = ''
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot 'build'
$TestClasses = Join-Path $BuildRoot 'test-classes'
$AppClasses = Join-Path $BuildRoot 'classes'
$AppLib = Join-Path $BuildRoot 'app\lib'
$LibWildcard = Join-Path $ProjectRoot 'lib\*'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $Javac = 'javac'
    $Jar = 'jar'
} else {
    $Javac = Join-Path $JavaHome 'bin\javac.exe'
    $Jar = Join-Path $JavaHome 'bin\jar.exe'
}

& (Join-Path $PSScriptRoot 'test.ps1') -JavaHome $JavaHome

if (Test-Path $AppClasses) { Remove-Item -Recurse -Force $AppClasses }
New-Item -ItemType Directory -Force $AppClasses | Out-Null
New-Item -ItemType Directory -Force $AppLib | Out-Null
$Sources = Get-ChildItem (Join-Path $ProjectRoot 'src\main\java') -Filter '*.java'
& $Javac --release 8 -encoding UTF-8 -cp $LibWildcard -d $AppClasses $Sources.FullName
if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed.' }

Copy-Item (Join-Path $ProjectRoot 'lib\*.jar') $AppLib -Force
$Manifest = Join-Path $BuildRoot 'MANIFEST.MF'
$ManifestContent = "Manifest-Version: 1.0`r`nMain-Class: FileCompareTool`r`nClass-Path: lib/java-diff-utils-4.12.jar lib/juniversalchardet-2.4.0.jar`r`n"
[IO.File]::WriteAllText($Manifest, $ManifestContent, [Text.UTF8Encoding]::new($false))
$JarPath = Join-Path $BuildRoot 'app\FileCompareTool.jar'
& $Jar cfm $JarPath $Manifest -C $AppClasses .
if ($LASTEXITCODE -ne 0) { throw 'Application JAR creation failed.' }

if ([string]::IsNullOrWhiteSpace($Commit)) {
    $Commit = 'unknown'
    try {
        $Detected = (& git -C $ProjectRoot rev-parse --short HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $Detected) { $Commit = $Detected.Trim() }
    } catch { }
}
[IO.File]::WriteAllText((Join-Path $BuildRoot 'BUILD-COMMIT.txt'), $Commit.Trim(),
        [Text.UTF8Encoding]::new($false))

Write-Host "Built $JarPath"
