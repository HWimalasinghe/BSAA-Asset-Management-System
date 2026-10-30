# Known Limitations and Support — BSSA Asset Management System

What this build does not do, what to do instead, and who to call. Every item here
was checked against the code and the running system on the machine used to build
it — nothing here is a guess from a quick read. Where an item is a **decision the
office still has to make** rather than a defect, it says so.

Version this document describes: `0.0.1-SNAPSHOT` (Spring Boot 4.1.1, Java 17,
MySQL 8.0.46). See `CHANGELOG.md`.

## 1. Getting help

| Severity | Situation | Who acts first | Target |
| --- | --- | --- | --- |
| 1 | Nobody can sign in, or the server will not start | System Administrator | same hour |
| 1 | Suspected data loss, or a restore is needed | System Administrator — **stop writing to the database first** | same hour |
| 2 | One role cannot work (register, approvals, exports failing) | System Administrator, with Inventory Admin | same day |
| 3 | One person's account or data issue | Inventory Admin | next day |
| 4 | Question, suggestion, training | Inventory Admin | when convenient |

Fill in before handing this to the office:

| Placeholder | Name | Phone / extension | Email |
| --- | --- | --- | --- |
| System Administrator (primary) | `<fill in>` | `<fill in>` | `<fill in>` |
| System Administrator (backup) | `<fill in>` — see limitation L-3 | | |
| Inventory Admin | `<fill in>` | | |
| Network / machine administrator (firewall, static address) | `<fill in>` | | |
| Developer / external support contact | `<fill in>` | | |

**Before you report anything**, write down: the time, the exact message on screen,
the URL in the address bar, the signed-in account and its role, whether it
reproduces from another computer, and whether it reproduced just after a restart
or an update. That list is usually the difference between a fix and a guess. Take
a screenshot rather than retyping a message.

Do not send a database dump, a log file, or a CSV export over email or chat without
treating it as personal data (it contains staff names, emails and locations), and
never include a password, a hash, or the contents of `deploy\env.local.ps1` in a
ticket.

## 2. Known limitations

### Accounts, passwords and access

**L-1 — there is no password reset.** No screen, no "forgot password" link, no
email. A password is set once, at account creation, and BCrypt-hashed; nothing in
the application can change it afterwards.
*Do instead:* the documented database procedure in
`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md` §5 (create a temporary account so the
application produces the hash, copy it, remove the temporary account). It was run
against a scratch restore and works. *Who may change this:* a developer, by adding
a reset screen — a real feature, out of scope for an installation task.

**L-2 — one role per account, enforced.** `UserManagementController` refuses two
roles on one account ("You are not permitted to assign the selected role"), and
`canManageTarget` refuses to show or touch any account that holds two. So a second
role can only be added directly in the database, and once it is, that account
leaves the reach of the account screens.
*Do instead:* give the person a second account, or leave the role set as it is. The
dashboard defect in L-4 is the reason this matters.

**L-3 — no System Administrator account can be created or managed through the
application, and there is exactly one.** Only `InitialAdminBootstrap` ever creates
one, and it refuses when any user exists. No administrator — including that account
itself — may edit, deactivate or demote a System Administrator through `/users`;
a test pins that reachability check. Good news: the top account cannot be locked
out by a mistake in the screens. Bad news: a forgotten System Administrator
password, or an account switched off in the database, is unrecoverable without SQL.
*Do instead:* create a second System Administrator once, by hand, following
`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md` §4, and record the credentials in
whatever the organisation uses for break-glass access.

**L-4 — the dashboard shows one role, not all of them.** `PageController` picks a
"primary role" from an unordered set and filters the cards by that single value, so
a person with two roles added by hand may lose the Assignments / Returns / Reports
cards while still being allowed to open those pages.
*Do instead:* use the direct addresses (`/assignments`, `/returns`, `/reports`).
This is display-only and grants nothing. The fix is to prefer the
highest-privileged role — a small change, but it sits in an area with its own
tests, so it is a decision for the developer, not an installation tweak.

**L-5 — at most one Inventory Admin account, active or not.** Creating a second is
refused ("An Inventory Admin account already exists."), and deactivating the only
one leaves no Inventory Admin: Staff and Manager accounts cannot be managed by
anyone until it is reactivated, because a System Administrator may not manage them
(L-2's counterpart in `canManageTarget`).
*Do instead:* when the role moves on, edit that account rather than creating a new
one, and never leave the organisation with no Inventory Admin for long.

**L-6 — sign-in has no lockout and no throttling.** A wrong password fails
quietly, the same as a wrong email; there is no attempt counter, no back-off and no
alert. *Mitigation:* this is a local-network application with no port forwarding
(see L-10). Do not expose it to the internet as it stands.

**L-7 — sessions expire after the framework default (30 minutes idle).** Nothing
in `application.properties` changes it. A long form left open loses its input.
*Do instead:* keep lengthy notes outside the browser and paste them in.

### Workflow

**L-8 — nobody may approve their own request or resolve their own incident.**
Server-enforced for every approver role ("Inventory Managers are not permitted to
approve or reject their own requests."). It is a control, not a bug.
*Consequence:* a site with a single approver stalls whenever that approver needs an
asset. Staff a second approver (an Inventory Admin or a second Manager).

**L-9 — no notifications leave the application.** There is no email, SMS or push.
A request decision, an incident outcome and an assignment land in
`/notifications`, which nobody sees until they sign in.
*Do instead:* make checking notifications part of the working routine, or tell
people directly. Treat "I never saw the message" as a process gap, not a bug.

**L-10 — the application is HTTP, not HTTPS.** No TLS is configured anywhere
(`SecurityConfig` has no channel encryption, and `server.ssl.*` is unset), so the
password crosses the network in clear text within whatever network it crosses.
*Position:* acceptable only on a network the office controls, with no port
forwarding, no Wi‑Fi guest access to that segment, and the database reachable from
the application host only. Putting this on the internet, or on an uncontrolled
network, needs a reverse proxy with TLS — a decision and a change, not a setting.

**L-11 — a damaged or broken return opens an incident automatically, and the
manager who records the return is not prompted to confirm it.** The behaviour is
deliberate (the repair has to be tracked), but it means raising the same incident
by hand afterwards produces two records for one event.
*Do instead:* check `/incidents` before reporting something a return already
captured.

**L-12 — `RETIRED` is terminal, and an assigned asset cannot be retired.** There is
no path from `RETIRED` back to any other status. *Do instead:* use
`UNDER_INSPECTION` or `UNDER_REPAIR` while you are still deciding; retire only
when it is final. Return the asset first, so the holder is off the hook.

**L-13 — assets and people are never deleted, only statused.** No delete endpoint
exists for properties or accounts, and the reference rows are guarded by foreign
keys. This is what keeps the audit trail intact; it is not a missing feature.

### Data and reporting

**L-14 — the `audit_logs` table is never written.** It is created by
`database/schema.sql` and holds nothing: no entity, repository or service in
`backend/src/main` references it. The audit report reads `property_status_history`
and the workflow tables instead. *Do not* promise anyone "the audit log will show
who did X" — say "the status history and the workflow records show it".

**L-15 — there is no migration tool.** No Flyway or Liquibase in `backend/pom.xml`;
`spring.jpa.hibernate.ddl-auto=none`, so nothing alters the schema by itself.
Schema changes are hand-written SQL applied in order, and `docs/RELEASE-PROCESS.md`
requires them to be listed in the release notes with a rollback statement.

**L-16 — reports and CSV exports contain personal data**: names, email addresses,
employee codes, departments, asset locations, and who held what. The same care as a
printed register applies; they belong on office machines.

### Operations

**L-17 — backups cover the database only.** There is no file upload in the
application (no `MultipartFile` anywhere in `backend/src/main`), so there is
nothing on disk to lose apart from the database, the configuration file
(`deploy\env.local.ps1`, deliberately **not** in a backup — it is a secret, kept
separately) and the logs.

**L-18 — stopping the server can be a force-stop.** `deploy\stop-server.ps1` asks
the process to close, and a Java process started without a window cannot accept
that request, so after a short wait it is stopped directly. Committed work is safe
(InnoDB), but whoever was filling a form loses that one unsaved operation.
*Do instead:* stop outside office hours, and say so beforehand.

**L-19 — `mvnw -o clean package` fails on a machine with no Maven cache for the
archiver plugins** (`maven-archiver`, `plexus-archiver`, `commons-io` missing under
`.m2`). `-o spring-boot:run` works offline and was used to start the application
successfully. *Do instead:* run the packaged build with network access once on the
server, or keep using `start-server.ps1`. Recorded in the
`docs/DEPLOYMENT.md` troubleshooting table.

**L-20 — the test suite needs a real database, and writes to it.** Several
`@SpringBootTest` classes run against the schema the configuration points at and
commit work (the concurrency proofs only mean something if two transactions really
cross), so `mvnw test` cannot run on a machine with no MySQL, and there is no
in-memory or container-based substitute configured. The classes create invented rows
and delete them again — verified: 456 tests passed and the schema's row total was
afterwards identical to that morning's backup. *Consequence:* nobody has set up
automated CI for this project, and the first person to do it needs to give the
pipeline a scratch schema rather than the office one. *Do instead:* run the suite
before each release per `docs/RELEASE-PROCESS.md` §3.

## 3. Items this installation did not finish (open, by design)

These are not defects in the software; they are steps that need an elevated shell,
a second physical machine, or a decision from the office. They are the first
things to close after hand-over.

| ID | Item | Status and what is blocking it |
| --- | --- | --- |
| O-1 | **Host firewall rules for office computers** | `deploy\configure-firewall.ps1` is written, validated with `-WhatIf` (it prints the chosen adapter, the address, the DHCP and Public-profile warnings and the rule previews) but **not applied** — this session's shell is not elevated (`#Requires -RunAsAdministrator` refuses). Needs a machine administrator to run it. |
| O-2 | **Database exposure** | MySQL listens on all interfaces (`bind-address` is not set in `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`), and two pre-existing inbound **allow** rules cover 3306 and 33060 on every profile (`{9D66050B-…}`, `{AFB59EA4-…}`). The script disables the unrestricted ones; restricting to the application host (or `bind-address = 127.0.0.1`) was **deliberately not executed** on a live office database — `docs/DEPLOYMENT.md` carries the exact change for the network administrator to apply. |
| O-3 | **Reachability from a second physical computer** | Proven at host level: the application binds `:::8091`, and `/api/health` and `/login` both answered over the host's LAN address `172.28.24.143`, with a CSRF token in the login page. **Not** proven from another desk, because there is only one machine here. Someone must open `http://<host>:8080/login` from an office computer and sign in. |
| O-4 | **The intended office network is undecided** | The host roamed between `192.168.216.0/24` (phone hotspot) and `172.28.0.0/19` (SLIIT-STD 3, a **Public** profile) during testing, and its address came from DHCP. A reserved address or DNS name, and a decision about which segment the system lives on, are prerequisites — `docs/DEPLOYMENT.md` has the fill-in table. |
| O-5 | **Backup schedule, off-server copy and named owners** | `deploy\backup-database.ps1` runs and is proven restorable, but the Task Scheduler entry, the destination for the off-server copy, and the names in §1 above are the office's to set. Retention deletion is opt-in by design. |
| O-6 | **A second System Administrator** | See L-3. Recommended, not created, because it is direct database work on live data. |
| O-7 | **Full build from a clean environment** | Not attempted: it would need the Maven artifacts downloaded and a spare machine. `docs/RELEASE-PROCESS.md` keeps it as a pre-release step. |

## 4. What is verified, and how you can check it yourself

| Claim | Evidence |
| --- | --- |
| The setup documentation works | Followed literally on a fresh schema: `database/schema.sql` produced 20 tables, 4 roles, 10 physical areas, 3 categories, 0 departments, 0 users; the department insert then gave `department_id` 1, which is what `BOOTSTRAP_ADMIN_DEPARTMENT_ID` defaults to. All three ways of feeding the file in `docs/DEPLOYMENT.md` §4.1 were run this way and gave the same counts: the PowerShell pipe, `-e "SOURCE …"`, and `Start-Process -RedirectStandardInput`. Scratch schemas dropped afterwards. |
| A backup restores | `docs/BACKUP-AND-RESTORE.md`: real dump 34,150 bytes → archive 5,453 bytes with a recorded SHA-256; scratch restore produced 20 tables / 42 rows; live vs restored counts identical; scratch schema then dropped. Run twice. |
| The application serves the LAN | `docs/DEPLOYMENT.md`: startup log (`Tomcat started on port 8091`, `Database version: 8.0.46`), `netstat` listener `:::8091`, health `UP` and `/login` 200 over `172.28.24.143`. |
| Authorization is server-side | `SecurityConfig` route rules plus controller/service checks; `backend/src/test/java/.../security/` (`SecurityAuthorizationTest`, `PropertySecurityTest`, `StateChangingRouteTest`, `WorkflowListAccessTest`, `AuthenticationFlowTest`) and the controller tests. |
| The whole suite passes | 456 tests in 30 classes, 0 failures, 0 errors, 0 skipped, exit code 0 (`.\mvnw.cmd -o clean test`, 2026-10-30). |
| Configuration guards are enforced, not just documented | `DeploymentReadinessTest` (11 tests): `ddl-auto=none`, bootstrap off by default, SQL logging off, password from the environment with no default, no `server.address` pin, no credential in the tracked configuration or template, ignores in `.gitignore`, deployment scripts present, health payload carries nothing personal. Proven to bite: turning `show-sql` on made the suite fail with exit code 1. |
| Logs hold no credentials | `spring.jpa.show-sql=false` in the shipped configuration; searching the captured logs for `password`, `token`, `secret` and `BCrypt` (case-insensitive, `Select-String`, command in `docs/DEPLOYMENT.md` §9) returns **0 matches** on the start/health-check/stop logs; `deploy\logs\` is git-ignored. Scope note: those runs contain no sign-in, so §9 says which part of the claim is code behaviour and which part is that search. |
| Documented recovery SQL works | The statements in `docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md` §4–5 were executed against a scratch restore of a real backup. |
| Nothing secret would be committed | `git check-ignore -v` matches `deploy/env.local.ps1`, `deploy/backups/test.zip`, `deploy/logs/test.log` and `backend/target/`; `git ls-files --others --exclude-standard` lists only scripts, `.example` templates and docs; a scan of every file due to be added for the real `DB_PASSWORD` value returned no hits. The only emails in the docs are `@example.org` placeholders. |

## 5. Things people ask for that this system will not do

| Request | Answer |
| --- | --- |
| "Give the Manager access to edit assets" | `/properties` is administrator-only by design. The choice is a role change or a deliberate `SecurityConfig` change — never a hidden-button workaround, and never an ad-hoc database edit of a role row. |
| "Just delete this asset, it was entered wrong" | Register it correctly and retire the wrong one, or ask an administrator to change its status. Nothing is deleted; history depends on that. |
| "Send me an email when it's approved" | There is no email. See L-9. |
| "Let me log in with my employee code" | The username is the email address. Changing that is a code change with tests attached to the login flow. |
| "Add an Auditor role" | The four role names are hard-coded in `SecurityConfig` and the services; a new row in `roles` authenticates and sees nothing. See L-15 and `docs/ROLE-PERMISSION-MATRIX.md` §5. |
| "Turn off the 'approve your own request' block for one person" | No. See L-8. Two approvers is the fix. |
| "Put it on the internet so the branch office can use it" | Not as it stands: HTTP with no sign-in throttling (L-6, L-10). Branch access needs a VPN or a TLS-terminating proxy plus the hardening that goes with it. |
