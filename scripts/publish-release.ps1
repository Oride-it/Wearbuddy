param([Parameter(Mandatory=$true)][ValidatePattern('^v[0-9]+\.[0-9]+\.[0-9]+$')][string]$Tag)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& ./gradlew.bat :wear:testDebugUnitTest :wear:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
& python scripts/prepare_release.py --tag $Tag
if ($LASTEXITCODE -ne 0) { throw 'Release validation failed' }
& git push origin main
if ($LASTEXITCODE -ne 0) { throw 'Source push failed' }
# Local releases do not trigger a second tag build; GitHub creates the tag at the pushed commit.
& gh release create $Tag --repo Oride-it/Wearbuddy --target main --draft --title "Wearbuddy $Tag" --notes-file "docs/releases/$($Tag.Substring(1)).md"
if ($LASTEXITCODE -ne 0) { throw 'Draft release creation failed' }
& gh release upload $Tag "dist/Wearbuddy-$Tag-release.apk" dist/SHA256SUMS --repo Oride-it/Wearbuddy
if ($LASTEXITCODE -ne 0) { throw 'Asset upload failed; release stays draft' }
& gh release edit $Tag --repo Oride-it/Wearbuddy --draft=false --latest
if ($LASTEXITCODE -ne 0) { throw 'Release publication failed' }
