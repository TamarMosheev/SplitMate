from fastapi import APIRouter, Depends, HTTPException

from dependencies.group_dependencies import expense_service, get_member_group
from models.expense_models import ExpenseBreakdown, ExpenseCreate
from services.expense_service import ExpenseNotFoundError, ExpenseValidationError

router = APIRouter(prefix="/groups", tags=["expenses"])


@router.get("/{group_id}/expenses")
def get_group_expenses(group: dict = Depends(get_member_group)):
    return expense_service.list_expenses(group["id"])


@router.get("/{group_id}/expenses/{expense_id}/breakdown", response_model=ExpenseBreakdown)
def get_expense_breakdown(expense_id: str, group: dict = Depends(get_member_group)):
    """Who owes the payer what for ONE expense (Expense Details), with the server's exact cent allocation.

    Per expense only: no other expense, no payments, no group-wide netting. Any group member may read it.
    404: no such group or expense. 403: not a member.
    """
    try:
        return expense_service.get_breakdown(group["id"], expense_id)
    except ExpenseNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))


@router.post("/{group_id}/expenses")
def create_group_expense(expense: ExpenseCreate, group: dict = Depends(get_member_group)):
    try:
        return expense_service.create_expense(group, expense)
    except ExpenseValidationError as error:
        raise HTTPException(status_code=400, detail=str(error))
