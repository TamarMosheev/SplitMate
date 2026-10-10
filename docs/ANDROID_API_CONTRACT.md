# SplitMate backend – Android API contract

Final contract for connecting the Android app to the FastAPI backend.
It describes the API exactly as implemented and tested (see [Verification status](#verification-status)).

- [1. Conventions](#1-conventions)
- [2. Recommended flow](#2-recommended-flow)
- [3. Endpoint reference](#3-endpoint-reference)
  - [3.1 List my groups](#31-list-my-groups) · [3.2 Balances](#32-balances) · [3.3 Settlement](#33-settlement) · [3.4 Mark as paid](#34-mark-as-paid) · [3.5 Send a reminder](#35-send-a-reminder) · [3.6 Notifications](#36-notifications) · [3.7 Unread count](#37-unread-count) · [3.8 Mark a notification read](#38-mark-a-notification-read)
  - [3.9 Additional endpoints](#39-additional-endpoints) (health, group details, expenses, read-all)
  - [3.10 Settlement breakdown](#310-settlement-breakdown-ממה-זה-מורכב) ("ממה זה מורכב")
  - [3.11 Report a payment](#311-report-a-payment-debtor-שלחתי-תשלום) · [3.12 Confirm a payment](#312-confirm-a-payment-creditor-אשר-קבלת-תשלום) · [3.13 Reject a payment claim](#313-reject-a-payment-claim-creditor) (two-step payment confirmation)
- [4. Data types](#4-data-types)
- [5. Status code summary](#5-status-code-summary)
- [6. Android integration notes](#6-android-integration-notes)
- [Verification status](#verification-status)

---

## 1. Conventions

| Topic | Rule |
|---|---|
| **Base URL** | Local development: `http://127.0.0.1:8000`. Android **emulator**: `http://10.0.2.2:8000`. A **physical phone** needs the PC's LAN IP and the server started with `--host 0.0.0.0` (it binds to 127.0.0.1 only by default). |
| **Authentication** | Every endpoint except `GET /` and the public `/auth/password-reset/*` ones (§3.14) needs `Authorization: Bearer <Firebase ID token>`. Get the token with `FirebaseAuth.getInstance().currentUser.getIdToken(false)` (the SDK caches it and refreshes it when it expires, so call it before each request). |
| **Request body** | JSON, UTF-8, with `Content-Type: application/json`. Only the endpoints that take a body need it. |
| **Response body** | JSON, UTF-8 (`application/json`). Hebrew text and emoji are sent as normal UTF-8. |
| **Error body** | `{"detail": "<message>"}`. Validation errors (422) use `{"detail": [{"type": "...", "loc": [...], "msg": "...", "input": ...}]}`. |
| **Money** | JSON numbers, always whole cents (at most 2 decimals), e.g. `116.66`. Parse as `BigDecimal` / decimal, never as `Double` arithmetic. |
| **Timestamps** | ISO-8601 UTC, in **two textual forms**: `2026-10-01T19:01:10.656000+00:00` (group and expense documents) and `2026-10-05T19:21:58.103000Z` (settlements and notifications). Parse both with `OffsetDateTime.parse(...)`. |
| **Unknown fields** | Ignore them. The server may add fields; group and expense objects are Firestore documents and can carry extra fields. |
| **Field named `from`** | Settlements use `from` (the debtor). Map it explicitly (e.g. `@SerializedName("from")`) because it clashes with keywords in some languages. |
| **User identity** | All ids named `...Uid`, `memberIds`, `paidBy`, `from`, `to`, `markedBy` are Firebase Auth uids. The server never trusts a uid sent by the client for who is acting: it comes from the token. |

### Authentication errors (all protected endpoints)

| Situation | Status | `detail` |
|---|---|---|
| No `Authorization` header, not `Bearer`, or empty token | **401** | `Missing or malformed Authorization header. Expected: Bearer <firebase_id_token>` |
| Token invalid, tampered or expired | **401** | `Invalid or expired Firebase ID token` |

The 401 response also carries `WWW-Authenticate: Bearer`. On a 401, force-refresh the token once (`getIdToken(true)`) and retry; if it still fails, send the user to login.
The server accepts up to 10 seconds of clock difference between its clock and Firebase's.

### Group access errors (every `/groups/{group_id}/...` endpoint)

| Situation | Status | `detail` |
|---|---|---|
| Group id does not exist | **404** | `Group '<group_id>' not found` |
| Caller's uid is not in the group's `memberIds` | **403** | `You are not a member of this group` |

Order of checks: token (401) → group exists (404) → caller is a member (403) → the endpoint's own rules.

---

## 2. Recommended flow

Open settlement ids stay the same while the debt exists, **even if its amount changes**. If later expenses change *who owes whom*, an old open id stops existing. So never act on a cached id:

1. Call `GET /groups/{group_id}/settlement`.
2. Show `transfers` (open debts) and `paid` (history).
3. Act (mark paid / remind) only with an `id` from that response.
4. A `404` or `409` on an action means the screen was out of date: refetch the settlement and refresh the UI.

Who sees which action: **`to` is the creditor** (is owed money), **`from` is the debtor**.
Show *Mark as paid* and *Send reminder* only when `to` equals the logged-in user's uid.

Bell badge: `GET /notifications/unread-count`. Notification list: `GET /notifications`. Opening a notification: `PATCH /notifications/{id}/read`, then go to the group using `groupId` (and refetch the settlement; its `settlementId` may be stale).

---

## 3. Endpoint reference

### 3.1 List my groups

`GET /groups`

- **Auth:** required.
- **Request body:** none.
- **Response `200`:** array of the groups the caller belongs to (`[]` if none). Items are the stored group documents.

```json
[
  {
    "id": "pRW37tsIegdpp9ztJkHO",
    "name": "אילת",
    "createdBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "icon": "✈️",
    "memberIds": [
      "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "70MNLii6tlOUMDLfA5zyw0Add7y2",
      "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
    ],
    "createdAt": "2026-10-01T18:33:21.173000+00:00"
  }
]
```

- **Use:** `id` (the `group_id` for every other call), `name`, `icon`, `memberIds`.
- **Status codes:** `200`, `401`.

### 3.2 Balances

`GET /groups/{group_id}/balances`

- **Auth:** required; caller must be a group member.
- **Request body:** none.
- **Response `200`:** object mapping uid → net balance. **Positive = is owed money. Negative = owes money.**

```json
{
  "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 233.33,
  "70MNLii6tlOUMDLfA5zyw0Add7y2": -116.66,
  "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2": -116.67
}
```

- **Use:** the entry for the logged-in uid is their net position in the group. The values always add up to exactly `0.00`, and already include settlements marked as paid.
- **Status codes:** `200`, `401`, `403`, `404`.

### 3.3 Settlement

`GET /groups/{group_id}/settlement`

- **Auth:** required; caller must be a group member.
- **Request body:** none.
- **Side effect:** reading keeps the stored settlements in sync with the current expenses (it may create, update or remove *open* settlements). It never changes paid ones.
- **Response `200`:**

```json
{
  "balances": {
    "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 233.33,
    "70MNLii6tlOUMDLfA5zyw0Add7y2": -116.66,
    "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2": -116.67
  },
  "transfers": [
    {
      "id": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
      "from": "70MNLii6tlOUMDLfA5zyw0Add7y2",
      "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "amount": 116.66,
      "status": "open",
      "createdAt": "2026-10-05T19:21:58.103000Z",
      "paidAt": null,
      "markedBy": null,
      "paymentClaimStatus": "none",
      "paymentClaimedAt": null,
      "paymentClaimedBy": null,
      "paymentClaimAmount": null,
      "paymentConfirmedAt": null,
      "paymentConfirmedBy": null,
      "paymentRejectedAt": null,
      "paymentRejectedBy": null
    },
    {
      "id": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
      "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
      "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "amount": 116.67,
      "status": "open",
      "createdAt": "2026-10-05T19:21:57.334000Z",
      "paidAt": null,
      "markedBy": null,
      "paymentClaimStatus": "none",
      "paymentClaimedAt": null,
      "paymentClaimedBy": null,
      "paymentClaimAmount": null,
      "paymentConfirmedAt": null,
      "paymentConfirmedBy": null,
      "paymentRejectedAt": null,
      "paymentRejectedBy": null
    }
  ],
  "paid": []
}
```

After the first transfer has been marked paid, the same response looks like:

```json
{
  "balances": {
    "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 116.67,
    "70MNLii6tlOUMDLfA5zyw0Add7y2": 0.0,
    "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2": -116.67
  },
  "transfers": [
    {
      "id": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
      "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
      "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "amount": 116.67,
      "status": "open",
      "createdAt": "2026-10-05T19:21:57.334000Z",
      "paidAt": null,
      "markedBy": null,
      "paymentClaimStatus": "none",
      "paymentClaimedAt": null,
      "paymentClaimedBy": null,
      "paymentClaimAmount": null,
      "paymentConfirmedAt": null,
      "paymentConfirmedBy": null,
      "paymentRejectedAt": null,
      "paymentRejectedBy": null
    }
  ],
  "paid": [
    {
      "id": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
      "from": "70MNLii6tlOUMDLfA5zyw0Add7y2",
      "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "amount": 116.66,
      "status": "paid",
      "createdAt": "2026-10-05T19:21:58.103000Z",
      "paidAt": "2026-10-05T19:23:01.637000Z",
      "markedBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "paymentClaimStatus": "none",
      "paymentClaimedAt": null,
      "paymentClaimedBy": null,
      "paymentClaimAmount": null,
      "paymentConfirmedAt": null,
      "paymentConfirmedBy": null,
      "paymentRejectedAt": null,
      "paymentRejectedBy": null
    }
  ]
}
```

**Settlement fields** (same shape in `transfers`, `paid`, and the mark-as-paid response):

| Field | Type | Meaning |
|---|---|---|
| `id` | string | **Settlement id**, format `{debtorUid}_{creditorUid}_{n}`. Treat as an opaque, URL-safe string. Use it in the paid and reminder paths. |
| `from` | string | uid of the **debtor** (the one who pays). |
| `to` | string | uid of the **creditor** (the one who is owed and receives). |
| `amount` | number | Amount owed (open) or paid (paid). Whole cents. |
| `status` | `"open"` \| `"paid"` | Settlement **status**. A `paid` settlement never becomes `open` again. |
| `createdAt` | string (timestamp) | When the settlement record was created. |
| `paidAt` | string (timestamp) \| `null` | When it was marked paid; `null` while open. |
| `markedBy` | string (uid) \| `null` | uid of the **creditor** who marked it paid; `null` while open. |
| `paymentClaimStatus` | `"none"` \| `"pending"` \| `"confirmed"` \| `"rejected"` | The two-step payment confirmation (see [3.11](#311-report-a-payment-debtor-שלחתי-תשלום)). `none` = nobody reported a payment. `pending` = the debtor reported paying, waiting for the creditor. `confirmed` = the creditor confirmed (the settlement is then `paid`). `rejected` = the creditor rejected the report (the settlement is still `open`; the debtor can report again). |
| `paymentClaimedAt` | timestamp \| `null` | When the debtor reported the payment. |
| `paymentClaimedBy` | uid \| `null` | Who reported it (always the debtor). |
| `paymentClaimAmount` | number \| `null` | The amount that was open when the debtor reported (the creditor can only confirm a claim for the current amount). |
| `paymentConfirmedAt` / `paymentConfirmedBy` | timestamp / uid \| `null` | When and by whom (the creditor) the claim was confirmed. |
| `paymentRejectedAt` / `paymentRejectedBy` | timestamp / uid \| `null` | When and by whom (the creditor) the claim was rejected. |

- A **pending claim never changes `status` or any balance.** Only the creditor's confirmation makes the settlement `paid`.
- `transfers` = open debts still to be paid. `paid` = payment history. Both arrays are sorted by `id`, which is not meaningful: sort them yourself for display.
- If the same two people owe each other again after a payment, a **new** open settlement appears with the next suffix (`..._2`); the paid one stays in `paid`.
- `balances` is the same as `GET .../balances`.
- **Status codes:** `200`, `401`, `403`, `404`.

### 3.4 Mark as paid

`PATCH /groups/{group_id}/settlements/{settlement_id}/paid`

- **Auth:** required. **Only the creditor** (`to`) may call it. The debtor gets `403`.
- **What it is:** the creditor's *direct* "I received the money" (for example cash handed over, with no claim from the debtor). It is the same confirmation as [3.12 confirm-payment](#312-confirm-a-payment-creditor-אשר-קבלת-תשלום) but does **not** require a pending claim. If a claim *is* pending, it is confirmed too (`paymentClaimStatus: "confirmed"`). Either way the debtor gets a `payment_confirmed` notification the first time it becomes paid. For the usual two-step flow use 3.11 and 3.12 instead.
- **Request body:** optional.

```json
{ "expectedAmount": 116.66 }
```

  `expectedAmount` = the amount the user saw on screen. **Send it.** If the debt's amount has changed since, the server answers `409` instead of marking a different amount as paid. Omitting the body skips this check.
- **Response `200`:** the settlement, now paid.

```json
{
  "id": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "from": "70MNLii6tlOUMDLfA5zyw0Add7y2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 116.66,
  "status": "paid",
  "createdAt": "2026-10-05T19:21:58.103000Z",
  "paidAt": "2026-10-05T19:23:01.637000Z",
  "markedBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "paymentClaimStatus": "none",
  "paymentClaimedAt": null,
  "paymentClaimedBy": null,
  "paymentClaimAmount": null,
  "paymentConfirmedAt": null,
  "paymentConfirmedBy": null,
  "paymentRejectedAt": null,
  "paymentRejectedBy": null
}
```

- **Idempotent:** calling it again as the creditor returns `200` with the **original** `paidAt` and `markedBy` (not updated).
- The debt's expenses are never changed; the payment is recorded on the settlement and counted in balances.
- **Status codes:**

| Status | When | `detail` (example) |
|---|---|---|
| `200` | Marked paid now, or already paid (unchanged) | – |
| `401` | Missing/invalid token | see §1 |
| `403` | Not a group member | `You are not a member of this group` |
| `403` | Caller is not the creditor (the debtor, or an unrelated member) | `Only the creditor (the person owed money) can mark this debt as paid` |
| `404` | Group not found | `Group '<id>' not found` |
| `404` | Settlement id is not current (unknown, or the debt changed) | `Settlement '<id>' is not current: it no longer exists because the group's debts changed. Refetch GET /groups/<group_id>/settlement and use the latest settlement id.` |
| `409` | `expectedAmount` differs from the current amount | `The amount of this settlement changed from <X> to <Y>. Refetch GET /groups/<group_id>/settlement and confirm the latest amount.` |
| `422` | Body is not valid JSON or `expectedAmount` is not a number | validation error list |

### 3.5 Send a reminder

`POST /groups/{group_id}/settlements/{settlement_id}/reminder`

- **Auth:** required. **Only the creditor** (`to`) of an **open** settlement may send a reminder, to the debtor (`from`).
- **Request body:** none. Do **not** send a uid or an amount; the server takes the sender from the token and the recipient and amount from the stored settlement.
- **Response `201`:** the notification created for the debtor.

```json
{
  "id": "FQfDod3eVP6A0mDe8IrP",
  "type": "debt_reminder",
  "recipientUid": "70MNLii6tlOUMDLfA5zyw0Add7y2",
  "senderUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "groupId": "pRW37tsIegdpp9ztJkHO",
  "settlementId": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "amount": 116.66,
  "message": "אלעזר מושייב שלח לך תזכורת לגבי חוב של ₪116.66",
  "isRead": false,
  "createdAt": "2026-10-05T19:22:42.157000Z",
  "readAt": null
}
```

- **Reminder response fields:** the full notification object (see [§4.2](#42-notification)). Useful ones: `id` (notification id), `recipientUid`, `amount`, `message` (already contains the sender's name and the amount).
- **Cooldown:** one reminder per sender + group + settlement per **24 hours**. A repeat inside that window returns `409` and creates nothing.
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `201` | Reminder created | – |
| `400` | The debtor is no longer a member of the group | `The debtor is no longer a member of this group` |
| `401` | Missing/invalid token | see §1 |
| `403` | Not a group member | `You are not a member of this group` |
| `403` | Caller is not the creditor (the debtor, or an unrelated member) | `Only the person owed money can send a reminder for this debt` |
| `404` | Group not found | `Group '<id>' not found` |
| `404` | Settlement id is not current | `Settlement '<id>' is not current: … Refetch GET /groups/<group_id>/settlement and use the latest settlement id.` |
| `409` | Cooldown (reminder already sent in the last 24h) | `A reminder for this settlement was already sent recently` |
| `409` | Settlement already paid | `This settlement has already been paid` |

  The two `409` cases are told apart by `detail`.

### 3.6 Notifications

`GET /notifications`

- **Auth:** required. Returns only the caller's own notifications; there is no way to request another user's.
- **Request body:** none.
- **Response `200`:** array, **newest first** (`[]` if none).

```json
[
  {
    "id": "FQfDod3eVP6A0mDe8IrP",
    "type": "debt_reminder",
    "recipientUid": "70MNLii6tlOUMDLfA5zyw0Add7y2",
    "senderUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "groupId": "pRW37tsIegdpp9ztJkHO",
    "settlementId": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
    "amount": 116.66,
    "message": "אלעזר מושייב שלח לך תזכורת לגבי חוב של ₪116.66",
    "isRead": false,
    "createdAt": "2026-10-05T19:22:42.157000Z",
    "readAt": null
  }
]
```

- **Use:** `id` (notification id), `message`, `createdAt`, `isRead`, `readAt`; `groupId` + `settlementId` to open the debt; `senderUid` if you want to look up the sender's profile (`users/{uid}.name`) for the live name.
- Only `type: "debt_reminder"` exists today. Be tolerant of other types in the future.
- **Status codes:** `200`, `401`.

### 3.7 Unread count

`GET /notifications/unread-count`

- **Auth:** required.
- **Request body:** none.
- **Response `200`:**

```json
{ "count": 3 }
```

- **Use:** show the bell badge when `count > 0`. Counts only the caller's unread notifications.
- **Status codes:** `200`, `401`.

### 3.8 Mark a notification read

`PATCH /notifications/{notification_id}/read`

- **Auth:** required. Works only on the caller's own notifications.
- **Request body:** none.
- **Response `200`:** the notification, now read (`isRead: true`, `readAt` set).

```json
{
  "id": "FQfDod3eVP6A0mDe8IrP",
  "type": "debt_reminder",
  "recipientUid": "70MNLii6tlOUMDLfA5zyw0Add7y2",
  "senderUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "groupId": "pRW37tsIegdpp9ztJkHO",
  "settlementId": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "amount": 116.66,
  "message": "אלעזר מושייב שלח לך תזכורת לגבי חוב של ₪116.66",
  "isRead": true,
  "createdAt": "2026-10-05T19:22:42.157000Z",
  "readAt": "2026-10-05T19:22:54.124000Z"
}
```

- **Idempotent:** calling it again returns `200` and keeps the first `readAt`.
- After marking read, refresh the unread count.
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `200` | Marked read (or already read) | – |
| `401` | Missing/invalid token | see §1 |
| `404` | Unknown id, **or** a notification that belongs to someone else | `Notification '<id>' not found` |

### 3.9 Additional endpoints

These also exist and are available to the app.

#### Health check

`GET /` – **no authentication.**
Response `200`: `{"status": "ok", "message": "SplitMate backend is running"}`.

#### Group details

`GET /groups/{group_id}` – auth required, caller must be a member.
Response `200`: one group object, same shape as an item of [3.1](#31-list-my-groups).
Status codes: `200`, `401`, `403`, `404`.

#### Search registered users

`GET /users/search?query=<text>` – auth required.

Finds users who are already registered in SplitMate, by a case-insensitive substring of their name or email. `query` must be at least 2 characters (after trimming). At most 20 results, sorted by name. Only public fields are returned (no Firebase/Auth data); `displayName`, `email` and `photoUrl` are omitted when the profile has none.

Response `200`:

```json
[
  { "uid": "Xy12AbCdEf34GhIj56Kl", "displayName": "נועה כהן", "email": "noa@example.com" }
]
```

Status codes: `200`, `400` (`query must be at least 2 characters`), `401`.

#### Add a member to a group

`POST /groups/{group_id}/members` – auth required.

Body: `{"userId": "<firebase uid of an already-registered user>"}`

- **Who may:** any current member of the group (the server checks that the authenticated uid is in the group's `memberIds`). The creator (`createdBy`) is not special here. `createdBy` is never changed.
- **Effect:** the uid is added to `memberIds` atomically (Firestore transaction + `ArrayUnion`, the array is never overwritten). Nothing else is written.
- **History is untouched:** existing expenses keep their `paidBy`, `participantIds` and amounts; settlements and balances do not change. The new member starts at ₪0.00 (they are simply not listed in `GET .../balances` until an expense involves them).
- **Future expenses:** from now on the new member is a valid `paidBy`/`participantIds` value in `POST /groups/{group_id}/expenses`, with no migration. Refetch the group (`GET /groups/{group_id}`) to get the updated `memberIds`.
- **Notification:** none is created for the added user (the notification schema is built around settlements; adding a type would change `GET /notifications` for existing clients).

Response `201`: the updated group object (same shape as [3.1](#31-list-my-groups)), e.g.

```json
{ "id": "aHnBG9OW2xQspSiyOdHO", "name": "חופשה בתאילנד", "createdBy": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "memberIds": ["8S2Qyuq2tKPfrmoJzJVP8H2eLWf2", "iO8Yi3G9xRVpUUAzPNhEfhxXcX92", "Xy12AbCdEf34GhIj56Kl"] }
```

| Status | When | `detail` |
|---|---|---|
| `201` | Member added | – |
| `400` | `userId` is empty/blank | `userId must not be empty` |
| `401` | Missing/invalid token | see §1 |
| `403` | The caller is not a member of the group | `You are not a member of this group` |
| `404` | No such group, or no registered user with that uid | `Group '<id>' not found` / `User '<uid>' not found` |
| `409` | The user is already a member | `This user is already a member of the group` |
| `422` | Body missing or `userId` not a string | validation list (see §1) |

#### Delete a group

`DELETE /groups/{group_id}`

Permanently deletes a group and all of its data.

- **Auth:** required (`Authorization: Bearer <Firebase ID token>`). **Only the person who created the group may delete it**: the server checks that the authenticated uid equals the group's `createdBy`. Nothing about who is deleting is taken from the request. Being a member is not enough.
- **How Android decides whether to show the delete option:** show it only when `group.createdBy == currentUser.uid` (both are in the group object from `GET /groups`). The server enforces the rule anyway (403).
- **Request body:** none.
- **Response `200`** (real response):

```json
{
  "success": true,
  "groupId": "AsY6Vu31E8ZOQgHz46zI"
}
```

- **What is deleted** (permanently, nothing is kept):
  - the group document;
  - all its expenses (`groups/{id}/expenses`);
  - all its settlements (`groups/{id}/settlements`), including paid history and the saved breakdowns;
  - every notification about the group (all types: `debt_reminder`, `payment_claim`, `payment_confirmed`, `payment_claim_rejected`) from the inbox of every person involved, including people who are no longer members.
  
  Nothing else is touched: other groups, other notifications and user profiles stay as they are. After the delete, `GET /groups` no longer lists the group, and every endpoint under `/groups/{id}/...` answers `404`. The unread count drops automatically if the group had unread notifications.
- **Order and retries:** notifications are deleted first and the group last, so if a request fails midway the group still exists and the same request can simply be repeated.
- **Not idempotent by design:** deleting an already-deleted group is a `404`.
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `200` | Deleted | – |
| `401` | Missing/invalid token | `Missing or malformed Authorization header. Expected: Bearer <firebase_id_token>` / `Invalid or expired Firebase ID token` |
| `403` | The caller did not create the group (a member who is not the creator, or a non-member) | `Only the person who created the group can delete it` |
| `404` | No such group (unknown, already deleted, or an invalid id) | `Group '<id>' not found` |

Real `403` and `404` responses:

```json
{ "detail": "Only the person who created the group can delete it" }
```

```json
{ "detail": "Group 'NO_SUCH_GROUP_123' not found" }
```

- **Android notes:** remove the group from the UI only after a `200`; treat `404` as "already gone" (drop it too); after success refresh the group list, Home data, balances and the unread count. Deleting is permanent and cannot be undone. Groups created before `createdBy` existed cannot be deleted through the API (403 for everyone).

#### Mark all notifications read

`PATCH /notifications/read-all` – auth required, no body.
Response `200`: `{"updated": 2}` (how many were unread and are now read; `0` if none).
Status codes: `200`, `401`.

#### Delete a notification

`DELETE /notifications/{notification_id}`

Permanently deletes **one of the caller's own** notifications (any type: `debt_reminder`, `payment_claim`, `payment_confirmed`, `payment_claim_rejected`).

- **Auth:** required (`Authorization: Bearer <Firebase ID token>`). The recipient is always the authenticated user, never taken from the request: a user can only delete their own notifications.
- **Request body:** none.
- **Response `200`** (real response):

```json
{
  "success": true,
  "notificationId": "S1lNvh3ECySGIIMorna1"
}
```

- **Effects:** the Firestore document `users/{uid}/notifications/{notification_id}` is really deleted, so `GET /notifications` no longer lists it. `GET /notifications/unread-count` is calculated from the stored notifications, so deleting an **unread** notification lowers the count by one; deleting a read one does not change it.
- **Not idempotent by design:** a second delete of the same id is a `404`, never a fake success.
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `200` | Deleted | – |
| `401` | Missing/invalid token | `Missing or malformed Authorization header. Expected: Bearer <firebase_id_token>` / `Invalid or expired Firebase ID token` |
| `404` | No such notification for this user: it does not exist, was already deleted, or belongs to someone else (the response is the same, so other users' notification ids are never revealed) | `Notification '<id>' not found` |

Real `404` response:

```json
{
  "detail": "Notification 'S1lNvh3ECySGIIMorna1' not found"
}
```

#### List expenses

`GET /groups/{group_id}/expenses` – auth required, caller must be a member, no body.
Response `200`: array of the group's expenses (`[]` if none), as stored. Equal split and exact split examples:

```json
[
  {
    "id": "TlPIG6UGFSc0s5o6E4ja",
    "amount": 350.0,
    "description": "קניות לילדים",
    "paidBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "splitType": "equal",
    "participantIds": [
      "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "70MNLii6tlOUMDLfA5zyw0Add7y2",
      "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
    ],
    "createdBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "createdAt": "2026-10-01T18:51:24.899000+00:00"
  },
  {
    "id": "JMUmJO1CglHnxlKyE3sD",
    "amount": 500.0,
    "description": "",
    "paidBy": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
    "splitType": "exact",
    "participantIds": [
      "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "70MNLii6tlOUMDLfA5zyw0Add7y2",
      "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
    ],
    "exactAmounts": {
      "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 200.0,
      "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2": 200.0,
      "70MNLii6tlOUMDLfA5zyw0Add7y2": 100.0
    },
    "createdBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "createdAt": "2026-10-01T19:01:47.381000+00:00"
  }
]
```

`createdBy` is present only on expenses the app wrote itself; expenses created through this API do not have it. `exactAmounts` exists only for `splitType: "exact"`.
Status codes: `200`, `401`, `403`, `404`.

#### Create an expense

`POST /groups/{group_id}/expenses` – auth required, caller must be a member.

Request body (`equal` split):

```json
{
  "amount": 350,
  "description": "קניות לילדים",
  "paidBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "splitType": "equal",
  "participantIds": [
    "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
    "70MNLii6tlOUMDLfA5zyw0Add7y2",
    "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
  ]
}
```

Request body (`exact` split – `exactAmounts` is required and must add up to `amount`):

```json
{
  "amount": 100,
  "description": "ארוחת ערב",
  "paidBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "splitType": "exact",
  "participantIds": ["iO8Yi3G9xRVpUUAzPNhEfhxXcX92", "70MNLii6tlOUMDLfA5zyw0Add7y2"],
  "exactAmounts": {
    "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 60,
    "70MNLii6tlOUMDLfA5zyw0Add7y2": 40
  }
}
```

| Field | Type | Rules |
|---|---|---|
| `amount` | number | `> 0`, at most 2 decimals |
| `description` | string | not empty after trimming |
| `paidBy` | string (uid) | not empty; must be a group member |
| `splitType` | `"equal"` \| `"exact"` | exactly one of these |
| `participantIds` | array of uids | at least one; all must be group members |
| `exactAmounts` | object uid → number | required when `splitType` is `exact`; each value at most 2 decimals; sum must equal `amount` exactly (in cents); every key must be in `participantIds`. Ignored for `equal`. |

Response `200` (the created expense; `createdAt` is set by the server):

```json
{
  "id": "mNJ0F7hGKxheHGiIIc2N",
  "amount": 100.0,
  "description": "ארוחת ערב",
  "paidBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "splitType": "exact",
  "participantIds": ["iO8Yi3G9xRVpUUAzPNhEfhxXcX92", "70MNLii6tlOUMDLfA5zyw0Add7y2"],
  "exactAmounts": {
    "iO8Yi3G9xRVpUUAzPNhEfhxXcX92": 60.0,
    "70MNLii6tlOUMDLfA5zyw0Add7y2": 40.0
  },
  "createdAt": "2026-10-05T18:47:16.958000+00:00"
}
```

Status codes:

| Status | When | `detail` |
|---|---|---|
| `200` | Created | – |
| `400` | `paidBy` is not a member | `paidBy must be a member of the group` |
| `400` | A participant is not a member | `participantIds must all be members of the group: ['<uid>']` |
| `400` | An `exactAmounts` key is not in `participantIds` | `exactAmounts users must all be in participantIds: ['<uid>']` |
| `401` / `403` / `404` | see §1 | – |
| `422` | Body failed validation (amount ≤ 0 or more than 2 decimals, blank text, empty `participantIds`, bad `splitType`, missing `exactAmounts`, `exactAmounts` not adding up to `amount`) | validation error list |

### 3.10 Settlement breakdown ("ממה זה מורכב")

`GET /groups/{group_id}/settlements/{settlement_id}/breakdown`

Explains one settlement with the **real expenses** behind it. Android shows the "ממה זה מורכב" section from this endpoint only.

There are two kinds of answer, told apart by **`breakdownType`**:

| `breakdownType` | Meaning | What is filled |
|---|---|---|
| `"exact"` | The settlement is fully explained by real expenses (and earlier payments). `breakdownAvailable` is `true`. | `items` (adds up to `amount` exactly). `contributingExpenses` is `[]`. |
| `"netted"` | The settlement is the netted result of several people's balances, so no exact explanation exists. `breakdownAvailable` is `false`. | `items` is `[]`. `contributingExpenses` lists the real expenses that **changed the balance of the debtor or the creditor**: context only, **not** a breakdown of the amount. |
| `"unavailable"` | Nothing was recorded for this settlement (only for settlements marked paid before breakdowns existed). `breakdownAvailable` is `false`. | `items` and `contributingExpenses` are both `[]`. |

- **Auth:** required; the caller must be a member of the group. Same visibility as `GET .../settlement`: any member can view the breakdown of any settlement in the group (debtor, creditor or other members).
- **Request body:** none.
- **Works for open and paid settlements.** For a paid settlement it returns the breakdown **as it was when the settlement was marked paid** (saved then), so it does not change if expenses are edited or deleted later.

**Response `200`, `breakdownType: "exact"`** (real data, group "אילת", debtor `70MN…` owes creditor `iO8Y…`):

```json
{
  "settlementId": "70MNLii6tlOUMDLfA5zyw0Add7y2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "groupId": "pRW37tsIegdpp9ztJkHO",
  "from": "70MNLii6tlOUMDLfA5zyw0Add7y2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 116.66,
  "status": "open",
  "breakdownAvailable": true,
  "breakdownType": "exact",
  "reason": null,
  "items": [
    {
      "type": "expense",
      "expenseId": "TlPIG6UGFSc0s5o6E4ja",
      "description": "קניות לילדים",
      "expenseAmount": 350.0,
      "payerUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "settlementId": null,
      "relevantAmount": 116.66,
      "createdAt": "2026-10-01T18:51:24.899000Z"
    }
  ],
  "contributingExpenses": []
}
```

**Response `200`, `breakdownType: "exact"`, several expenses including an offsetting one** (real run in a two-person group: the debtor owes 100.00 for one expense, is owed 30.00 on another, plus 16.66 from an odd-cent split):

```json
{
  "settlementId": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "groupId": "aHnBG9OW2xQspSiyOdHO",
  "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 86.66,
  "status": "open",
  "breakdownAvailable": true,
  "breakdownType": "exact",
  "reason": null,
  "items": [
    {
      "type": "expense",
      "expenseId": "ER8RWdeTeDMQSqqAWmIN",
      "description": "TEMP bd 1: dinner",
      "expenseAmount": 200.0,
      "payerUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "settlementId": null,
      "relevantAmount": 100.0,
      "createdAt": "2026-10-06T08:59:18.560000Z"
    },
    {
      "type": "expense",
      "expenseId": "pElQ5onpTm0sBUpwHb4b",
      "description": "TEMP bd 2: C pays, split equally",
      "expenseAmount": 60.0,
      "payerUid": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
      "settlementId": null,
      "relevantAmount": -30.0,
      "createdAt": "2026-10-06T08:59:25.120000Z"
    },
    {
      "type": "expense",
      "expenseId": "gByKJVXHbX6d6BnOSUdc",
      "description": "TEMP bd 3: odd cents",
      "expenseAmount": 33.33,
      "payerUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "settlementId": null,
      "relevantAmount": 16.66,
      "createdAt": "2026-10-06T08:59:25.875000Z"
    }
  ],
  "contributingExpenses": []
}
```

**Response `200`, `breakdownType: "netted"`** (real data, group "גינה של הביניים אורים 6": `70MN…` owes `8S2Q…` 195.00, a debt that comes out of everyone's combined balances). `items` is empty, nothing is attributed. `contributingExpenses` shows the real expenses that changed either person's balance:

```json
{
  "settlementId": "70MNLii6tlOUMDLfA5zyw0Add7y2_8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_1",
  "groupId": "ZQVkiKM7KLIbxMyJBGyT",
  "from": "70MNLii6tlOUMDLfA5zyw0Add7y2",
  "to": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "amount": 195.0,
  "status": "open",
  "breakdownAvailable": false,
  "breakdownType": "netted",
  "reason": "This settlement is a netted obligation across multiple participants and cannot be uniquely attributed to individual expenses.",
  "items": [],
  "contributingExpenses": [
    {
      "expenseId": "JMUmJO1CglHnxlKyE3sD",
      "description": "",
      "expenseAmount": 500.0,
      "payerUid": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
      "participantUids": [
        "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
        "70MNLii6tlOUMDLfA5zyw0Add7y2",
        "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
      ],
      "fromContribution": -100.0,
      "toContribution": 300.0,
      "createdAt": "2026-10-01T19:01:47.381000Z"
    },
    {
      "expenseId": "LxYEJbGWLAVpcI19b4Gz",
      "description": "גנן שבועי",
      "expenseAmount": 150.0,
      "payerUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "participantUids": [
        "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
        "70MNLii6tlOUMDLfA5zyw0Add7y2",
        "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
      ],
      "fromContribution": -60.0,
      "toContribution": -30.0,
      "createdAt": "2026-10-03T18:34:38.699000Z"
    },
    {
      "expenseId": "hwoyN4x7OuUgMN7tUxsm",
      "description": "דשן",
      "expenseAmount": 200.0,
      "payerUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
      "participantUids": [
        "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
        "70MNLii6tlOUMDLfA5zyw0Add7y2",
        "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2"
      ],
      "fromContribution": -75.0,
      "toContribution": -75.0,
      "createdAt": "2026-10-05T17:43:31.461000Z"
    }
  ]
}
```

**Top-level fields**

| Field | Type | Meaning |
|---|---|---|
| `settlementId` | string | The settlement being explained (same value as in the path). |
| `groupId` | string | The group. |
| `from` / `to` | string (uid) | Debtor / creditor of the settlement. |
| `amount` | number | The settlement amount (whole cents). |
| `status` | `"open"` \| `"paid"` | The settlement status. |
| `breakdownAvailable` | boolean | `true` only for an **exact** breakdown (`breakdownType: "exact"`). `false` for `"netted"` and `"unavailable"`. |
| `breakdownType` | `"exact"` \| `"netted"` \| `"unavailable"` | Which kind of answer this is (see the table above). |
| `reason` | string \| `null` | Why there is no exact breakdown. `null` when exact. Informational English text: show your own Hebrew message. |
| `items` | array | The exact records that make up the amount, oldest first. Empty unless `breakdownType` is `"exact"`. |
| `contributingExpenses` | array | Context rows, oldest first. Only filled when `breakdownType` is `"netted"`; otherwise `[]`. **Not** a decomposition of `amount`. |

**Item fields** (`items[]`)

| Field | Type | Meaning |
|---|---|---|
| `type` | `"expense"` \| `"payment"` | An expense, or an earlier payment between the same two people. |
| `expenseId` | string \| `null` | Expense rows only. |
| `description` | string \| `null` | Expense rows only. The expense's description as stored (can be an empty string). |
| `expenseAmount` | number \| `null` | Expense rows only. The whole expense, e.g. `350.0`. |
| `payerUid` | string \| `null` | Expense rows only. Who paid the expense. |
| `settlementId` | string \| `null` | Payment rows only. The earlier settlement that was paid. |
| `relevantAmount` | number | This record's part of the settlement, **signed from the debtor's point of view**: positive adds to what the debtor owes the creditor; negative reduces it. |
| `createdAt` | timestamp \| `null` | When the expense was created, or when the payment was marked paid. |

**Contributing expense fields** (`contributingExpenses[]`, `breakdownType: "netted"` only)

| Field | Type | Meaning |
|---|---|---|
| `expenseId` | string | The real expense. |
| `description` | string | Its description as stored (can be an empty string). |
| `expenseAmount` | number | The whole expense. |
| `payerUid` | string | Who paid it. |
| `participantUids` | array of uids | The participants recorded on the expense. |
| `fromContribution` | number | This expense's effect on the **debtor's** balance. Same sign as `GET .../balances`: negative = it made them owe money, positive = it made them owed money. |
| `toContribution` | number | This expense's effect on the **creditor's** balance, same sign convention. |
| `createdAt` | timestamp \| `null` | When the expense was created. |

**Which expenses are listed (the exact rule).** An expense is listed if and only if it changed the balance of the debtor **or** the creditor. Its effect on a person is *what they paid for it* (the whole amount if they were the payer, otherwise 0) *minus their share of it*, computed with the same split as the balances (integer cents, odd cents included). If both effects are zero, the expense is not listed (for example, someone else paying for themselves only).

**What the numbers mean.**
- For each of the two people, the `fromContribution` values (or `toContribution` values) of the listed expenses add up to that person's balance in `GET .../balances` (plus any earlier payments marked paid).
- They are **not** meant to add up to `amount`, and they usually do not. The settlement `amount` comes from netting everyone's balances. Do not present these rows as "this is what makes up the debt"; show them as "expenses that affected the balances of the two people". In the example above, the debtor's contributions are −100.00, −60.00 and −75.00 (their balance is −235.00), the creditor's are +300.00, −30.00 and −75.00 (balance +195.00), and the settlement is 195.00.
- Payments already marked paid also move balances but are not expenses, so they are not listed here.

**How to read it**
- **The sum rule (exact only):** when `breakdownAvailable` is `true`, the `relevantAmount` values of all items add up to **exactly** `amount` (checked in cents).
- **Negative rows:** an expense row can be negative when the *creditor* owes the debtor on that expense (it offsets the debt, as the `-30.0` row above).
- **Payment rows** appear only for a new debt between two people who already settled up before. A payment row has `type: "payment"` and a negative `relevantAmount`, so the items still add up to the new open amount. Real example, the new debt after a payment (excerpt of `items`, oldest first):

```text
expense  "TEMP bd 1: dinner"        relevantAmount  100.00
expense  "TEMP bd 2: C pays, ..."   relevantAmount  -30.00
expense  "TEMP bd 3: odd cents"      relevantAmount   16.66
expense  "TEMP bd 4: exact"          relevantAmount   60.00
payment  (settlementId ..._1)       relevantAmount -146.66
expense  "TEMP bd 5: after payment" relevantAmount   20.00      =>  amount 20.00
```

- **When is a breakdown available?** The backend computes what the debtor owes the creditor *directly* (expenses the creditor paid that the debtor shares, minus expenses the debtor paid that the creditor shares, minus payments between them). The breakdown is returned only when that equals the settlement amount exactly. This always holds in two-person groups and when one member paid for everything.
- **When it is not (`"netted"`):** in groups where several people pay for each other, the settlement algorithm nets debts across everyone (A owes B, B owes C, so A pays C), and a transfer can then be a *netted obligation* that no individual expense explains. The server never guesses: it returns `breakdownAvailable: false`, `breakdownType: "netted"`, `items: []`, and the `contributingExpenses` context. In the real group "גינה של הביניים אורים 6" both current settlements are like this.
- **Paid settlements:** the snapshot taken when the settlement was marked paid is returned (its `status` is `"paid"`): the exact rows, or for a netted debt its `contributingExpenses`. It stays the same even if expenses are changed or deleted later. If a paid settlement has no snapshot (marked paid before breakdowns existed) you get `breakdownType: "unavailable"`, both lists empty, and `reason: "No breakdown was recorded when this settlement was paid."`.
- **Possible `reason` values:** `This settlement is a netted obligation across multiple participants and cannot be uniquely attributed to individual expenses.` · `No breakdown was recorded when this settlement was paid.` · `The breakdown recorded when this settlement was paid is inconsistent and is not shown.`

**Status codes**

| Status | When | `detail` |
|---|---|---|
| `200` | Always when the settlement exists, including `breakdownAvailable: false` | – |
| `401` | Missing/invalid token | see §1 |
| `403` | Not a group member | `You are not a member of this group` |
| `404` | Group not found | `Group '<id>' not found` |
| `404` | Settlement id is not current (unknown, or the debts changed) | `Settlement '<id>' is not current: it no longer exists because the group's debts changed. Refetch GET /groups/<group_id>/settlement and use the latest settlement id.` |

### 3.11 Report a payment (debtor, "שלחתי תשלום")

`POST /groups/{group_id}/settlements/{settlement_id}/payment-claim`

The **debtor** says "I paid this debt". The settlement stays `open`, **no balance changes**, and the creditor gets a `payment_claim` notification and must confirm.

- **Auth:** required. **Only the debtor** (`from`) of the settlement may call it.
- **Request body:** optional `{"expectedAmount": 100.0}`: the amount the user saw. Send it; if the debt changed since, you get `409`.
- **Response `200`:** the updated settlement (real response):

```json
{
  "id": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 100.0,
  "status": "open",
  "createdAt": "2026-10-06T10:19:33.429000Z",
  "paidAt": null,
  "markedBy": null,
  "paymentClaimStatus": "pending",
  "paymentClaimedAt": "2026-10-06T10:19:39.858000Z",
  "paymentClaimedBy": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "paymentClaimAmount": 100.0,
  "paymentConfirmedAt": null,
  "paymentConfirmedBy": null,
  "paymentRejectedAt": null,
  "paymentRejectedBy": null
}
```

- **Notification created for the creditor** (`type: "payment_claim"`, see [4.2](#42-notification)): `senderUid` = the debtor, `recipientUid` = the creditor, plus `groupId`, `settlementId`, `amount`, `isRead: false`. The claim and the notification are written together, so one never exists without the other.
- **Rules:** the settlement must be `open`; there must be no pending claim for the current amount; after a rejection the debtor may report again; if expenses changed the amount while a claim was pending, the debtor may report again for the new amount (the old claim is replaced).
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `200` | Claim saved | – |
| `401` | Missing/invalid token | see §1 |
| `403` | Not a group member | `You are not a member of this group` |
| `403` | Caller is not the debtor (the creditor, or an unrelated member) | `Only the person who owes this payment can report it as sent` |
| `404` | Group not found / settlement id not current | see §1 / `Settlement '<id>' is not current: … Refetch GET /groups/<group_id>/settlement …` |
| `409` | Already paid | `This settlement has already been paid` |
| `409` | A claim for the current amount is already pending | `A payment claim for this settlement is already pending confirmation` |
| `409` | `expectedAmount` differs from the current amount | `The amount of this settlement changed from <X> to <Y>. Refetch …` |
| `409` | The creditor left the group | `The creditor is no longer a member of this group` |

### 3.12 Confirm a payment (creditor, "אשר קבלת תשלום")

`PATCH /groups/{group_id}/settlements/{settlement_id}/confirm-payment`

The **creditor** confirms receiving the money. **Only now** does the settlement become `paid`, and only now do balances change. The debtor gets a `payment_confirmed` notification.

- **Auth:** required. **Only the creditor** (`to`) may call it. Needs a **pending** claim.
- **Request body:** optional `{"expectedAmount": 120.0}` (send it; `409` if the debt changed).
- **Response `200`:** the updated settlement (real response, after a claim for 120.00):

```json
{
  "id": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 120.0,
  "status": "paid",
  "createdAt": "2026-10-06T10:19:33.429000Z",
  "paidAt": "2026-10-06T10:20:35.392000Z",
  "markedBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "paymentClaimStatus": "confirmed",
  "paymentClaimedAt": "2026-10-06T10:20:31.818000Z",
  "paymentClaimedBy": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "paymentClaimAmount": 120.0,
  "paymentConfirmedAt": "2026-10-06T10:20:35.392000Z",
  "paymentConfirmedBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "paymentRejectedAt": null,
  "paymentRejectedBy": null
}
```

- **Idempotent:** repeating it as the creditor returns `200` unchanged (same `paidAt`) and creates no second notification.
- **The expenses are never changed.** The payment is recorded on the settlement, and a breakdown snapshot is saved with it (see [3.10](#310-settlement-breakdown-ממה-זה-מורכב)).
- **Stale claims:** if the amount changed after the debtor reported (a new expense was added), the creditor cannot confirm that old claim: `409`. The creditor can reject it, or the debtor can report again for the current amount.
- **Status codes:**

| Status | When | `detail` |
|---|---|---|
| `200` | Confirmed now, or already confirmed (unchanged) | – |
| `401` / `403` / `404` | Token / not a member / group or settlement not current | see §1 |
| `403` | Caller is not the creditor (the debtor, or an unrelated member) | `Only the creditor (the person owed money) can confirm receiving a payment` |
| `409` | No pending claim (none was reported, or it was rejected) | `There is no pending payment claim for this settlement` |
| `409` | Paid directly with no claim | `This settlement was paid without a payment claim` |
| `409` | The amount changed since the claim | `The amount of this settlement changed since the payment was claimed. Reject the claim, or ask the other person to report the payment again for the current amount.` |
| `409` | `expectedAmount` differs from the current amount | `The amount of this settlement changed from <X> to <Y>. Refetch …` |

### 3.13 Reject a payment claim (creditor)

`PATCH /groups/{group_id}/settlements/{settlement_id}/reject-payment-claim`

The **creditor** says the payment was not received. The settlement stays `open`, and the debtor gets a `payment_claim_rejected` notification and may report again.

- **Auth:** required. **Only the creditor** (`to`). Needs a pending claim. **No request body.**
- **Response `200`:** the updated settlement (real response):

```json
{
  "id": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "from": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "to": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "amount": 100.0,
  "status": "open",
  "createdAt": "2026-10-06T10:19:33.429000Z",
  "paidAt": null,
  "markedBy": null,
  "paymentClaimStatus": "rejected",
  "paymentClaimedAt": "2026-10-06T10:19:39.858000Z",
  "paymentClaimedBy": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "paymentClaimAmount": 100.0,
  "paymentConfirmedAt": null,
  "paymentConfirmedBy": null,
  "paymentRejectedAt": "2026-10-06T10:20:16.870000Z",
  "paymentRejectedBy": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92"
}
```

- **Status codes:** `200`; `401`; `403` (not a member, or not the creditor: `Only the creditor (the person owed money) can reject a payment claim`); `404`; `409` (`There is no pending payment claim for this settlement`, or `This settlement has already been paid`).

---

### 3.14 Password reset (public, no `Authorization` header)

Flow: **request code → verify code → complete**. Emails are sent with Resend. These endpoints are public (besides `GET /`) because the user is logged out.

| Setting | Value |
|---|---|
| Code | 6 digits, expires after **10 minutes** |
| Failed attempts | max **5** per code, then `429` until a new code is requested |
| Resend cooldown | **60 seconds** between sends per email |
| Reset token | valid **10 minutes**, single use |
| Password policy | 8–128 characters, at least one letter and one number |

Emails are case-insensitive and trimmed. Only accounts that sign in with email/password can reset this way.

#### Request a code — `POST /auth/password-reset/request`

```json
{ "email": "user@example.com" }
```

`200` (always the same body, whether or not the account exists):

```json
{ "success": true, "message": "If the account exists, a verification code was sent." }
```

| Status | When |
|---|---|
| 400 | `Invalid email address` |
| 429 | Another code was requested < 60 s ago. `detail`: `Please wait N seconds before requesting another code`, plus a `Retry-After` header. Returned identically for unknown emails. |
| 500 | `Password reset is temporarily unavailable` (email provider not configured on the server) |

#### Resend — `POST /auth/password-reset/resend`

Same body, responses and rules as `/request`. Generates a **new** code; the previous code stops working immediately and the attempt counter resets. Show a 60-second countdown before enabling "send again".

#### Verify the code — `POST /auth/password-reset/verify`

```json
{ "email": "user@example.com", "code": "123456" }
```

`200`:

```json
{ "verified": true, "resetToken": "<opaque string>" }
```

Keep `resetToken` in memory only; it is the sole authorization for the next step.

| Status | When |
|---|---|
| 400 | `Invalid or expired verification code` (wrong, expired, already used, or no request: deliberately not distinguished) |
| 429 | `Too many failed attempts. Request a new code.` |

#### Set the new password — `POST /auth/password-reset/complete`

```json
{ "resetToken": "<from verify>", "newPassword": "NewPass456" }
```

`200`: `{ "success": true }`. The user's other sessions are signed out; sign in with the new password.

| Status | When |
|---|---|
| 400 | Policy: `Password must be at least 8 characters long` / `...at least one letter` / `...at least one number` / `...at most 128 characters long`. The token is **not** consumed, so the user can retry. |
| 401 | `Invalid or expired reset token` (unknown, expired, or already used) |
| 409 | `This reset token was already used` (only when two requests race) |

`404` is not used by this flow. Missing JSON fields return `422` as usual.

---

## 4. Data types

### 4.1 Settlement

See the field table in [3.3](#33-settlement).

### 4.2 Notification

| Field | Type | Meaning |
|---|---|---|
| `id` | string | **Notification id**. Use it in `PATCH /notifications/{id}/read`. |
| `type` | string | What happened. One of the four types in the table below. Be tolerant of new types in the future. |
| `recipientUid` | string | uid of the user the notification is for. |
| `senderUid` | string | uid of the user who caused it. Always the authenticated user who performed the action. |
| `groupId` | string | Group the debt belongs to. |
| `settlementId` | string | Settlement id the notification is about (may be stale later, see §2). |
| `amount` | number | The settlement amount at that moment. |
| `message` | string | Display text, Hebrew, built by the server (names + amount). |
| `isRead` | boolean | `false` until the recipient marks it read. |
| `createdAt` | timestamp | Server time when it was created. |
| `readAt` | timestamp \| `null` | Server time when it was first marked read; `null` while unread. |

**Notification types**

| `type` | Sent by → to | Meaning | Example `message` (real) |
|---|---|---|---|
| `debt_reminder` | creditor → debtor | A reminder to pay ([3.5](#35-send-a-reminder)). | `אלעזר מושייב שלח לך תזכורת לגבי חוב של ₪116.66` |
| `payment_claim` | debtor → creditor | The debtor reports having paid and waits for confirmation ([3.11](#311-report-a-payment-debtor-שלחתי-תשלום)). `senderUid` is who claims payment. | `תשלום של ₪100 מאור הלוי ממתין לאישורך` |
| `payment_confirmed` | creditor → debtor | The creditor confirmed receiving the payment ([3.12](#312-confirm-a-payment-creditor-אשר-קבלת-תשלום), or a direct mark-as-paid). | `התשלום שלך של ₪120 לאלעזר מושייב אושר` |
| `payment_claim_rejected` | creditor → debtor | The creditor did not confirm the reported payment ([3.13](#313-reject-a-payment-claim-creditor)). | `התשלום שלך של ₪100 לאלעזר מושייב לא אושר` |

A `payment_claim` notification as returned by `GET /notifications` (the `id` is illustrative; every other value is from a real run):

```json
{
  "id": "kP3x9QeWb7mZ2VnTa1cD",
  "type": "payment_claim",
  "recipientUid": "iO8Yi3G9xRVpUUAzPNhEfhxXcX92",
  "senderUid": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2",
  "groupId": "aHnBG9OW2xQspSiyOdHO",
  "settlementId": "8S2Qyuq2tKPfrmoJzJVP8H2eLWf2_iO8Yi3G9xRVpUUAzPNhEfhxXcX92_1",
  "amount": 100.0,
  "message": "תשלום של ₪100 מאור הלוי ממתין לאישורך",
  "isRead": false,
  "createdAt": "2026-10-06T10:19:39.858000Z",
  "readAt": null
}
```

All four types use the same fields, the same list, the same unread count and the same `PATCH .../read`.

### 4.3 Unread count

`{"count": <integer>}`

### 4.4 Error

`{"detail": "<message>"}`, or for `422` a list of validation problems (see §1).

---

## 5. Status code summary

| Endpoint | Success | Errors |
|---|---|---|
| `GET /groups` | 200 | 401 |
| `GET /groups/{group_id}/balances` | 200 | 401, 403, 404 |
| `GET /groups/{group_id}/settlement` | 200 | 401, 403, 404 |
| `PATCH /groups/{group_id}/settlements/{settlement_id}/paid` | 200 | 401, 403, 404, 409, 422 |
| `POST /groups/{group_id}/settlements/{settlement_id}/payment-claim` | 200 | 401, 403, 404, 409, 422 |
| `PATCH /groups/{group_id}/settlements/{settlement_id}/confirm-payment` | 200 | 401, 403, 404, 409, 422 |
| `PATCH /groups/{group_id}/settlements/{settlement_id}/reject-payment-claim` | 200 | 401, 403, 404, 409 |
| `POST /groups/{group_id}/settlements/{settlement_id}/reminder` | **201** | 400, 401, 403, 404, 409 |
| `GET /notifications` | 200 | 401 |
| `GET /notifications/unread-count` | 200 | 401 |
| `PATCH /notifications/{notification_id}/read` | 200 | 401, 404 |
| `PATCH /notifications/read-all` | 200 | 401 |
| `DELETE /notifications/{notification_id}` | 200 | 401, 404 |
| `GET /groups/{group_id}/settlements/{settlement_id}/breakdown` | 200 | 401, 403, 404 |
| `GET /groups/{group_id}` | 200 | 401, 403, 404 |
| `DELETE /groups/{group_id}` | 200 | 401, 403, 404 |
| `POST /groups/{group_id}/members` | **201** | 400, 401, 403, 404, 409, 422 |
| `GET /users/search` | 200 | 400, 401 |
| `GET /groups/{group_id}/expenses` | 200 | 401, 403, 404 |
| `POST /groups/{group_id}/expenses` | 200 | 400, 401, 403, 404, 422 |
| `POST /auth/password-reset/request` and `/resend` | 200 | 400, 429, 500 |
| `POST /auth/password-reset/verify` | 200 | 400, 429 |
| `POST /auth/password-reset/complete` | 200 | 400, 401, 409 |
| `GET /` | 200 | – |

Any `5xx` is a server problem: show a generic error and allow retry.

---

## 6. Android integration notes

**Auth and networking**
- Add the header with an interceptor: `Authorization: Bearer <idToken>`, token from `currentUser.getIdToken(false)`; on a `401`, call `getIdToken(true)` once and retry.
- Use the emulator address `10.0.2.2` (not `localhost`, which on the emulator points at the emulator itself). Apps targeting API 28+ block cleartext `http://` traffic by default, so a debug build needs a network-security config (or `usesCleartextTraffic`) allowing the dev host, and a release build should use HTTPS.

**Parsing**
- Money: parse JSON numbers into `BigDecimal` (e.g. Gson/Moshi `BigDecimal` adapters), display with 2 decimals. All amounts are whole cents.
- Timestamps: `OffsetDateTime.parse(...)` handles both `Z` and `+00:00` forms. Convert to the device's zone for display.
- Map `from` explicitly. Mark optional fields nullable: `paidAt`, `markedBy`, `readAt`, `createdAt`.
- Ignore unknown JSON fields.

**Settlements and actions**
- Always refetch `GET .../settlement` before showing or acting on a debt. Do not store settlement ids long-term, and do not deep-link into a debt by id without refetching.
- Show *Mark as paid* / *Send reminder* only if `to == currentUid` and `status == "open"`. The server enforces this too (403/409).
- Send `expectedAmount` with *Mark as paid*. On `409`, show "the amount changed" and reload.
- On `404 ... is not current`, reload the settlement screen: the debt was recalculated or settled.
- `PATCH .../paid` and `PATCH .../read` are idempotent, so they are safe to retry after a network failure. A retried `POST .../reminder` is also safe: it returns `409` instead of creating a duplicate.
- Reminder UX: after a `409 ... already sent recently`, show "reminder already sent" (24h limit). After `409 ... already been paid`, refresh the debt as paid.
- Balances and settlement amounts must come from the server. Do not recompute them on the device: leftover cents are distributed by a server-side rule (for example, `350.00` among 3 people is `116.66 / 116.67 / 116.67`), and a local calculation could differ by one cent.

**Payment confirmation flow (debtor "שלחתי תשלום" → creditor "אשר קבלת תשלום")**
- Decide the buttons from the settlement (`GET .../settlement`), never from your own state:
  - Debtor (`from == currentUid`, `status == "open"`): show **שלחתי תשלום** when `paymentClaimStatus` is `none` or `rejected`; show "waiting for confirmation" when it is `pending`. Do not offer *Mark as paid* to the debtor (it returns 403).
  - Creditor (`to == currentUid`, `status == "open"`): when `paymentClaimStatus == "pending"` show **אשר קבלת תשלום** (`confirm-payment`) and optionally a reject button (`reject-payment-claim`). When there is no claim you may still offer the direct *Mark as paid* (`PATCH .../paid`, for example cash).
- Send `expectedAmount` with the claim and with the confirmation. A `409` about a changed amount means expenses changed meanwhile: refetch. The creditor's confirm of an old claim fails until the debtor reports again (or the creditor rejects).
- Reporting a payment does **not** change the debt or any balance. Show the debt as still open (with a "pending confirmation" label) until `status == "paid"`.
- `paymentClaimStatus == "rejected"` means the creditor did not accept the report: the debt is still open and the debtor can report again.
- All `409` answers (already paid, already pending, nothing pending, amount changed) are state conflicts: refetch the settlement and refresh the screen. `paymentClaimStatus` and `paymentClaimedBy`/`paymentClaimedAt` tell you who reported what and when.
- Notifications: a `payment_claim` for the creditor, and a `payment_confirmed` / `payment_claim_rejected` for the debtor. Tapping one should open the group and refetch the settlement (the id may be stale). Handle all notification `type` values; the `message` is ready to display.

**Debt details ("ממה זה מורכב")**
- Use only `GET .../settlements/{settlement_id}/breakdown`. Never rebuild the list from the expenses on the device: the server's split of odd cents and its netting rules are the source of truth.
- Branch on `breakdownType`:
  - `"exact"`: show `items`: description, `expenseAmount` (the whole expense) and `relevantAmount` (this debt's part). Show negative amounts as offsets and `type: "payment"` rows as "earlier payment". The amounts add up to the debt.
  - `"netted"`: show a short neutral message (for example that this amount combines several people's expenses), and optionally a separate, clearly labelled secondary list (such as "expenses that affected the balances") from `contributingExpenses`. Show description and `expenseAmount`, plus the two contributions if you want. Never label that list as the composition of the debt, never add it up to the debt amount, and don't mix it with `items`.
  - `"unavailable"`: show only the neutral message.
- Do not compute any of this on the device.
- Fetch it together with, or right after, `GET .../settlement`, and treat a `404` like any stale-id case (refetch the settlement).
- Settlement ids are unique only **within a group** (`{debtor}_{creditor}_{n}`). Always use them together with `groupId`.

**Notifications**
- Bell badge: call `GET /notifications/unread-count` when the Home screen resumes (and after reading notifications). There is no push yet, so the badge updates only when you poll.
- The notification list is newest first. Tapping one: `PATCH /notifications/{id}/read`, refresh the count, then open the group (`groupId`) and refetch the settlement.
- Only the recipient sees a notification. The person who caused it does not get one.
- Deleting (`DELETE /notifications/{id}`): remove the row only after a `200`; treat `404` as "already gone" and drop it from the list too. Afterwards refresh `GET /notifications/unread-count` (there is no separate counter to maintain). Deleting is permanent and cannot be undone.

**Expenses**
- The server validates expenses created through `POST /groups/{group_id}/expenses` (members, whole-cent amounts, exact amounts adding up).
- Expenses the app writes **directly to Firestore** skip those checks. Keep amounts to at most 2 decimals and make `exactAmounts` add up to `amount`. If an exact split does not add up, the server assigns the difference to the person who paid, so the group balances still add up to zero, but the result may surprise users.
- Use `paidBy`, `participantIds` and `exactAmounts` with real Firebase uids that are in the group's `memberIds`.

**Groups**
- Only groups the user belongs to are returned or accessible. A `403` on a group endpoint means the user is not in `memberIds`.

---

## Verification status

- The 8 endpoints in §3.1–3.8 plus the expense and notification extras were exercised against the real backend and real Firestore with real Firebase ID tokens (46 live checks, all passing), including all the status codes in §5 except `5xx`.
- The tokens used in those tests were real Firebase tokens minted with the backend's service account. **A token produced by the Android app's own login has not been tested yet.** It is verified by the same code path, so no difference is expected, but the first integration run should confirm it.
- The settlement breakdown endpoint was verified live with real tokens and real Firestore data (35 checks): simple two-person, multi-expense with an offsetting expense, odd-cent split, exact split, a real three-person equal split, a netted case (returns `breakdownAvailable: false`), paid-settlement snapshots staying frozen after an expense was deleted, payment rows, and the 401/403/404 cases. A randomized test over 4,000 generated groups (11,326 settlements) found no case where a returned breakdown did not add up exactly or used anything but real expenses.
- `DELETE /groups/{group_id}` was verified live with real tokens and real Firestore data on temporary groups (23 checks): the creator's delete returns 200 and removes the group, its expenses, its settlements (paid and open) and all its notifications of every type; the group disappears from `GET /groups` and every endpoint under it is 404; a member who is not the creator and non-members get 403 and nothing changes; missing/tampered/garbage tokens are 401; unknown and invalid ids are 404; a second temporary group and your real groups and notifications stayed byte-for-byte unchanged.
- `DELETE /notifications/{notification_id}` was verified live with real tokens and real Firestore data (22 checks): the recipient's delete returns 200 and really removes the document, the list and the unread count update, deleting again is 404, other users get 404 and the notification survives, missing/tampered/garbage tokens are 401, and the existing notification endpoints still work.
- The two-step payment confirmation (3.11–3.13, plus the unchanged direct mark-as-paid) was verified live with real tokens and real Firestore data (57 checks): claim does not mark paid and does not change balances; the creditor gets a `payment_claim` notification; a duplicate claim is `409`; confirm makes the settlement paid and only then do balances change; the debtor gets `payment_confirmed`; reject keeps it open and the debtor can report again; a claim made for an old amount cannot be confirmed; debtor/creditor/unrelated-member/non-member permissions; paid settlements accept no new claim; reminders, unread counts and mark-read still work; and 401 for missing, tampered or garbage tokens.
- `breakdownType` and `contributingExpenses` were verified live (28 checks) against real Firestore data, including the real group "גינה של הביניים אורים 6", and by the same randomized test (6,246 netted settlements, 29,589 contributing rows): the listed expenses are exactly those that change the debtor's or creditor's balance, and their contributions reproduce both real balances to the cent.
- Push notifications (FCM) are not implemented: notifications are in-app only and appear after the app fetches them.
