from typing import Optional

from repositories.payment_repository import PaymentRepository
from repositories.settlement_repository import SettlementRepository
from repositories.user_repository import UserRepository
from services.notification_service import FALLBACK_NAME
from services.settlement_service import (
    SettlementNotAllowedError,
    SettlementNotFoundError,
    SettlementService,
    StaleSettlementError,
    stale_settlement_message,
)
from utils.money import format_cents, to_cents


class PaymentStateError(Exception):
    """The settlement is in a state where this payment action does not apply (paid, already pending, nothing pending)."""


class PaymentService:
    """The two-step payment confirmation.

    1. The DEBTOR reports "I paid" (claim_payment): the settlement stays open, the creditor is notified.
    2. The CREDITOR confirms (confirm_payment): only now does the settlement become paid (and only now do
       balances change), and the debtor is notified. The creditor may instead reject the claim.
    The creditor can also mark a debt paid directly (mark_paid), e.g. cash received without any claim;
    that is the same confirmation, so it also resolves a pending claim and notifies the debtor.

    Who acts is always the authenticated uid; the other person, the amount and the notification are
    derived from the stored settlement, never from the client.
    """

    def __init__(
        self,
        settlement_service: SettlementService,
        settlement_repository: SettlementRepository,
        payment_repository: PaymentRepository,
        user_repository: UserRepository,
    ):
        self._settlement_service = settlement_service
        self._settlements = settlement_repository
        self._payments = payment_repository
        self._users = user_repository

    # ---------------------------------------------------------------- helpers
    def _load(self, group_id: str, settlement_id: str) -> dict:
        settlement = self._settlement_service.get_settlement_by_id(group_id, settlement_id)
        if settlement is None:
            raise SettlementNotFoundError(stale_settlement_message(group_id, settlement_id))
        return settlement

    def _name(self, uid: str) -> str:
        return self._users.get_display_name(uid) or FALLBACK_NAME

    @staticmethod
    def _notification(kind: str, group_id: str, settlement: dict, sender: str, recipient: str, message: str) -> dict:
        return {
            "type": kind,
            "recipientUid": recipient,
            "senderUid": sender,
            "groupId": group_id,
            "settlementId": settlement["id"],
            "amount": settlement["amount"],
            "message": message,
            "isRead": False,
            "readAt": None,
        }

    @staticmethod
    def _check_expected(group_id: str, settlement: dict, expected_amount: Optional[float]) -> None:
        if expected_amount is not None and to_cents(expected_amount) != to_cents(settlement["amount"]):
            raise StaleSettlementError(
                f"The amount of this settlement changed from {expected_amount} to {settlement['amount']}. "
                f"Refetch GET /groups/{group_id}/settlement and confirm the latest amount."
            )

    def _result(self, outcome: str, group_id: str, settlement_id: str) -> dict:
        """Turn a repository outcome into the updated settlement, or the right error."""
        if outcome == "ok":
            return self._settlements.get(group_id, settlement_id)
        if outcome == "missing":
            raise SettlementNotFoundError(stale_settlement_message(group_id, settlement_id))
        if outcome == "not_open":
            raise PaymentStateError("This settlement has already been paid")
        if outcome == "already_pending":
            raise PaymentStateError("A payment claim for this settlement is already pending confirmation")
        if outcome == "no_pending_claim":
            raise PaymentStateError("There is no pending payment claim for this settlement")
        if outcome == "amount_changed":
            raise StaleSettlementError(
                "The amount of this settlement changed since the payment was claimed. Reject the claim, "
                "or ask the other person to report the payment again for the current amount."
            )
        raise RuntimeError(f"unexpected outcome {outcome!r}")

    # ---------------------------------------------------------------- debtor
    def claim_payment(
        self, group: dict, settlement_id: str, caller_uid: str, expected_amount: Optional[float] = None
    ) -> dict:
        """The debtor reports having paid. The settlement stays open and no balance changes."""
        group_id = group["id"]
        settlement = self._load(group_id, settlement_id)
        if caller_uid != settlement["from"]:
            raise SettlementNotAllowedError("Only the person who owes this payment can report it as sent")
        if settlement["status"] == "paid":
            raise PaymentStateError("This settlement has already been paid")
        if PaymentRepository.claim_is_current(settlement):
            raise PaymentStateError("A payment claim for this settlement is already pending confirmation")
        self._check_expected(group_id, settlement, expected_amount)
        creditor = settlement["to"]
        if creditor not in group.get("memberIds", []):
            raise PaymentStateError("The creditor is no longer a member of this group")

        message = f"תשלום של ₪{format_cents(to_cents(settlement['amount']))} מ{self._name(caller_uid)} ממתין לאישורך"
        notification = self._notification("payment_claim", group_id, settlement, caller_uid, creditor, message)
        return self._result(self._payments.claim(group_id, settlement_id, caller_uid, notification), group_id, settlement_id)

    # ---------------------------------------------------------------- creditor
    def _complete(
        self, group_id: str, settlement_id: str, caller_uid: str, expected_amount: Optional[float],
        require_claim: bool, denied_message: str,
    ) -> dict:
        settlement = self._load(group_id, settlement_id)
        if caller_uid != settlement["to"]:
            raise SettlementNotAllowedError(denied_message)
        if settlement["status"] == "paid":
            # Repeating the call as the creditor is harmless: the original paidAt/markedBy are kept.
            if require_claim and settlement.get("paymentClaimStatus") != "confirmed":
                raise PaymentStateError("This settlement was paid without a payment claim")
            return settlement
        if require_claim:
            if settlement.get("paymentClaimStatus") != "pending":
                raise PaymentStateError("There is no pending payment claim for this settlement")
            if not PaymentRepository.claim_is_current(settlement):
                return self._result("amount_changed", group_id, settlement_id)
        self._check_expected(group_id, settlement, expected_amount)

        debtor = settlement["from"]
        message = f"התשלום שלך של ₪{format_cents(to_cents(settlement['amount']))} ל{self._name(caller_uid)} אושר"
        notification = self._notification("payment_confirmed", group_id, settlement, caller_uid, debtor, message)
        # What the debt was made of is saved together with the paid status (see SettlementService.build_breakdown).
        breakdown = self._settlement_service.build_breakdown(group_id, settlement)
        outcome = self._payments.confirm(
            group_id, settlement_id, caller_uid, notification, breakdown=breakdown, require_claim=require_claim
        )
        if outcome == "not_open":  # someone else completed it a moment ago
            current = self._settlements.get(group_id, settlement_id)
            if current and current["status"] == "paid":
                return current
        return self._result(outcome, group_id, settlement_id)

    def confirm_payment(
        self, group: dict, settlement_id: str, caller_uid: str, expected_amount: Optional[float] = None
    ) -> dict:
        """The creditor confirms a pending claim: the settlement becomes paid."""
        return self._complete(
            group["id"], settlement_id, caller_uid, expected_amount, require_claim=True,
            denied_message="Only the creditor (the person owed money) can confirm receiving a payment",
        )

    def mark_paid(
        self, group: dict, settlement_id: str, caller_uid: str, expected_amount: Optional[float] = None
    ) -> dict:
        """The creditor marks the debt paid directly (with or without a claim)."""
        return self._complete(
            group["id"], settlement_id, caller_uid, expected_amount, require_claim=False,
            denied_message="Only the creditor (the person owed money) can mark this debt as paid",
        )

    def reject_claim(self, group: dict, settlement_id: str, caller_uid: str) -> dict:
        """The creditor says the payment was not received. The settlement stays open."""
        group_id = group["id"]
        settlement = self._load(group_id, settlement_id)
        if caller_uid != settlement["to"]:
            raise SettlementNotAllowedError("Only the creditor (the person owed money) can reject a payment claim")
        if settlement["status"] == "paid":
            raise PaymentStateError("This settlement has already been paid")
        if settlement.get("paymentClaimStatus") != "pending":
            raise PaymentStateError("There is no pending payment claim for this settlement")

        message = f"התשלום שלך של ₪{format_cents(to_cents(settlement['amount']))} ל{self._name(caller_uid)} לא אושר"
        notification = self._notification(
            "payment_claim_rejected", group_id, settlement, caller_uid, settlement["from"], message
        )
        return self._result(self._payments.reject(group_id, settlement_id, caller_uid, notification), group_id, settlement_id)
