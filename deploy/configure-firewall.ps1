<#
.SYNOPSIS
    Opens the application port to office computers and closes the database ports
    to everything except this machine.

.DESCRIPTION
    Two separate jobs, because the deployment needs both:

    1. The application must be reachable from other computers, so one inbound
       allow rule is created for the HTTP port, scoped to the office subnet
       rather than to the whole internet.
    2. MySQL must NOT be reachable from other computers. On this host two enabled
       inbound allow rules were found - 'Port 3306' and 'Port 33060', both
       Profile=Any with no remote address restriction - while the application
       connects over 127.0.0.1 and needs neither rule. They are disabled rather
       than deleted so the change can be reversed with one command.

    The rule is created for every profile on purpose. This office connects over a
    Wi-Fi network that Windows classifies as Public, and a rule limited to
    Domain/Private would then do nothing at all - the kind of configuration that
    looks correct in a list and fails on the day it matters.

    Nothing here is applied without -WhatIf being honoured first: run it with
    -WhatIf to see the exact changes.

.PARAMETER ApplicationPort
    The HTTP port the application listens on. Must match start-server.ps1.

.PARAMETER OfficeSubnet
    The subnet(s) office computers come from, in CIDR form. When it is not given,
    the script derives it from the host's own connected IPv4 address and prefix.

.PARAMETER DatabasePorts
    Ports to close to remote access. Defaults to MySQL's 3306 and X Plugin 33060.

.PARAMETER DeepPortScan
    Checks every enabled inbound allow rule instead of the ones that look
    database-related by name. Slower by about a minute and a half on a machine
    with a couple of hundred rules, but it cannot miss an oddly-named rule.

.PARAMETER SkipDatabaseHardening
    Only opens the application port. Use it when something outside this script
    is responsible for the database firewall rules.

.EXAMPLE
    .\deploy\configure-firewall.ps1 -WhatIf

.EXAMPLE
    .\deploy\configure-firewall.ps1 -OfficeSubnet 192.168.216.0/24

.EXAMPLE
    .\deploy\configure-firewall.ps1 -DeepPortScan

.EXAMPLE
    # Reverse the database part:
    Set-NetFirewallRule -DisplayName 'Port 3306'  -Enabled True
    Set-NetFirewallRule -DisplayName 'Port 33060' -Enabled True
#>
#Requires -RunAsAdministrator

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateRange(1, 65535)]
    [int] $ApplicationPort = 8080,

    [string[]] $OfficeSubnet,

    [int[]] $DatabasePorts = @(3306, 33060),

    [switch] $DeepPortScan,

    [switch] $SkipDatabaseHardening
)

$ErrorActionPreference = 'Stop'

$applicationRuleName = 'BSSA Asset Management (application HTTP)'
$ruleGroup = 'BSSA Asset Management'

function Get-OfficeSubnetFromHost {
    # The address the office computers will reach the host on. Adapters that are
    # virtual switches (Hyper-V, WSL, Docker) or link-local only are skipped, and
    # the chosen adapter is reported rather than assumed - a firewall rule opened
    # for the wrong subnet fails quietly and looks like a network problem.
    $adapter = Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -and $_.InterfaceAlias -notmatch 'vEthernet|Loopback|Tunnel|isatap|Bluetooth' } |
        Select-Object -First 1

    if (-not $adapter) {
        return $null
    }

    $address = $adapter.IPv4Address |
        Select-Object -First 1 |
        Where-Object { $_.IPAddress -notlike '169.254.*' }

    if (-not $address) {
        return $null
    }

    $addressParts = $address.IPAddress.Split('.')
    $maskBits = [int] $address.PrefixLength
    $octets = @()
    for ($i = 0; $i -lt 4; $i++) {
        $available = [Math]::Max(0, [Math]::Min(8, $maskBits - ($i * 8)))
        $octetMask = if ($available -eq 0) { 0 } else { ((1 -shl $available) - 1) -shl (8 - $available) }
        $octets += ([int] $addressParts[$i] -band $octetMask)
    }

    return [pscustomobject]@{
        Adapter = $adapter.InterfaceAlias
        Address = $address.IPAddress
        Subnet  = "$($octets -join '.')/$maskBits"
    }
}

if (-not $OfficeSubnet -or $OfficeSubnet.Count -eq 0) {
    $derived = Get-OfficeSubnetFromHost
    if (-not $derived) {
        throw ('The office subnet could not be derived from this host. Pass it explicitly, for example ' +
            "-OfficeSubnet 192.168.216.0/24. Opening the port to any remote address would expose the login page beyond the office.")
    }
    $OfficeSubnet = @($derived.Subnet)
    Write-Host "Derived the subnet from adapter '$($derived.Adapter)' at $($derived.Address): $($derived.Subnet)"
    Write-Host 'If the office computers are on a different range, stop and re-run with -OfficeSubnet.'
}

# --------------------------------------------------------------- network profile check
# Get-NetConnectionProfile already reports only the networks this host is on, and
# the Wi-Fi adapter in use here is categorised Public - filtering further on
# NetworkStatus would drop the one profile that matters.
$profiles = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue)

if ($profiles.Count -eq 0) {
    Write-Warning 'No network connection profile could be read, so the firewall profiles were not checked. The rule below covers every profile, which is the safe choice.'
}

foreach ($profile in $profiles) {
    if ($profile.NetworkCategory -eq 'Public') {
        Write-Warning ("'$($profile.Name)' on '$($profile.InterfaceAlias)' is classified as a Public network. " +
            'Firewall rules for this host must cover the Public profile, which they do. If the office network is ' +
            'trusted, the network administrator can set the category to Private, but the application will work either way.')
    }
}

$dhcpAddresses = Get-NetIPConfiguration |
    Where-Object { $_.IPv4DefaultGateway -and $_.InterfaceAlias -notmatch 'vEthernet' } |
    ForEach-Object {
        $adapterIndex = $_.InterfaceIndex
        Get-NetIPAddress -InterfaceIndex $adapterIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.PrefixOrigin -eq 'Dhcp' -or $_.SuffixOrigin -eq 'Dhcp' }
    }

if ($dhcpAddresses) {
    Write-Warning 'This host takes its address from DHCP, so it can change after a lease renewal and break every bookmark. Ask the network administrator for a reservation or use a host name - docs\DEPLOYMENT.md explains the options.'
}

# ------------------------------------------------------------- application port rule
try {
    $existingApplicationRule = Get-NetFirewallRule -DisplayName $applicationRuleName -ErrorAction Stop
}
catch {
    $existingApplicationRule = $null
}

if ($PSCmdlet.ShouldProcess("TCP $ApplicationPort from $($OfficeSubnet -join ', ')", 'allow inbound')) {
    if ($existingApplicationRule) {
        # Update in place so repeated runs do not pile up near-identical rules,
        # which is how a firewall ends up with five rules nobody will remove.
        Set-NetFirewallRule -DisplayName $applicationRuleName `
            -Enabled True `
            -Action Allow `
            -Direction Inbound `
            -Profile Any `
            -RemoteAddress $OfficeSubnet | Out-Null

        Set-NetFirewallPortFilter -DisplayName $applicationRuleName -Protocol TCP -LocalPort $ApplicationPort | Out-Null

        Write-Host "Updated the existing rule '$applicationRuleName'."
    }
    else {
        New-NetFirewallRule -DisplayName $applicationRuleName `
            -Name 'BSSA-AssetManagement-HTTP-In' `
            -Group $ruleGroup `
            -Enabled True `
            -Action Allow `
            -Direction Inbound `
            -Protocol TCP `
            -LocalPort $ApplicationPort `
            -Profile Any `
            -RemoteAddress $OfficeSubnet `
            -Description 'BSSA Asset Management web interface, office subnet only. See docs\DEPLOYMENT.md.' | Out-Null

        Write-Host "Created rule '$applicationRuleName'."
    }
}

# -------------------------------------------------------------------- database ports
if (-not $SkipDatabaseHardening) {
    # Resolving a rule's port filter costs one CIM query per rule, and on this
    # host the 246 enabled inbound allow rules took about 90 seconds. So the
    # default pass looks at rules that advertise themselves as database-related
    # by name, and -DeepPortScan checks every rule for anyone who wants certainty
    # that no oddly-named rule is leaving the port open.
    Write-Host 'Looking for inbound allow rules that expose the database ports ...'

    $allAllowRules = @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue)

    if ($DeepPortScan) {
        $candidateRules = $allAllowRules
    }
    else {
        $portPattern = ($DatabasePorts | ForEach-Object { [regex]::Escape("$_") }) -join '|'
        $candidateRules = @($allAllowRules | Where-Object {
            $_.DisplayName -match "(?i)($portPattern)|mysql|maria|database" -or
            $_.DisplayGroup -match '(?i)mysql|maria|database'
        })
        if ($candidateRules.Count -gt 0) {
            Write-Host "Checking $($candidateRules.Count) rule(s) found by name. Re-run with -DeepPortScan to check all $($allAllowRules.Count)."
        }
    }

    foreach ($rule in $candidateRules) {
        $portFilter = $rule | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue
        if (-not $portFilter) { continue }

        $isRelevantProtocol = $portFilter.Protocol -in @('TCP', 'Any')
        $matchingPort = @($portFilter.LocalPort | Where-Object { $DatabasePorts -contains [int]$_ })

        if (-not $isRelevantProtocol -or $matchingPort.Count -eq 0) { continue }

        $remoteAddresses = @(($rule | Get-NetFirewallAddressFilter -ErrorAction SilentlyContinue).RemoteAddress)
        $isUnrestricted = $remoteAddresses.Count -eq 0 -or
            @($remoteAddresses | Where-Object { $_ -in @('Any', '0.0.0.0/0', '::/0', 'IcmpV4Any', 'Any') }).Count -gt 0

        if ($isUnrestricted) {
            if ($PSCmdlet.ShouldProcess("inbound allow rule '$($rule.DisplayName)' (TCP $($matchingPort -join ',')) from any remote address", 'disable')) {
                Set-NetFirewallRule -Name $rule.Name -Enabled False | Out-Null
                Write-Host ("Disabled '{0}' (TCP {1}). It allowed the database port from anywhere, and the application connects over 127.0.0.1, so it needs nothing of the sort. Reverse with: Set-NetFirewallRule -Name '{2}' -Enabled True" -f $rule.DisplayName, ($matchingPort -join ','), $rule.Name)
            }
        }
        else {
            Write-Host ("Left '{0}' (TCP {1}) alone - it is already limited to {2}. Confirm that range still needs database access." -f $rule.DisplayName, ($matchingPort -join ','), ($remoteAddresses -join ', '))
        }
    }

    Write-Host ''
    Write-Host 'TCP 3306 should now be reachable only from this machine, which is what the application needs: it connects to 127.0.0.1. If the database later moves to its own server, do not reopen these ports to the whole subnet - add one rule limited to the application host, and restrict MySQL with bind-address as well.'
    Write-Host 'The only proof that this worked is a connection attempt from another computer; docs\DEPLOYMENT.md gives the command.'
}
else {
    Write-Host 'Database port hardening was skipped on request.'
}

# ------------------------------------------------------------------------ what to check
Write-Host ''
Write-Host 'Verify from another computer, not from this one - a rule that is open on the server tells you nothing about what the office can actually reach:'
Write-Host "  1. http://<this host>:$ApplicationPort/login  should show the sign-in page."
Write-Host '  2. A MySQL client aimed at <this host>:3306 should fail or time out.'
Write-Host "  3. The rules this script manages can be listed with: Get-NetFirewallRule -Group '$ruleGroup'"
Write-Host ''
Write-Host 'This host answers on:'
foreach ($address in (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -ne '127.0.0.1' -and $_.InterfaceAlias -notmatch 'vEthernet' } |
    Select-Object -ExpandProperty IPAddress)) {
    Write-Host "  http://${address}:$ApplicationPort/login"
}
