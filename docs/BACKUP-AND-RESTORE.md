# Backup and Restore — BSSA Asset Management System

A backup is not proven until it has been restored. This document contains a
restore that was performed and checked on this server, not a procedure that is
assumed to work.

- [1. The plan, in one page](#1-the-plan-in-one-page)
- [2. Taking a backup](#2-taking-a-backup)
- [3. Getting it off the server](#3-getting-it-off-the-server)
- [4. Testing that a backup can be restored](#4-testing-that-a-backup-can-be-restored)
- [5. Restoring for real](#5-restoring-for-real)
- [6. What is in a backup, and what is not](#6-what-is-in-a-backup-and-what-is-not)
- [7. Scenarios](#7-scenarios)

---

## 1. The plan, in one page

| Item | Setting | Why |
| --- | --- | --- |
| What | The MySQL schema `bssa_asset_management`, dumped with `mysqldump --single-transaction` | A consistent point-in-time copy without locking the tables the office is using |
| How often | **Every working day**, at the end of the day | One day of work is the most this site agrees to lose. If staff can enter assignments for a week before anyone notices a problem, a weekly backup is a week of re-typing |
| Who runs it | The person named below, or the scheduled task on the server | A backup "someone usually does" is the backup that was not done the day it mattered |
| Where | `deploy\backups\` on the server, **plus a copy on another machine or network share** | A server that dies with its own disk takes the backups with it |
| Retention | 30 daily copies (the script's default), one copy older than that kept per month for the year | Enough to go back to the Tuesday when things started being wrong |
| Restore test | **Once a quarter**, into a scratch schema, using the procedure in section 4 | An untested backup is a file, not a recovery plan |
| Verified by | Row counts printed by the restore, compared against what the live schema had at dump time | "No errors on screen" is not proof that 42 rows came back |

Fill this in before relying on the system; an empty row means nobody is
responsible:

| Role | Name | Contact |
| --- | --- | --- |
| Backup owner (runs/checks it daily) | _fill in_ | _fill in_ |
| Restore authority (may overwrite the live schema) | _fill in_ | _fill in_ |
| Where the off-server copy lives | _fill in_ | _fill in_ |

**The restore authority matters more than the backup owner.** Restoring over a
live schema deletes what is in it. That decision should not be available to
whoever happens to be sitting at the server.

## 2. Taking a backup

```powershell
.\deploy\backup-database.ps1
```

The script needs `DB_PASSWORD` in the environment; it reads
`deploy\env.local.ps1` if that file exists, so a scheduled task does not have to
set anything up itself. `DB_BACKUP_USERNAME` may point at the dedicated dump
account from `deploy\database-access.sql.example`; without it, the script uses
`DB_USERNAME`.

What it produces:

```
deploy\backups\bssa-asset-management-bssa_asset_management-<date>-<time>.dump.sql       (plain SQL)
deploy\backups\bssa-asset-management-...-<date>-<time>.dump.sql.zip                     (the copy you keep)
deploy\backups\backup-manifest.jsonl                                                    (one line per backup, with its SHA-256)
```

Real output from this server, so the numbers look familiar on the day they are
needed: the dump was **34,150 bytes**, the archive **5,453 bytes**, and the
manifest recorded the SHA-256
`10A794EFF0E407C14794C586B939F3B81FE236D956022C6D7A4953DC60E613B9`.
Those sizes are small because the office data is small; they grow with the
records, not with anything else.

Deliberate behaviours:

- The password is handed to the client through `MYSQL_PWD` and cleared straight
  after, so it never appears on a command line or in a process list.
- A dump under 1 kilobyte is treated as a failure and **deleted**, not kept. An
  empty file named like a backup is worse than no file, because someone will
  trust it later.
- Retention only ever matches file names this script produced, and only deletes
  when `-ApplyRetention` is passed:
  `.\deploy\backup-database.ps1 -ApplyRetention -RetentionDays 30`
- The `--single-transaction` dump does not block staff. Do not run backups
  against a schema someone is restoring into at the same time.

### Scheduling it

Task Scheduler, as an administrator, once:

1. Create a task that runs whether or not anyone is signed in.
2. Action: `Start a program` →
   `powershell.exe` with arguments
   `-NoProfile -ExecutionPolicy Bypass -File "D:\Office\BSSA-Asset-Management-System\deploy\backup-database.ps1"`.
3. Trigger: daily, after the office closes.
4. Run it once from the console and check that the three files appeared.

The task inherits **system** environment variables, not the ones in your PowerShell
window. Either set `DB_PASSWORD` as a system environment variable, or leave the
real values in `deploy\env.local.ps1`, which the script reads itself.

## 3. Getting it off the server

Copy the `.zip` and the `backup-manifest.jsonl` to another machine or share. The
manifest is not paperwork: without it there is no way to tell a damaged archive
from a real one, and the restore script refuses to continue when the archive is
not listed in a manifest it can see.

Treat the copies as confidential: they contain every staff name, employee code,
email address, phone number, BCrypt password hash, asset and assignment in the
system. Not on a personal USB stick, not in a private cloud account.

The plain `.dump.sql` left beside the archive is the same data uncompressed; let
retention remove it once the archive is stored elsewhere.

## 4. Testing that a backup can be restored

**This is the procedure that was run on this server**, and it is safe to repeat:
it restores into a new scratch schema and never touches the office data.

```powershell
$zip = (Get-ChildItem deploy\backups\*.dump.sql.zip | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
.\deploy\restore-database.ps1 -BackupFile $zip -TargetDatabase bssa_am_restore_verify
```

Expected output, all of it real:

1. `Checksum verified: …` — the archive matches the manifest.
2. `Backup holds 'bssa_asset_management'; restoring a retargeted copy into
   'bssa_am_restore_verify'. The stored backup was not modified.`
3. A table of every restored table with its row count. On the run described
   here: 20 tables, 42 rows total — 4 users, 4 roles, 4 user_roles, 12 physical
   areas, 3 departments, 3 property_assignments, 3 property_returns, 2
   properties, 2 status-history rows, 4 categories, 1 type, and 0 for the tables
   the office had not used yet.
4. `Restore finished for 'bssa_am_restore_verify'.`

Then compare with the live schema, and confirm the numbers agree:

```sql
SELECT 'properties' AS t, (SELECT COUNT(*) FROM bssa_asset_management.properties) AS live,
                        (SELECT COUNT(*) FROM bssa_am_restore_verify.properties)  AS restored
UNION ALL SELECT 'property_assignments',
       (SELECT COUNT(*) FROM bssa_asset_management.property_assignments),
       (SELECT COUNT(*) FROM bssa_am_restore_verify.property_assignments);
```

Small differences are normal if staff worked between the dump and the comparison.
A table that is empty in the copy and full in the live schema is not a timing
difference — it means the backup did not capture the data, and the whole
schedule has to be re-examined.

When the numbers agree, remove the scratch schema. Do not leave old copies of the
office database sitting on the server:

```powershell
$env:MYSQL_PWD = '<password>'
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -u root --host=127.0.0.1 -e "DROP DATABASE bssa_am_restore_verify;"
Remove-Item Env:MYSQL_PWD
```

Record the test somewhere durable — the date, who did it, and the result:

| Test date | Done by | Archive tested | Row counts matched | Notes |
| --- | --- | --- | --- | --- |
| 2026-10-30 | coding assistant, during installation work | `…-20261030-113052.dump.sql.zip` | yes, 20/20 tables | restore into scratch schema, then dropped |
| _next quarter_ | | | | |

### What the test refuses, and why

`restore-database.ps1` stops before it does damage. Each of these was exercised:

- **No `-TargetDatabase`, no `-AllowLiveOverwrite`:**
  `'bssa_asset_management' is the schema the application is using, and this dump
  drops and recreates every table in it.` Exit code 1, nothing restored. The dump
  contains 20 `DROP TABLE IF EXISTS` statements, so a live restore really does
  destroy the current data first.
- **Checksum mismatch, or an archive the manifest does not list:** it stops and
  says so. `-TrustUnlistedBackup` exists for a backup brought from elsewhere
  without its manifest, and prints a warning that the archive has not been
  verified.
- **A dump that is too small, or that does not contain the `CREATE DATABASE` and
  `USE` lines `mysqldump --databases` writes:** it refuses rather than guessing
  which schema it belongs to.

## 5. Restoring for real

Overwriting the working schema is a deliberate, three-step act.

1. **Stop the application.** Otherwise a request in flight writes rows into the
   schema being rebuilt, and the result is neither the backup nor the live data.
   `.\deploy\stop-server.ps1`
2. **Take a backup of the current state first**, if the server is still alive.
   Whatever is being thrown away is worth keeping until the restore is confirmed,
   because a restore can be the thing that went wrong.
3. **Restore, naming the live schema explicitly:**

```powershell
.\deploy\restore-database.ps1 -BackupFile <archive> -AllowLiveOverwrite
```

Then start the application and check the data is what people expect — a known
asset, a known assignment, the count on the dashboard.

```powershell
.\deploy\start-server.ps1
```

A restore rebuilds the schema the backup described. If the backup is older than
the software, the restored tables may not match the entities the application now
expects (section 6 covers what to do about that).

## 6. What is in a backup, and what is not

| In the dump | Not in the dump |
| --- | --- |
| All 20 tables: users, roles, user_roles, departments, physical_areas, property_categories, property_types, properties, property_assignments, property_returns, property_requests, property_request_items, property_incidents, property_status_history, property_location_history, audit_logs, purchases, purchase_items, suppliers, notifications | `deploy\env.local.ps1` — deliberately never in git and never in a dump. It has to be re-created from `deploy\env.example.ps1` on a new machine |
| Table definitions, indexes, foreign keys, CHECK constraints | Any file uploaded or stored outside the database |
| Stored routines and events (`--routines --events`) | The application jar, the repository, Windows itself |

Consequences worth knowing:

- **The password file is not in the backup.** A new server therefore fails to
  start with a helpful message rather than restoring into a machine that cannot
  reach its database. That is intentional: a dump that carried credentials would
  be a confidential file in free circulation.
- **Restoring is also the way to migrate** to a new server: install Java, MySQL
  and the repository, create the account, restore the dump, write
  `env.local.ps1`, start.
- The dump records the schema name inside it. Restoring onto a server where the
  schema must be called something else is exactly what `-TargetDatabase` does
  (section 4), and it works by retargeting a copy rather than editing the stored
  backup.

## 7. Scenarios

**Someone deleted the wrong record, or approved something they should not have.**
Do not restore over the live schema for one row. Restore into a scratch schema
(section 4), read the value out of it, and correct the live record through the
application so the audit trail and status history stay intact. Restoring the whole
schema throws away everything everyone else did since the backup.

**The server's disk failed.** New machine: install Java 17 and MySQL 8.0.19+
(section 2 of `docs\DEPLOYMENT.md`), apply nothing else, restore from the copy
that lives **off** the failed server (section 3), create the database account,
write `deploy\env.local.ps1`, start. Every step in that list is one of the things
people discover only during the outage, which is why the documents exist.

**The database will not start.** A dump taken with `--single-transaction` is a
plain SQL file: it can be restored into any working MySQL 8.0.19+ server. Recovery
does not depend on the broken machine.

**"The backup ran, but the file is wrong."** Trust the manifest. If the SHA-256
does not match, use an older archive and fix whatever writes to that folder; do
not restore a file you cannot verify and hope.

**Rolled back an application release.** Rollback of the software and rollback of
the data are separate operations. An older jar pointed at a newer schema can fail
on columns it does not know about, which is why migrations are additive
(`docs\RELEASE-PROCESS.md`) and why section 2 of that document takes a backup
before a release.
