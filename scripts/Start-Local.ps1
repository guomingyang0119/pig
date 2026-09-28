[CmdletBinding()]
param(
    [ValidateSet('all', 'redis', 'register', 'admin', 'auth', 'gateway', 'ui')]
    [string]$Service = 'all',
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot 'work/local'
$logDirectory = Join-Path $runtimeDirectory 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$rootPomText = [IO.File]::ReadAllText((Join-Path $projectRoot 'pom.xml'), [Text.Encoding]::UTF8)
$nacosUsernameMatch = [regex]::Match($rootPomText, '<nacos\.username>\s*([^<]+?)\s*</nacos\.username>')
$nacosPasswordMatch = [regex]::Match($rootPomText, '<nacos\.password>\s*([^<]+?)\s*</nacos\.password>')
if (-not $nacosUsernameMatch.Success -or -not $nacosPasswordMatch.Success) {
    throw 'Missing Nacos credentials in the cloud Maven profile.'
}
$nacosUsername = $nacosUsernameMatch.Groups[1].Value.Trim()
$nacosPassword = $nacosPasswordMatch.Groups[1].Value.Trim()

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

function Test-LocalPort([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $attempt = $client.ConnectAsync('127.0.0.1', $Port)
        return $attempt.Wait(300) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}

function Wait-RegistryReady {
    # Nacos can bind its HTTP port before the config service and gRPC are ready.
    $deadline = (Get-Date).AddSeconds(100)
    while ((Get-Date) -lt $deadline) {
        try {
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
        Start-Sleep -Seconds 1
    }
    throw "Registry is not ready to serve configuration. Check $logDirectory/register.out.log"
}

function Wait-ApplicationReady([string]$Name, [int]$Port) {
    # Spring opens the TCP port before database, Redis and service discovery are fully ready.
    $uri = "http://127.0.0.1:$Port/actuator/health"
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
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

function Wait-UiReady {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
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

function Start-LocalService([string]$Name, [int]$Port, [string]$Executable, [string[]]$Arguments,
                            [string]$WorkingDirectory, [hashtable]$EnvironmentValues) {
    if (Test-LocalPort $Port) { Write-Host "$Name : port $Port already listening; reusing existing process."; return }
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
    $deadline = (Get-Date).AddSeconds(100)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalPort $Port) { Write-Host "$Name : ready on port $Port (PID $($process.Id))."; return }
        $process.Refresh()
        if ($process.HasExited) { throw "$Name exited. Check $logDirectory/$Name.out.log and $Name.err.log" }
        Start-Sleep -Seconds 1
    }
    throw "$Name has not opened port $Port yet. Check $logDirectory/$Name.out.log"
}

if ($Build) {
    Push-Location $projectRoot
    try {
        & mvn -B -ntp -pl pig-register,pig-gateway,pig-auth,pig-upms/pig-upms-biz -am package '-DskipTests' '-Ddocker.skip=true'
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
    } finally { Pop-Location }
}

$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$mysqlUser = Get-RegisterValue 'db.user'
$mysqlPassword = Get-RegisterValue 'db.password'
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

if ($Service -in @('all','redis')) {
    $redisExecutable = Join-Path $runtimeDirectory 'redis-8.10.2/Redis-8.10.2-Windows-x64-msys2/redis-server.exe'
    $redisDirectory = Join-Path $runtimeDirectory 'redis-data'
    New-Item -ItemType Directory -Path $redisDirectory -Force | Out-Null
    Start-LocalService 'redis' 6379 $redisExecutable @('--bind','127.0.0.1','--port','6379','--protected-mode','yes','--appendonly','yes','--save','60','1') $redisDirectory @{}
}

$services = @(
    @{ Name='register'; Port=8848; Jar='pig-register/target/pig-register.jar' },
    @{ Name='admin'; Port=4000; Jar='pig-upms/pig-upms-biz/target/pig-upms-biz.jar' },
    @{ Name='auth'; Port=3000; Jar='pig-auth/target/pig-auth.jar' },
    @{ Name='gateway'; Port=9999; Jar='pig-gateway/target/pig-gateway.jar' }
)
foreach ($entry in $services) {
    if ($Service -notin @('all', $entry.Name)) { continue }
    $jar = Join-Path $projectRoot $entry.Jar
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar. Run this script with -Build first." }
    $serviceEnvironment = $environmentValues.Clone()
    if ($entry.Name -eq 'register') { $serviceEnvironment.MYSQL_DB = 'pig_config' }
    Start-LocalService $entry.Name $entry.Port $javaExecutable @('-Xms128m','-Xmx512m','-jar',('"' + $jar + '"')) $projectRoot $serviceEnvironment
    if ($entry.Name -eq 'register') { Wait-RegistryReady }
    else { Wait-ApplicationReady $entry.Name $entry.Port }
}
if ($Service -in @('all','ui')) {
    $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
    $uiDirectory = Join-Path $projectRoot 'pig-ui'
    if (-not (Test-Path -LiteralPath (Join-Path $uiDirectory 'node_modules/vite/bin/vite.js'))) { throw 'Run pnpm install in pig-ui first.' }
    Start-LocalService 'ui' 8888 $nodeExecutable @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','8888','--strictPort') $uiDirectory @{ VITE_OPEN='false' }
    Wait-UiReady
    Write-Host 'Business UI: http://127.0.0.1:8888'
}
