<#
.SYNOPSIS
    Takes a consistent backup of the BSSA Asset Management database.

.DESCRIPTION
    Runs mysqldump with --single-transaction, so InnoDB data is captured at one
    point in time without locking the tables the office is busy using, then
    compresses the result and records a checksum beside it. The checksum is what
    turns "there is a file" into "there is the file that was produced from the
    data we expected", which is the difference that matters on the day something
    has to be restored.

    The password is passed through the MYSQL_PWD environment variable so it does
    not appear on a command line, in a process list, or in this script.

.PARAMETER Database
    The schema to dump. Defaults to the application's own schema.

.PARAMETER BackupDirectory
    Where dumps are written. Defaults to BACKUP_DIRECTORY, then deploy\backups.

.PARAMETER RetentionDays
    How long dated backups are kept once retention is applied.

.PARAMETER ApplyRetention
    Actually deletes older backups. Without it, retention is only reported,
    because a backup script that removes files nobody asked it to remove is a
    worse failure than a full disk.

.EXAMPLE
    .\deploy\backup-database.ps1
    .\deploy\backup-database.ps1 -ApplyRetention -RetentionDays 30
#>
[CmdletBinding()]
param(
    [string] $Database = 'bssa_asset_management',

    [string] $BackupDirectory,

    [ValidateRange(1, 3650)]
    [int] $RetentionDays = 30,

    [switch] $ApplyRetention
)

$ErrorActionPreference = 'Stop'

if (Test-Path (Join-Path $PSScriptRoot 'env.local.ps1')) {
    . (Join-Path $PSScriptRoot 'env.local.ps1')
}

if (-not $BackupDirectory) {
    $BackupDirectory = if ($env:BACKUP_DIRECTORY) { $env:BACKUP_DIRECTORY } else { Join-Path $PSScriptRoot 'backups' }
}

if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD.Trim() -eq '') {
    throw 'DB_PASSWORD is not set, so there is nothing to dump with. See deploy\env.example.ps1.'
}

$dumpUser = if ($env:DB_BACKUP_USERNAME) { $env:DB_BACKUP_USERNAME } else { $env:DB_USERNAME }
if (-not $dumpUser) { $dumpUser = 'root' }

$mysqlBin = if ($env:MYSQL_BIN_DIRECTORY) { $env:MYSQL_BIN_DIRECTORY } else { $null }
$dumpExecutable = $null

if ($mysqlBin) {
    $candidate = Join-Path $mysqlBin 'mysqldump.exe'
    if (Test-Path $candidate) { $dumpExecutable = $candidate }
}
if (-not $dumpExecutable) {
    $dumpExecutable = (Get-Command mysqldump.exe -ErrorAction SilentlyContinue).Source
}
if (-not $dumpExecutable) {
    $installed = Get-ChildItem 'C:\Program Files\MySQL' -Filter 'mysqldump.exe' -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($installed) { $dumpExecutable = $installed }
}
if (-not $dumpExecutable) {
    throw 'mysqldump.exe was not found. Add MySQL\bin to PATH or set MYSQL_BIN_DIRECTORY before running this script.'
}

New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dumpFile = Join-Path $BackupDirectory "bssa-asset-management-$Database-$stamp.dump.sql"
$archiveFile = "$dumpFile.zip"

Write-Host "Dumping '$Database' with $dumpExecutable ..."

# MYSQL_PWD is read by the MySQL client tools without the value appearing in the
# command line. It is cleared the moment the dump is done.
$env:MYSQL_PWD = $env:DB_PASSWORD
try {
    $dumpArguments = @(
        "-u$dumpUser",
        '--host=127.0.0.1',
        '--port=3306',
        '--single-transaction',
        '--routines',
        '--triggers',
        '--events',
        '--set-gtid-purged=OFF',
        "--default-character-set=utf8mb4",
        # '--databases' is a flag, not an option that takes a value: the schema
        # name has to be passed as its own argument, otherwise mysqldump rejects
        # the whole command line and writes nothing.
        '--databases',
        $Database,
        "--result-file=$dumpFile"
    )
    & $dumpExecutable @dumpArguments
    if ($LASTEXITCODE -ne 0) {
        # --result-file is created before mysqldump validates the rest of its
        # command line, so a rejected run leaves an empty file that looks like a
        # backup until someone needs it. Remove it instead of leaving that behind.
        Remove-Item $dumpFile -Force -ErrorAction SilentlyContinue
        throw "mysqldump exited with code $LASTEXITCODE. No backup was published; see the message above."
    }
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

if (-not (Test-Path $dumpFile)) {
    throw "mysqldump reported success but $dumpFile is missing."
}

$dumpBytes = (Get-Item $dumpFile).Length
if ($dumpBytes -lt 1024) {
    Remove-Item $dumpFile -Force -ErrorAction SilentlyContinue
    throw "The dump was only $dumpBytes bytes, which for this schema means it captured nothing. The unusable file was removed; investigate before trying again."
}

Compress-Archive -Path $dumpFile -DestinationPath $archiveFile -CompressionLevel Optimal

$checksum = (Get-FileHash -Path $archiveFile -Algorithm SHA256).Hash
$manifestLine = @{
    timestamp      = (Get-Date).ToString('s')
    database       = $Database
    archive        = (Split-Path -Leaf $archiveFile)
    bytesUnpressed = $dumpBytes
    bytesArchive   = (Get-Item $archiveFile).Length
    sha256         = $checksum
    dumpedBy       = $env:USERNAME
} | ConvertTo-Json -Compress

$manifestFile = Join-Path $BackupDirectory 'backup-manifest.jsonl'
Add-Content -Path $manifestFile -Value $manifestLine

Write-Host ""
Write-Host "Backup written."
Write-Host "  Archive  : $archiveFile"
Write-Host "  SHA-256  : $checksum"
Write-Host "  Manifest : $manifestFile"
Write-Host "  Plain SQL: $dumpFile  (delete it, or let retention do so, once the archive is stored elsewhere)"

# Retention applies to files this script created and nothing else, so that an
# unrelated file in a shared folder cannot be caught by a wildcard.
$expired = Get-ChildItem -Path $BackupDirectory -File |
    Where-Object { $_.Name -like "bssa-asset-management-$Database-*.dump.sql*" -and $_.LastWriteTime -lt (Get-Date).AddDays(-$RetentionDays) }

if ($expired) {
    if ($ApplyRetention) {
        $expired | ForEach-Object {
            Write-Host "Removing $($_.Name) (older than $RetentionDays days)."
            Remove-Item $_.FullName -Force
        }
    }
    else {
        Write-Host ""
        Write-Host "$($expired.Count) backup file(s) are older than $RetentionDays days. Nothing was deleted; re-run with -ApplyRetention once these copies exist off this machine."
    }
}

Write-Host ""
Write-Host "A backup that only lives on this server is not a backup. Copy $archiveFile to the location named in docs\BACKUP-AND-RESTORE.md, then test a restore from it there."
