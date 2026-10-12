"""Live check of "remove a member from a group" against the real Firebase project.

Proves that removal changes ONLY memberIds: old expenses are byte-for-byte unchanged, balances and
settlements still include the removed user, and new expenses cannot use them. Creates throwaway data
(users/test_*, one group) and deletes it at the end.

Run from the repo root:  python tests/live_remove_member_check.py
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
    print(f"[{'PASS' if condition else 'FAIL'}] {name} {info}")


def act_as(uid):
    current["uid"] = uid


def raw_expenses(gid):
    """The stored expense documents, exactly as Firestore holds them."""
    docs = db.collection("groups").document(gid).collection("expenses").stream()
    return {d.id: d.to_dict() for d in docs}


group_ref = db.collection("groups").document()
gid = group_ref.id
try:
    for uid, name in ((A, "Creator Test"), (B, "Bee Test"), (C, "Cee Test")):
        db.collection("users").document(uid).set({"name": name, "email": f"{uid}@example.com"})
    group_ref.set({"name": "test group", "createdBy": A, "memberIds": [A, B, C]})

    act_as(A)
    equal = client.post(f"/groups/{gid}/expenses", json={
        "amount": 90, "description": "equal", "paidBy": A, "splitType": "equal", "participantIds": [A, B, C]}).json()
    exact = client.post(f"/groups/{gid}/expenses", json={
        "amount": 50, "description": "exact", "paidBy": C, "splitType": "exact",
        "participantIds": [A, B, C], "exactAmounts": {A: 10, B: 25, C: 15}}).json()
    check("setup: two expenses created", "id" in equal and "id" in exact, (equal, exact))

    docs_before = raw_expenses(gid)
    api_before = client.get(f"/groups/{gid}/expenses").json()
    bal_before = client.get(f"/groups/{gid}/balances").json()
    settle_before = client.get(f"/groups/{gid}/settlement").json()
    check("setup: B has an outstanding balance", bal_before[B] < 0, bal_before)

    # --- validation failures, none of which may change anything
    act_as(GHOST)
    check("4. non-member requester -> 403", client.delete(f"/groups/{gid}/members/{C}").status_code == 403)
    act_as(A)
    r = client.delete(f"/groups/{gid}/members/{A}")
    check("3. creator removing self -> 400", r.status_code == 400, r.text)
    act_as(B)
    check("3. non-creator removing self -> 400", client.delete(f"/groups/{gid}/members/{B}").status_code == 400)
    act_as(A)
    check("target not a member -> 404", client.delete(f"/groups/{gid}/members/{GHOST}").status_code == 404)
    check("no such group -> 404", client.delete(f"/groups/NO_SUCH_GROUP_123/members/{B}").status_code == 404)
    check("memberIds unchanged by failures", group_ref.get().to_dict()["memberIds"] == [A, B, C])

    # --- 1. the creator removes B, who still owes money
    r = client.delete(f"/groups/{gid}/members/{B}")
    check("1. creator removes another member -> 200", r.status_code == 200
          and set(r.json()["memberIds"]) == {A, C} and r.json()["createdBy"] == A, r.text)
    check("6. B gone from stored memberIds", group_ref.get().to_dict()["memberIds"] == [A, C])
    check("repeat removal -> 404", client.delete(f"/groups/{gid}/members/{B}").status_code == 404)

    # --- 5. no expense was rewritten
    check("1. stored expense documents identical (incl. participantIds/paidBy/exactAmounts)",
          raw_expenses(gid) == docs_before)
    # --- 2. old expenses still show B
    api_after = client.get(f"/groups/{gid}/expenses").json()
    check("2. expenses API output identical, B still a participant", api_after == api_before
          and all(B in e["participantIds"] for e in api_after), api_after)
    # --- 5. balances and settlements unchanged
    check("5. balances identical, B still has his debt", client.get(f"/groups/{gid}/balances").json() == bal_before)
    settle_after = client.get(f"/groups/{gid}/settlement").json()
    check("5. settlement still lists B's transfer", settle_after["transfers"] == settle_before["transfers"]
          and any(t["from"] == B for t in settle_after["transfers"]), settle_after["transfers"])
    check("5. breakdown for B's debt still works", all(
        client.get(f"/groups/{gid}/settlements/{t['id']}/breakdown").status_code == 200
        for t in settle_after["transfers"]))

    # --- 3/4. current members exclude B, and a new expense cannot include B
    members = client.get(f"/groups/{gid}/members").json()
    check("3. current members = A, C only", [m["uid"] for m in members] == [A, C], members)
    r = client.post(f"/groups/{gid}/expenses", json={
        "amount": 30, "description": "new", "paidBy": A, "splitType": "equal", "participantIds": [A, B]})
    check("4. new expense with removed B -> rejected", r.status_code in (400, 422), r.text)
    r = client.post(f"/groups/{gid}/expenses", json={
        "amount": 30, "description": "new", "paidBy": A, "splitType": "equal", "participantIds": [A, C]})
    check("4. new expense with current members -> accepted", r.status_code in (200, 201), r.text)

    # --- removed user can no longer read the group
    act_as(B)
    check("removed B can no longer open the group -> 403", client.get(f"/groups/{gid}").status_code == 403)

    # --- 2. a NON-creator removes another member (here: the creator himself, createdBy is irrelevant)
    act_as(C)
    r = client.delete(f"/groups/{gid}/members/{A}")
    check("2. non-creator removes another member -> 200", r.status_code == 200 and r.json()["memberIds"] == [C], r.text)
    check("5. expenses still unchanged after second removal", raw_expenses(gid) == {
        **docs_before, **{k: v for k, v in raw_expenses(gid).items() if k not in docs_before}})
finally:
    db.recursive_delete(group_ref)
    for uid in (A, B, C):
        db.collection("users").document(uid).delete()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
