[CmdletBinding()]
param(
    [int]$Requests = 320,

    [int]$DelayMs = 3000,

    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

[System.Net.ServicePointManager]::DefaultConnectionLimit = 1024

$uri = "$BaseUrl/api/ping?delayMs=$DelayMs"

try {
    $before = Invoke-RestMethod -Uri "$BaseUrl/api/status" -TimeoutSec 10
}
catch {
    throw "The server is not responding at $BaseUrl. Run first: .\SkyVault.ps1"
}

Write-Host ''
Write-Host '  SkyVault concurrency test' -ForegroundColor Cyan
Write-Host ('  ' + ('-' * 28)) -ForegroundColor DarkGray
Write-Host "  Server limit:    $($before.maxConcurrentUsers) users"
Write-Host "  Requests:        $Requests"
Write-Host "  Delay:           $DelayMs ms"
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

Write-Host ("  Total time:      {0:N0} ms" -f $stopwatch.Elapsed.TotalMilliseconds)
Write-Host ''

foreach ($code in ($codes.Keys | Sort-Object)) {
    $colour = if ($code -eq 200) { 'Green' } elseif ($code -eq 429) { 'Yellow' } else { 'Red' }
    Write-Host ("    HTTP {0}: {1,5}" -f $code, $codes[$code]) -ForegroundColor $colour
}
if ($networkErrors -gt 0) {
    Write-Host ("    Network errors: {0}" -f $networkErrors) -ForegroundColor Red
}

Write-Host ''
Write-Host '  Server state after the test' -ForegroundColor Cyan
Write-Host ('  ' + ('-' * 28)) -ForegroundColor DarkGray

$after = Invoke-RestMethod -Uri "$BaseUrl/api/status" -TimeoutSec 10

Write-Host ("    peak load:       {0}" -f $after.peakActiveUsers)
Write-Host ("    rejected total:  {0}" -f $after.rejectedTotal)
Write-Host ("    active now:      {0}" -f $after.activeUsers)
Write-Host ''

if ($after.peakActiveUsers -gt $after.maxConcurrentUsers) {
    Write-Host '  RESULT: FAIL - the peak exceeded the limit!' -ForegroundColor Red
    exit 1
}

if ($codes.ContainsKey(429)) {
    Write-Host '  RESULT: OK - the limit worked, the excess was rejected with code 429.' -ForegroundColor Green
}
else {
    Write-Host '  RESULT: the limit was not reached (too few requests or DelayMs <= acquire-timeout-ms).' -ForegroundColor Yellow
}
Write-Host ''
