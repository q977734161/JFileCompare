param(
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot 'build'
$Classes = Join-Path $BuildRoot 'test-classes'
$LibWildcard = Join-Path $ProjectRoot 'lib\*'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $Javac = 'javac'
    $Java = 'java'
} else {
    $Javac = Join-Path $JavaHome 'bin\javac.exe'
    $Java = Join-Path $JavaHome 'bin\java.exe'
}

. (Join-Path $PSScriptRoot 'TestClasses.ps1')

if (-not $SkipCompile) {
    if (Test-Path $Classes) { Remove-Item -Recurse -Force $Classes }
    New-Item -ItemType Directory -Force $Classes | Out-Null
    $Sources = @(Get-ChildItem (Join-Path $ProjectRoot 'src\main\java') -Filter '*.java') +
            @(Get-ChildItem (Join-Path $ProjectRoot 'src\test\java') -Filter '*.java')
    & $Javac --release 8 -encoding UTF-8 -cp $LibWildcard -d $Classes $Sources.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Java 8 compilation failed.' }
}

$ClassPath = $Classes + [IO.Path]::PathSeparator + $LibWildcard
foreach ($TestClass in $NonVisualTests) {
    Write-Host "[TEST] $TestClass"
    & $Java '-Djava.awt.headless=true' -cp $ClassPath $TestClass
    if ($LASTEXITCODE -ne 0) { throw "$TestClass failed." }
}

Write-Host "All $($NonVisualTests.Count) non-visual tests passed."
