from fastapi import Depends, HTTPException

from auth.auth_service import get_current_uid
from firebase.firebase_service import firebase_service
from repositories.expense_repository import ExpenseRepository
from repositories.group_repository import GroupRepository
from repositories.notification_repository import NotificationRepository
from repositories.payment_repository import PaymentRepository
from repositories.settlement_repository import SettlementRepository
from repositories.user_repository import UserRepository
from services.balance_service import BalanceService
from services.expense_service import ExpenseService
from services.group_service import GroupService
from services.notification_service import NotificationService
from services.payment_service import PaymentService
from services.settlement_service import SettlementService

# Shared instances, wired once: firebase -> repositories -> services.
group_repository = GroupRepository(firebase_service)
expense_repository = ExpenseRepository(firebase_service)
user_repository = UserRepository(firebase_service)
notification_repository = NotificationRepository(firebase_service)
settlement_repository = SettlementRepository(firebase_service)
balance_service = BalanceService(expense_repository, settlement_repository)
settlement_service = SettlementService(balance_service, settlement_repository)
expense_service = ExpenseService(expense_repository)
notification_service = NotificationService(notification_repository, user_repository, settlement_service)
group_service = GroupService(group_repository, expense_repository, settlement_repository, notification_repository, user_repository)
payment_repository = PaymentRepository(firebase_service)
payment_service = PaymentService(settlement_service, settlement_repository, payment_repository, user_repository)


def get_member_group(group_id: str, uid: str = Depends(get_current_uid)) -> dict:
    """Return the group, only if it exists (404) and the caller is a member (403)."""
    group = group_repository.get_by_id(group_id)
    if group is None:
        raise HTTPException(status_code=404, detail=f"Group '{group_id}' not found")
    if uid not in group.get("memberIds", []):
        raise HTTPException(status_code=403, detail="You are not a member of this group")
    return group
