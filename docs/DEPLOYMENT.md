# Deployment — BSSA Asset Management System

Who this is for: the person who installs and keeps the system running on the
office server. It assumes you can run commands in PowerShell and edit a text
file. It does not assume you wrote the code.

Every version number, port, path and rule name in this document was read from
this project and this server, not chosen from a template. Where something still
has to be decided by your site, it says **DECISION**.

- [1. What is being deployed](#1-what-is-being-deployed)
- [2. Requirements](#2-requirements)
- [3. Configuration without secrets](#3-configuration-without-secrets)
- [4. First-time setup](#4-first-time-setup)
- [5. Build, test, start, restart](#5-build-test-start-restart)
- [6. Making it reachable from office computers](#6-making-it-reachable-from-office-computers)
- [7. Restricting the database](#7-restricting-the-database)
- [8. A stable server](#8-a-stable-server)
- [9. Logs](#9-logs)
- [10. Troubleshooting](#10-troubleshooting)

---

## 1. What is being deployed

A Spring Boot web application served by embedded Tomcat, using a MySQL database
on the same machine, accessed by office computers through a browser on the local
network. There is no separate frontend server: the Thymeleaf pages are inside
the application jar.

```
office computers  ──HTTP :8080──▶  server (this machine)  ──JDBC 127.0.0.1:3306──▶  MySQL
```

Only the server needs Java, Maven, MySQL and this repository. Office computers
need nothing but a browser.

## 2. Requirements

| Component | Required | On this server (verified) |
| --- | --- | --- |
| Java | 17 (`<java.version>17</java.version>` in `backend/pom.xml`) | JDK 17 at `C:\Program Files\Java\jdk-17`, runtime 17.0.12 |
| MySQL | 8.0.19 or newer | 8.0.46, InnoDB, `caching_sha2_password` |
| Maven | Nothing to install — `backend/mvnw.cmd` is the wrapper | Maven wrapper present |
| Spring Boot | 4.1.1 (parent pom; you do not install it separately) | 4.1.1 |
| OS | Windows with PowerShell 5.1 or newer | Windows 11 25H2 |
| TCP port | 8080, free, or another port chosen once | 8080 free at time of writing |

**Why MySQL 8.0.19 and not older:** the seed statements in `database/schema.sql`
use the `INSERT … VALUES (…) AS new ON DUPLICATE KEY UPDATE` form, which 8.0.19
introduced. On 8.0.18 the schema script fails with a syntax error.

Hibernate 7.4.5, Spring Security 7.1.1, Tomcat embed 11.0.24, MySQL Connector/J
9.7.0 and Thymeleaf 3.1.5 come from the build file. Do not add or change them
without running the test suite.

## 3. Configuration without secrets

`backend/src/main/resources/application.properties` is inside the repository and
is compiled into the jar. It therefore holds **no passwords**:

```properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/bssa_asset_management
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=none
app.bootstrap-admin.enabled=${BOOTSTRAP_ADMIN_ENABLED:false}
```

`${DB_PASSWORD}` has **no default**, which is deliberate: with no password in the
environment the application refuses to start instead of quietly connecting as
something unintended.

Spring Boot reads any of these from the environment using relaxed binding, so an
operator can point the same jar at a different database without editing or
rebuilding anything. These environment variables are the supported way to
configure a deployment:

| Variable | Purpose | Default if unset |
| --- | --- | --- |
| `DB_USERNAME` | account the application connects with | `root` |
| `DB_PASSWORD` | its password | none — startup fails |
| `SPRING_DATASOURCE_URL` | full JDBC URL, used when the database is not `localhost:3306/bssa_asset_management` | the value in the properties file |
| `SERVER_PORT` | HTTP port | `8080` |
| `SERVER_ADDRESS` | address to bind. **Do not set it to `127.0.0.1` on the office server** — that turns a multi-computer tool into a single-machine tool | all interfaces |
| `SPRING_JPA_SHOW_SQL` | set `true` only while developing; SQL logging is off in the shipped configuration | `false` |
| `BOOTSTRAP_ADMIN_ENABLED` plus `BOOTSTRAP_ADMIN_*` | first System Administrator account, see section 4 | disabled |

`deploy/env.example.ps1` lists all of them with **placeholders only**. Copy it to
`deploy/env.local.ps1`, put the real values there, and leave it alone:

```powershell
Copy-Item deploy\env.example.ps1 deploy\env.local.ps1
notepad deploy\env.local.ps1
```

The root `.gitignore` excludes `deploy/env.local.ps1`, `.env`, backup files and
logs, so the file with the real password cannot be committed by accident. Verify
this yourself rather than trusting the file:

```powershell
git check-ignore -v deploy/env.local.ps1    # must report a match
git status --short                          # must NOT list env.local.ps1
```

Rules that do not change:

- Never write a password into `application.properties`, a template, a script in
  this repository, or a commit message.
- Never commit `deploy/env.local.ps1`. If it is ever committed, treat that
  password as leaked and change it in MySQL.
- The scripts pass the database password to the MySQL client tools through
  `MYSQL_PWD`, so it never appears on a command line or in a process list.

## 4. First-time setup

### 4.1 Create the schema and apply it

`database/schema.sql` contains 20 `CREATE TABLE` statements and 3 seed
statements, and **no `CREATE DATABASE` and no `USE` statement** — it expects to
run inside a schema that already exists. Skipping this step is the most common
cause of a "communication link failure" style error at first start.

```powershell
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:MYSQL_PWD = '<the MySQL root password>'      # not typed on the command line
& $mysql -u root --host=127.0.0.1 -e "CREATE DATABASE IF NOT EXISTS bssa_asset_management CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
Get-Content database\schema.sql -Raw | & $mysql -u root --host=127.0.0.1 bssa_asset_management
Remove-Item Env:MYSQL_PWD
```

What the script seeds automatically: the four roles (`SYSTEM_ADMIN`,
`INVENTORY_ADMIN`, `INVENTORY_MANAGER`, `OFFICE_STAFF`), ten physical areas, and
the three property categories General/IT/Electronic. Re-running it is safe —
the seeds use `ON DUPLICATE KEY UPDATE`.

What it does **not** seed: `departments`, `property_types`, `users`.

**This sequence was run and checked**, on a scratch schema against this MySQL
instance: the two commands above produced **20 tables**, `roles` 4 rows,
`physical_areas` 10 rows, `property_categories` 3 rows, `departments` 0 rows and
`users` 0 rows. Note that the schema name has to be the positional argument in the
second command (`… bssa_asset_management`), because the file contains no `USE`
statement — leave it out and every `CREATE TABLE` fails with "No database
selected". The file is plain ASCII, so piping it through PowerShell cannot mangle a
character. If you ever add non-ASCII seed data, let `mysql.exe` read the file itself
instead of piping it, so the bytes reach the server unchanged — either form below was
run against this instance and gave the same 20 tables:

```powershell
& $mysql -u root --host=127.0.0.1 bssa_asset_management -e "SOURCE database/schema.sql"
Start-Process -FilePath $mysql -ArgumentList @('-u','root','--host=127.0.0.1','bssa_asset_management') -RedirectStandardInput 'database\schema.sql' -NoNewWindow -Wait
```

### 4.2 Give the application its own database account

Connecting as `root` works, and is what the default
`${DB_USERNAME:root}` assumes, but an application account that can only read and
write this one schema limits the damage from a stolen credential or a bug.

`deploy/database-access.sql.example` is a fill-in-the-blanks script for that
account (and a separate one for backups, because MySQL's dump privileges are
global and cannot be limited to one schema). Copy it, replace every
`__REPLACE_ME__`, run it, and check the statements at the bottom of the file that
are expected to *fail* — a grant you cannot prove is restrictive is not
restrictive.

**DECISION — which account to use.** If you create `bssa_asset_app`, set
`DB_USERNAME=bssa_asset_app` in `deploy/env.local.ps1`. If you keep using `root`
the system works today, and the risk note in
`docs/KNOWN-ISSUES-AND-SUPPORT.md` stays open.

### 4.3 Create the first department

The System Administrator account is a row in `users`, and `users.department_id`
has a foreign key to `departments` with `ON DELETE RESTRICT`. Because
`schema.sql` never inserts a department, bootstrapping an admin against an empty
`departments` table fails with a foreign-key error.

```sql
INSERT INTO departments (name, description)
VALUES ('Administration', 'Department created during installation');
```

Note the `department_id` you are given — usually `1`, and confirmed to be `1` on
the checked fresh schema above. `BOOTSTRAP_ADMIN_DEPARTMENT_ID` defaults to `1`, so
an installer who follows this order needs no change there.

### 4.4 Create the first System Administrator

The bootstrap runs once, only when `users` is completely empty, and only when it
is switched on:

```powershell
$env:BOOTSTRAP_ADMIN_ENABLED   = 'true'
$env:BOOTSTRAP_ADMIN_EMPLOYEE_CODE = 'EMP001'
$env:BOOTSTRAP_ADMIN_FIRST_NAME    = '<name>'
$env:BOOTSTRAP_ADMIN_LAST_NAME     = '<surname>'
$env:BOOTSTRAP_ADMIN_EMAIL         = '<someone@yourorganisation>'
$env:BOOTSTRAP_ADMIN_PASSWORD      = '<a strong password, not this example>'
$env:BOOTSTRAP_ADMIN_DEPARTMENT_ID = '1'
```

Start the application (section 5). The log says
`Initial System Admin account created successfully.` Sign in with that
email address and password, then **turn the bootstrap back off** by removing
those variables from `deploy/env.local.ps1`. Leaving it enabled is harmless but
misleading: it does nothing while any user exists, so a later operator may think
it is how accounts are created.

The password is stored as a BCrypt hash; nobody needs to know it, and nobody who
can read the database can recover it.

### 4.5 Schema changes after the first install

`spring.jpa.hibernate.ddl-auto=none` means Hibernate **never** alters the
database. This is the right setting for real data — an automatic schema change on
a running office database is how tables get quietly rebuilt — but it means every
entity change needs a written migration.

Until the project has a migration tool, the procedure is manual and must be
tested before it is run on the working schema:

1. Add the `ALTER TABLE` statements to a numbered file under `database/` (for
   example `database/migrations/0002_add_property_warranty.sql`).
2. Take a backup (`docs/BACKUP-AND-RESTORE.md`).
3. Apply it to a copy of the schema and start the application against the copy.
4. Apply it to the real schema during a quiet hour, with the application stopped.

**DECISION:** Flyway or Liquibase should be introduced before the schema starts
changing often. Neither is in the build today, and adding one is a code change,
not a deployment task.

## 5. Build, test, start, restart

All commands run from the repository root in PowerShell.

```powershell
cd backend
.\mvnw.cmd -o clean test        # the whole suite; must end with BUILD SUCCESS
.\mvnw.cmd clean package        # builds target\*.jar - needs network (L-19: the
                                # offline form cannot resolve the packaging plug-in)
```

The offline `-o clean package` form fails here for three plug-in artifacts that are
not in the local repository; the same command with network access produced
`target\asset-management-0.0.1-SNAPSHOT.jar` (59,844,290 bytes, repackaged with
nested dependencies) in 16 seconds. Do the packaged build on a machine with
network, then copy or build on the server.

Start and stop with the scripts in `deploy\`, which handle the environment, the
log files and the readiness check:

```powershell
.\deploy\start-server.ps1              # waits until the health endpoint answers, then prints the URLs
.\deploy\stop-server.ps1               # finds the process on the port and stops it
.\deploy\start-server.ps1 -Port 8090   # a different port, if 8080 is taken
```

`start-server.ps1` prefers `backend\target\*.jar` and falls back to
`.\mvnw.cmd -o spring-boot:run` when there is no jar. Both paths were run on this
server: from a cold start the application answers within about 10 seconds, and
the script waits up to 180 seconds before giving up. The jar path is what the most
recent runs used, because a jar has been built since.

The script prints two process numbers and only one of them is useful. "Launch
request accepted (launcher PID …)" is the process the script started, which on the
default Java install is a launcher rather than the application; "Application : PID
…" is the process that really holds the port, taken from the port after the health
check answers. It is that second number to look at in Task Manager, and it is what
`stop-server.ps1` finds. Use `stop-server.ps1` rather than `taskkill`, because it
refuses a port owner it does not recognise.

What the script does when it cannot start: it prints the log file name. Start
there, not with guesses.

**Restart procedure** (for an update, a configuration change, or before a
restore):

1. Tell the office to sign out — anyone mid-save loses that one operation.
2. `.\deploy\stop-server.ps1`
3. `.\deploy\start-server.ps1`
4. Check `http://localhost:8080/api/health` answers `status: UP`, then sign in
   from a second computer.

**DECISION — automatic start after a reboot.** Nothing here does this yet, and a
web app that dies every Tuesday reboot is not a system the office can rely on.
The two supported options, both of which need an administrator:

- Task Scheduler: a task that runs `deploy\start-server.ps1` "At startup",
  configured to run whether or not anyone is signed in, using the environment
  variables from `deploy\env.local.ps1`.
- A Windows service wrapper (for example NSSM or `WinSW`) around
  `java -jar backend\target\asset-management-0.0.1-SNAPSHOT.jar`.

Neither is documented as done until someone has restarted the server and seen the
application come back by itself. Until then, "the server rebooted overnight and
nobody can sign in" is an expected failure, and the fix is step 3 above.

## 6. Making it reachable from office computers

The shipped configuration sets no `server.address`, so embedded Tomcat binds all
interfaces. Verified on this server: with the application on port 8091,
`Get-NetTCPConnection -State Listen -LocalPort 8091` reported `:::8091` (every
interface), `http://172.28.24.143:8091/api/health` answered `UP`, and
`http://172.28.24.143:8091/login` returned HTTP 200 with a CSRF field in the
form.

Reaching the host's own LAN address proves the application is listening on the
network. **It does not prove the firewall lets another computer in** — that
traffic goes out and comes back inside the same machine. The test that counts is
the next one.

### 6.1 Open the port, scoped to the office

`deploy\configure-firewall.ps1` creates one inbound allow rule for the
application port, limited to the office subnet, on every firewall profile. Run it
as an administrator, and look at the plan first:

```powershell
.\deploy\configure-firewall.ps1 -WhatIf                        # shows what it will do
.\deploy\configure-firewall.ps1                                # applies it
.\deploy\configure-firewall.ps1 -OfficeSubnet 192.168.10.0/24  # if the office is elsewhere
```

It derives the subnet from the adapter this host is actually connected to and
prints which one it chose — check that line. It deliberately covers the **Public**
profile as well: the network this server was on while it was written
("SLIIT-STD 3", and before that a phone hotspot) is classified Public by Windows,
and a rule limited to Domain/Private would do nothing at all.

### 6.2 Verify from a second computer

Do this on a real office computer, on the office network, and record the result.
The checklist item "multi-computer access is verified" cannot be ticked from the
server alone.

```powershell
Test-NetConnection -ComputerName <server address or name> -Port 8080
# then in a browser:  http://<server address or name>:8080/login
```

Success is a sign-in page. If `Test-NetConnection` reports `TcpTestSucceeded :
False`, work through section 10.4.

**DECISION:** the intended office network has to be named by someone who knows
the site — its subnet, whether users reach servers by IP or by host name, and
whether Wi-Fi and wired clients are on the same range. Everything in this section
depends on that answer.

## 7. Restricting the database

Nobody in the office needs to reach MySQL over the network; the application
connects to `127.0.0.1`. Two things were found on this server that say the
database is currently reachable from elsewhere:

- MySQL is listening on `::` port 3306 — the `my.ini` at
  `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini` sets **no `bind-address`**, so
  it accepts connections on every interface.
- Two **enabled inbound allow** firewall rules exist, named `Port 3306` and
  `Port 33060`, with `Profile=Any` and no remote-address restriction. Port 33060
  is MySQL's X Plugin.

Close the firewall rules with the script, which disables rather than deletes them
so the change reverses with one command:

```powershell
.\deploy\configure-firewall.ps1                     # disables unrestricted 3306/33060 rules
.\deploy\configure-firewall.ps1 -DeepPortScan       # also checks oddly-named rules (about 90 s)
```

Then bind MySQL to the machine itself, which is the change that survives a
firewall rule being re-enabled by someone else:

```ini
# C:\ProgramData\MySQL\MySQL Server 8.0\my.ini  →  [mysqld] section
bind-address = 127.0.0.1
```

Restart the MySQL service for it to take effect. **This is an administrator
action that stops the database for a few seconds, and it is deliberately not
performed by any script in this repository** — a tool that restarts a production
database without asking is a hazard. Do it in a quiet hour, then confirm:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3306 | Select-Object LocalAddress
# expect 127.0.0.1 only; 0.0.0.0 or :: means bind-address was not applied
```

If the database ever moves to its own machine, do not reopen these ports to the
subnet. Add a rule limited to the application server's address, and keep the
least-privilege account from section 4.2.

## 8. A stable server

The host must keep the same address, or every bookmark in the office breaks when
the lease changes. This is not theoretical: while this document was being
written, the machine moved from `192.168.216.212/24` (a phone hotspot) to
`172.28.24.143/19` (office Wi-Fi) with no warning, and `configure-firewall.ps1`
faithfully reported that the address comes from DHCP.

For a reliable deployment, one of these must be true:

1. **DHCP reservation** (preferred, easiest to support): the router always gives
   this machine the same address. The network administrator does it once.
2. **Static address** on the host: works, but is easy to set wrong and collide
   with another device.
3. **A wired interface** rather than Wi-Fi: office Wi-Fi is not where a shared
   server belongs — signal changes move the machine's reachability, and a laptop
   that walks away takes the system with it.
4. **A host name** that users type instead of an address, resolved by the
   router's DNS or the domain.

Record the chosen answer here, because "ask the network administrator" is not
something the next person can do in five minutes:

| Item | Value |
| --- | --- |
| Server hostname | _fill in_ |
| Address users type | _fill in_ |
| How it stays stable (reservation / static / name) | _fill in_ |
| Network administrator | _fill in_ |
| Physical machine, and who can restart it | _fill in_ |

The application port is worth writing down in the same place:
`http://<the address above>:8080/login`.

## 9. Logs

Two things write output:

- `deploy\logs\server-<timestamp>.log` and `…-error.log` — everything the
  application printed, newest name is the newest start.
- `deploy\backups\backup-manifest.jsonl` — one line per backup, with its SHA-256.

The shipped configuration keeps Hibernate statement logging off
(`spring.jpa.show-sql=false`, `hibernate.format_sql=false`) so the log holds
application events rather than every SQL statement the office generates. A
developer who wants statements sees them by setting `SPRING_JPA_SHOW_SQL=true`,
without editing the file.

Reviewed for what must not be logged:

- No password or token appears in application logs. Sign-in failures are handled
  by Spring Security, which does not echo the submitted credential, and the
  bootstrap writes only "created successfully" or names the missing
  **variable**, never its value.
- `BCrypt` hashes are never logged.
- The startup log prints the JDBC URL (`…localhost:3306/bssa_asset_management`)
  because that is Hibernate's database banner; the URL carries no credential.
- Nothing under `logs/` or `backups/` reaches git — `.gitignore` covers both.
  Do not paste a log into a shared channel without reading it first; a log from a
  working session can contain staff names and asset codes.

Personal data in logs is limited to what the application itself prints, which is
names and employee codes in normal workflow messages. Nothing writes passwords,
session identifiers or CSRF tokens.

That was checked rather than assumed, by searching the logs a real start and stop
produced:

```powershell
Select-String -Path deploy\logs\server-*.log -Pattern 'password|token|secret|BCrypt' -CaseSensitive:$false
```

Zero matches on the server logs kept in `deploy\logs\` (a recorded start, health
check and stop). Those runs did not include a sign-in, so the claim about failed
logins rests on Spring Security's own behaviour rather than on this grep; run the
grep again against a log that does contain real usage, and treat any new match as a
regression worth fixing. Repeat it before signing off a deployment.

## 10. Troubleshooting

### 10.1 The application will not start

Read the newest file in `deploy\logs\` first. The pattern to look for is the
exception on the line after `APPLICATION FAILED TO START` or the last `ERROR`.

| Log says | Meaning | Do |
| --- | --- | --- |
| `DB_PASSWORD is not set` (from start-server.ps1) | no password in the environment | create/fill `deploy\env.local.ps1` from `deploy\env.example.ps1` |
| `Communications link failure` / `Connection refused` | MySQL is not running, or the URL points at the wrong host/port | `Get-Service -Name '*MySQL*'` and start it; check `SPRING_DATASOURCE_URL` |
| `Access denied for user 'x'@'localhost' (using password: YES)` | wrong credential, or the account has no grant on this schema | re-check `DB_USERNAME`/`DB_PASSWORD`; if using `bssa_asset_app`, re-run the verification statements in `deploy\database-access.sql.example` |
| `Table 'bssa_asset_management.properties' doesn't exist` | schema was never applied, or applied to a different schema | section 4.1; confirm with `SHOW TABLES` |
| `Foreign key constraint fails` during bootstrap | `departments` is empty | section 4.3 |
| `Port 8080 was already in use` / `Web server failed to start` | something else holds the port | `Get-NetTCPConnection -State Listen -LocalPort 8080 \| Select-Object OwningProcess` then `Get-Process -Id <pid>`; move the app with `-Port` or stop the other thing deliberately |
| `BUILD FAILURE` … `maven-archiver`, `plexus-archiver`, `commons-io` | the local Maven repository cannot resolve the packaging plug-in while offline | run `.\mvnw.cmd clean package` with network, or use the `spring-boot:run` path (start-server.ps1 does this automatically when there is no jar) |
| `Process terminated with exit code: -1` after `Started AssetManagementApplication` | this is what **stopping** the server looks like: `stop-server.ps1` ends the process tree, so Maven reports the run as a failure | not a fault. Confirm the lines above it show a normal start, and that `deploy\logs\server-…-error.log` is empty. If the application was still serving when it died unexpectedly, that is a different problem - read 10.1 from the top |
| Starts, but sign-in always fails | see 10.3 | — |

### 10.2 Database problems

- **Check it answers at all:**
  `& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -u root --host=127.0.0.1 -e 'SELECT 1;'`
  (with `MYSQL_PWD` set, as in section 4.1).
- **Which schema is the application really using:** the startup log prints
  `Default catalog/schema:` and the JDBC URL. Trust that line over assumptions.
- **Disk full** stops MySQL mid-transaction and fills the error log. Backups add
  to this, which is why retention exists in `backup-database.ps1`. Check with
  `Get-PSDrive D`.
- **Corrupted or missing data:** stop and go to `docs/BACKUP-AND-RESTORE.md`. Do
  not "quickly" run the restore script against the live schema.

### 10.3 Login problems

- **Right password, refuses:** the account may be inactive. A disabled account is
  rejected exactly like a wrong password, on purpose. A System Administrator or
  Inventory Admin can see and change that under **Manage Users**.
- **Account was never made:** section 4.4, or an administrator creates it.
- **Blank page after signing in:** `defaultSuccessUrl` sends everyone to `/`. If
  the browser shows an error there, look at the server log — an
  `Authenticated user was not found in the database` means the row was deleted or
  deactivated while the session was alive; sign out and in again.
- **Session drops after a while:** the deployment has no configured session
  timeout, so it is the container default (30 minutes of idleness). That is a
  sensible setting for a shared office machine; changing it is a code/config
  decision, recorded in `docs/KNOWN-ISSUES-AND-SUPPORT.md`.
- **`403` after a long idle period on a form:** the CSRF token expired with the
  session. Reload the page and submit again. CSRF protection is on for every
  state-changing request and is not configurable at runtime.

### 10.4 Network problems

Work outside-in, one step at a time, so the cause is known rather than guessed:

1. **From the server itself:** `http://localhost:8080/api/health` → `UP`? If not,
   it is a start-up problem (10.1), not a network problem.
2. **From the server, using its own LAN address:**
   `http://<lan address>:8080/api/health`. If localhost works and this does not,
   `SERVER_ADDRESS` has been pinned — unset it.
3. **From another computer:** `Test-NetConnection <server>:8080`.
   - `TcpTestSucceeded : False` → firewall. Confirm the rule exists and covers
     the profile the *client's* network is on:
     `Get-NetFirewallRule -Group 'BSSA Asset Management' | Format-Table DisplayName,Enabled,Profile,Direction,Action`
     Confirm the client is on the subnet the rule allows — a client on a
     different Wi-Fi range is *not*, and the rule is correctly refusing it.
   - Times out entirely → also consider "AP/client isolation" on the wireless
     network, which blocks computer-to-computer traffic and cannot be fixed from
     this server.
4. **Page loads but assets are missing** (unstyled form): `/css/**` is permitted
   without a sign-in; if styling is broken, check that the browser is not
   caching a page from an older port, and that you are not on a captive-portal
   network.
5. **Everyone is slow** → the machine is also a workstation. See the stable-host
   notes in section 8.

Useful one-liners:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8080       # is the app listening, and on what address
Get-Process -Id <pid>                                    # what process owns it
Get-NetFirewallRule -Group 'BSSA Asset Management'       # the rules this project added
```
