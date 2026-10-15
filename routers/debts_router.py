from typing import Optional

from fastapi import APIRouter, Depends, HTTPException

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import direct_debt_action_service, direct_debt_service
from models.debt_models import DirectDebtClaimResult, DirectDebtDetail, DirectDebtPaid, DirectDebts, MarkDirectDebtPaidRequest
from models.notification_models import NotificationOut
from services.direct_debt_action_service import (
    DirectDebtBadRequestError,
    DirectDebtConflictError,
    DirectDebtNotAllowedError,
    DirectDebtNotFoundError,
)

router = APIRouter(prefix="/debts", tags=["debts"])


@router.get("/receivable", response_model=DirectDebts, response_model_exclude_none=True)
def get_receivable_debts(uid: str = Depends(get_current_uid)):
    """"חייבים לך": everyone who directly owes the signed-in user money because the user paid for them.

    Built from the expenses of all the user's groups (not from settlement transfers): each person's share
    of every expense the user paid, opposite direct debts between the same two people netted, and payments
    actually made between exactly those two people subtracted. A person is listed even if their overall
    group balance is positive. 401: not signed in.
    """
    return direct_debt_service.receivable(uid)


@router.get("/payable", response_model=DirectDebts, response_model_exclude_none=True)
def get_payable_debts(uid: str = Depends(get_current_uid)):
    """"את חייבת": everyone the signed-in user directly owes money because they paid for the user. Same model."""
    return direct_debt_service.payable(uid)


@router.get("/receivable/{other_uid}", response_model=DirectDebtDetail, response_model_exclude_none=True)
def get_receivable_debt_detail(other_uid: str, uid: str = Depends(get_current_uid)):
    """Opens one row of /debts/receivable: what `other_uid` directly owes the signed-in user, per group, with the
    real expenses/payments behind it. 404 when there is no such direct debt (it may have just been paid)."""
    return _detail(uid, True, other_uid)


@router.get("/payable/{other_uid}", response_model=DirectDebtDetail, response_model_exclude_none=True)
def get_payable_debt_detail(other_uid: str, uid: str = Depends(get_current_uid)):
    """Opens one row of /debts/payable: what the signed-in user directly owes `other_uid`. Same model."""
    return _detail(uid, False, other_uid)


def _direct_action(action):
    try:
        return action()
    except DirectDebtNotAllowedError as error:
        raise HTTPException(status_code=403, detail=str(error))
    except DirectDebtNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))
    except DirectDebtBadRequestError as error:
        raise HTTPException(status_code=400, detail=str(error))
    except DirectDebtConflictError as error:
        raise HTTPException(status_code=409, detail=str(error))


@router.patch("/direct/{debtor_uid}/{creditor_uid}/paid", response_model=DirectDebtPaid)
def mark_direct_debt_paid(
    debtor_uid: str,
    creditor_uid: str,
    groupId: Optional[str] = None,
    allGroups: bool = False,
    body: Optional[MarkDirectDebtPaidRequest] = None,
    uid: str = Depends(get_current_uid),
):
    """"סמן כשולם" for a direct debt. Only the creditor (`creditor_uid` must be the signed-in user) may.

    The amount is recomputed server-side from the real expenses; nothing is read from the client except the
    optional `expectedAmount`. The payment is stored as a paid record per group (expenses are never touched)
    and the debtor gets a `payment_confirmed` notification. If the debt spans several groups, pass `groupId`
    to pay one group, or `allGroups=true` to pay every group; otherwise 400.
    403: not the creditor. 404: no such debt / group. 409: already paid, or `expectedAmount` is stale.
    """
    return _direct_action(lambda: direct_debt_action_service.mark_paid(
        uid, debtor_uid, creditor_uid, groupId, allGroups, body.expectedAmount if body else None))


@router.post("/direct/{debtor_uid}/{creditor_uid}/payment-claim", status_code=201, response_model=DirectDebtClaimResult)
def claim_direct_debt_paid(
    debtor_uid: str,
    creditor_uid: str,
    groupId: Optional[str] = None,
    allGroups: bool = False,
    body: Optional[MarkDirectDebtPaidRequest] = None,
    uid: str = Depends(get_current_uid),
):
    """"שילמתי": the DEBTOR says they paid. Only `debtor_uid` == the signed-in user may.

    Creates a pending claim (per group) for the amount currently owed and notifies the creditor
    (`payment_claim`). The debt does NOT change until the creditor confirms. Scope rules as in `/paid`.
    403: not the debtor. 404: no such debt. 409: a claim is already pending, or `expectedAmount` is stale.
    """
    return _direct_action(lambda: direct_debt_action_service.claim_paid(
        uid, debtor_uid, creditor_uid, groupId, allGroups, body.expectedAmount if body else None))


@router.patch("/direct/{debtor_uid}/{creditor_uid}/confirm-payment", response_model=DirectDebtPaid)
def confirm_direct_debt_claim(
    debtor_uid: str, creditor_uid: str, groupId: Optional[str] = None, allGroups: bool = False,
    uid: str = Depends(get_current_uid),
):
    """The CREDITOR confirms the pending claim: the claimed amount becomes a real payment, the debt drops, the
    debtor gets `payment_confirmed`. 403: not the creditor. 409: no pending claim. `/paid` also confirms a pending claim."""
    return _direct_action(lambda: direct_debt_action_service.confirm_claim(uid, debtor_uid, creditor_uid, groupId, allGroups))


@router.patch("/direct/{debtor_uid}/{creditor_uid}/reject-payment-claim", response_model=DirectDebtClaimResult)
def reject_direct_debt_claim(
    debtor_uid: str, creditor_uid: str, groupId: Optional[str] = None, allGroups: bool = False,
    uid: str = Depends(get_current_uid),
):
    """The CREDITOR says the payment was not received: claim -> rejected, debt unchanged, debtor gets
    `payment_claim_rejected` and may claim again. 403: not the creditor. 409: no pending claim."""
    return _direct_action(lambda: direct_debt_action_service.reject_claim(uid, debtor_uid, creditor_uid, groupId, allGroups))


@router.post("/direct/{debtor_uid}/{creditor_uid}/reminder", status_code=201, response_model=NotificationOut)
def send_direct_debt_reminder(
    debtor_uid: str,
    creditor_uid: str,
    groupId: Optional[str] = None,
    uid: str = Depends(get_current_uid),
):
    """"שלח תזכורת" for a direct debt. Only the creditor may; the debtor is the recipient.

    Without `groupId`, one reminder covers every group where the debtor still owes (and is still a member).
    Cooldown: 24h per scope, like settlement reminders. 403: not the creditor. 404: no such debt.
    400: the debtor left every group with this debt. 409: already paid, or reminded within 24h.
    """
    return _direct_action(lambda: direct_debt_action_service.send_reminder(uid, debtor_uid, creditor_uid, groupId))


def _detail(uid: str, owed_to_me: bool, other_uid: str) -> dict:
    detail = direct_debt_service.details(uid, owed_to_me, other_uid)
    if not detail:
        raise HTTPException(status_code=404, detail="No direct debt with this person")
    return detail
