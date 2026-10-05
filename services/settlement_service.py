from typing import Dict, List

from services.balance_service import BalanceService


class SettlementService:
    """Turns balances into the transfers needed to settle a group's debts."""

    def __init__(self, balance_service: BalanceService):
        self._balances = balance_service

    def settle_debts(self, balances: Dict[str, float]) -> List[dict]:
        """Greedy matching: the biggest debtor pays the biggest creditor first.

        Works in whole cents to avoid floating point drift.
        """
        cents = {user_id: round(balance * 100) for user_id, balance in balances.items()}
        creditors = sorted(((u, c) for u, c in cents.items() if c > 0), key=lambda x: -x[1])
        debtors = sorted(((u, -c) for u, c in cents.items() if c < 0), key=lambda x: -x[1])

        transfers = []
        i = j = 0
        while i < len(debtors) and j < len(creditors):
            debtor, owed = debtors[i]
            creditor, due = creditors[j]
            paid = min(owed, due)
            transfers.append({"from": debtor, "to": creditor, "amount": paid / 100})

            debtors[i] = (debtor, owed - paid)
            creditors[j] = (creditor, due - paid)
            if debtors[i][1] == 0:
                i += 1
            if creditors[j][1] == 0:
                j += 1
        return transfers

    def get_settlement(self, group_id: str) -> dict:
        balances = self._balances.calculate_balances(group_id)
        return {"balances": balances, "transfers": self.settle_debts(balances)}
