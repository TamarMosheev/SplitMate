from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel


class NotificationOut(BaseModel):
    """A notification as stored at users/{recipientUid}/notifications/{id}."""

    id: str
    # debt_reminder: creditor -> debtor. payment_claim: debtor -> creditor ("I paid", awaiting confirmation).
    # payment_confirmed / payment_claim_rejected: creditor -> debtor, the answer to a payment claim.
    type: Literal["debt_reminder", "payment_claim", "payment_confirmed", "payment_claim_rejected"]
    recipientUid: str
    senderUid: str
    groupId: Optional[str] = None  # null for a direct-debt reminder that covers several groups
    settlementId: Optional[str] = None  # null for a direct-debt reminder (a direct debt has no settlement)
    claimId: Optional[str] = None  # direct-debt claim notifications: the claim they are about
    amount: float
    message: str
    isRead: bool
    createdAt: Optional[datetime] = None
    readAt: Optional[datetime] = None


class UnreadCount(BaseModel):
    count: int


class MarkAllReadResult(BaseModel):
    updated: int


class NotificationDeleted(BaseModel):
    success: bool
    notificationId: str
