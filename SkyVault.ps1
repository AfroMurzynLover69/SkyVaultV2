[CmdletBinding()]
param(
    [ValidateSet('run', 'build', 'test', 'clean', 'modules', 'help')]
    [string]$Mode = 'run',

    [switch]$SkipTests,

    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$maven = Join-Path $root 'mvnw.cmd'

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
        throw 'JAVA_HOME is not set. Install JDK 25 or set the JAVA_HOME variable.'
    }
}

function Get-JavaExecutable {
    $fromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $fromHome) { return $fromHome }
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

function Invoke-RunServer {
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

    $lanAddress = Get-LanAddress

    Write-Host ''
    Write-Host '  SkyVault' -ForegroundColor Green
    Write-Host ('  ' + ('=' * 8)) -ForegroundColor Green
    Write-Host "  Starting: $($jar.Name)"
    Write-Host '  The Web module is served automatically by the server.'
    Write-Host ''
    Write-Host '    Home:    https://localhost:8443/'                  -ForegroundColor White
    Write-Host '    Status:  https://localhost:8443/api/status'         -ForegroundColor White
    Write-Host '    Ping:    https://localhost:8443/api/ping'           -ForegroundColor White
    Write-Host '    Health:  https://localhost:8443/actuator/health'    -ForegroundColor White
    if ($lanAddress) {
        Write-Host ''
        Write-Host "    Network: https://$($lanAddress):8443/" -ForegroundColor White
        Write-Host '    Accept the certificate warning once in the browser.' -ForegroundColor DarkGray
    }
    Write-Host ''
    Write-Host '  Stop: Ctrl + C' -ForegroundColor DarkGray
    Write-Host ''

    & (Get-JavaExecutable) -jar $jar.FullName
    exit $LASTEXITCODE
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
    help      this help

  Switches:
    -SkipTests    build without running tests
    -SkipBuild    with "run": start the existing JAR without rebuilding

  Examples:
    .\SkyVault.ps1
    .\SkyVault.ps1 run -SkipBuild
    .\SkyVault.ps1 build -SkipTests
    .\SkyVault.ps1 test
'@
}

Assert-Environment

switch ($Mode) {
    'run' {
        Invoke-RunServer
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
