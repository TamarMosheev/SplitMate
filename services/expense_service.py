from typing import List

from models.expense_models import ExpenseCreate
from repositories.expense_repository import ExpenseRepository
from services.balance_service import compute_expense_shares
from utils.money import cents_to_amount


class ExpenseValidationError(Exception):
    """The expense is well-formed but inconsistent with the group's members."""


class ExpenseNotFoundError(Exception):
    """There is no such expense in the group."""


class ExpenseService:
    """Business rules for listing and creating a group's expenses."""

    def __init__(self, expense_repository: ExpenseRepository):
        self._expenses = expense_repository

    def list_expenses(self, group_id: str) -> List[dict]:
        return self._expenses.list_by_group(group_id)

    def get_breakdown(self, group_id: str, expense_id: str) -> dict:
        """Who owes the payer what for THIS expense only, before any group-wide netting.

        The shares come from compute_expense_shares, the single place an expense is split (also used by
        balances and settlements), so the cents here always match them exactly. Nothing else is applied:
        no other expense, no paid settlement, no routing. Membership is not consulted, so people who left
        the group still appear. The payer never owes themselves; people whose share is 0 are left out.
        """
        expense = self._expenses.get(group_id, expense_id)
        if expense is None:
            raise ExpenseNotFoundError(f"Expense '{expense_id}' not found")
        split = compute_expense_shares(expense)

        order = list(dict.fromkeys(list(expense.get("participantIds", [])) + sorted(split.shares)))
        items = [
            {"userId": uid, "share": cents_to_amount(split.shares[uid]), "owesTo": split.payer,
             "amountOwed": cents_to_amount(split.shares[uid])}
            for uid in order
            if uid != split.payer and split.shares.get(uid, 0) > 0
        ]
        return {
            "expenseId": expense["id"],
            "description": expense.get("description", ""),
            "paidBy": split.payer,
            "amount": cents_to_amount(split.total),
            "splitType": expense["splitType"],
            "payerShare": cents_to_amount(split.shares.get(split.payer, 0)),
            "items": items,
        }

    def create_expense(self, group: dict, expense: ExpenseCreate) -> dict:
        members = set(group.get("memberIds", []))
        if expense.paidBy not in members:
            raise ExpenseValidationError("paidBy must be a member of the group")

        outsiders = set(expense.participantIds) - members
        if outsiders:
            raise ExpenseValidationError(
                f"participantIds must all be members of the group: {sorted(outsiders)}"
            )

        if expense.splitType == "exact":
            unknown = set(expense.exactAmounts) - set(expense.participantIds)
            if unknown:
                raise ExpenseValidationError(
                    f"exactAmounts users must all be in participantIds: {sorted(unknown)}"
                )

        data = {
            "amount": expense.amount,
            "description": expense.description,
            "paidBy": expense.paidBy,
            "splitType": expense.splitType,
            "participantIds": expense.participantIds,
        }
        if expense.splitType == "exact":
            data["exactAmounts"] = expense.exactAmounts

        return self._expenses.create(group["id"], data)
