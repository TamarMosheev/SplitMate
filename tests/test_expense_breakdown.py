"""Offline tests for the per-expense debt breakdown (no Firestore). Run: python tests/test_expense_breakdown.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.balance_service import BalanceService, compute_expense_shares  # noqa: E402
from services.expense_service import ExpenseNotFoundError, ExpenseService  # noqa: E402
from utils.money import to_cents  # noqa: E402


class FakeExpenses:
    def __init__(self, expenses):
        self.by_id = {e["id"]: e for e in expenses}

    def get(self, group_id, expense_id):
        return self.by_id.get(expense_id)

    def list_by_group(self, group_id):
        return list(self.by_id.values())


class NoSettlements:
    def list_all(self, group_id):
        return []


def equal(eid, amount, paid_by, participants):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "equal", "participantIds": participants,
            "description": "x"}


def exact(eid, amount, paid_by, amounts):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "exact", "participantIds": list(amounts),
            "exactAmounts": amounts, "description": "x"}


def breakdown(*expenses, target=None):
    service = ExpenseService(FakeExpenses(list(expenses)))
    return service.get_breakdown("g", (target or expenses[0]["id"]))


class BreakdownTest(unittest.TestCase):
    def assert_adds_up(self, result, expense):
        """payer's own share + everything the others owe == the expense amount, to the cent."""
        total = to_cents(result["payerShare"]) + sum(to_cents(i["share"]) for i in result["items"])
        self.assertEqual(total, to_cents(expense["amount"]))
        self.assertTrue(all(i["owesTo"] == result["paidBy"] and i["amountOwed"] == i["share"] for i in result["items"]))
        self.assertNotIn(result["paidBy"], [i["userId"] for i in result["items"]])

    def test_1_one_payer_five_participants_equal(self):
        e = equal("e1", 500, "ortal", ["ortal", "or", "ayala", "tamar", "elazar"])
        r = breakdown(e)
        self.assertEqual({i["userId"]: i["share"] for i in r["items"]},
                         {"or": 100.0, "ayala": 100.0, "tamar": 100.0, "elazar": 100.0})
        self.assertEqual(r["payerShare"], 100.0)
        self.assertEqual([i["userId"] for i in r["items"]], ["or", "ayala", "tamar", "elazar"])  # participantIds order
        self.assertEqual((r["expenseId"], r["paidBy"], r["amount"], r["splitType"]), ("e1", "ortal", 500.0, "equal"))
        self.assert_adds_up(r, e)

    def test_2_odd_cents_350_over_3_uses_the_backend_allocation(self):
        for expense_id in ("e1", "e2", "e3", "abc123"):  # the seeded extra cents move around between expenses
            e = equal(expense_id, 350, "a", ["a", "b", "c"])
            r = breakdown(e)
            expected = compute_expense_shares(e).shares  # the exact function balances/settlements use
            self.assertEqual(to_cents(r["payerShare"]), expected["a"])
            self.assertEqual({i["userId"]: to_cents(i["share"]) for i in r["items"]},
                             {u: c for u, c in expected.items() if u != "a"})
            self.assertEqual(sorted(expected.values()), [11666, 11667, 11667])
            self.assert_adds_up(r, e)

    def test_3_exact_split_uses_exact_amounts(self):
        e = exact("e1", 100, "a", {"a": 20, "b": 50, "c": 30})
        r = breakdown(e)
        self.assertEqual({i["userId"]: i["share"] for i in r["items"]}, {"b": 50.0, "c": 30.0})
        self.assertEqual(r["payerShare"], 20.0)
        self.assert_adds_up(r, e)

    def test_4_payer_is_also_a_participant_is_not_listed_as_owing(self):
        r = breakdown(equal("e1", 90, "a", ["a", "b", "c"]))
        self.assertEqual([i["userId"] for i in r["items"]], ["b", "c"])
        self.assertEqual(r["payerShare"], 30.0)

    def test_5_payer_not_a_participant(self):
        e = equal("e1", 90, "a", ["b", "c", "d"])  # the backend allows this
        r = breakdown(e)
        self.assertEqual({i["userId"]: i["share"] for i in r["items"]}, {"b": 30.0, "c": 30.0, "d": 30.0})
        self.assertEqual(r["payerShare"], 0.0)
        self.assert_adds_up(r, e)

    def test_5b_exact_payer_absorbs_a_difference_like_the_balances_do(self):
        # a legacy/edited document whose exact amounts don't add up: the server gives the rest to the payer
        e = {**exact("e1", 100, "a", {"b": 50, "c": 30}), "participantIds": ["b", "c"]}
        r = breakdown(e)
        self.assertEqual(r["payerShare"], 20.0)
        self.assert_adds_up(r, e)

    def test_6_removed_historical_member_still_appears(self):
        # nothing about group membership is read: "gone" is a participant like any other
        r = breakdown(equal("e1", 300, "a", ["a", "gone", "c"]))
        self.assertEqual({i["userId"] for i in r["items"]}, {"gone", "c"})

    def test_7_matches_balances_and_ignores_other_expenses(self):
        e1 = equal("e1", 350, "a", ["a", "b", "c"])
        e2 = equal("e2", 1000, "b", ["a", "b", "c"])  # b is owed 666.67 here, a owes b: irrelevant to e1
        r = breakdown(e1, e2, target="e1")
        self.assertEqual({i["userId"] for i in r["items"]}, {"b", "c"})  # b still owes a for THIS expense
        # expense breakdown + balances are the same cents: summing every breakdown reproduces the balances
        service = ExpenseService(FakeExpenses([e1, e2]))
        owed = {}
        for e in (e1, e2):
            result = service.get_breakdown("g", e["id"])
            owed[result["paidBy"]] = owed.get(result["paidBy"], 0) + sum(to_cents(i["share"]) for i in result["items"])
            for item in result["items"]:
                owed[item["userId"]] = owed.get(item["userId"], 0) - to_cents(item["share"])
        balances = BalanceService(FakeExpenses([e1, e2]), NoSettlements()).calculate_balance_cents("g")
        self.assertEqual({u: v for u, v in owed.items() if v}, {u: v for u, v in balances.items() if v})

    def test_unknown_expense(self):
        with self.assertRaises(ExpenseNotFoundError):
            ExpenseService(FakeExpenses([])).get_breakdown("g", "nope")


if __name__ == "__main__":
    unittest.main()
