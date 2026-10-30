<#
.SYNOPSIS
    Stops the BSSA Asset Management application.

.DESCRIPTION
    Finds the process holding the application's HTTP port, checks that it really
    is this application, and stops it. An unrecognised owner of the port is
    reported instead of stopped: killing the wrong process on a shared office
    server takes down something nobody was told to watch.

    Stop the application before restoring a backup over its schema, and avoid
    doing it while staff are working - see docs\BACKUP-AND-RESTORE.md.

.PARAMETER Port
    The port the application was started on. Must match start-server.ps1.

.EXAMPLE
    .\deploy\stop-server.ps1
    .\deploy\stop-server.ps1 -Port 8090
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int] $Port = 8080
)

$ErrorActionPreference = 'Stop'

$listeners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue

if (-not $listeners) {
    Write-Host "Nothing is listening on port $Port. The application is already stopped."
    exit 0
}

$processIds = $listeners | Select-Object -ExpandProperty OwningProcess -Unique

foreach ($processId in $processIds) {
    $candidate = Get-Process -Id $processId -ErrorAction SilentlyContinue

    if (-not $candidate) {
        Write-Warning "Port $Port names process $processId, which is no longer running."
        continue
    }

    $isExpectedApplication = $candidate.ProcessName -match 'java|javaw'
    $isMavenWrapper = $candidate.ProcessName -match 'cmd|node|mvn'

    if (-not ($isExpectedApplication -or $isMavenWrapper)) {
        Write-Warning ("Port $Port is held by '{0}' (PID $processId), which is not this application. " +
            'Nothing was stopped. Check what is using the port before changing the application port.' -f $candidate.ProcessName)
        continue
    }

    Write-Host "Stopping '$($candidate.ProcessName)' (PID $processId) on port $Port."

    # A process started by start-server.ps1 has no window, so there is nothing to
    # ask politely: CloseMainWindow() returns false and waiting 20 seconds for a
    # request that was never delivered only delays the obvious. Stopping the
    # application abruptly is not dangerous to the data - InnoDB keeps committed
    # work and an unfinished transaction is rolled back by MySQL - but whoever was
    # mid-save loses that one operation, so do this when the office is not using
    # the system.
    $askedNicely = $candidate.CloseMainWindow()

    if (-not $askedNicely) {
        Write-Host 'The process has no window to send a close request to; stopping it directly.'
        Stop-Process -Id $processId -Force
    }
    elseif (-not $candidate.WaitForExit(20000)) {
        Write-Host "It did not exit within 20 seconds of the request; stopping it directly."
        Stop-Process -Id $processId -Force
    }
}

Start-Sleep -Seconds 2

if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    Write-Warning "Port $Port is still in use. Look at deploy\logs for the reason."
    exit 1
}

Write-Host "Stopped. Port $Port is free."
