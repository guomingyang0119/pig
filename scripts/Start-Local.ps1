# 报价系统本机启动入口：按依赖顺序启动Redis、注册中心、业务服务、开发平台、认证、网关和报价页面。
# 日常入口使用-AutoUpdate检查源码；-Build强制隔离构建；-IncludeCodegen额外启动开发工具。
[CmdletBinding()]
param(
    [ValidateSet('all', 'redis', 'register', 'admin', 'codegen', 'auth', 'gateway', 'ui')]
    [string]$Service = 'all',
    [switch]$Build,
    [switch]$AutoUpdate,
    [switch]$IncludeCodegen
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'work/local'
$logDirectory = Join-Path $runtimeDirectory 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
. (Join-Path $PSScriptRoot 'Local-Runtime.ps1')
$runtimeLock = Enter-LocalRuntimeLock $runtimeDirectory
$startupWatch = [Diagnostics.Stopwatch]::StartNew()
$trackedProcesses = @{}
try {
$rootPomText = [IO.File]::ReadAllText((Join-Path $projectRoot 'pom.xml'), [Text.Encoding]::UTF8)
$nacosUsernameMatch = [regex]::Match($rootPomText, '<nacos\.username>\s*([^<]+?)\s*</nacos\.username>')
$nacosPasswordMatch = [regex]::Match($rootPomText, '<nacos\.password>\s*([^<]+?)\s*</nacos\.password>')
if (-not $nacosUsernameMatch.Success -or -not $nacosPasswordMatch.Success) {
    throw 'Missing Nacos credentials in the cloud Maven profile.'
}
$nacosUsername = $nacosUsernameMatch.Groups[1].Value.Trim()
$nacosPassword = $nacosPasswordMatch.Groups[1].Value.Trim()

# 读取本机注册中心配置中的连接字段，并优先应用同名环境变量覆盖。
function Get-RegisterValue([string]$Key) {
    $line = Get-Content -LiteralPath (Join-Path $projectRoot 'pig-register/src/main/resources/application.properties') |
        Where-Object { $_.StartsWith($Key + '=') } | Select-Object -First 1
    if (-not $line) { throw "Missing registry property: $Key" }
    $value = $line.Substring($Key.Length + 1)
    return [regex]::Replace($value, '\$\{([^:}]+):([^}]*)\}', {
        param($match)
        $override = [Environment]::GetEnvironmentVariable($match.Groups[1].Value)
        if ($null -ne $override) { return $override }
        return $match.Groups[2].Value
    })
}

function Assert-StartedProcessesAlive {
    foreach ($name in $trackedProcesses.Keys) {
        $process = $trackedProcesses[$name]
        $process.Refresh()
        if ($process.HasExited) { throw "$name exited. Check $logDirectory/$name.out.log and $name.err.log" }
    }
}

function Write-StartupStage([string]$Message) {
    Write-Host ('[{0:N1}s] {1}' -f $startupWatch.Elapsed.TotalSeconds, $Message)
}

function Wait-LocalPort([string]$Name, [int]$Port) {
    $deadline = (Get-Date).AddSeconds(100)
    while ((Get-Date) -lt $deadline) {
        Assert-StartedProcessesAlive
        if (Test-LocalPort $Port) {
            Assert-LocalPortOwner $Name $Port $trackedProcesses[$Name]
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name has not opened port $Port yet. Check $logDirectory/$Name.out.log"
}

# 等待Nacos配置服务、gRPC与认证可用后再启动依赖它的报价服务。
function Wait-RegistryReady {
    # Nacos的HTTP端口可能先打开，仍需等配置服务和gRPC完整就绪。
    $deadline = (Get-Date).AddSeconds(100)
    while ((Get-Date) -lt $deadline) {
        try {
            # Nacos HTTP端口打开不代表配置和认证可用。
            $readiness = Invoke-RestMethod -Uri 'http://127.0.0.1:8848/nacos/v3/admin/core/state/readiness' -TimeoutSec 3
            $login = if ($readiness.code -eq 0 -and $readiness.data -eq 'ok' -and (Test-LocalPort 9848)) {
                Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8848/nacos/v1/auth/users/login' `
                    -Body @{ username = $nacosUsername; password = $nacosPassword } -TimeoutSec 3
            }
            if ($login.accessToken) {
                Write-Host 'register : configuration service ready.'
                return
            }
        } catch { }
        Assert-StartedProcessesAlive
        Start-Sleep -Seconds 1
    }
    throw "Registry is not ready to serve configuration. Check $logDirectory/register.out.log"
}

# 等待应用健康状态为UP，确认报价服务依赖的数据库、Redis和服务发现已就绪。
function Wait-ApplicationReady([string]$Name, [int]$Port) {
    # Spring可能先打开TCP端口，数据库、Redis和服务发现仍需通过健康检查确认。
    $uri = "http://127.0.0.1:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
        Assert-StartedProcessesAlive
        try {
            $health = Invoke-RestMethod -Uri $uri -TimeoutSec 5
            if ($health.status -eq 'UP') {
                Write-Host "$Name : application ready."
                return
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Name opened port $Port but did not become healthy. Check $logDirectory/$Name.out.log"
}

# 等待报价前端HTTP入口可访问，便于启动脚本结束后直接打开工作台。
function Wait-UiReady {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Assert-StartedProcessesAlive
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8888' -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host 'ui : application ready.'
                return
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "ui opened port 8888 but did not become ready. Check $logDirectory/ui.out.log"
}

# 启动单个后台组件，临时注入环境变量后恢复当前进程环境，并记录PID、路径和启动时间。
# 核对PID、启动时间及端口归属后复用；发起启动与等待就绪分开，允许按组并行。
function Start-LocalService([string]$Name, [int]$Port, [string]$Executable, [string[]]$Arguments,
                            [string]$WorkingDirectory, [hashtable]$EnvironmentValues) {
    $existing = Get-LocalManagedProcess $runtimeDirectory $Name
    Assert-LocalPortOwner $Name $Port $existing
    if ($existing) {
        $trackedProcesses[$Name] = $existing
        Write-Host "$Name : reusing registered process $($existing.Id); checking readiness."
        return
    }
    if (-not (Test-Path -LiteralPath $Executable)) { throw "Executable missing: $Executable" }
    $savedEnvironment = @{}
    try {
        foreach ($key in $EnvironmentValues.Keys) {
            $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, [string]$EnvironmentValues[$key], 'Process')
        }
        $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
            -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory "$Name.out.log") `
            -RedirectStandardError (Join-Path $logDirectory "$Name.err.log") -PassThru
    } finally {
        foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
    }
    @{ Name = $Name; Id = $process.Id; Executable = $Executable; Started = $process.StartTime.ToUniversalTime().ToString('o'); Port = $Port } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeDirectory "$Name.process.json") -Encoding UTF8
    $trackedProcesses[$Name] = $process
    Write-StartupStage "$Name launched (PID $($process.Id))."
}

# 隔离打包成功后才更新运行包，失败时保留原服务；仅显式要求时构建代码生成服务。
if ($Build -or $AutoUpdate) {
    if ($Service -ne 'all') { throw '-Build/-AutoUpdate must be used with -Service all.' }
    . (Join-Path $PSScriptRoot 'Update-LocalPackages.ps1')
    if ($Build) { Update-LocalPackages -ProjectRoot $projectRoot -IncludeCodegen:$IncludeCodegen }
    else {
        Write-StartupStage 'Checking local source and package versions.'
        $packageEntries = @(Get-LocalServices | Where-Object { $_.Name -ne 'codegen' -or $IncludeCodegen })
        $fingerprints = Get-LocalSourceFingerprints $projectRoot $packageEntries
        $changedEntries = @(Get-LocalPackageUpdates $projectRoot $packageEntries $fingerprints)
        if ($changedEntries.Count -gt 0) {
            Write-Host ('Source/package changes or missing version records detected: ' + ($changedEntries.Name -join ', '))
            Update-LocalPackages -ProjectRoot $projectRoot -IncludeCodegen:$IncludeCodegen -ServiceNames @($changedEntries.Name)
        } else { Write-Host 'Packages match current local sources; no build is needed.' }
    }
}

$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$mysqlUser = Get-RegisterValue 'db.user'
$mysqlPassword = Get-RegisterValue 'db.password'
# 系统权限库使用pig，报价数据使用独立quotationsystem库；两者复用本机配置账号。
$environmentValues = @{
    NACOS_HOST = '127.0.0.1'; NACOS_PORT = '8848'
    MYSQL_HOST = '127.0.0.1'; MYSQL_PORT = '3306'; MYSQL_DB = 'pig'
    MYSQL_USER = $mysqlUser; MYSQL_PWD = $mysqlPassword
    SPRING_DATA_REDIS_HOST = '127.0.0.1'; SPRING_DATA_REDIS_PORT = '6379'
    SPRING_CLOUD_NACOS_DISCOVERY_IP = '127.0.0.1'
    SERVER_ADDRESS = '127.0.0.1'
    QUOTATION_DB_URL = 'jdbc:mysql://127.0.0.1:3306/quotationsystem?characterEncoding=utf8&useUnicode=true&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true'
    QUOTATION_DB_USERNAME = $mysqlUser; QUOTATION_DB_PASSWORD = $mysqlPassword
    FILE_LOCAL_BASE_PATH = (Join-Path $runtimeDirectory 'uploads').Replace('\', '/')
}

function Start-Redis {
    $redisExecutable = Join-Path $runtimeDirectory 'redis-8.10.2/Redis-8.10.2-Windows-x64-msys2/redis-server.exe'
    $redisDirectory = Join-Path $runtimeDirectory 'redis-data'
    New-Item -ItemType Directory -Path $redisDirectory -Force | Out-Null
    Start-LocalService 'redis' 6379 $redisExecutable @('--bind','127.0.0.1','--port','6379','--protected-mode','yes','--appendonly','yes','--save','60','1') $redisDirectory @{}
}

# 各组件在后面的分组编排中启动，依赖就绪后才进入下一组。
$services = @(Get-LocalServices)
function Start-Backend([string]$Name) {
    $entry = $services | Where-Object { $_.Name -eq $Name } | Select-Object -First 1
    $jar = Join-Path $projectRoot $entry.Jar
    Assert-LocalExecutableJar $jar
    $serviceEnvironment = $environmentValues.Clone()
    $maximumHeap = if ($entry.Name -eq 'admin') { '2g' } else { '512m' }
    if ($entry.Name -eq 'register') {
        $serviceEnvironment.MYSQL_DB = 'pig_config'
    }
    if ($entry.Name -eq 'admin') {
        # 桌面启动器可能尚未继承新设的用户变量；仅向业务服务传入模型配置，不打印密钥。
        foreach ($key in @('DEEPSEEK_API_KEY','DEEPSEEK_BASE_URL','HY4_API_KEY','HY4_BASE_URL','HY4_MODEL','AI_MODEL_PROVIDER','AI_MODEL_TIMEOUT_SECONDS','AI_IMPORT_MAX_ROWS','AI_MAX_CONCURRENT_REQUESTS','AI_IMPORT_TASK_CONCURRENCY','QUOTATION_JAVA_MAX_HEAP')) {
            $value = [Environment]::GetEnvironmentVariable($key, 'Process')
            if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($key, 'User') }
            if ([string]::IsNullOrWhiteSpace($value)) { $value = [Environment]::GetEnvironmentVariable($key, 'Machine') }
            if (-not [string]::IsNullOrWhiteSpace($value)) { $serviceEnvironment[$key] = $value }
        }
        if ($serviceEnvironment.ContainsKey('QUOTATION_JAVA_MAX_HEAP')) { $maximumHeap = $serviceEnvironment.QUOTATION_JAVA_MAX_HEAP }
        if ($maximumHeap -notmatch '^[1-9][0-9]*[mMgG]$') { throw 'QUOTATION_JAVA_MAX_HEAP must be a heap size such as 1024m or 2g.' }
    }
    Start-LocalService $entry.Name $entry.Port $javaExecutable @('-Xms128m',('-Xmx' + $maximumHeap),'-jar',('"' + $jar + '"')) $projectRoot $serviceEnvironment
}
function Start-Ui {
    $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
    $uiDirectory = Join-Path $projectRoot 'pig-ui'
    if (-not (Test-Path -LiteralPath (Join-Path $uiDirectory 'node_modules/vite/bin/vite.js'))) { throw 'Run pnpm install in pig-ui first.' }
    Start-LocalService 'ui' 8888 $nodeExecutable @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','8888','--strictPort') $uiDirectory @{ VITE_OPEN='false' }
}

# 注册中心就绪后业务与页面可同时启动；业务健康后再同时启动认证与网关。
if ($Service -eq 'all') {
    foreach ($entry in $services) {
        if ($entry.Name -ne 'codegen' -or $IncludeCodegen) {
            Assert-LocalExecutableJar (Join-Path $projectRoot $entry.Jar)
        }
    }
    Write-StartupStage 'Stage 1/3: Redis and registry.'
    Start-Redis
    Start-Backend 'register'
    Wait-LocalPort 'redis' 6379
    Wait-LocalPort 'register' 8848
    Wait-RegistryReady
    Write-StartupStage 'Stage 2/3: quotation service and web page.'
    Start-Backend 'admin'
    Start-Ui
    Wait-LocalPort 'admin' 4000
    Wait-ApplicationReady 'admin' 4000
    Write-StartupStage 'Stage 3/3: authentication and gateway.'
    Start-Backend 'auth'
    Start-Backend 'gateway'
    Wait-LocalPort 'auth' 3000
    Wait-ApplicationReady 'auth' 3000
    Wait-LocalPort 'gateway' 9999
    Wait-ApplicationReady 'gateway' 9999
    Wait-LocalPort 'ui' 8888
    Wait-UiReady
    if ($IncludeCodegen) {
        Start-Backend 'codegen'
        Wait-LocalPort 'codegen' 5003
        Wait-ApplicationReady 'codegen' 5003
    }
} elseif ($Service -eq 'redis') {
    Start-Redis
    Wait-LocalPort 'redis' 6379
} elseif ($Service -eq 'ui') {
    Start-Ui
    Wait-LocalPort 'ui' 8888
    Wait-UiReady
} else {
    Start-Backend $Service
    $entry = $services | Where-Object { $_.Name -eq $Service } | Select-Object -First 1
    Wait-LocalPort $Service $entry.Port
    if ($Service -eq 'register') { Wait-RegistryReady }
    else { Wait-ApplicationReady $Service $entry.Port }
}
Assert-StartedProcessesAlive
Write-StartupStage 'Startup complete.'
if ($Service -in @('all', 'ui')) { Write-Host 'Business UI: http://127.0.0.1:8888' }
} finally { $runtimeLock.Dispose() }
