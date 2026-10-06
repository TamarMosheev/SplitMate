from typing import Optional

from fastapi import APIRouter, Depends, HTTPException

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import (
    balance_service,
    get_member_group,
    group_repository,
    group_service,
    notification_service,
    payment_service,
    settlement_service,
)
from models.group_models import GroupDeleted
from models.notification_models import NotificationOut
from models.settlement_models import (
    MarkPaidRequest,
    SettlementBreakdownResponse,
    SettlementOut,
    SettlementOverview,
)
from services.notification_service import (
    InvalidReminderStateError,
    ReminderCooldownError,
    ReminderNotAllowedError,
    SettlementAlreadyPaidError,
)
from services.group_service import GroupNotAllowedError, GroupNotFoundError
from services.payment_service import PaymentStateError
from services.settlement_service import SettlementNotAllowedError, SettlementNotFoundError, StaleSettlementError

router = APIRouter(prefix="/groups", tags=["groups"])


@router.get("")
def get_groups(uid: str = Depends(get_current_uid)):
    return group_repository.list_by_member(uid)


@router.get("/{group_id}")
def get_group(group: dict = Depends(get_member_group)):
    return group


@router.delete("/{group_id}", response_model=GroupDeleted)
def delete_group(group_id: str, uid: str = Depends(get_current_uid)):
    """Permanently delete a group and all of its data. Only the group's creator (`createdBy`) may.

    Deletes the group, its expenses, its settlements (including paid history) and every notification
    about it. 403: the caller did not create the group. 404: no such group.
    """
    try:
        group_service.delete_group(group_id, uid)
    except GroupNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))
    except GroupNotAllowedError as error:
        raise HTTPException(status_code=403, detail=str(error))
    return {"success": True, "groupId": group_id}


@router.get("/{group_id}/balances")
def get_group_balances(group: dict = Depends(get_member_group)):
    return balance_service.calculate_balances(group["id"])


@router.get("/{group_id}/settlement", response_model=SettlementOverview)
def get_group_settlement(group: dict = Depends(get_member_group)):
    """Current open settlements (`transfers`) and paid history (`paid`).

    Ids of open settlements are only stable while the group's debts don't change. Clients must
    refetch this endpoint and act on the latest id; reminder/paid reject stale ids (404/409).
    """
    return settlement_service.get_settlement(group["id"])


@router.get("/{group_id}/settlements/{settlement_id}/breakdown", response_model=SettlementBreakdownResponse)
def get_settlement_breakdown(settlement_id: str, group: dict = Depends(get_member_group)):
    """The real expenses behind one settlement ("ממה זה מורכב").

    Same visibility as GET .../settlement: any group member. `breakdownAvailable` is false (with a
    `reason` and no items) when the settlement is a netted obligation that cannot be attributed
    to individual expenses. For a paid settlement this is the snapshot saved when it was paid.
    """
    try:
        return settlement_service.get_breakdown(group["id"], settlement_id)
    except SettlementNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))


def _payment_action(action):
    """Run a payment action and turn its business errors into HTTP errors."""
    try:
        return action()
    except SettlementNotFoundError as error:  # unknown settlement, or the id is not current
        raise HTTPException(status_code=404, detail=str(error))
    except SettlementNotAllowedError as error:  # caller is not the right person for this action
        raise HTTPException(status_code=403, detail=str(error))
    except (StaleSettlementError, PaymentStateError) as error:  # amount changed / wrong state
        raise HTTPException(status_code=409, detail=str(error))


@router.post("/{group_id}/settlements/{settlement_id}/payment-claim", response_model=SettlementOut)
def claim_settlement_payment(
    settlement_id: str,
    body: Optional[MarkPaidRequest] = None,
    group: dict = Depends(get_member_group),
    uid: str = Depends(get_current_uid),
):
    """The DEBTOR reports "I paid" (שלחתי תשלום). Only `from` may.

    The settlement stays open and no balance changes; the creditor gets a `payment_claim` notification
    and must confirm. 403: not the debtor. 404: the id is not current. 409: already paid, a claim is
    already pending, or `expectedAmount` no longer matches.
    """
    return _payment_action(
        lambda: payment_service.claim_payment(group, settlement_id, uid, body.expectedAmount if body else None)
    )


@router.patch("/{group_id}/settlements/{settlement_id}/confirm-payment", response_model=SettlementOut)
def confirm_settlement_payment(
    settlement_id: str,
    body: Optional[MarkPaidRequest] = None,
    group: dict = Depends(get_member_group),
    uid: str = Depends(get_current_uid),
):
    """The CREDITOR confirms receiving the payment (אשר קבלת תשלום). Only `to` may.

    Requires a pending claim. The settlement becomes paid (balances change now) and the debtor gets a
    `payment_confirmed` notification. 403: not the creditor. 409: no pending claim, or the amount changed
    since it was claimed. Repeating it as the creditor returns 200 unchanged.
    """
    return _payment_action(
        lambda: payment_service.confirm_payment(group, settlement_id, uid, body.expectedAmount if body else None)
    )


@router.patch("/{group_id}/settlements/{settlement_id}/reject-payment-claim", response_model=SettlementOut)
def reject_settlement_payment_claim(
    settlement_id: str,
    group: dict = Depends(get_member_group),
    uid: str = Depends(get_current_uid),
):
    """The CREDITOR says the payment was not received. Only `to` may; needs a pending claim.

    The settlement stays open (`paymentClaimStatus: "rejected"`), the debtor gets a
    `payment_claim_rejected` notification and may report the payment again.
    """
    return _payment_action(lambda: payment_service.reject_claim(group, settlement_id, uid))


@router.patch("/{group_id}/settlements/{settlement_id}/paid", response_model=SettlementOut)
def mark_settlement_paid(
    settlement_id: str,
    body: Optional[MarkPaidRequest] = None,
    group: dict = Depends(get_member_group),
    uid: str = Depends(get_current_uid),
):
    """The creditor marks the debt paid directly (e.g. cash received without a claim). Only `to` may.

    Same confirmation as confirm-payment, but no pending claim is required; a pending claim, if there is
    one, is confirmed too, and the debtor gets a `payment_confirmed` notification.
    404: the id is not current (refetch GET .../settlement). 409: its amount changed since
    `expectedAmount` was read. Repeating the call as the creditor returns 200 unchanged.
    """
    return _payment_action(
        lambda: payment_service.mark_paid(group, settlement_id, uid, body.expectedAmount if body else None)
    )


@router.post(
    "/{group_id}/settlements/{settlement_id}/reminder",
    status_code=201,
    response_model=NotificationOut,
)
def send_settlement_reminder(
    settlement_id: str,
    group: dict = Depends(get_member_group),
    uid: str = Depends(get_current_uid),
):
    try:
        return notification_service.send_debt_reminder(group, settlement_id, uid)
    except SettlementNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))
    except ReminderNotAllowedError as error:
        raise HTTPException(status_code=403, detail=str(error))
    except (InvalidReminderStateError,) as error:
        raise HTTPException(status_code=400, detail=str(error))
    except (ReminderCooldownError, SettlementAlreadyPaidError) as error:
        raise HTTPException(status_code=409, detail=str(error))
