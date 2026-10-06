from dataclasses import dataclass
from typing import Dict, List

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

    def calculate_balances(self, group_id: str) -> Dict[str, float]:
        """Same as calculate_balance_cents, as amounts (e.g. 233.34) for the API."""
        return {user_id: cents_to_amount(cents) for user_id, cents in self.calculate_balance_cents(group_id).items()}
