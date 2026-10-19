#requires -Version 5.1
<#
    Test przepĹ‚ywu API SkyVault (Zero-Knowledge).

    Sprawdza cala sciezke: rejestracja -> weryfikacja -> logowanie -> upload ->
    listowanie -> pobranie -> kosz -> przywrocenie -> usuniecie -> wylogowanie.

    Uzywa curl.exe (dostepny w Windows 10/11) i pliku cookies.
#>

$ErrorActionPreference = 'Stop'
$BaseUrl = if ($env:SKYVAULT_BASE_URL) { $env:SKYVAULT_BASE_URL } else { 'http://127.0.0.1:8080' }
$CookieJar = Join-Path $env:TEMP 'skyvault-cookies.txt'
$JsonFile = Join-Path $env:TEMP 'skyvault-body.json'
$BlobFile = Join-Path $env:TEMP 'skyvault-blob.bin'

Remove-Item $CookieJar, $JsonFile, $BlobFile -ErrorAction SilentlyContinue

$script:Failures = 0

function New-RandomBytes([int]$Count) {
    $buffer = New-Object byte[] $Count
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return $buffer
}

function New-Base64([int]$Count) {
    return [Convert]::ToBase64String((New-RandomBytes $Count))
}

function New-Hex([int]$Count) {
    return ((New-RandomBytes $Count) | ForEach-Object { $_.ToString('x2') }) -join ''
}

<#
    Wykonuje zadanie HTTP i zwraca status oraz tresc.
    JSON jest podawany przez plik, zeby nie walczyc z cudzyslowami w PowerShellu.
#>
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Json,
        [string[]]$Form
    )

    $curlArgs = @(
        '-s', '-S',
        '-X', $Method,
        "$BaseUrl$Path",
        '-c', $CookieJar,
        '-b', $CookieJar,
        '-w', "`n%{http_code}"
    )

    if ($Json) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($JsonFile, $Json, $utf8NoBom)
        $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', "@$JsonFile")
    }
    if ($Form) {
        $curlArgs += $Form
    }

    $raw = (& curl.exe @curlArgs) -join "`n"
    $splitAt = $raw.LastIndexOf("`n")
    $status = if ($splitAt -ge 0) { [int]$raw.Substring($splitAt + 1).Trim() } else { 0 }
    $body = if ($splitAt -ge 0) { $raw.Substring(0, $splitAt) } else { $raw }

    return [pscustomobject]@{ Status = $status; Body = $body }
}

function Assert-Equal {
    param([string]$Name, $Actual, $Expected)
    if ("$Actual" -eq "$Expected") {
        Write-Host ("  [OK]   {0} = {1}" -f $Name, $Actual) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0}: oczekiwano {1}, otrzymano {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
        $script:Failures++
    }
}

function Assert-True {
    param([string]$Name, [bool]$Condition)
    if ($Condition) {
        Write-Host ("  [OK]   {0}" -f $Name) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0}" -f $Name) -ForegroundColor Red
        $script:Failures++
    }
}

Write-Host "`n=== SkyVault: test API Zero-Knowledge ===`n" -ForegroundColor Cyan

# --- 1. Status serwera ---
Write-Host "1. Status serwera"
$status = Invoke-Api -Method 'GET' -Path '/api/status'
Assert-Equal 'HTTP' $status.Status 200
$statusJson = $status.Body | ConvertFrom-Json
Assert-Equal 'limit wspolbieznosci' $statusJson.maxConcurrentUsers 256
Write-Host ("  " + ($statusJson | ConvertTo-Json -Compress))

# --- 2. Rejestracja ---
Write-Host "`n2. Rejestracja (pakiet kryptograficzny)"
$email = "test-$(New-Hex 4)@example.com"
$authVerifier = New-Base64 32
$passwordSalt = New-Base64 16
$wrappedKey = New-Base64 48
$wrappedIv = New-Base64 12

$registerBody = @{
    email                 = $email
    authVerifier          = $authVerifier
    passwordSalt          = $passwordSalt
    wrappedMasterKey      = $wrappedKey
    wrappedMasterKeyIv    = $wrappedIv
} | ConvertTo-Json

$register = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $registerBody
Assert-Equal 'HTTP' $register.Status 200
$registerJson = $register.Body | ConvertFrom-Json
Assert-Equal 'email' $registerJson.email $email
Assert-True 'kod weryfikacyjny zwrocony (dev-mode)' ($registerJson.devCode -ne $null)

# --- 3. Weryfikacja e-maila ---
Write-Host "`n3. Weryfikacja kodu"
$verifyBody = @{ email = $email; code = $registerJson.devCode } | ConvertTo-Json
$verify = Invoke-Api -Method 'POST' -Path '/api/auth/verify' -Json $verifyBody
Assert-Equal 'HTTP' $verify.Status 200

$me = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'sesja /api/auth/me' $me.Status 200
$meJson = $me.Body | ConvertFrom-Json
Assert-Equal 'konto' $meJson.email $email

# --- 4. Kontrola pakietu kryptograficznego ---
Write-Host "`n4. Walidacja pakietu kryptograficznego"
$badBody = @{
    email              = "bad-$(New-Hex 4)@example.com"
    authVerifier       = New-Base64 16      # zly rozmiar - ma byc 32 B
    passwordSalt       = $passwordSalt
    wrappedMasterKey   = $wrappedKey
    wrappedMasterKeyIv = $wrappedIv
} | ConvertTo-Json
$bad = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $badBody
Assert-Equal 'odrzucony zly weryfikator' $bad.Status 400

# --- 5. Material kryptograficzny ---
Write-Host "`n5. Material kryptograficzny"
$crypto = Invoke-Api -Method 'GET' -Path "/api/auth/crypto?email=$email"
Assert-Equal 'HTTP' $crypto.Status 200
$cryptoJson = $crypto.Body | ConvertFrom-Json
Assert-Equal 'sol' $cryptoJson.passwordSalt $passwordSalt
Assert-Equal 'zapakowany klucz' $cryptoJson.wrappedMasterKey $wrappedKey

$unknown = Invoke-Api -Method 'GET' -Path '/api/auth/crypto?email=nieistnieje@example.com'
Assert-Equal 'nieistniejacy adres nie zdradza sie' $unknown.Status 200

# --- 6. Upload pliku ---
Write-Host "`n6. Upload zaszyfrowanego pliku"
$blob = New-RandomBytes 4096
[System.IO.File]::WriteAllBytes($BlobFile, $blob)
$pathHash = New-Hex 32
$encryptedPath = New-Base64 96
$pathIv = New-Base64 12

$upload = Invoke-Api -Method 'POST' -Path '/api/files' -Form @(
    '-F', "pathHash=$pathHash",
    '-F', "encryptedVirtualPath=$encryptedPath",
    '-F', "virtualPathIv=$pathIv",
    '-F', 'sizeBytes=4096',
    '-F', "blob=@$BlobFile;type=application/octet-stream"
)
Assert-Equal 'HTTP' $upload.Status 200

# --- 7. Listowanie ---
Write-Host "`n7. Listowanie plikow"
$list = Invoke-Api -Method 'GET' -Path '/api/files'
Assert-Equal 'HTTP' $list.Status 200
$listJson = @($list.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'liczba plikow' $listJson.Count 1
Assert-Equal 'skrot sciezki' $listJson[0].pathHash $pathHash
Assert-True 'brak jawnej sciezki w odpowiedzi' (-not ($listJson[0].PSObject.Properties.Name -contains 'virtualPath'))

# --- 8. Pobranie ---
Write-Host "`n8. Pobranie zawartosci"
$download = Invoke-Api -Method 'GET' -Path "/api/files/$pathHash/content"
Assert-Equal 'HTTP' $download.Status 200

# --- 9. Kosz ---
Write-Host "`n9. Kosz, przywrocenie, usuniecie"
$trash = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/trash"
Assert-Equal 'przeniesienie do kosza' $trash.Status 200
$trashList = Invoke-Api -Method 'GET' -Path '/api/files/trash'
$trashJson = @($trashList.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'elementow w koszu' $trashJson.Count 1

$restore = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/restore"
Assert-Equal 'przywrocenie' $restore.Status 200

$trashAgain = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/trash"
Assert-Equal 'ponowny kosz' $trashAgain.Status 200
$delete = Invoke-Api -Method 'DELETE' -Path "/api/files/$pathHash"
Assert-Equal 'trwale usuniecie' $delete.Status 204

$afterDelete = Invoke-Api -Method 'GET' -Path '/api/files'
$afterJson = @($afterDelete.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'plikow po usunieciu' $afterJson.Count 0

# --- 10. Kontrola dostepu ---
Write-Host "`n10. Kontrola dostepu"
$logout = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'wylogowanie' $logout.Status 204
$afterLogout = Invoke-Api -Method 'GET' -Path '/api/files'
Assert-Equal 'brak dostepu bez sesji' $afterLogout.Status 401

# --- Podsumowanie ---
Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "WSZYSTKIE TESTY PRZESZLY" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("BLEDY: {0}" -f $script:Failures) -ForegroundColor Red
    exit 1
}

