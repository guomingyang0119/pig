# 报价系统本机停止入口：按前端到基础依赖的顺序停止启动脚本登记的组件。
[CmdletBinding()]
param([ValidateSet('all','redis','register','admin','codegen','auth','gateway','ui')][string]$Service='all')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'work/local'
foreach ($name in @('ui','gateway','auth','codegen','admin','register','redis')) {
    if ($Service -notin @('all',$name)) { continue }
    $statePath = Join-Path $runtimeDirectory "$name.process.json"
    if (-not (Test-Path -LiteralPath $statePath)) { continue }
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $state.Id -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    # 同时核对可执行文件和启动时间，防止PID被复用后误停其他进程。
    if ($process.Path -ne $state.Executable -or [Math]::Abs(($process.StartTime.ToUniversalTime() - ([datetime]$state.Started).ToUniversalTime()).TotalSeconds) -gt 1) {
        Write-Warning "Skipping $name because its process identity changed."
        continue
    }
    # Redis使用正常shutdown请求，其他已确认身份的组件按PID停止。
    if ($name -eq 'redis') {
        & (Join-Path (Split-Path -Parent $state.Executable) 'redis-cli.exe') -h 127.0.0.1 -p $state.Port shutdown
    } else { Stop-Process -Id $state.Id }
    Write-Host "$name stopped."
}
