# Inventory Admin Guide — The Register and the Reference Data

For anyone signed in with **Inventory Admin** (`INVENTORY_ADMIN`). You own the
data everybody else depends on: the asset register, the reference lists, and the
Staff and Manager accounts.

A System Administrator sees exactly these pages too, so this guide applies to
them as well — with the extra account powers noted in
`docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`.

## 1. Your pages

| Page | Path | What it is |
| --- | --- | --- |
| Property inventory | `/properties` | The asset register — search, sort, page, filter, register, edit, retire |
| Departments | `/departments` | Organisational units, activate/deactivate |
| Physical areas | `/physical-areas` | Where assets live |
| Property categories | `/property-categories` | General, IT, Electronic, … |
| Property types | `/property-types` | The specific item types inside a category |
| Accounts | `/users` | Staff and Manager accounts (see section 5) |
| Assignments / Returns | `/assignments`, `/returns` | Stores work, if you also do it |
| Reports | `/reports` | Registers, summaries, audit history, CSV export |
| Notifications | `/notifications` | Yours |

You are **not** given: `SYSTEM_ADMIN` account management, the `INVENTORY_ADMIN`
role to grant, or `/office-staff` (that route excludes your role — `/requests`
and `/incidents` are both open to you directly).

## 2. Set up the reference data first

The register cannot accept an asset without a type and an area, and both come
from lists you maintain. In order:

1. **Departments** (`/departments`) — needed before accounts, because every user
   row has a department and the database refuses an account without one.
2. **Physical areas** (`/physical-areas`) — names an office can recognise: floor,
   room, store.
3. **Property categories** (`/property-categories`) — the broad grouping.
   General, IT and Electronic are seeded by the installation script.
4. **Property types** (`/property-types`) — one per thing you actually issue,
   inside its category. This is the list staff pick from when requesting, so a
   missing type means a request that cannot be raised. Each type also carries a
   **usage type**, `COMMON` or `INDIVIDUAL`: a shared item that several people use
   over time, or a unit that is issued to one named holder. It is a property of
   the type, so decide it when you create the type rather than per asset.

Each of these has a status toggle. **Deactivating rather than deleting is nearly
always the right move**: rows are referenced by foreign keys from assets,
requests and assignments, and an in-use row cannot be removed. A deactivated
type stops appearing on new forms while history that mentions it stays readable.

## 3. Registering and maintaining assets

**Dashboard → Manage Properties → Register Property** (`/properties/new`).

| Field | Notes |
| --- | --- |
| Property Code * | The label on the asset, e.g. `BSAA-IT-001`. Must be unique — this is what staff type when reporting an incident |
| Property Type * | From your list; the category is derived from it |
| Physical Area * | From your list |
| Serial Number | Optional, but fill it in when the manufacturer printed one |
| Registration Date * | The date the asset entered the register |
| Condition * | `GOOD`, `DAMAGED`, `BROKEN` |
| Description | Anything that identifies it or warns the next holder |

### The seven statuses

| Status | Meaning | Can be issued? |
| --- | --- | --- |
| `AVAILABLE` | On the shelf, in good condition | Yes, if condition is `GOOD` |
| `ASSIGNED` | Out with a holder | No |
| `UNDER_INSPECTION` | Being checked, not usable | No |
| `DAMAGED` | Returned or reported damaged | No |
| `UNDER_REPAIR` | With repair, internal or vendor | No |
| `LOST` | Gone; recorded through an incident, not deleted | No |
| `RETIRED` | End of life — **terminal** | Never |

Change one with `/properties/{id}/status`, which asks for a reason; retire with
`/properties/{id}/retire`. Every change is written to the asset's status history
with who made it and why, and the audit report reads that table.

Rules the server enforces:

- **A retired asset stays retired.** `RETIRED` is terminal; there is no path back
  to any other status. Retire only when you mean it.
- **You cannot retire an asset that is assigned** — "Assigned assets cannot be
  retired. They must be returned first." Take it back through `/returns` first,
  so the holder is off the hook before the asset leaves the register.
- A status change and a retirement are two different acts. An asset sent for
  repair is `UNDER_REPAIR`, not retired; a repair that fails becomes `RETIRED`
  afterwards.

**Assets are never deleted.** The register only ever grows a status. Deleting a
row would leave assignments, returns and incidents pointing at nothing and would
break the audit trail.

## 4. Reports and the register export

**Dashboard → View Reports** (`/reports`): `/reports/assets` (the register, with
status and other filters), `/reports/summary`, `/reports/assignments`,
`/reports/requests`, `/reports/incidents`. The register is also exported straight
from the list page at `/properties/export.csv`.

Useful habits:

- Export the register before a physical count, and reconcile on paper, not by
  editing "while you are in there".
- `LOST`, `DAMAGED` and `UNDER_REPAIR` rows that have not moved in a month are
  the ones to chase.
- Reports contain staff names. They are for the office; the same caution applies
  to CSV files as to printed registers.

## 5. Accounts you may manage

**Dashboard → Manage Users** (`/users`). Create an account with employee code,
first and last name, email, initial password, department, position and role(s).

| Account holding | You may create / edit / activate / deactivate |
| --- | --- |
| `OFFICE_STAFF` | Yes |
| `INVENTORY_MANAGER` | Yes |
| `INVENTORY_ADMIN` | **No** — only the System Administrator may touch that account (which is yours) |
| `SYSTEM_ADMIN` | **No** — and no administrator may, through the application |

Practical points:

- **One role per account, and it is enforced.** The create and edit forms accept a
  single role; submitting two is refused with "You are not permitted to assign the
  selected role." If the office wants a person to be both Manager and Admin, the
  answer is a second account, not a database edit — see the primary-role note in
  `docs/ROLE-PERMISSION-MATRIX.md`.
- **There is exactly one Inventory Admin account, and you are it.** Creating
  another is refused ("An Inventory Admin account already exists."), active or
  inactive. When the role moves to a new person, the System Administrator edits
  *your* account's name, employee code and email; a deactivated Inventory Admin
  still blocks creating a fresh one.
- **Email is the username.** It must be unique, and staff repeatedly try their
  employee code when signing in.
- **Editing an account never changes its password**, and there is no reset screen
  either. A password is set once, when the account is created. If someone has
  forgotten theirs, the fix is a new password written into the database by a
  System Administrator — follow `docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`
  section 5 rather than inventing a method. Do not tell someone "I edited your
  account, so your password is now the one on the form".
- Deactivating is the correct response to someone leaving. It keeps every
  assignment and history row intact while stopping the account from signing in.
  Never delete a person.
- An account that holds an assignment cannot simply vanish — take the asset back
  through `/returns` first, then deactivate.
- Only a System Administrator may edit or reactivate the Inventory Admin account.
  Nobody can create a System Administrator from a screen; see
  `docs/USER-GUIDE-SYSTEM-ADMINISTRATOR.md`.

## 6. If you also run stores

Your role covers `/assignments`, `/returns`, `/requests` and `/incidents`, so you
can issue, take back, approve and resolve exactly as an Inventory Manager does.
Follow `docs/USER-GUIDE-INVENTORY-MANAGER.md` for those screens.

Two things that apply to you as an approver:

- You cannot approve a request you raised yourself.
- Approving and fulfilling are separate steps: `APPROVED` means "yes", `FULFILLED`
  means "the asset physically left". Both are needed before an asset is out.

## 7. Keeping the data trustworthy

| Problem in the data | Where it came from |
| --- | --- |
| Asset shows `ASSIGNED` to someone who left | A return was never recorded. Take it back through `/returns` and note the date it actually came back |
| Two assets with the same code | Codes were invented twice. Renumber one now; the longer you wait the more records point at the wrong one |
| A request for a type that does not exist | The type list is incomplete. Add it, then tell the requester |
| Incident raised against the wrong asset | Codes are not on the physical items. Label the assets — this is the most common root cause and no software fixes it |
| `UNDER_INSPECTION` items from months ago | Nobody closes them. Set a weekly review |
