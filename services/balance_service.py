from typing import Dict

from repositories.expense_repository import ExpenseRepository


class BalanceService:
    """Computes each member's net balance from a group's expenses."""

    def __init__(self, expense_repository: ExpenseRepository):
        self._expenses = expense_repository

    def calculate_balances(self, group_id: str) -> Dict[str, float]:
        """Positive = should receive money, negative = owes money."""
        balances: Dict[str, float] = {}
        for expense in self._expenses.list_by_group(group_id):
            amount = expense["amount"]
            participants = expense["participantIds"]

            if expense["splitType"] == "exact":
                shares = expense["exactAmounts"]
            else:
                shares = {user_id: amount / len(participants) for user_id in participants}

            balances[expense["paidBy"]] = balances.get(expense["paidBy"], 0) + amount
            for user_id, share in shares.items():
                balances[user_id] = balances.get(user_id, 0) - share

        return {user_id: round(balance, 2) for user_id, balance in balances.items()}
