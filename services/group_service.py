from typing import Set

from repositories.expense_repository import ExpenseRepository
from repositories.group_repository import GroupRepository
from repositories.notification_repository import NotificationRepository
from repositories.settlement_repository import SettlementRepository


class GroupNotFoundError(Exception):
    """There is no group with that id."""


class GroupNotAllowedError(Exception):
    """The caller is not allowed to delete the group."""


class GroupService:
    """Group-level operations that touch more than the group document."""

    def __init__(
        self,
        group_repository: GroupRepository,
        expense_repository: ExpenseRepository,
        settlement_repository: SettlementRepository,
        notification_repository: NotificationRepository,
    ):
        self._groups = group_repository
        self._expenses = expense_repository
        self._settlements = settlement_repository
        self._notifications = notification_repository

    def _people_involved(self, group: dict) -> Set[str]:
        """Every uid that could have received a notification about this group: current members, the
        creator, and everyone named in its expenses and settlements (this also covers people who are
        no longer members)."""
        uids = set(group.get("memberIds", []))
        if group.get("createdBy"):
            uids.add(group["createdBy"])
        for expense in self._expenses.list_by_group(group["id"]):
            uids.add(expense.get("paidBy"))
            uids.update(expense.get("participantIds", []))
            uids.update((expense.get("exactAmounts") or {}).keys())
        for settlement in self._settlements.list_all(group["id"]):
            uids.update((settlement.get("from"), settlement.get("to")))
        uids.discard(None)
        return uids

    def delete_group(self, group_id: str, caller_uid: str) -> None:
        """Permanently delete a group and all of its data. Only the person who created it may.

        Deleted: the group document, its expenses, its settlements (including paid history), and every
        notification about the group (in every involved user's inbox). Nothing else is touched.

        Order: notifications first, the group last. If something fails midway the group still exists,
        so repeating the same request finishes the cleanup instead of leaving orphans behind.
        """
        group = self._groups.get_by_id(group_id)
        if group is None:
            raise GroupNotFoundError(f"Group '{group_id}' not found")
        if caller_uid != group.get("createdBy"):
            raise GroupNotAllowedError("Only the person who created the group can delete it")

        for uid in self._people_involved(group):
            self._notifications.delete_for_group(uid, group_id)
        self._groups.delete_with_all_data(group_id)
