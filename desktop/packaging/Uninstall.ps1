$ErrorActionPreference = 'Stop'
$programsFolder = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Programs'))
$installFolder = [IO.Path]::GetFullPath((Join-Path $programsFolder 'FitnessBikeRecorderPC'))
if ([IO.Path]::GetDirectoryName($installFolder) -ne $programsFolder -or [IO.Path]::GetFileName($installFolder) -ne 'FitnessBikeRecorderPC') { throw 'Unexpected installation path.' }
$installedExe = Join-Path $installFolder 'FitnessBikeRecorderPC.exe'
Get-Process -Name FitnessBikeRecorderPC -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $installedExe } | Stop-Process
$startupKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$startupEntry = (Get-ItemProperty -LiteralPath $startupKey -Name FitnessBikeRecorderPC -ErrorAction SilentlyContinue).FitnessBikeRecorderPC
if ($startupEntry -eq ('"' + $installedExe + '"')) { Remove-ItemProperty -LiteralPath $startupKey -Name FitnessBikeRecorderPC }
if (Test-Path -LiteralPath $installFolder) { Remove-Item -LiteralPath $installFolder -Recurse -Force }
Write-Host 'Uninstalled. Pairing settings were preserved in LocalAppData\FitnessBikeRecorderPC.'
