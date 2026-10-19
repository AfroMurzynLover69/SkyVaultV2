#requires -Version 5.1

$ErrorActionPreference = 'Stop'
$BaseUrl = if ($env:SKYVAULT_BASE_URL) { $env:SKYVAULT_BASE_URL } else { 'https://127.0.0.1:8443' }
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

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Json,
        [string[]]$Form
    )

    $curlArgs = @(
        '-s', '-S', '-k',
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
        Write-Host ("  [FAIL] {0}: expected {1}, got {2}" -f $Name, $Expected, $Actual) -ForegroundColor Red
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

Write-Host "`n=== SkyVault: Zero-Knowledge API test ===`n" -ForegroundColor Cyan

Write-Host "1. Server status"
$status = Invoke-Api -Method 'GET' -Path '/api/status'
Assert-Equal 'HTTP' $status.Status 200
$statusJson = $status.Body | ConvertFrom-Json
Assert-Equal 'concurrency limit' $statusJson.maxConcurrentUsers 256
Write-Host ("  " + ($statusJson | ConvertTo-Json -Compress))

Write-Host "`n2. Registration (crypto bundle)"
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
Assert-True 'session opened immediately' ($registerJson.userId -gt 0)

Write-Host "`n3. Session after registration"
$me = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'session /api/auth/me' $me.Status 200
$meJson = $me.Body | ConvertFrom-Json
Assert-Equal 'account' $meJson.email $email

$duplicate = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $registerBody
Assert-Equal 'duplicate account rejected' $duplicate.Status 409

Write-Host "`n4. Crypto bundle validation"
$badBody = @{
    email              = "bad-$(New-Hex 4)@example.com"
    authVerifier       = New-Base64 16
    passwordSalt       = $passwordSalt
    wrappedMasterKey   = $wrappedKey
    wrappedMasterKeyIv = $wrappedIv
} | ConvertTo-Json
$bad = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $badBody
Assert-Equal 'bad verifier rejected' $bad.Status 400

Write-Host "`n5. Crypto material"
$crypto = Invoke-Api -Method 'GET' -Path "/api/auth/crypto?email=$email"
Assert-Equal 'HTTP' $crypto.Status 200
$cryptoJson = $crypto.Body | ConvertFrom-Json
Assert-Equal 'salt' $cryptoJson.passwordSalt $passwordSalt
Assert-Equal 'wrapped key' $cryptoJson.wrappedMasterKey $wrappedKey

$unknown = Invoke-Api -Method 'GET' -Path '/api/auth/crypto?email=missing@example.com'
Assert-Equal 'unknown address does not reveal itself' $unknown.Status 200

Write-Host "`n6. Encrypted file upload"
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

Write-Host "`n7. File listing"
$list = Invoke-Api -Method 'GET' -Path '/api/files'
Assert-Equal 'HTTP' $list.Status 200
$listJson = @($list.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'file count' $listJson.Count 1
Assert-Equal 'path hash' $listJson[0].pathHash $pathHash
Assert-True 'no plaintext path in the response' (-not ($listJson[0].PSObject.Properties.Name -contains 'virtualPath'))

Write-Host "`n8. Content download"
$download = Invoke-Api -Method 'GET' -Path "/api/files/$pathHash/content"
Assert-Equal 'HTTP' $download.Status 200

Write-Host "`n9. Trash, restore, delete"
$trash = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/trash"
Assert-Equal 'move to trash' $trash.Status 200
$trashList = Invoke-Api -Method 'GET' -Path '/api/files/trash'
$trashJson = @($trashList.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'items in trash' $trashJson.Count 1

$restore = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/restore"
Assert-Equal 'restore' $restore.Status 200

$trashAgain = Invoke-Api -Method 'POST' -Path "/api/files/$pathHash/trash"
Assert-Equal 'trash again' $trashAgain.Status 200
$delete = Invoke-Api -Method 'DELETE' -Path "/api/files/$pathHash"
Assert-Equal 'permanent delete' $delete.Status 204

$afterDelete = Invoke-Api -Method 'GET' -Path '/api/files'
$afterJson = @($afterDelete.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'files after delete' $afterJson.Count 0

Write-Host "`n10. Access control"
$logout = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'logout' $logout.Status 204
$afterLogout = Invoke-Api -Method 'GET' -Path '/api/files'
Assert-Equal 'no access without a session' $afterLogout.Status 401

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "ALL TESTS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("FAILURES: {0}" -f $script:Failures) -ForegroundColor Red
    exit 1
}
