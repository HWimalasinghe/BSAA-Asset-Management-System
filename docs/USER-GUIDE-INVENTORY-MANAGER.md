# Inventory Manager Guide — Issuing, Returns, Requests and Incidents

For anyone signed in with **Inventory Manager** (`INVENTORY_MANAGER`), including
an Inventory Admin or System Administrator who is doing stores work today.

You are the person who physically hands assets over and takes them back. The
system's record of who holds what is created and closed by you, so the accuracy
of the whole register depends on these screens being used at the moment the
movement happens, not at the end of the month.

## 1. What you can and cannot open

Allowed: `/` dashboard, `/inventory-manager`, `/requests`, `/incidents`,
`/assignments`, `/returns`, `/reports`, `/notifications`.

Refused by the server, whatever the dashboard shows:

| Page | Why not |
| --- | --- |
| `/properties` | The asset register belongs to Inventory Admin / System Admin |
| `/users`, `/departments`, `/physical-areas`, `/property-categories`, `/property-types` | Reference data and accounts are administrator work |
| `/office-staff` | Not in the role list for that route; use `/requests` and `/incidents`, which you can reach |

**Your dashboard may hide cards you are allowed to use.** The cards are filtered
by a single "primary role". The account screens give everyone exactly one role, so
this only bites if a second role was added directly to the database. If it happens,
the pages still work — use `/assignments`, `/returns` and `/reports` directly, and
see `docs/KNOWN-ISSUES-AND-SUPPORT.md` for the underlying defect.

## 2. Reviewing requests

**Dashboard → Manage Requests** (`/requests`), or `/requests/{id}` to read one.

A request sits at `PENDING` until an approver decides:

1. Open it. Read the purpose and the quantity.
2. **Approve or reject** (`POST /requests/{id}/review`). Give a reason when
   rejecting — it is the only feedback the requester gets.
3. Approved requests move to `APPROVED` and wait for fulfilment.
4. **Fulfil** (`/requests/{id}/fulfill`) — choose the actual asset(s) for an
   approved request. Fulfilment creates the assignment, marks the request
   `FULFILLED`, and the asset leaves `AVAILABLE`.

Rules the system enforces, not conventions:

- **You cannot approve your own request.** If you raised it, another approver
  must decide. This applies to every approver role.
- Only an active account with a reviewer role can review or fulfil. A deactivated
  account is refused even with a valid session.
- Your reviewer authority is checked again on each action, so a role removed
  mid-session stops working at the next click.

## 3. Issuing an asset directly

Sometimes an asset is handed over without a request (a swap, a temporary loan).

**Dashboard → Manage Assignments → Assign a Property** (`/assignments/new`).

| Field | Notes |
| --- | --- |
| Property | Only assets that are `AVAILABLE` **and** in `GOOD` condition appear; the server refuses anything else ("Only available properties in good condition can be assigned") |
| Employee | The person who becomes responsible |
| Issue Area | Where it is going — an Inventory Admin maintains the list |
| Condition at Issue | `Good`, `Damaged`, `Broken`. Record what you see now; this is the comparison point at return |
| Notes | Optional; use it for serial numbers, accessories, or the reason |

The asset becomes `ASSIGNED`, the assignment appears in `/assignments`, and the
holder gets a notification.

There is a safety rule worth knowing when two people work the same desk at the
same time: an asset that has just been taken is refused for the second issue
rather than being handed out twice. The register is locked per asset while an
issue is being written, so the second person sees a plain refusal and picks
another unit.

## 4. Recording a return

**Dashboard → Manage Returns → Return a Property** (`/returns/new`).

| Field | Notes |
| --- | --- |
| Assigned Property | The open assignments — pick the one ending today |
| Condition at Return | `Good`, `Damaged`, `Broken`. Be honest; this is what protects the previous holder |
| Notes | Accessories missing, wear, what you were told |

What happens next, automatically:

- The assignment is closed, so the person no longer holds the asset.
- The asset's status follows the condition you recorded:

  | Condition at return | Asset status |
  | --- | --- |
  | `GOOD` | `AVAILABLE` — back on the shelf |
  | `DAMAGED` | `DAMAGED` |
  | `BROKEN` | `UNDER_REPAIR` |

- A `DAMAGED` or `BROKEN` return also **opens an incident automatically** (type
  `DAMAGE` or `BROKEN`, status `OPEN`) and notifies the Inventory Managers, so the
  repair is tracked. You do not need to raise that incident yourself, and you
  should not, or the same event ends up reported twice.
- The status change is written to the asset's history, which is what the audit
  report reads.

Only one return can exist per assignment. If the same assignment appears to need
a second return, the first one is already recorded — check `/returns` before
raising another, and if the earlier record is wrong, correct the situation with an
incident rather than a duplicate return.

## 5. Incidents

**Dashboard → Manage Incidents** (`/incidents`), `/incidents/{id}` to review.

Staff reports an incident as `OPEN`. You:

- **Resolve** it (`POST /incidents/{id}/resolve`) after deciding what happens —
  repair, replace, write-off. Approved/rejected/resolved are the outcomes.
- **Return an asset to service** (`POST /incidents/return-to-service`) once it is
  fixed: choose the asset and the area, and it goes back to `AVAILABLE`.

An incident you reported yourself can be resolved by another approver, the same
way requests work.

Statuses: `OPEN` → `APPROVED` / `REJECTED` / `RESOLVED`. Types: `DAMAGE`,
`BROKEN`, `LOST`.

## 6. Reports you own

**Dashboard → View Reports** (`/reports`). All five reports accept filters and
the register can be exported as CSV:

| Report | Path | Use it for |
| --- | --- | --- |
| Asset register | `/reports/assets` | What exists, by status (`AVAILABLE`, `ASSIGNED`, `UNDER_INSPECTION`, `DAMAGED`, `UNDER_REPAIR`, `LOST`, `RETIRED`) |
| Summary | `/reports/summary` | Counts for the weekly meeting |
| Assignments | `/reports/assignments` | Who holds what; filter by employee |
| Requests | `/reports/requests` | Request history by status |
| Incidents | `/reports/incidents` | Damage/loss history by type |

Exports are for the office. They contain staff names and asset details — the same
caution as any printed register applies.

## 7. Daily habits that keep the register true

1. **Issue and return in the system at the moment it happens.** Every hour of
   delay is a period where the answer to "who has the projector" is wrong.
2. **Never skip the condition fields.** They are the only record of what left the
   shelf and what came back.
3. **Do not hand an asset to someone without recording it**, even informally,
   even "for an hour". Losses are unrecoverable when the register never knew.
4. **Check `UNDER_INSPECTION` and `DAMAGED` items weekly.** Anything parked there
   for a month is either repaired (put it back via incidents), sent for repair, or
   retired by an administrator.
5. **Lost assets are not deleted.** They are marked `LOST` through an incident so
   the history survives an audit.

## 8. What needs someone else

| Need | Who |
| --- | --- |
| Register a new asset, edit its details, retire it | Inventory Admin or System Administrator |
| New physical area, category, property type, department | Inventory Admin |
| A new account, or a role change | System Administrator (Inventory Admin for Staff/Manager) |
| A request you raised yourself approved | Another approver |
