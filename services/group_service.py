from typing import Set

from repositories.expense_repository import ExpenseRepository
from repositories.group_repository import GroupRepository
from repositories.notification_repository import NotificationRepository
from repositories.settlement_repository import SettlementRepository
from repositories.user_repository import UserRepository


class GroupNotFoundError(Exception):
    """There is no group with that id."""


class GroupNotAllowedError(Exception):
    """The caller is not allowed to delete the group."""


class NotAMemberError(Exception):
    """The caller is not a member of the group."""


class UserNotFoundError(Exception):
    """The user to add is not a registered SplitMate user."""


class AlreadyMemberError(Exception):
    """The user is already a member of the group."""


class InvalidMemberRequestError(Exception):
    """The add-member or user-search request is not valid."""


class GroupService:
    """Group-level operations that touch more than the group document."""

    def __init__(
        self,
        group_repository: GroupRepository,
        expense_repository: ExpenseRepository,
        settlement_repository: SettlementRepository,
        notification_repository: NotificationRepository,
        user_repository: UserRepository,
    ):
        self._groups = group_repository
        self._expenses = expense_repository
        self._settlements = settlement_repository
        self._notifications = notification_repository
        self._users = user_repository

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

    def add_member(self, group_id: str, requester_uid: str, new_uid: str) -> dict:
        """Add an already-registered user to the group. Any current member may do it (not only the creator).

        Only memberIds changes. Existing expenses, settlements and balances are not touched, so the
        new member starts at 0 and can be chosen as a participant of NEW expenses only.
        """
        new_uid = (new_uid or "").strip()
        if not new_uid:
            raise InvalidMemberRequestError("userId must not be empty")

        def validate_target():
            if self._users.get_public_profile(new_uid) is None:
                raise UserNotFoundError(f"User '{new_uid}' not found")

        try:
            return self._groups.add_member(group_id, requester_uid, new_uid, validate_target)
        except LookupError:
            raise GroupNotFoundError(f"Group '{group_id}' not found")
        except PermissionError:
            raise NotAMemberError("You are not a member of this group")
        except FileExistsError:
            raise AlreadyMemberError("This user is already a member of the group")

    def search_users(self, query: str, limit: int = 20) -> list:
        query = (query or "").strip()
        if len(query) < 2:
            raise InvalidMemberRequestError("query must be at least 2 characters")
        return self._users.search(query, limit)
