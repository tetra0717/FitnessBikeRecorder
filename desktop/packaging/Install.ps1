$ErrorActionPreference = 'Stop'
$installFolder = Join-Path $env:LOCALAPPDATA 'Programs\FitnessBikeRecorderPC'
$sourceExe = Join-Path $PSScriptRoot 'FitnessBikeRecorderPC.exe'
if (-not (Test-Path -LiteralPath $sourceExe)) { throw 'Extract the entire ZIP before installing.' }
New-Item -ItemType Directory -Path $installFolder -Force | Out-Null
$destinationExe = Join-Path $installFolder 'FitnessBikeRecorderPC.exe'
Get-Process -Name FitnessBikeRecorderPC -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $destinationExe } | Stop-Process
Get-ChildItem -LiteralPath $PSScriptRoot -File | Copy-Item -Destination $installFolder -Force
foreach ($contentFolder in @('licenses', 'docs')) {
    $sourceContent = Join-Path $PSScriptRoot $contentFolder
    if (Test-Path -LiteralPath $sourceContent) {
        $installedContent = Join-Path $installFolder $contentFolder
        New-Item -ItemType Directory -Path $installedContent -Force | Out-Null
        Get-ChildItem -LiteralPath $sourceContent -File | Copy-Item -Destination $installedContent -Force
    }
}
$startupKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
New-Item -Path $startupKey -Force | Out-Null
New-ItemProperty -Path $startupKey -Name FitnessBikeRecorderPC -Value ('"' + $destinationExe + '"') -PropertyType String -Force | Out-Null
Start-Process -FilePath $destinationExe -WindowStyle Hidden
Write-Host 'Installed. The app will start at Windows sign-in.'
Write-Host 'Right-click the bike icon in the notification area and open Settings.'
