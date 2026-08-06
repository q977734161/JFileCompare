param([string]$JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Classes = Join-Path $ProjectRoot 'build\test-classes'
$LibWildcard = Join-Path $ProjectRoot 'lib\*'

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $Java = 'java'
} else {
    $Java = Join-Path $JavaHome 'bin\java.exe'
}

. (Join-Path $PSScriptRoot 'TestClasses.ps1')
& (Join-Path $PSScriptRoot 'test.ps1') -JavaHome $JavaHome

$ClassPath = $Classes + [IO.Path]::PathSeparator + $LibWildcard
foreach ($TestClass in $VisualTests) {
    Write-Host "[UI] $TestClass"
    & $Java -cp $ClassPath $TestClass
    if ($LASTEXITCODE -ne 0) { throw "$TestClass failed." }
}

Write-Host "All $($VisualTests.Count) Swing smoke tests passed."

