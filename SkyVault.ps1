[CmdletBinding()]
param(
    [ValidateSet('run', 'build', 'test', 'clean', 'modules', 'config', 'help')]
    [string]$Mode = 'run',

    [switch]$SkipTests,

    [switch]$SkipBuild,

    [string]$ConfigFile,

    [switch]$NoLocalConfig
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
. (Join-Path $root 'SkyVault.Config.ps1')
$maven = Join-Path $root 'mvnw.cmd'
$defaultConfigFile = Join-Path $root 'skyvault.config.json'

$moduleList = @(
    [pscustomobject]@{ Name = 'Web';    Pom = 'Web\pom.xml';    Role = 'Web layer - HTML/CSS/JS files' }
    [pscustomobject]@{ Name = 'Server'; Pom = 'Server\pom.xml'; Role = 'Server - REST API, concurrency limit, PostgreSQL' }
)

function Write-Header {
    param([string]$Text)
    Write-Host ''
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * $Text.Length)) -ForegroundColor DarkGray
}

function Assert-Environment {
    if (-not (Test-Path $maven)) {
        throw "$maven not found - run the script from the project root directory."
    }

    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    }
    if (-not $env:JAVA_HOME) {
        $java = Get-Command java -ErrorAction SilentlyContinue
        if (-not $java) {
            throw 'JAVA_HOME is not set and java is not on PATH. Install JDK 25 or set the JAVA_HOME variable.'
        }
    }
}

function Get-JavaExecutable {
    if ($env:JAVA_HOME) {
        $fromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path $fromHome) { return $fromHome }
    }
    return 'java'
}

function Invoke-Maven {
    param([string[]]$MavenArguments)

    Push-Location $root
    try {
        & $maven -B @MavenArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven finished with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-ServerJar {
    $targetDirectory = Join-Path $root 'Server\target'
    if (-not (Test-Path $targetDirectory)) { return $null }

    return Get-ChildItem $targetDirectory -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-LanAddress {
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric |
        Select-Object -First 1
    if (-not $route) { return $null }

    return Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '169.254.*' } |
        Select-Object -First 1 -ExpandProperty IPAddress
}

# ---------------------------------------------------------------------------
# Configuration helpers
# ---------------------------------------------------------------------------

function Get-TotalPhysicalMemoryMb {
    try {
        $bytes = (Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).TotalPhysicalMemory
        if ($bytes) { return [long]($bytes / 1MB) }
    }
    catch {
    }
    return 0
}

function Resolve-KeyStoreLocation {
    param([string]$Value, [string]$BasePath)

    if ([string]::IsNullOrWhiteSpace($Value)) { return $Value }
    if ($Value -match '^(classpath:|file:|https?:)') { return $Value }

    $candidate = $Value
    if (-not [System.IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $BasePath $candidate
    }
    if (-not (Test-Path -LiteralPath $candidate)) {
        return $Value
    }

    $full = [System.IO.Path]::GetFullPath($candidate) -replace '\\', '/'
    if ($full -match '^[A-Za-z]:/') { return "file:///$full" }
    return "file:$full"
}

function Get-EffectiveSettings {
    param($ConfigInfo)

    $config = $ConfigInfo.Config
    $baseDirectory = $ConfigInfo.BaseDirectory

    # --- listener and Tomcat ------------------------------------------------
    $serverHost = [string](Get-EffectiveValue $config 'server.host' 'SERVER_ADDRESS' $null '0.0.0.0')
    $serverPort = ConvertTo-IntValue (Get-EffectiveValue $config 'server.port' 'SERVER_PORT' 'PORT' 8443) 'server.port' 1 65535
    $serverHttps = ConvertTo-BooleanValue (Get-EffectiveValue $config 'server.https' 'SERVER_SSL_ENABLED' $null $true) 'server.https'

    $threadsMax = ConvertTo-IntValue (Get-EffectiveValue $config 'server.threads.max' 'SERVER_TOMCAT_THREADS_MAX' $null 512) 'server.threads.max' 1 10000
    $threadsMinSpare = ConvertTo-IntValue (Get-EffectiveValue $config 'server.threads.minSpare' 'SERVER_TOMCAT_THREADS_MIN_SPARE' $null 16) 'server.threads.minSpare' 0 10000
    if ($threadsMinSpare -gt $threadsMax) {
        throw "server.threads.minSpare ($threadsMinSpare) cannot be greater than server.threads.max ($threadsMax)."
    }

    $maxConnections = ConvertTo-IntValue (Get-EffectiveValue $config 'server.maxConnections' 'SERVER_TOMCAT_MAX_CONNECTIONS' $null 8192) 'server.maxConnections' 1 1000000
    $acceptCount = ConvertTo-IntValue (Get-EffectiveValue $config 'server.acceptCount' 'SERVER_TOMCAT_ACCEPT_COUNT' $null 512) 'server.acceptCount' 0 1000000
    $connectionTimeoutMs = ConvertTo-LongValue (Get-EffectiveValue $config 'server.connectionTimeoutMs' 'SERVER_TOMCAT_CONNECTION_TIMEOUT_MS' $null 20000) 'server.connectionTimeoutMs' 1 3600000
    $keepAliveTimeoutMs = ConvertTo-LongValue (Get-EffectiveValue $config 'server.keepAliveTimeoutMs' 'SERVER_TOMCAT_KEEP_ALIVE_TIMEOUT_MS' $null 20000) 'server.keepAliveTimeoutMs' 0 3600000
    $maxHttpRequestHeaderSize = [string](Get-EffectiveValue $config 'server.maxHttpRequestHeaderSize' 'SERVER_TOMCAT_MAX_HTTP_REQUEST_HEADER_SIZE' $null '16KB')
    $maxHttpFormPostSize = [string](Get-EffectiveValue $config 'server.maxHttpFormPostSize' 'SERVER_TOMCAT_MAX_HTTP_FORM_POST_SIZE' $null '2MB')
    Assert-DataSizeValue $maxHttpRequestHeaderSize 'server.maxHttpRequestHeaderSize'
    Assert-DataSizeValue $maxHttpFormPostSize 'server.maxHttpFormPostSize'

    $maxConcurrentUsers = ConvertTo-IntValue (Get-EffectiveValue $config 'server.maxConcurrentUsers' 'SKYVAULT_SERVER_MAX_CONCURRENT_USERS' 'MAX_CONCURRENT_USERS' 256) 'server.maxConcurrentUsers' 1 100000
    $acquireTimeoutMs = ConvertTo-LongValue (Get-EffectiveValue $config 'server.acquireTimeoutMs' 'SKYVAULT_SERVER_ACQUIRE_TIMEOUT_MS' 'ACQUIRE_TIMEOUT_MS' 2000) 'server.acquireTimeoutMs' 0 3600000
    $gracefulShutdownTimeout = [string](Get-EffectiveValue $config 'server.gracefulShutdownTimeout' 'SHUTDOWN_TIMEOUT' $null '30s')
    Assert-DurationValue $gracefulShutdownTimeout 'server.gracefulShutdownTimeout'

    $compressionEnabled = ConvertTo-BooleanValue (Get-EffectiveValue $config 'server.compression.enabled' 'SERVER_COMPRESSION_ENABLED' $null $true) 'server.compression.enabled'
    $compressionMinSize = [string](Get-EffectiveValue $config 'server.compression.minResponseSize' 'SERVER_COMPRESSION_MIN_RESPONSE_SIZE' $null '2KB')
    Assert-DataSizeValue $compressionMinSize 'server.compression.minResponseSize'
    $compressionMimeRaw = Get-EffectiveValue $config 'server.compression.mimeTypes' 'SERVER_COMPRESSION_MIME_TYPES' $null @('application/json', 'application/javascript', 'text/html', 'text/css', 'text/plain', 'image/svg+xml')
    if ($compressionMimeRaw -is [array]) {
        $compressionMimeTypes = ($compressionMimeRaw -join ',')
    }
    else {
        $compressionMimeTypes = [string]$compressionMimeRaw
    }

    # --- JVM ----------------------------------------------------------------
    $heapMinMb = ConvertTo-IntValue (Get-EffectiveValue $config 'jvm.heapMinMb' 'JVM_HEAP_MIN_MB' $null 512) 'jvm.heapMinMb' 64 262144
    $heapMaxMb = ConvertTo-IntValue (Get-EffectiveValue $config 'jvm.heapMaxMb' 'JVM_HEAP_MAX_MB' $null 2048) 'jvm.heapMaxMb' 128 262144
    if ($heapMinMb -gt $heapMaxMb) {
        throw "jvm.heapMinMb ($heapMinMb) cannot be greater than jvm.heapMaxMb ($heapMaxMb)."
    }

    $activeProcessorsRaw = Get-EffectiveValue $config 'jvm.activeProcessors' 'JVM_ACTIVE_PROCESSORS' $null $null
    $activeProcessors = $null
    if ($null -ne $activeProcessorsRaw -and -not [string]::IsNullOrWhiteSpace([string]$activeProcessorsRaw)) {
        $activeProcessors = ConvertTo-IntValue $activeProcessorsRaw 'jvm.activeProcessors' 1 256
    }

    $garbageCollector = ([string](Get-EffectiveValue $config 'jvm.garbageCollector' 'JVM_GARBAGE_COLLECTOR' $null 'g1')).Trim().ToLowerInvariant()
    if ($garbageCollector -notin @('g1', 'zgc', 'parallel', 'serial', 'default')) {
        throw "jvm.garbageCollector must be one of: g1, zgc, parallel, serial, default (got '$garbageCollector')."
    }

    $directMemoryRaw = Get-EffectiveValue $config 'jvm.directMemoryMb' 'JVM_DIRECT_MEMORY_MB' $null $null
    $directMemoryMb = $null
    if ($null -ne $directMemoryRaw -and -not [string]::IsNullOrWhiteSpace([string]$directMemoryRaw)) {
        $directMemoryMb = ConvertTo-IntValue $directMemoryRaw 'jvm.directMemoryMb' 64 262144
    }

    $exitOnOutOfMemory = ConvertTo-BooleanValue (Get-EffectiveValue $config 'jvm.exitOnOutOfMemory' 'JVM_EXIT_ON_OOM' $null $true) 'jvm.exitOnOutOfMemory'
    $tempDirectory = Resolve-ConfigPath ([string](Get-EffectiveValue $config 'jvm.tempDirectory' 'JAVA_TMP_DIR' $null 'data/tmp')) $baseDirectory 'jvm.tempDirectory'

    $extraJvmArgsRaw = Get-ConfigValue $config 'jvm.extraArgs' @()
    $extraJvmArgs = @()
    if ($null -ne $extraJvmArgsRaw) {
        $extraJvmArgs = @($extraJvmArgsRaw) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    }
    foreach ($argument in $extraJvmArgs) {
        if (-not ([string]$argument).StartsWith('-')) {
            throw "jvm.extraArgs entries must start with '-' (got '$argument')."
        }
    }

    # --- storage ------------------------------------------------------------
    $dataDirectory = Resolve-ConfigPath ([string](Get-EffectiveValue $config 'storage.dataDirectory' 'SKYVAULT_STORAGE_DIRECTORY' 'SKYVAULT_DATA_DIR' 'data/files')) $baseDirectory 'storage.dataDirectory'
    $planStateFile = Resolve-ConfigPath ([string](Get-EffectiveValue $config 'storage.planStateFile' 'SKYVAULT_STORAGE_PLAN_STATE_FILE' 'SKYVAULT_PLAN_FILE' 'data/plans.json')) $baseDirectory 'storage.planStateFile'
    $planStateRefreshMs = ConvertTo-LongValue (Get-EffectiveValue $config 'storage.planStateRefreshMs' 'SKYVAULT_STORAGE_PLAN_STATE_REFRESH_MS' 'PLAN_STATE_REFRESH_MS' 30000) 'storage.planStateRefreshMs' 1000 3600000
    $defaultQuotaGb = ConvertTo-LongValue (Get-EffectiveValue $config 'storage.defaultQuotaGb' 'SKYVAULT_STORAGE_DEFAULT_QUOTA_GB' 'DEFAULT_QUOTA_GB' 5) 'storage.defaultQuotaGb' 1 10240

    # --- database -----------------------------------------------------------
    $dbHost = [string](Get-EffectiveValue $config 'database.host' 'DB_HOST' $null '127.0.0.1')
    $dbPort = ConvertTo-IntValue (Get-EffectiveValue $config 'database.port' 'DB_PORT' $null 5432) 'database.port' 1 65535
    $dbName = [string](Get-EffectiveValue $config 'database.name' 'DB_NAME' $null 'skyvault')
    $dbUser = [string](Get-EffectiveValue $config 'database.user' 'SPRING_DATASOURCE_USERNAME' 'DB_USER' 'skyvault')
    $dbPassword = [string](Get-EffectiveValue $config 'database.password' 'SPRING_DATASOURCE_PASSWORD' 'DB_PASSWORD' 'skyvault')
    $dbPoolSize = ConvertTo-IntValue (Get-EffectiveValue $config 'database.poolSize' 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE' 'DB_POOL_SIZE' 10) 'database.poolSize' 1 1000
    $dbPoolMinIdle = ConvertTo-IntValue (Get-EffectiveValue $config 'database.poolMinIdle' 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE' 'DB_POOL_MIN_IDLE' 2) 'database.poolMinIdle' 0 1000
    if ($dbPoolMinIdle -gt $dbPoolSize) {
        throw "database.poolMinIdle ($dbPoolMinIdle) cannot be greater than database.poolSize ($dbPoolSize)."
    }
    $dbConnectionTimeoutMs = ConvertTo-LongValue (Get-EffectiveValue $config 'database.connectionTimeoutMs' 'SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT' 'DB_CONNECTION_TIMEOUT_MS' 30000) 'database.connectionTimeoutMs' 250 86400000
    $dbIdleTimeoutMs = ConvertTo-LongValue (Get-EffectiveValue $config 'database.idleTimeoutMs' 'SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT' 'DB_IDLE_TIMEOUT_MS' 600000) 'database.idleTimeoutMs' 0 86400000
    $dbMaxLifetimeMs = ConvertTo-LongValue (Get-EffectiveValue $config 'database.maxLifetimeMs' 'SPRING_DATASOURCE_HIKARI_MAX_LIFETIME' 'DB_MAX_LIFETIME_MS' 1800000) 'database.maxLifetimeMs' 0 86400000

    $dbUrlOverride = Get-ProcessEnvironmentValue 'SPRING_DATASOURCE_URL'
    if ($null -ne $dbUrlOverride) {
        $dbUrl = $dbUrlOverride
    }
    else {
        $dbUrl = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"
    }

    # --- TLS ----------------------------------------------------------------
    $tlsKeystore = Resolve-KeyStoreLocation ([string](Get-EffectiveValue $config 'tls.keystore' 'SERVER_SSL_KEY_STORE' $null 'classpath:skyvault-dev.p12')) $baseDirectory
    $tlsPassword = [string](Get-EffectiveValue $config 'tls.keystorePassword' 'SERVER_SSL_KEY_STORE_PASSWORD' 'SSL_KEYSTORE_PASSWORD' 'skyvault')
    $tlsType = [string](Get-EffectiveValue $config 'tls.keystoreType' 'SERVER_SSL_KEY_STORE_TYPE' $null 'PKCS12')
    $tlsAlias = [string](Get-EffectiveValue $config 'tls.keyAlias' 'SERVER_SSL_KEY_ALIAS' $null 'skyvault')
    if ($serverHttps -and [string]::IsNullOrWhiteSpace($tlsKeystore)) {
        throw 'server.https=true requires tls.keystore.'
    }

    # --- sessions -----------------------------------------------------------
    $sessionCookieName = [string](Get-EffectiveValue $config 'session.cookieName' 'SKYVAULT_SESSION_COOKIE_NAME' 'SESSION_COOKIE_NAME' 'skyvault_session')
    $sessionLifetimeDays = ConvertTo-IntValue (Get-EffectiveValue $config 'session.lifetimeDays' 'SKYVAULT_SESSION_LIFETIME_DAYS' 'SESSION_LIFETIME_DAYS' 7) 'session.lifetimeDays' 1 365
    $sessionIdleTimeoutMinutes = ConvertTo-IntValue (Get-EffectiveValue $config 'session.idleTimeoutMinutes' 'SKYVAULT_SESSION_IDLE_TIMEOUT_MINUTES' 'SESSION_IDLE_TIMEOUT_MINUTES' 10) 'session.idleTimeoutMinutes' 1 1440
    $sessionSameSiteRaw = [string](Get-EffectiveValue $config 'session.sameSite' 'SKYVAULT_SESSION_SAME_SITE' 'SESSION_COOKIE_SAME_SITE' 'Lax')
    switch ($sessionSameSiteRaw.Trim().ToLowerInvariant()) {
        'strict' { $sessionSameSite = 'Strict' }
        'lax' { $sessionSameSite = 'Lax' }
        'none' { $sessionSameSite = 'None' }
        default {
            throw "session.sameSite must be Strict, Lax or None (got '$sessionSameSiteRaw')."
        }
    }
    $sessionSecure = ConvertTo-BooleanValue (Get-EffectiveValue $config 'session.secure' 'SKYVAULT_SESSION_SECURE' 'SESSION_COOKIE_SECURE' $true) 'session.secure'
    $sessionHttpOnly = ConvertTo-BooleanValue (Get-EffectiveValue $config 'session.httpOnly' 'SKYVAULT_SESSION_HTTP_ONLY' 'SESSION_COOKIE_HTTP_ONLY' $true) 'session.httpOnly'
    if ($sessionSameSite -eq 'None' -and -not $sessionSecure) {
        throw 'session.sameSite=None requires session.secure=true.'
    }

    # --- logging ------------------------------------------------------------
    $logLevel = [string](Get-EffectiveValue $config 'logging.level' 'LOGGING_LEVEL_ROOT' 'LOG_LEVEL' 'INFO')
    $logFile = Resolve-ConfigPath ([string](Get-EffectiveValue $config 'logging.file' 'LOGGING_FILE_NAME' 'LOG_FILE' 'logs/skyvault.log')) $baseDirectory 'logging.file'
    $logMaxFileSize = [string](Get-EffectiveValue $config 'logging.maxFileSize' 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE' 'LOG_MAX_FILE_SIZE' '10MB')
    $logMaxHistory = ConvertTo-IntValue (Get-EffectiveValue $config 'logging.maxHistory' 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY' 'LOG_MAX_HISTORY' 7) 'logging.maxHistory' 0 3650
    $logTotalSizeCap = [string](Get-EffectiveValue $config 'logging.totalSizeCap' 'LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP' 'LOG_TOTAL_SIZE_CAP' '100MB')
    Assert-DataSizeValue $logMaxFileSize 'logging.maxFileSize'
    Assert-DataSizeValue $logTotalSizeCap 'logging.totalSizeCap'

    # --- backup policy ------------------------------------------------------
    $backup = Get-BackupSettings $ConfigInfo

    # --- create writable directories ---------------------------------------
    foreach ($directory in @($dataDirectory, $tempDirectory)) {
        if (-not (Test-Path -LiteralPath $directory)) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
    }
    foreach ($file in @($planStateFile, $logFile)) {
        $parent = Split-Path -Parent $file
        if ($parent -and -not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
    }

    $physicalMemoryMb = Get-TotalPhysicalMemoryMb
    $processorCount = [Environment]::ProcessorCount

    if ($physicalMemoryMb -gt 0 -and $heapMaxMb -gt [long]($physicalMemoryMb * 0.8)) {
        Write-Warning "jvm.heapMaxMb=$heapMaxMb is more than 80% of physical RAM ($physicalMemoryMb MB). Leave memory for the OS and PostgreSQL."
    }
    if ($null -ne $activeProcessors -and $activeProcessors -gt $processorCount) {
        Write-Warning "jvm.activeProcessors=$activeProcessors is higher than the $processorCount logical processors reported by this machine."
    }
    if ($threadsMax -lt $maxConcurrentUsers) {
        Write-Warning "server.threads.max=$threadsMax is lower than server.maxConcurrentUsers=$maxConcurrentUsers; requests may queue before the semaphore."
    }
    if ($maxConnections -lt $threadsMax) {
        Write-Warning "server.maxConnections=$maxConnections is lower than server.threads.max=$threadsMax."
    }
    if (-not $serverHttps) {
        Write-Warning 'server.https is disabled. Web Crypto works on localhost over HTTP, but not from another computer unless a TLS reverse proxy is used.'
    }

    $scheme = if ($serverHttps) { 'https' } else { 'http' }
    $activeProcessorsText = if ($null -eq $activeProcessors) { "all ($processorCount)" } else { [string]$activeProcessors }

    return [pscustomobject]@{
        ServerHost               = $serverHost
        ServerPort               = $serverPort
        ServerHttps              = $serverHttps
        Scheme                   = $scheme
        ThreadsMax               = $threadsMax
        ThreadsMinSpare          = $threadsMinSpare
        MaxConnections           = $maxConnections
        AcceptCount              = $acceptCount
        ConnectionTimeoutMs      = $connectionTimeoutMs
        KeepAliveTimeoutMs       = $keepAliveTimeoutMs
        MaxHttpRequestHeaderSize = $maxHttpRequestHeaderSize
        MaxHttpFormPostSize      = $maxHttpFormPostSize
        MaxConcurrentUsers       = $maxConcurrentUsers
        AcquireTimeoutMs         = $acquireTimeoutMs
        GracefulShutdownTimeout  = $gracefulShutdownTimeout
        CompressionEnabled       = $compressionEnabled
        CompressionMinSize       = $compressionMinSize
        CompressionMimeTypes     = $compressionMimeTypes
        HeapMinMb                = $heapMinMb
        HeapMaxMb                = $heapMaxMb
        ActiveProcessors         = $activeProcessors
        ActiveProcessorsText     = $activeProcessorsText
        GarbageCollector         = $garbageCollector
        DirectMemoryMb           = $directMemoryMb
        ExitOnOutOfMemory        = $exitOnOutOfMemory
        TempDirectory            = $tempDirectory
        ExtraJvmArgs             = $extraJvmArgs
        DataDirectory            = $dataDirectory
        PlanStateFile            = $planStateFile
        PlanStateRefreshMs       = $planStateRefreshMs
        DefaultQuotaGb           = $defaultQuotaGb
        DbHost                   = $dbHost
        DbPort                   = $dbPort
        DbName                   = $dbName
        DbUser                   = $dbUser
        DbPassword               = $dbPassword
        DbPoolSize               = $dbPoolSize
        DbPoolMinIdle            = $dbPoolMinIdle
        DbConnectionTimeoutMs    = $dbConnectionTimeoutMs
        DbIdleTimeoutMs          = $dbIdleTimeoutMs
        DbMaxLifetimeMs          = $dbMaxLifetimeMs
        DbUrl                    = $dbUrl
        TlsKeystore              = $tlsKeystore
        TlsPassword              = $tlsPassword
        TlsType                  = $tlsType
        TlsAlias                 = $tlsAlias
        SessionCookieName        = $sessionCookieName
        SessionLifetimeDays      = $sessionLifetimeDays
        SessionIdleTimeoutMinutes = $sessionIdleTimeoutMinutes
        SessionSameSite          = $sessionSameSite
        SessionSecure            = $sessionSecure
        SessionHttpOnly          = $sessionHttpOnly
        LogLevel                 = $logLevel
        LogFile                  = $logFile
        LogMaxFileSize           = $logMaxFileSize
        LogMaxHistory            = $logMaxHistory
        LogTotalSizeCap          = $logTotalSizeCap
        Backup                   = $backup
        PhysicalMemoryMb         = $physicalMemoryMb
        ProcessorCount           = $processorCount
    }
}

function Get-JvmArguments {
    param($Settings)

    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add("-Xms$($Settings.HeapMinMb)m")
    $arguments.Add("-Xmx$($Settings.HeapMaxMb)m")

    switch ($Settings.GarbageCollector) {
        'g1' { $arguments.Add('-XX:+UseG1GC') }
        'zgc' { $arguments.Add('-XX:+UseZGC') }
        'parallel' { $arguments.Add('-XX:+UseParallelGC') }
        'serial' { $arguments.Add('-XX:+UseSerialGC') }
    }

    if ($null -ne $Settings.ActiveProcessors) {
        $arguments.Add("-XX:ActiveProcessorCount=$($Settings.ActiveProcessors)")
    }
    if ($null -ne $Settings.DirectMemoryMb) {
        $arguments.Add("-XX:MaxDirectMemorySize=$($Settings.DirectMemoryMb)m")
    }
    if ($Settings.ExitOnOutOfMemory) {
        $arguments.Add('-XX:+ExitOnOutOfMemoryError')
    }

    $arguments.Add("-Djava.io.tmpdir=$($Settings.TempDirectory)")
    foreach ($argument in $Settings.ExtraJvmArgs) {
        $arguments.Add([string]$argument)
    }
    return $arguments.ToArray()
}

function Set-ServerEnvironment {
    param($Settings)

    $values = [ordered]@{
        SERVER_ADDRESS                              = $Settings.ServerHost
        SERVER_PORT                                 = [string]$Settings.ServerPort
        SERVER_SSL_ENABLED                          = ([string]$Settings.ServerHttps).ToLowerInvariant()
        SERVER_SSL_KEY_STORE                        = $Settings.TlsKeystore
        SERVER_SSL_KEY_STORE_PASSWORD               = $Settings.TlsPassword
        SERVER_SSL_KEY_STORE_TYPE                   = $Settings.TlsType
        SERVER_SSL_KEY_ALIAS                        = $Settings.TlsAlias
        SERVER_SHUTDOWN                             = 'graceful'
        SHUTDOWN_TIMEOUT                            = $Settings.GracefulShutdownTimeout
        SERVER_TOMCAT_THREADS_MAX                   = [string]$Settings.ThreadsMax
        SERVER_TOMCAT_THREADS_MIN_SPARE             = [string]$Settings.ThreadsMinSpare
        SERVER_TOMCAT_MAX_CONNECTIONS               = [string]$Settings.MaxConnections
        SERVER_TOMCAT_ACCEPT_COUNT                  = [string]$Settings.AcceptCount
        SERVER_TOMCAT_CONNECTION_TIMEOUT_MS         = "$($Settings.ConnectionTimeoutMs)ms"
        SERVER_TOMCAT_KEEP_ALIVE_TIMEOUT_MS         = "$($Settings.KeepAliveTimeoutMs)ms"
        SERVER_TOMCAT_MAX_HTTP_REQUEST_HEADER_SIZE  = $Settings.MaxHttpRequestHeaderSize
        SERVER_TOMCAT_MAX_HTTP_FORM_POST_SIZE       = $Settings.MaxHttpFormPostSize
        SERVER_COMPRESSION_ENABLED                  = ([string]$Settings.CompressionEnabled).ToLowerInvariant()
        SERVER_COMPRESSION_MIN_RESPONSE_SIZE        = $Settings.CompressionMinSize
        SERVER_COMPRESSION_MIME_TYPES               = $Settings.CompressionMimeTypes
        SKYVAULT_SERVER_MAX_CONCURRENT_USERS        = [string]$Settings.MaxConcurrentUsers
        SKYVAULT_SERVER_ACQUIRE_TIMEOUT_MS          = [string]$Settings.AcquireTimeoutMs
        SKYVAULT_STORAGE_DIRECTORY                  = $Settings.DataDirectory
        SKYVAULT_STORAGE_PLAN_STATE_FILE            = $Settings.PlanStateFile
        SKYVAULT_STORAGE_PLAN_STATE_REFRESH_MS      = [string]$Settings.PlanStateRefreshMs
        SKYVAULT_STORAGE_DEFAULT_QUOTA_GB           = [string]$Settings.DefaultQuotaGb
        SKYVAULT_BACKUP_ENABLED                     = ([string]$Settings.Backup.Enabled).ToLowerInvariant()
        SKYVAULT_BACKUP_DIRECTORY                   = $Settings.Backup.Directory
        SKYVAULT_BACKUP_CRON                        = $Settings.Backup.Cron
        SKYVAULT_BACKUP_KEEP_COPIES                 = [string]$Settings.Backup.Copies
        SKYVAULT_BACKUP_COMPRESSION_LEVEL           = [string]$Settings.Backup.CompressionLevel
        SKYVAULT_BACKUP_DICTIONARY_MB               = [string]$Settings.Backup.DictionaryMb
        SKYVAULT_BACKUP_INCLUDE_DATABASE            = ([string]$Settings.Backup.IncludeDatabase).ToLowerInvariant()
        SKYVAULT_BACKUP_INCLUDE_FILES               = ([string]$Settings.Backup.IncludeFiles).ToLowerInvariant()
        SKYVAULT_BACKUP_INCLUDE_PLAN_STATE          = ([string]$Settings.Backup.IncludePlanState).ToLowerInvariant()
        SKYVAULT_BACKUP_VERIFY_AFTER_CREATE         = ([string]$Settings.Backup.VerifyAfterCreate).ToLowerInvariant()
        SKYVAULT_BACKUP_CATCH_UP                    = ([string]$Settings.Backup.CatchUp).ToLowerInvariant()
        SKYVAULT_BACKUP_PG_DUMP_PATH                = if ($null -eq $Settings.Backup.PgDumpPath) { '' } else { $Settings.Backup.PgDumpPath }
        SPRING_DATASOURCE_URL                       = $Settings.DbUrl
        SPRING_DATASOURCE_USERNAME                  = $Settings.DbUser
        SPRING_DATASOURCE_PASSWORD                  = $Settings.DbPassword
        SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE  = [string]$Settings.DbPoolSize
        SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE       = [string]$Settings.DbPoolMinIdle
        SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT = [string]$Settings.DbConnectionTimeoutMs
        SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT       = [string]$Settings.DbIdleTimeoutMs
        SPRING_DATASOURCE_HIKARI_MAX_LIFETIME       = [string]$Settings.DbMaxLifetimeMs
        SKYVAULT_SESSION_COOKIE_NAME                = $Settings.SessionCookieName
        SKYVAULT_SESSION_LIFETIME_DAYS              = [string]$Settings.SessionLifetimeDays
        SKYVAULT_SESSION_IDLE_TIMEOUT_MINUTES       = [string]$Settings.SessionIdleTimeoutMinutes
        SKYVAULT_SESSION_SAME_SITE                  = $Settings.SessionSameSite
        SKYVAULT_SESSION_SECURE                     = ([string]$Settings.SessionSecure).ToLowerInvariant()
        SKYVAULT_SESSION_HTTP_ONLY                  = ([string]$Settings.SessionHttpOnly).ToLowerInvariant()
        LOGGING_LEVEL_ROOT                          = $Settings.LogLevel
        LOGGING_FILE_NAME                           = $Settings.LogFile
        LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE = $Settings.LogMaxFileSize
        LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY   = [string]$Settings.LogMaxHistory
        LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP = $Settings.LogTotalSizeCap
    }

    foreach ($name in $values.Keys) {
        # An environment variable that is already present is the strongest
        # override. Do not replace it with a value derived from the JSON file.
        $existing = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrEmpty($existing)) {
            [Environment]::SetEnvironmentVariable($name, [string]$values[$name], 'Process')
        }
    }
}

function Show-ConfigSummary {
    param($Settings, $ConfigInfo, $JvmArguments)

    Write-Header 'Effective server configuration'

    if ($ConfigInfo.Found) {
        $configLabel = $ConfigInfo.Path
        if ($ConfigInfo.UsedLocal) {
            $configLabel += "`n                + $($ConfigInfo.LocalPath)"
        }
        Write-Host "  Config:       $configLabel"
    }
    else {
        Write-Host "  Config:       built-in defaults ($($ConfigInfo.Path) not found)" -ForegroundColor Yellow
    }

    Write-Host "  Listener:     $($Settings.Scheme)://$($Settings.ServerHost):$($Settings.ServerPort)  (HTTPS: $($Settings.ServerHttps))"
    Write-Host "  Threads:      $($Settings.ThreadsMax) max / $($Settings.ThreadsMinSpare) min spare"
    Write-Host "  Connections:  $($Settings.MaxConnections) max / $($Settings.AcceptCount) accept queue"
    Write-Host "  API limit:    $($Settings.MaxConcurrentUsers) concurrent, $($Settings.AcquireTimeoutMs) ms wait"
    Write-Host "  JVM:          -Xms$($Settings.HeapMinMb)m -Xmx$($Settings.HeapMaxMb)m, GC $($Settings.GarbageCollector.ToUpperInvariant()), processors $($Settings.ActiveProcessorsText)"
    if ($Settings.PhysicalMemoryMb -gt 0) {
        Write-Host "  Physical RAM: $($Settings.PhysicalMemoryMb) MB"
    }
    Write-Host "  Upload:       no file-size limit (account quota still applies)"
    Write-Host "  Data:         $($Settings.DataDirectory)"
    Write-Host "  Plan state:   $($Settings.PlanStateFile)"
    Write-Host "  Temp:         $($Settings.TempDirectory)"
    Write-Host "  Database:     $($Settings.DbUser)@$($Settings.DbHost):$($Settings.DbPort)/$($Settings.DbName), pool $($Settings.DbPoolSize)-$($Settings.DbPoolMinIdle)"
    Write-Host "  Session:      cookie '$($Settings.SessionCookieName)', $($Settings.SessionLifetimeDays) days, idle logout after $($Settings.SessionIdleTimeoutMinutes) min, SameSite=$($Settings.SessionSameSite)"
    Write-Host "  Logging:      $($Settings.LogLevel) -> $($Settings.LogFile)"
    $backupSchedule = if ($Settings.Backup.Enabled) { "daily at $($Settings.Backup.Time)" } else { 'disabled' }
    Write-Host "  Backup:       $backupSchedule -> $($Settings.Backup.Directory), keep $($Settings.Backup.Copies) copies, LZMA2 level $($Settings.Backup.CompressionLevel)"
    Write-Host "  JVM args:     $($JvmArguments -join ' ')"
}

function Show-Addresses {
    param($Settings)

    $hostForUrl = $Settings.ServerHost
    if ($hostForUrl -in @('0.0.0.0', '::', '[::]', '*')) {
        $hostForUrl = 'localhost'
    }
    $baseUrl = "$($Settings.Scheme)://$($hostForUrl):$($Settings.ServerPort)"

    Write-Host ''
    Write-Host '  Addresses' -ForegroundColor Cyan
    Write-Host "    Home:    $baseUrl/" -ForegroundColor White
    Write-Host "    Status:  $baseUrl/api/status" -ForegroundColor White
    Write-Host "    System:  $baseUrl/api/system" -ForegroundColor White
    Write-Host "    Health:  $baseUrl/actuator/health" -ForegroundColor White

    if ($Settings.ServerHost -in @('0.0.0.0', '::', '[::]', '*')) {
        $lanAddress = Get-LanAddress
        if ($lanAddress) {
            Write-Host ''
            Write-Host "    Network: $($Settings.Scheme)://$($lanAddress):$($Settings.ServerPort)/" -ForegroundColor White
            Write-Host '    Accept the certificate warning once in the browser.' -ForegroundColor DarkGray
        }
    }
}

function Show-ConfigOnly {
    param($Settings, $ConfigInfo, $JvmArguments)

    Show-ConfigSummary $Settings $ConfigInfo $JvmArguments

    $jar = Get-ServerJar
    $jarLabel = if ($jar) { $jar.FullName } else { '<Server\target\*.jar - run .\SkyVault.ps1 build first>' }
    Write-Host ''
    Write-Host '  Java command' -ForegroundColor Cyan
    $quotedArguments = $JvmArguments | ForEach-Object {
        if ([string]$_ -match '\s') { '"{0}"' -f $_ } else { $_ }
    }
    Write-Host "    java $($quotedArguments -join ' ') -jar `"$jarLabel`""
    Write-Host ''
    Write-Host '  Change the JSON file and restart the server to apply changes.' -ForegroundColor DarkGray
    Write-Host '  Environment variables take priority over the JSON file.' -ForegroundColor DarkGray
}

function Show-Modules {
    Write-Header 'Project modules'
    $moduleList |
        Format-Table -AutoSize @{ n = 'Module'; e = { $_.Name } },
                              @{ n = 'File';   e = { $_.Pom } },
                              @{ n = 'Role';   e = { $_.Role } } |
        Out-String |
        Write-Host
}

function Show-Help {
    Write-Header 'SkyVault'
    Write-Host @'
  Project module launcher.

    run       build and run the server        (default)
    build     build all modules
    test      run tests
    clean     clean target directories
    modules   show the module list
    config    show the effective configuration and JVM command
    help      this help

  Switches:
    -SkipTests       build without running tests
    -SkipBuild       with "run": start the existing JAR without rebuilding
    -ConfigFile      use a different JSON config file
    -NoLocalConfig   ignore skyvault.config.local.json

  Configuration:
    skyvault.config.json        base configuration (committed)
    skyvault.config.local.json  optional machine-specific override (gitignored)
    skyvault.config.schema.json JSON Schema for editor completion

  Backups:
    The server itself creates the backups. backup.schedule.time controls the
    daily run (default 00:00), backup.retention.copies keeps the newest
    archives (default 2) and the server writes LZMA2 .7z files to
    backup.directory. Status: GET /api/backup.

  Examples:
    .\SkyVault.ps1
    .\SkyVault.ps1 config
    .\SkyVault.ps1 run -SkipBuild
    .\SkyVault.ps1 build -SkipTests
    .\SkyVault.ps1 test
    .\SkyVault.ps1 config -ConfigFile .\my-server.json
'@
}

$needsToolchain = $Mode -in @('run', 'build', 'test', 'clean')
if ($needsToolchain) {
    Assert-Environment
}

$configPath = $defaultConfigFile
if (-not [string]::IsNullOrWhiteSpace($ConfigFile)) {
    $configPath = $ConfigFile
}

switch ($Mode) {
    'run' {
        $configInfo = Read-SkyVaultConfig -Path $configPath -Root $root -NoLocal:$NoLocalConfig
        $settings = Get-EffectiveSettings $configInfo
        $jvmArguments = Get-JvmArguments $settings

        if (-not $SkipBuild) {
            Write-Header 'Building modules'
            $mavenArguments = @('package')
            if ($SkipTests) { $mavenArguments += '-DskipTests' }
            Invoke-Maven $mavenArguments
        }

        $jar = Get-ServerJar
        if (-not $jar) {
            throw 'Server JAR file not found. Run first: .\SkyVault.ps1 build'
        }

        Write-Host ''
        Write-Host '  SkyVault' -ForegroundColor Green
        Write-Host ('  ' + ('=' * 8)) -ForegroundColor Green
        Write-Host "  Starting: $($jar.Name)"
        Write-Host '  The Web module is served automatically by the server.'

        Show-ConfigSummary $settings $configInfo $jvmArguments
        Show-Addresses $settings

        Write-Host ''
        Write-Host '  Stop: Ctrl + C' -ForegroundColor DarkGray
        Write-Host ''

        Set-ServerEnvironment $settings
        & (Get-JavaExecutable) @jvmArguments -jar $jar.FullName
        exit $LASTEXITCODE
    }
    'config' {
        $configInfo = Read-SkyVaultConfig -Path $configPath -Root $root -NoLocal:$NoLocalConfig
        $settings = Get-EffectiveSettings $configInfo
        $jvmArguments = Get-JvmArguments $settings
        Show-ConfigOnly $settings $configInfo $jvmArguments
    }
    'build' {
        Write-Header 'Building modules'
        $mavenArguments = @('clean', 'package')
        if ($SkipTests) { $mavenArguments += '-DskipTests' }
        Invoke-Maven $mavenArguments
        Write-Host '  Done.' -ForegroundColor Green
    }
    'test' {
        Write-Header 'Tests'
        Invoke-Maven @('test')
    }
    'clean' {
        Write-Header 'Cleaning'
        Invoke-Maven @('clean')
        Write-Host '  Done.' -ForegroundColor Green
    }
    'modules' {
        Show-Modules
    }
    'help' {
        Show-Help
    }
}
