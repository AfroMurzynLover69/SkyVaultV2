<#
    SkyVault - launcher modułów.

    Ten plik służy WYŁĄCZNIE do uruchamiania modułów projektu.
    Nie zawiera logiki aplikacji - kod jest w katalogach Server\ i Web\.

    Użycie:
        .\SkyVault.ps1                  # zbuduj i uruchom serwer
        .\SkyVault.ps1 run              # to samo co wyżej
        .\SkyVault.ps1 run -SkipBuild   # uruchom bez przebudowy (szybko)
        .\SkyVault.ps1 build            # zbuduj wszystkie moduły
        .\SkyVault.ps1 test             # uruchom testy
        .\SkyVault.ps1 clean            # wyczyść katalogi target
        .\SkyVault.ps1 modules          # pokaż listę modułów
        .\SkyVault.ps1 help             # ta pomoc

    Jeśli PowerShell blokuje skrypty:
        powershell -ExecutionPolicy Bypass -File .\SkyVault.ps1
#>

[CmdletBinding()]
param(
    [ValidateSet('run', 'build', 'test', 'clean', 'modules', 'help')]
    [string]$Mode = 'run',

    # Pomiń testy podczas budowania
    [switch]$SkipTests,

    # Przy trybie "run": nie przebudowuj projektu, uruchom istniejący JAR
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$maven = Join-Path $root 'mvnw.cmd'

# --- Moduły projektu ---
$moduleList = @(
    [pscustomobject]@{ Name = 'Web';    Pom = 'Web\pom.xml';    Role = 'Strona - pliki HTML/CSS/JS' }
    [pscustomobject]@{ Name = 'Server'; Pom = 'Server\pom.xml'; Role = 'Serwer - REST API, limit wspolbieznosci, PostgreSQL' }
)

function Write-Header {
    param([string]$Text)
    Write-Host ''
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('  ' + ('-' * $Text.Length)) -ForegroundColor DarkGray
}

function Assert-Environment {
    if (-not (Test-Path $maven)) {
        throw "Nie znaleziono $maven - uruchom skrypt z katalogu glownego projektu."
    }

    # JAVA_HOME musi byc ustawiony, zeby Maven wrapper i uruchomienie JAR znalazly JDK.
    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    }
    if (-not $env:JAVA_HOME) {
        throw 'JAVA_HOME nie jest ustawiony. Zainstaluj JDK 25 albo ustaw zmienna JAVA_HOME.'
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
            throw "Maven zakonczyl prace z kodem bledu $LASTEXITCODE"
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

function Invoke-RunServer {
    if (-not $SkipBuild) {
        Write-Header 'Budowanie modulow'
        $mavenArguments = @('package')
        if ($SkipTests) { $mavenArguments += '-DskipTests' }
        Invoke-Maven $mavenArguments
    }

    $jar = Get-ServerJar
    if (-not $jar) {
        throw 'Nie znaleziono pliku JAR serwera. Uruchom najpierw: .\SkyVault.ps1 build'
    }

    Write-Host ''
    Write-Host '  SkyVault' -ForegroundColor Green
    Write-Host ('  ' + ('=' * 8)) -ForegroundColor Green
    Write-Host "  Uruchamiam: $($jar.Name)"
    Write-Host '  Modul Web jest serwowany automatycznie przez serwer.'
    Write-Host ''
    Write-Host '    Strona:  http://localhost:8080/'                  -ForegroundColor White
    Write-Host '    Status:  http://localhost:8080/api/status'         -ForegroundColor White
    Write-Host '    Ping:    http://localhost:8080/api/ping'           -ForegroundColor White
    Write-Host '    Health:  http://localhost:8080/actuator/health'    -ForegroundColor White
    Write-Host ''
    Write-Host '  Zatrzymanie: Ctrl + C' -ForegroundColor DarkGray
    Write-Host ''

    & (Get-JavaExecutable) -jar $jar.FullName
    exit $LASTEXITCODE
}

function Show-Modules {
    Write-Header 'Moduly projektu'
    $moduleList |
        Format-Table -AutoSize @{ n = 'Modul'; e = { $_.Name } },
                              @{ n = 'Plik';  e = { $_.Pom } },
                              @{ n = 'Rola';  e = { $_.Role } } |
        Out-String |
        Write-Host
}

function Show-Help {
    Write-Header 'SkyVault'
    Write-Host @'
  Launcher modulow projektu.

    run       zbuduj i uruchom serwer          (domyslne)
    build     zbuduj wszystkie moduly
    test      uruchom testy
    clean     wyczysc katalogi target
    modules   pokaz liste modulow
    help      ta pomoc

  Przelaczniki:
    -SkipTests    buduj bez uruchamiania testow
    -SkipBuild    przy "run": uruchom istniejacy JAR bez przebudowy

  Przyklady:
    .\SkyVault.ps1
    .\SkyVault.ps1 run -SkipBuild
    .\SkyVault.ps1 build -SkipTests
    .\SkyVault.ps1 test
'@
}

# --- Start ---

Assert-Environment

switch ($Mode) {
    'run' {
        Invoke-RunServer
    }
    'build' {
        Write-Header 'Budowanie modulow'
        $mavenArguments = @('clean', 'package')
        if ($SkipTests) { $mavenArguments += '-DskipTests' }
        Invoke-Maven $mavenArguments
        Write-Host '  Gotowe.' -ForegroundColor Green
    }
    'test' {
        Write-Header 'Testy'
        Invoke-Maven @('test')
    }
    'clean' {
        Write-Header 'Czyszczenie'
        Invoke-Maven @('clean')
        Write-Host '  Gotowe.' -ForegroundColor Green
    }
    'modules' {
        Show-Modules
    }
    'help' {
        Show-Help
    }
}
