# Staff Guide — Using the Asset Management System

For anyone signed in as **Staff** (`OFFICE_STAFF`). This covers asking for an
asset, what happens while it is yours, giving it back, and reporting damage or
loss.

If a link below takes you to a page that refuses you, your account does not hold
the role that page needs — see your manager or a System Administrator. Do not
work around it.

## 1. Signing in

1. Open the address your office was given, for example
   `http://<server>:8080/login`.
2. Sign in with your **email address** and password. The email is the username —
   your employee code does not work here.
3. You land on the dashboard. It shows your own figures and the cards Staff may
   use: **Property Requests**, **Damage & Loss Incidents**, **Notifications**.

Signing out is the account/menu action on the dashboard; it ends the session for
real, so the next person at that computer cannot get back in with your browser.

If you leave the page open and come back after about half an hour, you will be
asked to sign in again. That is intentional on shared office machines.

## 2. Asking for an asset

**Dashboard → Manage Requests → Submit Asset Request** (`/requests/new`).

| Field | What to put |
| --- | --- |
| Requested Item / Category * | The asset type, e.g. "Laptop", "Desk Chair". Chosen from the types the Inventory Admin has set up |
| Quantity * | How many |
| Purpose / Business Justification * | Why you need it and when, e.g. "New team member in Operations, required by 1 Nov". This is what the approver reads |
| Item Specifications / Preferences | Optional: accessories, colour, department-specific needs |

Submit it. The request appears in your list as **PENDING**.

You will not be able to approve your own request — that is a deliberate control,
not a fault. Someone else (an Inventory Manager or an Administrator) decides.

### What the statuses mean

| Status | Meaning | What you do |
| --- | --- | --- |
| `PENDING` | Waiting for a decision | Nothing; wait, or add detail by editing it |
| `APPROVED` | Decision made, asset not handed over yet | Wait for the stores to issue it |
| `FULFILLED` | The asset has been assigned to you | Collect it; it is now your responsibility |
| `REJECTED` | Declined | Read the reviewer's note; you may raise a new request with more detail |
| `CANCELLED` | You cancelled it | — |

### Changing your mind

On one of your own requests that is still **PENDING** you can **Edit** it or
**Cancel** it. Once it has been approved or fulfilled, cancel is refused —
speak to whoever issued it, because the asset is now recorded against you.

You can only change your own requests. This is checked on the server, so it does
not matter how the link is presented.

## 3. While the asset is yours

An **assignment** is the record that an asset is with you: which asset, who holds
it, the area it was issued to, and its condition at the time it was handed over.
Inventory Managers create and close those records; Staff do not.

Your part is practical:

- Check the asset works when you receive it. If it does not, report it the same
  day (section 4) rather than at the next handover.
- Keep it where it was issued to be used. Moving expensive equipment between
  areas without telling stores is how assets end up on the register as
  unaccounted.
- Do not lend it to a colleague informally. The register says it is yours.

To see what is held against your own account, use **Notifications** and ask your
manager for the assignment list (`/assignments` is a Manager/Admin page).

## 4. Reporting damage, breakdown or loss

**Dashboard → Manage Incidents → Report Property Incident** (`/incidents/new`).

| Field | What to put |
| --- | --- |
| Select Identifiable Asset * | The asset, chosen by **property code**. If it is not in the list, it may not be assigned to you — say so in the description |
| Incident Type * | `Physical Damage (Casing, screen cracked, liquid spill)`, `Hardware / Functional Breakdown (Will not power on, internal failure)`, or `Lost or Stolen Equipment` |
| Incident Description & Circumstances * | What happened, where, when you noticed it, and anything already done. For a theft, the report reference |

Submit it. The incident starts as **OPEN** and appears on your list. A manager or
administrator reviews it and moves it to `APPROVED`, `REJECTED` or `RESOLVED`.

You can see your own incidents. Incidents raised by other people are not
visible to you; reviewers see them all.

Two things worth being precise about, because they change what happens next:

- **Damage/broken** leads to repair or retirement. The asset goes through
  `DAMAGED` → `UNDER_REPAIR` → back to service.
- **Lost** is a record-keeping event that normally needs a manager's report to the
  office. Report it as soon as you are reasonably sure it is gone, not at the end
  of the week.

## 5. Giving an asset back

Take the asset to stores / your Inventory Manager. **The manager records the
return**; there is no Staff-facing return form, and that is intentional — the
condition of an asset at handover has to be judged by the person receiving it.

What to do:

1. Bring the asset and anything issued with it (charger, cable, sleeve).
2. Ask the manager to record the return against your assignment.
3. Make sure the condition they choose (`Good`, `Damaged`, `Broken`) matches what
   you are handing over. If something is wrong, say it now — after the return is
   recorded, the asset is no longer your responsibility on the register.

## 6. Notifications

**Dashboard → View Notifications** (`/notifications`). Request decisions,
incident outcomes and workflow updates land here. They are yours alone: another
person's notifications cannot be read from your session, even by changing an
address in the browser.

Mark one as read, or use "read all". Nothing here is email — if the matter is
urgent, go and see the person.

## 7. What Staff cannot do

By design, and enforced by the server:

- Register, edit or retire assets; open the asset register (`/properties`)
- Issue an asset to somebody (`/assignments`) or record a return (`/returns`)
- Approve, reject or fulfil requests — including your own
- Resolve incidents or return equipment to service
- Run reports or export registers (`/reports`)
- See or change any other person's account, request, incident or notification

If your work needs one of these, the answer is a role change, not a workaround.
Ask your manager to request **Inventory Manager** from a System Administrator.

## 8. Small things that cause most of the calls

| Symptom | Cause / fix |
| --- | --- |
| "Wrong password" but it is right | You typed your employee code. The username is your **email address** |
| Page asks you to sign in again | Half an hour idle. Save long forms elsewhere first |
| A form submits and the page reloads with no change | Your session's protection token expired. Reload the page and try once more |
| The asset I want is not in the request list | Only configured **property types** appear. Ask an Inventory Admin whether the type exists and is active |
| Nothing appears under incidents for an asset | The asset may not be assigned to you. Mention the property code in the description |
| The page is unstyled or broken | A cached copy of an old address. Press Ctrl+F5 |
