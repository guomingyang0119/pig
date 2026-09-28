[CmdletBinding()]
param([ValidateSet('all','redis','register','admin','auth','gateway','ui')][string]$Service='all')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'work/local'
foreach ($name in @('ui','gateway','auth','admin','register','redis')) {
    if ($Service -notin @('all',$name)) { continue }
    $statePath = Join-Path $runtimeDirectory "$name.process.json"
    if (-not (Test-Path -LiteralPath $statePath)) { continue }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $state.Id -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if ($process.Path -ne $state.Executable -or [Math]::Abs(($process.StartTime.ToUniversalTime() - ([datetime]$state.Started).ToUniversalTime()).TotalSeconds) -gt 1) {
        Write-Warning "Skipping $name because its process identity changed."
        continue
    }
    if ($name -eq 'redis') {
        & (Join-Path (Split-Path -Parent $state.Executable) 'redis-cli.exe') -h 127.0.0.1 -p $state.Port shutdown
    } else { Stop-Process -Id $state.Id }
    Write-Host "$name stopped."
}
