from fastapi import Depends, HTTPException

from auth.auth_service import get_current_uid
from firebase.firebase_service import firebase_service
from repositories.expense_repository import ExpenseRepository
from repositories.group_repository import GroupRepository
from services.balance_service import BalanceService
from services.expense_service import ExpenseService
from services.settlement_service import SettlementService

# Shared instances, wired once: firebase -> repositories -> services.
group_repository = GroupRepository(firebase_service)
expense_repository = ExpenseRepository(firebase_service)
balance_service = BalanceService(expense_repository)
settlement_service = SettlementService(balance_service)
expense_service = ExpenseService(expense_repository)


def get_member_group(group_id: str, uid: str = Depends(get_current_uid)) -> dict:
    """Return the group, only if it exists (404) and the caller is a member (403)."""
    group = group_repository.get_by_id(group_id)
    if group is None:
        raise HTTPException(status_code=404, detail=f"Group '{group_id}' not found")
    if uid not in group.get("memberIds", []):
        raise HTTPException(status_code=403, detail="You are not a member of this group")
    return group
