<#
    SkyVault - test współbieżności.

    Wysyła serię RÓWNOLEGŁYCH żądań do serwera i sprawdza, czy limit jednoczesnych
    użytkowników działa: nadwyżka powinna dostać HTTP 429 Too Many Requests.

    Dlaczego osobny plik, a nie przycisk na stronie?
    Przeglądarka otwiera do jednego serwera tylko kilka połączeń naraz, więc z poziomu
    strony nie da się zapełnić limitu 256 miejsc. Ten skrypt używa HttpClient
    i prawdziwych równoległych połączeń.

    Uwaga: DelayMs musi być WIĘKSZY niż acquire-timeout-ms serwera (domyślnie 2000 ms),
    inaczej nadwyżka doczeka się wolnego miejsca i dostanie 200 zamiast 429.

    Użycie:
        .\SkyVault-loadtest.ps1
        .\SkyVault-loadtest.ps1 -Requests 40 -DelayMs 3000
        .\SkyVault-loadtest.ps1 -Requests 320 -DelayMs 3000
#>

[CmdletBinding()]
param(
    # Ile żądań wysłać równolegle
    [int]$Requests = 320,

    # Opóźnienie po stronie serwera (ms). Musi być > acquire-timeout-ms.
    [int]$DelayMs = 3000,

    # Adres serwera
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

# Domyślny limit połączeń .NET to 2 - bez podniesienia żądania szłyby po kolei.
[System.Net.ServicePointManager]::DefaultConnectionLimit = 1024

$uri = "$BaseUrl/api/ping?delayMs=$DelayMs"

try {
    $before = Invoke-RestMethod -Uri "$BaseUrl/api/status" -TimeoutSec 10
}
catch {
    throw "Serwer nie odpowiada pod $BaseUrl. Uruchom najpierw: .\SkyVault.ps1"
}

Write-Host ''
Write-Host '  Test wspolbieznosci SkyVault' -ForegroundColor Cyan
Write-Host ('  ' + ('-' * 28)) -ForegroundColor DarkGray
Write-Host "  Limit serwera:   $($before.maxConcurrentUsers) uzytkownikow"
Write-Host "  Zadania:         $Requests"
Write-Host "  Opoznienie:      $DelayMs ms"
Write-Host ''

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(120)

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

$tasks = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $Requests; $i++) {
    [void]$tasks.Add($client.GetAsync($uri))
}

[System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks.ToArray())
$stopwatch.Stop()

$codes = @{}
$networkErrors = 0

foreach ($task in $tasks) {
    try {
        $code = [int]$task.Result.StatusCode
        if ($codes.ContainsKey($code)) { $codes[$code]++ } else { $codes[$code] = 1 }
    }
    catch {
        $networkErrors++
    }
}

$client.Dispose()

Write-Host ("  Czas calkowity:  {0:N0} ms" -f $stopwatch.Elapsed.TotalMilliseconds)
Write-Host ''

foreach ($code in ($codes.Keys | Sort-Object)) {
    $colour = if ($code -eq 200) { 'Green' } elseif ($code -eq 429) { 'Yellow' } else { 'Red' }
    Write-Host ("    HTTP {0}: {1,5}" -f $code, $codes[$code]) -ForegroundColor $colour
}
if ($networkErrors -gt 0) {
    Write-Host ("    Bledy sieci: {0}" -f $networkErrors) -ForegroundColor Red
}

Write-Host ''
Write-Host '  Stan serwera po tescie' -ForegroundColor Cyan
Write-Host ('  ' + ('-' * 28)) -ForegroundColor DarkGray

$after = Invoke-RestMethod -Uri "$BaseUrl/api/status" -TimeoutSec 10

Write-Host ("    szczyt obciazenia:  {0}" -f $after.peakActiveUsers)
Write-Host ("    odrzucone razem:    {0}" -f $after.rejectedTotal)
Write-Host ("    aktywne teraz:      {0}" -f $after.activeUsers)
Write-Host ''

if ($after.peakActiveUsers -gt $after.maxConcurrentUsers) {
    Write-Host '  WYNIK: BLAD - szczyt przekroczyl limit!' -ForegroundColor Red
    exit 1
}

if ($codes.ContainsKey(429)) {
    Write-Host '  WYNIK: OK - limit zadzialal, nadwyzka odrzucona kodem 429.' -ForegroundColor Green
}
else {
    Write-Host '  WYNIK: limit nie zostal osiagniety (za malo zadan albo DelayMs <= acquire-timeout-ms).' -ForegroundColor Yellow
}
Write-Host ''
