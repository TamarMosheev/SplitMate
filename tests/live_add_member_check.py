"""Live check of "add an existing user to a group" against the real Firebase project.

Creates throwaway profiles (users/test_*), one throwaway group and expenses, drives the HTTP API
through FastAPI's TestClient (the auth dependency is overridden to act as each test uid), and deletes
everything it created at the end. No real data is touched.

Run from the repo root:  python tests/live_add_member_check.py
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
A, B, C, D, GHOST = (f"test_{n}_{tag}" for n in ("A", "B", "C", "D", "ghost"))
current = {"uid": A}
main.app.dependency_overrides[get_current_uid] = lambda: current["uid"]
client = TestClient(main.app)
results = []


def check(name, condition, info=""):
    results.append(bool(condition))
    print(f"[{'PASS' if condition else 'FAIL'}] {name} {info}")


def act_as(uid):
    current["uid"] = uid


group_ref = db.collection("groups").document()
gid = group_ref.id
try:
    for uid, name in ((A, "Tamar Test"), (B, "Elazar Test"), (C, "Outsider Test"), (D, f"Noa Test {tag}")):
        db.collection("users").document(uid).set(
            {"name": name, "email": f"{uid}@example.com", "emailLower": f"{uid}@example.com"}
        )
    group_ref.set({"name": "test group", "createdBy": A, "memberIds": [A, B]})
    old = {"amount": 100, "description": "old", "paidBy": A, "splitType": "equal", "participantIds": [A, B]}
    act_as(A)
    r = client.post(f"/groups/{gid}/expenses", json=old)
    old_id = r.json()["id"]
    old_before = client.get(f"/groups/{gid}/expenses").json()
    bal_before = client.get(f"/groups/{gid}/balances").json()

    # search
    r = client.get("/users/search", params={"query": f"Noa Test {tag}"})
    check("search finds D with safe fields only", r.status_code == 200 and r.json() == [
        {"uid": D, "displayName": f"Noa Test {tag}", "email": f"{D}@example.com"}], r.json())
    check("search too short -> 400", client.get("/users/search", params={"query": "a"}).status_code == 400)

    # 1. member A (creator) adds D
    r = client.post(f"/groups/{gid}/members", json={"userId": D})
    check("1. member A adds D -> 201, memberIds union", r.status_code == 201
          and set(r.json()["memberIds"]) == {A, B, D} and r.json()["createdBy"] == A, r.json())

    # 2/3. B (not creator) adds C
    act_as(B)
    r = client.post(f"/groups/{gid}/members", json={"userId": C})
    check("2/3. non-creator member B adds user -> 201", r.status_code == 201
          and set(r.json()["memberIds"]) == {A, B, C, D}, r.json())
    db.collection("groups").document(gid).update({"memberIds": [A, B, D]})  # put C back out for the 403 test

    # 4. non-member
    act_as(C)
    r = client.post(f"/groups/{gid}/members", json={"userId": C})
    check("4. non-member -> 403", r.status_code == 403, r.json())
    act_as(A)
    # 5. duplicate
    r = client.post(f"/groups/{gid}/members", json={"userId": D})
    check("5. duplicate -> 409", r.status_code == 409, r.json())
    # 6. unknown uid / group
    r = client.post(f"/groups/{gid}/members", json={"userId": GHOST})
    check("6. unknown uid -> 404", r.status_code == 404, r.json())
    r = client.post("/groups/NO_SUCH_GROUP_123/members", json={"userId": D})
    check("6b. unknown group -> 404", r.status_code == 404, r.json())
    check("400 blank userId", client.post(f"/groups/{gid}/members", json={"userId": "  "}).status_code == 400)
    check("memberIds unchanged by failures", db.collection("groups").document(gid).get().to_dict()["memberIds"]
          == [A, B, D])

    # 7/8. D sees the group, balance 0
    act_as(D)
    r = client.get(f"/groups/{gid}")
    check("7. D can GET the group", r.status_code == 200 and gid in [g["id"] for g in client.get("/groups").json()])
    bal = client.get(f"/groups/{gid}/balances").json()
    check("8. D balance is 0.00 and totals unchanged", str(bal) == str(bal_before) or
          all(abs(e.get("balance", e.get("amount", 0))) < 0.005 for e in
              (bal if isinstance(bal, list) else bal.get("balances", [])) if e.get("userId", e.get("uid")) == D), bal)

    # 9. old expenses unchanged
    act_as(A)
    check("9. old expenses unchanged", client.get(f"/groups/{gid}/expenses").json() == old_before)

    # 10/11. new expense with D
    new = {"amount": 90, "description": "new", "paidBy": A, "splitType": "equal", "participantIds": [A, B, D]}
    r = client.post(f"/groups/{gid}/expenses", json=new)
    check("10/11. new expense including D accepted", r.status_code == 200 and D in r.json()["participantIds"], r.json())
    old_now = [e for e in client.get(f"/groups/{gid}/expenses").json() if e["id"] == old_id][0]
    check("9b. old expense participantIds still [A, B]", old_now["participantIds"] == [A, B])
finally:
    db.recursive_delete(group_ref)
    for uid in (A, B, C, D):
        db.collection("users").document(uid).delete()
    main.app.dependency_overrides.clear()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
