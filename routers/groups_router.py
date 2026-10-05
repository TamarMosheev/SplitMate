from fastapi import APIRouter, Depends

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import (
    balance_service,
    get_member_group,
    group_repository,
    settlement_service,
)

router = APIRouter(prefix="/groups", tags=["groups"])


@router.get("")
def get_groups(uid: str = Depends(get_current_uid)):
    return group_repository.list_by_member(uid)


@router.get("/{group_id}")
def get_group(group: dict = Depends(get_member_group)):
    return group


@router.get("/{group_id}/balances")
def get_group_balances(group: dict = Depends(get_member_group)):
    return balance_service.calculate_balances(group["id"])


@router.get("/{group_id}/settlement")
def get_group_settlement(group: dict = Depends(get_member_group)):
    return settlement_service.get_settlement(group["id"])
