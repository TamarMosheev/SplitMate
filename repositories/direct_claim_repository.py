from typing import List

from firebase_admin import firestore
from google.api_core.exceptions import AlreadyExists
from google.cloud.firestore import transactional
from google.cloud.firestore_v1.base_query import FieldFilter

from firebase.firebase_service import FirebaseService

CLAIMS = "directPaymentClaims"


class DirectClaimRepository:
    """Firestore access for groups/{groupId}/directPaymentClaims: "I paid" claims on direct debts.

    A claim never changes any balance. It is confirmed by the same atomic batch that records the real payment
    (PaymentRepository.record_direct_payment) or rejected here; each change also writes its notification in the
    same batch, so a claim can never exist without its notification.
    """

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _collection(self, group_id: str):
        return self._firebase.get_db().collection("groups").document(group_id).collection(CLAIMS)

    def list_for_pair(self, group_id: str, debtor: str, creditor: str) -> List[dict]:
        query = (self._collection(group_id)
                 .where(filter=FieldFilter("debtorUid", "==", debtor))
                 .where(filter=FieldFilter("creditorUid", "==", creditor)))
        return [{**doc.to_dict(), "id": doc.id} for doc in query.stream()]

    def create_pending(self, group_id: str, claim_id: str, claim: dict, notification: dict) -> str:
        """Create the claim and notify the creditor atomically. The fixed id makes concurrent duplicates fail ("exists")."""
        db = self._firebase.get_db()
        batch = db.batch()
        batch.create(self._collection(group_id).document(claim_id), {
            **claim, "createdAt": firestore.SERVER_TIMESTAMP, "resolvedAt": None, "resolvedBy": None})
        ref = db.collection("users").document(notification["recipientUid"]).collection("notifications").document()
        batch.create(ref, {**notification, "createdAt": firestore.SERVER_TIMESTAMP})
        try:
            batch.commit()
        except AlreadyExists:
            return "exists"
        return "ok"

    def reject(self, group_id: str, claim_id: str, uid: str, notification: dict) -> str:
        db = self._firebase.get_db()
        ref = self._collection(group_id).document(claim_id)
        transaction = db.transaction()

        @transactional
        def attempt(tx) -> str:
            snap = ref.get(transaction=tx)
            if not snap.exists or snap.to_dict().get("status") != "pending":
                return "not_pending"
            tx.update(ref, {"status": "rejected", "resolvedAt": firestore.SERVER_TIMESTAMP, "resolvedBy": uid})
            n_ref = db.collection("users").document(notification["recipientUid"]).collection("notifications").document()
            tx.create(n_ref, {**notification, "createdAt": firestore.SERVER_TIMESTAMP})
            return "ok"

        return attempt(transaction)
