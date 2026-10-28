param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$Baseline,
    [Parameter(Mandatory=$true)][string]$Minecraft,
    [Parameter(Mandatory=$true)][string]$OriginalLoader,
    [Parameter(Mandatory=$true)][string]$OriginalSources,
    [Parameter(Mandatory=$true)][string]$PreviousRelease,
    [Parameter(Mandatory=$true)][string]$ReferenceRelease,
    [string]$Output = (Join-Path $PSScriptRoot 'dist'),
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
# No downloads, profile edits, elevation or cleanup: the Python build verifies
# every input checksum and refuses to overwrite a nonempty output directory.
$jdk = (Resolve-Path -LiteralPath $JavaHome).Path
foreach ($tool in @('java.exe', 'javac.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $jdk "bin\$tool") -PathType Leaf)) {
        throw "A complete Windows JDK 21 is required: $jdk\bin\$tool"
    }
}
$inputs = @($Baseline, $Minecraft, $OriginalLoader, $OriginalSources, $PreviousRelease, $ReferenceRelease)
foreach ($inputPath in $inputs) {
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Missing input: $inputPath" }
}
# Avoid embedded double quotes: Windows PowerShell 5 uses legacy native-argument parsing.
& $Python -c 'import sys;sys.exit(sys.version_info<(3,10))'
if ($LASTEXITCODE -ne 0) { throw 'Python 3.10+ preflight failed' }
& $Python -X utf8 (Join-Path $PSScriptRoot 'build.py') --offline `
    --java-home $jdk --baseline $Baseline --minecraft $Minecraft `
    --original-loader $OriginalLoader --original-sources $OriginalSources `
    --previous-release $PreviousRelease --reference-release $ReferenceRelease --output $Output
if ($LASTEXITCODE -ne 0) { throw "Build/tests failed (exit $LASTEXITCODE). See the printed staging reports." }
Write-Host "Build and automated checks complete: $Output"
Write-Host 'Minecraft gameplay and server compatibility require the separate scenario in docs/GAMEPLAY-TEST-RU.md.'
