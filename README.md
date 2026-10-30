# BSSA Asset Management System

A local-network web application for keeping the office's asset register: what
exists, who holds it, who asked for it, what happened to it. Server-rendered
Spring Boot with Thymeleaf pages, one MySQL database, four roles, no separate
frontend build.

Application version: `0.0.1-SNAPSHOT` (`backend/pom.xml`). See
[`CHANGELOG.md`](CHANGELOG.md).

## Start here

| If you are | Read |
| --- | --- |
| Staff — asking for an asset, reporting damage, giving one back | [`docs/USER-GUIDE-OFFICE-STAFF.md`](docs/USER-GUIDE-OFFICE-STAFF.md) |
| Inventory Manager — issuing, taking back, approving, resolving | [`docs/USER-GUIDE-INVENTORY-MANAGER.md`](docs/USER-GUIDE-INVENTORY-MANAGER.md) |
| Inventory Admin — the register, the reference lists, Staff and Manager accounts | [`docs/USER-GUIDE-INVENTORY-ADMIN.md`](docs/USER-GUIDE-INVENTORY-ADMIN.md) |
| System Administrator — the server, the accounts above Admin, backups, releases | [`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`](docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md) |
| Setting the system up on a machine | [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) |
| Deciding who is allowed to do what | [`docs/ROLE-PERMISSION-MATRIX.md`](docs/ROLE-PERMISSION-MATRIX.md) |
| Backing the database up, or needing to restore it | [`docs/BACKUP-AND-RESTORE.md`](docs/BACKUP-AND-RESTORE.md) |
| Releasing a change to the office server | [`docs/RELEASE-PROCESS.md`](docs/RELEASE-PROCESS.md) |
| Filing a problem, or wondering what the system will not do | [`docs/KNOWN-ISSUES-AND-SUPPORT.md`](docs/KNOWN-ISSUES-AND-SUPPORT.md) |

Sign in with your **email address**, not your employee code. That single fact
accounts for a surprising share of the first-week calls.

## What it runs on

| Component | Version | Where that comes from |
| --- | --- | --- |
| Java | 17 | `<java.version>17</java.version>` in `backend/pom.xml` |
| Spring Boot | 4.1.1 (parent) | `backend/pom.xml` |
| Spring Security / Spring Data JPA / Hibernate / Thymeleaf / Tomcat | 7.1.1 / managed / 7.4.5 / 3.1.5 / 11.0.24 | the running application's own startup log |
| MySQL | 8.0.46 server; **at least 8.0.19** required, because `database/schema.sql` uses `INSERT … AS new ON DUPLICATE KEY UPDATE` | startup log and `database/schema.sql` |
| MySQL Connector/J | 9.7.0 | `backend/pom.xml` |

The database schema is `database/schema.sql`, applied by hand — there is no
migration tool in this project. It creates 20 tables and seeds four roles, ten
physical areas and three property categories (General, IT, Electronic). It creates
**no departments, no property types and no users**; a department must exist before
the first account, because every user row requires one. This sequence was run
against a fresh schema and the counts above are what came out — see
`docs/DEPLOYMENT.md` §4.

## Quick start (development)

```powershell
cd d:\Office\BSSA-Asset-Management-System\backend

# 1. configuration: never in a file that is committed
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
Copy-Item ..\deploy\env.example.ps1 ..\deploy\env.local.ps1   # then fill in the placeholders
..\deploy\env.local.ps1                                        # loads them into this shell

# 2. schema, once, against your own database (the schema name is positional:
#    schema.sql has no USE statement)
& $mysql -u <you> --host=127.0.0.1 -e "CREATE DATABASE IF NOT EXISTS bssa_asset_management CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
Get-Content ..\database\schema.sql -Raw | & $mysql -u <you> --host=127.0.0.1 bssa_asset_management

# 3. run it, or run its tests
.\mvnw.cmd spring-boot:run
.\mvnw.cmd clean test
```

`spring.datasource.password=${DB_PASSWORD}` has **no default**: the application
refuses to start rather than guess a credential. Anything in
`application.properties` can be overridden by an environment variable
(`SPRING_DATASOURCE_URL`, `SERVER_PORT`, `SPRING_JPA_SHOW_SQL`, …) without editing
the file. Full list: `deploy/env.example.ps1`.

## Running it for the office

```powershell
.\deploy\start-server.ps1          # uses deploy\env.local.ps1 if present, logs to deploy\logs\
.\deploy\stop-server.ps1 -Port 8080
.\deploy\backup-database.ps1
.\deploy\restore-database.ps1 -BackupFile <archive> -TargetDatabase bssa_am_restore_verify
.\deploy\configure-firewall.ps1 -WhatIf
```

The office reaches it at `http://<host>:8080`, and `/api/health` says `UP` without
credentials. `docs/DEPLOYMENT.md` covers the parts that need deciding rather than
typing: which network segment, a reserved address for the host, the firewall rules,
and restricting MySQL to the application's own host. Read
`docs/KNOWN-ISSUES-AND-SUPPORT.md` §3 first — a handful of those steps still need a
machine administrator and a second physical computer.

## Layout

```
backend/                Spring Boot application (Maven wrapper, no local install needed)
  src/main/java/.../    bootstrap · config · controller · entity · repository · security · service
  src/main/resources/   application.properties · static/css · templates (Thymeleaf)
  src/test/java/...     controller · service · security · integration tests
database/schema.sql     the whole schema, applied by hand
deploy/                 PowerShell scripts for start, stop, backup, restore, firewall
  env.example.ps1                 placeholders only - copy to env.local.ps1 (ignored)
  database-access.sql.example     least-privilege database account, host restriction
docs/                   deployment, backup, role matrix, per-role guides, releases, support
CHANGELOG.md            what changed, what an operator must do, how to roll back
frontend/               empty - the UI is server-rendered, there is no separate build
```

`deploy/env.local.ps1`, `deploy/backups/` and `deploy/logs/` are git-ignored: they
hold credentials, office data and one machine's state. Nothing secret is committed,
and the `.example` files carry placeholders so an operator knows what to set.

## Roles, in one line each

| Role | Owns |
| --- | --- |
| `SYSTEM_ADMIN` | The server, the backups, the Inventory Admin account |
| `INVENTORY_ADMIN` | The asset register, the reference lists, Staff and Manager accounts |
| `INVENTORY_MANAGER` | Issuing, returns, request approvals, incident resolution, reports |
| `OFFICE_STAFF` | Their own requests, their own incidents, their own notifications |

Authorization lives on the server: `config/SecurityConfig.java` for the routes, the
services for the rules that go with an action (an asset is only issued when it is
`AVAILABLE` **and** `GOOD`, nobody approves their own request, a return sets the
asset's status from the condition recorded). A button being hidden proves nothing
and is not how access is decided. The full matrix, including what the application
deliberately cannot do, is in `docs/ROLE-PERMISSION-MATRIX.md`.

## Tests

```powershell
cd backend; .\mvnw.cmd clean test
```

Controller slices, service unit tests, security authorisation tests, an
authentication flow test, and a concurrency test that two people cannot issue the
same asset. Deployment-time assertions (no secret in the shipped configuration,
`ddl-auto=none`, bootstrap disabled, no `server.address` pin) live in
`DeploymentReadinessTest`. Release procedure: `docs/RELEASE-PROCESS.md`.
