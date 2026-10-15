from typing import Optional

from firebase_admin import firestore
from google.api_core.exceptions import AlreadyExists
from google.cloud.firestore import transactional

from firebase.firebase_service import FirebaseService
from utils.money import to_cents


class PaymentRepository:
    """Payment state changes of a settlement: claim, confirm, reject.

    Each change updates the settlement document AND creates the notification for the other person in
    ONE Firestore transaction, so a claim can never exist without its notification (or the reverse).
    Every method re-checks the preconditions inside the transaction and returns an outcome string
    ("ok", "missing", "not_open", "already_pending", "no_pending_claim", "amount_changed"), so concurrent
    requests cannot, for example, confirm the same payment twice.
    """

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _settlement_ref(self, group_id: str, settlement_id: str):
        return (
            self._firebase.get_db().collection("groups").document(group_id)
            .collection("settlements").document(settlement_id)
        )

    def _stage_notification(self, transaction, notification: dict) -> None:
        ref = (
            self._firebase.get_db().collection("users").document(notification["recipientUid"])
            .collection("notifications").document()
        )
        transaction.create(ref, {**notification, "createdAt": firestore.SERVER_TIMESTAMP})

    @staticmethod
    def claim_is_current(settlement: dict) -> bool:
        """A pending claim that was made for the settlement's current amount."""
        claimed = settlement.get("paymentClaimAmount")
        return (
            settlement.get("paymentClaimStatus") == "pending"
            and claimed is not None
            and to_cents(claimed) == to_cents(settlement["amount"])
        )

    def record_direct_payment(
        self, group_id: str, payment_id: str, payment: dict, notification: dict, claim_id: Optional[str] = None
    ) -> str:
        """Persist a paid direct debt AND notify the debtor in one atomic batch.

        The payment is stored as a 'paid' record in the group's settlements collection under a fixed id, so
        the existing balance / direct-debt maths subtract it and no expense is touched. `create` fails if the
        id exists, so two concurrent requests cannot both record the same payment. Returns "ok" or "exists".
        """
        db = self._firebase.get_db()
        batch = db.batch()
        batch.create(self._settlement_ref(group_id, payment_id), {
            **payment,
            "createdAt": firestore.SERVER_TIMESTAMP,
            "paidAt": firestore.SERVER_TIMESTAMP,
        })
        notification_ref = db.collection("users").document(notification["recipientUid"]).collection("notifications").document()
        batch.create(notification_ref, {**notification, "createdAt": firestore.SERVER_TIMESTAMP})
        if claim_id is not None:  # the debtor's pending "I paid" claim is confirmed by this very payment
            claim_ref = db.collection("groups").document(group_id).collection("directPaymentClaims").document(claim_id)
            batch.update(claim_ref, {"status": "confirmed", "resolvedAt": firestore.SERVER_TIMESTAMP,
                                     "resolvedBy": payment["markedBy"], "paymentId": payment_id})
        try:
            batch.commit()
        except AlreadyExists:
            return "exists"
        return "ok"

    def claim(self, group_id: str, settlement_id: str, uid: str, notification: dict) -> str:
        """The debtor reports having paid. The settlement stays 'open'; nothing about balances changes."""
        ref = self._settlement_ref(group_id, settlement_id)

        @transactional
        def attempt(transaction) -> str:
            snap = ref.get(transaction=transaction)
            if not snap.exists:
                return "missing"
            data = snap.to_dict()
            if data.get("status") != "open":
                return "not_open"
            if self.claim_is_current(data):
                return "already_pending"
            transaction.update(ref, {
                "paymentClaimStatus": "pending",
                "paymentClaimedAt": firestore.SERVER_TIMESTAMP,
                "paymentClaimedBy": uid,
                "paymentClaimAmount": data["amount"],
                "paymentConfirmedAt": None,
                "paymentConfirmedBy": None,
                "paymentRejectedAt": None,
                "paymentRejectedBy": None,
            })
            self._stage_notification(transaction, {**notification, "amount": data["amount"]})
            return "ok"

        return attempt(self._firebase.get_db().transaction())

    def confirm(
        self,
        group_id: str,
        settlement_id: str,
        uid: str,
        notification: dict,
        breakdown: Optional[dict] = None,
        require_claim: bool = True,
    ) -> str:
        """The creditor confirms receiving the money: the settlement becomes 'paid'.

        With require_claim the settlement must have a pending claim for its current amount. Without it
        (direct "mark as paid") a pending claim, if there is one, is confirmed too. `breakdown` (what
        the debt was made of) is stored with the paid status so the history stays stable.
        """
        ref = self._settlement_ref(group_id, settlement_id)

        @transactional
        def attempt(transaction) -> str:
            snap = ref.get(transaction=transaction)
            if not snap.exists:
                return "missing"
            data = snap.to_dict()
            if data.get("status") != "open":
                return "not_open"
            pending = data.get("paymentClaimStatus") == "pending"
            if require_claim and not pending:
                return "no_pending_claim"
            if require_claim and not self.claim_is_current(data):
                return "amount_changed"
            update = {"status": "paid", "paidAt": firestore.SERVER_TIMESTAMP, "markedBy": uid}
            if breakdown is not None:
                update["breakdown"] = breakdown
            if pending:
                update.update({
                    "paymentClaimStatus": "confirmed",
                    "paymentConfirmedAt": firestore.SERVER_TIMESTAMP,
                    "paymentConfirmedBy": uid,
                })
            transaction.update(ref, update)
            self._stage_notification(transaction, {**notification, "amount": data["amount"]})
            return "ok"

        return attempt(self._firebase.get_db().transaction())

    def reject(self, group_id: str, settlement_id: str, uid: str, notification: dict) -> str:
        """The creditor says the payment was not received. The settlement stays 'open'."""
        ref = self._settlement_ref(group_id, settlement_id)

        @transactional
        def attempt(transaction) -> str:
            snap = ref.get(transaction=transaction)
            if not snap.exists:
                return "missing"
            data = snap.to_dict()
            if data.get("status") != "open":
                return "not_open"
            if data.get("paymentClaimStatus") != "pending":
                return "no_pending_claim"
            transaction.update(ref, {
                "paymentClaimStatus": "rejected",
                "paymentRejectedAt": firestore.SERVER_TIMESTAMP,
                "paymentRejectedBy": uid,
            })
            self._stage_notification(transaction, {**notification, "amount": data["amount"]})
            return "ok"

        return attempt(self._firebase.get_db().transaction())
