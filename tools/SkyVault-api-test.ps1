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
$recoveryVerifier = New-Base64 32
$passwordSalt = New-Base64 16
$wrappedKey = New-Base64 48
$wrappedIv = New-Base64 12

$registerBody = @{
    email                 = $email
    authVerifier          = $authVerifier
    recoveryVerifier      = $recoveryVerifier
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
Assert-True 'no encrypted settings yet' (-not $meJson.encryptedSettings)
Assert-Equal 'new plan is active' $meJson.planActive $true
Assert-True 'plan start date returned' ([bool]$meJson.planStartedAt)
Assert-True 'plan expiry date returned' ([bool]$meJson.planExpiresAt)
$planDays = ([datetime]$meJson.planExpiresAt - [datetime]$meJson.planStartedAt).TotalDays
Assert-True 'plan lasts 30 days' ($planDays -gt 29.9 -and $planDays -lt 30.1)

$settingsCiphertext = New-Base64 64
$settingsIv = New-Base64 12
$settingsUpdate = Invoke-Api -Method 'POST' -Path '/api/auth/settings' -Json (@{
    encryptedSettings = $settingsCiphertext
    settingsIv       = $settingsIv
} | ConvertTo-Json)
Assert-Equal 'encrypted settings saved' $settingsUpdate.Status 204
$meWithSettings = Invoke-Api -Method 'GET' -Path '/api/auth/me'
$meWithSettingsJson = $meWithSettings.Body | ConvertFrom-Json
Assert-Equal 'settings ciphertext stored on the account' $meWithSettingsJson.encryptedSettings $settingsCiphertext
Assert-Equal 'settings IV stored on the account' $meWithSettingsJson.settingsIv $settingsIv

$badSettingsIv = Invoke-Api -Method 'POST' -Path '/api/auth/settings' -Json (@{
    encryptedSettings = $settingsCiphertext
    settingsIv       = New-Base64 16
} | ConvertTo-Json)
Assert-Equal 'settings with a bad IV rejected' $badSettingsIv.Status 400

Assert-Equal 'default plan' $meJson.plan 'free'
$planUpdate = Invoke-Api -Method 'POST' -Path '/api/auth/plan' -Json (@{ plan = 'plus' } | ConvertTo-Json)
Assert-Equal 'plan saved' $planUpdate.Status 204
$meWithPlan = Invoke-Api -Method 'GET' -Path '/api/auth/me'
$meWithPlanJson = $meWithPlan.Body | ConvertFrom-Json
Assert-Equal 'plan stored on the account' $meWithPlanJson.plan 'plus'
Assert-Equal 'plan storage limit' $meWithPlanJson.quotaBytes (200GB)
$unknownPlan = Invoke-Api -Method 'POST' -Path '/api/auth/plan' -Json (@{ plan = 'unlimited' } | ConvertTo-Json)
Assert-Equal 'unknown plan rejected' $unknownPlan.Status 400
$freePlan = Invoke-Api -Method 'POST' -Path '/api/auth/plan' -Json (@{ plan = 'free' } | ConvertTo-Json)
Assert-Equal 'plan switched back to free' $freePlan.Status 204

$planFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'data\plans.json'
Assert-True 'plain-text plan state file exists' (Test-Path $planFile)
if (Test-Path $planFile) {
    $planState = (Get-Content $planFile -Raw) | ConvertFrom-Json
    $planEntry = $planState.accounts | Where-Object { $_.email -eq $email }
    Assert-Equal 'plan state stores the plan' $planEntry.plan 'free'
    Assert-True 'plan state stores both dates' ([bool]$planEntry.planStartedAt -and [bool]$planEntry.planExpiresAt)
    $planFileText = Get-Content $planFile -Raw
    Assert-True 'plan state has no verifier or key material' (-not ($planFileText -match 'authVerifier|wrappedMasterKey|passwordSalt'))
}

Write-Host "`n3b. Plan expiry disables the account"
$psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
if (-not $psql) {
    $psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
}
if (Test-Path $psql) {
    $env:PGPASSWORD = 'skyvault'
    & $psql -h 127.0.0.1 -U skyvault -d skyvault -t -A -c "UPDATE app_user SET plan_started_at = now() - interval '31 days', plan_expires_at = now() - interval '1 day' WHERE email = '$email';" | Out-Null
    Assert-True 'database plan expired for the test' ($LASTEXITCODE -eq 0)
    Start-Sleep -Milliseconds 400

    $expiredMe = Invoke-Api -Method 'GET' -Path '/api/auth/me'
    $expiredMeJson = $expiredMe.Body | ConvertFrom-Json
    Assert-Equal 'expired plan reported inactive' $expiredMeJson.planActive $false

    $lockedFiles = Invoke-Api -Method 'GET' -Path '/api/files'
    Assert-Equal 'file access blocked' $lockedFiles.Status 403
    $lockedJson = $lockedFiles.Body | ConvertFrom-Json
    Assert-Equal 'plan expired code' $lockedJson.code 'PLAN_EXPIRED'

    $renew = Invoke-Api -Method 'POST' -Path '/api/auth/plan' -Json (@{ plan = 'free' } | ConvertTo-Json)
    Assert-Equal 'expired plan renewed' $renew.Status 204
    $renewedMe = Invoke-Api -Method 'GET' -Path '/api/auth/me'
    $renewedMeJson = $renewedMe.Body | ConvertFrom-Json
    Assert-Equal 'renewed plan active again' $renewedMeJson.planActive $true

    $unlockedFiles = Invoke-Api -Method 'GET' -Path '/api/files'
    Assert-Equal 'file access restored' $unlockedFiles.Status 200
} else {
    Write-Host '  [SKIP] psql not found; expiry test skipped' -ForegroundColor Yellow
}

$duplicate = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $registerBody
Assert-Equal 'duplicate account rejected' $duplicate.Status 409

Write-Host "`n4. Crypto bundle validation"
$badBody = @{
    email              = "bad-$(New-Hex 4)@example.com"
    authVerifier       = New-Base64 16
    recoveryVerifier   = $recoveryVerifier
    passwordSalt       = $passwordSalt
    wrappedMasterKey   = $wrappedKey
    wrappedMasterKeyIv = $wrappedIv
} | ConvertTo-Json
$bad = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $badBody
Assert-Equal 'bad verifier rejected' $bad.Status 400

$noRecoveryBody = @{
    email              = "bad-$(New-Hex 4)@example.com"
    authVerifier       = New-Base64 32
    passwordSalt       = $passwordSalt
    wrappedMasterKey   = $wrappedKey
    wrappedMasterKeyIv = $wrappedIv
} | ConvertTo-Json
$noRecovery = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $noRecoveryBody
Assert-Equal 'missing recovery verifier rejected' $noRecovery.Status 400

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

Write-Host "`n10. Change master password"
$wrongChangeBody = @{
    currentAuthVerifier = New-Base64 32
    authVerifier        = New-Base64 32
    passwordSalt        = New-Base64 16
    wrappedMasterKey    = New-Base64 48
    wrappedMasterKeyIv  = New-Base64 12
} | ConvertTo-Json
$wrongChange = Invoke-Api -Method 'POST' -Path '/api/auth/password' -Json $wrongChangeBody
Assert-Equal 'wrong current password rejected' $wrongChange.Status 400

$newVerifier = New-Base64 32
$newSalt = New-Base64 16
$newWrappedKey = New-Base64 48
$newWrappedIv = New-Base64 12

$changeBody = @{
    currentAuthVerifier = $authVerifier
    authVerifier        = $newVerifier
    passwordSalt        = $newSalt
    wrappedMasterKey    = $newWrappedKey
    wrappedMasterKeyIv  = $newWrappedIv
} | ConvertTo-Json
$change = Invoke-Api -Method 'POST' -Path '/api/auth/password' -Json $changeBody
Assert-Equal 'HTTP' $change.Status 204

$newCrypto = Invoke-Api -Method 'GET' -Path "/api/auth/crypto?email=$email"
$newCryptoJson = $newCrypto.Body | ConvertFrom-Json
Assert-Equal 'new salt' $newCryptoJson.passwordSalt $newSalt
Assert-Equal 'new wrapped key' $newCryptoJson.wrappedMasterKey $newWrappedKey

$meAfterChange = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'current session refreshed' $meAfterChange.Status 200

$logoutAfterChange = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'logout after password change' $logoutAfterChange.Status 204

$oldLoginBody = @{ email = $email; authVerifier = $authVerifier } | ConvertTo-Json
$oldLogin = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $oldLoginBody
Assert-Equal 'old password rejected' $oldLogin.Status 401

$newLoginBody = @{
    email            = $email
    authVerifier     = $newVerifier
    recoveryVerifier = $recoveryVerifier
} | ConvertTo-Json
$newLogin = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $newLoginBody
Assert-Equal 'new password accepted' $newLogin.Status 200

Write-Host "`n11. Access control"
$logout = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'logout' $logout.Status 204
$afterLogout = Invoke-Api -Method 'GET' -Path '/api/files'
Assert-Equal 'no access without a session' $afterLogout.Status 401
$anonymousChange = Invoke-Api -Method 'POST' -Path '/api/auth/password' -Json $changeBody
Assert-Equal 'password change requires a session' $anonymousChange.Status 401

Write-Host "`n12. Emergency password recovery"
$recoveryNewVerifier = New-Base64 32
$recoveryNewSalt = New-Base64 16
$recoveryNewWrappedKey = New-Base64 48
$recoveryNewWrappedIv = New-Base64 12

$wrongRecoveryBody = @{
    email              = $email
    recoveryVerifier   = New-Base64 32
    authVerifier       = $recoveryNewVerifier
    passwordSalt       = $recoveryNewSalt
    wrappedMasterKey   = $recoveryNewWrappedKey
    wrappedMasterKeyIv = $recoveryNewWrappedIv
} | ConvertTo-Json
$wrongRecovery = Invoke-Api -Method 'POST' -Path '/api/auth/recover' -Json $wrongRecoveryBody
Assert-Equal 'wrong recovery key rejected' $wrongRecovery.Status 401

$recoverBody = @{
    email              = $email
    recoveryVerifier   = $recoveryVerifier
    authVerifier       = $recoveryNewVerifier
    passwordSalt       = $recoveryNewSalt
    wrappedMasterKey   = $recoveryNewWrappedKey
    wrappedMasterKeyIv = $recoveryNewWrappedIv
} | ConvertTo-Json
$recover = Invoke-Api -Method 'POST' -Path '/api/auth/recover' -Json $recoverBody
Assert-Equal 'HTTP' $recover.Status 200

$meAfterRecovery = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'recovery opens a session' $meAfterRecovery.Status 200

$logoutAfterRecovery = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'logout after recovery' $logoutAfterRecovery.Status 204

$recoveredCrypto = Invoke-Api -Method 'GET' -Path "/api/auth/crypto?email=$email"
$recoveredCryptoJson = $recoveredCrypto.Body | ConvertFrom-Json
Assert-Equal 'recovery stores a new salt' $recoveredCryptoJson.passwordSalt $recoveryNewSalt
Assert-Equal 'recovery stores a new wrapped key' $recoveredCryptoJson.wrappedMasterKey $recoveryNewWrappedKey

$passwordBeforeRecovery = @{ email = $email; authVerifier = $newVerifier } | ConvertTo-Json
$loginBeforeRecovery = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $passwordBeforeRecovery
Assert-Equal 'password from before recovery rejected' $loginBeforeRecovery.Status 401

$passwordAfterRecovery = @{ email = $email; authVerifier = $recoveryNewVerifier } | ConvertTo-Json
$loginAfterRecovery = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $passwordAfterRecovery
Assert-Equal 'password from recovery accepted' $loginAfterRecovery.Status 200

$logoutFinal = Invoke-Api -Method 'POST' -Path '/api/auth/logout'
Assert-Equal 'logout' $logoutFinal.Status 204

Write-Host "`n13. Delete account and start over"
$startOverLoginBody = @{ email = $email; authVerifier = $recoveryNewVerifier } | ConvertTo-Json
$startOverLogin = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $startOverLoginBody
Assert-Equal 'sign in before deletion' $startOverLogin.Status 200
$startOverUserId = ($startOverLogin.Body | ConvertFrom-Json).userId

$orphanBlob = New-RandomBytes 1024
[System.IO.File]::WriteAllBytes($BlobFile, $orphanBlob)
$orphanHash = New-Hex 32
$orphanUpload = Invoke-Api -Method 'POST' -Path '/api/files' -Form @(
    '-F', "pathHash=$orphanHash",
    '-F', "encryptedVirtualPath=$(New-Base64 96)",
    '-F', "virtualPathIv=$(New-Base64 12)",
    '-F', 'sizeBytes=1024',
    '-F', "blob=@$BlobFile;type=application/octet-stream"
)
Assert-Equal 'file uploaded before deletion' $orphanUpload.Status 200
$userBlobDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) "data\files\u$startOverUserId"
Assert-True 'encrypted blob exists before deletion' (Test-Path $userBlobDirectory)

$wrongDeleteBody = @{ currentAuthVerifier = New-Base64 32 } | ConvertTo-Json
$wrongDelete = Invoke-Api -Method 'POST' -Path '/api/auth/account/delete' -Json $wrongDeleteBody
Assert-Equal 'wrong password does not delete the account' $wrongDelete.Status 400
$meAfterWrongDelete = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'account still exists after a rejected deletion' $meAfterWrongDelete.Status 200

$deleteBody = @{ currentAuthVerifier = $recoveryNewVerifier } | ConvertTo-Json
$delete = Invoke-Api -Method 'POST' -Path '/api/auth/account/delete' -Json $deleteBody
Assert-Equal 'account deleted' $delete.Status 204
Assert-True 'encrypted blobs removed with the account' (-not (Test-Path $userBlobDirectory))
if (Test-Path $psql) {
    $orphanRows = & $psql -h 127.0.0.1 -U skyvault -d skyvault -t -A -c "SELECT count(*) FROM stored_file WHERE user_id = $startOverUserId;"
    Assert-Equal 'file rows removed with the account' $orphanRows.Trim() 0
}

$afterDeleteMe = Invoke-Api -Method 'GET' -Path '/api/auth/me'
Assert-Equal 'session after deletion rejected' $afterDeleteMe.Status 401
$afterDeleteLogin = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Json $startOverLoginBody
Assert-Equal 'deleted account cannot sign in' $afterDeleteLogin.Status 401

$freshVerifier = New-Base64 32
$freshRecoveryVerifier = New-Base64 32
$freshSalt = New-Base64 16
$freshWrappedKey = New-Base64 48
$freshWrappedIv = New-Base64 12
$freshRegisterBody = @{
    email              = $email
    authVerifier       = $freshVerifier
    recoveryVerifier   = $freshRecoveryVerifier
    passwordSalt       = $freshSalt
    wrappedMasterKey   = $freshWrappedKey
    wrappedMasterKeyIv = $freshWrappedIv
} | ConvertTo-Json
$freshRegister = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Json $freshRegisterBody
Assert-Equal 'same email can register again' $freshRegister.Status 200

$freshCrypto = Invoke-Api -Method 'GET' -Path "/api/auth/crypto?email=$email"
$freshCryptoJson = $freshCrypto.Body | ConvertFrom-Json
Assert-Equal 'new registration has a new salt' $freshCryptoJson.passwordSalt $freshSalt
Assert-Equal 'new registration has a new wrapped key' $freshCryptoJson.wrappedMasterKey $freshWrappedKey
$freshFiles = Invoke-Api -Method 'GET' -Path '/api/files'
$freshFilesJson = @($freshFiles.Body | ConvertFrom-Json | ForEach-Object { $_ })
Assert-Equal 'new account starts with an empty drive' $freshFilesJson.Count 0

$freshDeleteBody = @{ currentAuthVerifier = $freshVerifier } | ConvertTo-Json
$freshDelete = Invoke-Api -Method 'POST' -Path '/api/auth/account/delete' -Json $freshDeleteBody
Assert-Equal 'fresh account deleted too' $freshDelete.Status 204

Write-Host ""
if ($script:Failures -eq 0) {
    Write-Host "ALL TESTS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("FAILURES: {0}" -f $script:Failures) -ForegroundColor Red
    exit 1
}
