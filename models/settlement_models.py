from datetime import datetime
from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class SettlementOut(BaseModel):
    """A settlement as stored at groups/{groupId}/settlements/{id}."""

    model_config = ConfigDict(populate_by_name=True)

    id: str
    from_: str = Field(alias="from")  # debtor: the one who pays
    to: str  # creditor: the one who receives
    amount: float
    status: Literal["open", "paid"]
    createdAt: Optional[datetime] = None
    paidAt: Optional[datetime] = None
    markedBy: Optional[str] = None
    # Two-step payment confirmation. The debtor reports "I paid" (pending); only the creditor's
    # confirmation makes the settlement paid. A pending claim never changes any balance.
    paymentClaimStatus: Literal["none", "pending", "confirmed", "rejected"] = "none"
    paymentClaimedAt: Optional[datetime] = None
    paymentClaimedBy: Optional[str] = None
    paymentClaimAmount: Optional[float] = None  # the amount the debtor reported paying
    paymentConfirmedAt: Optional[datetime] = None
    paymentConfirmedBy: Optional[str] = None
    paymentRejectedAt: Optional[datetime] = None
    paymentRejectedBy: Optional[str] = None


class SettlementOverview(BaseModel):
    balances: Dict[str, float]
    transfers: List[SettlementOut]  # open settlements still to be paid
    paid: List[SettlementOut]  # settlements already marked paid (history)


class MarkPaidRequest(BaseModel):
    """Optional body for PATCH .../paid.

    expectedAmount is the amount the client showed the user. If the settlement's amount has
    changed since (expenses were added), the server answers 409 instead of marking a
    different sum as paid.
    """

    expectedAmount: Optional[float] = None


class SettlementBreakdownItem(BaseModel):
    """One real record that makes up a settlement.

    relevantAmount is signed from the debtor's point of view: positive = adds to what the debtor
    owes the creditor, negative = reduces it (the creditor owed the debtor on that expense, or an
    earlier payment). The signed amounts of all items add up to the settlement amount.
    """

    type: Literal["expense", "payment"]
    expenseId: Optional[str] = None  # expense rows
    description: Optional[str] = None  # expense rows
    expenseAmount: Optional[float] = None  # expense rows: the whole expense
    payerUid: Optional[str] = None  # expense rows: who paid it
    settlementId: Optional[str] = None  # payment rows: the earlier settlement that was paid
    relevantAmount: float
    createdAt: Optional[datetime] = None  # when the expense was created / the payment was marked paid


class ContributingExpense(BaseModel):
    """Context for a netted settlement: a real expense that changed the balance of the debtor or the creditor.

    This is NOT a decomposition of the settlement amount. The contributions are the expense's effect on
    each person's balance (same sign convention as GET .../balances: positive = is owed money, negative =
    owes money), so they do not add up to the settlement amount.
    """

    expenseId: str
    description: str
    expenseAmount: float
    payerUid: str
    participantUids: List[str]
    fromContribution: float  # effect of this expense on the debtor's balance
    toContribution: float  # effect of this expense on the creditor's balance
    createdAt: Optional[datetime] = None


class SettlementBreakdownResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    settlementId: str
    groupId: str
    from_: str = Field(alias="from")
    to: str
    amount: float
    status: Literal["open", "paid"]
    breakdownAvailable: bool  # true only for an exact breakdown
    # "exact": items explain the amount exactly. "netted": no exact explanation exists, see
    # contributingExpenses for context. "unavailable": nothing was recorded (old paid settlements).
    breakdownType: Literal["exact", "netted", "unavailable"]
    reason: Optional[str] = None  # why there is no exact breakdown (null when exact)
    items: List[SettlementBreakdownItem]  # exact rows; empty unless breakdownType is "exact"
    contributingExpenses: List[ContributingExpense] = []  # context rows; only for breakdownType "netted"
