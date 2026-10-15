"""Offline tests for the direct-debt model behind "חייבים לך" / "את חייבת". Run: python tests/test_direct_debts.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.balance_service import BalanceService  # noqa: E402
from services.direct_debt_service import DirectDebtService  # noqa: E402
from utils.money import to_cents  # noqa: E402


class FakeExpenses:
    def __init__(self, by_group):
        self.by_group = by_group

    def list_by_group(self, group_id):
        return list(self.by_group.get(group_id, []))


class FakeSettlements:
    def __init__(self, by_group=None):
        self.by_group = by_group or {}

    def list_all(self, group_id):
        return list(self.by_group.get(group_id, []))


class FakeGroups:
    def __init__(self, groups):
        self.groups = groups

    def list_by_member(self, uid):
        return [g for g in self.groups if uid in g["memberIds"]]


class FakeUsers:
    def get_display_name(self, uid):
        return f"name-{uid}"


def eq(eid, amount, paid_by, people):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "equal", "participantIds": people}


def paid(frm, to, amount):
    return {"id": f"{frm}_{to}", "from": frm, "to": to, "amount": amount, "status": "paid"}


def service(expenses_by_group, settlements_by_group=None, members=("A", "B", "C", "D", "E")):
    groups = [{"id": gid, "name": f"group-{gid}", "memberIds": list(members)} for gid in expenses_by_group]
    balances = BalanceService(FakeExpenses(expenses_by_group), FakeSettlements(settlements_by_group))
    return DirectDebtService(FakeGroups(groups), balances, FakeUsers())


def amounts(result):
    return {d["userId"]: d["amount"] for d in result["debts"]}


class DirectDebtTest(unittest.TestCase):
    def test_everyone_the_user_paid_for_is_listed_even_with_a_positive_group_balance(self):
        # Ortal paid 500 for five. Tamar also paid 900 for herself, Elazar and Or, so Tamar and Elazar are net
        # POSITIVE in the group - they must still appear as owing Ortal 100 each.
        s = service({"g": [
            eq("e1", 500, "ortal", ["ortal", "or", "ayala", "tamar", "elazar"]),
            eq("e2", 900, "tamar", ["or", "elazar", "tamar"]),
        ]}, members=("ortal", "or", "ayala", "tamar", "elazar"))
        result = s.receivable("ortal")
        self.assertEqual(amounts(result), {"or": 100.0, "ayala": 100.0, "tamar": 100.0, "elazar": 100.0})
        self.assertEqual(result["total"], 400.0)
        self.assertEqual(result["debts"][0]["groups"], [{"groupId": "g", "groupName": "group-g", "amount": 100.0}])
        self.assertEqual(result["debts"][0]["displayName"], f"name-{result['debts'][0]['userId']}")

    def test_only_opposite_debts_between_the_same_two_people_are_netted(self):
        s = service({"g": [eq("e1", 200, "A", ["A", "B"]), eq("e2", 60, "B", ["A", "B"])]})
        self.assertEqual(amounts(s.receivable("A")), {"B": 70.0})  # B owes 100, A owes 30
        self.assertEqual(s.payable("A")["debts"], [])
        self.assertEqual(amounts(s.payable("B")), {"A": 70.0})

    def test_never_netted_through_a_third_person(self):
        # A paid for B (B owes A 50); B paid for C (C owes B 50). The settlement would say C -> A; here it must not.
        s = service({"g": [eq("e1", 100, "A", ["A", "B"]), eq("e2", 100, "B", ["B", "C"])]})
        self.assertEqual(amounts(s.receivable("A")), {"B": 50.0})
        self.assertEqual(amounts(s.receivable("B")), {"C": 50.0})
        self.assertEqual(amounts(s.payable("B")), {"A": 50.0})
        self.assertEqual(s.receivable("C")["debts"], [])

    def test_the_payer_is_not_a_debtor_and_a_non_participant_payer_works(self):
        s = service({"g": [eq("e1", 90, "A", ["B", "C", "D"])]})
        self.assertEqual(amounts(s.receivable("A")), {"B": 30.0, "C": 30.0, "D": 30.0})
        self.assertNotIn("A", amounts(s.receivable("A")))

    def test_odd_cents_use_the_backend_allocation_and_add_up(self):
        s = service({"g": [eq("e1", 350, "A", ["A", "B", "C"])]})
        result = s.receivable("A")
        self.assertEqual(sorted(to_cents(a) for a in amounts(result).values()) in ([11666, 11667], [11667, 11667]), True)
        self.assertEqual(to_cents(result["total"]), sum(to_cents(a) for a in amounts(result).values()))

    def test_amounts_from_several_groups_are_aggregated_per_person(self):
        s = service({"g1": [eq("e1", 100, "A", ["A", "B"])], "g2": [eq("e2", 60, "A", ["A", "B", "C"])]})
        result = s.receivable("A")
        self.assertEqual(amounts(result), {"B": 70.0, "C": 20.0})
        b = next(d for d in result["debts"] if d["userId"] == "B")
        self.assertEqual({g["groupId"]: g["amount"] for g in b["groups"]}, {"g1": 50.0, "g2": 20.0})
        self.assertEqual(result["total"], 90.0)

    def test_payment_between_the_same_two_people_reduces_the_debt(self):
        base = {"g": [eq("e1", 300, "A", ["A", "B", "C"])]}
        self.assertEqual(amounts(service(base, {"g": [paid("B", "A", 40)]}).receivable("A")), {"B": 60.0, "C": 100.0})
        self.assertEqual(amounts(service(base, {"g": [paid("B", "A", 100)]}).receivable("A")), {"C": 100.0})

    def test_a_payment_never_creates_a_debt_in_the_other_direction(self):
        base = {"g": [eq("e1", 300, "A", ["A", "B", "C"])]}
        # B paid A 130 (more than the 100 owed): the excess is not turned into "A owes B 30"
        s = service(base, {"g": [paid("B", "A", 130)]})
        self.assertEqual(amounts(s.receivable("A")), {"C": 100.0})
        self.assertEqual(s.payable("A")["debts"], [])
        # A payment in the wrong direction (A paid B) does not increase what B owes
        self.assertEqual(amounts(service(base, {"g": [paid("A", "B", 50)]}).receivable("A")), {"B": 100.0, "C": 100.0})

    def test_unpaid_open_settlements_do_not_reduce_anything(self):
        base = {"g": [eq("e1", 300, "A", ["A", "B", "C"])]}
        open_settlement = {"id": "x", "from": "B", "to": "A", "amount": 100, "status": "open", "paymentClaimStatus": "pending"}
        self.assertEqual(amounts(service(base, {"g": [open_settlement]}).receivable("A")), {"B": 100.0, "C": 100.0})

    def test_a_removed_member_still_owes(self):
        s = service({"g": [eq("e1", 300, "A", ["A", "gone", "C"])]}, members=("A", "C"))
        self.assertEqual(amounts(s.receivable("A")), {"gone": 100.0, "C": 100.0})

    def test_nothing_owed_gives_an_empty_result(self):
        self.assertEqual(service({"g": [eq("e1", 100, "B", ["A", "B"])]}).receivable("A"), {"total": 0.0, "debts": []})


class DirectDebtDetailTest(unittest.TestCase):
    def test_detail_matches_the_list_amount_and_rows_add_up(self):
        s = service({"g1": [eq("e1", 200, "A", ["A", "B"]), eq("e2", 60, "B", ["A", "B"])],
                     "g2": [eq("e3", 40, "A", ["A", "B"])]},
                    {"g1": [paid("B", "A", 20)]})
        listed = s.receivable("A")["debts"][0]
        d = s.details("A", True, "B")
        self.assertEqual(d["amount"], listed["amount"])
        self.assertEqual([(g["groupId"], g["amount"]) for g in d["groups"]],
                         [(g["groupId"], g["amount"]) for g in listed["groups"]])
        for g in d["groups"]:
            self.assertTrue(g["itemsExact"])
            self.assertEqual(round(sum(i["relevantAmount"] for i in g["items"]), 2), g["amount"])
        g1 = next(g for g in d["groups"] if g["groupId"] == "g1")
        self.assertEqual(sorted(i["type"] for i in g1["items"]), ["expense", "expense", "payment"])

    def test_payable_direction_and_no_debt(self):
        s = service({"g": [eq("e1", 100, "A", ["A", "B"])]})
        self.assertEqual(s.details("B", False, "A")["amount"], 50.0)
        self.assertEqual(s.details("A", False, "B"), {})
        self.assertEqual(s.details("A", True, "nobody"), {})


if __name__ == "__main__":
    unittest.main()
