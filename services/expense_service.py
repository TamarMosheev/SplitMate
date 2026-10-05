from typing import List

from models.expense_models import ExpenseCreate
from repositories.expense_repository import ExpenseRepository


class ExpenseValidationError(Exception):
    """The expense is well-formed but inconsistent with the group's members."""


class ExpenseService:
    """Business rules for listing and creating a group's expenses."""

    def __init__(self, expense_repository: ExpenseRepository):
        self._expenses = expense_repository

    def list_expenses(self, group_id: str) -> List[dict]:
        return self._expenses.list_by_group(group_id)

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
