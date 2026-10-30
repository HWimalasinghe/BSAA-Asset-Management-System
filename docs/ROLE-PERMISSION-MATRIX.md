# Role and Permission Matrix — BSSA Asset Management System

Four roles exist in the database, seeded by `database/schema.sql`:

| Role | Name in the system | Intended holder |
| --- | --- | --- |
| `SYSTEM_ADMIN` | System Administrator | IT / whoever owns the installation and the accounts |
| `INVENTORY_ADMIN` | Inventory Admin | The person who maintains the asset register and reference data |
| `INVENTORY_MANAGER` | Inventory Manager | Stores / whoever issues and takes back assets |
| `OFFICE_STAFF` | Staff | Everyone who uses an asset and reports on it |

The database allows a person to hold more than one role, and the security layer
honours all of them. **The account screens do not** — `UserManagementController`
accepts exactly one role per account, so a second role can only be added by
changing the database. Read [Notes and gaps](#notes-and-gaps) before doing that,
because the dashboard does not always show all of it.

Everything below is enforced **on the server**, in
`backend/src/main/java/com/bssa/assetmanagement/config/SecurityConfig.java` and
inside the controllers and services. Hiding a button is not a permission: the
route check happens whether or not a link was ever rendered, so a staff member
who types `/properties` in the address bar is refused, not admitted.

---

## 1. Pages and URL areas

`✔` allowed, `–` refused by the server. Taken directly from `SecurityConfig`.

| URL area | System Admin | Inventory Admin | Inventory Manager | Staff | What lives there |
| --- | :---: | :---: | :---: | :---: | --- |
| `/login` | open to all | open to all | open to all | open to all | Sign-in |
| `/api/health` | open to all | open to all | open to all | open to all | Unauthenticated readiness probe; says only `UP` and the application name |
| `/`, `/admin` | ✔ | ✔¹ | ✔¹ | ✔¹ | Dashboard (¹ see the primary-role note) |
| `/inventory-manager` | ✔ | ✔ | ✔ | – | Manager landing area |
| `/office-staff` | ✔ | **–** | ✔ | ✔ | Staff landing area |
| `/users`, `/users/**` | ✔ | ✔ | – | – | Account management (subject to section 2) |
| `/departments/**` | ✔ | ✔ | – | – | Departments |
| `/physical-areas/**` | ✔ | ✔ | – | – | Physical areas |
| `/property-categories/**` | ✔ | ✔ | – | – | Categories |
| `/property-types/**` | ✔ | ✔ | – | – | Types |
| `/properties/**` | ✔ | ✔ | **–** | **–** | The asset register |
| `/assignments/**` | ✔ | ✔ | ✔ | – | Issue an asset, assignment list |
| `/returns/**` | ✔ | ✔ | ✔ | – | Record a return |
| `/reports/**` | ✔ | ✔ | ✔ | – | Registers, summaries, audit reports, CSV export |
| `/requests/**` | ✔ | ✔ | ✔ | ✔ | Asset requests |
| `/incidents/**` | ✔ | ✔ | ✔ | ✔ | Damage and loss incidents |
| `/notifications/**` | ✔ | ✔ | ✔ | ✔ | The signed-in person's own notifications |

Two rows worth reading twice:

- **`/properties` is administrator-only.** An Inventory Manager works from
  `/assignments`, `/returns` and `/requests`; the service reads asset details for
  those screens internally, so the manager can do the job, but cannot open the
  register itself. If the office expects managers to edit asset records, that is
  a deliberate change to `SecurityConfig`, not a permission to grant on the fly.
- **`/office-staff` excludes Inventory Admin.** An Inventory Admin who wants the
  staff view of requests and incidents uses `/requests` and `/incidents`
  directly — both are open to all four roles.

## 2. Who may act on a workflow

Route access is only half of it; the services add checks on the action and on
whose record it is.

| Action | System Admin | Inventory Admin | Inventory Manager | Staff |
| --- | :---: | :---: | :---: | :---: |
| Submit an asset request | ✔ | ✔ | ✔ | ✔ |
| Cancel or edit **their own** pending request | ✔ | ✔ | ✔ | ✔ |
| Cancel or edit **someone else's** request | – | – | – | – |
| Approve / reject a request (`/requests/{id}/review`) | ✔ | ✔ | ✔ | – |
| Approve or reject **their own** request | – | – | **–** | – |
| Fulfil a request, creating an assignment | ✔ | ✔ | ✔ | – |
| Issue an asset directly (`POST /assignments`) | ✔ | ✔ | ✔ | – |
| Record a return (`POST /returns`) | ✔ | ✔ | ✔ | – |
| Report an incident | ✔ | ✔ | ✔ | ✔ |
| Resolve an incident, approve/reject it | ✔ | ✔ | ✔ | – |
| Return an asset to service after a repair | ✔ | ✔ | ✔ | – |
| View an incident | own | own | own | own — plus all, if review-privileged |
| Register / edit / retire an asset, change its status | ✔ | ✔ | – | – |
| Create or edit departments, areas, categories, types | ✔ | ✔ | – | – |
| Activate / deactivate those reference rows | ✔ | ✔ | – | – |
| Create an account | ✔ | ✔ | – | – |
| Grant a role | INVENTORY_ADMIN only | OFFICE_STAFF, INVENTORY_MANAGER only | – | – |
| Hold more than one role on one account | via the database only | via the database only | via the database only | via the database only |
| Activate / deactivate an account | per section 3 | per section 3 | – | – |
| Open `/reports/**`, export a register | ✔ | ✔ | ✔ | – |
| Read own notifications, mark them read | ✔ | ✔ | ✔ | ✔ |
| Read anyone else's notifications | – | – | – | – |

The four-role "approver" set used by request review and fulfilment is
`INVENTORY_MANAGER`, `INVENTORY_ADMIN`, `SYSTEM_ADMIN`, and the account must be
**active** (`PropertyRequestService`, `PropertyIncidentService`). The same set
governs incident resolution and return-to-service.

**No self-approval.** A request cannot be reviewed by the person who raised it —
`PropertyRequestService` rejects it with "Inventory Managers are not permitted to
approve or reject their own requests." It applies to every approver role, so an
Inventory Admin who also needs a laptop has to ask a second approver. Sites with
exactly one approver will hit this; it is a control, not a bug, and the answer is
a second approver account.

## 3. Account management reach

`UserManagementController` restricts what one administrator may do to another
account, by the role of the **target**. Both `canManageTarget` (which accounts
are offered for editing) and `canAssignRoles` (which roles may be granted) insist
on exactly one role on the target and one role in the submission:

| Target account holds | System Admin | Inventory Admin |
| --- | :---: | :---: |
| OFFICE_STAFF | **–** | ✔ |
| INVENTORY_MANAGER | **–** | ✔ |
| INVENTORY_ADMIN | ✔ | – |
| SYSTEM_ADMIN | **–** | **–** |
| two or more roles | **–** | **–** |

So the model is two levels, not a hierarchy of command:

- The **Inventory Admin** runs day-to-day account work — Staff and Manager
  accounts.
- The **System Administrator** manages exactly one account: the Inventory Admin.
  A System Administrator who wants a Staff account changed, or wants to open the
  register on someone's behalf, does the work in the pages instead and asks the
  Inventory Admin for the account.
- **Nobody may touch a System Administrator account through the application** —
  not even another System Administrator. There is also no screen that creates a
  System Administrator. The one that exists comes from the installation bootstrap
  (`InitialAdminBootstrap`, see `docs/DEPLOYMENT.md`).

Two further account rules that come out of the code:

- **At most one Inventory Admin account**, active or not: creating a second is
  refused with "An Inventory Admin account already exists." Replacing the person
  means editing that account's name, email and employee code, not adding another.
- A deactivated Inventory Admin still counts as "exists", so deactivate and then
  reactivate the same row rather than trying to create a fresh one.

**Lockout risk.** Because no administrator can act on a System Administrator
account, there is no route by which the last System Administrator is deactivated
or demoted from the application — `POST /users/status` additionally refuses to
deactivate the last active System Admin ("You cannot deactivate the last active
System Admin.", checked across the whole account list) and refuses to deactivate
your own account, but a test pins that the earlier reachability check makes that
first guard unreachable from the UI. The real risk is the opposite direction: a
forgotten System Administrator password, or a System Administrator switched off
directly in the database, cannot be repaired through the application at all.
Recovery is database work, spelled out in
`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`:

```sql
-- who can currently administer accounts
SELECT u.email, u.is_active, r.name
FROM users u JOIN user_roles ur ON ur.user_id = u.user_id JOIN roles r ON r.role_id = ur.role_id
WHERE r.name IN ('SYSTEM_ADMIN', 'INVENTORY_ADMIN');
```

See `docs/KNOWN-ISSUES-AND-SUPPORT.md` for the same limitation as a tracked item.

## 4. Passwords and credentials

- Passwords are stored as BCrypt hashes (`SecurityConfig.passwordEncoder`); the
  application never holds the plain value.
- Sign-in uses the account's **email address** as the username
  (`usernameParameter("username")`), which is a common source of "my password is
  right" calls.
- Editing an account (GET `/users/edit/{userId}`, POST `/users/{userId}/edit`)
  does not touch the password at all — there is no password field in that request,
  and a test asserts an edited account keeps the password it already has.
- **There is no password reset anywhere in the application.** A password is set
  once, when an account is created (POST `/users`). A colleague who has forgotten
  theirs is recovered by database work, described in
  `docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`, and tracked as a known limitation.
- Deactivation is POST `/users/status`; it refuses to switch off your own account
  and, in code, refuses to switch off the last active System Administrator.
- CSRF protection is on for every state-changing request, so all create/edit/approve
  submissions carry a per-session token. It is not configured away anywhere.
- There is no sign-in attempt throttling and no lockout. A wrong password simply
  fails, the same as a wrong email. Recorded as a known limitation.

## 5. Notes and gaps

**The dashboard shows one role, not all of them.** `PageController` picks a
"primary role" from the set of roles the person holds and the dashboard cards are
filtered by that single value (the figures on the page are computed from the whole
role set). For a person with exactly one role this is correct — which, because of
the single-role rule in section 3, is everybody the account screens can create.
If a second role is ever added straight into the database, the choice comes out of
an unordered set, so a manager who is also staff may get the staff view — and the
cards for assignments, returns and reports disappear even though the server still
lets them through (`/assignments`, `/returns`, `/reports` are all open to that
role).

Consequences and what to do about it:

- Users with more than one role should be told the direct addresses, or the code
  should be changed to prefer the highest-privileged role. The change is small
  and worth doing, but it is a behaviour change in an area that has its own
  tests, so it is listed as a decision in `docs/KNOWN-ISSUES-AND-SUPPORT.md`
  rather than being made as part of an installation task.
- This is a display defect only. It grants nothing: the refusals in the tables
  above are enforced independently of what any page renders.

**Roles cannot be added at runtime.** The four names are hard-coded in
`SecurityConfig` and in the service checks. The `roles` table is a lookup, not a
permission model — a row called `AUDITOR` would authenticate and see nothing it
was allowed to use.
