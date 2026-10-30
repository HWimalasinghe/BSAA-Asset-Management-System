<#
.SYNOPSIS
    Starts the BSSA Asset Management application on the office server.

.DESCRIPTION
    Reads its configuration from the environment (see deploy\env.example.ps1),
    never from a file it would have to invent, and refuses to start when the
    database password is missing rather than letting the application fail later
    with a less helpful message.

    Console output is redirected to logs\ so a problem can be described to
    whoever is supporting the system instead of being lost with the window.

.PARAMETER Port
    The HTTP port to listen on. 8080 unless the site has claimed it already.

.PARAMETER UseMaven
    Start from sources with the Maven wrapper instead of the packaged jar, which
    is how a developer runs it. Packaging needs network or a filled local
    repository, so this is the fallback when no jar is present.

.EXAMPLE
    .\deploy\start-server.ps1
    .\deploy\start-server.ps1 -Port 8090
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int] $Port = 8080,

    [switch] $UseMaven
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot 'backend'
$logDirectory = Join-Path $PSScriptRoot 'logs'

# The local configuration file is ignored by git. Sourcing it is optional: an
# operator who set the variables as system environment variables needs none.
$localConfiguration = Join-Path $PSScriptRoot 'env.local.ps1'
if (Test-Path $localConfiguration) {
    . $localConfiguration
}

if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD.Trim() -eq '') {
    throw 'DB_PASSWORD is not set. Copy deploy\env.example.ps1 to deploy\env.local.ps1, fill in the real values, and run this again. The password is never stored in the repository.'
}

if (-not (Test-Path $backendRoot)) {
    throw "Cannot find the application at $backendRoot. Run this script from the repository's deploy folder."
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$startedAt = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdoutLog = Join-Path $logDirectory "server-$startedAt.log"
$stderrLog = Join-Path $logDirectory "server-$startedAt-error.log"

$packagedApplication = Get-ChildItem -Path (Join-Path $backendRoot 'target') -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($UseMaven -or -not $packagedApplication) {
    $executable = Join-Path $backendRoot 'mvnw.cmd'
    $arguments = @(
        '-o', 'spring-boot:run',
        "-Dspring-boot.run.arguments=--server.port=$Port"
    )
    Write-Host "Starting from sources with the Maven wrapper (no packaged jar found, or -UseMaven was asked for)."
}
else {
    $javaExecutable = (Get-Command java -ErrorAction SilentlyContinue).Source
    if (-not $javaExecutable) {
        throw 'java was not found on PATH. Install a Java 17 runtime, or start with -UseMaven.'
    }
    $executable = $javaExecutable
    $arguments = @(
        '-jar', $packagedApplication.FullName,
        "--server.port=$Port"
    )
    Write-Host "Starting $($packagedApplication.Name)."
}

$process = Start-Process -FilePath $executable `
    -ArgumentList $arguments `
    -WorkingDirectory $backendRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

Write-Host "Process started (PID $($process.Id)). Output: $stdoutLog"

# The application needs a database connection and an EntityManager before it
# answers anything, which on a cold server takes well over ten seconds. Polling
# the health endpoint is the honest readiness test: it is the same question a
# staff member's browser is about to ask.
$healthUrl = "http://localhost:$Port/api/health"
$deadline = (Get-Date).AddSeconds(180)
$ready = $false

while ((Get-Date) -lt $deadline) {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
        if ($response.status -eq 'UP') {
            $ready = $true
            break
        }
    }
    catch {
        Start-Sleep -Seconds 3
    }
}

if (-not $ready) {
    Write-Warning "The application did not answer $healthUrl within 180 seconds. Read the log above; docs\DEPLOYMENT.md covers the usual causes."
    exit 1
}

# What to type on the other computers, printed once at start-up rather than
# buried in a document nobody opens when the network changes. Virtual switch
# addresses (Hyper-V, WSL, Docker) are excluded on purpose: they are reachable
# from this machine only, and printing one as an 'office users' URL sends people
# to a page that will never load.
$lanAddresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' -and
        $_.InterfaceAlias -notmatch 'vEthernet|Loopback|Tunnel|isatap|WSL|Docker' -and
        $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -ExpandProperty IPAddress

Write-Host ""
Write-Host "BSSA Asset Management is up."
Write-Host "  This machine : http://localhost:$Port/login"
foreach ($address in $lanAddresses) {
    Write-Host "  Office users : http://${address}:$Port/login"
}
Write-Host "  Health probe : $healthUrl"
Write-Host "  Stop with    : .\deploy\stop-server.ps1 -Port $Port"
Write-Host ""
if (-not $env:SERVER_ADDRESS -or $env:SERVER_ADDRESS.Trim() -eq '') {
    Write-Host "The application is listening on every address of this host. Confirm with the network administrator that this host keeps a reserved address or a name that office computers can resolve - see the stable-host section of docs\DEPLOYMENT.md."
}
else {
    Write-Warning "SERVER_ADDRESS is set to $env:SERVER_ADDRESS, so the application answers on that address only. Anyone outside it cannot reach the system."
}
