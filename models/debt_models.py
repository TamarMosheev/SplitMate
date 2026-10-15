from datetime import datetime
from typing import List, Literal, Optional

from pydantic import BaseModel


class DirectDebtGroup(BaseModel):
    groupId: str
    groupName: str
    amount: float


class DirectDebt(BaseModel):
    """One other person and what they directly owe the signed-in user (receivable) or are owed by them (payable)."""

    userId: str
    displayName: Optional[str] = None
    amount: float
    groups: List[DirectDebtGroup]


class DirectDebts(BaseModel):
    total: float
    debts: List[DirectDebt]


class DirectDebtItem(BaseModel):
    """A real record behind one group's amount, signed from the debtor's side (negative = a payment/offset)."""

    type: Literal["expense", "payment"]
    expenseId: Optional[str] = None
    description: Optional[str] = None
    expenseAmount: Optional[float] = None
    payerUid: Optional[str] = None
    settlementId: Optional[str] = None
    relevantAmount: float
    createdAt: Optional[datetime] = None


class DirectDebtDetailGroup(BaseModel):
    groupId: str
    groupName: str
    amount: float
    itemsExact: bool  # false when a payment was clamped, so the items do not add up to `amount`
    items: List[DirectDebtItem]
    canMarkPaid: bool = False  # the signed-in user is the creditor
    canSendReminder: bool = False  # the signed-in user is the creditor and the debtor is still in this group
    canClaimPaid: bool = False  # the signed-in user is the debtor and has no pending claim in this group
    claimPending: bool = False  # the debtor already said "I paid" here; waiting for the creditor
    claimedAmount: Optional[float] = None
    claimId: Optional[str] = None
    claimedAt: Optional[datetime] = None
    canConfirmClaim: bool = False  # creditor: a claim is pending
    canRejectClaim: bool = False


class DirectDebtDetail(BaseModel):
    userId: str
    displayName: Optional[str] = None
    owedToMe: bool
    amount: float
    groups: List[DirectDebtDetailGroup]
    debtorUid: str
    creditorUid: str
    canMarkPaid: bool = False  # only the creditor may mark a direct debt paid
    canSendReminder: bool = False  # only the creditor, and only while the debtor is still in a group with debt
    canClaimPaid: bool = False  # debtor: "שילמתי" is allowed (some group has no pending claim)
    claimPending: bool = False  # some group has a pending claim; show "ממתין לאישור"
    claimedAmount: Optional[float] = None  # sum of the pending claims
    canConfirmClaim: bool = False  # creditor: confirm the pending claim(s)
    canRejectClaim: bool = False
    requiresGroupSelection: bool = False  # several groups: paying needs groupId (or allGroups=true)


class MarkDirectDebtPaidRequest(BaseModel):
    """Optional body for PATCH /debts/direct/{debtor}/{creditor}/paid. expectedAmount = what the client showed;
    if the real amount differs the server answers 409 instead of paying a different sum."""

    expectedAmount: Optional[float] = None


class DirectDebtClaimItem(BaseModel):
    claimId: str
    groupId: str
    groupName: str
    amount: float
    status: Literal["pending", "confirmed", "rejected"]


class DirectDebtClaimResult(BaseModel):
    debtorUid: str
    creditorUid: str
    claimedAmount: float
    claims: List[DirectDebtClaimItem]


class DirectDebtPayment(BaseModel):
    groupId: str
    groupName: str
    paymentId: str  # id of the persisted paid record (groups/{groupId}/settlements/{paymentId})
    amount: float


class DirectDebtPaid(BaseModel):
    debtorUid: str
    creditorUid: str
    paidAmount: float
    payments: List[DirectDebtPayment]
    remainingAmount: float  # what the debtor still directly owes the creditor in ALL of the creditor's groups
