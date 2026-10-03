# Run as administrator only if Windows Firewall blocks automatic discovery.
# Only the installed application, private networks and local-subnet UDP 5353 are allowed.
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$installedExe = Join-Path $env:LOCALAPPDATA 'Programs\FitnessBikeRecorderPC\FitnessBikeRecorderPC.exe'
if (-not (Test-Path -LiteralPath $installedExe)) { throw 'Run Install.cmd as your normal user first.' }
$existing = Get-NetFirewallRule -DisplayName 'FitnessBikeRecorderPC discovery' -ErrorAction SilentlyContinue
if (-not $existing) {
    New-NetFirewallRule -DisplayName 'FitnessBikeRecorderPC discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5353 -Program $installedExe -Profile Private -RemoteAddress LocalSubnet | Out-Null
}
Write-Host 'Automatic discovery is allowed on private networks only.'
