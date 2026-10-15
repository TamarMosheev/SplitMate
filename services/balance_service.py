from dataclasses import dataclass
from typing import Dict, List, Tuple

from repositories.expense_repository import ExpenseRepository
from repositories.settlement_repository import SettlementRepository
from utils.money import cents_to_amount, split_cents_equally, to_cents


@dataclass(frozen=True)
class ExpenseShares:
    """One expense in integer cents: who paid, the total, and each participant's share.

    The shares always add up to `total`. This is the single place where an expense is split,
    used by both the balances and the settlement breakdown, so they can never disagree.
    """

    expense: dict
    payer: str
    total: int
    shares: Dict[str, int]


def compute_expense_shares(expense: dict) -> ExpenseShares:
    total = to_cents(expense["amount"])
    payer = expense["paidBy"]

    if expense["splitType"] == "exact":
        shares = {user_id: to_cents(value) for user_id, value in expense["exactAmounts"].items()}
        # Exact amounts that don't add up to the total are absorbed by the payer, so the
        # expense always balances (no money appears or disappears).
        shares[payer] = shares.get(payer, 0) + (total - sum(shares.values()))
    else:
        shares = split_cents_equally(total, expense["participantIds"], seed=expense["id"])

    return ExpenseShares(expense=expense, payer=payer, total=total, shares=shares)


class BalanceService:
    """Computes each member's net balance: expenses, minus payments already marked paid.

    Everything is calculated in integer cents, so a group's balances always add up to exactly 0.
    """

    def __init__(self, expense_repository: ExpenseRepository, settlement_repository: SettlementRepository):
        self._expenses = expense_repository
        self._settlements = settlement_repository

    def list_expense_shares(self, group_id: str) -> List[ExpenseShares]:
        return [compute_expense_shares(expense) for expense in self._expenses.list_by_group(group_id)]

    def calculate_balance_cents(self, group_id: str) -> Dict[str, int]:
        """Positive = should receive money, negative = owes money (in cents)."""
        balances: Dict[str, int] = {}

        def add(user_id: str, cents: int) -> None:
            balances[user_id] = balances.get(user_id, 0) + cents

        for expense_shares in self.list_expense_shares(group_id):
            add(expense_shares.payer, expense_shares.total)
            for user_id, share in expense_shares.shares.items():
                add(user_id, -share)

        # A settlement marked paid is a real payment: the debtor owes less, the creditor is owed less.
        # The expenses themselves are never touched.
        for settlement in self._settlements.list_all(group_id):
            if settlement["status"] == "paid":
                cents = to_cents(settlement["amount"])
                add(settlement["from"], cents)
                add(settlement["to"], -cents)

        return balances

    def _signed_pairs(self, group_id: str) -> Tuple[Dict[Tuple[str, str], int], Dict[Tuple[str, str], int]]:
        """(from expenses, from payments marked paid), each keyed (a, b) with a < b: positive = a owes b.

        Expenses: every participant other than the payer owes the payer their share, summed per pair, with
        opposite directions between the same two people netted. Payments: a settlement marked paid from
        `f` to `t` counts as +amount in the direction "f paid t" (the same sign convention).
        Membership is never consulted, so people who left or were removed keep their obligations.
        """
        def add(target: Dict[Tuple[str, str], int], debtor: str, creditor: str, cents: int) -> None:
            if debtor == creditor or cents == 0:
                return
            key, sign = ((debtor, creditor), 1) if debtor < creditor else ((creditor, debtor), -1)
            target[key] = target.get(key, 0) + sign * cents

        expenses: Dict[Tuple[str, str], int] = {}
        payments: Dict[Tuple[str, str], int] = {}
        for expense_shares in self.list_expense_shares(group_id):
            for user_id, share in expense_shares.shares.items():
                add(expenses, user_id, expense_shares.payer, share)
        for settlement in self._settlements.list_all(group_id):
            if settlement["status"] == "paid":
                add(payments, settlement["from"], settlement["to"], to_cents(settlement["amount"]))
        return expenses, payments

    @staticmethod
    def _oriented(signed: Dict[Tuple[str, str], int]) -> Dict[Tuple[str, str], int]:
        pairs: Dict[Tuple[str, str], int] = {}
        for (a, b), cents in signed.items():
            if cents > 0:
                pairs[(a, b)] = cents
            elif cents < 0:
                pairs[(b, a)] = -cents
        return pairs

    def calculate_pair_cents(self, group_id: str) -> Dict[Tuple[str, str], int]:
        """Direct net obligations between pairs of people, in cents: {(debtor, creditor): cents}, all > 0.

        Built from the same shares and payments as calculate_balance_cents, so the two always agree:
        for every person, (what others owe them) - (what they owe others) == their balance. Every paid
        settlement counts in full (this is what the group settlement is computed from).
        """
        expenses, payments = self._signed_pairs(group_id)
        return self._oriented(
            {key: expenses.get(key, 0) - payments.get(key, 0) for key in set(expenses) | set(payments)}
        )

    def calculate_direct_debts_cents(self, group_id: str) -> Dict[Tuple[str, str], int]:
        """Outstanding DIRECT debts between pairs, {(debtor, creditor): cents}, all > 0. Never routed through
        a third person and not tied to the settlement algorithm.

        Per pair: what the expenses make one owe the other (opposite directions netted), minus what was
        actually paid between exactly those two people in the same direction, never below 0. A payment
        that does not match a direct debt (the other direction, or more than is owed: e.g. a paid
        settlement that settled a debt netted through other people) cannot be attributed to this pair's
        expenses safely, so it is ignored here instead of inventing a debt in the other direction.
        """
        expenses, payments = self._signed_pairs(group_id)
        remaining: Dict[Tuple[str, str], int] = {}
        for key, owed in expenses.items():
            after = owed - payments.get(key, 0)
            remaining[key] = max(0, min(owed, after)) if owed > 0 else min(0, max(owed, after))
        return self._oriented(remaining)

    def direct_debt_rows(self, group_id: str, debtor: str, creditor: str) -> List[dict]:
        """The real records behind calculate_direct_debts_cents for ONE pair, signed from the debtor's side
        (positive = adds to what `debtor` owes `creditor`). Expenses: the debtor's share of what the creditor
        paid, minus the creditor's share of what the debtor paid. Payments: settlements marked paid from
        exactly `debtor` to `creditor` (negative). Same inputs as the amount, so the rows add up to it
        unless that amount was clamped (a payment larger than the direct debt).
        """
        rows: List[dict] = []
        for es in self.list_expense_shares(group_id):
            if es.payer == creditor and es.shares.get(debtor, 0) != 0:
                cents = es.shares[debtor]
            elif es.payer == debtor and es.shares.get(creditor, 0) != 0:
                cents = -es.shares[creditor]
            else:
                continue
            rows.append({"type": "expense", "expenseId": es.expense["id"],
                         "description": es.expense.get("description", ""),
                         "expenseAmount": cents_to_amount(es.total), "payerUid": es.payer,
                         "relevantCents": cents, "createdAt": es.expense.get("createdAt")})
        for paid in self._settlements.list_all(group_id):
            if paid["status"] == "paid" and (paid["from"], paid["to"]) == (debtor, creditor):
                rows.append({"type": "payment", "settlementId": paid["id"],
                             "relevantCents": -to_cents(paid["amount"]), "createdAt": paid.get("paidAt")})
        return rows

    def calculate_balances(self, group_id: str) -> Dict[str, float]:
        """Same as calculate_balance_cents, as amounts (e.g. 233.34) for the API."""
        return {user_id: cents_to_amount(cents) for user_id, cents in self.calculate_balance_cents(group_id).items()}
