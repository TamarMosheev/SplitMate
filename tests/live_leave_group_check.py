"""Live check of "leave group" (DELETE /groups/{id}/members/me) against the real Firebase project.

Creates throwaway data (users/test_*, one group) and deletes it at the end.
Run from the repo root:  python tests/live_leave_group_check.py
"""
import secrets
import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import main  # noqa: E402
from auth.auth_service import get_current_uid  # noqa: E402
from firebase.firebase_service import firebase_service  # noqa: E402

db = firebase_service.get_db()
tag = secrets.token_hex(4)
A, B, C, GHOST = (f"test_{n}_{tag}" for n in ("A", "B", "C", "ghost"))
current = {"uid": A}
main.app.dependency_overrides[get_current_uid] = lambda: current["uid"]
client = TestClient(main.app)
results = []


def check(name, condition, info=""):
    results.append(bool(condition))
    print(f"[{'PASS' if condition else 'FAIL'}] {name} {str(info)[:160]}")


def act_as(uid):
    current["uid"] = uid


def raw_expenses(gid):
    docs = db.collection("groups").document(gid).collection("expenses").stream()
    return {d.id: d.to_dict() for d in docs}


LEAVE = "/groups/{}/members/me"
group_ref = db.collection("groups").document()
solo_ref = db.collection("groups").document()
gid, solo = group_ref.id, solo_ref.id
try:
    for uid, name in ((A, "Creator Test"), (B, "Bee Test"), (C, "Cee Test")):
        db.collection("users").document(uid).set({"name": name, "email": f"{uid}@example.com"})
    group_ref.set({"name": "test group", "icon": "x", "createdBy": A, "memberIds": [A, B, C]})
    solo_ref.set({"name": "solo", "createdBy": A, "memberIds": [A]})

    act_as(A)
    client.post(f"/groups/{gid}/expenses", json={
        "amount": 90, "description": "equal", "paidBy": A, "splitType": "equal", "participantIds": [A, B, C]})
    client.post(f"/groups/{gid}/expenses", json={
        "amount": 50, "description": "exact", "paidBy": B, "splitType": "exact",
        "participantIds": [A, B, C], "exactAmounts": {A: 10, B: 25, C: 15}})
    docs_before = raw_expenses(gid)
    api_before = client.get(f"/groups/{gid}/expenses").json()
    bal_before = client.get(f"/groups/{gid}/balances").json()
    settle_before = client.get(f"/groups/{gid}/settlement").json()["transfers"]
    group_before = group_ref.get().to_dict()
    check("setup: C has an outstanding balance", bal_before[C] < 0, bal_before)

    # --- failures first (nothing may change)
    act_as(GHOST)
    r = client.delete(LEAVE.format(gid))
    check("9. non-member leaves -> 403", r.status_code == 403, r.text)
    check("group not found -> 404", client.delete(LEAVE.format("NO_SUCH_GROUP_123")).status_code == 404)
    act_as(A)
    r = client.delete(LEAVE.format(gid))
    check("8. creator leaves while others remain -> 409", r.status_code == 409, r.text)
    r = client.delete(LEAVE.format(solo))
    check("8b. creator alone -> 409 (delete the group instead)", r.status_code == 409 and "delete" in r.text, r.text)
    check("8c. solo group still exists", solo_ref.get().exists)
    check("memberIds unchanged by failures", group_ref.get().to_dict() == group_before)

    # --- 1/2. C (owes money) leaves
    act_as(C)
    r = client.delete(LEAVE.format(gid))
    check("1. normal member leaves -> 200 {success, groupId, leftGroup}", r.status_code == 200
          and r.json() == {"success": True, "groupId": gid, "leftGroup": True}, r.text)
    after = group_ref.get().to_dict()
    check("2. C removed from stored memberIds", after["memberIds"] == [A, B], after)
    check("10. group data other than memberIds unchanged",
          {k: v for k, v in after.items() if k != "memberIds"} == {k: v for k, v in group_before.items() if k != "memberIds"})
    check("C's user account still exists", db.collection("users").document(C).get().exists)
    check("leaving again -> 403", client.delete(LEAVE.format(gid)).status_code == 403)

    # --- 7. no longer has access
    check("7. C can no longer open the group -> 403", client.get(f"/groups/{gid}").status_code == 403)
    check("7. C can no longer list members/balances -> 403", client.get(f"/groups/{gid}/members").status_code == 403
          and client.get(f"/groups/{gid}/balances").status_code == 403)
    check("7. group gone from C's group list", gid not in [g["id"] for g in client.get("/groups").json()])

    act_as(A)
    # --- 3/4. history
    check("3. stored expense documents identical", raw_expenses(gid) == docs_before)
    check("3. expenses API identical, C still a participant", client.get(f"/groups/{gid}/expenses").json() == api_before
          and all(C in e["participantIds"] for e in api_before))
    check("4. balances identical, C keeps his debt", client.get(f"/groups/{gid}/balances").json() == bal_before)
    check("4. settlement transfers identical", client.get(f"/groups/{gid}/settlement").json()["transfers"] == settle_before)
    # --- 5/6. current members / future expenses
    members = client.get(f"/groups/{gid}/members").json()
    check("5. current members = A, B", [m["uid"] for m in members] == [A, B], [m["uid"] for m in members])
    r = client.post(f"/groups/{gid}/expenses", json={
        "amount": 30, "description": "new", "paidBy": A, "splitType": "equal", "participantIds": [A, C]})
    check("6. new expense with C -> rejected", r.status_code in (400, 422), r.text)
    r = client.post(f"/groups/{gid}/expenses", json={
        "amount": 30, "description": "new", "paidBy": A, "splitType": "equal", "participantIds": [A, B]})
    check("6. new expense with A, B -> accepted", r.status_code in (200, 201), r.text)

    # --- "me" is never read as a uid by the remove-other route
    check("remove-other route still works", client.delete(f"/groups/{gid}/members/{B}").status_code == 200)
finally:
    db.recursive_delete(group_ref)
    db.recursive_delete(solo_ref)
    for uid in (A, B, C):
        db.collection("users").document(uid).delete()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
