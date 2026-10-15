"""Offline tests for marking a direct debt paid and reminding about it. Run: python tests/test_direct_debt_actions.py

Firestore is replaced by small in-memory fakes that follow the same contracts as the real repositories
(a fixed-id create fails if the id exists; a reminder is refused inside its cooldown)."""
import copy
import sys
import unittest
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.balance_service import BalanceService  # noqa: E402
from services.direct_debt_action_service import (  # noqa: E402
    DirectDebtActionService,
    DirectDebtBadRequestError,
    DirectDebtConflictError,
    DirectDebtNotAllowedError,
    DirectDebtNotFoundError,
)
from services.direct_debt_service import DirectDebtService  # noqa: E402


class FakeExpenses:
    def __init__(self, by_group):
        self.by_group = by_group

    def list_by_group(self, group_id):
        return list(self.by_group.get(group_id, []))


class FakeSettlements:
    def __init__(self):
        self.by_group = {}

    def list_all(self, group_id):
        return [dict(s) for s in self.by_group.get(group_id, [])]


class FakeGroups:
    def __init__(self, groups):
        self.groups = groups

    def list_by_member(self, uid):
        return [g for g in self.groups if uid in g["memberIds"]]


class FakeUsers:
    def get_display_name(self, uid):
        return f"name-{uid}"


class FakeNotifications:
    def __init__(self):
        self.by_user = {}
        self.minutes = 0  # fake clock

    def add(self, uid, data):
        self.by_user.setdefault(uid, []).append({**data, "id": f"n{sum(map(len, self.by_user.values()))}", "at": self.minutes})

    def create_unless_recent(self, recipient, data, same_as, cooldown):
        for n in self.by_user.get(recipient, []):
            if all(n.get(k) == v for k, v in same_as.items()) and self.minutes - n["at"] < cooldown / timedelta(minutes=1):
                return None
        self.add(recipient, data)
        return {**self.by_user[recipient][-1], "createdAt": None}

    def count_unread(self, uid):
        return sum(1 for n in self.by_user.get(uid, []) if not n["isRead"])


class FakeClaims:
    def __init__(self, notifications):
        self.by_group, self.notifications = {}, notifications

    def list_for_pair(self, group_id, debtor, creditor):
        return [dict(c) for c in self.by_group.get(group_id, []) if (c["debtorUid"], c["creditorUid"]) == (debtor, creditor)]

    def create_pending(self, group_id, claim_id, claim, notification):
        existing = self.by_group.setdefault(group_id, [])
        if any(c["id"] == claim_id for c in existing):
            return "exists"
        existing.append({**claim, "id": claim_id})
        self.notifications.add(notification["recipientUid"], notification)
        return "ok"

    def reject(self, group_id, claim_id, uid, notification):
        claim = next(c for c in self.by_group[group_id] if c["id"] == claim_id)
        if claim["status"] != "pending":
            return "not_pending"
        claim["status"] = "rejected"
        self.notifications.add(notification["recipientUid"], notification)
        return "ok"


class FakePayments:
    def __init__(self, settlements, notifications, claims):
        self.settlements, self.notifications, self.claims = settlements, notifications, claims

    def record_direct_payment(self, group_id, payment_id, payment, notification, claim_id=None):
        existing = self.settlements.by_group.setdefault(group_id, [])
        if any(s["id"] == payment_id for s in existing):
            return "exists"
        existing.append(copy.deepcopy(payment))
        if claim_id:
            next(c for c in self.claims.by_group[group_id] if c["id"] == claim_id)["status"] = "confirmed"
        self.notifications.add(notification["recipientUid"], notification)
        return "ok"


def eq(eid, amount, paid_by, people):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "equal", "participantIds": people}


class Env:
    def __init__(self, expenses_by_group, members_by_group=None):
        self.expenses = {gid: list(v) for gid, v in expenses_by_group.items()}
        members_by_group = members_by_group or {}
        everyone = ["A", "B", "C"]
        groups = [{"id": gid, "name": f"group-{gid}", "memberIds": members_by_group.get(gid, everyone)} for gid in self.expenses]
        self.settlements = FakeSettlements()
        self.notifications = FakeNotifications()
        self.balances = BalanceService(FakeExpenses(self.expenses), self.settlements)
        self.groups = FakeGroups(groups)
        self.claims = FakeClaims(self.notifications)
        self.debts = DirectDebtService(self.groups, self.balances, FakeUsers(), self.claims)
        self.actions = DirectDebtActionService(
            self.groups, self.balances, self.settlements,
            FakePayments(self.settlements, self.notifications, self.claims),
            self.notifications, FakeUsers(), self.claims)


def amounts(result):
    return {d["userId"]: d["amount"] for d in result["debts"]}


class MarkPaidTest(unittest.TestCase):
    def test_creditor_marks_paid_and_receivable_payable_refresh(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        self.assertEqual(amounts(env.debts.receivable("A")), {"B": 100.0, "C": 100.0})
        result = env.actions.mark_paid("A", "B", "A")
        self.assertEqual(result["paidAmount"], 100.0)
        self.assertEqual(result["remainingAmount"], 0.0)
        self.assertEqual(result["payments"][0]["paymentId"], "direct_B_A_1")
        self.assertEqual(amounts(env.debts.receivable("A")), {"C": 100.0})
        self.assertEqual(env.debts.payable("B")["debts"], [])
        self.assertEqual(amounts(env.debts.payable("C")), {"A": 100.0})  # untouched

    def test_debtor_or_stranger_is_rejected_and_nothing_is_written(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        for caller in ("B", "C"):
            with self.assertRaises(DirectDebtNotAllowedError):
                env.actions.mark_paid(caller, "B", "A")
        self.assertEqual(env.settlements.by_group, {})
        self.assertEqual(env.notifications.by_user, {})

    def test_no_debt_is_404_and_a_repeat_is_a_safe_409(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        with self.assertRaises(DirectDebtNotFoundError):
            env.actions.mark_paid("B", "A", "B")  # A owes B nothing
        env.actions.mark_paid("A", "B", "A")
        with self.assertRaises(DirectDebtConflictError):
            env.actions.mark_paid("A", "B", "A")  # duplicate attempt
        self.assertEqual(len(env.settlements.by_group["g"]), 1)  # no second payment, no negative debt
        self.assertEqual(env.notifications.count_unread("B"), 1)

    def test_payment_never_rewrites_expenses(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"]), eq("e2", 90, "B", ["A", "B", "C"])]})
        before = copy.deepcopy(env.expenses)
        env.actions.mark_paid("A", "B", "A")
        self.assertEqual(env.expenses, before)
        payment = env.settlements.by_group["g"][0]
        self.assertEqual((payment["from"], payment["to"], payment["status"], payment["kind"]), ("B", "A", "paid", "direct_debt"))
        self.assertEqual(payment["amount"], 70.0)  # 100 owed by B minus the 30 A owes B
        # the frozen snapshot is the exact real rows, and still adds up
        self.assertEqual(round(sum(i["relevantAmount"] for i in payment["breakdown"]["items"]), 2), 70.0)

    def test_new_expense_after_payment_creates_new_debt_only_for_the_new_amount(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.mark_paid("A", "B", "A")
        env.expenses["g"].append(eq("e2", 60, "A", ["A", "B"]))
        self.assertEqual(amounts(env.debts.receivable("A"))["B"], 30.0)
        env.actions.mark_paid("A", "B", "A")
        self.assertEqual([s["id"] for s in env.settlements.by_group["g"]], ["direct_B_A_1", "direct_B_A_2"])

    def test_expected_amount_guards_against_stale_screens(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        with self.assertRaises(DirectDebtConflictError):
            env.actions.mark_paid("A", "B", "A", expected_amount=90)
        self.assertEqual(env.settlements.by_group, {})
        env.actions.mark_paid("A", "B", "A", expected_amount=100)

    def test_multi_group_requires_an_explicit_scope(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B"])]})
        with self.assertRaises(DirectDebtBadRequestError):
            env.actions.mark_paid("A", "B", "A")
        self.assertEqual(env.settlements.by_group, {})
        # one group only
        result = env.actions.mark_paid("A", "B", "A", group_id="g1")
        self.assertEqual((result["paidAmount"], result["remainingAmount"]), (50.0, 30.0))
        self.assertEqual(env.debts.details("A", True, "B")["groups"][0]["groupId"], "g2")
        # explicit aggregate on what is left
        env.expenses["g1"].append(eq("e3", 20, "A", ["A", "B"]))
        result = env.actions.mark_paid("A", "B", "A", all_groups=True)
        self.assertEqual({p["groupId"]: p["amount"] for p in result["payments"]}, {"g1": 10.0, "g2": 30.0})
        self.assertEqual(result["remainingAmount"], 0.0)
        self.assertEqual(env.notifications.count_unread("B"), 3)

    def test_unknown_group_and_group_without_the_debt(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "C", ["A", "C"])]})
        with self.assertRaises(DirectDebtNotFoundError):
            env.actions.mark_paid("A", "B", "A", group_id="nope")
        with self.assertRaises(DirectDebtNotFoundError):
            env.actions.mark_paid("A", "B", "A", group_id="g2")  # B owes A nothing there
        result = env.actions.mark_paid("A", "B", "A")  # a single group has the debt: no scope needed
        self.assertEqual(result["payments"][0]["groupId"], "g1")

    def test_removed_member_debt_can_still_be_paid_and_old_expenses_stay(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "gone", "C"])]}, {"g": ["A", "C"]})
        self.assertEqual(amounts(env.debts.receivable("A")), {"gone": 100.0, "C": 100.0})
        env.actions.mark_paid("A", "gone", "A")
        self.assertEqual(amounts(env.debts.receivable("A")), {"C": 100.0})
        self.assertEqual(env.notifications.count_unread("gone"), 1)
        self.assertEqual(len(env.expenses["g"]), 1)

    def test_paid_amount_flows_into_group_balances(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.mark_paid("A", "B", "A")
        self.assertEqual(env.balances.calculate_balance_cents("g"), {"A": 10000, "B": 0, "C": -10000})


class ReminderTest(unittest.TestCase):
    def test_reminder_goes_to_the_debtor_with_real_names_and_counts_as_unread(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        sent = env.actions.send_reminder("A", "B", "A")
        self.assertEqual((sent["type"], sent["recipientUid"], sent["senderUid"]), ("debt_reminder", "B", "A"))
        self.assertEqual((sent["groupId"], sent["settlementId"], sent["amount"]), ("g", None, 100.0))
        self.assertIn("name-A", sent["message"])
        self.assertIn("group-g", sent["message"])
        self.assertEqual(env.notifications.count_unread("B"), 1)
        self.assertEqual(env.notifications.count_unread("A"), 0)

    def test_only_the_creditor_can_remind(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        with self.assertRaises(DirectDebtNotAllowedError):
            env.actions.send_reminder("B", "B", "A")
        self.assertEqual(env.notifications.by_user, {})

    def test_cooldown_blocks_a_second_reminder_until_24h_pass(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.send_reminder("A", "B", "A")
        env.notifications.minutes = 23 * 60
        with self.assertRaises(DirectDebtConflictError):
            env.actions.send_reminder("A", "B", "A")
        env.notifications.minutes = 24 * 60 + 1
        env.actions.send_reminder("A", "B", "A")
        self.assertEqual(env.notifications.count_unread("B"), 2)
        # a different debtor is not blocked by B's cooldown
        env.actions.send_reminder("A", "C", "A")

    def test_no_reminder_for_a_paid_debt(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.mark_paid("A", "B", "A")
        with self.assertRaises(DirectDebtConflictError):
            env.actions.send_reminder("A", "B", "A")
        self.assertEqual(env.notifications.count_unread("B"), 1)  # only the payment notice

    def test_multi_group_reminder_is_one_notification_unless_a_group_is_chosen(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B"])]})
        sent = env.actions.send_reminder("A", "B", "A")
        self.assertEqual((sent["groupId"], sent["amount"]), (None, 80.0))
        self.assertIn("2", sent["message"])
        sent = env.actions.send_reminder("A", "B", "A", group_id="g1")  # its own scope, own cooldown
        self.assertEqual((sent["groupId"], sent["amount"]), ("g1", 50.0))
        with self.assertRaises(DirectDebtConflictError):
            env.actions.send_reminder("A", "B", "A", group_id="g1")

    def test_removed_debtor_cannot_be_reminded_but_a_remaining_group_still_can(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B"])]},
                  {"g1": ["A"], "g2": ["A", "B"]})
        with self.assertRaises(DirectDebtBadRequestError):
            env.actions.send_reminder("A", "B", "A", group_id="g1")
        sent = env.actions.send_reminder("A", "B", "A")  # only g2 is remindable
        self.assertEqual((sent["groupId"], sent["amount"]), ("g2", 30.0))


class DetailsFlagsTest(unittest.TestCase):
    def test_flags_for_creditor_debtor_and_removed_debtor(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        creditor_view = env.debts.details("A", True, "B")
        self.assertEqual((creditor_view["canMarkPaid"], creditor_view["canSendReminder"]), (True, True))
        self.assertEqual((creditor_view["debtorUid"], creditor_view["creditorUid"]), ("B", "A"))
        self.assertFalse(creditor_view["requiresGroupSelection"])
        self.assertNotIn("settlementId", creditor_view)
        debtor_view = env.debts.details("B", False, "A")
        self.assertEqual((debtor_view["canMarkPaid"], debtor_view["canSendReminder"]), (False, False))

        gone = Env({"g": [eq("e1", 300, "A", ["A", "gone", "C"])]}, {"g": ["A", "C"]})
        view = gone.debts.details("A", True, "gone")
        self.assertEqual((view["canMarkPaid"], view["canSendReminder"]), (True, False))

    def test_multi_group_requires_selection_and_flags_disappear_once_paid(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B"])]})
        self.assertTrue(env.debts.details("A", True, "B")["requiresGroupSelection"])
        env.actions.mark_paid("A", "B", "A", all_groups=True)
        self.assertEqual(env.debts.details("A", True, "B"), {})  # the router answers 404


def unread_types(env, uid):
    return [n["type"] for n in env.notifications.by_user.get(uid, []) if not n["isRead"]]


class PaymentClaimTest(unittest.TestCase):
    def test_debtor_claims_creditor_is_notified_and_debt_does_not_change(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        result = env.actions.claim_paid("B", "B", "A")
        self.assertEqual((result["claimedAmount"], result["claims"][0]["status"], result["claims"][0]["groupId"]),
                         (100.0, "pending", "g"))
        self.assertEqual(unread_types(env, "A"), ["payment_claim"])
        note = env.notifications.by_user["A"][0]
        self.assertEqual((note["senderUid"], note["amount"], note["claimId"], note["settlementId"]),
                         ("B", 100.0, result["claims"][0]["claimId"], None))
        self.assertIn("name-B", note["message"])
        self.assertEqual(amounts(env.debts.receivable("A")), {"B": 100.0, "C": 100.0})  # unchanged
        self.assertEqual(env.settlements.by_group, {})
        self.assertEqual(env.balances.calculate_balance_cents("g"), {"A": 20000, "B": -10000, "C": -10000})

    def test_creditor_and_unrelated_users_cannot_claim(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        for caller in ("A", "C"):
            with self.assertRaises(DirectDebtNotAllowedError):
                env.actions.claim_paid(caller, "B", "A")
        self.assertEqual(env.claims.by_group, {})
        self.assertEqual(env.notifications.by_user, {})

    def test_claim_on_a_missing_debt_is_rejected(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        with self.assertRaises(DirectDebtNotFoundError):
            env.actions.claim_paid("A", "A", "B")  # A owes B nothing
        with self.assertRaises(DirectDebtNotFoundError):
            env.actions.claim_paid("B", "B", "A", group_id="nope")
        self.assertEqual(env.claims.by_group, {})

    def test_duplicate_pending_claim_is_a_409_and_creates_nothing(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        with self.assertRaises(DirectDebtConflictError):
            env.actions.claim_paid("B", "B", "A")
        self.assertEqual(len(env.claims.by_group["g"]), 1)
        self.assertEqual(env.notifications.count_unread("A"), 1)

    def test_creditor_confirms_debt_drops_and_debtor_is_notified(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        before = copy.deepcopy(env.expenses)
        result = env.actions.confirm_claim("A", "B", "A")
        self.assertEqual((result["paidAmount"], result["remainingAmount"]), (100.0, 0.0))
        self.assertEqual(amounts(env.debts.receivable("A")), {"C": 100.0})
        self.assertEqual(env.debts.payable("B")["debts"], [])
        self.assertEqual(unread_types(env, "B"), ["payment_confirmed"])
        self.assertEqual(env.claims.by_group["g"][0]["status"], "confirmed")
        self.assertEqual(env.expenses, before)
        self.assertEqual(env.settlements.by_group["g"][0]["claimId"], env.claims.by_group["g"][0]["id"])
        with self.assertRaises(DirectDebtConflictError):  # nothing left to confirm
            env.actions.confirm_claim("A", "B", "A")

    def test_only_the_creditor_confirms_or_rejects(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        for caller in ("B", "C"):
            with self.assertRaises(DirectDebtNotAllowedError):
                env.actions.confirm_claim(caller, "B", "A")
            with self.assertRaises(DirectDebtNotAllowedError):
                env.actions.reject_claim(caller, "B", "A")
        self.assertEqual(env.claims.by_group["g"][0]["status"], "pending")

    def test_confirm_without_a_claim_is_409(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        with self.assertRaises(DirectDebtConflictError):
            env.actions.confirm_claim("A", "B", "A")

    def test_reject_keeps_the_debt_notifies_and_allows_a_new_claim(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        env.actions.reject_claim("A", "B", "A")
        self.assertEqual(unread_types(env, "B"), ["payment_claim_rejected"])
        self.assertEqual(amounts(env.debts.receivable("A")), {"B": 100.0, "C": 100.0})
        env.actions.claim_paid("B", "B", "A")  # allowed again
        self.assertEqual([c["status"] for c in env.claims.by_group["g"]], ["rejected", "pending"])
        self.assertEqual(env.claims.by_group["g"][1]["id"], "claim_B_A_2")

    def test_creditor_mark_paid_also_confirms_the_pending_claim(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        env.actions.mark_paid("A", "B", "A")
        self.assertEqual(env.claims.by_group["g"][0]["status"], "confirmed")
        self.assertEqual(amounts(env.debts.receivable("A")), {"C": 100.0})

    def test_debt_that_grew_after_the_claim_only_pays_the_claimed_amount(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        env.actions.claim_paid("B", "B", "A")
        env.expenses["g"].append(eq("e2", 60, "A", ["A", "B"]))  # B now owes 130
        result = env.actions.confirm_claim("A", "B", "A")
        self.assertEqual((result["paidAmount"], result["remainingAmount"]), (100.0, 30.0))

    def test_multi_group_claims_need_a_scope_and_are_tracked_per_group(self):
        env = Env({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B"])]})
        with self.assertRaises(DirectDebtBadRequestError):
            env.actions.claim_paid("B", "B", "A")
        env.actions.claim_paid("B", "B", "A", group_id="g1")
        view = env.debts.details("B", False, "A")
        self.assertTrue(view["claimPending"] and view["canClaimPaid"])  # g2 can still be claimed
        self.assertEqual({g["groupId"]: (g["claimPending"], g["canClaimPaid"]) for g in view["groups"]},
                         {"g1": (True, False), "g2": (False, True)})
        self.assertEqual(view["claimedAmount"], 50.0)
        with self.assertRaises(DirectDebtConflictError):
            env.actions.claim_paid("B", "B", "A", group_id="g1")
        with self.assertRaises(DirectDebtConflictError):
            env.actions.claim_paid("B", "B", "A", all_groups=True)  # g1 is already pending: nothing is created
        self.assertEqual(len(env.claims.by_group.get("g2", [])), 0)
        env.actions.claim_paid("B", "B", "A", group_id="g2")
        with self.assertRaises(DirectDebtBadRequestError):  # two pending claims, no scope
            env.actions.confirm_claim("A", "B", "A")
        env.actions.confirm_claim("A", "B", "A", group_id="g1")
        self.assertEqual(env.debts.details("A", True, "B")["groups"][0]["groupId"], "g2")
        result = env.actions.confirm_claim("A", "B", "A", all_groups=True)
        self.assertEqual((result["paidAmount"], result["remainingAmount"]), (30.0, 0.0))

    def test_details_claim_state_for_debtor_and_creditor(self):
        env = Env({"g": [eq("e1", 300, "A", ["A", "B", "C"])]})
        debtor = env.debts.details("B", False, "A")
        self.assertEqual((debtor["canClaimPaid"], debtor["claimPending"], debtor["claimedAmount"]), (True, False, None))
        self.assertEqual((debtor["canConfirmClaim"], debtor["canMarkPaid"]), (False, False))
        env.actions.claim_paid("B", "B", "A")
        debtor = env.debts.details("B", False, "A")
        self.assertEqual((debtor["canClaimPaid"], debtor["claimPending"], debtor["claimedAmount"]), (False, True, 100.0))
        creditor = env.debts.details("A", True, "B")
        self.assertEqual((creditor["canClaimPaid"], creditor["claimPending"], creditor["claimedAmount"]), (False, True, 100.0))
        self.assertEqual((creditor["canConfirmClaim"], creditor["canRejectClaim"], creditor["canMarkPaid"]), (True, True, True))
        env.actions.reject_claim("A", "B", "A")
        self.assertTrue(env.debts.details("B", False, "A")["canClaimPaid"])


if __name__ == "__main__":
    unittest.main()
