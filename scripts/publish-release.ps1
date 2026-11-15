param([Parameter(Mandatory=$true)][ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')][string]$Tag)
$ErrorActionPreference = 'Stop'
$publisherRoot = Split-Path -Parent $PSScriptRoot
$githubCommand = Get-Command gh -ErrorAction SilentlyContinue
$githubCli = if ($githubCommand) { $githubCommand.Source } else { Join-Path $publisherRoot 'build/tools/gh/gh.exe' }
if (-not (Test-Path -LiteralPath $githubCli)) { throw 'Install GitHub CLI and run gh auth login first.' }
if (-not $env:HTTPS_PROXY) {
    $githubUri = [Uri]'https://github.com'
    $systemProxy = [System.Net.WebRequest]::GetSystemWebProxy().GetProxy($githubUri)
    if ($systemProxy -and $systemProxy.AbsoluteUri -ne $githubUri.AbsoluteUri) {
        $env:HTTPS_PROXY = $systemProxy.AbsoluteUri
        $env:HTTP_PROXY = $systemProxy.AbsoluteUri
    }
}
Set-Location (Split-Path -Parent $PSScriptRoot)
& ./gradlew.bat :wear:testDebugUnitTest :wear:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
& python scripts/prepare_release.py --tag $Tag
if ($LASTEXITCODE -ne 0) { throw 'Release validation failed' }
& git push origin main
if ($LASTEXITCODE -ne 0) { throw 'Source push failed' }
# Local releases do not trigger a second tag build; GitHub creates the tag at the pushed commit.
& $githubCli release create $Tag --repo Oride-it/Wearbuddy --target main --draft --title "Wearbuddy $Tag" --notes-file "docs/releases/$($Tag.Substring(1)).md"
if ($LASTEXITCODE -ne 0) { throw 'Draft release creation failed' }
& $githubCli release upload $Tag "dist/Wearbuddy-$Tag-release.apk" dist/SHA256SUMS --repo Oride-it/Wearbuddy
if ($LASTEXITCODE -ne 0) { throw 'Asset upload failed; release stays draft' }
& $githubCli release edit $Tag --repo Oride-it/Wearbuddy --draft=false --latest
if ($LASTEXITCODE -ne 0) { throw 'Release publication failed' }
