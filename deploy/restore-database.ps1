<#
.SYNOPSIS
    Restores a BSSA Asset Management database backup, and proves it worked.

.DESCRIPTION
    Reads a backup produced by backup-database.ps1, checks its SHA-256 against
    backup-manifest.jsonl before touching the server, applies it, and then counts
    the rows that came back table by table. A restore that is not checked is only
    a hope, so the row counts are printed as part of a successful run.

    Safety, in the order the script applies it:

    1. The dump created by mysqldump --databases contains "DROP TABLE IF EXISTS"
       for all twenty tables. Running it against the live schema destroys whatever
       is in it right now, so that combination needs -AllowLiveOverwrite and is
       refused otherwise.
    2. Restoring to a different schema name (the normal way to test a backup) is
       allowed freely. mysqldump writes the schema name into CREATE DATABASE and
       USE statements, so the script rewrites those two lines in a working copy -
       the stored backup itself is never modified.
    3. A checksum that does not match, or a backup the manifest does not mention,
       stops the run. Corruption found after a restore is a much worse day than
       corruption found before one.

    The password reaches the MySQL client through MYSQL_PWD only, so it appears on
    no command line and in no process list.

.PARAMETER BackupFile
    Path to the .dump.sql.zip (preferred - it is what the manifest describes) or
    to an uncompressed .dump.sql.

.PARAMETER TargetDatabase
    Schema to restore into. Defaults to the name written inside the backup. Pass a
    scratch name such as bssa_am_restore_verify to test a backup without risking
    live data.

.PARAMETER AllowLiveOverwrite
    Required to restore into the application's own schema. Stop the application
    first: see docs\BACKUP-AND-RESTORE.md.

.PARAMETER TrustUnlistedBackup
    Proceeds when backup-manifest.jsonl does not describe this archive, which is
    the case for a backup copied from elsewhere without its manifest line.

.EXAMPLE
    # Prove a backup is restorable, without touching live data.
    .\deploy\restore-database.ps1 -BackupFile .\deploy\backups\my-backup.zip -TargetDatabase bssa_am_restore_verify

.EXAMPLE
    # Full recovery of the working schema, after the application has been stopped.
    .\deploy\stop-server.ps1
    .\deploy\restore-database.ps1 -BackupFile \\fileserver\ams-backups\my-backup.zip -AllowLiveOverwrite
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $BackupFile,

    [string] $TargetDatabase,

    [switch] $AllowLiveOverwrite,

    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string] $ServerHost = '127.0.0.1',

    [ValidateRange(1, 65535)]
    [int] $ServerPort = 3306,

    [switch] $TrustUnlistedBackup
)

$ErrorActionPreference = 'Stop'

if (Test-Path (Join-Path $PSScriptRoot 'env.local.ps1')) {
    . (Join-Path $PSScriptRoot 'env.local.ps1')
}

if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD.Trim() -eq '') {
    throw 'DB_PASSWORD is not set, so there is nothing to restore with. See deploy\env.example.ps1.'
}

$restoreUser = if ($env:DB_BACKUP_USERNAME) { $env:DB_BACKUP_USERNAME } else { $env:DB_USERNAME }
if (-not $restoreUser) { $restoreUser = 'root' }

# The same search order as backup-database.ps1, because mysql.exe and mysqldump.exe
# always live beside each other.
function Find-MySqlClientExecutable {
    param([string] $Name)

    if ($env:MYSQL_BIN_DIRECTORY) {
        $candidate = Join-Path $env:MYSQL_BIN_DIRECTORY $Name
        if (Test-Path $candidate) { return $candidate }
    }

    $onPath = (Get-Command $Name -ErrorAction SilentlyContinue).Source
    if ($onPath) { return $onPath }

    return (Get-ChildItem 'C:\Program Files\MySQL' -Filter $Name -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName)
}

$mysqlExecutable = Find-MySqlClientExecutable -Name 'mysql.exe'
if (-not $mysqlExecutable) {
    throw 'mysql.exe was not found. Add MySQL\bin to PATH or set MYSQL_BIN_DIRECTORY before running this script.'
}

if (-not (Test-Path $BackupFile)) {
    throw "Cannot find the backup file $BackupFile."
}
$BackupFile = (Resolve-Path $BackupFile).Path

$workRoot = Join-Path $PSScriptRoot 'backups'
$manifestFile = Join-Path $workRoot 'backup-manifest.jsonl'
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$workDirectory = Join-Path $workRoot "restore-work-$runStamp"
New-Item -ItemType Directory -Force -Path $workDirectory | Out-Null

try {
    # ---------------------------------------------------------------- verification
    $isArchive = $BackupFile -like '*.zip'
    $checksumEntry = $null

    if ($isArchive) {
        $archiveName = Split-Path -Leaf $BackupFile
        $actualChecksum = (Get-FileHash -Path $BackupFile -Algorithm SHA256).Hash

        if (Test-Path $manifestFile) {
            $entries = Get-Content $manifestFile |
                Where-Object { $_.Trim() -ne '' } |
                ForEach-Object { $_ | ConvertFrom-Json } |
                Where-Object { $_.archive -eq $archiveName }

            $checksumEntry = $entries | Select-Object -Last 1

            if (-not $checksumEntry) {
                if (-not $TrustUnlistedBackup) {
                    throw ("backup-manifest.jsonl has no entry for $archiveName. The archive may be intact, but " +
                        'nothing here can prove it. Copy the manifest alongside your backups, or pass ' +
                        '-TrustUnlistedBackup once you have confirmed where the file came from.')
                }
                Write-Warning "No manifest entry for $archiveName; continuing because -TrustUnlistedBackup was passed. The archive has NOT been verified against a recorded checksum."
            }
            elseif ($checksumEntry.sha256 -ne $actualChecksum) {
                throw ("Checksum mismatch for $archiveName. Manifest says $($checksumEntry.sha256), the file hashes to " +
                    "$actualChecksum. The copy is damaged or incomplete; nothing was restored.")
            }
            else {
                Write-Host "Checksum verified: $actualChecksum"
            }
        }
        else {
            if (-not $TrustUnlistedBackup) {
                throw "No backup-manifest.jsonl at $manifestFile, so this archive cannot be verified. Bring the manifest with the backup, or pass -TrustUnlistedBackup."
            }
            Write-Warning 'No manifest found; archive checksum cannot be confirmed.'
        }

        $expanded = Join-Path $workDirectory 'expanded'
        Expand-Archive -Path $BackupFile -DestinationPath $expanded -Force
        $dumpFile = Get-ChildItem -Path $expanded -File |
            Where-Object { $_.Name -like '*.dump.sql' -and $_.Length -gt 0 } |
            Select-Object -First 1 -ExpandProperty FullName
        if (-not $dumpFile) {
            throw "The archive does not contain a non-empty .dump.sql file. Nothing was restored."
        }
    }
    else {
        $dumpFile = $BackupFile
        Write-Warning 'Restoring an uncompressed dump: its checksum cannot be checked against the manifest, which records the archive. Prefer the .zip.'
    }

    if ((Get-Item $dumpFile).Length -lt 1024) {
        throw "The dump is only $((Get-Item $dumpFile).Length) bytes, which is not a real backup of this schema. Nothing was restored."
    }

    # ------------------------------------------------------------- schema inside dump
    # mysqldump --databases writes these two statements; verified against a dump
    # taken from this installation:
    #   CREATE DATABASE /*!32312 IF NOT EXISTS*/ `name` /*!40100 ... */;
    #   USE `name`;
    $createDatabaseLine = Select-String -Path $dumpFile -Pattern '^CREATE DATABASE .*?`([^`]+)`' | Select-Object -First 1
    $useLine = Select-String -Path $dumpFile -Pattern '^USE `([^`]+)`' | Select-Object -First 1

    if (-not $createDatabaseLine -or -not $useLine) {
        throw "The dump does not carry the 'CREATE DATABASE' and 'USE' lines that mysqldump --databases writes. It was probably made by another tool; refusing to guess which schema it belongs to."
    }

    $dumpedDatabase = $useLine.Matches[0].Groups[1].Value
    if (-not $TargetDatabase) { $TargetDatabase = $dumpedDatabase }

    # The schema the live application uses, read the same way the application reads it.
    $liveDatabase = 'bssa_asset_management'
    if ($env:SPRING_DATASOURCE_URL -match '/([A-Za-z0-9_]+)(\?|$)') {
        $liveDatabase = $Matches[1]
    }

    if ($TargetDatabase -eq $liveDatabase -and -not $AllowLiveOverwrite) {
        throw ("'$TargetDatabase' is the schema the application is using, and this dump drops and recreates every table in it. " +
            'Stop the application, then re-run with -AllowLiveOverwrite for a real recovery, or pass -TargetDatabase with a scratch ' +
            'name to test the backup without risk.')
    }

    $sourceForRestore = $dumpFile
    if ($TargetDatabase -ne $dumpedDatabase) {
        # Only these two statements name the schema; every table statement that
        # follows runs under USE, so rewriting them retargets the whole restore.
        # Backticks are kept by building the tokens from single-quoted parts -
        # '`[' inside a double-quoted string is an escape, not a backtick.
        $quotedSource = '`' + $dumpedDatabase + '`'
        $quotedTarget = '`' + $TargetDatabase + '`'

        $retargeted = Join-Path $workDirectory "retargeted-$dumpedDatabase-to-$TargetDatabase.sql"

        Get-Content -Path $dumpFile -ReadCount 2000 | ForEach-Object {
            foreach ($line in $_) {
                if ($line.StartsWith('CREATE DATABASE ') -or $line.StartsWith('USE ')) {
                    $line.Replace($quotedSource, $quotedTarget)
                }
                else {
                    $line
                }
            }
        } | Set-Content -Path $retargeted -Encoding UTF8

        if (-not (Select-String -Path $retargeted -Pattern ('^USE ' + $quotedTarget + ';') -Quiet)) {
            throw "Retargeting the dump to '$TargetDatabase' did not produce the expected USE statement. Inspect $retargeted before trying again."
        }

        $sourceForRestore = $retargeted
        Write-Host "Backup holds '$dumpedDatabase'; restoring a retargeted copy into '$TargetDatabase'. The stored backup was not modified."
    }
    else {
        Write-Host "Restoring '$TargetDatabase' from $(Split-Path -Leaf $dumpFile) as it was taken."
    }

    # ------------------------------------------------------------------- the restore
    $restoreErrorFile = Join-Path $workDirectory 'mysql-errors.txt'

    Write-Host "Applying the dump to $ServerHost`:$ServerPort as user '$restoreUser' ..."
    $env:MYSQL_PWD = $env:DB_PASSWORD
    try {
        # -RedirectStandardInput hands the file to mysql's stdin as bytes, which
        # avoids PowerShell re-encoding the dump on the way in.
        $restoreProcess = Start-Process -FilePath $mysqlExecutable `
            -ArgumentList @('--user', $restoreUser, "--host=$ServerHost", "--port=$ServerPort") `
            -RedirectStandardInput $sourceForRestore `
            -RedirectStandardOutput (Join-Path $workDirectory 'mysql-output.txt') `
            -RedirectStandardError $restoreErrorFile `
            -NoNewWindow -Wait -PassThru
    }
    finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }

    if ($restoreProcess.ExitCode -ne 0) {
        $detail = if (Test-Path $restoreErrorFile) { (Get-Content $restoreErrorFile -Raw).Trim() } else { '(no error output)' }
        throw "mysql exited with code $($restoreProcess.ExitCode). Nothing further was verified. Server said: $detail"
    }

    # ------------------------------------------------------------------ verification
    $quotedDatabase = '`' + $TargetDatabase + '`'

    $tableNames = Select-String -Path $dumpFile -Pattern '^CREATE TABLE `([^`]+)`' |
        ForEach-Object { $_.Matches[0].Groups[1].Value } |
        Where-Object { $_ }

    if (-not $tableNames) {
        Write-Warning 'No CREATE TABLE statements were found in the dump, so row counts cannot be checked. Verify the restore by hand.'
    }
    else {
        $countQueries = $tableNames | ForEach-Object {
            $quotedTable = '`' + $_ + '`'
            "SELECT '$_' AS table_name, COUNT(*) AS row_count FROM " + $quotedDatabase + '.' + $quotedTable
        }
        $verificationSql = ($countQueries -join "`nUNION ALL`n") + "`n;"
    }

    $verificationFile = Join-Path $workDirectory 'verify-row-counts.sql'
    $verificationOutputFile = Join-Path $workDirectory 'verify-row-counts.txt'
    Set-Content -Path $verificationFile -Value $verificationSql -Encoding UTF8

    $env:MYSQL_PWD = $env:DB_PASSWORD
    try {
        # Stdin again rather than '-e source', which is a mysql client command and
        # is not something to rely on in a batch script.
        $verifyProcess = Start-Process -FilePath $mysqlExecutable `
            -ArgumentList @('--user', $restoreUser, "--host=$ServerHost", "--port=$ServerPort", '--batch', '--skip-column-names') `
            -RedirectStandardInput $verificationFile `
            -RedirectStandardOutput $verificationOutputFile `
            -RedirectStandardError (Join-Path $workDirectory 'verify-errors.txt') `
            -NoNewWindow -Wait -PassThru
    }
    finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }

    if ($verifyProcess.ExitCode -ne 0) {
        Write-Warning "The restore ran, but row counts could not be read (exit $($verifyProcess.ExitCode)). Check the schema by hand before calling this a success."
    }
    else {
        $rows = Get-Content $verificationOutputFile |
            Where-Object { $_.Trim() -ne '' } |
            ForEach-Object { $parts = $_ -split "`t"; [pscustomobject]@{ Table = $parts[0]; Rows = [int]$parts[1] } }

        Write-Host ''
        Write-Host "Restored schema '$TargetDatabase' - $($rows.Count) tables:"
        $rows | Format-Table -AutoSize | Out-String | Write-Host
        Write-Host "Total rows across the schema: $(($rows | Measure-Object -Sum Rows).Sum)"
    }

    Write-Host ''
    Write-Host "Restore finished for '$TargetDatabase'."
    if ($TargetDatabase -ne $liveDatabase) {
        Write-Host 'This was a scratch schema, so the application is unaffected. Once you have compared it, remove it deliberately:'
        Write-Host "  $mysqlExecutable --user $restoreUser --host $ServerHost --port $ServerPort -e `"DROP DATABASE $quotedDatabase;`""
        Write-Host '  (set MYSQL_PWD first, as this script does, rather than putting the password on the command line)'
    }
    Write-Host "Working files kept for inspection at: $workDirectory"
}
catch {
    Write-Host "Restore did not complete: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
