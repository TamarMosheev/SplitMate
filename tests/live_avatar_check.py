"""Live check of user avatars against the real Firebase project (Firestore).

Creates throwaway profiles (users/test_*) and one throwaway group, drives the HTTP API through
FastAPI's TestClient (the auth dependency is overridden to act as each test uid), and deletes
everything it created at the end. No real data is touched.

Run from the repo root:  python tests/live_avatar_check.py
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
from models.user_models import DEFAULT_AVATAR  # noqa: E402

db = firebase_service.get_db()
tag = secrets.token_hex(4)
A, B, GHOST = (f"test_{n}_{tag}" for n in ("A", "B", "ghost"))
current = {"uid": A}
main.app.dependency_overrides[get_current_uid] = lambda: current["uid"]
client = TestClient(main.app)
results = []


def check(name, condition, info=""):
    results.append(bool(condition))
    print(f"[{'PASS' if condition else 'FAIL'}] {name} {'' if condition else info}")


def act_as(uid):
    current["uid"] = uid


def stored(uid):
    return db.collection("users").document(uid).get().to_dict()


NEW = {"hairStyle": "hair_2", "faceStyle": "face_1", "glassesEnabled": True,
       "skinTone": "skin_4", "hairColor": "dark_brown", "backgroundColor": "light_blue"}
group_ref = db.collection("groups").document()
gid = group_ref.id
try:
    # A: old-style profile, no avatar, plus an unrelated field. B: also no avatar yet.
    original_a = {"name": f"Tamar Test {tag}", "email": f"{A}@example.com", "emailLower": f"{A}@example.com",
                  "photoUrl": "https://example.com/p.jpg", "customField": 42}
    db.collection("users").document(A).set(original_a)
    db.collection("users").document(B).set({"name": f"Elazar Test {tag}", "email": f"{B}@example.com"})
    group_ref.set({"name": "test group", "createdBy": A, "memberIds": [A, B]})
    group_before = group_ref.get().to_dict()

    act_as(A)
    r = client.get("/users/me")
    check("1. no avatar -> default avatar", r.status_code == 200 and r.json()["avatar"] == DEFAULT_AVATAR, r.json())
    check("1b. /users/me has uid/name/email + avatar only",
          set(r.json()) == {"uid", "name", "email", "avatar"} and r.json()["uid"] == A, r.json())
    check("1c. nothing was written by a GET", "avatar" not in stored(A))

    r = client.patch("/users/me/avatar", json=NEW)
    check("2. update avatar -> 200 with new avatar", r.status_code == 200 and r.json()["avatar"] == NEW, r.json())
    check("3. Firestore stores the avatar object", stored(A)["avatar"] == NEW, stored(A))
    r = client.get("/users/me")
    check("4. GET /users/me returns saved avatar", r.json()["avatar"] == NEW, r.json())

    r = client.patch("/users/me/avatar", json={"glassesEnabled": False})
    check("2b. partial update keeps the other fields",
          r.status_code == 200 and r.json()["avatar"] == {**NEW, "glassesEnabled": False}, r.json())
    client.patch("/users/me/avatar", json={"glassesEnabled": True})

    after = stored(A)
    after.pop("avatar")
    check("9. name/email/photoUrl/customField unchanged", after == original_a, after)

    act_as(B)
    r = client.get("/users/search", params={"query": f"Tamar Test {tag}"})
    found = r.json()
    check("5. search returns A's saved avatar",
          r.status_code == 200 and len(found) == 1 and found[0]["uid"] == A and found[0]["avatar"] == NEW, found)
    check("5b. search exposes only public fields",
          set(found[0]) <= {"uid", "displayName", "email", "photoUrl", "avatar"}, found)
    r = client.get("/users/search", params={"query": f"Elazar Test {tag}"})
    check("5c. search: user without avatar gets default", r.json()[0]["avatar"] == DEFAULT_AVATAR, r.json())

    r = client.get(f"/groups/{gid}/members")
    members = {m["uid"]: m for m in r.json()}
    check("6. group members return saved avatar (B sees A's)",
          r.status_code == 200 and members[A]["avatar"] == NEW and members[B]["avatar"] == DEFAULT_AVATAR, r.json())
    check("6b. members keep memberIds order", [m["uid"] for m in r.json()] == [A, B])
    group_ref.update({"memberIds": [A, B, GHOST]})
    r = client.get(f"/groups/{gid}/members")
    check("6c. member without a profile doc still listed with default avatar",
          r.json()[2] == {"uid": GHOST, "avatar": DEFAULT_AVATAR}, r.json())
    group_ref.update({"memberIds": [A, B]})
    act_as(GHOST)
    check("6d. non-member cannot list members -> 403", client.get(f"/groups/{gid}/members").status_code == 403)

    # 7. B edits B only; A is untouched. There is no way to name another user.
    act_as(B)
    r = client.patch("/users/me/avatar", json={"hairStyle": "hair_5"})
    check("7. B updates own avatar", r.status_code == 200 and stored(B)["avatar"]["hairStyle"] == "hair_5")
    check("7b. A's avatar untouched by B's update", stored(A)["avatar"] == NEW)
    r = client.patch(f"/users/{A}/avatar", json={"hairStyle": "hair_7"})
    check("7c. PATCH /users/{other}/avatar does not exist", r.status_code in (404, 405), r.status_code)
    r = client.patch("/users/me/avatar", json={"uid": A, "hairStyle": "hair_7"})
    check("7d. uid in the body is rejected", r.status_code == 422 and stored(A)["avatar"] == NEW, r.json())

    # 8. invalid values
    for name, body in (("unknown hairStyle", {"hairStyle": "hair_99"}),
                       ("unknown color", {"hairColor": "neon"}),
                       ("android resource id", {"hairStyle": "R.drawable.hair"}),
                       ("glasses not a bool", {"glassesEnabled": "yes"}),
                       ("empty body", {}),
                       ("unknown field", {"photo": "x"}),
                       ("null value only", {"skinTone": None})):
        before = stored(B)
        r = client.patch("/users/me/avatar", json=body)
        check(f"8. {name} -> 422 and nothing written", r.status_code == 422 and stored(B) == before, r.status_code)

    # profile without a users doc
    act_as(GHOST)
    check("GET /users/me without profile doc -> 404", client.get("/users/me").status_code == 404)
    check("PATCH avatar without profile doc -> 404 and nothing created",
          client.patch("/users/me/avatar", json=NEW).status_code == 404
          and not db.collection("users").document(GHOST).get().exists)

    # a damaged stored avatar falls back to defaults instead of failing
    db.collection("users").document(B).update(
        {"avatar": {"hairStyle": "bogus", "glassesEnabled": "x", "skinTone": "skin_6"}})
    act_as(B)
    r = client.get("/users/me")
    check("damaged stored avatar -> valid avatar with defaults",
          r.status_code == 200 and r.json()["avatar"] == {**DEFAULT_AVATAR, "skinTone": "skin_6"}, r.json())

    # no token
    main.app.dependency_overrides.clear()
    check("no token -> 401 on /users/me and PATCH",
          client.get("/users/me").status_code == 401 and client.patch("/users/me/avatar", json=NEW).status_code == 401)
    main.app.dependency_overrides[get_current_uid] = lambda: current["uid"]

    check("group document untouched", group_ref.get().to_dict() == group_before)
finally:
    db.recursive_delete(group_ref)
    for uid in (A, B, GHOST):
        db.collection("users").document(uid).delete()
    main.app.dependency_overrides.clear()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
