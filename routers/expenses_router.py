from fastapi import APIRouter, Depends, HTTPException

from dependencies.group_dependencies import expense_service, get_member_group
from models.expense_models import ExpenseCreate
from services.expense_service import ExpenseValidationError

router = APIRouter(prefix="/groups", tags=["expenses"])


@router.get("/{group_id}/expenses")
def get_group_expenses(group: dict = Depends(get_member_group)):
    return expense_service.list_expenses(group["id"])


@router.post("/{group_id}/expenses")
def create_group_expense(expense: ExpenseCreate, group: dict = Depends(get_member_group)):
    try:
        return expense_service.create_expense(group, expense)
    except ExpenseValidationError as error:
        raise HTTPException(status_code=400, detail=str(error))
