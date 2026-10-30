# System Administrator Guide — Running the Asset Management System

For the person signed in with **System Administrator** (`SYSTEM_ADMIN`). You own
the installation, the accounts above Inventory Admin, the backups, and every
change that touches the server or the database directly.

Everything in this guide was read out of the code in this repository, and the
procedures marked **tested** were run on the machine used to build it. Where
something could not be finished here, it says so.

## 1. What only you can do

| Ability | Where |
| --- | --- |
| Everything an Inventory Admin can do: register, reference data, accounts for Staff/Manager, stores, reports | `/properties`, `/departments`, `/physical-areas`, `/property-categories`, `/property-types`, `/users`, `/assignments`, `/returns`, `/reports` |
| Open or change the **Inventory Admin** account | `/users` — the only account you may act on |
| `/office-staff` and `/admin` landing pages | Not available to Inventory Admin |
| Deploy, restart, back up, restore, firewall | This guide + `docs/DEPLOYMENT.md`, `docs/BACKUP-AND-RESTORE.md` |
| Direct database work: password recovery, a second System Administrator | Sections 4 and 5 |

Read `docs/ROLE-PERMISSION-MATRIX.md` before changing any account. The short
version: **the account screens let you manage exactly one account — the Inventory
Admin's.** Staff and Manager accounts belong to that account to manage, and no
screen anywhere in the application can create, edit, deactivate or promote a
System Administrator. That is deliberate in the code (`canManageTarget` refuses
any target that is not a sole `INVENTORY_ADMIN`), and it means your powers over
accounts are exercised in the database, not on a page.

## 2. Installation, in the order that works

Full detail, including versions and troubleshooting, is in `docs/DEPLOYMENT.md`.
The sequence matters because of foreign keys:

1. Create the schema and apply `database/schema.sql`. It creates tables and seeds
   roles, physical areas, categories — **not** departments and **not** users.
2. Create the least-privilege database account the application will sign in with,
   from `deploy/database-access.sql.example`. Never use MySQL root for the app.
3. Set the configuration in `deploy/env.local.ps1` (copied from
   `deploy/env.example.ps1`) or as system environment variables: `DB_USERNAME`,
   `DB_PASSWORD`, `SPRING_DATASOURCE_URL`, and the `BOOTSTRAP_ADMIN_*` values.
   Nothing secret is ever committed — `deploy/env.local.ps1` is git-ignored.
4. **Insert one department row before bootstrapping an administrator.** Every user
   row requires a department, and the bootstrap defaults to department ID 1:
   ```sql
   INSERT INTO departments (name, description) VALUES ('Head Office', 'Default department');
   ```
   (Check the column list in `database/schema.sql` before running it on a live
   system; an inactive department is refused by the account screens too.)
5. Enable the bootstrap (`BOOTSTRAP_ADMIN_ENABLED=true`; the shipped default in
   `application.properties` is **false**, which is what you want on a running
   system) and start with `.\deploy\start-server.ps1`. The first start creates the
   account and logs `Initial System Admin account created successfully.` On any
   later start, or any start where users already exist, it logs
   `Admin bootstrap skipped: users already exist.` and does nothing.
6. Sign in at `/login` with the **email address** you configured, and check
   `/api/health` from another machine.
7. **Turn the bootstrap off again**, then create the Inventory Admin account
   (section 3) so that day-to-day account work does not run through yours.

Do not leave `BOOTSTRAP_ADMIN_ENABLED=true` on a server that has accounts. It
is harmless (it refuses to run when any user exists) but it invites the next
operator to think credentials belong in the environment of a production start.

## 3. Accounts: what you can actually change

You may create **one** account type: `INVENTORY_ADMIN`, and there may be only one
such account — active or inactive — because a second is refused with "An Inventory
Admin account already exists."

| Situation | What to do |
| --- | --- |
| New Inventory Admin (first time) | `/users/new`, role `INVENTORY_ADMIN`, active department, initial password handed over in person |
| The Inventory Admin changed | `/users/edit/{userId}` on that account: employee code, names, email, department, position. The password and active flag are **preserved** by the edit, so the newcomer keeps the old password — reset it as in section 5 immediately, and treat the account as theirs only after that |
| Inventory Admin left, no replacement yet | Deactivate that account (`/users/status`). It still counts as "the Inventory Admin exists", so creating a fresh one will be refused; reactivate and edit the same row when the role is filled again |
| Staff or Manager account | **Ask the Inventory Admin.** You cannot open, edit or deactivate those accounts through the application, even though you can create them nowhere else |
| Someone leaves | The Inventory Admin deactivates their account. Deactivate, never delete — assignments, requests, incidents and history point at that row |
| They hold an asset | Take it back through `/returns` first, then deactivate |

Password rules, since they surprise people: 8–100 characters at creation, email
must be unique and is the sign-in name, employee code must be unique, and a
deactivated account is refused even with a valid session.

## 4. A second System Administrator (database work)

The application cannot create one. There is exactly one, from the bootstrap. That
is a single point of failure: if you are on leave with a forgotten password, the
office cannot administer itself. Recommendation: keep a second account, created
once, by hand.

**Tested shape of the procedure** — create the person as an ordinary account first
(so the employee code, email, department and password all come from the normal
screens and stay consistent), then grant the role in the database. Connect with
the schema named on the command line, so the session has a current database — the
multi-table statements below need it:

```text
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --user <app-user> --host 127.0.0.1 --port 3306 bssa_asset_management
```

```sql
-- 1. confirm both rows first; the role id is looked up, never assumed
SELECT user_id, email, is_active FROM users WHERE email = 'second.admin@example.org';
SELECT role_id, name FROM roles WHERE name = 'SYSTEM_ADMIN';

-- 2. grant it
INSERT INTO user_roles (user_id, role_id)
SELECT u.user_id, r.role_id
FROM users u JOIN roles r ON r.name = 'SYSTEM_ADMIN'
WHERE u.email = 'second.admin@example.org';

-- 3. verify
SELECT u.email, u.is_active, r.name
FROM users u JOIN user_roles ur ON ur.user_id = u.user_id JOIN roles r ON r.role_id = ur.role_id
WHERE r.name IN ('SYSTEM_ADMIN', 'INVENTORY_ADMIN');
```

Consequences to accept before you do it:

- The account screens refuse to manage **any** account holding two roles, and
  refuse to manage a System Administrator, so this account is maintained the same
  way — by hand. Keep it `SYSTEM_ADMIN` only unless you have read
  `docs/ROLE-PERMISSION-MATRIX.md` §5 on the dashboard's primary-role defect.
- Take a backup immediately before and after database edits
  (`.\deploy\backup-database.ps1`). The restore script is the only proven way back.
- The second account must sign out and in again to pick up the new role.

## 5. Recovering a forgotten password (database work)

**There is no password reset screen.** A password is written once, at account
creation, as a BCrypt hash; no endpoint changes it afterwards, and the application
has no "forgot password" flow, no email, and no token to hand out. Recovery is
therefore done in the database, and the safe way is to reuse the application's own
hashing rather than inventing one.

1. Take a backup first.
2. Have the Inventory Admin create a **temporary Staff account** through `/users/new`
   with the new password. That makes the application produce a valid BCrypt hash
   for you. (You cannot create this temporary account yourself — `INVENTORY_ADMIN`
   is the only role you may grant, and one already exists.)
3. Copy that hash onto the account that needs it, and remove the temporary account
   (same session, with the schema selected as in section 4):
   ```sql
   -- read both rows; note the ids
   SELECT user_id, email, password_hash FROM users
   WHERE email IN ('temp.reset.dummy@example.org', 'person.needing.reset@example.org');

   UPDATE users target
   JOIN users src ON src.email = 'temp.reset.dummy@example.org'
   SET target.password_hash = src.password_hash
   WHERE target.email = 'person.needing.reset@example.org';

   -- the temporary account has no workflow rows if you use it for nothing else;
   -- user_roles references it with ON DELETE RESTRICT, so clear that first
   DELETE ur FROM user_roles ur
   JOIN users u ON u.user_id = ur.user_id
   WHERE u.email = 'temp.reset.dummy@example.org';
   DELETE FROM users WHERE email = 'temp.reset.dummy@example.org';
   ```
   Every statement above was run against a scratch restore of a real backup and
   behaved as described: the target row came out holding the temporary account's
   hash and nothing else changed. The `user_roles` row must be cleared before the
   `users` row because that foreign key is `ON DELETE RESTRICT` (as defined in
   `database/schema.sql`). A genuine stored hash is 60 characters beginning
   `$2a$10$` — every account in the office data looks like that, so a hash of a
   different length is a sign you are looking at the wrong row.
   If the temporary account has acquired any referencing row (a notification, say),
   deactivate it instead of deleting it, and tell nobody it exists — the point of
   deleting is to keep the register of people honest, not to protect the data.
4. Hand the password over in person and ask them to sign in once. Do not put
   passwords in email, chat, or a ticket.
5. Back up again.

Never paste a hash into a document, ticket, or the repository. Never write the
plain password into SQL.

For a lost **System Administrator** password specifically, steps 2–3 work as long
as the Inventory Admin account is available. If neither is available, the options
are: the second System Administrator from section 4, or a controlled restore of a
backup taken while an administrator could sign in. Re-running the bootstrap is not
a recovery route on a live system — it refuses to run while users exist, and
making it run means wiping the schema.

## 6. Day-to-day operations

| Task | Command / page | Notes |
| --- | --- | --- |
| Start | `.\deploy\start-server.ps1` (add `-Port 8081` to test without disturbing the live instance) | Sources `deploy\env.local.ps1` itself, refuses to start with no `DB_PASSWORD`, writes `deploy\logs\server-<timestamp>.log` |
| Stop | `.\deploy\stop-server.ps1 -Port 8080` | Refuses to kill a process it cannot match to the port; see the note on force-stopping in `docs/DEPLOYMENT.md` |
| Health | `http://<host>:8080/api/health` | Unauthenticated, returns only `UP` and the application name — safe to let the office probe it |
| Backup | `.\deploy\backup-database.ps1` | Daily, scheduled; writes a zip and a checksum line into `deploy\backups\backup-manifest.jsonl` |
| Restore test | `.\deploy\restore-database.ps1 -BackupFile … -TargetDatabase bssa_am_restore_verify` | Quarterly, into a scratch schema — **tested**: 20 tables, 42 rows, counts identical to live |
| Firewall | `.\deploy\configure-firewall.ps1 -WhatIf` first, then without it from an elevated prompt | **Not applied here**: this build machine's shell is not elevated. Needs a machine administrator |
| Logs | `deploy\logs\` (git-ignored) | Reviewed for credentials before any copy leaves the server |

Verify the firewall and the reserved host address with the network administrator
before telling the office the address; `docs/DEPLOYMENT.md` records what was and
was not proven on this machine, including that this host roamed between
`192.168.216.x` and `172.28.24.x` during testing — which is exactly why a static
address or DNS name is a requirement.

## 7. Releases

`docs/RELEASE-PROCESS.md` is the checklist you follow: full test suite, `git
status` and `git diff` review, no secrets in the diff, tag only with the owner's
approval, and a rollback plan that starts with a backup. Take a backup
immediately before deploying, so the previous state is restorable, and note in
`CHANGELOG.md` anything an operator has to do by hand (a schema change, a new
environment variable).

## 8. Rules that keep you out of trouble

- **Authorization is server-side.** `SecurityConfig` maps the routes and the
  services re-check the actor and the record's owner. If you are ever tempted to
  "fix" a permissions complaint by hiding a button, that changes nothing — the
  check is in the route rules and the controller.
- **Never put a credential in the repository, a template, an issue, or a log.**
  The shipped configuration carries placeholders only; `DB_PASSWORD` has no
  default, so the application refuses to start rather than guess.
- **Do not hand out the database account.** `deploy/database-access.sql.example`
  exists so each operator can be revoked individually.
- **Deactivate, never delete, a person.** Same rule for departments, areas,
  categories and types that are in use — the foreign keys will refuse, and they are
  doing your auditing for you.
- **Two pairs of hands for approvals.** No account may approve its own request or
  resolve its own incident, so a site with one approver will stall. That is a
  control; staff a second approver.
- **Reports contain personal data.** Staff names, emails, employee codes and asset
  locations are in `/reports` and in CSV exports. They stay on office machines.

## 9. When something is wrong

Work the tables in `docs/DEPLOYMENT.md` §10 (startup, database, login, network)
and `docs/KNOWN-ISSUES-AND-SUPPORT.md`, which lists the limitations already known
in this build and who to call. Before you raise anything with the developer: note
the time, the exact message, the URL, the signed-in account and role, and whether
it reproduced from another machine — that is the difference between a five-minute
fix and a guess.
