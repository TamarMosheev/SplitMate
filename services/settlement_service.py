from datetime import datetime, timezone
from typing import Dict, List, Optional, Tuple

from repositories.settlement_repository import SettlementRepository
from services.balance_service import BalanceService
from utils.money import cents_to_amount, to_cents

NETTED_REASON = (
    "This settlement is a netted obligation across multiple participants and cannot be "
    "uniquely attributed to individual expenses."
)
TYPE_EXACT, TYPE_NETTED, TYPE_UNAVAILABLE = "exact", "netted", "unavailable"
NOT_RECORDED_REASON = "No breakdown was recorded when this settlement was paid."
INCONSISTENT_SNAPSHOT_REASON = "The breakdown recorded when this settlement was paid is inconsistent and is not shown."
_EPOCH = datetime.min.replace(tzinfo=timezone.utc)


def stale_settlement_message(group_id: str, settlement_id: str) -> str:
    return (
        f"Settlement '{settlement_id}' is not current: it no longer exists because the group's debts "
        f"changed. Refetch GET /groups/{group_id}/settlement and use the latest settlement id."
    )


class SettlementNotFoundError(Exception):
    """No current settlement with that id exists in the group (unknown or stale id)."""


class SettlementNotAllowedError(Exception):
    """The caller is not allowed to perform this action on the settlement."""


class StaleSettlementError(Exception):
    """The settlement exists but changed since the client read it (e.g. its amount)."""


class SettlementService:
    """Turns balances into persistent settlements and tracks which of them are paid.

    Action flow for clients (the ids of OPEN settlements are only stable while the group's
    debts don't change, so never act on a cached id):
      1. GET /groups/{group_id}/settlement  -> the current open settlements and their ids
      2. act on one of those ids (send a reminder / mark it paid)
    Every action re-reconciles first, so an id that is no longer current is rejected with a
    clear 404 (it vanished) or 409 (it changed, or is already paid) instead of acting on stale data.
    """

    def __init__(self, balance_service: BalanceService, settlement_repository: SettlementRepository):
        self._balances = balance_service
        self._settlements = settlement_repository

    @staticmethod
    def settle_cents(balances_cents: Dict[str, int]) -> List[Tuple[str, str, int]]:
        """Greedy matching in integer cents: the biggest debtor pays the biggest creditor first.

        Ties are broken by user id, so the result never depends on dict ordering.
        Returns (debtor, creditor, cents) tuples. If the balances add up to 0 the debts are
        settled exactly.
        """
        creditors = sorted(((u, c) for u, c in balances_cents.items() if c > 0), key=lambda x: (-x[1], x[0]))
        debtors = sorted(((u, -c) for u, c in balances_cents.items() if c < 0), key=lambda x: (-x[1], x[0]))

        transfers = []
        i = j = 0
        while i < len(debtors) and j < len(creditors):
            debtor, owed = debtors[i]
            creditor, due = creditors[j]
            paid = min(owed, due)
            transfers.append((debtor, creditor, paid))

            debtors[i] = (debtor, owed - paid)
            creditors[j] = (creditor, due - paid)
            if debtors[i][1] == 0:
                i += 1
            if creditors[j][1] == 0:
                j += 1
        return transfers

    def settle_debts(self, balances: Dict[str, float]) -> List[dict]:
        """balances (amounts) -> transfers [{"from", "to", "amount"}], computed in integer cents."""
        cents = {user_id: to_cents(balance) for user_id, balance in balances.items()}
        return [
            {"from": debtor, "to": creditor, "amount": cents_to_amount(amount)}
            for debtor, creditor, amount in self.settle_cents(cents)
        ]

    def reconcile(self, group_id: str) -> None:
        """Bring the stored settlements in line with the group's current debts.

        - Paid settlements are history: never edited, deleted or reopened.
        - Each debt the algorithm currently produces keeps its open settlement (only the amount
          is refreshed); if there is none yet it is created under a deterministic id
          '<debtor>_<creditor>_<n>', n = (paid settlements for that pair) + 1, so concurrent
          requests cannot create duplicates and the id stays the same until it is paid.
        - An open settlement whose debt no longer exists is removed.
        """
        existing = self._settlements.list_all(group_id)
        open_by_pair = {(s["from"], s["to"]): s for s in existing if s["status"] == "open"}
        paid_count: Dict[tuple, int] = {}
        for s in existing:
            if s["status"] == "paid":
                paid_count[(s["from"], s["to"])] = paid_count.get((s["from"], s["to"]), 0) + 1

        for debtor, creditor, cents in self.settle_cents(self._balances.calculate_balance_cents(group_id)):
            pair = (debtor, creditor)
            current = open_by_pair.pop(pair, None)
            if current is not None:
                if to_cents(current["amount"]) != cents:
                    self._settlements.update_open_amount(group_id, current["id"], cents_to_amount(cents))
            else:
                settlement_id = f"{debtor}_{creditor}_{paid_count.get(pair, 0) + 1}"
                self._settlements.create_open(group_id, settlement_id, debtor, creditor, cents_to_amount(cents))

        for obsolete in open_by_pair.values():
            self._settlements.delete_open(group_id, obsolete["id"])

    def get_settlement(self, group_id: str) -> dict:
        self.reconcile(group_id)
        settlements = self._settlements.list_all(group_id)
        open_ones = sorted((s for s in settlements if s["status"] == "open"), key=lambda s: s["id"])
        paid_ones = sorted((s for s in settlements if s["status"] == "paid"), key=lambda s: s["id"])
        return {
            "balances": self._balances.calculate_balances(group_id),
            "transfers": open_ones,
            "paid": paid_ones,
        }

    def get_settlement_by_id(self, group_id: str, settlement_id: str) -> Optional[dict]:
        """The stored settlement (open or paid) after reconciling, or None if the id is not current."""
        self.reconcile(group_id)
        return self._settlements.get(group_id, settlement_id)

    def build_breakdown(self, group_id: str, settlement: dict) -> dict:
        """Explain a settlement with the real expenses behind it - or say honestly that it can't be.

        Settlements come from netting every member's total balance, so in general a transfer is not
        tied to particular expenses (A owes B and B owes C  ->  A pays C, and no expense links A to C).
        What IS exact is the DIRECT obligation between the two people: for every expense, what the
        debtor owes the creditor (creditor paid) minus what the creditor owes the debtor (debtor paid),
        minus payments already made between them. When that direct amount equals the settlement
        amount, the settlement is fully explained by those real records. When it does not, the
        difference comes from netting through other participants and no breakdown is returned.

        Everything is integer cents; the signed amounts of the returned items add up to the
        settlement amount exactly.

        Returns {"available", "type", "reason", "items", "contributingExpenses"}:
        - exact:  available=True,  type="exact",  items = the exact rows, contributingExpenses = [].
        - netted: available=False, type="netted", items = [], contributingExpenses = the real expenses
          that changed the debtor's or the creditor's balance (context only, see _contributing_expenses).
        """
        debtor, creditor = settlement["from"], settlement["to"]
        target = to_cents(settlement["amount"])
        rows = []  # (sort key, cents, item)
        all_shares = self._balances.list_expense_shares(group_id)

        for expense_shares in all_shares:
            expense = expense_shares.expense
            if expense_shares.payer == creditor and expense_shares.shares.get(debtor, 0) != 0:
                cents = expense_shares.shares[debtor]  # the debtor's share of what the creditor paid
            elif expense_shares.payer == debtor and expense_shares.shares.get(creditor, 0) != 0:
                cents = -expense_shares.shares[creditor]  # the creditor's share of what the debtor paid
            else:
                continue
            created_at = expense.get("createdAt")
            rows.append(((created_at or _EPOCH, expense["id"]), cents, {
                "type": "expense",
                "expenseId": expense["id"],
                "description": expense.get("description", ""),
                "expenseAmount": cents_to_amount(expense_shares.total),
                "payerUid": expense_shares.payer,
                "settlementId": None,
                "createdAt": created_at,
            }))

        for paid in self._settlements.list_all(group_id):
            if paid["status"] != "paid":
                continue
            if (paid["from"], paid["to"]) == (debtor, creditor):
                cents = -to_cents(paid["amount"])  # the debtor already paid part of it
            elif (paid["from"], paid["to"]) == (creditor, debtor):
                cents = to_cents(paid["amount"])  # the creditor paid the debtor: the debt grows back
            else:
                continue
            paid_at = paid.get("paidAt")
            rows.append(((paid_at or _EPOCH, paid["id"]), cents, {
                "type": "payment",
                "expenseId": None,
                "description": None,
                "expenseAmount": None,
                "payerUid": None,
                "settlementId": paid["id"],
                "createdAt": paid_at,
            }))

        if sum(cents for _, cents, _ in rows) != target:
            return {
                "available": False,
                "type": TYPE_NETTED,
                "reason": NETTED_REASON,
                "items": [],
                "contributingExpenses": self._contributing_expenses(all_shares, debtor, creditor),
            }

        rows.sort(key=lambda row: row[0])
        return {
            "available": True,
            "type": TYPE_EXACT,
            "reason": None,
            "items": [{**item, "relevantAmount": cents_to_amount(cents)} for _, cents, item in rows],
            "contributingExpenses": [],
        }

    @staticmethod
    def _contributing_expenses(all_shares: list, debtor: str, creditor: str) -> List[dict]:
        """Context for a netted settlement: the real expenses that changed the debtor's or creditor's balance.

        Rule: an expense is listed if and only if its effect on the balance of the debtor OR of the
        creditor is not zero, where the effect on a user is (the total, if they paid it) minus (their
        share of it) - the same split the balances use, in integer cents. An expense that leaves both
        balances unchanged (for example someone else paying for themselves only) is not listed.

        The listed contributions are NOT forced to add up to the settlement amount: the settlement is a
        netted result of everyone's balances, so this is context, not an attribution of the debt.
        """
        def effect(expense_shares, uid: str) -> int:
            return (expense_shares.total if expense_shares.payer == uid else 0) - expense_shares.shares.get(uid, 0)

        rows = []
        for expense_shares in all_shares:
            from_effect, to_effect = effect(expense_shares, debtor), effect(expense_shares, creditor)
            if from_effect == 0 and to_effect == 0:
                continue
            expense = expense_shares.expense
            created_at = expense.get("createdAt")
            rows.append(((created_at or _EPOCH, expense["id"]), {
                "expenseId": expense["id"],
                "description": expense.get("description", ""),
                "expenseAmount": cents_to_amount(expense_shares.total),
                "payerUid": expense_shares.payer,
                "participantUids": list(expense.get("participantIds", [])),
                "fromContribution": cents_to_amount(from_effect),
                "toContribution": cents_to_amount(to_effect),
                "createdAt": created_at,
            }))
        rows.sort(key=lambda row: row[0])
        return [item for _, item in rows]

    def get_breakdown(self, group_id: str, settlement_id: str) -> dict:
        """The breakdown of one settlement (open: calculated now; paid: the snapshot taken when it was paid)."""
        settlement = self.get_settlement_by_id(group_id, settlement_id)
        if settlement is None:
            raise SettlementNotFoundError(stale_settlement_message(group_id, settlement_id))

        if settlement["status"] == "paid":
            # The snapshot saved when it was paid: exact rows, or the contributing expenses of a netted debt.
            breakdown = settlement.get("breakdown")
            if breakdown is None:
                breakdown = {"available": False, "type": TYPE_UNAVAILABLE, "reason": NOT_RECORDED_REASON,
                             "items": [], "contributingExpenses": []}
            elif breakdown["available"] and sum(
                to_cents(item["relevantAmount"]) for item in breakdown["items"]
            ) != to_cents(settlement["amount"]):
                breakdown = {"available": False, "type": TYPE_UNAVAILABLE, "reason": INCONSISTENT_SNAPSHOT_REASON,
                             "items": [], "contributingExpenses": []}
            else:  # tolerate snapshots saved before breakdownType/contributingExpenses existed
                breakdown = {
                    **breakdown,
                    "type": breakdown.get("type") or (TYPE_EXACT if breakdown["available"] else TYPE_NETTED),
                    "contributingExpenses": breakdown.get("contributingExpenses", []),
                }
        else:
            breakdown = self.build_breakdown(group_id, settlement)

        return {
            "settlementId": settlement["id"],
            "groupId": group_id,
            "from": settlement["from"],
            "to": settlement["to"],
            "amount": settlement["amount"],
            "status": settlement["status"],
            "breakdownAvailable": breakdown["available"],
            "breakdownType": breakdown["type"],
            "reason": breakdown["reason"],
            "items": breakdown["items"],
            "contributingExpenses": breakdown["contributingExpenses"],
        }
