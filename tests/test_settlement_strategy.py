"""Offline tests for the direct-obligation settlement strategy (no Firestore).
Run: python tests/test_settlement_strategy.py
"""
import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.balance_service import BalanceService  # noqa: E402
from services.settlement_service import SettlementService  # noqa: E402

G = "g1"


class FakeExpenses:
    def __init__(self, expenses):
        self.expenses = expenses

    def list_by_group(self, group_id):
        return list(self.expenses)


class FakeSettlements:
    def __init__(self, settlements):
        self.settlements = settlements

    def list_all(self, group_id):
        return list(self.settlements)


def equal(eid, amount, paid_by, participants):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "equal", "participantIds": participants}


def exact(eid, amount, paid_by, amounts):
    return {"id": eid, "amount": amount, "paidBy": paid_by, "splitType": "exact",
            "participantIds": list(amounts), "exactAmounts": amounts}


def paid(sid, frm, to, amount):
    return {"id": sid, "from": frm, "to": to, "amount": amount, "status": "paid"}


def build(expenses, settlements=()):
    balances = BalanceService(FakeExpenses(expenses), FakeSettlements(list(settlements)))
    return balances, SettlementService(balances, FakeSettlements(list(settlements)))


def transfers(expenses, settlements=()):
    balances, service = build(expenses, settlements)
    return {(d, c): cents for d, c, cents in service.current_transfers(G)}


class SettlementInvariants(unittest.TestCase):
    def assert_valid(self, expenses, settlements=()):
        """The rules every result must satisfy, whatever the expenses are."""
        balances, service = build(expenses, settlements)
        result = service.current_transfers(G)
        bal = balances.calculate_balance_cents(G)
        self.assertEqual(sum(bal.values()), 0, "balances must sum to zero")
        pairs = [(d, c) for d, c, _ in result]
        self.assertEqual(len(pairs), len(set(pairs)), "no duplicate pair")
        self.assertTrue(all(cents > 0 for _, _, cents in result))
        senders, receivers = {d for d, _, _ in result}, {c for _, c, _ in result}
        self.assertFalse(senders & receivers, "nobody both pays and receives (so no cycles or chains)")
        for user, balance in bal.items():
            out = sum(cents for d, _, cents in result if d == user)
            incoming = sum(cents for _, c, cents in result if c == user)
            self.assertEqual(out, -balance if balance < 0 else 0, f"{user} outgoing must equal their debt")
            self.assertEqual(incoming, balance if balance > 0 else 0, f"{user} incoming must equal what they are owed")
        # the pair data and the balances are two views of the same history
        derived = {}
        for (d, c), cents in balances.calculate_pair_cents(G).items():
            derived[d] = derived.get(d, 0) - cents
            derived[c] = derived.get(c, 0) + cents
        self.assertEqual({u: v for u, v in derived.items() if v}, {u: v for u, v in bal.items() if v})
        return {(d, c): cents for d, c, cents in result}


class Cases(SettlementInvariants):
    def test_1_payer_for_three_keeps_both_debtors_direct(self):
        self.assertEqual(self.assert_valid([equal("e1", 300, "A", ["A", "B", "C"])]),
                         {("B", "A"): 10000, ("C", "A"): 10000})

    def test_2_later_expense_reduces_only_that_pair(self):
        result = self.assert_valid([equal("e1", 300, "A", ["A", "B", "C"]), equal("e2", 60, "B", ["A", "B"])])
        self.assertEqual(result, {("B", "A"): 7000, ("C", "A"): 10000})  # B owed 100, A owes B 30

    def test_3_multiple_creditors_and_debtors_keep_every_direct_relationship(self):
        result = self.assert_valid([equal("e1", 300, "A", ["A", "B", "C"]), equal("e2", 240, "D", ["B", "C", "D"])])
        self.assertEqual(result, {("B", "A"): 10000, ("B", "D"): 8000, ("C", "A"): 10000, ("C", "D"): 8000})

    def test_3b_intermediary_is_netted_only_as_residual(self):
        # B owes A 50 and C owes B 50: B is neither net debtor nor creditor, so only C -> A remains.
        result = self.assert_valid([equal("e1", 100, "A", ["A", "B"]), equal("e2", 100, "B", ["B", "C"])])
        self.assertEqual(result, {("C", "A"): 5000})

    def test_3c_football_shape_each_creditor_gets_their_real_debtors(self):
        # T paid for T,B,C; A paid for everyone. T also owes A 100, so T is net owed only 100: just one of
        # B/C can pay T directly. Every transfer must still be a real direct obligation (no re-routing).
        expenses = [equal("e1", 300, "T", ["T", "B", "C"]), equal("e2", 500, "A", ["A", "T", "B", "C", "D"])]
        result = self.assert_valid(expenses)
        direct = BalanceService(FakeExpenses(expenses), FakeSettlements([])).calculate_pair_cents(G)
        self.assertTrue(set(result) <= set(direct), f"{result} is not within the direct obligations {direct}")
        self.assertEqual(sum(c for (_, to), c in result.items() if to == "A"), 40000)
        self.assertEqual(sum(c for (_, to), c in result.items() if to == "T"), 10000)
        self.assertEqual(result[("D", "A")], 10000)

    def test_4_opposite_obligations_are_netted(self):
        self.assertEqual(self.assert_valid([equal("e1", 100, "A", ["A", "B"]), equal("e2", 100, "B", ["A", "B"])]), {})
        result = self.assert_valid([equal("e1", 100, "A", ["A", "B"]), equal("e2", 20, "B", ["A", "B"])])
        self.assertEqual(result, {("B", "A"): 4000})  # B owes 50, A owes B 10

    def test_5_paid_settlement_leaves_only_the_remaining_debt(self):
        expenses = [equal("e1", 300, "A", ["A", "B", "C"])]
        self.assertEqual(self.assert_valid(expenses, [paid("s1", "B", "A", 100)]), {("C", "A"): 10000})
        self.assertEqual(self.assert_valid(expenses, [paid("s1", "B", "A", 40)]),
                         {("B", "A"): 6000, ("C", "A"): 10000})
        # B overpaid by 30: B is now owed 30 and A is owed 70, both by C (the only net debtor)
        self.assertEqual(self.assert_valid(expenses, [paid("s1", "B", "A", 130)]),
                         {("C", "A"): 7000, ("C", "B"): 3000})

    def test_6_odd_cents_no_drift(self):
        expenses = [equal("e1", 350, "A", ["A", "B", "C"])]
        balances, _ = build(expenses)
        shares = {u: s for u, s in BalanceService(FakeExpenses(expenses), FakeSettlements([])).list_expense_shares(G)[0].shares.items()}
        self.assertEqual(sorted(shares.values()), [11666, 11667, 11667])
        self.assertEqual(sum(shares.values()), 35000)
        result = self.assert_valid(expenses)
        self.assertEqual(result, {("B", "A"): shares["B"], ("C", "A"): shares["C"]})  # each owes exactly their share
        self.assertEqual(sum(balances.calculate_balance_cents(G).values()), 0)

    def test_7_removed_member_keeps_their_debt(self):
        # R left the group long ago; the expenses still name them, and membership is never consulted.
        result = self.assert_valid([equal("e1", 300, "A", ["A", "R", "C"])])
        self.assertEqual(result, {("R", "A"): 10000, ("C", "A"): 10000})

    def test_exact_split_direct(self):
        result = self.assert_valid([exact("e1", 100, "A", {"A": 20, "B": 50, "C": 30})])
        self.assertEqual(result, {("B", "A"): 5000, ("C", "A"): 3000})

    def test_random_histories_always_satisfy_the_invariants(self):
        rng = random.Random(7)
        users = ["A", "B", "C", "D", "E"]
        for round_no in range(300):
            expenses = []
            for i in range(rng.randint(1, 6)):
                people = rng.sample(users, rng.randint(2, 5))
                expenses.append(equal(f"r{round_no}-{i}", rng.randint(1, 100000) / 100, rng.choice(users), people))
            settlements = []
            if rng.random() < 0.5:
                a, b = rng.sample(users, 2)
                settlements.append(paid("p1", a, b, rng.randint(1, 5000) / 100))
            self.assert_valid(expenses, settlements)


class Compatibility(SettlementInvariants):
    def test_breakdown_is_exact_for_a_preserved_direct_debt(self):
        expenses = [equal("e1", 300, "A", ["A", "B", "C"])]
        balances, service = build(expenses)
        settlement = {"id": "B_A_1", "from": "B", "to": "A", "amount": 100.0, "status": "open"}
        breakdown = service.build_breakdown(G, settlement)
        self.assertTrue(breakdown["available"])
        self.assertEqual([item["relevantAmount"] for item in breakdown["items"]], [100.0])

    def test_settle_cents_greedy_is_unchanged(self):
        self.assertEqual(SettlementService.settle_cents({"A": 5000, "B": -3000, "C": -2000}),
                         [("B", "A", 3000), ("C", "A", 2000)])


if __name__ == "__main__":
    unittest.main()
