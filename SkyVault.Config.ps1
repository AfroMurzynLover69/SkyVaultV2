# Shared configuration helpers for SkyVault.ps1.

function Read-JsonFile {
    param([string]$Path)

    try {
        $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
        return $raw | ConvertFrom-Json
    }
    catch {
        throw "Cannot read JSON config '$Path': $($_.Exception.Message)"
    }
}

function Merge-ConfigObjects {
    param($Base, $Override)

    if ($null -eq $Base) { return $Override }
    if ($null -eq $Override) { return $Base }

    $result = [ordered]@{}
    foreach ($property in $Base.PSObject.Properties) {
        $result[$property.Name] = $property.Value
    }

    foreach ($property in $Override.PSObject.Properties) {
        $name = $property.Name
        $baseValue = $result[$name]
        if ($null -ne $baseValue -and
            $baseValue -is [System.Management.Automation.PSCustomObject] -and
            $property.Value -is [System.Management.Automation.PSCustomObject]) {
            $result[$name] = Merge-ConfigObjects $baseValue $property.Value
        }
        else {
            $result[$name] = $property.Value
        }
    }

    return [pscustomobject]$result
}

function Read-SkyVaultConfig {
    param([string]$Path, [string]$Root, [switch]$NoLocal)

    if ([string]::IsNullOrWhiteSpace($Root)) {
        $Root = (Get-Location).Path
    }
    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        $Path = Join-Path $Root $Path
    }
    $Path = [System.IO.Path]::GetFullPath($Path)
    $baseDirectory = Split-Path -Parent $Path

    $baseConfig = $null
    $found = Test-Path -LiteralPath $Path
    if ($found) {
        $baseConfig = Read-JsonFile $Path
        if ($null -eq $baseConfig) {
            throw "Configuration file '$Path' is empty."
        }
    }

    $localPath = [System.IO.Path]::ChangeExtension($Path, '.local.json')
    $localConfig = $null
    $usedLocal = $false
    if (-not $NoLocal -and (Test-Path -LiteralPath $localPath)) {
        $localConfig = Read-JsonFile $localPath
        $usedLocal = $true
    }

    $config = $baseConfig
    if ($null -ne $localConfig) {
        $config = Merge-ConfigObjects $baseConfig $localConfig
    }
    if ($null -eq $config) {
        $config = [pscustomobject]@{}
    }

    return [pscustomobject]@{
        Config        = $config
        Path          = $Path
        Found         = $found
        LocalPath     = $localPath
        UsedLocal     = $usedLocal
        BaseDirectory = $baseDirectory
    }
}

function Get-ConfigValue {
    param([object]$Config, [string]$Path, $Default = $null)

    $current = $Config
    foreach ($segment in $Path.Split('.')) {
        if ($null -eq $current) { return $Default }
        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) { return $Default }
        $current = $property.Value
        if ($null -eq $current) { return $Default }
    }
    return $current
}

function Get-ProcessEnvironmentValue {
    param([string]$Name)

    if ([string]::IsNullOrWhiteSpace($Name)) { return $null }
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return $value
}

function Get-EffectiveValue {
    param(
        [object]$Config,
        [string]$Path,
        [string]$EnvName,
        [string]$LegacyEnvName,
        $Default = $null
    )

    $value = Get-ProcessEnvironmentValue $EnvName
    if ($null -ne $value) { return $value }

    $value = Get-ProcessEnvironmentValue $LegacyEnvName
    if ($null -ne $value) { return $value }

    $value = Get-ConfigValue $Config $Path $null
    if ($null -eq $value -or ($value -is [string] -and [string]::IsNullOrWhiteSpace($value))) {
        return $Default
    }
    return $value
}

function ConvertTo-BooleanValue {
    param($Value, [string]$Name)

    if ($Value -is [bool]) { return $Value }

    $text = ([string]$Value).Trim().ToLowerInvariant()
    if ($text -in @('true', '1', 'yes', 'on')) { return $true }
    if ($text -in @('false', '0', 'no', 'off')) { return $false }
    throw "Setting '$Name' must be true or false (got '$Value')."
}

function ConvertTo-IntValue {
    param($Value, [string]$Name, [int]$Min, [int]$Max)

    $number = 0
    if (-not [int]::TryParse([string]$Value, [ref]$number)) {
        throw "Setting '$Name' must be an integer (got '$Value')."
    }
    if ($number -lt $Min -or $number -gt $Max) {
        throw "Setting '$Name' must be between $Min and $Max (got $number)."
    }
    return $number
}

function ConvertTo-LongValue {
    param($Value, [string]$Name, [long]$Min, [long]$Max)

    $number = [long]0
    if (-not [long]::TryParse([string]$Value, [ref]$number)) {
        throw "Setting '$Name' must be an integer (got '$Value')."
    }
    if ($number -lt $Min -or $number -gt $Max) {
        throw "Setting '$Name' must be between $Min and $Max (got $number)."
    }
    return $number
}

function Resolve-ConfigPath {
    param([string]$Value, [string]$BasePath, [string]$Name)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Setting '$Name' cannot be empty."
    }
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Value))
}

function Assert-DataSizeValue {
    param([string]$Value, [string]$Name)

    if ($Value -notmatch '^(?i)\s*\d+(\.\d+)?\s*(B|KB|MB|GB|TB)\s*$') {
        throw "Setting '$Name' must be a size such as 16KB or 2MB (got '$Value')."
    }
}

function Assert-DurationValue {
    param([string]$Value, [string]$Name)

    if ($Value -notmatch '^(?i)\s*(\d+(ms|s|m|h|d)|P\d.*)\s*$') {
        throw "Setting '$Name' must be a duration such as 30s or PT30S (got '$Value')."
    }
}

function Format-Bytes {
    param([long]$Bytes)

    if ($Bytes -lt 0) { return 'n/a' }
    $units = @('B', 'KB', 'MB', 'GB', 'TB')
    $value = [double]$Bytes
    $index = 0
    while ($value -ge 1024 -and $index -lt ($units.Count - 1)) {
        $value /= 1024
        $index++
    }
    if ($index -eq 0) { return ('{0:N0} {1}' -f $value, $units[$index]) }
    return ('{0:N2} {1}' -f $value, $units[$index])
}

function Get-BackupSettings {
    param($ConfigInfo)

    $config = $ConfigInfo.Config
    $baseDirectory = $ConfigInfo.BaseDirectory

    $enabled = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.enabled' 'SKYVAULT_BACKUP_ENABLED' $null $true) 'backup.enabled'
    $directory = Resolve-ConfigPath ([string](Get-EffectiveValue $config 'backup.directory' 'SKYVAULT_BACKUP_DIRECTORY' $null 'backups')) $baseDirectory 'backup.directory'

    $scheduleTime = [string](Get-EffectiveValue $config 'backup.schedule.time' 'SKYVAULT_BACKUP_SCHEDULE_TIME' $null '00:00')
    if ($scheduleTime -notmatch '^([01]\d|2[0-3]):([0-5]\d)$') {
        throw "Setting 'backup.schedule.time' must be HH:mm, for example 00:00 (got '$scheduleTime')."
    }
    $hour = [int]$Matches[1]
    $minute = [int]$Matches[2]
    $cron = "0 $minute $hour * * *"

    $copies = ConvertTo-IntValue (Get-EffectiveValue $config 'backup.retention.copies' 'SKYVAULT_BACKUP_KEEP_COPIES' $null 2) 'backup.retention.copies' 1 365
    $level = ConvertTo-IntValue (Get-EffectiveValue $config 'backup.compression.level' 'SKYVAULT_BACKUP_COMPRESSION_LEVEL' $null 9) 'backup.compression.level' 0 9
    $dictionaryMb = ConvertTo-IntValue (Get-EffectiveValue $config 'backup.compression.dictionaryMb' 'SKYVAULT_BACKUP_DICTIONARY_MB' $null 64) 'backup.compression.dictionaryMb' 1 1536

    $includeDatabase = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.includeDatabase' 'SKYVAULT_BACKUP_INCLUDE_DATABASE' $null $true) 'backup.includeDatabase'
    $includeFiles = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.includeFiles' 'SKYVAULT_BACKUP_INCLUDE_FILES' $null $true) 'backup.includeFiles'
    $includePlanState = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.includePlanState' 'SKYVAULT_BACKUP_INCLUDE_PLAN_STATE' $null $true) 'backup.includePlanState'
    if (-not $includeDatabase -and -not $includeFiles -and -not $includePlanState) {
        throw 'Backup is configured to include nothing; enable at least one of backup.includeDatabase, backup.includeFiles or backup.includePlanState.'
    }
    $verifyAfterCreate = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.verifyAfterCreate' 'SKYVAULT_BACKUP_VERIFY_AFTER_CREATE' $null $true) 'backup.verifyAfterCreate'
    $catchUp = ConvertTo-BooleanValue (Get-EffectiveValue $config 'backup.catchUp' 'SKYVAULT_BACKUP_CATCH_UP' $null $true) 'backup.catchUp'

    $pgDumpRaw = [string](Get-EffectiveValue $config 'backup.pgDumpPath' 'SKYVAULT_BACKUP_PG_DUMP_PATH' $null '')
    $pgDumpPath = $null
    if (-not [string]::IsNullOrWhiteSpace($pgDumpRaw)) {
        $pgDumpPath = Resolve-ConfigPath $pgDumpRaw $baseDirectory 'backup.pgDumpPath'
    }

    return [pscustomobject]@{
        Enabled           = $enabled
        Directory         = $directory
        Time              = $scheduleTime
        Cron              = $cron
        Copies            = $copies
        CompressionLevel  = $level
        DictionaryMb      = $dictionaryMb
        IncludeDatabase   = $includeDatabase
        IncludeFiles      = $includeFiles
        IncludePlanState  = $includePlanState
        VerifyAfterCreate = $verifyAfterCreate
        CatchUp           = $catchUp
        PgDumpPath        = $pgDumpPath
    }
}
