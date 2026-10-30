# Changelog — BSSA Asset Management System

Newest first. Every entry states what an operator has to **do** as well as what
changed, because a release note that only describes code leaves somebody's server
running the old schema.

Rollback statement: how to undo the change if the release goes wrong. See
`docs/RELEASE-PROCESS.md` §10 for the procedure it belongs to.

## [Unreleased] — deployment, backup, documentation and final acceptance work

Artifact version is still `0.0.1-SNAPSHOT` (`backend/pom.xml`); nothing here has
been tagged.

### Added

- `deploy/backup-database.ps1` — mysqldump of the application schema, compressed
  into a `.zip` archive, with the SHA-256 appended to
  `deploy/backups/backup-manifest.jsonl`.
  Removes a partial dump if mysqldump fails, so an empty file cannot masquerade as
  a backup. **Verified**: produced a 34,150-byte dump / 5,453-byte archive.
- `deploy/restore-database.ps1` — verifies the checksum against the manifest before
  touching the server, refuses to overwrite the live schema without
  `-AllowLiveOverwrite`, can retarget a copy into a scratch schema for testing, and
  prints per-table row counts afterwards. **Verified**: a scratch restore produced
  20 tables / 42 rows, identical counts to live; the negative controls refused.
- `deploy/start-server.ps1` / `deploy/stop-server.ps1` — start with the packaged jar
  when one exists and `spring-boot:run` otherwise, refuse to start without
  `DB_PASSWORD`, write logs to `deploy/logs/`, print only real LAN addresses
  (virtual Hyper‑V/WSL adapters excluded); stop matches the process by port and
  refuses an owner it cannot identify. **Verified**: start on port 8091, health and
  `/login` answered over the LAN address, stopped in 3.2 s with the port released.
- `deploy/configure-firewall.ps1` — creates an inbound rule for the application port
  restricted to the office subnet, and disables the machine-wide allow rules for
  MySQL 3306/33060. Requires elevation; `SupportsShouldProcess` makes `-WhatIf`
  meaningful. **Validated by dry run only — not applied** (`O-1`).
- `deploy/env.example.ps1` and `deploy/database-access.sql.example` — placeholders
  only: the environment variables the application reads, and the least-privilege
  database account plus the host-restriction statements.
- `.gitignore` at the repository root — excludes `deploy/env.local.ps1`, backups,
  dumps, logs and OS/editor state, while keeping the `.example` templates tracked.
- Documentation: `docs/DEPLOYMENT.md`, `docs/BACKUP-AND-RESTORE.md`,
  `docs/ROLE-PERMISSION-MATRIX.md`, `docs/KNOWN-ISSUES-AND-SUPPORT.md`,
  `docs/RELEASE-PROCESS.md`, and one guide per role
  (`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`, `-INVENTORY-ADMIN`, `-INVENTORY-MANAGER`,
  `-OFFICE-STAFF`). `README.md` rewritten as the index into them.
  Every version, route, message and command in them was taken from this repository
  or from the running system — `docs/KNOWN-ISSUES-AND-SUPPORT.md` §4 lists how each
  claim can be re-checked.
- `CHANGELOG.md` (this file).
- Acceptance tests for the four checklist items that had no automated evidence.
  None of them needed a change to application code — each one found the behaviour
  already correct and pinned it down.
  - `controller/ReferenceDataCrudTest.java` (30 tests) — create, view, update and
    deactivate for departments, physical areas, property categories and property
    types, including the duplicate-name refusals, the blank-name refusals,
    `Invalid usage type.`, the inactive-category refusal and the missing-row
    messages.
  - `security/ReferenceDataAccessTest.java` (6 tests) — the same 24 routes through
    the real filter chain: Staff and Inventory Manager are refused every page and
    every write, and the refusal provably saves nothing.
  - `controller/TemplateLinkRoutingTest.java` (6 tests) — reads all 36 templates,
    resolves their 288 `@{...}` links and checks each against the routes the
    controllers actually register, plus form targets, button types and stylesheet
    paths. **Verified by breaking it on purpose**: one invented link in
    `dashboard.html` fails it.
  - `ErrorPageExposureTest.java` (11 tests) — the error page and the model behind
    it carry no stack trace, exception name, SQL, connection string, file path or
    credential, and the shipped configuration leaves `server.error.*` alone.
    **Verified by breaking it on purpose**: setting
    `server.error.include-stacktrace=always` fails it.

### Changed

- `backend/src/main/resources/application.properties` — SQL statement logging is off
  (`spring.jpa.show-sql=false`, `spring.jpa.properties.hibernate.format_sql=false`)
  so the office server does not write every query and its parameters into the
  console log. A developer overrides with `SPRING_JPA_SHOW_SQL=true`; no file edit
  needed.
  *Rollback:* delete the two lines, or set `SPRING_JPA_SHOW_SQL=true`. No data effect.
- `README.md` — a scope paragraph stating that the register holds reusable physical
  items and that consumables are out of scope, pointing at limitation `L-21`.
  *Rollback:* delete the paragraph. No data effect.
- `docs/KNOWN-ISSUES-AND-SUPPORT.md` — `L-21` and `O-8` added, the seven new
  evidence rows in §4, and `L-19`/`L-20` re-measured. Documentation only.
  *Rollback:* revert the file. No data effect.
- `deploy/start-server.ps1` — now prints the process that really holds the port
  ("Application : PID …", read from `Get-NetTCPConnection` once the health check
  answers) as well as the launcher's number. Those are two different processes when
  `java` on PATH is the Oracle `javapath` launcher, which is the default install: an
  operator who acted on the old single line would have been watching, or stopping,
  a process that was not the application. Console output only — how the application
  is started is unchanged. **Verified**: jar start on ports 8091 and 8092; launcher
  PIDs 36396 and 32784, JVM PIDs 6932 and 22120, and `stop-server.ps1` stopped the
  PID the script printed in both runs.
  *Rollback:* delete the `$owningProcessIds` block and put back the one
  `Write-Host "Process started (PID …)"` line. No data effect.
- `docs/DEPLOYMENT.md` §5 — records that the packaged-jar build works with network
  access (so `L-19` is an offline-only limitation) and explains the two process
  numbers the start script prints. `docs/KNOWN-ISSUES-AND-SUPPORT.md` §4 — three
  further evidence rows. Documentation only.
  *Rollback:* revert the two files. No data effect.

### Known issues created, found or carried by this work

Numbered items live in `docs/KNOWN-ISSUES-AND-SUPPORT.md`: no password reset (`L-1`),
one role per account (`L-2`), no way to create or manage a System Administrator in
the application (`L-3`), dashboard shows one role of several (`L-4`), single
Inventory Admin (`L-5`), no sign-in throttling (`L-6`), 30-minute session (`L-7`),
no self-approval (`L-8`), in-app notifications only (`L-9`), HTTP without TLS
(`L-10`), auto-opened incident on a damaged return (`L-11`), `RETIRED` terminal
(`L-12`), nothing is deleted (`L-13`), `audit_logs` never written (`L-14`), no
migration tool (`L-15`), exports contain personal data (`L-16`), database-only
backups (`L-17`), force-stop on shutdown (`L-18`), offline `package` build failure
(`L-19`), and the reusable-not-consumable boundary being a rule for the Inventory
Admin rather than something the software enforces (`L-21`).

Open items that need somebody with more access than a build machine: apply the
firewall rules (`O-1`), restrict database exposure (`O-2`), sign in from a second
physical computer (`O-3`), decide the office network and give the host a reserved
address (`O-4`), schedule backups and name the owners (`O-5`), create a second
System Administrator (`O-6`), verify from a clean environment (`O-7`), and confirm
which spelling of the organisation's name the screens should carry (`O-8`).

### Schema

No change. `database/schema.sql` is untouched by this work, so no migration and no
rollback SQL is needed for these files.

## 0.0.1-SNAPSHOT — the application as installed

Summarised from the repository's history; this project had no changelog before the
section above, so treat this as a description of the current build rather than a
release record.

**Modules.** Departments, physical areas, property categories and property types
(with `COMMON` / `INDIVIDUAL` usage type) as reference data; the asset register with
seven statuses and a status history; asset requests with line items, review and
fulfilment; assignments; returns, which set the asset's status from the recorded
condition and open an incident when it comes back damaged or broken; damage,
breakdown and loss incidents; per-account notifications; dashboards and five reports
with CSV export of the register; account management with four roles
(`SYSTEM_ADMIN`, `INVENTORY_ADMIN`, `INVENTORY_MANAGER`, `OFFICE_STAFF`) and a
BCrypt-hashed password set at creation.

**Stack.** Spring Boot 4.1.1 parent, Java 17, Spring Security 7.1.1, Spring Data JPA
with Hibernate 7.4.5, Thymeleaf 3.1.5, MySQL Connector/J 9.7.0 against MySQL 8.0.46,
server-rendered only — no separate frontend build.

**Security posture.** Route rules in `SecurityConfig`, re-checked in the controllers
and services, plus ownership rules the server enforces on its own: only the
requester's own pending request may be edited or cancelled, nobody may approve or
review their own request or incident, only active reviewer accounts may act, an
asset may only be issued when it is `AVAILABLE` **and** `GOOD`, assignment takes a
row lock so two people cannot issue the same unit, a System Administrator may not
deactivate their own account, `csrf` is on for every state-changing request, and no
credential appears in a file that is tracked.

**Tests.** Under `backend/src/test/java/com/bssa/assetmanagement/` — controller
slices, service unit tests, security authorisation tests, an authentication flow
test and a concurrency integration test for allocation, alongside the application
context test. Run with `.\mvnw.cmd clean test` in `backend`.

## Undocumented history

Earlier commits are the module-by-module build of the application; `git log --oneline`
reads:

```
f7ac336 Add property assignment and request services, repositories, and corresponding test suites
982f6bf Add asset management backend services, controllers, templates, and tests
a068434 Add dashboard report service, controllers, views, and associated tests
99f5af0 Implement notification and asset management services, repositories, and controllers with tests
cfa7935 Implement property return functionality with controller, service, views, and tests
a4e1feb Implement property incident management module and security configuration
804f7ae Add property assignment and request management entities, repositories, services, and controllers
82069c3 Implement property request module with controllers, services, repositories, and UI templates
1ee880c Implement property and user management modules with security configurations and tests
b698426 Prevent duplicate Inventory Admin accounts          <- the rule behind L-5
cc09328 Add tests for unique Inventory Admin account
6e2eeb4 Secure role-based user account management
```
