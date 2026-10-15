"""Live regression for the global direct-debt settlement algorithm (real Firestore, nothing real is written).

Part A: every REAL group is copied (same expense/settlement document ids, so the seeded odd-cent splits are
identical) into a throwaway group; GET /settlement runs the real reconcile on the COPY and the result is
compared with the copy's stored state. The real groups are only read.
Part B: a throwaway group exercises paid settlements, mark paid, reminders, notifications, removed members,
odd-cent splits and the breakdown endpoint through the HTTP API.

Run from the repo root:  python tests/live_settlement_regression.py
"""
import secrets
import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import main  # noqa: E402
from auth.auth_service import get_current_uid  # noqa: E402
from dependencies.group_dependencies import balance_service, settlement_service  # noqa: E402
from firebase.firebase_service import firebase_service  # noqa: E402
from utils.money import cents_to_amount, to_cents  # noqa: E402

db = firebase_service.get_db()
current = {"uid": None}
main.app.dependency_overrides[get_current_uid] = lambda: current["uid"]
client = TestClient(main.app)
results = []
out = lambda *a: print(*a, flush=True)  # noqa: E731
names = {}


def check(name, condition, info=""):
    results.append(bool(condition))
    out(f"[{'PASS' if condition else 'FAIL'}] {name} {str(info)[:170] if not condition else ''}")


def nm(uid):
    if uid not in names:
        d = db.collection("users").document(uid).get().to_dict() or {}
        names[uid] = d.get("name") or d.get("displayName") or uid[:6]
    return names[uid]


def act_as(uid):
    current["uid"] = uid


def validate(label, gid):
    """The invariants of a reconciled group, read back through the API."""
    s = client.get(f"/groups/{gid}/settlement").json()
    bal = {u: to_cents(v) for u, v in client.get(f"/groups/{gid}/balances").json().items()}
    open_ = [(t["from"], t["to"], to_cents(t["amount"])) for t in s["transfers"]]
    check(f"{label}: balances sum to exactly 0", sum(bal.values()) == 0)
    check(f"{label}: no duplicate/contradictory open pairs", len({(d, c) for d, c, _ in open_}) == len(open_)
          and not ({d for d, _, _ in open_} & {c for _, c, _ in open_}))
    check(f"{label}: each debtor pays exactly their debt, each creditor receives exactly what they are owed",
          all(sum(x for d, _, x in open_ if d == u) == (-b if b < 0 else 0) and
              sum(x for _, c, x in open_ if c == u) == (b if b > 0 else 0) for u, b in bal.items()))
    check(f"{label}: API balances == settlement.balances", {u: v for u, v in s["balances"].items()} ==
          client.get(f"/groups/{gid}/balances").json())
    return s, bal, open_


# ============================================================ Part A: real groups, copied
out("=" * 72 + "\nPART A - real groups (copied, real data untouched)\n" + "=" * 72)
copies = []
try:
    for real in db.collection("groups").stream():
        g = real.to_dict()
        if not g.get("memberIds"):
            continue
        out(f"\n### {g.get('name')}  ({real.id})")
        real_ref = db.collection("groups").document(real.id)
        copy_ref = db.collection("groups").document()
        copies.append(copy_ref)
        copy_ref.set(g)
        for sub in ("expenses", "settlements"):
            for doc in real_ref.collection(sub).stream():
                copy_ref.collection(sub).document(doc.id).set(doc.to_dict())
        gid = copy_ref.id
        act_as(g["memberIds"][0])

        paid_before = {d.id: d.to_dict() for d in copy_ref.collection("settlements").stream() if d.to_dict()["status"] == "paid"}
        open_before = sorted((nm(d.to_dict()["from"]), nm(d.to_dict()["to"]), d.to_dict()["amount"])
                             for d in copy_ref.collection("settlements").stream() if d.to_dict()["status"] == "open")

        s, bal, open_ = validate(g.get("name"), gid)
        pairs = balance_service.calculate_pair_cents(gid)
        out("  balances:", {nm(u): cents_to_amount(c) for u, c in sorted(bal.items(), key=lambda x: -x[1])})
        out("  direct pair obligations:", sorted((nm(d), nm(c), cents_to_amount(x)) for (d, c), x in pairs.items()))
        out("  open settlements BEFORE (stored):", open_before)
        out("  open settlements AFTER  (global algorithm):",
            sorted((nm(d), nm(c), cents_to_amount(x), "direct" if (d, c) in pairs else "RESIDUAL/netted") for d, c, x in open_))
        paid_after = {d.id: d.to_dict() for d in copy_ref.collection("settlements").stream() if d.to_dict()["status"] == "paid"}
        check(f"{g.get('name')}: paid settlements byte-identical after reconcile", paid_after == paid_before)
        real_open_unchanged = sorted(
            (d.id, d.to_dict()["amount"]) for d in real_ref.collection("settlements").stream()
            if d.to_dict()["status"] == "open")
        check(f"{g.get('name')}: REAL group's own stored settlements untouched (copy only)",
              len(real_open_unchanged) == len(open_before))
        # every open settlement id is stable and every transfer retrievable + explained by the breakdown endpoint
        for t in s["transfers"]:
            r = client.get(f"/groups/{gid}/settlements/{t['id']}/breakdown")
            check(f"{g.get('name')}: breakdown {nm(t['from'])}->{nm(t['to'])} {t['amount']} ok", r.status_code == 200
                  and (not r.json()["breakdownAvailable"] or abs(sum(i["relevantAmount"] for i in r.json()["items"]) - t["amount"]) < 0.005),
                  r.text)
            out(f"     breakdown {nm(t['from'])}->{nm(t['to'])}: {r.json()['breakdownType']}")
        s2 = client.get(f"/groups/{gid}/settlement").json()
        check(f"{g.get('name')}: reconcile is idempotent (same ids and amounts)",
              [(t['id'], t['amount']) for t in s2['transfers']] == [(t['id'], t['amount']) for t in s['transfers']])
finally:
    for ref in copies:
        db.recursive_delete(ref)

# ============================================================ Part B: scenario regression
out("\n" + "=" * 72 + "\nPART B - paid settlements, mark paid, reminders, removed members, odd cents, breakdown\n" + "=" * 72)
tag = secrets.token_hex(4)
A, B, C, R = (f"test_{n}_{tag}" for n in ("A", "B", "C", "R"))
group_ref = db.collection("groups").document()
gid = group_ref.id
try:
    for uid, name in ((A, "Alef"), (B, "Bet"), (C, "Gimel"), (R, "Removed")):
        db.collection("users").document(uid).set({"name": name, "email": f"{uid}@example.com"})
    group_ref.set({"name": "regression", "createdBy": A, "memberIds": [A, B, C, R]})
    act_as(A)

    def add(**body):
        return client.post(f"/groups/{gid}/expenses", json=body).json()

    add(amount=350, description="odd", paidBy=A, splitType="equal", participantIds=[A, B, C])  # 350/3
    add(amount=300, description="r", paidBy=A, splitType="equal", participantIds=[A, R, C])
    s, bal, open_ = validate("B: odd-cent + removed-member scenario", gid)
    direct = balance_service.calculate_pair_cents(gid)
    check("B: every transfer here is a direct obligation", all((d, c) in direct for d, c, _ in open_), open_)
    check("B: odd-cent shares exact (no drift)", sum(bal.values()) == 0 and all(x > 0 for _, _, x in open_))
    by_pair = {(t["from"], t["to"]): t for t in s["transfers"]}

    # --- breakdown (direct debt -> exact)
    t_ba = by_pair[(B, A)]
    r = client.get(f"/groups/{gid}/settlements/{t_ba['id']}/breakdown")
    check("breakdown endpoint: B->A is exact and adds up", r.status_code == 200 and r.json()["breakdownAvailable"]
          and abs(sum(i["relevantAmount"] for i in r.json()["items"]) - t_ba["amount"]) < 0.005, r.text)

    # --- debtor claims, creditor confirms
    act_as(B)
    r = client.post(f"/groups/{gid}/settlements/{t_ba['id']}/payment-claim", json={"expectedAmount": t_ba["amount"]})
    check("claim: debtor reports payment -> 200", r.status_code == 200, r.text)
    act_as(A)
    r = client.patch(f"/groups/{gid}/settlements/{t_ba['id']}/confirm-payment", json={"expectedAmount": t_ba["amount"]})
    check("confirm: creditor confirms -> paid", r.status_code == 200 and r.json()["status"] == "paid", r.text)
    s2 = client.get(f"/groups/{gid}/settlement").json()
    check("paid settlement is history, no longer an open debt", all((t["from"], t["to"]) != (B, A) for t in s2["transfers"])
          and any(t["id"] == t_ba["id"] and t["status"] == "paid" for t in s2["paid"]))
    check("paid amount reduced B's balance exactly", abs(s2["balances"][B]) < 0.005, s2["balances"])
    validate("B: after a paid settlement", gid)
    act_as(B)
    check("notification: debtor got payment_confirmed", any(
        n["type"] == "payment_confirmed" and n["groupId"] == gid for n in client.get("/notifications").json()))

    # --- mark paid directly (cash), for the removed-member debt, AFTER removing them
    act_as(A)
    r = client.delete(f"/groups/{gid}/members/{R}")
    check("remove R from the group -> 200", r.status_code == 200, r.text)
    s3 = client.get(f"/groups/{gid}/settlement").json()
    t_ra = {(t["from"], t["to"]): t for t in s3["transfers"]}.get((R, A))
    check("removed member's historical debt is still an open transfer", t_ra is not None, s3["transfers"])
    r = client.post(f"/groups/{gid}/settlements/{t_ra['id']}/reminder")
    check("reminder to a removed debtor -> 400", r.status_code == 400, r.text)
    r = client.get(f"/groups/{gid}/settlements/{t_ra['id']}/breakdown")
    check("breakdown of the removed member's debt still works", r.status_code == 200, r.text)
    t_ca = {(t["from"], t["to"]): t for t in s3["transfers"]}[(C, A)]
    r = client.post(f"/groups/{gid}/settlements/{t_ca['id']}/reminder")
    check("reminder to a current debtor -> 201", r.status_code == 201, r.text)
    act_as(C)
    check("notification: debtor got debt_reminder", any(
        n["type"] == "debt_reminder" and n["groupId"] == gid for n in client.get("/notifications").json()))
    act_as(A)
    r = client.patch(f"/groups/{gid}/settlements/{t_ra['id']}/paid", json={"expectedAmount": t_ra["amount"]})
    check("mark paid for the removed member's debt -> paid", r.status_code == 200 and r.json()["status"] == "paid", r.text)
    s4, bal4, open4 = validate("B: after removal + mark paid", gid)
    check("only C->A remains open", [(d, c) for d, c, _ in open4] == [(C, A)], open4)
    r = client.get(f"/groups/{gid}/settlements/{t_ra['id']}/breakdown")
    check("breakdown of a PAID settlement returns its saved snapshot", r.status_code == 200 and r.json()["status"] == "paid", r.text)

    # --- a new expense changes the debts: paid history survives, open ones are reconciled
    add(amount=90, description="later", paidBy=C, splitType="equal", participantIds=[A, C])
    s5, bal5, open5 = validate("B: after a later expense", gid)
    check("paid settlements still present after reconcile", {t["id"] for t in s5["paid"]} >= {t_ba["id"], t_ra["id"]})
finally:
    try:
        from dependencies.group_dependencies import group_service
        group_service.delete_group(gid, A)  # also deletes the notifications created above
    except Exception:
        db.recursive_delete(group_ref)
    for uid in (A, B, C, R):
        db.collection("users").document(uid).delete()

out(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
