# Release Process — BSSA Asset Management System

The steps an operator follows to take this repository to the office server, and to
get back to where it was if that turns out to have been the wrong move. Nothing in
here is automatic: there is no CI, no deployment pipeline and no publishing in
`backend/pom.xml`. Every step is run by a named person, and the two steps that
change the outside world (tagging, deploying) need the system owner's approval
first.

Related: `docs/DEPLOYMENT.md` (setup, versions, troubleshooting),
`docs/BACKUP-AND-RESTORE.md` (backup and restore detail), `CHANGELOG.md`,
`docs/KNOWN-ISSUES-AND-SUPPORT.md`.

## 1. Who does what

| Step | Who | Why |
| --- | --- | --- |
| Code complete, tests written and passing | Developer | Nothing moves without a green suite |
| Diff review, secrets check | Developer, then System Administrator | Two pairs of eyes on what leaves the machine |
| Approval to tag and deploy | **System owner** | A release is a business decision, not a technical habit |
| Deploy to the server, smoke test | System Administrator | Needs the server's configuration file and the database |
| Rollback decision | System Administrator, escalating to the owner | Time-critical and destructive; whoever is accountable decides |

## 2. Prepare

1. Confirm the work is finished and reviewed, and that anything an operator has to
   do by hand (a schema change, a new environment variable) is written down before
   the release, not discovered during it.
2. Decide the version. The artifact is `com.bssa:asset-management`, currently
   `0.0.1-SNAPSHOT` in `backend/pom.xml`. A release drops `-SNAPSHOT`;
   `CHANGELOG.md` records what went into it.
3. Announce the window. The office stops being able to use the system for the few
   minutes between stop and start, and a return or an issue recorded in that window
   does not exist.

## 3. Run the full test suite

From `backend`:

```powershell
cd d:\Office\BSSA-Asset-Management-System\backend
.\mvnw.cmd clean test          # with network access to Maven Central
.\mvnw.cmd -o clean test       # offline, using what is already in the local repository
```

Expected: `BUILD SUCCESS`, and the summary line `Tests run: …, Failures: 0,
Errors: 0, Skipped: 0`. Measured on this build: **456 tests across 30 classes, no
failures, no skips, exit code 0, about 31 seconds** (2026-10-30).

**What the suite needs on the machine running it.** A reachable MySQL 8 with the
schema applied, and `DB_PASSWORD` in the environment. That is not a quirk to
configure away: `@SpringBootTest` loads the real configuration, and
`AssetAllocationConcurrencyIntegrationTest`, `PropertySearchIntegrationTest`,
`WorkflowListQueryIntegrationTest` and `DashboardReportServiceIntegrationTest`
deliberately run against the real database and commit — the concurrency tests only
mean anything if two transactions really cross. They invent their own codes and
names, record every row they create, and delete it afterwards.

Because of that, **the suite writes to whatever schema the configuration points
at.** On this build it ran against the schema the default URL names. Afterwards the
exact row total was counted and compared with the backup taken the same morning:
42 rows, every table matching, so the run left nothing behind — the cleanup those
classes document is real. It still deserves the caveat, and prefer a scratch schema
where you have one:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/bssa_am_test'
```

and never put that pointer at the office schema in a file that is committed.

Rules that make this step mean something:

- **A failure caused by your change is fixed, not deleted or `@Disabled`.**
- If a test fails that you did not touch, say so in the release notes rather than
  releasing around it silently.
- A new test must not depend on whoever happens to exist in the office database:
  invent the rows you need and delete them, as the integration classes do.
- `-o` offline builds of the **package** phase may fail for want of
  `maven-archiver`, `plexus-archiver` and `commons-io` in the local repository
  (`docs/KNOWN-ISSUES-AND-SUPPORT.md` L-19). `clean test` works offline on the
  build machine; run the packaged build without `-o` when you need the jar.

## 4. Review what is about to leave the machine

```powershell
cd d:\Office\BSSA-Asset-Management-System
git status --short
git diff --check            # whitespace and conflict markers; must print nothing
git diff --stat
git diff                    # the whole thing, read it, do not skim it
git diff --stat main        # when the branch is ahead of main
```

Look for, specifically:

- any credential, connection string with a password, or token, even your own;
- `application.properties` drift: `spring.jpa.show-sql` must stay `false`,
  `spring.jpa.hibernate.ddl-auto` must stay `none`,
  `app.bootstrap-admin.enabled` must stay `${BOOTSTRAP_ADMIN_ENABLED:false}`, and
  no `server.address` pin that would break LAN access;
- files that belong to one machine: `deploy\env.local.ps1`, anything under
  `deploy\backups\` or `deploy\logs\`, IDE and OS state;
- leftover debugging: `System.out.println`, a temporary `permitAll()`, a test that
  was widened to make it pass, a script parameter hard-coded to your own subnet.

## 5. Confirm nothing secret is tracked

```powershell
cd d:\Office\BSSA-Asset-Management-System
git check-ignore -v deploy/env.local.ps1 deploy/backups/test.zip deploy/logs/test.log
git ls-files deploy database          # only scripts, templates and the schema
git ls-files --others --exclude-standard   # what a `git add .` would take
```

The first command must report a matching rule for each of the three paths (the
local configuration file, a backup archive, a log). The last must not list
anything you would be embarrassed to see in the repository. What ships instead of
secrets: `deploy/env.example.ps1` and `deploy/database-access.sql.example`, which
carry placeholders only.

Never "temporarily" commit a real password to move a release along. If one ever
reaches a commit, it is burned: rotate the database credential and the System
Administrator password, and say so in the release notes.

## 6. Update the version and `CHANGELOG.md`

`CHANGELOG.md` keeps a section per release, newest first, with: version and date;
what changed, in terms an office user can read; schema or configuration changes an
operator must apply by hand; known issues carried into this release (with their
`L-n` / `O-n` identifiers from `docs/KNOWN-ISSUES-AND-SUPPORT.md`); and the
rollback statement for anything that changed data.

## 7. Verify from a clean environment, if practical

The strongest pre-release test, because it finds the file you forgot to add and the
dependency only your machine happens to have:

```powershell
git clone <repository> $env:TEMP\ams-clean
cd $env:TEMP\ams-clean\backend
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

Then start it against a **scratch** database, not the office one: create
`bssa_am_release_check`, apply `database/schema.sql`, point `SPRING_DATASOURCE_URL`
at it, start with the packaged jar, and sign in with a bootstrapped account.
If there is no second machine and no spare schema available, say in the release
notes that this step was skipped — do not quietly mark it done
(`docs/KNOWN-ISSUES-AND-SUPPORT.md` O-7).

## 8. Tag — only after the owner approves

Ask, in writing, for approval to release this version. Then:

```powershell
git tag -a v0.0.1 -m "BSSA Asset Management System 0.0.1 - first office release"
git show v0.0.1 --stat --no-patch
git push origin v0.0.1        # only if pushing was also approved
```

No tag without approval, no force push, and never rewrite a tag that has been
deployed somewhere — a released version number is a promise to whoever is running
the server.

## 9. Deploy

Order matters; each step exists to make a bad outcome survivable.

```powershell
cd d:\Office\BSSA-Asset-Management-System

# 1. back up, and confirm the archive exists before you touch anything
.\deploy\backup-database.ps1

# 2. stop the running application (tell the office first - see L-18)
.\deploy\stop-server.ps1 -Port 8080

# 3. build
cd backend; .\mvnw.cmd clean package; cd ..

# 4. start, then confirm it answers
.\deploy\start-server.ps1 -Port 8080
```

`start-server.ps1` sources `deploy\env.local.ps1` if present, refuses to start with
no `DB_PASSWORD`, writes `deploy\logs\server-<timestamp>.log`, and prints the
addresses to check. Confirm:

```powershell
Invoke-RestMethod http://localhost:8080/api/health        # status : UP
Invoke-WebRequest  http://<lan-address>:8080/login         # 200, and a _csrf input
```

Read the new log: `Database version: 8.0.46`, `Tomcat started on port 8080`, and
nothing that looks like a query, a password or a token.

**Smoke test, signed in as each role** — this is the part that catches a release
that passes its tests and still breaks the office:

| As | Do |
| --- | --- |
| Staff | sign in, raise a request, see it in your list, cancel it, report an incident against a code, read a notification |
| Inventory Manager | approve a request you did not raise, fulfil it, issue an asset directly, record a return in `GOOD` condition, open `/reports/summary` |
| Inventory Admin | open `/properties`, edit one asset's status with a reason, export the register, open `/users` and see the accounts you may manage |
| System Administrator | open `/users`, confirm the Inventory Admin account is reachable and that no Staff account is offered to you |
| Any | from **another physical computer**, load the login page and sign in (this is the check that has never been done on a second machine — O-3) |

Then take a backup of the released state, note the version, the commit, the
operator, the time and the smoke-test results, and tell the office it is live.

## 10. Rollback

Decide quickly. The rule: if the new version is unusable, or is writing data the
old version cannot read, go back — do not debug in front of the whole office.

**Application only** (no schema change, or a change that is still compatible):

```powershell
.\deploy\stop-server.ps1 -Port 8080
git switch v0.0.1                       # or the tag that is known good
cd backend; .\mvnw.cmd clean package; cd ..
.\deploy\start-server.ps1 -Port 8080
Invoke-RestMethod http://localhost:8080/api/health
```

**Database** (the released version damaged data, or the schema is incompatible):
follow `docs/BACKUP-AND-RESTORE.md` — stop the application, restore the backup taken
in step 1 of §9 with `.\deploy\restore-database.ps1 -BackupFile <archive>
-AllowLiveOverwrite`, verify the row counts it prints, then start the old build.
Restoring loses everything recorded since that backup, so say out loud what the
window is before you do it and record what was lost.

**Partial, preferred when the change is known:** apply the rollback SQL you wrote in
`CHANGELOG.md` instead of restoring a whole schema. Restoring a backup is a
hammer; a written rollback statement is a scalpel, and it keeps the day's other
work.

After any rollback: back up the state you landed in, tell the owner, note in
`CHANGELOG.md` under the version that it was rolled back and why, and open an item
in `docs/KNOWN-ISSUES-AND-SUPPORT.md` if the release process itself was at fault.

## 11. Release record

Copy this into the release notes and fill every line; an empty line means the step
was not done.

```text
Version:
Commit / tag:
Approved by, on:
Deployed by, at:
Backup taken before deploy (file, SHA-256):
Schema or configuration changes applied by hand:
Full test suite: Tests run ___, Failures ___, Errors ___, Skipped ___  (date, by)
Clean-environment check: done / skipped - reason
Secrets check (git check-ignore, git ls-files):
Smoke test by role: Staff / Manager / Inventory Admin / System Administrator
Second-computer access check:
Post-deploy backup:
Known issues carried forward (L-n / O-n):
Rollback performed: no / yes - reason, window of data lost
```
