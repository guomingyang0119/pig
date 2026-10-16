# 报价系统本机停止入口：按前端到基础依赖的顺序停止启动脚本登记的组件。
[CmdletBinding()]
param([ValidateSet('all','redis','register','admin','codegen','auth','gateway','ui')][string]$Service='all')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'work/local'
. (Join-Path $PSScriptRoot 'Local-Runtime.ps1')
$runtimeLock = Enter-LocalRuntimeLock $runtimeDirectory
try {
foreach ($name in @('ui','gateway','auth','codegen','admin','register','redis')) {
    if ($Service -notin @('all',$name)) { continue }
    Stop-LocalManagedService $runtimeDirectory $name
}
} finally { $runtimeLock.Dispose() }
